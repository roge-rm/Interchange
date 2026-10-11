"""Ground and trees, from Canadian country: meadow, prairie, boreal forest floor,
Shield granite, tundra and snow, with beach and water; spruce, pine, fir,
maple, birch and aspen."""
import math
import random
import zlib
from functools import lru_cache

import numpy as np
from PIL import Image, ImageDraw

from base import (DCELL, DHH, DHW, DSTEP, DTOP, SS, STEP_RISE, Frame, c, cell, draw, lit, masked, mix, noise_layer, proj, shade)


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


def field(seed, scales, size=DCELL):
    """Smooth noise from 0 to 1 over a cell: random fields blurred at each scale and added, larger scales weighing more."""
    rng = np.random.default_rng(seed)
    w, h = size
    total = np.zeros((h, w), np.float32)
    weight = 0.0
    for scale in scales:
        small = rng.random((max(2, h // scale + 2), max(2, w // scale + 2))).astype(np.float32)
        img = Image.fromarray((small * 255).astype(np.uint8)).resize((w + 2 * scale, h + 2 * scale), Image.BICUBIC)
        arr = np.asarray(img, np.float32)[scale:scale + h, scale:scale + w] / 255
        total += arr * scale
        weight += scale
    total /= weight
    lo, hi = np.percentile(total, 2), np.percentile(total, 98)
    return np.clip((total - lo) / (hi - lo + 1e-6), 0, 1)


def blend(cols, t):
    """Colours along a ramp of palette colours, t from 0 to 1."""
    pal = np.array([col[:3] for col in cols], np.float32)
    x = t * (len(pal) - 1)
    i = np.clip(x.astype(int), 0, len(pal) - 2)
    f = (x - i)[..., None]
    return pal[i] * (1 - f) + pal[i + 1] * f


# A ramp from dark to light for each kind, and its details.
RAMPS = {
    "meadow": [c("#3f6e2a"), c("#4f8634"), c("#5f9a3e"), c("#72aa4a"), c("#86b858")],
    "prairie": [c("#7d7a42"), c("#948e50"), c("#aea463"), c("#bfb272"), c("#cdbf84")],
    "forest": [c("#2a3a20"), c("#34482a"), c("#3f5a2e"), c("#4a6634"), c("#5a5236")],
    "rock": [c("#6e6560"), c("#857a74"), c("#9c918b"), c("#b0a49c"), c("#c4b8ae")],
    "tundra": [c("#5c5a3c"), c("#6b6a48"), c("#7d7a52"), c("#8f8c60"), c("#7a5f44")],
    "snow": [c("#cdd9e4"), c("#dde6ee"), c("#eef3f7"), c("#f6f9fb"), c("#ffffff")],
    "sand": [c("#b9a774"), c("#c9b47e"), c("#d8c690"), c("#e4d5a3"), c("#efe3b8")],
}


@lru_cache(maxsize=None)
def texture(kind, variant):
    """A cell of a kind's ground: patches and clumps from noise at several scales, then its details drawn in."""
    seed = zlib.crc32(f"{kind}{variant}".encode())
    broad = field(seed, [96, 48])
    fine = field(seed + 1, [12, 6, 3])
    grain = field(seed + 2, [2, 1])
    t = np.clip(broad * 0.55 + fine * 0.3 + grain * 0.15, 0, 1)
    rgb = blend(RAMPS[kind], t)
    img = Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8), "RGB").convert("RGBA")
    d = ImageDraw.Draw(img)
    rng = random.Random(seed)
    w, h = DCELL
    ramp = RAMPS[kind]
    if kind == "meadow":
        for _ in range(900):
            x, y = rng.randrange(w), rng.randrange(h)
            col = ramp[rng.choice([0, 1, 3, 4])]
            d.line([x, y, x + rng.randint(-2, 2), y - rng.randint(3, 8)], col, width=1)
        for _ in range(30):
            x, y = rng.randrange(w), rng.randrange(h)
            col = rng.choice([c("#f4eecf"), c("#f0d24a"), c("#d9a6d8"), c("#ffffff")])
            for _ in range(rng.randint(2, 5)):
                dx, dy = rng.randint(-6, 6), rng.randint(-3, 3)
                d.ellipse([x + dx, y + dy, x + dx + 2, y + dy + 2], fill=col)
    elif kind == "prairie":
        for _ in range(1400):
            x, y = rng.randrange(w), rng.randrange(h)
            d.line([x, y, x + rng.randint(1, 5), y - rng.randint(4, 10)], ramp[rng.choice([0, 1, 3, 4])], width=1)
    elif kind == "forest":
        for _ in range(700):
            x, y = rng.randrange(w), rng.randrange(h)
            d.line([x, y, x + rng.randint(-4, 4), y + rng.randint(-2, 2)], c("#6b4e30"), width=1)
        for _ in range(60):
            x, y = rng.randrange(w), rng.randrange(h)
            r = rng.randint(4, 10)
            d.ellipse([x - r, y - r // 2, x + r, y + r // 2], fill=c("#5c7a3e"))
    elif kind == "rock":
        for _ in range(40):
            x, y = rng.randrange(w), rng.randrange(h)
            pts = [(x, y)]
            for _ in range(rng.randint(2, 6)):
                x += rng.randint(-16, 16)
                y += rng.randint(-6, 6)
                pts.append((x, y))
            d.line(pts, c("#5a504a"), width=SS)
            d.line([(px, py + 2) for px, py in pts], c("#cfc4ba"), width=1)
        for _ in range(90):
            x, y = rng.randrange(w), rng.randrange(h)
            r = rng.randint(3, 8)
            d.ellipse([x - r, y - r // 2, x + r, y + r // 2], fill=rng.choice([c("#8f9a5a"), c("#b0a84a"), c("#d8d0c4")]))
    elif kind == "tundra":
        for _ in range(260):
            x, y = rng.randrange(w), rng.randrange(h)
            r = rng.randint(2, 6)
            d.ellipse([x - r, y - r // 2, x + r, y + r // 2], fill=rng.choice([c("#5e6a3a"), c("#8a4e36"), c("#6f7a44"), c("#a08a5a")]))
    elif kind == "snow":
        for _ in range(60):
            x, y = rng.randrange(w), rng.randrange(h)
            d.line([x, y, x + rng.randint(12, 40), y + rng.randint(-3, 3)], c("#c8d6e2"), width=SS)
            d.line([x, y - 2, x + rng.randint(8, 30), y - 2], c("#ffffff"), width=1)
    elif kind == "sand":
        for _ in range(240):
            x, y = rng.randrange(w), rng.randrange(h)
            d.point([x, y], fill=rng.choice([c("#a8977a"), c("#f4ecd0")]))
    return img


def ground(kind, variant, shape):
    img = cell()
    layer = texture(kind, variant)
    for tri in triangles(shape):
        # Hills read without striping the land: a little over a quarter of what a real slope would show.
        f = lit(tri_normal(*tri), strength=0.28, low=0.84, high=1.12)
        img.alpha_composite(masked(layer, [proj(*p) for p in tri], f, grow=1))
    return img


# Where a tile's top corner sits in a cell, at height 0.
DTOP_GROUND = DTOP

# The shapes edge blends come in: flat and the four slopes. Other shapes use the flat one.
BLEND_SHAPES = [0, 3, 6, 12, 9]
# View edges, on round from the top corner: 0 from top to right (the v = 0 side), 1 from right to bottom (u = 1),
# 2 from bottom to left (v = 1), 3 from left to top (u = 0).
EDGES = 4


def edge_mask(edge, shape, seed):
    """A mask over the tile's top, solid at the given view edge and fading out with a ragged line into the tile."""
    o = offsets(shape)
    w, h = DCELL
    rag = field(seed, [24, 8])
    # Distance into the tile from the edge, worked out in (u, v) for every pixel by inverting the projection at height 0,
    # then lifted by the slope's height there, which is close enough for a soft fade.
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    a = (xs - DHW) / DHW
    b = (ys - DTOP_GROUND) / DHH
    u = (a + b) / 2
    v = (b - a) / 2
    for _ in range(2):
        hgt = (o[0] * (1 - u) + o[1] * u) * (1 - v) + (o[3] * (1 - u) + o[2] * u) * v
        b2 = (ys - DTOP_GROUND + hgt * DSTEP) / DHH
        u = (a + b2) / 2
        v = (b2 - a) / 2
    dist = [v, 1 - u, 1 - v, u][edge]
    reach = 0.28 + (rag - 0.5) * 0.18
    mask = np.clip(1 - dist / reach, 0, 1) ** 1.4
    inside = (u >= -0.01) & (u <= 1.01) & (v >= -0.01) & (v <= 1.01)
    return Image.fromarray((mask * inside * 255).astype(np.uint8), "L")


def edge_blend(kind, edge, shape):
    """The neighbour's ground fading in over this tile from one view edge."""
    tex = ground(kind, 0, shape)
    m = edge_mask(edge, shape, zlib.crc32(f"edge{kind}{edge}".encode()))
    alpha = Image.fromarray((np.asarray(tex.getchannel("A"), np.float32) * np.asarray(m, np.float32) / 255).astype(np.uint8), "L")
    out = tex.copy()
    out.putalpha(alpha)
    return out


def foam(edge):
    """Foam and wet sand along the edge of a water tile where it meets land."""
    img = cell()
    m = np.asarray(edge_mask(edge, 0, zlib.crc32(f"foam{edge}".encode())), np.float32) / 255
    w, h = DCELL
    wet = np.zeros((h, w, 4), np.float32)
    band = np.clip((m - 0.55) / 0.45, 0, 1)
    froth = np.clip(1 - np.abs(m - 0.45) / 0.12, 0, 1) * (field(zlib.crc32(f"froth{edge}".encode()), [4, 2]) > 0.45)
    sand = np.array(c("#c9b47e")[:3], np.float32)
    white = np.array([235, 242, 245], np.float32)
    wet[..., :3] = sand * band[..., None] + white * (1 - band[..., None])
    wet[..., 3] = np.clip(band * 255 + froth * 230, 0, 255)
    return Image.fromarray(wet.astype(np.uint8), "RGBA")


DECOR = ["boulder", "shrub", "tall_grass"]
DECOR_LOOKS = 3


def decor(kind, look):
    rng = random.Random(zlib.crc32(f"{kind}{look}".encode()))
    img = cell()
    d = ImageDraw.Draw(img)
    if kind == "boulder":
        f = Frame(0)
        faces = []
        for k in range(rng.randint(1, 3)):
            a, s_ = rng.uniform(-0.08, 0.08), rng.uniform(-0.08, 0.08)
            r = rng.uniform(0.04, 0.07)
            faces += f.upright(a, s_, 0, rng.uniform(0.25, 0.5), r, r * 0.55, rng.choice([c("#8a8078"), c("#9c928a"), c("#7a706a")]), n=7)
        draw(img, faces, strength=0.6)
    elif kind == "shrub":
        for _ in range(rng.randint(5, 9)):
            a, s_ = rng.uniform(-0.06, 0.06), rng.uniform(-0.06, 0.06)
            sphere(d, proj(0.5 + a, 0.5 + s_, rng.uniform(0.2, 0.6)), rng.uniform(9, 15), rng.choice([c("#4a6a30"), c("#5a7a38"), c("#3e5a2a")]))
    else:
        for _ in range(60):
            a, s_ = rng.uniform(-0.1, 0.1), rng.uniform(-0.1, 0.1)
            x, y = proj(0.5 + a, 0.5 + s_, 0)
            d.line([x, y, x + rng.randint(-5, 5), y - rng.randint(14, 30)], rng.choice([c("#6a8a3a"), c("#8aa04a"), c("#a8a85a")]), width=SS)
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
