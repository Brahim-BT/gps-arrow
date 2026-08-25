package dev.gpsarrow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The estimator that stopped trusting the chip's bearing blindly.
 *
 * Everything is driven along real great-circle legs via [Geo.destination], so the derived
 * course has a geometric ground truth to be compared against — no hand-computed expectations.
 * The frozen-chip scenario reproduces the device finding that motivated this class: a receiver
 * whose position keeps updating while `getBearing()` repeats the same number forever.
 */
class CourseEstimatorTest {

    private val here = LatLon(48.8584, 2.2945)
    private val goodAccuracy = 5f

    private fun fix(
        at: LatLon,
        elapsedMillis: Long,
        bearingDeg: Float?,
        accuracy: Float = goodAccuracy,
        speed: Float = 8f,
    ) = Fix(at, accuracy, 40.0, speed, bearingDeg, elapsedMillis)

    /** One leg of [lengthM] from [from] along [bearingDeg], 1 s after [previousMillis]. */
    private fun leg(from: LatLon, bearingDeg: Double, lengthM: Double, previousMillis: Long) =
        fix(Geo.destination(from, bearingDeg, lengthM), previousMillis + 1_000L, null)

    // ------------------------------------------------------------------ the window

    @Test
    fun `the first fix anchors and derives nothing`() {
        val e = CourseEstimator.update(null, fix(here, 0L, 90f))
        assertNull(e.derivedCourseDeg)
        assertTrue(e.chipTrusted)
    }

    @Test
    fun `a straight drive derives the geometric bearing each time the window flushes`() {
        var s: CourseEstimator.State? = null
        var pos = here
        var t = 0L

        fun step(bearingDeg: Double): Double? {
            val f = leg(pos, bearingDeg, 20.0, t)
            val e = CourseEstimator.update(s, f)
            s = e.state
            pos = f.position
            t = f.elapsedMillis
            return e.derivedCourseDeg
        }

        assertNull(step(42.0)) // first call only anchors
        val second = step(42.0)!!
        val third = step(42.0)!!
        assertEquals(42.0, second, 1e-6)
        assertEquals(42.0, third, 1e-6)
    }

    @Test
    fun `a turn shows up in the next derived course`() {
        var s: CourseEstimator.State? = null
        val first = CourseEstimator.update(null, fix(here, 0L, 0f))
        s = first.state

        val turned = CourseEstimator.update(
            s,
            leg(here, 117.0, 25.0, 0L),
        )
        assertEquals(117.0, turned.derivedCourseDeg!!, 1e-6)
    }

    @Test
    fun `stationary jitter inside its own accuracy circle never derives a course`() {
        var s: CourseEstimator.State? = null
        // Six fixes wandering on a ~3 m ring: real receiver noise around a parked car. The
        // widest chord the ring can produce (6 m, opposite points) stays under the 8 m floor,
        // so no pair of noise samples can masquerade as travel.
        (0 until 6).forEach { i ->
            val wander = Geo.destination(here, i * 60.0, 3.0)
            val e = CourseEstimator.update(
                s,
                fix(wander, (i + 1) * 1_000L, i * 60f, speed = 0.2f),
            )
            s = e.state
            assertNull("fix $i derived a course from noise", e.derivedCourseDeg)
        }
    }

    @Test
    fun `a teleport resets the window instead of inventing a bearing`() {
        val anchored = CourseEstimator.update(null, fix(here, 0L, 0f))
        // 500 m in one second is a jump, not travel.
        val jumped = CourseEstimator.update(
            anchored.state,
            fix(Geo.destination(here, 270.0, 500.0), 1_000L, 270f),
        )
        assertNull(jumped.derivedCourseDeg)
    }

    @Test
    fun `an ancient window is discarded rather than averaged in`() {
        val anchored = CourseEstimator.update(null, fix(here, 0L, 0f))
        // Past STALE_AFTER_MS the anchor describes where the user *was*, not a journey.
        val late = CourseEstimator.update(
            anchored.state,
            fix(Geo.destination(here, 180.0, 30.0), NavigationState.STALE_AFTER_MS + 1_000L, 180f),
        )
        assertNull(late.derivedCourseDeg)
    }

    @Test
    fun `an unknown accuracy falls back to the displacement floor`() {
        val anchored = CourseEstimator.update(null, fix(here, 0L, 0f))
        val blind = CourseEstimator.update(
            anchored.state,
            fix(Geo.destination(here, 66.0, 9.0), 1_000L, null, accuracy = Float.MAX_VALUE),
        )
        assertEquals(66.0, blind.derivedCourseDeg!!, 1e-6)
    }

    // ------------------------------------------------------------------ the watchdog

    /**
     * A curving road with a stalled chip field: positions sweep through three 45° legs while
     * `bearingDeg` repeats bit-for-bit. The flag lands exactly when the run reaches
     * [CourseEstimator.FROZEN_IDENTICAL_FIXES] with geometry contradicting it.
     */
    @Test
    fun `a frozen chip on a curving road loses trust after three diverging fixes`() {
        val legs = listOf(0.0, 45.0, 90.0, 135.0)
        var s: CourseEstimator.State? = null
        var pos = here
        var t = 0L
        val verdicts = mutableListOf<Boolean>()

        legs.forEach { legBearing ->
            val f = leg(pos, legBearing, 25.0, t).copy(bearingDeg = 0f)
            val e = CourseEstimator.update(s, f)
            s = e.state
            pos = f.position
            t = f.elapsedMillis
            verdicts += e.chipTrusted
        }

        assertTrue(verdicts[0]); assertTrue(verdicts[1]); assertTrue(verdicts[2])
        assertFalse("three identical bearings against contradicting geometry", verdicts[3])
    }

    @Test
    fun `a genuinely straight road never flags a constant chip`() {
        var s: CourseEstimator.State? = null
        var pos = here
        var t = 0L

        repeat(10) {
            val f = leg(pos, 90.0, 25.0, t).copy(bearingDeg = 90f)
            val e = CourseEstimator.update(s, f)
            s = e.state
            pos = f.position
            t = f.elapsedMillis
            assertTrue("straight road flagged at fix $it", e.chipTrusted)
        }
    }

    @Test
    fun `trust returns the moment the chip reports a different value`() {
        // Drive into the latched state.
        var s: CourseEstimator.State? = null
        var pos = here
        var t = 0L
        listOf(0.0, 60.0, 120.0).forEach { b ->
            val f = leg(pos, b, 25.0, t).copy(bearingDeg = 0f)
            val e = CourseEstimator.update(s, f)
            s = e.state; pos = f.position; t = f.elapsedMillis
        }
        // One more leg pushes the run to three with divergence.
        val flagged = CourseEstimator.update(s, leg(pos, 180.0, 25.0, t).copy(bearingDeg = 0f))
        assertFalse(flagged.chipTrusted)

        // The chip wakes up: any different value proves the field is alive again.
        val revived = CourseEstimator.update(
            flagged.state,
            leg(flagged.state.anchor!!, 200.0, 25.0, t + 1_000L).copy(bearingDeg = 195f),
        )
        assertTrue(revived.chipTrusted)
    }

    @Test
    fun `a fix without a bearing neither breaks the run nor restores trust`() {
        // Latch distrust exactly as above.
        var s: CourseEstimator.State? = null
        var pos = here
        var t = 0L
        listOf(0.0, 60.0, 120.0).forEach { b ->
            val f = leg(pos, b, 25.0, t).copy(bearingDeg = 0f)
            val e = CourseEstimator.update(s, f)
            s = e.state; pos = f.position; t = f.elapsedMillis
        }
        val flagged = CourseEstimator.update(s, leg(pos, 180.0, 25.0, t).copy(bearingDeg = 0f))
        assertFalse(flagged.chipTrusted)

        // A silent fix holds the verdict...
        val silent = CourseEstimator.update(
            flagged.state,
            leg(flagged.state.anchor!!, 210.0, 25.0, t + 1_000L).copy(bearingDeg = null),
        )
        assertFalse("silence must not resurrect trust", silent.chipTrusted)

        // ...and the same frozen value afterwards stays distrusted too.
        val stillFrozen = CourseEstimator.update(
            silent.state,
            leg(silent.state.anchor!!, 240.0, 25.0, t + 2_000L).copy(bearingDeg = 0f),
        )
        assertFalse(stillFrozen.chipTrusted)
    }
}
