"""Battery and hydrogen trains, in our own colours.

Built facing +y, the front at +L/2, in tiles across and along, steps up, as diesel.py's are. Railcars carry their
own passengers and have a cab at each end; freight engines are road switchers with a safety cab, or from the 2060s a
full-width body with a raked nose. Battery ones carry their cells in long boxes on the roof or behind the hood doors
and a folded pantograph to charge under wires; hydrogen ones carry their tanks in a rounded casing on the roof.
"""
import parts
import rig
from parts import material
from steam import LETTERS
import diesel
from diesel import alto_paint, frame, lamp, letters, livery, louvres, pantograph, roof_fans, truck

# Each railcar's colours: body, the band along the sill and round the doors, the line over it, the roof and the
# window mask.
RAILCAR = {
    ("battery", 1): ("#e9ecea", "#1f8a72", "#9ad8b8", "#8a9094", "#121618"),
    ("hydrogen", 1): ("#e9ecea", "#2a58a8", "#8ab8e8", "#8a9094", "#121618"),
    ("battery", 2): ("#1f4a44", "#e9ecea", "#9ad86a", "#3a4448", "#0c1012"),
    ("hydrogen", 2): ("#1e3460", "#e9ecea", "#6ad0e8", "#3a4048", "#0c1012"),
}
# Half a cross-section from the middle of the floor round to the middle of the roof, (across, up) each 0 to 1:
# sides near upright, tucked in at the foot, a well rounded roof.
RING = [(0.0, 0.0), (0.6, 0.0), (0.9, 0.01), (0.97, 0.05), (1.0, 0.14), (1.0, 0.3), (1.0, 0.5), (0.995, 0.62), (0.98, 0.72),
        (0.95, 0.8), (0.9, 0.87), (0.8, 0.93), (0.62, 0.975), (0.35, 0.995), (0.0, 1.0)]
FLOOR, TOP, HALF = 0.5, 1.74, 0.12


def ring(half, z0, z1):
    right = [(u * half, z0 + v * (z1 - z0)) for u, v in RING]
    left = [(-u * half, z0 + v * (z1 - z0)) for u, v in reversed(RING[1:-1])]
    return right + left


def railcar(m, L, kind, gen):
    """A self-propelled regional railcar with a cab at each end: a rounded body, each nose falling from the roof to a
    blunt snout round a black glass mask, a row of windows and two pairs of plug doors each side. Battery cars carry
    their cells in boxes on the roof and a folded pantograph; hydrogen cars a long rounded tank casing. The 2060s cars
    have longer, lower noses for their speed."""
    body_c, band_c, line_c, roof_c, mask_c = RAILCAR[(kind, gen)]
    c = livery("silver")
    mats = {
        "body": material(f"{kind}{gen} railcar body", body_c, rough=0.4, clearcoat=0.3),
        "band": material(f"{kind}{gen} railcar band", band_c, rough=0.4, clearcoat=0.3),
        "line": material(f"{kind}{gen} railcar line", line_c, rough=0.4),
        "roof": material(f"{kind}{gen} railcar roof", roof_c, rough=0.6),
        "mask": material(f"{kind}{gen} railcar mask", mask_c, rough=0.3, clearcoat=0.3),
        "skirt": material("railcar skirt", "#22262a", rough=0.7),
    }
    a0, a1 = -L / 2 + 0.006, L / 2 - 0.006
    z0, z1, half = FLOOR, TOP, HALF
    nose = 0.15 if gen == 1 else 0.21
    tip_z = 0.72 if gen == 1 else 0.6

    def section(t):
        """(half, floor, roof) at t along a nose, 0 where the roof starts to fall, 1 at the tip."""
        roof = tip_z + (z1 - tip_z) * (1 - t ** (1.5 if gen == 1 else 1.3)) ** (1 / 1.4)
        floor = z0 - 0.04 * t * t
        w = half * (1 - t ** 3.5) ** (1 / 3.0)
        return w, floor, max(roof, floor + 0.02)

    ts = [k / 40 for k in range(39)] + [0.97, 0.985]
    secs = []
    for t in reversed(ts):
        w, f, r = section(t)
        secs.append((a0 + nose * (1 - t), ring(w, f, r)))
    for t in ts:
        w, f, r = section(t)
        secs.append((a1 - nose * (1 - t), ring(w, f, r)))
    body = m.loft(secs, {"body": mats["body"]}, sharp=50)

    def colour(y, x, z):
        d = min(y - a0, a1 - y)
        t = max(0.0, 1 - d / nose)
        w, f, r = section(min(t, 0.985))
        up = (z - f) / max(r - f, 1e-6)
        if up < 0.08:
            return "skirt"
        # The black mask round the windshield and the cab side windows, over the body colour of the snout.
        if t > 0.08 and 0.5 - 0.12 * min(1.0, t / 0.5) < up < 0.93:
            return "mask"
        if up < 0.24:
            return "band"
        if up < 0.27 and t < 0.85:
            return "line"
        if up > 0.9 and t < 0.05:
            return "roof"
        return None

    alto_paint(m, body, mats, colour)
    # Headlights low in the mask at each corner.
    for end in (-1, 1):
        t = 0.86
        w, f, r = section(t)
        y = end * (L / 2 - 0.006 - nose * (1 - t))
        z = f + (r - f) * 0.3
        for side in (-1, 1):
            with m.keep(at=z):
                m.upright(side * w * 0.62, y, z - 0.015, z + 0.015, 0.007, 0.007, c["lamp"], segments=10)
        # A coupler cover low on the snout.
        e = a1 if end > 0 else a0
        m.box(-0.03, 0.03, *sorted((e - end * 0.02, e + end * 0.004)), 0.44, 0.56, mats["skirt"], bevel=0.004)
    for y in (a0 + 0.12, a1 - 0.12):
        truck(m, y, c)
    # Underfloor: the converters, and for battery cars more cells, behind dark skirts.
    m.box(-0.085, 0.085, a0 + 0.22, a1 - 0.22, 0.36, z0 + 0.02, mats["skirt"])
    for side in (-1, 1):
        x = side * half * 0.92
        m.box(x - 0.003, x + 0.003, a0 + 0.24, a1 - 0.24, 0.4, z0 + 0.04, mats["skirt"], bevel=0.002)
    # Two pairs of plug doors each side in the band colour, a row of windows between and at the ends.
    span = a1 - a0 - 2 * nose
    doors = [a0 + nose + span * 0.22, a1 - nose - span * 0.22]
    dw = 0.05
    for side in (-1, 1):
        x = side * (half + 0.0008)
        for d in doors:
            m.box(x - 0.0008, x + 0.0008, d - dw / 2, d + dw / 2, 0.58, 1.4, mats["band"])
            m.box(x - 0.001, x + 0.001, d - 0.0012, d + 0.0012, 0.58, 1.4, mats["skirt"])
            for dy in (-0.012, 0.012):
                m.box(x - 0.0012, x + 0.0012, d + dy - 0.007, d + dy + 0.007, 0.98, 1.32, mats["mask"])
        runs = [(a0 + nose + 0.01, doors[0] - dw / 2 - 0.012), (doors[0] + dw / 2 + 0.012, doors[1] - dw / 2 - 0.012),
                (doors[1] + dw / 2 + 0.012, a1 - nose - 0.01)]
        for y0, y1 in runs:
            n = max(1, round((y1 - y0) / 0.07))
            for i in range(n):
                p = y0 + (y1 - y0) * i / n
                m.box(x - 0.0009, x + 0.0009, p + 0.004, p + (y1 - y0) / n - 0.004, 1.0, 1.36, mats["mask"])
        m.text(LETTERS, side * (half + 0.0014), 0.0, 0.76, 0.026, mats["line"] if gen == 2 else mats["band"], side)
    if kind == "battery":
        # The cells in two long boxes along the roof, a folded pantograph between them to charge under wires.
        for y0, y1 in ((a0 + nose + 0.02, -0.07), (0.07, a1 - nose - 0.02)):
            m.shell([(y0, z1 - 0.04, 0.006, "paint"), (y1, z1 - 0.04, 0.006, "paint"), (y1 - 0.01, z1 + 0.1, 0.012, "paint"),
                     (y0 + 0.01, z1 + 0.1, 0.012, "paint")], 0.075, {"paint": mats["roof"]}, crown=0.02)
            for k in range(1, 4):
                yy = y0 + (y1 - y0) * k / 4
                m.box(-0.0755, 0.0755, yy - 0.0015, yy + 0.0015, z1 - 0.02, z1 + 0.09, mats["skirt"])
        m.box(-0.05, 0.05, -0.06, 0.06, z1 - 0.03, z1 + 0.02, c["dark"])
        pantograph(m, 0.02, z1 + 0.01, c, down=True)
    else:
        # The tanks under a long rounded casing the body colour, the fuel cell's cooler grille at each end of it.
        y0, y1 = a0 + nose + 0.03, a1 - nose - 0.03
        m.shell([(y0, z1 - 0.05, 0.01, "paint"), (y1, z1 - 0.05, 0.01, "paint"), (y1 - 0.03, z1 + 0.2, 0.05, "paint"),
                 (y0 + 0.03, z1 + 0.2, 0.05, "paint")], 0.085, {"paint": mats["roof"]}, crown=0.08)
        for yy in (y0 + 0.04, y1 - 0.12):
            m.box(-0.06, 0.06, yy, yy + 0.08, z1 + 0.19, z1 + 0.215, mats["skirt"], bevel=0.003)
        for side in (-1, 1):
            m.box(side * 0.0858 - 0.0008, side * 0.0858 + 0.0008, y0 + 0.06, y1 - 0.06, z1 + 0.04, z1 + 0.08, mats["band"] if gen == 1 else mats["line"])


# Freight engines' colours: (body, stripe, roof, trim, lower, cab), as diesel.livery takes them.
FREIGHT = {
    "battery": ("#30393e", "#46b98a", "#2a2c2e", "#46b98a", "#2f7f66", "#2f7f66"),
    "hydrogen": ("#2a4f96", "#e4e8ea", "#3a4048", "#e4e8ea", "#e4e8ea", "#2a4f96"),
}


def _livery(kind):
    body, stripe, roof, trim, lower, cab = FREIGHT[kind]
    c = livery("red")
    c.update({
        "body": material(f"{kind} freight body", body, rough=0.4, clearcoat=0.4),
        "stripe": material(f"{kind} freight stripe", stripe, rough=0.4, clearcoat=0.3),
        "roof": material(f"{kind} freight roof", roof, rough=0.6),
        "trim": material(f"{kind} freight trim", trim, rough=0.4),
        "lower": material(f"{kind} freight lower", lower, rough=0.4, clearcoat=0.4),
        "cab": material(f"{kind} freight cab", cab, rough=0.4, clearcoat=0.4),
        "louvre": material(f"{kind} freight louvre", diesel._shade(body, 0.6), metal=0.3, rough=0.6),
    })
    return c


def freight(m, L, kind, gen):
    """A six-axle freight engine. The 2030s ones are road switchers: a long hood behind a full-width safety cab, its
    nose sloping up to the windshield, walkways and handrails. Battery: the hood full of cell cabinets behind plain
    doors, small cooling fans, no stack. Hydrogen: a lower hood with the tanks lying in a rounded casing over it and the
    fuel cells' radiator wings at the back. The 2060s ones have a full-width body with skirts and a raked cab, the
    battery's sides in panels, the hydrogen's tanks under a raised roof."""
    c = _livery(kind)
    if gen == 2:
        return _freight_body(m, L, kind, c)
    deck = 0.62
    frame(m, L, c, 3, deck, ge=True)
    a0, a1 = -L / 2 + 0.03, L / 2 - 0.03
    short = 0.1
    cab1 = a1 - short
    cab0 = cab1 - 0.1
    hh = 0.076
    ht = 1.66 if kind == "battery" else 1.4
    mats = {"paint": c["body"], "glass": c["glass"], "roof": c["roof"], "louvre": c["louvre"]}
    diesel.carbody(m, [(cab0 + 0.005, deck, 0, "paint"), (a0 + 0.03, deck, 0, "paint"), (a0 + 0.01, deck, 0, "paint", 0.92),
                       (a0, deck, 0.004, "paint", 0.84), (a0, ht, 0.03, "paint", 0.84), (a0 + 0.01, ht, 0, "paint", 0.92),
                       (a0 + 0.03, ht, 0, "paint"), (cab0 + 0.005, ht, 0, "paint")], hh, mats, crown=0.05)
    door = 0.06
    n_doors = int((cab0 - a0 - 0.06) / door)
    for side in (-1, 1):
        x = side * (hh + 0.0012)
        for k in range(n_doors + 1):
            y = a0 + 0.03 + k * door
            m.box(x - 0.0005, x + 0.0005, y, y + 0.003, deck + 0.06, ht - 0.05, c["grille"])
        # A stripe along the hood, the cab's colour.
        m.box(x - 0.0006, x + 0.0006, a0 + 0.02, cab0, ht - 0.2, ht - 0.15, c["stripe"])
    if kind == "battery":
        # Cell cabinets: a small vent at the top of every other door, two cooling fans at the back of the roof.
        for side in (-1, 1):
            x = side * (hh + 0.0014)
            for k in range(0, n_doors, 2):
                y = a0 + 0.04 + k * door
                louvres(m, x, y, y + door - 0.02, ht - 0.16, ht - 0.07, c)
        roof_fans(m, a0 + 0.03, a0 + 0.2, ht, c, n=2, w=0.07)
    else:
        # The radiator wings at the back, then the tanks under a rounded casing the length of the hood.
        rad = 0.2
        lo, out, knee, top = ht - 0.3, 0.112, ht + 0.12, ht + 0.22
        ring_ = [(-out, top), (-out, knee), (-hh, lo), (hh, lo), (out, knee), (out, top)]
        front = [(-hh * 0.98, ht - 0.01), (-hh * 0.98, ht - 0.04), (-hh * 0.98, lo), (hh * 0.98, lo), (hh * 0.98, ht - 0.04), (hh * 0.98, ht - 0.01)]
        m.loft([(a0 - 0.004, ring_), (a0 + rad - 0.05, ring_), (a0 + rad, front)], dict(mats, louvre=c["grille"]),
               part=lambda k: "louvre" if k in (1, 3) else "paint")
        roof_fans(m, a0 + 0.01, a0 + rad - 0.06, top, c, n=2, w=0.08)
        # Two tanks lying side by side on cradles along the hood, banded, with their valves at the front.
        y0, y1 = a0 + rad + 0.02, cab0 - 0.03
        tank = material("hydrogen tank", "#e2e5e8", metal=0.3, rough=0.35)
        r = 0.036
        zt = ht + r / rig.RISE
        for x in (-0.04, 0.04):
            m.tube(y0, y1, x, zt, r, tank, segments=20)
            for y in (y0 + 0.004, y1 - 0.004):
                m.upright(x, y, zt - 0.1, zt + 0.1, r * 0.55, r * 0.55, tank, segments=12)
            for k in range(1, 4):
                y = y0 + (y1 - y0) * k / 4
                m.tube(y - 0.006, y + 0.006, x, zt, r * 1.04, c["stripe"], segments=20)
        for k in range(5):
            y = y0 + (y1 - y0) * k / 4
            m.box(-0.085, 0.085, y - 0.004, y + 0.004, ht - 0.01, ht + 0.1, c["dark"])
        m.box(-0.02, 0.02, y1 + 0.004, y1 + 0.016, ht, ht + 0.22, c["steel"])
    mats_cab = dict(mats, paint=c["cab"])
    diesel._safety_cab(m, cab0, cab1, a1, deck, 1.97, "ge", mats_cab, c)
    diesel.handrails(m, a0, cab0, deck, c)
    letters(m, L, c, (ht + deck) / 2 + 0.06, at=(a0 + cab0) / 2 + 0.04, size=0.032, x=hh + 0.0014)


def _freight_body(m, L, kind, c):
    deck, top, half = 0.62, 1.9, 0.12
    frame(m, L, c, 3, deck, ge=True)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.01
    mats = {"paint": c["body"], "glass": c["glass"], "lower": c["lower"], "roof": c["roof"]}
    # A raked nose: a rounded lower nose, the windshield laid well back, the roof rounding down to it.
    shape = [(a0, deck - 0.1, 0.004, "paint"), (a1 - 0.17, deck - 0.1, 0, "paint"), (a1 - 0.03, deck - 0.1, 0.01, "lower", 0.9),
             (a1, 0.8, 0.03, "lower", 0.86), (a1 - 0.01, 1.08, 0.03, "paint", 0.9), (a1 - 0.04, 1.2, 0.03, "glass", 0.94),
             (a1 - 0.14, 1.82, 0.05, "paint", 0.98), (a1 - 0.19, top, 0.04, "roof"), (a0, top, 0.01, "paint")]
    outline = diesel.carbody(m, shape, half, mats, crown=0.16)
    nose = a1 - 0.19
    for side in (-1, 1):
        x = side * (half - 0.002)
        m.box(x - 0.002, x + 0.002, a0 + 0.24, a1 - 0.28, 0.42, deck, c["body"], bevel=0.003)
    diesel.side_band(m, a0 + 0.004, nose - 0.02, 0.62, 0.86, c["lower"])
    diesel.side_band(m, a0 + 0.004, nose - 0.02, 0.86, 0.9, c["stripe"] if kind == "battery" else c["dark"])
    for side in (-1, 1):
        x = side * (half + 0.0008)
        m.box(x - 0.0008, x + 0.0008, nose - 0.045, nose + 0.01, 1.45, 1.68, c["glass"])
        m.box(x - 0.0006, x + 0.0006, nose - 0.075, nose - 0.055, 0.82, 1.7, c["louvre"])
        if kind == "battery":
            # Cabinet panels down the side, each with a small vent at its top.
            n = 7
            p0, p1 = a0 + 0.03, nose - 0.1
            for k in range(n + 1):
                y = p0 + (p1 - p0) * k / n
                m.box(x - 0.0005, x + 0.0005, y, y + 0.003, 0.8, 1.76, c["grille"])
                if k < n and k % 2 == 0:
                    louvres(m, side * (half + 0.0014), y + 0.012, y + (p1 - p0) / n - 0.012, 1.56, 1.7, c)
        else:
            louvres(m, side * (half + 0.0014), a0 + 0.04, a0 + 0.24, 1.4, 1.72, c)
    m.wedge([(-0.1, a1 - 0.07, 0.42), (0.1, a1 - 0.07, 0.42), (0.07, a1 - 0.005, 0.42), (-0.07, a1 - 0.005, 0.42)],
            [(-0.1, a1 - 0.07, 0.55), (0.1, a1 - 0.07, 0.55), (0.08, a1 - 0.02, 0.55), (-0.08, a1 - 0.02, 0.55)], c["dark"])
    for x in (-0.07, 0.07):
        lamp(m, outline, 0.9, c, x=x, r=0.008)
    for x in (-0.02, 0.02):
        lamp(m, outline, 1.14, c, x=x, r=0.006)
    if kind == "battery":
        roof_fans(m, a0 + 0.04, a0 + 0.24, top, c, n=2, w=0.06)
        m.box(-0.05, 0.05, a0 + 0.3, a0 + 0.38, top - 0.02, top + 0.03, c["dark"])
        pantograph(m, a0 + 0.36, top + 0.01, c, down=True)
    else:
        # The tanks in a raised roof the body colour, rounded at each end, the fuel cells' fans behind it.
        y0, y1 = a0 + 0.22, nose - 0.03
        m.shell([(y0, top - 0.04, 0.01, "paint"), (y1, top - 0.04, 0.01, "paint"), (y1 - 0.04, top + 0.24, 0.06, "paint"),
                 (y0 + 0.03, top + 0.24, 0.06, "paint")], 0.1, {"paint": c["body"]}, crown=0.14)
        for side in (-1, 1):
            m.box(side * 0.1 - 0.0008, side * 0.1 + 0.0008, y0 + 0.03, y1 - 0.05, top + 0.02, top + 0.07, c["stripe"])
        roof_fans(m, a0 + 0.03, a0 + 0.2, top, c, n=2, w=0.065)
    letters(m, L, c, 1.24, at=(a0 + nose) / 2, size=0.036)
    return outline
