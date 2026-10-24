"""Buses, trucks and streetcars, in our own colours: built facing +y, L tiles long, x across, z in steps.

A road vehicle is narrower and lower than a rail car: about 0.1 tile each side of its middle and 1.3 steps
tall for a bus. Wheels sit on the road, their tops a little over a step's fifth up.
"""
import math

import bmesh

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
    """A city bus of its era: the 1920s motor bus with a hood, a rounded Brill, a New Look with its bulging windshield,
    standee windows and fluted sides, a low-floor bus with its windows set low, or a battery bus with a roof fairing."""
    c = common()
    paint = {"motor": ("#6a2a26", "#e6dcc0"), "brill": ("#8a2a22", "#e6dcc0"), "newlook": ("#e6dcc0", "#2a5a8a"),
             "lowfloor": ("#e8e8e4", "#2a7a4a"), "electric": ("#e8e8e4", "#2a8ab0")}[era]
    body = material(f"{era} bus", paint[0], rough=0.4, clearcoat=0.3)
    band = material(f"{era} band", paint[1], rough=0.4)
    grille = material("grille", "#3a3836", metal=0.4, rough=0.5)
    mats = {"paint": body, "glass": c["glass"], "grille": grille}
    R, F = -L / 2 + 0.01, L / 2 - 0.01
    half = 0.078
    low = era in ("lowfloor", "electric")
    fl = 0.3 if low else 0.42
    t = 1.06 if era == "motor" else 1.15
    # The window row follows the floor: a high-floor bus's sills sit about half way up its side, a low-floor bus's
    # about a third.
    sill, head = {"motor": (0.76, 0.97), "brill": (0.8, 1.04), "newlook": (0.8, 1.0)}.get(era, (0.56, 1.07))
    # A transit bus's front axle about 2 m behind its front and its rear axle about 3 m from its back; the motor bus's
    # front axle is under its hood.
    front_axle = F - (0.05 if era == "motor" else 0.075)
    wheels(m, [R + (0.065 if era == "motor" else 0.07), front_axle], c)
    nose = F
    if era == "motor":
        # The engine out front under a narrower hood, its top rising from the radiator back to the foot of the
        # windshield, and a flat windshield over the cowl.
        nose = F - 0.09
        hw = 0.66
        cowl = sill
        shape = [(R, fl, 0.015, "paint"), (nose, fl, 0, "paint"), (nose, fl, 0, "paint", hw), (F - 0.006, fl, 0.01, "grille", hw),
                 (F, 0.68, 0.012, "paint", hw), (nose + 0.004, cowl, 0.0, "paint", hw), (nose, cowl, 0, "paint"),
                 (nose - 0.004, cowl + 0.02, 0.006, "glass"), (nose - 0.01, t - 0.05, 0.015, "paint"), (nose - 0.026, t + 0.04, 0.03, "paint"),
                 (R + 0.03, t + 0.04, 0.03, "paint"), (R, t - 0.08, 0.02, "paint")]
        crown = 0.06
        for side in (-1, 1):
            fender(m, front_axle, side, c, body)
    elif era == "brill":
        # A rounded front, bowed in plan, the split windshield under a destination box.
        shape = [(R, fl, 0.02, "paint"), (F - 0.006, fl, 0.025, "paint", 0.9, 0.01), (F + 0.004, fl + 0.25, 0.03, "paint", 0.9, 0.014),
                 (F, sill - 0.02, 0.012, "glass", 0.9, 0.014), (F - 0.016, head - 0.02, 0.012, "paint", 0.92, 0.012),
                 (F - 0.04, t + 0.03, 0.05, "paint", 1.0, 0.008), (R + 0.05, t + 0.06, 0.05, "paint"), (R, t - 0.12, 0.04, "paint")]
        crown = 0.1
    elif era == "newlook":
        # The fishbowl: a windshield bowed out in plan and curving up into the roof.
        shape = [(R, fl, 0.015, "paint"), (F - 0.004, fl, 0.02, "paint", 0.9, 0.006), (F + 0.003, fl + 0.18, 0.03, "paint", 0.9, 0.012),
                 (F + 0.003, fl + 0.3, 0.015, "glass", 0.9, 0.016), (F - 0.008, t - 0.08, 0.04, "glass", 0.92, 0.014),
                 (F - 0.04, t + 0.04, 0.03, "paint", 1.0, 0.004), (R + 0.02, t + 0.04, 0.03, "paint"), (R, t - 0.06, 0.02, "paint")]
        crown = 0.07
    else:
        # A big flat windshield nearly down to the bumper.
        shape = [(R, fl, 0.015, "paint"), (F, fl, 0.015, "paint", 0.94), (F + 0.002, fl + 0.12, 0.012, "glass", 0.94, 0.004),
                 (F - 0.004, t - 0.03, 0.03, "paint", 0.94, 0.004), (F - 0.02, t + 0.04, 0.02, "paint"),
                 (R + 0.012, t + 0.04, 0.02, "paint"), (R, t - 0.04, 0.015, "paint")]
        crown = 0.05
    outline = m.shell(shape, half, mats, crown=crown)
    w0, w1 = R + 0.03, (nose - 0.02 if era == "motor" else nose - 0.07)
    n = {"motor": 6, "brill": 5, "newlook": 6}.get(era, 5)
    windows(m, w0, w1, sill, head, n, half + 0.0005, c, gap=0.15 if era != "newlook" else 0.1)
    for side in (-1, 1):
        if era == "newlook":
            # Standee windows over the main row, and the fluted aluminium sides under the band.
            windows(m, w0, w1, head + 0.04, head + 0.1, n, half + 0.0005, c, gap=0.25)
            m.box(side * half - 0.0015, side * half + 0.0015, R + 0.02, F - 0.03, sill - 0.08, sill - 0.02, band)
            flutes = material("flutes", "#b8bcc0", metal=0.6, rough=0.3)
            for k in range(5):
                z = fl + 0.05 + k * 0.05
                m.box(side * half - 0.002, side * half + 0.002, R + 0.015, F - 0.02, z, z + 0.022, flutes)
            # The engine grille at the back.
            m.box(side * half - 0.002, side * half + 0.002, R + 0.01, R + 0.07, sill - 0.02, head - 0.04, grille)
        elif era == "motor":
            m.box(side * half - 0.0015, side * half + 0.0015, R + 0.02, nose - 0.005, sill - 0.08, sill - 0.03, band)
        else:
            m.box(side * half - 0.0015, side * half + 0.0015, R + 0.02, nose - 0.03, sill - (0.16 if low else 0.09),
                  sill - (0.05 if low else 0.03), band)
    if era == "motor":
        # The radiator shell, its lamps either side and a destination board over the windshield.
        y = F - 0.004
        m.box(-0.048, 0.048, y - 0.004, y + 0.004, fl + 0.02, 0.7, c["chrome"], bevel=0.003)
        m.box(-0.04, 0.04, y + 0.002, y + 0.005, fl + 0.05, 0.66, grille)
        lamps(m, outline, 0.64, 0.056, c)
        m.box(-0.05, 0.05, nose - 0.032, nose - 0.026, t - 0.02, t + 0.04, c["dark"])
    else:
        y = parts.outline_front(outline, fl + 0.06)
        lamps(m, outline, fl + 0.1, 0.055, c)
        m.box(-0.07, 0.07, F - 0.01, F + 0.008, fl - 0.04, fl + 0.04, c["dark"], bevel=0.004)
        if era == "brill":
            # The destination box over the windshield and the post down its middle.
            yt = parts.outline_front(outline, head + 0.02)
            m.box(-0.04, 0.04, yt - 0.006, yt + 0.001, head, head + 0.06, c["dark"], bevel=0.002)
            m.box(-0.003, 0.003, F - 0.004, F + 0.016, sill - 0.02, head - 0.04, body)
        elif era == "newlook":
            m.box(-0.05, 0.05, F - 0.024, F - 0.008, t - 0.1, t - 0.04, c["dark"], bevel=0.002)
        else:
            m.box(-0.05, 0.05, F - 0.012, F - 0.002, t - 0.1, t - 0.04, c["dark"])
    if era == "lowfloor":
        # The air conditioning pod on the roof at the back.
        m.shell([(R + 0.03, t + 0.02, 0.01, "paint"), (R + 0.13, t + 0.02, 0.01, "paint"), (R + 0.12, t + 0.14, 0.02, "paint"),
                 (R + 0.04, t + 0.14, 0.02, "paint")], 0.062, {"paint": material("roof pod", "#d4d6d8", rough=0.5)}, crown=0.03)
    elif era == "electric":
        # The batteries under a fairing along most of the roof, sloping up from over the windshield.
        m.shell([(R + 0.02, t + 0.0, 0.01, "paint"), (F - 0.06, t + 0.0, 0.0, "paint"), (F - 0.11, t + 0.17, 0.05, "paint"),
                 (R + 0.04, t + 0.17, 0.03, "paint"), (R + 0.02, t + 0.06, 0.02, "paint")], 0.07, {"paint": body}, crown=0.05)
    m.text(LETTERS, half + 0.002, (R + nose) / 2, sill - (0.1 if low else 0.05), 0.025, body, 1)


def highway_coach(m, L, era=1980):
    """An intercity coach of its era over luggage bays: a 1950 Courier with a round front and a raised rear deck, a 1965
    MC-5 on two axles with fluted sides, a 1980 MC-9 on three with big tinted windows, or a 2000 D4500 with a raked,
    curved front and one dark band of glass."""
    c = common()
    body = material("coach silver", "#c4c8cc", metal=0.6, rough=0.3)
    band = material("coach blue", "#2a4a7a", rough=0.4)
    bays = material("bay doors", "#9a9ea2", metal=0.5, rough=0.35)
    flutes = material("flutes", "#d4d8dc", metal=0.7, rough=0.25)
    R, F = -L / 2 + 0.01, L / 2 - 0.01
    half = 0.078
    fl = 0.32
    # Three axles from the MC-7 of 1968, a tag axle behind the drive.
    wheels(m, [R + 0.07, F - 0.085] if era < 1968 else [R + 0.07, R + 0.13, F - 0.085], c)
    if era < 1960:
        t, sill = 1.22, 0.8
        shape = [(R, fl, 0.015, "paint"), (F - 0.006, fl, 0.03, "paint", 0.9, 0.01), (F + 0.006, 0.6, 0.04, "paint", 0.9, 0.016),
                 (F, 0.76, 0.012, "glass", 0.9, 0.016), (F - 0.022, t - 0.06, 0.04, "paint", 0.92, 0.012), (F - 0.07, t + 0.04, 0.05, "paint"),
                 (-0.02, t + 0.04, 0.0, "paint"), (-0.05, t + 0.16, 0.03, "paint"), (R + 0.03, t + 0.16, 0.04, "paint"), (R, t + 0.02, 0.03, "paint")]
        crown = 0.08
    elif era < 1975:
        t, sill = 1.3, 0.84
        shape = [(R, fl, 0.015, "paint"), (F - 0.004, fl, 0.02, "paint", 0.92), (F + 0.004, 0.6, 0.025, "paint", 0.92, 0.006),
                 (F, 0.74, 0.012, "glass", 0.92, 0.008), (F - 0.016, t - 0.08, 0.03, "paint", 0.93, 0.006), (F - 0.045, t + 0.04, 0.04, "paint"),
                 (R + 0.02, t + 0.04, 0.03, "paint"), (R, t - 0.1, 0.02, "paint")]
        crown = 0.07
    elif era < 1995:
        t, sill = 1.34, 0.85
        shape = [(R, fl, 0.015, "paint"), (F - 0.004, fl, 0.02, "paint", 0.92), (F + 0.004, 0.6, 0.025, "paint", 0.92, 0.004),
                 (F, 0.74, 0.012, "glass", 0.92, 0.006), (F - 0.018, t - 0.1, 0.04, "paint", 0.93, 0.004), (F - 0.055, t + 0.02, 0.04, "paint"),
                 (R + 0.02, t + 0.02, 0.03, "paint"), (R, t - 0.12, 0.02, "paint")]
        crown = 0.07
    else:
        t, sill = 1.38, 0.82
        shape = [(R, fl, 0.015, "paint"), (F - 0.002, fl, 0.02, "paint", 0.92, 0.006), (F + 0.004, 0.5, 0.02, "paint", 0.92, 0.01),
                 (F + 0.002, 0.58, 0.02, "glass", 0.92, 0.012), (F - 0.04, t - 0.02, 0.05, "paint", 0.94, 0.008), (F - 0.08, t + 0.02, 0.04, "paint"),
                 (R + 0.02, t + 0.02, 0.03, "paint"), (R, t - 0.12, 0.02, "paint")]
        crown = 0.07
    outline = m.shell(shape, half, {"paint": body, "glass": c["glass"]}, crown=crown)
    w0, w1 = R + 0.03, F - 0.07
    for side in (-1, 1):
        if era < 1975:
            # Fluted aluminium along the bays, a band and separate windows over them.
            for k in range(4):
                z = fl + 0.04 + k * 0.045
                m.box(side * half - 0.002, side * half + 0.002, R + 0.015, F - 0.03, z, z + 0.02, flutes)
            m.box(side * half - 0.0015, side * half + 0.0015, R + 0.01, F - 0.03, sill - 0.12, sill - 0.04, band)
        else:
            m.box(side * half - 0.0015, side * half + 0.0015, R + 0.01, F - 0.03, 0.5, 0.62 if era < 1995 else 0.58, band)
            for k in range(3):
                y = R + 0.05 + k * 0.1
                m.box(side * half - 0.0012, side * half + 0.0012, y, y + 0.07, fl + 0.06, fl + 0.15, bays)
    if era < 1960:
        windows(m, w0, -0.06, sill + 0.02, t + 0.04, 3, half + 0.0005, c, gap=0.15)
        windows(m, -0.03, w1, sill - 0.02, t - 0.1, 3, half + 0.0005, c, gap=0.15)
    elif era < 1975:
        windows(m, w0, w1, sill, t - 0.1, 6, half + 0.0005, c, gap=0.12)
    elif era < 1995:
        windows(m, w0, w1, sill, t - 0.12, 5, half + 0.0005, c, gap=0.06)
    else:
        m.box(-half - 0.001, half + 0.001, w0, F - 0.06, sill - 0.04, t - 0.1, c["glass"])
    lamps(m, outline, 0.45, 0.055, c)
    m.box(-0.07, 0.07, F - 0.01, F + 0.006, 0.28, 0.36, c["dark"], bevel=0.004)
    # The destination sign over the windshield.
    yt = parts.outline_front(outline, t - 0.07)
    m.box(-0.05, 0.05, yt - 0.01, yt - 0.002, t - 0.12, t - 0.06, c["dark"])
    m.text(LETTERS, half + 0.002, 0.0, 0.56, 0.025, c["chrome"] if era >= 1975 else body, 1)


# How long each cab is, from its back to its bumper, in tiles.
CAB_LEN = {"early": 0.15, "round": 0.17, "square": 0.17, "fifties": 0.22, "long": 0.25, "sleeper": 0.32, "aero": 0.27, "cabover": 0.1}
# Each cab's roof, in steps, for bodies sized to it.
CAB_TOP = {"early": 0.9, "round": 0.9, "square": 0.96, "fifties": 1.16, "long": 1.34, "sleeper": 1.34, "aero": 1.62, "cabover": 1.32}
# How far the steer axle sits behind each cab's front, in tiles: under the front of the hood, about a metre back.
FRONT_AXLE = {"early": 0.045, "round": 0.045, "square": 0.045, "fifties": 0.05, "long": 0.05, "sleeper": 0.05, "aero": 0.05, "cabover": 0.045}
# From a truck or trailer's back to its last axle, in tiles, about a metre and a quarter.
REAR_AXLE = 0.05


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
    yw, zc = y1 - FRONT_AXLE["early"], WHEEL_R / rig.RISE
    ro = WHEEL_R * 1.25
    upper = [(yw + math.cos(math.radians(d)) * ro, zc + math.sin(math.radians(d)) * ro / rig.RISE) for d in range(15, 166, 15)]
    upper += [(yw - ro * 1.5, 0.31), (y0 - 0.01, 0.31)]
    lower = [(y, z - 0.03) for y, z in reversed(upper)]
    with m.keep():
        for side in (-1, 1):
            m.shell([(y, z, 0, "paint") for y, z in upper + lower], 0.013, {"paint": black}, x=side * 0.075, cols=(-1, 1))
    return hood


def _stack(m, x, y, top, c):
    """An exhaust stack standing at x, y up to top steps, with a heat shield and its end cut on a slant."""
    m.upright(x, y, 0.42, top, 0.0055, 0.0055, c["chrome"], segments=10)
    m.upright(x, y, 0.9, 1.3, 0.0078, 0.0078, c["chrome"], segments=12)
    m.rod((x, y, top - 0.02), (x, y - 0.008, top + 0.05), 0.0055, c["chrome"], segments=10)


def _fuel_tanks(m, y0, y1, c, half=0.064):
    """Round fuel tanks along the frame each side from y0 to y1, with a step at the front of each."""
    zc = 0.2 + 0.024 / rig.RISE
    with m.keep():
        for side in (-1, 1):
            m.tube(y0, y1, side * half, zc, 0.024, c["chrome"], segments=16)
            for y in (y0 + 0.008, y1 - 0.008):
                m.tube(y - 0.002, y + 0.002, side * half, zc, 0.0255, c["dark"], segments=16)


def _long_cab(m, y0, y1, c, paint, style, era, stacks):
    """A long-nose conventional of the Kenworth W900, Hayes or Western Star kind: a long square hood behind a tall chrome
    grille, flat-topped fenders over the steer wheels, air cleaners on the cowl, a flat two-piece windshield, stacks
    behind the cab ("back") or beside the doors ("front") and fuel tanks under the doors. As "sleeper", a sleeper box
    behind, flat on top level with the cab before 1980 and raised over it after. Returns where the hood meets the cab."""
    half = 0.075
    top = CAB_TOP[style]
    grille = material("grille", "#3a3836", metal=0.4, rough=0.5)
    mats = {"paint": paint, "glass": c["glass"], "grille": grille}
    hood = y1 - 0.16
    hw = 0.74
    cb = y0 + (0.07 if style == "sleeper" else 0.0)
    # The hood runs level from the cowl on older trucks and drops a little to the grille on later ones.
    cowl, nose = (0.88, 0.87) if era < 1980 else (0.88, 0.82)
    if style == "sleeper" and era >= 1980:
        back = [(cb, top, 0.01, "paint"), (cb - 0.01, 1.52, 0.025, "paint"), (y0, 1.52, 0.015, "paint")]
    else:
        back = [(y0, top, 0.012, "paint")]
    shape = [(y0, 0.34, 0.01, "paint"), (hood, 0.34, 0, "paint"), (hood, 0.34, 0, "paint", hw), (y1 - 0.006, 0.34, 0.004, "paint", hw),
             (y1 - 0.006, nose, 0.01, "paint", hw), (hood + 0.01, cowl, 0.004, "paint", hw), (hood, cowl, 0, "paint", hw), (hood, cowl, 0, "paint"),
             (hood - 0.003, cowl + 0.04, 0.006, "glass"), (hood - 0.013, 1.27, 0.01, "paint"), (hood - 0.022, top, 0.014, "paint")] + back
    m.shell(shape, half, mats, crown=0.04)
    if style == "sleeper" and era < 1980:
        for side in (-1, 1):
            m.box(side * half - 0.0015, side * half + 0.0015, cb - 0.0015, cb + 0.0015, 0.4, top - 0.02, c["dark"])
    # The grille: a tall chrome shell with a dark core and a bar across, near the hood's full width.
    m.box(-0.05, 0.05, y1 - 0.008, y1 - 0.002, 0.36, nose + 0.02, c["chrome"], bevel=0.003)
    m.box(-0.042, 0.042, y1 - 0.0025, y1 - 0.001, 0.4, nose - 0.03, grille)
    m.box(-0.042, 0.042, y1 - 0.0024, y1 - 0.0005, (0.4 + nose) / 2 - 0.01, (0.4 + nose) / 2 + 0.01, c["chrome"])
    # Fenders: flat-topped wings beside the hood, arched over the steer wheels and stopping behind them.
    yw, zc = y1 - FRONT_AXLE[style], WHEEL_R / rig.RISE
    ro = WHEEL_R * 1.3
    arch = [(yw + math.cos(math.radians(d)) * ro, zc + math.sin(math.radians(d)) * ro / rig.RISE, 0, "paint") for d in range(20, 161, 20)]
    wing = [(y1 - 0.004, 0.26, 0, "paint")] + arch + [(yw - ro - 0.012, 0.26, 0, "paint"), (yw - ro - 0.012, 0.5, 0.006, "paint"),
                                                         (y1 - 0.004, 0.53, 0.016, "paint")]
    with m.keep():
        for side in (-1, 1):
            m.shell(wing, 0.016, {"paint": paint}, x=side * 0.07, cols=(-1, 1))
            m.tube(y1 - 0.012, y1 + 0.002, side * 0.07, 0.53, 0.012, c["chrome"], segments=12)
            m.tube(y1 + 0.001, y1 + 0.003, side * 0.07, 0.53, 0.009, c["light"], segments=12)
    for side in (-1, 1):
        # Air cleaners standing on the cowl each side, and the stacks.
        m.upright(side * 0.064, hood + 0.022, 0.6, 1.0, 0.014, 0.014, c["chrome"], segments=14)
        if stacks == "back":
            _stack(m, side * 0.062, y0 - 0.007, 1.95, c)
        else:
            _stack(m, side * (half + 0.008), hood - 0.008, 1.85, c)
    if era < 1980:
        # A sun visor over the windshield.
        m.box(-0.07, 0.07, hood - 0.024, hood - 0.006, top - 0.05, top - 0.02, paint, bevel=0.002)
    _fuel_tanks(m, cb + 0.006, hood + 0.016, c)
    for side in (-1, 1):
        m.box(side * half - 0.001, side * half + 0.001, hood - 0.054, hood - 0.014, cowl + 0.08, top - 0.1, c["glass"])
        if style == "sleeper" and era >= 1980:
            m.box(side * half - 0.001, side * half + 0.001, y0 + 0.02, y0 + 0.04, 1.2, 1.32, c["glass"])
    m.box(-0.07, 0.07, y1 - 0.004, y1 + 0.01, 0.26, 0.36, c["chrome"], bevel=0.003)
    return hood


def _fifties_cab(m, y0, y1, c, paint):
    """A 1950s highway tractor of the International R or Mack B kind: a rounded hood narrower than the cab between
    pontoon fenders, a tall grille, a two-piece windshield under a rounded roof, a stack beside the cab and a fuel tank
    under the door."""
    half = 0.074
    top = CAB_TOP["fifties"]
    grille = material("grille", "#3a3836", metal=0.4, rough=0.5)
    mats = {"paint": paint, "glass": c["glass"], "grille": grille}
    hood = y1 - 0.12
    hw = 0.7
    shape = [(y0, 0.32, 0.01, "paint"), (hood, 0.32, 0, "paint"), (hood, 0.32, 0, "paint", hw), (y1 - 0.01, 0.32, 0.012, "grille", hw),
             (y1 - 0.002, 0.7, 0.03, "paint", hw), (hood + 0.02, 0.78, 0.03, "paint", hw), (hood, 0.79, 0, "paint", hw), (hood, 0.79, 0, "paint"),
             (hood - 0.003, 0.82, 0.006, "glass"), (hood - 0.014, 1.04, 0.012, "paint"), (hood - 0.03, top, 0.024, "paint"), (y0, top, 0.016, "paint")]
    outline = m.shell(shape, half, mats, crown=0.07)
    lamps(m, outline, 0.56, 0.06, c)
    m.box(-0.002, 0.002, hood - 0.007, hood - 0.003, 0.82, 1.04, paint)
    for k in range(5):
        x = -0.03 + 0.015 * k
        m.box(x - 0.002, x + 0.002, y1 - 0.009, y1 - 0.003, 0.36, 0.66, c["chrome"])
    for side in (-1, 1):
        fender(m, y1 - FRONT_AXLE["fifties"], side, c, paint)
        m.box(side * half - 0.001, side * half + 0.001, hood - 0.05, hood - 0.012, 0.84, top - 0.08, c["glass"])
    _stack(m, 0.062, y0 - 0.007, 1.62, c)
    with m.keep():
        m.tube(y0 + 0.006, hood - 0.006, -0.064, 0.2 + 0.022 / rig.RISE, 0.022, c["chrome"], segments=16)
    m.box(-0.07, 0.07, y1 - 0.004, y1 + 0.01, 0.26, 0.36, c["chrome"], bevel=0.003)
    return hood


def _cab(m, y0, y1, c, paint, style, era=1970, stacks="back"):
    """A truck's cab from y0 to its front at y1, by era: an open 1910s C-cab, a 1930s rounded hood with fenders, a squarer
    1960s hood with a wide grille, a 1950s highway tractor, a long highway hood (with a sleeper behind as "sleeper"), a
    sloped aero hood with a tall roof, or a cab-over. Returns where the hood meets the cab."""
    if style == "early":
        return _early_cab(m, y0, y1, c, paint)
    if style in ("long", "sleeper"):
        return _long_cab(m, y0, y1, c, paint, style, era, stacks)
    if style == "fifties":
        return _fifties_cab(m, y0, y1, c, paint)
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
            # Fairings over the tanks between the steer wheels and the cab's back, and extenders closing the gap to the trailer.
            m.box(side * half - 0.003, side * half + 0.001, y0 + 0.01, hood - 0.07, 0.26, 0.62, paint, bevel=0.004)
            m.box(side * half - 0.003, side * half, y0 - 0.011, y0 + 0.004, 0.6, top - 0.1, paint, bevel=0.002)
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
    if style in ("round", "square"):
        for side in (-1, 1):
            fender(m, y1 - FRONT_AXLE[style], side, c, paint)
    door = hood - 0.012 if style != "cabover" else y0 + 0.05
    for side in (-1, 1):
        m.box(side * half - 0.001, side * half + 0.001, door - 0.04, door, win[0] if style != "cabover" else 0.82, win[1], c["glass"])
    if style == "aero":
        # A bumper moulded in the body colour, wrapping round the corners.
        m.box(-0.074, 0.074, y1 - 0.01, y1 + 0.006, 0.24, 0.4, paint, bevel=0.007)
    else:
        m.box(-0.068, 0.068, y1 - 0.004, y1 + 0.01, 0.28, 0.36, c["chrome"], bevel=0.003)
    return hood


# Each kind's paint, the load's colour, and its body's colour where that differs.
PAINTS = {"mail": "#b8322a", "stake": "#6a4a2a", "flatbed": "#2a5a8a", "dump": "#d8a020", "tank": "#d8d8d4", "log": "#3a5a2a",
          "cattle": "#8a2a22", "plow": "#e07a20"}


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


def tank(m, y0, y1, z0, z1, half, mat, domes, c, walkway=False):
    """A tank of elliptical section from y0 to y1 with dished heads, z0 to z1 steps and half tiles each side of the
    middle, with domes along the top and a walkway between spill rails if asked."""
    zc, hz = (z0 + z1) / 2, (z1 - z0) / 2
    ring = [(math.cos(2 * math.pi * k / 24), math.sin(2 * math.pi * k / 24)) for k in range(24)]
    heads = [(0.0, 0.5), (0.004, 0.8), (0.009, 0.93), (0.016, 1.0)]
    stations = [(y0 + d, f) for d, f in heads] + [(y1 - d, f) for d, f in reversed(heads)]
    m.loft([(y, [(half * f * u, zc + hz * f * v) for u, v in ring]) for y, f in stations], {"paint": mat})
    for k in range(domes):
        y = y0 + (y1 - y0) * (k + 0.5) / domes
        m.upright(0.0, y, z1 - 0.06, z1 + 0.05, 0.017, 0.015, c["dark"], segments=14)
    if walkway:
        m.box(-0.014, 0.014, y0 + 0.02, y1 - 0.02, z1 - 0.02, z1 + 0.01, c["hub"])
        for side in (-1, 1):
            m.rod((side * 0.03, y0 + 0.02, z1 + 0.07), (side * 0.03, y1 - 0.02, z1 + 0.07), 0.0018, c["hub"])
            for k in range(4):
                y = y0 + 0.02 + (y1 - y0 - 0.04) * k / 3
                m.rod((side * 0.03, y, z1 - 0.03), (side * 0.03, y, z1 + 0.07), 0.0016, c["hub"])


def truck(m, L, kind, cab="round", gen=1, plow="wedge", lifts=0, era=1970):
    """A truck: a cab of its era and a body for its load, sized to the cab's roof. Later generations run more axles, and
    lifts more again, bunched just ahead of the drive. A plow truck's plow is a V plow ("wedge") or a one-way plow with a
    side wing ("wing")."""
    c = common()
    paint = material(f"{kind} {cab} truck", PAINTS[kind], rough=0.45, clearcoat=0.2)
    wood = material("truck boards", "#8a6a44", rough=0.85)
    a0, a1 = -L / 2 + 0.01, L / 2 - 0.01
    cab0 = a1 - CAB_LEN[cab]
    top = CAB_TOP[cab]
    if lifts:
        rear = [a0 + REAR_AXLE + 0.045 * k for k in range(2 + lifts)]
    elif L > 0.45 or gen > 1:
        rear = [a0 + REAR_AXLE, a0 + REAR_AXLE + 0.06]
    else:
        # A single rear axle, its overhang a little longer.
        rear = [a0 + REAR_AXLE + 0.01]
    (spoked if cab == "early" else wheels)(m, [a1 - FRONT_AXLE[cab]] + rear, c)
    m.box(-0.06, 0.06, a0, a1, 0.25, 0.35, c["dark"])
    _cab(m, cab0, a1 - (0.005 if cab == "cabover" else 0.0), c, paint, cab, era, "front")
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
        tank(m, t0, b1, 0.42, top - 0.02, 0.072 if gen == 1 else 0.076, paint, 2 if gen == 1 else 3, c, walkway=gen > 1)
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


# Trailer axles by load: one group under a single trailer, the lead's and the pup's for a B-train.
TRAILER_AXLES = {"van": (3,), "logs": (3,), "cattle": (2,), "tank": (3, 2), "hopper": (3, 2)}
# Between axles in a group, in tiles.
AXLE_GAP = 0.047


def tractor(m, L, trailer, cab, paint_hex, bumper=False, era=1970, drive=2, axles=None, stacks="back"):
    """A highway tractor of its era pulling a trailer: a van, a B-train of tanks or grain hoppers, a cattle liner, or long
    logs on a pole trailer. bumper adds a moose bumper. drive is the tractor's driving axles; axles the trailer's, one
    count per trailer, by default TRAILER_AXLES. A B-train's lead trailer runs back on its axles to a fifth wheel the
    pup's front sits on."""
    c = common()
    paint = material(f"{trailer} {cab} tractor", paint_hex, rough=0.4, clearcoat=0.3)
    a0, a1 = -L / 2 + 0.01, L / 2 - 0.01
    cab0 = a1 - CAB_LEN[cab]
    axles = axles or TRAILER_AXLES[trailer]
    drives = [cab0 - 0.04] if drive == 1 else [cab0 - 0.03 - AXLE_GAP * k for k in range(drive)]
    rear = [a0 + REAR_AXLE + AXLE_GAP * k for k in range(axles[-1])]
    lead = [rear[-1] + 0.07 + AXLE_GAP * k for k in range(axles[0])] if len(axles) > 1 else []
    wheels(m, [a1 - FRONT_AXLE[cab]] + drives + rear + lead, c)
    m.box(-0.06, 0.06, drives[-1] - 0.035, a1, 0.25, 0.35, c["dark"])
    _cab(m, cab0, a1, c, paint, cab, era, stacks)
    if bumper:
        guard(m, a1, c, 0.95 if cab != "aero" else 0.75)
    # The fifth wheel over the drive.
    fifth = sum(drives) / len(drives)
    if trailer != "logs":
        m.box(-0.05, 0.05, fifth - 0.025, fifth + 0.025, 0.35, 0.41, c["dark"], bevel=0.004)
    t0, t1 = a0, cab0 - 0.012
    if lead:
        # Where the pup's front sits on the lead's tail, over the lead's middle axle.
        j = lead[len(lead) // 2] + 0.02
        m.box(-0.05, 0.05, lead[0] - 0.03, j + 0.01, 0.34, 0.42, c["dark"])
        m.box(-0.05, 0.05, j - 0.04, j - 0.004, 0.42, 0.46, c["dark"], bevel=0.004)
        bodies = ((j + 0.006, t1), (t0, j - 0.006))
    else:
        bodies = ((t0, t1),)
    alu = material("aluminium", "#c4c8cc", metal=0.7, rough=0.3)
    if trailer == "van":
        if cab == "fifties":
            # A 1950s riveted aluminium van with ribs down its sides, rounded at the front, on landing legs.
            m.shell([(t0, 0.42, 0.004, "paint"), (t1, 0.42, 0.004, "paint"), (t1, 1.38, 0.03, "paint"), (t0, 1.38, 0.006, "paint")],
                    0.078, {"paint": alu}, crown=0.04)
            for k in range(1, 12):
                y = t0 + (t1 - t0) * k / 12
                for side in (-1, 1):
                    m.box(side * 0.078 - 0.0012, side * 0.078 + 0.0012, y - 0.0015, y + 0.0015, 0.44, 1.34, alu)
            for side in (-1, 1):
                m.box(side * 0.0782 - 0.001, side * 0.0782 + 0.001, t0 + 0.01, t1 - 0.03, 0.5, 0.55, paint)
                m.box(side * 0.05 - 0.003, side * 0.05 + 0.003, t1 - 0.12, t1 - 0.114, 0.12, 0.42, c["dark"])
            m.text(LETTERS, 0.0792, (t0 + t1) / 2, 1.0, 0.03, paint, 1)
        else:
            m.box(-0.078, 0.078, t0, t1, 0.42, 1.55, material("trailer", "#e8e8e4", rough=0.4), bevel=0.006)
            for side in (-1, 1):
                m.box(side * 0.0782 - 0.001, side * 0.0782 + 0.001, t0 + 0.01, t1 - 0.01, 0.5, 0.56, paint)
                if cab == "aero":
                    m.box(side * 0.075 - 0.002, side * 0.075 + 0.002, t0 + 0.2, t1 - 0.12, 0.28, 0.42, material("skirt", "#5a5e62", rough=0.5))
            m.text(LETTERS, 0.0792, (t0 + t1) / 2, 1.1, 0.034, paint, 1)
    elif trailer == "tank":
        # Smooth elliptical tanks, lower than a van, each on a frame with a walkway and three domes on top.
        for y0, y1 in bodies:
            m.box(-0.05, 0.05, y0 + 0.01, y1 - 0.01, 0.36, 0.5, c["dark"])
            tank(m, y0, y1, 0.48, 1.24, 0.077, alu, 3, c, walkway=True)
    elif trailer == "hopper":
        # A Super B of grain hoppers: two trailers, each with a pair of hoppers under a tarp.
        tarp = material("hopper tarp", "#3a4a5a", rough=0.7)
        for y0, y1 in bodies:
            m.shell([(y0, 0.75, 0.006, "paint"), (y0 + 0.03, 0.4, 0.004, "paint"), ((y0 + y1) / 2 - 0.02, 0.4, 0.004, "paint"),
                     ((y0 + y1) / 2, 0.62, 0, "paint"), ((y0 + y1) / 2 + 0.02, 0.4, 0.004, "paint"), (y1 - 0.03, 0.4, 0.004, "paint"),
                     (y1, 0.75, 0.006, "paint"), (y1, 1.35, 0.008, "paint"), (y0, 1.35, 0.008, "paint")], 0.078, {"paint": alu})
            m.shell([(y0 + 0.005, 1.34, 0, "paint"), (y1 - 0.005, 1.34, 0, "paint"), (y1 - 0.01, 1.42, 0.01, "paint"), (y0 + 0.01, 1.42, 0.01, "paint")],
                    0.074, {"paint": tarp}, crown=0.05)
    elif trailer == "cattle":
        # A cattle liner: aluminium with a raised gooseneck over the fifth wheel, a dropped belly between it and the axles,
        # and rows of air slots down its sides.
        m.shell([(t0, 0.5, 0.01, "paint"), (t0 + 0.18, 0.5, 0, "paint"), (t0 + 0.2, 0.32, 0.01, "paint"), (t1 - 0.11, 0.32, 0.01, "paint"),
                 (t1 - 0.09, 0.62, 0, "paint"), (t1, 0.62, 0.01, "paint"), (t1, 1.5, 0.012, "paint"), (t0, 1.5, 0.012, "paint")],
                0.078, {"paint": alu})
        slot = material("air slots", "#2a2a2c", rough=0.8)
        for side in (-1, 1):
            for z in (0.86, 1.1, 1.32):
                m.box(side * 0.078 - 0.001, side * 0.078 + 0.001, t0 + 0.02, t1 - 0.02, z, z + 0.05, slot)
            m.box(side * 0.078 - 0.001, side * 0.078 + 0.001, t0 + 0.02, t0 + 0.17, 0.62, 0.67, slot)
            m.box(side * 0.078 - 0.001, side * 0.078 + 0.001, t0 + 0.21, t1 - 0.12, 0.5, 0.55, slot)
            m.box(side * 0.078 - 0.0015, side * 0.078 + 0.0015, t0 + 0.2, t1 - 0.11, 0.36, 0.44, paint)
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
        # A headache rack behind the cab, over its roof, to stop the logs shifting forward.
        y = cab0 - 0.009
        for x in (-0.066, -0.022, 0.022, 0.066):
            m.box(x - 0.003, x + 0.003, y - 0.003, y + 0.003, 0.36, 1.5, c["dark"])
        for z in (0.95, 1.22, 1.48):
            m.box(-0.07, 0.07, y - 0.004, y + 0.004, z - 0.025, z + 0.025, c["dark"], bevel=0.002)


def van(m, L, era):
    """A walk-in delivery van: a step van with a short sloped nose, or a battery van with one smooth curve from bumper to roof."""
    c = common()
    body = material(f"{era} van", "#ecebe6", rough=0.4, clearcoat=0.3)
    band = material("mail red", "#c8302a", rough=0.4)
    grille = material("grille", "#3a3836", metal=0.4, rough=0.5)
    R, F = -L / 2 + 0.01, L / 2 - 0.01
    half = 0.078
    wheels(m, [R + 0.065, F - 0.055], c)
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


def _section_ring(w, zlo, ztop, half, zs, roof, square=2.6, n_roof=12):
    """A body's cross-section as (x, z) points round from the bottom right: straight sides with a point at each height
    in zs (clamped under the roof), then the roof rounding over, roof steps deep at most, square sets how square."""
    xw = half * w
    rt = min(roof, (ztop - zlo) * 0.6)
    eaves = ztop - rt
    side = [zlo] + list(zs)
    pts = []
    for i, z in enumerate(side):
        z = min(max(z, zlo + 0.0005 * i), eaves - 0.0005 * (len(side) - i))
        pts.append((xw, z))
    for k in range(n_roof + 1):
        a = math.pi * k / n_roof
        c, s = math.cos(a), math.sin(a)
        pts.append((xw * math.copysign(abs(c) ** (2 / square), c), eaves + rt * abs(s) ** (2 / square)))
    pts += [(-x, z) for x, z in reversed(pts[:len(side)])]
    return pts


def _skin(m, sections, classify, mats, sharp=40):
    """A body lofted through sections, each (y, ring of (x, z in steps)), every ring the same length. Each face takes
    the material classify(x, y, z) names for its middle, in the builder's heights, so windows and bands follow the
    shape round its ends. The two end rings are closed with paint."""
    keys = list(mats)
    bm = bmesh.new()
    rings = [[bm.verts.new((x, y, m.z(z) * rig.RISE)) for x, z in ring] for y, ring in sections]
    n = len(sections[0][1])
    for (ya, ra), (yb, rb), va, vb in zip(sections, sections[1:], rings, rings[1:]):
        for k in range(n):
            j = (k + 1) % n
            face = bm.faces.new((va[k], va[j], vb[j], vb[k]))
            x = (ra[k][0] + ra[j][0] + rb[k][0] + rb[j][0]) / 4
            z = (ra[k][1] + ra[j][1] + rb[k][1] + rb[j][1]) / 4
            face.material_index = keys.index(classify(x, (ya + yb) / 2, z) if k < n - 1 else "paint")
    for ring in (rings[0], rings[-1]):
        face = bm.faces.new(ring)
        face.material_index = keys.index("paint")
        bmesh.ops.triangulate(bm, faces=[face])
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-6)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    for f in bm.faces:
        f.smooth = True
    for e in bm.edges:
        if len(e.link_faces) == 2 and e.calc_face_angle(0.0) > math.radians(sharp):
            e.smooth = False
    obj = m._mesh("skin", bm, None)
    for k in keys:
        obj.data.materials.append(mats[k])
    return obj


def streetcar(m, L, era):
    """A streetcar: a wooden single-truck car with closed vestibules and a monitor roof, a PCC with bullet ends, an
    angular CLRV, or a five-section low-floor tram."""
    c = common()
    paint = {"wood": ("#8a2a22", "#e6c870"), "pcc": ("#c8302a", "#ece6d6"), "clrv": ("#c8302a", "#ece6d6"), "flexity": ("#c8302a", "#2a2a2a")}[era]
    body = material(f"{era} tram", paint[0], rough=0.4, clearcoat=0.3)
    trim = material(f"{era} trim", paint[1], rough=0.4)
    a0, a1 = -L / 2 + 0.01, L / 2 - 0.01
    if era == "wood":
        floor = 0.42
        roof = material("car roof", "#6a625a", rough=0.75)
        deck = old_car(m, a0, a1, 0.082, floor, 1.1, 0.065, {"paint": body, "trim": trim, "roof": roof}, c, True, 8)
        # One small truck: four wheels showing between the bars of its side frames, and the trolley pole raked back
        # from the deck.
        r = 0.024
        zw = r / rig.RISE
        with m.keep():
            for side in (-1, 1):
                for y in (-0.06, 0.06):
                    m.tube_x(side * 0.066 - 0.006, side * 0.066 + 0.006, y, zw, r, c["dark"])
                    m.tube_x(side * 0.073 - 0.002, side * 0.073 + 0.002, y, zw, r * 0.4, c["hub"])
                for z0, z1 in ((zw - 0.03, zw + 0.03), (zw + 0.2, zw + 0.25)):
                    m.box(side * 0.076 - 0.003, side * 0.076 + 0.003, -0.093, 0.093, z0, z1, c["dark"], bevel=0.002)
                m.box(side * 0.076 - 0.003, side * 0.076 + 0.003, -0.012, 0.012, zw - 0.03, zw + 0.25, c["dark"])
        m.box(-0.055, 0.055, -0.08, 0.08, m.fixed(zw + 0.2), floor - 0.04, c["dark"])
        m.rod((0.0, a0 + L * 0.5, deck), (0.0, a0 + L * 0.14, deck + 0.42), 0.004, c["dark"])
        m.box(-0.016, 0.016, a0 + L * 0.48, a0 + L * 0.54, deck - 0.01, deck + 0.03, c["dark"])
        m.text(LETTERS, 0.0842, 0.0, floor + 0.2, 0.025, trim, 1)
        return
    if era == "flexity":
        _flexity(m, a0, a1, body, trim, c)
        return
    _modern_car(m, L, era, a0, a1, body, trim, c)


# Each modern car's nose, from the tip back, as (distance from the tip in tiles, width of half, bottom, top in steps),
# and its bands as (bottom, top, part) in steps.
TRAM = {
    # The PCC: a rounded bullet end, narrowing in plan and dropping over the windshield, skirts down over the trucks,
    # standee windows over the main ones and cream belts.
    "pcc": {"zlo": 0.33, "top": 1.3, "roof": 0.16, "square": 2.4,
            "nose": [(0.0, 0.5, 0.46, 0.7), (0.008, 0.66, 0.39, 0.8), (0.02, 0.78, 0.35, 0.92), (0.036, 0.87, 0.33, 1.04),
                     (0.056, 0.94, 0.33, 1.16), (0.08, 0.985, 0.33, 1.25), (0.105, 1.0, 0.33, 1.3)],
            "bands": [(0.42, 0.46, "trim"), (0.7, 0.76, "trim"), (0.8, 1.06, "glass"), (1.1, 1.17, "standee"), (1.2, 1.23, "trim")]},
    # The CLRV: square-cornered, a flat dash and a flat windshield sloping back, a deep black band round the windows
    # and a white stripe low down.
    "clrv": {"zlo": 0.35, "top": 1.34, "roof": 0.07, "square": 7,
             "nose": [(0.0, 0.9, 0.42, 0.74), (0.004, 0.95, 0.37, 0.77), (0.008, 0.97, 0.35, 0.8), (0.036, 0.985, 0.35, 1.26),
                      (0.044, 1.0, 0.35, 1.34)],
             "bands": [(0.47, 0.53, "trim"), (0.76, 1.16, "glass")]},
}


def _ends(y0, y1, nose, zlo, top, mid, front=True, back=True):
    """Sections (y, width, bottom, top) along a body from y0 to y1, shaped by nose at each end asked for, square
    elsewhere, with a section at each y in mid as well."""
    secs = [(y, 1.0, zlo, top) for y in mid if y0 + nose[-1][0] < y < y1 - nose[-1][0]]
    if back:
        secs += [(y0 + d, w, lo, hi) for d, w, lo, hi in nose]
    else:
        secs += [(y0, 1.0, zlo, top)]
    if front:
        secs += [(y1 - d, w, lo, hi) for d, w, lo, hi in nose]
    else:
        secs += [(y1, 1.0, zlo, top)]
    return sorted(secs)


def _band_of(bands, z):
    for z0, z1, part in bands:
        if z0 <= z <= z1:
            return part
    return "paint"


def _pane_edges(y0, y1, n, pillar):
    """The window edges along y0 to y1 for n windows, each followed by a pillar pillar of the window spacing wide."""
    step = (y1 - y0) / n
    return [y0 + k * step + d for k in range(n) for d in (0.0, step * (1 - pillar))] + [y1], step


def _modern_car(m, L, era, a0, a1, body, trim, c):
    """A PCC or a CLRV: one body on two trucks, both ends shaped alike as they run either way round or turn on loops."""
    spec = TRAM[era]
    half = 0.085
    zlo, top = spec["zlo"], spec["top"]
    zc = WHEEL_R / rig.RISE
    for y in (a0 + L * 0.17, a1 - L * 0.17):
        for side in (-1, 1):
            for dy in (-0.03, 0.03):
                with m.keep():
                    m.tube_x(side * 0.065 - 0.008, side * 0.065 + 0.008, y + dy, zc, WHEEL_R * 0.9, c["dark"])
        m.box(-0.07, 0.07, y - 0.05, y + 0.05, m.fixed(zc + 0.02), zlo + 0.05, c["dark"])
    nose = spec["nose"]
    w0, w1 = a0 + nose[-1][0] + 0.012, a1 - nose[-1][0] - 0.012
    n = max(4, round((w1 - w0) / 0.05))
    edges, step = _pane_edges(w0, w1, n, 0.18)
    band = [b for b in spec["bands"] if b[2] == "glass"][0]
    mats = {"paint": body, "trim": trim, "glass": c["glass"], "band": material("window band", "#161616", rough=0.3)}

    def classify(x, y, z):
        part = _band_of(spec["bands"], z)
        side = abs(x) > half * 0.8 and w0 - 0.012 < y < w1 + 0.012
        if part in ("glass", "standee") and side:
            if y < w0 or y > w1 or (y - w0) % step > step * 0.82:
                return "band" if era == "clrv" else "paint"
        if part == "standee":
            return "glass" if side else "paint"
        if part == "glass" and era == "clrv" and abs(x) <= half * 0.8 and band[0] + 0.02 > z:
            return "band"
        return part
    zs = sorted({z for b in spec["bands"] for z in b[:2] if z < top})
    secs = _ends(a0, a1, nose, zlo, top, edges)
    _skin(m, [(y, _section_ring(w, lo, hi, half, zs, spec["roof"], spec["square"])) for y, w, lo, hi in secs], classify, mats)
    for end in (-1, 1):
        y = a1 if end > 0 else a0
        if era == "pcc":
            # One headlight in the middle of the dash, a bumper under it.
            m.tube(y - 0.004, y + 0.004, 0.0, 0.6, 0.009, c["light"], segments=12)
            m.box(-0.05, 0.05, min(y, y + end * 0.006), max(y, y + end * 0.006), 0.4, 0.46, c["dark"], bevel=0.003)
        else:
            # Square lamps low at the corners, an anticlimber across under them.
            for s in (-1, 1):
                m.box(s * 0.058 - 0.01, s * 0.058 + 0.01, min(y, y + end * 0.002), max(y, y + end * 0.002), 0.55, 0.62, c["light"])
            m.box(-0.07, 0.07, min(y, y + end * 0.008), max(y, y + end * 0.008), 0.38, 0.47, c["dark"], bevel=0.002)
    # A trolley pole raked back to the wire, on a base on the roof.
    m.box(-0.016, 0.016, a0 + L * 0.42, a0 + L * 0.5, top - 0.01, top + 0.04, c["dark"])
    m.rod((0.0, a0 + L * 0.46, top + 0.04), (0.0, a0 + L * 0.12, top + 0.55), 0.004, c["dark"])
    m.text(LETTERS, half + 0.002, 0.0, 0.6 if era == "pcc" else 0.65, 0.025, trim, 1)


def _flexity(m, a0, a1, body, trim, c):
    """A Flexity Outlook: five sections on three trucks, flexible joints between them sitting flush under the roof
    line, a rounded raked nose with a big windshield at each end, a black window band and skirts, and a half
    pantograph on the middle section."""
    half = 0.085
    zlo, top = 0.24, 1.32
    zc = WHEEL_R / rig.RISE
    nose = [(0.0, 0.46, 0.34, 0.5), (0.008, 0.62, 0.29, 0.6), (0.02, 0.76, 0.26, 0.74), (0.036, 0.86, 0.24, 0.9),
            (0.056, 0.93, 0.24, 1.06), (0.08, 0.975, 0.24, 1.2), (0.105, 0.995, 0.24, 1.29), (0.125, 1.0, 0.24, 1.32)]
    bands = [(0.0, 0.36, "skirt"), (0.56, 1.1, "glass"), (1.31, 9, "roof")]
    mats = {"paint": body, "skirt": material("tram skirt", "#1a1a1a", rough=0.5), "glass": material("window band", "#161616", rough=0.25),
            "roof": material("flexity roof", "#8e8a86", rough=0.6)}
    gap = 0.012
    span = a1 - a0 - 4 * gap
    sizes = [0.235, 0.165, 0.2, 0.165, 0.235]
    zs = [0.36, 0.56, 1.1, 1.31]
    y = a0
    mods = []
    for k, f in enumerate(sizes):
        mods.append((y, y + span * f))
        y += span * f + gap

    def classify(x, yy, z):
        return _band_of(bands, z)
    for k, (y0, y1) in enumerate(mods):
        joint = [(0.0, 0.985, zlo + 0.01, top - 0.008), (0.006, 1.0, zlo, top)]
        secs = []
        if k == 0:
            secs += [(y0 + d, w, lo, hi) for d, w, lo, hi in nose]
        else:
            secs += [(y0 + d, w, lo, hi) for d, w, lo, hi in joint]
        if k == 4:
            secs += [(y1 - d, w, lo, hi) for d, w, lo, hi in nose]
        else:
            secs += [(y1 - d, w, lo, hi) for d, w, lo, hi in joint]
        _skin(m, [(yy, _section_ring(w, lo, hi, half, zs, 0.14, 2.6)) for yy, w, lo, hi in sorted(secs)], classify, mats)
        if 0 < k:
            # The joint's bellows, set in from the sides and just under the roof.
            jy0, jy1 = mods[k - 1][1] - 0.002, y0 + 0.002
            ring = _section_ring(0.93, zlo + 0.04, top - 0.02, half, [0.56, 1.1], 0.12, 2.6)
            m.loft([(jy0, ring), (jy1, ring)], {"skirt": mats["skirt"]})
        # Panes along the window band, lighter than it.
        if k in (0, 4):
            p0, p1 = (y0 + 0.14, y1 - 0.02) if k == 0 else (y0 + 0.02, y1 - 0.14)
        else:
            p0, p1 = y0 + 0.02, y1 - 0.02
        windows(m, p0, p1, 0.6, 1.06, max(2, round((p1 - p0) / 0.045)), half + 0.0006, c, gap=0.12)
    # Trucks under the end sections and the middle one, hidden but for the wheels under the skirts.
    for y in (mods[0][0] + 0.07, (mods[2][0] + mods[2][1]) / 2, mods[4][1] - 0.07):
        for side in (-1, 1):
            for dy in (-0.025, 0.025):
                with m.keep():
                    m.tube_x(side * 0.065 - 0.008, side * 0.065 + 0.008, y + dy, zc * 0.85, WHEEL_R * 0.85, c["dark"])
    for end in (-1, 1):
        y = a1 if end > 0 else a0
        for s in (-1, 1):
            m.tube(min(y, y + end * 0.003) - 0.004, max(y, y + end * 0.003), s * 0.024, 0.42, 0.006, c["light"], segments=12)
    # A half pantograph: a frame on the roof, the lower arm forward to the knee, the upper arm back to the head.
    my = (mods[2][0] + mods[2][1]) / 2
    base, knee, head = top + 0.03, top + 0.32, top + 0.62
    m.box(-0.04, 0.04, my - 0.035, my + 0.035, top - 0.01, base, c["dark"])
    for s in (-1, 1):
        m.rod((s * 0.025, my - 0.03, base), (s * 0.008, my + 0.035, knee), 0.0028, c["hub"], segments=6)
    m.rod((0.0, my + 0.035, knee), (0.0, my - 0.01, head), 0.0026, c["hub"], segments=6)
    m.rod((-0.008, my - 0.03, base), (0.0, my + 0.03, knee - 0.06), 0.0012, c["hub"], segments=5)
    for dy in (-0.006, 0.006):
        m.rod((-0.05, my - 0.01 + dy, head), (0.05, my - 0.01 + dy, head), 0.002, c["hub"], segments=5)
    for s in (-1, 1):
        m.rod((s * 0.05, my - 0.01, head), (s * 0.065, my - 0.01, head - 0.05), 0.0018, c["hub"], segments=5)
    m.text(LETTERS, half + 0.002, (mods[2][0] + mods[2][1]) / 2, 0.42, 0.025, trim, 1)


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
