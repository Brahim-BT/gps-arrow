"""Transpiled from the Kotlin in core/src/main/kotlin, then run against the exact assertions
in core/src/test/kotlin/dev/gpsarrow/core/*.kt.

This exists because the sandbox has a JRE but no kotlinc, so the Kotlin can't be compiled here.
Transpiling it back and asserting the same expectations catches transcription errors in the
port, which is the realistic failure mode.

Kotlin semantics preserved deliberately:
  - Double.toInt() / toLong() truncate toward zero (all values here are non-negative)
  - Long division truncates
  - Double % Double takes the sign of the dividend
"""
import math
import random
import sys

failures = []


def check(name, condition, detail=""):
    if condition:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name}  {detail}")
        failures.append(name)


def close(a, b, tol):
    return abs(a - b) <= tol


# ===================================================================== Geo.kt

EARTH_RADIUS_M = 6_371_008.8
DEG = math.pi / 180.0
RAD = 180.0 / math.pi


def wrap_longitude(lon):
    while lon > 180.0:
        lon -= 360.0
    while lon < -180.0:
        lon += 360.0
    return lon


def distance_meters(a, b):
    phi1, phi2 = a[0] * DEG, b[0] * DEG
    d_phi = (b[0] - a[0]) * DEG
    d_lam = (b[1] - a[1]) * DEG
    s1, s2 = math.sin(d_phi / 2), math.sin(d_lam / 2)
    h = s1 * s1 + math.cos(phi1) * math.cos(phi2) * s2 * s2
    return 2.0 * EARTH_RADIUS_M * math.asin(math.sqrt(min(1.0, max(0.0, h))))


def normalize_degrees(deg):
    d = math.fmod(deg, 360.0)
    return d + 360.0 if d < 0 else d


def initial_bearing(a, b):
    phi1, phi2 = a[0] * DEG, b[0] * DEG
    d_lam = (b[1] - a[1]) * DEG
    y = math.sin(d_lam) * math.cos(phi2)
    x = math.cos(phi1) * math.sin(phi2) - math.sin(phi1) * math.cos(phi2) * math.cos(d_lam)
    return normalize_degrees(math.atan2(y, x) * RAD)


def destination(frm, bearing_deg, distance_m):
    delta = distance_m / EARTH_RADIUS_M
    theta = bearing_deg * DEG
    phi1, lam1 = frm[0] * DEG, frm[1] * DEG
    sin_phi2 = math.sin(phi1) * math.cos(delta) + math.cos(phi1) * math.sin(delta) * math.cos(theta)
    phi2 = math.asin(min(1.0, max(-1.0, sin_phi2)))
    lam2 = lam1 + math.atan2(
        math.sin(theta) * math.sin(delta) * math.cos(phi1),
        math.cos(delta) - math.sin(phi1) * sin_phi2,
    )
    return (phi2 * RAD, wrap_longitude(lam2 * RAD))


def angle_delta(frm, to):
    d = math.fmod(to - frm, 360.0)
    if d > 180.0:
        d -= 360.0
    if d <= -180.0:
        d += 360.0
    return d


class CircularSmoother:
    def __init__(self, alpha=0.15):
        self.alpha, self.sx, self.sy, self.seeded = alpha, 0.0, 0.0, False

    def update(self, degrees):
        r = degrees * math.pi / 180.0
        x, y = math.cos(r), math.sin(r)
        if not self.seeded:
            self.sx, self.sy, self.seeded = x, y, True
        else:
            self.sx += self.alpha * (x - self.sx)
            self.sy += self.alpha * (y - self.sy)
        return normalize_degrees(math.atan2(self.sy, self.sx) * 180.0 / math.pi)


POINTS = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
          "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"]


def compass_point(deg):
    return POINTS[int((normalize_degrees(deg) / 22.5) + 0.5) % 16]


def kt_round_half_up(x):
    """Kotlin roundToInt/roundToLong: half rounds up (toward +inf)."""
    return math.floor(x + 0.5)


def format_distance(meters):
    if meters < 1000:
        return f"{int(kt_round_half_up(meters / 10.0)) * 10} m"
    if meters < 100000:
        return "%.1f km" % (meters / 1000.0)
    return f"{int(kt_round_half_up(meters / 1000.0))} km"


# ===================================================================== Mgrs.kt

A = 6_378_137.0
F = 1.0 / 298.257223563
E2 = F * (2 - F)
EP2 = E2 / (1 - E2)
K0 = 0.9996
BANDS = "CDEFGHJKLMNPQRSTUVWX"
COL_SETS = ["ABCDEFGH", "JKLMNPQR", "STUVWXYZ"]
ROW_LETTERS = "ABCDEFGHJKLMNPQRSTUV"


def zone_for(lat, lon):
    zone = int(math.floor((lon + 180.0) / 6.0)) + 1
    zone = min(60, max(1, zone))
    if 56.0 <= lat < 64.0 and 3.0 <= lon < 12.0:
        zone = 32
    if 72.0 <= lat < 84.0:
        if 0.0 <= lon < 9.0:
            zone = 31
        elif 9.0 <= lon < 21.0:
            zone = 33
        elif 21.0 <= lon < 33.0:
            zone = 35
        elif 33.0 <= lon < 42.0:
            zone = 37
    return zone


def band_for(lat):
    if lat >= 84.0:
        return "X"
    if lat < -80.0:
        return "C"
    return BANDS[min(len(BANDS) - 1, max(0, int(math.floor((lat + 80.0) / 8.0))))]


def central_meridian(zone):
    return (zone - 1) * 6.0 - 180.0 + 3.0


def to_utm(p, forced_zone=None):
    zone = forced_zone if forced_zone is not None else zone_for(p[0], p[1])
    lon0 = math.radians(central_meridian(zone))
    phi = math.radians(p[0])
    n = A / math.sqrt(1 - E2 * math.sin(phi) ** 2)
    t = math.tan(phi) ** 2
    c = EP2 * math.cos(phi) ** 2
    dl = math.radians(p[1]) - lon0
    while dl > math.pi:
        dl -= 2 * math.pi
    while dl <= -math.pi:
        dl += 2 * math.pi
    a1 = dl * math.cos(phi)
    m = A * (
        (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2 ** 3 / 256) * phi
        - (3 * E2 / 8 + 3 * E2 * E2 / 32 + 45 * E2 ** 3 / 1024) * math.sin(2 * phi)
        + (15 * E2 * E2 / 256 + 45 * E2 ** 3 / 1024) * math.sin(4 * phi)
        - (35 * E2 ** 3 / 3072) * math.sin(6 * phi)
    )
    easting = K0 * n * (
        a1 + (1 - t + c) * a1 ** 3 / 6 + (5 - 18 * t + t * t + 72 * c - 58 * EP2) * a1 ** 5 / 120
    ) + 500_000.0
    northing = K0 * (
        m + n * math.tan(phi) * (
            a1 * a1 / 2
            + (5 - t + 9 * c + 4 * c * c) * a1 ** 4 / 24
            + (61 - 58 * t + t * t + 600 * c - 330 * EP2) * a1 ** 6 / 720
        )
    )
    northern = p[0] >= 0
    if not northern:
        northing += 10_000_000.0
    return (zone, northern, easting, northing)


def from_utm(u):
    zone, northern, easting, northing = u
    x = easting - 500_000.0
    y = northing if northern else northing - 10_000_000.0
    lon0 = math.radians(central_meridian(zone))
    m = y / K0
    mu = m / (A * (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2 ** 3 / 256))
    e1 = (1 - math.sqrt(1 - E2)) / (1 + math.sqrt(1 - E2))
    phi1 = (mu
            + (3 * e1 / 2 - 27 * e1 ** 3 / 32) * math.sin(2 * mu)
            + (21 * e1 * e1 / 16 - 55 * e1 ** 4 / 32) * math.sin(4 * mu)
            + (151 * e1 ** 3 / 96) * math.sin(6 * mu)
            + (1097 * e1 ** 4 / 512) * math.sin(8 * mu))
    c1 = EP2 * math.cos(phi1) ** 2
    t1 = math.tan(phi1) ** 2
    n1 = A / math.sqrt(1 - E2 * math.sin(phi1) ** 2)
    r1 = A * (1 - E2) / (1 - E2 * math.sin(phi1) ** 2) ** 1.5
    d = x / (n1 * K0)
    phi = phi1 - (n1 * math.tan(phi1) / r1) * (
        d * d / 2
        - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * EP2) * d ** 4 / 24
        + (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * EP2 - 3 * c1 * c1) * d ** 6 / 720
    )
    lam = lon0 + (
        d - (1 + 2 * t1 + c1) * d ** 3 / 6
        + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * EP2 + 24 * t1 * t1) * d ** 5 / 120
    ) / math.cos(phi1)
    return (math.degrees(phi), wrap_longitude(math.degrees(lam)))


def to_mgrs(p, digits=5, spaced=False):
    if p[0] > 84.0 or p[0] < -80.0:
        return None
    zone, northern, easting, northing = to_utm(p)
    band = band_for(p[0])
    col_idx = int(easting / 100_000.0)
    if col_idx < 1 or col_idx > 8:
        return None
    col = COL_SETS[(zone - 1) % 3][col_idx - 1]
    row_idx = int(math.fmod(northing, 2_000_000.0) / 100_000.0)
    if zone % 2 == 0:
        row_idx = (row_idx + 5) % 20
    row = ROW_LETTERS[row_idx]
    div = 10.0 ** (5 - digits)
    e = int(math.fmod(easting, 100_000.0) / div)
    n = int(math.fmod(northing, 100_000.0) / div)
    ep, np_ = str(e).rjust(digits, "0"), str(n).rjust(digits, "0")
    return f"{zone}{band} {col}{row} {ep} {np_}" if spaced else f"{zone}{band}{col}{row}{ep}{np_}"


def from_mgrs(text):
    s = "".join(ch for ch in text if not ch.isspace()).upper()
    if len(s) < 5:
        return None
    i = 0
    while i < len(s) and s[i].isdigit():
        i += 1
    if not (1 <= i <= 2) or len(s) < i + 3:
        return None
    try:
        zone = int(s[:i])
    except ValueError:
        return None
    if not (1 <= zone <= 60):
        return None
    band, col, row = s[i], s[i + 1], s[i + 2]
    if BANDS.find(band) < 0:
        return None
    col_idx = COL_SETS[(zone - 1) % 3].find(col)
    if col_idx < 0:
        return None
    row_idx = ROW_LETTERS.find(row)
    if row_idx < 0:
        return None
    rest = s[i + 3:]
    if rest and (len(rest) % 2 != 0 or not rest.isdigit()):
        return None
    half = len(rest) // 2
    div = 10.0 ** (5 - half)
    e_rem = (int(rest[:half]) * div if half else 0.0) + div / 2.0
    n_rem = (int(rest[half:]) * div if half else 0.0) + div / 2.0
    easting = (col_idx + 1) * 100_000.0 + e_rem
    if zone % 2 == 0:
        row_idx = ((row_idx - 5) % 20 + 20) % 20
    base = row_idx * 100_000.0 + n_rem
    northern = band >= "N"
    bi = BANDS.find(band)
    lat_lo = bi * 8.0 - 80.0
    lat_hi = 84.0 if band == "X" else lat_lo + 8.0
    fallback, fallback_err = None, float("inf")
    for k in range(6):
        cand = base + k * 2_000_000.0
        if cand > 10_000_000.0:
            break
        p = from_utm((zone, northern, easting, cand))
        if lat_lo - 0.05 <= p[0] < lat_hi + 0.05:
            return p
        err = min(abs(p[0] - lat_lo), abs(p[0] - lat_hi))
        if err < fallback_err:
            fallback_err, fallback = err, p
    return fallback


# ===================================================================== PlusCode.kt

ALPHABET = "23456789CFGHJMPQRVWX"
SEPARATOR, SEPARATOR_POSITION, PADDING = "+", 8, "0"
PAIR_LEN, MAX_DIGITS, GRID_ROWS, GRID_COLS = 10, 15, 4, 5
PAIR_PRECISION = 8_000
PAIR_FIRST_PLACE = 160_000
FINAL_LAT_PRECISION = PAIR_PRECISION * 1_024
FINAL_LNG_PRECISION = PAIR_PRECISION * 3_125
GRID_LAT_FIRST_PLACE = 256
GRID_LNG_FIRST_PLACE = 625


def precision_degrees(code_length):
    if code_length <= PAIR_LEN:
        return 20.0 ** math.floor(code_length / -2.0 + 2.0)
    return 20.0 ** -3 / (GRID_ROWS ** (code_length - PAIR_LEN))


def olc_is_valid(code):
    s = code.strip().upper()
    sep = s.find(SEPARATOR)
    if sep < 0 or sep != s.rfind(SEPARATOR):
        return False
    if sep > SEPARATOR_POSITION or sep % 2 == 1:
        return False
    pad_index = s.find(PADDING)
    if pad_index >= 0:
        if pad_index < 2 or pad_index % 2 == 1:
            return False
        tail = s[pad_index:]
        if any(c != PADDING for c in tail[:-1]) or tail[-1] != SEPARATOR:
            return False
        if sep != len(s) - 1:
            return False
    if len(s) - sep - 1 == 1:
        return False
    return all(ALPHABET.find(c) >= 0 for c in s if c not in (SEPARATOR, PADDING))


def olc_is_full(code):
    if not olc_is_valid(code):
        return False
    s = code.strip().upper()
    if s.find(SEPARATOR) != SEPARATOR_POSITION:
        return False
    if ALPHABET.find(s[0]) * 20 >= 180:
        return False
    if len(s) > 1 and ALPHABET.find(s[1]) * 20 >= 360:
        return False
    return True


def olc_is_short(code):
    return olc_is_valid(code) and 0 <= code.strip().find(SEPARATOR) < SEPARATOR_POSITION


def olc_encode(p, code_length=PAIR_LEN):
    length = min(code_length, MAX_DIGITS)
    lat = min(90.0, max(-90.0, p[0]))
    lon = wrap_longitude(p[1])
    if lon >= 180.0:
        lon -= 360.0
    if lat == 90.0:
        lat -= precision_degrees(length)
    lat_val = int(math.floor((lat + 90.0) * FINAL_LAT_PRECISION))
    lng_val = int(math.floor((lon + 180.0) * FINAL_LNG_PRECISION))
    lat_val = max(0, min(lat_val, 180 * FINAL_LAT_PRECISION - 1))
    lng_val = max(0, min(lng_val, 360 * FINAL_LNG_PRECISION - 1))

    sb = ""
    if length > PAIR_LEN:
        for _ in range(MAX_DIGITS - PAIR_LEN):
            idx = (lat_val % GRID_ROWS) * GRID_COLS + (lng_val % GRID_COLS)
            sb = ALPHABET[idx] + sb
            lat_val //= GRID_ROWS
            lng_val //= GRID_COLS
    else:
        lat_val //= 1_024
        lng_val //= 3_125
    for _ in range(PAIR_LEN // 2):
        sb = ALPHABET[lng_val % 20] + sb
        sb = ALPHABET[lat_val % 20] + sb
        lat_val //= 20
        lng_val //= 20
    sb = sb[:SEPARATOR_POSITION] + SEPARATOR + sb[SEPARATOR_POSITION:]
    if length >= SEPARATOR_POSITION:
        return sb[: length + 1]
    return sb[:length] + PADDING * (SEPARATOR_POSITION - length) + SEPARATOR


def olc_decode(code):
    if not olc_is_full(code):
        return None
    clean = code.strip().upper().replace(SEPARATOR, "")
    pad = clean.find(PADDING)
    if pad >= 0:
        clean = clean[:pad]
    if not clean:
        return None
    clean = clean[: min(len(clean), MAX_DIGITS)]

    normal_lat, normal_lng, grid_lat, grid_lng = -90 * PAIR_PRECISION, -180 * PAIR_PRECISION, 0, 0
    digits = min(len(clean), PAIR_LEN)
    pv = PAIR_FIRST_PLACE
    i = 0
    while i < digits:
        normal_lat += ALPHABET.find(clean[i]) * pv
        normal_lng += ALPHABET.find(clean[i + 1]) * pv
        if i < digits - 2:
            pv //= 20
        i += 2
    lat_precision = pv / PAIR_PRECISION
    lng_precision = pv / PAIR_PRECISION

    if len(clean) > PAIR_LEN:
        row_pv, col_pv = GRID_LAT_FIRST_PLACE, GRID_LNG_FIRST_PLACE
        digits = min(len(clean), MAX_DIGITS)
        for j in range(PAIR_LEN, digits):
            d = ALPHABET.find(clean[j])
            grid_lat += (d // GRID_COLS) * row_pv
            grid_lng += (d % GRID_COLS) * col_pv
            if j < digits - 1:
                row_pv //= GRID_ROWS
                col_pv //= GRID_COLS
        lat_precision = row_pv / FINAL_LAT_PRECISION
        lng_precision = col_pv / FINAL_LNG_PRECISION

    lat = normal_lat / PAIR_PRECISION + grid_lat / FINAL_LAT_PRECISION
    lng = normal_lng / PAIR_PRECISION + grid_lng / FINAL_LNG_PRECISION
    return ((lat, lng), (lat + lat_precision, lng + lng_precision), min(len(clean), MAX_DIGITS))


def olc_center(area):
    (a, b), (c, d), _ = area
    return ((a + c) / 2, (b + d) / 2)


def olc_recover_nearest(short_code, reference):
    if olc_is_full(short_code):
        return short_code.strip().upper()
    if not olc_is_short(short_code):
        return None
    s = short_code.strip().upper()
    padding = SEPARATOR_POSITION - s.find(SEPARATOR)
    resolution = 20.0 ** (2.0 - padding / 2.0)
    half_res = resolution / 2.0
    ref_lat = min(90.0, max(-90.0, reference[0]))
    ref_lon = wrap_longitude(reference[1])
    prefix = olc_encode((ref_lat, ref_lon), PAIR_LEN)[:padding]
    area = olc_decode(prefix + s)
    if area is None:
        return None
    lat, lon = olc_center(area)
    if ref_lat - lat > half_res and lat + resolution <= 90:
        lat += resolution
    elif lat - ref_lat > half_res and lat - resolution >= -90:
        lat -= resolution
    if ref_lon - lon > half_res:
        lon += resolution
    elif lon - ref_lon > half_res:
        lon -= resolution
    return olc_encode((lat, lon), area[2])


# ===================================================================== HeadingArbiter

TAKEOVER_MPS, HANDBACK_MPS = 2.5, 1.5
COMPASS, GPS_COURSE, COMPASS_UNCALIBRATED, NONE = "COMPASS", "GPS_COURSE", "COMPASS_UNCAL", "NONE"


def arbiter_select(speed, course, compass_deg, reliable, previous):
    threshold = HANDBACK_MPS if previous == GPS_COURSE else TAKEOVER_MPS
    if speed is not None and course is not None and speed >= threshold:
        return course, GPS_COURSE
    if compass_deg is not None:
        return compass_deg, (COMPASS if reliable else COMPASS_UNCALIBRATED)
    if course is not None:
        return course, GPS_COURSE
    return None, NONE


# ===================================================================== the assertions

print("GeoTest")
check("1 deg latitude at equator ~= 111 km",
      close(distance_meters((0, 0), (1, 0)), 111_195.0, 200.0),
      distance_meters((0, 0), (1, 0)))
london, paris = (51.5074, -0.1278), (48.8566, 2.3522)
check("london -> paris ~= 343 km", close(distance_meters(london, paris), 343_000.0, 3_000.0),
      round(distance_meters(london, paris)))
check("antipodal = half circumference",
      close(distance_meters((0, 0), (0, 180)), math.pi * EARTH_RADIUS_M, 1.0))
check("cardinal bearings",
      all(close(initial_bearing((0, 0), t), e, 1e-6) for t, e in
          [((1, 0), 0.0), ((0, 1), 90.0), ((-1, 0), 180.0), ((0, -1), 270.0)]))
check("london -> paris bearing ~148", abs(initial_bearing(london, paris) - 148.0) < 3.0,
      round(initial_bearing(london, paris), 2))

ok = True
for bearing in range(0, 360, 17):
    for dist in (10.0, 1_000.0, 100_000.0, 3_000_000.0):
        p = destination(paris, float(bearing), dist)
        if not close(distance_meters(paris, p), dist, dist * 1e-6 + 0.01):
            ok = False
        if not close(initial_bearing(paris, p), float(bearing), 1e-6):
            ok = False
check("destination/distance/bearing round-trip", ok)

check("angle delta short way",
      close(angle_delta(359, 1), 2.0, 1e-9) and close(angle_delta(1, 359), -2.0, 1e-9)
      and close(angle_delta(0, 180), 180.0, 1e-9) and close(angle_delta(0, 270), -90.0, 1e-9))

s = CircularSmoother(0.5)
s.update(350.0)
out = s.update(10.0)
check("circular smoother crosses the 360 wrap", out > 350.0 or out < 10.0, round(out, 3))

check("distance formatting",
      format_distance(123.0) == "120 m" and format_distance(1234.0) == "1.2 km"
      and format_distance(123456.0) == "123 km",
      (format_distance(123.0), format_distance(1234.0), format_distance(123456.0)))
check("compass points",
      compass_point(0) == "N" and compass_point(359) == "N" and compass_point(45) == "NE"
      and compass_point(180) == "S" and compass_point(315) == "NW")

def meters_apart(a, b):
    return math.hypot((b[0] - a[0]) * 111_320.0,
                      (b[1] - a[1]) * 111_320.0 * math.cos(math.radians(a[0])))


print("\nMgrsTest")
z, nrt, e, n = to_utm((40.0, -75.0))
check("central meridian easting is exactly 500000", z == 18 and close(e, 500_000.0, 1e-6), e)
check("northing at 40N", close(n, 4_427_757.0, 2.0), round(n, 3))
published = ["18SUJ2347706483", "31UDQ4825111924", "56HLH3490052288",
             "33UUU9020016830", "19TCG3050046690"]
check("published USNG references decode and re-encode exactly",
      all(to_mgrs(from_mgrs(c)) == c for c in published),
      [(c, to_mgrs(from_mgrs(c))) for c in published if to_mgrs(from_mgrs(c)) != c])
wm = from_mgrs("18SUJ2347706483")
check("washington monument decodes to the right place",
      close(wm[0], 38.88950, 1e-4) and close(wm[1], -77.03531, 1e-4), wm)
check("spaced formatting", to_mgrs(wm, spaced=True) == "18S UJ 23477 06483",
      to_mgrs(wm, spaced=True))
coarse = from_mgrs("18SUJ2306")
corner = from_mgrs("18SUJ2300006000")
check("decode returns square centre, so encode is stable",
      to_mgrs(coarse, 2) == "18SUJ2306" and 600.0 <= meters_apart(corner, coarse) <= 800.0,
      (to_mgrs(coarse, 2), round(meters_apart(corner, coarse), 1)))
allidem = True
la = -79.0
while la <= 83.0:
    lo = -179.0
    while lo <= 179.0:
        c = to_mgrs((la, lo))
        if to_mgrs(from_mgrs(c)) != c:
            allidem = False
        lo += 3.0
    la += 2.0
check("encode(decode(code)) == code across a global sweep", allidem)
check("norway/svalbard zone exceptions",
      zone_for(60.0, 5.0) == 32 and zone_for(78.0, 15.0) == 33
      and zone_for(78.0, 5.0) == 31 and zone_for(78.0, 25.0) == 35)
check("polar refused", to_mgrs((85.0, 10.0)) is None and to_mgrs((-81.0, 10.0)) is None)

worst, worst_at = 0.0, None
lat = -79.0
while lat <= 83.0:
    lon = -179.0
    while lon <= 179.0:
        code = to_mgrs((lat, lon))
        back = from_mgrs(code)
        d = meters_apart((lat, lon), back)
        if d > worst:
            worst, worst_at = d, (lat, lon, code)
        lon += 7.0
    lat += 3.0
check("global round-trip < 1 m", worst < 1.0, f"worst {worst:.3f} m at {worst_at}")

ok = True
for digits, tol in [(5, 2.0), (4, 20.0), (3, 200.0), (2, 2_000.0)]:
    code = to_mgrs((48.8584, 2.2945), digits)
    if meters_apart((48.8584, 2.2945), from_mgrs(code)) >= tol:
        ok = False
check("lower-precision codes decode near source", ok)
check("rubbish rejected",
      from_mgrs("hello") is None and from_mgrs("99ZZZ0000000000") is None
      and from_mgrs("") is None)

print("\nPlusCodeTest")
check("reference vectors",
      olc_encode((47.0000625, 8.0000625)) == "8FVC2222+22"
      and olc_encode((-41.2730625, 174.7859375)) == "4VCPPQGP+Q9",
      (olc_encode((47.0000625, 8.0000625)), olc_encode((-41.2730625, 174.7859375))))

rnd = random.Random(7)
bad = 0
for _ in range(2_000):
    p = (rnd.uniform(-89.9, 89.9), rnd.uniform(-179.9, 179.9))
    for length in (10, 11, 12, 15):
        area = olc_decode(olc_encode(p, length))
        (sw_lat, sw_lon), (ne_lat, ne_lon), _ = area
        if not (sw_lat <= p[0] <= ne_lat and sw_lon <= p[1] <= ne_lon):
            bad += 1
check("decode contains encoded point at every length", bad == 0, f"{bad} failures")

parent_code = olc_encode((48.8584, 2.2945), 10)
parent = olc_decode(parent_code)
children = [olc_decode(parent_code + c) for c in ALPHABET]
lat_edges = {c[0][0] for c in children}
lon_edges = {c[0][1] for c in children}
child_area = sum((c[1][0] - c[0][0]) * (c[1][1] - c[0][1]) for c in children)
parent_area = (parent[1][0] - parent[0][0]) * (parent[1][1] - parent[0][1])
check("grid tiles parent as 4 rows x 5 cols",
      len(lat_edges) == 4 and len(lon_edges) == 5
      and abs(child_area - parent_area) <= parent_area * 1e-9,
      (len(lat_edges), len(lon_edges)))

check("validity checks",
      olc_is_full("8FVC2222+22") and olc_is_short("2222+22")
      and not olc_is_valid("8FVC2222") and not olc_is_valid("ABCD1234+56"))

full = olc_encode((48.8584, 2.2945))
check("short code recovery lands near reference",
      olc_recover_nearest(full[4:], (48.85, 2.30)) == full,
      (full, olc_recover_nearest(full[4:], (48.85, 2.30))))

print("\nHeadingArbiterTest")
check("gps course wins when moving",
      arbiter_select(10.0, 42.0, 300.0, True, COMPASS) == (42.0, GPS_COURSE))
check("compass wins when stationary",
      arbiter_select(0.2, 42.0, 300.0, True, COMPASS) == (300.0, COMPASS))
check("hysteresis stops flapping",
      arbiter_select(2.0, 42.0, 300.0, True, COMPASS)[1] == COMPASS
      and arbiter_select(2.0, 42.0, 300.0, True, GPS_COURSE)[1] == GPS_COURSE)
check("unreliable magnetometer reported",
      arbiter_select(0.0, None, 300.0, False, COMPASS)[1] == COMPASS_UNCALIBRATED)
check("no sensors", arbiter_select(None, None, None, False, NONE) == (None, NONE))

print("\nNavigationStateTest")
dest = (48.8584, 2.2945)
here = (48.85, 2.2945)
bearing = initial_bearing(here, dest)
check("arrow relative to phone heading (north)", close(normalize_degrees(bearing - 0.0), 0.0, 0.5),
      round(normalize_degrees(bearing - 0.0), 4))
check("arrow relative to phone heading (east)",
      close(normalize_degrees(bearing - 90.0), 270.0, 0.5),
      round(normalize_degrees(bearing - 90.0), 4))

print()
if failures:
    print(f"{len(failures)} FAILING: {failures}")
    sys.exit(1)
print("All transpiled core assertions passed.")
