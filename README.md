# Interchange

Interchange is a transport game for Android 8.1+ based on OpenTTD.

Start in 1836 with horse-drawn wagons and a bit of money, and build railways, roads, canals and air routes between towns and industries through to now and past it. Carry people, mail and freight, keep things running through the winter, and try to stay ahead of the computer companies, or your friends.

It's at 0.10 and playable, but not finished. Let me know what works and what doesn't in the #interchange channel **[on my discord](https://discord.gg/9Wun47jGC6)**, or open an issue here.

Dan (rm)

---

## What's in it

**Canada.** Maps in six regions (mixed, prairies, Canadian Shield, Rockies, Maritimes and the north), each with its own climate taken from a real town. Towns are named in English or French to suit where they are, and grow over the years.

**Seasons and weather.** Fronts cross the map, snow piles up, drifts and melts, lakes freeze over, the ground heaves in spring and the leaves turn in the fall. The climate warms as the years go by.

**Rail.** Trains with grades and grip, path signals and presignals, bridges and tunnels, electrification, track that wears and washes out, and snow plows for the drifts. Engines and cars from the 1830s on.

**Road.** Dirt tracks to highways, one-way roads, trams, buses and trucks by era, horse-drawn wagons and coaches early on, and winter tires, salt and plows later.

**Water and air.** Canals and locks, ferries, lakers and icebreakers, strips, seaplane bases and airports, and bush planes on floats and skis.

**Industries and money.** Logging camps, farms, mines, mills, oil sands and ports that make and take cargo. Loans, subsidies, company value and a league table.

**Rivals.** Up to 7 computer companies that run buses, trucks and railways, open and give up routes and go broke if they're not careful. You can buy one outright, or pick up one that's in trouble.

**Your company.** Give it a name and an insignia: pick one of 24 or draw your own in the pixel editor. It takes your company's colour.

**Town councils.** Advertise, fund new buildings, put up a statue, do road works or buy exclusive rights.

**The chronicle.** All the news kept in a newspaper of the era, to read back through.

**Day length.** Slow the days down, up to 16 times, so vehicles get more done in a year.

**Packs.** Vehicles, buildings and industries come from packs, and you can make your own. The spec is in [packs/SPEC.md](packs/SPEC.md), with a template in [packs/template](packs/template).

---

## Playing together

Everyone plays the same game, so building, vehicles and money are the same on every phone. You can start a company of your own, share one with a friend or just watch.

- **Host from your phone.** In a game, open the menu, then Multiplayer, then Open to players. You can set a password. The game keeps running with the app in the background, and the notification has a button to stop.
- **Join.** From the title screen, Multiplayer lists the games on your network. Pick one, then start a company, join one already being played or watch. You can also join by address.
- **Packs.** If a game uses packs you don't have, they're sent to you when you join.
- **Company passwords.** Set one in the Players window to keep others out of your company.
- **Chat** from the button in the top bar.

### Running a server

A dedicated server keeps a game going whether anyone's playing or not, with a web page to run it from:

```sh
cp .env.example .env        # set ADMIN_PASSWORD
docker compose up --build
```

Players on your network find it by themselves. The admin page is at `http://<this machine>:8088`, and from there you can pause, change the speed, chat, kick, save or start a new game. All the settings are in [dedicated/README.md](dedicated/README.md).

---

## Installing

There's no release to download yet. For now, build it yourself (below).

## Building it

You need a JDK (21 or newer) and the Android SDK.

```sh
./gradlew :app:assembleDebug        # the app
./gradlew :app:assembleFast         # the app, as quick as a release, debug-signed
./gradlew :sim:jvmTest              # the game's tests
./gradlew :dedicated:installDist    # the server, without docker
```

## How it's laid out

| | |
|---|---|
| `sim` | the game itself: the world, towns, vehicles, weather and rivals, with no screen |
| `server` | runs a game on its own thread, for hosting and joining |
| `net` | messages and connections between players |
| `shared` | the screen, in Compose Multiplatform |
| `app` | the Android app |
| `dedicated`, `web-admin`, `docker` | the dedicated server and its admin page |
| `packs`, `packtool` | the base pack, the pack spec and template, and the tool that checks packs |
| `tools` | Blender scripts that render the art, and the tool that packs it into sheets |

## Licence

GPL 3.0. See [LICENSE](LICENSE).
