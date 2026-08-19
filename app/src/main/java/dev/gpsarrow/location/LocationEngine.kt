package dev.gpsarrow.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 * Play Services one) is requested opportunistically where present, but never competes with GPS
 * — see [fixes]. NETWORK_PROVIDER is not used at all: it is the only provider whose error is
 * measured in kilometres, there is no separate low-confidence channel for it to feed, and an
 * app that works with the radio off should not be quietly trilaterating off cell towers.
 */
class LocationEngine(private val context: Context) {

    private val locationManager: LocationManager? =
        ContextCompat.getSystemService(context, LocationManager::class.java)

    data class Status(
        /** The OS location master switch, not this app's permission. */
        val locationEnabled: Boolean,
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

    /**
     * Whether the OS will give anyone a position at all.
     *
     * Distinct from having the runtime permission, and the distinction is the whole point: a
     * user can grant this app location access while the device's location master switch is off,
     * in which case `requestLocationUpdates` succeeds, no callback ever fires, and the app looks
     * like it is searching for satellites forever. That cost a first-time user several minutes
     * standing outside before they worked out what was wrong.
     *
     * `isLocationEnabled` (API 28+) is the real master switch. Below that, GPS_PROVIDER being
     * enabled is the closest available proxy.
     */
    fun isLocationEnabled(): Boolean {
        val lm = locationManager ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm.isLocationEnabled
            } else {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            }
        }.getOrDefault(false)
    }

    /**
     * Best available position with no waiting — used to render something the instant the app
     * opens, clearly marked as stale, rather than an empty screen for the 30-90 s a cold GNSS
     * fix can take with no assistance data.
     *
     * Two rules, both learned the hard way:
     *
     * **Provider priority, not newest-wins.** The old version took whichever of GPS / FUSED /
     * NETWORK had the latest timestamp, so a fresh cell-tower position beat a GPS fix from five
     * minutes ago and landed in the same `Fix` field the arrow and the save button read. A
     * NETWORK position can be a kilometre out. This app would rather say "I don't know" and let
     * the compass carry the screen — which it already does, because the needle never needed a
     * fix — than paint a position it cannot stand behind.
     *
     * **The accuracy gate applies here too.** [fixes] has always rejected anything worse than
     * [NavigationState.REJECT_ACCURACY_M]; this path skipped it entirely, which was the one
     * hole through which a fix the stream would have thrown away could reach the store.
     */
    @SuppressLint("MissingPermission")
    fun lastKnown(): Fix? {
        if (!hasPermission()) return null
        val lm = locationManager ?: return null
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        }
        return providers
            .asSequence()
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .map { it.toFix() }
            .firstOrNull { it.accuracyMeters <= NavigationState.REJECT_ACCURACY_M }
    }

    /**
     * Stream of accepted fixes. Fixes worse than [NavigationState.REJECT_ACCURACY_M] never
     * reach the arrow — a confidently wrong arrow is worse than no arrow.
     *
     * Exactly one provider is authoritative at a time. Both GPS_PROVIDER and FUSED_PROVIDER are
     * *registered*, but they are two independent position solutions for the same instant, and
     * standing still they routinely disagree by the width of the accuracy circle. Merging them
     * into one `Fix` field on a last-writer-wins basis makes the position hop between two points
     * ~10 m apart at the fix rate, which is what made a just-saved waypoint read 10 m away
     * instead of 0 m. GPS is the source of truth exactly as BUILD_PLAN 2.1 says; the platform
     * fused provider only fills in while GPS is silent (cold start, indoors, tunnel).
     */
    @SuppressLint("MissingPermission")
    fun fixes(rate: Rate = Rate.ACTIVE): Flow<Fix> = callbackFlow {
        val lm = locationManager
        if (lm == null || !hasPermission()) {
            close()
            return@callbackFlow
        }

        // Touched only from the main looper (see the Looper argument below), so no volatile.
        // Zero means "GPS has never reported", which lets the fused provider paint the first
        // fix during a cold start and then hands authority to GPS the moment it locks.
        var lastGpsElapsedMs = 0L

        val listener = object : LocationListener {
            // Runs on the main looper via the system's location dispatch: an exception here
            // kills the process. A dropped fix is recoverable; a crash is not.
            override fun onLocationChanged(location: Location) {
                runCatching {
                    val fix = location.toFix(satellitesUsed, satellitesVisible)
                    val now = SystemClock.elapsedRealtime()
                    val isGps = fix.provider == LocationManager.GPS_PROVIDER
                    if (isGps) lastGpsElapsedMs = now

                    val authoritative = isGps || now - lastGpsElapsedMs >= FUSED_FALLBACK_AFTER_MS
                    if (authoritative && fix.accuracyMeters <= NavigationState.REJECT_ACCURACY_M) {
                        trySend(fix)
                    }
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
            trySend(Status(locationEnabled = false, satellitesVisible = 0, satellitesUsed = 0))
            awaitClose { }
            return@callbackFlow
        }

        trySend(Status(isLocationEnabled(), 0, 0))

        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                runCatching {
                    var used = 0
                    for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
                    satellitesVisible = status.satelliteCount
                    satellitesUsed = used
                    trySend(Status(isLocationEnabled(), status.satelliteCount, used))
                }.onFailure { Log.w(TAG, "dropped a GNSS status update", it) }
            }
        }

        // Without this, the enabled flag is sampled once at subscription and never again: no
        // GNSS callback fires while location is off, so nothing would ever re-emit. That is
        // precisely why switching GPS on used to require killing and relaunching the app.
        // PROVIDERS_CHANGED_ACTION is a protected system broadcast, so NOT_EXPORTED is correct.
        val providerChanges = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(Status(isLocationEnabled(), satellitesVisible, satellitesUsed))
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                context,
                providerChanges,
                IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { Log.w(TAG, "could not observe provider changes", it) }

        runCatching { lm.registerGnssStatusCallback(callback, android.os.Handler(Looper.getMainLooper())) }
        awaitClose {
            runCatching { lm.unregisterGnssStatusCallback(callback) }
            runCatching { context.unregisterReceiver(providerChanges) }
        }
    }

    @Volatile
    private var satellitesVisible: Int = 0

    @Volatile
    private var satellitesUsed: Int = 0

    private companion object {
        const val TAG = "LocationEngine"

        /**
         * How long GPS_PROVIDER may go quiet before FUSED_PROVIDER is allowed to take over.
         *
         * Longer than three ACTIVE intervals, so a single dropped fix never causes a handover
         * (and a handover is a visible position jump); short enough that walking indoors leaves
         * the arrow stale for a few seconds rather than a minute.
         */
        const val FUSED_FALLBACK_AFTER_MS = 3_500L
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
