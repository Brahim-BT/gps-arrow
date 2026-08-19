"""Builds PMTiles v3 headers in Python and emits PmtilesTest.kt from them.

This is deliberately an *independent* implementation of the byte layout, written from the spec
table rather than from Pmtiles.kt. If the two agree, the Kotlin is probably right; if the Kotlin
were the only source of truth the test would just be asserting that the code does what the code
does, which is the shape of test that passes on broken code.

Spec: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md#3-header

Run:  python3 pmtiles_header_fixtures.py        (rewrites ../core/src/test/.../PmtilesTest.kt)
"""
import os
import struct

HEADER_BYTES = 127
OUT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "core", "src", "test", "kotlin", "dev", "gpsarrow", "core", "PmtilesTest.kt",
)

COMPRESSION_NONE, COMPRESSION_GZIP, COMPRESSION_BROTLI = 1, 2, 3
TILETYPE_MVT, TILETYPE_PNG = 1, 2


def header(
    magic=b"PMTiles", version=3,
    root_off=127, root_len=1000,
    meta_off=1127, meta_len=500,
    leaf_off=1627, leaf_len=0,
    tile_off=1627, tile_len=180_000_000,
    addressed=579_860, entries=400_000, contents=390_000,
    clustered=1, internal_compression=COMPRESSION_GZIP, tile_compression=COMPRESSION_GZIP,
    tile_type=TILETYPE_MVT, min_zoom=0, max_zoom=14,
    west=-17.10, south=20.77, east=-0.99, north=35.95,
    center_zoom=7, center_lon=-9.0, center_lat=28.4,
):
    """Every field is a keyword so a test can bend exactly one of them."""
    b = bytearray()
    b += magic                                   # 0-6
    b += struct.pack("<B", version)              # 7
    for v in (root_off, root_len, meta_off, meta_len,
              leaf_off, leaf_len, tile_off, tile_len,
              addressed, entries, contents):     # 8-95
        b += struct.pack("<Q", v & 0xFFFFFFFFFFFFFFFF)
    b += struct.pack("<B", clustered)            # 96
    b += struct.pack("<B", internal_compression) # 97
    b += struct.pack("<B", tile_compression)     # 98
    b += struct.pack("<B", tile_type)            # 99
    b += struct.pack("<B", min_zoom)             # 100
    b += struct.pack("<B", max_zoom)             # 101
    for v in (west, south, east, north):         # 102-117
        b += struct.pack("<i", round(v * 1e7))
    b += struct.pack("<B", center_zoom)          # 118
    b += struct.pack("<i", round(center_lon * 1e7))   # 119-122
    b += struct.pack("<i", round(center_lat * 1e7))   # 123-126
    assert len(b) == HEADER_BYTES, f"built {len(b)} bytes, spec says {HEADER_BYTES}"
    return bytes(b)


# A plausible Morocco z14 archive: sections all inside a file of this length.
GOOD_FILE_LEN = 1627 + 180_000_000

CASES = []


def case(name, kotlin_name, hdr, file_len, expect, comment):
    CASES.append(dict(name=name, fn=kotlin_name, hdr=hdr, len=file_len,
                      expect=expect, comment=comment))


case("valid", "acceptsAWellFormedArchive", header(), GOOD_FILE_LEN, "Valid",
     "the shape pmtiles extract actually produces")

case("truncated file", "rejectsATruncatedFile", header(),
     GOOD_FILE_LEN - 1, "FILE_TRUNCATED",
     "one byte short: the tile-data section now runs past the end of the file. This is the "
     "case that matters most - it is what a download interrupted at 99% looks like")

case("badly truncated", "rejectsAHalfDownloadedFile", header(),
     GOOD_FILE_LEN // 2, "FILE_TRUNCATED",
     "half a file")

case("not pmtiles", "rejectsAFileThatIsNotPmtilesAtAll",
     header(magic=b"\x89PNG\r\n\x1a"), GOOD_FILE_LEN, "NOT_A_PMTILES_FILE",
     "an HTML error page or a PNG saved under the right name")

case("wrong version", "rejectsAFutureSpecVersion", header(version=4),
     GOOD_FILE_LEN, "UNSUPPORTED_VERSION", "a v4 archive this build cannot read")

case("brotli internal", "rejectsBrotliInternalCompression",
     header(internal_compression=COMPRESSION_BROTLI), GOOD_FILE_LEN,
     "UNSUPPORTED_COMPRESSION",
     "MapLibre's reader throws on anything but none/gzip, so catch it here where we can explain it")

case("brotli tiles", "rejectsBrotliTileCompression",
     header(tile_compression=COMPRESSION_BROTLI), GOOD_FILE_LEN,
     "UNSUPPORTED_COMPRESSION", "same, for the tile data")

case("raster", "rejectsARasterArchive", header(tile_type=TILETYPE_PNG),
     GOOD_FILE_LEN, "NOT_VECTOR_TILES", "a raster archive would render but no style rule would match")

case("zoom inverted", "rejectsAnImpossibleZoomRange", header(min_zoom=10, max_zoom=4),
     GOOD_FILE_LEN, "IMPOSSIBLE_ZOOM_RANGE", "min above max")

case("root too far", "rejectsARootDirectoryPastTheSpecLimit",
     header(root_off=20000, root_len=100), 200_000_000, "ROOT_DIRECTORY_TOO_FAR",
     "spec requires header+root inside the first 16 KiB")

case("huge offset", "rejectsAnAbsurdSectionOffset",
     header(tile_off=2**63), GOOD_FILE_LEN, "NONSENSE_SECTION",
     "a u64 past Long.MAX_VALUE reads as negative rather than wrapping to a small positive")


def kotlin_bytes(bs):
    """Emit as a hex string; 127 bytes of decimal literals would be unreadable."""
    return '"' + "".join(f"{b:02x}" for b in bs) + '"'


def main():
    good = header()
    lines = []
    w = lines.append
    w("package dev.gpsarrow.core")
    w("")
    w("import org.junit.Assert.assertEquals")
    w("import org.junit.Assert.assertTrue")
    w("import org.junit.Test")
    w("")
    w("/**")
    w(" * GENERATED by verification/pmtiles_header_fixtures.py - do not hand-edit.")
    w(" *")
    w(" * Every header below was built in Python straight from the spec's byte table, as an")
    w(" * implementation independent of Pmtiles.kt. A test that built its fixtures with the same")
    w(" * code it is testing would agree with any bug the parser has.")
    w(" */")
    w("class PmtilesTest {")
    w("")
    w("    private fun bytes(hex: String) = ByteArray(hex.length / 2) {")
    w("        hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()")
    w("    }")
    w("")
    for c in CASES:
        w(f"    /** {c['comment']} */")
        w("    @Test")
        w(f"    fun `{c['fn']}`() {{")
        w(f"        val header = bytes({kotlin_bytes(c['hdr'])})")
        w(f"        val result = Pmtiles.check(header, {c['len']}L)")
        if c["expect"] == "Valid":
            w("        assertTrue(\"expected a valid header, got $result\", result is PmtilesCheck.Valid)")
        else:
            w("        assertTrue(\"expected rejection, got $result\", result is PmtilesCheck.Invalid)")
            w("        assertEquals(")
            w(f"            PmtilesProblem.{c['expect']},")
            w("            (result as PmtilesCheck.Invalid).problem,")
            w("        )")
        w("    }")
        w("")

    # Field decoding, checked against the values Python encoded.
    w("    /** The header's own numbers must survive the round trip, not just parse without error. */")
    w("    @Test")
    w("    fun `decodesTheHeaderFields`() {")
    w(f"        val result = Pmtiles.check(bytes({kotlin_bytes(good)}), {GOOD_FILE_LEN}L)")
    w("        val h = (result as PmtilesCheck.Valid).header")
    w("        assertEquals(0, h.minZoom)")
    w("        assertEquals(14, h.maxZoom)")
    w("        assertEquals(579860L, h.addressedTiles)")
    w("        assertEquals(400000L, h.tileEntries)")
    w("        assertEquals(390000L, h.tileContents)")
    w("        assertTrue(h.clustered)")
    w("        assertEquals(-17.10, h.west, 1e-7)")
    w("        assertEquals(20.77, h.south, 1e-7)")
    w("        assertEquals(-0.99, h.east, 1e-7)")
    w("        assertEquals(35.95, h.north, 1e-7)")
    w("    }")
    w("")
    w("    /** Bounds are what decides whether an installed region covers where the user is. */")
    w("    @Test")
    w("    fun `boundsCoverCasablancaAndNotNouakchott`() {")
    w(f"        val result = Pmtiles.check(bytes({kotlin_bytes(good)}), {GOOD_FILE_LEN}L)")
    w("        val h = (result as PmtilesCheck.Valid).header")
    w("        assertTrue(h.covers(LatLon(33.5731, -7.5898)))")
    w("        assertTrue(!h.covers(LatLon(18.0735, -15.9582)))")
    w("    }")
    w("")
    w("    /** A short read must be refused rather than indexed past the end. */")
    w("    @Test")
    w("    fun `rejectsAHeaderShorterThanTheSpecLength`() {")
    w(f"        val short = bytes({kotlin_bytes(good)}).copyOf(100)")
    w(f"        val result = Pmtiles.check(short, {GOOD_FILE_LEN}L)")
    w("        assertEquals(")
    w("            PmtilesProblem.TRUNCATED_HEADER,")
    w("            (result as PmtilesCheck.Invalid).problem,")
    w("        )")
    w("    }")
    w("}")

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    print(f"wrote {OUT}")
    print(f"  {len(CASES) + 4} test methods, header length {len(good)} bytes")
    for c in CASES:
        print(f"    {c['name']:<22} file={c['len']:>12,}  expect {c['expect']}")


if __name__ == "__main__":
    main()
