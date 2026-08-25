# Getting an APK without installing Android Studio

GitHub builds it for you on their machines and hands you a file. You need `git` on your Mac and
a GitHub account. Nothing else.

**Read §7 before you start.** The first build will probably fail to compile, and that's the
expected outcome, not a sign you did something wrong.

---

## 1. Create the empty repo on github.com

Go to <https://github.com/new>, name it `gps-arrow`, pick Public or Private.

> ### Do not tick "Add a README file"
>
> Also leave *Add .gitignore* and *Choose a license* set to **None**.
>
> Any of those three creates a commit on GitHub that your local history doesn't have, and your
> first `git push` is then **rejected** with `Updates were rejected because the remote contains
> work that you do not have locally`. It's the single most common snag here. You want the empty
> repo — the page that just shows you setup instructions.

Click **Create repository** and leave the page open; you'll need the URL.

## 2. Push the project

In Terminal, from wherever you unzipped `GpsArrow` (adjust the first line to match):

```bash
cd ~/Downloads/GpsArrow

git init -b main
git add .
git commit -m "GPS Arrow v0 scaffold"

# Replace YOUR-USERNAME. The URL is shown on the page from step 1.
git remote add origin https://github.com/YOUR-USERNAME/gps-arrow.git
git push -u origin main
```

**If the push asks for a username and password:** GitHub stopped accepting account passwords
over HTTPS in 2021. It wants a Personal Access Token instead. The path of least resistance is
the GitHub CLI, which does it in a browser:

```bash
brew install gh
gh auth login          # choose GitHub.com -> HTTPS -> authenticate in browser -> Yes
git push -u origin main
```

You should end with something like `branch 'main' set up to track 'origin/main'`.

## 2a. Every push after the first: `./push.sh`

Once the remote exists, the whole sequence above collapses to one command:

```bash
./push.sh "what changed and why"
```

That stages everything, commits with your message, pushes, and prints the commit hash. Pushing
stays a manual step on purpose — the agent working on this repo has no GitHub credentials and
should not be given any — but it is one line rather than four.

The message is required. If you leave it out the script stops and asks, rather than committing
something generic: these messages are the only record of the work.

Three things it does before committing, each of which it names on screen rather than doing
silently:

- **Clears a stale `.git/index.lock`, but only if no `git` process is running.** The agent's
  sandbox cannot delete files inside `.git`, so an abandoned lock gets left behind and blocks
  every later write with `File exists`. An orphaned lock is safe to remove; one belonging to a
  running git process is not, and the script refuses in that case rather than risking two
  processes writing the index at once.
- **Removes `*.bak` and `.probe` files** the agent occasionally leaves and cannot clean up.
- **Shows you the diffstat** of what it is about to commit.

It stops on the first error rather than pressing on. If the push is rejected because
`origin/main` has moved, it says so, tells you the commit exists locally, and deliberately does
**not** merge or rebase for you — your tree is running on a phone and a bad merge is expensive.

Running it with nothing to commit is fine: it says so and exits without an error.

## 2b. Why the debug APK is large, and why the release will not be

Adding MapLibre took the debug APK from 18 MB to 68 MB. That is expected and it is **not** what
users will download.

`assembleDebug` with no `splits` or `abiFilters` produces a **universal APK**: one file carrying
the native `.so` libraries for every architecture MapLibre ships — `arm64-v8a`, `armeabi-v7a`,
`x86` and `x86_64`. Only one of those ever executes on a given device. The other three are dead
weight, and two of them (`x86`, `x86_64`) exist essentially for emulators.

The debug variant is now restricted to `arm64-v8a`, which is what the target device uses. That is
a one-line `ndk { abiFilters += "arm64-v8a" }` inside `buildTypes.debug` and it touches nothing
else. To test on an emulator or a 32-bit device, add that ABI there.

**The release path is unaffected and needs no equivalent.** Ship an **App Bundle** (`bundleRelease`,
producing `.aab`) and Play generates a per-device APK containing exactly one ABI. A user installing
from Play gets roughly a quarter of the native payload. So:

| | size | status |
|---|---|---|
| Universal debug APK, all four ABIs | 68 MB | measured |
| Debug APK, `arm64-v8a` only | **33 MB** | measured |
| What Play delivers per device from an App Bundle | ~33 MB or less | release path |

The 35 MB removed for three ABIs works out at roughly **12 MB of native library per ABI**, which
confirms the four are comparable in size. It also settles the earlier projection: 25–28 MB per ABI
was right all along, and the 68 MB was purely universal packaging rather than anything unexpected
about MapLibre's footprint.

Write this down rather than remembering it: "the app is 68 MB" is exactly the sort of figure that
gets repeated later as though it were the shipping size.

**To see the real per-ABI split** on any built APK:

```bash
unzip -l app/build/outputs/apk/debug/*.apk | awk '/lib\// {
    split($4, p, "/"); size[p[2]] += $1
} END { for (a in size) printf "  %-14s %8.1f MB\n", a, size[a]/1048576 }'
```

## 3. Watch the build

The push triggers the workflow automatically.

1. Open `https://github.com/YOUR-USERNAME/gps-arrow` and click the **Actions** tab.
2. There'll be one run, named after your commit message. Click it.
3. Click the **assembleDebug** job on the left to watch the log live.

Takes roughly 4–8 minutes the first time (it downloads Gradle, the Android SDK bits and all the
dependencies); later runs are faster because they're cached.

**To rebuild without making a commit:** Actions tab ▸ *Build debug APK* in the left sidebar ▸
**Run workflow** ▸ **Run workflow**. That's what the `workflow_dispatch` trigger is for.

## 4. Download the APK

Go back to the run's summary page (click the run name at the top, not the job). Scroll to the
bottom — there's an **Artifacts** section with two entries:

| Artifact | What's in it |
|---|---|
| `gpsarrow-debug-apk` | the app |
| `build-reports` | `build.log`, the JUnit HTML report, lint output, and a `gradle-wrapper.jar` you can drop into your local checkout if you ever want `./gradlew` to work |

Click **gpsarrow-debug-apk** to download it.

> **It downloads as `gpsarrow-debug-apk.zip`, not as an `.apk`.** That's GitHub, not a mistake —
> it wraps every artifact in a zip. Unzip it and you get `app-debug.apk` inside. On a Mac,
> double-clicking the zip is enough.

The artifacts only appear once the run has finished, and they expire after 30 days.

## 5. Get it onto the phone

Any of these work — pick whichever is least annoying:

- **AirDrop / email / Signal to yourself.** Unzip on the Mac first, send the `.apk`.
- **Google Drive or Dropbox.** Upload the `.apk`, open the Drive app on the phone, download it.
- **USB cable.** Copy the `.apk` to the phone's Downloads folder, then open **Files** on the
  phone and tap it.
- **Download it on the phone directly.** Log into github.com in the phone's browser, navigate to
  the run, download the artifact. You'll get the `.zip` and need a file manager that can extract
  it — Files by Google can, via *Extract*.

Then tap the `.apk` file.

## 6. The two prompts that will stop you

Neither means anything is wrong. Both appear for any app that didn't come from the Play Store.

**First — "For your security, your phone can't install unknown apps from this source."**

Android blocks sideloading per-app: whichever app you opened the file *from* (Files, Chrome,
Drive) needs permission. Tap **Settings** in that dialog, turn on **Allow from this source**,
then press back and tap **Install** again.

**Second — Play Protect.** You may get *"Unsafe app blocked"*, *"App scan recommended"* or
*"Send app for scanning?"*. Choose **Install anyway** / **Don't send**. Play Protect flags
anything unsigned by a known developer, which includes every debug build in existence.

After that it installs as **GPS Baibbat**. On first launch it asks for location — grant
**Precise**, not Approximate, or the arrow can't work. See `TESTING.md` §5 for the field test.

*(For the curious: the APK is signed with Android's standard debug key, which every machine
generates automatically. That's what makes it installable without any keystore setup. It's also
why it can never be published to the Play Store — that needs a real signing key.)*

## 7. When the build fails — which it probably will first time

**Expect this.** The Kotlin in this repo has never been through a compiler. It was written
without a build environment available and verified by other means, which catches wrong *logic*
but says nothing about whether it *compiles*. Unresolved references, Compose API drift, and
wrong dependency versions in `gradle/libs.versions.toml` are all likely. A red X on run #1 is
the system working as intended — that run is what tells us what to fix.

The workflow is built so a failure still gives you everything useful: tests can't block the APK,
a compile error can't block the logs, and the errors get pulled onto the summary page.

### Reading the failure

1. Open the failed run. **The summary page is the first thing you see** — scroll down past the
   job list. If Kotlin failed to compile there'll be a **Kotlin compile errors** block with the
   file, line and message for each one, already stripped of the long runner paths. That block is
   usually all that's needed.
2. If the summary is empty or unclear, click the **assembleDebug** job, then expand the red
   step. Compile errors are lines starting with `e:`; Gradle's own problems are under
   `* What went wrong:`.
3. The full log is also in the `build-reports` artifact as `build.log` if you'd rather search it
   in a text editor.

### What to send me

Copy the whole **Kotlin compile errors** block from the summary page — or if there isn't one,
the last ~50 lines of the failed step. Paste it back to me and I'll fix the code and give you
the corrected files. Don't worry about editing anything yourself.

The list may be long. Long is fine and expected on a first compile: one wrong import can produce
twenty errors, and they usually collapse into a handful of real fixes.

### Failures that aren't the Kotlin

| In the log | Meaning |
|---|---|
| `Could not find com.android.tools.build:gradle:8.13.0` or similar | a version in `gradle/libs.versions.toml` doesn't exist. Send me the line. |
| `Could not resolve androidx.…` | same thing, an AndroidX or Compose version I guessed wrong. |
| `Failed to install the following SDK components` | the SDK step; re-run the workflow, it's usually transient. |
| `Unsupported class file major version` | a JDK mismatch — shouldn't happen, the workflow pins JDK 17. |
