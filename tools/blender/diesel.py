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
        # VIA's LRC: light grey, a blue band lined in yellow.
        "via_lrc": ("#c2c6ca", "#1e3a78", "#6a6e74", "#f0c020", "#c2c6ca"),
        # CN's: a black hood behind a red cab and nose, with a white line along the sill, as (..., lower, cab).
        "cn_red": ("#232426", "#ececea", "#2a2a2c", "#ececea", "#232426", "#c4321e"),
    }
    body, stripe, roof, trim, *rest = schemes[scheme]
    lower = rest[:1]
    return {
        "louvre": material(f"{scheme} louvre", _shade(body, 0.6), metal=0.3, rough=0.6),
        "lower": material(f"{scheme} lower", lower[0] if lower else body, rough=0.4, clearcoat=0.4),
        "lamp": material("lamp glass", "#f0e6b8", rough=0.2),
        "body": material(f"{scheme} body", body, rough=0.4, clearcoat=0.4),
        "cab": material(f"{scheme} cab", rest[1] if len(rest) > 1 else body, rough=0.4, clearcoat=0.4),
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


def truck(m, y, c, axles=2, ge=False):
    """A diesel truck of two or three axles at y along the engine: wheels, a cast side frame, springs. ge draws GE's
    Hi-Ad truck: a deep side frame that dips between the axles, with a nest of coil springs over each journal."""
    r = 0.036
    zc = r / rig.RISE
    gap = 0.075
    ys = [y + (i - (axles - 1) / 2) * gap for i in range(axles)]
    journal = material("journal", "#5a5c60", metal=0.4, rough=0.5)
    for side in (-1, 1):
        for yy in ys:
            m.wheel(yy, side, r, (c["steel"], material("wheel dark", "#222224", rough=0.6), c["steel"]), spokes=0)
        span = (axles - 1) * gap / 2 + 0.05
        if not ge:
            m.box(side * 0.112, side * 0.126, y - span, y + span, zc * 0.55, zc * 2.0, c["dark"], bevel=0.003)
            for yy in ys:
                m.box(side * 0.126, side * 0.13, yy - 0.012, yy + 0.012, zc * 0.7, zc * 1.4, journal)
            continue
        # The top member the full length, then a web under it between each pair of axles, down to below the hubs.
        m.box(side * 0.112, side * 0.126, y - span, y + span, zc * 1.5, zc * 2.05, c["dark"], bevel=0.003)
        for ya, yb in zip(ys, ys[1:]):
            mid = (ya + yb) / 2
            m.wedge([(side * 0.112, mid - 0.02, zc * 0.55), (side * 0.126, mid - 0.02, zc * 0.55), (side * 0.126, mid + 0.02, zc * 0.55), (side * 0.112, mid + 0.02, zc * 0.55)],
                    [(side * 0.112, ya + 0.01, zc * 1.5), (side * 0.126, ya + 0.01, zc * 1.5), (side * 0.126, yb - 0.01, zc * 1.5), (side * 0.112, yb - 0.01, zc * 1.5)], c["dark"])
        for yy in ys:
            m.box(side * 0.124, side * 0.132, yy - 0.013, yy + 0.013, zc * 0.75, zc * 1.35, journal)
            for dy in (-0.007, 0.007):
                m.upright(side * 0.129, yy + dy, zc * 1.35, zc * 1.75, 0.005, 0.005, material("truck spring", "#7a7c7e", metal=0.6, rough=0.4), segments=8)


def frame(m, L, c, axles, deck=0.62, ge=False):
    """The underframe, fuel tank and trucks, with the walkway at [deck] steps. ge puts on GE's Hi-Ad trucks."""
    a0, a1 = -L / 2 + 0.01, L / 2 - 0.01
    m.box(-0.12, 0.12, a0, a1, deck - 0.1, deck, c["dark"], bevel=0.002)
    m.box(-0.1, 0.1, -L * 0.18, L * 0.18, 0.35, deck - 0.1, c["dark"], bevel=0.01)
    inset = 0.11 if axles == 2 else 0.14
    for y in (a0 + inset, a1 - inset):
        truck(m, y, c, axles, ge=ge)


def letters(m, L, c, z, at=0.0, size=0.04, x=0.1216):
    for side in (-1, 1):
        m.text(LETTERS, side * x, at, z, size, c["trim"], side)


def fans(m, y0, y1, z, c, n=3, w=0.06):
    for k in range(n):
        y = y0 + (y1 - y0) * (k + 0.5) / n
        m.upright(0, y, z, z + 0.04, w * 0.5, w * 0.5, c["grille"], segments=16)


def handrails(m, y0, y1, deck, c):
    for side in (-1, 1):
        x = side * 0.118
        m.rod((x, y0, deck + 0.45), (x, y1, deck + 0.45), 0.003, c["rail"])
        posts = max(2, round((y1 - y0) / 0.09) + 1)
        for k in range(posts):
            y = y0 + (y1 - y0) * k / (posts - 1)
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


# How hood units are painted along their sides, by scheme: a lower band from the deck up and the line over it, as
# livery keys, or None. CP's maroon over a grey band lined in yellow, CN's green over black lined in yellow; the red
# schemes have a white line along the sill instead.
HOOD_PAINT = {
    "cp_maroon": ("stripe", "trim", None),
    "cn_green": ("lower", "stripe", None),
    "red": (None, None, "stripe"),
    "cn_red": (None, None, "stripe"),
}


def hood(m, L, scheme, axles=2, wide=False, dynamic=False, body="hood", nose="short", maker="emd", short=0.12, hood_top=1.62,
         flare=False, evo=False):
    """A road switcher: a long hood behind the cab, a short hood in front, walkways and handrails along both sides.

    maker: "alco" (a tall hood with squarer ends, one big stack, radiator shutters at the back), "emd" (a hood well below
    the cab roof, rounded ends, fans flush in the roof at the back, louvred doors) or "ge" (the blower housing behind
    the cab). short is the short hood's length in tiles, hood_top the long hood's top in steps. wide puts on a full-width
    safety cab and nose: GE's with its nose corners angled back, EMD's squared off with the headlights high. flare widens
    the radiator section at the back: GE's wings near the top, or EMD's sides sloping out. dynamic adds the dynamic
    brake: a blister mid-hood, or on a safety cab unit the raised housing just behind the cab. evo, with maker "ge",
    draws GE's Evolution body of 2005 on: a longer, taller radiator section with bigger wings, the hood stepping up to
    it, a longer dynamic brake housing behind the cab and Hi-Ad trucks.
    body="cowl" covers it all in a full-width body with no walkways, see cowl()."""
    if body == "cowl":
        return cowl(m, L, scheme, axles, nose, dynamic)
    c = livery(scheme)
    deck = 0.62
    frame(m, L, c, axles, deck, ge=evo)
    a0, a1 = -L / 2 + 0.03, L / 2 - 0.03
    cab1 = a1 - short
    cab0 = cab1 - (0.1 if wide else 0.095)
    hh = 0.078 if maker == "alco" else 0.074
    ht = hood_top
    cab_top = 1.97
    mats = {"paint": c["body"], "glass": c["glass"], "roof": c["roof"], "louvre": c["louvre"]}
    cab_mats = dict(mats, paint=c["cab"])
    band, line, sill = HOOD_PAINT.get(scheme, (None, None, None))

    # The long hood, its back end rounded over the top and at the corners.
    end_r, end_w = (0.012, 0.92) if maker == "alco" else (0.03, 0.84)
    carbody(m, [(cab0 + 0.005, deck, 0, "paint"), (a0 + 0.03, deck, 0, "paint"), (a0 + 0.01, deck, 0, "paint", (1 + end_w) / 2),
                (a0, deck, 0.004, "paint", end_w), (a0, ht, end_r, "paint", end_w), (a0 + 0.01, ht, 0, "paint", (1 + end_w) / 2),
                (a0 + 0.03, ht, 0, "paint"), (cab0 + 0.005, ht, 0, "paint")], hh, mats, crown=0.05)
    rad = 0.24 if axles == 3 else 0.16
    if evo:
        # The Evolution's radiators: about a third of the hood, standing over it, the wings nearly the walkway's
        # width with the cores in their sloped undersides, and a slope from the hood up to them at the front.
        rad = 0.3
        lo, out, knee, top = ht - 0.4, 0.118, ht - 0.04, ht + 0.07
        ring = [(-out, top), (-out, knee), (-hh, lo), (hh, lo), (out, knee), (out, top)]
        front = [(-hh * 0.98, ht - 0.01), (-hh * 0.98, ht - 0.04), (-hh * 0.98, lo), (hh * 0.98, lo), (hh * 0.98, ht - 0.04), (hh * 0.98, ht - 0.01)]
        m.loft([(a0 - 0.004, ring), (a0 + rad - 0.06, ring), (a0 + rad, front)], dict(mats, louvre=c["grille"]),
               part=lambda k: "louvre" if k in (1, 3) else "paint")
        # A lip along the top edge of each wing.
        for side in (-1, 1):
            m.box(side * out - 0.002, side * out + 0.002, a0 - 0.004, a0 + rad - 0.06, top - 0.012, top + 0.004, c["roof"])
        fan_top = top
    elif flare:
        # The radiators at the back, wider than the hood: GE's wings stand out near the top, their undersides the
        # radiator cores; EMD's sides slope out up to a wider roof.
        lo, out, knee, top = (ht - 0.34, 0.113, ht - 0.15, ht + 0.02) if maker == "ge" else (ht - 0.5, 0.1, ht - 0.04, ht + 0.03)
        ring = [(-out, top), (-out, knee), (-hh, lo), (hh, lo), (out, knee), (out, top)]
        tuck = [(-hh * 0.98, ht - 0.01), (-hh * 0.98, knee), (-hh * 0.98, lo), (hh * 0.98, lo), (hh * 0.98, knee), (hh * 0.98, ht - 0.01)]
        m.loft([(a0, ring), (a0 + rad - 0.04, ring), (a0 + rad, tuck)], mats, part=lambda k: "louvre" if k in (1, 3) else "paint")
        fan_top = top
    else:
        fan_top = ht
    # Doors as seams along the hood, louvres high on some, the radiator intakes at the back.
    door = 0.055
    n_doors = int((cab0 - a0 - rad - 0.02) / door)
    for side in (-1, 1):
        x = side * (hh + 0.0012)
        for k in range(n_doors + 1):
            y = a0 + rad + 0.01 + k * door
            m.box(x - 0.0005, x + 0.0005, y, y + 0.003, deck + 0.06, ht - 0.05, c["grille"])
        if maker == "alco":
            louvres(m, x, a0 + 0.025, a0 + 0.12, 1.0, ht - 0.08, c)
            for k in range(0, n_doors, 2):
                y = a0 + rad + 0.018 + k * door
                louvres(m, x, y, y + door - 0.016, ht - 0.2, ht - 0.07, c)
        elif maker == "emd":
            if not flare:
                louvres(m, x, a0 + 0.035, a0 + rad - 0.01, ht - 0.26, ht - 0.05, c)
            for k in range(1, n_doors, 2):
                y = a0 + rad + 0.018 + k * door
                louvres(m, x, y, y + door - 0.016, deck + 0.12, deck + 0.3, c)
        else:
            for k in range(1, n_doors, 3):
                y = a0 + rad + 0.018 + k * door
                louvres(m, x, y, y + door - 0.016, ht - 0.22, ht - 0.07, c)
    # Paint along the sides: the lower band and its line, or the sill line.
    spans = [(a0 + 0.03, cab0, hh)] + ([] if wide else [(cab0, cab1, 0.11), (cab1, a1 - 0.025, 0.07)])
    for y0, y1, half in spans if band else ():
        side_band(m, y0, y1, deck, 1.0, c[band], half=half)
        side_band(m, y0, y1, 1.0, 1.03, c[line], half=half)
    if sill:
        side_band(m, a0 - 0.02, a1 + 0.02, deck - 0.07, deck - 0.03, c[sill])

    # Fans flush in the roof at the back, and the stacks.
    if maker == "alco":
        roof_fans(m, a0 + 0.03, a0 + 0.11, fan_top, c, n=1, w=0.075)
        sy = (a0 + cab0) / 2 + 0.04
        m.box(-0.022, 0.022, sy - 0.03, sy + 0.03, ht - 0.02, ht + 0.07, c["dark"], bevel=0.005)
    elif maker == "ge":
        roof_fans(m, a0 + 0.02, a0 + rad - 0.07 if evo else a0 + rad - 0.05, fan_top, c, n=2, w=0.095 if evo else 0.085)
    else:
        n = 3 if axles == 3 else 2
        roof_fans(m, a0 + 0.02, a0 + rad - 0.01, fan_top, c, n=n, w=0.075 if flare else 0.07)
        sy = (a0 + cab0) / 2 + 0.02
        for dy in ((0.0,) if axles == 2 else (-0.02, 0.02)):
            m.box(-0.01, 0.01, sy + dy - 0.012, sy + dy + 0.012, ht - 0.02, ht + 0.05, c["dark"], bevel=0.003)

    # The dynamic brake: a blister mid-hood with its fan, or the housing behind a safety cab, grilles on its sides.
    if dynamic and not wide:
        y0, y1 = cab0 - 0.26, cab0 - 0.1
        m.shell([(y0, ht - 0.03, 0, "paint"), (y1, ht - 0.03, 0, "paint"), (y1 - 0.012, ht + 0.09, 0.012, "paint"),
                 (y0 + 0.012, ht + 0.09, 0.012, "paint")], hh + 0.006, mats, crown=0.03)
        roof_fans(m, y0 + 0.03, y1 - 0.03, ht + 0.09, c, n=1, w=0.06)
        for side in (-1, 1):
            louvres(m, side * (hh + 0.0072), y0 + 0.02, y1 - 0.02, ht - 0.02, ht + 0.07, c)
    housing = wide and (dynamic or maker == "ge")
    if housing:
        y0, y1 = cab0 - (0.16 if evo else 0.12 if maker == "ge" else 0.15), cab0 + 0.005
        rise = 0.1 if evo else 0.07 if maker == "ge" else 0.04
        m.shell([(y0, ht - 0.03, 0, "paint"), (y1, ht - 0.03, 0, "paint"), (y1, ht + rise, 0.01, "paint"),
                 (y0 + 0.015, ht + rise, 0.015, "paint")], hh + 0.008, mats, crown=0.03)
        for side in (-1, 1):
            louvres(m, side * (hh + 0.0094), y0 + 0.02, y1 - 0.02, ht - 0.18, ht + rise - 0.02, c)
        if maker == "ge":
            # GE's stack just behind the blower housing.
            m.box(-0.02, 0.02, y0 - 0.05, y0 - 0.01, ht - 0.02, ht + 0.05, c["dark"], bevel=0.004)

    if wide:
        cab_line = _safety_cab(m, cab0, cab1, a1, deck, cab_top, maker, cab_mats, c)
    else:
        cab_line = _cab(m, cab0, cab1, deck, cab_top, cab_mats, c)
        _short_hood(m, cab1, a1, deck, maker, mats, c)
    # Handrails along the walkways, round the front of the short hood.
    handrails(m, a0, cab0, deck, c)
    if not wide:
        handrails(m, cab1 + 0.01, a1, deck, c)
        m.rod((-0.118, a1, deck + 0.45), (0.118, a1, deck + 0.45), 0.003, c["rail"])
    letters(m, L, c, (ht + deck) / 2 + 0.08, at=(a0 + rad + cab0) / 2, size=0.032, x=hh + 0.0012)
    return cab_line


def _cab(m, cab0, cab1, deck, top, mats, c, half=0.11):
    """The standard cab: windows all round and a roof curved across."""
    outline = carbody(m, [(cab0, deck, 0, "paint"), (cab1, deck, 0, "paint"), (cab1, 1.5, 0, "glass"), (cab1 - 0.004, 1.84, 0, "paint"),
                          (cab1 - 0.006, top, 0.02, "roof"), (cab0, top, 0.02, "paint"), (cab0, 1.84, 0, "glass"),
                          (cab0, 1.68, 0, "paint")], half, mats, crown=0.09)
    # A post between the two front windows, and the sliding side windows.
    m.box(-0.006, 0.006, cab1 - 0.006, cab1 + 0.002, 1.48, 1.86, mats["paint"])
    for side in (-1, 1):
        x = side * (half + 0.0006)
        m.box(x - 0.0008, x + 0.0008, cab0 + 0.012, cab1 - 0.012, 1.5, 1.82, c["glass"])
        m.box(x - 0.0012, x + 0.0012, (cab0 + cab1) / 2 - 0.003, (cab0 + cab1) / 2 + 0.003, 1.5, 1.82, mats["paint"])
    return outline


def _short_hood(m, cab1, a1, deck, maker, mats, c):
    """The short hood in front of the cab, narrower than the long one: rounded at the front, with its headlight and
    number boards. Alco's is longer and steps down at the front."""
    half = 0.07
    if maker == "alco":
        st = 1.58
        line = carbody(m, [(cab1 - 0.005, deck, 0, "paint"), (a1 - 0.015, deck, 0, "paint"), (a1, deck, 0.004, "paint", 0.9), (a1, 1.42, 0.01, "paint", 0.9),
                           (a1 - 0.015, 1.42, 0, "paint"), (a1 - 0.05, 1.42, 0.006, "paint"), (a1 - 0.058, st, 0.008, "paint"),
                           (cab1 - 0.005, st, 0, "paint")], half, mats, crown=0.04)
        lamp(m, line, 1.34, c, r=0.011)
    else:
        st = 1.44
        line = carbody(m, [(cab1 - 0.005, deck, 0, "paint"), (a1 - 0.02, deck, 0, "paint"), (a1 - 0.008, deck, 0, "paint", 0.94), (a1, deck, 0.004, "paint", 0.86),
                           (a1, st, 0.025, "paint", 0.86), (a1 - 0.008, st, 0, "paint", 0.94), (a1 - 0.02, st, 0, "paint"),
                           (cab1 - 0.005, st, 0, "paint")], half, mats, crown=0.04)
        lamp(m, line, st - 0.08, c, r=0.011)
        # Number boards high on the front corners.
        y = parts.outline_front(line, st - 0.08)
        for x in (-0.04, 0.04):
            m.box(x - 0.014, x + 0.014, y - 0.012, y - 0.004, st - 0.12, st - 0.05, material("number board", "#e8e4d4", rough=0.4))
    return line


def _safety_cab(m, cab0, cab1, a1, deck, top, maker, mats, c, half=0.12):
    """The full-width safety cab and nose of the 1990s on. GE's nose slopes up to the windshield, its front corners
    angled back, the headlights on the nose; EMD's is squared off, nearly flat on top, the headlights high on the cab."""
    if maker == "ge":
        shape = [(cab0, deck, 0, "paint"), (a1 - 0.025, deck, 0, "paint"), (a1, deck, 0.004, "paint", 0.78), (a1, 1.2, 0.01, "paint", 0.78),
                 (a1 - 0.025, 1.27, 0, "paint"), (cab1, 1.44, 0.008, "glass"), (cab1 - 0.018, 1.84, 0.008, "paint"),
                 (cab1 - 0.024, top, 0.02, "roof"), (cab0, top, 0.008, "paint")]
        crown = 0.07
    else:
        shape = [(cab0, deck, 0, "paint"), (a1 - 0.01, deck, 0, "paint"), (a1, deck, 0.004, "paint", 0.94), (a1, 1.32, 0.008, "paint", 0.94),
                 (a1 - 0.01, 1.35, 0, "paint"), (cab1, 1.42, 0.004, "glass"), (cab1 - 0.01, 1.84, 0.004, "paint"),
                 (cab1 - 0.014, top, 0.01, "roof"), (cab0, top, 0.006, "paint")]
        crown = 0.04
    outline = carbody(m, shape, half, mats, crown=crown)
    # The post between the two windshields, the side windows.
    yw = parts.outline_front(outline, 1.64)
    m.box(-0.006, 0.006, yw - 0.006, yw + 0.002, 1.44, 1.84, mats["paint"])
    for side in (-1, 1):
        x = side * (half + 0.0006)
        m.box(x - 0.0008, x + 0.0008, cab0 + 0.012, cab1 - 0.025, 1.5, 1.82, c["glass"])
    if maker == "ge":
        for x in (-0.025, 0.025):
            lamp(m, outline, 1.1, c, x=x, r=0.009)
    else:
        for x in (-0.03, 0.03):
            lamp(m, outline, 1.9, c, x=x, r=0.009)
    # Ditch lights low on the nose corners.
    for x in (-0.08, 0.08):
        lamp(m, outline, 0.76, c, x=x, r=0.007)
    return outline


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
    """A boxcab electric of the 1910s, as CN's for the Mount Royal Tunnel: a steel box with a cab at each end under an
    arched roof, an end door with a window between two more, small platforms with railings at both ends, and the
    pantograph and resistor grids on the roof."""
    c = livery(scheme)
    deck = 0.62
    frame(m, L, c, 2, deck)
    a0, a1 = -L / 2 + 0.012, L / 2 - 0.012
    b0, b1 = a0 + 0.05, a1 - 0.05
    top, crown, half = 1.9, 0.2, 0.118
    mats = {"paint": c["body"], "roof": c["roof"]}
    carbody(m, [(b0, deck, 0, "paint"), (b1, deck, 0, "paint"), (b1, top, 0.008, "roof"), (b0, top, 0.008, "paint")], half, mats, crown=crown)
    door = c["louvre"]
    for end in (-1, 1):
        y = b1 if end > 0 else b0
        face = y + end * 0.001
        # The end door with its window, a window either side, the headlight over the door.
        m.box(-0.026, 0.026, face - 0.0015, face + 0.0015, deck + 0.01, 1.62, door)
        m.box(-0.017, 0.017, face - 0.002, face + 0.002, 1.32, 1.54, c["glass"])
        for x in (-0.072, 0.072):
            m.box(x - 0.022, x + 0.022, face - 0.0015, face + 0.0015, 1.3, 1.56, c["glass"])
        lo, hi = sorted((face, face + end * 0.012))
        m.box(-0.014, 0.014, lo, hi, 1.6, 1.7, c["dark"], bevel=0.002)
        lo, hi = sorted((face + end * 0.012, face + end * 0.014))
        m.box(-0.01, 0.01, lo, hi, 1.62, 1.68, c["lamp"])
        # The platform's railing: across the end either side of the gangway, and back along the sides to the body.
        edge = (a1 if end > 0 else a0) - end * 0.004
        rail_z = deck + 0.4
        for side in (-1, 1):
            x0, x1 = side * 0.04, side * 0.114
            m.rod((x0, edge, rail_z), (x1, edge, rail_z), 0.003, c["rail"])
            m.rod((x1, edge, rail_z), (x1, y, rail_z), 0.003, c["rail"])
            for x in (x0, x1):
                m.rod((x, edge, deck), (x, edge, rail_z), 0.003, c["rail"])
            # A step down at each corner.
            lo, hi = sorted((edge, edge - end * 0.03))
            m.box(x1 - 0.01, x1 + 0.006 * side, lo, hi, 0.42, 0.45, c["dark"])
    for side in (-1, 1):
        x = side * (half + 0.0006)
        # A cab window by each end, and tall louvred grilles along the middle: fine body-coloured slats standing out
        # over thin dark gaps, no frame, so they read as louvres and not as more windows.
        for y in (b0 + 0.025, b1 - 0.065):
            m.box(x - 0.0008, x + 0.0008, y, y + 0.04, 1.3, 1.56, c["glass"])
        for k in range(4):
            y = b0 + 0.12 + k * (b1 - b0 - 0.24) / 3
            m.box(x - 0.0008, x + 0.0008, y - 0.026, y + 0.026, 0.98, 1.62, c["grille"])
            out = side * 0.0022
            for j in range(15):
                z = 0.985 + j * 0.0423
                m.box(min(x, x + out), max(x, x + out), y - 0.026, y + 0.026, z, z + 0.031, c["body"])
    side_band(m, b0, b1, 0.86, 0.89, c["trim"], half=half)
    # Roof gear: resistor grids either side of the pantograph, the bus bar along the roof's crown.
    for y in (-0.15, 0.15):
        m.box(-0.045, 0.045, y - 0.06, y + 0.06, top - 0.02, top + 0.06, c["grille"], bevel=0.004)
    m.rod((0, -0.22, top + 0.08), (0, 0.22, top + 0.08), 0.003, c["steel"])
    pantograph(m, 0.0, top, c)
    letters(m, L, c, 0.74, size=0.028, x=half + 0.0012)


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


def lrc(m, L, scheme="via_lrc"):
    """A light, fast power car, VIA's LRC: a low body with rounded roof edges and a long nose curving down from the
    roof, the windshield set low on it."""
    c = livery(scheme)
    frame(m, L, c, 2)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.012
    deck, top, half = 0.62, 1.72, 0.12
    mats = {"paint": c["body"], "glass": c["glass"], "roof": c["roof"], "lower": c["lower"]}
    shape = [(a0, deck, 0.004, "paint"), (a1 - 0.03, deck, 0, "lower"), (a1, 0.74, 0.03, "lower", 0.86), (a1 - 0.004, 0.94, 0.04, "paint", 0.82),
             (a1 - 0.06, 1.16, 0.05, "glass", 0.86), (a1 - 0.15, 1.46, 0.07, "paint", 0.95), (a1 - 0.27, top, 0.1, "roof"),
             (a0, top, 0.012, "paint")]
    outline = carbody(m, shape, half, mats, crown=0.16)
    nose = a1 - 0.27
    # VIA's yellow and blue along the sides, the cab's side window, grilles at the back.
    side_band(m, a0 + 0.004, nose + 0.08, 0.84, 0.9, c["trim"])
    side_band(m, a0 + 0.004, nose + 0.08, 0.9, 1.04, c["stripe"])
    for side in (-1, 1):
        x = side * (half + 0.0008)
        m.box(x - 0.0008, x + 0.0008, nose - 0.02, nose + 0.06, 1.36, 1.56, c["glass"])
        louvres(m, x, a0 + 0.04, a0 + 0.22, 1.28, 1.56, c)
        louvres(m, x, a0 + 0.3, nose - 0.1, 1.4, 1.56, c)
    roof_fans(m, a0 + 0.03, a0 + 0.22, top, c, n=2, w=0.05)
    for x in (-0.05, 0.05):
        lamp(m, outline, 0.82, c, x=x, r=0.008)
    letters(m, L, c, 1.22, at=(a0 + nose) / 2, size=0.034)


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
