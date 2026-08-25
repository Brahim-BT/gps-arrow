package dev.gpsarrow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import dev.gpsarrow.R
import dev.gpsarrow.ui.theme.AppTheme

/**
 * A small compass dial showing where north is on a rotating map, and a way back to north-up.
 *
 * ## It does exactly one thing
 *
 * Tapping it faces north **and** resumes following the heading. Those are not two jobs: after a
 * user has panned away, "put me back the way it was" is a single intention, and splitting it into
 * two controls would make the user perform a two-step recovery from a state they did not choose
 * deliberately. One tap, one meaning.
 *
 * ## The needle is mirror-symmetric, and that is not cosmetic
 *
 * Each half used to be a single-sided sliver — the north triangle had one outer vertex to the
 * right of the axis, the south one had its only outer vertex to the left — and their inner
 * vertices sat either side of the centre rather than on it. Combined, the two halves were
 * 180°-rotationally symmetric instead of mirror-symmetric, so the thing read as a skewed pinwheel
 * with a gap through its middle rather than as a needle. Both blades now share a waist on the
 * centre line, which makes a gap or an overlap unrepresentable rather than merely fixed.
 *
 * ## The layout direction is locked, and that matters
 *
 * This is drawn inside a forced left-to-right scope, exactly like the arrow on the arrow screen.
 * Under a right-to-left layout — which this app has, in Arabic — a mirrored transform would flip
 * the needle horizontally, so a needle pointing north-east would render pointing north-west. It
 * would be wrong by up to 180°, it would look entirely plausible, and it would be invisible to
 * any reviewer who does not read the language. A compass that lies in one locale is worse than no
 * compass at all.
 *
 * The rotation itself comes from [dev.gpsarrow.core.MapOrientation.northIndicatorDegrees], which
 * is unit-tested at five angles including 45° and 270° — a sign error there looks correct at 0°
 * and 180°.
 */
@Composable
fun NorthIndicator(
    northDegrees: Double,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.cd_face_north)
    val tokens = AppTheme.tokens
    val northColor = MaterialTheme.colorScheme.error
    val southColor = MaterialTheme.colorScheme.onSurface
    val ringColor = tokens.label.copy(alpha = 0.4f)
    // Localised north. The array's first entry is north in each language, and it is the same
    // source the arrow screen's bearing readout reads, so the dial cannot disagree with it.
    val northLetter = stringArrayResource(R.array.compass_points)[0]

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier = modifier
                // 48dp is the platform's minimum comfortable target. The dial inside is smaller;
                // the touchable area is not.
                .size(48.dp)
                .background(
                    MaterialTheme.colorScheme.background.copy(alpha = 0.65f),
                    CircleShape,
                )
                .clickable(onClick = onTap)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            // The letter rotates with the needle, the way a real dial's card does. A fixed "N"
            // over a turning needle would label the top of the screen, not north.
            Box(
                modifier = Modifier
                    .size(DIAL)
                    .rotate(northDegrees.toFloat()),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val r = size.minDimension / 2f
                    val stroke = 1.dp.toPx()

                    // Inset by half the stroke: a circle at the full radius is centred on the
                    // canvas bounds, so the outer half of the line falls outside and is clipped
                    // at the tangents. The old ring was also 1 raw *pixel*, not 1dp, which is a
                    // sub-hairline at any modern density.
                    drawCircle(
                        color = ringColor,
                        radius = r - stroke / 2f,
                        center = Offset(cx, cy),
                        style = Stroke(width = stroke),
                    )

                    // Short enough that the north tip clears the letter's glyph above it, which
                    // is what sets these proportions rather than taste.
                    val halfLength = r * 0.46f
                    val halfWidth = r * 0.13f
                    drawPath(
                        Path().apply {
                            moveTo(cx, cy - halfLength)
                            lineTo(cx + halfWidth, cy)
                            lineTo(cx - halfWidth, cy)
                            close()
                        },
                        northColor,
                    )
                    drawPath(
                        Path().apply {
                            moveTo(cx, cy + halfLength)
                            lineTo(cx + halfWidth, cy)
                            lineTo(cx - halfWidth, cy)
                            close()
                        },
                        southColor,
                    )
                }

                Text(
                    text = northLetter,
                    color = tokens.label,
                    // The line height is pinned because the default one is what decides how far
                    // down the glyph sits, and therefore how much of the dial is left for the
                    // needle. Material's `labelSmall` carries 16sp of leading around an 11sp
                    // glyph, which pushed the letter into the middle of the circle and left the
                    // blade stunted. 12sp puts it against the rim with its top still clear.
                    style = MaterialTheme.typography.labelSmall.copy(lineHeight = 12.sp),
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

/**
 * The dial's diameter inside the 48dp target.
 *
 * Sized against the letter rather than picked by eye. With the line height pinned to 12sp the
 * "N" glyph bottom lands about 10dp below the dial's top edge, and the needle's `0.46` half
 * length puts the north tip about 2dp below that. The three numbers — this diameter, that line
 * height, that ratio — are one decision in three places: changing any of them means rechecking
 * the other two, or the letter and the tip start touching.
 */
private val DIAL = 44.dp
