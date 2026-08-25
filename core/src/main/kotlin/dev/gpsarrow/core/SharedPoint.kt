package dev.gpsarrow.core

import org.json.JSONObject

/**
 * A destination another user chose to make public, as it travels over the wire and sits in the
 * on-device cache.
 *
 * Deliberately narrower than [Destination]: a shared point carries nothing the owner did not
 * explicitly opt into — no favourites, no last-used stamp, no fix accuracy. The wire format is
 * the privacy policy made concrete.
 */
data class SharedPoint(
    val id: String,
    val name: String,
    val position: LatLon,
    val note: String? = null,
    val createdAtMillis: Long = 0L,
)

/**
 * The wire format for [SharedPoint], both directions of the Realtime Database REST API.
 *
 * Three shapes exist because the database does:
 *
 *  - **The point node** itself, which is what lives at `sharedPoints/<id>` and what the
 *    on-device cache stores verbatim.
 *  - **Publish**, which is a multi-path `PATCH` against the database root writing that node and
 *    the owner digest together — see [encodePublishPatch].
 *  - **Fetch** reads the whole subtree, which comes back as an OBJECT OF OBJECTS keyed by id —
 *    not an array — or as the literal body `null` when nobody has shared anything yet.
 *
 * Parsing is tolerant by design: one malformed entry from one bad client skips exactly that
 * entry and never blanks the layer. Everything this app publishes has already passed the
 * security rules server-side, but the cache also outlives rule changes, so the reader defends
 * itself rather than trusting its own history.
 */
object SharedPointJson {

    private const val KEY_NAME = "name"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_NOTE = "note"
    private const val KEY_CREATED_AT = "createdAt"

    /** Only ever appears in a pending edit, never in the feed. */
    private const val KEY_TOKEN = "t"

    /**
     * One point node: exactly what the owner opted into and nothing else.
     *
     * There is no owner field. An earlier version put a per-device id in here, in a feed whose
     * rules are `".read": true` — see [ShareToken] for why that is gone and what replaced it.
     */
    fun encodeForPublish(point: SharedPoint): String =
        JSONObject().apply {
            put(KEY_NAME, point.name)
            put(KEY_LAT, point.position.lat)
            put(KEY_LON, point.position.lon)
            // Blank is omitted as well as null. The reader turns a blank note into null coming
            // back in, so publishing `""` would produce a point that never compares equal to
            // itself — permanently "edited but not published", queueing an edit on every sync
            // that changes nothing. See SharedPoints.publishedMatches.
            point.note?.takeIf { it.isNotBlank() }?.let { put(KEY_NOTE, it) }
            put(KEY_CREATED_AT, point.createdAtMillis)
        }.toString()

    /**
     * The body for a multi-path `PATCH` against the database root, publishing the point and its
     * owner digest as one write.
     *
     * ```
     * {"sharedPoints/<id>": {name, lat, lon, note, createdAt},
     *  "owners/<id>":       "<sha256(token)>"}
     * ```
     *
     * Multi-path updates are atomic and each path is validated against its own rule, so the
     * failure this shape exists to prevent — a live point whose owner digest never landed, and
     * which therefore nobody can ever withdraw — cannot happen. Either both appear or neither
     * does.
     *
     * [id] must have passed [SharedPoints.isPublishableId] before reaching here: it is
     * interpolated into a path, and a slash in it would send half the write somewhere else.
     */
    fun encodePublishPatch(point: SharedPoint, ownerHash: String): String =
        JSONObject().apply {
            put("sharedPoints/${point.id}", JSONObject(encodeForPublish(point)))
            put("owners/${point.id}", ownerHash)
        }.toString()

    /**
     * The body for `PUT /pendingEdits/<id>.json`: an edit to an already-published point,
     * carrying the token that authorises it.
     *
     * `sharedPoints/<id>` is create-only for clients and stays that way — that create-only rule
     * is what makes a moderator's deletion stick. So an edit is not a write to the point; it is
     * a *request*, queued here and applied by the cleanup job, which verifies
     * `sha256(token) == owners/<id>` before touching anything. Realtime Database rules can
     * compare strings but cannot hash, so the check has to happen somewhere that can.
     *
     * The token sits here in plaintext until the queue is drained. That is the same exposure
     * `tombstones/<id>` already has — same node kind, same lifetime, same `".read": false` —
     * and it is why `owners/<id>` can go on holding a digest rather than a secret.
     *
     * **An absent note is written as JSON null, not omitted.** The job applies this with a
     * `PATCH`, and a `PATCH` that omits a key leaves the old value in place — so omitting it
     * would make "remove this note" the one edit that silently does nothing, which is the edit
     * most likely to matter.
     */
    fun encodePendingEdit(point: SharedPoint, token: String): String =
        JSONObject().apply {
            put(KEY_TOKEN, token)
            put(KEY_NAME, point.name)
            put(KEY_LAT, point.position.lat)
            put(KEY_LON, point.position.lon)
            put(KEY_NOTE, point.note?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        }.toString()

    /**
     * Parse a whole-feed response. Returns whatever survived; an unreadable body yields an
     * empty list, which renders as no dots rather than an error card — the map must not turn
     * into a diagnostics screen over one bad response.
     */
    fun decodeFeed(body: String): List<SharedPoint> {
        if (body.isBlank() || body == "null") return emptyList()
        return runCatching {
            val root = JSONObject(body)
            buildList {
                for (id in root.keys()) {
                    val o = root.optJSONObject(id) ?: continue
                    val lat = o.optDouble(KEY_LAT, Double.NaN)
                    val lon = o.optDouble(KEY_LON, Double.NaN)
                    // A point without a usable coordinate draws nowhere useful; drop it here
                    // rather than guard every downstream consumer.
                    if (!lat.isFinite() || !lon.isFinite()) continue
                    val name = o.optString(KEY_NAME).trim()
                    if (name.isEmpty()) continue
                    add(
                        SharedPoint(
                            id = id,
                            name = name,
                            position = LatLon(lat, lon),
                            note = o.optString(KEY_NOTE).takeIf { it.isNotBlank() },
                            createdAtMillis = o.optLong(KEY_CREATED_AT, 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
