package dev.gpsarrow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map's rotation gating.
 *
 * Every case drives time explicitly in milliseconds rather than by counting samples, because the
 * bug this design is guarding against — the 1 Hz arrow flicker — came from logic that assumed a
 * sample rate. A test that fed one sample per tick would pass on that bug.
 */
class MapOrientationTest {

    private val rotateAfter = MapOrientation.ROTATE_AFTER_MILLIS   // 1500
    private val northAfter = MapOrientation.NORTH_UP_AFTER_MILLIS  // 700
    private val resumeAfter = MapOrientation.RESUME_AFTER_MILLIS   // 8000

    @Test
    fun `startsNorthUpAndDoesNotRotateImmediately`() {
        val s = MapOrientation.update(OrientationState(), headingDeg = 90.0, nowMillis = 0L)
        assertEquals(
            "a single sample must not swing the camera",
            0.0, s.appliedBearingDeg, 1e-9,
        )
    }

    @Test
    fun `rotatesOnlyAfterHeadingHasBeenSteadyLongEnough`() {
        var s = MapOrientation.update(OrientationState(), 90.0, 0L)
        s = MapOrientation.update(s, 90.0, rotateAfter - 1)
        assertEquals("one millisecond short, still north", 0.0, s.appliedBearingDeg, 1e-9)

        s = MapOrientation.update(s, 90.0, rotateAfter)
        assertEquals("at the threshold it takes the heading", 90.0, s.appliedBearingDeg, 1e-9)
        assertFalse(s.northUp)
    }

    @Test
    fun `followsHeadingOnceRotating`() {
        var s = MapOrientation.update(OrientationState(), 10.0, 0L)
        s = MapOrientation.update(s, 10.0, rotateAfter)
        s = MapOrientation.update(s, 200.0, rotateAfter + 100)
        assertEquals(200.0, s.appliedBearingDeg, 1e-9)
    }

    /** A brief dropout must not throw the map north — that is the flicker this prevents. */
    @Test
    fun `holdsTheLastBearingThroughAShortDropout`() {
        var s = MapOrientation.update(OrientationState(), 120.0, 0L)
        s = MapOrientation.update(s, 120.0, rotateAfter)
        assertEquals(120.0, s.appliedBearingDeg, 1e-9)

        s = MapOrientation.update(s, null, rotateAfter + 100)
        assertEquals("still holding", 120.0, s.appliedBearingDeg, 1e-9)
        assertFalse(s.northUp)

        s = MapOrientation.update(s, null, rotateAfter + northAfter - 1)
        assertEquals("one millisecond short of giving up", 120.0, s.appliedBearingDeg, 1e-9)
    }

    @Test
    fun `snapsNorthAfterASustainedLoss`() {
        var s = MapOrientation.update(OrientationState(), 120.0, 0L)
        s = MapOrientation.update(s, 120.0, rotateAfter)
        s = MapOrientation.update(s, null, rotateAfter + 1)
        s = MapOrientation.update(s, null, rotateAfter + northAfter)
        assertEquals(0.0, s.appliedBearingDeg, 1e-9)
        assertTrue(s.northUp)
    }

    /**
     * The asymmetry is the whole point, so it is pinned: giving up must be quicker than
     * committing. A map frozen at a stale bearing looks authoritative and points the wrong way.
     */
    @Test
    fun `itIsQuickerToGiveUpThanToCommit`() {
        assertTrue(
            "north-up dwell ($northAfter) must be shorter than rotate dwell ($rotateAfter)",
            northAfter < rotateAfter,
        )
    }

    /** Alternating availability must not produce rotation — that would be the boundary flicker. */
    @Test
    fun `flappingHeadingNeverStartsRotation`() {
        var s = OrientationState()
        var t = 0L
        repeat(40) { i ->
            s = MapOrientation.update(s, if (i % 2 == 0) 45.0 else null, t)
            t += 200
        }
        assertEquals(
            "heading was never continuously present for the full dwell",
            0.0, s.appliedBearingDeg, 1e-9,
        )
    }

    @Test
    fun `aUserGestureSuspendsFollowing`() {
        var s = MapOrientation.update(OrientationState(), 90.0, 0L)
        s = MapOrientation.update(s, 90.0, rotateAfter)
        assertEquals(90.0, s.appliedBearingDeg, 1e-9)

        s = MapOrientation.afterUserGesture(s, rotateAfter)
        assertFalse(s.followingHeading)
        assertEquals("the resume dwell is timed from the gesture", rotateAfter, s.lastGestureAtMillis)

        // Further samples must not move the camera while suspended.
        s = MapOrientation.update(s, 270.0, rotateAfter + 1_000)
        assertEquals("the map must not fight the user's finger", 90.0, s.appliedBearingDeg, 1e-9)
    }

    @Test
    fun `followingResumesOnItsOwnAfterTheIdleDwell`() {
        var s = MapOrientation.update(OrientationState(), 90.0, 0L)
        s = MapOrientation.update(s, 90.0, rotateAfter)
        s = MapOrientation.afterUserGesture(s, 10_000L)

        // One millisecond short of the dwell: still suspended.
        s = MapOrientation.update(s, null, 10_000L + resumeAfter - 1)
        assertFalse("the idle dwell has not expired yet", s.followingHeading)

        // At the threshold, the very next sample resumes following — no tap required.
        s = MapOrientation.update(s, null, 10_000L + resumeAfter)
        assertTrue("following must come back on its own", s.followingHeading)
        assertNull("the gesture stamp is spent", s.lastGestureAtMillis)
    }

    /** Continuous browsing restamps the clock on every gesture and is never interrupted. */
    @Test
    fun `aSecondGestureRestartsTheIdleClock`() {
        var s = MapOrientation.update(OrientationState(), 90.0, 0L)
        s = MapOrientation.afterUserGesture(s, 1_000L)

        // Seven seconds pass without another touch…
        s = MapOrientation.update(s, null, 8_000L - 1)
        assertFalse(s.followingHeading)

        // …then the user pans again. The clock restarts from THAT gesture, not the first one —
        // otherwise a long browsing session would be interrupted mid-read eight seconds in.
        s = MapOrientation.afterUserGesture(s, 8_000L)
        s = MapOrientation.update(s, null, 16_000L - 1)
        assertFalse("the dwell restarted with the second gesture", s.followingHeading)

        s = MapOrientation.update(s, null, 16_000L)
        assertTrue(s.followingHeading)
    }

    /**
     * Heading that stayed steady throughout the suspension takes over immediately at resume:
     * availability was tracked all along, so making the user wait out the rotate dwell again
     * would be a second, unearned delay.
     */
    @Test
    fun `aResumeWithSteadyHeadingDoesNotWaitTwice`() {
        var s = MapOrientation.update(OrientationState(), 90.0, 0L)
        s = MapOrientation.update(s, 90.0, rotateAfter)
        s = MapOrientation.afterUserGesture(s, 10_000L)

        // Compass keeps reporting while the user browses…
        s = MapOrientation.update(s, 90.0, 12_000L)
        s = MapOrientation.update(s, 90.0, 18_000L - 1)
        assertEquals("still suspended: no rotation while browsing", 90.0, s.appliedBearingDeg, 1e-9)

        // …and at the dwell boundary the same sample both resumes and rotates.
        s = MapOrientation.update(s, 90.0, 18_000L)
        assertTrue(s.followingHeading)
        assertEquals(90.0, s.appliedBearingDeg, 1e-9)
    }

    @Test
    fun `tappingNorthResumesAndFacesNorth`() {
        var s = MapOrientation.update(OrientationState(), 90.0, 0L)
        s = MapOrientation.update(s, 90.0, rotateAfter)
        s = MapOrientation.afterUserGesture(s, rotateAfter)
        s = MapOrientation.afterNorthTap(s, 10_000L)

        assertTrue(s.followingHeading)
        assertEquals(0.0, s.appliedBearingDeg, 1e-9)
        assertNull("an explicit resume is not subject to the idle dwell", s.lastGestureAtMillis)

        // And it must earn rotation again from scratch rather than snapping straight back.
        s = MapOrientation.update(s, 90.0, 10_000L + rotateAfter - 1)
        assertEquals(0.0, s.appliedBearingDeg, 1e-9)
        s = MapOrientation.update(s, 90.0, 10_000L + rotateAfter)
        assertEquals(90.0, s.appliedBearingDeg, 1e-9)
    }

    /**
     * North sits opposite the camera bearing. Checked at several angles because a sign error here
     * points the indicator exactly wrong and looks plausible at 0 and 180.
     */
    @Test
    fun `northIndicatorPointsOppositeTheCameraBearing`() {
        val cases = listOf(0.0 to 0.0, 90.0 to 270.0, 180.0 to 180.0, 270.0 to 90.0, 45.0 to 315.0)
        for ((bearing, expected) in cases) {
            val s = OrientationState(appliedBearingDeg = bearing, northUp = false)
            assertEquals(
                "camera at $bearing, north should be at $expected",
                expected, MapOrientation.northIndicatorDegrees(s), 1e-9,
            )
        }
    }

    @Test
    fun `theIndicatorHidesWhenAlreadyNorthUpAndFollowing`() {
        assertFalse(
            MapOrientation.showNorthIndicator(
                OrientationState(northUp = true, followingHeading = true),
            ),
        )
        assertTrue(
            MapOrientation.showNorthIndicator(
                OrientationState(northUp = false, followingHeading = true),
            ),
        )
        assertTrue(
            "suspended by a gesture: the user needs a way back even if facing north",
            MapOrientation.showNorthIndicator(
                OrientationState(northUp = true, followingHeading = false),
            ),
        )
    }
}
