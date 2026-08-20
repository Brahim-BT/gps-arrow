package dev.gpsarrow.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.gpsarrow.core.AreaChoice
import dev.gpsarrow.core.Format
import dev.gpsarrow.core.LatLon
import dev.gpsarrow.core.Pmtiles
import dev.gpsarrow.core.PmtilesCheck
import dev.gpsarrow.maps.Detail
import dev.gpsarrow.maps.MapArea
import dev.gpsarrow.maps.RegionCatalogue
import dev.gpsarrow.maps.RegionIndex
import dev.gpsarrow.service.MapDownloadState
import java.io.File
import java.util.Locale

/**
 * Turns the catalogue, what is on disk, and any download in flight into rows the areas screen can
 * render without doing any thinking of its own.
 *
 * All formatting happens here rather than in the composables, because numbers have to go through
 * the app's pinned Latin-digit locale. A composable reaching for `String.format` is how
 * Arabic-Indic digits got into a size label once already.
 */
@Composable
fun rememberAreaRows(
    context: Context,
    index: RegionIndex,
    download: MapDownloadState,
    position: LatLon?,
    locale: Locale,
): List<AreaRow> = remember(download, position, locale, index.installedAreas()) {
    buildAreaRows(context, index, download, position, locale)
}

internal fun buildAreaRows(
    context: Context,
    index: RegionIndex,
    download: MapDownloadState,
    position: LatLon?,
    locale: Locale,
): List<AreaRow> {
    index.scan()

    // Which area the map would actually use, by the same rule tierFor applies. The indicator has
    // to describe behaviour, not geometry: two areas can both contain the user, and saying so
    // twice leaves the real question — which one am I getting — unanswered.
    val servingId = position?.let { p ->
        val installedIds = index.installedAreas().map { it.area.id }.toSet()
        // Prefer an installed area if one covers them; otherwise this becomes a recommendation.
        val pool = RegionCatalogue.ALL.filter { installedIds.isEmpty() || it.id in installedIds }
        AreaChoice.serving(
            p,
            pool.map { area ->
                AreaChoice.Candidate(
                    id = area.id,
                    maxZoom = area.levels.maxOf { it.maxZoom },
                    west = area.bbox.west, south = area.bbox.south,
                    east = area.bbox.east, north = area.bbox.north,
                    containsPosition = area.bbox.contains(p),
                )
            },
        )
    }
    val anyInstalled = index.installedAreas().isNotEmpty()

    return RegionCatalogue.ALL.map { area ->
        val installed = index.installFor(area.id)
        AreaRow(
            area = area,
            coverage = when {
                position == null -> Coverage.NO_FIX
                !area.bbox.contains(position) -> Coverage.OUTSIDE
                area.id != servingId -> Coverage.ALSO_COVERS
                anyInstalled -> Coverage.SERVING
                else -> Coverage.RECOMMENDED
            },
            sizeLabels = area.levels.associate { it.detail to megabytes(it.bytes, locale) },
            states = area.levels.associate { level ->
                level.detail to levelState(
                    context = context,
                    area = area,
                    detail = level.detail,
                    maxZoom = level.maxZoom,
                    installedDetail = installed?.level?.detail,
                    installedFile = installed?.file,
                    download = download,
                    locale = locale,
                )
            },
        )
    }
}

private fun levelState(
    context: Context,
    area: MapArea,
    detail: Detail,
    maxZoom: Int,
    installedDetail: Detail?,
    installedFile: File?,
    download: MapDownloadState,
    locale: Locale,
): LevelState {
    // A download in flight for this exact level wins over everything else on screen.
    if (download is MapDownloadState.Running &&
        download.areaId == area.id && download.maxZoom == maxZoom
    ) {
        val total = download.totalBytes.coerceAtLeast(1L)
        return LevelState.Downloading(
            fraction = (download.doneBytes.toFloat() / total).coerceIn(0f, 1f),
            doneLabel = megabytes(download.doneBytes, locale),
            totalLabel = megabytes(download.totalBytes, locale),
            replacingInstalled = download.replacingInstalled,
        )
    }

    if (installedDetail == detail && installedFile != null) {
        return LevelState.Installed(verification = verify(installedFile, locale))
    }
    if (installedDetail != null) return LevelState.Replaceable

    // Failures are only shown against the level they happened to, so a failed download of one
    // level does not paint an error over the other.
    val mine = downloadTargetsThisLevel(download, area.id, maxZoom)
    return when {
        !mine -> LevelState.Absent
        download is MapDownloadState.Unavailable -> LevelState.Unavailable
        download is MapDownloadState.NoSpace -> LevelState.Failed(
            context.getString(
                dev.gpsarrow.R.string.download_no_space,
                megabytes(download.neededBytes, locale),
                megabytes(download.freeBytes, locale),
            ),
        )
        download is MapDownloadState.NetworkFailed ->
            LevelState.Paused(context.getString(dev.gpsarrow.R.string.download_network_failed))
        download is MapDownloadState.Cancelled ->
            LevelState.Paused(context.getString(dev.gpsarrow.R.string.download_network_failed))
        download is MapDownloadState.ServerRefused -> LevelState.Failed(
            context.getString(
                dev.gpsarrow.R.string.download_server_error,
                Format.number("%d", locale, download.statusCode),
            ),
        )
        download is MapDownloadState.Corrupt ->
            LevelState.Failed(context.getString(dev.gpsarrow.R.string.download_corrupt))
        else -> LevelState.Absent
    }
}

/**
 * Whether a terminal download state belongs to this level.
 *
 * The terminal states do not carry an area id — by the time a download has failed there is no
 * running transfer to name one. So this is a best effort: a failure is attributed to the level the
 * last *running* state named. It is deliberately conservative and shows nothing rather than
 * showing an error against the wrong row.
 */
private fun downloadTargetsThisLevel(
    download: MapDownloadState,
    areaId: String,
    maxZoom: Int,
): Boolean = when (download) {
    is MapDownloadState.Running -> download.areaId == areaId && download.maxZoom == maxZoom
    is MapDownloadState.Installed -> download.areaId == areaId && download.maxZoom == maxZoom
    MapDownloadState.Idle -> false
    // Terminal failures: shown, because a silent failure is worse than one attributed loosely.
    MapDownloadState.Cancelled, MapDownloadState.Unavailable, MapDownloadState.NetworkFailed,
    MapDownloadState.Corrupt,
    -> true
    is MapDownloadState.NoSpace, is MapDownloadState.ServerRefused -> true
}

/**
 * Re-read an installed archive's PMTiles header.
 *
 * The diagnostic that tells a bad download apart from a bad renderer: 127 bytes, no CPU, and it
 * runs against the file the renderer will actually be handed.
 */
private fun verify(file: File, locale: Locale): FileVerification = try {
    val head = ByteArray(Pmtiles.HEADER_BYTES)
    val read = file.inputStream().use { it.read(head) }
    if (read < Pmtiles.HEADER_BYTES) {
        FileVerification.Bad("short file")
    } else {
        when (val c = Pmtiles.check(head, file.length())) {
            is PmtilesCheck.Valid -> FileVerification.Good(megabytes(file.length(), locale))
            is PmtilesCheck.Invalid -> FileVerification.Bad(c.problem.name)
        }
    }
} catch (e: Exception) {
    FileVerification.Bad(e.javaClass.simpleName)
}

/** Megabytes, through the pinned Latin-digit locale. Decimal MB, matching what GitHub reports. */
internal fun megabytes(bytes: Long, locale: Locale): String =
    Format.number("%d", locale, (bytes + 500_000L) / 1_000_000L) + " MB"


/**
 * Whether the active connection is metered.
 *
 * Deliberately a **capability** check, not a transport check. `TRANSPORT_CELLULAR` versus
 * `TRANSPORT_WIFI` gets this wrong in both directions: a phone tethered to a metered hotspot
 * reports as wifi, and an unmetered corporate mobile plan reports as cellular.
 * `NET_CAPABILITY_NOT_METERED` is the thing that actually answers "will this cost them".
 *
 * Returns false when the answer is unknown. A warning shown on an unmetered connection teaches
 * people to tap past warnings, which is worse than missing one.
 */
fun isMeteredConnection(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}
