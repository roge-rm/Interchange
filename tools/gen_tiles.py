#!/usr/bin/env python3
"""Draws Interchange's tiles and packs them into the atlases.

    uv run --with pillow tools/gen_tiles.py

Every sprite is drawn at the sharpest level, a tile 128 px wide, then halved
for each level after it, down to 8 px, and written as one atlas a level to
shared/src/commonMain/composeResources/files/, with map/Atlas.kt saying which
cell holds what.

Sprites are drawn as the camera sees them, in view coordinates: u runs down to
the right of the screen, v down to the left, so the same sprites serve all four
view directions. Light comes from the top left of the screen, as in Infill.

Every sprite is a cell of the same size. Its anchor, at (HW, STEP), is the
tile's top corner at the height the sprite stands on, so the game places a
sprite with that point on the tile's top corner.
"""
import math
import random
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
OUT_PNG = ROOT / "shared/src/commonMain/composeResources/files"
OUT_KT = ROOT / "shared/src/commonMain/kotlin/com/rm/interchange/map/Atlas.kt"

# The sharpest level: half a tile's width, half its height, and a step of height, in pixels.
HW = 64
HH = HW // 2
STEP = HW // 4
LEVELS = 5
CELL_W = 2 * HW
CELL_H = 96
COLUMNS = 8


def c(h, a=255):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5)) + (a,)


def shade(col, f):
    return tuple(max(0, min(255, int(v * f))) for v in col[:3]) + (col[3],)


# ---- palettes, from Infill's summer look ------------------------------------

GRASS = [c("#5a9a3c"), c("#4f8c34"), c("#66a846")]
SAND = [c("#d8c690"), c("#c9b47e"), c("#e4d5a3")]
WATER = c("#3a6fb0")
RIPPLE = c("#5a8fcc")
STONE_LIT = c("#b0a794")
STONE_DARK = c("#8a8270")
MORTAR = c("#6e675a")
OUTLINE = c("#3b3731")
GRAVEL = [c("#8c8478"), c("#7a7368"), c("#9d968a")]
SLEEPER = c("#6b4a30")
SLEEPER_TOP = c("#80603f")
RAIL = c("#d9d4cc")
RAIL_SHADE = c("#5a5650")

# Light from the top left of the screen and above, in view coordinates (u, v, up).
LIGHT = (-1.0, -0.35, 1.6)
# A step of height in tile widths, for shading slopes.
STEP_RISE = 0.5

KINDS = ["grass0", "grass1", "grass2", "grass3", "sand", "water"]


# ---- geometry -----------------------------------------------------------------

def proj(u, v, h):
    """A point in the tile, u and v from 0 to 1, h in steps above the anchor, to cell pixels."""
    return (HW + (u - v) * HW, STEP + (u + v) * HH - h * STEP)


# View corners in shape bit order: top (0, 0), right (1, 0), bottom (1, 1), left (0, 1).
CORNERS = [(0, 0), (1, 0), (1, 1), (0, 1)]


def offsets(shape):
    return [(shape >> i) & 1 for i in range(4)]


def height_at(offs, u, v):
    t, r, b, l = offs
    return (t * (1 - u) + r * u) * (1 - v) + (l * (1 - u) + b * u) * v


def brightness(p0, p1, p2):
    """How lit a triangle of (u, v, h) points is, 1 for flat ground."""
    ax, ay, az = (p1[i] - p0[i] for i in range(3))
    bx, by, bz = (p2[i] - p0[i] for i in range(3))
    nx, ny, nz = ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx
    if nz < 0:
        nx, ny, nz = -nx, -ny, -nz
    n = math.sqrt(nx * nx + ny * ny + nz * nz)
    lx, ly, lz = LIGHT
    l = math.sqrt(lx * lx + ly * ly + lz * lz)
    d = (nx * lx + ny * ly + nz * lz) / (n * l)
    flat = lz / l
    # A little over a quarter of the difference a real slope would make, so hills read without striping the land.
    return max(0.84, min(1.12, 1 + 0.28 * (d / flat - 1)))


def triangles(shape):
    """The tile's top as two triangles of (u, v, h), folded along whichever diagonal has its ends level."""
    offs = offsets(shape)
    p = [(u, v, offs[i] * STEP_RISE) for i, (u, v) in enumerate(CORNERS)]
    if offs[1] == offs[3]:
        return [(p[0], p[1], p[3]), (p[1], p[2], p[3])]
    return [(p[0], p[1], p[2]), (p[0], p[2], p[3])]


def screen(pt):
    u, v, h = pt
    return proj(u, v, h / STEP_RISE)


# ---- textures -----------------------------------------------------------------

def noise_layer(cols, seed, weights=(0.78, 0.12, 0.10), block=2):
    """A whole cell of noise in [cols], in blocks of [block] pixels so it reads at every level."""
    rng = random.Random(seed)
    img = Image.new("RGBA", (CELL_W, CELL_H))
    px = img.load()
    for y in range(0, CELL_H, block):
        for x in range(0, CELL_W, block):
            r = rng.random()
            col = cols[0] if r < weights[0] else cols[1] if r < weights[0] + weights[1] else cols[2]
            for dy in range(block):
                for dx in range(block):
                    px[x + dx, y + dy] = col
    return img


def grass_layer(variant):
    img = noise_layer(GRASS, 1000 + variant)
    # A few tufts: a dark blade with a light tip.
    rng = random.Random(2000 + variant)
    px = img.load()
    for _ in range(14):
        x, y = rng.randrange(2, CELL_W - 2), rng.randrange(4, CELL_H - 2)
        px[x, y] = shade(GRASS[1], 0.85)
        px[x, y - 1] = shade(GRASS[1], 0.9)
        px[x, y - 2] = GRASS[2]
    return img


def masked(layer, polygon, f=1.0, grow=0):
    """[layer] shaded by [f], cut to [polygon], grown by [grow] pixels all round."""
    mask = Image.new("L", (CELL_W, CELL_H), 0)
    if grow:
        ImageDraw.Draw(mask).polygon(polygon, fill=255, outline=255, width=grow * 2)
    else:
        ImageDraw.Draw(mask).polygon(polygon, fill=255)
    out = Image.new("RGBA", (CELL_W, CELL_H))
    shaded = layer if f == 1.0 else Image.eval(layer.convert("RGB"), lambda v: max(0, min(255, int(v * f)))).convert("RGBA")
    out.paste(shaded, (0, 0), mask)
    return out


# ---- ground -------------------------------------------------------------------

def ground(kind, shape):
    if kind == "water":
        img = Image.new("RGBA", (CELL_W, CELL_H))
        poly = [proj(u, v, 0) for u, v in CORNERS]
        ImageDraw.Draw(img).polygon(poly, fill=WATER, outline=WATER, width=2)
        rng = random.Random(3000)
        d = ImageDraw.Draw(img)
        for _ in range(6):
            u, v = rng.uniform(0.15, 0.75), rng.uniform(0.15, 0.75)
            x, y = proj(u, v, 0)
            d.line([x, y, x + rng.randrange(6, 14), y], RIPPLE, width=2)
        return img
    layer = grass_layer(int(kind[-1])) if kind.startswith("grass") else noise_layer(SAND, 4000)
    img = Image.new("RGBA", (CELL_W, CELL_H))
    # Each tile a pixel over its edges, so neighbours overlap and no seam shows once the levels are shrunk.
    for tri in triangles(shape):
        img.alpha_composite(masked(layer, [screen(p) for p in tri], brightness(*tri), grow=1))
    return img


# ---- foundations --------------------------------------------------------------

def face(img, a_top, b_top, a_bot, b_bot, lit):
    """A stone wall from corner a to b, its top along a_top to b_top and its foot along a_bot to b_bot."""
    poly = [a_top, b_top, b_bot, a_bot]
    if abs(a_top[1] - a_bot[1]) < 0.5 and abs(b_top[1] - b_bot[1]) < 0.5:
        return
    layer = Image.new("RGBA", (CELL_W, CELL_H), STONE_LIT if lit else STONE_DARK)
    d = ImageDraw.Draw(layer)
    # Courses parallel to the top edge, with joints offset course to course.
    course = STEP // 3
    for k in range(1, 8):
        y0, y1 = a_top[1] + k * course, b_top[1] + k * course
        d.line([a_top[0], y0, b_top[0], y1], MORTAR, width=1)
        for j in range(5):
            t = (j + (0.5 if k % 2 else 0)) / 4.5
            x = a_top[0] + (b_top[0] - a_top[0]) * t
            y = y0 + (y1 - y0) * t
            d.line([x, y, x, y + course], MORTAR, width=1)
    img.alpha_composite(masked(layer, poly))
    ImageDraw.Draw(img).line(poly + [poly[0]], OUTLINE, width=1)


def walls(ground_shape, surface_shape):
    """The walls of a foundation with its top shaped [surface_shape] over land shaped [ground_shape]."""
    g = offsets(ground_shape)
    s = offsets(surface_shape)
    img = Image.new("RGBA", (CELL_W, CELL_H))
    # The two faces towards the camera: right to bottom (in shade) and left to bottom (lit).
    for a, b, lit in ((1, 2, False), (3, 2, True)):
        (au, av), (bu, bv) = CORNERS[a], CORNERS[b]
        face(img, proj(au, av, s[a]), proj(bu, bv, s[b]), proj(au, av, g[a]), proj(bu, bv, g[b]), lit)
    return img


# ---- track --------------------------------------------------------------------

# Pieces as in the game, in bit order: straight along u, along v, then the
# diagonals cutting off the top, right, bottom and left corners.
PIECES = ["X", "Y", "N", "E", "S", "W"]
EDGE_MID = {"XM": (0.0, 0.5), "XP": (1.0, 0.5), "YM": (0.5, 0.0), "YP": (0.5, 1.0)}
PIECE_EDGES = {"X": ("XM", "XP"), "Y": ("YM", "YP"), "N": ("YM", "XM"), "E": ("YM", "XP"), "S": ("XP", "YP"), "W": ("XM", "YP")}


def strip(a, b, half, offs, t0=0.0, t1=1.0):
    """A strip of the track from t0 to t1 along a to b, half wide each side, on ground shaped by offs."""
    ax, ay = a
    bx, by = b
    length = math.hypot(bx - ax, by - ay)
    nx, ny = -(by - ay) / length, (bx - ax) / length
    sx, sy = ax + (bx - ax) * t0, ay + (by - ay) * t0
    ex, ey = ax + (bx - ax) * t1, ay + (by - ay) * t1
    pts = [(sx + nx * half, sy + ny * half), (ex + nx * half, ey + ny * half), (ex - nx * half, ey - ny * half), (sx - nx * half, sy - ny * half)]
    return [proj(u, v, height_at(offs, u, v)) for u, v in pts]


def track(piece, surface_shape):
    offs = offsets(surface_shape)
    a = EDGE_MID[PIECE_EDGES[piece][0]]
    b = EDGE_MID[PIECE_EDGES[piece][1]]
    length = math.hypot(b[0] - a[0], b[1] - a[1])
    img = Image.new("RGBA", (CELL_W, CELL_H))
    img.alpha_composite(masked(noise_layer(GRAVEL, 5000, block=2), strip(a, b, 0.21, offs)))
    d = ImageDraw.Draw(img)
    sleepers = max(3, round(length * 6))
    for i in range(sleepers):
        t = (i + 0.5) / sleepers
        dt = 0.035 / length
        poly = strip(a, b, 0.17, offs, t - dt, t + dt)
        d.polygon(poly, fill=SLEEPER)
        d.line([poly[0], poly[1]], SLEEPER_TOP, width=1)
    nx, ny = -(b[1] - a[1]) / length, (b[0] - a[0]) / length
    for side in (-1, 1):
        o = 0.095 * side
        p0 = (a[0] + nx * o, a[1] + ny * o)
        p1 = (b[0] + nx * o, b[1] + ny * o)
        q0 = proj(p0[0], p0[1], height_at(offs, *p0))
        q1 = proj(p1[0], p1[1], height_at(offs, *p1))
        # A dark line under each rail for its outline and shadow, the rail's head over it.
        d.line([q0[0], q0[1] + 1.5, q1[0], q1[1] + 1.5], RAIL_SHADE, width=3)
        d.line([q0, q1], RAIL, width=2)
    return img


# ---- the sprite list ------------------------------------------------------------

SLOPES = list(range(15))
SURFACES = [15, 3, 6, 12, 9]
# The slopes a straight piece runs up: along u for X, along v for Y.
TRACK_SURFACES = {"X": [0, 6, 9], "Y": [0, 3, 12]}


def sprites():
    out = []
    for k, kind in enumerate(KINDS):
        for shape in ([0] if kind == "water" else SLOPES):
            out.append((("ground", k, shape), ground(kind, shape)))
    for s in SURFACES:
        for g in SLOPES:
            if g != 0 and g != s and g & ~s == 0:
                out.append((("walls", g, s), walls(g, s)))
    for p, piece in enumerate(PIECES):
        for s in TRACK_SURFACES.get(piece, [0]):
            out.append((("track", p, s), track(piece, s)))
    return out


def main():
    items = sprites()
    rows = (len(items) + COLUMNS - 1) // COLUMNS
    sheet = Image.new("RGBA", (COLUMNS * CELL_W, rows * CELL_H))
    for i, (_, img) in enumerate(items):
        sheet.paste(img, ((i % COLUMNS) * CELL_W, (i // COLUMNS) * CELL_H))
    OUT_PNG.mkdir(parents=True, exist_ok=True)
    for level in range(LEVELS):
        f = 1 << level
        # Shrunk with the colour weighted by its alpha, or edges pick up the transparent black round them.
        img = sheet if f == 1 else sheet.convert("RGBa").resize((sheet.width // f, sheet.height // f), Image.BOX).convert("RGBA")
        # Every pixel solid or clear: where two tiles meet, one of them always covers the pixel, so no seam shows.
        r, g, b, a = img.split()
        img = Image.merge("RGBA", (r, g, b, a.point(lambda v: 255 if v >= 128 else 0)))
        img.save(OUT_PNG / f"atlas_{level}.png", optimize=True)

    ground_cells = [-1] * (len(KINDS) * 16)
    wall_cells = [-1] * (16 * 16)
    track_cells = [-1] * (len(PIECES) * 16)
    for i, (key, _) in enumerate(items):
        if key[0] == "ground":
            ground_cells[key[1] * 16 + key[2]] = i
        elif key[0] == "walls":
            wall_cells[key[1] * 16 + key[2]] = i
        else:
            track_cells[key[1] * 16 + key[2]] = i

    def arr(xs):
        return ", ".join(str(x) for x in xs)

    kinds = "\n".join(f"    const val {k.upper()} = {i}" for i, k in enumerate(KINDS))
    OUT_KT.write_text(f"""package com.rm.interchange.map

// Written by tools/gen_tiles.py. Don't edit by hand.

/**
 * Where each sprite is in the atlases, files/atlas_<level>.png. Every sprite
 * is a cell [CELL_W] by [CELL_H] at level 0, halved each level after, laid out
 * [COLUMNS] to a row. A sprite's anchor is ([CELL_W] / 2, [CELL_H_ANCHOR]) at
 * level 0: the tile's top corner at the height it stands on.
 *
 * Shapes are which view corners stand a step up: top, right, bottom, left as
 * bits 1, 2, 4 and 8.
 */
object Atlas {{
    const val LEVELS = {LEVELS}
    const val CELL_W = {CELL_W}
    const val CELL_H = {CELL_H}
    const val CELL_H_ANCHOR = {STEP}
    const val COLUMNS = {COLUMNS}

{kinds}

    /** The cell for ground of a kind and shape, at kind * 16 + shape, or -1. */
    val GROUND = intArrayOf({arr(ground_cells)})

    /** The cell for a foundation's walls, at land shape * 16 + top shape, or -1. */
    val WALLS = intArrayOf({arr(wall_cells)})

    /** The cell for a track piece in the view, at piece index * 16 + the shape it lies on, or -1. */
    val TRACK = intArrayOf({arr(track_cells)})
}}
""")
    print(f"{len(items)} sprites, {rows} rows")


if __name__ == "__main__":
    main()
