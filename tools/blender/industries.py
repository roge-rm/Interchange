"""Industry pieces, one to a tile, for the layouts in packs/base/industries.json.

In the same terms as the town buildings (tools/blender/houses.py): x across,
y along, z in steps, the tile from -0.5 to 0.5 each way, built facing +y and
turned by place(). Pieces of one industry share their colours, so they read as
one place across their tiles.
"""
import math
import random

from mathutils import Matrix

import cars
import harbour
import horses
import houses
import rig
import roadcars
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



def moved(m, build, x, y, turn=0.0, z=0.0):
    """Builds something drawn about the tile's middle facing +y, a vehicle or a team, then moves it to x, y and z steps up,
    turned by turn radians."""
    n0 = len(m.objects)
    build(m)
    mat = Matrix.Translation((x, y, z * rig.RISE)) @ Matrix.Rotation(turn, 4, "Z")
    for o in m.objects[n0:]:
        o.matrix_basis = mat @ o.matrix_basis



def battens(m, face, at, d, a0, a1, z0, top, mat, gap=0.022):
    """Battens up a board wall at x or y = at, facing d, a0 to a1 along it, from z0 up to top, a height or a function of
    where along the wall."""
    n = max(1, round((a1 - a0) / gap))
    for k in range(1, n):
        a = a0 + (a1 - a0) * k / n
        z1 = top(a) if callable(top) else top
        if z1 > z0 + 0.05:
            houses.on_wall(m, face, at, d, a - 0.0018, a + 0.0018, 0, 0.0035, z0, z1, mat)



def boards(m, x0, x1, y0, y1, z0, z1, mat, gap=0.022):
    """Board and batten round all four walls of a box."""
    for face, at, d, a0, a1 in (("y", y1, 1, x0, x1), ("y", y0, -1, x0, x1), ("x", x1, 1, y0, y1), ("x", x0, -1, y0, y1)):
        battens(m, face, at, d, a0, a1, z0, z1, mat, gap)



def fieldstone(m, x0, x1, y0, y1, z0, z1, seed=1, walls=None):
    """A fieldstone wall round a box: grey mortar with stones of a few colours laid in rough courses over it. walls limits
    it to some of the four walls, as (face, at, d, a0, a1)."""
    m.box(x0, x1, y0, y1, z0, z1, material("mortar", "#8a8478", rough=1.0))
    rng = random.Random(seed)
    mats = [material(f"fieldstone {c}", c, rough=0.95) for c in ("#a29a8c", "#7c7870", "#b4a68e", "#8c7c6a", "#6c6a66", "#c2baa8")]
    walls = walls or (("y", y1, 1, x0, x1), ("y", y0, -1, x0, x1), ("x", x1, 1, y0, y1), ("x", x0, -1, y0, y1))
    for face, at, d, a0, a1 in walls:
        z = z0 + 0.015
        while z < z1 - 0.07:
            h = min(rng.uniform(0.12, 0.19), z1 - 0.015 - z)
            a = a0 + rng.uniform(0.0, 0.012)
            while a < a1 - 0.01:
                w = min(rng.uniform(0.016, 0.034), a1 - a)
                p, q = sorted((at + d * 0.0005, at + d * 0.0045))
                zz = rng.uniform(-0.015, 0.015)
                if face == "y":
                    m.box(a + 0.002, a + w - 0.002, p, q, z + 0.014 + zz, z + h - 0.014 + zz, rng.choice(mats), 0.0015)
                else:
                    m.box(p, q, a + 0.002, a + w - 0.002, z + 0.014 + zz, z + h - 0.014 + zz, rng.choice(mats), 0.0015)
                a += w
            z += h



def cupola(m, x, y, z, w, h, wall, roof, trim):
    """A square ventilator cupola standing on a ridge at z: louvred sides, a pyramid roof and a finial."""
    a = w / 2
    m.box(x - a, x + a, y - a, y + a, z - 0.35, z + h, wall)
    slat = material("louvre slats", "#d8d2c4", rough=0.8)
    for face, at, d, c in (("y", y + a, 1, x), ("y", y - a, -1, x), ("x", x + a, 1, y), ("x", x - a, -1, y)):
        houses.louvres(m, face, at, d, c, z + 0.06, z + h - 0.07, w * 0.56, trim, slat)
    houses.corner_boards(m, x - a, x + a, y - a, y + a, z - 0.35, z + h, trim, w=0.005)
    hip(m, x - a, x + a, y - a, y + a, z + h, z + h + 0.38, roof, over=0.012, thick=0.04)
    houses.finial(m, x, y, z + h + 0.36, 0.32, material("finial", "#5a5a58", metal=0.7, rough=0.4))



def hay_hood(m, x, y, z, out, w, rise, roof, wall, d=-1):
    """The hood over a hay door at a gable peak, out from the wall at x = x towards d: a little gabled roof over the
    end of the hay track."""
    xo = x + d * out
    for s in (-1, 1):
        slab(m, [(x, y, z + rise), (xo, y, z + rise), (xo, y + s * w, z), (x, y + s * w, z)], 0.04, roof)
    for xx in (xo + d * 0.002,):
        m.wedge([(xx - 0.003, y - w * 0.9, z + 0.02), (xx - 0.003, y + w * 0.9, z + 0.02), (xx - 0.003, y, z + rise)],
                [(xx + 0.003, y - w * 0.9, z + 0.02), (xx + 0.003, y + w * 0.9, z + 0.02), (xx + 0.003, y, z + rise)], wall)
    # The hay track's end sticking out under it, with the trolley's pulley.
    iron = material("hay track", "#3a3836", metal=0.6, rough=0.5)
    m.box(min(x, xo), max(x, xo), y - 0.004, y + 0.004, z - 0.04, z + 0.02, iron)
    m.upright(xo - d * 0.015, y, z - 0.25, z - 0.04, 0.004, 0.004, iron, segments=8)



def aermotor(m, x, y, top, r=0.072):
    """A steel farm windmill of the Aermotor kind: a four-legged galvanized tower, a wheel of 18 curved sails on rings
    facing +y, the gearbox on top and a tail vane behind, the pump rod down the middle."""
    steel = material("galvanized", "#a8aaa6", metal=0.7, rough=0.35)
    dark = material("windmill head", "#5a5c5a", metal=0.6, rough=0.4)
    b, t = 0.05, 0.012
    corners = [(-1, -1), (1, -1), (1, 1), (-1, 1)]

    def half(z):
        return b + (t - b) * z / top
    for sx, sy in corners:
        m.rod((x + sx * b, y + sy * b, 0), (x + sx * t, y + sy * t, top), 0.0028, steel, segments=6)
        m.box(x + sx * b - 0.008, x + sx * b + 0.008, y + sy * b - 0.008, y + sy * b + 0.008, 0, 0.06, material("footing", "#9a968c", rough=1.0))
    levels = [0.12, 1.0, 1.8, 2.5, 3.1, top - 0.05]
    for za, zb in zip(levels, levels[1:]):
        ha, hb = half(za), half(zb)
        for k in range(4):
            (sx, sy), (tx, ty) = corners[k], corners[(k + 1) % 4]
            m.rod((x + sx * hb, y + sy * hb, zb), (x + tx * hb, y + ty * hb, zb), 0.0016, steel, segments=5)
            m.rod((x + sx * ha, y + sy * ha, za), (x + tx * hb, y + ty * hb, zb), 0.001, steel, segments=4)
            m.rod((x + tx * ha, y + ty * ha, za), (x + sx * hb, y + sy * hb, zb), 0.001, steel, segments=4)
    # A small platform under the head, and the pump rod down the middle to the pump.
    m.box(x - 0.03, x + 0.03, y - 0.03, y + 0.03, top - 0.55, top - 0.5, steel)
    m.rod((x, y, 0.3), (x, y, top), 0.0012, dark, segments=4)
    m.upright(x, y, 0, 0.3, 0.008, 0.006, dark, segments=8)
    m.rod((x, y, 0.28), (x + 0.012, y + 0.01, 0.4), 0.0025, dark)
    # The head: a gearbox on a turntable, the wheel out front on its shaft.
    zc = top + 0.42
    m.upright(x, y, top, top + 0.12, 0.014, 0.012, dark, segments=12)
    m.box(x - 0.011, x + 0.011, y - 0.03, y + 0.022, top + 0.1, top + 0.55, dark, 0.004)
    yw = y + 0.045
    m.tube(y, yw + 0.008, x, zc, 0.006, dark, segments=10)
    s = UNSQUASH / rig.RISE

    def at(a, rr, dy=0.0):
        return (x + rr * math.cos(a), yw + dy, zc + rr * math.sin(a) * s)
    n = 36
    for rr in (r, r * 0.62, r * 0.3):
        for k in range(n):
            m.rod(at(2 * math.pi * k / n, rr), at(2 * math.pi * (k + 1) / n, rr), 0.0014, steel, segments=4)
    sail = material("windmill sails", "#c0c2be", metal=0.6, rough=0.35)
    blades = 18
    for k in range(blades):
        a = 2 * math.pi * k / blades
        w = math.pi / blades * 0.78
        # Each sail is pitched about its own length, so one edge stands forward and the other back.
        quad = [at(a - w, r * 0.3, -0.004), at(a + w * 0.6, r * 0.3, 0.004), at(a + w, r * 0.99, 0.009), at(a - w, r * 0.99, -0.009)]
        m.wedge(quad, [(p[0], p[1] + 0.0015, p[2]) for p in quad], sail)
        m.rod(at(a, r * 0.08, -0.003), at(a, r * 0.3), 0.0012, steel, segments=4)
    # The tail: a boom back from the head with its braces and a flat vane on the end.
    ty0, ty1 = y - 0.06, y - 0.16
    m.rod((x, y - 0.025, zc + 0.03), (x, ty0, zc + 0.08), 0.0025, dark)
    m.rod((x, y - 0.02, zc - 0.12), (x, ty0, zc + 0.05), 0.0015, dark)
    vane = [(x, ty0, zc - 0.05), (x, ty1, zc - 0.22), (x, ty1, zc + 0.42), (x, ty0, zc + 0.2)]
    m.wedge([(p[0] - 0.0012, p[1], p[2]) for p in vane], [(p[0] + 0.0012, p[1], p[2]) for p in vane], sail)



def stock_tank(m, x, y, r):
    """A round galvanized stock tank with a rolled rim, full of water."""
    steel = material("stock tank", "#9a9c98", metal=0.7, rough=0.4)
    m.upright(x, y, 0, 0.24, r, r, steel, segments=28)
    for z in (0.08, 0.16):
        m.upright(x, y, z, z + 0.02, r * 1.02, r * 1.02, steel, segments=28)
    m.upright(x, y, 0.24, 0.25, r * 0.97, r * 0.97, material("stock tank water", "#4a6a78", metal=0.1, rough=0.15), segments=28)
    n = 28
    for k in range(n):
        a, b = 2 * math.pi * k / n, 2 * math.pi * (k + 1) / n
        m.rod((x + r * math.cos(a), y + r * math.sin(a), 0.26), (x + r * math.cos(b), y + r * math.sin(b), 0.26), 0.004, steel, segments=6)



def silo_bank(m, xs, ys, r, top, mat, band=None):
    """Concrete silos touching in a bank, xs by ys, with the diamond interstice bins filling between each four, faint
    pour lines round them and a rim at the top."""
    for x in xs:
        for y in ys:
            m.upright(x, y, 0, top, r, r, mat, segments=32)
            m.upright(x, y, top - 0.06, top + 0.02, r * 1.015, r * 1.015, mat, segments=32)
            if band is not None:
                z = 1.0
                while z < top - 0.5:
                    m.upright(x, y, z, z + 0.025, r * 1.004, r * 1.004, band, segments=32)
                    z += 1.0
    for xa, xb in zip(xs, xs[1:]):
        for ya, yb in zip(ys, ys[1:]):
            cx, cy = (xa + xb) / 2, (ya + yb) / 2
            hx, hy = (xb - xa) / 2, (yb - ya) / 2
            pts = [(cx + hx, cy, 0), (cx, cy + hy, 0), (cx - hx, cy, 0), (cx, cy - hy, 0)]
            m.wedge(pts, [(x, y, top - 0.02) for x, y, _ in pts], mat)



def gallery(m, a, b, w, h, wall, roof, glass, along_y=True):
    """An enclosed conveyor gallery from a to b, each (x, y, z of its floor), w wide and h steps tall, with a strip of
    windows down each side and a shallow roof."""
    (ax, ay, az), (bx, by, bz) = a, b

    def side(s, z0, z1, o):
        if along_y:
            return [(ax + s * w / 2 + o, ay, az + z0), (bx + s * w / 2 + o, by, bz + z0), (bx + s * w / 2 + o, by, bz + z1), (ax + s * w / 2 + o, ay, az + z1)]
        return [(ax, ay + s * w / 2 + o, az + z0), (bx, by + s * w / 2 + o, bz + z0), (bx, by + s * w / 2 + o, bz + z1), (ax, ay + s * w / 2 + o, az + z1)]
    inner = side(-1, 0, h, 0)
    outer = side(1, 0, h, 0)
    m.wedge(inner, outer, wall)
    for s in (-1, 1):
        m.wedge(side(s, h * 0.45, h * 0.75, s * 0.001), side(s, h * 0.45, h * 0.75, s * 0.003), glass)
    m.wedge(side(-1, h, h + 0.05, -0.006), side(1, h, h + 0.05, 0.006), roof)



def dray(m, sacks=8):
    """A flour dray: a low flat bed with stake sides piled with sacks, the driver up on the seat and a team of two."""
    bed = material("dray bed", "#3e5a6a", rough=0.6)
    wood = material("dray wheels", "#a2342a", rough=0.6)
    dark = material("undercarriage", "#222224", rough=0.7)
    sack = material("flour sacks", "#e8e0cc", rough=1.0)
    b0, b1, half, floor = -0.18, 0.04, 0.062, 0.34
    m.box(-half, half, b0, b1, floor - 0.06, floor, bed, 0.003)
    for s in (-1, 1):
        m.box(s * half - 0.004, s * half + 0.004, b0, b1, floor, floor + 0.12, bed)
    m.box(-half, half, b0, b0 + 0.008, floor, floor + 0.12, bed)
    m.box(-half, half, b1 - 0.008, b1, floor, floor + 0.3, bed)
    rng = random.Random(5)
    for k in range(sacks):
        col, row = k % 2, k // 2
        y = b0 + 0.03 + row * 0.042
        z = floor + (0.0 if k < 6 else 0.13)
        m.box(-0.05 + col * 0.05 + rng.uniform(-0.004, 0.004), -0.004 + col * 0.05, y - 0.018, y + 0.018, z, z + 0.13, sack, 0.01)
    for y, r in ((b0 + 0.045, 0.05), (b1 - 0.03, 0.04)):
        horses.axle(m, y, r, half + 0.008, floor - 0.06, dark)
        for s in (-1, 1):
            horses.wheel(m, s * (half + 0.008), y, r, wood, spokes=12)
    m.box(-0.04, 0.04, b1 - 0.02, b1 + 0.005, floor + 0.3, floor + 0.36, dark)
    horses.driver(m, b1 - 0.005, floor + 0.36)
    horses.pole(m, b1, b1 + 0.03, 0.4, dark)
    horses.team(m, b1 + 0.03)



def ramp(m, x0, x1, ya, yb, za, zb):
    """A covered cattle ramp from ya (its foot, za high) to yb (zb high), x0 to x1 across: a plank floor on posts, slatted
    board sides and a shed roof over it."""
    wood = material("ramp boards", "#8a7258", rough=0.9)
    post = material("ramp posts", "#5a4636", rough=0.9)
    roof = material("ramp roof", "#5e5a56", metal=0.3, rough=0.6)
    h = 0.75

    def z(y):
        return za + (zb - za) * (y - ya) / (yb - ya)
    m.wedge([(x0, ya, za - 0.06), (x1, ya, za - 0.06), (x1, yb, zb - 0.06), (x0, yb, zb - 0.06)], [(x0, ya, za), (x1, ya, za), (x1, yb, zb), (x0, yb, zb)], wood)
    n = 6
    for k in range(n + 1):
        y = ya + (yb - ya) * k / n
        for x in (x0, x1):
            m.box(x - 0.006, x + 0.006, y - 0.006, y + 0.006, 0, z(y) + h, post)
    for x in (x0, x1):
        for f in (0.15, 0.4, 0.65):
            m.wedge([(x - 0.003, ya, za + h * f), (x - 0.003, yb, zb + h * f), (x - 0.003, yb, zb + h * f + 0.12), (x - 0.003, ya, za + h * f + 0.12)],
                    [(x + 0.003, ya, za + h * f), (x + 0.003, yb, zb + h * f), (x + 0.003, yb, zb + h * f + 0.12), (x + 0.003, ya, za + h * f + 0.12)], wood)
    slab(m, [(x0 - 0.015, ya, za + h + 0.02), (x1 + 0.015, ya, za + h + 0.12), (x1 + 0.015, yb, zb + h + 0.12), (x0 - 0.015, yb, zb + h + 0.02)], 0.04, roof)
    # A gate at the foot where the cattle come in off the pens.
    houses.rails(m, (x1, ya), (x1 + 0.06, ya), post)



def water_tank(m, x, y, z0, r):
    """A wooden water tank of staves and hoops with a cone roof, up on four steel legs with a ladder."""
    steel = material("tank legs", "#3a3c3e", metal=0.5, rough=0.5)
    staves = material("tank staves", "#7a5e44", rough=0.9)
    zt = z0 + 0.95
    for sx in (-1, 1):
        for sy in (-1, 1):
            m.rod((x + sx * r * 0.95, y + sy * r * 0.95, z0), (x + sx * r * 0.7, y + sy * r * 0.7, zt), 0.004, steel, segments=6)
    for za, zb in ((z0 + 0.05, z0 + 0.5), (z0 + 0.5, zt)):
        for (ax, ay), (bx, by) in (((-1, -1), (1, -1)), ((1, -1), (1, 1)), ((1, 1), (-1, 1)), ((-1, 1), (-1, -1))):
            fa, fb = (0.95 - 0.25 * (za - z0) / 0.95) * r, (0.95 - 0.25 * (zb - z0) / 0.95) * r
            m.rod((x + ax * fa, y + ay * fa, za), (x + bx * fb, y + by * fb, zb), 0.0015, steel, segments=4)
    m.box(x - r * 0.9, x + r * 0.9, y - r * 0.9, y + r * 0.9, zt - 0.04, zt, steel)
    m.upright(x, y, zt, zt + 0.75, r, r * 0.97, staves, segments=24)
    for k in range(5):
        zz = zt + 0.06 + k * 0.15
        m.upright(x, y, zz, zz + 0.02, r * 1.012, r * 1.012, steel, segments=24)
    m.lathe(x, y, zt + 0.75, [(r * 1.04, 0), (r * 0.5, 0.18), (0.0, 0.26)], material("tank roof", "#4e4a46", rough=0.8), segments=24)
    harbour.ladder(m, x - r - 0.004, y, z0, zt + 0.75, face_x=True, grab=0.1)



def packing_wing(m):
    """The packing house's lower wings: a three storey brick wing, the smokehouse with its tall stack, and the reefer dock
    under a canopy with a refrigerator car on the siding beside it."""
    paved(m, -0.5, 0.5, -0.5, 0.5, "#8a867e")
    brick = material("plant brick", "#8a5240", rough=0.9)
    trim = material("plant trim", "#d0c6b2", rough=0.8)
    frame = material("plant window frames", "#3a3c3e", metal=0.3, rough=0.6)
    glass = houses.glass()
    x0, x1, y0, y1 = -0.44, 0.2, -0.5, 0.04
    top = 3 * STOREY + 0.2
    m.box(x0, x1, y0, y1, 0, top, brick, 0.004)
    houses.foundation(m, x0, x1, y0, y1, 0.3, trim, out=0.005)
    for face, at, d, a0, a1, n in (("y", y1, 1, x0, x1, 5), ("x", x0, -1, y0, y1, 4), ("x", x1, 1, y0, y1, 4)):
        for s in range(3):
            z = 0.2 + s * STOREY
            if s:
                houses.on_wall(m, face, at, d, a0, a1, 0, 0.01, z - 0.03, z + 0.03, trim)
            for k in range(n):
                if face == "y" and s == 0:
                    continue
                houses.pane(m, face, at, d, a0 + (k + 0.5) * (a1 - a0) / n, z + 0.3, z + 0.95, 0.07, glass, frame, trim, bars=2)
    houses.flat_top(m, x0, x1, y0, y1, top, trim, parapet=0.25)
    # Doors onto the reefer dock along the front.
    for k in range(4):
        c = x0 + 0.08 + k * 0.16
        harbour.rollup_door(m, c - 0.045, c + 0.045, y1, 0.45, 1.25, face=1, colour="#6a6e70")
    # The smokehouse: a narrow tall block at the yard end with its brick stack.
    sx0, sx1, sy0, sy1 = 0.2, 0.44, -0.5, -0.16
    m.box(sx0, sx1, sy0, sy1, 0, top + 0.9, brick, 0.004)
    houses.flat_top(m, sx0, sx1, sy0, sy1, top + 0.9, trim, parapet=0.15)
    for k in range(3):
        houses.on_wall(m, "x", sx1, 1, sy0 + 0.06 + k * 0.1, sy0 + 0.09 + k * 0.1, 0, 0.012, 0.5, top + 0.6, material("smokehouse vents", "#3a2a24", rough=0.9))
    stack(m, 0.32, -0.32, top + 0.9, 7.6, 0.04, brick, material("stack band", "#2e2624", rough=0.9))
    # The reefer dock and its canopy, and the siding with a refrigerator car spotted at it.
    dz = 0.45
    m.box(-0.44, 0.44, y1, 0.17, 0, dz, material("dock concrete", "#a8a296", rough=0.9), 0.003)
    m.box(-0.44, 0.44, 0.17, 0.18, 0.05, dz - 0.02, material("dock timbers", "#5a4a3a", rough=0.9))
    slab(m, [(-0.45, y1, 1.6), (0.45, y1, 1.6), (0.45, 0.2, 1.42), (-0.45, 0.2, 1.42)], 0.05, material("canopy roof", "#5a5e62", metal=0.4, rough=0.5))
    post = material("canopy posts", "#3a3c3e", metal=0.4, rough=0.6)
    for x in (-0.4, -0.08, 0.24, 0.42):
        m.box(x - 0.006, x + 0.006, 0.155, 0.167, dz, 1.45, post)
    ty = 0.34
    tie = material("tie", "#4e3626", rough=0.9)
    rail = material("rail", "#6a6662", metal=0.9, rough=0.45)
    x = -0.45
    while x < 0.5:
        m.box(x - 0.022, x + 0.022, ty - 0.15, min(0.5, ty + 0.16), 0.0, 0.04, tie)
        x += 0.11
    for s in (-1, 1):
        m.box(-0.5, 0.5, ty + s * 0.095 - 0.006, ty + s * 0.095 + 0.006, 0.04, 0.1, rail)
    moved(m, lambda mm: cars.reefer(mm, 9 / 16), 0.0, ty, -math.pi / 2, z=0.1)


# ---- forestry ---------------------------------------------------------------------------------

# Pieces are drawn at 0.75 of their height (BUILDING_RISE); round things stood on edge are stretched up by this to come out round.
UNSQUASH = 1 / 0.75

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
    """An Ontario bank barn: a gambrel roof over board and batten walls on a fieldstone lower storey, the bank ramp up to
    the threshing floor doors, stable doors under the hay hood at the end, a lean-to and two ventilators on the ridge."""
    ground(m, "#5e6a3a", "barnyard")
    red = material("barn red", "#8e2e22", rough=0.85)
    batten = material("barn battens", "#7a261c", rough=0.85)
    trim = material("barn trim", "#e8e2d4", rough=0.7)
    roof = material("barn roof", "#4a4e52", metal=0.3, rough=0.6)
    door = material("barn doors", "#7a2a20", rough=0.85)
    dark = material("barn dark", "#2a2220", rough=0.9)
    x0, x1, y0, y1 = -0.34, 0.34, -0.26, 0.24
    yc = (y0 + y1) / 2
    floor, eave, knee, ridge = 0.95, 2.0, 3.3, 3.9
    fieldstone(m, x0 - 0.008, x1 + 0.008, y0 - 0.008, y1 + 0.008, 0, floor, seed=3)
    m.box(x0, x1, y0, y1, floor, eave, red, 0.003)

    def gambrel(v):
        """The gable end's top edge at v across."""
        t = abs(v - yc) / (yc - y0)
        k = 0.07 / (yc - y0)
        return ridge - (ridge - knee) * t / (1 - k) if t < 1 - k else eave + (knee - eave) * (1 - t) / k
    for ya, ym in ((y0 - 0.03, y0 + 0.07), (y1 + 0.03, y1 - 0.07)):
        slab(m, [(x0 - 0.02, ya, eave), (x1 + 0.02, ya, eave), (x1 + 0.02, ym, knee), (x0 - 0.02, ym, knee)], 0.08, roof)
        slab(m, [(x0 - 0.02, ym, knee), (x1 + 0.02, ym, knee), (x1 + 0.02, yc, ridge), (x0 - 0.02, yc, ridge)], 0.08, roof)
        m.box(x0 - 0.02, x1 + 0.02, ya - 0.004, ya + 0.004, eave - 0.06, eave + 0.09, trim)
    m.box(x0 - 0.02, x1 + 0.02, yc - 0.008, yc + 0.008, ridge + 0.06, ridge + 0.11, roof)
    for x in (x0, x1):
        m.wedge([(x - 0.003, y0, eave), (x - 0.003, y1, eave), (x - 0.003, y1 - 0.07, knee), (x - 0.003, yc, ridge), (x - 0.003, y0 + 0.07, knee)],
                [(x + 0.003, y0, eave), (x + 0.003, y1, eave), (x + 0.003, y1 - 0.07, knee), (x + 0.003, yc, ridge), (x + 0.003, y0 + 0.07, knee)], red)
    for face, at, d, a0, a1 in (("y", y1, 1, x0, x1), ("y", y0, -1, x0, x1)):
        battens(m, face, at, d, a0, a1, floor, eave - 0.02, batten)
    for at, d in ((x0, -1), (x1, 1)):
        battens(m, "x", at, d, y0, y1, floor, lambda v: gambrel(v) - 0.03, batten)
    houses.corner_boards(m, x0, x1, y0, y1, floor, eave, trim)
    # The bank: an earth ramp up to the threshing floor doors, held in by stone wing walls.
    turf = material("bank turf", "#6a7446", rough=1.0)
    bank = [(y1, 0.0), (y1 + 0.23, 0.0), (y1 + 0.23, 0.03), (y1, floor)]
    m.wedge([(-0.15, y, z) for y, z in bank], [(0.15, y, z) for y, z in bank], turf)
    stone = material("fieldstone #8c7c6a", "#8c7c6a", rough=0.95)
    for x in (-0.175, 0.15):
        wing = [(y1, 0.0), (y1 + 0.24, 0.0), (y1 + 0.24, 0.12), (y1, floor + 0.1)]
        m.wedge([(x, y, z) for y, z in wing], [(x + 0.025, y, z) for y, z in wing], material("ramp walls", "#7e7870", rough=1.0))
    # Rolling doors on the threshing floor, white trimmed and braced, on a track under the eave.
    for a, b in ((-0.13, 0.0), (0.0, 0.13)):
        m.box(a + 0.002, b - 0.002, y1, y1 + 0.007, floor, floor + 0.92, door)
        for p, q in (((a, floor), (b, floor + 0.92)), ((b, floor), (a, floor + 0.92))):
            m.rod((p[0] + (0.006 if p[0] < q[0] else -0.006), y1 + 0.009, p[1] + 0.04), (q[0] + (-0.006 if p[0] < q[0] else 0.006), y1 + 0.009, q[1] - 0.04), 0.004, trim)
        for zz in (floor, floor + 0.88):
            houses.on_wall(m, "y", y1, 1, a + 0.002, b - 0.002, 0.007, 0.011, zz, zz + 0.04, trim)
        for s in (a + 0.002, b - 0.014):
            houses.on_wall(m, "y", y1, 1, s, s + 0.012, 0.007, 0.011, floor, floor + 0.92, trim)
    houses.on_wall(m, "y", y1, 1, -0.28, 0.28, 0, 0.012, floor + 0.95, floor + 0.99, material("door track", "#3a3836", metal=0.6, rough=0.5))
    glass = houses.glass()
    # Small windows lighting the stables in the stone, either side of the bank, and down the other side.
    for c in (-0.27, -0.21, 0.21, 0.27):
        houses.pane(m, "y", y1 + 0.008, 1, c, 0.35, 0.68, 0.034, glass, trim, stone, sash=True)
    for c in (-0.24, -0.08, 0.08, 0.24):
        houses.pane(m, "y", y0 - 0.008, -1, c, 0.35, 0.68, 0.034, glass, trim, stone, sash=True)
    # The end under the hay hood: stable doors in the stone, the hay door up in the gable and the hood at the peak.
    for c in (-0.12, 0.11):
        houses.on_wall(m, "x", x0 - 0.008, -1, c - 0.035, c + 0.035, 0, 0.004, 0, 0.82, trim)
        houses.on_wall(m, "x", x0 - 0.008, -1, c - 0.03, c + 0.03, 0.002, 0.007, 0, 0.4, door)
        houses.on_wall(m, "x", x0 - 0.008, -1, c - 0.03, c + 0.03, 0.002, 0.007, 0.42, 0.78, door)
    houses.pane(m, "x", x0 - 0.008, -1, 0.0, 0.38, 0.68, 0.034, glass, trim, stone)
    houses.on_wall(m, "x", x0, -1, yc - 0.055, yc + 0.055, 0, 0.006, 2.45, 3.25, trim)
    houses.on_wall(m, "x", x0, -1, yc - 0.045, yc + 0.045, 0.004, 0.009, 2.5, 3.2, door)
    m.rod((x0 - 0.01, yc - 0.04, 2.55), (x0 - 0.01, yc + 0.04, 3.15), 0.003, trim)
    m.rod((x0 - 0.01, yc + 0.04, 2.55), (x0 - 0.01, yc - 0.04, 3.15), 0.003, trim)
    houses.pane(m, "x", x1, 1, yc, 2.4, 2.9, 0.04, glass, trim, trim)
    hay_hood(m, x0 - 0.02, yc, 3.55, 0.07, 0.065, 0.45, roof, red)
    for x in (-0.16, 0.16):
        cupola(m, x, yc, ridge + 0.02, 0.07, 0.38, red, roof, trim)
        m.rod((x, yc, ridge + 0.85), (x, yc, ridge + 1.15), 0.0015, material("lightning rod", "#4a4a48", metal=0.8, rough=0.3))
    # The lean-to off the far end: a straw shed open to the yard, board walls, its roof sloping off the gable.
    lx = 0.47
    m.box(x1, lx, y0 + 0.01, y0 + 0.02, 0, 1.45, red)
    m.box(lx - 0.01, lx, y0 + 0.01, y1 - 0.02, 0, 0.95, red)
    battens(m, "x", lx, 1, y0 + 0.01, y1 - 0.02, 0, 0.95, batten)
    slab(m, [(x1 + 0.008, y0 - 0.01, 1.55), (lx + 0.02, y0 - 0.01, 0.95), (lx + 0.02, y1 + 0.005, 0.95), (x1 + 0.008, y1 + 0.005, 1.55)], 0.06, roof)
    post = material("lean-to posts", "#6a5440", rough=0.9)
    for y in (y0 + 0.17, y0 + 0.34, y1 - 0.02):
        m.box(lx - 0.01, lx, y - 0.006, y + 0.006, 0, 0.95, post)
    hay = material("hay bales", "#c8a858", rough=1.0)
    for k in range(3):
        m.box(x1 + 0.02, x1 + 0.1, y0 + 0.05 + k * 0.1, y0 + 0.14 + k * 0.1, 0, 0.32, hay, 0.008)
        m.box(x1 + 0.02, x1 + 0.1, y0 + 0.06 + k * 0.1, y0 + 0.13 + k * 0.1, 0.32, 0.62, hay, 0.008)
    m.box(x1 + 0.1, x1 + 0.12, y0 + 0.05, y0 + 0.14, 0, 0.3, hay, 0.006)
    # A manure pile and a stone boat by the stable doors.
    heap(m, -0.43, 0.3, 0.05, 0.25, "#4a3a2a", 4, "manure")


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
    """A brick flour mill five storeys high under a slate gable roof: segmental arched windows with stone heads, string
    courses, the company's name painted high on the long walls, hoist doors up the gable end under a hoist beam."""
    ground(m, "#8a847a", "mill yard paving")
    brick = material("mill brick", "#8e4a36", rough=0.9)
    stone = material("mill stone", "#c8bca8", rough=0.8)
    frame = material("mill window frames", "#e4dccb", rough=0.6)
    wood = material("hoist doors", "#4a3020", rough=0.8)
    glass = houses.glass()
    x0, x1, y0, y1 = -0.34, 0.34, -0.44, 0.44
    base, top, ridge = 0.3, 5 * STOREY + 0.2, 5 * STOREY + 1.3
    m.box(x0, x1, y0, y1, 0, top, brick, 0.004)
    houses.foundation(m, x0, x1, y0, y1, base, stone, out=0.006)
    gable(m, x0, x1, y0, y1, top, ridge, material("mill slate", "#4e5866", rough=0.6), brick, along_x=False, over=0.02, trim=stone, fascia=0.06)
    walls = (("y", y1, 1, x0, x1, 4), ("y", y0, -1, x0, x1, 4), ("x", x1, 1, y0, y1, 6), ("x", x0, -1, y0, y1, 6))
    for s in range(5):
        z = 0.2 + s * STOREY
        if s:
            # A stone string course at each floor.
            for face, at, d, a0, a1, _ in walls:
                houses.on_wall(m, face, at, d, a0, a1, 0, 0.006, z - 0.04, z + 0.03, stone)
        for face, at, d, a0, a1, n in walls:
            for k in range(n):
                c = a0 + (k + 0.5) * (a1 - a0) / n
                if face == "x" and s == 4:
                    continue
                if face == "y" and d == 1 and s == 0 and k == 3:
                    continue
                houses.arched_pane(m, face, at, d, c, z + 0.3, z + 0.86, 0.05, glass, frame, stone, rise=0.09, keystone=False)
    # The name painted across the top floor of each long wall, on a faded ground.
    paint = material("painted sign ground", "#5a3428", rough=0.95)
    letters = material("painted letters", "#ece2c8", rough=0.9)
    for at, d in ((x1, 1), (x0, -1)):
        houses.on_wall(m, "x", at, d, y0 + 0.04, y1 - 0.04, 0, 0.0015, 4 * STOREY + 0.3, top - 0.12, paint)
        houses.sign_text(m, "UNION FLOUR MILLS", "x", at, d, 0.0, 4 * STOREY + 0.72, 0.062, letters, o=0.002)
    # Hoist doors up the middle of the gable end, a freight door below, and the hoist beam over them at the peak.
    for s in range(1, 5):
        z = 0.2 + s * STOREY
        houses.on_wall(m, "y", y1, 1, -0.04, 0.04, 0, 0.006, z + 0.05, z + 0.95, stone)
        houses.on_wall(m, "y", y1, 1, -0.033, 0.033, 0.004, 0.009, z + 0.08, z + 0.92, wood)
        m.rod((-0.03, y1 + 0.011, z + 0.12), (0.03, y1 + 0.011, z + 0.88), 0.0025, frame)
    houses.on_wall(m, "y", y1, 1, 0.19, 0.29, 0, 0.006, 0, 1.05, stone)
    houses.on_wall(m, "y", y1, 1, 0.198, 0.282, 0.004, 0.009, 0.0, 1.0, wood)
    houses.door(m, -0.2, y1, wood, stone, w=0.045, h=0.85)
    beam = material("hoist beam", "#3e2c1e", rough=0.85)
    zb = top + 0.62
    m.box(-0.008, 0.008, y1 - 0.01, y1 + 0.09, zb, zb + 0.07, beam)
    for s in (-1, 1):
        slab(m, [(0.0, y1, zb + 0.4), (0.0, y1 + 0.1, zb + 0.4), (s * 0.04, y1 + 0.1, zb + 0.14), (s * 0.04, y1, zb + 0.14)], 0.035, material("mill slate", "#4e5866", rough=0.6))
    iron = material("hoist tackle", "#2e2c2a", metal=0.6, rough=0.5)
    m.upright(0.0, y1 + 0.075, zb - 0.12, zb, 0.007, 0.007, iron, segments=8)
    m.rod((0.0, y1 + 0.075, zb - 0.12), (0.0, y1 + 0.075, 3.2), 0.0012, iron, segments=4)
    m.box(-0.008, 0.008, y1 + 0.067, y1 + 0.083, 3.05, 3.2, iron)
    houses.chimney(m, 0.0, -0.3, ridge - 0.4, ridge + 0.5, brick)


def mill_silos(m):
    """The mill's grain storage: a bank of six concrete silos with the interstice bins between, the headhouse over them
    with its windows, a conveyor gallery along the top and a gallery bridge across to the mill's gable end."""
    ground(m, "#8a847a", "mill yard paving")
    concrete = material("silo concrete", "#c4beb2", rough=0.9)
    pour = material("silo pour lines", "#b2ac9f", rough=0.95)
    frame = material("headhouse frames", "#5a5650", metal=0.3, rough=0.6)
    glass = houses.glass()
    xs, ys, r, top = (-0.24, 0.0, 0.24), (0.02, 0.26), 0.12, 6.5
    silo_bank(m, xs, ys, r, top, concrete, pour)
    # The gallery along the tops feeding each bin, and the headhouse over the near row with the leg inside it.
    gallery(m, (-0.3, 0.14, top), (0.3, 0.14, top), 0.08, 0.42, concrete, frame, glass, along_y=False)
    hx0, hx1, hy0, hy1, hz = -0.13, 0.13, -0.07, 0.16, top + 1.9
    m.box(hx0, hx1, hy0, hy1, top - 0.4, hz, concrete, 0.003)
    for s in range(2):
        z = top + 0.25 + s * 0.85
        for face, at, d, a0, a1, n in (("y", hy1, 1, hx0, hx1, 3), ("y", hy0, -1, hx0, hx1, 3), ("x", hx1, 1, hy0, hy1, 2), ("x", hx0, -1, hy0, hy1, 2)):
            for k in range(n):
                houses.pane(m, face, at, d, a0 + (k + 0.5) * (a1 - a0) / n, z, z + 0.5, 0.05, glass, frame, concrete, sash=True, bars=1)
    houses.flat_top(m, hx0, hx1, hy0, hy1, hz, concrete, parapet=0.18)
    m.box(-0.05, 0.05, 0.0, 0.1, hz, hz + 0.55, concrete, 0.003)
    houses.pane(m, "x", -0.05, -1, 0.05, hz + 0.12, hz + 0.42, 0.04, glass, frame, concrete, sash=False)
    # The gallery bridge across to the mill, held up on a steel bent.
    steel = material("gallery steel", "#5a5650", metal=0.4, rough=0.6)
    gz = top + 0.05
    gallery(m, (0.18, -0.58, gz), (0.18, hy0, gz), 0.08, 0.4, material("gallery cladding", "#a8a294", metal=0.2, rough=0.7), frame, glass)
    for x in (0.145, 0.215):
        m.rod((x, -0.32, 0.0), (x, -0.32, gz), 0.005, steel, segments=6)
    for z0, z1 in ((0.2, 2.2), (2.2, 4.2), (4.2, gz)):
        m.rod((0.145, -0.32, z0), (0.215, -0.32, z1), 0.0025, steel, segments=5)
        m.rod((0.215, -0.32, z0), (0.145, -0.32, z1), 0.0025, steel, segments=5)
    # A steel stair up the side of the bank to the top.
    harbour.ladder(m, -0.36, 0.14, 0.0, top, face_x=True, grab=0.3)
    # The receiving shed at the foot, where cars and wagons tip their grain.
    shed = material("receiving shed", "#8e4a36", rough=0.9)
    m.box(-0.2, 0.2, 0.38, 0.47, 0, 1.0, shed, 0.003)
    gable(m, -0.2, 0.2, 0.38, 0.47, 1.0, 1.25, material("mill slate", "#4e5866", rough=0.6), shed, along_x=True, over=0.01, thick=0.04)
    harbour.rollup_door(m, -0.1, 0.1, 0.47, 0.0, 0.8, face=1, colour="#6a5a4a")


def mill_yard(m, team=False):
    """The mill's loading dock under its canopy along the mill's end, a rail siding along it to a bumper, and a flour dray
    with its team (before 1925) or a stake truck (from 1925) backed up to the dock."""
    ground(m, "#8a847a", "mill yard paving")
    dock = material("loading dock", "#8e4a36", rough=0.9)
    edge = material("dock timbers", "#5a4a3a", rough=0.9)
    x0, x1, dy0, dy1, dz = -0.46, 0.46, -0.5, -0.33, 0.45
    m.box(x0, x1, dy0, dy1, 0, dz, dock, 0.003)
    harbour.planks(m, x0, x1, dy0, dy1, dz - 0.03, dz, along_x=True, width=0.03, seed=7)
    m.box(x0, x1, dy1, dy1 + 0.01, 0.05, dz - 0.02, edge)
    houses.steps(m, x0 + 0.05, dy1, 0.06, dz, material("dock steps", "#9a948a", rough=0.9), n=3)
    # The canopy over the dock on posts along its edge, braced back to them.
    roof = material("canopy roof", "#5a5e62", metal=0.4, rough=0.5)
    post = material("canopy posts", "#4a3a2c", rough=0.85)
    slab(m, [(x0 - 0.02, -0.56, 1.7), (x1 + 0.02, -0.56, 1.7), (x1 + 0.02, -0.29, 1.45), (x0 - 0.02, -0.29, 1.45)], 0.05, roof)
    m.box(x0 - 0.02, x1 + 0.02, -0.295, -0.287, 1.36, 1.5, material("canopy fascia", "#d8d0bc", rough=0.7))
    for x in (-0.42, -0.14, 0.14, 0.42):
        m.box(x - 0.007, x + 0.007, -0.347, -0.333, dz, 1.47, post)
        m.rod((x, -0.34, 1.2), (x, -0.4, 1.56), 0.003, post, segments=5)
    # Flour sacks on pallets, a hand truck and a few barrels on the dock.
    for k, x in enumerate((-0.36, -0.24, 0.02)):
        harbour.pallet(m, x, -0.42, dz, size=0.08, load="sacks")
    iron = material("hand truck", "#3a3a3c", metal=0.5, rough=0.5)
    m.rod((-0.08, -0.38, dz), (-0.09, -0.4, dz + 0.45), 0.003, iron, segments=5)
    m.rod((-0.06, -0.38, dz), (-0.07, -0.4, dz + 0.45), 0.003, iron, segments=5)
    for x in (0.25, 0.3):
        harbour.drum(m, x, -0.45, dz, colour="#7a5a3a", r=0.018, h=0.3, name="flour barrel")
    # The siding along the dock, ending at a timber bumper.
    tie = material("tie", "#4e3626", rough=0.9)
    rail = material("rail", "#6a6662", metal=0.9, rough=0.45)
    ty, xb = -0.15, 0.06
    x = -0.48
    while x < xb - 0.02:
        m.box(x - 0.022, x + 0.022, ty - 0.16, ty + 0.16, 0.0, 0.07, tie)
        x += 0.11
    m.box(-0.5, xb + 0.02, ty - 0.14, ty + 0.14, 0.0, 0.035, material("ballast", "#7a766e", rough=1.0))
    for s in (-1, 1):
        m.box(-0.5, xb, ty + s * 0.095 - 0.006, ty + s * 0.095 + 0.006, 0.07, 0.16, rail)
    red = material("bumper red", "#a83226", rough=0.7)
    for s in (-1, 1):
        m.box(xb, xb + 0.03, ty + s * 0.095 - 0.012, ty + s * 0.095 + 0.012, 0, 0.42, edge)
        m.rod((xb + 0.03, ty + s * 0.095, 0.05), (xb + 0.1, ty + s * 0.095, 0.0), 0.008, edge, segments=6)
    m.box(xb - 0.004, xb + 0.03, ty - 0.13, ty + 0.13, 0.26, 0.4, red)
    m.box(xb - 0.006, xb - 0.004, ty - 0.05, ty + 0.05, 0.28, 0.38, material("bumper white", "#ece6d8", rough=0.7))
    if team:
        moved(m, dray, 0.3, -0.33 + 0.18)
    else:
        moved(m, lambda mm: roadcars.truck(mm, 6 / 16, "stake", "early"), 0.3, -0.33 + 0.19)


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
    """A prairie ranch barn in weathered board and batten: a loft door under a hay hood, a big sliding door, an open
    lean-to of hay, and an Aermotor windmill over a round stock tank."""
    ground(m, "#6a5a40", "ranch yard")
    wood = material("ranch barn", "#8a5236", rough=0.9)
    batten = material("ranch battens", "#74442c", rough=0.9)
    trim = material("ranch trim", "#d8d0bc", rough=0.8)
    roof = material("ranch roof", "#5e5a56", metal=0.3, rough=0.6)
    door = material("ranch doors", "#6a4028", rough=0.9)
    x0, x1, y0, y1 = -0.2, 0.3, -0.3, 0.12
    yc = (y0 + y1) / 2
    eave, ridge = 1.8, 2.9
    m.box(x0, x1, y0, y1, 0.0, eave, wood, 0.004)
    m.box(x0 - 0.005, x1 + 0.005, y0 - 0.005, y1 + 0.005, 0, 0.12, material("sill stones", "#8a8478", rough=1.0))
    gable(m, x0, x1, y0, y1, eave, ridge, roof, wood, along_x=True, over=0.04, trim=trim, fascia=0.06)
    boards(m, x0, x1, y0, y1, 0.12, eave - 0.02, batten)
    for at, d in ((x0, -1), (x1, 1)):
        battens(m, "x", at, d, y0, y1, eave, lambda v: eave + (ridge - eave) * (1 - abs(v - yc) / (yc - y0)) - 0.05, batten)
    houses.corner_boards(m, x0, x1, y0, y1, 0, eave, trim)
    # The big sliding door on its track, and a man door beside it.
    houses.on_wall(m, "y", y1, 1, 0.0, 0.17, 0, 0.008, 0, 1.25, door)
    for p, q in (((0.0, 0.0), (0.17, 1.25)), ((0.17, 0.0), (0.0, 1.25))):
        m.rod((p[0] + (0.008 if p[0] < q[0] else -0.008), y1 + 0.01, p[1] + 0.05), (q[0] + (-0.008 if p[0] < q[0] else 0.008), y1 + 0.01, q[1] - 0.05), 0.004, trim)
    for zz in (0.0, 1.2):
        houses.on_wall(m, "y", y1, 1, 0.0, 0.17, 0.006, 0.011, zz, zz + 0.05, trim)
    houses.on_wall(m, "y", y1, 1, -0.06, 0.29, 0, 0.014, 1.27, 1.32, material("door track", "#3a3836", metal=0.6, rough=0.5))
    houses.door(m, -0.1, y1, door, trim, w=0.045, h=0.9)
    glass = houses.glass()
    for c in (0.23,):
        houses.pane(m, "y", y1, 1, c, 0.6, 1.05, 0.04, glass, trim, trim)
    for c in (-0.12, 0.05, 0.2):
        houses.pane(m, "y", y0, -1, c, 0.6, 1.05, 0.04, glass, trim, trim)
    # The loft door up in the gable over the lean-to, under its hay hood.
    houses.on_wall(m, "x", x0, -1, yc - 0.05, yc + 0.05, 0, 0.008, 1.85, 2.55, trim)
    houses.on_wall(m, "x", x0, -1, yc - 0.04, yc + 0.04, 0.005, 0.011, 1.9, 2.5, door)
    m.rod((x0 - 0.012, yc - 0.035, 1.95), (x0 - 0.012, yc + 0.035, 2.45), 0.003, trim)
    hay_hood(m, x0 - 0.04, yc, 2.6, 0.06, 0.055, 0.36, roof, wood)
    # The lean-to: open to the yard on posts, its roof off the gable end, hay stacked under it.
    lx = -0.44
    slab(m, [(x0, y0 - 0.02, 1.62), (lx - 0.02, y0 - 0.02, 1.0), (lx - 0.02, y1 + 0.03, 1.0), (x0, y1 + 0.03, 1.62)], 0.06, roof)
    m.box(lx, x0, y0, y0 + 0.012, 0, 1.0, wood)
    m.box(lx, lx + 0.012, y0, y0 + 0.14, 0, 1.0, wood)
    battens(m, "y", y0, -1, lx, x0, 0, 1.0, batten)
    post = material("lean-to posts", "#5a4434", rough=0.9)
    for y in (y0 + 0.14, y1 + 0.02):
        m.box(lx, lx + 0.012, y - 0.006, y + 0.006, 0, 1.02, post)
    m.box(x0 - 0.12, x0 - 0.12 + 0.012, y1 + 0.014, y1 + 0.026, 0, 1.1, post)
    hay = material("hay", "#c8a858", rough=1.0)
    for k in range(3):
        m.box(lx + 0.02, x0 - 0.01, y0 + 0.02 + k * 0.11, y0 + 0.12 + k * 0.11, 0, 0.3 + 0.28 * (k < 2), hay, 0.01)
    m.box(lx + 0.03, x0 - 0.02, y0 + 0.03, y0 + 0.22, 0.58, 0.86, hay, 0.01)
    aermotor(m, -0.33, 0.36, 3.8)
    stock_tank(m, -0.19, 0.38, 0.07)
    m.rod((-0.33, 0.36, 0.3), (-0.23, 0.38, 0.32), 0.004, material("pump pipe", "#5a5a58", metal=0.6, rough=0.4))


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
    """A packing house of the Canada Packers kind: a five storey brick main block with big many-paned windows, its name
    across the top, a wooden water tank on legs on the roof, and the covered cattle ramp up its side from the stockyard."""
    paved(m, -0.5, 0.5, -0.5, 0.5, "#8a867e")
    brick = material("plant brick", "#8a5240", rough=0.9)
    trim = material("plant trim", "#d0c6b2", rough=0.8)
    frame = material("plant window frames", "#3a3c3e", metal=0.3, rough=0.6)
    glass = houses.glass()
    x0, x1, y0, y1 = -0.44, 0.2, -0.44, 0.5
    top = 5 * STOREY + 0.2
    m.box(x0, x1, y0, y1, 0, top, brick, 0.004)
    houses.foundation(m, x0, x1, y0, y1, 0.3, trim, out=0.005)
    # Brick piers between the bays, the windows between them, and a stone band at each floor.
    walls = (("y", y0, -1, x0, x1, 4), ("x", x1, 1, y0, y1, 6), ("x", x0, -1, y0, y1, 6))
    for face, at, d, a0, a1, n in walls:
        for k in range(n + 1):
            a = a0 + k * (a1 - a0) / n
            houses.on_wall(m, face, at, d, a - 0.012, a + 0.012, 0, 0.008, 0.3, top - 0.05, brick)
        for s in range(5):
            z = 0.2 + s * STOREY
            if s:
                houses.on_wall(m, face, at, d, a0, a1, 0, 0.01, z - 0.03, z + 0.03, trim)
            for k in range(n):
                if face == "x" and d == 1 and s < 2 and k < 2:
                    continue
                houses.pane(m, face, at, d, a0 + (k + 0.5) * (a1 - a0) / n, z + 0.3, z + 0.95, 0.075, glass, frame, trim, sash=True, bars=2)
    houses.flat_top(m, x0, x1, y0, y1, top, trim, parapet=0.3)
    letters = material("plant letters", "#ece4cc", rough=0.8)
    houses.sign_text(m, "PRAIRIE PACKERS", "x", x0, -1, 0.04, top - 0.25, 0.058, letters, o=0.012)
    houses.on_wall(m, "x", x0, -1, y0 + 0.03, y1 - 0.03, 0, 0.01, top - 0.42, top - 0.08, material("sign band", "#4a2e24", rough=0.9))
    # A penthouse over the stairs and the wooden water tank on its steel legs.
    m.box(-0.38, -0.22, -0.36, -0.2, top, top + 0.9, brick, 0.003)
    houses.flat_top(m, -0.38, -0.22, -0.36, -0.2, top + 0.9, trim, parapet=0.12)
    water_tank(m, -0.1, 0.22, top, 0.07)
    # The cattle ramp: a covered timber chute climbing from the stockyard to the killing floor on the third storey.
    ramp(m, 0.28, 0.38, 0.46, -0.3, 0.15, 2 * STOREY + 0.3)
    m.box(x1, 0.44, -0.44, -0.24, 0, 2 * STOREY + 1.1, brick, 0.003)
    houses.flat_top(m, x1, 0.44, -0.44, -0.24, 2 * STOREY + 1.1, trim, parapet=0.15)
    houses.pane(m, "x", 0.44, 1, -0.34, 1.0, 1.5, 0.06, glass, frame, trim, bars=2)
    houses.pane(m, "y", -0.44, -1, 0.32, 2.0, 2.5, 0.06, glass, frame, trim, bars=2)


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


# ---- pulp and paper, printing ----------------------------------------------------------------

def digester(m):
    ground(m, "#6a6660", "mill concrete")
    steel = material("digester steel", "#8a8a86", metal=0.5, rough=0.5)
    brick = material("mill brick", "#8e4a36", rough=0.9)
    # The digester tower where chips are cooked to pulp, with a conveyor feeding its top, and the recovery boiler's stack.
    m.box(-0.3, 0.05, -0.25, 0.1, 0, 2.0, brick, 0.004)
    m.upright(-0.12, -0.08, 2.0, 7.2, 0.1, 0.1, steel, segments=24)
    m.lathe(-0.12, -0.08, 7.2, [(0.1, 0), (0.06, 0.3), (0.0, 0.4)], steel, segments=24)
    m.rod((-0.12, 0.0, 7.0), (0.3, 0.45, 0.8), 0.02, material("conveyor", "#4a4038", rough=0.8))
    stack(m, 0.28, -0.25, 0, 8.5, 0.05, material("stack concrete", "#b8b2a6", rough=0.9), material("stack band", "#7a3a2a", rough=0.9))


def paper_machine(m):
    ground(m, "#6a6660", "mill concrete")
    wall = material("paper mill siding", "#c8c2b4", rough=0.85)
    x0, x1, y0, y1 = -0.46, 0.46, -0.4, 0.4
    m.box(x0, x1, y0, y1, 0, 2.6, wall, 0.004)
    gable(m, x0, x1, y0, y1, 2.6, 3.4, material("paper mill roof", "#4e5458", metal=0.3, rough=0.6), wall, along_x=True, over=0.0)
    windows(m, x0, x1, y0, y1, 0.6, 1, 8, 6, glass(), material("mill trim", "#8a8680", rough=0.8), w=0.07, h=0.9)
    # Vents along the roof where the steam from the drying cans goes out.
    for k in range(4):
        m.upright(-0.3 + k * 0.2, 0.0, 3.3, 3.9, 0.04, 0.04, material("vent", "#6a6a66", metal=0.5, rough=0.5), segments=12)


def chip_pile(m):
    ground(m, "#6a6660", "mill concrete")
    heap(m, 0.0, 0.0, 0.42, 2.2, "#c8a468", 91, "wood chips")


def printing_works(m):
    paved(m, -0.5, 0.5, -0.5, 0.5, "#8a867e")
    brick = material("printer brick", "#9a5a42", rough=0.9)
    trim = material("printer stone", "#d0c6b2", rough=0.8)
    x0, x1, y0, y1 = -0.42, 0.42, -0.4, 0.3
    top = 3 * STOREY + 0.2
    m.box(x0, x1, y0, y1, 0, top, brick, 0.004)
    windows(m, x0, x1, y0, y1, 0.1, 3, 5, 4, glass(), trim, skip_door=True)
    houses.flat_top(m, x0, x1, y0, y1, top, trim, parapet=0.4)
    m.box(x0 + 0.06, x1 - 0.06, y1 + 0.004, y1 + 0.012, top - 0.5, top - 0.1, material("printer sign", "#2a3a4a", rough=0.6))
    houses.door(m, 0.0, y1, material("printer door", "#3a2a22", rough=0.6), trim, w=0.08, h=1.0)
    m.box(x0, x1, y1, 0.5, 0, 0.03, material("sidewalk", "#aaa498", rough=0.9))


# ---- grain elevators and ports ------------------------------------------------------------------

def elevator(m):
    """The Prairie sentinel: a tall wooden country elevator in vertical siding with its name in big letters, the leg's
    cupola on top with its windows, the driveway shed out the side where trucks tip their grain, the office by it, the
    loading spout over the track and the conveyor across to the annex."""
    ground(m, "#7a6a50", "elevator yard")
    red = material("elevator red", "#8e2e22", rough=0.85)
    seam = material("elevator siding", "#7a261c", rough=0.85)
    trim = material("elevator trim", "#e8e2d4", rough=0.7)
    roof = material("elevator roof", "#5a5650", metal=0.3, rough=0.6)
    glass = houses.glass()
    x0, x1, y0, y1 = -0.2, 0.2, -0.24, 0.24
    m.box(x0, x1, y0, y1, 0, 7.0, red, 0.004)
    houses.foundation(m, x0, x1, y0, y1, 0.22, material("elevator footing", "#a8a296", rough=0.9), out=0.006)
    boards(m, x0, x1, y0, y1, 0.22, 6.98, seam, gap=0.02)
    houses.corner_boards(m, x0, x1, y0, y1, 0.22, 7.0, trim)
    for face, at, d, a0, a1 in (("y", y1, 1, x0, x1), ("y", y0, -1, x0, x1), ("x", x1, 1, y0, y1), ("x", x0, -1, y0, y1)):
        houses.on_wall(m, face, at, d, a0, a1, 0, 0.006, 6.9, 7.0, trim)
    m.wedge([(x0 - 0.01, y0 - 0.01, 7.0), (x1 + 0.01, y0 - 0.01, 7.0), (x1 + 0.01, y1 + 0.01, 7.0), (x0 - 0.01, y1 + 0.01, 7.0)],
            [(x0 + 0.08, y0 + 0.08, 7.8), (x1 - 0.08, y0 + 0.08, 7.8), (x1 - 0.08, y1 - 0.08, 7.8), (x0 + 0.08, y1 - 0.08, 7.8)], roof)
    # The name in big letters high on both long walls, the town's number on the ends.
    letters = material("elevator letters", "#f0ece0", rough=0.6)
    for at, d in ((x0, -1), (x1, 1)):
        houses.sign_text(m, "WHEAT", "x", at, d, 0.0, 6.25, 0.1, letters, o=0.006)
        houses.sign_text(m, "POOL", "x", at, d, 0.0, 5.55, 0.1, letters, o=0.006)
    for at, d in ((y1, 1), (y0, -1)):
        houses.sign_text(m, "No. 2", "y", at, d, 0.0, 5.6, 0.06, letters, o=0.006)
    # The cupola over the head of the leg: windows all round, a gabled roof with a vent, a railing round its foot.
    cx0, cx1, cy0, cy1 = -0.1, 0.1, -0.12, 0.12
    m.box(cx0, cx1, cy0, cy1, 7.6, 9.0, red, 0.003)
    boards(m, cx0, cx1, cy0, cy1, 7.8, 8.98, seam, gap=0.02)
    houses.corner_boards(m, cx0, cx1, cy0, cy1, 7.8, 9.0, trim)
    for face, at, d, a0, a1, n in (("x", cx0, -1, cy0, cy1, 2), ("x", cx1, 1, cy0, cy1, 2), ("y", cy0, -1, cx0, cx1, 1), ("y", cy1, 1, cx0, cx1, 1)):
        for k in range(n):
            houses.pane(m, face, at, d, a0 + (k + 0.5) * (a1 - a0) / n, 8.3, 8.75, 0.035, glass, trim, trim)
    gable(m, cx0, cx1, cy0, cy1, 9.0, 9.55, roof, red, along_x=False, over=0.02, thick=0.05, trim=trim, fascia=0.05)
    houses.louvres(m, "y", cy1, 1, 0.0, 9.1, 9.35, 0.04, trim, trim)
    rail = material("cupola rail", "#d8d2c4", rough=0.7)
    for (ax, ay), (bx, by) in (((cx0 - 0.03, cy0 - 0.03), (cx1 + 0.03, cy0 - 0.03)), ((cx1 + 0.03, cy0 - 0.03), (cx1 + 0.03, cy1 + 0.03)),
                               ((cx1 + 0.03, cy1 + 0.03), (cx0 - 0.03, cy1 + 0.03)), ((cx0 - 0.03, cy1 + 0.03), (cx0 - 0.03, cy0 - 0.03))):
        m.rod((ax, ay, 8.05), (bx, by, 8.05), 0.0018, rail, segments=4)
        m.rod((ax, ay, 7.75), (ax, ay, 8.05), 0.0018, rail, segments=4)
    m.rod((0.0, cy1 - 0.02, 9.6), (0.0, cy1 - 0.02, 9.95), 0.0015, material("lightning rod", "#4a4a48", metal=0.8, rough=0.3))
    harbour.ladder(m, x0 - 0.002, y0 + 0.05, 0.22, 7.0, face_x=True, grab=0.2)
    # The driveway shed out the side, doors open at both ends for trucks to drive through and tip.
    dx0, dy0, dy1 = -0.46, -0.13, 0.13
    m.box(dx0, x0, dy0, dy1, 0, 1.6, red, 0.003)
    gable(m, dx0, x0, dy0, dy1, 1.6, 2.15, roof, red, along_x=False, over=0.02, thick=0.05, trim=trim, fascia=0.05)
    for face, at, d, a0, a1 in (("x", dx0, -1, dy0, dy1),):
        battens(m, face, at, d, a0, a1, 0.05, 1.58, seam, gap=0.02)
    dark = material("driveway dark", "#1e1a18", rough=0.95)
    for at, d in ((dy1, 1), (dy0, -1)):
        houses.on_wall(m, "y", at, d, dx0 + 0.035, x0 - 0.035, 0, 0.003, 0, 1.25, dark)
        houses.on_wall(m, "y", at, d, dx0 + 0.025, x0 - 0.025, 0, 0.008, 1.25, 1.32, trim)
        for a in (dx0 + 0.025, x0 - 0.035):
            houses.on_wall(m, "y", at, d, a, a + 0.01, 0, 0.008, 0, 1.32, trim)
        battens(m, "y", at, d, dx0, x0, 1.32, lambda a: 1.6 + 0.55 * (1 - abs(a - (dx0 + x0) / 2) / ((x0 - dx0) / 2)) - 0.06, seam, gap=0.02)
    # The office with its stovepipe, and the spout over the track on the far side.
    ox0, ox1, oy0, oy1 = -0.44, -0.3, -0.42, -0.28
    m.box(ox0, ox1, oy0, oy1, 0, 0.9, red, 0.003)
    gable(m, ox0, ox1, oy0, oy1, 0.9, 1.25, roof, red, along_x=True, over=0.015, thick=0.04, trim=trim, fascia=0.04)
    houses.door(m, -0.4, oy1, material("office door", "#5a2a20", rough=0.8), trim, w=0.035, h=0.7)
    houses.pane(m, "x", ox0, -1, -0.35, 0.35, 0.65, 0.04, glass, trim, trim)
    m.upright(-0.33, -0.31, 1.0, 1.55, 0.006, 0.006, material("stovepipe", "#2a2a2a", metal=0.6, rough=0.5), segments=8)
    spout = material("spout", "#5a5650", metal=0.4, rough=0.5)
    m.rod((x1, 0.0, 6.0), (0.42, 0.0, 2.6), 0.012, spout, segments=10)
    m.rod((0.42, 0.0, 2.6), (0.43, 0.0, 2.1), 0.012, spout, segments=10)
    # The conveyor gallery off the end across to the annex.
    gallery(m, (0.0, y1, 6.35), (0.0, 0.5, 5.95), 0.07, 0.36, red, roof, glass)


def elevator_annex(m):
    """A crib annex beside the elevator: a long gabled bin house in the same siding, the conveyor housing along its
    ridge fed by the gallery from the elevator, and its own loading spout."""
    ground(m, "#7a6a50", "elevator yard")
    red = material("elevator red", "#8e2e22", rough=0.85)
    seam = material("elevator siding", "#7a261c", rough=0.85)
    trim = material("elevator trim", "#e8e2d4", rough=0.7)
    roof = material("elevator roof", "#5a5650", metal=0.3, rough=0.6)
    glass = houses.glass()
    x0, x1, y0, y1, eave, ridge = -0.18, 0.18, -0.34, 0.34, 5.0, 5.7
    m.box(x0, x1, y0, y1, 0, eave, red, 0.004)
    houses.foundation(m, x0, x1, y0, y1, 0.22, material("elevator footing", "#a8a296", rough=0.9), out=0.006)
    boards(m, x0, x1, y0, y1, 0.22, eave - 0.02, seam, gap=0.02)
    for at, d in ((y0, -1), (y1, 1)):
        battens(m, "y", at, d, x0, x1, eave, lambda a: eave + (ridge - eave) * (1 - abs(a) / x1) - 0.05, seam, gap=0.02)
    houses.corner_boards(m, x0, x1, y0, y1, 0.22, eave, trim)
    gable(m, x0, x1, y0, y1, eave, ridge, roof, red, along_x=False, over=0.02, trim=trim, fascia=0.06)
    # Crib walls bulge a little at each bin: tie rods show as rows of plates down the long walls.
    plate = material("tie plates", "#3a3634", metal=0.5, rough=0.5)
    for at, d in ((x0, -1), (x1, 1)):
        for k in range(1, 6):
            y = y0 + k * (y1 - y0) / 6
            for z in (1.2, 2.4, 3.6):
                houses.on_wall(m, "x", at, d, y - 0.006, y + 0.006, 0, 0.007, z, z + 0.08, plate)
    # The conveyor housing along the ridge, met by the elevator's gallery at the near end.
    gallery(m, (0.0, y0 - 0.03, ridge - 0.25), (0.0, y1 - 0.03, ridge - 0.25), 0.07, 0.4, red, roof, glass)
    gallery(m, (0.0, -0.5, 5.95), (0.0, y0 - 0.03, ridge - 0.25), 0.07, 0.36, red, roof, glass)
    letters = material("elevator letters", "#f0ece0", rough=0.6)
    for at, d in ((x0, -1), (x1, 1)):
        houses.sign_text(m, "WHEAT POOL", "x", at, d, 0.0, 4.4, 0.06, letters, o=0.006)
    spout = material("spout", "#5a5650", metal=0.4, rough=0.5)
    m.rod((x1, 0.1, 4.4), (0.4, 0.1, 2.4), 0.011, spout, segments=10)
    m.rod((0.4, 0.1, 2.4), (0.41, 0.1, 1.95), 0.011, spout, segments=10)
    harbour.ladder(m, x0 - 0.002, y1 - 0.05, 0.22, eave, face_x=True, grab=0.2)



def concrete_terminal(m):
    """A concrete inland grain terminal of the 1990s: a tall slipform workhouse with the company's name and band near its
    top, a bank of silos beside it with the gallery along their tops, the truck dump shed and a load-out spout."""
    ground(m, "#8a8478", "terminal yard")
    concrete = material("terminal concrete", "#cdc8bc", rough=0.9)
    pour = material("terminal pour lines", "#bab4a8", rough=0.95)
    band = material("terminal band", "#2a6a4a", rough=0.6)
    clad = material("terminal cladding", "#e2e0da", metal=0.3, rough=0.5)
    frame = material("terminal frames", "#4a4e52", metal=0.4, rough=0.5)
    glass = houses.glass()
    xs, ys, r, top = (-0.09, 0.09), (0.0, 0.18, 0.36), 0.09, 8.4
    silo_bank(m, xs, ys, r, top, concrete, pour)
    gallery(m, (0.0, -0.1, top), (0.0, 0.5, top), 0.08, 0.38, clad, frame, glass)
    wx0, wx1, wy0, wy1, wz = -0.15, 0.15, -0.42, -0.09, 10.6
    m.box(wx0, wx1, wy0, wy1, 0, wz, concrete, 0.003)
    z = 1.0
    while z < wz - 0.5:
        for face, at, d, a0, a1 in (("x", wx0, -1, wy0, wy1), ("x", wx1, 1, wy0, wy1), ("y", wy0, -1, wx0, wx1)):
            houses.on_wall(m, face, at, d, a0, a1, 0, 0.002, z, z + 0.025, pour)
        z += 1.0
    for face, at, d, a0, a1 in (("x", wx0, -1, wy0, wy1), ("x", wx1, 1, wy0, wy1), ("y", wy0, -1, wx0, wx1), ("y", wy1, 1, wx0, wx1)):
        houses.on_wall(m, face, at, d, a0, a1, 0, 0.004, wz - 1.25, wz - 0.95, band)
    # The penthouse over the head of the legs, clad in metal, with a strip of windows.
    m.box(-0.12, 0.12, -0.39, -0.14, wz, wz + 1.0, clad, 0.003)
    harbour.ribs_y(m, -0.12, -0.39, -0.14, wz, wz + 1.0, frame, gap=0.025, face=-1)
    houses.flat_top(m, -0.12, 0.12, -0.39, -0.14, wz + 1.0, clad, parapet=0.1)
    m.box(-0.122, -0.118, -0.38, -0.15, wz + 0.45, wz + 0.7, glass)
    houses.flat_top(m, wx0, wx1, wy0, wy1, wz, concrete, parapet=0.15)
    letters = material("terminal letters", "#2a6a4a", rough=0.6)
    houses.sign_text(m, "PRAIRIE", "x", wx0, -1, (wy0 + wy1) / 2, wz - 1.85, 0.05, letters, o=0.003)
    houses.sign_text(m, "GRAIN", "x", wx0, -1, (wy0 + wy1) / 2, wz - 2.4, 0.05, letters, o=0.003)
    for z in (2.0, 4.0, 6.0, 8.0):
        houses.pane(m, "y", wy0, -1, 0.06, z, z + 0.35, 0.03, glass, frame, concrete, sash=False)
    # The truck dump shed off the workhouse, roll-up doors at both ends.
    sx0, sx1, sy0, sy1 = -0.46, wx0, -0.4, -0.12
    m.box(sx0, sx1, sy0, sy1, 0, 1.7, clad, 0.003)
    harbour.ribs_y(m, sx0, sy0, sy1, 0, 1.7, frame, gap=0.025, face=-1)
    gable(m, sx0, sx1, sy0, sy1, 1.7, 1.95, material("shed roof", "#5a5e62", metal=0.4, rough=0.5), clad, along_x=True, over=0.015, thick=0.04)
    for at, d in ((sy1, 1), (sy0, -1)):
        harbour.rollup_door(m, sx0 + 0.05, sx1 - 0.05, at, 0.0, 1.3, face=d, colour="#d8d4cc")
    # The load-out spout over the track on the far side.
    spout = material("spout", "#5a5650", metal=0.4, rough=0.5)
    m.rod((wx1, -0.25, 7.0), (0.42, -0.25, 3.0), 0.013, spout, segments=10)
    m.rod((0.42, -0.25, 3.0), (0.43, -0.25, 2.4), 0.013, spout, segments=10)
    harbour.ladder(m, wx1 + 0.002, -0.4, 0.0, wz, face_x=True, grab=0.2)



def terminal_annex(m):
    """The terminal's annex: four corrugated steel bins with cone roofs and fans, the gallery coming off the silos on a
    truss and dropping to them, and the scale house by the truck scale."""
    ground(m, "#8a8478", "terminal yard")
    steel = material("bin steel", "#c8cac6", metal=0.6, rough=0.35)
    ring = material("bin corrugations", "#a8aaa6", metal=0.6, rough=0.4)
    frame = material("terminal frames", "#4a4e52", metal=0.4, rough=0.5)
    clad = material("terminal cladding", "#e2e0da", metal=0.3, rough=0.5)
    glass = houses.glass()
    r, h = 0.115, 4.6
    for x, y in ((-0.13, -0.08), (0.13, -0.08), (-0.13, 0.2), (0.13, 0.2)):
        m.upright(x, y, 0, h, r, r, steel, segments=32)
        z = 0.12
        while z < h - 0.05:
            m.upright(x, y, z, z + 0.025, r * 1.008, r * 1.008, ring, segments=32)
            z += 0.14
        m.lathe(x, y, h, [(r * 1.02, 0), (r * 0.7, 0.4), (0.14 * r, 0.75), (0.0, 0.78)], steel, segments=32)
        m.upright(x, y, h + 0.74, h + 0.9, 0.012, 0.012, frame, segments=10)
        # An aeration fan on the side of each bin.
        a = math.atan2(y - 0.06, x)
        fx, fy = x + (r + 0.012) * math.cos(a), y + (r + 0.012) * math.sin(a)
        m.box(fx - 0.014, fx + 0.014, fy - 0.014, fy + 0.014, 0.0, 0.35, frame, 0.004)
    # The gallery: on from the terminal's silos at their top, down a truss to a walkway over the bins.
    gz0, gz1 = 8.4, h + 0.9
    gallery(m, (0.0, -0.5, gz0), (0.0, 0.06, gz1), 0.07, 0.36, clad, frame, glass)
    for y, zt in ((-0.25, gz0 + (gz1 - gz0) * 0.25 / 0.56),):
        for x in (-0.03, 0.03):
            m.rod((x, y, 0.0), (x, y, zt), 0.005, frame, segments=6)
        for z0, z1 in ((0.2, zt * 0.33), (zt * 0.33, zt * 0.66), (zt * 0.66, zt)):
            m.rod((-0.03, y, z0), (0.03, y, z1), 0.0025, frame, segments=5)
            m.rod((0.03, y, z0), (-0.03, y, z1), 0.0025, frame, segments=5)
    m.box(-0.2, 0.2, 0.04, 0.08, gz1, gz1 + 0.04, frame)
    for y in (0.04, 0.08):
        m.rod((-0.2, y, gz1 + 0.25), (0.2, y, gz1 + 0.25), 0.0015, frame, segments=4)
    for x, y in ((-0.13, -0.08), (0.13, -0.08), (-0.13, 0.2), (0.13, 0.2)):
        m.rod((x * 0.4, 0.06, gz1), (x, y, h + 0.8), 0.006, frame, segments=6)
    # The truck scale and its scale house.
    m.box(-0.42, -0.3, -0.45, 0.4, 0.0, 0.04, material("scale deck", "#7a7670", metal=0.3, rough=0.6))
    m.box(-0.28, -0.18, 0.34, 0.46, 0, 0.85, clad, 0.003)
    houses.flat_top(m, -0.28, -0.18, 0.34, 0.46, 0.85, frame, parapet=0.08)
    houses.pane(m, "x", -0.28, -1, 0.4, 0.35, 0.65, 0.06, glass, frame, frame, sash=False)


def _wharf_deck(m, x0=-0.8, x1=0.5, y0=-0.5, y1=0.5, top=0.25, seed=1):
    """A timber wharf deck out over the water at -x: planks on caps over round piles braced in their bents, fender
    piles and a wale along the water face, bollards and a capstan along its edge."""
    harbour.planks(m, x0, x1, y0, y1, top - 0.05, top, along_x=False, width=0.04, seed=seed)
    cap = harbour.timber("wharf caps", "#4e3e2e")
    ys = [y0 + 0.06 + k * (y1 - y0 - 0.12) / 3 for k in range(4)]
    xs = [x0 + 0.05 + k * 0.28 for k in range(int((-0.05 - x0) / 0.28) + 1)]
    for y in ys:
        m.box(x0 + 0.01, x1 - 0.01, y - 0.018, y + 0.018, top - 0.12, top - 0.05, cap)
    harbour.piles(m, xs, ys, -0.6, top - 0.1, r=0.022)
    # Fender piles down the water face, with a wale and a curb along the top.
    fender = harbour.timber("wharf fenders", "#3e3024")
    n = int((y1 - y0) / 0.1)
    for k in range(n + 1):
        y = y0 + 0.03 + k * (y1 - y0 - 0.06) / n
        m.upright(x0 - 0.015, y, -0.4, top + 0.02, 0.014, 0.013, fender, segments=8)
    m.box(x0 - 0.035, x0 - 0.02, y0, y1, top - 0.13, top - 0.07, fender)
    m.box(x0, x0 + 0.025, y0, y1, top, top + 0.05, fender)
    return ys


def wharf(m):
    """Timber wharf along the water, out past the tile's edge: planked deck on braced piles, fenders, bollards and a
    capstan, and cargo waiting on it, crates, pallets and drums."""
    _wharf_deck(m)
    top = 0.25
    for y in (-0.38, 0.0, 0.38):
        harbour.bollard(m, -0.74, y, top)
    harbour.capstan(m, -0.72, -0.19, top)
    for y in (0.19,):
        harbour.cleat(m, -0.75, y, top, along_x=False)
    harbour.crate(m, 0.12, 0.26, -0.38, -0.24, top, top + 0.32, "#a8885a")
    harbour.crate(m, 0.14, 0.25, -0.36, -0.26, top + 0.32, top + 0.56, "#9a7a50")
    harbour.crate(m, 0.3, 0.42, -0.36, -0.22, top, top + 0.26, "#b89868")
    harbour.pallet(m, 0.22, 0.02, top, size=0.12, load="sacks")
    harbour.pallet(m, 0.36, 0.02, top, size=0.12, load="cartons")
    harbour.pallet(m, 0.36, 0.17, top, size=0.12, load="sacks")
    for i in range(3):
        for j in range(2):
            harbour.drum(m, 0.14 + i * 0.045, 0.28 + j * 0.045, top, colour=("#2a4a7a", "#c8302a", "#3a6a3a")[(i + j) % 3], r=0.018, h=0.3)
    harbour.crate(m, 0.3, 0.44, 0.3, 0.42, top, top + 0.3, "#8a6a44")
    harbour.crate(m, -0.12, 0.0, -0.08, 0.04, top, top + 0.28, "#b89868")
    harbour.crate(m, -0.1, 0.0, -0.06, 0.03, top + 0.28, top + 0.5, "#a8885a")
    for k, (x, y) in enumerate(((-0.25, 0.22), (-0.21, 0.25), (-0.23, 0.18))):
        harbour.drum(m, x, y, top, colour=("#c8302a", "#2a4a7a")[k % 2], r=0.018, h=0.3)
    harbour.pallet(m, -0.05, -0.3, top, size=0.12, load="cartons")
    harbour.lamp(m, -0.45, 0.45, top, 1.3, toward=(-1.0, 0.0))


def crane(m):
    """A travelling portal crane on the wharf: braced legs over the deck, a machinery house with its cab on a turntable,
    an A-frame and a lattice boom out over the water."""
    _wharf_deck(m, seed=2)
    top = 0.25
    steel = material("crane steel", "#b8862a", metal=0.4, rough=0.5)
    dark = material("crane dark", "#3a3a3c", metal=0.4, rough=0.5)
    rail = material("crane rail", "#6a6a6c", metal=0.7, rough=0.4)
    for x in (-0.55, -0.1):
        m.box(x - 0.004, x + 0.004, -0.5, 0.5, top, top + 0.015, rail)
    for x in (-0.55, -0.1):
        for y in (-0.2, 0.2):
            m.box(x - 0.02, x + 0.02, y - 0.045, y + 0.045, top + 0.015, top + 0.09, dark, 0.004)
            m.rod((x, y, top + 0.09), (x * 0.78 - 0.07, y * 0.6, 2.9), 0.016, steel)
        m.rod((x, -0.2, 0.9), (x, 0.2, 0.9), 0.008, steel)
        m.rod((x, -0.2, top + 0.1), (x * 0.9 - 0.03, 0.15, 2.0), 0.006, steel)
        m.rod((x, 0.2, top + 0.1), (x * 0.9 - 0.03, -0.15, 2.0), 0.006, steel)
    for y in (-0.2, 0.2):
        m.rod((-0.55, y, 1.6), (-0.1, y, 1.6), 0.008, steel)
    m.box(-0.5, -0.15, -0.14, 0.14, 2.9, 3.05, steel, 0.004)
    m.upright(-0.32, 0.0, 3.05, 3.12, 0.1, 0.095, dark, segments=24)
    # The machinery house on the turntable, rounded over, the cab forward of it beside the boom foot.
    m.shell([(-0.12, 3.12, 0.006, "paint"), (0.12, 3.12, 0.006, "paint"), (0.12, 3.75, 0.04, "paint"), (-0.12, 3.75, 0.04, "paint")],
            0.12, {"paint": steel}, crown=0.08, x=-0.26)
    m.box(-0.145, -0.135, -0.1, 0.1, 3.2, 3.65, dark, 0.004)
    for y in (-0.06, 0.0, 0.06):
        m.box(-0.38, -0.14, y - 0.004, y + 0.004, 3.75, 3.78, dark)
    glass = houses.glass()
    cab = material("crane cab", "#d8d4cc", rough=0.6)
    m.box(-0.47, -0.38, 0.06, 0.15, 3.2, 3.7, cab, 0.006)
    m.box(-0.475, -0.468, 0.07, 0.14, 3.42, 3.66, glass)
    m.box(-0.46, -0.39, 0.15, 0.156, 3.42, 3.66, glass)
    m.box(-0.48, -0.37, 0.05, 0.16, 3.7, 3.74, steel, 0.003)
    apex = (-0.2, 0.0, 4.9)
    for s in (-1, 1):
        m.rod((-0.16, s * 0.09, 3.75), apex, 0.012, steel)
        m.rod((-0.36, s * 0.09, 3.75), apex, 0.01, steel)
    tip = (-0.88, 0.0, 4.75)
    harbour.lattice(m, (-0.4, 0.0, 3.45), tip, 0.11, 0.04, steel, n=10, r=0.008, axis=(0.0, 1.0))
    m.rod(apex, tip, 0.004, dark)
    m.rod((-0.22, 0.0, 4.85), (-0.86, 0.0, 4.68), 0.003, dark)
    m.rod((-0.88, 0.0, 4.68), (-0.88, 0.0, 1.5), 0.003, dark)
    m.box(-0.9, -0.86, -0.02, 0.02, 1.35, 1.5, steel, 0.003)


def terminal_elevator(m):
    """A port's terminal elevator: rows of tall concrete bins and the headhouse over them."""
    ground(m, "#8a847a", "port concrete")
    concrete = material("terminal concrete", "#cfc9bd", rough=0.9)
    for x in (-0.3, -0.1, 0.1, 0.3):
        for y in (-0.2, 0.0, 0.2):
            m.upright(x, y, 0, 6.0, 0.1, 0.1, concrete, segments=20)
    m.box(-0.42, 0.42, -0.1, 0.1, 6.0, 7.6, concrete, 0.004)
    m.box(-0.15, 0.15, -0.06, 0.06, 7.6, 8.4, concrete, 0.004)
    m.rod((-0.42, 0.0, 6.8), (-0.9, 0.0, 3.0), 0.025, material("ship loader", "#7a7a76", metal=0.5, rough=0.5))


def warehouse(m):
    ground(m, "#8a847a", "port concrete")
    wall = material("warehouse siding", "#8a6a50", rough=0.85)
    x0, x1, y0, y1 = -0.42, 0.42, -0.4, 0.38
    m.box(x0, x1, y0, y1, 0, 2.2, wall, 0.004)
    gable(m, x0, x1, y0, y1, 2.2, 3.0, material("warehouse roof", "#5a5650", metal=0.3, rough=0.6), wall, along_x=True, over=0.02)
    for k in range(3):
        x = x0 + 0.14 + k * 0.28
        m.box(x - 0.08, x + 0.08, y1 - 0.002, y1 + 0.005, 0, 1.6, material("warehouse doors", "#4a5a62", metal=0.3, rough=0.6))


def fish_wharf(m):
    """A fishing wharf: a planked timber wharf on braced piles with a ladder down, a Cape Islander tied up alongside,
    lobster traps stacked with buoys, a small gabled bait shed, nets and totes."""
    top = 0.25
    _wharf_deck(m, x0=-0.7, x1=0.5, y0=-0.35, y1=0.35, top=top, seed=3)
    harbour.ladder(m, -0.725, -0.12, -0.1, top, face_x=True, grab=0.15)
    for y in (-0.3, 0.3):
        harbour.bollard(m, -0.66, y, top, s=0.8)
    harbour.cleat(m, -0.67, 0.08, top, along_x=False)
    harbour.cape_islander(m, -0.87, -0.32, 0.32, 0.17, 0.55)
    # Mooring lines from the boat to the wharf.
    line = material("mooring line", "#d8c8a0", rough=0.9)
    m.rod((-0.84, -0.28, 0.22), (-0.66, -0.3, top + 0.1), 0.0025, line, segments=5)
    m.rod((-0.84, 0.26, 0.32), (-0.66, 0.3, top + 0.1), 0.0025, line, segments=5)
    # Traps stacked high, buoys on and around them.
    harbour.trap_stack(m, -0.5, -0.29, top, rows=4, cols=3, layers=3, seed=4)
    harbour.trap_stack(m, -0.24, -0.29, top, rows=3, cols=2, layers=2, seed=5)
    for k, (x, y) in enumerate(((-0.53, 0.04), (-0.5, 0.08), (-0.47, 0.03), (-0.44, 0.07), (-0.41, 0.02))):
        harbour.buoy(m, x, y, top, colour=("#e84a2a", "#f0c020", "#2a8a4a")[k % 3], r=0.015)
    # A heap of net and a stack of fish totes.
    net = material("fishing net", "#2a6a5a", rough=1.0)
    m.lathe(-0.3, 0.16, top, [(0.07, 0), (0.07, 0.04), (0.05, 0.1), (0.02, 0.13), (0.0, 0.135)], net, segments=16, oval=0.7)
    for k in range(3):
        m.box(-0.12, -0.04, 0.16, 0.22, top + k * 0.07, top + (k + 1) * 0.07 - 0.005, material("fish totes", "#3a7aaa", rough=0.6), 0.003)
    # The bait shed: shingled walls, a gable roof, a door to the wharf and a window, buoys hung on its end.
    shingle = material("bait shed shingles", "#9a8a72", rough=0.9)
    trim = material("bait shed trim", "#c84a3a", rough=0.7)
    roof = material("bait shed roof", "#4a4642", rough=0.85)
    x0, x1, y0, y1, eave = 0.06, 0.44, -0.12, 0.26, 0.95
    m.box(x0, x1, y0, y1, top, eave, shingle)
    for k in range(5):
        z = top + 0.04 + k * (eave - top - 0.05) / 5
        for y, d in ((y0, -1), (y1, 1)):
            m.box(x0, x1, y + d * 0.004 - 0.002, y + d * 0.004 + 0.002, z, z + 0.012, roof)
        m.box(x1 + 0.002, x1 + 0.006, y0, y1, z, z + 0.012, roof)
        m.box(x0 - 0.006, x0 - 0.002, y0, y1, z, z + 0.012, roof)
    for x, y in ((x0, y0), (x1, y0), (x0, y1), (x1, y1)):
        m.box(x - 0.008, x + 0.008, y - 0.008, y + 0.008, top, eave, trim)
    houses.gable(m, x0, x1, y0, y1, eave, 1.35, roof, shingle, along_x=False, over=0.025, thick=0.06)
    m.box(x0 - 0.008, x0 - 0.002, 0.0, 0.1, top, 0.82, material("bait shed door", "#3e6a8a", rough=0.6))
    m.box(x0 - 0.01, x0 - 0.004, -0.005, 0.105, 0.82, 0.86, trim)
    glass = houses.glass()
    m.box(0.2, 0.3, y1 + 0.004, y1 + 0.009, 0.5, 0.78, glass)
    m.box(0.195, 0.305, y1 + 0.006, y1 + 0.011, 0.46, 0.5, trim)
    for k, y in enumerate((-0.05, 0.03, 0.11, 0.19)):
        harbour.buoy(m, x1 + 0.016, y, 0.55, colour=("#e84a2a", "#f0c020")[k % 2], r=0.011)


def fish_shed(m):
    ground(m, "#6a6660", "harbour gravel")
    wood = material("fish shed shingles", "#9a8a72", rough=0.9)
    m.box(-0.3, 0.3, -0.2, 0.2, 0, 1.5, wood, 0.004)
    gable(m, -0.3, 0.3, -0.2, 0.2, 1.5, 2.3, material("fish shed roof", "#4a4642", rough=0.85), wood, along_x=True)
    windows(m, -0.3, 0.3, -0.2, 0.2, 0.1, 1, 2, 1, glass(), material("shed trim", "#c84a3a", rough=0.7))
    for k in range(4):
        m.box(0.34, 0.44, -0.3 + k * 0.08, -0.24 + k * 0.08, 0, 0.3, material("fish crates", "#3e6a8a", rough=0.8), 0.003)


def boat_shed(m):
    ground(m, "#6a6660", "harbour gravel")
    wood = material("boat shed boards", "#c84a3a", rough=0.85)
    m.box(-0.34, 0.34, -0.34, 0.2, 0, 1.6, wood, 0.004)
    gable(m, -0.34, 0.34, -0.34, 0.2, 1.6, 2.5, material("boat shed roof", "#4a4642", rough=0.85), wood, along_x=False)
    m.box(-0.14, 0.14, 0.2, 0.206, 0, 1.3, material("boat shed doors", "#e8e2d4", rough=0.7))
    # A dory up on blocks.
    m.wedge([(0.2, 0.28, 0.1), (0.36, 0.28, 0.1), (0.36, 0.42, 0.1), (0.2, 0.42, 0.1)], [(0.18, 0.26, 0.32), (0.38, 0.26, 0.32), (0.38, 0.46, 0.32), (0.18, 0.46, 0.32)], material("dory", "#e8c84a", rough=0.6))


def cannery(m):
    paved(m, -0.5, 0.5, -0.5, 0.5, "#8a867e")
    wood = material("cannery siding", "#e6e0d2", rough=0.8)
    x0, x1, y0, y1 = -0.44, 0.44, -0.38, 0.3
    m.box(x0, x1, y0, y1, 0, 2.4, wood, 0.004)
    gable(m, x0, x1, y0, y1, 2.4, 3.3, material("cannery roof", "#8a3a2a", rough=0.8), wood, along_x=True, over=0.03)
    windows(m, x0, x1, y0, y1, 0.3, 1, 6, 4, glass(), material("cannery trim", "#3e5a6a", rough=0.7))
    m.box(x0 + 0.05, x1 - 0.05, y1 + 0.004, y1 + 0.012, 1.7, 2.2, material("cannery sign", "#3e5a6a", rough=0.6))
    stack(m, 0.36, -0.3, 2.4, 5.0, 0.035, material("cannery stack", "#5a5a5a", metal=0.4, rough=0.5))


# ---- potash, nickel and aluminium ----------------------------------------------------------------

def potash_mill(m):
    ground(m, "#8a7a6a", "mine gravel")
    wall = material("potash mill siding", "#c8beb0", rough=0.85)
    x0, x1, y0, y1 = -0.4, 0.4, -0.36, 0.36
    m.box(x0, x1, y0, y1, 0, 4.0, wall, 0.004)
    houses.flat_top(m, x0, x1, y0, y1, 4.0, material("potash trim", "#8a3a2a", rough=0.8))
    m.box(-0.15, 0.25, -0.2, 0.2, 4.0, 6.0, wall, 0.004)
    windows(m, x0, x1, y0, y1, 1.0, 2, 5, 5, glass(), material("potash trim", "#8a3a2a", rough=0.8), w=0.06, h=0.8)


def tailings(m, look=0):
    ground(m, "#8a7a6a", "mine gravel")
    heap(m, 0.0, 0.0, 0.46, 1.6, "#d8b8b0", 93 + look, "salt tailings")


def rock_dump(m):
    ground(m, "#4a4644", "mine rock")
    heap(m, 0.0, 0.0, 0.42, 2.0, "#6a6a66", 95, "waste rock")


def nickel_heap(m):
    ground(m, "#4a4644", "mine rock")
    heap(m, -0.05, 0.0, 0.36, 1.7, "#5a6050", 96, "nickel ore")


def superstack(m):
    """A smelter's tall concrete stack, a landmark from miles off."""
    ground(m, "#4a4644", "smelter yard")
    concrete = material("superstack", "#bcb6aa", rough=0.9)
    m.lathe(0, 0, 0, [(0.16, 0), (0.13, 3.0), (0.09, 9.0), (0.07, 14.0)], concrete, segments=28)
    for z in (13.0, 13.4):
        m.upright(0, 0, z, z + 0.2, 0.08, 0.08, material("stack red band", "#a83a2a", rough=0.8), segments=28)
    m.box(-0.36, 0.0, 0.2, 0.44, 0, 0.9, material("flue", "#6a6660", rough=0.85), 0.004)


def roaster(m):
    ground(m, "#4a4644", "smelter yard")
    steel = material("roaster siding", "#5e5a56", metal=0.4, rough=0.6)
    m.box(-0.36, 0.36, -0.3, 0.3, 0, 3.6, steel, 0.004)
    gable(m, -0.36, 0.36, -0.3, 0.3, 3.6, 4.4, material("roaster roof", "#3e3c3a", metal=0.4, rough=0.6), steel, along_x=True)
    for x in (-0.2, 0.0, 0.2):
        m.upright(x, 0.0, 4.2, 5.4, 0.05, 0.05, material("roaster vent", "#7a5a46", metal=0.4, rough=0.6), segments=12)


def smelter_shed(m):
    ground(m, "#4a4644", "smelter yard")
    brick = material("smelter brick", "#6e3e30", rough=0.9)
    x0, x1, y0, y1 = -0.44, 0.44, -0.4, 0.36
    m.box(x0, x1, y0, y1, 0, 3.0, brick, 0.004)
    gable(m, x0, x1, y0, y1, 3.0, 3.8, material("smelter roof", "#3e3c3a", metal=0.4, rough=0.6), brick, along_x=True)
    windows(m, x0, x1, y0, y1, 0.6, 1, 6, 5, glass(), brick, w=0.06, h=1.2)


def dam(m):
    """A concrete hydro dam across the river, out over the water, a spillway foaming at its foot."""
    concrete = material("dam concrete", "#bcb8ae", rough=0.9)
    m.wedge([(-1.1, -0.5, -0.4), (-0.4, -0.5, -0.4), (-0.4, 0.5, -0.4), (-1.1, 0.5, -0.4)], [(-0.7, -0.5, 2.6), (-0.5, -0.5, 2.6), (-0.5, 0.5, 2.6), (-0.7, 0.5, 2.6)], concrete)
    m.box(-0.72, -0.48, -0.5, 0.5, 2.6, 2.8, concrete)
    for y in (-0.3, 0.0, 0.3):
        m.box(-0.66, -0.54, y - 0.06, y + 0.06, 2.8, 3.4, material("gate house", "#8a8680", rough=0.8))
    m.box(-1.1, -0.8, -0.45, 0.45, -0.4, -0.1, material("foam", "#e8f0f4", rough=0.4))
    ground(m, "#6a6660", "dam road")


def powerhouse(m):
    ground(m, "#6a6660", "dam road")
    brick = material("powerhouse brick", "#9a5a42", rough=0.9)
    x0, x1, y0, y1 = -0.4, 0.3, -0.38, 0.38
    m.box(x0, x1, y0, y1, 0, 3.4, brick, 0.004)
    gable(m, x0, x1, y0, y1, 3.4, 4.2, material("powerhouse roof", "#4e5866", rough=0.6), brick, along_x=False)
    windows(m, x0, x1, y0, y1, 0.5, 1, 3, 5, glass(), material("powerhouse stone", "#d0c6b2", rough=0.8), w=0.07, h=2.0)
    steel = material("pylon", "#8a8a86", metal=0.5, rough=0.5)
    for x, y in ((0.4, -0.3), (0.4, 0.3)):
        m.rod((x - 0.05, y - 0.05, 0), (x, y, 4.5), 0.006, steel)
        m.rod((x + 0.05, y + 0.05, 0), (x, y, 4.5), 0.006, steel)
        m.box(x - 0.1, x + 0.1, y - 0.01, y + 0.01, 4.2, 4.28, steel)


def potline(m):
    paved(m, -0.5, 0.5, -0.5, 0.5, "#7a766e")
    wall = material("potline siding", "#a8a49c", metal=0.3, rough=0.6)
    x0, x1, y0, y1 = -0.48, 0.48, -0.32, 0.22
    m.box(x0, x1, y0, y1, 0, 2.2, wall, 0.004)
    gable(m, x0, x1, y0, y1, 2.2, 2.8, material("potline roof", "#5a5854", metal=0.4, rough=0.6), wall, along_x=True, over=0.0)
    m.box(x0, x1, -0.07, 0.0, 2.7, 3.1, wall)
    m.box(x0, x1, -0.075, 0.005, 2.8, 3.0, material("louvres", "#3a3a3a", rough=0.8))
    m.box(-0.3, 0.3, 0.3, 0.42, 0, 0.3, material("aluminium ingots", "#c8ccd0", metal=0.9, rough=0.3), 0.002)


# ---- the oil sands ------------------------------------------------------------------------------

def sand_pit(m, look=0):
    """Mined oil sand: black benches cut in the ground, a huge haul truck on them."""
    sand = material("oil sand", "#2a2622", rough=1.0)
    bench = material("oil sand bench", "#3a3430", rough=1.0)
    m.box(-0.5, 0.5, -0.5, 0.5, 0, 0.02, sand)
    for k, (inset, h) in enumerate(((0.0, 0.6), (0.18, 0.3))):
        m.box(-0.5 + inset, 0.5 - inset, -0.5 + inset, -0.36 + inset, 0, h, bench)
        m.box(-0.5 + inset, -0.36 + inset, -0.36 + inset, 0.5 - inset, 0, h, bench)
    truck = material("haul truck", "#d8b030", metal=0.3, rough=0.5)
    m.box(0.05, 0.3, 0.1, 0.24, 0.25, 0.75, truck, 0.006)
    m.box(0.25, 0.34, 0.12, 0.22, 0.6, 0.95, material("truck cab", "#e8e2d8", rough=0.6), 0.004)
    for x in (0.1, 0.27):
        for y in (0.1, 0.24):
            m.tube_x(x - 0.02, x + 0.02, y, 0.12, 0.08, material("big tire", "#1a1a1a", rough=0.9), segments=14)


def upgrader(m):
    ground(m, "#5a5652", "plant gravel")
    steel = material("upgrader steel", "#a8a6a0", metal=0.6, rough=0.4)
    for x, y, r, h in ((-0.2, -0.2, 0.09, 8.0), (0.15, -0.15, 0.07, 6.5), (0.0, 0.2, 0.1, 5.5)):
        m.upright(x, y, 0, h, r, r, steel, segments=20)
        m.lathe(x, y, h, [(r, 0), (r * 0.5, 0.15), (0.0, 0.2)], steel, segments=20)
    stack(m, 0.36, 0.3, 0, 9.0, 0.04, material("upgrader stack", "#8a8884", metal=0.5, rough=0.5), material("stack band", "#a83a2a", rough=0.8))
    m.rod((-0.2, -0.2, 3.0), (0.15, -0.15, 3.0), 0.015, steel)


def tailings_pond(m):
    m.box(-0.5, 0.5, -0.5, 0.5, 0, 0.3, material("pond dyke", "#6a5a46", rough=1.0))
    m.box(-0.44, 0.44, -0.44, 0.44, 0.26, 0.34, material("tailings water", "#4a4232", rough=0.3))


PARTS = {
    "bunkhouse": bunkhouse, "log_pile": log_pile, "camp_shed": camp_shed,
    "sawmill": sawmill, "log_deck": log_deck, "burner": burner, "lumber_stack": lumber_stack,
    "barn": barn, "silo": silo, "wheat_field": wheat_field,
    "mill_building": mill_building, "mill_silos": mill_silos, "mill_yard": mill_yard, "mill_yard_team": lambda m: mill_yard(m, team=True),
    "headframe": headframe, "tipple": tipple, "coal_heap": coal_heap, "mine_shed": mine_shed,
    "open_pit": open_pit, "ore_crusher": ore_crusher, "ore_heap": ore_heap,
    "blast_furnace": blast_furnace, "stacks": stacks, "steel_shed": steel_shed, "slag_heap": slag_heap, "steel_yard": steel_yard,
    "factory_shed": factory_shed, "factory_chimney": factory_chimney, "loading_dock": loading_dock, "factory_yard": factory_yard,
    "pumpjack": pumpjack, "oil_tank": oil_tank,
    "refinery_columns": refinery_columns, "refinery_tanks": refinery_tanks, "flare": flare,
    "ranch_barn": ranch_barn, "corral": corral,
    "packing_plant": packing_plant, "packing_wing": packing_wing, "stockyard": stockyard,
    "digester": digester, "paper_machine": paper_machine, "chip_pile": chip_pile, "printing_works": printing_works,
    "elevator": elevator, "elevator_annex": elevator_annex, "concrete_terminal": concrete_terminal, "terminal_annex": terminal_annex,
    "wharf": wharf, "crane": crane, "terminal_elevator": terminal_elevator, "warehouse": warehouse,
    "fish_wharf": fish_wharf, "fish_shed": fish_shed, "boat_shed": boat_shed, "cannery": cannery,
    "potash_mill": potash_mill, "tailings": tailings, "rock_dump": rock_dump, "nickel_heap": nickel_heap,
    "superstack": superstack, "roaster": roaster, "smelter_shed": smelter_shed,
    "dam": dam, "powerhouse": powerhouse, "potline": potline,
    "sand_pit": sand_pit, "upgrader": upgrader, "tailings_pond": tailings_pond,
}


def part(m, key):
    PARTS[key](m)
