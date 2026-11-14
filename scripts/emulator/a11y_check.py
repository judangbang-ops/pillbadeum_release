"""TASK-030 accessibility check (REQ-066, REQ-067, REQ-068, REQ-069) on one emulator (API 34) at a 360dp width: every
app screen and its dialogs, the widget, the widget's person picker and the reminder, each at text 100% and 200%,
photographed page by page and measured from its screen dump and its raw pixels.

Why: TASK-030's end condition in docs/08-wbs/wbs.md is "폭 360dp·글자 100%와 200% 화면별 캡처, 명암비 측정표", and
nobody on the team has an Android phone yet (2026-11-12). The app tests (AppScreenTest, Robolectric) draw some screens
at 360dp; this script sees what the phone draws, on every screen.

On every page (one screen dump, which holds what TalkBack reads, and one raw screen capture):
- REQ-067: each target, a node of the app or of our widget that is clickable, long-clickable or checkable (also when
  disabled: TalkBack reads it, with its state), is at least 48 x 48 dp. A node may be cut where it is drawn only in
  part: past the edge of the list it scrolls in, at the bottom of that list (or its top, once the list has moved), or
  reported smaller than the role node Compose puts under it (see `covered`). It counts where it is whole; one only
  ever seen cut is listed apart.
- REQ-068: each whole target has words to read: its description, its text, or the words of the nodes under it that are
  not targets themselves. Every target and what it reads goes to detail.md, for a person to read.
- REQ-066: no text touches the screen's left or right edge (the screens keep a 16dp side), and no two texts, no two
  targets and no text and a target outside it lie on top of each other. Bounds are taken within the lists a node
  scrolls in: Compose reports a control's touch bounds, which reach 24dp (half a target) past the edge of its list
  (NodeCoordinator.touchBoundsInRoot), where nothing is drawn. Compose reports a text's whole string even when it is
  drawn cut, so the captures are also looked at by a person.
- REQ-069: under each text (a node with a text, or in the app a description that is not a target: Compose texts read
  as one sentence carry no text of their own) the most common color is the background, and each token text color
  drawn there with at least 15 pixels is a color of its letters; each pair's ratio (WCAG 2.1) must be at least 4.5:1.
  White is both `surface.default` and `ink.on-solid`: on a background it differs from by less than 1.5:1 it is taken
  for a white cell, card or icon, not letters (the design draws white letters only on solid fills; tokens.md has the
  designed pairs, and a person looks at the captures). In the widget the texts are views of their own, so a tile's
  description is not measured. Colors are named by design/base.tokens.json; colors that are no token are listed. The
  reminder is drawn by System UI: its targets and words are listed, not judged.

Flow (clock in Asia/Seoul; day 0 is the run's date; the display density set for a 360dp width from the start):
   0. Setup: day -7 09:00, heads-up popups off, the debug APK installed.
   1. SCR-005 the intro, then SCR-004#empty.
   2. Five items added by the debug receiver with Korean names (one of 12 letters with a space), two people: two
      doses on day -7, so the weekly item shows its last dose on day 0.
   3. Day 0 09:00: 엄마 in green (SCR-010), one dose and one skip, so the first screen has every tile state.
   4. Each screen at 100%, then at 200%: SCR-004; SCR-006 new, with [+ 가족] open, edit, and the archive question;
      SCR-007 and its time picker; SCR-008; SCR-009 and its interval, per-day, quiet-hours and base-zone pickers (the
      zone list, some 600 lines of one kind, for its first 3 pages); SCR-010 and its person form; SCR-001 the widget;
      SCR-002 the widget's person picker; SCR-003 the reminder: the app shows a reminder only within 10 minutes of its
      time (ReminderAlarm.WINDOW), so the clock goes to the next planned one before the alarm's broadcast is sent.

Names are added through the debug receiver (androidApp/src/debug), not typed: `adb shell input text` cannot type Korean.

Writes report.md, detail.md, a11y.json, the pages (apiNN-NN-<screen>-<100|200>-pN.png with its .xml) and steps.json
(each step's pictures, for the capture gallery) to --out. Exits 1 when a check fails.
`--tokens-only` writes tokens.md, the contrast of the token pairs the design uses, with no emulator.
Standard library only. Not product code.
"""

from __future__ import annotations

import argparse
import json
import re
import struct
import subprocess
import sys
import time
import traceback
import xml.etree.ElementTree as ET
import zlib
from array import array
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path

from backup_restore_check import (
    ACTIVITY,
    BOUNDS,
    INTRO_MARK,
    PKG,
    UI_XML,
    Report,
    adb,
    api_level,
    become_root,
    dump_ui,
    install,
    launch_app,
    sh,
    tap,
    uninstall,
    wait_boot,
)
from explore import (
    KEYBOARD_NOTES,
    LOG_PARTS,
    ME,
    ZONE,
    ensure_home,
    exact,
    font_scale,
    night,
    row_button,
    set_clock,
    set_zone,
    tap_exact,
    tap_scrolled,
    tile,
)
from release_check import PICK_TITLE, go_home, place_widget
from reminder_check import ACTION_SEED, REMINDER_ALARM, REMINDER_RECEIVER, TITLE_END, collapse_shade, debug, expand_shade
from reminder_check import alarms, doses, query, wait_posted
from screens_check import (
    GREEN,
    RECONFIGURE,
    ShotSteps,
    app_in_front,
    at_home,
    desc_is,
    desc_starts,
    has_desc,
    has_text,
    open_history,
    open_people,
    refresh_widgets,
    reopen,
    scroll_top,
    seek,
    text_is,
    wait_screen,
    wipe_widget_choices,
)

TOKENS_FILE = Path(__file__).resolve().parents[2] / "design" / "base.tokens.json"

WIDTH_DP = 360  # REQ-066's width
TARGET_DP = 48  # REQ-067, design token size.target
TEXT_RATIO = 4.5  # REQ-069 (WCAG 2.1 AA for text)
SCALES = ("1.0", "2.0")
MAX_PAGES = 10
TOP_SWIPES = 2 * MAX_PAGES  # a swipe moves 70% of a list's height, so this reaches the top from MAX_PAGES pages down
ZONE_PAGES = 3  # the base-zone list: some 600 lines of one kind
MIN_TEXT_PIXELS = 15  # a token color with fewer pixels under a text is not taken for its letters
WHITE_FILL_RATIO = 1.5  # white (also surface.default) closer than this to its background is a fill, not letters

# The data (Korean names, typed through the debug receiver); ME is the first person (ManagePeople.DEFAULT_PERSON_NAME)
MOM = "엄마"
MULTI = "종합비타민"  # 나, daily 08:00: taken on day -7 and day 0
PROBIOTIC = "유산균"  # 나, daily 08:00: skipped on day 0
BONE = "골다공증약"  # 나, weekly on day 0's weekday: taken on day -7, so pending with its last dose on day 0
BP = "혈압약"  # 엄마, daily 20:00: pending in the evening, no record
LONG = "글루코사민 콘드로이친"  # 엄마, daily 08:00: pending, the longest name (12 letters with a space)
WEEKDAY_NAMES = ("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")

# Texts of the app (shared/src/commonMain/.../ui and status/Labels.kt)
EMPTY_HOME = "아직 복용 항목이 없어요"
ARCHIVE_ASK_END = "보관할까요?"  # ItemScreen's ConfirmDialog title, `<name>을(를) 보관할까요?`
INTERVAL_NOTE = "기록하지 않으면 이 간격으로 다시 알려요."
PER_DAY_NOTE = "첫 알림과 다시 알림을 모두 센 횟수예요."
QUIET_NOTE = "이 시간에는 다시 알림이 울리지 않아요. 첫 알림은 울려요."
ZONE_NOTE = "날짜를 이 시간대로 셈해요."
ACTION_LABELS = ("먹었어", "30분 뒤", "오늘 건너뜀", "이번 주 건너뜀")  # ReminderLabels


# ------------------------------------------------------------------------------------------------
# Colors (design/base.tokens.json, WCAG 2.1)


def load_tokens() -> dict[str, str]:
    """`surface.default` -> `#FFFFFF`, for every color token."""
    data = json.loads(TOKENS_FILE.read_text(encoding="utf-8"))
    found: dict[str, str] = {}

    def walk(node, path: list[str]) -> None:
        if not isinstance(node, dict):
            return
        value = node.get("$value")
        if isinstance(value, dict) and "hex" in value:
            found[".".join(path)] = value["hex"].upper()
            return
        for key, child in node.items():
            if not key.startswith("$"):
                walk(child, path + [key])

    walk(data.get("color", {}), [])
    return found


TOKENS = load_tokens()
BY_HEX: dict[str, list[str]] = {}
for _name, _hex in TOKENS.items():
    BY_HEX.setdefault(_hex, []).append(_name)


def is_text_token(name: str) -> bool:
    """Letters are drawn in `ink.*` and `person.N.ink`; the rest are fills and lines."""
    return name.startswith("ink.") or bool(re.fullmatch(r"person\.\d+\.ink", name))


def text_name(hex_: str) -> str | None:
    return next((n for n in BY_HEX.get(hex_, []) if is_text_token(n)), None)


def fill_name(hex_: str) -> str:
    """A background's token name: a fill or line when one has that color (white is surface.default, not
    ink.on-solid), else any token, else the hex itself."""
    names = BY_HEX.get(hex_, [])
    return next((n for n in names if not is_text_token(n)), names[0] if names else hex_)


def rgb(hex_: str) -> tuple[int, int, int]:
    return int(hex_[1:3], 16), int(hex_[3:5], 16), int(hex_[5:7], 16)


def luminance(hex_: str) -> float:
    def channel(v: int) -> float:
        c = v / 255
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4

    r, g, b = rgb(hex_)
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)


def ratio(a: str, b: str) -> float:
    la, lb = luminance(a), luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


def designed_pairs() -> list[tuple[str, str, str]]:
    """(letters or part, background, kind) of the token pairs the design uses (09 mockup, box.css and Tokens.kt's notes).
    kind `글자` needs 4.5:1, `글자 아닌 것` 3:1 (WCAG 2.1 1.4.11, beyond REQ-069)."""
    people = sorted({m.group(1) for n in TOKENS if (m := re.fullmatch(r"person\.(\d+)\.solid", n))}, key=int)
    surfaces = ("surface.default", "surface.sunken", "surface.skip", "surface.notice")
    pairs: list[tuple[str, str, str]] = []
    for ink in ("ink.default", "ink.muted", "ink.danger"):
        pairs += [(ink, s, "글자") for s in surfaces]
    pairs.append(("ink.notice", "surface.notice", "글자"))
    pairs.append(("ink.on-solid", "ink.default", "글자"))
    for p in people:
        pairs.append(("ink.on-solid", f"person.{p}.solid", "글자"))
        pairs.append((f"person.{p}.ink", f"person.{p}.tint", "글자"))
        pairs.append((f"person.{p}.ink", "surface.default", "글자"))
    pairs += [("line.strong", s, "글자 아닌 것") for s in ("surface.default", "surface.sunken", "surface.skip")]
    pairs += [(f"person.{p}.solid", "surface.default", "글자 아닌 것") for p in people]
    pairs.append(("line.subtle", "surface.default", "꾸밈 (기준 없음)"))
    return pairs


def tokens_table() -> str:
    lines = ["| 앞 | 바탕 | 종류 | 명암비 | 기준 |", "| --- | --- | --- | --- | --- |"]
    for fg, bg, kind in designed_pairs():
        r = ratio(TOKENS[fg], TOKENS[bg])
        need = TEXT_RATIO if kind == "글자" else 3.0 if kind == "글자 아닌 것" else None
        verdict = "-" if need is None else ("통과" if r >= need else "**미달**")
        lines.append(f"| `{fg}` {TOKENS[fg]} | `{bg}` {TOKENS[bg]} | {kind} | {r:.2f}:1 | {verdict} |")
    return "\n".join(lines) + "\n"


# ------------------------------------------------------------------------------------------------
# Raw screen capture


@dataclass
class Shot:
    w: int
    h: int
    data: bytes
    offset: int

    def colors(self, bounds: tuple[int, int, int, int], inset: int = 0) -> Counter:
        """How many pixels of each color lie in `bounds` less `inset` on every side (clipped to the screen). A color is
        the pixel as a little-endian int (R in the low byte)."""
        x1, y1, x2, y2 = bounds
        x1, x2 = max(0, x1 + inset), min(self.w, x2 - inset)
        y1, y2 = max(0, y1 + inset), min(self.h, y2 - inset)
        found: Counter = Counter()
        if x2 <= x1 or y2 <= y1:
            return found
        stride = self.w * 4
        for y in range(y1, y2):
            start = self.offset + y * stride + x1 * 4
            row = array("I")
            row.frombytes(self.data[start:start + (x2 - x1) * 4])
            found.update(row)
        return found

    def png(self) -> bytes:
        stride = self.w * 4
        rows = b"".join(b"\x00" + self.data[self.offset + y * stride:self.offset + (y + 1) * stride] for y in range(self.h))

        def chunk(kind: bytes, body: bytes) -> bytes:
            return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)

        head = struct.pack(">IIBBBBB", self.w, self.h, 8, 6, 0, 0, 0)  # 8-bit RGBA
        return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", head) + chunk(b"IDAT", zlib.compress(rows, 6)) + chunk(b"IEND", b"")


def grab() -> Shot:
    """`screencap` without -p: width, height and format (and on newer Android a color space), then RGBA_8888 rows."""
    data = subprocess.run(["adb", "exec-out", "screencap"], capture_output=True, check=True, timeout=60).stdout
    w, h, fmt = struct.unpack_from("<III", data, 0)
    header = len(data) - w * h * 4
    if fmt != 1 or header not in (12, 16):
        raise RuntimeError(f"raw screen capture is not RGBA_8888: {w}x{h}, format {fmt}, header {header} bytes")
    return Shot(w, h, data, header)


def hex_of(pixel: int) -> str:
    return f"#{pixel & 0xFF:02X}{(pixel >> 8) & 0xFF:02X}{(pixel >> 16) & 0xFF:02X}"


# ------------------------------------------------------------------------------------------------
# Screen dump with every attribute


@dataclass
class N:
    i: int
    parent: int
    text: str
    desc: str
    cls: str
    pkg: str
    rid: str
    bounds: tuple[int, int, int, int]
    click: bool
    long_click: bool
    checkable: bool
    checked: bool
    scroll: bool
    enabled: bool
    selected: bool
    kids: list[int] = field(default_factory=list)

    @property
    def target(self) -> bool:
        """A control, also when disabled (`[이 시각으로 기록]` before a time): TalkBack reads it with its state."""
        return self.click or self.long_click or self.checkable

    @property
    def w(self) -> int:
        return self.bounds[2] - self.bounds[0]

    @property
    def h(self) -> int:
        return self.bounds[3] - self.bounds[1]


def parse(xml: str) -> list[N]:
    nodes: list[N] = []

    def flag(el, name: str) -> bool:
        return el.get(name) == "true"

    def walk(el, parent: int) -> None:
        for child in el.findall("node"):
            m = BOUNDS.match(child.get("bounds", ""))
            bounds = tuple(int(v) for v in m.groups()) if m else (0, 0, 0, 0)
            node = N(
                len(nodes), parent,
                # The widget joins the letters of Korean words with U+2060 (WidgetLayout.keepWords)
                child.get("text", "").replace("\u2060", ""), child.get("content-desc", "").replace("\u2060", ""),
                child.get("class", ""), child.get("package", ""), child.get("resource-id", ""), bounds,
                flag(child, "clickable"), flag(child, "long-clickable"), flag(child, "checkable"), flag(child, "checked"),
                flag(child, "scrollable"), child.get("enabled", "true") == "true", flag(child, "selected"),
            )
            nodes.append(node)
            if parent >= 0:
                nodes[parent].kids.append(node.i)
            walk(child, node.i)

    walk(ET.fromstring(xml), -1)
    return nodes


def full_dump() -> tuple[list[N], str]:
    """dump_ui (which closes a stalled system app's dialog and fails on our own), then the same file read in full."""
    dump_ui()
    xml = adb("exec-out", "cat", UI_XML, check=False)
    return parse(xml), xml


def under(nodes: list[N], i: int) -> list[int]:
    found, stack = [], list(nodes[i].kids)
    while stack:
        k = stack.pop()
        found.append(k)
        stack.extend(nodes[k].kids)
    return found


def ancestors(nodes: list[N], i: int) -> list[int]:
    found, p = [], nodes[i].parent
    while p >= 0:
        found.append(p)
        p = nodes[p].parent
    return found


def scope(nodes: list[N], mode: str) -> list[int]:
    """The nodes a page is judged on: the app's own window, our widget on the launcher, or our reminders in the shade."""
    if mode == "app":
        return [n.i for n in nodes if n.pkg == PKG]
    if mode == "widget":
        # LauncherAppWidgetHostView (package of the launcher, read as the widget's label) holds the views our app's
        # RemoteViews made, which carry our package
        hosts = [n.i for n in nodes if "AppWidgetHostView" in n.cls]
        return sorted({k for h in hosts for k in under(nodes, h) if nodes[k].pkg == PKG})
    # shade: each of our reminders, from its title up to the first parent that also holds its buttons
    rows: set[int] = set()
    for n in nodes:
        if not n.text.endswith(TITLE_END):
            continue
        row = n.i
        for p in ancestors(nodes, n.i):
            row = p
            if any(nodes[k].text in ACTION_LABELS for k in under(nodes, p)):
                break
        rows.add(row)
    return sorted({k for r in rows for k in [r, *under(nodes, r)]})


def label(nodes: list[N], n: N) -> str:
    """What TalkBack reads for target `n`: its description or text, else the words under it that are not targets
    (a node with a description is read as that, not as what is under it)."""
    own = n.desc or n.text
    if own:
        return own
    words, stack = [], list(reversed(n.kids))
    while stack:
        k = nodes[stack.pop()]
        if k.target:
            continue
        if k.desc or k.text:
            words.append(k.desc or k.text)
        else:
            stack.extend(reversed(k.kids))
    return ", ".join(words)


ROLES = (("Switch", "스위치"), ("CheckBox", "체크박스"), ("RadioButton", "라디오 버튼"), ("EditText", "입력 칸"),
         ("Button", "버튼"))


def role_child(nodes: list[N], n: N) -> N | None:
    """The node Compose adds under a control that merges its words, to read its role: a `Button` (or another role
    class) with no words of its own. It keeps the control's bounds even where the control is reported covered."""
    return next((nodes[k] for k in n.kids if not nodes[k].target and not nodes[k].text and not nodes[k].desc
                 and any(nodes[k].cls.endswith(key) for key, _ in ROLES)), None)


def role(nodes: list[N], n: N) -> str:
    """The role TalkBack names, from the class (Compose puts it on a child with the same bounds), and the state."""
    child = role_child(nodes, n)
    classes = [n.cls] + ([child.cls] if child else [])
    word = next((w for c in classes for key, w in ROLES if c.endswith(key)), "")
    state = []
    if n.checkable:
        if word == "스위치":
            state.append("켜짐" if n.checked else "꺼짐")
        else:
            state.append("선택됨" if n.checked else "선택 안 됨")
    if n.selected and "선택됨" not in state:
        state.append("선택됨")
    if not n.enabled:
        state.append("쓸 수 없음")
    return ", ".join(x for x in [word, *state] if x)


Box = tuple[int, int, int, int]


def contains(outer: Box, inner: Box) -> bool:
    return outer[0] <= inner[0] and outer[1] <= inner[1] and outer[2] >= inner[2] and outer[3] >= inner[3]


def clip(a: Box, b: Box) -> Box | None:
    x1, y1, x2, y2 = max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])
    return (x1, y1, x2, y2) if x2 > x1 and y2 > y1 else None


def viewport(nodes: list[N], n: N) -> Box | None:
    p = n.parent
    while p >= 0:
        if nodes[p].scroll:
            return nodes[p].bounds
        p = nodes[p].parent
    return None


def visible(nodes: list[N], n: N) -> Box | None:
    """`n`'s bounds within every list it scrolls in, None when it lies outside. Compose reports a control's touch
    bounds, and a list clips them only half a target (24dp) past its edge (NodeCoordinator.touchBoundsInRoot): a tile
    of the first screen's list reached 24dp up under the top bar, where the bar is drawn (run 43570269368)."""
    box: Box | None = n.bounds
    p = n.parent
    while p >= 0 and box is not None:
        if nodes[p].scroll:
            box = clip(box, nodes[p].bounds)
        p = nodes[p].parent
    return box


def covered(nodes: list[N], n: N) -> bool:
    """Compose gives a node only the part of it no node drawn later covers (the accessibility delegate's uncovered
    region), so beside a list that reaches under it the top bar's `[지난 기록]` was reported 28dp tall; its role node
    kept the whole 48dp (run 43570269368, first screen, page 2)."""
    child = role_child(nodes, n)
    return child is not None and child.bounds != n.bounds and contains(child.bounds, n.bounds)


def maybe_cut(nodes: list[N], n: N, page: int) -> bool:
    """A node drawn only in part: past the edge of the list it scrolls in, reported smaller than its role node, or at
    the bottom of its list (or its top once the list has moved), where Compose reports it already clipped."""
    if visible(nodes, n) != n.bounds or covered(nodes, n):
        return True
    vp = viewport(nodes, n)
    if vp is None:
        return False
    return n.bounds[3] >= vp[3] - 1 or (page > 0 and n.bounds[1] <= vp[1] + 1)


def overlap(a: Box, b: Box) -> bool:
    return min(a[2], b[2]) - max(a[0], b[0]) >= 2 and min(a[3], b[3]) - max(a[1], b[1]) >= 2


# ------------------------------------------------------------------------------------------------
# Measuring


@dataclass
class Measured:
    sid: str
    name: str
    scale: str
    mode: str
    pages: list[str] = field(default_factory=list)
    targets: dict = field(default_factory=dict)  # key -> {label, role, w, h, whole, pages}
    edges: list[str] = field(default_factory=list)
    overlaps: list[str] = field(default_factory=list)
    pairs: dict = field(default_factory=dict)  # (fg, bg) -> {ratio, fg_hex, bg_hex, sample, count}
    parts: dict = field(default_factory=dict)  # non-text token parts: (fg, bg) -> ratio
    unknown: Counter = field(default_factory=Counter)  # non-token colors that fill 5%+ of a text's box
    unread: list[str] = field(default_factory=list)  # texts whose letters matched no token color
    truncated: bool = False  # stopped at MAX_PAGES before the end of the list

    def to_json(self) -> dict:
        return {
            "sid": self.sid, "name": self.name, "scale": self.scale, "mode": self.mode, "pages": self.pages,
            "truncated": self.truncated,
            "targets": list(self.targets.values()), "edges": self.edges, "overlaps": self.overlaps,
            "pairs": [{"fg": fg, "bg": bg, **v} for (fg, bg), v in sorted(self.pairs.items())],
            "parts": [{"fg": fg, "bg": bg, "ratio": v} for (fg, bg), v in sorted(self.parts.items())],
            "unknown": dict(self.unknown.most_common(8)), "unread": self.unread[:20],
        }


DENSITY = 3.0  # px per dp, set in setup


def dp(px: int) -> float:
    return px / DENSITY


def judge_page(m: Measured, nodes: list[N], shot: Shot | None, page: int) -> None:
    box: dict[int, Box] = {}
    for i in scope(nodes, m.mode):
        seen_box = visible(nodes, nodes[i])
        if seen_box is not None:  # a node wholly past the edge of its list is not on screen
            box[i] = seen_box
    ids = list(box)
    rel: dict[int, set[int]] = {i: set(ancestors(nodes, i)) for i in ids}

    def related(a: int, b: int) -> bool:
        # A text field is both a text and a target: the same node
        return a == b or a in rel.get(b, ()) or b in rel.get(a, ())

    targets = [i for i in ids if nodes[i].target]
    for i in targets:
        n = nodes[i]
        said = label(nodes, n)
        cut = maybe_cut(nodes, n, page)
        key = (said, n.bounds[0], n.bounds[2], n.w, n.h if not cut else -1)
        seen = m.targets.get(key)
        if seen is None:
            seen = m.targets[key] = {"label": said, "role": role(nodes, n), "w": round(dp(n.w), 1),
                                     "h": round(dp(n.h), 1), "whole": not cut, "pages": []}
        seen["pages"].append(page + 1)

    texts = [i for i in ids if nodes[i].text.strip()]
    if m.mode == "shade":
        return  # System UI draws it: targets and words only
    described = [i for i in ids if nodes[i].desc.strip() and not nodes[i].target and not nodes[i].text]
    sw = shot.w if shot else 0
    for i in texts + described:
        n = nodes[i]
        if sw and (box[i][0] <= 1 or box[i][2] >= sw - 1):
            m.edges.append(f"p{page + 1} '{(n.text or n.desc)[:30]}' {box[i]}")
    for a_pos, a in enumerate(texts):
        for b in texts[a_pos + 1:]:
            if not related(a, b) and overlap(box[a], box[b]):
                m.overlaps.append(f"p{page + 1} 글자 '{nodes[a].text[:20]}' {box[a]} ↔ '{nodes[b].text[:20]}' {box[b]}")
    for a_pos, a in enumerate(targets):
        for b in targets[a_pos + 1:]:
            if not related(a, b) and overlap(box[a], box[b]):
                m.overlaps.append(f"p{page + 1} 누르는 곳 '{label(nodes, nodes[a])[:20]}' {box[a]} ↔ "
                                  f"'{label(nodes, nodes[b])[:20]}' {box[b]}")
    for t in texts:
        for c in targets:
            if not related(t, c) and overlap(box[t], box[c]):
                m.overlaps.append(f"p{page + 1} 글자 '{nodes[t].text[:20]}' {box[t]} ↔ 누르는 곳 "
                                  f"'{label(nodes, nodes[c])[:20]}' {box[c]}")
    if shot is not None:
        # In the widget every text is a view of its own; a tile's description spans its icon too
        measure_colors(m, nodes, shot, texts + (described if m.mode == "app" else []), box)


def letters(hex_: str, bg: str) -> str | None:
    """The text token `hex_` is, when it can be letters on `bg`: white closer than WHITE_FILL_RATIO to its background
    is a cell, card or icon (white is surface.default too), so not letters."""
    fg = text_name(hex_)
    if fg is not None and any(not is_text_token(n) for n in BY_HEX[hex_]) and ratio(hex_, bg) < WHITE_FILL_RATIO:
        return None
    return fg


def measure_colors(m: Measured, nodes: list[N], shot: Shot, ids: list[int], box: dict[int, Box]) -> None:
    for i in ids:
        n = nodes[i]
        x1, y1, x2, y2 = box[i]
        w, h = x2 - x1, y2 - y1
        if w < 9 or h < 9:
            continue
        # A few pixels in from the box's edge: a neighbour's color, or the parent's color in a rounded corner, is not
        # taken for letters (white is both surface.default and ink.on-solid)
        hist = shot.colors(box[i], inset=max(2, min(w, h) // 12))
        total = sum(hist.values())
        if not total:
            continue
        bg_pixel, _ = hist.most_common(1)[0]
        bg = hex_of(bg_pixel)
        found_text = False
        for pixel, count in hist.most_common(80):
            if pixel == bg_pixel:
                continue
            hx = hex_of(pixel)
            if hx in BY_HEX:
                if count < MIN_TEXT_PIXELS:
                    continue
                fg = letters(hx, bg)
                if fg is None:
                    m.parts[(fill_name(hx), fill_name(bg))] = round(ratio(hx, bg), 2)
                    continue
                found_text = True
                key = (fg, fill_name(bg))
                r = round(ratio(hx, bg), 2)
                seen = m.pairs.get(key)
                if seen is None:
                    m.pairs[key] = {"ratio": r, "fg_hex": hx, "bg_hex": bg, "sample": (n.text or n.desc)[:40], "count": 1}
                else:
                    seen["count"] += 1
            elif count >= max(MIN_TEXT_PIXELS, total // 20):
                m.unknown[f"{hx} ('{(n.text or n.desc)[:16]}' 바탕 {bg})"] += 1
        if not found_text:
            m.unread.append(f"'{(n.text or n.desc)[:30]}' 바탕 {bg}")


def verdict(m: Measured) -> tuple[str, bool]:
    whole = [t for t in m.targets.values() if t["whole"]]
    small = [t for t in whole if t["w"] < TARGET_DP - 0.4 or t["h"] < TARGET_DP - 0.4]  # 1 px of rounding at 3x
    cut_only = [t for t in m.targets.values() if not t["whole"]
                and not any(w["label"] == t["label"] for w in whole)]
    # A cut target can have only its words' part off screen (a tile whose names have scrolled away)
    unnamed = [t for t in whole if not t["label"].strip()]
    low = {k: v for k, v in m.pairs.items() if v["ratio"] < TEXT_RATIO}
    parts = [f"쪽 {len(m.pages)}개" + (f"(목록 끝까지 가지 않고 앞 {len(m.pages)}쪽만 봄)" if m.truncated else "")]
    if whole:
        least = min(whole, key=lambda t: min(t["w"], t["h"]))
        parts.append(f"누르는 곳 {len({t['label'] for t in m.targets.values()})}가지(가장 작은 '{least['label'][:20]}' "
                     f"{least['w']:g}×{least['h']:g}dp)")
    else:
        parts.append("누르는 곳 없음")
    if small:
        parts.append("48dp 미만: " + ", ".join(f"'{t['label'][:24]}' {t['w']:g}×{t['h']:g}" for t in small[:6]))
    if cut_only:
        parts.append("잘린 채로만 봄: " + ", ".join(f"'{t['label'][:20]}'" for t in cut_only[:4]))
    if unnamed:
        parts.append(f"읽을 말 없는 곳 {len(unnamed)}")
    if m.mode != "shade":
        if m.edges:
            parts.append(f"화면 가장자리에 닿은 글 {len(m.edges)}: {m.edges[0]}")
        if m.overlaps:
            parts.append(f"겹침 {len(m.overlaps)}: {m.overlaps[0]}")
        if m.pairs:
            (fg, bg), worst = min(m.pairs.items(), key=lambda kv: kv[1]["ratio"])
            parts.append(f"글자 명암비 최소 {worst['ratio']:.2f}:1 ({fg} / {bg}, 쌍 {len(m.pairs)}개)")
        if low:
            parts.append("4.5:1 미만: " + ", ".join(f"{fg}/{bg} {v['ratio']:.2f}" for (fg, bg), v in low.items()))
        if m.unknown:
            parts.append(f"토큰 밖 색 {len(m.unknown)}가지")
    else:
        parts.append("시스템이 그림: 판정하지 않고 적기만 함")
    ok = m.mode == "shade" or not (small or unnamed or m.edges or m.overlaps or low)
    return "; ".join(parts), ok


def scroller_of(nodes: list[N], mode: str) -> N | None:
    if mode != "app":
        return None
    own = [n for n in nodes if n.pkg == PKG and n.scroll and n.h > 200]
    return max(own, key=lambda n: n.w * n.h, default=None)


def swipe_in(n: N, up: bool) -> None:
    x = (n.bounds[0] + n.bounds[2]) // 2
    top, bottom = n.bounds[1] + n.h * 15 // 100, n.bounds[1] + n.h * 85 // 100
    a, b = (bottom, top) if up else (top, bottom)
    sh(f"input swipe {x} {a} {x} {b} 500")
    time.sleep(1.2)


def signature(nodes: list[N], mode: str) -> tuple:
    return tuple((nodes[i].text, nodes[i].desc, nodes[i].bounds) for i in scope(nodes, mode))


RESULTS: list[Measured] = []


def measure(r: Report, sid: str, name: str, scale: str, mode: str = "app", max_pages: int = MAX_PAGES) -> tuple[str, bool]:
    """Photographs and judges every page of the screen on show, from the top of its list to the bottom (or to
    `max_pages` pages)."""
    m = Measured(sid, name, scale, mode)
    tag = f"{name}-{'100' if scale == '1.0' else '200'}"
    nodes, _ = full_dump()
    scroller = scroller_of(nodes, mode)
    if scroller is not None:
        # Up to the top until the list stops moving. A new text size makes the app again and a list keeps the place it
        # had at 100%, deeper at 200% (run 43576739894: four swipes left the history list's month, people and first
        # cell, and the time zone list's first rows, above the first page)
        for _ in range(TOP_SWIPES):
            before = signature(nodes, mode)
            swipe_in(scroller, up=False)
            nodes, _ = full_dump()
            if signature(nodes, mode) == before:
                break
            scroller = scroller_of(nodes, mode) or scroller
    last = None
    for page in range(max_pages + 1):
        shot = grab()
        nodes, xml = full_dump()
        sig = signature(nodes, mode)
        if sig == last:
            break
        if page == max_pages:
            m.truncated = True  # the list still moved after max_pages pages
            break
        r.shots += 1
        base = f"api{r.api}-{r.shots:02d}-{tag}-p{page + 1}"
        (r.out / f"{base}.png").write_bytes(shot.png())
        (r.out / f"{base}.xml").write_text(xml, encoding="utf-8")
        m.pages.append(base)
        judge_page(m, nodes, shot, page)
        last = sig
        scroller = scroller_of(nodes, mode)
        if scroller is None:
            break
        swipe_in(scroller, up=True)
    if not m.pages:
        raise RuntimeError("no page was captured")
    RESULTS.append(m)
    return verdict(m)


# ------------------------------------------------------------------------------------------------
# Moving around


def leave_to_home() -> None:
    """Back to the app's first screen, as screens_check.to_home, and BACK for a dialog: a dump holds only the active
    window, so with a dialog open the screen's [뒤로]/[닫기] is not in it (BACK closes the dialog without its action).
    When that does not get there, the app is started again."""
    for _ in range(6):
        nodes = dump_ui()
        if at_home(nodes):
            return
        back = next((n for n in nodes if n.desc in ("뒤로", "닫기")), None)
        if back is not None:
            tap(back)
        elif not app_in_front():
            sh(f"am start -n {ACTIVITY}")
            time.sleep(2)
        else:
            sh("input keyevent KEYCODE_BACK")
            time.sleep(1)
    ensure_home()


TODAY_TITLE = "오늘 기록"  # Labels.judgeTitle of a current cell: SCR-007's title


def judge_of(name: str) -> None:
    """First screen > `name`'s [⋯] > SCR-007, known by its title: with text 200% the buttons can be below the fold."""
    leave_to_home()
    tap(seek(desc_is(f"{name} 판정 고치기"), f"[⋯] of {name}"))
    if not ready(lambda ns: has_text(ns, TODAY_TITLE), 15):
        raise RuntimeError(f"SCR-007 of {name} did not open")


def hour_cell(n) -> bool:
    return bool(re.fullmatch(r"\d+시", n.desc))  # the picker's hour cells, read `9시` (JudgeScreen.TimePicker)


def shows_items(nodes) -> bool:
    """The widget shows at least one of the items (its buttons are read `나 종합비타민, …`, Labels.spoken)."""
    names = (MULTI, PROBIOTIC, BONE, BP, LONG)
    return any(n.desc.split(", ")[0].endswith(names) for n in nodes)


def ready(predicate, timeout: float = 20) -> bool:
    return predicate(wait_screen(predicate, timeout))


class A11ySteps(ShotSteps):
    """ShotSteps whose step may return (text, ok): a measured page that fails a check is a failed row, without the
    clean-up of a step that broke (no app stop: the flow stays where it is)."""

    def __call__(self, title: str, expected: str, fn, look: bool = False) -> bool:
        verdict_ok: dict[str, bool] = {}

        def run_fn():
            out = fn()
            if isinstance(out, tuple):
                verdict_ok["ok"] = out[1]
                return out[0]
            return out

        ok = super().__call__(title, expected, run_fn, look)
        if ok and verdict_ok.get("ok") is False:
            case, exp, actual, _ = self.r.rows[-1]
            self.r.rows[-1] = (case, exp, actual, False)
            self.spans[-1]["ok"] = False
            print(f"[FAIL] {case}: measured {actual}", flush=True)
            return False
        return ok


EXPECTED = ("누르는 곳 모두 48×48dp 이상, 모두 읽을 말 있음, 글이 화면 옆에 닿거나 겹치지 않음, "
            "글자 명암비 4.5:1 이상")


def both(steps: A11ySteps, r: Report, sid: str, name: str, title: str, open_fn, is_open, mode: str = "app",
         after_scale=None, expected: str = EXPECTED, max_pages: int = MAX_PAGES) -> None:
    """The screen at text 100%, then at 200%. At 200% the screen opened at 100% is measured again once the app has
    redrawn it; if it did not stay open (a state not kept across recreation), it is opened again at 200%."""

    def at(scale: str):
        def run():
            font_scale(scale)
            if after_scale:
                after_scale()
            if scale == SCALES[0] or not ready(is_open, 10):
                open_fn()
                if after_scale:
                    after_scale()
                if not ready(is_open, 20):
                    raise RuntimeError(f"{sid} {title} did not open")
            return measure(r, sid, name, scale, mode, max_pages)
        return run

    steps(f"{sid} {title} · 글자 100%", expected, at(SCALES[0]))
    steps(f"{sid} {title} · 글자 200%", expected, at(SCALES[1]))
    font_scale(SCALES[0])


def seed(names: str, start: str, person: str | None = None, weekly: str | None = None) -> str:
    extras = f"--es names '{names}' --es start {start}"
    if person:
        extras += f" --es person '{person}'"
    if weekly:
        extras += f" --es weekly {weekly}"
    return debug(ACTION_SEED, extras, "seeded")


# ------------------------------------------------------------------------------------------------
# Flow


def run(r: Report, steps: A11ySteps, apk: Path) -> None:
    base = datetime.now(ZONE).date()

    def at(day: int, hour: int, minute: int = 0) -> datetime:
        d = base + timedelta(days=day)
        return datetime(d.year, d.month, d.day, hour, minute, tzinfo=ZONE)

    # 0. phone setup, at the 360dp width from the start (wm density also re-lays the launcher)
    def setup() -> str:
        global DENSITY
        become_root()
        for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            sh(f"settings put global {setting} 0")
        sh("settings put secure show_ime_with_hard_keyboard 0", check=False)
        sh("settings put global heads_up_notifications_enabled 0", check=False)
        sh("svc power stayon true", check=False)
        sh("settings put system screen_off_timeout 1800000", check=False)
        width = int(re.findall(r"(\d+)x(\d+)", sh("wm size"))[-1][0])
        dpi = width * 160 // WIDTH_DP
        sh(f"wm density {dpi}")
        DENSITY = dpi / 160
        zone = set_zone()
        clock = set_clock(at(-7, 9, 0))
        font_scale(SCALES[0])
        night(False)
        uninstall()
        install(apk)
        return f"폭 {width}px, wm density {dpi} → {width * 160 // dpi}dp; 시간대 {zone}, 시계 {clock}"

    if not steps("0. 준비: 폭 360dp, 서울 시간대, -7일 09:00, 새로 설치", "폭 360dp, 시계가 바뀌고 앱이 깔림", setup):
        return

    # 1. the intro and the empty first screen
    def open_intro() -> None:
        launch_app(stop_at_intro=True)

    both(steps, r, "SCR-005", "intro", "첫 실행 안내", open_intro, lambda ns: has_text(ns, INTRO_MARK))

    def open_empty() -> None:
        nodes = dump_ui()
        if has_text(nodes, INTRO_MARK):
            tap_exact(INTRO_MARK)
        leave_to_home()

    both(steps, r, "SCR-004", "home-empty", "첫 화면, 항목 없음", open_empty,
         lambda ns: any(EMPTY_HOME in n.text for n in ns))

    # 2. the data, day -7
    def add_items() -> str:
        weekday = WEEKDAY_NAMES[base.weekday()]
        seeded = [seed(f"{MULTI},{PROBIOTIC}", "08:00"), seed(BONE, "08:00", weekly=weekday),
                  seed(BP, "20:00", person=MOM), seed(LONG, "08:00", person=MOM)]
        names = sorted(row[0] for row in query("SELECT name FROM intake WHERE archived_at IS NULL"))
        want = sorted([MULTI, PROBIOTIC, BONE, BP, LONG])
        if names != want:
            raise RuntimeError(f"items {names}, expected {want} (Korean through adb?)")
        reopen()
        for name in (BONE, MULTI):
            tap(row_button(name, "먹었어"))
            time.sleep(2)
        return f"{len(seeded)}번 넣음, 항목 {names}; -7일 {BONE}·{MULTI} 먹음"

    if not steps("2. 항목 다섯(대상자 둘), -7일 기록 둘", f"{want_names()} 다섯이 저장되고 -7일 기록 둘", add_items):
        return

    # 3. day 0 09:00: 엄마 in green, a dose and a skip
    def day0() -> str:
        clock = set_clock(at(0, 9, 0))
        reopen()
        open_people()
        tap(seek(desc_starts(f"{MOM}, "), f"{MOM}'s line"))
        if exact("대상자 수정", 10) is None:
            raise RuntimeError("the person form did not open")
        tap(seek(desc_is(GREEN), f"color {GREEN}"))
        tap(seek(text_is("저장"), "[저장]"))
        wait_screen(lambda ns: has_text(ns, "대상자 추가"), 15)
        leave_to_home()
        tap(row_button(MULTI, "먹었어"))
        time.sleep(2)
        judge_of(PROBIOTIC)
        tap(seek(text_is("건너뜀"), "[건너뜀]"))
        if exact("먹었어로 바꾸기", 10) is None:  # SCR-007 of a skipped cell
            raise RuntimeError(f"{PROBIOTIC} was not skipped")
        leave_to_home()
        kinds = query("SELECT i.name, d.kind FROM dose_record d JOIN intake i ON i.id = d.intake_id WHERE d.active = 1")
        return f"시계 {clock}; {MOM} 색 {GREEN}; 판정 {sorted(kinds)}"

    if not steps("3. 0일 09:00: 엄마 초록, 먹음 하나와 건너뜀 하나", "칸마다 먹음·건너뜀·미완료·주 1회 마지막 복용", day0):
        return

    # 4. every screen at 100% and 200%
    def home() -> None:
        leave_to_home()
        # A new text size makes the app again and its list keeps the place it had at 100% (run 43574423401: at 200%
        # the first card was above the fold and the screen was not known), so it is read from the top.
        for _ in range(3):
            if has_desc(dump_ui(), f"{ME} {MULTI}, "):
                return
            scroll_top()

    both(steps, r, "SCR-004", "home", "첫 화면 (먹음·건너뜀·미완료·주 1회)", home,
         lambda ns: at_home(ns) and has_desc(ns, f"{ME} {MULTI}, "))

    def item_new() -> None:
        leave_to_home()
        tap_scrolled("항목 추가")

    both(steps, r, "SCR-006", "item-new", "항목 추가", item_new, lambda ns: has_text(ns, "항목 추가") and has_desc(ns, "닫기"))

    def item_family() -> None:
        item_new()
        tap(seek(text_is("+ 가족"), "[+ 가족]"))

    both(steps, r, "SCR-006", "item-family", "항목 추가, [+ 가족] 펼침", item_family,
         lambda ns: any("가족 이름" in (n.text + n.desc) for n in ns))

    def item_edit() -> None:
        leave_to_home()
        node = tile(BP)
        if node is None:
            raise RuntimeError(f"tile {BP} not on the first screen")
        tap(node)

    both(steps, r, "SCR-006", "item-edit", "항목 수정", item_edit, lambda ns: has_text(ns, "항목 수정"))

    def archive_ask() -> None:
        item_edit()
        if not ready(lambda ns: has_text(ns, "항목 수정")):
            raise RuntimeError("the edit form did not open")
        tap(seek(text_is("보관"), "[보관]"))

    both(steps, r, "SCR-006", "item-archive-ask", "항목 보관 질문", archive_ask,
         lambda ns: any(n.text.endswith(ARCHIVE_ASK_END) for n in ns))

    def judge() -> None:
        judge_of(LONG)

    both(steps, r, "SCR-007", "judge", "판정 고치기 (판정 없음)", judge,
         lambda ns: has_text(ns, TODAY_TITLE) and not any(hour_cell(n) for n in ns))

    def judge_pick() -> None:
        judge()
        tap(seek(text_is("먹은 시각 고르기"), "[먹은 시각 고르기]"))
        seek(hour_cell, "the picker's hour cells")

    both(steps, r, "SCR-007", "judge-pick", "판정 고치기, 먹은 시각 고르기", judge_pick,
         lambda ns: has_text(ns, TODAY_TITLE) and any(hour_cell(n) for n in ns))

    def history() -> None:
        leave_to_home()
        open_history()

    both(steps, r, "SCR-008", "history", "지난 기록", history,
         lambda ns: has_text(ns, "지난 기록") and has_desc(ns, "뒤로") and not at_home(ns))

    def settings() -> None:
        leave_to_home()
        tap_exact("설정")

    both(steps, r, "SCR-009", "settings", "설정", settings, lambda ns: has_text(ns, "하루 기준"))

    def setting_row(prefix: str):
        def open_row() -> None:
            settings()
            if not ready(lambda ns: has_text(ns, "하루 기준")):
                raise RuntimeError("설정 did not open")
            tap(seek(desc_starts(prefix), f"설정 row {prefix}"))
        return open_row

    both(steps, r, "SCR-009", "settings-interval", "설정, 다시 알림 간격 고르기", setting_row("다시 알림 간격, "),
         lambda ns: has_text(ns, INTERVAL_NOTE))
    both(steps, r, "SCR-009", "settings-per-day", "설정, 하루 최대 고르기", setting_row("하루 최대, "),
         lambda ns: has_text(ns, PER_DAY_NOTE))
    both(steps, r, "SCR-009", "settings-quiet", "설정, 방해 금지 고르기", setting_row("방해 금지, "),
         lambda ns: has_text(ns, QUIET_NOTE))
    both(steps, r, "SCR-009", "settings-zone", "설정, 기준 시간대 고르기", setting_row("기준 시간대, "),
         lambda ns: has_text(ns, ZONE_NOTE), max_pages=ZONE_PAGES)

    def people() -> None:
        leave_to_home()
        open_people()

    both(steps, r, "SCR-010", "people", "대상자 관리", people,
         lambda ns: has_text(ns, "대상자 관리") and has_desc(ns, f"{MOM}, "))

    def person_edit() -> None:
        people()
        tap(seek(desc_starts(f"{MOM}, "), f"{MOM}'s line"))

    both(steps, r, "SCR-010", "person-edit", "대상자 수정", person_edit, lambda ns: has_text(ns, "대상자 수정"))

    # The widget: placed with two people, so the launcher opens the person picker (전체 picked)
    def place() -> str:
        leave_to_home()
        try:
            picker = place_widget(r, "widget")
        except RuntimeError as exc:
            r.note(f"위젯 놓기 첫 시도 실패, 20초 뒤 한 번 더: {exc}")
            go_home()
            time.sleep(20)
            picker = place_widget(r, "widget-retry")
        if not shows_items(wait_screen(shows_items, 20)):
            raise RuntimeError("the widget does not show the items")
        return f"대상자 고르기 화면 {'열림' if picker else '안 열림'}, 전체로 놓음"

    widget_placed = steps("SCR-001 위젯 놓기 (대상자 둘, 전체)", "홈 화면에 4×2 위젯", place)

    if widget_placed:
        def to_widget() -> None:
            go_home()

        both(steps, r, "SCR-001", "widget", "홈 화면 위젯 4×2", to_widget, shows_items, mode="widget",
             after_scale=refresh_widgets)

        def open_picker() -> None:
            if not has_text(dump_ui(), PICK_TITLE):
                wipe_widget_choices()
                go_home()
                line = exact(RECONFIGURE, 15)
                if line is None:
                    raise RuntimeError(f"'{RECONFIGURE}' not on the widget")
                tap(line)

        both(steps, r, "SCR-002", "widget-picker", "위젯 대상자 고르기", open_picker, lambda ns: has_text(ns, PICK_TITLE))

        def pick_all() -> str:
            if has_text(dump_ui(), PICK_TITLE):
                tap(seek(desc_starts("전체, "), "전체 in the picker"))
            go_home()
            return "전체를 고름"

        steps("SCR-002 전체 고르기 (되돌림)", "위젯이 다시 전체", pick_all)

    # The reminder (System UI): permission granted, then the clock at the next planned reminder and the alarm's broadcast
    # sent. The app shows a reminder only within 10 minutes of its time (ReminderAlarm.WINDOW): run 43570269368 sent the
    # broadcast at 09:20, after 09:00's had passed, and none came. Two items are pending then: 골다공증약 and 엄마's
    # 글루코사민 콘드로이친.
    def reminder_ready(nodes) -> bool:
        return any(n.text.endswith(TITLE_END) for n in nodes)

    def bring_reminders() -> str:
        planned = [a.millis for a in alarms(REMINDER_ALARM) if a.millis is not None and not a.windowed]
        due = datetime.fromtimestamp(min(planned) / 1000, ZONE) if planned else at(0, 10, 0)
        clock = set_clock(due + timedelta(seconds=20))
        sh(f"am broadcast -n {REMINDER_RECEIVER} -a {REMINDER_ALARM}")
        shown = doses(wait_posted(lambda p: bool(doses(p)), 60))
        if not shown:
            raise RuntimeError(f"no reminder was posted (clock {clock})")
        return f"다음 알림 {due:%H:%M}, 시계 {clock}; 알림 {len(shown)}개: " + ", ".join(sorted(p.title for p in shown))

    def open_reminder() -> None:
        expand_shade()
        if not ready(reminder_ready, 10):
            collapse_shade()
            expand_shade()
        for _ in range(3):  # open our reminders so their buttons show
            nodes = parse(adb("exec-out", "cat", UI_XML, check=False)) if dump_ui() else []
            title_ids = [n.i for n in nodes if n.text.endswith(TITLE_END)]
            buttons = [n for n in nodes if n.text in ACTION_LABELS]
            expanders = [n for n in nodes if n.rid.endswith(":id/expand_button") and "ollapse" not in n.desc
                         and any(abs(n.bounds[1] - nodes[t].bounds[1]) < 300 for t in title_ids)]
            if buttons or not expanders:
                break
            tap(expanders[0])

    def grant() -> str:
        sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS", check=False)
        leave_to_home()
        go_home()
        return "알림 권한 줌"

    steps("SCR-003 준비: 알림 권한", "권한 줌", grant)
    if steps("SCR-003 준비: 다음 알림 시각으로 시계, 알림 알람 방송", "남은 두 항목의 알림이 뜸", bring_reminders):
        both(steps, r, "SCR-003", "reminder", "알림 (알림 창을 내림)", open_reminder, reminder_ready, mode="shade",
             expected="시스템이 그리는 화면: 누르는 곳과 읽는 말을 적음(판정 안 함)")
    collapse_shade()


def want_names() -> str:
    return ", ".join((MULTI, PROBIOTIC, BONE, BP, LONG))


# ------------------------------------------------------------------------------------------------
# Writing


def write_detail(out: Path) -> None:
    lines = ["# 접근성 점검 자세히", "", f"폭 {WIDTH_DP}dp. 누르는 곳은 {TARGET_DP}dp, 글자 명암비는 {TEXT_RATIO}:1이 기준.", ""]
    for m in RESULTS:
        text, ok = verdict(m)
        lines += [f"## {m.sid} {m.name} · 글자 {'100%' if m.scale == '1.0' else '200%'}", "",
                  f"- 쪽: {', '.join(m.pages)}", f"- 판정: {'통과' if ok else '**실패**'} ({text})", ""]
        if m.targets:
            lines += ["| 읽는 말 | 역할·상태 | 크기 dp | 쪽 |", "| --- | --- | --- | --- |"]
            for t in sorted(m.targets.values(), key=lambda t: t["pages"][0]):
                size = f"{t['w']:g}×{t['h']:g}" + ("" if t["whole"] else " (잘렸을 수 있음)")
                if t["whole"] and (t["w"] < TARGET_DP - 0.4 or t["h"] < TARGET_DP - 0.4):
                    size = f"**{size}**"
                said = t["label"].replace("|", "\\|").replace("\n", " ") or "**(없음)**"
                lines.append(f"| {said} | {t['role'] or '-'} | {size} | {','.join(map(str, sorted(set(t['pages']))))} |")
            lines.append("")
        if m.pairs:
            lines += ["| 글자색 | 바탕색 | 명암비 | 본 글 (예) | 글 수 |", "| --- | --- | --- | --- | --- |"]
            for (fg, bg), v in sorted(m.pairs.items(), key=lambda kv: kv[1]["ratio"]):
                mark = "" if v["ratio"] >= TEXT_RATIO else " **미달**"
                sample = v["sample"].replace("|", "\\|").replace("\n", " ")
                lines.append(f"| `{fg}` {v['fg_hex']} | `{bg}` {v['bg_hex']} | {v['ratio']:.2f}:1{mark} | {sample} | {v['count']} |")
            lines.append("")
        extra = []
        if m.parts:
            extra.append("글자 아닌 토큰 색: " + ", ".join(f"{fg}/{bg} {v:.2f}" for (fg, bg), v in sorted(m.parts.items())))
        if m.unknown:
            extra.append("토큰 밖 색(글 상자의 5% 이상): " + ", ".join(f"{k} ×{v}" for k, v in m.unknown.most_common(6)))
        if m.unread:
            extra.append("글자색을 토큰에서 찾지 못한 글: " + "; ".join(m.unread[:8]))
        if m.edges:
            extra.append("화면 옆에 닿은 글: " + "; ".join(m.edges[:8]))
        if m.overlaps:
            extra.append("겹침: " + "; ".join(m.overlaps[:8]))
        lines += [f"- {e}" for e in extra] + ([""] if extra else [])
    (out / "detail.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (out / "a11y.json").write_text(json.dumps([m.to_json() for m in RESULTS], ensure_ascii=False, indent=1),
                                   encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--apk", type=Path, help="debug APK")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--tokens-only", action="store_true", help="write tokens.md only, no emulator")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    tokens = "# 토큰 색 쌍의 명암비\n\n" + f"`{TOKENS_FILE.relative_to(TOKENS_FILE.parents[1])}`에서 셈 (WCAG 2.1).\n\n" + tokens_table()
    (args.out / "tokens.md").write_text(tokens, encoding="utf-8")
    if args.tokens_only:
        print(tokens)
        return 0
    if args.apk is None:
        parser.error("--apk is needed without --tokens-only")
    adb("wait-for-device")
    wait_boot()  # also counts this boot, so become_root waits for the emulator's own commands after it
    api = api_level()
    r = Report(args.out, api, title=f"접근성 점검 API {api} (폭 {WIDTH_DP}dp, 글자 100%·200%)")
    steps = A11ySteps(r)
    try:
        run(r, steps, args.apk)
    except Exception as exc:  # the report is written either way
        traceback.print_exc()
        r.check("흐름", "끝까지 감", f"{type(exc).__name__}: {exc}", False)
    finally:
        try:
            font_scale(SCALES[0])
            sh("wm density reset", check=False)
        except Exception:
            pass
        if KEYBOARD_NOTES:
            r.note("; ".join(KEYBOARD_NOTES))
        r.write()  # also lists the system dialogs closed and adb's drops
        write_detail(args.out)
        (args.out / "steps.json").write_text(json.dumps(steps.spans, ensure_ascii=False, indent=1), encoding="utf-8")
        LOG_PARTS.append(adb("logcat", "-d", "-v", "time", check=False))
        (args.out / f"api{api}-logcat.txt").write_text("\n".join(LOG_PARTS), encoding="utf-8", errors="replace")
    return 1 if r.failed else 0


if __name__ == "__main__":
    sys.exit(main())
