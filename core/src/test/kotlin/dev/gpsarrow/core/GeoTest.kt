package dev.gpsarrow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeoTest {

    private val paris = LatLon(48.8566, 2.3522)
    private val london = LatLon(51.5074, -0.1278)

    @Test
    fun `one degree of latitude at the equator is about 111 km`() {
        val d = Geo.distanceMeters(LatLon(0.0, 0.0), LatLon(1.0, 0.0))
        assertEquals(111_195.0, d, 200.0)
    }

    @Test
    fun `london to paris is about 343 km`() {
        val d = Geo.distanceMeters(london, paris)
        assertEquals(343_000.0, d, 3_000.0)
    }

    @Test
    fun `antipodal points are half the circumference apart and do not blow up`() {
        val d = Geo.distanceMeters(LatLon(0.0, 0.0), LatLon(0.0, 180.0))
        assertEquals(Math.PI * EARTH_RADIUS_M, d, 1.0)
    }

    @Test
    fun `bearing due north is zero and due east is ninety`() {
        assertEquals(0.0, Geo.initialBearingDegrees(LatLon(0.0, 0.0), LatLon(1.0, 0.0)), 1e-6)
        assertEquals(90.0, Geo.initialBearingDegrees(LatLon(0.0, 0.0), LatLon(0.0, 1.0)), 1e-6)
        assertEquals(180.0, Geo.initialBearingDegrees(LatLon(0.0, 0.0), LatLon(-1.0, 0.0)), 1e-6)
        assertEquals(270.0, Geo.initialBearingDegrees(LatLon(0.0, 0.0), LatLon(0.0, -1.0)), 1e-6)
    }

    @Test
    fun `bearing london to paris is south east ish`() {
        val b = Geo.initialBearingDegrees(london, paris)
        assertTrue("expected ~148 deg, got $b", abs(b - 148.0) < 3.0)
    }

    @Test
    fun `destination then distance round trips`() {
        for (bearing in 0 until 360 step 17) {
            for (dist in listOf(10.0, 1_000.0, 100_000.0, 3_000_000.0)) {
                val p = Geo.destination(paris, bearing.toDouble(), dist)
                assertEquals(dist, Geo.distanceMeters(paris, p), dist * 1e-6 + 0.01)
                assertEquals(
                    bearing.toDouble(),
                    Geo.initialBearingDegrees(paris, p),
                    1e-6,
                )
            }
        }
    }

    @Test
    fun `angle delta takes the short way round the wrap`() {
        assertEquals(2.0, Geo.angleDeltaDegrees(359.0, 1.0), 1e-9)
        assertEquals(-2.0, Geo.angleDeltaDegrees(1.0, 359.0), 1e-9)
        assertEquals(180.0, Geo.angleDeltaDegrees(0.0, 180.0), 1e-9)
        assertEquals(-90.0, Geo.angleDeltaDegrees(0.0, 270.0), 1e-9)
    }

    @Test
    fun `circular smoother does not swing the long way at the 360 wrap`() {
        val s = CircularSmoother(alpha = 0.5)
        s.update(350.0)
        val out = s.update(10.0)
        // Naive averaging would give 180. The circular mean gives 0.
        assertTrue("smoothed to $out", out > 350.0 || out < 10.0)
    }

    @Test
    fun `distance formatting degrades precision sensibly`() {
        assertEquals("120 m", Format.distance(123.0))
        assertEquals("1.2 km", Format.distance(1_234.0))
        assertEquals("123 km", Format.distance(123_456.0))
    }

    @Test
    fun `compass points`() {
        assertEquals("N", Format.compassPoint(0.0))
        assertEquals("N", Format.compassPoint(359.0))
        assertEquals("NE", Format.compassPoint(45.0))
        assertEquals("S", Format.compassPoint(180.0))
        assertEquals("NW", Format.compassPoint(315.0))
    }
}
