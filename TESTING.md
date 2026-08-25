# Testing GPS Arrow v0 yourself

From the zip to the arrow pointing at your car. Follow in order.

---

## 1. What you must install

| Thing | Version | Notes |
|---|---|---|
| **Android Studio** | Any release from the last year | This is the only mandatory download. JDK 17 ships inside it — don't install a JDK separately. |
| **Android SDK Platform 36** | API 36 | Studio offers it on first sync. If it doesn't: `Settings ▸ Languages & Frameworks ▸ Android SDK ▸ SDK Platforms` ▸ tick **Android 16 (API 36)**. |
| **Android SDK Build-Tools + Platform-Tools** | latest | Usually already there; Platform-Tools gives you `adb`. |
| Gradle CLI | 8.14.3+ | **Optional.** Only if you want to build from a terminal. See §4. |

Nothing else. No emulator image is required if you're using a real phone.

## 2. What hardware you need

**A physical Android phone, Android 8.0 (API 26) or newer**, plus a USB cable. Enable
`Settings ▸ About phone` ▸ tap *Build number* seven times, then
`Settings ▸ Developer options ▸ USB debugging`.

### What an emulator can and can't tell you

Worth being blunt about this, because the app's entire premise is hardware that emulators fake.

**An emulator CAN validate:**

- that the project compiles, installs and launches
- the permission flow, including the "you granted approximate location" branch
- every screen's layout, and the paste/parse preview on the add-destination screen
- the arrow *maths*: `Extended controls ▸ Location` lets you set a position or play a GPX route,
  so distance, bearing and the GPS-course heading path all move
- `Virtual sensors` drives the rotation vector, so the arrow will rotate when you tilt the
  virtual device

**An emulator CANNOT validate anything that actually fails in the field:**

- **Time to first fix.** The emulator hands you a position instantly. On a real phone with no
  network there's no A-GNSS assistance data and a cold fix takes 30–90 seconds, sometimes
  minutes. That cold-start screen is the highest-risk UX in the app and you cannot see it here.
- **Magnetometer reality.** The virtual magnetometer always reports high accuracy and has no
  interference. Real phones get 10–30° wrong near car bodies, magnetic mounts and speakers.
  The calibration warning and the compass-to-GPS-course handover exist *because* of this, and
  neither is exercised on an emulator.
- **Fix accuracy gating.** No real `accuracy` values, so the ±30 m "weak" and ±100 m reject
  thresholds are never hit.
- **Battery.** Meaningless.
- **De-Googled behaviour.** The whole reason for using `LocationManager` over Play Services.
  Only a GrapheneOS/LineageOS/e-OS device proves it.

Use an emulator for the first "does it build and launch" pass. Use a phone for everything else.

## 3. Zip to running app — click sequence

1. Unzip `GpsArrow-scaffold.zip`. You get a `GpsArrow/` folder.
2. Android Studio ▸ **File ▸ Open** ▸ select the **`GpsArrow`** folder — the one that directly
   contains `settings.gradle.kts`. (Not its parent, not `app/`.)
3. Trust the project when prompted. Gradle sync starts automatically and downloads Gradle 8.14.3
   plus the AndroidX/Compose dependencies. First sync takes a few minutes.
   - If sync fails on a missing SDK, click the "Install missing platform" link in the error.
4. Run the unit tests first — they need no device and prove the geodesy, MGRS, plus-code and
   parser logic: right-click `core/src/test/kotlin` ▸ **Run 'Tests in kotlin'**. Expect all green.
5. Plug in the phone, accept the "Allow USB debugging" dialog on the device, and pick it in the
   device dropdown in the toolbar.
6. Press **▶ Run**. First install takes 30–60 s.
7. On the phone: tap **Continue** ▸ grant **Precise** location. Allow notifications when asked.
8. **Go outside**, or at least to a window with sky. Watch the satellite counter climb.

## 4. The gradle-wrapper.jar step

`gradle/wrapper/gradle-wrapper.jar` is a binary and is not in the zip. **Android Studio does not
need it** — the IDE reads `gradle-wrapper.properties` and fetches Gradle itself, so §3 works as
written. You only need the jar for `./gradlew` from a terminal.

Easiest fix, no extra install: after one successful sync, open the **Gradle** tool window
(right edge) ▸ `GpsArrow ▸ Tasks ▸ build setup ▸ wrapper` ▸ double-click. That generates the jar.

Or, if you have Gradle on your PATH (`brew install gradle`, or SDKMAN):

```bash
cd GpsArrow
gradle wrapper --gradle-version 8.14.3
./gradlew :core:test            # pure JVM, no device
./gradlew :app:assembleDebug    # APK at app/build/outputs/apk/debug/
./gradlew :app:installDebug     # push to the connected phone
```

## 5. The field test that actually proves it

Do this one. Five minutes, and it tests the product rather than the code.

1. **Airplane mode ON. Wi-Fi off.** (The v0 manifest has no `INTERNET` permission at all, so the
   OS enforces this regardless — but do it anyway so you experience the real cold start.)
2. Open the app. Wait for a fix. **Expect 30–90 seconds**, with the satellite count climbing.
   This is correct behaviour, not a bug.
3. Tap **Save here** ▸ *Save my current position* ▸ name it "Car".
4. Walk 300–500 m, ideally round a corner so you can't see the start point.
5. **Pass criteria:** the arrow points back at the start within about 5°, and the distance is
   within ~10 m of reality.
6. Turn slowly on the spot. The arrow should stay locked on the target while the phone rotates
   under it, and the chip should read *Compass*.
7. Now walk briskly. The chip should flip to *GPS course* above roughly 2.5 m/s and stay there
   until you slow below 1.5 m/s.
8. Wave the phone near something ferrous, or in a car. Expect the chip to change to
   *Compass needs calibration* and the arrow to turn red.
9. **The driving test — this one needs a passenger.** With "Car" as the destination, drive past
   9 km/h and hold that speed through at least two turns. The chip reads *GPS course*; the arrow
   must keep tracking the turns. On some receivers (a Samsung was caught doing this) the chip's
   own course-over-ground stalls while position keeps updating, which used to freeze the needle
   until you slowed under ~5 km/h. The app now cross-checks that bearing against movement
   computed from consecutive fixes and ignores it when it stalls. **Fail signature:** the arrow
   holds one direction for more than a few seconds mid-turn. Open diagnostics (long-press the
   needle) and check *receiver course trust*: if it says *stale — using movement* on a straight
   road, report it with the device model.

Also worth a minute: paste each of these into **Add destination** and check the live preview.

```
48.8584, 2.2945
48°51'30"N 2°17'40"E
geo:48.8584,2.2945
8FW4V75V+8Q
18S UJ 23477 06483
https://www.openstreetmap.org/#map=17/48.8584/2.2945
https://maps.app.goo.gl/anything
```

The last one **must** refuse with an explanation about shortened links. If it silently does
nothing, that's a bug.

## 6. What works in v0, and what deliberately doesn't

**Works:**

- arrow, distance, bearing, compass rose
- satellite counter, fix-accuracy chip, stale-fix warning, calibration warning
- heading-source arbitration (compass ↔ GPS course with hysteresis)
- save current position; paste-parse decimal / DMS / `geo:` / plus code (full and short) /
  MGRS / UTM / OSM and Google URLs containing coordinates
- receiving a `geo:` link or shared text from another app
- destination list sorted by distance, delete
- foreground service and its notification; navigation survives screen-off
- magnetic declination (via the framework model, unless you add `WMM.COF` — see the README)

**Deliberately not built yet — you'll see these and they're not bugs:**

- **Map view shows the "no map for this area" card.** That empty state *is* the v0 deliverable
  for the map tier. There is no renderer behind it; that's v1.
- **Region downloads and routing** don't exist. v1 and v2.
- **No settings screen.** Units are hard-coded to metric in `MainActivity`; the power-saving and
  keep-screen-on switches are wired in the ViewModel with no UI on top.
- **The notification distance doesn't tick.** It's set once per destination change. Wiring the
  state flow to `NotificationManager.notify()` is a small, known TODO.
- **GPX import/export has helper functions but no file-picker UI.**
- **No instrumented tests.** Sensor and GNSS behaviour is untested by design; the pure logic
  lives in `:core` and is covered by JVM tests.

## 7. If something goes wrong

| Symptom | Cause |
|---|---|
| Sync fails: "Failed to find Platform SDK with path 36" | Install API 36 in the SDK Manager (§1). |
| `./gradlew: gradle-wrapper.jar is missing` | Expected. See §4. |
| App installs but the arrow never appears | No destination selected. Tap **Destinations**. |
| Stuck on "Looking for satellites" indoors | Correct. GNSS needs sky. Go outside. |
| Arrow points confidently in the wrong direction | The magnetometer. Check the heading chip — if it says *Compass needs calibration*, wave a figure of eight. This is the failure mode the whole heading state machine exists to manage. |
| Arrow is greyed out | Fix is stale (>10 s old) or accuracy is worse than 100 m, so it's been rejected on purpose. |
