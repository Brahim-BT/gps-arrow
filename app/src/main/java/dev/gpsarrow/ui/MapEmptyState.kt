package dev.gpsarrow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.gpsarrow.R
import dev.gpsarrow.maps.InstalledArea
import dev.gpsarrow.maps.MapStyle
import dev.gpsarrow.maps.MapTier

/**
 * What the user sees when they open the map and there's no data for where they are.
 *
 * Three rules (BUILD_PLAN.md 7):
 *  1. Lead with the fact that the arrow is unaffected — the core feature has not failed.
 *  2. Name the area by the places it covers and give its size, so the dead end becomes an action.
 *  3. Never block going back.
 *
 * Nothing here renders a file path, a URL or a filename. An earlier version printed
 * `tier.installed.pmtilesUri` on screen, which showed the user a `pmtiles://file:///…` path — an
 * internal detail they cannot act on, and one that leaked the internal area id into the UI.
 */
@Composable
fun MapScreen(
    tier: MapTier,
    onBack: () -> Unit,
    onOpenRegions: () -> Unit,
    onRemindWhenOnline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The map fills the screen; the empty states are centred cards. Those want different layouts,
    // so the branch comes before the container rather than inside it — an earlier version nested
    // the map inside a centred, 20dp-padded Column, which would have boxed the renderer into a
    // strip in the middle of the screen.
    when (tier) {
        is MapTier.Available -> InstalledMap(
            installed = tier.installed,
            onBack = onBack,
            onOpenRegions = onOpenRegions,
            modifier = modifier,
        )

        is MapTier.NoDataHere -> EmptyStateColumn(modifier, onBack) {
            NoDataCard(
                placesRes = tier.suggested?.placesRes,
                onOpenRegions = onOpenRegions,
                onRemindWhenOnline = onRemindWhenOnline,
            )
        }

        MapTier.ArrowOnly -> EmptyStateColumn(modifier, onBack) {
            NoDataCard(
                placesRes = null,
                onOpenRegions = onOpenRegions,
                onRemindWhenOnline = onRemindWhenOnline,
            )
        }
    }
}

@Composable
private fun EmptyStateColumn(
    modifier: Modifier,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(R.string.map_back_to_arrow))
        }
    }
}

/**
 * The map itself, with the attribution the licence requires.
 *
 * If the renderer will not start — missing native library for this ABI, unreadable style asset,
 * GL context refused — this falls back to the same card the user sees when nothing is installed.
 * The map tab degrades to "no map, and the arrow still works"; it never fails.
 */
@Composable
private fun InstalledMap(
    installed: InstalledArea,
    onBack: () -> Unit,
    onOpenRegions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var rendererFailed by rememberSaveable(installed.file.path) { mutableStateOf(false) }
    val styleJson = remember(installed.file.path) { MapStyle.forInstalled(context, installed) }

    if (styleJson == null || rendererFailed) {
        EmptyStateColumn(modifier, onBack) {
            NoDataCard(
                placesRes = installed.area.placesRes,
                onOpenRegions = onOpenRegions,
                onRemindWhenOnline = {},
            )
        }
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        MapLibreView(
            styleJson = styleJson,
            modifier = Modifier.fillMaxSize(),
            onUnavailable = { rendererFailed = true },
        )
        // ODbL condition: attribution must be visible wherever the map is shown, not buried in
        // an About page. It sits over the map rather than beside it for exactly that reason.
        Text(
            stringResource(R.string.about_map_attribution),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun NoDataCard(
    placesRes: Int?,
    onOpenRegions: () -> Unit,
    onRemindWhenOnline: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.map_none_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.map_none_arrow_works),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
            )

            // When an area covers this position, showing its place list is the most useful thing
            // on the screen: it answers "would downloading that help me?" without a country name
            // and without the user having to know what the area is called.
            if (placesRes != null) {
                Text(
                    stringResource(placesRes),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.area_covers_you),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center,
                )
            }

            Text(
                stringResource(
                    if (placesRes == null) R.string.map_none_explanation else R.string.map_none_prompt
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Button(onClick = onOpenRegions, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.map_open_region_list))
            }
            OutlinedButton(onClick = onRemindWhenOnline, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.map_remind_when_online))
            }
        }
    }
}
