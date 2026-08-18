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
| `wmm_reference.py` | `Wmm.kt` ported to Python and checked against NOAA's reference implementation over thousands of points; reports the declination across the deployment region; regenerates `WmmReferenceTest.kt`. Needs `pip install pygeomag` for the coefficient files, which this repo does not ship. |
| `WmmReferenceTest.kt.template` | Skeleton the above fills in. Not a script. |
| `strings_table.py` | The single aligned table of every user-facing string in English, French and Arabic. |
| `emit_strings.py` | Emits `values/`, `values-fr/` and `values-ar/` from it, then checks the three key sets and all format specifiers match. Run after editing the table; never hand-edit one language's file. |
| `TRANSLATIONS.md` | The three languages side by side for review, with the safety-critical rows marked. |

Run them with `python3 <script>`; only `wmm_reference.py` has a dependency.

## Superseded — and what it missed

**CI now runs the real JUnit suite. Trust that, not this.** These scripts were a stand-in for a
compiler that wasn't available; they are kept for the algorithm provenance, not as a gate.

The first real JUnit run failed three tests that `run_core_tests.py` had reported as passing.
The transpile and the Kotlin did **not** disagree — the transpile never executed the code at all:

| Kotlin test class | in `run_core_tests.py`? |
|---|---|
| `GeoTest`, `MgrsTest`, `PlusCodeTest`, `HeadingArbiterTest`, `NavigationStateTest` | yes |
| `WmmTest` | **no** |
| `DestinationParserTest` | **no** |

All three failures were in the two uncovered classes. `DestinationParser` and `Wmm.decimalYearOf`
appear nowhere in the transpile, so "34/34 assertions passed" described 5 of 7 test classes while
being reported as if it validated the module. The lesson is not that transpiled verification is
useless — it caught a genuine MGRS corner/centre defect — but that a pass rate is meaningless
without a coverage denominator, and this one never stated its denominator.

## The WMM evaluator was 170 degrees wrong, and a green test said otherwise

August 2026. `Wmm.kt` had two independent faults:

1. **Legendre normalisation.** The recursion produces unnormalised associated Legendre
   functions and expects the Schmidt factors to have been folded into the *coefficients*
   (what NOAA's `geomag70.c` does). Instead the code applied `sqrt(2(n-m)!/(n+m)!)` to `P` and
   `dP` after the recursion — the right factor for a different set of functions. The recursion's
   sectoral seed is `P[n][n] = sin(theta)*P[n-1][n-1]`, which omits the `(2n-1)!!` that the
   standard functions carry, so the factor described something the code was not computing.
2. **Rotation sign.** Going geocentric to geodetic, the north component came out negated.
   That alone puts the declination 180 degrees out.

Together: about 170 degrees of error at Casablanca. Total intensity was correct throughout,
because magnitude is rotation-invariant — so the field was the right size, pointing the wrong way.

**Why nothing caught it.** No `WMM.COF` ships in the app, so `Declination.create()` always fell
through to `FrameworkDeclination` and this code path never executed on a device. And the only
test asserted that the result was finite and `abs(d) <= 180.0`, which is true of virtually every
wrong answer, including this one. A bound is not a reference value. The lesson is the same one as
below, one level deeper: a test that cannot fail is worse than no test, because it is counted.

`WmmReferenceTest` now pins the evaluator to NOAA reference values at thirteen real coordinates,
four of them outside Africa specifically to catch a hemisphere or quadrant flip that the
deployment-region points — all within a few degrees of north — would sail straight past.

## What this caught

`Mgrs.fromMgrs` originally returned the south-west corner of the designated square. Because
rounding in the UTM series lands a hair below the boundary, truncating on the way back out lost
a metre, so `encode(decode(code)) != code` for roughly three quarters of sampled codes. Returning
the square's **centre** instead fixes it (9840/9840 sampled codes now round-trip identically at
every precision from 10 km down to 1 m) and halves the worst-case round-trip error to 0.7 m.
