"""Reference prototype of the Open Location Code (plus code) implementation in :core."""
import math
import random

ALPHABET = "23456789CFGHJMPQRVWX"
SEP = "+"
SEP_POS = 8
PAD = "0"
PAIR_LEN = 10
MAX_DIGITS = 15
GRID_ROWS = 4
GRID_COLS = 5
PAIR_PRECISION = 8000
FINAL_LAT_PRECISION = PAIR_PRECISION * GRID_ROWS ** (MAX_DIGITS - PAIR_LEN)   # 8_192_000
FINAL_LNG_PRECISION = PAIR_PRECISION * GRID_COLS ** (MAX_DIGITS - PAIR_LEN)   # 25_000_000


def clip_lat(x):
    return min(90.0, max(-90.0, x))


def norm_lng(x):
    while x < -180:
        x += 360
    while x >= 180:
        x -= 360
    return x


def precision_for(code_len):
    if code_len <= PAIR_LEN:
        return 20.0 ** (math.floor(code_len / -2 + 2))
    return (20.0 ** -3) / (GRID_ROWS ** (code_len - PAIR_LEN))


def encode(lat, lng, code_len=PAIR_LEN):
    if code_len < 2 or (code_len < PAIR_LEN and code_len % 2 == 1):
        raise ValueError("invalid code length")
    code_len = min(code_len, MAX_DIGITS)
    lat = clip_lat(lat)
    lng = norm_lng(lng)
    if lat == 90:
        lat = lat - precision_for(code_len)

    lat_val = int(math.floor(round((lat + 90.0) * FINAL_LAT_PRECISION, 6)))
    lng_val = int(math.floor(round((lng + 180.0) * FINAL_LNG_PRECISION, 6)))
    lat_val = min(lat_val, 180 * FINAL_LAT_PRECISION - 1)
    lng_val = min(lng_val, 360 * FINAL_LNG_PRECISION - 1)

    code = ""
    if code_len > PAIR_LEN:
        for _ in range(MAX_DIGITS - PAIR_LEN):
            code = ALPHABET[(lat_val % GRID_ROWS) * GRID_COLS + (lng_val % GRID_COLS)] + code
            lat_val //= GRID_ROWS
            lng_val //= GRID_COLS
    else:
        lat_val //= GRID_ROWS ** (MAX_DIGITS - PAIR_LEN)
        lng_val //= GRID_COLS ** (MAX_DIGITS - PAIR_LEN)

    for _ in range(PAIR_LEN // 2):
        code = ALPHABET[lng_val % 20] + code
        code = ALPHABET[lat_val % 20] + code
        lat_val //= 20
        lng_val //= 20

    code = code[:SEP_POS] + SEP + code[SEP_POS:]
    if code_len >= SEP_POS:
        return code[: code_len + 1]
    return code[:code_len] + PAD * (SEP_POS - code_len) + SEP


PAIR_FIRST_PLACE = 20 ** (PAIR_LEN // 2 - 1)          # 160_000
GRID_LAT_FIRST_PLACE = GRID_ROWS ** (MAX_DIGITS - PAIR_LEN - 1)   # 256
GRID_LNG_FIRST_PLACE = GRID_COLS ** (MAX_DIGITS - PAIR_LEN - 1)   # 625


def decode(code):
    """Returns (lat_lo, lng_lo, lat_hi, lng_hi)."""
    clean = code.replace(SEP, "")
    if PAD in clean:
        clean = clean[: clean.index(PAD)]
    clean = clean[:MAX_DIGITS]

    normal_lat = -90 * PAIR_PRECISION
    normal_lng = -180 * PAIR_PRECISION
    grid_lat = 0
    grid_lng = 0

    digits = min(len(clean), PAIR_LEN)
    pv = PAIR_FIRST_PLACE
    for i in range(0, digits, 2):
        normal_lat += ALPHABET.index(clean[i]) * pv
        normal_lng += ALPHABET.index(clean[i + 1]) * pv
        if i < digits - 2:
            pv //= 20
    lat_precision = pv / PAIR_PRECISION
    lng_precision = pv / PAIR_PRECISION

    if len(clean) > PAIR_LEN:
        rowpv = GRID_LAT_FIRST_PLACE
        colpv = GRID_LNG_FIRST_PLACE
        digits = min(len(clean), MAX_DIGITS)
        for i in range(PAIR_LEN, digits):
            d = ALPHABET.index(clean[i])
            grid_lat += (d // GRID_COLS) * rowpv
            grid_lng += (d % GRID_COLS) * colpv
            if i < digits - 1:
                rowpv //= GRID_ROWS
                colpv //= GRID_COLS
        lat_precision = rowpv / FINAL_LAT_PRECISION
        lng_precision = colpv / FINAL_LNG_PRECISION

    lat = normal_lat / PAIR_PRECISION + grid_lat / FINAL_LAT_PRECISION
    lng = normal_lng / PAIR_PRECISION + grid_lng / FINAL_LNG_PRECISION
    return (lat, lng, lat + lat_precision, lng + lng_precision)


def center(code):
    a, b, c, d = decode(code)
    return ((a + c) / 2, (b + d) / 2)


if __name__ == "__main__":
    # Vectors from the Open Location Code reference test data.
    known = {
        (47.0000625, 8.0000625): "8FVC2222+22",
        (-41.2730625, 174.7859375): "4VCPPQGP+Q9",
    }
    for (la, lo), expect in known.items():
        got = encode(la, lo, len(expect.replace("+", "")))
        print(f"encode({la}, {lo}) -> {got}   expect {expect}   {'OK' if got == expect else 'MISMATCH'}")

    random.seed(7)
    worst = 0
    bad = 0
    for _ in range(20000):
        la = random.uniform(-89.9, 89.9)
        lo = random.uniform(-179.9, 179.9)
        for n in (10, 11, 12, 15):
            c = encode(la, lo, n)
            lo_lat, lo_lng, hi_lat, hi_lng = decode(c)
            if not (lo_lat <= la <= hi_lat and lo_lng <= lo <= hi_lng):
                bad += 1
            cy, cx = center(c)
            worst = max(worst, abs(cy - la), abs(cx - lo))
    print("containment failures:", bad, " worst centre offset (deg):", round(worst, 8))

    # Grid-section check: the 20 possible 11th characters must exactly tile the
    # parent 10-digit cell as 4 latitude rows x 5 longitude columns, no gaps, no overlap.
    base = encode(48.8584, 2.2945, 10)
    p_lo_lat, p_lo_lng, p_hi_lat, p_hi_lng = decode(base)
    cells = [decode(base + ALPHABET[d]) for d in range(20)]
    lat_edges = sorted({round(c[0], 12) for c in cells})
    lng_edges = sorted({round(c[1], 12) for c in cells})
    area = sum((c[2] - c[0]) * (c[3] - c[1]) for c in cells)
    parent_area = (p_hi_lat - p_lo_lat) * (p_hi_lng - p_lo_lng)
    print(
        "grid tiling: lat rows =", len(lat_edges),
        " lng cols =", len(lng_edges),
        " area match =", abs(area - parent_area) < 1e-18,
    )

    # Short-code recovery around a reference point.
    full = encode(48.8584, 2.2945)
    print("full:", full, "-> short form used in the app:", full[4:])
