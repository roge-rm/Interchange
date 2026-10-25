"""Ships of the lakes, rivers and coasts, in our own colours: built facing +y, L tiles long, x across, z in steps from the waterline.

Hulls are lofted through cross-sections: a stern, parallel sides, a bow that narrows, rakes back and flares, and a
sheer that lifts the deck towards the ends. Great Lakes bulk carriers keep their pilothouse right forward and their
engine aft, as the real lakers do.

Heights here are the builder's: ships are drawn at 45% of them (render_vehicles.height), so a deck of 0.35 comes out
at about 0.16 step, and a laker's freeboard of 0.3 at about 0.13.
"""
import math

import rig
from parts import material
from steam import LETTERS

# The boot top: the dark red band from the waterline up, in steps.
BOOT = 0.12


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
        "floor": material("car deck", "#4a4c4e", rough=0.8),
    }


def hull(m, L, half, free, paint, c, bow=0.18, stern=0.08, sheer=0.12, sheer_aft=None, transom=0.8, deck=None, sharp=False,
         rake=0.05, counter=0.03, flare=0.12, double=False, ice=False, blunt=0.0, boot=BOOT, at=0.0, n=36, x=0.0):
    """A hull L long, centred at y = at, half wide, its deck free steps over the water amidships, rising by sheer at the
    bow and sheer_aft at the stern. bow and stern are how much of the length they take; transom is how wide the stern
    is and blunt how wide the bow stays, for a push boat. rake is how far, as part of the length, the stem's foot sits
    back from its head; ice rounds it into a cut-away icebreaker bow. counter lifts the stern out of the water over
    that much of the length. flare narrows the hull at the waterline. double gives a bow at both ends, as on a ferry.
    x moves it across.
    Returns where the deck is at t (0 at the stern, 1 at the bow), with top.width(t) the deck's half width there."""
    a0 = at - L / 2
    sheer_aft = sheer * 0.35 if sheer_aft is None else sheer_aft

    def fwd(t):
        return max(t, 1 - t) if double else t

    def width(t):
        u = fwd(t)
        if not double and t < stern:
            s = t / stern
            w = transom + (1 - transom) * math.sin(s * math.pi / 2)
        elif u > 1 - bow:
            s = (u - (1 - bow)) / bow
            w = blunt + (1 - blunt) * max(0.0, math.cos(min(s, 1.0) * math.pi / 2)) ** (1.2 if sharp else 0.7)
        else:
            w = 1.0
        return max(w, 0.02)

    def top(t):
        if double:
            u = fwd(t)
            return free + sheer * max(0.0, (u - 0.6) / 0.4) ** 2
        return free + sheer * max(0.0, (t - 0.6) / 0.4) ** 2 + sheer_aft * max(0.0, (0.35 - t) / 0.35) ** 2

    def keel(t):
        """How far the bottom of the hull lifts from the water: the raked stem forward, the counter aft."""
        u = fwd(t)
        if rake > 0 and u > 1 - rake:
            s = (u - (1 - rake)) / rake
            return top(t) * 0.9 * s ** (1.7 if ice else 1.0)
        if not double and counter > 0 and t < counter:
            s = (counter - t) / counter
            return top(t) * 0.55 * s ** 1.5
        return 0.0

    sections = []
    for k in range(n + 1):
        t = k / n
        y = a0 + L * t
        wu = half * width(t)
        wl = wu * (1 - flare) * (0.88 if fwd(t) > 1 - bow else 1.0)
        z, kz = top(t), keel(t)
        zm = kz + (z - kz) * 0.55
        zb = max(kz, min(boot, zm - 0.01))
        wm = wu * (1 - flare * 0.35)
        ring = [(-wu, z), (-wm, zm), (-wl, zb), (-wl * 0.8, kz), (wl * 0.8, kz), (wl, zb), (wm, zm), (wu, z)]
        sections.append((y, [(x + a, b) for a, b in ring]))
    # The sides above the boot top in the hull's paint, the boot top at the waterline dark red, the deck across the top.
    faces = {0: "paint", 1: "paint", 2: "boot", 3: "boot", 4: "boot", 5: "paint", 6: "paint", 7: "deck"}
    m.loft(sections, {"paint": paint, "boot": c["boot"], "deck": deck or c["deck"]}, part=lambda k: faces[k])
    top.width = lambda t: half * width(t)
    top.y = lambda t: a0 + L * t
    return top


def house(m, y0, y1, half, z0, z1, mat, c, windows=True, round_front=0.02, rake=0.0, back=False, x=0.0):
    """A deckhouse from y0 to y1 (its front), half wide about x, from z0 to z1, its front rounded and raked back by rake
    tiles, with a band of windows across the front (and the back too if back) and down the sides."""
    m.shell([(y0, z0, 0.004, "paint"), (y1, z0, 0.004, "paint"), (y1 - rake, z1, round_front, "paint"),
             (y0 + (rake if back else 0.0), z1, round_front if back else 0.006, "paint")], half, {"paint": mat}, crown=0.03, x=x)
    if windows:
        zw = z1 - (z1 - z0) * 0.4
        # In a lowered ship the windows come down less than the house, so they still read, up to half its height.
        g = min(1 / math.sqrt(m.k(zw)), (z1 - z0) * 0.5 / 0.14)
        m.box(x - half * 0.85, x + half * 0.85, y1 - rake * 0.6 - 0.002, y1 - rake * 0.6 + 0.001, zw - 0.07 * g, zw + 0.05 * g, c["glass"])
        if back:
            m.box(x - half * 0.85, x + half * 0.85, y0 + rake * 0.6 - 0.001, y0 + rake * 0.6 + 0.002, zw - 0.07 * g, zw + 0.05 * g, c["glass"])
        for side in (-1, 1):
            m.box(x + side * half - 0.001, x + side * half + 0.001, y0 + 0.01 + (rake if back else 0.0), y1 - 0.01 - rake, zw - 0.06 * g, zw + 0.04 * g, c["glass"])


def funnel(m, y, z0, z1, r, mat, band, x=0.0, rake=0.0, oval=1.15):
    """A funnel standing at y from z0 to z1, r round across and oval times that along, its top raked back by rake tiles,
    with a coloured band and a black top."""
    h = z1 - z0

    def ring(a, b, k, what):
        m.lathe(x, y - rake * a, z0 + h * a, [(r * k, 0), (r * k, h * (b - a))], what, segments=18, lean=rake * (b - a), oval=oval)

    ring(0.0, 1.0, 1.0, mat)
    ring(0.55, 0.78, 1.03, band)
    ring(0.86, 1.0, 1.03, material("funnel top", "#1a1a1a", rough=0.8))


def hatches(m, y0, y1, n, half, z, c, colour="#5a6a5a"):
    """A row of n hatch covers along the deck from y0 to y1."""
    mat = material("hatches", colour, rough=0.6)
    for k in range(n):
        ya = y0 + (y1 - y0) * k / n
        yb = ya + (y1 - y0) / n * 0.78
        m.box(-half, half, ya, yb, z, z + 0.07, mat, bevel=0.003)


def masts(m, ys, z0, z1, c):
    for y in ys:
        m.rod((0, y, z0), (0, y, z1), 0.004, c["dark"])


CAR_COLOURS = ("#c83a2a", "#2a4a7a", "#d8d8d4", "#3a3d40", "#8a8e92", "#d8a020", "#3a6a3a")


def cars(m, y0, y1, xs, z, seed=0):
    """Cars and the odd truck parked in lanes at xs across, from y0 to y1, on a deck z steps up."""
    k = seed
    for x in xs:
        y = y0 + 0.004 * (k % 3)
        while True:
            long = k % 7 == 3
            n = 0.05 if long else 0.026
            if y + n > y1:
                break
            col = material(f"ferry car {k % len(CAR_COLOURS)}", CAR_COLOURS[k % len(CAR_COLOURS)], rough=0.4, clearcoat=0.3)
            m.box(x - 0.0065, x + 0.0065, y, y + n, z, z + (0.28 if long else 0.15), col, bevel=0.002)
            y += n + 0.007
            k += 1


def car_deck(m, top, t0, t1, z0, z1, paint, c, inset=0.98, closed=False):
    """The car deck inside the hull's walls, from t0 to t1 along the hull and z0 to z1 steps over its deck line, open
    at both ends so the cars inside show, or closed at the forward end: its sides follow the hull's width."""
    n = 24
    sections = []
    for k in range(n + 1):
        t = t0 + (t1 - t0) * k / n
        w = top.width(t) * inset
        z = top(t)
        sections.append((top.y(t), [(-w, z + z0), (-w, z + z1), (w, z + z1), (w, z + z0)]))
    if closed:
        y, ring = sections[-1]
        sections.append((y + 0.0005, [(x * 0.01, z) for x, z in ring]))
    faces = {0: "paint", 1: "roof", 2: "paint", 3: "floor"}
    m.loft(sections, {"paint": paint, "roof": c["white"], "floor": c["floor"]}, part=lambda k: faces[k], caps=False)


def fore_house(m, top, L, half, c, at=0.06, zs=(0.34, 0.64), mast=1.1):
    """The pilothouse on its forecastle, its front at at of the length back from the bow, with a mast in front."""
    fwd = L / 2 - L * at
    zf = top(1 - at)
    house(m, fwd - 0.065, fwd, half * 0.85, zf, zf + zs[0], c["white"], c, round_front=0.01)
    house(m, fwd - 0.05, fwd - 0.006, half * 0.65, zf + zs[0], zf + zs[1], c["white"], c, round_front=0.008)
    masts(m, [fwd + 0.012], zf + zs[1], zf + mast, c)
    return fwd


def laker(m, L, kind):
    """A Great Lakes bulk carrier: pilothouse forward, a long run of hatches, the engine house and funnel aft.
    kind is steam, seaway, early (a 1960s self-unloader of the Algorail or Tarantau kind: pilothouse forward, the boom
    on its elevator casing in front of the engine house and laid forward along the deck) or self: a modern
    self-unloader with all its cabins aft and the unloading boom laid forward along the deck from the front of the house."""
    c = common()
    paint = material(f"{kind} laker hull", {"steam": "#3a3d40", "seaway": "#8a2a22", "early": "#2e4a3e", "self": "#2a3a5a"}[kind],
                     rough=0.5, clearcoat=0.2)
    half = 0.065 if kind == "steam" else 0.075
    top = hull(m, L, half, 0.3, paint, c, bow=0.08, stern=0.05, sheer=0.12, transom=0.88 if kind == "steam" else 0.95, rake=0.025,
               counter=0.04 if kind == "steam" else 0.02, flare=0.06, deck=material("red deck", "#7a3a30", rough=0.8))
    a0, a1 = -L / 2, L / 2
    z = top(0.5)
    if kind == "self":
        self_unloader(m, L, top, half, paint, c)
        return
    fwd = fore_house(m, top, L, half, c)
    # Aft: the engine house and funnel.
    aft = a0 + L * 0.04
    house(m, aft, aft + 0.1, half * 0.92, z, z + 0.4, c["white"], c, windows=True)
    house(m, aft + 0.01, aft + 0.075, half * 0.7, z + 0.4, z + 0.68, c["white"], c, windows=kind != "steam")
    funnel(m, aft + 0.03, z + 0.4, z + 1.1 if kind == "steam" else z + 1.02, 0.018 if kind == "steam" else 0.02,
           c["dark"] if kind == "steam" else paint, material(f"{kind} funnel band", "#e0b020", rough=0.4),
           rake=0.008 if kind == "steam" else 0.004)
    masts(m, [aft + 0.085], z + 0.68, z + 1.2, c)
    h0 = aft + 0.12
    if kind == "early":
        # The loop belt's elevator casing just forward of the engine house, the boom's pivot on top of it and the boom
        # laid forward over the hatches to its cradle, with the hoist wires back to an A-frame on the casing.
        y = aft + 0.105
        m.box(-0.018, 0.018, y, y + 0.024, z, z + 0.52, c["steel"], bevel=0.002)
        boom0, boom1 = y + 0.02, y + L * 0.38
        m.box(-0.012, 0.012, boom0, boom1, z + 0.38, z + 0.46, c["steel"], bevel=0.003)
        m.rod((0, boom1 - 0.008, z + 0.38), (0, boom1 + 0.006, z + 0.46), 0.003, c["dark"])
        for side in (-1, 1):
            m.rod((side * 0.03, y + 0.004, z), (side * 0.008, y + 0.012, z + 0.8), 0.003, c["steel"])
            m.rod((side * 0.008, y + 0.012, z + 0.8), (side * 0.008, (boom0 + boom1) / 2, z + 0.46), 0.0012, c["dark"])
            m.rod((side * 0.022, boom1 - 0.02, z), (side * 0.008, boom1 - 0.02, z + 0.38), 0.0035, c["steel"])
        m.box(-0.026, 0.026, boom1 - 0.024, boom1 - 0.016, z, z + 0.05, c["steel"])
        h0 = y + 0.04
    hatches(m, h0, fwd - 0.09, int(L * 22), half * 0.7, z, c, "#4a5a4a" if kind == "steam" else "#2a2a2a")
    m.text(LETTERS, half + 0.002, 0, z * 0.6, 0.03, c["white"], 1)


def self_unloader(m, L, top, half, paint, c):
    """The self-unloader's deck: the whole house aft, four decks with the pilothouse and its wings on top and the
    funnel behind, the unloading boom resting forward on its cradle, hatches the rest of the way to a bare forecastle."""
    a0, a1 = -L / 2, L / 2
    z = top(0.5)
    aft = a0 + L * 0.025
    front = aft + 0.115
    house(m, aft, front, half * 0.94, z, z + 0.34, c["white"], c, round_front=0.006)
    house(m, aft + 0.008, front, half * 0.9, z + 0.34, z + 0.64, c["white"], c, round_front=0.006)
    house(m, aft + 0.03, front - 0.004, half * 0.86, z + 0.64, z + 0.94, c["white"], c, round_front=0.006)
    house(m, front - 0.045, front - 0.006, half * 0.7, z + 0.94, z + 1.22, c["white"], c, round_front=0.006)
    # The bridge wings, out to the ship's sides.
    m.box(-half * 0.98, half * 0.98, front - 0.03, front - 0.008, z + 0.92, z + 0.98, c["white"])
    masts(m, [front - 0.025], z + 1.22, z + 1.6, c)
    funnel(m, aft + 0.022, z + 0.64, z + 1.42, 0.021, paint, material("self funnel band", "#d8d8d4", rough=0.4), rake=0.004)
    # The loop belt casing up the front of the house, the boom's pivot on top of it and the boom laid forward to its cradle.
    boom0, boom1 = front + 0.01, front + L * 0.4
    m.box(-0.016, 0.016, front, front + 0.022, z, z + 0.5, c["steel"], bevel=0.002)
    m.box(-0.012, 0.012, boom0, boom1, z + 0.36, z + 0.44, c["steel"], bevel=0.003)
    m.rod((0, boom1 - 0.008, z + 0.36), (0, boom1 + 0.006, z + 0.44), 0.003, c["dark"])
    for side in (-1, 1):
        m.rod((side * 0.022, boom1 - 0.02, z), (side * 0.008, boom1 - 0.02, z + 0.36), 0.0035, c["steel"])
        # The boom's hoist wires from the casing to its middle.
        m.rod((side * 0.01, front + 0.01, z + 0.5), (side * 0.01, (boom0 + boom1) / 2, z + 0.44), 0.0012, c["dark"])
    m.box(-0.026, 0.026, boom1 - 0.024, boom1 - 0.016, z, z + 0.05, c["steel"])
    fwd = a1 - L * 0.06
    hatches(m, front + 0.03, fwd - 0.03, int(L * 24), half * 0.7, z, c, "#2a2a2a")
    zf = top(0.95)
    masts(m, [fwd + 0.012], zf, zf + 0.7, c)
    m.box(-0.012, 0.012, fwd - 0.012, fwd + 0.004, zf, zf + 0.12, c["dark"], bevel=0.002)
    m.text(LETTERS, half + 0.002, 0, z * 0.6, 0.03, c["white"], 1)


def canaller(m, L):
    """A steam canaller, sized for the old canals: bridge forward on a raised forecastle, hatches, the engine and funnel aft."""
    c = common()
    paint = material("canaller hull", "#2a2c2e", rough=0.55)
    half = 0.058
    top = hull(m, L, half, 0.3, paint, c, bow=0.12, stern=0.08, sheer=0.22, transom=0.75, rake=0.035, counter=0.06, flare=0.08)
    a0, a1 = -L / 2, L / 2
    z = top(0.5)
    fwd = a1 - L * 0.1
    zf = top(0.9)
    house(m, fwd - 0.055, fwd, half * 0.85, zf, zf + 0.32, c["white"], c, round_front=0.008)
    house(m, fwd - 0.045, fwd - 0.006, half * 0.65, zf + 0.32, zf + 0.6, c["white"], c, round_front=0.008)
    aft = a0 + L * 0.07
    house(m, aft, aft + 0.08, half * 0.88, z, z + 0.38, c["white"], c)
    funnel(m, aft + 0.03, z + 0.38, z + 1.15, 0.017, c["dark"], material("canaller band", "#c8302a", rough=0.4), rake=0.006)
    hatches(m, aft + 0.1, fwd - 0.075, int(L * 18), half * 0.7, z, c)
    masts(m, [fwd + 0.02, aft + 0.09], z, z + 1.4, c)


def schooner(m, L):
    """A Great Lakes schooner: a low wooden hull with a counter stern, three masts and gaff sails."""
    c = common()
    paint = material("schooner hull", "#2a3a30", rough=0.6)
    half = 0.045
    top = hull(m, L, half, 0.24, paint, c, bow=0.25, stern=0.12, sheer=0.22, sheer_aft=0.1, transom=0.6, deck=c["wood"], sharp=True,
               rake=0.06, counter=0.1, flare=0.1, boot=0.08)
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
    m.rod((0, a1 - 0.02, top(0.97)), (0, a1 + 0.06, top(1.0) + 0.2), 0.003, c["wood"])
    m.wedge([(-0.002, ys[0] + 0.01, z + 0.4), (0.002, ys[0] + 0.01, z + 0.4), (0.002, a1 + 0.05, z + 0.5), (-0.002, a1 + 0.05, z + 0.5)],
            [(-0.002, ys[0] + 0.01, z + 2.0), (0.002, ys[0] + 0.01, z + 2.0), (0.002, ys[0] + 0.02, z + 2.0), (-0.002, ys[0] + 0.02, z + 2.0)], sail)
    m.box(-0.02, 0.02, a0 + 0.03, a0 + 0.07, top(0.15), top(0.15) + 0.2, c["wood"], bevel=0.004)


def paddle_steamer(m, L):
    """A side-wheel passenger steamer: a long low hull, an open main deck under wide guards, a saloon deck, big paddle
    boxes amidships, a walking beam on its A-frame and two tall stacks side by side."""
    c = common()
    paint = material("steamer hull", "#2a2a2e", rough=0.6)
    half = 0.046
    top = hull(m, L, half, 0.2, paint, c, bow=0.24, stern=0.12, sheer=0.1, transom=0.6, deck=c["wood"], sharp=True,
               rake=0.04, counter=0.1, flare=0.08, boot=0.07)
    a0, a1 = -L / 2, L / 2
    z = top(0.5)
    guard = half + 0.016
    y0, y1 = a0 + 0.05, a1 - 0.085
    # The open main deck: a dark cabin inside, posts along the guards' edges, the saloon deck over them.
    house(m, y0 + 0.01, y1 - 0.01, half * 0.75, z, z + 0.3, material("main deck", "#5a4a3a", rough=0.8), c, windows=False, round_front=0.004)
    m.box(-guard, guard, y0, y1, z - 0.02, z + 0.02, c["wood"])
    for side in (-1, 1):
        for k in range(9):
            y = y0 + 0.006 + (y1 - y0 - 0.012) * k / 8
            m.box(side * (guard - 0.003) - 0.0015, side * (guard - 0.003) + 0.0015, y - 0.0015, y + 0.0015, z, z + 0.32, c["white"])
    m.box(-guard, guard, y0 - 0.008, y1 + 0.006, z + 0.3, z + 0.35, c["white"], bevel=0.002)
    house(m, y0 + 0.02, y1 - 0.03, half * 0.85, z + 0.35, z + 0.64, c["white"], c, round_front=0.008)
    house(m, y1 - 0.07, y1 - 0.04, 0.014, z + 0.64, z + 0.9, c["white"], c, round_front=0.003)
    # The paddle boxes: half discs reaching up past the saloon deck, round on screen, with their slots fanned out.
    red = material("paddle box", "#a8322a", rough=0.5)
    box = material("paddle box face", "#ecebe6", rough=0.5)
    R = 0.052
    zc = m.z(z)
    for side in (-1, 1):
        x = side * (guard + 0.01)
        arc = [(R * math.cos(a), m.fixed(zc + R * math.sin(a) / rig.RISE)) for a in [math.pi * i / 12 for i in range(13)]]
        prof = [(y, zz, 0.0, "paint") for y, zz in arc] + [(-R, 0.05, 0.0, "paint"), (R, 0.05, 0.0, "paint")]
        m.shell(list(reversed(prof)), 0.012, {"paint": box}, x=x)
        for i in range(1, 8):
            a = math.pi * i / 8
            p = (x + side * 0.0125, R * 0.25 * math.cos(a), m.fixed(zc + R * 0.25 * math.sin(a) / rig.RISE))
            q = (x + side * 0.0125, R * 0.92 * math.cos(a), m.fixed(zc + R * 0.92 * math.sin(a) / rig.RISE))
            m.rod(p, q, 0.0022, red, segments=6)
    # The walking beam on its A-frame, over the engine between the paddle boxes.
    pz = z + 1.05
    for dy in (-0.022, 0.022):
        for dx in (-0.008, 0.008):
            m.rod((dx, dy, z + 0.64), (dx * 0.4, 0, pz), 0.002, c["wood"])
    bz = 0.07
    m.wedge([(-0.0015, -0.055, pz), (-0.0015, 0, pz - bz), (-0.0015, 0.055, pz), (-0.0015, 0, pz + bz)],
            [(0.0015, -0.055, pz), (0.0015, 0, pz - bz), (0.0015, 0.055, pz), (0.0015, 0, pz + bz)], c["dark"])
    m.rod((0, 0.05, pz), (0, 0.05, z + 0.64), 0.002, c["dark"])
    for side in (-1, 1):
        funnel(m, y1 - 0.11, z + 0.64, z + 1.55, 0.008, c["dark"], material("steamer band", "#e0b020", rough=0.4), x=side * 0.014, oval=1.0)
    m.rod((0, a1 - 0.04, top(0.9)), (0, a1 - 0.04, top(0.9) + 0.7), 0.0025, c["wood"])


def passenger_steamer(m, L):
    """A lake passenger steamer: a long black hull, two white decks of one even width most of its length, a raked
    funnel and masts fore and aft."""
    c = common()
    paint = material("lake steamer hull", "#1e2024", rough=0.55)
    half = 0.058
    top = hull(m, L, half, 0.3, paint, c, bow=0.18, stern=0.12, sheer=0.12, transom=0.6, sharp=True, rake=0.03, counter=0.08, flare=0.08)
    a0, a1 = -L / 2, L / 2
    z = top(0.5)
    y0, y1 = a0 + L * 0.1, a1 - L * 0.16
    house(m, y0, y1, half * 0.95, z, z + 0.36, c["white"], c, round_front=0.012)
    house(m, y0 + 0.01, y1 - 0.01, half * 0.95, z + 0.36, z + 0.7, c["white"], c, round_front=0.012)
    house(m, y1 - 0.06, y1 - 0.02, half * 0.6, z + 0.7, z + 0.95, c["white"], c, round_front=0.006)
    band = material("steamer red band", "#b8322a", rough=0.4)
    funnel(m, a0 + L * 0.48, z + 0.7, z + 1.45, 0.02, material("steamer funnel", "#d8b060", rough=0.5), band, rake=0.016)
    for y in (a1 - L * 0.12, a0 + L * 0.2):
        m.rod((0, y, z), (0, y - 0.006, z + 1.8), 0.0035, c["dark"])
    # A row of portholes along the hull.
    for side in (-1, 1):
        m.box(side * half - 0.0008, side * half + 0.0008, y0 + 0.02, y1 - 0.02, z * 0.6, z * 0.7, c["glass"])
    m.text(LETTERS, half + 0.002, a1 - L * 0.2, z * 0.5, 0.024, c["white"], 1)


def tug_barge(m, L):
    """A northern push tug and its two barges lashed end to end: deck cargo and containers on the lead barge with its
    raked bow, fuel tanks on the one behind, square at both ends, and the tug's tall wheelhouse behind them."""
    c = common()
    a0, a1 = -L / 2, L / 2
    tug_len = L * 0.27
    tug = material("tug hull", "#c8302a", rough=0.5)
    barge = material("barge", "#5a5e62", metal=0.4, rough=0.6)
    fb = 0.14
    gap = 0.004
    blen = (L - tug_len - 2 * gap) / 2
    # The rear barge against the tug, then the lead barge with its raked bow.
    r0 = a0 + tug_len + gap
    l0 = r0 + blen + gap
    m.shell([(r0 + 0.004, 0.0, 0.003, "paint"), (r0 + blen - 0.004, 0.0, 0.003, "paint"), (r0 + blen, fb, 0.003, "paint"), (r0, fb, 0.003, "paint")],
            0.06, {"paint": barge})
    m.shell([(l0 + 0.004, 0.0, 0.003, "paint"), (a1 - 0.04, 0.0, 0.004, "paint"), (a1, fb, 0.004, "paint"), (l0, fb, 0.003, "paint")], 0.06,
            {"paint": barge})
    tank = material("fuel tank", "#d8d8d4", rough=0.5)
    for k in range(3):
        y = r0 + blen * (k + 0.5) / 3
        for side in (-1, 1):
            m.upright(side * 0.028, y, fb, fb + 0.3, 0.024, 0.024, tank)
    box = [material("box red", "#9a3a2a", rough=0.6), material("box blue", "#2a4a7a", rough=0.6), material("box green", "#3a6a3a", rough=0.6)]
    for k in range(2):
        y = l0 + 0.008 + k * 0.05
        for side in (-1, 1):
            m.box(side * 0.029 - 0.025, side * 0.029 + 0.025, y, y + 0.045, fb, fb + 0.24, box[(k + side) % 3], bevel=0.002)
    # Lumber stacked on the lead barge's bow.
    lumber = material("barge lumber", "#b89060", rough=0.8)
    m.box(-0.045, 0.045, l0 + 0.112, a1 - 0.02, fb, fb + 0.14, lumber, bevel=0.002)
    # The push tug: a beamy hull with a square bow and its push knees against the barge.
    top = hull(m, tug_len, 0.056, 0.26, tug, c, bow=0.12, stern=0.12, sheer=0.04, transom=0.85, blunt=0.85, rake=0.0, counter=0.08,
               flare=0.06, at=a0 + tug_len / 2)
    z = top(0.5)
    t0 = a0
    for side in (-1, 1):
        m.box(side * 0.03 - 0.004, side * 0.03 + 0.004, t0 + tug_len - 0.006, t0 + tug_len + 0.004, 0.05, z + 0.12, c["dark"])
    house(m, t0 + 0.03, t0 + tug_len - 0.03, 0.042, z, z + 0.3, c["white"], c, round_front=0.006)
    house(m, t0 + 0.045, t0 + tug_len - 0.035, 0.034, z + 0.3, z + 0.56, c["white"], c, round_front=0.006)
    house(m, t0 + 0.06, t0 + tug_len - 0.038, 0.03, z + 0.56, z + 0.84, c["white"], c, round_front=0.006)
    for side in (-1, 1):
        funnel(m, t0 + 0.04, z + 0.3, z + 0.75, 0.007, c["dark"], material("tug band", "#e0b020", rough=0.4), x=side * 0.03, oval=1.0)


def freighter(m, L, kind):
    """A lake package freighter or lake tanker, laid out as a laker with the pilothouse forward and the engine aft (the
    package boat with booms over its hatches and doors in its sides, the tanker with a catwalk over its tanks), or a
    product tanker or container feeder with the whole house aft."""
    c = common()
    colours = {"package": "#2a3a2a", "tanker": "#2a2a2c", "product": "#9a2a22", "container": "#2a4a7a"}
    paint = material(f"{kind} hull", colours[kind], rough=0.5, clearcoat=0.2)
    half = 0.062 if kind in ("package", "tanker") else 0.072
    a0, a1 = -L / 2, L / 2
    if kind in ("package", "tanker"):
        top = hull(m, L, half, 0.3 if kind == "package" else 0.26, paint, c, bow=0.1, stern=0.07, sheer=0.14, transom=0.85, rake=0.03,
                   counter=0.05 if kind == "package" else 0.04, flare=0.07)
        z = top(0.5)
        fwd = fore_house(m, top, L, half, c, at=0.07, zs=(0.32, 0.6), mast=1.05)
        aft = a0 + L * 0.04
        house(m, aft, aft + 0.09, half * 0.92, z, z + 0.38, c["white"], c)
        house(m, aft + 0.01, aft + 0.065, half * 0.7, z + 0.38, z + 0.62, c["white"], c)
        funnel(m, aft + 0.03, z + 0.38, z + 1.05, 0.018, c["dark"], material(f"{kind} band", "#e0b020", rough=0.4), rake=0.007)
        masts(m, [aft + 0.075], z + 0.62, z + 1.1, c)
        if kind == "package":
            # Four hatches with a mast and its booms between each pair, and cargo doors in the sides.
            y0, y1 = aft + 0.11, fwd - 0.085
            hatches(m, y0, y1, 4, half * 0.65, z, c)
            step = (y1 - y0) / 4
            for k in (1, 3):
                y = y0 + step * k - step * 0.11
                m.rod((0, y, z), (0, y, z + 1.3), 0.004, c["dark"])
                for dy in (-1, 1):
                    m.rod((0, y + dy * 0.004, z + 0.2), (0, y + dy * step * 0.55, z + 0.8), 0.0025, c["steel"])
            door = material("cargo door", "#1e2a1e", rough=0.6)
            for t in (0.22, 0.36, 0.5, 0.64, 0.78):
                for side in (-1, 1):
                    y = top.y(t)
                    m.box(side * half - 0.001, side * half + 0.001, y - 0.008, y + 0.008, BOOT + 0.03, z - 0.04, door)
            return
        # The catwalk on its posts from the house to the forecastle, the cargo pipes beside it and the tank hatches.
        m.box(-0.006, 0.006, aft + 0.09, fwd - 0.065, z + 0.2, z + 0.23, c["steel"])
        for k in range(9):
            y = aft + 0.1 + (fwd - 0.075 - aft - 0.1) * k / 8
            m.rod((0, y, z), (0, y, z + 0.2), 0.0018, c["steel"])
        pipe = material("deck pipes", "#8a8a2a", rough=0.5)
        for side in (-1, 1):
            m.tube(aft + 0.1, fwd - 0.07, side * 0.016, z + 0.05, 0.004, pipe, segments=8)
        n = 7
        for k in range(n):
            y = aft + 0.115 + (fwd - 0.1 - aft - 0.115) * k / (n - 1)
            for side in (-1, 1):
                m.upright(side * 0.036, y, z, z + 0.08, 0.008, 0.008, pipe)
            m.box(-0.026, 0.026, y - 0.004, y + 0.004, z, z + 0.04, pipe)
        return
    top = hull(m, L, half, 0.32, paint, c, bow=0.15, stern=0.08, sheer=0.18, transom=0.8, rake=0.04, counter=0.03,
               flare=0.12 if kind == "container" else 0.08)
    z = top(0.5)
    aft = a0 + L * 0.05
    levels = {"product": 3, "container": 4}[kind]
    house(m, aft, aft + 0.1, half * 0.92, z, z + 0.32, c["white"], c)
    for k in range(1, levels):
        house(m, aft + 0.04, aft + 0.1, half * (0.88 if k < levels - 1 else 0.98), z + k * 0.32, z + (k + 1) * 0.32, c["white"], c)
    funnel(m, aft + 0.018, z + 0.32, z + 0.32 * (levels - 1) + 0.75, 0.017, paint,
           material(f"{kind} band", "#e0b020", rough=0.4), rake=0.005)
    if kind == "product":
        m.box(-0.006, 0.006, aft + 0.1, a1 - L * 0.12, z + 0.1, z + 0.13, c["steel"])
        pipe = material("deck pipes", "#3a6a3a", rough=0.5)
        for side in (-1, 1):
            m.tube(aft + 0.11, a1 - L * 0.14, side * 0.02, z + 0.05, 0.004, pipe, segments=8)
        for k in range(int(L * 8)):
            y = aft + 0.14 + k * 0.12
            if y > a1 - L * 0.16:
                break
            m.box(-0.03, 0.03, y, y + 0.02, z, z + 0.08, pipe)
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
    """A car ferry. old: one bow, a bow door, the car deck open at the stern, a passenger deck over it and one funnel.
    super: double-ended, two car decks open at both ends, two passenger decks the same width over them, a bridge at
    each end and funnels side by side amidships. battery: double-ended, an open car deck the whole length, the
    passenger house along one side over the cars and its bridge on top, no funnel."""
    c = common()
    paint = material(f"{era} ferry hull", {"old": "#2a3a5a", "super": "#ecebe6", "battery": "#ecebe6"}[era], rough=0.45, clearcoat=0.2)
    band = material(f"{era} ferry band", {"old": "#c8302a", "super": "#2a5a8a", "battery": "#2a8a6a"}[era], rough=0.4)
    half = {"old": 0.062, "super": 0.08, "battery": 0.075}[era]
    double = era != "old"
    a0, a1 = -L / 2, L / 2
    if era == "old":
        top = hull(m, L, half, 0.24, paint, c, bow=0.16, stern=0.08, sheer=0.12, transom=0.85, rake=0.05, counter=0.03, flare=0.06)
        z = top(0.5)
        # The car deck runs from the open stern to the bow door; cars show through the stern.
        car_deck(m, top, 0.04, 0.985, 0.0, 0.42, paint, c, closed=True)
        cars(m, a0 + L * 0.03, a1 - L * 0.12, (-0.03, -0.01, 0.01, 0.03), z, seed=1)
        # The stern ramp, raised, and the bow door's seams.
        m.box(-half * 0.6, half * 0.6, a0 + L * 0.035, a0 + L * 0.045, z, z + 0.3, c["dark"])
        for side in (-1, 1):
            t = 0.9
            m.box(side * top.width(t) - 0.0008, side * top.width(t) + 0.0008, top.y(t) - 0.001, top.y(t) + 0.001, z * 0.6, top(t) + 0.42, c["dark"])
        zg = z + 0.42
        m.box(-half * 0.99, half * 0.99, a0 + 0.006, a0 + L * 0.06, zg - 0.04, zg, c["white"])
        for side in (-1, 1):
            m.box(side * half * 0.99 - 0.0015, side * half * 0.99 + 0.0015, a0 + L * 0.06, a1 - L * 0.1, zg - 0.1, zg - 0.03, band)
        house(m, a0 + L * 0.1, a1 - L * 0.16, half * 0.95, zg, zg + 0.34, c["white"], c, round_front=0.01)
        house(m, a1 - L * 0.26, a1 - L * 0.18, half * 0.75, zg + 0.34, zg + 0.6, c["white"], c, round_front=0.008)
        funnel(m, a0 + L * 0.42, zg + 0.34, zg + 0.9, 0.02, band, c["dark"], rake=0.006)
        m.text(LETTERS, half + 0.002, 0, z * 0.55, 0.026, c["white"], 1)
        return
    if era == "super":
        top = hull(m, L, half, 0.22, paint, c, bow=0.14, sheer=0.06, double=True, rake=0.05, flare=0.05)
        z = top(0.5)
        # Two car decks the length of the ship, open at both ends, the upper one on a floor halfway up.
        car_deck(m, top, 0.035, 0.965, 0.0, 0.72, paint, c)
        m.box(-half * 0.95, half * 0.95, a0 + L * 0.05, a1 - L * 0.05, z + 0.34, z + 0.37, c["floor"])
        lanes = (-0.045, -0.025, -0.005, 0.015, 0.035, 0.055)
        cars(m, a0 + L * 0.04, a1 - L * 0.04, lanes[:-1], z, seed=2)
        cars(m, a0 + L * 0.06, a1 - L * 0.06, lanes[1:], z + 0.37, seed=5)
        zg = z + 0.72
        for side in (-1, 1):
            m.box(side * half * 0.985 - 0.0015, side * half * 0.985 + 0.0015, a0 + L * 0.05, a1 - L * 0.05, zg - 0.14, zg - 0.04, band)
        y0, y1 = a0 + L * 0.12, a1 - L * 0.12
        house(m, y0, y1, half * 0.97, zg, zg + 0.34, c["white"], c, round_front=0.02, rake=0.012, back=True)
        house(m, y0 + 0.02, y1 - 0.02, half * 0.97, zg + 0.34, zg + 0.66, c["white"], c, round_front=0.02, rake=0.012, back=True)
        for side in (-1, 1):
            m.box(side * half * 0.97 - 0.0015, side * half * 0.97 + 0.0015, y0 + 0.02, y1 - 0.02, zg + 0.31, zg + 0.37, band)
        # A bridge at each end, and the funnels side by side amidships.
        for end in (-1, 1):
            yb = end * (L / 2 - L * 0.17)
            house(m, yb - 0.022, yb + 0.022, half * 0.9, zg + 0.66, zg + 0.9, c["white"], c, round_front=0.006, back=True)
        for side in (-1, 1):
            funnel(m, 0.0, zg + 0.66, zg + 1.08, 0.016, c["white"], band, x=side * 0.032, oval=1.6)
        m.text(LETTERS, half + 0.002, 0, z * 0.55, 0.026, band, 1)
        return
    top = hull(m, L, half, 0.22, paint, c, bow=0.12, sheer=0.05, double=True, rake=0.05, flare=0.05)
    z = top(0.5)
    deck = material("battery ferry deck", "#5a5c5e", rough=0.8)
    m.box(-half * 0.96, half * 0.96, a0 + L * 0.06, a1 - L * 0.06, z - 0.01, z + 0.01, deck)
    for side in (-1, 1):
        m.box(side * half * 0.97 - 0.002, side * half * 0.97 + 0.002, a0 + L * 0.1, a1 - L * 0.1, z, z + 0.14, paint)
        m.box(side * half * 0.97 - 0.0022, side * half * 0.97 + 0.0022, a0 + L * 0.1, a1 - L * 0.1, z + 0.06, z + 0.12, band)
    # The passenger house along one side over the cars, its bridge on top with windows at both ends.
    xh = half * 0.68
    house(m, a0 + L * 0.2, a1 - L * 0.2, half * 0.28, z, z + 0.66, c["white"], c, round_front=0.012, rake=0.006, back=True, x=xh)
    m.box(xh - half * 0.29, xh + half * 0.29, a0 + L * 0.2, a1 - L * 0.2, z + 0.28, z + 0.33, band)
    house(m, -0.03, 0.03, half * 0.6, z + 0.66, z + 0.9, c["white"], c, round_front=0.006, back=True, x=half * 0.35)
    cars(m, a0 + L * 0.05, a1 - L * 0.05, (-0.055, -0.035, -0.015), z + 0.01, seed=3)
    cars(m, a0 + L * 0.05, a0 + L * 0.18, (0.01, 0.035), z + 0.01, seed=4)
    m.text(LETTERS, half + 0.002, a0 + L * 0.3, z * 0.55, 0.024, band, 1)


def icebreaker(m, L, era):
    """A Coast Guard icebreaker: a short beamy hull with a cut-away ice bow. steam: a black hull, a white house, two
    buff funnels close together, masts fore and aft. diesel: a red hull with a white stripe at the bow, a block of
    white superstructure forward, the hangar and heli deck aft and two funnels side by side."""
    c = common()
    paint = material(f"{era} icebreaker hull", "#b8322a" if era == "diesel" else "#1e2024", rough=0.45, clearcoat=0.2)
    half = 0.07 if era == "steam" else 0.076
    top = hull(m, L, half, 0.36 if era == "steam" else 0.4, paint, c, bow=0.28, stern=0.12, sheer=0.14, sheer_aft=0.06, transom=0.7,
               rake=0.2, ice=True, counter=0.06, flare=0.14)
    a0, a1 = -L / 2, L / 2
    z = top(0.5)
    if era == "steam":
        y0, y1 = a0 + L * 0.34, a1 - L * 0.3
        house(m, y0, y1, half * 0.78, z, z + 0.34, c["white"], c, round_front=0.008)
        house(m, y1 - 0.045, y1, half * 0.95, z + 0.34, z + 0.62, c["white"], c, round_front=0.006)
        buff = material("ice funnel", "#c8a060", rough=0.5)
        for y in (y0 + 0.04, y0 + 0.08):
            funnel(m, y, z + 0.34, z + 1.35, 0.015, buff, c["dark"], rake=0.008)
        masts(m, [a1 - L * 0.18, a0 + L * 0.2], z, z + 1.6, c)
    else:
        # The superstructure, one block forward of amidships, the bridge with its wings across the top.
        y0, y1 = a0 + L * 0.42, a1 - L * 0.3
        house(m, y0, y1, half * 0.9, z, z + 0.36, c["white"], c, round_front=0.01)
        house(m, y0 + 0.01, y1, half * 0.9, z + 0.36, z + 0.7, c["white"], c, round_front=0.01)
        house(m, y1 - 0.05, y1, half * 0.98, z + 0.7, z + 0.98, c["white"], c, round_front=0.006)
        masts(m, [y1 - 0.03], z + 0.98, z + 1.6, c)
        # The hangar behind it, with the funnels side by side on top, and the heli deck aft over the stern.
        house(m, a0 + L * 0.3, y0, half * 0.75, z, z + 0.44, c["white"], c, windows=False, round_front=0.004)
        for side in (-1, 1):
            funnel(m, y0 - 0.025, z + 0.44, z + 0.98, 0.016, paint, c["white"], x=side * 0.028)
        m.box(-half * 0.92, half * 0.92, a0 + 0.02, a0 + L * 0.3, z + 0.36, z + 0.41, material("heli deck", "#3a6a3a", rough=0.7))
        m.box(-0.012, 0.012, a0 + L * 0.14, a0 + L * 0.16, z + 0.41, z + 0.42, c["white"])
        for side in (-1, 1):
            m.rod((side * half * 0.85, a0 + L * 0.06, z), (side * half * 0.85, a0 + L * 0.06, z + 0.36), 0.002, c["white"])
            m.rod((side * half * 0.85, a0 + L * 0.24, z), (side * half * 0.85, a0 + L * 0.24, z + 0.36), 0.002, c["white"])
        # The white stripe slanting up the bow on each side.
        t0, t1 = 0.56, 0.64
        for side in (-1, 1):
            x = side * (top.width(0.6) + 0.0012)
            m.wedge([(x - 0.0008, top.y(t0), 0.13), (x + 0.0008, top.y(t0), 0.13), (x + 0.0008, top.y(t0) + 0.032, 0.13), (x - 0.0008, top.y(t0) + 0.032, 0.13)],
                    [(x - 0.0008, top.y(t1), z - 0.03), (x + 0.0008, top.y(t1), z - 0.03), (x + 0.0008, top.y(t1) + 0.032, z - 0.03),
                     (x - 0.0008, top.y(t1) + 0.032, z - 0.03)], c["white"])
    m.text(LETTERS, half + 0.002, a0 + L * 0.4, z * 0.55, 0.026, c["white"], 1)


def hovercraft(m, L):
    """A Coast Guard hovercraft: wide and flat on a thick black skirt, a red buoyancy tank, the white cabin forward with
    its wheelhouse on top, and two big ducted propellers aft side by side with rudders in them."""
    c = common()
    a0, a1 = -L / 2, L / 2
    half = 0.07
    skirt = material("skirt", "#202022", rough=0.85)
    red = material("hovercraft red", "#c8302a", rough=0.4, clearcoat=0.3)
    zs, zd = 0.3, 0.4

    def plan(t):
        """Half width at t along, rounded at the bow and a little at the stern."""
        bow = max(0.0, (t - 0.75) / 0.25)
        aft = max(0.0, (0.1 - t) / 0.1)
        return half * math.sqrt(max(0.0, 1 - bow ** 2)) ** 0.6 * (1 - 0.12 * aft ** 2)

    sections, deck = [], []
    for k in range(25):
        t = k / 24
        y = a0 + L * t
        w = max(plan(t), 0.004)
        sections.append((y, [(-w * 0.92, zs), (-w * 1.0, zs * 0.7), (-w * 1.02, zs * 0.4), (-w * 0.95, 0.04), (-w * 0.85, 0.0),
                             (w * 0.85, 0.0), (w * 0.95, 0.04), (w * 1.02, zs * 0.4), (w * 1.0, zs * 0.7), (w * 0.92, zs)]))
        deck.append((y, [(-w * 0.94, zs), (-w * 0.94, zd), (w * 0.94, zd), (w * 0.94, zs)]))
    m.loft(sections, {"skirt": skirt})
    m.loft(deck, {"red": red}, sharp=50)
    # The cabin, its raked front glazed, and the wheelhouse on top of it forward.
    y0, y1 = a0 + L * 0.3, a1 - L * 0.1
    m.shell([(y0, zd, 0.006, "paint"), (y1, zd, 0.012, "paint"), (y1 - 0.012, zd + 0.22, 0.006, "glass"), (y1 - 0.03, zd + 0.42, 0.01, "paint"),
             (y0, zd + 0.42, 0.008, "paint")], half * 0.72, {"paint": c["white"], "glass": c["glass"]}, crown=0.04)
    zw = zd + 0.3
    for side in (-1, 1):
        m.box(side * half * 0.72 - 0.001, side * half * 0.72 + 0.001, y0 + 0.01, y1 - 0.03, zw - 0.05, zw + 0.03, c["glass"])
        m.box(side * half * 0.72 - 0.0012, side * half * 0.72 + 0.0012, y0 + 0.004, y1 - 0.01, zd + 0.02, zd + 0.1, red)
    house(m, y1 - 0.075, y1 - 0.035, half * 0.4, zd + 0.42, zd + 0.66, c["white"], c, round_front=0.006, rake=0.008)
    # The ducted propellers, kept round and their full size: a white ring with a dark mouth, a hub and two rudders behind.
    r = 0.024
    with m.keep(at=zd):
        zc = zd + 0.02 + r / rig.RISE
        for side in (-1, 1):
            x = side * 0.034
            m.tube(a0 + 0.006, a0 + 0.034, x, zc, r, c["white"], segments=28)
            m.tube(a0 + 0.004, a0 + 0.036, x, zc, r * 0.82, c["dark"], segments=28)
            m.tube(a0 + 0.035, a0 + 0.04, x, zc, r * 0.2, c["steel"], segments=12)
            for dx in (-0.008, 0.008):
                m.box(x + dx - 0.0012, x + dx + 0.0012, a0 + 0.0, a0 + 0.008, zc - r * 0.8 / rig.RISE, zc + r * 0.8 / rig.RISE, red)
            m.box(x - 0.01, x + 0.01, a0 + 0.012, a0 + 0.028, zd, zc - r * 0.9 / rig.RISE, c["white"])


def srn5(m, L):
    """The Coast Guard's first hovercraft, a Saunders-Roe SR.N5 of 1968: about 12 m by 7, a red buoyancy tank on a black
    skirt, a long white cabin with side decks round it and the control cabin raised at its front, and one big pusher
    propeller on a pylon at the back of the cabin, with two fins and rudders at the stern corners."""
    c = common()
    a0, a1 = -L / 2, L / 2
    half = 0.066
    skirt = material("skirt", "#202022", rough=0.85)
    red = material("hovercraft red", "#c8302a", rough=0.4, clearcoat=0.3)
    zs, zd = 0.26, 0.34

    def plan(t):
        """Half width at t along: square sided, well rounded at the bow and a little at the stern."""
        bow = max(0.0, (t - 0.7) / 0.3)
        aft = max(0.0, (0.12 - t) / 0.12)
        return half * math.sqrt(max(0.0, 1 - bow ** 2)) ** 0.5 * (1 - 0.18 * aft ** 2)

    sections, deck = [], []
    for k in range(25):
        t = k / 24
        y = a0 + L * t
        w = max(plan(t), 0.004)
        sections.append((y, [(-w * 0.92, zs), (-w * 1.0, zs * 0.7), (-w * 1.03, zs * 0.4), (-w * 0.95, 0.04), (-w * 0.85, 0.0),
                             (w * 0.85, 0.0), (w * 0.95, 0.04), (w * 1.03, zs * 0.4), (w * 1.0, zs * 0.7), (w * 0.92, zs)]))
        deck.append((y, [(-w * 0.94, zs), (-w * 0.94, zd), (w * 0.94, zd), (w * 0.94, zs)]))
    m.loft(sections, {"skirt": skirt})
    m.loft(deck, {"red": red}, sharp=50)
    # The cabin, narrower than the craft so side decks show, its front sloped and glazed, windows down the sides.
    y0, y1 = a0 + L * 0.2, a1 - L * 0.16
    cw = half * 0.6
    m.shell([(y0, zd, 0.006, "paint"), (y1, zd, 0.01, "paint"), (y1 - 0.008, zd + 0.16, 0.006, "glass"), (y1 - 0.024, zd + 0.34, 0.01, "paint"),
             (y0, zd + 0.34, 0.008, "paint")], cw, {"paint": c["white"], "glass": c["glass"]}, crown=0.04)
    zw = zd + 0.22
    for side in (-1, 1):
        m.box(side * cw - 0.001, side * cw + 0.001, y0 + 0.01, y1 - 0.024, zw - 0.05, zw + 0.03, c["glass"])
        m.box(side * cw - 0.0012, side * cw + 0.0012, y0 + 0.004, y1 - 0.006, zd + 0.01, zd + 0.07, red)
    # The control cabin on the roof at the front, glazed all round.
    house(m, y1 - 0.06, y1 - 0.022, cw * 0.7, zd + 0.34, zd + 0.54, c["white"], c, round_front=0.006, rake=0.008, back=True)
    # The engine's air intake on the roof behind it and the pylon at the back carrying the propeller.
    m.box(-cw * 0.6, cw * 0.6, y0 + 0.006, y0 + 0.05, zd + 0.34, zd + 0.42, c["steel"], bevel=0.004)
    r = 0.03
    with m.keep(at=zd + 0.34):
        zc = zd + 0.34 + 0.012 + r / rig.RISE
        yp = y0 - 0.004
        m.box(-0.008, 0.008, y0 - 0.006, y0 + 0.02, zd + 0.34, zc, c["white"], bevel=0.002)
        m.tube(yp - 0.012, y0 + 0.012, 0.0, zc, 0.007, c["white"], segments=12)
        m.tube(yp - 0.016, yp - 0.01, 0.0, zc, 0.004, c["dark"], segments=10)
        # Four blades, one painted tip each.
        for k in range(4):
            a = math.pi / 4 + k * math.pi / 2
            tip = (math.cos(a) * r, yp - 0.013, zc + math.sin(a) * r / rig.RISE)
            m.rod((0.0, yp - 0.013, zc), tip, 0.0035, c["dark"], segments=6)
            m.rod((tip[0] * 0.85, tip[1], zc + (tip[2] - zc) * 0.85), tip, 0.0036, material("prop tip", "#e8c020", rough=0.4), segments=6)
    # The fins and rudders at the stern corners.
    for side in (-1, 1):
        x = side * half * 0.78
        m.shell([(a0 + 0.004, zd, 0.002, "paint"), (a0 + 0.034, zd, 0.002, "paint"), (a0 + 0.026, zd + 0.62, 0.004, "paint"),
                 (a0 + 0.008, zd + 0.66, 0.004, "paint")], 0.0025, {"paint": c["white"]}, x=x, cols=(-1, 1))
        m.box(x - 0.0028, x + 0.0028, a0 + 0.0, a0 + 0.008, zd + 0.04, zd + 0.58, red)
    m.text(LETTERS, half * 0.94 + 0.002, a0 + L * 0.45, (zs + zd) / 2, 0.02, c["white"], 1)


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
        "self_unloader_fwd": (14, lambda m, L: laker(m, L, "early")),
        "srn5": (4, srn5),
        "container_feeder": (14, lambda m, L: freighter(m, L, "container")),
        "product_tanker": (14, lambda m, L: freighter(m, L, "product")),
        "battery_ferry": (9, lambda m, L: car_ferry(m, L, "battery")),
    }
