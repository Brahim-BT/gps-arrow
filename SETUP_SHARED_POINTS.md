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

## 2. Paste these security rules

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
- **Create-only everywhere.** Nobody can overwrite or delete another device's point, or replace
  an owner digest with their own.
- **Both write-only nodes are length-capped** at exactly 64 characters, which is what a hex
  SHA-256 and a minted token both are. Previously `tombstones` was an unbounded unauthenticated
  string write, which one hostile client could have used to fill the 1 GB tier and take the read
  feed down with it.

`tombstones` being create-only has one consequence worth knowing rather than discovering: a
stranger can occupy a point's queue slot with a wrong token and, until the next drain, the real
owner's withdrawal write is refused. It is the better of the two options — allowing overwrites
would let the same attacker clobber a *legitimate* tombstone at any moment right up to drain
time, which is the same denial with better timing for them. The drain deletes tombstones it
refuses, so each round of the attack costs the owner one cycle and the device retries on its
next sync. Throughout, the UI keeps saying the withdrawal is unconfirmed, which is true.

A note on the verb: the app sends this as a `POST` carrying `X-HTTP-Method-Override: PATCH`,
because `HttpURLConnection` refuses `setRequestMethod("PATCH")` outright. The database treats it
as the PATCH it is. `curl -X PATCH` below is the same request; only the wire verb differs.

### Moderation, and the one thing not to tidy

Deleting a point by hand from the console's data view is still the moderation tool, and it is
now **sticky**: the republish path is that same atomic `PATCH`, whose write to `owners/` is
create-only, so as long as `owners/<id>` exists the publisher's device cannot put the point
back. It will keep trying and keep being refused, and the user will keep seeing "not confirmed
yet", which is the truth.

**So do not tidy `owners/`.** An orphaned digest looks like litter and is doing a job. If you
want a point gone *and* the id usable again, delete all three nodes — `sharedPoints/<id>`,
`owners/<id>` and `tombstones/<id>` — which is exactly what the cleanup workflow does.

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

## 3. Daily tombstone drain (GitHub Actions)

When a user stops sharing, their device writes its token for that point to `tombstones/<id>`.
A scheduled workflow deletes the point when `sha256(token)` matches `owners/<id>`:

1. Project settings → **Service accounts → Generate new private key** → download the JSON.
2. Repo → Settings → Secrets and variables → Actions → **New repository secret**,
   name `FIREBASE_SERVICE_ACCOUNT`, paste the whole JSON file as the value.
3. Commit `.github/workflows/shared-points-cleanup.yml` (already in this repo), then edit the
   `DB:` line inside it to your database URL from step 1.

Run it once from the Actions tab (**Run workflow**) to check it works; it also runs daily and is
safe to re-run. It deletes all three nodes on a match, drops tombstones whose point does not
exist, and reports a `::warning::` for a token that does not match — which is not a fault but
the mechanism refusing a guess.

## 4. Point the app at it

Edit `app/src/main/java/dev/gpsarrow/data/SharedPointsConfig.kt`:

```kotlin
const val BASE_URL = "https://gps-baibbat-xxxxx-default-rtdb.europe-west1.firebasedatabase.app"
```

Rebuild. The share toggle appears in Add/Edit, the list shows what the app has actually observed
about each shared point, and everyone's map shows the shared dots.

## 5. Smoke-test with curl

The last two checks are the important ones, and they are the ones that are easy to run wrongly.

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
```

### Proving the negative: a stranger cannot withdraw your point

This is the check the old design would have failed, so it is worth doing deliberately.

```sh
# An attacker has read the feed and knows the id. They guess a token.
FAKE=$(printf 'guess' | sha256sum | cut -d' ' -f1)
curl -sS -X PUT "$DB/tombstones/test-1.json" -d "\"$FAKE\""
```

**This returns 200, and that is correct.** Do not read it as a failure of the rules, and do not
"fix" it by locking the node down — an unauthenticated client has to be able to write its own
withdrawal, and there is nobody to authenticate it as. The write is a request, not an act.

**Assert on the workflow's output, not on the HTTP status.** Run the cleanup workflow from the
Actions tab and read the log:

```
::warning::refused test-1 (token does not match the owner digest) — point kept
```

Then confirm the point is still there:

```sh
curl -sS "$DB/sharedPoints/test-1.json"          # still the point; NOT null
```

Now do it with the real token, and watch all three nodes go:

```sh
curl -sS -X PUT "$DB/tombstones/test-1.json" -d "\"$TOKEN\""
# ...run the workflow again; the log should say: removed test-1
curl -sS "$DB/sharedPoints/test-1.json"          # null
curl -sS -H "Authorization: Bearer $SA_TOKEN" "$DB/owners/test-1.json"   # null
```

If you only ever look at the status codes, both tombstone writes look identical and you will
conclude the wrong thing. The refusal lives in the drain, and the drain's log is where it is
visible.

## How the client behaves

- Refresh only when the map tab opens **and** the cache is older than 6 h, using ETags so an
  unchanged feed costs a 304. There is one exception: a refresh is forced immediately after a
  successful publish or withdrawal, so what the user sees next is something the app observed
  rather than something it assumed. No background polling; everything is cached on disk, so dots
  show in airplane mode.
- Saving someone else's dot keeps its id, so it becomes a normal editable destination and the
  teal dot disappears (no duplicates).
- **The app never claims a sharing state it has not observed.** "Publicly shared" is shown only
  for a point seen in a fetched feed. A withdrawal on a device that has never been online reads
  "Withdrawal not confirmed" and keeps saying so, because whether the daily job ran is not
  something the device can know. There is deliberately no "gone within about a day" anywhere in
  the UI.
- A publish or a withdrawal that could not be delivered is retried on the next successful sync,
  in both directions, by comparing the local intent against the fetched feed. There is no queue
  file and no backoff state to get stuck.
- A device that has lost its token for a point — app data cleared, or a different phone — cannot
  withdraw it. The app says the withdrawal is unconfirmed and goes on saying it, rather than
  hiding the badge and letting the user believe otherwise.

## Free-tier headroom

Spark allows ~1 GB stored and 10 GB/month egress. A point is ~120 bytes and an owner digest is
64; even ten thousand points refreshed hourly by a thousand users stays orders of magnitude
inside that. The 6-hour cache rule exists so real usage never gets near the limits, not because
they are close.
