"""Ships of the lakes, rivers and coasts, in our own colours: built facing +y, L tiles long, x across, z in steps from the waterline.

Hulls are lofted through cross-sections: a flat stern, parallel sides, a bow that narrows and flares, and a sheer
that lifts the deck towards the bow. Great Lakes bulk carriers keep their pilothouse right forward and their engine
aft, as the real lakers do.
"""
import math

import rig
from parts import material
from steam import LETTERS


def common():
    return {
        "glass": material("glass", "#1e2a36", rough=0.1),
        "dark": material("ship dark", "#26282a", rough=0.7),
        "white": material("ship white", "#ecebe6", rough=0.45),
        "deck": material("deck", "#7a6e60", rough=0.85),
        "steel": material("ship steel", "#6a6e72", metal=0.6, rough=0.45),
        "wood": material("ship wood", "#9a7a52", rough=0.8),
        "boot": material("boot top", "#8a2a22", rough=0.6),
        "rail": material("ship rail", "#d8d8d4", metal=0.3, rough=0.4),
    }


def hull(m, L, half, free, paint, c, bow=0.18, stern=0.08, sheer=0.25, transom=0.8, deck=None, sharp=False):
    """A hull L long and half wide, its deck free steps over the water and rising by sheer at the bow. bow and stern are
    how much of the length they take; transom is how wide the stern is. Returns where the deck is at y."""
    a0, a1 = -L / 2, L / 2
    n = 18

    def width(t, low=False):
        if t < stern:
            s = t / stern
            w = transom + (1 - transom) * math.sin(s * math.pi / 2)
        elif t > 1 - bow:
            s = (t - (1 - bow)) / bow
            w = max(0.0, math.cos(min(s, 1.0) * math.pi / 2)) ** (1.2 if sharp else 0.8)
        else:
            w = 1.0
        # Below, the bow is finer still and the stern tucks in: the hull flares out above the water.
        if low:
            w *= 0.82 if t > 1 - bow else 0.9
        return max(w, 0.02)

    def top(t):
        return free + sheer * max(0.0, (t - 0.7) / 0.3) ** 2 + 0.06 * max(0.0, (0.1 - t) / 0.1)

    sections = []
    for k in range(n + 1):
        t = k / n
        y = a0 + L * t
        wu = half * width(t)
        wl = half * width(t, True)
        z = top(t)
        ring = [(-wu, z), (-wu, z * 0.45), (-wl * 0.96, 0.08), (-wl * 0.7, 0.0), (wl * 0.7, 0.0), (wl * 0.96, 0.08), (wu, z * 0.45), (wu, z)]
        sections.append((y, ring))
    # The faces: the sides above the boot top paint, the boot top at the waterline dark red, the deck across the top.
    parts = {0: "paint", 1: "boot", 2: "boot", 3: "boot", 4: "boot", 5: "paint", 6: "paint", 7: "deck"}
    m.loft(sections, {"paint": paint, "boot": c["boot"], "deck": deck or c["deck"]}, part=lambda k: parts[k])
    return top


def house(m, y0, y1, half, z0, z1, mat, c, windows=True, round_front=0.02, rake=0.0):
    """A deckhouse from y0 to y1 (its front), half wide, from z0 to z1, its front rounded and raked back by rake tiles,
    with a band of windows across the front and down the sides."""
    m.shell([(y0, z0, 0.004, "paint"), (y1, z0, 0.004, "paint"), (y1 - rake, z1, round_front, "paint"), (y0, z1, 0.006, "paint")],
            half, {"paint": mat}, crown=0.03)
    if windows:
        zw = z1 - (z1 - z0) * 0.35
        # In a lowered ship the windows come down less than the house, so they still read, up to half its height.
        g = min(1 / math.sqrt(m.k(zw)), (z1 - z0) * 0.5 / 0.14)
        m.box(-half * 0.85, half * 0.85, y1 - rake * 0.65 - 0.002, y1 - rake * 0.65 + 0.001, zw - 0.08 * g, zw + 0.06 * g, c["glass"])
        for side in (-1, 1):
            m.box(side * half - 0.001, side * half + 0.001, y0 + 0.01, y1 - 0.015, zw - 0.07 * g, zw + 0.05 * g, c["glass"])


def funnel(m, y, z0, z1, r, mat, band, x=0.0, rake=0.0):
    """A funnel standing at y from z0 to z1, r round, raked back by rake tiles, with a coloured band near its top."""
    m.lathe(x, y, z0, [(r, 0), (r, z1 - z0)], mat, segments=16)
    m.lathe(x, y, z1 - 0.25, [(r * 1.04, 0), (r * 1.04, 0.15)], band, segments=16)
    m.lathe(x, y, z1, [(r * 0.85, 0), (r * 0.85, 0.02)], material("funnel top", "#1a1a1a", rough=0.8), segments=16)


def hatches(m, y0, y1, n, half, z, c, colour="#5a6a5a"):
    """A row of n hatch covers along the deck from y0 to y1."""
    mat = material("hatches", colour, rough=0.6)
    for k in range(n):
        ya = y0 + (y1 - y0) * k / n
        yb = ya + (y1 - y0) / n * 0.78
        m.box(-half, half, ya, yb, z, z + 0.08, mat, bevel=0.003)


def masts(m, ys, z0, z1, c):
    for y in ys:
        m.rod((0, y, z0), (0, y, z1), 0.004, c["dark"])


def laker(m, L, kind):
    """A Great Lakes bulk carrier: pilothouse forward, a long run of hatches, the engine house and funnel aft.
    kind is steam, seaway or self (a self-unloader, with its boom along the deck)."""
    c = common()
    paint = material(f"{kind} laker hull", {"steam": "#3a3d40", "seaway": "#8a2a22", "self": "#2a3a5a"}[kind], rough=0.5, clearcoat=0.2)
    half = 0.065 if kind == "steam" else 0.075
    free = 0.5
    top = hull(m, L, half, free, paint, c, bow=0.1, stern=0.06, sheer=0.15, transom=0.85, deck=material("red deck", "#7a3a30", rough=0.8))
    a0, a1 = -L / 2, L / 2
    z = top(0.5)
    # Forward: the pilothouse on its forecastle, right at the bow.
    fwd = a1 - L * 0.1
    house(m, fwd - 0.07, fwd, half * 0.85, top(0.9), top(0.9) + 0.45, c["white"], c, round_front=0.01)
    house(m, fwd - 0.055, fwd - 0.01, half * 0.6, top(0.9) + 0.45, top(0.9) + 0.75, c["white"], c, round_front=0.008)
    # Aft: the engine house and funnel.
    aft = a0 + L * 0.06
    house(m, aft, aft + 0.1, half * 0.9, z, z + 0.5, c["white"], c, windows=True)
    funnel(m, aft + 0.035, z + 0.5, z + 1.25 if kind == "steam" else z + 1.05, 0.018 if kind == "steam" else 0.022,
           c["dark"] if kind == "steam" else paint, material(f"{kind} funnel band", "#e0b020" if kind != "self" else "#d8d8d4", rough=0.4))
    hatches(m, aft + 0.13, fwd - 0.1, int(L * 22), half * 0.7, z, c, "#4a5a4a" if kind == "steam" else "#2a2a2a")
    if kind == "self":
        # The unloading boom, laid along the deck, and its A-frame.
        m.box(-0.012, 0.012, aft + 0.12, fwd - 0.12, z + 0.32, z + 0.4, c["steel"], bevel=0.003)
        m.rod((-0.02, aft + 0.11, z), (0, aft + 0.13, z + 0.7), 0.004, c["steel"])
        m.rod((0.02, aft + 0.11, z), (0, aft + 0.13, z + 0.7), 0.004, c["steel"])
    m.text(LETTERS, half + 0.002, 0, z - 0.15, 0.03, c["white"], 1)


def canaller(m, L):
    """A steam canaller, sized for the old canals: bridge forward, hatches, the engine and a tall funnel aft."""
    c = common()
    paint = material("canaller hull", "#2a2c2e", rough=0.55)
    half = 0.058
    top = hull(m, L, half, 0.45, paint, c, bow=0.14, stern=0.08, sheer=0.2, transom=0.75)
    a0, a1 = -L / 2, L / 2
    z = top(0.5)
    fwd = a1 - L * 0.14
    house(m, fwd - 0.06, fwd, half * 0.8, top(0.86), top(0.86) + 0.5, c["white"], c, round_front=0.008)
    aft = a0 + L * 0.08
    house(m, aft, aft + 0.08, half * 0.85, z, z + 0.45, c["white"], c)
    funnel(m, aft + 0.03, z + 0.45, z + 1.4, 0.016, c["dark"], material("canaller band", "#c8302a", rough=0.4))
    hatches(m, aft + 0.1, fwd - 0.08, int(L * 18), half * 0.7, z, c)
    masts(m, [fwd + 0.02, aft + 0.09], z, z + 1.6, c)


def schooner(m, L):
    """A Great Lakes schooner: a wooden hull, three masts and gaff sails."""
    c = common()
    paint = material("schooner hull", "#2a3a30", rough=0.6)
    half = 0.045
    top = hull(m, L, half, 0.35, paint, c, bow=0.25, stern=0.12, sheer=0.3, transom=0.6, deck=c["wood"], sharp=True)
    a0, a1 = -L / 2, L / 2
    z = top(0.5)
    sail = material("sailcloth", "#e8e0c8", rough=0.9)
    ys = [a1 - L * 0.28, a0 + L * 0.5, a0 + L * 0.25]
    for k, y in enumerate(ys):
        tall = 2.6 if k == 1 else 2.4
        m.rod((0, y, z), (0, y, z + tall), 0.004, c["wood"])
        # A gaff sail aft of each mast, its peak raised.
        aft = y - L * 0.2
        m.wedge([(-0.002, y - 0.005, z + 0.3), (0.002, y - 0.005, z + 0.3), (0.002, aft, z + 0.3), (-0.002, aft, z + 0.3)],
                [(-0.002, y - 0.005, z + tall - 0.3), (0.002, y - 0.005, z + tall - 0.3), (0.002, aft, z + tall - 0.6), (-0.002, aft, z + tall - 0.6)], sail)
    # A bowsprit and jib.
    m.rod((0, a1 - 0.02, z + 0.3), (0, a1 + 0.06, z + 0.5), 0.003, c["wood"])
    m.wedge([(-0.002, ys[0] + 0.01, z + 0.4), (0.002, ys[0] + 0.01, z + 0.4), (0.002, a1 + 0.05, z + 0.5), (-0.002, a1 + 0.05, z + 0.5)],
            [(-0.002, ys[0] + 0.01, z + 2.0), (0.002, ys[0] + 0.01, z + 2.0), (0.002, ys[0] + 0.02, z + 2.0), (-0.002, ys[0] + 0.02, z + 2.0)], sail)
    m.box(-0.02, 0.02, a0 + 0.02, a0 + 0.06, z, z + 0.25, c["wood"], bevel=0.004)


def paddle_steamer(m, L):
    """A side-wheel passenger steamer: paddle boxes amidships, two white decks of cabins, a tall funnel."""
    c = common()
    paint = material("steamer hull", "#2a2a2e", rough=0.6)
    half = 0.05
    top = hull(m, L, half, 0.3, paint, c, bow=0.2, stern=0.1, sheer=0.15, transom=0.7, deck=c["wood"], sharp=True)
    a0, a1 = -L / 2, L / 2
    z = top(0.5)
    house(m, a0 + 0.04, a1 - 0.08, half * 1.05, z, z + 0.42, c["white"], c, round_front=0.01)
    house(m, a0 + 0.07, a1 - 0.12, half * 0.9, z + 0.42, z + 0.8, c["white"], c, round_front=0.01)
    red = material("paddle box", "#a8322a", rough=0.5)
    for side in (-1, 1):
        m.tube_x(side * half, side * (half + 0.022), 0.0, z + 0.05, 0.05, red, segments=24)
    funnel(m, 0.02, z + 0.8, z + 2.0, 0.012, c["dark"], material("steamer band", "#e0b020", rough=0.4))
    m.rod((0, 0.06, z + 0.8), (0, 0.06, z + 1.7), 0.004, c["wood"])


def passenger_steamer(m, L):
    """A lake passenger steamer: three white decks the length of a black hull, two funnels."""
    c = common()
    paint = material("lake steamer hull", "#1e2024", rough=0.55)
    half = 0.062
    top = hull(m, L, half, 0.4, paint, c, bow=0.18, stern=0.1, sheer=0.15, transom=0.7, sharp=True)
    a0, a1 = -L / 2, L / 2
    z = top(0.5)
    for k, (s0, s1, w) in enumerate(((0.06, 0.12, 1.0), (0.09, 0.16, 0.92), (0.14, 0.24, 0.8))):
        house(m, a0 + s0, a1 - s1, half * w, z + k * 0.36, z + (k + 1) * 0.36, c["white"], c, round_front=0.015)
    band = material("steamer red band", "#b8322a", rough=0.4)
    for y in (-0.02, 0.07):
        funnel(m, y, z + 1.08, z + 2.0, 0.015, c["dark"], band, rake=0.0)
    m.text(LETTERS, half + 0.002, 0, z - 0.12, 0.028, c["white"], 1)


def tug_barge(m, L):
    """A northern tug pushing a barge: fuel tanks and containers on the barge ahead, the tug's tall wheelhouse behind."""
    c = common()
    a0, a1 = -L / 2, L / 2
    tug_len = L * 0.32
    tug = material("tug hull", "#c8302a", rough=0.5)
    # The barge, a box with a raked bow.
    barge = material("barge", "#5a5e62", metal=0.4, rough=0.6)
    b0 = a0 + tug_len
    m.shell([(b0, 0.0, 0.004, "paint"), (a1 - 0.04, 0.0, 0.004, "paint"), (a1, 0.3, 0.004, "paint"), (b0, 0.3, 0.004, "paint")], 0.06, {"paint": barge})
    tank = material("fuel tank", "#d8d8d4", rough=0.5)
    for k in range(2):
        y = b0 + 0.04 + k * 0.07
        m.upright(0, y, 0.3, 0.7, 0.028, 0.028, tank)
    box = [material("box red", "#9a3a2a", rough=0.6), material("box blue", "#2a4a7a", rough=0.6), material("box green", "#3a6a3a", rough=0.6)]
    for k in range(3):
        y = b0 + 0.2 + k * 0.05
        if y + 0.045 > a1 - 0.04:
            break
        for side in (-1, 1):
            m.box(side * 0.03 - 0.025, side * 0.03 + 0.025, y, y + 0.045, 0.3, 0.6, box[(k + side) % 3], bevel=0.002)
    top = hull_part(m, a0, a0 + tug_len, 0.055, 0.4, tug, c)
    house(m, a0 + 0.03, a0 + tug_len - 0.03, 0.04, 0.4, 0.8, c["white"], c, round_front=0.006)
    house(m, a0 + 0.045, a0 + tug_len - 0.035, 0.03, 0.8, 1.3, c["white"], c, round_front=0.006)
    funnel(m, a0 + 0.03, 0.8, 1.2, 0.01, c["dark"], material("tug band", "#e0b020", rough=0.4))


def hull_part(m, y0, y1, half, free, paint, c):
    """A short square-ended hull from y0 to y1, for a tug or a ferry's end."""
    return m.shell([(y0, 0.0, 0.01, "boot"), (y1, 0.0, 0.01, "paint"), (y1 + 0.006, free, 0.01, "paint"), (y0 - 0.004, free, 0.01, "paint")],
                   half, {"paint": paint, "boot": c["boot"]}, crown=0.0)


def freighter(m, L, kind):
    """A package freighter (house amidships, booms over the holds), a lake or product tanker (house aft, a catwalk over
    the tanks), or a container feeder (house aft, stacked boxes)."""
    c = common()
    colours = {"package": "#2a3a2a", "tanker": "#2a2a2c", "product": "#9a2a22", "container": "#2a4a7a"}
    paint = material(f"{kind} hull", colours[kind], rough=0.5, clearcoat=0.2)
    half = 0.062 if kind in ("package", "tanker") else 0.072
    top = hull(m, L, half, 0.5, paint, c, bow=0.15, stern=0.08, sheer=0.2, transom=0.8)
    a0, a1 = -L / 2, L / 2
    z = top(0.5)
    if kind == "package":
        mid = a0 + L * 0.45
        house(m, mid - 0.05, mid + 0.04, half * 0.9, z, z + 0.55, c["white"], c)
        house(m, mid - 0.03, mid + 0.03, half * 0.7, z + 0.55, z + 0.85, c["white"], c)
        funnel(m, mid - 0.04, z + 0.55, z + 1.3, 0.016, c["dark"], material("package band", "#e0b020", rough=0.4))
        hatches(m, a0 + 0.06, mid - 0.07, 2, half * 0.65, z, c)
        hatches(m, mid + 0.06, a1 - L * 0.16, 2, half * 0.65, z, c)
        masts(m, [a0 + L * 0.2, a1 - L * 0.25], z, z + 1.5, c)
        for y in (a0 + L * 0.2, a1 - L * 0.25):
            m.rod((0, y, z + 0.3), (0, y + 0.08, z + 1.0), 0.003, c["steel"])
        return
    aft = a0 + L * 0.06
    house(m, aft, aft + 0.1, half * 0.9, z, z + 0.6, c["white"], c)
    house(m, aft + 0.02, aft + 0.09, half * 0.75, z + 0.6, z + 1.0, c["white"], c)
    funnel(m, aft + 0.02, z + 0.6, z + 1.3, 0.02, paint if kind != "tanker" else c["dark"], material(f"{kind} band", "#e0b020", rough=0.4))
    if kind in ("tanker", "product"):
        m.box(-0.006, 0.006, aft + 0.1, a1 - L * 0.12, z + 0.12, z + 0.16, c["steel"])
        pipe = material("deck pipes", "#3a6a3a" if kind == "product" else "#8a8a2a", rough=0.5)
        for side in (-1, 1):
            m.tube(aft + 0.11, a1 - L * 0.14, side * 0.02, z + 0.06, 0.004, pipe, segments=8)
        for k in range(int(L * 8)):
            y = aft + 0.14 + k * 0.12
            if y > a1 - L * 0.16:
                break
            m.box(-0.03, 0.03, y, y + 0.02, z, z + 0.1, pipe)
    else:
        box = [material("can red", "#9a3a2a", rough=0.6), material("can blue", "#2a4a7a", rough=0.6), material("can grey", "#8a8e92", rough=0.6),
               material("can green", "#3a6a3a", rough=0.6), material("can orange", "#c87a2a", rough=0.6)]
        y = aft + 0.12
        k = 0
        while y + 0.05 < a1 - L * 0.12:
            for row, x in enumerate((-0.04, 0.0, 0.04)):
                for tier in range(2 if row != 1 else 3):
                    m.box(x - 0.019, x + 0.019, y, y + 0.05, z + tier * 0.25, z + (tier + 1) * 0.25 - 0.01, box[(k + row * 2 + tier) % 5], bevel=0.001)
            y += 0.055
            k += 1


def car_ferry(m, L, era):
    """A car ferry: older, a hull with a boxy house over the car deck and one funnel; a superferry, several decks of
    white with a coloured band and twin funnels; a battery ferry, double-ended with a smooth house and no funnel."""
    c = common()
    paint = material(f"{era} ferry hull", {"old": "#2a3a5a", "super": "#ecebe6", "battery": "#ecebe6"}[era], rough=0.45, clearcoat=0.2)
    band = material(f"{era} ferry band", {"old": "#c8302a", "super": "#2a5a8a", "battery": "#2a8a6a"}[era], rough=0.4)
    half = 0.07 if era == "old" else 0.085
    double = era == "battery"
    top = hull(m, L, half, 0.45, paint, c, bow=0.14 if not double else 0.12, stern=0.12 if double else 0.06, sheer=0.08,
               transom=0.5 if double else 0.85)
    a0, a1 = -L / 2, L / 2
    z = top(0.5)
    for side in (-1, 1):
        m.box(side * half * 0.98 - 0.0015, side * half * 0.98 + 0.0015, a0 + 0.03, a1 - 0.05, z * 0.55, z * 0.75, band)
    if era == "old":
        house(m, a0 + 0.03, a1 - 0.07, half * 0.95, z, z + 0.4, c["white"], c, round_front=0.01)
        house(m, a0 + 0.06, a1 - 0.12, half * 0.75, z + 0.4, z + 0.75, c["white"], c, round_front=0.01)
        funnel(m, -0.01, z + 0.75, z + 1.3, 0.018, band, c["dark"])
    elif era == "super":
        for k, (s0, s1, w) in enumerate(((0.03, 0.06, 0.98), (0.05, 0.09, 0.95), (0.08, 0.13, 0.85), (0.12, 0.2, 0.7))):
            house(m, a0 + s0, a1 - s1, half * w, z + k * 0.3, z + (k + 1) * 0.3, c["white"], c, round_front=0.02, rake=0.02)
        for side in (-1, 1):
            m.box(side * half * 0.86 - 0.0015, side * half * 0.86 + 0.0015, a0 + 0.06, a1 - 0.1, z + 0.62, z + 0.7, band)
            funnel(m, a0 + 0.07, z + 1.2, z + 1.6, 0.014, band, c["dark"], x=side * 0.03)
    else:
        house(m, a0 + 0.06, a1 - 0.06, half * 0.95, z, z + 0.45, c["white"], c, round_front=0.03, rake=0.02)
        m.shell([(-0.06, z + 0.45, 0.01, "paint"), (0.06, z + 0.45, 0.01, "paint"), (0.05, z + 0.75, 0.02, "paint"), (-0.05, z + 0.75, 0.02, "paint")],
                half * 0.6, {"paint": c["white"]}, crown=0.02)
        m.box(-half * 0.5, half * 0.5, -0.05, 0.05, z + 0.75, z + 0.8, material("solar", "#1e2a3a", metal=0.4, rough=0.3))
    m.text(LETTERS, half + 0.002, 0, z * 0.35, 0.028, band if era != "old" else c["white"], 1)


def icebreaker(m, L, era):
    """A Coast Guard icebreaker: a red hull with a strong rounded bow, a white superstructure, a heli deck aft on the
    later ones; a steam one has a black hull and two tall funnels."""
    c = common()
    paint = material(f"{era} icebreaker hull", "#b8322a" if era == "diesel" else "#1e2024", rough=0.45, clearcoat=0.2)
    half = 0.07
    top = hull(m, L, half, 0.5, paint, c, bow=0.25, stern=0.1, sheer=0.2, transom=0.8)
    a0, a1 = -L / 2, L / 2
    z = top(0.5)
    if era == "steam":
        house(m, a0 + L * 0.3, a1 - L * 0.3, half * 0.8, z, z + 0.5, c["white"], c)
        house(m, a1 - L * 0.36, a1 - L * 0.3, half * 0.7, z + 0.5, z + 0.85, c["white"], c)
        for y in (-0.02, 0.05):
            funnel(m, y - L * 0.05, z + 0.5, z + 1.6, 0.016, c["dark"], material("ice band", "#b8322a", rough=0.4))
        masts(m, [a1 - L * 0.2, a0 + L * 0.2], z, z + 1.8, c)
    else:
        house(m, a0 + L * 0.28, a1 - L * 0.28, half * 0.9, z, z + 0.55, c["white"], c, round_front=0.01)
        house(m, a0 + L * 0.35, a1 - L * 0.3, half * 0.8, z + 0.55, z + 1.0, c["white"], c, round_front=0.01)
        house(m, a1 - L * 0.36, a1 - L * 0.3, half * 0.85, z + 1.0, z + 1.3, c["white"], c, round_front=0.008)
        funnel(m, a0 + L * 0.33, z + 1.0, z + 1.6, 0.025, paint, c["white"])
        # The heli deck, green, aft over the stern.
        m.box(-half * 0.85, half * 0.85, a0 + 0.03, a0 + L * 0.27, z + 0.3, z + 0.36, material("heli deck", "#3a6a3a", rough=0.7))
        m.box(-0.012, 0.012, a0 + L * 0.13, a0 + L * 0.15, z + 0.36, z + 0.37, c["white"])
        masts(m, [a1 - L * 0.32], z + 1.3, z + 2.3, c)
    m.box(-half * 0.9 - 0.001, -half * 0.9 + 0.001, a1 - L * 0.25, a1 - L * 0.08, z * 0.5, z * 0.75, c["white"])


def hovercraft(m, L):
    """A Coast Guard hovercraft: a black rubber skirt, a cabin with a rounded front, and propellers on pylons aft."""
    c = common()
    a0, a1 = -L / 2, L / 2
    half = 0.07
    skirt = material("skirt", "#202022", rough=0.85)
    sections = []
    for k in range(13):
        t = k / 12
        y = a0 + L * t
        w = half * (0.85 + 0.15 * math.sin(t * math.pi)) * (0.75 + 0.25 * min(1.0, (1 - t) * 5))
        ring = [(-w, 0.35), (-w * 1.04, 0.15), (-w * 0.9, 0.0), (w * 0.9, 0.0), (w * 1.04, 0.15), (w, 0.35)]
        sections.append((y, ring))
    m.loft(sections, {"skirt": skirt})
    red = material("hovercraft red", "#c8302a", rough=0.4, clearcoat=0.3)
    m.box(-half * 0.95, half * 0.95, a0 + 0.01, a1 - 0.02, 0.35, 0.42, red, bevel=0.01)
    m.shell([(a0 + 0.06, 0.42, 0.01, "paint"), (a1 - 0.04, 0.42, 0.02, "paint"), (a1 - 0.06, 0.62, 0.008, "glass"), (a1 - 0.1, 0.9, 0.03, "paint"),
             (a0 + 0.08, 0.9, 0.02, "paint")], half * 0.75, {"paint": c["white"], "glass": c["glass"]}, crown=0.05)
    for side in (-1, 1):
        m.box(side * 0.03 - 0.004, side * 0.03 + 0.004, a0 + 0.02, a0 + 0.035, 0.42, 1.2, red)
        m.tube(a0 + 0.015, a0 + 0.04, side * 0.03, 1.15, 0.03, c["steel"], segments=20)
    m.box(-0.002, 0.002, a0 + 0.0, a0 + 0.03, 0.9, 1.5, red)


def ship_builders():
    """The ship models by key, as render_vehicles registers them, with their lengths in sixteenths."""
    return {
        "schooner": (6, schooner),
        "paddle_steamer": (7, paddle_steamer),
        "canaller": (8, canaller),
        "lake_steamer": (10, passenger_steamer),
        "steam_laker": (12, lambda m, L: laker(m, L, "steam")),
        "icebreaker_steam": (9, lambda m, L: icebreaker(m, L, "steam")),
        "tug_barge": (9, tug_barge),
        "package_freighter": (10, lambda m, L: freighter(m, L, "package")),
        "lake_tanker": (10, lambda m, L: freighter(m, L, "tanker")),
        "car_ferry": (9, lambda m, L: car_ferry(m, L, "old")),
        "seaway_laker": (16, lambda m, L: laker(m, L, "seaway")),
        "icebreaker_diesel": (12, lambda m, L: icebreaker(m, L, "diesel")),
        "self_unloader": (15, lambda m, L: laker(m, L, "self")),
        "superferry": (13, lambda m, L: car_ferry(m, L, "super")),
        "hovercraft": (5, hovercraft),
        "container_feeder": (14, lambda m, L: freighter(m, L, "container")),
        "product_tanker": (14, lambda m, L: freighter(m, L, "product")),
        "battery_ferry": (9, lambda m, L: car_ferry(m, L, "battery")),
    }
