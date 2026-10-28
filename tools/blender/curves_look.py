"""The curve look test's scenes (docs/CURVES_PLAN.md), shared by render_curves_look.py (Blender) and
tools/curves_look_sheet.py (the sheet). No Blender needed to import it."""
import hashlib

import curves

# Tiles (u, v) and their pieces.
SCENES = {
    "turn": {(0, 0): ["X"], (1, 0): ["X"], (2, 0): ["W"], (2, 1): ["Y"], (2, 2): ["Y"]},
    "ease": {(0, 0): ["W"], (0, 1): ["E"], (1, 1): ["W"], (1, 2): ["E"], (2, 2): ["X"], (3, 2): ["X"]},
    "turnout": {(0, 0): ["X"], (1, 0): ["X", "W"], (2, 0): ["X"], (3, 0): ["X"], (1, 1): ["E"], (2, 1): ["W"], (2, 2): ["E"]},
    "crossover": {(0, 0): ["X"], (1, 0): ["X", "W"], (2, 0): ["X"], (3, 0): ["X"],
                  (0, 1): ["X"], (1, 1): ["X", "E"], (2, 1): ["X"], (3, 1): ["X"]},
    "wye": {(0, 0): ["X"], (1, 0): ["X", "W", "S"], (2, 0): ["X"], (1, 1): ["Y"], (1, 2): ["Y"]},
    # A straight splitting into two diagonal lines.
    "wye_diagonal": {(-1, 0): ["X"], (0, 0): ["X"], (1, 0): ["N", "W"], (1, -1): ["S"], (2, -1): ["N"], (2, -2): ["S"],
                     (1, 1): ["E"], (2, 1): ["W"], (2, 2): ["E"]},
    # Two diagonal lines meeting at a right angle.
    "corner": {(-1, -1): ["E"], (0, -1): ["W"], (0, 0): ["E"], (1, 0): ["W"], (1, 1): ["N"], (0, 1): ["S"], (0, 2): ["N"],
               (-1, 2): ["S"]},
    # The turnout with a second one a tile before it, a ladder: their diagonals run side by side, so neither tile
    # round the second turnout is clear.
    "ladder": {(-1, 0): ["X"], (0, 0): ["X", "W"], (1, 0): ["X", "W"], (2, 0): ["X"], (3, 0): ["X"], (0, 1): ["E"],
               (1, 1): ["W", "E"], (2, 1): ["W"], (1, 2): ["E"], (2, 2): ["E"]},
}
# The 90 degree turn's pieces in the way a train runs through it.
TURN_PATH = [((0, 0), "X"), ((1, 0), "X"), ((2, 0), "W"), ((2, 1), "Y"), ((2, 2), "Y")]
VERSION = "v3"
# The scenes the v3 sheets show, and the ways each is drawn: None the plain shapes (v2), then the eased modes.
SHEET_SCENES = ["ease", "turnout", "wye_diagonal", "corner", "ladder"]
MODES = [None, "back", "ahead"]


def _r(x):
    return f"{x:.3f}"


def _sw_desc(sw, stand):
    f = sw["frame"]
    bits = [f"{_r(f.o[0])},{_r(f.o[1])}", str(stand), str(sw["bar"] is not None), str(sw["stand"] is not None)]
    bits += [f"{_r(x[0])},{_r(x[1])},{_r(x[2])}" for x in sw["long"]]
    bits += [f"{_r(q[0][0])},{_r(q[0][1])}" for q in sw["frogs"]]
    bits += [f"{_r(a[0])},{_r(a[1])}" for a, _b in sw["checks"]]
    return ";".join(bits) + f"|{_r(sw['long_end'])}"


def key(d):
    c = d.curve.ctrl
    desc = ";".join(f"{_r(p[0])},{_r(p[1])}" for p in c) + "|" + "/".join(_sw_desc(sw, st) for sw, st in d.switches)
    return f"{d.label}_{d.looks[0]}{d.looks[1]}_{hashlib.md5(desc.encode()).hexdigest()[:8]}"


def pieces(scene, mode="back", flags=None):
    """[(tile, label, Curve, key, switches)] for a scene as curves.drawn() draws it in [mode]; None is the plain
    shapes (v2)."""
    return [(d.tile, d.label, d.curve, key(d), d.switches) for d in curves.drawn(SCENES[scene], flags, mode)]
