package dev.gpsarrow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import dev.gpsarrow.core.DestinationParser
import dev.gpsarrow.core.Format
import dev.gpsarrow.core.LatLon
import dev.gpsarrow.core.Mgrs
import dev.gpsarrow.core.ParseResult
import dev.gpsarrow.core.PlusCode

/**
 * The "get a destination in without a geocoder" screen.
 *
 * The design principle here is that the parse result is shown live, before saving. There is no
 * search server to fall back on, so the user has to be able to see that the app understood
 * them — and, when it can't (shortened share links), to be told exactly why.
 */
@Composable
fun AddDestinationScreen(
    currentPosition: LatLon?,
    onSave: (name: String, position: LatLon, source: String) -> Unit,
    onSaveCurrent: (name: String) -> Unit,
    onBack: () -> Unit,
    initialText: String = "",
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf(initialText) }
    var name by remember { mutableStateOf("") }

    val result = remember(input, currentPosition) {
        if (input.isBlank()) null else DestinationParser.parse(input, currentPosition)
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
            TextButton(onClick = onBack) { Text("Back") }
            Text("Add destination", style = MaterialTheme.typography.headlineMedium)
            Text("")
        }

        OutlinedButton(
            onClick = { onSaveCurrent(name.ifBlank { "Here" }) },
            modifier = Modifier.fillMaxWidth(),
            enabled = currentPosition != null,
        ) {
            Text(
                if (currentPosition != null) "Save my current position"
                else "Waiting for a position fix…",
            )
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Paste coordinates, plus code, MGRS, geo: link or map URL") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        when (result) {
            null -> FormatHelp()

            is ParseResult.Success -> ParsePreview(result)

            is ParseResult.NeedsNetwork -> Notice(
                title = "Can't resolve this offline",
                body = result.reason,
                error = true,
            )

            is ParseResult.Invalid -> Notice(
                title = "That doesn't look right",
                body = result.reason,
                error = true,
            )

            ParseResult.Unrecognised -> Notice(
                title = "Not recognised yet",
                body = "Keep typing, or check the supported formats below.",
                error = false,
            )
        }

        Button(
            onClick = {
                val success = result as? ParseResult.Success ?: return@Button
                onSave(
                    name.ifBlank { defaultName(success) },
                    success.position,
                    success.format.name.lowercase(),
                )
            },
            enabled = result is ParseResult.Success,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save destination")
        }

        if (result == null) FormatExamples()
    }
}

private fun defaultName(success: ParseResult.Success): String =
    success.label?.takeIf { it.isNotBlank() } ?: Format.decimal(success.position)

@Composable
private fun ParsePreview(result: ParseResult.Success) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f),
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Read as ${result.format.name.lowercase().replace('_', ' ')}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(Format.decimal(result.position), style = MaterialTheme.typography.headlineMedium)
            Text(Format.dms(result.position), style = MaterialTheme.typography.bodyLarge)
            Text(
                "Plus code ${PlusCode.encode(result.position)}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Mgrs.toMgrs(result.position, spaced = true)?.let {
                Text("MGRS $it", style = MaterialTheme.typography.bodyLarge)
            }
            if (result.usedReference) {
                Text(
                    "Short plus code — resolved against your current position, so it assumes " +
                        "the place is near you.",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun Notice(title: String, body: String, error: Boolean) {
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
            Text(
                body,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FormatHelp() {
    Text(
        "Nothing to parse yet.",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FormatExamples() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Formats that work offline", style = MaterialTheme.typography.bodyLarge)
        listOf(
            "48.8584, 2.2945",
            """48°51'30"N 2°17'40"E""",
            "geo:48.8584,2.2945",
            "8FW4V75V+8Q  (plus code)",
            "V75V+8Q  (short plus code, uses your position)",
            "31U DQ 48251 11954  (MGRS)",
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
