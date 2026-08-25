package dev.gpsarrow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.gpsarrow.R
import dev.gpsarrow.maps.AreaLevel
import dev.gpsarrow.maps.Detail
import dev.gpsarrow.maps.MapArea

/**
 * The offline areas list: what you can download, what you have, and what it costs.
 *
 * Three things this screen is trying to do, in order of importance:
 *
 *  1. **Answer "does this cover me?"** — the coverage line sits above everything else, because it
 *     is the only question that matters and it makes the area's label almost decorative.
 *  2. **Name areas by the places in them**, never by country or territory. Neutral about a
 *     disputed frontier, and more useful anyway: a person recognises their own city instantly.
 *  3. **Describe levels by content, not by zoom.** Nobody can act on "z12".
 *
 * Nothing here renders a filename, a path or a URL.
 */
@Composable
fun AreasScreen(
    areas: List<AreaRow>,
    storageUsedLabel: String,
    freeSpaceLabel: String,
    meteredPrompt: String?,
    onDownload: (MapArea, AreaLevel) -> Unit,
    onConfirmMetered: () -> Unit,
    onDismissMetered: () -> Unit,
    onCancel: (MapArea) -> Unit,
    onDelete: (MapArea) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SectionHeader(stringResource(R.string.areas_title))

        // Shown only when the platform says the active connection is actually metered — a
        // capability check, not wifi-versus-cellular. A warning that appears regardless teaches
        // people to tap past warnings, and then it protects nobody.
        if (meteredPrompt != null) {
            MeteredWarning(
                sizeLabel = meteredPrompt,
                onContinue = onConfirmMetered,
                onDismiss = onDismissMetered,
            )
        }

        Text(
            stringResource(R.string.areas_one_level_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        for (row in areas) {
            AreaCard(row, onDownload, onCancel, onDelete)
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.areas_storage_used, storageUsedLabel),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.areas_free_space, freeSpaceLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.about_map_attribution),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // Its own string, not the map screen's. `onBack` here is `showAreas = false`, which
        // lands on the MAP — the shared "Back to the arrow" label was describing a different
        // button's destination. The app bar carries the same exit at the top of the screen,
        // because this one sits below the storage meter and the attribution and is off-screen
        // on a phone after a download.
        TextButton(onClick = onBack) { Text(stringResource(R.string.areas_back_to_map)) }
    }
}

/**
 * A warning, not an obstacle.
 *
 * It names the actual megabytes rather than saying "large", and "Download anyway" is a real
 * button rather than a buried option — the person walking into a desert with no wifi in reach is
 * making the right call, and the app should not make them fight for it.
 */
@Composable
private fun MeteredWarning(
    sizeLabel: String,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.metered_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Notice(stringResource(R.string.metered_body, sizeLabel), Tone.WARN)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onContinue, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.metered_continue))
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.metered_wait))
            }
        }
    }
}

@Composable
private fun AreaCard(
    row: AreaRow,
    onDownload: (MapArea, AreaLevel) -> Unit,
    onCancel: (MapArea) -> Unit,
    onDelete: (MapArea) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The coverage line comes first, deliberately. It is the answer to the only question the
        // user is actually asking, and it needs no place name to be understood.
        when (row.coverage) {
            Coverage.SERVING -> Notice(stringResource(R.string.area_covers_you), Tone.GOOD)
            Coverage.RECOMMENDED -> Notice(stringResource(R.string.area_recommended), Tone.GOOD)
            // Quieter on purpose: true, but not the answer to "which one am I getting".
            Coverage.ALSO_COVERS -> Notice(stringResource(R.string.area_also_covers), Tone.INFO)
            Coverage.NO_FIX -> Notice(stringResource(R.string.area_position_unknown), Tone.INFO)
            Coverage.OUTSIDE -> Unit
        }

        Text(
            stringResource(row.area.placesRes),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // An area with one level must not render a chooser: offering a choice of one implies
        // there is a decision to make and there is not.
        for (level in row.area.levels) {
            LevelRow(
                area = row.area,
                level = level,
                showLabel = row.area.hasChoice,
                sizeLabel = row.sizeLabels[level.detail].orEmpty(),
                state = row.stateFor(level.detail),
                onDownload = onDownload,
                onCancel = onCancel,
                onDelete = onDelete,
            )
        }
    }
}

@Composable
private fun LevelRow(
    area: MapArea,
    level: AreaLevel,
    showLabel: Boolean,
    sizeLabel: String,
    state: LevelState,
    onDownload: (MapArea, AreaLevel) -> Unit,
    onCancel: (MapArea) -> Unit,
    onDelete: (MapArea) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.fillMaxWidth(0.6f)) {
                if (showLabel) {
                    Text(
                        stringResource(level.detail.labelRes),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Text(
                    stringResource(level.detail.summaryRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(sizeLabel, style = MaterialTheme.typography.bodyMedium)
        }

        when (state) {
            LevelState.Absent ->
                Button(
                    onClick = { onDownload(area, level) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.areas_download)) }

            is LevelState.Installed -> {
                Notice(stringResource(R.string.areas_installed), Tone.GOOD)
                // The diagnostic. The download path and the renderer are both exercised for the
                // first time on the same device at the same moment, and "the map is blank" does
                // not say which failed. This re-reads the archive's own header, so a green line
                // here plus a blank map points squarely at the renderer, and a red line here
                // points at the file.
                when (val v = state.verification) {
                    is FileVerification.Good ->
                        Notice(stringResource(R.string.areas_file_ok, v.sizeLabel), Tone.GOOD)

                    is FileVerification.Bad ->
                        Notice(stringResource(R.string.areas_file_bad, v.problem), Tone.WARN)

                    FileVerification.NotChecked -> Unit
                }
                OutlinedButton(
                    onClick = { onDelete(area) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.areas_delete)) }
            }

            LevelState.Replaceable ->
                OutlinedButton(
                    onClick = { onDownload(area, level) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.areas_switch)) }

            is LevelState.Downloading -> {
                LinearProgressIndicator(
                    progress = { state.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.download_progress, state.doneLabel, state.totalLabel),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Said out loud so nobody fears they have already lost the level they had.
                if (state.replacingInstalled) {
                    Text(
                        stringResource(R.string.download_replacing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onCancel(area) }) {
                    Text(stringResource(R.string.download_cancel))
                }
            }

            LevelState.Verifying -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.download_verifying),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            is LevelState.Paused -> {
                Notice(state.reason, Tone.WARN)
                Button(
                    onClick = { onDownload(area, level) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.download_resume)) }
            }

            is LevelState.Failed -> {
                Notice(state.reason, Tone.WARN)
                Button(
                    onClick = { onDownload(area, level) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.areas_download)) }
            }

            LevelState.Unavailable -> Notice(
                stringResource(R.string.download_not_published),
                Tone.INFO,
            )
        }
    }
}

/**
 * What this area is to the user's current position.
 *
 * [SERVING] and [ALSO_COVERS] exist because two areas can legitimately both contain the user —
 * the overlap band is the coast road south, where a real user actually was. Saying "covers your
 * position" against both described geometry and left the obvious question unanswered: which one
 * is the app using? These states answer it, and they follow the same rule that picks the tiles.
 */
enum class Coverage {
    /** Contains the position, and is the one the map uses (or would, once installed). */
    SERVING,

    /** Contains the position, but another area is the one in use. */
    ALSO_COVERS,

    /** Contains the position, nothing installed yet, and this is the one to download. */
    RECOMMENDED,

    OUTSIDE,
    NO_FIX,
}

/**
 * The result of re-reading an installed archive's PMTiles header.
 *
 * Cheap — 127 bytes and no CPU — and it is the one thing that separates "the download is broken"
 * from "the renderer is broken" without a debugger. [Bad.problem] carries the parser's own reason,
 * already turned into text by the caller.
 */
sealed interface FileVerification {
    data object NotChecked : FileVerification
    data class Good(val sizeLabel: String) : FileVerification
    data class Bad(val problem: String) : FileVerification
}

/**
 * One area as this screen needs it: already-resolved labels and states, no formatting logic here.
 *
 * Sizes arrive pre-formatted because number formatting has to go through the app's pinned
 * Latin-digit locale, which lives in the caller. A composable reaching for `String.format` is how
 * Arabic-Indic digits got into a size label once already.
 */
data class AreaRow(
    val area: MapArea,
    val coverage: Coverage,
    val sizeLabels: Map<Detail, String>,
    val states: Map<Detail, LevelState>,
) {
    fun stateFor(detail: Detail): LevelState = states[detail] ?: LevelState.Absent
}

sealed interface LevelState {
    /** Not on disk, and no other level of this area is either. */
    data object Absent : LevelState

    /** On disk. [verification] is the header re-check, which is the diagnostic. */
    data class Installed(
        val verification: FileVerification = FileVerification.NotChecked,
    ) : LevelState

    /** A different level of this area is installed; taking this one replaces it. */
    data object Replaceable : LevelState

    data class Downloading(
        val fraction: Float,
        val doneLabel: String,
        val totalLabel: String,
        val replacingInstalled: Boolean,
    ) : LevelState

    data object Verifying : LevelState

    /** Stopped, resumable, with the reason already localised by the caller. */
    data class Paused(val reason: String) : LevelState

    data class Failed(val reason: String) : LevelState

    /** The catalogue names a file the release does not carry. Not an error, and not the user's. */
    data object Unavailable : LevelState
}
