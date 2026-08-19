# Offline map: sizes, hosting, reliability

Research for the v1 map tier. **No code written yet** — this exists so the hosting question can
be put to the user with real numbers.

Every figure below is either **measured**, **quoted from a primary source**, or **modelled with
the model stated**. Where something could not be verified from here, it says so rather than
guessing.

---

## 0. The thing that reframes the whole question

The map does **not** stream tiles over HTTP. MapLibre Android 11.7.0+ reads a PMTiles archive
straight off the device:

```kotlin
val source = VectorSource("basemap", "pmtiles://file://$path")
```

The byte-range reads that PMTiles depends on happen against the **local file**, not the network.
`RegionIndex.pmtilesUri` in `MapTier.kt` already builds exactly this URI.

So the host's `Range` support is **not** load-bearing for rendering. It is load-bearing for
**resumable download**, which matters a great deal on a rural Moroccan connection — but the
failure mode of a host that ignores `Range` is "downloads restart from zero", not "map renders
with holes". That is a much less frightening failure than the one we were guarding against.

Two hard constraints found in the MapLibre docs, both of which shape the build:

- **`pmtiles://asset://` is not supported.** `AssetManagerFileSource` does not implement
  byte-range reads. A PMTiles archive therefore **cannot be bundled inside the APK** — it must
  be downloaded to `getExternalFilesDir()`. (`RegionIndex` already targets that directory.)
- **PMTiles sources do not participate in MapLibre's offline pack manager or its tile cache.**
  We do our own downloading. That is what we were going to do anyway; it just means MapLibre's
  `OfflineManager` is not available to lean on.

---

## 1. Sizes

### The zoom range is not the one we assumed

**z16 is not available.** The Protomaps planet build is *z0–15*. `pmtiles extract --maxzoom`
can only take levels **away**. Getting z16 means building tiles from OSM with planetiler
ourselves — a large machine, hours of compute per build, and a permanent maintenance commitment.
That is a different project, and it should be off the table for v1.

So the real options are **z12, z13, z14, z15**.

### What each level costs and buys

| maxzoom | Morocco + W. Sahara | Mauritania | Both | What you give up |
|---|---|---|---|---|
| z12 | 65 MB | 20 MB | **85 MB** | Minor roads thin out; no useful street detail in towns |
| z13 | 104 MB | 25 MB | **129 MB** | Street network present but sparse at walking scale |
| z14 | 183 MB | 35 MB | **218 MB** | Buildings are *merged blobs*, not individual footprints |
| z15 | 341 MB | 55 MB | **396 MB** | Nothing — this is the maximum available |

The z14/z15 line is sharp and documented: the Protomaps `buildings` layer says *"z0–14 contains
merged buildings, even disconnected ones. z15+ contains individual OSM equivalent buildings."*
That is the single concrete thing the last doubling buys.

Note that MapLibre **overzooms** past maxzoom — a z14 archive still renders when the user pinches
in to z18, just without further detail. Labels stay crisp at any zoom because they are vector.
So "maxzoom 14" does not mean "can't zoom in past 14".

### Recommendation: z14, as two separate files

Reasons:

1. **The arrow is the product.** The map is context for the arrow. Individual building footprints
   are the least valuable 178 MB in the table.
2. **Two files, not one.** A user working only in Mauritania downloads **35 MB**, not 218 MB.
   `RegionIndex` already handles a list of installed regions, so this costs nothing to support
   and it is the difference between a 2-minute download and a 20-minute one for that user.
3. **218 MB combined is still a single sane download** for someone who crosses the border.

If the user pushes back and wants maximum detail, z15 at 396 MB combined is affordable —
it is not the difference between viable and not. z12 is the only option I would argue against:
85 MB is barely cheaper than z13's 129 MB and it degrades exactly the street-level detail a
walking navigator is for.

### How these numbers were produced — and their honest status

**Modelled, not measured.** I could not reach the Protomaps build server from this sandbox
(see §4), so I could not run `pmtiles extract` and weigh the result.

The model, stated so it can be checked:

- Protomaps publish the planet at **~120 GB for z0–15**, and that **"each additional zoom level
  roughly doubles the size of the file."**
- Extract size scales with **OSM data volume**, not land area — high-zoom vector tile bytes are
  almost entirely feature geometry, and PMTiles **deduplicates identical tiles**, so the tens of
  thousands of byte-identical empty Sahara tiles collapse to near-nothing. (An earlier version of
  this estimate had a "floor" term of tile-count × 600 B, which put Mauritania at 1.1 GB. That
  was wrong for exactly this reason and has been removed.)
- Current Geofabrik extracts, 2026-08-17: **Morocco 231 MB**, **Mauritania 29.1 MB**.
  Planet: **87.6 GB**.
- So `size(z15) ≈ 120 GB × country_pbf / 87.6 GB`, halving per level below 15, plus a directory
  overhead allowance (25 MB Morocco / 15 MB Mauritania).

**Validation.** The same model predicts Germany 6.0 GB, France 6.3 GB, Netherlands 2.2 GB,
Switzerland 0.62 GB — which reproduces the Protomaps documentation's own rule of thumb that
*"a country-sized extract typically lands in the low gigabytes."* That is the only independent
check available without running the extract, and it passes.

**Confidence:** roughly ±40%. Morocco at z14 is "somewhere between 110 and 260 MB". Good enough
to choose a zoom level; not good enough to print in the UI. The catalogue must carry the **real**
byte count from the actual file, which is why `RegionSummary.bytes` exists.

**To settle it exactly** (about 10 minutes, needs the `pmtiles` CLI and a decent connection):

```bash
BUILD=$(curl -s https://maps.protomaps.com/builds/ | grep -o 'v4[^"]*\.pmtiles' | tail -1)
pmtiles extract "https://build.protomaps.com/$BUILD" morocco-z14.pmtiles \
    --bbox=-17.10,20.77,-0.99,35.95 --maxzoom=14
pmtiles extract "https://build.protomaps.com/$BUILD" mauritania-z14.pmtiles \
    --bbox=-17.07,14.72,-4.83,27.30 --maxzoom=14
ls -l *.pmtiles
```

Better still, use `--region=` with a GeoJSON country polygon instead of `--bbox`; Morocco's
bounding box includes a lot of Atlantic.

---

## 2. Hosting

### GitHub Releases — free, no account, no card

**Verified from GitHub's own documentation, verbatim:**

> Up to 1000 release assets may be associated with a single release. Each file included in a
> release must be under 2 GiB. **There is no limit on the total size of a release, nor bandwidth
> usage.**

- **2 GiB per file** — our largest option is 341 MB, so we have 6× headroom.
- **No bandwidth cap.** A few hundred users at 218 MB each is ~44 GB of egress and costs nothing.
- Attaches to the repo that already exists. No new account, no card, no infrastructure.

**The `Range` question — VERIFIED, 2026-08-19.**

Tested against a real public release asset, following the redirect to the CDN:

```
HTTP/2 302                              <- github.com issues the redirect
HTTP/2 206                              <- the CDN honours the range
accept-ranges: bytes
content-range: bytes 0-99/17442403
```

`206 Partial Content` through the redirect, `accept-ranges: bytes`, and a `content-range` naming
the correct total. **Resumable download against GitHub Releases works.** This was previously
recorded here as an inference; it is now a measured fact.

(The test could not be run from the build sandbox — its egress proxy blocks
`release-assets.githubusercontent.com`, where assets actually live, returning
`X-Proxy-Error: blocked-by-allowlist`. It was run on the user's machine instead. The command is
kept below because it is the right check to re-run if the host ever changes.)

```bash
curl -sIL -H "Range: bytes=0-99" \
  https://github.com/protomaps/go-pmtiles/releases/download/v1.30.3/go-pmtiles_1.30.3_Linux_x86_64.tar.gz \
  | grep -iE "^HTTP/|accept-ranges|content-range"
```

Supporting evidence, consistent with the above: GitHub **Pages** also serves PMTiles by range
(`content-range: bytes 0-16383/9274689` in a Protomaps discussion thread), and the failure
reported there was a **Firefox cache bug** the maintainer reproduced on `pmtiles.io` too — not a
GitHub problem.

**One risk worth naming.** GitHub's acceptable-use terms are aimed at software distribution.
A few hundred megabytes of map data supporting your own app is squarely normal use. It is worth
knowing that this is a general-purpose free service being used for bulk data, and that the
polite thing — and the thing that keeps it working — is to keep the files to the two we need.

### Cloudflare R2 — the paid-but-actually-free alternative

- **10 GB storage free, permanently.** Our two files at z14 total 218 MB — 2% of the free tier.
- **Zero egress charges at any volume.** This is R2's whole reason to exist.
- **S3-compatible, so `Range` support is guaranteed by the API contract**, not inferred.
- Costs: needs a Cloudflare account and a card on file even at $0. That is the real price —
  an account someone has to own, and a bill that is $0 until it isn't.

I could not test R2's range behaviour either; `r2.cloudflarestorage.com` and `*.r2.dev` are both
blocked by the same proxy allowlist.

### Others considered

- **GitHub Pages** — 1 GB soft repo limit and a 100 GB/month soft bandwidth limit; the files
  would also live in git history. Worse than Releases for this.
- **Source Cooperative** — Protomaps mirror their daily build there, but it hosts *their* planet
  file, not our extracts. Not a host for us.
- **Hotlinking `build.protomaps.com`** — explicitly discouraged by Protomaps ("URLs may change
  and hotlinking to these downloads are discouraged"), and it would serve the 120 GB planet
  rather than our extract. Not an option.

### Recommendation

**GitHub Releases**, subject to the one-line `curl` check above passing. It needs nothing new
from anybody, it has no bandwidth cliff, and 2 GiB per file is comfortable headroom. R2 is the
fallback if the range check fails, and the migration is a URL change in the catalogue — cheap
to reverse, which is a good reason not to agonise.

---

## 3. Reliability

"It has to be reliable" — here is what that has to mean concretely, and what each part costs.

### Resumable download — **required**

HTTP `Range: bytes=N-` from the byte count already on disk, into a `.part` file. Roughly 80 lines
around `OkHttp`. The failure it prevents: a 218 MB download that dies at 90% on a patchy link and
starts again from zero. Without this the feature is not usable in rural Mauritania at all.

If the host turns out not to honour `Range`, the fallback is honest rather than silent: tell the
user the download must restart, and let them decide.

### Integrity verification — **required, and there is a good answer**

A truncated or corrupted PMTiles file must be **detected**, never rendered as a map with holes.
Three layers, cheapest first:

1. **Size check** against the catalogue's byte count. Free, catches truncation.
2. **PMTiles header check** — read the first 127 bytes, verify magic `PMTiles`, version, and that
   the root directory offsets fall inside the file. Cheap, catches a wrong or half-written file.
3. **Full-file hash.** Protomaps publish **BLAKE3** hashes for their daily builds — but those are
   for *their* planet file, not our extract, so we generate and publish our own hash alongside
   each extract. `RegionSummary.checksum` is already in the data model waiting for it.

SHA-256 over 218 MB on a mid-range phone is a few seconds. Do it once, on completion, before the
atomic rename into place. Never verify lazily at render time.

**The rename is the important part**: download to `region.pmtiles.part`, verify, then
`renameTo("region.pmtiles")`. A file with the final name is by construction one that passed. That
single discipline removes the whole class of "half a map" bugs.

### Free space — **required, and easy to get wrong**

Check `StatFs.getAvailableBytes()` before starting, and require the download size **plus a
margin** — the `.part` file and the final file briefly coexist if you copy rather than rename, so
rename (same filesystem) and the margin can be small. Refuse up front with a clear number rather
than failing at 95%.

Android can also delete files in `getExternalFilesDir()` under storage pressure, so the app must
survive a region vanishing between sessions. `RegionIndex` re-scans the directory, so this mostly
works already — but the scan must verify, not just check the filename exists.

### The arrow must never depend on the map — **already true, keep it true**

`BUILD_PLAN.md` §6.4 puts `:maps` downstream of `:core` with *nothing* on the arrow path
depending on it, and `MapTier` distinguishes `ArrowOnly` (no map module) from `NoDataHere` (fixable
by downloading). That separation is the most valuable thing already built here and it should be
enforced by a test, not just by intention: a `:core` test asserting no `:maps` import, the same
way the existing sweep asserts `:core` imports nothing from `android.*`.

### What the user sees, and background continuation

- A **foreground service** with a notification is the correct Android mechanism for a download
  that must survive the user leaving the app. `WorkManager` with an expedited job is the modern
  alternative; a foreground service is simpler to reason about for a single long transfer.
- Show **bytes and percentage**, not a spinner. On a slow link a spinner is indistinguishable
  from a hang, and this download can legitimately take 20 minutes.
- Let it be **paused and resumed**, and make "download only on Wi-Fi" the default. Mobile data in
  the region is metered and 218 MB is real money to somebody.

### Failure modes worth naming now

| Failure | Detection | Response |
|---|---|---|
| Connection dies mid-download | I/O exception | Keep `.part`, resume from byte N |
| Host ignores `Range` | First resume returns `200` not `206` | Restart, and say so |
| Disk fills mid-download | Write fails `ENOSPC` | Keep `.part`, report free space needed |
| Corrupt/truncated file | Hash mismatch after download | Delete, do not rename, offer retry |
| File deleted by Android | Re-scan finds it gone | Fall back to `NoDataHere`, offer re-download |
| Catalogue unreachable | Fetch fails | Use cached catalogue — already the design |

---

## 4. Fonts and sprites — **measured, not estimated**

I cloned `protomaps/basemaps-assets` and weighed the files.

A style's `glyphs` and `sprite` URLs are fetched at render time. Left as
`https://protomaps.github.io/...`, an offline device renders **roads with no labels** — which is
exactly the failure worth avoiding. They must ship inside the APK.

The Protomaps v4 style expects three font stacks: `Noto Sans Regular`, `Noto Sans Medium`,
`Noto Sans Italic`. Each is split into 256 glyph-range files. **We do not need all 256** — only
the Unicode blocks our three languages actually use:

| Range | Bytes (Regular) | What it covers |
|---|---|---|
| `0-255` | 74 kB | Basic Latin + Latin-1 Supplement (English, most French) |
| `256-511` | 125 kB | Latin Extended-A (`œ`, `Ç`) |
| `1536-1791` | 108 kB | Arabic |
| `1792-2047` | 91 kB | Arabic Supplement |
| `8192-8447` | 63 kB | General Punctuation — **includes the bidi marks** |
| `8448-8703` | 80 kB | Letterlike and number forms |
| `64256-65023` | 526 kB | Arabic Presentation Forms-A (3 ranges) |
| `65024-65279` | 67 kB | Arabic Presentation Forms-B — **shaped Arabic** |

The Presentation Forms ranges are not optional: MapLibre shapes Arabic into presentation forms
before looking up glyphs. Omit them and Arabic labels render as boxes.

**Totals, measured:**

| Asset | Size |
|---|---|
| Noto Sans Regular, needed ranges | 1.16 MB |
| Noto Sans Medium, needed ranges | 1.15 MB |
| Noto Sans Italic, needed ranges | 0.32 MB |
| Sprites — `dark` + `dark@2x` (the app is a dark theme) | 0.05 MB |
| **Total added to the APK** | **~2.7 MB** |

For comparison, shipping all 256 ranges of all three stacks would be 11.1 MB. The range selection
saves 8.4 MB and costs one comment in the build explaining which blocks are included and why, so
nobody "tidies up" the Arabic Presentation Forms later — the same class of decision as the
Latin-digits rule.

**APK impact overall:** ~2.7 MB of assets, plus MapLibre Native's per-ABI `.so` files. From
17 MB now, expect roughly **25–28 MB per ABI**. Ship an **App Bundle**, not a universal APK, or
that number doubles for no benefit — `BUILD_PLAN.md` §8 already says this.

**`asset://` for glyphs and sprites — VERIFIED against MapLibre Native's source.**

Previously flagged here as the riskiest inference in this document. Settled by reading the code
rather than reasoning about the docs:

```cpp
// platform/default/src/mbgl/storage/asset_file_source.cpp
bool acceptsURL(const std::string& url) {
    return url.starts_with(mln::util::ASSET_PROTOCOL);
}
bool AssetFileSource::canRequest(const Resource& resource) const {
    return acceptsURL(resource.url);
}
```

`canRequest` tests **the URL scheme and nothing else** — there is no `Resource::Kind` filter, so
it accepts glyphs, sprites and style JSON exactly as it accepts anything else.
`MainResourceLoader` dispatches on `canRequest` alone.

And the reason `pmtiles://asset://` is the exception is now concrete: `PMTilesFileSource` sets
`resource.dataRange` (`pmtiles_file_source.cpp` lines 147, 252, 424) to request byte ranges, and
the Android asset source has no way to serve a partial read. Glyph and sprite requests carry no
`dataRange`, so they never touch that path.

**Conclusion: ship the fonts in `src/main/assets/` and point the style at
`asset://fonts/{fontstack}/{range}.pbf`.** No first-launch unpacking needed.

---

## 5. What I could not verify from here

Stated plainly so nothing here is mistaken for tested:

| Claim | Status |
|---|---|
| GitHub release assets honour `Range` | **VERIFIED** 2026-08-19 on the user's machine: `206`, `accept-ranges: bytes`, correct `content-range`. |
| MapLibre accepts `asset://` for glyphs | **VERIFIED** against MapLibre Native source: `canRequest` is scheme-only, no kind filter. |
| Cloudflare R2 honours `Range` | Not tested — blocked from the sandbox. Guaranteed by S3 API compatibility. Moot now that Releases is chosen. |
| Extract sizes at each zoom | **Modelled**, ±40%. Validated against Protomaps' own published rule of thumb. `REGION_FILES.md` measures them exactly. |
| Everything else | Measured here, or quoted from a primary source and linked. |

**Decisions taken (2026-08-19):** GitHub Releases; maxzoom **14**; **two separate files**, Morocco
and Mauritania, downloaded independently.

---

## Sources

- [About releases — GitHub Docs](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases) — 2 GiB/file, 1000 assets, no total-size or bandwidth limit
- [PMTiles — MapLibre Android Examples](https://maplibre.org/maplibre-native/android/examples/data/PMTiles/) — `pmtiles://file://`, 11.7.0+, no `asset://`, no offline-pack support
- [Basemap Downloads — Protomaps Docs](https://docs.protomaps.com/basemaps/downloads) — 120 GB planet, z0–15, zoom doubling, BLAKE3 hashes
- [Basemap Layers — Protomaps Docs](https://docs.protomaps.com/basemaps/layers) — buildings merged at z0–14, individual at z15+
- [protomaps/basemaps-assets](https://github.com/protomaps/basemaps-assets) — the fonts and sprites, measured locally
- [Geofabrik Africa downloads](https://download.geofabrik.de/africa.html) — Morocco 231 MB, Mauritania 29.1 MB (2026-08-17)
- [GitHub Pages hosting .pmtiles — Protomaps Discussion #582](https://github.com/protomaps/PMTiles/discussions/582) — Pages returns 206; the reported bug is Firefox-side
- [Cloudflare R2 pricing](https://egresscost.com/cloudflare/) — 10 GB free, zero egress
