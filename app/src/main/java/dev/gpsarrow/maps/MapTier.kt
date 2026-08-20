package dev.gpsarrow.maps

import android.content.Context
import dev.gpsarrow.core.AreaChoice
import dev.gpsarrow.core.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The offline-first tiering logic, in one file.
 *
 * The arrow must never depend on the map tier, so tier resolution lives here as data and the
 * renderer consumes it. See BUILD_PLAN.md 6.4.
 */

/** A level of an area that has been downloaded and verified on this device. */
data class InstalledArea(
    val area: MapArea,
    val level: AreaLevel,
    val file: File,
    val installedAtMillis: Long,
) {
    /** The URI MapLibre reads directly. Requires MapLibre Android >= 11.7.0. */
    val pmtilesUri: String get() = "pmtiles://file://${file.absolutePath}"

    val bytesOnDisk: Long get() = file.length()

    /** This area as the pure chooser sees it. */
    fun candidate(position: LatLon) = AreaChoice.Candidate(
        id = area.id,
        maxZoom = level.maxZoom,
        west = area.bbox.west,
        south = area.bbox.south,
        east = area.bbox.east,
        north = area.bbox.north,
        containsPosition = area.bbox.contains(position),
    )
}

data class BoundingBox(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
) {
    fun contains(p: LatLon): Boolean =
        p.lat in south..north && p.lon in west..east

    /** Rough centre, good enough for "which area is nearest" ranking. */
    val center: LatLon get() = LatLon((south + north) / 2, (west + east) / 2)
}

/**
 * What the map view can show right now.
 *
 * [NoDataHere] and [ArrowOnly] are kept apart deliberately: the first means the user can fix this
 * by downloading something and [suggested] names which, the second means there is nothing to
 * download that would help. The empty state must say which, because "download this area" is
 * useless advice to someone standing outside every area we ship.
 */
sealed interface MapTier {

    /** Nothing installed and nothing in the catalogue covers this position. */
    data object ArrowOnly : MapTier

    /** Not installed, but an area covers this position. [suggested] is that area. */
    data class NoDataHere(val suggested: MapArea?) : MapTier

    data class Available(val installed: InstalledArea) : MapTier
}

/**
 * Index of what is installed on this device.
 *
 * Scans the app-scoped external files directory: no storage permission needed, survives app
 * updates, and is removed cleanly on uninstall — which is what you want for files this size.
 * `MANAGE_EXTERNAL_STORAGE` would never survive Play review for this use case.
 *
 * **One level per area, ever.** [installFor] returns at most one entry per area id, and the
 * downloader removes the previous file only after the replacement has verified. The invariant is
 * enforced on disk by the filename carrying the level, so two levels of one area cannot silently
 * coexist unnoticed — [scan] would find both and [prune] removes the stale one.
 */
class RegionIndex(private val context: Context) {

    /** Reads and writes the process-wide flow; this class holds no list of its own. */
    private var installed: List<InstalledArea>
        get() = shared.value
        set(value) { shared.value = value }

    /**
     * Whether [scan] has run at least once.
     *
     * Without this, an index that has never scanned is indistinguishable from one that scanned
     * and found nothing — and [tierFor] would confidently report "no map here" while a perfectly
     * good archive sat on disk.
     */
    private var scanned = false

    val regionsDirectory: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, DIRECTORY)
            .apply { mkdirs() }

    fun installedAreas(): List<InstalledArea> = installed

    fun installFor(areaId: String): InstalledArea? = installed.firstOrNull { it.area.id == areaId }

    /** Bytes used by downloaded map data — shown in the storage meter. */
    fun bytesUsed(): Long = regionsDirectory.listFiles()
        ?.filter { it.isFile && it.name.endsWith(EXTENSION) }
        ?.sumOf { it.length() } ?: 0L

    /**
     * Rebuild the index from what is actually on disk.
     *
     * The filesystem is the source of truth, not a stored list: Android may delete files in
     * `getExternalFilesDir` under storage pressure, and a remembered list would then claim a map
     * that is gone. Re-reading is cheap and cannot disagree with reality.
     */
    fun scan(): List<InstalledArea> {
        val files = regionsDirectory.listFiles().orEmpty()
        val found = mutableListOf<InstalledArea>()
        for (area in RegionCatalogue.ALL) {
            for (level in area.levels) {
                val f = files.firstOrNull { it.name == level.fileStem(area.id) + EXTENSION }
                if (f != null && f.isFile) {
                    found += InstalledArea(area, level, f, f.lastModified())
                }
            }
        }
        installed = found
        scanned = true
        return found
    }

    companion object {
        private const val DIRECTORY = "regions"
        private const val EXTENSION = ".pmtiles"

        /**
         * What is installed, shared across every [RegionIndex] in the process.
         *
         * Three instances of this class are alive at once — one in the ViewModel, one in the
         * areas screen, one created by the download service — and each used to hold a private
         * list behind a private `scanned` flag. So the service could finish an install, scan,
         * and update *its* copy, while the ViewModel's copy stayed empty and its `scanned` flag
         * said there was no reason to look again. The map only appeared after a restart, when
         * fresh instances scanned for the first time.
         *
         * One StateFlow on the companion fixes both halves at once: a single source of truth,
         * and the UI recomposes when it changes rather than having to be asked.
         */
        internal val shared = MutableStateFlow<List<InstalledArea>>(emptyList())

        /** Read-only view for observers. */
        val sharedFlow: StateFlow<List<InstalledArea>> = shared.asStateFlow()
    }

    /** Scan once, lazily. A directory listing is cheap but not free enough to repeat per frame. */
    private fun ensureScanned() {
        if (!scanned) scan()
    }

    /**
     * Delete any level of [areaId] other than [keep]. Called after a switch verifies.
     *
     * Never called before the replacement is in place: losing the old map to a download that then
     * fails would leave the user with nothing, which is the one outcome worth designing against.
     */
    fun prune(areaId: String, keep: AreaLevel): Int {
        var removed = 0
        for (entry in installed) {
            if (entry.area.id == areaId && entry.level.detail != keep.detail) {
                if (entry.file.delete()) removed++
            }
        }
        if (removed > 0) scan()
        return removed
    }

    fun delete(areaId: String): Boolean {
        val entry = installFor(areaId) ?: return false
        val ok = entry.file.delete()
        if (ok) scan()
        return ok
    }

    fun tierFor(position: LatLon?): MapTier {
        ensureScanned()
        if (position == null) {
            return installed.firstOrNull()?.let { MapTier.Available(it) }
                ?: MapTier.NoDataHere(null)
        }
        // Deterministic, not list order. Where two areas overlap — which they do across the
        // whole Atlantic coast band, 20.8N to 27.3N — this used to serve whichever appeared
        // first in the catalogue, i.e. the order the constants happened to be written in.
        val servingId = AreaChoice.serving(position, installed.map { it.candidate(position) })
        installed.firstOrNull { it.area.id == servingId }?.let {
            return MapTier.Available(it)
        }
        val suggested = RegionCatalogue.covering(position)
        return if (suggested == null) MapTier.ArrowOnly else MapTier.NoDataHere(suggested)
    }

}

/**
 * What is installed, observable, process-wide.
 *
 * A top-level val rather than a member so that the UI can collect it without holding a
 * [RegionIndex]. Any instance's [RegionIndex.scan] updates it, which is what makes a finished
 * download put a map on screen without a restart.
 */
val installedAreasFlow: StateFlow<List<InstalledArea>> get() = RegionIndex.sharedFlow

/**
 * Free space on the volume holding [directory].
 *
 * One implementation, in the module that owns the storage concern. There were briefly two — a
 * private copy in RegionDownloader and a top-level one in the UI layer — which is how the same
 * value ends up computed two subtly different ways. The static sweep flagged the collision.
 */
fun freeSpaceOn(directory: java.io.File): Long = try {
    android.os.StatFs(directory.path).availableBytes
} catch (_: IllegalArgumentException) {
    // The directory may not exist yet on first run; the parent is what has the volume.
    try {
        android.os.StatFs(directory.parentFile?.path ?: directory.path).availableBytes
    } catch (_: IllegalArgumentException) {
        0L
    }
}
