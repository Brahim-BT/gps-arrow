package dev.gpsarrow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gpsarrow.core.ArrowMode
import dev.gpsarrow.core.DistanceUnits
import dev.gpsarrow.core.FixQuality
import dev.gpsarrow.core.Format
import dev.gpsarrow.core.HeadingSource
import dev.gpsarrow.core.NavigationState
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The home screen and the entire product in one view.
 *
 * Design rules, in priority order:
 *  1. The needle moves whenever the phone moves. It is a compass first and a navigator second,
 *     so losing the GNSS fix downgrades it to pointing north — it never freezes or disappears.
 *  2. The arrow and the distance are readable at arm's length in sunlight.
 *  3. The user is always told how much to trust what they're looking at.
 */
@Composable
fun ArrowScreen(
    state: NavigationState,
    headingSourceLabel: String,
    satellitesUsed: Int,
    satellitesVisible: Int,
    /** Subsystems that failed but did not take the app down. Empty in the normal case. */
    degraded: List<String>,
    units: DistanceUnits,
    showDiagnostics: Boolean,
    diagnostics: List<Pair<String, String>>,
    onToggleDiagnostics: () -> Unit,
    onPickDestination: () -> Unit,
    onSaveMyLocation: () -> Unit,
    onAddDestination: () -> Unit,
    onOpenMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Long-press anywhere on the status row to open the diagnostics panel.
        Box(
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures(onLongPress = { onToggleDiagnostics() })
            },
        ) {
            StatusRow(state, satellitesUsed, satellitesVisible, headingSourceLabel)
        }

        degraded.forEach { reason -> Warning(reason) }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (showDiagnostics) {
                DiagnosticsPanel(diagnostics, onToggleDiagnostics)
            } else {
                // NOTE: no `fix == null` gate here. The needle is driven by the heading, which
                // exists indoors with no satellites at all. Gating the whole view on a fix was
                // why the arrow looked dead on the first device test.
                CompassFace(state, units, onPickDestination)
            }
        }

        ActionButtons(
            hasFix = state.fix != null,
            onSaveMyLocation = onSaveMyLocation,
            onAddDestination = onAddDestination,
            onPickDestination = onPickDestination,
            onOpenMap = onOpenMap,
        )
    }
}

/**
 * Buttons sized to their content across two rows.
 *
 * The previous single row gave three equal `weight(1f)` slots, so "Destinations" got the same
 * width as "Map" and its label was squeezed. The primary action now owns a full-width row and
 * the secondary actions share the second, which keeps every label on one line down to very
 * narrow screens.
 */
@Composable
private fun ActionButtons(
    hasFix: Boolean,
    onSaveMyLocation: () -> Unit,
    onAddDestination: () -> Unit,
    onPickDestination: () -> Unit,
    onOpenMap: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onSaveMyLocation,
            enabled = hasFix,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            contentPadding = ButtonDefaults.ContentPadding,
        ) {
            Text(
                text = if (hasFix) "Save my location" else "Save my location (no fix yet)",
                maxLines = 1,
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onPickDestination,
                modifier = Modifier.weight(1f),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                Text("Destinations", maxLines = 1, style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(
                onClick = onAddDestination,
                modifier = Modifier.weight(1f),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                Text("Add point", maxLines = 1, style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(
                onClick = onOpenMap,
                modifier = Modifier.weight(0.6f),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                Text("Map", maxLines = 1, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun CompassFace(
    state: NavigationState,
    units: DistanceUnits,
    onPickDestination: () -> Unit,
) {
    val stale = state.quality == FixQuality.STALE || state.quality == FixQuality.NONE
    val uncalibrated = state.headingSource == HeadingSource.COMPASS_UNCALIBRATED
    val mode = state.arrowMode

    val arrowColor = when {
        uncalibrated -> MaterialTheme.colorScheme.error
        mode == ArrowMode.NORTH -> MaterialTheme.colorScheme.onSurfaceVariant
        stale -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    val roseColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)

    if (mode == ArrowMode.NONE) {
        NoHeadingPrompt()
        return
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints {
            val side = min(maxWidth.value, maxHeight.value).dp
            Canvas(
                modifier = Modifier
                    .size(side * 0.85f)
                    .aspectRatio(1f),
            ) {
                drawCompassRose(roseColor)
                // Rotated directly from state — deliberately NOT animated. The previous
                // Animatable + LaunchedEffect(target) restarted a 220 ms tween on every
                // sensor sample (~50 Hz), so the needle never finished a movement and looked
                // sluggish. CircularSmoother already does the smoothing, upstream.
                rotate((state.arrowDeg ?: 0.0).toFloat()) { drawArrow(arrowColor) }
            }
        }

        when (mode) {
            ArrowMode.TARGET -> {
                state.distanceMeters?.let { d ->
                    Text(
                        text = Format.distance(d, units),
                        style = MaterialTheme.typography.displayLarge,
                        color = if (stale) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onBackground,
                    )
                }
                state.bearingToDestinationDeg?.let { b ->
                    Text(
                        text = Format.bearing(b),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.destination?.let {
                    Text(
                        text = it.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ArrowMode.NORTH -> {
                Text(
                    text = state.headingDeg?.let { Format.bearing(it) } ?: "—",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = if (state.destination == null) {
                        "Pointing north — no destination chosen yet"
                    } else {
                        "Pointing north — waiting for a position fix"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                if (state.destination == null) {
                    Text(
                        text = "Tap Destinations to pick one.",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .pointerInput(Unit) {
                                detectTapGestures { onPickDestination() }
                            },
                    )
                }
            }

            ArrowMode.NONE -> Unit
        }

        if (uncalibrated) {
            Warning("Compass unreliable — wave the phone in a figure of eight to recalibrate.")
        }
        if (mode == ArrowMode.TARGET && state.quality == FixQuality.STALE) {
            Warning("Position is out of date — searching for satellites.")
        }
    }
}

/** Everything needed to tell a dead sensor from a dead pipeline, without a USB cable. */
@Composable
private fun DiagnosticsPanel(rows: List<Pair<String, String>>, onClose: () -> Unit) {
    Card(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Diagnostics", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Close",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.pointerInput(Unit) { detectTapGestures { onClose() } },
                )
            }
            Text(
                "Long-press the status chips at any time to open or close this.",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            rows.forEach { (key, value) ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = key,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = value,
                        modifier = Modifier.weight(1.2f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawCompassRose(color: Color) {
    val radius = size.minDimension / 2f * 0.92f
    val center = Offset(size.width / 2f, size.height / 2f)
    drawCircle(color = color, radius = radius, center = center, style = Stroke(width = 3f))
    for (deg in 0 until 360 step 15) {
        val rad = Math.toRadians(deg.toDouble() - 90.0)
        val long = deg % 90 == 0
        val inner = radius * if (long) 0.84f else 0.92f
        drawLine(
            color = color,
            start = center + Offset((cos(rad) * inner).toFloat(), (sin(rad) * inner).toFloat()),
            end = center + Offset((cos(rad) * radius).toFloat(), (sin(rad) * radius).toFloat()),
            strokeWidth = if (long) 5f else 2f,
        )
    }
}

private fun DrawScope.drawArrow(color: Color) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = size.minDimension / 2f * 0.72f

    val path = Path().apply {
        moveTo(cx, cy - r)                        // tip
        lineTo(cx + r * 0.42f, cy + r * 0.55f)    // right shoulder
        lineTo(cx, cy + r * 0.22f)                // tail notch
        lineTo(cx - r * 0.42f, cy + r * 0.55f)    // left shoulder
        close()
    }
    drawPath(path, color)
}

@Composable
private fun StatusRow(
    state: NavigationState,
    used: Int,
    visible: Int,
    headingSourceLabel: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Chip(
            text = when (state.quality) {
                FixQuality.GOOD -> "±${state.fix?.accuracyMeters?.toInt() ?: 0} m"
                FixQuality.POOR -> "weak ±${state.fix?.accuracyMeters?.toInt() ?: 0} m"
                FixQuality.STALE -> "stale fix"
                FixQuality.NONE -> "no fix"
            },
            emphasised = state.quality == FixQuality.GOOD,
        )
        Chip(text = "$used/$visible sats")
        Chip(text = headingSourceLabel)
    }
}

@Composable
private fun Chip(text: String, emphasised: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (emphasised) MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)
        else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun Warning(text: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
        ),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** No rotation vector, no accelerometer+magnetometer pair, and not moving. Rare but real. */
@Composable
private fun NoHeadingPrompt() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "No compass",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "This device has no usable compass sensor. The arrow will work once you " +
                "start moving, using your GPS course instead.",
            modifier = Modifier.padding(top = 10.dp, start = 24.dp, end = 24.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
