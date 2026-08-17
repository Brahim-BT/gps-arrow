package dev.gpsarrow

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

private enum class Screen { ARROW, DESTINATIONS, ADD, MAP }

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
    val requestNotifications = rememberNotificationPermissionRequest()

    var screen by remember { mutableStateOf(Screen.ARROW) }
    var pendingText by remember { mutableStateOf<String?>(null) }
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

    when (screen) {
        Screen.ARROW -> ArrowScreen(
            state = state,
            headingSourceLabel = state.headingSource.label(),
            satellitesUsed = gnss.satellitesUsed,
            satellitesVisible = gnss.satellitesVisible,
            degraded = degraded,
            units = units,
            onPickDestination = { screen = Screen.DESTINATIONS },
            onSaveHere = { screen = Screen.ADD },
            onOpenMap = { screen = Screen.MAP },
            modifier = modifier,
        )

        Screen.DESTINATIONS -> DestinationsScreen(
            destinations = destinations,
            currentPosition = state.fix?.position,
            selectedId = state.destination?.id,
            units = units,
            onSelect = {
                viewModel.selectDestination(it)
                screen = Screen.ARROW
            },
            onDelete = viewModel::deleteDestination,
            onAdd = { screen = Screen.ADD },
            onBack = { screen = Screen.ARROW },
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
            onSaveCurrent = { name ->
                viewModel.saveCurrentPosition(name) { saved ->
                    saved?.let { viewModel.selectDestination(it) }
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
}
