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
     * After a pan or rotation by hand, following resumes on its own once the map has been left
     * alone this long.
     *
     * An earlier design suspended following until an explicit tap, and that was the "the dot
     * moves but the map never does" complaint: one accidental drag during a drive silently killed
     * following for the rest of the trip, and the small resume button went unnoticed. Eight
     * seconds is long enough to read the map without being yanked, short enough that nobody has
     * to wonder whether the app still knows where they are going. A second gesture restamps the
     * clock, so continuous browsing is never interrupted.
     */
    const val RESUME_AFTER_MILLIS = 8_000L

    /**
     * Suspend following because the user took the camera.
     *
     * Fighting a user's finger is the classic failure of a navigating map, so every gesture wins
     * immediately and unconditionally. What changed from the first version of this rule is how
     * suspension ends: it used to require an explicit tap, which users never discovered, and now
     * it also ends by itself after [RESUME_AFTER_MILLIS] of idleness — see [update].
     */
    fun afterUserGesture(state: OrientationState, nowMillis: Long): OrientationState =
        state.copy(
            followingHeading = false,
            headingSinceMillis = null,
            lastGestureAtMillis = nowMillis,
        )

    /** The north indicator was tapped: snap to north-up and resume following. */
    fun afterNorthTap(state: OrientationState, nowMillis: Long): OrientationState =
        state.copy(
            followingHeading = true,
            headingSinceMillis = nowMillis,
            lastHeadingAtMillis = nowMillis,
            appliedBearingDeg = 0.0,
            northUp = true,
            lastGestureAtMillis = null,
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
        var s = state

        if (!s.followingHeading) {
            val gestureAt = s.lastGestureAtMillis
            if (gestureAt == null || nowMillis - gestureAt < RESUME_AFTER_MILLIS) {
                // Suspended by a gesture and still inside the idle dwell. Track availability so
                // a later resume is not stale, but do not move the camera.
                return s.copy(
                    headingSinceMillis = if (headingDeg == null) null
                    else s.headingSinceMillis ?: nowMillis,
                    lastHeadingAtMillis =
                        if (headingDeg == null) s.lastHeadingAtMillis else nowMillis,
                )
            }
            // The dwell has expired: following resumes on its own, no tap required. Falling
            // through lets this same sample be folded in by the normal path below — so a heading
            // that stayed steady throughout the suspension takes over immediately instead of
            // making the user wait for the rotate dwell a second time.
            s = s.copy(followingHeading = true, lastGestureAtMillis = null)
        }

        if (headingDeg == null) {
            // The dwell measures how long heading has ACTUALLY been absent, timed from the last
            // good sample — not from the first null one. Timing from the first null starts the
            // clock one sample interval late, which at a 700 ms dwell and a 100-200 ms sample
            // rate is a 15-30% error in the direction of holding a stale bearing too long.
            val lostSince = s.lastHeadingAtMillis ?: nowMillis
            val absentLongEnough = nowMillis - lostSince >= NORTH_UP_AFTER_MILLIS
            return s.copy(
                headingSinceMillis = null,
                // Hold the last bearing until the dwell expires, then go north-up. Holding is
                // right for a brief dropout and wrong for a long one, which is what the dwell is
                // measuring.
                appliedBearingDeg = if (absentLongEnough) 0.0 else s.appliedBearingDeg,
                northUp = absentLongEnough,
            )
        }

        val since = s.headingSinceMillis ?: nowMillis
        val steadyLongEnough = nowMillis - since >= ROTATE_AFTER_MILLIS
        return s.copy(
            headingSinceMillis = since,
            lastHeadingAtMillis = nowMillis,
            appliedBearingDeg = if (steadyLongEnough) Geo.normalizeDegrees(headingDeg) else s.appliedBearingDeg,
            northUp = !steadyLongEnough && s.northUp,
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
 * @param followingHeading false once the user has panned or rotated by hand; true again either
 *   after an explicit resume tap or once [MapOrientation.RESUME_AFTER_MILLIS] of idleness pass.
 * @param lastGestureAtMillis when the user last took the camera. The auto-resume dwell is timed
 *   from here; null when following is active or was resumed explicitly.
 */
data class OrientationState(
    val appliedBearingDeg: Double = 0.0,
    val northUp: Boolean = true,
    val followingHeading: Boolean = true,
    val headingSinceMillis: Long? = null,
    /** When heading was last available. The loss dwell is timed from here, not from first null. */
    val lastHeadingAtMillis: Long? = null,
    val lastGestureAtMillis: Long? = null,
)
