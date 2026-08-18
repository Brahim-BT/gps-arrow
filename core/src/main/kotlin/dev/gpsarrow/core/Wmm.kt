package dev.gpsarrow.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * World Magnetic Model evaluator — magnetic declination with no network, anywhere.
 *
 * Why this exists when Android ships [android.hardware.GeomagneticField]: the framework's
 * coefficients are baked into the OS image at build time (the platform docs still describe
 * WMM-2020), so on an old or never-updated device you silently inherit a stale model. This
 * class reads a coefficient file you ship in assets, so the model epoch is yours to control.
 *
 * The coefficients are NOT bundled — see README, "Magnetic declination". Drop the public-domain
 * `WMM.COF` from NOAA NCEI into `app/src/main/assets/geomag/` and the app picks it up; without
 * it, the app falls back to the framework implementation.
 *
 * Standard degree-12 spherical harmonic expansion in geodetic coordinates, per the WMM
 * technical report.
 *
 * **History worth keeping.** Until August 2026 this class returned a declination roughly 170
 * degrees wrong. Two independent faults: the Legendre normalisation used a convention that did
 * not match the recursion it was paired with, and the geocentric-to-geodetic rotation had the
 * wrong sign on the north component. Neither was caught because no `WMM.COF` ships in the app,
 * so [Declination] always fell through to the framework model and this code never ran — and
 * because the only test asserted that the answer was finite and under 180 degrees, which is
 * true of almost any wrong answer. It is now checked against the NOAA reference implementation
 * at real coordinates; see `WmmReferenceTest`. If you touch the expansion, that is the test
 * that has to stay green.
 */
class Wmm private constructor(
    private val epoch: Double,
    private val modelName: String,
    private val nMax: Int,
    private val g: Array<DoubleArray>,
    private val h: Array<DoubleArray>,
    private val gDot: Array<DoubleArray>,
    private val hDot: Array<DoubleArray>,
) {

    private val f = 1.0 / RECIPROCAL_FLATTENING
    private val e2 = f * (2 - f)

    data class Field(
        /** Degrees east of true north. Add this to a magnetic bearing to get a true bearing. */
        val declinationDeg: Double,
        val inclinationDeg: Double,
        /** Total intensity, nanotesla. */
        val totalIntensityNt: Double,
    )

    val name: String get() = modelName

    /** Years since the model epoch; outside `[0, 5]` the model is being extrapolated. */
    fun yearsFromEpoch(decimalYear: Double): Double = decimalYear - epoch

    fun isValidFor(decimalYear: Double): Boolean = yearsFromEpoch(decimalYear) in -0.5..5.5

    /**
     * @param altitudeMeters height above the WGS84 ellipsoid. GNSS altitude is fine; so is 0.
     * @param decimalYear e.g. 2026.63. See [decimalYearOf].
     */
    fun calculate(p: LatLon, altitudeMeters: Double, decimalYear: Double): Field {
        val dt = decimalYear - epoch
        val phi = Math.toRadians(p.lat)
        val lambda = Math.toRadians(p.lon)
        val hKm = altitudeMeters / 1000.0

        // Geodetic -> geocentric spherical.
        val sinPhi = sin(phi)
        val cosPhi = cos(phi)
        val rc = A_SEMI_MAJOR / sqrt(1 - e2 * sinPhi * sinPhi)
        val p1 = (rc + hKm) * cosPhi
        val z1 = (rc * (1 - e2) + hKm) * sinPhi
        val r = sqrt(p1 * p1 + z1 * z1)
        val sinPhiPrime = z1 / r
        val cosPhiPrime = p1 / r
        val phiPrime = atan2(z1, p1)

        // Schmidt semi-normalised associated Legendre functions and their derivatives.
        val pnm = Array(nMax + 1) { DoubleArray(nMax + 1) }
        val dPnm = Array(nMax + 1) { DoubleArray(nMax + 1) }
        legendre(sinPhiPrime, cosPhiPrime, pnm, dPnm)

        val cosM = DoubleArray(nMax + 1)
        val sinM = DoubleArray(nMax + 1)
        for (m in 0..nMax) {
            cosM[m] = cos(m * lambda)
            sinM[m] = sin(m * lambda)
        }

        // Field in the GEOCENTRIC spherical frame, named as in the WMM technical report:
        // bt is southward (increasing colatitude), bp eastward, br outward. Keeping the
        // report's names here is what makes the rotation below checkable by eye.
        var bt = 0.0
        var bp = 0.0
        var br = 0.0
        val aOverR = EARTH_RADIUS / r

        for (n in 1..nMax) {
            val f1 = Math.pow(aOverR, (n + 2).toDouble())
            for (m in 0..n) {
                val gnm = g[n][m] + dt * gDot[n][m]
                val hnm = h[n][m] + dt * hDot[n][m]
                val t1 = gnm * cosM[m] + hnm * sinM[m]
                val t2 = gnm * sinM[m] - hnm * cosM[m]
                bt -= f1 * t1 * dPnm[n][m]
                br += (n + 1) * f1 * t1 * pnm[n][m]
                bp += m * f1 * t2 * pnm[n][m] /
                    (if (cosPhiPrime == 0.0) 1e-12 else cosPhiPrime)
            }
        }

        // Rotate geocentric -> geodetic. [alpha] is the angle between the two verticals, and
        // x/y/z come out as north / east / down.
        //
        // This rotation used to be written with the opposite sign on both terms of x and on
        // the bt term of z, which put the declination 180 degrees out — see the class comment.
        val alpha = phi - phiPrime
        val ca = cos(alpha)
        val sa = sin(alpha)
        val x = -bt * ca - br * sa
        val y = bp
        val z = bt * sa - br * ca

        val horizontal = sqrt(x * x + y * y)
        return Field(
            declinationDeg = Math.toDegrees(atan2(y, x)),
            inclinationDeg = Math.toDegrees(atan2(z, horizontal)),
            totalIntensityNt = sqrt(horizontal * horizontal + z * z),
        )
    }

    /** Convenience: just the number the compass needs. */
    fun declination(p: LatLon, altitudeMeters: Double, decimalYear: Double): Double =
        calculate(p, altitudeMeters, decimalYear).declinationDeg

    private fun legendre(
        sinPhi: Double,
        cosPhi: Double,
        p: Array<DoubleArray>,
        dp: Array<DoubleArray>,
    ) {
        p[0][0] = 1.0
        dp[0][0] = 0.0
        for (n in 1..nMax) {
            for (m in 0..n) {
                when {
                    n == m -> {
                        p[n][m] = cosPhi * p[n - 1][m - 1]
                        dp[n][m] = cosPhi * dp[n - 1][m - 1] + sinPhi * p[n - 1][m - 1]
                    }
                    n == 1 && m == 0 -> {
                        p[n][m] = sinPhi * p[n - 1][m]
                        dp[n][m] = sinPhi * dp[n - 1][m] - cosPhi * p[n - 1][m]
                    }
                    else -> {
                        if (n > 1 && m > n - 2) {
                            p[n - 2][m] = 0.0
                            dp[n - 2][m] = 0.0
                        }
                        val k = ((n - 1) * (n - 1) - m * m).toDouble() /
                            ((2 * n - 1) * (2 * n - 3)).toDouble()
                        p[n][m] = sinPhi * p[n - 1][m] - k * p[n - 2][m]
                        dp[n][m] = sinPhi * dp[n - 1][m] - cosPhi * p[n - 1][m] - k * dp[n - 2][m]
                    }
                }
            }
        }
        // No normalisation here, deliberately. This recursion produces the UNnormalised
        // associated Legendre functions, and [Loader.parse] has already pushed the Schmidt
        // factors into the coefficients — which is where the reference implementation puts
        // them, and it is the only way the two halves compose.
        //
        // The previous version applied sqrt(2*(n-m)!/(n+m)!) to p and dp at this point. That is
        // the factor for converting standard unnormalised functions to Schmidt, but this
        // recursion's sectoral seed is p[n][n] = sin(theta) * p[n-1][n-1], which omits the
        // (2n-1)!! that the standard functions carry — so the factor was being applied to
        // something it did not describe. The result was a field of roughly the right magnitude
        // pointing in the wrong direction.
    }

    companion object Loader {

        /** Reference ellipsoid and geomagnetic reference radius, per the WMM technical report. */
        private const val A_SEMI_MAJOR = 6_378.137          // km
        private const val RECIPROCAL_FLATTENING = 298.257223563
        private const val EARTH_RADIUS = 6_371.2            // km, geomagnetic reference sphere

        /**
         * Parse a NOAA/NGA `.COF` file.
         *
         * Format: a header line `EPOCH  MODELNAME  RELEASEDATE`, then rows of
         * `n m g h g_dot h_dot`, terminated by a line of `9`s.
         *
         * @return null if the text isn't a usable COF file, so the caller can fall back.
         */
        fun parse(text: String, nMax: Int = 12): Wmm? {
            val lines = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
            if (lines.isEmpty()) return null

            val header = lines.first().split(Regex("\\s+"))
            val epoch = header.getOrNull(0)?.toDoubleOrNull() ?: return null
            val modelName = header.getOrNull(1) ?: "WMM"

            val g = Array(nMax + 1) { DoubleArray(nMax + 1) }
            val h = Array(nMax + 1) { DoubleArray(nMax + 1) }
            val gd = Array(nMax + 1) { DoubleArray(nMax + 1) }
            val hd = Array(nMax + 1) { DoubleArray(nMax + 1) }

            var rows = 0
            for (line in lines.drop(1)) {
                if (line.startsWith("9999")) break
                val t = line.split(Regex("\\s+"))
                if (t.size < 6) continue
                val n = t[0].toIntOrNull() ?: continue
                val m = t[1].toIntOrNull() ?: continue
                if (n !in 1..nMax || m !in 0..n) continue
                g[n][m] = t[2].toDoubleOrNull() ?: 0.0
                h[n][m] = t[3].toDoubleOrNull() ?: 0.0
                gd[n][m] = t[4].toDoubleOrNull() ?: 0.0
                hd[n][m] = t[5].toDoubleOrNull() ?: 0.0
                rows++
            }
            if (rows < 10) return null

            // Convert the Schmidt semi-normalised Gauss coefficients in the file to the
            // unnormalised form [legendre] expects, folding the factors into the coefficients
            // once at load time rather than into P and dP on every evaluation. This is exactly
            // what the reference implementation (geomag70.c) does, and the two must agree:
            // the recursion and the normalisation are a matched pair, not independent choices.
            val snorm = Array(nMax + 1) { DoubleArray(nMax + 1) }
            snorm[0][0] = 1.0
            for (n in 1..nMax) {
                snorm[n][0] = snorm[n - 1][0] * (2 * n - 1).toDouble() / n.toDouble()
                var j = 2.0
                for (m in 0..n) {
                    if (m > 0) {
                        val flnmj = (n - m + 1).toDouble() * j / (n + m).toDouble()
                        snorm[n][m] = snorm[n][m - 1] * sqrt(flnmj)
                        j = 1.0
                        h[n][m] *= snorm[n][m]
                        hd[n][m] *= snorm[n][m]
                    }
                    g[n][m] *= snorm[n][m]
                    gd[n][m] *= snorm[n][m]
                }
            }
            return Wmm(epoch, modelName, nMax, g, h, gd, hd)
        }

        /** Fractional year, e.g. 17 Aug 2026 -> 2026.629. */
        fun decimalYearOf(year: Int, month: Int, dayOfMonth: Int): Double {
            val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
            val cumulative = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
            var day = cumulative[(month - 1).coerceIn(0, 11)] + dayOfMonth
            if (leap && month > 2) day += 1
            return year + (day - 1) / (if (leap) 366.0 else 365.0)
        }
    }
}
