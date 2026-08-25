package dev.gpsarrow.data

import android.content.Context
import android.util.Log
import dev.gpsarrow.core.ShareToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * One withdrawal token per published point, on this device only.
 *
 * ### Why per point, and why nothing device-wide
 *
 * This replaces a single persistent device id that was published alongside every point. That id
 * linked a user's points to each other in a world-readable feed, and — because it was the only
 * secret authorising deletion — anyone who read the feed could delete anyone's point. A token
 * that is per point, never published, and only ever compared as a digest fixes both at once and
 * leaves the device-identity concept with no remaining job. There is deliberately nothing here
 * that says "same device as before".
 *
 * ### Store first, publish second
 *
 * [mint] writes before it returns, and returns null if it could not. A token that reached the
 * server but never reached the disk is a point the user can never withdraw, which is the single
 * worst outcome this feature has — worse than failing to publish at all, because it is
 * irreversible and silent. Publishing is refused rather than risked.
 *
 * An existing token is reused rather than replaced. `owners/<id>` is create-only, so a retry
 * that presented a fresh digest would be refused by the rules and — the write being atomic —
 * would take the point with it.
 *
 * Tokens are never pruned. They are a few dozen bytes each, they live in app-private storage
 * next to the points themselves, and keeping one means a point that was withdrawn and later
 * shared again can still reuse its `owners/<id>` entry instead of colliding with it.
 */
class ShareTokenStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val random = SecureRandom()

    /**
     * The token for [id] if this device has one, or null. Withdrawal needs this and only this.
     *
     * Null is a real answer, not an error: app data cleared, or a point published from a device
     * the user no longer has. The withdrawal then cannot be delivered, and the UI goes on saying
     * so rather than pretending otherwise — see [dev.gpsarrow.core.ShareStatus].
     */
    suspend fun tokenFor(id: String): String? = withContext(Dispatchers.IO) {
        runCatching { read().optString(id) }
            .getOrNull()
            ?.takeIf { ShareToken.isWellFormed(it) }
    }

    /**
     * The token for [id], minting and persisting a new one if there isn't one yet.
     *
     * Null means "do not publish": either the file could not be read or the new token could not
     * be written, and in both cases the honest move is to leave the point private and let the
     * next sync try again.
     */
    suspend fun mint(id: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val root = read()
            val existing = root.optString(id)
            if (ShareToken.isWellFormed(existing)) return@runCatching existing

            val token = newToken()
            root.put(id, token)
            atomicWrite(root.toString())
            token
        }.getOrElse {
            Log.w(TAG, "could not persist a share token; refusing to publish", it)
            null
        }
    }

    private fun newToken(): String {
        // 32 bytes, rendered as the same lower-case hex ShareToken.isWellFormed expects.
        val bytes = ByteArray(ShareToken.LENGTH_CHARS / 2)
        random.nextBytes(bytes)
        return buildString(ShareToken.LENGTH_CHARS) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0F])
            }
        }
    }

    private fun read(): JSONObject =
        runCatching {
            if (file.exists()) JSONObject(file.readText()) else JSONObject()
        }.getOrElse {
            // A corrupt file must not silently become an empty one: that would mint fresh
            // tokens whose digests can never match what is already at owners/<id>, stranding
            // every point this device has published. Rethrow so mint() refuses instead.
            Log.w(TAG, "could not read $FILE_NAME", it)
            throw it
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
        const val TAG = "ShareTokenStore"
        const val FILE_NAME = "share_tokens.json"
        const val HEX_DIGITS = "0123456789abcdef"
    }
}
