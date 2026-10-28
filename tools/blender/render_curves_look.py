"""Renders the curve look test's pieces and a car at 16 headings (docs/CURVES_PLAN.md).

    blender -b --python tools/blender/render_curves_look.py -- OUT_DIR

Files: bed_<key>.png and rails_<key>.png, and ties_ and running_ for pieces on tiles with two or more, for each
piece the scenes use (curves_look.key), each a whole cell
at twice the sharpest level as render_scenery.py draws track, and car_<h>.png, boxcar_steel at heading h sixteenths
of a turn from u towards v, as render_vehicles.py draws it. A file already there is left alone.
"""
import math
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import bpy  # noqa: E402

import curves  # noqa: E402
import curves_look  # noqa: E402
import parts  # noqa: E402
import rig  # noqa: E402

CAR = "boxcar_steel"


def vehicles():
    """render_vehicles.py's builders, without running its main()."""
    src = (HERE / "render_vehicles.py").read_text().replace("\nmain()\n", "\n")
    ns = {"__file__": str(HERE / "render_vehicles.py"), "__name__": "render_vehicles"}
    exec(compile(src, "render_vehicles.py", "exec"), ns)
    return ns


def main():
    out = Path(sys.argv[sys.argv.index("--") + 1:][0])
    out.mkdir(parents=True, exist_ok=True)
    jobs = {}
    for scene in curves_look.SHEET_SCENES:
        for mode in curves_look.MODES:
            found = curves_look.pieces(scene, mode)
            count = {}
            for tile, *_ in found:
                count[tile] = count.get(tile, 0) + 1
            for tile, _piece, curve, key, switches in found:
                layers = jobs.setdefault(key, (curve, switches, set()))[2]
                layers.update(("bed", "rails"))
                if count[tile] > 1:
                    layers.update(("ties", "running"))
    for key, (curve, switches, layers) in jobs.items():
        for layer in sorted(layers):
            path = out / f"{layer}_{key}.png"
            if path.exists():
                continue
            rig.clear()
            parts._materials.clear()
            rig.setup(samples=48)
            m = parts.Model(key)
            curves.track(m, curve, layer, switches)
            rig.render(str(path))
    rv = vehicles()
    length, build = rv["MODELS"][CAR]
    rig.clear()
    parts._materials.clear()
    rig.setup(samples=40)
    rv["shadow_catcher"]()
    model = parts.Model(CAR, *rv["height"](CAR))
    build(model)
    rv["couple"](model, rv["L"](length))
    pivot = model.place(0)
    for h in range(16):
        path = out / f"car_{h}.png"
        if path.exists():
            continue
        pivot.rotation_euler = (0, 0, -h * math.pi / 8)
        rig.render(str(path))


main()
