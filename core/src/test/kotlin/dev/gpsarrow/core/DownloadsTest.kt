package dev.gpsarrow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resume and free-space decisions.
 *
 * Sizes here are the real ones from MAP_RESEARCH.md so the boundary cases are the boundaries the
 * app will actually meet: Morocco at maxzoom 14 is about 183 MB, Mauritania about 35 MB.
 */
class DownloadsTest {

    private val morocco = 183_000_000L
    private val margin = Downloads.FREE_SPACE_MARGIN_BYTES   // 67_108_864

    @Test
    fun `resumesFromWhereThePartialFileEnds`() {
        val onDisk = 90_000_000L
        val d = Downloads.decide(morocco, onDisk, freeSpaceBytes = 2_000_000_000L)
        assertEquals(DownloadDecision.Fetch(fromByte = onDisk, remainingBytes = 93_000_000L), d)
    }

    @Test
    fun `startsFromZeroWhenNothingIsOnDisk`() {
        val d = Downloads.decide(morocco, 0L, freeSpaceBytes = 2_000_000_000L)
        assertEquals(DownloadDecision.Fetch(fromByte = 0L, remainingBytes = morocco), d)
    }

    @Test
    fun `recognisesAFileThatIsAlreadyComplete`() {
        val d = Downloads.decide(morocco, morocco, freeSpaceBytes = 0L)
        assertEquals(DownloadDecision.AlreadyComplete, d)
    }

    /**
     * A partial longer than the expected total means the server's file changed under us, or a
     * write went wrong. Resuming would splice two different files together and produce something
     * of exactly the right length — a corrupt file that passes a size check. Start over.
     */
    @Test
    fun `discardsAPartialLongerThanTheExpectedTotal`() {
        val d = Downloads.decide(morocco, morocco + 1, freeSpaceBytes = 2_000_000_000L)
        assertTrue("expected a restart, got $d", d is DownloadDecision.DiscardAndRestart)
    }

    @Test
    fun `refusesWhenThereIsNotEnoughFreeSpace`() {
        // 100 MB left, 183 MB to fetch: short before the margin is even considered.
        val d = Downloads.decide(morocco, 0L, freeSpaceBytes = 100_000_000L)
        assertEquals(
            DownloadDecision.NotEnoughSpace(neededBytes = morocco + margin, freeBytes = 100_000_000L),
            d,
        )
    }

    /**
     * The boundary, checked from both sides: free space must cover the remaining bytes *plus*
     * the margin, and one byte either way flips the answer. An off-by-one here means either
     * refusing a download that would have fitted, or filling the device to zero.
     */
    @Test
    fun `theFreeSpaceBoundaryIsExact`() {
        val exactly = morocco + margin
        assertTrue(
            "at exactly needed bytes the download should proceed",
            Downloads.decide(morocco, 0L, exactly) is DownloadDecision.Fetch,
        )
        assertTrue(
            "one byte below needed, it must refuse",
            Downloads.decide(morocco, 0L, exactly - 1) is DownloadDecision.NotEnoughSpace,
        )
    }

    /**
     * Space is judged on what is *left* to fetch, not the whole file.
     *
     * 180 of 183 MB are already down, so 3 MB remain. 150 MB free is far less than the 183 MB
     * the whole file needs, and the download proceeds anyway — which is the point.
     *
     * (The first version of this test used 70 MB free and asserted Fetch. That was wrong: the
     * 64 MiB margin puts the requirement at 70,108,864 bytes, just over. The margin is large
     * enough to dominate small remainders, which is worth knowing.)
     */
    @Test
    fun `spaceIsJudgedOnTheRemainderNotTheWholeFile`() {
        val d = Downloads.decide(morocco, 180_000_000L, freeSpaceBytes = 150_000_000L)
        assertEquals(DownloadDecision.Fetch(fromByte = 180_000_000L, remainingBytes = 3_000_000L), d)
    }

    @Test
    fun `rejectsANonsenseCatalogueSize`() {
        assertTrue(Downloads.decide(0L, 0L, 1_000_000_000L) is DownloadDecision.DiscardAndRestart)
        assertTrue(Downloads.decide(-1L, 0L, 1_000_000_000L) is DownloadDecision.DiscardAndRestart)
    }

    // ---- response interpretation -------------------------------------------------------

    @Test
    fun `a200OnAFreshDownloadIsFine`() {
        assertEquals(ResponseVerdict.WriteFromStart, Downloads.interpretResponse(200, 0L))
    }

    @Test
    fun `a206OnAResumeAppends`() {
        assertEquals(ResponseVerdict.Append, Downloads.interpretResponse(206, 90_000_000L))
    }

    /**
     * The trap this whole function exists for: we asked for a range and the host sent the entire
     * file. Appending it would corrupt the download while leaving the byte count looking sane.
     */
    @Test
    fun `a200OnAResumeMeansTheHostIgnoredTheRange`() {
        assertEquals(
            ResponseVerdict.RangeIgnoredMustRestart,
            Downloads.interpretResponse(200, 90_000_000L),
        )
    }

    @Test
    fun `a416MeansStartOver`() {
        assertEquals(
            ResponseVerdict.RangeIgnoredMustRestart,
            Downloads.interpretResponse(416, 90_000_000L),
        )
    }

    /**
     * 404 is a first-class outcome, not an error: it is the expected state between shipping the
     * app and uploading the extracts, and what a user sees if a file is ever withdrawn.
     */
    @Test
    fun `a404IsNotPublishedYetRatherThanAFailure`() {
        assertEquals(ResponseVerdict.NotPublishedYet, Downloads.interpretResponse(404, 0L))
        assertEquals(ResponseVerdict.NotPublishedYet, Downloads.interpretResponse(404, 90_000_000L))
    }

    @Test
    fun `otherStatusCodesCarryTheCodeThrough`() {
        assertEquals(ResponseVerdict.Failed(503), Downloads.interpretResponse(503, 0L))
        assertEquals(ResponseVerdict.Failed(403), Downloads.interpretResponse(403, 10L))
    }
}
