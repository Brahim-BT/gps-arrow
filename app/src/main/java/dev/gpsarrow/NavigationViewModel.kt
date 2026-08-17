package dev.gpsarrow

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.gpsarrow.core.Destination
import dev.gpsarrow.core.Fix
import dev.gpsarrow.core.Geo
import dev.gpsarrow.core.HeadingArbiter
import dev.gpsarrow.core.HeadingSource
import dev.gpsarrow.core.LatLon
import dev.gpsarrow.core.NavigationState
import dev.gpsarrow.data.DestinationStore
import dev.gpsarrow.location.Declination
import dev.gpsarrow.location.DeclinationProvider
import dev.gpsarrow.location.HeadingEngine
import dev.gpsarrow.location.LocationEngine
import dev.gpsarrow.maps.MapTier
import dev.gpsarrow.maps.RegionIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the one [NavigationState] that the arrow screen and the foreground-service notification
 * both render. Everything else in the app is a pure function of it.
 */
class NavigationViewModel(app: Application) : AndroidViewModel(app) {

    // MUST be declared before anything that can call degrade(): Kotlin runs property
    // initialisers in declaration order, so a later-declared flow would still be null here.
    private val _degraded = MutableStateFlow<List<String>>(emptyList())

    /** Non-fatal subsystem failures, surfaced in the UI instead of taking the app down. */
    val degraded: StateFlow<List<String>> = _degraded.asStateFlow()

    private fun degrade(reason: String) {
        if (reason !in _degraded.value) _degraded.value = _degraded.value + reason
    }

    private val appContext: Context = app.applicationContext
    private val locationEngine = LocationEngine(appContext)
    private val headingEngine = HeadingEngine(appContext)
    private val regionIndex = RegionIndex(appContext)

    // Declination is the one construction-time dependency that touches assets, so it gets its
    // own guard rather than relying on the loader's internal one.
    private val declination: DeclinationProvider =
        runCatching { Declination.create(appContext) }
            .getOrElse {
                Log.w(TAG, "declination unavailable; true-north correction disabled", it)
                degrade("Using magnetic north — declination model unavailable")
                object : DeclinationProvider {
                    override val sourceName = "none"
                    override fun declinationDegrees(position: LatLon, altitudeMeters: Double) = 0.0
                }
            }

    val store = DestinationStore(app)

    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private val _gnss = MutableStateFlow(LocationEngine.Status(false, 0, 0))
    val gnss: StateFlow<LocationEngine.Status> = _gnss.asStateFlow()

    private val _powerSaving = MutableStateFlow(false)
    val powerSaving: StateFlow<Boolean> = _powerSaving.asStateFlow()

    val declinationSource: String get() = declination.sourceName

    private var compassDeg: Double? = null
    private var compassReliable = true
    private var locationJob: Job? = null
    private var headingJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { store.load() }.onFailure {
                Log.w(TAG, "could not load saved destinations", it)
                degrade("Saved destinations could not be read")
            }
        }

        // Paint a stale last-known position immediately rather than an empty screen while a
        // cold GNSS fix takes its 30-90 seconds with no assistance data.
        runCatching { locationEngine.lastKnown() }
            .onFailure { Log.w(TAG, "last-known position unavailable", it) }
            .getOrNull()
            ?.let { applyFix(it, acquiring = true) }

        viewModelScope.launch {
            while (true) {
                delay(1_000)
                val fix = _state.value.fix ?: continue
                _state.value = _state.value.copy(
                    fixAgeMillis = SystemClock.elapsedRealtime() - fix.elapsedMillis,
                )
            }
        }
    }

    // ---------------------------------------------------------------- sensors

    /**
     * Each subsystem starts inside its own guard. The two are independent on purpose: if the
     * compass pipeline dies the app must keep the GPS arrow, and vice versa. Neither may take
     * the process down — a degraded arrow beats a crashed one every time.
     */
    fun startSensors() {
        if (locationJob == null) {
            locationJob = viewModelScope.launch {
                runCatching {
                    locationEngine.fixes(
                        if (_powerSaving.value) LocationEngine.Rate.RELAXED
                        else LocationEngine.Rate.ACTIVE,
                    ).collect { applyFix(it, acquiring = false) }
                }.onFailure {
                    if (it is CancellationException) throw it
                    Log.e(TAG, "location stream failed", it)
                    degrade("No position updates — ${it.javaClass.simpleName}")
                }
            }
            viewModelScope.launch {
                runCatching { locationEngine.status().collect { _gnss.value = it } }
                    .onFailure {
                        if (it is CancellationException) throw it
                        Log.w(TAG, "GNSS status stream failed", it)
                    }
            }
        }
        if (headingJob == null) {
            headingJob = viewModelScope.launch {
                runCatching {
                    headingEngine.readings(
                        displayRotation = { HeadingEngine.displayRotationOf(appContext) },
                    ).collect { reading ->
                        compassReliable = reading.reliable
                        compassDeg = if (reading.hasCompass) {
                            val dec = _state.value.declinationDeg ?: 0.0
                            Geo.normalizeDegrees(reading.magneticDeg + dec)
                        } else {
                            null
                        }
                        recomputeHeading()
                    }
                }.onFailure {
                    if (it is CancellationException) throw it
                    // The arrow survives this: HeadingArbiter falls back to GPS course.
                    Log.e(TAG, "compass stream failed; falling back to GPS course", it)
                    compassDeg = null
                    recomputeHeading()
                    degrade("Compass unavailable — heading comes from GPS course while moving")
                }
            }
        }
    }

    fun stopSensors() {
        locationJob?.cancel(); locationJob = null
        headingJob?.cancel(); headingJob = null
    }

    fun setPowerSaving(enabled: Boolean) {
        if (_powerSaving.value == enabled) return
        _powerSaving.value = enabled
        if (locationJob != null) {
            locationJob?.cancel()
            locationJob = null
            startSensors()
        }
    }

    private fun applyFix(fix: Fix, acquiring: Boolean) {
        // Declination changes slowly with position; recompute per fix, it costs microseconds.
        val dec = runCatching {
            declination.declinationDegrees(fix.position, fix.altitudeMeters ?: 0.0)
        }.getOrDefault(0.0)

        _state.value = _state.value.copy(
            fix = fix,
            declinationDeg = dec,
            isAcquiring = acquiring,
            fixAgeMillis = SystemClock.elapsedRealtime() - fix.elapsedMillis,
        )
        recomputeHeading()
    }

    private fun recomputeHeading() {
        val current = _state.value
        val (heading, source) = HeadingArbiter.select(
            fix = current.fix,
            compassDeg = compassDeg,
            magnetometerReliable = compassReliable,
            previousSource = current.headingSource,
        )
        if (heading != current.headingDeg || source != current.headingSource) {
            _state.value = current.copy(headingDeg = heading, headingSource = source)
        }
    }

    // ---------------------------------------------------------------- destinations

    fun selectDestination(destination: Destination?) {
        _state.value = _state.value.copy(destination = destination)
    }

    fun saveCurrentPosition(name: String, onDone: (Destination?) -> Unit = {}) {
        val fix = _state.value.fix
        if (fix == null) {
            onDone(null)
            return
        }
        viewModelScope.launch {
            onDone(store.add(name, fix.position, source = "current position"))
        }
    }

    fun saveDestination(
        name: String,
        position: LatLon,
        source: String,
        onDone: (Destination) -> Unit = {},
    ) {
        viewModelScope.launch { onDone(store.add(name, position, source = source)) }
    }

    fun deleteDestination(id: String) {
        viewModelScope.launch {
            if (_state.value.destination?.id == id) selectDestination(null)
            store.delete(id)
        }
    }

    // ---------------------------------------------------------------- map tiering (v1 hook)

    /**
     * What the map button should do. In a v0 build this is always [MapTier.ArrowOnly] because
     * the :maps module isn't present — which is the point of the module boundary.
     */
    fun mapTier(): MapTier =
        runCatching { regionIndex.tierFor(_state.value.fix?.position) }
            .getOrDefault(MapTier.ArrowOnly)

    private companion object {
        const val TAG = "NavigationViewModel"
    }
}

/**
 * Label for the heading-source chip. A free function of the state rather than a ViewModel
 * property, so Compose actually recomposes when the source changes.
 */
fun HeadingSource.label(): String = when (this) {
    HeadingSource.COMPASS -> "Compass"
    HeadingSource.GPS_COURSE -> "GPS course"
    HeadingSource.COMPASS_UNCALIBRATED -> "Compass needs calibration"
    HeadingSource.NONE -> "No heading"
}
