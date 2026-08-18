package dev.gpsarrow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gpsarrow.R
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
    /** Hoisted: a search survives a trip to the arrow tab and back. */
    query: String,
    onQueryChange: (String) -> Unit,
    favouritesOnly: Boolean,
    onFavouritesOnlyChange: (Boolean) -> Unit,
    /** Freshly saved point: scrolled to and tinted briefly so the user sees where it landed. */
    highlightId: String? = null,
    modifier: Modifier = Modifier,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Destination?>(null) }

    val effectiveSort = DestinationQuery.effectiveSort(sort, currentPosition)
    val visible = remember(destinations, query, favouritesOnly, sort, currentPosition) {
        DestinationQuery.apply(destinations, query, favouritesOnly, sort, currentPosition)
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.delete_dialog_body,
                        target.name,
                        ltrIsolate(Format.decimal(target.position)),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(target)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.keep)) }
            },
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.destinations_title),
                style = MaterialTheme.typography.headlineMedium,
            )
        }

        if (destinations.isNotEmpty()) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text(stringResource(R.string.search)) },
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
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
                        Text(stringResource(effectiveSort.labelRes()), maxLines = 1)
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
                                        Text(stringResource(option.labelRes()))
                                        if (unavailable) {
                                            Text(
                                                stringResource(R.string.sort_needs_position),
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
                    onClick = { onFavouritesOnlyChange(!favouritesOnly) },
                    label = { Text(stringResource(R.string.filter_starred)) },
                )
            }

            // Distances are shown from a stale position: say so rather than implying they're
            // live. Gated on `currentPosition`, NOT on the sort order: every row shows a
            // distance and a bearing whenever an origin exists, so tying the caveat to the
            // distance *sorts* hid it from anyone browsing by name — the readouts were still
            // there, just no longer labelled.
            if (currentPosition != null && positionIsStale) {
                Text(
                    stringResource(R.string.distances_stale_warning),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        when {
            destinations.isEmpty() -> EmptyState(
                title = stringResource(R.string.empty_no_destinations_title),
                body = stringResource(R.string.empty_no_destinations_body),
                actionLabel = stringResource(R.string.empty_add_first),
                onAction = onAdd,
            )

            visible.isEmpty() -> EmptyState(
                title = stringResource(R.string.empty_nothing_matches_title),
                body = if (favouritesOnly && query.isNotBlank()) {
                    stringResource(R.string.empty_no_starred_match, query)
                } else if (favouritesOnly) {
                    stringResource(R.string.empty_no_starred)
                } else {
                    stringResource(R.string.empty_no_match, query)
                },
                actionLabel = stringResource(R.string.clear_search),
                onAction = { onQueryChange(""); onFavouritesOnlyChange(false) },
            )

            else -> {
                val listState = rememberLazyListState()
                // Sorting can drop a new point anywhere in the list, so scroll it into view.
                LaunchedEffect(highlightId, visible.size) {
                    val index = visible.indexOfFirst { it.id == highlightId }
                    if (index >= 0) listState.animateScrollToItem(index)
                }
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    items(visible, key = { it.id }) { destination ->
                        DestinationRow(
                            destination = destination,
                            currentPosition = currentPosition,
                            selected = destination.id == selectedId,
                            highlighted = destination.id == highlightId,
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

        FloatingActionButton(
            onClick = onAdd,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.cd_add_destination),
            )
        }
    }
}

@Composable
private fun DestinationRow(
    destination: Destination,
    currentPosition: LatLon?,
    selected: Boolean,
    highlighted: Boolean,
    units: DistanceUnits,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onToggleFavourite: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val numberLocale = rememberNumberLocale()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // Tapping the row stays "navigate to this" — the primary action. Editing gets its
            // own affordance rather than stealing the tap for a detail screen.
            .clickable(onClick = onSelect),
        colors = if (highlighted) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (selected) {
                        stringResource(R.string.selected_destination, destination.name)
                    } else {
                        destination.name
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // The accuracy the point was captured at, when it is known. A point taken
                    // from a ±40 m fix must not sit in the list looking identical to one taken
                    // from a ±4 m fix — the arrow is only ever as good as what it aims at.
                    text = ltrIsolate(Format.decimal(destination.position)) +
                        (
                            destination.accuracyMeters?.let {
                                stringResource(
                                    R.string.accuracy_suffix,
                                    Format.number("%d", numberLocale, it.toInt()),
                                )
                            } ?: ""
                            ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                currentPosition?.let {
                    Text(
                        text = Format.distance(
                            Geo.distanceMeters(it, destination.position),
                            units,
                            numberLocale,
                        ).text(context) + " · " + Format.bearing(
                            Geo.initialBearingDegrees(it, destination.position),
                            numberLocale,
                        ).text(context),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            IconButton(onClick = onToggleFavourite, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = if (destination.isFavourite) {
                        stringResource(R.string.cd_unstar, destination.name)
                    } else {
                        stringResource(R.string.cd_star, destination.name)
                    },
                    // Same glyph, distinguished by tint: keeps us on material-icons-core only.
                    tint = if (destination.isFavourite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.cd_edit, destination.name),
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.cd_delete, destination.name),
                )
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
    // Deliberately untranslated: this is text the user hands to another app, another device
    // or a radio, and every one of those expects the Latin labels.
    appendLine("Plus code: ${PlusCode.encode(position)}")
    Mgrs.toMgrs(position, spaced = true)?.let { appendLine("MGRS: $it") }
    appendLine("geo:${position.lat},${position.lon}")
}
