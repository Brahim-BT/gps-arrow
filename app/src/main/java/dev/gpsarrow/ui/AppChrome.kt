package dev.gpsarrow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gpsarrow.R
import dev.gpsarrow.ui.theme.AppTheme

/**
 * The charcoal bar across the top: arrow mark, two-line title, contextual actions.
 *
 * The mark is the app's own vector rather than a Material icon, tinted with the accent, and it
 * is the one place the brand appears. [actions] is a slot so each screen contributes its own
 * (search and add on the list, nothing on the arrow) without this file knowing about screens.
 */
@Composable
fun AppBar(
    actions: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tokens = AppTheme.tokens
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(tokens.appBar)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_notification_arrow),
            contentDescription = null,
            tint = tokens.accent,
            modifier = Modifier.size(38.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.app_subtitle),
                style = MaterialTheme.typography.labelMedium,
                color = tokens.label,
                letterSpacing = 1.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        actions()
    }
}

/**
 * Uppercase, letter-spaced, scrollable.
 *
 * Scrollable rather than fixed is the whole point, and it is a better answer than the label
 * shortening this app was doing before: with four tabs at a fixed width, "Destinations" had to
 * become "Saved" to fit, and Arabic would have had the same fight again. A scrollable row sizes
 * each tab to its own text, in any language, and lets the neighbours sit half off-screen — which
 * also tells the user there is more to swipe to.
 *
 * Two orange lines, as in the reference: a thick indicator under the selected tab, and a thin
 * continuous rule under the whole row. The rule is drawn here rather than inside the tab row so
 * it spans the screen instead of the (wider) scrollable content.
 */
@Composable
fun AppTabs(
    titles: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = AppTheme.tokens
    Column(modifier = modifier.background(tokens.appBar)) {
        ScrollableTabRow(
            selectedTabIndex = selectedIndex,
            edgePadding = 0.dp,
            containerColor = tokens.appBar,
            contentColor = MaterialTheme.colorScheme.onSurface,
            divider = {},
            indicator = { positions ->
                if (selectedIndex < positions.size) {
                    Box(
                        Modifier
                            .tabIndicatorOffset(positions[selectedIndex])
                            .height(tokens.tabIndicatorHeight)
                            .background(tokens.accent),
                    )
                }
            },
        ) {
            titles.forEachIndexed { index, title ->
                Tab(
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    selectedContentColor = MaterialTheme.colorScheme.onSurface,
                    unselectedContentColor = tokens.label,
                ) {
                    Text(
                        text = title.uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        letterSpacing = 1.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
        }
        HorizontalDivider(thickness = tokens.tabRuleHeight, color = tokens.accent)
    }
}

/** Section heading in the reference's style: large white text over a full-width orange rule. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    val tokens = AppTheme.tokens
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        HorizontalDivider(thickness = 1.dp, color = tokens.accent)
    }
}

/**
 * A corner readout on the arrow screen: a value and its label, at the same size.
 *
 * The value was `displayMedium` (34sp) to match the reference screenshots. On the device that
 * proved too heavy, so value and label are now both `labelLarge` and the only thing separating
 * them is colour — white against grey. That is what was asked for, deliberately and not as a
 * compromise, because the person judging it was looking at a real screen.
 *
 * [labelAbove] follows the reference: the label sits above the value in the top corners and
 * below it in the bottom ones, so the smaller grey text always hugs the nearer screen edge.
 *
 * Alignment is [Alignment.Start] / [Alignment.End], never left/right, so the whole block mirrors
 * correctly under RTL. The arrow between them does not — that is locked LTR elsewhere.
 */
@Composable
fun CornerReadout(
    label: String,
    value: String,
    alignment: Alignment.Horizontal,
    labelAbove: Boolean,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    val tokens = AppTheme.tokens
    Column(modifier = modifier, horizontalAlignment = alignment) {
        if (labelAbove) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = tokens.label, maxLines = 1)
        }
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!labelAbove) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = tokens.label, maxLines = 1)
        }
    }
}

/** Fills the whole tab body with black, so no screen has to remember to. */
@Composable
fun ScreenSurface(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopStart,
    ) {
        content()
    }
}

/** How loud a [Notice] is. Colour only — the layout is identical, so nothing jumps. */
enum class Tone { WARN, INFO, GOOD }

/**
 * A line of explanation under the arrow.
 *
 * Flat: coloured text on the black background rather than a tinted card. At this palette a card
 * behind the text would be the only lifted surface on the screen and would compete with the
 * needle, which is the thing that must dominate.
 */
@Composable
fun Notice(text: String, tone: Tone = Tone.WARN) {
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
