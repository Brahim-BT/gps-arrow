package dev.gpsarrow.data

import android.content.Context
import android.util.Log
import dev.gpsarrow.core.SharedPoint
import dev.gpsarrow.core.SharedPointJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The on-device copy of the shared feed, and the anonymous device identity.
 *
 * Offline-first like everything else in this app: the dots on the map come from this file, so a
 * user with no signal still sees what they saw last time the map could refresh. The file is one
 * JSON object, written atomically (temp + rename), same contract as [dev.gpsarrow.data.DestinationStore].
 *
 * The stored shape reuses the wire format for the points themselves — `{id: {…}}`, parsed by
 * [SharedPointJson.decodeFeed] — plus two envelope fields (`cachedAt`, `etag`) the server never
 * sees.
 */
class SharedPointCache(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val deviceFile = File(context.filesDir, DEVICE_FILE_NAME)

    data class Snapshot(
        val points: List<SharedPoint>,
        val etag: String?,
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
                    // The publish body minus the device id — that field is transport-only and
                    // must not sit in a file the user can open with any file manager.
                    encoded.put(p.id, JSONObject(SharedPointJson.encodeForPublish(p, "")))
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

    /**
     * A random identifier that says only "same device as before". No account, no hardware id,
     * nothing that follows the user across installs or devices — it exists so un-sharing can
     * later be verified to touch only this device's own points.
     */
    suspend fun deviceId(): String = withContext(Dispatchers.IO) {
        try {
            deviceFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
                ?: UUID.randomUUID().toString().also {
                    deviceFile.writeText(it)
                }
        } catch (e: Exception) {
            Log.w(TAG, "device id unavailable; publishing without one", e)
            ""
        }
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
        const val DEVICE_FILE_NAME = "device_id.txt"
    }
}
