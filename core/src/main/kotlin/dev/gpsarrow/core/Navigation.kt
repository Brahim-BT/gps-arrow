package dev.gpsarrow.core

/** A GNSS fix, stripped of anything Android-specific so it can be unit-tested. */
data class Fix(
    val position: LatLon,
    val accuracyMeters: Float,
    val altitudeMeters: Double?,
    val speedMps: Float?,
    /** Course over ground, degrees true. Null when the device isn't moving meaningfully. */
    val bearingDeg: Float?,
    val elapsedMillis: Long,
    val satellitesUsed: Int = 0,
    val satellitesVisible: Int = 0,
    val provider: String = "gps",
)

/** Where the heading number came from. Surfaced in the UI — never hide this from the user. */
enum class HeadingSource {
    /** Rotation-vector sensor, corrected to true north. */
    COMPASS,

    /** GPS course over ground. Already true north, immune to magnetic interference. */
    GPS_COURSE,

    /** Compass, but the magnetometer says it is unreliable. */
    COMPASS_UNCALIBRATED,

    NONE,
}

enum class FixQuality { NONE, STALE, POOR, GOOD }

/** What the needle is pointing at right now. */
enum class ArrowMode {
    /** At the saved destination. Needs both a fix and a destination. */
    TARGET,

    /** At true north. The fallback whenever a bearing can't be computed — still a useful compass. */
    NORTH,

    /** No heading source at all: no compass hardware and not moving. */
    NONE,
}

/**
 * The single source of truth the arrow screen and the notification both render.
 */
data class NavigationState(
    val fix: Fix? = null,
    val destination: Destination? = null,
    val headingDeg: Double? = null,
    val headingSource: HeadingSource = HeadingSource.NONE,
    val declinationDeg: Double? = null,
    val fixAgeMillis: Long = 0,
    val isAcquiring: Boolean = true,
) {
    val distanceMeters: Double?
        get() {
            val f = fix ?: return null
            val d = destination ?: return null
            return Geo.distanceMeters(f.position, d.position)
        }

    val bearingToDestinationDeg: Double?
        get() {
            val f = fix ?: return null
            val d = destination ?: return null
            return Geo.initialBearingDegrees(f.position, d.position)
        }

    /** What the arrow glyph is rotated by: bearing to target relative to where the phone points. */
    val relativeArrowDeg: Double?
        get() {
            val b = bearingToDestinationDeg ?: return null
            val h = headingDeg ?: return b
            return Geo.normalizeDegrees(b - h)
        }

    /**
     * What the needle is currently able to show.
     *
     * The app is a compass first and a navigator second. Losing the GNSS fix — indoors, in a
     * car park, in the first minute after launch — must not blank the needle, because a
     * north-pointing compass is still useful and still proves the hardware works.
     */
    val arrowMode: ArrowMode
        get() = when {
            destination != null && fix != null && bearingToDestinationDeg != null -> ArrowMode.TARGET
            headingDeg != null -> ArrowMode.NORTH
            else -> ArrowMode.NONE
        }

    /**
     * Rotation for the needle, in screen space.
     *
     * In [ArrowMode.NORTH] it points at true north (`-heading`), which is exactly what a
     * compass needle does. In [ArrowMode.TARGET] it points at the destination.
     */
    val arrowDeg: Double?
        get() = when (arrowMode) {
            ArrowMode.TARGET -> Geo.normalizeDegrees(
                (bearingToDestinationDeg ?: 0.0) - (headingDeg ?: 0.0),
            )
            ArrowMode.NORTH -> Geo.normalizeDegrees(-(headingDeg ?: 0.0))
            ArrowMode.NONE -> null
        }

    val quality: FixQuality
        get() = when {
            fix == null -> FixQuality.NONE
            fixAgeMillis > STALE_AFTER_MS -> FixQuality.STALE
            fix.accuracyMeters > POOR_ACCURACY_M -> FixQuality.POOR
            else -> FixQuality.GOOD
        }

    /** True when the arrow should be shown at all. */
    val isUsable: Boolean
        get() = fix != null && destination != null && quality != FixQuality.NONE

    companion object {
        const val STALE_AFTER_MS = 10_000L
        const val POOR_ACCURACY_M = 30f

        /** Fixes worse than this never reach the arrow. */
        const val REJECT_ACCURACY_M = 100f
    }
}

/**
 * Heading source arbitration.
 *
 * Above [TAKEOVER_MPS] the GPS course is strictly better than the magnetometer: it is
 * unaffected by the steel around you in a car, by a magnetic phone mount, or by the phone
 * being held at an angle. Below [HANDBACK_MPS] it is noise. The gap between the two is
 * hysteresis so the arrow does not flicker between sources at walking pace.
 */
object HeadingArbiter {

    const val TAKEOVER_MPS = 2.5f
    const val HANDBACK_MPS = 1.5f

    fun select(
        fix: Fix?,
        compassDeg: Double?,
        magnetometerReliable: Boolean,
        previousSource: HeadingSource,
    ): Pair<Double?, HeadingSource> {
        val speed = fix?.speedMps
        val course = fix?.bearingDeg

        val threshold = if (previousSource == HeadingSource.GPS_COURSE) HANDBACK_MPS else TAKEOVER_MPS
        if (speed != null && course != null && speed >= threshold) {
            return course.toDouble() to HeadingSource.GPS_COURSE
        }
        if (compassDeg != null) {
            return compassDeg to
                if (magnetometerReliable) HeadingSource.COMPASS else HeadingSource.COMPASS_UNCALIBRATED
        }
        if (course != null) return course.toDouble() to HeadingSource.GPS_COURSE
        return null to HeadingSource.NONE
    }
}

/** A saved place. This is the whole v0 data model. */
data class Destination(
    val id: String,
    val name: String,
    val position: LatLon,
    val note: String? = null,
    val createdAtMillis: Long = 0L,
    /** How it was created, for display: "pasted", "current position", "pin", "imported". */
    val source: String = "manual",
    /** Starred. One boolean, and it makes a thirty-point list usable. */
    val isFavourite: Boolean = false,
    /** Last time this was selected as the navigation target; null if never used. */
    val lastUsedAtMillis: Long? = null,
)
