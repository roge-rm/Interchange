"""Ground textures that repeat seamlessly, for the game to paint the land with pixel by pixel.

Each kind's texture is laid out in a tile's own (u, v) terms: TEXELS a tile at
the sharpest level, repeating every PERIOD tiles each way, so neighbouring
tiles join without a seam. A texel there covers about a screen pixel.

Up the screen is towards lower u and v together, so a grass blade standing up
is a line from (x, y) to (x - n, y - n) here, and something lying across the
screen runs along (1, -1). Details are drawn on a canvas three textures wide and
folded back, so whatever crosses an edge carries on from the other side.

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

# Base ramps from dark to light, and blade or detail colours, for each kind, in the order of the game's ground
# kinds, then beach sand, water, and the bare earth that shows through on slopes.
KINDS = {
    "meadow": [c("#24451a"), c("#2f5a20"), c("#3b6a26"), c("#4a7a2e"), c("#5a8a36")],
    "prairie": [c("#6e6a38"), c("#827c44"), c("#968e50"), c("#a89e5c"), c("#b8ac68")],
    "forest": [c("#1e2c18"), c("#28381e"), c("#324524"), c("#3c522a"), c("#4a4630")],
    "rock": [c("#625a56"), c("#78706a"), c("#8c847e"), c("#a09790"), c("#b4aba2")],
    "tundra": [c("#4e4c34"), c("#5e5c3e"), c("#6e6c48"), c("#7e7a52"), c("#6e563e")],
    "snow": [c("#c4d0dc"), c("#d4dee8"), c("#e4ebf2"), c("#f0f5f9"), c("#fcfdfe")],
    "sand": [c("#ac9a6a"), c("#bca876"), c("#ccb884"), c("#d8c794"), c("#e6d8aa")],
    "water": [c("#2a5a96"), c("#3062a0"), c("#3669aa"), c("#3e72b4"), c("#4a80c2")],
    "earth": [c("#4a3626"), c("#594230"), c("#684f39"), c("#775c43"), c("#86694d")],
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


class Wrapped:
    """A canvas three textures across with the texture's middle in the middle, folded back into one when done."""

    def __init__(self):
        self.img = Image.new("RGBA", (SIZE * 3, SIZE * 3))
        self.d = ImageDraw.Draw(self.img)

    def line(self, x0, y0, x1, y1, col, width=1):
        self.d.line([x0 + SIZE, y0 + SIZE, x1 + SIZE, y1 + SIZE], fill=col, width=width)

    def dot(self, x, y, r, col):
        self.d.ellipse([x + SIZE - r, y + SIZE - r, x + SIZE + r, y + SIZE + r], fill=col)

    def fold(self):
        out = self.img.crop((SIZE, SIZE, 2 * SIZE, 2 * SIZE))
        for bx in range(3):
            for by in range(3):
                if bx == 1 and by == 1:
                    continue
                out.alpha_composite(self.img.crop((bx * SIZE, by * SIZE, (bx + 1) * SIZE, (by + 1) * SIZE)))
        return out


def blades(w, rng, count, cols, lo, hi, light, clump=None, lean=1):
    """Grass blades standing up the screen, lighter at their tips, thicker where clump says."""
    for _ in range(count):
        x, y = rng.uniform(0, SIZE), rng.uniform(0, SIZE)
        if clump is not None and rng.random() > clump[int(y) % SIZE, int(x) % SIZE] * 1.4:
            continue
        n = rng.uniform(lo, hi)
        dx = rng.uniform(-lean, lean)
        col = rng.choice(cols)
        # Up the screen is (-1, -1); across it is (1, -1).
        tx, ty = x - n + dx, y - n - dx
        w.line(x, y, tx, ty, col)
        w.line((x + tx) / 2, (y + ty) / 2, tx, ty, light(col))


def lighter(f):
    return lambda col: tuple(min(255, int(v * f)) for v in col[:3]) + (255,)


def texture(kind):
    seed = zlib.crc32(kind.encode())
    rng = random.Random(seed)
    broad = periodic(seed, 64)
    mid = periodic(seed + 1, 14)
    fine = periodic(seed + 2, 2.0)
    t = np.clip(broad * 0.35 + mid * 0.35 + fine * 0.3, 0, 1)
    rgb = ramp(KINDS[kind], t)
    base = Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8), "RGB").convert("RGBA")
    w = Wrapped()
    cols = KINDS[kind]
    clump = periodic(seed + 3, 10)
    if kind == "meadow":
        greens = [c("#1e4014"), c("#28501a"), c("#346222"), c("#40722a"), c("#4e8232"), c("#5c9038"), c("#6a9a3e")]
        blades(w, rng, 60000, greens, 3, 8, lighter(1.12), clump)
        for _ in range(60):
            x, y = rng.uniform(0, SIZE), rng.uniform(0, SIZE)
            w.dot(x, y, 0.8, rng.choice([c("#f4eecf"), c("#f0d24a"), c("#e8e0f0")]))
    elif kind == "prairie":
        golds = [c("#6e6a3c"), c("#7e7844"), c("#8e864c"), c("#9e9456"), c("#ae9e5e"), c("#5e5c34")]
        blades(w, rng, 55000, golds, 5, 12, lighter(1.1), clump, lean=2)
    elif kind == "forest":
        for _ in range(26000):
            x, y = rng.uniform(0, SIZE), rng.uniform(0, SIZE)
            a = rng.uniform(-3, 3)
            w.line(x, y, x + a, y + rng.uniform(-3, 3), rng.choice([c("#5a4228"), c("#6b4e30"), c("#4a3a24"), c("#2e2416")]))
        for _ in range(900):
            x, y = rng.uniform(0, SIZE), rng.uniform(0, SIZE)
            w.dot(x, y, rng.uniform(1.5, 4), rng.choice([c("#4a6a34"), c("#567a3a"), c("#3e5a2c")]))
    elif kind == "rock":
        for _ in range(9000):
            x, y = rng.uniform(0, SIZE), rng.uniform(0, SIZE)
            w.dot(x, y, rng.uniform(0.5, 1.5), rng.choice([c("#5a524c"), c("#b8ada4"), c("#968c84"), c("#c8bcb0")]))
        for _ in range(90):
            x, y = rng.uniform(0, SIZE), rng.uniform(0, SIZE)
            for _ in range(rng.randint(3, 8)):
                nx, ny = x + rng.uniform(-10, 10), y + rng.uniform(-10, 10)
                w.line(x, y, nx, ny, c("#463e38"))
                w.line(x + 1, y, nx + 1, ny, c("#c4b8ac"))
                x, y = nx, ny
        for _ in range(500):
            x, y = rng.uniform(0, SIZE), rng.uniform(0, SIZE)
            w.dot(x, y, rng.uniform(1, 3.5), rng.choice([c("#8f9a5a"), c("#a8a44e"), c("#c8c0a0")]))
    elif kind == "tundra":
        browns = [c("#5a5a38"), c("#6a6840"), c("#7a5a3e"), c("#8a6a46"), c("#5e6a3a")]
        blades(w, rng, 20000, browns, 2, 4, lighter(1.2), clump)
        for _ in range(1400):
            x, y = rng.uniform(0, SIZE), rng.uniform(0, SIZE)
            w.dot(x, y, rng.uniform(1, 3), rng.choice([c("#4e5a30"), c("#7a3e2c"), c("#9a8858"), c("#c8c0a0")]))
    elif kind == "snow":
        for _ in range(900):
            x, y = rng.uniform(0, SIZE), rng.uniform(0, SIZE)
            n = rng.uniform(8, 30)
            # Wind streaks lie across the screen.
            w.line(x, y, x + n, y - n, c("#c8d4e0"))
            w.line(x, y - 1, x + n * 0.7, y - 1 - n * 0.7, c("#ffffff"))
    elif kind == "sand":
        for _ in range(16000):
            x, y = rng.uniform(0, SIZE), rng.uniform(0, SIZE)
            w.dot(x, y, 0.5, rng.choice([c("#9c8a68"), c("#f2e8c8"), c("#c0ac80")]))
        for _ in range(500):
            x, y = rng.uniform(0, SIZE), rng.uniform(0, SIZE)
            n = rng.uniform(6, 20)
            w.line(x, y, x + n, y - n, c("#b8a478"))
    elif kind == "earth":
        # Pebbles, small cracks and the odd dry tuft clinging on.
        for _ in range(7000):
            x, y = rng.uniform(0, SIZE), rng.uniform(0, SIZE)
            w.dot(x, y, rng.uniform(0.5, 1.6), rng.choice([c("#3a2a1e"), c("#8c7a62"), c("#a08c70"), c("#6e6258"), c("#5a4a3c")]))
        for _ in range(260):
            x, y = rng.uniform(0, SIZE), rng.uniform(0, SIZE)
            for _ in range(rng.randint(2, 5)):
                nx, ny = x + rng.uniform(-6, 6), y + rng.uniform(-6, 6)
                w.line(x, y, nx, ny, c("#3a2a1c"))
                x, y = nx, ny
        blades(w, rng, 4000, [c("#5e5a34"), c("#6e6a3c"), c("#4e5a2c")], 2, 5, lighter(1.15), clump)
    elif kind == "water":
        for _ in range(1800):
            x, y = rng.uniform(0, SIZE), rng.uniform(0, SIZE)
            n = rng.uniform(4, 14)
            w.line(x, y, x + n, y - n, rng.choice([c("#5a8ecc"), c("#6a9cd6"), c("#2c5a92")]))
    base.alpha_composite(w.fold())
    # Darker where the clumps are thickest, so the ground isn't one even mat.
    shade = 0.78 + 0.3 * periodic(seed + 4, 22)
    arr = np.asarray(base, np.float32)
    arr[..., :3] *= shade[..., None]
    return Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGBA")


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
