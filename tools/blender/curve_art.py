"""The curved track sprites the game draws (docs/CURVES_PLAN.md, C2), by the keys TileRecorder works out for each tile.

    python3 tools/blender/curve_art.py          rewrites tools/blender/curve_keys.txt
    python3 tools/blender/curve_art.py --check  says whether it is up to date
    python3 tools/blender/curve_art.py --reference  rewrites CurveKeysTest's sim/src/jvmTest/resources/curve_keys_reference.txt

No Blender needed: render_scenery.py renders what sprites() gives and gen_tiles.py packs the keys in curve_keys.txt.

A tile draws each of its curves in four layers, every curve's bed, then every curve's ties, then every curve's running
rails, then every curve's switch gear. Keys, in view terms as the tile is drawn:
- bed and running: kind (p plain, b eased back, c corner), the label (the sim piece, or a straight easing into a
  diagonal as XtoN), then the plain looks at the piece's two ends, or for an eased part the edge where it crosses
  into the span's other tile, off that edge's middle. pNTD, bXtoN_XP, cN_YM.
- ties: the bed key, then for a plain diagonal _ and t or - for each end, whether a turnout's long ties lie there, or
  for an eased part t if they do.
- gear: the frogs, check rails, bar and lever stand of a switch end. A plain diagonal's at edge E: gN, E, its far
  end's look, then the other routes leaving by E: s for the straight, q and the other diagonal's far look. An eased
  part's: g, its bed key, then the same, with E for an other diagonal that's eased too.

The pieces are found by drawing many layouts as curves.drawn() does, from the 5 by 5 round each tile as the game
does, and every layout giving a key must draw the same thing for it, or this stops.
"""
import os
import random
import sys

import curves

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "curve_keys.txt")
EDGES = ["XM", "XP", "YM", "YP"]
FLAGS = ["slope", "bridge", "tunnel", "crossing", "platform", "depot", "signal"]
LAYERS = ("bed", "ties", "running", "gear")
SAMPLES = 32000
SEED = 7


def _r(x):
    return f"{x + 0.0:.4f}".replace("-0.0000", "0.0000")


def _pt(p):
    return f"{_r(p[0])},{_r(p[1])}"


def window(masks, tile):
    return {(tile[0] + du, tile[1] + dv): masks.get((tile[0] + du, tile[1] + dv), 0) for du in range(-2, 3) for dv in range(-2, 3)}


class Tile:
    """What one tile draws, worked out from the 5 by 5 round it: [drawn] curves.Drawn, [kinds] by label p, b or c."""

    # By the window round a tile, relative to it, as many layouts repeat.
    seen = {}

    def __init__(self, masks, flags, tile):
        win = window(masks, tile)
        rel = tuple(win[(tile[0] + du, tile[1] + dv)] for du in range(-2, 3) for dv in range(-2, 3))
        rf = tuple(sorted((t[0] - tile[0], t[1] - tile[1], w) for t, f in flags.items() if t in win for w in f))
        if (rel, rf) not in Tile.seen:
            layout = curves.masks_to_layout(win)
            wf = {t: f for t, f in flags.items() if t in win}
            drawn = [d for d in curves.drawn(layout, wf, "back") if d.tile == tile]
            kinds = {}
            for sp in curves.eased_spans(layout, wf, "back"):
                for t, label, _sim, _e in sp.parts:
                    if t == tile:
                        kinds[label] = "b" if sp.kind == "back" else "c"
            Tile.seen[(rel, rf)] = (layout.get(tile, []), drawn, {d.label: kinds.get(d.label, "p") for d in drawn})
        self.tile = tile
        self.pieces, self.drawn, self.kinds = Tile.seen[(rel, rf)]

    def by_sim(self, piece):
        return [d for d in self.drawn if d.sim == piece and d.label == piece]


def split_edge(d):
    """The end of an eased part off its edge's middle, where it crosses into the span's other tile."""
    for end, edge in enumerate(d.curve.edges):
        p = d.curve.ctrl[0 if end == 0 else 3]
        m = curves.EDGE_MID[edge]
        if abs(p[0] - m[0]) + abs(p[1] - m[1]) > 1e-6:
            return edge
    raise ValueError(f"eased part {d.label} meets both edges at their middles")


def far_look(d, edge):
    """d's look at its end other than [edge]."""
    ea, eb = curves.PIECE_EDGES[d.sim]
    return d.looks[1] if ea == edge else d.looks[0]


def bed_key(t, d):
    kind = t.kinds[d.label]
    if kind == "p":
        return f"p{d.label}{d.looks[0]}{d.looks[1]}"
    return f"{kind}{d.label}_{split_edge(d)}"


def _others(t, piece, edge):
    return [x for x in t.pieces if x != piece and edge in curves.PIECE_EDGES[x]]


def _ctx(t, piece, edge):
    """The other routes leaving [t] by [edge] beside [piece]: s for a straight, q and the diagonal's looks or E."""
    out = ""
    for x in _others(t, piece, edge):
        if curves.straight(x):
            out = "s" + out
        else:
            q = t.by_sim(x)[0]
            out += "q" + ("E" if t.kinds[q.label] != "p" else q.looks[0] + q.looks[1])
    return out


def keys(tiles, tile):
    """[(Drawn, bed key, ties key, [(gear key, edge)])] for [tile], from [tiles](tile) giving its Tile."""
    t = tiles(tile)
    out = []
    covered = {d.sim for d in t.drawn if d.label == d.sim and t.kinds[d.label] != "p"}
    for d in t.drawn:
        bk = bed_key(t, d)
        gear = []
        kind = t.kinds[d.label]
        if kind == "p":
            tk = bk
            if not curves.straight(d.sim):
                ends = ""
                for edge in curves.PIECE_EDGES[d.sim]:
                    others = _others(t, d.sim, edge)
                    on = others and not any(o in covered for o in others)
                    ends += "t" if on and any(curves.straight(o) for o in others) else "-"
                    if on:
                        gear.append((f"g{d.sim}{edge}{d.looks[0]}{d.looks[1]}{_ctx(t, d.sim, edge)}", edge))
                if "t" in ends:
                    tk = f"{bk}_{ends}"
        elif kind == "b":
            e = split_edge(d)
            if d.label != d.sim:
                # The straight tile's part: the switch tile is across its split edge.
                st = tiles((tile[0] + curves.STEP[e][0], tile[1] + curves.STEP[e][1]))
                piece, edge = d.label[3:], curves.OPPOSITE[e]
            else:
                st, piece, edge = t, d.sim, e
            ctx = _ctx(st, piece, edge)
            tk = bk + ("t" if ctx.startswith("s") else "")
            if ctx:
                gear.append((f"g{bk}{ctx}", edge))
        else:
            tk = bk
        out.append((d, bk, tk, gear))
    return out


def _sw_ties(sw):
    if not sw["turnout"]:
        return ""
    f = sw["frame"]
    return "|".join([_pt(f.o), _r(sw["long_end"])] + [f"{_r(s)},{_r(a)},{_r(b)}" for s, a, b in sw["long"]])


def _sw_gear(sw, stand):
    f = sw["frame"]
    bits = [_pt(f.o), str(stand)]
    bits += [f"{_pt(p)},{_pt(d)}" for p, d in sw["frogs"]]
    bits += [f"{_pt(a)},{_pt(b)}" for a, b in sw["checks"]]
    for k in ("bar", "rod"):
        bits.append(f"{_pt(sw[k][0])},{_pt(sw[k][1])}" if sw[k] else "-")
    bits.append(_pt(sw["stand"]) if stand and sw["stand"] else "-")
    return ";".join(bits)


def _ctrl(c):
    return ";".join(_pt(p) for p in c.ctrl)


def _switch_at(d, edge):
    """d's switch at [edge] of its tile, as curves.drawn() gives it, or None."""
    m = curves.EDGE_MID[edge]
    for sw, stand in d.switches:
        o = sw["frame"].o
        if abs(o[0] - m[0]) + abs(o[1] - m[1]) < 1e-6:
            return sw, stand
    return None


def _holdout(t, d, edge):
    """The curves whose running rails hide d's switch gear at [edge]: d and every route leaving by it, as drawn."""
    if t.kinds[d.label] == "p":
        others = _others(t, d.sim, edge)
        mine = [d] + [x for x in t.drawn if x.label == x.sim and x.sim in others]
    elif d.label != d.sim:
        # The straight tile of an eased switch holds only its routes.
        mine = list(t.drawn)
    else:
        mine = [x for x in t.drawn if edge in x.curve.edges]
    return sorted(mine, key=lambda x: x.label)


def sample(rng):
    """A layout grown as track is laid: a few runs that keep mostly straight on, crossing and joining each other,
    with now and then a flag on a tile."""
    masks, flags = {}, {}
    for _ in range(rng.randint(1, 4)):
        tile = (rng.randint(-2, 2), rng.randint(-2, 2))
        edge = rng.choice(EDGES)
        for _ in range(rng.randint(2, 9)):
            choices = [p for p in curves.PIECES if edge in curves.PIECE_EDGES[p]]
            p = choices[0] if rng.random() < 0.5 and curves.straight(choices[0]) else rng.choice(choices)
            p = [x for x in choices if curves.straight(x)][0] if rng.random() < 0.45 else p
            masks[tile] = masks.get(tile, 0) | (1 << curves.PIECES.index(p))
            out = curves._far(p, edge)
            tile = (tile[0] + curves.STEP[out][0], tile[1] + curves.STEP[out][1])
            edge = curves.OPPOSITE[out]
    for tile in list(masks):
        if rng.random() < 0.05:
            flags[tile] = {rng.choice(FLAGS)}
    return masks, flags


def _draws(layer, t, d, tk, gear_edge):
    """What [layer] draws for Drawn [d] in Tile [t], as text to compare and as what to draw."""
    c = _ctrl(d.curve)
    if layer in ("bed", "running"):
        return c, d.curve
    if layer == "ties":
        return c + "|" + "/".join(_sw_ties(sw) for sw, _st in d.switches if sw["turnout"]), (d.curve, d.switches)
    found = _switch_at(d, gear_edge) if t.kinds[d.label] == "p" else (d.switches[0] if d.switches else None)
    if not found:
        raise SystemExit(f"gear at {gear_edge} for {d.label} has no switch in curves.drawn()")
    sw, stand = found
    hold = _holdout(t, d, gear_edge)
    return c + "|" + _sw_gear(sw, stand) + "|" + "/".join(_ctrl(h.curve) for h in hold), \
        (d.curve, [(sw, stand)], [h.curve for h in hold])


def eased_layouts():
    """Every eased back span's switch: each hook with or without the through straight, and with or without the other
    diagonal leaving by the same edge, that one open at its far end (so eased too), meeting a straight there or
    carrying on into a diagonal line that does (so T at both ends or the crossover's S)."""
    out = []
    bit = lambda p: 1 << curves.PIECES.index(p)  # noqa: E731
    nxt = lambda t, e: (t[0] + curves.STEP[e][0], t[1] + curves.STEP[e][1])  # noqa: E731
    for p in curves.PIECES[2:]:
        for e in curves.PIECE_EDGES[p]:
            oe = curves.OPPOSITE[e]
            sp = "X" if e in ("XM", "XP") else "Y"
            q = [x for x in curves.PIECES[2:] if x != p and e in curves.PIECE_EDGES[x]][0]
            h = curves._far(q, e)
            for through in (False, True):
                for qf in (None, "D", "T", "S"):
                    masks = {}

                    def put(t, piece):
                        masks[t] = masks.get(t, 0) | bit(piece)
                    t = (0, 0)
                    n = nxt(t, e)
                    put(t, p)
                    put(n, sp)
                    put(nxt(n, e), sp)
                    if through:
                        put(t, sp)
                        put(nxt(t, oe), sp)
                    if qf:
                        put(t, q)
                        nh = nxt(t, h)
                        if qf == "T":
                            put(nh, "X" if h in ("XM", "XP") else "Y")
                        elif qf == "S":
                            mine = curves.outward(q, h)
                            r = [x for x in curves.PIECES[2:] if curves.OPPOSITE[h] in curves.PIECE_EDGES[x]
                                 and all(abs(a + b) < 1e-9 for a, b in zip(mine, curves.outward(x, curves.OPPOSITE[h])))][0]
                            put(nh, r)
                            g = curves._far(r, curves.OPPOSITE[h])
                            put(nxt(nh, g), "X" if g in ("XM", "XP") else "Y")
                    out.append((masks, {}))
    # And the look sheets' scenes, which have every corner, at each quarter turn.
    import curves_look
    turn = {"XM": "YM", "XP": "YP", "YM": "XP", "YP": "XM"}
    for scene in curves_look.SCENES.values():
        layout = scene
        for _ in range(4):
            out.append(({t: sum(bit(p) for p in ps) for t, ps in layout.items()}, {}))
            layout = {(-t[1], t[0]): [_piece_of(turn[curves.PIECE_EDGES[p][0]], turn[curves.PIECE_EDGES[p][1]])
                                      for p in ps] for t, ps in layout.items()}
    return out


def _piece_of(a, b):
    return [p for p in curves.PIECES if set(curves.PIECE_EDGES[p]) == {a, b}][0]


def _layouts(seed, samples):
    """The layouts of one run: (index, tiles(tile) giving each Tile, the tiles to look at). Seed -1 is
    eased_layouts()."""
    rng = random.Random(seed)
    fixed = eased_layouts() if seed < 0 else None
    for i in range(len(fixed) if fixed else samples):
        masks, flags = fixed[i] if fixed else sample(rng)
        cache = {}

        def tiles(tile, masks=masks, flags=flags, cache=cache):
            if tile not in cache:
                cache[tile] = Tile(masks, flags, tile)
            return cache[tile]

        look = [t for t in sorted(masks) if masks[t] and max(abs(t[0]), abs(t[1])) <= 2
                and not flags.get(t, set()) & {"slope", "bridge", "tunnel", "crossing", "platform", "depot"}]
        yield i, tiles, look


def _entries(tiles, tile):
    """(layer, key, Drawn, ties key, gear edge) for everything [tile] draws."""
    for d, bk, tk, gear in keys(tiles, tile):
        yield "bed", bk, d, tk, None
        yield "running", bk, d, tk, None
        yield "ties", tk, d, tk, None
        for gk, edge in gear:
            yield "gear", gk, d, tk, edge


def _found(args):
    """Every key [samples] layouts from [seed] draw: {layer: {key: (what it draws as text, where it was found)}}."""
    samples, seed = args
    out = {layer: {} for layer in LAYERS}
    for i, tiles, look in _layouts(seed, samples):
        for tile in look:
            t = tiles(tile)
            for layer, key, d, tk, edge in _entries(tiles, tile):
                sig = _draws(layer, t, d, tk, edge)[0]
                if key in out[layer]:
                    if out[layer][key][0] != sig:
                        raise SystemExit(f"{layer} {key} draws two ways:\n  {out[layer][key][0]}\n  {sig}")
                    continue
                out[layer][key] = (sig, (seed, i, tile[0], tile[1]))
    return out


def sprites(samples=SAMPLES, seed=SEED, workers=1):
    """{layer: {key: (seed, layout index, x, y)}}, where each key was first found. Stops on a key that two layouts
    draw differently."""
    jobs = [(samples // 16, seed * 1000 + k) for k in range(16)] + [(0, -1)]
    if workers > 1:
        import multiprocessing
        with multiprocessing.Pool(workers) as pool:
            parts = pool.map(_found, jobs)
    else:
        parts = [_found(j) for j in jobs]
    out = {layer: {} for layer in LAYERS}
    for part in parts:
        for layer in LAYERS:
            for key, (sig, where) in part[layer].items():
                if key in out[layer] and out[layer][key][0] != sig:
                    raise SystemExit(f"{layer} {key} draws two ways:\n  {out[layer][key][0]}\n  {sig}")
                out[layer].setdefault(key, (sig, where))
    # Plain pieces are worked out from their looks for every mix, layouts or no, and must agree with what layouts
    # found.
    for layer, key, sig, _what in plain_sprites():
        if key in out[layer] and out[layer][key][0] != sig:
            raise SystemExit(f"{layer} {key} drawn from its looks differs:\n  {out[layer][key][0]}\n  {sig}")
        out[layer][key] = (sig, None)
    return {layer: {k: v[1] for k, v in out[layer].items()} for layer in LAYERS}


LOOK_PAIRS = ["TT", "TD", "DT", "DD", "TS", "ST"]


def _end_dir(piece, edge, look):
    """A plain piece's outward direction at [edge] for [look], as curves.end_look() gives it."""
    if curves.straight(piece) or look == "T":
        return curves.OUT[edge]
    d = curves.outward(piece, edge)
    if look == "D":
        return d
    axis = curves.OUT[curves._far(piece, edge)]
    a, b = curves._turn(d, curves.SWING_S), curves._turn(d, -curves.SWING_S)
    return a if abs(a[0] * axis[0] + a[1] * axis[1]) < abs(b[0] * axis[0] + b[1] * axis[1]) else b


def plain_curve(piece, looks):
    """A plain piece's curve from its looks, as curves.shapes() draws it."""
    ea, eb = curves.PIECE_EDGES[piece]
    oa, ob = _end_dir(piece, ea, looks[0]), _end_dir(piece, eb, looks[1])
    if curves.straight(piece):
        ha = hb = 1.0 / 3
    else:
        ha, hb = curves.HANDLE[(looks[0], looks[1])], curves.HANDLE[(looks[1], looks[0])]
    return curves.Curve(piece, (-oa[0], -oa[1]), ob, ha, hb)


def _from(piece, curve, edge):
    return curve if curves.PIECE_EDGES[piece][0] == edge else curve.reversed()


def plain_switch(piece, looks, edge, others):
    """The switch [piece] draws at [edge] with [others] {piece: looks} leaving by it, as curves.drawn() works it
    out: (switch_parts, whether it draws the lever stand)."""
    mine = _from(piece, plain_curve(piece, looks), edge)
    order = sorted(others, key=curves.PIECES.index)
    oc = [_from(o, plain_curve(o, others[o]), edge) for o in order]
    turning = [x for x in [piece] + order if not curves.straight(x)]
    return curves.switch_parts(mine, curves.Frame(edge), oc), piece == min(turning, key=curves.PIECES.index)


def plain_sprites():
    """(layer, key, what it draws as text, what to draw) for every plain piece and mix of looks."""
    out = []
    for piece in curves.PIECES:
        for looks in (["TT"] if curves.straight(piece) else LOOK_PAIRS):
            c = plain_curve(piece, looks)
            bk = f"p{piece}{looks}"
            out.append(("bed", bk, _ctrl(c), c))
            out.append(("running", bk, _ctrl(c), c))
            out.append(("ties", bk, _ctrl(c) + "|", (c, [])))
            if curves.straight(piece):
                continue
            edges = curves.PIECE_EDGES[piece]
            for ends in ("t-", "-t", "tt"):
                sws = [plain_switch(piece, looks, e, {"X" if e in ("XM", "XP") else "Y": "TT"})
                       for e, on in zip(edges, ends) if on == "t"]
                out.append(("ties", f"{bk}_{ends}", _ctrl(c) + "|" + "/".join(_sw_ties(sw) for sw, _st in sws),
                            (c, sws)))
            for e in edges:
                st = "X" if e in ("XM", "XP") else "Y"
                q = [x for x in curves.PIECES[2:] if x != piece and e in curves.PIECE_EDGES[x]][0]
                for ctx, others in [("s", {st: "TT"})] + [(f"{a}q{ql}", dict([(q, ql)] + ([(st, "TT")] if a else [])))
                                                         for a in ("", "s") for ql in LOOK_PAIRS]:
                    sw, stand = plain_switch(piece, looks, e, others)
                    hold = sorted([(piece, c)] + [(o, plain_curve(o, others[o])) for o in others], key=lambda x: x[0])
                    sig = _ctrl(c) + "|" + _sw_gear(sw, stand) + "|" + "/".join(_ctrl(h) for _o, h in hold)
                    out.append(("gear", f"g{piece}{e}{looks}{ctx}", sig, (c, [(sw, stand)], [h for _o, h in hold])))
    return out


_PLAIN = {}


def constructed(layer, key):
    """What to draw for a plain piece's [key] in [layer], from its looks."""
    if not _PLAIN:
        _PLAIN.update({(lay, k): got for lay, k, _sig, got in plain_sprites()})
    return _PLAIN[(layer, key)]


def what(layer, key, where):
    """What to draw for [key] in [layer], from the layout it was found in: bed and running a Curve, ties (Curve,
    switches), gear (Curve, switches, holdout curves)."""
    if where is None:
        return constructed(layer, key)
    seed, index, x, y = where
    for i, tiles, _look in _layouts(seed, index + 1):
        if i < index:
            continue
        for lay, k, d, tk, edge in _entries(tiles, (x, y)):
            if lay == layer and k == key:
                return _draws(layer, tiles((x, y)), d, tk, edge)[1]
    raise KeyError(f"{layer} {key} isn't at {where}")


def text(found):
    lines = ["# Written by tools/blender/curve_art.py: curved track sprites, by layer and key, and the layout each"
             " was found in (seed, index, tile)."]
    for layer in LAYERS:
        for key in sorted(found[layer]):
            where = found[layer][key]
            lines.append(f"{layer} {key} " + (" ".join(str(w) for w in where) if where else "looks"))
    return "\n".join(lines) + "\n"


def read_keys(path=OUT):
    """{layer: {key: where}} from curve_keys.txt."""
    out = {layer: {} for layer in LAYERS}
    for line in open(path):
        if line.startswith("#") or not line.strip():
            continue
        layer, key, *where = line.split()
        out[layer][key] = None if where == ["looks"] else tuple(int(w) for w in where)
    return out


REFERENCE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "sim", "src", "jvmTest", "resources",
                         "curve_keys_reference.txt")


def reference():
    """The keys of every tile of export_curves.py's layouts and eased_layouts(), for CurveKeysTest: "# name", then
    "T x y mask flags" for each tile and "K x y layer key" for each sprite it draws."""
    import export_curves
    scenes, name = [], None
    for line in export_curves.build().splitlines():
        w = line.split()
        if w[0] == "#":
            name = w[1]
            scenes.append((name, {}, {}))
        elif w[0] == "T":
            t = (int(w[1]), int(w[2]))
            scenes[-1][1][t] = int(w[3])
            scenes[-1][2][t] = {f for f, b in export_curves.FLAG.items() if int(w[4]) & b}
    for k, (masks, flags) in enumerate(eased_layouts()):
        scenes.append((f"eased{k}", masks, flags))
    out = []
    for name, masks, flags in scenes:
        out.append(f"# {name}")
        cache = {}

        def tiles(tile, masks=masks, flags=flags, cache=cache):
            if tile not in cache:
                cache[tile] = Tile(masks, flags, tile)
            return cache[tile]
        for t in sorted(masks):
            if masks[t]:
                bits = sum(b for f, b in export_curves.FLAG.items() if f in flags.get(t, set()))
                out.append(f"T {t[0]} {t[1]} {masks[t]} {bits}")
        for t in sorted(masks):
            if masks[t]:
                got = [f"{layer} {key}" for layer, key, _d, _tk, _e in _entries(tiles, t)]
                out += [f"K {t[0]} {t[1]} {k}" for k in sorted(got)]
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    if "--reference" in sys.argv:
        open(REFERENCE, "w").write(reference())
        print(f"wrote {REFERENCE}")
        sys.exit(0)
    found = sprites(int(os.environ.get("CURVE_SAMPLES", SAMPLES)), workers=12)
    body = text(found)
    for layer in LAYERS:
        print(layer, len(found[layer]))
    if "--check" in sys.argv:
        ok = os.path.exists(OUT) and open(OUT).read() == body
        print("up to date" if ok else "STALE")
        sys.exit(0 if ok else 1)
    if "--dry" not in sys.argv:
        open(OUT, "w").write(body)
        print(f"wrote {OUT}")
