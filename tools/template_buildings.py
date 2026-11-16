#!/usr/bin/env python3
"""Draws the template pack's placeholder building sheets: a plain block on its tile in every cell.

Each cell gets its own shade so the cells can be told apart in the game. Run from the repo root:
    python3 tools/template_buildings.py
"""
from PIL import Image, ImageDraw

CELL = 256
STAND_X, STAND_Y = 128, 160

# name: (walls, roof, height in pixels)
SHEETS = {
    "general_store": ((176, 120, 80), (110, 60, 50), 70),
    "salmon_cannery": ((150, 160, 170), (90, 100, 110), 50),
    "salmon_dock": ((120, 90, 60), (80, 60, 40), 12),
}


def shade(c, k):
    return tuple(max(0, min(255, int(v * k))) for v in c)


def block(d, ox, oy, walls, roof, h, k):
    # The tile's diamond, 200 by 100, its middle on the stand point.
    hw, hh = 100, 50
    cx, cy = ox + STAND_X, oy + STAND_Y
    n, e, s, w = (cx, cy - hh), (cx + hw, cy), (cx, cy + hh), (cx - hw, cy)
    up = lambda p: (p[0], p[1] - h)
    d.polygon([w, s, up(s), up(w)], fill=shade(walls, 0.8 * k))
    d.polygon([s, e, up(e), up(s)], fill=shade(walls, 1.0 * k))
    d.polygon([up(n), up(e), up(s), up(w)], fill=shade(roof, k))


def main():
    for name, (walls, roof, h) in SHEETS.items():
        img = Image.new("RGBA", (CELL * 4, CELL * 4), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        for c in range(16):
            block(d, c % 4 * CELL, c // 4 * CELL, walls, roof, h, 0.7 + 0.3 * (c % 4) / 3)
        img.save(f"packs/template/sprites/{name}.png", optimize=True)


if __name__ == "__main__":
    main()
