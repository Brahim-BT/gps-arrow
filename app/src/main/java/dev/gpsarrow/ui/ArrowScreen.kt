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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gpsarrow.Degradation
import dev.gpsarrow.Diagnostic
import dev.gpsarrow.R
import dev.gpsarrow.core.ArrowMode
import dev.gpsarrow.core.CoordinateFormat
import dev.gpsarrow.core.DistanceUnits
import dev.gpsarrow.core.FixQuality
import dev.gpsarrow.core.Format
import dev.gpsarrow.core.HeadingSource
import dev.gpsarrow.core.NavigationState
import dev.gpsarrow.ui.theme.AppTheme
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
    degraded: List<Degradation>,
    units: DistanceUnits,
    showDiagnostics: Boolean,
    diagnostics: List<Diagnostic>,
    onToggleDiagnostics: () -> Unit,
    onPickDestination: () -> Unit,
    onSaveMyLocation: () -> Unit,
    onAddDestination: () -> Unit,
    /** Which notation the position band is showing. Hoisted so a tab switch does not reset it. */
    positionFormat: CoordinateFormat,
    onCyclePositionFormat: () -> Unit,
    onPositionCopied: () -> Unit,
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

        // Above the four-corner composition, not inside it: that layout is deliberate and
        // dropping two more rows into its middle would wreck it. One line here costs the needle
        // about 30dp of roughly 430 and leaves it comfortably dominant.
        if (!showDiagnostics) {
            PositionBand(
                state = state,
                format = positionFormat,
                onCycleFormat = onCyclePositionFormat,
                onCopied = onPositionCopied,
            )
        }

        degraded.forEach { d ->
            Notice(
                if (d.arg != null) stringResource(d.messageRes, d.arg)
                else stringResource(d.messageRes),
            )
        }

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
            // Enabled whenever there is anything at all to save. A fix that exists but is too
            // old still gets a tap, because the refusal carries the explanation; only the
            // genuinely empty case is disabled, and its own label says why.
            saveEnabled = state.fix != null,
            saveLabel = stringResource(
                when {
                    state.fix == null -> R.string.save_my_location_no_fix
                    !state.isSaveable -> R.string.save_my_location_stale
                    else -> R.string.save_my_location
                },
            ),
            onSaveMyLocation = onSaveMyLocation,
            onAddDestination = onAddDestination,
        )
    }
}

/**
 * The actions that live on the arrow screen, now that the tab bar owns navigation.
 *
 * "Destinations" and "Map" used to sit here as buttons; both are tabs, so both are gone — a
 * second way to reach the same place is just a wider tap target for the wrong reason.
 *
 * What is left is deliberately not navigation:
 *  - **Save my location** is the primary action of the whole app (the "where did I park"
 *    button), one tap from the home screen, and it is emphatically not a place.
 *  - **Add point** opens the coordinate editor. It has no tab of its own — its other entry
 *    point is the FAB inside Destinations — so it is kept as a secondary action rather than
 *    silently costing the user a tap.
 */
@Composable
private fun ActionButtons(
    saveEnabled: Boolean,
    saveLabel: String,
    onSaveMyLocation: () -> Unit,
    onAddDestination: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Button(
            onClick = onSaveMyLocation,
            enabled = saveEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            contentPadding = ButtonDefaults.ContentPadding,
        ) {
            Text(
                text = saveLabel,
                maxLines = 1,
                style = MaterialTheme.typography.labelLarge,
            )
        }

        OutlinedButton(
            onClick = onAddDestination,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            contentPadding = ButtonDefaults.TextButtonContentPadding,
        ) {
            Text(
                text = stringResource(R.string.add_point),
                maxLines = 1,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * The four-corner navigation layout from the reference: two readouts along the top, two along
 * the bottom, and the needle filling everything between them.
 *
 * The labels sit outward — above the value in the top corners, below it in the bottom ones — so
 * the small grey text always hugs the nearer screen edge and the four large values are pulled
 * towards the centre, where the eye already is. That detail is straight from the screenshots.
 *
 * Corners degrade rather than disappear. With no destination the first corner says so and the
 * distance shows a dash, because a layout that reflows depending on GPS state is harder to read
 * at a glance than one that always has its numbers in the same four places.
 */
@Composable
private fun CompassFace(
    state: NavigationState,
    units: DistanceUnits,
    onPickDestination: () -> Unit,
) {
    val context = LocalContext.current
    val numberLocale = rememberNumberLocale()
    val tokens = AppTheme.tokens
    val stale = state.quality == FixQuality.STALE || state.quality == FixQuality.NONE
    val uncalibrated = state.headingSource == HeadingSource.COMPASS_UNCALIBRATED
    val mode = state.arrowMode
    val dash = stringResource(R.string.value_unknown)

    // A needle the app cannot stand behind is drawn flat and muted; the two-stop accent fill is
    // reserved for a bearing it actually believes.
    val mutedArrow = when {
        uncalibrated -> MaterialTheme.colorScheme.error
        mode == ArrowMode.NORTH || mode == ArrowMode.ARRIVED -> tokens.label
        stale -> tokens.label
        else -> null
    }

    if (mode == ArrowMode.NONE) {
        NoHeadingPrompt()
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---------------------------------------------------------------- top corners
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            CornerReadout(
                label = stringResource(R.string.label_destination),
                value = state.destination?.name ?: stringResource(R.string.no_destination_yet),
                alignment = Alignment.Start,
                labelAbove = true,
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(Unit) { detectTapGestures { onPickDestination() } },
                valueColor = if (state.destination == null) tokens.label
                else MaterialTheme.colorScheme.onSurface,
            )
            CornerReadout(
                label = stringResource(R.string.label_distance),
                value = state.distanceMeters
                    ?.let { Format.distance(it, units, numberLocale).text(context) }
                    ?: dash,
                alignment = Alignment.End,
                labelAbove = true,
                modifier = Modifier.weight(1f),
                valueColor = if (stale) tokens.label else MaterialTheme.colorScheme.onSurface,
            )
        }

        // ---------------------------------------------------------------- the needle
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints {
                val side = min(maxWidth.value, maxHeight.value).dp
                // THE ARROW MUST NEVER MIRROR.
                //
                // Compose flips a Canvas's coordinate system under an RTL layout direction,
                // which is right for UI chrome and catastrophic here: this needle points at a
                // geographic bearing, so mirroring it sends an Arabic-reading user the wrong
                // way across an east-west axis while looking completely normal to a reviewer
                // who does not read Arabic. North is north in every language. Forcing Ltr for
                // the drawing scope pins the rose and the needle to real-world geometry; the
                // readouts around them are outside this provider and still lay out RTL.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Canvas(
                        modifier = Modifier
                            .size(side * 0.98f)
                            .aspectRatio(1f),
                    ) {
                        drawCompassRose(tokens.divider)
                        val r = size.minDimension / 2f * 0.92f
                        val cy = size.height / 2f
                        val brush = mutedArrow?.let { SolidColor(it) }
                            ?: tokens.arrowBrush(tipY = cy - r, tailY = cy + r * 0.62f)
                        // Rotated directly from state — deliberately NOT animated. The previous
                        // Animatable + LaunchedEffect(target) restarted a 220 ms tween on every
                        // sensor sample (~50 Hz), so the needle never finished a movement and
                        // looked sluggish. CircularSmoother already smooths, upstream.
                        rotate((state.arrowDeg ?: 0.0).toFloat()) { drawArrow(brush) }
                    }
                }
            }
        }

        // ---------------------------------------------------------------- bottom corners
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            CornerReadout(
                label = stringResource(R.string.label_speed),
                value = state.fix?.speedMps
                    ?.let { Format.speed(it, units, numberLocale).text(context) }
                    ?: dash,
                alignment = Alignment.Start,
                labelAbove = false,
                modifier = Modifier.weight(1f),
            )
            CornerReadout(
                label = stringResource(R.string.label_direction),
                value = state.headingDeg?.let {
                    stringArrayResource(R.array.compass_points)[Format.compassPointIndex(it)]
                } ?: dash,
                alignment = Alignment.End,
                labelAbove = false,
                modifier = Modifier.weight(1f),
            )
        }

        if (uncalibrated) Notice(stringResource(R.string.compass_unreliable))
        val targeting = mode == ArrowMode.TARGET || mode == ArrowMode.ARRIVED
        if (targeting && state.quality == FixQuality.STALE) {
            Notice(stringResource(R.string.position_out_of_date))
        }
        if (mode == ArrowMode.ARRIVED) {
            Notice(stringResource(R.string.arrived_title), Tone.GOOD)
            Notice(stringResource(R.string.arrived_explanation), Tone.INFO)
        }
        if (mode == ArrowMode.NORTH) {
            Notice(
                stringResource(
                    if (state.destination == null) R.string.pointing_north_no_destination
                    else R.string.pointing_north_waiting_fix,
                ),
                Tone.INFO,
            )
        }
        if (state.headingIsMagnetic) {
            Notice(stringResource(R.string.magnetic_north_notice), Tone.INFO)
        }
    }
}

/** Everything needed to tell a dead sensor from a dead pipeline, without a USB cable. */
@Composable
private fun DiagnosticsPanel(rows: List<Diagnostic>, onClose: () -> Unit) {
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
                Text(
                    text = stringResource(R.string.diagnostics_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    stringResource(R.string.diagnostics_close),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.pointerInput(Unit) { detectTapGestures { onClose() } },
                )
            }
            Text(
                stringResource(R.string.diagnostics_hint),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            rows.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(row.labelRes),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = when {
                            row.value.isEmpty() -> stringResource(R.string.diag_none)
                            row.unitRes != null -> stringResource(row.unitRes, row.value)
                            else -> row.value
                        },
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

/**
 * The needle: a swept dart, not a triangle.
 *
 * Proportions follow the reference — the trailing points reach much wider and further back than
 * a plain triangle's, and the tail notch is deep, which is what makes it read as a dart rather
 * than a wedge at this size. Width across the trailing points is about 0.77 of the total length.
 *
 * [brush] is a two-stop fill along the arrow's own axis, bright at the tip and deep at the tail.
 * That is the one place this design keeps a gradient, and it is structure rather than gloss: a
 * single flat orange shape filling most of a black screen reads as unfinished, and the shading
 * is what gives the needle a direction you can see at a glance. Because the whole drawing scope
 * is rotated, the shading rotates with it.
 */
private fun DrawScope.drawArrow(brush: Brush) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = size.minDimension / 2f * 0.92f

    val path = Path().apply {
        moveTo(cx, cy - r)                        // tip
        lineTo(cx + r * 0.62f, cy + r * 0.62f)    // trailing point
        lineTo(cx, cy + r * 0.10f)                // tail notch, deep
        lineTo(cx - r * 0.62f, cy + r * 0.62f)    // trailing point
        close()
    }
    drawPath(path, brush)
}

@Composable
private fun StatusRow(
    state: NavigationState,
    used: Int,
    visible: Int,
    headingSourceLabel: String,
) {
    val numberLocale = rememberNumberLocale()
    val accuracy = Format.number("%d", numberLocale, state.fix?.accuracyMeters?.toInt() ?: 0)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Chip(
            text = when (state.quality) {
                FixQuality.GOOD -> stringResource(R.string.chip_accuracy, accuracy)
                FixQuality.POOR -> stringResource(R.string.chip_accuracy_weak, accuracy)
                FixQuality.STALE -> stringResource(R.string.chip_stale_fix)
                FixQuality.NONE -> stringResource(R.string.chip_no_fix)
            },
            emphasised = state.quality == FixQuality.GOOD,
        )
        Chip(
            text = stringResource(
                R.string.chip_satellites,
                Format.number("%d", numberLocale, used),
                Format.number("%d", numberLocale, visible),
            ),
        )
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

/** How loud a [Notice] is. Colour only — the layout is identical, so nothing jumps. */
private enum class Tone { WARN, INFO, GOOD }

/**
 * A line of explanation under the arrow.
 *
 * Flat: coloured text on the black background rather than a tinted card. At this palette a card
 * behind the text would be the only lifted surface on the screen and would compete with the
 * needle, which is the thing that must dominate.
 */
@Composable
private fun Notice(text: String, tone: Tone = Tone.WARN) {
    val tokens = AppTheme.tokens
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = when (tone) {
            Tone.WARN -> MaterialTheme.colorScheme.error
            Tone.INFO -> tokens.label
            Tone.GOOD -> tokens.good
        },
        textAlign = TextAlign.Center,
    )
}

/** No rotation vector, no accelerometer+magnetometer pair, and not moving. Rare but real. */
@Composable
private fun NoHeadingPrompt() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.no_compass_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.no_compass_body),
            modifier = Modifier.padding(top = 10.dp, start = 24.dp, end = 24.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
