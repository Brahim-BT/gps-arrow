# Producing and uploading the two map files

One-off task, done on your machine. The app is already built against these URLs and handles their
absence cleanly, so there is no ordering problem — until this is done, the region list simply says
the files are not published yet.

Budget about 40 minutes, most of it waiting.

> **An earlier version of this document had a build-discovery line that could never have worked.**
> It grepped for `v4` in filenames that are actually dated (`20260819.pmtiles`), against a page
> that is client-rendered and returns nothing to `curl` anyway. `$BUILD` came out empty and the
> URL collapsed to a 404. Everything below has been re-derived from a primary source, and the
> parts that are still assumptions are marked as such.

## 1. Install the CLI

The project's own instruction is to take a binary from
[the releases page](https://github.com/protomaps/go-pmtiles/releases) — that is what its README
says and it is the only method verified here. If `brew install pmtiles` works on your machine,
fine; there is no `protomaps/homebrew-tap`, so any formula would be coming from homebrew-core and
I have not confirmed it exists.

```bash
pmtiles version          # want 1.30.x or newer
```

## 2. Pick the build

**Builds are dated, roughly daily, and named `YYYYMMDD.pmtiles`.** There is no `latest` alias and
no machine-readable index I could verify, so this step is deliberately manual: open
<https://maps.protomaps.com/builds/> in a browser and read the most recent date.

The `4,5` you will see beside each build is a *compatible schema versions* column, not part of
the filename.

```bash
BUILD_DATE=20260819                      # <- read this off the page; it will not be today's example
: "${BUILD_DATE:?set BUILD_DATE to the date shown at https://maps.protomaps.com/builds/}"
PLANET="https://build.protomaps.com/${BUILD_DATE}.pmtiles"

# Fail now, loudly, rather than 20 seconds into an extract.
curl -sfI "$PLANET" >/dev/null \
  || { echo "No build at $PLANET — check the date on the builds page."; exit 1; }
echo "OK: $PLANET"
```

The `:?` and the `curl -sfI` guard exist because the failure this replaces was exactly an empty
variable producing a plausible-looking URL that 404s later.

## 3. Check the size before committing to the download

`--dry-run` computes the extract and reports its size **without downloading the tiles**. Seconds,
not minutes. This is the real number — use it rather than any estimate.

```bash
pmtiles extract "$PLANET" /dev/null --bbox=-17.30,20.60,-0.80,36.10 --maxzoom=14 --dry-run
pmtiles extract "$PLANET" /dev/null --bbox=-17.30,14.50,-4.60,27.40 --maxzoom=14 --dry-run
```

Look for the last line: `... for an archive size of N MB`.

**Expected, and why it may not match my earlier estimate.** `MAP_RESEARCH.md` put Morocco at
~183 MB and Mauritania at ~35 MB. Those were derived from each *country's* OSM data volume, but a
`--bbox` is a rectangle and a country is not: the Mauritania box necessarily includes slices of
Western Sahara, Algeria, Mali and Senegal, and the Morocco box includes a lot of Atlantic. Expect
the real figures to come in **above** the estimate, Mauritania especially. That is a flaw in how I
estimated, not in the extract. The dry-run number is the truth; tell me both and I will put them
in the catalogue.

If a number comes back wildly larger than expected, the usual cause is a dropped `--maxzoom`.

## 4. Cut the two extracts

> **Already done — 2026-08-19.** Both files exist and their real numbers are in
> `RegionCatalogue.kt`. Steps 4–5 are kept for when they need re-cutting against a newer planet
> build. **The boxes below are the ones the existing archives were actually cut with**, and they
> must stay identical to the boxes in `RegionCatalogue.kt`; see the note at the end of step 5.

```bash
pmtiles extract "$PLANET" morocco-z14.pmtiles \
    --bbox=-17.10,20.77,-0.99,35.95 --maxzoom=14

pmtiles extract "$PLANET" mauritania-z14.pmtiles \
    --bbox=-17.07,14.72,-4.80,27.30 --maxzoom=14
```

These read the planet **over HTTP by range request** — nothing is fetched whole, only the tiles
inside each box. 10–20 minutes each on a decent connection.

**About the boxes.** The Morocco box covers Western Sahara: the app is used across that whole
area and a navigation aid that stops at a disputed line is no use to the person holding it. They
overlap across the Morocco/Mauritania frontier, so someone driving over it is never in a gap
between regions.

They are *not* padded past the borders. Padding by ~0.2° would be slightly better — extra desert
and ocean tiles are byte-identical and PMTiles deduplicates them, so it is nearly free, while
clipping loses border towns. If these are ever re-cut, padding is worth doing. The two tightest
points under the current boxes are **Nouadhibou**, 0.035° (~3.7 km) inside Mauritania's western
edge, and **Lagouira**, 0.047° inside Morocco's. Both edges face open Atlantic, so no land is
being clipped — but those are the two to re-check if the numbers change.

## 5. Check them **before** uploading

A bad extract found after a 180 MB upload is a wasted afternoon.

```bash
# a) Structural check — this is a real subcommand and the strongest single test.
pmtiles verify morocco-z14.pmtiles
pmtiles verify mauritania-z14.pmtiles

# b) Header sane? Want tile type vector, maxzoom 14, bounds matching the box you asked for.
pmtiles show morocco-z14.pmtiles

# c) Tiles actually exist where the app will look (z14 x y, computed, not guessed):
pmtiles tile morocco-z14.pmtiles    14 7846 6568 | wc -c    # Casablanca
pmtiles tile morocco-z14.pmtiles    14 7465 7081 | wc -c    # Dakhla, deep in Western Sahara
pmtiles tile mauritania-z14.pmtiles 14 7465 7355 | wc -c    # Nouakchott
pmtiles tile mauritania-z14.pmtiles 14 7862 7424 | wc -c    # Néma, far east
```

Each of (c) should print a few thousand bytes. A `0` means the box was wrong — do not upload.

Then record the real numbers:

```bash
ls -l *.pmtiles
shasum -a 256 morocco-z14.pmtiles mauritania-z14.pmtiles
```

**What the 2026-08-19 build produced**, now in `RegionCatalogue.kt`:

| | bytes | header bounds | zoom | dedup |
|---|---|---|---|---|
| `morocco-z14.pmtiles` | 263,915,307 | -17.10, 20.77, -0.99, 35.95 | 0–14 | 70.5% |
| `mauritania-z14.pmtiles` | 83,111,730 | -17.07, 14.72, -4.80, 27.30 | 0–14 | 70.2% |

```
morocco    521d5dc075616f1e6e16bbddfeed4fd906baac6310499c70e6688618fba539fc
mauritania 0f69b393ca0b7c20bf002f12c3ae00a1c2df92a41ca6d4e6dd1b22c2320f915c
```

Both parse clean through the app's own `Pmtiles.check`: magic and version good, MVT tiles, gzip
compression (which MapLibre accepts), every section inside the file.

Note the deduplication figure — about 70% of addressed tiles share content with another tile.
That is the empty desert collapsing, and it is why sizing an extract by land area is wrong.

> **If you re-cut these, the `--bbox` values in step 4 and the `bbox = BoundingBox(...)` values in
> `RegionCatalogue.kt` must change together.** The catalogue box is what decides whether the app
> claims to have a map for where you are standing; if it is wider than the archive, the app
> promises a map and shows a void. `pmtiles show <file>` prints the archive's real bounds — those
> are the authority, not the command you thought you ran.

## 6. Upload

Filenames must match exactly — the app builds its URLs from them.

```bash
gh release create maps-v1 morocco-z14.pmtiles mauritania-z14.pmtiles \
    --title "Map regions v1" \
    --notes "Protomaps Basemap v4, maxzoom 14. Morocco (incl. Western Sahara) and Mauritania. ODbL, © OpenStreetMap contributors."
```

Or by hand: Releases → Draft a new release → tag `maps-v1` → attach both files.

The tag is `maps-v1`, deliberately separate from the app's version tags, so cutting a new app
release does not imply re-uploading 218 MB of maps.

## 7. Confirm, and send me the numbers

```bash
curl -sIL -H "Range: bytes=0-99" \
  https://github.com/Brahim-BT/gps-arrow/releases/download/maps-v1/morocco-z14.pmtiles \
  | grep -iE "^HTTP/|accept-ranges|content-range"
```

Want `206` and `content-range: bytes 0-99/<the size from step 5>`.

Then paste back the `ls -l` sizes and the two SHA-256 hashes. `RegionCatalogue.kt` currently holds
estimates and `checksum = null`; with the real values in, a corrupted download is detected instead
of rendered.

---

### Licensing

The Protomaps basemap is an ODbL Produced Work from OpenStreetMap. **OpenStreetMap attribution is
required** wherever the map is shown — "© OpenStreetMap contributors" must be visible on the map
screen, not buried in About. A v1 build item, noted here because it is a condition of using the
data rather than a nicety.

### What in this document is verified, and what is not

| Claim | Status |
|---|---|
| `pmtiles extract --bbox --maxzoom --dry-run`, `verify`, `show`, `tile z x y`, `version` | **Verified** — read from `main.go` in protomaps/go-pmtiles |
| `--dry-run` prints the archive size | **Verified** — the size log sits outside the `if !dryRun` block |
| `gh release create [<tag>] [<files>...] --title --notes` | **Verified** — read from cli/cli's command definition |
| Build files are `YYYYMMDD.pmtiles` at `build.protomaps.com` | **Verified** by you, by rendering the page |
| z14 tile coordinates in step 5 | **Computed**, not recalled |
| Bounding boxes contain every listed town with ≥0.2° margin | **Computed** against documented extreme points |
| No machine-readable build index exists | **Not established** — I could not reach the host to check. The manual step is chosen because it cannot fail silently, not because I proved there is no alternative. |
| `brew install pmtiles` | **Not verified** — no protomaps tap exists; use the releases page |
| Expected extract sizes | **Estimated and known to be low** — see step 3 |
