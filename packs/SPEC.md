# Interchange content packs

A content pack adds things to Interchange: for now trains and the wagons they haul, later road vehicles, ships, aircraft, buildings and industries. Anyone can make one. The game's own vehicles are a pack too, the base pack, in this same format.

This is format 1. Start from the template pack in `packs/template`, which has one of each thing a pack can do.

## What's in a pack

A pack is a zip file named `something.ichpack` holding:

- `manifest.json`, what the pack is
- `vehicles.json`, the vehicles it adds or changes
- `sprites/NAME.png`, a sprite sheet for each vehicle drawing

The files can sit at the top of the zip or in one folder inside it. Anything else in the zip is left alone.

Text is UTF-8 JSON. Every name, description and type comes in English and can come in French as well: `{ "en": "Ore car", "fr": "Wagon à minerai" }`. English is needed, French is shown to French players when it's there.

## manifest.json

```json
{
  "format": 1,
  "id": "prairie-lines",
  "version": 3,
  "name": { "en": "Prairie lines", "fr": "Lignes des Prairies" },
  "description": { "en": "Engines and cars of the prairie branch lines." },
  "author": "Your name",
  "licence": "CC-BY-SA-4.0",
  "needs": ["base"],
  "disables": ["base:stock_car"]
}
```

| Field | Needed | What it is |
|---|---|---|
| format | yes | The pack format, 1 for now. A game won't load a pack made for a newer format. |
| id | yes | Lower case letters, digits, `-` and `_`, up to 32. Keep it the same for every version. `base` is the game's own. |
| version | yes | A whole number. Raise it each time you share a new version. |
| name | yes | The pack's name in each language. |
| description | no | A line about it. |
| author, licence | no | Who made it and how others may use it. |
| needs | no | Ids of packs that have to be on before this one, if it builds on their vehicles. The base pack is always on first. |
| disables | no | Vehicles of other packs to take off sale, as `pack:id`. |

## vehicles.json

```json
{ "vehicles": [ { ... }, { ... } ] }
```

Each vehicle:

| Field | Needed | What it is |
|---|---|---|
| id | yes | Lower case letters, digits, `-` and `_`. Its full name is `pack:id`. |
| kind | yes | `engine`, `wagon` or `tender`. A tender only comes with its engine. |
| name | yes | Its name in each language. |
| plural | no | What more than one is called, for wagons. |
| type | no | What railwaymen called it: a wheel arrangement like `4-6-2`, or what a car is for. |
| from, until | yes | The years it's on sale, from the first up to but not including the second. |
| speed | engines | Top speed in km/h. |
| power | engines | Horsepower. |
| weight | yes | Tonnes. |
| cost | yes | Dollars to buy. |
| running | yes | Dollars a year to run. |
| length | yes | Sixteenths of a tile, 1 to 16. Draw it to match. |
| carries, capacity | no | The cargo a wagon carries and how much. Cargo ids for now: `passengers`, `mail`, `goods`, `timber`, `steel`, `coal`, `oil`, `livestock`, `iron_ore`. |
| tender | no | An engine's tender: a tender's id in this pack, or `pack:id` in another. |
| sprite | no | Its sprite sheet, `sprites/NAME.png`. Leave it out to use the vehicle's id. Vehicles can share one. |

### Changing another pack's vehicle

Give `replaces` and only the fields you change:

```json
{ "id": "van", "replaces": "base:caboose", "name": { "en": "Van", "fr": "Fourgon" }, "sprite": "van" }
```

It stays the same vehicle in saved games. Its kind can't change. A pack that only gives new sprites like this is an art set.

### Keeping saved games working

A saved game names each pack and its version, and loads with that version or a newer one. Vehicles are found again by their full name, so in a new version you can add vehicles anywhere and change any number, but don't remove a vehicle or change its id: games saved with it won't load.

## Sprite sheets

One PNG for each drawing, 1024 by 1024 pixels: four rows of four cells, each cell 256 pixels square. Cells go left to right, top to bottom:

| Cells | What's drawn |
|---|---|
| 1 to 8 | On the level, heading each of the eight ways, a step of 45° clockwise as seen from above each time. Cell 1 runs down to the right on screen, cell 3 down to the left, cell 5 up to the left, cell 7 up to the right. |
| 9 to 16 | On a slope, for the four headings of cells 1, 3, 5 and 7, each climbing then going down: 9 and 10 for cell 1's heading, 11 and 12 for cell 3's, 13 and 14 for cell 5's, 15 and 16 for cell 7's. |

- The view is the game's: orthographic, looking down 30°, turned 45°. A tile is 256 pixels wide and 128 high at this size.
- In each cell, the middle of the vehicle stands on the rail at pixel (128, 160) from the cell's top left.
- A step of height is 32 pixels up the screen. On a slope the vehicle tilts by one step along a tile.
- Draw at full size with a clear background. Soft shadows are fine. The game cuts out what's drawn, so empty space costs nothing, but keep everything inside its cell.
- Draw once at this size; the game makes the smaller zoom levels itself.

The base pack's sheets, made by `tools/gen_tiles.py` from Blender renders, are the best guide to size and style.

## The checking tool

`packtool` checks a pack, as a zip or a folder, and lists every problem it finds. It runs anywhere Java 11 or newer does.

```
./gradlew :packtool:installDist
packtool/build/install/packtool/bin/packtool check my-pack
packtool/build/install/packtool/bin/packtool zip my-pack my-pack.ichpack
```

`check` takes `--with other.ichpack` for each pack yours needs besides the base pack. `zip` checks a folder and zips what a pack is made of.

## In the game

Packs are added from the title screen, under Packs, from a file on the phone, and checked as they're added. Adding a newer version of a pack replaces the old one. The new game screen chooses which packs are on for that game.
