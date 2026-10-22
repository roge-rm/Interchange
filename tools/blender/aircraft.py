"""Aircraft flown in Canada, in our own colours: built facing +y, L tiles long, x across, z in steps from the ground.

Fuselages are lofted through round sections that narrow to a rounded nose and sweep up into the tail. Wings and tail
surfaces are thin tapered slabs, swept as the type was. Bush planes sit on floats, as they're seen on every northern lake.
"""
import math

import rig
from parts import material
from steam import LETTERS


def common():
    return {
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


def floats(m, L, half, zb, c):
    """A pair of floats under a bush plane, half apart, and the struts up to its belly at zb steps."""
    paint = material("floats", "#c8ccd0", metal=0.5, rough=0.35)
    fl = L * 0.85
    for s in (-1, 1):
        sections = []
        for k in range(9):
            t = k / 8
            y = -fl / 2 + fl * t + L * 0.02
            w = 0.014 * (math.sin(min(1.0, t * 3) * math.pi / 2) if t < 0.35 else (1 - max(0.0, (t - 0.75) / 0.25) ** 2 * 0.8))
            h = 0.22 * (0.5 + 0.5 * min(1.0, t * 2.5)) * (1 - max(0.0, (t - 0.8) / 0.2) * 0.5)
            ring = [(s * half - w, 0.02), (s * half + w, 0.02), (s * half + w, 0.02 + h), (s * half - w, 0.02 + h)]
            sections.append((y, ring))
        m.loft(sections, {"paint": paint})
        for y in (L * 0.18, -L * 0.08):
            m.rod((s * half, y, 0.2), (s * 0.01, y, zb), 0.0025, c["metal"])


def airliner(m, L, wing_pos="low", engines="jet_wing2", tail="normal", span=1.0, sweep=0.0, livery="airline", windows=True,
             r=None, hump=0.0, gear="nose", float_gear=False, cargo_door=False, struts=False):
    """An aircraft by its layout: where its wing is, its engines (radial1, radial2, radial4, turboprop1, turboprop2,
    turboprop4, jet_wing2, jet_wing4, jet_tail2), its tail (normal, t or twin), span as a share of its length, how far
    the wingtips sweep back, its livery and gear."""
    c = common()
    body_c, line_c, tail_c = LIVERIES[livery]
    body = material(f"{livery} body", body_c, rough=0.35, clearcoat=0.3, metal=0.3 if livery in ("classic", "freight") else 0.0)
    line = material(f"{livery} line", line_c, rough=0.4)
    tail_m = material(f"{livery} tail", tail_c, rough=0.4, clearcoat=0.2)
    r = r or L * 0.055
    # The belly sits just over the wheels, or just over the floats' tops.
    gear_h = 0.17 if not float_gear else 0.3
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
    if struts or float_gear:
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
    if float_gear:
        floats(m, L, rx * 2.2, zc - r / rig.RISE, c)
    else:
        mains = [wy - chord * 0.2]
        wheels(m, mains, zc - r / rig.RISE, (-rx * 1.6, rx * 1.6) if wing_pos == "low" else (-rx * 1.2, rx * 1.2), c)
        if gear == "nose":
            wheels(m, [a1 - L * 0.12], zc - r / rig.RISE, (0.0,), c, r=0.009)
        else:
            wheels(m, [a0 + L * 0.08], zc - r / rig.RISE + 0.1, (0.0,), c, r=0.007)


def helicopter(m, L, kind):
    """A helicopter: a Bell 47's bubble and open tail boom, an S-55's high cabin over its nose engine, a JetRanger's
    smooth cabin, or an S-92's long cabin; a main rotor, a tail rotor, and skids or wheels."""
    c = common()
    body_c, trim_c, _ = LIVERIES["heli"]
    body = material(f"{kind} heli body", body_c, rough=0.35, clearcoat=0.3)
    trim = material(f"{kind} heli trim", trim_c, rough=0.4)
    a0, a1 = -L / 2, L / 2
    blade = material("rotor blade", "#2a2a2c", rough=0.5)
    if kind == "bell47":
        m.lathe(0, a1 - L * 0.25, GROUND, [(0.0, 0.0), (0.035, 0.12), (0.045, 0.4), (0.04, 0.7), (0.0, 0.85)], c["glass"], segments=20, round=True)
        m.box(-0.02, 0.02, a1 - L * 0.42, a1 - L * 0.3, GROUND - 0.03, GROUND + 0.4, c["metal"])
        m.rod((0, a1 - L * 0.42, GROUND + 0.3), (0, a0 + 0.01, GROUND + 0.45), 0.006, body)
        cab_top, rotor_y, skids = GROUND + 0.9, a1 - L * 0.32, True
    else:
        r = L * (0.2 if kind == "s55" else 0.17 if kind == "jetranger" else 0.15)
        zc = GROUND + r / rig.RISE
        fuselage(m, L * 0.62, r, zc, body, trim, nose=0.3, tail=0.25, lift=0.4, square=kind == "s92")
        rx = r * m.k(zc)
        m.box(-rx * 0.7, rx * 0.7, L * 0.2, L * 0.28, zc + 0.05, zc + r * 0.8 / rig.RISE, c["glass"])
        m.loft([(a0 + 0.01, [(-0.006, zc + 0.12), (0.006, zc + 0.12), (0.006, zc + 0.26), (-0.006, zc + 0.26)]),
                (-L * 0.25, [(-rx * 0.3, zc - 0.05), (rx * 0.3, zc - 0.05), (rx * 0.3, zc + 0.3), (-rx * 0.3, zc + 0.3)])], {"b": body})
        fin(m, a0 + 0.02, zc + 0.1, 0.3, 0.035, 0.025, 0.008, body)
        for s in (-1, 1):
            m.box(s * rx * 0.99 - 0.001, s * rx * 0.99 + 0.001, -L * 0.2, L * 0.15, zc - 0.05, zc + 0.05, trim)
        cab_top, rotor_y, skids = zc + r / rig.RISE + 0.15, L * 0.02, kind == "jetranger"
    m.rod((0, rotor_y, cab_top - 0.2), (0, rotor_y, cab_top), 0.004, c["metal"])
    blades = 2 if kind in ("bell47", "jetranger") else 4
    rotor = L * 0.55
    for k in range(blades):
        a = math.pi * k / blades + math.pi / 8
        dx, dy = math.cos(a) * rotor, math.sin(a) * rotor
        m.wedge([(-dx - 0.004 * dy / rotor, rotor_y - dy, cab_top), (dx - 0.004 * dy / rotor, rotor_y + dy, cab_top),
                 (dx + 0.004 * dy / rotor, rotor_y + dy, cab_top), (-dx + 0.004 * dy / rotor, rotor_y - dy, cab_top)],
                [(-dx - 0.004 * dy / rotor, rotor_y - dy, cab_top + 0.02), (dx - 0.004 * dy / rotor, rotor_y + dy, cab_top + 0.02),
                 (dx + 0.004 * dy / rotor, rotor_y + dy, cab_top + 0.02), (-dx + 0.004 * dy / rotor, rotor_y - dy, cab_top + 0.02)], blade)
    m.tube_x(0.008, 0.012, a0 + 0.012, 0.9, L * 0.09, c["prop"], segments=16)
    if skids:
        for s in (-1, 1):
            m.tube(a0 + L * 0.3, a1 - L * 0.15, s * 0.035, 0.03, 0.004, c["metal"], segments=8)
            for y in (a1 - L * 0.25, a1 - L * 0.45):
                m.rod((s * 0.035, y, 0.03), (s * 0.02, y, GROUND + 0.02), 0.003, c["metal"])
    else:
        wheels(m, [L * 0.05], GROUND + 0.02, (-0.04, 0.04), c, r=0.008)
        wheels(m, [L * 0.25], GROUND + 0.02, (0.0,), c, r=0.007)


# How high a helicopter's belly sits over the ground, in steps: low on short skids or wheels.
GROUND = 0.13


def aircraft_builders():
    """The aircraft models by key, as render_vehicles registers them, with their lengths in sixteenths."""
    A = airliner
    return {
        "fairchild_71": (3, lambda m, L: A(m, L, "high", "radial1", span=1.55, livery="bush", windows=False, r=L * 0.07, float_gear=True)),
        "norseman": (3, lambda m, L: A(m, L, "high", "radial1", span=1.5, livery="bush", windows=False, r=L * 0.075, float_gear=True)),
        "beaver": (3, lambda m, L: A(m, L, "high", "radial1", span=1.55, livery="bush", windows=False, r=L * 0.075, float_gear=True)),
        "otter": (4, lambda m, L: A(m, L, "high", "radial1", span=1.35, livery="bush", windows=True, r=L * 0.07, float_gear=True)),
        "twin_otter": (5, lambda m, L: A(m, L, "high", "turboprop2", span=1.2, livery="bush", windows=True, r=L * 0.065, float_gear=True, struts=True)),
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
