"""Renders rail vehicles in eight headings, one PNG each, for tools/gen_tiles.py to pack.

    blender -b --python tools/blender/render_vehicles.py -- OUT_DIR [key ...]

Each file is OUT_DIR/<key>_<heading>.png, a whole cell at twice the sharpest
level, the vehicle's middle on the tile's middle. On slopes, the four straight
headings come tilted up and down too: OUT_DIR/<key>_s<heading>_up.png and _down.png.
A file already there is left alone, so a run can carry on where one stopped.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import math  # noqa: E402

import bpy  # noqa: E402

import cars  # noqa: E402
import parts  # noqa: E402
import rig  # noqa: E402
import steam  # noqa: E402
import diesel  # noqa: E402
import roadcars  # noqa: E402


def L(n):
    return n / 16


# (key, length in sixteenths of a tile, builder); the same keys and lengths as tools/art/vehicles.py.
MODELS = {
    "dorchester": (7, lambda m: steam.engine(m, L(7), 0, 2, 0, 0.042, 0.03, 0.052, "straight", 1, "victorian", pilot=False, cab_len=0.09)),
    "dorchester_tender": (4, lambda m: steam.tender(m, L(4), "victorian", small=True)),
    "american": (10, lambda m: steam.engine(m, L(10), 2, 2, 0, 0.055, 0.028, 0.063, "balloon", 2, "victorian")),
    "american_tender": (7, lambda m: steam.tender(m, L(7), "victorian")),
    "ten_wheeler": (11, lambda m: steam.engine(m, L(11), 2, 3, 0, 0.052, 0.028, 0.066, "diamond", 2, "victorian")),
    "ten_wheeler_tender": (8, lambda m: steam.tender(m, L(8), "victorian")),
    "consolidation": (11, lambda m: steam.engine(m, L(11), 1, 4, 0, 0.042, 0.028, 0.07, "straight", 2, "black")),
    "consolidation_tender": (8, lambda m: steam.tender(m, L(8), "black")),
    "pacific": (13, lambda m: steam.engine(m, L(13), 2, 3, 1, 0.06, 0.028, 0.074, "straight", 2, "black", cab_len=0.15)),
    "pacific_tender": (9, lambda m: steam.tender(m, L(9), "black")),
    "mikado": (13, lambda m: steam.engine(m, L(13), 1, 4, 1, 0.048, 0.028, 0.076, "straight", 2, "black", cab_len=0.15)),
    "mikado_tender": (9, lambda m: steam.tender(m, L(9), "black")),
    "hudson": (14, lambda m: steam.engine(m, L(14), 2, 3, 2, 0.062, 0.028, 0.078, "straight", 1, "black", cab_len=0.16)),
    "hudson_tender": (10, lambda m: steam.tender(m, L(10), "black")),
    "northern": (15, lambda m: steam.engine(m, L(15), 2, 4, 2, 0.058, 0.028, 0.08, "straight", 1, "black", cab_len=0.16)),
    "northern_tender": (10, lambda m: steam.tender(m, L(10), "black")),
    "selkirk": (15, lambda m: steam.engine(m, L(15), 1, 5, 2, 0.05, 0.028, 0.082, "straight", 1, "black", cab_len=0.16)),
    "selkirk_tender": (10, lambda m: steam.tender(m, L(10), "black")),
    "coach_wood": (11, lambda m: cars.coach_wood(m, L(11))),
    "coach_steel": (13, lambda m: cars.coach_steel(m, L(13))),
    "baggage_wood": (11, lambda m: cars.baggage(m, L(11), False)),
    "baggage_steel": (13, lambda m: cars.baggage(m, L(13), True)),
    "boxcar_wood": (9, lambda m: cars.boxcar(m, L(9), False)),
    "boxcar_steel": (10, lambda m: cars.boxcar(m, L(10), True)),
    "flatcar": (9, lambda m: cars.flatcar(m, L(9))),
    "gondola": (9, lambda m: cars.gondola(m, L(9))),
    "hopper": (8, lambda m: cars.hopper(m, L(8))),
    "tank_car": (8, lambda m: cars.tank_car(m, L(8))),
    "stock_car": (9, lambda m: cars.stock_car(m, L(9))),
    "caboose": (7, lambda m: cars.caboose(m, L(7))),
    "reefer": (9, lambda m: cars.reefer(m, L(9))),
    "ore_car": (6, lambda m: cars.ore_car(m, L(6))),
    "covered_hopper": (11, lambda m: cars.covered_hopper(m, L(11))),
    "wedge_plow": (10, lambda m: cars.wedge_plow(m, L(10))),
    "boxcab": (12, lambda m: diesel.boxcab(m, L(12))),
    "fp7": (13, lambda m: diesel.cab_unit(m, L(13), "green")),
    "rs3": (12, lambda m: diesel.hood(m, L(12), "maroon")),
    "gp9": (12, lambda m: diesel.hood(m, L(12), "black", dynamic=True)),
    "fpa4": (13, lambda m: diesel.cab_unit(m, L(13), "maroon")),
    "sd40": (15, lambda m: diesel.hood(m, L(15), "red", axles=3, dynamic=True)),
    "lrc": (13, lambda m: diesel.lrc(m, L(13))),
    "gf6c": (15, lambda m: diesel.gf6c(m, L(15))),
    "f40": (13, lambda m: diesel.hood(m, L(13), "silver", wide=True)),
    "dash9": (16, lambda m: diesel.hood(m, L(16), "black", axles=3, wide=True, dynamic=True)),
    "sd70": (16, lambda m: diesel.hood(m, L(16), "red", axles=3, wide=True, dynamic=True)),
    "charger": (14, lambda m: diesel.charger(m, L(14))),
    "coach_streamline": (14, lambda m: cars.coach_streamline(m, L(14))),
    "well_car": (14, lambda m: cars.well_car(m, L(14))),
    "motor_bus": (6, lambda m: roadcars.bus(m, L(6), "motor")),
    "brill_bus": (7, lambda m: roadcars.bus(m, L(7), "brill")),
    "new_look": (8, lambda m: roadcars.bus(m, L(8), "newlook")),
    "low_floor": (8, lambda m: roadcars.bus(m, L(8), "lowfloor")),
    "electric_bus": (8, lambda m: roadcars.bus(m, L(8), "electric")),
    "highway_coach": (9, lambda m: roadcars.highway_coach(m, L(9))),
    "mail_truck": (5, lambda m: roadcars.truck(m, L(5), "mail")),
    "stake_truck": (6, lambda m: roadcars.truck(m, L(6), "stake")),
    "dump_truck": (6, lambda m: roadcars.truck(m, L(6), "dump")),
    "tank_truck": (6, lambda m: roadcars.truck(m, L(6), "tank")),
    "log_truck": (9, lambda m: roadcars.truck(m, L(9), "log")),
    "cattle_truck": (8, lambda m: roadcars.truck(m, L(8), "cattle")),
    "semi_van": (11, lambda m: roadcars.truck(m, L(11), "semi", "cabover")),
    "plow_truck": (6, lambda m: roadcars.truck(m, L(6), "plow")),
    "step_van": (6, lambda m: roadcars.van(m, L(6), "step")),
    "electric_van": (6, lambda m: roadcars.van(m, L(6), "electric")),
    "flatbed_truck": (7, lambda m: roadcars.truck(m, L(7), "flatbed", "square", 2)),
    "tandem_dump": (7, lambda m: roadcars.truck(m, L(7), "dump", "square", 2)),
    "quad_dump": (8, lambda m: roadcars.truck(m, L(8), "dump", "long", 3)),
    "fuel_truck": (7, lambda m: roadcars.truck(m, L(7), "tank", "square", 2)),
    "tandem_plow": (7, lambda m: roadcars.truck(m, L(7), "plow", "square", 2)),
    "highway_tractor": (12, lambda m: roadcars.tractor(m, L(12), "van", "sleeper", "#2a4a7a")),
    "aero_tractor": (12, lambda m: roadcars.tractor(m, L(12), "van", "aero", "#c83a2a")),
    "tanker_train": (13, lambda m: roadcars.tractor(m, L(13), "tank", "aero", "#2a2a2c")),
    "grain_train": (13, lambda m: roadcars.tractor(m, L(13), "hopper", "sleeper", "#d8a020", bumper=True)),
    "cattle_liner": (12, lambda m: roadcars.tractor(m, L(12), "cattle", "long", "#8a2a22", bumper=True)),
    "logger": (12, lambda m: roadcars.tractor(m, L(12), "logs", "long", "#3a5a2a", bumper=True)),
    "streetcar": (8, lambda m: roadcars.streetcar(m, L(8), "wood")),
    "pcc": (10, lambda m: roadcars.streetcar(m, L(10), "pcc")),
    "clrv": (11, lambda m: roadcars.streetcar(m, L(11), "clrv")),
    "flexity": (16, lambda m: roadcars.streetcar(m, L(16), "flexity")),
    "rotary_plow": (12, lambda m: cars.rotary_plow(m, L(12))),
}


# Road vehicles stand alone; everything else runs coupled in trains.
ROAD = {"motor_bus", "brill_bus", "new_look", "low_floor", "electric_bus", "highway_coach", "mail_truck", "stake_truck", "dump_truck",
        "tank_truck", "log_truck", "cattle_truck", "semi_van", "plow_truck", "streetcar", "pcc", "clrv", "flexity",
        "step_van", "electric_van", "flatbed_truck", "tandem_dump", "quad_dump", "fuel_truck", "tandem_plow", "highway_tractor",
        "aero_tractor", "tanker_train", "grain_train", "cattle_liner", "logger"}
# How far in from each end of its length a rail vehicle's body stops, in tiles, leaving a gap to the next car that its coupler bridges.
END_GAP = 0.03


def couple(model, length):
    """Draws the model a little shorter than its length, so cars have a gap between them, and puts a coupler at each end
    reaching out to the next car's."""
    s = (length - 2 * END_GAP) / length
    for o in model.objects:
        if o.type == "MESH":
            o.scale.y *= s
        else:
            o.location.y *= s
    iron = parts.material("coupler", "#2a2a2c", metal=0.5, rough=0.5)
    for end in (-1, 1):
        inner = end * (length / 2 - END_GAP - 0.03)
        tip = end * (length / 2)
        knuckle = end * (length / 2 - 0.016)
        model.box(-0.011, 0.011, min(inner, knuckle), max(inner, knuckle), 0.48, 0.56, iron)
        model.box(-0.024, 0.024, min(knuckle, tip), max(knuckle, tip), 0.44, 0.6, iron, bevel=0.002)


def shadow_catcher():
    # Big enough that its edges are well out of the cell.
    bpy.ops.mesh.primitive_plane_add(size=40, location=rig.game(0.5, 0.5, 0))
    plane = bpy.context.object
    plane.is_shadow_catcher = True
    return plane


def outlines():
    scene = bpy.context.scene
    scene.render.use_freestyle = True
    scene.render.line_thickness_mode = "ABSOLUTE"
    scene.render.line_thickness = 1.6
    ls = scene.view_layers[0].freestyle_settings.linesets[0]
    ls.select_by_visibility = True
    ls.select_by_edge_types = True
    ls.select_silhouette = True
    ls.select_border = True
    ls.select_crease = False
    if ls.linestyle is None:
        ls.linestyle = bpy.data.linestyles.new("outline")
    ls.linestyle.color = (0.1, 0.09, 0.08)
    ls.linestyle.alpha = 0.9


def main():
    args = sys.argv[sys.argv.index("--") + 1:]
    out = Path(args[0])
    out.mkdir(parents=True, exist_ok=True)
    keys = args[1:] or list(MODELS)
    for key in keys:
        length, build = MODELS[key]
        rig.clear()
        # The reset takes the materials with it.
        parts._materials.clear()
        rig.setup(samples=40)
        shadow_catcher()
        model = parts.Model(key)
        build(model)
        if key not in ROAD:
            couple(model, L(length))
        pivot = model.place(0)
        # A step up over a tile, the steepest track climbs.
        tilt = math.atan2(rig.RISE, 1.0)
        shots = [(f"{key}_{h}.png", 0.0, h) for h in range(8)]
        shots += [(f"{key}_s{h}_{way}.png", tilt if way == "up" else -tilt, h) for h in (0, 2, 4, 6) for way in ("up", "down")]
        for name, pitch, h in shots:
            if (out / name).exists():
                continue
            pivot.rotation_euler = (pitch, 0, -h * math.pi / 4)
            rig.render(str(out / name))


main()
