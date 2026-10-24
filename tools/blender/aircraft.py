"""Aircraft flown in Canada, in our own colours: built facing +y, L tiles long, x across, z in steps from the ground.

Fuselages are lofted through round sections that narrow to a rounded nose and sweep up into the tail. Wings and tail
surfaces are thin tapered slabs, swept as the type was. Bush planes sit on floats, as they're seen on every northern lake.
"""
import math

import rig
from parts import material
from steam import LETTERS


def common():
    # A spinning prop or tail rotor shows what's behind it.
    blur = material("prop blur", "#8a8e92", rough=0.6)
    blur.node_tree.nodes["Principled BSDF"].inputs["Alpha"].default_value = 0.4
    return {
        "blur": blur,
        "glass": material("cockpit glass", "#1e2a36", rough=0.1),
        "dark": material("air dark", "#26282a", rough=0.6),
        "metal": material("bare metal", "#b8bcc0", metal=0.8, rough=0.3),
        "tire": material("air tire", "#1e1e20", rough=0.85),
        "prop": material("prop disc", "#8a8e92", rough=0.6),
        "white": material("air white", "#f0f0ec", rough=0.35, clearcoat=0.3),
        "grey": material("belly grey", "#a8acb0", metal=0.4, rough=0.4),
    }


# Our airline: white, a navy cheatline and a teal tail; bush planes yellow with a red trim; freighters grey; helicopters red.
LIVERIES = {
    "airline": ("#f0f0ec", "#1e3a6a", "#1e7a8a"),
    "classic": ("#c8ccd0", "#1e3a6a", "#1e3a6a"),
    "bush": ("#e8b020", "#b8322a", "#e8b020"),
    "freight": ("#c8ccd0", "#3a3d40", "#c87a2a"),
    "heli": ("#c8302a", "#ecebe6", "#c8302a"),
}


def fuselage(m, L, r, zc, body, belly, nose=0.16, tail=0.32, lift=0.6, hump=0.0, square=False):
    """A fuselage L long and r round (tiles), its middle zc steps up: a rounded nose over the front nose share of its
    length, the tail cone over the last tail share, its underside lifting by lift of r. hump raises the front top, as a
    747's upper deck does."""
    a0, a1 = -L / 2, L / 2
    n = 22
    pts = 14
    sections = []
    for k in range(n + 1):
        t = k / n
        y = a0 + L * t
        if t > 1 - nose:
            s = (t - (1 - nose)) / nose
            rr = r * math.sqrt(max(0.0, 1 - s ** 2.2)) if k < n else r * 0.05
            lift_here = 0.0
            drop = -0.25 * r * s
        elif t < tail:
            s = (tail - t) / tail
            rr = r * (1 - 0.8 * s ** 1.3)
            lift_here = lift * r * s ** 1.1
            drop = 0.0
        else:
            rr = r
            lift_here = 0.0
            drop = 0.0
        hump_here = hump * r * max(0.0, 1 - abs(t - 0.78) / 0.14) if hump else 0.0
        ring = []
        for j in range(pts):
            a = 2 * math.pi * j / pts
            sx = math.cos(a)
            sz = math.sin(a)
            if square:
                sx = max(-1.0, min(1.0, sx * 1.25))
                sz = max(-1.0, min(1.0, sz * 1.25))
            top_extra = hump_here if sz > 0 else 0.0
            ring.append((rr * sx, zc + (rr * sz + top_extra * sz + lift_here + drop) / rig.RISE))
        sections.append((y, ring))
    # The top two thirds in the body colour, the belly below.
    m.loft(sections, {"body": body, "belly": belly}, part=lambda k: "belly" if pts * 0.58 < k < pts * 0.92 else "body", sharp=60, round=True)


def wing(m, y, z, half_span, root, tip, sweep, mat, x0=0.0, dihedral=0.0, thick=0.08, side=0):
    """A pair of wings (or one, on side -1 or 1) from x0 out to half_span: chord root at the root, tip at the tip, the tip's
    leading edge sweep tiles back, rising dihedral steps by the tip; y is the root's middle."""
    for s in ((-1, 1) if side == 0 else (side,)):
        lead_r = y + root / 2
        lead_t = y + root / 2 - sweep
        bottom = [(s * x0, lead_r, z), (s * half_span, lead_t, z + dihedral), (s * half_span, lead_t - tip, z + dihedral), (s * x0, lead_r - root, z)]
        top = [(px, py, pz + thick) for px, py, pz in bottom]
        if s < 0:
            bottom = bottom[::-1]
            top = top[::-1]
        m.wedge(bottom, top, mat)


def fin(m, y, z, height, root, tip, sweep, mat, x=0.0, thick=0.006):
    """A fin standing at x on the fuselage at y, height steps tall, its chord root at the bottom and tip at the top."""
    lead_r = y + root / 2
    lead_t = lead_r - sweep
    pts = [(lead_r, z), (lead_t, z + height), (lead_t - tip, z + height), (lead_r - root, z)]
    m.wedge([(x - thick, py, pz) for py, pz in pts], [(x + thick, py, pz) for py, pz in pts], mat)


def nacelle(m, x, y0, y1, z, r, mat, c, prop=False, jet=False):
    """An engine along y from y0 (back) to y1 (front) at x and z steps: a prop disc in front, or a dark intake."""
    m.tube(y0, y1, x, z, r, mat, segments=16)
    if prop:
        m.tube(y1, y1 + 0.004, x, z, r * 0.45, c["dark"], segments=12)
        m.tube(y1 + 0.004, y1 + 0.006, x, z, r * 1.7, c["prop"], segments=24)
    if jet:
        m.tube(y1 - 0.002, y1 + 0.001, x, z, r * 0.8, c["dark"], segments=16)


def wheels(m, ys, z, xs, c, r=0.012):
    for y in ys:
        for x in xs:
            with m.keep():
                m.tube_x(x - 0.004, x + 0.004, y, r / rig.RISE, r, c["tire"], segments=14)
            m.rod((x, y, m.fixed(r / rig.RISE)), (x * 0.8, y, z), 0.0025, c["metal"])


def airliner(m, L, wing_pos="low", engines="jet_wing2", tail="normal", span=1.0, sweep=0.0, livery="airline", windows=True,
             r=None, hump=0.0, gear="nose", cargo_door=False, struts=False):
    """An aircraft by its layout: where its wing is, its engines (radial1, radial2, radial4, turboprop1, turboprop2,
    turboprop4, jet_wing2, jet_wing4, jet_tail2), its tail (normal, t or twin), span as a share of its length, how far
    the wingtips sweep back, its livery and gear."""
    c = common()
    body_c, line_c, tail_c = LIVERIES[livery]
    body = material(f"{livery} body", body_c, rough=0.35, clearcoat=0.3, metal=0.3 if livery in ("classic", "freight") else 0.0)
    line = material(f"{livery} line", line_c, rough=0.4)
    tail_m = material(f"{livery} tail", tail_c, rough=0.4, clearcoat=0.2)
    r = r or L * 0.055
    # The belly sits just over the wheels.
    gear_h = 0.17
    zc = gear_h + r / rig.RISE
    fuselage(m, L, r, zc, body, c["grey"] if livery != "bush" else body, hump=hump)
    # How much the fuselage shrank across, in a lowered model.
    rx = r * m.k(zc)
    a0, a1 = -L / 2, L / 2
    # Cockpit glass, a cheatline and a row of cabin windows, each set into the round side at its own height: f of r up
    # the side sits sqrt(1 - f^2) of r out.
    def out(f):
        return rx * math.sqrt(1 - f * f)

    nose_r = r * 0.85
    m.box(-rx * 0.85 * 0.75, rx * 0.85 * 0.75, a1 - L * 0.1, a1 - L * 0.065, zc + nose_r * 0.2 / rig.RISE, zc + nose_r * 0.7 / rig.RISE, c["glass"])
    for s in (-1, 1):
        m.box(s * out(0.12) - 0.0008, s * rx + 0.0004 * s, a0 + L * 0.3, a1 - L * 0.12, zc - r * 0.12 / rig.RISE, zc + r * 0.06 / rig.RISE, line)
        if windows:
            y = a0 + L * 0.32
            while y + 0.008 < a1 - L * 0.16:
                m.box(s * out(0.34) - 0.0006, s * out(0.18) + 0.0004 * s, y, y + 0.007, zc + r * 0.18 / rig.RISE, zc + r * 0.32 / rig.RISE, c["glass"])
                y += 0.015
        if cargo_door:
            m.box(s * out(0.4) - 0.0006, s * rx + 0.0004 * s, a1 - L * 0.32, a1 - L * 0.2, zc - r * 0.4 / rig.RISE, zc + r * 0.4 / rig.RISE, line)
    if livery != "bush":
        m.text(LETTERS, out(0.45) + 0.001, 0, zc - r * 0.45 / rig.RISE, L * 0.05, line, 1)
    half = L * 0.5 * span
    wy = 0.02 * L if wing_pos == "low" else 0.05 * L
    wz = zc - r * 0.75 / rig.RISE if wing_pos == "low" else zc + r * 0.8 / rig.RISE
    chord = L * (0.16 if sweep < 0.05 else 0.2)
    wing(m, wy, wz, half, chord, chord * 0.42, sweep, body, x0=rx * 0.6, dihedral=0.25 if wing_pos == "low" else 0.0, thick=0.07)
    if struts:
        for s in (-1, 1):
            m.rod((s * half * 0.5, wy, wz), (s * rx, wy, zc - r * 0.8 / rig.RISE), 0.003, c["metal"])
    # Engines.
    n = int(engines[-1])
    eng = material("engine", body_c if livery != "bush" else "#3a3a3c", metal=0.5, rough=0.35)
    if engines.startswith("radial") or engines.startswith("turboprop"):
        er = r * (0.55 if engines.startswith("radial") else 0.42)
        if n == 1:
            nacelle(m, 0, a1 - L * 0.05, a1 + 0.004, zc, r * 0.9, eng, c, prop=True)
        else:
            out = [0.32] if n == 2 else [0.26, 0.52]
            for f in out:
                for s in (-1, 1):
                    x = s * half * f
                    nacelle(m, x, wy - chord * 0.7, wy + chord * 0.7, wz + (0.0 if wing_pos == "low" else -0.1), er, eng, c, prop=True)
    elif engines.startswith("jet_wing"):
        er = r * (0.42 if n == 2 else 0.33)
        out = [0.34] if n == 2 else [0.3, 0.6]
        for f in out:
            for s in (-1, 1):
                x = s * half * f
                y = wy - sweep * f
                nacelle(m, x, y - chord * 0.2, y + chord * 0.55, wz - er / rig.RISE * 1.1, er, eng, c, jet=True)
                m.box(x - 0.002, x + 0.002, y, y + chord * 0.4, wz - er / rig.RISE * 0.4, wz, eng)
    elif engines == "jet_tail2":
        er = r * 0.45
        for s in (-1, 1):
            nacelle(m, s * rx * 1.5, a0 + L * 0.12, a0 + L * 0.28, zc + r * 0.3 / rig.RISE, er, eng, c, jet=True)
            m.box(s * rx * 0.9, s * rx * 1.3, a0 + L * 0.16, a0 + L * 0.24, zc + 0.15, zc + 0.22, eng)
    # Tail.
    ty = a0 + L * 0.1
    tz = zc + r * 0.55 / rig.RISE
    fin_h = L * 0.6 / rig.RISE * 0.32
    tail_sweep = 0.02 + sweep * 0.8
    if tail == "twin":
        wing(m, ty, tz, L * 0.17, L * 0.1, L * 0.07, 0.0, body, thick=0.04)
        for s in (-1, 1):
            fin(m, ty, tz - fin_h * 0.25, fin_h * 0.8, L * 0.08, L * 0.06, 0.005, tail_m, x=s * L * 0.17)
    else:
        fin(m, ty + 0.01, tz, fin_h, L * 0.16, L * 0.08, tail_sweep + L * 0.04, tail_m)
        if tail == "t":
            wing(m, ty - tail_sweep - L * 0.02, tz + fin_h, L * 0.16, L * 0.08, L * 0.05, 0.01, tail_m, thick=0.04)
        else:
            wing(m, ty, tz - 0.05, L * 0.18 * (1 + span * 0.1), L * 0.11, L * 0.06, tail_sweep, body, thick=0.04)
    # Gear.
    mains = [wy - chord * 0.2]
    wheels(m, mains, zc - r / rig.RISE, (-rx * 1.6, rx * 1.6) if wing_pos == "low" else (-rx * 1.2, rx * 1.2), c)
    if gear == "nose":
        wheels(m, [a1 - L * 0.12], zc - r / rig.RISE, (0.0,), c, r=0.009)
    else:
        wheels(m, [a0 + L * 0.08], zc - r / rig.RISE + 0.1, (0.0,), c, r=0.007)


# Helicopters and bush planes are drawn from their real measures in metres, a little taller than real as the fixed-wing
# aircraft are, and held at that size through keep() so the height by kind doesn't squash them again.
EX = 1.15


class Real:
    """Metres on a type L tiles long and length metres long: along and across in tiles, heights in steps."""

    def __init__(self, L, length):
        self.s = L / length
        self.nose = L / 2

    def y(self, metres):
        """Where a point metres back from the nose sits along the model."""
        return self.nose - metres * self.s

    def w(self, metres):
        return metres * self.s

    def h(self, metres):
        return metres * self.s * EX / rig.RISE


def pod(m, R, stations, mats, part=None, x=0.0, n=16):
    """A body lofted through stations, each (metres back from the nose, half width, bottom, top in metres, and optionally
    how square: 2 is an ellipse, 4 or more nearly a box). Ring point k runs from the right side's middle up over the top."""
    sections = []
    for st in stations:
        y, hw, zb, zt = st[:4]
        sq = st[4] if len(st) > 4 else 2.0
        zc, hh = (zb + zt) / 2, (zt - zb) / 2
        ring = []
        for j in range(n):
            a = 2 * math.pi * j / n
            ca, sa = math.cos(a), math.sin(a)
            ex = math.copysign(abs(ca) ** (2 / sq), ca)
            ez = math.copysign(abs(sa) ** (2 / sq), sa)
            ring.append((x + R.w(hw * ex), R.h(zc + hh * ez)))
        sections.append((R.y(y), ring))
    m.loft(sections, mats, part=part, sharp=60)


def upper(lo, hi):
    """A part() naming glass for the ring's faces lo to hi over the top, the body elsewhere."""
    return lambda k: "glass" if lo <= k < hi else "body"


def rotor(m, R, y, z, radius, blades, chord, mat, hub):
    """A main rotor of blades radius metres long, its hub at y metres back and z metres up, on a mast."""
    cy = R.y(y)
    zt = R.h(z)
    t = max(R.h(0.12), 0.012)
    half = max(R.w(chord), 0.006) / 2
    rr = R.w(radius)
    for k in range(blades):
        a = 2 * math.pi * k / blades + math.pi / 8
        dx, dy = math.cos(a), math.sin(a)
        # Each blade runs out from the hub, half its chord either side of its line.
        px, py = -dy * half, dx * half
        pts = [(px, cy + py), (dx * rr + px, cy + dy * rr + py), (dx * rr - px, cy + dy * rr - py), (-px, cy - py)]
        m.wedge([(a_, b_, zt) for a_, b_ in pts], [(a_, b_, zt + t) for a_, b_ in pts], mat)
    m.upright(0, cy, zt - 0.02, zt + t + 0.012, max(R.w(0.22), 0.004), max(R.w(0.16), 0.003), hub, segments=12)


def mast(m, R, y, z0, z1, mat):
    m.upright(0, R.y(y), R.h(z0), R.h(z1), max(R.w(0.13), 0.0028), max(R.w(0.1), 0.0024), mat, segments=10)


def tail_rotor(m, R, y, z, dia, side, c):
    """A tail rotor disc dia metres across on the side (-1 left, 1 right) of the boom, its hub at y back and z up."""
    x = side * R.w(0.32)
    m.tube_x(x - 0.0015, x + 0.0015, R.y(y), R.h(z), R.w(dia / 2), c["blur"], segments=20)
    m.tube_x(min(0, x), max(0, x), R.y(y), R.h(z), max(R.w(0.08), 0.002), c["dark"], segments=8)


def skids(m, R, front, back, track, z_body, cross, c):
    """A pair of skid tubes from front to back metres, track metres apart, turned up at the front, on cross tubes at the
    cross positions up to the belly at z_body metres."""
    zs = R.h(0.08)
    tube = 0.0028
    for s in (-1, 1):
        x = s * R.w(track / 2)
        m.tube(R.y(back), R.y(front), x, zs, tube, c["metal"], segments=8)
        m.rod((x, R.y(front) - 0.001, zs), (x, R.y(front - 0.4), R.h(0.35)), tube, c["metal"])
        for y in cross:
            m.rod((x, R.y(y), zs), (x * 0.45, R.y(y), R.h(z_body)), 0.0024, c["metal"])
    for y in cross:
        m.rod((-R.w(track / 2) * 0.45, R.y(y), R.h(z_body)), (R.w(track / 2) * 0.45, R.y(y), R.h(z_body)), 0.002, c["metal"])


def wheel(m, R, y, x, r, c, z_body, x_body=None, w=0.007):
    """A wheel r metres round at y back and x across, its leg up to the body at z_body metres, at x_body across."""
    yy, rr = R.y(y), max(R.w(r), 0.006)
    m.tube_x(R.w(x) - w / 2, R.w(x) + w / 2, yy, rr / rig.RISE, rr, c["tire"], segments=14)
    xb = R.w(x if x_body is None else x_body)
    m.rod((R.w(x), yy, rr / rig.RISE), (xb, yy, R.h(z_body)), 0.0022, c["metal"])


def heli_window(m, R, y0, y1, z0, z1, hw, c, side=None):
    """Glass on the flat of a cabin side hw metres out, y0 to y1 back and z0 to z1 up."""
    for s in ((-1, 1) if side is None else (side,)):
        x = s * R.w(hw)
        m.box(min(x, x + s * 0.0012), max(x, x + s * 0.0012), R.y(y1), R.y(y0), R.h(z0), R.h(z1), c["glass"])


def helicopter(m, L, kind):
    """A helicopter: a Bell 47's clear bubble, open engine and lattice boom; an S-55's tall nose with the cockpit
    perched over it and a drooping boom; a JetRanger's teardrop cabin; or an S-92's long cabin with sponsons."""
    with m.keep():
        _helicopter(m, L, kind)


def _helicopter(m, L, kind):
    c = common()
    body_c, trim_c, _ = LIVERIES["heli"]
    body = material(f"{kind} heli body", body_c, rough=0.35, clearcoat=0.3)
    trim = material(f"{kind} heli trim", trim_c, rough=0.4)
    blade = material("rotor blade", "#2a2a2c", rough=0.5)
    paint = {"body": body, "glass": c["glass"]}
    if kind == "bell47":
        R = Real(L, 9.6)
        bubble = material("bubble", "#9ab4c4", rough=0.05)
        bubble.node_tree.nodes["Principled BSDF"].inputs["Alpha"].default_value = 0.35
        # The bubble and the floor pan under it; the seat and console show through.
        pod(m, R, [(0.0, 0.15, 1.25, 1.55), (0.25, 0.62, 0.8, 2.0), (0.7, 0.9, 0.7, 2.2), (1.3, 0.95, 0.7, 2.25),
                   (1.9, 0.85, 0.75, 2.1), (2.15, 0.45, 0.9, 1.8)], {"g": bubble})
        pod(m, R, [(0.15, 0.4, 0.55, 0.85), (0.6, 0.85, 0.48, 0.9), (2.0, 0.85, 0.48, 0.9), (2.3, 0.5, 0.55, 0.85)], {"b": body}, n=12)
        m.box(-R.w(0.75), R.w(0.75), R.y(2.0), R.y(1.45), R.h(0.85), R.h(1.4), c["dark"])
        m.box(-R.w(0.2), R.w(0.2), R.y(0.75), R.y(0.45), R.h(0.85), R.h(1.25), c["dark"])
        # The open engine with a saddle tank either side of the mast.
        m.box(-R.w(0.4), R.w(0.4), R.y(3.2), R.y(2.1), R.h(0.6), R.h(1.45), c["dark"])
        m.box(-R.w(0.28), R.w(0.28), R.y(3.0), R.y(2.3), R.h(0.45), R.h(1.6), c["metal"])
        for s in (-1, 1):
            pod(m, R, [(2.0, 0.05, 1.5, 1.6), (2.15, 0.3, 1.25, 1.85), (2.95, 0.3, 1.25, 1.85), (3.1, 0.05, 1.5, 1.6)], {"b": body}, x=s * R.w(0.6), n=12)
        m.upright(0, R.y(2.3), R.h(1.4), R.h(2.2), R.w(0.28), R.w(0.18), c["metal"], segments=12)
        mast(m, R, 2.3, 2.1, 2.75, c["metal"])
        rotor(m, R, 2.3, 2.75, 5.66, 2, 0.3, blade, c["dark"])
        # The welded lattice boom: a top tube, two bottom ones and zig-zag bracing.
        rails = []
        for side, z0, z1 in ((0, 1.75, 1.55), (-1, 1.0, 1.28), (1, 1.0, 1.28)):
            a = (side * R.w(0.4), R.y(3.0), R.h(z0))
            b = (side * R.w(0.12), R.y(9.0), R.h(z1))
            m.rod(a, b, 0.0024, body)
            rails.append((a, b))

        def at(rail, t):
            a, b = rail
            return tuple(p + (q - p) * t for p, q in zip(a, b))
        steps = 9
        for k in range(steps):
            t0, t1 = k / steps, (k + 1) / steps
            m.rod(at(rails[0], t0), at(rails[1 + k % 2], t1), 0.0015, body)
            m.rod(at(rails[1], t0), at(rails[2], t0), 0.0015, body)
            m.rod(at(rails[2 - k % 2], t0), at(rails[0], t1), 0.0015, body)
        wing(m, R.y(6.8), R.h(1.4), R.w(0.9), R.w(0.45), R.w(0.4), 0.0, body, thick=R.h(0.08))
        m.box(-0.002, 0.002, R.y(9.4), R.y(8.9), R.h(1.2), R.h(1.6), c["metal"])
        m.rod((0, R.y(9.2), R.h(1.25)), (0, R.y(9.0), R.h(0.45)), 0.0018, c["metal"])
        tail_rotor(m, R, 9.25, 1.45, 1.7, -1, c)
        skids(m, R, 0.1, 3.3, 2.3, 0.55, (0.8, 2.5), c)
    elif kind == "jetranger":
        R = Real(L, 9.5)
        pod(m, R, [(0.0, 0.08, 0.98, 1.12), (0.25, 0.42, 0.72, 1.5), (0.7, 0.6, 0.63, 1.8), (1.3, 0.66, 0.6, 1.93)], paint, part=upper(1, 7))
        pod(m, R, [(1.3, 0.66, 0.6, 1.93, 2.4), (2.2, 0.66, 0.6, 1.98, 2.4), (2.9, 0.63, 0.65, 1.98, 2.4), (3.6, 0.5, 0.85, 1.88),
                   (4.2, 0.3, 1.15, 1.78), (4.6, 0.2, 1.3, 1.68), (9.2, 0.11, 1.45, 1.66)], {"b": body})
        pod(m, R, [(2.0, 0.05, 1.85, 1.95), (2.3, 0.36, 1.82, 2.36), (3.5, 0.38, 1.8, 2.42), (4.3, 0.2, 1.7, 2.12), (4.7, 0.05, 1.75, 1.85)], {"b": body})
        m.box(-R.w(0.12), R.w(0.12), R.y(4.75), R.y(4.4), R.h(1.85), R.h(2.05), c["dark"])
        heli_window(m, R, 1.4, 2.6, 1.2, 1.75, 0.66, c)
        for s in (-1, 1):
            m.box(s * R.w(0.66) - 0.001, s * R.w(0.66) + 0.001, R.y(4.0), R.y(0.6), R.h(0.95), R.h(1.08), trim)
        mast(m, R, 2.6, 2.3, 2.85, c["metal"])
        rotor(m, R, 2.6, 2.85, 5.08, 2, 0.33, blade, c["dark"])
        wing(m, R.y(6.8), R.h(1.5), R.w(1.0), R.w(0.5), R.w(0.4), 0.0, body, thick=R.h(0.08))
        fin(m, R.y(8.75), R.h(1.62), R.h(0.95), R.w(0.9), R.w(0.5), R.w(0.5), body)
        fin(m, R.y(8.85), R.h(1.0), R.h(0.48), R.w(0.45), R.w(0.6), -R.w(0.2), body)
        tail_rotor(m, R, 9.1, 1.75, 1.65, -1, c)
        skids(m, R, 0.7, 3.7, 1.92, 0.62, (1.3, 3.0), c)
    elif kind == "s55":
        R = Real(L, 12.85)
        pod(m, R, [(0.0, 0.45, 1.05, 2.0, 2.4), (0.3, 0.75, 0.8, 2.35, 2.4), (1.0, 0.9, 0.72, 2.55, 2.6), (2.0, 0.92, 0.7, 2.6, 2.6),
                   (4.6, 0.92, 0.7, 2.6, 2.6), (5.6, 0.8, 0.85, 2.55), (6.6, 0.55, 1.25, 2.4), (7.5, 0.35, 1.5, 2.25), (11.8, 0.2, 1.45, 1.9),
                   (12.6, 0.12, 1.6, 1.9)], {"b": body})
        # The cockpit perched over the nose engine, then the fairing back to the mast.
        pod(m, R, [(1.1, 0.1, 2.4, 2.5), (1.4, 0.6, 2.35, 3.15), (2.0, 0.75, 2.35, 3.42), (2.8, 0.75, 2.4, 3.45)], paint, part=upper(1, 7))
        pod(m, R, [(2.8, 0.75, 2.4, 3.45), (3.4, 0.68, 2.45, 3.35), (4.6, 0.55, 2.45, 3.15), (5.8, 0.15, 2.45, 2.65)], {"b": body})
        # The clamshell doors' seam, a white band, and the cabin windows.
        m.box(-0.0012, 0.0012, R.y(0.12), R.y(-0.01), R.h(1.15), R.h(2.2), c["dark"])
        for s in (-1, 1):
            m.box(s * R.w(0.92) - 0.001, s * R.w(0.92) + 0.001, R.y(7.0), R.y(0.6), R.h(1.25), R.h(1.4), trim)
        heli_window(m, R, 2.7, 3.4, 1.7, 2.25, 0.92, c)
        heli_window(m, R, 3.8, 4.5, 1.7, 2.25, 0.92, c)
        mast(m, R, 3.9, 3.2, 3.85, c["metal"])
        rotor(m, R, 3.9, 3.85, 8.07, 3, 0.42, blade, c["dark"])
        fin(m, R.y(12.3), R.h(1.8), R.h(0.6), R.w(0.6), R.w(0.4), R.w(0.15), body)
        tail_rotor(m, R, 12.6, 2.1, 2.6, -1, c)
        # Two nose wheels under the engine, two mains on outriggers, each clear of the belly.
        for s in (-1, 1):
            wheel(m, R, 1.3, s * 0.75, 0.3, c, 0.72, s * 0.6)
            wheel(m, R, 5.2, s * 1.7, 0.33, c, 1.0, s * 0.85)
            m.rod((s * R.w(1.7), R.y(5.2), R.h(0.33)), (s * R.w(0.7), R.y(4.6), R.h(0.75)), 0.002, c["metal"])
    else:
        R = Real(L, 17.1)
        pod(m, R, [(0.0, 0.15, 1.15, 1.4), (0.4, 0.6, 0.85, 1.8), (1.2, 0.95, 0.68, 2.2, 2.4)], {"b": body})
        pod(m, R, [(1.2, 0.95, 0.68, 2.2, 2.4), (2.6, 1.15, 0.6, 2.6, 3.0)], paint, part=upper(1, 7))
        pod(m, R, [(2.6, 1.15, 0.6, 2.6, 3.0), (9.0, 1.15, 0.6, 2.6, 3.0), (10.2, 0.9, 1.1, 2.55, 2.6), (11.0, 0.6, 1.6, 2.5),
                   (16.4, 0.35, 2.15, 2.75)], {"b": body})
        pod(m, R, [(2.6, 0.2, 2.5, 2.6), (3.5, 0.85, 2.5, 3.2, 3.0), (8.5, 0.9, 2.5, 3.25, 3.0), (10.0, 0.5, 2.45, 2.95), (10.8, 0.1, 2.45, 2.6)], {"b": body})
        for s in (-1, 1):
            m.box(s * R.w(0.9), s * R.w(0.95), R.y(8.4), R.y(7.8), R.h(2.85), R.h(3.1), c["dark"])
            pod(m, R, [(4.6, 0.05, 0.85, 1.05), (5.2, 0.38, 0.75, 1.35), (7.6, 0.38, 0.75, 1.35), (8.4, 0.05, 0.9, 1.15)], {"b": body}, x=s * R.w(1.35), n=12)
            m.box(s * R.w(1.15) - 0.001, s * R.w(1.15) + 0.001, R.y(10.0), R.y(1.4), R.h(1.4), R.h(1.55), trim)
        for k in range(5):
            y = 3.4 + k * 1.15
            heli_window(m, R, y, y + 0.65, 1.7, 2.2, 1.15, c)
        mast(m, R, 6.0, 3.2, 3.9, c["metal"])
        rotor(m, R, 6.0, 3.9, 8.6, 4, 0.53, blade, c["dark"])
        fin(m, R.y(16.2), R.h(2.6), R.h(2.0), R.w(1.6), R.w(1.0), R.w(0.9), body)
        wing(m, R.y(16.6), R.h(4.2), R.w(1.6), R.w(0.6), R.w(0.45), 0.0, body, thick=R.h(0.1), side=-1)
        tail_rotor(m, R, 16.9, 4.0, 3.35, 1, c)
        for s in (-1, 1):
            wheel(m, R, 2.3, s * 0.2, 0.3, c, 0.68, s * 0.2)
            wheel(m, R, 6.6, s * 1.35, 0.38, c, 0.75)


# Bush planes on floats, in metres: length, span, wing chord; nose length and cowl radius; the cabin's start, end, half
# width, bottom and top; the tail cone's half width, bottom and top; the fin's height, root and tip chord; the
# tailplane's half span and chord; struts (one or v); windows a side; floats' length, depth and half spread; how square.
BUSH = {
    "fairchild": dict(length=9.9, span=15.24, chord=1.9, nose=0.9, cowl=0.6, engine="open", cabin=(1.2, 4.3, 0.58, 1.8, 3.35),
                      tailcone=(0.08, 2.9, 3.2), fin=(1.5, 1.5, 1.25), tailplane=(2.3, 1.1), struts="v", windows=2,
                      floats=(7.6, 0.8, 1.35), sq=6.0, prop=1.3),
    "norseman": dict(length=9.75, span=15.7, chord=1.9, nose=2.0, cowl=0.68, engine="cowl", cabin=(2.2, 4.6, 0.62, 1.75, 3.35),
                     tailcone=(0.07, 2.85, 3.15), fin=(1.4, 1.5, 1.0), tailplane=(2.4, 1.1), struts="v", windows=3,
                     floats=(7.6, 0.8, 1.4), sq=4.0, prop=1.37),
    "beaver": dict(length=9.24, span=14.63, chord=1.59, nose=1.3, cowl=0.66, engine="cowl", cabin=(1.6, 4.3, 0.62, 1.75, 3.2),
                   tailcone=(0.1, 2.8, 3.1), fin=(1.5, 1.4, 1.1), tailplane=(2.5, 1.0), struts="one", windows=2,
                   floats=(7.4, 0.8, 1.35), sq=3.5, prop=1.3),
    "otter": dict(length=12.75, span=17.69, chord=2.03, nose=1.5, cowl=0.72, engine="cowl", cabin=(1.9, 7.0, 0.78, 1.8, 3.5),
                  tailcone=(0.1, 3.0, 3.35), fin=(1.7, 1.8, 1.3), tailplane=(3.0, 1.2), struts="one", windows=6,
                  floats=(9.8, 0.95, 1.6), sq=3.5, prop=1.6),
    "twin_otter": dict(length=15.77, span=19.81, chord=1.98, nose=2.8, cowl=0.0, engine="twin", cabin=(3.4, 10.5, 0.82, 1.9, 3.7),
                       tailcone=(0.12, 3.1, 3.6), fin=(2.3, 2.6, 1.6), tailplane=(3.2, 1.3), struts="one", windows=8,
                       floats=(11.0, 1.1, 1.9), sq=3.5, prop=1.3),
}


def bush(m, L, kind):
    """A bush plane on floats: a slab-sided fuselage behind an open or cowled radial, or the Twin Otter's long nose and
    two turboprops; a high wing on struts, a tall square tail and slim floats close under it."""
    with m.keep():
        _bush(m, L, BUSH[kind])


def _bush(m, L, t):
    c = common()
    body_c, line_c, tail_c = LIVERIES["bush"]
    body = material("bush body", body_c, rough=0.35, clearcoat=0.3)
    line = material("bush line", line_c, rough=0.4)
    R = Real(L, t["length"])
    sq = t["sq"]
    y0, y1, hw, zb, zt = t["cabin"]
    tw, tb, tt = t["tailcone"]
    nose = t["nose"]
    paint = {"body": body, "glass": c["glass"]}
    if t["engine"] == "twin":
        # A long pointed nose up to the windscreen.
        pod(m, R, [(0.0, 0.12, 2.3, 2.6), (0.8, 0.45, 2.0, 2.9), (2.0, 0.72, 1.92, 3.1, 3.0), (nose, 0.8, 1.9, 3.2, sq)], {"b": body})
        front = (nose, 0.8, 1.9, 3.2, sq)
    else:
        zc = zb + t["cowl"] + 0.05
        cr = t["cowl"]
        if t["engine"] == "open":
            # Bare cylinders round the crankcase, then the engine mount back to the firewall.
            m.tube(R.y(0.6), R.y(0.1), 0, R.h(zc), R.w(0.28), c["dark"], segments=12)
            for k in range(9):
                a = 2 * math.pi * k / 9
                m.rod((0, R.y(0.35), R.h(zc)), (R.w(cr * math.cos(a)), R.y(0.35), R.h(zc + cr * math.sin(a))), max(R.w(0.08), 0.0028), c["metal"], segments=6)
            pod(m, R, [(0.55, 0.35, zc - 0.35, zc + 0.35), (nose, hw * 0.9, zb + 0.1, zt - 0.5, sq)], {"b": c["dark"]}, n=12)
            front = (nose, hw * 0.9, zb + 0.1, zt - 0.5, sq)
        else:
            cowl = material("bush cowl", line_c, rough=0.35, clearcoat=0.3)
            pod(m, R, [(0.0, cr * 0.8, zc - cr * 0.8, zc + cr * 0.8), (0.12, cr, zc - cr, zc + cr), (0.7, cr, zc - cr, zc + cr),
                       (nose, max(cr * 0.95, hw * 0.95), zb + 0.05, zc + cr, 2.6)], {"b": cowl})
            m.tube(R.y(0.02), R.y(0.0) + 0.0005, 0, R.h(zc), R.w(cr * 0.7), c["dark"], segments=16)
            front = (nose, max(cr * 0.95, hw * 0.95), zb + 0.05, zc + cr, 2.6)
        # The prop disc and its hub.
        m.tube(R.y(0.0), R.y(-0.12), 0, R.h(zc), R.w(0.15), c["dark"], segments=10)
        m.tube(R.y(-0.12), R.y(-0.15), 0, R.h(zc), R.w(t["prop"]), c["blur"], segments=24)
    # The windscreen slopes up from the nose to the cabin roof.
    pod(m, R, [front, (y0, hw, zb, zt, sq)], paint, part=upper(2, 6))
    pod(m, R, [(y0, hw, zb, zt, sq), (y1, hw, zb, zt, sq), (y1 + (t["length"] - y1) * 0.35, hw * 0.6, zb + (tb - zb) * 0.6, zt - 0.05, sq),
               (t["length"] - 0.15, tw, tb, tt, 2.5)], {"b": body})
    # Side windows, and a red line under them back to the tail.
    n = t["windows"]
    span = (y1 - y0 - 0.3) / n
    for k in range(n):
        wy = y0 + 0.15 + span * k
        heli_window(m, R, wy + 0.08, wy + span - 0.08, zt - 0.85, zt - 0.3, hw, c)
    for s in (-1, 1):
        m.box(s * R.w(hw) - 0.001, s * R.w(hw) + 0.001, R.y(y1), R.y(front[0]), R.h(zb + 0.45), R.h(zb + 0.6), line)
    # The wing on the cabin roof, square tipped.
    lead = y0 - 0.1
    wy = R.y(lead + t["chord"] / 2)
    wz = R.h(zt - 0.05)
    half = R.w(t["span"] / 2)
    ch = R.w(t["chord"])
    wing(m, wy, wz, half, ch, ch, 0.0, body, thick=R.h(0.2))
    for s in (-1, 1):
        foot = (s * R.w(hw * 0.95), R.y(lead + t["chord"] * 0.5), R.h(zb + 0.25))
        if t["struts"] == "v":
            for f in (0.15, 0.85):
                m.rod(foot, (s * half * 0.55, R.y(lead + t["chord"] * f), wz), 0.0024, c["metal"])
        else:
            m.rod(foot, (s * half * 0.55, R.y(lead + t["chord"] * 0.35), wz), 0.003, c["metal"])
    if t["engine"] == "twin":
        for s in (-1, 1):
            x = s * R.w(2.6)
            pod(m, R, [(lead - 1.4, 0.12, zt - 0.5, zt - 0.3), (lead - 0.8, 0.38, zt - 0.75, zt + 0.02), (lead + 2.0, 0.38, zt - 0.7, zt + 0.02),
                       (lead + 3.6, 0.1, zt - 0.35, zt - 0.05)], {"b": body}, x=x, n=12)
            zn = R.h(zt - 0.4)
            m.tube(R.y(lead - 1.4), R.y(lead - 1.55), x, zn, R.w(0.13), c["dark"], segments=10)
            m.tube(R.y(lead - 1.55), R.y(lead - 1.58), x, zn, R.w(t["prop"]), c["blur"], segments=24)
    # The tail: a tailplane on the cone and a tall square fin over it.
    fh, fr, ft = t["fin"]
    tp, tc = t["tailplane"]
    end = t["length"] - 0.15
    wing(m, R.y(end - tc / 2 - 0.1), R.h(tt - 0.15), R.w(tp), R.w(tc), R.w(tc * 0.8), 0.0, body, thick=R.h(0.12))
    fin(m, R.y(end - fr / 2), R.h(tt - 0.1), R.h(fh), R.w(fr), R.w(ft), R.w(fr - ft) * 0.7, body)
    # The floats: a raised bow, a step under the middle and a tapered stern, on struts up to the belly.
    fl, fd, fx = t["floats"]
    fpaint = material("floats", "#c8ccd0", metal=0.5, rough=0.35)
    f0 = -0.25 if t["engine"] != "twin" else 0.4
    for s in (-1, 1):
        x = s * R.w(fx)
        st = []
        for tt_, wf, b, top in ((0.0, 0.12, 0.55, 0.92), (0.07, 0.75, 0.22, 1.0), (0.2, 1.0, 0.03, 1.0), (0.55, 1.0, 0.0, 1.0),
                                (0.56, 0.95, 0.14, 1.0), (1.0, 0.18, 0.6, 0.92)):
            st.append((f0 + fl * tt_, 0.4 * wf * (fl / 7.5) ** 0.3, fd * b, fd * top, 4.0))
        pod(m, R, st, {"f": fpaint}, x=x, n=12)
        for f in (0.22, 0.58):
            y = R.y(f0 + fl * f)
            m.rod((x, y, R.h(fd)), (s * R.w(hw * 0.8), y, R.h(zb + 0.05)), 0.0028, c["metal"])
            m.rod((x, y, R.h(fd)), (s * R.w(hw * 0.8), y + (0.02 if f < 0.5 else -0.02), R.h(zb + 0.05)), 0.0022, c["metal"])
        m.rod((x, R.y(f0 + fl * 0.22), R.h(fd)), (-x, R.y(f0 + fl * 0.22), R.h(fd)), 0.0016, c["metal"])


def aircraft_builders():
    """The aircraft models by key, as render_vehicles registers them, with their lengths in sixteenths."""
    A = airliner
    return {
        "fairchild_71": (3, lambda m, L: bush(m, L, "fairchild")),
        "norseman": (3, lambda m, L: bush(m, L, "norseman")),
        "beaver": (3, lambda m, L: bush(m, L, "beaver")),
        "otter": (4, lambda m, L: bush(m, L, "otter")),
        "twin_otter": (5, lambda m, L: bush(m, L, "twin_otter")),
        "electra_10": (5, lambda m, L: A(m, L, "low", "radial2", tail="twin", span=1.2, livery="classic", gear="tail")),
        "dc3": (6, lambda m, L: A(m, L, "low", "radial2", span=1.45, livery="classic", gear="tail")),
        "north_star": (8, lambda m, L: A(m, L, "low", "radial4", span=1.25, livery="classic")),
        "viscount": (7, lambda m, L: A(m, L, "low", "turboprop4", span=1.15, livery="airline")),
        "dc8": (12, lambda m, L: A(m, L, "low", "jet_wing4", span=0.9, sweep=0.12, livery="airline")),
        "dc9": (9, lambda m, L: A(m, L, "low", "jet_tail2", tail="t", span=0.8, sweep=0.06, livery="airline")),
        "b737_200": (9, lambda m, L: A(m, L, "low", "jet_wing2", span=0.92, sweep=0.06, livery="airline")),
        "dash7": (7, lambda m, L: A(m, L, "high", "turboprop4", tail="t", span=1.15, livery="airline")),
        "dash8": (7, lambda m, L: A(m, L, "high", "turboprop2", tail="t", span=1.1, livery="airline")),
        "b767": (12, lambda m, L: A(m, L, "low", "jet_wing2", span=0.95, sweep=0.13, livery="airline", r=L * 0.065)),
        "a320": (10, lambda m, L: A(m, L, "low", "jet_wing2", span=0.92, sweep=0.09, livery="airline")),
        "crj": (8, lambda m, L: A(m, L, "low", "jet_tail2", tail="t", span=0.8, sweep=0.07, livery="airline", r=L * 0.05)),
        "a220": (9, lambda m, L: A(m, L, "low", "jet_wing2", span=0.92, sweep=0.08, livery="airline")),
        "bristol_freighter": (6, lambda m, L: A(m, L, "high", "radial2", span=1.4, livery="freight", windows=False, cargo_door=True, gear="tail")),
        "hercules": (9, lambda m, L: A(m, L, "high", "turboprop4", span=1.3, livery="freight", windows=False, cargo_door=True, r=L * 0.075)),
        "b747f": (13, lambda m, L: A(m, L, "low", "jet_wing4", span=0.85, sweep=0.15, livery="freight", windows=False, cargo_door=True, hump=0.35, r=L * 0.065)),
        "bell_47": (3, lambda m, L: helicopter(m, L, "bell47")),
        "s55": (4, lambda m, L: helicopter(m, L, "s55")),
        "jetranger": (3, lambda m, L: helicopter(m, L, "jetranger")),
        "s92": (5, lambda m, L: helicopter(m, L, "s92")),
    }
