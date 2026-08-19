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

> **The estimates that were here were wrong and have been replaced.** z14 is now measured. The
> other rows are re-derived from that measurement and are given as ranges, because two defensible
> scaling rules disagree and I have one data point. See "The size model was wrong" below for what
> broke and why. The old numbers were: Morocco 183 MB, Mauritania 35 MB, both 218 MB at z14.

All **measured** except z15. Every level below z14 was cut locally from the z14 archive in
seconds — `pmtiles extract` reads local files, so no planet re-download is needed.

| maxzoom | Morocco + W. Sahara | Mauritania | Both | What is actually missing |
|---|---|---|---|---|
| z12 | **61,851,096** (62 MB) | **20,699,748** (21 MB) | **83 MB** | Service roads, footways, cycleways, steps. Residential streets *are* present. |
| z13 | **133,209,973** (133 MB) | **42,919,292** (43 MB) | **176 MB** | Sidewalks, crossings, tram/subway lines |
| z14 | **263,915,307** (264 MB) | **83,111,730** (83 MB) | **347 MB** | Individual building footprints (merged blobs instead) |
| z15 | ~528 MB | ~166 MB | ~694 MB | Nothing — the maximum the planet build offers |

### What each level actually contains

Read from the basemap generator's source (`protomaps/basemaps`, `tiles/.../layers/*.java`), not
from the prose docs. `pm:minzoom` is the level at which a feature first enters a tile.

| Feature | minzoom | z12 | z13 | z14 |
|---|---|---|---|---|
| motorway / trunk / primary / secondary / tertiary | 3–9 | ✓ | ✓ | ✓ |
| sand, bare rock, scree (`Landuse`, zoom range 7–15) | 7 | ✓ | ✓ | ✓ |
| rivers and major wadis | 9–10 | ✓ | ✓ | ✓ |
| village names | 10 | ✓ | ✓ | ✓ |
| hamlet names, buildings (merged) | 11 | ✓ | ✓ | ✓ |
| **`highway=track` — pistes and desert tracks** | **12** | ✓ | ✓ | ✓ |
| residential, unclassified | 12 | ✓ | ✓ | ✓ |
| small localities (population fallback) | 12 | ✓ | ✓ | ✓ |
| **isolated dwellings, farms** | **13** | — | ✓ | ✓ |
| **minor wadis (`waterway=stream`)** | **13** | — | ✓ | ✓ |
| footway, path, cycleway, steps | 13 | — | ✓ | ✓ |
| service roads, driveways, alleys | 13 | — | ✓ | ✓ |
| sidewalks, crossings, tram/subway | 14 | — | — | ✓ |
| individual building footprints | 15 | — | — | — |
| drinking water, water points | 15 | — | — | — |

Three things worth stating plainly because they are easy to assume otherwise:

- **`highway=track` is *not* in the z13 bucket.** The rule promoting paths to 13 lists
  `path, cycleway, bridleway, footway, steps` — `track` is absent, so it keeps `pm:minzoom 12`.
  Desert pistes are present at every level we would ship.
- **`surface` is not carried at any zoom.** The basemap has no paved/unpaved distinction, so the
  map cannot tell anyone whether a route is sealed. True at z14 as much as at z12.
- **Water sources are not usable data here.** `amenity=water_point` and `watering_place` fall to
  the base POI rule at z15 (z16 if unnamed, then clamped to 15), so they are absent from every
  level below z15 — and `man_made=water_well`, which is how traditional wells are usually tagged,
  **is not carried by the schema at all.** Nothing in this map should be presented as showing
  water, at any zoom. OSM's coverage of Saharan wells is sparse and unverified, and a navigation
  aid that implies otherwise would be worse than one that says nothing.

The consequence for the level choice: **z13 → z14 adds sidewalks, crossings and tram lines and
nothing else.** For a user in open desert that is nothing at all.

### Which scaling rule survived

Measured level-to-level ratios:

| | z13/z12 | z14/z13 |
|---|---|---|
| Morocco | 2.154 | 1.981 |
| Mauritania | 2.073 | 1.936 |

**Rule (a) — Protomaps' "each additional zoom level roughly doubles the size" — is correct**, and
holds to within 7% across both regions and both steps despite an 8:1 difference in OSM data
density. Predicting from the z14 measurement alone: Morocco z13 −0.9%, z12 +6.7%; Mauritania
z13 −3.2%, z12 +0.4%.

**Rule (b) — my floor model — is refuted.** It predicted z14→z13 ratios of 2.51 (Morocco) and
3.23 (Mauritania); the observed values are 1.98 and 1.94. The error I made was assuming the
area-driven floor scales with *tile count*, so that dropping a level divides it by four. It does
not. The floor is geometry covering a fixed area; slicing that area into four times fewer tiles
does not remove it, it just partitions it differently.

So the floor is real — it is why sizing from OSM data volume failed — but it does **not** change
the zoom scaling. Those are two separate questions and I conflated them. The corrected position:
*size scales cleanly as 2^zoom regardless of terrain density; only the constant of proportionality
depends on the terrain.* That is simpler than either model I proposed, and it is the one the data
supports.

The z15 row is now an extrapolation from a rule tested twice per region rather than a guess,
which is why it is a single figure again rather than a range.

**If you want an exact z13 figure, it costs under a minute and no network.** `pmtiles extract`
reads local archives, so a lower-zoom cut can be taken from the z14 file we already have rather
than from the planet:

```bash
pmtiles extract morocco-z14.pmtiles    morocco-z13.pmtiles    --maxzoom=13
pmtiles extract mauritania-z14.pmtiles mauritania-z13.pmtiles --maxzoom=13
ls -l *-z13.pmtiles
```

That is strictly better than any number I can give, and it also settles which of the two scaling
rules below is right — which would make every remaining row in the table trustworthy instead of a
range. I would do this before quoting the user anything.

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

### The size model was wrong — what broke, and the corrected one

Predicted against actual, from the 2026-08-19 build:

| | predicted | actual | error |
|---|---|---|---|
| Morocco | 183 MB | 264 MB | **+44%** |
| Mauritania | 35 MB | 83 MB | **+137%** |
| Both | 218 MB | 347 MB | +59% |

Stated confidence was ±40%. Morocco scraped inside it; Mauritania missed by more than three times
the stated band. A confidence interval that a sample falls outside by that margin was not a
confidence interval, it was a guess with error bars drawn on afterwards.

**The old model.** `size(z15) ≈ 120 GB × country_pbf / planet_pbf`, halved per level below 15.
It assumed vector-tile bytes are proportional to OSM feature volume.

**Two plausible causes, both ruled out by measurement:**

- *Directory overhead underestimated?* No. Root directory, leaf directories and metadata together
  are **0.23%** of the Morocco file and **0.49%** of Mauritania's. Negligible at any scale.
- *Deduplication over-credited in sparse terrain?* No, and this one is genuinely surprising.
  Dedup is **3.39:1** for Morocco and **3.35:1** for Mauritania — essentially identical. Empty
  desert does not collapse better than a mapped city.

**The actual cause.** One measurement gives it away:

| | OSM data | bytes per tile |
|---|---|---|
| Morocco : Mauritania | **7.94 : 1** | **1.89 : 1** |

Eight times the OSM data buys under twice the bytes per tile. Tile size is therefore *mostly not*
a function of OSM feature volume, and a model proportional to it cannot fit both countries — it
will fit the dense one roughly and the sparse one terribly, which is exactly the observed pattern.

What the model left out is that the Protomaps basemap is **not only OSM**. The `earth` layer comes
from OSMCoastline, `landcover` from the Daylight distribution, and low-zoom `water` and
`boundaries` from Natural Earth. Every land tile carries those polygons whether or not a single
road crosses it — an **area-driven floor** that `country_pbf` does not measure at all.

Fitting `bytes_per_tile = A + B × (OSM bytes per tile)` to the two measured archives:

```
bytes_per_tile = 137.5 + 0.68 × (OSM PBF bytes per tile)
```

That floor of ~137 bytes/tile is **40% of the Morocco file and 76% of Mauritania's**. The emptier
the region, the more of it is floor — which is precisely why the old model's error was worse for
the emptier country.

**The bitter part:** the first version of this estimate *had* a floor term, `tile_count × 600 B`.
I removed it, reasoning that deduplication would collapse empty tiles to nothing. The measured
dedup is 3.35:1, not infinite. The structure was right and I talked myself out of it; only the
constant was wrong, and it was wrong by 4.4×, not by being present.

**Status of the corrected model.** It is a two-parameter fit to two data points, so it reproduces
them exactly and that means **nothing**. It is calibration, not validation. Its value is that it
is anchored on measurements of this exact pipeline rather than on a chain of ratios through a
planet file, and that it has the right *shape* — a floor plus a density term.

**Why the other rows are ranges.** Dropping a zoom level scales the two components differently:

- the **floor** goes with tile count, which falls **4×** per level dropped;
- the **feature payload** follows Protomaps' documented "each zoom level roughly doubles", so **2×**.

Applying the doubling rule to the whole file gives Morocco z13 = 132 MB. Splitting by the measured
floor share gives 105 MB. Both are defensible from what is known, they differ by 25%, and one data
point cannot separate them. Hence ranges — and hence the local `--maxzoom=13` re-cut above, which
replaces the whole argument with a number.

**One further correction.** These are `--bbox` extracts, and a bounding box is a rectangle while a
country is not. The Mauritania box takes in slices of Western Sahara, Mali, Senegal and Algeria;
the Morocco box takes in a lot of Atlantic. Sizing a bbox extract from the OSM volume of the
country whose name is on it is wrong twice over. `--region=` with a GeoJSON country polygon would
cut closer, at the cost of needing a polygon file.

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
