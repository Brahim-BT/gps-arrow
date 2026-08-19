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
| `resolve_check.py` | Every capitalised identifier must resolve to something. Catches a symbol deleted out from under its uses, which the import-side check structurally cannot. Runnable alone. |
| `exhaustive_check.py` | Every `when` over a project enum handles every constant or says `else`. Runnable alone. |
| `sealed_check.py` | The same for sealed interfaces and classes, which `exhaustive_check.py` does not see. Added with the v1 map work, which introduced three sealed hierarchies at once. Runnable alone. |
| `pmtiles_header_fixtures.py` | Builds PMTiles v3 headers from the spec's byte table and emits `PmtilesTest.kt`. An implementation independent of `Pmtiles.kt`, so the two agreeing means something. Re-run after changing either. |
| `strings_table.py` | The single aligned table of every user-facing string in English, French and Arabic. |
| `emit_strings.py` | Emits `values/`, `values-fr/` and `values-ar/` from it, then checks the three key sets and all format specifiers match. Run after editing the table; never hand-edit one language's file. |
| `TRANSLATIONS.md` | The three languages side by side for review, with the safety-critical rows marked. |

Run them with `python3 <script>` from anywhere; they locate the repo root from their own path
and refuse to run if they cannot find it. Only `wmm_reference.py` has a dependency.

**Read the file and enum counts they print.** They are there so that a scan of nothing cannot be
mistaken for a clean scan — see "Two of the three checkers were passing without reading anything"
below. At the time of writing the numbers are 37 Kotlin files and 14 enums.

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

## The sweep passed on code that did not compile

CI run #7 failed with four `Unresolved reference` errors in `MainActivity.kt` after
`check_project.py` had reported clean on 33 files. Unresolved symbols are precisely what this
script exists to catch, so the false confidence was worse than the four missing characters.

The blind spot was directional. The symbol check walked **imports** and asked "does this
resolve to a declaration?" — a check that cannot see a symbol used with *no import at all*,
because there is no import line to inspect. Kotlin needs an explicit import for a top-level
function from another package even though a member of an imported class needs none, and that
asymmetry is invisible by eye in a file that already imports nine other things from the same
package.

Section 2b now walks the other direction: every **use** of a project top-level function must be
reachable from where it is used — same package, explicit import, wildcard import, or declared in
that file. It is deliberately conservative, only flagging names declared in exactly one package
project-wide, so a same-named member function elsewhere cannot produce a false positive. It was
verified by running it against the failing commit, where it reports both missing imports.

The general lesson, which is the same one twice now: a check that only ever looks in one
direction will report clean on the half it cannot see.

## The usage check passed on broken code too, for a different reason

CI run #11 failed with ten errors in `ArrowScreen.kt`: eight unresolved references to an enum
called `Tone`, and two knock-on errors from the `when` over it. Section 2b — added *specifically*
to catch unresolved symbols, and verified against run #7's failing commit — reported clean.

Two gaps, one shallow and one that invalidated the check's premise:

1. **It indexed only top-level functions.** `Tone` is an enum class, so it was never a candidate.
2. **It could only consider symbols that exist.** 2b iterates the declaration index and asks "is
   this declared symbol reachable from where it is used?". A reference to something declared
   *nowhere* is not a key in that index, so the branch that would flag it never executes. The
   check could never have caught this, at any size of index.

`Tone` was deleted by an over-broad text edit: removing two adjacent functions by slicing from
one anchor to another took the enum sitting between them as well. **The file's size was not the
cause and splitting it would not have prevented it** — the working rule that follows is to read
what a range contains before deleting it, and to prefer deleting named declarations over slicing
between anchors.

Section 2c inverts the question: every capitalised identifier must resolve to a same-file
declaration, a same-package declaration, an explicit import, or a Kotlin built-in. That is sound
only because the project has no wildcard imports, which 2d now enforces. 2b stays — it catches
*lowercase* top-level functions used without an import, which is run #7's shape and which 2c's
capitalised-identifier scan does not see. The two are complementary, not redundant.

Section 2e checks that a `when` over a project enum handles every constant or says `else`. That
was not run #11's cause — the `when` error there was downstream of the deleted enum — but adding
`ArrowMode.ARRIVED` in an earlier round meant updating a `when` in another file, and only the
compiler would have noticed had it been missed.

**All three were verified by running them against the failing commits before being trusted**,
which is the discipline that was missing when 2b was reported working:

| case | expected | result |
|---|---|---|
| current tree | pass | pass |
| run #11 (`5fac6af`), `Tone` deleted | fail | `UNRESOLVED IDENTIFIER 'Tone'` |
| run #7 shape, import removed | fail | `UNIMPORTED USAGE 'numberLocale'` |
| a `when` branch deleted | fail | `NON-EXHAUSTIVE WHEN ... missing: MGRS` |

Getting 2c and 2e to that table took four rounds of false positives — backtick-quoted test names
read as type references, `Format` matching inside `CoordinateFormat`, single-line enums skipped
by a regex that required a newline, and multi-constant branches (`ARRIVED, NORTH ->`) losing all
but the last label. A check is not finished when it passes; it is finished when it fails on the
thing it was written for and passes on everything else.

## Two of the three checkers were passing without reading anything

Found while running the sweep against the real tree for the first time. All three scripts
resolved their root wrongly, in two different ways, and all three reported success:

- `check_project.py` looked for `../GpsArrow` relative to itself. That was right only for a
  staging copy nested one directory deeper — the layout it happened to be developed against. In
  the actual repo that path does not exist, so it had **never once run against the tree that
  gets committed**. It crashed rather than lying, which is the only reason this was noticed.
- `resolve_check.py` and `exhaustive_check.py` defaulted their root to `"."`. Run standalone the
  way this README tells you to run them, `.` is `verification/`, which contains no Kotlin. They
  scanned zero files and printed `Every capitalised identifier resolves (0 files).` and
  `Every `when` over a project enum is exhaustive (0 enums: ).` — green, instantly, on nothing.

The second is the more dangerous shape and it is the same lesson as the WMM bound below, a third
time: **a check that cannot fail still gets counted as one.** The fix is in two parts, and the
second matters more than the first. All three now default to the repo root, anchored on
`settings.gradle.kts` having to exist there; and an empty scan is now an **error**, not a pass.
A checker that finds nothing to check has failed to run, and must say so.

The counts are the tell, which is why they are printed: 37 files and 14 enums are load-bearing
numbers, not decoration. `0 files` should have been read as a failure the moment it appeared.

Re-verified after the change — the regression table above still holds, plus:

| case | expected | result |
|---|---|---|
| root with no Kotlin files | fail | `refusing to report success on an empty scan` |
| root with no enums | fail | `refusing to report success on an empty scan` |
| not a repo root at all | fail | `no settings.gradle.kts under ... — this is not the repo root` |

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
