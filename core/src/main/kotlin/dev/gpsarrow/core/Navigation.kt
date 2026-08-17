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
)
