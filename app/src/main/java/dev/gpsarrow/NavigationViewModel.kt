package dev.gpsarrow

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.gpsarrow.core.Destination
import dev.gpsarrow.core.DestinationSort
import dev.gpsarrow.core.CourseEstimator
import dev.gpsarrow.core.FixQuality
import dev.gpsarrow.core.Format
import dev.gpsarrow.core.Fix
import dev.gpsarrow.core.Geo
import dev.gpsarrow.core.HeadingArbiter
import dev.gpsarrow.core.LatLon
import dev.gpsarrow.core.NavigationState
import dev.gpsarrow.core.ShareIntent
import dev.gpsarrow.core.ShareToken
import dev.gpsarrow.core.SharedPoint
import dev.gpsarrow.core.SharedPointJson
import dev.gpsarrow.core.SharedPoints
import dev.gpsarrow.data.DestinationStore
import dev.gpsarrow.data.ShareTokenStore
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
    private val shareTokens = ShareTokenStore(appContext)
    private val sharedApi = SharedPointsApi()

    private val _sharedPoints = MutableStateFlow<List<SharedPoint>>(emptyList())
    val sharedPoints: StateFlow<List<SharedPoint>> = _sharedPoints.asStateFlow()

    /**
     * When a feed was last fetched, or **null if one never has been on this device**.
     *
     * Exposed rather than kept private because it is half of every honest statement the UI makes
     * about sharing: without it, "your point is not in the feed" and "no feed has ever been
     * fetched" are the same empty list, and the app would report a withdrawal complete to a user
     * who has never once been online. See [SharedPoints.observationOf].
     */
    private val _sharedCachedAtMillis = MutableStateFlow<Long?>(null)
    val sharedCachedAtMillis: StateFlow<Long?> = _sharedCachedAtMillis.asStateFlow()

    private var sharedEtag: String? = null
    private var sharedSyncJob: Job? = null

    /**
     * Refresh the feed if it is due. Called when the map tab opens; deliberately never from a
     * timer, so the layer costs nothing while nobody is looking at it.
     */
    fun syncSharedPointsIfDue() = syncSharedPoints(force = false)

    /**
     * @param force skip the staleness dwell. Used right after a share or a withdrawal, so the
     *   next thing the user sees is an observation rather than a guess — an online user's badge
     *   settles in a second instead of in six hours, and an offline user's correctly does not.
     */
    private fun syncSharedPoints(force: Boolean) {
        if (!SharedPointsConfig.isConfigured) return
        if (sharedSyncJob?.isActive == true) return
        sharedSyncJob = viewModelScope.launch {
            val snapshot = runCatching { sharedCache.load() }.getOrNull() ?: return@launch
            val now = System.currentTimeMillis()
            if (!force && !SharedPoints.shouldRefresh(snapshot.cachedAtMillis, now)) {
                sharedSyncJob = null
                return@launch
            }
            // Only present an ETag when there is a cache the ETag describes. A 304 answered
            // against a stamp-less cache would mark the feed observed while holding no points,
            // and every shared point would read as absent.
            val etag = if (snapshot.cachedAtMillis != null) sharedEtag ?: snapshot.etag else null
            when (val result = sharedApi.fetch(etag)) {
                is SharedPointsApi.Fetch.Fresh -> {
                    val points = SharedPointJson.decodeFeed(result.body)
                    _sharedPoints.value = points
                    sharedEtag = result.etag
                    runCatching { sharedCache.save(points, result.etag, now) }
                    _sharedCachedAtMillis.value = now
                    reconcileWithFeed(points)
                }

                // A 304 is an observation too: it says the cached feed IS the current one. So
                // it stamps freshness and reconciles against the cache, rather than doing
                // nothing — a withdrawal that could not be delivered last time gets its retry
                // here as much as it would after a full body.
                SharedPointsApi.Fetch.NotModified -> {
                    runCatching { sharedCache.touch(now) }
                    _sharedCachedAtMillis.value = now
                    reconcileWithFeed(snapshot.points)
                }

                is SharedPointsApi.Fetch.Failed ->
                    Log.w(TAG, "shared-points refresh failed: ${result.detail}")
            }
            sharedSyncJob = null
        }
    }

    /**
     * Deliver, against a freshly observed feed, whatever the user asked for and this device has
     * not managed to hand over yet.
     *
     * This is the whole retry policy, and it runs in **both** directions — which is the half
     * that was missing. A publish attempted offline is retried because the point is absent while
     * the intent says shared; a withdrawal attempted offline is retried because the point is
     * present while the intent says withdrawn. No queue file, no backoff state, nothing to get
     * stuck: the difference between what was asked for and what is out there is recomputed from
     * scratch every time, so it self-corrects after any sync rather than accumulating.
     */
    private suspend fun reconcileWithFeed(feed: List<SharedPoint>) {
        val remoteIds = feed.map { it.id }.toSet()
        store.destinations.value.forEach { destination ->
            when (destination.shareIntent) {
                ShareIntent.PRIVATE -> Unit
                ShareIntent.SHARED ->
                    if (destination.id !in remoteIds) publishNow(destination)
                ShareIntent.WITHDRAWN ->
                    if (destination.id in remoteIds) withdrawNow(destination.id)
            }
        }
    }

    /**
     * Share or stop sharing one saved point.
     *
     * The single entry point for changing sharing, and the reason there is only one: the
     * previous version had the switch route through the ordinary edit path, which wrote the
     * local flag and published nothing, so ticking the box showed "Publicly shared" while
     * nothing left the device and unticking it hid the badge while the point stayed public
     * forever. Recording and delivering are both here, in that order.
     *
     * [onRecorded] fires as soon as the local instruction is on disk, before any network work,
     * so an editor can close on a phone with no signal exactly as fast as on one with signal.
     */
    fun setDestinationShared(
        destination: Destination,
        sharePublicly: Boolean,
        onRecorded: () -> Unit = {},
    ) {
        viewModelScope.launch { applyShare(destination, sharePublicly, onRecorded) }
    }

    private suspend fun applyShare(
        destination: Destination,
        sharePublicly: Boolean,
        onRecorded: () -> Unit = {},
    ) {
        val intent = SharedPoints.intentAfterSwitch(destination.shareIntent, sharePublicly)
        if (intent != destination.shareIntent) {
            store.setShareIntent(destination.id, intent)
            if (_state.value.destination?.id == destination.id) {
                _state.value =
                    _state.value.copy(destination = destination.copy(shareIntent = intent))
            }
        }
        onRecorded()

        if (!SharedPointsConfig.isConfigured) return
        // Delivered even when the instruction did not change, because "already asked for" and
        // "already delivered" are different things and this device may still owe the server the
        // second one. Both calls are create-or-queue and cost one refused request at worst.
        val delivered = when (intent) {
            ShareIntent.PRIVATE -> false
            ShareIntent.SHARED -> publishNow(destination.copy(shareIntent = intent))
            ShareIntent.WITHDRAWN -> withdrawNow(destination.id)
        }
        // Go and look, so the next thing shown is observed rather than assumed.
        if (delivered) syncSharedPoints(force = true)
    }

    /**
     * Publish one point: mint or reuse its withdrawal token, **write it to disk**, then send the
     * atomic pair.
     *
     * The order is the requirement. A token that reached the server but never reached this
     * device is a point the user can never withdraw, so a token that cannot be persisted refuses
     * the publish outright rather than risking it.
     */
    private suspend fun publishNow(destination: Destination): Boolean {
        if (!SharedPoints.canPublish(
                destination.id,
                destination.name,
                destination.position.lat,
                destination.position.lon,
                destination.note,
            )
        ) {
            Log.w(TAG, "not publishing ${destination.id}: fails the publish rules")
            return false
        }
        val token = shareTokens.mint(destination.id) ?: return false
        return when (val result = sharedApi.publish(destination.toSharedPoint(), ShareToken.hash(token))) {
            SharedPointsApi.Publish.Done -> true
            is SharedPointsApi.Publish.Failed -> {
                Log.w(TAG, "publish failed (retried on the next sync): ${result.detail}")
                false
            }
        }
    }

    /**
     * Queue a withdrawal by presenting this device's token for the point.
     *
     * A missing token — app data cleared, or the point published from a device the user no
     * longer has — means the withdrawal cannot be delivered from here at all. Nothing pretends
     * otherwise: the intent stays [ShareIntent.WITHDRAWN] and the status keeps saying the
     * withdrawal is unconfirmed, which is the true statement.
     */
    private suspend fun withdrawNow(id: String): Boolean {
        if (!SharedPoints.isPublishableId(id)) return false
        val token = shareTokens.tokenFor(id)
        if (token == null) {
            Log.w(TAG, "no withdrawal token for $id; this device cannot withdraw it")
            return false
        }
        return when (val result = sharedApi.queueRemoval(id, token)) {
            SharedPointsApi.Publish.Done -> true
            is SharedPointsApi.Publish.Failed -> {
                Log.w(TAG, "withdrawal queue failed (retried on the next sync): ${result.detail}")
                false
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

    // Course-over-ground bookkeeping, fed one fix at a time. The estimate is computed BEFORE
    // the new fix overwrites _state, because the estimator's whole job is comparing this fix
    // against what came before it.
    private var courseState: CourseEstimator.State? = null
    private var derivedCourseDeg: Double? = null
    private var chipCourseTrusted: Boolean = true

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
        //
        // cachedAtMillis is carried across with the points because it is what the sharing badges
        // are entitled to claim from. Until this lands it stays null, which reads as "nothing
        // has been observed" — the correct answer during the first few milliseconds, and the
        // permanent one for a device that has never been online.
        viewModelScope.launch {
            runCatching { sharedCache.load() }.onSuccess { snapshot ->
                _sharedPoints.value = snapshot.points
                sharedEtag = snapshot.etag
                _sharedCachedAtMillis.value = snapshot.cachedAtMillis
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
        // The estimator compares this fix with the previous one, so it runs before _state
        // (still holding the previous fix) is overwritten. Wrapped like everything else that
        // touches geometry: a failure here degrades to trusting the chip, never to a crash.
        runCatching { CourseEstimator.update(courseState, fix) }
            .onSuccess { estimate ->
                courseState = estimate.state
                derivedCourseDeg = estimate.derivedCourseDeg
                chipCourseTrusted = estimate.chipTrusted
            }
            .onFailure {
                Log.w(TAG, "course estimation failed; trusting the chip bearing", it)
                derivedCourseDeg = null
                chipCourseTrusted = true
            }

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
            derivedCourseDeg = derivedCourseDeg,
            chipCourseTrusted = chipCourseTrusted,
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
        /** The editor's share switch. Routed through [applyShare], never through [store.update]. */
        sharePublicly: Boolean = false,
        onDone: () -> Unit = {},
    ) {
        viewModelScope.launch {
            val updated = store.update(id, name, position)
            // Keep the live navigation target in step with the edit.
            if (updated != null && _state.value.destination?.id == id) {
                _state.value = _state.value.copy(destination = updated)
            }
            if (updated == null) {
                onDone()
                return@launch
            }
            // onDone is handed to applyShare rather than called here, so it fires once the
            // instruction is on disk and before the network work — the editor closes at the same
            // speed with and without signal.
            applyShare(updated, sharePublicly, onDone)
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
            // The course-over-ground story in three rows: what the chip claims, what geometry
            // derived from consecutive positions says, and whether the chip is currently
            // believed. A frozen-bearing device reports here as trusted=false with the two
            // courses disagreeing — the exact signature of the driving freeze this panel exists
            // to diagnose.
            degRow(R.string.diag_course_chip, fix?.bearingDeg?.toDouble()),
            degRow(R.string.diag_course_derived, derivedCourseDeg),
            Diagnostic(
                R.string.diag_course_trust,
                chipCourseTrusted.toString(),
                valueRes = if (chipCourseTrusted) {
                    R.string.diag_course_trusted
                } else {
                    R.string.diag_course_distrusted
                },
            ),
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
        /** The editor's share switch, on a brand-new point. */
        sharePublicly: Boolean = false,
        onDone: (Destination) -> Unit = {},
    ) {
        viewModelScope.launch {
            val saved = store.add(
                name,
                position,
                source = source,
                shareIntent = if (sharePublicly) ShareIntent.SHARED else ShareIntent.PRIVATE,
            )
            // Saved with the intent already on it, so the screen can close now; the publish
            // that follows is the network half and must not hold the UI open.
            onDone(saved)
            if (sharePublicly && SharedPointsConfig.isConfigured) {
                if (publishNow(saved)) syncSharedPoints(force = true)
            }
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
