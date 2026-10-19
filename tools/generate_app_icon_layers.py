"""Generate the LabImporter app icon layers (1024x1024 SVG) into <assets_dir>.

Usage: python3 tools/generate_app_icon_layers.py LabImporter/AppIcon.icon/Assets

Colors, glass, shadows and appearance variants live in AppIcon.icon/icon.json;
the SVG fills here are placeholders that icon.json overrides.
"""
import os
import sys

out = sys.argv[1]
DX, DY = -39, -26  # shift composition so its bounding box is centered


def svg(body):
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" '
            'viewBox="0 0 1024 1024">\n' + body + '\n</svg>\n')


def rrect(x, y, w, h, r, fill):
    return (f'  <rect x="{x + DX}" y="{y + DY}" width="{w}" height="{h}" '
            f'rx="{r}" fill="{fill}"/>')


# 1 — lab report card (back)
card = rrect(232, 196, 440, 540, 76, "#FFFFFF")

# 2 — result rows printed on the card
rows = "\n".join([
    rrect(304, 290, 296, 48, 24, "#2F6BFF"),
    rrect(304, 386, 210, 48, 24, "#2F6BFF"),
    rrect(304, 482, 150, 48, 24, "#2F6BFF"),
])

# 3 — heart (front), rounded tip, defined in unit space then scaled
W, CX, TOP = 400, 680, 520
pts = [
    ("M", [(-0.035, 0.862)]),
    ("C", [(-0.16, 0.77), (-0.5, 0.56), (-0.5, 0.295)]),
    ("C", [(-0.5, 0.125), (-0.38, 0.0), (-0.245, 0.0)]),
    ("C", [(-0.125, 0.0), (-0.045, 0.07), (0.0, 0.165)]),
    ("C", [(0.045, 0.07), (0.125, 0.0), (0.245, 0.0)]),
    ("C", [(0.38, 0.0), (0.5, 0.125), (0.5, 0.295)]),
    ("C", [(0.5, 0.56), (0.16, 0.77), (0.035, 0.862)]),
    ("Q", [(0.0, 0.89), (-0.035, 0.862)]),
]


def p(x, y):
    return f"{CX + x * W + DX:.1f} {TOP + y * W + DY:.1f}"


d = " ".join(cmd + " " + " ".join(p(*xy) for xy in xy_list) for cmd, xy_list in pts) + " Z"
heart = f'  <path fill="#FF375F" d="{d}"/>'

files = {"1-card.svg": card, "2-rows.svg": rows, "3-heart.svg": heart}
for name, body in files.items():
    with open(os.path.join(out, name), "w") as f:
        f.write(svg(body))
print("wrote", ", ".join(files))
