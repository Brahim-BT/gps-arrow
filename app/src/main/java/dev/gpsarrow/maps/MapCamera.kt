package dev.gpsarrow.maps

import dev.gpsarrow.core.LatLon

/**
 * Where the map is looking.
 *
 * Hoisted out of the map composable so it survives leaving the tab, and saveable so it survives
 * process death — the same treatment the half-typed coordinate draft gets, and for the same
 * reason: re-finding your place on a map is work the user already did once.
 */
data class MapCamera(
    val lat: Double,
    val lon: Double,
    val zoom: Double,
    val bearingDeg: Double = 0.0,
) {
    companion object {

        /** Close enough to read street names, far enough to see where you are going. */
        const val ZOOM_AT_POSITION = 14.0

        /**
         * How far below screen centre the dot rides while following and moving, as camera top
         * padding as a fraction of the map's height.
         *
         * Camera padding insets the viewport, and the target is drawn at the centre of what
         * remains: a top inset T puts the dot at half the map height plus T/2. At 0.35 that is
         * about two-thirds of the way down the screen — the standard navigating-map layout,
         * which shows the road ahead instead of a screen half full of where you have just been.
         */
        const val DOT_OFFSET_TOP_FRACTION = 0.35

        /** An area at a glance, when we know the area but not the user. */
        const val ZOOM_AT_AREA = 6.0

        /**
         * The camera to open with.
         *
         * Priority, and the ordering is the whole point:
         *
         *  1. **A remembered camera always wins.** If the user has been here before and moved the
         *     map, putting them back where they were beats any computed default, including one
         *     centred on a fresh fix. Moving the view is an intention; overriding it is rude.
         *  2. **Their position**, if there is a fix. This is the answer nearly always.
         *  3. **The installed area's centre**, if there is no fix yet. Someone who has downloaded
         *     a map of one place should not be shown the Atlantic while GPS acquires.
         *  4. **Null** — nothing installed, so there is no map to aim and the empty state is
         *     showing instead.
         *
         * Before this, the camera defaulted to zoom 0 at the origin, so a user who had just
         * downloaded a map of Morocco was shown the whole globe with Greenland in the middle.
         * The tiles were correct — a bbox extract keeps every low-zoom tile, and one z0 tile
         * covers the planet — but the view was useless.
         */
        fun opening(
            remembered: MapCamera?,
            position: LatLon?,
            installed: InstalledArea?,
        ): MapCamera? = when {
            remembered != null -> remembered
            position != null -> MapCamera(position.lat, position.lon, ZOOM_AT_POSITION)
            installed != null -> installed.area.bbox.center.let {
                MapCamera(it.lat, it.lon, ZOOM_AT_AREA)
            }
            else -> null
        }
    }
}

/**
 * A one-shot instruction to move the camera.
 *
 * Deliberately a command rather than a value the view continuously applies. A value gets
 * re-applied on every recomposition — which at the 1 Hz fix rate meant the map yanked itself back
 * to the position dot once a second, cancelling whatever the user was doing. A command carries an
 * [id] and fires exactly once per new id, so asking for the same place twice still works while
 * nothing fires in between.
 *
 * Null [zoom] or [bearingDeg] mean "leave that alone".
 */
data class CameraCommand(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val zoom: Double? = null,
    val bearingDeg: Double? = null,
)
