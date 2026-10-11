#!/usr/bin/env python3
"""Draws Interchange's sprites and textures and packs them into sheets.

    uv run --with pillow --with numpy tools/gen_tiles.py

The drawing is in tools/art: base.py has what everything shares, textures.py
the ground textures, ground.py trees and decorations, track.py track,
foundations, platforms, depots and signals, and vehicles.py plain vehicle
drawings. Vehicles rendered in Blender (tools/blender/render_vehicles.py, into
build/renders) are used in place of the plain ones where they exist.

Three kinds of sheet, one of each per zoom level, sharpest first, each level
half the last:
- files/ground_<level>.png: the ground textures, which the game paints the land with
- files/static_<level>.png: what the game bakes into the map: track, buildings, trees
- files/signals_<level>.png: signals, drawn over the map each frame
- files/vehicle_<key>_<level>.png: one sheet for each vehicle, so only those on
  screen are unpacked, as packs will add many

Each sprite is drawn on a cell, shrunk to the sharpest level, trimmed to what it
covers and packed; trimmed edges sit on a 32 pixel grid so every level divides
exactly. map/Atlas.kt says where everything is and sim/VehicleArt.kt which
drawing and length each vehicle has.
"""
import multiprocessing
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "art"))

from PIL import Image, ImageChops, ImageFilter  # noqa: E402

import base  # noqa: E402
import ground  # noqa: E402
import textures  # noqa: E402
import track  # noqa: E402
import vehicles  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
RENDERS = ROOT / "build/renders"
# Trees, platforms, the engine house and signals rendered by tools/blender/render_scenery.py.
SCENERY = RENDERS / "scenery"
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


def rendered(key, h, build):
    """The Blender render of a vehicle if there is one, else the plain drawing."""
    path = RENDERS / f"{key}_{h}.png"
    if path.exists():
        return Image.open(path).convert("RGBA")
    print(f"No render for {key} heading {h}; drawing it plain.")
    return vehicles.vehicle(build, h)


def scenery(name, fallback):
    """A Blender render of scenery if there is one, else the plain drawing."""
    path = SCENERY / name
    if path.exists():
        return Image.open(path).convert("RGBA")
    print(f"No render for {name}; drawing it plain.")
    return fallback()


def over(under, top):
    """One cell drawn over another."""
    out = under.copy()
    out.alpha_composite(top)
    return out


# What to draw: (sheet, key, function, finish). finish is "outline" for a dark line round the solid part.
JOBS = []
for s in SURFACES:
    for g in SLOPES:
        if g != 0 and g != s and g & ~s == 0:
            JOBS.append(("static", ("walls", g, s), lambda g=g, s=s: track.walls(g, s), None))
for p, piece in enumerate(track.PIECES):
    for s in TRACK_SURFACES.get(piece, [0]):
        JOBS.append(("static", ("track", p, s), lambda piece=piece, s=s: track.track(piece, s), None))
# Platforms and the engine house over their track, each with its shadow apart so the game can lay shadows first.
for a in (0, 1):
    JOBS.append(("static", ("station", a), lambda a=a: over(track.track("X" if a == 0 else "Y", 0), scenery(f"station_{a}.png", lambda: track.station(a == 0))), None))
    JOBS.append(("static", ("station_shadow", a), lambda a=a: scenery(f"station_{a}_shadow.png", base.cell), None))
for e, edge in enumerate(EDGES):
    JOBS.append(("static", ("depot", e), lambda edge=edge: scenery(f"depot_{edge}.png", lambda: track.depot(edge)), None))
    JOBS.append(("static", ("depot_shadow", e), lambda edge=edge: scenery(f"depot_{edge}_shadow.png", base.cell), None))
for t, kind in enumerate(ground.TREES):
    for look in range(ground.TREE_LOOKS):
        JOBS.append(("static", ("tree", t, look), lambda kind=kind, look=look: scenery(f"tree_{kind}_{look}.png", lambda: ground.tree(kind, look)), None))
        JOBS.append(("static", ("tree_shadow", t, look), lambda kind=kind, look=look: scenery(f"tree_{kind}_{look}_shadow.png", base.cell), None))
for d, kind in enumerate(ground.DECOR):
    for look in range(ground.DECOR_LOOKS):
        JOBS.append(("static", ("decor", d, look), lambda kind=kind, look=look: ground.decor(kind, look), None))
for red in (0, 1):
    JOBS.append(("signals", ("signal", red), lambda red=red: scenery(f"signal_{red}.png", lambda: track.signal(red == 1)), None))


def rendered_slope(key, h, way, build):
    """A vehicle tilted up or down a slope, from Blender, or flat if there's no render."""
    path = RENDERS / f"{key}_s{h}_{way}.png"
    return Image.open(path).convert("RGBA") if path.exists() else rendered(key, h, build)


for m, (key, length, build) in enumerate(vehicles.ROSTER):
    for h in range(8):
        JOBS.append((f"vehicle_{key}", ("vehicle", m, h), lambda key=key, build=build, h=h: rendered(key, h, build), "outline"))
    for h in (0, 2, 4, 6):
        for way in ("up", "down"):
            JOBS.append((f"vehicle_{key}", ("slope", m, h, way), lambda key=key, build=build, h=h, way=way: rendered_slope(key, h, way, build), "outline"))


def shrink(img, f):
    """Halved f times over, with colour weighted by alpha so edges don't pick up the clear black round them."""
    return img.convert("RGBa").resize((img.width // f, img.height // f), Image.BOX).convert("RGBA")


def outline(img):
    """A dark line a pixel wide round the solid part of a sprite, left off its soft shadow, in Infill's style."""
    solid = img.getchannel("A").point(lambda v: 255 if v > 200 else 0)
    ring = ImageChops.subtract(solid.filter(ImageFilter.MaxFilter(3)), solid)
    out = img.copy()
    out.paste(Image.new("RGBA", img.size, (36, 32, 28, 235)), (0, 0), ring)
    return out


def render(i):
    sheet, key, fn, finish = JOBS[i]
    img = shrink(fn(), base.SS)
    if finish == "outline":
        img = outline(img)
    # Trimmed to where it's more than faint, so a soft shadow's last wisps don't stretch the box.
    box = img.getchannel("A").point(lambda v: 255 if v > 12 else 0).getbbox()
    if box is None:
        return i, None, (0, 0, 0, 0)
    l, t, r, b = box
    l, t = l // GRID * GRID, t // GRID * GRID
    r, b = -(-r // GRID) * GRID, -(-b // GRID) * GRID
    return i, img.crop((l, t, r, b)), (l, t, r, b)


def pack(sizes, sheet_w=SHEET_W):
    """Shelves, tallest first. Returns a position for each size and the sheet's height."""
    order = sorted(range(len(sizes)), key=lambda i: -sizes[i][1])
    pos = [None] * len(sizes)
    x = y = shelf = 0
    for i in order:
        w, h = sizes[i]
        if x + w > sheet_w:
            x = 0
            y += shelf
            shelf = 0
        pos[i] = (x, y)
        x += w
        shelf = max(shelf, h)
    return pos, -(-(y + shelf) // GRID) * GRID


def write_sheet(name, done, sheet_w=SHEET_W):
    """Packs one sheet's sprites and writes it at every level. Returns each sprite's rect and where it sits from its anchor."""
    sizes = [(im.width, im.height) if im else (0, 0) for _, im, _ in done]
    pos, height = pack(sizes, sheet_w)
    sheet = Image.new("RGBA", (sheet_w, max(GRID, height)))
    for (_, im, _), p in zip(done, pos):
        if im:
            sheet.paste(im, p)
    for level in range(LEVELS):
        f = 1 << level
        (sheet if f == 1 else shrink(sheet, f)).save(OUT_PNG / f"{name}_{level}.png", optimize=True)
    rects = []
    for (_, im, (l, t, r, b)), p in zip(done, pos):
        rects += [p[0], p[1], r - l, b - t, l - base.HW, t - base.TOP]
    return rects, height


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


def main():
    with multiprocessing.get_context("fork").Pool() as pool:
        done = sorted(pool.map(render, range(len(JOBS)), chunksize=4))
    OUT_PNG.mkdir(parents=True, exist_ok=True)
    for pattern in ("atlas_*.png", "static_*.png", "moving_*.png", "signals_*.png", "vehicle_*.png", "ground_*.png"):
        for old in OUT_PNG.glob(pattern):
            old.unlink()

    # Each sheet's sprites numbered in their own sheet.
    index = {}
    rects = {}
    names = []
    for job in JOBS:
        if job[0] not in names:
            names.append(job[0])
    for name in names:
        mine = [d for d in done if JOBS[d[0]][0] == name]
        for n, d in enumerate(mine):
            index[JOBS[d[0]][1]] = n
        rects[name], height = write_sheet(name, mine, SHEET_W if name == "static" else 1024)
        if not name.startswith("vehicle_"):
            print(f"{name}: {len(mine)} sprites, {height} high at level 0")
    biggest = max(len(rects[n]) for n in names if n.startswith("vehicle_")) // 6
    print(f"{len([n for n in names if n.startswith('vehicle_')])} vehicle sheets, {biggest} sprites each")

    tex = textures.sheet().convert("RGB")
    for level in range(LEVELS):
        f = 1 << level
        tex.resize((tex.width // f, tex.height // f), Image.BOX).save(OUT_PNG / f"ground_{level}.png", optimize=True)

    walls = [index.get(("walls", g, s), -1) for g in range(16) for s in range(16)]
    tracks = [index.get(("track", p, s), -1) for p in range(6) for s in range(16)]
    trees = [index[("tree", t, lk)] for t in range(len(ground.TREES)) for lk in range(ground.TREE_LOOKS)]
    tree_shadows = [index[("tree_shadow", t, lk)] for t in range(len(ground.TREES)) for lk in range(ground.TREE_LOOKS)]
    decor = [index[("decor", d, lk)] for d in range(len(ground.DECOR)) for lk in range(ground.DECOR_LOOKS)]
    # Each vehicle's sixteen sprites in its own sheet, in a fixed order: eight headings, then the slopes.
    vehicle_rects = []
    for m, (key, _, _) in enumerate(vehicles.ROSTER):
        r = rects[f"vehicle_{key}"]
        order = [index[("vehicle", m, h)] for h in range(8)] + [index[("slope", m, h, way)] for h in (0, 2, 4, 6) for way in ("up", "down")]
        for n in order:
            vehicle_rects += r[n * 6:n * 6 + 6]
    vehicle_keys = ", ".join(f'"{key}"' for key, _, _ in vehicles.ROSTER)
    kinds = list(textures.KINDS)
    kind_consts = "\n".join(f"    const val {k.upper()} = {i}" for i, k in enumerate(kinds))
    OUT_KT.write_text(f"""package com.rm.interchange.map

// Written by tools/gen_tiles.py. Don't edit by hand.

/**
 * Where everything is in the sheets, sharpest level first, each level half the
 * last: files/ground_<level>.png, the ground textures; files/static_<level>.png,
 * what's baked into the map; files/signals_<level>.png and a sheet for each
 * vehicle, files/vehicle_<key>_<level>.png, what's drawn over it.
 *
 * The rects hold six numbers a sprite at level 0: x, y, width and height on its
 * sheet, then where its top left sits from its anchor. The anchor is the tile's
 * top corner at the height the sprite stands on; a vehicle, tree or signal is
 * drawn as if on a tile whose middle is where it stands.
 *
 * Foundation and track shapes are which view corners stand a step up: top,
 * right, bottom and left as bits 1, 2, 4 and 8.
 */
object Atlas {{
    const val LEVELS = {LEVELS}
    /** Half a tile's width at level 0. */
    const val HALF_WIDTH = {base.HW}

    /** Texels a tile across at level 0, and how many tiles a ground texture spans before it repeats. */
    const val TEXELS = {textures.TEXELS}
    const val PERIOD = {textures.PERIOD}

    // Ground kinds, in the order of their textures on the ground sheet: the game's land kinds, then beach sand and water.
{kind_consts}
    /** The grey noise that makes the edges between kinds ragged, after the kinds on the ground sheet. */
    const val RAG = {len(kinds)}

    val STATIC_RECTS = intArrayOf(
        {arr(rects["static"])},
    )

    val SIGNAL_RECTS = intArrayOf(
        {arr(rects["signals"])},
    )

    /** Each vehicle's sheet is files/vehicle_<key>_<level>.png. */
    val VEHICLE_KEYS = listOf({vehicle_keys})

    /**
     * Sixteen sprites a vehicle, in its own sheet: the eight headings in the view (0 down the u axis, on round
     * towards v), then the four straight headings climbing and going down a slope, at heading / 2 * 2 + 0 or 1.
     */
    val VEHICLE_RECTS = intArrayOf(
        {arr(vehicle_rects)},
    )

    /** A foundation's walls, at land shape * 16 + top shape, or -1. On the static sheet. */
    val WALLS = intArrayOf(
        {arr(walls)},
    )

    /** A track piece in the view, at piece index * 16 + the shape it lies on, or -1. On the static sheet. */
    val TRACK = intArrayOf(
        {arr(tracks)},
    )

    /** A platform with its track, along view u (0) or v (1), and its shadow. On the static sheet. */
    val STATION = intArrayOf({index[("station", 0)]}, {index[("station", 1)]})
    val STATION_SHADOW = intArrayOf({index[("station_shadow", 0)]}, {index[("station_shadow", 1)]})

    /** A depot with its doors at view edge XM, XP, YM or YP, and its shadow. On the static sheet. */
    val DEPOT = intArrayOf({", ".join(str(index[("depot", e)]) for e in range(4))})
    val DEPOT_SHADOW = intArrayOf({", ".join(str(index[("depot_shadow", e)]) for e in range(4))})

    const val TREE_LOOKS = {ground.TREE_LOOKS}
    /** A tree, at kind * TREE_LOOKS + look, kinds in the order of the game's trees, and its shadow. On the static sheet. */
    val TREE = intArrayOf({arr(trees)})
    val TREE_SHADOW = intArrayOf({arr(tree_shadows)})

    const val DECOR_LOOKS = {ground.DECOR_LOOKS}
    /** Boulders, shrubs and tall grass, at kind * DECOR_LOOKS + look. On the static sheet. */
    val DECOR = intArrayOf({arr(decor)})

    /** A signal showing clear (0) or stop (1). On the signals sheet. */
    val SIGNAL = intArrayOf({index[("signal", 0)]}, {index[("signal", 1)]})
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


if __name__ == "__main__":
    main()
