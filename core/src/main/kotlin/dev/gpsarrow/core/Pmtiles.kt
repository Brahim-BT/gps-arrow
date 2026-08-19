package dev.gpsarrow.core

/**
 * Enough of the PMTiles v3 header to decide whether a downloaded file is safe to hand to
 * MapLibre.
 *
 * Spec: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md#3-header
 *
 * Why this exists at all: a truncated download is the failure that matters here. If MapLibre is
 * given a file whose tile-data section runs past the end of the file, it renders a map with holes
 * in it — a map that looks like a map and is wrong, which is precisely the failure mode this
 * project treats as worse than an honest "I don't know". The header carries the offsets and
 * lengths of every section, so comparing them against the actual file length detects truncation
 * *without* hashing anything. That check costs 127 bytes and no CPU, so it runs first.
 *
 * A hash still runs afterwards, because truncation is not the only corruption. But the header
 * check is what makes a partial file impossible to mistake for a whole one.
 *
 * Pure: no file I/O here, only bytes in and a verdict out, so it is unit-testable and :core stays
 * free of android.* — the caller reads the first 127 bytes and passes the file length in.
 */
object Pmtiles {

    /** The header is a fixed 127 bytes at the very start of the archive. Spec 3. */
    const val HEADER_BYTES = 127

    /** `PMTiles` in ASCII. Spec 3.2. */
    private val MAGIC = byteArrayOf(0x50, 0x4D, 0x54, 0x69, 0x6C, 0x65, 0x73)

    private const val SPEC_VERSION = 3

    /**
     * Spec 3.3. Only NONE and GZIP are usable: MapLibre's own PMTiles reader throws
     * "Compression method not supported" for brotli and zstd
     * (platform/default/src/mbgl/storage/pmtiles_file_source.cpp), so accepting one here would
     * only move the failure to render time, where it is much harder to explain to a user.
     */
    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_GZIP = 2

    /** Spec 3.2. We need vector tiles; a raster archive would render but style rules would not match. */
    private const val TILE_TYPE_MVT = 1

    /**
     * The spec requires header + compressed root directory to fit in the first 16 KiB so that a
     * reader can fetch both in one go. A file violating this may still work, but it signals a
     * writer we do not recognise, so it is worth refusing rather than guessing.
     */
    private const val ROOT_DIRECTORY_LIMIT = 16_384L

    fun check(header: ByteArray, fileLengthBytes: Long): PmtilesCheck {
        if (header.size < HEADER_BYTES) {
            return invalid(
                PmtilesProblem.TRUNCATED_HEADER,
                "expected $HEADER_BYTES header bytes, got ${header.size}",
            )
        }
        for (i in MAGIC.indices) {
            if (header[i] != MAGIC[i]) {
                return invalid(
                    PmtilesProblem.NOT_A_PMTILES_FILE,
                    "magic number mismatch at byte $i",
                )
            }
        }
        val version = u8(header, 7)
        if (version != SPEC_VERSION) {
            return invalid(
                PmtilesProblem.UNSUPPORTED_VERSION,
                "spec version $version, this build understands $SPEC_VERSION",
            )
        }

        val rootOffset = u64(header, 8)
        val rootLength = u64(header, 16)
        val metadataOffset = u64(header, 24)
        val metadataLength = u64(header, 32)
        val leafOffset = u64(header, 40)
        val leafLength = u64(header, 48)
        val tileDataOffset = u64(header, 56)
        val tileDataLength = u64(header, 64)

        val internalCompression = u8(header, 97)
        val tileCompression = u8(header, 98)
        val tileType = u8(header, 99)
        val minZoom = u8(header, 100)
        val maxZoom = u8(header, 101)

        if (internalCompression != COMPRESSION_NONE && internalCompression != COMPRESSION_GZIP) {
            return invalid(
                PmtilesProblem.UNSUPPORTED_COMPRESSION,
                "internal compression $internalCompression; MapLibre accepts only none or gzip",
            )
        }
        if (tileCompression != COMPRESSION_NONE && tileCompression != COMPRESSION_GZIP) {
            return invalid(
                PmtilesProblem.UNSUPPORTED_COMPRESSION,
                "tile compression $tileCompression; MapLibre accepts only none or gzip",
            )
        }
        if (tileType != TILE_TYPE_MVT) {
            return invalid(
                PmtilesProblem.NOT_VECTOR_TILES,
                "tile type $tileType, expected $TILE_TYPE_MVT (MVT)",
            )
        }
        if (minZoom > maxZoom) {
            return invalid(
                PmtilesProblem.IMPOSSIBLE_ZOOM_RANGE,
                "min zoom $minZoom above max zoom $maxZoom",
            )
        }
        if (rootOffset + rootLength > ROOT_DIRECTORY_LIMIT) {
            return invalid(
                PmtilesProblem.ROOT_DIRECTORY_TOO_FAR,
                "root directory ends at ${rootOffset + rootLength}, spec limit is $ROOT_DIRECTORY_LIMIT",
            )
        }

        // The truncation test. Every section must lie inside the file we actually have.
        val sections = listOf(
            "root directory" to (rootOffset to rootLength),
            "metadata" to (metadataOffset to metadataLength),
            "leaf directories" to (leafOffset to leafLength),
            "tile data" to (tileDataOffset to tileDataLength),
        )
        for ((name, span) in sections) {
            val (offset, length) = span
            if (offset < 0L || length < 0L) {
                return invalid(PmtilesProblem.NONSENSE_SECTION, "$name has a negative offset or length")
            }
            val end = offset + length
            if (end > fileLengthBytes) {
                return invalid(
                    PmtilesProblem.FILE_TRUNCATED,
                    "$name ends at byte $end but the file is only $fileLengthBytes bytes",
                )
            }
        }

        return PmtilesCheck.Valid(
            PmtilesHeader(
                minZoom = minZoom,
                maxZoom = maxZoom,
                addressedTiles = u64(header, 72),
                tileEntries = u64(header, 80),
                tileContents = u64(header, 88),
                clustered = u8(header, 96) == 1,
                west = e7(header, 102),
                south = e7(header, 106),
                east = e7(header, 110),
                north = e7(header, 114),
            ),
        )
    }

    private fun invalid(problem: PmtilesProblem, detail: String) = PmtilesCheck.Invalid(problem, detail)

    private fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and 0xFF

    /**
     * Little-endian unsigned 64-bit, read into a signed Long.
     *
     * PMTiles offsets are u64 but a real archive is nowhere near 2^63 bytes, so a Long holds any
     * legitimate value exactly. A file claiming more than that is corrupt, and shifting the top
     * byte in would make it appear negative — which [check] then rejects via NONSENSE_SECTION
     * rather than silently wrapping into a small positive number.
     */
    private fun u64(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) {
            v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        }
        return v
    }

    /** Spec 3.4: signed little-endian int32, degrees times 1e7. */
    private fun e7(b: ByteArray, at: Int): Double {
        var v = 0
        for (i in 3 downTo 0) {
            v = (v shl 8) or (b[at + i].toInt() and 0xFF)
        }
        return v / 1e7
    }
}

/** The parts of the header worth surfacing once a file has passed [Pmtiles.check]. */
data class PmtilesHeader(
    val minZoom: Int,
    val maxZoom: Int,
    val addressedTiles: Long,
    val tileEntries: Long,
    val tileContents: Long,
    val clustered: Boolean,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
) {
    /** True when [p] falls inside the archive's own declared bounds. */
    fun covers(p: LatLon): Boolean =
        p.lat in south..north && p.lon in west..east
}

enum class PmtilesProblem {
    TRUNCATED_HEADER,
    NOT_A_PMTILES_FILE,
    UNSUPPORTED_VERSION,
    UNSUPPORTED_COMPRESSION,
    NOT_VECTOR_TILES,
    IMPOSSIBLE_ZOOM_RANGE,
    ROOT_DIRECTORY_TOO_FAR,
    NONSENSE_SECTION,
    FILE_TRUNCATED,
}

sealed interface PmtilesCheck {
    data class Valid(val header: PmtilesHeader) : PmtilesCheck
    data class Invalid(val problem: PmtilesProblem, val detail: String) : PmtilesCheck
}
