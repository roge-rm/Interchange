"""Small pieces for docks, wharves and seaplane bases: planked decks, piles with bracing, fenders, bollards, cleats,
ladders, drums, crates, ribbed siding, roll-up doors, lattice booms, windsocks and a Cape Islander fishing boat.

All in a model's own terms: x across, y along, z in steps.
"""
import math
import random

import ships
from parts import material


def timber(name="wharf timber", colour="#6e5a44"):
    return material(name, colour, rough=0.9)


def planks(m, x0, x1, y0, y1, z0, z1, along_x=False, width=0.035, colours=("#8a7458", "#7e6a50", "#94805f"), seed=1):
    """A deck of planks over x0..x1 by y0..y1, z0 to z1, laid along x or y, a hair apart, in a few shades."""
    rnd = random.Random(seed)
    mats = [material(f"plank {c}", c, rough=0.9) for c in colours]
    a0, a1 = (y0, y1) if along_x else (x0, x1)
    n = max(1, round((a1 - a0) / width))
    w = (a1 - a0) / n
    for k in range(n):
        s0, s1 = a0 + k * w + 0.0015, a0 + (k + 1) * w - 0.0015
        mat = mats[rnd.randrange(len(mats))]
        if along_x:
            m.box(x0, x1, s0, s1, z0, z1, mat)
        else:
            m.box(s0, s1, y0, y1, z0, z1, mat)
    # A dark bed under the gaps.
    m.box(x0 + 0.002, x1 - 0.002, y0 + 0.002, y1 - 0.002, z0 - 0.02, z0 + (z1 - z0) * 0.6, material("plank gap", "#2e2620", rough=0.95))


def piles(m, xs, ys, z0, z1, r=0.02, brace=True, across_x=True):
    """Round piles at every x in xs and y in ys from z0 up to z1, with caps along each bent and X bracing in it."""
    wood = material("piles", "#4a3a2c", rough=0.95)
    for x in xs:
        for y in ys:
            m.upright(x, y, z0, z1, r * 1.05, r, wood, segments=10)
    if not brace:
        return
    zb0, zb1 = z0 + (z1 - z0) * 0.35, z1 - (z1 - z0) * 0.08
    bents = ys if across_x else xs
    span = xs if across_x else ys
    for b in bents:
        for a0, a1 in zip(span, span[1:]):
            if across_x:
                m.rod((a0, b, zb0), (a1, b, zb1), r * 0.4, wood, segments=6)
                m.rod((a0, b, zb1), (a1, b, zb0), r * 0.4, wood, segments=6)
            else:
                m.rod((b, a0, zb0), (b, a1, zb1), r * 0.4, wood, segments=6)
                m.rod((b, a0, zb1), (b, a1, zb0), r * 0.4, wood, segments=6)


def bollard(m, x, y, z, s=1.0):
    """A cast iron bollard: a short post that flares to a cap."""
    iron = material("bollard", "#2a2a2c", metal=0.5, rough=0.5)
    m.lathe(x, y, z, [(0.02 * s, 0), (0.016 * s, 0.03), (0.014 * s, 0.1 * s), (0.022 * s, 0.11 * s), (0.022 * s, 0.14 * s), (0.0, 0.15 * s)], iron, segments=14)


def cleat(m, x, y, z, along_x=True):
    """A deck cleat: a low bar with horns either end."""
    iron = material("cleat", "#2a2a2c", metal=0.5, rough=0.5)
    if along_x:
        m.box(x - 0.006, x + 0.006, y - 0.004, y + 0.004, z, z + 0.04, iron)
        m.box(x - 0.018, x + 0.018, y - 0.005, y + 0.005, z + 0.04, z + 0.06, iron, 0.002)
    else:
        m.box(x - 0.004, x + 0.004, y - 0.006, y + 0.006, z, z + 0.04, iron)
        m.box(x - 0.005, x + 0.005, y - 0.018, y + 0.018, z + 0.04, z + 0.06, iron, 0.002)


def capstan(m, x, y, z):
    iron = material("capstan", "#3a3e42", metal=0.5, rough=0.5)
    m.lathe(x, y, z, [(0.03, 0), (0.03, 0.03), (0.018, 0.05), (0.016, 0.12), (0.026, 0.15), (0.026, 0.18), (0.0, 0.19)], iron, segments=16)


def ladder(m, x, y, z0, z1, face_x=False, grab=0.15):
    """A steel ladder down a quay face from z1 to z0, with grab rails standing grab steps above the top."""
    steel = material("ladder", "#c8b030", metal=0.4, rough=0.5)
    w = 0.016
    for s in (-1, 1):
        a = (x, y + s * w) if face_x else (x + s * w, y)
        m.rod((a[0], a[1], z0), (a[0], a[1], z1 + grab), 0.003, steel, segments=6)
    n = max(2, int((z1 - z0) / 0.07))
    for k in range(n + 1):
        z = z0 + (z1 - z0) * k / n
        a = (x, y - w) if face_x else (x - w, y)
        b = (x, y + w) if face_x else (x + w, y)
        m.rod((a[0], a[1], z), (b[0], b[1], z), 0.0025, steel, segments=6)


def drum(m, x, y, z, colour="#2a5a3a", r=0.018, h=0.4, name="drum"):
    """A steel drum standing at x, y on z, with two rolling hoops and a lid."""
    mat = material(name, colour, metal=0.3, rough=0.5)
    m.upright(x, y, z, z + h, r, r, mat, segments=14)
    for f in (0.3, 0.7):
        m.upright(x, y, z + h * f - 0.012, z + h * f + 0.012, r * 1.08, r * 1.08, mat, segments=14)
    m.upright(x, y, z + h, z + h + 0.012, r * 1.04, r * 0.92, material("drum lid", "#3a3a3c", metal=0.4, rough=0.5), segments=14)


def crate(m, x0, x1, y0, y1, z0, z1, colour="#a8885a"):
    """A slatted crate: a box with darker battens round its edges and a band across."""
    wood = material(f"crate {colour}", colour, rough=0.85)
    dark = material("crate battens", "#6a5236", rough=0.9)
    m.box(x0, x1, y0, y1, z0, z1, wood, 0.002)
    e = 0.004
    h = (z1 - z0) * 0.12
    for zz in (z0, z1 - h - 0.012):
        m.box(x0 - e, x1 + e, y0 - e, y1 + e, zz, zz + h, dark)
    m.box(x0 - e, x1 + e, (y0 + y1) / 2 - 0.004, (y0 + y1) / 2 + 0.004, z0, z1 - 0.012, dark)


def pallet(m, x, y, z, size=0.09, load="sacks"):
    """A pallet with sacks or cartons on it, wrapped."""
    wood = material("pallet", "#a08460", rough=0.9)
    h = 0.05
    for d in (-1, 0, 1):
        m.box(x - size / 2, x + size / 2, y + d * size * 0.4 - 0.008, y + d * size * 0.4 + 0.008, z, z + h * 0.6, wood)
    m.box(x - size / 2, x + size / 2, y - size / 2, y + size / 2, z + h * 0.6, z + h, wood)
    if load == "sacks":
        sack = material("sacks", "#c8b890", rough=1.0)
        for i in range(2):
            for j in range(2):
                sx, sy = x + (i - 0.5) * size * 0.48, y + (j - 0.5) * size * 0.48
                m.box(sx - size * 0.23, sx + size * 0.23, sy - size * 0.23, sy + size * 0.23, z + h, z + h + 0.07, sack, 0.008)
                m.box(sx - size * 0.21, sx + size * 0.21, sy - size * 0.21, sy + size * 0.21, z + h + 0.07, z + h + 0.14, sack, 0.008)
    else:
        box = material("cartons", "#b8a07a", rough=0.9)
        wrap = material("shrink wrap", "#d8dcdc", rough=0.3)
        m.box(x - size * 0.46, x + size * 0.46, y - size * 0.46, y + size * 0.46, z + h, z + h + 0.2, box, 0.003)
        m.box(x - size * 0.47, x + size * 0.47, y - size * 0.47, y + size * 0.47, z + h + 0.17, z + h + 0.21, wrap, 0.003)


def ribs(m, x0, x1, y, z0, z1, mat, gap=0.025, face=-1):
    """Ribbed metal siding on a wall in the x-z plane at y, standing out to face (-1 or 1)."""
    n = max(1, round((x1 - x0) / gap))
    for k in range(n + 1):
        x = x0 + (x1 - x0) * k / n
        m.box(x - 0.003, x + 0.003, y + face * 0.004 - 0.003, y + face * 0.004 + 0.003, z0, z1, mat)


def ribs_y(m, x, y0, y1, z0, z1, mat, gap=0.025, face=1):
    """Ribbed siding on a wall in the y-z plane at x."""
    n = max(1, round((y1 - y0) / gap))
    for k in range(n + 1):
        y = y0 + (y1 - y0) * k / n
        m.box(x + face * 0.004 - 0.003, x + face * 0.004 + 0.003, y - 0.003, y + 0.003, z0, z1, mat)


def rollup_door(m, x0, x1, y, z0, z1, face=-1, colour="#5a6670"):
    """A roll-up door in a wall at y: a frame, slats and the drum housing over it."""
    door = material(f"roll-up door {colour}", colour, metal=0.4, rough=0.5)
    frame = material("door frame", "#3a3e42", metal=0.3, rough=0.6)
    dy = face * 0.004
    m.box(x0, x1, y + dy - 0.002, y + dy + 0.002, z0, z1, door)
    n = max(3, int((z1 - z0) / 0.07))
    for k in range(1, n):
        z = z0 + (z1 - z0) * k / n
        m.box(x0, x1, y + 2 * dy - 0.0015, y + 2 * dy + 0.0015, z - 0.006, z + 0.006, frame)
    for x in (x0, x1):
        m.box(x - 0.007, x + 0.007, y + 2 * dy - 0.003, y + 2 * dy + 0.003, z0, z1 + 0.05, frame)
    m.box(x0 - 0.01, x1 + 0.01, y + 3 * dy - 0.006, y + 3 * dy + 0.006, z1, z1 + 0.07, frame, 0.002)


def lamp(m, x, y, z0, z1, reach=0.04, toward=(0.0, -1.0)):
    """A lamp standard from z0 to z1 with an arm and a shaded head."""
    pole = material("lamp pole", "#5a5e62", metal=0.5, rough=0.5)
    m.upright(x, y, z0, z1, 0.006, 0.004, pole, segments=8)
    hx, hy = x + toward[0] * reach, y + toward[1] * reach
    m.rod((x, y, z1 - 0.02), (hx, hy, z1), 0.003, pole)
    m.lathe(hx, hy, z1 - 0.06, [(0.0, 0), (0.016, 0.02), (0.01, 0.06), (0.0, 0.065)], pole, segments=10)
    m.upright(hx, hy, z1 - 0.07, z1 - 0.055, 0.008, 0.012, material("lamp glass", "#f0e0a0", rough=0.2), segments=10)


def lattice(m, a, b, wa, wb, mat, n=8, r=0.004, axis=(1.0, 0.0)):
    """A lattice boom or girder from a to b (x, y, z), wa across at a and wb at b, along axis (x, y) for its width.
    Two chords each side, top and bottom, laced in a zigzag on its sides."""
    ax, ay = axis
    za, zb = wa * 0.5, wb * 0.5
    chords = {}
    for s in (-1, 1):
        for t in (-1, 1):
            p = (a[0] + s * ax * wa / 2, a[1] + s * ay * wa / 2, a[2] + t * za)
            q = (b[0] + s * ax * wb / 2, b[1] + s * ay * wb / 2, b[2] + t * zb)
            m.rod(p, q, r, mat, segments=6)
            chords[(s, t)] = (p, q)

    def at(key, f):
        p, q = chords[key]
        return tuple(p[i] + (q[i] - p[i]) * f for i in range(3))

    for k in range(n):
        f0, f1 = k / n, (k + 1) / n
        for s in (-1, 1):
            m.rod(at((s, -1), f0), at((s, 1), f1), r * 0.6, mat, segments=5)
        m.rod(at((-1, 1), f0), at((1, 1), f1), r * 0.6, mat, segments=5)


def windsock(m, x, y, z0, z1, toward=(1.0, 0.0)):
    """A windsock on a pole: a tapered sock in orange and white bands, streaming toward (x, y)."""
    pole = material("sock pole", "#d8d8d4", rough=0.5)
    m.upright(x, y, z0, z1, 0.006, 0.004, pole, segments=8)
    m.rod((x - 0.012, y, z1 - 0.01), (x + 0.012, y, z1 - 0.01), 0.004, pole)
    orange = material("windsock", "#e86a1a", rough=0.6)
    white = material("windsock white", "#ecebe6", rough=0.6)
    n = 5
    for k in range(n):
        f0, f1 = k / n, (k + 1) / n
        r = 0.022 * (1 - 0.5 * f0)
        a = (x + toward[0] * 0.15 * f0, y + toward[1] * 0.15 * f0, z1 - 0.06 - 0.25 * f0)
        b = (x + toward[0] * 0.15 * f1, y + toward[1] * 0.15 * f1, z1 - 0.06 - 0.25 * f1)
        m.rod(a, b, r, orange if k % 2 == 0 else white, segments=10)


def cape_islander(m, x, y0, y1, free, house_top, colour="#e8e2d8", trim="#3e6a8a"):
    """A Nova Scotia Cape Islander lying along y from y0 (stern) to y1 (bow) at x: a lofted hull with a strong sheer,
    flared bow and raked stem, the wheelhouse forward, an open working deck aft with a trap hauler, a mast and boom,
    and a rubbing strake. free is the deck's height amidships and house_top the wheelhouse roof, in steps."""
    c = ships.common()
    L = y1 - y0
    half = L * 0.16
    paint = material("cape islander hull", colour, rough=0.45, clearcoat=0.2)
    top = ships.hull(m, L, half, free, paint, c, bow=0.42, stern=0.1, sheer=free * 0.7, sheer_aft=free * 0.15,
                     transom=0.86, rake=0.2, counter=0.04, flare=0.2, boot=free * 0.25, at=(y0 + y1) / 2, x=x,
                     deck=material("boat deck", "#a89a80", rough=0.85))
    trim_mat = material("boat trim", trim, rough=0.5)
    # The rubbing strake and gunwale cap along the sheer.
    for side in (-1, 1):
        n = 14
        for k in range(n):
            ta, tb = 0.02 + 0.96 * k / n, 0.02 + 0.96 * (k + 1) / n
            pa = (x + side * top.width(ta) * 1.02, top.y(ta), top(ta) - free * 0.18)
            pb = (x + side * top.width(tb) * 1.02, top.y(tb), top(tb) - free * 0.18)
            m.rod(pa, pb, 0.004, trim_mat, segments=6)
            ga = (x + side * top.width(ta) * 0.97, top.y(ta), top(ta) + 0.015)
            gb = (x + side * top.width(tb) * 0.97, top.y(tb), top(tb) + 0.015)
            m.rod(ga, gb, 0.003, c["white"], segments=6)
    # Wheelhouse forward of amidships, its front raked, windows round it.
    wy0, wy1 = top.y(0.5), top.y(0.7)
    z0 = top(0.6)
    ships.house(m, wy0, wy1, half * 0.62, z0, house_top, material("wheelhouse", colour, rough=0.45), c, round_front=0.012,
                rake=0.012, x=x)
    m.box(x - half * 0.68, x + half * 0.68, wy0 - 0.01, wy1 + 0.006, house_top, house_top + 0.025, trim_mat, 0.002)
    # Mast and boom just aft of the wheelhouse, the boom over the working deck.
    mx, my = x, wy0 - 0.012
    m.rod((mx, my, house_top - 0.05), (mx, my, house_top + 0.38), 0.004, c["white"])
    m.rod((mx, my, house_top - 0.02), (mx, top.y(0.12), z0 + 0.12), 0.003, c["white"])
    # The trap hauler on the starboard rail by the wheelhouse, and a few traps and a tote on the working deck.
    hx = x + half * 0.7
    m.upright(hx, wy0 - 0.03, z0, z0 + 0.12, 0.005, 0.005, c["steel"], segments=8)
    m.tube_x(hx - 0.004, hx + 0.012, wy0 - 0.03, z0 + 0.13, 0.014, c["steel"], segments=14)
    trap = material("lobster traps", "#a87a4a", rough=0.9)
    for k in range(2):
        ty = top.y(0.2 + k * 0.1)
        m.box(x - half * 0.5, x + half * 0.1, ty - 0.022, ty + 0.022, z0, z0 + 0.07, trap, 0.002)
    m.box(x + half * 0.2, x + half * 0.55, top.y(0.32) - 0.015, top.y(0.32) + 0.015, z0, z0 + 0.05, material("fish tote", "#3a7aaa", rough=0.6), 0.002)
    return top


def trap_stack(m, x, y, z, rows=3, cols=2, layers=2, size=(0.05, 0.03, 0.15), seed=0, colours=("#d8b828", "#4a7a3a")):
    """Lobster traps stacked in a block, each size (across, along, steps high): coated wire traps in a couple of
    colours, each with a darker frame round its middle and down its end."""
    rnd = random.Random(seed)
    laths = [material(f"trap wire {c}", c, rough=0.7) for c in colours]
    frame = material("trap frame", "#2e2e2a", rough=0.8)
    sx, sy, sz = size
    for i in range(cols):
        for j in range(rows):
            for k in range(layers - (1 if rnd.random() < 0.3 else 0)):
                cx, cy, cz = x + i * (sx + 0.004), y + j * (sy + 0.004), z + k * (sz + 0.01)
                m.box(cx - sx / 2, cx + sx / 2, cy - sy / 2, cy + sy / 2, cz, cz + sz, laths[rnd.randrange(len(laths))], 0.002)
                m.box(cx - sx / 2 - 0.001, cx + sx / 2 + 0.001, cy - 0.002, cy + 0.002, cz, cz + sz + 0.003, frame)
                m.box(cx - sx / 2 - 0.001, cx + sx / 2 + 0.001, cy - sy / 2 - 0.001, cy + sy / 2 + 0.001, cz + sz * 0.45, cz + sz * 0.55, frame)


def buoy(m, x, y, z, colour="#e84a2a", r=0.012):
    """A lobster buoy: a fat spindle in its owner's colours."""
    mat = material(f"buoy {colour}", colour, rough=0.5)
    m.lathe(x, y, z, [(0.002, 0), (r * 0.7, 0.02), (r, 0.05), (r * 0.6, 0.08), (0.002, 0.09)], mat, segments=10)
    m.upright(x, y, z + 0.09, z + 0.13, 0.002, 0.002, material("buoy stick", "#d8d0b8", rough=0.8), segments=5)
