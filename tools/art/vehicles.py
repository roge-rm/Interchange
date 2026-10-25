"""Rail vehicles, from those that ran in Canada: steam engines by wheel arrangement
and the wagons they hauled, wood first and steel after. Liveries are our own.

Each model is built facing along its own a axis, s across it, h up in steps, at
the middle of the tile, then turned to each of eight headings.
"""
import math

from PIL import ImageDraw

from base import (MODEL_RISE, OUTLINE, SS, Frame, c, cell, draw, face, proj, shade)

# ---- colours -------------------------------------------------------------------------

IRON = c("#3c4a56")         # Russia iron boilers of the early engines
BLACK = c("#3d4046")
SMOKEBOX = c("#2c2d30")
BRASS = c("#c9a640")
RED_WHEEL = c("#a2342a")
WHEEL = c("#2a2a2c")
HUB = c("#9a9690")
ROD = c("#c8c6c0")
CAB_GREEN = c("#2f5a3a")
CAB_MAROON = c("#6a2a26")
WOOD_CAB = c("#8a5a36")
TENDER_COAL = c("#1c1c1e")
FRAME = c("#2b2b2c")
VARNISH = c("#8a4a26")
GREEN = c("#2c4c38")         # our railway's passenger green
CREAM = c("#e6dcc0")
GOLD = c("#c9a640")
OXIDE = c("#8a3a2a")         # freight car red
OXIDE_DARK = c("#6e2e22")
ROOF = c("#6f6f6f")
ROOF_DARK = c("#4e4e4e")
WINDOW = c("#2e3a46")
WINDOW_LIT = c("#48586a")
STEEL_GREY = c("#7b7d80")
CABOOSE = c("#9a2e24")

GAUGE = 0.095        # rails, across from the middle
WHEEL_S = 0.108      # where wheels show, just outside the rails


def r_steps(r):
    return r / MODEL_RISE


TRUCK = c("#45474b")
JOURNAL = c("#6a6c70")
UNDER = c("#1c1c1e")


def truck(f, a, r=0.036):
    """A two-axle truck under a car, at a along it: wheels behind side frames flush with the car's sides, a journal box at each axle and a bolster across."""
    out = []
    rh = r_steps(r)
    for side in (-1, 1):
        s_in, s_out = side * 0.111, side * 0.121
        for da in (-0.045, 0.045):
            out += f.wheel(a + da, side * 0.104, r, WHEEL, hub=HUB)
        out += f.box(a - 0.078, a + 0.078, min(s_in, s_out), max(s_in, s_out), rh * 0.7, rh * 2.5, TRUCK)
        for da in (-0.045, 0.045):
            out += f.box(a + da - 0.014, a + da + 0.014, min(s_out, s_out + side * 0.005), max(s_out, s_out + side * 0.005), rh * 0.65, rh * 1.45, JOURNAL)
    out += f.box(a - 0.016, a + 0.016, -0.111, 0.111, rh * 1.9, rh * 2.6, TRUCK, outline=None)
    return out


def underframe(f, a0, a1, h0=0.45, h1=0.6):
    return f.box(a0, a1, -0.08, 0.08, h0, h1, UNDER, outline=None)


# ---- steam ------------------------------------------------------------------------------

def steam(f, L, lead, drive, trail, r_drive, r_lead, boiler, stack, domes, colours, pilot=True, cab_len=0.13, early=False):
    """A steam engine L tiles long: lead, drive and trail axles, boiler radius in tiles."""
    body, cab, wheel_col, trim = colours
    a_back = -L / 2 + 0.01
    a_front = L / 2 - (0.06 if pilot else 0.02)
    out = []
    deck = r_steps(r_drive) * 2 + 0.12
    # Frame and running board.
    out += f.box(a_back, a_front, -0.115, 0.115, deck - 0.1, deck, FRAME)
    # Wheels: leading under the front, drivers in the middle, trailing under the cab.
    span_front = a_front - 0.05
    lead_pos = [span_front - i * r_lead * 2.6 for i in range(lead)]
    d_start = (lead_pos[-1] - r_lead - r_drive - 0.02) if lead else span_front - r_drive
    drive_pos = [d_start - i * r_drive * 2.25 for i in range(drive)]
    trail_pos = [drive_pos[-1] - r_drive - 0.03 - r_lead - i * r_lead * 2.4 for i in range(trail)]
    for s in (-WHEEL_S, WHEEL_S):
        so = s + (0.004 if s > 0 else -0.004)
        for a in lead_pos + trail_pos:
            out += f.wheel(a, so, r_lead, wheel_col, hub=HUB)
        for a in drive_pos:
            out += f.wheel(a, so, r_drive, wheel_col, hub=HUB)
        # The side rod coupling the drivers.
        if len(drive_pos) > 1:
            h = r_steps(r_drive)
            sr = so + (0.004 if s > 0 else -0.004)
            out += f.box(drive_pos[-1], drive_pos[0], min(sr, sr + 0.002), max(sr, sr + 0.002), h - 0.05, h + 0.05, ROD, outline=None)
        # Cylinders ahead of the drivers.
        out += f.cylinder(drive_pos[0] + r_drive + 0.01, drive_pos[0] + r_drive + 0.09, s * 0.95, deck - 0.12, 0.03, body if not early else IRON, n=10)
    # Cab at the back.
    cab_a1 = a_back + cab_len
    out += f.box(a_back, cab_a1, -0.122, 0.122, deck, deck + 1.45, cab)
    out += f.box(a_back - 0.01, cab_a1 + 0.012, -0.135, 0.135, deck + 1.45, deck + 1.56, ROOF_DARK, top=ROOF)
    for s in (-0.1225, 0.1225):
        out += f.plate([(cab_a1 - 0.05, s, deck + 0.75), (cab_a1 - 0.015, s, deck + 0.75), (cab_a1 - 0.015, s, deck + 1.25), (cab_a1 - 0.05, s, deck + 1.25)],
                       WINDOW, (0, 1 if s > 0 else -1, 0))
    # Boiler and smokebox.
    bh = deck + r_steps(boiler) + 0.02
    smoke_len = 0.07
    out += f.cylinder(cab_a1, a_front - smoke_len, 0, bh, boiler, body, n=16, cap=body)
    out += f.cylinder(a_front - smoke_len, a_front, 0, bh, boiler * 1.04, SMOKEBOX, n=16, cap=SMOKEBOX)
    # Bands round the boiler.
    for k in range(1, 3):
        a = cab_a1 + (a_front - smoke_len - cab_a1) * k / 3
        out += f.cylinder(a - 0.004, a + 0.004, 0, bh, boiler * 1.02, trim, n=16, caps=False)
    top = bh + r_steps(boiler)
    # Stack.
    sa = a_front - smoke_len / 2
    if stack == "balloon":
        out += f.upright(sa, 0, top - 0.1, top + 0.55, 0.022, 0.03, BLACK, n=10)
        out += f.upright(sa, 0, top + 0.55, top + 1.15, 0.03, 0.06, BLACK, n=12)
    elif stack == "diamond":
        out += f.upright(sa, 0, top - 0.1, top + 0.6, 0.022, 0.05, BLACK, n=10)
        out += f.upright(sa, 0, top + 0.6, top + 0.95, 0.05, 0.02, BLACK, n=10)
    else:
        out += f.upright(sa, 0, top - 0.1, top + (0.75 if early else 0.45), 0.026, 0.028, BLACK, n=10)
    # Domes along the top.
    for i in range(domes):
        da = cab_a1 + (a_front - smoke_len - cab_a1) * (0.35 + 0.35 * i)
        dc = trim if early else body
        out += f.upright(da, 0, top - 0.15, top + 0.32, 0.034, 0.03, dc, n=12)
        out += f.upright(da, 0, top + 0.32, top + 0.45, 0.03, 0.012, dc, n=12)
    # Headlight.
    out += f.box(a_front - 0.03, a_front + 0.005, -0.025, 0.025, top + 0.05, top + 0.38, BLACK if not early else WOOD_CAB)
    # Pilot.
    if pilot:
        pa = a_front
        tip = a_front + 0.05
        h0, h1 = 0.05, deck - 0.08
        out += [face([f.p(pa, -0.11, h1), f.p(tip, 0, h0 + 0.05), f.p(tip, 0, h0), f.p(pa, -0.11, h0)], RED_WHEEL if early else BLACK, f.n(0.7, -0.7, 0.2)),
                face([f.p(pa, 0.11, h1), f.p(pa, 0.11, h0), f.p(tip, 0, h0), f.p(tip, 0, h0 + 0.05)], RED_WHEEL if early else BLACK, f.n(0.7, 0.7, 0.2)),
                face([f.p(pa, -0.11, h1), f.p(pa, 0.11, h1), f.p(tip, 0, h0 + 0.05)], RED_WHEEL if early else BLACK, f.n(0.4, 0, 1))]
    return out


def tender(f, L, colours, coal=True, axles=4, small=False):
    body = colours[1] if len(colours) > 1 else BLACK
    a0, a1 = -L / 2 + 0.01, L / 2 - 0.01
    out = []
    out += underframe(f, a0, a1)
    if small:
        for s in (-WHEEL_S, WHEEL_S):
            for a in (a0 + 0.04, a1 - 0.04):
                out += f.wheel(a, s + (0.006 if s > 0 else -0.006), 0.034, colours[2], hub=HUB)
    else:
        out += truck(f, a0 + 0.07)
        out += truck(f, a1 - 0.07)
    h1 = 1.35 if small else 1.6
    out += f.box(a0, a1, -0.12, 0.12, 0.6, h1, body, top=TENDER_COAL)
    if coal:
        out += f.box(a0 + 0.03, a1 - 0.08, -0.09, 0.09, h1, h1 + 0.2, TENDER_COAL, outline=None)
    return out


# ---- wagons -------------------------------------------------------------------------------

def windows(f, a0, a1, count, h0, h1, col=WINDOW, s=0.1205):
    out = []
    span = (a1 - a0) / count
    for i in range(count):
        wa0 = a0 + span * i + span * 0.2
        wa1 = a0 + span * (i + 1) - span * 0.2
        for side in (-1, 1):
            out += f.plate([(wa0, side * s, h0), (wa1, side * s, h0), (wa1, side * s, h1), (wa0, side * s, h1)], col, (0, side, 0), None)
    return out


def coach(f, L, steel):
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    out = underframe(f, a0, a1) + truck(f, a0 + 0.08) + truck(f, a1 - 0.08)
    if steel:
        out += f.box(a0, a1, -0.12, 0.12, 0.6, 1.0, GREEN)
        out += f.box(a0, a1, -0.12, 0.12, 1.0, 1.5, CREAM)
        out += f.box(a0, a1, -0.12, 0.12, 1.5, 1.72, GREEN)
        out += f.cylinder(a0, a1, 0, 1.72, 0.12, ROOF, n=12, caps=False)
        out += windows(f, a0 + 0.03, a1 - 0.03, 7, 1.08, 1.42)
        out += f.box(a0, a1, -0.1205, 0.1205, 0.95, 0.98, GOLD, outline=None)
    else:
        out += f.box(a0 + 0.03, a1 - 0.03, -0.12, 0.12, 0.6, 1.7, VARNISH)
        out += f.box(a0, a1, -0.13, 0.13, 1.7, 1.8, ROOF_DARK, top=ROOF)
        out += f.box(a0 + 0.06, a1 - 0.06, -0.06, 0.06, 1.8, 2.0, ROOF_DARK, top=ROOF)
        out += windows(f, a0 + 0.05, a1 - 0.05, 8, 1.05, 1.45)
        # Open platforms at the ends.
        out += f.box(a0, a0 + 0.03, -0.1, 0.1, 0.55, 0.62, FRAME)
        out += f.box(a1 - 0.03, a1, -0.1, 0.1, 0.55, 0.62, FRAME)
    return out


def baggage(f, L, steel):
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    body = GREEN if steel else VARNISH
    out = underframe(f, a0, a1) + truck(f, a0 + 0.08) + truck(f, a1 - 0.08)
    out += f.box(a0, a1, -0.12, 0.12, 0.6, 1.72, body)
    if steel:
        out += f.cylinder(a0, a1, 0, 1.72, 0.12, ROOF, n=12, caps=False)
    else:
        out += f.box(a0, a1, -0.13, 0.13, 1.72, 1.85, ROOF_DARK, top=ROOF)
    for side in (-1, 1):
        for da in (-0.08, 0.08):
            out += f.plate([(da - 0.04, side * 0.1205, 0.7), (da + 0.04, side * 0.1205, 0.7), (da + 0.04, side * 0.1205, 1.55), (da - 0.04, side * 0.1205, 1.55)],
                           shade(body, 0.8), (0, side, 0))
    out += windows(f, a0 + 0.02, a0 + 0.12, 1, 1.15, 1.45)
    out += windows(f, a1 - 0.12, a1 - 0.02, 1, 1.15, 1.45)
    return out


def boxcar(f, L, steel):
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    body = OXIDE
    out = underframe(f, a0, a1) + truck(f, a0 + 0.07) + truck(f, a1 - 0.07)
    out += f.box(a0, a1, -0.12, 0.12, 0.6, 1.8, body, top=shade(body, 0.9))
    # The roof walk and the door.
    out += f.box(a0, a1, -0.025, 0.025, 1.8, 1.86, c("#5a3a2a"), outline=None)
    for side in (-1, 1):
        out += f.plate([(-0.05, side * 0.1205, 0.65), (0.05, side * 0.1205, 0.65), (0.05, side * 0.1205, 1.72), (-0.05, side * 0.1205, 1.72)], OXIDE_DARK, (0, side, 0))
        if steel:
            for k in range(1, 6):
                a = a0 + (a1 - a0) * k / 6
                if abs(a) > 0.06:
                    out += f.plate([(a - 0.003, side * 0.1207, 0.62), (a + 0.003, side * 0.1207, 0.62), (a + 0.003, side * 0.1207, 1.78), (a - 0.003, side * 0.1207, 1.78)],
                                   shade(body, 0.75), (0, side, 0), None)
    return out


def flatcar(f, L):
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    out = truck(f, a0 + 0.07) + truck(f, a1 - 0.07)
    out += f.box(a0, a1, -0.12, 0.12, 0.45, 0.68, c("#5a4632"), top=c("#7a6044"))
    for side in (-1, 1):
        for k in range(5):
            a = a0 + 0.02 + (a1 - a0 - 0.04) * k / 4
            out += f.box(a - 0.008, a + 0.008, side * 0.115 - 0.008, side * 0.115 + 0.008, 0.68, 1.0, c("#4a3a2a"), outline=None)
    return out


def gondola(f, L):
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    out = underframe(f, a0, a1) + truck(f, a0 + 0.07) + truck(f, a1 - 0.07)
    out += f.box(a0, a1, -0.12, 0.12, 0.6, 1.1, c("#3a3634"), top=c("#1e1c1b"))
    for side in (-1, 1):
        for k in range(1, 7):
            a = a0 + (a1 - a0) * k / 7
            out += f.plate([(a - 0.004, side * 0.1205, 0.62), (a + 0.004, side * 0.1205, 0.62), (a + 0.004, side * 0.1205, 1.08), (a - 0.004, side * 0.1205, 1.08)],
                           c("#2a2725"), (0, side, 0), None)
    return out


def hopper(f, L):
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    out = truck(f, a0 + 0.06) + truck(f, a1 - 0.06)
    out += f.box(a0, a1, -0.12, 0.12, 0.85, 1.75, BLACK, top=c("#151515"))
    # Two bays sloping down to their chutes.
    for ca in (-0.06, 0.06):
        out += f.box(ca - 0.045, ca + 0.045, -0.09, 0.09, 0.4, 0.85, shade(BLACK, 0.9))
    return out


def tankcar(f, L):
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    out = underframe(f, a0, a1) + truck(f, a0 + 0.06) + truck(f, a1 - 0.06)
    out += f.cylinder(a0 + 0.01, a1 - 0.01, 0, 1.15, 0.11, BLACK, n=16, cap=shade(BLACK, 1.1))
    out += f.upright(0, 0, 1.6, 1.85, 0.04, 0.04, BLACK, n=12)
    out += f.box(a0, a1, -0.125, 0.125, 0.6, 0.64, FRAME, outline=None)
    return out


def stockcar(f, L):
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    out = underframe(f, a0, a1) + truck(f, a0 + 0.07) + truck(f, a1 - 0.07)
    out += f.box(a0, a1, -0.12, 0.12, 0.6, 1.75, c("#7a5a3a"), top=ROOF)
    for side in (-1, 1):
        for k in range(6):
            h = 0.75 + k * 0.17
            out += f.plate([(a0 + 0.01, side * 0.1205, h), (a1 - 0.01, side * 0.1205, h), (a1 - 0.01, side * 0.1205, h + 0.05), (a0 + 0.01, side * 0.1205, h + 0.05)],
                           c("#2a2016"), (0, side, 0), None)
    return out


def caboose(f, L):
    a0, a1 = -L / 2 + 0.015, L / 2 - 0.015
    out = underframe(f, a0, a1)
    for s in (-WHEEL_S, WHEEL_S):
        for a in (a0 + 0.05, a1 - 0.05):
            out += f.wheel(a, s + (0.006 if s > 0 else -0.006), 0.034, WHEEL, hub=HUB)
    out += f.box(a0 + 0.035, a1 - 0.035, -0.12, 0.12, 0.6, 1.65, CABOOSE)
    out += f.box(a0 + 0.025, a1 - 0.025, -0.13, 0.13, 1.65, 1.75, ROOF_DARK, top=ROOF)
    out += f.box(-0.045, 0.045, -0.11, 0.11, 1.75, 2.25, CABOOSE)
    out += f.box(-0.055, 0.055, -0.12, 0.12, 2.25, 2.33, ROOF_DARK, top=ROOF)
    out += windows(f, a0 + 0.06, a1 - 0.06, 3, 1.05, 1.4)
    out += windows(f, -0.035, 0.035, 1, 1.85, 2.15, s=0.1105)
    out += f.box(a0, a0 + 0.035, -0.1, 0.1, 0.55, 0.62, FRAME)
    out += f.box(a1 - 0.035, a1, -0.1, 0.1, 0.55, 0.62, FRAME)
    return out


# ---- the roster's looks ---------------------------------------------------------------
#
# (key, length in sixteenths of a tile, builder). The game's sim/Engines.kt
# gives each its stats and years; sim/VehicleArt.kt, written from this list,
# says which drawing and length each has.

EARLY = (IRON, CAB_GREEN, RED_WHEEL, BRASS)
VICTORIAN = (IRON, CAB_MAROON, RED_WHEEL, BRASS)
BLACK_ENGINE = (BLACK, BLACK, WHEEL, c("#5a5a5a"))
GREEN_ENGINE = (BLACK, BLACK, WHEEL, GOLD)


def L(n):
    return n / 16


ROSTER = [
    ("dorchester", 7, lambda f: steam(f, L(7), 0, 2, 0, 0.042, 0.03, 0.06, "straight", 1, EARLY, pilot=False, cab_len=0.08, early=True)),
    ("dorchester_tender", 4, lambda f: tender(f, L(4), EARLY, small=True)),
    ("samson", 8, lambda f: steam(f, L(8), 0, 3, 0, 0.036, 0.03, 0.064, "straight", 1, BLACK_ENGINE, pilot=False, cab_len=0.08, early=True)),
    ("samson_tender", 4, lambda f: tender(f, L(4), BLACK_ENGINE, small=True)),
    ("american", 10, lambda f: steam(f, L(10), 2, 2, 0, 0.055, 0.028, 0.075, "balloon", 2, VICTORIAN, early=True)),
    ("american_tender", 7, lambda f: tender(f, L(7), VICTORIAN)),
    ("ten_wheeler", 11, lambda f: steam(f, L(11), 2, 3, 0, 0.052, 0.028, 0.08, "diamond", 2, VICTORIAN, early=True)),
    ("ten_wheeler_tender", 8, lambda f: tender(f, L(8), VICTORIAN)),
    ("consolidation", 11, lambda f: steam(f, L(11), 1, 4, 0, 0.042, 0.028, 0.085, "straight", 2, BLACK_ENGINE)),
    ("consolidation_tender", 8, lambda f: tender(f, L(8), BLACK_ENGINE)),
    ("pacific", 13, lambda f: steam(f, L(13), 2, 3, 1, 0.06, 0.028, 0.092, "straight", 2, GREEN_ENGINE, cab_len=0.15)),
    ("pacific_tender", 9, lambda f: tender(f, L(9), GREEN_ENGINE)),
    ("mikado", 13, lambda f: steam(f, L(13), 1, 4, 1, 0.048, 0.028, 0.095, "straight", 2, BLACK_ENGINE, cab_len=0.15)),
    ("mikado_tender", 9, lambda f: tender(f, L(9), BLACK_ENGINE)),
    ("hudson", 14, lambda f: steam(f, L(14), 2, 3, 2, 0.062, 0.028, 0.098, "straight", 1, GREEN_ENGINE, cab_len=0.16)),
    ("hudson_tender", 10, lambda f: tender(f, L(10), GREEN_ENGINE)),
    ("northern", 15, lambda f: steam(f, L(15), 2, 4, 2, 0.058, 0.028, 0.1, "straight", 1, GREEN_ENGINE, cab_len=0.16)),
    ("northern_tender", 10, lambda f: tender(f, L(10), GREEN_ENGINE)),
    ("selkirk", 15, lambda f: steam(f, L(15), 1, 5, 2, 0.05, 0.028, 0.104, "straight", 1, BLACK_ENGINE, cab_len=0.16)),
    ("selkirk_tender", 10, lambda f: tender(f, L(10), BLACK_ENGINE)),
    ("coach_wood", 11, lambda f: coach(f, L(11), False)),
    ("coach_steel", 13, lambda f: coach(f, L(13), True)),
    ("baggage_wood", 11, lambda f: baggage(f, L(11), False)),
    ("baggage_steel", 13, lambda f: baggage(f, L(13), True)),
    ("boxcar_wood", 9, lambda f: boxcar(f, L(9), False)),
    ("boxcar_steel", 10, lambda f: boxcar(f, L(10), True)),
    ("flatcar", 9, lambda f: flatcar(f, L(9))),
    ("gondola", 9, lambda f: gondola(f, L(9))),
    ("hopper", 8, lambda f: hopper(f, L(8))),
    ("tank_car", 8, lambda f: tankcar(f, L(8))),
    ("stock_car", 9, lambda f: stockcar(f, L(9))),
    ("caboose", 7, lambda f: caboose(f, L(7))),
    # Drawn plain as their nearest kin until the Blender renders are there.
    ("reefer", 9, lambda f: boxcar(f, L(9), False)),
    ("ore_car", 6, lambda f: hopper(f, L(6))),
    ("covered_hopper", 11, lambda f: hopper(f, L(11))),
    ("wedge_plow", 10, lambda f: caboose(f, L(10))),
    ("boxcab", 12, lambda f: boxcar(f, L(12), True)),
    ("fp7", 13, lambda f: boxcar(f, L(13), True)),
    ("rs3", 12, lambda f: boxcar(f, L(12), True)),
    ("gp9", 12, lambda f: boxcar(f, L(12), True)),
    ("fpa4", 13, lambda f: boxcar(f, L(13), True)),
    ("sd40", 15, lambda f: boxcar(f, L(15), True)),
    ("lrc", 13, lambda f: boxcar(f, L(13), True)),
    ("gf6c", 15, lambda f: boxcar(f, L(15), True)),
    ("f40", 13, lambda f: boxcar(f, L(13), True)),
    ("dash9", 16, lambda f: boxcar(f, L(16), True)),
    ("sd70", 16, lambda f: boxcar(f, L(16), True)),
    ("charger", 14, lambda f: boxcar(f, L(14), True)),
    ("coach_streamline", 14, lambda f: coach(f, L(14), True)),
    ("well_car", 14, lambda f: flatcar(f, L(14))),
    # Road vehicles, drawn plain until their Blender models are there.
    ("horse_omnibus", 7, lambda f: coach(f, L(7), False)),
    ("stagecoach", 7, lambda f: coach(f, L(7), False)),
    ("mail_coach", 6, lambda f: coach(f, L(6), False)),
    ("freight_wagon", 7, lambda f: boxcar(f, L(7), False)),
    ("red_river_cart", 4, lambda f: flatcar(f, L(4))),
    ("horsecar", 7, lambda f: coach(f, L(7), False)),
    ("motor_bus", 6, lambda f: coach(f, L(6), True)),
    ("brill_bus", 7, lambda f: coach(f, L(7), True)),
    ("new_look", 8, lambda f: coach(f, L(8), True)),
    ("low_floor", 8, lambda f: coach(f, L(8), True)),
    ("electric_bus", 8, lambda f: coach(f, L(8), True)),
    ("highway_coach", 9, lambda f: coach(f, L(9), True)),
    ("mail_truck", 5, lambda f: boxcar(f, L(5), True)),
    ("stake_truck", 6, lambda f: flatcar(f, L(6))),
    ("dump_truck", 6, lambda f: hopper(f, L(6))),
    ("tank_truck", 6, lambda f: tankcar(f, L(6))),
    ("log_truck", 9, lambda f: flatcar(f, L(9))),
    ("cattle_truck", 8, lambda f: stockcar(f, L(8))),
    ("semi_van", 11, lambda f: boxcar(f, L(11), True)),
    ("plow_truck", 6, lambda f: caboose(f, L(6))),
    ("step_van", 6, lambda f: boxcar(f, L(6), True)),
    ("electric_van", 6, lambda f: boxcar(f, L(6), True)),
    ("flatbed_truck", 7, lambda f: flatcar(f, L(7))),
    ("tandem_dump", 7, lambda f: hopper(f, L(7))),
    ("quad_dump", 8, lambda f: hopper(f, L(8))),
    ("fuel_truck", 7, lambda f: tankcar(f, L(7))),
    ("tandem_plow", 7, lambda f: caboose(f, L(7))),
    ("highway_tractor", 12, lambda f: boxcar(f, L(12), True)),
    ("aero_tractor", 12, lambda f: boxcar(f, L(12), True)),
    ("tanker_train", 13, lambda f: tankcar(f, L(13))),
    ("grain_train", 13, lambda f: hopper(f, L(13))),
    ("cattle_liner", 12, lambda f: stockcar(f, L(12))),
    ("logger", 12, lambda f: flatcar(f, L(12))),
    ("streetcar", 6, lambda f: coach(f, L(6), False)),
    ("pcc", 10, lambda f: coach(f, L(10), True)),
    ("clrv", 11, lambda f: coach(f, L(11), True)),
    ("flexity", 16, lambda f: coach(f, L(16), True)),
    ("rotary_plow", 12, lambda f: caboose(f, L(12))),
    # Ships and aircraft, drawn plain until their Blender models are there.
    ("schooner", 6, lambda f: coach(f, L(6), True)),
    ("paddle_steamer", 7, lambda f: coach(f, L(7), True)),
    ("canaller", 8, lambda f: coach(f, L(8), True)),
    ("lake_steamer", 10, lambda f: coach(f, L(10), True)),
    ("steam_laker", 12, lambda f: coach(f, L(12), True)),
    ("icebreaker_steam", 9, lambda f: coach(f, L(9), True)),
    ("tug_barge", 9, lambda f: coach(f, L(9), True)),
    ("package_freighter", 10, lambda f: coach(f, L(10), True)),
    ("lake_tanker", 10, lambda f: coach(f, L(10), True)),
    ("car_ferry", 9, lambda f: coach(f, L(9), True)),
    ("seaway_laker", 16, lambda f: coach(f, L(16), True)),
    ("icebreaker_diesel", 12, lambda f: coach(f, L(12), True)),
    ("self_unloader", 15, lambda f: coach(f, L(15), True)),
    ("superferry", 13, lambda f: coach(f, L(13), True)),
    ("hovercraft", 5, lambda f: coach(f, L(5), True)),
    ("container_feeder", 14, lambda f: coach(f, L(14), True)),
    ("product_tanker", 14, lambda f: coach(f, L(14), True)),
    ("battery_ferry", 9, lambda f: coach(f, L(9), True)),
    ("fairchild_71", 3, lambda f: coach(f, L(3), True)),
    ("norseman", 3, lambda f: coach(f, L(3), True)),
    ("beaver", 3, lambda f: coach(f, L(3), True)),
    ("otter", 4, lambda f: coach(f, L(4), True)),
    ("twin_otter", 5, lambda f: coach(f, L(5), True)),
    ("electra_10", 5, lambda f: coach(f, L(5), True)),
    ("dc3", 6, lambda f: coach(f, L(6), True)),
    ("north_star", 8, lambda f: coach(f, L(8), True)),
    ("viscount", 7, lambda f: coach(f, L(7), True)),
    ("dc8", 12, lambda f: coach(f, L(12), True)),
    ("dc9", 9, lambda f: coach(f, L(9), True)),
    ("b737_200", 9, lambda f: coach(f, L(9), True)),
    ("dash7", 7, lambda f: coach(f, L(7), True)),
    ("dash8", 7, lambda f: coach(f, L(7), True)),
    ("b767", 12, lambda f: coach(f, L(12), True)),
    ("a320", 10, lambda f: coach(f, L(10), True)),
    ("crj", 8, lambda f: coach(f, L(8), True)),
    ("a220", 9, lambda f: coach(f, L(9), True)),
    ("bristol_freighter", 6, lambda f: coach(f, L(6), True)),
    ("hercules", 9, lambda f: coach(f, L(9), True)),
    ("b747f", 15, lambda f: coach(f, L(15), True)),
    ("bell_47", 3, lambda f: coach(f, L(3), True)),
    ("s55", 4, lambda f: coach(f, L(4), True)),
    ("jetranger", 3, lambda f: coach(f, L(3), True)),
    ("s92", 5, lambda f: coach(f, L(5), True)),
    ("courier_coach", 7, lambda f: coach(f, L(7), True)),
    ("mc5_coach", 7, lambda f: coach(f, L(7), True)),
    ("d4500_coach", 9, lambda f: coach(f, L(9), True)),
    ("sander_plow", 6, lambda f: caboose(f, L(6))),
    ("tandem_semi", 11, lambda f: boxcar(f, L(11), True)),
    ("boom_truck", 7, lambda f: flatcar(f, L(7))),
    ("tanker_long", 13, lambda f: tankcar(f, L(13))),
    ("coach_early", 6, lambda f: coach(f, L(6), False)),
    ("bulkhead_flat", 12, lambda f: flatcar(f, L(12))),
    ("tank_domeless", 12, lambda f: tankcar(f, L(12))),
    ("boxcar_50", 12, lambda f: boxcar(f, L(12), True)),
    ("reefer_mech", 12, lambda f: boxcar(f, L(12), True)),
]


def vehicle(builder, heading):
    img = cell()
    draw(img, builder(Frame(heading * math.pi / 4)))
    return img
