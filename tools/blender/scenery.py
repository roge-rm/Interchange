"""Trees, platforms, the engine house and signals, in the same terms as the vehicles:
x across, y along (the game's u), z in steps, standing on the middle of the tile
unless said otherwise.
"""
import math
import random

import bmesh
import bpy
from mathutils import Vector

import parts
import rig
from parts import material


def leafy(name, base, light):
    """A leaf material whose colour wanders between two greens, so a crown isn't flat."""
    key = ("leafy", name)
    if key in parts._materials:
        return parts._materials[key]
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    p = nt.nodes["Principled BSDF"]
    p.inputs["Roughness"].default_value = 0.85
    noise = nt.nodes.new("ShaderNodeTexNoise")
    noise.inputs["Scale"].default_value = 40.0
    noise.inputs["Detail"].default_value = 4.0
    ramp = nt.nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].color = parts.hexcol(base)
    ramp.color_ramp.elements[1].color = parts.hexcol(light)
    nt.links.new(noise.outputs["Fac"], ramp.inputs["Fac"])
    nt.links.new(ramp.outputs["Color"], p.inputs["Base Color"])
    parts._materials[key] = m
    return m


def lumpy(model, obj, strength, scale, seed):
    """Roughens a shape's surface with cloud noise, for leaves and boughs."""
    tex = bpy.data.textures.new(f"clouds{seed}", "CLOUDS")
    tex.noise_scale = scale
    tex.noise_depth = 2
    sub = obj.modifiers.new("subdivide", "SUBSURF")
    sub.levels = 2
    sub.render_levels = 2
    disp = obj.modifiers.new("lumps", "DISPLACE")
    disp.texture = tex
    disp.strength = strength
    disp.mid_level = 0.5
    disp.texture_coords = "OBJECT"
    return obj


def blob(model, x, y, z, r, mat, seed, squash=1.0):
    """A rough ball of leaves at x, y and z steps up, r tiles round."""
    bm = bmesh.new()
    bmesh.ops.create_icosphere(bm, subdivisions=2, radius=r)
    bmesh.ops.scale(bm, vec=(1, 1, squash), verts=bm.verts)
    bmesh.ops.translate(bm, vec=(x, y, z * rig.RISE), verts=bm.verts)
    obj = model._mesh("blob", bm, mat)
    obj.data.polygons.foreach_set("use_smooth", [True] * len(obj.data.polygons))
    return lumpy(model, obj, r * 0.55, r * 0.6, seed)


def tree(m, kind, look, season="summer"):
    """A tree. The leafy kinds can also be drawn in their autumn colours or bare for winter."""
    rng = random.Random(f"{kind}{look}")
    size = 0.85 + 0.15 * look + rng.uniform(-0.05, 0.05)
    bark = material("bark", "#5a3f2a", rough=0.9)
    if kind in ("spruce", "fir"):
        green = leafy(f"{kind} needles", "#1f3a20" if kind == "spruce" else "#1e3a34", "#3a5e34" if kind == "spruce" else "#3a6656")
        top = 5.0 * size
        m.upright(0, 0, 0, top * 0.4, 0.018, 0.014, bark, segments=8)
        layers = 7
        for k in range(layers):
            t = k / layers
            h0 = top * (0.12 + 0.78 * t)
            r0 = (0.15 if kind == "spruce" else 0.17) * size * (1 - 0.8 * t) + 0.02
            cone = m.upright(rng.uniform(-0.005, 0.005), rng.uniform(-0.005, 0.005), h0, h0 + top * 0.3, r0, 0.004, green, segments=14)
            lumpy(m, cone, r0 * 0.35, 0.05, look * 10 + k)
        return
    if kind == "pine":
        green = leafy("pine needles", "#2e4a26", "#56763c")
        trunk = material("pine bark", "#8a5a3a", rough=0.9)
        top = 5.6 * size
        m.upright(0, 0, 0, top * 0.8, 0.02, 0.014, trunk, segments=8)
        for k in range(9):
            a = rng.uniform(-0.1, 0.1)
            s = rng.uniform(-0.1, 0.1)
            blob(m, a, s, top * rng.uniform(0.62, 0.95), rng.uniform(0.09, 0.13) * size, green, look * 10 + k, squash=0.55)
        return
    colours = {
        "summer": {"maple": ("#2c5a22", "#4e8a36"), "birch": ("#4e7a30", "#86b04e"), "aspen": ("#5a8a34", "#9ac05a")},
        "autumn": {"maple": ("#9a2a16", "#e0702a"), "birch": ("#b08a24", "#e8c84a"), "aspen": ("#c09a1c", "#f2d454")},
    }.get(season, {}).get(kind)
    green = leafy(f"{kind} leaves {season}", *colours) if colours else None
    trunk = material("birch bark", "#e4e0d6", rough=0.8) if kind == "birch" else material("aspen bark", "#c4c2b0", rough=0.8) if kind == "aspen" else bark
    top = (4.2 if kind == "maple" else 4.6) * size
    m.upright(0, 0, 0, top * 0.6, 0.024, 0.016, trunk, segments=8)
    if kind == "birch":
        for k in range(6):
            z = top * 0.08 * (k + 1)
            m.box(-0.026, 0.026, -0.006, 0.006, z, z + 0.06, material("birch marks", "#2a2622", rough=0.9))
    wide = (0.16 if kind == "maple" else 0.11) * size
    count = 11 if kind == "maple" else 8
    twigs = None
    if green is None:
        # Bare: boughs out to where the leaves would be, twigs off them, and a faint haze of the finest twigs.
        twigs = haze(f"{kind} twigs", "#6a5c54" if kind == "maple" else "#8a7e74")
    for k in range(count):
        t = rng.random()
        h = top * (0.55 + 0.4 * t)
        reach = wide * math.sin(math.pi * min(1.0, 0.2 + t * 0.9))
        ang = rng.uniform(0, 2 * math.pi)
        r = rng.uniform(0.1, 0.14) * size * (1.1 if kind == "maple" else 0.85)
        x, y = reach * math.cos(ang), reach * math.sin(ang)
        if green is not None:
            blob(m, x, y, h, r, green, look * 10 + k)
            continue
        m.rod((0, 0, top * 0.45), (x, y, h), 0.008, trunk, segments=6)
        for j in range(3):
            a2 = ang + rng.uniform(-1.2, 1.2)
            m.rod((x, y, h), (x + r * 0.8 * math.cos(a2), y + r * 0.8 * math.sin(a2), h + rng.uniform(0.1, 0.6)), 0.004, trunk, segments=5)
        blob(m, x, y, h, r * 0.9, twigs, look * 10 + k)


def haze(name, colour):
    """A see-through material for the fine twigs of a bare crown, seen from afar as a haze."""
    key = ("haze", name)
    if key in parts._materials:
        return parts._materials[key]
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    p = m.node_tree.nodes["Principled BSDF"]
    p.inputs["Base Color"].default_value = parts.hexcol(colour)
    p.inputs["Roughness"].default_value = 0.9
    p.inputs["Alpha"].default_value = 0.22
    m.blend_method = "BLEND"
    parts._materials[key] = m
    return m


# ---- platforms, the engine house and signals ---------------------------------------------

def station(m, along_u):
    """A platform tile with its track running along y (the game's u) or along x. The track itself comes from the track sprite."""
    concrete = material("platform", "#c9c3b5", rough=0.85)
    edge = material("platform edge", "#e2d8a0", rough=0.7)
    timber = material("timber", "#7a4e2e", rough=0.7)
    roof = material("canopy roof", "#6e3a2a", rough=0.6)
    iron = material("cast iron", "#2e3234", metal=0.6, rough=0.45)

    def b(x0, x1, y0, y1, z0, z1, mat, bevel=0.0):
        # Laid out with the track along y; turned when it runs along x.
        if along_u:
            m.box(x0, x1, y0, y1, z0, z1, mat, bevel)
        else:
            m.box(y0, y1, x0, x1, z0, z1, mat, bevel)

    for side in (-1, 1):
        x0, x1 = sorted((side * 0.23, side * 0.5))
        b(x0, x1, -0.5, 0.5, 0, 0.42, concrete, 0.004)
        e0, e1 = sorted((side * 0.23, side * 0.255))
        b(e0, e1, -0.5, 0.5, 0.42, 0.44, edge)
    # A canopy on cast iron posts over the far platform (the -x side), with benches and a lamp.
    for y in (-0.33, 0.0, 0.33):
        b(-0.4, -0.385, y - 0.008, y + 0.008, 0.42, 2.7, iron)
    b(-0.5, -0.2, -0.48, 0.48, 2.7, 2.78, timber, 0.003)
    b(-0.52, -0.18, -0.49, 0.49, 2.78, 2.95, roof, 0.006)
    for y in (-0.18, 0.18):
        b(-0.47, -0.43, y - 0.06, y + 0.06, 0.42, 0.62, timber, 0.003)
        b(-0.48, -0.475, y - 0.06, y + 0.06, 0.62, 0.85, timber)
    b(0.42, 0.432, 0.38, 0.392, 0.42, 2.2, iron)
    b(0.405, 0.447, 0.365, 0.407, 2.2, 2.4, material("lamp glass", "#f4e8b0", rough=0.2), 0.002)


def depot(m, edge):
    """A brick engine house over the track, its doors at view edge XM, XP, YM or YP."""
    brick = material("brick", "#9a5a3c", rough=0.85)
    trim = material("brick trim", "#7a4430", rough=0.85)
    slate = material("slate", "#4e5866", rough=0.6)
    glass = material("glass", "#2a3644", rough=0.15)
    dark = material("doorway", "#1a1714", rough=1.0)
    along_u = edge in ("XM", "XP")
    doors_far = edge in ("XP", "YP")

    def b(x0, x1, y0, y1, z0, z1, mat, bevel=0.0):
        if along_u:
            m.box(x0, x1, y0, y1, z0, z1, mat, bevel)
        else:
            m.box(y0, y1, x0, x1, z0, z1, mat, bevel)

    # The house runs along y from y0 to y1, its doors at y1 when they face +y.
    y0, y1 = (-0.5, 0.36) if doors_far else (-0.36, 0.5)
    b(-0.38, 0.38, y0, y1, 0, 2.3, brick, 0.004)
    b(-0.39, 0.39, y0 - 0.01, y1 + 0.01, 2.3, 2.42, trim)
    # A pitched slate roof with a smoke vent along the ridge.
    ridge = 3.7
    for side in (-1, 1):
        pts_b = [(side * 0.42, y0 - 0.02, 2.42), (side * 0.42, y1 + 0.02, 2.42)]
        pts_t = [(0, y0 - 0.02, ridge), (0, y1 + 0.02, ridge)]
        if along_u:
            m.wedge([pts_b[0], pts_b[1], (side * 0.41, y1 + 0.02, 2.46), (side * 0.41, y0 - 0.02, 2.46)],
                    [pts_t[0], pts_t[1], (0, y1 + 0.02, ridge + 0.04), (0, y0 - 0.02, ridge + 0.04)], slate)
        else:
            m.wedge([(y0 - 0.02, side * 0.42, 2.42), (y1 + 0.02, side * 0.42, 2.42), (y1 + 0.02, side * 0.41, 2.46), (y0 - 0.02, side * 0.41, 2.46)],
                    [(y0 - 0.02, 0, ridge), (y1 + 0.02, 0, ridge), (y1 + 0.02, 0, ridge + 0.04), (y0 - 0.02, 0, ridge + 0.04)], slate)
    b(-0.06, 0.06, y0 + 0.15, y1 - 0.15, ridge, ridge + 0.35, trim, 0.003)
    # Gable ends filled in brick.
    for yy in (y0, y1):
        if along_u:
            m.wedge([(-0.38, yy - 0.004, 2.42), (0.38, yy - 0.004, 2.42), (0, yy - 0.004, ridge)], [(-0.38, yy + 0.004, 2.42), (0.38, yy + 0.004, 2.42), (0, yy + 0.004, ridge)], brick)
        else:
            m.wedge([(yy - 0.004, -0.38, 2.42), (yy - 0.004, 0.38, 2.42), (yy - 0.004, 0, ridge)], [(yy + 0.004, -0.38, 2.42), (yy + 0.004, 0.38, 2.42), (yy + 0.004, 0, ridge)], brick)
    # Arched windows down the long sides, and the doorway.
    for side in (-1, 1):
        for k in range(3):
            y = y0 + 0.14 + k * (y1 - y0 - 0.28) / 2
            b(side * 0.381 - 0.002, side * 0.381 + 0.002, y - 0.05, y + 0.05, 0.9, 1.8, glass)
            b(side * 0.382 - 0.002, side * 0.382 + 0.002, y - 0.06, y + 0.06, 1.8, 1.9, trim)
    dy = y1 if doors_far else y0
    b(-0.2, 0.2, dy - 0.003, dy + 0.003, 0, 1.95, dark)
    b(-0.23, 0.23, dy - 0.006, dy + 0.006, 1.95, 2.08, trim)


def signal(m, red):
    """A semaphore: a white post with a ladder, the arm level for stop or raised for clear, and its lamp."""
    white = material("post white", "#e4e0d8", rough=0.6)
    black = material("signal black", "#202020", rough=0.6)
    arm = material("arm red", "#b8302a", rough=0.5)
    m.upright(0, 0, 0, 4.4, 0.016, 0.012, white, segments=10)
    m.lathe(0, 0, 4.4, [(0.014, 0), (0.02, 0.1), (0.008, 0.25), (0.0, 0.3)], black)
    for k in range(10):
        z = 0.4 + k * 0.38
        m.box(0.018, 0.04, -0.012, 0.012, z, z + 0.03, black)
    m.box(0.038, 0.042, -0.012, 0.012, 0.2, 4.0, black)
    # The arm, pivoting at the post, sticking out to the left of the track.
    ang = 0.0 if red else math.radians(40)
    length = 0.16
    cx, cz = -0.02, 3.9
    tip_x = cx - length * math.cos(ang)
    tip_z = cz + length * math.sin(ang) / rig.RISE
    m.wedge([(cx, -0.003, cz - 0.08), (tip_x, -0.003, tip_z - 0.08), (tip_x, -0.003, tip_z + 0.08), (cx, -0.003, cz + 0.08)],
            [(cx, 0.003, cz - 0.08), (tip_x, 0.003, tip_z - 0.08), (tip_x, 0.003, tip_z + 0.08), (cx, 0.003, cz + 0.08)], arm)
    lamp = material("lamp red" if red else "lamp green", "#ff3a2a" if red else "#3aff5a", rough=0.2)
    m.box(0.008, 0.03, -0.014, 0.014, 3.55, 3.75, black, 0.002)
    m.box(0.012, 0.026, 0.0135, 0.0155, 3.6, 3.7, lamp)


def colour_light(m, red):
    """A colour light signal of the 1930s on: a plain mast, a black target with two lamps, the lit one red or green."""
    grey = material("mast grey", "#7a7c7e", metal=0.4, rough=0.5)
    black = material("signal black", "#202020", rough=0.6)
    m.upright(0, 0, 0, 3.9, 0.014, 0.012, grey, segments=10)
    m.box(-0.012, 0.012, -0.012, 0.012, 0.0, 0.25, black)
    m.tube(0.004, 0.012, 0.0, 3.55, 0.045, black, segments=24)
    m.box(-0.012, 0.012, -0.006, 0.008, 3.2, 3.9, black, 0.003)
    on = material("lamp red" if red else "lamp green", "#ff3a2a" if red else "#3aff5a", rough=0.2)
    off = material("lamp off", "#3a3a3a", rough=0.3)
    m.tube(0.012, 0.016, 0.0, 3.68, 0.011, off if red else on, segments=16)
    m.tube(0.012, 0.016, 0.0, 3.42, 0.011, on if red else off, segments=16)
    for k in range(9):
        z = 0.4 + k * 0.36
        m.box(0.014, 0.034, -0.01, 0.01, z, z + 0.025, black)


# ---- track --------------------------------------------------------------------------------
#
# Built in the tile's own terms, from its corner: the piece runs between the middles of two of the tile's
# edges, in game (u, v), lifted to the shape it lies on. Rail ends land exactly on the edges, so pieces join up.

EDGE_MID = {"XM": (0.0, 0.5), "XP": (1.0, 0.5), "YM": (0.5, 0.0), "YP": (0.5, 1.0)}
PIECE_EDGES = {"X": ("XM", "XP"), "Y": ("YM", "YP"), "N": ("YM", "XM"), "E": ("YM", "XP"), "S": ("XP", "YP"), "W": ("XM", "YP")}


def _height(offs, u, v):
    t, r, b, l = offs
    return (t * (1 - u) + r * u) * (1 - v) + (l * (1 - u) + b * u) * v


def _strip(m, a, b, offs, half_top, half_bottom, z0, z1, mat, t0=0.0, t1=1.0):
    """A prism along a piece from t0 to t1, its cross section a trapezoid half_bottom wide at z0 and half_top at z1 steps above the ground."""
    ax, ay = a
    bx, by = b
    length = math.hypot(bx - ax, by - ay)
    nx, ny = -(by - ay) / length, (bx - ax) / length
    sx, sy = ax + (bx - ax) * t0, ay + (by - ay) * t0
    ex, ey = ax + (bx - ax) * t1, ay + (by - ay) * t1
    bm = bmesh.new()

    def vert(u, v, lift):
        return bm.verts.new(rig.game(u, v, _height(offs, u, v) + lift))

    ring = []
    for (u, v) in ((sx, sy), (ex, ey)):
        ring.append([
            vert(u + nx * half_bottom, v + ny * half_bottom, z0),
            vert(u + nx * half_top, v + ny * half_top, z1),
            vert(u - nx * half_top, v - ny * half_top, z1),
            vert(u - nx * half_bottom, v - ny * half_bottom, z0),
        ])
    s, e = ring
    bm.faces.new(list(reversed(s)))
    bm.faces.new(e)
    for i in range(4):
        j = (i + 1) % 4
        bm.faces.new((s[i], s[j], e[j], e[i]))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return m._mesh("strip", bm, mat)


def gravel():
    key = ("gravel",)
    if key in parts._materials:
        return parts._materials[key]
    mat = bpy.data.materials.new("ballast")
    mat.use_nodes = True
    nt = mat.node_tree
    p = nt.nodes["Principled BSDF"]
    p.inputs["Roughness"].default_value = 0.95
    v = nt.nodes.new("ShaderNodeTexVoronoi")
    v.inputs["Scale"].default_value = 700.0
    ramp = nt.nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].color = parts.hexcol("#6a645a")
    ramp.color_ramp.elements[1].color = parts.hexcol("#a8a094")
    nt.links.new(v.outputs["Distance"], ramp.inputs["Fac"])
    nt.links.new(ramp.outputs["Color"], p.inputs["Base Color"])
    bump = nt.nodes.new("ShaderNodeBump")
    bump.inputs["Strength"].default_value = 0.6
    nt.links.new(v.outputs["Distance"], bump.inputs["Height"])
    nt.links.new(bump.outputs["Normal"], p.inputs["Normal"])
    parts._materials[key] = mat
    return mat


def track(m, piece, shape):
    offs = [(shape >> i) & 1 for i in range(4)]
    a = EDGE_MID[PIECE_EDGES[piece][0]]
    b = EDGE_MID[PIECE_EDGES[piece][1]]
    length = math.hypot(b[0] - a[0], b[1] - a[1])
    tie = material("tie", "#4e3626", rough=0.9)
    plate = material("tie plate", "#3e3c3a", metal=0.6, rough=0.6)
    rail = material("rail", "#6a6662", metal=0.9, rough=0.45)
    head = material("rail head", "#d8d4cc", metal=1.0, rough=0.2)
    _strip(m, a, b, offs, 0.17, 0.23, 0.0, 0.14, gravel())
    ties = max(4, round(length * 9))
    for i in range(ties):
        t = (i + 0.5) / ties
        dt = 0.024 / length
        _strip(m, a, b, offs, 0.165, 0.165, 0.12, 0.2, tie, t - dt, t + dt)
        for side in (-1, 1):
            # Plates under each rail, a touch wider than it.
            _plate_strip(m, a, b, offs, side * 0.095, 0.022, 0.2, 0.225, plate, t - dt * 0.8, t + dt * 0.8)
    for side in (-1, 1):
        _plate_strip(m, a, b, offs, side * 0.095, 0.012, 0.225, 0.26, rail, 0.0, 1.0)
        _plate_strip(m, a, b, offs, side * 0.095, 0.004, 0.26, 0.36, rail, 0.0, 1.0)
        _plate_strip(m, a, b, offs, side * 0.095, 0.009, 0.36, 0.42, head, 0.0, 1.0)


def _plate_strip(m, a, b, offs, across, half, z0, z1, mat, t0, t1):
    """A thin strip along a piece, set across it by across tiles, half tiles wide each side."""
    ax, ay = a
    bx, by = b
    length = math.hypot(bx - ax, by - ay)
    nx, ny = -(by - ay) / length, (bx - ax) / length
    return _strip(m, (ax + nx * across, ay + ny * across), (bx + nx * across, by + ny * across), offs, half, half, z0, z1, mat, t0, t1)


# ---- decorations ----------------------------------------------------------------------------

def decor(m, kind, look):
    rng = random.Random(f"{kind}{look}")
    if kind == "boulder":
        granite = material("granite", "#8a807a", rough=0.85)
        for k in range(rng.randint(1, 3)):
            x, y = rng.uniform(-0.1, 0.1), rng.uniform(-0.1, 0.1)
            r = rng.uniform(0.06, 0.11)
            bm = bmesh.new()
            bmesh.ops.create_icosphere(bm, subdivisions=2, radius=r)
            bmesh.ops.scale(bm, vec=(1.0, rng.uniform(0.7, 1.2), 0.65), verts=bm.verts)
            bmesh.ops.translate(bm, vec=(x, y, r * 0.3), verts=bm.verts)
            obj = m._mesh("boulder", bm, granite)
            lumpy(m, obj, r * 0.3, r * 0.8, look * 7 + k)
    elif kind == "shrub":
        green = leafy("shrub leaves", "#2e4a20", "#5a7a36")
        for k in range(rng.randint(6, 9)):
            blob(m, rng.uniform(-0.08, 0.08), rng.uniform(-0.08, 0.08), rng.uniform(0.35, 0.9), rng.uniform(0.055, 0.085), green, look * 9 + k)
    else:
        blades = leafy("tall grass", "#4a6a2a", "#a0a456")
        for k in range(rng.randint(30, 45)):
            x, y = rng.uniform(-0.09, 0.09), rng.uniform(-0.09, 0.09)
            m.upright(x, y, 0, rng.uniform(1.4, 2.6), 0.009, 0.0, blades, segments=4)
