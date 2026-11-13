"""Render TUI screen dumps (JSON) into PNG images for human review.

The e2e test writes one JSON file per scenario to target/tui-screens/.
This script turns each into a terminal-looking PNG. It is evidence only:
the pass/fail gate is the character + role snapshot in TuiSnapshots.java.

Usage:
    python e2e/screens.py target/tui-screens
"""
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

FONT_PATHS = [
    r"C:\Windows\Fonts\consola.ttf",
    r"C:\Windows\Fonts\DejaVuSansMono.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
]

ROLE_COLORS = {
    "DEFAULT": (205, 214, 227),
    "ACCENT": (86, 182, 194),
    "DIM": (110, 122, 140),
    "WARN": (248, 81, 73),
    "OK": (63, 185, 80),
    "SELECTED": (230, 237, 243),
}

SELECTED_BG = (30, 64, 140)
WINDOW_BG = (13, 18, 28)
BAR_BG = (22, 29, 43)
TITLES = {
    "main": "xac tui — main screen",
    "apply": "xac tui — apply preview",
    "destroy": "xac tui — destroy confirmation",
    "edit": "xac tui — structured editor",
    "edit-error": "xac tui — validation error",
    "narrow": "xac tui — narrow terminal (72x18)",
}


def main(directory):
    src = Path(directory)
    out_dir = src
    for path in sorted(src.glob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        image = render(data)
        target = out_dir / (path.stem + ".png")
        image.save(target)
        print(f"{target} {image.width}x{image.height}")


def render(data):
    cols = data["cols"]
    rows = data["rows"]
    font_path = next(p for p in FONT_PATHS if Path(p).exists())
    font = ImageFont.truetype(font_path, 18)
    adv = font.getlength("M")
    line_height = 26
    pad = 18
    bar = 38
    width = int(pad * 2 + cols * adv)
    height = bar + pad * 2 + rows * line_height

    image = Image.new("RGB", (width, height), WINDOW_BG)
    draw = ImageDraw.Draw(image)
    draw.rectangle([0, 0, width, bar], fill=BAR_BG)
    for index, color in enumerate([(255, 95, 86), (255, 189, 46), (39, 201, 63)]):
        x = 18 + index * 22
        draw.ellipse([x, bar // 2 - 6, x + 12, bar // 2 + 6], fill=color)
    draw.text((pad + 58, bar // 2 - 10), TITLES.get(data["name"], data["name"]),
              font=font, fill=(180, 190, 205))
    draw.line([0, bar, width, bar], fill=(48, 58, 78))

    for cell in data["cells"]:
        x = cell["x"]
        y = cell["y"]
        role = cell["role"]
        left = pad + x * adv
        top = bar + pad + y * line_height
        if role == "SELECTED":
            draw.rectangle([left, top, left + adv * 2, top + line_height - 2], fill=SELECTED_BG)
        draw.text((left, top), cell["ch"], font=font,
                  fill=ROLE_COLORS.get(role, ROLE_COLORS["DEFAULT"]))
    return image


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "target/tui-screens")
