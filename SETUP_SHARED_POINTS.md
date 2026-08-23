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
   `https://gps-arrow-xxxxx-default-rtdb.europe-west1.firebasedatabase.app`.

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
        ".validate": "newData.hasChildren(['name', 'lat', 'lon', 'createdAt', 'deviceId'])",
        "name":      { ".validate": "newData.isString() && newData.val().length >= 1 && newData.val().length <= 40" },
        "lat":       { ".validate": "newData.isNumber() && newData.val() >= -90 && newData.val() <= 90" },
        "lon":       { ".validate": "newData.isNumber() && newData.val() >= -180 && newData.val() <= 180" },
        "note":      { ".validate": "!newData.exists() || (newData.isString() && newData.val().length <= 200)" },
        "createdAt": { ".validate": "newData.isNumber()" },
        "deviceId":  { ".validate": "newData.isString()" },
        "$other":    { ".validate": false }
      }
    },

    "tombstones": {
      "$pointId": {
        ".write": true,
        ".validate": "newData.isString()"
      }
    }
  }
}
```

What this buys you:

- **Create-only writes.** Nobody can overwrite or delete another device's point — un-sharing
  works by queueing into `tombstones/<id>` instead. That is also your moderation tool: delete
  any point by hand from the console's data view.
- **Server-side validation** mirrors what the app checks locally, so a hostile client cannot
  publish a 10 kB name or a lat of 9999.
- Clients cannot read anything except `sharedPoints`; they cannot read the tombstone queue.

## 3. Daily tombstone drain (GitHub Actions)

When a user stops sharing, their device queues the id into `tombstones/<id>` (value = its
device id). A scheduled workflow deletes matching points within about a day:

1. Project settings → **Service accounts → Generate new private key** → download the JSON.
2. Repo → Settings → Secrets and variables → Actions → **New repository secret**,
   name `FIREBASE_SERVICE_ACCOUNT`, paste the whole JSON file as the value.
3. Commit `.github/workflows/shared-points-cleanup.yml` (already in this repo), then edit the
   `DB:` line inside it to your database URL from step 1.

Run it once from the Actions tab (**Run workflow**) to check it works; it also runs daily and
is safe to re-run — it only ever deletes points whose stored `deviceId` matches the
tombstone's, and reports mismatches without touching them.

## 4. Point the app at it

Edit `app/src/main/java/dev/gpsarrow/data/SharedPointsConfig.kt`:

```kotlin
const val BASE_URL = "https://gps-arrow-xxxxx-default-rtdb.europe-west1.firebasedatabase.app"
```

Rebuild. The share toggle appears in Add/Edit, published points get a globe badge in the list,
and everyone's map shows the shared dots.

## 5. Smoke-test with curl

```sh
DB=https://gps-arrow-xxxxx-default-rtdb.europe-west1.firebasedatabase.app

# Publish (what the app does)
curl -sS -X PUT "$DB/sharedPoints/test-1.json" \
  -d '{"name":"Test","lat":33.57,"lon":-7.59,"createdAt":1700000000000,"deviceId":"test"}'

# Read the feed (what every client does)
curl -sS "$DB/sharedPoints.json"

# Overwrite must FAIL with "Permission denied"
curl -sS -X PUT "$DB/sharedPoints/test-1.json" \
  -d '{"name":"Hacked","lat":0,"lon":0,"createdAt":1,"deviceId":"evil"}'

# A bad name must FAIL too
curl -sS -X PUT "$DB/sharedPoints/test-2.json" \
  -d '{"name":"","lat":33,"lon":-7,"createdAt":1,"deviceId":"test"}'

# Queue removal, then run the workflow once — both should vanish
curl -sS -X PUT "$DB/tombstones/test-1.json" -d '"test"'
```

## How the client behaves

- Refresh only when the map tab opens **and** the cache is older than 6 h, using ETags so an
  unchanged feed costs a 304. No background polling; everything is cached on disk, so dots
  show in airplane mode.
- Saving someone else's dot keeps its id, so it becomes a normal editable destination and the
  teal dot disappears (no duplicates).
- Un-sharing hides the dot immediately on the owner's device; everyone else sees it go within
  ~24 h (the next workflow run).

## Free-tier headroom

Spark allows ~1 GB stored and 10 GB/month egress. A point is ~120 bytes; even ten thousand
points refreshed hourly by a thousand users stays orders of magnitude inside that. The 6-hour
cache rule exists so real usage never gets near the limits, not because they are close.
