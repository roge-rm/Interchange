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
CAB_LEN = {"round": 0.17, "square": 0.17, "long": 0.2, "sleeper": 0.27, "aero": 0.27, "cabover": 0.1}


def guard(m, y1, c, top=0.9):
    """A moose bumper: a heavy guard of bars across the grille, for rural and northern roads."""
    for x in (-0.06, -0.025, 0.025, 0.06):
        m.box(x - 0.004, x + 0.004, y1 + 0.006, y1 + 0.014, 0.28, top, c["chrome"], bevel=0.002)
    for z in (0.34, top - 0.04):
        m.box(-0.072, 0.072, y1 + 0.008, y1 + 0.018, z - 0.03, z + 0.03, c["chrome"], bevel=0.003)


def _cab(m, y0, y1, c, paint, style):
    """A truck's cab from y0 to its front at y1, by era: a 1930s rounded hood with fenders, a squarer 1960s hood with a wide
    grille, a long highway hood with stacks (with a sleeper behind as "sleeper"), a sloped aero hood with a tall roof, or a
    cab-over. Returns where the hood meets the cab."""
    half = 0.075
    grille = material("grille", "#3a3836", metal=0.4, rough=0.5)
    mats = {"paint": paint, "glass": c["glass"], "grille": grille}
    top = 1.22
    if style == "round":
        hood = y1 - 0.07
        hw = 0.62
        shape = [(y0, 0.32, 0.01, "paint"), (hood, 0.32, 0, "paint"), (hood, 0.32, 0, "paint", hw), (y1 - 0.008, 0.32, 0.012, "grille", hw),
                 (y1, 0.68, 0.032, "paint", hw), (hood, 0.82, 0, "paint", hw), (hood, 0.82, 0, "paint"),
                 (hood - 0.003, 0.88, 0.008, "glass"), (hood - 0.013, 1.14, 0.015, "paint"), (hood - 0.03, top, 0.02, "paint"),
                 (y0, top, 0.015, "paint")]
        outline = m.shell(shape, half, mats, crown=0.07)
        lamps(m, outline, 0.66, 0.06, c)
    elif style == "square":
        hood = y1 - 0.08
        hw = 0.8
        top = 1.24
        shape = [(y0, 0.32, 0.01, "paint"), (hood, 0.32, 0, "paint"), (hood, 0.32, 0, "paint", hw), (y1 - 0.002, 0.32, 0.008, "grille", hw),
                 (y1, 0.76, 0.014, "paint", hw), (hood, 0.84, 0, "paint", hw), (hood, 0.84, 0, "paint"),
                 (hood - 0.002, 0.88, 0.006, "glass"), (hood - 0.008, 1.17, 0.01, "paint"), (hood - 0.02, top, 0.015, "paint"),
                 (y0, top, 0.012, "paint")]
        outline = m.shell(shape, half, mats, crown=0.04)
        lamps(m, outline, 0.68, 0.052, c)
    elif style in ("long", "sleeper"):
        hood = y1 - 0.11
        hw = 0.8
        top = 1.34
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
        top = 1.62
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
        top = 1.32
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
        m.box(side * half - 0.001, side * half + 0.001, door - 0.04, door, 0.88 if style != "cabover" else 0.82, min(top, 1.36) - 0.1, c["glass"])
    m.box(-0.068, 0.068, y1 - 0.004, y1 + 0.01, 0.28, 0.36, c["chrome"], bevel=0.003)
    return hood


# Each kind's paint, the load's colour, and its body's colour where that differs.
PAINTS = {"mail": "#b8322a", "stake": "#6a4a2a", "flatbed": "#2a5a8a", "dump": "#d8a020", "tank": "#d8d8d4", "log": "#3a5a2a",
          "cattle": "#8a2a22", "semi": "#c83a2a", "plow": "#e07a20"}


def truck(m, L, kind, cab="round", gen=1):
    """A truck: a cab of its era and a body for its load. Later generations run more axles."""
    c = common()
    paint = material(f"{kind} {cab} truck", PAINTS[kind], rough=0.45, clearcoat=0.2)
    wood = material("truck boards", "#8a6a44", rough=0.85)
    a0, a1 = -L / 2 + 0.01, L / 2 - 0.01
    cab0 = a1 - CAB_LEN[cab]
    if kind == "semi":
        wheels(m, [a1 - 0.06, cab0 - 0.04, a0 + 0.06, a0 + 0.12], c)
    else:
        rear = [a0 + 0.07] + ([a0 + 0.13] if L > 0.45 or gen > 1 else [])
        if gen > 2:
            rear += [cab0 - 0.04]
        wheels(m, [a1 - 0.055] + rear, c)
    m.box(-0.06, 0.06, a0, a1, 0.25, 0.35, c["dark"])
    _cab(m, cab0, a1 - (0.005 if cab == "cabover" else 0.0), c, paint, cab)
    if gen == 3:
        guard(m, a1, c)
    b0, b1 = a0 + 0.005, cab0 - 0.012
    if kind == "mail":
        m.box(-0.078, 0.078, b0, b1, 0.38, 1.4, paint, bevel=0.006)
        m.text(LETTERS, 0.0792, (b0 + b1) / 2, 0.85, 0.022, material("mail letters", "#f0f0e8", rough=0.5), 1)
    elif kind == "stake":
        m.box(-0.078, 0.078, b0, b1, 0.38, 0.45, wood)
        for side in (-1, 1):
            for k in range(4):
                y = b0 + (b1 - b0) * (k + 0.5) / 4
                m.box(side * 0.076 - 0.006, side * 0.076 + 0.006, y - 0.006, y + 0.006, 0.45, 0.95, wood)
            m.box(side * 0.076 - 0.004, side * 0.076 + 0.004, b0, b1, 0.8, 0.88, wood)
        m.box(-0.062, 0.062, b0 + 0.02, b1 - 0.02, 0.45, 0.75, material("crates", "#a88a5a", rough=0.8), bevel=0.01)
    elif kind == "flatbed":
        # A steel deck with a headboard, and a strapped load of lumber and pallets.
        m.box(-0.078, 0.078, b0, b1, 0.36, 0.44, c["dark"], bevel=0.003)
        m.box(-0.074, 0.074, b1 - 0.008, b1, 0.44, 1.15, c["hub"], bevel=0.002)
        m.box(-0.066, 0.066, b0 + 0.01, (b0 + b1) / 2 - 0.006, 0.44, 0.85, material("lumber", "#d8b880", rough=0.85), bevel=0.004)
        m.box(-0.066, 0.066, (b0 + b1) / 2 + 0.006, b1 - 0.02, 0.44, 0.78, material("tarp", "#4a6a3a", rough=0.7), bevel=0.012)
    elif kind == "dump" or kind == "plow":
        hi = 0.95 if gen == 1 else 1.1
        if gen == 1:
            m.box(-0.078, 0.078, b0, b1, 0.42, hi, paint, bevel=0.006)
        else:
            # An open box, its sides rising to a cab shield out over the cab's roof.
            m.box(-0.078, 0.078, b0, b1, 0.42, 0.5, paint, bevel=0.004)
            for side in (-1, 1):
                m.shell([(b0, 0.5, 0, "paint"), (b1, 0.5, 0, "paint"), (b1, hi + 0.12, 0.004, "paint"), (b0 + 0.01, hi, 0.004, "paint")],
                        0.004, {"paint": paint}, x=side * 0.074, cols=(-1, 1))
            m.box(-0.078, 0.078, b0, b0 + 0.01, 0.5, hi, paint, bevel=0.003)
            m.box(-0.078, 0.078, b1 - 0.01, b1, 0.5, hi + 0.12, paint, bevel=0.003)
            m.box(-0.078, 0.078, b1 - 0.01, b1 + 0.035, hi + 0.12, hi + 0.16, paint, bevel=0.004)
        m.box(-0.068, 0.068, b0 + 0.01, b1 - 0.01, hi - 0.05, hi + 0.02, material("load" if kind == "dump" else "sand", "#3a3a3a" if kind == "dump" else "#a89878", rough=0.95), bevel=0.02)
        if kind == "plow":
            blade = material("plow blade", "#e8b020", metal=0.3, rough=0.4)
            front = a1 + 0.02
            w = 0.13 if gen == 1 else 0.15
            m.wedge([(-w, front, 0.1), (w, front + 0.03, 0.1), (w, front + 0.05, 0.1), (-w, front + 0.02, 0.1)],
                    [(-w, front - 0.01, 0.75), (w, front + 0.02, 0.75), (w, front + 0.04, 0.75), (-w, front + 0.01, 0.75)], blade)
            if gen > 1:
                # A wing plow out on the right, and a light bar.
                m.wedge([(0.08, a1 - 0.1, 0.15), (0.16, a1 - 0.02, 0.15), (0.165, a1 - 0.025, 0.15), (0.085, a1 - 0.105, 0.15)],
                        [(0.08, a1 - 0.1, 0.55), (0.16, a1 - 0.02, 0.55), (0.165, a1 - 0.025, 0.55), (0.085, a1 - 0.105, 0.55)], blade)
                m.box(-0.05, 0.05, cab0 + 0.03, cab0 + 0.05, 1.24, 1.32, material("beacon", "#f0a020", rough=0.3), bevel=0.004)
            else:
                m.box(-0.01, 0.01, a1 - 0.08, a1 - 0.06, 1.35, 1.55, material("beacon", "#f0a020", rough=0.3))
    elif kind == "tank":
        if gen == 1:
            m.tube(b0, b1, 0.0, 0.78, 0.068, paint, segments=24)
        else:
            # An oval tank with rounded heads, a walkway on top.
            m.shell([(b0, 0.45, 0.04, "paint"), (b1, 0.45, 0.04, "paint"), (b1, 1.15, 0.04, "paint"), (b0, 1.15, 0.04, "paint")],
                    0.076, {"paint": paint}, crown=0.15)
            m.box(-0.012, 0.012, b0 + 0.02, b1 - 0.02, 1.15, 1.2, c["hub"])
        m.box(-0.02, 0.02, (b0 + b1) / 2 - 0.02, (b0 + b1) / 2 + 0.02, 1.15 if gen == 1 else 1.2, 1.3, c["dark"])
    elif kind == "log":
        for y in (b0 + 0.02, b1 - 0.02):
            for side in (-1, 1):
                m.box(side * 0.07 - 0.006, side * 0.07 + 0.006, y - 0.006, y + 0.006, 0.38, 1.25, c["dark"])
        bark = material("logs", "#6a4a30", rough=0.95)
        for k, (x, z) in enumerate(((-0.042, 0.55), (0.0, 0.55), (0.042, 0.55), (-0.021, 0.8), (0.021, 0.8), (0.0, 1.05))):
            m.tube(b0 - 0.01, b1 + 0.01, x, z, 0.022, bark, segments=10)
    elif kind == "cattle":
        m.box(-0.078, 0.078, b0, b1, 0.4, 0.45, wood)
        for side in (-1, 1):
            for k in range(5):
                z = 0.5 + k * 0.17
                m.box(side * 0.076 - 0.004, side * 0.076 + 0.004, b0, b1, z, z + 0.08, paint)
        m.box(-0.078, 0.078, b0, b1, 1.35, 1.38, paint)
    elif kind == "semi":
        # The trailer, its front over the tractor's fifth wheel.
        t0, t1 = a0, cab0 - 0.012
        m.box(-0.078, 0.078, t0, t1, 0.42, 1.35, material("trailer", "#e8e8e4", rough=0.4), bevel=0.006)
        for side in (-1, 1):
            m.box(side * 0.0782 - 0.001, side * 0.0782 + 0.001, t0 + 0.01, t1 - 0.01, 0.48, 0.52, paint)
        m.text(LETTERS, 0.0792, (t0 + t1) / 2, 1.05, 0.03, paint, 1)


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


def streetcar(m, L, era):
    """A streetcar: a wooden single-truck car with open platforms, a rounded PCC, an angular CLRV, or a long low-floor tram."""
    c = common()
    paint = {"wood": ("#8a2a22", "#e6c870"), "pcc": ("#c8302a", "#ece6d6"), "clrv": ("#c8302a", "#ece6d6"), "flexity": ("#c8302a", "#2a2a2a")}[era]
    body = material(f"{era} tram", paint[0], rough=0.4, clearcoat=0.3)
    trim = material(f"{era} trim", paint[1], rough=0.4)
    a0, a1 = -L / 2 + 0.01, L / 2 - 0.01
    half = 0.085
    floor = 0.3 if era == "flexity" else 0.42
    top = 1.3
    zc = WHEEL_R / rig.RISE
    trucks = [a0 + L * 0.2, a1 - L * 0.2] if era != "wood" else [0.0]
    for y in trucks:
        for side in (-1, 1):
            for dy in (-0.03, 0.03):
                m.tube_x(side * 0.065 - 0.008, side * 0.065 + 0.008, y + dy, zc, WHEEL_R * 0.9, c["dark"])
    if era == "wood":
        p0, p1 = a0 + 0.05, a1 - 0.05
        m.box(-half, half, p0, p1, floor, top, body, bevel=0.004)
        for y0, y1 in ((a0, p0), (p1, a1)):
            m.box(-half + 0.01, half - 0.01, y0, y1, floor, floor + 0.08, c["dark"])
            m.box(-half + 0.01, half - 0.01, y0, y1, top - 0.05, top, c["dark"])
        m.box(-0.05, 0.05, p0 + 0.03, p1 - 0.03, top, top + 0.15, trim, bevel=0.004)
    else:
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
    if era == "wood":
        for y in (a0, a1):
            m.box(-half + 0.015, half - 0.015, y - 0.003, y + 0.003, floor + 0.45, top - 0.1, c["glass"])
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
