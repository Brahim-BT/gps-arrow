package dev.gpsarrow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accuracy circle's geometry.
 *
 * Expectations here were computed from the Web Mercator ground-resolution formula independently
 * of the implementation, not read back out of it — otherwise the test would agree with any
 * scaling error the code has.
 */
class MapMarkerTest {

    /** metres per pixel at 512px tiles = 156543.03392 * cos(lat) / 2^z * (256/512) */
    private fun metresPerPixel(lat: Double, zoom: Int): Double =
        156_543.03392 * Math.cos(Math.toRadians(lat)) / Math.pow(2.0, zoom.toDouble()) * 0.5

    @Test
    fun `radius doubles with every zoom level`() {
        val r12 = MapMarker.radiusPixelsAt(12.0f, 24.0, 12)
        val r13 = MapMarker.radiusPixelsAt(12.0f, 24.0, 13)
        assertEquals("exponential base 2 is what the style interpolates with", 2.0, r13 / r12, 1e-9)
    }

    @Test
    fun `radius matches the ground resolution at several zooms and latitudes`() {
        val cases = listOf(
            Triple(12.0f, 24.0, 12), Triple(12.0f, 24.0, 16),
            Triple(25.0f, 33.6, 14), Triple(5.0f, 18.0, 18),
        )
        for ((accuracy, lat, zoom) in cases) {
            val expected = accuracy.toDouble() / metresPerPixel(lat, zoom)
            assertEquals(
                "accuracy $accuracy m at lat $lat, zoom $zoom",
                expected, MapMarker.radiusPixelsAt(accuracy, lat, zoom), 1e-6,
            )
        }
    }

    /**
     * Latitude matters and is easy to leave out. At 33.6°N a circle is ~20% smaller in pixels
     * than the same circle at the equator, because each pixel covers less ground.
     */
    @Test
    fun `latitude changes the radius`() {
        val equator = MapMarker.radiusPixelsAt(100.0f, 0.0, 14)
        val morocco = MapMarker.radiusPixelsAt(100.0f, 33.6, 14)
        assertTrue("higher latitude must give a LARGER pixel radius", morocco > equator)
        assertEquals(1.0 / Math.cos(Math.toRadians(33.6)), morocco / equator, 1e-9)
    }

    /** A sub-pixel circle at low zoom is correct, not a bug — 12 m really is under a pixel. */
    @Test
    fun `a small accuracy is sub-pixel at low zoom and visible at high zoom`() {
        assertTrue(MapMarker.radiusPixelsAt(12.0f, 24.0, 12) < 1.0)
        assertTrue(MapMarker.radiusPixelsAt(12.0f, 24.0, 16) > 10.0)
    }

    @Test
    fun `unknown or nonsense accuracy draws no circle`() {
        assertEquals(0.0, MapMarker.accuracyRadiusAtZoomZero(0.0f, 24.0), 0.0)
        assertEquals(0.0, MapMarker.accuracyRadiusAtZoomZero(-5.0f, 24.0), 0.0)
        assertEquals(0.0, MapMarker.accuracyRadiusAtZoomZero(Float.NaN, 24.0), 0.0)
    }

    // ---- when to draw the dot at all ---------------------------------------------------

    @Test
    fun `no fix means no dot`() {
        assertFalse(MapMarker.shouldDrawPosition(null))
    }

    @Test
    fun `a fresh fix draws`() {
        assertTrue(MapMarker.shouldDrawPosition(0L))
        assertTrue(MapMarker.shouldDrawPosition(NavigationState.STALE_AFTER_MS))
    }

    /**
     * A stale dot looks exactly like a fresh one, and the map has no room to caveat it the way
     * the position band above can. So it is not drawn — the same rule that removed the frozen
     * notification distance and the negative-zero heading.
     */
    @Test
    fun `a stale fix draws nothing`() {
        assertFalse(MapMarker.shouldDrawPosition(NavigationState.STALE_AFTER_MS + 1))
        assertFalse(MapMarker.shouldDrawPosition(60_000L))
    }

    /** A negative age is a clock that has gone backwards; refuse rather than guess. */
    @Test
    fun `a negative age draws nothing`() {
        assertFalse(MapMarker.shouldDrawPosition(-1L))
    }
}
