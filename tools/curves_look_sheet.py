"""Lays out the curve look test (docs/CURVES_PLAN.md): today's track beside curved track, at levels 0 and 2.

    uv run --with pillow --with numpy tools/curves_look_sheet.py RENDERS OUT_DIR

RENDERS holds render_curves_look.py's files; today's track comes from build/renders/scenery.
"""
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools/art"))
sys.path.insert(0, str(ROOT / "tools/blender"))

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont  # noqa: E402

import ground  # noqa: E402
import curves  # noqa: E402
import curves_look  # noqa: E402

SCENERY = ROOT / "build/renders/scenery"
HW, HH, TOP = 128, 64, 384
FONT = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 15)
SMALL = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 12)
BG = (40, 42, 46, 255)
# Bogies at the car's middle plus and minus this share of half its length.
BOGIE = 0.7
CAR_LEN = 10 / 16


def shrink(img, f):
    return img.convert("RGBa").resize((img.width // f, img.height // f), Image.BOX).convert("RGBA")


def outline(img):
    solid = img.getchannel("A").point(lambda v: 255 if v > 200 else 0)
    ring = ImageChops.subtract(solid.filter(ImageFilter.MaxFilter(3)), solid)
    out = img.copy()
    out.paste(Image.new("RGBA", img.size, (36, 32, 28, 235)), (0, 0), ring)
    return out


_cache = {}


def sprite(path, car=False):
    """A render shrunk to level 0, cars outlined as gen_tiles does."""
    if path not in _cache:
        img = shrink(Image.open(path).convert("RGBA"), 2)
        _cache[path] = outline(img) if car else img
    return _cache[path]


def grass(u, v):
    key = ("grass", (u * 7 + v * 3) % 3)
    if key not in _cache:
        _cache[key] = shrink(ground.ground("meadow", key[1], 0), 2)
    return _cache[key]


def panel(tiles, draws, cars=()):
    """Ground over [tiles] with a tile round them, then each tile's sprites from [draws], {tile: [images in order]},
    back to front, then [cars], (image, u, v), at level 0."""
    us = [t[0] for t in tiles]
    vs = [t[1] for t in tiles]
    area = [(u, v) for u in range(min(us), max(us) + 1) for v in range(min(vs), max(vs) + 1)]
    x0 = min(u - v for u, v in area) * HW - HW
    x1 = max(u - v for u, v in area) * HW + 3 * HW
    y0 = min(u + v for u, v in area) * HH - TOP
    y1 = max(u + v for u, v in area) * HH + 2 * HH + TOP
    img = Image.new("RGBA", (x1 - x0, y1 - y0), (0, 0, 0, 0))

    def at(u, v):
        return (round((u - v) * HW - HW - x0), round((u + v) * HH - TOP - y0))

    order = sorted(area, key=lambda t: (t[0] + t[1], t[0]))
    for u, v in order:
        img.alpha_composite(grass(u, v), at(u, v))
    for t in order:
        for s in draws.get(t, []):
            img.alpha_composite(s, at(*t))
    for car, u, v in sorted(cars, key=lambda c: c[1] + c[2]):
        img.alpha_composite(car, at(u - 0.5, v - 0.5))
    # Down to the ground's own diamond outline: the box round the ground tiles.
    gx0 = min(u - v for u, v in area) * HW - HW - x0 + 0
    gx1 = max(u - v for u, v in area) * HW + HW - x0
    gy0 = min(u + v for u, v in area) * HH - y0
    gy1 = max(u + v for u, v in area) * HH + 2 * HH - y0
    box = img.getchannel("A").point(lambda a: 255 if a > 12 else 0).getbbox()
    return img.crop((min(gx0, box[0]), min(gy0, box[1]), max(gx1, box[2]), max(gy1, box[3])))


def today(scene, per_piece=False):
    """The scene as the game draws it now: each piece's bed, then its rails, or a railset for two or more."""
    draws = {}
    for t, ps in curves_look.SCENES[scene].items():
        out = [sprite(SCENERY / f"track_{p}_0.png") for p in ps]
        if len(ps) == 1 or per_piece:
            out += [sprite(SCENERY / f"rails_{p}_0.png") for p in by_depth(ps)]
        else:
            mask = sum(1 << curves.PIECES.index(p) for p in ps)
            out.append(sprite(SCENERY / f"railset_{mask}.png"))
        draws[t] = out
    return draws


def by_depth(ps):
    def depth(p):
        a, b = curves.chord(p)
        return (a[0] + b[0] + a[1] + b[1], a[0] + b[0])
    return sorted(ps, key=depth)


def curved(scene, renders, mode=None):
    """The scene with curved pieces drawn in [mode]: every bed, then each piece's rails, and on a tile with two or
    more every piece's ties before any running rails, all back to front."""
    draws = {}
    for t, label, c, key, _sw in curves_look.pieces(scene, mode):
        a, b = c.ctrl[0], c.ctrl[3]
        draws.setdefault(t, []).append(((a[0] + b[0] + a[1] + b[1], a[0] + b[0]), key))
    out = {}
    for t, d in draws.items():
        keys = [k for _, k in sorted(d)]
        out[t] = [sprite(renders / f"bed_{k}.png") for k in keys]
        if len(keys) > 1:
            out[t] += [sprite(renders / f"{layer}_{k}.png") for layer in ("ties", "running") for k in keys]
        else:
            out[t] += [sprite(renders / f"rails_{k}.png") for k in keys]
    return out


def labelled(img, text, font=FONT):
    pad = 6
    h = font.size + 2 * pad
    out = Image.new("RGBA", (max(img.width, int(font.getlength(text)) + 2 * pad), img.height + h), BG)
    ImageDraw.Draw(out).text((pad, pad - 1), text, font=font, fill=(235, 235, 235, 255))
    out.alpha_composite(img, (0, h))
    return out


def row(images, gap=16):
    w = sum(i.width for i in images) + gap * (len(images) + 1)
    h = max(i.height for i in images) + 2 * gap
    out = Image.new("RGBA", (w, h), BG)
    x = gap
    for i in images:
        out.alpha_composite(i, (x, gap))
        x += i.width + gap
    return out


def column(images, gap=8):
    w = max(i.width for i in images)
    out = Image.new("RGBA", (w, sum(i.height for i in images) + gap * (len(images) - 1)), BG)
    y = 0
    for i in images:
        out.alpha_composite(i, (0, y))
        y += i.height + gap
    return out


def levels(title, panels):
    """[panels], (label, level 0 image), as a sheet: level 0 in a row, then level 2 at its size and blown up 3 times."""
    l0 = row([labelled(i, f"{name}, level 0") for name, i in panels])
    l2 = [shrink(i, 4) for _, i in panels]
    l2a = row([labelled(i, f"{name}, level 2", SMALL) for (name, _), i in zip(panels, l2)])
    l2b = row([labelled(i.resize((i.width * 3, i.height * 3), Image.NEAREST), f"{name}, level 2 shown 3x") for (name, _), i in zip(panels, l2)])
    return column([labelled(Image.new("RGBA", (1, 1), BG), title, ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 20)), l0, l2a, l2b])


def along(path, s):
    """Point and direction s tiles along the path, [Curve] in world (u, v)."""
    for c in path:
        if s <= c.length or c is path[-1]:
            return c.at(c.t_of(s))
        s -= c.length


def turnout_path(mode):
    """The turnout's branch as a train runs it in [mode], [Curve] in world terms, and where its eased span starts and
    ends along it."""
    layout = curves_look.SCENES["turnout"]
    plain = {(t, p): sh.curve.moved(t) for (t, p), sh in curves.shapes(layout).items()}
    span = [sp for sp in curves.eased_spans(layout, {}, mode)][0]
    eased = curves.Curve.of("eased", span.ctrl, ("XM", "XP"), straight=False)
    before = [plain[((0, 0), "X")]] if mode == "ahead" else []
    after = [plain[((1, 1), "E")]] if mode == "back" else []
    after += [plain[((2, 1), "W")], plain[((2, 2), "E")]]
    start = sum(c.length for c in before)
    return before + [eased] + after, start, start + eased.length


def car_at(path, s, renders):
    """The car with its middle s along [path]: bogies on the line, the body between them at the nearest sixteenth."""
    half = BOGIE * CAR_LEN / 2
    (p1, _), (p2, _) = along(path, s - half), along(path, s + half)
    mid = ((p1[0] + p2[0]) / 2, (p1[1] + p2[1]) / 2)
    ang = math.degrees(math.atan2(p2[1] - p1[1], p2[0] - p1[0]))
    h = curves.heading16((p2[0] - p1[0], p2[1] - p1[1]))
    return (sprite(renders / f"car_{h}.png", car=True), *mid), ang, h


def main():
    renders = Path(sys.argv[1])
    out = Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    v = curves_look.VERSION
    bold = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 20)
    back, ahead = "v3 eased back", "v3 eased ahead"
    sheets = [
        ("2_ease", "ease", "2. A diagonal line easing into a straight. Back: over the last diagonal tile and the first straight tile, no swing. Ahead: over the last two diagonal tiles, swings to 59 degrees"),
        ("3_turnout", "turnout", "3. A turnout onto a diagonal line. Back: points at the far edge of the tile before, no swing. Ahead: points in the switch tile, swings to 59 degrees over two tiles"),
        ("5_wye_diagonal", "wye_diagonal", "5. A straight splitting into two diagonal lines, both legs eased the same way. (The wye onto a crossing line is quarter circles and doesn't change)"),
        ("7_corner", "corner", "7. Two diagonal lines meeting at a right angle: one 90 degree arc over both tiles, the same in either mode"),
        ("8_fallback", "ladder", "8. Fallback: a second turnout a tile before the first. The second eases, the first keeps the C0b hook as its tiles aren't clear"),
    ]
    for name, scene, title in sheets:
        tiles = list(curves_look.SCENES[scene])
        panels = [("today", panel(tiles, today(scene))), ("C0b, v2", panel(tiles, curved(scene, renders, None)))]
        if scene == "corner":
            panels.append(("v3 eased", panel(tiles, curved(scene, renders, "back"))))
        else:
            panels += [(back, panel(tiles, curved(scene, renders, "back"))), (ahead, panel(tiles, curved(scene, renders, "ahead")))]
        levels(title, panels).convert("RGB").save(out / f"{name}_{v}.png")

    # 9. The car on the eased turnout's branch at three points, in each mode.
    tiles = list(curves_look.SCENES["turnout"])
    rows = []
    for mode, name in (("back", back), ("ahead", ahead)):
        path, s0, s1 = turnout_path(mode)
        r = []
        for k, f in enumerate((0.2, 0.55, 0.9), 1):
            car, ang, h = car_at(path, s0 + (s1 - s0) * f, renders)
            r.append((f"{name}, point {k}: {ang:.1f} deg, drawn {h * 22.5:g}", panel(tiles, curved("turnout", renders, mode), [car])))
        rows.append(r)
    l0 = column([row([labelled(i, f"{n}, level 0", SMALL) for n, i in r]) for r in rows])
    l2 = column([row([labelled(shrink(i, 4).resize((i.width // 4 * 3, i.height // 4 * 3), Image.NEAREST), f"{n}, level 2 shown 3x", SMALL) for n, i in r]) for r in rows])
    column([labelled(Image.new("RGBA", (1, 1), BG), "9. The car on the eased turnout's branch at a fifth, a half and nine tenths of the eased stretch; bogies at its middle +- 70% of half its length", bold),
            l0, l2]).convert("RGB").save(out / f"9_car_on_turnout_{v}.png")


main()
