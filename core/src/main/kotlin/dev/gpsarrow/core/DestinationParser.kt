package dev.gpsarrow.core

/**
 * Everything the user can paste, turned into a position — with no network.
 *
 * There is no geocoder in this app, so there is no "search". Every input path is a parser,
 * and the one thing that genuinely cannot work offline (shortened map share links) gets an
 * explicit, honest failure so the UI can explain it instead of silently doing nothing.
 */
sealed interface ParseResult {

    data class Success(
        val position: LatLon,
        val format: Format,
        val label: String? = null,
        /** True when a reference position was needed (short plus codes). */
        val usedReference: Boolean = false,
    ) : ParseResult

    /** Recognised, but genuinely impossible offline. [reason] is user-facing. */
    data class NeedsNetwork(val reason: String) : ParseResult

    /** Recognised the shape but the values are out of range. */
    data class Invalid(val reason: String) : ParseResult

    data object Unrecognised : ParseResult

    enum class Format { DECIMAL, DMS, GEO_URI, PLUS_CODE, PLUS_CODE_SHORT, MGRS, UTM, MAP_URL }
}

object DestinationParser {

    private val SHORTENERS = listOf(
        "maps.app.goo.gl", "goo.gl/maps", "g.co/kgs", "bit.ly", "tinyurl.com",
        "osm.org/go", "w3w.co", "what3words.com",
    )

    /**
     * True when [input] is one number rather than a whole coordinate.
     *
     * The editor uses this to decide whether a keystroke should be handed to [parse] at all.
     * It lives here, in `:core`, because getting it wrong corrupts coordinates silently and
     * that deserves a unit test rather than a Compose lambda.
     *
     * The comma normalisation is the whole point. `String.toDoubleOrNull` only accepts a dot,
     * so on a device whose locale writes decimals with a comma — French and Arabic both do —
     * "48,8" is not a number by that test, fell through to [parse], and came back as the pair
     * (48, 8): [DECIMAL] reads a comma as the pair separator. A user typing a latitude had
     * their coordinate replaced with a point 5000 km away on the first digit after the comma.
     *
     * A real pair still fails this test and reaches the parser, because normalising
     * "48,85840, 2,29450" gives "48.85840. 2.29450", which is not a number either.
     */
    fun isSingleComponent(input: String): Boolean =
        input.trim().replace(',', '.').toDoubleOrNull() != null

    /**
     * @param reference current position; required only to expand short plus codes.
     */
    fun parse(input: String, reference: LatLon? = null): ParseResult {
        val raw = input.trim()
        if (raw.isEmpty()) return ParseResult.Unrecognised

        SHORTENERS.firstOrNull { raw.contains(it, ignoreCase = true) }?.let { host ->
            return ParseResult.NeedsNetwork(
                "$host links are shortened — only their server knows where they point. " +
                    "Open the link once while you have signal, then copy the full address " +
                    "or the coordinates from it.",
            )
        }

        geoUri(raw)?.let { return it }
        mapUrl(raw)?.let { return it }
        plusCode(raw, reference)?.let { return it }
        mgrs(raw)?.let { return it }
        utm(raw)?.let { return it }
        // decimal BEFORE dms. "33.8568 S, 151.2153 E" is decimal degrees with hemisphere
        // suffixes, but the DMS matcher treats a bare space as a degree separator, so it
        // happily read "568 S" as 568 degrees. The decimal pattern is fully anchored and
        // cannot mis-claim a real DMS string, so it is safe to try first.
        decimal(raw)?.let { return it }
        dms(raw)?.let { return it }

        return ParseResult.Unrecognised
    }

    // ---------------------------------------------------------------- geo: URI (RFC 5870)

    private val GEO_URI = Regex(
        """^geo:\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE,
    )

    private fun geoUri(s: String): ParseResult? {
        val m = GEO_URI.find(s) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        // A `q=` parameter can carry a friendlier label.
        val label = Regex("""[?&]q=([^&]*)""", RegexOption.IGNORE_CASE)
            .find(s)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() && !it.first().isDigit() && it.first() != '-' }
            ?.replace('+', ' ')
        return validate(lat, lon, ParseResult.Format.GEO_URI, label)
    }

    // ---------------------------------------------------------------- map URLs containing coords

    private val URL_PATTERNS = listOf(
        // OpenStreetMap: #map=17/48.8584/2.2945
        Regex("""#map=\d+(?:\.\d+)?/(-?\d+\.?\d*)/(-?\d+\.?\d*)""", RegexOption.IGNORE_CASE),
        // OSM marker: ?mlat=48.8584&mlon=2.2945
        Regex("""[?&]mlat=(-?\d+\.?\d*)[^#]*?[?&]mlon=(-?\d+\.?\d*)""", RegexOption.IGNORE_CASE),
        // Google: /@48.8584,2.2945,17z
        Regex("""[/@](-?\d+\.\d+),(-?\d+\.\d+)(?:,\d+(?:\.\d+)?z)?""", RegexOption.IGNORE_CASE),
        // Google / Apple: ?q=48.8584,2.2945  or  ?ll=48.8584,2.2945
        Regex("""[?&](?:q|ll|daddr|sll|center)=(-?\d+\.\d+),(-?\d+\.\d+)""", RegexOption.IGNORE_CASE),
        // Google place data: !3d48.8584!4d2.2945
        Regex("""!3d(-?\d+\.\d+)!4d(-?\d+\.\d+)"""),
    )

    private fun mapUrl(s: String): ParseResult? {
        if (!s.contains("://") && !s.startsWith("www.", ignoreCase = true)) return null
        for (p in URL_PATTERNS) {
            val m = p.find(s) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lon = m.groupValues[2].toDoubleOrNull() ?: continue
            val r = validate(lat, lon, ParseResult.Format.MAP_URL, null)
            if (r is ParseResult.Success) return r
        }
        return ParseResult.NeedsNetwork(
            "That link doesn't contain coordinates, so it can't be resolved without a connection. " +
                "Open it while online and copy the coordinates.",
        )
    }

    // ---------------------------------------------------------------- plus codes

    private val PLUS_TOKEN = Regex("""\b([23456789CFGHJMPQRVWX0]{2,8}\+[23456789CFGHJMPQRVWX]{0,7})""",
        RegexOption.IGNORE_CASE)

    private fun plusCode(s: String, reference: LatLon?): ParseResult? {
        val token = PLUS_TOKEN.find(s.uppercase())?.groupValues?.get(1) ?: return null
        val label = s.uppercase().substringAfter(token).trim().takeIf { it.isNotBlank() }

        if (PlusCode.isFull(token)) {
            val area = PlusCode.decode(token) ?: return ParseResult.Invalid("Malformed plus code.")
            return ParseResult.Success(area.center, ParseResult.Format.PLUS_CODE, label)
        }
        if (PlusCode.isShort(token)) {
            if (reference == null) {
                return ParseResult.Invalid(
                    "\"$token\" is a shortened plus code. It needs your current position to " +
                        "resolve, and there is no position fix yet.",
                )
            }
            val full = PlusCode.recoverNearest(token, reference)
                ?: return ParseResult.Invalid("Could not resolve \"$token\".")
            val area = PlusCode.decode(full) ?: return ParseResult.Invalid("Malformed plus code.")
            return ParseResult.Success(
                area.center, ParseResult.Format.PLUS_CODE_SHORT, label, usedReference = true,
            )
        }
        return null
    }

    // ---------------------------------------------------------------- MGRS / UTM

    // The numeric part may itself be split by whitespace: "18S UJ 23477 06483" is the normal
    // way people write a 1 m reference. The old pattern allowed space only BEFORE the digits,
    // so it captured "18S UJ 23477", saw five digits, and rejected the whole thing as having
    // an odd digit count. `(?:\s*\d){2,10}` lets whitespace appear between any two digits.
    private val MGRS_TOKEN = Regex(
        """\b(\d{1,2}\s*[C-HJ-NP-X]\s*[A-HJ-NP-Z]{2}(?:\s*\d){2,10})""",
        RegexOption.IGNORE_CASE,
    )

    private fun mgrs(s: String): ParseResult? {
        val token = MGRS_TOKEN.find(s.uppercase())?.groupValues?.get(1) ?: return null
        val compact = token.filterNot { it.isWhitespace() }
        // Reject odd digit counts up front so "31UDQ123" doesn't silently mis-parse.
        val digits = compact.dropWhile { it.isDigit() }.drop(3)
        if (digits.length % 2 != 0) return ParseResult.Invalid("MGRS needs an even number of digits.")
        val p = Mgrs.fromMgrs(compact) ?: return ParseResult.Invalid("Not a valid MGRS reference.")
        return validate(p.lat, p.lon, ParseResult.Format.MGRS, null)
    }

    private val UTM_TOKEN = Regex(
        """\b(\d{1,2})\s*([C-HJ-NP-X])\s+(\d{5,7}(?:\.\d+)?)\s*[mM]?\s*[eE]?\s*[, ]\s*(\d{1,7}(?:\.\d+)?)\s*[mM]?\s*[nN]?\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun utm(s: String): ParseResult? {
        val m = UTM_TOKEN.find(s.uppercase()) ?: return null
        val zone = m.groupValues[1].toIntOrNull() ?: return null
        val band = m.groupValues[2].first()
        val easting = m.groupValues[3].toDoubleOrNull() ?: return null
        val northing = m.groupValues[4].toDoubleOrNull() ?: return null
        if (zone !in 1..60) return ParseResult.Invalid("UTM zone must be 1-60.")
        val p = Mgrs.fromUtm(Mgrs.Utm(zone, band >= 'N', easting, northing))
        return validate(p.lat, p.lon, ParseResult.Format.UTM, null)
    }

    // ---------------------------------------------------------------- DMS / DM

    private val DMS = Regex(
        """([NSEW])?\s*(\d{1,3})\s*[°d:\s]\s*(\d{1,2}(?:\.\d+)?)?\s*['′m:]?\s*(\d{1,2}(?:\.\d+)?)?\s*["″s]?\s*([NSEW])?""",
        RegexOption.IGNORE_CASE,
    )

    private fun dms(s: String): ParseResult? {
        val normalized = s.replace('\u2212', '-').replace('\u2032', '\'').replace('\u2033', '"')
        // Require a real angular marker, not merely a hemisphere letter. Gating on N/S/E/W
        // alone let plain decimal degrees into this matcher, where `[°d:\s]` treats a bare
        // space as a degree separator — so "33.8568 S, 151.2153 E" parsed as 568 degrees.
        if (normalized.none { it in "°'\"" }) return null

        val matches = DMS.findAll(normalized)
            .filter { it.value.isNotBlank() && it.groupValues[2].isNotEmpty() }
            .take(2).toList()
        if (matches.size < 2) return null

        val parts = matches.map { m ->
            val hemi = (m.groupValues[1].ifEmpty { m.groupValues[5] }).uppercase().firstOrNull()
            val d = m.groupValues[2].toDouble()
            val mm = m.groupValues[3].toDoubleOrNull() ?: 0.0
            val ss = m.groupValues[4].toDoubleOrNull() ?: 0.0
            var v = d + mm / 60.0 + ss / 3600.0
            if (hemi == 'S' || hemi == 'W') v = -v
            hemi to v
        }

        val latPart = parts.firstOrNull { it.first == 'N' || it.first == 'S' } ?: parts[0]
        val lonPart = parts.firstOrNull { it.first == 'E' || it.first == 'W' } ?: parts[1]
        if (latPart === lonPart) return null
        return validate(latPart.second, lonPart.second, ParseResult.Format.DMS, null)
    }

    // ---------------------------------------------------------------- decimal degrees

    private val DECIMAL = Regex(
        """^\s*([NS])?\s*(-?\d{1,3}(?:[.,]\d+)?)\s*°?\s*([NS])?\s*[,;/\s]\s*([EW])?\s*(-?\d{1,3}(?:[.,]\d+)?)\s*°?\s*([EW])?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun decimal(s: String): ParseResult? {
        val m = DECIMAL.find(s.replace('\u2212', '-')) ?: return null
        fun signed(value: String, a: String, b: String, negative: String): Double? {
            val v = value.replace(',', '.').toDoubleOrNull() ?: return null
            val hemi = (a.ifEmpty { b }).uppercase()
            return if (hemi == negative) -kotlin.math.abs(v) else v
        }
        val lat = signed(m.groupValues[2], m.groupValues[1], m.groupValues[3], "S") ?: return null
        val lon = signed(m.groupValues[5], m.groupValues[4], m.groupValues[6], "W") ?: return null
        return validate(lat, lon, ParseResult.Format.DECIMAL, null)
    }

    // ----------------------------------------------------------------

    private fun validate(
        lat: Double,
        lon: Double,
        format: ParseResult.Format,
        label: String?,
    ): ParseResult = when {
        lat.isNaN() || lon.isNaN() -> ParseResult.Invalid("Not a number.")
        lat < -90 || lat > 90 -> ParseResult.Invalid("Latitude must be between -90 and 90.")
        lon < -180 || lon > 180 -> ParseResult.Invalid("Longitude must be between -180 and 180.")
        else -> ParseResult.Success(LatLon(lat, lon), format, label?.take(60))
    }
}
