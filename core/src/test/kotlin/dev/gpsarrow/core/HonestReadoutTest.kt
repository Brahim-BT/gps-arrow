package dev.gpsarrow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // of standing on the point, from a receiver that cannot tell 0 m from 8 m apart. The
        // bound is now carried as a flag so each language can word "under 10 m" its own way.
        listOf(0.0, 1.0, 4.9, 5.0, 9.0, 9.999).forEach {
            val r = Format.distance(it, DistanceUnits.METRIC)
            assertTrue("at $it m", r.isLowerBound)
            assertEquals("at $it m", "10", r.value)
            assertEquals(LengthUnit.METRES, r.unit)
        }
        listOf(10.0 to "10", 14.0 to "10", 15.0 to "20").forEach { (meters, expected) ->
            val r = Format.distance(meters, DistanceUnits.METRIC)
            assertFalse("at $meters m", r.isLowerBound)
            assertEquals("at $meters m", expected, r.value)
        }
    }

    @Test
    fun `every unit system has a floor at its own resolution`() {
        // 30 ft is 9.14 m, the imperial equivalent of the metric floor.
        Format.distance(9.0, DistanceUnits.IMPERIAL).let {
            assertTrue(it.isLowerBound)
            assertEquals("30", it.value)
            assertEquals(LengthUnit.FEET, it.unit)
        }
        Format.distance(9.2, DistanceUnits.IMPERIAL).let {
            assertFalse(it.isLowerBound)
            assertEquals("30", it.value)
        }
        assertEquals("1000", Format.distance(304.0, DistanceUnits.IMPERIAL).value)

        // Two decimals of a nautical mile is 18.5 m, so that is where nautical has to stop.
        listOf(0.0, 18.0).forEach {
            val r = Format.distance(it, DistanceUnits.NAUTICAL)
            assertTrue("at $it m", r.isLowerBound)
            assertEquals("0.01", r.value)
            assertEquals(LengthUnit.NAUTICAL_MILES, r.unit)
        }
    }

    @Test
    fun `the floor and the arrival radius do not contradict each other`() {
        // ARRIVED takes over at or below ARRIVAL_RADIUS_M, so TARGET mode starts above it. If
        // someone lowers the arrival radius under the floor, the arrow would start showing a
        // bound as a live target distance and this fails.
        assertFalse(
            Format.distance(NavigationState.ARRIVAL_RADIUS_M, DistanceUnits.METRIC).isLowerBound,
        )
    }

    @Test
    fun `the pre-existing precision ladder is unchanged above the floor`() {
        assertEquals("120", Format.distance(123.0).value)
        assertEquals("990", Format.distance(994.0).value)
        Format.distance(123_456.0).let {
            assertEquals("123", it.value)
            assertEquals(LengthUnit.KILOMETRES, it.unit)
        }
    }

    // ------------------------------------------------------------------ Latin digits, always

    /**
     * The property under test, written the way the production code computes it rather than as a
     * list of characters someone guessed at.
     *
     * The first version of this test allowed a hand-written set of digits *and separators*,
     * which conflated two different things and failed on the first real JVM run: `ar-MR`,
     * `ar-EG` and `fa-IR` use U+066B ARABIC DECIMAL SEPARATOR, so "1\u066B2" has entirely Latin
     * digits and still fell outside the set. The guarantee is about digits. Separators follow
     * the language on purpose, because French must keep its comma.
     */
    private fun String.hasOnlyLatinDigits(): Boolean =
        none { it.digitToIntOrNull() != null && it !in '0'..'9' }

    @Test
    fun `no locale can put a non-Latin digit on the screen`() {
        // ar-MR is the locale that would regress silently: CLDR gives Mauritania the `arab`
        // numbering system while Morocco gets `latn`, so a device in Nouakchott would render
        // Arabic-Indic where one in Casablanca renders Latin - same app, same build, a
        // different-looking screen. One Arabic build reading identically in both countries is
        // a product decision, recorded in Format.latinDigitLocale.
        //
        // These locales are deliberately raw, WITHOUT the nu-latn extension, so what is under
        // test is the transliteration guarantee rather than the polite request. fa-IR is here
        // because `arabext` is a third set of digits again, and bn-IN a fourth.
        val locales = listOf(
            Locale.forLanguageTag("ar-MR"),
            Locale.forLanguageTag("ar-EG"),
            Locale.forLanguageTag("ar-MA"),
            Locale.forLanguageTag("ar"),
            Locale.forLanguageTag("fa-IR"),
            Locale.forLanguageTag("bn-IN"),
            Locale.FRANCE,
            Locale.ENGLISH,
        )
        locales.forEach { locale ->
            DistanceUnits.entries.forEach { units ->
                listOf(0.0, 5.0, 450.0, 1_234.0, 123_456.0).forEach { meters ->
                    val value = Format.distance(meters, units, locale).value
                    assertTrue("$locale/$units gave $value", value.hasOnlyLatinDigits())
                }
            }
            val bearing = Format.bearing(47.0, locale)
            assertEquals("$locale", "047", bearing.degrees)
            assertEquals(2, bearing.pointIndex)
            assertTrue("$locale", Format.number("%d", locale, 1_234).hasOnlyLatinDigits())
            assertTrue("$locale", Format.number("%.2f", locale, 1.5).hasOnlyLatinDigits())
        }
    }

    @Test
    fun `the digit check would notice if the transliteration stopped working`() {
        // Without this, a hasOnlyLatinDigits that always returned true would make the test
        // above pass while guaranteeing nothing.
        assertFalse("\u0664\u0665\u0660".hasOnlyLatinDigits())   // Arabic-Indic 450
        assertFalse("\u06F4\u06F5\u06F0".hasOnlyLatinDigits())   // Extended Arabic-Indic 450
        assertFalse("\u09EA\u09EB\u09E6".hasOnlyLatinDigits())   // Bengali 450
        assertTrue("450".hasOnlyLatinDigits())
        // A non-ASCII separator between Latin digits passes, and should: it is not a digit.
        assertTrue("1\u066B2".hasOnlyLatinDigits())
    }

    @Test
    fun `the numbering system is pinned without disturbing the language`() {
        val pinned = Format.latinDigitLocale(Locale.forLanguageTag("ar-MR"))
        assertEquals("ar", pinned.language)
        assertEquals("MR", pinned.country)
        // Pure Locale API, no CLDR data involved, so this holds on any JDK: the request is
        // provably made even where the platform declines to honour it.
        assertEquals("latn", pinned.getUnicodeLocaleType("nu"))
    }

    @Test
    fun `separators still follow the language, which is why they are not pinned`() {
        // English and French are the two no JDK can plausibly disagree about, and they are what
        // would break if someone ever "fixed" separators to ASCII along with the digits.
        assertEquals("1.2", Format.distance(1_234.0, DistanceUnits.METRIC, Locale.ENGLISH).value)
        assertEquals("1,2", Format.distance(1_234.0, DistanceUnits.METRIC, Locale.FRANCE).value)
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
