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
                    etag = connection.headerFields?.get("ETag")?.firstOrNull(),
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

    /** Create one point. Create-only by server rule; overwriting somebody else's is refused. */
    suspend fun publish(id: String, point: SharedPoint, deviceId: String): Publish =
        putBody(
            SharedPointsConfig.pointUrl(id),
            SharedPointJson.encodeForPublish(point, deviceId),
        )

    /**
     * Queue a removal. The queue is drained daily by the cleanup workflow, which deletes the
     * point only if its stored device id matches — the client itself has no delete rights.
     */
    suspend fun queueRemoval(id: String, deviceId: String): Publish =
        putBody(SharedPointsConfig.tombstoneUrl(id), JSONObject.quote(deviceId))

    private suspend fun putBody(url: String, body: String): Publish =
        withContext(Dispatchers.IO) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                requestMethod = "PUT"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }

            try {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode in 200..299) Publish.Done
                else Publish.Failed("HTTP ${connection.responseCode}")
            } catch (e: IOException) {
                Publish.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                connection.disconnect()
            }
        }
}
