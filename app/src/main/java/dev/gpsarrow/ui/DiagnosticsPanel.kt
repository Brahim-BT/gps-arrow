package dev.gpsarrow.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gpsarrow.Diagnostic
import dev.gpsarrow.R

/** Everything needed to tell a dead sensor from a dead pipeline, without a USB cable. */
@Composable
internal fun DiagnosticsPanel(rows: List<Diagnostic>, onClose: () -> Unit) {
    Card(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.diagnostics_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    stringResource(R.string.diagnostics_close),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.pointerInput(Unit) { detectTapGestures { onClose() } },
                )
            }
            Text(
                stringResource(R.string.diagnostics_hint),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            rows.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(row.labelRes),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = when {
                            row.value.isEmpty() -> stringResource(R.string.diag_none)
                            row.valueRes != null -> stringResource(row.valueRes)
                            row.unitRes != null -> stringResource(row.unitRes, row.value)
                            else -> row.value
                        },
                        modifier = Modifier.weight(1.2f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}
