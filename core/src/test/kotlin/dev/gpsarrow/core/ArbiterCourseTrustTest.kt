package dev.gpsarrow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How the arbiter treats a distrusted chip bearing.
 *
 * The device finding behind [CourseEstimator] ended with the needle frozen on a stale
 * `Location.getBearing()` value for as long as GPS course was selected. These tests pin the
 * rule that came out of it: **a chip bearing the watchdog has branded untrusted never reaches
 * the needle** — geometry speaks for GPS instead, or the arbiter falls through exactly as if
 * no course existed.
 */
class ArbiterCourseTrustTest {

    private val here = LatLon(48.8584, 2.2945)

    private fun movingFix(bearingDeg: Float?, speed: Float = 10f) =
        Fix(here, 5f, 40.0, speed, bearingDeg, 0L)

    @Test
    fun `a trusted chip still wins over geometry`() {
        val (heading, source) = HeadingArbiter.select(
            fix = movingFix(42f),
            compassDeg = 137.5,
            magnetometerReliable = true,
            previousSource = HeadingSource.COMPASS,
            derivedCourseDeg = 200.0,
            chipCourseTrusted = true,
        )
        assertEquals(HeadingSource.GPS_COURSE, source)
        assertEquals(42.0, heading!!, 1e-9)
    }

    @Test
    fun `a distrusted chip is replaced by the derived course`() {
        val (heading, source) = HeadingArbiter.select(
            fix = movingFix(0f),
            compassDeg = 137.5,
            magnetometerReliable = true,
            previousSource = HeadingSource.GPS_COURSE,
            derivedCourseDeg = 213.0,
            chipCourseTrusted = false,
        )
        assertEquals(HeadingSource.GPS_COURSE, source)
        assertEquals(213.0, heading!!, 1e-9)
    }

    @Test
    fun `with no derived course to speak for it, the distrusted chip falls all the way through`() {
        // Compass present: the fallback is the compass, exactly as if no course existed.
        val withCompass = HeadingArbiter.select(
            fix = movingFix(0f),
            compassDeg = 137.5,
            magnetometerReliable = false,
            previousSource = HeadingSource.GPS_COURSE,
            derivedCourseDeg = null,
            chipCourseTrusted = false,
        )
        assertEquals(HeadingSource.COMPASS_UNCALIBRATED, withCompass.second)
        assertEquals(137.5, withCompass.first!!, 1e-9)

        // No compass either: an honest nothing beats a frozen number.
        val withNothing = HeadingArbiter.select(
            fix = movingFix(0f),
            compassDeg = null,
            magnetometerReliable = false,
            previousSource = HeadingSource.GPS_COURSE,
            derivedCourseDeg = null,
            chipCourseTrusted = false,
        )
        assertEquals(HeadingSource.NONE, withNothing.second)
        assertNull(withNothing.first)
    }

    @Test
    fun `derived course obeys the same stationary gate as the chip`() {
        // Below STATIONARY_MPS neither source may move the needle, whatever trust says.
        val slow = HeadingArbiter.select(
            fix = movingFix(bearingDeg = null, speed = 0.3f),
            compassDeg = 137.5,
            magnetometerReliable = true,
            previousSource = HeadingSource.GPS_COURSE,
            derivedCourseDeg = 213.0,
            chipCourseTrusted = false,
        )
        assertEquals(HeadingSource.COMPASS, slow.second)

        val slowNoCompass = HeadingArbiter.select(
            fix = movingFix(bearingDeg = null, speed = 0.3f),
            compassDeg = null,
            magnetometerReliable = false,
            previousSource = HeadingSource.NONE,
            derivedCourseDeg = 213.0,
            chipCourseTrusted = false,
        )
        assertEquals(HeadingSource.NONE, slowNoCompass.second)
        assertNull(slowNoCompass.first)
    }
}
