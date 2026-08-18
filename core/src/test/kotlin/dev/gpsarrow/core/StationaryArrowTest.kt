package dev.gpsarrow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The phone that is not moving.
 *
 * Device testing found the needle twitching once a second while the user stood still, which is
 * the GPS fix rate rather than the ~50 Hz sensor rate — so something on the location path was
 * reaching the arrow. Two things could: the heading source, and the bearing to the destination.
 * These tests pin both to "a stationary fix must not move the needle".
 *
 * Everything here is derived with [Geo], never typed out by hand: the only literals are the
 * inputs (a bearing and a distance in metres), and the expectations are computed from them.
 */
class StationaryArrowTest {

    private val here = LatLon(48.8584, 2.2945)

    /**
     * A minute of 1 Hz fixes from a receiver sitting still.
     *
     * Speed is bounded below [HeadingArbiter.STATIONARY_MPS] by construction, and the course
     * is deliberate nonsense sweeping the whole circle — which is what a real receiver reports
     * when there is no motion for it to derive a course from.
     */
    private fun stationaryFixes(count: Int = 60, seed: Int = 20260818): List<Fix> {
        val noise = Random(seed)
        return (0 until count).map { i ->
            Fix(
                position = here,
                accuracyMeters = 10f,
                altitudeMeters = 40.0,
                speedMps = noise.nextDouble(0.0, 0.45).toFloat(),
                bearingDeg = noise.nextDouble(0.0, 360.0).toFloat(),
                elapsedMillis = i * 1_000L,
            )
        }
    }

    // ------------------------------------------------------------------ heading arbitration

    @Test
    fun `a minute of stationary fixes never moves the source off the compass`() {
        val compass = 137.5
        var source = HeadingSource.COMPASS
        val seen = mutableListOf<Pair<Double?, HeadingSource>>()

        // previousSource is fed forward exactly as the ViewModel does, so the hysteresis
        // branch sees real history rather than a constant.
        stationaryFixes().forEach { fix ->
            val result = HeadingArbiter.select(fix, compass, true, source)
            source = result.second
            seen += result
        }

        assertEquals(listOf(HeadingSource.COMPASS), seen.map { it.second }.distinct())
        // ...and the value handed on is the compass reading itself, untouched by the fix.
        assertEquals(listOf<Double?>(compass), seen.map { it.first }.distinct())
    }

    @Test
    fun `a stationary receiver's course never reaches a phone with no compass`() {
        var source = HeadingSource.NONE
        val seen = mutableListOf<Pair<Double?, HeadingSource>>()

        stationaryFixes().forEach { fix ->
            val result = HeadingArbiter.select(fix, null, false, source)
            source = result.second
            seen += result
        }

        // Before the stationary gate this fell through to "course over ground is better than
        // nothing" and handed the needle a new random angle on every fix. No heading is the
        // honest answer, and the arrow screen already has copy that says so.
        assertEquals(listOf(HeadingSource.NONE), seen.map { it.second }.distinct())
        assertEquals(listOf<Double?>(null), seen.map { it.first }.distinct())
    }

    @Test
    fun `course over ground comes back the moment the phone actually moves`() {
        val moving = Fix(here, 10f, 40.0, 3.0f, 42f, 0L)
        val (heading, source) = HeadingArbiter.select(moving, 137.5, true, HeadingSource.COMPASS)
        assertEquals(HeadingSource.GPS_COURSE, source)
        assertEquals(42.0, heading!!, 1e-9)
    }

    @Test
    fun `the stationary gate sits below the hysteresis band, not inside it`() {
        // Just under the gate: no course at all, whatever was in charge before.
        val below = Fix(here, 10f, 40.0, HeadingArbiter.STATIONARY_MPS - 0.01f, 42f, 0L)
        assertEquals(
            HeadingSource.NONE,
            HeadingArbiter.select(below, null, true, HeadingSource.GPS_COURSE).second,
        )
        // Just over it: course exists again, and with no compass it is the only heading left,
        // which is the walking-pace behaviour the hysteresis band is there to manage.
        val above = Fix(here, 10f, 40.0, HeadingArbiter.STATIONARY_MPS + 0.01f, 42f, 0L)
        assertEquals(
            HeadingSource.GPS_COURSE,
            HeadingArbiter.select(above, null, true, HeadingSource.GPS_COURSE).second,
        )
        // But with a compass present, walking pace still belongs to the compass until the
        // takeover speed — the gate must not have widened the GPS_COURSE window.
        assertEquals(
            HeadingSource.COMPASS,
            HeadingArbiter.select(above, 137.5, true, HeadingSource.COMPASS).second,
        )
    }

    @Test
    fun `a fix with a bearing but no speed at all is treated as stationary`() {
        val noSpeed = Fix(here, 10f, 40.0, null, 42f, 0L)
        val (heading, source) = HeadingArbiter.select(noSpeed, null, true, HeadingSource.NONE)
        assertNull(heading)
        assertEquals(HeadingSource.NONE, source)
    }

    // ------------------------------------------------------------------ bearing to target

    /** Six consecutive fixes 4 m out in different directions: a stationary receiver at 1 Hz. */
    private val wander: List<LatLon> = (0 until 6).map { Geo.destination(here, it * 60.0, 4.0) }

    private fun stateAt(position: LatLon, target: Destination, accuracy: Float = 10f) =
        NavigationState(
            fix = Fix(position, accuracy, 40.0, 0.1f, null, 0L),
            destination = target,
            headingDeg = 90.0,
        )

    @Test
    fun `a point saved where you stand stops the needle chasing the fix`() {
        val car = Destination("1", "Car", here)
        val states = wander.map { stateAt(it, car) }

        assertEquals(listOf(ArrowMode.ARRIVED), states.map { it.arrowMode }.distinct())
        // Phone faces east, so the needle shows north at 270 and nothing else. Every fix in
        // the sequence produces the same angle, which is the whole point.
        assertEquals(listOf(Geo.normalizeDegrees(-90.0)), states.map { it.arrowDeg!! }.distinct())
    }

    @Test
    fun `a destination far enough away still tracks the fix`() {
        // Guards against the test above passing for the wrong reason. At 200 m the bearing is
        // geometry rather than noise, so the same wander must still move the needle.
        val far = Destination("2", "Trailhead", Geo.destination(here, 0.0, 200.0))
        val states = wander.map { stateAt(it, far) }

        assertEquals(listOf(ArrowMode.TARGET), states.map { it.arrowMode }.distinct())
        val angles = states.map { it.arrowDeg!! }
        // ~2 degrees across a 8 m-wide ring at 200 m; asserted as a spread rather than a
        // distinct count because two of the six sit on the destination's own meridian and
        // share a bearing exactly.
        val spread = angles.maxOrNull()!! - angles.minOrNull()!!
        assertTrue("needle barely moved: spread was $spread deg", spread > 1.5)
    }

    @Test
    fun `arrival radius is the fix's own accuracy once that is worse than the floor`() {
        val good = 5f
        val bad = 25f
        fun target(meters: Double) = Destination("t", "t", Geo.destination(here, 90.0, meters))

        // Floor case: a good fix uses ARRIVAL_RADIUS_M, so 11 m is "here" and 13 m is not.
        assertEquals(ArrowMode.ARRIVED, stateAt(here, target(11.0), good).arrowMode)
        assertEquals(ArrowMode.TARGET, stateAt(here, target(13.0), good).arrowMode)

        // Honest-but-poor fix: it cannot resolve 20 m, and says so.
        assertEquals(ArrowMode.ARRIVED, stateAt(here, target(20.0), bad).arrowMode)
        assertEquals(ArrowMode.TARGET, stateAt(here, target(30.0), bad).arrowMode)
    }

    @Test
    fun `arriving does not blank the needle`() {
        val state = stateAt(here, Destination("1", "Car", here))
        // ARRIVED degrades to a compass rather than to nothing: the app is a compass first.
        assertEquals(ArrowMode.ARRIVED, state.arrowMode)
        assertTrue(state.arrowDeg != null)
        assertTrue(state.hasArrived)
        // The distance readout is still live — only the *direction* is withheld.
        assertTrue(state.distanceMeters!! < 1e-6)
    }

    @Test
    fun `nothing has arrived without both a fix and a destination`() {
        assertTrue(!NavigationState().hasArrived)
        assertTrue(!NavigationState(destination = Destination("1", "Car", here)).hasArrived)
        assertTrue(!NavigationState(fix = Fix(here, 5f, null, null, null, 0L)).hasArrived)
    }
}
