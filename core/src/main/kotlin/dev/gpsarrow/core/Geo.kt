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
import java.util.Locale

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

/**
 * The unit a [DistanceReadout] came out in. The *word* for it lives in the UI layer, because
 * it is translated and because Arabic does not necessarily put it on the same side of the
 * number as English does.
 */
enum class LengthUnit { METRES, KILOMETRES, FEET, MILES, NAUTICAL_MILES }

/**
 * A distance, split into a number already formatted for the reader and the unit it is in.
 *
 * `:core` deliberately stops here rather than returning "120 m". Composing the final string is
 * the UI layer's job: it owns the translations, and it is the only layer that can know whether
 * this language writes the unit after the number, before it, or with a different separator.
 */
data class DistanceReadout(
    val value: String,
    val unit: LengthUnit,
    /** True when [value] is a bound the fix cannot see past, not a measurement. */
    val isLowerBound: Boolean = false,
)

/** A bearing, split for the same reason as [DistanceReadout]. */
data class BearingReadout(
    /** Zero-padded degrees, e.g. "047". */
    val degrees: String,
    /** Index into the sixteen-point compass rose, N = 0, clockwise. */
    val pointIndex: Int,
)

/** The unit a [SpeedReadout] came out in. */
enum class SpeedUnit { KMH, MPH, KNOTS }

/** A speed, split for the same reason as [DistanceReadout]. */
data class SpeedReadout(
    val value: String,
    val unit: SpeedUnit,
    /** True when [value] is a bound the receiver cannot see past, not a measurement. */
    val isLowerBound: Boolean = false,
)

object Format {

    /**
     * [base] with the Latin numbering system pinned via the BCP-47 `nu` extension.
     *
     * **Latin digits everywhere, in every language, is a product decision — do not "fix" it.**
     * CLDR gives `ar-MA` the `latn` numbering system and `ar-MR` `arab`, so the two countries
     * this app is deployed in disagree with each other, and the picker offers a language rather
     * than a region: "follow the locale" has no defined answer. One Arabic build that reads the
     * same in Nouakchott and Casablanca is worth more than matching each country's default, and
     * it keeps prose consistent with coordinates, which must stay Latin because they are an
     * interchange value.
     *
     * This is the polite half of the guarantee. [latinDigits] is the other half, because
     * whether a given device's formatter honours the `nu` keyword is an ICU-version question
     * and this app would rather not have the answer vary by handset.
     */
    fun latinDigitLocale(base: Locale): Locale = runCatching {
        Locale.Builder().setLocale(base).setUnicodeLocaleKeyword("nu", "latn").build()
    }.getOrDefault(base)

    /**
     * Force every decimal digit in [text] to ASCII, whatever script the formatter used.
     *
     * The enforcing half of the rule above: [latinDigitLocale] asks, this guarantees. A locale
     * that arrives here without the extension — `ar-MR` straight from the device, say — still
     * comes out in Latin digits, which is exactly the regression this exists to prevent.
     * Separators are left alone, so French keeps its decimal comma.
     *
     * Public because text formatted elsewhere has to come through it too — a timestamp from
     * `android.text.format.DateFormat`, for instance, which this module cannot produce itself
     * but which lands on the same screen and must not be the one place Arabic-Indic digits
     * survive.
     */
    fun latinDigits(text: String): String {
        if (text.all { it.code < 0x0660 }) return text
        return buildString(text.length) {
            for (ch in text) {
                val d = if (ch in '0'..'9') null else ch.digitToIntOrNull()
                append(if (d != null) '0' + d else ch)
            }
        }
    }

    /**
     * A single number formatted for [locale] with the Latin-digit rule applied.
     *
     * Exposed so callers outside this object — the diagnostics panel, chip labels — go through
     * the same rule instead of reimplementing it and drifting. Anything numeric the user sees
     * should come through here or through [distance] / [bearing].
     */
    fun number(pattern: String, locale: Locale, value: Any): String =
        latinDigits(String.format(latinDigitLocale(locale), pattern, value))

    // Both of these pin the numbering system before formatting, exactly as [number] does.
    // They did not, originally, and that half-applied rule is worth naming: on a JDK that
    // honours the `nu` keyword, `ar` then also yields a full stop rather than U+066B ARABIC
    // DECIMAL SEPARATOR, so the Arabic build reads "1.2 كم" like the other two. The digits
    // were never at risk — [latinDigits] catches those either way — but the separator was
    // silently following the region, which is the same split the whole rule exists to close.
    private fun fixed(value: Double, decimals: Int, locale: Locale): String =
        latinDigits(String.format(latinDigitLocale(locale), "%.${decimals}f", value))

    private fun whole(value: Long, locale: Locale): String =
        latinDigits(String.format(latinDigitLocale(locale), "%d", value))

    /**
     * Human distance with precision that degrades sensibly with magnitude.
     *
     * Every ladder has a floor, and the floor is not zero. Rounding to the nearest 10 m makes
     * anything under 5 m print as "0 m" — a claim that you are standing on the point, from a
     * receiver that cannot tell 0 m from 8 m. Below the resolution of the format the honest
     * answer is a bound, not a number, and [DistanceReadout.isLowerBound] says which it is.
     * [DistanceUnits.NAUTICAL] keeps two decimals throughout, so its floor is one hundredth of
     * a nautical mile rather than ten metres.
     */
    fun distance(
        meters: Double,
        units: DistanceUnits = DistanceUnits.METRIC,
        locale: Locale = Locale.ROOT,
    ): DistanceReadout = when (units) {
        DistanceUnits.METRIC -> when {
            meters < 10 ->
                DistanceReadout(whole(10L, locale), LengthUnit.METRES, isLowerBound = true)

            meters < 1_000 ->
                DistanceReadout(whole((meters / 10.0).roundToInt() * 10L, locale), LengthUnit.METRES)

            meters < 100_000 ->
                DistanceReadout(fixed(meters / 1_000.0, 1, locale), LengthUnit.KILOMETRES)

            else ->
                DistanceReadout(whole((meters / 1_000.0).roundToLong(), locale), LengthUnit.KILOMETRES)
        }

        DistanceUnits.IMPERIAL -> {
            val feet = meters * 3.280839895
            val miles = meters / 1609.344
            when {
                feet < 30 ->
                    DistanceReadout(whole(30L, locale), LengthUnit.FEET, isLowerBound = true)

                feet < 1_000 ->
                    DistanceReadout(whole((feet / 10.0).roundToInt() * 10L, locale), LengthUnit.FEET)

                miles < 100 -> DistanceReadout(fixed(miles, 1, locale), LengthUnit.MILES)
                else -> DistanceReadout(whole(miles.roundToLong(), locale), LengthUnit.MILES)
            }
        }

        DistanceUnits.NAUTICAL -> {
            val nm = meters / 1852.0
            when {
                nm < 0.01 -> DistanceReadout(
                    fixed(0.01, 2, locale), LengthUnit.NAUTICAL_MILES, isLowerBound = true,
                )

                nm < 100 -> DistanceReadout(fixed(nm, 2, locale), LengthUnit.NAUTICAL_MILES)
                else -> DistanceReadout(whole(nm.roundToLong(), locale), LengthUnit.NAUTICAL_MILES)
            }
        }
    }

    /**
     * Speed, with the same floor logic as [distance] and for the same reason.
     *
     * A GNSS receiver reports a speed while you are standing still, and it is noise — the same
     * noise that makes a stationary course-over-ground useless, which `HeadingArbiter` already
     * gates on. Printing "1.3 km/h" for someone who is not moving is a measurement the hardware
     * cannot support, so below [SPEED_FLOOR_KMH] this returns the floor as a bound instead.
     */
    fun speed(
        metresPerSecond: Float,
        units: DistanceUnits = DistanceUnits.METRIC,
        locale: Locale = Locale.ROOT,
    ): SpeedReadout {
        val kmh = metresPerSecond * 3.6
        val below = kmh < SPEED_FLOOR_KMH
        return when (units) {
            DistanceUnits.METRIC -> SpeedReadout(
                fixed(if (below) SPEED_FLOOR_KMH else kmh, 1, locale), SpeedUnit.KMH, below,
            )

            DistanceUnits.IMPERIAL -> SpeedReadout(
                fixed(
                    if (below) SPEED_FLOOR_KMH / MILES_PER_KM else kmh / MILES_PER_KM, 1, locale,
                ),
                SpeedUnit.MPH,
                below,
            )

            DistanceUnits.NAUTICAL -> SpeedReadout(
                fixed(
                    if (below) SPEED_FLOOR_KMH / KNOTS_PER_KMH else kmh / KNOTS_PER_KMH, 1, locale,
                ),
                SpeedUnit.KNOTS,
                below,
            )
        }
    }

    /** Walking pace. Below this a GNSS speed is noise rather than movement. */
    const val SPEED_FLOOR_KMH = 5.0
    private const val MILES_PER_KM = 1.609344
    private const val KNOTS_PER_KMH = 1.852

    /** Degrees and a compass point, for the UI to join up in its own word order. */
    fun bearing(deg: Double, locale: Locale = Locale.ROOT): BearingReadout {
        val d = Geo.normalizeDegrees(deg)
        return BearingReadout(
            degrees = latinDigits(String.format(latinDigitLocale(locale), "%03.0f", d)),
            pointIndex = compassPointIndex(d),
        )
    }

    /**
     * Index into the sixteen-point rose, N = 0 and clockwise from there.
     *
     * An index rather than a letter because "NE" is not what this is called in every language,
     * and the abbreviations are a translated string array in the UI layer.
     */
    fun compassPointIndex(deg: Double): Int =
        ((Geo.normalizeDegrees(deg) / 22.5) + 0.5).toInt() % 16

    /**
     * Decimal degrees, 5dp ~= 1.1 m — more than a GNSS fix justifies.
     *
     * [Locale.ROOT], not the device locale, and deliberately so: this is an interchange format.
     * `String.format` with a comma-decimal locale renders 48.8584 as "48,85840", which turns
     * "48,85840, 2,29450" into a string with three commas and no unambiguous split — fine for
     * this app's own parser, which accepts both separators, and unreadable to every other tool
     * the user might paste it into. Coordinates are written with a dot everywhere: on charts,
     * in aviation, on handheld GPS units. [dms] is the same format for the same reason.
     */
    fun decimal(p: LatLon): String = String.format(Locale.ROOT, "%.5f, %.5f", p.lat, p.lon)

    /**
     * One coordinate component for an editable text field. 6dp, about 0.11 m.
     *
     * [Locale.ROOT] for the same reason as [decimal], plus one of its own: the list row and the
     * editor must agree. Formatting the row with a dot and the edit field with the device's
     * comma shows the same point two different ways on two adjacent screens.
     */
    fun coordinate(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

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
        return String.format(Locale.ROOT, "%d°%02d'%05.2f\"%s", d, m, s, hemi)
    }
}
