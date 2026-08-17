package dev.gpsarrow.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * UTM / MGRS on WGS84, offline.
 *
 * MGRS is the interchange format for SAR, military and a lot of back-country radio work,
 * and it decodes with no network at all, which is exactly what this app needs.
 *
 * Implementation is the standard Snyder series (the same one used by USGS/NGA docs).
 * Verified by decoding published 1 m references (18S UJ 23477 06483 for the Washington
 * Monument, 31U DQ 48251 11924 for the Eiffel Tower, and others) and re-encoding them back
 * to the identical string, plus a global sweep that round-trips to under a metre.
 *
 * Polar regions (|lat| > 84 / < -80) use UPS, which is deliberately NOT implemented —
 * [toMgrs] returns null there rather than emitting a wrong-but-plausible string.
 */
object Mgrs {

    private const val A = 6_378_137.0
    private const val F = 1.0 / 298.257223563
    private val E2 = F * (2 - F)
    private val EP2 = E2 / (1 - E2)
    private const val K0 = 0.9996

    private const val BANDS = "CDEFGHJKLMNPQRSTUVWX"
    private val COL_SETS = arrayOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")
    private const val ROW_LETTERS = "ABCDEFGHJKLMNPQRSTUV"

    data class Utm(val zone: Int, val northern: Boolean, val easting: Double, val northing: Double)

    // ---------------------------------------------------------------- zones & bands

    fun zoneFor(lat: Double, lon: Double): Int {
        var zone = floor((lon + 180.0) / 6.0).toInt() + 1
        if (zone > 60) zone = 60
        if (zone < 1) zone = 1
        // The two standard irregularities.
        if (lat >= 56.0 && lat < 64.0 && lon >= 3.0 && lon < 12.0) zone = 32
        if (lat >= 72.0 && lat < 84.0) {
            when {
                lon >= 0.0 && lon < 9.0 -> zone = 31
                lon >= 9.0 && lon < 21.0 -> zone = 33
                lon >= 21.0 && lon < 33.0 -> zone = 35
                lon >= 33.0 && lon < 42.0 -> zone = 37
            }
        }
        return zone
    }

    fun bandFor(lat: Double): Char = when {
        lat >= 84.0 -> 'X'
        lat < -80.0 -> 'C'
        else -> BANDS[floor((lat + 80.0) / 8.0).toInt().coerceIn(0, BANDS.length - 1)]
    }

    private fun centralMeridian(zone: Int) = (zone - 1) * 6.0 - 180.0 + 3.0

    // ---------------------------------------------------------------- UTM

    fun toUtm(p: LatLon, forcedZone: Int? = null): Utm {
        val zone = forcedZone ?: zoneFor(p.lat, p.lon)
        val lon0 = Math.toRadians(centralMeridian(zone))
        val phi = Math.toRadians(p.lat)

        val n = A / sqrt(1 - E2 * sin(phi) * sin(phi))
        val t = tan(phi) * tan(phi)
        val c = EP2 * cos(phi) * cos(phi)

        var dl = Math.toRadians(p.lon) - lon0
        while (dl > Math.PI) dl -= 2 * Math.PI
        while (dl <= -Math.PI) dl += 2 * Math.PI
        val a1 = dl * cos(phi)

        val m = A * (
            (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2 * E2 * E2 / 256) * phi -
                (3 * E2 / 8 + 3 * E2 * E2 / 32 + 45 * E2 * E2 * E2 / 1024) * sin(2 * phi) +
                (15 * E2 * E2 / 256 + 45 * E2 * E2 * E2 / 1024) * sin(4 * phi) -
                (35 * E2 * E2 * E2 / 3072) * sin(6 * phi)
            )

        val easting = K0 * n * (
            a1 + (1 - t + c) * a1.pow(3) / 6 +
                (5 - 18 * t + t * t + 72 * c - 58 * EP2) * a1.pow(5) / 120
            ) + 500_000.0

        var northing = K0 * (
            m + n * tan(phi) * (
                a1 * a1 / 2 +
                    (5 - t + 9 * c + 4 * c * c) * a1.pow(4) / 24 +
                    (61 - 58 * t + t * t + 600 * c - 330 * EP2) * a1.pow(6) / 720
                )
            )
        val northern = p.lat >= 0
        if (!northern) northing += 10_000_000.0

        return Utm(zone, northern, easting, northing)
    }

    fun fromUtm(u: Utm): LatLon {
        val x = u.easting - 500_000.0
        val y = if (u.northern) u.northing else u.northing - 10_000_000.0
        val lon0 = Math.toRadians(centralMeridian(u.zone))

        val m = y / K0
        val mu = m / (A * (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2 * E2 * E2 / 256))
        val e1 = (1 - sqrt(1 - E2)) / (1 + sqrt(1 - E2))

        val phi1 = mu +
            (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) +
            (21 * e1 * e1 / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
            (151 * e1.pow(3) / 96) * sin(6 * mu) +
            (1097 * e1.pow(4) / 512) * sin(8 * mu)

        val c1 = EP2 * cos(phi1) * cos(phi1)
        val t1 = tan(phi1) * tan(phi1)
        val n1 = A / sqrt(1 - E2 * sin(phi1) * sin(phi1))
        val r1 = A * (1 - E2) / (1 - E2 * sin(phi1) * sin(phi1)).pow(1.5)
        val d = x / (n1 * K0)

        val phi = phi1 - (n1 * tan(phi1) / r1) * (
            d * d / 2 -
                (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * EP2) * d.pow(4) / 24 +
                (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * EP2 - 3 * c1 * c1) * d.pow(6) / 720
            )
        val lambda = lon0 + (
            d - (1 + 2 * t1 + c1) * d.pow(3) / 6 +
                (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * EP2 + 24 * t1 * t1) * d.pow(5) / 120
            ) / cos(phi1)

        return LatLon(Math.toDegrees(phi), LatLon.wrapLongitude(Math.toDegrees(lambda)))
    }

    // ---------------------------------------------------------------- MGRS

    /**
     * @param digits precision per axis: 5 = 1 m, 4 = 10 m, 3 = 100 m, 2 = 1 km, 1 = 10 km.
     * @return e.g. "18SUJ2347706483", or null in the polar (UPS) regions.
     */
    fun toMgrs(p: LatLon, digits: Int = 5, spaced: Boolean = false): String? {
        if (p.lat > 84.0 || p.lat < -80.0) return null
        require(digits in 1..5) { "digits must be 1..5" }

        val u = toUtm(p)
        val band = bandFor(p.lat)
        val colIdx = (u.easting / 100_000.0).toInt()          // 1..8 inside a valid zone
        if (colIdx < 1 || colIdx > 8) return null
        val col = COL_SETS[(u.zone - 1) % 3][colIdx - 1]

        var rowIdx = ((u.northing % 2_000_000.0) / 100_000.0).toInt()
        if (u.zone % 2 == 0) rowIdx = (rowIdx + 5) % 20
        val row = ROW_LETTERS[rowIdx]

        val div = 10.0.pow(5 - digits)
        val e = ((u.easting % 100_000.0) / div).toLong()
        val n = ((u.northing % 100_000.0) / div).toLong()

        val ep = e.toString().padStart(digits, '0')
        val np = n.toString().padStart(digits, '0')
        return if (spaced) "${u.zone}$band $col$row $ep $np" else "${u.zone}$band$col$row$ep$np"
    }

    /** Accepts spaced or unspaced, upper or lower case. Returns null if it isn't MGRS. */
    fun fromMgrs(text: String): LatLon? {
        val s = text.filterNot { it.isWhitespace() }.uppercase()
        if (s.length < 5) return null

        var i = 0
        while (i < s.length && s[i].isDigit()) i++
        if (i !in 1..2 || s.length < i + 3) return null
        val zone = s.substring(0, i).toIntOrNull() ?: return null
        if (zone !in 1..60) return null

        val band = s[i]
        val col = s[i + 1]
        val row = s[i + 2]
        if (BANDS.indexOf(band) < 0) return null
        val colIdx = COL_SETS[(zone - 1) % 3].indexOf(col)
        if (colIdx < 0) return null
        var rowIdx = ROW_LETTERS.indexOf(row)
        if (rowIdx < 0) return null

        val rest = s.substring(i + 3)
        if (rest.isNotEmpty() && (rest.length % 2 != 0 || !rest.all { it.isDigit() })) return null
        val half = rest.length / 2
        val div = 10.0.pow(5 - half)
        // Return the CENTRE of the designated square, not its south-west corner. An MGRS
        // reference names a square, so the centre is the best single point — and it makes
        // decode -> encode exactly stable, which the corner does not: rounding in the UTM
        // series lands a hair below the boundary and truncation then loses a metre.
        val eRem = (if (half > 0) rest.substring(0, half).toLong() * div else 0.0) + div / 2.0
        val nRem = (if (half > 0) rest.substring(half).toLong() * div else 0.0) + div / 2.0

        val easting = (colIdx + 1) * 100_000.0 + eRem
        if (zone % 2 == 0) rowIdx = ((rowIdx - 5) % 20 + 20) % 20
        val base = rowIdx * 100_000.0 + nRem
        val northern = band >= 'N'

        // 100 km row letters repeat every 2 000 000 m. Choose the repetition whose
        // resulting latitude actually falls inside the band the code declares — exact,
        // rather than the usual "nearest approximate northing" heuristic which breaks
        // in the tall X band.
        val bi = BANDS.indexOf(band)
        val latLo = bi * 8.0 - 80.0
        val latHi = if (band == 'X') 84.0 else latLo + 8.0

        var fallback: LatLon? = null
        var fallbackErr = Double.MAX_VALUE
        var k = 0
        while (k < 6) {
            val cand = base + k * 2_000_000.0
            if (cand > 10_000_000.0) break
            val p = fromUtm(Utm(zone, northern, easting, cand))
            if (p.lat >= latLo - 0.05 && p.lat < latHi + 0.05) return p
            val err = minOf(abs(p.lat - latLo), abs(p.lat - latHi))
            if (err < fallbackErr) { fallbackErr = err; fallback = p }
            k++
        }
        return fallback
    }
}
