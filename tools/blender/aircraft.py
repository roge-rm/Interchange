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


def oval_loft(m, stations, mats, part=None, x=0.0, n=16, caps=True):
    """A body lofted through stations, each (y in tiles, half width in tiles, bottom and top in steps, and optionally how
    square: 2 an ellipse, 4 or more nearly a box). Ring point k runs from the right side's middle up over the top."""
    sections = []
    for st in stations:
        y, hw, zb, zt = st[:4]
        sq = st[4] if len(st) > 4 else 2.0
        zc, hh = (zb + zt) / 2, (zt - zb) / 2
        ring = []
        for j in range(n):
            a = 2 * math.pi * j / n
            ca, sa = math.cos(a), math.sin(a)
            ring.append((x + hw * math.copysign(abs(ca) ** (2 / sq), ca), zc + hh * math.copysign(abs(sa) ** (2 / sq), sa)))
        sections.append((y, ring))
    return m.loft(sections, mats, part=part, sharp=60, caps=caps)


def _f(s, p):
    return math.sqrt(max(0.0, 1 - s ** p))


# Noses by the share of the length they take, where their tip sits (in fuselage radii from the middle) and how fast the
# top, the bottom and the sides close in towards it: a lower power runs to a sharper point.
NOSES = {
    "round": (0.11, -0.35, 1.5, 3.0, 2.2),
    "airliner": (0.12, -0.28, 1.7, 2.8, 2.2),
    "jet": (0.13, -0.25, 1.8, 2.8, 2.2),
    "pointed": (0.15, -0.3, 1.3, 2.3, 1.7),
    "blunt": (0.09, -0.1, 4.0, 4.0, 3.2),
    "radome": (0.11, -0.3, 1.6, 2.6, 2.3),
}


# Ring faces by the angle of their middle, from the right side's middle up over the top: the belly, the windscreen's front
# panels either side of a centre post and the cockpit side windows.
RING = 48


def _mid(k):
    return (k + 0.5) * 360 / RING


def _skin(k):
    return "belly" if 225 < _mid(k) < 337.5 else "body"


def _screen(k):
    a = _mid(k)
    return "glass" if 22 < a < 158 and not 82 < a < 98 else _skin(k)


def _side(k):
    a = _mid(k)
    return "glass" if 12 < a < 52 or 128 < a < 168 else _skin(k)


def _cockpit(top, L, r, t0, t1, slopes=(20, 38), side=0.035):
    """Where along a nose (t, a share of L) the windscreen goes: where the top, top(t) in fuselage radii, slopes down
    between slopes degrees; the side windows run side of L behind it."""
    lo = hi = None
    n = 200
    for i in range(n):
        t = t0 + (t1 - t0) * i / n
        dt = (t1 - t0) / n
        a = math.degrees(math.atan((top(t) - top(t + dt)) * r / (L * dt)))
        if lo is None and a >= slopes[0]:
            lo = t
        if hi is None and a >= slopes[1]:
            hi = t
    lo = lo if lo is not None else t1 - (t1 - t0) * 0.3
    hi = max(hi if hi is not None else t1, lo + 0.012)
    return lo - side, lo, hi


def fuselage(m, L, r, zc, body, belly, c, nose="jet", glass=True, tail=0.32, upswept=False, hump=0.0, radome=None):
    """A fuselage L long and r round (tiles), its middle zc steps up: a nose of one of the NOSES styles with its windscreen
    where the nose slopes down and side windows behind it, a tail cone over the last tail share whose underside sweeps up
    (sharply into a ramp when upswept), and an upper deck hump radii high on top from the nose back over the forward
    fuselage, as a 747's, with the cockpit in its front."""
    share, tipz, pt, pb, pw = NOSES[nose]
    a0 = -L / 2
    R = r / rig.RISE
    n0 = 1 - share

    def station(t):
        if t < tail:
            s = (tail - t) / tail
            if upswept:
                w, top, bot = 1 - 0.72 * s ** 1.2, 1 - 0.12 * s ** 1.5, -1 + 1.62 * s ** 0.75
            else:
                w, top, bot = 1 - 0.8 * s ** 1.3, 1 - 0.2 * s ** 1.6, -1 + 1.4 * s ** 1.1
        elif t <= n0:
            w, top, bot = 1.0, 1.0, -1.0
        else:
            s = min(0.985, (t - n0) / share)
            w = max(0.04, _f(s, pw))
            top = tipz + (1 - tipz) * _f(s, pt)
            bot = tipz - (1 + tipz) * _f(s, pb)
        return (a0 + L * t, r * w, zc + bot * R, zc + top * R)

    paint = {"body": body, "belly": belly, "glass": c["glass"]}
    ts = [tail * k / 10 for k in range(10)] + [tail, n0]
    oval_loft(m, [station(t) for t in ts], paint, part=_skin, n=RING)
    cuts = [n0, 1.0]
    if glass and not hump:
        g0, g1, g2 = _cockpit(lambda t: (station(t)[3] - zc) / R, L, r, n0, 1.0)
        cuts += [g0, g1, g2]
    if radome:
        cuts.append(n0 + share * radome)
    cuts = sorted(set(cuts))
    ss = sorted(set([n0 + share * k / 14 for k in range(15)] + cuts))
    for lo, hi in zip(cuts, cuts[1:]):
        mid = (lo + hi) / 2
        piece = [s for s in ss if lo <= s <= hi]
        if radome and mid > n0 + share * radome:
            mats, part = {"r": material("radome", "#5a5e62", rough=0.5)}, None
        elif glass and not hump and g0 < mid < g1:
            mats, part = paint, _side
        elif glass and not hump and g1 < mid < g2:
            mats, part = paint, _screen
        else:
            mats, part = paint, _skin
        oval_loft(m, [station(t) for t in piece], mats, part=part, n=RING)
    if hump:
        _upper_deck(m, L, r, zc, hump, station, paint, glass)


def _upper_deck(m, L, r, zc, hump, station, paint, glass):
    """A 747's upper deck: a narrower lobe standing out of the top from the wing root forward, faired down into the top
    at the back and sloping down into the nose at the front with the cockpit in the slope."""
    R = r / rig.RISE
    a0 = -L / 2
    back, full, front, end = 0.62, 0.73, 0.895, 0.962
    bottom = zc - 0.2 * R

    def top(t):
        if t < full:
            u = (t - back) / (full - back)
            return 0.97 + (hump + 0.03) * u * u * (3 - 2 * u)
        if t <= front:
            return 1 + hump
        u = (t - front) / (end - front)
        low = (station(end)[3] - zc) / R - 0.06
        return low + (1 + hump - low) * _f(u, 1.7)

    def width(t):
        if t < full:
            u = (t - back) / (full - back)
            return r * (0.35 + 0.25 * u)
        if t <= front:
            return r * 0.6
        u = (t - front) / (end - front)
        return r * (0.6 - 0.32 * u ** 1.5)

    def st(t):
        return (a0 + L * t, width(t), bottom, zc + top(t) * R)
    ts = sorted(set([back + (full - back) * k / 8 for k in range(9)] + [front] + [front + (end - front) * k / 14 for k in range(15)]))
    if glass:
        g0, g1, g2 = _cockpit(top, L, r, front, end, slopes=(24, 50), side=0.03)
        ts = sorted(set(ts + [g0, g1, g2]))
        cuts = [ts[0], g0, g1, g2, ts[-1]]
        parts_ = [_skin, _side, _screen, _skin]
    else:
        cuts, parts_ = [ts[0], ts[-1]], [_skin]
    for (lo, hi), part in zip(zip(cuts, cuts[1:]), parts_):
        oval_loft(m, [st(t) for t in ts if lo <= t <= hi], paint, part=part, n=RING)


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
    flat(m, pts, mat, x, thick)


def flat(m, pts, mat, x=0.0, thick=0.006):
    """A thin upright plate at x through the outline pts, each (y, z in steps)."""
    m.wedge([(x - thick, py, pz) for py, pz in pts], [(x + thick, py, pz) for py, pz in pts], mat)


def round_fin(m, back, z, height, chord, mat, fillet=0.0, x=0.0, thick=0.006):
    """A piston airliner's fin: a straight trailing edge from back, a leading edge chord long at the foot curving round to
    the top, and a dorsal fillet running fillet tiles further forward along the fuselage."""
    pts = [(back, z)]
    if fillet:
        pts += [(back + chord + fillet, z), (back + chord + fillet * 0.45, z + height * 0.07), (back + chord + fillet * 0.12, z + height * 0.16)]
    for k in range(9):
        a = 0.2 + (math.pi / 2 - 0.2) * k / 8
        pts.append((back + 0.3 * chord + 0.7 * chord * math.cos(a), z + height * math.sin(a)))
    pts += [(back + 0.08 * chord, z + height * 0.95), (back, z + height * 0.8)]
    flat(m, pts, mat, x, thick)


def oval_fin(m, y, z, height, chord, mat, x):
    """An oval fin centred at y, standing from z, as the Electra's twin tails."""
    pts = [(y + chord / 2 * math.cos(2 * math.pi * k / 16), z + height / 2 * (1 + math.sin(2 * math.pi * k / 16))) for k in range(16)]
    flat(m, pts, mat, x, 0.004)


# Engine shapes along their length from the front (0) to the back (1): (where, half width, half height, how far the middle
# drops), in the engine's radius.
NACELLES = {
    # A short fat cowl round a radial, the nacelle tapering back over the wing.
    "radial": [(0.0, 0.84, 0.84, 0.0), (0.04, 0.98, 0.98, 0.0), (0.12, 1.0, 1.0, 0.0), (0.32, 0.98, 1.0, -0.02), (0.5, 0.86, 0.95, -0.08),
               (0.75, 0.58, 0.72, -0.12), (1.0, 0.12, 0.2, -0.1)],
    # A Merlin's slim pointed cowl with its radiator scoop under the chin.
    "inline": [(0.0, 0.5, 0.55, 0.0), (0.08, 0.78, 0.82, 0.0), (0.2, 0.88, 1.0, -0.12), (0.45, 0.9, 1.12, -0.2), (0.62, 0.85, 1.0, -0.12),
               (0.8, 0.6, 0.7, -0.05), (1.0, 0.12, 0.18, 0.0)],
    # A turboprop's long slim nacelle with an intake under the spinner.
    "turboprop": [(0.0, 0.62, 0.62, 0.0), (0.06, 0.86, 0.95, -0.08), (0.18, 1.0, 1.1, -0.1), (0.6, 1.0, 1.05, -0.05), (0.85, 0.72, 0.75, 0.0),
                  (1.0, 0.14, 0.16, 0.05)],
    # A big fan with its lip, the fan nozzle, then the core and its plug.
    "fan": [(0.0, 0.9, 0.9, 0.0), (0.05, 1.0, 1.0, 0.0), (0.4, 0.99, 0.99, 0.0), (0.66, 0.84, 0.84, 0.0), (0.68, 0.58, 0.58, 0.0),
            (0.86, 0.48, 0.48, 0.0), (1.0, 0.18, 0.18, 0.0)],
    # A low-bypass engine's long thin cigar.
    "cigar": [(0.0, 0.86, 0.86, 0.0), (0.06, 1.0, 1.0, 0.0), (0.5, 1.0, 1.0, 0.0), (0.82, 0.86, 0.86, 0.0), (0.96, 0.7, 0.7, 0.0),
              (1.0, 0.66, 0.66, 0.0)],
}


def nacelle(m, kind, x, front, z, R, length, mat, c, prop=0.0, tall=1.0):
    """An engine kind (NACELLES) at x and z steps, its front at front and length tiles long, R tiles round (tall times that
    up and down); a prop disc prop tiles round on a spinner, or a dark intake."""
    st = [(front - t * length, R * w, z + (dz - h * tall) * R / rig.RISE, z + (dz + h * tall) * R / rig.RISE) for t, w, h, dz in NACELLES[kind]]
    jet = kind in ("fan", "cigar")
    oval_loft(m, st, {"e": mat}, x=x, caps=not jet)
    if jet:
        m.tube(front - length * 0.06, front - length * 0.04, x, z, R * 0.86, c["dark"], segments=16)
        m.tube(front - length, front - length * 0.97, x, z, R * NACELLES[kind][-1][1] * 0.9, c["dark"], segments=12)
        return
    if kind == "radial":
        # The cylinders' dark face inside the cowl lip and a small hub.
        m.tube(front - 0.0006, front + 0.0008, x, z, R * 0.74, c["dark"], segments=18)
        spin = R * 0.35
    else:
        spin = R * 1.0
    oval_loft(m, [(front + spin, R * 0.04, z - R * 0.04 / rig.RISE, z + R * 0.04 / rig.RISE), (front + spin * 0.55, R * 0.3, z - R * 0.3 / rig.RISE, z + R * 0.3 / rig.RISE),
                  (front, R * 0.45, z - R * 0.45 / rig.RISE, z + R * 0.45 / rig.RISE)], {"s": mat if kind != "radial" else c["dark"]}, x=x, n=12)
    py = front + spin * 0.35
    m.tube(py - 0.001, py + 0.001, x, z, prop, c["blur"], segments=28)


def wheels(m, ys, z, xs, c, r=0.012):
    for y in ys:
        for x in xs:
            with m.keep():
                m.tube_x(x - 0.004, x + 0.004, y, r / rig.RISE, r, c["tire"], segments=14)
            m.rod((x, y, m.fixed(r / rig.RISE)), (x * 0.8 if abs(x) < 0.05 else x, y, z), 0.0025, c["metal"])


# How each airliner's nose, engines and stance go: the nose style (glass None for no windscreen in it); the engine shape,
# its radius in fuselage radii, where along each wing as a share of the half span, its length (in local wing chords for
# props, in fuselage lengths for jets), how much of it sits ahead of the wing's leading edge and the prop's radius in
# fuselage radii; drop, how far a jet's middle hangs under the wing in its radii; for tail-wheel aircraft, how far the
# tail sits down, in degrees. The big fans are sized from the real engines against the fuselages, which are drawn fatter
# than real: 747 JT9D and 767 CF6 about 2.7 m across, A220 PW1500G 2.2 m.
AIRLINERS = {
    "electra_10": dict(nose="round", eng=("radial", 0.5, [0.3], 1.25, 0.45, 1.0), stance=8),
    "dc3": dict(nose="round", eng=("radial", 0.62, [0.24], 1.45, 0.45, 1.45), stance=11),
    "north_star": dict(nose="airliner", eng=("inline", 0.42, [0.24, 0.48], 1.4, 0.5, 1.25)),
    "viscount": dict(nose="airliner", eng=("turboprop", 0.32, [0.22, 0.44], 1.5, 0.45, 1.0)),
    "bristol_freighter": dict(nose="blunt", glass=None, eng=("radial", 0.58, [0.25], 1.4, 0.45, 1.45), stance=8),
    "hercules": dict(nose="radome", radome=0.7, eng=("turboprop", 0.38, [0.25, 0.5], 1.45, 0.5, 1.0), tall=1.2),
    "dash7": dict(nose="airliner", eng=("turboprop", 0.36, [0.24, 0.48], 1.6, 0.45, 1.15), tall=1.2),
    "dash8": dict(nose="pointed", eng=("turboprop", 0.42, [0.3], 2.3, 0.38, 1.45), tall=1.45),
    "dc8": dict(nose="jet", eng=("cigar", 0.3, [0.32, 0.6], 0.13, 0.6, 0)),
    "b737_200": dict(nose="jet", eng=("cigar", 0.34, [0.3], 0.24, 0.45, 0), flush=True),
    "a320": dict(nose="jet", eng=("fan", 0.52, [0.34], 0.12, 0.7, 0)),
    "a220": dict(nose="pointed", eng=("fan", 0.6, [0.32], 0.125, 0.72, 0), drop=1.4),
    "b767": dict(nose="jet", eng=("fan", 0.47, [0.32], 0.12, 0.72, 0), drop=1.3),
    "b747f": dict(nose="jet", eng=("fan", 0.36, [0.4, 0.71], 0.095, 0.7, 0), drop=1.4),
    "dc9": dict(nose="jet", eng=("cigar", 0.42, None, 0.2, 0, 0)),
    "crj": dict(nose="pointed", eng=("fan", 0.5, None, 0.15, 0, 0)),
}


def airliner(m, L, kind, wing_pos="low", tail="normal", span=1.0, sweep=0.0, livery="airline", windows=True,
             r=None, hump=0.0, gear="nose", cargo_door=False):
    """An aircraft by its layout: its kind in AIRLINERS for the nose and engines, where its wing is, its tail (normal, t
    or twin), span as a share of its length, how far the wingtips sweep back, its livery and gear."""
    t = AIRLINERS[kind]
    c = common()
    body_c, line_c, tail_c = LIVERIES[livery]
    body = material(f"{livery} body", body_c, rough=0.35, clearcoat=0.3, metal=0.3 if livery in ("classic", "freight") else 0.0)
    line = material(f"{livery} line", line_c, rough=0.4)
    tail_m = material(f"{livery} tail", tail_c, rough=0.4, clearcoat=0.2)
    r = r or L * 0.055
    R_ = r / rig.RISE
    # The belly sits just over the wheels.
    gear_h = 0.17
    zc = gear_h + r / rig.RISE
    fuselage(m, L, r, zc, body, c["grey"], c, nose=t["nose"], glass=t.get("glass", True), upswept=kind == "hercules",
             hump=hump, radome=t.get("radome"), tail=0.4 if kind == "hercules" else 0.32)
    rx = r
    a0, a1 = -L / 2, L / 2

    # Cheatline and cabin windows, each set into the round side at its own height: f of r up the side sits sqrt(1 - f^2)
    # of r out.
    def out(f):
        return rx * math.sqrt(1 - f * f)

    nose_end = a1 - L * NOSES[t["nose"]][0]
    for s in (-1, 1):
        m.box(s * out(0.12) - 0.0008, s * rx + 0.0004 * s, a0 + L * 0.3, nose_end + L * 0.01, zc - r * 0.12 / rig.RISE, zc + r * 0.06 / rig.RISE, line)
        if windows and kind == "viscount":
            # The Viscount's big oval windows.
            y = a0 + L * 0.36
            while y + 0.01 < nose_end - L * 0.04:
                m.tube_x(s * out(0.3) - s * 0.0006, s * out(0.3) + s * 0.0006, y, zc + r * 0.3 / rig.RISE, r * 0.17, c["glass"], segments=14)
                y += r * 0.62
        elif windows:
            y = a0 + L * 0.32
            while y + 0.008 < nose_end - L * 0.03:
                m.box(s * out(0.34) - 0.0006, s * out(0.18) + 0.0004 * s, y, y + 0.007, zc + r * 0.18 / rig.RISE, zc + r * 0.32 / rig.RISE, c["glass"])
                y += 0.015
        if cargo_door:
            m.box(s * out(0.4) - 0.0006, s * rx + 0.0004 * s, a1 - L * 0.32, a1 - L * 0.2, zc - r * 0.4 / rig.RISE, zc + r * 0.4 / rig.RISE, line)
    if livery != "bush":
        m.text(LETTERS, out(0.45) + 0.001, 0, zc - r * 0.45 / rig.RISE, L * 0.05, line, 1)
    if kind == "bristol_freighter":
        # The cockpit perched on top behind the clamshell nose, faired back into a long spine.
        top = zc + R_
        oval_loft(m, [(a1 - L * 0.075, r * 0.25, top - 0.3 * R_, top - 0.02 * R_), (a1 - L * 0.1, r * 0.6, top - 0.4 * R_, top + 0.36 * R_),
                      (a1 - L * 0.15, r * 0.62, top - 0.4 * R_, top + 0.42 * R_)], {"body": body, "glass": c["glass"]},
                  part=lambda k: "glass" if 1 <= k < 7 else "body")
        oval_loft(m, [(a1 - L * 0.15, r * 0.62, top - 0.4 * R_, top + 0.42 * R_), (a1 - L * 0.24, r * 0.5, top - 0.4 * R_, top + 0.36 * R_),
                      (a1 - L * 0.42, r * 0.12, top - 0.3 * R_, top + 0.02 * R_)], {"body": body})
        # The seam between the clamshell doors.
        m.box(-0.0008, 0.0008, a1 - L * 0.012, a1 + 0.0004, zc - 0.75 * R_, zc + 0.6 * R_, c["dark"])
    if kind == "hercules":
        # The main gear in a pod either side of the belly.
        for s in (-1, 1):
            oval_loft(m, [(a1 - L * 0.36, r * 0.05, zc - 0.85 * R_, zc - 0.6 * R_), (a1 - L * 0.4, r * 0.3, zc - 0.98 * R_, zc - 0.25 * R_, 3.0),
                          (a1 - L * 0.62, r * 0.3, zc - 0.98 * R_, zc - 0.25 * R_, 3.0), (a1 - L * 0.7, r * 0.05, zc - 0.85 * R_, zc - 0.5 * R_)],
                      {"b": body}, x=s * r * 0.92, n=12)
    half = L * 0.5 * span
    wy = 0.02 * L if wing_pos == "low" else 0.05 * L
    wz = zc - r * 0.75 / rig.RISE if wing_pos == "low" else zc + r * 0.8 / rig.RISE
    chord = L * (0.16 if sweep < 0.05 else 0.2)
    tipc = chord * 0.42
    x0 = rx * 0.6
    dih = 0.25 if wing_pos == "low" else 0.0
    thick = 0.07
    wing(m, wy, wz, half, chord, tipc, sweep, body, x0=x0, dihedral=dih, thick=thick)

    def at(x):
        """The wing's leading edge, chord and underside at x across."""
        u = (abs(x) - x0) / (half - x0)
        return wy + chord / 2 - sweep * u, chord + (tipc - chord) * u, wz + dih * u

    # Engines.
    style, er, spots, length, ahead, prop = t["eng"]
    er *= r
    tall = t.get("tall", 1.0)
    eng = material("engine", body_c, metal=0.5, rough=0.35)
    gear_x = None
    if spots is None:
        # Two engines on the rear fuselage on stub pylons.
        ln = length * L
        front = a0 + L * 0.3
        ez = zc + r * 0.35 / rig.RISE
        ex = rx + er * 1.25
        for s in (-1, 1):
            nacelle(m, style, s * ex, front, ez, er, ln, eng, c)
            pts = [(s * rx * 0.8, front - ln * 0.25), (s * ex, front - ln * 0.3), (s * ex, front - ln * 0.7), (s * rx * 0.8, front - ln * 0.8)]
            m.wedge([(px, py, ez - 0.012) for px, py in pts], [(px, py, ez + 0.012) for px, py in pts], eng)
    else:
        for f in spots:
            for s in (-1, 1):
                x = s * half * f
                lead, ch, zb = at(x)
                if style in ("fan", "cigar"):
                    ln = length * L
                    front = lead + ln * ahead
                    if t.get("flush"):
                        # Hung tight under the wing, no pylon.
                        ez = zb - er / rig.RISE * 0.92
                    else:
                        ez = zb - er / rig.RISE * t.get("drop", 1.12)
                        top = ez + er / rig.RISE * 0.95
                        flat(m, [(front - ln * 0.2, top), (lead + 0.004, zb + 0.02), (lead - ch * 0.45, zb + 0.02), (front - ln * 0.8, top)], eng, x, 0.0025)
                    nacelle(m, style, x, front, ez, er, ln, eng, c)
                else:
                    ln = length * ch
                    front = lead + ln * ahead
                    if wing_pos == "low":
                        ez = zb + thick * 0.5 + er * 0.15 / rig.RISE
                    else:
                        ez = zb + thick - er * tall * 0.85 / rig.RISE
                    nacelle(m, style, x, front, ez, er, ln, eng, c, prop=prop * r, tall=tall)
                    if f == spots[0]:
                        gear_x = (abs(x), lead - ch * 0.1, ez - er * tall * 0.8 / rig.RISE)
    # Tail.
    ty = a0 + L * 0.1
    tz = zc + r * 0.55 / rig.RISE
    fin_h = L * 0.6 / rig.RISE * 0.32
    tail_sweep = 0.02 + sweep * 0.8
    if tail == "twin":
        wing(m, ty, tz, L * 0.17, L * 0.1, L * 0.07, 0.0, body, thick=0.04)
        for s in (-1, 1):
            oval_fin(m, ty - L * 0.01, tz - fin_h * 0.3, fin_h * 0.95, L * 0.09, tail_m, s * L * 0.17)
    else:
        if t.get("stance") or kind == "bristol_freighter":
            round_fin(m, a0 + L * 0.005, tz - fin_h * 0.05, fin_h * (1.0 if kind == "dc3" else 1.05), L * 0.15, tail_m,
                      fillet=L * (0.22 if kind == "bristol_freighter" else 0.07))
        elif kind == "hercules":
            # From the L-100's metres (29.8 m long), points by metres forward of the tail and up from the ground: a
            # broad fin 7 m along its root and 3.3 m at the tip, its top 11 m up (11.7 m real), the trailing edge near
            # upright and a dorsal fillet running 3 m on forward.
            sm = L / 29.8

            def yz(fwd, up):
                return (a0 + fwd * sm, up * sm / rig.RISE)
            root, top = zc + 0.6 * R_, zc + 0.95 * R_
            flat(m, [(a0 + 0.5 * sm, root), yz(0.9, 11.0), yz(4.2, 11.0), (a0 + 7.2 * sm, top + 1.2 * sm / rig.RISE),
                     (a0 + 8.6 * sm, top + 0.35 * sm / rig.RISE), (a0 + 10.5 * sm, top - 0.02 * R_), (a0 + 10.5 * sm, root)], tail_m)
        else:
            fin(m, ty + 0.01, tz, fin_h, L * 0.16, L * 0.08, tail_sweep + L * 0.04, tail_m)
        if tail == "t":
            wing(m, ty - tail_sweep - L * 0.02, tz + fin_h, L * 0.16, L * 0.08, L * 0.05, 0.01, tail_m, thick=0.04)
        else:
            wing(m, ty, tz - 0.05, L * 0.18 * (1 + span * 0.1), L * 0.11, L * 0.06, tail_sweep, body, thick=0.04)
    # Gear: on a piston or turboprop airliner the mains fold into the inner nacelles.
    belly = zc - r / rig.RISE
    if kind == "hercules":
        wheels(m, [a1 - L * 0.46, a1 - L * 0.56], zc - 0.6 * R_, (-rx * 0.92, rx * 0.92), c)
    elif gear_x:
        gx, gy, gz = gear_x
        wheels(m, [gy], gz, (-gx, gx), c)
    else:
        wheels(m, [wy - chord * 0.2], belly, (-rx * 1.6, rx * 1.6) if wing_pos == "low" else (-rx * 1.2, rx * 1.2), c)
    if gear == "nose":
        wheels(m, [a1 - L * 0.12], belly, (0.0,), c, r=0.009)
    else:
        # The tail wheel sits low enough to touch the ground once the model is tipped back on its mains.
        main_y = gear_x[1] if gear_x else wy
        yt = a0 + L * 0.09
        rw = 0.007
        dip = (main_y - yt) * math.tan(math.radians(t["stance"]))
        with m.keep():
            m.tube_x(-0.003, 0.003, yt, (dip + rw) / rig.RISE, rw, c["tire"], segments=12)
        m.rod((0, yt, (dip + rw) / rig.RISE), (0, yt + 0.006, zc - r * 0.25 / rig.RISE), 0.002, c["metal"])
        # render_vehicles tips ground shots back this far about the main wheels.
        m.stance = (main_y, math.radians(t["stance"]))


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
        "electra_10": (5, lambda m, L: A(m, L, "electra_10", "low", tail="twin", span=1.2, livery="classic", gear="tail")),
        "dc3": (6, lambda m, L: A(m, L, "dc3", "low", span=1.45, livery="classic", gear="tail")),
        "north_star": (8, lambda m, L: A(m, L, "north_star", "low", span=1.25, livery="classic")),
        "viscount": (7, lambda m, L: A(m, L, "viscount", "low", span=1.15, livery="airline")),
        "dc8": (12, lambda m, L: A(m, L, "dc8", "low", span=0.9, sweep=0.12, livery="airline")),
        "dc9": (9, lambda m, L: A(m, L, "dc9", "low", tail="t", span=0.8, sweep=0.06, livery="airline")),
        "b737_200": (9, lambda m, L: A(m, L, "b737_200", "low", span=0.92, sweep=0.06, livery="airline")),
        "dash7": (7, lambda m, L: A(m, L, "dash7", "high", tail="t", span=1.15, livery="airline")),
        "dash8": (7, lambda m, L: A(m, L, "dash8", "high", tail="t", span=1.1, livery="airline")),
        "b767": (12, lambda m, L: A(m, L, "b767", "low", span=0.95, sweep=0.13, livery="airline", r=L * 0.065)),
        "a320": (10, lambda m, L: A(m, L, "a320", "low", span=0.92, sweep=0.09, livery="airline")),
        "crj": (8, lambda m, L: A(m, L, "crj", "low", tail="t", span=0.8, sweep=0.07, livery="airline", r=L * 0.05)),
        "a220": (9, lambda m, L: A(m, L, "a220", "low", span=0.92, sweep=0.08, livery="airline", r=L * 0.05)),
        "bristol_freighter": (6, lambda m, L: A(m, L, "bristol_freighter", "high", span=1.4, livery="freight", windows=False, cargo_door=True, gear="tail")),
        "hercules": (9, lambda m, L: A(m, L, "hercules", "high", span=1.3, livery="freight", windows=False, cargo_door=True, r=L * 0.075)),
        "b747f": (15, lambda m, L: A(m, L, "b747f", "low", span=0.85, sweep=0.15, livery="freight", windows=False, cargo_door=True, hump=0.42, r=L * 0.065)),
        "bell_47": (3, lambda m, L: helicopter(m, L, "bell47")),
        "s55": (4, lambda m, L: helicopter(m, L, "s55")),
        "jetranger": (3, lambda m, L: helicopter(m, L, "jetranger")),
        "s92": (5, lambda m, L: helicopter(m, L, "s92")),
    }
