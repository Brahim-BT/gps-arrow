package dev.gpsarrow.maps

/**
 * The two regions this build knows about.
 *
 * Hard-coded on purpose. A remote catalogue is a second thing that can be unreachable, and the
 * deployment is fixed: Morocco and Mauritania. Two constants cannot fail to load. If the region
 * list ever needs to change without an app update, this becomes a cached JSON fetch and
 * `RegionIndex.setCatalogue` already takes the parsed result — but that day is not today.
 *
 * ## The numbers below are estimates until the files exist
 *
 * [RegionSummary.bytes] is modelled (MAP_RESEARCH.md 1), not measured, and it is used for two
 * things: the size shown before downloading, and the free-space check. Both tolerate being a bit
 * wrong. It is **not** used to decide whether a download is complete — that comes from the
 * server's `Content-Length`, which is the only authority on how long the file actually is.
 *
 * Once the extracts are built and uploaded (REGION_FILES.md), replace these with the real
 * `ls -l` figures and the real SHA-256 hashes.
 */
object RegionCatalogue {

    /**
     * The release tag holding the map files.
     *
     * Separate from the app's own version tags so that re-cutting the app does not imply
     * re-uploading 218 MB of maps, and so the map files have a stable URL across app releases.
     */
    const val RELEASE_TAG = "maps-v1"

    private const val BASE =
        "https://github.com/Brahim-BT/gps-arrow/releases/download/$RELEASE_TAG"

    /**
     * Morocco including Western Sahara.
     *
     * The bounding box is deliberately the whole territory the app will be used in rather than
     * any statement about borders: a navigation aid that stops working at a disputed line is
     * useless to the person holding it. The Protomaps basemap carries the OSM `disputed` flag on
     * boundary features and the style can render them as such.
     */
    val MOROCCO = RegionSummary(
        id = "morocco",
        name = "Morocco",
        parentId = null,
        bbox = BoundingBox(west = -17.10, south = 20.77, east = -0.99, north = 35.95),
        maxZoom = 14,
        bytes = 183_000_000L,
        url = "$BASE/morocco-z14.pmtiles",
        checksum = null,
    )

    val MAURITANIA = RegionSummary(
        id = "mauritania",
        name = "Mauritania",
        parentId = null,
        bbox = BoundingBox(west = -17.07, south = 14.72, east = -4.83, north = 27.30),
        maxZoom = 14,
        bytes = 35_000_000L,
        url = "$BASE/mauritania-z14.pmtiles",
        checksum = null,
    )

    val ALL: List<RegionSummary> = listOf(MOROCCO, MAURITANIA)

    /**
     * Two separate files rather than one combined extract, so that someone working only in
     * Mauritania downloads 35 MB instead of 218 MB. On a metered rural connection that is the
     * difference between a few minutes and half an hour, and it is the single most useful
     * decision in this file.
     */
    fun byId(id: String): RegionSummary? = ALL.firstOrNull { it.id == id }
}
