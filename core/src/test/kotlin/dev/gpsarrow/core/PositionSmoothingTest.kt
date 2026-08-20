package dev.gpsarrow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.random.Random

/**
 * The displayed-marker smoothing.
 *
 * The invariant is the point of this file: **the smoothed dot never sits further from the raw fix
 * than 0.7 of its accuracy radius**, at any speed. Everything else is a property of the filter;
 * that one is the honesty argument, so it is asserted over a simulated track rather than at a
 * couple of hand-picked instants.
 */
class PositionSmoothingTest {

    private val lat0 = 24.0
    private val metresPerDegLat = 111_132.0

    private fun fixAt(northMetres: Double, accuracy: Float = 10f) = Fix(
        position = LatLon(lat0 + northMetres / metresPerDegLat, -15.0),
        accuracyMeters = accuracy,
        altitudeMeters = null,
        speedMps = null,
        bearingDeg = null,
        elapsedMillis = 0L,
    )

    // ---- snapping ----------------------------------------------------------------------

    @Test
    fun `the first fix snaps, with nothing to filter from`() {
        val s = PositionSmoothing.update(null, fixAt(0.0), 1_000L)
        assertTrue(s.snapped)
        assertEquals(lat0, s.position.lat, 1e-12)
    }

    /**
     * The case that would look worst: a stale previous position would drag the dot across the
     * screen from where the user used to be, which reads as a bug rather than as smoothing.
     */
    @Test
    fun `a gap longer than the stale threshold snaps`() {
        val first = PositionSmoothing.update(null, fixAt(0.0), 0L)
        val after = PositionSmoothing.update(
            first, fixAt(5_000.0), NavigationState.STALE_AFTER_MS + 1,
        )
        assertTrue("a long gap must snap, not glide", after.snapped)
        assertEquals(fixAt(5_000.0).position.lat, after.position.lat, 1e-9)
    }

    @Test
    fun `a gap exactly at the threshold still filters`() {
        val first = PositionSmoothing.update(null, fixAt(0.0), 0L)
        val after = PositionSmoothing.update(first, fixAt(3.0), NavigationState.STALE_AFTER_MS)
        assertFalse(after.snapped)
    }

    /** A clock that has gone backwards gives an unknown elapsed time, not a small one. */
    @Test
    fun `a backwards clock snaps`() {
        val first = PositionSmoothing.update(null, fixAt(0.0), 10_000L)
        val after = PositionSmoothing.update(first, fixAt(3.0), 9_000L)
        assertTrue(after.snapped)
    }

    // ---- rate independence -------------------------------------------------------------

    /**
     * Two 500 ms steps must land where one 1000 ms step lands. Assuming a sample rate is the
     * second recurring bug in this project; this is the test that would have caught it.
     */
    @Test
    fun `the filter is rate independent`() {
        // Accuracy deliberately huge so the clamp cannot engage. The first version of this test
        // used a 10 m target with 10 m accuracy, and the clamp fired half way through the
        // two-step run — which broke the equivalence and looked like a rate-dependence bug. It
        // was the clamp working correctly; the test was asserting the wrong thing.
        val target = fixAt(10.0, accuracy = 100_000f)
        val start = PositionSmoothing.update(null, fixAt(0.0, accuracy = 100_000f), 0L)

        val oneStep = PositionSmoothing.update(start, target, 1_000L)
        val half = PositionSmoothing.update(start, target, 500L)
        val twoSteps = PositionSmoothing.update(half, target, 1_000L)

        assertEquals(
            "one 1000ms step and two 500ms steps must agree",
            oneStep.position.lat, twoSteps.position.lat, 1e-12,
        )
    }

    @Test
    fun `alpha follows the time constant`() {
        val start = PositionSmoothing.update(null, fixAt(0.0), 0L)
        val target = fixAt(100.0, accuracy = 1_000f)   // huge accuracy so the clamp cannot engage
        val dt = PositionSmoothing.TIME_CONSTANT_MILLIS
        val moved = PositionSmoothing.update(start, target, dt)

        val expectedAlpha = 1.0 - exp(-1.0)            // dt == tau
        val expectedLat = lat0 + expectedAlpha * (target.position.lat - lat0)
        assertEquals(expectedLat, moved.position.lat, 1e-12)
    }

    // ---- the invariant -----------------------------------------------------------------

    @Test
    fun `the clamp pulls a distant candidate onto the disc`() {
        val fix = fixAt(0.0, accuracy = 10f)
        val far = LatLon(lat0 + 100.0 / metresPerDegLat, -15.0)   // 100 m north
        val clamped = PositionSmoothing.clampToAccuracy(far, fix)
        val d = Geo.distanceMeters(fix.position, clamped)
        assertEquals(PositionSmoothing.CLAMP_FRACTION * 10.0, d, 0.05)
    }

    @Test
    fun `a candidate already inside the disc is left alone`() {
        val fix = fixAt(0.0, accuracy = 10f)
        val near = LatLon(lat0 + 2.0 / metresPerDegLat, -15.0)    // 2 m north, inside 7 m
        val out = PositionSmoothing.clampToAccuracy(near, fix)
        assertEquals(near.lat, out.lat, 1e-12)
    }

    @Test
    fun `no usable accuracy means no smoothing rather than an invented radius`() {
        val fix = fixAt(0.0, accuracy = 0f)
        val far = LatLon(lat0 + 100.0 / metresPerDegLat, -15.0)
        assertEquals(fix.position.lat, PositionSmoothing.clampToAccuracy(far, fix).lat, 1e-12)
    }

    /**
     * The honesty condition, over a simulated track rather than at one instant.
     *
     * Walking and driving both, because the steady-state lag of an exponential filter is `v x tau`
     * — proportional to speed — so a fixed time constant satisfies this at walking pace and fails
     * badly at driving speed. Only the clamp makes it hold at both.
     */
    @Test
    fun `the dot never leaves the accuracy circle, at any speed`() {
        for (speed in listOf(0.0, 1.4, 3.0, 25.0)) {
            val random = Random(11)
            var smoothed: SmoothedPosition? = null
            var truth = 0.0
            var worst = 0.0
            for (i in 1..300) {
                truth += speed
                val noise = (random.nextDouble() - 0.5) * 20.0     // +/- 10 m
                val fix = fixAt(truth + noise, accuracy = 10f)
                smoothed = PositionSmoothing.update(smoothed, fix, i * 1_000L)
                val d = Geo.distanceMeters(fix.position, smoothed.position)
                if (d > worst) worst = d
            }
            val limit = PositionSmoothing.CLAMP_FRACTION * 10.0
            assertTrue(
                "at $speed m/s the dot reached ${"%.2f".format(worst)} m from the fix, " +
                    "limit is ${"%.2f".format(limit)} m",
                worst <= limit + 0.05,
            )
        }
    }

    /** And it must actually smooth, or the whole exercise is lag for nothing. */
    @Test
    fun `stationary jitter is materially reduced`() {
        val random = Random(3)
        var smoothed: SmoothedPosition? = null
        var rawSpread = 0.0
        var shownSpread = 0.0
        var previousShown: LatLon? = null
        var previousRaw: LatLon? = null
        for (i in 1..400) {
            val noise = (random.nextDouble() - 0.5) * 20.0
            val fix = fixAt(noise, accuracy = 10f)
            smoothed = PositionSmoothing.update(smoothed, fix, i * 1_000L)
            if (i > 200) {
                previousRaw?.let { rawSpread += Geo.distanceMeters(it, fix.position) }
                previousShown?.let { shownSpread += Geo.distanceMeters(it, smoothed.position) }
            }
            previousRaw = fix.position
            previousShown = smoothed.position
        }
        assertTrue(
            "shown movement $shownSpread must be well under raw $rawSpread",
            shownSpread < rawSpread * 0.6,
        )
    }
}
