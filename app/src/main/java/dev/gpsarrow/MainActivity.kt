package dev.gpsarrow

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gpsarrow.core.Destination
import dev.gpsarrow.core.DistanceUnits
import dev.gpsarrow.core.Format
import dev.gpsarrow.service.NavigationService
import dev.gpsarrow.ui.AddDestinationScreen
import dev.gpsarrow.ui.ArrowScreen
import dev.gpsarrow.ui.DestinationsScreen
import dev.gpsarrow.ui.MapScreen
import dev.gpsarrow.ui.PermissionGate
import dev.gpsarrow.ui.rememberNotificationPermissionRequest
import dev.gpsarrow.ui.theme.GpsArrowTheme
import kotlinx.coroutines.launch

private enum class Screen { ARROW, DESTINATIONS, ADD, EDIT, MAP }

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

    var screen by remember { mutableStateOf(Screen.ARROW) }
    var pendingText by remember { mutableStateOf<String?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Destination?>(null) }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val units = DistanceUnits.METRIC   // wire to a settings store in v0.2

    // A shared geo: link or pasted text jumps straight to the add screen, pre-filled.
    LaunchedEffect(initialSharedText) {
        if (!initialSharedText.isNullOrBlank()) {
            pendingText = initialSharedText
            screen = Screen.ADD
            onSharedTextConsumed()
        }
    }

    // Start / stop the foreground service in step with having something to navigate to.
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

    val modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .consumeWindowInsets(WindowInsets.safeDrawing)

    Box(Modifier.fillMaxSize()) {
    when (screen) {
        Screen.ARROW -> ArrowScreen(
            state = state,
            headingSourceLabel = state.headingSource.label(),
            satellitesUsed = gnss.satellitesUsed,
            satellitesVisible = gnss.satellitesVisible,
            degraded = degraded,
            units = units,
            showDiagnostics = showDiagnostics,
            diagnostics = if (showDiagnostics) viewModel.diagnostics() else emptyList(),
            onToggleDiagnostics = { showDiagnostics = !showDiagnostics },
            onPickDestination = { screen = Screen.DESTINATIONS },
            // One tap, no form. This is the panic button for "remember where I parked".
            onSaveMyLocation = {
                viewModel.quickSaveHere { saved ->
                    scope.launch {
                        snackbarHost.showSnackbar(
                            if (saved != null) "Saved as \"${saved.name}\" — rename it from Destinations"
                            else "No position fix yet, nothing to save",
                        )
                    }
                }
            },
            onAddDestination = { screen = Screen.ADD },
            onOpenMap = { screen = Screen.MAP },
            modifier = modifier,
        )

        Screen.DESTINATIONS -> DestinationsScreen(
            destinations = destinations,
            currentPosition = viewModel.distanceOrigin(),
            positionIsStale = viewModel.originIsStale(),
            selectedId = state.destination?.id,
            sort = sort,
            units = units,
            onSortChange = viewModel::setSort,
            onSelect = {
                viewModel.selectDestination(it)
                screen = Screen.ARROW
            },
            onEdit = { editing = it; screen = Screen.EDIT },
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
            onAdd = { screen = Screen.ADD },
            onBack = { screen = Screen.ARROW },
            modifier = modifier,
        )

        Screen.EDIT -> AddDestinationScreen(
            currentPosition = state.fix?.position,
            editing = editing,
            onSave = { name, position, _ ->
                editing?.let { target ->
                    viewModel.updateDestination(target.id, name, position) {
                        editing = null
                        screen = Screen.DESTINATIONS
                    }
                }
            },
            onBack = { editing = null; screen = Screen.DESTINATIONS },
            modifier = modifier,
        )

        Screen.ADD -> AddDestinationScreen(
            currentPosition = state.fix?.position,
            initialText = pendingText.orEmpty(),
            onSave = { name, position, source ->
                viewModel.saveDestination(name, position, source) {
                    viewModel.selectDestination(it)
                    pendingText = null
                    screen = Screen.ARROW
                }
            },
            onBack = {
                pendingText = null
                screen = Screen.ARROW
            },
            modifier = modifier,
        )

        Screen.MAP -> MapScreen(
            tier = viewModel.mapTier(),
            onBack = { screen = Screen.ARROW },
            onOpenRegions = { /* v1: region browser */ },
            onRemindWhenOnline = { /* v1: WorkManager job on NetworkType.UNMETERED */ },
            modifier = modifier,
        )
    }

        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        )
    }
}
