package dev.gpsarrow.core

/**
 * Decides which way the map points, and refuses to change its mind quickly.
 *
 * The map rotates with the user's heading. That is the product decision, and it means the camera
 * bearing is driven by a value that can vanish — indoors, with a failed magnetometer, or while
 * stationary with no course to derive one from. Falling back to north-up is the right answer when
 * heading is unavailable, but doing it the instant a sample goes missing produces a map that
 * snaps between rotating and north-up at the boundary, which is far worse than either state.
 *
 * So this applies the same reasoning as [HeadingArbiter]: asymmetric dwell times, longer to start
 * rotating than to stop. Specifically it is quicker to fall back to north-up than to resume
 * rotating, because a map frozen at a stale bearing is actively misleading — it looks authoritative
 * and points the wrong way — whereas a map briefly held north-up is merely less convenient.
 *
 * Pure and rate-independent: every decision is a function of elapsed milliseconds, never of how
 * many samples arrived. The arrow's 1 Hz flicker came from logic that assumed a sample rate.
 */
object MapOrientation {

    /** Heading must be continuously available this long before the map starts rotating. */
    const val ROTATE_AFTER_MILLIS = 1_500L

    /** Heading must be continuously absent this long before the map snaps back to north-up. */
    const val NORTH_UP_AFTER_MILLIS = 700L

    /**
     * Once the user pans or rotates by hand, the map stops following heading until they ask for
     * it back. Fighting a user's finger is the classic failure of a rotating map, and there is no
     * timeout here on purpose: auto-resuming after N seconds would yank the view out from under
     * someone who is reading it.
     */
    fun afterUserGesture(state: OrientationState): OrientationState =
        state.copy(followingHeading = false, headingSinceMillis = null)

    /** The north indicator was tapped: snap to north-up and resume following. */
    fun afterNorthTap(state: OrientationState, nowMillis: Long): OrientationState =
        state.copy(
            followingHeading = true,
            headingSinceMillis = nowMillis,
            lastHeadingAtMillis = nowMillis,
            appliedBearingDeg = 0.0,
            northUp = true,
        )

    /**
     * Fold one heading sample — or its absence — into [state].
     *
     * @param headingDeg the arbiter's *filtered* heading, or null when it has none. Passing the
     *   raw magnetometer value here would make the map jitter with every sample; the smoothing
     *   already exists upstream and must not be duplicated or bypassed.
     */
    fun update(
        state: OrientationState,
        headingDeg: Double?,
        nowMillis: Long,
    ): OrientationState {
        if (!state.followingHeading) {
            // Suspended by a gesture. Track availability so a later resume is not stale, but do
            // not move the camera.
            return state.copy(
                headingSinceMillis = if (headingDeg == null) null
                else state.headingSinceMillis ?: nowMillis,
                lastHeadingAtMillis = if (headingDeg == null) state.lastHeadingAtMillis else nowMillis,
            )
        }

        if (headingDeg == null) {
            // The dwell measures how long heading has ACTUALLY been absent, timed from the last
            // good sample — not from the first null one. Timing from the first null starts the
            // clock one sample interval late, which at a 700 ms dwell and a 100-200 ms sample
            // rate is a 15-30% error in the direction of holding a stale bearing too long.
            val lostSince = state.lastHeadingAtMillis ?: nowMillis
            val absentLongEnough = nowMillis - lostSince >= NORTH_UP_AFTER_MILLIS
            return state.copy(
                headingSinceMillis = null,
                // Hold the last bearing until the dwell expires, then go north-up. Holding is
                // right for a brief dropout and wrong for a long one, which is what the dwell is
                // measuring.
                appliedBearingDeg = if (absentLongEnough) 0.0 else state.appliedBearingDeg,
                northUp = absentLongEnough,
            )
        }

        val since = state.headingSinceMillis ?: nowMillis
        val steadyLongEnough = nowMillis - since >= ROTATE_AFTER_MILLIS
        return state.copy(
            headingSinceMillis = since,
            lastHeadingAtMillis = nowMillis,
            appliedBearingDeg = if (steadyLongEnough) Geo.normalizeDegrees(headingDeg) else state.appliedBearingDeg,
            northUp = !steadyLongEnough && state.northUp,
        )
    }

    /**
     * Where the north indicator should point, in degrees clockwise from the top of the screen.
     *
     * When the map is rotated to bearing B, north sits at −B on screen. This is the only piece of
     * geometry the indicator needs, and it must never be mirrored: under a right-to-left layout a
     * compass drawn with a mirrored transform points the wrong way, and it fails silently to
     * anyone reviewing the screen who does not read the language. The composable drawing this
     * locks itself LTR for the same reason the arrow does.
     */
    fun northIndicatorDegrees(state: OrientationState): Double =
        Geo.normalizeDegrees(-state.appliedBearingDeg)

    /** Whether to show the indicator at all. Pointless when the map is already north-up. */
    fun showNorthIndicator(state: OrientationState): Boolean =
        !state.northUp || !state.followingHeading
}

/**
 * @param appliedBearingDeg what the camera is actually set to, which is not the latest heading —
 *   it is the last heading that survived the dwell test.
 * @param northUp true when the map is pointing north because heading is unavailable.
 * @param followingHeading false once the user has panned or rotated by hand.
 */
data class OrientationState(
    val appliedBearingDeg: Double = 0.0,
    val northUp: Boolean = true,
    val followingHeading: Boolean = true,
    val headingSinceMillis: Long? = null,
    /** When heading was last available. The loss dwell is timed from here, not from first null. */
    val lastHeadingAtMillis: Long? = null,
)
