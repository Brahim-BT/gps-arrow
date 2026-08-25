package dev.gpsarrow.data

import dev.gpsarrow.core.SharedPoint
import dev.gpsarrow.core.SharedPointJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The three REST calls the shared-points feature makes against the Realtime Database.
 *
 * Deliberately the same shape as [dev.gpsarrow.maps.RegionDownloader]: plain
 * `HttpURLConnection`, explicit timeouts, every failure a sealed outcome rather than an
 * exception, and no dependency on any Firebase SDK. The database is just a JSON endpoint to
 * this app; that is what keeps the feature free of new dependencies and the APK free of new
 * megabytes.
 *
 * Every method is fail-soft: a failed sync keeps yesterday's cached dots on screen, and a
 * failed publish is retried by the next successful sync (see NavigationViewModel's
 * self-healing), so none of these outcomes need to interrupt the user.
 */
class SharedPointsApi {

    private val connectTimeoutMillis = 20_000
    private val readTimeoutMillis = 30_000

    /** The result of asking for the whole feed. */
    sealed interface Fetch {
        /** A full feed body plus the ETag to send back next time. */
        data class Fresh(val body: String, val etag: String?) : Fetch

        /** The feed is unchanged since the etag we presented. Nothing to do. */
        data object NotModified : Fetch

        data class Failed(val detail: String) : Fetch
    }

    sealed interface Publish {
        data object Done : Publish
        data class Failed(val detail: String) : Publish
    }

    /**
     * Fetch `/sharedPoints.json`.
     *
     * First call sends `X-Firebase-ETag: true` and receives an ETag with the body; later calls
     * present it as `If-None-Match` and a 304 comes back instead of kilobytes nobody needed.
     * This header dance is what makes "refresh on every map opening" affordable on the free
     * tier — most refreshes cost a few hundred bytes.
     */
    suspend fun fetch(etag: String?): Fetch = withContext(Dispatchers.IO) {
        val connection = (URL(SharedPointsConfig.feedUrl()).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            instanceFollowRedirects = true
            if (etag == null) {
                setRequestProperty("X-Firebase-ETag", "true")
            } else {
                setRequestProperty("If-None-Match", etag)
            }
        }

        try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> Fetch.Fresh(
                    body = connection.inputStream.bufferedReader().use { it.readText() },
                    // getHeaderField is spec'd case-insensitive; headerFields["ETag"] is a map
                    // lookup whose case-sensitivity is implementation-dependent, and losing the
                    // ETag silently would pull the whole body on every refresh — the exact
                    // opposite of what the header dance above is for.
                    etag = connection.getHeaderField("ETag"),
                )

                HttpURLConnection.HTTP_NOT_MODIFIED -> Fetch.NotModified

                // An empty database answers 200 with the body "null", so anything else here is
                // genuinely a refusal or an unreachable network.
                else -> Fetch.Failed("HTTP ${connection.responseCode}")
            }
        } catch (e: IOException) {
            Fetch.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Publish one point and its owner digest as a single atomic multi-path update.
     *
     * Both paths are create-only by server rule, and a multi-path update is applied whole or
     * not at all with each path checked against its own rule. So the two states this cannot
     * produce are the two that would matter: a point in the feed with no owner digest (public
     * and un-withdrawable by anyone), and an owner digest for a point that was never published.
     *
     * A consequence worth knowing before you go tidying: if a moderator deletes
     * `sharedPoints/<id>` by hand and leaves `owners/<id>`, the create-only write to `owners`
     * is refused, the whole update fails with it, and the publisher's device can never put the
     * point back. That is deliberate — it is what makes moderation stick against a device that
     * still has the point marked shared — but it means the cleanup workflow has to delete all
     * three nodes for a legitimate re-share to work. See SETUP_SHARED_POINTS.md.
     */
    suspend fun publish(point: SharedPoint, ownerHash: String): Publish =
        patchRoot(SharedPointJson.encodePublishPatch(point, ownerHash))

    /**
     * Queue a removal by writing this device's token for the point.
     *
     * The queue is drained daily by the cleanup workflow, which deletes the point only when
     * `sha256(tombstone)` equals the stored `owners/<id>` — the client itself has no delete
     * rights and never did.
     *
     * The write itself succeeds for anybody, including an attacker writing a guess: the node
     * has to be writable by unauthenticated clients because that is the only kind this app has.
     * Refusal happens at drain time, not here, so a 200 from this call means "queued", never
     * "accepted".
     */
    suspend fun queueRemoval(id: String, token: String): Publish =
        sendBody(SharedPointsConfig.tombstoneUrl(id), "PUT", JSONObject.quote(token))

    /**
     * Queue an edit to an already-published point, presenting this device's token for it.
     *
     * Same shape and same caveats as [queueRemoval], for the same reason: the point node is
     * create-only for clients, so this is a request the cleanup job verifies and applies, not a
     * write to the point. A 200 means "queued", never "accepted".
     *
     * The queue slot is create-only, so a hostile write can occupy it. That costs the owner one
     * drain cycle and nothing more — the job frees the slot whether it accepts the edit or
     * refuses it, and the next sync notices the point is still stale and queues again.
     */
    suspend fun queueEdit(point: SharedPoint, token: String): Publish =
        sendBody(
            SharedPointsConfig.pendingEditUrl(point.id),
            "PUT",
            SharedPointJson.encodePendingEdit(point, token),
        )

    /**
     * `PATCH` the database root, via the method-override header.
     *
     * `HttpURLConnection` refuses `setRequestMethod("PATCH")` outright — its allowed set is
     * fixed at OPTIONS/GET/HEAD/POST/PUT/DELETE/TRACE — so the request goes out as a POST
     * carrying `X-HTTP-Method-Override: PATCH`, which the Realtime Database REST API documents
     * for exactly this situation. The server treats it as the PATCH it is; only the wire verb
     * differs.
     */
    private suspend fun patchRoot(body: String): Publish =
        sendBody(SharedPointsConfig.rootUrl(), "POST", body, methodOverride = "PATCH")

    private suspend fun sendBody(
        url: String,
        method: String,
        body: String,
        methodOverride: String? = null,
    ): Publish =
        withContext(Dispatchers.IO) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                requestMethod = method
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                methodOverride?.let { setRequestProperty("X-HTTP-Method-Override", it) }
            }

            try {
                // toByteArray() is UTF-8 in Kotlin, and naming the charset explicitly trips
                // verification/resolve_check.py, which cannot see kotlin.text's default
                // imports. Left implicit deliberately.
                connection.outputStream.use { it.write(body.toByteArray()) }
                if (connection.responseCode in 200..299) Publish.Done
                else Publish.Failed("HTTP ${connection.responseCode}")
            } catch (e: IOException) {
                Publish.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                connection.disconnect()
            }
        }
}
