package dev.gpsarrow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.gpsarrow.core.Destination
import dev.gpsarrow.core.DestinationParser
import dev.gpsarrow.core.Format
import dev.gpsarrow.core.LatLon
import dev.gpsarrow.core.Mgrs
import dev.gpsarrow.core.ParseResult
import dev.gpsarrow.core.PlusCode

/**
 * Two clearly labelled fields, because one combined box made people guess at the format.
 *
 * The smart parser is still there, it just isn't the primary affordance any more: paste a whole
 * coordinate — decimal pair, DMS, plus code, MGRS, `geo:` URI or a map URL — into the latitude
 * field and both fields fill themselves. Type a plain number and it stays a plain number.
 */
@Composable
fun AddDestinationScreen(
    /** Hoisted so tab switches don't discard a half-typed coordinate. */
    draft: CoordinateDraft,
    onDraftChange: (CoordinateDraft) -> Unit,
    currentPosition: LatLon?,
    onSave: (name: String, position: LatLon, source: String) -> Unit,
    /** null when shown as a tab (nothing to go back to); non-null in edit mode. */
    onBack: (() -> Unit)? = null,
    /** Non-null puts the screen in edit mode: same fields, same parser, different verb. */
    editing: Destination? = null,
    modifier: Modifier = Modifier,
) {
    val name = draft.name
    val latText = draft.latText
    val lonText = draft.lonText
    val pastedFormat = draft.readAs

    /**
     * Try to read [text] as a COMPLETE coordinate. Returns null for anything that is just one
     * number, so ordinary typing in the latitude field is never hijacked.
     */
    fun asFullCoordinate(text: String): ParseResult.Success? {
        if (text.isBlank()) return null
        // Locale-aware, and it has to be: see DestinationParser.isSingleComponent.
        if (DestinationParser.isSingleComponent(text)) return null
        return DestinationParser.parse(text, currentPosition) as? ParseResult.Success
    }

    /** Returns true when [text] was a whole coordinate and both fields were filled from it. */
    fun applyPaste(text: String): Boolean {
        val parsed = asFullCoordinate(text) ?: return false
        onDraftChange(
            draft.copy(
                latText = Format.coordinate(parsed.position.lat),
                lonText = Format.coordinate(parsed.position.lon),
                readAs = parsed.format.name.lowercase().replace('_', ' '),
                name = if (draft.name.isBlank()) parsed.label ?: draft.name else draft.name,
            ),
        )
        return true
    }

    val lat = latText.trim().replace(',', '.').toDoubleOrNull()
    val lon = lonText.trim().replace(',', '.').toDoubleOrNull()
    val latError = when {
        latText.isBlank() -> null
        lat == null -> "Not a number"
        lat < -90 || lat > 90 -> "Must be between -90 and 90"
        else -> null
    }
    val lonError = when {
        lonText.isBlank() -> null
        lon == null -> "Not a number"
        lon < -180 || lon > 180 -> "Must be between -180 and 180"
        else -> null
    }
    val position = if (latError == null && lonError == null && lat != null && lon != null) {
        LatLon(lat, lon)
    } else {
        null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                TextButton(onClick = onBack) { Text("Back") }
            } else {
                Spacer(Modifier.width(1.dp))
            }
            Text(
                if (editing == null) "Add a point" else "Edit point",
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.width(1.dp))
        }

        OutlinedTextField(
            value = name,
            onValueChange = { onDraftChange(draft.copy(name = it)) },
            label = { Text("Name") },
            placeholder = { Text("Trailhead") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = latText,
            onValueChange = {
                if (!applyPaste(it)) onDraftChange(draft.copy(latText = it, readAs = null))
            },
            label = { Text("Latitude") },
            placeholder = { Text("48.8584    (N positive, S negative)") },
            supportingText = {
                Text(latError ?: "Or paste a whole coordinate here — both fields will fill.")
            },
            isError = latError != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = lonText,
            onValueChange = {
                if (!applyPaste(it)) onDraftChange(draft.copy(lonText = it, readAs = null))
            },
            label = { Text("Longitude") },
            placeholder = { Text("2.2945    (E positive, W negative)") },
            supportingText = { Text(lonError ?: "Between -180 and 180") },
            isError = lonError != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth(),
        )

        pastedFormat?.let {
            Text(
                "Read as $it and split into both fields.",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        position?.let { OtherFormats(it) }

        Button(
            onClick = {
                val p = position ?: return@Button
                onSave(
                    name.ifBlank { Format.decimal(p) },
                    p,
                    pastedFormat ?: "manual",
                )
            },
            enabled = position != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (editing == null) "Save point" else "Save changes")
        }

        if (editing == null) FormatExamples()
    }
}

/** Confirms the point in every other notation, which is how people catch a transposition. */
@Composable
private fun OtherFormats(p: LatLon) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f),
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Format.decimal(p), style = MaterialTheme.typography.headlineMedium)
            Text(Format.dms(p), style = MaterialTheme.typography.bodyLarge)
            Text("Plus code ${PlusCode.encode(p)}", style = MaterialTheme.typography.bodyLarge)
            Mgrs.toMgrs(p, spaced = true)?.let {
                Text("MGRS $it", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun FormatExamples() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Pasteable formats", style = MaterialTheme.typography.bodyLarge)
        listOf(
            "48.8584, 2.2945",
            "33.8568 S, 151.2153 E",
            """48°51'30"N 2°17'40"E""",
            "geo:48.8584,2.2945",
            "8FW4V75V+8Q  (plus code)",
            "18S UJ 23477 06483  (MGRS)",
            "openstreetmap.org/#map=17/48.8584/2.2945",
        ).forEach {
            Text(
                "· $it",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Shortened links (maps.app.goo.gl, bit.ly) can never work offline — only the " +
                "server that made them knows where they point.",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
