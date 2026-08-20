package dev.gpsarrow.core

import kotlin.math.cos

/**
 * The geometry behind the position dot's accuracy circle.
 *
 * MapLibre's `circle-radius` is measured in **pixels**, so a circle drawn at a fixed radius is a
 * different number of metres at every zoom — a claim about accuracy that is wrong everywhere
 * except by coincidence. An accuracy circle is a real assertion about how well the device knows
 * where it is, and drawing it at the wrong size is the same category of error as a heading that
 * looks precise and is not.
 *
 * Two things vary: zoom, and latitude. Zoom is handled in the style by exponential-base-2
 * interpolation, which doubles the radius per level exactly as Web Mercator requires. Latitude is
 * handled *here*, by computing the radius at zoom 0 for the fix's own latitude and shipping it as
 * a feature property. That keeps the style expression simple and, more usefully, keeps the
 * trigonometry somewhere it can be unit-tested.
 */
object MapMarker {

    /**
     * Web Mercator ground resolution at zoom 0, latitude 0, for 256-pixel tiles: metres per pixel.
     * Equatorial circumference 40 075 016.686 m over 256 px.
     */
    private const val EQUATOR_METRES_PER_PIXEL_256 = 156_543.03392

    /** MapLibre's vector tiles are 512 px, which halves the metres each pixel covers. */
    private const val TILE_PIXELS = 512.0

    /**
     * Radius in pixels at zoom 0 for a circle of [accuracyMeters] at [latitudeDeg].
     *
     * The style then draws `r0 * 2^zoom`. Values are tiny — a 12 m circle is 0.00017 px at zoom 0
     * — which is correct and not a sign of an error: it becomes 0.7 px at zoom 12 and 11 px at
     * zoom 16. A sub-pixel circle at low zoom is an honest rendering of a 12 m claim.
     */
    fun accuracyRadiusAtZoomZero(accuracyMeters: Double, latitudeDeg: Double): Double {
        if (!accuracyMeters.isFinite() || accuracyMeters <= 0.0) return 0.0
        val metresPerPixel =
            EQUATOR_METRES_PER_PIXEL_256 * cos(Math.toRadians(latitudeDeg)) * (256.0 / TILE_PIXELS)
        if (metresPerPixel <= 0.0) return 0.0
        return accuracyMeters / metresPerPixel
    }

    /** What the style would draw at [zoom], for checking against the direct computation. */
    fun radiusPixelsAt(accuracyMeters: Double, latitudeDeg: Double, zoom: Int): Double =
        accuracyRadiusAtZoomZero(accuracyMeters, latitudeDeg) * Math.pow(2.0, zoom.toDouble())

    /**
     * Whether the position dot should be drawn at all.
     *
     * **No dot for a stale fix.** The position band above the map can say "this is where you were,
     * not where you are"; the map has no room for that caveat, and an uncaveated dot reads as
     * "you are here". A stale dot looks identical to a fresh one, which makes it exactly the kind
     * of thing this app does not display: something that looks like information and is not.
     *
     * No dot for no fix either, obviously — and that must be a real absence, not a dot at 0,0.
     */
    fun shouldDrawPosition(fixAgeMillis: Long?): Boolean {
        val age = fixAgeMillis ?: return false
        return age in 0..NavigationState.STALE_AFTER_MS
    }
}
