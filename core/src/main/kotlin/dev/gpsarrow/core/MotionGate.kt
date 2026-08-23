package dev.gpsarrow.core

/**
 * Whether the user is moving fast enough for the camera to ride ahead of their dot.
 *
 * The map places the position dot below centre while following, so a driver can see the road
 * ahead rather than the bonnet. That offset only makes sense when there is motion to look ahead
 * of: standing still it just hides part of the view for no reason.
 *
 * Speed comes from the GNSS fix and wobbles — a pedestrian at a stop light oscillates around any
 * single threshold. A bare `speed > X` test would flip the offset on and off several times a
 * minute, each flip jumping the dot by a fifth of the screen. So this is a hysteresis band:
 * moving begins only above [START_ABOVE_MPS] and ends only below [STOP_BELOW_MPS], and between
 * the two the previous answer is held.
 *
 * Pure and stateless per call — the previous answer is passed in, so the caller owns the one bit
 * of state and every decision remains a function of its inputs.
 */
object MotionGate {

    /** A brisk walk is about 1.4 m/s; above this you are going somewhere. */
    const val START_ABOVE_MPS = 1.4f

    /** Below about 2 km/h you have effectively stopped; holding the offset buys nothing. */
    const val STOP_BELOW_MPS = 0.6f

    /**
     * Fold one fix's speed into the moving flag.
     *
     * A null or non-finite speed (no fix yet, or a fix without velocity) is treated as not
     * moving: inventing a threshold crossing from missing data would be a made-up number, and
     * centred is the honest default.
     */
    fun update(moving: Boolean, speedMps: Float?): Boolean {
        val speed = speedMps
        if (speed == null || !speed.isFinite()) return false
        return if (moving) speed > STOP_BELOW_MPS else speed >= START_ABOVE_MPS
    }
}
