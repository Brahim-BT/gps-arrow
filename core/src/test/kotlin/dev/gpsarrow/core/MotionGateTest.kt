package dev.gpsarrow.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The moving/not-moving hysteresis that decides when the camera rides ahead of the dot.
 *
 * Speeds are driven across both thresholds and the band between them, because a single
 * threshold would flip the offset on every wobble of the GNSS velocity and each flip jumps the
 * dot by a fifth of the screen.
 */
class MotionGateTest {

    private val start = MotionGate.START_ABOVE_MPS  // 1.4
    private val stop = MotionGate.STOP_BELOW_MPS    // 0.6

    @Test
    fun `startsMovingOnlyAboveTheStartThreshold`() {
        assertFalse("just under: not yet", MotionGate.update(false, start - 0.01f))
        assertTrue(MotionGate.update(false, start))
        assertTrue(MotionGate.update(false, start + 1f))
    }

    @Test
    fun `stopsMovingOnlyBelowTheStopThreshold`() {
        assertTrue("just over: still moving", MotionGate.update(true, stop + 0.01f))
        assertFalse(MotionGate.update(true, stop))
        assertFalse(MotionGate.update(true, 0f))
    }

    /** The band between the thresholds holds the previous answer — that is the whole point. */
    @Test
    fun `holdsWithinTheHysteresisBand`() {
        val mid = (start + stop) / 2f
        assertTrue("was moving, speed in the band: keep riding ahead", MotionGate.update(true, mid))
        assertFalse("was stopped, speed in the band: stay centred", MotionGate.update(false, mid))
    }

    @Test
    fun `missingOrNonsenseSpeedIsNotMoving`() {
        assertFalse(MotionGate.update(true, null))
        assertFalse(MotionGate.update(false, null))
        assertFalse(MotionGate.update(true, Float.NaN))
        assertFalse(MotionGate.update(true, Float.POSITIVE_INFINITY))
    }
}
