"""Bridges and tunnel portals, in the same terms as scenery.py: x across, y along (the game's u), z in steps,
on the middle of the tile.

A deck is one tile of bridge with its rails on top at z 0, so a train at the deck's height runs on it. A pier
is one step of what holds the deck up, from z 0 to 1, stacked from the ground to under the deck; the top step
is drawn on its own with the cap the deck sits on. A portal is a tunnel's mouth in the hillside, its track at z 0.

Through trusses run two tiles between piers on every other tile. Each tile is one of TRUSS_KINDS: "mid" the
middle of a span, "pier" two spans' sloped ends meeting over a pier, "start" and "end" a pier tile at the first
or last tile of the bridge with a plate girder out to the abutment on its -y or +y half, and "girder" a one
tile bridge, plate girders both halves.
"""
import math
import random

import parts
import rig
from parts import material

STYLES = ["trestle", "truss", "concrete"]
TRUSS_KINDS = ["mid", "pier", "start", "end", "girder"]
SHED_ERAS = ["timber", "concrete"]

# The truss: its planes either side of the track, the top chord's height and the panel length along y.
TRUSS_X = 0.27
TRUSS_TOP = 2.58
PANEL = 0.25


def _boxer(m, along_u):
    def b(x0, x1, y0, y1, z0, z1, mat, bevel=0.0):
        if along_u:
            m.box(x0, x1, y0, y1, z0, z1, mat, bevel)
        else:
            m.box(y0, y1, x0, x1, z0, z1, mat, bevel)
    return b


def _swap(along_u, p):
    return p if along_u else (p[1], p[0], p[2])


def _rod(m, along_u, a, b, r, mat):
    m.rod(_swap(along_u, a), _swap(along_u, b), r, mat)


def _beam(m, along_u, a, b, w, t, mat, up=(0.0, 0.0, 1.0)):
    """A square cut member from a to b (x, y, z in steps), w tiles wide across up's side and t deep towards up."""
    r = rig.RISE
    pa = (a[0], a[1], a[2] * r)
    pb = (b[0], b[1], b[2] * r)
    d = [pb[i] - pa[i] for i in range(3)]
    ln = math.sqrt(sum(c * c for c in d)) or 1.0
    d = [c / ln for c in d]
    u = (d[1] * up[2] - d[2] * up[1], d[2] * up[0] - d[0] * up[2], d[0] * up[1] - d[1] * up[0])
    lu = math.sqrt(sum(c * c for c in u))
    if lu < 1e-6:
        u = (1.0, 0.0, 0.0) if abs(d[0]) < 0.9 else (0.0, 1.0, 0.0)
        lu = 1.0
    u = [c / lu for c in u]
    v = (d[1] * u[2] - d[2] * u[1], d[2] * u[0] - d[0] * u[2], d[0] * u[1] - d[1] * u[0])

    def ring(p):
        out = []
        for su, sv in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
            q = [p[i] + su * u[i] * w / 2 + sv * v[i] * t / 2 for i in range(3)]
            out.append(_swap(along_u, (q[0], q[1], q[2] / r)))
        return out
    return m.wedge(ring(pa), ring(pb), mat)


def _prism(m, along_u, profile, y0, y1, mat):
    """A solid from a closed outline of (x, z in steps) run along y from y0 to y1."""
    return m.wedge([_swap(along_u, (x, y0, z)) for x, z in profile], [_swap(along_u, (x, y1, z)) for x, z in profile], mat)


def _upright(m, along_u, plan, z0, z1, mat):
    """A solid from a closed outline of (x, y) in plan, from z0 to z1 steps."""
    return m.wedge([_swap(along_u, (x, y, z0)) for x, y in plan], [_swap(along_u, (x, y, z1)) for x, y in plan], mat)


def _label(m, along_u, body, x, y, z, size, mat, facing):
    """Lettering standing across the track at y, facing along y towards facing (-1 or 1)."""
    if along_u:
        obj = m.text(body, x, y, z, size, mat, 1)
        obj.location = (x, y, m.z(z) * rig.RISE)
        obj.rotation_euler = (math.radians(90), 0, math.radians(0 if facing < 0 else 180))
    else:
        obj = m.text(body, y, x, z, size, mat, facing)
    return obj


def _rails(m, along_u, z, tie_mat, tie_half=0.17):
    """Open ties and two rails along the tile, their tops at z + 0.42 as on track."""
    b = _boxer(m, along_u)
    rail = material("rail", "#6a6662", metal=0.9, rough=0.45)
    head = material("rail head", "#d8d4cc", metal=1.0, rough=0.2)
    for i in range(9):
        y = -0.5 + (i + 0.5) / 9
        b(-tie_half, tie_half, y - 0.024, y + 0.024, z + 0.12, z + 0.2, tie_mat)
    for side in (-1, 1):
        b(side * 0.095 - 0.012, side * 0.095 + 0.012, -0.5, 0.5, z + 0.2, z + 0.36, rail)
        b(side * 0.095 - 0.009, side * 0.095 + 0.009, -0.5, 0.5, z + 0.36, z + 0.42, head)


# ---- Decks ---------------------------------------------------------------------------------------------------


def _trestle_deck(m, along_u):
    b = _boxer(m, along_u)
    tie = material("tie", "#4e3626", rough=0.9)
    timber = material("trestle timber", "#7a5a3e", rough=0.9)
    seam = material("trestle seam", "#4a3626", rough=1.0)
    # Chorded stringers, two timbers deep, a pair under each rail, on the bents' caps at -0.45.
    for side in (-1, 1):
        for x in (0.065, 0.125):
            xc = side * x
            b(xc - 0.022, xc + 0.022, -0.5, 0.5, -0.45, 0.12, timber, 0.003)
            b(xc - 0.0235, xc + 0.0235, -0.5, 0.5, -0.17, -0.15, seam)
        # Packing blocks between each pair.
        x0, x1 = sorted((side * 0.087, side * 0.103))
        for y in (-0.3, 0.0, 0.3):
            b(x0, x1, y - 0.03, y + 0.03, -0.35, 0.0, seam)
    _rails(m, along_u, 0.0, tie, tie_half=0.22)
    # Guard timbers bolted down along the tie ends, and a refuge with a fire barrel off one side.
    for side in (-1, 1):
        b(side * 0.2 - 0.02, side * 0.2 + 0.02, -0.5, 0.5, 0.12, 0.24, timber, 0.003)
    b(0.22, 0.32, -0.08, 0.08, 0.12, 0.2, timber, 0.002)
    for y in (-0.06, 0.06):
        _rod(m, along_u, (0.24, y, 0.2), (0.24, y, -0.25), 0.008, timber)
    barrel = material("fire barrel", "#8a3a2a", rough=0.7)
    if along_u:
        m.upright(0.28, 0.0, 0.2, 0.42, 0.028, 0.028, barrel)
    else:
        m.upright(0.0, 0.28, 0.2, 0.42, 0.028, 0.028, barrel)


def _truss_floor(m, along_u, steel, tie):
    b = _boxer(m, along_u)
    # Floor beams at each panel point, stringers under the rails, ties and rails on top.
    for k in range(5):
        y = -0.5 + k * PANEL
        b(-TRUSS_X - 0.02, TRUSS_X + 0.02, y - 0.016, y + 0.016, -0.28, 0.06, steel, 0.002)
    for x in (-0.095, 0.095):
        b(x - 0.014, x + 0.014, -0.5, 0.5, -0.12, 0.12, steel)
    _rails(m, along_u, 0.0, tie)


def _top_struts(m, along_u, ys, steel, light):
    """Struts across between the top chords at ys, with knee braces under them and lateral rods between."""
    b = _boxer(m, along_u)
    for y in ys:
        b(-TRUSS_X, TRUSS_X, y - 0.012, y + 0.012, TRUSS_TOP - 0.03, TRUSS_TOP + 0.05, steel)
        for side in (-1, 1):
            _beam(m, along_u, (side * TRUSS_X, y, TRUSS_TOP - 0.35), (side * (TRUSS_X - 0.09), y, TRUSS_TOP - 0.02), 0.012, 0.012, light)
    for y0, y1 in zip(ys, ys[1:]):
        for s in (-1, 1):
            _rod(m, along_u, (s * TRUSS_X, y0, TRUSS_TOP + 0.06), (-s * TRUSS_X, y1, TRUSS_TOP + 0.06), 0.006, light)


def _post(m, along_u, x, y, steel, z0=0.0, z1=TRUSS_TOP, w=0.03, t=0.04):
    _beam(m, along_u, (x, y, z0), (x, y, z1), w, t, steel, up=(0.0, 1.0, 0.0))


def _member(m, along_u, x, a, c, steel, w=0.05, t=0.04):
    """A heavy member in the truss plane at x from (y, z) a to c."""
    _beam(m, along_u, (x, a[0], a[1]), (x, c[0], c[1]), w, t, steel, up=(1.0, 0.0, 0.0))


def _eyebar(m, along_u, x, a, c, steel, r=0.009):
    """Two flat bars side by side, as a Pratt truss's diagonals."""
    for dx in (-0.014, 0.014):
        _rod(m, along_u, (x + dx, a[0], a[1]), (x + dx, c[0], c[1]), r, steel)


def _half_mid(m, along_u, s, steel, light):
    """The half of a mid tile on side s of y 0, the span's middle at y 0: three panels' posts, diagonals down
    towards the middle, counters in the middle panel."""
    for x in (-TRUSS_X, TRUSS_X):
        b = _boxer(m, along_u)
        y0, y1 = min(0.0, s * 0.5), max(0.0, s * 0.5)
        b(x - 0.03, x + 0.03, y0, y1, TRUSS_TOP - 0.06, TRUSS_TOP + 0.07, steel, 0.002)
        b(x - 0.035, x + 0.035, y0, y1, -0.14, 0.08, steel, 0.002)
        for k in (1, 2):
            _post(m, along_u, x, s * k * PANEL, steel)
        if s < 0:
            _post(m, along_u, x, 0.0, steel, w=0.026, t=0.032)
        _eyebar(m, along_u, x, (s * 0.5, TRUSS_TOP), (s * 0.25, 0.04), light)
        _eyebar(m, along_u, x, (s * 0.25, TRUSS_TOP), (0.0, 0.04), light)
        _rod(m, along_u, (x, 0.0, TRUSS_TOP), (x, s * 0.25, 0.04), 0.006, light)


def _half_end(m, along_u, s, steel, light):
    """The half of a pier tile on side s: a span's sloped end post coming down to the pier at y 0, its hip
    vertical, one panel and a portal brace across with a name plate."""
    yb = s * 0.035
    yh = s * PANEL
    b = _boxer(m, along_u)
    for x in (-TRUSS_X, TRUSS_X):
        y0, y1 = min(yh, s * 0.5), max(yh, s * 0.5)
        b(x - 0.03, x + 0.03, y0, y1, TRUSS_TOP - 0.06, TRUSS_TOP + 0.07, steel, 0.002)
        y0, y1 = min(yb, s * 0.5), max(yb, s * 0.5)
        b(x - 0.035, x + 0.035, y0, y1, -0.14, 0.08, steel, 0.002)
        _member(m, along_u, x, (yb, -0.05), (yh, TRUSS_TOP + 0.04), steel)
        _post(m, along_u, x, yh, steel, w=0.022, t=0.028)
        _post(m, along_u, x, s * 0.5, steel)
        _eyebar(m, along_u, x, (yh, TRUSS_TOP), (s * 0.5, 0.04), light)
        # Bearing shoe on the pier.
        b(x - 0.04, x + 0.04, min(yb, s * 0.09), max(yb, s * 0.09), -0.26, -0.12, steel)
    # Portal brace in the slope of the end posts: struts, lattice between, knee braces and the plate.
    def at(z):
        return yb + (yh - yb) * (z + 0.05) / (TRUSS_TOP + 0.09)
    za, zb = 2.18, 2.5
    for z in (za, zb):
        b(-TRUSS_X, TRUSS_X, at(z) - 0.014, at(z) + 0.014, z - 0.03, z + 0.03, steel)
    n = 6
    for i in range(n):
        xa = -TRUSS_X + 2 * TRUSS_X * i / n
        xb = -TRUSS_X + 2 * TRUSS_X * (i + 1) / n
        _rod(m, along_u, (xa, at(za), za), (xb, at(zb), zb), 0.006, light)
        _rod(m, along_u, (xa, at(zb), zb), (xb, at(za), za), 0.006, light)
    for side in (-1, 1):
        _beam(m, along_u, (side * TRUSS_X, at(1.85), 1.85), (side * (TRUSS_X - 0.1), at(za), za), 0.014, 0.014, light)
    plate = material("name plate", "#2e3a40", metal=0.6, rough=0.5)
    letters = material("name plate letters", "#d8d2b8", metal=0.5, rough=0.4)
    yp = at(2.34) + s * 0.02
    b(-0.12, 0.12, yp - 0.006, yp + 0.006, 2.27, 2.41, plate)
    _label(m, along_u, "DOMINION BRIDGE 1912", 0.0, yp + s * 0.008, 2.34, 0.022, letters, s)
    _top_struts(m, along_u, [yh, s * 0.5], steel, light)


def _half_girder(m, along_u, s, steel):
    """The half of a tile on side s as a through plate girder out to the abutment at its edge."""
    b = _boxer(m, along_u)
    yb = s * 0.035
    y0, y1 = min(yb, s * 0.5), max(yb, s * 0.5)
    for side in (-1, 1):
        x = side * 0.25
        b(x - 0.008, x + 0.008, y0, y1, -0.28, 0.4, steel)
        b(x - 0.03, x + 0.03, y0, y1, 0.36, 0.42, steel, 0.002)
        b(x - 0.03, x + 0.03, y0, y1, -0.3, -0.24, steel, 0.002)
        for k in range(8):
            y = yb + s * (k + 0.5) * (0.465 / 8)
            b(x + side * 0.008, x + side * 0.02, y - 0.006, y + 0.006, -0.24, 0.36, steel)
        b(x - 0.04, x + 0.04, min(yb, s * 0.09), max(yb, s * 0.09), -0.36, -0.28, steel)


def _truss_deck(m, along_u, kind):
    tie = material("tie", "#4e3626", rough=0.9)
    steel = material("truss steel", "#3e4a52", metal=0.7, rough=0.5)
    light = material("truss steel light", "#4c5960", metal=0.7, rough=0.5)
    _truss_floor(m, along_u, steel, tie)
    if kind == "mid":
        for s in (-1, 1):
            _half_mid(m, along_u, s, steel, light)
        _top_struts(m, along_u, [-0.5, -0.25, 0.0, 0.25, 0.5], steel, light)
        return
    halves = {"pier": ("end", "end"), "start": ("girder", "end"), "end": ("end", "girder"), "girder": ("girder", "girder")}[kind]
    for s, half in zip((-1, 1), halves):
        if half == "end":
            _half_end(m, along_u, s, steel, light)
        else:
            _half_girder(m, along_u, s, steel)


def deck(m, style, along_u, kind="mid"):
    b = _boxer(m, along_u)
    tie = material("tie", "#4e3626", rough=0.9)
    if style == "trestle":
        _trestle_deck(m, along_u)
    elif style == "truss":
        _truss_deck(m, along_u, kind)
    else:
        concrete = material("bridge concrete", "#a8a49c", rough=0.85)
        ballast = material("deck ballast", "#6e6a64", rough=1.0)
        # A box girder under a slab that overhangs it, so the piers' caps show beneath.
        _prism(m, along_u, [(-0.12, -0.7), (0.12, -0.7), (0.17, -0.16), (0.26, -0.12), (0.26, 0.02), (-0.26, 0.02), (-0.26, -0.12), (-0.17, -0.16)],
               -0.5, 0.5, concrete)
        drip = material("deck drip edge", "#8e8a82", rough=0.9)
        for side in (-1, 1):
            x0, x1 = sorted((side * 0.258, side * 0.262))
            b(x0, x1, -0.5, 0.5, -0.12, -0.08, drip)
        b(-0.2, 0.2, -0.5, 0.5, 0.02, 0.12, ballast)
        for side in (-1, 1):
            b(side * 0.26 - 0.035, side * 0.26, -0.5, 0.5, 0.0, 0.45, concrete, 0.003)
        _rails(m, along_u, 0.0, tie)


# ---- Piers ---------------------------------------------------------------------------------------------------


def _timber(m, along_u, a, c, mat, w=0.036):
    _beam(m, along_u, a, c, w, w, mat, up=(0.0, 1.0, 0.0))


def _trestle_bent(m, along_u, top):
    """A storey of a timber bent: four posts, sway bracing both faces, a sill at the foot, and at the top either
    a girt where the next storey stands or, on the top storey, the battered outer posts and the cap."""
    b = _boxer(m, along_u)
    timber = material("trestle timber", "#7a5a3e", rough=0.9)
    brace = material("trestle brace", "#6c4e34", rough=0.9)
    zc = 0.47 if top else 1.0
    b(-0.27, 0.27, -0.026, 0.026, 0.0, 0.06, timber, 0.003)
    outer_top = 0.19 if top else 0.24
    for side in (-1, 1):
        _timber(m, along_u, (side * 0.24, 0.0, 0.06), (side * outer_top, 0.0, zc), timber)
        _timber(m, along_u, (side * 0.08, 0.0, 0.06), (side * 0.08, 0.0, zc), timber)
    # Sway braces, planks bolted across each face, crossing.
    for y in (-0.03, 0.03):
        zl, zh = 0.1, zc - 0.06
        _beam(m, along_u, (-0.25, y, zl), (outer_top + 0.01, y, zh), 0.03, 0.01, brace, up=(0.0, 1.0, 0.0))
        _beam(m, along_u, (0.25, y, zl), (-outer_top - 0.01, y, zh), 0.03, 0.01, brace, up=(0.0, 1.0, 0.0))
    if top:
        b(-0.25, 0.25, -0.03, 0.03, 0.47, 0.55, timber, 0.003)
    else:
        for y in (-0.03, 0.03):
            b(-0.27, 0.27, y - 0.008, y + 0.008, 0.9, 0.96, brace)


def _nosed(hw, hd, nose):
    """A pier's plan: hw across, hd along, its ends pointed out by nose to split the current."""
    return [(-hw - nose, 0.0), (-hw, -hd), (hw, -hd), (hw + nose, 0.0), (hw, hd), (-hw, hd)]


def _masonry_pier(m, along_u, top):
    stone = material("pier stone", "#8a8478", rough=0.9)
    stone2 = material("pier stone light", "#9a9384", rough=0.9)
    joint = material("pier joints", "#5e5a52", rough=1.0)
    cap = material("pier coping", "#a29c90", rough=0.85)
    shoe = material("truss steel", "#3e4a52", metal=0.7, rough=0.5)
    zt = 0.58 if top else 1.0
    _upright(m, along_u, _nosed(0.27, 0.145, 0.07), 0.0, zt - 0.006, joint)
    courses = 4 if top else 7
    for k in range(courses):
        z0 = k * zt / courses + 0.012
        z1 = (k + 1) * zt / courses
        _upright(m, along_u, _nosed(0.274, 0.149, 0.07), z0, z1, stone if k % 2 == 0 else stone2)
    if top:
        _upright(m, along_u, _nosed(0.3, 0.17, 0.075), 0.58, 0.7, cap)
        b = _boxer(m, along_u)
        for side in (-1, 1):
            b(side * TRUSS_X - 0.05, side * TRUSS_X + 0.05, -0.11, 0.11, 0.7, 0.76, cap, 0.003)


def _concrete_plan(hw, hd, n=6):
    """A rounded oblong in plan, hw across, hd along, the ends rounded to hd."""
    pts = []
    for side in (1, -1):
        cx = side * (hw - hd)
        for i in range(n + 1):
            a = -math.pi / 2 + math.pi * i / n
            if side < 0:
                a += math.pi
            pts.append((cx + hd * math.cos(a), hd * math.sin(a)))
    return pts


def _concrete_pier(m, along_u, top, foot=False):
    concrete = material("bridge concrete", "#a8a49c", rough=0.85)
    lift = material("pier lift line", "#8e8a82", rough=0.9)
    if foot:
        # The step on the ground: a spread footing, and the shaft tapering in from it to the straight shaft above.
        _upright(m, along_u, _concrete_plan(0.21, 0.15), 0.0, 0.1, concrete)
        bot = [_swap(along_u, (x, y, 0.1)) for x, y in _concrete_plan(0.16, 0.11)]
        tp = [_swap(along_u, (x, y, 1.0)) for x, y in _concrete_plan(0.11, 0.075)]
        m.wedge(bot, tp, concrete)
        _upright(m, along_u, _concrete_plan(0.142, 0.099, 6), 0.4, 0.42, lift)
        return
    if not top:
        _upright(m, along_u, _concrete_plan(0.11, 0.075), 0.0, 1.0, concrete)
        _upright(m, along_u, _concrete_plan(0.112, 0.077), 0.49, 0.51, lift)
        return
    _upright(m, along_u, _concrete_plan(0.11, 0.075), 0.0, 0.04, concrete)
    # The hammerhead: flaring out from the shaft to the deck slab's edges, below the girder's soffit at 0.3.
    _prism(m, along_u, [(-0.11, 0.0), (0.11, 0.0), (0.25, 0.13), (0.25, 0.3), (-0.25, 0.3), (-0.25, 0.13)], -0.085, 0.085, concrete)
    b = _boxer(m, along_u)
    for side in (-1, 1):
        x0, x1 = sorted((side * 0.249, side * 0.252))
        b(x0, x1, -0.085, 0.085, 0.26, 0.3, lift)


def pier(m, style, along_u, top=False, foot=False):
    """A step of pier; top the step under the deck with its cap, foot the step on the ground (concrete only)."""
    if style == "trestle":
        _trestle_bent(m, along_u, top)
    elif style == "truss":
        _masonry_pier(m, along_u, top)
    else:
        _concrete_pier(m, along_u, top, foot)


# ---- Portal --------------------------------------------------------------------------------------------------


def _ellipse(a, b, z0, n, a0=0.0, a1=math.pi):
    return [(a * math.cos(a0 + (a1 - a0) * i / n), z0 + b * math.sin(a0 + (a1 - a0) * i / n)) for i in range(n + 1)]


def portal(m, edge):
    """A stone tunnel mouth at the inner half of the tile, opening towards view edge [edge]."""
    stone = material("portal stone", "#8e877a", rough=0.9)
    stone2 = material("portal stone dark", "#827b6e", rough=0.9)
    cut = material("portal cut stone", "#a39b8c", rough=0.8)
    joint = material("portal joints", "#625c52", rough=1.0)
    dark = material("tunnel dark", "#0c0b0a", rough=1.0)
    cap = material("portal coping", "#6e685e", rough=0.85)
    rock = material("portal rock", "#77716a", rough=0.95)
    rock2 = material("portal rock dark", "#635e58", rough=0.95)
    letters = material("date stone letters", "#3e3a34", rough=0.8)
    along_u = edge in ("XM", "XP")
    out = 1 if edge in ("XP", "YP") else -1
    b = _boxer(m, along_u)
    # The face stands across the track a little in from the tile's middle, towards the hill.
    y = -out * 0.1
    f = y + out * 0.06
    hw = 0.42
    b(-hw, hw, y - 0.06, y + 0.06, 0.0, 3.2, stone, 0.004)
    b(-hw - 0.001, hw + 0.001, min(f, f + out * 0.002), max(f, f + out * 0.002), 0.0, 3.2, joint)
    # Courses of rock faced ashlar, alternating shades.
    for k in range(10):
        z0 = k * 0.32
        mat = stone if k % 2 == 0 else stone2
        b(-hw - 0.003, hw + 0.003, min(f, f + out * 0.004), max(f, f + out * 0.004), z0 + 0.02, z0 + 0.3, mat)
    # Impost bands at the spring line.
    for side in (-1, 1):
        x0, x1 = sorted((side * 0.24, side * 0.37))
        b(x0, x1, min(f, f + out * 0.016), max(f, f + out * 0.016), 1.5, 1.62, cut)
    b(-hw - 0.02, hw + 0.02, y - 0.08, y + 0.08, 3.2, 3.4, cap, 0.003)
    # The opening, dark: a half ellipse on straight sides, smooth.
    a, rz, spring = 0.24, 1.4, 1.6
    opening = [(-a, 0.0), (a, 0.0)] + _ellipse(a, rz, spring, 32)
    _prism(m, along_u, opening, min(f - out * 0.115, f + out * 0.008), max(f - out * 0.115, f + out * 0.008), dark)
    # Ring of voussoirs, the keystone standing proud, and quoins up the jambs.
    n = 15
    ring_in, ring_out = (a, rz), (a + 0.065, rz + 0.17)
    for i in range(n):
        a0, a1 = math.pi * i / n, math.pi * (i + 1) / n
        key = i == n // 2
        ro = (ring_out[0] + 0.015, ring_out[1] + 0.1) if key else ring_out
        g = 0.006
        pts = [(ring_in[0] * math.cos(a0 + g), spring + ring_in[1] * math.sin(a0 + g)),
               (ro[0] * math.cos(a0 + g), spring + ro[1] * math.sin(a0 + g)),
               (ro[0] * math.cos(a1 - g), spring + ro[1] * math.sin(a1 - g)),
               (ring_in[0] * math.cos(a1 - g), spring + ring_in[1] * math.sin(a1 - g))]
        pts = list(reversed(pts))
        depth = 0.03 if key else 0.018
        _prism(m, along_u, pts, min(f - out * 0.02, f + out * depth), max(f - out * 0.02, f + out * depth), cut)
    for side in (-1, 1):
        for k in range(5):
            z0 = k * 0.32
            wide = 0.09 if k % 2 == 0 else 0.06
            x0, x1 = sorted((side * a, side * (a + wide)))
            b(x0, x1, min(f, f + out * 0.014), max(f, f + out * 0.014), z0 + 0.015, z0 + 0.3, cut)
    # Date stone in a raised block of the parapet over the keystone.
    b(-0.11, 0.11, y - 0.06, y + 0.06, 3.2, 3.5, stone, 0.003)
    b(-0.13, 0.13, y - 0.08, y + 0.08, 3.5, 3.6, cap, 0.003)
    b(-0.085, 0.085, min(f, f + out * 0.03), max(f, f + out * 0.03), 3.22, 3.47, cut, 0.002)
    _label(m, along_u, "1914", 0.0, f + out * 0.032, 3.345, 0.05, letters, out)
    # Wing walls splayed out from the face, their tops stepping down, coped.
    for side in (-1, 1):
        x0, x1 = side * hw, side * 0.5
        ya, yb = f - out * 0.06, f + out * 0.12
        steps = 4
        for k in range(steps):
            t0, t1 = k / steps, (k + 1) / steps
            top = 3.2 - 0.85 * t1
            pa = (x0 + (x1 - x0) * t0, ya + (yb - ya) * t0)
            pb = (x0 + (x1 - x0) * t1, ya + (yb - ya) * t1)
            nx, ny = (pb[1] - pa[1]), -(pb[0] - pa[0])
            ln = math.hypot(nx, ny)
            nx, ny = nx / ln * 0.035, ny / ln * 0.035
            plan = [(pa[0] - nx, pa[1] - ny), (pb[0] - nx, pb[1] - ny), (pb[0] + nx, pb[1] + ny), (pa[0] + nx, pa[1] + ny)]
            _upright(m, along_u, plan, 0.0, top, stone if k % 2 == 0 else stone2)
            plan = [(pa[0] - nx * 1.4, pa[1] - ny * 1.4), (pb[0] - nx * 1.4, pb[1] - ny * 1.4), (pb[0] + nx * 1.4, pb[1] + ny * 1.4), (pa[0] + nx * 1.4, pa[1] + ny * 1.4)]
            _upright(m, along_u, plan, top, top + 0.12, cap)
    # Rock of the hill over and behind the face.
    rng = random.Random(7)
    for k in range(14):
        cx = rng.uniform(-0.45, 0.45)
        cy = y - out * rng.uniform(0.1, 0.38)
        r = rng.uniform(0.07, 0.14)
        z0 = 3.0 + rng.uniform(0.0, 0.25)
        h = rng.uniform(0.35, 0.8) * (1.0 - abs(cx) * 0.6)
        sides = 6
        rot = rng.uniform(0, math.pi)
        bot = []
        tp = []
        for i in range(sides):
            ang = rot + 2 * math.pi * i / sides
            rr = r * rng.uniform(0.75, 1.1)
            bot.append((cx + rr * math.cos(ang), cy + rr * math.sin(ang), z0))
            tp.append((cx + 0.5 * rr * math.cos(ang + 0.3), cy + 0.5 * rr * math.sin(ang + 0.3), z0 + h * rng.uniform(0.8, 1.1)))
        m.wedge([_swap(along_u, p) for p in bot], [_swap(along_u, p) for p in tp], rock if k % 3 else rock2)


# ---- Snow sheds ----------------------------------------------------------------------------------------------


def _log(m, along_u, y0, y1, x, z, r, mat):
    """A log along the track."""
    if along_u:
        m.tube(y0, y1, x, z, r, mat, segments=10)
    else:
        m.tube_x(y0, y1, x, z, r, mat, segments=10)


def _header(m, along_u, x0, x1, y, z, r, mat):
    """A log across the track."""
    if along_u:
        m.tube_x(x0, x1, y, z, r, mat, segments=10)
    else:
        m.tube(x0, x1, y, z, r, mat, segments=10)


def _roof_z(x):
    """The shed roof's top, from 3.03 over the uphill wall down to 2.55 past the posts."""
    return 3.03 - (x + 0.37) * (0.48 / 0.71)


def shed(m, along_u, era="timber"):
    """A snow shed over the track: a solid wall on the uphill side (-x), posts on the other, and a roof sloping
    down off the hill so slides pass over it. timber: the Selkirk and Fraser Canyon log crib sheds; concrete:
    the sheds built from the 1950s."""
    if era == "concrete":
        _concrete_shed(m, along_u)
        return
    b = _boxer(m, along_u)
    log = material("shed log", "#6e5236", rough=0.9)
    log2 = material("shed log dark", "#5a4330", rough=0.9)
    fill = material("shed rock fill", "#6e6a62", rough=1.0)
    timber = material("shed timber", "#7a5c3e", rough=0.9)
    roof = material("shed roof", "#5a4634", rough=0.85)
    batten = material("shed roof batten", "#4a3a2a", rough=0.9)
    # The crib: logs along the face and back, header logs through it every eighth of a tile, rock fill between.
    b(-0.355, -0.265, -0.5, 0.5, 0.0, 2.86, fill)
    r = 0.019
    dz = 2 * r / rig.RISE
    n = int(2.86 / dz)
    for k in range(n):
        z = r / rig.RISE + k * dz
        if k % 2 == 0:
            for x in (-0.262, -0.358):
                _log(m, along_u, -0.5, 0.5, x, z, r, log if k % 4 == 0 else log2)
        else:
            for j in range(8):
                yy = -0.5 + (j + 0.5) / 8
                _header(m, along_u, -0.38, -0.24, yy, z, r, log)
    # Downhill bents: posts on a sill, a plate along their tops, knee braces up to each roof beam.
    b(0.245, 0.305, -0.5, 0.5, 0.0, 0.06, timber, 0.003)
    for yy in (-0.375, -0.125, 0.125, 0.375):
        _timber(m, along_u, (0.275, yy, 0.06), (0.275, yy, 2.48), timber, w=0.042)
        _timber(m, along_u, (0.275, yy, 1.85), (0.13, yy, _roof_z(0.13) - 0.14), timber, w=0.026)
    b(0.245, 0.305, -0.5, 0.5, 2.44, 2.54, timber, 0.003)
    # Heavy roof beams from the crib over the plate, then plank decking on top, battens down the slope.
    for j in range(8):
        yy = -0.5 + (j + 0.5) / 8
        _timber(m, along_u, (-0.37, yy, _roof_z(-0.37) - 0.1), (0.34, yy, _roof_z(0.34) - 0.1), timber, w=0.034)
    _prism(m, along_u, [(-0.39, _roof_z(-0.39) - 0.06), (0.36, _roof_z(0.36) - 0.06), (0.36, _roof_z(0.36)), (-0.39, _roof_z(-0.39))],
           -0.5, 0.5, roof)
    for j in range(9):
        yy = -0.5 + j / 8
        yy = max(-0.495, min(0.495, yy))
        _beam(m, along_u, (-0.39, yy, _roof_z(-0.39) + 0.012), (0.36, yy, _roof_z(0.36) + 0.012), 0.01, 0.02, batten,
              up=(0.0, 1.0, 0.0))


def _concrete_shed(m, along_u):
    b = _boxer(m, along_u)
    concrete = material("shed concrete", "#a39f96", rough=0.85)
    lift = material("shed concrete lift", "#8c887f", rough=0.9)
    stain = material("shed concrete stain", "#7e7a72", rough=0.95)
    gravel = material("shed roof gravel", "#77736a", rough=1.0)
    # The uphill wall, battered on its back, with form lift lines and a footing.
    _prism(m, along_u, [(-0.4, 0.1), (-0.26, 0.1), (-0.26, 2.9), (-0.33, 2.9)], -0.5, 0.5, concrete)
    b(-0.42, -0.25, -0.5, 0.5, 0.0, 0.12, concrete)
    for z in (0.7, 1.4, 2.1):
        b(-0.262, -0.258, -0.5, 0.5, z, z + 0.025, lift)
    # Weep holes along the foot of the wall.
    for j in range(4):
        yy = -0.375 + j * 0.25
        b(-0.262, -0.255, yy - 0.012, yy + 0.012, 0.3, 0.36, stain)
    # Downhill columns, each with haunches into a beam along their tops.
    for yy in (-0.375, -0.125, 0.125, 0.375):
        b(0.25, 0.31, yy - 0.035, yy + 0.035, 0.0, 2.4, concrete, 0.003)
        b(0.24, 0.32, yy - 0.05, yy + 0.05, 0.0, 0.1, concrete)
        _prism(m, along_u, [(0.25, 2.0), (0.25, 2.4), (0.12, 2.4)], yy - 0.03, yy + 0.03, concrete)
    b(0.24, 0.33, -0.5, 0.5, 2.36, 2.54, concrete, 0.003)
    # Ribs under the slab across the track.
    for j in range(5):
        yy = -0.5 + j * 0.25
        yy = max(-0.47, min(0.47, yy))
        _prism(m, along_u, [(-0.26, _roof_z(-0.26) - 0.28), (0.3, _roof_z(0.3) - 0.24), (0.3, _roof_z(0.3) - 0.1), (-0.26, _roof_z(-0.26) - 0.1)],
               yy - 0.025, yy + 0.025, concrete)
    # The roof slab sloping off the hill, gravel on top, a curb along its low edge with streaks down the fascia.
    _prism(m, along_u, [(-0.4, _roof_z(-0.4) - 0.12), (0.36, _roof_z(0.36) - 0.12), (0.36, _roof_z(0.36)), (-0.4, _roof_z(-0.4))],
           -0.5, 0.5, concrete)
    _prism(m, along_u, [(-0.4, _roof_z(-0.4)), (0.33, _roof_z(0.33)), (0.33, _roof_z(0.33) + 0.03), (-0.4, _roof_z(-0.4) + 0.05)],
           -0.5, 0.5, gravel)
    b(0.33, 0.37, -0.5, 0.5, _roof_z(0.36) - 0.12, _roof_z(0.36) + 0.08, concrete)
    for j in range(6):
        yy = -0.42 + j * 0.17
        b(0.37, 0.372, yy - 0.01, yy + 0.01, _roof_z(0.36) - 0.12, _roof_z(0.36) + 0.06, stain)
    # Slide debris lying on the roof.
    rng = random.Random(3)
    rock = material("shed debris", "#6a665e", rough=1.0)
    for k in range(9):
        cx = rng.uniform(-0.36, 0.26)
        cy = rng.uniform(-0.45, 0.45)
        r = rng.uniform(0.02, 0.045)
        z0 = _roof_z(cx) + 0.02
        bot, tp = [], []
        for i in range(5):
            ang = 2 * math.pi * i / 5 + k
            bot.append((cx + r * math.cos(ang), cy + r * math.sin(ang), z0))
            tp.append((cx + 0.5 * r * math.cos(ang + 0.4), cy + 0.5 * r * math.sin(ang + 0.4), z0 + r / rig.RISE * 0.8))
        m.wedge([_swap(along_u, p) for p in bot], [_swap(along_u, p) for p in tp], rock)
