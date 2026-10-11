"""What every drawing shares: sizes, colours, the view, light, textures and solids.

Sprites are drawn in view coordinates as the camera sees them: u runs down to
the right of the screen, v down to the left, h up in steps. Everything is drawn
at SS times the sharpest level and shrunk after, so edges come out smooth.

Every sprite is drawn on a cell of the same size, anchored on its tile's top
corner at the height it stands on: (HW, TOP) at the sharpest level.
"""
import math
import random

from PIL import Image, ImageDraw

# The sharpest level: half a tile's width, half its height and a step of height, in pixels.
HW = 128
HH = HW // 2
STEP = HW // 4
# Room above a tile's top corner for tall things: trees, roofs, a raised corner.
TOP = 3 * HW
CELL_W = 2 * HW
CELL_H = TOP + 2 * HH + STEP
# Drawn this many times bigger, then shrunk.
SS = 2
DHW, DHH, DSTEP, DTOP = HW * SS, HH * SS, STEP * SS, TOP * SS
DCELL = (CELL_W * SS, CELL_H * SS)


def c(h, a=255):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5)) + (a,)


def shade(col, f):
    return tuple(max(0, min(255, int(v * f))) for v in col[:3]) + (col[3],)


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)


def proj(u, v, h):
    """A point in the tile, u and v from 0 to 1 and h in steps above the anchor, to drawing pixels."""
    return (DHW + (u - v) * DHW, DTOP + (u + v) * DHH - h * DSTEP)


def cell():
    return Image.new("RGBA", DCELL)


# ---- light ----------------------------------------------------------------------

# Light from the top left of the screen and above, in (u, v, up).
LIGHT = (-1.0, -0.35, 1.6)
_LL = math.sqrt(sum(x * x for x in LIGHT))
# A step of height in tile widths, for shading the land.
STEP_RISE = 0.5
# A step of height in tile widths for models, so wheels are round and boilers in proportion.
MODEL_RISE = 0.22
# Towards the camera, in (u, v, steps): moving along it keeps a point where it is on screen.
VIEW = (1.0, 1.0, 4.0)


def lit(n, strength=0.4, low=0.55, high=1.1, rise=STEP_RISE):
    """How bright a face is, 1 for flat ground. n is its normal in (u, v, steps); strength is how much slope tells."""
    # Steps are rise tiles high, so the normal's up part scales the other way.
    nx, ny, nz = n[0], n[1], n[2] / rise
    ln = math.sqrt(nx * nx + ny * ny + nz * nz) or 1
    d = (nx * LIGHT[0] + ny * LIGHT[1] + nz * LIGHT[2]) / (ln * _LL)
    flat = LIGHT[2] / _LL
    return max(low, min(high, 1 + strength * (d / flat - 1)))


# ---- textures -------------------------------------------------------------------

def noise_layer(cols, seed, weights=None, block=SS * 2):
    """A cell of speckle in cols, in blocks so it reads at every level."""
    rng = random.Random(seed)
    w, h = DCELL
    small = Image.new("RGBA", (w // block, h // block))
    px = small.load()
    n = len(cols)
    weights = weights or [0.7] + [0.3 / (n - 1)] * (n - 1)
    cum = [sum(weights[:i + 1]) for i in range(n)]
    for y in range(small.height):
        for x in range(small.width):
            r = rng.random()
            px[x, y] = cols[next(i for i, cv in enumerate(cum) if r <= cv)]
    return small.resize(DCELL, Image.NEAREST)


def masked(layer, polygon, f=1.0, grow=0):
    """layer shaded by f, cut to polygon, grown by grow drawing pixels all round."""
    mask = Image.new("L", DCELL, 0)
    d = ImageDraw.Draw(mask)
    if grow:
        d.polygon(polygon, fill=255, outline=255, width=grow * 2)
    else:
        d.polygon(polygon, fill=255)
    out = Image.new("RGBA", DCELL)
    src = layer if f == 1.0 else Image.eval(layer.convert("RGB"), lambda v: max(0, min(255, int(v * f)))).convert("RGBA")
    out.paste(src, (0, 0), mask)
    return out


# ---- solids ---------------------------------------------------------------------
#
# A model is a list of faces: (points, colour, outward normal, outline). Points
# are (u, v, h). Faces turned from the camera are left out and the rest drawn
# far to near.

OUTLINE = c("#2e2a25")


def face(pts, col, normal, outline=OUTLINE):
    return (pts, col, normal, outline)


def poly_normal(pts, centre):
    (ax, ay, az), (bx, by, bz), (cx, cy, cz) = pts[0], pts[1], pts[2]
    ux, uy, uz = bx - ax, by - ay, bz - az
    vx, vy, vz = cx - ax, cy - ay, cz - az
    n = (uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx)
    mid = [sum(p[i] for p in pts) / len(pts) for i in range(3)]
    if sum(n[i] * (mid[i] - centre[i]) for i in range(3)) < 0:
        n = tuple(-x for x in n)
    return n


def prism(base, h0, h1, col, top=None, outline=OUTLINE, sides=None):
    """A solid from a convex base in (u, v), h0 to h1 steps high."""
    n = len(base)
    centre = (sum(p[0] for p in base) / n, sum(p[1] for p in base) / n, (h0 + h1) / 2)
    out = []
    for i in range(n):
        (au, av), (bu, bv) = base[i], base[(i + 1) % n]
        pts = [(au, av, h0), (bu, bv, h0), (bu, bv, h1), (au, av, h1)]
        out.append(face(pts, (sides[i] if sides else col), poly_normal(pts, centre), outline))
    pts = [(u, v, h1) for u, v in base]
    out.append(face(pts, top or col, (0, 0, 1), outline))
    return out


def box(u0, u1, v0, v1, h0, h1, col, top=None, outline=OUTLINE):
    return prism([(u0, v0), (u1, v0), (u1, v1), (u0, v1)], h0, h1, col, top, outline)


def gloss(col, f):
    """col lit by f: darker below 1, and above it blended towards white, so black paint still shows its shape."""
    if f <= 1:
        return shade(col, f)
    return mix(col, (255, 255, 255, 255), min(1.0, (f - 1) * 0.9))


def draw(img, faces, shift=(0.0, 0.0), strength=0.5, rise=MODEL_RISE):
    d = ImageDraw.Draw(img)
    shown = []
    for pts, col, n, outline in faces:
        if n[0] * VIEW[0] + n[1] * VIEW[1] + n[2] * VIEW[2] <= 1e-6:
            continue
        mid = [sum(p[i] for p in pts) / len(pts) for i in range(3)]
        depth = mid[0] * VIEW[0] + mid[1] * VIEW[1] + mid[2] * VIEW[2]
        shown.append((depth, pts, gloss(col, lit(n, strength, low=0.6, high=1.32, rise=rise)), outline))
    for _, pts, col, outline in sorted(shown, key=lambda f: f[0]):
        poly = [(x + shift[0], y + shift[1]) for x, y in (proj(*p) for p in pts)]
        if outline is None:
            d.polygon(poly, fill=col)
        else:
            d.polygon(poly, fill=col, outline=outline, width=SS)


class Frame:
    """A model's own coordinates: a along its length, s across it, h up, turned to heading and set on a point."""

    def __init__(self, heading, cu=0.5, cv=0.5):
        self.ca = math.cos(heading)
        self.sa = math.sin(heading)
        self.cu = cu
        self.cv = cv

    def p(self, a, s, h):
        return (self.cu + a * self.ca - s * self.sa, self.cv + a * self.sa + s * self.ca, h)

    def n(self, na, ns, nh):
        return (na * self.ca - ns * self.sa, na * self.sa + ns * self.ca, nh)

    def box(self, a0, a1, s0, s1, h0, h1, col, top=None, outline=OUTLINE, ends=None):
        """A box along the model. ends colours its two ends, back then front."""
        corners = [(a0, s0), (a1, s0), (a1, s1), (a0, s1)]
        out = []
        normals = [(0, -1), (1, 0), (0, 1), (-1, 0)]
        for i in range(4):
            (aa, sa_), (ab, sb) = corners[i], corners[(i + 1) % 4]
            pts = [self.p(aa, sa_, h0), self.p(ab, sb, h0), self.p(ab, sb, h1), self.p(aa, sa_, h1)]
            col_i = col
            if ends and i == 1:
                col_i = ends[1]
            if ends and i == 3:
                col_i = ends[0]
            out.append(face(pts, col_i, self.n(normals[i][0], normals[i][1], 0), outline))
        out.append(face([self.p(a, s, h1) for a, s in corners], top or col, (0, 0, 1), outline))
        return out

    def cylinder(self, a0, a1, s, h, r, col, n=14, outline=None, caps=True, cap=None):
        """A cylinder lying along the model, centred at s across and h up, of radius r (tiles across, steps up)."""
        out = []
        rs = r
        # The same radius in steps.
        rh = r / MODEL_RISE
        for i in range(n):
            t0 = 2 * math.pi * i / n
            t1 = 2 * math.pi * (i + 1) / n
            s0, h0 = s + rs * math.cos(t0), h + rh * math.sin(t0)
            s1, h1 = s + rs * math.cos(t1), h + rh * math.sin(t1)
            tm = (t0 + t1) / 2
            pts = [self.p(a0, s0, h0), self.p(a1, s0, h0), self.p(a1, s1, h1), self.p(a0, s1, h1)]
            # A sheen along the top, a little to one side, as paint and metal catch the light.
            sheen = 0.3 if math.pi * 0.45 < tm < math.pi * 0.75 else 0.0
            out.append(face(pts, mix(col, (255, 255, 255, 255), sheen) if sheen else col, self.n(0, math.cos(tm), math.sin(tm) * MODEL_RISE), outline))
        if caps:
            for a, sign in ((a0, -1), (a1, 1)):
                ring = [self.p(a, s + rs * math.cos(2 * math.pi * i / n), h + rh * math.sin(2 * math.pi * i / n)) for i in range(n)]
                out.append(face(ring, cap or col, self.n(sign, 0, 0), OUTLINE))
        return out

    def upright(self, a, s, h0, h1, r0, r1, col, n=12, outline=None, top=None):
        """An upright cylinder or cone at a, s from h0 to h1, radius r0 at the foot and r1 at the top."""
        out = []
        for i in range(n):
            t0 = 2 * math.pi * i / n
            t1 = 2 * math.pi * (i + 1) / n
            tm = (t0 + t1) / 2
            pts = [
                self.p(a + r0 * math.cos(t0), s + r0 * math.sin(t0), h0),
                self.p(a + r0 * math.cos(t1), s + r0 * math.sin(t1), h0),
                self.p(a + r1 * math.cos(t1), s + r1 * math.sin(t1), h1),
                self.p(a + r1 * math.cos(t0), s + r1 * math.sin(t0), h1),
            ]
            slope = (r0 - r1) / max(0.01, (h1 - h0))
            out.append(face(pts, col, self.n(math.cos(tm), math.sin(tm), slope), outline))
        if r1 > 0.001:
            out.append(face([self.p(a + r1 * math.cos(2 * math.pi * i / n), s + r1 * math.sin(2 * math.pi * i / n), h1) for i in range(n)], top or col, (0, 0, 1), OUTLINE))
        return out

    def wheel(self, a, s, r, col, hub=None, n=16):
        """A wheel standing on the rail at a, its face at s across the model, facing out to that side."""
        rh = r / MODEL_RISE
        h = rh
        side = 1 if s >= 0 else -1
        ring = [self.p(a + r * math.cos(2 * math.pi * i / n), s, h + rh * math.sin(2 * math.pi * i / n)) for i in range(n)]
        out = [face(ring, col, self.n(0, side, 0), OUTLINE)]
        if hub:
            hr = r * 0.35
            hrh = rh * 0.35
            s2 = s + side * 0.002
            out.append(face([self.p(a + hr * math.cos(2 * math.pi * i / n), s2, h + hrh * math.sin(2 * math.pi * i / n)) for i in range(n)], hub, self.n(0, side, 0), None))
        return out

    def plate(self, pts_ash, col, normal_ash, outline=OUTLINE):
        """A flat face given in the model's own coordinates, with its normal in them too."""
        return [face([self.p(*q) for q in pts_ash], col, self.n(*normal_ash), outline)]
