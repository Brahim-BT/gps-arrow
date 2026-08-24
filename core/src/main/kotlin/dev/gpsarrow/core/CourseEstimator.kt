package dev.gpsarrow.core

import kotlin.math.abs

/**
 * Course over ground derived from consecutive fixes, plus a watchdog on the chip's own bearing.
 *
 * ## Why this exists
 *
 * Device testing found the arrow freezing above [HeadingArbiter.TAKEOVER_MPS] — exactly where
 * the heading source switches to GPS course — and unfreezing at [HeadingArbiter.HANDBACK_MPS],
 * where it hands back to the compass. The course the GNSS chip reports in `Location.getBearing()`
 * had gone stale while the position kept updating, and nothing in the pipeline noticed: the
 * arbiter faithfully selected a frozen number every fix and held it until the hysteresis let go.
 * Raw `LocationManager` on the wide spread of ROMs this app targets makes that failure mode a
 * when, not an if.
 *
 * So the chip's bearing is no longer trusted blindly. Two things come out of here:
 *
 *  - **A derived course** — the initial bearing from an anchor point to the current fix once
 *    enough real displacement has accumulated. Pure geometry from positions the app already has.
 *  - **A trust verdict on the chip's bearing** — false once it is provably lying, true again the
 *    moment it moves.
 *
 * ## The window, not the pair
 *
 * The derived course is measured across an anchor rather than between consecutive fixes because
 * at takeover speed a single second of travel is ~2.5 m — inside the position noise of all but
 * the best fixes, so a per-pair bearing would be noise with a degree symbol. Displacement is
 * allowed to accumulate until it reaches `max([MIN_DISPLACEMENT_M], accuracy)`, and only then is
 * one bearing computed and the anchor advanced. Near 9 km/h that is roughly one course per three
 * seconds; at city speeds several per second; both are far below the turn rates a road produces.
 *
 * ## The watchdog needs geometry to accuse
 *
 * A chip bearing that repeats is NOT proof of staleness: a straight highway legitimately
 * produces the same course for minutes. The accusation only sticks when the repeated value also
 * **diverges from the derived course** — geometry contradicting the chip — on consecutive fixes.
 * On a straight road the two agree within noise and the watchdog stays silent forever; on a
 * curving road with a stalled field they separate immediately and the flag lands after
 * [FROZEN_IDENTICAL_FIXES] fixes. Once flagged, distrust latches until the chip reports a
 * different value, so a gap in derived coverage (window refilling) cannot flip trust back on
 * behind a still-frozen value.
 *
 * Pure and rate-independent, like everything else in this module: the caller owns [State] and
 * every decision is a function of its inputs, never of how often it is called.
 */
object CourseEstimator {

    /**
     * Displacement the anchor window must accumulate before one derived course is emitted.
     *
     * 8 m against a ±5 m fix keeps the bearing error inside ~30° worst-case and typically under
     * 10°, while flushing often enough at takeover speed to track a road turn within seconds.
     */
    const val MIN_DISPLACEMENT_M = 8.0

    /** Above this implied speed the "movement" is a position jump, not travel. Reset silently. */
    const val MAX_IMPLIED_SPEED_MPS = 60.0

    /** Identical chip bearings at rest prove nothing; this many, moving and diverging, do. */
    const val FROZEN_IDENTICAL_FIXES = 3

    /** Chip-vs-geometry disagreement, sustained, that brands a repeated bearing stale. */
    const val FROZEN_DIVERGENCE_DEG = 25.0

    /**
     * Everything carried between fixes. Small on purpose: the caller keeps one instance, as it
     * already does for [PositionSmoothing]-style state elsewhere.
     */
    data class State(
        /** Where the current displacement window started. */
        val anchor: LatLon? = null,
        val anchorAtMillis: Long = 0L,
        /** The chip bearing last seen, for exact-equality runs. Null breaks the run. */
        val chipBearingDeg: Float? = null,
        /** Consecutive fixes reporting a bit-identical chip bearing. */
        val identicalRun: Int = 0,
        /**
         * Whether the chip's bearing may reach the needle. Latches false until the value moves.
         */
        val chipTrusted: Boolean = true,
    )

    /** One fix folded into the estimator. */
    data class Estimate(
        /** Geometry's answer, or null while the window is empty or refilling. */
        val derivedCourseDeg: Double?,
        /** Whether [Fix.bearingDeg] may be trusted for this fix. */
        val chipTrusted: Boolean,
        /** Fold into the next call. */
        val state: State,
    )

    fun update(previous: State?, fix: Fix): Estimate {
        var s = previous ?: State()

        // ---- the displacement window ---------------------------------------------
        //
        // Computed BEFORE the watchdog so the divergence verdict sees this fix's geometry.
        var derived: Double? = null
        val anchor = s.anchor
        if (anchor != null) {
            val elapsedMillis = fix.elapsedMillis - s.anchorAtMillis
            val displacement = Geo.distanceMeters(anchor, fix.position)
            val seconds = elapsedMillis / 1_000.0
            // Backwards time or a jump faster than anything this app serves means the window
            // no longer describes continuous travel; a bearing over it would be fiction.
            val discontinuity = elapsedMillis < 0L ||
                elapsedMillis > NavigationState.STALE_AFTER_MS ||
                (seconds > 0.0 && displacement / seconds > MAX_IMPLIED_SPEED_MPS)
            if (discontinuity) {
                s = s.copy(anchor = null)
            } else {
                // An unusable accuracy contributes no radius, so the floor stands alone.
                // The sentinel for "no accuracy" upstream is Float.MAX_VALUE — finite, so a
                // bare isFinite() test lets it through — and anything past the reject gate
                // this app applies to fixes is equally meaningless as a circle.
                val accuracy = fix.accuracyMeters.takeIf {
                    it.isFinite() && it > 0f && it <= NavigationState.REJECT_ACCURACY_M
                }?.toDouble() ?: MIN_DISPLACEMENT_M
                if (displacement >= maxOf(MIN_DISPLACEMENT_M, accuracy)) {
                    derived = Geo.initialBearingDegrees(anchor, fix.position)
                    s = s.copy(anchor = fix.position, anchorAtMillis = fix.elapsedMillis)
                }
            }
        }
        if (s.anchor == null) {
            s = s.copy(anchor = fix.position, anchorAtMillis = fix.elapsedMillis)
        }

        // ---- the frozen-chip watchdog --------------------------------------------
        val chip = fix.bearingDeg
        // Exact equality is deliberate: a healthy receiver's course twitches in the last bits
        // even on a straight road, while a stalled field repeats bit-for-bit.
        val repeated = chip != null && chip == s.chipBearingDeg
        val identicalRun = when {
            // A fix without a bearing is silence, not evidence: it neither extends nor breaks
            // the run, so a one-off gap cannot resurrect trust behind a still-frozen field.
            chip == null -> s.identicalRun
            repeated -> s.identicalRun + 1
            else -> 0
        }
        val diverged = derived != null && chip != null &&
            abs(Geo.angleDeltaDegrees(chip.toDouble(), derived)) > FROZEN_DIVERGENCE_DEG

        val chipTrusted = when {
            // No bearing this fix is no evidence either way; hold the previous verdict.
            chip == null -> s.chipTrusted
            // The value moved: the field is alive again, whatever it said before.
            !repeated -> true
            // Still accumulating accusations against a trusted value.
            s.chipTrusted -> !(identicalRun >= FROZEN_IDENTICAL_FIXES && diverged)
            // Latched distrust holds until the value itself moves, even through gaps where
            // the window has not yet produced a fresh comparison.
            else -> false
        }

        return Estimate(
            derivedCourseDeg = derived,
            chipTrusted = chipTrusted,
            state = s.copy(
                chipBearingDeg = chip ?: s.chipBearingDeg,
                identicalRun = identicalRun,
                chipTrusted = chipTrusted,
            ),
        )
    }
}
