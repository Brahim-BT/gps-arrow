package dev.gpsarrow

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gpsarrow.maps.AreaLevel
import dev.gpsarrow.maps.MapArea
import dev.gpsarrow.core.PositionSmoothing
import dev.gpsarrow.core.SmoothedPosition
import dev.gpsarrow.core.MapOrientation
import dev.gpsarrow.core.OrientationState
import dev.gpsarrow.maps.MapMarkers
import dev.gpsarrow.maps.CameraCommand
import dev.gpsarrow.maps.MapCamera
import dev.gpsarrow.maps.MapTier
import dev.gpsarrow.maps.RegionIndex
import dev.gpsarrow.service.MapDownloadService
import dev.gpsarrow.service.MapDownloads
import dev.gpsarrow.ui.AreasScreen
import dev.gpsarrow.ui.rememberAreaRows
import dev.gpsarrow.ui.megabytes
import dev.gpsarrow.maps.freeSpaceOn
import dev.gpsarrow.ui.isMeteredConnection
import dev.gpsarrow.core.Destination
import dev.gpsarrow.core.DestinationParser
import dev.gpsarrow.core.CoordinateFormat
import dev.gpsarrow.core.DistanceUnits
import dev.gpsarrow.core.Format
import dev.gpsarrow.core.ParseResult
import dev.gpsarrow.locale.AppLanguage
import dev.gpsarrow.locale.AppLocale
import dev.gpsarrow.service.NavigationService
import dev.gpsarrow.ui.AddDestinationScreen
import dev.gpsarrow.ui.AppBar
import dev.gpsarrow.ui.AppTabs
import dev.gpsarrow.ui.ArrowScreen
import dev.gpsarrow.ui.CoordinateDraft
import dev.gpsarrow.ui.DestinationsScreen
import dev.gpsarrow.ui.LanguagePicker
import dev.gpsarrow.ui.MapScreen
import dev.gpsarrow.ui.PermissionGate
import dev.gpsarrow.ui.SettingsScreen
// Top-level extension functions from another package need an explicit import, unlike the
// members of an imported class. Omitting these is what broke CI run #7.
import dev.gpsarrow.ui.numberLocale
import dev.gpsarrow.ui.rememberNotificationPermissionRequest
import dev.gpsarrow.ui.theme.GpsArrowTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The four top-level tabs.
 *
 * Adding a point is an action, not a place, so it is a FAB inside Destinations rather than a
 * peer tab — which also removes the "which tab am I on now?" jump after saving.
 *
 * The width problem is gone. A fixed tab row divided the screen four ways and forced label
 * shortening — "Destinations" had to become "Saved" — and the three languages would each have
 * fought that fight separately. `AppTabs` is scrollable, so every tab is as wide as its own
 * word in whatever language is active, and the neighbours sitting half off-screen are what tell
 * the user there is more to swipe to. No label is truncated in any of the three.
 */
private enum class AppTab(val labelRes: Int) {
    ARROW(R.string.tab_arrow),
    DESTINATIONS(R.string.tab_destinations),
    MAP(R.string.tab_map),

    /**
     * Settings is a tab rather than an overflow item because its one real content — the
     * language — is what a user needs when the app is in a language they cannot read, and a
     * menu they cannot read is not a route to it. Four tabs still leave ~97dp each at the
     * A54's width.
     */
    SETTINGS(R.string.tab_settings),
}

/** What the editor sheet is doing, when it is open at all. */
private sealed interface Editor {
    /** Creating a new point; backed by the saveable [CoordinateDraft]. */
    data object New : Editor

    /** Editing an existing one. Its draft is separate, so an incoming intent can never touch it. */
    data class Existing(val destination: Destination) : Editor
}

class MainActivity : ComponentActivity() {

    private val viewModel: NavigationViewModel by viewModels()

    /**
     * The API 26-32 half of the per-app locale, and harmless on 33+ where the platform has
     * already applied it. Both halves read the same stored preference, so they cannot disagree.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    /** Text handed to us by a geo: link or a plain-text share. */
    private var sharedText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 16 (targetSdk 36) enforces edge-to-edge with no opt-out, so all screens
        // apply safeDrawing insets themselves. Must come after super.onCreate(): it touches
        // window.decorView, and forcing decor installation before the base class has run is
        // asking for "requestFeature() must be called before adding content".
        enableEdgeToEdge()
        handleIntent(intent)

        setContent {
            GpsArrowTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var chosen by remember { mutableStateOf(AppLocale.hasChosen(this)) }
                    if (!chosen) {
                        // Before anything else, including the permission gate: a permission
                        // rationale the user cannot read is worse than useless, because a
                        // denial here is effectively permanent.
                        LanguagePicker(
                            onChosen = { language ->
                                chosen = true
                                if (AppLocale.set(this, language)) recreate()
                            },
                        )
                    } else {
                        PermissionGate {
                            AppRoot(
                                viewModel = viewModel,
                                initialSharedText = sharedText,
                                onSharedTextConsumed = { sharedText = null },
                                onLanguageSelected = { language ->
                                    if (AppLocale.set(this, language)) recreate()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.startSensors()
    }

    override fun onStop() {
        super.onStop()
        // Sensors keep running only if the foreground service is up; otherwise release them.
        if (viewModel.state.value.destination == null) viewModel.stopSensors()
    }

    private fun handleIntent(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        if (!text.isNullOrBlank()) sharedText = text
    }
}

@Composable
private fun AppRoot(
    viewModel: NavigationViewModel,
    initialSharedText: String?,
    onSharedTextConsumed: () -> Unit,
    onLanguageSelected: (AppLanguage) -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val destinations by viewModel.store.destinations.collectAsStateWithLifecycle()
    val gnss by viewModel.gnss.collectAsStateWithLifecycle()
    val degraded by viewModel.degraded.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val requestNotifications = rememberNotificationPermissionRequest()

    var tab by rememberSaveable { mutableStateOf(AppTab.ARROW) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }

    // Add and Edit are the same screen presented over Destinations, never a tab.
    var editor by remember { mutableStateOf<Editor?>(null) }

    // Two separate drafts on purpose. An incoming geo: intent only ever writes `addDraft`, so
    // it is structurally incapable of overwriting a point that is mid-edit.
    var editDraft by remember { mutableStateOf(CoordinateDraft.EMPTY) }

    /** Newly saved point, scrolled to and briefly highlighted so the user sees where it landed. */
    var highlightId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(highlightId) {
        if (highlightId != null) {
            delay(2_500)
            highlightId = null
        }
    }

    // All hoisted so switching tabs is free — no lost search, no lost half-typed coordinate.
    var addDraft by rememberSaveable(stateSaver = CoordinateDraftSaver) {
        mutableStateOf(CoordinateDraft.EMPTY)
    }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // Hoisted alongside the other view preferences so cycling the notation survives a tab
    // switch and process death, like the half-typed coordinate does.
    var positionFormat by rememberSaveable { mutableStateOf(CoordinateFormat.DECIMAL) }
    var favouritesOnly by rememberSaveable { mutableStateOf(false) }

    // The Map tab is the only expensive one (v1 puts a MapLibre surface here), so it is not
    // composed until first selected. Everything else is cheap and composes on demand.
    var mapEverOpened by rememberSaveable { mutableStateOf(false) }
    if (tab == AppTab.MAP) mapEverOpened = true

    // The areas list presents over the Map tab, the way the editor presents over Destinations.
    // Saveable so a rotation mid-download does not drop the user back to the map.
    var showAreas by rememberSaveable { mutableStateOf(false) }
    val regionIndex = remember { RegionIndex(context.applicationContext) }
    val downloadState by MapDownloads.state.collectAsStateWithLifecycle()
    // Pending confirmation for a download over a metered connection: the level awaiting a yes.
    var meteredPrompt by remember { mutableStateOf<Pair<MapArea, AreaLevel>?>(null) }

    // Installed areas, observed. Collecting the shared flow is what makes a finished download
    // put a map on screen without a restart: the service's scan updates it and this recomposes.
    val installedAreas by RegionIndex.sharedFlow.collectAsStateWithLifecycle()

    // The camera, hoisted out of the map so it survives a tab switch, and saveable so it survives
    // process death — the same treatment the half-typed coordinate draft gets, for the same
    // reason: re-finding your place on a map is work the user already did once.
    var mapCamera by rememberSaveable(stateSaver = MapCameraSaver) {
        mutableStateOf<MapCamera?>(null)
    }

    // Which way the map points. Fed the arbiter's ALREADY-FILTERED heading, never the raw
    // magnetometer: the smoothing exists upstream and duplicating or bypassing it is what made
    // the arrow jitter. MapOrientation adds the dwell times that stop it flapping at the
    // boundary between having a heading and not.
    var orientation by remember { mutableStateOf(OrientationState()) }

    // Smoothed position for the MAP MARKER ONLY. state.fix stays raw everywhere else — the
    // distance readout, the arrow bearing, saved destinations and diagnostics all read it
    // directly, and must keep doing so.
    var smoothed by remember { mutableStateOf<SmoothedPosition?>(null) }
    LaunchedEffect(state.fix) {
        val fix = state.fix ?: return@LaunchedEffect
        smoothed = PositionSmoothing.update(smoothed, fix, System.currentTimeMillis())
    }

    // One-shot camera requests. A counter rather than a boolean so that tapping "centre on me"
    // twice in a row works the second time — each tap is a distinct command.
    var cameraCommand by remember { mutableStateOf<CameraCommand?>(null) }
    var commandCounter by remember { mutableStateOf(0L) }
    LaunchedEffect(state.headingDeg, state.fix?.elapsedMillis) {
        orientation = MapOrientation.update(
            state = orientation,
            headingDeg = state.headingDeg,
            nowMillis = System.currentTimeMillis(),
        )
    }

    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val units = DistanceUnits.METRIC   // wire to a settings store in v0.2

    // ---- Back handling ------------------------------------------------------------------
    //
    // ONE handler, not several. Parallel BackHandlers with overlapping `enabled` flags are how
    // you get a single press doing two things, or a stale flag swallowing presses and leaving
    // the app feeling stuck. Deriving both the action and the enabled flag from the same
    // expression makes those states unrepresentable.
    //
    // Dialogs are deliberately absent from this list: the delete AlertDialog and the sort
    // DropdownMenu are separate windows (Dialog / Popup) that consume back themselves via
    // their own onDismissRequest, so a press with one open never reaches this handler. That is
    // why closing a dialog cannot also change tab.
    //
    // Note what is NOT here: a "discard your typed coordinate?" prompt. The Add-point draft is
    // hoisted and saveable, so leaving the tab preserves it and coming back finds it intact.
    // Nothing is lost, so there is nothing to warn about — better than both a silent discard
    // and a confirmation on every press.
    val backAction: (() -> Unit)? = when {
        showDiagnostics && editor == null && tab == AppTab.ARROW -> ({ showDiagnostics = false })
        // The metered-data prompt is inline rather than a Dialog window, so unlike the delete
        // AlertDialog it does NOT consume back itself and has to be listed here. It comes before
        // the areas case: a press with it open must dismiss the prompt, not the list underneath.
        //
        // `&& showAreas` is not redundant. The prompt is only reachable from the areas list, but
        // it is plain state — nothing stops it outliving a tab switch, and a back press then
        // dismissing an invisible prompt would look like a swallowed press. Gating on the screen
        // that can show it makes that unrepresentable.
        meteredPrompt != null && showAreas -> ({ meteredPrompt = null })
        // The areas list presents over the Map tab, so closing it lands on the map by
        // construction — the tab underneath is already MAP.
        showAreas -> ({ showAreas = false; meteredPrompt = null })
        // The editor always presents over Destinations, so closing it lands there by
        // construction — the tab underneath is already DESTINATIONS.
        editor != null -> ({ editor = null })
        tab != AppTab.ARROW -> ({ tab = AppTab.ARROW })
        // Arrow tab with nothing open: fall through to the system and finish the activity,
        // which also lets Android 14+ run its predictive back-to-home animation.
        else -> null
    }
    BackHandler(enabled = backAction != null) { backAction?.invoke() }

    // A shared geo: link or SEND intent selects the Add tab and pre-fills it.
    LaunchedEffect(initialSharedText) {
        val text = initialSharedText
        if (!text.isNullOrBlank()) {
            (DestinationParser.parse(text, state.fix?.position) as? ParseResult.Success)
                ?.let { parsed ->
                    addDraft = CoordinateDraft(
                        name = parsed.label.orEmpty(),
                        latText = Format.coordinate(parsed.position.lat),
                        lonText = Format.coordinate(parsed.position.lon),
                        readAs = parsed.format.name.lowercase().replace('_', ' '),
                    )
                }
            // Opens Destinations with the editor presented over it, in New mode. Never
            // Existing, so a share arriving mid-edit cannot corrupt the point being edited.
            tab = AppTab.DESTINATIONS
            editor = Editor.New
            onSharedTextConsumed()
        }
    }

    // Start / stop the foreground service in step with having something to navigate to.
    // Note this is keyed on the destination, NOT on the visible tab: sensors and the service
    // follow the activity lifecycle so the arrow never re-acquires on a tab switch.
    //
    // The notification deliberately carries NO distance. Being keyed on the destination, this
    // effect runs once when the target is chosen, so the number it wrote was the distance at
    // that moment and then stayed there for the rest of the walk — a notification reading
    // "1.2 km away" while the user stands on the spot. A frozen number is worse than no
    // number, especially on the surface people read with the screen off. Making it live means
    // re-issuing the notification as the state changes, which cannot be done from here safely
    // (Android 12+ blocks starting a foreground service from the background, and this
    // composition outlives the visible activity) — it belongs in the service. See TESTING.md.
    LaunchedEffect(state.destination?.id) {
        val destination = state.destination
        if (destination == null) {
            NavigationService.stop(context)
        } else {
            requestNotifications()
            NavigationService.start(
                context = context,
                title = destination.name,
                text = context.getString(R.string.notification_text),
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .consumeWindowInsets(WindowInsets.safeDrawing),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (editor == null) {
                AppBar()
                AppTabs(
                    titles = AppTab.entries.map { stringResource(it.labelRes) },
                    selectedIndex = tab.ordinal,
                    onSelect = { tab = AppTab.entries[it] },
                )
            }

            Box(Modifier.weight(1f)) {
                // Add and Edit are one screen presented over Destinations. Distinguished only
                // by which draft backs it and which save call it makes.
                when (val open = editor) {
                    is Editor.Existing -> {
                        AddDestinationScreen(
                            draft = editDraft,
                            onDraftChange = { editDraft = it },
                            currentPosition = state.fix?.position,
                            editing = open.destination,
                            onSave = { name, position, _ ->
                                viewModel.updateDestination(open.destination.id, name, position) {
                                    highlightId = open.destination.id
                                    editor = null
                                }
                            },
                            onBack = { editor = null },
                        )
                        return@Box
                    }

                    Editor.New -> {
                        AddDestinationScreen(
                            draft = addDraft,
                            onDraftChange = { addDraft = it },
                            currentPosition = state.fix?.position,
                            onSave = { name, position, source ->
                                viewModel.saveDestination(name, position, source) { saved ->
                                    addDraft = CoordinateDraft.EMPTY
                                    // Clear the filters, or a new point that doesn't match the
                                    // active search would be saved into an invisible row.
                                    searchQuery = ""
                                    favouritesOnly = false
                                    highlightId = saved.id
                                    editor = null
                                }
                            },
                            onBack = { editor = null },
                        )
                        return@Box
                    }

                    null -> Unit
                }

                when (tab) {
                    AppTab.ARROW -> ArrowScreen(
                        state = state,
                        locationState = gnss.location,
                        satellitesUsed = gnss.satellitesUsed,
                        satellitesVisible = gnss.satellitesVisible,
                        degraded = degraded,
                        units = units,
                        showDiagnostics = showDiagnostics,
                        diagnostics = if (showDiagnostics) {
                            viewModel.diagnostics(context.numberLocale())
                        } else {
                            emptyList()
                        },
                        onToggleDiagnostics = { showDiagnostics = !showDiagnostics },
                        onPickDestination = { tab = AppTab.DESTINATIONS },
                        // Stays on the arrow screen as the primary one-tap action. It is an
                        // action, not a place, so it is emphatically NOT a tab.
                        onSaveMyLocation = {
                            // The button stays tappable even when the fix isn't good enough,
                            // so a refusal always comes with the reason. A disabled button
                            // that silently does nothing teaches the user the app is broken.
                            when (val blocked = viewModel.saveBlocked()) {
                                SaveBlock.NoFix -> scope.launch {
                                    snackbarHost.showSnackbar(
                                        context.getString(R.string.save_blocked_no_fix),
                                    )
                                }

                                is SaveBlock.StaleFix -> scope.launch {
                                    snackbarHost.showSnackbar(
                                        context.getString(
                                            R.string.save_blocked_stale,
                                            Format.number(
                                                "%d",
                                                context.numberLocale(),
                                                blocked.ageSeconds,
                                            ),
                                        ),
                                    )
                                }

                                null -> viewModel.quickSaveHere { saved ->
                                    scope.launch {
                                        snackbarHost.showSnackbar(
                                            when {
                                                saved == null ->
                                                    context.getString(
                                                        R.string.save_failed_fix_changed,
                                                    )

                                                saved.accuracyMeters == null ->
                                                    context.getString(
                                                        R.string.saved_point,
                                                        saved.name,
                                                    )

                                                else -> context.getString(
                                                    R.string.saved_point_with_accuracy,
                                                    saved.name,
                                                    Format.number(
                                                        "%d",
                                                        context.numberLocale(),
                                                        saved.accuracyMeters!!.toInt(),
                                                    ),
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        },
                        onAddDestination = {
                            tab = AppTab.DESTINATIONS
                            editor = Editor.New
                        },
                        positionFormat = positionFormat,
                        onCyclePositionFormat = { positionFormat = positionFormat.next() },
                        onPositionCopied = {
                            scope.launch {
                                snackbarHost.showSnackbar(
                                    context.getString(R.string.position_copied),
                                )
                            }
                        },
                        // Straight to the system location screen. Making the user find it
                        // themselves is the difference between a working app and an uninstall,
                        // and coming back is handled: LocationEngine watches
                        // PROVIDERS_CHANGED_ACTION, so the toggle lands before they press back.
                        onOpenLocationSettings = { context.openLocationSettings() },
                    )

                    AppTab.DESTINATIONS -> DestinationsScreen(
                        destinations = destinations,
                        currentPosition = viewModel.distanceOrigin(),
                        positionIsStale = viewModel.originIsStale(),
                        selectedId = state.destination?.id,
                        sort = sort,
                        units = units,
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        favouritesOnly = favouritesOnly,
                        onFavouritesOnlyChange = { favouritesOnly = it },
                        onSortChange = viewModel::setSort,
                        // Choosing a destination is the whole point of the list, so it hands
                        // straight back to the arrow.
                        onSelect = {
                            viewModel.selectDestination(it)
                            tab = AppTab.ARROW
                        },
                        onEdit = {
                            editDraft = CoordinateDraft(
                                name = it.name,
                                latText = Format.coordinate(it.position.lat),
                                lonText = Format.coordinate(it.position.lon),
                            )
                            editor = Editor.Existing(it)
                        },
                        onToggleFavourite = viewModel::toggleFavourite,
                        onDelete = { target ->
                            viewModel.deleteDestination(target) { removed ->
                                scope.launch {
                                    val result = snackbarHost.showSnackbar(
                                        message = context.getString(
                                            R.string.deleted_point,
                                            removed.name,
                                        ),
                                        actionLabel = context.getString(R.string.undo),
                                        duration = SnackbarDuration.Long,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.restoreDestination(removed)
                                    }
                                }
                            }
                        },
                        onAdd = { editor = Editor.New },
                        highlightId = highlightId,
                    )

                    AppTab.SETTINGS -> SettingsScreen(
                        current = AppLocale.current(context),
                        onLanguageSelected = onLanguageSelected,
                        versionName = remember(context) { context.versionName() },
                        declinationSource = viewModel.declinationSource,
                        declinationIsFramework = viewModel.declinationIsFramework,
                    )

                    AppTab.MAP -> if (mapEverOpened) {
                        if (showAreas) {
                            val rows = rememberAreaRows(
                                context = context,
                                index = regionIndex,
                                download = downloadState,
                                position = state.fix?.position,
                                locale = context.numberLocale(),
                            )
                            AreasScreen(
                                areas = rows,
                                storageUsedLabel = megabytes(
                                    regionIndex.bytesUsed(),
                                    context.numberLocale(),
                                ),
                                freeSpaceLabel = megabytes(
                                    freeSpaceOn(regionIndex.regionsDirectory),
                                    context.numberLocale(),
                                ),
                                meteredPrompt = meteredPrompt?.let { (_, level) ->
                                    megabytes(level.bytes, context.numberLocale())
                                },
                                onDownload = { area, level ->
                                    // Warn, do not block. Someone deliberately downloading a map
                                    // before heading somewhere with no signal is the most
                                    // important case this app has, and it is their data.
                                    if (isMeteredConnection(context)) {
                                        meteredPrompt = area to level
                                    } else {
                                        MapDownloadService.start(context, area, level)
                                    }
                                },
                                onConfirmMetered = {
                                    meteredPrompt?.let { (area, level) ->
                                        MapDownloadService.start(context, area, level)
                                    }
                                    meteredPrompt = null
                                },
                                onDismissMetered = { meteredPrompt = null },
                                onCancel = { MapDownloadService.cancel(context) },
                                onDelete = { area ->
                                    regionIndex.delete(area.id)
                                    MapDownloads.clear()
                                },
                                onBack = { showAreas = false; meteredPrompt = null },
                            )
                        } else {
                            // Computed from the observed list rather than asked for once, so it
                            // tracks installs and deletions as they happen.
                            val tier = remember(installedAreas, state.fix?.position) {
                                viewModel.mapTier()
                            }
                            MapScreen(
                                tier = tier,
                                positionGeoJson = remember(state.fix, state.fixAgeMillis, smoothed) {
                                    MapMarkers.position(
                                        state.fix, state.fixAgeMillis, smoothed?.position,
                                    )
                                },
                                destinationGeoJson = remember(state.destination) {
                                    MapMarkers.destination(
                                        state.destination?.position,
                                        state.destination?.name,
                                    )
                                },
                                orientation = orientation,
                                hasPosition = state.fix != null,
                                onUserGesture = {
                                    orientation = MapOrientation.afterUserGesture(orientation)
                                },
                                onFaceNorth = {
                                    orientation = MapOrientation.afterNorthTap(
                                        orientation, System.currentTimeMillis(),
                                    )
                                    // Turn to north where they are; do not move them.
                                    val here = mapCamera
                                    if (here != null) {
                                        commandCounter += 1
                                        cameraCommand = CameraCommand(
                                            id = commandCounter,
                                            lat = here.lat, lon = here.lon,
                                            bearingDeg = 0.0,
                                        )
                                    }
                                },
                                onCentreOnMe = {
                                    state.fix?.position?.let { p ->
                                        commandCounter += 1
                                        cameraCommand = CameraCommand(
                                            id = commandCounter,
                                            lat = p.lat, lon = p.lon,
                                            zoom = mapCamera?.zoom ?: MapCamera.ZOOM_AT_POSITION,
                                        )
                                    }
                                    orientation = MapOrientation.afterNorthTap(
                                        orientation, System.currentTimeMillis(),
                                    )
                                },
                                cameraCommand = cameraCommand,
                                camera = MapCamera.opening(
                                    remembered = mapCamera,
                                    position = state.fix?.position,
                                    installed = (tier as? MapTier.Available)?.installed,
                                ),
                                onCameraMoved = { mapCamera = it },
                                onBack = { tab = AppTab.ARROW },
                                onOpenRegions = { showAreas = true },
                                onRemindWhenOnline = { /* v1: WorkManager job */ },
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Keeps a half-typed coordinate across process death, not just across tab switches. */
private val CoordinateDraftSaver = listSaver<CoordinateDraft, String>(
    save = { listOf(it.name, it.latText, it.lonText, it.readAs.orEmpty()) },
    restore = {
        CoordinateDraft(
            name = it[0],
            latText = it[1],
            lonText = it[2],
            readAs = it[3].ifEmpty { null },
        )
    },
)

/**
 * Keeps the map camera across process death, not just across tab switches.
 *
 * Nullable because "no camera yet" is a real state and must not be confused with a camera at
 * 0,0 zoom 0 — which is the null island in the middle of the Atlantic, and is exactly the view
 * the map opened on before there was an opening-camera policy at all. The flag in slot 0 keeps
 * those distinguishable through a save/restore round trip.
 */
private val MapCameraSaver = listSaver<MapCamera?, Double>(
    save = {
        if (it == null) listOf(0.0) else listOf(1.0, it.lat, it.lon, it.zoom, it.bearingDeg)
    },
    restore = {
        if (it.isEmpty() || it[0] == 0.0) null
        else MapCamera(lat = it[1], lon = it[2], zoom = it[3], bearingDeg = it[4])
    },
)

/**
 * The versionName from the package manager rather than a BuildConfig constant, so About cannot
 * drift from what was actually installed.
 */
private fun Context.versionName(): String = runCatching {
    packageManager.getPackageInfo(packageName, 0).versionName ?: "—"
}.getOrDefault("—")

/** The OS location master switch. Guarded: an OEM ROM without this screen must not crash us. */
private fun Context.openLocationSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
