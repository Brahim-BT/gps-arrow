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
import dev.gpsarrow.core.Format
import dev.gpsarrow.core.Fix
import dev.gpsarrow.core.Geo
import dev.gpsarrow.core.HeadingArbiter
import dev.gpsarrow.core.LatLon
import dev.gpsarrow.core.NavigationState
import dev.gpsarrow.core.SharedPoint
import dev.gpsarrow.core.SharedPointJson
import dev.gpsarrow.core.SharedPoints
import dev.gpsarrow.data.DestinationStore
import dev.gpsarrow.data.SharedPointCache
import dev.gpsarrow.data.SharedPointsApi
import dev.gpsarrow.data.SharedPointsConfig
import dev.gpsarrow.location.Declination
import dev.gpsarrow.location.DeclinationProvider
import dev.gpsarrow.location.FrameworkDeclination
import dev.gpsarrow.location.HeadingEngine
import dev.gpsarrow.location.LocationEngine
import dev.gpsarrow.maps.MapTier
import dev.gpsarrow.ui.headingChipRes
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
    private val _degraded = MutableStateFlow<List<Degradation>>(emptyList())

    /** Non-fatal subsystem failures, surfaced in the UI instead of taking the app down. */
    val degraded: StateFlow<List<Degradation>> = _degraded.asStateFlow()

    private fun degrade(degradation: Degradation) {
        if (_degraded.value.none { it.messageRes == degradation.messageRes }) {
            _degraded.value = _degraded.value + degradation
        }
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
                degrade(Degradation(R.string.degraded_declination))
                object : DeclinationProvider {
                    override val sourceName = "none"
                    override fun declinationDegrees(position: LatLon, altitudeMeters: Double) = 0.0
                }
            }

    val store = DestinationStore(app)

    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    // ---------------------------------------------------------------- shared points
    //
    // The public layer: what other users chose to publish, cached on disk and rendered on the
    // map even offline. Everything network here is fail-soft — a failed refresh keeps the last
    // feed, a failed publish is retried by the next successful one — because this feature must
    // be as incapable of disturbing the arrow as the map download is.

    private val sharedCache = SharedPointCache(appContext)
    private val sharedApi = SharedPointsApi()

    private val _sharedPoints = MutableStateFlow<List<SharedPoint>>(emptyList())
    val sharedPoints: StateFlow<List<SharedPoint>> = _sharedPoints.asStateFlow()

    private var sharedEtag: String? = null
    private var sharedSyncJob: Job? = null

    /**
     * Refresh the feed if it is due. Called when the map tab opens; deliberately never from a
     * timer, so the layer costs nothing while nobody is looking at it.
     */
    fun syncSharedPointsIfDue() {
        if (!SharedPointsConfig.isConfigured) return
        if (sharedSyncJob?.isActive == true) return
        sharedSyncJob = viewModelScope.launch {
            val snapshot = runCatching { sharedCache.load() }.getOrNull() ?: return@launch
            if (!SharedPoints.shouldRefresh(snapshot.cachedAtMillis, System.currentTimeMillis())) {
                sharedSyncJob = null
                return@launch
            }
            when (val result = sharedApi.fetch(sharedEtag ?: snapshot.etag)) {
                is SharedPointsApi.Fetch.Fresh -> {
                    val points = SharedPointJson.decodeFeed(result.body)
                    _sharedPoints.value = points
                    sharedEtag = result.etag
                    runCatching {
                        sharedCache.save(points, result.etag, System.currentTimeMillis())
                    }
                    republishPending(points)
                }

                SharedPointsApi.Fetch.NotModified ->
                    runCatching { sharedCache.touch(System.currentTimeMillis()) }

                is SharedPointsApi.Fetch.Failed ->
                    Log.w(TAG, "shared-points refresh failed: ${result.detail}")
            }
            sharedSyncJob = null
        }
    }

    /**
     * Publish again any local point marked public that the fresh feed does not know.
     *
     * This is the whole retry policy: a publish attempted offline, or refused once, is retried
     * automatically the next time a sync succeeds — no queue file, no backoff state, nothing to
     * get stuck. A point the user has un-shared still sits in the feed until its tombstone is
     * drained, and being present in the feed means it correctly does NOT re-publish here.
     */
    private suspend fun republishPending(feed: List<SharedPoint>) {
        val remoteIds = feed.map { it.id }.toSet()
        val deviceId = sharedCache.deviceId()
        store.destinations.value
            .filter { it.isPublic && it.id !in remoteIds }
            .filter { SharedPoints.canPublish(it.id, it.name, it.position.lat, it.position.lon, it.note) }
            .forEach { destination ->
                val result = sharedApi.publish(
                    destination.id,
                    destination.toSharedPoint(),
                    deviceId,
                )
                if (result is SharedPointsApi.Publish.Failed) {
                    Log.w(TAG, "publish of ${destination.id} failed: ${result.detail}")
                }
            }
    }

    /** Share or un-share a saved point. The local flag flips immediately; the network follows. */
    fun setDestinationShared(destination: Destination, shared: Boolean) {
        viewModelScope.launch {
            store.setPublic(destination.id, shared)
            if (_state.value.destination?.id == destination.id) {
                _state.value =
                    _state.value.copy(destination = destination.copy(isPublic = shared))
            }
            if (!SharedPointsConfig.isConfigured || shared == destination.isPublic) return@launch
            if (shared) {
                if (!SharedPoints.canPublish(
                        destination.id,
                        destination.name,
                        destination.position.lat,
                        destination.position.lon,
                        destination.note,
                    )
                ) {
                    return@launch
                }
                val result = sharedApi.publish(
                    destination.id,
                    destination.copy(isPublic = true).toSharedPoint(),
                    sharedCache.deviceId(),
                )
                if (result is SharedPointsApi.Publish.Failed) {
                    Log.w(TAG, "publish failed (will retry on next sync): ${result.detail}")
                }
            } else {
                val result = sharedApi.queueRemoval(destination.id, sharedCache.deviceId())
                if (result is SharedPointsApi.Publish.Failed) {
                    Log.w(TAG, "removal queued failed: ${result.detail}")
                }
            }
        }
    }

    /**
     * Save someone else's shared point into the user's own list, KEEPING its id — which is what
     * makes [SharedPoints.visibleFrom] hide the teal dot in favour of the now-editable local
     * copy instead of drawing both.
     */
    fun saveSharedPointAsMine(point: SharedPoint, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            store.restore(
                Destination(
                    id = point.id,
                    name = point.name,
                    position = point.position,
                    note = point.note,
                    createdAtMillis = point.createdAtMillis,
                    source = "shared",
                ),
            )
            onDone()
        }
    }

    private fun Destination.toSharedPoint() = SharedPoint(
        id = id,
        name = name,
        position = position,
        note = note,
        createdAtMillis = createdAtMillis,
    )

    // Starts UNKNOWN, not "off". The previous initial value asserted that location was
    // disabled before anything had looked, so the very first frame of a first launch showed
    // "turn on location" to a user whose location was already on.
    private val _gnss = MutableStateFlow(LocationEngine.Status.UNKNOWN)
    val gnss: StateFlow<LocationEngine.Status> = _gnss.asStateFlow()

    private val _powerSaving = MutableStateFlow(false)
    val powerSaving: StateFlow<Boolean> = _powerSaving.asStateFlow()

    val declinationSource: String get() = declination.sourceName

    /** True when the compass correction comes from the OS model rather than a bundled one. */
    val declinationIsFramework: Boolean get() = declination is FrameworkDeclination

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
                degrade(Degradation(R.string.degraded_store_read))
            }
        }

        // The cached feed loads with everything else, so the map shows dots on the very first
        // frame; a refresh only happens later, when the map tab is opened and the cache is due.
        viewModelScope.launch {
            runCatching { sharedCache.load() }.onSuccess { snapshot ->
                _sharedPoints.value = snapshot.points
                sharedEtag = snapshot.etag
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
                    degrade(Degradation(R.string.degraded_no_location, it.javaClass.simpleName))
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
                    degrade(Degradation(R.string.degraded_no_compass))
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

    fun updateDestination(
        id: String,
        name: String,
        position: LatLon,
        isPublic: Boolean? = null,
        onDone: () -> Unit = {},
    ) {
        viewModelScope.launch {
            val updated = store.update(id, name, position, isPublic = isPublic)
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

    /**
     * Why "save my location" cannot run right now, or null when it can.
     *
     * A saved point is permanent in a way the arrow is not, so this refuses on a stale fix
     * rather than recording where the user was several minutes ago as where they are. Returned
     * as a value, not a sentence: the wording is translated and lives in the UI layer.
     */
    fun saveBlocked(): SaveBlock? {
        val s = _state.value
        if (s.fix == null) return SaveBlock.NoFix
        if (!s.isSaveable) return SaveBlock.StaleFix(s.fixAgeMillis / 1000)
        return null
    }

    fun saveCurrentPosition(name: String, onDone: (Destination?) -> Unit = {}) {
        val current = _state.value
        val fix = current.fix
        if (fix == null || !current.isSaveable) {
            onDone(null)
            return
        }
        viewModelScope.launch {
            onDone(
                store.add(
                    name,
                    fix.position,
                    source = "current position",
                    // Recorded so the list can show what this point is worth. The arrow can
                    // only ever be as good as the point it aims at.
                    accuracyMeters = fix.accuracyMeters,
                ),
            )
        }
    }

    /**
     * One tap, no form. Names the point after the time it was taken, which is what makes it
     * findable later ("the one from 14:32"); renaming is available from the list.
     *
     * The name is a stored string, so it is written in a form that does not belong to any
     * language: `2026-08-18 14:32` rather than `18 Aug 14:32`. The previous version formatted
     * the month name in whatever language was active at the moment of saving, which froze it
     * there — switch the app to Arabic and last month\'s points still read "18 Aug", forever,
     * because they are user text and nothing may rewrite them. Digits and hyphens have no such
     * problem, they sort correctly, and they are unambiguous in all three languages.
     */
    fun quickSaveHere(onDone: (Destination?) -> Unit) {
        val label = SimpleDateFormat(QUICK_SAVE_NAME_PATTERN, Locale.ROOT).format(Date())
        saveCurrentPosition(label, onDone)
    }

    /**
     * Live values for the on-screen diagnostics panel. Cheap; only read when it is open.
     *
     * Labels come back as resource ids rather than text because this ViewModel holds the
     * Application context, whose configuration does not follow the per-app locale on API
     * levels below 33 — only the Activity\'s does. Handing ids to the UI means the panel is in
     * the same language as the rest of the app on every supported device.
     */
    fun diagnostics(locale: Locale): List<Diagnostic> {
        val s = _state.value
        val fix = s.fix
        val dash = "—"
        fun deg(value: Double?) = value?.let { latin("%.1f", locale, it) } ?: dash
        fun degRow(label: Int, value: Double?) =
            Diagnostic(label, deg(value), if (value == null) null else R.string.diag_degrees)
        fun rows(vararg pairs: Diagnostic) = pairs.toList()
        return rows(
            Diagnostic(R.string.diag_arrow_mode, s.arrowMode.name),
            degRow(R.string.diag_arrow_angle, s.arrowDeg),
            Diagnostic(
                R.string.diag_arbiter_mode,
                s.headingSource.name,
                valueRes = s.headingChipRes(),
            ),
            degRow(R.string.diag_heading_smoothed, s.headingDeg),
            degRow(R.string.diag_compass_raw, rawCompassDeg),
            degRow(R.string.diag_compass_smoothed, smoothedCompassDeg),
            degRow(
                R.string.diag_raw_minus_smoothed,
                rawCompassDeg?.let { raw ->
                    smoothedCompassDeg?.let { Geo.angleDeltaDegrees(it, raw) }
                },
            ),
            Diagnostic(R.string.diag_compass_sensor, compassSensorName),
            Diagnostic(
                R.string.diag_sample_rate,
                latin("%.0f", locale, compassHz),
                R.string.diag_hertz,
            ),
            Diagnostic(
                R.string.diag_smoothing_tau,
                latin("%d", locale, (HeadingEngine.SMOOTHING_TIME_CONSTANT_S * 1000).toInt()),
                R.string.diag_millis,
            ),
            Diagnostic(R.string.diag_sensors_present, headingEngine.availableSensors()),
            Diagnostic(R.string.diag_magnetometer_ok, compassReliable.toString()),
            Diagnostic(
                R.string.diag_declination,
                s.declinationDeg?.let { latin("%.2f", locale, it) } ?: dash,
                s.declinationDeg?.let { R.string.diag_degrees },
            ),
            Diagnostic(R.string.diag_declination_source, declination.sourceName),
            degRow(R.string.diag_bearing_to_destination, s.bearingToDestinationDeg),
            Diagnostic(
                R.string.diag_distance,
                s.distanceMeters?.let { latin("%.0f", locale, it) } ?: dash,
                s.distanceMeters?.let { R.string.unit_metres },
            ),
            Diagnostic(
                R.string.diag_fix,
                fix?.let { "\u2066" + Format.decimal(it.position) + "\u2069" } ?: dash,
            ),
            Diagnostic(
                R.string.diag_fix_accuracy,
                fix?.let { latin("%.0f", locale, it.accuracyMeters) } ?: dash,
                fix?.let { R.string.diag_accuracy_meters },
            ),
            Diagnostic(
                R.string.diag_fix_age,
                latin("%d", locale, s.fixAgeMillis / 1000),
                R.string.diag_seconds,
            ),
            Diagnostic(R.string.diag_fix_quality, s.quality.name),
            Diagnostic(R.string.diag_provider, fix?.provider ?: dash),
            Diagnostic(
                R.string.diag_satellites,
                latin("%d", locale, _gnss.value.satellitesUsed) + "/" +
                    latin("%d", locale, _gnss.value.satellitesVisible),
            ),
            Diagnostic(R.string.diag_gps_enabled, _gnss.value.location.name),
            Diagnostic(R.string.diag_location_job, (locationJob?.isActive == true).toString()),
            Diagnostic(R.string.diag_heading_job, (headingJob?.isActive == true).toString()),
            Diagnostic(R.string.diag_heading_updates, latin("%d", locale, headingUpdateCount)),
            Diagnostic(R.string.diag_fix_updates, latin("%d", locale, fixUpdateCount)),
            // Restored: which subsystems have degraded is exactly what a support conversation
            // needs, and the banner only shows the first of them.
            Diagnostic(
                R.string.diag_degraded,
                _degraded.value.size.takeIf { it > 0 }?.let { latin("%d", locale, it) }
                    ?: DEGRADED_NONE,
            ),
        )
    }

    /** Diagnostics numbers go through the same Latin-digit rule as everything else. */
    private fun latin(pattern: String, locale: Locale, value: Any): String =
        Format.number(pattern, locale, value)

    fun saveDestination(
        name: String,
        position: LatLon,
        source: String,
        isPublic: Boolean = false,
        onDone: (Destination) -> Unit = {},
    ) {
        viewModelScope.launch {
            onDone(store.add(name, position, source = source, isPublic = isPublic))
        }
    }

    // ---------------------------------------------------------------- map tiering (v1 hook)

    /**
     * What the map button should do.
     *
     * Wrapped in `runCatching` and defaulting to [MapTier.ArrowOnly] on purpose: this is the one
     * place the arrow path touches anything map-related, and a failure here — unreadable storage,
     * a directory that vanished — must degrade to "no map" rather than propagate. The arrow does
     * not need a map and must not be able to fail because of one.
     */
    fun mapTier(): MapTier =
        runCatching { regionIndex.tierFor(_state.value.fix?.position) }
            .getOrDefault(MapTier.ArrowOnly)

    private companion object {
        const val TAG = "NavigationViewModel"
        const val KEY_SORT = "destinations.sort"

        /** Locale-neutral by design; see [quickSaveHere]. */
        const val QUICK_SAVE_NAME_PATTERN = "yyyy-MM-dd HH:mm"

        /** Rendered by the panel through R.string.diag_none; see [Diagnostic]. */
        const val DEGRADED_NONE = ""
    }
}
