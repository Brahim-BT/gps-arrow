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

    // Device report: turning on the spot moved the needle slowly, then it stopped short of the
    // true bearing. These pin down the two properties that symptom violates.

    @Test
    fun `time based smoothing converges and does not stall short of the target`() {
        val s = CircularSmoother()
        val dt = 1.0 / 50.0
        val tau = 0.08
        s.update(0.0, 0.0, tau)
        var out = 0.0
        // One second of samples is more than 12 time constants; it must effectively arrive.
        repeat(50) { out = s.update(90.0, dt, tau) }
        assertEquals(90.0, out, 0.5)
    }

    @Test
    fun `response is independent of the delivery rate`() {
        val tau = 0.08
        // Steps are passed explicitly. The previous version derived them as
        // (seconds * hz).toInt(), which truncated 0.16 * 10 to a single step — so it compared
        // 0.16 s of filtering against 0.10 s and failed on a difference it had created itself.
        fun after(totalSeconds: Double, steps: Int): Double {
            val s = CircularSmoother()
            val dt = totalSeconds / steps
            s.update(0.0, 0.0, tau)
            var out = 0.0
            repeat(steps) { out = s.update(90.0, dt, tau) }
            return out
        }
        // With alpha = 1 - exp(-dt/tau) the residual after n steps is exactly exp(-T/tau),
        // independent of how T was subdivided, so equal elapsed time gives an equal angle to
        // within floating-point noise. A fixed per-sample alpha would not.
        val fast = after(0.2, 10)   // 50 Hz
        val slow = after(0.2, 2)    // 10 Hz
        assertEquals(fast, slow, 1e-9)
        assertTrue("should have moved most of the way, was $fast", fast > 80.0)
    }

    @Test
    fun `smoothing reaches most of the way within one time constant`() {
        val s = CircularSmoother()
        val tau = 0.08
        s.update(0.0, 0.0, tau)
        var out = 0.0
        repeat(4) { out = s.update(90.0, tau / 4, tau) }
        assertTrue("after one tau the needle should be past halfway, was $out", out > 45.0)
    }

    @Test
    fun `distance formatting degrades precision sensibly`() {
        // The unit word lives in the UI layer now, so these assert the number and the scale.
        Format.distance(123.0).let {
            assertEquals("120", it.value)
            assertEquals(LengthUnit.METRES, it.unit)
        }
        Format.distance(1_234.0).let {
            assertEquals("1.2", it.value)
            assertEquals(LengthUnit.KILOMETRES, it.unit)
        }
        Format.distance(123_456.0).let {
            assertEquals("123", it.value)
            assertEquals(LengthUnit.KILOMETRES, it.unit)
        }
    }

    @Test
    fun `compass points`() {
        // Index into the sixteen-point rose: N = 0, clockwise. The abbreviations are a
        // translated string array, so :core only knows which of the sixteen it is.
        assertEquals(0, Format.compassPointIndex(0.0))
        assertEquals(0, Format.compassPointIndex(359.0))
        assertEquals(2, Format.compassPointIndex(45.0))
        assertEquals(8, Format.compassPointIndex(180.0))
        assertEquals(14, Format.compassPointIndex(315.0))
    }
}
