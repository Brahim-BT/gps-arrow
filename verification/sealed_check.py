"""Every `when` over a sealed interface or class must handle every subtype, or say `else`.

`exhaustive_check.py` does this for enums. Sealed hierarchies were not covered, and the v1 map
work added three of them at once (DownloadDecision, ResponseVerdict, DownloadOutcome) whose
`when` blocks the Kotlin compiler will reject if a branch is missing. That is exactly the class
of error these scripts exist to catch before CI does.

Two things this gets right that a first attempt got wrong, both of which produced false alarms:

  1. **Qualified branch labels.** Kotlin allows `ArrowOnly ->`, `MapTier.ArrowOnly ->` and
     `is MapTier.Available ->`. Matching only the bare form reported MapEmptyState.kt as missing
     a branch it plainly had.
  2. **Multi-line subjects.** `when (val d = Downloads.decide(\n a = 1,\n b = 2,\n))` cannot be
     found with a regex for the subject expression. Parentheses are matched instead, so the
     three new `when` blocks are actually seen rather than silently skipped — a checker that
     skips what it cannot parse reports clean on everything it failed to read.
"""
import glob
import os
import re
import sys


def strip(source):
    """Remove comments and string bodies so their contents cannot look like code."""
    source = re.sub(r'""".*?"""', '""', source, flags=re.S)
    source = re.sub(r'"(\\.|[^"\\])*"', '""', source)
    source = re.sub(r'/\*.*?\*/', '', source, flags=re.S)
    source = re.sub(r'//[^\n]*', '', source)
    return source


def match_brace(source, start):
    depth = 0
    i = start
    while i < len(source):
        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return len(source)


def match_paren(source, start):
    depth = 0
    i = start
    while i < len(source):
        if source[i] == "(":
            depth += 1
        elif source[i] == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return len(source)


def kt_files(root):
    return sorted(
        p for p in glob.glob(os.path.join(root, "**", "src", "**", "*.kt"), recursive=True)
    )


def sealed_types(files):
    """name -> set of nested subtype names."""
    found = {}
    for path in files:
        source = strip(open(path, encoding="utf-8").read())
        for m in re.finditer(r"sealed\s+(?:interface|class)\s+(\w+)", source):
            brace = source.find("{", m.end())
            if brace < 0 or brace - m.end() > 80:
                continue          # a sealed type with no body block
            body = source[brace:match_brace(source, brace)]
            subs = (
                set(re.findall(r"data\s+class\s+(\w+)", body))
                | set(re.findall(r"data\s+object\s+(\w+)", body))
                | set(re.findall(r"\bobject\s+(\w+)", body))
            )
            if subs:
                found[m.group(1)] = subs
    return found


def when_blocks(source):
    """(subject, body) for every `when (...) { ... }`, found by matching brackets."""
    out = []
    for m in re.finditer(r"\bwhen\s*\(", source):
        open_paren = m.end() - 1
        close_paren = match_paren(source, open_paren)
        brace = source.find("{", close_paren)
        if brace < 0 or brace - close_paren > 4:
            continue              # `when (x)` used without a block
        out.append((source[open_paren:close_paren + 1], source[brace:match_brace(source, brace)]))
    return out


def scan(root):
    files = kt_files(root)
    types = sealed_types(files)
    problems = []
    checked = 0

    for path in files:
        source = strip(open(path, encoding="utf-8").read())
        for _subject, block in when_blocks(source):
            if re.search(r"(?<![\w.])else\s*->", block):
                continue
            for name, subs in types.items():
                handled = {
                    s for s in subs
                    if re.search(rf"(?:^|[\s(|]){name}\.{s}\b\s*(?:->|,)", block, re.M)
                    or re.search(rf"(?:^|[\s(|]){s}\b\s*(?:->|,)", block, re.M)
                }
                # Two or more matches means this `when` is plausibly over this type. One match
                # is not enough: an unrelated `when` mentioning a single name would trip it.
                if len(handled) < 2:
                    continue
                checked += 1
                missing = subs - handled
                if missing:
                    line = source[:source.index(block)].count("\n") + 1
                    problems.append((os.path.relpath(path, root), line, name, sorted(missing)))
    return problems, checked, types


if __name__ == "__main__":
    root = sys.argv[1] if len(sys.argv) > 1 else \
        os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    problems, checked, types = scan(root)
    if not types:
        sys.exit(f"no sealed types found under {root} — refusing to report success on an empty scan")
    if problems:
        print(f"{len(problems)} non-exhaustive `when` over a sealed type:")
        for f, line, name, missing in problems:
            print(f"  {f}:{line}  over {name}, no else, missing: {', '.join(missing)}")
    else:
        print(f"Every `when` over a sealed type is exhaustive "
              f"({checked} checked across {len(types)} sealed types).")
    sys.exit(1 if problems else 0)
