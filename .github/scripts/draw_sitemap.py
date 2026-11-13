#!/usr/bin/env python3
"""Draws the sitemap views in docs/05-ui/screens.md (section 1) from docs/05-ui/sitemap.yaml.

Usage: python3 .github/scripts/draw_sitemap.py [repository root] [--check]

The YAML is the source (CORTHEX stage 07). This script rewrites the part of screens.md between
`<!-- sitemap:start -->` and `<!-- sitemap:end -->`: a Mermaid board (parent -> child as solid
lines, `links` as dotted lines) and a list in tree order. With --check it changes nothing and
exits with 1 when the views are not what the YAML draws. Standard library only; it reads only the
simple YAML shape this file uses.
"""
import re
import sys
from pathlib import Path

ENTRY_POINTS = {"widget": "홈 화면", "notification": "알림창 · 잠금 화면", "app": "앱 아이콘"}


def parse(text):
    """Returns the screens in file order as dicts with depth and parent."""
    screens, stack = [], []
    current = None
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        m = re.match(r"(\s*)- id: (SCR-\d{3})\s*$", line)
        if m:
            indent = len(m.group(1))
            while stack and stack[-1][0] >= indent:
                stack.pop()
            current = {"id": m.group(2), "parent": stack[-1][1]["id"] if stack else None, "depth": len(stack)}
            stack.append((indent, current))
            screens.append(current)
            continue
        m = re.match(r"\s*(\w+): (.*)$", line)
        if m and current is not None:
            key, value = m.groups()
            if value.startswith("["):
                value = [v.strip().strip('"') for v in value.strip("[]").split(",") if v.strip()]
            current[key] = value
    return screens


def draw(screens):
    node = lambda sid: sid.replace("-", "")
    out = ["```mermaid", "flowchart TD"]
    for prefix, label in ENTRY_POINTS.items():
        for s in screens:
            if s["parent"] is None and s["path"].startswith(prefix + ":"):
                out.append(f'    {prefix}(["{label}"]) --> {node(s["id"])}["{s["id"]} {s["name"]}<br/>{s["path"]}"]')
    for s in screens:
        if s["parent"]:
            out.append(f'    {node(s["parent"])} --> {node(s["id"])}["{s["id"]} {s["name"]}<br/>{s["path"]}"]')
    for s in screens:
        for link in s.get("links", []):
            to, _, label = link.partition(":")
            out.append(f'    {node(s["id"])} -. "{label.strip()}" .-> {node(to.strip())}')
    out.append("```")
    out.append("")
    out.append("| ID | 화면 | 경로 | 누가 보나 | 흐름 | 지금 코드 |")
    out.append("| --- | --- | --- | --- | --- | --- |")
    for s in screens:
        name = "　" * s["depth"] + s["name"]
        flows = ", ".join(s.get("flows", [])) or "없음"
        out.append(f'| {s["id"]} | {name} | `{s["path"]}` | {s["access"]} | {flows} | {s.get("code", "")} |')
    return "\n".join(out)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    root = Path(args[0] if args else ".").resolve()
    screens = parse((root / "docs/05-ui/sitemap.yaml").read_text(encoding="utf-8"))
    doc_path = root / "docs/05-ui/screens.md"
    doc = doc_path.read_text(encoding="utf-8")
    m = re.search(r"(<!-- sitemap:start -->\n)(.*?)(\n<!-- sitemap:end -->)", doc, re.S)
    if not m:
        print("screens.md has no sitemap markers")
        sys.exit(1)
    drawn = draw(screens)
    if "--check" in sys.argv:
        if m.group(2) != drawn:
            print("screens.md section 1 is not drawn from sitemap.yaml: run .github/scripts/draw_sitemap.py")
            sys.exit(1)
        print(f"sitemap views: {len(screens)} screens, drawn from sitemap.yaml")
        return
    doc_path.write_text(doc[: m.start(2)] + drawn + doc[m.end(2):], encoding="utf-8")
    print(f"drew {len(screens)} screens into {doc_path.relative_to(root)}")


if __name__ == "__main__":
    main()
