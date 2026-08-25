package dev.gpsarrow.core

import java.security.MessageDigest

/**
 * The secret that authorises withdrawing one shared point, and the public digest of it.
 *
 * ### Why there is no device identity any more
 *
 * The first version of this feature published a per-device id alongside every point, and the
 * cleanup workflow deleted a point when a tombstone carried a matching id. That id had exactly
 * one job — proving "I am the one who published this" — and it did that job in the open: the
 * feed is world-readable, so anyone could read a point's owner id and use it to delete somebody
 * else's point. It also linked every point one device had published into one set, which is
 * re-identification in a user base this size.
 *
 * Moving the authorisation to a per-point token removes both problems and, with them, the last
 * reason for the concept to exist. There is now no value anywhere that says "same device as
 * before". See SETUP_SHARED_POINTS.md for what that costs (device-level banning), which is a
 * door this project chose to close.
 *
 * ### The shape
 *
 *  - The device mints a token per point, from [java.security.SecureRandom] on the app side, and
 *    **stores it locally before publishing anything.** A token that was never written to disk is
 *    a point that can never be withdrawn.
 *  - The publish body carries [hash] of the token, at `owners/<id>`, which clients cannot read.
 *  - Withdrawal writes the token itself to `tombstones/<id>`; the cleanup workflow deletes the
 *    point only when `sha256(tombstone) == owners/<id>`.
 *
 * Storing the digest rather than the token buys nothing against the service account, which can
 * read either. It buys that a later mistake — someone flipping `owners` to `".read": true`
 * while tidying the rules — hands out digests instead of delete keys, and is therefore
 * embarrassing rather than fatal.
 */
object ShareToken {

    /** 32 bytes of randomness, written as lower-case hex. */
    const val LENGTH_CHARS = 64

    /**
     * Lower-case hex SHA-256 of [token], which is what goes in `owners/<id>`.
     *
     * The case matters: the workflow compares this string to the output of `sha256sum`, which is
     * lower-case, and a comparison that silently never matches would look exactly like a user
     * who never withdrew anything.
     */
    fun hash(token: String): String =
        // toByteArray() is UTF-8 in Kotlin. Spelling the charset out would be identical, and it
        // also trips verification/resolve_check.py, which cannot see kotlin.text's default
        // imports. Left implicit deliberately; please do not "fix" it back.
        hex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

    /**
     * Whether [token] is one this app minted: exactly [LENGTH_CHARS] lower-case hex characters.
     *
     * Checked on the way out rather than trusted, because a truncated or empty token would be
     * published as a perfectly valid-looking owner digest that no future withdrawal can match.
     */
    fun isWellFormed(token: String?): Boolean =
        token != null &&
            token.length == LENGTH_CHARS &&
            token.all { it in '0'..'9' || it in 'a'..'f' }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0F])
        }
    }

    private const val HEX_DIGITS = "0123456789abcdef"
}
