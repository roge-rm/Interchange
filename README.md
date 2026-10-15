# Interchange

Interchange is a transport game for Android 8.1+, somewhere between OpenTTD and Locomotion, set in Canada. Build railways, roads, shipping and air routes between towns and industries from 1836 into the future, and keep them running through Canadian winters.

Play on your own against computer companies, or with friends over your network or on a dedicated server.

---

## What's in it

**Canada.** Maps in six regions (mixed, prairies, Canadian Shield, Rockies, Maritimes and the north), each with its own climate taken from a real town. Towns are named in English or French and grow over the years. The game is in both.

**Seasons and weather.** Fronts cross the map, snow piles up and melts, lakes freeze, the ground heaves in spring and the leaves turn in the fall. The climate warms through the years.

**Rail.** Train physics with grades and grip, path signals and presignals, bridges and tunnels, electrification, track that wears and washes out, and snow plows for the drifts. Engines and cars from the 1830s to now.

**Road.** Dirt to highway, one-way roads, trams, buses and trucks by era, winter tires, salt and plows.

**Water and air.** Canals and locks, icebreakers, lakers and ferries; strips, seaplane bases and airports, with bush planes on floats and skis.

**Industries and money.** Logging camps, farms, mines, mills, oil sands and ports making and taking cargo, loans, subsidies, company value and a league table.

**Rivals.** Computer companies that run buses, trucks and railways, buy up companies in trouble and deal with town councils as you do. Easy, normal or hard.

**Town councils.** Advertise, fund buildings, put up a statue, do road works or buy exclusive rights.

**The chronicle.** Every bit of news kept in an era newspaper you can read back through.

**Day length.** Slow the days down (up to 16 times) so vehicles get more done in a year.

**Packs.** Vehicles, buildings and industries come from packs, and you can add your own. The spec is in [packs/SPEC.md](packs/SPEC.md) and a template in [packs/template](packs/template).

---

## Playing together

Everyone plays the same game in lockstep, so building, vehicles and money are the same on every phone.

- **Host from your phone.** In a game, open the menu, then Multiplayer, then Open to players. You can set a password.
- **Join.** From the title screen, Multiplayer lists games on your network. Pick one, then start a new company, join one already being played or just watch. You can also join by address.
- **Company passwords.** Set one in the Multiplayer window to keep others out of your company.
- **Chat** from the button in the top bar.

### Running a server

A dedicated server keeps a game going whether anyone's playing or not, with a web page to run it from:

```sh
cp .env.example .env        # set ADMIN_PASSWORD
docker compose up --build
```

Players find it on your network by themselves. The admin page is at `http://<this machine>:8088`. All the settings are in [dedicated/README.md](dedicated/README.md).

---

## Installing

Interchange isn't released yet. To try it, build it yourself (below).

## Building it

You need a JDK (21 or newer) and the Android SDK.

```sh
./gradlew :app:assembleDebug        # the app
./gradlew :sim:jvmTest              # the game's tests
./gradlew :dedicated:installDist    # the server, without docker
```

## How it's laid out

| | |
|---|---|
| `sim` | the game itself: the world, towns, vehicles, weather, rivals. No screen. |
| `server` | runs a game on its own thread, hosting and joining |
| `net` | messages and connections between players |
| `shared` | the screen, in Compose Multiplatform |
| `app` | the Android app |
| `dedicated`, `web-admin`, `docker` | the dedicated server and its admin page |
| `packs`, `packtool` | the base pack, the pack spec and template, and the pack checking tool |
| `tools` | Blender scripts that render the art, and the tool that packs it into sheets |

## Licence

GPL 3.0. See [LICENSE](LICENSE).
