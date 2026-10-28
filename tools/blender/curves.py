"""Track pieces drawn as curves between their two edge middles (docs/CURVES_PLAN.md).

The geometry needs no Blender, so tools outside it can place trains on the same curves; the builders at the bottom
import bpy when they run. Points are in tile terms (u, v), directions unit vectors in the way the piece runs, from its
first edge to its second as PIECE_EDGES lists them.

The rule, as real track has it:
- A straight X or Y never bends. Everything that turns is a diagonal piece.
- A diagonal piece's end is tangent to the tile edge's axis (T) when anything but a diagonal carrying on in its own
  line meets it there: a straight through the edge, either side, or another diagonal turning off. A diagonal between
  two T ends is a quarter circle of half a tile radius: a 90 degree turn, a wye leg, or a turnout's branch onto a
  crossing straight.
- Where a diagonal carries on in its own line, or ends open, it keeps 45 degrees (D). A diagonal from a T end to a D
  end is a hook: edge middles on a diagonal line lie on a straight line, so a curve that leaves a straight there and
  ends on that line has to swing past 45 degrees (to about 60) and come back within its own piece.
- Where the two pieces of a diagonal pair both hook (S), as in a crossover, they meet at 69 degrees instead, so the
  pair is one smooth reverse curve with no swing back.
So a diagonal piece has 4 plain shapes (D-D straight, T-D hook, T-T quarter circle, T-S crossover half) and a straight
1, plus a switch's ties and gear. Where the tiles round a hook are clear it's drawn eased over two tiles instead (Eased
shapes, below).
"""
import math

EDGE_MID = {"XM": (0.0, 0.5), "XP": (1.0, 0.5), "YM": (0.5, 0.0), "YP": (0.5, 1.0)}
PIECE_EDGES = {"X": ("XM", "XP"), "Y": ("YM", "YP"), "N": ("YM", "XM"), "E": ("YM", "XP"), "S": ("XP", "YP"), "W": ("XM", "YP")}
PIECES = ["X", "Y", "N", "E", "S", "W"]
OPPOSITE = {"XM": "XP", "XP": "XM", "YM": "YP", "YP": "YM"}
# The tile across each edge.
STEP = {"XM": (-1, 0), "XP": (1, 0), "YM": (0, -1), "YP": (0, 1)}
# Straight out of the tile through each edge.
OUT = {"XM": (-1.0, 0.0), "XP": (1.0, 0.0), "YM": (0.0, -1.0), "YP": (0.0, 1.0)}

# Track sizes, as scenery.track() has them, in tiles across the middle of a straight. The gauge is the same on every
# heading.
BED_BOTTOM, BED_TOP = 0.23, 0.17
TIE_HALF, TIE_ALONG = 0.165, 0.024
GAUGE = 0.095
GHOST = 0.35
# Ties per tile along a straight; a turnout's long ties sit where its straight's ties do.
TIES = 9

# Where both pieces of a diagonal pair hook, how far past 45 degrees they meet.
SWING_S = 24.0
# Handle lengths of each piece's cubic, in tiles, by (this end's look, the other end's look), worked out to keep the
# bending as even as a cubic can. T-T is a quarter circle, D-D a straight line.
HANDLE = {
    ("T", "T"): 0.2761, ("T", "D"): 0.280, ("D", "T"): 0.219, ("T", "S"): 0.314, ("S", "T"): 0.164,
    ("D", "D"): 0.2357,
}


def _norm(d):
    n = math.hypot(d[0], d[1])
    return (d[0] / n, d[1] / n)


def _turn(d, deg):
    a = math.radians(deg)
    return (d[0] * math.cos(a) - d[1] * math.sin(a), d[0] * math.sin(a) + d[1] * math.cos(a))


def chord(piece):
    a, b = (EDGE_MID[e] for e in PIECE_EDGES[piece])
    return a, b


def own(piece):
    a, b = chord(piece)
    return _norm((b[0] - a[0], b[1] - a[1]))


def outward(piece, edge):
    """The piece's own direction where it leaves its tile through [edge]."""
    d = own(piece)
    return d if PIECE_EDGES[piece][1] == edge else (-d[0], -d[1])


def straight(piece):
    return piece in ("X", "Y")


def _far(piece, edge):
    return [e for e in PIECE_EDGES[piece] if e != edge][0]


def _carries_on(layout, tile, piece, edge):
    """The diagonal across [edge] that carries on in [piece]'s line, as (tile, piece), or None."""
    if straight(piece):
        return None
    nt = (tile[0] + STEP[edge][0], tile[1] + STEP[edge][1])
    mine = outward(piece, edge)
    for p in layout.get(nt, []):
        if not straight(p) and OPPOSITE[edge] in PIECE_EDGES[p]:
            theirs = outward(p, OPPOSITE[edge])
            if abs(mine[0] + theirs[0]) < 1e-9 and abs(mine[1] + theirs[1]) < 1e-9:
                return nt, p
    return None


def _base_look(layout, tile, piece, edge):
    """T or D at a diagonal's end, before S is worked out."""
    if _carries_on(layout, tile, piece, edge):
        return "D"
    nt = (tile[0] + STEP[edge][0], tile[1] + STEP[edge][1])
    here = [p for p in layout[tile] if p != piece and edge in PIECE_EDGES[p]]
    there = [p for p in layout.get(nt, []) if OPPOSITE[edge] in PIECE_EDGES[p]]
    return "T" if here or there else "D"


def end_look(layout, tile, piece, edge):
    """How [piece] in [tile] meets [edge]: (look, its outward direction there). Reads [tile] and the 8 tiles round
    it, no further."""
    if straight(piece):
        return "T", OUT[edge]
    base = _base_look(layout, tile, piece, edge)
    if base == "T":
        return "T", OUT[edge]
    on = _carries_on(layout, tile, piece, edge)
    if not on:
        return "D", outward(piece, edge)
    nt, other = on
    if not (_base_look(layout, tile, piece, _far(piece, edge)) == "T"
            and _base_look(layout, nt, other, _far(other, OPPOSITE[edge])) == "T"):
        return "D", outward(piece, edge)
    # Steeper, away from the axis both pieces leave along.
    axis = OUT[_far(piece, edge)]
    d = outward(piece, edge)
    a, b = _turn(d, SWING_S), _turn(d, -SWING_S)
    da = abs(a[0] * axis[0] + a[1] * axis[1])
    db = abs(b[0] * axis[0] + b[1] * axis[1])
    return "S", a if da < db else b


class Shape:
    """One piece's look in its place: its curve, each end's look, and the switches at its ends, [(end, other
    pieces in the tile leaving by that edge)]."""

    def __init__(self, piece, looks, curve, switches):
        self.piece, self.looks, self.curve, self.switches = piece, looks, curve, switches


def shapes(layout):
    """{(tile, piece): Shape} for every piece in [layout], {tile: [pieces]}."""
    out = {}
    for tile, pieces in layout.items():
        for piece in pieces:
            ea, eb = PIECE_EDGES[piece]
            la, oa = end_look(layout, tile, piece, ea)
            lb, ob = end_look(layout, tile, piece, eb)
            da, db = (-oa[0], -oa[1]), ob
            if straight(piece):
                ha = hb = 1.0 / 3
            else:
                ha, hb = HANDLE[(la, lb)], HANDLE[(lb, la)]
            switches = []
            for end, e in enumerate((ea, eb)):
                others = [p for p in pieces if p != piece and e in PIECE_EDGES[p]]
                if others and not straight(piece):
                    switches.append((end, others))
            out[(tile, piece)] = Shape(piece, (la, lb), Curve(piece, da, db, ha, hb), switches)
    return out


def bezier(piece, da, db, ha, hb):
    a, b = chord(piece)
    return (a, (a[0] + da[0] * ha, a[1] + da[1] * ha), (b[0] - db[0] * hb, b[1] - db[1] * hb), b)


def point(ctrl, t):
    p0, p1, p2, p3 = ctrl
    s = 1 - t
    return tuple(s * s * s * p0[i] + 3 * s * s * t * p1[i] + 3 * s * t * t * p2[i] + t * t * t * p3[i] for i in (0, 1))


def tangent(ctrl, t):
    p0, p1, p2, p3 = ctrl
    s = 1 - t
    d = tuple(3 * s * s * (p1[i] - p0[i]) + 6 * s * t * (p2[i] - p1[i]) + 3 * t * t * (p3[i] - p2[i]) for i in (0, 1))
    return _norm(d)


def table(ctrl, n=64):
    """Distance along the curve at n + 1 even steps of t, from 0 to its length."""
    out = [0.0]
    prev = point(ctrl, 0.0)
    for i in range(1, n + 1):
        p = point(ctrl, i / n)
        out.append(out[-1] + math.hypot(p[0] - prev[0], p[1] - prev[1]))
        prev = p
    return out


def t_at(tab, s):
    """The t a distance s along a curve with distance table tab."""
    n = len(tab) - 1
    s = max(0.0, min(tab[-1], s))
    for i in range(n):
        if tab[i + 1] >= s:
            f = (s - tab[i]) / max(1e-12, tab[i + 1] - tab[i])
            return (i + f) / n
    return 1.0


class Curve:
    """One piece's curve, with straight runs on past both ends along its end directions. [edges] are the tile edges
    it starts and ends on; an eased part can meet an edge off its middle."""

    def __init__(self, piece, da, db, ha=None, hb=None, ctrl=None, edges=None):
        self.piece = piece
        self.edges = edges or PIECE_EDGES[piece]
        self.straight = piece in ("X", "Y")
        if ctrl is None:
            a, b = chord(piece)
            c = math.hypot(b[0] - a[0], b[1] - a[1])
            ctrl = bezier(piece, da, db, c / 3 if ha is None else ha, c / 3 if hb is None else hb)
        self.ctrl = ctrl
        self.da, self.db = da, db
        self.tab = table(self.ctrl)
        self.length = self.tab[-1]

    @classmethod
    def of(cls, piece, ctrl, edges=None, straight=None):
        """A curve from its control points, in whatever terms they're in."""
        c = cls(piece, tangent(ctrl, 0.0), tangent(ctrl, 1.0), ctrl=ctrl, edges=edges or PIECE_EDGES.get(piece))
        if straight is not None:
            c.straight = straight
        return c

    def moved(self, by):
        """The same curve shifted by [by] tiles, as from tile terms to world or back."""
        ctrl = tuple((p[0] + by[0], p[1] + by[1]) for p in self.ctrl)
        return Curve.of(self.piece, ctrl, self.edges, self.straight)

    def reversed(self):
        return Curve.of(self.piece, self.ctrl[::-1], self.edges[::-1], self.straight)

    def at(self, t):
        """Point and direction at t; past 0 or 1 straight on, t measured in curve lengths."""
        if t < 0:
            p, d = self.ctrl[0], self.da
            return (p[0] + d[0] * t * self.length, p[1] + d[1] * t * self.length), d
        if t > 1:
            p, d = self.ctrl[3], self.db
            e = (t - 1) * self.length
            return (p[0] + d[0] * e, p[1] + d[1] * e), d
        return point(self.ctrl, t), tangent(self.ctrl, t)

    def offset(self, t, across):
        """A point [across] tiles to the left of the curve at t."""
        p, d = self.at(t)
        return (p[0] - d[1] * across, p[1] + d[0] * across)

    def clip(self, across, end):
        """The t where the line [across] to the left meets the tile edge at [end] (0 or 1)."""
        edge = self.edges[end]
        axis = 0 if edge in ("XM", "XP") else 1
        value = EDGE_MID[edge][axis]
        lo, hi = (-0.45, 0.45) if end == 0 else (0.55, 1.45)
        f = lambda t: self.offset(t, across)[axis] - value  # noqa: E731
        flo, fhi = f(lo), f(hi)
        if flo * fhi > 0:
            return float(end)
        for _ in range(40):
            mid = (lo + hi) / 2
            fm = f(mid)
            if fm * flo > 0:
                lo, flo = mid, fm
            else:
                hi = mid
        return (lo + hi) / 2

    def t_of(self, s):
        return t_at(self.tab, s)


def heading16(d):
    """The nearest of 16 headings, from u towards v, to direction d."""
    return round(math.atan2(d[1], d[0]) / (math.pi / 8)) % 16


# ---- Switches -----------------------------------------------------------------------------------
# A switch is where pieces leave a tile by the same edge. Each piece that turns off there draws its share: a frog where
# its rail crosses the other route's, a bar across the points and a lever stand beside them. Where the other route is
# a straight it also draws long ties square to the straight under both routes up to where they part, and a check rail
# inside each rail opposite the frog. Where two diagonals part, each keeps its own ties, as the two would cross.

# Long ties run on until the routes' middles are this far apart, and a turning piece's own ties start where they
# are this far apart, so the two overlap a little with no gap.
LONG_UNTIL = 0.34
OWN_FROM = 0.34
FROG_ALONG = 0.045
CHECK_ALONG = 0.07
CHECK_IN = 0.032


class Frame:
    """Terms along a switch edge's axis: s in from the edge, lat to the left looking in. [at] is the tile the edge
    belongs to, for curves in world terms."""

    def __init__(self, edge, at=(0, 0)):
        self.o = (at[0] + EDGE_MID[edge][0], at[1] + EDGE_MID[edge][1])
        self.i = (-OUT[edge][0], -OUT[edge][1])
        self.l = (-self.i[1], self.i[0])

    def to(self, p):
        x, y = p[0] - self.o[0], p[1] - self.o[1]
        return x * self.i[0] + y * self.i[1], x * self.l[0] + y * self.l[1]

    def back(self, s, lat):
        return (self.o[0] + self.i[0] * s + self.l[0] * lat, self.o[1] + self.i[1] * s + self.l[1] * lat)

    def moved(self, by):
        f = Frame.__new__(Frame)
        f.o, f.i, f.l = (self.o[0] + by[0], self.o[1] + by[1]), self.i, self.l
        return f


def _line(curve, across, n=120):
    """A rail line of [curve], from its start on, as points."""
    return [curve.offset(k / n, across) for k in range(n + 1)]


def _cross(pa, pb):
    """Where polylines pa and pb first cross, skipping where they start together, as a point or None."""
    for i in range(3, len(pa) - 1):
        p, q = pa[i], pa[i + 1]
        for j in range(3, len(pb) - 1):
            r, s = pb[j], pb[j + 1]
            dx1, dy1 = q[0] - p[0], q[1] - p[1]
            dx2, dy2 = s[0] - r[0], s[1] - r[1]
            den = dx1 * dy2 - dy1 * dx2
            if abs(den) < 1e-12:
                continue
            t = ((r[0] - p[0]) * dy2 - (r[1] - p[1]) * dx2) / den
            w = ((r[0] - p[0]) * dy1 - (r[1] - p[1]) * dx1) / den
            if 0 <= t <= 1 and 0 <= w <= 1:
                return (p[0] + dx1 * t, p[1] + dy1 * t)
    return None


def _across_for(curve, frame, lat_sign):
    """The across (left of the curve as it runs) of the rail that starts on the [lat_sign] side of the axis."""
    p0 = curve.offset(0.0, GAUGE)
    return GAUGE if frame.to(p0)[1] * lat_sign > 0 else -GAUGE


def switch_parts(curve, frame, others):
    """What [curve] draws at its switch with [others], the other routes' curves, all starting at the points on
    [frame]'s edge, all in the same terms: {"long": [(s, lat0, lat1)] in Frame terms, "long_end": the s where its
    own ties start again, "frogs": [(point, dir)], "checks": [(a, b)], "bar": (a, b), "stand": point, "rod": (a, b),
    "frame", "side", "turnout": whether another route is a straight}."""
    f = frame
    side = 1.0 if f.to(curve.at(1.0)[0])[1] > 0 else -1.0
    centre = [f.to(p) for p in _line(curve, 0.0, 200)]
    turnout = any(o.straight for o in others)
    long = []
    for k in range(3 * TIES if turnout else 0):
        s = (k + 0.5) / TIES
        lat = None
        for (s0, l0), (s1, l1) in zip(centre, centre[1:]):
            if (s0 - s) * (s1 - s) <= 0 and s1 != s0:
                lat = l0 + (l1 - l0) * (s - s0) / (s1 - s0)
                break
        if lat is None or abs(lat) > LONG_UNTIL:
            break
        lo, hi = -TIE_HALF, abs(lat) + TIE_HALF
        long.append((s, lo * side, hi * side) if side > 0 else (s, hi * side, lo * side))
    out = {"long": long, "long_end": (long[-1][0] + 0.5 / TIES) if long else 0.5, "frogs": [], "checks": [],
           "frame": f, "side": side, "turnout": turnout}
    inner = _across_for(curve, f, -side)
    mine = _line(curve, inner)
    mine_outer = _line(curve, -inner)
    for o_curve in others:
        o_rail = _across_for(o_curve, f, side)
        theirs = _line(o_curve, o_rail)
        hit = _cross(mine, theirs)
        if not hit:
            continue
        i = min(range(len(mine)), key=lambda k: math.hypot(mine[k][0] - hit[0], mine[k][1] - hit[1]))
        j = min(range(len(theirs)), key=lambda k: math.hypot(theirs[k][0] - hit[0], theirs[k][1] - hit[1]))
        d1 = _norm((mine[min(i + 1, len(mine) - 1)][0] - mine[i - 1][0], mine[min(i + 1, len(mine) - 1)][1] - mine[i - 1][1]))
        d2 = _norm((theirs[min(j + 1, len(theirs) - 1)][0] - theirs[j - 1][0], theirs[min(j + 1, len(theirs) - 1)][1] - theirs[j - 1][1]))
        out["frogs"].append((hit, _norm((d1[0] + d2[0], d1[1] + d2[1]))))
        if not o_curve.straight:
            continue
        # Check rails inside the two rails that don't cross, opposite the frog.
        o_far = _line(o_curve, -o_rail)
        for line, k in ((o_far, j), (mine_outer, i)):
            k = min(k, len(line) - 2)
            p = line[k]
            q = line[k + 1]
            d = _norm((q[0] - p[0], q[1] - p[1]))
            mid = ((hit[0] + p[0]) / 2, (hit[1] + p[1]) / 2)
            to_mid = _norm((mid[0] - p[0], mid[1] - p[1]))
            c = (p[0] + to_mid[0] * CHECK_IN, p[1] + to_mid[1] * CHECK_IN)
            out["checks"].append(((c[0] - d[0] * CHECK_ALONG, c[1] - d[1] * CHECK_ALONG), (c[0] + d[0] * CHECK_ALONG, c[1] + d[1] * CHECK_ALONG)))
    out["bar"] = (f.back(0.035, -GAUGE - 0.03), f.back(0.035, GAUGE + 0.03))
    out["stand"] = f.back(0.07, -side * 0.27)
    out["rod"] = (f.back(0.035, -side * GAUGE), f.back(0.07, -side * 0.27))
    return out


def localise(sw, tile, keep=None):
    """[sw] from world terms into [tile]'s, keeping only the parts whose middle [keep](world point) passes."""
    by = (-tile[0], -tile[1])
    mv = lambda p: (p[0] + by[0], p[1] + by[1])  # noqa: E731
    ok = keep or (lambda _p: True)
    f = sw["frame"]
    out = dict(sw)
    out["frame"] = f.moved(by)
    out["long"] = [x for x in sw["long"] if ok(f.back(x[0], (x[1] + x[2]) / 2))]
    out["frogs"] = [(mv(pt), d) for pt, d in sw["frogs"] if ok(pt)]
    out["checks"] = [(mv(a), mv(b)) for a, b in sw["checks"] if ok(((a[0] + b[0]) / 2, (a[1] + b[1]) / 2))]
    for k in ("bar", "rod"):
        a, b = sw[k]
        out[k] = (mv(a), mv(b)) if ok(((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)) else None
    out["stand"] = mv(sw["stand"]) if ok(sw["stand"]) else None
    return out


# ---- Eased shapes ------------------------------------------------------------------------------
# A hook (T to D) starts on the diagonal line it ends on, so any curve that leaves the T end square to its edge and
# stays inside its own tile, or inside the next diagonal tile as well, has to swing past 45 degrees to come back onto
# that line. Where the tiles round it are clear, the drawn line leaves the edge middles over two tiles instead, as
# one cubic split where it crosses the edge between them. Each span is one of:
# - back: a hook's T end meets a straight in line. The curve starts at the straight tile's far edge middle and cuts
#   the corner of the two lines, a circular-like arc of about 1.6 tiles radius with no swing at all. On a turnout or a
#   diagonal wye the points move to the far edge of the straight tile; on a plain diagonal line meeting a straight
#   it's the ease.
# - ahead: the hook and the diagonal it carries on into. Starts square at the T end's middle, swings to about 59
#   degrees at a radius of about half a tile and comes back onto the line by the second tile's far edge.
# - corner: two hooks meeting T to T, where two diagonal lines meet at a right angle. A 90 degree arc of 0.7 tiles
#   radius across both tiles. Used in either mode.
# Each span meets its neighbours at their edge middles in their own directions, so nothing outside it changes.

EASE_MODES = ("back", "ahead")
# back: shares of the way from each end to the corner of the two lines, straight end then diagonal end, set for the
# widest smallest radius (1.58 tiles).
BACK = (0.39, 0.99)
# corner: a quarter circle's handles as a share of the way to the corner.
CORNER = 0.5523
# ahead: handle lengths in tiles at the T end and the far diagonal end, for the least swing at about half a tile
# radius.
AHEAD = (0.52, 0.40)
# Anything on a tile that keeps a span off it. Signals stand at the tile's edge and need the line at its middle.
BLOCKING = {"slope", "bridge", "tunnel", "crossing", "platform", "depot", "signal"}


class Span:
    """One eased line: its control points in world terms, [parts] [(tile, label, sim piece, (entry, exit) edges)]
    in the way it runs, the edge it's split at (tile, edge), [points] (tile, edge) where a switch's points are or
    None, [group] what it belongs to, and [hide], straights its parts replace."""

    def __init__(self, kind, group, ctrl, parts, split, points=None, hide=()):
        self.kind, self.group, self.ctrl, self.parts, self.split = kind, group, ctrl, parts, split
        self.points, self.hide = points, list(hide)

    def tiles(self):
        return [t for t, _l, _p, _e in self.parts]


def _w(tile, p):
    return (tile[0] + p[0], tile[1] + p[1])


def _mid(tile, edge):
    return _w(tile, EDGE_MID[edge])


def _lerp(a, b, f):
    return (a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f)


def _next(tile, edge):
    return (tile[0] + STEP[edge][0], tile[1] + STEP[edge][1])


def _hooks(layout):
    """{(tile, piece): (T edge, D edge)} for every hook as the plain rule picks them."""
    out = {}
    for (tile, piece), sh in shapes(layout).items():
        if not straight(piece) and sorted(sh.looks) == ["D", "T"]:
            ea, eb = PIECE_EDGES[piece]
            out[(tile, piece)] = (ea, eb) if sh.looks[0] == "T" else (eb, ea)
    return out


def candidates(layout, mode):
    """Every span the pieces allow in [mode], before the clear rule. Corners are the same in either mode."""
    hooks = _hooks(layout)
    out, seen = [], set()
    for (t, p), (e, f) in sorted(hooks.items()):
        n, oe = _next(t, e), OPPOSITE[e]
        rs = [r for r in layout.get(n, []) if hooks.get((n, r), (None,))[0] == oe]
        if rs:
            r = rs[0]
            group = tuple(sorted([(t, e), (n, oe)]))
            if group in seen:
                continue
            seen.add(group)
            g = hooks[(n, r)][1]
            p0, p3, c = _mid(t, f), _mid(n, g), _mid(t, e)
            ctrl = (p0, _lerp(p0, c, CORNER), _lerp(p3, c, CORNER), p3)
            out.append(Span("corner", group, ctrl, [(t, p, p, (f, e)), (n, r, r, (oe, g))], (t, e)))
            continue
        # The switch's other routes, leaving by the same edge.
        others = [x for x in layout[t] if x != p and e in PIECE_EDGES[x]]
        through = [x for x in others if straight(x)]
        points = None
        if mode == "back":
            ns = layout.get(n, [])
            st = [x for x in ns if straight(x) and oe in PIECE_EDGES[x]]
            if not st:
                continue
            sp = st[0]
            g = _far(sp, oe)
            p0, c, p3 = _mid(n, g), _mid(t, e), _mid(t, f)
            ctrl = (p0, _lerp(p0, c, BACK[0]), _lerp(p3, c, BACK[1]), p3)
            if others:
                points = (n, g)
            hide = [] if through else [(n, sp)]
            out.append(Span("back", (t, e), ctrl, [(n, f"{sp}to{p}", sp, (g, oe)), (t, p, p, (e, f))], (t, e),
                            points, hide))
        else:
            on = _carries_on(layout, t, p, f)
            if not on:
                continue
            t2, q = on
            g = _far(q, OPPOSITE[f])
            p0, p3 = _mid(t, e), _mid(t2, g)
            d0, d3 = (-OUT[e][0], -OUT[e][1]), outward(q, g)
            ctrl = (p0, (p0[0] + d0[0] * AHEAD[0], p0[1] + d0[1] * AHEAD[0]),
                    (p3[0] - d3[0] * AHEAD[1], p3[1] - d3[1] * AHEAD[1]), p3)
            if others:
                points = (t, e)
            out.append(Span("ahead", (t, e), ctrl, [(t, p, p, (e, f)), (t2, q, q, (OPPOSITE[f], g))], (t, f), points))
    return out


def switch_tile(span):
    """The tile where a span's hook starts square to its edge, which may also hold the switch's other routes."""
    return span.parts[1][0] if span.kind == "back" else span.parts[0][0]


def tile_clear(span, layout, flags):
    """The clear rule: [span]'s tiles hold only its own pieces, or in its switch tile only routes leaving by the
    same edge, and none has a slope, bridge or tunnel end, level crossing, platform, depot or signal ([flags])."""
    for tile, _label, sim, _edges in span.parts:
        if flags.get(tile, set()) & BLOCKING:
            return False
        here = layout.get(tile, [])
        if span.kind != "corner" and tile == switch_tile(span):
            if not all(span.group[1] in PIECE_EDGES[x] for x in here):
                return False
        elif here != [sim]:
            return False
    return True


def eased_spans(layout, flags=None, mode="back"):
    """The spans drawn eased: candidates whose tiles are clear and claimed by no other group's span. Everything
    else keeps the plain shapes. [layout] {tile: [pieces]}, [flags] {tile: set of BLOCKING words}. A piece's span
    reads its tile and the 24 round it (5 by 5)."""
    flags = flags or {}
    found = [sp for sp in candidates(layout, mode) if tile_clear(sp, layout, flags)]
    claims = {}
    for sp in found:
        for t in sp.tiles():
            claims.setdefault(t, set()).add(sp.group)
    return [sp for sp in found if all(len(claims[t]) == 1 for t in sp.tiles())]


def masks_to_layout(masks):
    """{tile: bitmask of PIECES} as the sim keeps it, to {tile: [pieces]}."""
    return {t: [p for i, p in enumerate(PIECES) if m >> i & 1] for t, m in masks.items() if m}


def span_of(masks, flags, tile, piece, mode="back"):
    """The eased span [piece] in [tile] is drawn on, or None for its plain shape. Reads only the 5 by 5 round
    [tile], as ChunkBaker would; a part in a straight tile has [piece] as its sim piece."""
    window = {(tile[0] + du, tile[1] + dv): masks.get((tile[0] + du, tile[1] + dv), 0)
              for du in range(-2, 3) for dv in range(-2, 3)}
    for sp in eased_spans(masks_to_layout(window), flags, mode):
        if any(t == tile and sim == piece for t, _l, sim, _e in sp.parts):
            return sp
    return None


def split(ctrl, t):
    """A cubic cut at t into two."""
    p0, p1, p2, p3 = ctrl
    a, b, c = _lerp(p0, p1, t), _lerp(p1, p2, t), _lerp(p2, p3, t)
    d, e = _lerp(a, b, t), _lerp(b, c, t)
    m = _lerp(d, e, t)
    return (p0, a, d, m), (m, e, c, p3)


def span_parts(span):
    """[(tile, label, sim, Curve in tile terms)] of a span, split where it crosses its middle edge."""
    tile, edge = span.split
    axis = 0 if edge in ("XM", "XP") else 1
    value = _mid(tile, edge)[axis]
    lo, hi = 0.0, 1.0
    flo = point(span.ctrl, lo)[axis] - value
    for _ in range(60):
        m = (lo + hi) / 2
        fm = point(span.ctrl, m)[axis] - value
        if fm * flo > 0:
            lo, flo = m, fm
        else:
            hi = m
    halves = split(span.ctrl, (lo + hi) / 2)
    out = []
    for (t, label, sim, edges), half in zip(span.parts, halves):
        local = tuple((q[0] - t[0], q[1] - t[1]) for q in half)
        out.append((t, label, sim, Curve.of(label, local, edges, straight=False)))
    return out


class Drawn:
    """One thing a tile draws: [label] its name, [sim] the sim piece it stands for, [curve] in tile terms, [looks],
    [switches] [(switch_parts in tile terms, whether it draws the lever stand)]."""

    def __init__(self, tile, label, sim, curve, looks, switches):
        self.tile, self.label, self.sim, self.curve, self.looks, self.switches = tile, label, sim, curve, looks, switches


def drawn(layout, flags=None, mode="back"):
    """Everything [layout] draws, tile by tile: plain shapes, and with [mode] the eased spans in place of the pieces
    they cover. With mode None just the plain shapes."""
    base = shapes(layout)
    spans = eased_spans(layout, flags, mode) if mode else []
    world = {(t, p): sh.curve.moved(t) for (t, p), sh in base.items()}
    covered, hidden, out = set(), set(), []
    for sp in spans:
        hidden.update(sp.hide)
        for t, label, sim, curve in span_parts(sp):
            if label == sim:
                covered.add((t, sim))
            out.append(Drawn(t, label, sim, curve, ("E", "E"), []))
    by_tile = {}
    for (tile, piece), sh in base.items():
        if (tile, piece) in covered or (tile, piece) in hidden:
            continue
        switches = []
        for end, others in sh.switches:
            if any((tile, o) in covered for o in others):
                continue
            edge = PIECE_EDGES[piece][end]
            mine = sh.curve if end == 0 else sh.curve.reversed()
            oc = [base[(tile, o)].curve if PIECE_EDGES[o][0] == edge else base[(tile, o)].curve.reversed() for o in others]
            turning = [x for x in [piece] + others if not straight(x)]
            switches.append((switch_parts(mine, Frame(edge), oc), piece == min(turning, key=PIECES.index)))
        d = Drawn(tile, piece, piece, sh.curve, sh.looks, switches)
        out.append(d)
    for d in out:
        by_tile.setdefault(d.tile, []).append(d)
    # Switch gear on eased spans, worked out along the whole span in world terms and handed to the tile its middle
    # lies in.
    groups = {}
    for sp in spans:
        if sp.points:
            groups.setdefault(sp.points, []).append(sp)
    for (pt, edge), legs in groups.items():
        t = legs[0].parts[1][0] if legs[0].kind == "back" else pt
        e = legs[0].group[1]
        routes = {}
        for sp in legs:
            routes[sp.parts[-1][2] if sp.kind == "back" else sp.parts[0][2]] = Curve.of("eased", sp.ctrl, ("XM", "XP"), straight=False)
        for x in layout[t]:
            if x in routes or e not in PIECE_EDGES[x]:
                continue
            c = world[(t, x)]
            c = c if PIECE_EDGES[x][0] == e else c.reversed()
            if straight(x) and pt != t:
                # The through straight from the points on: back across the straight tile too.
                a = _mid(pt, edge)
                b = c.ctrl[3]
                c = Curve.of(x, (a, _lerp(a, b, 1 / 3), _lerp(a, b, 2 / 3), b), ("XM", "XP"), straight=True)
            routes[x] = c
        turning = sorted([x for x in routes if not straight(x)], key=PIECES.index)
        for sp in legs:
            leg = sp.parts[-1][2] if sp.kind == "back" else sp.parts[0][2]
            sw = switch_parts(routes[leg], Frame(edge, pt), [c for x, c in routes.items() if x != leg])
            tiles = sp.tiles()

            def home(q, tiles=tiles):
                return min(tiles, key=lambda tt: (max(abs(q[0] - tt[0] - 0.5), abs(q[1] - tt[1] - 0.5))))
            for tt, label, _sim, _e in sp.parts:
                part = [d for d in by_tile[tt] if d.label == label][0]
                part.switches.append((localise(sw, tt, lambda q, tt=tt: home(q) == tt), leg == turning[0]))
    return out


# ---- Blender ------------------------------------------------------------------------------------


def _sweep(m, curve, lines, mat, caps=(True, True), ghost=None):
    """A prism along [curve]: each of [lines] is (across, lift in steps), a corner running along it, round in order.
    Each corner line runs from where it meets the tile edge at the start to where it meets it at the end. With
    [ghost] 0 or 1 it runs instead from where it meets that end's edge on out past it by GHOST tiles."""
    import bmesh
    import rig

    n = max(8, int(curve.length * 48))
    bm = bmesh.new()
    rings = []
    if ghost is not None:
        g = GHOST / curve.length
        spans = [(c - g, c) if ghost == 0 else (c, c + g) for c in (curve.clip(a, ghost) for (a, _) in lines)]
    else:
        spans = [(curve.clip(a, 0), curve.clip(a, 1)) for (a, _) in lines]
    for k in range(n + 1):
        ring = []
        for (a, z), (s, e) in zip(lines, spans):
            u, v = curve.offset(s + (e - s) * k / n, a)
            ring.append(bm.verts.new(rig.game(u, v, z)))
        rings.append(ring)
    c = len(lines)
    if caps[0]:
        bm.faces.new(list(reversed(rings[0])))
    if caps[1]:
        bm.faces.new(rings[-1])
    for k in range(n):
        for i in range(c):
            j = (i + 1) % c
            bm.faces.new((rings[k][i], rings[k][j], rings[k + 1][j], rings[k + 1][i]))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return m._mesh("curve", bm, mat)


def _box(m, centre, d, half_along, half_across, z0, z1, mat):
    """A box on the ground at [centre], its length along [d]."""
    import bmesh
    import rig

    nx, ny = -d[1], d[0]
    bm = bmesh.new()
    vs = []
    for z in (z0, z1):
        for (sa, sc) in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
            u = centre[0] + d[0] * sa * half_along + nx * sc * half_across
            v = centre[1] + d[1] * sa * half_along + ny * sc * half_across
            vs.append(bm.verts.new(rig.game(u, v, z)))
    for f in ((0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7)):
        bm.faces.new([vs[i] for i in f])
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return m._mesh("block", bm, mat)


def _bar(m, a, b, half, z0, z1, mat):
    """A box from point a to point b."""
    d = (b[0] - a[0], b[1] - a[1])
    length = math.hypot(*d)
    return _box(m, ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2), _norm(d), length / 2, half, z0, z1, mat)


def _bed_lines():
    return [(BED_BOTTOM, 0.0), (BED_TOP, 0.14), (-BED_TOP, 0.14), (-BED_BOTTOM, 0.0)]


def _rail_lines(side):
    """The three parts of a rail on [side] (+1 left, -1 right): foot, web and head, as corner lines."""
    g = side * GAUGE
    out = []
    for (half, z0, z1) in ((0.012, 0.225, 0.26), (0.004, 0.26, 0.36), (0.009, 0.36, 0.42)):
        out.append([(g + half, z0), (g + half, z1), (g - half, z1), (g - half, z0)])
    return out


def track(m, curve, layer, switches=(), holdout=()):
    """A curved piece of track in [layer]: "bed" the ballast, "rails" the ties, plates, switch gear and running
    rails, "ties" all but the running rails and "running" just those, so a tile with two or more pieces can lay
    every piece's ties before any piece's rails. For the game, "sleepers" is the ties, plates and long ties alone
    and "gear" the frogs, check rails, bars and lever stands, drawn over every piece's running rails, with the
    running rails of [holdout] curves cutting it where they pass in front. [switches] are (switch_parts(), whether
    it draws the lever stand) for its switch ends. What isn't in the layer is still built, unseen, for the shade it
    casts. Flat ground only."""
    from parts import material
    import scenery

    tie = material("tie", "#4e3626", rough=0.9)
    plate = material("tie plate", "#3e3c3a", metal=0.6, rough=0.6)
    rail = material("rail", "#6a6662", metal=0.9, rough=0.45)
    head = material("rail head", "#d8d4cc", metal=1.0, rough=0.2)
    steel = material("switch rod", "#3e3c3a", metal=0.6, rough=0.6)
    bed = [_sweep(m, curve, _bed_lines(), scenery.gravel())]
    ties = []
    count = max(4, round(curve.length * TIES))
    for i in range(count):
        t = curve.t_of((i + 0.5) / count * curve.length)
        p, d = curve.at(t)
        # Under a switch the long ties take the place of the piece's own.
        if any(sw["turnout"] and abs(sw["frame"].to(p)[1]) < OWN_FROM and sw["frame"].to(p)[0] < sw["long_end"] for sw, _ in switches):
            continue
        ties.append(_box(m, p, d, TIE_ALONG, TIE_HALF, 0.12, 0.2, tie))
        for side in (-1, 1):
            ties.append(_box(m, curve.offset(t, side * GAUGE), d, TIE_ALONG * 0.8, 0.022, 0.2, 0.225, plate))
    gear = []
    for sw, stand in switches:
        f = sw["frame"]
        for (s, l0, l1) in sw["long"]:
            ties.append(_bar(m, f.back(s, l0), f.back(s, l1), TIE_ALONG, 0.12, 0.2, tie))
        for (pt, d) in sw["frogs"]:
            gear.append(_box(m, pt, d, FROG_ALONG, 0.022, 0.2, 0.36, rail))
        for (a, b) in sw["checks"]:
            gear.append(_bar(m, a, b, 0.008, 0.225, 0.4, rail))
        if sw["bar"]:
            gear.append(_bar(m, *sw["bar"], 0.008, 0.2, 0.23, steel))
        if stand and sw["stand"]:
            gear += _stand(m, sw, steel)
    running = []
    for side in (-1, 1):
        for lines, mat in zip(_rail_lines(side), (rail, rail, head)):
            running.append(_sweep(m, curve, lines, mat))
    # Straight on past each end from the tile edge, unseen, so the shade runs on into the next piece.
    ghosts = []
    for end in (0, 1):
        if layer != "bed":
            ghosts.append(_sweep(m, curve, _bed_lines(), scenery.gravel(), ghost=end))
        for side in (-1, 1):
            g = side * GAUGE
            ghosts.append(_sweep(m, curve, [(g + 0.012, 0.225), (g + 0.012, 0.42), (g - 0.012, 0.42), (g - 0.012, 0.225)], rail, ghost=end))
    cut = []
    if layer == "gear":
        for c in holdout:
            for side in (-1, 1):
                for lines in _rail_lines(side):
                    cut.append(_sweep(m, c, lines, rail))
        for obj in running + cut:
            obj.is_holdout = True
    seen = {"bed": bed, "rails": ties + gear + running, "ties": ties + gear, "running": running, "sleepers": ties,
            "gear": gear + running + cut}[layer]
    for obj in bed + ties + gear + running + ghosts + cut:
        if obj not in seen:
            obj.visible_camera = False
            obj["unseen"] = True


def _stand(m, sw, steel):
    """A switch's lever stand beside the points, as rail_set's: a low base, a post and a red target, with a rod to
    the points."""
    from parts import material

    black = material("stand black", "#2a2826", rough=0.6)
    red = material("target red", "#b8302a", rough=0.5)
    base = sw["stand"]
    d = sw["frame"].i
    return ([_bar(m, *sw["rod"], 0.006, 0.2, 0.23, steel)] if sw["rod"] else []) + [
        _box(m, base, d, 0.025, 0.024, 0.0, 0.25, black),
        _box(m, base, d, 0.008, 0.007, 0.25, 1.15, black),
        _box(m, base, d, 0.004, 0.026, 0.9, 1.15, red),
    ]
