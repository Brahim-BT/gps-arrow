package dev.gpsarrow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.gpsarrow.R
import dev.gpsarrow.locale.AppLanguage

/**
 * First launch: pick a language.
 *
 * **A neutral three-way choice, not a default with a confirm.** Defaulting to the device
 * language is less friction and is the right call in most markets — but the phones this app
 * runs on are frequently shared, handed down or bought secondhand, and a device left in a
 * language its current owner does not read is a real and common case here. A preselected row
 * invites a reflexive tap on "continue" that confirms the wrong answer; three equal cards make
 * the choice explicit and cost exactly one tap either way.
 *
 * Every option is written in its own language and its own script, so it is recognisable to
 * someone who cannot read any of the surrounding text. There is deliberately no title in a
 * single language competing for attention — the three names are the interface.
 */
@Composable
fun LanguagePicker(
    onChosen: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.language_picker_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.language_picker_subtitle),
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        AppLanguage.entries.forEach { language ->
            Card(
                onClick = { onChosen(language) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .heightIn(min = 64.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        // Direction from the characters, not the screen, so each name reads
                        // correctly whichever language the app happens to be in right now.
                        text = language.nativeName,
                        style = MaterialTheme.typography.headlineMedium.merge(
                            TextStyle(textDirection = TextDirection.Content),
                        ),
                    )
                }
            }
        }
    }
}
