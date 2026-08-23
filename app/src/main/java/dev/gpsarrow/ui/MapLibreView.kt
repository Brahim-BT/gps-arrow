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
import dev.gpsarrow.core.LatLon
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
    /** Bearing to follow, or null to leave the camera's rotation alone. */
    followBearingDeg: Double?,
    /**
     * Position to keep centred, or null to leave the camera's target alone.
     *
     * Must be the SMOOTHED position. Following the raw fix would step the whole map by the
     * jitter amplitude at the fix rate — reintroducing the 1 Hz stutter the smoothing round
     * just removed, through a different door.
     */
    followTarget: LatLon?,
    /**
     * Camera top padding in pixels that rides the target below screen centre — the forward-view
     * layout of a navigating map — or null to hold the geometry as it is. Callers null it while
     * not following or standing still; see [MapCamera.DOT_OFFSET_TOP_FRACTION].
     */
    dotOffsetTopPx: Double?,
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

    // Follow the user as they move. This is the default behaviour of a navigation map, and its
    // absence was an over-correction: the rule is "nothing but the user moves the camera WHILE
    // THEY ARE INTERACTING OR AFTER THEY HAVE PANNED", not "nothing ever follows the user".
    //
    // Deliberately a direct assignment rather than an animation. At 1 Hz the smoothed position
    // moves 1.3 px per second at zoom 16 while walking and 5.1 px at zoom 18 — below the
    // threshold of noticing. An animation would be smoother at driving speed, but a tween
    // retargeted on every sample is precisely the shape that made the arrow unresponsive, and
    // that risk is not worth 20 px of polish in a case the user is rarely in.
    //
    // Only the TARGET moves. Zoom is the user's intention exactly as their pan is, and bearing
    // has its own effect above; rebuilding either here would fight them.
    LaunchedEffect(mapRef.value, followTarget) {
        val map = mapRef.value ?: return@LaunchedEffect
        val target = followTarget ?: return@LaunchedEffect
        if (interacting.get()) return@LaunchedEffect
        runCatching {
            map.cameraPosition = CameraPosition.Builder(map.cameraPosition)
                .target(LatLng(target.lat, target.lon))
                .build()
        }.onFailure { Log.w(TAG, "could not follow position", it) }
    }

    // The forward-view dot offset. Camera padding insets the viewport and the target renders at
    // the centre of what remains, so a top inset puts the dot below centre — the driver sees the
    // road ahead rather than a screen half full of where they have been.
    //
    // Padding persists through every other camera write here (they all rebuild from the current
    // position), so it is set only when the requested inset changes, never per fix. Skipped
    // while a finger is down for the same reason everything else is.
    LaunchedEffect(mapRef.value, dotOffsetTopPx) {
        val map = mapRef.value ?: return@LaunchedEffect
        if (interacting.get()) return@LaunchedEffect
        runCatching {
            val current = map.cameraPosition
            val top = dotOffsetTopPx ?: 0.0
            val existing = current.padding
            if (existing != null && existing.size == 4 && existing[1] == top) {
                return@runCatching
            }
            map.cameraPosition = CameraPosition.Builder(current)
                .padding(0.0, top, 0.0, 0.0)
                .build()
        }.onFailure { Log.w(TAG, "could not apply the forward-view offset", it) }
    }

    // The only other way the app moves the camera: an explicit, one-shot user request. Keyed on
    // the command's id so that asking twice for the same place works the second time too.
    //
    // Deliberately NOT gated on `interacting`, and that is the one exception in this file. A
    // command only originates from a button tap, and `interacting` stays true until the camera
    // settles — so a user who flings the map, lets go, and taps "centre on me" while it is still
    // gliding would have their tap swallowed. The command also clears the flag, because asking
    // to be centred is asking for the app to take the camera back.
    LaunchedEffect(mapRef.value, cameraCommand?.id) {
        val map = mapRef.value ?: return@LaunchedEffect
        val command = cameraCommand ?: return@LaunchedEffect
        interacting.set(false)
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
