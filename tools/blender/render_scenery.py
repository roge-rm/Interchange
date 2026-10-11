"""Renders trees, platforms, the engine house and signals for tools/gen_tiles.py to pack.

    blender -b --python tools/blender/render_scenery.py -- OUT_DIR

Files: tree_<kind>_<look>.png, station_<0 along u, 1 along v>.png,
depot_<edge>.png, signal_<0 clear, 1 stop>.png, each a whole cell at twice the
sharpest level. A file already there is left alone.

Everything but signals comes twice: the thing itself with no shadow, and
<name>_shadow.png, its shadow alone, so the game can lay shadows over the ground
before it draws what stands on it.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bpy  # noqa: E402

import parts  # noqa: E402
import rig  # noqa: E402
import scenery  # noqa: E402

TREES = ["spruce", "pine", "fir", "maple", "birch", "aspen"]
EDGES = ["XM", "XP", "YM", "YP"]


def shadow_catcher():
    bpy.ops.mesh.primitive_plane_add(size=40, location=rig.game(0.5, 0.5, 0))
    plane = bpy.context.object
    plane.is_shadow_catcher = True
    return plane


def shot(out, name, build, samples=48, shadow=True):
    shadow_name = name.replace(".png", "_shadow.png")
    if (out / name).exists() and (not shadow or (out / shadow_name).exists()):
        return
    rig.clear()
    parts._materials.clear()
    rig.setup(samples=samples)
    catcher = shadow_catcher()
    model = parts.Model(name)
    build(model)
    model.place(0)
    if not shadow:
        rig.render(str(out / name))
        return
    # The thing alone: nothing to catch its shadow.
    catcher.hide_render = True
    rig.render(str(out / name))
    # Its shadow alone: the thing hidden from the camera but still casting.
    catcher.hide_render = False
    for o in model.objects:
        o.visible_camera = False
    rig.render(str(out / shadow_name))


def main():
    out = Path(sys.argv[sys.argv.index("--") + 1])
    out.mkdir(parents=True, exist_ok=True)
    for kind in TREES:
        for look in range(3):
            shot(out, f"tree_{kind}_{look}.png", lambda m, kind=kind, look=look: scenery.tree(m, kind, look))
    for a in (0, 1):
        shot(out, f"station_{a}.png", lambda m, a=a: scenery.station(m, a == 0))
    for edge in EDGES:
        shot(out, f"depot_{edge}.png", lambda m, edge=edge: scenery.depot(m, edge))
    for red in (0, 1):
        shot(out, f"signal_{red}.png", lambda m, red=red: scenery.signal(m, red == 1), shadow=False)


main()
