#!/usr/bin/env python3
"""Cross-checks the design documents against each other.

Usage: python3 .github/scripts/check_docs.py [repository root]

Reads docs/01-planning/requirements.md, docs/04-spec/record-rules-supplement.md,
docs/05-ui/sitemap.yaml, docs/05-ui/screens.md, docs/08-wbs/wbs.md and every Markdown file
under docs/ plus README.md. Standard library only. Prints the counts, then every problem it
found, and exits with 1 when there is a problem. It changes nothing.

Starts from docs/06-review/evidence-2026-11-11/check-docs.py (design review, 2026-11-11) and
adds what the 2026-11-12 scope confirmation needs: counterexamples RR-26 onward, one owner per
task, the six standard states per screen, state references, relative links and anchors, and
table shapes.
"""
import json
import re
import sys
from collections import Counter
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
problems = []


def problem(message):
    problems.append(message)


def read(rel):
    return (root / rel).read_text(encoding="utf-8")


def strip_fences(text):
    """Blank out fenced code blocks so their lines are not read as tables or links."""
    out, fenced = [], False
    for line in text.splitlines():
        if line.lstrip().startswith("```"):
            fenced = not fenced
            out.append("")
            continue
        out.append("" if fenced else line)
    return out


def cells(line):
    """Split a Markdown table row the way GitHub does: on every pipe that is not escaped."""
    s = line.strip()
    s = s[1:] if s.startswith("|") else s
    s = s[:-1] if s.endswith("|") and not s.endswith("\\|") else s
    return [c.strip() for c in re.split(r"(?<!\\)\|", s)]


def rr_refs(text):
    """RR numbers in a text: 'RR-3', 'RR-38~43', 'RR-30~32', 'RR-51·52', 'RR-60·RR-62'."""
    found = set()
    for m in re.finditer(r"RR-(\d+)((?:\s*[~·]\s*(?:RR-)?\d+)*)", text):
        prev = int(m.group(1))
        found.add(prev)
        for sep, n in re.findall(r"\s*([~·])\s*(?:RR-)?(\d+)", m.group(2)):
            n = int(n)
            found.update(range(prev, n + 1) if sep == "~" else {n})
            prev = n
    return found


def task_refs(text):
    found = set()
    for a, b in re.findall(r"TASK-(\d{3})~TASK-(\d{3})", text):
        found |= {f"TASK-{n:03d}" for n in range(int(a), int(b) + 1)}
    return found | set(re.findall(r"TASK-\d{3}", text))


# --- requirements ---------------------------------------------------------------------------
req_md = read("docs/01-planning/requirements.md")
rows = {}
for line in req_md.splitlines():
    m = re.match(r"\|\s*(REQ-\d{3})\s*\|", line)
    if m:
        c = cells(line)
        if m.group(1) in rows:
            problem(f"requirements: duplicate {m.group(1)}")
        if len(c) != 7:
            problem(f"requirements: {m.group(1)} has {len(c)} cells, expected 7")
        rows[m.group(1)] = c
ids = sorted(rows)
nums = [int(i[4:]) for i in ids]
missing = sorted(set(range(1, max(nums) + 1)) - set(nums))
print(f"requirements: {len(ids)} rows, {ids[0]}..{ids[-1]}, missing numbers: {missing or 'none'}")
if missing:
    problem(f"requirements: missing numbers {missing}")
kinds = Counter(r[2] for r in rows.values())
priorities = Counter(r[3] for r in rows.values())
print("  kind:", dict(kinds))
print("  priority:", dict(priorities))
for rid, r in rows.items():
    if r[2] not in {"기능", "비기능"}:
        problem(f"requirements: {rid} kind '{r[2]}'")
    if not re.match(r"(Must|Should|Could)\b", r[3]):
        problem(f"requirements: {rid} priority '{r[3]}'")
empty = [i for i, r in rows.items() if not all(r[k] for k in range(1, min(len(r), 7)))]
print("  rows with an empty cell:", empty or "none")
if empty:
    problem(f"requirements: empty cells in {empty}")
functional = {i for i, r in rows.items() if r[2] == "기능"}
must = {i for i, r in rows.items() if r[3].startswith("Must")}

# --- counterexamples ------------------------------------------------------------------------
supplement = read("docs/04-spec/record-rules-supplement.md")
defined_rr = sorted({int(m) for m in re.findall(r"^\|\s*(\d+)\s*\|", supplement, re.M)})
expected_rr = list(range(26, defined_rr[-1] + 1)) if defined_rr else []
print(f"supplement: counterexamples RR-{defined_rr[0]}..RR-{defined_rr[-1]}, {len(defined_rr)} rows")
if defined_rr != expected_rr:
    problem(f"supplement: counterexample numbers are not RR-26.. without gaps: {defined_rr}")
rr_in_reqs = set().union(*(rr_refs(r[5]) for r in rows.values()))
original_unlinked = sorted(set(range(1, 26)) - rr_in_reqs)
supplement_unlinked = sorted(set(defined_rr) - rr_in_reqs)
print(f"  RR-1..25 not linked from acceptance criteria: {original_unlinked}")
print(f"  RR-26..{defined_rr[-1]} not linked from acceptance criteria: {supplement_unlinked or 'none'}")
unknown_rr = sorted(rr_in_reqs - set(range(1, defined_rr[-1] + 1)))
if unknown_rr:
    problem(f"requirements: unknown counterexamples {unknown_rr}")
rule_ids = set(re.findall(r"\*\*([A-Z]-\d+)", supplement))
print(f"  rule ids: {len(rule_ids)} ({', '.join(sorted(rule_ids, key=lambda s: (s[0], int(s[2:]))))})")

# --- sitemap and screens --------------------------------------------------------------------
sitemap = read("docs/05-ui/sitemap.yaml")
screens = read("docs/05-ui/screens.md")
scr = re.findall(r"- id: (SCR-\d{3})", sitemap)
paths = re.findall(r"path: (\S+)", sitemap)
site_reqs = {}
for block in re.split(r"\n\s*- id: ", "\n" + sitemap)[1:]:
    sid = block.split()[0]
    m = re.search(r"reqs: \[(.*?)\]", block)
    site_reqs[sid] = set(re.findall(r"REQ-\d{3}", m.group(1))) if m else set()
used = set().union(*site_reqs.values())
print(f"sitemap: {len(scr)} screens, duplicate ids: {len(scr) - len(set(scr))}, "
      f"duplicate paths: {len(paths) - len(set(paths))}")
if len(scr) != len(set(scr)) or len(paths) != len(set(paths)):
    problem("sitemap: duplicate ids or paths")
not_used = sorted(functional - used)
print(f"  functional requirements not used by any screen: {not_used or 'none'}")
if not_used:
    problem(f"sitemap: functional requirements without a screen {not_used}")
print(f"  screen reqs that are not functional requirements: "
      f"{sorted(i for i in used if i in rows and i not in functional) or 'none'}")
if used - set(rows):
    problem(f"sitemap: unknown requirement ids {sorted(used - set(rows))}")
sections = re.split(r"\n### (SCR-\d{3}) ", screens)
mismatch = []
for sid, body in zip(sections[1::2], sections[2::2]):
    body = body.split("\n## ")[0]
    in_doc = set(re.findall(r"REQ-\d{3}", body))
    if in_doc != site_reqs.get(sid, set()):
        mismatch.append((sid, sorted(in_doc ^ site_reqs.get(sid, set()))))
print(f"  screens.md sections whose REQ set differs from sitemap.yaml: {mismatch or 'none'}")
if mismatch:
    problem(f"screens: REQ sets differ from sitemap.yaml {mismatch}")

states = {}
for sid, state in re.findall(r"^\|\s*(SCR-\d{3})#([a-z]+)\s*\|", screens, re.M):
    states.setdefault(sid, []).append(state)
standard = ["normal", "loading", "empty", "error", "permission", "stale"]
lacking = {s: [x for x in standard if x not in states.get(s, [])] for s in scr}
lacking = {s: v for s, v in lacking.items() if v}
extra = {s: [x for x in v if x not in standard] for s, v in states.items()}
print(f"  states: {sum(len(v) for v in states.values())} rows; screens lacking a standard state: "
      f"{lacking or 'none'}")
print("  screen-specific states:", {s: v for s, v in extra.items() if v})
if lacking:
    problem(f"screens: standard states missing {lacking}")
dupe_states = {s: [x for x, n in Counter(v).items() if n > 1] for s, v in states.items()}
if any(dupe_states.values()):
    problem(f"screens: duplicate states {dupe_states}")

# --- work breakdown -------------------------------------------------------------------------
wbs = read("docs/08-wbs/wbs.md")
tasks = {}
for line in wbs.splitlines():
    m = re.match(r"\|\s*(TASK-\d{3})\s*\|", line)
    if m:
        c = cells(line)
        if m.group(1) in tasks:
            problem(f"wbs: duplicate {m.group(1)}")
        if len(c) != 8:
            problem(f"wbs: {m.group(1)} has {len(c)} cells, expected 8")
        tasks[m.group(1)] = c
tnums = sorted(int(t[5:]) for t in tasks)
tmissing = sorted(set(range(1, tnums[-1] + 1)) - set(tnums))
print(f"wbs: {len(tasks)} tasks, TASK-{tnums[0]:03d}..TASK-{tnums[-1]:03d}, missing numbers: {tmissing or 'none'}")
if tmissing:
    problem(f"wbs: missing task numbers {tmissing}")
covered = {r for t in tasks.values() for r in re.findall(r"REQ-\d{3}", t[2])}
no_task = sorted(set(rows) - covered)
print(f"  requirements without a task: {no_task or 'none'} (Must among them: {sorted(set(no_task) & must) or 'none'})")
if no_task:
    problem(f"wbs: requirements without a task {no_task}")
if covered - set(rows):
    problem(f"wbs: unknown requirement ids {sorted(covered - set(rows))}")
owners = Counter(t[5] for t in tasks.values())
print("  owners:", dict(owners))
bad_owner = [k for k, t in tasks.items() if t[5] not in {"Claude", "동희님", "가쟈누님"}]
if bad_owner:
    problem(f"wbs: tasks without exactly one known owner {bad_owner}")
no_done = [k for k, t in tasks.items() if not t[7]]
if no_done:
    problem(f"wbs: tasks without a done condition {no_done}")
rr_in_wbs = set().union(*(rr_refs(t[1] + " " + t[7]) for t in tasks.values()))
untested = sorted(set(defined_rr) - rr_in_wbs)
print(f"  RR-26..{defined_rr[-1]} not named by any task: {untested or 'none'}")
if untested:
    problem(f"wbs: counterexamples no task tests {untested}")
state_refs = set()
for text in (wbs, req_md, screens, supplement):
    state_refs |= set(re.findall(r"(SCR-\d{3})#([a-z]+)", text))
unknown_states = sorted(f"{s}#{x}" for s, x in state_refs if x not in states.get(s, []))
print(f"  state references that screens.md does not define: {unknown_states or 'none'}")
if unknown_states:
    problem(f"state references not defined in screens.md {unknown_states}")

deps = {}
for k, t in tasks.items():
    named = task_refs(t[4])
    if named - set(tasks):
        problem(f"wbs: {k} depends on unknown {sorted(named - set(tasks))}")
    deps[k] = named & set(tasks)
size = {k: float(m.group(1)) if (m := re.match(r"([\d.]+)일$", t[6])) else None for k, t in tasks.items()}
open_ended = [k for k, v in size.items() if v is None]
done = [k for k, t in tasks.items() if "**끝남**" in t[1]]
print(f"  size sum without open-ended tasks {open_ended}: {sum(v for v in size.values() if v is not None)}일; "
      f"of which done {done}: {sum(size[k] or 0 for k in done)}일")

state, cycles = {}, []


def visit(node, trail):
    if state.get(node) == 1:
        cycles.append(trail + [node])
        return
    if state.get(node) == 2:
        return
    state[node] = 1
    for d in sorted(deps[node]):
        visit(d, trail + [node])
    state[node] = 2


for k in tasks:
    visit(k, [])
print("  dependency cycles:", cycles or "none")
if cycles:
    problem(f"wbs: dependency cycles {cycles}")

memo = {}


def longest(node):
    if node not in memo:
        best = max((longest(d) for d in deps[node]), key=lambda p: p[0], default=(0.0, []))
        memo[node] = (best[0] + (size[node] or 0.0), best[1] + [node])
    return memo[node]


for end in ("TASK-038", "TASK-039"):
    if end in tasks:
        days, chain = longest(end)
        print(f"  longest dependency chain ending at {end} (open-ended tasks count 0): {days}일 = {' > '.join(chain)}")

# --- project settings -----------------------------------------------------------------------
project = json.loads(read(".corthex/project.json"))
for key, stage in project.get("stages", {}).items():
    src = stage.get("source", "")
    if src and ":" not in src and not (root / src).exists():
        problem(f"project.json: {key} source {src} does not exist")

# --- links, anchors and tables in Markdown --------------------------------------------------


def slugs(path):
    found, seen = set(), Counter()
    for line in strip_fences(path.read_text(encoding="utf-8")):
        m = re.match(r"(#{1,6})\s+(.*?)\s*#*\s*$", line)
        if m:
            text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", m.group(2))
            s = re.sub(r"[^\w\- ]", "", text.strip().lower()).replace(" ", "-")
            found.add(s if seen[s] == 0 else f"{s}-{seen[s]}")
            seen[s] += 1
    return found


md_files = sorted((root / "docs").rglob("*.md")) + [root / "README.md"]
link_count, table_count = 0, 0
for path in md_files:
    lines = strip_fences(path.read_text(encoding="utf-8"))
    for n, line in enumerate(lines, 1):
        for target in re.findall(r"\[[^\]]*\]\(([^)\s]+)\)", re.sub(r"`[^`]*`", "", line)):
            if re.match(r"[a-z]+:", target):
                continue
            link_count += 1
            file_part, _, anchor = target.partition("#")
            dest = (path.parent / file_part).resolve() if file_part else path
            rel = path.relative_to(root)
            if not dest.exists():
                problem(f"link: {rel}:{n} -> {target} (no such file)")
            elif anchor and dest.suffix == ".md" and anchor not in slugs(dest):
                problem(f"link: {rel}:{n} -> {target} (no such heading)")
    header = None
    for n, line in enumerate(lines, 1):
        if line.lstrip().startswith("|"):
            width = len(cells(line))
            if header is None:
                header, table_count = width, table_count + 1
            elif width != header:
                problem(f"table: {path.relative_to(root)}:{n} has {width} cells, header has {header}")
        else:
            header = None
print(f"markdown: {len(md_files)} files, {link_count} relative links, {table_count} tables checked")

print()
if problems:
    print(f"{len(problems)} problem(s):")
    for p in problems:
        print("-", p)
    sys.exit(1)
print("no problems")
