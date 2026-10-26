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


# How big each look of a tree is, from a young one to an old one, so a forest isn't all one height.
TREE_SIZES = (0.6, 0.76, 0.9, 1.04, 1.2)


def tree(m, kind, look, season="summer"):
    """A tree. The leafy kinds can also be drawn in their autumn colours or bare for winter."""
    rng = random.Random(f"{kind}{look}")
    size = TREE_SIZES[look] + rng.uniform(-0.04, 0.04)
    bark = material("bark", "#5a3f2a", rough=0.9)
    if kind in ("spruce", "fir"):
        green = leafy(f"{kind} needles", "#1f3a20" if kind == "spruce" else "#1e3a34", "#3a5e34" if kind == "spruce" else "#3a6656")
        top = 3.0 * size
        m.upright(0, 0, 0, top * 0.4, 0.018, 0.014, bark, segments=8)
        layers = 7
        for k in range(layers):
            t = k / layers
            h0 = top * (0.12 + 0.78 * t)
            r0 = (0.13 if kind == "spruce" else 0.145) * size * (1 - 0.8 * t) + 0.02
            cone = m.upright(rng.uniform(-0.005, 0.005), rng.uniform(-0.005, 0.005), h0, h0 + top * 0.3, r0, 0.004, green, segments=14)
            lumpy(m, cone, r0 * 0.35, 0.05, look * 10 + k)
        return
    if kind == "pine":
        # A lodgepole or jack pine: a tall straight trunk, bare but for a few dead stubs, and a narrow ragged crown of
        # needle tufts on short upturned branches over its top third.
        green = leafy("pine needles", "#22402a", "#58784a")
        trunk = material("pine bark", "#8a5a3a", rough=0.9)
        grey = material("pine stubs", "#6a5a4c", rough=0.9)
        top = 3.4 * size
        m.upright(0, 0, 0, top * 0.94, 0.02, 0.006, trunk, segments=8)
        for k in range(5):
            h = top * rng.uniform(0.25, 0.55)
            a = rng.uniform(0, 2 * math.pi)
            r = rng.uniform(0.025, 0.045) * size
            m.rod((0, 0, h), (r * math.cos(a), r * math.sin(a), h + rng.uniform(-0.05, 0.05)), 0.003, grey, segments=4)
        n = 15
        for k in range(n):
            t = k / (n - 1)
            h = top * (0.58 + 0.38 * t + rng.uniform(-0.02, 0.02))
            a = k * 2.4 + rng.uniform(-0.5, 0.5)
            # Branches longest low in the crown, each ending in a clump of small flat tufts with gaps between.
            reach = (0.08 * (1 - t) + 0.02) * size * rng.uniform(0.7, 1.25)
            x, y = reach * math.cos(a), reach * math.sin(a)
            lift = rng.uniform(0.08, 0.2)
            m.rod((0, 0, h - 0.1), (x, y, h + lift), 0.004, trunk, segments=5)
            r = (0.03 * (1 - t) + 0.022) * size
            for j in range(3 if t < 0.8 else 2):
                f = 1.0 - j * 0.3
                blob(m, x * f + rng.uniform(-0.012, 0.012), y * f + rng.uniform(-0.012, 0.012), h + lift * f + rng.uniform(-0.06, 0.08),
                     r * rng.uniform(0.75, 1.1), green, look * 40 + k * 3 + j, squash=0.5)
        blob(m, 0, 0, top * 0.97, 0.022 * size, green, look * 40 + 99, squash=0.9)
        return
    colours = {
        "summer": {"maple": ("#2c5a22", "#4e8a36"), "birch": ("#4e7a30", "#86b04e"), "aspen": ("#5a8a34", "#9ac05a")},
        "autumn": {"maple": ("#9a2a16", "#e0702a"), "birch": ("#b08a24", "#e8c84a"), "aspen": ("#c09a1c", "#f2d454")},
    }.get(season, {}).get(kind)
    green = leafy(f"{kind} leaves {season}", *colours) if colours else None
    trunk = material("birch bark", "#e4e0d6", rough=0.8) if kind == "birch" else material("aspen bark", "#c4c2b0", rough=0.8) if kind == "aspen" else bark
    top = (2.6 if kind == "maple" else 3.0) * size
    m.upright(0, 0, 0, top * 0.75, 0.024, 0.016, trunk, segments=8)
    if kind == "birch":
        for k in range(4):
            z = top * 0.07 * (k + 1)
            m.box(-0.026, 0.026, -0.006, 0.006, z, z + 0.06, material("birch marks", "#2a2622", rough=0.9))
    # The crown starts low and is widest a third of the way up, as an egg.
    low = 0.32 if kind == "maple" else 0.38
    wide = (0.15 if kind == "maple" else 0.1) * size
    count = 14 if kind == "maple" else 11
    twigs = None
    if green is None:
        # Bare: boughs out to where the leaves would be, twigs off them, and a faint haze of the finest twigs.
        twigs = haze(f"{kind} twigs", "#6a5c54" if kind == "maple" else "#8a7e74")
    for k in range(count):
        t = rng.random()
        h = top * (low + (0.92 - low) * t)
        reach = wide * (0.35 + 0.65 * math.sin(math.pi * min(1.0, 0.25 + t * 0.85)))
        ang = rng.uniform(0, 2 * math.pi)
        r = rng.uniform(0.09, 0.12) * size * (1.1 if kind == "maple" else 0.85)
        x, y = reach * math.cos(ang), reach * math.sin(ang)
        if green is not None:
            blob(m, x, y, h, r, green, look * 10 + k)
            continue
        m.rod((0, 0, top * 0.3), (x, y, h), 0.008, trunk, segments=6)
        for j in range(3):
            a2 = ang + rng.uniform(-1.2, 1.2)
            m.rod((x, y, h), (x + r * 0.8 * math.cos(a2), y + r * 0.8 * math.sin(a2), h + rng.uniform(0.1, 0.4)), 0.004, trunk, segments=5)
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

class _Turned:
    """The model with x and y swapped, so a piece laid out along y can be drawn along x."""

    def __init__(self, m):
        self.m = m

    def box(self, x0, x1, y0, y1, z0, z1, mat, bevel=0.0):
        return self.m.box(y0, y1, x0, x1, z0, z1, mat, bevel)

    def upright(self, x, y, *a, **k):
        return self.m.upright(y, x, *a, **k)

    def rod(self, a, b, *r, **k):
        return self.m.rod((a[1], a[0], a[2]), (b[1], b[0], b[2]), *r, **k)

    def wedge(self, bottom, top, mat):
        return self.m.wedge([(y, x, z) for x, y, z in bottom], [(y, x, z) for x, y, z in top], mat)

    def lathe(self, x, y, *a, **k):
        return self.m.lathe(y, x, *a, **k)


# Platform canopies by era, from the year the station went up: wooden to 1915, the steel butterfly to 1970, then a
# modern shelter. The canopy's underside stays at 2.1, just over the tallest coach and caboose.
CANOPY_ERAS = 3


def station(m, along_u, canopy=True, era=1):
    """A platform tile with its track running along y (the game's u) or along x. The track itself comes from the track
    sprite. With no canopy, for the tile in front of a station building, the far platform keeps its benches."""
    concrete = material("platform", "#c9c3b5", rough=0.85)
    edge = material("platform edge", "#e2d8a0", rough=0.7)
    timber = material("timber", "#7a4e2e", rough=0.7)
    iron = material("cast iron", "#2e3234", metal=0.6, rough=0.45)
    t = m if along_u else _Turned(m)

    for side in (-1, 1):
        x0, x1 = sorted((side * 0.23, side * 0.5))
        t.box(x0, x1, -0.5, 0.5, 0, 0.42, concrete, 0.004)
        e0, e1 = sorted((side * 0.23, side * 0.255))
        t.box(e0, e1, -0.5, 0.5, 0.42, 0.44, edge)
    # A canopy over the far platform (the -x side), with benches and a lamp.
    if canopy:
        [_canopy_wood, _canopy_butterfly, _canopy_modern][era](t)
    if not canopy or era < 2:
        for y in (-0.18, 0.18):
            t.box(-0.47, -0.43, y - 0.06, y + 0.06, 0.42, 0.62, timber, 0.003)
            t.box(-0.48, -0.475, y - 0.06, y + 0.06, 0.62, 0.85, timber)
    if not canopy or era == 0:
        t.box(0.42, 0.432, 0.38, 0.392, 0.42, 1.7, iron)
        t.box(0.405, 0.447, 0.365, 0.407, 1.7, 1.85, material("lamp glass", "#f4e8b0", rough=0.2), 0.002)
    elif era == 1:
        # An electric globe on a fluted iron post.
        t.upright(0.426, 0.386, 0.42, 0.5, 0.012, 0.009, iron, segments=10)
        t.upright(0.426, 0.386, 0.5, 1.68, 0.006, 0.005, iron, segments=8)
        t.lathe(0.426, 0.386, 1.66, [(0.012, 0), (0.02, 0.08), (0.016, 0.18), (0.0, 0.2)], material("lamp globe", "#f4ecd0", rough=0.2), segments=12)
    else:
        # A tall pole with a lamp head reaching out over the platform.
        pole = material("lamp pole", "#8a8e90", metal=0.6, rough=0.4)
        t.upright(0.44, 0.386, 0.42, 1.8, 0.006, 0.004, pole, segments=8)
        t.rod((0.44, 0.386, 1.78), (0.39, 0.386, 1.84), 0.003, pole)
        t.box(0.36, 0.4, 0.376, 0.396, 1.8, 1.85, pole, 0.003)
        t.box(0.364, 0.396, 0.379, 0.393, 1.795, 1.802, material("lamp lens", "#fff4d8", rough=0.2))


def _valance(t, x, y0, y1, z, mat, d=0.004, deep=0.1, gap=0.016):
    """A board valance along the canopy's edge at x, boards hanging down from z, long and short in turn as cut points."""
    t.box(x - d, x + d, y0, y1, z - 0.03, z, mat)
    n = round((y1 - y0) / gap)
    for k in range(n):
        y = y0 + (k + 0.5) * (y1 - y0) / n
        t.box(x - d, x + d, y - gap * 0.36, y + gap * 0.36, z - (deep if k % 2 == 0 else deep * 0.55), z - 0.03, mat)


def _canopy_wood(t):
    """An umbrella shed of timber: square posts down the middle with knee braces to the plates, a shingled gable roof
    and a cut valance round its eaves."""
    post = material("canopy posts", "#6a3424", rough=0.75)
    trim = material("canopy trim", "#d6c69c", rough=0.7)
    roof = material("canopy shingles", "#5a4a3c", rough=0.9)
    import houses
    xc = -0.35
    for y in (-0.33, 0.0, 0.33):
        t.box(xc - 0.011, xc + 0.011, y - 0.011, y + 0.011, 0.42, 2.06, post, 0.003)
        t.box(xc - 0.016, xc + 0.016, y - 0.016, y + 0.016, 0.42, 0.5, post)
        # Knee braces up to the cross beam and along to the ridge plate.
        for d in (-1, 1):
            t.rod((xc + d * 0.01, y, 1.72), (xc + d * 0.1, y, 2.05), 0.005, post)
            t.rod((xc, y + d * 0.01, 1.78), (xc, y + d * 0.08, 2.03), 0.005, post)
        t.box(-0.5, -0.2, y - 0.008, y + 0.008, 2.03, 2.1, post)
    t.box(xc - 0.012, xc + 0.012, -0.5, 0.5, 2.03, 2.1, post)
    for x in (-0.49, -0.21):
        t.box(x - 0.008, x + 0.008, -0.5, 0.5, 2.06, 2.12, post)
    # The roof: two slopes from eaves at 2.14 to the ridge at 2.32, open at the tile's ends to run on along the platform.
    for xa in (-0.53, -0.17):
        houses.slab(t, [(xa, -0.5, 2.12), (xa, 0.5, 2.12), (xc, 0.5, 2.28), (xc, -0.5, 2.28)], 0.04, roof)
        # Shingle courses showing as lines up the slope.
        for f in (0.2, 0.4, 0.6, 0.8):
            x, z = xa + (xc - xa) * f, 2.16 + 0.16 * f
            t.box(x - 0.004, x + 0.004, -0.5, 0.5, z - 0.004, z + 0.01, material("shingle course", "#45382e", rough=0.9))
    t.box(xc - 0.008, xc + 0.008, -0.5, 0.5, 2.28, 2.34, trim)
    for x in (-0.53, -0.17):
        _valance(t, x, -0.5, 0.5, 2.15, trim)


def _canopy_butterfly(t):
    """A steel butterfly canopy: a row of columns down the middle, cantilevered arms rising to each edge, a roof falling
    to a gutter in the middle with its downpipes down the columns, a fascia round the edges and hanging lamps."""
    steel = material("canopy steel", "#33463c", metal=0.4, rough=0.5)
    deck = material("canopy deck", "#4e5254", metal=0.3, rough=0.6)
    fascia = material("canopy fascia", "#d8ccaa", rough=0.6)
    xc = -0.35
    for y in (-0.33, 0.0, 0.33):
        # An H column: a web and two flanges, with a cast base.
        t.box(xc - 0.004, xc + 0.004, y - 0.01, y + 0.01, 0.42, 2.12, steel)
        for d in (-1, 1):
            t.box(xc - 0.012, xc + 0.012, y + d * 0.01 - 0.0025, y + d * 0.01 + 0.0025, 0.42, 2.12, steel)
            # The arm out to each edge, deepest at the column, and the strut under it.
            t.wedge([(xc, y - 0.004, 1.98), (xc + d * 0.18, y - 0.004, 2.22), (xc + d * 0.18, y + 0.004, 2.22), (xc, y + 0.004, 1.98)],
                    [(xc, y - 0.004, 2.12), (xc + d * 0.18, y - 0.004, 2.28), (xc + d * 0.18, y + 0.004, 2.28), (xc, y + 0.004, 2.12)], steel)
        t.box(xc - 0.02, xc + 0.02, y - 0.02, y + 0.02, 0.42, 0.52, steel, 0.003)
        t.upright(xc + 0.016, y + 0.016, 0.52, 2.1, 0.005, 0.005, deck, segments=6)
        # A lamp hanging from the gutter between the columns.
        if y < 0.3:
            yl = y + 0.165
            t.box(xc - 0.002, xc + 0.002, yl - 0.002, yl + 0.002, 1.88, 2.1, steel)
            t.lathe(xc, yl, 1.72, [(0.006, 0), (0.024, 0.06), (0.026, 0.12), (0.01, 0.17)], material("lamp shade", "#e8e4cc", rough=0.4), segments=12)
    # Purlins along, the deck on them, the gutter and the fascia.
    for d in (-1, 1):
        for f in (0.33, 0.66, 0.97):
            x = xc + d * 0.18 * f
            t.box(x - 0.005, x + 0.005, -0.5, 0.5, 2.06 + 0.16 * f, 2.12 + 0.16 * f, steel)
        t.wedge([(xc, -0.5, 2.12), (xc + d * 0.19, -0.5, 2.28), (xc + d * 0.19, 0.5, 2.28), (xc, 0.5, 2.12)],
                [(xc, -0.5, 2.15), (xc + d * 0.19, -0.5, 2.31), (xc + d * 0.19, 0.5, 2.31), (xc, 0.5, 2.15)], deck)
        # Standing seams across the deck.
        for k in range(8):
            y = -0.5 + (k + 0.5) / 8
            t.wedge([(xc, y - 0.003, 2.15), (xc + d * 0.19, y - 0.003, 2.31), (xc + d * 0.19, y + 0.003, 2.31), (xc, y + 0.003, 2.15)],
                    [(xc, y - 0.003, 2.165), (xc + d * 0.19, y - 0.003, 2.325), (xc + d * 0.19, y + 0.003, 2.325), (xc, y + 0.003, 2.165)], steel)
        x = xc + d * 0.19
        t.box(x - 0.005, x + 0.005, -0.5, 0.5, 2.2, 2.33, fascia)
    t.box(xc - 0.016, xc + 0.016, -0.5, 0.5, 2.06, 2.14, deck)


def _canopy_modern(t):
    """A modern shelter: a light curved canopy on round columns, lights under it, and a glass shelter with a bench and
    heater for the cold."""
    steel = material("shelter steel", "#d4d8da", metal=0.5, rough=0.35)
    roof = material("shelter roof", "#9aa2a6", metal=0.5, rough=0.4)
    glass = material("shelter glass", "#a8c4d0", rough=0.1)
    frame = material("shelter frame", "#4a5258", metal=0.5, rough=0.4)
    light = material("canopy light", "#fff4d8", rough=0.2)
    xc = -0.35
    for y in (-0.25, 0.25):
        t.upright(xc, y, 0.42, 2.12, 0.009, 0.009, steel, segments=12)
        t.upright(xc, y, 0.42, 0.46, 0.016, 0.016, steel, segments=12)
        # Forked arms up to the canopy's spine.
        for d in (-1, 1):
            t.rod((xc, y, 1.95), (xc + d * 0.12, y, 2.2), 0.005, steel)
    # The canopy: a shallow arch across, from 2.16 at the edges to 2.32 over the columns, laid as strips.
    n = 8
    pts = []
    for k in range(n + 1):
        f = -1 + 2 * k / n
        pts.append((xc + f * 0.18, 2.16 + 0.16 * (1 - f * f)))
    for (xa, za), (xb, zb) in zip(pts, pts[1:]):
        t.wedge([(xa, -0.5, za - 0.04), (xb, -0.5, zb - 0.04), (xb, 0.5, zb - 0.04), (xa, 0.5, za - 0.04)],
                [(xa, -0.5, za), (xb, -0.5, zb), (xb, 0.5, zb), (xa, 0.5, za)], roof)
    for d in (-1, 1):
        x = xc + d * 0.18
        t.box(x - 0.005, x + 0.005, -0.5, 0.5, 2.1, 2.18, steel)
        t.box(xc + d * 0.08 - 0.008, xc + d * 0.08 + 0.008, -0.45, 0.45, 2.15, 2.17, light)
    t.box(xc - 0.012, xc + 0.012, -0.5, 0.5, 2.18, 2.26, steel)
    # The glass shelter: an aluminium frame, panes all round but a doorway to the platform edge, and a flat roof.
    x0, x1, y0, y1 = -0.48, -0.3, -0.14, 0.14
    top = 1.5
    t.box(x0, x1, y0, y1, 0.42, 0.45, frame)
    for x in (x0, x1):
        t.box(x - 0.003, x + 0.003, y0, y1, 0.45, top, glass)
    for y in (y0, y1):
        t.box(x0, x1, y - 0.003, y + 0.003, 0.45, top, glass)
    for x, y in ((x0, y0), (x0, y1), (x1, y0), (x1, y1), (x1, -0.04), (x1, 0.04), (x0, 0.0)):
        t.box(x - 0.005, x + 0.005, y - 0.005, y + 0.005, 0.45, top, frame)
    # The doorway: no glass between the two posts on the platform side.
    t.box(x1 - 0.004, x1 + 0.004, -0.036, 0.036, 0.45, 1.3, material("doorway", "#1a1714", rough=1.0))
    t.box(x0 - 0.01, x1 + 0.01, y0 - 0.01, y1 + 0.01, top, top + 0.06, frame, 0.003)
    t.box(-0.47, -0.43, -0.12, 0.12, 0.62, 0.66, material("bench steel", "#5a6266", metal=0.5, rough=0.5))
    t.box(-0.475, -0.47, -0.12, 0.12, 0.66, 0.85, material("bench steel", "#5a6266", metal=0.5, rough=0.5))
    t.box(-0.47, -0.45, -0.1, 0.1, top - 0.12, top - 0.06, material("heater", "#e07a3a", rough=0.4))
    # A route sign and a trash bin at the shelter's end.
    t.box(-0.42, -0.34, 0.2, 0.206, 1.2, 1.4, material("station sign", "#2a5a9a", rough=0.5))
    t.box(-0.39, -0.37, 0.2, 0.206, 0.42, 1.2, frame)
    t.upright(-0.45, 0.22, 0.42, 0.75, 0.018, 0.018, material("trash bin", "#3a6a4a", rough=0.6), segments=12)


# The engine house by era, from the game's year: wooden to 1900, brick to 1960, then a steel diesel shop. Each is drawn
# with its doors facing +y, turned to its edge by place(), and runs from the back of the tile to 0.36, the track down
# its middle. Walls to 2.3 and the doorway to 2.2 clear every engine; pantographs come down inside.
DEPOT_ERAS = 3
DX = 0.38
DY0, DY1 = -0.5, 0.36


def depot(m, era):
    """An engine house over the track, its doors facing +y: 0 board and batten with a monitor and wooden smoke jacks,
    1 brick with arched windows, a clerestory and iron smoke jacks, 2 a steel-clad diesel shop with a roll-up door."""
    import houses
    import industries
    [_engine_house_wood, _engine_house_brick, _diesel_shop][era](m, houses, industries)


def _swing_doors(m, wood, brace, y, gap=0.2):
    """Two board doors swung right back against the front wall either side of the doorway, Z-braced."""
    for d in (-1, 1):
        a, b = sorted((d * (gap + 0.006), d * (DX - 0.008)))
        m.box(a, b, y + 0.004, y + 0.014, 0.06, 2.14, wood)
        for z in (0.2, 1.1, 1.98):
            m.box(a, b, y + 0.014, y + 0.02, z, z + 0.06, brace)
        for z0, z1 in ((0.26, 1.1), (1.16, 1.98)):
            m.rod((a + 0.01, y + 0.017, z0), (b - 0.01, y + 0.017, z1 - 0.02), 0.004, brace)
        # Strap hinges on the door post.
        hx = d * (gap + 0.01)
        for z in (0.4, 1.8):
            m.box(min(hx, hx + d * 0.05), max(hx, hx + d * 0.05), y + 0.014, y + 0.02, z, z + 0.04, material("door iron", "#2a2c2e", metal=0.5))


def _monitor(m, z0, top, wall, slats, roof, w=0.11, inset=0.08, trim=None):
    """A monitor along the ridge: low side walls of louvres or windows and its own small gable roof over them."""
    import houses
    y0, y1 = DY0 + inset, DY1 - inset
    m.box(-w, w, y0, y1, z0, top, wall)
    for d in (-1, 1):
        n = round((y1 - y0) / 0.05)
        for k in range(n):
            y = y0 + (k + 0.5) * (y1 - y0) / n
            a, b = sorted((d * w, d * (w + 0.006)))
            m.box(a, b, y - 0.018, y + 0.018, z0 + 0.04, top - 0.04, slats)
    houses.gable(m, -w, w, y0, y1, top, top + 0.16, roof, wall, along_x=False, over=0.025, thick=0.04, trim=trim, fascia=0.03)


def _engine_house_wood(m, houses, industries):
    # About 20 m long and 19 m wide, board and batten in red oxide on a fieldstone footing, cedar shingles, and a
    # louvred monitor along the ridge carrying the smoke out, with square smoke jacks of sheet iron over the stalls.
    wall = material("engine house boards", "#7e3f2c", rough=0.85)
    batten = material("engine house battens", "#683222", rough=0.85)
    trim = material("engine house trim", "#d8c8a0", rough=0.7)
    roof = material("cedar shingles", "#5c4a3a", rough=0.9)
    stone = material("fieldstone", "#8a8278", rough=0.95)
    dark = material("doorway", "#1a1714", rough=1.0)
    jack = material("smoke jack", "#2e2c2a", metal=0.4, rough=0.7)
    glass = houses.glass()
    eave = 2.3
    m.box(-DX - 0.006, DX + 0.006, DY0, DY1 + 0.006, 0, 0.14, stone, 0.003)
    m.box(-DX, DX, DY0, DY1, 0.14, eave, wall, 0.003)
    industries.boards(m, -DX, DX, DY0, DY1, 0.14, eave, batten, gap=0.024)
    houses.gable(m, -DX, DX, DY0, DY1, eave, 3.42, roof, wall, along_x=False, over=0.035, thick=0.06, trim=trim, fascia=0.05)
    _monitor(m, 3.05, 3.55, wall, material("louvres", "#3a2a22", rough=0.9), roof, trim=trim)
    for y in (-0.3, 0.12):
        m.box(-0.026, 0.026, y - 0.026, y + 0.026, 3.5, 3.92, jack)
        m.lathe(0, y, 3.9, [(0.05, 0), (0.0, 0.1)], jack, segments=4)
    # Small-paned windows down each side, corner boards.
    for d in (-1, 1):
        for k in range(4):
            y = DY0 + 0.12 + k * 0.2
            _end_pane(m, d * DX, d, y, 1.0, 1.7, 0.06, glass, trim)
        for y in (DY0, DY1):
            m.box(d * DX - 0.008, d * DX + 0.008, y - 0.008, y + 0.008, 0.14, eave, trim)
    # The doorway with its posts and header, and the doors swung open.
    m.box(-0.2, 0.2, DY1 - 0.003, DY1 + 0.003, 0.0, 2.2, dark)
    for d in (-1, 1):
        a, b = sorted((d * 0.2, d * 0.225))
        m.box(a, b, DY1 - 0.004, DY1 + 0.008, 0, 2.2, trim)
    m.box(-0.225, 0.225, DY1 - 0.004, DY1 + 0.008, 2.2, 2.28, trim)
    _swing_doors(m, material("engine house doors", "#6e3424", rough=0.85), trim, DY1)
    # Cordwood for the engines stacked along the side.
    logs = material("cordwood", "#8a6a48", rough=0.9)
    y = -0.44
    while y < 0.2:
        industries.log_stack(m, DX + 0.03, DX + 0.1, y, 0.013, 3, logs)
        y += 0.03


def _engine_house_brick(m, houses, industries):
    # Red brick on a stone base course, pilasters between round-headed windows, a slate roof with a glazed clerestory
    # and round iron smoke jacks over the stall, a brick arch over the doors, and a lean-to machine shop with its stack.
    brick = material("brick", "#9a5a3c", rough=0.85)
    trim = material("brick trim", "#7a4430", rough=0.85)
    stone = material("base course", "#a8a090", rough=0.9)
    slate = material("slate", "#4e5866", rough=0.6)
    dark = material("doorway", "#1a1714", rough=1.0)
    jack = material("smoke jack", "#2e2c2a", metal=0.4, rough=0.7)
    sash = material("window sash", "#d8c8a0", rough=0.7)
    glass = houses.glass()
    eave = 2.3
    m.box(-DX, DX, DY0, DY1, 0, eave, brick, 0.004)
    m.box(-DX - 0.006, DX + 0.006, DY0 - 0.006, DY1 + 0.006, 0, 0.3, stone)
    m.box(-DX - 0.01, DX + 0.01, DY0 - 0.01, DY1 + 0.01, 2.2, 2.32, trim)
    houses.gable(m, -DX, DX, DY0, DY1, 2.32, 3.4, slate, brick, along_x=False, over=0.04, thick=0.06, trim=trim, fascia=0.05)
    _monitor(m, 3.05, 3.55, trim, glass, slate, trim=trim)
    for y in (-0.3, 0.12):
        m.upright(0, y, 3.5, 3.92, 0.024, 0.024, jack, segments=12)
        m.lathe(0, y, 3.88, [(0.026, 0), (0.05, 0.03), (0.012, 0.1), (0.0, 0.11)], jack, segments=12)
    # Pilasters and arched windows down each side, a window in the back gable.
    for d in (-1, 1):
        x = d * DX
        for k in range(5):
            y = DY0 + k * (DY1 - DY0) / 4
            houses.on_wall(m, "x", x, d, y - 0.016, y + 0.016, 0, 0.012, 0.3, 2.2, trim)
        for k in range(4):
            y = DY0 + (k + 0.5) * (DY1 - DY0) / 4
            _end_pane(m, x, d, y, 0.85, 1.75, 0.08, glass, sash)
            m.tube_x(min(x, x + d * 0.006), max(x, x + d * 0.006), y, 1.75, 0.052, trim, segments=16)
            m.tube_x(min(x, x + d * 0.009), max(x, x + d * 0.009), y, 1.75, 0.04, glass, segments=16)
            houses.on_wall(m, "x", x, d, y - 0.05, y + 0.05, 0, 0.012, 0.8, 0.85, stone)
    # The doorway under a brick arch, and the doors swung open.
    m.box(-0.2, 0.2, DY1 - 0.003, DY1 + 0.003, 0.0, 2.2, dark)
    for d in (-1, 1):
        a, b = sorted((d * 0.2, d * 0.235))
        m.box(a, b, DY1 - 0.004, DY1 + 0.01, 0.3, 2.2, trim)
    m.box(-0.24, 0.24, DY1 - 0.004, DY1 + 0.01, 2.2, 2.32, trim)
    m.box(-0.025, 0.025, DY1 - 0.004, DY1 + 0.014, 2.16, 2.34, stone)
    _swing_doors(m, material("engine house doors red", "#5a2a1e", rough=0.85), sash, DY1, gap=0.205)
    # A lean-to machine shop on the -x side with its own stack.
    x0, x1, y0, y1 = -0.49, -DX, -0.46, -0.08
    m.box(x0, x1, y0, y1, 0, 1.3, brick, 0.003)
    houses.slab(m, [(x0 - 0.02, y0 - 0.02, 1.3), (x0 - 0.02, y1 + 0.02, 1.3), (x1, y1 + 0.02, 1.62), (x1, y0 - 0.02, 1.62)], 0.05, slate)
    for y in (-0.38, -0.16):
        _end_pane(m, x0, -1, y, 0.45, 1.05, 0.07, glass, sash)
    houses.chimney(m, -0.44, -0.12, 1.3, 2.6, brick, 0.018)
    # Oil drums and a sand box by the doors.
    drum = material("oil drums", "#3a4a3a", rough=0.6)
    for k, (x, y) in enumerate(((0.3, 0.42), (0.34, 0.45), (0.3, 0.47))):
        m.upright(x, y, 0, 0.36, 0.016, 0.016, drum, segments=12)
    m.box(-0.36, -0.26, 0.4, 0.47, 0, 0.3, material("sand box", "#6a5a44", rough=0.85), 0.003)


def _diesel_shop(m, houses, industries):
    # A steel-clad shop on a block base, a low roof with exhaust fans and skylights, a roll-up door half way up under
    # its roll, yellow bollards, a block office on the +x side, and a fuel tank on saddles on the -x side.
    clad = material("shop cladding", "#a9adae", rough=0.6, metal=0.2)
    rib = material("shop ribs", "#929899", rough=0.6, metal=0.2)
    block = material("shop block", "#b8b0a0", rough=0.9)
    roof = material("shop roof", "#6a7072", metal=0.4, rough=0.5)
    blue = material("shop trim", "#2a4a6e", rough=0.5)
    dark = material("doorway", "#1a1714", rough=1.0)
    slats = material("roll-up door", "#c4c8c8", metal=0.4, rough=0.45)
    yellow = material("bollards", "#e0b020", rough=0.5)
    sky = material("skylights", "#c8d8d4", rough=0.3)
    glass = houses.glass()
    eave = 2.3
    m.box(-DX, DX, DY0, DY1, 0, 0.5, block, 0.003)
    industries.clad_box(m, -DX, DX, DY0, DY1, 0.5, eave, clad, rib, gap=0.03)
    m.box(-DX - 0.008, DX + 0.008, DY0 - 0.008, DY1 + 0.008, 2.18, 2.3, blue)
    houses.gable(m, -DX, DX, DY0, DY1, 2.3, 2.85, roof, clad, along_x=False, over=0.02, thick=0.05)
    for d in (-1, 1):
        for k in range(3):
            y = DY0 + 0.15 + k * 0.28
            houses.slab(m, [(d * 0.08, y - 0.06, 2.8), (d * 0.08, y + 0.06, 2.8), (d * 0.24, y + 0.06, 2.58), (d * 0.24, y - 0.06, 2.58)], 0.03, sky)
    for y in (-0.36, -0.08, 0.2):
        industries.roof_vent(m, 0, y, 2.9, rib, r=0.028)
    # A band of windows high on each side.
    for d in (-1, 1):
        houses.on_wall(m, "x", d * DX, d, DY0 + 0.06, DY1 - 0.06, 0, 0.008, 1.6, 1.9, glass)
    # The roll-up door, raised most of the way, its roll in a hood above, steel jambs and bollards.
    m.box(-0.2, 0.2, DY1 - 0.003, DY1 + 0.003, 0.0, 2.2, dark)
    m.box(-0.2, 0.2, DY1 + 0.003, DY1 + 0.01, 1.7, 2.18, slats)
    for k in range(7):
        z = 1.72 + k * 0.066
        m.box(-0.2, 0.2, DY1 + 0.01, DY1 + 0.013, z, z + 0.012, rib)
    m.box(-0.23, 0.23, DY1, DY1 + 0.04, 2.18, 2.3, blue, 0.004)
    for d in (-1, 1):
        a, b = sorted((d * 0.2, d * 0.225))
        m.box(a, b, DY1 - 0.004, DY1 + 0.01, 0, 2.2, blue)
        m.upright(d * 0.25, DY1 + 0.04, 0, 0.42, 0.012, 0.012, yellow, segments=10)
        m.box(d * 0.26 - 0.015, d * 0.26 + 0.015, DY1 + 0.008, DY1 + 0.016, 1.9, 1.96, material("wall lamp", "#fff4d8", rough=0.3))
    # The office and locker room in block on the +x side, flat roofed, windows and a door.
    x0, x1, y0, y1 = DX, 0.49, -0.42, 0.18
    m.box(x0, x1, y0, y1, 0, 1.5, block, 0.003)
    m.box(x0, x1 + 0.01, y0 - 0.01, y1 + 0.01, 1.5, 1.6, blue)
    for y in (-0.32, -0.18, -0.04):
        _end_pane(m, x1, 1, y, 0.6, 1.15, 0.08, glass, blue)
    m.box(x1, x1 + 0.006, 0.06, 0.12, 0, 1.0, blue)
    # The fuel tank on saddles, its fill line and a sand bin on legs.
    tank = material("fuel tank", "#d4d0c4", metal=0.3, rough=0.5)
    m.tube(-0.45, 0.05, -0.44, 0.48, 0.045, tank, segments=20)
    for y in (-0.36, -0.04):
        m.box(-0.48, -0.4, y - 0.015, y + 0.015, 0, 0.36, block)
    m.rod((-0.44, 0.05, 0.48), (-0.44, 0.2, 0.48), 0.006, blue)
    m.rod((-0.44, 0.2, 0.48), (-0.44, 0.2, 0.0), 0.006, blue)
    for x in (-0.32, -0.24):
        for y in (0.4, 0.47):
            m.rod((x, y, 0), (x, y, 1.2), 0.004, blue)
    m.lathe(-0.28, 0.435, 1.0, [(0.0, 0), (0.012, 0.0), (0.05, 0.25), (0.05, 0.75), (0.0, 0.8)], tank, segments=12)


# ---- station buildings ---------------------------------------------------------------------
# A station building stands on its own tile beside the platform, its front facing +y (the platform edge), turned
# by place() like the houses. A metre is 0.04 tiles; the floor is at platform height, 0.42 steps, a storey 1.25.

FLOOR = 0.42


def _walls_along(m, x0, x1, y, d, z0, z1, mat, thick=0.004):
    """A thin plate on the wall at y, standing out d of a side (1 front, -1 back), for battens, panes and trim."""
    m.box(x0, x1, min(y, y + d * thick), max(y, y + d * thick), z0, z1, mat)


def _pane(m, x, y, d, z0, z1, w, glass, frame):
    """A framed window in the wall at y, its middle at x."""
    _walls_along(m, x - w / 2 - 0.006, x + w / 2 + 0.006, y, d, z0 - 0.05, z1 + 0.05, frame, 0.004)
    _walls_along(m, x - w / 2, x + w / 2, y, d, z0, z1, glass, 0.007)


def _end_pane(m, x, d, y, z0, z1, w, glass, frame):
    """A framed window in the end wall at x, its middle at y."""
    m.box(min(x, x + d * 0.004), max(x, x + d * 0.004), y - w / 2 - 0.006, y + w / 2 + 0.006, z0 - 0.05, z1 + 0.05, frame)
    m.box(min(x, x + d * 0.007), max(x, x + d * 0.007), y - w / 2, y + w / 2, z0, z1, glass)


def _hip_roof(m, x0, x1, y0, y1, eave, ridge, roof, over=0.04, bell=0.0, kick=0.2):
    """A hip roof over the box x0..x1 by y0..y1 from its eaves to a ridge along x. bell flares the eaves out that much
    further on a shallow kick, kick steps high, as the bellcast roofs on standard-plan depots."""
    yc = (y0 + y1) / 2
    inset = (y1 - y0) / 2 + over
    if bell:
        o = over + bell
        m.wedge([(x0 - o, y0 - o, eave), (x1 + o, y0 - o, eave), (x1 + o, y1 + o, eave), (x0 - o, y1 + o, eave)],
                [(x0 - over, y0 - over, eave + kick), (x1 + over, y0 - over, eave + kick), (x1 + over, y1 + over, eave + kick), (x0 - over, y1 + over, eave + kick)], roof)
        eave += kick
    a, b = x0 - over + inset, x1 + over - inset
    m.wedge([(x0 - over, y0 - over, eave), (x1 + over, y0 - over, eave), (x1 + over, y1 + over, eave), (x0 - over, y1 + over, eave)],
            [(a, yc - 0.003, ridge), (b, yc - 0.003, ridge), (b, yc + 0.003, ridge), (a, yc + 0.003, ridge)], roof)


def _apron(m, y):
    """The platform carried on from the track's tile up to the building's front at y, at the platform's height."""
    m.box(-0.5, 0.5, y - 0.02, 0.5, 0, FLOOR, material("platform", "#c9c3b5", rough=0.85))


def _cart(m, x, y):
    """A baggage cart: a deck on four iron wheels with a long handle."""
    wood = material("cart deck", "#6e5034", rough=0.8)
    iron = material("cart iron", "#2a2c2e", metal=0.5, rough=0.6)
    m.box(x - 0.05, x + 0.05, y - 0.022, y + 0.022, FLOOR + 0.25, FLOOR + 0.31, wood, 0.002)
    for wx in (x - 0.035, x + 0.035):
        for wy in (y - 0.02, y + 0.02):
            m.box(wx - 0.01, wx + 0.01, wy - 0.003, wy + 0.003, FLOOR, FLOOR + 0.25, iron)
    m.box(x + 0.05, x + 0.1, y - 0.003, y + 0.003, FLOOR + 0.28, FLOOR + 0.31, iron)


def _lamp(m, x, y):
    iron = material("cast iron", "#2e3234", metal=0.6, rough=0.45)
    m.box(x - 0.006, x + 0.006, y - 0.006, y + 0.006, FLOOR, FLOOR + 1.4, iron)
    m.box(x - 0.02, x + 0.02, y - 0.02, y + 0.02, FLOOR + 1.4, FLOOR + 1.55, material("lamp glass", "#f4e8b0", rough=0.2), 0.002)


def _parking(m, y0, y1):
    """An asphalt lot behind the building with its stalls painted on."""
    m.box(-0.5, 0.5, -0.5, y1, 0, 0.025, material("asphalt", "#4a4a4c", rough=0.95))
    paint = material("lot paint", "#e8e4d8", rough=0.8)
    for k in range(9):
        x = -0.4 + k * 0.1
        m.box(x - 0.003, x + 0.003, y0, y0 + 0.2, 0.025, 0.03, paint)


def station_house(m, era):
    """A station building in the style of its era: 0 a board-and-batten wooden depot with a freight end, 1 a
    standard-plan depot of painted boards with a bellcast hip roof and an operator's bay, 2 brick and stone with a hip roof and
    dormers, 3 a flat-roofed brick and glass building with a cantilevered canopy, 4 glass and steel under a curved roof."""
    import houses
    [_depot_wood, _depot_standard, _station_brick, _station_modern, _station_glass][era](m, houses)


def _depot_wood(m, houses):
    # 6 m deep, 18 m long, the last 7 m a freight shed; walls 3.7 m over the floor, a 40 degree gable roof.
    wall = material("depot boards", "#c4a26a", rough=0.85)
    batten = material("battens", "#a8864e", rough=0.85)
    trim = material("depot trim", "#5a3a24", rough=0.7)
    roof = material("cedar shingles", "#5c4a3a", rough=0.9)
    glass = houses.glass()
    x0, x1, y0, y1 = -0.36, 0.36, -0.06, 0.18
    eave = 1.95
    _apron(m, y1)
    houses.paved(m, -0.5, 0.5, -0.5, y0, colour="#8a8070")
    m.box(x0, x1, y0, y1, 0, eave, wall, 0.003)
    # Battens every half metre all round, a corner board at each corner and the post between the rooms.
    x = x0 + 0.012
    while x < x1 - 0.005:
        for y, d in ((y1, 1), (y0, -1)):
            _walls_along(m, x - 0.003, x + 0.003, y, d, 0, eave, batten)
        x += 0.02
    y = y0 + 0.012
    while y < y1 - 0.005:
        for xx, d in ((x1, 1), (x0, -1)):
            m.box(min(xx, xx + d * 0.004), max(xx, xx + d * 0.004), y - 0.003, y + 0.003, 0, eave, batten)
        y += 0.02
    for xx in (x0, x1, 0.08):
        for y, d in ((y1, 1), (y0, -1)):
            _walls_along(m, xx - 0.008, xx + 0.008, y, d, 0, eave, trim, 0.006)
    houses.gable(m, x0, x1, y0, y1, eave, 3.0, roof, wall, along_x=True, over=0.04)
    houses.chimney(m, -0.14, (y0 + y1) / 2, 2.4, 3.3, material("chimney brick", "#8a4a36", rough=0.9), 0.02)
    # The waiting room and office: tall windows and a door front and back.
    for y, d in ((y1, 1), (y0, -1)):
        for x in (-0.3, -0.22, -0.04, 0.03):
            _pane(m, x, y, d, FLOOR + 0.35, FLOOR + 1.1, 0.036, glass, trim)
        _walls_along(m, -0.165, -0.115, y, d, FLOOR, FLOOR + 0.95, material("plank door", "#4a3220", rough=0.9), 0.008)
        # The freight shed's sliding door and its track.
        _walls_along(m, 0.14, 0.3, y, d, FLOOR, FLOOR + 1.15, trim, 0.008)
        _walls_along(m, 0.12, 0.32, y, d, FLOOR + 1.17, FLOOR + 1.22, material("door track", "#2a2c2e", metal=0.5), 0.01)
    _end_pane(m, x0, -1, (y0 + y1) / 2, FLOOR + 0.35, FLOOR + 1.1, 0.036, glass, trim)
    # The name board along the front under the eaves.
    _walls_along(m, -0.3, -0.02, y1, 1, 1.62, 1.82, material("name board", "#ece2c8", rough=0.7), 0.01)
    # A team track dock at the back of the freight shed, crates and a cart on the platform.
    m.box(0.1, 0.34, y0 - 0.08, y0, 0, FLOOR, material("dock planks", "#7a6448", rough=0.9), 0.002)
    crate = material("crates", "#9a7a4e", rough=0.9)
    m.box(0.2, 0.24, y1 + 0.03, y1 + 0.07, FLOOR, FLOOR + 0.35, crate, 0.003)
    m.box(0.25, 0.28, y1 + 0.03, y1 + 0.06, FLOOR, FLOOR + 0.25, crate, 0.003)
    m.upright(0.31, y1 + 0.05, FLOOR, FLOOR + 0.38, 0.016, 0.016, material("barrel", "#6a4a2a", rough=0.8), segments=12)
    _cart(m, -0.02, y1 + 0.12)


def _depot_standard(m, houses):
    # 7 m deep, 22 m long; walls 3.7 m over the floor, eaves out 1.8 m on brackets, a bay window for the operator.
    # Painted wood in CPR colours: a dark red body, clapboard below a belt rail and board-and-batten above, buff trim.
    wall = material("depot paint red", "#6a2a20", rough=0.7)
    lap = material("clapboard shadow", "#43180f", rough=0.85)
    batten = material("batten red", "#5a2219", rough=0.7)
    trim = material("buff trim", "#dcc89a", rough=0.7)
    roof = material("depot shingles", "#3e4a42", rough=0.85)
    glass = houses.glass()
    x0, x1, y0, y1 = -0.44, 0.44, -0.08, 0.2
    eave = 1.95
    belt = FLOOR + 0.3
    _apron(m, y1)
    houses.paved(m, -0.5, 0.5, -0.5, y0, colour="#8a8070")
    m.box(x0, x1, y0, y1, 0, eave, wall, 0.003)
    # Clapboard: the lower edge of each board stands out a little and shades the one under it.
    z = 0.06
    while z < belt - 0.02:
        m.box(x0 - 0.003, x1 + 0.003, y0 - 0.003, y1 + 0.003, z, z + 0.018, lap)
        z += 0.075
    x = x0 + 0.018
    while x < x1 - 0.01:
        for y, d in ((y1, 1), (y0, -1)):
            _walls_along(m, x - 0.003, x + 0.003, y, d, belt, eave, batten, 0.005)
        x += 0.026
    y = y0 + 0.018
    while y < y1 - 0.01:
        for xx, d in ((x1, 1), (x0, -1)):
            m.box(min(xx, xx + d * 0.005), max(xx, xx + d * 0.005), y - 0.003, y + 0.003, belt, eave, batten)
        y += 0.026
    m.box(x0 - 0.006, x1 + 0.006, y0 - 0.006, y1 + 0.006, belt, belt + 0.06, trim)
    m.box(x0 - 0.004, x1 + 0.004, y0 - 0.004, y1 + 0.004, 0, 0.06, trim)
    m.box(x0 - 0.006, x1 + 0.006, y0 - 0.006, y1 + 0.006, eave - 0.1, eave, trim)
    for xx in (x0, x1):
        for y in (y0, y1):
            m.box(xx - 0.008, xx + 0.008, y - 0.008, y + 0.008, 0, eave, trim)
    _hip_roof(m, x0, x1, y0, y1, eave, 3.45, roof, over=0.03, bell=0.05, kick=0.18)
    brick = material("chimney brick", "#8a4a36", rough=0.9)
    for x in (-0.2, 0.18):
        houses.chimney(m, x, (y0 + y1) / 2, 2.6, 3.75, brick, 0.02)
    # Brackets under the eaves all round.
    for k in range(9):
        x = x0 + 0.04 + k * (x1 - x0 - 0.08) / 8
        for y, d in ((y1, 1), (y0, -1)):
            m.wedge([(x - 0.004, y, eave - 0.45), (x - 0.004, y, eave), (x - 0.004, y + d * 0.07, eave)],
                    [(x + 0.004, y, eave - 0.45), (x + 0.004, y, eave), (x + 0.004, y + d * 0.07, eave)], trim)
    for xx, d in ((x0, -1), (x1, 1)):
        for y in (y0 + 0.07, y1 - 0.07):
            m.wedge([(xx, y - 0.004, eave - 0.45), (xx, y - 0.004, eave), (xx + d * 0.07, y - 0.004, eave)],
                    [(xx, y + 0.004, eave - 0.45), (xx, y + 0.004, eave), (xx + d * 0.07, y + 0.004, eave)], trim)
    # The operator's bay in the middle of the front, three sides of glass to see up and down the line.
    bay_b = [(-0.055, y1, 0), (0.055, y1, 0), (0.035, y1 + 0.04, 0), (-0.035, y1 + 0.04, 0)]
    m.wedge(bay_b, [(x, y, eave - 0.12) for x, y, _ in bay_b], wall)
    pane_b = [(-0.052, y1, FLOOR + 0.45), (0.052, y1, FLOOR + 0.45), (0.038, y1 + 0.044, FLOOR + 0.45), (-0.038, y1 + 0.044, FLOOR + 0.45)]
    m.wedge(pane_b, [(x, y, FLOOR + 1.2) for x, y, _ in pane_b], glass)
    m.wedge([(x, y, eave - 0.12) for x, y, _ in bay_b], [(x, y, eave - 0.06) for x, y, _ in bay_b], trim)
    # The waiting room to one side, the baggage room to the other.
    for y, d in ((y1, 1), (y0, -1)):
        for x in (-0.38, -0.3, -0.12, 0.12):
            _pane(m, x, y, d, FLOOR + 0.4, FLOOR + 1.15, 0.036, glass, trim)
        _walls_along(m, -0.225, -0.175, y, d, FLOOR, FLOOR + 0.95, material("panel door", "#3a2a1c", rough=0.6), 0.008)
        _walls_along(m, 0.25, 0.37, y, d, FLOOR, FLOOR + 1.1, material("baggage door", "#5a2620", rough=0.7), 0.008)
        _walls_along(m, 0.245, 0.375, y, d, FLOOR + 1.1, FLOOR + 1.16, trim, 0.01)
    for xx, d in ((x0, -1), (x1, 1)):
        _end_pane(m, xx, d, (y0 + y1) / 2, FLOOR + 0.4, FLOOR + 1.15, 0.036, glass, trim)
    # Name boards on the ends, the platform lamp and a cart.
    for xx, d in ((x0, -1), (x1, 1)):
        m.box(min(xx, xx + d * 0.01), max(xx, xx + d * 0.01), y0 + 0.04, y1 - 0.04, 1.55, 1.75, material("name board", "#ece2c8", rough=0.7))
    m.box(-0.2, -0.1, y0 - 0.06, y0, 0, FLOOR, material("dock planks", "#7a6448", rough=0.9), 0.002)
    _lamp(m, -0.36, 0.44)
    _cart(m, 0.3, y1 + 0.13)


def _station_brick(m, houses):
    # 8.5 m deep, 21 m long; a stone base to the window sills, brick walls 4.3 m over the floor, a slate hip roof
    # with three dormers to the platform.
    stone = material("station stone", "#8e8678", rough=0.95)
    brick = material("station brick", "#9a5238", rough=0.9)
    sill = material("stone trim", "#c8bca8", rough=0.8)
    slate = material("slate", "#4e5866", rough=0.6)
    glass = houses.glass()
    frame = material("window frame", "#e8e2d4", rough=0.6)
    x0, x1, y0, y1 = -0.42, 0.42, -0.12, 0.22
    eave = 2.2
    _apron(m, y1)
    houses.paved(m, -0.5, 0.5, -0.5, y0, colour="#9a958c")
    m.box(x0, x1, y0, y1, 0, eave, brick, 0.003)
    m.box(x0 - 0.008, x1 + 0.008, y0 - 0.008, y1 + 0.008, 0, FLOOR + 0.42, stone, 0.003)
    m.box(x0 - 0.01, x1 + 0.01, y0 - 0.01, y1 + 0.01, eave - 0.12, eave, sill)
    for xx in (x0, x1):
        for y in (y0, y1):
            for z in (1.0, 1.3, 1.6, 1.9):
                m.box(xx - 0.012, xx + 0.012, y - 0.012, y + 0.012, z, z + 0.15, stone)
    _hip_roof(m, x0, x1, y0, y1, eave, 3.75, slate, over=0.05)
    for x in (-0.28, 0.28):
        houses.chimney(m, x, (y0 + y1) / 2 - 0.03, 3.0, 4.1, brick, 0.025)
    # Dormers on the front slope, each with a window and its own little gable.
    for x in (-0.22, 0.0, 0.22):
        m.box(x - 0.035, x + 0.035, 0.05, y1 - 0.01, 2.3, 2.95, slate)
        _walls_along(m, x - 0.02, x + 0.02, y1 - 0.01, 1, 2.42, 2.85, glass, 0.006)
        houses.gable(m, x - 0.035, x + 0.035, 0.05, y1 - 0.01, 2.95, 3.3, slate, slate, along_x=False, over=0.01, thick=0.05)
    # Tall windows with stone sills and heads all round, and the entrance in a stone surround in the middle.
    for y, d in ((y1, 1), (y0, -1)):
        for x in (-0.36, -0.27, -0.18, 0.18, 0.27, 0.36):
            _pane(m, x, y, d, FLOOR + 0.5, FLOOR + 1.4, 0.042, glass, frame)
            _walls_along(m, x - 0.03, x + 0.03, y, d, FLOOR + 1.43, FLOOR + 1.52, sill, 0.008)
        _walls_along(m, -0.07, 0.07, y, d, FLOOR, FLOOR + 1.35, sill, 0.008)
        _walls_along(m, -0.045, 0.045, y, d, FLOOR, FLOOR + 1.1, material("oak doors", "#5a3a22", rough=0.6), 0.012)
        _walls_along(m, -0.04, 0.04, y, d, FLOOR + 1.12, FLOOR + 1.3, glass, 0.012)
    for xx, d in ((x0, -1), (x1, 1)):
        for y in (y0 + 0.1, y1 - 0.1):
            _end_pane(m, xx, d, y, FLOOR + 0.5, FLOOR + 1.4, 0.042, glass, frame)
    # A bracketed hood over the platform door, steps up at the back, lamps and a cart.
    houses.slab(m, [(-0.09, y1, 2.0), (0.09, y1, 2.0), (0.09, y1 + 0.08, 1.88), (-0.09, y1 + 0.08, 1.88)], 0.05, slate)
    m.box(-0.08, 0.08, y0 - 0.06, y0, 0, FLOOR, stone, 0.003)
    for x in (-0.3, 0.3):
        _lamp(m, x, 0.44)
    _cart(m, -0.3, y1 + 0.12)


def _station_modern(m, houses):
    # 8 m deep, 19 m long, one storey of 3.8 m with a flat roof; a glass front between brick ends and a canopy
    # cantilevered 4 m out over the platform.
    brick = material("buff brick", "#b08a62", rough=0.9)
    white = material("fascia white", "#e6e4de", rough=0.6)
    curtain = material("curtain glass", "#3e5a6e", metal=0.3, rough=0.1, grime=0.15)
    mullion = material("bronze mullions", "#3a3430", metal=0.5, rough=0.4)
    x0, x1, y0, y1 = -0.38, 0.38, -0.14, 0.18
    top = 2.05
    _apron(m, y1)
    _parking(m, -0.46, y0)
    m.box(x0, x1, y0, y1, 0, top, brick, 0.003)
    houses.flat_top(m, x0, x1, y0, y1, top, white, parapet=0.15)
    # The front glazed from the floor nearly to the roof between brick ends, mullions every 2 m.
    for y, d, a, b in ((y1, 1, -0.26, 0.3), (y0, -1, -0.12, 0.22)):
        _walls_along(m, a, b, y, d, FLOOR + 0.05, top - 0.2, curtain, 0.006)
        k = 0
        while a + k * 0.05 <= b + 0.001:
            x = a + k * 0.05
            _walls_along(m, x - 0.004, x + 0.004, y, d, FLOOR + 0.05, top - 0.2, mullion, 0.01)
            k += 1
        _walls_along(m, a, b, y, d, top - 0.22, top - 0.18, mullion, 0.01)
    # The canopy: a thin slab out from the front with no posts, a red band on its fascia.
    m.box(x0 - 0.06, x1 + 0.06, y1, y1 + 0.16, 1.85, 1.95, white, 0.002)
    m.box(x0 - 0.06, x1 + 0.06, y1 + 0.16, y1 + 0.166, 1.86, 1.92, material("fascia red", "#b8302a", rough=0.6))
    m.box(-0.14, 0.12, -0.08, 0.06, top + 0.06, top + 0.4, material("roof unit", "#9a9c9a", metal=0.3, rough=0.6), 0.003)
    m.box(-0.06, 0.06, y0 - 0.05, y0, 0, FLOOR, material("concrete steps", "#a8a49a", rough=0.9), 0.002)
    for x in (-0.42, 0.42):
        _lamp(m, x, 0.44)


def _station_glass(m, houses):
    # 7 m deep, 15 m long, glass walls on a steel frame over a precast base; the curved roof runs out 5 m over the
    # platform on columns and 2 m at the back.
    base = material("precast base", "#b4aea4", rough=0.9)
    curtain = material("station glass", "#5a7a8a", metal=0.2, rough=0.08, grime=0.1)
    steel = material("frame steel", "#3e4650", metal=0.6, rough=0.4)
    roof = material("curved roof", "#c4c8cc", metal=0.5, rough=0.35)
    band = material("roof band", "#2e6a3e", rough=0.6)
    x0, x1, y0, y1 = -0.3, 0.3, -0.12, 0.16
    top = 2.0
    _apron(m, y1)
    _parking(m, -0.46, y0 - 0.06)
    m.box(x0, x1, y0, y1, 0, FLOOR + 0.25, base, 0.003)
    m.box(x0 + 0.004, x1 - 0.004, y0 + 0.004, y1 - 0.004, FLOOR + 0.25, top, curtain)
    m.box(x0 + 0.01, x1 - 0.01, y0 + 0.01, y1 - 0.01, FLOOR + 0.25, top, material("lobby light", "#d8d4c8", rough=0.8))
    for x in [x0 + k * (x1 - x0) / 10 for k in range(11)]:
        for y in (y0, y1):
            m.box(x - 0.004, x + 0.004, y - 0.004, y + 0.004, FLOOR + 0.25, top, steel)
    for y in [y0 + k * (y1 - y0) / 4 for k in range(5)]:
        for x in (x0, x1):
            m.box(x - 0.004, x + 0.004, y - 0.004, y + 0.004, FLOOR + 0.25, top, steel)
    for z in (FLOOR + 0.25, top - 0.02):
        m.box(x0 - 0.002, x1 + 0.002, y0 - 0.002, y1 + 0.002, z, z + 0.04, steel)
    _walls_along(m, -0.06, 0.06, y1, 1, FLOOR, FLOOR + 1.0, steel, 0.003)
    _walls_along(m, -0.055, 0.055, y1, 1, FLOOR, FLOOR + 0.96, material("door glass", "#2c3a48", rough=0.1), 0.006)
    # The roof, a shallow arch from back to front in flat strips, with a coloured band at each edge.
    ya, yb, n = y0 - 0.08, y1 + 0.2, 12
    pts = [(ya + (yb - ya) * k / n, top + 0.05 + 0.3 * math.sin(math.pi * k / n)) for k in range(n + 1)]
    for (ys, zs), (ye, ze) in zip(pts, pts[1:]):
        houses.slab(m, [(x0 - 0.1, ys, zs), (x1 + 0.1, ys, zs), (x1 + 0.1, ye, ze), (x0 - 0.1, ye, ze)], 0.07, roof)
    for yy, z in ((ya, pts[0][1]), (yb, pts[-1][1])):
        m.box(x0 - 0.1, x1 + 0.1, yy - 0.006, yy + 0.006, z - 0.06, z + 0.08, band)
    # Columns under the platform end of the roof, and benches out there.
    yc = y1 + 0.17
    zc = top + 0.05 + 0.3 * math.sin(math.pi * (yc - ya) / (yb - ya))
    for x in (x0 - 0.05, 0.0, x1 + 0.05):
        m.box(x - 0.008, x + 0.008, yc - 0.008, yc + 0.008, FLOOR, zc + 0.02, steel)
    bench = material("bench steel", "#5a6068", metal=0.4, rough=0.5)
    for x in (-0.17, 0.17):
        m.box(x - 0.05, x + 0.05, yc - 0.03, yc - 0.01, FLOOR, FLOOR + 0.2, bench)
    for x in (-0.45, 0.45):
        _lamp(m, x, 0.44)


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
# edges, in game (u, v), lifted to the shape it lies on. Each piece's ends are cut along the tile's edges, and its
# ballast and rails meet each edge at the same points whatever way it runs, as OpenTTD's do, so a corner piece joins
# a straight one, or the next corner piece, with no step or gap. A corner piece is narrower across for it.

EDGE_MID = {"XM": (0.0, 0.5), "XP": (1.0, 0.5), "YM": (0.5, 0.0), "YP": (0.5, 1.0)}
PIECE_EDGES = {"X": ("XM", "XP"), "Y": ("YM", "YP"), "N": ("YM", "XM"), "E": ("YM", "XP"), "S": ("XP", "YP"), "W": ("XM", "YP")}


def _height(offs, u, v):
    t, r, b, l = offs
    return (t * (1 - u) + r * u) * (1 - v) + (l * (1 - u) + b * u) * v


# How far past each end the unseen track that casts shadows goes, in tiles.
GHOST = 0.35


def _edge_dir(edge, n):
    """Along tile edge [edge], the way that's to the left of a piece whose left normal is [n]."""
    d = (0.0, 1.0) if edge in ("XM", "XP") else (1.0, 0.0)
    return d if d[0] * n[0] + d[1] * n[1] > 0 else (-d[0], -d[1])


def _strip(m, a, b, offs, half_top, half_bottom, z0, z1, mat, t0=0.0, t1=1.0, ends=None, caps=True):
    """A prism along a piece from t0 to t1, its cross section a trapezoid half_bottom wide at z0 and half_top at z1 steps
    above the ground. With [ends], two ways along the edges at a and b, its ends are cut along them and its widths
    measured along them there. Without [caps] its ends are left open, for a rail that runs on into the next piece's."""
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
    for k, (u, v) in enumerate(((sx, sy), (ex, ey))):
        dx, dy = ends[k] if ends and ends[k] else (nx, ny)
        ring.append([
            vert(u + dx * half_bottom, v + dy * half_bottom, z0),
            vert(u + dx * half_top, v + dy * half_top, z1),
            vert(u - dx * half_top, v - dy * half_top, z1),
            vert(u - dx * half_bottom, v - dy * half_bottom, z0),
        ])
    s, e = ring
    cap_start, cap_end = caps if isinstance(caps, tuple) else (caps, caps)
    if cap_start:
        bm.faces.new(list(reversed(s)))
    if cap_end:
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


def track(m, piece, shape, layer):
    """A piece of track on ground of [shape], in two layers drawn apart, so where pieces cross every piece's rails go
    over every piece's bed: [layer] "bed" is the ballast, with the rails' shade on it, and "rails" the ties, plates and
    rails; "ties" is the rails layer without its running rails, for rail_set. What isn't in the layer is still built,
    unseen, for the shade it casts."""
    offs = [(shape >> i) & 1 for i in range(4)]
    ea, eb = PIECE_EDGES[piece]
    a = EDGE_MID[ea]
    b = EDGE_MID[eb]
    length = math.hypot(b[0] - a[0], b[1] - a[1])
    n = (-(b[1] - a[1]) / length, (b[0] - a[0]) / length)
    ends = (_edge_dir(ea, n), _edge_dir(eb, n))
    # How much narrower across the piece it is than along the edges it meets: 1 straight, 0.71 for a corner piece.
    k = abs(ends[0][0] * n[0] + ends[0][1] * n[1])
    tie = material("tie", "#4e3626", rough=0.9)
    plate = material("tie plate", "#3e3c3a", metal=0.6, rough=0.6)
    rail = material("rail", "#6a6662", metal=0.9, rough=0.45)
    head = material("rail head", "#d8d4cc", metal=1.0, rough=0.2)
    bed = [_strip(m, a, b, offs, 0.17, 0.23, 0.0, 0.14, gravel(), ends=ends)]
    rails = []
    ties = max(4, round(length * 9))
    for i in range(ties):
        t = (i + 0.5) / ties
        dt = 0.024 / length
        rails.append(_strip(m, a, b, offs, 0.165 * k, 0.165 * k, 0.12, 0.2, tie, t - dt, t + dt))
        for side in (-1, 1):
            # Plates under each rail, a touch wider than it.
            rails.append(_plate_strip(m, a, b, offs, side * 0.095 * k, 0.022, 0.2, 0.225, plate, t - dt * 0.8, t + dt * 0.8))
    # With "ties" only the ties: rail_set lays the rails of all a tile's pieces together.
    for side in ((-1, 1) if layer != "ties" else ()):
        # Each rail from where it meets one edge to where it meets the other.
        ra = (a[0] + ends[0][0] * side * 0.095, a[1] + ends[0][1] * side * 0.095)
        rb = (b[0] + ends[1][0] * side * 0.095, b[1] + ends[1][1] * side * 0.095)
        rails.append(_strip(m, ra, rb, offs, 0.012, 0.012, 0.225, 0.26, rail, ends=ends, caps=False))
        rails.append(_strip(m, ra, rb, offs, 0.004, 0.004, 0.26, 0.36, rail, ends=ends, caps=False))
        rails.append(_strip(m, ra, rb, offs, 0.009, 0.009, 0.36, 0.42, head, ends=ends, caps=False))
    # The track going on past each end, unseen, so the shade of the rails and ballast runs on into the next piece.
    # The bed layer gets no ballast past its ends: it would shut the light off the ballast's end face, which shows
    # black at a dead end. Where the track runs on, the next piece's ballast covers that face.
    unseen = rails if layer == "bed" else bed
    for (p, q, e) in ((a, b, ends[0]), (b, a, ends[1])):
        dx, dy = (p[0] - q[0]) / length, (p[1] - q[1]) / length
        far = (p[0] + dx * GHOST, p[1] + dy * GHOST)
        if layer != "bed":
            unseen.append(_strip(m, p, far, offs, 0.17, 0.23, 0.0, 0.14, gravel(), ends=(e, e)))
        for side in (-1, 1):
            r0 = (p[0] + e[0] * side * 0.095, p[1] + e[1] * side * 0.095)
            r1 = (far[0] + e[0] * side * 0.095, far[1] + e[1] * side * 0.095)
            unseen.append(_strip(m, r0, r1, offs, 0.012, 0.012, 0.225, 0.42, rail, ends=(e, e)))
    for obj in unseen:
        obj.visible_camera = False
        obj["unseen"] = True


# The pieces in the order the game numbers them, a bit each in a tile's track.
PIECES = ["X", "Y", "N", "E", "S", "W"]
# Each side of where one rail crosses another, the gap left in both for the wheel flanges, in tiles.
FLANGEWAY = 0.028


def _rails_of(piece):
    """A piece's two running rails as ((start, end), (edge way at start, edge way at end)), from edge to edge."""
    ea, eb = PIECE_EDGES[piece]
    a = EDGE_MID[ea]
    b = EDGE_MID[eb]
    length = math.hypot(b[0] - a[0], b[1] - a[1])
    n = (-(b[1] - a[1]) / length, (b[0] - a[0]) / length)
    ends = (_edge_dir(ea, n), _edge_dir(eb, n))
    out = []
    for side in (-1, 1):
        ra = (a[0] + ends[0][0] * side * 0.095, a[1] + ends[0][1] * side * 0.095)
        rb = (b[0] + ends[1][0] * side * 0.095, b[1] + ends[1][1] * side * 0.095)
        out.append(((ra, rb), ends))
    return out


def _crossing(p, q, r, s):
    """Where segment p-q crosses segment r-s inside both, as (t along p-q, t along r-s), or None."""
    dx1, dy1 = q[0] - p[0], q[1] - p[1]
    dx2, dy2 = s[0] - r[0], s[1] - r[1]
    den = dx1 * dy2 - dy1 * dx2
    if abs(den) < 1e-9:
        return None
    t = ((r[0] - p[0]) * dy2 - (r[1] - p[1]) * dx2) / den
    w = ((r[0] - p[0]) * dy1 - (r[1] - p[1]) * dx1) / den
    # Rails that only meet where they start on a tile edge, as at a switch's points, run on together.
    if 0.01 < t < 0.99 and 0.01 < w < 0.99:
        return t, w
    return None


# A switch's blades: how far they run from the points, in tiles, and how far an open one's tip stands off its stock
# rail, against a closed one's, across the rail's middle.
BLADE = 0.3
BLADE_OPEN = 0.06
BLADE_SHUT = 0.021


def _set_route(pieces):
    """Of [pieces] leaving a tile by one edge, the one the switch is set for: the straight one, else the first."""
    straight = [p for p in pieces if p in ("X", "Y")]
    return straight[0] if straight else pieces[0]


def rail_set(m, mask):
    """The ties and rails of every piece in [mask], on level ground, with a gap in each rail wherever another
    piece's rail crosses it: a diamond where two lines cross, a frog at a switch. Where pieces leave by the same edge
    each rail running on to the frog starts as a blade, shut against its stock rail for the route the switch is set
    for and open for the others, with a bar across the tips and a lever stand beside them. Their beds are built
    unseen for the shade, as [track] does."""
    pieces = [p for i, p in enumerate(PIECES) if mask & (1 << i)]
    rail = material("rail", "#6a6662", metal=0.9, rough=0.45)
    head = material("rail head", "#d8d4cc", metal=1.0, rough=0.2)
    steel = material("switch rod", "#3e3c3a", metal=0.6, rough=0.6)
    # Each piece's ballast unseen and its ties seen, as a piece on its own has them.
    for piece in pieces:
        track(m, piece, 0, "ties")
    rails = [(piece, seg, ends) for piece in pieces for (seg, ends) in _rails_of(piece)]
    # The pieces leaving by each edge, for the switches there.
    by_edge = {}
    for piece in pieces:
        for e in PIECE_EDGES[piece]:
            by_edge.setdefault(e, []).append(piece)
    tips = {}
    for k, (piece, (ra, rb), ends) in enumerate(rails):
        length = math.hypot(rb[0] - ra[0], rb[1] - ra[1])
        cuts = []
        crossed = set()
        for j, (other, (oa, ob), _) in enumerate(rails):
            if other == piece:
                continue
            hit = _crossing(ra, rb, oa, ob)
            if hit:
                cuts.append(hit[0])
                crossed.add(other)
        # The rail in runs between the gaps: open where it meets a tile edge, ended where it stops at a gap.
        g = FLANGEWAY / length
        spans = []
        t = 0.0
        for c in sorted(cuts):
            if c - g > t:
                spans.append((t, c - g))
            t = max(t, c + g)
        if t < 1.0:
            spans.append((t, 1.0))
        # At points shared with a piece whose rail this one crosses, it starts as a blade.
        blades = []
        for end, e in enumerate(PIECE_EDGES[piece]):
            sharing = [p for p in by_edge[e] if p != piece]
            if not any(p in crossed for p in sharing):
                continue
            tb = min(BLADE / length, min((abs(c - end) for c in cuts), default=1.0) - g * 2)
            if tb <= 0.0:
                continue
            blades.append((end, e, tb))
        for (end, e, tb) in blades:
            spans = [(max(t0, tb), t1) if end == 0 else (t0, min(t1, 1.0 - tb)) for (t0, t1) in spans]
            spans = [(t0, t1) for (t0, t1) in spans if t1 > t0]
            point = ra if end == 0 else rb
            heel_t = tb if end == 0 else 1.0 - tb
            heel = (ra[0] + (rb[0] - ra[0]) * heel_t, ra[1] + (rb[1] - ra[1]) * heel_t)
            # In from the stock rail, towards the middle of its own piece.
            mid = ((ra[0] + rb[0]) / 2, (ra[1] + rb[1]) / 2)
            other = rails[k ^ 1][1]
            cx = (other[0][0] + other[1][0]) / 2 - mid[0]
            cy = (other[0][1] + other[1][1]) / 2 - mid[1]
            way = ends[end]
            inward = way if way[0] * cx + way[1] * cy > 0 else (-way[0], -way[1])
            off = BLADE_SHUT if piece == _set_route(by_edge[e]) else BLADE_OPEN
            tip = (point[0] + inward[0] * off + (heel[0] - point[0]) * 0.08, point[1] + inward[1] * off + (heel[1] - point[1]) * 0.08)
            _strip(m, tip, heel, (0, 0, 0, 0), 0.009, 0.009, 0.225, 0.26, rail)
            _strip(m, tip, heel, (0, 0, 0, 0), 0.004, 0.004, 0.26, 0.36, rail)
            _strip(m, tip, heel, (0, 0, 0, 0), 0.006, 0.006, 0.36, 0.41, head)
            tips.setdefault(e, []).append((tip, inward))
        for (t0, t1) in spans:
            caps = (t0 > 0.0, t1 < 1.0)
            span_ends = (ends[0] if t0 == 0.0 else None, ends[1] if t1 == 1.0 else None)
            _strip(m, ra, rb, (0, 0, 0, 0), 0.012, 0.012, 0.225, 0.26, rail, t0, t1, ends=span_ends, caps=caps)
            _strip(m, ra, rb, (0, 0, 0, 0), 0.004, 0.004, 0.26, 0.36, rail, t0, t1, ends=span_ends, caps=caps)
            _strip(m, ra, rb, (0, 0, 0, 0), 0.009, 0.009, 0.36, 0.42, head, t0, t1, ends=span_ends, caps=caps)
    for e, ts in tips.items():
        _switch_gear(m, e, ts, by_edge[e], steel)


def _switch_gear(m, edge, tips, pieces, steel):
    """The bar joining a switch's blade tips at [edge], and its lever stand beside the track, out past the end of the
    ties on the side away from where the other routes turn off, with a rod over to the nearest tip."""
    (x0, y0), (x1, y1) = tips[0][0], tips[-1][0]
    if len(tips) > 1:
        _strip(m, (x0, y0), (x1, y1), (0, 0, 0, 0), 0.008, 0.008, 0.2, 0.23, steel)
    mx, my = EDGE_MID[edge]
    along = (0.0, 1.0) if edge in ("XM", "XP") else (1.0, 0.0)
    into = {"XM": (1.0, 0.0), "XP": (-1.0, 0.0), "YM": (0.0, 1.0), "YP": (0.0, -1.0)}[edge]
    # Which way the turning routes go off along the edge, from their far ends.
    turn = 0.0
    for p in pieces:
        if p == _set_route(pieces):
            continue
        far = EDGE_MID[[e for e in PIECE_EDGES[p] if e != edge][0]]
        turn += (far[0] - mx) * along[0] + (far[1] - my) * along[1]
    side = -1.0 if turn > 0 else 1.0
    base = (mx + along[0] * side * 0.25 + into[0] * 0.07, my + along[1] * side * 0.25 + into[1] * 0.07)
    near = min((t for (t, _) in tips), key=lambda t: math.hypot(t[0] - base[0], t[1] - base[1]))
    _strip(m, near, base, (0, 0, 0, 0), 0.006, 0.006, 0.2, 0.23, steel)
    # The stand: a low base, a post, and a red target on top that turns with the switch.
    black = material("stand black", "#2a2826", rough=0.6)
    red = material("target red", "#b8302a", rough=0.5)
    _strip(m, (base[0] - into[0] * 0.025, base[1] - into[1] * 0.025), (base[0] + into[0] * 0.025, base[1] + into[1] * 0.025),
           (0, 0, 0, 0), 0.024, 0.024, 0.0, 0.25, black)
    _strip(m, (base[0] - into[0] * 0.008, base[1] - into[1] * 0.008), (base[0] + into[0] * 0.008, base[1] + into[1] * 0.008),
           (0, 0, 0, 0), 0.007, 0.007, 0.25, 1.15, black)
    _strip(m, (base[0] - into[0] * 0.004, base[1] - into[1] * 0.004), (base[0] + into[0] * 0.004, base[1] + into[1] * 0.004),
           (0, 0, 0, 0), 0.026, 0.026, 0.9, 1.15, red)


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


def road_stop(m, along_u):
    """A drive-through stop beside the road: a glass shelter with a bench, and a sign on a post, on the right of each way."""
    glass = material("shelter glass", "#a8c4d0", rough=0.1)
    frame = material("shelter frame", "#4a5258", metal=0.5, rough=0.4)
    roof = material("shelter roof", "#5a6066", metal=0.3, rough=0.5)
    bench = material("bench", "#7a5636", rough=0.8)
    sign = material("stop sign", "#2a6ab0", rough=0.5)

    def b(x0, x1, y0, y1, z0, z1, mat, bevel=0.0):
        if along_u:
            m.box(x0, x1, y0, y1, z0, z1, mat, bevel)
        else:
            m.box(y0, y1, x0, x1, z0, z1, mat, bevel)

    for side in (-1, 1):
        x = side * 0.42
        y0, y1 = (-0.3, 0.05) if side < 0 else (-0.05, 0.3)
        b(x - 0.035, x + 0.035, y0, y1, 0.0, 0.05, frame)
        b(x + side * 0.03 - 0.004, x + side * 0.03 + 0.004, y0, y1, 0.05, 1.2, glass)
        for y in (y0, y1):
            b(x - 0.035, x + 0.035, y - 0.004, y + 0.004, 0.05, 1.2, glass)
            b(x + side * 0.03 - 0.006, x + side * 0.03 + 0.006, y - 0.006, y + 0.006, 0.0, 1.25, frame)
        b(x - 0.05, x + 0.05, y0 - 0.01, y1 + 0.01, 1.2, 1.28, roof, 0.003)
        b(x + side * 0.005 - 0.015, x + side * 0.005 + 0.015, y0 + 0.04, y1 - 0.04, 0.3, 0.36, bench)
        sy = y1 + 0.08 if side < 0 else y0 - 0.08
        b(x - 0.005, x + 0.005, sy - 0.005, sy + 0.005, 0.0, 1.5, frame)
        b(x - 0.004, x + 0.004, sy - 0.03, sy + 0.03, 1.3, 1.55, sign)


def _arch(x0, x1, eave, top, n=10):
    """Points across a bowstring arch from x0 to x1, eave at the ends and top in the middle."""
    xc, hw = (x0 + x1) / 2, (x1 - x0) / 2
    return [(xc + hw * f, eave + (top - eave) * (1 - f * f)) for f in (-1 + 2 * k / n for k in range(n + 1))]


def garage(m):
    """A transit garage for buses and trucks, its doors facing +y, turned to its edge by place(): a bowstring roofed
    bay hall in block with a red band, two roll-up doors (the middle one open), a brick office and dispatch end with
    a flat roof, and a fuel island in front of it. Doors to 1.95 clear tractors' stacks and streetcar poles."""
    import houses
    import industries
    block = material("garage block", "#b0a898", rough=0.85)
    roof = material("garage roof", "#4e5458", metal=0.4, rough=0.5)
    door = material("garage door", "#5a6e7e", metal=0.4, rough=0.45)
    trim = material("garage trim", "#7a2a22", rough=0.6)
    brick = material("garage brick", "#9a5a3c", rough=0.85)
    stone = material("garage stone", "#c8c0ac", rough=0.85)
    dark = material("doorway", "#1a1714", rough=1.0)
    sky = material("skylights", "#c8d8d4", rough=0.3)
    yellow = material("bollards", "#e0b020", rough=0.5)
    glass = houses.glass()
    # The bay hall: block walls to 2.1, a red band, the bowstring roof rising to 2.6 across it, glazed down its crown.
    x0, x1, y0, y1 = -0.44, 0.22, -0.44, 0.34
    eave = 2.1
    m.box(x0, x1, y0, y1, 0, eave, block, 0.003)
    m.box(x0 - 0.006, x1 + 0.006, y0 - 0.006, y1 + 0.006, 1.98, eave + 0.04, trim)
    arch = _arch(x0 - 0.02, x1 + 0.02, eave + 0.04, 2.62)
    m.loft([(y0 - 0.02, [(x, z) for x, z in arch] + [(x, z - 0.05) for x, z in reversed(arch)]),
            (y1 + 0.02, [(x, z) for x, z in arch] + [(x, z - 0.05) for x, z in reversed(arch)])], {"roof": roof})
    # The arched end walls under it, and the bowstring trusses showing as ribs on the roof.
    for y in (y0, y1):
        inner = _arch(x0, x1, eave + 0.04, 2.57)
        m.loft([(y - 0.004, inner), (y + 0.004, inner)], {"wall": block})
    for k in range(5):
        y = y0 + 0.04 + k * (y1 - y0 - 0.08) / 4
        rib = _arch(x0 - 0.024, x1 + 0.024, eave + 0.05, 2.635)
        m.loft([(y - 0.006, rib + [(x, z - 0.03) for x, z in reversed(rib)]), (y + 0.006, rib + [(x, z - 0.03) for x, z in reversed(rib)])], {"rib": trim})
    xc = (x0 + x1) / 2
    for y in (y0 + 0.08, y0 + 0.34, y0 + 0.6):
        m.box(xc - 0.06, xc + 0.06, y, y + 0.16, 2.6, 2.65, sky, 0.004)
    # Clerestory windows high along the -x side and a row of small windows along the back.
    houses.on_wall(m, "x", x0, -1, y0 + 0.05, y1 - 0.05, 0, 0.007, 1.55, 1.85, glass)
    for k in range(6):
        y = y0 + 0.05 + k * (y1 - y0 - 0.1) / 5
        houses.on_wall(m, "x", x0, -1, y - 0.005, y + 0.005, 0, 0.011, 1.55, 1.85, block)
    # Two doors in front: the open one in the middle over the drive, a shut one beside it, block piers and bollards.
    for a, b, shut in ((-0.15, 0.15, False), (-0.4, -0.2, True)):
        if shut:
            m.box(a, b, y1, y1 + 0.006, 0, 1.95, door)
            for k in range(1, 9):
                z = k * 1.95 / 9
                m.box(a, b, y1 + 0.006, y1 + 0.009, z - 0.008, z + 0.008, roof)
            m.box(a + 0.03, b - 0.03, y1 + 0.006, y1 + 0.009, 1.0, 1.2, glass)
        else:
            m.box(a, b, y1 - 0.003, y1 + 0.003, 0, 1.95, dark)
            m.box(a, b, y1 + 0.003, y1 + 0.009, 1.68, 1.95, door)
        m.box(a - 0.012, b + 0.012, y1, y1 + 0.03, 1.95, 2.04, roof, 0.003)
        for x in (a - 0.03, b + 0.03):
            m.upright(x, y1 + 0.03, 0, 0.42, 0.011, 0.011, yellow, segments=10)
    # The office and dispatch end: two storeys of brick, stone sills and a coping, a flat roof, and a sign.
    ox0, ox1, oy0, oy1 = x1, 0.44, -0.4, 0.34
    m.box(ox0, ox1, oy0, oy1, 0, 2.3, brick, 0.003)
    m.box(ox0 - 0.004, ox1 + 0.008, oy0 - 0.008, oy1 + 0.008, 2.3, 2.38, stone)
    m.box(ox0 + 0.01, ox1 - 0.01, oy0 + 0.01, oy1 - 0.01, 2.38, 2.42, roof)
    m.box(ox0 - 0.004, ox1 + 0.006, oy0 - 0.006, oy1 + 0.006, 0, 0.2, stone)
    for z0, z1 in ((0.45, 0.95), (1.4, 1.9)):
        for y in (-0.3, -0.16, -0.02, 0.12):
            _end_pane(m, ox1, 1, y, z0, z1, 0.07, glass, stone)
        for x in (0.28, 0.38):
            _pane(m, x, oy1, 1, z0, z1, 0.06, glass, stone)
    # The dispatch office's door with a canopy over it, and the sign board up on the front.
    houses.on_wall(m, "y", oy1, 1, 0.3, 0.36, 0, 0.008, 0.2, 1.0, material("office door", "#2a4a6e", rough=0.5))
    m.box(0.27, 0.39, oy1, oy1 + 0.05, 1.05, 1.1, roof, 0.003)
    houses.on_wall(m, "y", oy1, 1, 0.24, 0.42, 0, 0.01, 2.0, 2.24, material("garage sign", "#ece2c8", rough=0.6))
    # A fuel island beside the drive: a curbed island, two pumps and a light canopy on two posts.
    fx, fy = 0.33, 0.43
    m.box(fx - 0.09, fx + 0.09, fy - 0.025, fy + 0.025, 0, 0.08, stone, 0.004)
    for x in (fx - 0.04, fx + 0.04):
        m.box(x - 0.016, x + 0.016, fy - 0.012, fy + 0.012, 0.08, 0.6, material("fuel pump", "#c8302a", rough=0.5), 0.004)
        m.box(x - 0.012, x + 0.012, fy + 0.012, fy + 0.014, 0.4, 0.52, glass)
    steel = material("fuel canopy", "#d4d8da", metal=0.4, rough=0.4)
    for x in (fx - 0.08, fx + 0.08):
        m.box(x - 0.006, x + 0.006, fy - 0.006, fy + 0.006, 0.08, 1.75, steel)
    m.box(fx - 0.11, fx + 0.11, fy - 0.06, fy + 0.06, 1.75, 1.85, steel, 0.004)
    m.box(fx - 0.112, fx + 0.112, fy - 0.062, fy + 0.062, 1.78, 1.83, trim)
    # The boiler house chimney at the back corner, roof vents along the hall.
    houses.chimney(m, 0.38, -0.36, 2.42, 3.0, brick, 0.022)
    for y in (-0.3, 0.1):
        industries.roof_vent(m, x0 + 0.12, y, 2.45, roof, r=0.02)
