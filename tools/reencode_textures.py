#!/usr/bin/env python3
"""Re-encode textures from a folder of original lossless WebP sheets.

    python3 tools/reencode_textures.py --source build/release/asset-compression/original
    python3 tools/reencode_textures.py --kind vehicles --source build/release/vehicle-compression/original
    python3 tools/reencode_textures.py --kind scenery --source build/release/scenery-compression/original

For later art changes, gen_tiles.py uses the same encoder directly on the renders.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path

from PIL import Image
from art.sheet_encoding import save_sheet

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--kind", choices=("terrain", "vehicles", "scenery"), default="terrain")
    parser.add_argument("--output", type=Path, default=ROOT / "shared/src/commonMain/composeResources/files")
    args = parser.parse_args()
    if args.kind == "vehicles":
        sources = sorted(p for p in args.source.glob("vehicle_*.webp") if p.stem.rsplit("_", 1)[-1] in ("0", "1"))
        keys = {p.stem.rsplit("_", 1)[0] for p in sources}
        expected = {f"{key}_{level}.webp" for key in keys for level in (0, 1)}
    elif args.kind == "scenery":
        sources = sorted(args.source.glob("static_[01].webp"))
        expected = {f"static_{level}.webp" for level in (0, 1)}
    else:
        sources = sorted(p for p in args.source.glob("*.webp") if p.name.startswith(("ground_", "shadows_")))
        expected = {f"{kind}_{level}.webp" for kind in ("ground", "shadows") for level in range(6)}
    if not sources or {p.name for p in sources} != expected:
        parser.error("source must hold complete original sheet levels for the selected kind")
    # Check every source before writing, including when source and output are the same folder.
    for p in sources:
        if p.read_bytes()[12:16] != b"VP8L":
            parser.error(f"{p} is not an original lossless WebP sheet")
        if p.resolve() == (args.output / p.name).resolve():
            parser.error("source and output must be separate folders")
    args.output.mkdir(parents=True, exist_ok=True)

    def encode(p):
        with Image.open(p) as img:
            save_sheet(img, args.output / p.name, original_lossless=p.read_bytes())
        target = args.output / p.name
        # Preserve the original encoding when it is already smaller than the freshly encoded sheet.
        if args.kind != "scenery" and target.stat().st_size > p.stat().st_size:
            target.write_bytes(p.read_bytes())
        return dict(file=p.name, before=p.stat().st_size, after=target.stat().st_size,
                    source_sha256=hashlib.sha256(p.read_bytes()).hexdigest())

    # Vehicle sheets are small enough to encode four at a time; terrain sheets need the memory to themselves.
    with ThreadPoolExecutor(max_workers=4 if args.kind == "vehicles" else 1) as pool:
        for result in pool.map(encode, sources):
            print(json.dumps(result), flush=True)


if __name__ == "__main__":
    main()
