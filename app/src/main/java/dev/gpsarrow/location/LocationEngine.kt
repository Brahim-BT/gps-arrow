package dev.gpsarrow.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dev.gpsarrow.core.Fix
import dev.gpsarrow.core.LatLon
import dev.gpsarrow.core.NavigationState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Raw platform location, no Google Play Services.
 *
 * This is the deliberate architectural choice of the whole app: `FusedLocationProviderClient`
 * lives in Play Services, which does not exist on GrapheneOS / LineageOS / de-Googled ROMs —
 * exactly the devices whose owners want an offline navigation app. `LocationManager` is part
 * of AOSP and always present.
 *
 * GPS_PROVIDER is the source of truth. FUSED_PROVIDER (API 31+, the *platform* one, not the
 * Play Services one) is requested opportunistically where present, and NETWORK_PROVIDER is
 * used only to paint a greyed-out last-known position while the GNSS fix is still cold.
 */
class LocationEngine(private val context: Context) {

    private val locationManager: LocationManager? =
        ContextCompat.getSystemService(context, LocationManager::class.java)

    data class Status(
        val gpsEnabled: Boolean,
        val satellitesVisible: Int,
        val satellitesUsed: Int,
    )

    enum class Rate(val intervalMs: Long, val minDistanceM: Float) {
        /** Active navigation. */
        ACTIVE(1_000L, 0f),

        /** Power-saving: the arrow dead-reckons between fixes. Roughly halves the drain. */
        RELAXED(4_000L, 5f),
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun isGpsEnabled(): Boolean =
        locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true

    /**
     * Best available position with no waiting — used to render something the instant the app
     * opens, clearly marked as stale, rather than an empty screen for the 30-90 s a cold GNSS
     * fix can take with no assistance data.
     */
    @SuppressLint("MissingPermission")
    fun lastKnown(): Fix? {
        if (!hasPermission()) return null
        val lm = locationManager ?: return null
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
        return providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.toFix()
    }

    /**
     * Stream of accepted fixes. Fixes worse than [NavigationState.REJECT_ACCURACY_M] never
     * reach the arrow — a confidently wrong arrow is worse than no arrow.
     */
    @SuppressLint("MissingPermission")
    fun fixes(rate: Rate = Rate.ACTIVE): Flow<Fix> = callbackFlow {
        val lm = locationManager
        if (lm == null || !hasPermission()) {
            close()
            return@callbackFlow
        }

        val listener = object : LocationListener {
            // Runs on the main looper via the system's location dispatch: an exception here
            // kills the process. A dropped fix is recoverable; a crash is not.
            override fun onLocationChanged(location: Location) {
                runCatching {
                    val fix = location.toFix(satellitesUsed, satellitesVisible)
                    if (fix.accuracyMeters <= NavigationState.REJECT_ACCURACY_M) trySend(fix)
                }.onFailure { Log.w(TAG, "dropped a location fix", it) }
            }

            // Required on API < 30 devices with older OEM implementations.
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }

        val providers = buildList {
            if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                lm.allProviders.contains(LocationManager.FUSED_PROVIDER)
            ) {
                add(LocationManager.FUSED_PROVIDER)
            }
        }

        providers.forEach { provider ->
            runCatching {
                lm.requestLocationUpdates(
                    provider,
                    rate.intervalMs,
                    rate.minDistanceM,
                    listener,
                    Looper.getMainLooper(),
                )
            }
        }

        awaitClose { runCatching { lm.removeUpdates(listener) } }
    }

    /** Satellite counts, so the acquiring screen can show progress instead of a spinner. */
    @SuppressLint("MissingPermission")
    fun status(): Flow<Status> = callbackFlow {
        val lm = locationManager
        if (lm == null || !hasPermission()) {
            trySend(Status(gpsEnabled = false, satellitesVisible = 0, satellitesUsed = 0))
            awaitClose { }
            return@callbackFlow
        }

        trySend(Status(isGpsEnabled(), 0, 0))

        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                runCatching {
                    var used = 0
                    for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
                    satellitesVisible = status.satelliteCount
                    satellitesUsed = used
                    trySend(Status(isGpsEnabled(), status.satelliteCount, used))
                }.onFailure { Log.w(TAG, "dropped a GNSS status update", it) }
            }
        }

        runCatching { lm.registerGnssStatusCallback(callback, android.os.Handler(Looper.getMainLooper())) }
        awaitClose { runCatching { lm.unregisterGnssStatusCallback(callback) } }
    }

    @Volatile
    private var satellitesVisible: Int = 0

    @Volatile
    private var satellitesUsed: Int = 0

    private companion object {
        const val TAG = "LocationEngine"
    }
}

private fun Location.toFix(used: Int = 0, visible: Int = 0): Fix = Fix(
    position = LatLon(latitude, longitude),
    accuracyMeters = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
    altitudeMeters = if (hasAltitude()) altitude else null,
    speedMps = if (hasSpeed()) speed else null,
    // A bearing of exactly 0 with no speed is Android's "I don't know" value; treat it as such.
    bearingDeg = if (hasBearing() && hasSpeed() && speed > 0.5f) bearing else null,
    // Monotonic clock, so the age of a fix is immune to the user changing the wall clock.
    elapsedMillis = elapsedRealtimeNanos / 1_000_000L,
    satellitesUsed = used,
    satellitesVisible = visible,
    provider = provider ?: "gps",
)
