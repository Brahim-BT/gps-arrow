package dev.gpsarrow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.gpsarrow.R
import dev.gpsarrow.locale.AppLanguage

/**
 * Settings, which is also About.
 *
 * **A tab, not a screen buried behind an overflow menu.** The app has exactly one setting worth
 * changing — the language — and it is the setting a user is most likely to need on a phone that
 * is not theirs, or one bought secondhand and left in someone else's language. Making that a
 * four-tap journey through a menu they cannot read would be a poor joke. Four tabs still leave
 * roughly 97dp each at the A54's width, which the labels fit inside.
 *
 * About lives here rather than in its own place because it is three lines of provenance, and
 * because the honest answers to "where does the compass correction come from" and "does this
 * app phone home" belong somewhere a curious user can actually find.
 */
@Composable
fun SettingsScreen(
    current: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    versionName: String,
    declinationSource: String,
    /** True when the OS model is in use, which has a friendlier name than its class name. */
    declinationIsFramework: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
        )

        Text(
            text = stringResource(R.string.settings_language),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                AppLanguage.entries.forEachIndexed { index, language ->
                    if (index > 0) HorizontalDivider()
                    LanguageRow(
                        language = language,
                        selected = language == current,
                        onSelect = { onLanguageSelected(language) },
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.settings_about),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AboutRow(stringResource(R.string.about_version), versionName)
                AboutRow(
                    label = stringResource(R.string.about_declination_source),
                    value = if (declinationIsFramework) {
                        stringResource(R.string.about_declination_framework)
                    } else {
                        declinationSource
                    },
                )
                Text(
                    text = stringResource(R.string.about_declination_note),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.about_offline),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = stringResource(R.string.about_attribution),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One language, written in itself.
 *
 * [TextDirection.Content] on the name is the point of this being its own composable: "العربية"
 * has to read right-to-left inside an English layout and "English" left-to-right inside an
 * Arabic one, so the direction has to come from the characters rather than from the screen.
 * Someone who cannot read the current language finds their own by its shape.
 */
@Composable
private fun LanguageRow(
    language: AppLanguage,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = language.nativeName,
            style = MaterialTheme.typography.bodyLarge.merge(
                TextStyle(textDirection = TextDirection.Content),
            ),
        )
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1.4f),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
