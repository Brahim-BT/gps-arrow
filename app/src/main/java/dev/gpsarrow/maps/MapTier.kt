package dev.gpsarrow.maps

import android.content.Context
import dev.gpsarrow.core.LatLon
import java.io.File

/**
 * The offline-first tiering logic, in one file.
 *
 * v0 ships this with no renderer behind it. That is deliberate: the arrow must never depend on
 * the map tier, so the tier resolution lives here as data and the :maps module (v1) supplies the
 * MapLibre view that consumes it. See BUILD_PLAN.md 6.4.
 */

/** A region as advertised in the server catalogue. */
data class RegionSummary(
    val id: String,
    val name: String,
    val parentId: String?,
    val bbox: BoundingBox,
    val maxZoom: Int,
    val bytes: Long,
    val url: String,
    val checksum: String?,
) {
    val approximateSizeLabel: String
        get() = when {
            bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1e9)
            bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
            else -> "${bytes / 1_000} kB"
        }
}

/** A region that has been fully downloaded and verified on this device. */
data class InstalledRegion(
    val summary: RegionSummary,
    val file: File,
    val installedAtMillis: Long,
    val catalogueVersion: String,
) {
    /** The URI MapLibre reads directly. Requires MapLibre Android >= 11.7.0. */
    val pmtilesUri: String get() = "pmtiles://file://${file.absolutePath}"
}

data class BoundingBox(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
) {
    fun contains(p: LatLon): Boolean =
        p.lat in south..north && p.lon in west..east

    /** Rough centre, good enough for "which region is nearest" ranking. */
    val center: LatLon get() = LatLon((south + north) / 2, (west + east) / 2)
}

/**
 * What the map view can show right now.
 *
 * The distinction between [ArrowOnly] and [NoDataHere] matters: the first means the feature
 * doesn't exist in this build, the second means the user can fix it by downloading something,
 * and the empty state should say which one it is.
 */
sealed interface MapTier {

    /** No map module, or no regions installed at all. */
    data object ArrowOnly : MapTier

    /** Regions exist, but none covers where the user is. [suggested] names the fix. */
    data class NoDataHere(val suggested: RegionSummary?) : MapTier

    data class Available(val region: InstalledRegion) : MapTier
}

/**
 * Index of what is installed on this device.
 *
 * Scans the app-scoped external files directory: no storage permission needed, survives app
 * updates, and is removed cleanly on uninstall — which is what you want for multi-gigabyte
 * files. `MANAGE_EXTERNAL_STORAGE` would never survive Play review for this use case.
 */
class RegionIndex(private val context: Context) {

    private var installed: List<InstalledRegion> = emptyList()
    private var catalogue: List<RegionSummary> = emptyList()

    val regionsDirectory: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, DIRECTORY)
            .apply { mkdirs() }

    fun installedRegions(): List<InstalledRegion> = installed

    /** Bytes used by downloaded map data — shown in the storage meter. */
    fun bytesUsed(): Long = regionsDirectory.listFiles()
        ?.filter { it.isFile && it.name.endsWith(EXTENSION) }
        ?.sumOf { it.length() } ?: 0L

    fun tierFor(position: LatLon?): MapTier {
        if (installed.isEmpty()) {
            val suggested = position?.let { p -> catalogue.firstOrNull { it.bbox.contains(p) } }
            return if (catalogue.isEmpty() && suggested == null) {
                MapTier.ArrowOnly
            } else {
                MapTier.NoDataHere(suggested)
            }
        }
        if (position == null) return MapTier.NoDataHere(null)

        installed.firstOrNull { it.summary.bbox.contains(position) }?.let {
            return MapTier.Available(it)
        }
        return MapTier.NoDataHere(catalogue.firstOrNull { it.bbox.contains(position) })
    }

    /** Called by the v1 download manager once a file is verified and renamed into place. */
    fun setInstalled(regions: List<InstalledRegion>) {
        installed = regions
    }

    /**
     * The catalogue is cached on disk so the region list stays browsable offline — a user
     * with no signal can still see what they *would* download and how big it is.
     */
    fun setCatalogue(regions: List<RegionSummary>) {
        catalogue = regions
    }

    private companion object {
        const val DIRECTORY = "regions"
        const val EXTENSION = ".pmtiles"
    }
}
