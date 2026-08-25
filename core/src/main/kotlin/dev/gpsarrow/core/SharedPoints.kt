package dev.gpsarrow.core

/**
 * What the user has asked for. Local, certain, and the only thing this app persists about
 * sharing.
 *
 * Deliberately three values rather than a Boolean, because "never offered" and "asked to stop"
 * are different instructions with different consequences: the first has nothing to withdraw,
 * the second has a withdrawal that may not have been delivered yet.
 */
enum class ShareIntent {
    /** Never published. The default, and what every point that was not opted in stays. */
    PRIVATE,

    /** The user wants this point public. */
    SHARED,

    /**
     * The user asked to stop. Distinct from [PRIVATE] for as long as the point may still be out
     * there — this is what keeps the withdrawal being retried, and what stops the app claiming
     * the point is private before it has seen that it is.
     */
    WITHDRAWN,

    ;

    companion object {
        /**
         * Read an intent back out of the saved-points file, defaulting to [PRIVATE].
         *
         * Unknown text — a file from a newer version, or a corrupted one — reads as [PRIVATE]
         * rather than as anything that would publish. The default has to be the safe direction
         * because this is the one field where guessing wrong puts a coordinate on the internet.
         */
        fun fromStoredName(name: String?): ShareIntent =
            values().firstOrNull { it.name == name } ?: PRIVATE
    }
}

/**
 * What the last fetched feed actually said about a point's id.
 *
 * A remote fact, so it is allowed to be absent. [NEVER_LOOKED] is the value this project has
 * had to add five times now under different names, and each of the previous four was added
 * after shipping a screen that asserted the negative case before anything had looked.
 */
enum class ShareObservation {
    /** The id was in the feed the last time one was fetched. */
    PRESENT,

    /** A feed was fetched and this id was not in it. */
    ABSENT,

    /** No feed has ever been fetched on this device. Nothing is known either way. */
    NEVER_LOOKED,
}

/**
 * What the app is entitled to tell the user about one point's public visibility.
 *
 * Exactly one of these — [PUBLISHED] — is a claim about the world, and it is the only one
 * reached by having seen the point in a feed. Everything else names the uncertainty rather than
 * resolving it in whichever direction happens to look tidier.
 *
 * There is deliberately no state meaning "it will be gone within about a day". The cleanup
 * queue is drained by a scheduled job the app cannot see, and a forecast about whether that job
 * ran is not something this device knows. A user who is told a withdrawal completed, and whose
 * camp is still on the map, is worse off than one who is told nothing.
 */
enum class ShareStatus {
    /** Say nothing. Either it was never shared, or a withdrawal has been observed to work. */
    NOT_SHARED,

    /** Seen in a fetched feed while the user wants it shared. The only certain claim. */
    PUBLISHED,

    /** The user wants it shared; no feed has confirmed that it is. */
    PUBLISH_UNCONFIRMED,

    /** The user asked to stop and the point was still in the last feed. */
    STILL_PUBLIC,

    /** The user asked to stop and no feed has been seen since. */
    WITHDRAWAL_UNCONFIRMED,
}

/**
 * The pure decisions behind the shared-points layer.
 *
 * Nothing here touches the network or the disk. Timing, validation, de-duplication and — since
 * the honesty rework — what the UI is allowed to claim are the parts worth testing, and they
 * are also the parts that go wrong silently: a layer that refreshes every map opening burns the
 * free tier's egress for nothing, a feed that renders a dot under your own saved point looks
 * like a bug, and a badge that says "shared" without having looked is a lie the user cannot
 * detect.
 */
object SharedPoints {

    /**
     * How stale the cache may get before the next map opening triggers a refresh.
     *
     * Shared points change on the scale of days, not minutes. Six hours keeps the layer fresh
     * enough to trust while making the per-user cost of browsing essentially zero: with ETags,
     * most refreshes are a 304 that costs a few hundred bytes.
     */
    const val REFRESH_AFTER_MILLIS = 6L * 60 * 60 * 1000

    /** Mirrors the server-side security rule; enforcing it client-side too saves a round trip. */
    const val MAX_NAME_CHARS = 40

    /** Ditto. */
    const val MAX_NOTE_CHARS = 200

    /**
     * Characters a Realtime Database key may not contain, plus the control range.
     *
     * This stopped being cosmetic when publishing became one multi-path `PATCH`: the id is
     * interpolated into the key `sharedPoints/<id>`, so an id containing a slash would write
     * somewhere else entirely, and the atomic pair would no longer be a pair.
     */
    private const val ILLEGAL_KEY_CHARS = ".$#[]/"

    /**
     * Whether the cached feed is old enough to be worth re-fetching when the map opens.
     *
     * A null cache time means there is no cache — always refresh. This is deliberately the ONLY
     * trigger policy: no background polling, no timer. The layer refreshes when the user
     * actually goes to look at it, which is both the polite and the cheap behaviour.
     */
    fun shouldRefresh(cachedAtMillis: Long?, nowMillis: Long): Boolean {
        if (cachedAtMillis == null) return true
        return nowMillis - cachedAtMillis >= REFRESH_AFTER_MILLIS
    }

    /**
     * The dots to draw from a fetched feed, minus anything the user already has locally.
     *
     * "Saving" someone's point keeps its id, so this one filter is what stops the map showing
     * two dots at the same coordinates after a save — the local copy wins, exactly as the list
     * and the arrow treat local state as authoritative everywhere else in this app.
     */
    fun visibleFrom(feed: List<SharedPoint>, locallyKnownIds: Set<String>): List<SharedPoint> =
        feed.filterNot { it.id in locallyKnownIds }

    /**
     * What the last fetched feed says about [id].
     *
     * [cachedAtMillis] is the whole point of the signature: without it, "not in the feed" and
     * "there is no feed" collapse into the same empty set, and the app would report a
     * withdrawal complete to a user who has never once been online.
     */
    fun observationOf(
        id: String,
        feedIds: Set<String>,
        cachedAtMillis: Long?,
    ): ShareObservation = when {
        cachedAtMillis == null -> ShareObservation.NEVER_LOOKED
        id in feedIds -> ShareObservation.PRESENT
        else -> ShareObservation.ABSENT
    }

    /**
     * The one place a local instruction and a remote observation become a claim.
     *
     * Read the [ShareIntent.WITHDRAWN] × [ShareObservation.NEVER_LOOKED] row first: a user who
     * is permanently offline taps the switch off, and this returns
     * [ShareStatus.WITHDRAWAL_UNCONFIRMED] rather than silence. Silence there would be the app
     * telling them their camp is private on the strength of never having checked.
     *
     * [ShareIntent.PRIVATE] says nothing even when the id is in the feed. That combination is
     * real — saving somebody else's dot keeps its id — and the badge means "you published
     * this", which in that case is not true.
     */
    fun statusOf(intent: ShareIntent, observation: ShareObservation): ShareStatus =
        when (intent) {
            ShareIntent.PRIVATE -> ShareStatus.NOT_SHARED

            ShareIntent.SHARED -> when (observation) {
                ShareObservation.PRESENT -> ShareStatus.PUBLISHED
                ShareObservation.ABSENT -> ShareStatus.PUBLISH_UNCONFIRMED
                ShareObservation.NEVER_LOOKED -> ShareStatus.PUBLISH_UNCONFIRMED
            }

            ShareIntent.WITHDRAWN -> when (observation) {
                ShareObservation.PRESENT -> ShareStatus.STILL_PUBLIC
                ShareObservation.ABSENT -> ShareStatus.NOT_SHARED
                ShareObservation.NEVER_LOOKED -> ShareStatus.WITHDRAWAL_UNCONFIRMED
            }
        }

    /**
     * What the share switch means, given what the point's intent already was.
     *
     * The asymmetry is the point: turning the switch ON always means [ShareIntent.SHARED], but
     * turning it OFF means [ShareIntent.WITHDRAWN] only for a point that was offered in the
     * first place. A point that was never shared goes back to [ShareIntent.PRIVATE] and says
     * nothing, rather than acquiring a withdrawal it has nothing to withdraw — which would show
     * "Withdrawal not confirmed" on a point that was never anywhere.
     */
    fun intentAfterSwitch(current: ShareIntent, sharePublicly: Boolean): ShareIntent = when {
        sharePublicly -> ShareIntent.SHARED
        current == ShareIntent.PRIVATE -> ShareIntent.PRIVATE
        else -> ShareIntent.WITHDRAWN
    }

    /**
     * The client-side half of the publish rules.
     *
     * The security rules remain the authority — this exists so an over-long name fails in the
     * editor where the user can fix it, instead of as a silent server refusal they never see.
     * Keep in lockstep with the `.validate` block in SETUP_SHARED_POINTS.md: name 1..40 chars,
     * note at most 200, coordinates finite and inside the geographic ranges.
     */
    fun canPublish(
        id: String?,
        name: String?,
        lat: Double?,
        lon: Double?,
        note: String?,
    ): Boolean {
        if (!isPublishableId(id)) return false
        // Blank-after-trim is stricter than the server rule (which only bounds length), because
        // a whitespace name renders as an unlabelled dot nobody can identify or moderate.
        if (name.isNullOrEmpty() || name.trim().isEmpty() || name.length > MAX_NAME_CHARS) return false
        if (lat == null || !lat.isFinite() || lat < -90.0 || lat > 90.0) return false
        if (lon == null || !lon.isFinite() || lon < -180.0 || lon > 180.0) return false
        if (note != null && note.length > MAX_NOTE_CHARS) return false
        return true
    }

    /**
     * Whether [id] is safe to interpolate into a database path.
     *
     * Separate from [canPublish] because withdrawal needs the same guarantee and has no name or
     * coordinates to check.
     */
    fun isPublishableId(id: String?): Boolean {
        if (id.isNullOrBlank() || id.length > 128) return false
        return id.none { it in ILLEGAL_KEY_CHARS || it.code < 0x20 || it.code == 0x7F }
    }
}
