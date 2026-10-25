"""Encode texture sheets while keeping sprite colour bits and clear shadow padding intact."""

from io import BytesIO

import numpy as np
from PIL import Image


def pack_snow(img):
    """Four two-bit snow values per byte, the first pixel in the low bits."""
    snow = np.asarray(img.getchannel("B"), dtype=np.uint8).reshape(-1) & 3
    snow = np.concatenate([snow, np.zeros(-len(snow) % 4, dtype=np.uint8)]).reshape(-1, 4)
    return (snow[:, 0] | snow[:, 1] << 2 | snow[:, 2] << 4 | snow[:, 3] << 6).astype(np.uint8).tobytes()


def save_sheet(img, path, *, original_lossless=None):
    """Compress ground, shadows and large sprite sheets; keep snow data and alpha exact."""
    target = path.with_suffix(".webp")
    if path.stem.startswith("ground_"):
        img.save(target, "WEBP", quality=90, method=6)
    elif path.stem in ("static_0", "static_1"):
        # Colour compression changes low blue bits, so keep snow data separately and restore it after decoding.
        level = path.stem.rsplit("_", 1)[-1]
        target.with_name(f"static_snow_{level}.bin").write_bytes(pack_snow(img))
        bands = list(img.split())
        bands[2] = bands[2].point(lambda v: v & 252)
        colour = Image.merge(img.mode, bands)
        colour.save(target, "WEBP", quality=95, method=4, alpha_quality=100)
    elif path.stem.startswith("shadows_"):
        # The renderer reads this as a mask. Preserve zero exactly to avoid dark padding round sprites.
        grey = img if img.mode == "L" else img.getchannel("B")
        grey = grey.point([min(255, ((v + 2) // 4) * 4) for v in range(256)])
        grey.save(target, "WEBP", lossless=True, quality=100, method=6, exact=True)
    elif path.stem.startswith("vehicle_") and path.stem.rsplit("_", 1)[-1] in ("0", "1"):
        # WebP keeps alpha lossless. Small zoom levels stay exact, and simple drawings may pack better losslessly.
        lossy = BytesIO()
        img.save(lossy, "WEBP", quality=95, method=4, alpha_quality=100)
        if original_lossless is None:
            lossless = BytesIO()
            img.save(lossless, "WEBP", lossless=True, quality=100, method=6, exact=True)
            original_lossless = lossless.getvalue()
        target.write_bytes(min((lossy.getvalue(), original_lossless), key=len))
    else:
        # Static sprites carry snow data in the low colour bits; signals and small vehicle sheets stay exact.
        img.save(target, "WEBP", lossless=True, quality=100, method=6, exact=True)
