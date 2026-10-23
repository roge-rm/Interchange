"""Horse-drawn road vehicles, in our own colours: an omnibus, a Concord stagecoach, a mail coach, a covered freight
wagon, a Red River cart behind an ox, and a horsecar on tram rails. Built facing +y as roadcars.py builds, L tiles long,
x across, z in steps. The team walks in front, the vehicle behind it.

A horse stands about 0.7 steps at the withers and is about 0.11 tiles long; its legs are a little under half its
height, as a real horse's are.
"""
import math

import bmesh
from mathutils import Vector

import rig
import roadcars
from parts import material
from steam import LETTERS

COATS = ["#6a4428", "#3a2a20", "#8a5a34", "#9a958c"]


def _at(m, x, y, z):
    """A point given in tiles every way as a Blender location, inside keep()."""
    return Vector((x, y, m.z(z / rig.RISE) * rig.RISE))


def _sweep(m, pts, mat, n=10, ref=(1.0, 0.0, 0.0)):
    """A smooth limb along pts, each (x, y, z, a, b) in tiles: an oval ring a across (towards ref) and b the other way
    round the path at each point, its ends closed."""
    bm = bmesh.new()
    rings = []
    for i, (x, y, z, a, b) in enumerate(pts):
        p = _at(m, x, y, z)
        prev = _at(m, *pts[max(i - 1, 0)][:3])
        nxt = _at(m, *pts[min(i + 1, len(pts) - 1)][:3])
        t = (nxt - prev).normalized()
        r = Vector(ref)
        side = (r - r.dot(t) * t).normalized()
        up = t.cross(side)
        rings.append([bm.verts.new(p + side * a * math.cos(2 * math.pi * k / n) + up * b * math.sin(2 * math.pi * k / n))
                      for k in range(n)])
    for r0, r1 in zip(rings, rings[1:]):
        for k in range(n):
            j = (k + 1) % n
            bm.faces.new((r0[k], r0[j], r1[j], r1[k]))
    for ring in (rings[0], rings[-1]):
        bmesh.ops.triangulate(bm, faces=[bm.faces.new(ring)])
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    obj = m._mesh("limb", bm, mat)
    obj.data.polygons.foreach_set("use_smooth", [True] * len(obj.data.polygons))
    return obj


def _blob(m, x, y, z, rx, ry, rz, mat):
    """A smooth egg centred at (x, y, z), its half sizes rx, ry and rz, all in tiles."""
    bm = bmesh.new()
    bmesh.ops.create_uvsphere(bm, u_segments=12, v_segments=8, radius=1.0)
    bmesh.ops.scale(bm, vec=(rx, ry, rz), verts=bm.verts)
    bmesh.ops.translate(bm, vec=_at(m, x, y, z), verts=bm.verts)
    obj = m._mesh("blob", bm, mat)
    obj.data.polygons.foreach_set("use_smooth", [True] * len(obj.data.polygons))
    return obj


def _section(cx, top, bottom, half, n=14, p=2.15):
    """A body's cross-section as (x, z) in tiles: rounded-square, flatter over the back than an ellipse, from bottom to top."""
    zc, rz = (top + bottom) / 2, (top - bottom) / 2
    ring = []
    for k in range(n):
        a = 2 * math.pi * k / n
        c, s = math.cos(a), math.sin(a)
        ring.append((cx + half * math.copysign(abs(c) ** (2 / p), c), zc + rz * math.copysign(abs(s) ** (2 / p), s)))
    return ring


def _body(m, x, y, table, mat):
    """The trunk lofted through table rows of (y, top, bottom, half width), all in tiles from the rump at y."""
    m.loft([(y + yy, [(sx, z / rig.RISE) for sx, z in _section(x, top, bottom, half)]) for yy, top, bottom, half in table],
           {"hide": mat}, sharp=80)


def _along(table, yy):
    """The table's (top, bottom, half width) at yy, between its rows."""
    for (y0, *a), (y1, *b) in zip(table, table[1:]):
        if y0 <= yy <= y1:
            f = (yy - y0) / (y1 - y0)
            return [u + (v - u) * f for u, v in zip(a, b)]
    return table[0][1:] if yy < table[0][0] else table[-1][1:]


def _band(m, x, y, table, yy, width, mat, grow=1.06):
    """A strap round the trunk at yy, width tiles wide: a girth, a backband."""
    rows = []
    for d in (-width / 2, width / 2):
        top, bottom, half = _along(table, yy + d)
        mid = (top + bottom) / 2
        rows.append((y + yy + d, [(sx, z / rig.RISE) for sx, z in _section(x, mid + (top - mid) * grow, mid - (mid - bottom) * grow, half * grow)]))
    m.loft(rows, {"strap": mat})


def _strap(m, x, y, table, z, y0, y1, mat, r=0.0016, behind=False):
    """A strap along each flank z tiles up from y0 to y1, lying on the trunk; behind carries it on round the buttock."""
    for side in (-1, 1):
        pts = []
        for k in range(9):
            yy = y0 + (y1 - y0) * k / 8
            top, bottom, half = _along(table, yy)
            rz = (top - bottom) / 2
            f = max(0.0, 1 - abs((z - (top + bottom) / 2) / rz) ** 2.15) ** (1 / 2.15)
            pts.append((x + side * (half * f + 0.001), y + yy, z, r, r))
        if behind:
            pts = [(x, pts[0][1] - 0.006, z, r, r)] + pts
        _sweep(m, pts, mat, n=6, ref=(0.0, 0.0, 1.0))


def horse(m, x, y, coat, ox=False, harness=True):
    """A draught horse, or an ox, standing at x across with its rump at y, facing +y; it keeps its size in a lowered model."""
    with m.keep():
        return _ox(m, x, y, coat) if ox else _horse(m, x, y, coat, harness)


def _darker(coat, f):
    c = coat.lstrip("#")
    return "#" + "".join(f"{int(int(c[i:i + 2], 16) * f):02x}" for i in (0, 2, 4))


# A draught horse's trunk from the point of the buttock to the breast, (y, top, bottom, half width) in tiles from its
# rump: high round quarters, a short back dipping a little, withers rising into the neck and a deep chest.
HORSE = [(-0.008, 0.112, 0.090, 0.008), (-0.004, 0.124, 0.074, 0.016), (0.006, 0.131, 0.066, 0.020), (0.022, 0.132, 0.062, 0.021),
         (0.042, 0.128, 0.060, 0.021), (0.062, 0.129, 0.057, 0.021), (0.080, 0.136, 0.057, 0.020), (0.092, 0.134, 0.062, 0.018),
         (0.100, 0.124, 0.072, 0.014), (0.105, 0.108, 0.086, 0.007)]

# The ox's: longer and lower, square at the pins, a hump over the withers and the brisket low between the forelegs.
OX = [(-0.008, 0.112, 0.088, 0.010), (-0.003, 0.124, 0.070, 0.020), (0.010, 0.127, 0.058, 0.025), (0.035, 0.124, 0.050, 0.027),
      (0.060, 0.125, 0.048, 0.027), (0.080, 0.136, 0.048, 0.025), (0.092, 0.137, 0.050, 0.022), (0.102, 0.126, 0.052, 0.018),
      (0.109, 0.112, 0.060, 0.011)]


def _horse(m, x, y, coat, harness):
    hide = material(f"coat {coat}", coat, rough=0.8)
    hair = material(f"mane {coat}", _darker(coat, 0.45), rough=0.9)
    dark = material("hoof", "#1e1a18", rough=0.7)
    _body(m, x, y, HORSE, hide)
    # The quarters each side, round over the hind legs.
    for side in (-1, 1):
        _blob(m, x + side * 0.009, y + 0.012, 0.104, 0.011, 0.02, 0.026, hide)
    # The neck, thick at the shoulder and arched up to the poll, the mane along its crest.
    neck = [(0.072, 0.110, 0.016, 0.032), (0.088, 0.130, 0.014, 0.025), (0.101, 0.149, 0.012, 0.017), (0.110, 0.163, 0.0095, 0.012),
            (0.116, 0.170, 0.008, 0.009)]
    _sweep(m, [(x, y + yy, z, a, b) for yy, z, a, b in neck], hide)
    crest = []
    for i, (yy, z, a, b) in enumerate(neck[1:], 1):
        dy, dz = yy - neck[i - 1][0], z - neck[i - 1][1]
        d = math.hypot(dy, dz)
        crest.append((x, y + yy - dz / d * b * 0.92, z + dy / d * b * 0.92, 0.0028, 0.003))
    _sweep(m, crest + [(x, y + 0.119, 0.178, 0.0022, 0.0025), (x, y + 0.122, 0.176, 0.001, 0.001)], hair, n=6)
    # The head from the poll down to a square muzzle, deep at the jaw, ears up.
    _sweep(m, [(x, y + 0.112, 0.175, 0.004, 0.004), (x, y + 0.118, 0.168, 0.0075, 0.010), (x, y + 0.126, 0.152, 0.0065, 0.0085),
               (x, y + 0.133, 0.137, 0.0056, 0.0068), (x, y + 0.137, 0.128, 0.0052, 0.0058), (x, y + 0.138, 0.124, 0.003, 0.003)], hide)
    _blob(m, x, y + 0.118, 0.160, 0.0068, 0.0075, 0.0085, hide)
    for side in (-1, 1):
        _sweep(m, [(x + side * 0.0035, y + 0.115, 0.172, 0.0018, 0.0013), (x + side * 0.0045, y + 0.117, 0.181, 0.0014, 0.001),
                   (x + side * 0.005, y + 0.1185, 0.186, 0.0003, 0.0003)], hide, n=6)
    # Forelegs: forearm muscle under the chest, the knee, a slim cannon and fetlock, a broad hoof.
    for side in (-1, 1):
        lx = x + side * 0.011
        _sweep(m, [(lx, y + 0.086, 0.078, 0.007, 0.0105), (lx, y + 0.088, 0.060, 0.006, 0.0078), (lx, y + 0.089, 0.045, 0.0042, 0.0052),
                   (lx, y + 0.089, 0.038, 0.0046, 0.0054), (lx, y + 0.089, 0.032, 0.0035, 0.0042), (lx, y + 0.0895, 0.015, 0.0035, 0.004),
                   (lx, y + 0.0905, 0.011, 0.0042, 0.0047), (lx, y + 0.092, 0.006, 0.004, 0.0042)], hide)
        _sweep(m, [(lx, y + 0.092, 0.007, 0.0045, 0.0045), (lx, y + 0.093, 0.0, 0.0058, 0.0058)], dark, n=8)
    # Hind legs: the gaskin running back and down from the stifle to the point of the hock, the cannon straight below.
    for side in (-1, 1):
        lx = x + side * 0.012
        _sweep(m, [(lx, y + 0.016, 0.090, 0.0085, 0.013), (lx, y + 0.016, 0.068, 0.0068, 0.0095), (lx, y + 0.010, 0.052, 0.0052, 0.0072),
                   (lx, y + 0.005, 0.040, 0.0042, 0.0058), (lx, y + 0.006, 0.033, 0.0036, 0.0045), (lx, y + 0.007, 0.015, 0.0035, 0.004),
                   (lx, y + 0.008, 0.011, 0.0042, 0.0047), (lx, y + 0.010, 0.006, 0.004, 0.0042)], hide)
        _sweep(m, [(lx, y + 0.010, 0.007, 0.0045, 0.0045), (lx, y + 0.011, 0.0, 0.0058, 0.0058)], dark, n=8)
    # A full tail from the croup, out a little and hanging to the hocks.
    _sweep(m, [(x, y - 0.004, 0.126, 0.0025, 0.0025), (x, y - 0.010, 0.120, 0.0045, 0.004), (x, y - 0.014, 0.104, 0.0055, 0.0045),
               (x, y - 0.015, 0.080, 0.006, 0.0045), (x, y - 0.014, 0.058, 0.005, 0.004), (x, y - 0.013, 0.050, 0.002, 0.002)], hair, n=8)
    if harness:
        _harness(m, x, y, neck)
    return y + 0.09


def _harness(m, x, y, neck):
    """A collar round the base of the neck with brass hames, a backband and girth, traces along the flanks to the breeching
    round the quarters, all dark leather."""
    leather = material("harness", "#241c16", rough=0.75)
    brass = material("hames", "#c9a640", metal=0.5, rough=0.35)
    # The collar: an oval ring tilted back with the neck, sitting against the shoulder.
    cy, cz = y + 0.092, 0.126
    tilt = math.radians(35)
    for mat, r, grow in ((leather, 0.0042, 1.0), (brass, 0.0016, 1.18)):
        pts = []
        for k in range(15):
            a = 2 * math.pi * k / 14
            along = math.sin(a) * 0.022 * grow
            pts.append((x + math.cos(a) * 0.0155 * grow, cy - along * math.sin(tilt), cz + along * math.cos(tilt), r, r))
        if mat is brass:
            pts = pts[1:7]
        _sweep(m, pts, mat, n=6, ref=(0.0, 1.0, 0.0))
    _band(m, x, y, HORSE, 0.062, 0.008, leather)
    _strap(m, x, y, HORSE, 0.100, 0.06, 0.088, leather)
    _strap(m, x, y, HORSE, 0.096, -0.004, 0.06, leather, behind=True)
    _sweep(m, [(x, y + 0.000, 0.126, 0.002, 0.002), (x, y + 0.03, 0.134, 0.002, 0.002), (x, y + 0.062, 0.131, 0.002, 0.002)], leather, n=6,
           ref=(1.0, 0.0, 0.0))


def _ox(m, x, y, coat):
    hide = material(f"coat {coat}", coat, rough=0.85)
    tuft = material(f"tuft {coat}", _darker(coat, 0.4), rough=0.9)
    dark = material("hoof", "#1e1a18", rough=0.7)
    horn = material("horn", "#d8ccb0", rough=0.5)
    wood = material("cart wood", "#8c7c68", rough=0.9)
    _body(m, x, y, OX, hide)
    for side in (-1, 1):
        _blob(m, x + side * 0.012, y + 0.014, 0.094, 0.0125, 0.018, 0.024, hide)
    # A short thick neck carried low and forward, the dewlap hanging under it to the brisket.
    _sweep(m, [(x, y + 0.088, 0.108, 0.015, 0.024), (x, y + 0.104, 0.104, 0.013, 0.019), (x, y + 0.118, 0.100, 0.011, 0.013),
               (x, y + 0.124, 0.098, 0.009, 0.010)], hide)
    _sweep(m, [(x, y + 0.128, 0.084, 0.002, 0.002), (x, y + 0.120, 0.082, 0.0035, 0.006), (x, y + 0.108, 0.074, 0.004, 0.009),
               (x, y + 0.098, 0.062, 0.004, 0.008), (x, y + 0.090, 0.056, 0.002, 0.002)], hide, n=8)
    # The broad head low and forward, a wide muzzle at the end, ears out to the sides under the horns.
    _sweep(m, [(x, y + 0.120, 0.108, 0.006, 0.006), (x, y + 0.126, 0.104, 0.0105, 0.0095), (x, y + 0.134, 0.093, 0.0092, 0.008),
               (x, y + 0.141, 0.082, 0.0085, 0.0068), (x, y + 0.145, 0.076, 0.0085, 0.006), (x, y + 0.147, 0.073, 0.005, 0.004)], hide)
    _blob(m, x, y + 0.145, 0.076, 0.0088, 0.0045, 0.0055, material("muzzle", _darker(coat, 0.55), rough=0.6))
    for side in (-1, 1):
        _sweep(m, [(x + side * 0.008, y + 0.124, 0.106, 0.0016, 0.0016), (x + side * 0.015, y + 0.125, 0.103, 0.0022, 0.001),
                   (x + side * 0.018, y + 0.126, 0.101, 0.0005, 0.0005)], hide, n=6, ref=(0.0, 1.0, 0.0))
        # Horns out from the poll and curving up.
        _sweep(m, [(x + side * 0.006, y + 0.125, 0.112, 0.0034, 0.0034), (x + side * 0.014, y + 0.125, 0.114, 0.0028, 0.0028),
                   (x + side * 0.020, y + 0.128, 0.119, 0.002, 0.002), (x + side * 0.023, y + 0.131, 0.126, 0.0012, 0.0012),
                   (x + side * 0.023, y + 0.133, 0.130, 0.0004, 0.0004)], horn, n=6, ref=(0.0, 1.0, 0.0))
    # Short thick legs, a little bent at the hock, cloven hooves.
    for ly, hock in ((0.088, 0.0), (0.012, 1.0)):
        for side in (-1, 1):
            lx = x + side * 0.014
            _sweep(m, [(lx, y + ly, 0.074, 0.0085, 0.012), (lx, y + ly - 0.004 * hock, 0.052, 0.0068, 0.0085),
                       (lx, y + ly - 0.006 * hock, 0.036, 0.0052, 0.006), (lx, y + ly - 0.003 * hock, 0.026, 0.0045, 0.0052),
                       (lx, y + ly, 0.012, 0.0044, 0.005), (lx, y + ly + 0.001, 0.007, 0.0048, 0.0052)], hide)
            for k in (-1, 1):
                _sweep(m, [(lx + k * 0.0022, y + ly + 0.001, 0.007, 0.0026, 0.004), (lx + k * 0.0024, y + ly + 0.002, 0.0, 0.0032, 0.0052)],
                       dark, n=6)
    # A thin tail from the tail head down to a dark tuft.
    _sweep(m, [(x, y - 0.006, 0.120, 0.0022, 0.0022), (x, y - 0.010, 0.112, 0.0018, 0.0018), (x, y - 0.011, 0.085, 0.0015, 0.0015),
               (x, y - 0.010, 0.062, 0.0014, 0.0014)], hide, n=6)
    _blob(m, x, y - 0.010, 0.056, 0.0035, 0.0035, 0.008, tuft)
    # A wooden yoke over the neck in front of the hump, bows round the neck below it, for the cart's shafts to lash to.
    _sweep(m, [(x - 0.037, y + 0.104, 0.118, 0.0022, 0.0026), (x - 0.012, y + 0.104, 0.124, 0.0034, 0.004), (x, y + 0.104, 0.125, 0.0036, 0.0042),
               (x + 0.012, y + 0.104, 0.124, 0.0034, 0.004), (x + 0.037, y + 0.104, 0.118, 0.0022, 0.0026)], wood, n=8, ref=(0.0, 1.0, 0.0))
    pts = []
    for k in range(9):
        a = math.pi * k / 8
        pts.append((x + math.cos(a) * 0.016, y + 0.106, 0.123 - math.sin(a) * 0.04, 0.0018, 0.0018))
    _sweep(m, pts, wood, n=6, ref=(0.0, 1.0, 0.0))
    return y + 0.1


def team(m, y, pairs=1, coats=(0, 1)):
    """Horses in pairs ahead of y, the front pair furthest on; returns where the pole between them starts."""
    for p in range(pairs):
        for k, side in enumerate((-1, 1)):
            horse(m, side * 0.024, y + p * 0.105, COATS[coats[(k + p) % len(coats)] % len(COATS)])
    return y


def wheel(m, x, y, r, rim, spokes=10, hub_r=None, dish=0.0):
    """A wooden spoked wheel across x at y, r tiles round, its foot on the road. dish sets the hub that far in from the
    rim, the spokes splaying out to it."""
    with m.keep():
        _wheel(m, x, y, r, rim, spokes, hub_r, dish)


def _wheel(m, x, y, r, rim, spokes, hub_r, dish):
    zc = r / rig.RISE
    hx = x - math.copysign(dish, x)

    def at(a, f):
        return (x, y + math.cos(a) * r * f, zc + math.sin(a) * r * f / rig.RISE)
    # The rim as short straight pieces round the wheel.
    n = 20
    for k in range(n):
        m.rod(at(2 * math.pi * k / n, 0.92), at(2 * math.pi * (k + 1) / n, 0.92), r * 0.08, rim, segments=6)
    m.tube_x(hx - 0.007, hx + 0.007, y, zc, hub_r or r * 0.22, rim, segments=12)
    for k in range(spokes):
        a = 2 * math.pi * k / spokes
        m.rod((hx, y, zc), at(a, 0.88), 0.0016 if not dish else 0.0024, rim, segments=5)


def axle(m, y, r, x, body, dark):
    """An axle across at y between wheels r tiles round at x either side, and a spring each side up to the body's
    underside at body steps."""
    hub = r / rig.RISE
    with m.keep():
        m.rod((-x, y, hub), (x, y, hub), 0.004, dark, segments=6)
    if m.z(body) > hub + 0.02:
        for side in (-1, 1):
            m.box(side * x * 0.55 - 0.005, side * x * 0.55 + 0.005, y - 0.012, y + 0.012, m.fixed(hub), body, dark)


def driver(m, y, z, coat="#2a3a5a", standing=False):
    """A driver sat up front, or standing on a platform, hands forward on the reins."""
    cloth = material(f"driver {coat}", coat, rough=0.8)
    with m.keep(z):
        _driver(m, y, z * rig.RISE, cloth, standing)


def _driver(m, y, z, cloth, standing):
    face = material("face", "#d8b090", rough=0.6)
    hat = material("hat", "#1e1e20", rough=0.6)
    trousers = material("trousers", "#26221e", rough=0.8)
    # Legs: down to the footboard ahead of the seat, or straight down to the platform.
    hip = z + (0.052 if standing else 0.0)
    for side in (-1, 1):
        lx = side * 0.0055
        if standing:
            pts = [(lx, y, hip + 0.004, 0.0048, 0.0052), (lx, y, z + 0.024, 0.0038, 0.0042), (lx, y + 0.001, z + 0.003, 0.0034, 0.0036)]
        else:
            pts = [(lx, y - 0.002, z + 0.006, 0.005, 0.005), (lx, y + 0.018, z + 0.006, 0.0042, 0.0045),
                   (lx, y + 0.021, z - 0.012, 0.0036, 0.0038), (lx, y + 0.023, z - 0.028, 0.0034, 0.0034)]
        _sweep(m, pts, trousers, n=8)
        _sweep(m, [(lx, pts[-1][1], pts[-1][2] - 0.002, 0.0034, 0.004), (lx, pts[-1][1] + 0.004, pts[-1][2] - 0.003, 0.0028, 0.0026)],
               hat, n=6)
    # The body in a long coat, a little forward, broad at the shoulders.
    _sweep(m, [(0.0, y - 0.002, hip + 0.0, 0.0085, 0.0075), (0.0, y - 0.001, hip + 0.016, 0.0095, 0.0075),
               (0.0, y + 0.001, hip + 0.034, 0.0115, 0.0072), (0.0, y + 0.002, hip + 0.040, 0.0095, 0.006),
               (0.0, y + 0.002, hip + 0.044, 0.004, 0.004)], cloth, n=10)
    # Arms from the shoulders down to the hands held out over the knees.
    for side in (-1, 1):
        _sweep(m, [(side * 0.011, y + 0.001, hip + 0.036, 0.0032, 0.0032), (side * 0.012, y + 0.004, hip + 0.022, 0.003, 0.003),
                   (side * 0.007, y + 0.016, hip + 0.016, 0.0026, 0.0026)], cloth, n=6)
        _blob(m, side * 0.006, y + 0.018, hip + 0.016, 0.0024, 0.0024, 0.0024, face)
    # The head, and a hat with a brim.
    _blob(m, 0.0, y + 0.003, hip + 0.052, 0.0062, 0.0066, 0.0072, face)
    _sweep(m, [(0.0, y + 0.003, hip + 0.0575, 0.0095, 0.0095), (0.0, y + 0.003, hip + 0.0590, 0.0095, 0.0095)], hat, n=12,
           ref=(0.0, 1.0, 0.0))
    _sweep(m, [(0.0, y + 0.003, hip + 0.058, 0.0062, 0.0062), (0.0, y + 0.003, hip + 0.069, 0.006, 0.006)], hat, n=12,
           ref=(0.0, 1.0, 0.0))


def pole(m, y0, y1, z, wood):
    """The pole from the vehicle forward between the pair."""
    m.rod((0.0, y0, z), (0.0, y1, z + 0.08), 0.003, wood)


def omnibus(m, L):
    """A horse omnibus: a long box body with a curved roof and windows all along, a door at the back, the driver up
    front over the pair."""
    a0, a1 = -L / 2 + 0.005, L / 2 - 0.005
    body = material("omnibus", "#2c4c38", rough=0.45, clearcoat=0.3)
    trim = material("omnibus trim", "#e6dcc0", rough=0.45)
    glass = material("glass", "#1e2a36", rough=0.1)
    wood = material("wheel red", "#a2342a", rough=0.6)
    dark = material("undercarriage", "#222224", rough=0.7)
    b0, b1 = a0 + 0.01, a0 + 0.24
    half = 0.07
    floor, top = 0.3, 1.1
    m.shell([(b0, floor, 0.006, "paint", 1), (b1, floor, 0.006, "paint", 1), (b1 + 0.004, top - 0.12, 0.02, "paint", 1),
             (b1 - 0.01, top, 0.03, "paint"), (b0 + 0.01, top, 0.03, "paint"), (b0 - 0.004, top - 0.12, 0.02, "paint", 1)],
            half, {"paint": body}, crown=0.05)
    for side in (-1, 1):
        for k in range(5):
            ya = b0 + 0.02 + k * 0.043
            m.box(side * half - 0.001, side * half + 0.001, ya, ya + 0.032, floor + 0.4, top - 0.18, glass)
        m.box(side * half - 0.0015, side * half + 0.0015, b0 + 0.01, b1 - 0.01, floor + 0.3, floor + 0.36, trim)
    m.box(-0.025, 0.025, b0 - 0.003, b0 + 0.002, floor, top - 0.15, glass)
    m.box(-half, half, b0 - 0.02, b0, floor - 0.04, floor, dark)
    # The driver's seat up at the front, the footboard below it.
    m.box(-0.04, 0.04, b1 - 0.005, b1 + 0.03, top - 0.02, top + 0.06, dark)
    driver(m, b1 + 0.012, top + 0.06)
    for y, r in ((b0 + 0.05, 0.048), (b1 - 0.04, 0.036)):
        axle(m, y, r, half + 0.006, floor, dark)
        for side in (-1, 1):
            wheel(m, side * (half + 0.006), y, r, wood, spokes=12)
    pole(m, b1, b1 + 0.03, 0.4, dark)
    team(m, b1 + 0.03)
    m.text(LETTERS, half + 0.002, (b0 + b1) / 2, floor + 0.2, 0.02, trim, 1)


def coach(m, L, mail):
    """A Concord stagecoach slung on its leather braces: an egg-shaped body, a high box seat for the driver, a boot at the
    back and luggage on the roof. The mail coach is the same in darker colours and lettered for the mail."""
    a0, a1 = -L / 2 + 0.005, L / 2 - 0.005
    paint, wheels = ("#5a1e22", "#1e1e20") if mail else ("#a2342a", "#d8a020")
    body = material("coach mail" if mail else "coach", paint, rough=0.4, clearcoat=0.4)
    gold = material("coach trim", "#c9a640", metal=0.4, rough=0.35)
    glass = material("glass", "#1e2a36", rough=0.1)
    wood = material("coach wheels mail" if mail else "coach wheels", wheels, rough=0.6)
    dark = material("undercarriage", "#222224", rough=0.7)
    leather = material("boot", "#3a2a20", rough=0.75)
    b0 = a0 + 0.03
    b1 = b0 + (0.15 if not mail else 0.13)
    half = 0.062
    lo, hi = 0.42, 1.04
    # The body's curved belly and flat top, its ends leaning out.
    m.shell([(b0 + 0.02, lo, 0.03, "paint", 0.92), (b1 - 0.02, lo, 0.03, "paint", 0.92), (b1 + 0.008, lo + 0.25, 0.03, "paint", 1),
             (b1, hi, 0.012, "paint", 1), (b0, hi, 0.012, "paint", 1), (b0 - 0.008, lo + 0.25, 0.03, "paint", 1)],
            half, {"paint": body}, crown=0.04)
    for side in (-1, 1):
        m.box(side * half - 0.001, side * half + 0.001, (b0 + b1) / 2 - 0.022, (b0 + b1) / 2 + 0.022, lo + 0.32, hi - 0.1, glass)
        m.box(side * half - 0.0015, side * half + 0.0015, b0 + 0.004, b1 - 0.004, hi - 0.05, hi - 0.03, gold)
    # The boot behind, the box seat in front with the driver, a rail of luggage on the roof.
    m.box(-0.05, 0.05, b0 - 0.03, b0 - 0.002, lo + 0.05, lo + 0.4, leather, bevel=0.004)
    m.box(-0.045, 0.045, b1 - 0.004, b1 + 0.035, hi - 0.15, hi - 0.06, body, bevel=0.003)
    driver(m, b1 + 0.016, hi - 0.06)
    for k, (yy, cc) in enumerate(((b0 + 0.03, "#6a5a40"), (b0 + 0.07, "#4a3a2a"))):
        m.box(-0.035, 0.035, yy - 0.016, yy + 0.016, hi, hi + 0.12 - k * 0.03, material(f"trunk {cc}", cc, rough=0.7), bevel=0.003)
    # The undercarriage: a perch from axle to axle, and the body slung between them on leather braces.
    rear, front = (b0 + 0.015, 0.05), (b1 + 0.005, 0.04)
    m.box(-0.006, 0.006, rear[0], front[0], 0.17, 0.21, dark)
    for y, r in (rear, front):
        axle(m, y, r, half + 0.008, 0.3, dark)
        for side in (-1, 1):
            wheel(m, side * (half + 0.008), y, r, wood, spokes=12)
    for side in (-1, 1):
        m.rod((side * 0.04, rear[0], 0.3), (side * 0.04, (rear[0] + front[0]) / 2, lo + 0.02), 0.004, leather, segments=6)
        m.rod((side * 0.04, (rear[0] + front[0]) / 2, lo + 0.02), (side * 0.04, front[0], 0.3), 0.004, leather, segments=6)
    pole(m, b1 + 0.02, b1 + 0.045, 0.4, dark)
    team(m, b1 + 0.045, coats=(1, 2) if mail else (0, 2))
    m.text("MAIL" if mail else LETTERS, half + 0.002, (b0 + b1) / 2, lo + 0.18, 0.02, gold, 1)


def freight_wagon(m, L):
    """A covered freight wagon: a deep bed sloping up at each end, canvas over hoops, big wheels behind."""
    a0, a1 = -L / 2 + 0.005, L / 2 - 0.005
    bed = material("wagon bed", "#2a4a7a", rough=0.6)
    canvas = material("canvas", "#e8e2d2", rough=0.9)
    wood = material("wagon wheels", "#a2342a", rough=0.6)
    dark = material("undercarriage", "#222224", rough=0.7)
    b0, b1 = a0 + 0.01, a0 + 0.24
    half = 0.06
    lo = 0.36
    m.shell([(b0 + 0.012, lo, 0.004, "paint", 1), (b1 - 0.012, lo, 0.004, "paint", 1), (b1 + 0.012, lo + 0.3, 0.004, "paint", 1),
             (b0 - 0.012, lo + 0.3, 0.004, "paint", 1)], half, {"paint": bed})
    # The canvas over its hoops, full at the middle and drawn in at the ends.
    sections = []
    for k in range(9):
        f = k / 8
        y = b0 + 0.005 + (b1 - b0 - 0.01) * f
        rise = 0.52 + 0.05 * (abs(f - 0.5) * 2) ** 2
        sections.append((y, [(math.cos(math.pi * j / 10) * (half + 0.004), lo + 0.3 + math.sin(math.pi * j / 10) * rise)
                             for j in range(11)] + [(-half - 0.004, lo + 0.29), (half + 0.004, lo + 0.29)]))
    m.loft(sections, {"canvas": canvas})
    m.box(-0.006, 0.006, b0 + 0.04, b1 - 0.035, 0.18, 0.22, dark)
    for y, r in ((b0 + 0.045, 0.05), (b1 - 0.04, 0.042)):
        axle(m, y, r, half + 0.008, lo, dark)
        m.box(-half * 0.8, half * 0.8, y - 0.01, y + 0.01, lo - 0.05, lo, dark)
        for side in (-1, 1):
            wheel(m, side * (half + 0.008), y, r, wood, spokes=12)
    driver(m, b1 - 0.01, lo + 0.3, coat="#5a4a3a")
    pole(m, b1, b1 + 0.03, 0.38, dark)
    team(m, b1 + 0.03, coats=(2, 3))


def red_river_cart(m, L):
    """A Red River cart: all grey weathered wood, two tall dished wheels as high as the ox's back, an open rack of rails
    on the shafts between them with a few bales in it, and the shafts running on to the ox."""
    a0, a1 = -L / 2 + 0.005, L / 2 - 0.005
    wood = material("cart wood", "#8c7c68", rough=0.9)
    r = 0.068
    y = a0 + r - 0.008
    half = 0.05
    for side in (-1, 1):
        wheel(m, side * (half + 0.012), y, r, wood, spokes=12, hub_r=0.013, dish=0.012)
    zc = r / rig.RISE
    with m.keep():
        m.rod((-half - 0.004, y, zc), (half + 0.004, y, zc), 0.006, wood, segments=6)
    # The bed sits on the axle, its shafts the side rails, carrying on forward to the ox's shoulders.
    floor = m.fixed(zc + 0.03)
    b0, b1 = y - 0.06, y + 0.036
    m.box(-half + 0.004, half - 0.004, b0, b1, floor, floor + 0.035, wood)
    for side in (-1, 1):
        m.rod((side * half, b0 - 0.008, floor + 0.02), (side * 0.034, b1 + 0.13, 0.58), 0.0035, wood)
    # Open rails: posts round the bed with two rails along the top and middle.
    rail = floor + 0.32
    for side in (-1, 1):
        for k in range(6):
            yy = b0 + 0.004 + (b1 - b0 - 0.008) * k / 5
            m.rod((side * half, yy, floor + 0.03), (side * half, yy, rail + 0.02), 0.0022, wood, segments=5)
        for z in (floor + 0.16, rail):
            m.rod((side * half, b0, z), (side * half, b1, z), 0.0026, wood, segments=5)
    for yy in (b0, b1):
        for x in (-0.017, 0.017):
            m.rod((x, yy, floor + 0.03), (x, yy, rail + 0.02), 0.0022, wood, segments=5)
        for z in (floor + 0.16, rail):
            m.rod((-half, yy, z), (half, yy, z), 0.0026, wood, segments=5)
    m.box(-0.036, 0.036, b0 + 0.008, (b0 + b1) / 2 - 0.003, floor + 0.035, floor + 0.21, material("cart bales", "#5a4232", rough=0.9), bevel=0.008)
    m.box(-0.034, 0.034, (b0 + b1) / 2 + 0.002, b1 - 0.008, floor + 0.035, floor + 0.17, material("cart sacks", "#c8bc98", rough=0.9), bevel=0.01)
    horse(m, 0.0, b1 + 0.024, "#8a6a4a", ox=True)


def horsecar(m, L):
    """A horsecar: a small car on four small wheels, open platforms behind curved dashes at each end under the roof's
    hoods, a low monitor deck, drawn along the tram rails by one horse with the driver standing on the front platform."""
    a0 = -L / 2 + 0.005
    c = roadcars.common()
    mats = {"paint": material("horsecar", "#8a2a22", rough=0.4, clearcoat=0.3), "trim": material("horsecar trim", "#d8ccb0", rough=0.5),
            "roof": material("car roof", "#6a625a", rough=0.75)}
    dark = c["dark"]
    b0, b1 = a0 + 0.005, a0 + 0.275
    plat = 0.04
    half = 0.066
    floor = 0.32
    roadcars.old_car(m, b0, b1, half, floor, 0.98, plat, mats, c, False, 6, rise=0.05, deck=0.07)
    r = 0.02
    zw = r / rig.RISE
    with m.keep():
        for y in (b0 + plat + 0.035, b1 - plat - 0.035):
            for side in (-1, 1):
                m.tube_x(side * 0.056 - 0.005, side * 0.056 + 0.005, y, zw, r, dark)
                m.tube_x(side * 0.062 - 0.002, side * 0.062 + 0.002, y, zw, r * 0.4, c["hub"])
                m.box(side * 0.064 - 0.003, side * 0.064 + 0.003, y - 0.008, y + 0.008, zw, zw + 0.12, dark)
    m.box(-0.045, 0.045, b0 + plat + 0.01, b1 - plat - 0.01, m.fixed(zw + 0.06), floor - 0.04, dark)
    driver(m, b1 - 0.018, floor, standing=True)
    pole(m, b1, b1 + 0.02, 0.36, dark)
    horse(m, 0.0, b1 + 0.022, COATS[0])
    m.text(LETTERS, half + 0.002, (b0 + b1) / 2, floor + 0.2, 0.02, mats["trim"], 1)
