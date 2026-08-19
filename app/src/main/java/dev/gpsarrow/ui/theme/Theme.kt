package dev.gpsarrow.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Black and orange, flat, and large.
 *
 * The look comes from GPS Arrow Pro, reproduced in flat Material 3 rather than its original
 * skeuomorphism. Three reasons that is not just taste:
 *
 *  - **Sunlight.** A gradient fill is darker at one end, so part of any gradient button is
 *    always below the contrast a flat fill of the same colour achieves. This app gets read in
 *    the Sahara.
 *  - **AMOLED.** Pure black costs no power to display. A gradient that lifts the background off
 *    black spends battery on decoration, on a device that may be a long way from a charger.
 *  - **The gradients were compensating for something we do not have.** In 2011 a bevelled
 *    button was how you signalled "tappable" before Material established the convention. We get
 *    that from shape and state layers for free; copying the gradient copies the workaround.
 *
 * The one exception is the arrow itself, which keeps a two-stop fill along its axis — that is
 * structure, not gloss: a single flat triangle at that size reads as unfinished, and the shading
 * is what makes it look like a needle.
 *
 * **Everything visual lives here.** No screen hard-codes a colour or a size. Switching the
 * primary button back to a gradient is one line in [GpsArrowTokens.primaryButtonBrush].
 */

// ----------------------------------------------------------------------------- raw palette

/** True black. Not a dark grey — see the AMOLED note above. */
private val Black = Color(0xFF000000)

/** The app bar and any raised surface. Just far enough off black to read as a distinct plane. */
private val Charcoal = Color(0xFF1A1A1A)
private val CharcoalHigh = Color(0xFF242424)

/** The single accent. Bright end for highlights and the arrow tip, deep end for the tail. */
private val AccentBright = Color(0xFFFFB300)
private val Accent = Color(0xFFFF9800)
private val AccentDeep = Color(0xFFF4511E)

private val TextPrimary = Color(0xFFFFFFFF)

/** Labels, timestamps, units. 4.6:1 on black, so it stays legible outdoors. */
private val TextSecondary = Color(0xFF9E9E9E)

/** "GPS has fix" green. Saturated for contrast, not the Material default. */
private val Good = Color(0xFF00E676)
private val Bad = Color(0xFFFF5252)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Black,
    primaryContainer = AccentDeep,
    onPrimaryContainer = TextPrimary,
    secondary = Good,
    onSecondary = Black,
    background = Black,
    onBackground = TextPrimary,
    surface = Black,
    onSurface = TextPrimary,
    surfaceVariant = Charcoal,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = Charcoal,
    surfaceContainerHigh = CharcoalHigh,
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF2A2A2A),
    error = Bad,
    onError = Black,
)

// ----------------------------------------------------------------------------- type scale

/**
 * Much larger than Material's defaults, deliberately.
 *
 * This is read at arm's length, while walking, often in bright light and sometimes through
 * sunglasses. The distance and the arrow are the product; everything else is a label.
 */
private val AppTypography = Typography(
    // The distance on the arrow screen. Nothing else uses this.
    displayLarge = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    // Corner values on the arrow screen, and the destination name in a list row.
    displayMedium = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Normal),
    displaySmall = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Normal),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Normal),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 17.sp),
    bodyMedium = TextStyle(fontSize = 15.sp),
    // The small grey labels above and below the corner values.
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    labelMedium = TextStyle(fontSize = 13.sp),
)

// ----------------------------------------------------------------------------- tokens

/**
 * The parts of the design Material's colour scheme has no slot for.
 *
 * Kept as one object behind a CompositionLocal so a screen never reaches for a raw
 * `Color(0xFF...)`, and so the gradient decision is reversible in one place.
 */
data class GpsArrowTokens(
    val accentBright: Color,
    val accent: Color,
    val accentDeep: Color,
    val appBar: Color,
    val label: Color,
    val good: Color,
    val divider: Color,
    /** The rule under the whole tab row, and the selected-tab indicator. */
    val tabIndicatorHeight: androidx.compose.ui.unit.Dp,
    val tabRuleHeight: androidx.compose.ui.unit.Dp,
) {
    /**
     * Fill for the primary action button.
     *
     * Flat today. To go back to the original glossy look, return
     * `Brush.verticalGradient(listOf(accentBright, accentDeep))` here and nothing else changes.
     */
    fun primaryButtonBrush(): Brush = SolidColor(accent)

    /**
     * Fill for the arrow, along its own axis: bright at the tip, deep at the tail.
     *
     * The deliberate exception to the flat rule. [tipY] and [tailY] are in the arrow's local
     * (unrotated) space, so the shading rotates with the needle as it should.
     */
    fun arrowBrush(tipY: Float, tailY: Float): Brush = Brush.verticalGradient(
        colors = listOf(accentBright, accentDeep),
        startY = tipY,
        endY = tailY,
    )
}

private val DefaultTokens = GpsArrowTokens(
    accentBright = AccentBright,
    accent = Accent,
    accentDeep = AccentDeep,
    appBar = Charcoal,
    label = TextSecondary,
    good = Good,
    divider = Color(0xFF2A2A2A),
    tabIndicatorHeight = 4.dp,
    tabRuleHeight = 2.dp,
)

private val LocalTokens = staticCompositionLocalOf { DefaultTokens }

/** `AppTheme.tokens` at any call site inside [GpsArrowTheme]. */
object AppTheme {
    val tokens: GpsArrowTokens
        @Composable @ReadOnlyComposable get() = LocalTokens.current
}

/**
 * One theme, always dark.
 *
 * There is no light scheme. A light theme would be unreadable in the sun at the brightness a
 * phone can manage, would cost battery on AMOLED, and would destroy the black-and-orange
 * identity the app is meant to have. `isSystemInDarkTheme` is deliberately not consulted.
 */
@Composable
fun GpsArrowTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTokens provides DefaultTokens) {
        MaterialTheme(
            colorScheme = Scheme,
            typography = AppTypography,
            content = content,
        )
    }
}
