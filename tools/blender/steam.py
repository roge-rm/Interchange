"""Steam engines and their tenders, after those that ran in Canada, in our own colours.

Built facing +y, the front at +L/2, in tiles across and along, steps up.
"""
import math

import parts
from parts import material

LETTERS = "I.R."
# How far the skyline casing stands over the boiler top, in steps.
SKYLINE = 0.1


# Two-tone liveries over the black scheme: the boiler casing, the smokebox front, the cab and tender, the band along
# the running boards and the tender's foot, and the lining on the band's edge.
LIVERIES = {
    # CP's 1930s semi-streamlined engines, black with a graphite front and tuscan red cab, tender and skirts.
    "cp_tuscan": {"jacket": "#25272b", "front": "#4a4d52", "cab": "#6e2a1c", "roof": "#3c1a14", "band": "#6e2a1c", "line": "#d8b450"},
    # Maroon cab and tender, a grey casing and grey bands.
    "cp_grey": {"jacket": "#5a5f66", "front": "#34363a", "cab": "#5a1c1e", "roof": "#341618", "band": "#7e848a", "line": "#d8b450"},
    # CN green over black.
    "cn_green": {"jacket": "#2c4a34", "front": "#262626", "cab": "#2c4a34", "roof": "#1c2c20", "band": "#1e1f22", "line": "#e6dcc0"},
}


def colours(scheme, livery=None):
    c = _colours(scheme)
    if livery:
        lv = LIVERIES[livery]
        c["boiler"] = material(f"{livery} casing", lv["jacket"], rough=0.35, clearcoat=0.5)
        c["cab"] = c["tender"] = material(f"{livery} body", lv["cab"], rough=0.38, clearcoat=0.5)
        c["roof"] = material(f"{livery} roof", lv["roof"], rough=0.6)
        c["front"] = material(f"{livery} front", lv["front"], metal=0.2, rough=0.4, clearcoat=0.3)
        c["band"] = material(f"{livery} band", lv["band"], rough=0.38, clearcoat=0.5)
        c["line"] = c["letters"] = material(f"{livery} lining", lv["line"], metal=0.6, rough=0.35)
    return c


def _colours(scheme):
    if scheme == "victorian":
        return {
            "boiler": material("russia iron", "#4a5a68", metal=0.6, rough=0.35),
            "cab": material("varnished wood", "#7a3a1e", rough=0.4, clearcoat=0.6),
            "paint": material("engine red", "#8a2a22", rough=0.45, clearcoat=0.4),
            "wheel": material("wheel red", "#9a2e24", rough=0.5),
            "brass": material("brass", "#c9a040", metal=1.0, rough=0.25),
            "black": material("smokebox", "#262626", rough=0.6),
            "iron": material("iron", "#3a3a3c", metal=0.5, rough=0.5),
            "steel": material("steel", "#9a9a9a", metal=1.0, rough=0.3),
            "glass": material("glass", "#1e2a36", rough=0.1),
            "lamp": material("lamp glass", "#f0e6b8", rough=0.2),
            "roof": material("cab roof", "#2c2622", rough=0.8),
            "letters": material("gold leaf", "#d8b450", metal=1.0, rough=0.3),
        }
    return {
        "boiler": material("engine black", "#2a2c30", rough=0.35, clearcoat=0.5),
        "cab": material("engine black", "#2a2c30", rough=0.35, clearcoat=0.5),
        "paint": material("engine black", "#2a2c30", rough=0.35, clearcoat=0.5),
        "wheel": material("wheel black", "#202022", rough=0.5),
        "brass": material("brass", "#c9a040", metal=1.0, rough=0.25),
        "black": material("smokebox", "#262626", rough=0.6),
        "iron": material("iron", "#3a3a3c", metal=0.5, rough=0.5),
        "steel": material("steel", "#9a9a9a", metal=1.0, rough=0.3),
        "glass": material("cab glass", "#56687a", metal=0.3, rough=0.1),
        "lamp": material("lamp glass", "#f0e6b8", rough=0.2),
        "roof": material("cab roof", "#1a1a1a", rough=0.8),
        "letters": material("cream letters", "#e6dcc0", rough=0.5),
    }


def engine(m, L, lead, drive, trail, r_drive, r_lead, boiler_r, stack, domes, scheme, cab="steel", cab_len=0.13,
           pilot="cast", headlight="top", feedwater=False, pumps=0, cylinders="outside", lagged=False, stack_top=None,
           drive_set=0.0, return_flue=False, deflectors=False, shroud=False, livery=None):
    """A steam engine L tiles long, nose at +y.

    Heights here are worked out as drawn, in steps over the rail once the model is lowered, and handed to m through
    m.fixed, so the boiler sits just over the drivers and the cab roof just over the boiler whatever the model's height
    factor. boiler_r is the boiler's radius as drawn, in tiles.

    cab: "none" (an open footplate, the 1830s kind), "wood" (Victorian, big windows), "steel", or "vestibule" (closed in
    to the tender, for winter). stack: "early" (tall and thin), "balloon", "diamond", "capped" (short, modern) or "hidden";
    stack_top is its top in steps. headlight: "box" (an oil lamp on the smokebox), "top" (over the smokebox door),
    "centre" (on the door) or None. pilot: "slatted" (a cowcatcher), "footboard", "cast" or None. feedwater puts a heater
    drum across the top of the smokebox, pumps hangs one or two air pumps on the boiler's sides. cylinders: "outside",
    "inside" (none showing) or "vertical" (standing over the front driver, as the Samson's). return_flue puts the chimney
    at the back by the fire door, where a Hackworth engine has it. lagged gives the boiler wooden staves held by iron
    bands. drive_set moves the drivers back from the front by that many tiles. deflectors stands a smoke deflector
    plate each side of the smokebox. shroud is the semi-streamlined kind: a skyline casing along the boiler top over the
    chimney and domes, curving down onto a rounded smokebox front, with skirts under the running boards. livery is one
    of LIVERIES, painting the casing, cab and bands over the scheme."""
    c = colours(scheme, livery)
    rise = parts.rig.RISE
    fz = m.fixed
    early = cab == "none"
    back = -L / 2 + 0.01
    front = L / 2 - (0.07 if pilot else 0.02)
    cab_front = back + cab_len
    # Heights as drawn: the drivers' tops, the deck (footplate or running boards) just over them, the boiler on it.
    axle = r_drive / rise
    wtop = 2 * axle
    deck_f = wtop + (0.07 if early else 0.06)
    rs = boiler_r / rise
    bz_f = deck_f + (rs * (0.6 if return_flue else 0.8) if early else rs + 0.02)
    top_f = bz_f + rs
    deck, bz, top = fz(deck_f), fz(bz_f), fz(top_f)
    br = boiler_r / m.k(bz)

    # Frame and running boards.
    m.box(-0.08, 0.08, back, front, fz(axle), deck, c["iron"])
    for side in (-1, 1):
        m.box(side * 0.105, side * 0.13, back + (0 if early else cab_len), front - 0.02, fz(deck_f - 0.035), deck, c["iron"], bevel=0.002)
    if early:
        # A deep frame plate down each side to just over the wheels, and beams across each end.
        for side in (-1, 1):
            m.box(side * 0.114, side * 0.13, back, front, fz(deck_f - 0.06), fz(deck_f - 0.01), c["paint"], bevel=0.002)
        for y0, y1 in ((front - 0.012, front), (back, back + 0.012)):
            m.box(-0.135, 0.135, y0, y1, fz(deck_f - 0.1), deck, c["paint"], bevel=0.002)

    # Wheels: leading under the front, drivers in the middle, trailing under the cab.
    lead_pos = [front - 0.05 - i * r_lead * 2.5 for i in range(lead)]
    d0 = (lead_pos[-1] - r_lead - r_drive - 0.03) if lead else front - r_drive - 0.02 - drive_set
    drive_pos = [d0 - i * r_drive * 2.2 for i in range(drive)]
    trail_pos = [drive_pos[-1] - r_drive - 0.03 - r_lead - i * r_lead * 2.4 for i in range(trail)]
    wmats = (c["steel"], c["wheel"], c["steel"])
    crank_z = axle - r_drive * 0.45 / rise
    for side in (-1, 1):
        for y in lead_pos + trail_pos:
            m.wheel(y, side, r_lead, wmats, spokes=10)
        for y in drive_pos:
            m.wheel(y, side, r_drive, wmats, spokes=14, counterweight=True)
        # The running gear keeps its size with the wheels: cylinder, crosshead guide, main rod and side rods.
        with m.keep():
            zcy = axle + 0.06
            cy0 = drive_pos[0] + r_drive + 0.03
            if cylinders == "outside":
                m.tube(cy0, cy0 + 0.09, side * 0.116, zcy, 0.03, c["paint"])
                for y in (cy0, cy0 + 0.09):
                    m.tube(y - 0.004, y + 0.004, side * 0.116, zcy, 0.032, c["steel"], cap=False)
                m.box(side * 0.112, side * 0.118, drive_pos[0] + r_drive * 0.4, cy0, zcy - 0.03, zcy + 0.03, c["steel"])
                m.rod((side * 0.121, cy0 - 0.01, zcy), (side * 0.121, drive_pos[-1 if drive < 3 else 1], crank_z), 0.006, c["steel"])
            if drive > 1:
                m.rod((side * 0.124, drive_pos[0], crank_z), (side * 0.124, drive_pos[-1], crank_z), 0.006, c["steel"])

    # Boiler, from the cab or firebox to the smokebox; the open-footplate kind has a firebox drum standing higher.
    nose = front - 0.05 if cylinders == "vertical" else front
    smoke = 0 if return_flue else 0.05 + boiler_r * 0.5
    if early:
        fire_len = 0.07
        fr_f = boiler_r * 1.18
        fz_f = deck_f + fr_f / rise * (0.75 if return_flue else 0.9)
        fzb, frb = fz(fz_f), fr_f / m.k(fz(fz_f))
        m.tube(cab_front, cab_front + fire_len, 0, fzb, frb, c["black"])
        m.tube(cab_front - 0.002, cab_front + 0.001, 0, fz(fz_f - fr_f / rise * 0.4), frb * 0.35, c["iron"])
        for y in (cab_front + 0.004, cab_front + fire_len - 0.004):
            m.tube(y - 0.003, y + 0.003, 0, fzb, frb * 1.02, c["iron"], cap=False)
        b0 = cab_front + fire_len
        fire_top = fz(fz_f + fr_f / rise)
    else:
        b0 = cab_front
        if trail:
            # A wide firebox over the trailing truck, below the boiler's back end.
            m.box(-boiler_r * 0.95, boiler_r * 0.95, cab_front, cab_front + 0.05 + 0.02 * trail, fz(deck_f - 0.05), bz, c["boiler"], bevel=0.006)
    if shroud:
        casing(m, c, b0, nose, smoke, boiler_r, bz_f, top_f)
    elif lagged:
        # Wooden staves, left faceted so each shows, held by iron bands.
        wood = material("boiler lagging", "#8a6a48", rough=0.75)
        staves = m.tube(b0, nose - smoke, 0, bz, br, wood, segments=22)
        staves.data.polygons.foreach_set("use_smooth", [False] * len(staves.data.polygons))
        bands, band_mat = 5, c["iron"]
    else:
        m.tube(b0, nose - smoke, 0, bz, br, c["boiler"])
        bands, band_mat = 4, (c["brass"] if scheme == "victorian" else c["iron"])
    for k in range(1, 0 if shroud else bands):
        y = b0 + (nose - smoke - b0) * k / bands
        m.tube(y - 0.004, y + 0.004, 0, bz, br * 1.03, band_mat, cap=False)
    if return_flue:
        # The far end of the barrel is closed, in an iron casing the vertical cylinders stand beside.
        m.box(-boiler_r * 0.9, boiler_r * 0.9, nose - 0.035, nose, deck, fz(top_f - 0.02), c["iron"], bevel=0.004)
    else:
        if not shroud:
            m.tube(nose - smoke, nose, 0, bz, br * 1.05, c["black"])
            m.tube(nose - 0.002, nose + 0.004, 0, bz, br * 0.86, c["iron"])
        # The saddle under the smokebox, down to the cylinders.
        m.box(-boiler_r * 0.7, boiler_r * 0.7, nose - smoke * 0.85, nose - smoke * 0.1, fz(axle + 0.04), bz, c["black"])
    if not early:
        # Handrails along the boiler.
        for side in (-1, 1):
            x = side * (boiler_r * 0.72 + 0.012)
            m.rod((x, cab_front, fz(bz_f + rs * 0.7)), (x, nose - smoke, fz(bz_f + rs * 0.7)), 0.003, c["brass"] if scheme == "victorian" else c["steel"])

    # Chimney.
    sy = b0 + 0.025 if return_flue else nose - smoke * (0.62 if feedwater else 0.5)
    if shroud:
        # Only the chimney's mouth shows, a slot in the casing top.
        sky = fz(top_f + SKYLINE)
        m.box(-0.016, 0.016, sy - 0.028, sy + 0.028, sky - 0.02, sky + 0.006, c["black"], bevel=0.004)
    elif stack != "hidden":
        foot = (fire_top if return_flue else top) - 0.08
        if stack_top is None:
            stack_top = {"early": 1.3, "balloon": 1.42, "diamond": 1.42}.get(stack, top_f + 0.14)
        h = fz(stack_top) - foot
        prof = {
            "early": [(0.016, 0), (0.015, 0.88), (0.021, 0.95), (0.022, 0.98), (0.018, 1.0)],
            "balloon": [(0.02, 0), (0.022, 0.38), (0.045, 0.64), (0.056, 0.87), (0.052, 0.97), (0.028, 1.0)],
            "diamond": [(0.02, 0), (0.022, 0.46), (0.05, 0.74), (0.018, 1.0)],
            "capped": [(0.038, 0), (0.03, 0.3), (0.027, 0.45), (0.027, 0.82), (0.033, 0.88), (0.033, 1.0), (0.024, 1.0)],
        }[stack]
        m.lathe(0, sy, foot, [(r, f * h) for r, f in prof], c["black"])
    if feedwater and not shroud:
        # An Elesco heater drum across the smokebox top, ahead of the chimney.
        fr = 0.028
        m.tube_x(-boiler_r * 0.85, boiler_r * 0.85, nose - smoke * 0.24, fz(top_f + fr / rise * 0.6), fr / m.h, c["black"])
        for side in (-1, 1):
            m.tube_x(side * boiler_r * 0.85, side * (boiler_r * 0.85 + 0.008), nose - smoke * 0.24, fz(top_f + fr / rise * 0.6), fr * 1.1 / m.h, c["iron"])
    if pumps:
        # Air pumps hung on the boiler's side over the running boards: on both sides by the smokebox, or one midway.
        spots = [(s, nose - smoke - 0.03) for s in (-1, 1)] if pumps > 1 else [(1, (b0 + nose - smoke) / 2)]
        for side, y in spots:
            x = side * (boiler_r + 0.022)
            for dy in (-0.013, 0.013):
                m.upright(x, y + dy, deck, fz(bz_f + rs * 0.25), 0.014, 0.014, c["iron"])
                m.upright(x, y + dy, fz(bz_f + rs * 0.25), fz(bz_f + rs * 0.35), 0.016, 0.012, c["steel"])

    # Domes, bell and whistle.
    for i in range(0 if shroud else domes):
        dy = b0 + (nose - smoke - b0) * (0.3 + 0.32 * i)
        if scheme == "victorian":
            m.lathe(0, dy, top - 0.15, [(0.036, 0), (0.034, 0.22), (0.028, 0.3), (0.016, 0.35), (0.0, 0.36)], c["brass"])
        else:
            m.lathe(0, dy, top - 0.12, [(0.042, 0), (0.04, 0.14), (0.033, 0.2), (0.018, 0.24), (0.0, 0.25)], c["boiler"])
    if early:
        # A brass dome on the firebox drum, the safety valve, and the open footplate's low rail.
        if not return_flue:
            m.lathe(0, cab_front + fire_len / 2, fire_top - 0.12, [(0.032, 0), (0.03, 0.2), (0.022, 0.3), (0.01, 0.34), (0.0, 0.35)], c["brass"])
        m.lathe(0.0, b0 + (0.07 if return_flue else 0.012), top - 0.05, [(0.004, 0), (0.004, 0.2), (0.006, 0.22), (0.006, 0.3), (0.0, 0.32)], c["brass"])
        rail = fz(deck_f + 0.3)
        ends = [(back + 0.006, cab_front - 0.006)]
        if cylinders == "vertical":
            ends.append((nose + 0.006, front - 0.006))
        for side in (-1, 1):
            x = side * 0.125
            for y0, y1 in ends:
                for y in (y0, y1):
                    m.rod((x, y, deck), (x, y, rail), 0.004, c["iron"])
                m.rod((x, y0, rail), (x, y1, rail), 0.004, c["iron"])
        m.rod((0.06, cab_front - 0.03, deck), (0.06, cab_front - 0.015, fz(deck_f + 0.34)), 0.004, c["steel"])
        if cylinders == "vertical":
            # A vertical cylinder each side over the front driver, standing above the boiler, its rod straight down to the crank.
            y = drive_pos[0]
            ctop = fz(top_f + 0.13)
            for side in (-1, 1):
                x = side * 0.092
                m.upright(x, y, deck, ctop - 0.04, 0.024, 0.024, c["iron"])
                m.upright(x, y, ctop - 0.04, ctop, 0.027, 0.027, c["steel"])
                m.upright(x, y, deck, fz(deck_f + 0.03), 0.028, 0.028, c["steel"])
                m.rod((side * 0.121, y, fz(deck_f + 0.05)), (side * 0.121, y, crank_z), 0.005, c["steel"])
                m.box(side * 0.121 - 0.006, side * 0.121 + 0.006, y - 0.012, y + 0.012, fz(deck_f - 0.02), fz(deck_f + 0.06), c["steel"])
    elif not shroud:
        m.lathe(0, cab_front + (nose - smoke - cab_front) * 0.5, top - 0.05, [(0.004, 0), (0.004, 0.1), (0.014, 0.12), (0.017, 0.22), (0.005, 0.25)], c["brass"])
        m.lathe(0.0, cab_front + 0.02, top - 0.05, [(0.004, 0), (0.004, 0.2), (0.006, 0.22), (0.006, 0.3), (0.0, 0.32)], c["brass"])

    # Headlight.
    if shroud and headlight:
        # Set into the rounded front over the smokebox door.
        hz = fz(bz_f + rs * 0.55)
        m.tube(nose - 0.02, nose + 0.012, 0, hz, 0.022 / m.h, c["front"])
        m.tube(nose + 0.012, nose + 0.015, 0, hz, 0.017 / m.h, c["lamp"])
    elif headlight == "box":
        m.box(-0.025, 0.025, nose - 0.045, nose, top - 0.02, fz(top_f + 0.2), c["cab"], bevel=0.003)
        m.box(-0.03, 0.03, nose - 0.05, nose + 0.005, fz(top_f + 0.2), fz(top_f + 0.24), c["black"], bevel=0.003)
        m.tube(nose - 0.002, nose + 0.004, 0, fz(top_f + 0.1), 0.016 / m.h, c["brass"])
    elif headlight == "top":
        hz = fz(top_f + 0.08)
        m.box(-0.014, 0.014, nose - 0.03, nose - 0.008, top - 0.05, hz, c["black"])
        m.tube(nose - 0.04, nose + 0.006, 0, hz, 0.026 / m.h, c["black"])
        m.tube(nose + 0.006, nose + 0.009, 0, hz, 0.02 / m.h, c["lamp"])
    elif headlight == "centre":
        m.tube(nose, nose + 0.035, 0, bz, 0.026 / m.h, c["black"])
        m.tube(nose + 0.035, nose + 0.038, 0, bz, 0.02 / m.h, c["lamp"])

    if deflectors:
        # A plate each side of the smokebox, from the running boards to the boiler top, its top front corner rounded.
        y0, y1 = nose - smoke - 0.035, nose + 0.012
        for side in (-1, 1):
            m.shell([(y0, deck, 0, "paint"), (y1, deck, 0, "paint"), (y1, fz(top_f - 0.1), 0.03, "paint"),
                     (y1 - 0.02, fz(top_f - 0.01), 0.02, "paint"), (y0, fz(top_f - 0.01), 0.005, "paint")],
                    0.002, {"paint": c["black"]}, x=side * 0.122)
            m.rod((side * 0.12, y0 + 0.02, fz(top_f - 0.08)), (side * boiler_r * 0.8, y0 + 0.02, fz(bz_f + rs * 0.5)), 0.003, c["iron"])
    band_top = deck_f + 0.06
    if shroud:
        # Skirts under the running boards from the cab to the pilot, sweeping down over the cylinders at the front.
        zt, zb = deck_f + 0.015, deck_f - 0.075
        sweep = nose - smoke - 0.01
        for side in (-1, 1):
            x = side * 0.133
            m.shell([(cab_front, fz(zb), 0, "paint"), (cab_front, fz(zt), 0, "paint"), (sweep, fz(zt), 0.04, "paint"),
                     (front - 0.008, fz(zt - 0.1), 0.02, "paint"), (front - 0.008, fz(zb - 0.08), 0, "paint"),
                     (sweep - 0.04, fz(zb), 0.03, "paint")], 0.003, {"paint": c["band"]}, x=x)
            lx = sorted((x + side * 0.0028, x + side * 0.0042))
            m.box(lx[0], lx[1], cab_front, sweep - 0.01, fz(zt - 0.03), fz(zt - 0.012), c["line"])
        band_top = zt
    if livery and not early:
        # The band carried on along the cab's foot, lined along its top.
        rear = back if cab == "vestibule" else back + 0.03
        for side in (-1, 1):
            x = side * 0.1225
            m.box(x - 0.0006, x + 0.0006, rear, cab_front, deck, fz(band_top), c["band"])
            m.box(x - 0.001, x + 0.001, rear, cab_front, fz(band_top - 0.03), fz(band_top - 0.012), c["line"])
        if not shroud:
            # Lining along the running boards' edge.
            for side in (-1, 1):
                m.box(side * 0.1295, side * 0.1315, cab_front, front - 0.02, fz(deck_f - 0.024), fz(deck_f - 0.01), c["line"])

    # The cab: walls just over the boiler, a wider arched roof overhanging them.
    if not early:
        roof_f = top_f + {"wood": 0.17, "steel": 0.09, "vestibule": 0.1}[cab]
        if livery:
            # A thinner roof sitting just over the boiler or casing top, so more of the painted cab side shows.
            roof_f = top_f + (SKYLINE if shroud else 0.0) + 0.02
        roof = fz(roof_f)
        wall = c["cab"]
        rear = back if cab == "vestibule" else back + 0.03
        m.box(-0.122, 0.122, rear, cab_front, deck, roof, wall, bevel=0.003)
        if cab != "vestibule":
            # The open gangway to the tender behind the cab sides.
            m.box(-0.11, 0.11, back, rear, deck, roof, c["black"])
        w, crown, lip = (0.132, 0.04, 0.015) if livery else (0.14, 0.07, 0.03)
        ring = [(w * math.cos(a), roof + crown * math.sin(a)) for a in [math.pi * k / 10 for k in range(11)]]
        ring += [(-w, roof - lip), (w, roof - lip)]
        m.loft([(back - 0.004, ring), (cab_front + 0.02, ring)], {"roof": c["roof"]})
        sill, head = fz(bz_f if cab == "wood" else top_f - 0.1), roof - 0.05
        if livery:
            sill, head = fz(roof_f - 0.17), fz(roof_f - 0.035)
        if cab == "wood":
            panes = [(cab_front - 0.055, cab_front - 0.012), (rear + 0.012, rear + 0.055)]
            if cab_len > 0.12:
                panes = [(cab_front - 0.05, cab_front - 0.01), ((rear + cab_front) / 2 - 0.02, (rear + cab_front) / 2 + 0.02), (rear + 0.01, rear + 0.05)]
        elif cab == "steel":
            panes = [(cab_front - 0.06, cab_front - 0.012)]
        else:
            panes = [(cab_front - 0.055, cab_front - 0.012), (cab_front - 0.11, cab_front - 0.07)]
        for side in (-1, 1):
            x = side * 0.1225
            for y0, y1 in panes:
                m.box(x - 0.0008, x + 0.0008, y0, y1, sill, head, c["glass"])
            if cab == "vestibule":
                # The side door into the enclosed back.
                m.box(x - 0.0006, x + 0.0006, back + 0.012, back + 0.05, deck + 0.02, head, c["black"])
            # Front windows either side of the boiler.
            m.box(side * 0.105, side * (boiler_r * 0.7), cab_front - 0.001, cab_front + 0.0015, fz(top_f - 0.06), head, c["glass"])
            m.text(LETTERS, side * 0.1232, (rear + cab_front) / 2, (deck + sill) / 2, 0.03, c["letters"], side)

    # Pilot.
    if pilot:
        beam = c["paint"] if scheme == "victorian" else c["iron"]
        m.box(-0.12, 0.12, front - 0.012, front + 0.004, fz(deck_f - 0.1), deck, beam, bevel=0.002)
        h0, h1 = 0.06, fz(deck_f - 0.1)
        if pilot == "slatted":
            # A cowcatcher of slats from the beam down to a point.
            tip = front + 0.065
            m.box(-0.115, 0.115, front - 0.004, front + 0.006, h1 - 0.06, h1, beam)
            for k in range(-5, 6):
                f = k / 5
                m.rod((f * 0.11, front, h1 - 0.03), (f * 0.025, tip - abs(f) * 0.055, h0 + 0.02), 0.004, beam)
            m.rod((-0.11, front + 0.004, h0 + 0.02), (0, tip, h0 + 0.02), 0.005, beam)
            m.rod((0.11, front + 0.004, h0 + 0.02), (0, tip, h0 + 0.02), 0.005, beam)
        elif pilot == "footboard":
            # A footboard each side of the coupler for the switchmen.
            for side in (-1, 1):
                m.box(side * 0.035, side * 0.12, front + 0.004, front + 0.03, h0 + 0.12, h0 + 0.16, c["iron"], bevel=0.002)
                for x in (0.045, 0.11):
                    m.box(side * x - 0.004, side * x + 0.004, front + 0.004, front + 0.012, h0 + 0.12, h1, c["iron"])
        elif shroud:
            # A cast pilot under the rounded front, raked back from a tapered nose, the coupler showing past its tip.
            tip = front + 0.032
            m.shell([(front - 0.012, h1, 0, "paint", 1.0), (front + 0.006, h1, 0.012, "paint", 0.95),
                     (tip, h0 + 0.03, 0.02, "paint", 0.5), (tip - 0.008, h0, 0.006, "paint", 0.45), (front - 0.012, h0, 0, "paint", 0.9)],
                    0.115, {"paint": c["black"]}, cols=(-1, -0.8, -0.4, 0, 0.4, 0.8, 1))
            m.box(-0.02, 0.02, tip - 0.01, tip + 0.004, 0.42, 0.6, c["iron"], bevel=0.002)
        else:
            # A cast steel pilot, sloping back from its foot, with its bars showing.
            tip = front + 0.05
            m.wedge([(-0.1, front, h0), (0.1, front, h0), (0.05, tip, h0), (-0.05, tip, h0)],
                    [(-0.11, front + 0.004, h1), (0.11, front + 0.004, h1), (0.08, front + 0.01, h1), (-0.08, front + 0.01, h1)], c["black"])
            for k in range(1, 4):
                f = k / 4
                y, z = front + 0.01 + (tip - front - 0.01) * (1 - f), h0 + (h1 - h0) * f
                m.box(-0.1 + 0.03 * (1 - f), 0.1 - 0.03 * (1 - f), y - 0.002, y + 0.004, z - 0.012, z, c["iron"])


def casing(m, c, b0, nose, smoke, boiler_r, bz_f, top_f, n=72):
    """The semi-streamlined boiler: the barrel from b0 to nose with a flat-topped skyline casing along its top, the
    casing dipping onto the smokebox and the front rounding in to the door, painted the front colour over the smokebox.
    Heights in steps as they end up, as in engine()."""
    rise = parts.rig.RISE
    fz = m.fixed
    sky_full = top_f + SKYLINE
    w = boiler_r * 0.42
    dip0 = nose - smoke - 0.03
    round0 = nose - 0.04

    def ring(y):
        sky, r = sky_full, boiler_r
        if y > dip0:
            f = (y - dip0) / (nose - dip0)
            sky = sky_full - (sky_full - (top_f - 0.06)) * (1 - math.cos(f * math.pi / 2))
        if y > round0:
            f = (y - round0) / (nose - round0)
            r = boiler_r * (1.0 - 0.3 * f ** 2)
        pts = []
        for k in range(n):
            a = -math.pi / 2 + 2 * math.pi * k / n
            x, z = r * math.cos(a), bz_f + r * math.sin(a) / rise
            if abs(x) < w and sky > z:
                z = max(z, bz_f + (sky - bz_f) * (1 - (abs(x) / w) ** 4) ** 0.25)
            pts.append((x, fz(z)))
        return pts

    split = nose - smoke * 0.55
    back = [b0, dip0 - 0.02] + [dip0 + (split - dip0) * k / 4 for k in range(1, 5)]
    front = [split + (nose - split) * k / 6 for k in range(7)]
    m.loft([(y, ring(y)) for y in back], {"casing": c["boiler"]}, sharp=55)
    m.loft([(y, ring(y)) for y in front], {"front": c["front"]}, sharp=55)
    # The smokebox door standing a little proud of the rounded front.
    bz = fz(bz_f)
    m.tube(nose - 0.002, nose + 0.004, 0, bz, boiler_r * 0.55 / m.k(bz), c["black"])


def tender(m, L, scheme, style="box", top=None, livery=None):
    """A tender L tiles long, its front (the end at the engine) at +y.

    style: "early" (four wheels, low plank sides, a water barrel and firewood, the 1830s kind), "plain" (four wheels, a low
    wooden box with the coal showing over its sides), "box" (a tank on two trucks with flared coal boards), "long" (a
    bigger tank on two three-axle trucks, the coal bunker raised at the front), "vanderbilt" (a round tank behind a coal
    bunker, on three-axle trucks, as CN's) or "oil" (a long tank with an oil bunker in place of the coal). top is the
    tank's top in steps over the rail once lowered, to sit level with the engine's boiler top; heights here are given
    as they end up and handed to m through m.fixed, as in engine(). livery paints it to match its engine's, with the
    band along its foot."""
    c = colours(scheme, livery)
    fz = m.fixed
    rise = parts.rig.RISE
    a0, a1 = -L / 2 + 0.01, L / 2 - 0.01
    # The deck as drawn (where the trucks' bolsters stop) and where it ends up.
    deck, deck_f = 0.62, m.z(0.62)
    coal = material("coal", "#141414", rough=0.9)
    body = c["cab"] if scheme == "victorian" or livery else c["paint"]
    underframe(m, a0, a1, c)
    if style in ("early", "plain"):
        for side in (-1, 1):
            for y in (a0 + 0.045, a1 - 0.045):
                m.wheel(y, side, 0.034, (c["steel"], c["wheel"], c["steel"]), spokes=8)
                # Pedestals either side of the axle box, down from the frame.
                m.box(side * 0.112 - 0.008, side * 0.112 + 0.008, y - 0.03, y + 0.03, 0.12, 0.62, c["iron"], bevel=0.002)
                m.box(side * 0.122 - 0.006, side * 0.122 + 0.006, y - 0.014, y + 0.014, 0.12, 0.26, material("journal", "#5a5c60", metal=0.4, rough=0.5))
    else:
        axles = 2 if style == "box" else 3
        inset = 0.08 if axles == 2 else 0.125
        for y in (a0 + inset, a1 - inset):
            truck(m, y, c, axles=axles)
    planks = material("tender planks", "#6e5438", rough=0.8)
    if style == "plain":
        h1 = 0.92
        m.box(-0.122, 0.122, a0, a1, 0.62, h1, planks, bevel=0.003)
        for y in (a0 + 0.004, a1 - 0.004):
            for side in (-1, 1):
                m.box(side * 0.124 - 0.004, side * 0.124 + 0.004, y - 0.006, y + 0.006, 0.6, h1 + 0.01, c["iron"])
        m.box(-0.105, 0.105, a0 + 0.02, a1 - 0.03, h1 - 0.05, h1 + 0.1, coal, bevel=0.02)
        return
    if style == "early":
        # Low plank sides on an open deck, iron straps at the corners.
        side_top = fz(top or 0.72)
        m.box(-0.118, 0.118, a0, a1, deck, fz(deck_f + 0.03), planks)
        for side in (-1, 1):
            m.box(side * 0.11, side * 0.122, a0, a1, deck, side_top, planks, bevel=0.002)
            for y in (a0 + 0.006, a1 - 0.006):
                m.box(side * 0.124 - 0.004, side * 0.124 + 0.004, y - 0.006, y + 0.006, deck - 0.02, side_top + 0.01, c["iron"])
        for y0, y1 in ((a0, a0 + 0.012), (a1 - 0.012, a1)):
            m.box(-0.122, 0.122, y0, y1, deck, side_top, planks, bevel=0.002)
        # The water barrel lying along the back half on two chocks, with its hoops.
        r = 0.052
        bz_f = deck_f + 0.035 + r / rise
        bz, br = fz(bz_f), r / m.k(fz(bz_f))
        y0, y1 = a0 + 0.02, a0 + 0.02 + L * 0.5
        staves = material("barrel staves", "#8a6440", rough=0.7)
        m.tube(y0, y1, 0, bz, br, staves)
        for f in (0.08, 0.5, 0.92):
            y = y0 + (y1 - y0) * f
            m.tube(y - 0.004, y + 0.004, 0, bz, br * 1.04, c["iron"], cap=False)
        for y in (y0 + 0.03, y1 - 0.03):
            m.box(-0.05, 0.05, y - 0.01, y + 0.01, deck, fz(deck_f + 0.06), c["iron"])
        # Firewood stacked across the front.
        wood = material("firewood", "#5a4128", rough=0.9)
        lr = 0.011
        for row in range(3):
            z = fz(deck_f + 0.03 + (2 * row + 1) * lr / rise)
            n = 6 - row
            for k in range(n):
                x = (k - (n - 1) / 2) * 0.034
                m.tube(y1 + 0.02, a1 - 0.02, x, z, lr / m.k(z), wood, segments=8)
        return
    top_f = top or 1.1
    tz = fz(top_f)
    if style == "vanderbilt":
        # A deck plate over the frame, the coal bunker at the front and the round tank behind it.
        m.box(-0.124, 0.124, a0, a1, 0.62, fz(deck_f + 0.03), c["iron"], bevel=0.002)
        bunker = a1 - L * 0.34
        r = 0.088
        cz_f = top_f - r / rise
        cz, cr = fz(cz_f), r / m.k(fz(cz_f))
        m.tube(a0 + 0.012, bunker + 0.01, 0, cz, cr, body)
        # A dished end at the back, and hoops along the tank.
        m.tube(a0 + 0.004, a0 + 0.012, 0, cz, cr * 0.93, body)
        for k in range(1, 4):
            y = a0 + 0.012 + (bunker - a0) * k / 4
            m.tube(y - 0.003, y + 0.003, 0, cz, cr * 1.02, c["iron"], cap=False)
        m.upright(0, a0 + 0.06, fz(top_f - 0.02), fz(top_f + 0.04), 0.022, 0.022, c["iron"])
        # Saddles from the deck up under the tank.
        for y in (a0 + 0.04, (a0 + bunker) / 2, bunker - 0.02):
            m.box(-0.07, 0.07, y - 0.008, y + 0.008, fz(deck_f + 0.03), fz(cz_f - r / rise * 0.6), c["iron"])
        # The bunker stands lower than the tank, its boards under the tank top and the coal heaped up to it.
        bt = top_f - 0.25
        m.box(-0.122, 0.122, bunker, a1, deck, fz(bt), body, bevel=0.004)
        boards(m, bunker + 0.004, a1 - 0.01, bt, c, body if livery else c["iron"])
        heap(m, bunker + 0.015, a1 - 0.03, bt - 0.02, top_f + 0.02, coal)
        for side in (-1, 1):
            m.text(LETTERS, side * (r + 0.002), (a0 + bunker) / 2, cz, 0.045, c["letters"], side)
            if livery:
                # Lining along the deck plate and round the bunker's foot.
                m.box(side * 0.1245, side * 0.1265, a0, a1, fz(deck_f + 0.005), fz(deck_f + 0.025), c["line"])
        return
    # A tank with its top edges rounded, along the whole tender.
    rr = 0.016
    ring = [(0.122, deck)]
    for k in range(5):
        a = math.pi / 2 * k / 4
        ring.append((0.122 - rr + rr * math.cos(a), fz(top_f - rr / rise + rr / rise * math.sin(a))))
    ring += [(-x, z) for x, z in reversed(ring)]
    m.loft([(a0, ring), (a1, ring)], {"body": body}, sharp=50)
    m.box(-0.126, 0.126, a0 - 0.002, a1 + 0.002, deck - 0.05, deck + 0.005, c["iron"], bevel=0.002)
    if style == "box":
        # Coal boards flaring out over the front two thirds, the coal heaped in them, a water hatch behind.
        c0 = a0 + L * 0.36
        boards(m, c0, a1 - 0.01, top_f, c)
        heap(m, c0 + 0.015, a1 - 0.04, top_f - 0.02, top_f + 0.25, coal)
        m.upright(0, a0 + (c0 - a0) * 0.45, tz - 0.02, fz(top_f + 0.04), 0.022, 0.022, c["iron"])
    elif style == "long":
        # The coal bunker raised over the front half, its sides sloping down at the back to the tank top.
        c0 = a0 + L * 0.42
        hi = fz(top_f + 0.11)
        for side in (-1, 1):
            x0, x1 = side * 0.112, side * 0.123
            m.wedge([(x0, c0, tz - 0.01), (x1, c0, tz - 0.01), (x1, a1 - 0.006, tz - 0.01), (x0, a1 - 0.006, tz - 0.01)],
                    [(x0, c0 + 0.05, hi), (x1, c0 + 0.05, hi), (x1, a1 - 0.006, hi), (x0, a1 - 0.006, hi)], body)
        m.wedge([(-0.112, c0, tz - 0.01), (0.112, c0, tz - 0.01), (0.112, c0 + 0.05, tz - 0.01), (-0.112, c0 + 0.05, tz - 0.01)],
                [(-0.112, c0 + 0.05, hi), (0.112, c0 + 0.05, hi), (0.112, c0 + 0.06, hi), (-0.112, c0 + 0.06, hi)], body)
        heap(m, c0 + 0.05, a1 - 0.03, top_f, top_f + 0.22, coal)
        m.upright(0, a0 + (c0 - a0) * 0.4, tz - 0.02, fz(top_f + 0.04), 0.024, 0.024, c["iron"])
    else:
        # Oil: a raised bunker at the front with its filler, a water hatch at the back, no coal.
        o0 = a0 + L * 0.5
        hi = top_f + 0.08
        ring2 = [(0.112, tz - 0.01)]
        for k in range(5):
            a = math.pi / 2 * k / 4
            ring2.append((0.112 - rr + rr * math.cos(a), fz(hi - rr / rise + rr / rise * math.sin(a))))
        ring2 += [(-x, z) for x, z in reversed(ring2)]
        m.loft([(o0, ring2), (a1 - 0.012, ring2)], {"body": body}, sharp=50)
        m.upright(0, (o0 + a1) / 2, fz(hi - 0.02), fz(hi + 0.04), 0.026, 0.026, c["iron"])
        m.upright(0, a0 + (o0 - a0) * 0.4, tz - 0.02, fz(top_f + 0.04), 0.024, 0.024, c["iron"])
    if livery:
        # The band along the tank's foot, lined along its top and round the tank top.
        bt = deck_f + 0.14
        for side in (-1, 1):
            x = side * 0.1228
            m.box(side * 0.122, side * 0.123, a0 + 0.004, a1 - 0.004, deck, fz(bt), c["band"])
            m.box(x - 0.0009, x + 0.0009, a0 + 0.004, a1 - 0.004, fz(bt - 0.03), fz(bt - 0.012), c["line"])
            m.box(x - 0.0009, x + 0.0009, a0 + 0.006, a1 - 0.006, fz(top_f - 0.11), fz(top_f - 0.092), c["line"])
    # A ladder up the back.
    for x in (-0.03, 0.03):
        m.rod((x, a0 - 0.004, deck), (x, a0 - 0.004, tz), 0.003, c["steel"])
    for side in (-1, 1):
        m.text(LETTERS, side * 0.1232, 0, (deck + tz) / 2, 0.05, c["letters"], side)


def boards(m, y0, y1, top_f, c, mat=None):
    """Coal boards flaring out from the tank's sides at top_f steps, from y0 to y1 along the tender."""
    fz = m.fixed
    mat = mat or c["iron"]
    lo, hi = fz(top_f - 0.02), fz(top_f + 0.1)
    for side in (-1, 1):
        x0, x1 = side * 0.112, side * 0.122
        m.wedge([(x0, y0, lo), (x1, y0, lo), (x1, y1, lo), (x0, y1, lo)],
                [(x0 + side * 0.012, y0, hi), (x1 + side * 0.012, y0, hi), (x1 + side * 0.012, y1, hi), (x0 + side * 0.012, y1, hi)], mat)
    m.wedge([(-0.112, y0, lo), (0.112, y0, lo), (0.112, y0 + 0.01, lo), (-0.112, y0 + 0.01, lo)],
            [(-0.124, y0, hi), (0.124, y0, hi), (0.124, y0 + 0.01, hi), (-0.124, y0 + 0.01, hi)], mat)


def heap(m, y0, y1, z0_f, z1_f, coal):
    """A heap of coal from y0 to y1 along the tender, its foot at z0_f and crown at z1_f steps, lower at its ends."""
    fz = m.fixed
    half = 0.11

    def ring(h, w):
        pts = [(half * w, fz(z0_f))]
        for k in range(1, 8):
            x = half * w * (1 - 2 * k / 8)
            pts.append((x, fz(z0_f + h * (1 - (x / (half * w)) ** 2) ** 0.6)))
        pts.append((-half * w, fz(z0_f)))
        return pts

    h = z1_f - z0_f
    m.loft([(y0, ring(h * 0.3, 0.8)), (y0 + 0.04, ring(h, 1.0)), (y1 - 0.04, ring(h, 1.0)), (y1, ring(h * 0.3, 0.8))], {"coal": coal}, sharp=70)


def underframe(m, a0, a1, c, deck=0.62):
    """A car's underframe from a0 to a1: the centre sill, and side sills out to the body's sides so it comes down
    onto its trucks with no daylight under it."""
    m.box(-0.085, 0.085, a0, a1, 0.45, deck, c["iron"])
    for side in (-1, 1):
        m.box(side * 0.104, side * 0.121, a0, a1, deck - 0.14, deck, c["iron"], bevel=0.002)


def truck(m, y, c, r=0.036, top=0.62, axles=2):
    """An arch-bar truck of two or three axles at y along the model, with the body bolster over it up to [top], where the body sits."""
    zc = r / 0.2041
    dys = (-0.045, 0.045) if axles == 2 else (-0.078, 0.0, 0.078)
    span = 0.075 if axles == 2 else 0.11
    m.box(-0.11, 0.11, y - 0.03, y + 0.03, zc * 2.0, top, c["iron"], bevel=0.002)
    for side in (-1, 1):
        for dy in dys:
            m.wheel(y + dy, side, r, (c["steel"], material("wheel dark", "#222224", rough=0.6), c["steel"]), spokes=0)
        m.box(side * 0.111, side * 0.121, y - span, y + span, zc * 0.6, zc * 2.4, c["iron"], bevel=0.002)
        for dy in dys:
            m.box(side * 0.121, side * 0.127, y + dy - 0.013, y + dy + 0.013, zc * 0.65, zc * 1.4, material("journal", "#5a5c60", metal=0.4, rough=0.5))
    m.box(-0.111, 0.111, y - 0.016, y + 0.016, zc * 1.9, zc * 2.6, c["iron"])
