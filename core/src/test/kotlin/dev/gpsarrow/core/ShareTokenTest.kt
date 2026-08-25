package dev.gpsarrow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The digest that decides whether a withdrawal is honoured.
 *
 * Pinned against reference values rather than against itself. A hash function tested only by
 * `hash(x) == hash(x)` passes while producing upper-case hex, or a truncated digest, or the
 * digest of the wrong bytes — and every one of those failures looks, from the user's side,
 * exactly like a withdrawal that silently never happens.
 *
 * Expectations computed with `python3 -c "import hashlib; print(hashlib.sha256(s.encode()).hexdigest())"`,
 * which is an implementation this code shares nothing with.
 */
class ShareTokenTest {

    @Test
    fun `matchesTheReferenceDigests`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ShareToken.hash(""),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ShareToken.hash("abc"),
        )
        assertEquals(
            "41b8941c0ac5a95289e8637bb10c41301c37f1d6e090a0042794b38e30d6fc21",
            ShareToken.hash("a2f1c9d4b6e8"),
        )
    }

    /**
     * Non-Latin input goes in as UTF-8. Tokens this app mints are hex, so this can never fire in
     * production — which is exactly why it is worth pinning: an encoding assumption that is
     * never exercised is one nobody notices changing.
     */
    @Test
    fun `hashesUtf8BytesNotSomePlatformDefault`() {
        assertEquals(
            "3a243313fea1c2a7e6239fe943cf810c89542fa11e36858da5a74d9e3d98a60c",
            ShareToken.hash("نقطة"),
        )
    }

    /** Lower-case, zero-padded, and always the full 64 characters. */
    @Test
    fun `digestsAreLowerCaseHexOfTheFullLength`() {
        listOf("", "abc", "a", "the well by the road", "0").forEach { input ->
            val digest = ShareToken.hash(input)
            assertEquals(input, 64, digest.length)
            assertTrue(input, digest.all { it in '0'..'9' || it in 'a'..'f' })
        }
    }

    @Test
    fun `wellFormedRejectsEverythingThatIsNotAMintedToken`() {
        val good = "a".repeat(ShareToken.LENGTH_CHARS)
        assertTrue(ShareToken.isWellFormed(good))
        assertTrue(ShareToken.isWellFormed(ShareToken.hash("anything")))

        // The empty string is the one that mattered: the previous design fell back to "" when
        // it could not read its identity file and published anyway, which handed the delete key
        // to anyone willing to send two quote marks.
        assertFalse(ShareToken.isWellFormed(""))
        assertFalse(ShareToken.isWellFormed(null))
        assertFalse(ShareToken.isWellFormed(good.dropLast(1)))
        assertFalse(ShareToken.isWellFormed(good + "a"))
        assertFalse(ShareToken.isWellFormed("A".repeat(ShareToken.LENGTH_CHARS)))
        assertFalse(ShareToken.isWellFormed("g".repeat(ShareToken.LENGTH_CHARS)))
    }
}
