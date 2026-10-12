"""Diesel and electric engines, after those that ran in Canada, in our own colours.

Built facing +y, the front at +L/2, in tiles across and along, steps up, as steam.py's are. Each builder takes
its length and a livery; trucks have two or three axles.
"""
import math

import parts
import rig
from parts import material
from steam import LETTERS


def livery(scheme):
    """Body, stripe, roof and trim colours for a scheme of our own railway's."""
    schemes = {
        "maroon": ("#6a2a26", "#9a9a94", "#3a3836", "#e0b040"),
        "green": ("#2e4a36", "#e0b030", "#2a2c2a", "#e0b030"),
        "red": ("#a8302a", "#ececea", "#3a3a3a", "#ececea"),
        "black": ("#26282a", "#e07a20", "#1e1e20", "#e07a20"),
        "silver": ("#b8bcc0", "#2a5a9a", "#5a5e62", "#2a5a9a"),
        "boxcab": ("#3a4a3a", "#3a4a3a", "#2a2c2a", "#d8c890"),
        "orange": ("#d86a20", "#2a2a2a", "#3a3a3a", "#f0f0ea"),
    }
    body, stripe, roof, trim = schemes[scheme]
    return {
        "body": material(f"{scheme} body", body, rough=0.4, clearcoat=0.4),
        "stripe": material(f"{scheme} stripe", stripe, rough=0.4, clearcoat=0.3),
        "roof": material(f"{scheme} roof", roof, rough=0.6),
        "trim": material(f"{scheme} trim", trim, rough=0.4),
        "iron": material("iron", "#3a3a3c", metal=0.5, rough=0.5),
        "steel": material("steel", "#9a9a9a", metal=1.0, rough=0.3),
        "dark": material("underframe", "#1e1e20", rough=0.7),
        "glass": material("glass", "#1e2a36", rough=0.1),
        "grille": material("grille", "#2a2c2e", metal=0.4, rough=0.6),
        "rail": material("handrail", "#d8d8d0", metal=0.4, rough=0.4),
    }


def truck(m, y, c, axles=2):
    """A diesel truck of two or three axles at y along the engine: wheels, a cast side frame, springs."""
    r = 0.036
    zc = r / rig.RISE
    gap = 0.075
    ys = [y + (i - (axles - 1) / 2) * gap for i in range(axles)]
    for side in (-1, 1):
        for yy in ys:
            m.wheel(yy, side, r, (c["steel"], material("wheel dark", "#222224", rough=0.6), c["steel"]), spokes=0)
        span = (axles - 1) * gap / 2 + 0.05
        m.box(side * 0.112, side * 0.126, y - span, y + span, zc * 0.55, zc * 2.0, c["dark"], bevel=0.003)
        for yy in ys:
            m.box(side * 0.126, side * 0.13, yy - 0.012, yy + 0.012, zc * 0.7, zc * 1.4, material("journal", "#5a5c60", metal=0.4, rough=0.5))


def frame(m, L, c, axles, deck=0.62):
    """The underframe, fuel tank and trucks, with the walkway at [deck] steps."""
    a0, a1 = -L / 2 + 0.01, L / 2 - 0.01
    m.box(-0.12, 0.12, a0, a1, deck - 0.1, deck, c["dark"], bevel=0.002)
    m.box(-0.1, 0.1, -L * 0.18, L * 0.18, 0.35, deck - 0.1, c["dark"], bevel=0.01)
    inset = 0.11 if axles == 2 else 0.14
    for y in (a0 + inset, a1 - inset):
        truck(m, y, c, axles)


def letters(m, L, c, z, at=0.0, size=0.04):
    for side in (-1, 1):
        m.text(LETTERS, side * 0.1216, at, z, size, c["trim"], side)


def fans(m, y0, y1, z, c, n=3, w=0.06):
    for k in range(n):
        y = y0 + (y1 - y0) * (k + 0.5) / n
        m.upright(0, y, z, z + 0.04, w * 0.5, w * 0.5, c["grille"], segments=16)


def handrails(m, y0, y1, deck, c):
    for side in (-1, 1):
        x = side * 0.118
        m.rod((x, y0, deck + 0.45), (x, y1, deck + 0.45), 0.003, c["rail"])
        for k in range(6):
            y = y0 + (y1 - y0) * k / 5
            m.rod((x, y, deck), (x, y, deck + 0.45), 0.003, c["rail"])


def cab_unit(m, L, scheme, axles=2):
    """A streamlined cab unit, an F-unit or FPA-4 shape: a full-width body, a rounded bulldog nose and the cab up behind it."""
    c = livery(scheme)
    frame(m, L, c, axles)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.02
    nose = a1 - 0.13
    m.box(-0.12, 0.12, a0, nose, 0.62, 1.85, c["body"], bevel=0.01)
    # The nose: lower than the cab, sloping down and rounding to the front.
    m.wedge([(-0.12, nose, 0.62), (0.12, nose, 0.62), (0.1, a1, 0.62), (-0.1, a1, 0.62)],
            [(-0.12, nose, 1.45), (0.12, nose, 1.45), (0.08, a1 - 0.02, 1.2), (-0.08, a1 - 0.02, 1.2)], c["body"])
    m.wedge([(-0.12, nose - 0.08, 1.45), (0.12, nose - 0.08, 1.45), (0.12, nose, 1.45), (-0.12, nose, 1.45)],
            [(-0.11, nose - 0.08, 1.85), (0.11, nose - 0.08, 1.85), (0.11, nose - 0.02, 1.62), (-0.11, nose - 0.02, 1.62)], c["glass"])
    m.box(-0.115, 0.115, a0 + 0.01, nose - 0.08, 1.85, 1.92, c["roof"], bevel=0.006)
    # A stripe down the side sweeping to the nose, portholes and grilles.
    for side in (-1, 1):
        m.box(side * 0.1205 - 0.001, side * 0.1205 + 0.001, a0 + 0.01, nose, 0.95, 1.05, c["stripe"])
        for k in range(3):
            y = a0 + 0.08 + k * (nose - a0 - 0.2) / 2.5
            m.tube_x(side * 0.12, side * 0.123, y, 1.45, 0.018, c["glass"])
        m.box(side * 0.1205 - 0.001, side * 0.1205 + 0.001, a0 + 0.05, a0 + 0.2, 1.6, 1.8, c["grille"])
    m.box(-0.09, 0.09, a1 - 0.005, a1 + 0.002, 0.7, 1.0, c["stripe"])
    fans(m, a0 + 0.04, nose - 0.14, 1.92, c, n=4)
    letters(m, L, c, 1.25, at=(a0 + nose) / 2 - 0.05)


def hood(m, L, scheme, axles=2, wide=False, dynamic=False):
    """A road switcher: a long hood, the cab, a short hood in front; wide puts a full-width safety cab and nose on it."""
    c = livery(scheme)
    deck = 0.62
    frame(m, L, c, axles, deck)
    a0, a1 = -L / 2 + 0.03, L / 2 - 0.03
    cab0 = a1 - (0.2 if wide else 0.18)
    cab1 = cab0 + 0.1
    # The long hood, with louvres, fans and the stack.
    m.box(-0.075, 0.075, a0, cab0, deck, 1.72, c["body"], bevel=0.006)
    for side in (-1, 1):
        for k in range(5):
            y = a0 + 0.04 + k * (cab0 - a0 - 0.08) / 4.5
            m.box(side * 0.0755 - 0.001, side * 0.0755 + 0.001, y, y + 0.05, 1.2, 1.55, c["grille"])
        m.box(side * 0.0756 - 0.001, side * 0.0756 + 0.001, a0, cab0, 0.95, 1.02, c["stripe"])
    fans(m, a0 + 0.02, a0 + (cab0 - a0) * 0.45, 1.72, c, n=2 if axles == 2 else 3, w=0.07)
    m.box(-0.015, 0.015, a0 + (cab0 - a0) * 0.62, a0 + (cab0 - a0) * 0.62 + 0.03, 1.72, 1.82, c["dark"])
    if dynamic:
        m.box(-0.08, 0.08, a0 + (cab0 - a0) * 0.68, a0 + (cab0 - a0) * 0.9, 1.72, 1.82, c["body"], bevel=0.004)
        fans(m, a0 + (cab0 - a0) * 0.7, a0 + (cab0 - a0) * 0.88, 1.82, c, n=1, w=0.06)
    # The cab.
    half = 0.12 if wide else 0.11
    m.box(-half, half, cab0, cab1, deck, 1.95, c["body"], bevel=0.006)
    m.box(-half - 0.005, half + 0.005, cab0 - 0.005, cab1 + 0.005, 1.95, 2.01, c["roof"], bevel=0.004)
    for side in (-1, 1):
        m.box(side * (half + 0.0005) - 0.001, side * (half + 0.0005) + 0.001, cab0 + 0.015, cab1 - 0.015, 1.5, 1.82, c["glass"])
    m.box(-half + 0.015, half - 0.015, cab1 - 0.001, cab1 + 0.002, 1.5, 1.82, c["glass"])
    # The front: a short hood, or the wide nose of a safety cab.
    if wide:
        m.wedge([(-0.12, cab1, deck), (0.12, cab1, deck), (0.12, a1, deck), (-0.12, a1, deck)],
                [(-0.12, cab1, 1.5), (0.12, cab1, 1.5), (0.1, a1, 1.25), (-0.1, a1, 1.25)], c["body"])
        m.box(-0.1, 0.1, a1 - 0.003, a1 + 0.001, 0.75, 1.0, c["stripe"])
    else:
        m.box(-0.07, 0.07, cab1, a1, deck, 1.42, c["body"], bevel=0.006)
        m.box(-0.0705, 0.0705, a1 - 0.002, a1 + 0.001, 0.8, 1.0, c["stripe"])
    handrails(m, a0, cab0, deck, c)
    letters(m, L, c, 1.3, at=(a0 + cab0) / 2, size=0.032)


def pantograph(m, y, z, c):
    """A pantograph folded halfway up from the roof at z, its pan at the top across the engine."""
    steel = c["steel"]
    for side in (-1, 1):
        m.rod((side * 0.05, y - 0.05, z), (side * 0.03, y + 0.02, z + 0.5), 0.004, steel)
        m.rod((side * 0.03, y + 0.02, z + 0.5), (side * 0.03, y - 0.03, z + 0.95), 0.004, steel)
    m.box(-0.08, 0.08, y - 0.04, y - 0.02, z + 0.95, z + 0.99, steel)
    m.box(-0.06, 0.06, y - 0.07, y + 0.07, z, z + 0.04, c["dark"])


def boxcab(m, L, scheme="boxcab"):
    """A boxcab electric of the 1910s: a plain steel box with a cab at each end and a pantograph on the roof."""
    c = livery(scheme)
    frame(m, L, c, 2)
    a0, a1 = -L / 2 + 0.03, L / 2 - 0.03
    m.box(-0.12, 0.12, a0, a1, 0.62, 1.75, c["body"], bevel=0.005)
    m.box(-0.125, 0.125, a0 - 0.005, a1 + 0.005, 1.75, 1.82, c["roof"], bevel=0.008)
    for y in (a0, a1):
        m.box(-0.09, 0.09, y - 0.002, y + 0.002, 1.35, 1.6, c["glass"])
        m.box(-0.11, 0.11, y - 0.004, y + 0.004, 0.75, 0.8, c["trim"])
    for side in (-1, 1):
        for k in range(4):
            y = a0 + 0.06 + k * (a1 - a0 - 0.12) / 3
            m.box(side * 0.1205 - 0.001, side * 0.1205 + 0.001, y - 0.025, y + 0.025, 1.3, 1.55, c["glass"] if k in (0, 3) else c["grille"])
    pantograph(m, 0.0, 1.82, c)
    letters(m, L, c, 1.0, size=0.034)


def gf6c(m, L, scheme="orange"):
    """A big six-axle electric for coal over the mountains: a wide safety cab, a long hood and two pantographs."""
    hood(m, L, scheme, axles=3, wide=True)
    c = livery(scheme)
    a0 = -L / 2 + 0.03
    for y in (a0 + 0.08, a0 + 0.32):
        pantograph(m, y, 1.72, c)


def lrc(m, L, scheme="silver"):
    """A light, fast power car: a low rounded body and a long sloping nose."""
    c = livery(scheme)
    frame(m, L, c, 2)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    nose = a1 - 0.2
    m.box(-0.12, 0.12, a0, nose, 0.62, 1.7, c["body"], bevel=0.02)
    m.wedge([(-0.12, nose, 0.62), (0.12, nose, 0.62), (0.1, a1, 0.62), (-0.1, a1, 0.62)],
            [(-0.12, nose, 1.7), (0.12, nose, 1.7), (0.08, a1 - 0.02, 0.95), (-0.08, a1 - 0.02, 0.95)], c["body"])
    m.wedge([(-0.11, nose - 0.02, 1.62), (0.11, nose - 0.02, 1.62), (0.1, nose + 0.06, 1.42), (-0.1, nose + 0.06, 1.42)],
            [(-0.11, nose - 0.02, 1.66), (0.11, nose - 0.02, 1.66), (0.1, nose + 0.06, 1.46), (-0.1, nose + 0.06, 1.46)], c["glass"])
    for side in (-1, 1):
        m.box(side * 0.1205 - 0.001, side * 0.1205 + 0.001, a0, nose, 0.9, 1.02, c["stripe"])
        m.box(side * 0.1205 - 0.001, side * 0.1205 + 0.001, a0 + 0.04, a0 + 0.2, 1.3, 1.55, c["grille"])
    fans(m, a0 + 0.03, a0 + 0.22, 1.7, c, n=2, w=0.05)
    letters(m, L, c, 1.2, at=(a0 + nose) / 2, size=0.034)


def charger(m, L, scheme="silver"):
    """A modern passenger diesel: a full-width angular body and a raked cab front."""
    c = livery(scheme)
    frame(m, L, c, 2)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.02
    nose = a1 - 0.08
    m.box(-0.12, 0.12, a0, nose, 0.62, 1.9, c["body"], bevel=0.008)
    m.wedge([(-0.12, nose, 0.62), (0.12, nose, 0.62), (0.115, a1, 0.62), (-0.115, a1, 0.62)],
            [(-0.12, nose, 1.9), (0.12, nose, 1.9), (0.105, a1 - 0.03, 1.55), (-0.105, a1 - 0.03, 1.55)], c["body"])
    m.wedge([(-0.11, nose - 0.005, 1.86), (0.11, nose - 0.005, 1.86), (0.1, a1 - 0.025, 1.58), (-0.1, a1 - 0.025, 1.58)],
            [(-0.11, nose + 0.003, 1.86), (0.11, nose + 0.003, 1.86), (0.1, a1 - 0.017, 1.58), (-0.1, a1 - 0.017, 1.58)], c["glass"])
    for side in (-1, 1):
        m.wedge([(side * 0.1205, a0 + 0.02, 0.8), (side * 0.1215, a0 + 0.02, 0.8), (side * 0.1215, nose, 1.2), (side * 0.1205, nose, 1.2)],
                [(side * 0.1205, a0 + 0.02, 0.95), (side * 0.1215, a0 + 0.02, 0.95), (side * 0.1215, nose, 1.35), (side * 0.1205, nose, 1.35)], c["stripe"])
        m.box(side * 0.1205 - 0.001, side * 0.1205 + 0.001, a0 + 0.05, a0 + 0.3, 1.5, 1.8, c["grille"])
    m.box(-0.115, 0.115, a0 + 0.01, nose, 1.9, 1.96, c["roof"], bevel=0.005)
    fans(m, a0 + 0.05, a0 + 0.3, 1.96, c, n=2, w=0.06)
    letters(m, L, c, 1.6, at=(a0 + nose) / 2 + 0.05, size=0.032)
