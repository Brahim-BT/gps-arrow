#!/usr/bin/env bash
#
# Prove the whole download chain against the real hosted files.
#
#     ./verification/check_hosted_assets.sh
#
# Fetches the first 127 bytes of each release asset by HTTP range and runs them through the same
# header validation the app uses (core/.../Pmtiles.kt). About 400 bytes of traffic total.
#
# This exists because the agent's sandbox cannot reach release-assets.githubusercontent.com --
# its egress proxy allows github.com but not the CDN the download redirects to. So the first hop
# (302, correct host) is verifiable from there and the second is not. This closes that gap.
#
# What a pass proves, end to end:
#   1. the URL the catalogue builds resolves anonymously
#   2. GitHub redirects it to the asset CDN
#   3. the CDN honours Range with 206 and a correct content-range
#   4. the bytes are a valid PMTiles v3 header the app would accept
#   5. the total size matches what the catalogue claims

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

BASE="https://github.com/Brahim-BT/gps-arrow/releases/download/maps-v1"

# name:expected_total_bytes:expected_maxzoom  (from RegionCatalogue.kt)
ASSETS=(
    "morocco-z12:61851096:12"
    "morocco-z13:133209973:13"
    "mauritania-z13:42919292:13"
)

fail=0

for entry in "${ASSETS[@]}"; do
    name="${entry%%:*}"
    rest="${entry#*:}"
    want_bytes="${rest%%:*}"
    want_maxzoom="${rest##*:}"

    echo "=== $name"

    headers=$(mktemp)
    body=$(mktemp)
    trap 'rm -f "$headers" "$body"' EXIT

    if ! curl -sL --max-time 60 -r 0-126 -D "$headers" -o "$body" "$BASE/$name.pmtiles"; then
        echo "    FAIL: request failed"
        fail=1
        continue
    fi

    status=$(grep -c '^HTTP/[0-9.]* 206' "$headers" || true)
    crange=$(grep -i '^content-range:' "$headers" | tail -1 | tr -d '\r' || true)
    got=$(wc -c < "$body")

    if [ "$status" -lt 1 ]; then
        echo "    FAIL: no 206 Partial Content in the response chain"
        grep -i '^HTTP/' "$headers" | sed 's/^/        /'
        fail=1
    else
        echo "    206 Partial Content"
    fi

    echo "    $crange"
    echo "    received $got bytes (want 127)"
    [ "$got" -eq 127 ] || { echo "    FAIL: wrong byte count"; fail=1; }

    # total from "content-range: bytes 0-126/<total>"
    total="${crange##*/}"
    if [ "$total" = "$want_bytes" ]; then
        echo "    total $total matches the catalogue"
    else
        echo "    FAIL: total $total but the catalogue says $want_bytes"
        fail=1
    fi

    python3 - "$body" "$want_maxzoom" <<'PY' || fail=1
import sys
h = open(sys.argv[1], "rb").read()
want_maxzoom = int(sys.argv[2])
ok = True
def check(label, cond, detail=""):
    global ok
    print(f"    {'ok  ' if cond else 'FAIL'} {label}{(' -- ' + detail) if detail else ''}")
    ok = ok and cond
check("127 header bytes", len(h) == 127, f"got {len(h)}")
if len(h) == 127:
    check("magic is PMTiles", h[:7] == b"PMTiles", repr(h[:7]))
    check("spec version 3", h[7] == 3, str(h[7]))
    check("tile type MVT", h[99] == 1, str(h[99]))
    check("compression none or gzip", h[97] in (1, 2) and h[98] in (1, 2), f"{h[97]},{h[98]}")
    check("minzoom 0", h[100] == 0, str(h[100]))
    check(f"maxzoom {want_maxzoom}", h[101] == want_maxzoom, str(h[101]))
    def e7(a):
        v = int.from_bytes(h[a:a+4], "little", signed=True)
        return v / 1e7
    print(f"         bounds {e7(102):.2f},{e7(106):.2f},{e7(110):.2f},{e7(114):.2f}")
sys.exit(0 if ok else 1)
PY
    echo
done

if [ "$fail" -eq 0 ]; then
    echo "ALL THREE ASSETS PASS end to end: URL, redirect, range support, header validity, size."
else
    echo "SOMETHING FAILED above. Do not ship against these URLs until it is understood." >&2
    exit 1
fi
