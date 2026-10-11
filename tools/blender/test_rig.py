"""Renders a tile-sized square and a one-step cube to check the camera matches the game."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bpy  # noqa: E402

import rig  # noqa: E402

out = sys.argv[sys.argv.index("--") + 1]
rig.clear()
rig.setup(samples=8)
bpy.ops.mesh.primitive_plane_add(size=1, location=rig.game(0.5, 0.5))
bpy.ops.mesh.primitive_cube_add(size=1, location=(0, 0, 0))
cube = bpy.context.object
# A cube a quarter tile wide and one step tall, standing on the tile's middle.
cube.scale = (0.25, 0.25, rig.RISE)
cube.location = rig.game(0.5, 0.5, 0.5)
rig.render(out)
