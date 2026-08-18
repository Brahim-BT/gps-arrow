package dev.gpsarrow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Readouts must not claim more than the hardware knows.
 *
 * Every case here is a place the app could state something with more confidence than the fix,
 * the sensor or the format behind it can support. A navigation tool used somewhere without
 * signal has no second opinion available, so "I don't know" has to be a first-class answer.
 */
class HonestReadoutTest {

    private val here = LatLon(48.8584, 2.2945)

    private fun fix(accuracy: Float = 5f) = Fix(here, accuracy, 40.0, null, null, 0L)

    // ------------------------------------------------------------------ the distance floor

    @Test
    fun `distance never prints zero, because no fix can support it`() {
        // Rounding to the nearest 10 m used to render everything under 5 m as "0 m" — a claim
        // of standing on the point, from a receiver that cannot tell 0 m from 8 m apart.
        listOf(0.0, 1.0, 4.9, 5.0, 9.0, 9.999).forEach {
            assertEquals("at $it m", "under 10 m", Format.distance(it, DistanceUnits.METRIC))
        }
        assertEquals("10 m", Format.distance(10.0, DistanceUnits.METRIC))
        assertEquals("10 m", Format.distance(14.0, DistanceUnits.METRIC))
        assertEquals("20 m", Format.distance(15.0, DistanceUnits.METRIC))
    }

    @Test
    fun `every unit system has a floor at its own resolution`() {
        // 30 ft is 9.14 m, the imperial equivalent of the metric floor.
        assertEquals("under 30 ft", Format.distance(9.0, DistanceUnits.IMPERIAL))
        assertEquals("30 ft", Format.distance(9.2, DistanceUnits.IMPERIAL))
        assertEquals("1000 ft", Format.distance(304.0, DistanceUnits.IMPERIAL))

        // Two decimals of a nautical mile is 18.5 m, so that is where nautical has to stop.
        assertEquals("under 0.01 NM", Format.distance(0.0, DistanceUnits.NAUTICAL))
        assertEquals("under 0.01 NM", Format.distance(18.0, DistanceUnits.NAUTICAL))
    }

    @Test
    fun `the floor and the arrival radius do not contradict each other`() {
        // ARRIVED takes over at or below ARRIVAL_RADIUS_M, so TARGET mode starts above it. If
        // someone lowers the arrival radius under the floor, the arrow would start showing
        // "under 10 m" as a live target distance and this fails.
        assertNotEquals(
            "under 10 m",
            Format.distance(NavigationState.ARRIVAL_RADIUS_M, DistanceUnits.METRIC),
        )
    }

    @Test
    fun `the pre-existing precision ladder is unchanged above the floor`() {
        assertEquals("120 m", Format.distance(123.0))
        assertEquals("990 m", Format.distance(994.0))
        assertEquals("123 km", Format.distance(123_456.0))
    }

    // ------------------------------------------------------------------ interchange formats

    @Test
    fun `coordinate text is locale independent because it is an interchange format`() {
        val original = Locale.getDefault()
        try {
            // France formats decimals with a comma, which would turn a coordinate pair into
            // "48,85840, 2,29450": three commas, no unambiguous split, and unreadable to every
            // other mapping tool the user might paste it into.
            Locale.setDefault(Locale.FRANCE)
            assertEquals("48.85840, 2.29450", Format.decimal(here))
            assertEquals("48°51'30.24\"N 2°17'40.20\"E", Format.dms(here))

            // Southern and western hemispheres go through the same path.
            assertEquals("-33.85680, 151.21530", Format.decimal(LatLon(-33.8568, 151.2153)))

            // The editor's field text goes through the same rule, so the list row and the edit
            // screen can never disagree about how a point is written.
            assertEquals("48.858400", Format.coordinate(here.lat))
            assertEquals("-33.856800", Format.coordinate(-33.8568))

            // And the whole point: it survives a round trip through the app's own parser.
            val parsed = DestinationParser.parse(Format.decimal(here))
            assertTrue("got $parsed", parsed is ParseResult.Success)
            val position = (parsed as ParseResult.Success).position
            assertTrue(Geo.distanceMeters(here, position) < 1.5)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `a comma-decimal latitude is one number, not a coordinate pair`() {
        // The regression this guards: on a comma-decimal device, "48,8" typed into the latitude
        // field reached the pair parser, which reads the comma as the separator and returned
        // lat 48 / lon 8 — a point in the Gulf of Guinea, about 5400 km from Casablanca. The
        // window was every latitude with one or two digits after the comma, which is every
        // latitude, on the way to being typed.
        listOf("48", "48,", "48,8", "48,85", "48,8584", "-33,8", "0,5", "48.8584", "-7.5898")
            .forEach { assertTrue("\"$it\" should be one number", DestinationParser.isSingleComponent(it)) }

        // Whole pairs must still reach the parser, in either notation.
        listOf("48,85840, 2,29450", "48.8584, 2.2945", "48.8584 2.2945", "48,8584 2,2945")
            .forEach { assertFalse("\"$it\" is a pair", DestinationParser.isSingleComponent(it)) }

        // And the pairs really do parse to the same place whichever separator is used.
        val dot = DestinationParser.parse("48.85840, 2.29450") as ParseResult.Success
        val comma = DestinationParser.parse("48,85840, 2,29450") as ParseResult.Success
        assertEquals(0.0, Geo.distanceMeters(dot.position, comma.position), 1e-9)
        assertTrue(Geo.distanceMeters(here, dot.position) < 1.5)
    }

    // ------------------------------------------------------------------ what may be saved

    @Test
    fun `a stale fix is where you were, so it cannot be saved as where you are`() {
        val fresh = NavigationState(fix = fix(), fixAgeMillis = 0)
        assertTrue(fresh.isSaveable)

        // The staleness boundary is the same one the arrow greys out on, to the millisecond.
        val atLimit = fresh.copy(fixAgeMillis = NavigationState.STALE_AFTER_MS)
        assertEquals(FixQuality.GOOD, atLimit.quality)
        assertTrue(atLimit.isSaveable)

        val overLimit = fresh.copy(fixAgeMillis = NavigationState.STALE_AFTER_MS + 1)
        assertEquals(FixQuality.STALE, overLimit.quality)
        assertFalse(overLimit.isSaveable)
    }

    @Test
    fun `a merely imprecise fix is saved, not refused`() {
        // Refusing here would be the wrong call: a fresh 55 m fix is a real position, and the
        // user gets to decide whether it is good enough. It is recorded with its accuracy so
        // the decision is an informed one rather than a guess.
        val poor = NavigationState(fix = fix(accuracy = 55f), fixAgeMillis = 0)
        assertEquals(FixQuality.POOR, poor.quality)
        assertTrue(poor.isSaveable)
    }

    @Test
    fun `nothing is saveable without a fix`() {
        assertFalse(NavigationState().isSaveable)
        assertEquals(FixQuality.NONE, NavigationState().quality)
    }

    @Test
    fun `a saved point carries no accuracy unless one was measured`() {
        // Null is "we don't know how good this is", and it must never collapse into 0.
        assertNull(Destination("1", "Pasted", here).accuracyMeters)
        assertEquals(
            7.5f,
            Destination("2", "Here", here, accuracyMeters = 7.5f).accuracyMeters!!,
            1e-6f,
        )
    }

    // ------------------------------------------------------------------ magnetic vs true north

    @Test
    fun `the compass is magnetic until a fix makes declination computable`() {
        val noFix = NavigationState(headingDeg = 90.0, headingSource = HeadingSource.COMPASS)
        assertTrue(noFix.headingIsMagnetic)

        // An uncalibrated compass is magnetic for the same reason.
        assertTrue(noFix.copy(headingSource = HeadingSource.COMPASS_UNCALIBRATED).headingIsMagnetic)
    }

    @Test
    fun `a declination of exactly zero is a computed answer, not a missing one`() {
        // Declination really is near zero along the agonic line through the Americas, so zero
        // must read as "corrected", never as "not corrected yet".
        val corrected = NavigationState(
            fix = fix(),
            headingDeg = 90.0,
            headingSource = HeadingSource.COMPASS,
            declinationDeg = 0.0,
        )
        assertFalse(corrected.headingIsMagnetic)
    }

    @Test
    fun `gps course is already true north, so it is never flagged magnetic`() {
        val course = NavigationState(headingDeg = 90.0, headingSource = HeadingSource.GPS_COURSE)
        assertFalse(course.headingIsMagnetic)
        assertFalse(NavigationState().headingIsMagnetic)
    }
}
