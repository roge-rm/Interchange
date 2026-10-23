"""Buses, trucks and streetcars, in our own colours: built facing +y, L tiles long, x across, z in steps.

A road vehicle is narrower and lower than a rail car: about 0.1 tile each side of its middle and 1.3 steps
tall for a bus. Wheels sit on the road, their tops a little over a step's fifth up.
"""
import math

import parts
import rig
from parts import material
from steam import LETTERS

WHEEL_R = 0.03


def common():
    return {
        "tire": material("tire", "#1e1e20", rough=0.85),
        "hub": material("hub", "#9a9a9a", metal=0.8, rough=0.35),
        "glass": material("glass", "#1e2a36", rough=0.1),
        "dark": material("chassis", "#222224", rough=0.7),
        "chrome": material("chrome", "#d8d8d8", metal=1.0, rough=0.15),
        "light": material("headlight", "#f4eed0", rough=0.2),
    }


def wheels(m, ys, c, half=0.07):
    """A wheel each side at each of [ys] along the vehicle."""
    zc = WHEEL_R / rig.RISE
    with m.keep():
        for y in ys:
            for side in (-1, 1):
                m.tube_x(side * half - 0.014, side * half + 0.014, y, zc, WHEEL_R, c["tire"])
                m.tube_x(side * (half + 0.0145) - 0.001, side * (half + 0.0145) + 0.001, y, zc, WHEEL_R * 0.55, c["hub"])


def windows(m, y0, y1, z0, z1, n, half, c, gap=0.3):
    for side in (-1, 1):
        for k in range(n):
            ya = y0 + (y1 - y0) * k / n
            yb = ya + (y1 - y0) / n * (1 - gap)
            m.box(side * half - 0.001, side * half + 0.001, ya, yb, z0, z1, c["glass"])


def fender(m, y, side, c, paint):
    """An arched mudguard over the wheel at y on side, as older vehicles have."""
    zc = WHEEL_R / rig.RISE
    ro, ri = WHEEL_R * 1.4, WHEEL_R * 1.12
    outer = [(y + math.cos(a) * ro, zc + math.sin(a) * ro / rig.RISE, 0, "paint") for a in [math.radians(d) for d in range(195, -20, -15)]]
    inner = [(y + math.cos(a) * ri, zc + math.sin(a) * ri / rig.RISE, 0, "paint") for a in [math.radians(d) for d in range(-15, 200, 15)]]
    with m.keep():
        m.shell(outer + inner, 0.016, {"paint": paint}, x=side * 0.072, cols=(-1, 1))


def lamps(m, outline, z, x, c):
    """A headlight each side x out, on the nose at height z."""
    y = parts.outline_front(outline, z)
    for side in (-1, 1):
        m.tube(y - 0.006, y + 0.003, side * x, z, 0.011, c["chrome"], segments=12)
        m.tube(y + 0.002, y + 0.004, side * x, z, 0.008, c["light"], segments=12)


def bus(m, L, era):
    """A city bus of its era: the 1920s motor bus with a hood, a rounded Brill, a New Look with its bulging windshield, a
    low-floor bus, or a battery bus with packs on its roof."""
    c = common()
    paint = {"motor": ("#6a2a26", "#e6dcc0"), "brill": ("#8a2a22", "#e6dcc0"), "newlook": ("#e6dcc0", "#2a5a8a"),
             "lowfloor": ("#e8e8e4", "#2a7a4a"), "electric": ("#e8e8e4", "#2a8ab0")}[era]
    body = material(f"{era} bus", paint[0], rough=0.4, clearcoat=0.3)
    band = material(f"{era} band", paint[1], rough=0.4)
    grille = material("grille", "#3a3836", metal=0.4, rough=0.5)
    mats = {"paint": body, "glass": c["glass"], "grille": grille}
    R, F = -L / 2 + 0.01, L / 2 - 0.01
    half = 0.078
    fl = 0.32 if era in ("lowfloor", "electric") else 0.42
    t = 1.15
    front_axle = F - (0.16 if era == "motor" else 0.09)
    wheels(m, [R + 0.08, front_axle], c)
    nose = F
    if era == "motor":
        # The engine out front under a narrower hood, and a flat windshield behind it.
        nose = F - 0.09
        hw = 0.68
        shape = [(R, fl, 0.015, "paint"), (nose, fl, 0, "paint"), (nose, fl, 0, "paint", hw), (F - 0.006, fl, 0.01, "grille", hw),
                 (F, 0.74, 0.03, "paint", hw), (nose, 0.84, 0, "paint", hw), (nose, 0.84, 0, "paint"),
                 (nose - 0.004, 0.9, 0.008, "glass"), (nose - 0.012, t - 0.05, 0.015, "paint"), (nose - 0.03, t + 0.05, 0.03, "paint"),
                 (R + 0.03, t + 0.05, 0.03, "paint"), (R, t - 0.08, 0.02, "paint")]
        crown = 0.06
        for side in (-1, 1):
            fender(m, front_axle, side, c, body)
    elif era == "brill":
        shape = [(R, fl, 0.02, "paint"), (F - 0.006, fl, 0.025, "paint", 0.9), (F + 0.004, fl + 0.25, 0.03, "paint", 0.9),
                 (F, fl + 0.4, 0.012, "glass", 0.9), (F - 0.024, t - 0.04, 0.035, "paint", 0.92), (F - 0.065, t + 0.06, 0.05, "paint"),
                 (R + 0.05, t + 0.06, 0.05, "paint"), (R, t - 0.12, 0.04, "paint")]
        crown = 0.1
    elif era == "newlook":
        shape = [(R, fl, 0.015, "paint"), (F - 0.004, fl, 0.02, "paint", 0.9), (F + 0.003, fl + 0.18, 0.03, "paint", 0.9),
                 (F, fl + 0.34, 0.015, "glass", 0.88), (F - 0.01, t - 0.03, 0.035, "paint", 0.9), (F - 0.035, t + 0.04, 0.03, "paint"),
                 (R + 0.02, t + 0.04, 0.03, "paint"), (R, t - 0.06, 0.02, "paint")]
        crown = 0.07
    else:
        shape = [(R, fl, 0.015, "paint"), (F, fl, 0.015, "paint", 0.94), (F + 0.003, fl + 0.28, 0.012, "glass", 0.94),
                 (F - 0.007, t - 0.02, 0.03, "paint", 0.94), (F - 0.022, t + 0.04, 0.02, "paint"),
                 (R + 0.012, t + 0.04, 0.02, "paint"), (R, t - 0.04, 0.015, "paint")]
        crown = 0.05
    outline = m.shell(shape, half, mats, crown=crown)
    for side in (-1, 1):
        m.box(side * half - 0.0015, side * half + 0.0015, R + 0.02, nose - 0.03, fl + 0.15, fl + 0.28, band)
    windows(m, R + 0.04, nose - 0.08 if era != "motor" else nose - 0.02, fl + 0.42 if fl < 0.4 else 0.82, t - 0.1, 5, half + 0.0005, c, gap=0.15)
    if era == "motor":
        lamps(m, outline, 0.86, 0.04, c)
    else:
        y = parts.outline_front(outline, fl + 0.16)
        m.box(-0.05, 0.05, y - 0.006, y + 0.002, fl + 0.12, fl + 0.2, band)
        lamps(m, outline, fl + 0.1, 0.055, c)
        m.box(-0.07, 0.07, F - 0.01, F + 0.008, fl - 0.04, fl + 0.04, c["dark"], bevel=0.004)
    if era == "electric":
        m.box(-0.055, 0.055, R + 0.06, F - 0.12, t + 0.03, t + 0.2, material("battery pods", "#c8ccd0", rough=0.5), bevel=0.012)
    m.text(LETTERS, half + 0.002, (R + nose) / 2, fl + 0.2, 0.025, body, 1)


def highway_coach(m, L):
    """An intercity coach: a high floor over the luggage bays, a band of tinted windows and a raked, rounded front."""
    c = common()
    body = material("coach silver", "#c4c8cc", metal=0.6, rough=0.3)
    band = material("coach blue", "#2a4a7a", rough=0.4)
    R, F = -L / 2 + 0.01, L / 2 - 0.01
    half = 0.078
    wheels(m, [R + 0.07, R + 0.13, F - 0.1], c)
    shape = [(R, 0.32, 0.015, "paint"), (F - 0.004, 0.32, 0.02, "paint", 0.92), (F + 0.004, 0.6, 0.025, "paint", 0.92),
             (F, 0.74, 0.012, "glass", 0.92), (F - 0.018, 1.24, 0.04, "paint", 0.93), (F - 0.055, 1.36, 0.04, "paint"),
             (R + 0.02, 1.36, 0.03, "paint"), (R, 1.2, 0.02, "paint")]
    outline = m.shell(shape, half, {"paint": body, "glass": c["glass"]}, crown=0.07)
    for side in (-1, 1):
        m.box(side * half - 0.0015, side * half + 0.0015, R + 0.01, F - 0.03, 0.5, 0.68, band)
        m.box(side * half - 0.001, side * half + 0.001, R + 0.03, F - 0.06, 0.85, 1.2, c["glass"])
        for k in range(3):
            y = R + 0.05 + k * 0.1
            m.box(side * half - 0.0012, side * half + 0.0012, y, y + 0.07, 0.4, 0.48, material("bay doors", "#9a9ea2", metal=0.5, rough=0.35))
    lamps(m, outline, 0.45, 0.055, c)
    m.box(-0.07, 0.07, F - 0.01, F + 0.006, 0.28, 0.36, c["dark"], bevel=0.004)
    m.text(LETTERS, half + 0.002, 0.0, 0.58, 0.025, c["chrome"], 1)


# How long each cab is, from its back to its bumper, in tiles.
CAB_LEN = {"early": 0.15, "round": 0.17, "square": 0.17, "long": 0.25, "sleeper": 0.32, "aero": 0.27, "cabover": 0.1}
# Each cab's roof, in steps, for bodies sized to it.
CAB_TOP = {"early": 0.9, "round": 0.9, "square": 0.96, "long": 1.34, "sleeper": 1.34, "aero": 1.62, "cabover": 1.32}


def _ring(m, x, y, zc, r, w, mat, n=20):
    """A thin hoop across x, r tiles round, made of n short bars w thick."""
    pts = [(x, y + math.cos(2 * math.pi * k / n) * r, zc + math.sin(2 * math.pi * k / n) * r / rig.RISE) for k in range(n + 1)]
    for p, q in zip(pts, pts[1:]):
        m.rod(p, q, w, mat, segments=6)


def spoked(m, ys, c, half=0.07):
    """Wooden-spoked wheels with thin tires, as trucks ran until the late 1920s."""
    zc = WHEEL_R / rig.RISE
    spoke = material("wheel spokes", "#9a7448", rough=0.75)
    with m.keep():
        for y in ys:
            for side in (-1, 1):
                x = side * half
                _ring(m, x, y, zc, WHEEL_R * 0.88, WHEEL_R * 0.12, c["tire"])
                _ring(m, x, y, zc, WHEEL_R * 0.72, WHEEL_R * 0.06, c["dark"])
                for k in range(10):
                    a = 2 * math.pi * k / 10
                    p = (x, y + math.cos(a) * WHEEL_R * 0.7, zc + math.sin(a) * WHEEL_R * 0.7 / rig.RISE)
                    m.rod((x, y, zc), p, 0.0026, spoke, segments=5)
                m.tube_x(x - 0.008, x + 0.008, y, zc, WHEEL_R * 0.22, c["hub"])
            m.rod((-half, y, zc), (half, y, zc), 0.004, c["dark"])


def guard(m, y1, c, top=0.9):
    """A moose bumper: a heavy guard of bars across the grille, for rural and northern roads."""
    for x in (-0.06, -0.025, 0.025, 0.06):
        m.box(x - 0.004, x + 0.004, y1 + 0.006, y1 + 0.014, 0.28, top, c["chrome"], bevel=0.002)
    for z in (0.34, top - 0.04):
        m.box(-0.072, 0.072, y1 + 0.008, y1 + 0.018, z - 0.03, z + 0.03, c["chrome"], bevel=0.003)


def _early_cab(m, y0, y1, c, paint):
    """A 1910s-20s C-cab: a short hood behind a tall narrow radiator, a flat upright windshield, open sides with C-shaped
    panels and no doors, under a thin roof, and black wings over the front wheels running back as running boards."""
    top = CAB_TOP["early"]
    hood = y1 - 0.065
    black = material("early black", "#1c1c1e", rough=0.5, clearcoat=0.3)
    leather = material("seat leather", "#3a2a22", rough=0.8)
    grille = material("radiator core", "#3a3836", metal=0.4, rough=0.5)
    # The hood, narrower than the cab, and the radiator standing a little over it.
    m.shell([(hood, 0.34, 0, "paint"), (y1 - 0.014, 0.34, 0, "paint"), (y1 - 0.014, 0.58, 0.008, "paint"), (hood, 0.6, 0, "paint")],
            0.04, {"paint": paint}, crown=0.03)
    m.box(-0.036, 0.036, y1 - 0.016, y1 - 0.004, 0.32, 0.68, black, bevel=0.003)
    m.box(-0.028, 0.028, y1 - 0.0045, y1 - 0.002, 0.37, 0.64, grille)
    m.upright(0.0, y1 - 0.01, 0.68, 0.72, 0.005, 0.005, c["chrome"])
    for side in (-1, 1):
        m.tube(y1 - 0.03, y1 - 0.012, side * 0.054, 0.6, 0.011, black, segments=12)
        m.tube(y1 - 0.012, y1 - 0.01, side * 0.054, 0.6, 0.008, c["light"], segments=12)
    # Floor, dash and the windshield, flat and upright.
    m.box(-0.068, 0.068, y0, hood, 0.32, 0.37, paint)
    m.box(-0.066, 0.066, hood - 0.006, hood, 0.34, 0.64, paint)
    m.box(-0.06, 0.06, hood - 0.004, hood - 0.002, 0.64, 0.86, c["glass"])
    m.box(-0.064, 0.064, hood - 0.005, hood - 0.001, 0.855, 0.875, black)
    # The C-shaped side panels, a post at the back of each side and the roof.
    for side in (-1, 1):
        m.shell([(y0, 0.37, 0, "paint"), (hood, 0.37, 0, "paint"), (hood, top, 0, "paint"), (hood - 0.032, top, 0.004, "paint"),
                 (hood - 0.016, 0.74, 0.02, "paint"), (hood - 0.034, 0.58, 0.006, "paint"), (y0, 0.56, 0.004, "paint")],
                0.003, {"paint": paint}, x=side * 0.067, cols=(-1, 1))
        m.box(side * 0.067 - 0.004, side * 0.067 + 0.004, y0, y0 + 0.008, 0.56, top, paint)
    m.shell([(y0 - 0.006, top - 0.02, 0.004, "paint"), (hood + 0.004, top - 0.02, 0.004, "paint"), (hood + 0.004, top + 0.02, 0.004, "paint"),
             (y0 - 0.006, top + 0.02, 0.004, "paint")], 0.073, {"paint": black}, crown=0.02)
    m.box(-0.06, 0.06, y0 + 0.004, y0 + 0.04, 0.37, 0.5, leather, bevel=0.004)
    m.box(-0.06, 0.06, y0 + 0.002, y0 + 0.014, 0.5, 0.7, leather, bevel=0.004)
    # Wings over the front wheels, sweeping down into flat running boards.
    yw, zc = y1 - 0.055, WHEEL_R / rig.RISE
    ro = WHEEL_R * 1.25
    upper = [(yw + math.cos(math.radians(d)) * ro, zc + math.sin(math.radians(d)) * ro / rig.RISE) for d in range(15, 166, 15)]
    upper += [(yw - ro * 1.5, 0.31), (y0 - 0.01, 0.31)]
    lower = [(y, z - 0.03) for y, z in reversed(upper)]
    with m.keep():
        for side in (-1, 1):
            m.shell([(y, z, 0, "paint") for y, z in upper + lower], 0.013, {"paint": black}, x=side * 0.075, cols=(-1, 1))
    return hood


def _cab(m, y0, y1, c, paint, style):
    """A truck's cab from y0 to its front at y1, by era: an open 1910s C-cab, a 1930s rounded hood with fenders, a squarer
    1960s hood with a wide grille, a long highway hood with stacks (with a sleeper behind as "sleeper"), a sloped aero hood
    with a tall roof, or a cab-over. Returns where the hood meets the cab."""
    if style == "early":
        return _early_cab(m, y0, y1, c, paint)
    half = 0.075
    grille = material("grille", "#3a3836", metal=0.4, rough=0.5)
    mats = {"paint": paint, "glass": c["glass"], "grille": grille}
    top = CAB_TOP[style]
    # Side glass from its sill to its head, in steps.
    win = (0.88, min(top, 1.36) - 0.1)
    if style == "round":
        # A Ford AA or Fargo of the 1930s: roof about 2.3 m and hood top about 1.6 m against 0.9 m wheels.
        hood = y1 - 0.09
        hw = 0.62
        shape = [(y0, 0.32, 0.01, "paint"), (hood, 0.32, 0, "paint"), (hood, 0.32, 0, "paint", hw), (y1 - 0.008, 0.32, 0.012, "grille", hw),
                 (y1, 0.55, 0.026, "paint", hw), (hood, 0.6, 0, "paint", hw), (hood, 0.6, 0, "paint"),
                 (hood - 0.003, 0.63, 0.006, "glass"), (hood - 0.012, 0.83, 0.012, "paint"), (hood - 0.026, top, 0.018, "paint"),
                 (y0, top, 0.012, "paint")]
        outline = m.shell(shape, half, mats, crown=0.05)
        lamps(m, outline, 0.48, 0.058, c)
        win = (0.64, top - 0.07)
    elif style == "square":
        hood = y1 - 0.1
        hw = 0.8
        shape = [(y0, 0.32, 0.01, "paint"), (hood, 0.32, 0, "paint"), (hood, 0.32, 0, "paint", hw), (y1 - 0.002, 0.32, 0.008, "grille", hw),
                 (y1, 0.58, 0.014, "paint", hw), (hood, 0.64, 0, "paint", hw), (hood, 0.64, 0, "paint"),
                 (hood - 0.002, 0.67, 0.006, "glass"), (hood - 0.008, 0.89, 0.01, "paint"), (hood - 0.02, top, 0.015, "paint"),
                 (y0, top, 0.012, "paint")]
        outline = m.shell(shape, half, mats, crown=0.04)
        lamps(m, outline, 0.5, 0.052, c)
        win = (0.68, top - 0.08)
    elif style in ("long", "sleeper"):
        hood = y1 - 0.16
        hw = 0.8
        back = [(y0 + 0.07, top, 0.01, "paint"), (y0 + 0.06, 1.52, 0.025, "paint"), (y0, 1.52, 0.015, "paint")] if style == "sleeper" else [(y0, top, 0.015, "paint")]
        shape = [(y0, 0.32, 0.01, "paint"), (hood, 0.32, 0, "paint"), (hood, 0.32, 0, "paint", hw), (y1 - 0.003, 0.32, 0.01, "grille", hw),
                 (y1, 0.86, 0.016, "paint", hw), (hood, 0.94, 0, "paint", hw), (hood, 0.94, 0, "paint"),
                 (hood - 0.003, 0.98, 0.006, "glass"), (hood - 0.012, 1.27, 0.012, "paint"), (hood - 0.026, top, 0.018, "paint")] + back
        outline = m.shell(shape, half, mats, crown=0.05)
        lamps(m, outline, 0.74, 0.055, c)
        for side in (-1, 1):
            m.tube_x(side * half - 0.002, side * half + 0.004, hood - 0.03, 0.42, 0.02, c["chrome"])
            m.rod((side * (half + 0.008), hood - 0.008, 0.6), (side * (half + 0.008), hood - 0.008, 1.75), 0.0055, c["chrome"])
    elif style == "aero":
        hood = y1 - 0.1
        hw = 0.9
        shape = [(y0, 0.32, 0.01, "paint"), (hood, 0.32, 0, "paint"), (hood, 0.32, 0, "paint", hw), (y1 - 0.006, 0.32, 0.015, "grille", hw),
                 (y1, 0.6, 0.02, "paint", hw), (hood + 0.01, 0.86, 0.035, "paint", hw), (hood, 0.9, 0, "paint", hw), (hood, 0.9, 0, "paint"),
                 (hood - 0.004, 0.94, 0.01, "glass"), (hood - 0.032, 1.3, 0.02, "paint"), (hood - 0.06, 1.42, 0.04, "paint"),
                 (y0 + 0.04, top, 0.04, "paint"), (y0, top, 0.015, "paint")]
        outline = m.shell(shape, half, mats, crown=0.06)
        lamps(m, outline, 0.52, 0.058, c)
        for side in (-1, 1):
            # Fairings between the steer wheels and the cab's back.
            m.box(side * half - 0.003, side * half + 0.001, y0 + 0.01, hood - 0.07, 0.3, 0.62, paint, bevel=0.004)
    else:
        hood = y1 - 0.005
        shape = [(y0, 0.32, 0.01, "paint"), (y1 - 0.004, 0.32, 0.015, "grille", 0.92), (y1, 0.64, 0.006, "paint", 0.92),
                 (y1 - 0.002, 0.78, 0.01, "glass", 0.92), (y1 - 0.016, 1.2, 0.03, "paint", 0.93), (y1 - 0.04, top, 0.03, "paint"),
                 (y0, top, 0.02, "paint")]
        outline = m.shell(shape, half, mats, crown=0.07)
        lamps(m, outline, 0.42, 0.058, c)
        # A wind fairing on the roof, sloping up from the windshield.
        m.shell([(y0 + 0.004, 1.3, 0, "paint"), (y1 - 0.045, 1.3, 0.01, "paint"), (y0 + 0.012, 1.46, 0.02, "paint"), (y0 + 0.004, 1.45, 0, "paint")],
                half - 0.006, {"paint": paint}, crown=0.05)
    if style in ("round", "square", "long"):
        for side in (-1, 1):
            fender(m, y1 - 0.045, side, c, paint)
    door = hood - 0.012 if style != "cabover" else y0 + 0.05
    for side in (-1, 1):
        m.box(side * half - 0.001, side * half + 0.001, door - 0.04, door, win[0] if style != "cabover" else 0.82, win[1], c["glass"])
    m.box(-0.068, 0.068, y1 - 0.004, y1 + 0.01, 0.28, 0.36, c["chrome"], bevel=0.003)
    return hood


# Each kind's paint, the load's colour, and its body's colour where that differs.
PAINTS = {"mail": "#b8322a", "stake": "#6a4a2a", "flatbed": "#2a5a8a", "dump": "#d8a020", "tank": "#d8d8d4", "log": "#3a5a2a",
          "cattle": "#8a2a22", "semi": "#c83a2a", "plow": "#e07a20"}


def rack(m, b0, b1, z0, top, boards, mat, post):
    """A stake rack from z0 up to top: posts in pockets along the sides and boards between them with gaps showing through,
    across the front and as a tailgate at the back."""
    pitch = (top - z0) / boards
    for k in range(boards):
        za, zb = z0 + pitch * (k + 0.45), z0 + pitch * (k + 1)
        for side in (-1, 1):
            m.box(side * 0.076 - 0.003, side * 0.076 + 0.003, b0, b1, za, zb, mat)
        for y in (b0, b1 - 0.006):
            m.box(-0.076, 0.076, y, y + 0.006, za, zb, mat)
    n = max(3, round((b1 - b0) / 0.04) + 1)
    for side in (-1, 1):
        for k in range(n):
            y = b0 + 0.004 + (b1 - b0 - 0.008) * k / (n - 1)
            m.box(side * 0.079 - 0.004, side * 0.079 + 0.004, y - 0.004, y + 0.004, z0 - 0.06, top + 0.02, post)


def truck(m, L, kind, cab="round", gen=1, plow="wedge"):
    """A truck: a cab of its era and a body for its load, sized to the cab's roof. Later generations run more axles.
    A plow truck's plow is a V plow ("wedge") or a one-way plow with a side wing ("wing")."""
    c = common()
    paint = material(f"{kind} {cab} truck", PAINTS[kind], rough=0.45, clearcoat=0.2)
    wood = material("truck boards", "#8a6a44", rough=0.85)
    a0, a1 = -L / 2 + 0.01, L / 2 - 0.01
    cab0 = a1 - CAB_LEN[cab]
    top = CAB_TOP[cab]
    if kind == "semi":
        wheels(m, [a1 - 0.06, cab0 - 0.04, a0 + 0.06, a0 + 0.12], c)
    else:
        rear = [a0 + 0.07] + ([a0 + 0.13] if L > 0.45 or gen > 1 else [])
        if gen > 2:
            rear += [cab0 - 0.04]
        (spoked if cab == "early" else wheels)(m, [a1 - 0.055] + rear, c)
    m.box(-0.06, 0.06, a0, a1, 0.25, 0.35, c["dark"])
    _cab(m, cab0, a1 - (0.005 if cab == "cabover" else 0.0), c, paint, cab)
    if gen == 3:
        guard(m, a1, c)
    b0, b1 = a0 + 0.005, cab0 - 0.012
    if kind == "mail":
        # A closed box no higher than the cab's roof, rounded over the top.
        m.shell([(b0, 0.38, 0.006, "paint"), (b1, 0.38, 0.006, "paint"), (b1, top, 0.02, "paint"), (b0, top, 0.02, "paint")],
                0.078, {"paint": paint}, crown=0.04)
        m.text(LETTERS, 0.0792, (b0 + b1) / 2, (0.38 + top) / 2, 0.022, material("mail letters", "#f0f0e8", rough=0.5), 1)
    elif kind == "stake":
        m.box(-0.078, 0.078, b0, b1, 0.38, 0.44, wood)
        m.box(-0.062, 0.062, b0 + 0.02, b1 - 0.02, 0.44, 0.66, material("crates", "#a88a5a", rough=0.8), bevel=0.01)
        rack(m, b0, b1, 0.44, top, 3, wood, material("stakes", "#6a5034", rough=0.85))
    elif kind == "flatbed":
        # A steel deck with a headboard, and a strapped load of lumber and pallets.
        m.box(-0.078, 0.078, b0, b1, 0.36, 0.44, c["dark"], bevel=0.003)
        m.box(-0.074, 0.074, b1 - 0.008, b1, 0.44, top - 0.02, c["hub"], bevel=0.002)
        m.box(-0.066, 0.066, b0 + 0.01, (b0 + b1) / 2 - 0.006, 0.44, 0.74, material("lumber", "#d8b880", rough=0.85), bevel=0.004)
        m.box(-0.066, 0.066, (b0 + b1) / 2 + 0.006, b1 - 0.02, 0.44, 0.7, material("tarp", "#4a6a3a", rough=0.7), bevel=0.012)
    elif kind == "dump" or kind == "plow":
        load = material("load" if kind == "dump" else "sand", "#3a3a3a" if kind == "dump" else "#a89878", rough=0.95)
        if gen == 1:
            # A low open box, its rim a little over the hood, on a subframe with the hoist under its front.
            rim = top - 0.24
            for side in (-1, 1):
                m.box(side * 0.045 - 0.006, side * 0.045 + 0.006, b0, b1, 0.35, 0.4, c["dark"])
            m.upright(0.0, b1 - 0.03, 0.3, 0.45, 0.013, 0.013, c["hub"])
            m.box(-0.078, 0.078, b0, b1, 0.44, 0.48, paint, bevel=0.003)
            for side in (-1, 1):
                m.box(side * 0.076 - 0.003, side * 0.076 + 0.003, b0, b1, 0.44, rim, paint, bevel=0.002)
                for k in range(4):
                    y = b0 + 0.012 + (b1 - b0 - 0.024) * k / 3
                    m.box(side * 0.079 - 0.002, side * 0.079 + 0.002, y - 0.004, y + 0.004, 0.44, rim, paint)
            m.box(-0.078, 0.078, b1 - 0.008, b1, 0.44, rim + 0.05, paint, bevel=0.002)
            m.box(-0.076, 0.076, b0, b0 + 0.006, 0.46, rim, paint, bevel=0.002)
            m.rod((-0.08, b0 + 0.003, rim), (0.08, b0 + 0.003, rim), 0.004, c["dark"])
            m.box(-0.07, 0.07, b0 + 0.008, b1 - 0.01, rim - 0.1, rim + 0.02, load, bevel=0.02)
        else:
            # An open box, its sides rising to a cab shield out over the cab's roof.
            hi = top - 0.14 if gen == 2 else 1.1
            m.box(-0.078, 0.078, b0, b1, 0.42, 0.5, paint, bevel=0.004)
            for side in (-1, 1):
                m.shell([(b0, 0.5, 0, "paint"), (b1, 0.5, 0, "paint"), (b1, hi + 0.12, 0.004, "paint"), (b0 + 0.01, hi, 0.004, "paint")],
                        0.004, {"paint": paint}, x=side * 0.074, cols=(-1, 1))
            m.box(-0.078, 0.078, b0, b0 + 0.01, 0.5, hi, paint, bevel=0.003)
            m.box(-0.078, 0.078, b1 - 0.01, b1, 0.5, hi + 0.12, paint, bevel=0.003)
            m.box(-0.078, 0.078, b1 - 0.01, b1 + 0.035, hi + 0.12, hi + 0.16, paint, bevel=0.004)
            m.box(-0.068, 0.068, b0 + 0.01, b1 - 0.01, hi - 0.05, hi + 0.02, load, bevel=0.02)
        if kind == "plow":
            _plow(m, a0, a1, cab0, top, plow, c)
    elif kind == "tank":
        # An oval tank with rounded heads, its top about the cab's roof, and domes along it.
        t0 = b0
        if gen > 1:
            # A cabinet at the back for the hose reel and meter.
            t0 = b0 + 0.035
            m.box(-0.074, 0.074, b0, t0 - 0.004, 0.4, top - 0.18, material("hose cabinet", "#8a8e92", metal=0.4, rough=0.45), bevel=0.004)
        m.box(-0.05, 0.05, t0 + 0.01, b1 - 0.01, 0.36, 0.46, c["dark"])
        m.shell([(t0, 0.44, 0.035, "paint"), (b1, 0.44, 0.035, "paint"), (b1, top - 0.02, 0.035, "paint"), (t0, top - 0.02, 0.035, "paint")],
                0.072 if gen == 1 else 0.076, {"paint": paint}, crown=0.14)
        n = 2 if gen == 1 else 3
        for k in range(n):
            y = t0 + (b1 - t0) * (k + 0.5) / n
            m.upright(0.0, y, top - 0.06, top + 0.03, 0.016, 0.014, c["dark"])
        if gen > 1:
            m.box(-0.012, 0.012, t0 + 0.02, b1 - 0.02, top - 0.04, top - 0.01, c["hub"])
    elif kind == "log":
        # Bunks with stakes to the cab's roof, and a few big logs.
        for y in (b0 + 0.02, b1 - 0.02):
            m.box(-0.074, 0.074, y - 0.008, y + 0.008, 0.38, 0.45, c["dark"])
            for side in (-1, 1):
                m.box(side * 0.07 - 0.006, side * 0.07 + 0.006, y - 0.006, y + 0.006, 0.38, top + 0.05, c["dark"])
        bark = material("logs", "#6a4a30", rough=0.95)
        r = 0.034
        z1 = 0.45 + r / rig.RISE
        for x, z in ((-0.032, z1), (0.032, z1), (0.0, z1 + 0.055 / rig.RISE)):
            m.tube(b0 - 0.01, b1 + 0.01, x, z, r, bark, segments=14)
    elif kind == "cattle":
        m.box(-0.078, 0.078, b0, b1, 0.4, 0.45, wood)
        rack(m, b0, b1, 0.45, top + 0.02, 4, paint, material("rack posts", "#5a2420", rough=0.6))
    elif kind == "semi":
        # The trailer, its front over the tractor's fifth wheel.
        t0, t1 = a0, cab0 - 0.012
        m.box(-0.078, 0.078, t0, t1, 0.42, 1.35, material("trailer", "#e8e8e4", rough=0.4), bevel=0.006)
        for side in (-1, 1):
            m.box(side * 0.0782 - 0.001, side * 0.0782 + 0.001, t0 + 0.01, t1 - 0.01, 0.48, 0.52, paint)
        m.text(LETTERS, 0.0792, (t0 + t1) / 2, 1.05, 0.03, paint, 1)


def _blade(m, p0, p1, zb, h0, h1, mats):
    """A curved mouldboard seen from above running from p0 to p1, each (x, y), its hollow face to the left of that
    line, its foot zb steps up and its top h0 steps over the foot at p0 rising to h1 at p1. A steel cutting edge along
    the foot."""
    dx, dy = p1[0] - p0[0], p1[1] - p0[1]
    length = math.hypot(dx, dy)
    n = 9

    def ring(h):
        # Forward of the line is local -x, once turned.
        front = [(-(0.022 * s ** 3 - 0.02 * math.sin(math.pi * s * 0.85)), zb + h * s) for s in (k / (n - 1) for k in range(n))]
        back = [(x + 0.006, z) for x, z in reversed(front)]
        return front + back
    obj = m.loft([(length * t, ring(h0 + (h1 - h0) * t)) for t in (0, 0.25, 0.5, 0.75, 1)], mats,
                 part=lambda k: "edge" if k == 0 or k == 2 * n - 1 else "paint", sharp=60)
    obj.rotation_euler = (0, 0, math.atan2(-dx, dy))
    obj.location = (p0[0], p0[1], 0)
    return obj


def _plow(m, a0, a1, cab0, top, style, c):
    """The plow on a truck's front on a push frame at the bumper: a V plow with a lifting frame over it ("wedge"), or a
    one-way plow throwing to the right with a long wing out along the right side ("wing"), a beacon and a sand spinner
    at the back, as highway plows have had since the 1950s."""
    paint = material("plow blade", "#e8b020", metal=0.3, rough=0.4)
    mats = {"paint": paint, "edge": material("cutting edge", "#4a4a4c", metal=0.7, rough=0.4)}
    iron = c["dark"]
    if style == "wedge":
        tip, back, w = a1 + 0.075, a1 + 0.028, 0.094
        _blade(m, (-w, back), (0.0, tip), 0.06, 0.48, 0.6, mats)
        _blade(m, (0.0, tip), (w, back), 0.06, 0.6, 0.48, mats)
        m.upright(0.0, tip - 0.004, 0.06, 0.68, 0.006, 0.005, paint, segments=10)
        # Push arms from the frame's front to the back of each half, and a gallows with chains to lift it.
        for side in (-1, 1):
            x = side * 0.04
            yb = tip - (tip - back) * 0.04 / w - 0.012
            m.rod((side * 0.045, a1 - 0.03, 0.3), (x, yb, 0.24), 0.004, iron)
            m.rod((side * 0.05, a1 + 0.004, 0.3), (side * 0.05, a1 + 0.004, 0.8), 0.003, iron)
            m.rod((side * 0.05, a1 + 0.004, 0.8), (side * 0.02, tip - 0.02, 0.6), 0.0015, iron)
        m.rod((-0.04, tip - 0.06, 0.24), (0.04, tip - 0.06, 0.24), 0.004, iron)
        m.rod((-0.05, a1 + 0.004, 0.8), (0.05, a1 + 0.004, 0.8), 0.003, iron)
        return
    # A one-way plow, its left end forward and its right end higher to throw the snow off to the right.
    left, right = (-0.1, a1 + 0.07), (0.115, a1 + 0.022)
    _blade(m, left, right, 0.06, 0.48, 0.7, mats)
    for x in (-0.04, 0.04):
        y = left[1] + (right[1] - left[1]) * (x - left[0]) / (right[0] - left[0]) - 0.014
        m.rod((x * 1.1, a1 - 0.03, 0.3), (x, y, 0.24), 0.005, iron)
    m.rod((0.0, a1 + 0.006, 0.38), (0.0, a1 + 0.038, 0.46), 0.006, c["hub"])
    # The wing: a mast at the front right corner, an arm out to the wing's front and a push arm from mid-truck to its back.
    front, rear = (0.108, a1 - 0.012), (0.176, a1 - 0.215)
    _blade(m, front, rear, 0.12, 0.3, 0.46, mats)
    m.box(0.07, 0.086, a1 - 0.006, a1 + 0.008, 0.28, 0.98, iron, bevel=0.002)
    m.rod((0.078, a1 + 0.001, 0.52), (front[0] + 0.004, front[1] - 0.006, 0.46), 0.004, iron)
    m.rod((0.078, a1 + 0.001, 0.96), (rear[0] - 0.01, rear[1] + 0.03, 0.56), 0.0018, iron)
    m.rod((0.06, rear[1] + 0.02, 0.33), (rear[0] - 0.014, rear[1] + 0.03, 0.3), 0.0055, iron)
    # An amber beacon on the cab roof and the sander's spinner under the tailgate.
    m.upright(0.0, cab0 + 0.07, top - 0.02, top + 0.06, 0.012, 0.01, material("beacon", "#f0a020", rough=0.3), segments=12)
    m.rod((0.0, a0 + 0.012, 0.42), (0.0, a0 - 0.004, 0.22), 0.006, iron)
    m.upright(0.0, a0 - 0.006, 0.18, 0.21, 0.016, 0.016, iron, segments=12)


def tractor(m, L, trailer, cab, paint_hex, bumper=False):
    """A highway tractor of its era pulling a trailer: a van, a B-train of tanks or grain hoppers, a cattle liner, or long
    logs on a pole trailer. bumper adds a moose bumper."""
    c = common()
    paint = material(f"{trailer} {cab} tractor", paint_hex, rough=0.4, clearcoat=0.3)
    a0, a1 = -L / 2 + 0.01, L / 2 - 0.01
    cab0 = a1 - CAB_LEN[cab]
    drive = [cab0 - 0.03, cab0 - 0.075]
    rear = [a0 + 0.06, a0 + 0.11] + ([a0 + 0.16] if trailer in ("van", "logs") else [])
    wheels(m, [a1 - 0.06] + drive + rear, c)
    m.box(-0.06, 0.06, cab0 - 0.11, a1, 0.25, 0.35, c["dark"])
    _cab(m, cab0, a1, c, paint, cab)
    if bumper:
        guard(m, a1, c, 0.95 if cab != "aero" else 0.75)
    t0, t1 = a0, cab0 - 0.012
    alu = material("aluminium", "#c4c8cc", metal=0.7, rough=0.3)
    if trailer == "van":
        m.box(-0.078, 0.078, t0, t1, 0.42, 1.55, material("trailer", "#e8e8e4", rough=0.4), bevel=0.006)
        for side in (-1, 1):
            m.box(side * 0.0782 - 0.001, side * 0.0782 + 0.001, t0 + 0.01, t1 - 0.01, 0.5, 0.56, paint)
            if cab == "aero":
                m.box(side * 0.075 - 0.002, side * 0.075 + 0.002, t0 + 0.2, t1 - 0.12, 0.28, 0.42, material("skirt", "#5a5e62", rough=0.5))
        m.text(LETTERS, 0.0792, (t0 + t1) / 2, 1.1, 0.034, paint, 1)
    elif trailer == "tank":
        # Two tanks, a lead trailer and a pup behind it on one hitch.
        mid = (t0 + t1) / 2
        for y0, y1 in ((t0, mid - 0.012), (mid + 0.012, t1)):
            m.shell([(y0, 0.45, 0.04, "paint"), (y1, 0.45, 0.04, "paint"), (y1, 1.2, 0.04, "paint"), (y0, 1.2, 0.04, "paint")], 0.076,
                    {"paint": alu}, crown=0.15)
            m.box(-0.012, 0.012, y0 + 0.02, y1 - 0.02, 1.2, 1.25, c["dark"])
        wheels(m, [mid + 0.04, mid + 0.085], c)
    elif trailer == "hopper":
        # A Super B of grain hoppers: two trailers, each with a pair of hoppers under a tarp.
        mid = (t0 + t1) / 2
        tarp = material("hopper tarp", "#3a4a5a", rough=0.7)
        for y0, y1 in ((t0, mid - 0.012), (mid + 0.012, t1)):
            m.shell([(y0, 0.75, 0.006, "paint"), (y0 + 0.03, 0.4, 0.004, "paint"), ((y0 + y1) / 2 - 0.02, 0.4, 0.004, "paint"),
                     ((y0 + y1) / 2, 0.62, 0, "paint"), ((y0 + y1) / 2 + 0.02, 0.4, 0.004, "paint"), (y1 - 0.03, 0.4, 0.004, "paint"),
                     (y1, 0.75, 0.006, "paint"), (y1, 1.35, 0.008, "paint"), (y0, 1.35, 0.008, "paint")], 0.078, {"paint": alu})
            m.shell([(y0 + 0.005, 1.34, 0, "paint"), (y1 - 0.005, 1.34, 0, "paint"), (y1 - 0.01, 1.42, 0.01, "paint"), (y0 + 0.01, 1.42, 0.01, "paint")],
                    0.074, {"paint": tarp}, crown=0.05)
        wheels(m, [mid + 0.04, mid + 0.085], c)
    elif trailer == "cattle":
        # A cattle liner: aluminium with a dropped belly between the axles, rows of air slots down its sides.
        m.shell([(t0, 0.5, 0.01, "paint"), (t0 + 0.18, 0.5, 0, "paint"), (t0 + 0.2, 0.32, 0.01, "paint"), (t1 - 0.1, 0.32, 0.01, "paint"),
                 (t1 - 0.08, 0.5, 0, "paint"), (t1, 0.5, 0.01, "paint"), (t1, 1.5, 0.012, "paint"), (t0, 1.5, 0.012, "paint")],
                0.078, {"paint": alu})
        slot = material("air slots", "#2a2a2c", rough=0.8)
        for side in (-1, 1):
            for z in (0.62, 0.86, 1.1, 1.32):
                m.box(side * 0.078 - 0.001, side * 0.078 + 0.001, t0 + 0.02, t1 - 0.02, z, z + 0.05, slot)
            m.box(side * 0.078 - 0.0015, side * 0.078 + 0.0015, t0 + 0.2, t1 - 0.1, 0.36, 0.44, paint)
    elif trailer == "logs":
        # Bunks on the tractor and the pole trailer, with long logs between.
        for y in (cab0 - 0.03, cab0 - 0.075, a0 + 0.08, a0 + 0.14):
            for side in (-1, 1):
                m.box(side * 0.07 - 0.006, side * 0.07 + 0.006, y - 0.006, y + 0.006, 0.38, 1.3, c["dark"])
            m.box(-0.07, 0.07, y - 0.008, y + 0.008, 0.38, 0.44, c["dark"])
        m.box(-0.012, 0.012, a0 + 0.05, cab0 - 0.1, 0.28, 0.36, c["dark"])
        bark = material("logs", "#6a4a30", rough=0.95)
        for x, z in ((-0.045, 0.6), (0.0, 0.6), (0.045, 0.6), (-0.022, 0.86), (0.022, 0.86), (-0.045, 1.1), (0.0, 1.12), (0.045, 1.1)):
            m.tube(a0 + 0.02, cab0 - 0.015, x, z, 0.023, bark, segments=10)


def van(m, L, era):
    """A walk-in delivery van: a step van with a short sloped nose, or a battery van with one smooth curve from bumper to roof."""
    c = common()
    body = material(f"{era} van", "#ecebe6", rough=0.4, clearcoat=0.3)
    band = material("mail red", "#c8302a", rough=0.4)
    grille = material("grille", "#3a3836", metal=0.4, rough=0.5)
    R, F = -L / 2 + 0.01, L / 2 - 0.01
    half = 0.078
    wheels(m, [R + 0.08, F - 0.07], c)
    if era == "step":
        shape = [(R, 0.32, 0.012, "paint"), (F - 0.004, 0.32, 0.015, "grille", 0.88), (F, 0.62, 0.02, "paint", 0.88),
                 (F - 0.035, 0.76, 0.02, "paint", 0.92), (F - 0.04, 0.8, 0.006, "glass", 0.95), (F - 0.05, 1.26, 0.02, "paint"),
                 (F - 0.07, 1.44, 0.025, "paint"), (R, 1.44, 0.015, "paint")]
        crown = 0.05
        lamp_z = 0.52
    else:
        shape = [(R, 0.32, 0.012, "paint"), (F - 0.006, 0.32, 0.02, "paint", 0.88), (F, 0.56, 0.035, "paint", 0.88),
                 (F - 0.03, 0.8, 0.02, "glass", 0.9), (F - 0.075, 1.34, 0.045, "paint", 0.95), (F - 0.11, 1.42, 0.04, "paint"),
                 (R, 1.42, 0.02, "paint")]
        crown = 0.08
        lamp_z = 0.6
    outline = m.shell(shape, half, {"paint": body, "glass": c["glass"], "grille": grille}, crown=crown)
    lamps(m, outline, lamp_z, 0.055, c)
    for side in (-1, 1):
        m.box(side * half - 0.0015, side * half + 0.0015, R + 0.01, F - 0.06, 0.5, 0.6, band)
        m.box(side * half - 0.001, side * half + 0.001, F - 0.1, F - 0.06, 0.82, 1.22, c["glass"])
    m.box(-0.07, 0.07, F - 0.01, F + 0.006, 0.28, 0.36, c["dark"], bevel=0.004)
    m.text(LETTERS, half + 0.002, (R + F) / 2 - 0.04, 0.95, 0.024, band, 1)


def _pane(m, p, q, z0, z1, mat, cy, inset=0.15):
    """A thin pane of glass on a wall running from p to q seen from above, each (x, y), on its outer face away from (0, cy),
    stopping inset of its length short of each end."""
    dx, dy = q[0] - p[0], q[1] - p[1]
    length = math.hypot(dx, dy)
    nx, ny = dy / length, -dx / length
    if nx * ((p[0] + q[0]) / 2) + ny * ((p[1] + q[1]) / 2 - cy) < 0:
        nx, ny = -nx, -ny
    a = (p[0] + dx * inset, p[1] + dy * inset)
    b = (q[0] - dx * inset, q[1] - dy * inset)
    quad = [(a[0] + nx * o, a[1] + ny * o) for o in (0.0005, 0.002)]
    quad = [quad[0], (b[0] + nx * 0.0005, b[1] + ny * 0.0005), (b[0] + nx * 0.002, b[1] + ny * 0.002), quad[1]]
    m.wedge([(x, y, z0) for x, y in quad], [(x, y, z1) for x, y in quad], mat)


def old_car(m, b0, b1, half, floor, eaves, plat, mats, c, closed, windows_n, rise=0.06, deck=0.12):
    """A wooden horsecar or streetcar body from b0 to b1: a saloon with tall narrow windows, a platform at each end plat
    long, closed in as a vestibule with three windows round its bowed end (closed) or left open behind a curved dash, a
    roof running out over the platforms to rounded hoods and a monitor deck with small windows along the saloon. mats
    names "paint", "trim" (belt rail and letterboard) and "roof". Returns the height of the deck's top, in steps."""
    body, trim, roof = mats["paint"], mats["trim"], mats["roof"]
    p0, p1 = b0 + plat, b1 - plat
    cy = (b0 + b1) / 2
    lo = floor - 0.04
    belt = floor + 0.4
    head = eaves - 0.1
    ring = [(half, lo), (half, eaves), (-half, eaves), (-half, lo)]
    bow, e = 0.6, 0.026
    if closed:
        narrow = [(x * bow, z) for x, z in ring]
        m.loft([(b0, narrow), (b0 + e, ring), (b1 - e, ring), (b1, narrow)], {"paint": body}, sharp=30)
        w0, w1 = b0 + e, b1 - e
    else:
        m.loft([(p0, ring), (p1, ring)], {"paint": body})
        w0, w1 = p0, p1
    for side in (-1, 1):
        for z0, z1 in ((belt - 0.035, belt), (eaves - 0.08, eaves - 0.01)):
            m.box(side * half - 0.0015, side * half + 0.0015, w0, w1, z0, z1, trim)
    windows(m, p0 + 0.008, p1 + 0.002, belt + 0.01, head, windows_n, half + 0.0005, c, gap=0.25)
    for end in (-1, 1):
        yb, yp = (b0, p0) if end < 0 else (b1, p1)
        ye = yb - end * e
        if closed:
            # The vestibule: a glazed door each side, and three windows round the bowed end over a headlamp.
            for side in (-1, 1):
                m.box(side * half - 0.001, side * half + 0.001, min(yp, ye) + 0.006, max(yp, ye) - 0.006, floor + 0.06, head, c["glass"])
                m.box(side * (half - 0.012), side * (half + 0.004), min(yp, ye) + 0.004, max(yp, ye) - 0.004, lo - 0.1, lo, c["dark"])
                _pane(m, (side * half, ye), (side * half * bow, yb), belt + 0.01, head, c["glass"], cy)
                m.box(side * half - 0.0015, side * half + 0.0015, min(yp, ye), max(yp, ye), belt - 0.035, belt, trim)
            _pane(m, (-half * bow, yb), (half * bow, yb), belt + 0.01, head, c["glass"], cy, inset=0.08)
            m.tube(yb - 0.002 * end, yb + 0.006 * end, 0.0, floor + 0.2, 0.009, c["light"], segments=12)
            m.box(-half * 0.7, half * 0.7, min(yb, yb + 0.012 * end), max(yb, yb + 0.012 * end), lo - 0.02, lo + 0.06, c["dark"], bevel=0.003)
        else:
            # An open platform: a door in the bulkhead, the floor, a dash curving round its end, posts up to the hood and
            # a step each side.
            m.box(-0.022, 0.022, yp - 0.001, yp + 0.001, belt - 0.12, head, c["glass"])
            m.box(-half + 0.004, half - 0.004, min(yb, yp), max(yb, yp), lo, floor, c["dark"])
            arc = [(half * 0.94 * math.sin(a), yb - end * (0.022 * (1 - math.cos(a)) - 0.004)) for a in
                   [math.radians(d) for d in range(-90, 91, 15)]]
            arc = [(x, yb + end * 0.024 - end * (0.024 - 0.022 * (1 - abs(x) / (half * 0.94)) ** 0.5)) for x, _ in arc]
            for p, q in zip(arc, arc[1:]):
                m.wedge([(p[0], p[1], floor), (q[0], q[1], floor), (q[0], q[1] - end * 0.004, floor), (p[0], p[1] - end * 0.004, floor)],
                        [(p[0], p[1], belt), (q[0], q[1], belt), (q[0], q[1] - end * 0.004, belt), (p[0], p[1] - end * 0.004, belt)], body)
            for side in (-1, 1):
                x, y = arc[0 if side < 0 else -1]
                m.rod((x, y, floor), (x, y, eaves), 0.0026, c["chrome"], segments=6)
                m.box(side * half - 0.008, side * half + 0.004, min(y, yp) + 0.003, max(y, yp) - 0.003, lo - 0.12, lo - 0.08, c["dark"])
    # The roof over the whole car, rounding in plan and dipping a little at the hoods.
    W, zb, th = half + 0.005, eaves + 0.01, 0.028
    arch = [math.pi * k / 10 for k in range(11)]

    def roof_ring(sx, dz, k):
        return [(W * sx * math.cos(a), zb - dz + rise * k * math.sin(a)) for a in arch] + [(-W * sx, zb - dz - th), (W * sx, zb - dz - th)]
    R = min(plat, 0.05) * 0.9
    secs = []
    for end in (-1, 1):
        y_end = (b0 - 0.006) if end < 0 else (b1 + 0.006)
        pts = []
        for f in (0.0, 0.35, 0.6, 0.8, 0.93, 1.0):
            sx = max(0.12, (1 - f ** 2.6) ** (1 / 2.6))
            pts.append((y_end - end * R * (1 - f), roof_ring(sx, 0.03 * f * f, 1 - 0.45 * f * f)))
        secs = pts[::-1] + secs if end < 0 else secs + pts
    secs.sort(key=lambda s: s[0])
    m.loft(secs, {"roof": roof}, sharp=50)
    # The monitor deck along the saloon, its ends sloping down onto the roof.
    mw = half * 0.56
    z0, z1 = zb + rise * 0.5, zb + rise + deck

    def deck_ring(k):
        ring = [(mw, z0), (mw, z0 + (z1 - z0) * k)]
        ring += [(mw * math.cos(a), z0 + (z1 - z0) * k + 0.035 * k * math.sin(a)) for a in arch[1:-1]]
        return ring + [(-mw, z0 + (z1 - z0) * k), (-mw, z0)]
    sl = min(plat * 0.7, 0.035)
    m.loft([(p0 - sl, deck_ring(0.25)), (p0 + 0.004, deck_ring(1)), (p1 - 0.004, deck_ring(1)), (p1 + sl, deck_ring(0.25))],
           {"roof": roof}, sharp=40)
    n = max(4, round((p1 - p0) / 0.03))
    for side in (-1, 1):
        for k in range(n):
            y = p0 + 0.01 + (p1 - p0 - 0.02) * (k + 0.2) / n
            m.box(side * mw - 0.001, side * mw + 0.001, y, y + (p1 - p0 - 0.02) / n * 0.6, z0 + rise * 0.6, z1 - 0.025, c["glass"])
    return z1 + 0.035


def streetcar(m, L, era):
    """A streetcar: a wooden single-truck car with closed vestibules and a monitor roof, a rounded PCC, an angular CLRV, or a long low-floor tram."""
    c = common()
    paint = {"wood": ("#8a2a22", "#e6c870"), "pcc": ("#c8302a", "#ece6d6"), "clrv": ("#c8302a", "#ece6d6"), "flexity": ("#c8302a", "#2a2a2a")}[era]
    body = material(f"{era} tram", paint[0], rough=0.4, clearcoat=0.3)
    trim = material(f"{era} trim", paint[1], rough=0.4)
    a0, a1 = -L / 2 + 0.01, L / 2 - 0.01
    half = 0.085
    floor = 0.3 if era == "flexity" else 0.42
    top = 1.3
    zc = WHEEL_R / rig.RISE
    if era == "wood":
        roof = material("car roof", "#6a625a", rough=0.75)
        deck = old_car(m, a0, a1, 0.082, floor, 1.1, 0.065, {"paint": body, "trim": trim, "roof": roof}, c, True, 8)
        # One small truck: four wheels showing between the bars of its side frames, and the trolley pole raked back
        # from the deck.
        r = 0.024
        zw = r / rig.RISE
        with m.keep():
            for side in (-1, 1):
                for y in (-0.045, 0.045):
                    m.tube_x(side * 0.066 - 0.006, side * 0.066 + 0.006, y, zw, r, c["dark"])
                    m.tube_x(side * 0.073 - 0.002, side * 0.073 + 0.002, y, zw, r * 0.4, c["hub"])
                for z0, z1 in ((zw - 0.03, zw + 0.03), (zw + 0.2, zw + 0.25)):
                    m.box(side * 0.076 - 0.003, side * 0.076 + 0.003, -0.078, 0.078, z0, z1, c["dark"], bevel=0.002)
                m.box(side * 0.076 - 0.003, side * 0.076 + 0.003, -0.012, 0.012, zw - 0.03, zw + 0.25, c["dark"])
        m.box(-0.055, 0.055, -0.065, 0.065, m.fixed(zw + 0.2), floor - 0.04, c["dark"])
        m.rod((0.0, a0 + L * 0.5, deck), (0.0, a0 + L * 0.14, deck + 0.42), 0.004, c["dark"])
        m.box(-0.016, 0.016, a0 + L * 0.48, a0 + L * 0.54, deck - 0.01, deck + 0.03, c["dark"])
        m.text(LETTERS, 0.0842, 0.0, floor + 0.2, 0.025, trim, 1)
        return
    for y in (a0 + L * 0.2, a1 - L * 0.2):
        for side in (-1, 1):
            for dy in (-0.03, 0.03):
                with m.keep():
                    m.tube_x(side * 0.065 - 0.008, side * 0.065 + 0.008, y + dy, zc, WHEEL_R * 0.9, c["dark"])
    # Both ends shaped alike, as these run either way round or turn on loops.
    nose = {"pcc": (0.004, 0.03, 0.04, 0.88, 0.1), "clrv": (0.003, 0.012, 0.008, 0.94, 0.05), "flexity": (0.005, 0.025, 0.035, 0.9, 0.06)}[era]
    lean, rake, r, w, crown = nose
    shape = [(a0 + 0.004, floor, r * 0.6, "paint", w), (a1 - 0.004, floor, r * 0.6, "paint", w), (a1 + lean, floor + 0.25, r, "paint", w),
             (a1, floor + 0.42, 0.01, "glass", w), (a1 - rake, top - 0.04, r, "paint", w), (a1 - rake - 0.03, top + 0.05, r, "paint"),
             (a0 + rake + 0.03, top + 0.05, r, "paint"), (a0 + rake, top - 0.04, r, "glass", w), (a0, floor + 0.42, 0.01, "paint", w),
             (a0 - lean, floor + 0.25, r, "paint", w)]
    outline = m.shell(shape, half, {"paint": body, "glass": c["glass"]}, crown=crown)
    if era == "pcc":
        m.shell([(a0 + 0.08, top + 0.02, 0, "paint"), (a1 - 0.08, top + 0.02, 0, "paint"), (a1 - 0.1, top + 0.09, 0.02, "paint"),
                 (a0 + 0.1, top + 0.09, 0.02, "paint")], 0.05, {"paint": trim}, crown=0.02)
    for side in (-1, 1):
        m.box(side * half - 0.0015, side * half + 0.0015, a0 + 0.03, a1 - 0.03, floor + 0.08, floor + 0.2, trim)
    for y in (a0, a1):
        for s in (-1, 1):
            m.tube(y - 0.004, y + 0.004, s * 0.05, floor + 0.15, 0.009, c["light"], segments=12)
    windows(m, a0 + 0.07, a1 - 0.07, floor + 0.45, top - 0.12, int(L * 14), half + 0.0005, c, gap=0.2)
    if era == "flexity":
        # Joints between the sections, and a pantograph.
        for f in (1 / 3, 2 / 3):
            y = a0 + (a1 - a0) * f
            m.box(-half - 0.001, half + 0.001, y - 0.012, y + 0.012, floor, top + 0.04, c["dark"])
        m.rod((0.0, 0.0, top + 0.06), (0.0, 0.05, top + 0.6), 0.004, c["hub"])
        m.box(-0.06, 0.06, 0.04, 0.06, top + 0.58, top + 0.62, c["hub"])
    else:
        # A trolley pole raked back to the wire.
        m.rod((0.0, a0 + L * 0.45, top + 0.08), (0.0, a0 + L * 0.12, top + 0.55), 0.004, c["dark"])
    m.text(LETTERS, half + 0.002, 0.0, floor + 0.3, 0.025, trim, 1)


def salt_dome(m):
    """A salt and sand dome: a round timber dome over a concrete ring, a door on one side and the pile showing in it."""
    concrete = material("dome base", "#a8a49c", rough=0.85)
    shingle = material("dome roof", "#5e5a54", rough=0.75)
    salt = material("road salt", "#e8e4dc", rough=0.9)
    m.lathe(0, 0, 0.0, [(0.4, 0.0), (0.4, 0.5), (0.38, 0.55)], concrete, segments=32)
    m.lathe(0, 0, 0.55, [(0.39, 0.0), (0.34, 0.9), (0.24, 1.6), (0.1, 2.0), (0.0, 2.1)], shingle, segments=32)
    m.box(-0.12, 0.12, 0.3, 0.42, 0.0, 1.3, shingle, 0.006)
    m.box(-0.1, 0.1, 0.41, 0.425, 0.0, 1.1, material("dome door", "#1a1816", rough=1.0))
    m.lathe(0, 0.3, 0.0, [(0.12, 0.0), (0.06, 0.4), (0.0, 0.5)], salt, segments=16)
