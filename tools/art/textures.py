"""Ground textures that repeat seamlessly, for the game to paint the land with pixel by pixel.

Each kind's texture is laid out in a tile's own (u, v) terms: TEXELS a tile at
the sharpest level, repeating every PERIOD tiles each way, so neighbouring
tiles join without a seam. Noise is made periodic by filtering white noise in
frequency space, and details are drawn wrapped round the edges.

After the kinds comes one more texture, plain grey noise, which the game uses to
make the edge ragged where one kind of ground meets another. It's a texture of
its own, not alpha, since Android keeps image colour multiplied by alpha and that
would spoil colour wherever the noise is low.
"""
import random
import zlib

import numpy as np
from PIL import Image, ImageDraw

from base import c

TEXELS = 128
PERIOD = 4
SIZE = TEXELS * PERIOD

# A ramp from dark to light for each kind, in the order of the game's ground kinds, then beach sand and water.
KINDS = {
    "meadow": [c("#3f6e2a"), c("#4f8634"), c("#5c983c"), c("#6aa646"), c("#7cb452")],
    "prairie": [c("#7d7a42"), c("#948e50"), c("#aaa060"), c("#bbae70"), c("#c9bc80")],
    "forest": [c("#283820"), c("#33462a"), c("#3d562e"), c("#486234"), c("#56503a")],
    "rock": [c("#6e6560"), c("#857a74"), c("#9a8f89"), c("#aea29a"), c("#c2b6ac")],
    "tundra": [c("#5a583a"), c("#696846"), c("#7b7850"), c("#8c895e"), c("#7a5f44")],
    "snow": [c("#c8d4e0"), c("#d8e2ec"), c("#e8eff5"), c("#f3f7fa"), c("#ffffff")],
    "sand": [c("#b4a270"), c("#c4b07c"), c("#d4c28c"), c("#e0d19e"), c("#ece0b4")],
    "water": [c("#2e5f9c"), c("#3466a6"), c("#3a6fb0"), c("#4278ba"), c("#5a8fcc")],
}


def periodic(seed, scale):
    """Noise from 0 to 1 that repeats every SIZE texels, with features about scale texels across."""
    rng = np.random.default_rng(seed)
    white = rng.standard_normal((SIZE, SIZE))
    fy = np.fft.fftfreq(SIZE)[:, None]
    fx = np.fft.fftfreq(SIZE)[None, :]
    kernel = np.exp(-(fx * fx + fy * fy) * (scale * np.pi) ** 2 / 2)
    out = np.real(np.fft.ifft2(np.fft.fft2(white) * kernel))
    lo, hi = np.percentile(out, 1), np.percentile(out, 99)
    return np.clip((out - lo) / (hi - lo + 1e-9), 0, 1)


def ramp(cols, t):
    pal = np.array([col[:3] for col in cols], np.float32)
    x = t * (len(pal) - 1)
    i = np.clip(x.astype(int), 0, len(pal) - 2)
    f = (x - i)[..., None]
    return pal[i] * (1 - f) + pal[i + 1] * f


def wrapped(d, box, fill):
    """An ellipse drawn wrapped round the texture's edges, so it repeats."""
    x0, y0, x1, y1 = box
    for dx in (-SIZE, 0, SIZE):
        for dy in (-SIZE, 0, SIZE):
            d.ellipse([x0 + dx, y0 + dy, x1 + dx, y1 + dy], fill=fill)


def texture(kind):
    seed = zlib.crc32(kind.encode())
    t = periodic(seed, 48) * 0.35 + periodic(seed + 1, 10) * 0.35 + periodic(seed + 2, 2.5) * 0.3
    t = np.clip((t - t.min()) / (t.max() - t.min()), 0, 1)
    rgb = ramp(KINDS[kind], t)
    img = Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8), "RGB")
    d = ImageDraw.Draw(img)
    rng = random.Random(seed)
    cols = KINDS[kind]
    spots = {
        "meadow": (500, [cols[0], cols[4], c("#f0d24a"), c("#f4eecf")], (1, 3)),
        "prairie": (700, [cols[0], cols[4], c("#d8c890")], (1, 3)),
        "forest": (600, [c("#6b4e30"), c("#5c7a3e"), c("#2a2018")], (1, 4)),
        "rock": (500, [c("#5a504a"), c("#8f9a5a"), c("#d8d0c4"), c("#b0a84a")], (1, 5)),
        "tundra": (600, [c("#5e6a3a"), c("#8a4e36"), c("#a08a5a"), c("#6f7a44")], (1, 4)),
        "snow": (200, [c("#c0ccd8"), c("#ffffff")], (1, 3)),
        "sand": (500, [c("#a8977a"), c("#f4ecd0")], (1, 2)),
        "water": (180, [c("#5f93cf"), c("#2c5a94")], (1, 2)),
    }[kind]
    count, palette, (r0, r1) = spots
    for _ in range(count):
        x, y = rng.randrange(SIZE), rng.randrange(SIZE)
        r = rng.randint(r0, r1)
        wrapped(d, (x - r, y - r, x + r, y + r), rng.choice(palette))
    if kind == "rock":
        # Cracks in the granite.
        for _ in range(40):
            x, y = rng.randrange(SIZE), rng.randrange(SIZE)
            for _ in range(rng.randint(3, 7)):
                nx, ny = x + rng.randint(-10, 10), y + rng.randint(-10, 10)
                for dx in (-SIZE, 0, SIZE):
                    for dy in (-SIZE, 0, SIZE):
                        d.line([x + dx, y + dy, nx + dx, ny + dy], c("#4e4640"), width=1)
                x, y = nx, ny
    return img.convert("RGBA")


def rag():
    """Grey noise for ragged edges between kinds, opaque."""
    n = (periodic(zlib.crc32(b"rag"), 6) * 255).astype(np.uint8)
    return Image.fromarray(n, "L").convert("RGBA")


def sheet():
    """Every kind's texture stacked top to bottom, in the order of KINDS, then the ragged edge noise."""
    out = Image.new("RGBA", (SIZE, SIZE * (len(KINDS) + 1)))
    for i, kind in enumerate(KINDS):
        out.paste(texture(kind), (0, i * SIZE))
    out.paste(rag(), (0, len(KINDS) * SIZE))
    return out
