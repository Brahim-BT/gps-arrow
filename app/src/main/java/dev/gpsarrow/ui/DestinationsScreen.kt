package dev.gpsarrow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.gpsarrow.core.Destination
import dev.gpsarrow.core.DistanceUnits
import dev.gpsarrow.core.Format
import dev.gpsarrow.core.Geo
import dev.gpsarrow.core.LatLon
import dev.gpsarrow.core.Mgrs
import dev.gpsarrow.core.PlusCode

@Composable
fun DestinationsScreen(
    destinations: List<Destination>,
    currentPosition: LatLon?,
    selectedId: String?,
    units: DistanceUnits,
    onSelect: (Destination) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Sorted by distance when we know where we are — that is almost always what you want.
    val sorted = remember(destinations, currentPosition) {
        if (currentPosition == null) destinations.sortedByDescending { it.createdAtMillis }
        else destinations.sortedBy { Geo.distanceMeters(currentPosition, it.position) }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Destinations", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onAdd) { Text("Add") }
        }

        if (sorted.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "No saved destinations.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onAdd) { Text("Add your first one") }
            }
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sorted, key = { it.id }) { destination ->
                DestinationRow(
                    destination = destination,
                    currentPosition = currentPosition,
                    selected = destination.id == selectedId,
                    units = units,
                    onSelect = { onSelect(destination) },
                    onDelete = { onDelete(destination.id) },
                )
            }
        }
    }
}

@Composable
private fun DestinationRow(
    destination: Destination,
    currentPosition: LatLon?,
    selected: Boolean,
    units: DistanceUnits,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (selected) "▸ ${destination.name}" else destination.name,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = Format.decimal(destination.position),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                currentPosition?.let {
                    Text(
                        text = Format.distance(
                            Geo.distanceMeters(it, destination.position),
                            units,
                        ) + " · " + Format.bearing(
                            Geo.initialBearingDegrees(it, destination.position),
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete ${destination.name}")
            }
        }
    }
}

/** Every offline-shareable representation of a point, for the share sheet. */
fun Destination.shareText(): String = buildString {
    appendLine(name)
    appendLine(Format.decimal(position))
    appendLine(Format.dms(position))
    appendLine("Plus code: ${PlusCode.encode(position)}")
    Mgrs.toMgrs(position, spaced = true)?.let { appendLine("MGRS: $it") }
    appendLine("geo:${position.lat},${position.lon}")
}
