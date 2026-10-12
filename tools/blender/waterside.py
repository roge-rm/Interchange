"""Docks, shipyards and locks, and what stands at airports: hangars, terminals, a control tower, a gravel strip's hut and
a seaplane base's float dock. Each is built on one tile in its own terms, x across and y along, z in steps, and those
with a front are built facing view edge [edge] (XM, XP, YM or YP), as the garage is.
"""
import math

from parts import material


def _b(m, edge):
    """A box maker that turns the model so its front, at y = -0.5, faces [edge]."""
    along_u = edge in ("XM", "XP")
    flip = edge in ("XP", "YP")

    def b(x0, x1, y0, y1, z0, z1, mat, bevel=0.0):
        if flip:
            y0, y1 = -y1, -y0
        if along_u:
            m.box(x0, x1, y0, y1, z0, z1, mat, bevel)
        else:
            m.box(y0, y1, x0, x1, z0, z1, mat, bevel)
    return b


def _pt(edge, x, y):
    """A point in the turned frame, as (x, y) in the model's terms."""
    if edge in ("XP", "YP"):
        y = -y
    return (x, y) if edge in ("XM", "XP") else (y, x)


def dock(m, edge):
    """A wharf: a concrete quay out to the water at [edge], bollards along its edge, a transit shed behind and a crane."""
    b = _b(m, edge)
    concrete = material("quay", "#9a968e", rough=0.85)
    shed = material("transit shed", "#8a4a32", rough=0.75)
    roof = material("shed roof", "#4e5458", metal=0.4, rough=0.5)
    dark = material("bollard", "#2a2a2c", rough=0.6)
    crane = material("crane", "#d8a020", metal=0.3, rough=0.5)
    b(-0.48, 0.48, -0.5, 0.45, 0.0, 0.55, concrete, 0.006)
    for x in (-0.35, -0.1, 0.15, 0.4):
        b(x - 0.015, x + 0.015, -0.47, -0.44, 0.55, 0.7, dark, 0.004)
    b(-0.4, 0.4, 0.0, 0.42, 0.55, 1.75, shed, 0.004)
    b(-0.42, 0.42, -0.02, 0.44, 1.75, 1.9, roof, 0.004)
    for x in (-0.25, 0.0, 0.25):
        b(x - 0.08, x + 0.08, -0.004, 0.004, 0.55, 1.45, material("shed door", "#5a3a2a", rough=0.7))
    # The crane on its rails along the quay, its jib out over the water.
    b(-0.2, -0.12, -0.45, -0.37, 0.55, 2.6, crane)
    b(-0.19, -0.13, -0.5, -0.2, 2.5, 2.65, crane)
    b(-0.2, -0.12, -0.25, -0.15, 2.0, 2.7, material("crane cab", "#e8e4dc", rough=0.5))


def shipyard(m, edge):
    """A shipyard: a tall building shed open to the water at [edge], a slipway running down into it, and a gantry crane."""
    b = _b(m, edge)
    wall = material("shipyard wall", "#7a7e82", metal=0.3, rough=0.6)
    roof = material("shipyard roof", "#3e4448", metal=0.4, rough=0.5)
    slip = material("slipway", "#8a8680", rough=0.85)
    crane = material("gantry", "#c8302a", metal=0.3, rough=0.5)
    b(-0.3, 0.3, -0.5, 0.4, 0.0, 0.35, slip, 0.004)
    for side in (-1, 1):
        b(side * 0.42 - 0.04, side * 0.42 + 0.04, -0.2, 0.45, 0.0, 2.6, wall, 0.004)
    b(-0.46, 0.46, -0.22, 0.47, 2.6, 2.75, roof, 0.006)
    b(-0.42, 0.42, 0.42, 0.46, 0.0, 2.6, wall)
    for side in (-1, 1):
        b(side * 0.47 - 0.02, side * 0.47 + 0.02, -0.48, -0.43, 0.0, 3.3, crane)
    b(-0.49, 0.49, -0.49, -0.42, 3.2, 3.4, crane)


def lock(m, edge):
    """A lock rising to [edge]: concrete walls either side of the chamber, gates at both ends and halfway, the water low
    in the chamber's lower half and high in its upper half."""
    b = _b(m, edge)
    concrete = material("lock wall", "#a8a49c", rough=0.85)
    gate = material("lock gate", "#3a3026", rough=0.7)
    water = material("lock water", "#3a5a6e", rough=0.15)
    rail = material("lock rail", "#d8d8d4", metal=0.3, rough=0.4)
    # The chamber runs along y, upper end at y = -0.5 (edge), lower at +0.5.
    for side in (-1, 1):
        b(side * 0.42 - 0.07, side * 0.42 + 0.07, -0.5, 0.5, 0.0, 1.35, concrete, 0.004)
        b(side * 0.42 - 0.07, side * 0.42 - 0.064, -0.5, 0.5, 1.35, 1.6, rail)
    b(-0.35, 0.35, 0.0, 0.5, 0.0, 0.2, water)
    b(-0.35, 0.35, -0.5, 0.0, 0.0, 1.05, water)
    for y in (-0.48, 0.0, 0.48):
        b(-0.35, 0.35, y - 0.025, y + 0.025, 0.0, 1.3, gate, 0.003)
        b(-0.4, 0.4, y - 0.03, y + 0.03, 1.3, 1.36, rail)


def seaplane_dock(m, edge):
    """A seaplane base: a float dock out over the water at [edge], a fuel shed and drums, and a windsock."""
    b = _b(m, edge)
    wood = material("float dock", "#8a6a44", rough=0.85)
    shed = material("fuel shed", "#b8322a", rough=0.7)
    roof = material("fuel shed roof", "#3a3a3c", rough=0.6)
    drum = material("fuel drum", "#2a5a3a", rough=0.6)
    # The float dock runs out to the tile's edge over the water, a T at its end.
    b(-0.07, 0.07, -0.5, 0.1, 0.0, 0.3, wood, 0.003)
    b(-0.32, 0.32, -0.5, -0.4, 0.0, 0.3, wood, 0.003)
    b(-0.32, 0.0, 0.1, 0.42, 0.3, 1.2, shed, 0.004)
    b(-0.34, 0.02, 0.08, 0.44, 1.2, 1.32, roof, 0.004)
    for k in range(3):
        x = 0.1 + k * 0.08
        x0, y0 = _pt(edge, x, 0.3)
        m.upright(x0, y0, 0.3, 0.7, 0.03, 0.03, drum)
    wx, wy = _pt(edge, 0.35, 0.0)
    m.rod((wx, wy, 0.3), (wx, wy, 2.4), 0.006, material("sock pole", "#d8d8d4", rough=0.5))
    sx, sy = _pt(edge, 0.35, 0.12)
    m.rod((wx, wy, 2.35), (sx, sy, 2.25), 0.022, material("windsock", "#e86a1a", rough=0.6))


def hangar(m, edge, big):
    """A hangar with its doors to [edge]: a curved roof over a long shed, the doors most of its front."""
    b = _b(m, edge)
    wall = material("hangar wall", "#b8b4ac", rough=0.8)
    roof = material("hangar roof", "#5a6066", metal=0.5, rough=0.45)
    door = material("hangar door", "#7a8890", metal=0.4, rough=0.45)
    w = 0.46 if big else 0.4
    tall = 2.4 if big else 1.8
    b(-w, w, -0.42, 0.42, 0.0, tall, wall, 0.004)
    # The barrel roof, in steps across.
    n = 8
    for k in range(n):
        a0 = math.pi * k / n
        a1 = math.pi * (k + 1) / n
        x0, x1 = -w * math.cos(a0), -w * math.cos(a1)
        z = tall + math.sin((a0 + a1) / 2) * (0.9 if big else 0.7)
        b(min(x0, x1), max(x0, x1), -0.44, 0.44, tall, z, roof)
    b(-w * 0.85, w * 0.85, -0.43, -0.42, 0.0, tall * 0.92, door)
    for k in range(1, 6):
        x = -w * 0.85 + k * w * 1.7 / 6
        b(x - 0.004, x + 0.004, -0.435, -0.42, 0.0, tall * 0.92, wall)


def terminal(m, edge, size):
    """A terminal facing [edge]: a small one-storey building, a two-storey terminal with a glass front, or a big
    terminal with a jet bridge out to the apron."""
    b = _b(m, edge)
    wall = material("terminal wall", "#d8d4cc", rough=0.7)
    glass = material("terminal glass", "#3a5a6e", rough=0.1, metal=0.2)
    roof = material("terminal roof", "#5a5e62", rough=0.6)
    tall = {"small": 1.1, "mid": 2.0, "big": 2.6}[size]
    deep = {"small": 0.25, "mid": 0.35, "big": 0.42}[size]
    b(-0.48, 0.48, -deep, 0.42, 0.0, tall, wall, 0.004)
    b(-0.5, 0.5, -deep - 0.04, 0.44, tall, tall + 0.15, roof, 0.004)
    b(-0.46, 0.46, -deep - 0.004, -deep + 0.002, 0.25, tall - 0.15, glass)
    if size == "big":
        jet = material("jet bridge", "#a8acb0", metal=0.4, rough=0.5)
        b(-0.05, 0.05, -0.85, -deep, 0.9, 1.4, jet, 0.004)
        b(-0.06, 0.06, -0.8, -0.74, 0.0, 0.9, jet)


def tower(m):
    """A control tower: a concrete shaft and a glass cab on top."""
    concrete = material("tower", "#c8c4bc", rough=0.8)
    glass = material("tower glass", "#2e4a5e", rough=0.1, metal=0.2)
    roof = material("tower roof", "#3a3a3c", rough=0.6)
    m.box(-0.3, 0.3, -0.3, 0.3, 0.0, 1.0, concrete, 0.004)
    m.upright(0.0, 0.0, 1.0, 4.2, 0.09, 0.08, concrete, segments=16)
    m.upright(0.0, 0.0, 4.2, 4.8, 0.17, 0.2, glass, segments=16)
    m.upright(0.0, 0.0, 4.8, 4.95, 0.21, 0.12, roof, segments=16)
    m.rod((0.0, 0.0, 4.95), (0.0, 0.0, 5.6), 0.005, roof)


def strip_hut(m):
    """A gravel strip's hut, fuel drums and a windsock."""
    wood = material("hut wood", "#7a5a3a", rough=0.85)
    roof = material("hut roof", "#3a3a3c", rough=0.6)
    drum = material("strip drum", "#c8302a", rough=0.6)
    m.box(0.15, 0.42, 0.15, 0.42, 0.0, 0.8, wood, 0.004)
    m.box(0.13, 0.44, 0.13, 0.44, 0.8, 0.92, roof, 0.004)
    for k in range(3):
        m.upright(0.05 + k * 0.07, 0.42, 0.0, 0.4, 0.028, 0.028, drum)
    m.rod((-0.35, 0.35, 0.0), (-0.35, 0.35, 2.2), 0.006, material("sock pole", "#d8d8d4", rough=0.5))
    m.rod((-0.35, 0.35, 2.15), (-0.2, 0.35, 2.05), 0.022, material("windsock", "#e86a1a", rough=0.6))
