# -*- coding: utf-8 -*-
import os, re, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from strings_table import STRINGS, COMPASS_POINTS

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    "app", "src", "main", "res")

def esc(s):
    """Android strings.xml escaping."""
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = s.replace("'", "\\'").replace('"', '\\"')
    return s

HEADER = """<?xml version="1.0" encoding="utf-8"?>
<!--
  {note}

  Generated from verification/strings_table.py so the three languages stay key-for-key
  identical. Edit the table and regenerate rather than editing one file by hand: a key that
  exists in one language and not another is exactly the bug that ships a half-translated app.
-->
<resources>
"""

NOTES = {
    "": "English source strings.",
    "-fr": "French. Maghreb-neutral register, vouvoiement throughout.",
    "-ar": "Modern Standard Arabic, kept short for a utility read while walking.",
}
IDX = {"": 1, "-fr": 2, "-ar": 3}

for suffix, note in NOTES.items():
    out = [HEADER.format(note=note)]
    for row in STRINGS:
        if row[0] == "SECTION":
            out.append(f"\n    <!-- {row[1]} -->\n")
            continue
        key = row[0]
        value = row[IDX[suffix]]
        out.append(f'    <string name="{key}">{esc(value)}</string>\n')
    lang = {"": "en", "-fr": "fr", "-ar": "ar"}[suffix]
    out.append("\n    <!-- Sixteen-point compass rose, N first, clockwise. -->\n")
    out.append('    <string-array name="compass_points">\n')
    for p in COMPASS_POINTS[lang]:
        out.append(f"        <item>{esc(p)}</item>\n")
    out.append("    </string-array>\n")
    out.append("</resources>\n")

    d = os.path.join(ROOT, f"values{suffix}")
    os.makedirs(d, exist_ok=True)
    path = os.path.join(d, "strings.xml")
    with open(path, "w", encoding="utf-8") as f:
        f.write("".join(out))
    print(f"wrote {path}")

# Verify all three parse and agree.
import xml.etree.ElementTree as ET
sets = {}
for suffix in NOTES:
    t = ET.parse(os.path.join(ROOT, f"values{suffix}", "strings.xml"))
    r = t.getroot()
    names = [e.get("name") for e in r.findall("string")]
    arrays = {a.get("name"): len(a.findall("item")) for a in r.findall("string-array")}
    sets[suffix or "en"] = (names, arrays)
    assert len(names) == len(set(names)), f"duplicate keys in values{suffix}"
base = sets["en"][0]
for k, (names, arrays) in sets.items():
    assert names == base, f"{k} key set differs"
    assert arrays == {"compass_points": 16}, f"{k} arrays {arrays}"
print(f"\nOK: 3 files x {len(base)} strings + a 16-item array, identical key sets, all well-formed XML")

# Format-specifier agreement: a translation with the wrong arg count crashes at runtime.
def specs(s):
    return sorted(re.findall(r"%(\d+\$)?[sd]", s))
bad = []
for row in STRINGS:
    if row[0] == "SECTION": continue
    en, fr, ar = row[1], row[2], row[3]
    if not (specs(en) == specs(fr) == specs(ar)):
        bad.append((row[0], specs(en), specs(fr), specs(ar)))
print("format-specifier mismatches:", bad or "none")
