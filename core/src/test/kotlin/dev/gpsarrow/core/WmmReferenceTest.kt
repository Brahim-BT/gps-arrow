package dev.gpsarrow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [Wmm] against the NOAA reference implementation, at real coordinates.
 *
 * GENERATED — do not hand-edit the numbers. The coefficient block below is the public-domain
 * WMM2025 file from NOAA NCEI verbatim (header: epoch 2025.0, released 11/13/2024), and every
 * expected declination was produced by geomag70.c (via the `pygeomag` port of it) at the same
 * coordinate, altitude and date. Regenerate with `python3 verification/wmm_reference.py --generate`.
 *
 * Why this exists: the previous test for this class asserted only that the declination was
 * finite and under 180 degrees, which stayed green while the evaluator was 170 degrees wrong.
 * A bound is not a reference value. The four non-African points are here to catch a hemisphere
 * or quadrant sign flip, which is the failure mode the region points alone would miss.
 */
class WmmReferenceTest {

    private data class Case(
        val name: String,
        val lat: Double,
        val lon: Double,
        val altMeters: Double,
        val declinationDeg: Double,
    )

    private val cases = listOf(
        Case("Tangier", 35.7595, -5.834, 10.0, 0.05031443483867547),
        Case("Casablanca", 33.5731, -7.5898, 50.0, -0.48806708986610353),
        Case("Marrakech", 31.6295, -7.9811, 466.0, -0.672049634461097),
        Case("Figuig", 32.1092, -1.2306, 900.0, 1.027797533751955),
        Case("Dakhla", 23.6848, -15.9579, 10.0, -3.826079062825997),
        Case("Eastern desert", 22.5, -5.5, 350.0, -0.7224718213035819),
        Case("Nouadhibou", 20.941, -17.0347, 10.0, -4.562668732997441),
        Case("Nouakchott", 18.0858, -15.9785, 5.0, -4.595488718661336),
        Case("Nema", 16.6089, -7.2568, 240.0, -1.959213437698489),
        Case("London", 51.5074, -0.1278, 11.0, 1.183814668460281),
        Case("Sydney", -33.8688, 151.2093, 58.0, 12.823416712834861),
        Case("Anchorage", 61.2181, -149.9003, 31.0, 14.02575876261327),
        Case("Quito", -0.1807, -78.4678, 2850.0, -5.067784309993424),
    )

    /** Decimal year for 18 August 2026, the date the expectations were generated at. */
    private val epochOfTest = Wmm.decimalYearOf(2026, 8, 18)

    private val model: Wmm by lazy {
        val parsed = Wmm.parse(WMM2025_COF)
        assertNotNull("the bundled WMM2025 coefficients must parse", parsed)
        parsed!!
    }

    @Test
    fun `model metadata matches the coefficient file header`() {
        assertEquals("WMM-2025", model.name)
        assertEquals(0.0, model.yearsFromEpoch(2025.0), 1e-12)
        assertTrue(model.isValidFor(epochOfTest))
    }

    @Test
    fun `declination matches the NOAA reference implementation`() {
        // 1e-6 deg is about 4 milliarcseconds. The two implementations agree far more closely
        // than that; the tolerance is here for cross-platform floating point, not for slack.
        cases.forEach { c ->
            val got = model.declination(LatLon(c.lat, c.lon), c.altMeters, epochOfTest)
            assertEquals(c.name, c.declinationDeg, got, 1e-6)
        }
    }

    @Test
    fun `declination in the deployment region is a small negative number, not a large one`() {
        // A plain-language guard against the exact regression this test was written for: any
        // sign or normalisation fault throws the answer tens or hundreds of degrees off, and
        // across Morocco and Mauritania the true value is within a few degrees of north.
        cases.take(9).forEach { c ->
            val got = model.declination(LatLon(c.lat, c.lon), c.altMeters, epochOfTest)
            assertTrue("${c.name} declination was $got", abs(got) < 6.0)
        }
    }

    @Test
    fun `the field is physically plausible everywhere in the region`() {
        // Total intensity at the Earth's surface runs roughly 22000-67000 nT globally; the
        // magnitude is rotation-invariant, so this catches a bad expansion even when a sign
        // fault would leave the declination looking innocuous.
        var lat = 14.5
        while (lat <= 36.0) {
            var lon = -17.5
            while (lon <= -0.5) {
                val f = model.calculate(LatLon(lat, lon), 0.0, epochOfTest)
                assertTrue(
                    "intensity ${f.totalIntensityNt} at $lat,$lon",
                    f.totalIntensityNt in 20_000.0..70_000.0,
                )
                assertTrue("inclination ${f.inclinationDeg} at $lat,$lon", abs(f.inclinationDeg) < 90.0)
                lon += 2.0
            }
            lat += 2.0
        }
    }

    private companion object {
        /**
         * WMM2025, from NOAA NCEI. Public domain (17 U.S.C. 105); not subject to copyright.
         * Test data only — the app ships no coefficient file and uses the framework model.
         */
        val WMM2025_COF = """
        2025.0            WMM-2025     11/13/2024
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
        5  0    -233.2       0.0        0.6        0.0
        5  1     368.9      45.4        1.4       -0.5
        5  2     187.2     220.2        0.0        2.2
        5  3    -138.7    -122.9        0.6        0.4
        5  4    -142.0      43.0        2.2        1.7
        5  5      20.9     106.1        0.9        1.9
        6  0      64.4       0.0       -0.2        0.0
        6  1      63.8     -18.4       -0.4        0.3
        6  2      76.9      16.8        0.9       -1.6
        6  3    -115.7      48.8        1.2       -0.4
        6  4     -40.9     -59.8       -0.9        0.9
        6  5      14.9      10.9        0.3        0.7
        6  6     -60.7      72.7        0.9        0.9
        7  0      79.5       0.0       -0.0        0.0
        7  1     -77.0     -48.9       -0.1        0.6
        7  2      -8.8     -14.4       -0.1        0.5
        7  3      59.3      -1.0        0.5       -0.8
        7  4      15.8      23.4       -0.1        0.0
        7  5       2.5      -7.4       -0.8       -1.0
        7  6     -11.1     -25.1       -0.8        0.6
        7  7      14.2      -2.3        0.8       -0.2
        8  0      23.2       0.0       -0.1        0.0
        8  1      10.8       7.1        0.2       -0.2
        8  2     -17.5     -12.6        0.0        0.5
        8  3       2.0      11.4        0.5       -0.4
        8  4     -21.7      -9.7       -0.1        0.4
        8  5      16.9      12.7        0.3       -0.5
        8  6      15.0       0.7        0.2       -0.6
        8  7     -16.8      -5.2       -0.0        0.3
        8  8       0.9       3.9        0.2        0.2
        9  0       4.6       0.0       -0.0        0.0
        9  1       7.8     -24.8       -0.1       -0.3
        9  2       3.0      12.2        0.1        0.3
        9  3      -0.2       8.3        0.3       -0.3
        9  4      -2.5      -3.3       -0.3        0.3
        9  5     -13.1      -5.2        0.0        0.2
        9  6       2.4       7.2        0.3       -0.1
        9  7       8.6      -0.6       -0.1       -0.2
        9  8      -8.7       0.8        0.1        0.4
        9  9     -12.9      10.0       -0.1        0.1
        10  0      -1.3       0.0        0.1        0.0
        10  1      -6.4       3.3        0.0        0.0
        10  2       0.2       0.0        0.1       -0.0
        10  3       2.0       2.4        0.1       -0.2
        10  4      -1.0       5.3       -0.0        0.1
        10  5      -0.6      -9.1       -0.3       -0.1
        10  6      -0.9       0.4        0.0        0.1
        10  7       1.5      -4.2       -0.1        0.0
        10  8       0.9      -3.8       -0.1       -0.1
        10  9      -2.7       0.9       -0.0        0.2
        10 10      -3.9      -9.1       -0.0       -0.0
        11  0       2.9       0.0        0.0        0.0
        11  1      -1.5       0.0       -0.0       -0.0
        11  2      -2.5       2.9        0.0        0.1
        11  3       2.4      -0.6        0.0       -0.0
        11  4      -0.6       0.2        0.0        0.1
        11  5      -0.1       0.5       -0.1       -0.0
        11  6      -0.6      -0.3        0.0       -0.0
        11  7      -0.1      -1.2       -0.0        0.1
        11  8       1.1      -1.7       -0.1       -0.0
        11  9      -1.0      -2.9       -0.1        0.0
        11 10      -0.2      -1.8       -0.1        0.0
        11 11       2.6      -2.3       -0.1        0.0
        12  0      -2.0       0.0        0.0        0.0
        12  1      -0.2      -1.3        0.0       -0.0
        12  2       0.3       0.7       -0.0        0.0
        12  3       1.2       1.0       -0.0       -0.1
        12  4      -1.3      -1.4       -0.0        0.1
        12  5       0.6      -0.0       -0.0       -0.0
        12  6       0.6       0.6        0.1       -0.0
        12  7       0.5      -0.1       -0.0       -0.0
        12  8      -0.1       0.8        0.0        0.0
        12  9      -0.4       0.1        0.0       -0.0
        12 10      -0.2      -1.0       -0.1       -0.0
        12 11      -1.3       0.1       -0.0        0.0
        12 12      -0.7       0.2       -0.1       -0.1
        999999999999999999999999999999999999999999999999
        999999999999999999999999999999999999999999999999
        """.trimIndent()
    }
}
