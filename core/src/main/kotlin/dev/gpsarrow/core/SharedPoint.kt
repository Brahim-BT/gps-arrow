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
 * Two shapes exist because the database does:
 *
 *  - **Publish** writes ONE point as a JSON object under `sharedPoints/<id>.json`.
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
    private const val KEY_DEVICE_ID = "deviceId"

    /** The body for `PUT /sharedPoints/<id>.json`. */
    fun encodeForPublish(point: SharedPoint, deviceId: String): String =
        JSONObject().apply {
            put(KEY_NAME, point.name)
            put(KEY_LAT, point.position.lat)
            put(KEY_LON, point.position.lon)
            point.note?.let { put(KEY_NOTE, it) }
            put(KEY_CREATED_AT, point.createdAtMillis)
            put(KEY_DEVICE_ID, deviceId)
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
