"""Bridges and tunnel portals, in the same terms as scenery.py: x across, y along (the game's u), z in steps,
on the middle of the tile.

A deck is one tile of bridge with its rails on top at z 0, so a train at the deck's height runs on it. A pier
is one step of what holds the deck up, from z 0 to 1, stacked from the ground to under the deck. A portal is a
tunnel's mouth in the hillside, its track at z 0.
"""
import math

import parts
from parts import material

STYLES = ["trestle", "truss", "concrete"]


def _boxer(m, along_u):
    def b(x0, x1, y0, y1, z0, z1, mat, bevel=0.0):
        if along_u:
            m.box(x0, x1, y0, y1, z0, z1, mat, bevel)
        else:
            m.box(y0, y1, x0, x1, z0, z1, mat, bevel)
    return b


def _rod(m, along_u, a, b, r, mat):
    if along_u:
        m.rod(a, b, r, mat)
    else:
        m.rod((a[1], a[0], a[2]), (b[1], b[0], b[2]), r, mat)


def _rails(m, along_u, z, tie_mat):
    """Open ties and two rails along the tile, their tops at z + 0.42 as on track."""
    b = _boxer(m, along_u)
    rail = material("rail", "#6a6662", metal=0.9, rough=0.45)
    head = material("rail head", "#d8d4cc", metal=1.0, rough=0.2)
    for i in range(9):
        y = -0.5 + (i + 0.5) / 9
        b(-0.17, 0.17, y - 0.024, y + 0.024, z + 0.12, z + 0.2, tie_mat)
    for side in (-1, 1):
        b(side * 0.095 - 0.012, side * 0.095 + 0.012, -0.5, 0.5, z + 0.2, z + 0.36, rail)
        b(side * 0.095 - 0.009, side * 0.095 + 0.009, -0.5, 0.5, z + 0.36, z + 0.42, head)


def deck(m, style, along_u):
    b = _boxer(m, along_u)
    tie = material("tie", "#4e3626", rough=0.9)
    if style == "trestle":
        timber = material("trestle timber", "#7a5a3e", rough=0.9)
        for x in (-0.12, 0.12):
            b(x - 0.035, x + 0.035, -0.5, 0.5, -0.45, 0.12, timber, 0.003)
        b(-0.2, 0.2, -0.5, 0.5, -0.5, -0.42, timber)
        _rails(m, along_u, 0.0, tie)
        # Guard timbers along the tie ends.
        for side in (-1, 1):
            b(side * 0.2 - 0.02, side * 0.2 + 0.02, -0.5, 0.5, 0.12, 0.24, timber)
    elif style == "truss":
        steel = material("truss steel", "#3e4a52", metal=0.7, rough=0.5)
        for side in (-1, 1):
            x = side * 0.27
            b(x - 0.025, x + 0.025, -0.5, 0.5, -0.3, 0.05, steel, 0.002)
            b(x - 0.03, x + 0.03, -0.5, 0.5, 2.5, 2.65, steel, 0.002)
            # A Pratt truss: posts at each end and the middle, diagonals sloping down to the middle.
            for y in (-0.5, 0.0, 0.5):
                _rod(m, along_u, (x, y, 0.0), (x, y, 2.55), 0.016, steel)
            _rod(m, along_u, (x, -0.5, 2.55), (x, 0.0, 0.0), 0.012, steel)
            _rod(m, along_u, (x, 0.5, 2.55), (x, 0.0, 0.0), 0.012, steel)
        # Floor beams under the ties, and bracing across the top.
        for y in (-0.45, 0.0, 0.45):
            b(-0.3, 0.3, y - 0.02, y + 0.02, -0.2, 0.1, steel)
        for y in (-0.5, 0.5):
            b(-0.29, 0.29, y - 0.015, y + 0.015, 2.5, 2.6, steel)
        _rod(m, along_u, (-0.27, -0.5, 2.6), (0.27, 0.5, 2.6), 0.01, steel)
        _rails(m, along_u, 0.0, tie)
    else:
        concrete = material("bridge concrete", "#a8a49c", rough=0.85)
        ballast = material("deck ballast", "#6e6a64", rough=1.0)
        b(-0.26, 0.26, -0.5, 0.5, -0.7, 0.02, concrete, 0.004)
        b(-0.2, 0.2, -0.5, 0.5, 0.02, 0.12, ballast)
        for side in (-1, 1):
            b(side * 0.26 - 0.035, side * 0.26, -0.5, 0.5, 0.0, 0.45, concrete, 0.003)
        _rails(m, along_u, 0.0, tie)


def pier(m, style, along_u):
    b = _boxer(m, along_u)
    if style == "trestle":
        timber = material("trestle timber", "#7a5a3e", rough=0.9)
        # A bent of four posts, the outer ones battered, braced across.
        for x0, x1 in ((-0.24, -0.2), (-0.08, -0.08), (0.08, 0.08), (0.24, 0.2)):
            _rod(m, along_u, (x0, 0.0, 0.0), (x1, 0.0, 1.0), 0.022, timber)
        _rod(m, along_u, (-0.23, 0.0, 0.1), (0.21, 0.0, 0.9), 0.012, timber)
        _rod(m, along_u, (0.23, 0.0, 0.1), (-0.21, 0.0, 0.9), 0.012, timber)
        b(-0.25, 0.25, -0.025, 0.025, 0.92, 1.0, timber)
    elif style == "truss":
        stone = material("pier stone", "#8a8478", rough=0.9)
        b(-0.3, 0.3, -0.16, 0.16, 0.0, 1.0, stone, 0.006)
        # Courses of the masonry.
        dark = material("pier joints", "#5e5a52", rough=1.0)
        for z in (0.33, 0.66):
            b(-0.302, 0.302, -0.162, 0.162, z, z + 0.015, dark)
    else:
        concrete = material("bridge concrete", "#a8a49c", rough=0.85)
        b(-0.11, 0.11, -0.11, 0.11, 0.0, 1.0, concrete, 0.006)


def portal(m, edge):
    """A stone tunnel mouth at the inner half of the tile, opening towards view edge [edge]."""
    stone = material("portal stone", "#8e877a", rough=0.9)
    dark = material("tunnel dark", "#0c0b0a", rough=1.0)
    cap = material("portal coping", "#6e685e", rough=0.85)
    along_u = edge in ("XM", "XP")
    out = 1 if edge in ("XP", "YP") else -1
    b = _boxer(m, along_u)
    # The facade stands across the track a little in from the tile's middle, towards the hill.
    y = -out * 0.1
    b(-0.5, 0.5, y - 0.06, y + 0.06, 0.0, 3.2, stone, 0.004)
    b(-0.52, 0.52, y - 0.08, y + 0.08, 3.2, 3.4, cap, 0.003)
    # The arched opening, dark, cut as a box and a half round on top.
    b(-0.24, 0.24, y - 0.07, y + 0.07, 0.0, 1.6, dark)
    steps = 10
    for k in range(steps):
        a0 = math.pi * k / steps
        a1 = math.pi * (k + 1) / steps
        xa, xb = 0.24 * math.cos(a0), 0.24 * math.cos(a1)
        zc = 1.6 + 1.4 * max(math.sin(a0), math.sin(a1))
        b(min(xa, xb), max(xa, xb), y - 0.07, y + 0.07, 1.6, zc, dark)
    # Wing walls back into the hill.
    for side in (-1, 1):
        b(side * 0.5 - 0.06, side * 0.5 + 0.02, min(y, -out * 0.5), max(y, -out * 0.5), 0.0, 3.2, stone, 0.004)


def shed(m, along_u):
    """A timber snow shed over the track: a solid cribbed wall on the uphill side, posts on the other, and a roof
    sloping down off the hill so slides pass over it."""
    b = _boxer(m, along_u)
    timber = material("shed timber", "#6e5236", rough=0.9)
    dark = material("shed timber dark", "#4e3a28", rough=0.9)
    roof = material("shed roof", "#5a4634", rough=0.85)
    b(-0.34, -0.26, -0.5, 0.5, 0.0, 2.9, dark, 0.004)
    for k in range(6):
        z = 0.2 + k * 0.45
        b(-0.345, -0.255, -0.5, 0.5, z, z + 0.04, timber)
    for y in (-0.375, -0.125, 0.125, 0.375):
        b(0.25, 0.29, y - 0.025, y + 0.025, 0.0, 2.55, timber, 0.003)
        _rod(m, along_u, (0.27, y, 1.6), (0.0, y, 2.6), 0.012, timber)
    # The roof, a slope of planks from over the wall down past the posts.
    for k in range(10):
        x0 = -0.36 + k * 0.069
        b(x0, x0 + 0.07, -0.5, 0.5, 2.95 - k * 0.045, 3.03 - k * 0.045, roof)
