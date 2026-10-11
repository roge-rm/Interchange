"""The camera, light and render settings that make Blender draw as the game does.

The game's view: screen x = (u - v) * HW, screen y = (u + v) * HH - h * STEP,
with HW = 128, HH = 64 and STEP = 32 at the sharpest level. That's an
orthographic camera turned 45 degrees, looking down at 30 degrees, if a step of
height is RISE tiles tall. Blender's x is the game's v and its y the game's u,
so u runs to the right of the screen as it does in the game.

Every render is one cell: CELL_W by CELL_H at the sharpest level, times SS,
with the tile's top corner at height 0 at (HW, TOP), as tools/art/base.py has it.
"""
import math

import bpy
from mathutils import Vector

HW, HH, STEP = 128, 64, 32
TOP = 3 * HW
CELL_W, CELL_H = 2 * HW, TOP + 2 * HH + STEP
SS = 2
# A step of height, in tiles, for the camera's 30 degree tilt to show it STEP pixels tall.
RISE = STEP / (math.cos(math.radians(30)) * math.sqrt(2) * HW)
# Towards the camera, in Blender's (x, y, z).
VIEW = Vector((math.cos(math.radians(30)) / math.sqrt(2), math.cos(math.radians(30)) / math.sqrt(2), math.sin(math.radians(30))))


def game(u, v, h=0.0):
    """A point in game terms (tiles, tiles, steps) as a Blender location."""
    return Vector((v, u, h * RISE))


def clear():
    bpy.ops.wm.read_factory_settings(use_empty=True)


def setup(samples=48):
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.samples = samples
    # This Blender has no denoiser; enough samples instead.
    scene.cycles.use_denoising = False
    scene.render.film_transparent = True
    scene.render.resolution_x = CELL_W * SS
    scene.render.resolution_y = CELL_H * SS
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGBA"
    scene.view_settings.view_transform = "Standard"
    scene.view_settings.look = "None"
    scene.render.threads_mode = "AUTO"
    # A tighter pixel filter than the default, for crisp edges once shrunk.
    scene.cycles.filter_width = 0.9

    # The camera: centred on the cell's middle, which is CELL_H / 2 - TOP pixels above the anchor.
    cam_data = bpy.data.cameras.new("cam")
    cam_data.type = "ORTHO"
    # One tile along u moves HW pixels across, and a tile along u is 1 / sqrt(2) along the camera's right.
    cam_data.ortho_scale = CELL_W / (math.sqrt(2) * HW)
    cam_data.sensor_fit = "HORIZONTAL"
    cam_data.clip_start = 0.01
    cam_data.clip_end = 100
    cam = bpy.data.objects.new("cam", cam_data)
    scene.collection.objects.link(cam)
    scene.camera = cam
    up_px = TOP - CELL_H / 2
    # Straight up the screen by up_px pixels is z, as the camera sees it.
    z = up_px / (math.cos(math.radians(30)) * math.sqrt(2) * HW)
    target = Vector((0, 0, z))
    cam.location = target + VIEW * 20
    direction = target - cam.location
    cam.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()

    # Light from the top left of the screen and above, as everywhere in the game, with soft sky light for the shade.
    sun_data = bpy.data.lights.new("sun", "SUN")
    sun_data.energy = 2.8
    sun_data.angle = math.radians(14)
    sun = bpy.data.objects.new("sun", sun_data)
    scene.collection.objects.link(sun)
    # Top left of the screen is towards -u, a little -v, so in Blender towards -y, a little -x, from above.
    sun_dir = Vector((-0.35, -1.0, 1.6)).normalized()
    sun.rotation_euler = (-sun_dir).to_track_quat("-Z", "Y").to_euler()

    world = bpy.data.worlds.new("world")
    scene.world = world
    world.use_nodes = True
    bg = world.node_tree.nodes["Background"]
    bg.inputs[0].default_value = (0.78, 0.82, 0.9, 1)
    bg.inputs[1].default_value = 0.75
    return scene


def render(path):
    bpy.context.scene.render.filepath = path
    bpy.ops.render.render(write_still=True)
