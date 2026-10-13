"""Town buildings, each on its own lot, built facing +y (the game's +u) with
its front to the road, and turned by place() to face any edge.

In the same terms as the rest: x across, y along, z in steps, the lot
running from -0.5 to 0.5 each way. A storey is STOREY steps, about three
metres; a lot is about 25 metres across. The kinds are those in
packs/base/houses.json, by id.
"""
import parts
import scenery
from parts import material

STOREY = 1.25
WINDOW_W = 0.055
WINDOW_H = 0.62


def slab(m, pts, thick, mat):
    """A flat plate through the points, each (x, y, z in steps), thick steps through."""
    m.wedge(pts, [(x, y, z + thick) for x, y, z in pts], mat)


def gable(m, x0, x1, y0, y1, eave, ridge, roof, wall, along_x=True, over=0.03, thick=0.08):
    """A pitched roof over x0..x1 by y0..y1 from the eaves up to its ridge, with the gable ends walled in."""
    if along_x:
        yc = (y0 + y1) / 2
        for ya in (y0 - over, y1 + over):
            slab(m, [(x0 - over, ya, eave), (x1 + over, ya, eave), (x1 + over, yc, ridge), (x0 - over, yc, ridge)], thick, roof)
        for x in (x0, x1):
            m.wedge([(x - 0.003, y0, eave), (x - 0.003, y1, eave), (x - 0.003, yc, ridge)], [(x + 0.003, y0, eave), (x + 0.003, y1, eave), (x + 0.003, yc, ridge)], wall)
    else:
        xc = (x0 + x1) / 2
        for xa in (x0 - over, x1 + over):
            slab(m, [(xa, y0 - over, eave), (xa, y1 + over, eave), (xc, y1 + over, ridge), (xc, y0 - over, ridge)], thick, roof)
        for y in (y0, y1):
            m.wedge([(x0, y - 0.003, eave), (x1, y - 0.003, eave), (xc, y - 0.003, ridge)], [(x0, y + 0.003, eave), (x1, y + 0.003, eave), (xc, y + 0.003, ridge)], wall)


def hip(m, x0, x1, y0, y1, eave, ridge, roof, over=0.03, thick=0.08):
    """A hipped roof, sloping on all four sides to a short ridge along the longer side."""
    x0, x1, y0, y1 = x0 - over, x1 + over, y0 - over, y1 + over
    inset = min(x1 - x0, y1 - y0) / 2
    if x1 - x0 >= y1 - y0:
        yc = (y0 + y1) / 2
        a, b = x0 + inset, x1 - inset
        slab(m, [(x0, y0, eave), (x1, y0, eave), (b, yc, ridge), (a, yc, ridge)], thick, roof)
        slab(m, [(x1, y1, eave), (x0, y1, eave), (a, yc, ridge), (b, yc, ridge)], thick, roof)
        slab(m, [(x0, y1, eave), (x0, y0, eave), (a, yc, ridge)], thick, roof)
        slab(m, [(x1, y0, eave), (x1, y1, eave), (b, yc, ridge)], thick, roof)
    else:
        xc = (x0 + x1) / 2
        a, b = y0 + inset, y1 - inset
        slab(m, [(x0, y0, eave), (x0, y1, eave), (xc, b, ridge), (xc, a, ridge)], thick, roof)
        slab(m, [(x1, y1, eave), (x1, y0, eave), (xc, a, ridge), (xc, b, ridge)], thick, roof)
        slab(m, [(x1, y0, eave), (x0, y0, eave), (xc, a, ridge)], thick, roof)
        slab(m, [(x0, y1, eave), (x1, y1, eave), (xc, b, ridge)], thick, roof)


def windows(m, x0, x1, y0, y1, z0, storeys, per_front, per_side, glass, frame, skip_door=False, w=WINDOW_W, h=WINDOW_H, sill=0.35):
    """Windows on all four walls of a box, storey by storey, set a little proud with a frame round each."""
    for s in range(storeys):
        z = z0 + s * STOREY + sill
        for k in range(per_front):
            x = x0 + (k + 0.5) * (x1 - x0) / per_front
            for y, d in ((y1, 1), (y0, -1)):
                if skip_door and s == 0 and d == 1 and k == per_front // 2:
                    continue
                m.box(x - w / 2 - 0.006, x + w / 2 + 0.006, y + d * 0.002 - 0.003, y + d * 0.002 + 0.003, z - 0.05, z + h + 0.05, frame)
                m.box(x - w / 2, x + w / 2, y + d * 0.005 - 0.003, y + d * 0.005 + 0.003, z, z + h, glass)
        for k in range(per_side):
            y = y0 + (k + 0.5) * (y1 - y0) / per_side
            for x, d in ((x1, 1), (x0, -1)):
                m.box(x + d * 0.002 - 0.003, x + d * 0.002 + 0.003, y - w / 2 - 0.006, y + w / 2 + 0.006, z - 0.05, z + h + 0.05, frame)
                m.box(x + d * 0.005 - 0.003, x + d * 0.005 + 0.003, y - w / 2, y + w / 2, z, z + h, glass)


def door(m, x, y, mat, frame, w=0.05, h=0.95):
    """A front door in the wall at y, its middle at x."""
    m.box(x - w / 2 - 0.008, x + w / 2 + 0.008, y - 0.002, y + 0.006, 0, h + 0.07, frame)
    m.box(x - w / 2, x + w / 2, y, y + 0.009, 0, h, mat)


def path(m, x, y0, y1, w=0.05):
    """A walk from the front door out to the road."""
    m.box(x - w / 2, x + w / 2, y0, y1, 0, 0.03, material("walk", "#b8b2a6", rough=0.9))


def chimney(m, x, y, z0, z1, mat, w=0.035):
    m.box(x - w, x + w, y - w, y + w, z0, z1, mat, 0.002)
    m.box(x - w - 0.005, x + w + 0.005, y - w - 0.005, y + w + 0.005, z1, z1 + 0.08, mat)


def porch(m, x0, x1, y0, y1, z, roof, floor, post, posts=4):
    """A porch across the front: a deck, posts and a roof sloping out."""
    m.box(x0, x1, y0, y1, 0, 0.18, floor, 0.002)
    for k in range(posts):
        x = x0 + 0.01 + k * (x1 - x0 - 0.02) / (posts - 1)
        m.box(x - 0.006, x + 0.006, y1 - 0.016, y1 - 0.004, 0.18, z, post)
    slab(m, [(x0 - 0.01, y0, z + 0.25), (x1 + 0.01, y0, z + 0.25), (x1 + 0.01, y1 + 0.02, z), (x0 - 0.01, y1 + 0.02, z)], 0.06, roof)


def flat_top(m, x0, x1, y0, y1, z, cap, parapet=0.25):
    """A flat roof with a low parapet and a coping round its edge."""
    m.box(x0, x1, y0, y1, z, z + 0.06, material("tar roof", "#3a3a3c", rough=0.95))
    for xa, xb, ya, yb in ((x0, x1, y0, y0 + 0.012), (x0, x1, y1 - 0.012, y1), (x0, x0 + 0.012, y0, y1), (x1 - 0.012, x1, y0, y1)):
        m.box(xa, xb, ya, yb, z, z + parapet - 0.06, cap)
        m.box(xa - 0.006, xb + 0.006, ya - 0.006, yb + 0.006, z + parapet - 0.06, z + parapet, cap)


# ---- the lot round a building: fences, gardens, trees, sheds and drives ---------------------

# Where a lot's fences run: just inside its edges, so a neighbour's fence on the same line stands beside it.
EDGE = 0.48


def picket(m, a, b, mat, height=0.42, gap=None):
    """A picket fence from a to b, each (x, y), along x or y, open at gap (a place along it) for a walk."""
    (x0, y0), (x1, y1) = a, b
    along_x = abs(x1 - x0) > abs(y1 - y0)
    lo, hi = (min(x0, x1), max(x0, x1)) if along_x else (min(y0, y1), max(y0, y1))
    fixed = y0 if along_x else x0
    t = lo
    while t <= hi + 1e-6:
        if gap is None or abs(t - gap) > 0.035:
            if along_x:
                m.box(t - 0.004, t + 0.004, fixed - 0.003, fixed + 0.003, 0, height, mat)
            else:
                m.box(fixed - 0.003, fixed + 0.003, t - 0.004, t + 0.004, 0, height, mat)
        t += 0.024
    for z in (height * 0.3, height * 0.75):
        segs = [(lo, hi)] if gap is None else [(lo, gap - 0.035), (gap + 0.035, hi)]
        for s0, s1 in segs:
            if s1 <= s0:
                continue
            if along_x:
                m.box(s0, s1, fixed - 0.005, fixed + 0.005, z, z + 0.04, mat)
            else:
                m.box(fixed - 0.005, fixed + 0.005, s0, s1, z, z + 0.04, mat)


def board(m, a, b, mat, height=0.75):
    """A close boarded fence from a to b, along x or y, with posts."""
    (x0, y0), (x1, y1) = a, b
    if abs(x1 - x0) > abs(y1 - y0):
        m.box(min(x0, x1), max(x0, x1), y0 - 0.004, y0 + 0.004, 0, height, mat)
        t = min(x0, x1)
        while t <= max(x0, x1) + 1e-6:
            m.box(t - 0.006, t + 0.006, y0 - 0.007, y0 + 0.007, 0, height + 0.06, mat)
            t += 0.12
    else:
        m.box(x0 - 0.004, x0 + 0.004, min(y0, y1), max(y0, y1), 0, height, mat)
        t = min(y0, y1)
        while t <= max(y0, y1) + 1e-6:
            m.box(x0 - 0.007, x0 + 0.007, t - 0.006, t + 0.006, 0, height + 0.06, mat)
            t += 0.12


def rails(m, a, b, mat):
    """A split rail fence from a to b: posts and three rails."""
    (x0, y0), (x1, y1) = a, b
    along_x = abs(x1 - x0) > abs(y1 - y0)
    n = max(2, int(abs((x1 - x0) if along_x else (y1 - y0)) / 0.16) + 1)
    for k in range(n):
        t = k / (n - 1)
        x, y = x0 + (x1 - x0) * t, y0 + (y1 - y0) * t
        m.box(x - 0.006, x + 0.006, y - 0.006, y + 0.006, 0, 0.55, mat)
    for z in (0.15, 0.3, 0.45):
        m.rod((x0, y0, z), (x1, y1, z), 0.005, mat, segments=5)


def chain(m, a, b, height=0.7):
    """A chain-link fence: thin posts and a see-through grey mesh, drawn as a faint panel."""
    (x0, y0), (x1, y1) = a, b
    metal = material("galvanised", "#9aa0a4", metal=0.6, rough=0.45)
    mesh = material("chain link", "#7c8286", metal=0.5, rough=0.6, grime=0.2)
    along_x = abs(x1 - x0) > abs(y1 - y0)
    if along_x:
        m.box(min(x0, x1), max(x0, x1), y0 - 0.001, y0 + 0.001, 0.02, height, mesh)
    else:
        m.box(x0 - 0.001, x0 + 0.001, min(y0, y1), max(y0, y1), 0.02, height, mesh)
    m.rod((x0, y0, height), (x1, y1, height), 0.003, metal, segments=5)


def fence_round(m, kind, mat=None, front=None, door_x=0.0, side_front=0.32):
    """Fences down the sides and across the back of the lot, and across the front if front is given, open at the walk."""
    sides = [((-EDGE, -EDGE), (-EDGE, side_front)), ((EDGE, -EDGE), (EDGE, side_front)), ((-EDGE, -EDGE), (EDGE, -EDGE))]
    for a, b in sides:
        if kind == "rails":
            rails(m, a, b, mat)
        elif kind == "board":
            board(m, a, b, mat)
        elif kind == "chain":
            chain(m, a, b)
        elif kind == "hedge":
            (x0, y0), (x1, y1) = a, b
            m.box(min(x0, x1) - 0.015, max(x0, x1) + 0.015, min(y0, y1) - 0.015, max(y0, y1) + 0.015, 0, 0.6, material("hedge", "#3a5a2a", rough=1.0), 0.01)
        else:
            picket(m, a, b, mat)
    if front == "picket":
        picket(m, (-EDGE, EDGE), (EDGE, EDGE), mat, gap=door_x)


def yard_tree(m, x, y, size=1.0, seed=1, kind="maple"):
    """A shade tree in the yard: a trunk and a crown of leaf blobs."""
    bark = material("bark", "#4a3a2c", rough=0.95)
    m.upright(x, y, 0, 2.4 * size, 0.012 * size, 0.008 * size, bark, segments=8)
    leaves = scenery.leafy("yard " + kind, "#3e6a2a", "#6a9a3e") if kind == "maple" else scenery.leafy("yard elm", "#4a6e2e", "#78a048")
    for k, (dx, dy, dz, r) in enumerate(((0, 0, 3.2, 0.11), (0.06, 0.03, 2.8, 0.08), (-0.05, -0.04, 2.9, 0.085), (0.02, -0.05, 3.6, 0.07))):
        scenery.blob(m, x + dx * size, y + dy * size, dz * size, r * size, leaves, seed * 7 + k)


def shed(m, x, y, w=0.08, d=0.07, colour="#7a5a3e", roof="#4e4844"):
    wall = material("shed " + colour, colour, rough=0.9)
    m.box(x - w, x + w, y - d, y + d, 0, 0.8, wall, 0.002)
    gable(m, x - w, x + w, y - d, y + d, 0.8, 1.2, material("shed roof", roof, rough=0.85), wall, along_x=True, over=0.012, thick=0.04)
    m.box(x - 0.025, x + 0.025, y + d, y + d + 0.004, 0, 0.6, material("shed door", "#3e2e22", rough=0.9))


def garden(m, x0, x1, y0, y1, rows=4):
    """A vegetable or flower bed: dark soil with rows of green."""
    m.box(x0, x1, y0, y1, 0, 0.04, material("garden soil", "#3e2e22", rough=1.0))
    leaf = material("garden rows", "#4e7a32", rough=1.0)
    for k in range(rows):
        y = y0 + (k + 0.5) * (y1 - y0) / rows
        m.box(x0 + 0.01, x1 - 0.01, y - 0.008, y + 0.008, 0.04, 0.16, leaf, 0.004)


def flowers(m, x0, x1, y0, y1):
    """A flower bed along a wall."""
    m.box(x0, x1, y0, y1, 0, 0.05, material("bed soil", "#4a3628", rough=1.0))
    for k in range(int((x1 - x0) / 0.02)):
        x = x0 + 0.01 + k * 0.02
        m.box(x - 0.006, x + 0.006, y0 + 0.004, y1 - 0.004, 0.05, 0.14, material("flowers " + str(k % 3), ["#c84a5a", "#e8c84a", "#d87ab0"][k % 3], rough=0.9), 0.003)


def drive(m, x, y0, y1, w=0.07, colour="#8a8680"):
    m.box(x - w, x + w, y0, y1, 0, 0.025, material("drive " + colour, colour, rough=0.95))


def paved(m, x0, x1, y0, y1, colour="#9a958c"):
    """A paved yard."""
    m.box(x0, x1, y0, y1, 0, 0.02, material("yard paving " + colour, colour, rough=0.95))


def glass():
    return material("window glass", "#2c3a48", rough=0.12, grime=0.2)


# ---- the kinds of building ------------------------------------------------------------------

def log_cabin(m):
    logs = material("logs", "#6e4a2c", rough=0.95)
    chink = material("chinking", "#c8bca0", rough=1.0)
    shingle = material("cedar shingles", "#5c4a3a", rough=0.9)
    stone = material("fieldstone", "#7c766c", rough=0.95)
    x0, x1, y0, y1 = -0.17, 0.17, -0.08, 0.16
    m.box(x0, x1, y0, y1, 0, 1.3, logs, 0.004)
    # The logs' courses, and their ends crossing at the corners.
    z = 0.12
    while z < 1.3:
        m.box(x0 - 0.002, x1 + 0.002, y0 - 0.002, y1 + 0.002, z, z + 0.025, chink)
        for x in (x0, x1):
            for y in (y0, y1):
                m.box(x - 0.012, x + 0.012, y - 0.012, y + 0.012, z - 0.1, z, logs, 0.003)
        z += 0.16
    gable(m, x0, x1, y0, y1, 1.3, 2.35, shingle, logs, along_x=True, over=0.035)
    chimney(m, x1 + 0.02, 0.04, 0, 2.6, stone, 0.03)
    windows(m, x0, x1, y0, y1, 0, 1, 2, 1, glass(), logs, skip_door=True)
    door(m, 0.0, y1, material("plank door", "#4a3220", rough=0.9), logs)
    path(m, 0.0, y1, 0.5, 0.04)
    # A woodpile by the side.
    for k in range(3):
        m.box(x0 - 0.09, x0 - 0.03, -0.02 + k * 0.035, 0.01 + k * 0.035, 0, 0.35, material("cordwood", "#8a6a48", rough=0.95), 0.004)
    # A split rail fence round the clearing, a vegetable patch and the outhouse at the back.
    fence_round(m, "rails", logs)
    garden(m, 0.12, 0.38, -0.4, -0.16)
    shed(m, -0.34, -0.36, w=0.05, d=0.05, colour="#5e4430")
    yard_tree(m, 0.32, 0.3, 0.9, seed=1)


def farmhouse(m):
    siding = material("white clapboard", "#e6e2d6", rough=0.7)
    trim = material("green trim", "#3e5a3a", rough=0.6)
    roof = material("asphalt shingles", "#5a3a34", rough=0.85)
    brick = material("chimney brick", "#8a4a36", rough=0.9)
    x0, x1, y0, y1 = -0.2, 0.2, -0.16, 0.1
    m.box(x0, x1, y0, y1, 0, 2.5, siding, 0.003)
    for z in [0.2 + 0.11 * k for k in range(21)]:
        m.box(x0 - 0.002, x1 + 0.002, y0 - 0.002, y1 + 0.002, z, z + 0.012, material("clapboard shadow", "#c8c4b8", rough=0.8))
    gable(m, x0, x1, y0, y1, 2.5, 3.9, roof, siding, along_x=True)
    chimney(m, -0.1, -0.03, 2.5, 4.3, brick)
    windows(m, x0, x1, y0, y1, 0, 2, 3, 2, glass(), trim, skip_door=True)
    door(m, 0.0, y1, trim, siding)
    porch(m, x0, x1, y1, y1 + 0.09, 1.3, roof, material("porch floor", "#8a7a66", rough=0.8), siding, posts=5)
    path(m, 0.0, y1 + 0.09, 0.5)
    picket(m, (-EDGE, EDGE), (EDGE, EDGE), material("white pickets", "#eeeae0", rough=0.7), gap=0.0)
    # Rail fences down the sides and back, a big kitchen garden, a shed and the old maple.
    fence_round(m, "rails", material("fence rails", "#8a7458", rough=0.95))
    garden(m, -0.4, -0.08, -0.42, -0.24, rows=5)
    shed(m, 0.32, -0.36, colour="#8a3a2a")
    yard_tree(m, 0.34, 0.24, 1.1, seed=2)


def clapboard_house(m, look=0):
    colours = ["#d8c26a", "#7e9ab0", "#9ab08a", "#c8826a"]
    siding = material("siding", colours[look % len(colours)], rough=0.7)
    trim = material("white trim", "#f0ece2", rough=0.6)
    roof = material("asphalt shingles dark", "#3e3a38", rough=0.85)
    x0, x1, y0, y1 = -0.13, 0.13, -0.2, 0.14
    m.box(x0, x1, y0, y1, 0, 2.2, siding, 0.003)
    for z in [0.2 + 0.11 * k for k in range(18)]:
        m.box(x0 - 0.002, x1 + 0.002, y0 - 0.002, y1 + 0.002, z, z + 0.012, material("siding shadow " + str(look), colours[look % len(colours)], rough=0.9, grime=0.7))
    # Gable end to the street.
    gable(m, x0, x1, y0, y1, 2.2, 3.7, roof, siding, along_x=False)
    windows(m, x0, x1, y0, y1, 0, 2, 2, 3, glass(), trim)
    m.box(-0.03, 0.03, y1 - 0.003, y1 + 0.003, 2.6, 3.1, trim)
    m.box(-0.025, 0.025, y1, y1 + 0.006, 2.65, 3.05, glass())
    door(m, 0.07, y1, material("red door", "#8a2a22", rough=0.5), trim)
    porch(m, x0, x1, y1, y1 + 0.08, 1.25, roof, material("porch floor", "#8a7a66", rough=0.8), trim, posts=3)
    path(m, 0.07, y1 + 0.08, 0.5)
    chimney(m, x1 - 0.04, -0.1, 2.6, 3.9, material("chimney brick", "#8a4a36", rough=0.9))
    # A picket fence along the front with a gate at the walk, board fences behind, a backyard maple and a bed of flowers.
    fence_round(m, "board", material("board fence", "#8a7a64", rough=0.9), side_front=0.14)
    picket(m, (-EDGE, EDGE), (EDGE, EDGE), material("white pickets", "#eeeae0", rough=0.7), gap=0.07)
    flowers(m, -0.12, 0.0, 0.145, 0.175)
    yard_tree(m, -0.3, -0.32, 0.9, seed=3)
    shed(m, 0.32, -0.38, w=0.06, d=0.05, colour="#7a6a52")


def general_store(m):
    boards = material("weathered boards", "#a8906a", rough=0.9)
    front = material("false front", "#c8b48a", rough=0.85)
    trim = material("store trim", "#6a2a22", rough=0.6)
    roof = material("tin roof", "#7c8084", metal=0.4, rough=0.5)
    x0, x1, y0, y1 = -0.2, 0.2, -0.24, 0.16
    m.box(x0, x1, y0, y1, 0, 2.2, boards, 0.003)
    gable(m, x0, x1, y0, y1 - 0.02, 2.2, 3.2, roof, boards, along_x=False)
    # The square false front standing above the roof, with its cornice and sign board.
    m.box(x0 - 0.01, x1 + 0.01, y1, y1 + 0.02, 0, 3.6, front, 0.003)
    m.box(x0 - 0.02, x1 + 0.02, y1 - 0.005, y1 + 0.035, 3.5, 3.7, trim)
    m.box(x0 + 0.04, x1 - 0.04, y1 + 0.02, y1 + 0.028, 2.5, 3.2, material("sign board", "#2e2a26", rough=0.7))
    # Big shop windows each side of the door, and an awning over the boardwalk.
    for x in (-0.12, 0.12):
        m.box(x - 0.06, x + 0.06, y1 + 0.02, y1 + 0.028, 0.3, 1.5, glass())
        m.box(x - 0.065, x + 0.065, y1 + 0.018, y1 + 0.03, 1.5, 1.58, trim)
    door(m, 0.0, y1 + 0.02, trim, front, w=0.06)
    m.box(x0, x1, y1 + 0.02, y1 + 0.13, 0, 0.12, material("boardwalk", "#7a6a52", rough=0.9))
    slab(m, [(x0, y1 + 0.02, 2.1), (x1, y1 + 0.02, 2.1), (x1, y1 + 0.13, 1.85), (x0, y1 + 0.13, 1.85)], 0.04, material("awning", "#3e5a6a", rough=0.7))
    for x in (x0 + 0.01, x1 - 0.01):
        m.box(x - 0.005, x + 0.005, y1 + 0.12, y1 + 0.13, 0, 1.85, trim)
    windows(m, x0, x1, y0, y1, 0, 1, 0, 3, glass(), trim)
    # Barrels and crates on the walk.
    m.upright(x1 - 0.03, y1 + 0.08, 0.12, 0.42, 0.018, 0.018, material("barrel", "#6a4a2a", rough=0.8), segments=12)
    # The lot is a hard-packed yard: a shed for stock, a hitching rail out front.
    paved(m, -0.5, 0.5, -0.5, 0.5, colour="#8a7c66")
    shed(m, -0.34, -0.38, w=0.1, d=0.07, colour="#8a7458")
    m.box(0.26, 0.42, 0.42, 0.43, 0.45, 0.5, material("hitching rail", "#5a4430", rough=0.9))
    for x in (0.26, 0.42):
        m.box(x - 0.006, x + 0.006, 0.42, 0.432, 0, 0.5, material("hitching rail", "#5a4430", rough=0.9))


def church(m):
    wood = material("white church", "#eeeae2", rough=0.7)
    roof = material("church roof", "#4a4e56", rough=0.7)
    glass_c = material("stained glass", "#3a4a6a", rough=0.2)
    x0, x1, y0, y1 = -0.13, 0.13, -0.3, 0.12
    m.box(x0, x1, y0, y1, 0, 2.6, wood, 0.003)
    gable(m, x0, x1, y0, y1, 2.6, 4.1, roof, wood, along_x=False)
    # Tall pointed windows down the sides.
    for k in range(4):
        y = y0 + 0.06 + k * 0.095
        for x, d in ((x0, -1), (x1, 1)):
            m.box(x + d * 0.004 - 0.003, x + d * 0.004 + 0.003, y - 0.022, y + 0.022, 0.6, 2.0, glass_c)
            m.wedge([(x + d * 0.004 - 0.003, y - 0.022, 2.0), (x + d * 0.004 - 0.003, y + 0.022, 2.0), (x + d * 0.004 - 0.003, y, 2.2)],
                    [(x + d * 0.004 + 0.003, y - 0.022, 2.0), (x + d * 0.004 + 0.003, y + 0.022, 2.0), (x + d * 0.004 + 0.003, y, 2.2)], glass_c)
    # The tower at the front, its belfry and its spire.
    tx0, tx1, ty0, ty1 = -0.06, 0.06, y1 - 0.02, y1 + 0.1
    m.box(tx0, tx1, ty0, ty1, 0, 4.6, wood, 0.003)
    m.box(tx0 - 0.008, tx1 + 0.008, ty0 - 0.008, ty1 + 0.008, 4.6, 4.75, roof)
    for x in (tx0, tx1):
        m.box(x - 0.004, x + 0.004, (ty0 + ty1) / 2 - 0.025, (ty0 + ty1) / 2 + 0.025, 3.7, 4.4, material("belfry louvres", "#2a2a2a", rough=0.9))
    m.wedge([(tx0, ty0, 4.75), (tx1, ty0, 4.75), (tx1, ty1, 4.75), (tx0, ty1, 4.75)],
            [(-0.002, (ty0 + ty1) / 2 - 0.002, 7.6), (0.002, (ty0 + ty1) / 2 - 0.002, 7.6), (0.002, (ty0 + ty1) / 2 + 0.002, 7.6), (-0.002, (ty0 + ty1) / 2 + 0.002, 7.6)], roof)
    door(m, 0.0, ty1, material("church doors", "#5a3a26", rough=0.6), wood, w=0.05, h=1.1)
    path(m, 0.0, ty1, 0.5, 0.06)
    # A white fence along the front, and the churchyard behind with its stones and an old elm.
    picket(m, (-EDGE, EDGE), (EDGE, EDGE), material("white pickets", "#eeeae0", rough=0.7), gap=0.0)
    stone = material("grave stone", "#9a968e", rough=0.9)
    for gx in (-0.36, -0.28, 0.24, 0.32, 0.4):
        for gy in (-0.4, -0.3, -0.2):
            m.box(gx - 0.012, gx + 0.012, gy - 0.004, gy + 0.004, 0, 0.32, stone, 0.002)
    yard_tree(m, -0.34, 0.2, 1.2, seed=4, kind="elm")


def brick_row(m):
    brick = material("red brick", "#9a4a36", rough=0.9)
    trim = material("stone trim", "#c8bca8", rough=0.8)
    roof = material("row roof", "#3a3634", rough=0.85)
    doors = ["#2a3e5a", "#5a2a22", "#2e4a2e"]
    x0, x1, y0, y1 = -0.39, 0.39, -0.2, 0.12
    m.box(x0, x1, y0, y1, 0, 2.9, brick, 0.003)
    m.box(x0 - 0.008, x1 + 0.008, y0 - 0.008, y1 + 0.008, 2.9, 3.05, trim)
    hip(m, x0, x1, y0, y1, 3.05, 3.9, roof, over=0.01)
    for k in range(3):
        cx = x0 + (k + 0.5) * (x1 - x0) / 3
        # Each house its own door up a stoop, a bay window beside it, and windows above.
        door(m, cx - 0.06, y1, material("row door " + str(k), doors[k], rough=0.5), trim)
        m.box(cx - 0.09, cx - 0.03, y1, y1 + 0.05, 0, 0.18, trim)
        m.box(cx + 0.01, cx + 0.11, y1, y1 + 0.04, 0.2, 1.3, brick, 0.003)
        m.box(cx + 0.02, cx + 0.1, y1 + 0.04, y1 + 0.046, 0.4, 1.15, glass())
        m.box(cx + 0.005, cx + 0.115, y1 - 0.002, y1 + 0.05, 1.3, 1.38, trim)
        for x in (cx - 0.06, cx + 0.06):
            m.box(x - 0.03, x + 0.03, y1 + 0.002, y1 + 0.007, 1.75, 2.45, glass())
            m.box(x - 0.034, x + 0.034, y1 + 0.001, y1 + 0.009, 2.45, 2.52, trim)
        chimney(m, cx + (x1 - x0) / 6 - 0.01, -0.04, 3.3, 4.3, brick)
        path(m, cx - 0.06, y1 + 0.05, 0.5, 0.04)
    windows(m, x0, x1, y0, y1, 0, 2, 0, 2, glass(), trim)
    # Iron railings in front, three back yards divided by board fences, each with its shed.
    iron = material("iron railings", "#2a2a2a", metal=0.5, rough=0.5)
    picket(m, (-EDGE, 0.3), (EDGE, 0.3), iron, height=0.32)
    fence = material("board fence", "#8a7a64", rough=0.9)
    fence_round(m, "board", fence, side_front=-0.2)
    for x in (-0.13, 0.13):
        board(m, (x, -EDGE), (x, -0.2), fence)
    for k in range(3):
        shed(m, -0.39 + (k + 0.5) * 0.26, -0.4, w=0.04, d=0.04, colour="#7a5a46")


def brick_block(m, look=0):
    brick = material("block brick", ["#a0583e", "#c8a878"][look % 2], rough=0.9)
    trim = material("block trim", "#d6ccb8", rough=0.8)
    x0, x1, y0, y1 = -0.4, 0.4, -0.32, 0.26
    m.box(x0, x1, y0, y1, 0, 3 * STOREY + 0.3, brick, 0.003)
    flat_top(m, x0, x1, y0, y1, 3 * STOREY + 0.3, trim, parapet=0.45)
    m.box(x0 - 0.012, x1 + 0.012, y1 - 0.01, y1 + 0.02, 3 * STOREY + 0.55, 3 * STOREY + 0.75, trim)
    # Shop fronts all along the ground floor, under striped awnings.
    awnings = ["#7a2a2a", "#2a4a6a", "#3e5a2a", "#8a6a2a"]
    for k in range(4):
        cx = x0 + (k + 0.5) * (x1 - x0) / 4
        m.box(cx - 0.075, cx + 0.075, y1, y1 + 0.008, 0.25, 1.35, glass())
        m.box(cx - 0.08, cx + 0.08, y1, y1 + 0.012, 1.35, 1.45, trim)
        slab(m, [(cx - 0.09, y1, 1.6), (cx + 0.09, y1, 1.6), (cx + 0.09, y1 + 0.08, 1.3), (cx - 0.09, y1 + 0.08, 1.3)], 0.04, material("awning " + str(k), awnings[k], rough=0.8))
    windows(m, x0, x1, y0, y1, STOREY + 0.1, 2, 6, 4, glass(), trim)
    windows(m, x0, x1, y0, y1, 0.1, 1, 0, 4, glass(), trim)
    m.box(x0, x1, y1, 0.5, 0, 0.03, material("sidewalk", "#aaa498", rough=0.9))
    # A paved back lane behind the shops.
    paved(m, -0.5, 0.5, -0.5, -0.32)
    m.box(-0.45, -0.38, -0.47, -0.4, 0, 0.35, material("bins", "#3e4a3e", rough=0.8), 0.004)


def bungalow(m, look=0):
    colours = ["#d8cdb0", "#a8b8b0", "#c0a088"]
    stucco = material("bungalow siding", colours[look % len(colours)], rough=0.85)
    trim = material("bungalow trim", "#f0ece2", rough=0.6)
    roof = material("bungalow roof", "#4e4844", rough=0.85)
    x0, x1, y0, y1 = -0.17, 0.17, -0.14, 0.12
    m.box(x0, x1, y0, y1, 0, 1.3, stucco, 0.003)
    hip(m, x0, x1, y0, y1, 1.3, 2.3, roof, over=0.04)
    windows(m, x0, x1, y0, y1, 0, 1, 3, 2, glass(), trim, skip_door=True)
    door(m, 0.0, y1, material("bungalow door", "#5a3a26", rough=0.6), trim)
    porch(m, -0.08, 0.08, y1, y1 + 0.07, 1.1, roof, material("porch floor", "#8a7a66", rough=0.8), trim, posts=2)
    path(m, 0.0, y1 + 0.07, 0.5)
    # A driveway down the side to a garage at the back, a board fence round the back yard and a shade tree.
    drive(m, 0.32, -0.3, 0.5)
    m.box(0.22, 0.42, -0.44, -0.26, 0, 1.0, stucco, 0.003)
    gable(m, 0.22, 0.42, -0.44, -0.26, 1.0, 1.4, roof, stucco, along_x=False, over=0.02, thick=0.05)
    m.box(0.25, 0.39, -0.26, -0.255, 0, 0.8, material("garage door", "#e8e4dc", rough=0.6))
    fence_round(m, "board", material("cedar fence", "#9a7a5a", rough=0.9), side_front=-0.2)
    flowers(m, -0.16, -0.04, 0.125, 0.15)
    yard_tree(m, -0.28, -0.3, 1.0, seed=5)


def walkup(m):
    brick = material("walkup brick", "#8a5a46", rough=0.9)
    trim = material("walkup trim", "#c8c0b0", rough=0.8)
    iron = material("fire escape", "#2a2a2a", metal=0.5, rough=0.5)
    x0, x1, y0, y1 = -0.34, 0.34, -0.28, 0.22
    top = 4 * STOREY + 0.2
    m.box(x0, x1, y0, y1, 0, top, brick, 0.003)
    flat_top(m, x0, x1, y0, y1, top, trim)
    windows(m, x0, x1, y0, y1, 0.1, 4, 5, 4, glass(), trim, skip_door=True)
    door(m, 0.0, y1, material("walkup door", "#3a2a22", rough=0.6), trim, w=0.07, h=1.0)
    slab(m, [(-0.07, y1, 1.15), (0.07, y1, 1.15), (0.07, y1 + 0.06, 1.15), (-0.07, y1 + 0.06, 1.15)], 0.06, trim)
    # A fire escape down one side: landings and a ladder.
    for s in range(1, 4):
        z = s * STOREY + 0.1
        m.box(x1, x1 + 0.05, -0.12, 0.06, z, z + 0.04, iron)
        m.box(x1 + 0.045, x1 + 0.05, -0.12, 0.06, z + 0.04, z + 0.45, iron)
    m.rod((x1 + 0.03, -0.1, 1.35), (x1 + 0.03, 0.04, 4 * STOREY + 0.15), 0.004, iron)
    path(m, 0.0, y1, 0.5, 0.08)
    # A paved forecourt, chain-link round the back where the bins are, and trees along the side.
    paved(m, -0.12, 0.12, 0.22, 0.5)
    fence_round(m, "chain", side_front=-0.28)
    m.box(-0.42, -0.34, -0.46, -0.38, 0, 0.35, material("bins", "#3e4a3e", rough=0.8), 0.004)
    yard_tree(m, -0.42, 0.36, 0.8, seed=6, kind="elm")
    yard_tree(m, 0.42, 0.36, 0.8, seed=7, kind="elm")


def split_level(m, look=0):
    brick = material("split brick", ["#b8a48a", "#a87a5a"][look % 2], rough=0.9)
    siding = material("split siding", "#d8d4c8", rough=0.75)
    roof = material("split roof", "#5a5450", rough=0.85)
    trim = material("split trim", "#f0ece2", rough=0.6)
    # The low wing, the raised wing beside it, and a garage with its drive.
    m.box(-0.2, 0.02, -0.12, 0.12, 0, 1.3, brick, 0.003)
    gable(m, -0.2, 0.02, -0.12, 0.12, 1.3, 2.0, roof, brick, along_x=True, over=0.04)
    m.box(0.02, 0.2, -0.14, 0.1, 0, 2.3, siding, 0.003)
    gable(m, 0.02, 0.2, -0.14, 0.1, 2.3, 3.0, roof, siding, along_x=True, over=0.04)
    windows(m, -0.2, 0.02, -0.12, 0.12, 0, 1, 2, 0, glass(), trim)
    windows(m, 0.02, 0.2, -0.14, 0.1, 0.9, 1, 2, 1, glass(), trim)
    door(m, -0.03, 0.12, material("split door", "#2a3a4a", rough=0.5), trim)
    m.box(0.2, 0.36, -0.1, 0.12, 0, 1.15, siding, 0.003)
    gable(m, 0.2, 0.36, -0.1, 0.12, 1.15, 1.7, roof, siding, along_x=False, over=0.03)
    m.box(0.22, 0.34, 0.12, 0.126, 0, 0.95, material("garage door", "#e8e4dc", rough=0.6))
    m.box(0.21, 0.35, 0.12, 0.5, 0, 0.025, material("driveway", "#6a6866", rough=0.95))
    path(m, -0.03, 0.12, 0.5)
    # A cedar privacy fence round the back yard, a patio and a young tree out front.
    fence_round(m, "board", material("cedar fence", "#9a7a5a", rough=0.9), side_front=-0.15)
    paved(m, -0.18, 0.0, -0.3, -0.14, colour="#a8a296")
    yard_tree(m, -0.3, 0.32, 0.75, seed=8)
    flowers(m, -0.19, -0.07, 0.125, 0.15)


def office_block(m):
    concrete = material("office concrete", "#b4b0a8", rough=0.85)
    curtain = material("curtain glass", "#3e5a6e", metal=0.3, rough=0.1, grime=0.15)
    x0, x1, y0, y1 = -0.32, 0.32, -0.3, 0.28
    storeys = 8
    top = storeys * STOREY
    m.box(x0, x1, y0, y1, 0, top, concrete, 0.003)
    # Bands of glass between concrete spandrels, floor by floor, all round.
    for s in range(storeys):
        z = s * STOREY + 0.35
        for xa, xb, ya, yb in ((x0 + 0.02, x1 - 0.02, y1, y1 + 0.006), (x0 + 0.02, x1 - 0.02, y0 - 0.006, y0), (x1, x1 + 0.006, y0 + 0.02, y1 - 0.02), (x0 - 0.006, x0, y0 + 0.02, y1 - 0.02)):
            m.box(xa, xb, ya, yb, z, z + 0.75, curtain)
    for k in range(9):
        x = x0 + 0.02 + k * (x1 - x0 - 0.04) / 8
        m.box(x - 0.006, x + 0.006, y1, y1 + 0.012, 0.3, top, concrete)
    flat_top(m, x0, x1, y0, y1, top, concrete, parapet=0.2)
    m.box(-0.12, 0.1, -0.15, 0.08, top, top + 1.0, concrete, 0.003)
    m.box(x0, x1, y1, 0.5, 0, 0.03, material("plaza", "#9a968e", rough=0.9))
    m.box(-0.1, 0.1, y1, y1 + 0.1, 1.0, 1.08, concrete)
    # Planters with trees on the plaza.
    for x in (-0.38, 0.38):
        m.box(x - 0.05, x + 0.05, 0.35, 0.45, 0, 0.3, material("planter", "#8a867e", rough=0.9), 0.004)
        yard_tree(m, x, 0.4, 0.7, seed=9, kind="elm")


def apartment_tower(m):
    concrete = material("tower concrete", "#c8c2b4", rough=0.85)
    rail = material("balcony rail", "#5a6064", metal=0.4, rough=0.5)
    x0, x1, y0, y1 = -0.2, 0.2, -0.22, 0.2
    storeys = 9
    top = storeys * STOREY + 0.2
    m.box(x0, x1, y0, y1, 0, top, concrete, 0.003)
    windows(m, x0, x1, y0, y1, 0.1, storeys, 4, 4, glass(), concrete)
    # Balconies stacked up the front and back.
    for s in range(1, storeys):
        z = s * STOREY + 0.05
        for y, d in ((y1, 1), (y0, -1)):
            for cx in (-0.1, 0.1):
                m.box(cx - 0.07, cx + 0.07, min(y, y + d * 0.06), max(y, y + d * 0.06), z, z + 0.06, concrete)
                m.box(cx - 0.07, cx + 0.07, y + d * 0.06 - 0.003, y + d * 0.06 + 0.003, z + 0.06, z + 0.42, rail)
    flat_top(m, x0, x1, y0, y1, top, concrete, parapet=0.2)
    m.box(-0.06, 0.06, -0.05, 0.08, top, top + 0.8, concrete, 0.003)
    door(m, 0.0, y1, material("lobby glass", "#2c3a48", rough=0.15), concrete, w=0.1, h=1.0)
    m.box(-0.12, 0.12, y1, 0.5, 0, 0.03, material("tower walk", "#a8a49a", rough=0.9))
    for x in (-0.36, 0.36):
        m.box(x - 0.08, x + 0.08, -0.35, 0.35, 0, 0.03, material("lawn", "#4e7a3a", rough=1.0))
    # Trees on the lawns.
    yard_tree(m, -0.36, 0.3, 0.9, seed=10)
    yard_tree(m, 0.38, -0.3, 0.9, seed=11)
    yard_tree(m, -0.38, -0.32, 0.8, seed=12, kind="elm")


def statue(m):
    """A company's statue in a small square: a bronze figure on a granite plinth with a plaque facing the road, paving,
    flower beds, benches and shrubs."""
    granite = material("plinth granite", "#8e8a84", rough=0.8)
    bronze = material("statue bronze", "#5e7a62", metal=0.7, rough=0.45, grime=0.6)
    paved(m, -0.42, 0.42, -0.42, 0.42, "#b4ada0")
    path(m, 0.0, 0.42, 0.5, 0.08)
    # The plinth in three steps, and the plaque on its front.
    m.box(-0.08, 0.08, -0.08, 0.08, 0.02, 0.25, granite, 0.004)
    m.box(-0.055, 0.055, -0.055, 0.055, 0.25, 1.05, granite, 0.003)
    m.box(-0.068, 0.068, -0.068, 0.068, 1.05, 1.15, granite, 0.003)
    m.box(-0.03, 0.03, 0.055, 0.059, 0.55, 0.85, bronze)
    # The figure: a long coat, shoulders and head, one arm out towards the town.
    m.lathe(0.0, 0.0, 1.15, [(0.042, 0.0), (0.042, 0.12), (0.036, 0.9), (0.03, 1.35), (0.036, 1.45), (0.016, 1.52), (0.022, 1.57),
                              (0.022, 1.76), (0.0, 1.84)], bronze)
    m.box(0.025, 0.1, -0.011, 0.011, 2.5, 2.58, bronze)
    m.box(-0.044, -0.026, -0.011, 0.011, 1.85, 2.6, bronze)
    # Flower beds at the corners, benches facing it from either side, and low shrubs at the back, which keep out of the
    # figure's way from every side.
    for (x0, x1, y0, y1) in ((-0.38, -0.22, 0.22, 0.38), (0.22, 0.38, 0.22, 0.38)):
        flowers(m, x0, x1, y0, y1)
    wood = material("bench slats", "#7a5a3a", rough=0.8)
    iron = material("bench iron", "#2a2a2a", metal=0.5, rough=0.6)
    for x in (-0.25, 0.25):
        m.box(x - 0.025, x + 0.025, -0.1, 0.1, 0.2, 0.25, wood)
        m.box(x - 0.008 + (0.02 if x > 0 else -0.02), x + 0.008 + (0.02 if x > 0 else -0.02), -0.1, 0.1, 0.25, 0.5, wood)
        for y in (-0.09, 0.09):
            m.box(x - 0.02, x + 0.02, y - 0.004, y + 0.004, 0.02, 0.2, iron)
    shrub = scenery.leafy("square shrubs", "#3e6a2a", "#6a9a3e")
    for k, (x, y) in enumerate(((-0.34, -0.34), (-0.22, -0.36), (0.34, -0.34), (0.22, -0.36))):
        scenery.blob(m, x, y, 0.25, 0.06, shrub, 30 + k)


BUILDERS = {
    "log_cabin": log_cabin,
    "farmhouse": farmhouse,
    "clapboard_house": clapboard_house,
    "general_store": general_store,
    "church": church,
    "brick_row": brick_row,
    "brick_block": brick_block,
    "bungalow": bungalow,
    "walkup": walkup,
    "split_level": split_level,
    "office_block": office_block,
    "apartment_tower": apartment_tower,
    "statue": statue,
}

# Which way each view edge is, as a heading in eighths for place(): the front faces +y, the game's +u.
FACING = {"XP": 0, "YP": 2, "XM": 4, "YM": 6}


def house(m, kind):
    BUILDERS[kind](m)
