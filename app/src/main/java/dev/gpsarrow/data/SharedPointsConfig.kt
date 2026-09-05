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
    const val BASE_URL = "https://gps-arrow-baibbat-default-rtdb.europe-west1.firebasedatabase.app"

    val isConfigured: Boolean get() = BASE_URL.isNotBlank()

    /** REST paths against the base URL. Centralised so the shapes live in one sentence. */
    fun feedUrl() = "$BASE_URL/sharedPoints.json"

    /**
     * The database root, which is where publishing writes.
     *
     * Publishing is a multi-path update covering `sharedPoints/<id>` and `owners/<id>`, and a
     * multi-path update has to be addressed at the common ancestor of the paths it names. There
     * is deliberately no `pointUrl(id)` any more: a write that could touch the point without
     * touching its owner digest is exactly the half-published state the atomic shape exists to
     * make impossible.
     */
    fun rootUrl() = "$BASE_URL/.json"

    fun tombstoneUrl(id: String) = "$BASE_URL/tombstones/$id.json"

    /**
     * Where an edit to an already-published point is queued.
     *
     * A queue rather than a write, for the same reason withdrawal is: `sharedPoints/<id>` is
     * create-only for clients and stays so — that rule is what makes a moderator's deletion
     * stick — and the token can only be verified by something that can hash, which database
     * rules cannot.
     */
    fun pendingEditUrl(id: String) = "$BASE_URL/pendingEdits/$id.json"
}
