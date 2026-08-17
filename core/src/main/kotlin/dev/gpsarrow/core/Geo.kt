package dev.gpsarrow.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** Mean Earth radius (IUGG), metres. */
const val EARTH_RADIUS_M = 6_371_008.8

/**
 * A WGS84 position. Latitude is clamped, longitude is wrapped, so a LatLon is always valid.
 */
data class LatLon(val lat: Double, val lon: Double) {
    init {
        require(!lat.isNaN() && !lon.isNaN()) { "lat/lon must be finite" }
    }

    fun normalized(): LatLon = LatLon(lat.coerceIn(-90.0, 90.0), wrapLongitude(lon))

    companion object {
        fun wrapLongitude(lon: Double): Double {
            var l = lon
            while (l > 180.0) l -= 360.0
            while (l < -180.0) l += 360.0
            return l
        }
    }
}

object Geo {

    private const val DEG = Math.PI / 180.0
    private const val RAD = 180.0 / Math.PI

    /**
     * Great-circle distance in metres (haversine).
     *
     * A sphere is deliberate: the ~0.3% error against WGS84 is an order of magnitude
     * smaller than a typical GNSS fix, and unlike Vincenty this never fails to converge
     * on near-antipodal pairs. See BUILD_PLAN.md 2.4.
     */
    fun distanceMeters(a: LatLon, b: LatLon): Double {
        val phi1 = a.lat * DEG
        val phi2 = b.lat * DEG
        val dPhi = (b.lat - a.lat) * DEG
        val dLambda = (b.lon - a.lon) * DEG

        val sinDPhi = sin(dPhi / 2.0)
        val sinDLambda = sin(dLambda / 2.0)
        val h = sinDPhi * sinDPhi + cos(phi1) * cos(phi2) * sinDLambda * sinDLambda
        return 2.0 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /**
     * Initial bearing (forward azimuth) from [a] to [b], degrees clockwise from true north,
     * in `[0, 360)`.
     *
     * Note this is the *initial* bearing: over long distances the great circle curves, so
     * re-computing every fix (which the app does) is what keeps the arrow correct.
     */
    fun initialBearingDegrees(a: LatLon, b: LatLon): Double {
        val phi1 = a.lat * DEG
        val phi2 = b.lat * DEG
        val dLambda = (b.lon - a.lon) * DEG

        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return normalizeDegrees(atan2(y, x) * RAD)
    }

    /** Point at [distanceM] along [bearingDeg] from [from]. Used by tests and by pin-drop preview. */
    fun destination(from: LatLon, bearingDeg: Double, distanceM: Double): LatLon {
        val delta = distanceM / EARTH_RADIUS_M
        val theta = bearingDeg * DEG
        val phi1 = from.lat * DEG
        val lambda1 = from.lon * DEG

        val sinPhi2 = sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta)
        val phi2 = asin(sinPhi2.coerceIn(-1.0, 1.0))
        val lambda2 = lambda1 + atan2(
            sin(theta) * sin(delta) * cos(phi1),
            cos(delta) - sin(phi1) * sinPhi2,
        )
        return LatLon(phi2 * RAD, LatLon.wrapLongitude(lambda2 * RAD))
    }

    /**
     * Wrap any angle into `[0, 360)`.
     *
     * The `+ 0.0` is not redundant. `-0.0 % 360.0` is `-0.0`, and `-0.0 < 0` is false under
     * IEEE 754, so a heading of exactly 0 would otherwise leak negative zero out of the API.
     * That bites twice: `Double.equals(-0.0, 0.0)` is false (different bit patterns), and
     * `"%.1f".format(-0.0)` renders "-0.0", so the diagnostics panel would show "-0.0°".
     * Adding positive zero collapses both zeros to `+0.0` and changes nothing else.
     */
    fun normalizeDegrees(deg: Double): Double {
        val d = deg % 360.0
        return if (d < 0) d + 360.0 else d + 0.0
    }

    /**
     * Shortest signed angular difference `to - from`, in `(-180, 180]`.
     * This is what the arrow animates along, so it never spins the long way round.
     */
    fun angleDeltaDegrees(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }
}

/**
 * Circular (wrap-safe) exponential smoothing for headings.
 *
 * Filtering degrees directly makes a compass jump 359 -> 0 through 180. Filtering the unit
 * vector and taking atan2 does the right thing. [alpha] is the weight of each new sample.
 */
class CircularSmoother(private val alpha: Double = 0.15) {
    private var sx = 0.0
    private var sy = 0.0
    private var seeded = false

    fun reset() {
        sx = 0.0; sy = 0.0; seeded = false
    }

    fun update(degrees: Double): Double = updateWith(degrees, alpha)

    /**
     * Frame-rate independent variant: the weight is derived from how much time actually
     * elapsed, so the response feels the same whether the sensor delivers at 50 Hz or 10 Hz.
     *
     * A fixed per-sample alpha silently becomes a much slower filter on a device that delivers
     * fewer samples than you assumed, which looks exactly like a stuck needle.
     *
     * @param timeConstantSeconds time to cover ~63% of a step change.
     */
    fun update(degrees: Double, dtSeconds: Double, timeConstantSeconds: Double): Double {
        val a = when {
            timeConstantSeconds <= 0.0 -> 1.0
            dtSeconds <= 0.0 -> alpha
            // Clamp dt so a long pause (screen off, app backgrounded) snaps rather than
            // producing a wild single-step jump computed from a huge elapsed time.
            else -> 1.0 - exp(-dtSeconds.coerceAtMost(0.5) / timeConstantSeconds)
        }
        return updateWith(degrees, a.coerceIn(0.0, 1.0))
    }

    private fun updateWith(degrees: Double, a: Double): Double {
        val r = degrees * Math.PI / 180.0
        val x = cos(r)
        val y = sin(r)
        if (!seeded) {
            sx = x; sy = y; seeded = true
        } else {
            sx += a * (x - sx)
            sy += a * (y - sy)
        }
        return Geo.normalizeDegrees(atan2(sy, sx) * 180.0 / Math.PI)
    }

    val value: Double?
        get() = if (seeded) Geo.normalizeDegrees(atan2(sy, sx) * 180.0 / Math.PI) else null
}

enum class DistanceUnits { METRIC, IMPERIAL, NAUTICAL }

object Format {

    /** Human distance with precision that degrades sensibly with magnitude. */
    fun distance(meters: Double, units: DistanceUnits = DistanceUnits.METRIC): String = when (units) {
        DistanceUnits.METRIC -> when {
            meters < 1_000 -> "${(meters / 10.0).roundToInt() * 10} m"
            meters < 100_000 -> String.format("%.1f km", meters / 1_000.0)
            else -> "${(meters / 1_000.0).roundToLong()} km"
        }

        DistanceUnits.IMPERIAL -> {
            val feet = meters * 3.280839895
            val miles = meters / 1609.344
            when {
                feet < 1_000 -> "${(feet / 10.0).roundToInt() * 10} ft"
                miles < 100 -> String.format("%.1f mi", miles)
                else -> "${miles.roundToLong()} mi"
            }
        }

        DistanceUnits.NAUTICAL -> {
            val nm = meters / 1852.0
            if (nm < 100) String.format("%.2f NM", nm) else "${nm.roundToLong()} NM"
        }
    }

    /** "047deg NE" style bearing label. */
    fun bearing(deg: Double): String {
        val d = Geo.normalizeDegrees(deg)
        return String.format("%03.0f° %s", d, compassPoint(d))
    }

    private val POINTS = arrayOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )

    fun compassPoint(deg: Double): String {
        val idx = ((Geo.normalizeDegrees(deg) / 22.5) + 0.5).toInt() % 16
        return POINTS[idx]
    }

    /** Decimal degrees, 5dp ~= 1.1 m — more than a GNSS fix justifies. */
    fun decimal(p: LatLon): String = String.format("%.5f, %.5f", p.lat, p.lon)

    /** Degrees / minutes / seconds, the format most paper maps and radios use. */
    fun dms(p: LatLon): String = "${dmsComponent(p.lat, true)} ${dmsComponent(p.lon, false)}"

    private fun dmsComponent(value: Double, isLat: Boolean): String {
        val hemi = if (isLat) (if (value < 0) "S" else "N") else (if (value < 0) "W" else "E")
        var v = abs(value)
        var d = v.toInt()
        v = (v - d) * 60.0
        var m = v.toInt()
        var s = (v - m) * 60.0
        // Guard against 59.9999" rounding up to 60".
        if (s >= 59.995) { s = 0.0; m += 1 }
        if (m >= 60) { m = 0; d += 1 }
        return String.format("%d°%02d'%05.2f\"%s", d, m, s, hemi)
    }
}
