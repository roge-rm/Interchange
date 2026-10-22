"""Horse-drawn road vehicles, in our own colours: an omnibus, a Concord stagecoach, a mail coach, a covered freight
wagon, a Red River cart behind an ox, and a horsecar on tram rails. Built facing +y as roadcars.py builds, L tiles long,
x across, z in steps. The team walks in front, the vehicle behind it.

A horse stands about 0.7 steps at the withers and is about 0.11 tiles long; its legs are a little under half its
height, as a real horse's are.
"""
import math

import rig
from parts import material
from steam import LETTERS

COATS = ["#6a4428", "#3a2a20", "#8a5a34", "#9a958c"]


def _ring(cx, cz, rx, rz, n=10):
    """An ellipse round (cx, cz) across x and up z, for a loft section."""
    return [(cx + rx * math.cos(2 * math.pi * k / n), cz + rz * math.sin(2 * math.pi * k / n)) for k in range(n)]


def horse(m, x, y, coat, ox=False):
    """A horse, or an ox, standing at x across with its rump at y, facing +y; it keeps its size in a lowered model."""
    with m.keep():
        return _horse(m, x, y, coat, ox)


def _horse(m, x, y, coat, ox):
    hide = material(f"coat {coat}", coat, rough=0.8)
    dark = material("hoof", "#1e1a18", rough=0.7)
    long = 0.1 if ox else 0.09
    half = 0.025 if ox else 0.021
    mid = 0.44 if ox else 0.48
    deep = 0.2 if ox else 0.19
    leg = mid - deep * 0.75
    # The barrel, fuller in the middle than at the rump and chest.
    sections = []
    for k in range(7):
        f = k / 6
        swell = 0.75 + 0.25 * math.sin(math.pi * f)
        sections.append((y + long * f, _ring(x, mid, half * swell, deep * swell)))
    m.loft(sections, {"hide": hide})
    # Legs right under the body, thick at the top and fine below the knee, a hoof at each foot.
    thick = 1.25 if ox else 1.0
    for ly in (y + 0.014, y + long - 0.014):
        for side in (-1, 1):
            lx = x + side * half * 0.55
            m.upright(lx, ly, leg * 0.5, leg + 0.06, 0.0065 * thick, 0.0095 * thick, hide, segments=8)
            m.upright(lx, ly, 0.05, leg * 0.5 + 0.01, 0.0042 * thick, 0.005 * thick, hide, segments=8)
            m.upright(lx, ly, 0.0, 0.05, 0.0055 * thick, 0.0045 * thick, dark, segments=8)
    if ox:
        # A low, heavy neck and head carried forward, and short horns.
        m.rod((x, y + long - 0.01, mid + 0.05), (x, y + long + 0.025, mid + 0.02), 0.014, hide)
        m.rod((x, y + long + 0.02, mid + 0.04), (x, y + long + 0.045, mid - 0.06), 0.01, hide)
        for side in (-1, 1):
            m.rod((x + side * 0.006, y + long + 0.024, mid + 0.07), (x + side * 0.018, y + long + 0.03, mid + 0.12), 0.0025,
                  material("horn", "#d8ccb0", rough=0.5))
    else:
        # The neck up and forward, the head angled down from the poll.
        m.rod((x, y + long - 0.016, mid + 0.06), (x, y + long + 0.014, mid + 0.3), 0.013, hide)
        m.rod((x, y + long + 0.011, mid + 0.31), (x, y + long + 0.04, mid + 0.16), 0.0085, hide)
        m.rod((x, y + long - 0.004, mid + 0.18), (x, y + long + 0.016, mid + 0.36), 0.003, dark)
        # A collar for the harness.
        m.rod((x, y + long - 0.008, mid + 0.03), (x, y + long + 0.002, mid + 0.16), 0.012, material("collar", "#2a2420", rough=0.7))
    # The tail.
    m.rod((x, y + 0.002, mid + deep * 0.6), (x, y - 0.012, mid - deep * 0.4), 0.004 if not ox else 0.0025, dark if not ox else hide)
    return y + long


def team(m, y, pairs=1, coats=(0, 1)):
    """Horses in pairs ahead of y, the front pair furthest on; returns where the pole between them starts."""
    for p in range(pairs):
        for k, side in enumerate((-1, 1)):
            horse(m, side * 0.024, y + p * 0.105, COATS[coats[(k + p) % len(coats)] % len(COATS)])
    return y


def wheel(m, x, y, r, rim, spokes=10, hub_r=None):
    """A wooden spoked wheel across x at y, r tiles round, its foot on the road."""
    with m.keep():
        _wheel(m, x, y, r, rim, spokes, hub_r)


def _wheel(m, x, y, r, rim, spokes, hub_r):
    zc = r / rig.RISE

    def at(a, f):
        return (x, y + math.cos(a) * r * f, zc + math.sin(a) * r * f / rig.RISE)
    # The rim as short straight pieces round the wheel.
    n = 20
    for k in range(n):
        m.rod(at(2 * math.pi * k / n, 0.92), at(2 * math.pi * (k + 1) / n, 0.92), r * 0.08, rim, segments=6)
    m.tube_x(x - 0.007, x + 0.007, y, zc, hub_r or r * 0.22, rim, segments=12)
    for k in range(spokes):
        a = 2 * math.pi * k / spokes
        m.rod((x, y, zc), at(a, 0.88), 0.0016, rim, segments=5)


def axle(m, y, r, x, body, dark):
    """An axle across at y between wheels r tiles round at x either side, and a spring each side up to the body's
    underside at body steps."""
    hub = r / rig.RISE
    with m.keep():
        m.rod((-x, y, hub), (x, y, hub), 0.004, dark, segments=6)
    if m.z(body) > hub + 0.02:
        for side in (-1, 1):
            m.box(side * x * 0.55 - 0.005, side * x * 0.55 + 0.005, y - 0.012, y + 0.012, m.fixed(hub), body, dark)


def driver(m, y, z, coat="#2a3a5a"):
    """A driver sat up front, reins forward."""
    cloth = material(f"driver {coat}", coat, rough=0.8)
    with m.keep(z):
        _driver(m, y, z, cloth)


def _driver(m, y, z, cloth):
    m.upright(0.0, y, z, z + 0.22, 0.012, 0.01, cloth, segments=10)
    m.upright(0.0, y, z + 0.22, z + 0.3, 0.007, 0.007, material("face", "#d8b090", rough=0.6), segments=10)
    m.upright(0.0, y, z + 0.3, z + 0.34, 0.01, 0.007, material("hat", "#1e1e20", rough=0.6), segments=10)


def pole(m, y0, y1, z, wood):
    """The pole from the vehicle forward between the pair."""
    m.rod((0.0, y0, z), (0.0, y1, z + 0.08), 0.003, wood)


def omnibus(m, L):
    """A horse omnibus: a long box body with a curved roof and windows all along, a door at the back, the driver up
    front over the pair."""
    a0, a1 = -L / 2 + 0.005, L / 2 - 0.005
    body = material("omnibus", "#2c4c38", rough=0.45, clearcoat=0.3)
    trim = material("omnibus trim", "#e6dcc0", rough=0.45)
    glass = material("glass", "#1e2a36", rough=0.1)
    wood = material("wheel red", "#a2342a", rough=0.6)
    dark = material("undercarriage", "#222224", rough=0.7)
    b0, b1 = a0 + 0.01, a0 + 0.24
    half = 0.07
    floor, top = 0.3, 1.1
    m.shell([(b0, floor, 0.006, "paint", 1), (b1, floor, 0.006, "paint", 1), (b1 + 0.004, top - 0.12, 0.02, "paint", 1),
             (b1 - 0.01, top, 0.03, "paint"), (b0 + 0.01, top, 0.03, "paint"), (b0 - 0.004, top - 0.12, 0.02, "paint", 1)],
            half, {"paint": body}, crown=0.05)
    for side in (-1, 1):
        for k in range(5):
            ya = b0 + 0.02 + k * 0.043
            m.box(side * half - 0.001, side * half + 0.001, ya, ya + 0.032, floor + 0.4, top - 0.18, glass)
        m.box(side * half - 0.0015, side * half + 0.0015, b0 + 0.01, b1 - 0.01, floor + 0.3, floor + 0.36, trim)
    m.box(-0.025, 0.025, b0 - 0.003, b0 + 0.002, floor, top - 0.15, glass)
    m.box(-half, half, b0 - 0.02, b0, floor - 0.04, floor, dark)
    # The driver's seat up at the front, the footboard below it.
    m.box(-0.04, 0.04, b1 - 0.005, b1 + 0.03, top - 0.02, top + 0.06, dark)
    driver(m, b1 + 0.012, top + 0.06)
    for y, r in ((b0 + 0.05, 0.048), (b1 - 0.04, 0.036)):
        axle(m, y, r, half + 0.006, floor, dark)
        for side in (-1, 1):
            wheel(m, side * (half + 0.006), y, r, wood, spokes=12)
    pole(m, b1, b1 + 0.03, 0.4, dark)
    team(m, b1 + 0.03)
    m.text(LETTERS, half + 0.002, (b0 + b1) / 2, floor + 0.2, 0.02, trim, 1)


def coach(m, L, mail):
    """A Concord stagecoach slung on its leather braces: an egg-shaped body, a high box seat for the driver, a boot at the
    back and luggage on the roof. The mail coach is the same in darker colours and lettered for the mail."""
    a0, a1 = -L / 2 + 0.005, L / 2 - 0.005
    paint, wheels = ("#5a1e22", "#1e1e20") if mail else ("#a2342a", "#d8a020")
    body = material("coach mail" if mail else "coach", paint, rough=0.4, clearcoat=0.4)
    gold = material("coach trim", "#c9a640", metal=0.4, rough=0.35)
    glass = material("glass", "#1e2a36", rough=0.1)
    wood = material("coach wheels mail" if mail else "coach wheels", wheels, rough=0.6)
    dark = material("undercarriage", "#222224", rough=0.7)
    leather = material("boot", "#3a2a20", rough=0.75)
    b0 = a0 + 0.03
    b1 = b0 + (0.15 if not mail else 0.13)
    half = 0.062
    lo, hi = 0.42, 1.04
    # The body's curved belly and flat top, its ends leaning out.
    m.shell([(b0 + 0.02, lo, 0.03, "paint", 0.92), (b1 - 0.02, lo, 0.03, "paint", 0.92), (b1 + 0.008, lo + 0.25, 0.03, "paint", 1),
             (b1, hi, 0.012, "paint", 1), (b0, hi, 0.012, "paint", 1), (b0 - 0.008, lo + 0.25, 0.03, "paint", 1)],
            half, {"paint": body}, crown=0.04)
    for side in (-1, 1):
        m.box(side * half - 0.001, side * half + 0.001, (b0 + b1) / 2 - 0.022, (b0 + b1) / 2 + 0.022, lo + 0.32, hi - 0.1, glass)
        m.box(side * half - 0.0015, side * half + 0.0015, b0 + 0.004, b1 - 0.004, hi - 0.05, hi - 0.03, gold)
    # The boot behind, the box seat in front with the driver, a rail of luggage on the roof.
    m.box(-0.05, 0.05, b0 - 0.03, b0 - 0.002, lo + 0.05, lo + 0.4, leather, bevel=0.004)
    m.box(-0.045, 0.045, b1 - 0.004, b1 + 0.035, hi - 0.15, hi - 0.06, body, bevel=0.003)
    driver(m, b1 + 0.016, hi - 0.06)
    for k, (yy, cc) in enumerate(((b0 + 0.03, "#6a5a40"), (b0 + 0.07, "#4a3a2a"))):
        m.box(-0.035, 0.035, yy - 0.016, yy + 0.016, hi, hi + 0.12 - k * 0.03, material(f"trunk {cc}", cc, rough=0.7), bevel=0.003)
    # The undercarriage: a perch from axle to axle, and the body slung between them on leather braces.
    rear, front = (b0 + 0.015, 0.05), (b1 + 0.005, 0.04)
    m.box(-0.006, 0.006, rear[0], front[0], 0.17, 0.21, dark)
    for y, r in (rear, front):
        axle(m, y, r, half + 0.008, 0.3, dark)
        for side in (-1, 1):
            wheel(m, side * (half + 0.008), y, r, wood, spokes=12)
    for side in (-1, 1):
        m.rod((side * 0.04, rear[0], 0.3), (side * 0.04, (rear[0] + front[0]) / 2, lo + 0.02), 0.004, leather, segments=6)
        m.rod((side * 0.04, (rear[0] + front[0]) / 2, lo + 0.02), (side * 0.04, front[0], 0.3), 0.004, leather, segments=6)
    pole(m, b1 + 0.02, b1 + 0.045, 0.4, dark)
    team(m, b1 + 0.045, coats=(1, 2) if mail else (0, 2))
    m.text("MAIL" if mail else LETTERS, half + 0.002, (b0 + b1) / 2, lo + 0.18, 0.02, gold, 1)


def freight_wagon(m, L):
    """A covered freight wagon: a deep bed sloping up at each end, canvas over hoops, big wheels behind."""
    a0, a1 = -L / 2 + 0.005, L / 2 - 0.005
    bed = material("wagon bed", "#2a4a7a", rough=0.6)
    canvas = material("canvas", "#e8e2d2", rough=0.9)
    wood = material("wagon wheels", "#a2342a", rough=0.6)
    dark = material("undercarriage", "#222224", rough=0.7)
    b0, b1 = a0 + 0.01, a0 + 0.24
    half = 0.06
    lo = 0.36
    m.shell([(b0 + 0.012, lo, 0.004, "paint", 1), (b1 - 0.012, lo, 0.004, "paint", 1), (b1 + 0.012, lo + 0.3, 0.004, "paint", 1),
             (b0 - 0.012, lo + 0.3, 0.004, "paint", 1)], half, {"paint": bed})
    # The canvas over its hoops, full at the middle and drawn in at the ends.
    sections = []
    for k in range(9):
        f = k / 8
        y = b0 + 0.005 + (b1 - b0 - 0.01) * f
        rise = 0.52 + 0.05 * (abs(f - 0.5) * 2) ** 2
        sections.append((y, [(math.cos(math.pi * j / 10) * (half + 0.004), lo + 0.3 + math.sin(math.pi * j / 10) * rise)
                             for j in range(11)] + [(-half - 0.004, lo + 0.29), (half + 0.004, lo + 0.29)]))
    m.loft(sections, {"canvas": canvas})
    m.box(-0.006, 0.006, b0 + 0.04, b1 - 0.035, 0.18, 0.22, dark)
    for y, r in ((b0 + 0.045, 0.05), (b1 - 0.04, 0.042)):
        axle(m, y, r, half + 0.008, lo, dark)
        m.box(-half * 0.8, half * 0.8, y - 0.01, y + 0.01, lo - 0.05, lo, dark)
        for side in (-1, 1):
            wheel(m, side * (half + 0.008), y, r, wood, spokes=12)
    driver(m, b1 - 0.01, lo + 0.3, coat="#5a4a3a")
    pole(m, b1, b1 + 0.03, 0.38, dark)
    team(m, b1 + 0.03, coats=(2, 3))


def red_river_cart(m, L):
    """A Red River cart: all wood, two tall dished wheels, a light box between them and long shafts to the ox."""
    a0, a1 = -L / 2 + 0.005, L / 2 - 0.005
    wood = material("cart wood", "#a08060", rough=0.85)
    load = material("cart load", "#d8c890", rough=0.9)
    y = a0 + 0.045
    half = 0.05
    r = 0.045
    for side in (-1, 1):
        wheel(m, side * (half + 0.006), y, r, wood, spokes=12, hub_r=0.012)
    zc = r / rig.RISE
    with m.keep():
        m.rod((-half - 0.006, y, zc), (half + 0.006, y, zc), 0.005, wood, segments=6)
    m.box(-half, half, y - 0.045, y + 0.045, zc - 0.05, zc + 0.02, wood, bevel=0.003)
    for side in (-1, 1):
        m.box(side * half - 0.003, side * half + 0.003, y - 0.045, y + 0.045, zc + 0.02, zc + 0.25, wood)
    m.box(-half + 0.006, half - 0.006, y - 0.04, y + 0.04, zc + 0.02, zc + 0.2, load, bevel=0.006)
    # The shafts run forward either side of the ox.
    for side in (-1, 1):
        m.rod((side * 0.034, y - 0.04, zc - 0.02), (side * 0.034, y + 0.12, 0.55), 0.0035, wood)
    horse(m, 0.0, y + 0.05, "#8a6a4a", ox=True)


def horsecar(m, L):
    """A horsecar: a small single-truck car with open platforms at each end, drawn along the tram rails by one horse."""
    a0, a1 = -L / 2 + 0.005, L / 2 - 0.005
    body = material("horsecar", "#8a2a22", rough=0.4, clearcoat=0.3)
    trim = material("horsecar trim", "#d8ccb0", rough=0.5)
    glass = material("glass", "#1e2a36", rough=0.1)
    dark = material("chassis", "#222224", rough=0.7)
    b0, b1 = a0 + 0.005, a0 + 0.27
    p0, p1 = b0 + 0.035, b1 - 0.035
    half = 0.068
    floor, top = 0.32, 1.05
    m.box(-half, half, p0, p1, floor, top, body, bevel=0.004)
    for y0, y1 in ((b0, p0), (p1, b1)):
        m.box(-half + 0.008, half - 0.008, y0, y1, floor, floor + 0.07, dark)
        m.box(-half + 0.008, half - 0.008, y0, y1, top - 0.04, top, dark)
    m.box(-0.045, 0.045, p0 + 0.02, p1 - 0.02, top, top + 0.08, trim, bevel=0.004)
    for side in (-1, 1):
        for k in range(5):
            ya = p0 + 0.012 + k * 0.04
            m.box(side * half - 0.001, side * half + 0.001, ya, ya + 0.03, floor + 0.4, top - 0.12, glass)
    zc = 0.02 / rig.RISE
    m.box(-0.05, 0.05, p0 + 0.02, p1 - 0.02, zc, floor, dark)
    for y in (p0 + 0.04, p1 - 0.04):
        for side in (-1, 1):
            with m.keep():
                m.tube_x(side * 0.058 - 0.005, side * 0.058 + 0.005, y, zc, 0.02, dark)
    driver(m, b1 - 0.015, floor + 0.07)
    pole(m, b1, b1 + 0.02, 0.38, dark)
    horse(m, 0.0, b1 + 0.02, COATS[0])
    m.text(LETTERS, half + 0.002, (p0 + p1) / 2, floor + 0.2, 0.02, trim, 1)
