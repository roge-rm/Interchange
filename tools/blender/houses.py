"""Town buildings, each on its own lot, built facing +y (the game's +u) with
its front to the road, and turned by place() to face any edge.

In the same terms as the rest: x across, y along, z in steps, the lot
running from -0.5 to 0.5 each way. A storey is STOREY steps, about three
metres; a lot is about 25 metres across. The kinds are those in
packs/base/houses.json, by id.
"""
import math

import parts
import scenery
from parts import material

STOREY = 1.25
WINDOW_W = 0.055
WINDOW_H = 0.62


def slab(m, pts, thick, mat):
    """A flat plate through the points, each (x, y, z in steps), thick steps through."""
    m.wedge(pts, [(x, y, z + thick) for x, y, z in pts], mat)


def gable(m, x0, x1, y0, y1, eave, ridge, roof, wall, along_x=True, over=0.03, thick=0.08, trim=None, fascia=0.07,
          barge=False, returns=False, ends=(True, True), gap=None):
    """A pitched roof over x0..x1 by y0..y1 from the eaves up to its ridge, with the gable ends walled in.

    With trim, the eaves get a fascia and soffit and the gable ends rake boards; barge makes those a deep gingerbread
    bargeboard with drops and a finial, and returns gives the eaves short returns round the gable-end corners. ends says
    which gable ends (low, high side) get rake boards and returns. gap (a, b, top) leaves the high side's slope open between
    a and b along the ridge, for a cross gable that wide rising from the wall there with its ridge top high."""
    # u runs along the ridge, v across it; pt and blk turn them back into x and y.
    if along_x:
        u0, u1, v0, v1 = x0, x1, y0, y1
    else:
        u0, u1, v0, v1 = y0, y1, x0, x1

    def pt(u, v, z):
        return (u, v, z) if along_x else (v, u, z)

    def blk(ua, ub, va, vb, za, zb, mat):
        if along_x:
            m.box(ua, ub, va, vb, za, zb, mat)
        else:
            m.box(va, vb, ua, ub, za, zb, mat)

    vc = (v0 + v1) / 2
    spans = [(u0 - over, u1 + over)]
    for k, va in enumerate((v0 - over, v1 + over)):
        runs = [(u0 - over, gap[0]), (gap[1], u1 + over)] if gap and k == 1 else spans
        if gap and k == 1:
            # Behind the cross gable the slope carries on up from the valleys, which meet at its ridge, gap[2] high.
            gm = (gap[0] + gap[1]) / 2
            vv = va + (gap[2] - eave) / (ridge - eave) * (vc - va)
            for ga in gap[:2]:
                slab(m, [pt(ga, va, eave), pt(gm, vv, gap[2]), pt(gm, vc, ridge), pt(ga, vc, ridge)], thick, roof)
        for ua, ub in runs:
            slab(m, [pt(ua, va, eave), pt(ub, va, eave), pt(ub, vc, ridge), pt(ua, vc, ridge)], thick, roof)
            if trim is not None:
                # The fascia board along the eave and the soffit back to the wall under it.
                blk(ua, ub, va - 0.004, va + 0.004, eave - fascia, eave + thick + 0.004, trim)
                blk(ua, ub, min(va, (v0, v1)[k]), max(va, (v0, v1)[k]), eave - 0.025, eave, trim)
    for u in (u0, u1):
        m.wedge([pt(u - 0.003, v0, eave), pt(u - 0.003, v1, eave), pt(u - 0.003, vc, ridge)], [pt(u + 0.003, v0, eave), pt(u + 0.003, v1, eave), pt(u + 0.003, vc, ridge)], wall)
    if trim is None:
        return
    deep = 0.16 if barge else fascia
    for side, (ue, uw, d) in enumerate(((u0 - over, u0, -1), (u1 + over, u1, 1))):
        if not ends[side]:
            continue
        # Rake boards under each sloping edge of the gable end.
        for va in (v0 - over, v1 + over):
            band = [pt(ue - 0.004, va, eave - deep), pt(ue - 0.004, vc, ridge - deep), pt(ue - 0.004, vc, ridge + thick + 0.01), pt(ue - 0.004, va, eave + thick + 0.01)]
            m.wedge(band, [(x + (0.008 if along_x else 0), y + (0 if along_x else 0.008), z) for x, y, z in band], trim)
            if barge:
                # Gingerbread drops hanging from the board.
                n = 6
                for j in range(1, n):
                    t = j / n
                    v, z = va + (vc - va) * t, eave + (ridge - eave) * t
                    blk(ue - 0.003, ue + 0.003, v - 0.005, v + 0.005, z - deep - 0.09, z - deep + 0.01, trim)
        if barge:
            blk(ue - 0.005, ue + 0.005, vc - 0.006, vc + 0.006, ridge - deep - 0.22, ridge + thick + 0.16, trim)
        if returns:
            # Eave returns: the eave carried a short way round each corner of the gable end, with a little roof on top.
            for va, vb in ((v0 - over, v0 + 0.06), (v1 - 0.06, v1 + over)):
                ua, ub = sorted((uw - d * 0.004, uw + d * 0.03))
                blk(ua, ub, va, vb, eave - fascia, eave + 0.03, trim)
                blk(ua - 0.004, ub + 0.004, va - 0.004, vb + 0.004, eave + 0.03, eave + 0.07, roof)


def hip(m, x0, x1, y0, y1, eave, ridge, roof, over=0.03, thick=0.08, trim=None, fascia=0.07):
    """A hipped roof, sloping on all four sides to a short ridge along the longer side. With trim, a fascia and soffit
    run round the eaves."""
    if trim is not None:
        xa, xb, ya, yb = x0 - over, x1 + over, y0 - over, y1 + over
        for a, b in ((ya, y0), (y1, yb)):
            m.box(xa, xb, a, b, eave - 0.025, eave, trim)
        for a, b in ((xa, x0), (x1, xb)):
            m.box(a, b, y0, y1, eave - 0.025, eave, trim)
        t = 0.004
        for y in (ya, yb):
            m.box(xa - t, xb + t, y - t, y + t, eave - fascia, eave + thick + t, trim)
        for x in (xa, xb):
            m.box(x - t, x + t, ya + t, yb - t, eave - fascia, eave + thick + t, trim)
    x0, x1, y0, y1 = x0 - over, x1 + over, y0 - over, y1 + over
    inset = min(x1 - x0, y1 - y0) / 2
    if x1 - x0 >= y1 - y0:
        yc = (y0 + y1) / 2
        a, b = x0 + inset, x1 - inset
        slab(m, [(x0, y0, eave), (x1, y0, eave), (b, yc, ridge), (a, yc, ridge)], thick, roof)
        slab(m, [(x1, y1, eave), (x0, y1, eave), (a, yc, ridge), (b, yc, ridge)], thick, roof)
        slab(m, [(x0, y1, eave), (x0, y0, eave), (a, yc, ridge)], thick, roof)
        slab(m, [(x1, y0, eave), (x1, y1, eave), (b, yc, ridge)], thick, roof)
    else:
        xc = (x0 + x1) / 2
        a, b = y0 + inset, y1 - inset
        slab(m, [(x0, y0, eave), (x0, y1, eave), (xc, b, ridge), (xc, a, ridge)], thick, roof)
        slab(m, [(x1, y1, eave), (x1, y0, eave), (xc, a, ridge), (xc, b, ridge)], thick, roof)
        slab(m, [(x1, y0, eave), (x0, y0, eave), (xc, a, ridge)], thick, roof)
        slab(m, [(x0, y1, eave), (x1, y1, eave), (xc, b, ridge)], thick, roof)


def windows(m, x0, x1, y0, y1, z0, storeys, per_front, per_side, glass, frame, skip_door=False, w=WINDOW_W, h=WINDOW_H, sill=0.35,
            trim=None, sash=True, skip=()):
    """Windows on all four walls of a box, storey by storey, set a little proud with a frame round each. With trim, each is
    drawn by pane() with a sill and head of that; skip lists (storey, front window) places on the front to leave out."""
    for s in range(storeys):
        z = z0 + s * STOREY + sill
        for k in range(per_front):
            x = x0 + (k + 0.5) * (x1 - x0) / per_front
            for y, d in ((y1, 1), (y0, -1)):
                if skip_door and s == 0 and d == 1 and k == per_front // 2:
                    continue
                if d == 1 and (s, k) in skip:
                    continue
                if trim is not None:
                    pane(m, "y", y, d, x, z, z + h, w, glass, frame, trim, sash)
                    continue
                m.box(x - w / 2 - 0.006, x + w / 2 + 0.006, y + d * 0.002 - 0.003, y + d * 0.002 + 0.003, z - 0.05, z + h + 0.05, frame)
                m.box(x - w / 2, x + w / 2, y + d * 0.005 - 0.003, y + d * 0.005 + 0.003, z, z + h, glass)
        for k in range(per_side):
            y = y0 + (k + 0.5) * (y1 - y0) / per_side
            for x, d in ((x1, 1), (x0, -1)):
                if trim is not None:
                    pane(m, "x", x, d, y, z, z + h, w, glass, frame, trim, sash)
                    continue
                m.box(x + d * 0.002 - 0.003, x + d * 0.002 + 0.003, y - w / 2 - 0.006, y + w / 2 + 0.006, z - 0.05, z + h + 0.05, frame)
                m.box(x + d * 0.005 - 0.003, x + d * 0.005 + 0.003, y - w / 2, y + w / 2, z, z + h, glass)


def on_wall(m, face, at, d, a0, a1, o0, o1, z0, z1, mat):
    """A box on the wall at x or y = at (face "x" or "y"), facing d (1 or -1): a0..a1 along the wall, o0..o1 out from it."""
    p, q = sorted((at + d * o0, at + d * o1))
    if face == "y":
        m.box(a0, a1, p, q, z0, z1, mat)
    else:
        m.box(p, q, a0, a1, z0, z1, mat)


def pane(m, face, at, d, c, z0, z1, w, glass, frame, trim, sash=True, bars=0):
    """A window centred at c in the wall at x or y = at: a frame standing proud of the glass, a sash bar across the middle,
    bars upright mullions, a sill under it and a head over it of trim."""
    b = 0.007
    a0, a1 = c - w / 2, c + w / 2
    on_wall(m, face, at, d, a0, a1, -0.002, 0.003, z0, z1, glass)
    for s0, s1 in ((a0 - b, a0), (a1, a1 + b)):
        on_wall(m, face, at, d, s0, s1, 0, 0.007, z0 - b, z1 + b, frame)
    for zz in (z0 - b, z1):
        on_wall(m, face, at, d, a0, a1, 0, 0.007, zz, zz + b, frame)
    if sash:
        zm = (z0 + z1) / 2
        on_wall(m, face, at, d, a0, a1, 0, 0.005, zm - 0.012, zm + 0.012, frame)
    for k in range(bars):
        a = a0 + (k + 1) * w / (bars + 1)
        on_wall(m, face, at, d, a - 0.003, a + 0.003, 0, 0.005, z0, z1, frame)
    on_wall(m, face, at, d, a0 - b - 0.008, a1 + b + 0.008, 0, 0.014, z0 - b - 0.04, z0 - b, trim)
    on_wall(m, face, at, d, a0 - b - 0.005, a1 + b + 0.005, 0, 0.01, z1 + b, z1 + b + 0.05, trim)


def gothic_pane(m, x, y, z0, z1, point, w, glass, frame, trim):
    """A pointed Gothic window in the front wall at y, its middle at x, from z0 to z1 with its point at point."""
    for (a, out, mat) in ((w / 2 + 0.008, 0.006, frame), (w / 2, 0.009, glass)):
        lo = z0 - 0.008 if mat is frame else z0
        top = point + 0.03 if mat is frame else point
        poly = [(x - a, y, lo), (x + a, y, lo), (x + a, y, z1), (x, y, top), (x - a, y, z1)]
        m.wedge(poly, [(px, py + out, pz) for px, py, pz in poly], mat)
    m.box(x - w / 2 - 0.016, x + w / 2 + 0.016, y, y + 0.014, z0 - 0.05, z0 - 0.008, trim)


def door(m, x, y, mat, frame, w=0.05, h=0.95, z0=0.0, transom=None):
    """A front door in the wall at y, its middle at x, its sill at z0, with a glass transom over it if given."""
    top = h + (0.2 if transom is not None else 0.0)
    m.box(x - w / 2 - 0.008, x + w / 2 + 0.008, y - 0.002, y + 0.006, z0, z0 + top + 0.07, frame)
    m.box(x - w / 2, x + w / 2, y, y + 0.009, z0, z0 + h, mat)
    if transom is not None:
        m.box(x - w / 2, x + w / 2, y, y + 0.008, z0 + h + 0.03, z0 + h + 0.18, transom)


def corner_boards(m, x0, x1, y0, y1, z0, z1, mat, w=0.01):
    """Boards up each corner of a box, standing a little proud of both walls."""
    o = 0.004
    for x, sx in ((x0, -1), (x1, 1)):
        for y, sy in ((y0, -1), (y1, 1)):
            xa, xb = sorted((x + sx * o, x - sx * w))
            ya, yb = sorted((y + sy * o, y - sy * w))
            m.box(xa, xb, ya, yb, z0, z1, mat)


def foundation(m, x0, x1, y0, y1, h, mat, out=0.007):
    """The foundation wall showing under a house, h steps high."""
    m.box(x0 - out, x1 + out, y0 - out, y1 + out, 0, h, mat, 0.002)


def steps(m, x, y, w, rise, mat, n=3, d=1, run=0.022, landing=0.02):
    """Steps from the ground up to rise at the wall at y, out the d side, w wide, their middle at x."""
    # Each step a little wider than the one over it, so their sides don't share a plane (that renders as dark specks).
    for k in range(n):
        reach = landing + k * run
        a, b = sorted((y, y + d * reach))
        half = w / 2 + k * 0.002
        m.box(x - half, x + half, a, b, 0, rise * (n - k) / n, mat)


def lap_siding(m, x0, x1, y0, y1, z0, z1, mat, gap=0.11):
    """Lines of clapboard shadow round a box, each a thin band standing just proud."""
    z = z0
    while z < z1 - 0.02:
        m.box(x0 - 0.002, x1 + 0.002, y0 - 0.002, y1 + 0.002, z, z + 0.012, mat)
        z += gap


def verandah(m, x0, x1, y0, y1, deck, z, roof, floor, post, trim, posts=4, step_at=None, rail=True, brackets=False, taper=None,
             skirt=None, pitch=0.25):
    """A porch along the front wall at y0 out to y1: a deck deck steps high on a skirt, posts up to z with a beam and
    fascia, a roof sloping out from pitch higher at the wall, a railing between the posts and steps at step_at. taper (a
    pier height) sets each post on a brick pier and tapers it, as on a Craftsman porch; brackets adds fretwork at the
    post heads."""
    m.box(x0, x1, y0, y1, 0, deck, floor, 0.002)
    if skirt is not None:
        m.box(x0 + 0.004, x1 - 0.004, y0, y1 + 0.003, 0.02, deck - 0.04, skirt)
    xs = [x0 + 0.01 + k * (x1 - x0 - 0.02) / (posts - 1) for k in range(posts)]
    py = y1 - 0.012
    for x in xs:
        if taper is not None:
            m.box(x - 0.016, x + 0.016, py - 0.016, py + 0.016, 0, taper, skirt if skirt is not None else floor, 0.002)
            sq = [(x - 0.012, py - 0.012, taper), (x + 0.012, py - 0.012, taper), (x + 0.012, py + 0.012, taper), (x - 0.012, py + 0.012, taper)]
            m.wedge(sq, [(px + (x - px) * 0.45, py_ + (py - py_) * 0.45, z) for px, py_, _ in sq], post)
        else:
            m.box(x - 0.006, x + 0.006, py - 0.006, py + 0.006, deck, z, post)
        if brackets:
            for s in (-1, 1):
                m.wedge([(x, py - 0.002, z - 0.22), (x, py - 0.002, z), (x + s * 0.04, py - 0.002, z)],
                        [(x, py + 0.002, z - 0.22), (x, py + 0.002, z), (x + s * 0.04, py + 0.002, z)], trim)
    m.box(x0, x1, py - 0.008, py + 0.008, z - 0.09, z, trim)
    if rail:
        for a, b in zip(xs, xs[1:]):
            if step_at is not None and a < step_at < b:
                continue
            m.box(a, b, py - 0.004, py + 0.004, deck + 0.3, deck + 0.34, trim)
            m.box(a, b, py - 0.003, py + 0.003, deck + 0.03, deck + 0.06, trim)
            t = a + 0.012
            while t < b - 0.006:
                m.box(t - 0.002, t + 0.002, py - 0.002, py + 0.002, deck + 0.06, deck + 0.3, trim)
                t += 0.012
    slab(m, [(x0 - 0.012, y0, z + pitch), (x1 + 0.012, y0, z + pitch), (x1 + 0.012, y1 + 0.02, z), (x0 - 0.012, y1 + 0.02, z)], 0.06, roof)
    m.box(x0 - 0.012, x1 + 0.012, y1 + 0.016, y1 + 0.024, z - 0.06, z + 0.07, trim)
    if step_at is not None:
        steps(m, step_at, y1, 0.07, deck, floor, n=max(1, round(deck / 0.08)))


def face_pane(m, p, q, z0, z1, glass, frame, out, t0=0.18, t1=0.82):
    """A window on a wall running from p to q (each (x, y)), on its outer side by out, from t0 to t1 of the way along."""
    (px, py), (qx, qy) = p, q
    L = ((qx - px) ** 2 + (qy - py) ** 2) ** 0.5
    nx, ny = (qy - py) / L * out, -(qx - px) / L * out

    def strip(a, b, za, zb, o0, o1, mat):
        pts = [(px + (qx - px) * a, py + (qy - py) * a), (px + (qx - px) * b, py + (qy - py) * b)]
        base = [(x + nx * o0, y + ny * o0) for x, y in pts] + [(x + nx * o1, y + ny * o1) for x, y in reversed(pts)]
        m.wedge([(x, y, za) for x, y in base], [(x, y, zb) for x, y in base], mat)

    strip(t0 - 0.06, t1 + 0.06, z0 - 0.05, z1 + 0.05, 0.0, 1.0, frame)
    strip(t0, t1, z0, z1, 1.0, 1.6, glass)


def wall_at(face, at, d, a, o, z):
    """A point on the wall at x or y = at (face "x" or "y"), facing d: a along the wall, o out from it, z up."""
    return (a, at + d * o, z) if face == "y" else (at + d * o, a, z)


def on_face(m, face, at, d, outline, o0, o1, mat):
    """A plate in the shape of outline, (along, z) points going round, on the wall at x or y = at, o0 to o1 out from it."""
    m.wedge([wall_at(face, at, d, a, o0, z) for a, z in outline], [wall_at(face, at, d, a, o1, z) for a, z in outline], mat)


def across(m, face, at, d, a, w, profile, mat):
    """A piece standing out from the wall at x or y = at, w wide about a, its side shape profile in (out, z) points."""
    m.wedge([wall_at(face, at, d, a - w / 2, o, z) for o, z in profile], [wall_at(face, at, d, a + w / 2, o, z) for o, z in profile], mat)


def arc(a0, a1, z, rise, n=6):
    """Points along a shallow arch from a0 to a1 springing at z, rise steps high at its crown."""
    return [(a0 + (a1 - a0) * k / n, z + rise * (1 - (2 * k / n - 1) ** 2)) for k in range(n + 1)]


def arch_band(m, face, at, d, inner, outer, o0, o1, mat):
    """A curved band between two arcs of the same number of points, piece by piece so each piece is flat."""
    for (p, q), (r, s) in zip(zip(inner, inner[1:]), zip(outer, outer[1:])):
        on_face(m, face, at, d, [p, q, s, r], o0, o1, mat)


def arched_pane(m, face, at, d, c, z0, z1, w, glass, frame, head, rise=0.1, sash=True, keystone=True):
    """A window with a segmental arched top in the wall at x or y = at, centred at c, its arch springing at z1: a frame
    round it, a stone head of the same curve over it with a keystone at the crown, and a stone sill."""
    a0, a1, b = c - w / 2, c + w / 2, 0.007
    on_face(m, face, at, d, [(a0, z0), (a1, z0)] + arc(a1, a0, z1, rise), -0.002, 0.003, glass)
    for s0, s1 in ((a0 - b, a0), (a1, a1 + b)):
        on_wall(m, face, at, d, s0, s1, 0, 0.007, z0 - b, z1, frame)
    on_wall(m, face, at, d, a0, a1, 0, 0.007, z0 - b, z0, frame)
    rim = [(a, z + 0.07) for a, z in arc(a0 - b, a1 + b, z1, rise)]
    arch_band(m, face, at, d, arc(a0, a1, z1, rise), rim, 0, 0.007, frame)
    if sash:
        zm = (z0 + z1) / 2
        on_wall(m, face, at, d, a0, a1, 0, 0.005, zm - 0.012, zm + 0.012, frame)
    if head is None:
        return
    ha = 0.012
    arch_band(m, face, at, d, rim, [(a, z + 0.16) for a, z in arc(a0 - b - ha, a1 + b + ha, z1, rise)], 0, 0.011, head)
    if keystone:
        on_wall(m, face, at, d, c - 0.007, c + 0.007, 0, 0.015, z1 + rise + 0.04, z1 + rise + 0.3, head)
    on_wall(m, face, at, d, a0 - b - 0.008, a1 + b + 0.008, 0, 0.014, z0 - b - 0.05, z0 - b, head)


def pointed_pane(m, face, at, d, c, z0, z1, point, w, glass, frame, trim, bars=0):
    """A pointed Gothic window in the wall at x or y = at, its middle at c, from z0 to z1 with its point at point, bars
    upright mullions in it, and a sill of trim."""
    a = w / 2 + 0.008
    on_face(m, face, at, d, [(c - a, z0 - 0.008), (c + a, z0 - 0.008), (c + a, z1), (c, point + 0.03), (c - a, z1)], 0, 0.006, frame)
    a = w / 2
    on_face(m, face, at, d, [(c - a, z0), (c + a, z0), (c + a, z1), (c, point), (c - a, z1)], -0.002, 0.009, glass)
    for k in range(bars):
        x = c - a + (k + 1) * w / (bars + 1)
        on_wall(m, face, at, d, x - 0.003, x + 0.003, 0, 0.012, z0, z1 + (point - z1) * (1 - abs(x - c) / a) - 0.02, frame)
    if bars:
        on_wall(m, face, at, d, c - a, c + a, 0, 0.011, z1 - 0.02, z1 + 0.02, frame)
    on_wall(m, face, at, d, c - a - 0.016, c + a + 0.016, 0, 0.014, z0 - 0.05, z0 - 0.008, trim)


def louvres(m, face, at, d, c, z0, z1, w, frame, slat, point=None):
    """A belfry or vent opening in the wall at x or y = at: slats across a dark back in a frame, pointed if point is given."""
    a = w / 2
    top = [(c, point)] if point is not None else []
    on_face(m, face, at, d, [(c - a - 0.008, z0 - 0.02), (c + a + 0.008, z0 - 0.02), (c + a + 0.008, z1)] +
            ([(c, point + 0.04)] if point is not None else [(c + a + 0.008, z1 + 0.03), (c - a - 0.008, z1 + 0.03)]) + [(c - a - 0.008, z1)],
            0, 0.004, frame)
    on_face(m, face, at, d, [(c - a, z0), (c + a, z0), (c + a, z1)] + top + [(c - a, z1)], 0, 0.006, material("louvre gaps", "#1e1e20", rough=0.95))
    z = z0 + 0.03
    while z < z1 - 0.02:
        on_wall(m, face, at, d, c - a, c + a, 0, 0.009, z, z + 0.035, slat)
        z += 0.075


def buttress(m, face, at, d, a, w, out, z1, mat):
    """A stepped buttress against the wall at x or y = at, w wide about a and out deep at its foot, with a sloping
    weathering at each step, up to z1."""
    h = z1 * 0.5
    across(m, face, at, d, a, w, [(0, 0), (out, 0), (out, h), (out * 0.55, h + 0.12), (out * 0.55, z1 - 0.2), (0, z1)], mat)


def spire(m, x, y, z0, z1, r, mat, sides=8):
    """A spire of sides faces from z0 to its point at z1, r tiles from its middle to each face at its foot."""
    R = r / math.cos(math.pi / sides)
    ring = [(R * math.cos(math.pi / sides * (2 * k + 1)), R * math.sin(math.pi / sides * (2 * k + 1))) for k in range(sides)]
    m.wedge([(x + px, y + py, z0) for px, py in ring], [(x + px * 0.03, y + py * 0.03, z1) for px, py in ring], mat)


def finial(m, x, y, z, h, mat):
    """A finial on a spire's point: a short rod h steps high with a ball on it."""
    m.upright(x, y, z, z + h, 0.0025, 0.002, mat, segments=8)
    m.lathe(x, y, z + h * 0.55, [(0.0, 0.0), (0.007, 0.06), (0.009, 0.15), (0.007, 0.24), (0.0, 0.3)], mat, segments=12)


def bracket_cornice(m, x0, x1, y0, y1, z, mat, frieze, out=0.035, gap=0.067, back=False):
    """A pressed metal cornice round the top of a box at z, along the front, the sides and the back if back: a frieze
    under a projecting crown held up on brackets."""
    walls = [("y", y1, 1, x0, x1), ("x", x1, 1, y0, y1), ("x", x0, -1, y0, y1)] + ([("y", y0, -1, x0, x1)] if back else [])
    for face, at, d, a0, a1 in walls:
        # The crown along the front runs past the corners; the sides' crowns stop at its back.
        e0, e1 = (a0 - out, a1 + out) if face == "y" else (a0, a1)
        on_wall(m, face, at, d, a0, a1, 0, 0.008, z - 0.32, z - 0.08, frieze)
        on_wall(m, face, at, d, e0, e1, 0, out, z - 0.08, z + 0.06, mat)
        on_wall(m, face, at, d, e0 - (0.006 if face == "y" else 0), e1 + (0.006 if face == "y" else 0), 0, out + 0.006, z + 0.06, z + 0.11, mat)
        on_wall(m, face, at, d, e0, e1, 0, out - 0.012, z - 0.12, z - 0.08, mat)
        n = max(1, round((a1 - a0) / gap))
        for k in range(n + 1):
            a = a0 + 0.012 + k * (a1 - a0 - 0.024) / n
            across(m, face, at, d, a, 0.008, [(0, z - 0.38), (0.012, z - 0.38), (out - 0.004, z - 0.12), (0, z - 0.12)], mat)


def sign_text(m, body, face, at, d, c, z, size, mat, o=0.0):
    """Lettering on the wall at x or y = at, facing d, centred at c along it and z steps up, o out from the wall."""
    obj = m.text(body, 0, 0, z, size, mat, 1)
    x, y, _ = wall_at(face, at, d, c, o + 0.001, z)
    obj.location.x, obj.location.y = x, y
    obj.rotation_euler[2] = math.radians({("y", 1): 180, ("y", -1): 0, ("x", 1): 90, ("x", -1): -90}[(face, d)])


def curtain(m, x0, x1, y0, y1, z0, storeys, glass, mullion, gap=0.04, vision=(0.35, 1.2), fin=0.012):
    """A curtain wall round a box from z0 up, storey by storey: vision glass between the spandrels (the box itself) set
    just proud, and upright mullions every gap standing out as fins, the corners taken by the front and back ones."""
    top = z0 + storeys * STOREY
    walls = (("y", y1, 1, x0, x1), ("y", y0, -1, x0, x1), ("x", x1, 1, y0, y1), ("x", x0, -1, y0, y1))
    for s in range(storeys):
        za, zb = z0 + s * STOREY + vision[0], z0 + s * STOREY + vision[1]
        for face, at, d, a0, a1 in walls:
            on_wall(m, face, at, d, a0 + 0.004, a1 - 0.004, -0.002, 0.004, za, zb, glass)
    for face, at, d, a0, a1 in walls:
        n = max(1, round((a1 - a0) / gap))
        for k in range(n + 1):
            if face == "x" and k in (0, n):
                continue
            a = min(max(a0 + k * (a1 - a0) / n, a0 + 0.003), a1 - 0.003)
            on_wall(m, face, at, d, a - 0.003, a + 0.003, 0, fin, z0, top, mullion)


def parking(m, x0, x1, y0, y1, stalls, cars, along_y=False):
    """A surface parking lot: asphalt, painted stall lines across it every stalls tiles and cars, each (place, colour), nosed
    in towards x1 (or y1 if along_y) in the stall at that place along it."""
    m.box(x0, x1, y0, y1, 0, 0.025, material("parking asphalt", "#4e4e50", rough=0.95))
    paint = material("parking lines", "#e8e4d4", rough=0.8)
    a0, a1 = (y0, y1) if not along_y else (x0, x1)
    t = a0 + 0.01
    while t < a1 - 0.005:
        if along_y:
            m.box(t - 0.002, t + 0.002, y1 - 0.24, y1 - 0.02, 0.025, 0.03, paint)
        else:
            m.box(x1 - 0.24, x1 - 0.02, t - 0.002, t + 0.002, 0.025, 0.03, paint)
        t += stalls
    for at, colour in cars:
        if along_y:
            car(m, at, y1 - 0.13, colour, along_y=True)
        else:
            car(m, x1 - 0.13, at, colour, along_y=False)


def lamp_post(m, x, y, h=0.62):
    """A yard lamp: a black post and a lantern."""
    iron = material("lamp post", "#2a2a2c", metal=0.5, rough=0.5)
    m.upright(x, y, 0, h, 0.005, 0.004, iron, segments=8)
    m.box(x - 0.008, x + 0.008, y - 0.008, y + 0.008, h, h + 0.12, material("lamp glass", "#f4e8b0", rough=0.2))
    m.box(x - 0.011, x + 0.011, y - 0.011, y + 0.011, h + 0.12, h + 0.15, iron)


def car(m, x, y, colour="#7a2a2a", along_y=True):
    """A parked car of the 1960s, nose to +y if along_y: a long body, a glass cabin and dark wheels."""
    body = material("car " + colour, colour, metal=0.4, rough=0.35)
    dark = material("tires", "#1e1e1e", rough=0.9)
    chrome = material("car chrome", "#c8ccd0", metal=0.8, rough=0.25)
    L, W = 0.1, 0.038

    def b(a0, a1, c0, c1, z0, z1, mat, bevel=0.0):
        if along_y:
            m.box(x + c0, x + c1, y + a0, y + a1, z0, z1, mat, bevel)
        else:
            m.box(x + a0, x + a1, y + c0, y + c1, z0, z1, mat, bevel)

    for a in (-L * 0.62, L * 0.62):
        b(a - 0.014, a + 0.014, -W - 0.002, W + 0.002, 0, 0.16, dark)
    b(-L, L, -W, W, 0.08, 0.32, body, 0.006)
    b(-L * 0.35, L * 0.35, -W + 0.004, W - 0.004, 0.32, 0.52, glass(), 0.004)
    b(-L * 0.38, L * 0.38, -W + 0.002, W - 0.002, 0.52, 0.56, body, 0.003)
    for a in (-L - 0.003, L - 0.002):
        b(a, a + 0.005, -W, W, 0.1, 0.16, chrome)


def path(m, x, y0, y1, w=0.05):
    """A walk from the front door out to the road."""
    m.box(x - w / 2, x + w / 2, y0, y1, 0, 0.03, material("walk", "#b8b2a6", rough=0.9))


def chimney(m, x, y, z0, z1, mat, w=0.035):
    m.box(x - w, x + w, y - w, y + w, z0, z1, mat, 0.002)
    m.box(x - w - 0.005, x + w + 0.005, y - w - 0.005, y + w + 0.005, z1, z1 + 0.08, mat)


def flat_top(m, x0, x1, y0, y1, z, cap, parapet=0.25, open_y=()):
    """A flat roof with a low parapet and a coping round its edge. open_y leaves out the parapet along y0 or y1 (-1 or
    1 in it), where the roof runs on into a neighbour's."""
    m.box(x0, x1, y0, y1, z, z + 0.06, material("tar roof", "#3a3a3c", rough=0.95))
    # The sides along x run the full length and the ends fit between them, so no two pieces overlap at a corner
    # (overlaps render as dark specks).
    t, o = 0.012, 0.006
    for d, xa, xb, ya, yb in ((-1, x0, x1, y0, y0 + t), (1, x0, x1, y1 - t, y1)):
        if d in open_y:
            continue
        m.box(xa, xb, ya, yb, z, z + parapet - 0.06, cap)
        m.box(xa - o, xb + o, ya - o, yb + o, z + parapet - 0.06, z + parapet, cap)
    ya, yb = y0 + (0 if -1 in open_y else t), y1 - (0 if 1 in open_y else t)
    oa, ob = (0 if -1 in open_y else o), (0 if 1 in open_y else o)
    for xa, xb in ((x0, x0 + t), (x1 - t, x1)):
        m.box(xa, xb, ya, yb, z, z + parapet - 0.06, cap)
        m.box(xa - o, xb + o, ya + oa, yb - ob, z + parapet - 0.06, z + parapet, cap)


# ---- the lot round a building: fences, gardens, trees, sheds and drives ---------------------

# Where a lot's fences run: just inside its edges, so a neighbour's fence on the same line stands beside it.
EDGE = 0.48


def picket(m, a, b, mat, height=0.42, gap=None):
    """A picket fence from a to b, each (x, y), along x or y, open at gap (a place along it) for a walk."""
    (x0, y0), (x1, y1) = a, b
    along_x = abs(x1 - x0) > abs(y1 - y0)
    lo, hi = (min(x0, x1), max(x0, x1)) if along_x else (min(y0, y1), max(y0, y1))
    fixed = y0 if along_x else x0
    t = lo
    while t <= hi + 1e-6:
        if gap is None or abs(t - gap) > 0.035:
            if along_x:
                m.box(t - 0.004, t + 0.004, fixed - 0.003, fixed + 0.003, 0, height, mat)
            else:
                m.box(fixed - 0.003, fixed + 0.003, t - 0.004, t + 0.004, 0, height, mat)
        t += 0.024
    for z in (height * 0.3, height * 0.75):
        segs = [(lo, hi)] if gap is None else [(lo, gap - 0.035), (gap + 0.035, hi)]
        for s0, s1 in segs:
            if s1 <= s0:
                continue
            if along_x:
                m.box(s0, s1, fixed - 0.005, fixed + 0.005, z, z + 0.04, mat)
            else:
                m.box(fixed - 0.005, fixed + 0.005, s0, s1, z, z + 0.04, mat)


def board(m, a, b, mat, height=0.75):
    """A close boarded fence from a to b, along x or y, with posts."""
    (x0, y0), (x1, y1) = a, b
    if abs(x1 - x0) > abs(y1 - y0):
        m.box(min(x0, x1), max(x0, x1), y0 - 0.004, y0 + 0.004, 0, height, mat)
        t = min(x0, x1)
        while t <= max(x0, x1) + 1e-6:
            m.box(t - 0.006, t + 0.006, y0 - 0.007, y0 + 0.007, 0, height + 0.06, mat)
            t += 0.12
    else:
        m.box(x0 - 0.004, x0 + 0.004, min(y0, y1), max(y0, y1), 0, height, mat)
        t = min(y0, y1)
        while t <= max(y0, y1) + 1e-6:
            m.box(x0 - 0.007, x0 + 0.007, t - 0.006, t + 0.006, 0, height + 0.06, mat)
            t += 0.12


def rails(m, a, b, mat):
    """A split rail fence from a to b: posts and three rails."""
    (x0, y0), (x1, y1) = a, b
    along_x = abs(x1 - x0) > abs(y1 - y0)
    n = max(2, int(abs((x1 - x0) if along_x else (y1 - y0)) / 0.16) + 1)
    for k in range(n):
        t = k / (n - 1)
        x, y = x0 + (x1 - x0) * t, y0 + (y1 - y0) * t
        m.box(x - 0.006, x + 0.006, y - 0.006, y + 0.006, 0, 0.55, mat)
    for z in (0.15, 0.3, 0.45):
        m.rod((x0, y0, z), (x1, y1, z), 0.005, mat, segments=5)


def chain(m, a, b, height=0.7):
    """A chain-link fence: thin posts and a see-through grey mesh, drawn as a faint panel."""
    (x0, y0), (x1, y1) = a, b
    metal = material("galvanised", "#9aa0a4", metal=0.6, rough=0.45)
    mesh = material("chain link", "#7c8286", metal=0.5, rough=0.6, grime=0.2)
    along_x = abs(x1 - x0) > abs(y1 - y0)
    if along_x:
        m.box(min(x0, x1), max(x0, x1), y0 - 0.001, y0 + 0.001, 0.02, height, mesh)
    else:
        m.box(x0 - 0.001, x0 + 0.001, min(y0, y1), max(y0, y1), 0.02, height, mesh)
    m.rod((x0, y0, height), (x1, y1, height), 0.003, metal, segments=5)


def fence_round(m, kind, mat=None, front=None, door_x=0.0, side_front=0.32):
    """Fences down the sides and across the back of the lot, and across the front if front is given, open at the walk."""
    sides = [((-EDGE, -EDGE), (-EDGE, side_front)), ((EDGE, -EDGE), (EDGE, side_front)), ((-EDGE, -EDGE), (EDGE, -EDGE))]
    for a, b in sides:
        if kind == "rails":
            rails(m, a, b, mat)
        elif kind == "board":
            board(m, a, b, mat)
        elif kind == "chain":
            chain(m, a, b)
        elif kind == "hedge":
            (x0, y0), (x1, y1) = a, b
            m.box(min(x0, x1) - 0.015, max(x0, x1) + 0.015, min(y0, y1) - 0.015, max(y0, y1) + 0.015, 0, 0.6, material("hedge", "#3a5a2a", rough=1.0), 0.01)
        else:
            picket(m, a, b, mat)
    if front == "picket":
        picket(m, (-EDGE, EDGE), (EDGE, EDGE), mat, gap=door_x)


def yard_tree(m, x, y, size=1.0, seed=1, kind="maple"):
    """A shade tree in the yard: a short trunk and a full crown of leaf blobs from low down."""
    bark = material("bark", "#4a3a2c", rough=0.95)
    m.upright(x, y, 0, 1.8 * size, 0.012 * size, 0.008 * size, bark, segments=8)
    leaves = scenery.leafy("yard " + kind, "#3e6a2a", "#6a9a3e") if kind == "maple" else scenery.leafy("yard elm", "#4a6e2e", "#78a048")
    for k, (dx, dy, dz, r) in enumerate(((0, 0, 2.1, 0.1), (0.06, 0.03, 1.6, 0.085), (-0.05, -0.04, 1.7, 0.085), (0.03, -0.05, 2.6, 0.075),
                                         (-0.04, 0.05, 2.4, 0.08), (0.05, -0.02, 1.3, 0.07), (-0.02, 0.04, 1.2, 0.07))):
        scenery.blob(m, x + dx * size, y + dy * size, dz * size, r * size, leaves, seed * 7 + k)


def shed(m, x, y, w=0.08, d=0.07, colour="#7a5a3e", roof="#4e4844"):
    wall = material("shed " + colour, colour, rough=0.9)
    m.box(x - w, x + w, y - d, y + d, 0, 0.8, wall, 0.002)
    gable(m, x - w, x + w, y - d, y + d, 0.8, 1.2, material("shed roof", roof, rough=0.85), wall, along_x=True, over=0.012, thick=0.04)
    m.box(x - 0.025, x + 0.025, y + d, y + d + 0.004, 0, 0.6, material("shed door", "#3e2e22", rough=0.9))


def garden(m, x0, x1, y0, y1, rows=4):
    """A vegetable or flower bed: dark soil with rows of green."""
    m.box(x0, x1, y0, y1, 0, 0.04, material("garden soil", "#3e2e22", rough=1.0))
    leaf = material("garden rows", "#4e7a32", rough=1.0)
    for k in range(rows):
        y = y0 + (k + 0.5) * (y1 - y0) / rows
        m.box(x0 + 0.01, x1 - 0.01, y - 0.008, y + 0.008, 0.04, 0.16, leaf, 0.004)


def flowers(m, x0, x1, y0, y1):
    """A flower bed along a wall."""
    m.box(x0, x1, y0, y1, 0, 0.05, material("bed soil", "#4a3628", rough=1.0))
    for k in range(int((x1 - x0) / 0.02)):
        x = x0 + 0.01 + k * 0.02
        m.box(x - 0.006, x + 0.006, y0 + 0.004, y1 - 0.004, 0.05, 0.14, material("flowers " + str(k % 3), ["#c84a5a", "#e8c84a", "#d87ab0"][k % 3], rough=0.9), 0.003)


def drive(m, x, y0, y1, w=0.07, colour="#8a8680"):
    m.box(x - w, x + w, y0, y1, 0, 0.025, material("drive " + colour, colour, rough=0.95))


def paved(m, x0, x1, y0, y1, colour="#9a958c"):
    """A paved yard."""
    m.box(x0, x1, y0, y1, 0, 0.02, material("yard paving " + colour, colour, rough=0.95))


def glass():
    return material("window glass", "#2c3a48", rough=0.12, grime=0.2)


def gable_glass():
    """Glass for small windows up in a gable, a little lighter and without grime, as they sit in the shade of the eaves.
    They go on the face of the gable's thin end wall (0.003 out), so the glass doesn't share a plane with it."""
    return material("gable glass", "#4a6078", rough=0.3, grime=0.0)


# ---- the kinds of building ------------------------------------------------------------------

def log_cabin(m):
    logs = material("logs", "#6e4a2c", rough=0.95)
    chink = material("chinking", "#c8bca0", rough=1.0)
    shingle = material("cedar shingles", "#5c4a3a", rough=0.9)
    stone = material("fieldstone", "#7c766c", rough=0.95)
    x0, x1, y0, y1 = -0.17, 0.17, -0.08, 0.16
    m.box(x0, x1, y0, y1, 0, 1.3, logs, 0.004)
    # The logs' courses, and their ends crossing at the corners.
    z = 0.12
    while z < 1.3:
        m.box(x0 - 0.002, x1 + 0.002, y0 - 0.002, y1 + 0.002, z, z + 0.025, chink)
        for x in (x0, x1):
            for y in (y0, y1):
                m.box(x - 0.012, x + 0.012, y - 0.012, y + 0.012, z - 0.1, z, logs, 0.003)
        z += 0.16
    gable(m, x0, x1, y0, y1, 1.3, 2.35, shingle, logs, along_x=True, over=0.035)
    chimney(m, x1 + 0.02, 0.04, 0, 2.6, stone, 0.03)
    windows(m, x0, x1, y0, y1, 0, 1, 2, 1, glass(), logs, skip_door=True)
    door(m, 0.0, y1, material("plank door", "#4a3220", rough=0.9), logs)
    path(m, 0.0, y1, 0.5, 0.04)
    # A woodpile by the side.
    for k in range(3):
        m.box(x0 - 0.09, x0 - 0.03, -0.02 + k * 0.035, 0.01 + k * 0.035, 0, 0.35, material("cordwood", "#8a6a48", rough=0.95), 0.004)
    # A split rail fence round the clearing, a vegetable patch and the outhouse at the back.
    fence_round(m, "rails", logs)
    garden(m, 0.12, 0.38, -0.4, -0.16)
    shed(m, -0.34, -0.36, w=0.05, d=0.05, colour="#5e4430")
    yard_tree(m, 0.32, 0.3, 0.9, seed=1)


def farmhouse(m):
    siding = material("white clapboard", "#e6e2d6", rough=0.7)
    trim = material("green trim", "#3e5a3a", rough=0.6)
    roof = material("asphalt shingles", "#5a3a34", rough=0.85)
    brick = material("chimney brick", "#8a4a36", rough=0.9)
    white = material("white trim", "#f4f0e6", rough=0.6)
    stone = material("fieldstone", "#7c766c", rough=0.95)
    lap = material("clapboard shadow", "#c8c4b8", rough=0.8)
    # The Ontario Gothic house: two storeys on a fieldstone foundation, a steep centre gable over the front door with a
    # pointed window, gingerbread bargeboards, a verandah across the front and a summer kitchen ell at the back.
    x0, x1, y0, y1 = -0.2, 0.2, -0.16, 0.1
    base = 0.25
    foundation(m, x0, x1, y0, y1, base, stone)
    m.box(x0, x1, y0, y1, 0, 2.5, siding, 0.003)
    lap_siding(m, x0, x1, y0, y1, base + 0.06, 2.45, lap)
    corner_boards(m, x0, x1, y0, y1, base, 2.5, white)
    gable(m, x0, x1, y0, y1, 2.5, 3.9, roof, siding, along_x=True, over=0.04, trim=trim, barge=True, gap=(-0.105, 0.105, 3.8))
    yc = (y0 + y1) / 2
    gable(m, -0.075, 0.075, 0.0, y1, 2.5, 3.8, roof, siding, along_x=False, over=0.03, trim=trim, barge=True, ends=(False, True))
    gothic_pane(m, 0.0, y1 + 0.003, 2.72, 3.12, 3.36, 0.045, gable_glass(), white, white)
    chimney(m, -0.13, yc, 2.5, 4.3, brick)
    windows(m, x0, x1, y0, y1, 0.1, 2, 3, 2, glass(), white, skip_door=True, trim=trim)
    door(m, 0.0, y1, material("farm door", "#5a3a26", rough=0.6), white, z0=base, transom=glass())
    verandah(m, x0, x1, y1, y1 + 0.09, base, 1.4, roof, material("porch floor", "#8a7a66", rough=0.8), white, trim, posts=5,
             step_at=0.0, brackets=True, skirt=material("lattice skirt", "#4e4a42", rough=0.9), pitch=0.2)
    # The summer kitchen: a storey and a half of board and batten behind the house, with its own chimney and back door.
    kx0, kx1, ky0, ky1 = 0.0, 0.15, -0.33, y0
    foundation(m, kx0, kx1, ky0, ky1, 0.15, stone)
    m.box(kx0, kx1, ky0, ky1, 0, 1.6, siding, 0.003)
    y = ky0 + 0.015
    while y < ky1 - 0.005:
        for x, d in ((kx0, -1), (kx1, 1)):
            on_wall(m, "x", x, d, y - 0.003, y + 0.003, 0, 0.004, 0.15, 1.6, lap)
        y += 0.024
    corner_boards(m, kx0, kx1, ky0, ky1, 0.15, 1.6, white)
    gable(m, kx0, kx1, ky0, ky1 + 0.04, 1.6, 2.55, roof, siding, along_x=False, over=0.03, trim=trim, ends=(True, False))
    chimney(m, (kx0 + kx1) / 2, ky0 + 0.03, 1.6, 2.95, brick, 0.028)
    for y in (ky0 + 0.06, ky1 - 0.06):
        pane(m, "x", kx0, -1, y, 0.5, 1.1, WINDOW_W, glass(), white, trim)
    pane(m, "x", kx1, 1, ky0 + 0.06, 0.5, 1.1, WINDOW_W, glass(), white, trim)
    m.box(kx1, kx1 + 0.009, ky1 - 0.07, ky1 - 0.02, 0.15, 1.1, material("kitchen door", "#3e5a3a", rough=0.6))
    m.box(kx1, kx1 + 0.04, ky1 - 0.075, ky1 - 0.015, 0, 0.15, stone, 0.002)
    pane(m, "y", ky0 - 0.003, -1, (kx0 + kx1) / 2, 1.72, 2.02, 0.04, gable_glass(), white, trim, sash=False)
    path(m, 0.0, y1 + 0.09 + 0.08, 0.5)
    picket(m, (-EDGE, EDGE), (EDGE, EDGE), material("white pickets", "#eeeae0", rough=0.7), gap=0.0)
    # Rail fences down the sides and back, a big kitchen garden, a shed and the old maple.
    fence_round(m, "rails", material("fence rails", "#8a7458", rough=0.95))
    garden(m, -0.4, -0.08, -0.42, -0.24, rows=5)
    shed(m, 0.32, -0.36, colour="#8a3a2a")
    yard_tree(m, 0.34, 0.24, 1.1, seed=2)


def clapboard_house(m, look=0):
    colours = ["#d8c26a", "#7e9ab0", "#9ab08a", "#c8826a"]
    siding = material("siding", colours[look % len(colours)], rough=0.7)
    trim = material("white trim", "#f0ece2", rough=0.6)
    roof = material("asphalt shingles dark", "#3e3a38", rough=0.85)
    stone = material("foundation stone", "#8a847a", rough=0.95)
    x0, x1, y0, y1 = -0.13, 0.13, -0.2, 0.14
    base = 0.18
    foundation(m, x0, x1, y0, y1, base, stone)
    m.box(x0, x1, y0, y1, 0, 2.2, siding, 0.003)
    lap_siding(m, x0, x1, y0, y1, base + 0.04, 2.15, material("siding shadow " + str(look), colours[look % len(colours)], rough=0.9, grime=0.7))
    corner_boards(m, x0, x1, y0, y1, base, 2.2, trim)
    # Gable end to the street, with eave returns at its corners.
    gable(m, x0, x1, y0, y1, 2.2, 3.7, roof, siding, along_x=False, over=0.035, trim=trim, returns=True)
    windows(m, x0, x1, y0, y1, 0, 2, 2, 3, glass(), trim, trim=trim, skip=((0, 1),))
    pane(m, "y", y1 + 0.003, 1, 0.0, 2.62, 3.05, 0.045, gable_glass(), trim, trim, sash=False)
    door(m, 0.07, y1, material("red door", "#8a2a22", rough=0.5), trim, z0=base)
    verandah(m, x0, x1, y1, y1 + 0.08, base, 1.3, roof, material("porch floor", "#8a7a66", rough=0.8), trim, trim, posts=3,
             step_at=0.07, skirt=material("lattice skirt", "#5a564e", rough=0.9), pitch=0.22)
    path(m, 0.07, y1 + 0.08 + 0.06, 0.5)
    chimney(m, x1 - 0.04, -0.1, 2.6, 3.9, material("chimney brick", "#8a4a36", rough=0.9))
    # A picket fence along the front with a gate at the walk, board fences behind, a backyard maple and a bed of flowers.
    fence_round(m, "board", material("board fence", "#8a7a64", rough=0.9), side_front=0.14)
    picket(m, (-EDGE, EDGE), (EDGE, EDGE), material("white pickets", "#eeeae0", rough=0.7), gap=0.07)
    flowers(m, -0.12, 0.0, 0.225, 0.255)
    yard_tree(m, -0.3, -0.32, 0.9, seed=3)
    shed(m, 0.32, -0.38, w=0.06, d=0.05, colour="#7a6a52")


def general_store(m):
    boards = material("weathered boards", "#a8906a", rough=0.9)
    front = material("false front", "#c8b48a", rough=0.85)
    trim = material("store trim", "#6a2a22", rough=0.6)
    roof = material("tin roof", "#7c8084", metal=0.4, rough=0.5)
    x0, x1, y0, y1 = -0.2, 0.2, -0.24, 0.16
    m.box(x0, x1, y0, y1, 0, 2.2, boards, 0.003)
    gable(m, x0, x1, y0, y1 - 0.02, 2.2, 3.2, roof, boards, along_x=False)
    # The square false front standing above the roof, with its cornice and sign board.
    m.box(x0 - 0.01, x1 + 0.01, y1, y1 + 0.02, 0, 3.6, front, 0.003)
    m.box(x0 - 0.02, x1 + 0.02, y1 - 0.005, y1 + 0.035, 3.5, 3.7, trim)
    m.box(x0 + 0.04, x1 - 0.04, y1 + 0.02, y1 + 0.028, 2.5, 3.2, material("sign board", "#2e2a26", rough=0.7))
    # Big shop windows each side of the door, and an awning over the boardwalk.
    for x in (-0.12, 0.12):
        m.box(x - 0.06, x + 0.06, y1 + 0.02, y1 + 0.028, 0.3, 1.5, glass())
        m.box(x - 0.065, x + 0.065, y1 + 0.018, y1 + 0.03, 1.5, 1.58, trim)
    door(m, 0.0, y1 + 0.02, trim, front, w=0.06)
    m.box(x0, x1, y1 + 0.02, y1 + 0.13, 0, 0.12, material("boardwalk", "#7a6a52", rough=0.9))
    slab(m, [(x0, y1 + 0.02, 2.1), (x1, y1 + 0.02, 2.1), (x1, y1 + 0.13, 1.85), (x0, y1 + 0.13, 1.85)], 0.04, material("awning", "#3e5a6a", rough=0.7))
    for x in (x0 + 0.01, x1 - 0.01):
        m.box(x - 0.005, x + 0.005, y1 + 0.12, y1 + 0.13, 0, 1.85, trim)
    windows(m, x0, x1, y0, y1, 0, 1, 0, 3, glass(), trim)
    # Barrels and crates on the walk.
    m.upright(x1 - 0.03, y1 + 0.08, 0.12, 0.42, 0.018, 0.018, material("barrel", "#6a4a2a", rough=0.8), segments=12)
    # The lot is a hard-packed yard: a shed for stock, a hitching rail out front.
    paved(m, -0.5, 0.5, -0.5, 0.5, colour="#8a7c66")
    shed(m, -0.34, -0.38, w=0.1, d=0.07, colour="#8a7458")
    m.box(0.26, 0.42, 0.42, 0.43, 0.45, 0.5, material("hitching rail", "#5a4430", rough=0.9))
    for x in (0.26, 0.42):
        m.box(x - 0.006, x + 0.006, 0.42, 0.432, 0, 0.5, material("hitching rail", "#5a4430", rough=0.9))


def church(m):
    """The white board church, 1836 to 1870: clapboard on a stone foundation with eave returns, buttresses between tall
    pointed windows, a tower at the front with a louvred belfry and a cornice under its spire, steps up to the door and a
    small vestry at the back."""
    wood = material("white church", "#eeeae2", rough=0.7)
    lap = material("church lap", "#d4d0c6", rough=0.8)
    trim = material("church trim", "#f6f2ea", rough=0.6)
    roof = material("church roof", "#4a4e56", rough=0.7)
    stone = material("church foundation", "#8a847a", rough=0.95)
    glass_c = material("stained glass", "#3a4a6a", rough=0.2)
    x0, x1, y0, y1 = -0.13, 0.13, -0.3, 0.12
    base, eave = 0.15, 2.6
    foundation(m, x0, x1, y0, y1, base, stone)
    m.box(x0, x1, y0, y1, 0, eave, wood, 0.003)
    lap_siding(m, x0, x1, y0, y1, base + 0.05, eave - 0.05, lap)
    corner_boards(m, x0, x1, y0, y1, base, eave, trim, w=0.012)
    gable(m, x0, x1, y0, y1, eave, 4.1, roof, wood, along_x=False, over=0.03, trim=trim, returns=True)
    # Tall pointed windows down the sides with board buttresses between them.
    for k in range(4):
        y = y0 + 0.06 + k * 0.095
        for x, d in ((x0, -1), (x1, 1)):
            pointed_pane(m, "x", x, d, y, 0.6, 1.95, 2.2, 0.044, glass_c, trim, trim)
    for k in range(5):
        y = y0 + 0.012 + k * 0.095
        for x, d in ((x0, -1), (x1, 1)):
            buttress(m, "x", x, d, y, 0.014, 0.02, 2.2, trim)
    for x in (-0.095, 0.095):
        pointed_pane(m, "y", y1, 1, x, 0.6, 1.95, 2.2, 0.04, glass_c, trim, trim)
    # The tower: clapboard to a cornice, the belfry with louvred openings, a second cornice and the spire.
    tx0, tx1, ty0, ty1 = -0.06, 0.06, y1 - 0.02, y1 + 0.1
    tc = (ty0 + ty1) / 2
    tt = 4.9
    m.box(tx0, tx1, ty0, ty1, 0, tt, wood, 0.003)
    foundation(m, tx0, tx1, ty0, ty1, base, stone)
    lap_siding(m, tx0, tx1, ty0, ty1, base + 0.05, tt - 0.1, lap)
    corner_boards(m, tx0, tx1, ty0, ty1, base, tt, trim, w=0.012)
    m.box(tx0 - 0.012, tx1 + 0.012, ty0 - 0.012, ty1 + 0.012, tt, tt + 0.12, trim)
    bx0, bx1, by0, by1 = tx0 + 0.006, tx1 - 0.006, ty0 + 0.006, ty1 - 0.006
    bt = tt + 0.92
    m.box(bx0, bx1, by0, by1, tt + 0.12, bt, wood, 0.002)
    corner_boards(m, bx0, bx1, by0, by1, tt + 0.12, bt, trim, w=0.012)
    for face, at, d, c in (("y", by1, 1, 0.0), ("y", by0, -1, 0.0), ("x", bx1, 1, tc), ("x", bx0, -1, tc)):
        louvres(m, face, at, d, c, tt + 0.25, tt + 0.6, 0.05, trim, trim, point=tt + 0.8)
    m.box(bx0 - 0.014, bx1 + 0.014, by0 - 0.014, by1 + 0.014, bt, bt + 0.1, trim)
    m.box(bx0 - 0.02, bx1 + 0.02, by0 - 0.02, by1 + 0.02, bt + 0.1, bt + 0.18, trim)
    for x in (bx0 - 0.01, bx1 + 0.01):
        for y in (by0 - 0.01, by1 + 0.01):
            spire(m, x, y, bt + 0.18, bt + 0.63, 0.008, trim, sides=4)
    spire(m, 0.0, tc, bt + 0.18, 10.15, 0.048, roof)
    finial(m, 0.0, tc, 10.1, 0.3, material("gilt finial", "#c8a84a", metal=0.7, rough=0.35))
    pointed_pane(m, "y", ty1, 1, 0.0, 2.1, 2.9, 3.15, 0.04, glass_c, trim, trim)
    pointed_pane(m, "y", ty1, 1, 0.0, 3.6, 4.1, 4.3, 0.03, glass_c, trim, trim)
    door(m, 0.0, ty1, material("church doors", "#5a3a26", rough=0.6), trim, w=0.055, h=1.0, z0=base)
    m.box(-0.002, 0.002, ty1 + 0.009, ty1 + 0.011, base, base + 1.0, material("door split", "#3a2618", rough=0.7))
    m.wedge([(-0.04, ty1, 1.22), (0.04, ty1, 1.22), (0.0, ty1, 1.42)], [(-0.04, ty1 + 0.012, 1.22), (0.04, ty1 + 0.012, 1.22), (0.0, ty1 + 0.012, 1.42)], trim)
    steps(m, 0.0, ty1, 0.1, base, stone, n=3)
    # The vestry behind, a storey under its own gable, with a door on the side.
    vx0, vx1, vy0, vy1 = -0.07, 0.07, -0.4, y0
    foundation(m, vx0, vx1, vy0, vy1, base, stone)
    m.box(vx0, vx1, vy0, vy1, 0, 1.45, wood, 0.003)
    lap_siding(m, vx0, vx1, vy0, vy1, base + 0.05, 1.4, lap)
    corner_boards(m, vx0, vx1, vy0, vy1, base, 1.45, trim, w=0.01)
    gable(m, vx0, vx1, vy0, vy1 + 0.02, 1.45, 2.25, roof, wood, along_x=False, over=0.025, thick=0.06, trim=trim, ends=(True, False))
    pointed_pane(m, "y", vy0, -1, 0.0, 0.5, 1.1, 1.25, 0.04, glass_c, trim, trim)
    m.box(vx1, vx1 + 0.009, -0.37, -0.33, base, base + 0.9, material("vestry door", "#5a3a26", rough=0.6))
    m.box(vx1, vx1 + 0.03, -0.375, -0.325, 0, base, stone, 0.002)
    chimney(m, 0.0, -0.37, 1.8, 2.6, material("chimney brick", "#8a4a36", rough=0.9), 0.022)
    path(m, 0.0, ty1 + 0.08, 0.5, 0.06)
    # A white fence along the front, and the churchyard behind with its stones and an old elm.
    picket(m, (-EDGE, EDGE), (EDGE, EDGE), material("white pickets", "#eeeae0", rough=0.7), gap=0.0)
    grave = material("grave stone", "#9a968e", rough=0.9)
    for gx in (-0.36, -0.28, 0.24, 0.32, 0.4):
        for gy in (-0.4, -0.3, -0.2):
            m.box(gx - 0.012, gx + 0.012, gy - 0.004, gy + 0.004, 0, 0.32, grave, 0.002)
    yard_tree(m, -0.34, 0.2, 1.2, seed=4, kind="elm")


def stone_church(m):
    """The Gothic Revival parish church, 1870 to 1960, as in every Ontario and Quebec town: grey limestone with dressed
    trim, a steep tin roof, buttresses between lancet windows, a big traceried window over a gabled porch, a corner tower
    with a louvred belfry, pinnacles and a tin spire, and a chancel with a polygonal apse at the back."""
    rubble = material("limestone", "#a39c8e", rough=0.95)
    dressed = material("dressed stone", "#cdc5b2", rough=0.85)
    tin = material("tin roof", "#a8aeb4", metal=0.55, rough=0.4)
    glass_c = material("stained glass", "#3a4a6a", rough=0.2)
    frame = material("tracery", "#d8d0bc", rough=0.8)
    x0, x1, y0, y1 = -0.13, 0.09, -0.28, 0.14
    eave = 2.3
    m.box(x0, x1, y0, y1, 0, eave, rubble, 0.003)
    foundation(m, x0, x1, y0, y1, 0.2, dressed, out=0.006)
    gable(m, x0, x1, y0, y1, eave, 4.3, tin, rubble, along_x=False, over=0.02, trim=dressed, fascia=0.06)
    # Lancets between stepped buttresses down both sides.
    for k in range(4):
        y = y0 + 0.055 + k * 0.1
        for x, d in ((x0, -1), (x1, 1)):
            pointed_pane(m, "x", x, d, y, 0.55, 1.75, 2.05, 0.038, glass_c, frame, dressed)
    for k in range(5):
        y = y0 + 0.006 + k * 0.1
        for x, d in ((x0, -1), (x1, 1)):
            buttress(m, "x", x, d, min(max(y, y0 + 0.008), y1 - 0.008), 0.016, 0.03, 2.1, dressed)
    # The front: a big window with tracery in the gable over a gabled porch, and a stone pinnacle on the apex.
    xc = (x0 + x1) / 2
    pointed_pane(m, "y", y1, 1, xc, 1.55, 2.75, 3.4, 0.09, glass_c, frame, dressed, bars=2)
    m.box(xc - 0.004, xc + 0.004, y1 - 0.004, y1 + 0.004, 4.3, 4.5, dressed)
    spire(m, xc, y1, 4.5, 4.85, 0.008, dressed, sides=4)
    px0, px1, py1 = xc - 0.05, xc + 0.05, y1 + 0.06
    m.box(px0, px1, y1 - 0.01, py1, 0, 1.15, rubble, 0.003)
    gable(m, px0, px1, y1 - 0.01, py1, 1.15, 1.6, tin, rubble, along_x=False, over=0.01, thick=0.05, trim=dressed, fascia=0.05, ends=(False, True))
    on_face(m, "y", py1, 1, [(xc - 0.032, 0.0), (xc + 0.032, 0.0), (xc + 0.032, 0.75), (xc, 1.0), (xc - 0.032, 0.75)], 0, 0.006, dressed)
    on_face(m, "y", py1, 1, [(xc - 0.024, 0.02), (xc + 0.024, 0.02), (xc + 0.024, 0.72), (xc, 0.92), (xc - 0.024, 0.72)], 0, 0.009,
            material("church doors", "#5a3a26", rough=0.6))
    steps(m, xc, py1, 0.09, 0.12, dressed, n=2)
    for x, d in ((px0, -1), (px1, 1)):
        pointed_pane(m, "x", x, d, (y1 + py1) / 2, 0.45, 0.8, 0.92, 0.022, glass_c, frame, dressed)
    # The corner tower: buttressed at its corners, a louvred belfry, a parapet with pinnacles and a tin spire.
    tx0, tx1, ty0, ty1 = 0.08, 0.22, 0.04, 0.18
    tcx, tcy = (tx0 + tx1) / 2, (ty0 + ty1) / 2
    tt = 5.9
    m.box(tx0, tx1, ty0, ty1, 0, tt, rubble, 0.003)
    foundation(m, tx0, tx1, ty0, ty1, 0.2, dressed, out=0.006)
    for face, at, d, a0, a1 in (("y", ty1, 1, tx0, tx1), ("x", tx1, 1, ty0, ty1)):
        for a in (a0 + 0.01, a1 - 0.01):
            buttress(m, face, at, d, a, 0.018, 0.028, 4.5, dressed)
    for z in (2.3, 3.55, 4.75):
        m.box(tx0 - 0.006, tx1 + 0.006, ty0 - 0.006, ty1 + 0.006, z, z + 0.06, dressed)
    pointed_pane(m, "y", ty1, 1, tcx, 1.0, 1.9, 2.1, 0.03, glass_c, frame, dressed)
    pointed_pane(m, "x", tx1, 1, tcy, 1.0, 1.9, 2.1, 0.03, glass_c, frame, dressed)
    pointed_pane(m, "y", ty1, 1, tcx, 3.85, 4.4, 4.6, 0.024, glass_c, frame, dressed)
    pointed_pane(m, "x", tx1, 1, tcy, 3.85, 4.4, 4.6, 0.024, glass_c, frame, dressed)
    for face, at, d, c in (("y", ty1, 1, tcx), ("y", ty0, -1, tcx), ("x", tx1, 1, tcy), ("x", tx0, -1, tcy)):
        louvres(m, face, at, d, c, tt - 0.95, tt - 0.45, 0.05, dressed, frame, point=tt - 0.2)
    m.box(tx0 - 0.01, tx1 + 0.01, ty0 - 0.01, ty1 + 0.01, tt, tt + 0.12, dressed)
    for x in (tx0 - 0.002, tx1 + 0.002):
        for y in (ty0 - 0.002, ty1 + 0.002):
            m.box(x - 0.01, x + 0.01, y - 0.01, y + 0.01, tt + 0.12, tt + 0.3, dressed)
            spire(m, x, y, tt + 0.3, tt + 0.85, 0.01, dressed, sides=4)
    spire(m, tcx, tcy, tt + 0.12, 10.15, 0.06, tin)
    finial(m, tcx, tcy, 10.1, 0.3, material("gilt finial", "#c8a84a", metal=0.7, rough=0.35))
    # The chancel and its apse: lower and narrower, a hip of tin over the half octagon.
    cx0, cx1, cy0 = -0.09, 0.05, y0 - 0.05
    m.box(cx0, cx1, cy0, y0 + 0.01, 0, 1.9, rubble, 0.003)
    gable(m, cx0, cx1, cy0, y0 + 0.01, 1.9, 3.1, tin, rubble, along_x=False, over=0.015, thick=0.06, trim=dressed, fascia=0.05, ends=(False, False))
    r = (cx1 - cx0) / 2
    apse = [(xc + r * math.cos(math.radians(a)), cy0 + r * math.sin(math.radians(a))) for a in (0, -45, -90, -135, -180)]
    m.wedge([(x, y, 0) for x, y in apse], [(x, y, 1.9) for x, y in apse], rubble)
    eaves = [(xc + (r + 0.015) * math.cos(math.radians(a)), cy0 + (r + 0.015) * math.sin(math.radians(a))) for a in (0, -45, -90, -135, -180)]
    for p, q in zip(eaves, eaves[1:]):
        slab(m, [(p[0], p[1], 1.9), (q[0], q[1], 1.9), (xc, cy0, 3.1)], 0.06, tin)
    for a in (-45, -90, -135):
        face_pane(m, (xc + r * math.cos(math.radians(a - 22.5)), cy0 + r * math.sin(math.radians(a - 22.5))),
                  (xc + r * math.cos(math.radians(a + 22.5)), cy0 + r * math.sin(math.radians(a + 22.5))), 0.6, 1.5, glass_c, frame, 0.004, 0.3, 0.7)
    # A low stone wall with iron railings along the front, a few old stones and maples.
    iron = material("iron railings", "#2a2a2a", metal=0.5, rough=0.5)
    for a, b in ((-EDGE, -0.05), (0.05, EDGE)):
        m.box(a, b, EDGE - 0.012, EDGE + 0.012, 0, 0.18, dressed, 0.002)
        picket(m, (a, EDGE), (b, EDGE), iron, height=0.42)
    path(m, xc, py1 + 0.04, 0.5, 0.07)
    grave = material("grave stone", "#9a968e", rough=0.9)
    for gx in (-0.4, -0.32, 0.26, 0.34, 0.42):
        for gy in (-0.42, -0.32):
            m.box(gx - 0.014, gx + 0.014, gy - 0.005, gy + 0.005, 0, 0.36, grave, 0.002)
    yard_tree(m, -0.34, 0.24, 1.15, seed=15)
    yard_tree(m, 0.36, -0.12, 1.0, seed=16)


def modern_church(m):
    """A 1960s church, 1960 to 2100: a steep A-frame with a glass front, low brick walls, a
    flat-roofed brick hall beside it, a free-standing bell frame by the road and a parking lot."""
    brick = material("modern church brick", "#a87a5a", rough=0.9)
    roof = material("a-frame roof", "#4a4440", rough=0.85)
    wood = material("a-frame wood", "#8a5e3a", rough=0.7)
    concrete = material("church concrete", "#b8b2a6", rough=0.9)
    metal = material("window frames", "#3a3a3c", metal=0.5, rough=0.4)
    x0, x1, y0, y1 = -0.15, 0.13, -0.26, 0.12
    xc = (x0 + x1) / 2
    eave, ridge = 0.4, 4.0
    m.box(x0, x1, y0, y1, 0, eave, brick, 0.003)
    gable(m, x0, x1, y0, y1, eave, ridge, roof, wood, along_x=False, over=0.03, thick=0.07, trim=wood, fascia=0.06, ends=(False, True))
    # The glass front: glass just proud of the thin gable end, upright mullions and two transoms.
    half = (x1 - x0) / 2

    def h(x):
        return eave + (ridge - eave) * (1 - abs(x - xc) / half)

    gy = y1 + 0.004
    m.box(x0, x1, y1 - 0.004, y1 + 0.003, 0, eave, wood)
    pts = [(x0 + 0.01, 0.02), (x1 - 0.01, 0.02), (x1 - 0.01, h(x1 - 0.01) - 0.06), (xc, ridge - 0.12), (x0 + 0.01, h(x0 + 0.01) - 0.06)]
    on_face(m, "y", gy, 1, pts, 0, 0.004, gable_glass())
    for k in range(1, 8):
        x = x0 + k * (x1 - x0) / 8
        on_wall(m, "y", gy, 1, x - 0.003, x + 0.003, 0.004, 0.009, 0.02, h(x) - 0.06, metal)
    # Each transom stops where it meets the roof slopes.
    on_wall(m, "y", gy, 1, xc - 0.115, xc + 0.115, 0.004, 0.009, 0.85, 0.9, metal)
    on_wall(m, "y", gy, 1, xc - 0.06, xc + 0.06, 0.004, 0.009, 2.2, 2.25, metal)
    on_wall(m, "y", gy, 1, xc - 0.04, xc + 0.04, 0.009, 0.013, 0.02, 0.8, material("church doors", "#6a4428", rough=0.6))
    m.box(x0 - 0.01, x1 + 0.01, y1, y1 + 0.08, 0, 0.04, concrete)
    m.box(x0, x1, y0 - 0.004, y0 + 0.004, 0, eave, brick)
    # Long dormer lights low in the roof down each side.
    for x, d in ((x0, -1), (x1, 1)):
        for k in range(3):
            y = y0 + 0.07 + k * 0.12
            on_wall(m, "x", x, d, y - 0.03, y + 0.03, 0.002, 0.006, 0.12, 0.34, gable_glass())
    # The hall: one storey of brick under a flat roof with a deep fascia, joined to the church by a glass link.
    hx0, hx1, hy0, hy1 = x1 + 0.04, 0.42, -0.3, 0.02
    m.box(x1, hx0, -0.1, 0.0, 0, 1.0, gable_glass(), 0.002)
    m.box(x1 - 0.005, hx0 + 0.005, -0.11, 0.01, 1.0, 1.08, concrete)
    m.box(hx0, hx1, hy0, hy1, 0, 1.2, brick, 0.003)
    m.box(hx0 - 0.02, hx1 + 0.02, hy0 - 0.02, hy1 + 0.02, 1.2, 1.34, concrete)
    for face, at, d, a0, a1 in (("y", hy1, 1, hx0, hx1), ("y", hy0, -1, hx0, hx1), ("x", hx1, 1, hy0, hy1)):
        on_wall(m, face, at, d, a0 + 0.03, a1 - 0.03, -0.002, 0.004, 0.55, 0.95, glass())
        n = max(1, round((a1 - a0 - 0.06) / 0.04))
        for k in range(n + 1):
            a = a0 + 0.03 + k * (a1 - a0 - 0.06) / n
            on_wall(m, face, at, d, a - 0.003, a + 0.003, 0, 0.007, 0.55, 0.95, metal)
    on_wall(m, "y", hy1, 1, hx0 + 0.02, hx0 + 0.07, 0, 0.008, 0, 0.9, material("hall door", "#2a4a5a", rough=0.5))
    # The bell frame: two concrete piers with the bell hung between them.
    bx, by = 0.3, 0.22
    for x in (bx - 0.04, bx + 0.04):
        m.box(x - 0.01, x + 0.01, by - 0.025, by + 0.025, 0, 3.2, concrete, 0.003)
    m.box(bx - 0.05, bx + 0.05, by - 0.028, by + 0.028, 3.2, 3.32, concrete, 0.003)
    m.box(bx - 0.05, bx + 0.05, by - 0.02, by + 0.02, 1.2, 1.28, concrete)
    bronze = material("bell bronze", "#8a6a3a", metal=0.75, rough=0.35)
    m.lathe(bx, by, 2.4, [(0.028, 0.0), (0.024, 0.08), (0.018, 0.3), (0.016, 0.55), (0.0, 0.6)], bronze, segments=16)
    m.box(bx - 0.002, bx + 0.002, by - 0.002, by + 0.002, 3.0, 3.2, bronze)
    m.box(bx - 0.06, bx + 0.06, by - 0.04, by + 0.04, 0, 0.04, concrete)
    # A sign board on the lawn, the walk, shrubs and the parking lot behind.
    m.box(-0.38, -0.22, 0.39, 0.41, 0.0, 0.55, brick, 0.002)
    m.box(-0.365, -0.235, 0.41, 0.414, 0.15, 0.48, material("sign white", "#ece8de", rough=0.7))
    path(m, xc, y1 + 0.08, 0.5, 0.07)
    parking(m, -0.47, -0.21, -0.47, -0.02, 0.1, ((-0.42, "#8a8a90"), (-0.22, "#3a5a7a"), (-0.12, "#c8c0a8")))
    shrub = scenery.leafy("church shrubs", "#3e6a2a", "#6a9a3e")
    for k, x in enumerate((x0 + 0.03, x1 - 0.03, 0.22, 0.38)):
        scenery.blob(m, x, 0.15 if k < 2 else 0.06, 0.15, 0.03, shrub, 50 + k, squash=0.8)
    yard_tree(m, 0.34, -0.4, 0.9, seed=17)


def brick_row(m):
    brick = material("red brick", "#9a4a36", rough=0.9)
    trim = material("stone trim", "#c8bca8", rough=0.8)
    wood = material("row wood trim", "#eae2d0", rough=0.6)
    roof = material("row roof", "#3a3634", rough=0.85)
    stone = material("row foundation", "#7a7268", rough=0.95)
    iron = material("iron railings", "#2a2a2a", metal=0.5, rough=0.5)
    doors = ["#2a3e5a", "#5a2a22", "#2e4a2e"]
    # Three Toronto bay-and-gable houses: a two-storey bay under a steep gable with bargeboard, and a door up a stoop.
    x0, x1, y0, y1 = -0.39, 0.39, -0.2, 0.12
    base = 0.4
    foundation(m, x0, x1, y0, y1, base, stone)
    m.box(x0, x1, y0, y1, 0, 2.9, brick, 0.003)
    m.box(x0 - 0.008, x1 + 0.008, y0 - 0.008, y1 + 0.008, 2.9, 3.05, trim)
    hip(m, x0, x1, y0, y1, 3.05, 3.9, roof, over=0.01)
    yc = (y0 + y1) / 2
    for k in range(3):
        cx = x0 + (k + 0.5) * (x1 - x0) / 3
        dx = cx - 0.06
        door(m, dx, y1, material("row door " + str(k), doors[k], rough=0.5), wood, z0=base, transom=glass())
        m.box(dx - 0.04, dx + 0.04, y1, y1 + 0.012, base + 1.23, base + 1.31, trim)
        steps(m, dx, y1, 0.065, base, trim, n=4, run=0.018, landing=0.03)
        for sx in (dx - 0.034, dx + 0.034):
            m.rod((sx, y1 + 0.03, base + 0.3), (sx, y1 + 0.084, 0.3), 0.003, iron, segments=5)
            m.box(sx - 0.003, sx + 0.003, y1 + 0.081, y1 + 0.087, 0, 0.32, iron)
        pane(m, "y", y1, 1, dx, 1.65, 2.4, 0.05, glass(), wood, trim)
        # The canted bay: a front and two angled sides, brick up to a cornice under the gable.
        bx0, bx1, bd = cx - 0.005, cx + 0.115, 0.05
        bay = [(bx1, y1), (bx0, y1), (bx0 + 0.025, y1 + bd), (bx1 - 0.025, y1 + bd)]
        m.wedge([(x, y, 0) for x, y in bay], [(x, y, 2.75) for x, y in bay], brick)
        m.wedge([(x, y, 0) for x, y in [(bx1 + 0.004, y1), (bx0 - 0.004, y1), (bx0 + 0.023, y1 + bd + 0.006), (bx1 - 0.023, y1 + bd + 0.006)]],
                [(x, y, base) for x, y in [(bx1 + 0.004, y1), (bx0 - 0.004, y1), (bx0 + 0.023, y1 + bd + 0.006), (bx1 - 0.023, y1 + bd + 0.006)]], stone)
        cap = [(bx1 + 0.008, y1), (bx0 - 0.008, y1), (bx0 + 0.022, y1 + bd + 0.01), (bx1 - 0.022, y1 + bd + 0.01)]
        m.wedge([(x, y, 2.75) for x, y in cap], [(x, y, 2.88) for x, y in cap], trim)
        for z0, z1 in ((base + 0.25, base + 1.05), (1.65, 2.4)):
            face_pane(m, (bx1 - 0.025, y1 + bd), (bx0 + 0.025, y1 + bd), z0, z1, glass(), wood, 0.005, 0.12, 0.88)
            face_pane(m, (bx0 + 0.025, y1 + bd), (bx0, y1), z0, z1, glass(), wood, 0.005, 0.2, 0.8)
            face_pane(m, (bx1, y1), (bx1 - 0.025, y1 + bd), z0, z1, glass(), wood, 0.005, 0.2, 0.8)
            m.box(bx0 + 0.02, bx1 - 0.02, y1 + bd, y1 + bd + 0.014, z0 - 0.09, z0 - 0.05, trim)
        # The gable over the bay, with its window and bargeboard.
        gable(m, bx0 - 0.005, bx1 + 0.005, yc + 0.02, y1 + 0.012, 3.05, 3.85, roof, brick, along_x=False, over=0.02, thick=0.06, trim=wood,
              barge=True, ends=(False, True))
        pane(m, "y", y1 + 0.015, 1, cx + 0.055, 3.15, 3.45, 0.035, gable_glass(), wood, trim, sash=False)
        chimney(m, cx + (x1 - x0) / 6 - 0.01, -0.04, 3.3, 4.3, brick)
        path(m, dx, y1 + 0.084, 0.5, 0.04)
    windows(m, x0, x1, y0, y1, 0.1, 2, 0, 2, glass(), wood, trim=trim)
    for k in range(3):
        pane(m, "y", y0, -1, x0 + (k + 0.5) * (x1 - x0) / 3, 1.65, 2.3, 0.05, glass(), wood, trim)
    # Iron railings in front, three back yards divided by board fences, each with its shed.
    picket(m, (-EDGE, 0.3), (EDGE, 0.3), iron, height=0.32)
    fence = material("board fence", "#8a7a64", rough=0.9)
    fence_round(m, "board", fence, side_front=-0.2)
    for x in (-0.13, 0.13):
        board(m, (x, -EDGE), (x, -0.2), fence)
    for k in range(3):
        shed(m, -0.39 + (k + 0.5) * 0.26, -0.4, w=0.04, d=0.04, colour="#7a5a46")


def brick_block(m, look=0):
    """The Main Street block, 1880 to 1975: three shops behind recessed doors with sign boards over them, a door at the
    end to the flats upstairs, segmental arched windows with stone heads and a pressed metal cornice on brackets with a
    name and date stone over it."""
    brick = material("block brick", ["#a0583e", "#c8a878"][look % 2], rough=0.9)
    stone = material("block trim", "#d6ccb8", rough=0.8)
    metal = material("pressed metal cornice", "#d8d0bc", metal=0.2, rough=0.6)
    frieze = material("cornice frieze", "#b8ae98", metal=0.2, rough=0.6)
    sash = material("block sashes", "#ece6d6", rough=0.6)
    dark = material("sign letters", "#2a2622", rough=0.7)
    paints = ["#3e4a3a", "#5a2a26", "#2a3a4e"]
    x0, x1, y0, y1 = -0.4, 0.4, -0.32, 0.26
    top, shop, rec = 3 * STOREY + 0.3, 1.5, 0.03
    m.box(x0, x1, y0, y1 - rec, 0, shop, brick, 0.003)
    m.box(x0, x1, y0, y1, shop, top, brick, 0.003)
    flat_top(m, x0, x1, y0, y1, top, stone, parapet=0.45)
    bracket_cornice(m, x0, x1, y0, y1, top + 0.4, metal, frieze)
    # A stone band over the shops round the sides and back, and a sill course under the upper windows.
    for face, at, d, a0, a1 in (("x", x1, 1, y0, y1), ("x", x0, -1, y0, y1), ("y", y0, -1, x0, x1)):
        on_wall(m, face, at, d, a0, a1, 0, 0.006, shop - 0.04, shop + 0.06, stone)
    m.box(x0 - 0.004, x1 + 0.004, y1, y1 + 0.009, 1.84, 1.9, stone)
    # The name and date stone in a raised parapet with a low pediment over the middle of the front.
    m.box(-0.11, 0.11, y1 - 0.012, y1, top + 0.45, top + 0.78, brick)
    m.wedge([(-0.12, y1 - 0.016, top + 0.78), (0.12, y1 - 0.016, top + 0.78), (0.0, y1 - 0.016, top + 0.92)],
            [(-0.12, y1 + 0.006, top + 0.78), (0.12, y1 + 0.006, top + 0.78), (0.0, y1 + 0.006, top + 0.92)], stone)
    m.box(-0.075, 0.075, y1, y1 + 0.008, top + 0.5, top + 0.74, stone)
    sign_text(m, "1898", "y", y1, 1, 0.0, top + 0.62, 0.02, dark, o=0.008)
    # The door to the flats at the end of the front, with its transom and a lamp over it.
    bay = -0.33
    m.box(x0, bay, y1 - rec, y1, 0, shop, brick, 0.002)
    door(m, (x0 + bay) / 2, y1, material("flats door", "#3a2a22", rough=0.6), stone, w=0.04, h=0.95, z0=0.03, transom=glass())
    m.box((x0 + bay) / 2 - 0.006, (x0 + bay) / 2 + 0.006, y1, y1 + 0.02, 1.32, 1.4, material("door lamp", "#f0e0a0", rough=0.3))
    # Three shops between brick piers: a panelled bulkhead, display windows each side of a door set back in a recess with
    # glass returns, a transom light over it all and a sign board above.
    piers = [bay + k * (x1 - bay) / 3 for k in range(4)]
    for px in piers:
        m.box(max(x0, px - 0.008), min(x1, px + 0.008), y1 - rec, y1 + 0.004, 0, shop, brick, 0.002)
        m.box(max(x0, px - 0.01), min(x1, px + 0.01), y1 - rec, y1 + 0.007, 0, 0.12, stone)
    tile = material("door tiles", "#b8b0a0", rough=0.7)
    for k in range(3):
        a, b = piers[k] + 0.008, piers[k + 1] - 0.008
        c, dw = (a + b) / 2, 0.024
        paint = material("shop paint " + str(k), paints[k], rough=0.6)
        for s0, s1 in ((a, c - dw), (c + dw, b)):
            m.box(s0, s1, y1 - 0.014, y1 - 0.002, 0, 0.28, paint)
            m.box(s0 + 0.008, s1 - 0.008, y1 - 0.002, y1 + 0.001, 0.06, 0.22, paint)
            m.box(s0, s1, y1 - 0.011, y1 - 0.006, 0.28, 1.2, glass())
            for e in (s0, s1):
                m.box(e - 0.003, e + 0.003, y1 - 0.013, y1 - 0.002, 0.28, 1.2, paint)
            m.box(s0, s1, y1 - 0.015, y1 - 0.002, 0.26, 0.3, paint)
        for e in (c - dw, c + dw):
            m.box(e - 0.002, e + 0.002, y1 - rec, y1 - 0.011, 0.28, 1.2, glass())
            m.box(e - 0.003, e + 0.003, y1 - rec, y1 - 0.002, 0, 0.28, paint)
        m.box(c - dw, c + dw, y1 - rec, y1, 0, 0.035, tile)
        door(m, c, y1 - rec, material("shop door " + str(k), "#2a2420", rough=0.6), paint, w=0.034, h=0.9, z0=0.035, transom=None)
        m.box(c - 0.011, c + 0.011, y1 - rec + 0.009, y1 - rec + 0.011, 0.45, 0.85, glass())
        m.box(a, b, y1 - 0.016, y1 - 0.002, 1.18, 1.24, paint)
        m.box(a, b, y1 - 0.011, y1 - 0.006, 1.24, 1.42, glass())
        for j in range(1, 5):
            x = a + j * (b - a) / 5
            m.box(x - 0.002, x + 0.002, y1 - 0.013, y1 - 0.002, 1.24, 1.42, paint)
        m.box(a, b, y1 - 0.016, y1 - 0.002, 1.42, shop, paint)
        m.box(a - 0.004, b + 0.004, y1, y1 + 0.008, 1.54, 1.8, paint)
        m.box(a + 0.01, b - 0.01, y1 + 0.008, y1 + 0.011, 1.58, 1.76, material("sign board", "#e8dcc0", rough=0.7))
        sign_text(m, ["BAKERY", "HARDWARE", "DRUGS"][k], "y", y1, 1, c, 1.67, 0.018, paint, o=0.011)
        if k != 1:
            # A striped canvas awning rolled out over the window.
            stripes = [material("awning " + str(k), ["#7a2a2a", "#2a4a6a", "#3e5a2a"][k], rough=0.8), material("awning white", "#e8e2d4", rough=0.8)]
            n = 8
            for j in range(n):
                xa, xb = a + j * (b - a) / n, a + (j + 1) * (b - a) / n
                slab(m, [(xa, y1, 1.48), (xb, y1, 1.48), (xb, y1 + 0.08, 1.2), (xa, y1 + 0.08, 1.2)], 0.035, stripes[j % 2])
                m.box(xa, xb, y1 + 0.076, y1 + 0.082, 1.08, 1.2, stripes[j % 2])
    # Arched windows on the two floors of flats, all round.
    for z0 in (1.98, 3.2):
        for k in range(6):
            x = x0 + (k + 0.5) * (x1 - x0) / 6
            arched_pane(m, "y", y1, 1, x, z0, z0 + 0.55, 0.055, glass(), sash, stone)
            arched_pane(m, "y", y0, -1, x, z0, z0 + 0.55, 0.055, glass(), sash, stone)
        for k in range(4):
            arched_pane(m, "x", x1, 1, y0 + (k + 0.5) * (y1 - y0) / 4, z0, z0 + 0.55, 0.055, glass(), sash, stone)
    # The end wall over the flats door is blank but for a faded painted sign.
    on_wall(m, "x", x0, -1, -0.2, 0.16, 0, 0.002, 2.0, 3.6, material("painted sign", "#c8b88a", rough=0.95, grime=0.8))
    painted = material("painted letters", "#6a2a22", rough=0.95, grime=0.8)
    sign_text(m, "DRY GOODS", "x", x0, -1, -0.02, 3.05, 0.055, painted, o=0.002)
    sign_text(m, "FLOUR & FEED", "x", x0, -1, -0.02, 2.45, 0.036, painted, o=0.002)
    # Back doors to the shops off the lane, chimneys and a roof hatch.
    for k in range(3):
        c = (piers[k] + piers[k + 1]) / 2
        on_wall(m, "y", y0, -1, c - 0.03, c + 0.03, 0, 0.006, 0, 1.08, stone)
        on_wall(m, "y", y0, -1, c - 0.022, c + 0.022, 0, 0.009, 0.0, 1.0, material("back door", "#4a3a2e", rough=0.8))
        pane(m, "y", y0, -1, c + 0.08, 0.5, 1.0, 0.045, glass(), sash, stone)
    for x in (-0.3, 0.0, 0.3):
        chimney(m, x, y0 + 0.06, top, top + 0.75, brick, 0.03)
    m.box(0.12, 0.2, -0.1, -0.02, top, top + 0.3, material("roof hatch", "#6a6660", rough=0.8), 0.003)
    m.box(x0, x1, y1 - rec, 0.5, 0, 0.03, material("sidewalk", "#aaa498", rough=0.9))
    # A paved back lane behind the shops.
    paved(m, -0.5, 0.5, -0.5, -0.32)
    m.box(-0.45, -0.38, -0.47, -0.4, 0, 0.35, material("bins", "#3e4a3e", rough=0.8), 0.004)
    m.box(0.3, 0.42, -0.47, -0.38, 0, 0.12, material("pallets", "#8a7458", rough=0.9), 0.003)


def bungalow(m, look=0):
    """A Craftsman bungalow, 1915 to 1942: a low front gable with exposed rafter tails and knee braces, and a deep porch
    under its own gable on tapered posts set on brick piers."""
    colours = ["#b8a47a", "#8a9a7a", "#a88a6a"]
    shingle = material("bungalow shingles " + str(look), colours[look % len(colours)], rough=0.9)
    trim = material("craftsman trim", "#ece4cc", rough=0.6)
    roof = material("bungalow roof", "#4e4844", rough=0.85)
    brick = material("craftsman brick", "#8a4a36", rough=0.9)
    rafter = material("rafter tails", "#5a3e2a", rough=0.8)
    x0, x1, y0, y1 = -0.16, 0.16, -0.16, 0.1
    base, eave, over = 0.2, 1.3, 0.06
    foundation(m, x0, x1, y0, y1, base, brick)
    m.box(x0, x1, y0, y1, 0, eave, shingle, 0.003)
    lap_siding(m, x0, x1, y0, y1, base + 0.04, eave - 0.05, material("shingle shadow " + str(look), colours[look % len(colours)], rough=0.95, grime=0.8), gap=0.09)
    corner_boards(m, x0, x1, y0, y1, base, eave, trim)
    gable(m, x0, x1, y0, y1, eave, 2.15, roof, shingle, along_x=False, over=over, trim=trim, fascia=0.05)
    # Rafter tails under the eaves down both sides, and knee braces under the gable ends.
    for k in range(10):
        y = y0 - over + 0.012 + k * (y1 - y0 + 2 * over - 0.024) / 9
        for x, d in ((x0, -1), (x1, 1)):
            m.box(min(x, x + d * (over + 0.012)), max(x, x + d * (over + 0.012)), y - 0.004, y + 0.004, eave - 0.07, eave - 0.01, rafter)
    slope = (2.15 - eave) / ((x1 - x0) / 2 + over)
    for y, d in ((y0, -1), (y1, 1)):
        for x in (x0 + 0.04, x1 - 0.04):
            top = eave + ((x1 - x0) / 2 + over - abs(x)) * slope - 0.05
            m.wedge([(x - 0.003, y, top - 0.3), (x - 0.003, y, top), (x - 0.003, y + d * (over - 0.01), top)],
                    [(x + 0.003, y, top - 0.3), (x + 0.003, y, top), (x + 0.003, y + d * (over - 0.01), top)], rafter)
    pane(m, "y", y1 + 0.003, 1, 0.0, 1.68, 1.9, 0.05, gable_glass(), trim, trim, sash=False, bars=1)
    windows(m, x0, x1, y0, y1, 0.05, 1, 3, 3, glass(), trim, w=0.05, h=0.55, trim=trim, skip=((0, 1),))
    door(m, 0.0, y1, material("bungalow door", "#5a3a26", rough=0.6), trim, w=0.045, z0=base)
    # The porch across the front under its own low gable.
    py1 = y1 + 0.1
    verandah(m, -0.15, 0.15, y1, py1, base, 1.15, roof, material("porch floor", "#8a7a66", rough=0.8), trim, trim, posts=2,
             step_at=0.0, rail=False, taper=0.62, skirt=brick, pitch=0.0)
    gable(m, -0.15, 0.15, y1 - 0.02, py1, 1.15, 1.6, roof, shingle, along_x=False, over=0.035, thick=0.06, trim=trim, fascia=0.05, ends=(False, True))
    for x in (-0.12, 0.12):
        m.box(x - 0.004, x + 0.004, y1, py1 + 0.03, 1.08, 1.13, rafter)
    chimney(m, x0 - 0.025, -0.06, 0, 2.4, brick, 0.03)
    path(m, 0.0, py1 + 0.06, 0.5)
    # A driveway down the side to a garage at the back, a board fence round the back yard and a shade tree.
    drive(m, 0.32, -0.3, 0.5)
    m.box(0.22, 0.42, -0.44, -0.26, 0, 1.0, shingle, 0.003)
    gable(m, 0.22, 0.42, -0.44, -0.26, 1.0, 1.4, roof, shingle, along_x=False, over=0.03, thick=0.05, trim=trim, fascia=0.04)
    for x0g in (0.25, 0.325):
        m.box(x0g, x0g + 0.065, -0.26, -0.254, 0, 0.8, material("garage doors", "#5a4632", rough=0.7))
        m.box(x0g + 0.008, x0g + 0.057, -0.254, -0.251, 0.55, 0.72, glass())
    fence_round(m, "board", material("cedar fence", "#9a7a5a", rough=0.9), side_front=-0.2)
    flowers(m, -0.15, -0.05, 0.215, 0.24)
    yard_tree(m, -0.3, -0.3, 1.0, seed=5)


def wartime_house(m, look=0):
    """The wartime Victory house, 1942 to 1955: a storey and a half under a steep side gable, plain siding, small windows,
    a stoop with a little hood over the door."""
    looks = [("#e8e4d8", "#3e5a3a"), ("#c8d0b8", "#7a2a22"), ("#d8c8a0", "#2a3e5a")]
    face, accent = looks[look % len(looks)]
    siding = material("wartime siding " + str(look), face, rough=0.8)
    trim = material("wartime trim " + str(look), accent, rough=0.6)
    white = material("wartime white", "#f0ece2", rough=0.6)
    roof = material("wartime roof", "#3e3a38", rough=0.85)
    concrete = material("concrete foundation", "#a8a49a", rough=0.95)
    x0, x1, y0, y1 = -0.13, 0.13, -0.12, 0.1
    base, eave = 0.2, 1.4
    foundation(m, x0, x1, y0, y1, base, concrete)
    m.box(x0, x1, y0, y1, 0, eave, siding, 0.003)
    lap_siding(m, x0, x1, y0, y1, base + 0.05, eave - 0.05, material("wartime lap " + str(look), face, rough=0.9, grime=0.7), gap=0.09)
    corner_boards(m, x0, x1, y0, y1, base, eave, white, w=0.007)
    gable(m, x0, x1, y0, y1, eave, 2.75, roof, siding, along_x=True, over=0.018, trim=white, fascia=0.04)
    # Two front windows, one in each gable end lighting the rooms upstairs, two at the back.
    for x in (-0.075, 0.075):
        pane(m, "y", y1, 1, x, base + 0.3, base + 0.85, 0.05, glass(), white, white)
        for sx in (x - 0.046, x + 0.034):
            m.box(sx, sx + 0.012, y1, y1 + 0.006, base + 0.28, base + 0.87, trim)
        pane(m, "y", y0, -1, x, base + 0.35, base + 0.85, 0.045, glass(), white, white)
    for xx, d in ((x0, -1), (x1, 1)):
        pane(m, "x", xx + d * 0.003, d, (y0 + y1) / 2, 1.62, 2.05, 0.05, gable_glass(), white, white)
        pane(m, "x", xx, d, 0.0, base + 0.35, base + 0.85, 0.045, glass(), white, white)
    door(m, 0.0, y1, trim, white, w=0.042, z0=base)
    steps(m, 0.0, y1, 0.065, base, concrete, n=2, landing=0.035)
    gable(m, -0.035, 0.035, y1 - 0.01, y1 + 0.04, 1.3, 1.5, roof, white, along_x=False, over=0.008, thick=0.04, trim=white, fascia=0.03, ends=(False, True))
    chimney(m, 0.0, 0.0, 2.0, 3.05, material("chimney brick", "#8a4a36", rough=0.9), 0.025)
    path(m, 0.0, y1 + 0.06, 0.5)
    # A picket fence along the front, a victory garden and a clothesline out back.
    picket(m, (-EDGE, EDGE), (EDGE, EDGE), material("white pickets", "#eeeae0", rough=0.7), gap=0.0)
    fence_round(m, "board", material("board fence", "#8a7a64", rough=0.9), side_front=-0.12)
    garden(m, -0.4, -0.06, -0.42, -0.22, rows=5)
    pole = material("clothesline pole", "#5a5a5a", metal=0.4, rough=0.6)
    for y in (-0.2, -0.42):
        m.box(0.25, 0.258, y - 0.004, y + 0.004, 0, 0.9, pole)
        m.box(0.22, 0.29, y - 0.003, y + 0.003, 0.86, 0.9, pole)
    for x in (0.225, 0.285):
        m.rod((x, -0.2, 0.87), (x, -0.42, 0.87), 0.0015, pole, segments=4)
    for k, c in enumerate(("#e8e4dc", "#6a8ab0", "#c84a4a", "#e8e4dc")):
        y = -0.25 - k * 0.04
        m.box(0.22, 0.23, y - 0.012, y + 0.012, 0.6, 0.86, material("washing " + c, c, rough=0.9))
    shed(m, 0.36, 0.0, w=0.05, d=0.05, colour="#6a6a5e")
    yard_tree(m, -0.38, -0.08, 0.8, seed=13)


def ranch_bungalow(m, look=0):
    """The postwar ranch bungalow, 1955 to 2010: long and low under a hip roof with wide eaves, a brick front with a
    picture window, siding round the sides and back, and a carport at one end."""
    bricks = ["#9a5a42", "#b89a7a", "#8a6a5a"]
    brick = material("ranch brick " + str(look), bricks[look % len(bricks)], rough=0.9)
    siding = material("ranch siding", "#d8d4c4", rough=0.75)
    trim = material("ranch trim", "#f0ece2", rough=0.6)
    roof = material("ranch roof", "#54504c", rough=0.85)
    concrete = material("ranch concrete", "#a8a49a", rough=0.95)
    x0, x1, y0, y1 = -0.34, 0.12, -0.13, 0.1
    base, eave = 0.1, 1.15
    foundation(m, x0, x1, y0, y1, base, concrete)
    m.box(x0, x1, y0, y1, 0, eave, siding, 0.003)
    lap_siding(m, x0, x1, y0, y1, base + 0.05, eave - 0.04, material("ranch lap", "#c4c0b0", rough=0.85), gap=0.08)
    # Brick veneer across the front.
    m.box(x0 - 0.002, x1 + 0.002, y1, y1 + 0.008, base, eave, brick, 0.001)
    corner_boards(m, x0, x1, y0, y1 - 0.0, base, eave, trim, w=0.006)
    hip(m, x0, x1, y0, y1, eave, 1.9, roof, over=0.06, thick=0.06, trim=trim, fascia=0.09)
    # The picture window in the living room, smaller windows to the bedrooms and the door between.
    fy = y1 + 0.008
    pane(m, "y", fy, 1, -0.2, 0.38, 0.98, 0.14, glass(), trim, concrete, sash=False, bars=2)
    for x in (0.04, 0.09):
        pane(m, "y", fy, 1, x, 0.6, 0.98, 0.04, glass(), trim, concrete, sash=False)
    for x in (-0.31,):
        pane(m, "y", fy, 1, x, 0.6, 0.98, 0.04, glass(), trim, concrete, sash=False)
    door(m, -0.07, fy, material("ranch door", "#2a4a5a", rough=0.5), trim, w=0.045, z0=base)
    m.box(-0.065, -0.05, fy, fy + 0.005, base + 0.05, base + 0.9, glass())
    steps(m, -0.07, fy, 0.09, base, concrete, n=1, landing=0.05)
    for x in (-0.3, -0.15, 0.05):
        pane(m, "y", y0, -1, x, 0.55, 0.98, 0.05, glass(), trim, trim, sash=False)
    pane(m, "x", x0, -1, 0.0, 0.55, 0.98, 0.05, glass(), trim, trim, sash=False)
    chimney(m, -0.2, -0.04, 1.4, 2.2, brick, 0.03)
    # The carport: a flat roof with a deep fascia on thin steel posts, a storage room at its back and a car under it.
    cx0, cx1 = x1, 0.38
    paved(m, cx0, cx1, y0 - 0.02, y1 + 0.04, colour="#8a8680")
    m.box(cx0, cx1 - 0.02, y0, y0 + 0.07, 0, 1.05, siding, 0.002)
    m.box(cx0 + 0.04, cx0 + 0.09, y0 + 0.07, y0 + 0.074, base, 0.9, material("store door", "#e8e4dc", rough=0.6))
    m.box(cx0, cx1 + 0.02, y0 - 0.03, y1 + 0.06, 1.05, 1.12, roof)
    m.box(cx0, cx1 + 0.024, y0 - 0.034, y1 + 0.064, 0.98, 1.06, trim)
    m.box(cx0 + 0.004, cx1 + 0.02, y0 - 0.03, y1 + 0.06, 0.96, 0.98, trim)
    for y in (y1 + 0.04, y0 + 0.08):
        m.box(cx1 - 0.008, cx1 + 0.002, y - 0.005, y + 0.005, 0, 0.98, material("carport posts", "#3a3a3a", metal=0.5, rough=0.5))
    car(m, (cx0 + cx1) / 2, 0.03, ["#6a8aa0", "#b84a3a", "#e8dcb8"][look % 3])
    drive(m, (cx0 + cx1) / 2, y1 + 0.06, 0.5, w=0.1, colour="#8a8680")
    path(m, -0.07, fy + 0.05, 0.5)
    # Shrubs along the front, a lamp post by the walk, a fence round the back yard and a young maple.
    shrub = scenery.leafy("foundation shrubs", "#3e6a2a", "#6a9a3e")
    for k, x in enumerate((-0.32, -0.23, -0.15, 0.03)):
        scenery.blob(m, x, fy + 0.03, 0.15, 0.028, shrub, 40 + k, squash=0.8)
    lamp_post(m, -0.12, 0.4)
    fence_round(m, "board", material("cedar fence", "#9a7a5a", rough=0.9), side_front=-0.16)
    paved(m, -0.3, -0.1, -0.3, -0.13, colour="#a8a296")
    yard_tree(m, 0.3, -0.34, 0.85, seed=14)


def walkup(m):
    """A Winnipeg or Toronto walk-up, 1910 to 2000: four storeys of brick on a stone base, an entrance bay with a stone
    surround and the building's name over the door, canted bay windows each side, a corbelled brick cornice, stacked
    wooden sunporches at the back and a fire escape down one side."""
    brick = material("walkup brick", "#8a5a46", rough=0.9)
    dark = material("walkup corbels", "#7a4e3c", rough=0.9)
    trim = material("walkup trim", "#c8c0b0", rough=0.8)
    sash = material("walkup sashes", "#e6e0d0", rough=0.6)
    iron = material("fire escape", "#2a2a2a", metal=0.5, rough=0.5)
    x0, x1, y0, y1 = -0.34, 0.34, -0.28, 0.22
    top, base = 4 * STOREY + 0.2, 0.3
    m.box(x0, x1, y0, y1, 0, top, brick, 0.003)
    foundation(m, x0, x1, y0, y1, base, trim, out=0.006)
    flat_top(m, x0, x1, y0, y1, top, trim)
    # The corbelled cornice: courses stepping out, a row of corbels, and a last course under the coping.
    for z, out in ((top - 0.42, 0.005), (top - 0.36, 0.01)):
        m.box(x0 - out, x1 + out, y0 - out, y1 + out, z, z + 0.06, brick)
    for face, at, d, a0, a1 in (("y", y1, 1, x0, x1), ("y", y0, -1, x0, x1), ("x", x1, 1, y0, y1), ("x", x0, -1, y0, y1)):
        n = int((a1 - a0) / 0.028)
        for k in range(n):
            a = a0 + (k + 0.5) * (a1 - a0) / n
            on_wall(m, face, at, d, a - 0.006, a + 0.006, 0, 0.016, top - 0.3, top - 0.16, dark)
    m.box(x0 - 0.018, x1 + 0.018, y0 - 0.018, y1 + 0.018, top - 0.16, top - 0.06, brick)
    # The entrance bay, standing out from the front and up past the cornice, its name panel over the door.
    ex, ey = 0.075, y1 + 0.025
    m.box(-ex, ex, y1, ey, 0, top + 0.45, brick, 0.002)
    m.box(-ex - 0.006, ex + 0.006, y1, ey + 0.006, 0, base, trim)
    m.box(-ex - 0.008, ex + 0.008, y1, ey + 0.008, top + 0.45, top + 0.53, trim)
    m.box(-ex - 0.004, ex + 0.004, y1, ey + 0.004, top - 0.16, top - 0.06, trim)
    for x in (-0.054, 0.042):
        m.box(x, x + 0.012, ey, ey + 0.012, 0.2, 1.45, trim)
    m.box(-0.068, 0.068, ey, ey + 0.016, 1.45, 1.6, trim)
    m.box(-0.052, 0.052, ey, ey + 0.01, 1.62, 1.84, trim)
    sign_text(m, "THE ROSLYN", "y", ey, 1, 0.0, 1.73, 0.014, material("name letters", "#3a3430", rough=0.8), o=0.01)
    m.wedge([(-0.06, ey, 1.84), (0.06, ey, 1.84), (0.0, ey, 1.96)], [(-0.06, ey + 0.014, 1.84), (0.06, ey + 0.014, 1.84), (0.0, ey + 0.014, 1.96)], trim)
    door(m, 0.0, ey, material("walkup door", "#3a2a22", rough=0.6), sash, w=0.06, h=0.95, z0=0.2, transom=glass())
    steps(m, 0.0, ey, 0.12, 0.2, trim, n=3)
    # The stair windows, at the landings between floors.
    for s in range(1, 4):
        z = s * STOREY + 0.62
        pane(m, "y", ey, 1, 0.0, z, z + 0.5, 0.05, glass(), sash, trim)
    # Canted bay windows each side of the entrance, from the stone base up to the cornice.
    for cx in (-0.22, 0.22):
        bx0, bx1, bd = cx - 0.065, cx + 0.065, 0.04
        bay = [(bx1, y1), (bx0, y1), (bx0 + 0.022, y1 + bd), (bx1 - 0.022, y1 + bd)]
        m.wedge([(x, y, 0) for x, y in bay], [(x, y, top - 0.45) for x, y in bay], brick)
        low = [(bx1 + 0.005, y1), (bx0 - 0.005, y1), (bx0 + 0.02, y1 + bd + 0.006), (bx1 - 0.02, y1 + bd + 0.006)]
        m.wedge([(x, y, 0) for x, y in low], [(x, y, base) for x, y in low], trim)
        cap = [(bx1 + 0.008, y1), (bx0 - 0.008, y1), (bx0 + 0.019, y1 + bd + 0.01), (bx1 - 0.019, y1 + bd + 0.01)]
        m.wedge([(x, y, top - 0.45) for x, y in cap], [(x, y, top - 0.38) for x, y in cap], trim)
        for s in range(4):
            z0 = s * STOREY + 0.1 + 0.38
            z1 = z0 + 0.6
            face_pane(m, (bx1 - 0.022, y1 + bd), (bx0 + 0.022, y1 + bd), z0, z1, glass(), sash, 0.005, 0.12, 0.88)
            face_pane(m, (bx0 + 0.022, y1 + bd), (bx0, y1), z0, z1, glass(), sash, 0.005, 0.2, 0.8)
            face_pane(m, (bx1, y1), (bx1 - 0.022, y1 + bd), z0, z1, glass(), sash, 0.005, 0.2, 0.8)
            if s:
                band = [(bx1 + 0.003, y1), (bx0 - 0.003, y1), (bx0 + 0.021, y1 + bd + 0.004), (bx1 - 0.021, y1 + bd + 0.004)]
                m.wedge([(x, y, z0 - 0.12) for x, y in band], [(x, y, z0 - 0.06) for x, y in band], trim)
        # The windows between the bay and the entrance.
        for s in range(4):
            z = s * STOREY + 0.1 + 0.38
            pane(m, "y", y1, 1, cx * 0.52, z, z + 0.6, 0.045, glass(), sash, trim)
    # Windows down the sides and across the back.
    for s in range(4):
        z = s * STOREY + 0.1 + 0.38
        for k in range(4):
            y = y0 + (k + 0.5) * (y1 - y0) / 4
            for x, d in ((x1, 1), (x0, -1)):
                pane(m, "x", x, d, y, z, z + 0.6, WINDOW_W, glass(), sash, trim)
        for x in (-0.27, 0.0, 0.27):
            pane(m, "y", y0, -1, x, z, z + 0.6, WINDOW_W, glass(), sash, trim)
    # Stacked wooden sunporches at the back, a glazed porch on each floor on posts over the yard.
    boards = material("sunporch boards", "#e8e2d2", rough=0.75)
    green = material("sunporch trim", "#3e5a3a", rough=0.6)
    for cx in (-0.17, 0.17):
        px0, px1, py0 = cx - 0.075, cx + 0.075, y0 - 0.075
        for x in (px0 + 0.006, px1 - 0.006):
            m.box(x - 0.006, x + 0.006, py0, py0 + 0.012, 0, 0.3, boards)
        m.box(px0, px1, py0, y0, 0.3, 4 * STOREY + 0.15, boards, 0.002)
        for s in range(4):
            z = 0.3 + s * STOREY
            m.box(px0 - 0.004, px1 + 0.004, py0 - 0.004, y0, z, z + 0.06, green)
            for face, at, d, a0, a1 in (("y", py0, -1, px0, px1), ("x", px0, -1, py0, y0), ("x", px1, 1, py0, y0)):
                on_wall(m, face, at, d, a0 + 0.01, a1 - 0.01, -0.002, 0.003, z + 0.45, z + 1.05, glass())
                n = max(1, round((a1 - a0) / 0.03))
                for k in range(n + 1):
                    a = a0 + 0.01 + k * (a1 - a0 - 0.02) / n
                    on_wall(m, face, at, d, a - 0.003, a + 0.003, 0, 0.006, z + 0.45, z + 1.05, green)
                on_wall(m, face, at, d, a0 + 0.01, a1 - 0.01, 0, 0.006, z + 1.05, z + 1.1, green)
        slab(m, [(px0 - 0.01, y0, 4 * STOREY + 0.3), (px1 + 0.01, y0, 4 * STOREY + 0.3), (px1 + 0.01, py0 - 0.012, 4 * STOREY + 0.15),
                 (px0 - 0.01, py0 - 0.012, 4 * STOREY + 0.15)], 0.05, material("sunporch roof", "#4e4844", rough=0.85))
        on_wall(m, "y", py0, -1, cx - 0.02, cx + 0.02, 0, 0.008, 0.3, 1.2, green)
        steps(m, cx, py0, 0.05, 0.3, boards, n=3, d=-1)
    # A fire escape down one side: landings with railings, stairs between them and a drop ladder.
    for s in range(1, 4):
        z = s * STOREY + 0.1
        m.box(x1, x1 + 0.05, -0.12, 0.06, z, z + 0.04, iron)
        m.box(x1 + 0.045, x1 + 0.05, -0.12, 0.06, z + 0.04, z + 0.45, iron)
        for y in (-0.12, 0.06):
            m.box(x1, x1 + 0.05, y - 0.002, y + 0.002, z + 0.04, z + 0.45, iron)
        if s < 3:
            m.rod((x1 + 0.025, -0.1, z + 0.04), (x1 + 0.025, 0.04, z + STOREY), 0.004, iron)
    m.rod((x1 + 0.03, 0.04, 0.6), (x1 + 0.03, 0.04, STOREY + 0.1), 0.003, iron)
    m.rod((x1 + 0.02, 0.04, 0.6), (x1 + 0.02, 0.04, STOREY + 0.1), 0.003, iron)
    chimney(m, -0.2, -0.05, top, top + 0.8, brick, 0.035)
    m.box(0.08, 0.18, -0.12, -0.02, top, top + 0.55, brick, 0.003)
    m.box(0.074, 0.186, -0.126, -0.014, top + 0.55, top + 0.6, trim)
    path(m, 0.0, ey + 0.06, 0.5, 0.08)
    # A paved forecourt, chain-link round the back where the bins are, and trees along the side.
    paved(m, -0.12, 0.12, 0.22, 0.5)
    fence_round(m, "chain", side_front=-0.28)
    m.box(-0.46, -0.38, -0.46, -0.38, 0, 0.35, material("bins", "#3e4a3e", rough=0.8), 0.004)
    yard_tree(m, -0.42, 0.36, 0.8, seed=6, kind="elm")
    yard_tree(m, 0.42, 0.36, 0.8, seed=7, kind="elm")


def split_level(m, look=0):
    brick = material("split brick", ["#b8a48a", "#a87a5a"][look % 2], rough=0.9)
    siding = material("split siding", "#d8d4c8", rough=0.75)
    lap = material("split lap", "#c4c0b2", rough=0.85)
    roof = material("split roof", "#5a5450", rough=0.85)
    trim = material("split trim", "#f0ece2", rough=0.6)
    concrete = material("split concrete", "#a8a49a", rough=0.95)
    # The low wing in brick veneer, the raised wing beside it brick up to the upper floor and siding above, low eaves
    # with a wide fascia, and a carport on the end.
    base = 0.1
    foundation(m, -0.2, 0.02, -0.12, 0.12, base, concrete)
    m.box(-0.2, 0.02, -0.12, 0.12, 0, 1.3, brick, 0.003)
    gable(m, -0.2, 0.02, -0.12, 0.12, 1.3, 2.0, roof, brick, along_x=True, over=0.06, trim=trim, fascia=0.1)
    m.box(0.02, 0.2, -0.14, 0.1, 0, 1.05, brick, 0.003)
    m.box(0.02, 0.2, -0.14, 0.1, 1.05, 2.3, siding, 0.003)
    m.box(0.016, 0.204, -0.144, 0.104, 1.03, 1.08, trim)
    lap_siding(m, 0.02, 0.2, -0.14, 0.1, 1.15, 2.25, lap, gap=0.08)
    corner_boards(m, 0.02, 0.2, -0.14, 0.1, 1.08, 2.3, trim, w=0.007)
    gable(m, 0.02, 0.2, -0.14, 0.1, 2.3, 3.0, roof, siding, along_x=True, over=0.06, trim=trim, fascia=0.1)
    # The picture window in the living room, and the windows of both levels in the raised wing.
    pane(m, "y", 0.12, 1, -0.13, 0.38, 1.0, 0.11, glass(), trim, concrete, sash=False, bars=2)
    pane(m, "y", -0.12, -1, -0.09, 0.5, 1.0, 0.06, glass(), trim, trim)
    for x in (0.065, 0.155):
        pane(m, "y", 0.1, 1, x, 1.3, 1.85, 0.05, glass(), trim, trim)
        pane(m, "y", 0.1, 1, x, 0.35, 0.72, 0.05, glass(), trim, concrete, sash=False)
        pane(m, "y", -0.14, -1, x, 1.3, 1.85, 0.05, glass(), trim, trim)
    pane(m, "x", -0.2, -1, 0.0, 0.5, 1.0, 0.06, glass(), trim, trim)
    pane(m, "x", 0.2, 1, -0.04, 1.3, 1.85, 0.05, glass(), trim, trim)
    door(m, -0.03, 0.12, material("split door", "#2a3a4a", rough=0.5), trim, z0=base)
    steps(m, -0.03, 0.12, 0.08, base, concrete, n=1, landing=0.04)
    m.box(-0.06, 0.0, 0.12, 0.16, 1.2, 1.25, trim)
    # The carport: its low gable on thin posts, a storage wall at the back and a car under it.
    m.box(0.2, 0.36, -0.1, -0.04, 0, 1.15, siding, 0.003)
    m.box(0.24, 0.29, -0.04, -0.036, base, 0.9, material("store door", "#e8e4dc", rough=0.6))
    gable(m, 0.2, 0.36, -0.1, 0.13, 1.15, 1.7, roof, siding, along_x=False, over=0.03, trim=trim, fascia=0.08, ends=(False, True))
    for x in (0.215, 0.345):
        m.box(x - 0.005, x + 0.005, 0.118, 0.128, 0, 1.15, material("carport posts", "#e8e4dc", rough=0.6))
    m.box(0.2, 0.36, 0.115, 0.13, 1.07, 1.15, trim)
    m.box(0.21, 0.35, -0.1, 0.5, 0, 0.025, material("driveway", "#6a6866", rough=0.95))
    car(m, 0.28, 0.03, ["#3e6a5a", "#c8b47a"][look % 2])
    path(m, -0.03, 0.16, 0.5)
    lamp_post(m, -0.1, 0.42)
    # A cedar privacy fence round the back yard, a patio and a young tree out front.
    fence_round(m, "board", material("cedar fence", "#9a7a5a", rough=0.9), side_front=-0.15)
    paved(m, -0.18, 0.0, -0.3, -0.14, colour="#a8a296")
    yard_tree(m, -0.3, 0.32, 0.75, seed=8)
    flowers(m, -0.19, -0.07, 0.135, 0.16)


def office_block(m):
    """The International style office block, 1950 to 1990: a curtain wall of glass and light spandrels between
    aluminium mullions, raised on columns over a glass lobby set back under it, a canopy over the doors and a louvred
    mechanical penthouse on the roof."""
    concrete = material("office concrete", "#b4b0a8", rough=0.85)
    spandrel = material("office spandrels", "#8e9ca2", metal=0.2, rough=0.5)
    curtain_g = material("curtain glass", "#3e5a6e", metal=0.3, rough=0.1, grime=0.15)
    alu = material("aluminium mullions", "#c4c8cc", metal=0.7, rough=0.35)
    lobby = material("lobby glass", "#2c3a48", rough=0.15)
    x0, x1, y0, y1 = -0.32, 0.32, -0.3, 0.28
    storeys, lob = 7, STOREY
    top = lob + storeys * STOREY
    # The lobby, glass all round, set back under the tower between its columns.
    lx0, lx1, ly0, ly1 = x0 + 0.06, x1 - 0.06, y0 + 0.06, y1 - 0.06
    m.box(lx0, lx1, ly0, ly1, 0, lob, lobby)
    for face, at, d, a0, a1 in (("y", ly1, 1, lx0, lx1), ("y", ly0, -1, lx0, lx1), ("x", lx1, 1, ly0, ly1), ("x", lx0, -1, ly0, ly1)):
        n = round((a1 - a0) / 0.05)
        for k in range(n + 1):
            a = a0 + k * (a1 - a0) / n
            on_wall(m, face, at, d, a - 0.003, a + 0.003, 0, 0.005, 0, lob, alu)
        on_wall(m, face, at, d, a0, a1, 0, 0.005, 0.95, 1.0, alu)
    for x in (-0.03, 0.03):
        on_wall(m, "y", ly1, 1, x - 0.024, x + 0.024, 0, 0.007, 0.03, 0.9, material("lobby doors", "#1e2a34", rough=0.2))
    cols = [x0 + 0.03 + k * (x1 - x0 - 0.06) / 4 for k in range(5)]
    rows = [y0 + 0.03 + k * (y1 - y0 - 0.06) / 3 for k in range(4)]
    for x in cols:
        for y in rows:
            if x in (cols[0], cols[-1]) or y in (rows[0], rows[-1]):
                m.box(x - 0.012, x + 0.012, y - 0.012, y + 0.012, 0, lob, concrete, 0.002)
    m.box(x0, x1, y0, y1, lob - 0.1, lob, concrete)
    # The tower over it: spandrels, vision glass and mullion fins floor after floor.
    m.box(x0, x1, y0, y1, lob, top, spandrel, 0.002)
    curtain(m, x0, x1, y0, y1, lob, storeys, curtain_g, alu, gap=0.04, vision=(0.4, 1.15))
    flat_top(m, x0, x1, y0, y1, top, alu, parapet=0.2)
    # The mechanical penthouse, louvred all round.
    px0, px1, py0, py1 = -0.15, 0.12, -0.16, 0.1
    m.box(px0, px1, py0, py1, top, top + 0.95, concrete, 0.003)
    for face, at, d, a0, a1 in (("y", py1, 1, px0, px1), ("y", py0, -1, px0, px1), ("x", px1, 1, py0, py1), ("x", px0, -1, py0, py1)):
        louvres(m, face, at, d, (a0 + a1) / 2, top + 0.2, top + 0.8, a1 - a0 - 0.04, concrete, alu)
    m.box(px0 - 0.008, px1 + 0.008, py0 - 0.008, py1 + 0.008, top + 0.95, top + 1.02, concrete)
    m.box(0.16, 0.26, 0.08, 0.2, top, top + 0.35, alu, 0.003)
    # The canopy over the doors, cantilevered out over the plaza.
    m.box(-0.12, 0.12, ly1, y1 + 0.1, 0.98, 1.04, concrete)
    m.box(-0.122, 0.122, y1 + 0.096, y1 + 0.104, 0.9, 1.06, alu)
    m.box(x0, x1, y0, 0.5, 0, 0.03, material("plaza", "#9a968e", rough=0.9))
    # Planters with trees and a flagpole on the plaza.
    for x in (-0.38, 0.38):
        m.box(x - 0.05, x + 0.05, 0.35, 0.45, 0, 0.3, material("planter", "#8a867e", rough=0.9), 0.004)
        yard_tree(m, x, 0.4, 0.7, seed=9, kind="elm")
    m.upright(0.22, 0.42, 0, 3.2, 0.004, 0.003, alu, segments=8)
    red = material("flag red", "#c8282a", rough=0.8)
    for a, b, mat in ((0.424, 0.444, red), (0.444, 0.476, material("flag white", "#f0ece4", rough=0.8)), (0.476, 0.496, red)):
        m.box(0.222, 0.226, a, b, 2.75, 3.15, mat)
    m.box(0.221, 0.227, 0.453, 0.467, 2.88, 3.02, red)


def stone_office(m):
    """A 1920s office tower, 1910 to 1950: a three-storey base of rusticated limestone with an arched entrance, a shaft
    of buff terra cotta with piers running up between the windows, setbacks near the top each with a cornice, and a
    copper hip roof."""
    lime = material("office limestone", "#cfc6b2", rough=0.85)
    groove = material("rustication", "#a8a090", rough=0.9)
    terra = material("terra cotta", "#d2bc94", rough=0.8)
    spandrel = material("office spandrel", "#6a5e50", rough=0.7)
    copper = material("copper roof", "#5e8a72", metal=0.4, rough=0.5)
    sash = material("bronze sashes", "#4a3e30", metal=0.4, rough=0.5)
    bronze = material("bronze doors", "#5a4428", metal=0.6, rough=0.4)
    x0, x1, y0, y1 = -0.32, 0.32, -0.3, 0.28
    bt = 3 * STOREY
    m.box(x0, x1, y0, y1, 0, bt, lime, 0.003)
    z = 0.2
    while z < 1.55:
        m.box(x0 - 0.002, x1 + 0.002, y0 - 0.002, y1 + 0.002, z, z + 0.025, groove)
        z += 0.2
    m.box(x0 - 0.008, x1 + 0.008, y0 - 0.008, y1 + 0.008, 1.6, 1.68, lime)
    m.box(x0 - 0.02, x1 + 0.02, y0 - 0.02, y1 + 0.02, bt - 0.1, bt + 0.06, lime)
    m.box(x0 - 0.012, x1 + 0.012, y0 - 0.012, y1 + 0.012, bt - 0.2, bt - 0.1, lime)
    # Tall arched windows on the ground floor, the entrance arch in the middle of the front, and plain windows over them.
    for k in range(6):
        x = x0 + (k + 0.5) * (x1 - x0) / 6
        if abs(x) > 0.1:
            arched_pane(m, "y", y1, 1, x, 0.3, 1.2, 0.06, glass(), sash, lime, rise=0.12)
        arched_pane(m, "y", y0, -1, x, 0.3, 1.2, 0.06, glass(), sash, lime, rise=0.12)
        for s in (1, 2):
            pane(m, "y", y1, 1, x, s * STOREY + 0.4, s * STOREY + 1.0, 0.055, glass(), sash, lime)
            pane(m, "y", y0, -1, x, s * STOREY + 0.4, s * STOREY + 1.0, 0.055, glass(), sash, lime)
    for k in range(5):
        y = y0 + (k + 0.5) * (y1 - y0) / 5
        for xx, d in ((x1, 1), (x0, -1)):
            arched_pane(m, "x", xx, d, y, 0.3, 1.2, 0.06, glass(), sash, lime, rise=0.12)
            for s in (1, 2):
                pane(m, "x", xx, d, y, s * STOREY + 0.4, s * STOREY + 1.0, 0.055, glass(), sash, lime)
    ea, ez = 0.055, 1.05
    on_face(m, "y", y1, 1, [(-ea, 0.0), (ea, 0.0)] + arc(ea, -ea, ez, 0.35, n=8), -0.002, 0.004, material("entrance shade", "#2a2622", rough=0.9))
    on_face(m, "y", y1, 1, [(-0.04, 0.03), (0.04, 0.03), (0.04, 0.95), (-0.04, 0.95)], 0.004, 0.008, bronze)
    on_face(m, "y", y1, 1, [(-0.04, 1.0)] + [(a, z - 0.04) for a, z in arc(-0.04, 0.04, ez, 0.3, n=8)][1:-1] + [(0.04, 1.0)], 0.004, 0.007, glass())
    arch_band(m, "y", y1, 1, arc(-ea, ea, ez, 0.35, n=8), [(a, z + 0.18) for a, z in arc(-ea - 0.02, ea + 0.02, ez, 0.35, n=8)], 0, 0.014, lime)
    on_wall(m, "y", y1, 1, -0.008, 0.008, 0, 0.02, ez + 0.3, ez + 0.62, lime)
    for x in (-ea - 0.012, ea + 0.012):
        on_wall(m, "y", y1, 1, x - 0.012, x + 0.012, 0, 0.012, 0, ez, lime)
    steps(m, 0.0, y1, 0.13, 0.1, lime, n=2)

    def shaft(sx0, sx1, sy0, sy1, z0, z1, bays_x, bays_y):
        """A block of the shaft: terra cotta with piers up between the bays, glass and dark spandrels between them."""
        m.box(sx0, sx1, sy0, sy1, z0, z1, terra, 0.003)
        n = round((z1 - z0) / STOREY)
        for face, at, d, a0, a1, bays in (("y", sy1, 1, sx0, sx1, bays_x), ("y", sy0, -1, sx0, sx1, bays_x),
                                          ("x", sx1, 1, sy0, sy1, bays_y), ("x", sx0, -1, sy0, sy1, bays_y)):
            w = (a1 - a0) / bays
            for k in range(bays):
                a = a0 + (k + 0.5) * w
                on_wall(m, face, at, d, a - w * 0.32, a + w * 0.32, -0.002, 0.002, z0 + 0.1, z1 - 0.15, spandrel)
                for s in range(n):
                    z = z0 + s * STOREY + 0.3
                    on_wall(m, face, at, d, a - w * 0.28, a + w * 0.28, 0, 0.004, z, z + 0.72, glass())
                    on_wall(m, face, at, d, a - 0.002, a + 0.002, 0, 0.006, z, z + 0.72, sash)
            for k in range(bays + 1):
                a = min(max(a0 + k * w, a0 + 0.008), a1 - 0.008)
                if face == "x" and k in (0, bays):
                    continue
                on_wall(m, face, at, d, a - 0.008, a + 0.008, 0, 0.01, z0, z1 - 0.05, terra)
        m.box(sx0 - 0.014, sx1 + 0.014, sy0 - 0.014, sy1 + 0.014, z1 - 0.08, z1 + 0.04, lime)
        m.box(sx0 - 0.02, sx1 + 0.02, sy0 - 0.02, sy1 + 0.02, z1 + 0.04, z1 + 0.1, lime)

    z1 = bt + 4 * STOREY
    shaft(x0 + 0.02, x1 - 0.02, y0 + 0.02, y1 - 0.02, bt + 0.06, z1, 7, 6)
    z2 = z1 + STOREY + 0.1
    shaft(x0 + 0.08, x1 - 0.08, y0 + 0.08, y1 - 0.08, z1 + 0.1, z2, 5, 4)
    z3 = z2 + STOREY - 0.3
    shaft(x0 + 0.15, x1 - 0.15, y0 + 0.15, y1 - 0.15, z2 + 0.1, z3, 3, 3)
    # Urns on the corners of each setback, and the copper hip roof with a flagpole.
    for inset, z in ((0.035, z1 + 0.1), (0.095, z2 + 0.1)):
        for x in (x0 + inset, x1 - inset):
            for y in (y0 + inset, y1 - inset):
                m.lathe(x, y, z, [(0.012, 0.0), (0.008, 0.06), (0.014, 0.18), (0.006, 0.3), (0.0, 0.34)], lime, segments=10)
    hip(m, x0 + 0.15, x1 - 0.15, y0 + 0.15, y1 - 0.15, z3 + 0.1, z3 + 0.75, copper, over=0.01, thick=0.05)
    m.upright(0.0, (y0 + y1) / 2, z3 + 0.6, z3 + 1.25, 0.004, 0.003, sash, segments=8)
    m.box(-0.5, 0.5, y1, 0.5, 0, 0.03, material("sidewalk", "#aaa498", rough=0.9))
    for x in (-0.38, 0.38):
        lamp_post(m, x, 0.42, h=0.9)


def clipped(x0, x1, y0, y1, c):
    """A rectangle with its corners clipped c across, as eight (x, y) points going round."""
    return [(x0 + c, y0), (x1 - c, y0), (x1, y0 + c), (x1, y1 - c), (x1 - c, y1), (x0 + c, y1), (x0, y1 - c), (x0, y0 + c)]


def glass_office(m):
    """A tinted glass office tower, 1990 to 2100: a granite podium with a glass entrance hall under a steel and glass
    canopy, and the tower over it in green glass with clipped corners, light bands at each floor and a sloping glass crown."""
    granite = material("granite", "#6e6864", rough=0.6)
    tint = material("tinted glass", "#3a6a68", metal=0.45, rough=0.08, grime=0.1)
    crown_g = material("crown glass", "#3e6462", metal=0.3, rough=0.35, grime=0.1)
    band = material("floor bands", "#c0c8c6", metal=0.6, rough=0.35)
    steel = material("canopy steel", "#8a9094", metal=0.7, rough=0.35)
    x0, x1, y0, y1 = -0.32, 0.32, -0.3, 0.28
    pod = 2 * STOREY
    m.box(x0, x1, y0, y1, 0, pod, granite, 0.003)
    for face, at, d, a0, a1 in (("y", y1, 1, x0, x1), ("y", y0, -1, x0, x1), ("x", x1, 1, y0, y1), ("x", x0, -1, y0, y1)):
        n = round((a1 - a0) / 0.1)
        for k in range(n):
            a = a0 + (k + 0.5) * (a1 - a0) / n
            on_wall(m, face, at, d, a - 0.038, a + 0.038, -0.002, 0.004, 0.08, 1.15, glass())
            on_wall(m, face, at, d, a - 0.038, a + 0.038, -0.002, 0.004, 1.4, 2.25, tint)
    m.box(x0 - 0.008, x1 + 0.008, y0 - 0.008, y1 + 0.008, pod, pod + 0.1, granite)
    # The entrance hall: a glass box standing out from the podium under its canopy.
    m.box(-0.1, 0.1, y1, y1 + 0.05, 0, 1.6, crown_g, 0.002)
    for k in range(5):
        x = -0.1 + k * 0.05
        m.box(x - 0.003, x + 0.003, y1 + 0.05, y1 + 0.055, 0, 1.6, steel)
    slab(m, [(-0.14, y1, 1.75), (0.14, y1, 1.75), (0.14, y1 + 0.13, 1.7), (-0.14, y1 + 0.13, 1.7)], 0.04, material("canopy glass", "#9ab8bc", metal=0.2, rough=0.1))
    for x in (-0.13, 0.13):
        m.rod((x, y1 + 0.12, 1.74), (x, y1, 2.35), 0.003, steel)
    # The tower: green glass with clipped corners and a band at each floor, up to a crown sloping down to the front.
    tx0, tx1, ty0, ty1, c = -0.26, 0.24, -0.24, 0.22, 0.06
    storeys = 6
    tz = pod + 0.1 + storeys * STOREY
    ring = clipped(tx0, tx1, ty0, ty1, c)
    m.wedge([(x, y, pod + 0.1) for x, y in ring], [(x, y, tz) for x, y in ring], tint)
    big = clipped(tx0 - 0.004, tx1 + 0.004, ty0 - 0.004, ty1 + 0.004, c)
    for s in range(storeys + 1):
        z = pod + 0.1 + s * STOREY
        m.wedge([(x, y, z - 0.03) for x, y in big], [(x, y, z + 0.03) for x, y in big], band)
    for face, at, d, a0, a1 in (("y", ty1, 1, tx0 + c, tx1 - c), ("y", ty0, -1, tx0 + c, tx1 - c), ("x", tx1, 1, ty0 + c, ty1 - c), ("x", tx0, -1, ty0 + c, ty1 - c)):
        n = round((a1 - a0) / 0.06)
        for k in range(n + 1):
            a = a0 + k * (a1 - a0) / n
            on_wall(m, face, at, d, a - 0.002, a + 0.002, 0, 0.006, pod + 0.1, tz, band)
    crown = [(x, y, tz + 0.35 + 0.75 * (ty1 - y) / (ty1 - ty0)) for x, y in ring]
    m.wedge([(x, y, tz) for x, y in ring], crown, crown_g)
    m.wedge([(x, y, z) for x, y, z in crown], [(x, y, z + 0.06) for x, y, z in crown], material("crown roof", "#4e5658", metal=0.3, rough=0.6))
    m.upright(-0.02, ty0 + 0.1, tz + 1.0, tz + 1.6, 0.006, 0.003, steel, segments=8)
    # Paving, trees in planters and benches on the plaza.
    m.box(x0, x1, y1, 0.5, 0, 0.03, material("plaza", "#a8a49c", rough=0.9))
    for x in (-0.38, 0.38):
        m.box(x - 0.05, x + 0.05, 0.35, 0.45, 0, 0.25, granite, 0.004)
        yard_tree(m, x, 0.4, 0.7, seed=18, kind="elm")
    for x in (-0.22, 0.22):
        m.box(x - 0.05, x + 0.05, 0.42, 0.44, 0.15, 0.2, material("bench wood", "#8a6a4a", rough=0.8))


def apartment_tower(m):
    """The 1960s slab apartment tower, 1960 to 2000: concrete, its balconies stacked up the front and back behind solid
    concrete fronts, a glass lobby under a canopy, an elevator penthouse on the roof and a surface parking lot beside it."""
    concrete = material("tower concrete", "#c8c2b4", rough=0.85)
    panel = material("balcony fronts", "#dcd6c8", rough=0.8)
    alu = material("lobby frames", "#9a9ea2", metal=0.6, rough=0.4)
    x0, x1, y0, y1 = -0.2, 0.2, -0.22, 0.2
    storeys = 13
    top = storeys * STOREY + 0.2
    m.box(x0, x1, y0, y1, 0, top, concrete, 0.003)
    windows(m, x0, x1, y0, y1, 0.1, storeys, 4, 4, glass(), concrete, skip=((0, 1), (0, 2)))
    # Balconies up the front and back: a slab, a solid front and ends, and a glass door behind.
    for s in range(1, storeys):
        z = s * STOREY + 0.05
        for y, d in ((y1, 1), (y0, -1)):
            for cx in (-0.1, 0.1):
                yo = y + d * 0.06
                m.box(cx - 0.07, cx + 0.07, min(y, yo), max(y, yo), z, z + 0.06, concrete)
                m.box(cx - 0.07, cx + 0.07, min(yo, yo - d * 0.008), max(yo, yo - d * 0.008), z + 0.06, z + 0.42, panel)
                for x in (cx - 0.07, cx + 0.066):
                    m.box(x, x + 0.004, min(y, yo), max(y, yo), z + 0.06, z + 0.42, panel)
                on_wall(m, "y", y, d, cx - 0.02, cx + 0.02, -0.002, 0.004, z + 0.06, z + 0.95, glass())
    flat_top(m, x0, x1, y0, y1, top, concrete, parapet=0.2)
    # The elevator penthouse, taller than the machine room beside it, louvred at the top.
    m.box(-0.06, 0.06, -0.05, 0.08, top, top + 1.1, concrete, 0.003)
    for face, at, d in (("y", 0.08, 1), ("y", -0.05, -1)):
        louvres(m, face, at, d, 0.0, top + 0.6, top + 0.95, 0.07, concrete, alu)
    m.box(0.06, 0.13, -0.05, 0.05, top, top + 0.55, concrete, 0.003)
    # The lobby: glass across the middle of the ground floor under a flat canopy on two posts.
    on_wall(m, "y", y1, 1, -0.1, 0.1, -0.002, 0.005, 0.05, 1.05, material("lobby glass", "#2c3a48", rough=0.15))
    for k in range(6):
        x = -0.1 + k * 0.04
        on_wall(m, "y", y1, 1, x - 0.003, x + 0.003, 0, 0.008, 0.05, 1.05, alu)
    on_wall(m, "y", y1, 1, -0.1, 0.1, 0, 0.008, 0.85, 0.89, alu)
    m.box(-0.13, 0.13, y1, y1 + 0.14, 1.12, 1.2, concrete)
    m.box(-0.132, 0.132, y1 + 0.136, y1 + 0.144, 1.08, 1.22, alu)
    for x in (-0.12, 0.12):
        m.box(x - 0.005, x + 0.005, y1 + 0.125, y1 + 0.135, 0, 1.12, alu)
    m.box(-0.12, 0.12, y1, 0.5, 0, 0.03, material("tower walk", "#a8a49a", rough=0.9))
    # The parking lot down one side with its drive out to the road, a lawn on the other side with trees.
    parking(m, 0.23, 0.48, -0.46, 0.3, 0.1, ((-0.4, "#7a2a2a"), (-0.2, "#3a5a7a"), (-0.1, "#c8c0a8"), (0.1, "#4a6a4a"), (0.2, "#8a8a90")))
    m.box(0.3, 0.44, 0.3, 0.5, 0, 0.025, material("parking asphalt", "#4e4e50", rough=0.95))
    m.box(-0.44, -0.28, -0.35, 0.35, 0, 0.03, material("lawn", "#4e7a3a", rough=1.0))
    yard_tree(m, -0.36, 0.3, 0.9, seed=10)
    yard_tree(m, -0.38, -0.32, 0.8, seed=12, kind="elm")


def glass_condo(m):
    """A glass condo tower, 2000 to 2100: floor-to-ceiling glass between white slab edges, glass balconies wrapping its
    corners, a two-storey podium of shops with a lobby canopy and a green roof terrace, and a glass screen round the
    mechanical floor at the top."""
    stone = material("podium stone", "#5e5a58", rough=0.7)
    tint = material("condo glass", "#4a7a8a", metal=0.45, rough=0.08, grime=0.1)
    white = material("slab edges", "#ecebe6", rough=0.6)
    rail = material("glass balustrade", "#a8ccd4", metal=0.15, rough=0.05, grime=0.0)
    steel = material("condo steel", "#3a3c3e", metal=0.6, rough=0.4)
    x0, x1, y0, y1 = -0.3, 0.3, -0.3, 0.26
    pod = 2 * STOREY
    m.box(x0, x1, y0, y1, 0, pod, stone, 0.003)
    for face, at, d, a0, a1 in (("y", y1, 1, x0, x1), ("x", x1, 1, y0, y1), ("x", x0, -1, y0, y1), ("y", y0, -1, x0, x1)):
        n = round((a1 - a0) / 0.12)
        for k in range(n):
            a = a0 + (k + 0.5) * (a1 - a0) / n
            on_wall(m, face, at, d, a - 0.05, a + 0.05, -0.002, 0.004, 0.06, 1.05, glass() if face == "y" and d == 1 else tint)
            on_wall(m, face, at, d, a - 0.05, a + 0.05, -0.002, 0.004, 1.4, 2.25, tint)
        on_wall(m, face, at, d, a0, a1, 0, 0.01, 1.12, 1.3, steel)
    for k, c in enumerate(("#c84a3a", "#e8c84a", "#3a6a8a", "#4a8a4a")):
        x = x0 + 0.075 + k * 0.15
        if abs(x) > 0.05:
            m.box(x - 0.04, x + 0.04, y1, y1 + 0.004, 1.15, 1.27, material("shop sign " + c, c, rough=0.6))
    m.box(-0.1, 0.1, y1, y1 + 0.12, 1.32, 1.37, rail)
    m.box(x0 - 0.006, x1 + 0.006, y0 - 0.006, y1 + 0.006, pod, pod + 0.08, white)
    # The green roof on the podium round the tower's foot, with planters and shrubs.
    m.box(x0 + 0.01, x1 - 0.01, y0 + 0.01, y1 - 0.01, pod + 0.08, pod + 0.12, material("green roof", "#5a7a3e", rough=1.0))
    shrub = scenery.leafy("terrace shrubs", "#3e6a2a", "#6a9a3e")
    for k, (x, y) in enumerate(((-0.26, 0.22), (0.26, 0.22), (-0.26, -0.26), (0.26, -0.26))):
        scenery.blob(m, x, y, pod + 0.2, 0.025, shrub, 60 + k, squash=0.8)
    for y in (y1 - 0.012, y0 + 0.012):
        m.box(x0 + 0.01, x1 - 0.01, y - 0.002, y + 0.002, pod + 0.08, pod + 0.4, rail)
    # The tower: glass floor to ceiling between white slab edges, glass balconies round all four corners.
    tx0, tx1, ty0, ty1 = -0.17, 0.17, -0.19, 0.16
    storeys = 7
    tz = pod + 0.1 + storeys * STOREY
    m.box(tx0, tx1, ty0, ty1, pod + 0.1, tz, tint, 0.002)
    for face, at, d, a0, a1 in (("y", ty1, 1, tx0, tx1), ("y", ty0, -1, tx0, tx1), ("x", tx1, 1, ty0, ty1), ("x", tx0, -1, ty0, ty1)):
        n = round((a1 - a0) / 0.05)
        for k in range(1, n):
            a = a0 + k * (a1 - a0) / n
            on_wall(m, face, at, d, a - 0.002, a + 0.002, 0, 0.005, pod + 0.1, tz, steel)
    for s in range(storeys + 1):
        z = pod + 0.1 + s * STOREY
        m.box(tx0 - 0.006, tx1 + 0.006, ty0 - 0.006, ty1 + 0.006, z - 0.06, z + 0.02, white)
        if 0 < s < storeys:
            for cx, sx in ((tx0, -1), (tx1, 1)):
                for cy, sy in ((ty0, -1), (ty1, 1)):
                    # An L of slab round the corner, 0.05 out, with glass along its outer edges.
                    ox, oy = cx + sx * 0.05, cy + sy * 0.05
                    ax, bx = sorted((cx - sx * 0.12, ox))
                    ay, by = sorted((cy - sy * 0.12, oy))
                    m.box(ax, bx, min(cy, oy), max(cy, oy), z - 0.06, z, white)
                    m.box(min(cx, ox), max(cx, ox), *sorted((cy - sy * 0.12, cy)), z - 0.06, z, white)
                    m.box(ax, bx, oy - 0.002, oy + 0.002, z, z + 0.4, rail)
                    m.box(ox - 0.002, ox + 0.002, ay, by, z, z + 0.4, rail)
    # The mechanical floor at the top, screened by glass fins.
    m.box(tx0, tx1, ty0, ty1, tz, tz + 0.04, material("tar roof", "#3a3a3c", rough=0.95))
    m.box(tx0 + 0.04, tx1 - 0.04, ty0 + 0.04, ty1 - 0.04, tz, tz + 0.7, material("mechanical floor", "#a8aaa8", rough=0.7), 0.003)
    for face, at, d, a0, a1 in (("y", ty1, 1, tx0, tx1), ("y", ty0, -1, tx0, tx1), ("x", tx1, 1, ty0, ty1), ("x", tx0, -1, ty0, ty1)):
        n = round((a1 - a0) / 0.03)
        for k in range(n + 1):
            a = min(max(a0 + k * (a1 - a0) / n, a0 + 0.003), a1 - 0.003)
            on_wall(m, face, at, d, a - 0.003, a + 0.003, -0.012, 0.0, tz, tz + 0.85, rail)
    # The street: a wide sidewalk with trees in grates and the garage ramp down at the side.
    m.box(-0.5, 0.5, y1, 0.5, 0, 0.03, material("sidewalk", "#b0aca2", rough=0.9))
    for x in (-0.38, 0.38):
        m.box(x - 0.03, x + 0.03, 0.37, 0.43, 0.03, 0.035, steel)
        yard_tree(m, x, 0.4, 0.7, seed=19, kind="elm")
    m.box(x1 + 0.02, 0.46, -0.1, 0.5, 0, 0.025, material("ramp asphalt", "#4e4e50", rough=0.95))
    m.box(x1, x1 + 0.004, -0.08, 0.04, 0, 0.6, material("garage door", "#2a2a2c", metal=0.4, rough=0.5))


def statue(m):
    """A company's statue in a small square: a bronze figure on a granite plinth with a plaque facing the road, paving,
    flower beds, benches and shrubs."""
    granite = material("plinth granite", "#8e8a84", rough=0.8)
    bronze = material("statue bronze", "#5e7a62", metal=0.7, rough=0.45, grime=0.6)
    paved(m, -0.42, 0.42, -0.42, 0.42, "#b4ada0")
    path(m, 0.0, 0.42, 0.5, 0.08)
    # The plinth in three steps, and the plaque on its front.
    m.box(-0.08, 0.08, -0.08, 0.08, 0.02, 0.25, granite, 0.004)
    m.box(-0.055, 0.055, -0.055, 0.055, 0.25, 1.05, granite, 0.003)
    m.box(-0.068, 0.068, -0.068, 0.068, 1.05, 1.15, granite, 0.003)
    m.box(-0.03, 0.03, 0.055, 0.059, 0.55, 0.85, bronze)
    # The figure: a long coat, shoulders and head, one arm out towards the town.
    m.lathe(0.0, 0.0, 1.15, [(0.042, 0.0), (0.042, 0.12), (0.036, 0.9), (0.03, 1.35), (0.036, 1.45), (0.016, 1.52), (0.022, 1.57),
                              (0.022, 1.76), (0.0, 1.84)], bronze)
    m.box(0.025, 0.1, -0.011, 0.011, 2.5, 2.58, bronze)
    m.box(-0.044, -0.026, -0.011, 0.011, 1.85, 2.6, bronze)
    # Flower beds at the corners, benches facing it from either side, and low shrubs at the back, which keep out of the
    # figure's way from every side.
    for (x0, x1, y0, y1) in ((-0.38, -0.22, 0.22, 0.38), (0.22, 0.38, 0.22, 0.38)):
        flowers(m, x0, x1, y0, y1)
    wood = material("bench slats", "#7a5a3a", rough=0.8)
    iron = material("bench iron", "#2a2a2a", metal=0.5, rough=0.6)
    for x in (-0.25, 0.25):
        m.box(x - 0.025, x + 0.025, -0.1, 0.1, 0.2, 0.25, wood)
        m.box(x - 0.008 + (0.02 if x > 0 else -0.02), x + 0.008 + (0.02 if x > 0 else -0.02), -0.1, 0.1, 0.25, 0.5, wood)
        for y in (-0.09, 0.09):
            m.box(x - 0.02, x + 0.02, y - 0.004, y + 0.004, 0.02, 0.2, iron)
    shrub = scenery.leafy("square shrubs", "#3e6a2a", "#6a9a3e")
    for k, (x, y) in enumerate(((-0.34, -0.34), (-0.22, -0.36), (0.34, -0.34), (0.22, -0.36))):
        scenery.blob(m, x, y, 0.25, 0.06, shrub, 30 + k)


BUILDERS = {
    "log_cabin": log_cabin,
    "farmhouse": farmhouse,
    "clapboard_house": clapboard_house,
    "general_store": general_store,
    "church": church,
    "brick_row": brick_row,
    "brick_block": brick_block,
    "bungalow": bungalow,
    "walkup": walkup,
    "split_level": split_level,
    "office_block": office_block,
    "apartment_tower": apartment_tower,
    "statue": statue,
    "wartime_house": wartime_house,
    "ranch_bungalow": ranch_bungalow,
    "stone_church": stone_church,
    "modern_church": modern_church,
    "stone_office": stone_office,
    "glass_office": glass_office,
    "glass_condo": glass_condo,
}

# Which way each view edge is, as a heading in eighths for place(): the front faces +y, the game's +u.
FACING = {"XP": 0, "YP": 2, "XM": 4, "YM": 6}


def house(m, kind):
    BUILDERS[kind](m)
