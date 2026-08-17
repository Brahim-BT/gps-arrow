package dev.gpsarrow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.random.Random

class MgrsTest {

    private fun metersApart(a: LatLon, b: LatLon): Double {
        val dLat = (b.lat - a.lat) * 111_320.0
        val dLon = (b.lon - a.lon) * 111_320.0 * cos(Math.toRadians(a.lat))
        return kotlin.math.sqrt(dLat * dLat + dLon * dLon)
    }

    @Test
    fun `easting at a central meridian is exactly the false easting`() {
        val u = Mgrs.toUtm(LatLon(40.0, -75.0))
        assertEquals(18, u.zone)
        assertEquals(500_000.0, u.easting, 1e-6)
        // Meridian arc to 40N scaled by k0.
        assertEquals(4_427_757.0, u.northing, 2.0)
    }

    @Test
    fun `published usng references decode and re-encode exactly`() {
        // Published 1 m USNG/MGRS references. Round-tripping a published code back to itself
        // is the strongest assertion available: it pins the zone, band, 100 km square letters
        // and both numeric offsets simultaneously.
        listOf(
            "18SUJ2347706483",   // Washington Monument, DC
            "31UDQ4825111924",   // Eiffel Tower, Paris
            "56HLH3490052288",   // Sydney Opera House
            "33UUU9020016830",   // Berlin
            "19TCG3050046690",   // Massachusetts
        ).forEach { code ->
            val point = Mgrs.fromMgrs(code)
            assertNotNull("could not decode $code", point)
            assertEquals(code, Mgrs.toMgrs(point!!))
        }
    }

    @Test
    fun `washington monument decodes to the right place`() {
        val p = Mgrs.fromMgrs("18SUJ2347706483")!!
        assertEquals(38.88950, p.lat, 1e-4)
        assertEquals(-77.03531, p.lon, 1e-4)
    }

    @Test
    fun `spaced formatting`() {
        val p = Mgrs.fromMgrs("18SUJ2347706483")!!
        assertEquals("18S UJ 23477 06483", Mgrs.toMgrs(p, spaced = true))
    }

    @Test
    fun `decoding returns the centre of the square so encoding is stable`() {
        // A 1 km reference must decode to the middle of that kilometre square.
        val coarse = Mgrs.fromMgrs("18SUJ2306")!!
        assertEquals("18SUJ2306", Mgrs.toMgrs(coarse, digits = 2))
        // And the centre must be ~500 m from the corner the reference names.
        val corner = Mgrs.fromMgrs("18SUJ2300006000")!!
        assertTrue(
            "centre should be about 700 m from the corner, was ${metersApart(corner, coarse)}",
            metersApart(corner, coarse) in 600.0..800.0,
        )
    }

    @Test
    fun `zone exceptions for norway and svalbard`() {
        assertEquals(32, Mgrs.zoneFor(60.0, 5.0))     // would be 31 without the exception
        assertEquals(33, Mgrs.zoneFor(78.0, 15.0))    // would be 33 -> stays 33
        assertEquals(31, Mgrs.zoneFor(78.0, 5.0))     // would be 31
        assertEquals(35, Mgrs.zoneFor(78.0, 25.0))    // would be 35
    }

    @Test
    fun `polar regions are refused rather than encoded wrongly`() {
        assertNull(Mgrs.toMgrs(LatLon(85.0, 10.0)))
        assertNull(Mgrs.toMgrs(LatLon(-81.0, 10.0)))
    }

    @Test
    fun `round trips globally to within the truncation limit`() {
        var worst = 0.0
        var lat = -79.0
        while (lat <= 83.0) {
            var lon = -179.0
            while (lon <= 179.0) {
                val p = LatLon(lat, lon)
                val code = Mgrs.toMgrs(p)
                assertNotNull("no code for $p", code)
                val back = Mgrs.fromMgrs(code!!)
                assertNotNull("could not decode $code", back)
                worst = maxOf(worst, metersApart(p, back!!))
                lon += 7.0
            }
            lat += 3.0
        }
        assertTrue("worst round-trip error was $worst m", worst < 2.0)
    }

    @Test
    fun `lower precision codes still decode near the source`() {
        val p = LatLon(48.8584, 2.2945)
        for ((digits, tolerance) in listOf(5 to 2.0, 4 to 20.0, 3 to 200.0, 2 to 2_000.0)) {
            val code = Mgrs.toMgrs(p, digits)!!
            val back = Mgrs.fromMgrs(code)!!
            assertTrue(
                "digits=$digits gave ${metersApart(p, back)} m",
                metersApart(p, back) < tolerance,
            )
        }
    }

    @Test
    fun `rubbish input is rejected`() {
        assertNull(Mgrs.fromMgrs("hello"))
        assertNull(Mgrs.fromMgrs("99ZZZ0000000000"))
        assertNull(Mgrs.fromMgrs(""))
    }
}

class PlusCodeTest {

    @Test
    fun `reference vectors`() {
        assertEquals("8FVC2222+22", PlusCode.encode(LatLon(47.0000625, 8.0000625)))
        assertEquals("4VCPPQGP+Q9", PlusCode.encode(LatLon(-41.2730625, 174.7859375)))
    }

    @Test
    fun `decode contains the encoded point at every supported length`() {
        val rnd = Random(7)
        repeat(2_000) {
            val p = LatLon(rnd.nextDouble(-89.9, 89.9), rnd.nextDouble(-179.9, 179.9))
            for (len in listOf(10, 11, 12, 15)) {
                val code = PlusCode.encode(p, len)
                val area = PlusCode.decode(code)!!
                assertTrue(
                    "$code does not contain $p",
                    p.lat >= area.southWest.lat && p.lat <= area.northEast.lat &&
                        p.lon >= area.southWest.lon && p.lon <= area.northEast.lon,
                )
            }
        }
    }

    @Test
    fun `grid section tiles its parent cell as four rows by five columns`() {
        val parentCode = PlusCode.encode(LatLon(48.8584, 2.2945), 10)
        val parent = PlusCode.decode(parentCode)!!
        val alphabet = "23456789CFGHJMPQRVWX"

        val children = alphabet.map { PlusCode.decode(parentCode + it)!! }
        val latEdges = children.map { it.southWest.lat }.distinct()
        val lonEdges = children.map { it.southWest.lon }.distinct()
        assertEquals(4, latEdges.size)
        assertEquals(5, lonEdges.size)

        val childArea = children.sumOf {
            (it.northEast.lat - it.southWest.lat) * (it.northEast.lon - it.southWest.lon)
        }
        val parentArea = (parent.northEast.lat - parent.southWest.lat) *
            (parent.northEast.lon - parent.southWest.lon)
        assertEquals(parentArea, childArea, parentArea * 1e-9)
    }

    @Test
    fun `validity checks`() {
        assertTrue(PlusCode.isFull("8FVC2222+22"))
        assertTrue(PlusCode.isShort("2222+22"))
        assertTrue(!PlusCode.isValid("8FVC2222"))
        assertTrue(!PlusCode.isValid("ABCD1234+56"))
    }

    @Test
    fun `short code recovery lands near the reference`() {
        val target = LatLon(48.8584, 2.2945)
        val full = PlusCode.encode(target)
        val short = full.substring(4)
        val recovered = PlusCode.recoverNearest(short, LatLon(48.85, 2.30))
        assertEquals(full, recovered)
    }
}

class WmmTest {

    private val cof = """
        2025.0            WMM-2025        11/13/2024
        1  0  -29351.8       0.0       12.0        0.0
        1  1   -1410.8    4545.4        9.7      -21.5
        2  0   -2556.6       0.0      -11.6        0.0
        2  1    2951.1   -3133.6       -5.2      -27.7
        2  2    1649.3    -815.1       -8.0      -12.1
        3  0    1361.0       0.0       -1.3        0.0
        3  1   -2404.1     -56.6       -4.2        4.0
        3  2    1243.8     237.5        0.4       -0.3
        3  3     453.6    -549.5      -15.6       -4.1
        4  0     895.0       0.0       -1.6        0.0
        4  1     799.5     278.6       -2.4       -1.1
        4  2      55.7    -133.9       -6.0        4.1
        4  3    -281.1     212.0        5.6        1.6
        4  4      12.1    -375.6       -7.0       -4.4
        999999999999999999999999999999999999999999999999
    """.trimIndent()

    @Test
    fun `parses a cof header and body`() {
        val model = Wmm.parse(cof)
        assertNotNull("COF should parse", model)
        assertEquals("WMM-2025", model!!.name)
        assertEquals(0.0, model.yearsFromEpoch(2025.0), 1e-9)
        assertTrue(model.isValidFor(2026.6))
        assertTrue(!model.isValidFor(2032.0))
    }

    @Test
    fun `rejects text that is not a cof file`() {
        assertNull(Wmm.parse("this is not a coefficient file"))
        assertNull(Wmm.parse(""))
    }

    @Test
    fun `decimal year conversion`() {
        // Convention: 1 January is exactly year.0, so the fraction is (dayOfYear - 1) / daysInYear.
        assertEquals(2026.0, Wmm.decimalYearOf(2026, 1, 1), 1e-9)

        // 17 August 2026 is day 229 (31+28+31+30+31+30+31 = 212, +17).
        // Written as an expression rather than a decimal literal so it can't drift again:
        // the previous expectation here was a hand-rounded 2026.629, which is simply wrong
        // for this convention (the correct value is 2026.6247).
        assertEquals(2026.0 + 228.0 / 365.0, Wmm.decimalYearOf(2026, 8, 17), 1e-9)

        // 2024 was a leap year: 1 March is day 61 of 366.
        assertEquals(2024.0 + 60.0 / 366.0, Wmm.decimalYearOf(2024, 3, 1), 1e-9)

        // End points pin the convention down from both sides.
        assertEquals(2026.0 + 364.0 / 365.0, Wmm.decimalYearOf(2026, 12, 31), 1e-9)
        assertEquals(2024.0 + 365.0 / 366.0, Wmm.decimalYearOf(2024, 12, 31), 1e-9)
    }

    @Test
    fun `declination is finite and bounded with a truncated model`() {
        val model = Wmm.parse(cof)!!
        for (lat in -80..80 step 20) {
            for (lon in -180..180 step 45) {
                val d = model.declination(LatLon(lat.toDouble(), lon.toDouble()), 0.0, 2026.6)
                assertTrue("declination $d not finite at $lat,$lon", d.isFinite())
                assertTrue("declination $d out of range", abs(d) <= 180.0)
            }
        }
    }
}

class DestinationParserTest {

    private val here = LatLon(48.85, 2.30)

    private fun pos(input: String): LatLon {
        val r = DestinationParser.parse(input, here)
        assertTrue("expected success for \"$input\", got $r", r is ParseResult.Success)
        return (r as ParseResult.Success).position
    }

    private fun assertNear(expected: LatLon, actual: LatLon, toleranceDeg: Double = 1e-4) {
        assertEquals(expected.lat, actual.lat, toleranceDeg)
        assertEquals(expected.lon, actual.lon, toleranceDeg)
    }

    @Test
    fun `decimal degrees in several separators`() {
        val expected = LatLon(48.8584, 2.2945)
        assertNear(expected, pos("48.8584, 2.2945"))
        assertNear(expected, pos("48.8584 2.2945"))
        assertNear(expected, pos("48.8584;2.2945"))
        assertNear(expected, pos("48.8584/2.2945"))
    }

    @Test
    fun `negative and hemispheric decimal`() {
        assertNear(LatLon(-33.8568, 151.2153), pos("-33.8568, 151.2153"))
        assertNear(LatLon(-33.8568, 151.2153), pos("33.8568 S, 151.2153 E"))
        assertNear(LatLon(38.8895, -77.0353), pos("N38.8895 W77.0353"))
    }

    @Test
    fun `dms and dm`() {
        assertNear(LatLon(48.8584, 2.2945), pos("""48°51'30.2"N 2°17'40.2"E"""), 1e-3)
        assertNear(LatLon(-33.8568, 151.2153), pos("""33°51'24.5"S 151°12'55.1"E"""), 1e-3)
    }

    @Test
    fun `geo uri`() {
        val r = DestinationParser.parse("geo:48.8584,2.2945?z=17", here)
        assertTrue(r is ParseResult.Success)
        assertEquals(ParseResult.Format.GEO_URI, (r as ParseResult.Success).format)
        assertNear(LatLon(48.8584, 2.2945), r.position)
    }

    @Test
    fun `osm and google urls that carry coordinates`() {
        assertNear(
            LatLon(48.8584, 2.2945),
            pos("https://www.openstreetmap.org/#map=17/48.8584/2.2945"),
        )
        assertNear(
            LatLon(48.8584, 2.2945),
            pos("https://www.google.com/maps/@48.8584,2.2945,17z"),
        )
        assertNear(
            LatLon(48.8584, 2.2945),
            pos("https://maps.google.com/?q=48.8584,2.2945"),
        )
    }

    @Test
    fun `shortened share links fail loudly instead of silently`() {
        val r = DestinationParser.parse("https://maps.app.goo.gl/abc123XYZ", here)
        assertTrue("expected NeedsNetwork, got $r", r is ParseResult.NeedsNetwork)
        assertTrue((r as ParseResult.NeedsNetwork).reason.contains("shortened"))
    }

    @Test
    fun `full and short plus codes`() {
        val full = PlusCode.encode(LatLon(48.8584, 2.2945))
        assertNear(LatLon(48.8584, 2.2945), pos(full), 1e-3)

        val r = DestinationParser.parse(full.substring(4), here)
        assertTrue(r is ParseResult.Success)
        assertTrue((r as ParseResult.Success).usedReference)
        assertNear(LatLon(48.8584, 2.2945), r.position, 1e-3)
    }

    @Test
    fun `mgrs spaced and unspaced`() {
        assertNear(LatLon(38.8895, -77.0353), pos("18S UJ 23477 06483"), 1e-3)
        assertNear(LatLon(38.8895, -77.0353), pos("18SUJ2347706483"), 1e-3)
    }

    @Test
    fun `garbage is unrecognised rather than a wrong position`() {
        assertTrue(DestinationParser.parse("where are my keys", here) is ParseResult.Unrecognised)
        assertTrue(DestinationParser.parse("", here) is ParseResult.Unrecognised)
    }

    @Test
    fun `out of range values are rejected`() {
        val r = DestinationParser.parse("91.0, 2.0", here)
        assertTrue("got $r", r is ParseResult.Invalid)
    }
}

class HeadingArbiterTest {

    private fun fix(speed: Float?, course: Float?) = Fix(
        position = LatLon(48.85, 2.30),
        accuracyMeters = 5f,
        altitudeMeters = 40.0,
        speedMps = speed,
        bearingDeg = course,
        elapsedMillis = 0L,
    )

    @Test
    fun `gps course wins when moving`() {
        val (h, s) = HeadingArbiter.select(fix(10f, 42f), 300.0, true, HeadingSource.COMPASS)
        assertEquals(42.0, h!!, 1e-9)
        assertEquals(HeadingSource.GPS_COURSE, s)
    }

    @Test
    fun `compass wins when stationary`() {
        val (h, s) = HeadingArbiter.select(fix(0.2f, 42f), 300.0, true, HeadingSource.COMPASS)
        assertEquals(300.0, h!!, 1e-9)
        assertEquals(HeadingSource.COMPASS, s)
    }

    @Test
    fun `hysteresis stops flapping at walking pace`() {
        // 2.0 m/s: not enough to take over from the compass...
        val (_, cold) = HeadingArbiter.select(fix(2.0f, 42f), 300.0, true, HeadingSource.COMPASS)
        assertEquals(HeadingSource.COMPASS, cold)
        // ...but enough to keep GPS course once it is already in charge.
        val (_, warm) = HeadingArbiter.select(fix(2.0f, 42f), 300.0, true, HeadingSource.GPS_COURSE)
        assertEquals(HeadingSource.GPS_COURSE, warm)
    }

    @Test
    fun `unreliable magnetometer is reported not hidden`() {
        val (_, s) = HeadingArbiter.select(fix(0f, null), 300.0, false, HeadingSource.COMPASS)
        assertEquals(HeadingSource.COMPASS_UNCALIBRATED, s)
    }

    @Test
    fun `no sensors at all`() {
        val (h, s) = HeadingArbiter.select(null, null, false, HeadingSource.NONE)
        assertEquals(null, h)
        assertEquals(HeadingSource.NONE, s)
    }
}

class NavigationStateTest {

    private val destination = Destination("1", "Car", LatLon(48.8584, 2.2945))

    @Test
    fun `arrow is relative to where the phone is pointing`() {
        val state = NavigationState(
            fix = Fix(LatLon(48.85, 2.2945), 5f, null, null, null, 0L),
            destination = destination,
            headingDeg = 0.0,
        )
        // Destination is due north, phone points north -> arrow straight up.
        assertEquals(0.0, state.relativeArrowDeg!!, 0.5)

        val turned = state.copy(headingDeg = 90.0)
        // Phone now points east -> target is 90 deg to the left, i.e. 270.
        assertEquals(270.0, turned.relativeArrowDeg!!, 0.5)
    }

    @Test
    fun `quality flags`() {
        val good = NavigationState(
            fix = Fix(LatLon(48.85, 2.30), 5f, null, null, null, 0L),
            destination = destination,
        )
        assertEquals(FixQuality.GOOD, good.quality)
        assertEquals(FixQuality.POOR, good.copy(fix = good.fix!!.copy(accuracyMeters = 55f)).quality)
        assertEquals(FixQuality.STALE, good.copy(fixAgeMillis = 30_000).quality)
        assertEquals(FixQuality.NONE, NavigationState().quality)
    }

    // The first device test found the needle frozen indoors: the UI gated the whole arrow on
    // `fix != null`, so with no satellites there was no compass at all. These pin the rule that
    // a heading alone is enough to draw a moving needle.

    @Test
    fun `compass still points north with no fix and no destination`() {
        val s = NavigationState(headingDeg = 90.0)
        assertEquals(ArrowMode.NORTH, s.arrowMode)
        // Phone faces east, so north is 90 degrees to the left of the screen's up direction.
        assertEquals(270.0, s.arrowDeg!!, 1e-9)
    }

    @Test
    fun `needle tracks the phone while waiting for a fix`() {
        val angles = listOf(0.0, 45.0, 180.0, 359.0)
        val seen = angles.map { NavigationState(headingDeg = it).arrowDeg!! }
        assertEquals(listOf(0.0, 315.0, 180.0, 1.0), seen)
        assertEquals(angles.size, seen.distinct().size)
    }

    @Test
    fun `a destination with no fix still shows the compass rather than nothing`() {
        val s = NavigationState(destination = destination, headingDeg = 12.0)
        assertEquals(ArrowMode.NORTH, s.arrowMode)
        assertTrue(s.arrowDeg != null)
    }

    @Test
    fun `mode becomes TARGET once a fix arrives`() {
        val s = NavigationState(
            fix = Fix(LatLon(48.85, 2.2945), 5f, null, null, null, 0L),
            destination = destination,
            headingDeg = 0.0,
        )
        assertEquals(ArrowMode.TARGET, s.arrowMode)
        assertEquals(0.0, s.arrowDeg!!, 0.5)
    }

    @Test
    fun `no heading and no fix is the only case with no needle`() {
        assertEquals(ArrowMode.NONE, NavigationState().arrowMode)
        assertEquals(null, NavigationState().arrowDeg)
    }

    @Test
    fun `no destination means nothing to point at`() {
        val s = NavigationState(fix = Fix(LatLon(48.85, 2.30), 5f, null, null, null, 0L))
        assertEquals(null, s.distanceMeters)
        assertTrue(!s.isUsable)
    }
}
