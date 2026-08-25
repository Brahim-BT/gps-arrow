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
| `strings_table.py` | The single aligned table of every user-facing string in English, French and Arabic, plus `SAFETY_KEYS` — the reviewed list of which of them carry safety meaning. |
| `emit_strings.py` | Emits `values/`, `values-fr/` and `values-ar/` from it, then checks the three key sets and all format specifiers match. Run after editing the table; never hand-edit one language's file. |
| `emit_translations.py` | Emits `TRANSLATIONS.md` from the same table. Run after `emit_strings.py` — it refuses to run before, so the document cannot describe strings the app does not ship. |
| `workflow_drain_test.py` | Runs the cleanup workflow's `run:` blocks verbatim against a fake Realtime Database, over five cases: an edit that removes a note actually removes it, a wrong token changes nothing on either the edit or the withdrawal path, an edit cannot resurrect a moderated-away point, and an honest withdrawal clears all four nodes. **Shell logic only** — it proves nothing about the live drain, the security rules or the service account; the real check is the curl smoke test in `SETUP_SHARED_POINTS.md`. Runnable alone. |
| `TRANSLATIONS.md` | Generated. The three languages side by side for review, with the safety-carrying rows marked. |

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

## A document that said it was generated, and was not

`TRANSLATIONS.md` opened with "Generated from `verification/strings_table.py`". No script
generated it; it was written by hand once and that line was aspirational. By the time anyone
looked it held 191 of the table's 257 keys — 26% stale, still naming the app by a name it no
longer used, still showing a permission string that had been replaced, and missing
`position_stale`, `value_unknown`, `about_map_attribution` and the whole `diag_course_*` set.

The missing rows were not a random 26%. Staleness accumulates at the end, so the absent strings
were the *newest* ones, and on this project the newest strings are disproportionately the safety
ones — each was added because some state was being asserted without warrant. The document
existed to get the safety-carrying strings read in three languages side by side, and those were
precisely the rows it did not have.

This is the same shape as the two checkers that scanned nothing: **the claim of being checked is
what stops anyone checking.** Nobody diffs a file whose header says it is generated. The fix is
`emit_translations.py`, which makes the header true, refuses to run unless the three
`strings.xml` already carry the table's exact key set, and re-parses its own output and compares
it back to the table rather than trusting that it wrote what it meant to write.

One thing it deliberately does *not* do is infer which rows are safety-carrying. That is a
reading of what a string claims, not a property a script can compute, so it lives in
`SAFETY_KEYS` in the table; the emitter only checks that every key named there still exists, so a
rename cannot silently drop a mark. The document says outright that an unmarked row means "not
yet judged" as well as "not safety-carrying", because a marking scheme that looks complete and
is not would be the same bug again in a smaller font.

## The workflow checker, and what it was made to fail on

`workflow_drain_test.py` was added because the cleanup workflow became the only thing that can
apply an edit to a published point, and its worst failure is silent. An edit that removes a note
is applied with a `PATCH`, and a `PATCH` that omits a key leaves the old value — so skipping an
absent note instead of deleting the child makes "remove my note" the one edit that does nothing
while the app reports it as sent. Nothing else in this repo executes that shell.

Per the rule this file has had to learn twice, it was trusted only after being made to fail:

| case | expected | result |
|---|---|---|
| current tree | pass | pass |
| an absent note skipped instead of deleted | fail | note survived the edit |
| edit-path token check removed | fail | a wrong token moved the point |
| withdrawal-path token check removed | fail | a wrong token deleted the point |
| queue entry left behind on refusal | fail | slot still occupied (permanent denial of edit) |
| the step it needs renamed | fail | `refusing to report success: no run: block named …` |

The last row is the empty-scan guard in this checker's own shape: extracting shell by step name
means a rename would otherwise skip the case silently and still print a pass. The third row also
prompted a fix here rather than in the workflow — a wrong workflow can delete the node the check
then looks for, so a check that *raises* is reported as a failing case rather than escaping as a
traceback that would send the reader to debug the harness instead of the thing that is wrong.

**Read its second paragraph before quoting a green run at anyone.** It runs against a fake API,
so it says nothing about the security rules, the service account, Firebase's real PATCH and
DELETE semantics, or whether `DB:` was ever pointed at a database. The check that covers those is
the curl smoke test in `SETUP_SHARED_POINTS.md`, and it asserts on the workflow's log rather than
on HTTP status codes — because the tombstone write returns 200 for an attacker too, and the
refusal happens at drain time.

## What these checkers cannot see

Read this before assuming more of them than they promise. Every entry here has reached CI at
least once, and none of them is worth closing — the compiler already does it in ninety seconds.

**Types.** No type checking of any kind. A stale reference against a renamed parameter, or a
`Float` where a `Double` is wanted, resolves fine here and fails in `kotlinc`.

**Position within a file.** The declaration index is built file-wide with no notion of *where* a
name is declared. Kotlin locals are in scope only from their declaration onward, so a `var` used
eleven lines above its own `var` line — same function, same file — is unresolvable to the
compiler and perfectly resolvable to these scripts. That is not lexical scope and it is not a
sibling-function problem: it is ordering, inside one body.

**Lexical scope generally.** A name declared inside one function reads as available inside
another. Tracking this properly needs a parser, not regular expressions.

**Whether a substitution applied.** Several of these bugs came from an edit that silently matched
nothing — wrong indentation, or an anchor that had already changed. The scripts check the file
that exists, not the edit you thought you made. Print and read the result of a substitution.

The division of labour that has actually held: these scripts catch missing resources, unresolved
and unimported *symbols*, and non-exhaustive `when`s — the classes where the compiler's message is
slow to reach or hard to read. The compiler catches everything above. Keeping rounds small is what
makes that division cheap, because a red run then costs one line and ninety seconds.

## The two recurring shapes

Enough bugs have now repeated that the shapes are worth naming. Neither is caught by any script
here; both are caught by asking the right question of the code.

### 1. A value that cannot say "I don't know"

A Boolean, or a default, standing in for a state that has three possibilities: yes, no, and
not-yet-determined. Every time, the code confidently asserted the negative case before it had
looked.

| where | what it claimed |
|---|---|
| `RegionIndex.scanned` | "no map installed" — before ever scanning the directory |
| `LocationEngine.status()` | "location is off" — before the permission dialog was answered |
| `_gnss` initial value | "location is off" — on the first frame, before any emission |
| `MapCamera` restored as `0,0,0` | a real camera at null island, indistinguishable from none |
| `Destination.isPublic` | "this point is published" — having never fetched a feed |

The fix is always the same: make the third state representable, and never assert a problem from
it. Showing nothing briefly is fine; showing a false instruction is not.

The fifth is the one that shows the shape at its worst, and it is worth reading the diagnosis
rather than only the fix. `isPublic` carried a *remote* fact in a local Boolean, and its own KDoc
said so — "it can be true while the publish request is still in flight or has failed... nothing
here may assume the flag matches the server" — while the list rendered "Publicly shared" straight
off it. The comment was right and the code did the thing the comment forbade, which is what
happens when the type cannot express what the comment knows.

Two things generalise from it:

- **A comment is not a constraint.** If the honest statement about a field is "nothing may assume
  X", and the field's type permits assuming X, something eventually will. The fix was not a
  better comment; it was splitting the field into the half that is local and certain (an
  *intent*) and the half that is remote and may be absent (an *observation*, derived at read
  time from the cached feed), so the uncertain state has nowhere to hide.
- **The dangerous direction is the reassuring one.** The other four instances asserted a
  *problem* prematurely — "no map", "location is off" — which is annoying and self-correcting.
  This one asserted *success*: tap the switch off with no signal and the badge disappeared, so
  the user was told their camp was private while it was still on every other user's map. Nothing
  in the app would ever correct that, and the user had no way to find out. When judging one of
  these, ask which way the premature assertion falls.

There is a matching rule about what may be *said*, which is the same discipline one layer up: do
not replace an unknown with a forecast. "Gone within about a day" is a prediction about whether a
scheduled job ran, and presenting it as a fact about the user's own camp is the same defect
wearing better clothes. Report what was observed.

### 2. Something runs at a rate you did not intend

Code placed somewhere that executes far more often than its author pictured, so a
once-per-event action becomes a once-per-frame or once-per-fix action.

| where | intended | actually ran |
|---|---|---|
| `AndroidView`'s `update` lambda | once, to place the camera | **every recomposition** — so at the 1 Hz fix rate, yanking the camera back mid-gesture |
| listeners registered inside `update` | once | every recomposition, accumulating hundreds of duplicates |
| the arrow's `tween` | once per heading change | restarted on every sample, producing the 1 Hz flicker |
| a bearing effect keyed on heading | when following | mid-gesture, cancelling the user's rotate |

The tell is a symptom whose *period* matches a known rate. "Interrupted about once a second" was
the fix rate showing through, and that timing was the fastest route to the cause — faster than
reading any of the code.

The working rule: for anything with side effects, ask **how often does this line actually
execute**, not what it is for. In Compose specifically, `update` is not a setup hook.

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
