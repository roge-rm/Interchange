#!/usr/bin/env python3
"""Draws the template pack's placeholder building sheets: a plain block on its tile in cells 1 to 4.

Cells follow packs/SPEC.md: cell 1 is a building facing the view's upper left edge (XM), 2 the lower right (XP),
3 the upper right (YM), 4 the lower left (YP). A dark arrow on the roof points the way it faces. Run from the repo root:
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


# Where each edge's middle is from the tile's middle, in half widths and half heights: XM, XP, YM, YP.
EDGE_MIDDLE = [(-0.5, -0.5), (0.5, 0.5), (0.5, -0.5), (-0.5, 0.5)]


def arrow(d, ox, oy, h, edge):
    # A triangle on the roof's middle, its point towards the edge the building faces.
    hw, hh = 100, 50
    cx, cy = ox + STAND_X, oy + STAND_Y - h
    tx, ty = EDGE_MIDDLE[edge]
    tip = (cx + tx * hw * 1.4, cy + ty * hh * 1.4)
    px, py = -ty * 0.5, tx * 0.5
    d.polygon([tip, (cx + px * hw * 0.5, cy + py * hh * 1.0), (cx - px * hw * 0.5, cy - py * hh * 1.0)], fill=(40, 30, 30))


def main():
    for name, (walls, roof, h) in SHEETS.items():
        img = Image.new("RGBA", (CELL * 4, CELL * 4), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        for c in range(4):
            block(d, c * CELL, 0, walls, roof, h, 1.0)
            arrow(d, c * CELL, 0, h, c)
        img.save(f"packs/template/sprites/{name}.png", optimize=True)


if __name__ == "__main__":
    main()
