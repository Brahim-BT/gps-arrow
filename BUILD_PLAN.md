# GPS Arrow — Offline-First Navigation for Android

**Build plan and technology decisions · August 2026**

This is a decision document, not a survey. Every section says what to use and why, and names the option that was rejected. Verification links are at the bottom.

---

## 0. The product in one paragraph

An app that works with the radio off. You open it, it acquires a GNSS fix from the satellites, and it draws a large arrow pointing at a destination you saved earlier, with a distance readout. No tiles, no geocoder, no account, no network — ever, for the core loop. Maps are a **separate, optional tier** you download while you happen to have connectivity, and routing is a third tier on top of that. The product promise is that tier 1 never degrades because tiers 2 and 3 are missing.

That tiering is the whole architecture. It is enforced in code by a module boundary: the arrow never imports anything from the map or routing modules.

---

## 1. Decisions at a glance

| Question | Decision | Rejected |
|---|---|---|
| Map renderer | **MapLibre Native Android 11.11.x** (`org.maplibre.gl:android-sdk`), BSD-2-Clause | Mapsforge (LGPL-3, bespoke `.map`, no MVT/GL styling); Organic Maps / CoMaps engine (an app, not a library) |
| Offline tile container | **PMTiles**, one file per region, loaded via `pmtiles://file://` | MBTiles (needs a SQLite shim + custom source); MapLibre `OfflineManager` tile packs (per-tile HTTP, slow, server-heavy) |
| Map data | **Protomaps Basemap v4** (OSM-derived, ODbL Produced Work), self-hosted | OpenMapTiles schema (extra "OpenMapTiles" attribution obligation); OpenFreeMap public instance (not designed for bulk client downloads) |
| Region cutting | **Server-side, pre-cut, nightly** with `pmtiles extract` → static object storage + a JSON catalogue | Client-side extraction (would require reimplementing PMTiles directory traversal on Android); on-demand server cutting (uncacheable, expensive) |
| Routing (v2) | **BRouter** (`org.btools:brouter-core`, MIT, pure Java, `.rd5` 5°×5° tiles) | Valhalla (best instructions, but C++/JNI build + admin/timezone DB tile pipeline); GraphHopper (Apache-2 but Android support officially dropped) |
| Location source | **`LocationManager` directly**, `GPS_PROVIDER` first, `FUSED_PROVIDER` (API 31+, platform-level) as an opportunistic add | Play Services `FusedLocationProviderClient` — unavailable on de-Googled devices, which is precisely this app's audience |
| Heading | **`TYPE_ROTATION_VECTOR`** → `getOrientation`, remapped for display rotation, circular-mean smoothed, with **GPS course-over-ground takeover** above a speed threshold | Raw accelerometer + magnetometer fusion (noisier, more code); `TYPE_GAME_ROTATION_VECTOR` (no magnetometer, yaw drifts — unusable for absolute bearing) |
| True north | **WMM evaluator in `:core`, fed by a public-domain `WMM*.COF` asset**, with `android.hardware.GeomagneticField` as fallback | Relying on `GeomagneticField` alone — its coefficients are frozen at OS build time and the docs still describe WMM-2020 |
| UI | **Kotlin + Jetpack Compose**, Material 3 | Views/XML — except the one place it is forced (below) |
| Storage for map files | **`getExternalFilesDir("regions")`** — app-scoped, no permission, survives updates, removed on uninstall | `MediaStore` / `MANAGE_EXTERNAL_STORAGE` (a permission you will never get past Play review for this) |
| Build stack | AGP **8.13.0**, Gradle 8.14.3, Kotlin **2.2.10**, Compose BOM **2025.10.00**, `compileSdk`/`targetSdk` **36**, `minSdk` **26** | AGP 9.x — see §8 |

---

## 2. Tier 1: the arrow (the actual product)

### 2.1 Location: skip Play Services entirely

`FusedLocationProviderClient` is a Google Play Services API. On a GrapheneOS / LineageOS / Chinese-market device without GMS it is simply not there, and microG's reimplementation has a history of blocking on fixes it should pass through. An app whose entire pitch is "works anywhere with no internet" cannot have a Google dependency on its critical path.

Use `android.location.LocationManager`:

- **`GPS_PROVIDER`** — raw GNSS, no network, works in the middle of the ocean. This is the source of truth.
- **`FUSED_PROVIDER`** (API 31+) — this is the *platform's* fused provider, not the Play Services one. Free to request opportunistically; on devices that have it, it improves indoor/urban behaviour. Never depend on it.
- **`NETWORK_PROVIDER`** — request it only to render a low-confidence "last known position" while the GNSS fix is still cold. Never feed it to the arrow.

Register a `GnssStatus.Callback` to surface satellites-used/satellites-visible. Cold-start TTFF with no assistance data (no A-GNSS, because no network) is **30–90 seconds under open sky**, occasionally several minutes. This is the single biggest UX risk in the app and it must be designed for explicitly, not treated as an error state: show a satellite counter and a "searching — this can take a minute with no internet" line, and keep the last known fix on screen greyed out in the meantime.

Accuracy gating: reject fixes with `accuracy > 100 m` for the arrow, and show a staleness badge once a fix is older than 10 s.

### 2.2 Heading: three sources, one state machine

The arrow's rotation is `bearingToDestination − deviceHeading`. `bearingToDestination` is exact geodesy. `deviceHeading` is the hard part.

```
if (speed > 2.5 m/s && fix.hasBearing())      → GPS course over ground   (true north, no magnetometer)
else if (magnetometer accuracy >= MEDIUM)     → rotation vector + declination  (true north)
else                                          → rotation vector, flagged "compass needs calibration"
```

Rationale: above walking pace, course-over-ground is *strictly better* than the magnetometer — it is unaffected by the car's steel body, phone mounts, speaker magnets, or the user holding the phone flat-ish. Below that speed COG is noise, so the magnetometer wins. The hysteresis band (take over at 2.5 m/s, hand back at 1.5 m/s) stops the arrow flickering between sources at the boundary.

Implementation notes that matter:

- `SensorManager.getRotationMatrix` / `getOrientation` yield azimuth relative to **magnetic** north. Add declination to get true north.
- Remap the coordinate system for `Display.rotation` (`remapCoordinateSystem` with `AXIS_Y`/`AXIS_MINUS_X` etc.) or the arrow is 90° wrong in landscape.
- Smooth with a **circular** low-pass (filter `sin`/`cos` separately, then `atan2`). Filtering degrees directly makes the arrow spin the long way round at the 359°→0° wrap.
- Listen for `onAccuracyChanged`; on `SENSOR_STATUS_ACCURACY_LOW`, show the figure-8 calibration prompt. Do not silently show a wrong arrow.
- Sensor rate: `SENSOR_DELAY_GAME` (~50 Hz) is plenty; `FASTEST` just burns battery.

### 2.3 True north offline

Declination is a pure function of (lat, lon, altitude, date) evaluated from the World Magnetic Model's spherical-harmonic coefficients — 90 numbers, no network, microseconds to evaluate. Two providers behind one interface:

1. **`WmmDeclination`** — parses a `WMM.COF` text file from `assets/geomag/` and runs the standard degree-12 spherical harmonic expansion. WMM2025 is public domain (NOAA NCEI / NGA), valid to late 2029, and is a ~4 KB text file. **Drop the file in; the scaffold ships the parser and evaluator but not the coefficients** (see README §"Magnetic declination").
2. **`FrameworkDeclination`** — wraps `android.hardware.GeomagneticField`. Zero setup, but its coefficients ship with the OS image, and the platform docs still reference WMM-2020. On an old or never-updated device you inherit a stale model. Fine as a fallback, wrong as the only implementation.

Error budget: a stale WMM epoch costs well under a degree almost everywhere; an uncalibrated magnetometer costs 10–30°. Do not over-engineer #1 before fixing calibration UX.

### 2.4 Distance and bearing

Use **great-circle (haversine) distance and initial bearing** on a spherical Earth (R = 6 371 008.8 m). Sphere-vs-ellipsoid error is ~0.3 % — irrelevant next to a 5 m GNSS fix, and Vincenty has convergence failures on near-antipodal pairs that will eventually crash a field app. Ship the sphere.

Show distance with sane precision: `<1 km` → metres rounded to 10; `1–100 km` → one decimal; `>100 km` → integer km. Offer metric/imperial/nautical.

### 2.5 Foreground service

Navigation must survive screen-off. Declare `android:foregroundServiceType="location"` plus `FOREGROUND_SERVICE_LOCATION`, and **start the service only from a visible activity** — from Android 14 you cannot start a location FGS from the background without `ACCESS_BACKGROUND_LOCATION`, which this app should not request (it triggers the Play "background location" review process for no benefit).

Battery: at 1 Hz GNSS the app draws roughly 100–180 mW on a modern SoC. Offer a "power saving" mode that drops to a 4-second interval and lets the arrow dead-reckon between fixes — for a walk-to-a-waypoint use case the difference is hours.

### 2.6 Permissions flow

`ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` requested together (Android 12+ shows the precise/approximate toggle; if the user picks approximate, tell them the arrow needs precise and offer a one-tap re-request). `POST_NOTIFICATIONS` on API 33+, requested lazily at the moment the user starts a navigation session, not at launch. Never request `ACCESS_BACKGROUND_LOCATION`.

---

## 3. Tier 1.5: getting destinations in, with no geocoder

There is no server, so there is no search. Every input path is a **parser**. One `parseDestination(String)` entry point that tries, in order:

| Format | Example | Notes |
|---|---|---|
| Decimal degrees | `48.8584, 2.2945` | Also `48.8584 2.2945`, `48.8584/2.2945` |
| DMS / DM | `48°51'29.9"N 2°17'40.2"E` | Accept `'`, `′`, `"`, `″`, and unicode minus |
| `geo:` URI | `geo:48.8584,2.2945?z=17` | RFC 5870. **Register an intent filter for this** — it is how other offline apps hand off a point |
| Plus code (full) | `8FW4V75V+8Q` | Self-contained, decodes offline |
| Plus code (short) | `V75V+8Q Paris` | Needs a reference point. Use the **current fix** and ignore the locality text — say so in the UI |
| MGRS / USNG | `31U DQ 48251 11924` | Military/SAR interchange format, decodes offline |
| UTM | `31U 452170 5411050` | |
| OSM / Geo URL with coords | `.../#map=17/48.8584/2.2945`, `?mlat=…&mlon=…` | Pure string parsing, no network |
| Google Maps URL with coords | `.../@48.8584,2.2945,17z`, `?q=48.8584,2.2945` | Works offline **only when the URL already contains coordinates** |

**Say the quiet part out loud in the UI:** a shortened share link (`maps.app.goo.gl/…`, `goo.gl/maps/…`) is an opaque token that only Google's server can expand. It cannot be resolved offline, ever. The paste screen should detect these and show: *"This is a short link — open it once while online, then copy the full URL."* Silently failing here is the single most likely one-star review.

Also support: **drop a pin** (long-press on the map in tier 2, or on a blank grid in tier 1), **"save my current position"** (the most-used button in practice — panic button for "remember where I parked"), and **import/export GPX + a plain CSV** so people can prepare waypoints on a desktop.

Destinations are a flat list with `id, name, lat, lon, note, createdAt, source`. That is the entire data model for v0. No folders, no sync, no cloud.

---

## 4. Tier 2: optional offline vector maps

### 4.1 Why PMTiles-as-a-file, not offline tile packs

MapLibre Native has an `OfflineManager` that walks a region and downloads tiles one HTTP request at a time into a local SQLite cache. For a country-sized region that is millions of requests. It is the wrong tool.

Since **MapLibre Android 11.7.0**, the renderer reads PMTiles archives directly, including from local storage:

```kotlin
val path = File(getExternalFilesDir("regions"), "france.pmtiles").absolutePath
style.addSource(VectorSource("basemap", "pmtiles://file://$path"))
```

So the whole "download manager" becomes **downloading one file over HTTP with range-resume**. No tile database, no per-tile bookkeeping, no cache eviction logic. Delete the file to delete the region. This is a dramatic simplification and it is the single most important technical finding in this document.

Two constraints to design around:

- `pmtiles://asset://` **does not work** — PMTiles needs byte-range reads and `AssetManagerFileSource` doesn't implement them. Anything bundled in the APK must be copied to `filesDir`/`getExternalFilesDir` on first run. (Bundle a tiny z0–z5 world file this way so the map view is never a blank grey rectangle.)
- PMTiles sources are excluded from MapLibre's offline pack/caching machinery. Irrelevant here — the file is already local — but it means you cannot mix the two strategies.

### 4.2 Region catalogue and the download pipeline

Do **not** try to cut regions on the device. Run this server-side, nightly:

```
pmtiles extract https://<mirror>/planet.pmtiles regions/france.pmtiles \
    --bbox=-5.3,41.2,9.7,51.2 --maxzoom=14
```

`pmtiles extract` reads only the bytes it needs from the source archive over HTTP range requests, so cutting 200 regions from a 120 GB planet does not mean downloading 120 GB × 200. Output goes to plain object storage behind a CDN, plus a `catalog.json`:

```json
{ "schema": 1, "generated": "2026-08-17T03:00:00Z", "basemapStyle": "protomaps-v4",
  "regions": [ { "id": "fr", "name": "France", "parent": "eu",
                 "bbox": [-5.3,41.2,9.7,51.2], "maxzoom": 14,
                 "bytes": 1284000000, "blake3": "…",
                 "url": "https://cdn.example/r/2026-08-17/fr.pmtiles" } ] }
```

The client caches `catalog.json` so the region list is browsable offline (you can see what you *would* download, and what you already have).

**Size and zoom.** Every extra zoom level roughly doubles the file. This is the main product lever:

| maxzoom | What it's good for | Rough scale |
|---|---|---|
| 12 | Regional overview, road network shape | very small |
| 14 | **Default.** Streets, named roads, most POIs | ~1 GB for a mid-size country |
| 15 | Building footprints, house-number-level detail | ~2× the above |

Ship z14 as the default and offer z15 as a "detailed" toggle. Show the estimate *before* download and check free space with `StorageManager.getAllocatableBytes` / `allocateBytes` so a 90 %-full phone fails fast instead of at 97 %.

**Download mechanics.** `HttpURLConnection` with `Range:` resume, WorkManager with `NetworkType.UNMETERED` + `requiresStorageNotLow`, write to `region.pmtiles.part`, verify BLAKE3 against the catalogue, atomic rename. Never let a half-file be visible to the renderer.

### 4.3 Style, glyphs and sprites — the offline trap

A MapLibre style JSON references `glyphs` and `sprite` by URL. Point them at a CDN and your "offline" map renders roads with **no labels at all** the first time it is opened without network. Bundle them:

```json
"glyphs": "asset://styles/glyphs/{fontstack}/{range}.pbf",
"sprite": "asset://styles/sprite"
```

`asset://` is fine here (whole-file reads, unlike PMTiles). Budget: Latin + Greek + Cyrillic ranges for two weights is a couple of MB in the APK. CJK/Arabic/Indic ranges are tens of MB — ship those as optional **glyph packs** alongside the region download, not in the base APK.

Use the `@protomaps/basemaps` v4 style as the base and fork it for a dark, high-contrast, sunlight-readable "field" theme. Label localization: Protomaps carries `name:*` variants, so language selection is client-side and offline.

### 4.4 Attribution — not optional

The Protomaps basemap is an ODbL **Produced Work** built from OpenStreetMap. Requirements to actually satisfy:

- A visible "© OpenStreetMap contributors" on the map surface (a small always-on credit; the MapLibre attribution control does this) and in an About screen linking to `openstreetmap.org/copyright`.
- Credit MapLibre and Protomaps in the About/licences screen.
- ODbL's share-alike applies to the **database**, not to your app's source. Rendered tiles and the map images are a Produced Work — you can ship a closed-source app. If you ever *derive a new dataset* from OSM and distribute it, that derivative is ODbL.
- **Do not hotlink** `maps.protomaps.com` builds — Protomaps explicitly discourages it and the URLs change. Mirror the planet file into your own bucket.

Hosting cost sanity check: one planet mirror is ~120 GB of object storage (tens of dollars a month), and region files are static, immutable, CDN-cacheable objects. Your real cost is egress: at ~1 GB per install-that-downloads-a-region, 10 000 downloads/month ≈ 10 TB of egress. **Pick the CDN on egress price before you pick anything else** — this is the cost driver for the entire product. Cloudflare R2 (zero egress fees) is the obvious first choice; a plain S3/CloudFront setup is 10–30× more expensive at this volume.

---

## 5. Tier 3: offline routing (stretch)

**Use BRouter.** It is MIT-licensed, pure Java (so no NDK, no JNI, no 16 KB page-size problem), designed from day one for phone-class memory, published on Maven as `org.btools:brouter-core`, and its `.rd5` segments are a clean 5°×5° grid published weekly for the whole planet. Typical segment: a few MB to ~100 MB for dense areas. It emits voice hints / turn instructions when the profile sets `turnInstructionMode`.

Why not the alternatives:

- **Valhalla** produces the best turn-by-turn instructions of the three and is genuinely designed for tiled offline use — but on Android it means a C++ cross-compile through a third-party wrapper (`Rallista/valhalla-mobile`, not first-party), and generating tiles needs an admin database and a timezone database in the pipeline. That is a month of build engineering before the first route. Revisit at v3 if instruction quality becomes the differentiator.
- **GraphHopper** is Apache-2.0 and Java, which looks perfect, but the project **officially dropped Android support** — the Android demo stopped at 1.0 and modern versions target desktop JVM APIs. Betting a shipping feature on "should still work" is not a plan.

Routing UX: route on the downloaded region, snap to the route line, and — critically — **keep the arrow**. When the user goes off-network-of-roads (a field, a beach, a collapsed trail), the app falls back to the tier-1 arrow rather than refusing to navigate. That fallback is the reason this app exists.

Note the region mismatch: BRouter `.rd5` tiles and Protomaps region cutouts are different grids. Either present routing data as its own download list, or compute which `.rd5` tiles a chosen map region intersects and bundle them as an add-on. Do the latter — "France (map 1.1 GB + routing 380 MB)" is a comprehensible offer.

---

## 6. Architecture

### 6.1 Modules

```
:core            pure Kotlin/JVM — no Android imports
                 geodesy, MGRS/UTM, plus codes, WMM evaluator, destination parser
                 → fully unit-testable on the JVM, no emulator

:app             Compose UI, LocationManager, SensorManager, foreground service,
                 destination store, permission flow
                 → depends on :core only

:maps            (v1) MapLibre view, region catalogue, download manager
                 → depends on :core; :app depends on :maps
                 → NOTHING depends on :maps from the arrow path

:routing         (v2) BRouter wrapper, segment download
                 → depends on :core and :maps
```

The dependency arrow is the product promise made structural: deleting `:maps` and `:routing` must still produce a shippable app. Enforce it in CI with a build that excludes both modules.

### 6.2 Data flow

```
LocationManager ──► LocationEngine ──┐
                                      ├─► NavigationState (StateFlow) ──► ArrowScreen
SensorManager   ──► HeadingEngine ───┤              │
                                      │              └─► NavigationForegroundService
DeclinationProvider ──────────────────┘                  (notification: distance + bearing)
DestinationStore (file) ──────────────┘
```

`NavigationState` is a single immutable data class holding fix, heading, heading source, destination, distance, bearing, and quality flags. One source of truth; the UI is a pure function of it. The service and the Compose screen render the same object.

### 6.3 Screens

1. **Arrow** — the home screen. Giant arrow, distance, destination name, accuracy/satellite chip, heading-source chip ("compass" / "GPS course" / "calibrate"). One button to the destination list, one to map view (tier 2).
2. **Destinations** — list, search-by-name (local string match, not geocoding), sort by distance, swipe to delete, share as text/`geo:`/GPX.
3. **Add destination** — big paste box that live-previews the parse result (`"Parsed as 48.8584, 2.2945 · plus code"`), plus "use my current position" and manual lat/lon fields with a format switcher.
4. **Map** (v1) — MapLibre view. **If no region file covers the current viewport**, render the empty-state card described in §7 rather than a grey void.
5. **Regions** (v1) — catalogue browser, size estimates, download progress, installed list with sizes, update-available badge, delete. Shows total storage used and free space.
6. **Settings / About** — units, keep-screen-on, power mode, declination source, attribution and licences.

### 6.4 The tiering logic, concretely

```kotlin
sealed interface MapTier {
    data object ArrowOnly : MapTier                       // no map module or no regions at all
    data class NoDataHere(val suggested: RegionSummary?) : MapTier  // regions exist, none covers here
    data class Available(val region: InstalledRegion) : MapTier
}
```

Resolution is a bbox containment test against the installed-region index, evaluated on the current fix (or map centre). `NoDataHere` carries the best-guess region so the empty state can say *"You'll need the **France** region (≈1.1 GB). Download it next time you're online."* — naming the region is what turns a dead end into an action.

---

## 7. The offline empty state (worth designing properly)

When the user opens the map with no data for their location, they see a card, not an error:

> **No map for this area yet**
> The arrow still works — it doesn't need maps.
> To see streets here, download **France (≈1.1 GB)** the next time you have Wi-Fi.
> `[ Remind me ]` `[ Open region list ]`

Three rules: (1) lead with the fact that the core feature is unaffected, (2) name the region and its size, (3) never block the back button. "Remind me" queues a WorkManager job constrained to unmetered network that fires a notification when connectivity returns — the highest-value 20 lines of code in the map tier.

---

## 8. Build stack and the AGP 9 question

Google Play requires `targetSdk 36` for new submissions and updates as of **31 August 2026** — two weeks from now — so `compileSdk`/`targetSdk` 36 is not optional. `minSdk 26` covers effectively the whole install base and buys `AdaptiveIconDrawable` and a sane `JobScheduler`.

AGP 9.x is current (9.3.0, July 2026) and brings built-in Kotlin, `android.newDsl=true`, and a new `minSdk { version = release(n) }` DSL. **The scaffold deliberately targets AGP 8.13.0** because 8.13 supports up to API 36.1, uses the DSL every existing tutorial and Stack Overflow answer describes, and imports without surprises in any Studio from the last year. Android Studio will offer the AGP Upgrade Assistant on first open; take it when you have a green build to compare against, not before. When you do upgrade, the three things that will bite are built-in Kotlin (drop the `kotlin-android` plugin), `newDsl` (legacy `applicationVariants` disappears), and `android.sdk.defaultTargetSdkToCompileSdkIfUnset` (set `targetSdk` explicitly, which the scaffold already does).

Android 16 behaviour changes that the scaffold accounts for:

- **Edge-to-edge is enforced** for `targetSdk 36` — no opt-out. The scaffold calls `enableEdgeToEdge()` and applies `WindowInsets` padding.
- **Predictive back** is on by default; declare `android:enableOnBackInvokedCallback="true"` and use Compose's back handling.
- **Orientation/aspect-ratio restrictions are ignored** on displays ≥600dp — the arrow screen must be a responsive layout, not a locked portrait one.

**16 KB page sizes**: mandatory for Play since 1 Nov 2025 for anything targeting API 35+. Tier 1 is pure Kotlin so it is compliant by construction. When you add MapLibre in v1, use **≥ 11.5.0** (16 KB support landed there; current is 11.11.x) and build with AGP 8.5.1+/NDK r28+. BRouter is pure Java, so tier 3 adds no native code either.

**APK size**: tier 1 lands around 3–5 MB. MapLibre Native contributes native `.so` per ABI — budget roughly 5–8 MB per ABI in the delivered download and *measure it with an App Bundle*, not an APK, since ABI splitting is what keeps this honest. Ship an AAB.

---

## 9. Milestones

### v0 — Arrow only (shippable on its own)

The whole point: this is a complete product, not a prototype. If v1 never happens, v0 is still worth installing.

- `LocationManager` engine with GNSS status, accuracy gating, cold-start UX
- Heading state machine (rotation vector ↔ GPS COG) with calibration prompt
- Declination via WMM COF asset, framework fallback
- Destination parser (decimal, DMS, `geo:`, plus code, MGRS, UTM, OSM/Google URLs with coords) + `geo:` intent filter
- File-backed destination store, save-current-position, GPX/CSV import-export
- Arrow screen, destination list, add-destination screen, settings
- Foreground service + notification, power-saving mode
- `:core` unit tests: geodesy, MGRS round-trip, plus-code round-trip, parser fixtures

*Exit criteria: airplane mode, factory-reset device, no Google account — save a waypoint, walk 500 m, arrow points back within 5°.*

### v1 — Offline vector maps

- Server pipeline: planet mirror, nightly `pmtiles extract` for ~200 regions at z14 (+z15 for a "detailed" subset), `catalog.json`, CDN with cheap egress
- `:maps` module, MapLibre `MapView` in Compose via `AndroidView`, local PMTiles source
- Bundled field style + Latin/Greek/Cyrillic glyphs + sprite; optional glyph packs
- Region browser, size estimates, resumable download via WorkManager, BLAKE3 verification, update/delete, storage meter
- The `NoDataHere` empty state and the "remind me when online" job
- Long-press pin drop, map-centred destination creation
- Attribution surfaces

*Exit criteria: download one region on Wi-Fi, enable airplane mode, force-stop, reopen — full labelled map, no network calls (verify with a network-log build).*

### v2 — Offline routing

- `:routing` module wrapping `brouter-core`
- `.rd5` segment resolution from a chosen map region, bundled as a routing add-on per region
- Profiles: walk, bike, car
- Route line on the map, turn list from BRouter voice hints, off-route detection
- **Arrow fallback whenever off-route or off-network** — the differentiator

### v3 — candidates, in priority order

Terrain/hillshade via Mapterhorn PMTiles (same `pmtiles://file://` mechanism, ~zero new machinery) · track recording + GPX export · multi-waypoint routes · offline search over the region's POI layer · Wear OS companion.

---

## 10. Risks

| Risk | Mitigation |
|---|---|
| Cold GNSS fix takes 90 s with no A-GNSS and users think the app is broken | Explicit "acquiring satellites" state with a live satellite counter and an honest explanation; show stale last-known position meanwhile |
| Magnetometer garbage in cars and near magnets → arrow points wrong, user trusts it | GPS-COG takeover above 2.5 m/s; never hide `ACCURACY_LOW`; calibration prompt with the figure-8 animation |
| CDN egress cost scales with installs, not revenue | Choose zero-egress object storage first; default to z14; per-region rather than per-country-group files; consider P2P/sideload for large regions later |
| Users paste `maps.app.goo.gl` short links and nothing happens | Detect and explain in the paste UI; add a "resolve now" button that only appears when online |
| PMTiles offline path is a relatively new code path in MapLibre Native | Pin the MapLibre version, keep a spike branch that reads MBTiles through a custom source as the escape hatch, add a device smoke test in CI |
| Play policy churn on foreground services and target API | Never request background location; keep the FGS strictly user-initiated and foreground-started; re-check `targetSdk` requirements each August |
| ODbL misunderstanding leading to a takedown or licence violation | Attribution in-map and in About; never redistribute a *derived database*; document the produced-work reasoning in the repo |

---

## Sources

- [MapLibre Native (repo, BSD-2-Clause)](https://github.com/maplibre/maplibre-native) · [Android PMTiles guide — `pmtiles://file://`, asset limitation, offline-pack caveat](https://maplibre.org/maplibre-native/android/examples/data/PMTiles/) · [Android quickstart / Maven coordinates](https://maplibre.org/maplibre-native/android/examples/getting-started/) · [Android CHANGELOG (16 KB page support in 11.5.0)](https://github.com/maplibre/maplibre-native/blob/main/platform/android/CHANGELOG.md)
- [Protomaps basemap downloads — planet size, ODbL Produced Work, hotlinking guidance, `--maxzoom`](https://docs.protomaps.com/basemaps/downloads) · [`pmtiles` CLI `extract`](https://docs.protomaps.com/pmtiles/cli) · [Mapterhorn terrain](https://mapterhorn.com/data-access/)
- [OpenMapTiles licence and attribution](https://openmaptiles.org/) · [OpenFreeMap](https://openfreemap.org/) · [OSM copyright](https://www.openstreetmap.org/copyright)
- [Mapsforge (LGPL-3, `.map`)](https://github.com/mapsforge/mapsforge) · [CoMaps / Organic Maps fork](https://codeberg.org/comaps/comaps)
- [BRouter (MIT, F-Droid listing)](https://f-droid.org/en/packages/btools.routingapp/) · [BRouter repo and `.rd5` segments](https://github.com/abrensch/brouter) · [Valhalla on mobile — tile generation guidance](https://github.com/valhalla/valhalla/discussions/4746) · [`Rallista/valhalla-mobile`](https://github.com/Rallista/valhalla-mobile) · [GraphHopper (Apache-2, Android support dropped)](https://github.com/graphhopper/graphhopper)
- [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types) · [FGS types required (Android 14)](https://developer.android.com/about/versions/14/changes/fgs-types-required) · [Behaviour changes: apps targeting Android 16](https://developer.android.com/about/versions/16/behavior-changes-16) · [Play target API level requirement](https://developer.android.com/google/play/requirements/target-sdk) · [16 KB page size requirement](https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html)
- [`GeomagneticField` (WMM model shipped with the OS)](https://developer.android.com/reference/android/hardware/GeomagneticField) · [NOAA NCEI World Magnetic Model (WMM2025, public domain)](https://www.ncei.noaa.gov/products/world-magnetic-model)
- [AGP 9.0 release notes — built-in Kotlin, `newDsl`, breaking DSL](https://developer.android.com/build/releases/agp-9-0-0-release-notes) · [AGP 8.13 release notes](https://developer.android.com/build/releases/agp-8-13-0-release-notes) · [Compose BOM mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping)
- [Open Location Code (plus codes), Apache-2.0](https://github.com/google/open-location-code)
