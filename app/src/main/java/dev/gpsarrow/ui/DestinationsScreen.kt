package dev.gpsarrow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gpsarrow.core.Destination
import dev.gpsarrow.core.DestinationQuery
import dev.gpsarrow.core.DestinationSort
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
    /** True when [currentPosition] came from a stale fix, so distances need a caveat. */
    positionIsStale: Boolean,
    selectedId: String?,
    sort: DestinationSort,
    units: DistanceUnits,
    onSortChange: (DestinationSort) -> Unit,
    onSelect: (Destination) -> Unit,
    onEdit: (Destination) -> Unit,
    onToggleFavourite: (Destination) -> Unit,
    onDelete: (Destination) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var favouritesOnly by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Destination?>(null) }

    val effectiveSort = DestinationQuery.effectiveSort(sort, currentPosition)
    val visible = remember(destinations, query, favouritesOnly, sort, currentPosition) {
        DestinationQuery.apply(destinations, query, favouritesOnly, sort, currentPosition)
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this point?") },
            text = {
                Text(
                    "\"${target.name}\" will be removed. " +
                        Format.decimal(target.position),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(target)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep") }
            },
        )
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

        if (destinations.isNotEmpty()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search") },
                placeholder = { Text("Name or note") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    TextButton(onClick = { sortMenuOpen = true }) {
                        Text(effectiveSort.label, maxLines = 1)
                    }
                    DropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false },
                    ) {
                        DestinationSort.entries.forEach { option ->
                            val unavailable = option.needsPosition && currentPosition == null
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(option.label)
                                        if (unavailable) {
                                            Text(
                                                "needs a position fix",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                enabled = !unavailable,
                                onClick = {
                                    sortMenuOpen = false
                                    onSortChange(option)
                                },
                            )
                        }
                    }
                }

                FilterChip(
                    selected = favouritesOnly,
                    onClick = { favouritesOnly = !favouritesOnly },
                    label = { Text("Starred") },
                )
            }

            // Distances are shown from a stale position: say so rather than implying they're live.
            if (effectiveSort.needsPosition && positionIsStale) {
                Text(
                    "Distances are from your last known position, not a live fix.",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        when {
            destinations.isEmpty() -> EmptyState(
                title = "No destinations yet",
                body = "Save your location from the arrow screen, or add a point from " +
                    "coordinates, a plus code or an MGRS reference.",
                actionLabel = "Add your first one",
                onAction = onAdd,
            )

            visible.isEmpty() -> EmptyState(
                title = "Nothing matches",
                body = if (favouritesOnly && query.isNotBlank()) {
                    "No starred point matches \"$query\"."
                } else if (favouritesOnly) {
                    "You haven't starred any points yet. Tap the star on a row to add one."
                } else {
                    "No saved point matches \"$query\"."
                },
                actionLabel = "Clear search",
                onAction = { query = ""; favouritesOnly = false },
            )

            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                items(visible, key = { it.id }) { destination ->
                    DestinationRow(
                        destination = destination,
                        currentPosition = currentPosition,
                        selected = destination.id == selectedId,
                        units = units,
                        onSelect = { onSelect(destination) },
                        onEdit = { onEdit(destination) },
                        onToggleFavourite = { onToggleFavourite(destination) },
                        onDelete = { pendingDelete = destination },
                    )
                }
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
    onEdit: () -> Unit,
    onToggleFavourite: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // Tapping the row stays "navigate to this" — the primary action. Editing gets its
            // own affordance rather than stealing the tap for a detail screen.
            .clickable(onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (selected) "▸ ${destination.name}" else destination.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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

            IconButton(onClick = onToggleFavourite, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = if (destination.isFavourite) Icons.Filled.Star
                    else Icons.Outlined.StarBorder,
                    contentDescription = if (destination.isFavourite) {
                        "Unstar ${destination.name}"
                    } else {
                        "Star ${destination.name}"
                    },
                    tint = if (destination.isFavourite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit ${destination.name}")
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete ${destination.name}")
            }
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp),
        )
        TextButton(onClick = onAction, modifier = Modifier.padding(top = 12.dp)) {
            Text(actionLabel)
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
