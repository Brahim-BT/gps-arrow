package dev.gpsarrow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
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
import dev.gpsarrow.core.ShareStatus
import dev.gpsarrow.ui.theme.AppTheme

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
    /**
     * Whether public sharing exists in this build. Gates the badge rather than the data: a
     * point can carry a share intent while the backend is unconfigured, and a badge that means
     * nothing to this install is noise.
     */
    sharingAvailable: Boolean = false,
    /**
     * What the app has observed about each point's public visibility.
     *
     * A function rather than a field on [Destination] because it is derived — the local intent
     * combined with the last fetched feed — and a copy stored per row would go stale the moment
     * a sync landed. Defaults to saying nothing, which is the right answer for any caller that
     * has not wired the feed in.
     */
    shareStatus: (Destination) -> ShareStatus = { ShareStatus.NOT_SHARED },
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
    Column(modifier = Modifier.fillMaxSize()) {
        SectionHeader(stringResource(R.string.select_destination))

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
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
                    modifier = Modifier.padding(top = 4.dp),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    itemsIndexed(visible, key = { _, d -> d.id }) { index, destination ->
                        if (index > 0) {
                            HorizontalDivider(thickness = 1.dp, color = AppTheme.tokens.divider)
                        }
                        DestinationRow(
                            destination = destination,
                            currentPosition = currentPosition,
                            selected = destination.id == selectedId,
                            highlighted = destination.id == highlightId,
                            units = units,
                            sharingAvailable = sharingAvailable,
                            shareStatus = shareStatus(destination),
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

/**
 * A row in the reference's shape: name and distance on one line, both large, then the smaller
 * grey detail beneath.
 *
 * **No category icon.** The reference has a coloured square on every row, but those encode
 * categories this app's data model does not have, and a decorative placeholder would imply a
 * distinction we cannot back. Dropping it also gives the name the full width of the row, which
 * matters most in Arabic, where the same name runs longer.
 *
 * Everything is `weight` and start/end, no fixed widths, so a long name in any language
 * ellipsises rather than pushing the distance off the screen — and the whole row mirrors under
 * RTL without a single left or right in it.
 */
@Composable
private fun DestinationRow(
    destination: Destination,
    currentPosition: LatLon?,
    selected: Boolean,
    highlighted: Boolean,
    units: DistanceUnits,
    sharingAvailable: Boolean,
    shareStatus: ShareStatus,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onToggleFavourite: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val numberLocale = rememberNumberLocale()
    val tokens = AppTheme.tokens
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (highlighted) tokens.accent.copy(alpha = 0.16f) else Color.Transparent,
            )
            // Tapping the row stays "navigate to this" — the primary action. Editing keeps its
            // own affordance rather than stealing the tap for a detail screen.
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (selected) {
                    stringResource(R.string.selected_destination, destination.name)
                } else {
                    destination.name
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.displaySmall,
                color = if (selected) tokens.accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            currentPosition?.let {
                Text(
                    text = Format.distance(
                        Geo.distanceMeters(it, destination.position),
                        units,
                        numberLocale,
                    ).text(context),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }

        destination.note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            // The accuracy the point was captured at, when it is known. A point taken from a
            // ±40 m fix must not sit in the list looking identical to one taken from a ±4 m
            // fix — the arrow is only ever as good as what it aims at.
            text = ltrIsolate(Format.decimal(destination.position)) +
                (
                    destination.accuracyMeters?.let { a ->
                        stringResource(
                            R.string.accuracy_suffix,
                            Format.number("%d", numberLocale, a.toInt()),
                        )
                    } ?: ""
                    ),
            style = MaterialTheme.typography.labelMedium,
            color = tokens.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // Sharing state, as words rather than as a glyph.
        //
        // It used to be a lone globe icon rendered straight off a local Boolean, which said
        // "Publicly shared" about a point the app had never seen in a feed. There are now four
        // things it can say and only one of them is a claim about the world, so a single tinted
        // icon could not carry them: the same dimmed globe would have to mean "not confirmed
        // yet", "still public" and "withdrawal not confirmed" at once. Nothing is drawn at all
        // when there is nothing to say, so a row without sharing looks exactly as it did before
        // the feature existed.
        //
        // Still not a control. Sharing is changed where the point is edited, so there is exactly
        // one place whose state has to stay honest.
        if (sharingAvailable) {
            shareStatusLabelRes(shareStatus)?.let { labelRes ->
                val certain = shareStatus == ShareStatus.PUBLISHED
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Public,
                        // The text beside it is the label; announcing both would read the state
                        // twice.
                        contentDescription = null,
                        tint = if (certain) tokens.accent else tokens.label,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (certain) tokens.accent else tokens.label,
                        // Two lines, not one. "Publicly shared — your edit is not published yet"
                        // ellipsised on a narrow screen reads as "Publicly shared…", which is
                        // the opposite of what it says: the clause that carries the warning is
                        // the one that would be cut.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggleFavourite, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = if (destination.isFavourite) {
                        stringResource(R.string.cd_unstar, destination.name)
                    } else {
                        stringResource(R.string.cd_star, destination.name)
                    },
                    // Same glyph, distinguished by tint: keeps us on material-icons-core only.
                    tint = if (destination.isFavourite) tokens.accent
                    else tokens.label.copy(alpha = 0.45f),
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.cd_edit, destination.name),
                    tint = tokens.label,
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.cd_delete, destination.name),
                    tint = tokens.label,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = formatTimestamp(context, destination.createdAtMillis),
                style = MaterialTheme.typography.labelMedium,
                color = tokens.label,
                maxLines = 1,
            )
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
