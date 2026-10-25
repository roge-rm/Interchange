"""Docks, shipyards and locks, and what stands at airports: hangars, terminals, a control tower, a gravel strip's hut and
a seaplane base's float dock. Each is built on one tile in its own terms, x across and y along, z in steps. Docks,
shipyards and seaplane bases have their water at y = -0.5 and are turned to their edge (WATER_HEADING); the rest with a
front are built facing view edge [edge] (XM, XP, YM or YP), as the garage is.
"""
import math

import harbour
import houses
from parts import material


def _b(m, edge):
    """A box maker that turns the model so its front, at y = -0.5, faces [edge]."""
    along_u = edge in ("XM", "XP")
    flip = edge in ("XP", "YP")

    def b(x0, x1, y0, y1, z0, z1, mat, bevel=0.0):
        if flip:
            y0, y1 = -y1, -y0
        if along_u:
            m.box(x0, x1, y0, y1, z0, z1, mat, bevel)
        else:
            m.box(y0, y1, x0, x1, z0, z1, mat, bevel)
    return b


# Dock, shipyard and seaplane base are built with the water at y = -0.5 and turned to their edge by render_scenery.
WATER_HEADING = {"XM": 0, "YM": 2, "XP": 4, "YP": 6}


def dock(m):
    """A Seaway wharf: a concrete quay wall with a coping and timber fenders, bollards, cleats and ladders, crane rails
    and a railway track along it, a transit shed of ribbed steel with roll-up doors and a roof monitor, lamps, and a
    portal crane with a lattice boom out over the water."""
    concrete = material("quay", "#9a968e", rough=0.85)
    coping = material("quay coping", "#b4b0a6", rough=0.8)
    paving = material("quay paving", "#7e7c78", rough=0.9)
    fender = material("quay fenders", "#4a3a2c", rough=0.95)
    rail = material("quay rail", "#6a6a6c", metal=0.7, rough=0.4)
    q = 0.25
    m.box(-0.49, 0.49, -0.5, 0.45, 0.0, q - 0.02, concrete, 0.004)
    m.box(-0.49, 0.49, -0.455, 0.45, q - 0.02, q, paving)
    m.box(-0.49, 0.49, -0.51, -0.455, q - 0.05, q + 0.012, coping, 0.004)
    # Timber fenders down the face, a wale across them.
    for k in range(9):
        x = -0.44 + k * 0.11
        m.box(x - 0.012, x + 0.012, -0.532, -0.51, 0.0, q - 0.01, fender, 0.002)
    m.box(-0.49, 0.49, -0.542, -0.532, 0.12, 0.17, fender, 0.002)
    for x in (-0.36, -0.02, 0.32):
        harbour.bollard(m, x, -0.478, q)
    for x in (-0.19, 0.15, 0.44):
        harbour.cleat(m, x, -0.48, q)
    harbour.ladder(m, 0.06, -0.52, 0.0, q + 0.01, grab=0.14)
    harbour.ladder(m, -0.42, -0.52, 0.0, q + 0.01, grab=0.14)
    # Crane rails near the edge, and a railway track set into the paving.
    groove = material("rail groove", "#3e3c3a", rough=0.9)
    for y in (-0.425, -0.175, -0.112, -0.055):
        m.box(-0.49, 0.49, y - 0.008, y + 0.008, q - 0.002, q + 0.002, groove)
        m.box(-0.49, 0.49, y - 0.003, y + 0.003, q, q + 0.012, rail)

    # The transit shed: ribbed steel walls on a concrete plinth, a low gable roof with a monitor along its ridge.
    wall = material("transit shed", "#8a4a32", metal=0.2, rough=0.6)
    rib = material("shed ribs", "#743c28", metal=0.2, rough=0.6)
    roof = material("shed roof", "#5a6064", metal=0.4, rough=0.5)
    trim = material("shed trim", "#d8d4c8", rough=0.6)
    plinth = material("shed plinth", "#a8a49a", rough=0.85)
    x0, x1, y0, y1, eave = -0.42, 0.42, 0.0, 0.43, 1.32
    m.box(x0, x1, y0, y1, q, eave, wall)
    m.box(x0 - 0.006, x1 + 0.006, y0 - 0.006, y1 + 0.006, q, q + 0.07, plinth)
    harbour.ribs(m, x0, x1, y0, q + 0.07, eave, rib, face=-1)
    harbour.ribs(m, x0, x1, y1, q + 0.07, eave, rib, face=1)
    harbour.ribs_y(m, x1, y0, y1, q + 0.07, eave, rib, face=1)
    harbour.ribs_y(m, x0, y0, y1, q + 0.07, eave, rib, face=-1)
    houses.gable(m, x0, x1, y0, y1, eave, 1.52, roof, wall, along_x=True, over=0.035, thick=0.05)
    m.box(x0 - 0.035, x1 + 0.035, y0 - 0.04, y0 - 0.03, eave - 0.06, eave + 0.01, trim)
    m.box(x0 - 0.035, x1 + 0.035, y1 + 0.03, y1 + 0.04, eave - 0.06, eave + 0.01, trim)
    # The monitor: a raised lantern along the ridge with louvres down its sides.
    louvre = material("monitor louvres", "#3a3e42", metal=0.3, rough=0.6)
    yc = (y0 + y1) / 2
    m.box(-0.3, 0.3, yc - 0.04, yc + 0.04, 1.45, 1.58, wall)
    for k in range(12):
        x = -0.28 + k * 0.051
        for y in (yc - 0.042, yc + 0.042):
            m.box(x - 0.012, x + 0.012, y - 0.002, y + 0.002, 1.48, 1.56, louvre)
    houses.gable(m, -0.3, 0.3, yc - 0.04, yc + 0.04, 1.58, 1.66, roof, wall, along_x=True, over=0.015, thick=0.03)
    for x in (-0.36, 0.36):
        m.lathe(x, y1 - 0.08, 1.38, [(0.012, 0), (0.012, 0.1), (0.02, 0.12), (0.018, 0.16), (0.0, 0.17)], roof, segments=12)
    # Roll-up doors to the quay and the back, a door and windows between, a strip of windows under the eaves.
    for x in (-0.27, 0.0, 0.27):
        harbour.rollup_door(m, x - 0.085, x + 0.085, y0, q, 1.02, face=-1)
        harbour.rollup_door(m, x - 0.085, x + 0.085, y1, q, 1.02, face=1)
    glass = houses.glass()
    for x in (-0.135, 0.135):
        m.box(x - 0.03, x + 0.03, y0 - 0.008, y0 - 0.002, q + 0.04, 0.82, material("shed door", "#4a5a62", metal=0.3, rough=0.6))
        m.box(x - 0.034, x + 0.034, y0 - 0.01, y0 - 0.004, 0.82, 0.85, trim)
    for k in range(14):
        x = x0 + 0.03 + k * (x1 - x0 - 0.06) / 13
        for y, d in ((y0, -1), (y1, 1)):
            m.box(x - 0.02, x + 0.02, y + d * 0.008 - 0.002, y + d * 0.008 + 0.002, 1.12, 1.24, glass)
    for y in (0.1, 0.22, 0.34):
        for x, d in ((x0, -1), (x1, 1)):
            m.box(x + d * 0.008 - 0.002, x + d * 0.008 + 0.002, y - 0.04, y + 0.04, 0.7, 0.95, glass)
            m.box(x + d * 0.009 - 0.002, x + d * 0.009 + 0.002, y - 0.045, y + 0.045, 0.66, 0.7, trim)
    harbour.lamp(m, -0.47, -0.02, q, 1.15)
    harbour.lamp(m, 0.47, -0.02, q, 1.15)

    # The portal crane on its rails: bogies, tapered legs, a turntable and machinery house with its cab, an A-frame
    # and a lattice boom luffed up over the water.
    yellow = material("crane", "#d8a020", metal=0.3, rough=0.5)
    dark = material("crane dark", "#3a3a3c", metal=0.4, rough=0.5)
    cx = -0.22
    for y in (-0.425, -0.175):
        for s in (-1, 1):
            m.box(cx + s * 0.07 - 0.025, cx + s * 0.07 + 0.025, y - 0.012, y + 0.012, q + 0.01, q + 0.06, dark, 0.003)
            m.rod((cx + s * 0.07, y, q + 0.06), (cx + s * 0.045, -0.3 + (y + 0.3) * 0.6, 1.1), 0.01, yellow)
    for s in (-1, 1):
        m.rod((cx + s * 0.07, -0.425, 0.6), (cx + s * 0.07, -0.175, 0.6), 0.006, yellow)
        m.rod((cx + s * 0.07, -0.425, q + 0.08), (cx + s * 0.055, -0.175, 0.95), 0.004, yellow)
    m.box(cx - 0.06, cx + 0.06, -0.39, -0.21, 1.1, 1.17, yellow, 0.004)
    m.upright(cx, -0.3, 1.17, 1.21, 0.055, 0.05, dark, segments=20)
    m.shell([(-0.36, 1.21, 0.004, "paint"), (-0.2, 1.21, 0.004, "paint"), (-0.2, 1.5, 0.02, "paint"), (-0.36, 1.5, 0.02, "paint")],
            0.045, {"paint": yellow}, crown=0.04, x=cx)
    m.box(cx - 0.05, cx + 0.05, -0.215, -0.19, 1.22, 1.38, dark, 0.003)
    m.shell([(-0.4, 1.25, 0.004, "paint"), (-0.36, 1.25, 0.004, "paint"), (-0.36, 1.48, 0.01, "paint"), (-0.385, 1.48, 0.01, "glass"),
             (-0.405, 1.3, 0.004, "paint")], 0.022, {"paint": material("crane cab", "#e8e4dc", rough=0.5), "glass": glass}, x=cx + 0.03)
    apex = (cx, -0.24, 1.98)
    for s in (-1, 1):
        m.rod((cx + s * 0.035, -0.21, 1.5), apex, 0.006, yellow)
        m.rod((cx + s * 0.035, -0.33, 1.5), apex, 0.005, yellow)
    tip = (cx, -0.7, 2.28)
    harbour.lattice(m, (cx, -0.34, 1.4), tip, 0.045, 0.018, yellow, n=9, r=0.0045)
    m.rod(apex, tip, 0.0025, dark)
    m.rod((cx, -0.25, 1.95), (cx, -0.69, 2.24), 0.002, dark)
    m.rod((cx, -0.7, 2.24), (cx, -0.7, 1.0), 0.0018, dark)
    m.box(cx - 0.01, cx + 0.01, -0.71, -0.69, 0.92, 1.0, yellow, 0.002)


def shipyard(m):
    """A shipyard: a tall building hall of ribbed steel open to the water, a slipway with ground ways, keel blocks and
    a ship's frames going up on them, and a gantry crane across the front."""
    wall = material("shipyard wall", "#7a7e82", metal=0.3, rough=0.6)
    rib = material("shipyard ribs", "#6a6e72", metal=0.3, rough=0.6)
    roof = material("shipyard roof", "#3e4448", metal=0.4, rough=0.5)
    slip = material("slipway", "#8a8680", rough=0.85)
    ways = material("ground ways", "#5a4a3a", rough=0.85)
    steel = material("ship frames", "#7a5a48", metal=0.5, rough=0.6)
    crane = material("gantry", "#c8302a", metal=0.3, rough=0.5)
    # The slipway runs down from 0.2 at the head to the water.
    m.wedge([(-0.3, -0.5, 0.0), (0.3, -0.5, 0.0), (0.3, 0.42, 0.0), (-0.3, 0.42, 0.0)],
            [(-0.3, -0.5, 0.04), (0.3, -0.5, 0.04), (0.3, 0.42, 0.2), (-0.3, 0.42, 0.2)], slip)

    def deck(y):
        return 0.04 + 0.16 * (y + 0.5) / 0.92

    for x in (-0.11, 0.11):
        m.wedge([(x - 0.018, -0.5, 0.03), (x + 0.018, -0.5, 0.03), (x + 0.018, 0.42, deck(0.42) - 0.01), (x - 0.018, 0.42, deck(0.42) - 0.01)],
                [(x - 0.018, -0.5, 0.06), (x + 0.018, -0.5, 0.06), (x + 0.018, 0.42, deck(0.42) + 0.025), (x - 0.018, 0.42, deck(0.42) + 0.025)], ways)
    # Keel blocks, in stacks of oak and steel, and the keel along them.
    oak = material("keel blocks", "#8a6a44", rough=0.9)
    block_steel = material("keel block steel", "#4a4c4e", metal=0.4, rough=0.6)
    ys = [-0.2 + k * 0.075 for k in range(8)]
    top = 0.0
    for y in ys:
        z = deck(y)
        for k, mat in enumerate((block_steel, oak, oak, block_steel)):
            m.box(-0.03, 0.03, y - 0.018, y + 0.018, z + k * 0.05, z + (k + 1) * 0.05, mat, 0.002)
        top = z + 0.2
    keel_z = deck(ys[0]) + 0.2
    m.wedge([(-0.012, ys[0] - 0.05, keel_z), (0.012, ys[0] - 0.05, keel_z), (0.012, ys[-1] + 0.05, top), (-0.012, ys[-1] + 0.05, top)],
            [(-0.012, ys[0] - 0.05, keel_z + 0.04), (0.012, ys[0] - 0.05, keel_z + 0.04), (0.012, ys[-1] + 0.05, top + 0.04), (-0.012, ys[-1] + 0.05, top + 0.04)], steel)
    # The ship's frames, U shaped, fuller amidships, with a cradle of shores under the bilges.
    for k, y in enumerate(ys[1:-1]):
        z = deck(y) + 0.24
        f = 1.0 - abs(k - 2.5) / 6
        half = 0.13 * f + 0.05
        pts = [(0.0, z), (half * 0.6, z + 0.02), (half * 0.95, z + 0.18), (half, z + 0.45 * f + 0.15)]
        for s in (-1, 1):
            for a, b in zip(pts, pts[1:]):
                m.rod((s * a[0], y, a[1]), (s * b[0], y, b[1]), 0.005, steel, segments=6)
            m.rod((s * (half * 0.75), y, deck(y)), (s * half * 0.8, y, z + 0.08), 0.005, oak, segments=6)
    for s in (-1, 1):
        m.rod((s * 0.14, ys[1], deck(ys[1]) + 0.24 + 0.35), (s * 0.14, ys[-2], deck(ys[-2]) + 0.24 + 0.35), 0.004, steel, segments=6)

    # The hall: ribbed side walls, a clerestory of glass, a low gable roof, the back walled in.
    glass = houses.glass()
    for s in (-1, 1):
        x = s * 0.42
        m.box(x - 0.035, x + 0.035, -0.2, 0.45, 0.0, 2.6, wall)
        harbour.ribs_y(m, x + s * 0.035, -0.2, 0.45, 0.0, 2.05, rib, gap=0.03, face=s)
        m.box(x + s * 0.035 - 0.003, x + s * 0.035 + 0.006 * s, -0.19, 0.44, 2.1, 2.42, glass)
        for k in range(8):
            y = -0.19 + k * 0.63 / 7
            m.box(x + s * 0.04 - 0.004, x + s * 0.04 + 0.004, y - 0.006, y + 0.006, 2.05, 2.48, rib)
        m.box(x - 0.04, x + 0.04, -0.215, -0.19, 0.0, 2.62, rib)
    m.box(-0.42, 0.42, 0.42, 0.46, 0.0, 2.6, wall)
    harbour.ribs(m, -0.42, 0.42, 0.46, 0.0, 2.6, rib, gap=0.03, face=1)
    houses.gable(m, -0.46, 0.46, -0.22, 0.47, 2.6, 2.82, roof, wall, along_x=False, over=0.03, thick=0.06)
    # Skylights down each slope and vents along the ridge.
    sky = material("skylights", "#8aa0b0", rough=0.2, metal=0.2)
    for s in (-1, 1):
        for y in (-0.1, 0.1, 0.3):
            xa, xb = s * 0.12, s * 0.3
            za, zb = (2.6 + 0.22 * (0.49 - abs(v)) / 0.49 + 0.065 for v in (xa, xb))
            houses.slab(m, [(xa, y - 0.05, za), (xb, y - 0.05, zb), (xb, y + 0.05, zb), (xa, y + 0.05, za)], 0.012, sky)
    for y in (-0.12, 0.08, 0.28):
        m.lathe(0.0, y, 2.86, [(0.018, 0), (0.018, 0.08), (0.03, 0.1), (0.026, 0.15), (0.0, 0.16)], roof, segments=12)
    # Doors and windows on the back and the sides, and downpipes.
    trim = material("shipyard trim", "#d8d4c8", rough=0.6)
    harbour.rollup_door(m, -0.18, 0.18, 0.46, 0.0, 1.5, face=1, colour="#4a5a62")
    m.box(0.26, 0.33, 0.466, 0.472, 0.0, 0.85, material("shipyard door", "#c8302a", rough=0.6))
    m.box(0.25, 0.34, 0.468, 0.476, 0.85, 0.9, trim)
    for x in (-0.36, -0.28, 0.26, 0.34):
        m.box(x - 0.03, x + 0.03, 0.47, 0.476, 1.05 if x > 0 else 0.5, 1.4 if x > 0 else 0.85, glass)
    for s in (-1, 1):
        x = s * 0.462
        for y in (-0.02, 0.18, 0.38):
            m.box(x - 0.003, x + 0.003, y - 0.04, y + 0.04, 0.5, 0.85, glass)
            m.box(x - 0.004, x + 0.004, y - 0.045, y + 0.045, 0.46, 0.5, trim)
        m.box(x - 0.004, x + 0.004, 0.06, 0.12, 0.0, 0.85, material("shipyard door", "#c8302a", rough=0.6))
        for y in (-0.18, 0.44):
            m.upright(x + s * 0.008, y, 0.0, 2.6, 0.008, 0.008, trim, segments=8)
    # The gantry crane: lattice legs on rails either side, a box girder across, a trolley and cab.
    for s in (-1, 1):
        x = s * 0.47
        for y in (-0.49, -0.42):
            m.rod((x, y, 0.0), (x, -0.455, 3.2), 0.008, crane)
        for k in range(6):
            z0, z1 = k * 0.53, (k + 1) * 0.53
            ya = -0.49 + 0.035 * z0 / 3.2
            yb = -0.42 - 0.035 * z1 / 3.2
            m.rod((x, ya, z0), (x, yb, z1), 0.004, crane, segments=6)
        m.box(x - 0.02, x + 0.02, -0.5, -0.41, 0.0, 0.06, material("gantry bogie", "#3a3a3c", metal=0.4, rough=0.5))
    m.box(-0.5, 0.5, -0.48, -0.43, 3.2, 3.4, crane, 0.004)
    m.box(-0.5, 0.5, -0.47, -0.44, 3.4, 3.43, material("gantry walkway", "#d8d4c8", rough=0.6))
    m.box(-0.1, 0.02, -0.5, -0.41, 3.05, 3.2, material("gantry trolley", "#3a3a3c", metal=0.4, rough=0.5), 0.004)
    m.shell([(-0.5, 2.85, 0.004, "paint"), (-0.43, 2.85, 0.004, "paint"), (-0.43, 3.05, 0.008, "paint"), (-0.49, 3.05, 0.008, "glass"),
             (-0.51, 2.92, 0.004, "glass")], 0.03, {"paint": material("gantry cab", "#e8e4dc", rough=0.5), "glass": glass}, x=0.3)
    m.rod((-0.04, -0.455, 3.05), (-0.04, -0.455, 1.3), 0.002, material("hoist", "#2a2a2a", rough=0.6))


def lock(m, edge):
    """A lock rising to [edge]: concrete walls either side of the chamber, gates at both ends and halfway, the water low
    in the chamber's lower half and high in its upper half."""
    b = _b(m, edge)
    concrete = material("lock wall", "#a8a49c", rough=0.85)
    gate = material("lock gate", "#3a3026", rough=0.7)
    water = material("lock water", "#3a5a6e", rough=0.15)
    rail = material("lock rail", "#d8d8d4", metal=0.3, rough=0.4)
    # The chamber runs along y, upper end at y = -0.5 (edge), lower at +0.5.
    for side in (-1, 1):
        b(side * 0.42 - 0.07, side * 0.42 + 0.07, -0.5, 0.5, 0.0, 1.35, concrete, 0.004)
        b(side * 0.42 - 0.07, side * 0.42 - 0.064, -0.5, 0.5, 1.35, 1.6, rail)
    b(-0.35, 0.35, 0.0, 0.5, 0.0, 0.2, water)
    b(-0.35, 0.35, -0.5, 0.0, 0.0, 1.05, water)
    for y in (-0.48, 0.0, 0.48):
        b(-0.35, 0.35, y - 0.025, y + 0.025, 0.0, 1.3, gate, 0.003)
        b(-0.4, 0.4, y - 0.03, y + 0.03, 1.3, 1.36, rail)


def seaplane_dock(m):
    """A seaplane base: a float dock of planks on pontoons with a T at its end, tire fenders and cleats, a hinged
    gangway up to a crib on shore, a small gabled fuel shed, drums on a rack with a hand pump, and a windsock."""
    deck_z, float_z = 0.3, 0.2
    pontoon = material("pontoons", "#d8d8d0", rough=0.6)
    edge = material("dock edge", "#5a4634", rough=0.9)
    tire = material("tire fenders", "#1e1e20", rough=0.8)
    # The stem out from shore and the T across its end, each on pontoons with a timber edge round its planks.
    for x0, x1, y0, y1, along_x in ((-0.06, 0.06, -0.4, -0.1, True), (-0.32, 0.32, -0.5, -0.4, False)):
        m.box(x0 + 0.01, x1 - 0.01, y0 + 0.01, y1 - 0.01, 0.04, float_z, pontoon, 0.004)
        harbour.planks(m, x0, x1, y0, y1, float_z + 0.03, deck_z, along_x=along_x, width=0.024)
        m.box(x0 - 0.006, x0, y0, y1, float_z - 0.02, deck_z + 0.01, edge)
        m.box(x1, x1 + 0.006, y0, y1, float_z - 0.02, deck_z + 0.01, edge)
        if along_x:
            continue
        m.box(x0, x1, y0 - 0.006, y0, float_z - 0.02, deck_z + 0.01, edge)
        m.box(x0, x1, y1, y1 + 0.006, float_z - 0.02, deck_z + 0.01, edge)
    for x in (-0.26, -0.12, 0.12, 0.26):
        m.tube(-0.512, -0.5, x, float_z - 0.02, 0.022, tire, segments=14)
    for s in (-1, 1):
        m.tube_x(s * 0.32, s * 0.332, -0.45, float_z - 0.02, 0.022, tire, segments=14)
        m.tube_x(s * 0.06, s * 0.072, -0.25, float_z - 0.02, 0.022, tire, segments=14)
    for x in (-0.22, 0.22):
        harbour.cleat(m, x, -0.49, deck_z + 0.01)
    for s in (-1, 1):
        harbour.cleat(m, s * 0.05, -0.2, deck_z + 0.01, along_x=False)
    # The gangway up to the shore crib, hinged there, with hand rails.
    crib = material("shore crib", "#6a5440", rough=0.9)
    rail = material("gangway rail", "#c8c8c4", metal=0.4, rough=0.5)
    m.box(-0.07, 0.07, 0.04, 0.12, 0.0, 0.48, crib, 0.003)
    for k in range(4):
        m.box(-0.072, 0.072, 0.038, 0.122, 0.08 + k * 0.11, 0.11 + k * 0.11, edge)
    harbour.planks(m, -0.07, 0.07, 0.04, 0.12, 0.44, 0.5, along_x=True, width=0.02)
    gw = material("gangway", "#8a7458", rough=0.9)
    harbour_slab = [(-0.04, -0.12, deck_z + 0.01), (0.04, -0.12, deck_z + 0.01), (0.04, 0.05, 0.5), (-0.04, 0.05, 0.5)]
    m.wedge(harbour_slab, [(x, y, z + 0.04) for x, y, z in harbour_slab], gw)
    for s in (-1, 1):
        x = s * 0.04
        for y, z in ((-0.115, deck_z + 0.05), (-0.035, 0.42), (0.045, 0.54)):
            m.rod((x, y, z), (x, y, z + 0.32), 0.0025, rail, segments=6)
        m.rod((x, -0.115, deck_z + 0.37), (x, 0.045, 0.86), 0.003, rail, segments=6)
    m.tube_x(-0.045, 0.045, 0.048, 0.52, 0.008, material("gangway hinge", "#3a3a3c", metal=0.5, rough=0.5), segments=10)

    # The fuel shed: board and batten walls, a gable roof, a door and a window toward the water.
    red = material("fuel shed", "#b8322a", rough=0.7)
    batten = material("shed battens", "#9a2a22", rough=0.7)
    white = material("shed trim white", "#ecebe6", rough=0.6)
    roof = material("fuel shed roof", "#3a3a3c", rough=0.6)
    x0, x1, y0, y1, eave = -0.4, -0.12, 0.14, 0.42, 1.1
    m.box(x0, x1, y0, y1, 0.0, eave, red)
    harbour.ribs(m, x0, x1, y0, 0.0, eave, batten, gap=0.03, face=-1)
    harbour.ribs_y(m, x1, y0, y1, 0.0, eave, batten, gap=0.03, face=1)
    for x, y in ((x0, y0), (x1, y0), (x1, y1)):
        m.box(x - 0.008, x + 0.008, y - 0.008, y + 0.008, 0.0, eave, white)
    houses.gable(m, x0, x1, y0, y1, eave, 1.45, roof, red, along_x=True, over=0.025, thick=0.06)
    m.box(-0.36, -0.27, y0 - 0.008, y0 - 0.002, 0.0, 0.85, material("shed door", "#ecebe6", rough=0.6))
    m.box(-0.37, -0.26, y0 - 0.01, y0 - 0.004, 0.85, 0.9, white)
    m.box(-0.33, -0.3, y0 - 0.011, y0 - 0.006, 0.4, 0.48, material("door handle", "#2a2a2c", metal=0.5, rough=0.5))
    m.box(-0.22, -0.15, y0 - 0.008, y0 - 0.002, 0.45, 0.8, houses.glass())
    m.box(-0.225, -0.145, y0 - 0.01, y0 - 0.004, 0.42, 0.45, white)
    m.box(-0.188, -0.182, y0 - 0.011, y0 - 0.005, 0.45, 0.8, white)
    m.box(x1 + 0.002, x1 + 0.008, 0.24, 0.32, 0.45, 0.8, houses.glass())
    # Drums standing on a timber rack, a hand pump on one, its hose hung on a post.
    rack = material("drum rack", "#6a5440", rough=0.9)
    for y in (0.27, 0.35):
        m.box(0.06, 0.3, y - 0.012, y + 0.012, 0.0, 0.09, rack)
    m.box(0.06, 0.3, 0.25, 0.37, 0.09, 0.12, rack)
    for i in range(4):
        for j in range(2):
            harbour.drum(m, 0.09 + i * 0.055, 0.28 + j * 0.06, 0.12, colour="#2a5a3a" if (i + j) % 3 else "#b8322a", r=0.022, h=0.42)
    pump = material("hand pump", "#c8302a", metal=0.3, rough=0.5)
    m.upright(0.09, 0.28, 0.56, 0.86, 0.005, 0.005, pump, segments=8)
    m.box(0.08, 0.1, 0.27, 0.29, 0.86, 0.94, pump, 0.002)
    m.rod((0.09, 0.28, 0.92), (0.05, 0.25, 1.0), 0.0025, pump)
    m.rod((0.08, 0.28, 0.8), (0.03, 0.2, 0.3), 0.003, material("hose", "#1e1e20", rough=0.7))
    harbour.windsock(m, 0.38, 0.0, 0.0, 2.4, toward=(0.6, 0.8))


def hangar(m, edge, big):
    """A hangar with its doors to [edge]: a curved roof over a long shed, the doors most of its front."""
    b = _b(m, edge)
    wall = material("hangar wall", "#b8b4ac", rough=0.8)
    roof = material("hangar roof", "#5a6066", metal=0.5, rough=0.45)
    door = material("hangar door", "#7a8890", metal=0.4, rough=0.45)
    w = 0.46 if big else 0.4
    tall = 2.4 if big else 1.8
    b(-w, w, -0.42, 0.42, 0.0, tall, wall, 0.004)
    # The barrel roof, in steps across.
    n = 8
    for k in range(n):
        a0 = math.pi * k / n
        a1 = math.pi * (k + 1) / n
        x0, x1 = -w * math.cos(a0), -w * math.cos(a1)
        z = tall + math.sin((a0 + a1) / 2) * (0.9 if big else 0.7)
        b(min(x0, x1), max(x0, x1), -0.44, 0.44, tall, z, roof)
    b(-w * 0.85, w * 0.85, -0.43, -0.42, 0.0, tall * 0.92, door)
    for k in range(1, 6):
        x = -w * 0.85 + k * w * 1.7 / 6
        b(x - 0.004, x + 0.004, -0.435, -0.42, 0.0, tall * 0.92, wall)


def terminal(m, edge, size):
    """A terminal facing [edge]: a small one-storey building, a two-storey terminal with a glass front, or a big
    terminal with a jet bridge out to the apron."""
    b = _b(m, edge)
    wall = material("terminal wall", "#d8d4cc", rough=0.7)
    glass = material("terminal glass", "#3a5a6e", rough=0.1, metal=0.2)
    roof = material("terminal roof", "#5a5e62", rough=0.6)
    tall = {"small": 1.1, "mid": 2.0, "big": 2.6}[size]
    deep = {"small": 0.25, "mid": 0.35, "big": 0.42}[size]
    b(-0.48, 0.48, -deep, 0.42, 0.0, tall, wall, 0.004)
    b(-0.5, 0.5, -deep - 0.04, 0.44, tall, tall + 0.15, roof, 0.004)
    b(-0.46, 0.46, -deep - 0.004, -deep + 0.002, 0.25, tall - 0.15, glass)
    if size == "big":
        jet = material("jet bridge", "#a8acb0", metal=0.4, rough=0.5)
        b(-0.05, 0.05, -0.85, -deep, 0.9, 1.4, jet, 0.004)
        b(-0.06, 0.06, -0.8, -0.74, 0.0, 0.9, jet)


def tower(m):
    """A control tower: a concrete shaft and a glass cab on top."""
    concrete = material("tower", "#c8c4bc", rough=0.8)
    glass = material("tower glass", "#2e4a5e", rough=0.1, metal=0.2)
    roof = material("tower roof", "#3a3a3c", rough=0.6)
    m.box(-0.3, 0.3, -0.3, 0.3, 0.0, 1.0, concrete, 0.004)
    m.upright(0.0, 0.0, 1.0, 4.2, 0.09, 0.08, concrete, segments=16)
    m.upright(0.0, 0.0, 4.2, 4.8, 0.17, 0.2, glass, segments=16)
    m.upright(0.0, 0.0, 4.8, 4.95, 0.21, 0.12, roof, segments=16)
    m.rod((0.0, 0.0, 4.95), (0.0, 0.0, 5.6), 0.005, roof)


def strip_hut(m):
    """A gravel strip's hut: board walls, a shed roof, a door and windows, a stovepipe, drums beside it and a windsock."""
    wood = material("hut wood", "#7a5a3a", rough=0.85)
    batten = material("hut battens", "#664a30", rough=0.85)
    trim = material("hut trim", "#d8d0b8", rough=0.7)
    roof = material("hut roof", "#3a3a3c", rough=0.6)
    x0, x1, y0, y1 = 0.14, 0.42, 0.14, 0.4
    lo, hi = 1.05, 1.3
    # The walls, rising to the front so the roof sheds to the back.
    m.wedge([(x0, y0, 0.0), (x1, y0, 0.0), (x1, y1, 0.0), (x0, y1, 0.0)], [(x0, y0, lo), (x1, y0, lo), (x1, y1, hi), (x0, y1, hi)], wood)
    harbour.ribs(m, x0, x1, y1, 0.0, hi - 0.02, batten, gap=0.028, face=1)
    harbour.ribs_y(m, x1, y0, y1, 0.0, lo - 0.02, batten, gap=0.028, face=1)
    slab = [(x0 - 0.03, y0 - 0.03, lo - 0.02), (x1 + 0.03, y0 - 0.03, lo - 0.02), (x1 + 0.03, y1 + 0.04, hi + 0.01), (x0 - 0.03, y1 + 0.04, hi + 0.01)]
    m.wedge(slab, [(x, y, z + 0.07) for x, y, z in slab], roof)
    for x, y in ((x1, y1), (x0, y1), (x1, y0)):
        m.box(x - 0.007, x + 0.007, y - 0.007, y + 0.007, 0.0, lo if y == y0 else hi, trim)
    # Door and step on the front, a window beside it and one on the side.
    m.box(0.31, 0.39, y1 + 0.002, y1 + 0.008, 0.06, 0.88, material("hut door", "#3e6a8a", rough=0.6))
    m.box(0.3, 0.4, y1 + 0.004, y1 + 0.01, 0.88, 0.93, trim)
    m.box(0.3, 0.4, y1, y1 + 0.05, 0.0, 0.06, material("hut step", "#8a8680", rough=0.9))
    glass = houses.glass()
    m.box(0.17, 0.26, y1 + 0.002, y1 + 0.008, 0.48, 0.85, glass)
    m.box(0.165, 0.265, y1 + 0.004, y1 + 0.01, 0.44, 0.48, trim)
    m.box(x1 + 0.002, x1 + 0.008, 0.22, 0.31, 0.48, 0.85, glass)
    m.box(x1 + 0.004, x1 + 0.01, 0.215, 0.315, 0.44, 0.48, trim)
    # The stovepipe up through the roof at the back, with its cap.
    pipe = material("stovepipe", "#5a5a5c", metal=0.6, rough=0.4)
    m.upright(0.2, 0.2, 1.0, 1.75, 0.008, 0.008, pipe, segments=10)
    m.lathe(0.2, 0.2, 1.75, [(0.0, 0), (0.02, 0.05), (0.0, 0.08)], pipe, segments=10)
    for k in range(3):
        harbour.drum(m, 0.03 + k * 0.05, 0.44, 0.0, colour="#c8302a", r=0.02, h=0.42, name="strip drum")
    harbour.drum(m, 0.055, 0.39, 0.0, colour="#c8302a", r=0.02, h=0.42, name="strip drum")
    harbour.windsock(m, -0.35, 0.35, 0.0, 2.2, toward=(0.8, -0.6))
