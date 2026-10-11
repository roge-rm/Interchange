"""Ground and trees, from Canadian country: meadow, prairie, boreal forest floor,
Shield granite, tundra and snow, with beach and water; spruce, pine, fir,
maple, birch and aspen."""
import math
import random
import zlib
from functools import lru_cache

from PIL import Image, ImageDraw

from base import (DCELL, DHH, DHW, DSTEP, SS, STEP_RISE, Frame, c, cell, draw, lit, masked, mix, noise_layer, proj, shade)

# ---- ground -----------------------------------------------------------------------

# Each kind's speckle colours and how many looks it has. The game's sim/Land.kt kinds come in this order.
GROUND = {
    "meadow": ([c("#5f9a3e"), c("#548c36"), c("#6aa646"), c("#4c7f31")], 3),
    "prairie": ([c("#aea463"), c("#9d9658"), c("#bdb06e"), c("#8c8a50")], 2),
    "forest": ([c("#3f5a2e"), c("#4a3f2a"), c("#56703a"), c("#34492a")], 2),
    "rock": ([c("#9c918b"), c("#b3a59d"), c("#857a74"), c("#a89a8f")], 2),
    "tundra": ([c("#7d7a52"), c("#6b6a48"), c("#8f8c60"), c("#7a5f44")], 2),
    "snow": ([c("#eef3f7"), c("#dfe8ef"), c("#f9fbfd"), c("#d3dee8")], 1),
    "sand": ([c("#d8c690"), c("#c9b47e"), c("#e4d5a3"), c("#cdbb86")], 1),
}
WATER = c("#3a6fb0")
WATER_DEEP = c("#34649f")
RIPPLE = c("#5f93cf")

# Corners in shape bit order: top (0, 0), right (1, 0), bottom (1, 1), left (0, 1).
CORNERS = [(0, 0), (1, 0), (1, 1), (0, 1)]


def offsets(shape):
    return [(shape >> i) & 1 for i in range(4)]


def height_at(offs, u, v):
    t, r, b, l = offs
    return (t * (1 - u) + r * u) * (1 - v) + (l * (1 - u) + b * u) * v


def triangles(shape):
    """The top as two triangles of (u, v, h), folded along whichever diagonal has its ends level."""
    o = offsets(shape)
    p = [(u, v, o[i]) for i, (u, v) in enumerate(CORNERS)]
    if o[1] == o[3]:
        return [(p[0], p[1], p[3]), (p[1], p[2], p[3])]
    return [(p[0], p[1], p[2]), (p[0], p[2], p[3])]


def tri_normal(a, b, c_):
    ux, uy, uz = (b[i] - a[i] for i in range(3))
    vx, vy, vz = (c_[i] - a[i] for i in range(3))
    n = (uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx)
    return n if n[2] > 0 else tuple(-x for x in n)


@lru_cache(maxsize=None)
def texture(kind, variant):
    """A cell of a kind's ground, with its details drawn in."""
    cols, _ = GROUND[kind]
    seed = zlib.crc32(f"{kind}{variant}".encode()) & 0xffff
    img = noise_layer(cols, seed, [0.55, 0.2, 0.15, 0.1])
    d = ImageDraw.Draw(img)
    rng = random.Random(seed + 1)
    w, h = DCELL
    if kind == "meadow":
        for _ in range(260):
            x, y = rng.randrange(w), rng.randrange(h)
            d.line([x, y, x + rng.randint(-2, 2), y - rng.randint(4, 9)], shade(cols[3], 0.9), width=SS)
            d.point([x, y - 9], fill=cols[2])
        for _ in range(18):
            x, y = rng.randrange(w), rng.randrange(h)
            d.ellipse([x, y, x + 3, y + 3], fill=rng.choice([c("#f4eecf"), c("#f0d24a"), c("#d9a6d8")]))
    elif kind == "prairie":
        for _ in range(420):
            x, y = rng.randrange(w), rng.randrange(h)
            d.line([x, y, x + rng.randint(2, 6), y - rng.randint(5, 11)], rng.choice([cols[1], cols[2], cols[3]]), width=SS)
    elif kind == "forest":
        for _ in range(300):
            x, y = rng.randrange(w), rng.randrange(h)
            d.line([x, y, x + rng.randint(-4, 4), y + rng.randint(-2, 2)], c("#6b4e30"), width=1)
        for _ in range(40):
            x, y = rng.randrange(w), rng.randrange(h)
            r = rng.randint(4, 9)
            d.ellipse([x - r, y - r // 2, x + r, y + r // 2], fill=c("#5c7a3e"))
    elif kind == "rock":
        for _ in range(26):
            x, y = rng.randrange(w), rng.randrange(h)
            pts = [(x, y)]
            for _ in range(rng.randint(2, 5)):
                x += rng.randint(-14, 14)
                y += rng.randint(-6, 6)
                pts.append((x, y))
            d.line(pts, c("#5e5550"), width=SS)
        for _ in range(60):
            x, y = rng.randrange(w), rng.randrange(h)
            r = rng.randint(3, 7)
            d.ellipse([x - r, y - r // 2, x + r, y + r // 2], fill=rng.choice([c("#8f9a5a"), c("#b0a84a"), c("#c2b8a8")]))
    elif kind == "tundra":
        for _ in range(140):
            x, y = rng.randrange(w), rng.randrange(h)
            r = rng.randint(2, 5)
            d.ellipse([x - r, y - r // 2, x + r, y + r // 2], fill=rng.choice([c("#5e6a3a"), c("#8a4e36"), c("#6f7a44")]))
    elif kind == "snow":
        for _ in range(40):
            x, y = rng.randrange(w), rng.randrange(h)
            d.line([x, y, x + rng.randint(10, 30), y + rng.randint(-3, 3)], c("#cfdbe6"), width=SS)
    elif kind == "sand":
        for _ in range(60):
            x, y = rng.randrange(w), rng.randrange(h)
            d.point([x, y], fill=c("#a8977a"))
    return img


def ground(kind, variant, shape):
    img = cell()
    layer = texture(kind, variant)
    for tri in triangles(shape):
        # Hills read without striping the land: a little over a quarter of what a real slope would show.
        f = lit(tri_normal(*tri), strength=0.28, low=0.84, high=1.12)
        img.alpha_composite(masked(layer, [proj(*p) for p in tri], f, grow=1))
    return img


def water():
    img = cell()
    poly = [proj(u, v, 0) for u, v in CORNERS]
    ImageDraw.Draw(img).polygon(poly, fill=WATER, outline=WATER, width=2)
    rng = random.Random(3000)
    d = ImageDraw.Draw(img)
    for _ in range(10):
        u, v = rng.uniform(0.15, 0.8), rng.uniform(0.15, 0.8)
        x, y = proj(u, v, 0)
        L = rng.randrange(14, 30)
        d.line([x, y, x + L, y], RIPPLE, width=SS)
        d.line([x + 4, y + SS, x + L - 4, y + SS], WATER_DEEP, width=SS)
    return img


# ---- trees -------------------------------------------------------------------------

TREES = ["spruce", "pine", "fir", "maple", "birch", "aspen"]
TREE_LOOKS = 3


def sphere(d, centre, r, col):
    """A lit ball: rings from shadow to light, the light towards the top left."""
    x, y = centre
    for k in range(6):
        t = k / 5
        rr = r * (1 - 0.55 * t)
        ox = -r * 0.25 * t
        oy = -r * 0.3 * t
        d.ellipse([x + ox - rr, y + oy - rr, x + ox + rr, y + oy + rr], fill=shade(col, 0.72 + 0.45 * t))


def tree(kind, look):
    rng = random.Random(zlib.crc32(f"{kind}{look}".encode()))
    size = 0.85 + 0.15 * look + rng.uniform(-0.05, 0.05)
    img = cell()
    f = Frame(0)
    if kind in ("spruce", "fir", "pine"):
        trunk = c("#8a5a3a") if kind == "pine" else c("#5a3f2a")
        green = {"spruce": c("#355a32"), "fir": c("#376052"), "pine": c("#46683a")}[kind]
        top = 5.6 * size if kind != "pine" else 6.0 * size
        faces = f.upright(0, 0, 0, top * 0.5, 0.025, 0.02, trunk, n=8)
        if kind == "pine":
            # A tall bare trunk with a ragged crown high up.
            draw(img, faces)
            d = ImageDraw.Draw(img)
            for k in range(11):
                a = rng.uniform(-0.12, 0.12)
                s = rng.uniform(-0.1, 0.1)
                hh = top * rng.uniform(0.55, 0.95)
                sphere(d, proj(0.5 + a, 0.5 + s, hh), rng.uniform(26, 38) * size, green)
            return img
        layers = 4 if kind == "spruce" else 5
        for k in range(layers):
            t0 = k / layers
            h0 = top * (0.15 + 0.7 * t0)
            h1 = h0 + top * 0.38
            r0 = (0.17 if kind == "spruce" else 0.2) * size * (1 - 0.7 * t0)
            faces += f.upright(0, 0, h0, h1, r0, 0.01, shade(green, 0.95 + 0.06 * k), n=12)
        draw(img, faces, strength=0.55)
        return img
    trunk = {"maple": c("#5e4632"), "birch": c("#e8e4dc"), "aspen": c("#c9c6b4")}[kind]
    green = {"maple": c("#3d6e2e"), "birch": c("#6a9a40"), "aspen": c("#7aa848")}[kind]
    top = (4.2 if kind == "maple" else 4.8) * size
    draw(img, f.upright(0, 0, 0, top * 0.55, 0.03, 0.022, trunk, n=8))
    d = ImageDraw.Draw(img)
    if kind == "birch":
        for k in range(5):
            x, y = proj(0.5, 0.5, top * 0.1 * (k + 1))
            d.line([x - 4, y, x + 2, y], c("#2a2622"), width=SS)
    # A full crown of leaf clusters, an egg shape for the maple, narrower for birch and aspen.
    blobs = []
    count = 26 if kind == "maple" else 18
    wide = (0.2 if kind == "maple" else 0.14) * size
    crown_lo, crown_hi = top * 0.42, top * 1.02
    for _ in range(count):
        t = rng.random()
        hh = crown_lo + (crown_hi - crown_lo) * t
        # Widest a little below the middle of the crown.
        reach = wide * math.sin(math.pi * min(1.0, 0.15 + t * 0.95))
        ang = rng.uniform(0, 2 * math.pi)
        rad = reach * math.sqrt(rng.random())
        a = rad * math.cos(ang)
        s = rad * math.sin(ang)
        r = rng.uniform(24, 34) * size * (1.1 if kind == "maple" else 0.85)
        blobs.append((a + s + hh * 0.02, (0.5 + a, 0.5 + s, hh), r))
    for _, p, r in sorted(blobs):
        sphere(d, proj(*p), r, green)
    return img
