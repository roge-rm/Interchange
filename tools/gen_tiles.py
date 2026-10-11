#!/usr/bin/env python3
"""Draws Interchange's sprites and packs them into the atlases.

    uv run --with pillow tools/gen_tiles.py

The drawing is in tools/art: base.py has what everything shares, ground.py the
land and trees, track.py track, foundations, platforms, depots and signals,
and vehicles.py the rail vehicles.

Each sprite is drawn on a cell, shrunk to the sharpest level, trimmed to what
it covers and packed, then the whole sheet is halved for each level after:
files/atlas_<level>.png, with map/Atlas.kt saying where each sprite is and
sim/VehicleArt.kt which drawing and length each vehicle has. Trimmed edges sit
on a 32 pixel grid so every level divides exactly.
"""
import multiprocessing
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "art"))

from PIL import Image  # noqa: E402

import base  # noqa: E402
import ground  # noqa: E402
import track  # noqa: E402
import vehicles  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
OUT_PNG = ROOT / "shared/src/commonMain/composeResources/files"
OUT_KT = ROOT / "shared/src/commonMain/kotlin/com/rm/interchange/map/Atlas.kt"
OUT_SIM = ROOT / "sim/src/commonMain/kotlin/com/rm/interchange/sim/VehicleArt.kt"

LEVELS = 6
GRID = 1 << (LEVELS - 1)
SHEET_W = 2048

SLOPES = list(range(15))
SURFACES = [15, 3, 6, 12, 9]
TRACK_SURFACES = {"X": [0, 6, 9], "Y": [0, 3, 12]}
EDGES = ["XM", "XP", "YM", "YP"]

# What to draw: (key, function, whether it fills its tile and so must have no part-clear edge pixels).
JOBS = []
for kind, (_, looks) in ground.GROUND.items():
    for look in range(looks):
        for shape in SLOPES:
            JOBS.append((("ground", kind, look, shape), lambda k=kind, lk=look, s=shape: ground.ground(k, lk, s), True))
JOBS.append((("water",), ground.water, True))
for s in SURFACES:
    for g in SLOPES:
        if g != 0 and g != s and g & ~s == 0:
            JOBS.append((("walls", g, s), lambda g=g, s=s: track.walls(g, s), False))
for p, piece in enumerate(track.PIECES):
    for s in TRACK_SURFACES.get(piece, [0]):
        JOBS.append((("track", p, s), lambda piece=piece, s=s: track.track(piece, s), False))
for a in (0, 1):
    JOBS.append((("station", a), lambda a=a: track.station(a == 0), False))
for e, edge in enumerate(EDGES):
    JOBS.append((("depot", e), lambda edge=edge: track.depot(edge), False))
for red in (0, 1):
    JOBS.append((("signal", red), lambda red=red: track.signal(red == 1), False))
for t, kind in enumerate(ground.TREES):
    for look in range(ground.TREE_LOOKS):
        JOBS.append((("tree", t, look), lambda kind=kind, look=look: ground.tree(kind, look), False))
for m, (key, length, build) in enumerate(vehicles.ROSTER):
    for h in range(8):
        JOBS.append((("vehicle", m, h), lambda build=build, h=h: vehicles.vehicle(build, h), False))


def shrink(img, f):
    """Halved f times over, with colour weighted by alpha so edges don't pick up the clear black round them."""
    return img.convert("RGBa").resize((img.width // f, img.height // f), Image.BOX).convert("RGBA")


def solid_edges(img):
    r, g, b, a = img.split()
    return Image.merge("RGBA", (r, g, b, a.point(lambda v: 255 if v >= 128 else 0)))


def render(i):
    key, fn, solid = JOBS[i]
    img = shrink(fn(), base.SS)
    if solid:
        img = solid_edges(img)
    box = img.getchannel("A").getbbox()
    if box is None:
        return i, None, (0, 0, 0, 0)
    l, t, r, b = box
    l, t = l // GRID * GRID, t // GRID * GRID
    r, b = -(-r // GRID) * GRID, -(-b // GRID) * GRID
    return i, img.crop((l, t, r, b)), (l, t, r, b)


def pack(sizes):
    """Shelves, tallest first. Returns a position for each size and the sheet's height."""
    order = sorted(range(len(sizes)), key=lambda i: -sizes[i][1])
    pos = [None] * len(sizes)
    x = y = shelf = 0
    for i in order:
        w, h = sizes[i]
        if x + w > SHEET_W:
            x = 0
            y += shelf
            shelf = 0
        pos[i] = (x, y)
        x += w
        shelf = max(shelf, h)
    return pos, y + shelf


def main():
    with multiprocessing.get_context("fork").Pool() as pool:
        done = sorted(pool.map(render, range(len(JOBS)), chunksize=4))
    sizes = [(im.width, im.height) if im else (0, 0) for _, im, _ in done]
    pos, height = pack(sizes)
    height = -(-height // GRID) * GRID
    sheet = Image.new("RGBA", (SHEET_W, height))
    for (i, im, _), p in zip(done, pos):
        if im:
            sheet.paste(im, p)
    OUT_PNG.mkdir(parents=True, exist_ok=True)
    for old in OUT_PNG.glob("atlas_*.png"):
        old.unlink()
    for level in range(LEVELS):
        f = 1 << level
        img = sheet if f == 1 else shrink(sheet, f)
        # Tiles that fill their ground stay solid or clear at every level, so they meet without seams.
        r, g, b, a = img.split()
        ap = a.load()
        for (i, im, _), p in zip(done, pos):
            if im and JOBS[i][2]:
                for yy in range(p[1] // f, (p[1] + im.height) // f):
                    for xx in range(p[0] // f, (p[0] + im.width) // f):
                        ap[xx, yy] = 255 if ap[xx, yy] >= 128 else 0
        Image.merge("RGBA", (r, g, b, a)).save(OUT_PNG / f"atlas_{level}.png", optimize=True)

    rects = []
    index = {}
    for (i, im, (l, t, r, b)), p in zip(done, pos):
        index[JOBS[i][0]] = i
        rects += [p[0], p[1], r - l, b - t, l - base.HW, t - base.TOP]

    kinds = list(ground.GROUND.keys())
    starts, looks, n = [], [], 0
    for k in kinds:
        starts.append(n)
        looks.append(ground.GROUND[k][1])
        n += ground.GROUND[k][1]
    ground_cells = [-1] * (n * 16)
    for k_i, k in enumerate(kinds):
        for look in range(ground.GROUND[k][1]):
            for s in SLOPES:
                ground_cells[(starts[k_i] + look) * 16 + s] = index[("ground", k, look, s)]
    walls = [index.get(("walls", g, s), -1) for g in range(16) for s in range(16)]
    tracks = [index.get(("track", p, s), -1) for p in range(6) for s in range(16)]
    trees = [index[("tree", t, lk)] for t in range(len(ground.TREES)) for lk in range(ground.TREE_LOOKS)]
    vehicle_cells = [index[("vehicle", m, h)] for m in range(len(vehicles.ROSTER)) for h in range(8)]

    def arr(xs):
        out, line = [], []
        for x in xs:
            line.append(str(x))
            if len(line) == 24:
                out.append(", ".join(line))
                line = []
        if line:
            out.append(", ".join(line))
        return ",\n        ".join(out)

    kind_consts = "\n".join(f"    const val {k.upper()} = {i}" for i, k in enumerate(kinds))
    OUT_KT.write_text(f"""package com.rm.interchange.map

// Written by tools/gen_tiles.py. Don't edit by hand.

/**
 * Where each sprite is in the atlases, files/atlas_<level>.png, sharpest
 * first, each level half the last. [RECTS] holds six numbers a sprite at
 * level 0: x, y, width and height on the sheet, then where its top left sits
 * from its anchor. The anchor is the tile's top corner at the height the sprite
 * stands on; a vehicle or tree is drawn as if on a tile whose middle is where
 * it stands.
 *
 * Ground and foundation shapes are which view corners stand a step up: top,
 * right, bottom and left as bits 1, 2, 4 and 8.
 */
object Atlas {{
    const val LEVELS = {LEVELS}
    /** Half a tile's width at level 0. */
    const val HALF_WIDTH = {base.HW}

    val RECTS = intArrayOf(
        {arr(rects)},
    )

    // Ground kinds, in the order of the game's land kinds, then beach sand.
{kind_consts}

    /** Where each kind's looks start, and how many it has. */
    val KIND_START = intArrayOf({arr(starts)})
    val KIND_LOOKS = intArrayOf({arr(looks)})

    /** Ground of a look and shape, at (KIND_START[kind] + look) * 16 + shape. */
    val GROUND = intArrayOf(
        {arr(ground_cells)},
    )

    const val WATER = {index[("water",)]}

    /** A foundation's walls, at land shape * 16 + top shape, or -1. */
    val WALLS = intArrayOf(
        {arr(walls)},
    )

    /** A track piece in the view, at piece index * 16 + the shape it lies on, or -1. */
    val TRACK = intArrayOf(
        {arr(tracks)},
    )

    /** A platform with its track, along view u (0) or v (1). */
    val STATION = intArrayOf({index[("station", 0)]}, {index[("station", 1)]})

    /** A depot with its doors at view edge XM, XP, YM or YP. */
    val DEPOT = intArrayOf({", ".join(str(index[("depot", e)]) for e in range(4))})

    /** A signal showing clear (0) or stop (1). */
    val SIGNAL = intArrayOf({index[("signal", 0)]}, {index[("signal", 1)]})

    const val TREE_LOOKS = {ground.TREE_LOOKS}
    /** A tree, at kind * TREE_LOOKS + look, kinds in the order of the game's trees. */
    val TREE = intArrayOf({arr(trees)})

    /** A rail vehicle, at drawing * 8 + heading in the view (0 down the u axis, on round towards v). */
    val VEHICLE = intArrayOf(
        {arr(vehicle_cells)},
    )
}}
""")
    consts = "\n".join(f"    const val {key.upper()} = {m}" for m, (key, _, _) in enumerate(vehicles.ROSTER))
    OUT_SIM.write_text(f"""package com.rm.interchange.sim

// Written by tools/gen_tiles.py. Don't edit by hand.

/** The vehicle drawings, and each one's length in sixteenths of a tile. */
object VehicleArt {{
{consts}

    val LENGTHS = intArrayOf({", ".join(str(length) for _, length, _ in vehicles.ROSTER)})
}}
""")
    print(f"{len(JOBS)} sprites, sheet {SHEET_W} x {height} at level 0")


if __name__ == "__main__":
    main()
