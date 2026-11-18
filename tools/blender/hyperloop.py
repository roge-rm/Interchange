"""Hyperloop pods and their tube.

The tube is a sealed steel tube on slim pylons, its axis AXIS steps up and R tiles round, with a band of dark glass
along its crown and a joint ring every half tile. Pieces are built in the tile's own terms as maglev.guide is, on
ground of a shape, and curves as curves.track has them. A bridge of tube is the same tube on a slim box girder, on the
concrete bridge's piers; a bore shows only at its heads, as an entry hall the tube runs into.

Pods are built as maglev.py's trains are: facing +y, x across, y along, z in steps, drawn at full height from the
ground. Each pod is a round capsule with a nose at both ends, narrower than the tube, so on the map a pod shows over
the tube with the tube's walls round it. Pods cast no shadow on the ground, as they run inside the tube.
"""
import math

import bmesh

import parts
import rig
from parts import material
from steam import LETTERS
from diesel import alto_paint

# ---- The tube's section ---------------------------------------------------------------------------------------

# The axis in steps over the ground, the radius in tiles, and the same radius in steps.
AXIS = 1.2
R = 0.1
RZ = R / rig.RISE
# Segments round the tube, and how far either side of the crown (in radians) the glass band reaches.
SEGMENTS = 28
GLASS = math.radians(38)
# The pylon's column, in tiles round at the foot and top.
COLUMN = (0.024, 0.017)
# render_scenery's BUILDING_RISE: the station and hall are lowered by it when placed, so their tube is drawn taller.
BUILDING_RISE = 0.75


def _mats():
    return {
        "shell": material("tube shell", "#b9c0c6", metal=0.55, rough=0.32, clearcoat=0.2),
        "glass": material("tube glass", "#33506a", metal=0.2, rough=0.12, clearcoat=0.6),
        "joint": material("tube joint", "#6c747c", metal=0.6, rough=0.35),
        "pylon": material("tube pylon", "#d2d0ca", rough=0.75),
        "foot": material("tube pylon foot", "#a8a49c", rough=0.85),
    }


def _section(r=R, axis=AXIS, n=SEGMENTS, squash=1.0):
    """The tube's outline round from its foot: (across in tiles, up in steps), its z [squash]ed for a model that's
    lowered when placed, so it still shows round."""
    rz = r / rig.RISE
    return [(r * math.sin(2 * math.pi * k / n), (axis - rz * math.cos(2 * math.pi * k / n)) * squash) for k in range(n)]


def _glass_part(n=SEGMENTS):
    """For a section of n points from the foot: which faces are on the crown's glass band."""
    def part(k):
        mid = 2 * math.pi * (k + 0.5) / n
        return "glass" if abs(mid - math.pi) < GLASS else "shell"
    return part


def _skin(m, rings, mats, part=None, caps=True):
    """A body through rings of model points (x, y, z in steps), each the same length, shaded smooth; part(k) names
    the material in mats for the faces from ring point k to the next, else the first."""
    keys = list(mats)
    bm = bmesh.new()
    vs = [[bm.verts.new((x, y, m.z(z) * rig.RISE)) for x, y, z in ring] for ring in rings]
    n = len(vs[0])
    for a, b in zip(vs, vs[1:]):
        for k in range(n):
            j = (k + 1) % n
            f = bm.faces.new((a[k], a[j], b[j], b[k]))
            f.material_index = keys.index(part(k)) if part else 0
            f.smooth = True
    if caps:
        for ring in (vs[0], list(reversed(vs[-1]))):
            f = bm.faces.new(ring)
            f.material_index = keys.index(part(0)) if part else 0
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    obj = m._mesh("skin", bm, None)
    for k in keys:
        obj.data.materials.append(mats[k])
    return obj


# ---- Pieces on the ground ------------------------------------------------------------------------------------


def _line_rings(a, b, offs, section, ts, ends=None, k=1.0, drop=0.0):
    """Rings of section along the straight from a to b at each of ts, over ground of offs, in Blender's (x, y, z)."""
    import scenery
    length = math.hypot(b[0] - a[0], b[1] - a[1])
    n = (-(b[1] - a[1]) / length, (b[0] - a[0]) / length)
    rings = []
    for t in ts:
        u, v = a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t
        d = n
        s = 1.0
        if ends and t in (0.0, 1.0):
            d = ends[0 if t == 0.0 else 1]
            s = 1.0 / k
        h = scenery._height(offs, u, v)
        rings.append([(v + d[1] * x * s, u + d[0] * x * s, h + z - drop) for x, z in section])
    return rings


def _pylon(m, p, h, mats, d=None):
    """A slim pylon on the ground at tile point p, h steps up: a footing, a tapered column and a cradle up round the
    tube's foot, and a clamp ring round the tube. d is the way along the tube."""
    u, v = p
    m.box(v - 0.04, v + 0.04, u - 0.04, u + 0.04, h - 0.1, h + 0.06, mats["foot"], bevel=0.006)
    top = h + AXIS - RZ * 0.7
    m.upright(v, u, h + 0.06, top, COLUMN[0], COLUMN[1], mats["pylon"], segments=14)
    if d is None:
        return
    nx, ny = -d[1], d[0]
    # The cradle: two arms from the column's top out and up to the tube's sides.
    for s in (-1, 1):
        ax, ay = u + nx * s * 0.075, v + ny * s * 0.075
        m.rod((v, u, top - 0.08), (ay, ax, h + AXIS - RZ * 0.35), 0.009, mats["pylon"], segments=8)
    ring = _section(R + 0.006)
    rings = []
    for t in (-0.016, 0.016):
        cu, cv = u + d[0] * t, v + d[1] * t
        rings.append([(cv + ny * x, cu + nx * x, h + z) for x, z in ring])
    _skin(m, rings, {"joint": mats["joint"]})


def tube(m, piece, shape, drop=0.0, pylon=True):
    """A piece of tube on ground of [shape]: the tube with its glass crown and two joint rings, on a pylon at its
    middle. Its axis [drop] steps low, so where tubes cross on a tile one shows clean over the other."""
    import scenery
    offs = [(shape >> i) & 1 for i in range(4)]
    ea, eb = scenery.PIECE_EDGES[piece]
    a = scenery.EDGE_MID[ea]
    b = scenery.EDGE_MID[eb]
    length = math.hypot(b[0] - a[0], b[1] - a[1])
    n = (-(b[1] - a[1]) / length, (b[0] - a[0]) / length)
    ends = (scenery._edge_dir(ea, n), scenery._edge_dir(eb, n))
    k = abs(ends[0][0] * n[0] + ends[0][1] * n[1])
    mats = _mats()
    _skin(m, _line_rings(a, b, offs, _section(), [0.0, 1.0], ends, k, drop), mats, _glass_part())
    joint = _section(R + 0.005)
    for tj in (0.18, 0.82):
        dt = 0.012 / length
        _skin(m, _line_rings(a, b, offs, joint, [tj - dt, tj + dt], drop=drop), {"joint": mats["joint"]})
    d = ((b[0] - a[0]) / length, (b[1] - a[1]) / length)
    mid = ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
    if pylon:
        _pylon(m, mid, scenery._height(offs, *mid) - drop, mats, d)


def tube_set(m, mask):
    """Every piece in [mask] on one level tile, the tubes running through each other where they meet."""
    import scenery
    # X and Y share the tile's middle, so only the first of them gets a pylon there.
    middle = False
    for i, piece in enumerate(scenery.PIECES):
        if mask & (1 << i):
            tube(m, piece, 0, drop=0.004 * i, pylon=not (middle and piece in ("X", "Y")))
            middle = middle or piece in ("X", "Y")


def curve_tube(m, curve):
    """A curved piece of tube on level ground, along a curves.Curve: the tube, its glass crown and a pylon at its middle."""
    import curves
    mats = _mats()
    body = curves._sweep(m, curve, _section(), mats["shell"])
    # The crown's band, a hair proud of the shell, its chord face hidden inside.
    n = 12
    crown = [((R + 0.002) * math.sin(math.pi + GLASS * (2 * i / n - 1)), AXIS - (R + 0.002) / rig.RISE * math.cos(math.pi + GLASS * (2 * i / n - 1)))
             for i in range(n + 1)]
    glass = curves._sweep(m, curve, crown, mats["glass"])
    for obj in (body, glass):
        obj.data.polygons.foreach_set("use_smooth", [True] * len(obj.data.polygons))
    p, d = curve.at(curve.t_of(curve.length / 2))
    _pylon(m, p, 0.0, mats, d)


def tube_end(m, edge):
    """A sealed cap where a tube stops at view edge [edge]: a collar and a rounded dome over the tube's end, with a red
    lamp on its face."""
    import scenery
    mats = _mats()
    red = material("buffer lamp", "#e0302a", rough=0.3)
    ex, ey = scenery.EDGE_MID[edge]
    out = {"XM": (-1, 0), "XP": (1, 0), "YM": (0, -1), "YP": (0, 1)}[edge]
    nx, ny = -out[1], out[0]
    r = R + 0.012
    ring = _section(r)

    def at(back, scale=1.0, lift=0.0):
        cu, cv = ex - out[0] * back, ey - out[1] * back
        return [(cv + ny * x * scale, cu + nx * x * scale, AXIS + (z - AXIS) * scale + lift) for x, z in ring]

    _skin(m, [at(0.12), at(0.05)], {"joint": mats["joint"]})
    dome = [at(0.05 - 0.045 * math.sin(math.pi / 2 * i / 8), math.cos(math.pi / 2 * i / 8) * 0.98 + 0.02) for i in range(9)]
    _skin(m, dome, {"shell": mats["shell"]})
    c = (ex - out[0] * 0.006, ey - out[1] * 0.006)
    m.box(c[1] - 0.012, c[1] + 0.012, c[0] - 0.012, c[0] + 0.012, AXIS - 0.06, AXIS + 0.06, red, bevel=0.004)


# ---- Portals, the hall, bridges and bores (built round the tile's middle) ------------------------------------


def _swap(along_u, x, y, z):
    return (x, y, z) if along_u else (y, x, z)


def _straight(m, along_u, y0, y1, mats, part=None, squash=1.0, lift=None, r=R, glass=True):
    """The tube along the tile from y0 to y1 round its middle; lift(y) raises it along the way."""
    section = _section(r, squash=squash)
    rings = []
    for y in (y0, y1):
        h = lift(y) if lift else 0.0
        rings.append([_swap(along_u, x, y, z + h) for x, z in section])
    return _skin(m, rings, mats, _glass_part() if glass else part)


def station(m, along_u, canopy=True):
    """A portal platform tile: a glass tube between platforms raised level with the pods' floor, scenery.station's
    modern platforms and shelter on them, on walls down to the ground. Built to be lowered by BUILDING_RISE."""
    import scenery
    b = m if along_u else scenery._Turned(m)
    mats = _mats()
    wall = material("platform wall", "#b4b0a6", rough=0.85)
    rib = material("tube rib", "#eef0f0", metal=0.4, rough=0.3)
    clear = material("portal glass", "#5f8fa8", metal=0.1, rough=0.06, clearcoat=0.8)
    lift = 0.71
    squash = 1 / BUILDING_RISE
    for side in (-1, 1):
        x0, x1 = sorted((side * 0.24, side * 0.5))
        b.box(x0, x1, -0.5, 0.5, 0.0, lift, wall)
    # The tube through the station is glass all round, on white ribs, its pylons in the walls' shadow.
    _straight(m, along_u, -0.5, 0.5, {"glass": clear}, squash=squash, glass=False)
    for y in (-0.4, -0.13, 0.13, 0.4):
        section = _section(R + 0.006, squash=squash)
        _skin(m, [[_swap(along_u, x, yy, z) for x, z in section] for yy in (y - 0.01, y + 0.01)], {"rib": rib})
    for y in (-0.27, 0.27):
        b.box(-0.03, 0.03, y - 0.03, y + 0.03, 0.0, (AXIS - RZ * 0.8) * squash, mats["pylon"])
    m._shift = lift
    scenery.station(m, along_u, canopy=canopy, era=2)
    m._shift = None


def depot(m):
    """The tube's pod hall, its doors facing +y: a long low hall in white panels with rounded eaves, solar panels on
    its roof, a teal band round it and a round port in the front where the tube runs in. Built to be lowered by
    BUILDING_RISE."""
    import houses
    import scenery
    squash = 1 / BUILDING_RISE
    mats = _mats()
    clad = material("pod hall cladding", "#e6e8ea", rough=0.4, metal=0.15)
    seam = material("pod hall seams", "#b8bec2", rough=0.5, metal=0.2)
    plinth = material("pod hall plinth", "#8e8c86", rough=0.9)
    trim = material("pod hall band", "#1c8c9a", rough=0.4)
    solar = material("solar panels", "#1a2a44", metal=0.4, rough=0.15, clearcoat=0.6)
    dark = material("port dark", "#121416", rough=1.0)
    glass = houses.glass()
    dx, y0, y1 = scenery.DX, scenery.DY0, scenery.DY1
    eave = 2.3
    m.box(-dx, dx, y0, y1, 0, 0.25, plinth, 0.003)
    m.box(-dx, dx, y0, y1, 0.25, eave, clad, 0.004)
    # Rounded eaves: a quarter round each side, then the flat roof.
    n = 6
    rr = 0.06
    for side in (-1, 1):
        for i in range(n):
            a0, a1 = math.pi / 2 * i / n, math.pi / 2 * (i + 1) / n
            xa, za = side * (dx - rr + rr * math.cos(a0)), eave + rr / rig.RISE * math.sin(a0)
            xb, zb = side * (dx - rr + rr * math.cos(a1)), eave + rr / rig.RISE * math.sin(a1)
            pts = [(xa, za), (xb, zb), (side * (dx - rr), zb), (side * (dx - rr), za)]
            m.wedge([(x, y0, z) for x, z in pts], [(x, y1, z) for x, z in pts], clad)
    top = eave + rr / rig.RISE
    m.box(-dx + rr, dx - rr, y0, y1, eave, top, clad)
    # Two rows of solar panels on the roof, tilted a little.
    for x0, x1 in ((-dx + 0.08, -0.04), (0.04, dx - 0.08)):
        for k in range(4):
            ya = y0 + 0.06 + (y1 - y0 - 0.12) * k / 4
            yb = ya + (y1 - y0 - 0.12) / 4 - 0.02
            m.wedge([(x0, ya, top), (x1, ya, top), (x1, yb, top), (x0, yb, top)],
                    [(x0, ya, top + 0.08), (x1, ya, top + 0.08), (x1, yb, top + 0.2), (x0, yb, top + 0.2)], solar)
    for k in range(1, 8):
        y = y0 + (y1 - y0) * k / 8
        for side in (-1, 1):
            x = side * (dx + 0.001)
            m.box(x - 0.002, x + 0.002, y - 0.004, y + 0.004, 0.25, eave, seam)
    for d in (-1, 1):
        houses.on_wall(m, "x", d * dx, d, y0 + 0.06, y1 - 0.06, 0, 0.008, 1.5, 1.85, glass)
        a, c = sorted((d * dx, d * (dx + 0.01)))
        m.box(a, c, y0, y1, 2.0, 2.1, trim)
    # The front: the end wall, the teal band across it and the round port the tube runs into.
    m.box(-dx, dx, y1 - 0.004, y1 + 0.006, 0.25, eave, clad)
    m.box(-dx, dx, y1 + 0.006, y1 + 0.012, 2.0, 2.1, trim)
    port = _section(R + 0.03, squash=squash)
    _skin(m, [[(x, y, z) for x, z in port] for y in (y1 - 0.002, y1 + 0.014)], {"dark": dark})
    rim = _section(R + 0.055, squash=squash)
    inner = _section(R + 0.03, squash=squash)
    for k in range(SEGMENTS):
        j = (k + 1) % SEGMENTS
        pts = [inner[k], inner[j], rim[j], rim[k]]
        m.wedge([(x, y1 + 0.006, z) for x, z in pts], [(x, y1 + 0.03, z) for x, z in pts], trim)
    _straight(m, True, y1, 0.5, mats, squash=squash)
    m.box(-0.05, 0.05, y1 + 0.004, y1 + 0.016, (AXIS + RZ + 0.25) * squash, (AXIS + RZ + 0.33) * squash,
          material("wall lamp", "#fff4d8", rough=0.3))


def _girder(along_u, m, mat):
    """The deck's slim box girder under the tube, (x, z) round from the soffit."""
    import spans
    spans._prism(m, along_u, [(-0.06, -0.55), (0.06, -0.55), (0.075, 0.45), (-0.075, 0.45)], -0.5, 0.5, mat)


def deck(m, along_u):
    """A tile of tube bridge: the tube on saddles over a slim box girder that spans the concrete piers."""
    import spans
    mats = _mats()
    concrete = material("tube girder", "#c8c5be", rough=0.8)
    _girder(along_u, m, concrete)
    b = spans._boxer(m, along_u)
    for y in (-0.25, 0.25):
        b(-0.06, 0.06, y - 0.025, y + 0.025, 0.45, AXIS - RZ * 0.6, mats["pylon"])
    _straight(m, along_u, -0.5, 0.5, mats)
    joint = _section(R + 0.005)
    for y in (-0.25, 0.25):
        _skin(m, [[_swap(along_u, x, yy, z) for x, z in joint] for yy in (y - 0.014, y + 0.014)], {"joint": mats["joint"]})
    drip = material("deck drip edge", "#8e8a82", rough=0.9)
    for side in (-1, 1):
        x0, x1 = sorted((side * 0.058, side * 0.063))
        b(x0, x1, -0.5, 0.5, -0.55, -0.48, drip)


def ramp(m, edge):
    """A tube bridge's head on flat ground: the tube climbing a step across the tile to the deck at view edge [edge],
    on two pylons, with a seat for the deck's girder at the inner end."""
    import spans
    along_u = edge in ("XM", "XP")
    d = 1 if edge in ("XP", "YP") else -1
    mats = _mats()

    def lift(y):
        return d * y + 0.5

    _straight(m, along_u, -0.5, 0.5, mats, lift=lift)
    b = spans._boxer(m, along_u)
    for y in (-0.22, 0.18):
        yy = d * y
        h = lift(yy)
        b(-0.04, 0.04, yy - 0.04, yy + 0.04, 0.0, 0.08, mats["foot"], 0.004)
        x, y_, _ = _swap(along_u, 0.0, yy, 0.0)
        m.upright(x, y_, 0.06, h + AXIS - RZ * 0.7, COLUMN[0], COLUMN[1], mats["pylon"], segments=14)
    yi = d * 0.5
    b(-0.1, 0.1, *sorted((yi, yi - d * 0.1)), 0.0, 1.0 + AXIS - RZ * 0.9, mats["pylon"], 0.004)
    b(-0.14, 0.14, *sorted((yi + d * 0.01, yi - d * 0.13)), 0.0, 0.08, mats["foot"])


def portal(m, edge):
    """A bore's head: an entry hall on the inner half of the tile that the tube runs into, opening towards view edge
    [edge]: a concrete block with a round port and collar where the tube enters, a teal band, a vent on the roof and
    banks of earth along its sides."""
    import spans
    along_u = edge in ("XM", "XP")
    out = 1 if edge in ("XP", "YP") else -1
    face = material("bore hall concrete", "#cbc8c1", rough=0.8)
    trim = material("pod hall band", "#1c8c9a", rough=0.4)
    vent = material("bore vent", "#5c6268", metal=0.5, rough=0.4)
    dark = material("port dark", "#121416", rough=1.0)
    earth = material("bore bank", "#6e7a4a", rough=0.95)
    mats = _mats()
    b = spans._boxer(m, along_u)
    yf = -out * 0.06
    yb = -out * 0.5
    hw, top = 0.34, 2.3
    b(-hw, hw, *sorted((yf, yb)), 0.0, top, face, 0.004)
    b(-hw - 0.01, hw + 0.01, *sorted((yf, yb)), top, top + 0.1, face, 0.003)
    b(-hw - 0.003, hw + 0.003, *sorted((yf + out * 0.003, yb)), 2.0, 2.08, trim)
    # The port: a dark disc in a collar standing out from the face, the tube running into it.
    port = _section(R + 0.012)
    rim = _section(R + 0.045)
    _skin(m, [[_swap(along_u, x, y, z) for x, z in port] for y in (yf - out * 0.01, yf + out * 0.004)], {"dark": dark})
    for k in range(SEGMENTS):
        j = (k + 1) % SEGMENTS
        pts = [port[k], port[j], rim[j], rim[k]]
        m.wedge([_swap(along_u, x, yf, z) for x, z in pts], [_swap(along_u, x, yf + out * 0.04, z) for x, z in pts], trim)
    _straight(m, along_u, yf, out * 0.5, mats)
    # A louvred vent on the roof.
    yc = (yf + yb) / 2
    b(-0.12, 0.12, yc - 0.1, yc + 0.1, top + 0.1, top + 0.45, vent, 0.004)
    for k in range(5):
        z = top + 0.16 + 0.06 * k
        b(-0.125, 0.125, yc - 0.105, yc + 0.105, z, z + 0.02, face)
    # Banks of earth against the hall's sides, falling away from it.
    for side in (-1, 1):
        x0, x1 = side * hw, side * 0.5
        plan = [(x0, yf), (x0, yb), (x1, yb), (x1, yf + out * 0.04)]
        zs = [1.6, 1.8, 0.4, 0.1]
        m.wedge([spans._swap(along_u, (px, py, 0.0)) for px, py in plan],
                [spans._swap(along_u, (px, py, z)) for (px, py), z in zip(plan, zs)], earth)


# ---- Pods -----------------------------------------------------------------------------------------------------

# Each pod's colours: body, the light band along its side, the window band, the nose caps and the belly.
COLOURS = {
    "vela": {"body": "#f1f3f4", "band": "#18b4c8", "mask": "#16222c", "cap": "#9aa6ae", "belly": "#8c969c"},
    "photon": {"body": "#323a44", "band": "#f2a22c", "mask": "#0a0e12", "cap": "#c6ccd2", "belly": "#1e242a"},
}
# A pod's half width in tiles, and its middle in steps: on the tube's axis, narrow enough that the tube's walls
# show either side of it once outlined.
POD_HALF = 0.062
POD_AXIS = AXIS
# The nose at each end, (length in tiles, roundness): Photon's longer and finer.
NOSE = {"vela": (0.13, 2.2), "photon": (0.21, 1.55)}


def _pod_mats(name):
    c = COLOURS[name]
    return {k: material(f"{name} {k}", v, rough=0.25 if k == "mask" else 0.35, clearcoat=0.45) for k, v in c.items()}


def pod(m, L, name):
    """A hyperloop pod: a round capsule with a nose at each end, a window band along its sides, a light band under it
    and dark caps on its noses, shaped to slip down the tube. Photon's noses are longer and finer."""
    mats = _pod_mats(name)
    a0, a1 = -L / 2 + 0.004, L / 2 - 0.004
    nose, power = NOSE[name]
    rz = POD_HALF / rig.RISE
    n = 24

    def scale(y):
        """How round the section is at y, 0 at a tip, 1 along the middle."""
        e = min(y - a0, a1 - y)
        if e >= nose:
            return 1.0
        t = 1 - max(e, 0.0) / nose
        return max((1 - t ** power) ** (1 / power), 0.0)

    ys = [a0 + nose * (1 - math.cos(math.pi / 2 * i / 16)) for i in range(17)]
    ys = [a0] + ys[1:] + [a1 - (y - a0) for y in reversed(ys[1:])] + [a1]
    sections = []
    for y in ys:
        s = max(scale(y), 0.04)
        ring = [(POD_HALF * s * math.sin(2 * math.pi * k / n), POD_AXIS - rz * s * math.cos(2 * math.pi * k / n)) for k in range(n)]
        sections.append((y, ring))
    body = m.loft(sections, {"body": mats["body"]}, sharp=50)
    z0, z1 = POD_AXIS - rz, POD_AXIS + rz

    def colour(y, x, z):
        e = min(y - a0, a1 - y)
        up = (z - z0) / (z1 - z0)
        if e < nose * 0.3:
            return "cap"
        if up < 0.16:
            return "belly"
        if 0.3 < up < 0.38 and e > nose * 0.6:
            return "band"
        # The window band, its ends curving down round the noses as a cab's glass does.
        if 0.54 < up < 0.74 and e > nose * 0.75:
            return "mask"
        return None

    alto_paint(m, body, mats, colour)
    lamp = material("pod lamp", "#fff6e0", rough=0.2)
    for y, d in ((a0, 1), (a1, -1)):
        yy = y + d * nose * 0.38
        s = scale(yy)
        for side in (-1, 1):
            m.box(side * POD_HALF * s * 0.55 - 0.006, side * POD_HALF * s * 0.55 + 0.006, yy - 0.006, yy + 0.006,
                  POD_AXIS - 0.04, POD_AXIS + 0.02, lamp)
    for side in (-1, 1):
        m.text(LETTERS, side * (POD_HALF + 0.001), 0.0, POD_AXIS - rz * 0.1, 0.022, mats["band"], side)
