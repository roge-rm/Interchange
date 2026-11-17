"""Drones and their pad's charging shed.

Drones are built as aircraft.py's are, facing +y, x across, z in steps from the ground, but in tiles and steps
directly and drawn bigger than their pack length so they still show zoomed out: a bright body, rotor discs ringed by
guards in the body colour, short legs. The mail drone is a quadcopter, the freight drone an octocopter with a cargo pod
slung under it, and the tilt-rotor a small winged drone with a rotor at each wing tip turned up, as it takes off and lands.

The pad's shed stands in the tile's back corner, behind the landing square the ground is painted with.
"""
import math

import aircraft
import harbour
from parts import material
from roadcars import AUTO_BAND, AUTO_WHITE


def _mats(body_hex):
    disc = material("drone disc", "#c4c8cc", rough=0.6)
    disc.node_tree.nodes["Principled BSDF"].inputs["Alpha"].default_value = 0.5
    return {
        "body": material(f"drone body {body_hex}", body_hex, rough=0.3, clearcoat=0.5),
        "band": material("drone band", AUTO_BAND, rough=0.25, clearcoat=0.3),
        "dark": material("drone dark", "#26282a", rough=0.55),
        "metal": material("drone metal", "#9a9ea2", metal=0.7, rough=0.35),
        "disc": disc,
    }


def _rotor(m, x, y, z, r, c, guard=True):
    """A motor at (x, y) under a spinning disc r tiles round at z steps, with a guard ring round it in the body colour."""
    m.upright(x, y, z - 0.09, z - 0.005, r * 0.22, r * 0.2, c["dark"], segments=12)
    m.upright(x, y, z, z + 0.012, r, r, c["disc"], segments=28)
    if guard:
        n = 18
        rr = r * 1.08
        for k in range(n):
            a0, a1 = 2 * math.pi * k / n, 2 * math.pi * (k + 1) / n
            m.rod((x + rr * math.cos(a0), y + rr * math.sin(a0), z + 0.006), (x + rr * math.cos(a1), y + rr * math.sin(a1), z + 0.006),
                  0.0022, c["body"], segments=6)


def _legs(m, xs, ys, z, c):
    """Landing legs from the body's belly at z steps down to a skid each side."""
    for x in xs:
        m.tube(ys[0] - 0.006, ys[1] + 0.006, x, 0.012, 0.0022, c["dark"], segments=8)
        for y in ys:
            m.rod((x * 0.7, y, z), (x, y, 0.012), 0.002, c["dark"], segments=6)


def multirotor(m, L, kind):
    """A quadcopter ("quad") or an octocopter with a cargo pod ("octo"): a rounded body on arms out to its rotors."""
    if kind == "quad":
        c = _mats("#f26a1b")
        arm, r, n, lo, hi, hw, half_len = 0.036, 0.021, 4, 0.18, 0.36, 0.017, 0.026
    else:
        c = _mats("#f2c418")
        arm, r, n, lo, hi, hw, half_len = 0.05, 0.018, 8, 0.3, 0.48, 0.022, 0.03
    zr = hi + 0.06
    # The body: a rounded shell, a teal band round its middle and a dark sensor dome under the nose.
    aircraft.oval_loft(m, [(-half_len, hw * 0.3, lo + 0.05, hi - 0.05, 3), (-half_len + 0.008, hw, lo, hi, 3), (half_len - 0.01, hw, lo, hi, 3),
                           (half_len, hw * 0.35, lo + 0.05, hi - 0.06, 3)], {"b": c["body"]})
    mid = (lo + hi) / 2
    aircraft.oval_loft(m, [(-half_len + 0.004, hw * 0.75 + 0.002, mid - 0.025, mid + 0.025, 3),
                           (half_len - 0.004, hw * 0.75 + 0.002, mid - 0.025, mid + 0.025, 3)], {"b": c["band"]})
    m.lathe(0.0, half_len - 0.006, lo - 0.035, [(0.0, 0.0), (0.006, 0.01), (0.008, 0.035), (0.0, 0.04)], c["dark"], segments=12)
    for k in range(n):
        a = math.pi / n + 2 * math.pi * k / n
        x, y = arm * math.cos(a), arm * math.sin(a)
        m.rod((0.0, 0.0, hi - 0.04), (x, y, hi - 0.02), 0.0034, c["dark"], segments=8)
        _rotor(m, x, y, zr, r, c)
    if kind == "octo":
        # The cargo pod under the body: a grey box with the teal band and rounded ends.
        pod = material("drone cargo pod", "#8a8e92", rough=0.45)
        aircraft.oval_loft(m, [(-0.024, 0.012, 0.13, 0.26, 4), (-0.02, 0.018, 0.12, 0.27, 4), (0.02, 0.018, 0.12, 0.27, 4),
                               (0.024, 0.012, 0.13, 0.26, 4)], {"b": pod})
        m.box(-0.0186, 0.0186, -0.02, 0.02, 0.18, 0.21, c["band"])
        m.box(-0.004, 0.004, -0.004, 0.004, 0.26, lo + 0.01, c["dark"])
        _legs(m, (-0.026, 0.026), (-0.02, 0.02), lo + 0.01, c)
    else:
        _legs(m, (-0.016, 0.016), (-0.014, 0.014), lo + 0.01, c)


def tiltrotor(m, L):
    """A small tilt-rotor drone: a white body with a dark sensor nose, a high teal wing with a nacelle and big rotor
    disc at each tip, a V tail, and three short wheels."""
    c = _mats(AUTO_WHITE)
    wing_c = c["band"]
    n = L / 2
    aircraft.oval_loft(m, [(n, 0.003, 0.22, 0.25), (n - 0.008, 0.009, 0.15, 0.3), (n - 0.022, 0.014, 0.12, 0.34), (-n * 0.35, 0.014, 0.12, 0.34),
                           (-n + 0.01, 0.006, 0.24, 0.33), (-n, 0.002, 0.28, 0.31)], {"b": c["body"]})
    m.lathe(0.0, n - 0.012, 0.11, [(0.0, 0.0), (0.006, 0.01), (0.008, 0.03), (0.0, 0.035)], c["dark"], segments=12)
    # A dark sensor window round the nose.
    aircraft.oval_loft(m, [(n - 0.004, 0.0065, 0.2, 0.27), (n - 0.016, 0.0125, 0.21, 0.33), (n - 0.022, 0.0128, 0.23, 0.34)], {"b": c["dark"]})
    span = 0.075
    aircraft.wing(m, 0.004, 0.34, span, 0.024, 0.02, 0.002, wing_c, thick=0.035)
    for s in (-1, 1):
        x = s * span
        # The nacelle stands up at the tip, its rotor turned up for take-off and landing.
        m.upright(x, 0.004, 0.27, 0.46, 0.0075, 0.0065, c["body"], segments=14)
        m.lathe(x, 0.004, 0.46, [(0.0065, 0.0), (0.004, 0.03), (0.0, 0.04)], c["dark"], segments=12)
        _rotor(m, x, 0.004, 0.5, 0.034, c, guard=False)
        # The V tail, each fin leaning out.
        m.wedge([(0.0, -n + 0.03, 0.3), (0.0, -n + 0.006, 0.3), (s * 0.026, -n - 0.004, 0.5), (s * 0.026, -n + 0.012, 0.5)],
                [(0.0, -n + 0.03, 0.32), (0.0, -n + 0.006, 0.32), (s * 0.026, -n - 0.004, 0.52), (s * 0.026, -n + 0.012, 0.52)], wing_c)
    for x, y in ((0.0, n - 0.03), (-0.016, -0.012), (0.016, -0.012)):
        m.tube_x(x - 0.0025, x + 0.0025, y, 0.035, 0.007, c["dark"], segments=12)
        m.rod((x, y, 0.035), (x * 0.6, y, 0.14), 0.0018, c["metal"], segments=6)


def drone_builders():
    """The drones by key, as render_vehicles registers aircraft, with their lengths in sixteenths."""
    return {
        "mail_drone": (1, lambda m, L: multirotor(m, L, "quad")),
        "freight_drone": (1, lambda m, L: multirotor(m, L, "octo")),
        "tiltrotor_drone": (2, lambda m, L: tiltrotor(m, L)),
    }


def pad_shed(m):
    """A drone pad's charging shed in the tile's back corner: a small white prefab with a teal band, a roll-up door
    facing the pad, a charger cabinet with a lit panel, solar panels on its roof and a windsock, as wind grounds drones."""
    body = material("pad shed", AUTO_WHITE, rough=0.4)
    band = material("pad band", AUTO_BAND, rough=0.3, clearcoat=0.3)
    dark = material("pad door", "#4a4e52", metal=0.3, rough=0.5)
    roof = material("pad roof", "#7a7e82", rough=0.6)
    solar = material("solar panels", "#1a2a44", metal=0.4, rough=0.15, clearcoat=0.6)
    x0, x1, y0, y1 = -0.44, -0.24, -0.44, -0.25
    top = 0.95
    m.box(x0, x1, y0, y1, 0.0, top, body, bevel=0.01)
    m.box(x0 - 0.008, x1 + 0.008, y0 - 0.008, y1 + 0.008, top, top + 0.06, roof, bevel=0.004)
    for x, face in ((x1, 1),):
        m.box(x, x + face * 0.002, y0 + 0.005, y1 - 0.005, 0.62, 0.72, band)
    m.box(x0 + 0.005, x1 - 0.005, y1, y1 + 0.002, 0.62, 0.72, band)
    # The roll-up door on the pad side, and its slats.
    m.box(x0 + 0.04, x1 - 0.04, y1, y1 + 0.004, 0.0, 0.56, dark)
    for k in range(1, 6):
        z = 0.56 * k / 6
        m.box(x0 + 0.04, x1 - 0.04, y1 + 0.004, y1 + 0.006, z - 0.006, z + 0.006, roof)
    # The charger cabinet on the other face, with its lit panel and a cable reel.
    m.box(x1, x1 + 0.03, y0 + 0.05, y0 + 0.11, 0.0, 0.62, body, bevel=0.004)
    m.box(x1 + 0.03, x1 + 0.032, y0 + 0.065, y0 + 0.095, 0.38, 0.5, band)
    m.tube_x(x1 + 0.008, x1 + 0.03, y0 + 0.15, 0.24, 0.02, dark, segments=14)
    # Two rows of solar panels tilted on the roof.
    for ya, yb in ((y0 + 0.01, (y0 + y1) / 2 - 0.005), ((y0 + y1) / 2 + 0.005, y1 - 0.01)):
        m.wedge([(x0 + 0.01, ya, top + 0.08), (x1 - 0.01, ya, top + 0.08), (x1 - 0.01, yb, top + 0.08), (x0 + 0.01, yb, top + 0.08)],
                [(x0 + 0.01, ya, top + 0.1), (x1 - 0.01, ya, top + 0.22), (x1 - 0.01, yb, top + 0.22), (x0 + 0.01, yb, top + 0.1)], solar)
    harbour.windsock(m, x0 + 0.02, y1 + 0.06, 0.0, 1.7, toward=(0.6, -0.8))
