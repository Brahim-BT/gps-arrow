package dev.gpsarrow.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gpsarrow.R
import dev.gpsarrow.core.FixQuality
import dev.gpsarrow.core.CoordinateFormat
import dev.gpsarrow.core.Format
import dev.gpsarrow.core.NavigationState
import dev.gpsarrow.ui.theme.AppTheme

/**
 * The position area, which is also where the app admits it has no position and why.
 *
 * Three genuinely different problems live here and the user can only act on the right one if it
 * is named. The permission gate upstream handles the first; these are the other two:
 *
 *  - **Location switched off at the OS level.** Granting this app permission is not the same as
 *    the device's location master switch being on, and when it is off `requestLocationUpdates`
 *    succeeds and then silently never delivers. Saying "searching for satellites" here would be
 *    a lie that costs the user minutes standing outside — which is exactly what it did. So this
 *    state says so plainly and offers the one-tap route to the setting.
 *  - **Enabled, permitted, but no fix yet.** A genuinely normal state for 30 to 90 seconds from
 *    cold with no assistance data, and the satellite count is the reassurance that something is
 *    happening. That count used to live in a status chip; with the chips gone this is its home,
 *    and it is a better one, because it appears exactly when it is meaningful.
 */
@Composable
fun PositionStatus(
    state: NavigationState,
    locationEnabled: Boolean,
    satellitesUsed: Int,
    satellitesVisible: Int,
    format: CoordinateFormat,
    onCycleFormat: () -> Unit,
    onCopied: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val numberLocale = rememberNumberLocale()
    val tokens = AppTheme.tokens
    when {
        !locationEnabled -> Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.location_off_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.location_off_body),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.label,
            )
            Button(
                onClick = onOpenLocationSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.location_off_button),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }

        state.fix == null -> Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.acquiring_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    R.string.acquiring_body,
                    Format.number("%d", numberLocale, satellitesUsed),
                    Format.number("%d", numberLocale, satellitesVisible),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.label,
            )
        }

        else -> PositionBand(state, format, onCycleFormat, onCopied, modifier)
    }
}

/**
 * The user's own position, always on screen above the arrow.
 *
 * **Always visible, rather than revealed by a tap.** Hiding it behind a gesture would fail the
 * one thing it was asked to be, which is prominent — and a position you have to remember how to
 * summon is no use in the moment you need to read it to somebody. It costs one line, and the
 * needle below has `weight(1f)`, so the arrow gives up about 30dp of roughly 430 and stays the
 * dominant thing on the screen. It also sits *above* the four-corner composition rather than
 * inside it, so that layout is untouched.
 *
 * **Accuracy is shown, and precision is not reduced.** Five decimal places from a ±40 m fix does
 * overstate what is known, and the two ways to fix that are to truncate the number or to qualify
 * it. This qualifies it. Truncating is lossy at exactly the wrong moment: the number is being
 * written down or read aloud, the recipient may have a better fix to combine it with, and a
 * digit discarded here cannot be recovered there. It also does not generalise — a plus code's
 * precision is its length and MGRS's is its digit count, so "reduce precision" would need four
 * different rules and would make the same spot render differently as accuracy wandered.
 */
@Composable
private fun PositionBand(
    state: NavigationState,
    format: CoordinateFormat,
    onCycleFormat: () -> Unit,
    onCopied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val numberLocale = rememberNumberLocale()
    val tokens = AppTheme.tokens
    val position = state.fix?.position

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = position != null, onClick = onCycleFormat),
        ) {
            Text(
                // "My position · Plus code" — the format name doubles as the hint that this is
                // tappable, without spending a line on an instruction.
                text = if (position == null) {
                    stringResource(R.string.my_position)
                } else {
                    stringResource(R.string.my_position) + " · " +
                        stringResource(format.labelRes())
                },
                style = MaterialTheme.typography.labelLarge,
                color = tokens.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (position == null) {
                Text(
                    text = stringResource(R.string.position_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.label,
                    maxLines = 1,
                )
            } else {
                val stale = state.quality == FixQuality.STALE
                Text(
                    // Isolated for display only. The clipboard copy below deliberately uses the
                    // un-isolated string: U+2066/U+2069 pasted into another app would corrupt
                    // the coordinate, which is the one outcome this whole feature must avoid.
                    text = ltrIsolate(format.render(position)) +
                        (
                            state.fix?.accuracyMeters?.let {
                                "  " + stringResource(
                                    R.string.chip_accuracy,
                                    Format.number("%d", numberLocale, it.toInt()),
                                )
                            } ?: ""
                            ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (stale) tokens.label else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (stale) {
                    Text(
                        text = stringResource(R.string.position_stale),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                    )
                }
            }
        }

        if (position != null) {
            // An explicit button, not a long-press. A hidden gesture is not an affordance, and
            // copying is the entire reason this band earns its line — the point of reading your
            // own position is to do something with it.
            IconButton(
                onClick = {
                    context.copyPlainText(
                        label = context.getString(R.string.my_position),
                        text = format.render(position),
                    )
                    onCopied()
                },
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.cd_copy_position),
                    tint = tokens.accent,
                )
            }
        }
    }
}

/**
 * Framework clipboard rather than Compose's `LocalClipboardManager`.
 *
 * The Compose wrapper is mid-deprecation in favour of a suspend API, and this is a two-line
 * platform call that has been stable since API 11. Not worth taking a deprecation cycle on
 * something that cannot be compiled here before it ships.
 */
private fun Context.copyPlainText(label: String, text: String) {
    runCatching {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
