package dev.gpsarrow

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.gpsarrow.core.Destination
import dev.gpsarrow.core.DestinationSort
import dev.gpsarrow.core.FixQuality
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    // Diagnostics only. Counters are the fastest way to tell "sensor is dead" apart from
    // "sensor is alive but the value never reaches the UI".
    private var rawCompassDeg: Double? = null
    private var smoothedCompassDeg: Double? = null
    private var compassHz: Double = 0.0
    private var compassSensorName: String = "not started"
    private var headingUpdateCount: Long = 0
    private var fixUpdateCount: Long = 0

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
                        headingUpdateCount++
                        rawCompassDeg = reading.rawMagneticDeg
                        smoothedCompassDeg = reading.magneticDeg
                        compassHz = reading.sampleRateHz
                        compassSensorName = reading.sensorName
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
        fixUpdateCount++
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
        // Stamping here is what makes "recently used" mean anything.
        destination?.let { viewModelScope.launch { store.markUsed(it.id) } }
    }

    fun updateDestination(id: String, name: String, position: LatLon, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val updated = store.update(id, name, position)
            // Keep the live navigation target in step with the edit.
            if (updated != null && _state.value.destination?.id == id) {
                _state.value = _state.value.copy(destination = updated)
            }
            onDone()
        }
    }

    fun toggleFavourite(destination: Destination) {
        viewModelScope.launch { store.setFavourite(destination.id, !destination.isFavourite) }
    }

    /** Deletes and hands the caller the removed value so it can offer undo. */
    fun deleteDestination(destination: Destination, onDeleted: (Destination) -> Unit = {}) {
        viewModelScope.launch {
            if (_state.value.destination?.id == destination.id) selectDestination(null)
            store.delete(destination.id)
            onDeleted(destination)
        }
    }

    fun restoreDestination(destination: Destination) {
        viewModelScope.launch { store.restore(destination) }
    }

    // ---------------------------------------------------------------- list preferences

    private val prefs = appContext.getSharedPreferences("gpsarrow.prefs", Context.MODE_PRIVATE)

    private val _sort = MutableStateFlow(
        DestinationSort.fromName(prefs.getString(KEY_SORT, null)),
    )
    val sort: StateFlow<DestinationSort> = _sort.asStateFlow()

    fun setSort(value: DestinationSort) {
        _sort.value = value
        prefs.edit().putString(KEY_SORT, value.name).apply()
    }

    /**
     * Origin for distance sorting and the per-row distances.
     *
     * Deliberately still returns a stale fix rather than null: a distance from ten minutes ago
     * is far more useful than no distance at all, as long as the UI labels it. Only a complete
     * absence of any fix disables the distance orders.
     */
    fun distanceOrigin(): LatLon? = _state.value.fix?.position

    fun originIsStale(): Boolean = _state.value.quality.let {
        it == FixQuality.STALE || it == FixQuality.NONE
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

    /**
     * One tap, no form. Names the point after the time it was taken, which is what makes it
     * findable later ("the one from 14:32"); renaming is available from the list.
     */
    fun quickSaveHere(onDone: (Destination?) -> Unit) {
        val label = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date())
        saveCurrentPosition(label, onDone)
    }

    /** Live values for the on-screen diagnostics panel. Cheap; only read when it is open. */
    fun diagnostics(): List<Pair<String, String>> {
        val s = _state.value
        val fix = s.fix
        return listOf(
            "arrow mode" to s.arrowMode.name,
            "arrow angle" to (s.arrowDeg?.let { "%.1f°".format(it) } ?: "—"),
            "arbiter mode" to s.headingSource.name,
            "heading (smoothed)" to (s.headingDeg?.let { "%.1f°".format(it) } ?: "—"),
            "compass RAW" to (rawCompassDeg?.let { "%.1f°".format(it) } ?: "—"),
            "compass SMOOTHED" to (smoothedCompassDeg?.let { "%.1f°".format(it) } ?: "—"),
            "raw − smoothed" to (
                if (rawCompassDeg != null && smoothedCompassDeg != null) {
                    "%.1f°".format(Geo.angleDeltaDegrees(smoothedCompassDeg!!, rawCompassDeg!!))
                } else "—"
                ),
            "compass sensor" to compassSensorName,
            "sample rate" to "%.0f Hz".format(compassHz),
            "smoothing tau" to "${(HeadingEngine.SMOOTHING_TIME_CONSTANT_S * 1000).toInt()} ms",
            "sensors present" to headingEngine.availableSensors(),
            "magnetometer ok" to compassReliable.toString(),
            "declination" to (s.declinationDeg?.let { "%.2f°".format(it) } ?: "—"),
            "declination src" to declination.sourceName,
            "bearing to dest" to (s.bearingToDestinationDeg?.let { "%.1f°".format(it) } ?: "—"),
            "distance" to (s.distanceMeters?.let { "%.0f m".format(it) } ?: "—"),
            "fix" to (fix?.let { "%.5f, %.5f".format(it.position.lat, it.position.lon) } ?: "none"),
            "fix accuracy" to (fix?.let { "±%.0f m".format(it.accuracyMeters) } ?: "—"),
            "fix age" to "${s.fixAgeMillis / 1000} s",
            "fix quality" to s.quality.name,
            "provider" to (fix?.provider ?: "—"),
            "satellites" to "${_gnss.value.satellitesUsed}/${_gnss.value.satellitesVisible}",
            "gps enabled" to _gnss.value.gpsEnabled.toString(),
            "location job" to (locationJob?.isActive == true).toString(),
            "heading job" to (headingJob?.isActive == true).toString(),
            "heading updates" to headingUpdateCount.toString(),
            "fix updates" to fixUpdateCount.toString(),
            "degraded" to (_degraded.value.takeIf { it.isNotEmpty() }?.joinToString("; ") ?: "none"),
        )
    }

    fun saveDestination(
        name: String,
        position: LatLon,
        source: String,
        onDone: (Destination) -> Unit = {},
    ) {
        viewModelScope.launch { onDone(store.add(name, position, source = source)) }
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
        const val KEY_SORT = "destinations.sort"
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
