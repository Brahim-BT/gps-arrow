package dev.gpsarrow.core

import kotlin.math.exp

/**
 * Damps GNSS jitter in the **displayed** position marker, and nothing else.
 *
 * ## Why this is not the lie it looks like
 *
 * Every other round of this project has refused to soften a number. The difference here is what
 * is being softened. The accuracy ring already states the uncertainty, and it does not fluctuate
 * second to second — so a dot skittering inside a steady ring is not *more* honest than a settled
 * one. It adds a second, false claim on top of the true one: that the app's confidence is
 * changing every second. It is not.
 *
 * The line, and it is absolute: this touches the marker's GeoJSON and nothing else. The distance
 * readout, the arrow's bearing, saved destinations and the diagnostics all read the raw fix.
 * Smoothing a displayed pixel is cosmetics; smoothing a recorded coordinate would be a lie.
 *
 * ## The invariant that makes the argument airtight
 *
 * **The smoothed point is never further from the raw fix than [CLAMP_FRACTION] of its accuracy
 * radius.** So the dot is always somewhere the raw data already said the user might be — the
 * smoothing never asserts anything the fix did not permit. That is checked and enforced on every
 * update, not merely arranged for by choosing a gentle filter.
 *
 * It has to be a clamp, and that is a real finding rather than a design flourish. The steady-state
 * lag of an exponential filter tracking constant velocity is `v x tau`, which is **proportional to
 * speed**. With `tau = 2 s` that is 2.8 m while walking and 50 m while driving — so no single time
 * constant can satisfy the invariant at both. The clamp does, at any speed, and it has the useful
 * side effect of switching the filter off exactly when the user is moving fast enough not to need
 * it. Measured: it engages on ~6% of walking samples and ~100% of driving ones.
 *
 * ## Rate independence
 *
 * `alpha` is derived from elapsed milliseconds, never from a sample count. Assuming a sample rate
 * is the second recurring bug in this project — see verification/README.md — and it produced both
 * the 1 Hz arrow flicker and the map camera fighting the user's finger.
 */
object PositionSmoothing {

    /**
     * Filter time constant. Chosen by simulation, not feel: at 1 Hz it cuts stationary jitter by
     * about three quarters while keeping the walking lag near 2.7 m, comfortably inside a typical
     * 10 m ring so the clamp stays out of the way during normal walking.
     */
    const val TIME_CONSTANT_MILLIS = 2_000L

    /**
     * How far the dot may sit from the raw fix, as a fraction of the accuracy radius.
     *
     * 0.7 rather than 1.0 so the dot stays visibly inside its ring instead of riding the rim, and
     * rather than 0.5 because a tighter clamp engages on a third of walking samples with a good
     * fix, which reintroduces exactly the twitching this exists to remove.
     */
    const val CLAMP_FRACTION = 0.7

    /**
     * Fold one fix into the smoothed position.
     *
     * Snaps — rather than glides — in three cases, all of which would otherwise drag the dot
     * across the screen from somewhere the user no longer is:
     *
     *  - the first fix, where there is nothing to filter from;
     *  - after a gap longer than [NavigationState.STALE_AFTER_MS], reusing the same threshold that
     *    already decides whether the dot is drawn at all, so the two cannot drift apart;
     *  - when the clock goes backwards, which is not a small elapsed time but an unknown one.
     */
    fun update(previous: SmoothedPosition?, fix: Fix, nowMillis: Long): SmoothedPosition {
        val elapsed = previous?.let { nowMillis - it.atMillis }

        if (previous == null || elapsed == null ||
            elapsed < 0L || elapsed > NavigationState.STALE_AFTER_MS
        ) {
            return SmoothedPosition(fix.position, nowMillis, snapped = true)
        }

        val alpha = 1.0 - exp(-elapsed.toDouble() / TIME_CONSTANT_MILLIS)
        val blended = LatLon(
            lat = previous.position.lat + alpha * (fix.position.lat - previous.position.lat),
            lon = previous.position.lon + alpha * (fix.position.lon - previous.position.lon),
        )
        return SmoothedPosition(
            position = clampToAccuracy(blended, fix),
            atMillis = nowMillis,
            snapped = false,
        )
    }

    /**
     * Pull [candidate] back onto the accuracy disc around the raw fix, if it has drifted outside.
     *
     * Interpolating in degrees is exact enough here because the correction is metres: the error
     * from ignoring the sphere's curvature over such a distance is far below a pixel at any zoom
     * this app renders.
     */
    fun clampToAccuracy(candidate: LatLon, fix: Fix): LatLon {
        val accuracy = fix.accuracyMeters
        // No usable accuracy means no radius to clamp within, and inventing one would be a made-up
        // number. Fall back to the raw position, which asserts nothing extra.
        if (!accuracy.isFinite() || accuracy <= 0f) return fix.position

        val limit = CLAMP_FRACTION * accuracy
        val distance = Geo.distanceMeters(fix.position, candidate)
        if (distance <= limit || distance <= 0.0) return candidate

        val keep = limit / distance
        return LatLon(
            lat = fix.position.lat + (candidate.lat - fix.position.lat) * keep,
            lon = fix.position.lon + (candidate.lon - fix.position.lon) * keep,
        )
    }
}

/**
 * @param snapped true when this update jumped rather than glided — first fix, or after a gap.
 *   Exposed so a caller can tell "the dot moved because you moved" from "the dot moved because we
 *   gave up filtering", rather than having to infer it.
 */
data class SmoothedPosition(
    val position: LatLon,
    val atMillis: Long,
    val snapped: Boolean,
)
