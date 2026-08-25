#!/usr/bin/env python3
"""Runs the cleanup workflow's `run:` blocks against a FAKE database and checks what they did.

WHAT THIS PROVES. The shell in `.github/workflows/shared-points-cleanup.yml` is extracted
verbatim and executed with a stand-in `curl` on PATH, backed by a JSON file addressed by path
the way the Realtime Database REST API addresses its tree. So it proves the shell logic: which
branch is taken, which paths are written, which are deleted, and in what order.

WHAT IT DOES NOT PROVE. Nothing about the live drain. Not the security rules, not the service
account's permissions, not that Firebase's PATCH and DELETE behave as the fake does, not the
schedule, not that `DB:` was ever pointed at a real database. A green run here and a broken
production drain are entirely compatible. The real check is still the curl smoke test in
SETUP_SHARED_POINTS.md, run against the actual database, reading the workflow's log.

WHY IT EXISTS ANYWAY. The workflow is the only thing that can apply an edit or honour a
withdrawal, and its worst failure is silent: an edit that removes a note is applied with a
PATCH, and a PATCH that omits a key leaves the old value. Get that wrong and "remove my note"
becomes the one edit that does nothing while the app reports it as sent. That is the case this
exists for; the other four are the refusal paths that must not quietly become permissive.

Verified by breaking the workflow and watching this go red — see verification/README.md.
Pass a path as argv[1] to run it against a different (e.g. deliberately broken) workflow file.
"""
import json, os, re, subprocess, sys, tempfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if not os.path.exists(os.path.join(REPO, "settings.gradle.kts")):
    sys.exit(f"no settings.gradle.kts under {REPO} — this is not the repo root")
WORKFLOW = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    REPO, ".github/workflows/shared-points-cleanup.yml")

CURL = '''#!/usr/bin/env python3
import json, os, sys
DB, a = os.environ["FAKE_DB"], sys.argv[1:]
method, data, url, i = "GET", "", "", 0
while i < len(a):
    if a[i] == "-X": i += 1; method = a[i]
    elif a[i] == "-d": i += 1; data = a[i]
    elif a[i] == "-H": i += 1
    elif not a[i].startswith("-"): url = a[i]
    i += 1
path = url.split(".app/", 1)[-1]
parts = [p for p in (path[:-5] if path.endswith(".json") else path).split("/") if p]
root = json.load(open(DB)) if os.path.exists(DB) else {}
node = root
for p in parts[:-1]:
    node = node.setdefault(p, {}) if method in ("PUT", "PATCH") else node.get(p) or {}
key = parts[-1] if parts else None
if method == "GET":
    v = root if key is None else node.get(key)
    print(json.dumps(v) if v is not None else "null")
else:
    if method == "PUT": node[key] = json.loads(data)
    elif method == "PATCH": node.setdefault(key, {}).update(json.loads(data))
    elif method == "DELETE": node.pop(key, None)
    json.dump(root, open(DB, "w"))
'''


def run_blocks(path):
    """The named `run: |` scripts, without a YAML parser — this repo ships no dependencies."""
    blocks, name, buf, indent = {}, None, None, 0
    for line in open(path, encoding="utf-8"):
        if buf is not None:
            if line.strip() and len(line) - len(line.lstrip()) <= indent:
                blocks[name], buf = "".join(buf), None
            else:
                buf.append(line[indent + 2:] if line.strip() else "\n")
                continue
        m = re.match(r"\s*- name: (.+?)\s*$", line)
        if m: name = m.group(1)
        m = re.match(r"(\s*)run: \|\s*$", line)
        if m: indent, buf = len(m.group(1)), []
    if buf is not None: blocks[name] = "".join(buf)
    return blocks


TOK = "a" * 64
DIG = __import__("hashlib").sha256(TOK.encode()).hexdigest()
BAD = "b" * 64
POINT = {"name": "Test", "lat": 33.57, "lon": -7.59, "createdAt": 1700000000000, "note": "bring rope"}

# (case, step, starting tree, what must be true afterwards)
CASES = [
    ("an edit removes a note rather than leaving it", "Apply pending edits",
     {"sharedPoints": {"p": dict(POINT)}, "owners": {"p": DIG},
      "pendingEdits": {"p": {"t": TOK, "name": "Test", "lat": 33.57, "lon": -7.59, "note": None}}},
     lambda d: "note" not in d["sharedPoints"]["p"]
     and d["sharedPoints"]["p"]["createdAt"] == 1700000000000 and not d.get("pendingEdits")),

    ("an edit with the wrong token changes nothing and frees the slot", "Apply pending edits",
     {"sharedPoints": {"p": dict(POINT)}, "owners": {"p": DIG},
      "pendingEdits": {"p": {"t": BAD, "name": "Moved", "lat": 33.64, "lon": -7.59, "note": None}}},
     lambda d: d["sharedPoints"]["p"] == POINT and not d.get("pendingEdits")),

    ("an edit to a moderated-away point does not resurrect it", "Apply pending edits",
     {"owners": {"p": DIG},
      "pendingEdits": {"p": {"t": TOK, "name": "Back", "lat": 1, "lon": 2, "note": None}}},
     lambda d: not d.get("sharedPoints") and not d.get("pendingEdits")),

    ("an honest withdrawal clears all four nodes", "Drain tombstones",
     {"sharedPoints": {"p": dict(POINT)}, "owners": {"p": DIG}, "tombstones": {"p": TOK}},
     lambda d: not any(d.get(n) for n in
                       ("sharedPoints", "owners", "tombstones", "pendingEdits"))),

    ("a withdrawal with the wrong token keeps the point", "Drain tombstones",
     {"sharedPoints": {"p": dict(POINT)}, "owners": {"p": DIG}, "tombstones": {"p": BAD}},
     lambda d: d["sharedPoints"]["p"] == POINT and not d.get("tombstones")),
]


def main():
    blocks = run_blocks(WORKFLOW)
    needed = {step for _, step, _, _ in CASES}
    missing = needed - set(blocks)
    if missing:
        sys.exit(f"refusing to report success: no `run:` block named {sorted(missing)} "
                 f"in {WORKFLOW} (found {sorted(blocks)})")

    with tempfile.TemporaryDirectory() as tmp:
        curl = os.path.join(tmp, "curl")
        open(curl, "w").write(CURL)
        os.chmod(curl, 0o755)
        env = dict(os.environ, PATH=tmp + os.pathsep + os.environ["PATH"],
                   DB="https://fake-default-rtdb.test.firebasedatabase.app", TOKEN="fake")
        failed = []
        for case, step, tree, expected in CASES:
            db = os.path.join(tmp, "db.json")
            json.dump(tree, open(db, "w"))
            env["FAKE_DB"] = db
            p = subprocess.run(["bash", "-c", blocks[step]], env=env,
                               capture_output=True, text=True)
            after = json.load(open(db))
            if p.returncode != 0:
                failed.append(f"{case}: the step exited {p.returncode}\n{p.stderr.strip()}")
                continue
            # A check that raises is a FAILING case, not a broken harness. A wrong workflow
            # can delete a node the check then looks for, and a traceback there would send
            # whoever sees it to debug this file instead of the one that is wrong.
            try:
                ok = expected(after)
            except Exception as e:
                ok, case = False, f"{case} [{type(e).__name__}: {e}]"
            if not ok:
                failed.append(f"{case}: tree afterwards was {json.dumps(after)}")

    for f in failed:
        print("FAIL " + f)
    if failed:
        sys.exit(f"\n{len(failed)} of {len(CASES)} workflow cases failed.")
    print(f"All {len(CASES)} workflow drain cases pass "
          f"({len(blocks)} `run:` blocks read from {os.path.relpath(WORKFLOW, REPO)}).")
    print("This is the shell logic only — see the module docstring for what it does not prove.")


main()
