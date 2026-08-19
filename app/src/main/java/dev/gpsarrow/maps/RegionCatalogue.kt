package dev.gpsarrow.maps

import dev.gpsarrow.R

/**
 * The offline areas this build knows about, and the detail levels each one offers.
 *
 * Hard-coded on purpose. A remote catalogue is a second thing that can be unreachable, and the
 * deployment is fixed. Two constants cannot fail to load.
 *
 * ## Naming
 *
 * Nothing here carries a country or territory name that a user can see. Areas are labelled by the
 * places they cover ([MapArea.placesRes]), which is neutral about a disputed frontier and is also
 * simply more useful — someone recognises their own city faster than they parse a country label,
 * and a place list answers "does this cover me?" directly. The `id` values below are internal:
 * they name files on disk and URLs, and never reach the screen.
 *
 * ## Why these levels
 *
 * Measured, not chosen by feel. Reading the basemap generator's own source, `pm:minzoom` puts
 * `highway=track` — pistes and desert tracks — at **12**, so the smaller download keeps the
 * network that matters most here. Level 13 adds isolated dwellings, seasonal watercourses
 * (`waterway=stream`) and footpaths. Level 14 adds sidewalks, crossings and tram lines and
 * nothing else, which is why it is not offered: it doubles the file for content this deployment
 * has no use for.
 *
 * The smaller area offers one level only. At 43 MB it is already below the larger area's
 * *smaller* option, so a chooser there would be asking a question with no meaningful answer.
 */
object RegionCatalogue {

    /**
     * The release tag holding the map files. Separate from the app's version tags so that cutting
     * a new app release does not imply re-uploading the maps.
     */
    const val RELEASE_TAG = "maps-v1"

    /**
     * Which Protomaps daily planet build these extracts were cut from.
     *
     * The checksums below are only meaningful against this build: Protomaps publish a new planet
     * most days, and re-cutting from a different one produces different bytes even with identical
     * commands and bounding boxes.
     */
    const val SOURCE_BUILD = "20260819.pmtiles"

    private const val BASE =
        "https://github.com/Brahim-BT/gps-arrow/releases/download/$RELEASE_TAG"

    /**
     * Bounding boxes are read off the built archives' own headers, never chosen independently.
     *
     * This is the only safe direction for the dependency to point: the box drives
     * [BoundingBox.contains], which decides whether the app claims to have a map for where the
     * user is standing. A box wider than the archive promises a map and shows a void. Once a
     * level is installed, [dev.gpsarrow.core.PmtilesHeader.covers] reads the bounds out of the
     * file itself, which cannot drift at all.
     */
    private val LARGER_BOX = BoundingBox(west = -17.10, south = 20.77, east = -0.99, north = 35.95)
    private val SMALLER_BOX = BoundingBox(west = -17.07, south = 14.72, east = -4.80, north = 27.30)

    val LARGER = MapArea(
        id = "morocco",
        placesRes = R.string.area_larger_places,
        bbox = LARGER_BOX,
        levels = listOf(
            AreaLevel(
                detail = Detail.STANDARD,
                maxZoom = 12,
                bytes = 61_851_096L,
                url = "$BASE/morocco-z12.pmtiles",
                sha256 = "8e8e7bb8ca2f09f73fa5c03580b08eb0826357f2b23357db4a3733b22255d521",
            ),
            AreaLevel(
                detail = Detail.DETAILED,
                maxZoom = 13,
                bytes = 133_209_973L,
                url = "$BASE/morocco-z13.pmtiles",
                sha256 = "7117463095248ad649e1dd8ebccec8cab0b6352149790bd24aba7f0f932e2158",
            ),
        ),
    )

    val SMALLER = MapArea(
        id = "mauritania",
        placesRes = R.string.area_smaller_places,
        bbox = SMALLER_BOX,
        levels = listOf(
            AreaLevel(
                detail = Detail.DETAILED,
                maxZoom = 13,
                bytes = 42_919_292L,
                url = "$BASE/mauritania-z13.pmtiles",
                sha256 = "f5483e31f9a6151cfa6bb2f905e277596bf504d54a798a5fc9afd018f2ad59ff",
            ),
        ),
    )

    val ALL: List<MapArea> = listOf(LARGER, SMALLER)

    fun byId(id: String): MapArea? = ALL.firstOrNull { it.id == id }

    /** The area whose bounds contain [p], for "you need this one" prompts. */
    fun covering(p: dev.gpsarrow.core.LatLon): MapArea? =
        ALL.firstOrNull { it.bbox.contains(p) }
}

/**
 * One downloadable area, offered at one or more detail levels.
 *
 * [levels] is ordered smallest first. An area with a single entry must not render a chooser —
 * see [hasChoice].
 */
data class MapArea(
    val id: String,
    val placesRes: Int,
    val bbox: BoundingBox,
    val levels: List<AreaLevel>,
) {
    val hasChoice: Boolean get() = levels.size > 1

    fun level(detail: Detail): AreaLevel? = levels.firstOrNull { it.detail == detail }
}

/**
 * One detail level of one area: a single downloadable file.
 *
 * [sha256] is not nullable. A level with no checksum is one whose corruption we could not detect,
 * and there is no reason to ship one — the hashes are known at build time. Making the field
 * required means the compiler refuses to let a future level be added without one.
 */
data class AreaLevel(
    val detail: Detail,
    val maxZoom: Int,
    val bytes: Long,
    val url: String,
    val sha256: String,
) {
    /** `morocco-z12` — the on-disk stem and the release asset name. Never shown to the user. */
    fun fileStem(areaId: String): String = "$areaId-z$maxZoom"
}

/**
 * Detail levels, described to the user by content rather than by zoom number.
 *
 * A user cannot act on "z12". They can act on "roads, tracks, towns and villages" versus "adds
 * footpaths, isolated buildings and seasonal watercourses", which is what these two actually
 * differ by in the basemap schema.
 */
enum class Detail(val labelRes: Int, val summaryRes: Int) {
    STANDARD(R.string.level_standard, R.string.level_standard_summary),
    DETAILED(R.string.level_detailed, R.string.level_detailed_summary),
}
