"""Writes the curve numbers the Kotlin TrackCurves is tested against (sim/src/jvmTest/resources/curves_reference.txt):
every tile of the curves_look scenes and some seeded random layouts, as curves.py draws it in back mode from the 5 by 5
tiles round it. `python3 tools/blender/export_curves.py` rewrites the file, `--check` says whether it is up to date."""
import os
import random
import sys

import curves
import curves_look

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "sim", "src", "jvmTest", "resources", "curves_reference.txt")
EDGE = {"XM": 0, "XP": 1, "YM": 2, "YP": 3}
FLAG = {"slope": 1, "bridge": 2, "tunnel": 4, "crossing": 8, "platform": 16, "depot": 32, "signal": 64}


def mask_of(pieces):
    return sum(1 << curves.PIECES.index(p) for p in pieces)


def tile_lines(masks, flags, tile):
    window = {(tile[0] + du, tile[1] + dv): masks.get((tile[0] + du, tile[1] + dv), 0)
              for du in range(-2, 3) for dv in range(-2, 3)}
    wf = {t: f for t, f in flags.items() if t in window}
    out = []
    for d in curves.drawn(curves.masks_to_layout(window), wf, "back"):
        if d.tile != tile:
            continue
        via = d.label[3:] if d.label != d.sim else "-"
        c = d.curve
        nums = " ".join(f"{v:.9f}" for p in c.ctrl for v in p)
        out.append((d.sim, via, f"C {tile[0]} {tile[1]} {d.sim} {via} {EDGE[c.edges[0]]} {EDGE[c.edges[1]]} {nums} {c.length:.9f}"))
    return [line for _s, _v, line in sorted(out, key=lambda o: (curves.PIECES.index(o[0]), o[1]))]


def scene_lines(name, masks, flags):
    out = [f"# {name}"]
    for tile in sorted(masks):
        if masks[tile]:
            f = sum(FLAG[w] for w in flags.get(tile, ()))
            out.append(f"T {tile[0]} {tile[1]} {masks[tile]} {f}")
    for tile in sorted(masks):
        if masks[tile]:
            out += tile_lines(masks, flags, tile)
    return out


def build():
    out = []
    for name, layout in curves_look.SCENES.items():
        masks = {t: mask_of(p) for t, p in layout.items()}
        out += scene_lines(name, masks, {})
    flagged = {(1, 0): {"signal"}}
    out += scene_lines("turnout_signal", {t: mask_of(p) for t, p in curves_look.SCENES["turnout"].items()}, flagged)
    flagged = {(2, 2): {"slope"}}
    out += scene_lines("ease_slope", {t: mask_of(p) for t, p in curves_look.SCENES["ease"].items()}, flagged)
    rng = random.Random(7)
    for k in range(40):
        masks, flags = {}, {}
        for x in range(7):
            for y in range(7):
                if rng.random() < 0.45:
                    masks[(x, y)] = rng.choice([1, 2, 4, 8, 16, 32, 1, 2, 1 | 4, 1 | 32, 2 | 8, 4 | 16, 8 | 32, 5 | 8])
                    if rng.random() < 0.08:
                        flags[(x, y)] = {rng.choice(list(FLAG))}
        out += scene_lines(f"random{k}", masks, flags)
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    text = build()
    if "--check" in sys.argv:
        print("up to date" if os.path.exists(OUT) and open(OUT).read() == text else "STALE")
        sys.exit(0 if os.path.exists(OUT) and open(OUT).read() == text else 1)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    open(OUT, "w").write(text)
    print(f"wrote {len(text.splitlines())} lines")
