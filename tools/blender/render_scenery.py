"""Renders trees, platforms, the engine house and signals for tools/gen_tiles.py to pack.

    blender -b --python tools/blender/render_scenery.py -- OUT_DIR [ONLY]

ONLY, if given, renders just the files whose names start with it.

Files: tree_<kind>_<look>.png, station_<0 along u, 1 along v>.png,
depot_<edge>.png, signal_<0 clear, 1 stop>.png, house_<kind>_<edge>.png (a town
building facing that view edge), part_<piece>_<edge>.png (a piece of an
industry), each a whole cell at twice the sharpest level.
A file already there is left alone.

Everything but signals comes twice: the thing itself with no shadow, and
<name>_shadow.png, its shadow alone, so the game can lay shadows over the ground
before it draws what stands on it. Then <name>_up.png, how much each pixel faces
the sky, from black (sideways or down) to white (flat on top), for laying snow.

Maples, birches and aspens come again for the seasons, as
tree_<kind>_<look>_autumn.png and tree_<kind>_<look>_bare.png.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bpy  # noqa: E402

import houses  # noqa: E402
import industries  # noqa: E402
import parts  # noqa: E402
import rig  # noqa: E402
import scenery  # noqa: E402

TREES = ["spruce", "pine", "fir", "maple", "birch", "aspen"]
LEAFY = ["maple", "birch", "aspen"]
DECOR = ["boulder", "shrub", "tall_grass"]
PIECES = ["X", "Y", "N", "E", "S", "W"]
# The shapes each piece lies on: flat, and for the straight ones the slopes running along them.
TRACK_SHAPES = {"X": [0, 6, 9], "Y": [0, 3, 12]}
EDGES = ["XM", "XP", "YM", "YP"]


def shadow_catcher():
    bpy.ops.mesh.primitive_plane_add(size=40, location=rig.game(0.5, 0.5, 0))
    plane = bpy.context.object
    plane.is_shadow_catcher = True
    return plane


ONLY = None


def up_material():
    """Shows how much a surface faces up, from its normal, as plain light the render doesn't shade."""
    m = bpy.data.materials.new("up")
    m.use_nodes = True
    nt = m.node_tree
    nt.nodes.clear()
    geo = nt.nodes.new("ShaderNodeNewGeometry")
    sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    ramp = nt.nodes.new("ShaderNodeMapRange")
    ramp.inputs["From Min"].default_value = 0.3
    ramp.inputs["From Max"].default_value = 0.7
    glow = nt.nodes.new("ShaderNodeEmission")
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    nt.links.new(geo.outputs["Normal"], sep.inputs["Vector"])
    nt.links.new(sep.outputs["Z"], ramp.inputs["Value"])
    nt.links.new(ramp.outputs["Result"], glow.inputs["Color"])
    nt.links.new(glow.outputs["Emission"], out.inputs["Surface"])
    return m


def up_pass(out, name, model):
    """Renders the model again as how much each pixel faces up."""
    scene = bpy.context.scene
    scene.cycles.samples = 12
    scene.world.node_tree.nodes["Background"].inputs[1].default_value = 0.0
    for o in model.objects:
        o.visible_camera = True
        o.visible_shadow = False
    bpy.context.view_layer.material_override = up_material()
    rig.render(str(out / name.replace(".png", "_up.png")))


def shot(out, name, build, samples=48, shadow=True, placed=True, heading=0):
    if ONLY and not name.startswith(ONLY):
        return
    shadow_name = name.replace(".png", "_shadow.png")
    up_name = name.replace(".png", "_up.png")
    if (out / name).exists() and (not shadow or (out / shadow_name).exists()) and (out / up_name).exists():
        return
    if (out / name).exists() and (not shadow or (out / shadow_name).exists()):
        # Only the up pass is missing.
        rig.clear()
        parts._materials.clear()
        rig.setup(samples=samples)
        catcher = shadow_catcher()
        catcher.hide_render = True
        model = parts.Model(name)
        build(model)
        if placed:
            model.place(heading)
        up_pass(out, name, model)
        return
    rig.clear()
    parts._materials.clear()
    rig.setup(samples=samples)
    catcher = shadow_catcher()
    model = parts.Model(name)
    build(model)
    if placed:
        model.place(heading)
    if not shadow:
        catcher.hide_render = True
        rig.render(str(out / name))
        up_pass(out, name, model)
        return
    # The thing alone: nothing to catch its shadow.
    catcher.hide_render = True
    rig.render(str(out / name))
    # Its shadow alone: the thing hidden from the camera but still casting.
    catcher.hide_render = False
    for o in model.objects:
        o.visible_camera = False
    rig.render(str(out / shadow_name))
    catcher.hide_render = True
    up_pass(out, name, model)


def main():
    global ONLY
    args = sys.argv[sys.argv.index("--") + 1:]
    out = Path(args[0])
    ONLY = args[1] if len(args) > 1 else None
    out.mkdir(parents=True, exist_ok=True)
    for kind in TREES:
        for look in range(3):
            shot(out, f"tree_{kind}_{look}.png", lambda m, kind=kind, look=look: scenery.tree(m, kind, look))
    for kind in LEAFY:
        for season in ("autumn", "bare"):
            for look in range(3):
                shot(out, f"tree_{kind}_{look}_{season}.png", lambda m, kind=kind, look=look, season=season: scenery.tree(m, kind, look, season))
    for a in (0, 1):
        shot(out, f"station_{a}.png", lambda m, a=a: scenery.station(m, a == 0))
    for edge in EDGES:
        shot(out, f"depot_{edge}.png", lambda m, edge=edge: scenery.depot(m, edge))
    for kind in houses.BUILDERS:
        for edge in EDGES:
            shot(out, f"house_{kind}_{edge}.png", lambda m, kind=kind: houses.house(m, kind), heading=houses.FACING[edge])
    for key in industries.PARTS:
        for edge in EDGES:
            shot(out, f"part_{key}_{edge}.png", lambda m, key=key: industries.part(m, key), heading=houses.FACING[edge])
    for red in (0, 1):
        shot(out, f"signal_{red}.png", lambda m, red=red: scenery.signal(m, red == 1), shadow=False)
    for kind in DECOR:
        for look in range(3):
            shot(out, f"decor_{kind}_{look}.png", lambda m, kind=kind, look=look: scenery.decor(m, kind, look))
    # Track is built in the tile's own terms, already in place, and lies on the ground so needs no shadow apart.
    for p, piece in enumerate(PIECES):
        for shape in TRACK_SHAPES.get(piece, [0]):
            shot(out, f"track_{piece}_{shape}.png", lambda m, piece=piece, shape=shape: scenery.track(m, piece, shape), shadow=False, placed=False)


main()
