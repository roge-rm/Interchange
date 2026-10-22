"""Steam engines and their tenders, after those that ran in Canada, in our own colours.

Built facing +y, the front at +L/2, in tiles across and along, steps up.
"""
import math

import parts
from parts import material

LETTERS = "I.R."


def colours(scheme):
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
           drive_set=0.0, return_flue=False):
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
    bands. drive_set moves the drivers back from the front by that many tiles."""
    c = colours(scheme)
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
    if lagged:
        # Wooden staves, left faceted so each shows, held by iron bands.
        wood = material("boiler lagging", "#8a6a48", rough=0.75)
        staves = m.tube(b0, nose - smoke, 0, bz, br, wood, segments=22)
        staves.data.polygons.foreach_set("use_smooth", [False] * len(staves.data.polygons))
        bands, band_mat = 5, c["iron"]
    else:
        m.tube(b0, nose - smoke, 0, bz, br, c["boiler"])
        bands, band_mat = 4, (c["brass"] if scheme == "victorian" else c["iron"])
    for k in range(1, bands):
        y = b0 + (nose - smoke - b0) * k / bands
        m.tube(y - 0.004, y + 0.004, 0, bz, br * 1.03, band_mat, cap=False)
    if return_flue:
        # The far end of the barrel is closed, in an iron casing the vertical cylinders stand beside.
        m.box(-boiler_r * 0.9, boiler_r * 0.9, nose - 0.035, nose, deck, fz(top_f - 0.02), c["iron"], bevel=0.004)
    else:
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
    if stack != "hidden":
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
    if feedwater:
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
    for i in range(domes):
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
    else:
        m.lathe(0, cab_front + (nose - smoke - cab_front) * 0.5, top - 0.05, [(0.004, 0), (0.004, 0.1), (0.014, 0.12), (0.017, 0.22), (0.005, 0.25)], c["brass"])
        m.lathe(0.0, cab_front + 0.02, top - 0.05, [(0.004, 0), (0.004, 0.2), (0.006, 0.22), (0.006, 0.3), (0.0, 0.32)], c["brass"])

    # Headlight.
    if headlight == "box":
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

    # The cab: walls just over the boiler, a wider arched roof overhanging them.
    if not early:
        roof_f = top_f + {"wood": 0.17, "steel": 0.09, "vestibule": 0.1}[cab]
        roof = fz(roof_f)
        wall = c["cab"]
        rear = back if cab == "vestibule" else back + 0.03
        m.box(-0.122, 0.122, rear, cab_front, deck, roof, wall, bevel=0.003)
        if cab != "vestibule":
            # The open gangway to the tender behind the cab sides.
            m.box(-0.11, 0.11, back, rear, deck, roof, c["black"])
        w, crown = 0.14, 0.07
        ring = [(w * math.cos(a), roof + crown * math.sin(a)) for a in [math.pi * k / 10 for k in range(11)]]
        ring += [(-w, roof - 0.03), (w, roof - 0.03)]
        m.loft([(back - 0.004, ring), (cab_front + 0.02, ring)], {"roof": c["roof"]})
        sill, head = fz(bz_f if cab == "wood" else top_f - 0.1), roof - 0.05
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
        else:
            # A cast steel pilot, sloping back from its foot, with its bars showing.
            tip = front + 0.05
            m.wedge([(-0.1, front, h0), (0.1, front, h0), (0.05, tip, h0), (-0.05, tip, h0)],
                    [(-0.11, front + 0.004, h1), (0.11, front + 0.004, h1), (0.08, front + 0.01, h1), (-0.08, front + 0.01, h1)], c["black"])
            for k in range(1, 4):
                f = k / 4
                y, z = front + 0.01 + (tip - front - 0.01) * (1 - f), h0 + (h1 - h0) * f
                m.box(-0.1 + 0.03 * (1 - f), 0.1 - 0.03 * (1 - f), y - 0.002, y + 0.004, z - 0.012, z, c["iron"])


def tender(m, L, scheme, small=False, plain=False):
    """A tender L tiles long; plain is the earliest kind, a low wooden box with the coal showing over its sides."""
    c = colours(scheme)
    a0, a1 = -L / 2 + 0.01, L / 2 - 0.01
    underframe(m, a0, a1, c)
    if small:
        for side in (-1, 1):
            for y in (a0 + 0.045, a1 - 0.045):
                m.wheel(y, side, 0.034, (c["steel"], c["wheel"], c["steel"]), spokes=8)
                # Pedestals either side of the axle box, down from the frame.
                m.box(side * 0.112 - 0.008, side * 0.112 + 0.008, y - 0.03, y + 0.03, 0.12, 0.62, c["iron"], bevel=0.002)
                m.box(side * 0.122 - 0.006, side * 0.122 + 0.006, y - 0.014, y + 0.014, 0.12, 0.26, material("journal", "#5a5c60", metal=0.4, rough=0.5))
    else:
        for y in (a0 + 0.08, a1 - 0.08):
            truck(m, y, c)
    if plain:
        h1 = 0.92
        m.box(-0.122, 0.122, a0, a1, 0.62, h1, material("tender planks", "#6e5438", rough=0.8), bevel=0.003)
        for y in (a0 + 0.004, a1 - 0.004):
            for side in (-1, 1):
                m.box(side * 0.124 - 0.004, side * 0.124 + 0.004, y - 0.006, y + 0.006, 0.6, h1 + 0.01, c["iron"])
        m.box(-0.105, 0.105, a0 + 0.02, a1 - 0.03, h1 - 0.05, h1 + 0.1, material("coal", "#141414", rough=0.9), bevel=0.02)
        return
    h1 = 1.15 if small else 1.35
    m.box(-0.122, 0.122, a0, a1, 0.62, h1, c["cab"] if scheme == "victorian" else c["paint"], bevel=0.003)
    # Coal boards flaring out at the top, and the coal heaped in them.
    m.box(-0.13, 0.13, a0 + 0.005, a1 - 0.08, h1, h1 + 0.15, c["iron"], bevel=0.002)
    m.box(-0.105, 0.105, a0 + 0.02, a1 - 0.1, h1 + 0.1, h1 + 0.32, material("coal", "#141414", rough=0.9), bevel=0.02)
    for side in (-1, 1):
        m.text(LETTERS, side * 0.1232, 0, (0.62 + h1) / 2, 0.05, c["letters"], side)


def underframe(m, a0, a1, c, deck=0.62):
    """A car's underframe from a0 to a1: the centre sill, and side sills out to the body's sides so it comes down
    onto its trucks with no daylight under it."""
    m.box(-0.085, 0.085, a0, a1, 0.45, deck, c["iron"])
    for side in (-1, 1):
        m.box(side * 0.104, side * 0.121, a0, a1, deck - 0.14, deck, c["iron"], bevel=0.002)


def truck(m, y, c, r=0.036, top=0.62):
    """A two-axle arch-bar truck at y along the model, with the body bolster over it up to [top], where the body sits."""
    zc = r / 0.2041
    m.box(-0.11, 0.11, y - 0.03, y + 0.03, zc * 2.0, top, c["iron"], bevel=0.002)
    for side in (-1, 1):
        for dy in (-0.045, 0.045):
            m.wheel(y + dy, side, r, (c["steel"], material("wheel dark", "#222224", rough=0.6), c["steel"]), spokes=0)
        m.box(side * 0.111, side * 0.121, y - 0.075, y + 0.075, zc * 0.6, zc * 2.4, c["iron"], bevel=0.002)
        for dy in (-0.045, 0.045):
            m.box(side * 0.121, side * 0.127, y + dy - 0.013, y + dy + 0.013, zc * 0.65, zc * 1.4, material("journal", "#5a5c60", metal=0.4, rough=0.5))
    m.box(-0.111, 0.111, y - 0.016, y + 0.016, zc * 1.9, zc * 2.6, c["iron"])
