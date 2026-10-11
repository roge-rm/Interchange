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
            "roof": material("cab roof", "#3a3430", rough=0.7),
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
        "glass": material("glass", "#1e2a36", rough=0.1),
        "roof": material("cab roof", "#222222", rough=0.7),
        "letters": material("cream letters", "#e6dcc0", rough=0.5),
    }


def engine(m, L, lead, drive, trail, r_drive, r_lead, boiler_r, stack, domes, scheme, pilot=True, cab_len=0.13):
    """A steam engine L tiles long, nose at +y."""
    c = colours(scheme)
    back = -L / 2 + 0.01
    front = L / 2 - (0.07 if pilot else 0.02)
    deck = r_drive / 0.2041 * 2 + 0.15
    # Frame and running boards.
    m.box(-0.08, 0.08, back, front, deck - 0.45, deck - 0.05, c["iron"])
    for side in (-1, 1):
        m.box(side * 0.105, side * 0.13, back + cab_len, front - 0.02, deck - 0.05, deck, c["iron"], bevel=0.002)
    # Wheels: leading under the front, drivers in the middle, trailing under the cab.
    lead_pos = [front - 0.05 - i * r_lead * 2.5 for i in range(lead)]
    d0 = (lead_pos[-1] - r_lead - r_drive - 0.03) if lead else front - r_drive - 0.02
    drive_pos = [d0 - i * r_drive * 2.2 for i in range(drive)]
    trail_pos = [drive_pos[-1] - r_drive - 0.03 - r_lead - i * r_lead * 2.4 for i in range(trail)]
    wmats = (c["steel"], c["wheel"], c["steel"])
    for side in (-1, 1):
        for y in lead_pos + trail_pos:
            m.wheel(y, side, r_lead, wmats, spokes=10)
        for y in drive_pos:
            m.wheel(y, side, r_drive, wmats, spokes=14, counterweight=True)
        # Steam cylinder, crosshead guide, main rod and side rods.
        zc = r_drive / 0.2041
        cy0 = drive_pos[0] + r_drive + 0.03
        m.tube(cy0, cy0 + 0.085, side * 0.115, zc + 0.15, 0.032, c["paint"])
        m.box(side * 0.112, side * 0.118, drive_pos[0] + r_drive * 0.4, cy0, zc + 0.1, zc + 0.2, c["steel"])
        crank_z = zc - r_drive * 0.45 / 0.2041
        m.rod((side * 0.121, cy0 - 0.01, zc + 0.15), (side * 0.121, drive_pos[-1 if drive < 3 else 1], crank_z), 0.006, c["steel"])
        if drive > 1:
            m.rod((side * 0.124, drive_pos[0], crank_z), (side * 0.124, drive_pos[-1], crank_z), 0.006, c["steel"])
    # Boiler, bands and smokebox.
    cab_front = back + cab_len
    bz = deck + boiler_r / 0.2041 + 0.02
    smoke = 0.07
    m.tube(cab_front, front - smoke, 0, bz, boiler_r, c["boiler"])
    for k in range(1, 4):
        y = cab_front + (front - smoke - cab_front) * k / 4
        m.tube(y - 0.004, y + 0.004, 0, bz, boiler_r * 1.03, c["brass"], cap=False)
    m.tube(front - smoke, front, 0, bz, boiler_r * 1.06, c["black"])
    # Handrails along the boiler.
    for side in (-1, 1):
        m.rod((side * (boiler_r + 0.012), cab_front, bz + 0.3), (side * (boiler_r + 0.012), front - smoke, bz + 0.3), 0.003, c["brass"])
    top = bz + boiler_r / 0.2041
    sy = front - smoke / 2
    if stack == "balloon":
        m.lathe(0, sy, top - 0.1, [(0.02, 0), (0.022, 0.3), (0.045, 0.5), (0.056, 0.68), (0.052, 0.76), (0.028, 0.78)], c["black"])
    elif stack == "diamond":
        m.lathe(0, sy, top - 0.1, [(0.02, 0), (0.022, 0.32), (0.05, 0.52), (0.018, 0.7)], c["black"])
    else:
        m.lathe(0, sy, top - 0.1, [(0.024, 0), (0.024, 0.35), (0.03, 0.4), (0.028, 0.44)], c["black"])
    # Domes, bell and whistle.
    for i in range(domes):
        dy = cab_front + (front - smoke - cab_front) * (0.3 + 0.38 * i)
        m.lathe(0, dy, top - 0.15, [(0.036, 0), (0.034, 0.22), (0.028, 0.3), (0.016, 0.35), (0.0, 0.36)], c["brass"] if scheme == "victorian" else c["boiler"])
    m.lathe(0, cab_front + (front - smoke - cab_front) * 0.5, top - 0.05, [(0.004, 0), (0.004, 0.1), (0.014, 0.12), (0.017, 0.22), (0.005, 0.25)], c["brass"])
    m.lathe(0.0, cab_front + 0.02, top - 0.05, [(0.004, 0), (0.004, 0.2), (0.006, 0.22), (0.006, 0.3), (0.0, 0.32)], c["brass"])
    # Headlamp on the smokebox.
    if scheme == "victorian":
        m.box(-0.025, 0.025, front - 0.045, front - 0.0, top - 0.02, top + 0.24, c["cab"], bevel=0.003)
        m.tube(front - 0.002, front + 0.004, 0, top + 0.11, 0.014, c["brass"])
    else:
        m.tube(front - 0.03, front + 0.005, 0, bz + 0.15, 0.022, c["black"])
    # The cab, with windows on each side and at the front.
    cab_h = deck + 1.0
    m.box(-0.12, 0.12, back, cab_front, deck, cab_h, c["cab"], bevel=0.003)
    m.box(-0.135, 0.135, back - 0.015, cab_front + 0.015, cab_h, cab_h + 0.12, c["roof"], bevel=0.012)
    for side in (-1, 1):
        m.box(side * 0.1205 - 0.0008, side * 0.1205 + 0.0008, cab_front - 0.06, cab_front - 0.015, deck + 0.5, deck + 0.88, c["glass"])
        m.box(side * 0.1205 - 0.0008, side * 0.1205 + 0.0008, back + 0.015, back + 0.05, deck + 0.5, deck + 0.88, c["glass"])
    for side in (-1, 1):
        m.box(side * 0.05 - 0.025, side * 0.05 + 0.025, cab_front - 0.001, cab_front + 0.0015, deck + 0.55, deck + 0.88, c["glass"])
    # Lettering on the cab sides.
    for side in (-1, 1):
        m.text(LETTERS, side * 0.1212, back + cab_len / 2, deck + 0.28, 0.03, c["letters"], side)
    # Pilot.
    if pilot:
        tip = front + 0.065
        h0, h1 = 0.08, deck - 0.2
        m.wedge([(-0.1, front, h0), (0.1, front, h0), (0.0, tip, h0)], [(-0.1, front, h1), (0.1, front, h1), (0.0, front + 0.012, h1)], c["paint"] if scheme == "victorian" else c["iron"])
        m.box(-0.11, 0.11, front - 0.01, front + 0.005, deck - 0.35, deck - 0.05, c["paint"])


def tender(m, L, scheme, small=False):
    c = colours(scheme)
    a0, a1 = -L / 2 + 0.01, L / 2 - 0.01
    m.box(-0.085, 0.085, a0, a1, 0.45, 0.62, c["iron"])
    if small:
        for side in (-1, 1):
            for y in (a0 + 0.045, a1 - 0.045):
                m.wheel(y, side, 0.034, (c["steel"], c["wheel"], c["steel"]), spokes=8)
    else:
        for y in (a0 + 0.08, a1 - 0.08):
            truck(m, y, c)
    h1 = 1.15 if small else 1.35
    m.box(-0.122, 0.122, a0, a1, 0.62, h1, c["cab"] if scheme == "victorian" else c["paint"], bevel=0.003)
    # Coal boards flaring out at the top, and the coal heaped in them.
    m.box(-0.13, 0.13, a0 + 0.005, a1 - 0.08, h1, h1 + 0.15, c["iron"], bevel=0.002)
    m.box(-0.105, 0.105, a0 + 0.02, a1 - 0.1, h1 + 0.1, h1 + 0.32, material("coal", "#141414", rough=0.9), bevel=0.02)
    for side in (-1, 1):
        m.text(LETTERS, side * 0.1232, 0, (0.62 + h1) / 2, 0.05, c["letters"], side)


def truck(m, y, c, r=0.036):
    """A two-axle arch-bar truck at y along the model."""
    zc = r / 0.2041
    for side in (-1, 1):
        for dy in (-0.045, 0.045):
            m.wheel(y + dy, side, r, (c["steel"], material("wheel dark", "#222224", rough=0.6), c["steel"]), spokes=0)
        m.box(side * 0.111, side * 0.121, y - 0.075, y + 0.075, zc * 0.6, zc * 2.4, c["iron"], bevel=0.002)
        for dy in (-0.045, 0.045):
            m.box(side * 0.121, side * 0.127, y + dy - 0.013, y + dy + 0.013, zc * 0.65, zc * 1.4, material("journal", "#5a5c60", metal=0.4, rough=0.5))
    m.box(-0.111, 0.111, y - 0.016, y + 0.016, zc * 1.9, zc * 2.6, c["iron"])
