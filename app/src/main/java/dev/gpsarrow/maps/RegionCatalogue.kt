package dev.gpsarrow.maps

/**
 * The two regions this build knows about.
 *
 * Hard-coded on purpose. A remote catalogue is a second thing that can be unreachable, and the
 * deployment is fixed: Morocco and Mauritania. Two constants cannot fail to load. If the region
 * list ever needs to change without an app update, this becomes a cached JSON fetch and
 * `RegionIndex.setCatalogue` already takes the parsed result — but that day is not today.
 *
 * ## The sizes and hashes are measured, from the built archives
 *
 * Both files have been cut and weighed. The earlier estimates in `MAP_RESEARCH.md` were low, and
 * by more than the stated ±40%:
 *
 * | | estimated | actual | out by |
 * |---|---|---|---|
 * | Morocco | 183 MB | **264 MB** | +44% |
 * | Mauritania | 35 MB | **83 MB** | +137% |
 *
 * The cause was named before the files existed and is worth keeping written down: the estimate
 * scaled each *country's* OSM data volume, but `--bbox` is a rectangle and a country is not. The
 * Mauritania box takes in slices of Western Sahara, Algeria, Mali and Senegal, which is why it is
 * the further off of the two. The lesson is that a bbox extract should be sized from the bbox,
 * not from the country whose name is on it.
 *
 * [RegionSummary.bytes] drives the size shown before downloading and the free-space check. It is
 * **not** what decides a download is complete — that comes from the server's `Content-Length`,
 * the only authority on how long the file actually is.
 */
object RegionCatalogue {

    /**
     * The release tag holding the map files.
     *
     * Separate from the app's own version tags so that re-cutting the app does not imply
     * re-uploading 347 MB of maps, and so the map files have a stable URL across app releases.
     */
    const val RELEASE_TAG = "maps-v1"

    /**
     * Which Protomaps daily planet build these extracts were cut from.
     *
     * Recorded because the checksums below are only meaningful against this build: Protomaps
     * publish a new planet most days, and re-cutting from a different one produces different
     * bytes even with identical commands and bounding boxes. Without this, a future rebuild
     * would silently invalidate every hash here with nothing to point at as the reason.
     *
     * If you re-cut, change this, both [RegionSummary.bytes] and both [RegionSummary.checksum]
     * together — they are one fact recorded in four places.
     */
    const val SOURCE_BUILD = "20260819.pmtiles"

    private const val BASE =
        "https://github.com/Brahim-BT/gps-arrow/releases/download/$RELEASE_TAG"

    /**
     * ## These boxes are read off the built archives, not chosen
     *
     * Each one is the `min_position`/`max_position` in the actual file's PMTiles header. That is
     * deliberate and it is the only safe direction for this to point: the catalogue box drives
     * [BoundingBox.contains], which decides whether the app claims to have a map for where the
     * user is standing. A box wider than the archive means promising a map and showing a void.
     *
     * They were briefly padded ~0.2° past the borders — better coverage in principle, since
     * padding is close to free and clipping loses border towns. But the extracts had already
     * been cut with the unpadded boxes, and a catalogue that overstates what the file contains is
     * worse than a slightly tight box. If these are ever re-cut with padding, update both these
     * values *and* REGION_FILES.md, and re-check with `pmtiles show`.
     *
     * Tightest real margins under these bounds: Nouadhibou sits 0.035° (~3.7 km) inside the
     * Mauritania western edge and Lagouira 0.047° inside Morocco's. Both edges face the Atlantic,
     * so there is no land being clipped — but they are the two to re-check if the boxes change.
     *
     * Longer term the right fix is to stop trusting this field once a region is installed:
     * [PmtilesHeader.covers] reads the bounds out of the file itself, which cannot drift.
     */
    val MOROCCO = RegionSummary(
        id = "morocco",
        name = "Morocco",
        parentId = null,
        bbox = BoundingBox(west = -17.10, south = 20.77, east = -0.99, north = 35.95),
        maxZoom = 14,
        bytes = 263_915_307L,
        url = "$BASE/morocco-z14.pmtiles",
        checksum = "521d5dc075616f1e6e16bbddfeed4fd906baac6310499c70e6688618fba539fc",
    )

    val MAURITANIA = RegionSummary(
        id = "mauritania",
        name = "Mauritania",
        parentId = null,
        bbox = BoundingBox(west = -17.07, south = 14.72, east = -4.80, north = 27.30),
        maxZoom = 14,
        bytes = 83_111_730L,
        url = "$BASE/mauritania-z14.pmtiles",
        checksum = "0f69b393ca0b7c20bf002f12c3ae00a1c2df92a41ca6d4e6dd1b22c2320f915c",
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
