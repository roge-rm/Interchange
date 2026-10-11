"""Industry pieces, one to a tile, for the layouts in packs/base/industries.json.

In the same terms as the town buildings (tools/blender/houses.py): x across,
y along, z in steps, the tile from -0.5 to 0.5 each way, built facing +y and
turned by place(). Pieces of one industry share their colours, so they read as
one place across their tiles.
"""
import math
import random

import houses
import rig
import scenery
from houses import STOREY, gable, glass, hip, paved, slab, windows
from parts import material


def ground(m, colour="#6e5c46", name="yard dirt"):
    """The tile's working ground: packed dirt or a yard, just over the land."""
    m.box(-0.5, 0.5, -0.5, 0.5, 0, 0.02, material(name + " " + colour, colour, rough=1.0))


def heap(m, x, y, r, h, colour, seed, name="heap"):
    """A heap of loose stuff, coal, ore or slag: a broad mound with a rough top and a few lumps on it."""
    mat = material(name + " " + colour, colour, rough=1.0)
    m.lathe(x, y, 0, [(r, 0), (r * 0.92, h * 0.25), (r * 0.7, h * 0.6), (r * 0.4, h * 0.88), (r * 0.12, h), (0.0, h * 1.02)], mat, segments=20)
    rng = random.Random(seed)
    for _ in range(7):
        a = rng.uniform(0, math.tau)
        d = rng.uniform(0.2, 0.75) * r
        z = h * (1 - d / r) * 0.9
        scenery.blob(m, x + d * math.cos(a), y + d * math.sin(a), z, r * rng.uniform(0.12, 0.2), mat, seed * 11 + _, squash=0.6)


def logs(m, x0, x1, y, z, r, n, mat):
    """A row of n logs lying across x from x0 to x1, at y, their bottoms z steps up."""
    for k in range(n):
        yy = y + (k - (n - 1) / 2) * r * 2.05
        m.tube_x(x0, x1, yy, z + r / rig.RISE, r, mat, segments=10)


def log_stack(m, x0, x1, y, r, rows, mat):
    """A pyramid of logs: rows of them, each one fewer on top."""
    rise = r * 1.8 / rig.RISE
    for k in range(rows):
        logs(m, x0, x1, y, k * rise, r, rows - k + 1, mat)


def stack(m, x, y, z0, z1, r, mat, band=None):
    """A round chimney stack with a band near its top."""
    m.upright(x, y, z0, z1, r * 1.1, r, mat, segments=16)
    if band is not None:
        m.upright(x, y, z1 - 0.5, z1 - 0.3, r * 1.18, r * 1.18, band, segments=16)


def tank(m, x, y, r, h, mat, roof=None):
    """A round storage tank with a shallow cone roof and a ladder up its side."""
    m.upright(x, y, 0, h, r, r, mat, segments=28)
    m.upright(x, y, h, h + 0.25, r, r * 0.1, roof or mat, segments=28)
    rail = material("tank ladder", "#3a3a3a", metal=0.5, rough=0.5)
    m.rod((x + r * 0.98, y, 0), (x + r * 0.98, y, h), 0.004, rail)


def cow(m, x, y, turn, coat):
    """A beef cow at x, y facing round by turn radians: body, head and legs."""
    c, s = math.cos(turn), math.sin(turn)

    def at(dx, dy):
        return x + dx * c - dy * s, y + dx * s + dy * c
    body = material("cow " + coat, coat, rough=0.95)
    bx, by = at(0, 0)
    m.box(bx - 0.018, bx + 0.018, by - 0.018, by + 0.018, 0.35, 0.62, body, 0.008)
    hx, hy = at(0, 0.035)
    m.box(hx - 0.012, hx + 0.012, hy - 0.012, hy + 0.012, 0.45, 0.62, body, 0.005)
    for dx, dy in ((-0.011, -0.012), (0.011, -0.012), (-0.011, 0.012), (0.011, 0.012)):
        lx, ly = at(dx, dy)
        m.box(lx - 0.004, lx + 0.004, ly - 0.004, ly + 0.004, 0, 0.36, body)


# ---- forestry ---------------------------------------------------------------------------------

LOG = ("logs", "#8a6a48")
WEATHERED = ("weathered planks", "#8a7a64")


def bunkhouse(m):
    ground(m)
    logs_mat = material("log walls", "#6e4a2c", rough=0.95)
    roof = material("cedar shingles", "#5c4a3a", rough=0.9)
    x0, x1, y0, y1 = -0.32, 0.32, -0.12, 0.14
    m.box(x0, x1, y0, y1, 0, 1.4, logs_mat, 0.004)
    z = 0.14
    while z < 1.4:
        m.box(x0 - 0.002, x1 + 0.002, y0 - 0.002, y1 + 0.002, z, z + 0.025, material("chinking", "#c8bca0", rough=1.0))
        z += 0.17
    gable(m, x0, x1, y0, y1, 1.4, 2.3, roof, logs_mat, along_x=True, over=0.04)
    windows(m, x0, x1, y0, y1, 0, 1, 4, 1, glass(), logs_mat, skip_door=True)
    houses.door(m, 0.0, y1, material("plank door", "#4a3220", rough=0.9), logs_mat)
    houses.chimney(m, 0.22, 0.0, 1.8, 2.8, material("stone chimney", "#7c766c", rough=0.95), 0.035)
    # Cordwood and a cook's woodpile, a sawhorse.
    log_stack(m, -0.35, -0.15, 0.32, 0.016, 3, material(*LOG, rough=0.9))
    scenery.blob(m, 0.38, -0.36, 2.6, 0.12, scenery.leafy("camp spruce", "#1e3a24", "#2e5a34"), 41, squash=1.6)


def log_pile(m):
    ground(m)
    wood = material(*LOG, rough=0.9)
    log_stack(m, -0.4, 0.4, -0.2, 0.026, 4, wood)
    log_stack(m, -0.36, 0.36, 0.22, 0.024, 3, wood)


def camp_shed(m):
    ground(m)
    wood = material(*WEATHERED, rough=0.9)
    roof = material("tar paper", "#3a3a3a", rough=0.95)
    # An open-fronted shed for the horses and sleighs.
    m.box(-0.34, 0.34, -0.3, -0.26, 0, 1.5, wood)
    for x in (-0.34, 0.34):
        m.box(x - 0.02, x + 0.02, -0.3, 0.1, 0, 1.5, wood)
    slab(m, [(-0.38, -0.34, 1.75), (0.38, -0.34, 1.75), (0.38, 0.14, 1.4), (-0.38, 0.14, 1.4)], 0.06, roof)
    # A log sleigh out front, loaded.
    runner = material("sleigh runner", "#4a3a2a", rough=0.8)
    for x in (-0.07, 0.07):
        m.box(x - 0.008, x + 0.008, 0.18, 0.44, 0.0, 0.08, runner)
    m.box(-0.09, 0.09, 0.2, 0.42, 0.08, 0.14, runner)
    logs(m, -0.1, 0.1, 0.31, 0.14, 0.02, 4, material(*LOG, rough=0.9))


def sawmill(m):
    ground(m, "#7a6450", "sawdust yard")
    wood = material("mill boards", "#8e7656", rough=0.9)
    roof = material("mill roof", "#5a5650", metal=0.3, rough=0.6)
    x0, x1, y0, y1 = -0.3, 0.3, -0.46, 0.46
    m.box(x0, x1, y0, y1, 0, 2.3, wood, 0.004)
    for k in range(1, 12):
        y = y0 + (y1 - y0) * k / 12
        for x in (x0, x1):
            m.box(x - 0.003, x + 0.003, y - 0.004, y + 0.004, 0, 2.3, material("mill battens", "#6e5a40", rough=0.9))
    gable(m, x0, x1, y0, y1, 2.3, 3.5, roof, wood, along_x=False, over=0.03)
    windows(m, x0, x1, y0, y1, 0.6, 1, 0, 6, glass(), wood)
    # A clerestory along the ridge to let the dust out.
    m.box(-0.06, 0.06, y0 + 0.05, y1 - 0.05, 3.4, 3.75, wood)
    slab(m, [(-0.09, y0 + 0.04, 3.75), (0.09, y0 + 0.04, 3.75), (0.09, y1 - 0.04, 3.75), (-0.09, y1 - 0.04, 3.75)], 0.05, roof)


def log_deck(m):
    ground(m, "#5e4e3e", "wet log deck")
    wood = material(*LOG, rough=0.9)
    # The jack ladder, a ramp carrying logs up into the mill, and the logs waiting at its foot.
    frame = material("jack ladder", "#5a4630", rough=0.9)
    m.wedge([(-0.06, -0.5, 1.8), (0.06, -0.5, 1.8), (0.06, 0.2, 0.0), (-0.06, 0.2, 0.0)],
            [(-0.06, -0.5, 1.95), (0.06, -0.5, 1.95), (0.06, 0.2, 0.15), (-0.06, 0.2, 0.15)], frame)
    for k in range(4):
        y = -0.4 + k * 0.17
        m.box(-0.07, 0.07, y - 0.01, y + 0.01, 0, 1.8 - (y + 0.5) * 2.5, frame)
    log_stack(m, 0.12, 0.44, 0.0, 0.022, 4, wood)
    log_stack(m, -0.44, -0.12, 0.26, 0.022, 3, wood)


def burner(m):
    """The teepee burner, where a sawmill burned its waste: a tall steel cone with a mesh dome."""
    ground(m, "#5e5650", "cinders")
    steel = material("burner rust", "#6a4636", metal=0.3, rough=0.8)
    mesh = material("burner screen", "#3a3634", metal=0.4, rough=0.6)
    m.upright(0, 0, 0, 5.2, 0.34, 0.12, steel, segments=24)
    m.upright(0, 0, 5.2, 5.5, 0.12, 0.12, mesh, segments=24)
    m.lathe(0, 0, 5.5, [(0.12, 0), (0.11, 0.15), (0.07, 0.3), (0.0, 0.38)], mesh, segments=24)
    m.box(-0.06, 0.06, 0.31, 0.36, 0, 0.6, material("burner door", "#2a2624", rough=0.8))
    # The conveyor feeding it from the mill.
    m.wedge([(-0.03, -0.5, 1.0), (0.03, -0.5, 1.0), (0.03, -0.2, 3.6), (-0.03, -0.2, 3.6)],
            [(-0.03, -0.5, 1.12), (0.03, -0.5, 1.12), (0.03, -0.2, 3.72), (-0.03, -0.2, 3.72)], material("conveyor", "#4a4038", rough=0.8))


def lumber_stack(m):
    ground(m, "#7a6a54", "lumber yard")
    lumber = material("fresh lumber", "#d0b080", rough=0.8)
    sticker = material("lumber stickers", "#9a7a50", rough=0.9)
    for cx, cy, h in ((-0.22, -0.22, 0.9), (0.22, -0.22, 1.2), (-0.22, 0.22, 1.1), (0.22, 0.22, 0.7)):
        z = 0.0
        while z < h:
            m.box(cx - 0.15, cx + 0.15, cy - 0.15, cy + 0.15, z, z + 0.12, lumber)
            for s in (-0.11, 0.0, 0.11):
                m.box(cx - 0.155, cx + 0.155, cy + s - 0.008, cy + s + 0.008, z + 0.12, z + 0.16, sticker)
            z += 0.16


# ---- grain and flour ------------------------------------------------------------------------------

def barn(m):
    ground(m, "#5e6a3a", "barnyard")
    red = material("barn red", "#8e2e22", rough=0.85)
    trim = material("barn trim", "#e8e2d4", rough=0.7)
    roof = material("barn roof", "#4a4e52", metal=0.3, rough=0.6)
    x0, x1, y0, y1 = -0.34, 0.34, -0.26, 0.24
    m.box(x0, x1, y0, y1, 0, 2.0, red, 0.004)
    # A gambrel roof: steep lower slopes, shallow upper ones.
    for ya, ym, yc in ((y0 - 0.03, y0 + 0.07, (y0 + y1) / 2), (y1 + 0.03, y1 - 0.07, (y0 + y1) / 2)):
        slab(m, [(x0 - 0.02, ya, 2.0), (x1 + 0.02, ya, 2.0), (x1 + 0.02, ym, 3.3), (x0 - 0.02, ym, 3.3)], 0.08, roof)
        slab(m, [(x0 - 0.02, ym, 3.3), (x1 + 0.02, ym, 3.3), (x1 + 0.02, yc, 3.9), (x0 - 0.02, yc, 3.9)], 0.08, roof)
    for x in (x0, x1):
        yc = (y0 + y1) / 2
        m.wedge([(x - 0.003, y0, 2.0), (x - 0.003, y1, 2.0), (x - 0.003, y1 - 0.07, 3.3), (x - 0.003, yc, 3.9), (x - 0.003, y0 + 0.07, 3.3)],
                [(x + 0.003, y0, 2.0), (x + 0.003, y1, 2.0), (x + 0.003, y1 - 0.07, 3.3), (x + 0.003, yc, 3.9), (x + 0.003, y0 + 0.07, 3.3)], red)
    # Big doors with their white cross braces, and the hay door above.
    m.box(-0.12, 0.12, y1, y1 + 0.006, 0, 1.6, material("barn doors", "#7a2a20", rough=0.85))
    for a, b in (((-0.12, 0.0), (0.0, 1.6)), ((0.0, 0.12), (1.6, 0.0))):
        m.rod((a[0], y1 + 0.008, b[0]), (a[1], y1 + 0.008, b[1]), 0.006, trim)
    m.box(-0.06, 0.06, y1, y1 + 0.006, 2.3, 2.9, trim)
    m.box(-0.13, 0.13, y1, y1 + 0.007, 1.6, 1.66, trim)
    # Round hay bales by the side.
    hay = material("hay bales", "#c8a858", rough=1.0)
    for k in range(3):
        m.tube_x(0.36, 0.44, -0.3 + k * 0.08, 0.17, 0.035, hay, segments=16)


def silo(m):
    ground(m, "#5e6a3a", "barnyard")
    stave = material("silo staves", "#b8b0a2", rough=0.85)
    dome = material("silo dome", "#8a8e92", metal=0.5, rough=0.4)
    for x, y, r, h in ((-0.12, -0.08, 0.13, 5.0), (0.2, 0.18, 0.1, 4.2)):
        m.upright(x, y, 0, h, r, r, stave, segments=28)
        for k in range(1, int(h / 0.6)):
            m.upright(x, y, k * 0.6, k * 0.6 + 0.04, r * 1.02, r * 1.02, material("silo hoops", "#5a5650", metal=0.6, rough=0.4), segments=28)
        m.lathe(x, y, h, [(r, 0), (r * 0.9, 0.25), (r * 0.6, 0.45), (0.0, 0.55)], dome, segments=28)
    # A wagon of grain beside them.
    wagon = material("grain wagon", "#6a8a9a", rough=0.7)
    m.box(-0.38, -0.2, 0.25, 0.42, 0.25, 0.6, wagon, 0.004)
    m.box(-0.37, -0.21, 0.26, 0.41, 0.58, 0.66, material("wheat", "#d8b860", rough=1.0))
    for x in (-0.36, -0.22):
        for y in (0.27, 0.4):
            m.tube_x(x - 0.01, x + 0.01, y, 0.12, 0.05, material("wagon wheel", "#3a3028", rough=0.8), segments=12)


def wheat_field(m, look=0):
    """A field of wheat in its rows, to its edges, with a post and wire fence on two sides."""
    m.box(-0.5, 0.5, -0.5, 0.5, 0, 0.02, material("field soil", "#5a4a32", rough=1.0))
    wheat = material("ripe wheat", "#d4b258", rough=1.0)
    wheat_dark = material("wheat shade", "#b89040", rough=1.0)
    for k in range(14):
        y = -0.46 + k * 0.071
        m.box(-0.47, 0.47, y - 0.025, y + 0.025, 0.02, 0.32, wheat if k % 2 else wheat_dark, 0.006)
    post = material("fence post", "#6a5440", rough=0.9)
    for k in range(9):
        t = -0.48 + k * 0.12
        for x, y in ((t, -0.49), (-0.49, t)):
            m.box(x - 0.006, x + 0.006, y - 0.006, y + 0.006, 0, 0.45, post)
    for z in (0.2, 0.38):
        m.rod((-0.49, -0.49, z), (0.49, -0.49, z), 0.002, post)
        m.rod((-0.49, -0.49, z), (-0.49, 0.49, z), 0.002, post)


def mill_building(m):
    ground(m, "#8a847a", "mill yard paving")
    brick = material("mill brick", "#8e4a36", rough=0.9)
    trim = material("mill stone", "#c8bca8", rough=0.8)
    x0, x1, y0, y1 = -0.34, 0.34, -0.4, 0.4
    top = 5 * STOREY + 0.2
    m.box(x0, x1, y0, y1, 0, top, brick, 0.004)
    windows(m, x0, x1, y0, y1, 0.1, 5, 4, 5, glass(), trim)
    gable(m, x0, x1, y0, y1, top, top + 1.1, material("mill slate", "#4e5866", rough=0.6), brick, along_x=False, over=0.02)
    # A sign along the roof's edge, and a hoist door up the front.
    m.box(x0 + 0.04, x1 - 0.04, y1 + 0.004, y1 + 0.012, top - 0.6, top - 0.15, material("mill sign", "#e8e2d0", rough=0.6))
    m.box(-0.05, 0.05, y1, y1 + 0.006, 1.4, top - 0.8, material("hoist doors", "#4a3020", rough=0.8))


def mill_silos(m):
    ground(m, "#8a847a", "mill yard paving")
    concrete = material("silo concrete", "#c4beb2", rough=0.9)
    for x in (-0.24, 0.0, 0.24):
        for y in (-0.14, 0.14):
            m.upright(x, y, 0, 6.5, 0.12, 0.12, concrete, segments=24)
    # The headhouse across their tops, with the leg going up.
    m.box(-0.38, 0.38, -0.08, 0.08, 6.5, 7.6, concrete, 0.004)
    m.box(-0.03, 0.03, 0.26, 0.32, 0, 7.4, material("elevator leg", "#8a8680", metal=0.3, rough=0.6))


def mill_yard(m):
    ground(m, "#8a847a", "mill yard paving")
    # A loading dock with sacks, and a delivery wagon.
    dock = material("loading dock", "#9a9488", rough=0.9)
    m.box(-0.45, 0.1, -0.45, -0.2, 0, 0.45, dock, 0.004)
    sack = material("flour sacks", "#e8e0cc", rough=1.0)
    for k in range(6):
        m.box(-0.4 + k * 0.08, -0.34 + k * 0.08, -0.4, -0.3, 0.45, 0.62, sack, 0.01)
    wagon = material("delivery wagon", "#3e5a6a", rough=0.7)
    m.box(0.1, 0.38, 0.1, 0.3, 0.3, 1.0, wagon, 0.006)
    for x in (0.13, 0.35):
        for y in (0.11, 0.29):
            m.tube_x(x - 0.012, x + 0.012, y, 0.13, 0.055, material("wagon wheel", "#3a3028", rough=0.8), segments=12)


# ---- coal, iron and steel ------------------------------------------------------------------------

def headframe(m):
    ground(m, "#3e3a36", "mine yard")
    steel = material("headframe steel", "#5a4a42", metal=0.4, rough=0.6)
    # Four legs leaning in to the sheave wheels at the top, braced, over the shaft house.
    top = 7.0
    for x in (-0.18, 0.18):
        for y in (-0.18, 0.18):
            m.rod((x * 1.6, y * 1.6, 0), (x * 0.5, y * 0.5, top), 0.012, steel)
    for z in (1.8, 3.6, 5.4):
        f = 1.6 - (z / top) * 1.1
        for a, b in (((-0.18, -0.18), (0.18, -0.18)), ((0.18, -0.18), (0.18, 0.18)), ((0.18, 0.18), (-0.18, 0.18)), ((-0.18, 0.18), (-0.18, -0.18))):
            m.rod((a[0] * f, a[1] * f, z), (b[0] * f, b[1] * f, z), 0.007, steel)
    m.box(-0.11, 0.11, -0.11, 0.11, top, top + 0.15, steel)
    for x in (-0.05, 0.05):
        m.tube_x(x - 0.006, x + 0.006, 0, top + 0.5, 0.11, steel, segments=24, ring_inner=0.09)
    # The backstay out to the hoist house.
    m.rod((0, 0, top), (0, -0.48, 1.2), 0.01, steel)
    m.box(-0.2, 0.2, -0.2, 0.2, 0, 1.2, material("shaft house", "#6a5a4e", rough=0.8), 0.004)


def tipple(m):
    ground(m, "#3e3a36", "mine yard")
    wood = material("tipple boards", "#4a4038", rough=0.9)
    # A tall house on legs where the coal is screened and dropped into cars.
    for x in (-0.3, 0.3):
        for y in (-0.25, 0.25):
            m.box(x - 0.025, x + 0.025, y - 0.025, y + 0.025, 0, 2.6, wood)
    m.box(-0.34, 0.34, -0.3, 0.3, 2.6, 4.6, wood, 0.004)
    gable(m, -0.34, 0.34, -0.3, 0.3, 4.6, 5.3, material("tipple roof", "#3a3a3a", metal=0.3, rough=0.6), wood, along_x=True)
    windows(m, -0.34, 0.34, -0.3, 0.3, 2.8, 1, 3, 2, glass(), wood)
    m.wedge([(-0.05, -0.3, 2.6), (0.05, -0.3, 2.6), (0.05, -0.5, 1.6), (-0.05, -0.5, 1.6)],
            [(-0.05, -0.3, 2.75), (0.05, -0.3, 2.75), (0.05, -0.5, 1.75), (-0.05, -0.5, 1.75)], wood)
    heap(m, 0.0, 0.0, 0.18, 0.7, "#1a1a1c", 51, "coal under tipple")


def coal_heap(m):
    ground(m, "#2e2c2a", "coal yard")
    heap(m, -0.12, -0.08, 0.34, 1.8, "#161618", 52, "coal")
    heap(m, 0.24, 0.26, 0.18, 0.9, "#1c1c1e", 53, "coal")


def mine_shed(m):
    ground(m, "#3e3a36", "mine yard")
    brick = material("hoist house brick", "#7e4232", rough=0.9)
    x0, x1, y0, y1 = -0.3, 0.3, -0.22, 0.22
    m.box(x0, x1, y0, y1, 0, 2.0, brick, 0.004)
    gable(m, x0, x1, y0, y1, 2.0, 2.8, material("hoist house roof", "#4a4e52", rough=0.6), brick, along_x=True)
    windows(m, x0, x1, y0, y1, 0.2, 1, 4, 2, glass(), material("hoist trim", "#c8bca8", rough=0.8))
    stack(m, 0.36, -0.3, 0, 5.0, 0.04, brick, material("stack band", "#3a2a24", rough=0.9))


def open_pit(m, look=0):
    """An open pit mine: terraced red earth and rock stepping down to its floor, a haul road round it."""
    rock = material("pit rock", "#7a5a48", rough=1.0)
    rim = material("pit rim", "#8a6a54", rough=1.0)
    floor = material("pit floor", "#5a3e32", rough=1.0)
    m.box(-0.5, 0.5, -0.5, 0.5, 0, 0.02, floor)
    # Benches round the edge, highest at the rim.
    for k, (inset, h) in enumerate(((0.0, 0.9), (0.1, 0.6), (0.2, 0.3))):
        for xa, xb, ya, yb in ((-0.5 + inset, 0.5 - inset, -0.5 + inset, -0.4 + inset), (-0.5 + inset, 0.5 - inset, 0.4 - inset, 0.5 - inset),
                               (-0.5 + inset, -0.4 + inset, -0.4 + inset, 0.4 - inset), (0.4 - inset, 0.5 - inset, -0.4 + inset, 0.4 - inset)):
            m.box(xa, xb, ya, yb, 0, h, rock if k else rim)
    # A steam shovel at work in the bottom.
    body = material("shovel", "#3a3a3a", metal=0.4, rough=0.6)
    m.box(-0.08, 0.08, -0.06, 0.08, 0.05, 0.6, body, 0.004)
    m.rod((0.0, 0.08, 0.5), (0.0, 0.22, 1.4), 0.01, body)
    m.box(-0.03, 0.03, 0.19, 0.25, 0.9, 1.15, body)


def ore_crusher(m):
    ground(m, "#5a4a42", "ore yard")
    steel = material("crusher siding", "#7a7a76", metal=0.4, rough=0.6)
    x0, x1, y0, y1 = -0.26, 0.26, -0.3, 0.2
    m.box(x0, x1, y0, y1, 0, 4.2, steel, 0.004)
    for k in range(1, 9):
        x = x0 + (x1 - x0) * k / 9
        m.box(x - 0.003, x + 0.003, y1, y1 + 0.004, 0, 4.2, material("crusher ribs", "#5a5a56", metal=0.4, rough=0.6))
    gable(m, x0, x1, y0, y1, 4.2, 4.8, material("crusher roof", "#4a4a48", metal=0.4, rough=0.6), steel, along_x=True)
    windows(m, x0, x1, y0, y1, 2.2, 1, 3, 3, glass(), steel)
    # The conveyor out to the heap.
    m.wedge([(-0.04, 0.2, 3.6), (0.04, 0.2, 3.6), (0.04, 0.5, 2.2), (-0.04, 0.5, 2.2)],
            [(-0.04, 0.2, 3.72), (0.04, 0.2, 3.72), (0.04, 0.5, 2.32), (-0.04, 0.5, 2.32)], material("conveyor", "#4a4038", rough=0.8))


def ore_heap(m):
    ground(m, "#5a4a42", "ore yard")
    heap(m, 0.0, -0.05, 0.36, 2.0, "#7a3a2a", 61, "iron ore")
    heap(m, 0.3, 0.3, 0.14, 0.7, "#6a3426", 62, "iron ore")


def blast_furnace(m):
    ground(m, "#4a4644", "mill yard")
    shell = material("furnace shell", "#5a4e48", metal=0.4, rough=0.6)
    stove = material("hot stove", "#6a5e56", metal=0.3, rough=0.7)
    # The furnace, its downcomer and three hot blast stoves.
    m.lathe(0.0, 0.1, 0, [(0.16, 0), (0.18, 1.5), (0.15, 4.5), (0.11, 6.5), (0.11, 7.0)], shell, segments=28)
    m.upright(0.0, 0.1, 7.0, 8.6, 0.05, 0.05, shell)
    m.rod((0.0, 0.1, 8.4), (-0.3, -0.25, 6.0), 0.03, shell)
    for x in (-0.3, -0.05, 0.2):
        m.upright(x, -0.3, 0, 5.5, 0.09, 0.09, stove, segments=24)
        m.lathe(x, -0.3, 5.5, [(0.09, 0), (0.07, 0.25), (0.0, 0.4)], stove, segments=24)
    m.box(-0.45, 0.45, 0.3, 0.48, 0, 1.4, material("cast house", "#6a5446", rough=0.8), 0.004)


def stacks(m):
    ground(m, "#4a4644", "mill yard")
    brick = material("stack brick", "#7a4434", rough=0.9)
    band = material("stack band", "#2e2624", rough=0.9)
    for x, y, h in ((-0.2, -0.15, 9.0), (0.05, 0.05, 10.0), (0.28, 0.25, 8.5)):
        stack(m, x, y, 0, h, 0.06, brick, band)
    m.box(-0.42, 0.42, -0.42, 0.42, 0, 0.8, material("flue base", "#5a4a42", rough=0.85), 0.004)


def steel_shed(m):
    ground(m, "#4a4644", "mill yard")
    siding = material("shed siding", "#6e6a64", metal=0.4, rough=0.6)
    roof = material("shed roof", "#4a4846", metal=0.4, rough=0.6)
    x0, x1, y0, y1 = -0.44, 0.44, -0.5, 0.5
    m.box(x0, x1, y0, y1, 0, 3.4, siding, 0.004)
    for k in range(1, 12):
        x = x0 + (x1 - x0) * k / 12
        for y in (y0, y1):
            m.box(x - 0.003, x + 0.003, y - 0.004, y + 0.004, 0, 3.4, material("shed ribs", "#56524c", metal=0.4, rough=0.6))
    gable(m, x0, x1, y0, y1, 3.4, 4.2, roof, siding, along_x=True, over=0.0)
    # The monitor along the ridge, glazed to let the heat out.
    m.box(-0.44, 0.44, -0.08, 0.08, 4.0, 4.6, siding)
    m.box(-0.44, 0.44, -0.085, 0.085, 4.15, 4.45, glass())
    slab(m, [(-0.46, -0.12, 4.6), (0.46, -0.12, 4.6), (0.46, 0.12, 4.6), (-0.46, 0.12, 4.6)], 0.05, roof)


def slag_heap(m):
    ground(m, "#3e3c3a", "slag yard")
    heap(m, -0.05, 0.0, 0.42, 2.4, "#4a4644", 71, "slag")
    heap(m, 0.3, -0.32, 0.12, 0.6, "#5a5450", 72, "slag")


def steel_yard(m):
    ground(m, "#5a5652", "steel yard")
    steel = material("rolled steel", "#6a6c70", metal=0.8, rough=0.35)
    # Stacked beams and coils, and a gantry crane over them.
    for k in range(4):
        y = -0.36 + k * 0.1
        for j in range(3):
            m.box(-0.4, 0.0, y - 0.03, y + 0.03, j * 0.12, j * 0.12 + 0.1, steel)
    for x, y in ((0.16, -0.3), (0.32, -0.3), (0.16, -0.12), (0.32, -0.12)):
        m.tube_x(x - 0.05, x + 0.05, y, 0.32, 0.07, steel, segments=20, ring_inner=0.03)
    crane = material("gantry", "#c8a030", metal=0.4, rough=0.5)
    for x in (-0.45, 0.45):
        for y in (0.12, 0.42):
            m.box(x - 0.015, x + 0.015, y - 0.015, y + 0.015, 0, 2.6, crane)
        m.box(x - 0.015, x + 0.015, 0.1, 0.44, 2.6, 2.7, crane)
    m.box(-0.47, 0.47, 0.24, 0.3, 2.7, 2.85, crane)


# ---- the factory --------------------------------------------------------------------------------

def factory_shed(m):
    paved(m, -0.5, 0.5, -0.5, 0.5, "#8a867e")
    brick = material("factory brick", "#8e4a38", rough=0.9)
    x0, x1, y0, y1 = -0.46, 0.46, -0.46, 0.46
    m.box(x0, x1, y0, y1, 0, 2.6, brick, 0.004)
    windows(m, x0, x1, y0, y1, 0.3, 1, 6, 6, glass(), material("factory trim", "#c8bca8", rough=0.8), w=0.08, h=1.4)
    # A sawtooth roof: north lights of glass facing one way, slopes the other.
    roof = material("sawtooth roof", "#4e4a46", rough=0.7)
    for k in range(4):
        ya = y0 + k * (y1 - y0) / 4
        yb = ya + (y1 - y0) / 4
        m.wedge([(x0, ya, 2.6), (x1, ya, 2.6), (x1, yb, 2.6), (x0, yb, 2.6)], [(x0, ya, 3.4), (x1, ya, 3.4), (x1, ya + 0.01, 3.4), (x0, ya + 0.01, 3.4)], roof)
        m.box(x0, x1, ya - 0.003, ya + 0.003, 2.6, 3.35, glass())


def factory_chimney(m):
    paved(m, -0.5, 0.5, -0.5, 0.5, "#8a867e")
    brick = material("factory brick", "#8e4a38", rough=0.9)
    # The boiler house and its tall chimney.
    m.box(-0.36, 0.2, -0.3, 0.3, 0, 2.2, brick, 0.004)
    gable(m, -0.36, 0.2, -0.3, 0.3, 2.2, 2.9, material("boiler roof", "#4e5866", rough=0.6), brick, along_x=True)
    windows(m, -0.36, 0.2, -0.3, 0.3, 0.3, 1, 3, 3, glass(), material("factory trim", "#c8bca8", rough=0.8))
    stack(m, 0.32, 0.0, 0, 9.5, 0.07, brick, material("stack band", "#2e2624", rough=0.9))


def loading_dock(m):
    paved(m, -0.5, 0.5, -0.5, 0.5, "#8a867e")
    brick = material("factory brick", "#8e4a38", rough=0.9)
    x0, x1, y0, y1 = -0.46, 0.46, -0.46, 0.0
    m.box(x0, x1, y0, y1, 0, 2.2, brick, 0.004)
    houses.flat_top(m, x0, x1, y0, y1, 2.2, material("factory trim", "#c8bca8", rough=0.8))
    dock = material("dock concrete", "#9a9488", rough=0.9)
    m.box(x0, x1, y1, y1 + 0.12, 0, 0.45, dock)
    for k in range(4):
        x = x0 + 0.1 + k * 0.24
        m.box(x - 0.07, x + 0.07, y1 - 0.002, y1 + 0.004, 0.45, 1.6, material("dock doors", "#5a6a72", metal=0.3, rough=0.6))
    crate = material("crates", "#a8885a", rough=0.85)
    for x, y in ((-0.3, 0.3), (-0.18, 0.32), (0.1, 0.28), (0.3, 0.36)):
        m.box(x - 0.05, x + 0.05, y - 0.05, y + 0.05, 0, 0.42, crate, 0.004)


def factory_yard(m):
    paved(m, -0.5, 0.5, -0.5, 0.5, "#8a867e")
    crate = material("crates", "#a8885a", rough=0.85)
    for k in range(5):
        x = -0.35 + k * 0.17
        for j in range(2):
            m.box(x - 0.06, x + 0.06, -0.3 + j * 0.15 - 0.06, -0.3 + j * 0.15 + 0.06, 0, 0.45 + 0.1 * (k % 2), crate, 0.004)
    houses.board(m, (-0.48, 0.45), (0.48, 0.45), material("yard fence", "#7a6a54", rough=0.9))
    houses.yard_tree(m, 0.3, 0.2, 0.8, seed=81, kind="elm")


# ---- oil ------------------------------------------------------------------------------------------

def pumpjack(m):
    ground(m, "#6a5e4a", "lease dirt")
    m.box(-0.2, 0.2, -0.12, 0.12, 0, 0.12, material("pump pad", "#9a948a", rough=0.9))
    frame = material("pumpjack", "#3a3e44", metal=0.5, rough=0.5)
    head = material("horse head", "#b8302a", metal=0.4, rough=0.5)
    # The samson post, the walking beam, the horse head over the well and the crank and weights at the back.
    m.rod((0.0, -0.06, 0.12), (0.0, 0.0, 1.8), 0.012, frame)
    m.rod((0.0, 0.06, 0.12), (0.0, 0.0, 1.8), 0.012, frame)
    m.box(-0.015, 0.015, -0.24, 0.2, 1.75, 1.92, frame)
    m.box(-0.025, 0.025, 0.2, 0.26, 1.2, 2.1, head, 0.004)
    m.rod((0.0, 0.24, 1.2), (0.0, 0.24, 0.12), 0.004, frame)
    for x in (-0.05, 0.05):
        m.tube_x(x - 0.01, x + 0.01, -0.2, 0.75, 0.13, frame, segments=20)
    m.rod((0.0, -0.2, 0.75), (0.0, -0.24, 1.8), 0.008, frame)
    m.box(-0.06, 0.06, -0.12, -0.02, 0.12, 0.45, frame)


def oil_tank(m):
    ground(m, "#6a5e4a", "lease dirt")
    steel = material("tank white", "#d8d4cc", metal=0.3, rough=0.5)
    tank(m, -0.12, -0.08, 0.2, 1.6, steel, material("tank roof", "#9a9690", metal=0.4, rough=0.5))
    tank(m, 0.26, 0.26, 0.12, 1.1, steel)
    # A berm round them.
    berm = material("berm", "#7a6a50", rough=1.0)
    for xa, xb, ya, yb in ((-0.48, 0.48, -0.48, -0.42), (-0.48, 0.48, 0.42, 0.48), (-0.48, -0.42, -0.42, 0.42), (0.42, 0.48, -0.42, 0.42)):
        m.box(xa, xb, ya, yb, 0, 0.25, berm, 0.01)


def refinery_columns(m):
    ground(m, "#6a6660", "refinery concrete")
    steel = material("column steel", "#b8b6b0", metal=0.6, rough=0.4)
    pipe = material("refinery pipe", "#7a7a76", metal=0.6, rough=0.4)
    for x, y, r, h in ((-0.2, -0.15, 0.07, 7.5), (0.05, -0.2, 0.05, 6.0), (0.24, 0.0, 0.08, 8.2), (-0.12, 0.22, 0.05, 5.0)):
        m.upright(x, y, 0, h, r, r, steel, segments=20)
        for z in range(1, int(h), 2):
            m.upright(x, y, z, z + 0.08, r + 0.03, r + 0.03, pipe, segments=20)
        m.lathe(x, y, h, [(r, 0), (r * 0.6, 0.12), (0.0, 0.18)], steel, segments=20)
    for a, b in (((-0.2, -0.15), (0.24, 0.0)), ((0.05, -0.2), (-0.12, 0.22)), ((0.24, 0.0), (0.4, 0.4))):
        m.rod((a[0], a[1], 2.2), (b[0], b[1], 2.2), 0.015, pipe)
    m.box(-0.45, 0.45, 0.32, 0.4, 0, 2.4, pipe)


def refinery_tanks(m):
    ground(m, "#6a6660", "refinery concrete")
    white = material("tank white", "#d8d4cc", metal=0.3, rough=0.5)
    tank(m, -0.2, -0.2, 0.22, 1.8, white, material("tank roof", "#9a9690", metal=0.4, rough=0.5))
    tank(m, 0.22, 0.2, 0.2, 1.6, white, material("tank roof", "#9a9690", metal=0.4, rough=0.5))
    m.lathe(0.25, -0.25, 0, [(0.13, 0), (0.13, 0.4), (0.12, 0.8), (0.09, 1.1), (0.0, 1.25)], white, segments=24)


def flare(m):
    ground(m, "#6a6660", "refinery concrete")
    steel = material("flare stack", "#8a8884", metal=0.5, rough=0.5)
    m.upright(0, 0, 0, 10.0, 0.035, 0.03, steel, segments=12)
    for z in (2.5, 5.0, 7.5):
        for a in range(3):
            ang = a * 2 * math.pi / 3
            m.rod((0, 0, z), (0.3 * math.cos(ang), 0.3 * math.sin(ang), 0), 0.004, steel)
    flame = material("flame", "#ffb040", rough=1.0)
    flame.node_tree.nodes["Principled BSDF"].inputs["Emission Color"].default_value = (1.0, 0.55, 0.15, 1)
    flame.node_tree.nodes["Principled BSDF"].inputs["Emission Strength"].default_value = 6.0
    m.lathe(0, 0, 10.0, [(0.04, 0), (0.06, 0.3), (0.04, 0.7), (0.0, 1.1)], flame, segments=12)
    # A small control house at its foot.
    m.box(0.18, 0.42, 0.18, 0.42, 0, 1.2, material("control house", "#c8c2b4", rough=0.85), 0.004)


# ---- ranching and meat packing ------------------------------------------------------------------

def ranch_barn(m):
    ground(m, "#6a5a40", "ranch yard")
    wood = material("ranch barn", "#8a5a3a", rough=0.9)
    x0, x1, y0, y1 = -0.3, 0.3, -0.3, 0.16
    m.box(x0, x1, y0, y1, 0, 1.8, wood, 0.004)
    gable(m, x0, x1, y0, y1, 1.8, 2.9, material("ranch roof", "#5e5a56", metal=0.3, rough=0.6), wood, along_x=True, over=0.05)
    # A lean-to off one side, hay under it, and a windmill pumping the trough.
    slab(m, [(x1, y0, 1.6), (x1 + 0.16, y0, 1.1), (x1 + 0.16, y1, 1.1), (x1, y1, 1.6)], 0.05, material("lean-to roof", "#5e5a56", metal=0.3, rough=0.6))
    hay = material("hay", "#c8a858", rough=1.0)
    m.box(x1 + 0.02, x1 + 0.15, y0 + 0.04, y1 - 0.04, 0, 0.7, hay, 0.02)
    m.box(-0.08, 0.08, y1, y1 + 0.006, 0, 1.3, material("ranch doors", "#6a4028", rough=0.9))
    mill = material("windmill", "#9a9a96", metal=0.6, rough=0.4)
    for a in range(4):
        ang = a * math.pi / 2 + math.pi / 4
        m.rod((-0.36 + 0.06 * math.cos(ang), 0.36 + 0.06 * math.sin(ang), 0), (-0.36, 0.36, 4.0), 0.005, mill)
    m.tube_x(-0.37, -0.35, 0.36, 4.1, 0.12, mill, segments=16, ring_inner=0.03)
    m.box(-0.3, -0.14, 0.4, 0.46, 0, 0.18, material("trough", "#6a6a66", metal=0.4, rough=0.5))


def corral(m, look=0):
    ground(m, "#7a6648", "corral dirt")
    rail = material("corral rails", "#8a7254", rough=0.9)
    for a, b in (((-0.47, -0.47), (0.47, -0.47)), ((0.47, -0.47), (0.47, 0.47)), ((0.47, 0.47), (-0.47, 0.47)), ((-0.47, 0.47), (-0.47, -0.47))):
        houses.rails(m, a, b, rail)
    rng = random.Random(look * 13 + 7)
    for k in range(6):
        cow(m, rng.uniform(-0.35, 0.35), rng.uniform(-0.35, 0.35), rng.uniform(0, math.tau), rng.choice(["#4a2e22", "#2a2220", "#8a5a3a", "#e8e2d8"]))
    m.box(-0.1, 0.1, 0.32, 0.38, 0, 0.16, material("feed trough", "#6a5a46", rough=0.9))


def packing_plant(m):
    paved(m, -0.5, 0.5, -0.5, 0.5, "#8a867e")
    brick = material("plant brick", "#8a5240", rough=0.9)
    trim = material("plant trim", "#d0c6b2", rough=0.8)
    x0, x1, y0, y1 = -0.44, 0.44, -0.44, 0.38
    top = 4 * STOREY + 0.2
    m.box(x0, x1, y0, y1, 0, top, brick, 0.004)
    windows(m, x0, x1, y0, y1, 0.2, 4, 5, 5, glass(), trim)
    houses.flat_top(m, x0, x1, y0, y1, top, trim)
    m.box(-0.12, 0.12, -0.1, 0.1, top, top + 1.0, brick, 0.004)
    stack(m, 0.3, -0.3, top, top + 3.0, 0.045, brick, material("stack band", "#2e2624", rough=0.9))


def stockyard(m, look=0):
    ground(m, "#6e5a40", "stockyard dirt")
    rail = material("pen rails", "#7a6a54", rough=0.9)
    for x in (-0.47, -0.16, 0.16, 0.47):
        houses.rails(m, (x, -0.47), (x, 0.47), rail)
    for y in (-0.47, 0.0, 0.47):
        houses.rails(m, (-0.47, y), (0.47, y), rail)
    rng = random.Random(look * 17 + 3)
    for k in range(9):
        cow(m, rng.uniform(-0.42, 0.42), rng.uniform(-0.42, 0.42), rng.uniform(0, math.tau), rng.choice(["#4a2e22", "#2a2220", "#8a5a3a"]))


PARTS = {
    "bunkhouse": bunkhouse, "log_pile": log_pile, "camp_shed": camp_shed,
    "sawmill": sawmill, "log_deck": log_deck, "burner": burner, "lumber_stack": lumber_stack,
    "barn": barn, "silo": silo, "wheat_field": wheat_field,
    "mill_building": mill_building, "mill_silos": mill_silos, "mill_yard": mill_yard,
    "headframe": headframe, "tipple": tipple, "coal_heap": coal_heap, "mine_shed": mine_shed,
    "open_pit": open_pit, "ore_crusher": ore_crusher, "ore_heap": ore_heap,
    "blast_furnace": blast_furnace, "stacks": stacks, "steel_shed": steel_shed, "slag_heap": slag_heap, "steel_yard": steel_yard,
    "factory_shed": factory_shed, "factory_chimney": factory_chimney, "loading_dock": loading_dock, "factory_yard": factory_yard,
    "pumpjack": pumpjack, "oil_tank": oil_tank,
    "refinery_columns": refinery_columns, "refinery_tanks": refinery_tanks, "flare": flare,
    "ranch_barn": ranch_barn, "corral": corral,
    "packing_plant": packing_plant, "stockyard": stockyard,
}


def part(m, key):
    PARTS[key](m)
