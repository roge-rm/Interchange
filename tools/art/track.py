"""Track, foundations, platforms, depots and signals."""
import math
import random

from PIL import Image, ImageDraw

from base import (DCELL, OUTLINE, SS, Frame, box, c, cell, draw, masked, noise_layer, prism, proj, shade)
from ground import CORNERS, height_at, offsets

GRAVEL = [c("#8e877c"), c("#7a7368"), c("#a29b8f"), c("#6c665c")]
TIE = c("#5e4330")
TIE_TOP = c("#76583c")
PLATE = c("#55524c")
RAIL_TOP = c("#e6e2da")
RAIL_SIDE = c("#6d6962")

# Pieces as in the game, in bit order: straight along u, along v, then the
# diagonals cutting off the top, right, bottom and left corners.
PIECES = ["X", "Y", "N", "E", "S", "W"]
EDGE_MID = {"XM": (0.0, 0.5), "XP": (1.0, 0.5), "YM": (0.5, 0.0), "YP": (0.5, 1.0)}
PIECE_EDGES = {"X": ("XM", "XP"), "Y": ("YM", "YP"), "N": ("YM", "XM"), "E": ("YM", "XP"), "S": ("XP", "YP"), "W": ("XM", "YP")}


def strip(a, b, half, offs, t0=0.0, t1=1.0, lift=0.0):
    """A strip of a piece from t0 to t1 along a to b, half wide each side, on ground shaped by offs."""
    ax, ay = a
    bx, by = b
    length = math.hypot(bx - ax, by - ay)
    nx, ny = -(by - ay) / length, (bx - ax) / length
    sx, sy = ax + (bx - ax) * t0, ay + (by - ay) * t0
    ex, ey = ax + (bx - ax) * t1, ay + (by - ay) * t1
    pts = [(sx + nx * half, sy + ny * half), (ex + nx * half, ey + ny * half), (ex - nx * half, ey - ny * half), (sx - nx * half, sy - ny * half)]
    return [proj(u, v, height_at(offs, u, v) + lift) for u, v in pts]


_gravel = None


def track(piece, surface_shape):
    global _gravel
    if _gravel is None:
        _gravel = noise_layer(GRAVEL, 5000, [0.45, 0.25, 0.2, 0.1], block=SS)
    offs = offsets(surface_shape)
    a = EDGE_MID[PIECE_EDGES[piece][0]]
    b = EDGE_MID[PIECE_EDGES[piece][1]]
    length = math.hypot(b[0] - a[0], b[1] - a[1])
    img = cell()
    # The ballast, darker along its shoulders.
    img.alpha_composite(masked(_gravel, strip(a, b, 0.23, offs), 0.85))
    img.alpha_composite(masked(_gravel, strip(a, b, 0.19, offs, lift=0.04)))
    d = ImageDraw.Draw(img)
    ties = max(4, round(length * 9))
    for i in range(ties):
        t = (i + 0.5) / ties
        dt = 0.026 / length
        poly = strip(a, b, 0.165, offs, t - dt, t + dt, lift=0.06)
        d.polygon(poly, fill=TIE, outline=shade(TIE, 0.7))
        d.line([poly[0], poly[1]], TIE_TOP, width=SS)
    nx, ny = -(b[1] - a[1]) / length, (b[0] - a[0]) / length
    for side in (-1, 1):
        o = 0.095 * side
        p0 = (a[0] + nx * o, a[1] + ny * o)
        p1 = (b[0] + nx * o, b[1] + ny * o)
        q0 = proj(p0[0], p0[1], height_at(offs, *p0) + 0.1)
        q1 = proj(p1[0], p1[1], height_at(offs, *p1) + 0.1)
        d.line([q0[0], q0[1] + 2 * SS, q1[0], q1[1] + 2 * SS], OUTLINE, width=3 * SS)
        d.line([q0[0], q0[1] + SS, q1[0], q1[1] + SS], RAIL_SIDE, width=2 * SS)
        d.line([q0, q1], RAIL_TOP, width=SS + 1)
    return img


# ---- foundations -------------------------------------------------------------------

STONE_LIT = c("#b4ab98")
STONE_DARK = c("#8c8472")
MORTAR = c("#6e675a")


def wall_face(img, a_top, b_top, a_bot, b_bot, lit_side):
    poly = [a_top, b_top, b_bot, a_bot]
    if abs(a_top[1] - a_bot[1]) < 1 and abs(b_top[1] - b_bot[1]) < 1:
        return
    base = STONE_LIT if lit_side else STONE_DARK
    layer = Image.new("RGBA", DCELL, base)
    d = ImageDraw.Draw(layer)
    rng = random.Random(int(a_top[0] * 7 + b_top[1]))
    course = 10 * SS
    for k in range(0, 16):
        y0, y1 = a_top[1] + k * course, b_top[1] + k * course
        d.line([a_top[0], y0, b_top[0], y1], MORTAR, width=SS)
        n = 6
        for j in range(n + 1):
            t = (j + (0.5 if k % 2 else 0)) / n
            x = a_top[0] + (b_top[0] - a_top[0]) * t
            y = y0 + (y1 - y0) * t
            d.line([x, y, x, y + course], MORTAR, width=SS)
            # A few stones a shade off the rest.
            if rng.random() < 0.25:
                x2 = a_top[0] + (b_top[0] - a_top[0]) * min(1, t + 1 / n)
                y2 = y0 + (y1 - y0) * min(1, t + 1 / n)
                d.polygon([(x + SS, y + SS), (x2 - SS, y2 + SS), (x2 - SS, y2 + course - SS), (x + SS, y + course - SS)], fill=shade(base, rng.choice([0.92, 1.06])))
    img.alpha_composite(masked(layer, poly))
    ImageDraw.Draw(img).line(poly + [poly[0]], OUTLINE, width=SS)


def walls(ground_shape, surface_shape):
    g = offsets(ground_shape)
    s = offsets(surface_shape)
    img = cell()
    for a, b, lit_side in ((1, 2, False), (3, 2, True)):
        (au, av), (bu, bv) = CORNERS[a], CORNERS[b]
        wall_face(img, proj(au, av, s[a]), proj(bu, bv, s[b]), proj(au, av, g[a]), proj(bu, bv, g[b]), lit_side)
    return img


# ---- platforms, depots, signals ---------------------------------------------------

CONCRETE = c("#cbc5b7")
EDGE_STONE = c("#a7a092")
TIMBER = c("#8a5a36")
CANOPY = c("#7d3f2c")
POST = c("#3f3f3f")
BRICK = c("#9c5a3c")
BRICK_DARK = c("#7d4630")
SLATE = c("#56606c")
DOORWAY = c("#24201c")
WINDOW = c("#3a4654")


def swap(faces, along_u):
    if along_u:
        return faces
    return [([(v, u, h) for u, v, h in pts], col, (n[1], n[0], n[2]), o) for pts, col, n, o in faces]


def station(along_u):
    img = track("X" if along_u else "Y", 0)
    faces = box(0.0, 1.0, 0.0, 0.27, 0.0, 0.4, CONCRETE)
    faces += box(0.0, 1.0, 0.24, 0.27, 0.0, 0.42, EDGE_STONE)
    faces += box(0.0, 1.0, 0.73, 1.0, 0.0, 0.4, CONCRETE)
    faces += box(0.0, 1.0, 0.73, 0.76, 0.0, 0.42, EDGE_STONE)
    # A timber canopy on posts over the far platform, with a bench under it.
    for u in (0.15, 0.5, 0.85):
        faces += box(u - 0.018, u + 0.018, 0.1, 0.136, 0.4, 2.6, POST)
    faces += prism([(0.03, -0.02), (0.97, -0.02), (0.97, 0.3), (0.03, 0.3)], 2.6, 2.8, TIMBER)
    faces += box(0.04, 0.96, -0.02, 0.15, 2.8, 3.05, CANOPY, top=shade(CANOPY, 1.08))
    faces += box(0.04, 0.96, 0.15, 0.3, 2.8, 2.98, CANOPY, top=shade(CANOPY, 0.95))
    faces += box(0.35, 0.65, 0.05, 0.1, 0.4, 0.75, TIMBER)
    draw(img, swap(faces, along_u))
    return img


def depot(edge):
    """A brick engine house over the track, its doors at view edge XM, XP, YM or YP."""
    along_u = edge in ("XM", "XP")
    img = cell()
    if along_u:
        u0, u1 = (0.0, 0.86) if edge == "XP" else (0.14, 1.0)
        faces = box(u0, u1, 0.12, 0.88, 0.0, 2.2, BRICK)
        faces += box(u0, u1, 0.12, 0.88, 2.2, 2.35, BRICK_DARK)
        m = 0.5
        roof = [
            ([(u0, 0.08, 2.35), (u1, 0.08, 2.35), (u1, m, 3.6), (u0, m, 3.6)], SLATE, (0, -1, 0.6), OUTLINE),
            ([(u0, 0.92, 2.35), (u0, m, 3.6), (u1, m, 3.6), (u1, 0.92, 2.35)], SLATE, (0, 1, 0.6), OUTLINE),
            ([(u0, 0.12, 2.35), (u0, m, 3.6), (u0, 0.88, 2.35)], BRICK, (-1, 0, 0), OUTLINE),
            ([(u1, 0.12, 2.35), (u1, 0.88, 2.35), (u1, m, 3.6)], BRICK, (1, 0, 0), OUTLINE),
        ]
        faces += roof
        faces += box(u0 + 0.2, u0 + 0.3, 0.46, 0.54, 3.4, 4.0, BRICK_DARK)
    else:
        v0, v1 = (0.0, 0.86) if edge == "YP" else (0.14, 1.0)
        faces = box(0.12, 0.88, v0, v1, 0.0, 2.2, BRICK)
        faces += box(0.12, 0.88, v0, v1, 2.2, 2.35, BRICK_DARK)
        m = 0.5
        faces += [
            ([(0.08, v0, 2.35), (m, v0, 3.6), (m, v1, 3.6), (0.08, v1, 2.35)], SLATE, (-1, 0, 0.6), OUTLINE),
            ([(0.92, v0, 2.35), (0.92, v1, 2.35), (m, v1, 3.6), (m, v0, 3.6)], SLATE, (1, 0, 0.6), OUTLINE),
            ([(0.12, v0, 2.35), (0.88, v0, 2.35), (m, v0, 3.6)], BRICK, (0, -1, 0), OUTLINE),
            ([(0.12, v1, 2.35), (m, v1, 3.6), (0.88, v1, 2.35)], BRICK, (0, 1, 0), OUTLINE),
        ]
        faces += box(0.46, 0.54, v0 + 0.2, v0 + 0.3, 3.4, 4.0, BRICK_DARK)
    draw(img, faces)
    d = ImageDraw.Draw(img)
    # Windows down the long wall facing the camera, and the doorway if the doors face it.
    if along_u:
        for k in range(3):
            u = (u0 + 0.15) + k * (u1 - u0 - 0.3) / 2
            d.polygon([proj(u - 0.05, 0.88, 0.9), proj(u + 0.05, 0.88, 0.9), proj(u + 0.05, 0.88, 1.7), proj(u - 0.05, 0.88, 1.7)], fill=WINDOW, outline=OUTLINE)
        if edge == "XP":
            d.polygon([proj(0.86, v, h) for v, h in ((0.3, 0), (0.7, 0), (0.7, 1.9), (0.3, 1.9))], fill=DOORWAY, outline=OUTLINE)
    else:
        for k in range(3):
            v = (v0 + 0.15) + k * (v1 - v0 - 0.3) / 2
            d.polygon([proj(0.88, v - 0.05, 0.9), proj(0.88, v + 0.05, 0.9), proj(0.88, v + 0.05, 1.7), proj(0.88, v - 0.05, 1.7)], fill=WINDOW, outline=OUTLINE)
        if edge == "YP":
            d.polygon([proj(u, 0.86, h) for u, h in ((0.3, 0), (0.7, 0), (0.7, 1.9), (0.3, 1.9))], fill=DOORWAY, outline=OUTLINE)
    return img


def signal(red):
    """A semaphore on a post: the arm level for stop, raised for clear, with its lamp."""
    img = cell()
    f = Frame(0)
    faces = f.upright(0, 0, 0, 4.2, 0.022, 0.016, c("#d9d4cc"), n=8)
    faces += f.upright(0, 0, 4.2, 4.45, 0.03, 0.0, c("#2a2a2a"), n=8)
    draw(img, faces)
    d = ImageDraw.Draw(img)
    x, y = proj(0.5, 0.5, 3.8)
    if red:
        arm = [(x, y - 3 * SS), (x - 26 * SS, y - 3 * SS), (x - 26 * SS, y + 3 * SS), (x, y + 3 * SS)]
    else:
        arm = [(x, y - 3 * SS), (x - 20 * SS, y - 17 * SS), (x - 23 * SS, y - 12 * SS), (x, y + 3 * SS)]
    d.polygon(arm, fill=c("#c8322a"), outline=OUTLINE)
    lamp = c("#ff4a3a") if red else c("#4aff6e")
    lx, ly = x + 4 * SS, y + 6 * SS
    d.ellipse([lx - 4 * SS, ly - 4 * SS, lx + 4 * SS, ly + 4 * SS], fill=c("#1e1e1e"), outline=OUTLINE)
    d.ellipse([lx - 2.5 * SS, ly - 2.5 * SS, lx + 2.5 * SS, ly + 2.5 * SS], fill=lamp)
    return img
