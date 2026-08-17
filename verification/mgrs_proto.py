"""Reference prototype of the MGRS/UTM code that ships in :core.

Run to validate the algorithm before trusting the Kotlin port.
"""
import math

A = 6378137.0
F = 1 / 298.257223563
E2 = F * (2 - F)
EP2 = E2 / (1 - E2)
K0 = 0.9996

BANDS = "CDEFGHJKLMNPQRSTUVWX"
COL_SETS = ["ABCDEFGH", "JKLMNPQR", "STUVWXYZ"]
ROW_LETTERS = "ABCDEFGHJKLMNPQRSTUV"


def zone_for(lat, lon):
    zone = int(math.floor((lon + 180.0) / 6.0)) + 1
    if zone > 60:
        zone = 60
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
    idx = int(math.floor((lat + 80.0) / 8.0))
    idx = max(0, min(len(BANDS) - 1, idx))
    return BANDS[idx]


def ll_to_utm(lat, lon, zone=None):
    if zone is None:
        zone = zone_for(lat, lon)
    lon0 = math.radians((zone - 1) * 6 - 180 + 3)
    phi = math.radians(lat)
    lam = math.radians(lon)

    N = A / math.sqrt(1 - E2 * math.sin(phi) ** 2)
    T = math.tan(phi) ** 2
    C = EP2 * math.cos(phi) ** 2
    dl = lam - lon0
    while dl > math.pi:
        dl -= 2 * math.pi
    while dl <= -math.pi:
        dl += 2 * math.pi
    Aa = dl * math.cos(phi)

    M = A * (
        (1 - E2 / 4 - 3 * E2**2 / 64 - 5 * E2**3 / 256) * phi
        - (3 * E2 / 8 + 3 * E2**2 / 32 + 45 * E2**3 / 1024) * math.sin(2 * phi)
        + (15 * E2**2 / 256 + 45 * E2**3 / 1024) * math.sin(4 * phi)
        - (35 * E2**3 / 3072) * math.sin(6 * phi)
    )

    easting = K0 * N * (
        Aa + (1 - T + C) * Aa**3 / 6 + (5 - 18 * T + T**2 + 72 * C - 58 * EP2) * Aa**5 / 120
    ) + 500000.0

    northing = K0 * (
        M
        + N * math.tan(phi) * (
            Aa**2 / 2
            + (5 - T + 9 * C + 4 * C**2) * Aa**4 / 24
            + (61 - 58 * T + T**2 + 600 * C - 330 * EP2) * Aa**6 / 720
        )
    )
    if lat < 0:
        northing += 10000000.0
    return zone, easting, northing


def utm_to_ll(zone, easting, northing, northern):
    x = easting - 500000.0
    y = northing if northern else northing - 10000000.0
    lon0 = math.radians((zone - 1) * 6 - 180 + 3)

    M = y / K0
    mu = M / (A * (1 - E2 / 4 - 3 * E2**2 / 64 - 5 * E2**3 / 256))
    e1 = (1 - math.sqrt(1 - E2)) / (1 + math.sqrt(1 - E2))
    phi1 = (
        mu
        + (3 * e1 / 2 - 27 * e1**3 / 32) * math.sin(2 * mu)
        + (21 * e1**2 / 16 - 55 * e1**4 / 32) * math.sin(4 * mu)
        + (151 * e1**3 / 96) * math.sin(6 * mu)
        + (1097 * e1**4 / 512) * math.sin(8 * mu)
    )
    C1 = EP2 * math.cos(phi1) ** 2
    T1 = math.tan(phi1) ** 2
    N1 = A / math.sqrt(1 - E2 * math.sin(phi1) ** 2)
    R1 = A * (1 - E2) / (1 - E2 * math.sin(phi1) ** 2) ** 1.5
    D = x / (N1 * K0)

    phi = phi1 - (N1 * math.tan(phi1) / R1) * (
        D**2 / 2
        - (5 + 3 * T1 + 10 * C1 - 4 * C1**2 - 9 * EP2) * D**4 / 24
        + (61 + 90 * T1 + 298 * C1 + 45 * T1**2 - 252 * EP2 - 3 * C1**2) * D**6 / 720
    )
    lam = lon0 + (
        D
        - (1 + 2 * T1 + C1) * D**3 / 6
        + (5 - 2 * C1 + 28 * T1 - 3 * C1**2 + 8 * EP2 + 24 * T1**2) * D**5 / 120
    ) / math.cos(phi1)
    return math.degrees(phi), math.degrees(lam)


def to_mgrs(lat, lon, digits=5):
    zone, e, n = ll_to_utm(lat, lon)
    band = band_for(lat)
    col_idx = int(e // 100000)
    col = COL_SETS[(zone - 1) % 3][col_idx - 1]
    row_idx = int((n % 2000000) // 100000)
    if zone % 2 == 0:
        row_idx = (row_idx + 5) % 20
    row = ROW_LETTERS[row_idx]
    div = 10 ** (5 - digits)
    ee = int((e % 100000) // div)
    nn = int((n % 100000) // div)
    return f"{zone}{band}{col}{row}{ee:0{digits}d}{nn:0{digits}d}"


def from_mgrs(s):
    s = "".join(s.split()).upper()
    i = 0
    while i < len(s) and s[i].isdigit():
        i += 1
    zone = int(s[:i])
    band = s[i]
    col = s[i + 1]
    row = s[i + 2]
    rest = s[i + 3:]
    half = len(rest) // 2
    div = 10 ** (5 - half)
    # Centre of the designated square, not its south-west corner: an MGRS reference names a
    # square, so the centre is the best single point, and it makes decode->encode stable
    # against floating-point noise in the UTM series.
    e_rem = (int(rest[:half]) * div if half else 0) + div / 2.0
    n_rem = (int(rest[half:]) * div if half else 0) + div / 2.0

    col_idx = COL_SETS[(zone - 1) % 3].index(col) + 1
    easting = col_idx * 100000 + e_rem

    row_idx = ROW_LETTERS.index(row)
    if zone % 2 == 0:
        row_idx = (row_idx - 5) % 20
    base = row_idx * 100000 + n_rem
    northern = band >= "N"

    bi = BANDS.index(band)
    lat_lo = bi * 8 - 80
    lat_hi = 84.0 if band == "X" else lat_lo + 8

    # The 100 km row letters repeat every 2 000 000 m. Pick the repeat that lands
    # inside the latitude band the code declares. Exact, and no approximation needed.
    best = None
    for k in range(0, 6):
        cand = base + k * 2000000.0
        if cand > 10000000.0:
            break
        la, lo = utm_to_ll(zone, easting, cand, northern)
        if lat_lo - 0.05 <= la < lat_hi + 0.05:
            return la, lo
        err = min(abs(la - lat_lo), abs(la - lat_hi))
        if best is None or err < best[0]:
            best = (err, la, lo)
    return best[1], best[2]


if __name__ == "__main__":
    z, e, n = ll_to_utm(40.0, -75.0)
    print("zone18 @ (40,-75):", z, round(e, 3), round(n, 3), "(expect 18, 500000.000, ~4428236)")

    for name, (la, lo) in {
        "Eiffel": (48.8584, 2.2945),
        "Washington Mon": (38.8895, -77.0353),
        "Sydney Opera": (-33.8568, 151.2153),
        "Nairobi": (-1.2921, 36.8219),
        "Reykjavik": (64.1466, -21.9426),
        "Ushuaia": (-54.8019, -68.3030),
    }.items():
        m = to_mgrs(la, lo)
        back = from_mgrs(m)
        d = math.hypot((back[0] - la) * 111320, (back[1] - lo) * 111320 * math.cos(math.radians(la)))
        print(f"{name:16s} {m}  round-trip err {d:6.2f} m")

    worst = 0.0
    worst_at = None
    la = -79.0
    while la <= 83.0:
        lo = -179.0
        while lo <= 179.0:
            m = to_mgrs(la, lo)
            b = from_mgrs(m)
            d = math.hypot((b[0] - la) * 111320, (b[1] - lo) * 111320 * math.cos(math.radians(la)))
            if d > worst:
                worst, worst_at = d, (la, lo, m)
            lo += 7.0
        la += 3.0
    print("worst global MGRS round-trip error: %.3f m at %s" % (worst, worst_at))
