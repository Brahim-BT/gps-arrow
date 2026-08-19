"""Does every `when` over a project enum handle every constant, or say `else`?

Not the cause of run #11 — that error was downstream of a deleted enum — but a real and
recurring hazard in this project. Adding ArrowMode.ARRIVED meant updating a `when` in another
file, and nothing but the compiler would have noticed if it had been missed. Enums here gain
constants often: ArrowMode, HeadingSource, ParseProblem, CoordinateFormat, SpeedUnit, Tone.

Heuristic, deliberately: it looks for `when` blocks that mention a project enum's constants and
flags any that mention some but not all and have no `else`. Kotlin requires exhaustiveness for
both expressions and statements over enums, so a proper subset with no `else` is always an error.
"""
import os, re, sys

def strip(src):
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    src = re.sub(r'"""(?:[^"]|"(?!""))*"""', '""', src)
    src = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', src)
    src = re.sub(r"`[^`\n]*`", "`x`", src)
    return src

def kt_files(root):
    for base in ("app", "core"):
        for d, _, fs in os.walk(os.path.join(root, base)):
            if "/build/" in d.replace(os.sep, "/"):
                continue
            for f in fs:
                if f.endswith(".kt"):
                    yield os.path.join(d, f)

ENUM_HEAD = re.compile(r"\benum\s+class\s+(\w+)\s*(?:\([^)]*\))?\s*\{")

def brace_block(body, open_index):
    """Text between `{` at open_index and its matching `}`."""
    depth, j = 0, open_index
    while j < len(body):
        if body[j] == "{":
            depth += 1
        elif body[j] == "}":
            depth -= 1
            if depth == 0:
                return body[open_index + 1:j]
        j += 1
    return body[open_index + 1:]

def enums(root):
    """Every project enum and its constants.

    Brace-matched rather than regex-terminated: the first version required a newline before the
    closing brace, so it silently skipped every single-line enum — which included Tone and
    LengthUnit, the two most relevant to the failure it was written for. A checker with a blind
    spot in the shape of the bug is worse than none.
    """
    found = {}
    for p in kt_files(root):
        body = strip(open(p, encoding="utf-8").read())
        for m in ENUM_HEAD.finditer(body):
            name = m.group(1)
            block = brace_block(body, body.index("{", m.end() - 1))
            # Constants come before the first `;` in an enum that also declares members.
            head = block.split(";", 1)[0]
            consts = {t for t in re.findall(r"(?<![\w.])([A-Z][A-Z0-9_]*)\s*(?=[,(\n}])", head)}
            if len(consts) >= 2:
                found[name] = consts
    return found

def when_blocks(body):
    """Yield (start_index, text) for each `when ... { ... }`."""
    for m in re.finditer(r"\bwhen\b\s*(?:\([^{]*\))?\s*\{", body):
        i = body.index("{", m.start())
        depth, j = 0, i
        while j < len(body):
            if body[j] == "{":
                depth += 1
            elif body[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        yield m.start(), body[i:j + 1]

# A whole branch-label list before a `->`. Kotlin allows several constants per branch
# (`ARRIVED, NORTH ->`), and capturing only the token adjacent to the arrow lost the rest —
# which made a correctly exhaustive `when` look like it was missing a case.
LABEL_LIST = re.compile(r"(?<![\w.])((?:[\w.]+\s*,\s*)*[\w.]+)\s*->")

def branch_labels(block):
    """(qualifier, CONSTANT) for every constant named in a branch label."""
    out = []
    for m in LABEL_LIST.finditer(block):
        for token in m.group(1).split(","):
            token = token.strip()
            qualifier, _, const = token.rpartition(".")
            if re.fullmatch(r"[A-Z][A-Z0-9_]*", const):
                out.append((qualifier, const))
    return out

def scan(root):
    table = enums(root)
    # Which file each enum was declared in, to break ties between enums sharing constant names.
    home = {}
    for p in kt_files(root):
        body = strip(open(p, encoding="utf-8").read())
        for m in ENUM_HEAD.finditer(body):
            if m.group(1) in table:
                home[m.group(1)] = p

    problems = []
    for p in kt_files(root):
        body = strip(open(p, encoding="utf-8").read())
        for start, block in when_blocks(body):
            if re.search(r"(?<![\w.])else\s*->", block):
                continue
            labels = branch_labels(block)
            if not labels:
                continue

            # Qualified labels name their enum outright. The qualifier may itself be dotted for
            # a nested enum (ParseResult.Format.DECIMAL), so match on its last segment, and
            # require a word boundary so `Format` does not match inside `CoordinateFormat`.
            for name, consts in table.items():
                used = {c for q, c in labels if q.split(".")[-1] == name and c in consts}
                if used and used != consts:
                    problems.append((os.path.relpath(p, root),
                                     body[:start].count("\n") + 1, name,
                                     ", ".join(sorted(consts - used))))

            # Unqualified labels: inside an enum's own members the constants are in scope, which
            # is how CoordinateFormat.render is written. Infer the enum, preferring one declared
            # in this same file when several share these constant names; skip if still ambiguous,
            # because a wrong guess here is a false alarm and those are what get checks ignored.
            bare = {c for q, c in labels if q == ""}
            if not bare:
                continue
            candidates = [n for n, cs in table.items() if bare <= cs]
            if len(candidates) > 1:
                same_file = [n for n in candidates if home.get(n) == p]
                candidates = same_file if len(same_file) == 1 else []
            if len(candidates) == 1:
                name = candidates[0]
                consts = table[name]
                if bare != consts:
                    problems.append((os.path.relpath(p, root),
                                     body[:start].count("\n") + 1, name,
                                     ", ".join(sorted(consts - bare))))
    return problems, table

if __name__ == "__main__":
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    problems, table = scan(root)
    if problems:
        print(f"{len(problems)} non-exhaustive `when`:")
        for f, line, name, missing in problems:
            print(f"  {f}:{line}  over {name}, no else, missing: {missing}")
    else:
        print(f"Every `when` over a project enum is exhaustive "
              f"({len(table)} enums: {', '.join(sorted(table))}).")
    sys.exit(1 if problems else 0)
