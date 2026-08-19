# Producing and uploading the two map files

One-off task, done on your machine. The app is already built against these URLs and handles
their absence cleanly, so there is no rush and no ordering problem — but until this is done, the
region list will say the files are not published yet.

Budget about 40 minutes, most of it waiting on the extract.

## 1. Install the CLI

```bash
brew install pmtiles          # or: https://github.com/protomaps/go-pmtiles/releases
pmtiles version               # want 1.30.x or newer
```

## 2. Find the current planet build

```bash
BUILD=$(curl -s https://maps.protomaps.com/builds/ | grep -o 'v4[^"]*\.pmtiles' | tail -1)
echo "$BUILD"                 # e.g. 20260818.pmtiles
```

## 3. Cut the two extracts

These read the 120 GB planet file **over HTTP by range request** — nothing is downloaded whole,
only the tiles inside each bounding box. Expect roughly 10–20 minutes each on a decent
connection, and far less traffic than the output size might suggest.

```bash
pmtiles extract "https://build.protomaps.com/$BUILD" morocco-z14.pmtiles \
    --bbox=-17.10,20.77,-0.99,35.95 --maxzoom=14

pmtiles extract "https://build.protomaps.com/$BUILD" mauritania-z14.pmtiles \
    --bbox=-17.07,14.72,-4.83,27.30 --maxzoom=14
```

The Morocco box covers Western Sahara too — the app is used across that whole area and a
navigation aid that stops working at a disputed line is no use to the person holding it.

**Expected sizes.** These are modelled, not measured (MAP_RESEARCH.md §1), so treat them as a
sanity range rather than a target:

| File | Expect | Worry if |
|---|---|---|
| `morocco-z14.pmtiles` | ~110–260 MB | under 40 MB or over 600 MB |
| `mauritania-z14.pmtiles` | ~20–55 MB | under 8 MB or over 150 MB |

Wildly small usually means the bounding box was wrong. Wildly large usually means `--maxzoom`
was dropped.

## 4. Check them **before** uploading

A bad extract found after a 180 MB upload is a wasted afternoon. Three checks, one minute:

```bash
# a) Header sane? Want: type vector, maxzoom 14, and bounds matching the box you asked for.
pmtiles show morocco-z14.pmtiles

# b) A tile actually exists where the app will look. Casablanca, z14:
pmtiles tile morocco-z14.pmtiles 14 7846 6568 | wc -c       # want a few thousand bytes, not 0

# c) Nouakchott, in the Mauritania file, z14:
pmtiles tile mauritania-z14.pmtiles 14 7465 7355 | wc -c    # want non-zero

# d) A corner each, to prove the box did not get clipped:
pmtiles tile morocco-z14.pmtiles    14 7880 6543 | wc -c    # Rabat
pmtiles tile mauritania-z14.pmtiles 14 7416 7217 | wc -c    # Nouadhibou
```

If (b) or (c) returns 0 bytes, the box is wrong — do not upload.

Then record the real numbers:

```bash
ls -l *.pmtiles
shasum -a 256 morocco-z14.pmtiles mauritania-z14.pmtiles
```

## 5. Upload

The filenames must match exactly — the app builds its URLs from them.

```bash
gh release create maps-v1 \
    --title "Map regions v1" \
    --notes "Protomaps Basemap v4, maxzoom 14. Morocco (incl. Western Sahara) and Mauritania. ODbL, © OpenStreetMap contributors." \
    morocco-z14.pmtiles mauritania-z14.pmtiles
```

Or by hand: Releases → Draft a new release → tag `maps-v1` → attach both files.

The tag is `maps-v1`, deliberately separate from the app's version tags, so that cutting a new
app release does not imply re-uploading 218 MB of maps.

## 6. Send me the numbers

Paste back the output of step 4 — the `ls -l` sizes and the two SHA-256 hashes. They go into
`RegionCatalogue.kt`, which currently carries estimates and `checksum = null`. Once the real
hashes are in, a corrupted download is detected rather than rendered.

## 7. Confirm

```bash
curl -sIL -H "Range: bytes=0-99" \
  https://github.com/Brahim-BT/gps-arrow/releases/download/maps-v1/morocco-z14.pmtiles \
  | grep -iE "^HTTP/|accept-ranges|content-range"
```

Want `206` and `content-range: bytes 0-99/<the size from step 4>`. That is the same check that
verified resumable downloads work, now against the real file.

---

### Licensing

The Protomaps basemap is an ODbL Produced Work from OpenStreetMap. **OpenStreetMap attribution is
required** wherever the map is shown — the app needs "© OpenStreetMap contributors" visible on
the map screen, not buried in About. That is a v1 build item, noted here because it is a
condition of using the data rather than a nicety.
