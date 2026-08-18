package dev.gpsarrow.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.gpsarrow.R

/**
 * Location permission, explained before it is asked for.
 *
 * Two things this deliberately does NOT do:
 *  - request ACCESS_BACKGROUND_LOCATION (Play review cost, no benefit — the service is only
 *    ever started from a visible activity)
 *  - request POST_NOTIFICATIONS at launch (asked for lazily, when navigation actually starts)
 *
 * On Android 12+ the system dialog offers "approximate" — which is useless for an arrow, so
 * that case gets its own explanation and a one-tap path to fix it.
 */
@Composable
fun PermissionGate(
    onGranted: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var fineGranted by remember { mutableStateOf(context.hasFineLocation()) }
    var coarseOnly by remember { mutableStateOf(context.hasCoarseOnly()) }
    var asked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        fineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        coarseOnly = !fineGranted && result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        asked = true
    }

    if (fineGranted) {
        onGranted()
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(
                if (coarseOnly) R.string.permission_title_precise
                else R.string.permission_title_location,
            ),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(
                if (coarseOnly) R.string.permission_body_approximate
                else R.string.permission_body_initial,
            ),
            modifier = Modifier.padding(vertical = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Button(
            onClick = {
                launcher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            },
        ) {
            Text(
                stringResource(
                    if (coarseOnly) R.string.permission_grant_precise
                    else R.string.permission_continue,
                ),
            )
        }

        // After a denial the system stops showing the dialog; the only route left is Settings.
        if (asked && !fineGranted) {
            TextButton(onClick = { context.openAppSettings() }) {
                Text(stringResource(R.string.permission_open_settings))
            }
        }
    }
    // Note: the dialog is never fired automatically on launch. Explaining first and letting
    // the user tap Continue measurably improves grant rates, and a denial here is permanent.
}

/**
 * Notification permission, requested only when a navigation session starts, because that is
 * the moment the foreground-service notification actually becomes useful.
 */
@Composable
fun rememberNotificationPermissionRequest(): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private fun Context.hasFineLocation() = ContextCompat.checkSelfPermission(
    this,
    Manifest.permission.ACCESS_FINE_LOCATION,
) == PackageManager.PERMISSION_GRANTED

private fun Context.hasCoarseOnly() = !hasFineLocation() && ContextCompat.checkSelfPermission(
    this,
    Manifest.permission.ACCESS_COARSE_LOCATION,
) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
