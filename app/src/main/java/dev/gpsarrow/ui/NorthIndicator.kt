package dev.gpsarrow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import dev.gpsarrow.R

/**
 * A small compass needle showing where north is on a rotating map, and a way back to north-up.
 *
 * ## It does exactly one thing
 *
 * Tapping it faces north **and** resumes following the heading. Those are not two jobs: after a
 * user has panned away, "put me back the way it was" is a single intention, and splitting it into
 * two controls would make the user perform a two-step recovery from a state they did not choose
 * deliberately. One tap, one meaning.
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
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier = modifier
                // 48dp is the platform's minimum comfortable target. The needle inside is much
                // smaller; the touchable area is not.
                .size(48.dp)
                .background(
                    MaterialTheme.colorScheme.background.copy(alpha = 0.65f),
                    CircleShape,
                )
                .clickable(onClick = onTap)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .size(26.dp)
                    .rotate(northDegrees.toFloat()),
            ) {
                val w = size.width
                val h = size.height
                // A simple two-tone needle: the north half accented, the south half muted, so
                // which end is north is unambiguous without a letter to read.
                val north = Path().apply {
                    moveTo(w / 2f, 0f)
                    lineTo(w * 0.78f, h * 0.62f)
                    lineTo(w / 2f, h * 0.48f)
                    close()
                }
                val south = Path().apply {
                    moveTo(w / 2f, h)
                    lineTo(w * 0.22f, h * 0.38f)
                    lineTo(w / 2f, h * 0.52f)
                    close()
                }
                drawPath(south, Color(0xFF6E6E6E))
                drawPath(north, Color(0xFFFF5252))
                drawCircle(
                    color = Color(0x55FFFFFF),
                    radius = w * 0.5f,
                    center = Offset(w / 2f, h / 2f),
                    style = Stroke(width = 1f),
                )
            }
        }
    }
}
