package dev.gpsarrow.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.gpsarrow.R
import dev.gpsarrow.core.BearingReadout
import dev.gpsarrow.core.CoordinateFormat
import dev.gpsarrow.core.DestinationSort
import dev.gpsarrow.core.DistanceReadout
import dev.gpsarrow.core.Format
import dev.gpsarrow.core.HeadingSource
import dev.gpsarrow.core.LengthUnit
import dev.gpsarrow.core.NavigationState
import dev.gpsarrow.core.ParseProblem
import dev.gpsarrow.core.ParseResult
import dev.gpsarrow.core.SpeedReadout
import dev.gpsarrow.core.SpeedUnit
import java.util.Locale

/**
 * Where `:core`'s structured values become words.
 *
 * `:core` deliberately hands up numbers, enums and indices rather than sentences, so every
 * translated string in the app is reachable from `strings.xml` and none of them are hiding in
 * a pure-Kotlin module that cannot see resources. This file is the whole of the seam.
 */

/**
 * The locale to format numbers in, taken from the context's own configuration rather than the
 * stored preference, so it always matches whatever the platform actually applied.
 *
 * Latin digits are pinned here as well as inside `Format` — see `Format.latinDigitLocale` for
 * why one Arabic build reads the same in both deployment countries.
 */
@Composable
fun rememberNumberLocale(): Locale {
    val context = LocalContext.current
    return remember(context) { context.numberLocale() }
}

fun Context.numberLocale(): Locale {
    val configured = resources.configuration.locales.let {
        if (it.isEmpty) Locale.getDefault() else it[0]
    }
    return Format.latinDigitLocale(configured)
}

/**
 * Wrap [text] in a Unicode LTR isolate so bidirectional reordering cannot rearrange it.
 *
 * A coordinate is a Latin-digit run sitting inside an Arabic paragraph, and the separators in
 * "48.85840, 2.29450" are bidi-neutral characters. Without an isolate the surrounding
 * right-to-left context can pull the neutrals — and with them the apparent order of the two
 * numbers — the wrong way round. A latitude and longitude that swap places on screen is the
 * quietest possible way to send someone to the wrong continent, so every coordinate that is
 * concatenated into translated text goes through here.
 *
 * U+2066 LEFT-TO-RIGHT ISOLATE / U+2069 POP DIRECTIONAL ISOLATE. Isolates rather than the older
 * embedding marks because they also stop the run affecting the text around it.
 */
fun ltrIsolate(text: String): String = "\u2066" + text + "\u2069"

// ---------------------------------------------------------------------- distance and bearing

fun DistanceReadout.text(context: Context): String {
    val withUnit = context.getString(unit.formatRes(), value)
    return if (isLowerBound) context.getString(R.string.distance_under, withUnit) else withUnit
}

private fun LengthUnit.formatRes(): Int = when (this) {
    LengthUnit.METRES -> R.string.unit_metres
    LengthUnit.KILOMETRES -> R.string.unit_kilometres
    LengthUnit.FEET -> R.string.unit_feet
    LengthUnit.MILES -> R.string.unit_miles
    LengthUnit.NAUTICAL_MILES -> R.string.unit_nautical_miles
}

fun SpeedReadout.text(context: Context): String {
    val withUnit = context.getString(unit.formatRes(), value)
    return if (isLowerBound) context.getString(R.string.distance_under, withUnit) else withUnit
}

private fun SpeedUnit.formatRes(): Int = when (this) {
    SpeedUnit.KMH -> R.string.unit_kmh
    SpeedUnit.MPH -> R.string.unit_mph
    SpeedUnit.KNOTS -> R.string.unit_knots
}

/**
 * A saved point's timestamp, in the reader's language but always in Latin digits.
 *
 * The platform formatters honour the user's 12/24-hour setting, which a hand-rolled pattern
 * would not, so they do the work and [Format.latinDigits] enforces the app-wide digit rule on
 * the way out. Unlike the quick-save *name*, which is stored and must never change language,
 * this is rendered fresh on every frame and follows whatever language is active.
 */
fun formatTimestamp(context: Context, millis: Long): String {
    if (millis <= 0L) return ""
    val date = java.util.Date(millis)
    val d = android.text.format.DateFormat.getDateFormat(context).format(date)
    val t = android.text.format.DateFormat.getTimeFormat(context).format(date)
    return ltrIsolate(Format.latinDigits("$d, $t"))
}

fun BearingReadout.text(context: Context): String {
    val points = context.resources.getStringArray(R.array.compass_points)
    return context.getString(R.string.bearing_format, degrees, points[pointIndex])
}

// ---------------------------------------------------------------------- list and editor

fun DestinationSort.labelRes(): Int = when (this) {
    DestinationSort.NAME_ASC -> R.string.sort_name_asc
    DestinationSort.NAME_DESC -> R.string.sort_name_desc
    DestinationSort.DISTANCE_NEAREST -> R.string.sort_nearest
    DestinationSort.DISTANCE_FARTHEST -> R.string.sort_farthest
    DestinationSort.ADDED_NEWEST -> R.string.sort_newest
    DestinationSort.ADDED_OLDEST -> R.string.sort_oldest
    DestinationSort.RECENTLY_USED -> R.string.sort_recently_used
}

/** Short mode label for the position band, e.g. "Plus code". */
fun CoordinateFormat.labelRes(): Int = when (this) {
    CoordinateFormat.DECIMAL -> R.string.coord_format_decimal
    CoordinateFormat.DMS -> R.string.coord_format_dms
    CoordinateFormat.PLUS_CODE -> R.string.coord_format_plus_code
    CoordinateFormat.MGRS -> R.string.coord_format_mgrs
}

fun ParseResult.Format.labelRes(): Int = when (this) {
    ParseResult.Format.DECIMAL -> R.string.format_decimal
    ParseResult.Format.DMS -> R.string.format_dms
    ParseResult.Format.GEO_URI -> R.string.format_geo_uri
    ParseResult.Format.PLUS_CODE -> R.string.format_plus_code
    ParseResult.Format.PLUS_CODE_SHORT -> R.string.format_plus_code_short
    ParseResult.Format.MGRS -> R.string.format_mgrs
    ParseResult.Format.UTM -> R.string.format_utm
    ParseResult.Format.MAP_URL -> R.string.format_map_url
}

/** Renders a parse failure, substituting the host or code the parser handed back. */
fun ParseProblem.text(context: Context, arg: String?): String = when (this) {
    ParseProblem.SHORTENED_LINK ->
        context.getString(R.string.parse_shortened_link, arg.orEmpty())

    ParseProblem.LINK_WITHOUT_COORDINATES ->
        context.getString(R.string.parse_link_without_coordinates)

    ParseProblem.MALFORMED_PLUS_CODE -> context.getString(R.string.parse_malformed_plus_code)

    ParseProblem.SHORT_PLUS_CODE_NEEDS_FIX ->
        context.getString(R.string.parse_short_plus_code_needs_fix, arg.orEmpty())

    ParseProblem.PLUS_CODE_UNRESOLVABLE ->
        context.getString(R.string.parse_plus_code_unresolvable, arg.orEmpty())

    ParseProblem.MGRS_ODD_DIGIT_COUNT -> context.getString(R.string.parse_mgrs_odd_digit_count)
    ParseProblem.MGRS_INVALID -> context.getString(R.string.parse_mgrs_invalid)
    ParseProblem.UTM_ZONE_OUT_OF_RANGE -> context.getString(R.string.parse_utm_zone_out_of_range)
    ParseProblem.NOT_A_NUMBER -> context.getString(R.string.parse_not_a_number)
    ParseProblem.LATITUDE_OUT_OF_RANGE -> context.getString(R.string.parse_latitude_out_of_range)
    ParseProblem.LONGITUDE_OUT_OF_RANGE -> context.getString(R.string.parse_longitude_out_of_range)
}

// ---------------------------------------------------------------------- heading chip

/**
 * The heading-source chip, including the caveat the source alone cannot express: before the
 * first fix there is no declination, so "Compass" is showing magnetic north.
 */
fun NavigationState.headingChipRes(): Int = when {
    headingIsMagnetic && headingSource == HeadingSource.COMPASS ->
        R.string.heading_compass_magnetic

    else -> when (headingSource) {
        HeadingSource.COMPASS -> R.string.heading_compass
        HeadingSource.GPS_COURSE -> R.string.heading_gps_course
        HeadingSource.COMPASS_UNCALIBRATED -> R.string.heading_compass_uncalibrated
        HeadingSource.NONE -> R.string.heading_none
    }
}
