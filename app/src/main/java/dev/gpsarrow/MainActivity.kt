package dev.gpsarrow

import android.content.Intent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gpsarrow.core.Destination
import dev.gpsarrow.core.DestinationParser
import dev.gpsarrow.core.DistanceUnits
import dev.gpsarrow.core.Format
import dev.gpsarrow.core.ParseResult
import dev.gpsarrow.service.NavigationService
import dev.gpsarrow.ui.AddDestinationScreen
import dev.gpsarrow.ui.ArrowScreen
import dev.gpsarrow.ui.CoordinateDraft
import dev.gpsarrow.ui.DestinationsScreen
import dev.gpsarrow.ui.MapScreen
import dev.gpsarrow.ui.PermissionGate
import dev.gpsarrow.ui.rememberNotificationPermissionRequest
import dev.gpsarrow.ui.theme.GpsArrowTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The three top-level tabs.
 *
 * Adding a point is an action, not a place, so it is a FAB inside Destinations rather than a
 * peer tab — which also removes the "which tab am I on now?" jump after saving.
 *
 * Dropping to three tabs gives ~130dp each at the A54's ~390dp width, and "Destinations" needs
 * ~116dp with its icon padding, so the full word is back; "Saved" was only ever a compromise
 * forced by the four-tab layout.
 */
private enum class AppTab(val label: String) {
    ARROW("Arrow"),
    DESTINATIONS("Destinations"),
    MAP("Map"),
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
                    PermissionGate {
                        AppRoot(
                            viewModel = viewModel,
                            initialSharedText = sharedText,
                            onSharedTextConsumed = { sharedText = null },
                        )
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
    var favouritesOnly by rememberSaveable { mutableStateOf(false) }

    // The Map tab is the only expensive one (v1 puts a MapLibre surface here), so it is not
    // composed until first selected. Everything else is cheap and composes on demand.
    var mapEverOpened by rememberSaveable { mutableStateOf(false) }
    if (tab == AppTab.MAP) mapEverOpened = true

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
                        latText = "%.6f".format(parsed.position.lat),
                        lonText = "%.6f".format(parsed.position.lon),
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
    LaunchedEffect(state.destination?.id) {
        val destination = state.destination
        if (destination == null) {
            NavigationService.stop(context)
        } else {
            requestNotifications()
            NavigationService.start(
                context = context,
                title = destination.name,
                text = state.distanceMeters
                    ?.let { "${Format.distance(it, units)} away" }
                    ?: "Acquiring position",
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
                TabRow(selectedTabIndex = tab.ordinal) {
                    AppTab.entries.forEach { entry ->
                        Tab(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            text = { Text(entry.label, maxLines = 1) },
                            icon = { TabIcon(entry) },
                        )
                    }
                }
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
                        headingSourceLabel = state.headingSource.label(),
                        satellitesUsed = gnss.satellitesUsed,
                        satellitesVisible = gnss.satellitesVisible,
                        degraded = degraded,
                        units = units,
                        showDiagnostics = showDiagnostics,
                        diagnostics = if (showDiagnostics) viewModel.diagnostics() else emptyList(),
                        onToggleDiagnostics = { showDiagnostics = !showDiagnostics },
                        onPickDestination = { tab = AppTab.DESTINATIONS },
                        // Stays on the arrow screen as the primary one-tap action. It is an
                        // action, not a place, so it is emphatically NOT a tab.
                        onSaveMyLocation = {
                            viewModel.quickSaveHere { saved ->
                                scope.launch {
                                    snackbarHost.showSnackbar(
                                        if (saved != null) {
                                            "Saved as \"${saved.name}\" — rename it from Saved"
                                        } else {
                                            "No position fix yet, nothing to save"
                                        },
                                    )
                                }
                            }
                        },
                        onAddDestination = {
                            tab = AppTab.DESTINATIONS
                            editor = Editor.New
                        },
                        onOpenMap = { tab = AppTab.MAP },
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
                                latText = "%.6f".format(it.position.lat),
                                lonText = "%.6f".format(it.position.lon),
                            )
                            editor = Editor.Existing(it)
                        },
                        onToggleFavourite = viewModel::toggleFavourite,
                        onDelete = { target ->
                            viewModel.deleteDestination(target) { removed ->
                                scope.launch {
                                    val result = snackbarHost.showSnackbar(
                                        message = "Deleted \"${removed.name}\"",
                                        actionLabel = "Undo",
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

                    AppTab.MAP -> if (mapEverOpened) {
                        MapScreen(
                            tier = viewModel.mapTier(),
                            onBack = { tab = AppTab.ARROW },
                            onOpenRegions = { /* v1: region browser */ },
                            onRemindWhenOnline = { /* v1: WorkManager job */ },
                        )
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

/**
 * Tab icons come from material-icons-core only, which ships with material3. The arrow uses the
 * app's own vector so the first tab needs no icon dependency whatsoever.
 */
@Composable
private fun TabIcon(entry: AppTab) {
    when (entry) {
        AppTab.ARROW -> Icon(
            painter = painterResource(R.drawable.ic_notification_arrow),
            contentDescription = null,
        )
        AppTab.DESTINATIONS -> Icon(Icons.Filled.List, contentDescription = null)
        AppTab.MAP -> Icon(Icons.Filled.Place, contentDescription = null)
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
