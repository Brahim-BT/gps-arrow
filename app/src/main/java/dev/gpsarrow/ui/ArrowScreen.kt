package dev.gpsarrow.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.gpsarrow.core.DistanceUnits
import dev.gpsarrow.core.Format
import dev.gpsarrow.core.FixQuality
import dev.gpsarrow.core.Geo
import dev.gpsarrow.core.HeadingSource
import dev.gpsarrow.core.NavigationState
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The home screen and the entire product in one view.
 *
 * Design rules, in priority order:
 *  1. The arrow and the distance are readable at arm's length in sunlight.
 *  2. The user is always told how much to trust what they're looking at (fix quality,
 *     heading source, calibration). A confidently wrong arrow is the worst possible failure.
 *  3. Nothing here needs a map, a network, or Play Services.
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
    onPickDestination: () -> Unit,
    onSaveHere: () -> Unit,
    onOpenMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        StatusRow(state, satellitesUsed, satellitesVisible, headingSourceLabel)

        degraded.forEach { reason -> Warning(reason) }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.destination == null -> EmptyPrompt(onPickDestination)
                state.fix == null -> AcquiringPrompt(satellitesUsed, satellitesVisible)
                else -> ArrowAndDistance(state, units)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = onSaveHere, modifier = Modifier.weight(1f)) {
                Text("Save here")
            }
            OutlinedButton(onClick = onOpenMap, modifier = Modifier.weight(1f)) {
                Text("Map")
            }
            Button(onClick = onPickDestination, modifier = Modifier.weight(1f)) {
                Text("Destinations")
            }
        }
    }
}

@Composable
private fun ArrowAndDistance(state: NavigationState, units: DistanceUnits) {
    val target = state.relativeArrowDeg ?: 0.0
    val rotation = remember { Animatable(target.toFloat()) }

    // Animate along the *shortest* arc so the needle never spins the long way at the 359->0 wrap.
    LaunchedEffect(target) {
        val current = rotation.value.toDouble()
        val delta = Geo.angleDeltaDegrees(current, target)
        rotation.animateTo((current + delta).toFloat(), animationSpec = tween(220))
    }

    val stale = state.quality == FixQuality.STALE || state.quality == FixQuality.NONE
    val uncalibrated = state.headingSource == HeadingSource.COMPASS_UNCALIBRATED

    val arrowColor = when {
        stale -> MaterialTheme.colorScheme.onSurfaceVariant
        uncalibrated -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    // Colours must be resolved in the composable scope; DrawScope has no MaterialTheme.
    val roseColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints {
            val side = min(maxWidth.value, maxHeight.value).dp
            Canvas(
                modifier = Modifier
                    .size(side * 0.9f)
                    .aspectRatio(1f),
            ) {
                drawCompassRose(roseColor)
                rotate(rotation.value) { drawArrow(arrowColor) }
            }
        }

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

        if (uncalibrated) {
            Warning("Compass unreliable — wave the phone in a figure of eight to recalibrate.")
        }
        if (state.quality == FixQuality.STALE) {
            Warning("Position is out of date — searching for satellites.")
        }
    }
}

private fun DrawScope.drawCompassRose(color: Color) {
    val radius = size.minDimension / 2f * 0.92f
    val center = Offset(size.width / 2f, size.height / 2f)
    drawCircle(color = color, radius = radius, center = center, style = Stroke(width = 3f))
    // Ticks every 30 degrees, longer on the cardinals.
    for (deg in 0 until 360 step 15) {
        val rad = Math.toRadians(deg.toDouble() - 90.0)
        val long = deg % 90 == 0
        val inner = radius * if (long) 0.84f else 0.92f
        drawLine(
            color = color,
            start = center + Offset(
                (cos(rad) * inner).toFloat(),
                (sin(rad) * inner).toFloat(),
            ),
            end = center + Offset(
                (cos(rad) * radius).toFloat(),
                (sin(rad) * radius).toFloat(),
            ),
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Chip(
            text = when (state.quality) {
                FixQuality.GOOD -> "±${state.fix?.accuracyMeters?.toInt() ?: 0} m"
                FixQuality.POOR -> "Weak ±${state.fix?.accuracyMeters?.toInt() ?: 0} m"
                FixQuality.STALE -> "Stale fix"
                FixQuality.NONE -> "No fix"
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
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Warning(text: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
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

@Composable
private fun EmptyPrompt(onPickDestination: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Nothing to point at yet",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Save a destination, or paste coordinates, a plus code or an MGRS reference.",
            modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onPickDestination, modifier = Modifier.padding(top = 20.dp)) {
            Text("Choose a destination")
        }
    }
}

/**
 * The cold-start state. With no network there is no A-GNSS assistance data, so a first fix
 * genuinely can take 30-90 seconds under open sky. Saying so is the difference between
 * "it's working" and "it's broken".
 */
@Composable
private fun AcquiringPrompt(used: Int, visible: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (visible == 0) "Looking for satellites" else "$used of $visible satellites",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "With no internet there's no assistance data, so the first fix can take a " +
                "minute or two. Standing where you can see open sky helps a lot.",
            modifier = Modifier.padding(top = 10.dp, start = 24.dp, end = 24.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
