"""Building blocks for models: materials, and shapes placed in a model's own terms.

A model is built facing +y (Blender's y is the game's u), across x, in tiles,
with heights given in steps (rig.RISE tiles each). Everything is added to one
collection so it can be turned to a heading as a whole.

A model can be built lower than its builder draws it: heights over base steps are
scaled by h, as each shape is made. Round parts stay round, shrinking about their
middles, and what's built inside keep() (wheels, horses, people) keeps its size and
only moves with what it stands on.
"""
import math
from contextlib import contextmanager

import bmesh
import bpy
from mathutils import Matrix, Vector

import rig

_materials = {}


def hexcol(h):
    """A colour from #rrggbb, in Blender's linear terms."""
    def lin(c):
        c /= 255
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    return (lin(int(h[1:3], 16)), lin(int(h[3:5], 16)), lin(int(h[5:7], 16)), 1.0)


def material(name, colour, metal=0.0, rough=0.5, clearcoat=0.0, grime=0.45):
    """A material with a little life in it: the colour wanders a few percent, the surface has a fine grain, and
    crevices darken with grime as real paint and metal do. grime is how dark the crevices go."""
    key = (name, colour, metal, rough, clearcoat, grime)
    if key in _materials:
        return _materials[key]
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    p = nt.nodes["Principled BSDF"]
    p.inputs["Metallic"].default_value = metal
    p.inputs["Roughness"].default_value = rough
    if clearcoat:
        p.inputs["Coat Weight"].default_value = clearcoat
    base = hexcol(colour)
    # Colour wandering a few percent over the surface.
    noise = nt.nodes.new("ShaderNodeTexNoise")
    noise.inputs["Scale"].default_value = 220.0
    noise.inputs["Detail"].default_value = 6.0
    tint = nt.nodes.new("ShaderNodeValToRGB")
    tint.color_ramp.elements[0].color = tuple(v * 0.9 for v in base[:3]) + (1,)
    tint.color_ramp.elements[1].color = tuple(min(1.0, v * 1.08) for v in base[:3]) + (1,)
    nt.links.new(noise.outputs["Fac"], tint.inputs["Fac"])
    # Grime in the crevices, from ambient occlusion.
    ao = nt.nodes.new("ShaderNodeAmbientOcclusion")
    ao.inputs["Distance"].default_value = 0.02
    ao.samples = 8
    dirty = nt.nodes.new("ShaderNodeMix")
    dirty.data_type = "RGBA"
    dirty.blend_type = "MULTIPLY"
    nt.links.new(tint.outputs["Color"], dirty.inputs[6])
    grime_ramp = nt.nodes.new("ShaderNodeValToRGB")
    grime_ramp.color_ramp.elements[0].color = (1 - grime, 1 - grime, 1 - grime, 1)
    grime_ramp.color_ramp.elements[1].color = (1, 1, 1, 1)
    nt.links.new(ao.outputs["AO"], grime_ramp.inputs["Fac"])
    nt.links.new(grime_ramp.outputs["Color"], dirty.inputs[7])
    dirty.inputs[0].default_value = 1.0
    nt.links.new(dirty.outputs[2], p.inputs["Base Color"])
    # A fine grain to the surface.
    grain = nt.nodes.new("ShaderNodeTexNoise")
    grain.inputs["Scale"].default_value = 900.0
    bump = nt.nodes.new("ShaderNodeBump")
    bump.inputs["Strength"].default_value = 0.08
    nt.links.new(grain.outputs["Fac"], bump.inputs["Height"])
    nt.links.new(bump.outputs["Normal"], p.inputs["Normal"])
    _materials[key] = m
    return m


class Model:
    """Shapes added to a collection, in the model's own terms: y along it, x across it, z in steps."""

    def __init__(self, name, h=1.0, base=0.0):
        self.col = bpy.data.collections.new(name)
        bpy.context.scene.collection.children.link(self.col)
        self.objects = []
        self.h = h
        self.base = base
        # Inside keep(): how far shapes move up or down, in steps, instead of being scaled.
        self._shift = None

    def z(self, z):
        """Where a height the builder gives, in steps, ends up."""
        if self._shift is not None:
            return z + self._shift
        return z if z <= self.base else self.base + (z - self.base) * self.h

    def fixed(self, z):
        """The height to give for a shape to end up at z steps: for a part that has to meet a kept one, as an axle a wheel."""
        if self._shift is not None:
            return z - self._shift
        return z if z <= self.base else self.base + (z - self.base) / self.h

    def k(self, z):
        """How much round parts shrink at a height of z steps."""
        return 1.0 if self._shift is not None or z <= self.base else self.h

    def _circle(self, z, r):
        """A round section's middle in steps and radius in tiles once lowered: it fits between where its top and bottom go."""
        lo, hi = self.z(z - r / rig.RISE), self.z(z + r / rig.RISE)
        return (lo + hi) / 2, (hi - lo) / 2 * rig.RISE

    @contextmanager
    def keep(self, at=0.0):
        """Shapes made inside keep their size, moved only as far as a height of at steps moves."""
        outer = self._shift
        self._shift = self.z(at) - at
        try:
            yield
        finally:
            self._shift = outer

    def _link(self, obj, mat):
        for c in obj.users_collection:
            c.objects.unlink(obj)
        self.col.objects.link(obj)
        if mat is not None:
            obj.data.materials.clear()
            obj.data.materials.append(mat)
        self.objects.append(obj)
        return obj

    def _mesh(self, name, bm, mat):
        me = bpy.data.meshes.new(name)
        bm.to_mesh(me)
        bm.free()
        obj = bpy.data.objects.new(name, me)
        bpy.context.scene.collection.objects.link(obj)
        return self._link(obj, mat)

    def box(self, x0, x1, y0, y1, z0, z1, mat, bevel=0.0):
        """A box across x0 to x1, along y0 to y1, z0 to z1 steps high, its edges rounded by bevel tiles."""
        z0, z1 = self.z(z0), self.z(z1)
        bm = bmesh.new()
        bmesh.ops.create_cube(bm, size=1.0)
        sx, sy, sz = x1 - x0, y1 - y0, (z1 - z0) * rig.RISE
        bmesh.ops.scale(bm, vec=(sx, sy, sz), verts=bm.verts)
        bmesh.ops.translate(bm, vec=((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2 * rig.RISE), verts=bm.verts)
        obj = self._mesh("box", bm, mat)
        if bevel > 0:
            mod = obj.modifiers.new("bevel", "BEVEL")
            mod.width = bevel
            mod.segments = 2
            mod.limit_method = "NONE"
        return obj

    def tube(self, y0, y1, x, z, r, mat, segments=24, cap=True, oval=False):
        """A cylinder lying along the model from y0 to y1, its middle at x across and z steps up, r tiles round.
        oval keeps its width when lowered, for a roof."""
        z, rz = self._circle(z, r)
        if not oval:
            r = rz
        bm = bmesh.new()
        bmesh.ops.create_cone(bm, cap_ends=cap, cap_tris=False, segments=segments, radius1=r, radius2=r, depth=y1 - y0)
        bmesh.ops.rotate(bm, verts=bm.verts, cent=(0, 0, 0), matrix=Matrix.Rotation(math.radians(90), 3, "X"))
        bmesh.ops.scale(bm, vec=(1, 1, rz / r), verts=bm.verts)
        bmesh.ops.translate(bm, vec=(x, (y0 + y1) / 2, z * rig.RISE), verts=bm.verts)
        obj = self._mesh("tube", bm, mat)
        obj.data.polygons.foreach_set("use_smooth", [True] * len(obj.data.polygons))
        return obj

    def upright(self, x, y, z0, z1, r0, r1, mat, segments=20):
        """An upright cylinder or cone at x, y from z0 to z1 steps, r0 tiles round at the foot and r1 at the top."""
        z0, z1 = self.z(z0), self.z(z1)
        bm = bmesh.new()
        bmesh.ops.create_cone(bm, cap_ends=True, cap_tris=False, segments=segments, radius1=r0, radius2=r1, depth=(z1 - z0) * rig.RISE)
        bmesh.ops.translate(bm, vec=(x, y, (z0 + z1) / 2 * rig.RISE), verts=bm.verts)
        obj = self._mesh("upright", bm, mat)
        obj.data.polygons.foreach_set("use_smooth", [True] * len(obj.data.polygons))
        return obj

    def lathe(self, x, y, z0, profile, mat, segments=24, round=False, lean=0.0, oval=1.0):
        """A turned shape standing at x, y from z0 steps: profile is (radius in tiles, height in steps above z0) from the foot up.
        round shrinks it all over when lowered, as a bubble canopy, where otherwise only its heights come down. lean moves
        the top back along y by that many tiles, for a raked funnel; oval stretches it along y."""
        k = self.k(z0 + max(h for _, h in profile) / 2) if round else 1.0
        top = max(h for _, h in profile) or 1.0
        bm = bmesh.new()
        rings = []
        for r, h in profile:
            ring = []
            dy = -lean * h / top
            for i in range(segments):
                a = 2 * math.pi * i / segments
                ring.append(bm.verts.new((x + r * k * math.cos(a), y + dy + r * k * oval * math.sin(a), self.z(z0 + h) * rig.RISE)))
            rings.append(ring)
        for a, b in zip(rings, rings[1:]):
            for i in range(segments):
                j = (i + 1) % segments
                bm.faces.new((a[i], a[j], b[j], b[i]))
        bm.faces.new(list(reversed(rings[0])))
        bm.faces.new(rings[-1])
        obj = self._mesh("lathe", bm, mat)
        obj.data.polygons.foreach_set("use_smooth", [True] * len(obj.data.polygons))
        return obj

    def rod(self, a, b, r, mat, segments=8):
        """A thin round bar between points a and b, each (x, y, z in steps)."""
        pa = Vector((a[0], a[1], self.z(a[2]) * rig.RISE))
        pb = Vector((b[0], b[1], self.z(b[2]) * rig.RISE))
        d = pb - pa
        bm = bmesh.new()
        bmesh.ops.create_cone(bm, cap_ends=True, cap_tris=False, segments=segments, radius1=r, radius2=r, depth=d.length)
        rot = d.to_track_quat("Z", "Y").to_matrix()
        bmesh.ops.rotate(bm, verts=bm.verts, cent=(0, 0, 0), matrix=rot)
        bmesh.ops.translate(bm, vec=(pa + pb) / 2, verts=bm.verts)
        return self._mesh("rod", bm, mat)

    def wheel(self, y, side, r, mats, spokes=12, counterweight=False):
        """A spoked wheel at y along the model, on side (-1 or 1), r tiles round: a tire, a rim, spokes and a hub."""
        with self.keep():
            self._wheel(y, side, r, mats, spokes, counterweight)

    def _wheel(self, y, side, r, mats, spokes, counterweight):
        tire, body, hub = mats
        x = side * 0.103
        zc = r / rig.RISE
        w = 0.012
        self.tube_x(x - w / 2, x + w / 2, y, zc, r, tire)
        self.tube_x(x - w / 2 + side * 0.002, x + w / 2 + side * 0.002, y, zc, r * 0.86, body, ring_inner=r * 0.74)
        for k in range(spokes):
            a = 2 * math.pi * k / spokes
            p = (x + side * 0.004, y + math.cos(a) * r * 0.76, zc + math.sin(a) * r * 0.76 / rig.RISE)
            self.rod((x + side * 0.004, y, zc), p, r * 0.07, body, segments=6)
        self.tube_x(x + side * 0.003, x + side * 0.011, y, zc, r * 0.2, hub)
        if counterweight:
            # A crescent of weight opposite the crank, drawn as a short thick segment of rim.
            self.box(x + side * 0.002 - 0.004, x + side * 0.002 + 0.004, y - r * 0.45, y + r * 0.45, zc - r * 0.75 / rig.RISE, zc - r * 0.35 / rig.RISE, body)

    def tube_x(self, x0, x1, y, z, r, mat, segments=28, ring_inner=None):
        """A short cylinder across the model from x0 to x1, at y and z steps, r tiles round; a ring if ring_inner is given."""
        z, r1 = self._circle(z, r)
        if ring_inner is not None:
            ring_inner *= r1 / r
        r = r1
        bm = bmesh.new()
        bmesh.ops.create_cone(bm, cap_ends=True, cap_tris=False, segments=segments, radius1=r, radius2=r, depth=abs(x1 - x0))
        bmesh.ops.rotate(bm, verts=bm.verts, cent=(0, 0, 0), matrix=Matrix.Rotation(math.radians(90), 3, "Y"))
        bmesh.ops.translate(bm, vec=((x0 + x1) / 2, y, z * rig.RISE), verts=bm.verts)
        obj = self._mesh("disc", bm, mat)
        obj.data.polygons.foreach_set("use_smooth", [True] * len(obj.data.polygons))
        return obj

    def wedge(self, pts_bottom, pts_top, mat):
        """A solid from a polygon at the bottom to one at the top, each a list of (x, y, z in steps), the same length."""
        bm = bmesh.new()
        bot = [bm.verts.new((x, y, self.z(z) * rig.RISE)) for x, y, z in pts_bottom]
        top = [bm.verts.new((x, y, self.z(z) * rig.RISE)) for x, y, z in pts_top]
        n = len(bot)
        bm.faces.new(list(reversed(bot)))
        bm.faces.new(top)
        for i in range(n):
            j = (i + 1) % n
            bm.faces.new((bot[i], bot[j], top[j], top[i]))
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
        return self._mesh("wedge", bm, mat)

    def shell(self, profile, half, mats, crown=0.0, x=0.0, cols=(-1, -0.94, -0.7, 0, 0.7, 0.94, 1), pillars=True):
        """A body from its side outline, run across from x - half to x + half.

        profile is a list of (y, z in steps, corner radius in tiles, part, width) going round the outline; part names the
        material in mats for the edge to the next point, and width (0 to 1 of half) lets a hood or nose narrow. An
        optional sixth value bows the point forward by that many tiles at the middle, none at the sides, for a
        windshield or nose round in plan. A point
        given twice with two widths makes a step across, as a cab behind a narrower hood. crown drops the roof's edges by
        that many steps. Glass stops short of the outer columns, leaving pillars, unless pillars is False.
        Returns the rounded outline as (y, z) points, in the builder's heights."""
        given = _round(profile)
        pts = _round([(p[0], self.z(p[1])) + tuple(p[2:]) for p in profile])
        crown *= self.k(max(p[1] for p in profile))
        keys = list(mats)
        z_top = max(p[1] for p in pts)
        bm = bmesh.new()
        rings = []
        for y, z, part, w, b in pts:
            roof = min(1.0, max(0.0, (z - (z_top - 0.15)) / 0.15))
            rings.append([bm.verts.new((x + f * half * w, y + b * (1 - f * f), (z - crown * f * f * roof) * rig.RISE)) for f in cols])
        n = len(pts)
        for k in range(n):
            a, b = rings[k], rings[(k + 1) % n]
            part = pts[k][2]
            for c in range(len(cols) - 1):
                face = bm.faces.new((a[c], b[c], b[c + 1], a[c + 1]))
                outer = c == 0 or c == len(cols) - 2
                use = "paint" if part == "glass" and outer and pillars and "paint" in mats else part
                face.material_index = keys.index(use)
        for c, rev in ((0, True), (len(cols) - 1, False)):
            ring = [r[c] for r in rings]
            face = bm.faces.new(list(reversed(ring)) if rev else ring)
            face.material_index = keys.index("paint" if "paint" in mats else keys[0])
            bmesh.ops.triangulate(bm, faces=[face])
        bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-6)
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
        for f in bm.faces:
            f.smooth = True
        for e in bm.edges:
            if len(e.link_faces) == 2 and e.calc_face_angle(0.0) > math.radians(38):
                e.smooth = False
        obj = self._mesh("shell", bm, None)
        for k in keys:
            obj.data.materials.append(mats[k])
        mod = obj.modifiers.new("bevel", "BEVEL")
        mod.width = 0.003
        mod.segments = 2
        mod.limit_method = "ANGLE"
        mod.angle_limit = math.radians(38)
        return [(p[0], p[1]) for p in given]

    def loft(self, sections, mats, part=None, caps=True, sharp=38, round=False):
        """A body through cross-sections along the model: sections is a list of (y, ring), each ring a closed list of
        (x, z in steps) with the same number of points. part(k) names the material in mats for the faces from ring point
        k to the next, else the first. Hulls, fuselages, floats. round shrinks each ring about its middle when lowered,
        as a fuselage, where otherwise only its heights come down."""
        keys = list(mats)
        bm = bmesh.new()
        if round:
            sections = [(y, self._shrink(ring)) for y, ring in sections]
        else:
            sections = [(y, [(x, self.z(z)) for x, z in ring]) for y, ring in sections]
        rings = [[bm.verts.new((x, y, z * rig.RISE)) for x, z in ring] for y, ring in sections]
        n = len(rings[0])
        for a, b in zip(rings, rings[1:]):
            for k in range(n):
                j = (k + 1) % n
                face = bm.faces.new((a[k], a[j], b[j], b[k]))
                face.material_index = keys.index(part(k)) if part else 0
        if caps:
            for ring in (rings[0], rings[-1]):
                face = bm.faces.new(ring)
                face.material_index = keys.index(part(n - 1)) if part else 0
                bmesh.ops.triangulate(bm, faces=[face])
        bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-6)
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
        for f in bm.faces:
            f.smooth = True
        for e in bm.edges:
            if len(e.link_faces) == 2 and e.calc_face_angle(0.0) > math.radians(sharp):
                e.smooth = False
        obj = self._mesh("loft", bm, None)
        for k in keys:
            obj.data.materials.append(mats[k])
        return obj

    def _shrink(self, ring):
        """A cross-section lowered and shrunk about its middle, keeping its shape."""
        lo, hi = min(z for _, z in ring), max(z for _, z in ring)
        mid = (lo + hi) / 2
        f = (self.z(hi) - self.z(lo)) / (hi - lo) if hi > lo else 1.0
        new_mid = (self.z(hi) + self.z(lo)) / 2
        cx = sum(x for x, _ in ring) / len(ring)
        return [(cx + (x - cx) * f, new_mid + (z - mid) * f) for x, z in ring]

    def text(self, body, x, y, z, size, mat, side):
        """Lettering on the model's side (side -1 or 1), centred at y along it and z steps up."""
        cu = bpy.data.curves.new("text", "FONT")
        cu.body = body
        cu.align_x = "CENTER"
        cu.align_y = "CENTER"
        # Lettering comes down less than the body, so it still reads.
        cu.size = size * math.sqrt(self.k(z))
        cu.extrude = 0.0005
        obj = bpy.data.objects.new("text", cu)
        bpy.context.scene.collection.objects.link(obj)
        obj.location = (x, y, self.z(z) * rig.RISE)
        obj.rotation_euler = (math.radians(90), 0, math.radians(90 if side > 0 else -90))
        return self._link(obj, mat)

    def place(self, heading, slope=0):
        """Sets the model on the middle of the tile, facing heading (eighths of a turn from u towards v), tilted up a slope of slope steps over a tile."""
        empty = bpy.data.objects.new("pivot", None)
        bpy.context.scene.collection.objects.link(empty)
        for o in self.objects:
            o.parent = empty
        tilt = math.atan2(slope * rig.RISE, 1.0)
        # From u towards v is from Blender's y towards its x, a turn the clockwise way seen from above.
        empty.rotation_euler = (tilt, 0, -heading * math.pi / 4)
        empty.location = rig.game(0.5, 0.5, 0)
        return empty


def _round(profile):
    """The outline with each corner rounded by its radius, as (y, z, part, width, bow); corners curve in tile space."""
    pts = [(p[0], p[1], p[2], p[3], p[4] if len(p) > 4 else 1.0, p[5] if len(p) > 5 else 0.0) for p in profile]
    n = len(pts)
    out = []
    for k, (y, z, r, part, w, bow) in enumerate(pts):
        py, pz, _, prev_part, pw, _ = pts[k - 1]
        ny, nz, _, _, nw, _ = pts[(k + 1) % n]
        a = Vector((py - y, (pz - z) * rig.RISE))
        b = Vector((ny - y, (nz - z) * rig.RISE))
        if r <= 0 or a.length < 1e-6 or b.length < 1e-6 or abs(a.normalized().dot(b.normalized())) > 0.999:
            out.append((y, z, part, w, bow))
            continue
        half_angle = a.angle(b) / 2
        d = min(r / math.tan(half_angle), a.length * 0.48, b.length * 0.48)
        p0 = Vector((y, z * rig.RISE)) + a.normalized() * d
        p2 = Vector((y, z * rig.RISE)) + b.normalized() * d
        pc = Vector((y, z * rig.RISE))
        steps = 6
        for i in range(steps + 1):
            t = i / steps
            q = p0 * (1 - t) ** 2 + pc * 2 * t * (1 - t) + p2 * t * t
            out.append((q.x, q.y / rig.RISE, prev_part if t < 0.5 else part, w, bow))
    return out


def outline_front(outline, z):
    """How far forward the outline reaches at height z steps."""
    best = -1e9
    n = len(outline)
    for k in range(n):
        (y0, z0), (y1, z1) = outline[k], outline[(k + 1) % n]
        if (z0 - z) * (z1 - z) <= 0 and z0 != z1:
            best = max(best, y0 + (y1 - y0) * (z - z0) / (z1 - z0))
    return best
