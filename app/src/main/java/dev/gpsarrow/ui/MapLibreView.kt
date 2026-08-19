package dev.gpsarrow.ui

import android.util.Log
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * A MapLibre `MapView` in Compose, wired to the composition's lifecycle, that cannot take the map
 * tab down with it.
 *
 * ## Failing soft is the point
 *
 * MapLibre loads native `.so` libraries per ABI. If they are absent for the device's
 * architecture, or the GL context cannot be created, construction throws — and a crash in the map
 * tab would be a crash in an app whose entire premise is that the arrow keeps working. So
 * construction is guarded and failure reports through [onUnavailable] rather than propagating.
 * The caller then shows the ordinary empty state, which already knows how to say "no map here,
 * and the arrow still works".
 *
 * `UnsatisfiedLinkError` and `NoClassDefFoundError` are caught by name because they are `Error`s,
 * not `Exception`s: a bare `catch (e: Exception)` would sail straight past the exact failure this
 * exists to guard. Anything else still crashes loudly — an `OutOfMemoryError` or a programming
 * mistake is not something to swallow.
 *
 * ## Lifecycle
 *
 * `MapView` predates Compose and needs its callbacks forwarded by hand. Missing `onDestroy` leaks
 * the GL surface; missing `onStop` leaves the renderer running behind a backgrounded app, which
 * on a navigation device is real battery.
 *
 * ## Scope of this stage
 *
 * This proves the dependency resolves, the native libraries load, and a style parses. The PMTiles
 * source, the position marker, heading-up rotation and the north indicator are the next stage —
 * deliberately, so "the dependency works" and "the map renders correctly" are two separate
 * answers rather than one entangled failure.
 */
@Composable
fun MapLibreView(
    styleJson: String,
    modifier: Modifier = Modifier,
    onUnavailable: (String) -> Unit = {},
    onMapReady: (MapLibreMap) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Created once, eagerly, so the failure is known before anything tries to lay it out.
    val created = remember {
        try {
            MapLibre.getInstance(context)          // safe to call repeatedly
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
            try {
                created.onStop()
                created.onDestroy()
            } catch (e: Exception) {
                Log.w(TAG, "teardown failed", e)
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { created },
        update = { view ->
            try {
                view.getMapAsync { map ->
                    map.setStyle(Style.Builder().fromJson(styleJson)) {
                        onMapReady(map)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "style failed to load", e)
                onUnavailable(e.message ?: e.javaClass.simpleName)
            }
        },
    )
}

/**
 * A placeholder that occupies the same space when the renderer is unavailable.
 *
 * Exists so the caller has something structurally identical to swap in, rather than a branch that
 * changes the layout and makes the failure look like a different screen.
 */
@Composable
fun MapUnavailablePlaceholder(modifier: Modifier = Modifier) {
    AndroidView(modifier = modifier, factory = { context -> View(context) })
}

private const val TAG = "MapLibreView"
