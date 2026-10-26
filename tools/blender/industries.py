"""Industry pieces, one to a tile, for the layouts in packs/base/industries.json.

In the same terms as the town buildings (tools/blender/houses.py): x across,
y along, z in steps, the tile from -0.5 to 0.5 each way, built facing +y and
turned by place(). Pieces of one industry share their colours, so they read as
one place across their tiles.
"""
import math
import random

import bmesh

from mathutils import Matrix

import cars
import harbour
import horses
import houses
import rig
import roadcars
import scenery
from houses import STOREY, gable, glass, hip, paved, slab, windows
import parts
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



def moved(m, build, x, y, turn=0.0, z=0.0, scale=1.0, pitch=0.0):
    """Builds something drawn about the tile's middle facing +y, a vehicle or a team, then moves it to x, y and z steps up,
    turned by turn radians, its nose tipped up by pitch radians and scaled by scale."""
    n0 = len(m.objects)
    build(m)
    mat = Matrix.Translation((x, y, z * rig.RISE)) @ Matrix.Rotation(turn, 4, "Z") @ Matrix.Rotation(pitch, 4, "X") @ Matrix.Scale(scale, 4)
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
    facing +y, the gearbox on top and a tail vane behind, the pump rod down the middle. The head, tail and members
    scale with the wheel's radius r."""
    steel = material("galvanized", "#a8aaa6", metal=0.7, rough=0.35)
    dark = material("windmill head", "#5a5c5a", metal=0.6, rough=0.4)
    k = r / 0.072
    b, t = 0.05 * min(k, 1.2), 0.012 * k
    corners = [(-1, -1), (1, -1), (1, 1), (-1, 1)]

    def half(z):
        return b + (t - b) * z / top
    for sx, sy in corners:
        m.rod((x + sx * b, y + sy * b, 0), (x + sx * t, y + sy * t, top), 0.0028 * k, steel, segments=6)
        m.box(x + sx * b - 0.008, x + sx * b + 0.008, y + sy * b - 0.008, y + sy * b + 0.008, 0, 0.06, material("footing", "#9a968c", rough=1.0))
    n = max(5, round(top / 0.75))
    levels = [0.12] + [0.12 + (top - 0.17) * (j / n) ** 0.85 for j in range(1, n + 1)]
    for za, zb in zip(levels, levels[1:]):
        ha, hb = half(za), half(zb)
        for j in range(4):
            (sx, sy), (tx, ty) = corners[j], corners[(j + 1) % 4]
            m.rod((x + sx * hb, y + sy * hb, zb), (x + tx * hb, y + ty * hb, zb), 0.0016 * k, steel, segments=5)
            m.rod((x + sx * ha, y + sy * ha, za), (x + tx * hb, y + ty * hb, zb), 0.001 * k, steel, segments=4)
            m.rod((x + tx * ha, y + ty * ha, za), (x + sx * hb, y + sy * hb, zb), 0.001 * k, steel, segments=4)
    # A small platform under the head, and the pump rod down the middle to the pump.
    m.box(x - 0.03 * k, x + 0.03 * k, y - 0.03 * k, y + 0.03 * k, top - 0.55, top - 0.5, steel)
    m.rod((x, y, 0.3), (x, y, top), 0.0012 * k, dark, segments=4)
    m.upright(x, y, 0, 0.3, 0.008, 0.006, dark, segments=8)
    m.rod((x, y, 0.28), (x + 0.012, y + 0.01, 0.4), 0.0025, dark)
    # The head: a gearbox on a turntable, the wheel out front on its shaft.
    zc = top + 0.42 * k
    m.upright(x, y, top, top + 0.12 * k, 0.014 * k, 0.012 * k, dark, segments=12)
    m.box(x - 0.011 * k, x + 0.011 * k, y - 0.03 * k, y + 0.022 * k, top + 0.1 * k, top + 0.55 * k, dark, 0.004)
    yw = y + 0.045 * k
    m.tube(y, yw + 0.008 * k, x, zc, 0.006 * k, dark, segments=10)
    s = UNSQUASH / rig.RISE

    def at(a, rr, dy=0.0):
        return (x + rr * math.cos(a), yw + dy, zc + rr * math.sin(a) * s)
    n = 36
    for rr in (r, r * 0.62, r * 0.3):
        for j in range(n):
            m.rod(at(2 * math.pi * j / n, rr), at(2 * math.pi * (j + 1) / n, rr), 0.0014 * k, steel, segments=4)
    sail = material("windmill sails", "#c0c2be", metal=0.6, rough=0.35)
    blades = 18
    for j in range(blades):
        a = 2 * math.pi * j / blades
        w = math.pi / blades * 0.78
        # Each sail is pitched about its own length, so one edge stands forward and the other back.
        quad = [at(a - w, r * 0.3, -0.004 * k), at(a + w * 0.6, r * 0.3, 0.004 * k), at(a + w, r * 0.99, 0.009 * k), at(a - w, r * 0.99, -0.009 * k)]
        m.wedge(quad, [(p[0], p[1] + 0.0015 * k, p[2]) for p in quad], sail)
        m.rod(at(a, r * 0.08, -0.003 * k), at(a, r * 0.3), 0.0012 * k, steel, segments=4)
    # The tail: a boom back from the head with its braces and a flat vane on the end.
    ty0, ty1 = y - 0.06 * k, y - 0.16 * k
    m.rod((x, y - 0.025 * k, zc + 0.03 * k), (x, ty0, zc + 0.08 * k), 0.0025 * k, dark)
    m.rod((x, y - 0.02 * k, zc - 0.12 * k), (x, ty0, zc + 0.05 * k), 0.0015 * k, dark)
    vane = [(x, ty0, zc - 0.05 * k), (x, ty1, zc - 0.22 * k), (x, ty1, zc + 0.42 * k), (x, ty0, zc + 0.2 * k)]
    m.wedge([(p[0] - 0.0012 * k, p[1], p[2]) for p in vane], [(p[0] + 0.0012 * k, p[1], p[2]) for p in vane], sail)


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
    dz, dx = 0.45, 0.22
    m.box(-0.44, dx, y1, 0.17, 0, dz, material("dock concrete", "#a8a296", rough=0.9), 0.003)
    m.box(-0.44, dx, 0.17, 0.18, 0.05, dz - 0.02, material("dock timbers", "#5a4a3a", rough=0.9))
    slab(m, [(-0.45, y1, 1.6), (dx + 0.01, y1, 1.6), (dx + 0.01, 0.2, 1.42), (-0.45, 0.2, 1.42)], 0.05, material("canopy roof", "#5a5e62", metal=0.4, rough=0.5))
    post = material("canopy posts", "#3a3c3e", metal=0.4, rough=0.6)
    for x in (-0.4, -0.1, dx - 0.01):
        m.box(x - 0.006, x + 0.006, 0.155, 0.167, dz, 1.45, post)
    # The siding along the dock to a bumper, short of the cattle ramp.
    ty, xb = 0.34, 0.24
    tie = material("tie", "#4e3626", rough=0.9)
    rail = material("rail", "#6a6662", metal=0.9, rough=0.45)
    x = -0.45
    while x < xb - 0.02:
        m.box(x - 0.022, x + 0.022, ty - 0.15, min(0.5, ty + 0.16), 0.0, 0.04, tie)
        x += 0.11
    for s in (-1, 1):
        m.box(-0.5, xb, ty + s * 0.095 - 0.006, ty + s * 0.095 + 0.006, 0.04, 0.1, rail)
    m.box(xb - 0.004, xb + 0.03, ty - 0.12, ty + 0.12, 0.0, 0.36, material("bumper red", "#a83226", rough=0.7), 0.003)
    moved(m, lambda mm: cars.reefer(mm, 9 / 16), -0.07, ty, -math.pi / 2, z=0.1)
    # The cattle ramp up from the stockyard end to the third storey, and the chute house at its head against the wing.
    rz = 2 * STOREY + 0.3
    ramp(m, 0.27, 0.39, 0.47, -0.08, 0.15, rz)
    m.box(x1, 0.4, -0.16, -0.06, 0, rz, brick, 0.003)
    m.box(x1, 0.4, -0.16, -0.06, rz, rz + 0.85, material("ramp boards", "#8a7258", rough=0.9), 0.003)
    slab(m, [(x1, -0.17, rz + 0.95), (0.41, -0.17, rz + 0.85), (0.41, -0.05, rz + 0.85), (x1, -0.05, rz + 0.95)], 0.05,
         material("ramp roof", "#5e5a56", metal=0.3, rough=0.6))


# ---- forestry ---------------------------------------------------------------------------------

# Pieces are drawn at 0.75 of their height (BUILDING_RISE); round things stood on edge are stretched up by this to come out round.
UNSQUASH = 1 / 0.75

LOG = ("logs", "#8a6a48")
WEATHERED = ("weathered planks", "#8a7a64")


def beam(m, a, b, w, z0, z1, mat, o=0.0):
    """A straight member from a to b, each (x, y, z in steps), w tiles wide across the line in plan, from z0 to z1 steps
    over it, and o tiles off to its left."""
    dx, dy = b[0] - a[0], b[1] - a[1]
    n = math.hypot(dx, dy) or 1.0
    nx, ny = -dy / n, dx / n
    pts = []
    for p, s in ((a, -1), (b, -1), (b, 1), (a, 1)):
        pts.append((p[0] + nx * (o + s * w / 2), p[1] + ny * (o + s * w / 2), p[2]))
    m.wedge([(x, y, z + z0) for x, y, z in pts], [(x, y, z + z1) for x, y, z in pts], mat)


def conveyor(m, a, b, w, frame, belt, legs=0.2, flights=None, hood=None, load=None):
    """An open conveyor from a to b, each (x, y, z of its bed in steps): a trough with side rails over a belt or chain,
    on trestle legs every legs tiles. flights puts cross bars on a chain that far apart, hood a shallow roof over it, load
    lumps on the belt of that material."""
    dx, dy = b[0] - a[0], b[1] - a[1]
    run = math.hypot(dx, dy) or 1.0
    nx, ny = -dy / run, dx / run

    def at(f, o=0.0, z=0.0):
        return (a[0] + dx * f + nx * o, a[1] + dy * f + ny * o, a[2] + (b[2] - a[2]) * f + z)
    beam(m, a, b, w, -0.06, 0.0, frame)
    beam(m, a, b, w * 0.72, 0.0, 0.025, belt)
    for s in (-1, 1):
        beam(m, a, b, 0.005, 0.0, 0.14, frame, o=s * w / 2)
    if flights:
        n = max(1, int(run / flights))
        for k in range(n):
            f = (k + 0.5) / n
            m.rod(at(f, -w * 0.36, 0.035), at(f, w * 0.36, 0.035), 0.0025, frame, segments=5)
    if load is not None:
        rng = random.Random(int(run * 1000))
        n = max(1, int(run / 0.03))
        for k in range(n):
            f = (k + rng.uniform(0.2, 0.8)) / n
            x, y, z = at(f, rng.uniform(-0.2, 0.2) * w, 0.04)
            m.box(x - 0.006, x + 0.006, y - 0.006, y + 0.006, z, z + 0.05, load)
    if hood is not None:
        for s in (-1, 1):
            m.wedge([at(0, s * (w / 2 + 0.006), 0.2), at(1, s * (w / 2 + 0.006), 0.2), at(1, 0, 0.3), at(0, 0, 0.3)],
                    [at(0, s * (w / 2 + 0.006), 0.23), at(1, s * (w / 2 + 0.006), 0.23), at(1, 0, 0.33), at(0, 0, 0.33)], hood)
    if legs:
        n = max(1, int(run / legs))
        for k in range(n + 1):
            f = (k + 0.5) / (n + 1)
            x, y, z = at(f)
            if z < 0.25:
                continue
            for s in (-1, 1):
                px, py, _ = at(f, s * w * 0.42)
                m.box(px - 0.0035, px + 0.0035, py - 0.0035, py + 0.0035, 0, z - 0.06, frame)
            m.rod(at(f, -w * 0.42, -z + 0.05), at(f, w * 0.42, -0.1), 0.0018, frame, segments=4)
            if z > 1.0:
                m.rod(at(f, -w * 0.42, -0.1), at(f, w * 0.42, -z * 0.55), 0.0018, frame, segments=4)


def log_walls(m, x0, x1, y0, y1, top, mat, chink, r=0.013, ends=0.022):
    """Walls of round logs laid up to top steps, the courses along x and along y taking turns so they cross at the corners
    with their ends sticking out, over a chinked core."""
    m.box(x0 + r * 0.6, x1 - r * 0.6, y0 + r * 0.6, y1 - r * 0.6, 0, top, chink)
    rise = r * 2 / rig.RISE
    z, k = 0.0, 0
    while z + rise / 2 < top:
        if k % 2 == 0:
            for y in (y0 + r, y1 - r):
                m.tube_x(x0 - ends, x1 + ends, y, z + r / rig.RISE, r, mat, segments=10)
        else:
            for x in (x0 + r, x1 - r):
                m.tube(y0 - ends, y1 + ends, x, z + r / rig.RISE, r, mat, segments=10)
        z += rise / 2
        k += 1


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
    # A spruce left standing behind the camp.
    moved(m, lambda mm: scenery.tree(mm, "spruce", 2), 0.38, -0.36)


def log_pile(m):
    ground(m)
    wood = material(*LOG, rough=0.9)
    log_stack(m, -0.4, 0.4, -0.2, 0.026, 4, wood)
    log_stack(m, -0.36, 0.36, 0.22, 0.024, 3, wood)


def logging_sleigh(m, x, y0, y1, load):
    """A logging sleigh along y from y0 to y1: two bobs on runners turned up at the front, a bunk across each, and a load
    of logs lying along it chained down."""
    wood = material("sleigh wood", "#6a5038", rough=0.85)
    iron = material("sleigh iron", "#2e2c2a", metal=0.6, rough=0.5)
    for yb in (y0 + 0.05, y1 - 0.07):
        for s in (-1, 1):
            xr = x + s * 0.034
            m.box(xr - 0.004, xr + 0.004, yb - 0.045, yb + 0.045, 0.0, 0.06, iron)
            m.wedge([(xr - 0.004, yb + 0.045, 0.0), (xr + 0.004, yb + 0.045, 0.0), (xr + 0.004, yb + 0.07, 0.14), (xr - 0.004, yb + 0.07, 0.14)],
                    [(xr - 0.004, yb + 0.045, 0.06), (xr + 0.004, yb + 0.045, 0.06), (xr + 0.004, yb + 0.068, 0.2), (xr - 0.004, yb + 0.068, 0.2)], iron)
            m.box(xr - 0.004, xr + 0.004, yb - 0.03, yb + 0.03, 0.06, 0.16, wood)
        m.box(x - 0.06, x + 0.06, yb - 0.01, yb + 0.01, 0.16, 0.24, wood)
        for s in (-1, 1):
            m.box(x + s * 0.058 - 0.004, x + s * 0.058 + 0.004, yb - 0.008, yb + 0.008, 0.24, 0.42, wood)
    m.box(x - 0.006, x + 0.006, y1 - 0.03, y1 + 0.03, 0.12, 0.16, iron)
    for k, (dx, dz) in enumerate(((-0.036, 0), (0.0, 0), (0.036, 0), (-0.018, 1), (0.018, 1))):
        r = 0.018
        m.tube(y0 - 0.01 + k * 0.006, y1 - 0.02 - k * 0.004, x + dx, 0.24 + r / rig.RISE + dz * r * 1.75 / rig.RISE, r, load, segments=10)
    m.rod((x - 0.06, (y0 + y1) / 2, 0.3), (x, (y0 + y1) / 2, 0.24 + 0.036 * 2.75 / rig.RISE), 0.0018, iron, segments=4)
    m.rod((x + 0.06, (y0 + y1) / 2, 0.3), (x, (y0 + y1) / 2, 0.24 + 0.036 * 2.75 / rig.RISE), 0.0018, iron, segments=4)


def camp_shed(m):
    """The camp's horse barn: round log walls, a steep shake roof with a hay loft door and hay beam in the front gable,
    harness on hooks by the door, a manure pile out the side and a loaded logging sleigh waiting in front."""
    ground(m)
    logs_mat = material("barn logs", "#6a4e34", rough=0.95)
    chink = material("chinking", "#b8ac90", rough=1.0)
    gable_wall = material(*WEATHERED, rough=0.9)
    shakes = material("cedar shakes", "#5e4e40", rough=0.95)
    plank = material("barn door planks", "#5a4430", rough=0.9)
    x0, x1, y0, y1 = -0.24, 0.24, -0.44, 0.06
    eave, ridge = 1.05, 1.8
    log_walls(m, x0, x1, y0, y1, eave, logs_mat, chink)
    gable(m, x0 - 0.01, x1 + 0.01, y0 - 0.01, y1 + 0.01, eave, ridge, shakes, gable_wall, along_x=False, over=0.05)
    battens(m, "y", y1 + 0.01, 1, x0, x1, eave, lambda a: eave + (ridge - eave) * (1 - abs(a) / (x1 - x0) * 2) - 0.04, plank, gap=0.03)
    # Shake courses across both slopes.
    for k in range(1, 9):
        f = k / 9
        for s in (-1, 1):
            xa = s * (x1 + 0.05) * (1 - f)
            z = eave + (ridge - eave) * f + 0.085
            m.box(min(xa, xa + s * 0.01), max(xa, xa + s * 0.01), y0 - 0.06, y1 + 0.06, z - 0.03, z, material("shake butts", "#54463a", rough=0.95))
    # Double doors with a Z brace in the front, the hay loft door over them and the hay beam out from the peak.
    yf = y1 + 0.013
    for s in (-1, 1):
        m.box(min(0, s * 0.075), max(0, s * 0.075), yf - 0.004, yf + 0.004, 0, 0.82, plank)
        for z in (0.12, 0.7):
            m.box(min(0, s * 0.072), max(0, s * 0.072), yf + 0.003, yf + 0.008, z, z + 0.05, plank)
        m.wedge([(s * 0.01, yf + 0.003, 0.15), (s * 0.016, yf + 0.003, 0.15), (s * 0.072, yf + 0.003, 0.68), (s * 0.066, yf + 0.003, 0.68)],
                [(s * 0.01, yf + 0.008, 0.15), (s * 0.016, yf + 0.008, 0.15), (s * 0.072, yf + 0.008, 0.68), (s * 0.066, yf + 0.008, 0.68)], plank)
    m.box(-0.085, 0.085, yf - 0.003, yf + 0.006, 0.82, 0.86, plank)
    m.box(-0.045, 0.045, yf - 0.004, yf + 0.006, 1.12, 1.48, material("loft door", "#3e2e22", rough=0.9))
    m.box(-0.052, 0.052, yf, yf + 0.008, 1.08, 1.12, plank)
    m.box(-0.006, 0.006, y1 - 0.02, y1 + 0.09, 1.62, 1.68, plank)
    m.rod((0.0, y1 + 0.085, 1.62), (0.0, y1 + 0.085, 1.2), 0.0015, material("hay rope", "#b8a478", rough=1.0), segments=4)
    # Harness hung on pegs on the wall by the door: collars and the straps of the traces below.
    leather = material("harness leather", "#2a1e16", rough=0.6)
    brass = material("harness brass", "#b08a3a", metal=0.8, rough=0.35)
    for k, x in enumerate((0.12, 0.17)):
        m.box(x - 0.003, x + 0.003, yf, yf + 0.016, 0.68, 0.7, plank)
        rr, zc = 0.016, 0.6
        n = 10
        for i in range(n):
            a0, a1 = 2 * math.pi * i / n, 2 * math.pi * (i + 1) / n
            m.rod((x + rr * math.cos(a0), yf + 0.012, zc + rr * 1.3 * math.sin(a0) / rig.RISE),
                  (x + rr * math.cos(a1), yf + 0.012, zc + rr * 1.3 * math.sin(a1) / rig.RISE), 0.0045, leather, segments=5)
        m.upright(x, yf + 0.014, zc + rr * 1.3 / rig.RISE - 0.02, zc + rr * 1.3 / rig.RISE + 0.03, 0.004, 0.002, brass, segments=6)
        for dx in (-0.008, 0.008):
            m.box(x + dx - 0.0015, x + dx + 0.0015, yf + 0.008, yf + 0.011, 0.18, 0.5, leather)
    # Small stall windows along the side.
    for y in (-0.34, -0.2, -0.06):
        houses.on_wall(m, "x", x0, -1, y - 0.025, y + 0.025, 0, 0.012, 0.5, 0.72, material("stall shutter", "#4a3828", rough=0.9))
    # The manure pile out the side with a fork in it, a water trough and a hitching rail.
    heap(m, -0.38, -0.3, 0.1, 0.32, "#4a3a28", 17, "manure")
    m.rod((-0.37, -0.27, 0.3), (-0.33, -0.22, 0.75), 0.0025, plank, segments=4)
    m.box(-0.42, -0.34, 0.12, 0.2, 0, 0.18, plank, 0.003)
    m.box(-0.415, -0.345, 0.125, 0.195, 0.16, 0.19, material("trough water", "#3e5a66", rough=0.2))
    rail = material("hitching rail", "#6a5440", rough=0.9)
    for x in (-0.3, -0.1):
        m.box(x - 0.006, x + 0.006, 0.2, 0.212, 0, 0.5, rail)
    m.box(-0.31, -0.09, 0.2, 0.212, 0.44, 0.48, rail)
    horses.horse(m, -0.2, 0.32, horses.COATS[0])
    logging_sleigh(m, 0.2, 0.1, 0.46, material(*LOG, rough=0.9))


def sawmill(m):
    """A steam sawmill: a long board and batten mill with a monitor along its ridge, the log end open on the saw floor
    with the carriage and a log on it, a lean-to down the near side, the slab conveyor leaving for the burner and the
    covered transfer across to the green chain."""
    ground(m, "#7a6450", "sawdust yard")
    wood = material("mill boards", "#8e7656", rough=0.9)
    batten = material("mill battens", "#6e5a40", rough=0.9)
    roof = material("mill roof", "#5a5650", metal=0.3, rough=0.6)
    dark = material("mill interior", "#2a241e", rough=1.0)
    iron = material("mill iron", "#3a3836", metal=0.6, rough=0.5)
    x0, x1, y0, y1 = -0.3, 0.3, -0.46, 0.46
    eave, ridge, floor = 2.3, 3.5, 1.4
    yb = 0.3
    # The mill stands on posts with the saw floor up a storey; the log end, yb to y1, is open on the saw floor.
    m.box(x0, x1, y0, yb, 0, eave, wood, 0.003)
    boards(m, x0, x1, y0, yb, 0.0, eave, batten)
    for x in (x0, x1):
        m.box(x - 0.006 if x < 0 else x - 0.012, x + 0.012 if x < 0 else x + 0.006, yb, y1, 0, eave, wood)
        battens(m, "x", x, 1 if x > 0 else -1, yb, y1, 0, eave, batten)
    m.box(x0, x1, yb, y1, 0, floor - 0.1, wood)
    battens(m, "y", y1, 1, x0, x1, 0, floor - 0.1, batten)
    m.box(x0, x1, yb, y1 + 0.02, floor - 0.1, floor, material("saw floor", "#6a5640", rough=0.9))
    m.box(x0 + 0.01, x1 - 0.01, yb - 0.002, yb + 0.002, floor, eave, dark)
    gable(m, x0, x1, y0, y1, eave, ridge, roof, wood, along_x=False, over=0.03)
    # The carriage on its track with a log dogged to it, and the band saw's wheel housing behind.
    for x in (-0.06, 0.06):
        m.box(x - 0.004, x + 0.004, yb, y1, floor, floor + 0.04, iron)
    m.box(-0.07, 0.07, yb + 0.03, y1 - 0.02, floor + 0.04, floor + 0.12, material("carriage", "#5a4a3a", rough=0.7))
    for y in (yb + 0.05, y1 - 0.05):
        m.box(0.03, 0.06, y - 0.01, y + 0.01, floor + 0.12, floor + 0.38, iron)
    m.tube(yb + 0.01, y1 + 0.03, 0.0, floor + 0.12 + 0.03 / rig.RISE, 0.03, material(*LOG, rough=0.9), segments=12)
    m.box(-0.2, -0.12, yb + 0.01, yb + 0.06, floor, eave - 0.15, material("band mill", "#4a5a4e", metal=0.4, rough=0.5))
    # Windows along the saw floor, and the monitor along the ridge with louvres to let the dust out.
    for face, at, d in (("x", x0, -1), ("x", x1, 1)):
        for k in range(5):
            houses.pane(m, face, at, d, y0 + 0.08 + k * 0.16, 1.55, 2.05, 0.06, glass(), batten, wood, sash=False, bars=1)
    m.box(-0.06, 0.06, y0 + 0.05, y1 - 0.05, 3.3, 3.75, wood)
    slat = material("monitor louvres", "#5e4c38", rough=0.9)
    for x, d in ((-0.06, -1), (0.06, 1)):
        for k in range(6):
            houses.louvres(m, "x", x, d, y0 + 0.12 + k * 0.14, 3.42, 3.68, 0.08, batten, slat)
    gable(m, -0.06, 0.06, y0 + 0.05, y1 - 0.05, 3.75, 3.85, roof, wood, along_x=False, over=0.03, thick=0.05)
    # The lean-to down the near side, the filing room and engine room, with its own windows and door.
    lx = -0.43
    m.box(lx, x0, -0.4, 0.22, 0, 1.25, wood, 0.003)
    boards(m, lx, x0, -0.4, 0.22, 0.0, 1.25, batten)
    slab(m, [(lx - 0.03, -0.43, 1.25), (x0, -0.43, 1.75), (x0, 0.25, 1.75), (lx - 0.03, 0.25, 1.25)], 0.06, roof)
    for y in (-0.3, -0.12, 0.1):
        houses.pane(m, "x", lx, -1, y, 0.45, 0.95, 0.05, glass(), batten, wood, sash=True)
    houses.on_wall(m, "x", lx, -1, -0.04, 0.02, 0, 0.008, 0, 0.85, material("plank door", "#4a3220", rough=0.9))
    # The slab and sawdust conveyor coming out of the lean-to's end, bound for the burner.
    conveyor(m, (-0.4, 0.2, 1.15), (-0.4, 0.5, 1.35), 0.06, iron, material("conveyor belt", "#2e2a26", rough=0.8), legs=0.12,
             load=material("slabs and sawdust", "#b89a6c", rough=1.0))
    # The covered transfer out the far side to the green chain.
    m.box(x1, 0.5, -0.1, 0.18, 0.75, 0.82, wood)
    m.box(x1, 0.5, -0.1, -0.09, 0.82, 1.35, wood)
    m.box(x1, 0.5, 0.17, 0.18, 0.82, 1.35, wood)
    slab(m, [(x1, -0.12, 1.5), (0.5, -0.12, 1.5), (0.5, 0.2, 1.5), (x1, 0.2, 1.5)], 0.05, roof)
    for x in (0.4, 0.49):
        for y in (-0.1, 0.17):
            m.box(x - 0.006, x + 0.006, y - 0.006, y + 0.006, 0, 0.75, wood)
    # Sawdust drifted round the foot of the mill.
    heap(m, 0.38, -0.36, 0.1, 0.25, "#c0a070", 23, "sawdust")


def green_chain(m):
    """The sawmill's other side: the green chain, a long roofed platform where the boards come out of the mill on cross
    chains and are pulled off by hand onto lumber carts, and the boiler house with its guyed steel stack and hog fuel."""
    ground(m, "#7a6450", "sawdust yard")
    wood = material("mill boards", "#8e7656", rough=0.9)
    batten = material("mill battens", "#6e5a40", rough=0.9)
    roof = material("mill roof", "#5a5650", metal=0.3, rough=0.6)
    iron = material("mill iron", "#3a3836", metal=0.6, rough=0.5)
    lumber = material("fresh lumber", "#d0b080", rough=0.8)
    # The platform, its deck at the transfer's height, open sided under a shed roof on posts.
    px0, px1, py0, py1, deck = -0.5, 0.22, -0.1, 0.18, 0.75
    m.box(px0, px1, py0, py1, deck - 0.07, deck, material("platform deck", "#7a6448", rough=0.9))
    for x in (-0.36, -0.14, 0.08, 0.21):
        for y in (py0 + 0.008, py1 - 0.008):
            m.box(x - 0.007, x + 0.007, y - 0.007, y + 0.007, 0, 1.45, wood)
    slab(m, [(px0, py0 - 0.04, 1.45), (px1 + 0.03, py0 - 0.04, 1.45), (px1 + 0.03, py1 + 0.04, 1.6), (px0, py1 + 0.04, 1.6)], 0.05, roof)
    # The cross chains running out along the platform and boards riding on them.
    for y in (py0 + 0.05, (py0 + py1) / 2, py1 - 0.05):
        m.box(px0, px1 - 0.02, y - 0.004, y + 0.004, deck, deck + 0.03, iron)
    rng = random.Random(9)
    for k in range(7):
        x = px0 + 0.06 + k * 0.09 + rng.uniform(-0.01, 0.01)
        m.box(x - 0.012, x + 0.012, py0 + 0.02, py1 - 0.02, deck + 0.03, deck + 0.06, lumber)
    # Boards sorted onto carts along the platform's side, ready to go to the yard.
    for x in (-0.3, -0.06, 0.16):
        m.box(x - 0.07, x + 0.07, py1 + 0.03, py1 + 0.13, 0.08, 0.14, iron)
        for s in (-1, 1):
            m.tube_x(x + s * 0.05 - 0.005, x + s * 0.05 + 0.005, py1 + 0.08, 0.04 / rig.RISE, 0.03, iron)
        z = 0.14
        for _ in range(3 + rng.randrange(3)):
            m.box(x - 0.065, x + 0.065, py1 + 0.035, py1 + 0.125, z, z + 0.09, lumber)
            z += 0.09
    # The boiler house: board walls, a pitched roof with a ventilator, its firing door and the steel stack, guyed.
    bx0, bx1, by0, by1 = -0.1, 0.42, -0.46, -0.18
    m.box(bx0, bx1, by0, by1, 0, 1.5, wood, 0.003)
    boards(m, bx0, bx1, by0, by1, 0.0, 1.5, batten)
    gable(m, bx0, bx1, by0, by1, 1.5, 2.15, roof, wood, along_x=True, over=0.03)
    cupola(m, 0.05, (by0 + by1) / 2, 2.1, 0.06, 0.32, wood, roof, batten)
    for x in (0.0, 0.3):
        houses.pane(m, "y", by1, 1, x, 0.5, 1.0, 0.06, glass(), batten, wood, sash=True)
    m.box(0.12, 0.2, by1, by1 + 0.008, 0, 0.95, material("boiler house doors", "#4a3220", rough=0.9))
    sx, sy = 0.32, -0.32
    stack(m, sx, sy, 2.0, 5.2, 0.028, material("stack steel", "#2e2c2c", metal=0.5, rough=0.6), material("stack band", "#4a3a34", metal=0.4, rough=0.6))
    m.upright(sx, sy, 5.2, 5.28, 0.04, 0.04, iron, segments=16)
    guy = material("guy wire", "#2a2a2a", metal=0.6, rough=0.5)
    for gx, gy in ((0.48, -0.48), (0.48, -0.08), (-0.02, -0.48)):
        m.rod((sx, sy, 4.4), (gx, gy, 0.0), 0.0012, guy, segments=4)
    # Hog fuel heaped by the firing door, and a slab pile.
    heap(m, 0.32, -0.06, 0.1, 0.35, "#8a6a48", 31, "hog fuel")
    logs(m, -0.44, -0.16, -0.3, 0.0, 0.012, 5, material("slabs", "#a07a52", rough=0.9))


def log_deck(m):
    """The log deck at the mill's end: logs lying on skid timbers that slope to the foot of the jack ladder, a chain
    conveyor in a trough with side rails that carries them up into the mill, roofed over at the top, and the slab
    conveyor passing along the side to the burner."""
    ground(m, "#5e4e3e", "wet log deck")
    wood = material(*LOG, rough=0.9)
    frame = material("jack ladder", "#5a4630", rough=0.9)
    chain = material("ladder chain", "#2e2c2a", metal=0.6, rough=0.5)
    roof = material("mill roof", "#5a5650", metal=0.3, rough=0.6)
    a, b = (0.0, 0.26, 0.08), (0.0, -0.5, 1.4)
    conveyor(m, a, b, 0.08, frame, chain, legs=0.14, flights=0.035)

    def on_ladder(y):
        return a[2] + (b[2] - a[2]) * (y - a[1]) / (b[1] - a[1])
    for ya, yb in ((-0.42, -0.24), (-0.05, 0.13)):
        m.rod((0.0, ya, on_ladder(ya) + 0.03 + 0.02 / rig.RISE), (0.0, yb, on_ladder(yb) + 0.03 + 0.02 / rig.RISE), 0.02, wood, segments=10)
    # The roof over the top of the ladder, on posts, meeting the mill's gable.
    for x in (-0.07, 0.07):
        for y in (-0.47, -0.2):
            m.box(x - 0.006, x + 0.006, y - 0.006, y + 0.006, 0, 2.05, frame)
    gable(m, -0.075, 0.075, -0.5, -0.18, 2.05, 2.35, roof, frame, along_x=False, over=0.02, thick=0.05)
    m.box(-0.075, 0.075, -0.2, -0.19, 1.8, 2.05, frame)
    # Skids on both sides sloping down to the ladder's foot, with logs lying along them waiting to roll in.
    skid = material("deck skids", "#4a3a2c", rough=0.95)
    for s, x0, x1 in ((1, 0.06, 0.46), (-1, -0.06, -0.33)):
        for y in (-0.08, 0.12, 0.32):
            m.wedge([(x0, y - 0.012, 0.0), (x1, y - 0.012, 0.0), (x1, y + 0.012, 0.0), (x0, y + 0.012, 0.0)],
                    [(x0, y - 0.012, 0.1), (x1, y - 0.012, 0.42), (x1, y + 0.012, 0.42), (x0, y + 0.012, 0.1)], skid)
        rng = random.Random(7 + s)
        n = 6 if s > 0 else 4
        for k in range(n):
            x = x0 + s * (0.035 + k * 0.05)
            z = 0.1 + 0.32 * abs(x - x0) / abs(x1 - x0)
            r = rng.uniform(0.018, 0.024)
            m.tube(-0.12 + rng.uniform(-0.03, 0.03), 0.38 + rng.uniform(-0.03, 0.03), x, z + r / rig.RISE, r, wood, segments=10)
            if k % 2 == 1 and k < n - 1:
                m.tube(-0.1, 0.36, x + s * 0.025, z + 0.04 + 2.6 * r / rig.RISE, r * 0.9, wood, segments=10)
    # The slab conveyor along the side, on to the burner.
    iron = material("mill iron", "#3a3836", metal=0.6, rough=0.5)
    conveyor(m, (-0.4, -0.5, 1.35), (-0.4, 0.5, 2.3), 0.06, iron, material("conveyor belt", "#2e2a26", rough=0.8), legs=0.2,
             load=material("slabs and sawdust", "#b89a6c", rough=1.0))


def burner(m):
    """The teepee burner, where a sawmill burned its waste: a tall steel cone with a mesh dome."""
    ground(m, "#5e5650", "cinders")
    steel = material("burner rust", "#6a4636", metal=0.3, rough=0.8)
    mesh = material("burner screen", "#3a3634", metal=0.4, rough=0.6)
    m.upright(0, 0, 0, 5.2, 0.34, 0.12, steel, segments=24)
    m.upright(0, 0, 5.2, 5.5, 0.12, 0.12, mesh, segments=24)
    m.lathe(0, 0, 5.5, [(0.12, 0), (0.11, 0.15), (0.07, 0.3), (0.0, 0.38)], mesh, segments=24)
    m.box(-0.06, 0.06, 0.31, 0.36, 0, 0.6, material("burner door", "#2a2624", rough=0.8))
    # The slab conveyor coming from the mill along the log deck, up into the cone's side.
    conveyor(m, (-0.4, -0.5, 2.3), (-0.14, -0.14, 3.55), 0.06, material("mill iron", "#3a3836", metal=0.6, rough=0.5),
             material("conveyor belt", "#2e2a26", rough=0.8), legs=0.2, load=material("slabs and sawdust", "#b89a6c", rough=1.0))


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

def siding(m, x0, x1, ty, z=0.0):
    """Track along x from x0 to x1 at y = ty on its ballast, z steps up."""
    tie = material("tie", "#4e3626", rough=0.9)
    rail = material("rail", "#6a6662", metal=0.9, rough=0.45)
    m.box(x0, x1, ty - 0.16, ty + 0.16, z, z + 0.03, material("ballast", "#7a746a", rough=1.0))
    x = x0 + 0.03
    while x < x1 - 0.01:
        m.box(x - 0.02, x + 0.02, ty - 0.14, ty + 0.14, z + 0.02, z + 0.06, tie)
        x += 0.1
    for s in (-1, 1):
        m.box(x0, x1, ty + s * 0.095 - 0.006, ty + s * 0.095 + 0.006, z + 0.06, z + 0.12, rail)


def sheave(m, x, y, z, r, mat):
    """A spoked sheave wheel turning in the y-z plane, its middle at x, y and z steps up, r tiles round."""
    s = UNSQUASH / rig.RISE

    def at(a, rr):
        return (x, y + rr * math.cos(a), z + rr * math.sin(a) * s)
    n = 24
    for rr, t in ((r, 0.006), (r * 0.86, 0.0025)):
        for k in range(n):
            m.rod(at(2 * math.pi * k / n, rr), at(2 * math.pi * (k + 1) / n, rr), t, mat, segments=5)
    for k in range(8):
        m.rod((x, y, z), at(2 * math.pi * k / 8 + 0.2, r * 0.86), 0.002, mat, segments=4)
    m.tube_x(x - 0.012, x + 0.012, y, z, r * 0.16, mat, segments=10)


def terrain(m, height, mats, pick, n=110, oy=0.0, wall=1, ox=0.0):
    """Shaped ground over the tile: height(x, y) in steps on an n by n grid, x and y offset by ox and oy for a shape that
    runs across more than one tile, with walls of mats[wall] down its edges. pick(x, y, slope) gives each face's index in
    mats, slope its rise in steps a tile."""
    bm = bmesh.new()
    picks = []
    xs = [-0.5 + i / n for i in range(n + 1)]
    hs = [[height(x + ox, y + oy) for y in xs] for x in xs]
    vs = [[bm.verts.new((x, y, m.z(hs[i][j]) * rig.RISE)) for j, y in enumerate(xs)] for i, x in enumerate(xs)]
    for i in range(n):
        for j in range(n):
            bm.faces.new((vs[i][j], vs[i + 1][j], vs[i + 1][j + 1], vs[i][j + 1]))
            hh = (hs[i][j], hs[i + 1][j], hs[i + 1][j + 1], hs[i][j + 1])
            picks.append(pick((xs[i] + xs[i + 1]) / 2 + ox, (xs[j] + xs[j + 1]) / 2 + oy, (max(hh) - min(hh)) * n))
    # Walls down the four edges to the ground.
    edges = [[vs[i][0] for i in range(n + 1)], [vs[n][j] for j in range(n + 1)], [vs[i][n] for i in range(n, -1, -1)], [vs[0][j] for j in range(n, -1, -1)]]
    for line in edges:
        low = [bm.verts.new((v.co.x, v.co.y, 0.0)) for v in line]
        for k in range(len(line) - 1):
            bm.faces.new((low[k], low[k + 1], line[k + 1], line[k]))
            picks.append(wall)
    # Every face turned to look up or out from the tile's middle.
    for f in bm.faces:
        f.normal_update()
        c = f.calc_center_median()
        if f.normal.z < -1e-6 or (abs(f.normal.z) <= 1e-6 and f.normal.x * c.x + f.normal.y * c.y < 0):
            f.normal_flip()
    obj = m._mesh("terrain", bm, mats[0])
    for mat in mats[1:]:
        obj.data.materials.append(mat)
    obj.data.polygons.foreach_set("material_index", picks)
    return obj


def benched(e, steps, w=0.012):
    """The height of benched ground at e: steps is [(e out to, height)], each bench flat with a steep face to the next."""
    h = steps[0][1]
    for (t, h0), (_, h1) in zip(steps, steps[1:]):
        f = min(1.0, max(0.0, (e - t) / w + 0.5))
        h += (h1 - h0) * f * f * (3 - 2 * f)
    return h


def bumps(x, y, seed):
    """A little roughness, from -1 to 1, for loose rock on the benches."""
    return (math.sin(x * 61 + seed) * math.sin(y * 53 + seed * 1.7) + math.sin((x + y) * 97 + seed * 2.3) * 0.5) / 1.5


class Benches:
    """Benched ground round a middle at cx, cy, rx by ry tiles to its outer edge (e = 1), wobbled by seed so it isn't a
    clean oval, with a road spiralling over the benches: from angle a0, turning by turns, from e0 at h0 steps to e1 at h1.
    face is how far in e each bench's face runs, small for a cut face and larger for loose rock at its angle of rest."""

    def __init__(self, cx, cy, rx, ry, steps, seed, road=None, rough=0.012, face=0.012, edge=0.0):
        self.cx, self.cy, self.rx, self.ry, self.steps, self.seed, self.road, self.rough = cx, cy, rx, ry, steps, seed, road, rough
        self.face = face
        # How far in e past the road's side the ground blends down or up to the benches, so its sides aren't cliffs.
        self.edge = edge

    def at(self, x, y):
        dx, dy = (x - self.cx) / self.rx, (y - self.cy) / self.ry
        a = math.atan2(dy, dx)
        s = self.seed
        wob = 1 + 0.07 * math.sin(3 * a + s) + 0.045 * math.sin(5 * a + s * 2.1) + 0.03 * math.sin(8 * a + s * 0.7)
        return math.hypot(dx, dy) / wob, a

    def on_road(self, e, a):
        """The road's height where e, a is on it, or None."""
        if self.road is None:
            return None
        a0, turns, e0, e1, h0, h1, w = self.road
        span = turns * math.tau
        for k in range(-1, int(turns) + 2):
            t = (a - a0) * (1 if turns > 0 else -1) + k * math.tau
            if 0 <= t <= abs(span):
                f = t / abs(span)
                if abs(e - (e0 + (e1 - e0) * f)) < w:
                    return h0 + (h1 - h0) * f
        return None

    def near_road(self, e, a):
        """Near the road's sides or ends, its height there and how far into the blend to the benches, 0 to 1; else None."""
        a0, turns, e0, e1, h0, h1, w = self.road
        span = abs(turns * math.tau)
        best = None
        for k in range(-1, int(abs(turns)) + 2):
            t = (a - a0) * (1 if turns > 0 else -1) + k * math.tau
            over = max(0.0, t - span, -t) * e
            f = min(1.0, max(0.0, t / span))
            d = abs(e - (e0 + (e1 - e0) * f))
            g = max((d - w) / self.edge, over / self.edge, 0.0)
            if g < 1 and (best is None or g < best[1]):
                best = (h0 + (h1 - h0) * f, g)
        return best

    def height(self, x, y):
        e, a = self.at(x, y)
        r = self.on_road(e, a)
        if r is not None:
            return r
        h = benched(e, self.steps, self.face) + self.rough * bumps(x, y, self.seed) * min(1.0, max(0.0, (1.0 - e) / 0.1))
        if self.edge and self.road is not None:
            near = self.near_road(e, a)
            if near is not None:
                f = near[1]
                f = f * f * (3 - 2 * f)
                h = near[0] + (h - near[0]) * f
        return h

    def on_face(self, x, y, pad=0.0):
        """Whether x, y is on one of the bench faces, by how far out it is rather than how steep, so the faces come out
        as clean bands on terrain()'s grid; and the height of the bench below it. pad widens the band, by about half a
        grid square in e, so a square the face only crosses a corner of still counts."""
        e, _ = self.at(x, y)
        for (t, h0), (_, h1) in zip(self.steps, self.steps[1:]):
            if abs(e - t) < self.face * 0.5 + pad:
                return True, min(h0, h1)
        return False, None

    def pick(self, faces):
        """A pick() for terrain(): 3 on the road, the bench faces 1 or 2 by where round they are, 0 on the benches."""
        def pick(x, y, slope):
            e, a = self.at(x, y)
            if self.on_road(e, a) is not None:
                return 3
            if slope > faces:
                return 2 if math.sin(2 * a + self.seed * 1.3) + 0.4 * math.sin(7 * a) > -0.4 else 1
            return 0
        return pick


# ---- mines ----------------------------------------------------------------------------------------

# Where the headframe's conveyor crosses into the next tile (+y), x across and its bed's height, and how fast it climbs,
# in steps a tile: it carries on at the same slope up the tipple, into the potash mill or out over the rock dump.
HEAD_X, HEAD_Z, HEAD_CLIMB = 0.3, 3.0, 4.2


def head_z(y, beyond=False):
    """The headframe conveyor's bed at y, in the headframe's tile or the one beyond it."""
    return HEAD_Z + (y + (0.5 if beyond else -0.5)) * HEAD_CLIMB


def head_conveyor(m, y0, y1, frame, belt, wall=None, roof=None, legs=0.25, beyond=False):
    """The headframe's conveyor along x = HEAD_X from y0 to y1, in the headframe's tile or the one beyond: open on
    trestles, or an enclosed gallery if wall is given."""
    a = (HEAD_X, y0, head_z(y0, beyond))
    b = (HEAD_X, y1, head_z(y1, beyond))
    if wall is None:
        conveyor(m, a, b, 0.06, frame, belt, legs=legs, hood=roof, load=material("ore on belt", "#4a4440", rough=1.0))
    else:
        gallery(m, a, b, 0.075, 0.42, wall, roof, glass())
    return a, b


def hoist_ropes(m, xs, a, b, mat):
    """Hoist ropes from the sheaves at a down to the drum at b, each (y, z), one at each x."""
    for x in xs:
        m.rod((x, a[0], a[1]), (x, b[0], b[1]), 0.0018, mat, segments=4)


def headframe(m, era=2):
    """A mine's headframe over the shaft. era 0 a timber A-frame, 1 an open steel frame with its backlegs braced down to
    the hoist house, 2 a tall concrete tower with the hoists in the headhouse on top, as at Sudbury and the potash mines.
    The ore goes up out of a bin house beside it on a conveyor to the next tile (+y)."""
    ground(m, "#3e3a36", "mine yard")
    sx, sy = 0.0, 0.04
    g = glass()
    trim = material("mine trim", "#c8bca8", rough=0.8)
    belt = material("conveyor belt", "#2e2a26", rough=0.8)
    if era == 2:
        _headframe_tower(m, sx, sy, g, belt)
        return
    if era == 0:
        frame = material("headframe timber", "#6a5640", rough=0.9)
        board = material("shaft house boards", "#7a6248", rough=0.9)
        roof = material("shake roof", "#4e4640", rough=0.9)
        r = 0.009
    else:
        frame = material("headframe steel", "#4e4844", metal=0.5, rough=0.55)
        board = material("shaft house siding", "#7e7a72", metal=0.4, rough=0.6)
        roof = material("shaft house roof", "#4a4e52", metal=0.4, rough=0.6)
        r = 0.0065
    rope = material("hoist rope", "#2a2a2a", metal=0.6, rough=0.5)
    # Four legs leaning in from the collar to the sheave deck, girts and cross braces on every face.
    top = 6.4
    b0x, b0y, b1x, b1y = 0.11, 0.09, 0.055, 0.05
    corners = [(-1, -1), (1, -1), (1, 1), (-1, 1)]

    def pt(c, z):
        f = z / top
        hx, hy = b0x + (b1x - b0x) * f, b0y + (b1y - b0y) * f
        return (sx + c[0] * hx, sy + c[1] * hy, z)
    for c in corners:
        if era == 0:
            p, q = pt(c, 0), pt(c, top)
            m.rod(p, q, r * 1.3, frame, segments=4)
        else:
            m.rod(pt(c, 0), pt(c, top), r, frame, segments=6)
        p = pt(c, 0)
        m.box(p[0] - 0.014, p[0] + 0.014, p[1] - 0.014, p[1] + 0.014, 0, 0.12, material("footing", "#9a968c", rough=1.0))
    levels = [1.4, 2.4, 3.4, 4.4, 5.4, top]
    for za, zb in zip([0.15] + levels, levels):
        for k in range(4):
            ca, cb = corners[k], corners[(k + 1) % 4]
            m.rod(pt(ca, zb), pt(cb, zb), r * 0.8, frame, segments=4)
            m.rod(pt(ca, za), pt(cb, zb), r * 0.45, frame, segments=4)
            if era == 1:
                m.rod(pt(cb, za), pt(ca, zb), r * 0.45, frame, segments=4)
    # The sheave deck with its rail, two sheaves on it, and over a timber frame a small hood.
    deck = material("sheave deck", "#5a5450", metal=0.3, rough=0.7)
    m.box(sx - 0.085, sx + 0.085, sy - 0.08, sy + 0.08, top, top + 0.08, deck)
    post = frame
    for xx in (sx - 0.08, sx + 0.08):
        for yy in (sy - 0.075, sy + 0.075):
            m.box(xx - 0.003, xx + 0.003, yy - 0.003, yy + 0.003, top + 0.08, top + 0.45, post)
    for xx in (sx - 0.08, sx + 0.08):
        m.box(xx - 0.002, xx + 0.002, sy - 0.075, sy + 0.075, top + 0.42, top + 0.45, post)
    m.box(sx - 0.08, sx + 0.08, sy + 0.073, sy + 0.077, top + 0.42, top + 0.45, post)
    wr = 0.055
    wz = top + 0.08 + wr / rig.RISE * UNSQUASH + 0.04
    for xx in (sx - 0.035, sx + 0.035):
        sheave(m, xx, sy, wz, wr, material("sheave", "#3a3a3c", metal=0.6, rough=0.4))
        m.box(xx - 0.01, xx + 0.01, sy - 0.012, sy + 0.012, top + 0.08, wz, frame)
    if era == 0:
        gable(m, sx - 0.1, sx + 0.1, sy - 0.1, sy + 0.1, wz + 0.45, wz + 0.8, roof, board, along_x=False, over=0.015, thick=0.05)
        for xx in (sx - 0.09, sx + 0.09):
            for yy in (sy - 0.09, sy + 0.09):
                m.box(xx - 0.004, xx + 0.004, yy - 0.004, yy + 0.004, top + 0.08, wz + 0.45, frame)
    # The backlegs, braced down towards the hoist house to take the ropes' pull.
    fy = -0.2
    for s in (-1, 1):
        a, b = (sx + s * 0.06, sy - 0.04, top), (sx + s * 0.1, fy, 0.0)
        if era == 0:
            m.rod(a, b, r * 1.5, frame, segments=4)
        else:
            harbour.lattice(m, b, a, 0.03, 0.02, frame, n=10, r=0.003, axis=(1.0, 0.0))
        m.box(b[0] - 0.016, b[0] + 0.016, b[1] - 0.016, b[1] + 0.016, 0, 0.12, material("footing", "#9a968c", rough=1.0))
    for z in (1.6, 3.4, 5.0):
        f = z / top
        y = fy + (sy - 0.04 - fy) * f
        w = 0.1 + (0.06 - 0.1) * f
        m.rod((sx - w, y, z), (sx + w, y, z), r * 0.7, frame, segments=4)
        # A strut back from the backlegs to the tower at each level.
        m.rod((sx, y, z), pt((0, -1), z), r * 0.6, frame, segments=4)
    # The shaft house round the collar, the tower rising through its roof.
    hx0, hx1, hy0, hy1 = sx - 0.14, sx + 0.14, sy - 0.12, sy + 0.14
    if era == 0:
        boards(m, hx0, hx1, hy0, hy1, 0, 1.2, board)
    else:
        clad_box(m, hx0, hx1, hy0, hy1, 0, 1.2, board, material("siding ribs", "#6a665e", metal=0.4, rough=0.6))
    gable(m, hx0, hx1, hy0, hy1, 1.2, 1.7, roof, board, along_x=False, over=0.02, thick=0.05)
    m.box(sx - 0.03, sx + 0.03, hy1, hy1 + 0.006, 0, 0.75, material("door", "#4a3a2e", rough=0.8))
    houses.pane(m, "x", hx0, -1, sy, 0.45, 0.85, 0.05, g, trim, trim, sash=True)
    # The hoist house behind, the ropes coming down to its end wall.
    ux0, ux1, uy0, uy1 = -0.26, 0.24, -0.48, -0.25
    if era == 0:
        boards(m, ux0, ux1, uy0, uy1, 0, 1.4, board)
        gable(m, ux0, ux1, uy0, uy1, 1.4, 2.1, roof, board, along_x=False, trim=trim)
        windows(m, ux0, ux1, uy0, uy1, 0.0, 1, 0, 2, g, trim)
        # The boiler's brick stack for the steam hoist, guyed.
        brick = material("stack brick", "#7e4232", rough=0.9)
        stack(m, 0.36, -0.4, 0, 4.2, 0.035, brick, material("stack band", "#3a2a24", rough=0.9))
        for gx, gy in ((0.48, -0.48), (0.46, -0.22), (0.24, -0.48)):
            m.rod((0.36, -0.4, 3.6), (gx, gy, 0.0), 0.0012, rope, segments=4)
        m.box(0.26, 0.46, -0.48, -0.3, 0, 0.9, board)
        gable(m, 0.26, 0.46, -0.48, -0.3, 0.9, 1.25, roof, board, along_x=False, over=0.015, thick=0.05)
        logs(m, -0.46, -0.32, -0.15, 0.02, 0.012, 3, material(*LOG, rough=0.9))
    else:
        brick = material("hoist house brick", "#7e4232", rough=0.9)
        m.box(ux0, ux1, uy0, uy1, 0, 1.5, brick, 0.003)
        gable(m, ux0, ux1, uy0, uy1, 1.5, 2.15, roof, brick, along_x=False, trim=trim)
        for y in (-0.42, -0.31):
            for x, d in ((ux0, -1), (ux1, 1)):
                houses.arched_pane(m, "x", x, d, y, 0.4, 1.15, 0.06, g, trim, trim, rise=0.08, keystone=False)
        # The transformer yard for the electric hoist.
        fence = material("yard fence", "#6a6a66", metal=0.5, rough=0.5)
        m.box(0.3, 0.46, -0.46, -0.3, 0, 0.04, material("yard gravel", "#8a8478", rough=1.0))
        for x in (0.34, 0.42):
            m.box(x - 0.025, x + 0.025, -0.42, -0.36, 0.04, 0.45, material("transformer", "#5a6a5e", metal=0.4, rough=0.6), 0.004)
            for k in range(3):
                m.upright(x - 0.015 + k * 0.015, -0.39, 0.45, 0.65, 0.004, 0.003, material("insulator", "#8a5a3a", rough=0.5), segments=6)
        for a, b in (((0.3, -0.46), (0.46, -0.46)), ((0.46, -0.46), (0.46, -0.3)), ((0.46, -0.3), (0.3, -0.3)), ((0.3, -0.3), (0.3, -0.46))):
            houses.chain(m, a, b, height=0.5)
    m.box(sx - 0.05, sx + 0.05, uy1, uy1 + 0.008, 1.0, 1.25, material("rope slot", "#1a1a1a", rough=0.9))
    hoist_ropes(m, (sx - 0.035, sx + 0.035), (sy - wr * 0.5, wz + 0.2), (uy1, 1.12), rope)
    # The bin house beside the tower, the skip's chute down into it, and the conveyor away up to the next tile.
    bx0, bx1, by0, by1 = 0.19, 0.42, -0.2, 0.04
    if era == 0:
        boards(m, bx0, bx1, by0, by1, 0, 1.3, board)
    else:
        clad_box(m, bx0, bx1, by0, by1, 0, 1.3, board, material("siding ribs", "#6a665e", metal=0.4, rough=0.6))
    slab(m, [(bx0 - 0.02, by0 - 0.02, 1.55), (bx1 + 0.02, by0 - 0.02, 1.3), (bx1 + 0.02, by1 + 0.02, 1.3), (bx0 - 0.02, by1 + 0.02, 1.55)], 0.05, roof)
    m.wedge([(sx + 0.06, sy - 0.03, 4.2), (sx + 0.06, sy + 0.03, 4.2), (sx + 0.08, sy + 0.03, 4.3), (sx + 0.08, sy - 0.03, 4.3)],
            [(bx0 + 0.02, -0.11, 1.5), (bx0 + 0.02, -0.05, 1.5), (bx0 + 0.04, -0.05, 1.6), (bx0 + 0.04, -0.11, 1.6)], frame)
    head_conveyor(m, by1 - 0.06, 0.5, frame, belt, roof=roof if era == 0 else None)
    m.box(bx0 + 0.04, bx0 + 0.1, by1, by1 + 0.006, 0, 0.7, material("door", "#4a3a2e", rough=0.8))
    harbour.drum(m, -0.4, 0.3, 0.02)
    harbour.drum(m, -0.36, 0.34, 0.02, colour="#7a3a2a")
    if era == 0:
        m.box(-0.44, -0.24, 0.24, 0.44, 0.02, 0.06, material("mine timbers", "#7a6248", rough=0.9))
        logs(m, -0.44, -0.24, 0.32, 0.06, 0.012, 4, material("mine props", "#8a7050", rough=0.9))
    else:
        for x in (-0.42, -0.3):
            m.box(x - 0.04, x + 0.04, 0.22, 0.42, 0.02, 0.3, material("ore car", "#5a4a42", metal=0.4, rough=0.6), 0.004)


def _headframe_tower(m, sx, sy, g, belt):
    """The late headframe: a slipformed concrete tower with pilasters and a stair of small windows, the clad headhouse
    on top where the friction hoists are, the collar house at its foot and an enclosed gallery away from the bin house."""
    concrete = material("headframe concrete", "#bab4a8", rough=0.9)
    clad = material("headhouse cladding", "#8e3a2e", metal=0.3, rough=0.6)
    rib = material("headhouse ribs", "#7a3228", metal=0.3, rough=0.6)
    trim = material("headframe trim", "#5a5c5e", metal=0.3, rough=0.6)
    tx0, tx1, ty0, ty1, top = sx - 0.12, sx + 0.12, sy - 0.11, sy + 0.13, 6.5
    m.box(tx0, tx1, ty0, ty1, 0, top, concrete, 0.01)
    for x in (tx0 + 0.03, sx, tx1 - 0.03):
        for y, d in ((ty0, -1), (ty1, 1)):
            houses.on_wall(m, "y", y, d, x - 0.012, x + 0.012, 0, 0.01, 0.2, top, concrete)
    for y in (ty0 + 0.04, ty1 - 0.04):
        for x, d in ((tx0, -1), (tx1, 1)):
            houses.on_wall(m, "x", x, d, y - 0.012, y + 0.012, 0, 0.01, 0.2, top, concrete)
    for k in range(8):
        z = 0.7 + k * 0.7
        houses.pane(m, "x", tx1, 1, sy + 0.04, z, z + 0.2, 0.025, g, trim, trim, sash=False)
        houses.pane(m, "y", ty1, 1, sx - 0.06, z + 0.35, z + 0.55, 0.025, g, trim, trim, sash=False)
    # The headhouse, wider than the tower, a band of windows round it and the hoist motors' vents on its roof.
    hx0, hx1, hy0, hy1, h1 = tx0 - 0.03, tx1 + 0.03, ty0 - 0.03, ty1 + 0.03, 7.9
    m.box(tx0 - 0.01, tx1 + 0.01, ty0 - 0.01, ty1 + 0.01, top - 0.1, top, trim)
    clad_box(m, hx0, hx1, hy0, hy1, top, h1, clad, rib, gap=0.028)
    houses.flat_top(m, hx0, hx1, hy0, hy1, h1, trim, parapet=0.1)
    for face, at, d, a0, a1 in (("y", hy1, 1, hx0 + 0.02, hx1 - 0.02), ("y", hy0, -1, hx0 + 0.02, hx1 - 0.02), ("x", hx1, 1, hy0 + 0.02, hy1 - 0.02), ("x", hx0, -1, hy0 + 0.02, hy1 - 0.02)):
        houses.on_wall(m, face, at, d, a0, a1, 0, 0.008, top + 0.75, top + 1.05, g)
        houses.on_wall(m, face, at, d, a0, a1, 0, 0.01, top + 0.71, top + 0.75, trim)
    vent = material("vent", "#6a6a66", metal=0.5, rough=0.5)
    for x in (sx - 0.07, sx + 0.07):
        m.box(x - 0.03, x + 0.03, sy - 0.02, sy + 0.06, h1 + 0.06, h1 + 0.3, vent, 0.004)
    m.upright(sx + 0.1, ty1, h1, h1 + 0.6, 0.003, 0.002, trim, segments=6)
    # The collar house round its foot, where the cages land.
    wall = material("collar house cladding", "#a8a49a", metal=0.3, rough=0.6)
    wrib = material("collar house ribs", "#96928a", metal=0.3, rough=0.6)
    cx0, cx1, cy0, cy1 = -0.32, 0.17, -0.42, 0.24
    clad_box(m, cx0, cx1, cy0, ty0, 0, 1.4, wall, wrib)
    houses.flat_top(m, cx0, cx1, cy0, ty0, 1.4, trim, parapet=0.1)
    clad_box(m, cx0, tx0, ty0, cy1, 0, 1.1, wall, wrib)
    houses.flat_top(m, cx0, tx0, ty0, cy1, 1.1, trim, parapet=0.1)
    harbour.rollup_door(m, -0.3, -0.18, cy1, 0, 0.75, face=1)
    harbour.rollup_door(m, -0.06, 0.08, cy0, 0, 0.8, face=-1)
    for y in (-0.36, -0.26, -0.16):
        houses.pane(m, "x", cx0, -1, y, 0.5, 0.95, 0.05, g, trim, trim, sash=False)
    for x in (-0.2, 0.1):
        m.box(x - 0.03, x + 0.03, -0.32, -0.24, 1.46, 1.7, vent, 0.004)
    # The bin house and the enclosed gallery up to the next tile on its bents.
    bx0, bx1, by0, by1 = 0.2, 0.42, -0.3, 0.04
    clad_box(m, bx0, bx1, by0, by1, 0, 1.6, wall, wrib)
    houses.flat_top(m, bx0, bx1, by0, by1, 1.6, trim, parapet=0.1)
    roof = material("gallery roof", "#4e5458", metal=0.3, rough=0.6)
    frame = material("mill steel", "#4a4c4e", metal=0.5, rough=0.5)
    head_conveyor(m, by1 - 0.02, 0.5, frame, belt, wall=wall, roof=roof)
    for y in (0.22, 0.42):
        bent(m, HEAD_X, y, head_z(y) - 0.01, 0.1, frame)
    harbour.drum(m, 0.0, 0.38, 0.02)
    houses.car(m, -0.36, 0.38, colour="#3a4a6a")
    houses.lamp_post(m, -0.42, 0.3)


def tipple(m):
    """A coal tipple: a tall timber frame over the loading track, the screen house on top with chutes down to a hopper
    car, a picking lean-to, and the covered conveyor climbing in from the headframe (-y)."""
    ground(m, "#3e3a36", "mine yard")
    wood = material("tipple timber", "#5a4a3a", rough=0.9)
    board = material("tipple boards", "#4a4038", rough=0.9)
    roof = material("tipple roof", "#3a3a3a", metal=0.3, rough=0.6)
    trim = material("tipple trim", "#6a5a48", rough=0.9)
    coal = material("coal", "#161618", rough=0.95)
    ty = 0.06
    siding(m, -0.5, 0.5, ty)
    m.box(-0.5, 0.5, ty - 0.16, ty + 0.16, 0.0, 0.02, coal)
    # Timber bents straddling the track, each braced across and to the next.
    floor = 2.6
    py = (ty - 0.22, ty + 0.24)
    xs = (-0.33, -0.11, 0.11, 0.31)
    for x in xs:
        for y in py:
            m.box(x - 0.016, x + 0.016, y - 0.016, y + 0.016, 0, floor, wood)
            m.box(x - 0.024, x + 0.024, y - 0.024, y + 0.024, 0, 0.1, material("footing", "#9a968c", rough=1.0))
        m.box(x - 0.016, x + 0.016, py[0], py[1], floor - 0.15, floor, wood)
        for y, d in ((py[0], 1), (py[1], -1)):
            m.rod((x, y, floor - 0.6), (x, y + d * 0.09, floor - 0.13), 0.006, wood, segments=4)
    for y in py:
        for xa, xb in zip(xs, xs[1:]):
            m.box(xa, xb, y - 0.012, y + 0.012, floor - 0.12, floor, wood)
            m.box(xa, xb, y - 0.01, y + 0.01, 1.85, 1.93, wood)
            m.rod((xa, y, 0.1), (xb, y, 1.85), 0.0045, wood, segments=4)
            m.rod((xb, y, 1.93), (xa, y, floor - 0.12), 0.0045, wood, segments=4)
    # The screen house on top, board and batten, windows along it.
    hx0, hx1, hy0, hy1, eave = -0.37, 0.35, -0.28, 0.32, 4.3
    m.box(hx0, hx1, hy0, hy1, floor, floor + 0.08, wood)
    boards(m, hx0, hx1, hy0, hy1, floor + 0.08, eave, board)
    gable(m, hx0, hx1, hy0, hy1, eave, 5.1, roof, board, along_x=True, over=0.025)
    g = glass()
    for x in (-0.25, -0.05, 0.15):
        for y, d in ((hy0, -1), (hy1, 1)):
            houses.pane(m, "y", y, d, x, 3.3, 3.85, 0.07, g, trim, trim, sash=True)
    houses.pane(m, "x", hx1, 1, 0.12, 3.3, 3.85, 0.07, g, trim, trim, sash=True)
    m.box(-0.06, 0.06, -0.04, 0.12, 5.0, 5.25, roof, 0.003)
    # The chutes down from the screens to the car, one for each size of coal, and their hinged aprons.
    chute = material("chute plate", "#3a3634", metal=0.5, rough=0.6)
    for x in (-0.22, 0.0, 0.2):
        m.wedge([(x - 0.035, ty + 0.2, floor), (x + 0.035, ty + 0.2, floor), (x + 0.035, ty + 0.2, floor - 0.1), (x - 0.035, ty + 0.2, floor - 0.1)],
                [(x - 0.03, ty + 0.03, 1.85), (x + 0.03, ty + 0.03, 1.85), (x + 0.03, ty + 0.03, 1.75), (x - 0.03, ty + 0.03, 1.75)], chute)
        for s in (-1, 1):
            m.rod((x + s * 0.035, ty + 0.2, floor - 0.02), (x + s * 0.03, ty + 0.03, 1.87), 0.003, chute, segments=4)
    moved(m, lambda mm: cars.hopper(mm, 9 / 16), -0.02, ty, -math.pi / 2, z=0.12)
    # The picking lean-to off the +y side, sloping down over its posts.
    slab(m, [(hx0 + 0.06, hy1, 3.7), (hx1 - 0.1, hy1, 3.7), (hx1 - 0.1, 0.47, 3.1), (hx0 + 0.06, 0.47, 3.1)], 0.05, roof)
    for x in (hx0 + 0.08, -0.1, hx1 - 0.12):
        m.box(x - 0.014, x + 0.014, 0.44, 0.47, 0, floor, wood)
    # The screen shaking out: the refuse chute out the end to the rock pile.
    m.wedge([(hx0, -0.02, 2.9), (hx0, 0.06, 2.9), (hx0, 0.06, 3.0), (hx0, -0.02, 3.0)],
            [(-0.48, 0.0, 1.3), (-0.48, 0.06, 1.3), (-0.48, 0.06, 1.4), (-0.48, 0.0, 1.4)], chute)
    heap(m, -0.45, 0.4, 0.08, 0.6, "#3e3a36", 54, "mine refuse")
    # The covered conveyor up from the headframe, its gallery on bents.
    frame = wood
    head_conveyor(m, -0.5, hy0, frame, material("conveyor belt", "#2e2a26", rough=0.8), wall=board, roof=roof, beyond=True)
    bent(m, HEAD_X, -0.42, head_z(-0.42, True) - 0.02, 0.1, wood)
    heap(m, 0.43, -0.4, 0.06, 0.35, "#1a1a1c", 51, "coal spill")


def _loader(m, x, y, z, turn=0.0, paint="#d8a428"):
    """A wheeled front end loader at x, y, z steps up, turned by turn radians: four big tires, the engine at the back,
    the cab over the middle and the bucket on its arms out front, down near the ground."""
    def build(mm):
        body = material("loader " + paint, paint, metal=0.3, rough=0.5)
        dark = material("loader dark", "#2e2e30", metal=0.4, rough=0.6)
        tire = material("big tire", "#1a1a1a", rough=0.9)
        r = 0.018
        for yy in (-0.03, 0.03):
            for sx in (-1, 1):
                mm.tube_x(sx * 0.026 - 0.008, sx * 0.026 + 0.008, yy, r / rig.RISE, r, tire, segments=12)
        mm.box(-0.018, 0.018, -0.06, 0.045, 0.14, 0.3, body, 0.003)
        mm.box(-0.016, 0.016, -0.06, -0.02, 0.3, 0.38, body, 0.003)
        mm.box(-0.017, 0.017, -0.012, 0.022, 0.3, 0.52, body, 0.003)
        mm.box(-0.015, 0.015, 0.0, 0.024, 0.36, 0.5, glass())
        for sx in (-1, 1):
            mm.rod((sx * 0.014, 0.03, 0.3), (sx * 0.014, 0.075, 0.12), 0.003, body, segments=4)
        mm.wedge([(-0.032, 0.065, 0.02), (0.032, 0.065, 0.02), (0.032, 0.095, 0.02), (-0.032, 0.095, 0.02)],
                 [(-0.032, 0.065, 0.2), (0.032, 0.065, 0.2), (0.032, 0.072, 0.2), (-0.032, 0.072, 0.2)], dark)
    moved(m, build, x, y, turn, z=z)


def _light_pole(m, x, y, h=2.6):
    """A yard light: a wooden pole with two floodlights on a cross arm."""
    pole = material("light pole", "#5a4a3a", rough=0.9)
    m.upright(x, y, 0, h, 0.006, 0.005, pole, segments=8)
    m.box(x - 0.03, x + 0.03, y - 0.003, y + 0.003, h - 0.12, h - 0.08, pole)
    for s in (-1, 1):
        m.box(x + s * 0.024 - 0.008, x + s * 0.024 + 0.008, y - 0.01, y + 0.006, h - 0.2, h - 0.1, material("floodlight", "#e8e4d4", rough=0.4))


def coal_heap(m):
    """The coal yard: the big pile under a conveyor climbing on timber bents to its head pulley, a slotted concrete
    lowering tower under the head so the coal doesn't break falling, a dozer working the pile, and a loader at the small
    pile."""
    ground(m, "#2e2c2a", "coal yard")
    heap(m, -0.12, -0.08, 0.34, 1.8, "#161618", 52, "coal")
    heap(m, 0.24, 0.26, 0.18, 0.9, "#1c1c1e", 53, "coal")
    wood = material("tipple timber", "#5a4a3a", rough=0.9)
    roof = material("tipple roof", "#3a3a3a", metal=0.3, rough=0.6)
    head = (-0.1, -0.08, 2.6)
    conveyor(m, (0.48, -0.44, 0.35), head, 0.06, wood, material("conveyor belt", "#2e2a26", rough=0.8), legs=0,
             hood=roof, load=material("coal on belt", "#161618", rough=1.0))
    for f in (0.2, 0.42):
        x, y, z = (0.48 + (head[0] - 0.48) * f, -0.44 + (head[1] + 0.44) * f, 0.35 + (head[2] - 0.35) * f)
        bent(m, x, y, z - 0.06, 0.09, wood, along_y=False)
    m.box(head[0] - 0.04, head[0] + 0.04, head[1] - 0.04, head[1] + 0.04, head[2] - 0.05, head[2] + 0.28, material("head house", "#4a4038", rough=0.9))
    gable(m, head[0] - 0.045, head[0] + 0.045, head[1] - 0.045, head[1] + 0.045, head[2] + 0.28, head[2] + 0.36, roof, roof, along_x=True, over=0.006, thick=0.02)
    concrete = material("lowering tower", "#8a867e", rough=0.95)
    m.upright(head[0], head[1], 0, head[2] - 0.05, 0.026, 0.024, concrete, segments=14)
    for z in (0.6, 1.0, 1.4, 1.8, 2.2):
        m.box(head[0] + 0.018, head[0] + 0.03, head[1] - 0.006, head[1] + 0.006, z, z + 0.16, material("coal", "#161618", rough=0.95))
    _dozer(m, -0.3, 0.1, 0.55, turn=2.4)
    _loader(m, 0.06, 0.36, 0.02, turn=-2.2)
    _light_pole(m, 0.44, 0.1)


def _stacker(m, pivot, head, w, steel, belt, load):
    """A radial stacker: a boom conveyor from its pivot at pivot (x, y, z of the bed) up to head, on a wheeled bogie near
    its middle, with the head pulley and its hood at the end."""
    conveyor(m, pivot, head, w, steel, belt, legs=0, load=load)
    m.upright(pivot[0], pivot[1], 0, pivot[2] - 0.04, 0.03, 0.03, steel, segments=12)
    f = 0.55
    bx, by, bz = (pivot[i] + (head[i] - pivot[i]) * f for i in range(3))
    for s in (-1, 1):
        dx, dy = head[1] - pivot[1], -(head[0] - pivot[0])
        n = math.hypot(dx, dy) or 1.0
        ox, oy = dx / n * w * 0.6 * s, dy / n * w * 0.6 * s
        m.rod((bx + ox, by + oy, 0.1), (bx + ox * 0.5, by + oy * 0.5, bz - 0.06), 0.005, steel, segments=6)
        m.upright(bx + ox, by + oy, 0, 0.1, 0.014, 0.014, material("stacker tire", "#1a1a1a", rough=0.9), segments=10)
    m.rod((pivot[0], pivot[1], pivot[2] + 0.5), (head[0], head[1], head[2] + 0.2), 0.0025, steel, segments=4)
    m.rod((pivot[0], pivot[1], pivot[2] - 0.04), (pivot[0], pivot[1], pivot[2] + 0.5), 0.006, steel, segments=6)
    m.tube_x(head[0] - w / 2, head[0] + w / 2, head[1], head[2], 0.012, steel, segments=10)


def mine_shed(m):
    """The lamp and wash house: brick, a louvred monitor along its ridge with round ventilators on the slopes, the lamp
    room wing with its counter window, and the boiler house behind with its stack on a square base."""
    ground(m, "#3e3a36", "mine yard")
    brick = material("wash house brick", "#7e4232", rough=0.9)
    roof = material("wash house roof", "#4a4e52", rough=0.6)
    trim = material("mine trim", "#c8bca8", rough=0.8)
    g = glass()
    x0, x1, y0, y1, eave = -0.34, 0.24, -0.2, 0.2, 2.0
    houses.foundation(m, x0, x1, y0, y1, 0.15, material("foundation", "#8a8478", rough=1.0))
    m.box(x0, x1, y0, y1, 0, eave, brick)
    for x in (x0 + 0.003, x1 - 0.003):
        for y, d in ((y0, -1), (y1, 1)):
            houses.on_wall(m, "y", y, d, x - 0.014, x + 0.014, 0, 0.008, 0, eave, brick)
    houses.on_wall(m, "y", y1, 1, x0, x1, 0, 0.008, eave - 0.12, eave, trim)
    houses.on_wall(m, "y", y0, -1, x0, x1, 0, 0.008, eave - 0.12, eave, trim)
    gable(m, x0, x1, y0, y1, eave, 2.6, roof, brick, along_x=True, trim=trim)
    for x in (-0.25, -0.13, -0.01, 0.11):
        for y, d in ((y1, 1), (y0, -1)):
            houses.arched_pane(m, "y", y, d, x, 0.55, 1.5, 0.06, g, trim, trim, rise=0.08, keystone=False)
    houses.door(m, 0.19, y1, material("door", "#3e4a3a", rough=0.8), trim, w=0.05, h=0.85)
    slab(m, [(0.14, y1, 1.05), (0.24, y1, 1.05), (0.24, y1 + 0.06, 0.95), (0.14, y1 + 0.06, 0.95)], 0.04, roof)
    houses.sign_text(m, "WASH HOUSE", "y", y1, 1, -0.1, 1.68, 0.032, material("sign letters", "#e8e0c8", rough=0.6), o=0.009)
    # The monitor down the ridge, louvred both sides, and the ventilators.
    mx0, mx1 = -0.28, 0.18
    m.box(mx0, mx1, -0.06, 0.06, 2.4, 2.85, brick)
    slat = material("monitor louvres", "#8a8680", metal=0.3, rough=0.6)
    for k in range(5):
        x = mx0 + 0.05 + k * 0.09
        for y, d in ((-0.06, -1), (0.06, 1)):
            houses.louvres(m, "y", y, d, x, 2.5, 2.78, 0.06, trim, slat)
    gable(m, mx0, mx1, -0.06, 0.06, 2.85, 2.98, roof, brick, along_x=True, over=0.015, thick=0.04)
    vent = material("ventilator", "#8a8a86", metal=0.6, rough=0.4)
    for x in (-0.2, 0.1):
        for y in (-0.12, 0.12):
            z = eave + (2.6 - eave) * (1 - abs(y) / 0.2)
            m.upright(x, y, z - 0.05, z + 0.18, 0.012, 0.012, vent, segments=10)
            m.lathe(x, y, z + 0.18, [(0.024, 0), (0.026, 0.08), (0.012, 0.14), (0.0, 0.15)], vent, segments=12)
    # The lamp room wing, its counter window where the lamps are handed out.
    lx0, lx1, ly0, ly1 = x1, 0.44, -0.12, 0.16
    m.box(lx0, lx1, ly0, ly1, 0, 1.35, brick)
    houses.flat_top(m, lx0, lx1, ly0, ly1, 1.35, trim, parapet=0.12)
    houses.pane(m, "y", ly1, 1, 0.34, 0.5, 0.95, 0.12, g, trim, trim, sash=False, bars=3)
    houses.on_wall(m, "y", ly1, 1, 0.27, 0.41, 0, 0.03, 0.44, 0.48, trim)
    houses.pane(m, "x", lx1, 1, 0.02, 0.5, 0.95, 0.06, g, trim, trim)
    # The boiler house behind, with its stack on a square brick base, and the coal for it.
    bx0, bx1, by0, by1 = -0.46, -0.16, -0.46, y0
    m.box(bx0, bx1, by0, by1, 0, 1.2, brick)
    slab(m, [(bx0 - 0.02, by0 - 0.02, 1.2), (bx1 + 0.02, by0 - 0.02, 1.2), (bx1 + 0.02, by1, 1.45), (bx0 - 0.02, by1, 1.45)], 0.05, roof)
    houses.pane(m, "x", bx0, -1, -0.33, 0.4, 0.85, 0.06, g, trim, trim)
    m.box(-0.32, -0.24, by0 - 0.004, by0, 0, 0.8, material("door", "#4a3a2e", rough=0.8))
    base = material("stack base", "#6e3a2c", rough=0.9)
    m.box(-0.08, 0.04, -0.46, -0.34, 0, 0.9, base, 0.004)
    m.box(-0.088, 0.048, -0.468, -0.332, 0.9, 1.0, trim)
    stack(m, -0.02, -0.4, 1.0, 5.0, 0.036, brick, material("stack band", "#3a2a24", rough=0.9))
    m.rod((bx1, -0.4, 0.9), (-0.08, -0.4, 0.7), 0.012, material("breeching", "#4a4644", metal=0.5, rough=0.6))
    heap(m, 0.2, -0.38, 0.1, 0.45, "#1a1a1c", 55, "boiler coal")
    # The path to the door, and a bench.
    m.box(0.15, 0.23, y1, 0.5, 0.0, 0.025, material("cinder path", "#5a5652", rough=1.0))
    bench = material("bench", "#6a5440", rough=0.9)
    m.box(-0.2, -0.04, y1 + 0.03, y1 + 0.06, 0.22, 0.26, bench)
    for x in (-0.19, -0.05):
        m.box(x - 0.004, x + 0.004, y1 + 0.035, y1 + 0.055, 0, 0.22, bench)


def haul_truck(m, paint="#d8a428", load=("ore load", "#7a3a2a")):
    """A rear dump haul truck facing +y, the 1960s to 90s kind: big tires, duals at the back, a dump body sloping up to
    a canopy over the cab, the cab on the left of the deck and the engine and grille out front. load is its load's
    material name and colour."""
    body = material("haul truck " + paint, paint, metal=0.3, rough=0.5)
    dark = material("truck frame", "#2e2e30", metal=0.4, rough=0.6)
    tire = material("big tire", "#1a1a1a", rough=0.9)
    g = glass()
    r = 0.038
    zc = r / rig.RISE
    for y, xs in ((0.085, (0.062,)), (-0.075, (0.05, 0.075))):
        for x in xs:
            for s in (-1, 1):
                m.tube_x(s * x - 0.012, s * x + 0.012, y, zc, r, tire, segments=18)
                m.tube_x(s * x - 0.013, s * x + 0.013, y, zc, r * 0.45, dark, segments=12)
    m.box(-0.035, 0.035, -0.12, 0.14, 0.22, 0.42, dark)
    # The dump body, its side outline run across.
    side = [(-0.13, 0.5), (0.055, 0.6), (0.065, 0.98), (0.14, 1.0), (0.14, 1.06), (0.05, 1.08), (-0.13, 0.88)]
    m.wedge([(-0.078, y, z) for y, z in side], [(0.078, y, z) for y, z in side], body)
    for y in (-0.08, -0.02, 0.03):
        for s in (-1, 1):
            m.box(s * 0.078 - 0.003, s * 0.078 + 0.003, y - 0.005, y + 0.005, 0.55, 0.95, body)
    m.box(-0.07, 0.07, -0.125, 0.05, 0.86, 0.9, material(load[0], load[1], rough=1.0))
    m.box(-0.034, 0.034, 0.06, 0.145, 0.32, 0.66, body, 0.004)
    m.box(-0.026, 0.026, 0.145, 0.15, 0.36, 0.6, dark)
    m.box(-0.075, 0.075, 0.07, 0.145, 0.6, 0.63, dark)
    m.box(-0.072, -0.022, 0.08, 0.128, 0.63, 0.94, body, 0.003)
    m.box(-0.07, -0.024, 0.128, 0.131, 0.72, 0.9, g)
    m.box(-0.0735, -0.0715, 0.085, 0.124, 0.72, 0.9, g)
    m.rod((0.06, 0.145, 0.62), (0.06, 0.155, 0.1), 0.003, dark, segments=4)


def shovel(m, paint="#c8a028"):
    """An electric mining shovel facing +y: crawlers, the revolving house with its cab and A-frame gantry, the boom and
    its ropes, and the dipper on its handle down at the face."""
    body = material("shovel " + paint, paint, metal=0.3, rough=0.55)
    dark = material("shovel crawler", "#2e2e30", metal=0.4, rough=0.6)
    steel = material("shovel steel", "#4a4a48", metal=0.5, rough=0.5)
    rope = material("hoist rope", "#2a2a2a", metal=0.6, rough=0.5)
    g = glass()
    for s in (-1, 1):
        m.box(s * 0.062 - 0.02, s * 0.062 + 0.02, -0.085, 0.085, 0.0, 0.22, dark, 0.012)
        for y in (-0.06, -0.02, 0.02, 0.06):
            m.box(s * 0.083 - 0.002, s * 0.083 + 0.002, y - 0.006, y + 0.006, 0.05, 0.17, steel)
    m.box(-0.045, 0.045, -0.045, 0.045, 0.2, 0.3, steel)
    m.box(-0.07, 0.07, -0.11, 0.055, 0.3, 0.8, body, 0.004)
    gable(m, -0.07, 0.07, -0.11, 0.055, 0.8, 0.88, body, body, along_x=False, over=0.004, thick=0.03)
    m.box(-0.07, 0.07, -0.125, -0.11, 0.3, 0.6, dark)
    for y in (-0.08, -0.04, 0.0):
        houses.louvres(m, "x", 0.07, 1, y, 0.45, 0.7, 0.03, steel, steel)
    m.box(-0.07, -0.03, 0.055, 0.09, 0.5, 0.82, body, 0.003)
    m.box(-0.068, -0.032, 0.09, 0.093, 0.6, 0.78, g)
    # The gantry and the boom with its suspension ropes, the sheave at the point.
    apex = (0.0, -0.035, 1.55)
    for s in (-1, 1):
        m.rod((s * 0.045, -0.09, 0.85), apex, 0.005, body, segments=4)
        m.rod((s * 0.045, 0.02, 0.85), apex, 0.005, body, segments=4)
    foot, point = (0.0, 0.06, 0.55), (0.0, 0.25, 1.9)
    for s in (-1, 1):
        m.rod((s * 0.014, foot[1], foot[2]), (s * 0.008, point[1], point[2]), 0.007, body, segments=4)
    for f in (0.3, 0.55, 0.8):
        p = tuple(foot[i] + (point[i] - foot[i]) * f for i in range(3))
        m.rod((-0.014, p[1], p[2]), (0.014, p[1], p[2]), 0.003, body, segments=4)
    for s in (-1, 1):
        m.rod((s * 0.006, apex[1], apex[2]), (s * 0.008, point[1] - 0.01, point[2] + 0.03), 0.0018, rope, segments=4)
    m.tube_x(-0.012, 0.012, point[1], point[2], 0.018, steel, segments=12)
    # The dipper handle through the shipper shaft, the dipper at the face, its hoist rope from the point.
    mid = (0.0, foot[1] + (point[1] - foot[1]) * 0.45, foot[2] + (point[2] - foot[2]) * 0.45)
    dip = (0.0, 0.25, 0.45)
    m.rod((0.0, mid[1] - 0.03, mid[2] + 0.2), dip, 0.006, steel, segments=6)
    m.tube_x(-0.02, 0.02, mid[1], mid[2], 0.012, steel, segments=10)
    m.wedge([(-0.03, dip[1] - 0.03, 0.2), (0.03, dip[1] - 0.03, 0.2), (0.03, dip[1] + 0.035, 0.28), (-0.03, dip[1] + 0.035, 0.28)],
            [(-0.032, dip[1] - 0.035, 0.68), (0.032, dip[1] - 0.035, 0.68), (0.032, dip[1] + 0.04, 0.68), (-0.032, dip[1] + 0.04, 0.68)], dark)
    for k in range(4):
        x = -0.022 + k * 0.0147
        m.rod((x, dip[1] + 0.035, 0.28), (x, dip[1] + 0.05, 0.2), 0.003, steel, segments=4)
    m.rod((0.0, point[1] + 0.015, point[2] - 0.05), (0.0, dip[1] + 0.005, 0.7), 0.002, rope, segments=4)


def euclid_truck(m, paint="#d89a28"):
    """A Euclid style rear dump of the 1930s to 60s facing +y: a narrow hood and grille out front, the cab behind it,
    two axles with duals at the back and a dump body with a short canopy over the cab's back."""
    body = material("euclid " + paint, paint, metal=0.3, rough=0.5)
    dark = material("truck frame", "#2e2e30", metal=0.4, rough=0.6)
    tire = material("big tire", "#1a1a1a", rough=0.9)
    g = glass()
    r = 0.027
    zc = r / rig.RISE
    for y, xs in ((0.1, (0.05,)), (-0.08, (0.04, 0.06))):
        for x in xs:
            for s in (-1, 1):
                m.tube_x(s * x - 0.009, s * x + 0.009, y, zc, r, tire, segments=16)
                m.tube_x(s * x - 0.01, s * x + 0.01, y, zc, r * 0.45, dark, segments=10)
    m.box(-0.028, 0.028, -0.15, 0.16, 0.16, 0.3, dark)
    for s in (-1, 1):
        m.box(s * 0.05 - 0.016, s * 0.05 + 0.016, 0.06, 0.135, 0.36, 0.4, body, 0.003)
    # The hood narrow out front, a step out to the cab behind it.
    m.shell([(0.02, 0.3, 0.0, "paint", 0.5), (0.16, 0.3, 0.004, "grille", 0.5), (0.162, 0.58, 0.012, "paint", 0.5),
             (0.05, 0.6, 0.0, "paint", 0.5), (0.05, 0.6, 0.0, "glass", 0.9), (0.04, 0.84, 0.012, "paint", 0.9),
             (0.0, 0.85, 0.008, "paint", 0.9), (0.0, 0.3, 0.0, "paint", 0.9), (0.02, 0.3, 0.0, "paint", 0.9)],
            0.05, {"paint": body, "grille": dark, "glass": g}, crown=0.02)
    m.box(-0.04, 0.04, 0.162, 0.17, 0.2, 0.28, dark)
    # The dump body, its side outline run across, ribbed, and its load.
    side = [(-0.165, 0.46), (-0.01, 0.38), (-0.01, 0.82), (0.03, 0.9), (0.03, 0.94), (-0.02, 0.94), (-0.165, 0.76)]
    m.wedge([(-0.064, y, z) for y, z in side], [(0.064, y, z) for y, z in side], body)
    for y in (-0.13, -0.08, -0.04):
        for s in (-1, 1):
            m.box(s * 0.064 - 0.003, s * 0.064 + 0.003, y - 0.004, y + 0.004, 0.44, 0.84, body)
    m.box(-0.058, 0.058, -0.155, -0.02, 0.78, 0.82, material("ore load", "#7a3a2a", rough=1.0))
    m.rod((0.04, 0.0, 0.85), (0.04, 0.0, 1.0), 0.003, dark, segments=6)


def steam_shovel(m, paint="#2e2a28"):
    """A steam shovel of before the 1930s facing +y: crawlers, the board house with its round roof and the boiler's
    stack out of the back of it, the A-frame, a short boom and the dipper on its stick."""
    body = material("steam shovel " + paint, paint, metal=0.2, rough=0.7)
    red = material("steam shovel red", "#7a2a22", metal=0.2, rough=0.7)
    dark = material("shovel crawler", "#2e2e30", metal=0.4, rough=0.6)
    steel = material("shovel steel", "#4a4a48", metal=0.5, rough=0.5)
    rope = material("hoist rope", "#2a2a2a", metal=0.6, rough=0.5)
    for s in (-1, 1):
        m.box(s * 0.05 - 0.016, s * 0.05 + 0.016, -0.07, 0.07, 0.0, 0.18, dark, 0.01)
    m.box(-0.04, 0.04, -0.06, 0.06, 0.18, 0.28, steel)
    # The house, its roof curved across, windows down the sides and the stack through the back.
    x0, x1, y0, y1 = -0.06, 0.06, -0.12, 0.05
    m.box(x0, x1, y0, y1, 0.28, 0.72, red, 0.003)
    arch = [(x0 - 0.008 + (x1 - x0 + 0.016) * k / 8, 0.72 + 0.12 * math.sin(math.pi * k / 8)) for k in range(9)]
    m.loft([(y0 - 0.008, arch + [(x, z - 0.03) for x, z in reversed(arch)]), (y1 + 0.008, arch + [(x, z - 0.03) for x, z in reversed(arch)])],
           {"roof": body})
    for y in (-0.08, -0.03, 0.02):
        for xx, d in ((x0, -1), (x1, 1)):
            houses.on_wall(m, "x", xx, d, y - 0.014, y + 0.014, 0, 0.004, 0.45, 0.62, material("shovel window", "#1e2226", rough=0.3))
    m.upright(0.0, -0.09, 0.8, 1.35, 0.016, 0.016, body, segments=12)
    m.upright(0.0, -0.09, 1.3, 1.36, 0.02, 0.02, body, segments=12)
    # The A-frame and the boom, the dipper stick racked through its middle, the dipper and its rope.
    apex = (0.0, 0.02, 1.25)
    for s in (-1, 1):
        m.rod((s * 0.05, -0.02, 0.75), apex, 0.005, body, segments=4)
        m.rod((s * 0.05, y1, 0.72), apex, 0.005, body, segments=4)
    foot, point = (0.0, y1 + 0.01, 0.4), (0.0, 0.22, 1.5)
    for s in (-1, 1):
        m.rod((s * 0.012, foot[1], foot[2]), (s * 0.007, point[1], point[2]), 0.006, body, segments=4)
    m.rod(apex, (0.0, point[1] - 0.01, point[2] + 0.03), 0.0018, rope, segments=4)
    m.tube_x(-0.01, 0.01, point[1], point[2], 0.015, steel, segments=10)
    mid = (0.0, foot[1] + (point[1] - foot[1]) * 0.45, foot[2] + (point[2] - foot[2]) * 0.45)
    dip = (0.0, 0.21, 0.38)
    m.rod((0.0, mid[1] - 0.025, mid[2] + 0.18), dip, 0.006, steel, segments=6)
    m.wedge([(-0.024, dip[1] - 0.025, 0.16), (0.024, dip[1] - 0.025, 0.16), (0.024, dip[1] + 0.03, 0.22), (-0.024, dip[1] + 0.03, 0.22)],
            [(-0.026, dip[1] - 0.03, 0.56), (0.026, dip[1] - 0.03, 0.56), (0.026, dip[1] + 0.034, 0.56), (-0.026, dip[1] + 0.034, 0.56)], dark)
    m.rod((0.0, point[1] + 0.012, point[2] - 0.04), (0.0, dip[1] + 0.004, 0.58), 0.002, rope, segments=4)


def _pit_loco(m):
    """A narrow gauge saddle tank engine facing +y, its wheels on rails whose tops are 0.06 steps up: frame, boiler with
    the saddle tank over it, stack, dome and the cab at the back."""
    black = material("dinky black", "#24221f", metal=0.3, rough=0.6)
    wheel = material("dinky wheel", "#2a2826", metal=0.5, rough=0.6)
    for y in (-0.02, 0.02):
        m.tube_x(-0.03, 0.03, y, 0.06 + 0.014 / rig.RISE, 0.014, wheel, segments=12)
    m.box(-0.03, 0.03, -0.05, 0.05, 0.12, 0.2, black)
    m.tube(-0.02, 0.05, 0.0, 0.32, 0.024, black, segments=14)
    m.tube(-0.01, 0.035, 0.0, 0.36, 0.03, black, segments=14)
    m.upright(0.0, 0.042, 0.42, 0.6, 0.008, 0.012, black, segments=10)
    m.upright(0.0, 0.005, 0.44, 0.5, 0.01, 0.009, material("dinky brass", "#b8903a", metal=0.7, rough=0.4), segments=10)
    m.box(-0.034, 0.034, -0.05, -0.018, 0.2, 0.62, black, 0.002)
    m.box(-0.038, 0.038, -0.056, -0.012, 0.62, 0.65, black)
    m.box(-0.036, 0.036, -0.045, -0.025, 0.42, 0.56, material("cab opening", "#121212", rough=0.5))


def _dump_car(m, k=0, tip=False):
    """A side dump ore car facing +y on the same rails: a V body on a frame and two axles, tipped to -x if tip."""
    black = material("dinky black", "#24221f", metal=0.3, rough=0.6)
    red = material("dump car red", "#6e2e24", metal=0.2, rough=0.8)
    wheel = material("dinky wheel", "#2a2826", metal=0.5, rough=0.6)
    ore = material("ore load", "#7a3a2a", rough=1.0)
    for y in (-0.022, 0.022):
        m.tube_x(-0.03, 0.03, y, 0.06 + 0.012 / rig.RISE, 0.012, wheel, segments=10)
    m.box(-0.03, 0.03, -0.036, 0.036, 0.12, 0.18, black)
    if tip:
        prof = [(-0.06, 0.3), (-0.012, 0.18), (0.024, 0.44)]
        m.wedge([(x, -0.036, z) for x, z in prof], [(x, 0.036, z) for x, z in prof], red)
        return
    prof = [(-0.04, 0.42), (0.0, 0.2), (0.04, 0.42)]
    m.wedge([(x, -0.036, z) for x, z in prof], [(x, 0.036, z) for x, z in prof], red)
    m.box(-0.036, 0.036, -0.032, 0.032, 0.36, 0.42, ore)
    scenery.blob(m, 0.0, 0.0, 0.4, 0.024, ore, 500 + k, squash=0.6)


def road_at(b, f):
    """Benches b's road middle at f along it, 0 at its start to 1 at its end: x, y, its height, its heading toward the
    end in radians (as moved() turns) and how steeply it climbs that way."""
    a0, turns, e0, e1, h0, h1, _ = b.road

    def at(t):
        a = a0 + turns * math.tau * t
        e = e0 + (e1 - e0) * t
        dx, dy = (e * b.rx * math.cos(a), e * b.ry * math.sin(a))
        # at() divides by the wobble, so scale out by it to land on the road.
        ee, _ = b.at(b.cx + dx, b.cy + dy)
        k = e / ee
        return b.cx + dx * k, b.cy + dy * k, h0 + (h1 - h0) * t
    x, y, h = at(f)
    xn, yn, hn = at(f + 0.01)
    run = math.hypot(xn - x, yn - y)
    return x, y, h, math.atan2(-(xn - x), yn - y), math.atan2((hn - h) * rig.RISE, run)


def track_path(m, pts, oy=0.0):
    """Narrow gauge track on its ballast along pts, a list of (x, y, z in steps) in the first tile's terms, drawing only
    the bits on this tile (y offset by oy): ballast, ties every 0.04 and the two rails, their tops 0.06 over the line."""
    tie = material("tie", "#4e3626", rough=0.9)
    rail = material("rail", "#6a6662", metal=0.9, rough=0.45)
    ballast = material("pit ballast", "#8a7e70", rough=1.0)
    since = 0.0
    for p, q in zip(pts, pts[1:]):
        mx, my = (p[0] + q[0]) / 2, (p[1] + q[1]) / 2 - oy
        seg = math.hypot(q[0] - p[0], q[1] - p[1])
        if abs(mx) > 0.5 or abs(my) > 0.5 or seg < 1e-6:
            since += seg
            continue
        a, b = (p[0], p[1] - oy, p[2]), (q[0], q[1] - oy, q[2])
        beam(m, a, b, 0.075, -0.04, 0.02, ballast)
        for s in (-1, 1):
            beam(m, a, b, 0.006, 0.03, 0.06, rail, o=s * 0.025)
        nx, ny = -(q[1] - p[1]) / seg, (q[0] - p[0]) / seg
        while since < seg:
            f = since / seg
            c = (a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f)
            beam(m, (c[0] - nx * 0.04, c[1] - ny * 0.04, c[2]), (c[0] + nx * 0.04, c[1] + ny * 0.04, c[2]), 0.014, 0.0, 0.03, tie)
            since += 0.04
        since -= seg


def pit_rails():
    """The pit railway's line, (x, y, z) every so often: out along a dump trestle past the rim, then down the haul road
    to its foot on the floor."""
    x0, y0, h0, turn, _ = road_at(PIT, 0.0)
    dx, dy = math.sin(turn), -math.cos(turn)
    pts = [(x0 + dx * d, y0 + dy * d, h0) for d in (0.16, 0.08)]
    n = 160
    for k in range(n + 1):
        x, y, h, _, _ = road_at(PIT, k / n)
        pts.append((x, y, h))
    return pts


def place_on(m, pts, s, build, oy=0.0):
    """Builds something facing +y with build and sets it on the line pts at s tiles from its end, facing the end."""
    run = 0.0
    for p, q in zip(reversed(pts[:-1]), reversed(pts[1:])):
        seg = math.hypot(q[0] - p[0], q[1] - p[1])
        if run + seg >= s:
            f = (s - run) / seg
            x, y, z = (q[i] + (p[i] - q[i]) * f for i in range(3))
            turn = math.atan2(-(q[0] - p[0]), q[1] - p[1])
            pitch = math.atan2((q[2] - p[2]) * rig.RISE, seg)
            moved(m, build, x, y - oy, turn, z=z, pitch=pitch)
            return
        run += seg


def pit_railway(m, far):
    """Before 1935: the pit railway, its track down the haul road, the saddle tank engine and two cars of ore at its foot
    by the steam shovel, and at the top a timber ore bin past the rim with a car tipped into it."""
    oy = 1.0 if far else 0.0
    pts = pit_rails()
    track_path(m, pts, oy)
    if far:
        return
    # The train waits at the foot, the engine nearest the end; each piece on the line where it stands.
    place_on(m, pts, 0.06, _pit_loco)
    for k in range(2):
        place_on(m, pts, 0.155 + k * 0.085, lambda mm, k=k: _dump_car(mm, k))
    # At the top the line runs out past the rim to a timber ore bin, a car tipped into it.
    wood = material("trestle timber", "#5a4a3a", rough=0.9)
    plank = material("bin planks", "#6a5844", rough=0.9)
    x0, y0, h0, turn, _ = road_at(PIT, 0.0)
    ox, oy_ = math.sin(turn), -math.cos(turn)
    lx, ly = -oy_, ox
    cx, cy = x0 + ox * 0.13 + lx * 0.085, y0 + oy_ * 0.13 + ly * 0.085
    m.box(cx - 0.05, cx + 0.05, cy - 0.05, cy + 0.05, h0, h0 + 0.42, plank, 0.003)
    for sx in (-1, 1):
        for sy in (-1, 1):
            m.box(cx + sx * 0.05 - 0.006, cx + sx * 0.05 + 0.006, cy + sy * 0.05 - 0.006, cy + sy * 0.05 + 0.006, h0, h0 + 0.48, wood)
    m.box(cx - 0.044, cx + 0.044, cy - 0.044, cy + 0.044, h0 + 0.42, h0 + 0.43, material("ore load", "#7a3a2a", rough=1.0))
    place_on(m, list(reversed(pts[:4])), 0.03, lambda mm: _dump_car(mm, 9, tip=True))


# The open pit's benches, its middle on the shared edge of its two tiles (y = 0.5 in the first tile's terms).
PIT = Benches(0.0, 0.5, 0.42, 0.9, [(0.32, 0.02), (0.5, 0.36), (0.67, 0.7), (0.84, 1.04), (9.0, 1.38)], seed=2.0,
              road=(-math.pi / 2 - 0.5, 1.3, 1.0, 0.26, 1.38, 0.02, 0.07), rough=0.014, face=0.04, edge=0.04)
PIT_GRID = 200


def pit_edge(x, y):
    """How far x, y is in from the outside of the open pit's two tiles."""
    return min(0.5 - abs(x), y + 0.5, 1.5 - y)


def pit_pick(x, y, slope):
    """The open pit's faces as PIT.pick() has them, but its spoil berm all dirt."""
    if pit_edge(x, y) < 0.08:
        return 0
    e, a = PIT.at(x, y)
    if PIT.on_road(e, a) is not None:
        return 3
    if PIT.on_face(x, y, 0.7 / (PIT_GRID * PIT.rx))[0] or (slope > 5.0 and PIT.near_road(e, a) is not None):
        return 2 if math.sin(2 * a + PIT.seed * 1.3) + 0.4 * math.sin(7 * a) > -0.4 else 1
    return 0


def pit_height(x, y):
    """The open pit's ground, its spoil berm sloping down to the land round the pit's two tiles."""
    d = pit_edge(x, y)
    h = PIT.height(x, y)
    return min(h, 0.1 + 1.28 * max(0.0, d) / 0.07) if d < 0.07 else h


def open_pit(m, far=False, era=2):
    """An open pit iron mine across two tiles, this one and far, its +y neighbour: irregular benches of red ore and grey
    rock down to the floor, the haul road spiralling down them, a shovel at the face and what hauls from it. era 0 is
    before 1935, a steam shovel loading side dump cars behind a little saddle tank engine, 1 to 1965 an electric shovel
    and a Euclid rear dump, 2 after that a bigger haul truck."""
    mats = [material("pit bench", "#94765a", rough=1.0), material("pit rock face", "#7a7068", rough=1.0),
            material("pit ore face", "#b0442a", rough=1.0), material("haul road", "#c4b49c", rough=1.0)]
    terrain(m, pit_height, mats, pit_pick, n=PIT_GRID, oy=1.0 if far else 0.0, wall=0)
    if era == 0:
        # The steam shovel on the floor's far end, clear of the railway coming down the road.
        pit_railway(m, far)
        if far:
            moved(m, steam_shovel, 0.04, -0.36, -math.pi / 2, z=0.02)
    elif not far:
        moved(m, shovel, 0.01, 0.34, math.pi, z=0.02)
    else:
        moved(m, euclid_truck if era == 1 else haul_truck, 0.02, -0.28, 0.5, z=0.02)
        m.box(-0.07, 0.04, -0.2, -0.12, 0.02, 0.035, material("pit water", "#3a4a52", rough=0.15))


def ore_crusher(m):
    """The crusher house stepping down from the dump pocket on its bank, a covered conveyor on trestles up to the ore
    bins over the loading track, and the ramp the trucks climb to dump."""
    ground(m, "#5a4a42", "ore yard")
    earth = material("crusher bank", "#6a5444", rough=1.0)
    wall = material("crusher siding", "#7e7c74", metal=0.4, rough=0.6)
    rib = material("crusher ribs", "#6a6862", metal=0.4, rough=0.6)
    roof = material("crusher roof", "#4a4a48", metal=0.4, rough=0.6)
    trim = material("crusher trim", "#4a4c4e", metal=0.3, rough=0.6)
    concrete = material("pocket concrete", "#9a958c", rough=0.95)
    g = glass()
    bank = 1.4
    prof = [(-0.5, 0.0), (-0.06, 0.0), (-0.3, bank), (-0.5, bank)]
    m.wedge([(-0.5, y, z) for y, z in prof], [(0.08, y, z) for y, z in prof], earth)
    # The ramp up the bank's side.
    m.wedge([(0.46, -0.48, 0.0), (0.46, -0.34, 0.0), (0.08, -0.34, bank), (0.08, -0.48, bank)],
            [(0.46, -0.48, 0.0), (0.46, -0.34, 0.0), (0.08, -0.34, 0.0), (0.08, -0.48, 0.0)], earth)
    for y in (-0.485, -0.335):
        m.rod((0.46, y, 0.05), (0.08, y, bank + 0.05), 0.003, material("guard rail", "#8a8a86", metal=0.4, rough=0.5), segments=4)
    # The dump pocket: a concrete-walled pit with its grizzly bars and a bumper log.
    px0, px1, py0, py1 = -0.12, 0.05, -0.47, -0.33
    m.box(px0, px1, py0, py1, bank, bank + 0.02, material("pocket dark", "#1e1c1a", rough=1.0))
    for x0, x1, y0, y1 in ((px0, px1, py0, py0 + 0.012), (px0, px1, py1 - 0.012, py1), (px0, px0 + 0.012, py0, py1)):
        m.box(x0, x1, y0, y1, bank, bank + 0.12, concrete)
    for k in range(6):
        y = py0 + 0.02 + k * 0.02
        m.box(px0 + 0.012, px1, y - 0.002, y + 0.002, bank + 0.03, bank + 0.05, trim)
    m.tube(py0, py1, px1 + 0.012, bank + 0.06, 0.01, material("bumper log", "#5a4a38", rough=0.9), segments=8)
    # The house in three steps down the slope: primary crusher on the bank, secondary below, screens at the foot.
    hx0, hx1 = -0.46, -0.14
    for y0, y1, base, eave, ridge in ((-0.47, -0.26, bank, 3.9, 4.6), (-0.28, -0.1, 0.0, 3.0, 3.55), (-0.12, 0.06, 0.0, 2.1, 2.55)):
        clad_box(m, hx0, hx1, y0, y1, base, eave, wall, rib, gap=0.028)
        gable(m, hx0, hx1, y0, y1, eave, ridge, roof, wall, along_x=True, over=0.015, thick=0.05)
        for x in (-0.38, -0.22):
            houses.on_wall(m, "y", y1, 1, x - 0.04, x + 0.04, 0, 0.007, eave - 0.6, eave - 0.3, g)
        houses.on_wall(m, "x", hx0, -1, y0 + 0.03, y1 - 0.03, 0, 0.007, eave - 0.55, eave - 0.3, g)
    harbour.rollup_door(m, -0.34, -0.24, 0.06, 0.0, 0.7, face=1)
    # A dust collector by the primary house.
    m.lathe(-0.08, -0.2, 1.0, [(0.0, 0), (0.012, 0.0), (0.04, 0.5), (0.04, 1.1), (0.03, 1.2), (0.0, 1.22)], material("cyclone", "#9a9a94", metal=0.4, rough=0.5), segments=16)
    for x in (-0.1, -0.06):
        m.box(x - 0.003, x + 0.003, -0.203, -0.197, 0, 1.0, trim)
    m.rod((-0.08, -0.2, 2.2), (hx1, -0.2, 2.5), 0.01, material("cyclone", "#9a9a94", metal=0.4, rough=0.5), segments=8)
    # The ore bins over the track, three pockets with hopper bottoms, and a tripper house along their top.
    ty = 0.34
    siding(m, -0.5, 0.5, ty)
    steel = material("bin steel", "#6a5448", metal=0.4, rough=0.6)
    bx0, bx1, by0, by1, b0, b1 = 0.04, 0.46, 0.25, 0.43, 2.0, 2.9
    m.box(bx0, bx1, by0, by1, b0, b1, steel, 0.003)
    for x in (bx0, bx1):
        m.box(x - 0.004, x + 0.004, by0 - 0.004, by1 + 0.004, b0, b1 + 0.04, trim)
    for k in range(3):
        cx = bx0 + (k + 0.5) * (bx1 - bx0) / 3
        hw = (bx1 - bx0) / 6
        m.wedge([(cx - 0.02, ty - 0.03, 1.72), (cx + 0.02, ty - 0.03, 1.72), (cx + 0.02, ty + 0.03, 1.72), (cx - 0.02, ty + 0.03, 1.72)],
                [(cx - hw, by0, b0), (cx + hw, by0, b0), (cx + hw, by1, b0), (cx - hw, by1, b0)], steel)
        m.box(cx - 0.012, cx + 0.012, ty - 0.02, ty + 0.02, 1.6, 1.72, trim)
        for y in (by0 + 0.003, by1 - 0.003):
            for x in (cx - hw + 0.006,):
                m.box(x - 0.006, x + 0.006, y - 0.006, y + 0.006, 0, b0, trim)
        m.rod((cx - hw + 0.006, by0, 0.2), (cx + hw - 0.006, by0, b0 - 0.1), 0.0025, trim, segments=4)
    for y in (by0 + 0.003, by1 - 0.003):
        m.box(bx1 - 0.012, bx1, y - 0.006, y + 0.006, 0, b0, trim)
    m.box(0.1, 0.4, ty - 0.035, ty + 0.035, b1, b1 + 0.3, wall)
    gable(m, 0.1, 0.4, ty - 0.035, ty + 0.035, b1 + 0.3, b1 + 0.4, roof, wall, along_x=True, over=0.01, thick=0.03)
    heap(m, 0.25, ty, 0.07, 0.15, "#6a3426", 63, "ore spill")
    # The covered conveyor from the screens up to the tripper.
    conveyor(m, (hx1 - 0.02, 0.0, 1.3), (0.12, ty, b1 + 0.08), 0.055, trim, material("conveyor belt", "#2e2a26", rough=0.8), legs=0.14,
             hood=roof, load=material("ore on belt", "#6a3426", rough=1.0))


def ore_heap(m):
    """The crushed ore stockpile: a radial stacker swung over the pile from its pivot by the tile's corner, the kidney
    shaped windrow it leaves as it swings, a dozer on top and a loader at the toe."""
    ground(m, "#5a4a42", "ore yard")
    heap(m, -0.04, -0.06, 0.32, 1.7, "#7a3a2a", 61, "iron ore")
    for k, (a, r, h) in enumerate(((-2.0, 0.17, 0.9), (-2.5, 0.15, 0.75), (-2.95, 0.13, 0.6))):
        heap(m, 0.38 + 0.56 * math.cos(a), 0.4 + 0.56 * math.sin(a), r, h, "#6a3426", 64 + k, "iron ore")
    steel = material("stacker steel", "#c8a028", metal=0.3, rough=0.5)
    _stacker(m, (0.38, 0.4, 0.5), (-0.02, -0.04, 2.2), 0.05, steel, material("conveyor belt", "#2e2a26", rough=0.8),
             material("ore on belt", "#6a3426", rough=1.0))
    _dozer(m, -0.16, -0.24, 0.7, turn=0.9)
    _loader(m, 0.3, -0.34, 0.02, turn=0.5)
    _light_pole(m, 0.44, -0.44)


def pipe_ring(m, x, y, z, R, r, mat, n=20):
    """A pipe bent round in a ring R tiles round about x, y at z steps, r tiles thick."""
    pts = [(x + R * math.cos(2 * math.pi * k / n), y + R * math.sin(2 * math.pi * k / n), z) for k in range(n + 1)]
    for a, b in zip(pts, pts[1:]):
        m.rod(a, b, r, mat, segments=8)


def ring_deck(m, x, y, z, r, w, deck, rail, a0=0.0, a1=2 * math.pi, h=0.22):
    """A walkway round a column of radius r from angle a0 to a1: a grating w tiles wide at z steps and a handrail h over it."""
    n = max(3, int((a1 - a0) / (math.pi / 8)))
    for k in range(n):
        p, q = a0 + (a1 - a0) * k / n, a0 + (a1 - a0) * (k + 1) / n
        pts = [(x + rr * math.cos(a), y + rr * math.sin(a), z) for rr, a in ((r, p), (r + w, p), (r + w, q), (r, q))]
        slab(m, pts, 0.035, deck)
        m.rod((x + (r + w) * math.cos(p), y + (r + w) * math.sin(p), z + h), (x + (r + w) * math.cos(q), y + (r + w) * math.sin(q), z + h),
              0.0018, rail, segments=4)
        m.rod((x + (r + w) * math.cos(p), y + (r + w) * math.sin(p), z), (x + (r + w) * math.cos(p), y + (r + w) * math.sin(p), z + h),
              0.0016, rail, segments=4)
    m.rod((x + (r + w) * math.cos(a1), y + (r + w) * math.sin(a1), z), (x + (r + w) * math.cos(a1), y + (r + w) * math.sin(a1), z + h),
          0.0016, rail, segments=4)


def column_ladder(m, x, y, r, a, z0, z1, mat, cage=True):
    """A caged ladder up the side of a round column of radius r at angle a, from z0 to z1 steps."""
    c, s = math.cos(a), math.sin(a)
    px, py = -s, c
    o = r + 0.008
    for side in (-1, 1):
        m.rod((x + c * o + px * side * 0.008, y + s * o + py * side * 0.008, z0),
              (x + c * o + px * side * 0.008, y + s * o + py * side * 0.008, z1), 0.0016, mat, segments=4)
    if cage:
        z = z0 + 0.5
        while z < z1:
            pts = [(x + c * (o + 0.006 + 0.012 * math.sin(t)) + px * 0.014 * math.cos(t), y + s * (o + 0.006 + 0.012 * math.sin(t)) + py * 0.014 * math.cos(t), z)
                   for t in (0, math.pi / 2, math.pi)]
            for p, q in zip(pts, pts[1:]):
                m.rod(p, q, 0.0012, mat, segments=4)
            z += 0.35
        for t in (math.pi / 2,):
            m.rod((x + c * (o + 0.018), y + s * (o + 0.018), z0 + 0.5), (x + c * (o + 0.018), y + s * (o + 0.018), z1), 0.0012, mat, segments=4)


def spiral_stair(m, x, y, r, h, a0, turn, tread, rail, n=None):
    """A stair winding up the outside of a round tank of radius r from the ground to h steps, starting at angle a0 and
    going turn radians round."""
    n = n or max(8, int(abs(turn) * r / 0.018))
    w = 0.022
    pts = []
    for k in range(n):
        a, b = a0 + turn * k / n, a0 + turn * (k + 1) / n
        z = h * (k + 1) / n
        q = [(x + rr * math.cos(t), y + rr * math.sin(t), z - 0.04) for rr, t in ((r, a), (r + w, a), (r + w, b), (r, b))]
        slab(m, q, 0.035, tread)
        pts.append((x + (r + w) * math.cos(b), y + (r + w) * math.sin(b), z + 0.2))
    for p, q in zip(pts, pts[1:]):
        m.rod(p, q, 0.0016, rail, segments=4)
    for p in pts[::3]:
        m.rod(p, (p[0], p[1], p[2] - 0.2), 0.0014, rail, segments=4)


def roof_vent(m, x, y, z, mat, r=0.022):
    """A round roof ventilator: a short neck and a flared cap with a gap under it."""
    m.upright(x, y, z - 0.08, z + 0.12, r * 0.8, r * 0.8, mat, segments=12)
    m.lathe(x, y, z + 0.14, [(r * 1.4, 0), (r * 1.3, 0.06), (r * 0.6, 0.12), (0.0, 0.14)], mat, segments=12)
    m.upright(x, y, z + 0.1, z + 0.14, r * 0.5, r * 0.5, material("vent gap", "#1e1e20", rough=0.95), segments=12)


STEEL_CLAD = ("steel works cladding", "#6a6460")
STEEL_RIB = ("steel works ribs", "#57524e")


def blast_furnace(m):
    """A blast furnace as at Hamilton or the Soo: the furnace rising out of its cast house, the bustle pipe ring round it
    with the tuyere stocks, three hot blast stoves with domed tops along the back, the skip hoist incline up from the
    stockhouse to the top, and the downcomer from the top down to the dust catcher."""
    ground(m, "#4a4644", "mill yard")
    shell = material("furnace shell", "#5a4a42", metal=0.4, rough=0.6)
    stove = material("hot stove", "#8a8682", metal=0.4, rough=0.55)
    pipe = material("blast pipe", "#6a5e56", metal=0.4, rough=0.6)
    steel = material("furnace steelwork", "#3a3634", metal=0.5, rough=0.5)
    rail = material("furnace handrail", "#c8a030", metal=0.3, rough=0.5)
    deck = material("furnace grating", "#4a4644", metal=0.4, rough=0.6)
    wall = material(*STEEL_CLAD, metal=0.4, rough=0.6)
    rib = material(*STEEL_RIB, metal=0.4, rough=0.6)
    roof = material("cast house roof", "#3e3c3a", metal=0.4, rough=0.6)
    fx, fy = 0.06, -0.04
    # The furnace: hearth, bosh, the stack tapering up to the throat, and the top cone.
    m.lathe(fx, fy, 0, [(0.15, 0), (0.15, 1.2), (0.185, 2.0), (0.175, 2.9), (0.13, 5.6), (0.1, 6.2), (0.1, 6.4)], shell, segments=28)
    for z in (3.4, 4.4, 5.3):
        r = 0.175 - (0.175 - 0.13) * (z - 2.9) / 2.7
        m.upright(fx, fy, z, z + 0.08, r + 0.008, r + 0.008, steel, segments=28)
    ring_deck(m, fx, fy, 4.0, 0.16, 0.035, deck, rail, a0=math.pi * 0.2, a1=math.pi * 1.6)
    # The charging platform and top cone, the uptakes and bleeders.
    m.upright(fx, fy, 6.4, 6.48, 0.17, 0.17, deck, segments=24)
    pipe_ring(m, fx, fy, 6.7, 0.17, 0.0018, rail, n=20)
    m.lathe(fx, fy, 6.48, [(0.11, 0), (0.11, 0.3), (0.07, 0.7), (0.05, 1.1)], shell, segments=20)
    for k in range(4):
        a = math.pi / 4 + k * math.pi / 2
        c, s = math.cos(a), math.sin(a)
        m.rod((fx + 0.08 * c, fy + 0.08 * s, 6.5), (fx + 0.07 * c, fy + 0.07 * s, 7.8), 0.02, pipe, segments=10)
        m.rod((fx + 0.07 * c, fy + 0.07 * s, 7.8), (fx + 0.02 * c, fy + 0.02 * s, 8.15), 0.02, pipe, segments=10)
        m.upright(fx + 0.07 * c, fy + 0.07 * s, 7.8, 8.45, 0.009, 0.009, pipe, segments=8)
        m.upright(fx + 0.07 * c, fy + 0.07 * s, 8.45, 8.55, 0.016, 0.012, steel, segments=8)
    m.lathe(fx, fy, 8.0, [(0.035, 0), (0.035, 0.25), (0.0, 0.3)], pipe, segments=12)
    # The dust catcher on its legs, the downcomer to it from the top.
    dx, dy = 0.34, 0.16
    for k in range(4):
        a = math.pi / 4 + k * math.pi / 2
        m.box(dx + 0.06 * math.cos(a) - 0.006, dx + 0.06 * math.cos(a) + 0.006, dy + 0.06 * math.sin(a) - 0.006,
              dy + 0.06 * math.sin(a) + 0.006, 0, 2.0, steel)
    m.lathe(dx, dy, 1.4, [(0.02, 0), (0.08, 0.8), (0.085, 0.85), (0.085, 3.1), (0.05, 3.5), (0.03, 3.7)], pipe, segments=20)
    m.upright(dx, dy, 1.0, 1.4, 0.02, 0.02, pipe, segments=10)
    m.rod((fx, fy, 8.2), (dx, dy, 5.1), 0.024, pipe, segments=12)
    # Three stoves along the back with domed tops, a stack and the hot blast main to the bustle pipe.
    sy = -0.36
    for x in (-0.3, -0.07, 0.16):
        m.upright(x, sy, 0, 5.0, 0.085, 0.085, stove, segments=24)
        m.lathe(x, sy, 5.0, [(0.085 * math.cos(t), 0.42 * math.sin(t)) for t in (0, 0.4, 0.8, 1.15, 1.45, math.pi / 2)], stove, segments=24)
        for z in (1.0, 2.5, 4.0):
            m.upright(x, sy, z, z + 0.05, 0.09, 0.09, steel, segments=24)
        m.rod((x, sy + 0.08, 1.4), (x, -0.27, 1.4), 0.016, pipe)
        m.upright(x, sy + 0.08, 1.25, 1.55, 0.022, 0.022, steel, segments=10)
        ring_deck(m, x, sy, 5.0, 0.085, 0.025, deck, rail, a0=0.3, a1=math.pi - 0.3)
    m.rod((-0.36, -0.27, 1.4), (0.2, -0.27, 1.4), 0.022, pipe, segments=12)
    m.rod((fx, -0.27, 1.4), (fx, -0.27, 2.3), 0.022, pipe, segments=12)
    stack(m, 0.36, -0.42, 0, 6.0, 0.035, stove, steel)
    # The bustle pipe round the furnace above the cast house, the tuyere stocks dropping in to it.
    pipe_ring(m, fx, fy, 2.3, 0.26, 0.02, pipe, n=24)
    for k in range(10):
        a = 2 * math.pi * k / 10
        c, s = math.cos(a), math.sin(a)
        m.rod((fx + 0.26 * c, fy + 0.26 * s, 2.25), (fx + 0.23 * c, fy + 0.23 * s, 1.75), 0.006, pipe, segments=6)
        m.rod((fx + 0.23 * c, fy + 0.23 * s, 1.75), (fx + 0.15 * c, fy + 0.15 * s, 1.6), 0.006, pipe, segments=6)
    # The cast house round the front of the hearth: clad, with a monitor, the tap hole glowing through its open end.
    x0, x1, y0, y1, eave, ridge = -0.46, -0.06, -0.22, 0.3, 1.25, 1.8
    clad_box(m, x0, x1, y0, y1, 0, eave, wall, rib)
    gable(m, x0, x1, y0, y1, eave, ridge, roof, wall, along_x=True, over=0.02, thick=0.05)
    m.box(-0.42, -0.1, 0.04 - 0.04, 0.04 + 0.04, ridge - 0.12, ridge + 0.12, wall)
    gable(m, -0.42, -0.1, 0.0, 0.08, ridge + 0.12, ridge + 0.2, roof, wall, along_x=True, over=0.01, thick=0.03)
    hot = lit("cast house glow", "#ff7a2a", 4.0)
    houses.on_wall(m, "x", x0, -1, -0.06, 0.16, 0, 0.004, 0, 0.95, hot)
    houses.on_wall(m, "x", x0, -1, -0.075, 0.175, 0, 0.012, 0.95, 1.03, roof)
    houses.on_wall(m, "y", y1, 1, -0.4, -0.2, 0, 0.004, 0, 0.8, material("cast house door", "#2e2a28", metal=0.3, rough=0.7))
    # The stockhouse and ore bins along the +y edge, and the skip incline from its pit to the top.
    bx0, bx1, by0, by1 = -0.04, 0.46, 0.36, 0.49
    clad_box(m, bx0, bx1, by0, by1, 0, 0.9, wall, rib)
    for k in range(4):
        x = bx0 + 0.07 + k * 0.12
        m.wedge([(x - 0.05, by0 + 0.01, 0.9), (x + 0.05, by0 + 0.01, 0.9), (x + 0.05, by1 - 0.01, 0.9), (x - 0.05, by1 - 0.01, 0.9)],
                [(x - 0.055, by0, 1.5), (x + 0.055, by0, 1.5), (x + 0.055, by1, 1.5), (x - 0.055, by1, 1.5)], rib)
    m.box(bx0, bx1, by0 - 0.005, by1 + 0.005, 1.5, 1.56, steel)
    harbour.lattice(m, (fx + 0.04, 0.42, 0.6), (fx + 0.01, fy + 0.11, 6.75), 0.07, 0.06, steel, n=12, r=0.004)
    for f in (0.35, 0.7):
        z = 0.6 + (6.75 - 0.6) * f
        y = 0.42 + (fy + 0.11 - 0.42) * f
        for s in (-1, 1):
            m.box(fx + 0.04 + s * 0.04 - 0.005, fx + 0.04 + s * 0.04 + 0.005, y - 0.005, y + 0.005, 0, z - 0.1, steel)
        m.rod((fx + 0.0, y, 0.2), (fx + 0.08, y, z - 0.2), 0.002, steel, segments=4)
    skip = material("skip car", "#8a4a2a", metal=0.3, rough=0.6)
    for f in (0.25, 0.8):
        z = 0.6 + (6.75 - 0.6) * f
        y = 0.42 + (fy + 0.11 - 0.42) * f
        m.box(fx + 0.02, fx + 0.06, y - 0.025, y + 0.025, z + 0.1, z + 0.35, skip, 0.003)
    m.box(fx - 0.03, fx + 0.1, fy + 0.06, fy + 0.16, 6.5, 7.0, wall)
    # The hoist house at the foot of the incline.
    m.box(-0.2, -0.05, 0.34, 0.48, 0, 0.75, material("hoist house brick", "#7a4434", rough=0.9), 0.003)
    gable(m, -0.2, -0.05, 0.34, 0.48, 0.75, 1.0, roof, material("hoist house brick", "#7a4434", rough=0.9), along_x=False, over=0.01, thick=0.04)


def shop_walls(m, x0, x1, y0, y1, eave, wall, rib, glazing, band=(0.66, 0.82), bay=0.11, trim=None):
    """A tall clad mill building's walls: ribbed cladding, columns standing out every bay, and a band of glazing high up."""
    clad_box(m, x0, x1, y0, y1, 0, eave, wall, rib)
    trim = trim or rib
    z0, z1 = eave * band[0], eave * band[1]
    for face, at, d, a0, a1 in (("y", y0, -1, x0, x1), ("y", y1, 1, x0, x1), ("x", x0, -1, y0, y1), ("x", x1, 1, y0, y1)):
        houses.on_wall(m, face, at, d, a0 + 0.01, a1 - 0.01, 0, 0.007, z0, z1, glazing)
        n = max(1, round((a1 - a0) / bay))
        for k in range(n + 1):
            a = a0 + (a1 - a0) * k / n
            houses.on_wall(m, face, at, d, a - 0.008, a + 0.008, 0, 0.012, 0, eave, trim)


def open_hearth(m):
    """The open hearth shop, to about 1970: a tall clad shop with a monitor along its ridge, the charging side lean-to
    along -x and a row of brick stacks rising out of it, one to each furnace, the ladle doors glowing at the +y end."""
    ground(m, "#4a4644", "mill yard")
    wall = material("open hearth cladding", "#5e5650", metal=0.4, rough=0.65)
    rib = material("open hearth ribs", "#4a4440", metal=0.4, rough=0.65)
    roof = material("open hearth roof", "#3a3836", metal=0.4, rough=0.6)
    brick = material("stack brick", "#7a4434", rough=0.9)
    band = material("stack band", "#2e2624", rough=0.9)
    g = glass()
    x0, x1, y0, y1, eave, ridge = -0.26, 0.44, -0.5, 0.48, 4.0, 4.7
    shop_walls(m, x0, x1, y0, y1, eave, wall, rib, g)
    gable(m, x0, x1, y0, y1, eave, ridge, roof, wall, along_x=False, over=0.02, thick=0.06)
    xc = (x0 + x1) / 2
    m.box(xc - 0.08, xc + 0.08, y0 + 0.04, y1 - 0.04, ridge - 0.25, ridge + 0.25, wall)
    slat = material("monitor louvres", "#7a7672", metal=0.3, rough=0.6)
    for k in range(8):
        c = y0 + 0.1 + k * 0.115
        for x, d in ((xc - 0.08, -1), (xc + 0.08, 1)):
            houses.louvres(m, "x", x, d, c, ridge - 0.18, ridge + 0.18, 0.08, rib, slat)
    gable(m, xc - 0.08, xc + 0.08, y0 + 0.04, y1 - 0.04, ridge + 0.25, ridge + 0.38, roof, wall, along_x=False, over=0.015, thick=0.04)
    # The charging side lean-to along -x and the stacks up out of its roof.
    lx0, lz0, lz1 = -0.46, 2.4, 3.1
    clad_box(m, lx0, x0, y0, y1, 0, lz0, wall, rib)
    slab(m, [(lx0 - 0.02, y0, lz0), (x0, y0, lz1), (x0, y1, lz1), (lx0 - 0.02, y1, lz0)], 0.06, roof)
    for k in range(6):
        c = y0 + 0.08 + k * 0.165
        houses.on_wall(m, "x", lx0, -1, c - 0.04, c + 0.04, 0, 0.004, 0, 1.1, material("charging door", "#2e2a28", metal=0.3, rough=0.7))
    for k, h in enumerate((9.2, 10.0, 9.4, 9.8, 9.0)):
        y = y0 + 0.14 + k * 0.18
        x = (lx0 + x0) / 2
        stack(m, x, y, 0, h, 0.042, brick, band)
        m.upright(x, y, h - 1.6, h - 1.5, 0.05, 0.05, band, segments=16)
        m.upright(x, y, h - 0.05, h + 0.02, 0.05, 0.048, band, segments=16)
    # The ladle doors at the +y end, glowing.
    hot = lit("open hearth glow", "#ff7a2a", 4.5)
    for c in (-0.06, 0.24):
        houses.on_wall(m, "y", y1, 1, c - 0.08, c + 0.08, 0, 0.004, 0, 1.9, hot)
        houses.on_wall(m, "y", y1, 1, c - 0.095, c + 0.095, 0, 0.014, 1.9, 2.0, rib)
        houses.on_wall(m, "y", y1, 1, c - 0.08, c + 0.08, 0.004, 0.008, 1.3, 1.9, material("door leaf", "#3a3634", metal=0.4, rough=0.6))


def bof_shop(m):
    """The basic oxygen shop, from about 1970: a long clad shop with the tall converter aisle rising out of its middle,
    two steel off-gas stacks up from its roof with the hood ducts to them, a lower charging aisle and, beyond its end, a
    tall liquid oxygen tank and an oxygen sphere piped into the shop."""
    ground(m, "#4a4644", "mill yard")
    wall = material("BOF cladding", "#6a7480", metal=0.4, rough=0.55)
    rib = material("BOF ribs", "#56606a", metal=0.4, rough=0.55)
    roof = material("BOF roof", "#3e4246", metal=0.4, rough=0.6)
    steel = material("BOF stack", "#8a8884", metal=0.5, rough=0.5)
    dark = material("BOF steelwork", "#3a3634", metal=0.5, rough=0.5)
    rail = material("stack handrail", "#c8a030", metal=0.3, rough=0.5)
    g = glass()
    # The main shop and the lower charging aisle along -x.
    x0, x1, y0, y1, eave = -0.3, 0.44, -0.5, 0.48, 4.0
    shop_walls(m, x0, x1, y0, y1, eave, wall, rib, g)
    gable(m, x0, x1, y0, y1, eave, eave + 0.35, roof, wall, along_x=False, over=0.02, thick=0.06)
    lx0, ly1, lz = -0.46, -0.2, 2.8
    shop_walls(m, lx0, x0, y0, ly1, lz, wall, rib, g, band=(0.6, 0.82))
    houses.flat_top(m, lx0, x0, y0, ly1, lz, roof, parapet=0.1)
    for c in (-0.16, 0.2):
        harbour.rollup_door(m, c - 0.08, c + 0.08, y1, 0, 1.6, face=1, colour="#4a5058")
    # The converter aisle rising out of the middle.
    tx0, tx1, ty0, ty1, top = -0.12, 0.3, -0.3, 0.22, 7.0
    shop_walls(m, tx0, tx1, ty0, ty1, top, wall, rib, g, band=(0.84, 0.92), bay=0.105)
    houses.flat_top(m, tx0, tx1, ty0, ty1, top, roof, parapet=0.1)
    # The hoods' off-gas ducts out of the aisle's -x face and up to the stacks on its roof.
    for k, (y, h) in enumerate(((-0.17, 10.0), (0.08, 9.6))):
        sx = 0.14
        m.upright(sx, y, top, h, 0.05, 0.045, steel, segments=20)
        m.upright(sx, y, top, top + 0.15, 0.065, 0.065, dark, segments=20)
        m.upright(sx, y, h - 0.4, h - 0.32, 0.055, 0.055, dark, segments=20)
        ring_deck(m, sx, y, h - 1.4, 0.05, 0.02, dark, rail, a0=math.pi * 0.5, a1=math.pi * 1.5)
        column_ladder(m, sx, y, 0.048, math.pi, top, h - 1.4, rail, cage=False)
        m.rod((tx0, y, 5.6), (tx0 - 0.06, y, 5.9), 0.035, steel, segments=12)
        m.rod((tx0 - 0.06, y, 5.9), (tx0 - 0.06, y, 7.6), 0.035, steel, segments=12)
        m.rod((tx0 - 0.06, y, 7.6), (sx - 0.05, y, 8.1), 0.035, steel, segments=12)
        m.box(tx0 - 0.07, tx0, y - 0.04, y + 0.04, 5.3, 6.0, dark)
    # The oxygen plant past the charging aisle's end: a tall insulated liquid oxygen tank and a sphere of the gas on legs,
    # on a pad, both piped into the shop's side.
    white = material("oxygen tank", "#d8d4cc", metal=0.3, rough=0.5)
    pipe = material("oxygen line", "#3a6a8a", metal=0.5, rough=0.45)
    m.box(-0.5, x0, ly1 + 0.01, 0.5, 0, 0.06, material("tank pad", "#8a867e", rough=0.9))
    # The tank stands where the converter aisle can't hide it, tall enough to show over the shop from the far side.
    ox, oy, orr, oh = -0.4, -0.1, 0.075, 5.6
    m.upright(ox, oy, 0.06, 0.3, orr + 0.006, orr + 0.006, dark, segments=24)
    m.upright(ox, oy, 0.3, oh, orr, orr, white, segments=24)
    m.lathe(ox, oy, oh, [(orr, 0), (orr * 0.8, 0.12), (orr * 0.4, 0.2), (0.0, 0.22)], white, segments=24)
    for z in (1.5, 3.0, 4.5):
        pipe_ring(m, ox, oy, z, orr + 0.003, 0.003, rib, n=24)
    column_ladder(m, ox, oy, orr, math.pi, 0.06, oh + 0.1, rail)
    pipe_ring(m, ox, oy, oh + 0.3, orr * 0.6, 0.0015, rail, n=12)
    sx, sy, sr, sz = -0.4, 0.37, 0.095, 0.9
    rise = sr / rig.RISE
    m.lathe(sx, sy, sz, [(sr * math.sin(math.pi * k / 16), rise * (1 - math.cos(math.pi * k / 16))) for k in range(17)], white, segments=28)
    for k in range(4):
        a = math.pi / 4 + k * math.pi / 2
        px, py = sx + sr * 0.95 * math.cos(a), sy + sr * 0.95 * math.sin(a)
        m.upright(px, py, 0.06, sz + rise, 0.006, 0.006, dark, segments=8)
    for a, b in (((sx, sy, sz + 0.1), (sx, sy, 0.2)), ((sx, sy, 0.2), (ox, oy + orr, 0.2)),
                 ((ox + orr, oy, 1.1), (x0, oy, 1.1)), ((sx + sr * 0.6, sy, sz + rise), (x0, sy, sz + rise))):
        m.rod(a, b, 0.008, pipe, segments=8)


def steel_shed(m):
    """The rolling mill: a long clad shed with louvred monitor and rows of roof ventilators, a big door in its +y end and
    the crane runway running out of it on columns, an overhead crane over the slabs on it."""
    ground(m, "#4a4644", "mill yard")
    wall = material(*STEEL_CLAD, metal=0.4, rough=0.6)
    rib = material(*STEEL_RIB, metal=0.4, rough=0.6)
    roof = material("shed roof", "#4a4846", metal=0.4, rough=0.6)
    steel = material("runway steel", "#3a3634", metal=0.5, rough=0.5)
    crane = material("overhead crane", "#c8a030", metal=0.4, rough=0.5)
    g = glass()
    x0, x1, y0, y1, eave, ridge = -0.42, 0.42, -0.5, 0.14, 3.4, 4.1
    shop_walls(m, x0, x1, y0, y1, eave, wall, rib, g, band=(0.62, 0.8))
    gable(m, x0, x1, y0, y1, eave, ridge, roof, wall, along_x=False, over=0.02, thick=0.06)
    # The monitor along the ridge, louvred to let the heat out.
    m.box(-0.07, 0.07, y0 + 0.03, y1 - 0.03, ridge - 0.2, ridge + 0.32, wall)
    slat = material("monitor louvres", "#7a7672", metal=0.3, rough=0.6)
    for k in range(5):
        c = y0 + 0.09 + k * 0.115
        for x, d in ((-0.07, -1), (0.07, 1)):
            houses.louvres(m, "x", x, d, c, ridge - 0.12, ridge + 0.24, 0.085, rib, slat)
    gable(m, -0.07, 0.07, y0 + 0.03, y1 - 0.03, ridge + 0.32, ridge + 0.42, roof, wall, along_x=False, over=0.015, thick=0.04)
    # Two rows of ventilators down each slope.
    vent = material("roof ventilators", "#6e6a66", metal=0.4, rough=0.5)
    for x in (-0.27, -0.16, 0.16, 0.27):
        z = eave + (ridge - eave) * (1 - abs(x) / 0.42)
        for k in range(5):
            roof_vent(m, x, y0 + 0.07 + k * 0.125, z + 0.03, vent)
    # The big door in the +y end, part open with a glow inside, and the end's ribs over it.
    dx0, dx1, dz = -0.2, 0.2, 2.6
    houses.on_wall(m, "y", y1, 1, dx0, dx1, 0, 0.005, 0, dz, material("mill dark", "#1a1816", rough=1.0))
    houses.on_wall(m, "y", y1, 1, -0.12, 0.12, 0.005, 0.007, 0, 0.6, lit("mill glow", "#ff8a3a", 2.5))
    houses.on_wall(m, "y", y1, 1, 0.05, 0.31, 0.012, 0.02, 0, dz, material("door leaf", "#4a4440", metal=0.4, rough=0.6))
    houses.on_wall(m, "y", y1, 1, dx0 - 0.02, dx1 + 0.13, 0.012, 0.03, dz, dz + 0.1, steel)
    # The crane runway out of the end on its columns, and the crane on it.
    rx = 0.36
    for x in (-rx, rx):
        for y in (0.3, 0.47):
            m.box(x - 0.012, x + 0.012, y - 0.012, y + 0.012, 0, 2.75, steel)
            m.rod((x, y, 2.2), (x, y - 0.06, 2.72), 0.003, steel, segments=4)
        m.box(x - 0.015, x + 0.015, y1, 0.5, 2.75, 2.95, steel)
        m.box(x - 0.006, x + 0.006, y1, 0.5, 2.95, 2.99, material("crane rail", "#6a6662", metal=0.9, rough=0.4))
    m.rod((-rx, 0.3, 0.3), (-rx, 0.47, 2.5), 0.003, steel, segments=4)
    m.rod((rx, 0.3, 0.3), (rx, 0.47, 2.5), 0.003, steel, segments=4)
    cy = 0.36
    for s in (-1, 1):
        m.box(-rx - 0.02, rx + 0.02, cy + s * 0.03 - 0.008, cy + s * 0.03 + 0.008, 3.0, 3.35, crane)
    for x in (-rx, rx):
        m.box(x - 0.022, x + 0.022, cy - 0.06, cy + 0.06, 2.95, 3.2, crane, 0.003)
    m.box(-0.08, 0.04, cy - 0.045, cy + 0.045, 3.35, 3.6, crane, 0.003)
    m.box(-0.07, -0.02, cy + 0.045, cy + 0.07, 3.0, 3.3, material("crane cab", "#3a3a3c", metal=0.3, rough=0.5))
    for s in (-1, 1):
        m.rod((-0.02 + s * 0.012, cy, 3.35), (-0.02 + s * 0.012, cy, 1.7), 0.0015, steel, segments=4)
    m.box(-0.04, 0.0, cy - 0.015, cy + 0.015, 1.55, 1.72, steel)
    slab_steel = material("steel slabs", "#5a5652", metal=0.6, rough=0.5)
    for k in range(3):
        m.box(-0.28, 0.12, 0.2 + k * 0.0, 0.3, k * 0.12, k * 0.12 + 0.1, slab_steel)
    for x in (0.2, 0.28):
        m.tube_x(x - 0.035, x + 0.035, 0.42, 0.28, 0.055, material("rolled steel", "#6a6c70", metal=0.8, rough=0.35), segments=20, ring_inner=0.02)


def slag_pot(m, x, y, z, tip=0.0, glow=None):
    """A slag pot car on the track along x at x, y, z steps up: its frame and wheels and the cast pot in its ring,
    tipped toward +y by tip radians, the molten slag in it glowing."""
    iron = material("pot car iron", "#2e2c2a", metal=0.5, rough=0.6)
    m.box(x - 0.07, x + 0.07, y - 0.05, y + 0.05, z + 0.12, z + 0.22, iron, 0.003)
    for xx in (x - 0.045, x + 0.045):
        for s in (-1, 1):
            m.tube_x(xx - 0.016, xx + 0.016, y + s * 0.095, z + 0.08, 0.014, iron, segments=10)
        m.tube(y - 0.1, y + 0.1, xx, z + 0.08, 0.004, iron, segments=6)
    for s in (-1, 1):
        m.box(x + s * 0.062 - 0.008, x + s * 0.062 + 0.008, y - 0.03, y + 0.03, z + 0.22, z + 0.5, iron)
    n0 = len(m.objects)
    m.lathe(0.0, 0.0, 0.0, [(0.025, 0.0), (0.05, 0.1), (0.065, 0.35), (0.072, 0.55), (0.078, 0.58)], iron, segments=24)
    m.upright(0.0, 0.0, 0.5, 0.54, 0.068, 0.068, glow, segments=24)
    mat = Matrix.Translation((x, y, (z + 0.22) * rig.RISE)) @ Matrix.Rotation(-tip, 4, "X")
    for o in m.objects[n0:]:
        o.matrix_basis = mat @ o.matrix_basis


def slag_heap(m):
    """A slag dump: benched black slag with a dump road, the slag train's track along its top and a pot tipped over the
    edge, the pour glowing down the side to a pool at its foot."""
    ground(m, "#3e3c3a", "slag yard")
    dump = Benches(-0.04, -0.06, 0.46, 0.44, [(0.62, 2.0), (0.78, 1.4), (0.9, 0.76), (9.0, 0.0)], seed=8.0,
                   road=(math.pi * 1.1, -0.65, 0.97, 0.55, 0.04, 2.0, 0.07), rough=0.025, face=0.035)
    mats = [material("slag", "#36322f", rough=0.9), material("slag face", "#4a4440", rough=0.9),
            material("slag rust", "#5a4234", rough=0.9), material("slag road", "#5e5a56", rough=1.0)]
    terrain(m, dump.height, mats, dump.pick(9.0))
    glow = lit("slag glow", "#ff6a1a", 9.0)
    crust = lit("slag crust", "#8a2814", 1.2)
    # The track along the top and a pot tipped out over the +y edge.
    ty = 0.02
    siding(m, -0.3, 0.2, ty, z=1.98)
    m.box(-0.33, -0.3, ty - 0.12, ty + 0.12, 1.98, 2.2, material("bumper", "#5a4a3a", rough=0.9))
    slag_pot(m, -0.16, ty, 2.1, glow=glow)
    slag_pot(m, 0.06, ty, 2.1, tip=1.25, glow=glow)
    # The pour down the face, spreading as it goes, and the pool and crust at its foot.
    pts = []
    y = 0.16
    while y < 0.5:
        x = 0.06 + 0.012 * math.sin(y * 30)
        pts.append((x, y, dump.height(x, y) + 0.03))
        y += 0.01
    for (xa, ya, za), (xb, yb, zb) in zip(pts, pts[1:]):
        w = 0.014 + (ya - 0.16) * 0.1
        m.wedge([(xa - w, ya, za), (xb - w, yb, zb), (xb + w, yb, zb), (xa + w, ya, za)],
                [(xa - w, ya, za + 0.03), (xb - w, yb, zb + 0.03), (xb + w, yb, zb + 0.03), (xa + w, ya, za + 0.03)], glow)
    m.lathe(0.1, 0.44, 0.0, [(0.09, 0.0), (0.085, 0.04), (0.0, 0.05)], crust, segments=20, oval=0.6)
    m.lathe(0.07, 0.45, 0.0, [(0.05, 0.0), (0.045, 0.06), (0.0, 0.07)], glow, segments=20, oval=0.7)
    for x, y in ((0.2, 0.42), (-0.1, 0.44)):
        m.lathe(x, y, 0.0, [(0.035, 0.0), (0.03, 0.04), (0.0, 0.05)], crust, segments=14, oval=0.7)


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
    """A brick factory with pilasters and a sawtooth roof, the steep faces glazed toward +y with mullions down them, brick
    end walls stepped to the teeth, and a couple of roof fans."""
    paved(m, -0.5, 0.5, -0.5, 0.5, "#8a867e")
    brick = material("factory brick", "#8e4a38", rough=0.9)
    trim = material("factory trim", "#c8bca8", rough=0.8)
    x0, x1, y0, y1, eave, peak = -0.46, 0.46, -0.46, 0.46, 2.6, 3.4
    m.box(x0, x1, y0, y1, 0, eave, brick, 0.004)
    windows(m, x0, x1, y0, y1, 0.3, 1, 6, 6, glass(), trim, w=0.08, h=1.4)
    for face, at, d, a0, a1 in (("y", y0, -1, x0, x1), ("y", y1, 1, x0, x1), ("x", x0, -1, y0, y1), ("x", x1, 1, y0, y1)):
        for k in range(7):
            a = a0 + (a1 - a0) * k / 6
            houses.on_wall(m, face, at, d, a - 0.012, a + 0.012, 0, 0.01, 0, eave, brick)
        houses.on_wall(m, face, at, d, a0, a1, 0, 0.012, eave - 0.1, eave, trim)
    # The teeth: a roof slope rising toward +y to a steep glazed face, brick ends stepped to them.
    roof = material("sawtooth roof", "#4e4a46", rough=0.7)
    pane = material("north light glass", "#5a7088", rough=0.1, grime=0.15)
    bar = material("north light bars", "#2e3236", metal=0.4, rough=0.5)
    n, g = 4, 0.04
    for k in range(n):
        ya = y0 + k * (y1 - y0) / n
        yb = ya + (y1 - y0) / n
        yt = yb - g
        m.wedge([(x0, ya, eave), (x0, yt, peak), (x0, yb, eave)], [(x1, ya, eave), (x1, yt, peak), (x1, yb, eave)], roof)
        m.wedge([(x0 + 0.01, yt + 0.002, peak - 0.03), (x1 - 0.01, yt + 0.002, peak - 0.03), (x1 - 0.01, yb + 0.002, eave + 0.04), (x0 + 0.01, yb + 0.002, eave + 0.04)],
                [(x0 + 0.01, yt + 0.006, peak - 0.03), (x1 - 0.01, yt + 0.006, peak - 0.03), (x1 - 0.01, yb + 0.006, eave + 0.04), (x0 + 0.01, yb + 0.006, eave + 0.04)], pane)
        x = x0 + 0.01
        while x <= x1 - 0.005:
            m.rod((x, yt + 0.007, peak - 0.02), (x, yb + 0.007, eave + 0.03), 0.0025, bar, segments=4)
            x += 0.065
        m.rod((x0 + 0.01, yt + 0.007, peak - 0.03), (x1 - 0.01, yt + 0.007, peak - 0.03), 0.004, bar, segments=4)
        m.rod((x0 + 0.01, yb + 0.006, eave + 0.05), (x1 - 0.01, yb + 0.006, eave + 0.05), 0.004, bar, segments=4)
        for xe, o in ((x0, -0.006), (x1, 0.006)):
            m.wedge([(xe, ya, eave), (xe, yt, peak + 0.04), (xe, yb, eave)], [(xe + o, ya, eave), (xe + o, yt, peak + 0.04), (xe + o, yb, eave)], brick)
    fan = material("roof fan", "#8a8884", metal=0.5, rough=0.4)
    for x, k in ((-0.2, 1), (0.25, 2)):
        ya = y0 + k * (y1 - y0) / n
        y = ya + 0.06
        exhaust_fan(m, x, y, eave + (peak - eave) * 0.06 / ((y1 - y0) / n - g), eave + 0.45, fan)


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


def factory_yard(m, era=2000):
    """The factory's shipping yard: a chain link fence round the outer edges with its gate open on +y and a truck of its
    era in at it (a 1940s box truck before 1950, a long nose tractor and van trailer to 1985, an aero tractor after), pallets of cartons stacked two high, drums, crates and empty pallets, and a light
    standard with floodlights."""
    paved(m, -0.5, 0.5, -0.5, 0.5, "#8a867e")
    post = material("fence posts", "#7a7c7e", metal=0.6, rough=0.4)
    mesh = material("chain link", "#9a9c9e", metal=0.6, rough=0.5)
    gx0, gx1 = -0.08, 0.2
    # The fence along +y with the gate gap, and along +x.
    for a, b in (((-0.48, 0.47), (gx0, 0.47)), ((gx1, 0.47), (0.47, 0.47)), ((0.47, 0.47), (0.47, -0.48))):
        n = max(1, int(math.hypot(b[0] - a[0], b[1] - a[1]) / 0.1))
        for k in range(n + 1):
            x, y = a[0] + (b[0] - a[0]) * k / n, a[1] + (b[1] - a[1]) * k / n
            m.upright(x, y, 0, 0.72, 0.004, 0.004, post, segments=6)
        m.rod((a[0], a[1], 0.7), (b[0], b[1], 0.7), 0.0025, post, segments=4)
        beam(m, (a[0], a[1], 0.0), (b[0], b[1], 0.0), 0.002, 0.02, 0.68, mesh)
    # The gate leaves swung in.
    for hx, s in ((gx0, 1), (gx1, -1)):
        m.upright(hx, 0.47, 0, 0.8, 0.007, 0.007, post, segments=8)
        end = (hx + s * 0.1, 0.47 - 0.1)
        m.rod((hx, 0.47, 0.68), (end[0], end[1], 0.68), 0.003, post, segments=4)
        m.rod((hx, 0.47, 0.06), (end[0], end[1], 0.06), 0.003, post, segments=4)
        m.rod((end[0], end[1], 0.06), (end[0], end[1], 0.68), 0.003, post, segments=4)
        beam(m, (hx, 0.47, 0.0), (end[0], end[1], 0.0), 0.002, 0.06, 0.68, mesh)
    # The truck in at the gate.
    if era < 1950:
        moved(m, lambda mm: roadcars.truck(mm, 7 / 16, "box", "round", era=1940), 0.06, 0.22)
    elif era < 1985:
        moved(m, lambda mm: roadcars.tractor(mm, 11 / 16, "van", "sleeper", "#2a4a7a", era=1970), 0.06, 0.08)
    else:
        moved(m, lambda mm: roadcars.tractor(mm, 12 / 16, "van", "aero", "#c83a2a", era=2008), 0.06, 0.05)
    # Pallets of cartons, two high, in rows on the -x side.
    for i, x in enumerate((-0.4, -0.29)):
        for j, y in enumerate((-0.4, -0.29, -0.18, -0.07, 0.04)):
            harbour.pallet(m, x, y, 0.02, size=0.095, load="cartons")
            if (i + j) % 3:
                harbour.pallet(m, x, y, 0.29, size=0.095, load="cartons")
    # Empty pallets stacked up, drums and crates.
    wood = material("pallet", "#a08460", rough=0.9)
    for x, y, h in ((-0.4, 0.22, 10), (-0.29, 0.24, 7)):
        for k in range(h):
            m.box(x - 0.048, x + 0.048, y - 0.048, y + 0.048, 0.02 + k * 0.05, 0.02 + k * 0.05 + 0.035, wood)
    for k, (x, y) in enumerate(((0.3, -0.42), (0.34, -0.42), (0.38, -0.42), (0.42, -0.42), (0.32, -0.38), (0.36, -0.38), (0.4, -0.38),
                                (0.3, -0.33), (0.34, -0.33))):
        harbour.drum(m, x, y, 0.02, colour=("#2a4a7a", "#2a2a2c", "#a83226")[k % 3], r=0.017, h=0.36)
    harbour.crate(m, 0.26, 0.4, -0.22, -0.12, 0.02, 0.4)
    harbour.crate(m, 0.3, 0.4, -0.08, 0.02, 0.02, 0.3, colour="#9a7a4e")
    harbour.crate(m, 0.31, 0.39, -0.07, 0.01, 0.3, 0.52)
    # The light standard by the gate, two floodlights on its cross arm.
    pole = material("light standard", "#7a7c7e", metal=0.6, rough=0.4)
    lx, ly = 0.38, 0.36
    m.upright(lx, ly, 0, 2.4, 0.009, 0.006, pole, segments=10)
    m.box(lx - 0.012, lx + 0.012, ly - 0.012, ly + 0.012, 0, 0.12, material("light base", "#9a948a", rough=0.9))
    m.rod((lx - 0.06, ly + 0.0, 2.35), (lx + 0.06, ly, 2.35), 0.004, pole, segments=6)
    lamp_glass = material("flood glass", "#f4ecc8", rough=0.2)
    for s in (-1, 1):
        m.box(lx + s * 0.05 - 0.017, lx + s * 0.05 + 0.017, ly - 0.03, ly + 0.0, 2.25, 2.4, pole, 0.003)
        m.box(lx + s * 0.05 - 0.014, lx + s * 0.05 + 0.014, ly - 0.033, ly - 0.029, 2.27, 2.38, lamp_glass)


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


REFINERY_RAIL = ("refinery handrail", "#c8a030")


def pipe_rack(m, x, y0, y1, w, tiers, pipes, steel, bay=0.16):
    """A pipe rack along y at x from y0 to y1, w tiles wide: steel bents every bay with a beam for each tier, and pipes
    along each tier, pipes a list of (tier, offset across, radius, material)."""
    n = max(1, round((y1 - y0) / bay))
    for k in range(n + 1):
        y = y0 + (y1 - y0) * k / n
        for s in (-1, 1):
            m.box(x + s * w / 2 - 0.006, x + s * w / 2 + 0.006, y - 0.006, y + 0.006, 0, tiers[-1] + 0.06, steel)
        for z in tiers:
            m.box(x - w / 2 - 0.008, x + w / 2 + 0.008, y - 0.006, y + 0.006, z - 0.06, z, steel)
        if k < n:
            yn = y0 + (y1 - y0) * (k + 1) / n
            m.rod((x - w / 2, y, tiers[0] - 0.05), (x - w / 2, yn, tiers[-1] - 0.05), 0.0018, steel, segments=4)
    for t, o, r, mat in pipes:
        z = tiers[t] + r / rig.RISE
        m.tube(y0, y1, x + o, z, r, mat, segments=10)


def column(m, x, y, r, h, shell, steel, deck, rail, ladder_a=0.0, decks=None):
    """A distillation column: a skirt on a footing, its shell with seams, a domed head with a davit, walkways round it
    with handrails, a caged ladder up the side and the overhead line down to the rack."""
    m.upright(x, y, 0, 0.12, r + 0.02, r + 0.02, material("column footing", "#9a948a", rough=0.9), segments=20)
    m.upright(x, y, 0.12, 0.9, r * 1.03, r * 1.03, steel, segments=20)
    m.upright(x, y, 0.9, h, r, r, shell, segments=20)
    m.lathe(x, y, h, [(r, 0), (r * 0.85, r / rig.RISE * 0.4), (r * 0.45, r / rig.RISE * 0.75), (0.0, r / rig.RISE * 0.85)], shell, segments=20)
    for z in range(2, int(h), 2):
        m.upright(x, y, z, z + 0.04, r + 0.004, r + 0.004, steel, segments=20)
    decks = decks or [z for z in (h - 0.1, h * 0.66, h * 0.33)]
    for k, z in enumerate(decks):
        a0 = ladder_a - math.pi * (0.55 if k == 0 else 0.45)
        a1 = ladder_a + math.pi * (0.55 if k == 0 else 0.45)
        ring_deck(m, x, y, z, r, 0.026, deck, rail, a0=a0, a1=a1)
    column_ladder(m, x, y, r + 0.026, ladder_a, 0.0, decks[0], rail)
    top = h + r / rig.RISE * 0.85
    m.rod((x, y, top), (x, y, top + 0.35), 0.003, steel, segments=4)
    m.rod((x, y, top + 0.35), (x + 0.04, y, top + 0.3), 0.003, steel, segments=4)


def refinery_columns(m):
    """The process units as at Sarnia or Edmonton: four distillation columns with walkways and caged ladders, either side
    of a pipe rack running along y, the overhead lines down to it, and a fired heater box with its short stacks."""
    ground(m, "#6a6660", "refinery concrete")
    shell = material("column steel", "#b8b6b0", metal=0.6, rough=0.4)
    steel = material("refinery steelwork", "#5a5650", metal=0.5, rough=0.5)
    deck = material("refinery grating", "#4a4844", metal=0.4, rough=0.6)
    rail = material(*REFINERY_RAIL, metal=0.3, rough=0.5)
    pipe = material("refinery pipe", "#7a7a76", metal=0.6, rough=0.4)
    lagged = material("lagged pipe", "#c8c8c4", metal=0.7, rough=0.35)
    rust = material("rust pipe", "#7a4e36", metal=0.4, rough=0.6)
    yellow = material("gas pipe", "#c8a828", metal=0.3, rough=0.5)
    rx = 0.04
    tiers = [1.4, 2.0]
    pipe_rack(m, rx, -0.5, 0.5, 0.13, tiers, [(0, -0.045, 0.012, pipe), (0, -0.015, 0.016, lagged), (0, 0.02, 0.01, rust), (0, 0.045, 0.012, pipe),
                                               (1, -0.04, 0.014, lagged), (1, -0.005, 0.01, yellow), (1, 0.03, 0.018, pipe)], steel)
    cols = [(-0.24, -0.22, 0.075, 8.2, 0.0), (-0.26, 0.2, 0.055, 6.0, 0.0), (0.3, -0.3, 0.06, 7.5, math.pi), (0.32, -0.02, 0.048, 5.0, math.pi)]
    for x, y, r, h, a in cols:
        column(m, x, y, r, h, shell, steel, deck, rail, ladder_a=a + (0.6 if a == 0 else -0.6))
        top = h + r / rig.RISE * 0.85
        side = 1 if x < rx else -1
        ex = x + side * r * 0.4
        m.rod((x, y, top - 0.1), (ex + side * 0.05, y, top + 0.1), 0.012, pipe, segments=8)
        m.rod((ex + side * 0.05, y, top + 0.1), (ex + side * 0.07, y, tiers[1] + 0.2), 0.012, pipe, segments=8)
        m.rod((ex + side * 0.07, y, tiers[1] + 0.2), (rx - side * 0.05, y, tiers[1] + 0.05), 0.012, pipe, segments=8)
        m.rod((x + side * r, y, 1.1), (rx - side * 0.065, y, tiers[0] + 0.05), 0.01, rust, segments=8)
    # The fired heater: a brick lined box on legs, the convection section on top and two short stacks.
    hx0, hx1, hy0, hy1 = 0.18, 0.46, 0.18, 0.46
    heat = material("heater casing", "#8a8a86", metal=0.4, rough=0.55)
    for x in (hx0 + 0.02, hx1 - 0.02):
        for y in (hy0 + 0.02, hy1 - 0.02):
            m.box(x - 0.01, x + 0.01, y - 0.01, y + 0.01, 0, 0.5, steel)
    clad_box(m, hx0, hx1, hy0, hy1, 0.5, 2.4, heat, steel, gap=0.05)
    m.box(hx0 + 0.04, hx1 - 0.04, hy0 + 0.05, hy1 - 0.05, 2.4, 3.1, heat)
    m.box(hx0 + 0.035, hx1 - 0.035, hy0 + 0.045, hy1 - 0.045, 2.38, 2.48, steel)
    burner = lit("burner glow", "#ff8a3a", 2.0)
    for k in range(4):
        x = hx0 + 0.05 + k * 0.06
        m.upright(x, hy0 + 0.07, 0.3, 0.5, 0.01, 0.01, steel, segments=8)
        m.box(x - 0.01, x + 0.01, hy0 - 0.004, hy0, 0.9, 1.0, burner)
    for x in (hx0 + 0.09, hx1 - 0.09):
        y = (hy0 + hy1) / 2
        m.upright(x, y, 3.1, 4.6, 0.03, 0.026, heat, segments=14)
        m.upright(x, y, 4.5, 4.6, 0.032, 0.032, steel, segments=14)
    for a, b in (((hx0, hy0), (hx1, hy0)), ((hx1, hy0), (hx1, hy1)), ((hx1, hy1), (hx0, hy1)), ((hx0, hy1), (hx0, hy0))):
        m.rod((a[0], a[1], 2.62), (b[0], b[1], 2.62), 0.0018, rail, segments=4)
        m.rod((a[0], a[1], 2.4), (a[0], a[1], 2.62), 0.0016, rail, segments=4)
    for a, b in (((hx0, hy0 + 0.1), (rx + 0.07, hy0 + 0.1)), ((hx0, hy1 - 0.1), (rx + 0.07, hy1 - 0.1))):
        m.rod((a[0], a[1], 1.6), (b[0], b[1], tiers[0] + 0.05), 0.012, lagged, segments=8)
    column_ladder(m, hx0, hy0 + 0.2, 0.0, math.pi, 0.0, 2.4, rail)


def floating_roof_tank(m, x, y, r, h, shell, roof, rail, stair_a=0.0, turn=1.9):
    """An open topped tank with a floating roof: the deck down inside the shell, the rim and wind girder round the top,
    the rolling ladder down onto the roof and a spiral stair up the side, going turn radians round."""
    m.upright(x, y, 0, h - 0.3, r, r, shell, segments=36)
    m.upright(x, y, h - 0.3, h - 0.17, r - 0.007, r - 0.007, roof, segments=36)
    m.lathe(x, y, h - 0.3, [(r, 0), (r, 0.3), (r - 0.006, 0.3), (r - 0.006, 0)], shell, segments=36)
    pipe_ring(m, x, y, h - 0.12, r + 0.006, 0.007, material("wind girder", "#9a9690", metal=0.4, rough=0.5), n=36)
    m.upright(x, y, h - 0.17, h - 0.14, r * 0.25, r * 0.25, material("roof drain sump", "#5a5854", metal=0.4, rough=0.6), segments=12)
    a = stair_a + turn
    c, s = math.cos(a), math.sin(a)
    m.rod((x + c * r, y + s * r, h + 0.2), (x + c * r * 0.2, y + s * r * 0.2, h - 0.16), 0.003, rail, segments=4)
    m.rod((x + c * r - s * 0.012, y + s * r + c * 0.012, h + 0.2), (x + c * r * 0.2 - s * 0.012, y + s * r * 0.2 + c * 0.012, h - 0.16), 0.003, rail, segments=4)
    m.box(x + c * r - 0.015, x + c * r + 0.015, y + s * r - 0.015, y + s * r + 0.015, h, h + 0.04, rail)
    spiral_stair(m, x, y, r, h, stair_a, turn, material("stair treads", "#5a5854", metal=0.4, rough=0.6), rail)
    pipe_ring(m, x, y, h + 0.2, r - 0.004, 0.0015, rail, n=28)


def berm(m, x0, x1, y0, y1, h, mat, foot=0.06, top=0.02):
    """An earth berm round x0..x1 by y0..y1: a bank h steps high, foot tiles across at the bottom, top across on top."""
    for a, b in (((x0, y0), (x1, y0)), ((x1, y0), (x1, y1)), ((x1, y1), (x0, y1)), ((x0, y1), (x0, y0))):
        dx, dy = b[0] - a[0], b[1] - a[1]
        n = math.hypot(dx, dy)
        nx, ny = dy / n, -dx / n
        ext = foot / 2
        ax, ay = a[0] - dx / n * ext, a[1] - dy / n * ext
        bx, by = b[0] + dx / n * ext, b[1] + dy / n * ext
        bot = [(ax - nx * foot / 2, ay - ny * foot / 2, 0), (bx - nx * foot / 2, by - ny * foot / 2, 0),
               (bx + nx * foot / 2, by + ny * foot / 2, 0), (ax + nx * foot / 2, ay + ny * foot / 2, 0)]
        tp = [(ax - nx * top / 2, ay - ny * top / 2, h), (bx - nx * top / 2, by - ny * top / 2, h),
              (bx + nx * top / 2, by + ny * top / 2, h), (ax + nx * top / 2, ay + ny * top / 2, h)]
        m.wedge(bot, tp, mat)


def refinery_tanks(m):
    """The tank farm: two floating roof tanks and a cone roof tank, each with a spiral stair, inside an earth berm, with a
    pipe stair over it and the tank lines out through it toward the units at -y."""
    ground(m, "#6a6660", "refinery concrete")
    white = material("tank white", "#d8d4cc", metal=0.3, rough=0.5)
    roof = material("floating roof", "#a8a6a0", metal=0.4, rough=0.55)
    cone = material("tank roof", "#9a9690", metal=0.4, rough=0.5)
    rail = material(*REFINERY_RAIL, metal=0.3, rough=0.5)
    pipe = material("refinery pipe", "#7a7a76", metal=0.6, rough=0.4)
    m.box(-0.44, 0.44, -0.44, 0.44, 0.0, 0.03, material("tank field gravel", "#7a7466", rough=1.0))
    berm(m, -0.45, 0.45, -0.45, 0.45, 0.28, material("berm grass", "#6a7448", rough=1.0))
    # Tanks about 12 to 15 m tall; the stairs wind further round to keep their pitch.
    floating_roof_tank(m, -0.17, -0.17, 0.22, 3.5, white, roof, rail, stair_a=math.pi * 0.85, turn=3.4)
    floating_roof_tank(m, 0.2, 0.2, 0.19, 3.2, white, roof, rail, stair_a=math.pi * 1.3, turn=3.4)
    tx, ty, tr, th = 0.25, -0.24, 0.12, 2.8
    m.upright(tx, ty, 0, th, tr, tr, white, segments=28)
    m.lathe(tx, ty, th, [(tr + 0.004, 0), (tr * 0.5, 0.18), (0.0, 0.25)], cone, segments=28)
    spiral_stair(m, tx, ty, tr, th, math.pi * 0.6, 3.4, material("stair treads", "#5a5854", metal=0.4, rough=0.6), rail)
    pipe_ring(m, tx, ty, th + 0.12, tr * 0.8, 0.0015, rail, n=20)
    # The tank lines out to -y through the berm, on sleepers.
    for x in (-0.06, 0.0, 0.06):
        m.tube(-0.5, -0.3, x, 0.12, 0.012, pipe, segments=10)
    m.rod((-0.06, -0.3, 0.12), (-0.17 + 0.16, -0.17 - 0.15, 0.12), 0.012, pipe, segments=8)
    m.rod((0.06, -0.3, 0.12), (tx - 0.1, ty - 0.07, 0.12), 0.012, pipe, segments=8)
    m.rod((0.0, -0.3, 0.12), (0.06, 0.04, 0.12), 0.012, pipe, segments=8)
    m.rod((0.06, 0.04, 0.12), (0.2 - 0.12, 0.2 - 0.15, 0.12), 0.012, pipe, segments=8)
    for y in (-0.48, -0.36):
        m.box(-0.09, 0.09, y - 0.01, y + 0.01, 0.0, 0.1, material("pipe sleeper", "#9a948a", rough=0.9))
    # A stair over the berm on -x.
    st = material("stair treads", "#5a5854", metal=0.4, rough=0.6)
    for k in range(5):
        x = -0.5 + 0.012 + k * 0.016
        m.box(x - 0.008, x + 0.008, 0.08, 0.12, 0.0, 0.08 + k * 0.05, st)


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
    aermotor(m, -0.33, 0.34, 4.3, r=0.11)
    stock_tank(m, -0.19, 0.38, 0.07)
    m.rod((-0.33, 0.34, 0.3), (-0.23, 0.38, 0.32), 0.004, material("pump pipe", "#5a5a58", metal=0.6, rough=0.4))


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

def clad_box(m, x0, x1, y0, y1, z0, z1, wall, rib, gap=0.03):
    """A box in ribbed metal cladding, the ribs up all four walls."""
    m.box(x0, x1, y0, y1, z0, z1, wall)
    harbour.ribs(m, x0, x1, y0, z0, z1, rib, gap, face=-1)
    harbour.ribs(m, x0, x1, y1, z0, z1, rib, gap, face=1)
    harbour.ribs_y(m, x0, y0, y1, z0, z1, rib, gap, face=-1)
    harbour.ribs_y(m, x1, y0, y1, z0, z1, rib, gap, face=1)


def bent(m, x, y, z, w, mat, along_y=True):
    """A steel bent under a gallery or conveyor: two posts w apart up to z with a cross brace."""
    p = [(x - w / 2, y), (x + w / 2, y)] if along_y else [(x, y - w / 2), (x, y + w / 2)]
    for px, py in p:
        m.box(px - 0.005, px + 0.005, py - 0.005, py + 0.005, 0, z, mat)
    m.rod((p[0][0], p[0][1], 0.1), (p[1][0], p[1][1], z * 0.5), 0.0022, mat, segments=4)
    m.rod((p[1][0], p[1][1], z * 0.5), (p[0][0], p[0][1], z - 0.05), 0.0022, mat, segments=4)


PULP_WALL = ("pulp mill cladding", "#b8b4a8")
PULP_RIB = ("pulp mill ribs", "#a29e94")
GALLERY_X, GALLERY_W, GALLERY_H = -0.3, 0.07, 0.45
# The chip gallery's floor where it crosses from the chip pile's tile into the digester's, and its climb in steps a tile.
# Under about 2.7 steps a tile a climb towards the viewer reads level, so it climbs well over that.
GALLERY_Z, GALLERY_CLIMB = 4.4, 6.5


def digester(m):
    """The pulp mill's cooking end: the tall clad digester house with the chip gallery climbing to its top, the blow tank
    beside it, and the recovery boiler house with its precipitator and tall concrete stack."""
    ground(m, "#6a6660", "mill concrete")
    wall = material(*PULP_WALL, metal=0.3, rough=0.6)
    rib = material(*PULP_RIB, metal=0.3, rough=0.6)
    brick = material("mill brick", "#8e4a36", rough=0.9)
    trim = material("mill trim", "#5a5c5e", metal=0.3, rough=0.6)
    steel = material("digester steel", "#8a8a86", metal=0.5, rough=0.5)
    roof = material("gallery roof", "#4e5458", metal=0.3, rough=0.6)
    frame = material("mill steel", "#4a4c4e", metal=0.5, rough=0.5)
    g = glass()
    # The digester house, brick below and clad above, a stair tower's windows up its end.
    hx0, hx1, hy0, hy1, top = -0.46, -0.14, -0.2, 0.24, 7.0
    m.box(hx0 - 0.004, hx1 + 0.004, hy0 - 0.004, hy1 + 0.004, 0, 1.1, brick)
    clad_box(m, hx0, hx1, hy0, hy1, 1.1, top, wall, rib)
    houses.flat_top(m, hx0, hx1, hy0, hy1, top, trim, parapet=0.15)
    for k in range(7):
        z = 1.4 + k * 0.65
        houses.pane(m, "x", hx0, -1, 0.14, z, z + 0.32, 0.05, g, trim, trim, sash=False)
        houses.pane(m, "y", hy1, 1, -0.3, z, z + 0.32, 0.05, g, trim, trim, sash=False)
    for y in (-0.08, 0.06):
        houses.pane(m, "x", hx0, -1, y, 0.35, 0.8, 0.06, g, trim, trim, sash=False, bars=1)
    # The digesters' domed tops and relief stacks standing up through the roof.
    for y in (-0.06, 0.12):
        m.lathe(-0.3, y, top, [(0.07, 0), (0.06, 0.18), (0.03, 0.3), (0.0, 0.33)], steel, segments=20)
        m.upright(-0.3, y, top + 0.3, top + 0.75, 0.008, 0.008, steel, segments=8)
    # The gallery's last stretch, from the tile edge up to the house's top.
    gz1 = GALLERY_Z + (hy0 + 0.5) * GALLERY_CLIMB
    gallery(m, (GALLERY_X, -0.5, GALLERY_Z), (GALLERY_X, hy0, gz1), GALLERY_W, GALLERY_H, wall, roof, g)
    bent(m, GALLERY_X, -0.42, GALLERY_Z + 0.08 * GALLERY_CLIMB, GALLERY_W + 0.02, frame)
    # The blow tank beside it, the blow line across from the house, and its vent.
    bx, by, br = -0.43, -0.37, 0.062
    m.upright(bx, by, 0, 0.3, br * 1.05, br * 1.05, material("tank footing", "#9a968c", rough=1.0), segments=24)
    m.upright(bx, by, 0.3, 3.4, br, br, steel, segments=24)
    for z in (1.1, 2.0, 2.8):
        m.upright(bx, by, z, z + 0.05, br * 1.02, br * 1.02, frame, segments=24)
    m.lathe(bx, by, 3.4, [(br, 0), (br * 0.6, 0.25), (0.012, 0.35)], steel, segments=24)
    m.upright(bx, by, 3.7, 4.3, 0.012, 0.012, steel, segments=8)
    m.rod((-0.42, hy0, 3.3), (bx, by + 0.03, 3.6), 0.01, steel, segments=8)
    harbour.ladder(m, bx - br - 0.004, by, 0.3, 3.5, face_x=True, grab=0.1)
    # The recovery boiler house, taller at its back, with a band of windows at each level.
    rx0, rx1, ry0, ry1 = -0.06, 0.44, -0.04, 0.46
    wall = material("boiler cladding", "#8e9aa2", metal=0.3, rough=0.6)
    rib = material("boiler ribs", "#7c8890", metal=0.3, rough=0.6)
    m.box(rx0 - 0.004, rx1 + 0.004, ry0 - 0.004, ry1 + 0.004, 0, 1.1, brick)
    clad_box(m, rx0, rx1, ry0, ry1, 1.1, 4.4, wall, rib)
    clad_box(m, 0.1, rx1, 0.12, ry1, 4.4, 5.4, wall, rib)
    houses.flat_top(m, rx0, rx1, ry0, ry1, 4.4, trim, parapet=0.12)
    houses.flat_top(m, 0.1, rx1, 0.12, ry1, 5.4, trim, parapet=0.12)
    for z in (1.5, 2.6, 3.6):
        for face, at, d, a0, a1 in (("x", rx0, -1, ry0 + 0.03, ry1 - 0.03), ("y", ry1, 1, rx0 + 0.03, rx1 - 0.03), ("y", ry0, -1, rx0 + 0.03, rx1 - 0.03)):
            houses.on_wall(m, face, at, d, a0, a1, 0, 0.007, z, z + 0.3, g)
            houses.on_wall(m, face, at, d, a0, a1, 0, 0.009, z - 0.03, z, trim)
    for x in (0.18, 0.34):
        m.box(x - 0.03, x + 0.03, 0.2, 0.28, 5.4, 5.75, material("roof vent", "#6a6a66", metal=0.5, rough=0.5))
    # The precipitator, its duct round to the stack, and the stack itself.
    ex0, ex1, ey0, ey1 = 0.1, 0.3, -0.24, -0.04
    m.box(ex0, ex1, ey0, ey1, 0.9, 2.9, material("precipitator", "#8e8e88", metal=0.4, rough=0.6))
    for k in range(3):
        x = ex0 + 0.035 + k * 0.065
        m.wedge([(x - 0.03, ey0 + 0.02, 0.9), (x + 0.03, ey0 + 0.02, 0.9), (x + 0.03, ey1 - 0.02, 0.9), (x - 0.03, ey1 - 0.02, 0.9)],
                [(x - 0.01, ey0 + 0.07, 0.4), (x + 0.01, ey0 + 0.07, 0.4), (x + 0.01, ey1 - 0.07, 0.4), (x - 0.01, ey1 - 0.07, 0.4)], frame)
        m.box(x - 0.004, x + 0.004, (ey0 + ey1) / 2 - 0.004, (ey0 + ey1) / 2 + 0.004, 0, 0.4, frame)
    m.box(ex0, ex1, ey0, ey1, 2.9, 3.0, trim)
    sx, sy = 0.33, -0.33
    m.box(0.26, 0.31, -0.3, ey0, 2.1, 2.6, frame)
    stack(m, sx, sy, 0, 8.5, 0.05, material("stack concrete", "#b8b2a6", rough=0.9), material("stack band", "#7a3a2a", rough=0.9))
    m.upright(sx, sy, 7.6, 7.66, 0.064, 0.064, frame, segments=16)
    # Steam and liquor lines across from the boiler house to the digester house.
    for y, r in ((0.02, 0.012), (0.06, 0.009), (0.09, 0.009)):
        m.tube_x(hx1, rx0, y, 3.1, r, steel, segments=10)


def chip_pile(m):
    """The chip pile with the radial stacker reaching out over it on its A-frame, and the start of the enclosed gallery
    that takes the chips up to the digester house."""
    ground(m, "#6a6660", "mill concrete")
    steel = material("stacker steel", "#c8a032", metal=0.4, rough=0.5)
    frame = material("mill steel", "#4a4c4e", metal=0.5, rough=0.5)
    chips = material("wood chips #c8a468", "#c8a468", rough=1.0)
    heap(m, 0.12, 0.04, 0.34, 2.2, "#c8a468", 91, "wood chips")
    # The stacker: a lattice boom from its pivot to the head pulley over the pile's peak, the belt on top under a hood.
    tail, head = (0.13, -0.5, 0.55), (0.13, -0.02, 2.85)
    harbour.lattice(m, (tail[0], tail[1], tail[2] - 0.15), (head[0], head[1], head[2] - 0.15), 0.06, 0.05, steel, n=8, r=0.004, axis=(1.0, 0.0))
    conveyor(m, tail, head, 0.05, steel, material("conveyor belt", "#2e2a26", rough=0.8), legs=0, hood=material("belt hood", "#9a9890", metal=0.4, rough=0.5),
             load=chips)
    m.tube_x(head[0] - 0.03, head[0] + 0.03, head[1], head[2] + 0.02, 0.016, frame, segments=12)
    m.upright(head[0], head[1] + 0.01, 2.15, head[2] - 0.05, 0.02, 0.008, chips, segments=10)
    y = -0.24
    z = tail[2] + (head[2] - tail[2]) * (y - tail[1]) / (head[1] - tail[1]) - 0.2
    for s in (-1, 1):
        m.rod((tail[0] + s * 0.07, y - 0.04, 0), (tail[0] + s * 0.025, y, z), 0.005, steel, segments=6)
        m.tube_x(tail[0] + s * 0.07 - 0.006, tail[0] + s * 0.07 + 0.006, y - 0.04, 0.015 / rig.RISE, 0.015, frame, segments=10)
    m.rod((tail[0] - 0.07, y - 0.04, 0.15), (tail[0] + 0.07, y - 0.04, 0.15), 0.003, steel, segments=4)
    m.box(tail[0] - 0.04, tail[0] + 0.04, -0.5, -0.42, 0, 0.5, frame)
    # The gallery climbing from the reclaim house at the pile's foot, on to the digester.
    wall = material(*PULP_WALL, metal=0.3, rough=0.6)
    rib = material(*PULP_RIB, metal=0.3, rough=0.6)
    roof = material("gallery roof", "#4e5458", metal=0.3, rough=0.6)
    z0 = 0.55
    y0 = 0.5 - (GALLERY_Z - z0) / GALLERY_CLIMB
    clad_box(m, GALLERY_X - 0.07, GALLERY_X + 0.07, y0 - 0.1, y0 + 0.07, 0, 1.1, wall, rib)
    houses.flat_top(m, GALLERY_X - 0.07, GALLERY_X + 0.07, y0 - 0.1, y0 + 0.07, 1.1, material("mill trim", "#5a5c5e", metal=0.3, rough=0.6), parapet=0.1)
    gallery(m, (GALLERY_X, y0, z0), (GALLERY_X, 0.5, GALLERY_Z), GALLERY_W, GALLERY_H, wall, roof, glass())
    for yy in (0.15, 0.4):
        bent(m, GALLERY_X, yy, z0 + (GALLERY_Z - z0) * (yy - y0) / (0.5 - y0), GALLERY_W + 0.02, frame)
    # A dozer working the pile.
    dozer = material("dozer yellow", "#d8a428", metal=0.3, rough=0.5)
    dark = material("dozer tracks", "#2a2a2a", metal=0.4, rough=0.7)
    dx, dy = 0.3, 0.32
    for s in (-1, 1):
        m.box(dx - 0.035, dx + 0.035, dy + s * 0.022 - 0.008, dy + s * 0.022 + 0.008, 0.0, 0.14, dark, 0.004)
    m.box(dx - 0.03, dx + 0.03, dy - 0.016, dy + 0.016, 0.1, 0.3, dozer, 0.003)
    m.box(dx - 0.012, dx + 0.02, dy - 0.015, dy + 0.015, 0.3, 0.5, dozer, 0.003)
    m.box(dx - 0.05, dx - 0.044, dy - 0.04, dy + 0.04, 0.0, 0.18, dozer)


PAPER_WALL = ("paper mill siding", "#c8c2b4")


def paper_hall(m, y0, y1, eave, ridge, base=0.9, band=(-1, 1)):
    """A stretch of the paper machine hall, x across the tile, from y0 to y1: brick up to the machine floor, ribbed
    siding over it with a band of windows high up both sides, and a pitched roof. The tile pieces of the hall meet end to
    end."""
    wall = material(*PAPER_WALL, rough=0.85)
    rib = material("paper mill ribs", "#b0aa9c", rough=0.85)
    roof = material("paper mill roof", "#4e5458", metal=0.3, rough=0.6)
    brick = material("paper mill brick", "#8e4a36", rough=0.9)
    trim = material("mill trim", "#6a6c6e", metal=0.3, rough=0.7)
    x0, x1 = -0.42, 0.42
    m.box(x0, x1, y0, y1, 0, eave, wall)
    m.box(x0 - 0.004, x1 + 0.004, y0, y1, 0, base, brick)
    for x, d in ((x0, -1), (x1, 1)):
        harbour.ribs_y(m, x, y0, y1, base, eave, rib, 0.03, face=d)
        if d in band:
            houses.on_wall(m, "x", x, d, y0, y1, 0, 0.009, eave - 0.85, eave - 0.3, glass())
            houses.on_wall(m, "x", x, d, y0, y1, 0, 0.012, eave - 0.9, eave - 0.85, trim)
            houses.on_wall(m, "x", x, d, y0, y1, 0, 0.012, eave - 0.3, eave - 0.25, trim)
            n = max(1, round((y1 - y0) / 0.06))
            for k in range(n + 1):
                a = y0 + (y1 - y0) * k / n
                houses.on_wall(m, "x", x, d, a - 0.003, a + 0.003, 0, 0.012, eave - 0.85, eave - 0.3, trim)
        for k in range(max(1, round((y1 - y0) / 0.2))):
            a = y0 + (k + 0.5) * (y1 - y0) / max(1, round((y1 - y0) / 0.2))
            houses.pane(m, "x", x, d, a, 0.25, 0.65, 0.07, glass(), trim, trim, sash=False, bars=1)
    gable(m, x0, x1, y0, y1, eave, ridge, roof, wall, along_x=False, over=0.0)
    return wall, rib, roof, brick, trim


def exhaust_fan(m, x, y, z0, z1, mat):
    """A roof exhaust fan: a short round stack with a mushroom cap."""
    m.upright(x, y, z0, z1, 0.03, 0.03, mat, segments=16)
    m.lathe(x, y, z1 + 0.05, [(0.045, 0), (0.04, 0.08), (0.0, 0.12)], mat, segments=16)
    for k in range(4):
        a = k * math.pi / 2
        m.rod((x + 0.026 * math.cos(a), y + 0.026 * math.sin(a), z1 - 0.02), (x + 0.036 * math.cos(a), y + 0.036 * math.sin(a), z1 + 0.06), 0.002, mat, segments=4)


def paper_machine(m):
    """The middle of the paper machine hall, over the dryer section: steam hoods rising through the roof with their
    exhaust fans, more fans along the slopes, and the steam lines along the wall."""
    ground(m, "#6a6660", "mill concrete")
    eave, ridge = 2.6, 3.4
    wall, rib, roof, brick, trim = paper_hall(m, -0.5, 0.5, eave, ridge)
    vent = material("vent", "#6a6a66", metal=0.5, rough=0.5)
    for y in (-0.24, 0.2):
        clad_box(m, -0.12, 0.12, y - 0.12, y + 0.12, 3.0, 3.7, wall, rib, gap=0.03)
        gable(m, -0.12, 0.12, y - 0.12, y + 0.12, 3.7, 3.85, roof, wall, along_x=True, over=0.01, thick=0.05)
        for dy in (-0.06, 0.06):
            exhaust_fan(m, 0.0, y + dy, 3.85, 4.05, vent)
    for x in (-0.25, 0.25):
        z = eave + (ridge - eave) * (1 - abs(x) / 0.42)
        for y in (-0.42, -0.02, 0.4):
            exhaust_fan(m, x, y, z - 0.08, z + 0.2, vent)
    # Steam lines along the near wall on brackets.
    steel = material("steam pipe", "#8a8a86", metal=0.5, rough=0.5)
    for z, r in ((1.35, 0.012), (1.52, 0.009)):
        m.tube(-0.5, 0.5, -0.45, z, r, steel, segments=10)
    for y in (-0.35, 0.0, 0.35):
        m.box(-0.462, -0.42, y - 0.004, y + 0.004, 1.25, 1.3, trim)


def paper_wet_end(m):
    """The wet end of the paper machine hall, lower than the rest: a louvred vapour monitor along its ridge, the stock
    preparation house at its end with the stock chests and their pipes."""
    ground(m, "#6a6660", "mill concrete")
    eave, ridge = 1.9, 2.6
    y1 = 0.24
    wall, rib, roof, brick, trim = paper_hall(m, -0.5, y1, eave, ridge)
    harbour.ribs(m, -0.42, 0.42, y1, 0.9, eave, rib, 0.03, face=1)
    for x in (-0.28, -0.12, 0.12, 0.28):
        houses.pane(m, "y", y1, 1, x, 1.05, 1.6, 0.08, glass(), trim, trim, sash=False, bars=2)
    m.box(-0.07, 0.07, -0.44, y1 - 0.06, 2.4, 2.85, wall)
    slat = material("monitor louvres", "#8a8680", metal=0.3, rough=0.6)
    for x, d in ((-0.07, -1), (0.07, 1)):
        for k in range(5):
            houses.louvres(m, "x", x, d, -0.38 + k * 0.13, 2.5, 2.78, 0.09, trim, slat)
    gable(m, -0.07, 0.07, -0.44, y1 - 0.06, 2.85, 2.97, roof, wall, along_x=False, over=0.02, thick=0.05)
    # The stock preparation house, in brick, flat roofed.
    sx0, sx1, sy0, sy1 = -0.4, 0.1, y1, 0.46
    m.box(sx0, sx1, sy0, sy1, 0, 1.3, brick, 0.003)
    houses.flat_top(m, sx0, sx1, sy0, sy1, 1.3, trim, parapet=0.12)
    for x in (-0.3, -0.15, 0.0):
        houses.pane(m, "y", sy1, 1, x, 0.35, 0.95, 0.06, glass(), trim, trim, sash=True, bars=1)
    houses.pane(m, "x", sx0, -1, 0.35, 0.35, 0.95, 0.06, glass(), trim, trim, sash=True, bars=1)
    # A stock chest, with its pipes into the house.
    steel = material("stock chest", "#9a9890", metal=0.4, rough=0.5)
    x, y, r = 0.27, 0.35, 0.075
    m.upright(x, y, 0, 1.8, r, r, steel, segments=24)
    m.upright(x, y, 1.8, 1.9, r * 1.02, r * 0.4, steel, segments=24)
    for z in (0.6, 1.2):
        m.upright(x, y, z, z + 0.04, r * 1.02, r * 1.02, trim, segments=24)
    harbour.ladder(m, x + r + 0.004, y, 0, 1.9, face_x=True, grab=0.1)
    for z in (0.7, 1.1):
        m.tube_x(sx1, 0.2, 0.36, z, 0.01, steel, segments=10)
    m.rod((0.27, 0.35, 1.9), (0.27, y1 + 0.004, 1.9), 0.01, steel, segments=8)


def paper_dry_end(m):
    """The dry end of the paper machine hall, where the reel and winders are: the mill's name along the hall, and the
    finishing room at the end with its dock, roll-up doors and rolls of newsprint waiting to ship."""
    ground(m, "#6a6660", "mill concrete")
    eave, ridge = 2.6, 3.4
    y0 = -0.24
    wall, rib, roof, brick, trim = paper_hall(m, y0, 0.5, eave, ridge, band=(1,))
    harbour.ribs(m, -0.42, 0.42, y0, 0.9, eave, rib, 0.03, face=-1)
    vent = material("vent", "#6a6a66", metal=0.5, rough=0.5)
    for x in (-0.25, 0.25):
        z = eave + (ridge - eave) * (1 - abs(x) / 0.42)
        exhaust_fan(m, x, 0.2, z - 0.08, z + 0.2, vent)
    letters = material("mill letters", "#2e3a4a", rough=0.7)
    houses.on_wall(m, "x", -0.42, -1, y0 + 0.03, 0.47, 0, 0.01, eave - 0.75, eave - 0.25, material("mill sign board", "#e8e4d8", rough=0.7))
    houses.sign_text(m, "CEDAR RIVER PAPER", "x", -0.42, -1, (y0 + 0.5) / 2, eave - 0.62, 0.05, letters, o=0.011)
    # The finishing room and shipping dock at the hall's end.
    fx0, fx1, fy0, fy1 = -0.42, 0.42, -0.38, y0
    m.box(fx0, fx1, fy0, fy1, 0, 1.7, brick, 0.003)
    houses.flat_top(m, fx0, fx1, fy0, fy1, 1.7, trim, parapet=0.12)
    for x in (-0.24, 0.0, 0.24):
        harbour.rollup_door(m, x - 0.06, x + 0.06, fy0, 0.42, 1.25, face=-1, colour="#5a6670")
    dz = 0.42
    m.box(-0.42, 0.42, -0.49, fy0, 0, dz, material("dock concrete", "#a8a296", rough=0.9), 0.003)
    slab(m, [(-0.43, fy0, 1.55), (0.43, fy0, 1.55), (0.43, -0.5, 1.42), (-0.43, -0.5, 1.42)], 0.05, roof)
    post = material("canopy posts", "#3a3c3e", metal=0.4, rough=0.6)
    for x in (-0.4, 0.0, 0.4):
        m.box(x - 0.006, x + 0.006, -0.487, -0.475, dz, 1.44, post)
    paper = material("newsprint rolls", "#e8e4da", rough=0.7)
    wrap = material("roll wrap", "#a08a68", rough=0.8)
    for k, x in enumerate((-0.36, -0.31, -0.26, -0.12, -0.07, 0.1, 0.15, 0.3)):
        for j, y in enumerate((-0.45, -0.405)):
            if (k + j) % 3 == 2:
                continue
            m.upright(x, y, dz, dz + 0.4, 0.021, 0.021, paper, segments=16)
            m.upright(x, y, dz + 0.4, dz + 0.41, 0.021, 0.021, wrap, segments=16)


def printing_works(m):
    """The newspaper's front building: three storeys of brick with stone bands and arched windows, the paper's name
    across a sign band at the top, a stone entrance and shop windows on the street, and a water tank on the roof."""
    paved(m, -0.5, 0.5, -0.5, 0.5, "#8a867e")
    brick = material("printer brick", "#9a5a42", rough=0.9)
    stone = material("printer stone", "#d0c6b2", rough=0.8)
    frame = material("printer frames", "#2e3a34", rough=0.6)
    g = glass()
    x0, x1, y0, y1 = -0.42, 0.42, -0.42, 0.5
    top = 3 * STOREY + 0.2
    m.box(x0, x1, y0, y1, 0, top, brick, 0.004)
    houses.foundation(m, x0, x1, y0, y1, 0.22, stone, out=0.005)
    m.box(-0.5, x0, -0.5, 0.5, 0, 0.03, material("sidewalk", "#aaa498", rough=0.9))
    walls = (("x", x0, -1, y0, y1, 5), ("y", y0, -1, x0, x1, 4), ("x", x1, 1, y0, y1, 5))
    for face, at, d, a0, a1, n in walls:
        for s in (1, 2):
            z = 0.2 + s * STOREY
            houses.on_wall(m, face, at, d, a0, a1, 0, 0.01, z - 0.05, z + 0.02, stone)
            for k in range(n):
                houses.arched_pane(m, face, at, d, a0 + (k + 0.5) * (a1 - a0) / n, z + 0.25, z + 0.8, 0.075, g, frame, stone, rise=0.12)
    # Ground floor on the street: big front windows either side of the entrance.
    for k in range(5):
        c = y0 + (k + 0.5) * (y1 - y0) / 5
        if k == 2:
            continue
        houses.pane(m, "x", x0, -1, c, 0.35, 1.15, 0.13, g, frame, stone, sash=False, bars=2)
        houses.on_wall(m, "x", x0, -1, c - 0.07, c + 0.07, 0, 0.006, 1.2, 1.35, frame)
    for k in range(4):
        houses.pane(m, "y", y0, -1, x0 + (k + 0.5) * (x1 - x0) / 4, 0.4, 1.0, 0.07, g, frame, stone, bars=1)
    # The entrance: a stone surround with an arch and pilasters, double doors and steps.
    c = y0 + 2.5 * (y1 - y0) / 5
    houses.on_wall(m, "x", x0, -1, c - 0.09, c + 0.09, 0, 0.012, 0.15, 1.45, stone)
    houses.on_wall(m, "x", x0, -1, c - 0.055, c + 0.055, 0.012, 0.016, 0.15, 1.0, material("printer doors", "#3a2a22", rough=0.6))
    houses.on_wall(m, "x", x0, -1, c - 0.055, c + 0.055, 0.012, 0.016, 1.05, 1.3, g)
    houses.on_wall(m, "x", x0, -1, c - 0.1, c + 0.1, 0, 0.03, 1.45, 1.55, stone)
    for k in range(3):
        houses.on_wall(m, "x", x0, -1, c - 0.08, c + 0.08, 0.012, 0.03 + 0.012 * (2 - k), k * 0.05, k * 0.05 + 0.05, stone)
    letters = material("printer letters", "#e8dcb8", metal=0.3, rough=0.5)
    houses.sign_text(m, "THE", "x", x0, -1, c, 1.36, 0.028, letters, o=0.032)
    # The sign band under the cornice with the paper's name, and a date stone over it.
    houses.on_wall(m, "x", x0, -1, y0 + 0.02, y1 - 0.02, 0, 0.012, top - 0.62, top - 0.12, material("printer sign", "#2a3a34", rough=0.6))
    houses.sign_text(m, "MORNING LANTERN", "x", x0, -1, (y0 + y1) / 2, top - 0.5, 0.055, letters, o=0.013)
    houses.on_wall(m, "x", x0, -1, y0, y1, 0, 0.03, top - 0.12, top - 0.04, stone)
    houses.flat_top(m, x0, x1, y0, y1, top, stone, parapet=0.4)
    houses.on_wall(m, "x", x0, -1, c - 0.1, c + 0.1, 0, 0.012, top + 0.4, top + 0.65, stone)
    m.wedge([(x0 - 0.012, c - 0.12, top + 0.62), (x0 + 0.01, c - 0.12, top + 0.62), (x0 + 0.01, c + 0.12, top + 0.62), (x0 - 0.012, c + 0.12, top + 0.62)],
            [(x0 - 0.012, c, top + 0.8), (x0 + 0.01, c, top + 0.8), (x0 + 0.01, c, top + 0.8), (x0 - 0.012, c, top + 0.8)], stone)
    water_tank(m, 0.18, 0.05, top, 0.06)
    houses.lamp_post(m, -0.47, -0.3)
    houses.lamp_post(m, -0.47, 0.3)


def lit(name, colour, strength):
    """A material that gives off its own light, for a lit room seen through windows."""
    mat = material(name, colour, rough=0.9)
    p = mat.node_tree.nodes["Principled BSDF"]
    p.inputs["Emission Color"].default_value = parts.hexcol(colour)
    p.inputs["Emission Strength"].default_value = strength
    return mat


def press_hall(m):
    """The newspaper's press hall: tall arched windows along the street showing the presses at work inside, offices over
    it, a glazed monitor on the roof, and the paper roll dock at the end where newsprint comes in."""
    paved(m, -0.5, 0.5, -0.5, 0.5, "#8a867e")
    brick = material("printer brick", "#9a5a42", rough=0.9)
    stone = material("printer stone", "#d0c6b2", rough=0.8)
    frame = material("printer frames", "#2e3a34", rough=0.6)
    g = glass()
    x0, x1, y0, y1 = -0.42, 0.42, -0.5, 0.3
    top, gf, deep = 3 * STOREY + 0.2, 1.6, 0.09
    m.box(-0.5, x0, -0.5, 0.5, 0, 0.03, material("sidewalk", "#aaa498", rough=0.9))
    m.box(x0, x1, y0, y1, gf, top, brick, 0.004)
    m.box(x0 + deep, x1, y0, y1, 0, gf, brick)
    houses.foundation(m, x0 + deep, x1, y0, y1, 0.22, stone, out=0.005)
    # The press room seen through the windows: a lit back wall and the presses in a line.
    m.box(x0 + deep - 0.004, x0 + deep, y0 + 0.01, y1 - 0.01, 0.2, gf - 0.05, lit("press room lights", "#f4e2b0", 6.0))
    m.box(x0 + 0.01, x0 + deep, y0, y1, 0, 0.2, material("press room floor", "#5a5650", rough=0.9))
    # The presses stand dark and low against the lit wall, so their outline reads through the windows.
    press = material("press frames", "#2e3e38", metal=0.4, rough=0.5)
    roller = material("press rollers", "#9a9a96", metal=0.8, rough=0.3)
    ink = material("press ink", "#1a1a1c", rough=0.6)
    web = material("paper web", "#f4f0e6", rough=0.7)
    red = material("press guards", "#b03a2a", rough=0.6)
    n = 4
    bays = [y0 + (k + 0.5) * (y1 - y0) / n for k in range(n)]
    for c in bays:
        m.box(x0 + 0.04, x0 + 0.075, c - 0.042, c + 0.042, 0.2, 0.8, press, 0.002)
        for zc in (0.4, 0.62):
            m.tube_x(x0 + 0.032, x0 + 0.04, c, zc, 0.018, roller, segments=16)
            m.tube_x(x0 + 0.028, x0 + 0.032, c, zc, 0.008, ink, segments=10)
        m.box(x0 + 0.036, x0 + 0.04, c - 0.042, c + 0.042, 0.2, 0.3, red)
        m.upright(x0 + 0.058, c, 0.2, 0.34, 0.026, 0.026, web, segments=14)
    # The paper web running from press to press along their tops, and the folder over the middle, dark against the light.
    m.box(x0 + 0.045, x0 + 0.07, y0 + 0.02, y1 - 0.02, 0.8, 0.84, web)
    m.box(x0 + 0.045, x0 + 0.08, -0.16, -0.04, 0.84, 1.15, press, 0.002)
    # The piers between the windows, the wall under them, and arched stone heads.
    for k in range(n + 1):
        a = y0 + k * (y1 - y0) / n
        m.box(x0, x0 + deep, max(y0, a - 0.024), min(y1, a + 0.024), 0, gf, brick)
    m.box(x0, x0 + deep, y0, y1, 0, 0.18, brick)
    houses.on_wall(m, "x", x0, -1, y0, y1, 0, 0.012, 0.18, 0.23, stone)
    w = (y1 - y0) / n - 0.048
    for c in bays:
        inner = houses.arc(c - w / 2, c + w / 2, gf - 0.3, 0.08)
        outer = [(a, z + 0.1) for a, z in houses.arc(c - w / 2 - 0.012, c + w / 2 + 0.012, gf - 0.3, 0.08)]
        m.wedge([(x0, a, z) for a, z in [(c - w / 2, gf), (c + w / 2, gf)] + houses.arc(c + w / 2, c - w / 2, gf - 0.3, 0.08)],
                [(x0 + deep, a, z) for a, z in [(c - w / 2, gf), (c + w / 2, gf)] + houses.arc(c + w / 2, c - w / 2, gf - 0.3, 0.08)], brick)
        houses.arch_band(m, "x", x0, -1, inner, outer, 0, 0.01, stone)
        # Glazing bars across the opening.
        for f in (1 / 3, 2 / 3):
            a = c - w / 2 + w * f
            m.box(x0 + 0.004, x0 + 0.01, a - 0.003, a + 0.003, 0.23, gf - 0.25, frame)
        for z in (0.85, gf - 0.3):
            m.box(x0 + 0.004, x0 + 0.01, c - w / 2, c + w / 2, z - 0.012, z + 0.012, frame)
    # Offices above, over the street and round the sides.
    for s in range(2):
        z = gf + 0.05 + s * STOREY
        houses.on_wall(m, "x", x0, -1, y0, y1, 0, 0.01, z - 0.05, z + 0.02, stone)
        for k in range(n):
            houses.arched_pane(m, "x", x0, -1, bays[k], z + 0.25, z + 0.8, 0.075, g, frame, stone, rise=0.12)
        for k in range(4):
            houses.pane(m, "x", x1, 1, y0 + (k + 0.5) * (y1 - y0) / 4, z + 0.25, z + 0.85, 0.07, g, frame, stone, bars=1)
    for k in range(4):
        houses.pane(m, "x", x1, 1, y0 + (k + 0.5) * (y1 - y0) / 4, 0.45, 1.2, 0.08, g, frame, stone, bars=2)
    houses.on_wall(m, "x", x0, -1, y0, y1, 0, 0.03, top - 0.12, top - 0.04, stone)
    houses.flat_top(m, x0, x1, y0, y1, top, stone, parapet=0.4, open_y=(-1,))
    # A glazed monitor along the roof over the press room.
    roof = material("monitor roof", "#4e5458", metal=0.3, rough=0.6)
    m.box(-0.14, 0.14, y0 + 0.08, y1 - 0.08, top, top + 0.45, brick)
    for x, d in ((-0.14, -1), (0.14, 1)):
        houses.on_wall(m, "x", x, d, y0 + 0.1, y1 - 0.1, 0, 0.006, top + 0.12, top + 0.38, g)
        for k in range(9):
            a = y0 + 0.1 + k * (y1 - y0 - 0.2) / 8
            houses.on_wall(m, "x", x, d, a - 0.003, a + 0.003, 0, 0.009, top + 0.12, top + 0.38, frame)
    gable(m, -0.14, 0.14, y0 + 0.08, y1 - 0.08, top + 0.45, top + 0.65, roof, brick, along_x=True, over=0.015, thick=0.05)
    # The paper roll dock at the end: a raised dock under a canopy, roll-up doors, and newsprint rolls on it.
    dz = 0.42
    m.box(x0 + 0.06, x1, y1, 0.45, 0, dz, material("dock concrete", "#a8a296", rough=0.9), 0.003)
    for x in (-0.12, 0.18):
        harbour.rollup_door(m, x - 0.07, x + 0.07, y1, dz, 1.4, face=1, colour="#5a5e52")
    slab(m, [(x0 + 0.05, y1, 1.75), (x1 + 0.01, y1, 1.75), (x1 + 0.01, 0.48, 1.6), (x0 + 0.05, 0.48, 1.6)], 0.05,
         material("canopy roof", "#5a5e62", metal=0.4, rough=0.5))
    post = material("canopy posts", "#3a3c3e", metal=0.4, rough=0.6)
    for x in (x0 + 0.08, 0.02, x1 - 0.01):
        m.box(x - 0.006, x + 0.006, 0.44, 0.452, dz, 1.62, post)
    paper = material("newsprint rolls", "#e8e4da", rough=0.7)
    wrap = material("roll wrap", "#a08a68", rough=0.8)
    for k, (x, y) in enumerate(((-0.28, 0.35), (-0.23, 0.36), (-0.25, 0.41), (0.02, 0.35), (0.07, 0.4), (0.3, 0.36), (0.35, 0.4))):
        m.upright(x, y, dz, dz + 0.42, 0.021, 0.021, paper, segments=16)
        m.upright(x, y, dz + 0.42, dz + 0.43, 0.021, 0.021, wrap, segments=16)


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
    """A port's terminal elevator, as at Thunder Bay: three rows of slip formed concrete bins with the interstice bins
    filled between them and a gallery along their tops, the tall workhouse at the land end with bands of windows and
    a head house, and the shipping gallery out over the wharf on a steel tower with two loading spouts."""
    ground(m, "#8a847a", "port concrete")
    concrete = material("terminal concrete", "#cfc9bd", rough=0.9)
    shade = material("terminal concrete joints", "#b4aea2", rough=0.9)
    glass_ = glass()
    steel = material("spout steel", "#7a7a76", metal=0.5, rough=0.5)
    clad = material("gallery cladding", "#a8aaa6", metal=0.4, rough=0.55)
    rib = material("gallery ribs", "#8e908c", metal=0.4, rough=0.55)
    r = 0.098
    xs, ys = (-0.4, -0.2, 0.0), (-0.33, -0.11, 0.11, 0.33)
    for x in xs:
        for y in ys:
            m.upright(x, y, 0.02, 6.0, r, r, concrete, segments=24)
            for z in (1.5, 3.0, 4.5):
                m.upright(x, y, z, z + 0.02, r + 0.002, r + 0.002, shade, segments=24)
    m.box(xs[0], xs[-1], ys[0], ys[-1], 0.02, 6.0, concrete)
    m.box(xs[0] - r, xs[-1] + r, ys[0] - 0.02, ys[-1] + 0.02, 5.9, 6.05, concrete)
    # The bin gallery along the tops, clad, with a strip of windows.
    m.box(-0.27, -0.13, -0.45, 0.45, 6.05, 6.5, clad)
    harbour.ribs(m, -0.27, -0.13, -0.45, 6.05, 6.5, rib, gap=0.025, face=-1)
    for x, d in ((-0.27, -1), (-0.13, 1)):
        harbour.ribs_y(m, x, -0.44, 0.44, 6.05, 6.5, rib, gap=0.03, face=d)
        m.box(*sorted((x, x + d * 0.008)), -0.42, 0.42, 6.28, 6.38, glass_)
    houses.gable(m, -0.28, -0.12, -0.46, 0.46, 6.5, 6.6, material("gallery roof", "#5a5c5a", metal=0.4, rough=0.6), clad,
                 along_x=False, over=0.01, thick=0.03)
    # The workhouse at the land end: taller than the bins, bands of windows up it, the head house on top.
    wx0, wx1, wy0, wy1 = 0.12, 0.46, -0.3, 0.3
    m.box(wx0, wx1, wy0, wy1, 0.02, 7.6, concrete)
    # Narrow windows up the stair and the legs, a band of them along the distributing floor at the top.
    for k in range(8):
        z = 1.0 + k * 0.76
        for y, d in ((wy0, -1), (wy1, 1)):
            for x in (0.2, 0.38):
                m.box(x - 0.011, x + 0.011, *sorted((y, y + d * 0.005)), z, z + 0.42, glass_)
        m.box(wx1, wx1 + 0.005, -0.011, 0.011, z, z + 0.42, glass_)
        m.box(wx0 - 0.004, wx1 + 0.004, wy0 - 0.004, wy1 + 0.004, z - 0.2, z - 0.18, shade)
    for y, d in ((wy0, -1), (wy1, 1)):
        for k in range(6):
            x = wx0 + 0.04 + k * (wx1 - wx0 - 0.08) / 5
            m.box(x - 0.02, x + 0.02, *sorted((y, y + d * 0.005)), 7.12, 7.42, glass_)
    for k in range(7):
        y = wy0 + 0.04 + k * (wy1 - wy0 - 0.08) / 6
        m.box(wx1, wx1 + 0.005, y - 0.02, y + 0.02, 7.12, 7.42, glass_)
    for x, y in ((wx0, wy0), (wx1, wy0), (wx0, wy1), (wx1, wy1)):
        m.box(x - 0.012, x + 0.012, y - 0.012, y + 0.012, 0.02, 7.62, concrete)
    m.box(wx0 - 0.01, wx1 + 0.01, wy0 - 0.01, wy1 + 0.01, 7.6, 7.66, shade)
    m.box(0.2, 0.42, -0.14, 0.14, 7.66, 8.4, clad)
    harbour.ribs(m, 0.2, 0.42, -0.14, 7.66, 8.4, rib, gap=0.025, face=-1)
    harbour.ribs(m, 0.2, 0.42, 0.14, 7.66, 8.4, rib, gap=0.025, face=1)
    m.box(0.19, 0.43, -0.15, 0.15, 8.4, 8.44, material("gallery roof", "#5a5c5a", metal=0.4, rough=0.6))
    m.box(0.12, 0.2, -0.1, 0.1, 0.02, 0.9, concrete)
    harbour.rollup_door(m, 0.2, 0.36, wy1, 0.02, 0.85, face=1)
    # The shipping gallery out over the wharf on a steel tower, and two spouts swung down toward a ship's hatches.
    m.box(-0.9, -0.27, -0.05, 0.05, 6.15, 6.45, clad)
    harbour.ribs(m, -0.9, -0.27, -0.05, 6.15, 6.45, rib, gap=0.03, face=-1)
    harbour.ribs(m, -0.9, -0.27, 0.05, 6.15, 6.45, rib, gap=0.03, face=1)
    m.box(-0.91, -0.27, -0.06, 0.06, 6.45, 6.49, material("gallery roof", "#5a5c5a", metal=0.4, rough=0.6))
    tower_ = material("gallery tower", "#6a6c6a", metal=0.5, rough=0.5)
    for x in (-0.66, -0.58):
        for y in (-0.05, 0.05):
            m.rod((x, y, 0.0), (x, y, 6.15), 0.006, tower_)
    for k in range(6):
        z0, z1 = k * 1.0, (k + 1) * 1.0
        m.rod((-0.66, -0.05, z0), (-0.58, -0.05, z1), 0.003, tower_, segments=5)
        m.rod((-0.66, 0.05, z1), (-0.58, 0.05, z0), 0.003, tower_, segments=5)
    for y in (-0.03, 0.03):
        m.rod((-0.86, y, 6.15), (-0.92, y * 2, 3.2), 0.009, steel)
        m.upright(-0.92, y * 2, 3.05, 3.2, 0.014, 0.011, steel, segments=10)
    m.rod((-0.86, 0.0, 6.2), (-0.86, 0.0, 6.0), 0.012, steel)


def warehouse(m):
    """A brick port warehouse: pilasters and arched windows down its sides, sliding freight doors to the wharf, a
    loading platform with a canopy on brackets along the back, ventilators on the ridge, cargo on the platform."""
    ground(m, "#8a847a", "port concrete")
    brick = material("warehouse brick", "#9a5a42", rough=0.85)
    stone = material("warehouse stone", "#cfc6b2", rough=0.85)
    roof = material("warehouse roof", "#4e4c48", metal=0.3, rough=0.6)
    door = material("warehouse doors", "#4a5a52", rough=0.65)
    trim = material("warehouse trim", "#2e3a34", rough=0.6)
    glass_ = glass()
    x0, x1, y0, y1, eave = -0.42, 0.3, -0.4, 0.38, 2.2
    m.box(x0, x1, y0, y1, 0.0, eave, brick)
    m.box(x0 - 0.005, x1 + 0.005, y0 - 0.005, y1 + 0.005, 0.0, 0.12, stone)
    gable(m, x0, x1, y0, y1, eave, 3.0, roof, brick, along_x=False, over=0.025, thick=0.06)
    # Pilasters and a corbelled band, arched windows between, on both gable ends (y0 and y1), a round vent in each gable.
    for y, d in ((y0, -1), (y1, 1)):
        for k in range(6):
            x = x0 + 0.01 + k * (x1 - x0 - 0.02) / 5
            m.box(x - 0.014, x + 0.014, *sorted((y, y + d * 0.014)), 0.0, eave, brick)
        m.box(x0, x1, *sorted((y, y + d * 0.02)), eave - 0.12, eave - 0.04, brick)
        for k in range(5):
            c = x0 + 0.01 + (k + 0.5) * (x1 - x0 - 0.02) / 5
            houses.arched_pane(m, "y", y, d, c, 0.55, 0.95, 0.06, glass_, trim, stone, rise=0.06, keystone=False)
            houses.arched_pane(m, "y", y, d, c, 1.4, 1.8, 0.06, glass_, trim, stone, rise=0.06, keystone=False)
        m.tube(*sorted((y, y + d * 0.008)), (x0 + x1) / 2, 2.55, 0.05, stone, segments=20)
        m.tube(*sorted((y, y + d * 0.012)), (x0 + x1) / 2, 2.55, 0.036, material("gable louvre", "#3a3a3a", rough=0.7), segments=20)
    # Freight doors to the wharf end (-x) on the gable, and on the platform side, the platform and canopy (+x).
    for k, c in enumerate((-0.2, 0.18)):
        m.box(x0 - 0.008, x0 - 0.002, c - 0.1, c + 0.1, 0.0, 1.25, door)
        for j in range(3):
            yy = c - 0.1 + (j + 0.5) * 0.2 / 3
            m.rod((x0 - 0.009, yy - 0.025, 0.05), (x0 - 0.009, yy + 0.025, 1.2), 0.002, trim, segments=4)
        m.box(x0 - 0.012, x0 - 0.002, c - 0.12, c + 0.12, 1.25, 1.31, trim)
    m.box(x0 - 0.016, x0 - 0.004, -0.38, 0.38, 1.33, 1.36, trim)
    houses.sign_text(m, "HARBOUR STORES", "x", x0, -1, 0.0, 1.9, 0.07, material("painted letters", "#ece6d4", rough=0.7))
    # The loading platform along the back.
    harbour.planks(m, x1, 0.48, y0 + 0.02, y1 - 0.02, 0.3, 0.35, along_x=True, width=0.03, seed=7)
    m.box(x1, 0.475, y0 + 0.02, y1 - 0.02, 0.0, 0.3, material("platform face", "#5a4a3a", rough=0.9))
    for c in (-0.24, 0.0, 0.24):
        m.box(x1 + 0.002, x1 + 0.008, c - 0.08, c + 0.08, 0.35, 1.35, door)
        m.box(x1 + 0.004, x1 + 0.01, c - 0.09, c + 0.09, 1.35, 1.4, trim)
    canopy = [(x1, y0, 1.72), (0.5, y0, 1.6), (0.5, y1, 1.6), (x1, y1, 1.72)]
    slab(m, canopy, 0.05, roof)
    for y in (y0 + 0.06, 0.0, y1 - 0.06):
        m.rod((x1 + 0.01, y, 1.3), (0.43, y, 1.64), 0.005, trim, segments=6)
    for y in (-0.28, 0.1):
        harbour.pallet(m, 0.4, y, 0.35, size=0.1, load="cartons")
    harbour.crate(m, 0.36, 0.45, 0.18, 0.28, 0.35, 0.62, "#a8885a")
    for k in range(3):
        harbour.drum(m, 0.36 + k * 0.04, -0.12, 0.35, colour="#2a4a7a", r=0.016, h=0.3)
    for y in (-0.25, 0.0, 0.25):
        roof_vent(m, (x0 + x1) / 2, y, 3.0, roof, r=0.025)


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
    """A Maritime fish store: weathered shingles with red trim, double doors and a loft door with its hoist beam in the
    gable toward the wharf, buoys hung on the side, a lean-to bait shed, traps, totes, a net heap and drums."""
    ground(m, "#6a6660", "harbour gravel")
    wood = material("fish shed shingles", "#9a9080", rough=0.9)
    course = material("shingle courses", "#7e7466", rough=0.9)
    trim = material("shed trim", "#c84a3a", rough=0.7)
    roof = material("fish shed roof", "#4a4642", rough=0.85)
    glass_ = glass()
    x0, x1, y0, y1, eave = -0.32, 0.2, -0.24, 0.22, 1.5
    m.box(x0, x1, y0, y1, 0.0, eave, wood)
    for k in range(1, 19):
        z = k * 0.08
        m.box(x0 - 0.003, x1 + 0.003, y0 - 0.003, y1 + 0.003, z, z + 0.01, course)
    gable(m, x0, x1, y0, y1, eave, 2.3, roof, wood, along_x=True, over=0.025, thick=0.06)
    for x, y in ((x0, y0), (x1, y0), (x0, y1), (x1, y1)):
        m.box(x - 0.009, x + 0.009, y - 0.009, y + 0.009, 0.0, eave, trim)
    # The wharf end (-x): double doors, one swung open, the loft door over them and a hoist beam with its block.
    dark = material("shed inside", "#1e1a16", rough=1.0)
    m.box(x0 - 0.004, x0, -0.1, 0.1, 0.0, 1.1, dark)
    m.box(x0 - 0.008, x0 - 0.002, 0.0, 0.1, 0.0, 1.1, trim)
    m.wedge([(x0 - 0.002, -0.1, 0.0), (x0 - 0.008, -0.1, 0.0), (x0 - 0.1 - 0.006, -0.1 - 0.035, 0.0), (x0 - 0.1, -0.1 - 0.035, 0.0)],
            [(x0 - 0.002, -0.1, 1.1), (x0 - 0.008, -0.1, 1.1), (x0 - 0.1 - 0.006, -0.1 - 0.035, 1.1), (x0 - 0.1, -0.1 - 0.035, 1.1)], trim)
    m.box(x0 - 0.012, x0 - 0.002, -0.115, 0.115, 1.1, 1.15, trim)
    m.box(x0 - 0.008, x0 - 0.002, -0.06, 0.06, 1.62, 1.98, material("loft door", "#7a3a2e", rough=0.7))
    m.box(x0 - 0.012, x0 - 0.002, -0.07, 0.07, 1.98, 2.02, trim)
    m.box(x0 - 0.16, x0 + 0.05, -0.012, 0.012, 2.12, 2.17, material("hoist beam", "#5a4632", rough=0.9))
    m.rod((x0 - 0.14, 0.0, 2.12), (x0 - 0.14, 0.0, 1.5), 0.0018, material("hoist rope", "#c8b890", rough=0.9), segments=4)
    m.box(x0 - 0.15, x0 - 0.13, -0.008, 0.008, 1.45, 1.52, material("hoist block", "#3a3a3c", metal=0.4, rough=0.5))
    # The side (+y): a door, windows and a row of buoys hung on the wall.
    m.box(0.06, 0.13, y1 + 0.002, y1 + 0.008, 0.0, 0.82, material("shed door", "#3e6a8a", rough=0.6))
    m.box(0.055, 0.135, y1 + 0.004, y1 + 0.01, 0.82, 0.86, trim)
    for c in (-0.22, -0.06):
        houses.pane(m, "y", y1, 1, c, 0.6, 0.95, 0.07, glass_, trim, trim, bars=1)
    for k in range(7):
        harbour.buoy(m, -0.27 + k * 0.045, y1 + 0.016, 1.12, colour=("#e84a2a", "#f0c020", "#2a8a4a", "#ecebe6")[k % 4], r=0.012)
    houses.pane(m, "y", y0, -1, -0.1, 0.6, 0.95, 0.07, glass_, trim, trim, bars=1)
    # The bait shed lean-to on the land end, its stovepipe.
    bx0, bx1, by0, by1 = x1, 0.4, -0.2, 0.12
    m.wedge([(bx0, by0, 0.0), (bx1, by0, 0.0), (bx1, by1, 0.0), (bx0, by1, 0.0)],
            [(bx0, by0, 1.05), (bx1, by0, 0.82), (bx1, by1, 0.82), (bx0, by1, 1.05)], wood)
    slab(m, [(bx0, by0 - 0.02, 1.06), (bx1 + 0.02, by0 - 0.02, 0.8), (bx1 + 0.02, by1 + 0.02, 0.8), (bx0, by1 + 0.02, 1.06)], 0.04, roof)
    m.box(bx1, bx1 + 0.006, -0.08, 0.0, 0.0, 0.72, material("bait shed door", "#3e6a8a", rough=0.6))
    pipe = material("stovepipe", "#4a4a4c", metal=0.6, rough=0.4)
    m.upright(0.33, 0.06, 0.8, 1.35, 0.008, 0.008, pipe, segments=8)
    m.lathe(0.33, 0.06, 1.35, [(0.0, 0), (0.02, 0.04), (0.0, 0.07)], pipe, segments=10)
    # Gear round about: traps stacked, a net heap, totes and drums.
    harbour.trap_stack(m, -0.36, -0.44, 0.02, rows=2, cols=5, layers=2, seed=8)
    harbour.trap_stack(m, 0.12, -0.44, 0.02, rows=2, cols=3, layers=3, seed=9)
    net = material("fishing net", "#2a6a5a", rough=1.0)
    m.lathe(0.32, 0.32, 0.02, [(0.08, 0), (0.08, 0.04), (0.06, 0.1), (0.025, 0.14), (0.0, 0.145)], net, segments=16, oval=0.7)
    for k in range(4):
        m.box(-0.44, -0.36, 0.3, 0.37, 0.02 + k * 0.07, 0.02 + (k + 1) * 0.07 - 0.005, material("fish totes", "#3a7aaa", rough=0.6), 0.003)
    for k in range(3):
        harbour.drum(m, 0.36, 0.18 + k * 0.045, 0.02, colour="#b8322a", r=0.018, h=0.32)


def boat_shed(m):
    """A red boat shed with white trim: board and batten walls, big doors at the water end (-x) opening onto a marine
    railway down to the shore, windows down its side, a cupola, and a dory on sawhorses with lumber stacked by it."""
    ground(m, "#6a6660", "harbour gravel")
    wood = material("boat shed boards", "#b8402e", rough=0.85)
    batten = material("boat shed battens", "#9a3424", rough=0.85)
    white = material("boat shed trim", "#ecebe4", rough=0.6)
    roof = material("boat shed roof", "#3e3c3a", rough=0.85)
    glass_ = glass()
    x0, x1, y0, y1, eave = -0.36, 0.32, -0.3, 0.2, 1.6
    m.box(x0, x1, y0, y1, 0.0, eave, wood)
    harbour.ribs(m, x0, x1, y0, 0.0, eave, batten, gap=0.03, face=-1)
    harbour.ribs(m, x0, x1, y1, 0.0, eave, batten, gap=0.03, face=1)
    harbour.ribs_y(m, x1, y0, y1, 0.0, eave, batten, gap=0.03, face=1)
    gable(m, x0, x1, y0, y1, eave, 2.5, roof, wood, along_x=True, over=0.03, thick=0.06, trim=white)
    for x, y in ((x0, y0), (x1, y0), (x0, y1), (x1, y1)):
        m.box(x - 0.009, x + 0.009, y - 0.009, y + 0.009, 0.0, eave, white)
    # Big doors at the water end, both folded back, the dark shed and a boat's bow inside.
    dark = material("shed inside", "#1e1a16", rough=1.0)
    m.box(x0 - 0.004, x0 + 0.02, -0.17, 0.07, 0.0, 1.35, dark)
    for s, yh in ((-1, -0.17), (1, 0.07)):
        yo = yh + s * 0.11
        m.wedge([(x0 - 0.002, yh, 0.0), (x0 - 0.009, yh, 0.0), (x0 - 0.06, yo, 0.0), (x0 - 0.053, yo, 0.0)],
                [(x0 - 0.002, yh, 1.33), (x0 - 0.009, yh, 1.33), (x0 - 0.06, yo, 1.33), (x0 - 0.053, yo, 1.33)], white)
    m.box(x0 - 0.012, x0 - 0.002, -0.19, 0.09, 1.35, 1.42, white)
    m.box(x0 - 0.01, x0 - 0.002, -0.07, -0.03, 1.7, 1.98, white)
    hull = material("boat in the shed", "#e8e2d8", rough=0.5)
    m.wedge([(x0 + 0.03, -0.05, 0.25), (x0 + 0.03, -0.05, 0.25), (x0 + 0.2, -0.12, 0.25), (x0 + 0.2, 0.02, 0.25)],
            [(x0 + 0.0, -0.05, 0.75), (x0 + 0.0, -0.05, 0.75), (x0 + 0.2, -0.14, 0.7), (x0 + 0.2, 0.04, 0.7)], hull)
    # The marine railway from the doors down to the shore: ties, two rails and a cradle.
    tie = material("railway ties", "#4a3a2c", rough=0.95)
    rail = material("railway rails", "#5a5a5c", metal=0.6, rough=0.45)
    for k in range(6):
        x = x0 - 0.02 - k * 0.025
        m.box(x - 0.007, x + 0.007, -0.11, 0.01, 0.0, 0.03, tie)
    for y in (-0.08, -0.02):
        m.box(-0.5, x0 + 0.1, y - 0.004, y + 0.004, 0.03, 0.05, rail)
    m.box(x0 + 0.03, x0 + 0.14, -0.1, 0.0, 0.05, 0.2, material("cradle", "#5a4632", rough=0.9))
    # Windows down the side and on the land end, a door, the cupola on the ridge.
    for c in (-0.24, -0.06, 0.12):
        houses.pane(m, "y", y1, 1, c, 0.65, 1.05, 0.08, glass_, white, white, bars=1)
        houses.pane(m, "y", y0, -1, c, 0.65, 1.05, 0.08, glass_, white, white, bars=1)
    m.box(x1 + 0.002, x1 + 0.008, -0.04, 0.04, 0.0, 0.85, white)
    houses.pane(m, "x", x1, 1, 0.0, 1.7, 2.0, 0.07, glass_, white, white)
    m.box(-0.04, 0.04, -0.09, -0.01, 2.34, 2.6, white)
    houses.gable(m, -0.04, 0.04, -0.09, -0.01, 2.6, 2.7, roof, white, along_x=False, over=0.012, thick=0.025)
    for x in (-0.04, 0.04):
        m.box(x - 0.002, x + 0.002, -0.08, -0.02, 2.4, 2.55, batten)
    # The dory on sawhorses by the side, and a pile of lumber.
    horse = material("sawhorses", "#a08460", rough=0.9)
    for y in (-0.42, -0.34):
        for s in (-1, 1):
            m.rod((0.08 + s * 0.04, y, 0.0), (0.08, y, 0.22), 0.004, horse, segments=5)
            m.rod((0.32 + s * 0.04, y, 0.0), (0.32, y, 0.22), 0.004, horse, segments=5)
        m.rod((0.06, y, 0.22), (0.34, y, 0.22), 0.005, horse, segments=5)
    dory = material("dory", "#e8c84a", rough=0.6)
    sections = []
    for k in range(7):
        t = k / 6
        x = 0.04 + t * 0.32
        hw = 0.012 + 0.05 * math.sin(math.pi * t)
        bw = 0.006 + 0.022 * math.sin(math.pi * t)
        sheer = 0.42 + 0.1 * (2 * t - 1) ** 2
        sections.append((x, [(-bw, 0.24), (bw, 0.24), (hw, sheer), (-hw, sheer)]))
    for (xa, ra), (xb, rb) in zip(sections, sections[1:]):
        m.wedge([(xa, -0.38 + px, pz) for px, pz in ra], [(xb, -0.38 + px, pz) for px, pz in rb], dory)
    for k in range(4):
        m.box(0.36, 0.48, -0.1, 0.1, k * 0.04, k * 0.04 + 0.035, material(f"lumber {k}", ("#c8a878", "#b89868")[k % 2], rough=0.9))


def cannery(m):
    """A British Columbia cannery: a long white board and batten hall with a red roof and a net loft under it, raised
    on piles with a plank walk along the water side, a lower canning line wing, a brick boiler house with its stack,
    a ventilator on the ridge and the company's name along the wall."""
    white = material("cannery siding", "#e6e0d2", rough=0.8)
    batten = material("cannery battens", "#cec8b8", rough=0.8)
    red = material("cannery roof", "#8a3a2a", rough=0.8)
    trim = material("cannery trim", "#3e5a6a", rough=0.7)
    glass_ = glass()
    ground(m, "#6e6a62", "cannery gravel")
    deck = 0.3
    x0, x1, y0, y1, eave = -0.44, 0.22, -0.36, 0.22, 2.4
    # Piles under the floor and the plank walk round the water side.
    harbour.piles(m, [x0 - 0.04, x0 + 0.12, 0.0, 0.14], [-0.38, -0.12, 0.12, 0.3], 0.0, deck - 0.03, r=0.014, across_x=True)
    harbour.planks(m, x0 - 0.06, x1, y0 - 0.04, 0.34, deck - 0.04, deck, along_x=False, width=0.03, seed=5)
    m.box(x0, x1, y0, y1, deck, eave, white)
    harbour.ribs(m, x0, x1, y0, deck, eave, batten, gap=0.035, face=-1)
    harbour.ribs(m, x0, x1, y1, deck, eave, batten, gap=0.035, face=1)
    harbour.ribs_y(m, x0, y0, y1, deck, eave, batten, gap=0.035, face=-1)
    gable(m, x0, x1, y0, y1, eave, 3.3, red, white, along_x=True, over=0.03, thick=0.07)
    # Two rows of windows, the lower the cannery floor, the upper the net loft.
    for y, d in ((y0, -1), (y1, 1)):
        for k in range(6):
            c = x0 + 0.06 + k * 0.11
            houses.pane(m, "y", y, d, c, 0.75, 1.2, 0.06, glass_, trim, trim)
            houses.pane(m, "y", y, d, c, 1.75, 2.1, 0.05, glass_, trim, trim, sash=False)
    houses.pane(m, "x", x0, -1, -0.07, 2.55, 2.85, 0.07, glass_, trim, trim)
    m.box(x0 - 0.008, x0 - 0.002, 0.02, 0.16, deck, deck + 0.75, material("cannery doors", "#3e5a6a", rough=0.6))
    m.box(x0 - 0.008, x0 - 0.002, -0.3, -0.16, deck, deck + 0.75, material("cannery doors", "#3e5a6a", rough=0.6))
    # The ventilator on the ridge.
    m.box(-0.18, -0.04, -0.04, 0.04, 3.2, 3.5, white)
    gable(m, -0.18, -0.04, -0.04, 0.04, 3.5, 3.62, red, white, along_x=True, over=0.012, thick=0.03)
    for x in (-0.16, -0.13, -0.1, -0.07):
        for y in (-0.042, 0.042):
            m.box(x - 0.008, x + 0.008, y - 0.002, y + 0.002, 3.26, 3.45, trim)
    # The canning line wing along the land side, lower, with its own windows and a loading door.
    wy0, wy1 = y1, 0.46
    m.box(x0 + 0.06, x1, wy0, wy1, deck, 1.45, white)
    harbour.ribs(m, x0 + 0.06, x1, wy1, deck, 1.45, batten, gap=0.035, face=1)
    slab(m, [(x0 + 0.04, wy0, 1.75), (x1 + 0.02, wy0, 1.75), (x1 + 0.02, wy1 + 0.03, 1.42), (x0 + 0.04, wy1 + 0.03, 1.42)], 0.05, red)
    for k in range(4):
        c = x0 + 0.13 + k * 0.13
        houses.pane(m, "y", wy1, 1, c, 0.7, 1.1, 0.07, glass_, trim, trim)
    m.box(x0 + 0.08, x1 - 0.06, y0 - 0.012, y0 - 0.004, 1.32, 1.58, white)
    houses.sign_text(m, "CANNING CO", "y", y0 - 0.012, -1, (x0 + x1) / 2 + 0.01, 1.45, 0.055, trim)
    # The boiler house and its stack at the land end.
    brick = material("boiler house brick", "#8a4a36", rough=0.85)
    bx0, bx1, by0, by1 = 0.25, 0.46, -0.42, -0.14
    m.box(bx0, bx1, by0, by1, 0.0, 1.4, brick)
    gable(m, bx0, bx1, by0, by1, 1.4, 1.75, red, brick, along_x=False, over=0.02, thick=0.05)
    m.box(bx1 + 0.002, bx1 + 0.008, -0.33, -0.23, 0.0, 0.8, material("boiler house door", "#3a3a3c", metal=0.3, rough=0.6))
    stack(m, 0.36, -0.3, 1.5, 5.0, 0.035, material("cannery stack", "#5a5a5a", metal=0.4, rough=0.5),
          band=material("stack band", "#3a3a3a", metal=0.4, rough=0.5))
    for z in (2.6, 3.8):
        for a in (0.5, 2.6, 4.4):
            m.rod((0.36, -0.3, z), (0.36 + 0.14 * math.cos(a), -0.3 + 0.14 * math.sin(a), 0.0), 0.0012,
                  material("guy wire", "#2a2a2a", metal=0.5, rough=0.5), segments=3)


# ---- potash, nickel and aluminium ----------------------------------------------------------------

def potash_mill(m):
    """A Saskatchewan potash mill: the tall metal-clad mill with the headframe's gallery coming in (-y), the A-frame
    storage building with its conveyor along the ridge, and the loadout over the track."""
    ground(m, "#8a7a6a", "mine gravel")
    wall = material("potash mill siding", "#d4cec0", metal=0.3, rough=0.6)
    rib = material("potash mill ribs", "#bcb6a8", metal=0.3, rough=0.6)
    red = material("potash trim", "#8a3a2a", rough=0.8)
    groof = material("gallery roof", "#4e5458", metal=0.3, rough=0.6)
    frame = material("mill steel", "#4a4c4e", metal=0.5, rough=0.5)
    g = glass()
    x0, x1, y0, y1 = -0.08, 0.44, -0.3, 0.14
    clad_box(m, x0, x1, y0, y1, 0, 4.4, wall, rib, gap=0.03)
    houses.flat_top(m, x0, x1, y0, y1, 4.4, red, parapet=0.12)
    ux0, uy1 = 0.16, -0.04
    clad_box(m, ux0, x1, y0, uy1, 4.4, 6.0, wall, rib, gap=0.03)
    houses.flat_top(m, ux0, x1, y0, uy1, 6.0, red, parapet=0.12)
    for z in (1.2, 2.3, 3.4):
        for face, at, d, a0, a1 in (("y", y1, 1, x0 + 0.03, x1 - 0.03), ("x", x1, 1, y0 + 0.03, y1 - 0.03), ("x", x0, -1, y0 + 0.03, y1 - 0.03)):
            houses.on_wall(m, face, at, d, a0, a1, 0, 0.008, z, z + 0.22, g)
    for face, at, d, a0, a1 in (("x", x1, 1, y0 + 0.03, uy1 - 0.03), ("y", uy1, 1, ux0 + 0.03, x1 - 0.03)):
        houses.on_wall(m, face, at, d, a0, a1, 0, 0.008, 5.1, 5.35, g)
    houses.on_wall(m, "y", y1, 1, x0, x1, 0, 0.01, 0.0, 0.18, red)
    harbour.rollup_door(m, 0.26, 0.38, y1, 0.0, 0.85, face=1)
    vent = material("vent", "#6a6a66", metal=0.5, rough=0.5)
    for x in (0.0, 0.1):
        m.box(x - 0.03, x + 0.03, 0.0, 0.08, 4.46, 4.7, vent, 0.004)
    m.upright(0.38, -0.2, 6.06, 6.5, 0.022, 0.02, material("dryer stack", "#9a9890", metal=0.5, rough=0.5), segments=12)
    # The gallery in from the headframe on a bent.
    head_conveyor(m, -0.5, y0, frame, material("conveyor belt", "#2e2a26", rough=0.8), wall=wall, roof=groof, beyond=True)
    bent(m, HEAD_X, -0.42, head_z(-0.42, True) - 0.01, 0.1, frame)
    # The A-frame storage building, its roof down to a low concrete wall, the conveyor along its ridge.
    sx0, sx1, sy0, sy1, ridge = -0.47, -0.13, -0.47, 0.22, 2.5
    sc = (sx0 + sx1) / 2
    sroof = material("storage roof", "#c4c0b4", metal=0.3, rough=0.6)
    knee = material("storage wall", "#a8a49a", rough=0.95)
    seam = material("storage seams", "#8a867c", metal=0.3, rough=0.6)
    m.box(sx0, sx1, sy0, sy1, 0, 0.18, knee)
    for xa in (sx0, sx1):
        slab(m, [(xa, sy0, 0.18), (xa, sy1, 0.18), (sc, sy1, ridge), (sc, sy0, ridge)], 0.06, sroof)
        y = sy0 + 0.04
        while y < sy1 - 0.02:
            m.rod((xa + (0.004 if xa < sc else -0.004), y, 0.24), (sc, y, ridge + 0.06), 0.003, seam, segments=4)
            y += 0.06
    for ya in (sy0, sy1):
        tri = [(sx0, ya, 0.18), (sx1, ya, 0.18), (sc, ya, ridge)]
        m.wedge(tri, [(x, ya + 0.008, z) for x, _, z in tri], knee)
    m.box(sc - 0.025, sc + 0.025, sy0, sy1, ridge, ridge + 0.25, wall)
    gable(m, sc - 0.025, sc + 0.025, sy0, sy1, ridge + 0.25, ridge + 0.32, groof, wall, along_x=False, over=0.008, thick=0.03)
    gallery(m, (x0, -0.12, 3.2), (sc + 0.025, -0.12, ridge + 0.05), 0.06, 0.32, wall, groof, g, along_y=False)
    # The loadout over the track and the gallery out to it.
    ty = 0.36
    siding(m, -0.12, 0.5, ty)
    lx0, lx1 = 0.02, 0.22
    clad_box(m, lx0, lx1, ty - 0.1, ty + 0.1, 1.9, 2.6, wall, rib, gap=0.03)
    houses.flat_top(m, lx0, lx1, ty - 0.1, ty + 0.1, 2.6, red, parapet=0.08)
    for x in (lx0 + 0.01, lx1 - 0.01):
        for y in (ty - 0.15, ty + 0.13):
            m.box(x - 0.006, x + 0.006, y - 0.006, y + 0.006, 0, 1.9, frame)
        m.box(x - 0.004, x + 0.004, ty - 0.15, ty + 0.13, 1.84, 1.9, frame)
    m.upright(0.12, ty, 1.55, 1.9, 0.012, 0.03, frame, segments=10)
    gallery(m, (0.12, y1, 2.9), (0.12, ty - 0.1, 2.3), 0.06, 0.3, wall, groof, g)


def tailings(m, look=0):
    """The salt tailings pile: benched sides with a dump road winding up to its flat top, a dozer working there."""
    ground(m, "#8a7a6a", "mine gravel")
    pile = Benches(0.0, 0.0, 0.48, 0.48, [(0.34, 1.6), (0.53, 1.22), (0.71, 0.84), (0.88, 0.44), (9.0, 0.0)], seed=4.0 + look,
                   road=(math.pi * 0.3, 1.05, 0.97, 0.3, 0.04, 1.6, 0.06), rough=0.012, face=0.04)
    mats = [material("salt tailings", "#d8c4bc", rough=1.0), material("salt tailings face", "#c4aaa2", rough=1.0),
            material("salt tailings crust", "#e2d6cc", rough=1.0), material("tailings road", "#b4a49a", rough=1.0)]
    terrain(m, pile.height, mats, pile.pick(9.0))
    _dozer(m, -0.04, 0.06, 1.6)


def _dozer(m, x, y, z, turn=0.6):
    """A small crawler dozer at x, y, z steps up, turned by turn radians."""
    def build(mm):
        paint = material("dozer yellow", "#d8a428", metal=0.3, rough=0.5)
        dark = material("dozer dark", "#2e2e30", metal=0.4, rough=0.6)
        for s in (-1, 1):
            mm.box(s * 0.024 - 0.008, s * 0.024 + 0.008, -0.035, 0.035, 0, 0.13, dark, 0.004)
        mm.box(-0.016, 0.016, -0.03, 0.03, 0.1, 0.26, paint, 0.003)
        mm.box(-0.014, 0.014, -0.03, 0.0, 0.26, 0.42, paint, 0.003)
        mm.box(-0.012, 0.012, -0.028, -0.002, 0.3, 0.4, glass())
        mm.box(-0.036, 0.036, 0.045, 0.052, 0.0, 0.16, paint)
        for s in (-1, 1):
            mm.rod((s * 0.03, 0.0, 0.08), (s * 0.03, 0.047, 0.06), 0.0025, dark, segments=4)
    moved(m, build, x, y, turn, z=z)


def rock_dump(m):
    """The waste rock dump: benched grey rock with a dump road up it, and the headframe's conveyor carried out over it
    to a head pulley that drops the rock on a cone."""
    ground(m, "#4a4644", "mine rock")
    dump = Benches(-0.04, 0.08, 0.46, 0.42, [(0.3, 2.0), (0.5, 1.5), (0.7, 1.0), (0.88, 0.5), (9.0, 0.0)], seed=6.0,
                   road=(-math.pi * 0.8, -1.0, 0.98, 0.28, 0.04, 2.0, 0.065), rough=0.02, face=0.06)
    mats = [material("waste rock", "#6a6a66", rough=1.0), material("waste rock face", "#5a5a56", rough=1.0),
            material("waste rock rust", "#7a5a48", rough=1.0), material("dump road", "#8a8680", rough=1.0)]
    terrain(m, dump.height, mats, dump.pick(9.0))
    frame = material("mill steel", "#4a4c4e", metal=0.5, rough=0.5)
    end = (HEAD_X, -0.26, HEAD_Z + 0.25)
    conveyor(m, (HEAD_X, -0.5, HEAD_Z), end, 0.06, frame, material("conveyor belt", "#2e2a26", rough=0.8), legs=0,
             load=material("rock on belt", "#5a5a56", rough=1.0))
    bent(m, HEAD_X, -0.44, HEAD_Z - 0.06, 0.1, frame)
    bent(m, HEAD_X, -0.3, HEAD_Z + 0.15, 0.1, frame)
    m.tube_x(HEAD_X - 0.035, HEAD_X + 0.035, end[1], end[2] - 0.02, 0.016, frame, segments=12)
    heap(m, HEAD_X, -0.22, 0.2, 1.7, "#5e5e5a", 97, "waste rock")


def nickel_heap(m):
    """The ore stockpile: a dozer pushing the pile to the reclaim tunnel at its foot, the conveyor out of it climbing to
    a steel loadout bin over a siding, and a hopper car under the bin."""
    ground(m, "#4a4644", "mine rock")
    heap(m, -0.1, -0.16, 0.3, 1.6, "#5a6050", 96, "nickel ore")
    heap(m, 0.24, -0.28, 0.14, 0.6, "#525848", 98, "nickel ore")
    concrete = material("pocket concrete", "#9a958c", rough=0.95)
    steel = material("bin steel", "#6a6662", metal=0.4, rough=0.6)
    trim = material("mill steel", "#4a4c4e", metal=0.5, rough=0.5)
    # The reclaim tunnel's portal at the pile's foot.
    m.box(-0.42, -0.28, 0.08, 0.16, 0, 0.42, concrete)
    m.box(-0.39, -0.31, 0.16, 0.161, 0.02, 0.3, material("pocket dark", "#1e1c1a", rough=1.0))
    ty = 0.34
    siding(m, -0.5, 0.5, ty)
    a, b = (-0.35, 0.16, 0.3), (0.16, ty, 2.45)
    conveyor(m, a, b, 0.05, trim, material("conveyor belt", "#2e2a26", rough=0.8), legs=0.14,
             hood=material("crusher roof", "#4a4a48", metal=0.4, rough=0.6), load=material("ore on belt", "#5a6050", rough=1.0))
    # The loadout bin on four legs over the track, its hopper and chute.
    bx0, bx1, by0, by1, b0, b1 = 0.14, 0.38, ty - 0.1, ty + 0.1, 1.7, 2.5
    m.box(bx0, bx1, by0, by1, b0, b1, steel, 0.003)
    m.box(bx0 - 0.006, bx1 + 0.006, by0 - 0.006, by1 + 0.006, b1, b1 + 0.05, trim)
    for x in (bx0 + 0.006, bx1 - 0.006):
        for y in (by0 + 0.006, by1 - 0.006):
            m.box(x - 0.006, x + 0.006, y - 0.006, y + 0.006, 0, b0, trim)
    cx = (bx0 + bx1) / 2
    m.wedge([(cx - 0.02, ty - 0.02, 1.3), (cx + 0.02, ty - 0.02, 1.3), (cx + 0.02, ty + 0.02, 1.3), (cx - 0.02, ty + 0.02, 1.3)],
            [(bx0, by0, b0), (bx1, by0, b0), (bx1, by1, b0), (bx0, by1, b0)], steel)
    for xx in (bx0 + 0.006, bx1 - 0.006):
        m.rod((xx, by0, 0.2), (xx, by0, b0 - 0.1), 0.0025, trim, segments=4)
    m.rod((bx0 + 0.006, by0, 0.2), (bx1 - 0.006, by0, b0 - 0.1), 0.0025, trim, segments=4)
    moved(m, lambda mm: cars.hopper(mm, 9 / 16), cx, ty, -math.pi / 2, z=0.12)
    _dozer(m, -0.2, -0.02, 0.45, turn=0.8)
    _light_pole(m, -0.44, 0.44)


def smelter_flue(m, y0, brick, trim):
    """The brick flue from the roaster (+y) to the stack, meeting the roaster's own at the tile edge."""
    fx0, fx1 = -0.36, 0.0
    m.box(fx0, fx1, y0, 0.5, 0, 0.8, brick)
    gable(m, fx0, fx1, y0, 0.5, 0.8, 0.95, brick, brick, along_x=False, over=0.0, thick=0.04)
    for y in (0.22, 0.4):
        m.box(fx0 - 0.008, fx1 + 0.008, y - 0.01, y + 0.01, 0, 0.85, trim)


def superstack(m, brick_stacks=False):
    """A smelter's tall concrete stack from 1972, a landmark from miles off, or before that the three brick stacks Copper
    Cliff had, all fed by the brick flue from the roaster."""
    ground(m, "#4a4644", "smelter yard")
    brick = material("flue brick", "#6e3e30", rough=0.9)
    trim = material("roaster trim", "#3a3c3e", metal=0.3, rough=0.6)
    if not brick_stacks:
        smelter_flue(m, 0.08, brick, trim)
        concrete = material("superstack", "#bcb6aa", rough=0.9)
        m.lathe(0, 0, 0, [(0.16, 0), (0.13, 3.0), (0.09, 9.0), (0.07, 14.0)], concrete, segments=28)
        for z in (13.0, 13.4):
            m.upright(0, 0, z, z + 0.2, 0.08, 0.08, material("stack red band", "#a83a2a", rough=0.8), segments=28)
        m.upright(0, 0, 13.85, 14.0, 0.074, 0.074, material("stack cap", "#2e2c2a", metal=0.4, rough=0.6), segments=28)
        m.box(-0.17, -0.12, -0.03, 0.03, 0, 0.7, material("stack door", "#3a3634", metal=0.4, rough=0.6))
        return
    # The flue turns along x into a header, the three stacks standing on brick bases off its -y side.
    smelter_flue(m, 0.1, brick, trim)
    hy0, hy1 = -0.1, 0.1
    m.box(-0.42, 0.34, hy0, hy1, 0, 0.8, brick)
    gable(m, -0.42, 0.34, hy0, hy1, 0.8, 0.95, brick, brick, along_x=True, over=0.0, thick=0.04)
    for x in (-0.2, 0.12):
        m.box(x - 0.01, x + 0.01, hy0 - 0.008, hy1 + 0.008, 0, 0.85, trim)
    stack_brick = material("old stack brick", "#7a4434", rough=0.9)
    band = material("stack band", "#3a2a24", rough=0.9)
    soot = material("stack soot", "#2a2624", rough=0.95)
    for x, h, r in ((-0.3, 8.5, 0.07), (-0.02, 7.6, 0.065), (0.24, 6.6, 0.06)):
        y = -0.26
        m.box(x - 0.09, x + 0.09, y - 0.09, y + 0.09, 0, 0.9, brick, 0.004)
        m.box(x - 0.096, x + 0.096, y - 0.096, y + 0.096, 0.9, 1.0, trim)
        m.lathe(x, y, 1.0, [(r * 1.25, 0), (r * 1.08, (h - 1.0) * 0.5), (r, h - 1.0 - 0.3), (r * 1.12, h - 1.0 - 0.2), (r * 1.12, h - 1.0),
                            (r * 0.9, h - 1.0)], stack_brick, segments=20)
        m.upright(x, y, h - 0.5, h - 0.2, r * 1.06, r * 1.06, soot, segments=20)
        for z in (h * 0.4, h * 0.7):
            m.upright(x, y, z, z + 0.08, r * 1.14, r * 1.14, band, segments=20)
        column_ladder(m, x, y, r * 1.1, math.pi, 1.0, h - 0.2, trim, cage=False)
        m.box(x - 0.05, x + 0.05, y + 0.06, hy0, 0.1, 0.65, brick)


def roaster(m):
    """The roaster house: steel clad with a louvred monitor along its ridge, the roasters' offtakes up through the roof
    and sloping down into the brick flue that runs to the superstack (-y), and the concentrate bins with their bucket
    elevator at the end."""
    ground(m, "#4a4644", "smelter yard")
    wall = material("roaster siding", "#5e5a56", metal=0.4, rough=0.6)
    rib = material("roaster ribs", "#4e4a46", metal=0.4, rough=0.6)
    roof = material("roaster roof", "#3e3c3a", metal=0.4, rough=0.6)
    trim = material("roaster trim", "#3a3c3e", metal=0.3, rough=0.6)
    brick = material("flue brick", "#6e3e30", rough=0.9)
    duct = material("roaster duct", "#7a5a46", metal=0.4, rough=0.6)
    g = glass()
    x0, x1, y0, y1, eave, ridge = -0.27, 0.45, -0.26, 0.3, 3.3, 3.9
    yc = (y0 + y1) / 2
    m.box(x0 - 0.004, x1 + 0.004, y0 - 0.004, y1 + 0.004, 0, 0.6, brick)
    clad_box(m, x0, x1, y0, y1, 0.6, eave, wall, rib, gap=0.03)
    gable(m, x0, x1, y0, y1, eave, ridge, roof, wall, along_x=True, over=0.02)
    for x in (-0.12, 0.08, 0.28):
        houses.on_wall(m, "y", y1, 1, x - 0.06, x + 0.06, 0, 0.008, 1.9, 2.6, g)
    houses.on_wall(m, "x", x0, -1, -0.1, 0.14, 0, 0.008, 2.0, 2.5, g)
    harbour.rollup_door(m, 0.3, 0.4, y1, 0.0, 0.9, face=1)
    # The monitor down the ridge.
    mx0, mx1 = x0 + 0.06, x1 - 0.06
    m.box(mx0, mx1, yc - 0.07, yc + 0.07, ridge - 0.2, ridge + 0.22, wall)
    slat = material("monitor louvres", "#7a7672", metal=0.3, rough=0.6)
    for k in range(6):
        x = mx0 + 0.05 + k * 0.1
        for y, d in ((yc - 0.07, -1), (yc + 0.07, 1)):
            houses.louvres(m, "y", y, d, x, ridge - 0.02, ridge + 0.18, 0.07, trim, slat)
    gable(m, mx0, mx1, yc - 0.07, yc + 0.07, ridge + 0.22, ridge + 0.34, roof, wall, along_x=True, over=0.015, thick=0.04)
    # The brick flue to the stack, and the offtakes up through the roof and sloping down into it.
    fx0, fx1, fy0, fy1 = -0.36, 0.0, -0.5, y0
    m.box(fx0, fx1, fy0, fy1, 0, 0.8, brick)
    gable(m, fx0, fx1, fy0, fy1, 0.8, 0.95, brick, brick, along_x=False, over=0.0, thick=0.04)
    for y in (-0.46, -0.34):
        m.box(fx0 - 0.008, fx1 + 0.008, y - 0.01, y + 0.01, 0, 0.85, trim)
    r = 0.024
    yo = -0.1
    zr = eave + (ridge - eave) * (1 - abs(yo - yc) / ((y1 - y0) / 2))
    # They rise well clear of the monitor so they show over the roof from the far side too.
    top = 5.1
    for x in (-0.2, -0.1, 0.0):
        m.upright(x, yo, zr - 0.1, top, r, r, duct, segments=12)
        m.upright(x, yo, zr - 0.02, zr + 0.06, r * 1.4, r * 1.4, trim, segments=12)
        m.lathe(x, yo, top - 0.05, [(r * 1.15, 0), (r * 1.15, 0.12), (0, 0.13)], duct, segments=12)
        m.upright(x, yo, top - 0.5, top - 0.44, r * 1.3, r * 1.3, trim, segments=12)
        xf = max(fx0 + 0.04, min(fx1 - 0.04, x))
        m.rod((x, yo, top), (xf, -0.42, 0.9), r, duct, segments=12)
        m.upright(xf, -0.42, 0.85, 1.05, r * 1.3, r * 1.3, trim, segments=12)
    # The concentrate bins on their legs at the end, the bucket elevator up to them and the feed into the house.
    steel = material("bin steel", "#6a6662", metal=0.4, rough=0.6)
    for cy in (-0.16, 0.04):
        bx0, bx1, by0, by1 = -0.46, -0.31, cy - 0.08, cy + 0.08
        m.box(bx0, bx1, by0, by1, 1.2, 3.3, steel, 0.003)
        m.box(bx0 - 0.004, bx1 + 0.004, by0 - 0.004, by1 + 0.004, 3.3, 3.36, trim)
        m.wedge([(-0.4, cy - 0.02, 0.8), (-0.37, cy - 0.02, 0.8), (-0.37, cy + 0.02, 0.8), (-0.4, cy + 0.02, 0.8)],
                [(bx0, by0, 1.2), (bx1, by0, 1.2), (bx1, by1, 1.2), (bx0, by1, 1.2)], steel)
        for x in (bx0 + 0.006, bx1 - 0.006):
            for y in (by0 + 0.006, by1 - 0.006):
                m.box(x - 0.005, x + 0.005, y - 0.005, y + 0.005, 0, 1.2, trim)
        m.tube_x(bx1, x0, cy, 1.0, 0.012, steel, segments=10)
    m.box(-0.42, -0.36, 0.18, 0.24, 0, 3.9, steel)
    m.box(-0.43, -0.35, 0.17, 0.25, 3.9, 4.2, steel, 0.003)
    m.rod((-0.39, 0.17, 4.0), (-0.39, 0.08, 3.4), 0.01, steel, segments=6)
    harbour.ladder(m, -0.43, 0.21, 0, 3.9, face_x=True, grab=0.1)
    conveyor(m, (-0.39, 0.5, 0.35), (-0.39, 0.25, 0.35), 0.05, trim, material("conveyor belt", "#2e2a26", rough=0.8), legs=0,
             load=material("concentrate", "#5a5848", rough=1.0))


def smelter_shed(m):
    """The smelter's furnace and converter building: brick with pilasters, a long louvred monitor along its ridge, fume
    ducts out of the roof sloping down to a header (-y), and the converter aisle doors glowing at the end with the slag
    track out of them."""
    ground(m, "#4a4644", "smelter yard")
    brick = material("smelter brick", "#6e3e30", rough=0.9)
    roof = material("smelter roof", "#3e3c3a", metal=0.4, rough=0.6)
    trim = material("smelter trim", "#3a3c3e", metal=0.3, rough=0.6)
    duct = material("fume duct", "#6a5a4e", metal=0.4, rough=0.6)
    g = glass()
    x0, x1, y0, y1, eave, ridge = -0.44, 0.44, -0.36, 0.36, 2.9, 3.4
    yc = (y0 + y1) / 2
    m.box(x0, x1, y0, y1, 0, eave, brick)
    x = x0
    while x <= x1 + 1e-6:
        for y, d in ((y0, -1), (y1, 1)):
            houses.on_wall(m, "y", y, d, x - 0.012, x + 0.012, 0, 0.01, 0, eave, brick)
        x += 0.11
    for y, d in ((y0, -1), (y1, 1)):
        houses.on_wall(m, "y", y, d, x0, x1, 0, 0.012, eave - 0.12, eave, trim)
        for k in range(8):
            c = x0 + 0.055 + k * 0.11
            houses.pane(m, "y", y, d, c, 1.4, 2.5, 0.06, g, trim, trim, sash=False, bars=1)
    gable(m, x0, x1, y0, y1, eave, ridge, roof, brick, along_x=True, over=0.02)
    # The monitor nearly the whole length, louvred both sides.
    mx0, mx1 = -0.4, 0.4
    m.box(mx0, mx1, yc - 0.075, yc + 0.075, ridge - 0.2, ridge + 0.24, brick)
    slat = material("monitor louvres", "#7a7672", metal=0.3, rough=0.6)
    for k in range(8):
        c = mx0 + 0.05 + k * 0.1
        for y, d in ((yc - 0.075, -1), (yc + 0.075, 1)):
            houses.louvres(m, "y", y, d, c, ridge - 0.02, ridge + 0.2, 0.075, trim, slat)
    gable(m, mx0, mx1, yc - 0.075, yc + 0.075, ridge + 0.24, ridge + 0.36, roof, brick, along_x=True, over=0.015, thick=0.04)
    # The converter aisle doors at the -x end, molten light inside, and the slag pot track out of one of them.
    hot = lit("converter glow", "#ff7a2a", 5.0)
    leaf = material("door leaf", "#3a3634", metal=0.4, rough=0.6)
    for c, open_ in ((-0.2, True), (0.16, False)):
        houses.on_wall(m, "x", x0, -1, c - 0.085, c + 0.085, 0, 0.004, 0, 1.75, hot if open_ else leaf)
        for s in (-1, 1):
            houses.on_wall(m, "x", x0, -1, c + s * 0.085 - 0.01, c + s * 0.085 + 0.01, 0, 0.014, 0, 1.85, trim)
        houses.on_wall(m, "x", x0, -1, c - 0.1, c + 0.1, 0, 0.014, 1.75, 1.85, trim)
        if open_:
            houses.on_wall(m, "x", x0, -1, c - 0.085, c + 0.085, 0.004, 0.008, 1.1, 1.75, leaf)
    siding(m, -0.5, x0, -0.2)
    for c in (-0.2, 0.16):
        houses.on_wall(m, "x", x1, 1, c - 0.085, c + 0.085, 0, 0.004, 0, 1.75, leaf)
        houses.on_wall(m, "x", x1, 1, c - 0.1, c + 0.1, 0, 0.014, 1.75, 1.85, trim)
    # The fume ducts up out of the roof and sloping down to the header on its bents.
    # They rise well clear of the monitor so they show over the roof from the far side too.
    r = 0.028
    hy, hz = -0.45, 2.5
    yo = -0.2
    top = 4.5
    zr = eave + (ridge - eave) * (1 - abs(yo - yc) / ((y1 - y0) / 2))
    for x in (-0.28, 0.0, 0.28):
        m.upright(x, yo, zr - 0.1, top, r, r, duct, segments=12)
        m.upright(x, yo, zr - 0.02, zr + 0.06, r * 1.4, r * 1.4, trim, segments=12)
        m.upright(x, yo, top - 0.5, top - 0.44, r * 1.3, r * 1.3, trim, segments=12)
        m.lathe(x, yo, top - 0.05, [(r * 1.15, 0), (r * 1.15, 0.12), (0, 0.13)], duct, segments=12)
        m.rod((x, yo, top), (x + 0.05, hy, hz + 0.1), r, duct, segments=12)
    m.tube_x(-0.36, 0.5, hy, hz, 0.038, duct, segments=16)
    for x in (-0.3, 0.06, 0.4):
        m.box(x - 0.006, x + 0.006, hy - 0.04, hy - 0.028, 0, hz, trim)
        m.box(x - 0.006, x + 0.006, hy + 0.028, hy + 0.04, 0, hz, trim)
        m.box(x - 0.006, x + 0.006, hy - 0.04, hy + 0.04, hz - 0.2, hz - 0.16, trim)
    m.lathe(-0.38, hy, 0.0, [(0.05, 0), (0.05, hz - 0.4), (0.03, hz - 0.1), (0.0, hz - 0.05)], duct, segments=16)


def dam(m):
    """A concrete gravity dam across the river, its downstream face out over the water (-x): a spillway of piers with
    vertical lift gates between them, a service deck on top with rails along it for the gantry crane that lifts the
    gates, and one gate up with the water pouring down the face to foam at its foot."""
    ground(m, "#6a6660", "dam road")
    concrete = material("dam concrete", "#bcb8ae", rough=0.9)
    stain = material("dam concrete wet", "#8e8a82", rough=0.85)
    steel = material("gate steel", "#4a5058", metal=0.5, rough=0.5)
    rail = material("dam handrail", "#d8d4c8", metal=0.3, rough=0.5)
    paint = material("gantry crane", "#c8a028", metal=0.3, rough=0.5)
    dark = material("crane machinery", "#3a3c3e", metal=0.4, rough=0.6)
    water = material("spillway water", "#c8dce4", rough=0.15)
    foam = material("foam", "#e8f0f4", rough=0.4)
    m.wedge([(-1.1, -0.5, -0.4), (-0.4, -0.5, -0.4), (-0.4, 0.5, -0.4), (-1.1, 0.5, -0.4)],
            [(-0.7, -0.5, 2.2), (-0.5, -0.5, 2.2), (-0.5, 0.5, 2.2), (-0.7, 0.5, 2.2)], concrete)
    m.box(-0.74, -0.46, -0.5, 0.5, 2.2, 2.32, concrete)
    # The piers every 0.2 along, rounded on the upstream (+x) end.
    piers = (-0.4, -0.2, 0.0, 0.2, 0.4)
    for y in piers:
        m.box(-0.76, -0.47, y - 0.024, y + 0.024, 2.32, 2.96, concrete)
        m.upright(-0.47, y, 2.32, 2.96, 0.024, 0.024, concrete, segments=12)
    # The gates: closed ones down on the crest, ribbed, the open one hung up in its slots.
    bays = [(-0.5, -0.424), (-0.376, -0.224), (-0.176, -0.024), (0.024, 0.176), (0.224, 0.376), (0.424, 0.5)]
    for k, (a, b) in enumerate(bays):
        up = k == 3
        z0 = 2.58 if up else 2.32
        m.box(-0.6, -0.58, a, b, z0, z0 + 0.42, steel)
        for z in (z0 + 0.1, z0 + 0.22, z0 + 0.34):
            m.box(-0.612, -0.6, a, b, z, z + 0.03, steel)
        if not up:
            m.box(-0.74, -0.6, a, b, 2.32, 2.34, stain)
    # Water through the open gate, down the face between training walls and foaming at the foot.
    a, b = bays[3]
    m.wedge([(-1.12, a + 0.004, -0.38), (-1.09, a + 0.004, -0.38), (-1.09, b - 0.004, -0.38), (-1.12, b - 0.004, -0.38)],
            [(-0.72, a + 0.004, 2.34), (-0.58, a + 0.004, 2.36), (-0.58, b - 0.004, 2.36), (-0.72, b - 0.004, 2.34)], water)
    for y in piers[2:4]:
        m.wedge([(-1.11, y - 0.02, -0.4), (-1.08, y - 0.02, -0.4), (-1.08, y + 0.02, -0.4), (-1.11, y + 0.02, -0.4)],
                [(-0.73, y - 0.02, 2.32), (-0.7, y - 0.02, 2.32), (-0.7, y + 0.02, 2.32), (-0.73, y + 0.02, 2.32)], concrete)
    for k in range(9):
        y = a + 0.01 + k * (b - a - 0.02) / 8
        scenery.blob(m, -1.14 + 0.03 * math.sin(k * 2.1), y, -0.34, 0.035 + 0.01 * math.sin(k * 1.7), foam, 300 + k, squash=0.5)
    m.box(-1.1, -0.86, a - 0.06, b + 0.06, -0.4, -0.32, foam)
    # The service deck on the piers, the crane rails along it and a handrail each side.
    m.box(-0.62, -0.47, -0.5, 0.5, 2.96, 3.04, concrete)
    m.box(-0.76, -0.68, -0.5, 0.5, 2.96, 3.04, concrete)
    for x in (-0.745, -0.48):
        m.box(x - 0.005, x + 0.005, -0.5, 0.5, 3.04, 3.08, dark)
    for x in (-0.765, -0.465):
        m.box(x - 0.002, x + 0.002, -0.5, 0.5, 3.22, 3.24, rail)
        for k in range(10):
            y = -0.45 + k * 0.1
            m.box(x - 0.002, x + 0.002, y - 0.002, y + 0.002, 3.04, 3.24, rail)
    # The gantry crane over the open gate, kept low (top 3.4): two braced leg frames on the rails, the girders over the
    # handrails, its machinery house and the lifting beam hung on its ropes above the raised gate (top 3.0).
    cy = (a + b) / 2
    for y in (cy - 0.06, cy + 0.06):
        for x in (-0.745, -0.48):
            m.box(x - 0.012, x + 0.012, y - 0.03, y + 0.03, 3.08, 3.13, dark)
            m.box(x - 0.008, x + 0.008, y - 0.008, y + 0.008, 3.13, 3.26, paint)
        m.rod((-0.745, y, 3.14), (-0.48, y, 3.24), 0.004, paint, segments=4)
        m.rod((-0.48, y, 3.14), (-0.745, y, 3.24), 0.004, paint, segments=4)
        m.box(-0.765, -0.46, y - 0.012, y + 0.012, 3.255, 3.3, paint)
    for x in (-0.745, -0.48):
        m.rod((x, cy - 0.06, 3.16), (x, cy + 0.06, 3.16), 0.004, paint, segments=4)
        m.rod((x, cy - 0.06, 3.16), (x, cy + 0.06, 3.25), 0.003, paint, segments=4)
    m.box(-0.68, -0.54, cy - 0.075, cy + 0.075, 3.3, 3.365, paint, 0.003)
    gable(m, -0.68, -0.54, cy - 0.075, cy + 0.075, 3.365, 3.4, dark, paint, along_x=False, over=0.006, thick=0.02)
    for s in (-1, 1):
        m.rod((-0.59, cy + s * 0.05, 3.255), (-0.59, cy + s * 0.05, 3.1), 0.0016, dark, segments=4)
    m.box(-0.605, -0.575, cy - 0.065, cy + 0.065, 3.06, 3.1, paint)


def powerhouse(m):
    """The generating station on the river bank (-x), Shipshaw style: the brick turbine hall with tall windows and its
    tailrace arches, three penstocks coming down to it from the intake up on the forebay wall (+x) with its gate houses,
    transformers on the -y side and the lines going out on lattice towers."""
    ground(m, "#6a6660", "dam road")
    brick = material("powerhouse brick", "#9a5a42", rough=0.9)
    stone = material("powerhouse stone", "#d0c6b2", rough=0.8)
    concrete = material("dam concrete", "#bcb8ae", rough=0.9)
    penstock = material("penstock", "#5e646a", metal=0.5, rough=0.5)
    dark = material("crane machinery", "#3a3c3e", metal=0.4, rough=0.6)
    house = material("gate house", "#a8a49a", rough=0.8)
    slate = material("powerhouse roof", "#4e5866", rough=0.6)
    g = glass()
    x0, x1, y0, y1 = -0.46, -0.12, -0.38, 0.38
    m.box(x0, x1, y0, y1, 0, 0.5, stone)
    m.box(x0, x1, y0, y1, 0.5, 3.4, brick, 0.004)
    gable(m, x0, x1, y0, y1, 3.4, 4.2, slate, brick, along_x=False)
    for x in (x0, x1):
        for y in (y0, y1):
            m.box(x - 0.012, x + 0.012, y - 0.012, y + 0.012, 0.5, 3.45, stone)
    for k in range(5):
        y = y0 + 0.076 + k * 0.152
        houses.pane(m, "x", x0, -1, y, 1.0, 2.9, 0.08, g, stone, stone, sash=False, bars=1)
        houses.on_wall(m, "x", x0, -1, y - 0.05, y + 0.05, 0, 0.012, 0.0, 0.35, material("tailrace arch", "#1e2428", rough=0.4))
    for x in (-0.36, -0.18):
        houses.pane(m, "y", y1, 1, x, 1.0, 2.9, 0.08, g, stone, stone, sash=False, bars=1)
        houses.pane(m, "y", y0, -1, x, 1.0, 2.9, 0.08, g, stone, stone, sash=False, bars=1)
    # The intake on the forebay wall with its gate houses and trash rack, and the penstocks down to the hall on their
    # saddles, stiffener rings round them and an anchor block at the foot of each.
    ix0, ix1 = 0.34, 0.48
    iz = 2.0
    # A low forebay wall with an intake block for each penstock standing out of it and a deck across their tops, open
    # between so the penstocks show from the intake side.
    m.wedge([(ix0 - 0.04, -0.42, 0), (ix1 - 0.02, -0.42, 0), (ix1 - 0.02, 0.42, 0), (ix0 - 0.04, 0.42, 0)],
            [(ix0, -0.42, 0.7), (ix1 - 0.02, -0.42, 0.7), (ix1 - 0.02, 0.42, 0.7), (ix0, 0.42, 0.7)], concrete)
    for y in (-0.24, 0.0, 0.24):
        m.wedge([(ix0 - 0.04, y - 0.07, 0), (ix1, y - 0.07, 0), (ix1, y + 0.07, 0), (ix0 - 0.04, y + 0.07, 0)],
                [(ix0, y - 0.07, iz), (ix1, y - 0.07, iz), (ix1, y + 0.07, iz), (ix0, y + 0.07, iz)], concrete)
    m.box(ix1 - 0.05, ix1, -0.42, 0.42, iz - 0.08, iz, concrete)
    for x in (ix1 - 0.048, ix1 - 0.002):
        m.box(x - 0.002, x + 0.002, -0.42, 0.42, iz + 0.16, iz + 0.18, material("dam handrail", "#d8d4c8", metal=0.3, rough=0.5))
    for y in (-0.24, 0.0, 0.24):
        m.box(ix0 + 0.03, ix1 - 0.03, y - 0.05, y + 0.05, iz, iz + 0.5, house)
        gable(m, ix0 + 0.03, ix1 - 0.03, y - 0.05, y + 0.05, iz + 0.5, iz + 0.62, slate, house, along_x=True, over=0.008, thick=0.02)
        top, bot = (ix0 - 0.02, y, iz - 0.35), (x1 + 0.03, y, 0.75)
        m.rod(top, bot, 0.036, penstock, segments=16)
        for f in (0.15, 0.4, 0.65, 0.9):
            p = tuple(top[i] + (bot[i] - top[i]) * f for i in range(3))
            q = tuple(top[i] + (bot[i] - top[i]) * (f + 0.03) for i in range(3))
            m.rod(p, q, 0.041, dark, segments=16)
        for f in (0.3, 0.6):
            p = tuple(top[i] + (bot[i] - top[i]) * f for i in range(3))
            m.box(p[0] - 0.02, p[0] + 0.02, y - 0.03, y + 0.03, 0, p[2] - 0.12, concrete)
        m.box(x1, x1 + 0.08, y - 0.06, y + 0.06, 0, 1.05, concrete)
    rack = material("trash rack", "#5a5c5e", metal=0.5, rough=0.6)
    for y in (-0.24, 0.0, 0.24):
        m.box(ix1, ix1 + 0.004, y - 0.065, y + 0.065, 0.6, 1.5, material("intake opening", "#2a3034", rough=0.4))
        for k in range(7):
            yy = y - 0.06 + k * 0.02
            m.box(ix1 + 0.004, ix1 + 0.008, yy - 0.002, yy + 0.002, 0.6, 1.5, rack)
        m.box(ix1 + 0.004, ix1 + 0.008, y - 0.065, y + 0.065, 1.45, 1.5, rack)
    # Transformers on the -y side and the lattice towers taking the lines away.
    tank = material("transformer", "#7a8478", metal=0.4, rough=0.6)
    for x in (-0.36, -0.2, -0.04):
        m.box(x - 0.045, x + 0.045, -0.475, -0.405, 0, 0.5, tank, 0.004)
        for k in range(3):
            m.box(x - 0.04 + k * 0.035, x - 0.03 + k * 0.035, -0.49, -0.475, 0.05, 0.45, tank)
            m.upright(x - 0.025 + k * 0.025, -0.44, 0.5, 0.72, 0.006, 0.004, material("bushing", "#c8bca8", rough=0.5), segments=8)
    steel = material("pylon", "#8a8a86", metal=0.5, rough=0.5)
    for x, y in ((0.2, -0.44), (0.2, 0.44)):
        for dx, dy in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
            m.rod((x + dx * 0.04, y + dy * 0.03, 0), (x + dx * 0.008, y + dy * 0.008, 4.2), 0.004, steel, segments=4)
        for z in (1.2, 2.4, 3.4):
            f = z / 4.2
            w = 0.04 * (1 - f) + 0.008 * f
            m.rod((x - w, y, z), (x + w, y, z), 0.003, steel, segments=4)
        m.box(x - 0.11, x + 0.11, y - 0.008, y + 0.008, 4.2, 4.28, steel)
        for s in (-1, 0, 1):
            m.rod((x + s * 0.1, y, 4.2), (x + s * 0.1, y, 4.0), 0.003, material("insulator", "#c8bca8", rough=0.5), segments=6)


def potline(m, scrubber=False):
    """A length of potroom, run end to end across the potline's tiles into one very long, low building: ribbed cladding,
    a ventilator along the whole ridge, and the fume ducts off its +x side into a header on bents. On the middle tile the
    header goes into a dry scrubber with its fan house and stack; on the others stand alumina silos and stacked ingots."""
    paved(m, -0.5, 0.5, -0.5, 0.5, "#7a766e")
    wall = material("potline siding", "#a8a49c", metal=0.3, rough=0.6)
    rib = material("potline ribs", "#929088", metal=0.3, rough=0.6)
    roof = material("potline roof", "#5a5854", metal=0.4, rough=0.6)
    trim = material("potline trim", "#3a3c3e", metal=0.3, rough=0.6)
    slat = material("monitor louvres", "#7a7672", metal=0.3, rough=0.6)
    duct = material("fume duct", "#8a8680", metal=0.5, rough=0.5)
    g = glass()
    x0, x1, eave, ridge = -0.44, -0.08, 1.4, 1.72
    xc = (x0 + x1) / 2
    m.box(x0, x1, -0.5, 0.5, 0, eave, wall)
    for x, d in ((x0, -1), (x1, 1)):
        harbour.ribs_y(m, x, -0.5, 0.5, 0.0, eave, rib, 0.03, face=d)
        houses.on_wall(m, "x", x, d, -0.5, 0.5, 0, 0.012, eave - 0.1, eave, trim)
    houses.on_wall(m, "x", x0, -1, -0.5, 0.5, 0.004, 0.008, 0.95, 1.18, g)
    for k in range(4):
        houses.on_wall(m, "x", x0, -1, -0.5 + k * 0.25 + 0.11, -0.5 + k * 0.25 + 0.14, 0.008, 0.012, 0.95, 1.18, trim)
    gable(m, x0, x1, -0.5, 0.5, eave, ridge, roof, wall, along_x=False, over=0.02)
    # The ventilator along the whole ridge, louvred both sides.
    m.box(xc - 0.05, xc + 0.05, -0.5, 0.5, ridge - 0.1, ridge + 0.2, wall)
    for k in range(10):
        y = -0.45 + k * 0.1
        for x, d in ((xc - 0.05, -1), (xc + 0.05, 1)):
            houses.louvres(m, "x", x, d, y, ridge - 0.04, ridge + 0.16, 0.07, trim, slat)
    gable(m, xc - 0.05, xc + 0.05, -0.5, 0.5, ridge + 0.2, ridge + 0.3, roof, wall, along_x=False, over=0.015, thick=0.04)
    # A fume duct off each pair of pots, out and up into the header along +x on its bents.
    hx, hz = 0.03, 1.55
    for k in range(8):
        y = -0.4375 + k * 0.125
        m.tube_x(x1, hx - 0.03, y, 0.95, 0.014, duct, segments=10)
        m.rod((hx - 0.03, y, 0.95), (hx - 0.01, y, hz - 0.05), 0.014, duct, segments=10)
    m.tube(-0.5, 0.5, hx, hz, 0.034, duct, segments=16)
    for y in (-0.25, 0.25):
        for s in (-1, 1):
            m.box(hx + s * 0.035 - 0.005, hx + s * 0.035 + 0.005, y - 0.005, y + 0.005, 0, hz - 0.1, trim)
        m.box(hx - 0.045, hx + 0.045, y - 0.006, y + 0.006, hz - 0.2, hz - 0.14, trim)
    if scrubber:
        # The dry scrubber: reactor modules over their hoppers, the clean gas main to the fan house and the stack.
        steel = material("scrubber steel", "#9a9890", metal=0.4, rough=0.55)
        m.rod((hx, 0.0, hz), (0.16, 0.0, 1.85), 0.034, duct, segments=16)
        sx0, sx1 = 0.16, 0.44
        for k in range(4):
            y0 = -0.36 + k * 0.15
            y1 = y0 + 0.13
            for x in (sx0 + 0.01, sx1 - 0.01):
                for y in (y0 + 0.01, y1 - 0.01):
                    m.box(x - 0.006, x + 0.006, y - 0.006, y + 0.006, 0, 0.7, trim)
            m.wedge([(0.28, y0 + 0.05, 0.4), (0.32, y0 + 0.05, 0.4), (0.32, y1 - 0.05, 0.4), (0.28, y1 - 0.05, 0.4)],
                    [(sx0, y0, 0.7), (sx1, y0, 0.7), (sx1, y1, 0.7), (sx0, y1, 0.7)], steel)
            clad_box(m, sx0, sx1, y0, y1, 0.7, 1.9, steel, trim, gap=0.035)
            m.box(sx0 - 0.004, sx1 + 0.004, y0 - 0.004, y1 + 0.004, 1.9, 1.96, trim)
        m.tube(-0.36, 0.25, 0.3, 2.06, 0.03, duct, segments=14)
        m.box(0.2, 0.42, 0.28, 0.46, 0, 1.0, steel, 0.003)
        houses.flat_top(m, 0.2, 0.42, 0.28, 0.46, 1.0, trim, parapet=0.06)
        m.rod((0.3, 0.25, 2.06), (0.3, 0.34, 1.1), 0.03, duct, segments=12)
        m.rod((0.2, 0.37, 0.6), (0.14, 0.42, 0.6), 0.03, duct, segments=12)
        stack(m, 0.1, 0.42, 0, 3.0, 0.035, material("scrubber stack", "#b0aca4", metal=0.4, rough=0.5),
              material("stack red band", "#a83a2a", rough=0.8))
        return
    # Alumina silos with the gallery over them and the feed line to the potroom, and stacked ingots ready to load.
    white = material("alumina silo", "#d8d4cc", metal=0.3, rough=0.5)
    for y in (-0.3, -0.08):
        m.upright(0.3, y, 0, 0.5, 0.085, 0.085, trim, segments=20)
        m.upright(0.3, y, 0.5, 2.4, 0.09, 0.09, white, segments=24)
        m.lathe(0.3, y, 2.4, [(0.094, 0), (0.05, 0.22), (0.0, 0.26)], white, segments=24)
    gallery(m, (0.3, -0.3, 2.62), (0.3, -0.08, 2.62), 0.04, 0.16, white, trim, g)
    m.rod((0.3, -0.19, 2.66), (hx + 0.04, -0.19, 1.62), 0.012, trim, segments=8)
    ingot = material("aluminium ingots", "#c8ccd0", metal=0.9, rough=0.3)
    for x in (0.18, 0.3, 0.42):
        for y in (0.2, 0.36):
            for k in range(3):
                m.box(x - 0.045, x + 0.045, y - 0.05, y + 0.05, 0.02 + k * 0.1, 0.1 + k * 0.1, ingot, 0.003)


# ---- the oil sands ------------------------------------------------------------------------------

def heavy_hauler(m, paint="#d8a428", load=1.0):
    """A Cat 797 class haul truck facing +y, bigger than the mine's: the radiator and its grille between the front tires,
    the full width deck over them with the cab on the left and a stairway across the front, the dump body with a V
    floor sloping up to the tail and its canopy out over the cab, duals at the back. load is how full it is, 0 to 1."""
    body = material("haul truck " + paint, paint, metal=0.3, rough=0.5)
    dark = material("truck frame", "#2e2e30", metal=0.4, rough=0.6)
    tire = material("big tire", "#1a1a1a", rough=0.9)
    rail = material("truck handrail", "#e8e2d4", metal=0.3, rough=0.5)
    g = glass()
    r = 0.042
    zc = r / rig.RISE
    for y, xs in ((0.08, (0.068,)), (-0.07, (0.045, 0.076))):
        for x in xs:
            for s in (-1, 1):
                m.tube_x(s * x - 0.0135, s * x + 0.0135, y, zc, r, tire, segments=20)
                m.tube_x(s * x - 0.0145, s * x + 0.0145, y, zc, r * 0.45, dark, segments=12)
    m.tube_x(-0.06, 0.06, -0.07, zc, 0.018, dark, segments=12)
    m.box(-0.03, 0.03, -0.13, 0.15, 0.24, 0.42, dark)
    # The radiator module, its grille facing forward, and the bumper under it.
    m.shell([(0.03, 0.26, 0.0, "paint"), (0.15, 0.26, 0.008, "grille"), (0.158, 0.6, 0.012, "paint"), (0.12, 0.64, 0.01, "paint"),
             (0.03, 0.64, 0.0, "paint")], 0.042, {"paint": body, "grille": dark}, x=0.0)
    m.box(-0.05, 0.05, 0.145, 0.165, 0.22, 0.32, dark)
    # The deck over the front tires, the cab on the left of it, the air cleaners on the right, handrails round it.
    m.box(-0.092, 0.092, 0.035, 0.16, 0.64, 0.68, dark)
    m.shell([(0.05, 0.68, 0.0, "paint"), (0.142, 0.68, 0.004, "paint"), (0.146, 0.8, 0.006, "glass"), (0.13, 0.98, 0.01, "paint"),
             (0.05, 0.99, 0.01, "paint")], 0.031, {"paint": body, "glass": g}, x=-0.058, crown=0.02)
    m.box(-0.0905, -0.0885, 0.07, 0.125, 0.8, 0.95, g)
    for x in (0.035, 0.07):
        m.tube(0.06, 0.15, x, 0.74, 0.019, body, segments=12)
    for x in (-0.09, -0.03, 0.03, 0.09):
        m.rod((x, 0.158, 0.68), (x, 0.158, 0.88), 0.0016, rail, segments=4)
    m.rod((-0.09, 0.158, 0.88), (0.09, 0.158, 0.88), 0.0016, rail, segments=4)
    m.rod((0.09, 0.158, 0.88), (0.09, 0.04, 0.88), 0.0016, rail, segments=4)
    # The stairway up across the front, from the ground on the right to the deck.
    a, b = (0.088, 0.172, 0.08), (-0.025, 0.172, 0.64)
    m.rod(a, b, 0.003, dark, segments=4)
    m.rod((a[0], a[1], a[2] + 0.22), (b[0], b[1], b[2] + 0.24), 0.0016, rail, segments=4)
    for k in range(7):
        f = (k + 0.5) / 7
        x, z = a[0] + (b[0] - a[0]) * f, a[2] + (b[2] - a[2]) * f
        m.box(x - 0.008, x + 0.008, 0.162, 0.18, z - 0.02, z, dark)
        if k % 2 == 0:
            m.rod((x, 0.176, z), (x, 0.176, z + 0.22), 0.0014, rail, segments=4)
    # The dump body: a V floor rising to the tail, ribbed sides, the canopy forward over the deck.
    def ring(fl, top, hw=0.092):
        return [(-hw, top), (hw, top), (hw, fl + 0.16), (hw * 0.55, fl), (-hw * 0.55, fl), (-hw, fl + 0.16)]
    m.loft([(-0.165, ring(0.76, 0.96)), (-0.12, ring(0.6, 1.0)), (0.0, ring(0.54, 1.04)), (0.04, ring(0.54, 1.06))], {"paint": body})
    m.box(-0.094, 0.094, 0.035, 0.17, 1.03, 1.08, body, 0.003)
    m.box(-0.094, 0.094, 0.16, 0.17, 0.98, 1.03, body)
    for y in (-0.12, -0.07, -0.02):
        for s in (-1, 1):
            m.box(s * 0.094 - 0.003, s * 0.094 + 0.003, y - 0.005, y + 0.005, 0.76, 1.0, body)
    for s in (-1, 1):
        m.rod((s * 0.095, -0.165, 0.975), (s * 0.095, 0.04, 1.065), 0.006, body, segments=8)
        m.rod((s * 0.095, -0.165, 0.975), (s * 0.06, -0.17, 0.8), 0.005, body, segments=8)
    for s in (-1, 1):
        m.rod((s * 0.035, 0.03, 0.36), (s * 0.045, -0.03, 0.62), 0.007, dark, segments=8)
    if load > 0:
        sand = material("oil sand load", "#1e1a17", rough=1.0)
        top = 0.98 + 0.18 * load
        m.wedge([(-0.084, -0.155, 0.92), (0.084, -0.155, 0.92), (0.084, 0.032, 1.0), (-0.084, 0.032, 1.0)],
                [(-0.03, -0.09, top), (0.03, -0.09, top), (0.03, -0.01, top + 0.02), (-0.03, -0.01, top + 0.02)], sand)
        for k, (x, y) in enumerate(((-0.03, -0.06), (0.025, -0.03), (0.0, -0.1))):
            scenery.blob(m, x, y, top - 0.04, 0.03, sand, 410 + k, squash=0.6)


# The oil sand pit's benches across its four tiles, its middle on their shared corner (0.5, 0.5 in the first tile's
# terms); the haul road spirals from the floor up through the +x, +y tile to the rim.
SAND = Benches(0.5, 0.5, 0.88, 0.88, [(0.55, 0.02), (0.72, 0.55), (0.86, 1.0), (9.0, 1.4)], seed=7.0,
               road=(0.62 * math.pi, -0.62, 0.5, 0.85, 0.02, 1.4, 0.12), rough=0.012, face=0.05, edge=0.05)
# Where each of the pit's pieces sits from the first, x and y.
SAND_TILES = {"sand_pit": (0, 0), "sand_pit_far": (0, 1), "sand_pit_side": (1, 0), "sand_pit_corner": (1, 1)}


def sand_edge(x, y):
    """How far x, y is in from the outside of the sand pit's four tiles."""
    return min(x + 0.5, 1.5 - x, y + 0.5, 1.5 - y)


def sand_height(x, y):
    """The sand pit's ground, its spoil berm sloping down to the land round its four tiles."""
    d = sand_edge(x, y)
    h = SAND.height(x, y)
    return min(h, 0.1 + 1.3 * max(0.0, d) / 0.07) if d < 0.07 else h


def sand_pick(x, y, slope):
    """The sand pit's faces: black oil sand under the brown overburden face at the top, grey-brown benches, a darker
    wet floor, the road and the spoil berm."""
    if sand_edge(x, y) < 0.08:
        return 4
    e, a = SAND.at(x, y)
    if SAND.on_road(e, a) is not None:
        return 3
    h = SAND.height(x, y)
    face, below = SAND.on_face(x, y, 0.7 / (180 * SAND.rx))
    if face:
        # The top face is brown overburden over the black oil sand.
        return 2 if below >= 1.0 and h > 1.2 else 1
    if slope > 5.0 and SAND.near_road(e, a) is not None:
        # The bank down from the road's side to the bench.
        return 2 if h > 1.2 else 1
    return 5 if h < 0.1 else 0


def sand_road(f):
    """The haul road's middle at f, 0 at the floor to 1 at the rim: x, y, its height and its heading uphill in radians."""
    a0, turns, e0, e1, h0, h1, _ = SAND.road

    def at(t):
        a = a0 + turns * math.tau * t
        e = e0 + (e1 - e0) * t
        dx, dy = (e * SAND.rx * math.cos(a), e * SAND.ry * math.sin(a))
        x0, y0 = SAND.cx + dx, SAND.cy + dy
        # at() divides by the wobble, so scale out by it to land on the road.
        ee, _ = SAND.at(x0, y0)
        k = e / ee
        return SAND.cx + dx * k, SAND.cy + dy * k, h0 + (h1 - h0) * t
    x, y, h = at(f)
    xn, yn, hn = at(f + 0.01)
    run = math.hypot(xn - x, yn - y)
    return x, y, h, math.atan2(-(xn - x), yn - y), math.atan2((hn - h) * rig.RISE, run)


def bucketwheel(m):
    """A bucketwheel excavator facing +y, as Suncor dug with from 1967 and Syncrude reclaimed its windrows with: crawlers
    under a turntable, the machinery house with its mast, the long boom forward down to the wheel of buckets at the face
    and its belt back along it, the counterweight boom behind, cables from the mast to both, and the discharge boom out
    to the -x side."""
    body = material("bucketwheel paint", "#d8cfa8", metal=0.3, rough=0.55)
    dark = material("bucketwheel crawler", "#2e2e30", metal=0.4, rough=0.6)
    steel = material("bucketwheel steel", "#5a5a56", metal=0.5, rough=0.5)
    rope = material("boom stay", "#2a2a2a", metal=0.6, rough=0.5)
    belt = material("conveyor belt", "#2e2a26", rough=0.8)
    sand = material("oil sand on belt", "#1e1a17", rough=1.0)
    g = glass()
    for sx in (-1, 1):
        m.box(sx * 0.07 - 0.024, sx * 0.07 + 0.024, -0.1, 0.1, 0.0, 0.2, dark, 0.012)
    m.upright(0.0, 0.0, 0.2, 0.3, 0.07, 0.07, steel, segments=20)
    m.box(-0.065, 0.065, -0.1, 0.06, 0.3, 0.72, body, 0.004)
    gable(m, -0.065, 0.065, -0.1, 0.06, 0.72, 0.8, body, body, along_x=False, over=0.004, thick=0.03)
    m.box(-0.065, -0.025, 0.06, 0.1, 0.5, 0.78, body, 0.003)
    m.box(-0.063, -0.027, 0.1, 0.103, 0.58, 0.74, g)
    # The mast, an A-frame over the house.
    top = (0.0, -0.02, 2.0)
    for sx in (-1, 1):
        for y in (-0.08, 0.04):
            m.rod((sx * 0.05, y, 0.8), top, 0.006, body, segments=4)
    # The main boom, a box girder down to the wheel, and the belt on it.
    foot, tip = (0.0, 0.06, 0.75), (0.0, 0.3, 0.6)
    for sx in (-1, 1):
        m.rod((sx * 0.018, foot[1], foot[2] + 0.1), (sx * 0.012, tip[1], tip[2] + 0.08), 0.007, body, segments=4)
        m.rod((sx * 0.018, foot[1], foot[2] - 0.05), (sx * 0.012, tip[1], tip[2] - 0.06), 0.007, body, segments=4)
    for f in (0.2, 0.4, 0.6, 0.8):
        p = tuple(foot[i] + (tip[i] - foot[i]) * f for i in range(3))
        m.rod((-0.016, p[1], p[2] - 0.05), (0.016, p[1], p[2] + 0.09), 0.003, body, segments=4)
    conveyor(m, (0.0, tip[1] - 0.02, tip[2] + 0.02), (0.0, foot[1], foot[2] + 0.04), 0.022, steel, belt, legs=0, load=sand)
    # The wheel of buckets, turning about x on the boom's +x side.
    r = 0.07
    wc = (0.026, tip[1] + 0.02, r / rig.RISE + 0.1)
    m.tube_x(wc[0] - 0.012, wc[0] + 0.012, wc[1], wc[2], r, steel, segments=24)
    m.tube_x(wc[0] - 0.016, wc[0] + 0.016, wc[1], wc[2], 0.012, dark, segments=12)
    for k in range(12):
        an = k * math.tau / 12
        by, bz = wc[1] + math.cos(an) * r, wc[2] + math.sin(an) * r / rig.RISE
        m.box(wc[0] - 0.016, wc[0] + 0.016, by - 0.012, by + 0.012, bz - 0.05, bz + 0.05, dark)
    # The counterweight boom behind, its block, and the stays from the mast.
    back = (0.0, -0.18, 1.0)
    for sx in (-1, 1):
        m.rod((sx * 0.03, -0.06, 0.78), (sx * 0.02, back[1], back[2]), 0.006, body, segments=4)
    m.box(-0.045, 0.045, back[1] - 0.04, back[1] + 0.03, back[2] - 0.2, back[2] + 0.08, material("counterweight", "#6a6a64", metal=0.3, rough=0.7), 0.004)
    for sx in (-1, 1):
        m.rod((sx * 0.004, top[1], top[2]), (sx * 0.012, tip[1] - 0.02, tip[2] + 0.1), 0.0018, rope, segments=4)
        m.rod((sx * 0.004, top[1], top[2]), (sx * 0.02, back[1], back[2] + 0.08), 0.0018, rope, segments=4)
    # The discharge boom out to the -x side over the bench conveyor.
    d0, d1 = (-0.06, -0.02, 0.75), (-0.16, -0.02, 0.55)
    conveyor(m, d0, d1, 0.02, steel, belt, legs=0, load=sand)
    m.rod((0.0, top[1], top[2] - 0.3), (d1[0], d1[1], d1[2] + 0.12), 0.0016, rope, segments=4)


def dragline(m):
    """A walking dragline facing +y, as Syncrude dug with from 1978: the tub it sits on and the walking shoes either
    side, the house with its A-frame, the long lattice boom and the bucket hanging off its point on the hoist ropes,
    dragged back on its drag ropes."""
    body = material("dragline paint", "#c8a028", metal=0.3, rough=0.55)
    house = material("dragline house", "#d8d4c8", metal=0.2, rough=0.6)
    dark = material("dragline dark", "#2e2e30", metal=0.4, rough=0.6)
    rope = material("hoist rope", "#2a2a2a", metal=0.6, rough=0.5)
    g = glass()
    m.upright(0.0, 0.0, 0.0, 0.12, 0.1, 0.1, dark, segments=24)
    for sx in (-1, 1):
        m.box(sx * 0.11 - 0.025, sx * 0.11 + 0.025, -0.08, 0.08, 0.0, 0.1, dark, 0.006)
    m.box(-0.08, 0.08, -0.14, 0.08, 0.12, 0.62, house, 0.004)
    gable(m, -0.08, 0.08, -0.14, 0.08, 0.62, 0.7, house, house, along_x=False, over=0.004, thick=0.03)
    m.box(-0.08, -0.03, 0.08, 0.12, 0.36, 0.62, house, 0.003)
    m.box(-0.078, -0.032, 0.12, 0.123, 0.44, 0.58, g)
    apex = (0.0, -0.02, 1.4)
    for sx in (-1, 1):
        m.rod((sx * 0.06, -0.12, 0.7), apex, 0.006, body, segments=4)
        m.rod((sx * 0.06, 0.06, 0.7), apex, 0.006, body, segments=4)
    foot, point = (0.0, 0.1, 0.35), (0.0, 0.5, 2.0)
    for sx in (-1, 1):
        m.rod((sx * 0.025, foot[1], foot[2]), (sx * 0.006, point[1], point[2]), 0.004, body, segments=4)
        m.rod((sx * 0.025, foot[1], foot[2] + 0.12), (sx * 0.006, point[1], point[2] + 0.03), 0.003, body, segments=4)
    for k in range(1, 8):
        f = k / 8
        p = tuple(foot[i] + (point[i] - foot[i]) * f for i in range(3))
        w = 0.025 * (1 - f) + 0.006 * f
        m.rod((-w, p[1], p[2]), (w, p[1], p[2] + 0.12 * (1 - f)), 0.0018, body, segments=4)
    for sx in (-1, 1):
        m.rod((sx * 0.004, apex[1], apex[2]), (sx * 0.006, point[1] - 0.01, point[2] + 0.03), 0.0016, rope, segments=4)
    m.tube_x(-0.01, 0.01, point[1], point[2], 0.012, dark, segments=10)
    # The bucket down on the ropes, and the drag ropes back to the house.
    bk = (0.0, 0.44, 0.35)
    m.rod((0.0, point[1] + 0.008, point[2] - 0.03), (bk[0], bk[1], bk[2] + 0.1), 0.0018, rope, segments=4)
    m.wedge([(-0.03, bk[1] - 0.03, bk[2] - 0.12), (0.03, bk[1] - 0.03, bk[2] - 0.12), (0.03, bk[1] + 0.035, bk[2] - 0.06), (-0.03, bk[1] + 0.035, bk[2] - 0.06)],
            [(-0.032, bk[1] - 0.035, bk[2] + 0.08), (0.032, bk[1] - 0.035, bk[2] + 0.08), (0.032, bk[1] + 0.04, bk[2] + 0.08), (-0.032, bk[1] + 0.04, bk[2] + 0.08)], dark)
    for sx in (-1, 1):
        m.rod((sx * 0.02, bk[1] - 0.03, bk[2]), (sx * 0.02, 0.12, 0.3), 0.0016, rope, segments=4)


def sand_conveyor_line():
    """The bench conveyor of before 1993, (x, y, z) in the first tile's terms: on the floor under the bucketwheel's
    discharge, then climbing the pit's +y side, steep enough to clear the benches, to a transfer house on the rim."""
    x, y0, y1, y2, z0 = 0.42, 0.2, 0.72, 1.34, 0.18
    k = 0.0
    for i in range(1, 41):
        y = y1 + (y2 - y1) * i / 40
        k = max(k, (sand_height(x, y) + 0.16 - z0) / (y - y1))
    return [(x, y0, z0), (x, y1, z0), (x, y2, z0 + k * (y2 - y1))]


def sand_pit(m, key="sand_pit", era=2):
    """An oil sands mine across four tiles, this one key in it: benches cut down through brown overburden into black oil
    sand to a wet floor, the haul road spiralling up them, and a dozer on the floor. era 2 from 1998 has an electric
    shovel at the face, a 797 hauler waiting by it and another climbing loaded; era 1 the same with the smaller rear
    dumps of before the 797; era 0 before 1993 has no trucks, as the mines then dug by bucketwheel onto conveyors: the
    bucketwheel at the face, the bench conveyor up the side to the rim, and a dragline up on the rim casting a windrow."""
    def hauler(mm, load=1.0):
        if era == 1:
            haul_truck(mm, load=("oil sand load", "#1e1a17"))
        else:
            heavy_hauler(mm, load=load)
    ox, oy = SAND_TILES[key]
    mats = [material("oil sand bench", "#5a5048", rough=1.0), material("oil sand face", "#1e1b18", rough=1.0),
            material("overburden face", "#8a7458", rough=1.0), material("sand haul road", "#7a7066", rough=1.0),
            material("overburden spoil", "#6e5e48", rough=1.0), material("pit floor", "#3a332e", rough=0.7)]
    terrain(m, sand_height, mats, sand_pick, n=180, ox=ox, oy=oy, wall=4)
    if era == 0 and key in ("sand_pit", "sand_pit_far"):
        steel = material("conveyor steel", "#5a5a56", metal=0.5, rough=0.5)
        belt = material("conveyor belt", "#2e2a26", rough=0.8)
        line = sand_conveyor_line()
        for a, b in zip(line, line[1:]):
            # Each tile draws its own length of the line.
            lo, hi = max(a[1], oy - 0.5), min(b[1], oy + 0.5)
            if hi <= lo:
                continue
            f0, f1 = (lo - a[1]) / (b[1] - a[1]), (hi - a[1]) / (b[1] - a[1])
            za, zb = a[2] + (b[2] - a[2]) * f0, a[2] + (b[2] - a[2]) * f1
            conveyor(m, (a[0] - ox, lo - oy, za), (a[0] - ox, hi - oy, zb), 0.05, steel, belt, legs=0.12,
                     hood=material("conveyor hood", "#8a8a84", metal=0.4, rough=0.5), load=material("oil sand on belt", "#1e1a17", rough=1.0))
        if key == "sand_pit":
            moved(m, bucketwheel, 0.26, 0.3, math.pi, z=0.02)
        else:
            x, y, z = line[-1]
            y -= oy
            m.box(x - 0.06, x + 0.06, y - 0.02, y + 0.1, 0.0, z + 0.45, material("transfer house", "#9a9890", metal=0.3, rough=0.6), 0.004)
            gable(m, x - 0.065, x + 0.065, y - 0.025, y + 0.105, z + 0.45, z + 0.55, material("conveyor hood", "#8a8a84", metal=0.4, rough=0.5),
                  material("transfer house", "#9a9890", metal=0.3, rough=0.6), along_x=False, over=0.006, thick=0.02)
            # The surge bin beside it that the plant draws from.
            m.upright(x - 0.16, y + 0.05, 0.0, z + 0.3, 0.06, 0.06, material("surge bin", "#7a7670", metal=0.4, rough=0.6), segments=20)
            m.lathe(x - 0.16, y + 0.05, z + 0.3, [(0.06, 0), (0.03, 0.12), (0.0, 0.14)], material("surge bin", "#7a7670", metal=0.4, rough=0.6), segments=20)
            m.rod((x - 0.06, y + 0.04, z + 0.35), (x - 0.1, y + 0.05, z + 0.42), 0.012, material("conveyor hood", "#8a8a84", metal=0.4, rough=0.5), segments=8)
    elif era == 0 and key == "sand_pit_corner":
        # The dragline up on the rim, its boom out over the pit, the windrow it has cast along the rim behind.
        moved(m, dragline, 0.18, 0.18, 0.75 * math.pi, z=1.4)
        for k, (x, y) in enumerate(((0.36, -0.02), (0.38, 0.16), (0.3, 0.34), (0.12, 0.4))):
            moved(m, lambda mm, k=k: heap(mm, 0, 0, 0.09, 0.42, "#1e1a17", 420 + k, "oil sand"), x, y, z=1.4)
    elif key == "sand_pit":
        moved(m, lambda mm: shovel(mm, "#d6d0c2"), 0.3, 0.3, 0.75 * math.pi, z=0.02, scale=1.25)
    elif key == "sand_pit_far":
        moved(m, lambda mm: hauler(mm, load=0.3), 0.3, -0.3, 0.0, z=0.02)
    elif key == "sand_pit_corner":
        x, y, h, turn, pitch = sand_road(0.3)
        moved(m, hauler, x - ox, y - oy, turn, z=h + 0.02, pitch=pitch)
    else:
        _dozer(m, -0.22, 0.26, 0.02, turn=2.2)
        puddle = material("pit water", "#3a4a52", rough=0.15)
        for x, y, r in ((-0.34, 0.38, 0.06), (-0.27, 0.34, 0.045), (-0.38, 0.3, 0.035)):
            m.upright(x, y, 0.02, 0.035, r, r, puddle, segments=16)


def upgrader(m):
    """A length of the upgrader, Suncor or Syncrude style: a delayed coker in its tall steel structure with four coke
    drums and the cutting derricks on top, coke under them, the fractionator and the fired heater, the pipe rack along x
    joining the upgrader's tiles, a terraced block of poured sulphur, and two tall stacks."""
    ground(m, "#5a5652", "plant gravel")
    shell = material("column steel", "#b8b6b0", metal=0.6, rough=0.4)
    steel = material("upgrader steelwork", "#5a5650", metal=0.5, rough=0.5)
    deck = material("refinery grating", "#4a4844", metal=0.4, rough=0.6)
    rail = material(*REFINERY_RAIL, metal=0.3, rough=0.5)
    pipe = material("refinery pipe", "#7a7a76", metal=0.6, rough=0.4)
    lagged = material("lagged pipe", "#c8c8c4", metal=0.7, rough=0.35)
    rust = material("rust pipe", "#7a4e36", metal=0.4, rough=0.6)
    # The coker structure: columns, a beam round each floor, braced faces and the top deck, the drums standing through.
    fx0, fx1, fy0, fy1 = -0.46, -0.08, -0.47, -0.13
    top = 5.6
    for x in (fx0, (fx0 + fx1) / 2, fx1):
        for y in (fy0, fy1):
            m.box(x - 0.01, x + 0.01, y - 0.01, y + 0.01, 0, top, steel)
    for z in (1.4, 2.8, 4.2):
        for y in (fy0, fy1):
            m.box(fx0, fx1, y - 0.008, y + 0.008, z - 0.06, z, steel)
        for x in (fx0, fx1):
            m.box(x - 0.008, x + 0.008, fy0, fy1, z - 0.06, z, steel)
        slab(m, [(fx0, fy0, z - 0.04), (fx1, fy0, z - 0.04), (fx1, fy0 + 0.05, z - 0.04), (fx0, fy0 + 0.05, z - 0.04)], 0.03, deck)
    for z0, z1 in ((0.0, 1.4), (1.4, 2.8), (2.8, 4.2), (4.2, top)):
        for y in (fy0, fy1):
            m.rod((fx0, y, z0), ((fx0 + fx1) / 2, y, z1), 0.003, steel, segments=4)
            m.rod((fx1, y, z0), ((fx0 + fx1) / 2, y, z1), 0.003, steel, segments=4)
    m.box(fx0 - 0.01, fx1 + 0.01, fy0 - 0.01, fy1 + 0.01, top, top + 0.06, deck)
    for a, b in (((fx0, fy0), (fx1, fy0)), ((fx1, fy0), (fx1, fy1)), ((fx1, fy1), (fx0, fy1)), ((fx0, fy1), (fx0, fy0))):
        m.rod((a[0], a[1], top + 0.28), (b[0], b[1], top + 0.28), 0.0018, rail, segments=4)
    drums = [(-0.37, -0.385), (-0.17, -0.385), (-0.37, -0.215), (-0.17, -0.215)]
    for x, y in drums:
        m.lathe(x, y, 0.95, [(0.0, 0), (0.02, 0.0), (0.065, 0.45)], shell, segments=20)
        m.upright(x, y, 1.4, top, 0.065, 0.065, shell, segments=20)
        for z in (2.2, 3.5, 4.8):
            m.upright(x, y, z, z + 0.05, 0.069, 0.069, steel, segments=20)
        m.lathe(x, y, top + 0.06, [(0.06, 0), (0.045, 0.2), (0.02, 0.28), (0.0, 0.3)], shell, segments=20)
        # The cutting derrick over the drum.
        for dx, dy in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
            m.rod((x + dx * 0.045, y + dy * 0.045, top + 0.06), (x + dx * 0.012, y + dy * 0.012, 7.6), 0.0035, steel, segments=4)
        for z, w in ((6.3, 0.034), (7.0, 0.022)):
            for (ax, ay), (bx, by) in (((-1, -1), (1, -1)), ((1, -1), (1, 1)), ((1, 1), (-1, 1)), ((-1, 1), (-1, -1))):
                m.rod((x + ax * w, y + ay * w, z), (x + bx * w, y + by * w, z), 0.0025, steel, segments=4)
        m.box(x - 0.016, x + 0.016, y - 0.016, y + 0.016, 7.6, 7.68, steel)
    heap(m, -0.27, -0.3, 0.13, 0.35, "#141414", 71, "petroleum coke")
    # The stair tower up the +x side of the structure.
    m.box(fx1 + 0.01, fx1 + 0.07, fy0, fy0 + 0.08, 0, top + 0.3, material("stair tower cladding", "#8a8680", metal=0.4, rough=0.55), 0.003)
    # The fractionator and the vapour lines from the drum heads over to it.
    cx, cy = 0.07, -0.33
    column(m, cx, cy, 0.058, 6.0, shell, steel, deck, rail, ladder_a=math.pi * 0.3)
    for x, y in drums[1::2]:
        m.rod((x, y, top + 0.3), (x + 0.06, y, top + 0.45), 0.012, lagged, segments=8)
        m.rod((x + 0.06, y, top + 0.45), (cx - 0.04, cy, 4.6), 0.012, lagged, segments=8)
    # The fired heater with its two short stacks.
    hx0, hx1, hy0, hy1 = 0.22, 0.46, -0.47, -0.22
    heat = material("heater casing", "#8a8a86", metal=0.4, rough=0.55)
    for x in (hx0 + 0.02, hx1 - 0.02):
        for y in (hy0 + 0.02, hy1 - 0.02):
            m.box(x - 0.01, x + 0.01, y - 0.01, y + 0.01, 0, 0.4, steel)
    clad_box(m, hx0, hx1, hy0, hy1, 0.4, 2.2, heat, steel, gap=0.05)
    m.box(hx0 + 0.04, hx1 - 0.04, hy0 + 0.05, hy1 - 0.05, 2.2, 2.8, heat)
    for x in (hx0 + 0.07, hx1 - 0.07):
        m.upright(x, (hy0 + hy1) / 2, 2.8, 4.2, 0.026, 0.022, heat, segments=14)
        m.upright(x, (hy0 + hy1) / 2, 4.1, 4.2, 0.028, 0.028, steel, segments=14)
    m.rod((hx0, -0.3, 1.2), (cx + 0.06, cy, 1.0), 0.012, rust, segments=8)
    # The pipe rack along x, joining the upgrader's tiles.
    ry, tiers = 0.02, [1.3, 1.9]
    moved(m, lambda mm: pipe_rack(mm, 0.0, -0.5, 0.5, 0.14, tiers, [(0, -0.045, 0.014, pipe), (0, -0.01, 0.018, lagged), (0, 0.03, 0.012, rust),
                                                                   (1, -0.04, 0.012, lagged), (1, 0.0, 0.016, pipe), (1, 0.04, 0.01, material("gas pipe", "#c8a828", metal=0.3, rough=0.5))],
                                     steel), 0.0, ry, math.pi / 2)
    m.rod((cx, cy + 0.06, 1.0), (cx, ry - 0.05, tiers[0] + 0.05), 0.012, pipe, segments=8)
    m.rod((cx, cy + 0.06, 5.8), (cx + 0.02, ry - 0.04, tiers[1] + 0.05), 0.01, pipe, segments=8)
    m.rod((0.34, hy1, 1.6), (0.34, ry - 0.05, tiers[0] + 0.05), 0.012, lagged, segments=8)
    # The sulphur block, poured in terraces.
    sulphur = material("sulphur", "#e6cc3a", rough=0.9)
    for k, (z0, z1) in enumerate(((0.0, 0.22), (0.22, 0.4), (0.4, 0.54))):
        i = 0.025 * k
        m.box(-0.46 + i, 0.06 - i, 0.16 + i, 0.47 - i, z0, z1, sulphur)
    # A horizontal drum on saddles and the two stacks.
    for x in (0.18, 0.38):
        m.box(x - 0.012, x + 0.012, 0.12, 0.2, 0, 0.3, material("saddle", "#9a948a", rough=0.9))
    m.tube_x(0.14, 0.42, 0.16, 0.33, 0.045, shell, segments=18)
    band = material("stack red band", "#a83a2a", rough=0.8)
    for x, y, h, r in ((0.36, 0.37, 9.0, 0.045), (0.16, 0.4, 7.2, 0.035)):
        m.upright(x, y, 0, 0.5, r * 1.8, r * 1.8, material("stack footing", "#9a948a", rough=0.9), segments=16)
        stack(m, x, y, 0.5, h, r, material("upgrader stack", "#b4b0a8", metal=0.4, rough=0.5), band)
        ring_deck(m, x, y, h - 1.2, r * 1.1, 0.02, deck, rail)
        column_ladder(m, x, y, r * 1.1, math.pi, 0.5, h - 1.2, rail)


def tailings_edge(x, y):
    """How far x, y is in from the outside of the tailings pond's two tiles."""
    return min(0.5 - abs(x), y + 0.5, 1.5 - y)


def tailings_beach(x, y):
    """How far down the beach x, y is from the dyke at -y, 0 at its top to 1 out under the water."""
    return (y + 0.4) / (0.42 + 0.06 * math.sin(7 * x + 1))


def tailings_height(x, y):
    """The pond's ground: the sand dyke round it, the pond floor and the beach the slurry has built at its -y end."""
    d = tailings_edge(x, y)
    if d < 0.06:
        h = 0.7 * max(0.0, d) / 0.06
    elif d < 0.1:
        h = 0.7
    else:
        h = max(0.2, 0.7 - 0.5 * (d - 0.1) / 0.06)
    b = tailings_beach(x, y)
    if b < 1.2:
        h = max(h, 0.66 - 0.4 * max(0.0, b) + 0.008 * bumps(x, y, 3.0))
    return h


def tailings_pick(x, y, slope):
    """The dyke's sand faces, its outer slope grassed over, the crest road, the wet beach and the slurry running over it."""
    d = tailings_edge(x, y)
    if d < 0.06:
        return 4
    if d < 0.1:
        return 3
    b = tailings_beach(x, y)
    if 0.0 < b < 0.6:
        fan = abs(x + 0.24 + 0.15 * b + 0.03 * math.sin(b * 20)) < 0.02 + 0.06 * b
        return 5 if fan else 2
    return 1 if slope > 3.0 else 0


def tailings_pond(m, far=False):
    """An oil sands tailings pond across two tiles, this one and far, its +y neighbour: a sand dyke round it with a road
    on its crest and its outer slope grassed, brown water, and the beach at the -y end built by the slurry from the
    discharge pipe along the dyke; a dozer works the beach and a pump barge takes water back out at the far end."""
    mats = [material("pond sand", "#b8a280", rough=1.0), material("dyke sand face", "#a8946e", rough=1.0),
            material("tailings beach", "#c8b898", rough=0.8), material("dyke road", "#8a8070", rough=1.0),
            material("reclaimed grass", "#6a7448", rough=1.0), material("tailings slurry", "#5a5244", rough=0.3)]
    oy = 1.0 if far else 0.0
    terrain(m, tailings_height, mats, tailings_pick, oy=oy, wall=4)
    water = material("tailings water", "#4c5250", rough=0.08)
    m.box(-0.38, 0.38, -0.37 if not far else -0.5, 0.5 if not far else 0.37, 0.3, 0.45, water)
    pipe = material("tailings pipe", "#4a4c4e", metal=0.4, rough=0.6)
    sleeper = material("pipe sleeper", "#9a948a", rough=0.9)
    px, pz = -0.425, 0.7 + 0.025 / rig.RISE
    if far:
        m.tube(-0.5, 0.5, px, pz, 0.025, pipe, segments=14)
        for y in (-0.3, 0.0, 0.3):
            m.box(px - 0.035, px + 0.035, y - 0.01, y + 0.01, 0.68, 0.72, sleeper)
        # The reclaim barge out on the water, its pipeline on floats back to the dyke.
        steel = material("barge steel", "#5a6066", metal=0.4, rough=0.6)
        m.box(0.0, 0.14, 0.04, 0.14, 0.4, 0.52, steel, 0.003)
        m.box(0.02, 0.08, 0.06, 0.12, 0.52, 0.72, material("barge house", "#c8c2b0", rough=0.7), 0.003)
        m.box(0.018, 0.082, 0.058, 0.122, 0.72, 0.75, steel)
        for k in range(5):
            x = 0.16 + k * 0.045
            m.tube_x(x, x + 0.03, 0.09, 0.45, 0.012, material("pipe float", "#d8a428", rough=0.6), segments=10)
        m.tube_x(0.14, 0.4, 0.09, 0.5, 0.008, pipe, segments=8)
        m.rod((0.4, 0.09, 0.5), (0.45, 0.09, 0.76), 0.008, pipe, segments=8)
        return
    # The discharge pipe along the -x dyke, turning in over the beach to its spigot.
    m.tube(-0.3, 0.5, px, pz, 0.025, pipe, segments=14)
    for y in (-0.2, 0.1, 0.4):
        m.box(px - 0.035, px + 0.035, y - 0.01, y + 0.01, 0.68, 0.72, sleeper)
    m.rod((px, -0.3, pz), (-0.25, -0.34, 0.74), 0.025, pipe, segments=14)
    m.rod((-0.25, -0.34, 0.74), (-0.23, -0.31, 0.62), 0.02, pipe, segments=12)
    m.box(-0.27, -0.21, -0.36, -0.3, 0.56, 0.6, sleeper)
    _dozer(m, 0.18, -0.3, 0.58, turn=-0.4)


PARTS = {
    "bunkhouse": bunkhouse, "log_pile": log_pile, "camp_shed": camp_shed,
    "sawmill": sawmill, "log_deck": log_deck, "burner": burner, "lumber_stack": lumber_stack,
    "barn": barn, "silo": silo, "wheat_field": wheat_field,
    "mill_building": mill_building, "mill_silos": mill_silos, "mill_yard": mill_yard, "mill_yard_team": lambda m: mill_yard(m, team=True),
    "headframe": headframe, "headframe_wood": lambda m: headframe(m, 0), "headframe_steel": lambda m: headframe(m, 1), "tipple": tipple, "coal_heap": coal_heap, "mine_shed": mine_shed,
    "open_pit": open_pit, "open_pit_far": lambda m: open_pit(m, far=True), "ore_crusher": ore_crusher, "ore_heap": ore_heap,
    "open_pit_steam": lambda m: open_pit(m, era=0), "open_pit_far_rail": lambda m: open_pit(m, far=True, era=0),
    "open_pit_far_euclid": lambda m: open_pit(m, far=True, era=1),
    "blast_furnace": blast_furnace, "stacks": bof_shop, "stacks_open_hearth": open_hearth, "steel_shed": steel_shed, "slag_heap": slag_heap, "steel_yard": steel_yard,
    "factory_shed": factory_shed, "factory_chimney": factory_chimney, "loading_dock": loading_dock, "factory_yard": factory_yard,
    "factory_yard_box": lambda m: factory_yard(m, 1940), "factory_yard_tractor": lambda m: factory_yard(m, 1970),
    "pumpjack": pumpjack, "oil_tank": oil_tank,
    "refinery_columns": refinery_columns, "refinery_tanks": refinery_tanks, "flare": flare,
    "ranch_barn": ranch_barn, "corral": corral,
    "packing_plant": packing_plant, "packing_wing": packing_wing, "stockyard": stockyard,
    "digester": digester, "paper_machine": paper_machine, "chip_pile": chip_pile, "printing_works": printing_works,
    "elevator": elevator, "elevator_annex": elevator_annex, "concrete_terminal": concrete_terminal, "terminal_annex": terminal_annex,
    "wharf": wharf, "crane": crane, "terminal_elevator": terminal_elevator, "warehouse": warehouse,
    "fish_wharf": fish_wharf, "fish_shed": fish_shed, "boat_shed": boat_shed, "cannery": cannery,
    "potash_mill": potash_mill, "tailings": tailings, "rock_dump": rock_dump, "nickel_heap": nickel_heap,
    "superstack": superstack, "superstack_brick": lambda m: superstack(m, brick_stacks=True), "roaster": roaster, "smelter_shed": smelter_shed,
    "dam": dam, "powerhouse": powerhouse, "potline": potline, "potline_scrubber": lambda m: potline(m, scrubber=True),
    "sand_pit": sand_pit, "sand_pit_far": lambda m: sand_pit(m, "sand_pit_far"), "sand_pit_side": lambda m: sand_pit(m, "sand_pit_side"),
    "sand_pit_corner": lambda m: sand_pit(m, "sand_pit_corner"), "upgrader": upgrader,
    "tailings_pond": tailings_pond, "tailings_pond_far": lambda m: tailings_pond(m, far=True),
    "green_chain": green_chain, "paper_wet_end": paper_wet_end, "paper_dry_end": paper_dry_end, "press_hall": press_hall,
    "sand_pit_far_early": lambda m: sand_pit(m, "sand_pit_far", era=1),
    "sand_pit_corner_early": lambda m: sand_pit(m, "sand_pit_corner", era=1),
    "sand_pit_bucketwheel": lambda m: sand_pit(m, "sand_pit", era=0),
    "sand_pit_far_bucketwheel": lambda m: sand_pit(m, "sand_pit_far", era=0),
    "sand_pit_corner_dragline": lambda m: sand_pit(m, "sand_pit_corner", era=0),
}


def part(m, key):
    PARTS[key](m)
