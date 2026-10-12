"""Cars, in our own colours: built facing +y, L tiles long."""
import math

import parts
import rig
from parts import material
from steam import LETTERS, truck, underframe


def common():
    return {
        "iron": material("iron", "#3a3a3c", metal=0.5, rough=0.5),
        "steel": material("steel", "#9a9a9a", metal=1.0, rough=0.3),
        "glass": material("glass", "#1e2a36", rough=0.1),
        "roof": material("car roof", "#3e3a36", rough=0.75),
        "letters": material("gold leaf", "#d8b450", metal=1.0, rough=0.3),
    }


def coach_wood(m, L):
    c = common()
    body = material("varnished wood", "#7a3a1e", rough=0.4, clearcoat=0.6)
    trim = material("dark wood", "#4a2414", rough=0.5)
    a0, a1 = -L / 2 + 0.03, L / 2 - 0.03
    underframe(m, -L / 2 + 0.01, L / 2 - 0.01, c)
    for y in (-L / 2 + 0.09, L / 2 - 0.09):
        truck(m, y, c)
    m.box(-0.12, 0.12, a0, a1, 0.62, 1.5, body, bevel=0.003)
    # Panels: a raised rail under the windows and above them.
    for side in (-1, 1):
        m.box(side * 0.12 - 0.002, side * 0.12 + 0.002, a0 + 0.005, a1 - 0.005, 0.9, 0.95, trim)
        m.box(side * 0.12 - 0.002, side * 0.12 + 0.002, a0 + 0.005, a1 - 0.005, 1.32, 1.36, trim)
    # A row of windows down each side.
    n = 9
    span = (a1 - a0 - 0.04) / n
    for side in (-1, 1):
        for i in range(n):
            y0 = a0 + 0.02 + span * i + span * 0.18
            m.box(side * 0.1205 - 0.0008, side * 0.1205 + 0.0008, y0, y0 + span * 0.64, 0.98, 1.28, c["glass"])
        m.text(LETTERS, side * 0.1216, 0, 0.75, 0.035, c["letters"], side)
    # A clerestory roof: the lower roof, then the raised middle with its own little windows.
    m.box(-0.13, 0.13, a0 - 0.01, a1 + 0.01, 1.5, 1.58, c["roof"], bevel=0.004)
    m.box(-0.065, 0.065, a0 + 0.03, a1 - 0.03, 1.58, 1.74, body, bevel=0.002)
    m.box(-0.075, 0.075, a0 + 0.025, a1 - 0.025, 1.74, 1.8, c["roof"], bevel=0.003)
    # Open platforms at each end with railings.
    for y0, y1 in ((-L / 2 + 0.005, a0), (a1, L / 2 - 0.005)):
        m.box(-0.1, 0.1, y0, y1, 0.55, 0.65, c["iron"])
        for side in (-1, 1):
            m.rod((side * 0.1, (y0 + y1) / 2, 0.65), (side * 0.1, (y0 + y1) / 2, 1.15), 0.003, c["steel"])


def _base(m, L, a0, a1, c):
    underframe(m, -L / 2 + 0.01, L / 2 - 0.01, c)
    for y in (-L / 2 + 0.085, L / 2 - 0.085):
        truck(m, y, c)


def coach_steel(m, L):
    c = common()
    green = material("passenger green", "#2c4c38", rough=0.35, clearcoat=0.5)
    cream = material("cream", "#e6dcc0", rough=0.4, clearcoat=0.4)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    _base(m, L, a0, a1, c)
    m.box(-0.12, 0.12, a0, a1, 0.62, 0.95, green, bevel=0.004)
    m.box(-0.1202, 0.1202, a0 + 0.004, a1 - 0.004, 0.95, 1.3, cream, bevel=0.002)
    m.box(-0.12, 0.12, a0, a1, 1.3, 1.45, green, bevel=0.004)
    m.tube(a0, a1, 0, 1.38, 0.115, c["roof"], segments=32)
    n = 8
    span = (a1 - a0 - 0.06) / n
    for side in (-1, 1):
        for i in range(n):
            y0 = a0 + 0.03 + span * i + span * 0.15
            m.box(side * 0.1207 - 0.0008, side * 0.1207 + 0.0008, y0, y0 + span * 0.7, 1.0, 1.25, c["glass"])
        m.box(side * 0.1205 - 0.001, side * 0.1205 + 0.001, a0 + 0.01, a1 - 0.01, 0.9, 0.93, material("gold line", "#c9a640", metal=1.0, rough=0.3))
        m.text(LETTERS, side * 0.1212, 0, 0.77, 0.035, material("gold leaf", "#d8b450", metal=1.0, rough=0.3), side)


def baggage(m, L, steel):
    c = common()
    body = material("passenger green", "#2c4c38", rough=0.35, clearcoat=0.5) if steel else material("varnished wood", "#7a3a1e", rough=0.4, clearcoat=0.6)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    _base(m, L, a0, a1, c)
    m.box(-0.12, 0.12, a0, a1, 0.62, 1.45, body, bevel=0.004)
    if steel:
        m.tube(a0, a1, 0, 1.38, 0.115, c["roof"], segments=32)
    else:
        m.box(-0.13, 0.13, a0 - 0.01, a1 + 0.01, 1.45, 1.53, c["roof"], bevel=0.004)
    door = material("door", "#1f2a22" if steel else "#4a2414", rough=0.5)
    for side in (-1, 1):
        for dy in (-0.09, 0.09):
            m.box(side * 0.1205 - 0.001, side * 0.1205 + 0.001, dy - 0.04, dy + 0.04, 0.68, 1.35, door)
        for y in (a0 + 0.045, a1 - 0.045):
            m.box(side * 0.1207 - 0.0008, side * 0.1207 + 0.0008, y - 0.018, y + 0.018, 1.05, 1.3, c["glass"])
        m.text("MAIL", side * 0.1212, 0, 1.0, 0.03, c["letters"], side)


def boxcar(m, L, steel):
    c = common()
    body = material("freight red", "#8a3a2a", rough=0.7)
    dark = material("freight red dark", "#5e261c", rough=0.7)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    _base(m, L, a0, a1, c)
    m.box(-0.12, 0.12, a0, a1, 0.62, 1.55, body, bevel=0.003)
    m.box(-0.125, 0.125, a0 - 0.004, a1 + 0.004, 1.55, 1.6, dark, bevel=0.003)
    m.box(-0.025, 0.025, a0, a1, 1.6, 1.64, material("roof walk", "#4a3426", rough=0.8))
    for side in (-1, 1):
        m.box(side * 0.1208 - 0.001, side * 0.1208 + 0.001, -0.05, 0.05, 0.66, 1.5, dark)
        m.rod((side * 0.1218, -0.052, 1.52), (side * 0.1218, 0.052, 1.52), 0.003, c["iron"])
        if steel:
            for k in range(1, 7):
                y = a0 + (a1 - a0) * k / 7
                if abs(y) > 0.06:
                    m.box(side * 0.1206 - 0.0015, side * 0.1206 + 0.0015, y - 0.003, y + 0.003, 0.64, 1.53, dark)
        else:
            for k in range(1, 9):
                z = 0.62 + (1.55 - 0.62) * k / 9
                m.box(side * 0.1203 - 0.0006, side * 0.1203 + 0.0006, a0 + 0.005, a1 - 0.005, z - 0.004, z + 0.004, dark)
        m.text(LETTERS, side * 0.1216, (a0 - 0.05) / 2 - 0.02, 1.2, 0.03, material("white letters", "#e8e4dc", rough=0.6), side)


def flatcar(m, L):
    c = common()
    deck = material("deck wood", "#6a5236", rough=0.8)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    for y in (-L / 2 + 0.085, L / 2 - 0.085):
        truck(m, y, c)
    m.box(-0.12, 0.12, a0, a1, 0.45, 0.68, deck, bevel=0.003)
    for side in (-1, 1):
        for k in range(5):
            y = a0 + 0.02 + (a1 - a0 - 0.04) * k / 4
            m.box(side * 0.114 - 0.007, side * 0.114 + 0.007, y - 0.007, y + 0.007, 0.68, 1.0, material("stake", "#4a3a2a", rough=0.8))
    # A load of timber.
    logs = material("logs", "#8a6a44", rough=0.9)
    for x in (-0.06, 0.0, 0.06):
        m.tube(a0 + 0.02, a1 - 0.02, x, 0.85, 0.028, logs, segments=12)
    for x in (-0.03, 0.03):
        m.tube(a0 + 0.03, a1 - 0.03, x, 1.07, 0.028, logs, segments=12)


def gondola(m, L):
    c = common()
    body = material("gondola", "#3a3634", rough=0.7)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    _base(m, L, a0, a1, c)
    m.box(-0.12, 0.12, a0, a1, 0.62, 1.0, body, bevel=0.003)
    m.box(-0.11, 0.11, a0 + 0.01, a1 - 0.01, 0.95, 1.0, material("inside", "#1e1c1b", rough=0.9))
    for side in (-1, 1):
        for k in range(1, 8):
            y = a0 + (a1 - a0) * k / 8
            m.box(side * 0.1206 - 0.0015, side * 0.1206 + 0.0015, y - 0.003, y + 0.003, 0.64, 0.98, material("gondola rib", "#2a2725", rough=0.7))


def hopper(m, L):
    c = common()
    body = material("hopper black", "#2e2e30", rough=0.6)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    for y in (-L / 2 + 0.075, L / 2 - 0.075):
        truck(m, y, c, top=0.86)
    m.box(-0.12, 0.12, a0, a1, 0.85, 1.6, body, bevel=0.003)
    # Two bays sloping down to their chutes.
    for cy in (-0.07, 0.07):
        m.wedge([(-0.06, cy - 0.03, 0.38), (0.06, cy - 0.03, 0.38), (0.06, cy + 0.03, 0.38), (-0.06, cy + 0.03, 0.38)],
                [(-0.115, cy - 0.065, 0.85), (0.115, cy - 0.065, 0.85), (0.115, cy + 0.065, 0.85), (-0.115, cy + 0.065, 0.85)], body)
    m.box(-0.105, 0.105, a0 + 0.01, a1 - 0.01, 1.55, 1.68, material("coal", "#141414", rough=0.9), bevel=0.015)
    for side in (-1, 1):
        m.text(LETTERS, side * 0.1212, 0, 1.25, 0.03, material("white letters", "#e8e4dc", rough=0.6), side)


def tank_car(m, L):
    c = common()
    body = material("tank black", "#262628", rough=0.35, clearcoat=0.4)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    _base(m, L, a0, a1, c)
    m.tube(a0 + 0.01, a1 - 0.01, 0, 1.15, 0.105, body, segments=32)
    m.upright(0, 0, 1.55, 1.82, 0.04, 0.04, body)
    m.box(-0.125, 0.125, a0, a1, 0.62, 0.66, c["iron"])
    for side in (-1, 1):
        m.rod((side * 0.112, a0 + 0.01, 1.2), (side * 0.112, a1 - 0.01, 1.2), 0.003, c["iron"])
        m.text("OIL", side * 0.1062, 0, 1.15, 0.03, material("white letters", "#e8e4dc", rough=0.6), side)


def stock_car(m, L):
    c = common()
    body = material("stock car", "#7a5a3a", rough=0.8)
    gap = material("gap", "#1e160e", rough=0.9)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    _base(m, L, a0, a1, c)
    m.box(-0.12, 0.12, a0, a1, 0.62, 1.5, body, bevel=0.003)
    m.box(-0.125, 0.125, a0 - 0.004, a1 + 0.004, 1.5, 1.56, c["roof"], bevel=0.003)
    for side in (-1, 1):
        for k in range(6):
            z = 0.72 + k * 0.13
            m.box(side * 0.1203 - 0.0006, side * 0.1203 + 0.0006, a0 + 0.01, a1 - 0.01, z, z + 0.04, gap)


def caboose(m, L):
    c = common()
    red = material("caboose red", "#9a2e24", rough=0.5, clearcoat=0.3)
    a0, a1 = -L / 2 + 0.035, L / 2 - 0.035
    underframe(m, -L / 2 + 0.01, L / 2 - 0.01, c)
    for y in (-L / 2 + 0.085, L / 2 - 0.085):
        truck(m, y, c)
    m.box(-0.12, 0.12, a0, a1, 0.62, 1.42, red, bevel=0.003)
    m.box(-0.13, 0.13, a0 - 0.01, a1 + 0.01, 1.42, 1.5, c["roof"], bevel=0.004)
    m.box(-0.1, 0.1, -0.045, 0.045, 1.5, 1.9, red, bevel=0.002)
    m.box(-0.11, 0.11, -0.055, 0.055, 1.9, 1.96, c["roof"], bevel=0.003)
    for side in (-1, 1):
        for y in (a0 + 0.04, 0.0, a1 - 0.04):
            m.box(side * 0.1207 - 0.0008, side * 0.1207 + 0.0008, y - 0.016, y + 0.016, 0.95, 1.22, c["glass"])
        m.box(side * 0.1007 - 0.0008, side * 0.1007 + 0.0008, -0.022, 0.022, 1.6, 1.8, c["glass"])
    for y0, y1 in ((-L / 2 + 0.005, a0), (a1, L / 2 - 0.005)):
        m.box(-0.1, 0.1, y0, y1, 0.55, 0.65, c["iron"])
        for side in (-1, 1):
            m.rod((side * 0.1, (y0 + y1) / 2, 0.65), (side * 0.1, (y0 + y1) / 2, 1.1), 0.003, c["steel"])


def reefer(m, L):
    """A wooden ice refrigerator car: a yellow body, ice hatches at each end of the roof and a plug door."""
    c = common()
    body = material("reefer yellow", "#d8b24a", rough=0.7)
    trim = material("reefer brown", "#5a3a26", rough=0.75)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    _base(m, L, a0, a1, c)
    m.box(-0.12, 0.12, a0, a1, 0.62, 1.55, body, bevel=0.003)
    m.box(-0.125, 0.125, a0 - 0.004, a1 + 0.004, 1.55, 1.6, trim, bevel=0.003)
    m.box(-0.025, 0.025, a0, a1, 1.6, 1.64, material("roof walk", "#4a3426", rough=0.8))
    for y in (a0 + 0.03, a1 - 0.03):
        m.box(-0.06, 0.06, y - 0.02, y + 0.02, 1.6, 1.68, trim, bevel=0.002)
    for side in (-1, 1):
        for k in range(1, 11):
            z = 0.62 + (1.55 - 0.62) * k / 11
            m.box(side * 0.1203 - 0.0006, side * 0.1203 + 0.0006, a0 + 0.005, a1 - 0.005, z - 0.003, z + 0.003, material("reefer boards", "#c09a3a", rough=0.8))
        m.box(side * 0.1212 - 0.002, side * 0.1212 + 0.002, -0.04, 0.04, 0.7, 1.45, trim)
        m.text(LETTERS, side * 0.1216, a0 / 2 - 0.02, 1.25, 0.028, trim, side)
        m.text("REFRIGERATOR", side * 0.1216, a1 / 2 + 0.03, 1.25, 0.018, trim, side)


def ore_car(m, L):
    """A short, heavy ore car: a deep steel hopper on two trucks, its load heaped red."""
    c = common()
    body = material("ore car rust", "#5a2e22", rough=0.6)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    for y in (-L / 2 + 0.07, L / 2 - 0.07):
        truck(m, y, c, top=0.96)
    m.box(-0.11, 0.11, a0 + 0.01, a1 - 0.01, 0.95, 1.45, body, bevel=0.004)
    m.wedge([(-0.06, -0.035, 0.42), (0.06, -0.035, 0.42), (0.06, 0.035, 0.42), (-0.06, 0.035, 0.42)],
            [(-0.105, a0 + 0.015, 0.95), (0.105, a0 + 0.015, 0.95), (0.105, a1 - 0.015, 0.95), (-0.105, a1 - 0.015, 0.95)], body)
    m.box(-0.095, 0.095, a0 + 0.02, a1 - 0.02, 1.4, 1.58, material("iron ore", "#7a3a2a", rough=0.95), bevel=0.02)
    for side in (-1, 1):
        for y in (a0 + 0.04, 0.0, a1 - 0.04):
            m.box(side * 0.112 - 0.004, side * 0.112 + 0.004, y - 0.006, y + 0.006, 0.95, 1.45, body)


def covered_hopper(m, L):
    """A grain car of the 1960s on: a long grey covered hopper with three bays and roof hatches."""
    c = common()
    body = material("hopper grey", "#a8aaa6", rough=0.55)
    dark = material("hopper grey dark", "#7e807c", rough=0.6)
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    for y in (-L / 2 + 0.08, L / 2 - 0.08):
        truck(m, y, c, top=0.91)
    m.box(-0.12, 0.12, a0 + 0.02, a1 - 0.02, 0.9, 1.75, body, bevel=0.01)
    for cy in (-0.11, 0.0, 0.11):
        m.wedge([(-0.05, cy - 0.025, 0.4), (0.05, cy - 0.025, 0.4), (0.05, cy + 0.025, 0.4), (-0.05, cy + 0.025, 0.4)],
                [(-0.115, cy - 0.05, 0.9), (0.115, cy - 0.05, 0.9), (0.115, cy + 0.05, 0.9), (-0.115, cy + 0.05, 0.9)], dark)
    m.box(-0.03, 0.03, a0 + 0.04, a1 - 0.04, 1.75, 1.8, dark)
    for side in (-1, 1):
        for k in range(1, 8):
            y = a0 + (a1 - a0) * k / 8
            m.box(side * 0.1205 - 0.0015, side * 0.1205 + 0.0015, y - 0.003, y + 0.003, 0.92, 1.73, dark)
        m.text("GRAIN", side * 0.1216, 0.0, 1.35, 0.035, material("hopper letters", "#2a2a2a", rough=0.6), side)


def wedge_plow(m, L):
    """A wooden wedge plow: a heavy car with a steel wedge filling the front, flangers, and a cupola for the crew."""
    c = common()
    body = material("plow red", "#8a2a20", rough=0.55)
    blade = material("plow blade", "#2e3134", metal=0.6, rough=0.45)
    a0, a1 = -L / 2 + 0.02, L / 2 - 0.02
    _base(m, L, a0, a1, c)
    m.box(-0.12, 0.12, a0, a1 - 0.17, 0.62, 1.42, body, bevel=0.003)
    m.box(-0.13, 0.13, a0 - 0.01, a1 - 0.16, 1.42, 1.5, c["roof"], bevel=0.004)
    m.box(-0.08, 0.08, a0 + 0.08, a0 + 0.22, 1.5, 1.82, body, bevel=0.002)
    m.box(-0.09, 0.09, a0 + 0.07, a0 + 0.23, 1.82, 1.88, c["roof"], bevel=0.003)
    for side in (-1, 1):
        m.box(side * 0.0807 - 0.0008, side * 0.0807 + 0.0008, a0 + 0.11, a0 + 0.19, 1.58, 1.76, c["glass"])
    # The wedge: from a point down at the rails, out to the car's full width and up to its roof.
    tip = a1 + 0.015
    m.wedge([(-0.005, tip, 0.42), (0.005, tip, 0.42), (0.13, a1 - 0.17, 0.42), (-0.13, a1 - 0.17, 0.42)],
            [(-0.005, a1 - 0.06, 1.5), (0.005, a1 - 0.06, 1.5), (0.13, a1 - 0.17, 1.5), (-0.13, a1 - 0.17, 1.5)], blade)
    for side in (-1, 1):
        m.text(LETTERS, side * 0.1216, a0 / 2 + 0.02, 1.1, 0.03, c["letters"], side)


def rotary_plow(m, L):
    """A steam rotary plow: a long red car with a black roof and stack, and a great fan in a black drum at the
    front, its lip painted yellow so it shows."""
    c = common()
    body = material("rotary red", "#8e2a22", rough=0.45, clearcoat=0.3)
    black = material("rotary black", "#1e1e20", rough=0.55)
    band = material("rotary cream", "#e8dcb8", rough=0.5)
    lip = material("rotary yellow", "#e8b020", rough=0.4)
    fan = material("rotary fan", "#c8ccd0", metal=0.8, rough=0.3)
    a0, a1 = -L / 2 + 0.02, L / 2 - 0.02
    _base(m, L, a0, a1, c)
    m.box(-0.125, 0.125, a0, a1 - 0.14, 0.62, 1.65, body, bevel=0.004)
    m.box(-0.132, 0.132, a0 - 0.01, a1 - 0.13, 1.65, 1.76, black, bevel=0.006)
    m.upright(0, a0 + 0.12, 1.76, 2.1, 0.024, 0.028, black, segments=12)
    m.upright(0, a0 + 0.12, 2.06, 2.12, 0.032, 0.032, black, segments=12)
    for side in (-1, 1):
        m.box(side * 0.1256 - 0.001, side * 0.1256 + 0.001, a0 + 0.01, a1 - 0.15, 1.08, 1.4, band)
        for y in (a0 + 0.06, a0 + 0.16, a0 + 0.26, a1 - 0.22):
            m.box(side * 0.1262 - 0.0008, side * 0.1262 + 0.0008, y - 0.025, y + 0.025, 1.12, 1.36, c["glass"])
        m.box(side * 0.1262 - 0.002, side * 0.1262 + 0.002, a0 + 0.01, a1 - 0.15, 0.66, 0.72, black)
    # The hood: a square front with the snow chute on top, a yellow rim round a dark recess, and the wheel in it:
    # broad pale blades round a steel hub. Radii in tiles, heights in steps.
    m.box(-0.135, 0.135, a1 - 0.14, a1 - 0.004, 0.55, 1.78, black, bevel=0.006)
    m.box(-0.045, 0.045, a1 - 0.1, a1 - 0.03, 1.78, 2.15, black, bevel=0.004)
    m.wedge([(-0.05, a1 - 0.1, 2.15), (0.05, a1 - 0.1, 2.15), (0.05, a1 - 0.03, 2.15), (-0.05, a1 - 0.03, 2.15)],
            [(-0.05, a1 - 0.16, 2.35), (0.05, a1 - 0.16, 2.35), (0.05, a1 - 0.1, 2.35), (-0.05, a1 - 0.1, 2.35)], black)
    m.tube(a1 - 0.006, a1 + 0.002, 0.0, 1.12, 0.132, lip, segments=40)
    m.tube(a1 - 0.004, a1 + 0.004, 0.0, 1.12, 0.12, black, segments=40)
    blades = 10
    for k in range(blades):
        ang = k * 2 * math.pi / blades
        pts = []
        for r, da in ((0.025, -0.16), (0.112, -0.2), (0.112, 0.2), (0.025, 0.16)):
            a = ang + da
            pts.append((r * math.cos(a), r * math.sin(a) / rig.RISE))
        m.wedge([(x, a1 + 0.004, 1.12 + z) for x, z in pts], [(x, a1 + 0.011, 1.12 + z) for x, z in pts], fan)
    m.tube(a1 + 0.004, a1 + 0.016, 0.0, 1.12, 0.03, material("hub", "#e8e8e4", metal=0.9, rough=0.25), segments=20)
    m.text(LETTERS, 0.1266, a0 / 2, 0.92, 0.03, band, 1)
    m.text(LETTERS, -0.1266, a0 / 2, 0.92, 0.03, band, -1)


def coach_streamline(m, L):
    """A lightweight stainless steel coach of the 1950s: fluted sides, a smooth roof and a long band of windows."""
    c = common()
    stainless = material("stainless", "#c4c8cc", metal=0.85, rough=0.28)
    band = material("coach band", "#6a2a26", rough=0.4, clearcoat=0.4)
    a0, a1 = -L / 2 + 0.012, L / 2 - 0.012
    _base(m, L, a0, a1, c)
    m.box(-0.12, 0.12, a0, a1, 0.62, 1.62, stainless, bevel=0.006)
    m.box(-0.115, 0.115, a0, a1, 1.62, 1.78, stainless, bevel=0.02)
    for side in (-1, 1):
        for k in range(14):
            z = 0.66 + k * 0.022
            m.box(side * 0.1205 - 0.001, side * 0.1205 + 0.001, a0 + 0.01, a1 - 0.01, z, z + 0.009, material("fluting", "#9ea2a6", metal=0.9, rough=0.3))
        m.box(side * 0.1206 - 0.001, side * 0.1206 + 0.001, a0 + 0.01, a1 - 0.01, 1.32, 1.36, band)
        n = 10
        for i in range(n):
            y0 = a0 + 0.03 + (a1 - a0 - 0.06) * i / n
            m.box(side * 0.1208 - 0.0008, side * 0.1208 + 0.0008, y0, y0 + (a1 - a0 - 0.06) / n * 0.78, 1.02, 1.3, c["glass"])
        m.text(LETTERS, side * 0.1216, 0, 1.45, 0.03, band, side)


def well_car(m, L):
    """An intermodal well car with two containers stacked in it."""
    c = common()
    frame_mat = material("well car", "#3a3e42", rough=0.6)
    boxes = [material("container red", "#9a3a2a", rough=0.6), material("container blue", "#2a4a7a", rough=0.6)]
    a0, a1 = -L / 2 + 0.01, L / 2 - 0.01
    for y in (-L / 2 + 0.07, L / 2 - 0.07):
        truck(m, y, c)
    for side in (-1, 1):
        m.box(side * 0.105, side * 0.125, a0 + 0.08, a1 - 0.08, 0.35, 0.95, frame_mat, bevel=0.003)
    m.box(-0.125, 0.125, a0, a0 + 0.1, 0.45, 0.95, frame_mat)
    m.box(-0.125, 0.125, a1 - 0.1, a1, 0.45, 0.95, frame_mat)
    for k, (z0, z1) in enumerate(((0.4, 1.45), (1.45, 2.5))):
        m.box(-0.1, 0.1, a0 + 0.11, a1 - 0.11, z0, z1, boxes[k], bevel=0.003)
        for side in (-1, 1):
            for j in range(1, 12):
                y = a0 + 0.11 + (a1 - a0 - 0.22) * j / 12
                m.box(side * 0.1005 - 0.0008, side * 0.1005 + 0.0008, y - 0.003, y + 0.003, z0 + 0.03, z1 - 0.03, material("container ribs", "#555555", rough=0.7))
