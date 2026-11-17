# Interchange content packs

A content pack adds things to Interchange: trains and the wagons they haul, road vehicles, ships, aircraft, town buildings, industries and cargo. Anyone can make one. The game's own are a pack too, the base pack, in this same format.

This is Interchange packs 1.0, pack format 4. Start from the template pack in `packs/template`, which has one of each thing a pack can do.

## Formats

A pack says in its manifest the format it's made for. Each table below gives the least format a field needs, and a pack has to say at least the format of the newest thing it uses. Say the least that does, so older games load it too: `packtool check` prints the format a pack needs.

| Format | What it brought | Read by |
|---|---|---|
| 1 | Vehicles | Interchange 0.2 on, some fields later |
| 2 | Town buildings and industries, with rules | Interchange 0.14 on |
| 3 | Cargo, stores and recipes, handling | Interchange 0.14 on |
| 4 | Interchange packs 1.0: battery, hydrogen, maglev and hyperloop traction, autonomous road vehicles, drones, future cargo, future and fading industries | Interchange 0.15 on |

A game won't load a pack made for a newer format than it reads, and says so: "made for a newer version of the game (pack format 5, this game reads up to 4, Interchange packs 1.0)". A field the game doesn't know is left out as the pack loads, so a typo doesn't stop it; `packtool check` lists them as warnings. Older games refuse a pack with a field they don't know.

The base pack comes with the game, so it isn't held to its format.

## Limits

| What | Most |
|---|---|
| A pack's files unpacked, together | 64 MB |
| One file | 16 MB |
| Cargo in a game, from every pack | 32 |
| Town buildings in a game, from every pack | 4095 |
| Industries in a game, from every pack | 4095 |
| An industry's layout | 5 by 5 tiles |
| A rule | 1000 characters |

The game holds every pack it has in memory, and sends a pack whole to players joining a game, so the 64 MB keeps that within what a phone can spare. The base pack's zip, made from the game's own sheets by `tools/gen_tiles.py`, can be up to 128 MB, as the game never adds it from a file.

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
  "format": 4,
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

In the tables, "needed" means a pack has to give it. A field that changes another pack's thing (`replaces`, below) needs only `id` and `replaces`.

| Field | Type | Default | Format | What it is |
|---|---|---|---|---|
| format | whole number, 1 to 4 | needed | 1 | The pack format, as above. |
| id | text, lower case letters, digits, `-` and `_`, up to 32 | needed | 1 | Keep it the same for every version. `base` is the game's own. |
| version | whole number, 1 to 1000000 | needed | 1 | Raise it each time you share a new version. |
| name | text in each language | needed | 1 | The pack's name. |
| description | text in each language | none | 1 | A line about it. |
| author, licence | text | none | 1 | Who made it and how others may use it. |
| needs | list of pack ids | none | 1 | Packs that have to be on before this one, if it builds on their vehicles or cargo. The base pack is always on first. |
| disables | list of `pack:id` | none | 1 | Vehicles of other packs to take off sale, and town buildings and industries of other packs no longer to build. Those already on the map stay. |

## vehicles.json

```json
{ "vehicles": [ { ... }, { ... } ] }
```

Each vehicle:

| Field | Type | Default | Format | What it is |
|---|---|---|---|---|
| id | text, lower case letters, digits, `-` and `_`, up to 32 | needed | 1 | Its full name is `pack:id`. |
| kind | `engine`, `wagon`, `tender`, `road`, `ship` or `aircraft` | needed | 1 | `engine`, `wagon` or `tender` on the track, `road` for buses, trucks and streetcars. A tender only comes with its engine. |
| name | text in each language | needed | 1 | Its name. |
| plural | text in each language | its name and "s" | 1 | What more than one is called, for wagons. |
| type | text in each language | none | 1 | What railwaymen called it: a wheel arrangement like `4-6-2`, or what a car is for. |
| from, until | whole numbers, 1700 to 2300 | needed | 1 | The years it's on sale, from the first up to but not including the second. |
| speed | whole number, 1 to 2000 | needed for engines, road, ships, aircraft | 1 | Top speed in km/h. Wagons and tenders have none. |
| power | whole number, 1 to 1000000 | needed for engines, road, ships, aircraft | 1 | Horsepower. Wagons and tenders have none. |
| tractive | whole number, 1 to 10000 | its weight times 2.45 | 1 | Engines and road vehicles: starting tractive effort in kN, what the wheels pull before they slip. The default is a quarter of its weight. |
| traction | `steam`, `diesel`, `electric`, `dual`, `horse`, `battery`, `hydrogen`, `maglev` or `hyperloop` | `steam` | 1; 4 for `battery`, `hydrogen`, `maglev`, `hyperloop` | Engines and road vehicles. Electric engines only run under wires. Dual-mode engines run anywhere, on their speed and power under wires and on their diesel speed and power off them. Horse-drawn road vehicles need no winter tires and go on sleigh runners in deep snow, so it doesn't hold them up. `battery` and `hydrogen` (ships and aircraft too) run on a charge or a tank for their `range` in a game with energy on, and as diesel with it off; road vehicles with `electric` that aren't streetcars run as battery. `maglev` (engines and wagons) runs only on maglev guideway, in a game with future transport on, and a maglev train is all maglev cars. `hyperloop` (engines and wagons) runs only in hyperloop tube, the same way, and carries only passengers and mail. Wagons can only be `maglev` or `hyperloop`, ships and aircraft only `battery` or `hydrogen`. |
| diesel_speed, diesel_power | whole numbers, 1 to 2000 and 1 to 1000000 | its speed and power | 1 | Dual-mode engines only: top speed in km/h and horsepower off the wires. |
| life | whole number, 1 to 200 | 35 | 1 | Years it runs well before it wears out and breaks down more. |
| reliability | whole number, 1 to 100 | 85 | 1 | How reliable it is new, in percent. |
| plow | `wedge`, `rotary` or `oneway` | none | 1 | Wagons: `wedge` or `rotary` makes it a snow plow, run at the head of its train. A wedge clears up to a metre of snow and pushes through drifts four times as fast; a rotary clears anything. Road vehicles: `wedge` or `oneway`, a plow truck that clears the road it drives. |
| tram | `true` or `false` | `false` | 1 | Road vehicles: `true` for a streetcar, which runs only on tram rails. |
| autonomous | `true` or `false` | `false` | 4 | Road vehicles: `true` for one with no crew, on sale only in a game with future transport on. Give it a running cost without the crew's share. On highways it follows other autonomous vehicles closer, in convoys. |
| icebreaker | whole number, 1 to 500 | none | 1 | Ships: the ice it breaks through, in cm, clearing a channel as it goes. Other ships stop at 15 cm. |
| hovercraft | `true` or `false` | `false` | 1 | Ships: `true` rides over ice of any thickness. |
| runway | `strip`, `regional`, `city`, `international`, `heli` or `drone` | needed for aircraft | 1; 4 for `drone` | Aircraft: the least it lands at: `strip` (a gravel strip), `regional`, `city` or `international` airport, `heli` for a helicopter, which lands at any airport or a heliport but not a drone pad, or `drone` for a drone, which lands only at a drone pad, carries only mail, goods and food, at most 6, and is on sale only in a game with future transport on. |
| range | whole number, 1 to 30000 | aircraft: no limit; battery 100, hydrogen 400 | 1; 4 for battery and hydrogen | Aircraft: how far it flies without stopping, in km. Stops beyond it are skipped. Battery and hydrogen engines, road vehicles, ships and aircraft: tiles on a full charge or tank. |
| floats | `true` or `false` | `false` | 1 | Aircraft: `true` for a bush plane, which goes on floats in summer and skis in winter, and lands at seaplane bases. |
| weight | whole number, 1 to 100000 | needed | 1 | Tonnes. |
| cost | whole number, 0 to 1000000000 | needed | 1 | Dollars to buy. |
| running | whole number, 0 to 1000000000 | needed | 1 | Dollars a year to run. |
| length | whole number, 1 to 16 | needed | 1 | Sixteenths of a tile. Draw it to match. |
| carries | a cargo id, or a list of them | nothing | 1; 4 for future cargo | The cargo it carries, the first unless set otherwise. Cargo ids: `passengers`, `mail`, `timber`, `lumber`, `grain`, `coal`, `iron_ore`, `steel`, `goods`, `oil`, `fuel`, `livestock`, `food`, `fish`, `paper`, `potash`, `nickel_ore`, `nickel`, `bauxite`, `aluminium`, and the future cargo `lithium`, `batteries`, `hydrogen`, in play only with future transport on; a vehicle carrying only those is on sale only then. Cargo from cargo.json can be named too. |
| capacity | whole number, 0 to 100000 | 0 | 1 | How much it carries. A passenger weighs 90 kg with their bags, mail 50 kg a bag, livestock half a tonne a head, the rest a tonne each, or a pack cargo's `kg`. Needs `carries`. |
| tender | a tender's id in this pack, or `pack:id` | none | 1 | Engines: the tender that comes with it. |
| sprite | a sheet's name | its id | 1 | Its sprite sheet, `sprites/NAME.png`. Vehicles can share one. |
| sprite_off_wire | a sheet's name | `sprite` | 1 | Dual-mode engines: the sprite sheet drawn off the wires, with the pantograph down. |

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

| Field | Type | Default | Format | What it is |
|---|---|---|---|---|
| id | text, lower case letters, digits, `-` and `_`, up to 32 | needed | 2 | Its full name is `pack:id`. |
| name | text in each language | needed | 2 | Its name. |
| from, until | whole numbers, 1700 to 2300 | needed | 2 | The years towns build it, from the first up to but not including the second. A town pulls it down for something newer once its years are past. |
| residents, jobs | whole numbers, 0 to 10000 | needed | 2 | How many live and work in it. |
| zones | a list of `outskirts`, `suburbs`, `inner`, `centre` | needed, but for a monument | 2 | How far into a town it's built. |
| min_population | whole number, 0 to 10000000 | 0 | 2 | How many people a town needs before it builds one. |
| weight | whole number, 1 to 100 | 1 | 2 | How often it's picked among the buildings that suit a lot. |
| monument | `true` or `false` | `false` | 2 | `true` for a statue and the like, which a town never builds; it goes up when a company pays for it. Needs no zones. |
| sprite | a sheet's name | its id | 2 | Its sprite sheet, `sprites/NAME.png`. |

Towns build a pack's buildings along with the base pack's, picked by weight among those whose zone, years and town size suit the lot.

```json
{ "id": "general_store", "name": { "en": "General store", "fr": "Magasin général" }, "from": 1850, "until": 1960, "residents": 4, "jobs": 6, "zones": ["outskirts", "suburbs"], "weight": 3 }
```

## industries.json

```json
{ "industries": [ { ... }, { ... } ] }
```

Each industry:

| Field | Type | Default | Format | What it is |
|---|---|---|---|---|
| id | text, lower case letters, digits, `-` and `_`, up to 32 | needed | 2 | Its full name is `pack:id`. |
| name | text in each language | needed | 2 | Its name. |
| from, until | whole numbers, 1700 to 2300 | needed | 2 | The years new ones open, from the first up to but not including the second. |
| layout | a list of rows, each part names split by spaces | needed | 2 | Its tiles, all rows the same length, at most 5 by 5. The game turns it any of four ways as it's placed. |
| parts | `{ "part": "sheet" }` | none | 2 | The sprite sheet drawn for each part of the layout that's this pack's own. Any part of a base industry's layout can be used without it. |
| regions | `{ "region": n }`, each 0 to 100 | 2 for each | 2 | How often it's placed in each region: `prairies`, `shield`, `rockies`, `maritimes`, `north`, `mixed`. 0 for never. |
| ground | a list of `meadow`, `prairie`, `forest`, `rock`, `tundra`, `snow` | anywhere | 2 | The ground most of it has to stand on. |
| water | `true` or `false` | `false` | 2 | `true` for one on the shore, the first row of its layout facing the water. |
| produces | `{ "cargo": n }`, each 0 to 100000 | nothing | 2; 4 for future cargo | What it makes each month at its usual level. Give a cargo 0 to make it only from what's brought. |
| accepts | a list of cargo ids | nothing | 2; 4 for future cargo | The cargo it takes. One that takes cargo and has a cargo at 0 in `produces` makes that from what it's brought, a tonne for a tonne. |
| recipe | `{ "inputs": { "cargo": n }, "makes": { "cargo": n } }`, each 1 to 100 | a tonne for a tonne | 2; 4 for future cargo | With stockpiles on, it makes in whole batches, each taking `inputs` from its stores and making `makes`, only while every input is in store. Inputs have to be in `accepts`, and what it makes in `produces` at 0. |
| seasons | twelve whole numbers, each 0 to 1000 | 100 each | 2 | How much it makes each month, January first, in percent of its usual. |
| weather | a list of `crops`, `thaw`, `ice` | none | 2 | How the weather changes it: `crops` makes less in drought or soaked ground, `thaw` stops in the spring mud, `ice` shuts it while the water by it is frozen. |
| production | `{ "cargo": "rule" }` | none | 2 | A rule giving that cargo's monthly amount in place of the usual. The cargo has to be in `produces` too, above 0: rivals and subsidies go by that amount. |
| open | a rule | always | 2 | Worked out where and when one would open: 0 means it can't. |
| closes | a rule | never | 2 | Worked out on the first of each month: anything but 0 closes it. |
| future | `true` or `false` | `false` | 4 | `true` for one that opens only in a game with future transport on. From 2030 it opens more often, a little more each decade. One that makes or takes future cargo no pack gives is future too. |
| fades | whole number, 1700 to 2300 | never | 4 | A year from which, with future transport on, it opens less (half as often each decade) and closes more often, a little more each decade, as coal does. Its closing is told 6 months ahead. |

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

| Field | Type | Default | Format | What it is |
|---|---|---|---|---|
| id | text, lower case letters, digits, `-` and `_`, up to 32 | needed | 3 | Cargo ids are shared by every pack in a game, without the pack's id in front: two packs can't add the same one. |
| name | text in each language | needed | 3 | Its name. |
| unit | text in each language | needed | 3 | A short word for amounts of it, as in "40 casks". |
| rate | whole number, 1 to 1000 | needed | 3 | What one pays carried a tile, in cents. The base pack's run from 20 (coal, passengers) to 48 (food). |
| grace | whole number, 0 to 365 | needed | 3 | Days it can take over its pace before it pays less. |
| decay | whole number, 0 to 100 | needed | 3 | How much less it pays a day after that, in percent. It never pays less than a third. |
| pace | whole number, 1 to 10 | needed | 3 | Days a tile it can take: 1 for what has to be quick, 2 for freight that keeps. |
| fast | whole number, 0 to 400 | 0 | 3 | Percent more paid carried much faster than a train, as by air. |
| kg | whole number, 1 to 100000 | needed | 3 | What one weighs. |
| colour | `"#rrggbb"` | needed | 3 | Its colour in the game's lists and charts. |
| towns | `true` or `false` | `false` | 3 | `true` if towns take it at stations with 60 or more people round them, as they take goods. Otherwise only industries that accept it take it. |
| keeps | `true` or `false` | `true` | 3 | `false` for cargo that spoils in an industry's store, as livestock, food and fish do. |
| handling | `people`, `bulk`, `general`, `liquid` or `livestock` | `general` | 3 | How stations handle it, for handling time and charges. |

New cargo come after the base pack's 20, in pack order and then in file order. After them come the future cargo, `lithium`, `batteries` and `hydrogen`, in play only with future transport on and left out of a game whose packs leave no room for them (what names them is turned off). A game can have 32 cargo at most, so 12 from packs, 9 to keep the future cargo. A cargo's place can't change once a game is saved: a saved game only loads with packs giving the same cargo in the same order.

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

`check` takes `--with other.ichpack` for each pack yours needs besides the base pack. It checks every field in this spec, its type and range, the cargo, tenders, parts and packs it names, and every rule, and flags sprite sheets nothing uses or that are drawn wrong and a pack over the size limit. It warns of fields the game doesn't know and of a new cargo no vehicle carries or no industry makes. Last it says the format the pack needs, and if the pack could say an older one. `zip` checks a folder and zips what a pack is made of. `look my-pack out.png` lists the pack's cargo with their colours and draws its buildings.

`packtool check packs/base` checks the base pack's data; its sheets are in `build/packs/base.ichpack`, made by `tools/gen_tiles.py`.

## In the game

Packs are added from the title screen, under Packs, from a file on the phone, and checked as they're added. Adding a newer version of a pack replaces the old one. The new game screen chooses which packs are on for that game.

## Changes

Format 4, Interchange packs 1.0 (Interchange 0.15):
- Vehicles: traction `battery` and `hydrogen` with `range` for engines, road vehicles, ships and aircraft; `maglev` and `hyperloop` for engines and wagons.
- Vehicles: `autonomous` for road vehicles, and runway `drone`.
- The future cargo `lithium`, `batteries` and `hydrogen` can be named.
- Industries: `future` and `fades`.
- Fields the game doesn't know are left out with a warning, where they stopped a pack loading before.
- The format a pack says is checked against what it uses.

Format 3 (Interchange 0.14):
- cargo.json, with `keeps` and `handling`.
- Industries: `recipe`, and the rule functions `brought(cargo)` and `stock(cargo)` and name `made`.

Format 2 (Interchange 0.14):
- houses.json and industries.json, with rules.

Format 1 (Interchange 0.2, with fields added up to 0.14):
- manifest.json and vehicles.json, and sprite sheets.
