package dev.gpsarrow.data

/**
 * Where the Realtime Database lives, and the one switch for the whole feature.
 *
 * Paste the database URL from the Firebase console here after following
 * SETUP_SHARED_POINTS.md. Left blank, the feature is invisible: the share toggle does not
 * render and no network call is ever attempted, so an unconfigured build behaves exactly like
 * the version without the feature.
 */
object SharedPointsConfig {

    /** e.g. "https://gps-arrow-xxxxx-default-rtdb.europe-west1.firebasedatabase.app" */
    const val BASE_URL = ""

    val isConfigured: Boolean get() = BASE_URL.isNotBlank()

    /** REST paths against the base URL. Centralised so the shapes live in one sentence. */
    fun feedUrl() = "$BASE_URL/sharedPoints.json"

    fun pointUrl(id: String) = "$BASE_URL/sharedPoints/$id.json"

    fun tombstoneUrl(id: String) = "$BASE_URL/tombstones/$id.json"
}
