package dev.gpsarrow.core

/**
 * The decisions a resumable download has to make, with no Android and no I/O in sight.
 *
 * The whole resume mechanism rests on one idea: **the partial file is the state.** There is no
 * journal, no preferences entry, no database row recording how far a download got — the length of
 * the `.part` file on disk is that number, and it cannot disagree with reality because it *is*
 * reality. Process death, a battery pull, a force-stop: on the next launch the file is measured
 * and the download resumes from there. A separate progress record would be one more thing that
 * can be wrong, and the failure it causes — resuming from the wrong offset — produces a corrupt
 * file that is the exact size it should be, which is the hardest kind to detect.
 *
 * The naming convention carries the other half of the guarantee: a file is written as
 * `<id>.pmtiles.part` and only renamed to `<id>.pmtiles` after it has been verified. So a file
 * with the final name is, by construction, one that passed. Nothing else needs to remember that.
 */
object Downloads {

    /**
     * Headroom left free after the download completes.
     *
     * The `.part` file is *renamed*, not copied, so the peak requirement is the file size itself
     * rather than twice it. This margin is not for that: it exists because filling a phone to
     * literally zero free bytes makes the whole device misbehave — Android starts failing writes
     * in unrelated apps, and the user experiences that as "the map app broke my phone".
     */
    const val FREE_SPACE_MARGIN_BYTES = 64L * 1024 * 1024

    /**
     * @param expectedTotalBytes size from the catalogue.
     * @param bytesOnDisk length of the `.part` file, or 0 if none.
     * @param freeSpaceBytes what the filesystem reports as available.
     */
    fun decide(
        expectedTotalBytes: Long,
        bytesOnDisk: Long,
        freeSpaceBytes: Long,
    ): DownloadDecision {
        if (expectedTotalBytes <= 0L) {
            return DownloadDecision.DiscardAndRestart("catalogue gives a size of $expectedTotalBytes bytes")
        }
        if (bytesOnDisk < 0L) {
            return DownloadDecision.DiscardAndRestart("negative bytes on disk")
        }
        if (bytesOnDisk > expectedTotalBytes) {
            // Longer than it should be: a changed file on the server, or a botched write. Either
            // way resuming would splice two different files together and the result would pass a
            // size check while being garbage.
            return DownloadDecision.DiscardAndRestart(
                "partial file is $bytesOnDisk bytes, larger than the expected $expectedTotalBytes",
            )
        }
        if (bytesOnDisk == expectedTotalBytes) {
            return DownloadDecision.AlreadyComplete
        }

        val remaining = expectedTotalBytes - bytesOnDisk
        if (freeSpaceBytes < remaining + FREE_SPACE_MARGIN_BYTES) {
            return DownloadDecision.NotEnoughSpace(
                neededBytes = remaining + FREE_SPACE_MARGIN_BYTES,
                freeBytes = freeSpaceBytes,
            )
        }
        return DownloadDecision.Fetch(fromByte = bytesOnDisk, remainingBytes = remaining)
    }

    /**
     * Whether a server response is usable given what we asked for.
     *
     * The trap: we send `Range: bytes=N-` expecting `206 Partial Content`, and a host that does
     * not implement ranges answers `200 OK` with the *whole file*. Appending that to an existing
     * partial produces a file of the wrong length made of two overlapping copies. Verified on
     * 2026-08-19 that GitHub Releases does return 206, but a host can change, and this is cheap
     * insurance against the day it does.
     */
    fun interpretResponse(statusCode: Int, requestedFromByte: Long): ResponseVerdict = when {
        requestedFromByte == 0L && statusCode == 200 -> ResponseVerdict.WriteFromStart
        requestedFromByte > 0L && statusCode == 206 -> ResponseVerdict.Append
        requestedFromByte > 0L && statusCode == 200 -> ResponseVerdict.RangeIgnoredMustRestart
        statusCode == 416 -> ResponseVerdict.RangeIgnoredMustRestart
        statusCode == 404 -> ResponseVerdict.NotPublishedYet
        else -> ResponseVerdict.Failed(statusCode)
    }
}

sealed interface DownloadDecision {
    /** The bytes are all there. Verify, then rename. */
    data object AlreadyComplete : DownloadDecision

    data class Fetch(val fromByte: Long, val remainingBytes: Long) : DownloadDecision

    data class NotEnoughSpace(val neededBytes: Long, val freeBytes: Long) : DownloadDecision

    /** Delete the partial and begin again. [why] is for the log, not the user. */
    data class DiscardAndRestart(val why: String) : DownloadDecision
}

sealed interface ResponseVerdict {
    data object WriteFromStart : ResponseVerdict
    data object Append : ResponseVerdict

    /** Asked for a range, got the whole file. Start over rather than splice. */
    data object RangeIgnoredMustRestart : ResponseVerdict

    /**
     * The catalogue names a file the release does not have yet.
     *
     * This is a first-class outcome rather than an error because it is the *expected* state
     * between shipping the app and uploading the extracts, and it is also what a user sees if a
     * file is ever pulled. It must read as "not available yet", never as a crash or a stuck
     * progress bar.
     */
    data object NotPublishedYet : ResponseVerdict

    data class Failed(val statusCode: Int) : ResponseVerdict
}
