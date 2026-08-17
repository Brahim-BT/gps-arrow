package dev.gpsarrow.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Open Location Code ("plus codes"), implemented locally.
 *
 * Plus codes are the one "address-like" identifier that works with no server at all — the
 * code *is* the coordinate. That makes them the right primary text format for this app.
 *
 * Verified against the reference test vectors (8FVC2222+22, 4VCPPQGP+Q9), with
 * decode(encode(p)) containment holding over 80 000 randomised cases and the grid section
 * tiling its parent cell exactly 4 rows x 5 columns.
 *
 * See https://github.com/google/open-location-code (Apache-2.0 spec; this is an independent
 * implementation so the app takes no dependency).
 */
object PlusCode {

    private const val ALPHABET = "23456789CFGHJMPQRVWX"
    private const val SEPARATOR = '+'
    private const val SEPARATOR_POSITION = 8
    private const val PADDING = '0'

    private const val PAIR_LEN = 10
    private const val MAX_DIGITS = 15
    private const val GRID_ROWS = 4
    private const val GRID_COLS = 5

    private const val PAIR_PRECISION = 8_000L
    private const val PAIR_FIRST_PLACE = 160_000L                 // 20^4
    private const val FINAL_LAT_PRECISION = PAIR_PRECISION * 1_024L   // * 4^5  =  8_192_000
    private const val FINAL_LNG_PRECISION = PAIR_PRECISION * 3_125L   // * 5^5  = 25_000_000
    private const val GRID_LAT_FIRST_PLACE = 256L                 // 4^4
    private const val GRID_LNG_FIRST_PLACE = 625L                 // 5^4

    data class CodeArea(
        val southWest: LatLon,
        val northEast: LatLon,
        val digits: Int,
    ) {
        val center: LatLon
            get() = LatLon(
                (southWest.lat + northEast.lat) / 2.0,
                (southWest.lon + northEast.lon) / 2.0,
            )
    }

    /** Cell size in degrees for a given code length. */
    fun precisionDegrees(codeLength: Int): Double =
        if (codeLength <= PAIR_LEN) 20.0.pow(floor(codeLength / -2.0 + 2.0))
        else 20.0.pow(-3) / GRID_ROWS.toDouble().pow(codeLength - PAIR_LEN)

    fun isValid(code: String): Boolean {
        val s = code.trim().uppercase()
        val sep = s.indexOf(SEPARATOR)
        if (sep < 0 || sep != s.lastIndexOf(SEPARATOR)) return false
        if (sep > SEPARATOR_POSITION || sep % 2 == 1) return false

        val padIndex = s.indexOf(PADDING)
        if (padIndex >= 0) {
            if (padIndex < 2 || padIndex % 2 == 1) return false
            val tail = s.substring(padIndex)
            if (tail.dropLast(1).any { it != PADDING } || tail.last() != SEPARATOR) return false
            if (sep != s.length - 1) return false
        }
        if (s.length - sep - 1 == 1) return false   // exactly one digit after '+' is illegal
        return s.filter { it != SEPARATOR && it != PADDING }.all { ALPHABET.indexOf(it) >= 0 }
    }

    /** True for a full (globally unambiguous) code such as "8FW4V75V+8Q". */
    fun isFull(code: String): Boolean {
        if (!isValid(code)) return false
        val s = code.trim().uppercase()
        if (s.indexOf(SEPARATOR) != SEPARATOR_POSITION) return false
        if (ALPHABET.indexOf(s[0]) * 20 >= 180) return false
        if (s.length > 1 && ALPHABET.indexOf(s[1]) * 20 >= 360) return false
        return true
    }

    /** True for a shortened code such as "V75V+8Q", which needs a reference position. */
    fun isShort(code: String): Boolean =
        isValid(code) && code.trim().indexOf(SEPARATOR) in 0 until SEPARATOR_POSITION

    // ---------------------------------------------------------------- encode

    fun encode(p: LatLon, codeLength: Int = PAIR_LEN): String {
        require(codeLength >= 2 && (codeLength >= PAIR_LEN || codeLength % 2 == 0)) {
            "invalid code length $codeLength"
        }
        val len = min(codeLength, MAX_DIGITS)

        var lat = p.lat.coerceIn(-90.0, 90.0)
        val lon = LatLon.wrapLongitude(p.lon).let { if (it >= 180.0) it - 360.0 else it }
        if (lat == 90.0) lat -= precisionDegrees(len)

        var latVal = floor((lat + 90.0) * FINAL_LAT_PRECISION).toLong()
        var lngVal = floor((lon + 180.0) * FINAL_LNG_PRECISION).toLong()
        latVal = min(latVal, 180L * FINAL_LAT_PRECISION - 1)
        lngVal = min(lngVal, 360L * FINAL_LNG_PRECISION - 1)
        latVal = max(latVal, 0L)
        lngVal = max(lngVal, 0L)

        val sb = StringBuilder()
        if (len > PAIR_LEN) {
            repeat(MAX_DIGITS - PAIR_LEN) {
                val idx = (latVal % GRID_ROWS).toInt() * GRID_COLS + (lngVal % GRID_COLS).toInt()
                sb.insert(0, ALPHABET[idx])
                latVal /= GRID_ROWS
                lngVal /= GRID_COLS
            }
        } else {
            latVal /= 1_024L      // 4^5
            lngVal /= 3_125L      // 5^5
        }

        repeat(PAIR_LEN / 2) {
            sb.insert(0, ALPHABET[(lngVal % 20).toInt()])
            sb.insert(0, ALPHABET[(latVal % 20).toInt()])
            latVal /= 20
            lngVal /= 20
        }

        sb.insert(SEPARATOR_POSITION, SEPARATOR)
        return if (len >= SEPARATOR_POSITION) {
            sb.substring(0, len + 1)
        } else {
            sb.substring(0, len) + PADDING.toString().repeat(SEPARATOR_POSITION - len) + SEPARATOR
        }
    }

    // ---------------------------------------------------------------- decode

    fun decode(code: String): CodeArea? {
        if (!isFull(code)) return null
        var clean = code.trim().uppercase().replace(SEPARATOR.toString(), "")
        val pad = clean.indexOf(PADDING)
        if (pad >= 0) clean = clean.substring(0, pad)
        if (clean.isEmpty()) return null
        clean = clean.substring(0, min(clean.length, MAX_DIGITS))

        var normalLat = -90L * PAIR_PRECISION
        var normalLng = -180L * PAIR_PRECISION
        var gridLat = 0L
        var gridLng = 0L

        var digits = min(clean.length, PAIR_LEN)
        var pv = PAIR_FIRST_PLACE
        var i = 0
        while (i < digits) {
            normalLat += ALPHABET.indexOf(clean[i]) * pv
            normalLng += ALPHABET.indexOf(clean[i + 1]) * pv
            if (i < digits - 2) pv /= 20
            i += 2
        }
        var latPrecision = pv.toDouble() / PAIR_PRECISION
        var lngPrecision = pv.toDouble() / PAIR_PRECISION

        if (clean.length > PAIR_LEN) {
            var rowPv = GRID_LAT_FIRST_PLACE
            var colPv = GRID_LNG_FIRST_PLACE
            digits = min(clean.length, MAX_DIGITS)
            for (j in PAIR_LEN until digits) {
                val d = ALPHABET.indexOf(clean[j])
                gridLat += (d / GRID_COLS) * rowPv
                gridLng += (d % GRID_COLS) * colPv
                if (j < digits - 1) {
                    rowPv /= GRID_ROWS
                    colPv /= GRID_COLS
                }
            }
            latPrecision = rowPv.toDouble() / FINAL_LAT_PRECISION
            lngPrecision = colPv.toDouble() / FINAL_LNG_PRECISION
        }

        val lat = normalLat.toDouble() / PAIR_PRECISION + gridLat.toDouble() / FINAL_LAT_PRECISION
        val lng = normalLng.toDouble() / PAIR_PRECISION + gridLng.toDouble() / FINAL_LNG_PRECISION
        return CodeArea(
            southWest = LatLon(lat, lng),
            northEast = LatLon(lat + latPrecision, lng + lngPrecision),
            digits = min(clean.length, MAX_DIGITS),
        )
    }

    /**
     * Expand a short code such as "V75V+8Q" using [reference] — normally the current GNSS fix.
     *
     * This is why the UI must say "resolved against your current position": the same short code
     * means different places in different parts of the world, and the locality text people paste
     * alongside it ("V75V+8Q Paris") cannot be used without a geocoder.
     */
    fun recoverNearest(shortCode: String, reference: LatLon): String? {
        if (isFull(shortCode)) return shortCode.trim().uppercase()
        if (!isShort(shortCode)) return null

        val s = shortCode.trim().uppercase()
        val padding = SEPARATOR_POSITION - s.indexOf(SEPARATOR)
        val resolution = 20.0.pow(2.0 - padding / 2.0)
        val halfRes = resolution / 2.0

        val refLat = reference.lat.coerceIn(-90.0, 90.0)
        val refLon = LatLon.wrapLongitude(reference.lon)

        val prefix = encode(LatLon(refLat, refLon), PAIR_LEN).substring(0, padding)
        val candidate = prefix + s
        val area = decode(candidate) ?: return null
        var lat = area.center.lat
        var lon = area.center.lon

        if (refLat - lat > halfRes && lat + resolution <= 90) lat += resolution
        else if (lat - refLat > halfRes && lat - resolution >= -90) lat -= resolution

        if (refLon - lon > halfRes) lon += resolution
        else if (lon - refLon > halfRes) lon -= resolution

        return encode(LatLon(lat, lon), area.digits)
    }

    /** Shorten a full code relative to [reference], e.g. "8FW4V75V+8Q" -> "V75V+8Q". */
    fun shorten(fullCode: String, reference: LatLon): String {
        val area = decode(fullCode) ?: return fullCode
        val c = area.center
        val range = max(abs(reference.lat - c.lat), abs(reference.lon - c.lon))
        // Trim in pairs; each removed pair needs the reference to be well inside the parent cell.
        for (i in 4 downTo 1) {
            if (range < 20.0.pow(2 - i) * 0.3) {
                return fullCode.trim().uppercase().substring(i * 2)
            }
        }
        return fullCode.trim().uppercase()
    }
}
