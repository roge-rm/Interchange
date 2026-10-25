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
- files/ground_<level>.webp: the ground textures, which the game paints the land with
- files/static_<level>.webp: what the game bakes into the map: track, buildings, trees, with how
  much each pixel faces the sky for snow: static_snow_<level>.bin at levels 0 and 1,
  otherwise in the low two bits of its blue
- files/signals_<level>.webp: signals, drawn over the map each frame
- files/vehicle_<key>_<level>.webp: one sheet for each vehicle, so only those on
  screen are unpacked, as packs will add many

Each sprite is drawn on a cell, shrunk to the sharpest level, trimmed to what it
covers and packed; trimmed edges sit on a 32 pixel grid so every level divides
exactly. map/Atlas.kt says where everything is.

The vehicles also go into content packs (packs/SPEC.md), each as a sheet of
sixteen cells at the sharpest level: the base pack in build/packs, with each
vehicle's length written into packs/base/vehicles.json, and the template pack
in packs/template, its vehicles recoloured from the base ones.
"""
import hashlib
import json
import os
import pickle
import re
import zlib
import zipfile
import multiprocessing
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "art"))

import numpy as np  # noqa: E402
from PIL import Image, ImageChops, ImageFilter  # noqa: E402

import base  # noqa: E402
import ground  # noqa: E402
import textures  # noqa: E402
import track  # noqa: E402
import vehicles  # noqa: E402
from sheet_encoding import save_sheet  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
RENDERS = ROOT / "build/renders"
# Trees, platforms, the engine house, signals, track and decorations rendered by tools/blender/render_scenery.py.
SCENERY = RENDERS / "scenery"
OUT_PNG = ROOT / "shared/src/commonMain/composeResources/files"


OUT_KT = ROOT / "shared/src/commonMain/kotlin/com/rm/interchange/map/Atlas.kt"
PACKS = ROOT / "packs"
BUILD_PACKS = ROOT / "build/packs"
# A pack sprite sheet: four by four cells, each CELL square, the vehicle's middle standing at (STAND_X, STAND_Y).
CELL = 256
STAND_X, STAND_Y = 128, 160

# Finished sprites are kept here, so a run only redraws what changed. --full ignores them.
CACHE = ROOT / "build/gen_tiles_cache"
# What the sprite being drawn read: [path, mtime, size] of each file, size None if it wasn't there.
DEPS = []

LEVELS = 6
GRID = 1 << (LEVELS - 1)
SHEET_W = 2048

SLOPES = list(range(15))
SURFACES = [15, 3, 6, 12, 9]
TRACK_SURFACES = {"X": [0, 6, 9], "Y": [0, 3, 12]}
EDGES = ["XM", "XP", "YM", "YP"]


def dep(path):
    """Notes a file the sprite being drawn depends on, whether it's there or not."""
    try:
        st = path.stat()
        DEPS.append([str(path), st.st_mtime_ns, st.st_size])
    except OSError:
        DEPS.append([str(path), None, None])


def rendered(key, h, build):
    """The Blender render of a vehicle if there is one, else the plain drawing."""
    path = RENDERS / f"{key}_{h}.png"
    dep(path)
    if path.exists():
        return Image.open(path).convert("RGBA")
    print(f"No render for {key} heading {h}; drawing it plain.")
    return vehicles.vehicle(build, h)


def scenery(name, fallback):
    """A Blender render of scenery if there is one, else the plain drawing."""
    path = SCENERY / name
    dep(path)
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
# Static sprites with a Blender up pass, by key: the render's file name. Where it faces the sky is packed into the
# low two bits of each pixel's blue, for the game to lay snow on.
UP = {}
for s in SURFACES:
    for g in SLOPES:
        if g != 0 and g != s and g & ~s == 0:
            JOBS.append(("static", ("walls", g, s), lambda g=g, s=s: track.walls(g, s), None))
for p, piece in enumerate(track.PIECES):
    for s in TRACK_SURFACES.get(piece, [0]):
        JOBS.append(("static", ("track", p, s), lambda piece=piece, s=s: scenery(f"track_{piece}_{s}.png", lambda: track.track(piece, s)), None))
        UP[("track", p, s)] = f"track_{piece}_{s}.png"
        JOBS.append(("static", ("rails", p, s), lambda piece=piece, s=s: scenery(f"rails_{piece}_{s}.png", base.cell), None))
        UP[("rails", p, s)] = f"rails_{piece}_{s}.png"
# The rails of two or more pieces on one tile, by the tile's piece bits.
for mask in range(1, 64):
    if bin(mask).count("1") >= 2:
        JOBS.append(("static", ("railset", mask), lambda mask=mask: scenery(f"railset_{mask}.png", base.cell), None))
        UP[("railset", mask)] = f"railset_{mask}.png"
# Platforms and the engine house over their track, each with its shadow apart so the game can lay shadows first.
for a in (0, 1):
    JOBS.append(("static", ("station", a), lambda a=a: over(track.track("X" if a == 0 else "Y", 0), scenery(f"station_{a}.png", lambda: track.station(a == 0))), None))
    JOBS.append(("shadows", ("station_shadow", a), lambda a=a: scenery(f"station_{a}_shadow.png", base.cell), None))
    UP[("station", a)] = f"station_{a}.png"
    JOBS.append(("static", ("station_bare", a), lambda a=a: over(track.track("X" if a == 0 else "Y", 0), scenery(f"station_bare_{a}.png", lambda: track.station(a == 0))), None))
    JOBS.append(("shadows", ("station_bare_shadow", a), lambda a=a: scenery(f"station_bare_{a}_shadow.png", base.cell), None))
    UP[("station_bare", a)] = f"station_bare_{a}.png"
for e, edge in enumerate(EDGES):
    JOBS.append(("static", ("garage", e), lambda edge=edge: scenery(f"garage_{edge}.png", base.cell), None))
    JOBS.append(("shadows", ("garage_shadow", e), lambda edge=edge: scenery(f"garage_{edge}_shadow.png", base.cell), None))
    UP[("garage", e)] = f"garage_{edge}.png"
JOBS.append(("static", ("salt_dome",), lambda: scenery("salt_dome.png", base.cell), None))
JOBS.append(("shadows", ("salt_dome_shadow",), lambda: scenery("salt_dome_shadow.png", base.cell), None))
UP[("salt_dome",)] = "salt_dome.png"
# Docks, shipyards, locks and seaplane bases by the view edge they face; hangars and terminals by the edge their front faces.
WATERSIDE = ["dock", "shipyard", "lock", "seaplane", "hangar_small", "hangar", "terminal_small", "terminal_mid", "terminal_big"]
for name in WATERSIDE:
    for e, edge in enumerate(EDGES):
        JOBS.append(("static", (name, e), lambda name=name, edge=edge: scenery(f"{name}_{edge}.png", base.cell), None))
        JOBS.append(("shadows", (name + "_shadow", e), lambda name=name, edge=edge: scenery(f"{name}_{edge}_shadow.png", base.cell), None))
        UP[(name, e)] = f"{name}_{edge}.png"
for name in ("tower", "strip_hut"):
    JOBS.append(("static", (name,), lambda name=name: scenery(f"{name}.png", base.cell), None))
    JOBS.append(("shadows", (name + "_shadow",), lambda name=name: scenery(f"{name}_shadow.png", base.cell), None))
    UP[(name,)] = f"{name}.png"
for a in (0, 1):
    JOBS.append(("static", ("road_stop", a), lambda a=a: scenery(f"road_stop_{a}.png", base.cell), None))
    JOBS.append(("shadows", ("road_stop_shadow", a), lambda a=a: scenery(f"road_stop_{a}_shadow.png", base.cell), None))
    UP[("road_stop", a)] = f"road_stop_{a}.png"
for e, edge in enumerate(EDGES):
    JOBS.append(("static", ("depot", e), lambda edge=edge: scenery(f"depot_{edge}.png", lambda: track.depot(edge)), None))
    JOBS.append(("shadows", ("depot_shadow", e), lambda edge=edge: scenery(f"depot_{edge}_shadow.png", base.cell), None))
    UP[("depot", e)] = f"depot_{edge}.png"
for t, kind in enumerate(ground.TREES):
    for look in range(ground.TREE_LOOKS):
        JOBS.append(("static", ("tree", t, look), lambda kind=kind, look=look: scenery(f"tree_{kind}_{look}.png", lambda: ground.tree(kind, look)), None))
        JOBS.append(("shadows", ("tree_shadow", t, look), lambda kind=kind, look=look: scenery(f"tree_{kind}_{look}_shadow.png", base.cell), None))
        UP[("tree", t, look)] = f"tree_{kind}_{look}.png"
# The leafy trees in autumn and bare in winter, the bare ones with their own thinner shadows.
LEAFY = ["maple", "birch", "aspen"]
for t, kind in enumerate(ground.TREES):
    if kind not in LEAFY:
        continue
    for season in ("autumn", "bare"):
        for look in range(ground.TREE_LOOKS):
            name = f"tree_{kind}_{look}_{season}.png"
            JOBS.append(("static", ("tree_" + season, t, look), lambda name=name, kind=kind, look=look: scenery(name, lambda: ground.tree(kind, look)), None))
            UP[("tree_" + season, t, look)] = name
            if season == "bare":
                JOBS.append(("shadows", ("tree_bare_shadow", t, look), lambda name=name: scenery(name.replace(".png", "_shadow.png"), base.cell), None))
for d, kind in enumerate(ground.DECOR):
    for look in range(ground.DECOR_LOOKS):
        JOBS.append(("static", ("decor", d, look), lambda kind=kind, look=look: scenery(f"decor_{kind}_{look}.png", lambda: ground.decor(kind, look)), None))
        JOBS.append(("shadows", ("decor_shadow", d, look), lambda kind=kind, look=look: scenery(f"decor_{kind}_{look}_shadow.png", base.cell), None))
        UP[("decor", d, look)] = f"decor_{kind}_{look}.png"
for red in (0, 1):
    JOBS.append(("signals", ("signal", red), lambda red=red: scenery(f"signal_{red}.png", lambda: track.signal(red == 1)), None))
    JOBS.append(("signals", ("signal_light", red), lambda red=red: scenery(f"signal_light_{red}.png", lambda: track.signal(red == 1)), None))
# Town buildings, in the order of the base pack's houses.json, each facing every view edge, with its shadow apart.
HOUSES = [h["id"] for h in json.loads((ROOT / "packs/base/houses.json").read_text())["houses"]]
for k, kind in enumerate(HOUSES):
    for e, edge in enumerate(EDGES):
        JOBS.append(("static", ("house", k, e), lambda kind=kind, edge=edge: scenery(f"house_{kind}_{edge}.png", base.cell), None))
        JOBS.append(("shadows", ("house_shadow", k, e), lambda kind=kind, edge=edge: scenery(f"house_{kind}_{edge}_shadow.png", base.cell), None))
        UP[("house", k, e)] = f"house_{kind}_{edge}.png"
# Station buildings by era (sim stationEra), each facing every view edge, with its shadow apart.
STATION_ERAS = 5
for era in range(STATION_ERAS):
    for e, edge in enumerate(EDGES):
        JOBS.append(("static", ("station_house", era, e), lambda era=era, edge=edge: scenery(f"station_house_{era}_{edge}.png", base.cell), None))
        JOBS.append(("shadows", ("station_house_shadow", era, e), lambda era=era, edge=edge: scenery(f"station_house_{era}_{edge}_shadow.png", base.cell), None))
        UP[("station_house", era, e)] = f"station_house_{era}_{edge}.png"
# Industry pieces, every one named in the base pack's industries.json layouts, each facing every view edge, with
# its shadow apart.
PARTS = []
for ind in json.loads((ROOT / "packs/base/industries.json").read_text())["industries"]:
    for row in ind["layout"]:
        for key in row.split():
            if key not in PARTS:
                PARTS.append(key)
for k, key in enumerate(PARTS):
    for e, edge in enumerate(EDGES):
        JOBS.append(("static", ("part", k, e), lambda key=key, edge=edge: scenery(f"part_{key}_{edge}.png", base.cell), None))
        JOBS.append(("shadows", ("part_shadow", k, e), lambda key=key, edge=edge: scenery(f"part_{key}_{edge}_shadow.png", base.cell), None))
        UP[("part", k, e)] = f"part_{key}_{edge}.png"
# Bridges, a tile and a step at a time, and tunnel mouths, drawn over the map each frame with the trains.
SPAN_STYLES = ["trestle", "truss", "concrete"]
for st, style in enumerate(SPAN_STYLES):
    for a in (0, 1):
        JOBS.append(("signals", ("span_deck", st, a), lambda style=style, a=a: scenery(f"span_deck_{style}_{a}.png", base.cell), None))
        JOBS.append(("signals", ("span_pier", st, a), lambda style=style, a=a: scenery(f"span_pier_{style}_{a}.png", base.cell), None))
for e, edge in enumerate(EDGES):
    JOBS.append(("signals", ("portal", e), lambda edge=edge: scenery(f"span_portal_{edge}.png", base.cell), None))
for a in (0, 1):
    JOBS.append(("signals", ("shed", a), lambda a=a: scenery(f"span_shed_{a}.png", base.cell), None))
# The engine house again, drawn over trains on its tile so they show going in through the doors.
for e, edge in enumerate(EDGES):
    JOBS.append(("signals", ("depot_over", e), lambda edge=edge: scenery(f"depot_{edge}.png", lambda: track.depot(edge)), None))


def rendered_slope(key, h, way, build):
    """A vehicle tilted up or down a slope, from Blender, or flat if there's no render."""
    path = RENDERS / f"{key}_s{h}_{way}.png"
    dep(path)
    return Image.open(path).convert("RGBA") if path.exists() else rendered(key, h, build)


for m, (key, length, build) in enumerate(vehicles.ROSTER):
    for h in range(8):
        JOBS.append((f"vehicle_{key}", ("vehicle", m, h), lambda key=key, build=build, h=h: rendered(key, h, build), "outline"))
    for h in (0, 2, 4, 6):
        for way in ("up", "down"):
            JOBS.append((f"vehicle_{key}", ("slope", m, h, way), lambda key=key, build=build, h=h, way=way: rendered_slope(key, h, way, build), "outline"))


def waterside_consts(index):
    """Atlas lines for the docks, locks and airport buildings: each by view edge, and its shadow."""
    out = ["    /** Docks, shipyards, locks (by the edge they rise to), seaplane bases, hangars and terminals, by the view edge they face, and their shadows. On the static sheet. */"]
    for name in WATERSIDE:
        const = name.upper()
        out.append(f"    val {const} = intArrayOf({', '.join(str(index[(name, e)]) for e in range(4))})")
        out.append(f"    val {const}_SHADOW = intArrayOf({', '.join(str(index[(name + '_shadow', e)]) for e in range(4))})")
    for name in ("tower", "strip_hut"):
        out.append(f"    const val {name.upper()} = {index[(name,)]}")
        out.append(f"    const val {name.upper()}_SHADOW = {index[(name + '_shadow',)]}")
    return "\n".join(out)


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


def pack_cell(img):
    """A vehicle's cell as a pack has it: the same size whatever it holds, the tile's top corner 64 above where it stands."""
    left, top = base.HW - STAND_X, base.TOP + base.HH - STAND_Y
    return img.crop((left, top, left + CELL, top + CELL))


def render(i):
    DEPS.clear()
    sheet, key, fn, finish = JOBS[i]
    img = shrink(fn(), base.SS)
    if finish == "outline":
        img = outline(img)
    cell = pack_cell(img) if sheet.startswith("vehicle_") else None
    up = None
    if key in UP:
        dep(SCENERY / UP[key].replace(".png", "_up.png"))
    if key in UP and (SCENERY / UP[key].replace(".png", "_up.png")).exists():
        raw = Image.open(SCENERY / UP[key].replace(".png", "_up.png")).convert("L")
        up = raw.resize((raw.width // base.SS, raw.height // base.SS), Image.BOX)
    # Trimmed to where it's more than faint, so a soft shadow's last wisps don't stretch the box.
    box = img.getchannel("A").point(lambda v: 255 if v > 12 else 0).getbbox()
    if box is None:
        return i, None, (0, 0, 0, 0), cell, None, list(DEPS)
    l, t, r, b = box
    l, t = l // GRID * GRID, t // GRID * GRID
    r, b = -(-r // GRID) * GRID, -(-b // GRID) * GRID
    return i, img.crop((l, t, r, b)), (l, t, r, b), cell, up.crop((l, t, r, b)) if up else None, list(DEPS)


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


def layout(done, sheet_w=SHEET_W):
    """Packs one sheet's sprites. Returns the sheet, its snow values, each sprite's rect and where it sits from its anchor, and the height."""
    sizes = [(im.width, im.height) if im else (0, 0) for im, _, _, _ in done]
    pos, height = pack(sizes, sheet_w)
    sheet = Image.new("RGBA", (sheet_w, max(GRID, height)))
    ups = Image.new("L", sheet.size)
    for (im, _, _, up), p in zip(done, pos):
        if im:
            sheet.paste(im, p)
        if up:
            ups.paste(up, p)
    rects = []
    for (_, (l, t, r, b), _, _), p in zip(done, pos):
        rects += [p[0], p[1], r - l, b - t, l - base.HW, t - base.TOP]
    return sheet, ups, rects, height


def write_level(name, level):
    """Writes one zoom level of a laid out sheet."""
    sheet, ups = SHEETS[name]
    f = 1 << level
    out = sheet if f == 1 else shrink(sheet, f)
    if name == "static":
        out = with_up(out, ups if f == 1 else ups.resize((ups.width // f, ups.height // f), Image.BOX))
    # Shadows are black, so their sheet keeps only how dark each pixel is, a byte each.
    save_sheet(out.getchannel("A") if name == "shadows" else out, OUT_PNG / f"{name}_{level}.png")


def with_up(img, up):
    """The sheet with how much each pixel faces the sky, 0 to 3, in the low two bits of its blue."""
    px = np.array(img)
    px[..., 2] = (px[..., 2] & 0xFC) | (np.array(up) >> 6)
    return Image.fromarray(px, "RGBA")


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


TIMES = {}
# Laid out sheets, the ground texture and the vehicles' pack cells, set before the pool forks so its workers have them.
SHEETS = {}
TEX = None
PACK_CELLS = {}


def timed(name, since):
    """Adds the time since `since` to the phase called name; returns now."""
    now = time.time()
    TIMES[name] = TIMES.get(name, 0) + now - since
    return now


def code_hash():
    """A hash of the drawing code: anything in it changing redraws everything."""
    h = hashlib.sha1()
    for f in [Path(__file__).resolve()] + sorted((ROOT / "tools/art").glob("*.py")):
        h.update(f.name.encode() + f.read_bytes())
    return h.hexdigest()


def job_file(i):
    return CACHE / "jobs" / hashlib.sha1(repr((JOBS[i][:2], [d for d in JOBS[i][2].__defaults__ or () if isinstance(d, (str, int))])).encode()).hexdigest()[:24]


def load_meta(i, code):
    """The saved details of sprite i if it's still good: same code, and none of the files it read have changed."""
    f = job_file(i)
    try:
        m = json.loads(f.with_suffix(".json").read_text())
    except (OSError, ValueError):
        return None
    if m["code"] != code or not f.with_suffix(".bin").exists():
        return None
    for path, mtime, size in m["deps"]:
        try:
            st = Path(path).stat()
            cur = [st.st_mtime_ns, st.st_size]
        except OSError:
            cur = [None, None]
        if cur != [mtime, size]:
            return None
    return m


def freeze(im):
    return None if im is None else (im.mode, im.size, zlib.compress(im.tobytes(), 1))


def thaw(t):
    return None if t is None else Image.frombytes(t[0], t[1], zlib.decompress(t[2]))


def save_job(i, code, img, rect, cell, up, deps):
    f = job_file(i)
    f.parent.mkdir(parents=True, exist_ok=True)
    f.with_suffix(".bin").write_bytes(pickle.dumps((freeze(img), freeze(cell), freeze(up))))
    meta = {"code": code, "deps": deps, "rect": list(rect), "key": repr(JOBS[i][:2])}
    f.with_suffix(".json").write_text(json.dumps(meta))
    return meta


def load_job(i, meta):
    img, cell, up = pickle.loads(job_file(i).with_suffix(".bin").read_bytes())
    return thaw(img), tuple(meta["rect"]), thaw(cell), thaw(up)


def stamp(files):
    """Size and mtime of each file, to tell if one has been replaced since it was written."""
    out = []
    for f in files:
        try:
            st = f.stat()
            out.append([st.st_size, st.st_mtime_ns])
        except OSError:
            out.append(None)
    return out


def sheet_outputs(name):
    """Every file written for a sheet."""
    out = [OUT_PNG / f"{name}_{level}.webp" for level in range(LEVELS)]
    if name == "static":
        out += [OUT_PNG / f"static_snow_{level}.bin" for level in (0, 1)]
    if name.startswith("vehicle_"):
        key = name.removeprefix("vehicle_")
        out.append(BUILD_PACKS / "base/sprites" / f"{key}.png")
        out += [PACKS / "template/sprites" / f"{t}.png" for t, source, _ in TEMPLATE if source == key]
    return out


def run_task(task):
    """One piece of encoding for the pool: a zoom level of a sheet, of the ground, or a vehicle's pack sprites. Returns its group and seconds."""
    start = time.time()
    kind, name, level = task
    if kind == "level":
        write_level(name, level)
    elif kind == "ground":
        f = 1 << level
        save_sheet(TEX.resize((TEX.width // f, TEX.height // f), Image.BOX), OUT_PNG / f"ground_{level}.png")
    else:
        write_pack_sheets(name)
    return name.split("_")[0] if kind == "level" else kind, time.time() - start


def main():
    full = "--full" in sys.argv[1:]
    global TEX
    t = time.time()
    code = code_hash()
    ctx = multiprocessing.get_context("fork")
    workers = min(os.cpu_count() or 4, 12)
    metas = [None if full else load_meta(i, code) for i in range(len(JOBS))]
    stale = [i for i, m in enumerate(metas) if m is None]
    fresh = {}
    if stale:
        with ctx.Pool(workers) as pool:
            for i, img, rect, cell, up, deps in pool.imap_unordered(render, stale, chunksize=4):
                fresh[i] = (img, rect, cell, up)
                metas[i] = save_job(i, code, img, rect, cell, up, deps)
    print(f"{len(stale)} of {len(JOBS)} sprites drawn.")
    t = timed("render", t)

    OUT_PNG.mkdir(parents=True, exist_ok=True)
    names = []
    for job in JOBS:
        if job[0] not in names:
            names.append(job[0])
    index = {}
    rects = {}
    heights = {}
    states = {}
    dirty = []
    for name in names:
        mine = [i for i, job in enumerate(JOBS) if job[0] == name]
        for n, i in enumerate(mine):
            index[JOBS[i][1]] = n
        digest = hashlib.sha1((code + json.dumps([[job_file(i).name, metas[i]["deps"]] for i in mine])).encode()).hexdigest()
        state_file = CACHE / "sheets" / f"{name}.json"
        try:
            state = json.loads(state_file.read_text())
        except (OSError, ValueError):
            state = None
        if not full and state and state["digest"] == digest and state.get("stamp") == stamp(sheet_outputs(name)):
            rects[name], heights[name] = state["rects"], state["height"]
        else:
            dirty.append((name, mine))
            state_file.unlink(missing_ok=True)
        states[name] = (state_file, digest)
    t = timed("check", t)

    # Lay out the sheets that changed, then encode all their levels, the ground and the vehicle packs across the pool.
    tasks = []
    for name, mine in dirty:
        done = [fresh[i] if i in fresh else load_job(i, metas[i]) for i in mine]
        sheet, ups, rects[name], heights[name] = layout(done, SHEET_W if name in ("static", "shadows") else 1024)
        SHEETS[name] = (sheet, ups)
        tasks += [("level", name, level) for level in range(LEVELS)]
        if name.startswith("vehicle_"):
            cells = [None] * 16
            for i, (_, _, cell, _) in zip(mine, done):
                key = JOBS[i][1]
                cells[key[2] if key[0] == "vehicle" else 8 + key[2] // 2 * 2 + (0 if key[3] == "up" else 1)] = cell
            PACK_CELLS[name.removeprefix("vehicle_")] = cells
            tasks.append(("pack", name.removeprefix("vehicle_"), 0))
    del fresh
    t = timed("layout", t)
    ground_state = CACHE / "sheets" / "ground.json"
    ground_files = [OUT_PNG / f"ground_{level}.webp" for level in range(LEVELS)]
    try:
        gs = json.loads(ground_state.read_text())
        ground_ok = gs["digest"] == code and gs["stamp"] == stamp([OUT_PNG / f"ground_{level}.webp" for level in range(LEVELS)])
    except (OSError, ValueError):
        ground_ok = False
    if full or not ground_ok:
        ground_state.unlink(missing_ok=True)
        TEX = textures.sheet().convert("RGB")
        tasks += [("ground", "ground", level) for level in range(LEVELS)]
        t = timed("ground texture", t)
        ground_dirty = True
    else:
        ground_dirty = False
    # Biggest first, so the slow ones start early.
    weight = {"static": 0, "ground": 1, "pack": 1, "shadows": 2, "signals": 3}
    tasks.sort(key=lambda k: (weight.get(k[1].split("_")[0] if k[0] == "level" else k[0], 4), k[1], k[2]))
    cpu = {}
    if tasks:
        with ctx.Pool(min(workers, len(tasks))) as pool:
            for group, secs in pool.imap_unordered(run_task, tasks, chunksize=1):
                cpu[group] = cpu.get(group, 0) + secs
    t = timed("encode", t)
    for name, _ in dirty:
        state_file, digest = states[name]
        state_file.parent.mkdir(parents=True, exist_ok=True)
        state_file.write_text(json.dumps({"stamp": stamp(sheet_outputs(name)), "digest": digest, "rects": rects[name], "height": heights[name]}))
    if ground_dirty:
        ground_state.write_text(json.dumps({"digest": code, "stamp": stamp(ground_files)}))
    for name in names:
        if not name.startswith("vehicle_"):
            print(f"{name}: {len(rects[name]) // 6} sprites, {heights[name]} high at level 0")
    biggest = max(len(rects[n]) for n in names if n.startswith("vehicle_")) // 6
    print(f"{len([n for n in names if n.startswith('vehicle_')])} vehicle sheets, {biggest} sprites each")

    walls = [index.get(("walls", g, s), -1) for g in range(16) for s in range(16)]
    tracks = [index.get(("track", p, s), -1) for p in range(6) for s in range(16)]
    rails = [index.get(("rails", p, s), -1) for p in range(6) for s in range(16)]
    railsets = [index.get(("railset", mask), -1) for mask in range(64)]
    trees = [index[("tree", t, lk)] for t in range(len(ground.TREES)) for lk in range(ground.TREE_LOOKS)]
    autumn = [index.get(("tree_autumn", t, lk), index[("tree", t, lk)]) for t in range(len(ground.TREES)) for lk in range(ground.TREE_LOOKS)]
    bare = [index.get(("tree_bare", t, lk), index[("tree", t, lk)]) for t in range(len(ground.TREES)) for lk in range(ground.TREE_LOOKS)]
    tree_shadows = [index[("tree_shadow", t, lk)] for t in range(len(ground.TREES)) for lk in range(ground.TREE_LOOKS)]
    bare_shadows = [index.get(("tree_bare_shadow", t, lk), index[("tree_shadow", t, lk)]) for t in range(len(ground.TREES)) for lk in range(ground.TREE_LOOKS)]
    decor = [index[("decor", d, lk)] for d in range(len(ground.DECOR)) for lk in range(ground.DECOR_LOOKS)]
    decor_shadows = [index[("decor_shadow", d, lk)] for d in range(len(ground.DECOR)) for lk in range(ground.DECOR_LOOKS)]
    # Each vehicle's sixteen sprites in its own sheet, in a fixed order: eight headings, then the slopes.
    vehicle_rects = []
    for m, (key, _, _) in enumerate(vehicles.ROSTER):
        r = rects[f"vehicle_{key}"]
        order = [index[("vehicle", m, h)] for h in range(8)] + [index[("slope", m, h, way)] for h in (0, 2, 4, 6) for way in ("up", "down")]
        for n in order:
            vehicle_rects += r[n * 6:n * 6 + 6]
    vehicle_keys = ", ".join(f'"{key}"' for key, _, _ in vehicles.ROSTER)
    kinds = list(textures.KINDS)
    t = time.time()
    kind_consts = "\n".join(f"    const val {k.upper()} = {i}" for i, k in enumerate(kinds))
    atlas = (f"""package com.rm.interchange.map

// Written by tools/gen_tiles.py. Don't edit by hand.

/**
 * Where everything is in the sheets, sharpest level first, each level half the
 * last: files/ground_<level>.webp, the ground textures; files/static_<level>.webp,
 * what's baked into the map; files/signals_<level>.webp and a sheet for each
 * vehicle, files/vehicle_<key>_<level>.webp, what's drawn over it.
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

    /** Numbers written out in a string: big tables as literals would make the class too large to load. */
    private fun ints(s: String): IntArray = s.split(',').map {{ it.toInt() }}.toIntArray()

    /** Texels a tile across at level 0, and how many tiles a ground texture spans before it repeats. */
    const val TEXELS = {textures.TEXELS}
    const val PERIOD = {textures.PERIOD}

    // Ground kinds, in the order of their textures on the ground sheet: the game's land kinds, then beach sand and water.
{kind_consts}
    /** The grey noise that makes the edges between kinds ragged, after the kinds on the ground sheet. */
    const val RAG = {len(kinds)}

    /** Each sprite's place on its sheet and where it sits from its anchor, six numbers each. */
    val STATIC_RECTS = ints("{",".join(str(x) for x in rects["static"])}")

    /** Shadows, on files/shadows_<level>.webp: how dark, a byte a pixel, to darken what's under them. */
    val SHADOW_RECTS = ints("{",".join(str(x) for x in rects["shadows"])}")

    val SIGNAL_RECTS = ints("{",".join(str(x) for x in rects["signals"])}")

    /** Each vehicle's sheet is files/vehicle_<key>_<level>.webp. */
    val VEHICLE_KEYS = listOf({vehicle_keys})

    /**
     * Sixteen sprites a vehicle, in its own sheet: the eight headings in the view (0 down the u axis, on round
     * towards v), then the four straight headings climbing and going down a slope, at heading / 2 * 2 + 0 or 1.
     */
    val VEHICLE_RECTS = ints("{",".join(str(x) for x in vehicle_rects)}")

    /** A foundation's walls, at land shape * 16 + top shape, or -1. On the static sheet. */
    val WALLS = intArrayOf(
        {arr(walls)},
    )

    /** A track piece's bed in the view, at piece index * 16 + the shape it lies on, or -1. On the static sheet. */
    val TRACK = intArrayOf(
        {arr(tracks)},
    )

    /** A track piece's ties and rails, drawn over every piece's bed on the tile, as [TRACK]. On the static sheet. */
    val RAILS = intArrayOf(
        {arr(rails)},
    )

    /**
     * The ties and rails of a level tile with two or more pieces, by its view piece bits, gapped where they cross: a
     * diamond, a switch's frog. -1 for fewer than two. On the static sheet.
     */
    val RAIL_SETS = intArrayOf(
        {arr(railsets)},
    )

    /** A platform with its track, along view u (0) or v (1), and its shadow, on the shadows sheet. On the static sheet. */
    val STATION = intArrayOf({index[("station", 0)]}, {index[("station", 1)]})
    val STATION_SHADOW = intArrayOf({index[("station_shadow", 0)]}, {index[("station_shadow", 1)]})
    /** A platform with no canopy, for the one in front of a station building, and its shadow. */
    val STATION_BARE = intArrayOf({index[("station_bare", 0)]}, {index[("station_bare", 1)]})
    val STATION_BARE_SHADOW = intArrayOf({index[("station_bare_shadow", 0)]}, {index[("station_bare_shadow", 1)]})

    /** A depot with its doors at view edge XM, XP, YM or YP, and its shadow, on the shadows sheet. On the static sheet. */
    val DEPOT = intArrayOf({", ".join(str(index[("depot", e)]) for e in range(4))})
    val DEPOT_SHADOW = intArrayOf({", ".join(str(index[("depot_shadow", e)]) for e in range(4))})

    /** A garage with its door at view edge XM, XP, YM or YP, and a road stop along view u (0) or v (1), with their shadows. On the static sheet. */
    val GARAGE = intArrayOf({", ".join(str(index[("garage", e)]) for e in range(4))})
    val GARAGE_SHADOW = intArrayOf({", ".join(str(index[("garage_shadow", e)]) for e in range(4))})
    val ROAD_STOP = intArrayOf({index[("road_stop", 0)]}, {index[("road_stop", 1)]})
    val ROAD_STOP_SHADOW = intArrayOf({index[("road_stop_shadow", 0)]}, {index[("road_stop_shadow", 1)]})
    /** A salt dome, and its shadow. On the static sheet. */
    const val SALT_DOME = {index[("salt_dome",)]}
    const val SALT_DOME_SHADOW = {index[("salt_dome_shadow",)]}
{waterside_consts(index)}

    const val TREE_LOOKS = {ground.TREE_LOOKS}
    /** A tree, at kind * TREE_LOOKS + look, kinds in the order of the game's trees, and its shadow, on the shadows sheet. On the static sheet. */
    val TREE = intArrayOf({arr(trees)})
    val TREE_SHADOW = intArrayOf({arr(tree_shadows)})
    /** The same trees in autumn colours, and bare for winter with their own shadows; the evergreens as they are. */
    val TREE_AUTUMN = intArrayOf({arr(autumn)})
    val TREE_BARE = intArrayOf({arr(bare)})
    val TREE_BARE_SHADOW = intArrayOf({arr(bare_shadows)})

    const val DECOR_LOOKS = {ground.DECOR_LOOKS}
    /** Boulders, shrubs and tall grass, at kind * DECOR_LOOKS + look, and their shadows, on the shadows sheet. On the static sheet. */
    val DECOR = intArrayOf({arr(decor)})
    val DECOR_SHADOW = intArrayOf({arr(decor_shadows)})

    /** The town buildings drawn here, by the id in the base pack's houses.json. */
    val HOUSE_KEYS = listOf({", ".join(f'"{k}"' for k in HOUSES)})
    /** A building, at its place in HOUSE_KEYS * 4 + the view edge it faces, and its shadow, on the shadows sheet. On the static sheet. */
    val HOUSE = intArrayOf({arr([index[("house", k, e)] for k in range(len(HOUSES)) for e in range(4)])})
    val HOUSE_SHADOW = intArrayOf({arr([index[("house_shadow", k, e)] for k in range(len(HOUSES)) for e in range(4)])})
    /** A station building, at its era * 4 + the view edge it faces, and its shadow, on the shadows sheet. On the static sheet. */
    val STATION_HOUSE = intArrayOf({arr([index[("station_house", k, e)] for k in range(STATION_ERAS) for e in range(4)])})
    val STATION_HOUSE_SHADOW = intArrayOf({arr([index[("station_house_shadow", k, e)] for k in range(STATION_ERAS) for e in range(4)])})

    /** The industry pieces drawn here, by the names in the base pack's industries.json layouts. */
    val PART_KEYS = listOf({", ".join(f'"{k}"' for k in PARTS)})
    /** An industry piece, at its place in PART_KEYS * 4 + the view edge it faces, and its shadow, on the shadows sheet. On the static sheet. */
    val PART = intArrayOf({arr([index[("part", k, e)] for k in range(len(PARTS)) for e in range(4)])})
    val PART_SHADOW = intArrayOf({arr([index[("part_shadow", k, e)] for k in range(len(PARTS)) for e in range(4)])})

    /** A signal showing clear (0) or stop (1). On the signals sheet. */
    val SIGNAL = intArrayOf({index[("signal", 0)]}, {index[("signal", 1)]})
    /** A colour light signal showing clear (0) or stop (1), from [SIGNAL_LIGHTS_FROM]. On the signals sheet. */
    val SIGNAL_LIGHT = intArrayOf({index[("signal_light", 0)]}, {index[("signal_light", 1)]})
    const val SIGNAL_LIGHTS_FROM = 1930

    /** A tile of bridge and a step of what holds it up, at style * 2 + 0 along view u or 1 along v; a tunnel mouth opening to view edge XM, XP, YM or YP. On the signals sheet. */
    val SPAN_DECK = intArrayOf({", ".join(str(index[("span_deck", st, a)]) for st in range(len(SPAN_STYLES)) for a in (0, 1))})
    val SPAN_PIER = intArrayOf({", ".join(str(index[("span_pier", st, a)]) for st in range(len(SPAN_STYLES)) for a in (0, 1))})
    val PORTAL = intArrayOf({", ".join(str(index[("portal", e)]) for e in range(4))})
    /** A snow shed over track along view u (0) or v (1), its solid wall on the side away from the camera. On the signals sheet. */
    val SHED = intArrayOf({index[("shed", 0)]}, {index[("shed", 1)]})

    /** The engine house with its doors at view edge XM, XP, YM or YP, drawn over trains on its tile. On the signals sheet. */
    val DEPOT_OVER = intArrayOf({", ".join(str(index[("depot_over", e)]) for e in range(4))})
}}
""")
    if not OUT_KT.exists() or OUT_KT.read_text() != atlas:
        OUT_KT.write_text(atlas)
    t = timed("Atlas.kt", t)
    write_packs()
    t = timed("packs", t)

    # Files from older layouts and sprites no longer drawn.
    keep = {f for name in names for f in sheet_outputs(name)} | set(ground_files)
    for pattern in ("atlas_*.png", "static_*.png", "shadows_*.png", "moving_*.png", "signals_*.png", "vehicle_*.png", "ground_*.png",
                    "static_*.webp", "static_snow_*.bin", "shadows_*.webp", "signals_*.webp", "vehicle_*.webp", "ground_*.webp"):
        for old in OUT_PNG.glob(pattern):
            if old not in keep:
                old.unlink()
    live = {job_file(i).name for i in range(len(JOBS))}
    for old in (CACHE / "jobs").glob("*"):
        if old.stem not in live:
            old.unlink()
    timed("cleanup", t)
    print("Seconds: " + ", ".join(f"{k} {v:.1f}" for k, v in TIMES.items()))
    print("Encoding seconds summed over workers: " + ", ".join(f"{k} {v:.1f}" for k, v in sorted(cpu.items())))


def write_json(path, data):
    """JSON two spaces in, with each { "en": ..., "fr": ... } on one line."""
    text = json.dumps(data, indent=2, ensure_ascii=False)
    text = re.sub(r'\{\n\s+("en": "[^"\n]*")(?:,\n\s+("fr": "[^"\n]*"))?\n\s+\}', lambda m: "{ " + ", ".join(g for g in m.groups() if g) + " }", text)
    path.write_text(text + "\n")


def zip_pack(src, sheets, out):
    """A pack's zip: its manifest and vehicles from src, and its sprite sheets."""
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for name in ("manifest.json", "vehicles.json"):
            z.write(src / name, name)
        for name, path in sheets.items():
            z.write(path, f"sprites/{name}.png")


def recolour(img, turn):
    """A new livery: the hue of everything coloured turned by turn, a fraction of the way round; greys and black stay."""
    alpha = img.getchannel("A")
    h, s_, v = img.convert("RGB").convert("HSV").split()
    h = h.point(lambda x: (x + int(turn * 256)) % 256)
    out = Image.merge("HSV", (h, s_, v)).convert("RGB").convert("RGBA")
    out.putalpha(alpha)
    return out


# The template pack: (its id for the vehicle, the base drawing it's recoloured from, how far round the hue turns).
TEMPLATE = [("prairie", "ten_wheeler", 0.45), ("prairie_tender", "ten_wheeler_tender", 0.45), ("ore_car", "hopper", 0.08), ("van", "caboose", 0.6)]


def sheet_of(name, turn=0.0):
    """A vehicle's sixteen cells as a pack sprite sheet, its hue turned if turn."""
    out = Image.new("RGBA", (CELL * 4, CELL * 4))
    for n, cell in enumerate(PACK_CELLS[name]):
        out.paste(recolour(cell, turn) if turn else cell, (n % 4 * CELL, n // 4 * CELL))
    return out


def write_pack_sheets(key):
    """A vehicle's base pack sheet, and any template ones recoloured from it."""
    (BUILD_PACKS / "base/sprites").mkdir(parents=True, exist_ok=True)
    sheet_of(key).save(BUILD_PACKS / "base/sprites" / f"{key}.png", optimize=True)
    for name, source, turn in TEMPLATE:
        if source == key:
            (PACKS / "template/sprites").mkdir(parents=True, exist_ok=True)
            sheet_of(source, turn).save(PACKS / "template/sprites" / f"{name}.png", optimize=True)


def write_packs():
    """The base and template packs, zipped every run as their manifests and vehicles are edited by hand."""
    base_sheets = {key: BUILD_PACKS / "base/sprites" / f"{key}.png" for key, _, _ in vehicles.ROSTER}
    # The base pack's lengths come from the drawings.
    lengths = {key: length for key, length, _ in vehicles.ROSTER}
    data = json.loads((PACKS / "base/vehicles.json").read_text())
    for v in data["vehicles"]:
        v["length"] = lengths[v.get("sprite", v["id"])]
    write_json(PACKS / "base/vehicles.json", data)
    template = PACKS / "template"
    template_sheets = {name: template / "sprites" / f"{name}.png" for name, _, _ in TEMPLATE}
    zip_pack(PACKS / "base", base_sheets, BUILD_PACKS / "base.ichpack")
    zip_pack(template, template_sheets, BUILD_PACKS / "template.ichpack")
    print(f"Packs: {len(base_sheets)} base sheets, {len(template_sheets)} template sheets, in {BUILD_PACKS}")


if __name__ == "__main__":
    main()
