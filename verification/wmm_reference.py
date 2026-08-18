"""WMM verification and test-data generation for GpsArrow.

Three jobs, in order:

  1. Port ``core/src/main/kotlin/dev/gpsarrow/core/Wmm.kt`` to Python, line for line, and check
     it against ``pygeomag`` (a port of NOAA's reference geomag70.c) over thousands of random
     points. Agreement validates the Kotlin, because the port is faithful to it.
  2. Report the declination across Morocco and Mauritania, and the error a device using the
     framework model (WMM2020) makes against the current model (WMM2025).
  3. Regenerate ``core/src/test/kotlin/dev/gpsarrow/core/WmmReferenceTest.kt``.

The coefficient files are not in this repo; pygeomag ships them.

    pip install pygeomag --break-system-packages
    python3 verification/wmm_reference.py             # validate and report
    python3 verification/wmm_reference.py --generate  # also rewrite the test

This is what caught the two faults in the August 2026 evaluator - a Legendre normalisation
convention that did not match the recursion it was paired with, and a sign flip on the north
component - which together put the declination about 170 degrees out.
"""

import argparse
import math
import os
import random
import re
import sys

A_SEMI_MAJOR = 6_378.137
RECIPROCAL_FLATTENING = 298.257223563
EARTH_RADIUS = 6_371.2


class Wmm:
    def __init__(self, epoch, name, nmax, g, h, gd, hd):
        self.epoch, self.name, self.nmax = epoch, name, nmax
        self.g, self.h, self.gd, self.hd = g, h, gd, hd
        f = 1.0 / RECIPROCAL_FLATTENING
        self.e2 = f * (2 - f)

    @staticmethod
    def parse(text, nmax=12):
        lines = [l.strip() for l in text.splitlines()]
        lines = [l for l in lines if l and not l.startswith("#")]
        header = re.split(r"\s+", lines[0])
        epoch = float(header[0]); name = header[1]
        z = lambda: [[0.0] * (nmax + 1) for _ in range(nmax + 1)]
        g, h, gd, hd = z(), z(), z(), z()
        rows = 0
        for line in lines[1:]:
            if line.startswith("9999"): break
            t = re.split(r"\s+", line)
            if len(t) < 6: continue
            n, m = int(t[0]), int(t[1])
            if not (1 <= n <= nmax and 0 <= m <= n): continue
            g[n][m], h[n][m] = float(t[2]), float(t[3])
            gd[n][m], hd[n][m] = float(t[4]), float(t[5])
            rows += 1
        if rows < 10: return None

        # (1) Schmidt semi-normalised -> unnormalised, folded into the coefficients.
        snorm = [[0.0] * (nmax + 1) for _ in range(nmax + 1)]
        snorm[0][0] = 1.0
        for n in range(1, nmax + 1):
            snorm[n][0] = snorm[n - 1][0] * float(2 * n - 1) / float(n)
            j = 2.0
            for m in range(0, n + 1):
                if m > 0:
                    flnmj = float((n - m + 1) * j) / float(n + m)
                    snorm[n][m] = snorm[n][m - 1] * math.sqrt(flnmj)
                    j = 1.0
                    h[n][m] *= snorm[n][m]
                    hd[n][m] *= snorm[n][m]
                g[n][m] *= snorm[n][m]
                gd[n][m] *= snorm[n][m]
        return Wmm(epoch, name, nmax, g, h, gd, hd)

    def _legendre(self, sin_phi, cos_phi):
        n_max = self.nmax
        p = [[0.0] * (n_max + 1) for _ in range(n_max + 1)]
        dp = [[0.0] * (n_max + 1) for _ in range(n_max + 1)]
        p[0][0] = 1.0; dp[0][0] = 0.0
        for n in range(1, n_max + 1):
            for m in range(0, n + 1):
                if n == m:
                    p[n][m] = cos_phi * p[n - 1][m - 1]
                    dp[n][m] = cos_phi * dp[n - 1][m - 1] + sin_phi * p[n - 1][m - 1]
                elif n == 1 and m == 0:
                    p[n][m] = sin_phi * p[n - 1][m]
                    dp[n][m] = sin_phi * dp[n - 1][m] - cos_phi * p[n - 1][m]
                else:
                    if n > 1 and m > n - 2:
                        p[n - 2][m] = 0.0
                        dp[n - 2][m] = 0.0
                    k = float((n - 1) ** 2 - m * m) / float((2 * n - 1) * (2 * n - 3))
                    p[n][m] = sin_phi * p[n - 1][m] - k * p[n - 2][m]
                    dp[n][m] = sin_phi * dp[n - 1][m] - cos_phi * p[n - 1][m] - k * dp[n - 2][m]
        # (2) no post-recursion normalisation.
        return p, dp

    def field(self, lat, lon, alt_m, decimal_year):
        """Components named as in geomag70.c (bt/br/bp), so the rotation can be copied verbatim."""
        dt = decimal_year - self.epoch
        phi = math.radians(lat); lam = math.radians(lon); h_km = alt_m / 1000.0
        sin_phi, cos_phi = math.sin(phi), math.cos(phi)
        rc = A_SEMI_MAJOR / math.sqrt(1 - self.e2 * sin_phi * sin_phi)
        p1 = (rc + h_km) * cos_phi
        z1 = (rc * (1 - self.e2) + h_km) * sin_phi
        r = math.sqrt(p1 * p1 + z1 * z1)
        sin_pp, cos_pp = z1 / r, p1 / r
        phi_prime = math.atan2(z1, p1)
        pnm, dpnm = self._legendre(sin_pp, cos_pp)
        cos_m = [math.cos(m * lam) for m in range(self.nmax + 1)]
        sin_m = [math.sin(m * lam) for m in range(self.nmax + 1)]
        bt = br = bp = 0.0
        a_over_r = EARTH_RADIUS / r
        for n in range(1, self.nmax + 1):
            f1 = math.pow(a_over_r, float(n + 2))
            for m in range(0, n + 1):
                gnm = self.g[n][m] + dt * self.gd[n][m]
                hnm = self.h[n][m] + dt * self.hd[n][m]
                t1 = gnm * cos_m[m] + hnm * sin_m[m]
                t2 = gnm * sin_m[m] - hnm * cos_m[m]
                bt -= f1 * t1 * dpnm[n][m]
                br += (n + 1) * f1 * t1 * pnm[n][m]
                bp += m * f1 * t2 * pnm[n][m] / (1e-12 if cos_pp == 0.0 else cos_pp)
        # Geodetic <- geocentric rotation. alpha is the angle between the two verticals.
        alpha = phi - phi_prime
        ca, sa = math.cos(alpha), math.sin(alpha)
        x = -bt * ca - br * sa
        y = bp
        z = bt * sa - br * ca
        horizontal = math.sqrt(x * x + y * y)
        return (math.degrees(math.atan2(y, x)),
                math.degrees(math.atan2(z, horizontal)),
                math.sqrt(horizontal * horizontal + z * z))

    def declination(self, lat, lon, alt_m, decimal_year):
        return self.field(lat, lon, alt_m, decimal_year)[0]


def load(path):
    return Wmm.parse(open(path, encoding="utf-8").read())


def decimal_year_of(year, month, day):
    """Port of Wmm.decimalYearOf."""
    leap = (year % 4 == 0 and year % 100 != 0) or year % 400 == 0
    cumulative = [0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334]
    d = cumulative[min(max(month - 1, 0), 11)] + day
    if leap and month > 2:
        d += 1
    return year + (d - 1) / (366.0 if leap else 365.0)


# Cities and desert interior spanning the deployment area.
REGION_POINTS = [
    ("Tangier", 35.7595, -5.8340, 10.0),
    ("Casablanca", 33.5731, -7.5898, 50.0),
    ("Marrakech", 31.6295, -7.9811, 466.0),
    ("Figuig", 32.1092, -1.2306, 900.0),
    ("Dakhla", 23.6848, -15.9579, 10.0),
    ("Eastern desert", 22.5000, -5.5000, 350.0),
    ("Nouadhibou", 20.9410, -17.0347, 10.0),
    ("Nouakchott", 18.0858, -15.9785, 5.0),
    ("Nema", 16.6089, -7.2568, 240.0),
]

# Elsewhere in the world, to catch a hemisphere or quadrant sign flip that the region
# points alone would miss - they are all within a few degrees of north.
GLOBAL_POINTS = [
    ("London", 51.5074, -0.1278, 11.0),
    ("Sydney", -33.8688, 151.2093, 58.0),
    ("Anchorage", 61.2181, -149.9003, 31.0),
    ("Quito", -0.1807, -78.4678, 2850.0),
]

# lat/lon box covering Morocco, Western Sahara and Mauritania with margin.
BOX = (14.5, 36.0, -17.5, -0.5)

GENERATED_AT = (2026, 8, 18)


def pygeomag_dir():
    import pygeomag
    return os.path.join(os.path.dirname(pygeomag.__file__), "wmm")


def validate(models):
    """Worst declination disagreement against pygeomag, in degrees."""
    from pygeomag import GeoMag
    worst = 0.0
    for year, mine in sorted(models.items()):
        ref = GeoMag(coefficients_file="wmm/WMM_%d.COF" % year)
        random.seed(year)
        for _ in range(4000):
            lat = random.uniform(-89.9, 89.9)
            lon = random.uniform(-180.0, 180.0)
            alt = random.uniform(0.0, 10000.0)
            t = year + random.uniform(0.0, 4.99)
            a = mine.field(lat, lon, alt, t)[0]
            b = ref.calculate(glat=lat, glon=lon, alt=alt / 1000.0, time=t).d
            e = abs(a - b)
            if e > 180.0:
                e = abs(e - 360.0)
            worst = max(worst, e)
    return worst


def report(models, today):
    from pygeomag import GeoMag
    w20, w25 = models[2020], models[2025]
    unc = GeoMag(coefficients_file="wmm/WMM_2025.COF")
    print("Declination across the deployment region at decimal year %.4f" % today)
    print("  D true      = WMM2025, in date until 2030-01")
    print("  D framework = WMM2020, which is what android.hardware.GeomagneticField uses")
    head = "%-18s%8s%9s %9s %12s %8s %9s" % (
        "point", "lat", "lon", "D true", "D framework", "error", "WMM unc")
    print()
    print("  " + head)
    print("  " + "-" * len(head))
    for name, lat, lon, alt in REGION_POINTS:
        d25 = w25.declination(lat, lon, alt, today)
        d20 = w20.declination(lat, lon, alt, today)
        u = unc.calculate(glat=lat, glon=lon, alt=alt / 1000.0,
                          time=today).calculate_uncertainty().d
        print("  %-18s%8.3f%9.3f %8.2f  %11.2f  %7.3f  %8.2f"
              % (name, lat, lon, d25, d20, d25 - d20, u))

    print()
    lat0, lat1, lon0, lon1 = BOX
    for label, t in (("today", today), ("2030-01", 2030.0), ("2032-01", 2032.0)):
        worst, at, n = 0.0, None, 0
        lat = lat0
        while lat <= lat1 + 1e-9:
            lon = lon0
            while lon <= lon1 + 1e-9:
                for alt in (0.0, 4000.0):
                    e = abs(w25.declination(lat, lon, alt, t)
                            - w20.declination(lat, lon, alt, t))
                    n += 1
                    if e > worst:
                        worst, at = e, (round(lat, 2), round(lon, 2), alt)
                lon += 0.25
            lat += 0.25
        print("  worst error in box at %-8s %.4f deg at %s "
              "(%d evaluations, %.1f m lateral per km)"
              % (label, worst, at, n, 1000.0 * math.tan(math.radians(worst))))


def generate(models, today, repo_root):
    from pygeomag import GeoMag
    w25 = models[2025]
    ref = GeoMag(coefficients_file="wmm/WMM_2025.COF")
    cof_path = os.path.join(pygeomag_dir(), "WMM_2025.COF")
    with open(cof_path, encoding="utf-8") as f:
        cof_lines = [l.strip() for l in f if l.strip()]
    for l in cof_lines:
        assert "$" not in l and chr(34) not in l, "coefficient line unsafe in a raw string: " + l
    cof_body = "\n".join(" " * 8 + l for l in cof_lines)

    rows = []
    for name, lat, lon, alt in REGION_POINTS + GLOBAL_POINTS:
        mine = w25.declination(lat, lon, alt, today)
        check = ref.calculate(glat=lat, glon=lon, alt=alt / 1000.0, time=today).d
        assert abs(mine - check) < 1e-8, (name, mine, check)
        rows.append((name, lat, lon, alt, check))
    cases = "\n".join('        Case("%s", %r, %r, %r, %r),' % r for r in rows)

    src = _template().replace("@@CASES@@", cases).replace("@@COF@@", cof_body)
    src = src.replace("@@N_REGION@@", str(len(REGION_POINTS)))
    src = src.replace("@@Y@@", str(GENERATED_AT[0]))
    src = src.replace("@@M@@", str(GENERATED_AT[1]))
    src = src.replace("@@D@@", str(GENERATED_AT[2]))
    dest = os.path.join(repo_root, "core", "src", "test", "kotlin", "dev", "gpsarrow",
                        "core", "WmmReferenceTest.kt")
    with open(dest, "w", encoding="utf-8") as f:
        f.write(src)
    print("wrote %s (%d reference points)" % (dest, len(rows)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--generate", action="store_true",
                    help="rewrite core/.../WmmReferenceTest.kt")
    args = ap.parse_args()

    base = pygeomag_dir()
    models = {y: load(os.path.join(base, "WMM_%d.COF" % y)) for y in (2020, 2025)}
    for y, m in sorted(models.items()):
        print("loaded WMM_%d.COF  name=%r epoch=%s" % (y, m.name, m.epoch))

    worst = validate(models)
    print("cross-check vs pygeomag: worst declination disagreement %.3e deg" % worst)
    if worst > 1e-6:
        print("FAIL: the Kotlin evaluator does not match the reference implementation.")
        return 1
    print("OK: evaluator validated.")
    print()

    today = decimal_year_of(*GENERATED_AT)
    report(models, today)

    if args.generate:
        print()
        generate(models, today,
                 os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    return 0


def _template():
    """The Kotlin test skeleton, kept beside this script so nothing has to nest triple quotes."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "WmmReferenceTest.kt.template")
    with open(path, encoding="utf-8") as f:
        return f.read()


if __name__ == "__main__":
    sys.exit(main())
