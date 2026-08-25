# Shared public points — server setup

The feature lets a user opt a saved point into being visible to everyone: its name,
coordinates and note appear as teal dots on every other user's map. It costs nothing to run —
no Firebase SDK, no new dependency in the APK, just the Realtime Database REST API on the
Spark free tier.

Until you do the steps below, nothing changes for anyone: `SharedPointsConfig.BASE_URL` is
blank, the share toggle does not render, no network call is attempted, and an unconfigured
build behaves exactly like one without the feature.

## 1. Create the database

1. console.firebase.google.com → **Add project** (or reuse one). The free Spark plan is enough.
2. In the project: **Build → Realtime Database → Create Database**.
3. Pick a location (e.g. `europe-west1` for the Maghreb) and start in **locked mode**.
4. Copy the URL shown at the top of the database panel. It looks like
   `https://gps-baibbat-xxxxx-default-rtdb.europe-west1.firebasedatabase.app`.

## 2. Confirm the rules language still cannot hash

Thirty seconds, and it is the hinge the whole ownership design turns on. Paste this into
**Realtime Database → Rules** and try to save it:

```json
{ "rules": { "probe": { ".validate": "hashing.sha256('x') == 'y'" } } }
```

**It should be rejected as an unknown function.** Realtime Database uses the legacy JSON rules
language, whose whole surface is `root`/`data`/`newData`/`auth`/`now`, the RuleDataSnapshot
methods, and string methods (`length`, `contains`, `beginsWith`, `endsWith`, `replace`,
`toLowerCase`, `toUpperCase`, `matches`). There is no hashing. A rule can compare two strings; it
cannot verify a preimage.

`hashing.sha256()` **does** exist — in the *other* Firebase rules language, the CEL-like one used
by Cloud Firestore and Cloud Storage. Searching for "Firebase rules sha256" returns that answer,
which is exactly why this probe is here rather than a sentence asking you to trust the claim.

That single limitation is why withdrawals and edits are queued and applied by a scheduled job
rather than written directly, and why `owners/<id>` can hold a digest instead of a secret. **If
the probe saves**, Realtime Database has gained hashing since this was written, and it is worth
revisiting: the queues could then be replaced by rules that verify the token directly, and edits
would land immediately instead of on the job's schedule. Do not change anything on the strength
of the probe alone — it changes what is *possible*, and the trade would need arguing again.

Then discard the probe and carry on.

## 3. Paste these security rules

**Realtime Database → Rules** tab → replace everything with:

```json
{
  "rules": {
    ".read": false,
    ".write": false,

    "sharedPoints": {
      ".read": true,
      "$pointId": {
        ".write": "!data.exists() && newData.exists()",
        ".validate": "newData.hasChildren(['name', 'lat', 'lon', 'createdAt'])",
        "name":      { ".validate": "newData.isString() && newData.val().length >= 1 && newData.val().length <= 40" },
        "lat":       { ".validate": "newData.isNumber() && newData.val() >= -90 && newData.val() <= 90" },
        "lon":       { ".validate": "newData.isNumber() && newData.val() >= -180 && newData.val() <= 180" },
        "note":      { ".validate": "!newData.exists() || (newData.isString() && newData.val().length <= 200)" },
        "createdAt": { ".validate": "newData.isNumber()" },
        "$other":    { ".validate": false }
      }
    },

    "owners": {
      ".read": false,
      "$pointId": {
        ".write": "!data.exists() && newData.exists()",
        ".validate": "newData.isString() && newData.val().length == 64"
      }
    },

    "tombstones": {
      ".read": false,
      "$pointId": {
        ".write": "!data.exists() && newData.exists()",
        ".validate": "newData.isString() && newData.val().length == 64"
      }
    },

    "pendingEdits": {
      ".read": false,
      "$pointId": {
        ".write": "!data.exists() && newData.exists()",
        ".validate": "newData.hasChildren(['t', 'name', 'lat', 'lon'])",
        "t":    { ".validate": "newData.isString() && newData.val().length == 64" },
        "name": { ".validate": "newData.isString() && newData.val().length >= 1 && newData.val().length <= 40" },
        "lat":  { ".validate": "newData.isNumber() && newData.val() >= -90 && newData.val() <= 90" },
        "lon":  { ".validate": "newData.isNumber() && newData.val() >= -180 && newData.val() <= 180" },
        "note": { ".validate": "!newData.exists() || (newData.isString() && newData.val().length <= 200)" },
        "$other": { ".validate": false }
      }
    }
  }
}
```

What this buys you:

- **Nothing in the public feed identifies the publisher.** `sharedPoints` is world-readable —
  `curl $DB/sharedPoints.json` returns every point — and it contains a name, a coordinate, an
  optional note and a timestamp. Nothing else. There is no device id, no account, no value that
  links two points to the same person.
- **The delete key is not in the feed.** Withdrawal is authorised by a secret token the
  publishing device mints per point and keeps locally; the database stores only its SHA-256, at
  `owners/<id>`, which is `".read": false`. Reading the whole feed tells an attacker nothing
  about how to withdraw any of it.
- **A point can never exist without its owner digest.** Publishing is one atomic multi-path
  `PATCH` to the root writing `sharedPoints/<id>` and `owners/<id>` together. Multi-path updates
  apply whole or not at all, with each path checked against its own rule, so the failure that
  would matter — a live point nobody can ever withdraw — is not representable.
- **Create-only everywhere, including the point itself.** Nobody can overwrite or delete another
  device's point, or replace an owner digest with their own. *Editing* a published point is not
  an exception to this: `sharedPoints/<id>` stays create-only for clients, and an edit is queued
  to `pendingEdits/<id>` and applied by the job, which checks the token first. Clients never
  write to `sharedPoints` except to create.
- **Every write-only node is validated to the same shape as the thing it becomes.** Tokens and
  digests are capped at exactly 64 characters, which is what a hex SHA-256 and a minted token
  both are; a queued edit is bounded by the same name, note and coordinate rules as a published
  point, so nothing can be staged in a queue that could not have been published directly.
  Previously `tombstones` was an unbounded unauthenticated string write, which one hostile client
  could have used to fill the 1 GB tier and take the read feed down with it.

Both queues being create-only has one consequence worth knowing rather than discovering: **a
stranger can occupy a point's queue slot with a wrong token, and it costs the owner one drain
cycle and nothing more.** Until the next run the owner's write is refused; the job then frees the
slot — it deletes the entry whether it accepts it or refuses it — and the owner's device notices
on its next sync that the point is still stale or still public and queues again.

Create-only is the better of the two options. Allowing overwrites would let the same attacker
clobber a *legitimate* entry at any moment right up to drain time, which is the same denial with
better timing for them. Throughout, the UI goes on saying the withdrawal or the edit is
unconfirmed, which is true.

A note on the verb: the app sends this as a `POST` carrying `X-HTTP-Method-Override: PATCH`,
because `HttpURLConnection` refuses `setRequestMethod("PATCH")` outright. The database treats it
as the PATCH it is. `curl -X PATCH` below is the same request; only the wire verb differs.

### Moderation, and the one thing not to tidy

Deleting a point by hand from the console's data view is still the moderation tool, and it is
now **sticky**: the republish path is that same atomic `PATCH`, whose write to `owners/` is
create-only, so as long as `owners/<id>` exists the publisher's device cannot put the point
back. It will keep trying and keep being refused, and the user will keep seeing "not confirmed
yet", which is the truth.

Editing does not open a way round this. A queued edit for a point that no longer exists is
refused by the job and its queue entry deleted, so a hand-deleted point cannot be brought back
through the edit path either.

**So do not tidy `owners/`.** An orphaned digest looks like litter and is doing a job. If you
want a point gone *and* the id usable again, delete all four nodes — `sharedPoints/<id>`,
`owners/<id>`, `tombstones/<id>` and `pendingEdits/<id>` — which is exactly what the cleanup
workflow does.

### Device-level banning is not possible, deliberately

There is no value anywhere that says "same device as before", so there is nothing to ban. This
is a door being closed on purpose, and it is worth being explicit about what was given up:

- It was **never implemented**. The device id that existed before this change was only ever
  compared by the cleanup workflow to authorise a deletion; nothing consulted a ban list,
  because there was no ban list.
- RTDB security rules **cannot express it** for unauthenticated clients. A rule can only inspect
  the data being written, and a banned device would simply write a different id.
- **Clearing app data defeated it** in one tap, on any device, with no tools.

So the ban that has been given up is one that did not exist, could not have been enforced by the
rules, and would have been trivially evaded. What was gained is that a world-readable feed no
longer links a user's points to each other, and no longer hands out the key to delete them.
Moderation is per point, and per point it is now stronger than it was.

## 4. The queue drain (GitHub Actions)

This job is the only thing that can honour an edit or a withdrawal, because it is the only part
of the system that can hash — see step 2. A device writes its token to `pendingEdits/<id>` or
`tombstones/<id>`; the job applies the change when `sha256(token)` matches `owners/<id>`, and
refuses it otherwise.

1. Project settings → **Service accounts → Generate new private key** → download the JSON.
2. Repo → Settings → Secrets and variables → Actions → **New repository secret**,
   name `FIREBASE_SERVICE_ACCOUNT`, paste the whole JSON file as the value.
3. In `.github/workflows/shared-points-cleanup.yml` (already in this repo), set the `DB:` line
   under the top-level `env:` to your database URL from step 1. There is one such line.

Run it once from the Actions tab (**Run workflow**) to check it works; it also runs every 15
minutes and is safe to re-run.

### Before you do any of that, it has been running and skipping

The workflow is scheduled from the moment the repo exists, and until the setup is finished it
takes one look, says so, and exits successfully:

```
Shared points are not configured, so there is nothing to drain and this run did nothing.
Missing: the FIREBASE_SERVICE_ACCOUNT secret, the DB: URL in this file, and
SharedPointsConfig.BASE_URL. See SETUP_SHARED_POINTS.md.
```

That is deliberate on both counts. It stays **scheduled** so the first real drain happens by
itself once you finish, rather than waiting for somebody to remember this file exists. And it
exits **green**, because a check that is permanently red teaches everyone to ignore red, and the
rest of this repo's verification depends on red meaning something.

The skip is narrow. It needs the secret absent **and** the `DB:` URL still a placeholder **and**
the app's `BASE_URL` still blank. Any other combination is a real fault and the run goes red
naming what is missing — in particular, if `BASE_URL` is set while the drain is not configured,
people are publishing points and asking for them to be withdrawn and nothing is honouring those
requests. That case must shout.

**Do not turn "every 15 minutes" into a promise to the user.** GitHub's scheduled minimum is 5
minutes and schedules are best-effort — 5-to-30-minute delays are normal at peak, and a run can
be skipped entirely. That is fine, because nothing in the app quotes a duration: it reports what
it has observed in a fetched feed and says "not confirmed yet" until it has. An earlier version
of this feature told users a withdrawal would complete "within about a day", which was a forecast
about whether a cron job ran, presented as a fact about their camp.

What the job does, in order: applies pending edits whose token matches, refusing edits to points
that no longer exist; then deletes points whose tombstone matches, clearing all four nodes. Every
path frees the queue slot, and a `::warning::` for a non-matching token is not a fault — it is
the mechanism refusing a guess.

## 5. Point the app at it

Edit `app/src/main/java/dev/gpsarrow/data/SharedPointsConfig.kt`:

```kotlin
const val BASE_URL = "https://gps-baibbat-xxxxx-default-rtdb.europe-west1.firebasedatabase.app"
```

Rebuild. The share toggle appears in Add/Edit, the list shows what the app has actually observed
about each shared point, and everyone's map shows the shared dots.

## 6. Smoke-test with curl

The last three checks are the important ones, and they are the ones that are easy to run wrongly.

```sh
DB=https://gps-baibbat-xxxxx-default-rtdb.europe-west1.firebasedatabase.app

TOKEN=$(head -c32 /dev/urandom | xxd -p -c64)      # what a device mints, per point
DIGEST=$(printf '%s' "$TOKEN" | sha256sum | cut -d' ' -f1)

# Publish (what the app does): one atomic PATCH at the ROOT, both paths together.
curl -sS -X PATCH "$DB/.json" -d "{
  \"sharedPoints/test-1\": {\"name\":\"Test\",\"lat\":33.57,\"lon\":-7.59,\"createdAt\":1700000000000},
  \"owners/test-1\":       \"$DIGEST\"
}"

# Read the feed (what every client, and anyone at all, does).
# It must contain the point and NOTHING that identifies who published it.
curl -sS "$DB/sharedPoints.json"

# The owner digest must NOT be readable. Expect "Permission denied".
curl -sS "$DB/owners.json"
curl -sS "$DB/owners/test-1.json"

# Overwrite must FAIL with "Permission denied".
curl -sS -X PUT "$DB/sharedPoints/test-1.json" \
  -d '{"name":"Hacked","lat":0,"lon":0,"createdAt":1}'

# A bad name must FAIL too — and note it fails ATOMICALLY, leaving no owners/test-2 behind.
curl -sS -X PATCH "$DB/.json" \
  -d "{\"sharedPoints/test-2\": {\"name\":\"\",\"lat\":33,\"lon\":-7,\"createdAt\":1},
       \"owners/test-2\": \"$DIGEST\"}"

# Editing the point directly must FAIL. sharedPoints stays create-only for clients; that rule
# is what makes a moderator's deletion stick, and editing does not get an exception to it.
curl -sS -X PATCH "$DB/sharedPoints/test-1.json" -d '{"name":"Edited directly"}'
```

### Editing goes through the queue, and removing a note must actually remove it

```sh
# Add a note first, so there is something to remove.
curl -sS -X PUT "$DB/pendingEdits/test-1.json" \
  -d "{\"t\":\"$TOKEN\",\"name\":\"Test\",\"lat\":33.57,\"lon\":-7.59,\"note\":\"bring rope\"}"
# ...run the workflow; log: "applied edit to test-1"
curl -sS "$DB/sharedPoints/test-1.json"          # note is now "bring rope"

# Now remove it. `note` is present and NULL — not omitted.
curl -sS -X PUT "$DB/pendingEdits/test-1.json" \
  -d "{\"t\":\"$TOKEN\",\"name\":\"Test\",\"lat\":33.57,\"lon\":-7.59,\"note\":null}"
# ...run the workflow again
curl -sS "$DB/sharedPoints/test-1.json"          # the note must be GONE, not still "bring rope"
```

**This is the check that would be silent if the job were wrong.** The edit is applied as a
`PATCH`, and a `PATCH` that omits a key leaves the old value — so an implementation that skipped
an absent note instead of deleting the child would make "remove my note" the one edit that does
nothing, while the app reported it as sent. If the note survives this, the job's note handling is
broken, not the client's.

Note also that the second `PUT` above only succeeds because the workflow deleted the first queue
entry. The queue is create-only; the slot is freed on every path.

### Proving the negative: a stranger cannot withdraw or edit your point

This is the check the old design would have failed, so it is worth doing deliberately.

```sh
# An attacker has read the feed and knows the id. They guess a token.
FAKE=$(printf 'guess' | sha256sum | cut -d' ' -f1)
curl -sS -X PUT "$DB/tombstones/test-1.json" -d "\"$FAKE\""

# And they try to move the well eight kilometres, which is the worse of the two.
curl -sS -X PUT "$DB/pendingEdits/test-1.json" \
  -d "{\"t\":\"$FAKE\",\"name\":\"Test\",\"lat\":33.64,\"lon\":-7.59,\"note\":null}"
```

**Both return 200, and that is correct.** Do not read it as a failure of the rules, and do not
"fix" it by locking the nodes down — an unauthenticated client has to be able to write its own
withdrawal and its own edit, and there is nobody to authenticate it as. The write is a request,
not an act.

**Assert on the workflow's output, not on the HTTP status.** Run the cleanup workflow from the
Actions tab and read the log:

```
::warning::refused edit to test-1 (token does not match the owner digest)
::warning::refused test-1 (token does not match the owner digest) — point kept
```

Then confirm the point is still there, and still where it was:

```sh
curl -sS "$DB/sharedPoints/test-1.json"          # still the point, lat still 33.57; NOT null
```

Now do it with the real token, and watch all four nodes go:

```sh
curl -sS -X PUT "$DB/tombstones/test-1.json" -d "\"$TOKEN\""
# ...run the workflow again; the log should say: removed test-1
curl -sS "$DB/sharedPoints/test-1.json"          # null
curl -sS -H "Authorization: Bearer $SA_TOKEN" "$DB/owners/test-1.json"   # null
```

If you only ever look at the status codes, the honest and the hostile writes look identical and
you will conclude the wrong thing. The refusal lives in the drain, and the drain's log is where
it is visible.

## How the client behaves

- Refresh only when the map tab opens **and** the cache is older than 6 h, using ETags so an
  unchanged feed costs a 304. There is one exception: a refresh is forced immediately after a
  successful publish, edit or withdrawal, so what the user sees next is something the app
  observed rather than something it assumed. No background polling; everything is cached on disk,
  so dots show in airplane mode.
- Saving someone else's dot keeps its id, so it becomes a normal editable destination and the
  teal dot disappears (no duplicates).
- **The app never claims a sharing state it has not observed.** "Publicly shared" is shown only
  for a point seen in a fetched feed *carrying what this device holds*. A withdrawal on a device
  that has never been online reads "Withdrawal not confirmed" and keeps saying so, because
  whether the job ran is not something the device can know. There is deliberately no "gone within
  about a day" anywhere in the UI.
- **Editing a published point says so until the edit lands.** The point is public and the public
  copy is the old text, so the row reads *"Publicly shared — your edit is not published yet"*.
  That is computed by comparing the local point against the cached feed, which means it is true
  the instant the user hits save, with no signal and nothing stored to track it. It returns to
  "Publicly shared" when a fetched feed comes back carrying the new text — never before.
- A publish, an edit or a withdrawal that could not be delivered is retried on the next
  successful sync, by comparing local state against the fetched feed. There is no queue file and
  no backoff state to get stuck: the difference is recomputed from scratch every time.
- A device that has lost its token for a point — app data cleared, or a different phone — can
  neither withdraw nor edit it. The app says so and goes on saying it, rather than hiding the
  badge and letting the user believe otherwise.

## Free-tier headroom

Spark allows ~1 GB stored and 10 GB/month egress. A point is ~120 bytes and an owner digest is
64; even ten thousand points refreshed hourly by a thousand users stays orders of magnitude
inside that. The 6-hour cache rule exists so real usage never gets near the limits, not because
they are close.
