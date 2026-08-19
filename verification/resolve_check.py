"""Does every capitalised identifier in this file resolve to anything at all?

Section 2b answered a narrower question than it appeared to: "is this *declared* symbol
reachable from where it is used?". It indexed only top-level FUNCTIONS, and — the deeper flaw —
it could only ever consider names that exist somewhere in the project. A reference to a symbol
declared nowhere was never a key in its index, so the loop that would have flagged it never ran.
That is exactly the shape of the run #11 failure: an enum was deleted, and eight references to it
became invisible to a check whose whole purpose was to see them.

This inverts the question. It relies on the project having no wildcard imports, which is
verified separately: every external symbol must therefore be named in an explicit import, so a
capitalised identifier that is not same-file-declared, not same-package-declared, not imported,
and not a Kotlin built-in cannot resolve, full stop.
"""
import os, re, sys

# Kotlin's default imports, plus the handful of platform types used unqualified.
BUILTIN = set("""
Any Nothing Unit Boolean Byte Short Int Long Float Double Char String CharSequence
Array List MutableList Map MutableMap Set MutableSet Collection Iterable Sequence
IntArray LongArray FloatArray DoubleArray BooleanArray CharArray ByteArray ShortArray
Pair Triple Comparable Number Enum Throwable Exception RuntimeException Error
IllegalArgumentException IllegalStateException NumberFormatException
StringBuilder Regex RegexOption MatchResult Result Lazy Function
Math System Thread Runnable Object Class Void Character Integer
Suppress Deprecated Volatile JvmStatic JvmField JvmOverloads Throws OptIn
SuppressLint Composable ReadOnlyComposable StringRes DrawableRes Stable Immutable
R BuildConfig
""".split())

# Every kind of declaration a file can make, at any nesting depth. The previous check only
# collected top-level FUNCTIONS, which is why a deleted enum was invisible to it.
DECL_PATTERNS = [
    re.compile(r"\b(?:class|object|interface)\s+(\w+)"),
    re.compile(r"\btypealias\s+(\w+)"),
    # Composable functions are capitalised, so they show up in usage position too.
    re.compile(r"\bfun\s+(?:<[^>]+>\s+)?(?:[\w.<>?]+\.)?(\w+)\s*\("),
    re.compile(r"\b(?:const\s+)?va[lr]\s+(\w+)"),
]
# A capitalised identifier in a usage position: the head of a dotted chain, a constructor call,
# a type annotation, an `is` check or a generic argument. Never preceded by a dot.
USE = re.compile(r"(?<![\w.])([A-Z][A-Za-z0-9_]*)")

def strip(src):
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    src = re.sub(r'"""(?:[^"]|"(?!""))*"""', '""', src)
    src = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', src)
    # Backtick-quoted identifiers. This project names its tests in English prose that way, and
    # the words inside are not code: "non-Latin" would otherwise look like a reference to a
    # type called Latin.
    src = re.sub(r"`[^`\n]*`", "`x`", src)
    return src

def scan(root):
    files = []
    for base in ("app", "core"):
        for d, _, fs in os.walk(os.path.join(root, base)):
            if "/build/" in d.replace(os.sep, "/"):
                continue
            files += [os.path.join(d, f) for f in fs if f.endswith(".kt")]

    info = {}
    for p in files:
        raw = open(p, encoding="utf-8").read()
        body = strip(raw)
        pkg = re.search(r"^package\s+([\w.]+)", raw, re.M)
        imports = set()
        for m in re.finditer(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?$", raw, re.M):
            imports.add(m.group(2) or m.group(1).rsplit(".", 1)[-1])
        info[p] = dict(
            pkg=pkg.group(1) if pkg else "",
            body=body,
            imports=imports,
            declares={m.group(1) for pat in DECL_PATTERNS for m in pat.finditer(body)},
        )

    by_pkg = {}
    for p, i in info.items():
        by_pkg.setdefault(i["pkg"], set()).update(i["declares"])

    problems = []
    for p, i in sorted(info.items()):
        visible = i["declares"] | i["imports"] | by_pkg.get(i["pkg"], set()) | BUILTIN
        seen = set()
        for m in USE.finditer(i["body"]):
            name = m.group(1)
            if name in visible or name in seen:
                continue
            if len(name) <= 2 and name.isupper():
                continue                      # type parameters: T, R, K, V
            if name.isupper() or (name.isupper() and "_" in name):
                continue                      # SCREAMING_CASE constants
            if re.fullmatch(r"[A-Z][A-Z0-9_]*", name):
                continue                      # constants again, defensively
            seen.add(name)
            line = i["body"][:m.start()].count("\n") + 1
            problems.append((os.path.relpath(p, root), line, name))
    return problems, len(files)

if __name__ == "__main__":
    # Default to the repo root, not the cwd. Defaulting to "." meant running this from the
    # directory it lives in scanned zero Kotlin files and printed a green line — a check that
    # cannot fail, which is worse than no check because it gets counted as one.
    root = sys.argv[1] if len(sys.argv) > 1 else \
        os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    problems, n = scan(root)
    if n == 0:
        sys.exit(f"no Kotlin files under {root} — refusing to report success on an empty scan")
    if problems:
        print(f"{len(problems)} unresolvable identifier(s) across {n} files:")
        for f, line, name in problems:
            print(f"  {f}:{line}  '{name}' resolves to nothing")
    else:
        print(f"Every capitalised identifier resolves ({n} files).")
    sys.exit(1 if problems else 0)
