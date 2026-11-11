#!/usr/bin/env python3
"""Recounts the claims the design documents make about themselves.

Usage: python3 check-docs.py <repository root>
Reads docs/01-planning/requirements.md, docs/05-ui/sitemap.yaml, docs/05-ui/screens.md and
docs/08-wbs/wbs.md. Standard library only; prints the counts and changes nothing.
"""
import re
import sys
from collections import Counter
from pathlib import Path

root = Path(sys.argv[1])
req_md = (root / "docs/01-planning/requirements.md").read_text(encoding="utf-8")
sitemap = (root / "docs/05-ui/sitemap.yaml").read_text(encoding="utf-8")
screens = (root / "docs/05-ui/screens.md").read_text(encoding="utf-8")
wbs = (root / "docs/08-wbs/wbs.md").read_text(encoding="utf-8")

# --- requirements -------------------------------------------------------------------------
rows = {}
for line in req_md.splitlines():
    m = re.match(r"\|\s*(REQ-\d{3})\s*\|", line)
    if m:
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        assert m.group(1) not in rows, f"duplicate {m.group(1)}"
        rows[m.group(1)] = cells
ids = sorted(rows)
nums = [int(i[4:]) for i in ids]
print(f"requirements: {len(ids)} rows, {ids[0]}..{ids[-1]}, missing numbers: "
      f"{sorted(set(range(1, max(nums) + 1)) - set(nums)) or 'none'}")
print("  kind:", dict(Counter(r[2] for r in rows.values())))
print("  priority:", dict(Counter(r[3] for r in rows.values())))
empty = [i for i, r in rows.items() if not all(r[k] for k in (1, 2, 3, 4, 5))]
print("  rows with an empty requirement/kind/priority/source/acceptance cell:", empty or "none")
rr = sorted({int(n) for r in rows.values() for n in re.findall(r"RR-(\d+)", r[5])})
print(f"  RR counterexamples linked from acceptance criteria: {len(rr)} of 25, not linked: "
      f"{sorted(set(range(1, 26)) - set(rr))}")
functional = {i for i, r in rows.items() if r[2] == "기능"}

# --- sitemap and screens ------------------------------------------------------------------
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
print(f"  functional requirements not used by any screen: {sorted(functional - used) or 'none'}")
print(f"  screen reqs that are not functional requirements: "
      f"{sorted(i for i in used if i in rows and i not in functional) or 'none'}; "
      f"unknown ids: {sorted(used - set(rows)) or 'none'}")
sections = re.split(r"\n### (SCR-\d{3}) ", screens)
mismatch = []
for sid, body in zip(sections[1::2], sections[2::2]):
    body = body.split("\n## ")[0]
    in_doc = set(re.findall(r"REQ-\d{3}", body))
    if in_doc != site_reqs.get(sid, set()):
        mismatch.append((sid, sorted(in_doc ^ site_reqs.get(sid, set()))))
print(f"  screens.md sections whose REQ set differs from sitemap.yaml: {mismatch or 'none'}")

# --- work breakdown -----------------------------------------------------------------------
tasks = {}
for line in wbs.splitlines():
    m = re.match(r"\|\s*(TASK-\d{3})\s*\|", line)
    if m:
        tasks[m.group(1)] = [c.strip() for c in line.strip().strip("|").split("|")]
covered = {r for t in tasks.values() for r in re.findall(r"REQ-\d{3}", t[2])}
print(f"wbs: {len(tasks)} tasks; requirements without a task: {sorted(set(rows) - covered) or 'none'}; "
      f"unknown requirement ids: {sorted(covered - set(rows)) or 'none'}")
print("  owners:", dict(Counter(t[5] for t in tasks.values())),
      "| tasks without a done condition:", [k for k, t in tasks.items() if not t[7]] or "none")

def expand(cell):
    deps = set()
    for a, b in re.findall(r"TASK-(\d{3})~TASK-(\d{3})", cell):
        deps |= {f"TASK-{n:03d}" for n in range(int(a), int(b) + 1)}
    return deps | set(re.findall(r"TASK-\d{3}", cell))

deps = {k: expand(t[4]) & set(tasks) for k, t in tasks.items()}
size = {k: float(m.group(1)) if (m := re.match(r"([\d.]+)일$", t[6])) else None for k, t in tasks.items()}
print(f"  size sum without open-ended tasks {[k for k, v in size.items() if v is None]}: "
      f"{sum(v for v in size.values() if v is not None)}일")

state, cycle = {}, []
def visit(node, trail):
    if state.get(node) == 1:
        cycle.append(trail + [node]); return
    if state.get(node) == 2:
        return
    state[node] = 1
    for d in sorted(deps[node]):
        visit(d, trail + [node])
    state[node] = 2
for k in tasks:
    visit(k, [])
print("  dependency cycles:", cycle or "none")

memo = {}
def longest(node):
    if node not in memo:
        best = max((longest(d) for d in deps[node]), key=lambda p: p[0], default=(0.0, []))
        memo[node] = (best[0] + (size[node] or 0.0), best[1] + [node])
    return memo[node]
days, chain = longest("TASK-038")
print(f"  longest dependency chain ending at TASK-038: {days}일 = {' > '.join(chain)}")
