# Verification

The sandbox this scaffold was built in has a JRE but no Kotlin compiler, so `:core` could not
be compiled and run directly. These scripts are what was done instead. They are not part of the
build — once you have Android Studio open, `./gradlew :core:test` is the real gate.

| Script | What it does |
|---|---|
| `check_project.py` | Static sweep of the whole project: every `R.*` resource reference resolves, every `dev.gpsarrow.*` import points at a declared symbol, `:core` imports nothing from `android.*`, every `libs.*` catalogue alias exists, braces and parens balance, modules in `settings.gradle.kts` have build files, manifest classes exist. |
| `mgrs_proto.py` | Standalone MGRS/UTM reference implementation used to validate the algorithm before porting. Checks the false easting at a central meridian is exactly 500 000, and sweeps the globe for round-trip error. |
| `olc_proto.py` | Same, for Open Location Code. Checks the published reference vectors, `decode(encode(p))` containment over 80 000 cases, and that the grid section tiles its parent cell exactly 4 rows x 5 columns. |
| `run_core_tests.py` | The Kotlin in `core/src/main/kotlin` transpiled back to Python, run against the same assertions as `core/src/test/kotlin`. This is what catches transcription errors in the port. |

Run them with `python3 <script>`; no dependencies.

## What this caught

`Mgrs.fromMgrs` originally returned the south-west corner of the designated square. Because
rounding in the UTM series lands a hair below the boundary, truncating on the way back out lost
a metre, so `encode(decode(code)) != code` for roughly three quarters of sampled codes. Returning
the square's **centre** instead fixes it (9840/9840 sampled codes now round-trip identically at
every precision from 10 km down to 1 m) and halves the worst-case round-trip error to 0.7 m.
