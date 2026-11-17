"""Maglev trains and their guideway.

Trains are built as diesel.py's are: facing +y, the front at +L/2, x across, y along, z in steps, but drawn at full
height from the ground (render_vehicles gives them no lowering), as they ride high on the guideway's beam. The body
wraps down round the beam's head, so there are no wheels or trucks, and no pantograph.

The guideway is a concrete beam on a pier a tile, its top at BEAM_TOP steps, where the trains' skirts hang round it.
Pieces are built in the tile's own terms as scenery.track is, on ground of a shape, and curves as curves.track has
them. A bridge of guideway is the same beam, deeper, on the concrete bridge's piers.
"""
import math
import random

import parts
import rig
from parts import material
from steam import LETTERS
from diesel import alto_paint, livery

# ---- Trains ---------------------------------------------------------------------------------------------------

# Each train's colours: body, the band low along the side, the line over it, the roof and the glass.
COLOURS = {
    "aurora": {"body": "#eef1f2", "band": "#1f7a6e", "line": "#8a5ac8", "roof": "#9aa4aa", "mask": "#0e1418", "skirt": "#2a3036"},
    "meteor": {"body": "#2a3440", "band": "#e07a2a", "line": "#f0c060", "roof": "#1c242c", "mask": "#080c10", "skirt": "#161c22"},
}
# Half a cross-section from the middle of the skirt's foot round to the middle of the roof, (across, up) each 0 to 1:
# the skirt tucked in where it wraps the beam, the body flaring out over it, a well rounded roof.
RING = [(0.0, 0.0), (0.62, 0.0), (0.76, 0.01), (0.8, 0.05), (0.82, 0.14), (0.86, 0.2), (0.94, 0.25), (0.99, 0.31),
        (1.0, 0.4), (1.0, 0.55), (1.0, 0.7), (0.99, 0.76), (0.97, 0.81), (0.93, 0.86), (0.86, 0.91), (0.75, 0.95),
        (0.56, 0.98), (0.3, 0.997), (0.0, 1.0)]
# Where the skirt stops and the body starts, up the ring.
SKIRT_UP = 0.22
# The body's foot and roof in steps, and its half width in tiles.
FOOT, HALF = 0.5, 0.12
TOP = {"aurora": 1.56, "meteor": 1.48}


def ring(half, z0, z1):
    right = [(u * half, z0 + v * (z1 - z0)) for u, v in RING]
    left = [(-u * half, z0 + v * (z1 - z0)) for u, v in reversed(RING[1:-1])]
    return right + left


def _mats(name):
    c = COLOURS[name]
    return {k: material(f"{name} {k}", v, rough=0.3 if k == "mask" else 0.4, clearcoat=0.35) for k, v in c.items()}


def _nose(name):
    """(length, tip height, roof power, width power) of each train's nose: the Meteor's longer, lower and finer."""
    return (0.34, 0.66, 1.25, 2.4) if name == "aurora" else (0.46, 0.6, 1.1, 1.9)


def power_car(m, L, name):
    """A maglev power car: a long rounded nose falling from the roof to a low snout over the beam, a black glass
    windshield wrapping round into the cab side windows, a band of glass along the side, skirts hanging round the
    beam's head and a lamp each side low on the snout. The Meteor's nose is longer, lower and finer."""
    mats = _mats(name)
    lamp = livery("silver")["lamp"]
    a0, a1 = -L / 2 + 0.008, L / 2 - 0.004
    z0, z1, half = FOOT, TOP[name], HALF
    nose, tip_z, rp, wp = _nose(name)
    n0 = a1 - nose

    def section(t):
        """(half, foot, roof) at t along the nose, 0 where the roof starts to fall, 1 at the tip. The foot stays down
        round the beam to the very end, the sides round in."""
        roof = tip_z + (z1 - tip_z) * (1 - t ** rp) ** (1 / 1.3)
        w = half * (1 - t ** wp) ** (1 / 2.4)
        return max(w, 0.006), z0, max(roof, z0 + 0.04)

    secs = [(a0, ring(half * 0.9, z0 + 0.03, z1 - 0.06)), (a0 + 0.006, ring(half * 0.97, z0 + 0.01, z1 - 0.015)),
            (a0 + 0.016, ring(half, z0, z1))]
    for t in [k / 64 for k in range(63)] + [0.975, 0.99]:
        w, f, r = section(t)
        secs.append((n0 + nose * t, ring(w, f, r)))
    body = m.loft(secs, {"body": mats["body"]}, sharp=50)

    def colour(y, x, z):
        t = (y - n0) / nose
        w, f, r = (half, z0, z1) if t <= 0 else section(min(t, 0.99))
        up = (z - f) / max(r - f, 1e-6)
        if up < SKIRT_UP:
            return "skirt"
        if up < 0.3 and t < 0.9:
            return "band"
        if up < 0.33 and t < 0.75:
            return "line"
        # The windshield, round the nose and back along the sides as the cab windows, then a glass band to the back.
        top = 0.86 - 0.1 * max(0.0, min(1.0, t / 0.7))
        if t > 0.2 and 0.6 - 0.18 * min(1.0, (t - 0.2) / 0.5) < up < top:
            return "mask"
        if t <= 0.2 and 0.6 < up < 0.78 and y > a0 + 0.06:
            return "mask"
        if up > 0.92 and t < 0.1:
            return "roof"
        return None

    alto_paint(m, body, mats, colour)
    # Lamps low in the snout either side.
    t = 0.8
    w, f, r = section(t)
    y = n0 + nose * t
    z = f + (r - f) * 0.38
    for side in (-1, 1):
        m.upright(side * w * 0.8, y, z - 0.025, z + 0.025, 0.008, 0.008, lamp, segments=10)
    # A dark diaphragm where it meets the coach, and the company's letters on the side.
    m.box(-0.085, 0.085, a0 - 0.008, a0 + 0.004, z0 + 0.2, z1 - 0.1, mats["skirt"], bevel=0.003)
    for side in (-1, 1):
        x = side * (half + 0.0012)
        door = n0 - 0.04
        for yy in (door - 0.034, door):
            m.box(x - 0.0007, x + 0.0007, yy, yy + 0.003, z0 + 0.3 * (z1 - z0), z0 + 0.78 * (z1 - z0), mats["skirt"])
        m.text(LETTERS, x, (a0 + door) / 2, z0 + 0.42 * (z1 - z0), 0.03, mats["band"], side)


def coach(m, L, name):
    """A maglev coach: the power car's body with blunt ends and a diaphragm at each, a long band of windows, a plug
    door near each end and skirts round the beam."""
    mats = _mats(name)
    glass = livery("silver")["glass"]
    a0, a1 = -L / 2 + 0.008, L / 2 - 0.008
    z0, z1, half = FOOT, TOP[name], HALF
    secs = [(a0, ring(half * 0.92, z0 + 0.03, z1 - 0.05)), (a0 + 0.008, ring(half, z0, z1)),
            (a1 - 0.008, ring(half, z0, z1)), (a1, ring(half * 0.92, z0 + 0.03, z1 - 0.05))]
    body = m.loft(secs, {"body": mats["body"]}, sharp=50)

    def colour(y, x, z):
        up = (z - z0) / (z1 - z0)
        if up < SKIRT_UP:
            return "skirt"
        if up < 0.3:
            return "band"
        if up < 0.33:
            return "line"
        if up > 0.93:
            return "roof"
        return None

    alto_paint(m, body, mats, colour)
    for y0, y1 in ((a0 - 0.008, a0 + 0.002), (a1 - 0.002, a1 + 0.008)):
        m.box(-0.085, 0.085, y0, y1, z0 + 0.2, z1 - 0.1, mats["skirt"], bevel=0.002)
    doors = (a0 + 0.05, a1 - 0.08)
    w0, w1 = 0.56, 0.76
    for side in (-1, 1):
        x = side * (half + 0.0008)
        for d in doors:
            for yy in (d, d + 0.032):
                m.box(x - 0.0007, x + 0.0007, yy, yy + 0.003, z0 + 0.3 * (z1 - z0), z0 + 0.78 * (z1 - z0), mats["skirt"])
            m.box(x - 0.0008, x + 0.0008, d + 0.009, d + 0.026, z0 + w0 * (z1 - z0), z0 + w1 * (z1 - z0), glass)
        # One long band of glass between the doors, its panes split by thin pillars.
        g0, g1 = doors[0] + 0.05, doors[1] - 0.02
        m.box(x - 0.0008, x + 0.0008, g0, g1, z0 + w0 * (z1 - z0), z0 + w1 * (z1 - z0), mats["mask"])
        n = 10
        for i in range(1, n):
            p = g0 + (g1 - g0) * i / n
            m.box(x - 0.0011, x + 0.0011, p - 0.0015, p + 0.0015, z0 + w0 * (z1 - z0), z0 + w1 * (z1 - z0), mats["body"])
        m.text(LETTERS, side * (half + 0.0012), (a0 + a1) / 2, z0 + 0.42 * (z1 - z0), 0.026, mats["band"], side)


# ---- Guideway -------------------------------------------------------------------------------------------------

# The beam's cross-section, its top at BEAM_TOP steps: a web from WEB_FOOT up, the dark stator packs under the head
# and the head the trains wrap round.
BEAM_TOP = 0.75
HEAD = (0.66, 0.062)
STATOR = (0.62, 0.058)
WEB = (0.42, 0.04)
# The cleared strip under the beam and the piers, in tiles across.
STRIP = 0.1
PIER = 0.042


def concrete():
    return material("guideway concrete", "#c4c2bc", rough=0.8)


def _beam_mats():
    return concrete(), material("guideway stator", "#3a3e44", metal=0.5, rough=0.45), material("guideway pier", "#aeaba4", rough=0.85)


def _beam_parts(drop=0.0):
    """The beam as (half top, half bottom, z0, z1, which material) prisms, from the ground up, its top [drop] steps low."""
    return [(WEB[1], WEB[1], WEB[0], STATOR[0], 0), (STATOR[1], STATOR[1], STATOR[0], HEAD[0], 1), (HEAD[1], HEAD[1], HEAD[0], BEAM_TOP - drop, 0)]


def guide(m, piece, shape, drop=0.0):
    """A piece of guideway on ground of [shape]: the cleared strip, a pier at its middle and the beam, its top [drop]
    steps low so where beams meet on a tile one top shows clean over the other."""
    import scenery
    offs = [(shape >> i) & 1 for i in range(4)]
    ea, eb = scenery.PIECE_EDGES[piece]
    a = scenery.EDGE_MID[ea]
    b = scenery.EDGE_MID[eb]
    length = math.hypot(b[0] - a[0], b[1] - a[1])
    n = (-(b[1] - a[1]) / length, (b[0] - a[0]) / length)
    ends = (scenery._edge_dir(ea, n), scenery._edge_dir(eb, n))
    k = abs(ends[0][0] * n[0] + ends[0][1] * n[1])
    beam, stator, pier = _beam_mats()
    scenery._strip(m, a, b, offs, STRIP, STRIP + 0.012, 0.0, 0.03 - drop, scenery.gravel(), ends=ends)
    mats = (beam, stator)
    for top, bottom, z0, z1, which in _beam_parts(drop):
        scenery._strip(m, a, b, offs, top / k, bottom / k, z0, z1, mats[which], ends=ends)
    t = 0.5
    dt = PIER / length
    scenery._strip(m, a, b, offs, PIER, PIER + 0.004, 0.0, WEB[0], pier, t - dt, t + dt)
    scenery._strip(m, a, b, offs, PIER + 0.03, PIER + 0.035, 0.0, 0.05, pier, t - dt * 1.8, t + dt * 1.8)


def guide_set(m, mask):
    """Every piece in [mask] on one level tile, the beams running through each other where they meet."""
    import scenery
    for i, piece in enumerate(scenery.PIECES):
        if mask & (1 << i):
            guide(m, piece, 0, drop=0.004 * i)


def curve_guide(m, curve):
    """A curved piece of guideway on level ground, along a curves.Curve: the strip, the beam and a pier at its middle."""
    import curves
    beam, stator, pier = _beam_mats()
    import scenery
    curves._sweep(m, curve, [(STRIP + 0.012, 0.0), (STRIP, 0.03), (-STRIP, 0.03), (-STRIP - 0.012, 0.0)], scenery.gravel())
    mats = (beam, stator)
    for top, bottom, z0, z1, which in _beam_parts():
        curves._sweep(m, curve, [(bottom, z0), (top, z1), (-top, z1), (-bottom, z0)], mats[which])
    p, d = curve.at(curve.t_of(curve.length / 2))
    curves._box(m, p, d, PIER, PIER, 0.0, WEB[0], pier)
    curves._box(m, p, d, PIER + 0.03, PIER + 0.03, 0.0, 0.05, pier)


def guide_end(m, edge):
    """A buffer at the end of a beam that stops at view edge [edge]: a concrete block over the beam's head with a
    red lamp facing out."""
    import scenery
    beam, stator, pier = _beam_mats()
    red = material("buffer lamp", "#e0302a", rough=0.3)
    yellow = material("buffer stripes", "#e0b020", rough=0.5)
    ex, ey = scenery.EDGE_MID[edge]
    out = {"XM": (-1, 0), "XP": (1, 0), "YM": (0, -1), "YP": (0, 1)}[edge]
    c = (ex - out[0] * 0.07, ey - out[1] * 0.07)
    import curves
    curves._box(m, c, out, 0.05, 0.1, 0.0, 1.05, beam)
    f = (ex - out[0] * 0.018, ey - out[1] * 0.018)
    curves._box(m, f, out, 0.002, 0.1, 0.78, 0.9, yellow)
    for s in (-1, 1):
        p = (f[0] - out[1] * s * 0.06, f[1] + out[0] * s * 0.06)
        curves._box(m, p, out, 0.004, 0.016, 0.92, 0.98, red)


def _lift(m, by):
    """Lifts everything the builder makes after this by [by] steps."""
    m._shift = by


def station(m, along_u, canopy=True):
    """A guideway platform tile: scenery.station's modern platforms and shelter raised level with the trains' floor,
    on walls down to the ground. The beam comes from the guideway sprite under it."""
    import scenery
    b = m if along_u else scenery._Turned(m)
    wall = material("platform wall", "#b4b0a6", rough=0.85)
    lift = 0.54
    for side in (-1, 1):
        x0, x1 = sorted((side * 0.24, side * 0.5))
        b.box(x0, x1, -0.5, 0.5, 0.0, lift, wall)
    _lift(m, lift)
    scenery.station(m, along_u, canopy=canopy, era=2)
    _lift(m, None)


def depot(m):
    """A guideway maintenance hall, its doors facing +y: a long hall clad in silver panels under a low barrel roof
    with a glass ridge, a tall doorway for the beam with a glass door rolled up over it, and a plinth."""
    import houses
    import scenery
    clad = material("hall cladding", "#c8ccd0", rough=0.45, metal=0.3)
    seam = material("hall seams", "#a4aaae", rough=0.5, metal=0.3)
    plinth = material("hall plinth", "#9a968e", rough=0.9)
    trim = material("hall trim", "#1f7a6e", rough=0.45)
    dark = material("doorway", "#1a1714", rough=1.0)
    sky = material("hall roof glass", "#a8c4d0", rough=0.15)
    glass = houses.glass()
    dx, y0, y1 = scenery.DX, scenery.DY0, scenery.DY1
    eave = 2.5
    m.box(-dx, dx, y0, y1, 0, 0.3, plinth, 0.003)
    m.box(-dx, dx, y0, y1, 0.3, eave, clad, 0.004)
    for k in range(1, 9):
        y = y0 + (y1 - y0) * k / 9
        for side in (-1, 1):
            x = side * (dx + 0.001)
            m.box(x - 0.002, x + 0.002, y - 0.004, y + 0.004, 0.3, eave, seam)
    # The barrel roof, a curve across from eave to eave, with a glass band down its crown.
    n = 10
    pts = [(-dx - 0.02 + (2 * dx + 0.04) * i / n, eave + 0.55 * math.sin(math.pi * i / n)) for i in range(n + 1)]
    for i, ((xa, za), (xb, zb)) in enumerate(zip(pts, pts[1:])):
        mat = sky if i in (4, 5) else clad
        m.wedge([(xa, y0 - 0.02, za), (xb, y0 - 0.02, zb), (xb, y1 + 0.02, zb), (xa, y1 + 0.02, za)],
                [(xa, y0 - 0.02, za + 0.05), (xb, y0 - 0.02, zb + 0.05), (xb, y1 + 0.02, zb + 0.05), (xa, y1 + 0.02, za + 0.05)], mat)
    # A band of windows along each side and a trim line under the eaves.
    for d in (-1, 1):
        houses.on_wall(m, "x", d * dx, d, y0 + 0.06, y1 - 0.06, 0, 0.008, 1.7, 2.1, glass)
        a, c = sorted((d * dx, d * (dx + 0.01)))
        m.box(a, c, y0, y1, 2.3, 2.38, trim)
    # The front: the end wall closed under the barrel, the doorway, its glass door rolled up and a trim frame.
    m.box(-dx, dx, y1 - 0.004, y1 + 0.004, 0.3, eave, clad)
    m.box(-0.17, 0.17, y1 - 0.002, y1 + 0.012, 0.0, 2.25, dark)
    m.box(-0.17, 0.17, y1 + 0.012, y1 + 0.02, 1.95, 2.24, glass)
    for d in (-1, 1):
        a, c = sorted((d * 0.17, d * 0.2))
        m.box(a, c, y1, y1 + 0.03, 0.0, 2.28, trim)
    m.box(-0.2, 0.2, y1, y1 + 0.03, 2.25, 2.34, trim)
    m.box(-0.06, 0.06, y1 + 0.004, y1 + 0.016, 2.5, 2.62, material("wall lamp", "#fff4d8", rough=0.3))


# ---- Bridges and tunnels --------------------------------------------------------------------------------------


def _girder(hb):
    """The bridge beam's outline across, (x, z) round from the soffit: deep, a little wider at the foot, the stator
    packs under the head as the beam on the ground has them. hb the half width at the soffit."""
    return [(-hb, -0.7), (hb, -0.7), (WEB[1] + 0.02, STATOR[0]), (-WEB[1] - 0.02, STATOR[0])]


def deck(m, along_u):
    """A tile of guideway bridge: the beam deep enough to span between the concrete piers, its top at BEAM_TOP."""
    import spans
    beam, stator, _pier = _beam_mats()
    spans._prism(m, along_u, _girder(0.09), -0.5, 0.5, beam)
    b = spans._boxer(m, along_u)
    b(-STATOR[1], STATOR[1], -0.5, 0.5, STATOR[0], HEAD[0], stator)
    b(-HEAD[1], HEAD[1], -0.5, 0.5, HEAD[0], BEAM_TOP, beam)
    # A shadow line at the soffit's edges, as the concrete deck has its drip edge.
    drip = material("deck drip edge", "#8e8a82", rough=0.9)
    for side in (-1, 1):
        x0, x1 = sorted((side * 0.088, side * 0.093))
        b(x0, x1, -0.5, 0.5, -0.7, -0.62, drip)


def ramp(m, edge):
    """A guideway bridge's head on flat ground: the beam climbing a step across the tile to the deck at view edge
    [edge], on a wall down to a footing, with a seat for the deck at the inner end."""
    import spans
    along_u = edge in ("XM", "XP")
    d = 1 if edge in ("XP", "YP") else -1
    beam, stator, pier = _beam_mats()
    import scenery
    spans._loft(m, along_u, d, -STRIP - 0.012, STRIP + 0.012, -0.5, 0.5, 0.0, 0.03, scenery.gravel(), lift_b=False, lift_t=False)
    # The wall under the beam, from the ground up to the stator packs.
    spans._loft(m, along_u, d, -WEB[1], WEB[1], -0.5, 0.5, 0.0, STATOR[0], pier, lift_b=False)
    spans._loft(m, along_u, d, -WEB[1] - 0.03, WEB[1] + 0.03, -0.5, 0.5, 0.0, 0.05, pier, lift_b=False, lift_t=False)
    spans._ramp_profile(m, along_u, d, [(-STATOR[1], STATOR[0]), (STATOR[1], STATOR[0]), (STATOR[1], HEAD[0]), (-STATOR[1], HEAD[0])], stator)
    spans._ramp_profile(m, along_u, d, [(-HEAD[1], HEAD[0]), (HEAD[1], HEAD[0]), (HEAD[1], BEAM_TOP), (-HEAD[1], BEAM_TOP)], beam)
    # The seat at the inner end, a block across under the deck's beam.
    b = spans._boxer(m, along_u)
    yi = d * 0.5
    b(-0.16, 0.16, *sorted((yi, yi - d * 0.12)), 0.0, 1.3, pier, 0.004)
    b(-0.2, 0.2, *sorted((yi + d * 0.01, yi - d * 0.15)), 0.0, 0.08, pier)


def portal(m, edge):
    """A guideway tunnel mouth at the inner half of the tile, opening towards view edge [edge]: a plain concrete face
    with a flared hood round a tall rounded opening, wing walls and the rock of the hill over it."""
    import spans
    face = material("portal concrete", "#b6b2aa", rough=0.85)
    hood_mat = material("portal hood", "#cac6be", rough=0.8)
    dark = material("tunnel dark", "#0c0b0a", rough=1.0)
    rock = material("portal rock", "#77716a", rough=0.95)
    rock2 = material("portal rock dark", "#635e58", rough=0.95)
    along_u = edge in ("XM", "XP")
    out = 1 if edge in ("XP", "YP") else -1
    b = spans._boxer(m, along_u)
    y = -out * 0.1
    f = y + out * 0.05
    hw = 0.4
    b(-hw, hw, y - 0.05, y + 0.05, 0.0, 3.1, face, 0.004)
    b(-hw - 0.02, hw + 0.02, y - 0.07, y + 0.07, 3.1, 3.25, hood_mat, 0.003)
    # The opening: straight sides and a round top, dark, with a hood flaring out round it.
    a, spring, rz = 0.2, 1.2, 0.85
    opening = [(-a, 0.0), (a, 0.0)] + spans._ellipse(a, rz, spring, 28)
    spans._prism(m, along_u, opening, min(f - out * 0.11, f + out * 0.02), max(f - out * 0.11, f + out * 0.02), dark)
    n = 16
    for i in range(n):
        a0, a1 = math.pi * i / n, math.pi * (i + 1) / n
        inner = [(a * math.cos(a0), spring + rz * math.sin(a0)), (a * math.cos(a1), spring + rz * math.sin(a1))]
        outer = [((a + 0.06) * math.cos(a1), spring + (rz + 0.25) * math.sin(a1)), ((a + 0.06) * math.cos(a0), spring + (rz + 0.25) * math.sin(a0))]
        spans._prism(m, along_u, list(reversed(inner + outer)), min(f, f + out * 0.12), max(f, f + out * 0.12), hood_mat)
    for side in (-1, 1):
        x0, x1 = sorted((side * a, side * (a + 0.06)))
        b(x0, x1, min(f, f + out * 0.12), max(f, f + out * 0.12), 0.0, spring, hood_mat)
    # Wing walls splayed out from the face, falling to the ends.
    for side in (-1, 1):
        x0, x1 = side * hw, side * 0.5
        ya, yb = f - out * 0.05, f + out * 0.12
        pa, pb = (x0, ya), (x1, yb)
        nx, ny = (pb[1] - pa[1]), -(pb[0] - pa[0])
        ln = math.hypot(nx, ny)
        nx, ny = nx / ln * 0.03, ny / ln * 0.03
        plan = [(pa[0] - nx, pa[1] - ny), (pb[0] - nx, pb[1] - ny), (pb[0] + nx, pb[1] + ny), (pa[0] + nx, pa[1] + ny)]
        m.wedge([spans._swap(along_u, (px, py, 0.0)) for px, py in plan],
                [spans._swap(along_u, (px, py, 1.7 if k in (1, 2) else 3.1)) for k, (px, py) in enumerate(plan)], face)
    rng = random.Random(11)
    for k in range(12):
        cx = rng.uniform(-0.44, 0.44)
        cy = y - out * rng.uniform(0.1, 0.36)
        r = rng.uniform(0.07, 0.13)
        z0 = 2.9 + rng.uniform(0.0, 0.25)
        h = rng.uniform(0.3, 0.7) * (1.0 - abs(cx) * 0.6)
        rot = rng.uniform(0, math.pi)
        bot, tp = [], []
        for i in range(6):
            ang = rot + 2 * math.pi * i / 6
            rr = r * rng.uniform(0.75, 1.1)
            bot.append((cx + rr * math.cos(ang), cy + rr * math.sin(ang), z0))
            tp.append((cx + 0.5 * rr * math.cos(ang + 0.3), cy + 0.5 * rr * math.sin(ang + 0.3), z0 + h * rng.uniform(0.8, 1.1)))
        m.wedge([spans._swap(along_u, p) for p in bot], [spans._swap(along_u, p) for p in tp], rock if k % 3 else rock2)
