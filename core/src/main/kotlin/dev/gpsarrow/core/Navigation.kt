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

    /**
     * Close enough to the destination that the direction to it is no longer knowable.
     *
     * Inside the fix's own error circle the bearing is dominated by GNSS noise rather than
     * geometry, so the needle degrades to a plain compass instead of spinning to a new
     * "direction to target" on every fix.
     */
    ARRIVED,

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
     * True once the destination is inside the circle the fix itself can't see into.
     *
     * The direction to a point 10 m away changes by tens of degrees when the position moves by
     * a couple of metres, so at short range "bearing to target" is a readout of GNSS noise at
     * the fix rate, not of geometry. [ARRIVAL_RADIUS_M] is the floor; when the fix is honest
     * about being worse than that, its own accuracy is used instead.
     */
    val hasArrived: Boolean
        get() {
            val f = fix ?: return false
            val d = distanceMeters ?: return false
            return d <= maxOf(ARRIVAL_RADIUS_M, f.accuracyMeters.toDouble())
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
            destination != null && fix != null && bearingToDestinationDeg != null ->
                if (hasArrived) ArrowMode.ARRIVED else ArrowMode.TARGET

            headingDeg != null -> ArrowMode.NORTH
            else -> ArrowMode.NONE
        }

    /**
     * Rotation for the needle, in screen space.
     *
     * In [ArrowMode.NORTH] and [ArrowMode.ARRIVED] it points at true north (`-heading`), which
     * is exactly what a compass needle does. In [ArrowMode.TARGET] it points at the destination.
     */
    val arrowDeg: Double?
        get() = when (arrowMode) {
            ArrowMode.TARGET -> Geo.normalizeDegrees(
                (bearingToDestinationDeg ?: 0.0) - (headingDeg ?: 0.0),
            )
            ArrowMode.ARRIVED, ArrowMode.NORTH -> Geo.normalizeDegrees(-(headingDeg ?: 0.0))
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

    /**
     * Whether the current fix may be committed to storage as "where I am".
     *
     * A stale fix is where you *were*. The arrow can show one honestly because it is labelled
     * and self-corrects on the next update, but a saved point is permanent: walk 500 m from the
     * car, tap save on a ten-minute-old fix, and the app has silently recorded the car park as
     * your current position with nothing on screen to say so. Refusing is the only safe answer.
     */
    val isSaveable: Boolean
        get() = quality == FixQuality.GOOD || quality == FixQuality.POOR

    /**
     * True when the heading on screen is magnetic north wearing true north's clothes.
     *
     * Declination is a function of position, so until the first fix arrives there is nothing to
     * correct with and the compass is showing magnetic north. That gap is under a degree across
     * much of Europe and over 20 degrees in northern Canada and Alaska — far too large to leave
     * unlabelled in an app whose whole job is pointing at things. GPS course is excluded because
     * course over ground is already true north.
     */
    val headingIsMagnetic: Boolean
        get() = declinationDeg == null &&
            (
                headingSource == HeadingSource.COMPASS ||
                    headingSource == HeadingSource.COMPASS_UNCALIBRATED
                )

    companion object {
        const val STALE_AFTER_MS = 10_000L
        const val POOR_ACCURACY_M = 30f

        /** Fixes worse than this never reach the arrow. */
        const val REJECT_ACCURACY_M = 100f

        /**
         * Floor for [hasArrived]. A good GNSS fix is ±5 m, so a destination within about a
         * dozen metres is "here" as far as the receiver can tell — pointing at it is a
         * confidently-wrong arrow, which BUILD_PLAN 10 says never to draw.
         */
        const val ARRIVAL_RADIUS_M = 12.0
    }
}

/**
 * Heading source arbitration.
 *
 * Above [TAKEOVER_MPS] the GPS course is strictly better than the magnetometer: it is
 * unaffected by the steel around you in a car, by a magnetic phone mount, or by the phone
 * being held at an angle. Below [HANDBACK_MPS] it is noise. The gap between the two is
 * hysteresis so the arrow does not flicker between sources at walking pace.
 *
 * The chip's course is only ever taken on the word of [CourseEstimator]'s watchdog
 * ([chipCourseTrusted]); when it is distrusted, [derivedCourseDeg] — computed from consecutive
 * positions — speaks for GPS instead, which is what keeps the arrow tracking through turns on
 * devices whose reported bearing stalls at speed.
 */
object HeadingArbiter {

    const val TAKEOVER_MPS = 2.5f
    const val HANDBACK_MPS = 1.5f

    /**
     * Below this the device is not moving, and course over ground carries no information.
     *
     * A stationary receiver still emits a bearing, and that bearing wanders over the whole
     * circle from one fix to the next. Letting it reach the needle moves the arrow once per
     * fix — a 1 Hz twitch on a phone lying still on a table. Gating it here, once, means every
     * branch below inherits the guarantee that standing still can never select GPS_COURSE.
     */
    const val STATIONARY_MPS = 0.5f

    fun select(
        fix: Fix?,
        compassDeg: Double?,
        magnetometerReliable: Boolean,
        previousSource: HeadingSource,
        derivedCourseDeg: Double? = null,
        chipCourseTrusted: Boolean = true,
    ): Pair<Double?, HeadingSource> {
        // A fix with no speed counts as stationary: something that cannot say how fast you are
        // moving cannot say which way you are facing either.
        val speed = fix?.speedMps ?: 0f
        val chip: Double? = fix?.bearingDeg?.toDouble()?.takeIf { speed >= STATIONARY_MPS }

        // The chip's word is taken at face value only while [CourseEstimator] has not caught it
        // repeating against contradicting geometry. A distrusted bearing never reaches the
        // needle — not even as a last resort, because "the frozen number we know is wrong" is
        // exactly the failure this exists to remove. While geometry has no fresh answer (the
        // window refilling) the arbiter simply falls through to whatever is next.
        val course: Double? = when {
            chip != null && chipCourseTrusted -> chip
            derivedCourseDeg != null && speed >= STATIONARY_MPS -> derivedCourseDeg
            else -> null
        }

        if (course != null) {
            val threshold =
                if (previousSource == HeadingSource.GPS_COURSE) HANDBACK_MPS else TAKEOVER_MPS
            if (speed >= threshold) return course to HeadingSource.GPS_COURSE
        }
        if (compassDeg != null) {
            return compassDeg to
                if (magnetometerReliable) HeadingSource.COMPASS else HeadingSource.COMPASS_UNCALIBRATED
        }
        // No compass on this device at all. Course over ground is the only heading left, but
        // only while actually moving — which is exactly what the arrow screen's "no compass"
        // copy already promises the user.
        if (course != null) return course to HeadingSource.GPS_COURSE
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
    /**
     * Accuracy of the fix this point was captured from, metres. Null for typed, pasted or
     * imported points, whose accuracy is whatever the source claimed and is not ours to state.
     *
     * Carried so a point saved from a ±40 m fix does not sit in the list looking identical to
     * one saved from a ±4 m fix. The arrow can only ever be as good as the point it aims at,
     * and the user is the only one who can decide whether ±40 m is good enough for the job.
     */
    val accuracyMeters: Float? = null,
    /**
     * What the user has asked for regarding public sharing. **An instruction, not a status.**
     *
     * This used to be `isPublic: Boolean`, whose own comment admitted it was "a local flag about
     * a REMOTE fact" that "nothing here may assume matches the server" — and the list then
     * rendered "Publicly shared" straight off it. A Boolean cannot carry a remote fact, so this
     * field no longer tries to: it records only the local, certain half.
     *
     * The other half — whether the point is actually in the feed — is derived at read time from
     * the cached feed by [SharedPoints.observationOf], and the two are combined by
     * [SharedPoints.statusOf] into the only thing the UI is allowed to say. That derivation is
     * why nothing about the remote side is persisted here: it self-corrects on the next sync,
     * and a stored copy of it would not.
     */
    val shareIntent: ShareIntent = ShareIntent.PRIVATE,
)
