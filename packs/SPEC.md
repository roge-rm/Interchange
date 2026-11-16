# Interchange content packs

A content pack adds things to Interchange: trains and the wagons they haul, road vehicles, ships, aircraft, town buildings, industries and cargo. Anyone can make one. The game's own are a pack too, the base pack, in this same format.

This is format 3. A pack with only vehicles can say format 1, so older versions of the game load it too; one with town buildings or industries needs format 2, and one with cargo format 3. Start from the template pack in `packs/template`, which has one of each thing a pack can do.

## What's in a pack

A pack is a zip file named `something.ichpack` holding:

- `manifest.json`, what the pack is
- `vehicles.json`, the vehicles it adds or changes
- `houses.json`, the town buildings it adds or changes
- `industries.json`, the industries it adds or changes
- `cargo.json`, the cargo it adds or changes
- `sprites/NAME.png`, a sprite sheet for each drawing

Each of the data files can be left out.

The files can sit at the top of the zip or in one folder inside it. Anything else in the zip is left alone.

Text is UTF-8 JSON. Every name, description and type comes in English and can come in French as well: `{ "en": "Ore car", "fr": "Wagon à minerai" }`. English is needed, French is shown to French players when it's there.

## manifest.json

```json
{
  "format": 3,
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
| format | yes | The pack format, 1 to 3. A game won't load a pack made for a newer format. |
| id | yes | Lower case letters, digits, `-` and `_`, up to 32. Keep it the same for every version. `base` is the game's own. |
| version | yes | A whole number. Raise it each time you share a new version. |
| name | yes | The pack's name in each language. |
| description | no | A line about it. |
| author, licence | no | Who made it and how others may use it. |
| needs | no | Ids of packs that have to be on before this one, if it builds on their vehicles. The base pack is always on first. |
| disables | no | Vehicles of other packs to take off sale, and town buildings and industries of other packs no longer to build, as `pack:id`. Those already on the map stay. |

## vehicles.json

```json
{ "vehicles": [ { ... }, { ... } ] }
```

Each vehicle:

| Field | Needed | What it is |
|---|---|---|
| id | yes | Lower case letters, digits, `-` and `_`. Its full name is `pack:id`. |
| kind | yes | `engine`, `wagon` or `tender` on the track, `road` for buses, trucks and streetcars, `ship` or `aircraft`. A tender only comes with its engine. |
| name | yes | Its name in each language. |
| plural | no | What more than one is called, for wagons. |
| type | no | What railwaymen called it: a wheel arrangement like `4-6-2`, or what a car is for. |
| from, until | yes | The years it's on sale, from the first up to but not including the second. |
| speed | engines, road, ships, aircraft | Top speed in km/h. |
| power | engines, road, ships, aircraft | Horsepower. |
| tractive | no | Engines' starting tractive effort in kN, what the wheels pull before they slip. A quarter of the engine's weight if left out (its weight in tonnes times 2.45). |
| traction | no | Engines and road vehicles: `steam` (the default), `diesel`, `electric`, `dual` or `horse`. Electric engines only run under wires. Dual-mode engines run anywhere, on their speed and power under wires and on their diesel speed and power off them. Horse-drawn road vehicles need no winter tires and go on sleigh runners in deep snow, so it doesn't hold them up. |
| diesel_speed, diesel_power | no | Dual-mode engines: top speed in km/h and horsepower off the wires. Their speed and power if left out. |
| life | no | Years it runs well before it wears out and breaks down more. 35 if left out. |
| reliability | no | How reliable it is new, in percent. 85 if left out. |
| plow | no | Wagons: `wedge` or `rotary` makes it a snow plow, run at the head of its train. A wedge clears up to a metre of snow and pushes through drifts four times as fast; a rotary clears anything. Road vehicles: `wedge` or `oneway`, a plow truck that clears the road it drives. |
| tram | no | Road vehicles: `true` for a streetcar, which runs only on tram rails. |
| icebreaker | no | Ships: the ice it breaks through, in cm, clearing a channel as it goes. Other ships stop at 15 cm. |
| hovercraft | no | Ships: `true` rides over ice of any thickness. |
| runway | aircraft | The least it lands at: `strip` (a gravel strip), `regional`, `city` or `international` airport, or `heli` for a helicopter, which lands at any airport or a heliport. |
| range | no | Aircraft: how far it flies without stopping, in km. Stops beyond it are skipped. |
| floats | no | Aircraft: `true` for a bush plane, which goes on floats in summer and skis in winter, and lands at seaplane bases. |
| weight | yes | Tonnes. |
| cost | yes | Dollars to buy. |
| running | yes | Dollars a year to run. |
| length | yes | Sixteenths of a tile, 1 to 16. Draw it to match. |
| carries, capacity | no | The cargo a wagon carries and how much. Cargo ids: `passengers`, `mail`, `timber`, `lumber`, `grain`, `coal`, `iron_ore`, `steel`, `goods`, `oil`, `fuel`, `livestock`, `food`, `fish`, `paper`, `potash`, `nickel_ore`, `nickel`, `bauxite`, `aluminium`. A passenger weighs 90 kg with their bags, mail 50 kg a bag, livestock half a tonne a head, the rest a tonne each. Cargo from cargo.json can be named too. |
| tender | no | An engine's tender: a tender's id in this pack, or `pack:id` in another. |
| sprite | no | Its sprite sheet, `sprites/NAME.png`. Leave it out to use the vehicle's id. Vehicles can share one. |
| sprite_off_wire | no | Dual-mode engines: the sprite sheet drawn off the wires, with the pantograph down. Leave it out to use `sprite` everywhere. |

### Changing another pack's vehicle

Give `replaces` and only the fields you change:

```json
{ "id": "van", "replaces": "base:caboose", "name": { "en": "Van", "fr": "Fourgon" }, "sprite": "van" }
```

It stays the same vehicle in saved games. Its kind can't change. A pack that only gives new sprites like this is an art set.

### Keeping saved games working

A saved game names each pack and its version, and loads with that version or a newer one. Vehicles, town buildings and industries are found again by their full name, so in a new version you can add them anywhere and change any number, but don't remove one or change its id: games saved with it won't load.

## houses.json

```json
{ "houses": [ { ... }, { ... } ] }
```

Each town building:

| Field | Needed | What it is |
|---|---|---|
| id | yes | Lower case letters, digits, `-` and `_`. Its full name is `pack:id`. |
| name | yes | Its name in each language. |
| from, until | yes | The years towns build it, from the first up to but not including the second. A town pulls it down for something newer once its years are past. |
| residents, jobs | yes | How many live and work in it, 0 to 10000 each. |
| zones | yes | How far into a town it's built: any of `outskirts`, `suburbs`, `inner` and `centre`. |
| min_population | no | How many people a town needs before it builds one. 0 if left out. |
| weight | no | How often it's picked among the buildings that suit a lot, 1 to 100. 1 if left out. |
| monument | no | `true` for a statue and the like, which a town never builds; it goes up when a company pays for it. Needs no zones. |
| sprite | no | Its sprite sheet, `sprites/NAME.png`. Leave it out to use the building's id. |

Towns build a pack's buildings along with the base pack's, picked by weight among those whose zone, years and town size suit the lot.

```json
{ "id": "general_store", "name": { "en": "General store", "fr": "Magasin général" }, "from": 1850, "until": 1960, "residents": 4, "jobs": 6, "zones": ["outskirts", "suburbs"], "weight": 3 }
```

## industries.json

```json
{ "industries": [ { ... }, { ... } ] }
```

Each industry:

| Field | Needed | What it is |
|---|---|---|
| id | yes | Lower case letters, digits, `-` and `_`. Its full name is `pack:id`. |
| name | yes | Its name in each language. |
| from, until | yes | The years new ones open, from the first up to but not including the second. |
| layout | yes | Its tiles as rows of part names split by spaces, all rows the same length, at most 5 by 5. The game turns it any of four ways as it's placed. |
| parts | no | `{ "part": "sprite" }`: the sprite sheet drawn for each part of the layout that's this pack's own. Any part of a base industry's layout can be used without it. |
| regions | no | How often it's placed in each region, 0 to 100: `prairies`, `shield`, `rockies`, `maritimes`, `north`, `mixed`. 2 where it doesn't say, 0 for never. |
| ground | no | The ground most of it has to stand on: any of `meadow`, `prairie`, `forest`, `rock`, `tundra`, `snow`. Anywhere if left out. |
| water | no | `true` for one on the shore, the first row of its layout facing the water. |
| produces | no | `{ "cargo": amount }`: what it makes each month at its usual level, 0 to 100000 each. Give a cargo 0 to make it only from what's brought. |
| accepts | no | The cargo it takes, by id. One that takes cargo and has a cargo at 0 in `produces` makes that from what it's brought, a tonne for a tonne. |
| recipe | no | `{ "inputs": { "cargo": n }, "makes": { "cargo": n } }`: with stockpiles on, it makes in whole batches, each taking `inputs` from its stores and making `makes`, only while every input is in store. Each from 1 to 100. Inputs have to be in `accepts`, and what it makes in `produces` at 0. |
| seasons | no | Twelve numbers, January first: how much it makes each month, in percent of its usual, 0 to 1000. |
| weather | no | How the weather changes it: `crops` (makes less in drought or soaked ground), `thaw` (stops in the spring mud), `ice` (shut while the water by it is frozen). |
| production | no | `{ "cargo": "rule" }`: a rule giving that cargo's monthly amount in place of the usual. The cargo has to be in `produces` too, above 0: rivals and subsidies go by that amount. |
| open | no | A rule worked out where and when one would open: 0 means it can't. |
| closes | no | A rule worked out on the first of each month: anything but 0 closes it. |

The cargo ids are those under vehicles.json and those from cargo.json.

```json
{
  "id": "salmon_cannery",
  "name": { "en": "Salmon Cannery", "fr": "Conserverie de saumon" },
  "from": 1870, "until": 2100,
  "regions": { "rockies": 4, "maritimes": 2, "north": 1, "prairies": 0 },
  "water": true,
  "layout": ["salmon_dock salmon_dock", "salmon_cannery fish_shed"],
  "parts": { "salmon_dock": "salmon_dock", "salmon_cannery": "salmon_cannery" },
  "produces": { "fish": 60 },
  "production": { "fish": "pick(month, 0, 0, 0, 20, 60, 120, 160, 140, 80, 30, 0, 0) * level / 16" }
}
```

Without rules an industry makes its `produces` amount times its level over 16, times its season and weather in percent. Its level starts at 16 and goes up or down now and then by how much of what it makes is carried. What it makes is shared out over the month's days.

In a game with stockpiles on, an industry keeps a store of each cargo it takes and makes something of. It works up to 64 t a day from its stores at level 16 (more or less by its level), by its recipe or a tonne for a tonne, and stores up to 90 days of that of each cargo; a full store takes no more of that cargo. One that makes nothing of what it takes, like a port, takes it all and it's gone. What no station takes goes into a stock of about a month's worth, a thirtieth of it offered to stations again each day. Cargo that doesn't keep loses a thirtieth a day in store and in stock. Without stockpiles, as in games saved before them, everything brought is made into its own the next day and what no station takes is lost.

```json
"recipe": { "inputs": { "iron_ore": 2, "coal": 1 }, "makes": { "steel": 2 } }
```

## cargo.json

```json
{
  "cargo": [
    {
      "id": "maple_syrup",
      "name": { "en": "Maple syrup", "fr": "Sirop d'érable" },
      "unit": { "en": "casks", "fr": "barils" },
      "rate": 46, "grace": 20, "decay": 1, "pace": 2, "kg": 300,
      "colour": "#b5651d",
      "towns": true
    }
  ]
}
```

| Field | Needed | What it is |
|---|---|---|
| id | yes | Lower case letters, digits, `-` and `_`. Cargo ids are shared by every pack in a game, without the pack's id in front: two packs can't add the same one. |
| name | yes | Its name. |
| unit | yes | A short word for amounts of it, as in "40 casks". |
| rate | yes | What one pays carried a tile, in cents, 1 to 1000. The base pack's run from 20 (coal, passengers) to 48 (food). |
| grace | yes | Days it can take over its pace before it pays less, 0 to 365. |
| decay | yes | How much less it pays a day after that, in percent, 0 to 100. It never pays less than a third. |
| pace | yes | Days a tile it can take, 1 to 10: 1 for what has to be quick, 2 for freight that keeps. |
| fast | no | Percent more paid carried much faster than a train, as by air, 0 to 400. 0 if not given. |
| kg | yes | What one weighs, 1 to 100000. |
| colour | yes | Its colour in the game's lists and charts, `"#rrggbb"`. |
| towns | no | `true` if towns take it at stations with 60 or more people round them, as they take goods. Otherwise only industries that accept it take it. |
| keeps | no | `false` for cargo that spoils in an industry's store, as livestock, food and fish do. `true` if not given. |

New cargo come after the base pack's 20, in pack order and then in file order. A game can have 32 cargo at most, so 12 from packs. A cargo's place can't change once a game is saved: a saved game only loads with packs giving the same cargo in the same order.

A vehicle or industry can name the cargo of its own pack, the base pack's, and those of packs it lists in `needs`. Any other name stops the pack loading.

Town needs, heating, passengers and mail stay with the base pack's cargo. Rivals' freight lines, subsidies and delivery goals can pick a pack's cargo, from the industries that make it.

### Changing another pack's cargo

Give `replaces` with the cargo's id and only the fields you change. It keeps its place, so saved games still load:

```json
{ "id": "dear_coal", "replaces": "coal", "rate": 26 }
```

Cargo can't be turned off.

## Rules

A rule is a whole-number expression in a string, like `"pick(month, 0, 0, 40, 100, 100, 100, 100, 100, 100, 40, 0, 0) * level / 16"`. The game works it out with whole numbers only; division rounds towards 0.

| Operators, loosest first | |
|---|---|
| `a ? b : c` | `b` if `a` isn't 0, else `c` |
| `a \|\| b` | 1 if either isn't 0, else 0 |
| `a && b` | 1 if neither is 0, else 0 |
| `== != < <= > >=` | 1 if true, else 0 |
| `+ -` | |
| `* / %` | Divided by 0 gives 0, and so does `%` 0. |
| `-a`, `!a` | `!a` is 1 if `a` is 0, else 0 |

Brackets `( )` group as usual.

| Function | What it gives |
|---|---|
| `min(a, b, ...)`, `max(a, b, ...)` | The least or most of two or more. |
| `clamp(x, lo, hi)` | `x`, but at least `lo` and at most `hi`. |
| `abs(x)` | `x` without its sign. |
| `pick(i, a, b, c, ...)` | The `i`-th of the rest counting from 0: `a` for 0, `b` for 1. Below 0 gives the first, past the end the last. Good for a table by month. |
| `brought(cargo)` | What was brought to it of `cargo` (a cargo id) last month, if it takes it, else 0. Without stockpiles, all cargo together. |
| `stock(cargo)` | What it has of `cargo` now, in its store and its stock. 0 without stockpiles. |

| Name | What it is |
|---|---|
| `month` | 0 for January to 11 for December. |
| `year` | The year. |
| `level` | Its level, 16 at its usual. |
| `base` | In `production`, what it would make of that cargo this month without the rule. In `open` and `closes`, that for all its cargo together. |
| `carried` | How much of what it made last month was carried away, in percent. |
| `wet` | How wet the ground is at its top corner, 0 to 255. |
| `snow` | Snow on the ground there, in cm. |
| `mud` | How muddy the ground is there, 0 to 255; over 60 is the spring break-up. |
| `ice` | The thickest ice on the water next to it, in cm; at 15 boats stop. |
| `stations` | How many stations in reach have had a train for something it makes or takes. |
| `made` | What it made last month, all cargo together. |

In `open`, worked out for a place it might go, `level` is 16 and `carried`, `stations`, `made`, `brought` and `stock` are 0.

A `production` rule's answer is kept from 0 to 100000. Its season and weather aren't added on top; use `base` to keep them:

```json
"production": { "grain": "base * (stations > 1 ? 120 : 100) / 100" }
```

```json
"open": "year >= 1900 && snow == 0",
"closes": "year >= 1960 && carried < 10"
```

A rule can be at most 1000 characters, 256 numbers, names and operators, and 32 brackets, calls, `?:`, `-` or `!` inside one another. One that's wrong, too big or reads a name it doesn't know stops the pack loading, with the column where it went wrong.

### Changing another pack's building or industry

As with vehicles, give `replaces` and only the fields you change:

```json
{ "id": "big_farm", "replaces": "base:farm", "produces": { "grain": 120 } }
```

It stays the same building or industry in saved games. A changed layout can use the parts of the one it changes, the base pack's and its own in `parts`.

## Sprite sheets

One PNG for each drawing, 1024 by 1024 pixels: four rows of four cells, each cell 256 pixels square. Cells go left to right, top to bottom:

A vehicle's sheet:

| Cells | What's drawn |
|---|---|
| 1 to 8 | On the level, heading each of the eight ways, a step of 45° clockwise as seen from above each time. Cell 1 runs down to the right on screen, cell 3 down to the left, cell 5 up to the left, cell 7 up to the right. |
| 9 to 16 | On a slope, for the four headings of cells 1, 3, 5 and 7, each climbing then going down: 9 and 10 for cell 1's heading, 11 and 12 for cell 3's, 13 and 14 for cell 5's, 15 and 16 for cell 7's. Aircraft instead have each of the eight headings again up in the air, with no shadow: cell 9 for cell 1's heading and so on; the game draws the shadow on the ground. Ships don't use these. |

- The view is the game's: orthographic, looking down 30°, turned 45°. A tile is 256 pixels wide and 128 high at this size.
- In each cell, the middle of the vehicle stands on the rail at pixel (128, 160) from the cell's top left.
- A step of height is 32 pixels up the screen. On a slope the vehicle tilts by one step along a tile.
- Draw at full size with a clear background. Soft shadows are fine. The game cuts out what's drawn, so empty space costs nothing, but keep everything inside its cell.
- Draw once at this size; the game makes the smaller zoom levels itself.

A town building's or industry part's sheet draws one tile's building, standing on the tile with its middle at (128, 160), at the same size as a vehicle. Only cells 1 to 4 are used and have to have something in them; leave the rest empty. The cell is the edge of the view the building's front faces:

| Cell | Edge | Front faces, on screen |
|---|---|---|
| 1 | XM | up and to the left |
| 2 | XP | down and to the right |
| 3 | YM | up and to the right |
| 4 | YP | down and to the left |

A town building faces the road it was built beside, an industry part the way its industry is turned. When the player turns the view, the game picks the cell for the edge it now faces, so a building facing XP shows cell 2, 4, 1 and 3 as the view turns once, twice, three times and back. You draw the four looks; the game does the rest. A building with the same look from every side can draw the same in all four. Pack buildings have no separate shadow, so draw a soft one in the cell if you want one, and snow isn't laid on them.

`tools/template_buildings.py` draws the template's sheets to this, with an arrow on the roof pointing the way each cell faces. `packtool look my-pack look.png` draws a pack's buildings on their tiles at each cell and each turn so you can check them.

The base pack's sheets, made by `tools/gen_tiles.py` from Blender renders, are the best guide to size and style.

## The checking tool

`packtool` checks a pack, as a zip or a folder, and lists every problem it finds. It runs anywhere Java 11 or newer does.

```
./gradlew :packtool:installDist
packtool/build/install/packtool/bin/packtool check my-pack
packtool/build/install/packtool/bin/packtool zip my-pack my-pack.ichpack
```

`check` takes `--with other.ichpack` for each pack yours needs besides the base pack. It reads every rule and flags sprite sheets nothing uses, and warns of a new cargo no vehicle carries or no industry makes. `zip` checks a folder and zips what a pack is made of. `look my-pack out.png` lists the pack's cargo with their colours and draws its buildings.

## In the game

Packs are added from the title screen, under Packs, from a file on the phone, and checked as they're added. Adding a newer version of a pack replaces the old one. The new game screen chooses which packs are on for that game.
