#!/usr/bin/env python3
"""Render approximate 1024px previews of LabImporter/AppIcon.icon (light/dark/tinted).

These are flat approximations for design review on machines without Icon Composer:
they stack the layer SVGs over the background fill but do NOT reproduce the system's
Liquid Glass specular/shadow/translucency rendering. Requires `cairosvg` and `pillow`.

    python3 tools/render_icon_previews.py [OUT_DIR]
"""
import io
import sys
from pathlib import Path

import cairosvg
from PIL import Image, ImageChops, ImageDraw, ImageOps

ROOT = Path(__file__).resolve().parent.parent
ICON = ROOT / "LabImporter" / "AppIcon.icon" / "Assets"
LAYERS = ["page.svg", "flask.svg", "heart.svg"]  # back to front
SIZE = 1024


def gradient(top, bottom):
    img = Image.new("RGB", (SIZE, SIZE))
    px = img.load()
    for y in range(SIZE):
        t = y / (SIZE - 1)
        row = tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        for x in range(SIZE):
            px[x, y] = row
    return img.convert("RGBA")


def layer(name):
    png = cairosvg.svg2png(url=str(ICON / name), output_width=SIZE, output_height=SIZE)
    return Image.open(io.BytesIO(png)).convert("RGBA")


def mask(radius=230):
    m = Image.new("L", (SIZE, SIZE), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, SIZE - 1, SIZE - 1), radius, fill=255)
    return m


def compose(bg, tint=None):
    canvas = bg.copy()
    for name in LAYERS:
        img = layer(name)
        if tint:
            gray = ImageOps.grayscale(img.convert("RGB"))
            gray = ImageOps.autocontrast(gray, cutoff=0)
            colored = ImageOps.colorize(gray, black=(40, 40, 46), white=tint).convert("RGBA")
            colored.putalpha(img.getchannel("A"))
            img = colored
        canvas = Image.alpha_composite(canvas, img)
    return canvas


def save(img, path, rounded=True):
    out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask() if rounded else None)
    out.save(path)


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "docs" / "app-icon"
    out.mkdir(parents=True, exist_ok=True)
    light = compose(gradient((89, 166, 255), (46, 130, 255)))
    dark = compose(gradient((18, 30, 62), (8, 14, 34)))
    tinted = compose(gradient((50, 50, 56), (24, 24, 28)), tint=(255, 255, 255))
    for name, img in (("icon-light", light), ("icon-dark", dark), ("icon-tinted", tinted)):
        save(img, out / f"{name}.png")
    # Square (unmasked) renders for the in-app AppIconPreview image set.
    save(light, out / "icon-light-square.png", rounded=False)
    save(dark, out / "icon-dark-square.png", rounded=False)
    sheet = Image.new("RGBA", (SIZE * 3 + 160, SIZE + 80), (236, 238, 242, 255))
    for i, n in enumerate(("icon-light", "icon-dark", "icon-tinted")):
        sheet.alpha_composite(Image.open(out / f"{n}.png"), (40 + i * (SIZE + 40), 40))
    sheet.convert("RGB").save(out / "overview.png")


if __name__ == "__main__":
    main()
