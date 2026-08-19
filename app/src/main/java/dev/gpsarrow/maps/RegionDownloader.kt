package dev.gpsarrow.maps

import android.os.StatFs
import dev.gpsarrow.core.DownloadDecision
import dev.gpsarrow.core.Downloads
import dev.gpsarrow.core.Pmtiles
import dev.gpsarrow.core.PmtilesCheck
import dev.gpsarrow.core.ResponseVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Downloads one region file, resumably, and refuses to hand over anything it has not verified.
 *
 * ## The one invariant
 *
 * A file named `<id>.pmtiles` has passed every check. Bytes arrive in `<id>.pmtiles.part` and are
 * renamed only after verification, so there is no window in which a half-written or corrupt file
 * carries the name the renderer looks for. Everything else here is in service of that sentence.
 *
 * ## What this deliberately does not do
 *
 * It does not touch the arrow. It does not hold a location listener, it is not referenced from
 * the arrow path, and `:maps` sits downstream of `:core` with nothing on the navigation side
 * depending on it (BUILD_PLAN.md 6.4). A failed download, a corrupt file or a full disk cannot
 * reach the thing that already works — not because the code is careful, but because there is no
 * edge in the dependency graph along which it could travel.
 *
 * ## Threading
 *
 * Every method that touches the network or the disk is `withContext(Dispatchers.IO)`. Cancellation
 * is checked inside the read loop, so cancelling mid-download stops within one buffer and leaves
 * the `.part` file intact for a later resume.
 */
class RegionDownloader(private val regionsDirectory: File) {

    /** 64 KiB: large enough that the syscall overhead disappears, small enough to cancel promptly. */
    private val bufferBytes = 64 * 1024

    private val connectTimeoutMillis = 20_000
    private val readTimeoutMillis = 30_000

    fun partialFile(region: RegionSummary) = File(regionsDirectory, "${region.id}.pmtiles.part")
    fun finalFile(region: RegionSummary) = File(regionsDirectory, "${region.id}.pmtiles")

    /**
     * Fetch [region], resuming if a partial is present.
     *
     * [onProgress] is called with (bytesDone, bytesTotal) roughly every buffer; the caller is
     * expected to throttle its own UI updates rather than have this guess at a sensible rate.
     */
    suspend fun download(
        region: RegionSummary,
        onProgress: (Long, Long) -> Unit,
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        val part = partialFile(region)
        val target = finalFile(region)

        if (target.exists()) return@withContext DownloadOutcome.AlreadyInstalled(target)

        regionsDirectory.mkdirs()

        when (val decision = Downloads.decide(
            expectedTotalBytes = region.bytes,
            bytesOnDisk = if (part.exists()) part.length() else 0L,
            freeSpaceBytes = freeSpaceBytes(),
        )) {
            is DownloadDecision.NotEnoughSpace ->
                return@withContext DownloadOutcome.NotEnoughSpace(decision.neededBytes, decision.freeBytes)

            is DownloadDecision.DiscardAndRestart -> part.delete()

            DownloadDecision.AlreadyComplete -> Unit   // fall through to verification below

            is DownloadDecision.Fetch -> Unit
        }

        val transfer = try {
            transfer(region, part, onProgress)
        } catch (e: IOException) {
            // The partial is kept: this is the ordinary "connection died" case and the whole
            // point of the .part file is that it survives to be resumed.
            return@withContext DownloadOutcome.NetworkFailed(e.message ?: e.javaClass.simpleName)
        }
        if (transfer != null) return@withContext transfer

        verifyAndInstall(region, part, target)
    }

    /** @return non-null when the transfer ended in an outcome the caller should see. */
    private suspend fun transfer(
        region: RegionSummary,
        part: File,
        onProgress: (Long, Long) -> Unit,
    ): DownloadOutcome? {
        var from = if (part.exists()) part.length() else 0L
        if (from >= region.bytes && part.exists()) return null   // nothing left to fetch

        val connection = (URL(region.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            instanceFollowRedirects = true
            if (from > 0L) setRequestProperty("Range", "bytes=$from-")
        }

        try {
            val status = connection.responseCode
            var append = true

            when (val verdict = Downloads.interpretResponse(status, from)) {
                ResponseVerdict.NotPublishedYet -> return DownloadOutcome.NotPublishedYet

                is ResponseVerdict.Failed -> return DownloadOutcome.ServerRefused(verdict.statusCode)

                ResponseVerdict.RangeIgnoredMustRestart -> {
                    // The host sent the whole file when we asked for part of it. Appending would
                    // splice two copies together, so throw the partial away and take this body
                    // from byte zero.
                    part.delete()
                    from = 0L
                    append = false
                }

                ResponseVerdict.WriteFromStart -> {
                    part.delete()
                    append = false
                }

                ResponseVerdict.Append -> append = true
            }

            // Content-Length describes what is left to send, so the true total is what we already
            // hold plus that. The server is the authority here, not the catalogue estimate.
            val declaredRemaining = connection.contentLengthLong
            val total = if (declaredRemaining > 0L) from + declaredRemaining else region.bytes

            var done = from
            connection.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(bufferBytes)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                    // Push this download's bytes to the platform before we claim success. Without
                    // it a crash seconds later could leave the file shorter than we believe, and
                    // the resume would then start from the wrong offset.
                    output.flush()
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
        return null
    }

    /**
     * Header check, then hash, then rename — in that order, cheapest first.
     *
     * The hash is computed over the whole file in one pass at the end rather than incrementally
     * as bytes arrive. That is not laziness: a running digest cannot be persisted across process
     * death, so after a resume there would be no valid intermediate state to continue from. A
     * full pass costs a few seconds on 183 MB and is correct in every case, including the ones
     * that only happen when a phone is killed mid-download.
     */
    private suspend fun verifyAndInstall(
        region: RegionSummary,
        part: File,
        target: File,
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        if (!part.exists()) return@withContext DownloadOutcome.NetworkFailed("no data was written")

        val head = ByteArray(Pmtiles.HEADER_BYTES)
        val readCount = part.inputStream().use { it.read(head) }
        if (readCount < Pmtiles.HEADER_BYTES) {
            part.delete()
            return@withContext DownloadOutcome.Corrupt("file is shorter than a PMTiles header")
        }

        when (val check = Pmtiles.check(head, part.length())) {
            is PmtilesCheck.Invalid -> {
                part.delete()
                return@withContext DownloadOutcome.Corrupt("${check.problem}: ${check.detail}")
            }

            is PmtilesCheck.Valid -> Unit
        }

        val expected = region.checksum
        if (expected != null) {
            val actual = sha256(part)
            if (!actual.equals(expected, ignoreCase = true)) {
                part.delete()
                return@withContext DownloadOutcome.Corrupt("checksum mismatch")
            }
        }

        // The atomic step. Same directory, so this is a rename within one filesystem and either
        // happens completely or not at all.
        if (!part.renameTo(target)) {
            return@withContext DownloadOutcome.Corrupt("could not move the verified file into place")
        }
        DownloadOutcome.Installed(target)
    }

    /**
     * SHA-256 rather than the BLAKE3 that Protomaps publish.
     *
     * Their hashes cover *their* planet builds, not the extracts we cut from them, so we are
     * hashing our own artefacts either way and the algorithm is our choice. SHA-256 is in the
     * platform (`MessageDigest`), hardware-accelerated on every ARMv8 phone this will run on, and
     * adds no dependency. BLAKE3 would mean shipping a library to gain speed we do not need for
     * a once-per-region check.
     */
    private suspend fun sha256(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(bufferBytes)
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun freeSpaceBytes(): Long = try {
        StatFs(regionsDirectory.path).availableBytes
    } catch (_: IllegalArgumentException) {
        // The directory may not exist yet on first run; the parent is what matters.
        StatFs(regionsDirectory.parentFile?.path ?: regionsDirectory.path).availableBytes
    }
}

/**
 * Every way a download can end.
 *
 * Deliberately exhaustive and deliberately specific: the UI has to tell the difference between
 * "the file is not uploaded yet", "your phone is full" and "the file arrived damaged", because
 * those need three different things from the user and a single "download failed" tells them
 * nothing they can act on.
 */
sealed interface DownloadOutcome {
    data class Installed(val file: File) : DownloadOutcome
    data class AlreadyInstalled(val file: File) : DownloadOutcome

    /** 404 — the catalogue names a file the release does not carry yet. Not an error. */
    data object NotPublishedYet : DownloadOutcome

    data class NotEnoughSpace(val neededBytes: Long, val freeBytes: Long) : DownloadOutcome

    /** Connection lost. The partial file is kept and the next attempt resumes. */
    data class NetworkFailed(val detail: String) : DownloadOutcome

    data class ServerRefused(val statusCode: Int) : DownloadOutcome

    /** Arrived, failed verification, and has been deleted. Never rendered. */
    data class Corrupt(val detail: String) : DownloadOutcome
}
