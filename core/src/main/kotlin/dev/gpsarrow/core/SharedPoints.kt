package dev.gpsarrow.core

/**
 * The pure decisions behind the shared-points layer.
 *
 * Nothing here touches the network or the disk. Timing, validation and de-duplication are the
 * parts worth testing, and they are also the parts that go wrong silently: a layer that
 * refreshes every map opening burns the free tier's egress for nothing, and a feed that renders
 * a dot under your own saved point looks like a bug.
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
        if (id.isNullOrBlank() || id.length > 128) return false
        // Blank-after-trim is stricter than the server rule (which only bounds length), because
        // a whitespace name renders as an unlabelled dot nobody can identify or moderate.
        if (name.isNullOrEmpty() || name.trim().isEmpty() || name.length > MAX_NAME_CHARS) return false
        if (lat == null || !lat.isFinite() || lat < -90.0 || lat > 90.0) return false
        if (lon == null || !lon.isFinite() || lon < -180.0 || lon > 180.0) return false
        if (note != null && note.length > MAX_NOTE_CHARS) return false
        return true
    }
}
