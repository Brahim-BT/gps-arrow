package dev.gpsarrow.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.gpsarrow.R
import dev.gpsarrow.maps.MapTier

/**
 * What the user sees when they open the map and there's no data for where they are.
 *
 * Three rules (BUILD_PLAN.md 7):
 *  1. Lead with the fact that the arrow is unaffected — the core feature has not failed.
 *  2. Name the region and its size, so the dead end becomes an action.
 *  3. Never block going back.
 */
@Composable
fun MapScreen(
    tier: MapTier,
    onBack: () -> Unit,
    onOpenRegions: () -> Unit,
    onRemindWhenOnline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (tier) {
            is MapTier.Available -> {
                // v1: replace this with the MapLibre MapView wrapped in AndroidView, reading
                // tier.region.pmtilesUri  ->  "pmtiles://file:///.../regions/fr.pmtiles"
                Text(stringResource(R.string.map_ready, tier.region.summary.name))
                Text(
                    tier.region.pmtilesUri,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is MapTier.NoDataHere -> NoDataCard(
                regionName = tier.suggested?.name,
                sizeLabel = tier.suggested?.approximateSizeLabel,
                onOpenRegions = onOpenRegions,
                onRemindWhenOnline = onRemindWhenOnline,
            )

            MapTier.ArrowOnly -> NoDataCard(
                regionName = null,
                sizeLabel = null,
                onOpenRegions = onOpenRegions,
                onRemindWhenOnline = onRemindWhenOnline,
            )
        }

        TextButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(R.string.map_back_to_arrow))
        }
    }
}

@Composable
private fun NoDataCard(
    regionName: String?,
    sizeLabel: String?,
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
            Text(
                when {
                    regionName == null -> stringResource(R.string.map_none_explanation)
                    sizeLabel == null -> stringResource(R.string.map_none_download, regionName)
                    else -> stringResource(R.string.map_none_download_sized, regionName, sizeLabel)
                },
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
