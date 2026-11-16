"""Store images for the Play listing (TASK-037, TASK-045), made from the artifact of the store screenshot run
(.github/workflows/store-shots.yml, scripts/emulator/store_shots.py):

    python3 scripts/release/store_images.py shots <artifact dir>
    python3 scripts/release/store_images.py graphic <artifact dir>

- shots: each store-N-name.png, the screen as captured (with an alpha channel), becomes
  docs/release/listing/ko-KR/images/phoneScreenshots/N-name.png, a 24-bit PNG. Play takes "JPEG or 24-bit PNG (no
  alpha)" (docs/release/store-listing.md 4.1). Each one has to be 1080x1920.
- graphic: the feature graphic, docs/release/listing/ko-KR/images/featureGraphic.png, 1024x500 and 24-bit. On the
  icon's blue: the store icon (icon.png), the app name and the intro's headline (Labels.APP_NAME and
  Labels.INTRO_HEADLINE, read from the app's source; the headline is also the short description's first sentence),
  and the widget of store 1, cut at its bounds in that picture's screen dump. No phone frame (Play advises against
  device imagery).

Fonts for the graphic: Noto Sans KR (SIL Open Font License 1.1) from the Google Fonts API, with only the letters it
draws (the API's `text` parameter). It is the Korean design of Noto Sans CJK, the phone's Korean font in the pictures.
Needs Pillow and the network.
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
IMAGES = ROOT / "docs/release/listing/ko-KR/images"
LABELS = ROOT / "shared/src/commonMain/kotlin/com/judangbang/pillbadeum/status/Labels.kt"
SHOT_SIZE = (1080, 1920)
GRAPHIC_SIZE = (1024, 500)
BLUE = (0x2B, 0x59, 0xC3)  # the launcher icon's background (res/values/ic_launcher_background.xml)
WHITE = (255, 255, 255)
STORE_SHOT = re.compile(r"store-(\d+)-([a-z0-9-]+)\.png")
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def label(name: str) -> str:
    """A `const val` string of Labels.kt, with its ${NBSP} as the character."""
    m = re.search(rf'const val {name}: String = "([^"]*)"', LABELS.read_text(encoding="utf-8"))
    if m is None:
        raise SystemExit(f"{name} not in {LABELS}")
    return m.group(1).replace("${NBSP}", " ")


def shots(artifact: Path) -> None:
    out = IMAGES / "phoneScreenshots"
    out.mkdir(parents=True, exist_ok=True)
    found = sorted((int(m.group(1)), m.group(2), p) for p in artifact.rglob("store-*.png")
                   if (m := STORE_SHOT.fullmatch(p.name)))
    if not found:
        raise SystemExit(f"no store-N-name.png under {artifact}")
    for n, name, path in found:
        with Image.open(path) as im:
            if im.size != SHOT_SIZE:
                raise SystemExit(f"{path.name} is {im.size[0]}x{im.size[1]}, not {SHOT_SIZE[0]}x{SHOT_SIZE[1]}")
            rgb = im.convert("RGB")
        target = out / f"{n}-{name}.png"
        rgb.save(target, optimize=True)
        print(f"{target.relative_to(ROOT)}: {rgb.size[0]}x{rgb.size[1]} {rgb.mode}, {target.stat().st_size // 1024} KB")


def font(weight: int, text: str, cache: Path) -> Path:
    """Noto Sans KR at `weight` with the letters of `text`: the API serves TrueType to a client that names no
    browser."""
    letters = "".join(sorted(set(text) - {" ", " "}))
    query = urlencode({"family": f"Noto Sans KR:wght@{weight}", "text": letters})
    css = urlopen(Request(f"https://fonts.googleapis.com/css2?{query}"), timeout=30).read().decode()
    m = re.search(r"url\((https://[^)]+)\) format\('truetype'\)", css)
    if m is None:
        raise SystemExit(f"no TrueType font in the Google Fonts answer: {css[:200]}")
    path = cache / f"noto-sans-kr-{weight}.ttf"
    path.write_bytes(urlopen(m.group(1), timeout=30).read())
    return path


def widget_box(dump: Path) -> tuple[int, int, int, int]:
    """The bounds of the widget in a screen dump: the launcher's host read as the app's name (Pixel Launcher)."""
    name = label("APP_NAME")
    for el in ET.parse(dump).getroot().iter("node"):
        if "AppWidgetHostView" in el.get("class", "") and el.get("content-desc") == name:
            m = BOUNDS.fullmatch(el.get("bounds", ""))
            if m:
                x1, y1, x2, y2 = (int(v) for v in m.groups())
                return x1, y1, x2, y2
    raise SystemExit(f"no widget host read as {name} in {dump.name}")


def corner_radius(widget: Image.Image) -> int:
    """The radius of the widget's rounded corners: on its top row the white background starts where the corner's
    curve ends. 42 px (16 dp on the Pixel 2) when the row gives nothing likely."""
    rgb = widget.convert("RGB")
    for x in range(rgb.width // 3):
        if rgb.getpixel((x, 0)) == WHITE:
            return x if 12 <= x <= 120 else 42
    return 42


def rounded(size: tuple[int, int], radius: int, scale: int = 4) -> Image.Image:
    """An anti-aliased rounded rectangle mask, drawn large and scaled down."""
    big = Image.new("L", (size[0] * scale, size[1] * scale), 0)
    ImageDraw.Draw(big).rounded_rectangle((0, 0, big.width - 1, big.height - 1), radius * scale, fill=255)
    return big.resize(size, Image.LANCZOS)


def graphic(artifact: Path) -> None:
    pictures = sorted(artifact.rglob("store-1-*.png"))
    dumps = sorted(artifact.rglob("*store-1-*.xml"))
    if not pictures or not dumps:
        raise SystemExit(f"store 1 and its screen dump not under {artifact}")
    name, headline = label("APP_NAME"), label("INTRO_HEADLINE")
    first, second = (part.strip() for part in headline.split(",", 1))
    lines = (f"{first},", second)

    w, h = GRAPHIC_SIZE
    canvas = Image.new("RGB", (w, h), BLUE)
    draw = ImageDraw.Draw(canvas)

    # Right: the widget, two fifths of the width, with a soft shadow
    with Image.open(pictures[0]) as shot:
        box = widget_box(dumps[0])
        widget = shot.convert("RGB").crop(box)
    radius = corner_radius(widget)
    scale = 430 / widget.width
    size = (430, round(widget.height * scale))
    if size[1] > h - 80:
        scale = (h - 80) / widget.height
        size = (round(widget.width * scale), h - 80)
    widget = widget.resize(size, Image.LANCZOS)
    mask = rounded(size, max(1, round(radius * scale)))
    x, y = w - 56 - size[0], (h - size[1]) // 2
    shadow = Image.new("L", (w, h), 0)
    shadow.paste(mask, (x, y + 10))
    shadow = shadow.filter(ImageFilter.GaussianBlur(18)).point(lambda v: v * 45 // 100)
    canvas.paste(Image.new("RGB", (w, h), (0x12, 0x2A, 0x66)), (0, 0), shadow)
    canvas.paste(widget, (x, y), mask)

    # Left: the icon and the name side by side, the headline under them
    with tempfile.TemporaryDirectory() as tmp:
        name_font = ImageFont.truetype(str(font(800, name, Path(tmp))), 92)
        line_font = ImageFont.truetype(str(font(700, "".join(lines), Path(tmp))), 40)
        icon_size, gap, left = 112, 28, 64
        name_box = draw.textbbox((0, 0), name, font=name_font)
        line_boxes = [draw.textbbox((0, 0), line, font=line_font) for line in lines]
        line_height = 58
        block = icon_size + 40 + line_height * len(lines)
        top = (h - block) // 2
        with Image.open(IMAGES / "icon.png") as icon:
            small = icon.convert("RGBA").resize((icon_size, icon_size), Image.LANCZOS)
        icon_mask = rounded((icon_size, icon_size), 26)
        ring = rounded((icon_size + 4, icon_size + 4), 28)
        canvas.paste(Image.new("RGB", ring.size, (0x5B, 0x80, 0xD6)), (left - 2, top - 2), ring)
        canvas.paste(small.convert("RGB"), (left, top), icon_mask)
        name_x = left + icon_size + gap
        name_y = top + (icon_size - (name_box[3] - name_box[1])) // 2 - name_box[1]
        draw.text((name_x, name_y), name, font=name_font, fill=WHITE)
        for i, (line, lb) in enumerate(zip(lines, line_boxes)):
            draw.text((left, top + icon_size + 40 + i * line_height - lb[1]), line, font=line_font, fill=(0xE4, 0xEC, 0xFF))
        right_edge = max(left + lb[2] - lb[0] for lb in line_boxes)
        right_edge = max(right_edge, name_x + name_box[2] - name_box[0])
        if right_edge > x - 24:
            raise SystemExit(f"the words reach x {right_edge}, into the widget at x {x}")

    target = IMAGES / "featureGraphic.png"
    canvas.save(target, optimize=True)
    print(f"{target.relative_to(ROOT)}: {w}x{h} {canvas.mode}, {target.stat().st_size // 1024} KB; widget {box} "
          f"radius {radius} px, shown at {size[0]}x{size[1]}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("what", choices=("shots", "graphic"))
    parser.add_argument("artifact", type=Path, help="the extracted artifact store-shots-api34")
    args = parser.parse_args()
    (shots if args.what == "shots" else graphic)(args.artifact)
    return 0


if __name__ == "__main__":
    sys.exit(main())
