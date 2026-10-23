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
        # After real ones, as (body, stripe, roof, trim, lower): CP maroon and grey, CN green and black, VIA blue and
        # yellow, BC Rail red, white and blue, VIA's Charger grey and yellow.
        "cp_maroon": ("#6a2420", "#a4a6a4", "#3a3a3a", "#e0b838", "#6a2420"),
        "cn_green": ("#3a5638", "#e0b830", "#1e2020", "#e0b830", "#1e2020"),
        "via_blue": ("#1e3a78", "#f0c020", "#4a4e54", "#f0c020", "#f0c020"),
        "bcr": ("#b82a26", "#f0f0ea", "#3a3c40", "#1e3a7a", "#1e3a7a"),
        "via_grey": ("#4c5158", "#f0c020", "#2a2c30", "#f0c020", "#f0c020"),
    }
    body, stripe, roof, trim, *lower = schemes[scheme]
    return {
        "louvre": material(f"{scheme} louvre", _shade(body, 0.6), metal=0.3, rough=0.6),
        "lower": material(f"{scheme} lower", lower[0] if lower else body, rough=0.4, clearcoat=0.4),
        "lamp": material("lamp glass", "#f0e6b8", rough=0.2),
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


def _shade(hexcol, f):
    """A colour darkened by f, 0 to 1."""
    return "#" + "".join(f"{int(int(hexcol[i:i + 2], 16) * f):02x}" for i in (1, 3, 5))


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


def lamp(m, outline, z, c, x=0.0, r=0.011):
    """A headlight on the nose at height z, x out from the middle, set into the outline the shell returned."""
    y = parts.outline_front(outline, z)
    with m.keep(at=z):
        m.tube(y - 0.008, y + 0.002, x, z, r * 1.25, c["dark"], segments=14)
        m.tube(y + 0.002, y + 0.004, x, z, r, c["lamp"], segments=14)


def carbody(m, shape, half, mats, crown=0.0):
    """m.shell, with its flat sides shaded flat so their long triangles don't streak. Returns the outline."""
    outline = m.shell(shape, half, mats, crown=crown)
    for p in m.objects[-1].data.polygons:
        if abs(p.normal.x) > 0.9:
            p.use_smooth = False
    return outline


def roof_fans(m, y0, y1, z, c, n=3, w=0.05):
    """Radiator fans set low in the roof: a flat grille ring with the fan dark inside."""
    for k in range(n):
        y = y0 + (y1 - y0) * (k + 0.5) / n
        for r, z0, z1, mat in ((w * 0.5, z - 0.03, z + 0.004, c["louvre"]), (w * 0.4, z + 0.004, z + 0.006, c["grille"])):
            fan = m.upright(0, y, z0, z1, r, r, mat, segments=20)
            fan.data.polygons.foreach_set("use_smooth", [False] * len(fan.data.polygons))


def louvres(m, x, y0, y1, z0, z1, c):
    """A louvred panel on the side at x: a dark panel crossed by slats, so it reads as a grille, not a window."""
    m.box(x - 0.0006, x + 0.0006, y0, y1, z0, z1, c["louvre"])
    n = max(2, int((z1 - z0) / 0.05))
    for k in range(n):
        z = z0 + (z1 - z0) * (k + 0.5) / n
        m.box(x - 0.0009, x + 0.0009, y0 + 0.003, y1 - 0.003, z - 0.008, z + 0.008, c["grille"])


def side_band(m, y0, y1, z0, z1, mat, half=0.12):
    """A band of paint along both sides, just proud of a body half wide."""
    for side in (-1, 1):
        x = side * (half + 0.0006)
        m.box(x - 0.0006, x + 0.0006, y0, y1, z0, z1, mat)


def cab_unit(m, L, scheme, axles=2, nose="bulldog"):
    """A streamlined cab unit, the full-width body with the cab up behind the nose. nose is "bulldog", the rounded
    F-unit nose with the headlight on top, or "alco", the FA's long nose sloping down nearly straight, with squarer edges."""
    c = livery(scheme)
    frame(m, L, c, axles)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.02
    deck, top, half = 0.62, 1.9, 0.12
    mats = {"paint": c["body"], "glass": c["glass"], "lower": c["lower"], "roof": c["roof"], "stripe": c["stripe"]}
    if nose == "alco":
        shape = [(a0, deck, 0.004, "paint"), (a1 - 0.185, deck, 0, "paint"), (a1 - 0.004, deck, 0.006, "lower", 0.92), (a1, 0.9, 0.012, "stripe", 0.9),
                 (a1 - 0.01, 0.98, 0.016, "paint", 0.9), (a1 - 0.16, 1.5, 0.01, "glass", 0.94), (a1 - 0.168, 1.8, 0.006, "paint", 0.98),
                 (a1 - 0.185, top, 0.01, "roof"), (a0, top, 0.006, "paint")]
        crown, cab = 0.05, a1 - 0.185
    else:
        shape = [(a0, deck, 0.004, "paint"), (a1 - 0.125, deck, 0, "paint"), (a1 - 0.01, deck, 0.012, "lower", 0.9), (a1 + 0.004, 0.98, 0.045, "paint", 0.86),
                 (a1 - 0.04, 1.42, 0.06, "glass", 0.82), (a1 - 0.095, 1.78, 0.02, "paint", 0.95), (a1 - 0.125, top, 0.05, "roof"),
                 (a0, top, 0.012, "paint")]
        crown, cab = 0.13, a1 - 0.125
    outline = carbody(m, shape, half, mats, crown=crown)
    body_end = cab - 0.03
    # The cab side windows, then the stripes and grilles back along the body.
    for side in (-1, 1):
        x = side * (half + 0.0006)
        m.box(x - 0.0008, x + 0.0008, cab - 0.075, cab - 0.01, 1.52, 1.74, c["glass"])
    if nose == "alco":
        # CN: green over a black sill, yellow lining between, carried across the nose.
        side_band(m, a0 + 0.004, cab + 0.03, deck, 0.92, c["lower"])
        side_band(m, a0 + 0.004, cab + 0.03, 0.92, 0.95, c["stripe"])
        side_band(m, a0 + 0.004, cab - 0.09, 1.79, 1.81, c["stripe"])
        for side in (-1, 1):
            x = side * (half + 0.0012)
            louvres(m, x, a0 + 0.05, body_end - 0.06, 1.58, 1.76, c)
            for k in range(2):
                m.tube_x(side * half, side * (half + 0.003), a0 + 0.2 + k * 0.16, 1.4, 0.015, c["glass"])
            louvres(m, x, a0 + 0.02, a0 + 0.08, 1.05, 1.5, c)
        roof_fans(m, a0 + 0.06, a0 + 0.36, top, c, n=3, w=0.06)
        # The Alco's one big exhaust stack in the middle of the roof.
        m.box(-0.02, 0.02, a0 + 0.44, a0 + 0.5, top - 0.02, top + 0.05, c["dark"], bevel=0.004)
        lamp(m, outline, 1.4, c, r=0.013)
        letters(m, L, c, 1.22, at=(a0 + cab) / 2, size=0.036)
    else:
        # CP: maroon with a grey band through the windows, lined in yellow above and below.
        side_band(m, a0 + 0.004, cab - 0.005, 1.36, 1.76, c["stripe"])
        side_band(m, a0 + 0.004, cab - 0.005, 1.34, 1.36, c["trim"])
        side_band(m, a0 + 0.004, body_end, 0.92, 0.94, c["trim"])
        for side in (-1, 1):
            x = side * (half + 0.0012)
            louvres(m, x, a0 + 0.04, body_end - 0.12, 1.62, 1.76, c)
            for k in range(3):
                m.tube_x(side * half, side * (half + 0.003), a0 + 0.16 + k * (body_end - a0 - 0.3) / 2, 1.5, 0.016, c["glass"])
        roof_fans(m, a0 + 0.05, a0 + 0.4, top, c, n=4, w=0.052)
        # Yellow wings swept back from the headlight round the nose.
        for side in (-1, 1):
            m.rod((side * 0.02, a1 - 0.012, 1.18), (side * 0.104, a1 - 0.07, 0.99), 0.0045, c["trim"], segments=6)
            m.rod((side * 0.104, a1 - 0.07, 0.99), (side * (half + 0.001), a1 - 0.17, 0.94), 0.0045, c["trim"], segments=6)
        lamp(m, outline, 1.34, c, r=0.013)
        lamp(m, outline, 1.18, c, r=0.009)
        letters(m, L, c, 1.18, at=(a0 + cab) / 2 - 0.04, size=0.036)


def hood(m, L, scheme, axles=2, wide=False, dynamic=False, body="hood", nose="short"):
    """A road switcher: a long hood, the cab, a short hood in front; wide puts a full-width safety cab and nose on it.
    body="cowl" covers it all in a full-width body with no walkways, see cowl()."""
    if body == "cowl":
        return cowl(m, L, scheme, axles, nose, dynamic)
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


def cowl(m, L, scheme, axles=2, nose="short", dynamic=False):
    """A cowl unit: one full-width body over the frame, the cab at the front. nose is "short", the F40PH's standard cab
    with a narrow sloped nose in front, or "safety", a full-width cab and nose sloping up to the windshield, as the GF6C's.
    The roof rises a little over the cab. Returns the livery and where the roof is, for the builder to add to."""
    c = livery(scheme)
    deck, top, half = 0.62, 1.86, 0.12
    frame(m, L, c, axles, deck)
    a0, a1 = -L / 2 + 0.02, L / 2 - 0.02
    mats = {"paint": c["body"], "glass": c["glass"], "lower": c["lower"], "roof": c["roof"]}
    if nose == "short":
        cab1 = a1 - 0.06
        cab0 = cab1 - 0.12
        cab_top = top + 0.08
        shape = [(a0, deck, 0.004, "paint"), (cab1, deck, 0.0, "lower"), (cab1, 1.36, 0.004, "glass"), (cab1 - 0.014, 1.8, 0.008, "paint"),
                 (cab1 - 0.024, cab_top, 0.02, "roof"), (cab0, cab_top, 0.01, "paint"), (cab0 - 0.02, top, 0.02, "roof"), (a0, top, 0.006, "paint")]
        outline = carbody(m, shape, half, mats, crown=0.06)
        # The nose, narrower than the cab, its top sloping down to the front.
        nose_line = carbody(m, [(cab1 - 0.01, deck, 0, "lower"), (a1, deck, 0.004, "lower"), (a1, 1.12, 0.012, "lower"), (a1 - 0.016, 1.3, 0.01, "paint"),
                             (cab1 - 0.01, 1.38, 0, "paint")], half * 0.8, mats, crown=0.02)
        lamp(m, nose_line, 0.98, c, x=0.045, r=0.009)
        lamp(m, nose_line, 0.98, c, x=-0.045, r=0.009)
        lamp(m, outline, 1.84, c, r=0.01)
    else:
        cab1 = a1 - 0.075
        cab0 = cab1 - 0.1
        cab_top = top + 0.06
        shape = [(a0, deck, 0.004, "paint"), (a1, deck, 0.006, "lower"), (a1, 1.16, 0.014, "paint"), (cab1, 1.42, 0.006, "glass"),
                 (cab1 - 0.012, 1.8, 0.008, "paint"), (cab1 - 0.02, cab_top, 0.016, "roof"), (cab0, cab_top, 0.01, "paint"),
                 (cab0 - 0.016, top, 0.016, "roof"), (a0, top, 0.006, "paint")]
        outline = carbody(m, shape, half, mats, crown=0.05)
        for x in (-0.07, 0.07):
            lamp(m, outline, 1.0, c, x=x, r=0.009)
        lamp(m, outline, 1.84, c, r=0.01)
    # The cab side windows; the body behind has doors and grilles, not windows.
    side_band(m, a0 + 0.004, cab1, deck, 0.88, c["lower"])
    side_band(m, a0 + 0.004, cab1, 0.88, 1.1, c["stripe"])
    for side in (-1, 1):
        x = side * (half + 0.0006)
        m.box(x - 0.0008, x + 0.0008, cab0 + 0.015, cab1 - 0.02, 1.48, 1.76, c["glass"])
        x = side * (half + 0.0014)
        # Radiator intakes high at the back, and the engine room doors as thin seams.
        louvres(m, x, a0 + 0.03, a0 + 0.2, 1.5, 1.76, c)
        for k in range(5):
            y = a0 + 0.24 + k * (cab0 - a0 - 0.3) / 5
            m.box(x - 0.0005, x + 0.0005, y, y + 0.004, 1.14, 1.76, c["grille"])
        louvres(m, x, cab0 - 0.1, cab0 - 0.03, 1.5, 1.68, c)
    m.box(-0.08, 0.08, a0 - 0.002, a0 + 0.002, 1.3, 1.7, c["louvre"])
    roof_fans(m, a0 + 0.03, a0 + 0.24, top, c, n=3 if axles == 3 else 2, w=0.06)
    if dynamic:
        # The dynamic brake blister on the roof, its fan set in it.
        y0, y1 = cab0 - 0.2, cab0 - 0.04
        m.shell([(y0, top - 0.02, 0, "paint"), (y1, top - 0.02, 0, "paint"), (y1 - 0.012, top + 0.1, 0.012, "paint"),
                 (y0 + 0.012, top + 0.1, 0.012, "paint")], 0.085, mats, crown=0.03)
        roof_fans(m, y0 + 0.02, y1 - 0.02, top + 0.09, c, n=1, w=0.05)
    letters(m, L, c, 1.3, at=(a0 + cab0) / 2, size=0.036)
    return c, a0, cab0, cab1, top


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


def gf6c(m, L, scheme="bcr"):
    """A big six-axle electric for coal over the mountains, BC Rail's: a full-width body with a safety cab and one
    pantograph over the transformer at the back."""
    c, a0, cab0, cab1, top = hood(m, L, scheme, axles=3, body="cowl", nose="safety")
    # Roof gear: the transformer housing under the pantograph, insulators and the bus bar forward to the cab.
    m.box(-0.07, 0.07, a0 + 0.26, a0 + 0.44, top - 0.02, top + 0.06, c["roof"], bevel=0.006)
    pantograph(m, a0 + 0.35, top + 0.06, c)
    m.rod((0, a0 + 0.44, top + 0.1), (0, cab0 - 0.02, top + 0.1), 0.004, c["steel"])
    for y in (a0 + 0.5, cab0 - 0.1):
        m.upright(0, y, top - 0.01, top + 0.1, 0.008, 0.006, material("insulator", "#e8e6e0", rough=0.3), segments=10)


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


def charger(m, L, scheme="via_grey"):
    """A Siemens Charger: smooth full-width sides with skirts, a strongly raked windshield over a pointed lower nose."""
    c = livery(scheme)
    frame(m, L, c, 2)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.01
    deck, top, half = 0.62, 1.92, 0.12
    mats = {"paint": c["body"], "glass": c["glass"], "lower": c["lower"], "roof": c["roof"]}
    shape = [(a0, deck - 0.1, 0.004, "paint"), (a1 - 0.19, deck - 0.1, 0, "paint"), (a1 - 0.03, deck - 0.1, 0.01, "lower", 0.86), (a1, 0.8, 0.02, "lower", 0.8),
             (a1 - 0.012, 1.04, 0.02, "paint", 0.86), (a1 - 0.04, 1.2, 0.03, "glass", 0.9), (a1 - 0.145, 1.84, 0.04, "paint", 0.98),
             (a1 - 0.19, top, 0.04, "roof"), (a0, top, 0.01, "paint")]
    outline = carbody(m, shape, half, mats, crown=0.18)
    nose = a1 - 0.19
    # Skirts down over the frame between the trucks, a yellow line along the foot of the body.
    for side in (-1, 1):
        x = side * (half - 0.002)
        m.box(x - 0.002, x + 0.002, a0 + 0.2, a1 - 0.24, 0.42, deck, c["body"], bevel=0.003)
    side_band(m, a0 + 0.004, nose - 0.02, 0.62, 0.7, c["lower"])
    for side in (-1, 1):
        x = side * (half + 0.0008)
        m.box(x - 0.0008, x + 0.0008, nose - 0.04, nose + 0.01, 1.45, 1.66, c["glass"])
        louvres(m, x, a0 + 0.05, a0 + 0.3, 1.42, 1.66, c)
    # The pilot: a plow under the pointed nose.
    m.wedge([(-0.1, a1 - 0.07, 0.42), (0.1, a1 - 0.07, 0.42), (0.06, a1 - 0.005, 0.42), (-0.06, a1 - 0.005, 0.42)],
            [(-0.1, a1 - 0.07, 0.55), (0.1, a1 - 0.07, 0.55), (0.07, a1 - 0.02, 0.55), (-0.07, a1 - 0.02, 0.55)], c["dark"])
    for x in (-0.06, 0.06):
        lamp(m, outline, 0.92, c, x=x, r=0.008)
    roof_fans(m, a0 + 0.05, a0 + 0.3, top, c, n=2, w=0.06)
    letters(m, L, c, 1.3, at=(a0 + nose) / 2, size=0.036)
