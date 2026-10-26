"""Docks, shipyards and locks, and what stands at airports: hangars, terminals, a control tower, a gravel strip's hut and
a seaplane base's float dock. Each is built on one tile in its own terms, x across and y along, z in steps. Docks,
shipyards and seaplane bases have their water at y = -0.5 and are turned to their edge (WATER_HEADING); hangars,
terminals and locks face +y and are turned by houses.FACING, as the garage is.
"""
import math

import harbour
import houses
from parts import material


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
    sections = []
    for k, y in enumerate(ys[1:-1]):
        z = deck(y) + 0.24
        f = 1.0 - abs(k - 2.5) / 6
        half = 0.13 * f + 0.05
        pts = [(0.0, z), (half * 0.6, z + 0.02), (half * 0.95, z + 0.18), (half, z + 0.45 * f + 0.15)]
        sections.append((y, pts))
        for s in (-1, 1):
            for a, b in zip(pts, pts[1:]):
                m.rod((s * a[0], y, a[1]), (s * b[0], y, b[1]), 0.005, steel, segments=6)
            m.rod((s * (half * 0.75), y, deck(y)), (s * half * 0.8, y, z + 0.08), 0.005, oak, segments=6)
    for s in (-1, 1):
        m.rod((s * 0.14, ys[1], deck(ys[1]) + 0.24 + 0.35), (s * 0.14, ys[-2], deck(ys[-2]) + 0.24 + 0.35), 0.004, steel, segments=6)
    # Shell plating in red primer over the after frames, the bottom and bilge strakes on two frames further forward,
    # the rest of the frames still bare. Each plate runs between two frames, just outside them.
    primer = material("hull primer", "#9a4430", metal=0.3, rough=0.6)
    lap = material("hull plate seams", "#7a3426", metal=0.3, rough=0.6)

    def out(p, s, y, o):
        return (s * (p[0] + o), y, p[1] - o * 0.5)
    for k, ((ya, pa), (yb, pb)) in enumerate(zip(sections, sections[1:])):
        strakes = len(pa) - 1 if k < 3 else (2 if k == 3 else 0)
        for s in (-1, 1):
            for j in range(strakes):
                quad = [out(pa[j], s, ya, 0.004), out(pb[j], s, yb, 0.004), out(pb[j + 1], s, yb, 0.004), out(pa[j + 1], s, ya, 0.004)]
                m.wedge(quad, [out(pa[j], s, ya, 0.008), out(pb[j], s, yb, 0.008), out(pb[j + 1], s, yb, 0.008), out(pa[j + 1], s, ya, 0.008)],
                        primer)
            if strakes >= 2:
                m.rod(out(pa[1], s, ya, 0.009), out(pb[1], s, yb, 0.009), 0.002, lap, segments=4)
            if strakes > 2:
                m.rod(out(pa[2], s, ya, 0.009), out(pb[2], s, yb, 0.009), 0.002, lap, segments=4)

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


# ---- airports and locks ---------------------------------------------------------------------------
# Hangars and terminals are built with their fronts to +y and turned to their edge by place() (houses.FACING), as the
# garage is. Terminals that take two or three tiles repeat the same piece, so theirs run the whole tile across x and
# join their neighbours.

def _bx(m, xa, xb, ya, yb, za, zb, mat, bevel=0.0):
    """A box from corners given either way round."""
    m.box(min(xa, xb), max(xa, xb), min(ya, yb), max(ya, yb), za, zb, mat, bevel)


def _poly(n, r, rot, x=0.0, y=0.0):
    return [(x + r * math.cos(rot + 2 * math.pi * k / n), y + r * math.sin(rot + 2 * math.pi * k / n)) for k in range(n)]


def _frustum(m, x, y, z0, z1, r0, r1, n, mat, rot=None):
    """A flat faced n-sided frustum, r0 to its corners at z0 and r1 at z1, a flat side square to x and y."""
    rot = math.pi / n if rot is None else rot
    m.wedge([(px, py, z0) for px, py in _poly(n, r0, rot, x, y)], [(px, py, z1) for px, py in _poly(n, r1, rot, x, y)], mat)


def _plank(m, a, b, w, z0, z1, mat):
    """A board standing on edge from a to b (x, y), w tiles thick, z0 to z1 steps."""
    dx, dy = b[0] - a[0], b[1] - a[1]
    n = math.hypot(dx, dy) or 1.0
    px, py = -dy / n * w / 2, dx / n * w / 2
    pts = [(a[0] + px, a[1] + py), (b[0] + px, b[1] + py), (b[0] - px, b[1] - py), (a[0] - px, a[1] - py)]
    m.wedge([(x, y, z0) for x, y in pts], [(x, y, z1) for x, y in pts], mat)


def _wall(m, ya, yb, ring, mat):
    """A wall from ya to yb along y in the outline ring, a closed list of (x, z)."""
    m.loft([(ya, list(ring)), (yb, list(ring))], {"wall": mat})


def _bow(x, w=0.4, base=2.2, rise=1.1):
    """The bowstring hangar's roof line across x."""
    return base + rise * (1 - (x / w) ** 2)


def _leaf(m, xa, xb, y, h, paint, frame, glass, brace):
    """A hangar door leaf on its track at y: a framed panel with a strip of windows high up, braced across."""
    m.box(xa, xb, y - 0.005, y + 0.005, 0.02, h, paint)
    f = y + 0.0065
    for a, b in ((xa, xa + 0.008), (xb - 0.008, xb)):
        m.box(a, b, f - 0.0015, f + 0.0015, 0.02, h, frame)
    for z in (0.02, h * 0.5, h - 0.04):
        m.box(xa, xb, f - 0.0015, f + 0.0015, z, z + 0.04, frame)
    m.box(xa + 0.014, xb - 0.014, f - 0.001, f + 0.002, h * 0.74, h * 0.9, glass)
    for k in (1, 2):
        x = xa + (xb - xa) * k / 3
        m.box(x - 0.002, x + 0.002, f, f + 0.003, h * 0.74, h * 0.9, frame)
    m.rod((xa + 0.01, f, 0.08), (xb - 0.01, f, h * 0.48), 0.0025, brace, segments=5)
    m.rod((xa + 0.01, f, h * 0.48), (xb - 0.01, f, 0.08), 0.0025, brace, segments=5)
    m.box(xa + 0.01, xb - 0.01, y - 0.007, y + 0.007, 0.0, 0.03, material("door rollers", "#2a2a2c", metal=0.5, rough=0.5))


# Hangars by the year their airport went up (ChunkBaker.hangarEra): the small one a wooden flying club hangar to 1950,
# then a Butler steel arch; the big one a wartime RCAF hangar to 1970, then a clad steel hangar.
HANGAR_ERAS = 2


def hangar(m, big, era=1):
    """A hangar with its doors to +y in the look of [era]."""
    if not big:
        (_wood_hangar if era == 0 else _arch_hangar)(m)
        return
    if era == 1:
        _steel_hangar(m)
        return
    # A wartime double hangar: wood sided walls under a bowstring truss roof, sliding door leaves on three tracks
    # running out into pockets at the sides (the right hand ones run back to open the door), lean-to shops down both sides.
    siding = material("hangar siding", "#d6cdb2", rough=0.8)
    lines = material("hangar siding lines", "#b4aa8e", rough=0.85)
    trim = material("hangar trim", "#3e5a44", rough=0.6)
    roof = material("hangar roof", "#4a4e50", rough=0.7)
    seam = material("hangar roof seams", "#5e6264", rough=0.6)
    dark = material("hangar inside", "#1c1a18", rough=1.0)
    glass = houses.glass()
    w, y0, y1 = 0.4, -0.45, 0.4
    xs = [-w + 2 * w * k / 20 for k in range(21)]
    for s in (-1, 1):
        _bx(m, s * w - 0.012, s * w + 0.012, y0, y1, 0.0, 2.2, siding)
        for k in range(1, 20):
            _bx(m, s * (w + 0.012), s * (w + 0.015), y0, y1, k * 0.11, k * 0.11 + 0.012, lines)
    m.box(-w, w, y0 - 0.012, y0 + 0.012, 0.0, 2.2, siding)
    end = [(x, _bow(x)) for x in xs]
    _wall(m, y1 - 0.012, y1 + 0.004, end, siding)
    _wall(m, y0 - 0.004, y0 + 0.012, end, siding)
    for k in range(1, 20):
        m.box(-w, w, y0 - 0.015, y0 - 0.012, k * 0.11, k * 0.11 + 0.012, lines)
    # The roof, its seams running over the bow, and ventilators along the crown.
    rx = [-0.43 + 0.86 * k / 20 for k in range(21)]
    ring = [(x, _bow(x) + 0.07) for x in rx] + [(x, _bow(x)) for x in reversed(rx)]
    m.loft([(y0 - 0.035, ring), (y1 + 0.03, ring)], {"roof": roof})
    for k in range(13):
        y = y0 - 0.02 + k * (y1 - y0 + 0.04) / 12
        for a, b in zip(rx, rx[1:]):
            m.rod((a, y, _bow(a) + 0.075), (b, y, _bow(b) + 0.075), 0.003, seam, segments=4)
    for y in (-0.3, -0.05, 0.2):
        m.lathe(0.0, y, 3.3, [(0.02, 0), (0.02, 0.08), (0.034, 0.1), (0.03, 0.16), (0.0, 0.17)], seam, segments=12)
    # Clerestory windows in the gable over the doors, and a trim board along the roof's edge.
    for k in range(8):
        x = -0.28 + k * 0.08
        m.box(x - 0.032, x + 0.032, y1 + 0.004, y1 + 0.008, 2.42, 2.74, glass)
        m.box(x - 0.036, x + 0.036, y1 + 0.004, y1 + 0.01, 2.38, 2.42, trim)
    for a, b in zip(rx, rx[1:]):
        m.rod((a, y1 + 0.032, _bow(a) + 0.03), (b, y1 + 0.032, _bow(b) + 0.03), 0.008, trim, segments=6)
    # The door head girder across the front and its pockets out at the sides, the dark hall behind the opening.
    m.box(-0.47, 0.47, y1, y1 + 0.05, 2.14, 2.34, trim, 0.003)
    for s in (-1, 1):
        _bx(m, s * w, s * 0.47, y1 - 0.08, y1 + 0.05, 0.0, 2.34, siding)
        for k in range(1, 21):
            _bx(m, s * 0.47, s * 0.473, y1 - 0.08, y1 + 0.05, k * 0.11, k * 0.11 + 0.012, lines)
        _bx(m, s * 0.465, s * 0.475, y1 + 0.04, y1 + 0.055, 0.0, 2.34, trim)
    m.box(-w + 0.01, w - 0.01, y1 - 0.12, y1 - 0.004, 0.0, 2.14, dark)
    # Concrete apron in front with the three door tracks set in it.
    m.box(-0.49, 0.49, y1, 0.5, 0.0, 0.015, material("hangar apron", "#aaa69e", rough=0.9))
    rail = material("door track", "#5a5a5c", metal=0.6, rough=0.4)
    tracks = (y1 + 0.012, y1 + 0.024, y1 + 0.036)
    for y in tracks:
        m.box(-0.47, 0.47, y - 0.002, y + 0.002, 0.015, 0.022, rail)
    paint = material("hangar doors", "#6a8068", rough=0.6)
    brace = material("door braces", "#4e6250", rough=0.6)
    for k in range(3):
        _leaf(m, -0.395 + k * 0.13, -0.395 + k * 0.13 + 0.138, tracks[k], 2.13, paint, trim, glass, brace)
    for k in range(3):
        _leaf(m, 0.31, 0.448, tracks[k], 2.13, paint, trim, glass, brace)
    m.box(-0.36, -0.32, tracks[0] + 0.006, tracks[0] + 0.01, 0.03, 0.82, material("wicket door", "#3e4a40", rough=0.6))
    for x in (-0.22, 0.12):
        harbour.lamp(m, x, y1 + 0.05, 2.34, 2.44, reach=0.045, toward=(0.0, 1.0))
    # Lean-to shops down both sides: shed roofs, windows, doors and a chimney.
    shop = material("lean-to siding", "#d6cdb2", rough=0.8)
    lroof = material("lean-to roof", "#3e4244", rough=0.75)
    trim_white = material("lean-to trim", "#ecebe4", rough=0.6)
    ly0, ly1 = -0.43, 0.3
    for s in (-1, 1):
        a, b = s * (w + 0.012), s * 0.49
        bot = [(a, ly0, 0.0), (b, ly0, 0.0), (b, ly1, 0.0), (a, ly1, 0.0)]
        m.wedge(bot, [(a, ly0, 1.05), (b, ly0, 0.8), (b, ly1, 0.8), (a, ly1, 1.05)], shop)
        slab = [(a, ly0 - 0.015, 1.06), (s * 0.5, ly0 - 0.015, 0.8), (s * 0.5, ly1 + 0.015, 0.8), (a, ly1 + 0.015, 1.06)]
        houses.slab(m, slab, 0.045, lroof)
        for k in range(1, 7):
            _bx(m, b, b + s * 0.003, ly0, ly1, k * 0.11, k * 0.11 + 0.012, lines)
        for k, c in enumerate((-0.34, -0.2, -0.06, 0.1, 0.24)):
            if k == 2:
                _bx(m, b, b + s * 0.006, c - 0.03, c + 0.03, 0.0, 0.62, trim)
                continue
            houses.pane(m, "x", b, s, c, 0.3, 0.62, 0.075, glass, trim_white, trim, bars=1)
    m.upright(0.45, -0.32, 0.9, 1.45, 0.018, 0.018, material("shop chimney", "#8a4a36", rough=0.85), segments=8)


def _arch_hangar(m):
    """A Butler steel arch hangar: galvanized corrugated sheets bent over in one span on a concrete curb, end walls of
    ribbed steel, two pairs of sliding doors on a track running out past the arch (the right hand pair slid open),
    windows low in the sides, a stovepipe and ventilators."""
    galv = material("arch steel", "#a2a6a6", metal=0.6, rough=0.45)
    corr = material("arch corrugations", "#8a8e90", metal=0.6, rough=0.45)
    end = material("arch end wall", "#d4d0c4", metal=0.3, rough=0.6)
    rib = material("end wall ribs", "#b8b4a8", metal=0.3, rough=0.6)
    dark = material("hangar inside", "#1c1a18", rough=1.0)
    steel = material("door track", "#3a3c3e", metal=0.5, rough=0.5)
    glass = houses.glass()
    w, hh, p = 0.42, 2.5, 2.6
    y0, y1 = -0.42, 0.39

    def at(a, ww=w, h=hh):
        c, s = math.cos(a), math.sin(a)
        return (ww * math.copysign(abs(c) ** (2 / p), c), h * abs(s) ** (2 / p))

    def z_at(x):
        return hh * max(0.0, 1 - (abs(x) / w) ** p) ** (1 / p)

    angles = [math.pi * k / 24 for k in range(25)]
    outer = [at(a) for a in angles]
    inner = [at(a, w - 0.012, hh - 0.05) for a in reversed(angles)]
    m.box(-w - 0.01, w + 0.01, y0 - 0.01, y1 + 0.01, 0.0, 0.05, material("hangar curb", "#a8a49c", rough=0.9))
    m.loft([(y0, outer + inner), (y1 + 0.01, outer + inner)], {"steel": galv})
    big = [at(a, w + 0.003, hh + 0.012) for a in angles]
    for k in range(17):
        y = y0 + 0.02 + k * (y1 - y0 - 0.03) / 16
        for (xa, za), (xb, zb) in zip(big, big[1:]):
            m.rod((xa, y, za), (xb, y, zb), 0.0025, corr, segments=4)
    # The front wall: a header over the door and the pieces either side down to the arch, the back wall whole.
    door_w, door_h = 0.34, 1.68
    right = [(door_w, 0.0)] + sorted([q for q in outer if q[0] > door_w], key=lambda q: q[1]) + [(door_w, z_at(door_w))]
    left = [(-x, z) for x, z in reversed(right)]
    head = [(door_w, door_h), (door_w, z_at(door_w))] + [q for q in outer if abs(q[0]) < door_w] + [(-door_w, z_at(door_w)), (-door_w, door_h)]
    for ring in (right, left, head):
        _wall(m, y1 - 0.01, y1, ring, end)
    _wall(m, y0 - 0.002, y0 + 0.008, outer, end)
    for k in range(-15, 16):
        x = k * 0.027
        top = z_at(x) - 0.03
        if abs(x) < door_w and top > door_h:
            m.box(x - 0.002, x + 0.002, y1, y1 + 0.004, door_h + 0.08, top, rib)
        elif abs(x) >= door_w:
            m.box(x - 0.002, x + 0.002, y1, y1 + 0.004, 0.05, top, rib)
        m.box(x - 0.002, x + 0.002, y0 - 0.006, y0 - 0.002, 0.05, top, rib)
    m.box(-door_w, door_w, y1 - 0.1, y1 - 0.004, 0.0, door_h, dark)
    for x in (-0.09, 0.09):
        m.box(x - 0.04, x + 0.04, y1 + 0.002, y1 + 0.006, 1.98, 2.2, glass)
        m.box(x - 0.044, x + 0.044, y1 + 0.002, y1 + 0.008, 1.95, 1.98, rib)
    m.box(-0.05, 0.05, y0 - 0.008, y0 - 0.004, 1.6, 1.85, glass)
    # The door track on outriggers past the arch, and four leaves of ribbed steel with a strip of windows.
    m.box(-0.5, 0.5, y1 + 0.004, y1 + 0.03, door_h + 0.02, door_h + 0.1, steel, 0.003)
    for s in (-1, 1):
        m.upright(s * 0.49, y1 + 0.017, 0.0, door_h + 0.1, 0.008, 0.008, steel, segments=8)
    m.box(-0.5, 0.5, y1, 0.5, 0.0, 0.012, material("hangar apron", "#aaa69e", rough=0.9))
    leaf = material("arch doors", "#5a6e5a", metal=0.3, rough=0.55)
    leaf_rib = material("arch door ribs", "#4a5c4a", metal=0.3, rough=0.55)
    for xa, y in ((-0.34, y1 + 0.01), (-0.17, y1 + 0.022), (0.32, y1 + 0.01), (0.32, y1 + 0.022)):
        xb = xa + 0.175
        m.box(xa, xb, y - 0.005, y + 0.005, 0.015, door_h, leaf)
        harbour.ribs(m, xa + 0.006, xb - 0.006, y + 0.003, 0.02, door_h - 0.02, leaf_rib, gap=0.022, face=1)
        m.box(xa + 0.012, xb - 0.012, y + 0.005, y + 0.01, 1.2, 1.4, glass)
        m.box(xa, xb, y - 0.006, y + 0.012, door_h - 0.05, door_h, leaf_rib)
    # Windows low in the sides where the arch stands near upright, a stovepipe and ventilators along the crown.
    for s in (-1, 1):
        for y in (-0.28, -0.06, 0.16):
            _bx(m, s * 0.414, s * 0.426, y - 0.04, y + 0.04, 0.28, 0.55, glass)
            _bx(m, s * 0.412, s * 0.428, y - 0.045, y + 0.045, 0.25, 0.28, rib)
    pipe = material("stovepipe", "#4a4a4c", metal=0.6, rough=0.4)
    m.upright(0.24, -0.25, z_at(0.24) - 0.1, z_at(0.24) + 0.45, 0.008, 0.008, pipe, segments=10)
    m.lathe(0.24, -0.25, z_at(0.24) + 0.45, [(0.0, 0), (0.02, 0.04), (0.0, 0.07)], pipe, segments=10)
    for y in (-0.2, 0.12):
        m.lathe(0.0, y, hh, [(0.018, 0), (0.018, 0.07), (0.03, 0.09), (0.026, 0.14), (0.0, 0.15)], corr, segments=12)
    for k in range(3):
        harbour.drum(m, -0.47, -0.3 + k * 0.04, 0.0, colour="#c8302a", r=0.016, h=0.36)


def _wood_hangar(m):
    """A 1930s flying club hangar in wood: white clapboard walls under a Belfast truss roof (a low bow) in tarred roll
    roofing, four sliding wooden door leaves with windows (the right hand pair slid back), a lean-to office with a
    stovepipe down one side, and a windsock on the roof."""
    siding = material("club hangar siding", "#e6e2d4", rough=0.8)
    lines = material("club hangar siding lines", "#c4bfae", rough=0.85)
    trim = material("club hangar trim", "#2e5a3a", rough=0.6)
    roof = material("roll roofing", "#3e3c3a", rough=0.95)
    seam = material("roll roofing laps", "#54504a", rough=0.9)
    dark = material("hangar inside", "#1c1a18", rough=1.0)
    glass = houses.glass()
    w, y0, y1 = 0.36, -0.4, 0.36
    base, rise = 1.45, 0.62

    def bow(x):
        return _bow(x, w, base, rise)
    xs = [-w + 2 * w * k / 16 for k in range(17)]
    m.box(-w - 0.01, w + 0.01, y0 - 0.01, y1 + 0.01, 0.0, 0.05, material("hangar sill", "#9a968c", rough=0.9))
    for s in (-1, 1):
        _bx(m, s * w - 0.012, s * w + 0.012, y0, y1, 0.05, base, siding)
        for k in range(1, 14):
            _bx(m, s * (w + 0.012), s * (w + 0.015), y0, y1, 0.05 + k * 0.1, 0.05 + k * 0.1 + 0.01, lines)
        _bx(m, s * (w + 0.008), s * (w + 0.016), y0 - 0.004, y0 + 0.012, 0.05, base, trim)
        _bx(m, s * (w + 0.008), s * (w + 0.016), y1 - 0.012, y1 + 0.004, 0.05, base, trim)
    _wall(m, y0 - 0.004, y0 + 0.012, [(x, bow(x)) for x in xs], siding)
    m.box(-w, w, y0 - 0.012, y0 + 0.012, 0.05, base, siding)
    for k in range(1, 20):
        z = 0.05 + k * 0.1
        half = w * (1 - max(0.0, (z - base) / rise)) ** 0.5 if z > base else w
        if half > 0.02:
            m.box(-half, half, y0 - 0.015, y0 - 0.012, z, z + 0.01, lines)
    # The front: the gable over the doors, a trim board along the bow, a door head beam.
    door_h = 1.18
    _wall(m, y1 - 0.012, y1 + 0.004, [(x, bow(x)) for x in xs], siding)
    m.box(-w, w, y1 - 0.012, y1 + 0.004, door_h, base, siding)
    m.box(-w, w, y1 - 0.012, y1 + 0.006, door_h, door_h + 0.06, trim)
    for k in range(1, 8):
        z = door_h + 0.06 + k * 0.1
        half = w * (1 - max(0.0, (z - base) / rise)) ** 0.5 if z > base else w
        if half > 0.02:
            m.box(-half, half, y1 + 0.004, y1 + 0.007, z, z + 0.01, lines)
    for x in (-0.12, 0.12):
        m.box(x - 0.05, x + 0.05, y1 + 0.004, y1 + 0.008, base + 0.08, base + 0.3, glass)
        m.box(x - 0.054, x + 0.054, y1 + 0.004, y1 + 0.01, base + 0.06, base + 0.08, trim)
    rx = [-(w + 0.04) + 2 * (w + 0.04) * k / 16 for k in range(17)]
    ring = [(x, _bow(x, w + 0.04, base - 0.03, rise + 0.03) + 0.06) for x in rx] + [(x, _bow(x, w + 0.04, base - 0.03, rise + 0.03)) for x in reversed(rx)]
    m.loft([(y0 - 0.03, ring), (y1 + 0.03, ring)], {"roof": roof})
    for k in range(9):
        y = y0 - 0.02 + k * (y1 - y0 + 0.04) / 8
        for a, b in zip(rx, rx[1:]):
            za = _bow(a, w + 0.04, base - 0.03, rise + 0.03) + 0.064
            zb = _bow(b, w + 0.04, base - 0.03, rise + 0.03) + 0.064
            m.rod((a, y, za), (b, y, zb), 0.0025, seam, segments=4)
    for a, b in zip(rx, rx[1:]):
        za = _bow(a, w + 0.04, base - 0.03, rise + 0.03) + 0.02
        zb = _bow(b, w + 0.04, base - 0.03, rise + 0.03) + 0.02
        m.rod((a, y1 + 0.03, za), (b, y1 + 0.03, zb), 0.007, trim, segments=6)
    # The dark hall behind the opening, four door leaves on a track, two folded back to the right.
    m.box(-w + 0.01, w - 0.01, y1 - 0.1, y1 - 0.004, 0.05, door_h, dark)
    m.box(-w, w, y1, 0.5, 0.0, 0.012, material("hangar apron", "#aaa69e", rough=0.9))
    paint = material("club hangar doors", "#e6e2d4", rough=0.75)
    brace = material("club door braces", "#2e5a3a", rough=0.6)
    lw = 2 * w / 4
    for k in range(2):
        _leaf(m, -w + k * lw, -w + (k + 1) * lw, y1 + 0.008, door_h - 0.01, paint, trim, glass, brace)
    for k in range(2):
        _leaf(m, w - lw, w, y1 + 0.008 + 0.014 * (k + 1), door_h - 0.01, paint, trim, glass, brace)
    for k in range(3):
        y = y1 + 0.008 + 0.014 * k
        m.box(-w, w, y - 0.002, y + 0.002, 0.012, 0.018, material("door track", "#5a5a5c", metal=0.6, rough=0.4))
    # The lean-to office on the left: clapboard, a shed roof, windows to the field, a door and a stovepipe.
    ox0, ox1, oy0, oy1 = -w - 0.012, -0.49, -0.3, 0.2
    bot = [(ox0, oy0, 0.0), (ox1, oy0, 0.0), (ox1, oy1, 0.0), (ox0, oy1, 0.0)]
    m.wedge(bot, [(ox0, oy0, 0.85), (ox1, oy0, 0.65), (ox1, oy1, 0.65), (ox0, oy1, 0.85)], siding)
    houses.slab(m, [(ox0, oy0 - 0.02, 0.86), (-0.5, oy0 - 0.02, 0.64), (-0.5, oy1 + 0.02, 0.64), (ox0, oy1 + 0.02, 0.86)], 0.035, roof)
    for k in range(1, 6):
        _bx(m, ox1, ox1 - 0.003, oy0, oy1, k * 0.1, k * 0.1 + 0.01, lines)
    white = material("club window trim", "#f2f0e8", rough=0.6)
    for c in (-0.2, -0.04):
        houses.pane(m, "x", ox1, -1, c, 0.25, 0.52, 0.07, glass, white, trim)
    m.box(-0.47, -0.43, oy1, oy1 + 0.004, 0.0, 0.5, trim)
    houses.pane(m, "y", oy1, 1, -0.45, 0.22, 0.48, 0.045, glass, white, trim)
    pipe = material("stovepipe", "#4a4a4c", metal=0.6, rough=0.4)
    m.upright(-0.45, -0.2, 0.7, 1.15, 0.008, 0.008, pipe, segments=10)
    harbour.windsock(m, 0.0, -0.1, base + rise, base + rise + 0.6, toward=(0.7, -0.7))
    for k in range(2):
        harbour.drum(m, 0.42, -0.3 + k * 0.04, 0.0, colour="#c8302a", r=0.016, h=0.36)


def _steel_hangar(m):
    """A clad steel hangar of the 1970s on: a wide portal frame shed under a low pitched roof, walls of ribbed steel
    sheets in light grey with a blue band, a box girder door head across the whole front, six telescoping steel door
    leaves (two run back to open it), skylight strips and ridge vents, a two storey office block down one side."""
    clad = material("hangar cladding", "#c8ccce", metal=0.4, rough=0.5)
    rib = material("hangar cladding ribs", "#aeb3b6", metal=0.4, rough=0.5)
    band = material("hangar band", "#2e5a8a", metal=0.3, rough=0.5)
    roof = material("hangar steel roof", "#8e9396", metal=0.4, rough=0.5)
    light = material("skylight", "#d6dcd8", rough=0.4)
    dark = material("hangar inside", "#1c1a18", rough=1.0)
    door = material("hangar steel doors", "#dfe2e2", metal=0.3, rough=0.5)
    line = material("door panel lines", "#a6abae", metal=0.3, rough=0.5)
    glass = material("office glass", "#2e4a5e", rough=0.08, metal=0.3)
    w, y0, y1 = 0.4, -0.45, 0.4
    eave, ridge = 2.3, 2.62
    for s in (-1, 1):
        _bx(m, s * w - 0.012, s * w, y0, y1, 0.0, eave, clad)
        harbour.ribs_y(m, s * w, y0 + 0.01, y1 - 0.01, 0.0, eave, rib, gap=0.03, face=s)
        _bx(m, s * w, s * (w + 0.008), y0, y1, eave - 0.3, eave - 0.2, band)
    end = [(-w, 0.0), (w, 0.0), (w, eave), (0.0, ridge), (-w, eave)]
    _wall(m, y0, y0 + 0.012, end, clad)
    for k in range(-16, 17):
        x = k * 0.028
        m.box(x - 0.003, x + 0.003, y0 - 0.008, y0 - 0.002, 0.0, eave + (ridge - eave) * (1 - abs(x) / w) - 0.01, rib)
    m.box(-w, w, y0 - 0.008, y0, eave - 0.3, eave - 0.2, band)
    # The roof with skylight strips down the slopes and ridge vents.
    ov = 0.03
    for s in (-1, 1):
        a, b = s * (w + ov), 0.0
        za, zb = eave - ov * (ridge - eave) / w, ridge
        houses.slab(m, [(a, y0 - ov, za), (b, y0 - ov, zb), (b, y1 + ov, zb), (a, y1 + ov, za)], 0.04, roof)
        for f in (0.35, 0.7):
            x = s * w * f
            z = ridge - (ridge - eave) * f + 0.045
            m.box(x - 0.025, x + 0.025, y0 + 0.04, y1 - 0.04, z - 0.01, z + 0.006, light)
    for y in (-0.3, -0.05, 0.2):
        m.box(-0.04, 0.04, y - 0.04, y + 0.04, ridge + 0.02, ridge + 0.12, rib)
        m.box(-0.05, 0.05, y - 0.05, y + 0.05, ridge + 0.12, ridge + 0.15, roof)
    # The door head girder over the opening, the gable above it, the hall and six leaves on tracks.
    door_h = 1.95
    m.box(-w - 0.01, w + 0.01, y1 - 0.02, y1 + 0.04, door_h, eave + 0.02, band, 0.003)
    _wall(m, y1 - 0.012, y1, [(-w, eave), (w, eave), (0.0, ridge)], clad)
    for k in range(-15, 16):
        x = k * 0.028
        m.box(x - 0.003, x + 0.003, y1, y1 + 0.006, eave + 0.02, eave + (ridge - eave) * (1 - abs(x) / w) - 0.01, rib)
    m.box(-w + 0.01, w - 0.01, y1 - 0.12, y1 - 0.004, 0.0, door_h, dark)
    m.box(-0.5, 0.5, y1, 0.5, 0.0, 0.014, material("hangar apron", "#b4b0a8", rough=0.9))
    rail = material("door track", "#5a5a5c", metal=0.6, rough=0.4)
    tracks = (y1 + 0.01, y1 + 0.022, y1 + 0.034)
    for y in tracks:
        m.box(-0.5, 0.5, y - 0.002, y + 0.002, 0.014, 0.02, rail)
    lw = 0.155
    leaves = [(-w, tracks[0]), (-w + lw, tracks[1]), (-w + 2 * lw - 0.01, tracks[2]),
              (w - lw, tracks[0]), (w - lw - 0.01, tracks[1]), (w - lw - 0.02, tracks[2])]
    for xa, y in leaves:
        xb = xa + lw
        m.box(xa, xb, y - 0.005, y + 0.005, 0.02, door_h - 0.01, door)
        for k in range(1, 9):
            z = k * door_h / 9
            m.box(xa, xb, y + 0.005, y + 0.007, z - 0.004, z + 0.004, line)
        m.box(xa + 0.012, xb - 0.012, y + 0.005, y + 0.008, door_h * 0.7, door_h * 0.78, glass)
    for x in (-0.3, 0.0, 0.3):
        harbour.lamp(m, x, y1 + 0.04, door_h + 0.2, door_h + 0.3, reach=0.04, toward=(0.0, 1.0))
    # The office block on the right: two storeys of ribbon windows, a flat roof with coping, a door and a canopy.
    ox0, ox1, oy0, oy1 = w, 0.5, -0.4, 0.3
    precast = material("office panels", "#e2e0d8", rough=0.75)
    m.box(ox0, ox1, oy0, oy1, 0.0, 1.0, precast)
    m.box(ox0, ox1 + 0.004, oy0 - 0.004, oy1 + 0.004, 0.98, 1.04, band)
    for z in (0.18, 0.62):
        m.box(ox1, ox1 + 0.004, oy0 + 0.04, oy1 - 0.04, z, z + 0.2, glass)
        for k in range(10):
            y = oy0 + 0.04 + k * (oy1 - oy0 - 0.08) / 9
            m.box(ox1, ox1 + 0.007, y - 0.002, y + 0.002, z, z + 0.2, rib)
    m.box(ox0 + 0.004, ox1 - 0.004, oy1, oy1 + 0.004, 0.02, 0.4, glass)
    m.box(ox0 - 0.02, ox1 + 0.02, oy1, oy1 + 0.08, 0.44, 0.47, band)
    for k in range(3):
        harbour.drum(m, -0.48, -0.3 + k * 0.04, 0.0, colour="#d8b828", r=0.016, h=0.36)


# Terminals: 0 a Department of Transport terminal of the 1940s, 1 a 1960s glass curtain terminal, 2 a 1990s glass hall.
TERMINAL_ERAS = 3
TERMINAL_TALL = {"small": 1.1, "mid": 2.0, "big": 2.6}
TERMINAL_FRONT = {"small": 0.25, "mid": 0.35, "big": 0.42}


def terminal(m, size, era, cab=False):
    """A terminal its airside front to +y, in the look of [era]. The small one stands alone; mid and big ones take two
    and three tiles, so run the whole tile across and join up. cab puts the 1940s control cab on a mid or big one's roof,
    for one of its tiles (ChunkBaker picks which)."""
    tall, y1 = TERMINAL_TALL[size], TERMINAL_FRONT[size]
    ends = size == "small"
    x0, x1 = (-0.44, 0.44) if ends else (-0.5, 0.5)
    if era == 0:
        _terminal_dot(m, size, tall, x0, x1, -0.42, y1, ends, cab or ends)
        return
    (_terminal_dot, _terminal_curtain, _terminal_hall)[era](m, size, tall, x0, x1, -0.42, y1, ends)


def _band(m, xa, xb, y, d, z0, z1, glass, frame, sill, pitch=0.05, transom=True):
    """A band of steel sash windows in the wall at y facing d: glass with mullions every pitch, a transom, a sill."""
    m.box(xa, xb, *sorted((y + d * 0.001, y + d * 0.004)), z0, z1, glass)
    n = max(1, round((xb - xa) / pitch))
    for k in range(n + 1):
        x = xa + (xb - xa) * k / n
        m.box(x - 0.0025, x + 0.0025, *sorted((y, y + d * 0.007)), z0, z1, frame)
    if transom:
        zt = z0 + (z1 - z0) * 0.66
        m.box(xa, xb, *sorted((y, y + d * 0.006)), zt - 0.01, zt + 0.01, frame)
    m.box(xa - 0.006, xb + 0.006, *sorted((y, y + d * 0.014)), z0 - 0.04, z0, sill)


def _terminal_dot(m, size, tall, x0, x1, y0, y1, ends, cab=True):
    """A Department of Transport terminal of the 1940s in buff brick: bands of steel sash windows, a stone entrance bay
    with glass block and a canopy, a flat roof with coping; the small one has a rounded end and the control cab on its
    roof, the bigger ones a spectators' deck."""
    brick = material("terminal brick", "#c9b48c", rough=0.85)
    stone = material("terminal stone", "#e4ddcb", rough=0.8)
    frame = material("steel sash", "#2e3634", metal=0.3, rough=0.5)
    tar = material("tar roof", "#3a3a3c", rough=0.95)
    glass = houses.glass()
    r = 0.12
    xe = x1 - r if ends else x1
    m.box(x0, xe, y0, y1, 0.0, tall, brick)
    m.box(x0 - 0.004, xe, y0 - 0.004, y1 + 0.004, 0.0, 0.1, stone)
    if ends:
        m.box(xe - 0.001, x1, y0, y1 - r, 0.0, tall, brick)
        m.box(xe - 0.001, x1 + 0.004, y0 - 0.004, y1 - r, 0.0, 0.1, stone)
        m.upright(xe, y1 - r, 0.0, tall, r, r, brick, segments=32)
        m.upright(xe, y1 - r, 0.0, 0.1, r + 0.004, r + 0.004, stone, segments=32)
    # Coping round the roof, the tar roof inside it.
    m.box(x0, x1 if not ends else xe, y0 + 0.01, y1 - 0.01, tall, tall + 0.03, tar)
    for y in (y0, y1):
        m.box(x0 - (0.006 if ends else 0), xe, y - 0.008, y + 0.008, tall, tall + 0.1, stone)
    if ends:
        m.box(x0 - 0.008, x0 + 0.008, y0, y1, tall, tall + 0.1, stone)
        m.box(x1 - 0.008, x1 + 0.006, y0, y1 - r, tall, tall + 0.1, stone)
        m.upright(xe, y1 - r, tall - 0.001, tall + 0.1, r + 0.006, r + 0.006, stone, segments=32)
        m.upright(xe, y1 - r, tall + 0.09, tall + 0.104, r - 0.01, r - 0.01, tar, segments=32)
    # Window bands, a storey at a time, round the rounded end on the small one.
    storeys = max(1, round(tall / 0.95))
    sh = tall / storeys
    bay = (-0.13, 0.13) if not ends else (-0.12, 0.1)
    for s in range(storeys):
        za, zb = s * sh + sh * 0.32, s * sh + sh * 0.8
        for xa, xb in ((x0 + (0.04 if ends else 0.0), bay[0] - 0.03), (bay[1] + 0.03, xe - (0.0 if ends else 0.0))):
            _band(m, xa, xb, y1, 1, za, zb, glass, frame, stone)
        _band(m, x0 + (0.04 if ends else 0.0), xe - (0.02 if ends else 0.0), y0, -1, za, zb, glass, frame, stone, pitch=0.06)
        if ends:
            m.upright(xe, y1 - r, za, zb, r + 0.002, r + 0.002, glass, segments=32)
            for k in range(7):
                a = k * math.pi / 12
                m.rod((xe + math.cos(a) * (r + 0.004), y1 - r + math.sin(a) * (r + 0.004), za),
                      (xe + math.cos(a) * (r + 0.004), y1 - r + math.sin(a) * (r + 0.004), zb), 0.0028, frame, segments=4)
            m.upright(xe, y1 - r, za - 0.04, za, r + 0.012, r + 0.012, stone, segments=32)
    # The entrance bay: stone, standing proud and over the roof, a tall strip of glass block, doors and a canopy.
    ba, bb = bay
    bt = tall + 0.3
    m.box(ba, bb, y1 - 0.02, y1 + 0.05, 0.0, bt, stone)
    m.box(ba - 0.006, bb + 0.006, y1 - 0.02, y1 + 0.056, bt - 0.05, bt, stone)
    block = material("glass block", "#a8bcc4", rough=0.3)
    c = (ba + bb) / 2
    m.box(c - 0.045, c + 0.045, y1 + 0.05, y1 + 0.054, 0.75, bt - 0.12, block)
    z = 0.78
    while z < bt - 0.14:
        m.box(c - 0.045, c + 0.045, y1 + 0.054, y1 + 0.056, z, z + 0.006, stone)
        z += 0.045
    for k in (-1, 1):
        m.box(c + k * 0.015 - 0.003, c + k * 0.015 + 0.003, y1 + 0.054, y1 + 0.056, 0.75, bt - 0.12, stone)
    m.box(c - 0.07, c + 0.07, y1 + 0.05, y1 + 0.054, 0.02, 0.5, material("entrance doors", "#2a3236", metal=0.3, rough=0.4))
    for k in (-1, 0, 1):
        m.box(c + k * 0.035 - 0.003, c + k * 0.035 + 0.003, y1 + 0.054, y1 + 0.058, 0.02, 0.5, frame)
    m.box(c - 0.11, c + 0.11, y1 + 0.05, y1 + 0.17, 0.56, 0.6, stone, 0.012)
    m.box(c - 0.11, c + 0.11, y1 + 0.165, y1 + 0.172, 0.53, 0.62, frame)
    if cab:
        # The control cab: a brick room on the roof, a catwalk round it with a rail, the glass cab leaning out, a beacon.
        cx, cy = -0.25, -0.1
        zr = tall + 0.03
        m.box(cx - 0.1, cx + 0.1, cy - 0.1, cy + 0.1, zr, zr + 0.32, brick)
        _band(m, cx - 0.06, cx + 0.06, cy + 0.1, 1, zr + 0.1, zr + 0.26, glass, frame, stone, pitch=0.04, transom=False)
        m.box(cx - 0.14, cx + 0.14, cy - 0.14, cy + 0.14, zr + 0.32, zr + 0.36, stone)
        rail = material("cab rail", "#2e3634", metal=0.4, rough=0.5)
        corners = [(cx - 0.135, cy - 0.135), (cx + 0.135, cy - 0.135), (cx + 0.135, cy + 0.135), (cx - 0.135, cy + 0.135)]
        for (ax, ay), (bx, by) in zip(corners, corners[1:] + corners[:1]):
            m.rod((ax, ay, zr + 0.52), (bx, by, zr + 0.52), 0.002, rail, segments=4)
            for f in (0.0, 0.33, 0.67):
                px, py = ax + (bx - ax) * f, ay + (by - ay) * f
                m.rod((px, py, zr + 0.36), (px, py, zr + 0.52), 0.0018, rail, segments=4)
        m.box(cx - 0.085, cx + 0.085, cy - 0.085, cy + 0.085, zr + 0.36, zr + 0.42, brick)
        _frustum(m, cx, cy, zr + 0.42, zr + 0.72, 0.085 * math.sqrt(2), 0.105 * math.sqrt(2), 4, glass, rot=math.pi / 4)
        for px, py in _poly(4, 1.0, math.pi / 4):
            m.rod((cx + px * 0.085, cy + py * 0.085, zr + 0.42), (cx + px * 0.105, cy + py * 0.105, zr + 0.72), 0.004, frame, segments=5)
        m.box(cx - 0.125, cx + 0.125, cy - 0.125, cy + 0.125, zr + 0.72, zr + 0.78, material("cab roof", "#3a3a3c", rough=0.6), 0.004)
        m.upright(cx, cy, zr + 0.78, zr + 0.86, 0.02, 0.016, rail, segments=10)
        m.upright(cx, cy, zr + 0.86, zr + 0.94, 0.018, 0.018, material("beacon", "#3a9a5a", rough=0.2), segments=10)
        m.rod((cx + 0.08, cy - 0.08, zr + 0.78), (cx + 0.08, cy - 0.08, zr + 1.3), 0.003, rail)
        m.rod((cx + 0.06, cy - 0.08, zr + 1.2), (cx + 0.1, cy - 0.08, zr + 1.2), 0.002, rail)
    if not ends:
        # The spectators' deck: a rail along the front of the roof and a stair house onto it.
        rail = material("deck rail", "#2e3634", metal=0.4, rough=0.5)
        for k in range(11):
            x = -0.5 + k * 0.1
            if ba - 0.01 < x < bb + 0.01:
                continue
            m.rod((x, y1 - 0.02, tall + 0.1), (x, y1 - 0.02, tall + 0.36), 0.002, rail, segments=4)
        for xa, xb in ((-0.5, ba), (bb, 0.5)):
            m.rod((xa, y1 - 0.02, tall + 0.36), (xb, y1 - 0.02, tall + 0.36), 0.0025, rail, segments=4)
            m.rod((xa, y1 - 0.02, tall + 0.23), (xb, y1 - 0.02, tall + 0.23), 0.0018, rail, segments=4)
        m.box(0.22, 0.38, -0.25, -0.05, tall, tall + 0.48, brick)
        m.box(0.215, 0.385, -0.255, -0.045, tall + 0.48, tall + 0.53, stone)
        m.box(0.27, 0.33, -0.05, -0.046, tall + 0.03, tall + 0.38, material("stair door", "#3e5a44", rough=0.6))


def _terminal_curtain(m, size, tall, x0, x1, y0, y1, ends):
    """A 1960s terminal: a glass curtain wall front with aluminium mullions and dark spandrels, a deep white roof
    fascia, a long canopy along the front on slim columns, precast fins on the back; the small one between fieldstone
    end walls."""
    glass = material("curtain glass", "#3e6276", rough=0.08, metal=0.3)
    mullion = material("aluminium", "#c8ccd0", metal=0.6, rough=0.35)
    spandrel = material("spandrel panels", "#2e3e4a", rough=0.4)
    fascia = material("roof fascia", "#ebe9e2", rough=0.6)
    precast = material("precast", "#d4d2ca", rough=0.8)
    soffit = material("canopy soffit", "#b8b6ae", rough=0.7)
    m.box(x0, x1, y0, y1, 0.0, tall, precast)
    storeys = 1 if size == "small" else 2
    sh = tall / storeys
    m.box(x0 + (0.01 if ends else 0.0), x1 - (0.01 if ends else 0.0), y1, y1 + 0.006, 0.02, tall, glass)
    n = max(1, round((x1 - x0) / 0.055))
    for k in range(n + 1):
        x = x0 + (x1 - x0) * k / n
        if ends and k in (0, n):
            continue
        m.box(x - 0.003, x + 0.003, y1 + 0.006, y1 + 0.018, 0.02, tall, mullion)
    for s in range(1, storeys + 1):
        z = s * sh
        m.box(x0, x1, y1 + 0.004, y1 + 0.009, z - 0.16, z, spandrel)
    m.box(x0, x1, y1 + 0.004, y1 + 0.009, 0.0, 0.05, spandrel)
    # The roof: a deep fascia standing out all round, a gravel roof inside, a louvred penthouse.
    o = 0.03 if ends else 0.0
    m.box(x0 - o, x1 + o, y0 - 0.035, y1 + 0.05, tall, tall + 0.15, fascia, 0.004)
    m.box(x0 - o, x1 + o, y1 + 0.02, y1 + 0.05, tall - 0.02, tall, soffit)
    m.box(x0 - o + 0.02, x1 + o - 0.02, y0 - 0.015, y1 + 0.03, tall + 0.15, tall + 0.17, material("gravel roof", "#7a7670", rough=1.0))
    m.box(0.12, 0.34, -0.32, -0.08, tall + 0.15, tall + 0.4, precast)
    for k in range(9):
        x = 0.135 + k * 0.024
        m.box(x - 0.006, x + 0.006, -0.08, -0.076, tall + 0.2, tall + 0.36, spandrel)
    m.box(0.11, 0.35, -0.33, -0.07, tall + 0.4, tall + 0.44, fascia)
    # The long canopy along the front.
    cz = 0.78 if size == "small" else 1.05
    cy = y1 + 0.16
    m.box(x0 - o, x1 + o, y1 + 0.018, cy, cz, cz + 0.05, fascia)
    m.box(x0 - o, x1 + o, cy - 0.006, cy + 0.008, cz - 0.03, cz + 0.08, fascia)
    m.box(x0 - o, x1 + o, y1 + 0.018, cy - 0.006, cz - 0.004, cz, soffit)
    cols = (-0.32, -0.08, 0.16, 0.38) if ends else (-0.375, -0.125, 0.125, 0.375)
    for x in cols:
        m.upright(x, cy - 0.02, 0.0, cz, 0.008, 0.008, mullion, segments=10)
    # The back: precast fins and a band of windows high up, a door for the baggage room.
    nb = round((x1 - x0) / 0.06)
    for k in range(nb + 1):
        x = x0 + (x1 - x0) * k / nb
        if ends and k in (0, nb):
            continue
        m.box(x - 0.006, x + 0.006, y0 - 0.016, y0, 0.05, tall - 0.02, precast)
    for k in range(nb):
        xa = x0 + (x1 - x0) * k / nb
        m.box(xa + 0.008, xa + (x1 - x0) / nb - 0.008, y0 - 0.006, y0 - 0.002, tall - sh * 0.45, tall - sh * 0.12, glass)
    m.box(-0.08, 0.08, y0 - 0.006, y0 - 0.002, 0.02, 0.55, material("baggage door", "#5a6670", metal=0.4, rough=0.5))
    if ends:
        stone = material("fieldstone", "#8e8172", rough=0.95)
        course = material("fieldstone joints", "#6e6458", rough=0.95)
        for x in (x0, x1):
            m.box(x - 0.025, x + 0.025, y0 - 0.04, y1 + 0.06, 0.0, tall + 0.2, stone)
            z = 0.1
            while z < tall + 0.15:
                for y, d in ((y1 + 0.06, 1), (y0 - 0.04, -1)):
                    m.box(x - 0.026, x + 0.026, *sorted((y, y + d * 0.002)), z, z + 0.012, course)
                m.box(x - 0.027, x + 0.027, y0 - 0.04, y1 + 0.06, z, z + 0.012, course)
                z += 0.13
        houses.sign_text(m, "AIR TERMINAL", "y", y1 + 0.05, 1, 0.0, tall + 0.075, 0.07, material("fascia letters", "#2e3e4a", rough=0.5))


def _jet_bridge(m, x, y1):
    """A jet bridge out from the front at y1: a rotunda on its column, two telescoping tunnels, a drive column on
    wheels and the cab with its bellows, floor at 0.45 to meet an airliner's door."""
    steel = material("jet bridge", "#b4b8bc", metal=0.4, rough=0.5)
    rib = material("jet bridge ribs", "#9a9ea2", metal=0.4, rough=0.5)
    dark = material("bellows", "#2a2a2c", rough=0.8)
    glass = houses.glass()
    ry = y1 + 0.045
    m.upright(x, ry, 0.0, 0.44, 0.014, 0.014, steel, segments=10)
    m.upright(x, ry, 0.44, 0.84, 0.045, 0.045, steel, segments=20)
    m.upright(x, ry, 0.84, 0.88, 0.048, 0.04, rib, segments=20)
    for (a, b, hw, z0, z1) in ((ry + 0.03, ry + 0.2, 0.031, 0.47, 0.78), (ry + 0.18, ry + 0.34, 0.037, 0.45, 0.8)):
        m.box(x - hw, x + hw, a, b, z0, z1, steel, 0.003)
        for s in (-1, 1):
            harbour.ribs_y(m, x + s * hw, a + 0.006, b - 0.006, z0 + 0.02, z1 - 0.02, rib, gap=0.018, face=s)
            _bx(m, x + s * hw, x + s * (hw + 0.004), a + 0.01, b - 0.01, z0 + 0.15, z0 + 0.24, glass)
    for s in (-1, 1):
        m.rod((x + s * 0.03, ry + 0.3, 0.06), (x + s * 0.03, ry + 0.3, 0.45), 0.006, steel, segments=8)
        m.tube_x(x + s * 0.03 - 0.008, x + s * 0.03 + 0.008, ry + 0.3, 0.025, 0.025, dark, segments=14)
    m.box(x - 0.04, x + 0.04, ry + 0.29, ry + 0.31, 0.04, 0.07, steel)
    m.box(x - 0.046, x + 0.046, ry + 0.34, ry + 0.41, 0.44, 0.81, steel, 0.006)
    for s in (-1, 1):
        _bx(m, x + s * 0.046, x + s * 0.05, ry + 0.35, ry + 0.4, 0.6, 0.75, glass)
    m.box(x - 0.042, x + 0.042, ry + 0.41, ry + 0.43, 0.46, 0.79, dark)


def _terminal_hall(m, size, tall, x0, x1, y0, y1, ends):
    """A 1990s terminal hall: a curved roof sweeping out over a leaning glass front on branching steel columns, metal
    panels and a band of windows on the back, and on the bigger ones a jet bridge from each tile."""
    glass = material("hall glass", "#5a8296", rough=0.06, metal=0.3)
    mullion = material("hall mullions", "#d8dcde", metal=0.5, rough=0.35)
    roof = material("hall roof", "#c4c8ca", metal=0.6, rough=0.35)
    under = material("hall soffit", "#e4e2dc", rough=0.6)
    panel = material("hall panels", "#a8acae", metal=0.4, rough=0.5)
    seam = material("hall panel seams", "#8a8e90", metal=0.4, rough=0.5)
    lean = 0.05
    m.box(x0, x1, y0, y1, 0.0, tall, panel)
    # The glass front leaning out, mullions and transoms over it.
    bot = [(x0, y1, 0.0), (x1, y1, 0.0), (x1, y1 + 0.006, 0.0), (x0, y1 + 0.006, 0.0)]
    m.wedge(bot, [(x, y + lean, tall) for x, y, _ in bot], glass)
    m.wedge([(x0, y1 - 0.001, 0.0), (x1, y1 - 0.001, 0.0), (x1, y1 + 0.001, 0.0), (x0, y1 + 0.001, 0.0)],
            [(x0, y1 - 0.001, tall), (x1, y1 - 0.001, tall), (x1, y1 + lean, tall), (x0, y1 + lean, tall)], panel)
    n = max(1, round((x1 - x0) / 0.08))
    for k in range(n + 1):
        x = x0 + (x1 - x0) * k / n
        if ends and k in (0, n):
            continue
        m.rod((x, y1 + 0.009, 0.0), (x, y1 + 0.009 + lean, tall), 0.003, mullion, segments=5)
    z = 0.4
    while z < tall - 0.1:
        y = y1 + 0.009 + lean * z / tall
        m.rod((x0, y, z), (x1, y, z), 0.0022, mullion, segments=4)
        z += 0.4
    # The roof, curved in section and running across the tile.
    ya, yb = y0 - 0.05, y1 + lean + 0.12
    top = []
    for k in range(13):
        t = k / 12
        y = yb + (ya - yb) * t
        top.append((y, tall - 0.02 + 0.3 * math.sin(math.pi * (0.15 + 0.85 * t)) - 0.12 * t))
    bottom = [(y, z - 0.07) for y, z in reversed(top)]
    prof = [(y, z, 0.0, "roof") for y, z in top] + [(y, z, 0.0, "under") for y, z in bottom]
    hx = (x1 - x0) / 2 + (0.025 if ends else 0.0)
    m.shell(prof, hx, {"roof": roof, "under": under}, x=(x0 + x1) / 2, cols=(-1, -0.5, 0, 0.5, 1))
    for k in range(9 if ends else 10):
        x = x0 + 0.05 + k * 0.1 if ends else -0.45 + k * 0.1
        for (ya_, za), (yb_, zb) in zip(top, top[1:]):
            m.rod((x, ya_, za + 0.004), (x, yb_, zb + 0.004), 0.002, seam, segments=4)
    # Branching columns holding up the overhang.
    tree_x = (-0.25, 0.22) if ends else (-0.25, 0.25)
    yr = top[1][0]
    zr = top[1][1] - 0.07
    for x in tree_x:
        foot = (x, y1 + lean + 0.07, 0.0)
        knot = (x, y1 + lean + 0.07, zr * 0.55)
        m.rod(foot, knot, 0.007, mullion, segments=10)
        for dx in (-0.06, 0.0, 0.06):
            m.rod(knot, (x + dx, yr, zr), 0.004, mullion, segments=6)
    # The back: seams up the metal panels, a band of windows, doors.
    nb = round((x1 - x0) / 0.04)
    for k in range(nb + 1):
        x = x0 + (x1 - x0) * k / nb
        m.box(x - 0.002, x + 0.002, y0 - 0.004, y0, 0.0, tall - 0.08, seam)
    m.box(x0 + (0.02 if ends else 0.0), x1 - (0.02 if ends else 0.0), y0 - 0.006, y0 - 0.002, tall * 0.55, tall * 0.8, glass)
    m.box(-0.1, 0.1, y0 - 0.008, y0 - 0.002, 0.02, 0.5, glass)
    if ends:
        for x, d in ((x0, -1), (x1, 1)):
            nb = round((y1 - y0) / 0.04)
            for k in range(nb + 1):
                y = y0 + (y1 - y0) * k / nb
                _bx(m, x, x + d * 0.004, y - 0.002, y + 0.002, 0.0, tall - 0.08, seam)
    else:
        _jet_bridge(m, 0.12, y1 + lean)


# Towers by the year their airport went up (ChunkBaker.towerEra): 0 a 1940s wooden tower to 1960, 1 a Transport Canada tower.
TOWER_ERAS = 2


def tower(m, era=1):
    """The control tower in the look of [era]."""
    (_tower_wood, _tower_concrete)[era](m)


def _tower_wood(m):
    """A 1940s Department of Transport tower in wood: a cab on a four legged timber frame, braced in storeys, a stair
    climbing it in flights to a landing, the cab in white siding with a band of windows leaning out under a hip roof,
    a catwalk with a rail round it, an anemometer mast and a beacon; a radio hut at its foot and a windsock."""
    timber = material("tower timber", "#8a7a62", rough=0.85)
    brace = material("tower braces", "#76684f", rough=0.85)
    siding = material("tower siding", "#ece8dc", rough=0.75)
    lines = material("tower siding lines", "#c8c3b2", rough=0.8)
    trim = material("tower trim", "#2e5a3a", rough=0.6)
    roof = material("tower roof", "#3e3e40", rough=0.8)
    glass = material("tower glass", "#2e4a5e", rough=0.06, metal=0.3)
    frame = material("tower frames", "#2e3634", metal=0.3, rough=0.5)
    rail = material("tower rail", "#ece8dc", rough=0.6)
    cx, cy = 0.05, -0.05
    foot, head, zc = 0.17, 0.11, 2.9
    legs = [(sx, sy) for sx in (-1, 1) for sy in (-1, 1)]
    m.box(cx - foot - 0.04, cx + foot + 0.04, cy - foot - 0.04, cy + foot + 0.04, 0.0, 0.03, material("tower footing", "#a8a49c", rough=0.9))
    for sx, sy in legs:
        m.rod((cx + sx * foot, cy + sy * foot, 0.03), (cx + sx * head, cy + sy * head, zc), 0.011, timber, segments=6)

    def at(sx, sy, z):
        f = z / zc
        r = foot + (head - foot) * f
        return (cx + sx * r, cy + sy * r, z)
    storeys = (0.03, 0.75, 1.45, 2.15, zc)
    sides = [((-1, -1), (1, -1)), ((1, -1), (1, 1)), ((1, 1), (-1, 1)), ((-1, 1), (-1, -1))]
    for za, zb in zip(storeys, storeys[1:]):
        for a, b in sides:
            m.rod(at(*a, zb), at(*b, zb), 0.006, timber, segments=5)
            m.rod(at(*a, za), at(*b, zb), 0.004, brace, segments=4)
            m.rod(at(*b, za), at(*a, zb), 0.004, brace, segments=4)
    # The stair: flights up the +y face from storey to storey with a landing at each, a handrail on the outside.
    for k, (za, zb) in enumerate(zip(storeys, storeys[1:])):
        sx = -1 if k % 2 == 0 else 1
        ra = at(sx, 1, za)
        rb = at(-sx, 1, zb)
        a = (ra[0] * 0.9 + cx * 0.1, ra[1] + 0.03, za)
        b = (rb[0] * 0.9 + cx * 0.1, rb[1] + 0.03, zb)
        m.rod(a, b, 0.012, timber, segments=4)
        m.rod((a[0], a[1] + 0.012, a[2] + 0.2), (b[0], b[1] + 0.012, b[2] + 0.2), 0.0025, rail, segments=4)
        m.box(b[0] - 0.04, b[0] + 0.04, b[1] - 0.02, b[1] + 0.03, zb - 0.02, zb, timber)
    # The cab floor and catwalk, the cab walls, its glass leaning out, the hip roof.
    m.box(cx - 0.2, cx + 0.2, cy - 0.2, cy + 0.2, zc, zc + 0.05, timber)
    pts = [(cx - 0.195, cy - 0.195), (cx + 0.195, cy - 0.195), (cx + 0.195, cy + 0.195), (cx - 0.195, cy + 0.195)]
    for (ax, ay), (bx, by) in zip(pts, pts[1:] + pts[:1]):
        m.rod((ax, ay, zc + 0.23), (bx, by, zc + 0.23), 0.0025, rail, segments=4)
        m.rod((ax, ay, zc + 0.14), (bx, by, zc + 0.14), 0.0018, rail, segments=4)
        for f in (0.0, 0.33, 0.67):
            m.rod((ax + (bx - ax) * f, ay + (by - ay) * f, zc + 0.05), (ax + (bx - ax) * f, ay + (by - ay) * f, zc + 0.23), 0.002, rail, segments=4)
    c = 0.13
    m.box(cx - c, cx + c, cy - c, cy + c, zc + 0.05, zc + 0.36, siding)
    for z in (zc + 0.11, zc + 0.18, zc + 0.25, zc + 0.32):
        m.box(cx - c - 0.003, cx + c + 0.003, cy - c - 0.003, cy + c + 0.003, z, z + 0.008, lines)
    m.box(cx - c - 0.006, cx + c + 0.006, cy - c - 0.006, cy + c + 0.006, zc + 0.36, zc + 0.39, trim)
    _frustum(m, cx, cy, zc + 0.39, zc + 0.8, c * math.sqrt(2), (c + 0.025) * math.sqrt(2), 4, glass, rot=math.pi / 4)
    for px, py in _poly(4, 1.0, math.pi / 4):
        m.rod((cx + px * c, cy + py * c, zc + 0.39), (cx + px * (c + 0.025), cy + py * (c + 0.025), zc + 0.8), 0.005, trim, segments=5)
    for sx, sy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        f0, f1 = c + 0.003, c + 0.028
        m.rod((cx + sx * f0, cy + sy * f0, zc + 0.39), (cx + sx * f1, cy + sy * f1, zc + 0.8), 0.0025, frame, segments=4)
    houses.hip(m, cx - c - 0.035, cx + c + 0.035, cy - c - 0.035, cy + c + 0.035, zc + 0.8, zc + 1.0, roof, over=0.01, thick=0.035)
    m.rod((cx, cy, zc + 1.0), (cx, cy, zc + 1.45), 0.004, frame)
    for a in (0.0, 2.1, 4.2):
        tx, ty = cx + 0.04 * math.cos(a), cy + 0.04 * math.sin(a)
        m.rod((cx, cy, zc + 1.4), (tx, ty, zc + 1.4), 0.0015, frame, segments=4)
        m.lathe(tx, ty, zc + 1.39, [(0.0, 0), (0.01, 0.005), (0.0, 0.02)], frame, segments=8)
    m.upright(cx, cy, zc + 1.45, zc + 1.52, 0.012, 0.012, material("beacon", "#3a9a5a", rough=0.2), segments=10)
    # The radio hut at its foot: clapboard, a gable roof, a door and window, an aerial wire to the cab.
    hx0, hx1, hy0, hy1 = 0.2, 0.44, 0.12, 0.36
    m.box(hx0, hx1, hy0, hy1, 0.0, 0.55, siding)
    for k in range(1, 5):
        m.box(hx0 - 0.003, hx1 + 0.003, hy0 - 0.003, hy1 + 0.003, k * 0.11, k * 0.11 + 0.01, lines)
    houses.gable(m, hx0, hx1, hy0, hy1, 0.55, 0.8, roof, siding, along_x=True, over=0.025, thick=0.04)
    white = material("tower window trim", "#f2f0e8", rough=0.6)
    houses.pane(m, "y", hy1, 1, 0.26, 0.22, 0.45, 0.06, houses.glass(), white, trim)
    m.box(0.36, 0.4, hy1, hy1 + 0.004, 0.0, 0.42, trim)
    m.rod((0.4, 0.3, 0.8), (0.4, 0.3, 1.6), 0.003, frame)
    m.rod((0.4, 0.3, 1.6), (cx + c, cy + c, zc + 0.7), 0.0012, frame, segments=4)
    harbour.windsock(m, -0.38, 0.32, 0.0, 0.9, toward=(0.7, -0.7))


def _tower_concrete(m):
    """A Transport Canada control tower: a two storey base building, an eight sided concrete shaft with a stair window
    strip, an equipment floor, a catwalk with a rail round the cab, the cab's glass leaning out between mullions, and a
    roof with a mast, whips, a dish and the beacon."""
    concrete = material("tower", "#cdc9bf", rough=0.8)
    joints = material("tower joints", "#aaa69c", rough=0.85)
    glass = material("tower glass", "#2e4a5e", rough=0.06, metal=0.3)
    frame = material("tower frames", "#3a3e42", metal=0.4, rough=0.5)
    roof = material("tower roof", "#3a3a3c", rough=0.6)
    rail = material("tower rail", "#d8d8d4", metal=0.4, rough=0.45)
    wglass = houses.glass()
    # The base building: precast panels, ribbon windows on both floors, a flat roof with coping, a canopy at the door.
    x0, x1, y0, y1 = -0.32, 0.32, -0.3, 0.28
    m.box(x0, x1, y0, y1, 0.0, 0.95, concrete)
    for z in (0.18, 0.62):
        for y, d in ((y1, 1), (y0, -1)):
            m.box(x0 + 0.03, x1 - 0.03, *sorted((y, y + d * 0.004)), z, z + 0.2, wglass)
            for k in range(13):
                x = x0 + 0.03 + k * (x1 - x0 - 0.06) / 12
                m.box(x - 0.002, x + 0.002, *sorted((y, y + d * 0.007)), z, z + 0.2, frame)
        for x, d in ((x1, 1), (x0, -1)):
            m.box(*sorted((x, x + d * 0.004)), y0 + 0.04, y1 - 0.04, z, z + 0.2, wglass)
    m.box(x0 - 0.01, x1 + 0.01, y0 - 0.01, y1 + 0.01, 0.95, 1.05, joints)
    m.box(x0, x1, y0, y1, 1.0, 1.03, roof)
    m.box(0.1, 0.26, y1, y1 + 0.12, 0.42, 0.46, joints, 0.004)
    m.box(0.15, 0.21, y1, y1 + 0.004, 0.02, 0.38, material("tower door", "#2a3236", metal=0.3, rough=0.4))
    m.box(-0.26, -0.1, -0.24, -0.08, 1.03, 1.25, joints)
    # The shaft, a stair window strip up one face, panel joints.
    sx, sy = 0.06, -0.02
    _frustum(m, sx, sy, 1.03, 3.85, 0.12, 0.105, 8, concrete)
    for k in range(1, 9):
        z = 1.03 + k * 0.32
        _frustum(m, sx, sy, z, z + 0.015, 0.121 - 0.0017 * k, 0.121 - 0.0017 * k, 8, joints)
    yf = sy + 0.12 * math.cos(math.pi / 8) - 0.001
    for k in range(8):
        z = 1.2 + k * 0.32
        m.box(sx - 0.018, sx + 0.018, yf, yf + 0.006, z, z + 0.14, wglass)
    # The equipment floor and the catwalk round the cab.
    _frustum(m, sx, sy, 3.85, 4.15, 0.15, 0.17, 8, concrete)
    for k in range(8):
        a = math.pi / 8 + k * math.pi / 4 + math.pi / 8
        px, py = sx + 0.158 * math.cos(a), sy + 0.158 * math.sin(a)
        m.box(px - 0.012, px + 0.012, py - 0.012, py + 0.012, 3.95, 4.05, frame)
    _frustum(m, sx, sy, 4.15, 4.2, 0.25, 0.25, 8, joints)
    pts = _poly(8, 0.245, math.pi / 8, sx, sy)
    for (ax, ay), (bx, by) in zip(pts, pts[1:] + pts[:1]):
        m.rod((ax, ay, 4.38), (bx, by, 4.38), 0.002, rail, segments=4)
        m.rod((ax, ay, 4.3), (bx, by, 4.3), 0.0015, rail, segments=4)
        for f in (0.0, 0.5):
            px, py = ax + (bx - ax) * f, ay + (by - ay) * f
            m.rod((px, py, 4.2), (px, py, 4.38), 0.0016, rail, segments=4)
    # The cab: a sill, glass leaning out, mullions at the corners, the roof standing out over it.
    _frustum(m, sx, sy, 4.2, 4.28, 0.18, 0.182, 8, concrete)
    _frustum(m, sx, sy, 4.28, 4.8, 0.182, 0.22, 8, glass)
    for px, py in _poly(8, 1.0, math.pi / 8):
        m.rod((sx + px * 0.184, sy + py * 0.184, 4.28), (sx + px * 0.222, sy + py * 0.222, 4.8), 0.004, frame, segments=5)
    _frustum(m, sx, sy, 4.8, 4.86, 0.235, 0.235, 8, roof)
    _frustum(m, sx, sy, 4.86, 4.93, 0.2, 0.17, 8, roof)
    # The mast with cross arms and its beacon, two whips and a dish.
    m.rod((sx, sy, 4.93), (sx, sy, 5.6), 0.005, frame)
    for z in (5.15, 5.4):
        m.rod((sx - 0.04, sy, z), (sx + 0.04, sy, z), 0.002, frame, segments=4)
    m.upright(sx, sy, 5.6, 5.66, 0.008, 0.008, material("beacon red", "#d82a20", rough=0.2), segments=8)
    for a in (0.7, 2.6):
        px, py = sx + 0.15 * math.cos(a), sy + 0.15 * math.sin(a)
        m.rod((px, py, 4.93), (px, py, 5.3), 0.0018, frame, segments=4)
    m.rod((sx - 0.12, sy - 0.08, 4.93), (sx - 0.12, sy - 0.08, 5.05), 0.004, frame)
    m.lathe(sx - 0.12, sy - 0.08, 5.05, [(0.004, 0.0), (0.03, 0.05), (0.035, 0.07), (0.0, 0.065)], rail, segments=14, lean=0.02)


# Locks: 0 a Rideau lock in stone with timber gates and a stone keeper's house, 1 a Seaway lock in concrete.
LOCK_ERAS = 2
# A lock's place in a flight of lock tiles (ChunkBaker.lockPlace): 0 one on its own, 1 the low end, 2 a middle tile,
# 3 the high end. Every tile lifts a step at its middle gates; only the flight's ends have end gates, the low end the house.
LOCK_PLACES = 4
LOCK_GATES = {0: (-0.46, 0.0, 0.42), 1: (-0.46, 0.0), 2: (0.0,), 3: (0.0, 0.42)}


def lock(m, era, place=0):
    """A lock with its upper end to +y (turned to the edge it rises to by place()): walls either side of the chamber,
    mitre gates halfway and at the ends of its flight, the water low in the chamber's lower half and high in its upper
    half, gate machinery, bollards, and on its own or at the low end a keeper's house or control house on the wall."""
    house = place in (0, 1)
    water = material("lock water", "#3a5a6e", rough=0.15)
    slime = material("lock wet band", "#3e4434", rough=0.9)
    stone = era == 0
    wall = material("lock stone", "#b5ad9c", rough=0.9) if stone else material("lock wall", "#a8a49c", rough=0.85)
    joint = material("lock joints", "#8e8676", rough=0.95) if stone else material("lock wall joints", "#8a8680", rough=0.9)
    cope = material("lock coping", "#c8c2b2", rough=0.85) if stone else material("lock coping", "#b8b4ac", rough=0.8)
    top = 1.35
    for s in (-1, 1):
        _bx(m, s * 0.35, s * 0.49, -0.5, 0.5, 0.0, top - 0.04, wall)
        _bx(m, s * 0.345, s * 0.495, -0.5, 0.5, top - 0.05, top, cope)
        if stone:
            # Coursed limestone on the chamber face, joints broken course by course.
            for c, z in enumerate([0.1 + 0.11 * k for k in range(11)]):
                _bx(m, s * 0.348, s * 0.352, -0.5, 0.5, z, z + 0.01, joint)
                _bx(m, s * 0.488, s * 0.492, -0.5, 0.5, z, z + 0.01, joint)
                for j in range(10):
                    y = -0.5 + (j + 0.5 * (c % 2)) * 0.1
                    if -0.5 < y < 0.5:
                        _bx(m, s * 0.348, s * 0.352, y - 0.004, y + 0.004, z + 0.01, z + 0.11, joint)
            for j in range(9):
                y = -0.45 + j * 0.11
                _bx(m, s * 0.344, s * 0.496, y - 0.003, y + 0.003, top - 0.05, top + 0.002, joint)
        else:
            for j in range(9):
                y = -0.5 + (j + 0.5) * 0.111
                _bx(m, s * 0.347, s * 0.351, y - 0.003, y + 0.003, 0.0, top - 0.05, joint)
            _bx(m, s * 0.35, s * 0.37, -0.5, 0.5, top, top + 0.004, material("lock edge line", "#e0c030", rough=0.6))
        # Wet bands at both water levels.
        _bx(m, s * 0.348, s * 0.353, -0.5, 0.0, 0.2, 0.36, slime)
        _bx(m, s * 0.348, s * 0.353, 0.0, 0.5, 1.05, 1.13, slime)
    m.box(-0.35, 0.35, -0.5, 0.0, 0.0, 0.2, water)
    m.box(-0.35, 0.35, 0.0, 0.5, 0.0, 1.05, water)
    timber = material("gate timber", "#4a3a2a", rough=0.85)
    wale = material("gate wales", "#3a2e22", rough=0.85)
    steel = material("gate steel", "#4a5258", metal=0.4, rough=0.5)
    yellow = material("gate rail", "#e0b828", metal=0.3, rough=0.5)
    iron = material("crab winch", "#2e3032", metal=0.5, rough=0.5)
    for g in LOCK_GATES[place]:
        gz = top - 0.02
        for s in (-1, 1):
            hinge, mitre = (s * 0.35, g), (s * 0.004, g + 0.07)
            dx, dy = mitre[0] - hinge[0], mitre[1] - hinge[1]
            ln = math.hypot(dx, dy)
            nx, ny = dy / ln, -dx / ln
            if ny > 0:
                nx, ny = -nx, -ny
            if stone:
                _plank(m, hinge, mitre, 0.024, 0.0, gz, timber)
                for z in (0.25, 0.55, 0.85, 1.15):
                    o = 0.014
                    _plank(m, (hinge[0] + nx * o, hinge[1] + ny * o), (mitre[0] + nx * o, mitre[1] + ny * o), 0.006, z, z + 0.05, wale)
                # The balance beam on top, out past the hinge over the wall.
                tail = (hinge[0] - dx / ln * 0.13, hinge[1] - dy / ln * 0.13)
                _plank(m, tail, mitre, 0.026, gz, gz + 0.07, timber)
                harbour.capstan(m, s * 0.43, g - 0.1 if g < 0.4 else g - 0.12, top)
                m.rod((s * 0.43, g - 0.1, top + 0.12), ((hinge[0] + mitre[0]) / 2, (hinge[1] + mitre[1]) / 2, gz + 0.03), 0.0015, iron, segments=4)
            else:
                _plank(m, hinge, mitre, 0.034, 0.0, gz, steel)
                for z in (0.2, 0.45, 0.7, 0.95, 1.2):
                    o = 0.019
                    _plank(m, (hinge[0] + nx * o, hinge[1] + ny * o), (mitre[0] + nx * o, mitre[1] + ny * o), 0.006, z, z + 0.04, steel)
                for f in (0.05, 0.5, 0.95):
                    px, py = hinge[0] + dx * f, hinge[1] + dy * f
                    m.rod((px, py, gz), (px, py, gz + 0.22), 0.0018, yellow, segments=4)
                m.rod((hinge[0], hinge[1], gz + 0.22), (mitre[0], mitre[1], gz + 0.22), 0.0022, yellow, segments=4)
                # The machinery house on the wall and its strut to the leaf.
                hy0, hy1 = (g - 0.13, g - 0.05) if g < 0.4 else (g - 0.16, g - 0.08)
                _bx(m, s * 0.37, s * 0.48, hy0, hy1, top, top + 0.36, wall)
                _bx(m, s * 0.365, s * 0.485, hy0 - 0.005, hy1 + 0.005, top + 0.36, top + 0.4, cope)
                _bx(m, s * 0.365, s * 0.369, hy0 + 0.02, hy1 - 0.02, top + 0.02, top + 0.26, material("machinery door", "#3e5a6a", rough=0.6))
                m.rod((s * 0.37, (hy0 + hy1) / 2, top + 0.1), (hinge[0] + dx * 0.3, hinge[1] + dy * 0.3, gz - 0.02), 0.006, iron, segments=6)
    if not house:
        # Bollards along both walls, a lamp and ladders; the Seaway lock's fence runs the whole tile.
        ys = ((-0.3, 0.3) if place == 2 else (-0.3, 0.18)) if stone else ((-0.36, 0.12, 0.36) if place == 2 else (-0.36, 0.12))
        for y in ys:
            for s in (-1, 1):
                harbour.bollard(m, s * 0.4, y, top, s=0.8 if stone else 1.0)
        harbour.lamp(m, -0.44, 0.2, top, top + (0.65 if stone else 0.8), toward=(1.0, 0.0))
        if not stone:
            harbour.ladder(m, -0.352, -0.25, 0.15, top, face_x=True, grab=0.12)
            harbour.ladder(m, 0.352, 0.25, 1.0, top, face_x=True, grab=0.12)
            post = material("lock fence", "#c8c8c4", metal=0.4, rough=0.5)
            for s in (-1, 1):
                for k in range(6):
                    y = -0.48 + k * 0.96 / 5
                    m.rod((s * 0.485, y, top), (s * 0.485, y, top + 0.25), 0.002, post, segments=4)
                m.rod((s * 0.485, -0.5, top + 0.25), (s * 0.485, 0.5, top + 0.25), 0.0018, post, segments=4)
                m.rod((s * 0.485, -0.5, top + 0.14), (s * 0.485, 0.5, top + 0.14), 0.0012, post, segments=4)
    elif stone:
        # The keeper's house on the wall by the lower gates: stone walls, a hip roof, chimney, door and windows.
        hx0, hx1, hy0, hy1 = 0.355, 0.495, -0.36, -0.13
        hz = top + 0.75
        house = material("keeper's house stone", "#a89e8a", rough=0.9)
        m.box(hx0, hx1, hy0, hy1, top, hz, house)
        for z in (top + 0.12, top + 0.27, top + 0.42, top + 0.57):
            m.box(hx0 - 0.002, hx1 + 0.002, hy0 - 0.002, hy1 + 0.002, z, z + 0.008, joint)
        houses.hip(m, hx0, hx1, hy0, hy1, hz, hz + 0.42, material("keeper's roof", "#5a4a3e", rough=0.8), over=0.02, thick=0.05)
        m.box(0.44, 0.47, -0.2, -0.17, hz + 0.1, hz + 0.6, house)
        hwhite = material("keeper's trim", "#e8e4d8", rough=0.6)
        houses.pane(m, "x", hx0, -1, -0.29, top + 0.25, top + 0.55, 0.05, houses.glass(), hwhite, joint)
        m.box(hx0 - 0.006, hx0, -0.2, -0.16, top, top + 0.5, material("keeper's door", "#3e5a44", rough=0.6))
        houses.pane(m, "y", hy0, -1, 0.425, top + 0.25, top + 0.55, 0.05, houses.glass(), hwhite, joint)
        for y in (-0.06, 0.26):
            for s in (-1, 1):
                harbour.bollard(m, s * 0.4, y, top, s=0.8)
        harbour.lamp(m, -0.44, 0.2, top, top + 0.65, toward=(1.0, 0.0))
    else:
        # The control house on the wall: glazed all round under a flat roof standing out, a lamp and bollards.
        hx0, hx1, hy0, hy1 = 0.36, 0.49, -0.34, -0.16
        m.box(hx0, hx1, hy0, hy1, top, top + 0.2, wall)
        glass = houses.glass()
        m.box(hx0 + 0.004, hx1 - 0.004, hy0 + 0.004, hy1 - 0.004, top + 0.2, top + 0.5, glass)
        for x, y in ((hx0, hy0), (hx1, hy0), (hx0, hy1), (hx1, hy1)):
            m.box(x - 0.005, x + 0.005, y - 0.005, y + 0.005, top + 0.2, top + 0.5, wall)
        m.box(hx0 - 0.025, hx1 + 0.005, hy0 - 0.025, hy1 + 0.025, top + 0.5, top + 0.56, cope)
        m.rod((0.45, -0.2, top + 0.56), (0.45, -0.2, top + 0.85), 0.002, iron)
        for y in (-0.06, 0.12, 0.3):
            for s in (-1, 1):
                harbour.bollard(m, s * 0.4, y, top)
        harbour.lamp(m, -0.44, 0.22, top, top + 0.8, toward=(1.0, 0.0))
        harbour.ladder(m, -0.352, -0.25, 0.15, top, face_x=True, grab=0.12)
        harbour.ladder(m, 0.352, 0.25, 1.0, top, face_x=True, grab=0.12)
        # A chain fence along the outer edges of the walls.
        post = material("lock fence", "#c8c8c4", metal=0.4, rough=0.5)
        for s in (-1, 1):
            fy0 = -0.49 if s < 0 else -0.1
            for k in range(6):
                y = fy0 + 0.01 + k * (0.48 - fy0) / 5
                m.rod((s * 0.485, y, top), (s * 0.485, y, top + 0.25), 0.002, post, segments=4)
            m.rod((s * 0.485, fy0, top + 0.25), (s * 0.485, 0.49, top + 0.25), 0.0018, post, segments=4)
            m.rod((s * 0.485, fy0, top + 0.14), (s * 0.485, 0.49, top + 0.14), 0.0012, post, segments=4)
