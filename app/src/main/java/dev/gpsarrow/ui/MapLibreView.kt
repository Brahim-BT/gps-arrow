package dev.gpsarrow.ui

import android.util.Log
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.gpsarrow.maps.CameraCommand
import dev.gpsarrow.maps.MapCamera
import dev.gpsarrow.maps.MapMarkers
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * A MapLibre `MapView` in Compose.
 *
 * ## The one rule about the camera
 *
 * **While the user is touching the map, or after they have moved it, nothing but the user moves
 * the camera.** No exceptions for bearing, target or zoom. The only way the app regains control is
 * an explicit [CameraCommand] — which is what "centre on me" and the north indicator send.
 *
 * This is enforced structurally rather than by remembering to check, because the first version
 * had **four** separate paths that wrote the camera, and every one of them fought the user:
 *
 *  1. `AndroidView`'s `update` lambda applied a `camera` parameter. `update` runs on *every*
 *     recomposition, and recomposition happens at the 1 Hz fix rate — so the camera was slammed
 *     back to the position dot once a second. That is the "pan gets dragged back" symptom, and
 *     the "interrupted about once a second" timing was the fix rate showing through.
 *  2. The bearing effect wrote `cameraPosition` whenever heading changed. A heading sample
 *     arriving mid-rotate cancelled the gesture. That is the "two-finger rotate cannot be
 *     completed" symptom.
 *  3. Both listeners were registered inside `update`, so a new pair was added on every
 *     recomposition — hundreds of duplicates, each firing.
 *  4. `onCameraMoved` fed state that triggered recomposition that ran `update` that wrote the
 *     camera — a feedback loop with the user's finger inside it.
 *
 * So now: `update` is empty, listeners are registered exactly once, the initial camera is applied
 * exactly once, and every write checks [interacting] first.
 */
@Composable
fun MapLibreView(
    styleJson: String,
    initialCamera: MapCamera?,
    cameraCommand: CameraCommand?,
    positionGeoJson: String,
    destinationGeoJson: String,
    /** Bearing to follow, or null to leave the camera entirely alone. */
    followBearingDeg: Double?,
    modifier: Modifier = Modifier,
    onUnavailable: (String) -> Unit = {},
    onCameraMoved: (MapCamera) -> Unit = {},
    onUserGesture: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }

    /**
     * True from the moment a finger starts moving the camera until the camera settles.
     *
     * Not Compose state on purpose: it is read inside listeners and effects that must see the
     * current value immediately, and routing it through recomposition would let a write slip
     * through in the frame before the state propagated — which is precisely the race that makes
     * a gesture feel like it was interrupted.
     */
    val interacting = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    /** The initial camera is applied once. After that the user owns it. */
    val placed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    val created = remember {
        try {
            MapLibre.getInstance(context)
            MapView(context).also { it.onCreate(null) }
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "MapLibre native library missing for this ABI", e); null
        } catch (e: NoClassDefFoundError) {
            Log.w(TAG, "MapLibre classes missing", e); null
        } catch (e: Exception) {
            Log.w(TAG, "MapLibre failed to initialise", e); null
        }
    }

    if (created == null) {
        LaunchedEffect(Unit) { onUnavailable("renderer unavailable") }
        return
    }

    DisposableEffect(lifecycleOwner, created) {
        val observer = LifecycleEventObserver { _, event ->
            try {
                when (event) {
                    Lifecycle.Event.ON_START -> created.onStart()
                    Lifecycle.Event.ON_RESUME -> created.onResume()
                    Lifecycle.Event.ON_PAUSE -> created.onPause()
                    Lifecycle.Event.ON_STOP -> created.onStop()
                    Lifecycle.Event.ON_DESTROY -> created.onDestroy()
                    else -> Unit
                }
            } catch (e: Exception) {
                Log.w(TAG, "lifecycle callback failed: $event", e)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapRef.value = null
            try {
                created.onStop()
                created.onDestroy()
            } catch (e: Exception) {
                Log.w(TAG, "teardown failed", e)
            }
        }
    }

    // Everything that happens once, happens here — keyed on the view and the style, never on a
    // value that changes at the fix rate.
    LaunchedEffect(created, styleJson) {
        runCatching {
            created.getMapAsync { map ->
                // MapLibre draws three pieces of its own chrome: a compass, a logo and an
                // attribution badge. We draw our own compass (NorthIndicator) and our own
                // attribution (which the ODbL licence requires), so all three of MapLibre's are
                // turned off rather than overlapping ours.
                map.uiSettings.isCompassEnabled = false
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isAttributionEnabled = false
                // Tilt makes a north reference meaningless and buys nothing here. Rotation stays
                // enabled: the user is allowed to turn the map by hand, which suspends following.
                map.uiSettings.isTiltGesturesEnabled = false

                map.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                        interacting.set(true)
                        onUserGesture()
                    }
                }
                map.addOnCameraIdleListener {
                    interacting.set(false)
                    val p = map.cameraPosition
                    onCameraMoved(
                        MapCamera(
                            lat = p.target?.latitude ?: 0.0,
                            lon = p.target?.longitude ?: 0.0,
                            zoom = p.zoom,
                            bearingDeg = p.bearing,
                        ),
                    )
                }

                map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                    style.getSourceAs<GeoJsonSource>(MapMarkers.POSITION_SOURCE)
                        ?.setGeoJson(positionGeoJson)
                    style.getSourceAs<GeoJsonSource>(MapMarkers.DESTINATION_SOURCE)
                        ?.setGeoJson(destinationGeoJson)

                    // Once, and only if the user has not already taken over.
                    if (initialCamera != null && placed.compareAndSet(false, true) &&
                        !interacting.get()
                    ) {
                        map.cameraPosition = CameraPosition.Builder()
                            .target(LatLng(initialCamera.lat, initialCamera.lon))
                            .zoom(initialCamera.zoom)
                            .bearing(initialCamera.bearingDeg)
                            .build()
                    }
                    mapRef.value = map
                }
            }
        }.onFailure {
            Log.w(TAG, "map setup failed", it)
            onUnavailable(it.message ?: it.javaClass.simpleName)
        }
    }

    // update is EMPTY on purpose. Anything here runs on every recomposition, and recomposition
    // happens at the fix rate. This is where the camera used to be rewritten once a second.
    AndroidView(modifier = modifier, factory = { created }, update = { })

    // Markers change with every fix. They touch sources, never the camera, so they are safe to
    // apply while the user is panning.
    LaunchedEffect(mapRef.value, positionGeoJson, destinationGeoJson) {
        val map = mapRef.value ?: return@LaunchedEffect
        runCatching {
            map.style?.let { style ->
                style.getSourceAs<GeoJsonSource>(MapMarkers.POSITION_SOURCE)
                    ?.setGeoJson(positionGeoJson)
                style.getSourceAs<GeoJsonSource>(MapMarkers.DESTINATION_SOURCE)
                    ?.setGeoJson(destinationGeoJson)
            }
        }.onFailure { Log.w(TAG, "could not update markers", it) }
    }

    // Heading-up rotation. Skipped entirely while a finger is down, and skipped when the caller
    // passes null — which it does the moment following is suspended.
    LaunchedEffect(mapRef.value, followBearingDeg) {
        val map = mapRef.value ?: return@LaunchedEffect
        val target = followBearingDeg ?: return@LaunchedEffect
        if (interacting.get()) return@LaunchedEffect
        runCatching {
            map.cameraPosition = CameraPosition.Builder(map.cameraPosition).bearing(target).build()
        }.onFailure { Log.w(TAG, "could not apply bearing", it) }
    }

    // The only other way the app moves the camera: an explicit, one-shot user request. Keyed on
    // the command's id so that asking twice for the same place works the second time too.
    LaunchedEffect(mapRef.value, cameraCommand?.id) {
        val map = mapRef.value ?: return@LaunchedEffect
        val command = cameraCommand ?: return@LaunchedEffect
        runCatching {
            val current = map.cameraPosition
            map.cameraPosition = CameraPosition.Builder(current)
                .target(LatLng(command.lat, command.lon))
                .zoom(command.zoom ?: current.zoom)
                .bearing(command.bearingDeg ?: current.bearing)
                .build()
        }.onFailure { Log.w(TAG, "could not apply camera command", it) }
    }
}

/**
 * A placeholder that occupies the same space when the renderer is unavailable, so the failure
 * does not change the layout and look like a different screen.
 */
@Composable
fun MapUnavailablePlaceholder(modifier: Modifier = Modifier) {
    AndroidView(modifier = modifier, factory = { context -> View(context) })
}

private const val TAG = "MapLibreView"
