# Interchange dedicated server

A game hosted with no screen. Players join it from Multiplayer in the app, and it carries on when they leave. It saves every game month, when it's stopped and when the admin asks.

## Running it with Docker

```sh
cp .env.example .env        # set ADMIN_PASSWORD
docker compose up --build
```

The game listens on 45688, and the admin page is at `http://<this machine>:8088`.

| | |
|---|---|
| `server` | the game, kept in the `state` volume |
| `web` | the admin page. It talks to the server over a Unix socket in the `control` volume, and the game runs the same without it. |

Both use host networking, so phones on the same network find the game by themselves.

## Running it without Docker

```sh
./gradlew :dedicated:installDist
INTERCHANGE_STATE_DIR=./state INTERCHANGE_CONTROL_SOCKET=./state/control.sock \
    dedicated/build/install/interchange-server/bin/interchange-server
```

## Settings

All are environment variables; `docker-compose.yml` sets them from the shorter names in `.env`.

| Variable | Default | |
|---|---|---|
| `INTERCHANGE_NAME` | `Interchange` | the game's name in the list |
| `INTERCHANGE_PORT` | `45688` | |
| `INTERCHANGE_PASSWORD` | none | needed to join |
| `INTERCHANGE_STATE_DIR` | `./state` | where the game is saved |
| `INTERCHANGE_PACKS_DIR` | `packs` in the state folder | the add-on packs, see below |
| `INTERCHANGE_LAN_DISCOVERY` | `true` | `false` keeps it off the local network's list |
| `INTERCHANGE_CONTROL_SOCKET` | none | the admin channel's socket; none means no admin channel |
| `INTERCHANGE_MAP_SIZE` | `256` | for a new game: 64 to 4096 |
| `INTERCHANGE_SEED` | random | |
| `INTERCHANGE_START_YEAR` | `1930` | |
| `INTERCHANGE_REGION` | `MIXED` | `PRAIRIES`, `SHIELD`, `ROCKIES`, `MARITIMES` or `NORTH` |
| `INTERCHANGE_RIVALS` | `2` | 0 to 7 |
| `INTERCHANGE_DAY_LENGTH` | `10` | 1, 2, 4, 6, 10 or 16 |
| `INTERCHANGE_TOWN_GROWTH` | `NORMAL` | `SUPPLIED`: towns grow only when supplied with what they need |
| `INTERCHANGE_GOALS` | `NONE` | `TOWNS`, `DELIVERIES`, `CONNECTIONS` or `MIXED` |
| `INTERCHANGE_SCENARIO` | none | a `.ichscn` scenario file to start from instead of a new map |

The admin page takes `ADMIN_PASSWORD`, `WEB_PORT` (8088), `WEB_BIND`, `HTTPS` (1 behind a reverse proxy that serves HTTPS) and `FORWARDED_ALLOW_IPS` (the proxy's address, 127.0.0.1 unless set).

It won't start with an empty password or the example's. After 5 wrong passwords from one address, or 30 from anywhere, it refuses sign-ins for 15 minutes. It only takes button presses from its own page.

## The state folder

`game.ichg` is the game, `players.tsv` who last played which company and `passwords.tsv` the company passwords, hashed. A new game from the admin page keeps the last game as `game.ichg.old`; rename it back to `game.ichg` with the server stopped to carry on with it.

## Packs

Put `.ichpack` files in the packs folder (`packs` in the state folder unless set) and start a new game: it uses every pack there, in name order, so a pack that needs another should sort after it. A saved game carries on with the packs it was made with, at that version or newer. Players who haven't got a pack the game uses download it from the server when they join. With Docker, copy them in with `docker compose cp my.ichpack server:/state/packs/`.

## The admin channel

A Unix socket, guarded by its file permissions. One tab-separated line goes in and one line of JSON comes out:

```sh
printf 'status\n' | socat - UNIX-CONNECT:/run/interchange/control.sock
```

| Command | |
|---|---|
| `status` | name, date, map, speed, paused, players, last save |
| `players` | who's playing which company |
| `companies` | money, value and whether a password is set |
| `log <n>` | the last n log lines |
| `pause`, `resume`, `speed <n>` | speed is 1, 2, 4 or 8 |
| `chat <text>` | says it to everyone, from "Server" |
| `kick <name>` | sends a player away |
| `unlock <company>` | clears a company's password |
| `save` | saves now |
| `newgame [key=value ...]` | a new map from size, seed, year, region, rivals and dayLength, the rest from the settings above |

Everything that reads or changes the game is done on the game thread.
