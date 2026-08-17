# GPS Arrow

An offline-first navigation app for Android. A large arrow points at a saved destination with a
distance readout, using raw GNSS and the device's own sensors. No internet, no Google Play
Services, no account. Offline vector maps and offline routing are optional tiers layered on top.

This repository is the **v0 scaffold**: the arrow tier, implemented as far as is useful without a
device in hand.

| Document | Read it when |
|---|---|
| `BUILD_PLAN.md` | you want the technology decisions and the v1 (maps) / v2 (routing) roadmap |
| `GITHUB_BUILD.md` | **you want an installable APK without installing Android Studio** — push to GitHub, let Actions build it, download the artifact |
| `TESTING.md` | you're ready to test on a real phone, or want to know what an emulator can and can't prove |
| `verification/README.md` | you want to know how the maths was checked, and what that checking does *not* cover |

> **Status: the Kotlin here has not yet been through a compiler.** It was written without a build
> environment available. The logic is verified (see `verification/`), but expect compile errors on
> the first real build — `GITHUB_BUILD.md` §7 explains how to capture and report them.

---

## Open it and run it

**Prerequisites**

- Android Studio (any release from the last year — Narwhal or newer)
- JDK 17 (bundled with Android Studio; nothing to install separately)
- A physical device. **The emulator is close to useless here** — it has no magnetometer and only
  a mocked GPS, so the arrow and the heading-source arbitration cannot be exercised.

**Steps**

1. `File ▸ Open` and select the `GpsArrow` folder (the one containing `settings.gradle.kts`).
2. Let Gradle sync. Studio downloads Gradle 8.14.3 as pinned in
   `gradle/wrapper/gradle-wrapper.properties`. First sync also fetches the Android SDK
   platform 36 if you don't have it.
3. Plug in a phone with USB debugging on, pick it in the device dropdown, press ▶.
4. Grant precise location when asked. Go outside. The first fix can take a minute or two —
   there is no A-GNSS assistance data without a network, and the app says so on screen.
5. Tap **Save here** ▸ *Save my current position*, walk 100 m, and the arrow should point back.

**Command line**

`gradle-wrapper.jar` is a binary and is not checked in. Studio doesn't need it, but the shell
scripts do. Generate it once:

```bash
gradle wrapper --gradle-version 8.14.3     # needs a local Gradle install
./gradlew :core:test                        # pure-JVM unit tests, no device needed
./gradlew :app:assembleDebug
```

If you don't have Gradle on your PATH, run the tests from Studio instead (right-click the
`core/src/test` folder ▸ *Run tests*), or use Studio's built-in terminal after one successful
sync, which puts a working wrapper in place.

---

## What's actually implemented

| Area | State |
|---|---|
| Great-circle distance and bearing | Done, unit-tested |
| MGRS / UTM encode and decode | Done; published 1 m references (e.g. `18S UJ 23477 06483`) decode and re-encode to the identical string, global sweep round-trips to under a metre |
| Plus codes (full + short, encode/decode/shorten/recover) | Done, verified against reference vectors |
| Destination parser (decimal, DMS, `geo:`, plus code, MGRS, UTM, OSM/Google URLs) | Done, unit-tested |
| Shortened-link detection with an honest error | Done |
| WMM declination evaluator + `.COF` parser | Done; **coefficients not bundled**, see below |
| `LocationManager` engine (GPS + platform FUSED, GNSS status, accuracy gating) | Done |
| Rotation-vector heading with display-rotation remap and circular smoothing | Done |
| Heading-source arbitration (compass ↔ GPS course with hysteresis) | Done, unit-tested |
| Destination store (atomic JSON, GPX export/import helpers) | Done |
| Arrow screen, destinations list, add-destination screen, permission gate | Done |
| Foreground service + notification | Done, wired to destination selection |
| Map tiering model and the "no data here" empty state | Modelled and rendered; no renderer behind it yet |
| Region download manager, MapLibre view | **v1 — not started**, see `BUILD_PLAN.md` §4 |
| Offline routing | **v2 — not started**, see `BUILD_PLAN.md` §5 |

### Known gaps, deliberately left

- **Settings screen** — units, keep-screen-on and power mode are wired in the ViewModel but
  there is no UI for them yet (`units` is hard-coded to metric in `MainActivity`).
- **The service notification does not update live.** `NavigationService.start()` is called once
  per destination change. Wire the ViewModel's `state` flow to `NotificationManager.notify()`
  to get a ticking distance.
- **No instrumented tests.** Everything device-dependent (sensors, GNSS) is untested by design;
  the pure logic is in `:core` and covered.

---

## Magnetic declination — one manual step

Bearings to a coordinate are relative to **true** north. The magnetometer measures **magnetic**
north. The difference (declination) is up to ~20° in populated areas and much more at high
latitudes, so it has to be corrected or the arrow is simply wrong.

The app computes it from the World Magnetic Model, offline. It looks for:

```
app/src/main/assets/geomag/WMM.COF
```

To install it: download the current **WMM coefficient file** (`WMM.COF` / `WMM2025.COF`) from
NOAA NCEI's World Magnetic Model page — it is US Government work and public domain — and save
it at that path. It is about 4 kB.

If the file is absent or out of date, `Declination.create()` silently falls back to
`android.hardware.GeomagneticField`, which works but ships its coefficients inside the OS image
(the platform docs still describe WMM-2020), so its epoch is whatever the device's ROM was built
with. The app shows which source is in use.

---

## Project layout

```
core/                        pure Kotlin/JVM — no android.* imports anywhere
  Geo.kt                     haversine distance, initial bearing, circular smoothing, formatting
  Mgrs.kt                    UTM + MGRS both ways
  PlusCode.kt                Open Location Code
  Wmm.kt                     spherical-harmonic declination + .COF parser
  DestinationParser.kt       every paste format, and the honest failures
  Navigation.kt              Fix / NavigationState / HeadingArbiter / Destination
  src/test/                  runs on the JVM, no emulator

app/
  MainActivity.kt            edge-to-edge host, screen switching, geo:/share intent handling
  NavigationViewModel.kt     the single NavigationState everything renders
  location/
    LocationEngine.kt        LocationManager (NOT Play Services — see below)
    HeadingEngine.kt         TYPE_ROTATION_VECTOR, display remap, smoothing
    DeclinationProvider.kt   asset WMM, framework fallback
  data/DestinationStore.kt   atomic JSON file + GPX
  service/NavigationService.kt   location foreground service
  maps/MapTier.kt            the offline tiering model (v1 hook)
  ui/                        Compose screens
```

### The one architectural rule

`:app` depends on `:core`. The v1 `:maps` and v2 `:routing` modules will depend on `:core` and be
depended on by `:app` — **never the reverse**. Deleting them must still produce a shippable app.
That rule is what makes "the arrow always works" a structural fact rather than an intention.

---

## Why `LocationManager` and not `FusedLocationProviderClient`

`FusedLocationProviderClient` lives in Google Play Services. On GrapheneOS, LineageOS, /e/OS and
most Chinese-market devices it isn't there, and microG's reimplementation has a history of
blocking on fixes it ought to pass through. Those are exactly the users who want an offline
navigation app. `LocationManager` is part of AOSP and always present.

The app requests `GPS_PROVIDER` (the source of truth) and, on API 31+, the *platform's*
`FUSED_PROVIDER` opportunistically — that one is AOSP, not Play Services.

---

## Testing the offline claim

The v0 manifest has **no `INTERNET` permission**. That is not decoration: it means a v0 build is
provably incapable of network access, and the OS will enforce it. When `:maps` is added in v1,
`INTERNET` goes in *that module's* manifest, so a maps-free build stays provably offline.

Field test that actually proves the product works:

1. Airplane mode on, Wi-Fi off.
2. Save your current position as "Car".
3. Walk 500 m out of sight of it.
4. The arrow should point back within about 5°, and the distance should be within ~10 m.

---

## Licences and attribution

The scaffold itself has no third-party runtime dependencies beyond AndroidX and Kotlin. Two
things you must get right before shipping later tiers:

- **v1 maps** — the Protomaps basemap is an OpenStreetMap-derived ODbL *Produced Work*. You must
  display "© OpenStreetMap contributors" on the map surface and in an About screen, and credit
  MapLibre and Protomaps. Do not hotlink Protomaps' build URLs; mirror them.
- **v2 routing** — BRouter is MIT-licensed; its `.rd5` segments are OSM-derived and carry the
  same attribution obligation.

`PlusCode.kt` and `Mgrs.kt` are independent implementations of public specifications, so the app
takes no dependency on Google's Apache-2.0 reference library.
