package dev.gpsarrow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.gpsarrow.R
import dev.gpsarrow.core.Format
import dev.gpsarrow.core.LatLon
import dev.gpsarrow.core.MapOrientation
import dev.gpsarrow.core.OrientationState
import dev.gpsarrow.core.SharedPoint
import dev.gpsarrow.maps.InstalledArea
import dev.gpsarrow.maps.CameraCommand
import dev.gpsarrow.maps.MapCamera
import dev.gpsarrow.maps.MapStyle
import dev.gpsarrow.maps.MapTier

/**
 * What the user sees when they open the map and there's no data for where they are.
 *
 * Three rules (BUILD_PLAN.md 7):
 *  1. Lead with the fact that the arrow is unaffected — the core feature has not failed.
 *  2. Name the area by the places it covers and give its size, so the dead end becomes an action.
 *  3. Never block going back.
 *
 * Nothing here renders a file path, a URL or a filename. An earlier version printed
 * `tier.installed.pmtilesUri` on screen, which showed the user a `pmtiles://file:///…` path — an
 * internal detail they cannot act on, and one that leaked the internal area id into the UI.
 */
@Composable
fun MapScreen(
    tier: MapTier,
    camera: MapCamera?,
    cameraCommand: CameraCommand?,
    positionGeoJson: String,
    destinationGeoJson: String,
    /** GeoJSON for the public shared-points dots. */
    sharedGeoJson: String,
    /** The shared dot the user tapped, or null for no selection. */
    selectedShared: SharedPoint?,
    /** Formatted distance from the user, when a fix exists; shown on the selection card. */
    selectedDistanceText: String?,
    /** True when the selected id already exists in the user's own list (hides "save as mine"). */
    selectedAlreadySaved: Boolean,
    orientation: OrientationState,
    hasPosition: Boolean,
    /** Smoothed position to keep centred while following. */
    followTarget: LatLon?,
    /**
     * Whether the user is moving fast enough (MotionGate) to justify the forward-view dot
     * offset while following.
     */
    moving: Boolean,
    onCameraMoved: (MapCamera) -> Unit,
    onUserGesture: () -> Unit,
    /** Tapped a shared dot (its id), or tapped empty map (null) to dismiss the card. */
    onSharedTap: (String?) -> Unit,
    onNavigateShared: (SharedPoint) -> Unit,
    onSaveShared: (SharedPoint) -> Unit,
    onFaceNorth: () -> Unit,
    onCentreOnMe: () -> Unit,
    onBack: () -> Unit,
    onOpenRegions: () -> Unit,
    onRemindWhenOnline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The map fills the screen; the empty states are centred cards. Those want different layouts,
    // so the branch comes before the container rather than inside it — an earlier version nested
    // the map inside a centred, 20dp-padded Column, which would have boxed the renderer into a
    // strip in the middle of the screen.
    when (tier) {
        is MapTier.Available -> InstalledMap(
            installed = tier.installed,
            camera = camera,
            cameraCommand = cameraCommand,
            positionGeoJson = positionGeoJson,
            destinationGeoJson = destinationGeoJson,
            sharedGeoJson = sharedGeoJson,
            selectedShared = selectedShared,
            selectedDistanceText = selectedDistanceText,
            selectedAlreadySaved = selectedAlreadySaved,
            orientation = orientation,
            hasPosition = hasPosition,
            followTarget = followTarget,
            moving = moving,
            onCameraMoved = onCameraMoved,
            onUserGesture = onUserGesture,
            onSharedTap = onSharedTap,
            onNavigateShared = onNavigateShared,
            onSaveShared = onSaveShared,
            onFaceNorth = onFaceNorth,
            onCentreOnMe = onCentreOnMe,
            onBack = onBack,
            onOpenRegions = onOpenRegions,
            modifier = modifier,
        )

        is MapTier.NoDataHere -> EmptyStateColumn(modifier, onBack) {
            NoDataCard(
                placesRes = tier.suggested?.placesRes,
                onOpenRegions = onOpenRegions,
                onRemindWhenOnline = onRemindWhenOnline,
            )
        }

        MapTier.ArrowOnly -> EmptyStateColumn(modifier, onBack) {
            NoDataCard(
                placesRes = null,
                onOpenRegions = onOpenRegions,
                onRemindWhenOnline = onRemindWhenOnline,
            )
        }
    }
}

@Composable
private fun EmptyStateColumn(
    modifier: Modifier,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(R.string.map_back_to_arrow))
        }
    }
}

/**
 * The map itself, with the attribution the licence requires.
 *
 * If the renderer will not start — missing native library for this ABI, unreadable style asset,
 * GL context refused — this falls back to the same card the user sees when nothing is installed.
 * The map tab degrades to "no map, and the arrow still works"; it never fails.
 */
@Composable
private fun InstalledMap(
    installed: InstalledArea,
    camera: MapCamera?,
    cameraCommand: CameraCommand?,
    positionGeoJson: String,
    destinationGeoJson: String,
    /** GeoJSON for the public shared-points dots. */
    sharedGeoJson: String,
    /** The shared dot the user tapped, or null for no selection. */
    selectedShared: SharedPoint?,
    /** Formatted distance from the user, when a fix exists; shown on the selection card. */
    selectedDistanceText: String?,
    /** True when the selected id already exists in the user's own list (hides "save as mine"). */
    selectedAlreadySaved: Boolean,
    orientation: OrientationState,
    hasPosition: Boolean,
    /** Smoothed position to keep centred while following. */
    followTarget: LatLon?,
    /**
     * Whether the user is moving fast enough (MotionGate) to justify the forward-view dot
     * offset while following.
     */
    moving: Boolean,
    onCameraMoved: (MapCamera) -> Unit,
    onUserGesture: () -> Unit,
    /** Tapped a shared dot (its id), or tapped empty map (null) to dismiss the card. */
    onSharedTap: (String?) -> Unit,
    onNavigateShared: (SharedPoint) -> Unit,
    onSaveShared: (SharedPoint) -> Unit,
    onFaceNorth: () -> Unit,
    onCentreOnMe: () -> Unit,
    onBack: () -> Unit,
    onOpenRegions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var rendererFailed by rememberSaveable(installed.file.path) { mutableStateOf(false) }
    val styleJson = remember(installed.file.path) { MapStyle.forInstalled(context, installed) }

    if (styleJson == null || rendererFailed) {
        EmptyStateColumn(modifier, onBack) {
            NoDataCard(
                placesRes = installed.area.placesRes,
                onOpenRegions = onOpenRegions,
                onRemindWhenOnline = {},
            )
        }
        return
    }

    // The map's own height, in pixels, for the forward-view offset. Constraints rather than
    // display metrics because the map does not fill the whole screen — the bar and tabs sit
    // above it — and a fraction of the wrong height puts the dot noticeably off its mark.
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val dotOffsetPx = with(LocalDensity.current) {
            maxHeight.toPx().toDouble() * MapCamera.DOT_OFFSET_TOP_FRACTION
        }

        MapLibreView(
            styleJson = styleJson,
            initialCamera = camera,
            cameraCommand = cameraCommand,
            positionGeoJson = positionGeoJson,
            destinationGeoJson = destinationGeoJson,
            sharedGeoJson = sharedGeoJson,
            onSharedPointTapped = onSharedTap,
            // Null the moment following is suspended, so the view leaves the camera entirely
            // alone rather than merely holding the bearing steady. It comes back by itself
            // after MapOrientation's idle dwell, or immediately via "centre on me" / north.
            followBearingDeg =
                if (orientation.followingHeading) orientation.appliedBearingDeg else null,
            // Same gate as the bearing: following stops the instant a gesture starts and stays
            // stopped until the idle dwell expires or "centre on me" is tapped. Position and
            // rotation are one intention, not two.
            followTarget = if (orientation.followingHeading) followTarget else null,
            // The forward-view offset only makes sense while actively following AND actually
            // moving; standing still it just buries part of the view.
            dotOffsetTopPx =
                if (orientation.followingHeading && moving) dotOffsetPx else null,
            modifier = Modifier.fillMaxSize(),
            onUnavailable = { rendererFailed = true },
            onCameraMoved = onCameraMoved,
            onUserGesture = onUserGesture,
        )

        // Top-end: where north is, and one tap back to north-up and following.
        if (MapOrientation.showNorthIndicator(orientation)) {
            NorthIndicator(
                northDegrees = MapOrientation.northIndicatorDegrees(orientation),
                onTap = onFaceNorth,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            )
        }

        // Bottom-end: the way back to yourself after panning away. Auto-resume brings the map
        // home on its own now, but nobody should have to wait eight seconds to be found.
        if (!orientation.followingHeading && hasPosition) {
            Button(
                onClick = onCentreOnMe,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
            ) { Text(stringResource(R.string.map_centre_on_me)) }
        }

        // Tap-inspect card for a shared public point. It floats above the controls — the
        // centre-on-me button and the attribution line both live in the bottom corners, so a
        // 64dp bottom inset clears whichever of them is showing. Dismissal is tapping the map
        // somewhere else, which arrives here as onSharedTap(null).
        if (selectedShared != null) {
            SharedSelectionCard(
                point = selectedShared,
                distanceText = selectedDistanceText,
                alreadySaved = selectedAlreadySaved,
                onNavigate = { onNavigateShared(selectedShared) },
                onSave = { onSaveShared(selectedShared) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = 64.dp),
            )
        }

        // ODbL condition: attribution must be visible wherever the map is shown, not buried in
        // an About page. It sits over the map rather than beside it for exactly that reason.
        Text(
            stringResource(R.string.about_map_attribution),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * What a tapped shared dot opens: who it came from (as much as is known — "another user"),
 * where it is, how far away, and the two things one can do with it.
 *
 * "Save as mine" disappears once the point already exists in the user's own list — every
 * saved-from-shared point keeps its original id, so the check is an id lookup, and offering
 * to save something already saved would create the impression of a duplicate.
 */
@Composable
private fun SharedSelectionCard(
    point: SharedPoint,
    distanceText: String?,
    alreadySaved: Boolean,
    onNavigate: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.shared_card_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(point.name, style = MaterialTheme.typography.titleLarge)
            Text(
                ltrIsolate(Format.decimal(point.position)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (distanceText != null) {
                Text(
                    distanceText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onNavigate, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.navigate_here))
                }
                if (!alreadySaved) {
                    OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.shared_card_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun NoDataCard(
    placesRes: Int?,
    onOpenRegions: () -> Unit,
    onRemindWhenOnline: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.map_none_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.map_none_arrow_works),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
            )

            // When an area covers this position, showing its place list is the most useful thing
            // on the screen: it answers "would downloading that help me?" without a country name
            // and without the user having to know what the area is called.
            if (placesRes != null) {
                Text(
                    stringResource(placesRes),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.area_covers_you),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center,
                )
            }

            Text(
                stringResource(
                    if (placesRes == null) R.string.map_none_explanation else R.string.map_none_prompt
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Button(onClick = onOpenRegions, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.map_open_region_list))
            }
            OutlinedButton(onClick = onRemindWhenOnline, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.map_remind_when_online))
            }
        }
    }
}
