package dev.gpsarrow.data

import android.content.Context
import android.util.Log
import dev.gpsarrow.core.SharedPoint
import dev.gpsarrow.core.SharedPointJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * The on-device copy of the shared feed.
 *
 * Offline-first like everything else in this app: the dots on the map come from this file, so a
 * user with no signal still sees what they saw last time the map could refresh. The file is one
 * JSON object, written atomically (temp + rename), same contract as [dev.gpsarrow.data.DestinationStore].
 *
 * The stored shape reuses the wire format for the points themselves — `{id: {…}}`, parsed by
 * [SharedPointJson.decodeFeed] — plus two envelope fields (`cachedAt`, `etag`) the server never
 * sees.
 *
 * This file is also the *evidence* half of the sharing status. [Snapshot.cachedAtMillis] being
 * null is the difference between "your point is not in the feed" and "no feed has ever been
 * fetched on this device", and those two must never collapse — see
 * [dev.gpsarrow.core.SharedPoints.observationOf].
 *
 * It used to own an anonymous device id as well. That concept is gone entirely; withdrawal is
 * authorised per point by [ShareTokenStore].
 */
class SharedPointCache(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    data class Snapshot(
        val points: List<SharedPoint>,
        val etag: String?,
        /** Null means no feed has ever been fetched — not "the feed was empty". */
        val cachedAtMillis: Long?,
    )

    suspend fun load(): Snapshot = withContext(Dispatchers.IO) {
        runCatching { read() }.getOrElse {
            Log.w(TAG, "could not read $FILE_NAME; starting empty", it)
            Snapshot(emptyList(), null, null)
        }
    }

    suspend fun save(points: List<SharedPoint>, etag: String?, nowMillis: Long) =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = JSONObject()
                    .put("cachedAt", nowMillis)
                etag?.let { root.put("etag", it) }
                val encoded = JSONObject()
                points.forEach { p ->
                    encoded.put(p.id, JSONObject(SharedPointJson.encodeForPublish(p)))
                }
                root.put("points", encoded)
                atomicWrite(root.toString(2))
            }.onFailure { Log.w(TAG, "could not write $FILE_NAME", it) }
        }

    /** A 304 means "nothing changed": stamp freshness without rewriting the points. */
    suspend fun touch(nowMillis: Long) = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) return@withContext
            val root = JSONObject(file.readText())
            root.put("cachedAt", nowMillis)
            atomicWrite(root.toString(2))
        }.onFailure { Log.w(TAG, "could not refresh cache timestamp", it) }
    }

    private fun read(): Snapshot {
        if (!file.exists()) return Snapshot(emptyList(), null, null)
        val root = JSONObject(file.readText())
        val points = SharedPointJson.decodeFeed(
            root.optJSONObject("points")?.toString() ?: "null",
        )
        return Snapshot(
            points = points,
            etag = root.optString("etag").takeIf { it.isNotEmpty() },
            cachedAtMillis = root.optLong("cachedAt", 0L).takeIf { it > 0L },
        )
    }

    private fun atomicWrite(text: String) {
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    private companion object {
        const val TAG = "SharedPointCache"
        const val FILE_NAME = "shared_points.json"
    }
}
