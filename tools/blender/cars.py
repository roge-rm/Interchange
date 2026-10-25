"""Cars, in our own colours: built facing +y, L tiles long."""
import math

import parts
import rig
from parts import material
from steam import LETTERS, truck, underframe


def common():
    return {
        "iron": material("iron", "#3a3a3c", metal=0.5, rough=0.5),
        "steel": material("steel", "#9a9a9a", metal=1.0, rough=0.3),
        "glass": material("glass", "#1e2a36", rough=0.1),
        "roof": material("car roof", "#3e3a36", rough=0.75),
        "coach roof": material("coach roof", "#58544e", rough=0.65),
        "letters": material("gold leaf", "#d8b450", metal=1.0, rough=0.3),
    }


def _arch(m, secs, mat, edge=0.02, n=12):
    """A thin roof with a low arch, lofted through secs, each (y, z0, half, rise): its eaves at z0 steps, edge steps
    thick there, half tiles each side of the middle and rising rise steps more at the crown."""
    rings = []
    for y, z0, half, rise in secs:
        ring = [(half * (1 - 2 * i / n), z0 + edge + rise * (1 - (1 - 2 * i / n) ** 2)) for i in range(n + 1)]
        ring += [(-half, z0), (half, z0)]
        rings.append((y, ring))
    return m.loft(rings, {"roof": mat}, sharp=50)


def _hooded(a0, a1, z0, half, rise, end=0.04):
    """Sections for a roof whose crown rounds down over each end, as on a passenger car."""
    return [(a0, z0, half, rise * 0.1), (a0 + end * 0.4, z0, half, rise * 0.65), (a0 + end, z0, half, rise),
            (a1 - end, z0, half, rise), (a1 - end * 0.4, z0, half, rise * 0.65), (a1, z0, half, rise * 0.1)]


def _clerestory(m, a0, a1, z0, body, roof, glass, half=0.124, cw=0.068, ch=0.13, windows=12):
    """A thin lower roof at z0 steps with a raised clerestory along its middle, ch steps tall and cw tiles each side,
    its small windows down both sides and its roof sloping down to the lower one over each end. The lower roof is
    tin, lighter than the upper one, so the two read apart from above."""
    _arch(m, [(a0, z0, half, 0.02), (a1, z0, half, 0.02)], material("tin roof", "#77726a", rough=0.6), edge=0.02)
    zl, zt, e = z0 + 0.035, z0 + 0.035 + ch, 0.045
    ys = (a0 + 0.004, a0 + e, a1 - e, a1 - 0.004)
    tops = (zl + 0.012, zt, zt, zl + 0.012)
    m.loft([(y, [(-cw, zl - 0.01), (cw, zl - 0.01), (cw, t), (-cw, t)]) for y, t in zip(ys, tops)], {"body": body})
    _arch(m, [(y, t, cw + 0.01, 0.025 if t == zt else 0.004) for y, t in zip(ys, tops)], roof, edge=0.016, n=8)
    span = (a1 - a0 - 2 * e - 0.02) / windows
    for side in (-1, 1):
        for i in range(windows):
            y0 = a0 + e + 0.01 + span * i + span * 0.2
            m.box(side * cw - 0.0008, side * cw + 0.0008, y0, y0 + span * 0.6, zl + 0.03, zt - 0.03, glass)


def _platforms(m, L, a0, a1, c, rail=1.12):
    """Open end platforms with railings, a step down from each corner and an end door."""
    door = material("end door", "#2a1a12", rough=0.6)
    for y0, y1, end in ((-L / 2 + 0.005, a0, a0), (a1, L / 2 - 0.005, a1)):
        m.box(-0.1, 0.1, y0, y1, 0.55, 0.65, c["iron"])
        out = y0 if end == a0 else y1
        m.rod((-0.1, out, rail), (0.1, out, rail), 0.003, c["steel"])
        for side in (-1, 1):
            m.rod((side * 0.1, out, 0.65), (side * 0.1, out, rail), 0.003, c["steel"])
            m.rod((side * 0.1, out, rail), (side * 0.1, end, rail + 0.05), 0.003, c["steel"])
            m.box(side * 0.1 - 0.012, side * 0.1 + 0.012, (y0 + y1) / 2 - 0.008, (y0 + y1) / 2 + 0.008, 0.42, 0.55, c["iron"])
        d = 0.002 if end == a1 else -0.002
        m.box(-0.03, 0.03, min(end, end + d), max(end, end + d), 0.66, 1.25, door)


def coach_wood(m, L):
    """A wooden open-platform coach of the 1870s to 1900s: varnished sides, a row of tall windows, truss rods and a
    clerestory roof with its own small windows."""
    c = common()
    body = material("varnished wood", "#7a3a1e", rough=0.4, clearcoat=0.6)
    trim = material("dark wood", "#4a2414", rough=0.5)
    a0, a1 = -L / 2 + 0.03, L / 2 - 0.03
    underframe(m, -L / 2 + 0.01, L / 2 - 0.01, c)
    for y in (-L / 2 + 0.09, L / 2 - 0.09):
        truck(m, y, c)
    m.box(-0.12, 0.12, a0, a1, 0.62, 1.42, body, bevel=0.003)
    for side in (-1, 1):
        m.box(side * 0.12 - 0.002, side * 0.12 + 0.002, a0 + 0.005, a1 - 0.005, 0.86, 0.9, trim)
        m.box(side * 0.12 - 0.002, side * 0.12 + 0.002, a0 + 0.005, a1 - 0.005, 1.28, 1.32, trim)
        # Truss rods sagging to queen posts between the trucks.
        m.rod((side * 0.09, a0 + 0.1, 0.5), (side * 0.09, -0.08, 0.36), 0.0025, c["iron"])
        m.rod((side * 0.09, -0.08, 0.36), (side * 0.09, 0.08, 0.36), 0.0025, c["iron"])
        m.rod((side * 0.09, 0.08, 0.36), (side * 0.09, a1 - 0.1, 0.5), 0.0025, c["iron"])
    n = 11
    span = (a1 - a0 - 0.04) / n
    for side in (-1, 1):
        for i in range(n):
            y0 = a0 + 0.02 + span * i + span * 0.22
            m.box(side * 0.1205 - 0.0008, side * 0.1205 + 0.0008, y0, y0 + span * 0.56, 0.94, 1.25, c["glass"])
        m.text(LETTERS, side * 0.1216, 0, 0.75, 0.035, c["letters"], side)
    _clerestory(m, a0 - 0.008, a1 + 0.008, 1.42, body, c["roof"], c["glass"])
    _platforms(m, L, a0, a1, c)


def _base(m, L, a0, a1, c):
    underframe(m, -L / 2 + 0.01, L / 2 - 0.01, c)
    for y in (-L / 2 + 0.085, L / 2 - 0.085):
        truck(m, y, c)


def _vestibules(m, a0, a1, mat):
    """Dark rubber diaphragms on the closed vestibule ends, where cars meet."""
    for y0, y1 in ((a0 - 0.006, a0), (a1, a1 + 0.006)):
        m.box(-0.075, 0.075, y0, y1, 0.66, 1.38, mat, bevel=0.002)


def _vents(m, a0, a1, z, mat, n=7):
    """A row of small roof ventilators along the crown, z steps up."""
    for k in range(n):
        y = a0 + 0.06 + (a1 - a0 - 0.12) * k / (n - 1)
        m.box(-0.016, 0.016, y - 0.012, y + 0.012, z - 0.01, z + 0.05, mat, bevel=0.004)


def coach_steel(m, L):
    """A heavyweight steel coach: green with a cream window band, windows in pairs, closed vestibules and a thin
    low roof rounded down over the ends."""
    c = common()
    green = material("passenger green", "#2c4c38", rough=0.35, clearcoat=0.5)
    cream = material("cream", "#e6dcc0", rough=0.4, clearcoat=0.4)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    _base(m, L, a0, a1, c)
    m.box(-0.12, 0.12, a0, a1, 0.62, 0.92, green, bevel=0.004)
    m.box(-0.1202, 0.1202, a0 + 0.004, a1 - 0.004, 0.92, 1.27, cream, bevel=0.002)
    m.box(-0.12, 0.12, a0, a1, 1.27, 1.4, green, bevel=0.004)
    _arch(m, _hooded(a0 - 0.002, a1 + 0.002, 1.4, 0.123, 0.11), c["coach roof"])
    _vents(m, a0, a1, 1.4 + 0.02 + 0.11, c["roof"])
    _vestibules(m, a0, a1, c["iron"])
    pairs = 8
    span = (a1 - a0 - 0.08) / pairs
    for side in (-1, 1):
        for i in range(pairs):
            for k in range(2):
                y0 = a0 + 0.04 + span * i + span * (0.1 + 0.42 * k)
                m.box(side * 0.1207 - 0.0008, side * 0.1207 + 0.0008, y0, y0 + span * 0.36, 0.98, 1.22, c["glass"])
        for y in (a0 + 0.018, a1 - 0.018):
            m.box(side * 0.1207 - 0.0008, side * 0.1207 + 0.0008, y - 0.01, y + 0.01, 0.7, 1.3, material("door", "#1f2a22", rough=0.5))
        m.box(side * 0.1205 - 0.001, side * 0.1205 + 0.001, a0 + 0.03, a1 - 0.03, 0.88, 0.9, material("gold line", "#c9a640", metal=1.0, rough=0.3))
        m.text(LETTERS, side * 0.1212, 0, 0.75, 0.035, c["letters"], side)


def baggage(m, L, steel):
    """A baggage car to run with the coaches: the steel one with the steel coach's roof and vestibules, the wooden one
    with the wooden coach's clerestory; two wide doors a side and few windows."""
    c = common()
    body = material("passenger green", "#2c4c38", rough=0.35, clearcoat=0.5) if steel else material("varnished wood", "#7a3a1e", rough=0.4, clearcoat=0.6)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    _base(m, L, a0, a1, c)
    m.box(-0.12, 0.12, a0, a1, 0.62, 1.4, body, bevel=0.004)
    if steel:
        _arch(m, _hooded(a0 - 0.002, a1 + 0.002, 1.4, 0.123, 0.11), c["coach roof"])
        _vents(m, a0, a1, 1.4 + 0.02 + 0.11, c["roof"])
        _vestibules(m, a0, a1, c["iron"])
    else:
        _clerestory(m, a0 - 0.008, a1 + 0.008, 1.4, body, c["roof"], c["glass"], windows=8)
        trim = material("dark wood", "#4a2414", rough=0.5)
        for side in (-1, 1):
            m.box(side * 0.12 - 0.002, side * 0.12 + 0.002, a0 + 0.005, a1 - 0.005, 1.27, 1.3, trim)
    door = material("door", "#1f2a22" if steel else "#4a2414", rough=0.5)
    for side in (-1, 1):
        for dy in (-0.09, 0.09):
            m.box(side * 0.1205 - 0.001, side * 0.1205 + 0.001, dy - 0.04, dy + 0.04, 0.68, 1.32, door)
        for y in (a0 + 0.045, a1 - 0.045):
            m.box(side * 0.1207 - 0.0008, side * 0.1207 + 0.0008, y - 0.018, y + 0.018, 1.02, 1.24, c["glass"])
        m.text("MAIL", side * 0.1212, 0, 1.0, 0.03, c["letters"], side)


def _freight_roof(m, a0, a1, top, mat, walk=True):
    """A freight car's thin roof, barely over the sides, with a roofwalk down the middle."""
    _arch(m, [(a0 - 0.003, top, 0.123, 0.035), (a1 + 0.003, top, 0.123, 0.035)], mat, edge=0.018)
    if walk:
        m.box(-0.022, 0.022, a0, a1, top + 0.04, top + 0.07, material("roof walk", "#4a3426", rough=0.8))


def _ladders(m, a0, a1, top, mat):
    """Ladders up the sides at diagonally opposite corners, and a brake wheel on the B end."""
    for side, y in ((1, a1 - 0.012), (-1, a0 + 0.012)):
        for dy in (-0.007, 0.007):
            m.rod((side * 0.123, y + dy, 0.66), (side * 0.123, y + dy, top - 0.04), 0.0015, mat)
        for k in range(1, 7):
            z = 0.66 + (top - 0.7) * k / 7
            m.rod((side * 0.123, y - 0.007, z), (side * 0.123, y + 0.007, z), 0.0012, mat)
    m.rod((0, a1 + 0.004, top - 0.14), (0, a1 + 0.004, top - 0.04), 0.002, mat)
    m.tube(a1 + 0.004, a1 + 0.008, 0, top - 0.04, 0.016, mat, segments=12)


def boxcar(m, L, steel):
    """A boxcar: the wooden one with upright boards and outside bracing, the steel one browner, with outside posts
    and ribbed ends; a sliding door, thin roof and roofwalk on both."""
    c = common()
    body = material("freight brown", "#6a3826", rough=0.7) if steel else material("freight red", "#8a3a2a", rough=0.7)
    dark = material("freight brown dark", "#45241a", rough=0.7) if steel else material("freight red dark", "#5e261c", rough=0.7)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    top = 1.5
    _base(m, L, a0, a1, c)
    m.box(-0.12, 0.12, a0, a1, 0.62, top, body, bevel=0.003)
    _freight_roof(m, a0, a1, top, dark)
    for side in (-1, 1):
        x = side * 0.1208
        m.box(x - 0.001, x + 0.001, -0.05, 0.05, 0.66, top - 0.05, dark)
        m.rod((side * 0.1218, -0.052, top - 0.03), (side * 0.1218, 0.052, top - 0.03), 0.003, c["iron"])
        if steel:
            for k in range(1, 9):
                y = a0 + (a1 - a0) * k / 9
                if abs(y) > 0.06:
                    m.box(side * 0.1206 - 0.0015, side * 0.1206 + 0.0015, y - 0.003, y + 0.003, 0.64, top - 0.02, dark)
        else:
            # Upright boards, then the outside braces: posts and diagonals each side of the door.
            for k in range(1, 30):
                y = a0 + (a1 - a0) * k / 30
                if abs(y) > 0.052:
                    m.box(side * 0.1202 - 0.0004, side * 0.1202 + 0.0004, y - 0.0012, y + 0.0012, 0.64, top - 0.02, dark)
            for y0, y1 in ((a0 + 0.005, -0.055), (0.055, a1 - 0.005)):
                for y in (y0, (y0 + y1) / 2, y1):
                    m.box(side * 0.1212 - 0.002, side * 0.1212 + 0.002, y - 0.004, y + 0.004, 0.64, top - 0.02, dark)
                for ya, yb in ((y0, (y0 + y1) / 2), ((y0 + y1) / 2, y1)):
                    m.rod((side * 0.1214, ya, 0.66), (side * 0.1214, yb, top - 0.04), 0.0025, dark)
        m.text(LETTERS, side * 0.1222, (a0 - 0.05) / 2 - 0.01, 1.2, 0.03, material("white letters", "#e8e4dc", rough=0.6), side)
    if steel:
        # Dreadnaught ends: ribs across each end.
        for y in (a0, a1):
            d = -0.002 if y == a0 else 0.002
            for k in range(1, 6):
                z = 0.62 + (top - 0.62) * k / 6
                m.box(-0.115, 0.115, min(y, y + d), max(y, y + d), z - 0.02, z + 0.02, dark, bevel=0.001)
    _ladders(m, a0, a1, top, c["iron"])


def flatcar(m, L):
    c = common()
    deck = material("deck wood", "#6a5236", rough=0.8)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    for y in (-L / 2 + 0.085, L / 2 - 0.085):
        truck(m, y, c)
    m.box(-0.12, 0.12, a0, a1, 0.45, 0.68, deck, bevel=0.003)
    for side in (-1, 1):
        for k in range(5):
            y = a0 + 0.02 + (a1 - a0 - 0.04) * k / 4
            m.box(side * 0.114 - 0.007, side * 0.114 + 0.007, y - 0.007, y + 0.007, 0.68, 1.0, material("stake", "#4a3a2a", rough=0.8))
    # A load of timber.
    logs = material("logs", "#8a6a44", rough=0.9)
    for x in (-0.06, 0.0, 0.06):
        m.tube(a0 + 0.02, a1 - 0.02, x, 0.85, 0.028, logs, segments=12)
    for x in (-0.03, 0.03):
        m.tube(a0 + 0.03, a1 - 0.03, x, 1.07, 0.028, logs, segments=12)


def gondola(m, L):
    """A steel gondola: low ribbed sides, open, with its floor showing inside."""
    c = common()
    body = material("gondola", "#3a3634", rough=0.7)
    rib = material("gondola rib", "#2a2725", rough=0.7)
    inside = material("gondola inside", "#5a4c40", rough=0.9)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    top = 0.94
    _base(m, L, a0, a1, c)
    m.box(-0.115, 0.115, a0 + 0.008, a1 - 0.008, 0.6, 0.68, inside)
    for side in (-1, 1):
        m.box(side * 0.112, side * 0.12, a0, a1, 0.62, top, body, bevel=0.002)
        m.box(side * 0.108, side * 0.112, a0, a1, 0.66, top, inside)
        for k in range(1, 10):
            y = a0 + (a1 - a0) * k / 10
            m.box(side * 0.1206 - 0.0015, side * 0.1206 + 0.0015, y - 0.003, y + 0.003, 0.62, top - 0.01, rib)
        m.box(side * 0.12, side * 0.1225, a0, a1, top - 0.03, top + 0.005, rib)
        m.text(LETTERS, side * 0.1232, a0 / 2, 0.79, 0.026, material("white letters", "#e8e4dc", rough=0.6), side)
    for y0, y1 in ((a0, a0 + 0.008), (a1 - 0.008, a1)):
        m.box(-0.12, 0.12, y0, y1, 0.62, top, body, bevel=0.002)
        m.box(-0.11, 0.11, y0 + (0.004 if y0 == a0 else -0.004), y1 + (0.004 if y0 == a0 else -0.004), 0.66, top - 0.005, inside)


def _plate(m, poly, x0, x1, mat):
    """A flat plate standing across from x0 to x1, its outline poly given as (y, z in steps)."""
    m.wedge([(x0, y, z) for y, z in poly], [(x1, y, z) for y, z in poly], mat)


def _slope(m, y_top, z_top, y_bot, z_bot, half, mat, t=0.008):
    """A sloping end sheet across the car, from y_top at z_top steps down to y_bot at z_bot."""
    d = t if y_bot < y_top else -t
    m.wedge([(-half, y_top, z_top), (half, y_top, z_top), (half, y_bot, z_bot), (-half, y_bot, z_bot)],
            [(-half, y_top - d, z_top), (half, y_top - d, z_top), (half, y_bot - d, z_bot), (-half, y_bot - d, z_bot)], mat)


def _heap(m, y0, y1, half, z0, z1, mat, n=6):
    """A heap of loose load from y0 to y1, z0 steps at its edges up to z1 along the middle, lower at the ends."""
    secs = []
    for k in range(n + 1):
        t = k / n
        f = math.sin(math.pi * t) ** 0.35
        y = y0 + (y1 - y0) * t
        zt = z0 + (z1 - z0) * f
        ring = [(-half, z0), (half, z0)] + [(half * math.cos(math.pi * i / 6), z0 + (zt - z0) * math.sin(math.pi * i / 6) ** 0.7) for i in range(1, 6)]
        secs.append((y, ring))
    m.loft(secs, {"load": mat}, sharp=70)


def hopper(m, L):
    """A two-bay open coal hopper: straight sides with outside ribs, ends sloping down to the bays and coal heaped
    in the open top."""
    c = common()
    body = material("hopper black", "#2e2e30", rough=0.6)
    rib = material("hopper rib", "#222224", rough=0.6)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    top = 1.5
    for y in (-L / 2 + 0.075, L / 2 - 0.075):
        truck(m, y, c, top=0.86)
    for side in (-1, 1):
        x = side * 0.12
        _plate(m, [(a0, top), (a1, top), (a1, 1.25), (a1 - 0.095, 0.86), (a0 + 0.095, 0.86), (a0, 1.25)], x - side * 0.006, x, body)
        for y in (-0.105, -0.035, 0.035, 0.105):
            m.box(x - 0.0015 + side * 0.0015, x + 0.0015 + side * 0.0015, y - 0.004, y + 0.004, 0.86, top, rib)
        m.box(x, x + side * 0.003, a0, a1, top - 0.03, top, rib)
        m.text(LETTERS, side * 0.1236, 0, 1.2, 0.03, material("white letters", "#e8e4dc", rough=0.6), side)
    for end in (a0, a1):
        inward = 1 if end == a0 else -1
        m.box(-0.12, 0.12, min(end, end + inward * 0.008), max(end, end + inward * 0.008), 1.36, top, body)
        _slope(m, end, 1.4, end + inward * 0.135, 0.84, 0.115, body)
        # The end sill and platform over the truck, down to the coupler.
        m.box(-0.11, 0.11, min(end, end + inward * 0.04), max(end, end + inward * 0.04), 0.5, 0.62, c["iron"])
        m.box(-0.025, 0.025, min(end, end + inward * 0.1), max(end, end + inward * 0.1), 0.5, 0.86, c["iron"])
    # Two bays sloping down to their chutes.
    for cy in (-0.07, 0.07):
        m.wedge([(-0.06, cy - 0.03, 0.38), (0.06, cy - 0.03, 0.38), (0.06, cy + 0.03, 0.38), (-0.06, cy + 0.03, 0.38)],
                [(-0.115, cy - 0.065, 0.86), (0.115, cy - 0.065, 0.86), (0.115, cy + 0.065, 0.86), (-0.115, cy + 0.065, 0.86)], body)
    m.box(-0.114, 0.114, a0 + 0.06, a1 - 0.06, 0.86, 1.3, body)
    _heap(m, a0 + 0.008, a1 - 0.008, 0.114, 1.4, 1.6, material("coal", "#161616", rough=0.95))


def tank_car(m, L):
    c = common()
    body = material("tank black", "#262628", rough=0.35, clearcoat=0.4)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    _base(m, L, a0, a1, c)
    m.tube(a0 + 0.01, a1 - 0.01, 0, 1.15, 0.105, body, segments=32)
    m.upright(0, 0, 1.55, 1.82, 0.04, 0.04, body)
    m.box(-0.125, 0.125, a0, a1, 0.62, 0.66, c["iron"])
    for side in (-1, 1):
        m.rod((side * 0.112, a0 + 0.01, 1.2), (side * 0.112, a1 - 0.01, 1.2), 0.003, c["iron"])
        m.text("OIL", side * 0.1062, 0, 1.15, 0.03, material("white letters", "#e8e4dc", rough=0.6), side)


def stock_car(m, L):
    """A slatted stock car: open slats with daylight between them, a door in the middle, thin roof and roofwalk."""
    c = common()
    body = material("stock car", "#7a5a3a", rough=0.8)
    post = material("stock car post", "#5a3e26", rough=0.8)
    inside = material("stock car floor", "#2a2016", rough=0.95)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    top = 1.48
    _base(m, L, a0, a1, c)
    m.box(-0.12, 0.12, a0, a1, 0.6, 0.68, inside)
    for y0, y1 in ((a0, a0 + 0.01), (a1 - 0.01, a1)):
        m.box(-0.12, 0.12, y0, y1, 0.62, top, body, bevel=0.002)
    m.box(-0.12, 0.12, a0, a1, top - 0.08, top, body)
    for side in (-1, 1):
        x = side * 0.117
        m.box(x - 0.003, x + 0.003, a0, a1, 0.62, 0.72, body)
        for k in range(6):
            z = 0.78 + k * 0.105
            m.box(x - 0.003, x + 0.003, a0, a1, z, z + 0.055, body)
        for k in range(9):
            y = a0 + (a1 - a0) * k / 8
            if abs(y) > 0.06 or abs(y) < 0.001:
                m.box(x + side * 0.003, x + side * 0.007, y - 0.005, y + 0.005, 0.62, top - 0.02, post)
        for y in (-0.055, 0.055):
            m.box(x + side * 0.003, x + side * 0.008, y - 0.006, y + 0.006, 0.64, top - 0.04, post)
        m.rod((side * 0.1218, -0.058, top - 0.04), (side * 0.1218, 0.058, top - 0.04), 0.003, c["iron"])
        m.text(LETTERS, side * 0.1226, (a0 - 0.05) / 2 - 0.01, top - 0.04, 0.024, material("white letters", "#e8e4dc", rough=0.6), side)
    _freight_roof(m, a0, a1, top, c["roof"])
    _ladders(m, a0, a1, top, c["iron"])


def caboose(m, L):
    """A wooden van: a short red body with a window near each end, a low wide cupola a third of its length with
    windows all round, a stovepipe, thin roofs and open end platforms."""
    c = common()
    red = material("caboose red", "#9a2e24", rough=0.5, clearcoat=0.3)
    trim = material("caboose trim", "#e8dcc0", rough=0.5)
    a0, a1 = -L / 2 + 0.035, L / 2 - 0.035
    top = 1.32
    underframe(m, -L / 2 + 0.01, L / 2 - 0.01, c)
    for y in (-L / 2 + 0.085, L / 2 - 0.085):
        truck(m, y, c)
    m.box(-0.12, 0.12, a0, a1, 0.62, top, red, bevel=0.003)
    _arch(m, [(a0 - 0.012, top, 0.124, 0.03), (a1 + 0.012, top, 0.124, 0.03)], c["roof"], edge=0.018)
    cl = 0.062
    m.box(-0.11, 0.11, -cl, cl, top + 0.02, 1.58, red, bevel=0.002)
    _arch(m, [(-cl - 0.008, 1.58, 0.118, 0.025), (cl + 0.008, 1.58, 0.118, 0.025)], c["roof"], edge=0.016)
    for side in (-1, 1):
        for y in (a0 + 0.04, a1 - 0.04):
            m.box(side * 0.1207 - 0.0008, side * 0.1207 + 0.0008, y - 0.015, y + 0.015, 0.92, 1.18, c["glass"])
        for y in (-0.03, 0.03):
            m.box(side * 0.1107 - 0.0008, side * 0.1107 + 0.0008, y - 0.019, y + 0.019, 1.42, 1.54, c["glass"])
        m.box(side * 0.1206 - 0.001, side * 0.1206 + 0.001, a0 + 0.005, a1 - 0.005, 0.66, 0.69, trim)
        m.text(LETTERS, side * 0.1214, 0, 1.05, 0.026, trim, side)
    for y in (-cl, cl):
        d = 0.0016 if y > 0 else -0.0016
        m.box(-0.05, 0.05, min(y, y + d), max(y, y + d), 1.42, 1.54, c["glass"])
    m.upright(0.06, a1 - 0.05, top + 0.03, 1.62, 0.008, 0.008, c["iron"], segments=10)
    m.upright(0.06, a1 - 0.05, 1.6, 1.65, 0.013, 0.013, c["iron"], segments=10)
    _platforms(m, L, a0, a1, c, rail=1.08)
    # Ladders from each platform to the roof.
    for end in (a0, a1):
        for x in (-0.012, 0.012):
            m.rod((x - 0.05, end + (0.003 if end == a1 else -0.003), 0.66), (x - 0.05, end + (0.003 if end == a1 else -0.003), top + 0.03), 0.0015, c["iron"])


def reefer(m, L):
    """A wooden ice refrigerator car: a yellow body, ice hatches at each end of the roof and a plug door."""
    c = common()
    body = material("reefer yellow", "#d8b24a", rough=0.7)
    trim = material("reefer brown", "#5a3a26", rough=0.75)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    top = 1.5
    _base(m, L, a0, a1, c)
    m.box(-0.12, 0.12, a0, a1, 0.62, top, body, bevel=0.003)
    _freight_roof(m, a0, a1, top, trim)
    for y in (a0 + 0.03, a1 - 0.03):
        m.box(-0.055, 0.055, y - 0.018, y + 0.018, top + 0.03, top + 0.085, trim, bevel=0.002)
    for side in (-1, 1):
        for k in range(1, 28):
            y = a0 + (a1 - a0) * k / 28
            if abs(y) > 0.045:
                m.box(side * 0.1202 - 0.0004, side * 0.1202 + 0.0004, y - 0.0012, y + 0.0012, 0.64, top - 0.02, material("reefer boards", "#b8923a", rough=0.8))
        m.box(side * 0.1212 - 0.002, side * 0.1212 + 0.002, -0.04, 0.04, 0.7, top - 0.08, trim)
        m.text(LETTERS, side * 0.1222, a0 / 2 - 0.02, 1.22, 0.028, trim, side)
        m.text("REFRIGERATOR", side * 0.1222, a1 / 2 + 0.03, 1.22, 0.018, trim, side)
    _ladders(m, a0, a1, top, c["iron"])


def ore_car(m, L):
    """A short, heavy ore car: a steel hopper whose ends taper in to a single bay, open, its ore heaped red."""
    c = common()
    body = material("ore car rust", "#5a2e22", rough=0.6)
    rib = material("ore car rib", "#44221a", rough=0.6)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    top = 1.45
    for y in (-L / 2 + 0.07, L / 2 - 0.07):
        truck(m, y, c, top=0.96)
    for side in (-1, 1):
        x = side * 0.115
        _plate(m, [(a0 + 0.01, top), (a1 - 0.01, top), (0.07, 0.95), (-0.07, 0.95)], x - side * 0.006, x, body)
        m.box(x, x + side * 0.003, a0 + 0.01, a1 - 0.01, top - 0.03, top, rib)
        for y in (-0.04, 0.0, 0.04):
            m.box(x, x + side * 0.003, y - 0.005, y + 0.005, 0.95, top, rib)
        for y0, y1 in ((a0 + 0.02, -0.065), (a1 - 0.02, 0.065)):
            m.rod((x + side * 0.002, y0, top), (x + side * 0.002, y1, 0.97), 0.004, rib)
    for end in (a0, a1):
        inward = 1 if end == a0 else -1
        _slope(m, end + inward * 0.01, top, inward * -0.07, 0.95, 0.112, body)
        m.box(-0.1, 0.1, min(end, end + inward * 0.035), max(end, end + inward * 0.035), 0.48, 0.62, c["iron"])
        m.box(-0.025, 0.025, min(end, end + inward * 0.11), max(end, end + inward * 0.11), 0.5, 0.96, c["iron"])
    m.wedge([(-0.06, -0.035, 0.42), (0.06, -0.035, 0.42), (0.06, 0.035, 0.42), (-0.06, 0.035, 0.42)],
            [(-0.11, -0.07, 0.96), (0.11, -0.07, 0.96), (0.11, 0.07, 0.96), (-0.11, 0.07, 0.96)], body)
    _heap(m, a0 + 0.03, a1 - 0.03, 0.108, 1.3, 1.56, material("iron ore", "#7a3a2a", rough=0.95))


def covered_hopper(m, L):
    """A cylindrical grain car of the 1970s: a long round grey body, a trough hatch along the top and four bays. The
    body's section is drawn as it ends up after lowering: its upper half a true half circle the car's width across,
    the lower half flatter, so the car reads round with the height cut."""
    c = common()
    body = material("hopper grey", "#a8aaa6", rough=0.55)
    dark = material("hopper grey dark", "#7e807c", rough=0.6)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    for y in (-L / 2 + 0.08, L / 2 - 0.08):
        truck(m, y, c, top=0.91)
    # In lowered steps: the top where a 1.9 step tall car's comes down to, a 0.12 tile radius above the middle line.
    hw = 0.12
    top = m.z(1.9)
    up = hw / rig.RISE
    zc = top - up
    down = zc - m.z(0.74)

    def pt(a, dr=0.0, k=1.0, lift=0.0):
        """A point round the section at angle a, dr tiles out from the skin, the section shrunk by k; in builder steps."""
        r = (hw + dr) * k
        sz = math.sin(a)
        z = zc + lift + (up if sz > 0 else down) * (r / hw) * sz
        return (r * math.cos(a), m.fixed(z))

    angles = [2 * math.pi * i / 36 for i in range(36)]
    ring = [pt(a) for a in angles]
    small = [pt(a, k=0.84, lift=0.04) for a in angles]
    m.loft([(a0 + 0.012, small), (a0 + 0.03, ring), (a1 - 0.03, ring), (a1 - 0.012, small)], {"body": body}, sharp=30)
    for cy in (-0.125, -0.04, 0.04, 0.125):
        m.wedge([(-0.045, cy - 0.02, 0.4), (0.045, cy - 0.02, 0.4), (0.045, cy + 0.02, 0.4), (-0.045, cy + 0.02, 0.4)],
                [(-0.1, cy - 0.04, 0.98), (0.1, cy - 0.04, 0.98), (0.1, cy + 0.04, 0.98), (-0.1, cy + 0.04, 0.98)], dark)
    for end in (a0, a1):
        inward = 1 if end == a0 else -1
        _slope(m, end + inward * 0.03, 1.2, end + inward * 0.16, 0.9, 0.1, body)
        m.box(-0.12, 0.12, min(end, end + inward * 0.035), max(end, end + inward * 0.035), 0.86, 0.9, c["iron"])
        m.box(-0.025, 0.025, min(end, end + inward * 0.12), max(end, end + inward * 0.12), 0.48, 0.9, c["iron"])
        for side in (-1, 1):
            m.rod((side * 0.11, end, 0.9), (side * 0.11, end, 1.2), 0.002, c["iron"])
    # The trough hatch and its running board along the crown.
    crown = m.fixed(top)
    m.box(-0.02, 0.02, a0 + 0.04, a1 - 0.04, crown - 0.05, crown + 0.05, dark, bevel=0.003)
    m.box(-0.032, 0.032, a0 + 0.04, a1 - 0.04, crown + 0.04, crown + 0.055, material("hopper walk", "#5e605c", rough=0.7))
    # Hoops round the sides, from under the middle line up over the shoulder.
    arc = [-0.5 + 1.7 * i / 8 for i in range(9)]
    for side in (-1, 1):
        sa = [a if side > 0 else math.pi - a for a in arc]
        band = [pt(a, 0.002) for a in sa] + [pt(a, -0.004) for a in reversed(sa)]
        for y in (-0.21, -0.105, 0.0, 0.105, 0.21):
            m.loft([(y - 0.003, band), (y + 0.003, band)], {"rib": dark})
        m.text("GRAIN", side * 0.1222, 0.0525, m.fixed(zc + 0.05), 0.032, material("hopper letters", "#2a2a2a", rough=0.6), side)


def wedge_plow(m, L):
    """A Russell wedge plow: a broad steel wedge the full width of the car filling its front half, two flat sloped
    mouldboards meeting at a sharp prow that rises steeply from the rails to a flat top at the roof, then a short red
    cab with a small cupola."""
    c = common()
    body = material("plow red", "#8a2a20", rough=0.55)
    blade = material("plow blade", "#26282b", metal=0.5, rough=0.5)
    edge = material("plow edge", "#e8c030", rough=0.45)
    a0, a1 = -L / 2 + 0.02, L / 2 - 0.02
    top = 1.42
    back = -0.03
    # The frame under the cab only; under the wedge just the centre sill, so the wedge comes down to the rails clear.
    underframe(m, -L / 2 + 0.01, back + 0.01, c)
    m.box(-0.03, 0.03, back, L / 2 - 0.01, 0.45, 0.6, c["iron"])
    for y in (-L / 2 + 0.085, L / 2 - 0.085):
        truck(m, y, c)
    m.box(-0.12, 0.12, a0, back + 0.01, 0.62, top, body, bevel=0.003)
    _arch(m, [(a0 - 0.008, top, 0.124, 0.03), (back + 0.01, top, 0.124, 0.03)], c["roof"], edge=0.018)
    m.box(-0.07, 0.07, a0 + 0.05, a0 + 0.15, top + 0.02, 1.62, body, bevel=0.002)
    _arch(m, [(a0 + 0.044, 1.62, 0.076, 0.02), (a0 + 0.156, 1.62, 0.076, 0.02)], c["roof"], edge=0.015)
    for side in (-1, 1):
        m.box(side * 0.0707 - 0.0008, side * 0.0707 + 0.0008, a0 + 0.07, a0 + 0.13, 1.5, 1.58, c["glass"])
        m.box(side * 0.1207 - 0.0008, side * 0.1207 + 0.0008, a0 + 0.03, a0 + 0.07, 0.95, 1.2, c["glass"])
        m.text(LETTERS, side * 0.1216, (a0 + back) / 2 + 0.04, 1.0, 0.03, c["letters"], side)
    # The wedge: each mouldboard is the plane through the prow line (tip at the rails up to the top at prow) and the
    # bottom edge (tip out to half width wide at the cab), so sections are taken where that outline bends.
    tip, prow = a1 + 0.012, a1 - 0.035
    low, high = 0.42, top + 0.015
    wide = 0.142

    def sec(y):
        wb = max(0.004, wide * (tip - y) / (tip - back))
        if y <= prow:
            return [(-wb, low), (wb, low), (max(0.004, wide * (prow - y) / (tip - back)), high), (-max(0.004, wide * (prow - y) / (tip - back)), high)]
        zt = low + 0.05 + (high - low - 0.05) * (tip - y) / (tip - prow)
        return [(-wb, low), (wb, low), (0.004, zt), (-0.004, zt)]

    m.loft([(y, sec(y)) for y in (back, prow, tip)], {"blade": blade}, sharp=20)
    # A yellow line up the prow and along the top edges and the bottom so the wedge shows head on and against snow.
    w_back = wide * (prow - back) / (tip - back)
    m.rod((0, tip + 0.002, low + 0.05), (0, prow + 0.002, high + 0.004), 0.004, edge)
    for side in (-1, 1):
        m.rod((0, prow, high + 0.004), (side * w_back, back, high + 0.004), 0.003, edge)
        m.rod((0, tip, low + 0.02), (side * (wide + 0.002), back, low + 0.02), 0.004, edge)


def rotary_plow(m, L):
    """A steam rotary plow: a long red car with a black roof and stack, and a great fan in a black drum at the
    front, its lip painted yellow so it shows."""
    c = common()
    body = material("rotary red", "#8e2a22", rough=0.45, clearcoat=0.3)
    black = material("rotary black", "#1e1e20", rough=0.55)
    band = material("rotary cream", "#e8dcb8", rough=0.5)
    lip = material("rotary yellow", "#e8b020", rough=0.4)
    fan = material("rotary fan", "#c8ccd0", metal=0.8, rough=0.3)
    a0, a1 = -L / 2 + 0.02, L / 2 - 0.02
    _base(m, L, a0, a1, c)
    m.box(-0.125, 0.125, a0, a1 - 0.14, 0.62, 1.65, body, bevel=0.004)
    m.box(-0.132, 0.132, a0 - 0.01, a1 - 0.13, 1.65, 1.76, black, bevel=0.006)
    m.upright(0, a0 + 0.12, 1.76, 2.1, 0.024, 0.028, black, segments=12)
    m.upright(0, a0 + 0.12, 2.06, 2.12, 0.032, 0.032, black, segments=12)
    for side in (-1, 1):
        m.box(side * 0.1256 - 0.001, side * 0.1256 + 0.001, a0 + 0.01, a1 - 0.15, 1.08, 1.4, band)
        for y in (a0 + 0.06, a0 + 0.16, a0 + 0.26, a1 - 0.22):
            m.box(side * 0.1262 - 0.0008, side * 0.1262 + 0.0008, y - 0.025, y + 0.025, 1.12, 1.36, c["glass"])
        m.box(side * 0.1262 - 0.002, side * 0.1262 + 0.002, a0 + 0.01, a1 - 0.15, 0.66, 0.72, black)
    # The hood: a square front with the snow chute on top, a yellow rim round a dark recess, and the wheel in it:
    # broad pale blades round a steel hub. Radii in tiles, heights in steps.
    m.box(-0.135, 0.135, a1 - 0.14, a1 - 0.004, 0.55, 1.78, black, bevel=0.006)
    m.box(-0.045, 0.045, a1 - 0.1, a1 - 0.03, 1.78, 2.15, black, bevel=0.004)
    m.wedge([(-0.05, a1 - 0.1, 2.15), (0.05, a1 - 0.1, 2.15), (0.05, a1 - 0.03, 2.15), (-0.05, a1 - 0.03, 2.15)],
            [(-0.05, a1 - 0.16, 2.35), (0.05, a1 - 0.16, 2.35), (0.05, a1 - 0.1, 2.35), (-0.05, a1 - 0.1, 2.35)], black)
    m.tube(a1 - 0.006, a1 + 0.002, 0.0, 1.12, 0.132, lip, segments=40)
    m.tube(a1 - 0.004, a1 + 0.004, 0.0, 1.12, 0.12, black, segments=40)
    blades = 10
    for k in range(blades):
        ang = k * 2 * math.pi / blades
        pts = []
        for r, da in ((0.025, -0.16), (0.112, -0.2), (0.112, 0.2), (0.025, 0.16)):
            a = ang + da
            pts.append((r * math.cos(a), r * math.sin(a) / rig.RISE))
        m.wedge([(x, a1 + 0.004, 1.12 + z) for x, z in pts], [(x, a1 + 0.011, 1.12 + z) for x, z in pts], fan)
    m.tube(a1 + 0.004, a1 + 0.016, 0.0, 1.12, 0.03, material("hub", "#e8e8e4", metal=0.9, rough=0.25), segments=20)
    m.text(LETTERS, 0.1266, a0 / 2, 0.92, 0.03, band, 1)
    m.text(LETTERS, -0.1266, a0 / 2, 0.92, 0.03, band, -1)


def coach_streamline(m, L):
    """A Budd stainless coach of the 1950s: fluted sides above and below the windows, a smooth rounded fluted roof,
    a long band of windows and skirts."""
    c = common()
    stainless = material("stainless", "#c4c8cc", metal=0.85, rough=0.28)
    flute = material("fluting", "#9ea2a6", metal=0.9, rough=0.3)
    band = material("coach band", "#6a2a26", rough=0.4, clearcoat=0.4)
    a0, a1 = -L / 2 + 0.012, L / 2 - 0.012
    _base(m, L, a0, a1, c)
    hw, eave, crown = 0.12, 1.42, 1.64

    def roof_pt(a):
        # A rounded roof: a superellipse from the eave at a = 0 over to the far eave at a = pi.
        cs, sn = math.cos(a), math.sin(a)
        return (hw * math.copysign(abs(cs) ** 0.6, cs), eave + (crown - eave) * abs(sn) ** 0.6)

    ring = [(-hw, 0.56), (hw, 0.56)] + [roof_pt(math.pi * i / 16) for i in range(17)]
    m.loft([(a0, ring), (a1, ring)], {"body": stainless}, sharp=60)
    _vestibules(m, a0, a1, c["iron"])
    for side in (-1, 1):
        x = side * (hw + 0.0015)
        for k in range(12):
            z = 0.6 + k * 0.03
            m.rod((x, a0 + 0.006, z), (x, a1 - 0.006, z), 0.0016, flute)
        for z in (1.34, 1.37, 1.4):
            m.rod((x, a0 + 0.006, z), (x, a1 - 0.006, z), 0.0016, flute)
        for i in range(1, 7):
            px, pz = roof_pt(math.pi * i / 16 if side > 0 else math.pi * (16 - i) / 16)
            m.rod((px * 1.012, a0 + 0.006, pz + 0.004), (px * 1.012, a1 - 0.006, pz + 0.004), 0.0016, flute)
        m.box(side * 0.1206 - 0.001, side * 0.1206 + 0.001, a0 + 0.01, a1 - 0.01, 1.28, 1.31, band)
        n = 11
        for i in range(n):
            y0 = a0 + 0.03 + (a1 - a0 - 0.06) * i / n
            m.box(side * 0.1208 - 0.0008, side * 0.1208 + 0.0008, y0, y0 + (a1 - a0 - 0.06) / n * 0.78, 1.0, 1.26, c["glass"])
        m.text(LETTERS, side * 0.1222, 0, 0.95, 0.026, band, side)


def _container(m, y0, y1, z0, z1, mat, frame):
    """A shipping container: ribbed sides, a corner frame and doors at the back end."""
    m.box(-0.1, 0.1, y0, y1, z0, z1, mat, bevel=0.002)
    for side in (-1, 1):
        n = int((y1 - y0) / 0.025)
        for j in range(1, n):
            y = y0 + (y1 - y0) * j / n
            m.box(side * 0.1005 - 0.0008, side * 0.1005 + 0.0008, y - 0.002, y + 0.002, z0 + 0.04, z1 - 0.04, frame)
        m.box(side * 0.1, side * 0.1015, y0, y1, z1 - 0.04, z1, frame)
        m.box(side * 0.1, side * 0.1015, y0, y1, z0, z0 + 0.04, frame)
    for y in (y0, y1):
        m.box(-0.102, 0.102, y - 0.004, y + 0.004, z0, z1, frame)


def well_car(m, L):
    """A double-stack well car: a low frame with thin side sills between the trucks, a short container in the well
    and a longer one stacked on it."""
    c = common()
    frame_mat = material("well car", "#3a3e42", rough=0.6)
    a0, a1 = -L / 2 + 0.01, L / 2 - 0.01
    for y in (-L / 2 + 0.07, L / 2 - 0.07):
        truck(m, y, c)
    for y0, y1 in ((a0, a0 + 0.1), (a1 - 0.1, a1)):
        m.box(-0.12, 0.12, y0, y1, 0.48, 0.74, frame_mat, bevel=0.003)
    for side in (-1, 1):
        m.box(side * 0.112, side * 0.12, a0 + 0.09, a1 - 0.09, 0.4, 0.7, frame_mat, bevel=0.002)
        m.box(side * 0.112, side * 0.124, a0 + 0.09, a1 - 0.09, 0.66, 0.72, frame_mat, bevel=0.002)
        for k in range(1, 8):
            y = a0 + 0.1 + (a1 - a0 - 0.2) * k / 8
            m.box(side * 0.12, side * 0.123, y - 0.004, y + 0.004, 0.42, 0.68, c["iron"])
    m.box(-0.112, 0.112, a0 + 0.1, a1 - 0.1, 0.38, 0.42, c["iron"])
    _container(m, a0 + 0.11, a1 - 0.11, 0.42, 1.3, material("container red", "#9a3a2a", rough=0.6), material("container red dark", "#6a2418", rough=0.7))
    _container(m, a0 + 0.04, a1 - 0.04, 1.32, 2.2, material("container blue", "#2a4a7a", rough=0.6), material("container blue dark", "#1a2e4e", rough=0.7))
    for side in (-1, 1):
        m.text("I.R. LINE", side * 0.1022, 0, 1.76, 0.04, material("white letters", "#e8e4dc", rough=0.6), side)


def coach_early(m, L):
    """A four-wheel coach of the 1830s and 1840s, like the Champlain and St. Lawrence's: a short carriage body of three
    compartments with its sides curving in below the waist, a door and drop window in each, a low arched roof with a
    luggage rail, on a wooden frame with spoked wheels and leaf springs."""
    c = common()
    body = material("early coach yellow", "#c89a34", rough=0.45, clearcoat=0.4)
    trim = material("early coach black", "#1e1a16", rough=0.5)
    frame_wood = material("early frame", "#3a2a1e", rough=0.7)
    a0, a1 = -L / 2 + 0.02, L / 2 - 0.02
    deck, waist, top = 0.66, 0.96, 1.36
    wheels = (a0 + 0.06, a1 - 0.06)
    # The frame, its sole bars out at the sides, and buffer beams at the ends.
    m.box(-0.112, 0.112, -L / 2 + 0.01, L / 2 - 0.01, 0.48, 0.6, frame_wood, bevel=0.002)
    for side in (-1, 1):
        for y in wheels:
            m.wheel(y, side, 0.034, (c["steel"], material("wheel dark", "#222224", rough=0.6), c["steel"]), spokes=10)
            m.box(side * 0.114 - 0.007, side * 0.114 + 0.007, y - 0.028, y + 0.028, 0.14, 0.5, c["iron"], bevel=0.002)
            m.box(side * 0.124 - 0.006, side * 0.124 + 0.006, y - 0.013, y + 0.013, 0.14, 0.28, material("journal", "#5a5c60", metal=0.4, rough=0.5))
            # A leaf spring over the axle box, its ends hung from the sole bar.
            m.box(side * 0.124 - 0.005, side * 0.124 + 0.005, y - 0.045, y + 0.045, 0.32, 0.38, c["iron"], bevel=0.002)
            m.box(side * 0.124 - 0.005, side * 0.124 + 0.005, y - 0.025, y + 0.025, 0.28, 0.32, c["iron"])
    # The body: upright above the waist, curving in below it to a narrower bottom, lofted so the ends round off.
    def ring(k):
        pts = [(-0.098 * k, deck), (0.098 * k, deck), (0.112 * k, deck + 0.08), (0.118 * k, deck + 0.18), (0.12 * k, waist),
               (0.12 * k, top), (-0.12 * k, top), (-0.12 * k, waist), (-0.118 * k, deck + 0.18), (-0.112 * k, deck + 0.08)]
        return pts
    m.loft([(a0, ring(0.94)), (a0 + 0.008, ring(1.0)), (a1 - 0.008, ring(1.0)), (a1, ring(0.94))], {"body": body}, sharp=30)
    _arch(m, [(a0 - 0.01, top, 0.126, 0.04), (a1 + 0.01, top, 0.126, 0.04)], c["roof"], edge=0.02)
    # A luggage rail round the roof, on short posts.
    rz = top + 0.13
    for x in (-0.1, 0.1):
        m.rod((x, a0 + 0.02, rz), (x, a1 - 0.02, rz), 0.0022, c["iron"])
        for k in range(5):
            y = a0 + 0.02 + (a1 - a0 - 0.04) * k / 4
            m.rod((x, y, top + 0.05), (x, y, rz), 0.0018, c["iron"])
    for y in (a0 + 0.02, a1 - 0.02):
        m.rod((-0.1, y, rz), (0.1, y, rz), 0.0022, c["iron"])
    # Three compartments a side: a door with a drop window in the middle of each, a small quarter window either side,
    # black mouldings at the waist and along the bottom and between them.
    n = 3
    span = (a1 - a0) / n
    for side in (-1, 1):
        x = side * 0.1206
        m.box(x - 0.0012, x + 0.0012, a0 + 0.006, a1 - 0.006, waist - 0.02, waist + 0.01, trim)
        m.box(x - 0.0012, x + 0.0012, a0 + 0.006, a1 - 0.006, top - 0.03, top, trim)
        for i in range(n + 1):
            y = a0 + span * i
            if 0 < i < n:
                m.box(x - 0.0014, x + 0.0014, y - 0.004, y + 0.004, deck + 0.15, top, trim)
        for i in range(n):
            yc = a0 + span * (i + 0.5)
            m.box(x - 0.0008, x + 0.0008, yc - 0.02, yc + 0.02, waist + 0.04, top - 0.06, c["glass"])
            for dy in (-1, 1):
                m.box(x - 0.0008, x + 0.0008, yc + dy * 0.05 - 0.015, yc + dy * 0.05 + 0.015, waist + 0.05, top - 0.07, c["glass"])
            # The door's outline and handle.
            for yy in (yc - 0.03, yc + 0.03):
                m.box(x - 0.0012, x + 0.0012, yy - 0.0015, yy + 0.0015, deck + 0.15, top - 0.04, trim)
            m.box(x - 0.002, x + 0.002, yc + 0.022, yc + 0.027, waist - 0.06, waist - 0.04, c["letters"])
        # Footboards along the side to step up to the doors.
        m.box(side * 0.125 - 0.008, side * 0.125 + 0.008, a0 + 0.03, a1 - 0.03, 0.5, 0.53, frame_wood)
    m.text("FIRST CLASS", 0.1218, 0, deck + 0.11, 0.016, c["letters"], 1)
    m.text("FIRST CLASS", -0.1218, 0, deck + 0.11, 0.016, c["letters"], -1)


def _lumber(m, y0, y1, z0, z1, half, mat, strap, n=4):
    """Wrapped bundles of lumber along a deck, stacked two high, with straps over them."""
    span = (y1 - y0) / n
    zm = (z0 + z1) / 2
    for i in range(n):
        ya, yb = y0 + span * i + 0.004, y0 + span * (i + 1) - 0.004
        for za, zb in ((z0, zm - 0.01), (zm, z1)):
            m.box(-half, half, ya, yb, za, zb, mat, bevel=0.003)
        for y in (ya + span * 0.25, yb - span * 0.25):
            m.box(-half - 0.002, half + 0.002, y - 0.003, y + 0.003, z0, z1 + 0.01, strap)


def bulkhead_flat(m, L):
    """A steel bulkhead flatcar of the 1950s on: a deep fish-belly side sill under a steel deck, tall braced
    bulkheads at both ends and wrapped lumber between them."""
    c = common()
    steel = material("flatcar steel", "#3e3a36", rough=0.65)
    dark = material("flatcar steel dark", "#2a2826", rough=0.7)
    a0, a1 = -L / 2 + 0.012, L / 2 - 0.012
    deck = 0.68
    for y in (-L / 2 + 0.08, L / 2 - 0.08):
        truck(m, y, c)
    # The side sills, deepest between the trucks.
    for side in (-1, 1):
        x0, x1 = side * 0.112, side * 0.122
        _plate(m, [(a0, deck), (a1, deck), (a1, deck - 0.1), (a1 - 0.14, deck - 0.1), (a1 - 0.2, deck - 0.26),
                   (a0 + 0.2, deck - 0.26), (a0 + 0.14, deck - 0.1), (a0, deck - 0.1)], min(x0, x1), max(x0, x1), steel)
        for k in range(1, 12):
            y = a0 + (a1 - a0) * k / 12
            m.box(side * 0.1225 - 0.0015, side * 0.1225 + 0.0015, y - 0.0025, y + 0.0025, deck - 0.1, deck, dark)
        m.text(LETTERS, side * 0.1232, -0.1, deck - 0.12, 0.024, material("white letters", "#e8e4dc", rough=0.6), side)
    m.box(-0.12, 0.12, a0, a1, deck - 0.04, deck, material("deck wood", "#6a5236", rough=0.8))
    m.box(-0.06, 0.06, a0 + 0.02, a1 - 0.02, 0.42, deck - 0.04, dark)
    # The bulkheads: a plate across with posts and a diagonal brace behind it.
    bt = 1.62
    for end in (a0, a1):
        inward = 1 if end == a0 else -1
        y0, y1 = sorted((end, end + inward * 0.012))
        m.box(-0.12, 0.12, y0, y1, deck, bt, steel, bevel=0.002)
        for x in (-0.115, -0.04, 0.04, 0.115):
            ya, yb = sorted((end - inward * 0.004, end + inward * 0.002))
            m.box(x - 0.006, x + 0.006, ya, yb, deck, bt + 0.01, dark)
        for z in (deck + 0.3, deck + 0.62, bt - 0.04):
            ya, yb = sorted((end - inward * 0.005, end))
            m.box(-0.12, 0.12, ya, yb, z - 0.015, z + 0.015, dark)
    _lumber(m, a0 + 0.016, a1 - 0.016, deck, 1.5, 0.108, material("lumber wrap", "#c4b48a", rough=0.75),
            material("lumber strap", "#4a4a4c", metal=0.5, rough=0.5))


def tank_domeless(m, L):
    """A long domeless tank car of the 1960s on: a slender tank with no frame under it, its heads dished, stub
    sills over the trucks, a small valve housing on a railed platform at the top, and a ladder up each side."""
    c = common()
    body = material("tank black", "#262628", rough=0.35, clearcoat=0.4)
    a0, a1 = -L / 2 + 0.012, L / 2 - 0.012
    zc, r = 1.1, 0.108
    rz = r / rig.RISE

    def circ(k, n=32):
        return [(r * k * math.cos(2 * math.pi * i / n), zc + rz * k * math.sin(2 * math.pi * i / n)) for i in range(n)]
    m.loft([(a0 + 0.004, circ(0.55)), (a0 + 0.012, circ(0.86)), (a0 + 0.026, circ(1.0)),
            (a1 - 0.026, circ(1.0)), (a1 - 0.012, circ(0.86)), (a1 - 0.004, circ(0.55))], {"body": body}, sharp=50, round=True)
    for y in (-L / 2 + 0.085, L / 2 - 0.085):
        truck(m, y, c)
    # Stub sills from the couplers back over the trucks, a saddle where each meets the tank.
    for end in (a0, a1):
        inward = 1 if end == a0 else -1
        ya, yb = sorted((end - inward * 0.01, end + inward * 0.13))
        m.box(-0.03, 0.03, ya, yb, 0.44, 0.62, c["iron"])
        ya, yb = sorted((end + inward * 0.06, end + inward * 0.11))
        m.box(-0.07, 0.07, ya, yb, 0.56, 0.7, c["iron"], bevel=0.003)
        ya, yb = sorted((end, end + inward * 0.03))
        m.box(-0.1, 0.1, ya, yb, 0.52, 0.6, c["iron"])
    # The valve housing on its railed platform at the top.
    mid, rr = m._circle(zc, r)
    crown = m.fixed(mid + rr / rig.RISE)
    m.box(-0.045, 0.045, -0.05, 0.05, crown - 0.03, crown + 0.012, material("tank walk", "#4a4a4c", metal=0.4, rough=0.6))
    m.upright(0, 0, crown, crown + 0.1, 0.024, 0.024, body, segments=16)
    m.upright(0, 0, crown + 0.1, crown + 0.13, 0.03, 0.03, body, segments=16)
    for x in (-0.045, 0.045):
        for y in (-0.05, 0.05):
            m.rod((x, y, crown + 0.01), (x, y, crown + 0.2), 0.0018, c["iron"])
        m.rod((x, -0.05, crown + 0.2), (x, 0.05, crown + 0.2), 0.0018, c["iron"])
    for side in (-1, 1):
        # A ladder up the tank's side to the platform.
        for dy in (-0.008, 0.008):
            m.rod((side * 0.113, dy, 0.62), (side * 0.06, dy, crown - 0.02), 0.0016, c["iron"])
        m.text("OIL", side * 0.11, -0.13, zc, 0.03, material("white letters", "#e8e4dc", rough=0.6), side)
        m.text(LETTERS, side * 0.11, 0.13, zc, 0.024, material("white letters", "#e8e4dc", rough=0.6), side)


def boxcar_50(m, L):
    """A 50 ft steel boxcar of the 1960s on: taller than the 40 ft car, outside posts, a wide plug door and a sliding
    door beside it, improved Dreadnaught ends and no roofwalk, the ladders cut short."""
    c = common()
    body = material("boxcar red", "#7a2a20", rough=0.65)
    dark = material("boxcar red dark", "#521c16", rough=0.7)
    a0, a1 = -L / 2 + 0.012, L / 2 - 0.012
    top = 1.6
    _base(m, L, a0, a1, c)
    m.box(-0.12, 0.12, a0, a1, 0.62, top, body, bevel=0.003)
    _freight_roof(m, a0, a1, top, dark, walk=False)
    doors = ((-0.1, 0.0), (0.0, 0.09))
    for side in (-1, 1):
        x = side * 0.1208
        for d0, d1 in doors:
            m.box(x - 0.0015, x + 0.0015, d0 + 0.004, d1 - 0.004, 0.64, top - 0.04, dark)
            for k in range(1, 4):
                z = 0.64 + (top - 0.68) * k / 4
                m.box(x - 0.0022, x + 0.0022, d0 + 0.008, d1 - 0.008, z - 0.006, z + 0.006, body)
        m.rod((side * 0.1222, -0.1, top - 0.03), (side * 0.1222, 0.09, top - 0.03), 0.003, c["iron"])
        m.rod((side * 0.1222, -0.1, 0.66), (side * 0.1222, 0.09, 0.66), 0.003, c["iron"])
        for k in range(1, 12):
            y = a0 + (a1 - a0) * k / 12
            if y < -0.105 or y > 0.095:
                m.box(side * 0.1206 - 0.0015, side * 0.1206 + 0.0015, y - 0.003, y + 0.003, 0.64, top - 0.02, dark)
        m.text(LETTERS, side * 0.1222, (a0 - 0.1) / 2, 1.3, 0.034, material("white letters", "#e8e4dc", rough=0.6), side)
        m.text("50 FT", side * 0.1222, (a1 + 0.09) / 2, 0.85, 0.018, material("white letters", "#e8e4dc", rough=0.6), side)
    # Improved Dreadnaught ends: fewer, deeper ribs.
    for y in (a0, a1):
        d = -0.003 if y == a0 else 0.003
        for k in range(1, 5):
            z = 0.62 + (top - 0.62) * k / 5
            m.box(-0.115, 0.115, min(y, y + d), max(y, y + d), z - 0.03, z + 0.03, dark, bevel=0.0015)
    # Short ladders, as cars built without a roofwalk had, and the brake wheel.
    for side, y in ((1, a1 - 0.012), (-1, a0 + 0.012)):
        for dy in (-0.007, 0.007):
            m.rod((side * 0.123, y + dy, 0.66), (side * 0.123, y + dy, 1.2), 0.0015, c["iron"])
        for k in range(1, 5):
            z = 0.66 + 0.54 * k / 5
            m.rod((side * 0.123, y - 0.007, z), (side * 0.123, y + 0.007, z), 0.0012, c["iron"])
    m.rod((0, a1 + 0.004, 1.1), (0, a1 + 0.004, 1.3), 0.002, c["iron"])
    m.tube(a1 + 0.004, a1 + 0.008, 0, 1.3, 0.016, c["iron"], segments=12)


def reefer_mech(m, L):
    """A steel mechanical refrigerator car of the 1960s to 1990s: smooth white insulated sides, a wide plug door,
    the diesel refrigeration unit behind a louvred grille at one end and its fuel tank under the frame, no ice
    hatches or roofwalk."""
    c = common()
    body = material("reefer white", "#e2e0d8", rough=0.5)
    band = material("reefer red", "#a8302a", rough=0.5)
    dark = material("reefer grey", "#6a6864", rough=0.6)
    a0, a1 = -L / 2 + 0.012, L / 2 - 0.012
    top = 1.6
    _base(m, L, a0, a1, c)
    m.box(-0.12, 0.12, a0, a1, 0.62, top, body, bevel=0.004)
    _freight_roof(m, a0, a1, top, dark, walk=False)
    unit = (a1 - 0.075, a1 - 0.01)
    for side in (-1, 1):
        x = side * 0.1208
        # The plug door, a little proud, with its bars.
        m.box(x - 0.0015, x + 0.0015, -0.064, 0.044, 0.64, top - 0.04, dark)
        m.box(x - 0.003, x + 0.003, -0.06, 0.04, 0.66, top - 0.06, body, bevel=0.001)
        for y in (-0.045, 0.025):
            m.rod((side * 0.1245, y, 0.7), (side * 0.1245, y, top - 0.1), 0.002, dark)
        m.rod((side * 0.1235, -0.06, top - 0.07), (side * 0.1235, 0.04, top - 0.07), 0.0025, c["iron"])
        m.box(x - 0.0012, x + 0.0012, a0 + 0.01, a1 - 0.01, 0.66, 0.72, band)
        m.box(x - 0.0012, x + 0.0012, a0 + 0.01, a1 - 0.01, top - 0.08, top - 0.04, band)
        # The refrigeration unit's grille at the B end.
        louvre_mat = material("reefer grille", "#3a3a3c", metal=0.3, rough=0.6)
        m.box(x - 0.0012, x + 0.0012, unit[0], unit[1], 0.74, 1.26, louvre_mat)
        for k in range(8):
            z = 0.76 + (1.24 - 0.76) * (k + 0.5) / 8
            m.box(x - 0.0018, x + 0.0018, unit[0] + 0.003, unit[1] - 0.003, z - 0.008, z + 0.008, dark)
        m.text(LETTERS, side * 0.1222, (a0 - 0.06) / 2, 1.25, 0.034, band, side)
        m.text("MECHANICAL REEFER", side * 0.1222, (a0 - 0.06) / 2, 0.92, 0.014, dark, side)
        # The fuel tank under the frame near the unit.
        m.box(min(side * 0.03, side * 0.095), max(side * 0.03, side * 0.095), unit[0] - 0.09, unit[0] - 0.01, 0.3, 0.46, c["iron"], bevel=0.006)
    _ladders(m, a0, a1, top, c["iron"])
