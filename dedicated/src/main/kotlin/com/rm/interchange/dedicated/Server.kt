package com.rm.interchange.dedicated

import com.rm.interchange.net.LanDiscovery
import com.rm.interchange.server.Game
import com.rm.interchange.server.GameThread
import com.rm.interchange.server.Host
import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.BadPack
import com.rm.interchange.sim.NewGame
import com.rm.interchange.sim.Pack
import com.rm.interchange.sim.PackRef
import com.rm.interchange.sim.Packs
import com.rm.interchange.sim.Region
import com.rm.interchange.sim.Roster
import com.rm.interchange.sim.Save
import com.rm.interchange.sim.Scenarios
import com.rm.interchange.sim.World
import com.rm.interchange.sim.companyValue
import com.rm.interchange.sim.netWorth
import com.rm.interchange.sim.human
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The game this server hosts and what the admin channel can do with it. It
 * carries on the game saved in [state], or starts a new one from the env vars,
 * and saves it every game month, on [close] and when asked. A new game uses
 * every pack in the packs folder, in name order, and players missing one
 * download it from here.
 *
 * The admin's requests come on the control thread; anything reading or
 * changing the world is handed to the game thread and waited for.
 */
class Server(
    private val env: Map<String, String>,
    private val state: File,
    private val log: LogRing,
    /** Makes a new world from the packs' roster; tests swap it for one that fails. */
    private val makeWorld: (NewGame, Roster) -> World = { settings, roster -> World(settings, roster) },
) : ControlServer.Handler {
    private class Running(val runner: GameThread, val host: Host)

    val name = env["INTERCHANGE_NAME"] ?: "Interchange"
    private val port = env["INTERCHANGE_PORT"]?.toIntOrNull() ?: LanDiscovery.GAME_PORT
    private val saved = File(state, "game.ichg")
    private val players = File(state, "players.tsv")
    // Company passwords, hashed.
    private val locks = File(state, "passwords.tsv")
    private val packsDir = env["INTERCHANGE_PACKS_DIR"]?.takeIf { it.isNotBlank() }?.let(::File) ?: File(state, "packs")

    @Volatile private var now: Running? = null
    /** Held while a game opens or closes, so a save, a new game and stopping take turns. */
    private val swap = Any()
    private val startedAt = System.currentTimeMillis()
    @Volatile private var lastSave = 0L

    /** Opens the saved game, or a new one from the env vars, and waits for it to be made. */
    fun open() = synchronized(swap) { open(null) }

    /** Saves and stops the game. */
    fun close() = synchronized(swap) {
        now?.let { shut(it, save = true) }
        now = null
    }

    /** The port players join on, or null while a new game is being made. */
    val gamePort: Int? get() = now?.host?.port

    /** One line on how it's going, for the log now and then. */
    fun summary(): String? {
        val r = now ?: return null
        return "Day ${r.runner.day}, ${r.host.players.size} playing"
    }

    /**
     * Opens the saved game, or a new one from [next] or the env vars. For a new game from [next], the game before is
     * moved to game.ichg.old once the new one is running, so if it can't be made the old one is still there.
     */
    private fun open(next: NewGame?) {
        state.mkdirs()
        packsDir.mkdirs()
        val fresh = next != null || !saved.exists()
        val claimed = if (!fresh && players.exists()) players.readLines().mapNotNull { l ->
            l.split('\t').takeIf { it.size == 2 }?.let { (client, company) -> company.toIntOrNull()?.let { client to it } }
        }.toMap() else emptyMap()
        val passwords = if (!fresh && locks.exists()) locks.readLines().mapNotNull { l ->
            l.split('\t').takeIf { it.size == 2 }?.let { (company, hash) -> company.toIntOrNull()?.let { it to hash } }
        }.toMap() else emptyMap()

        // Made here, so a world that can't be read or made fails the open.
        val packs = readPacks()
        fun find(ref: PackRef) = if (ref.id == Packs.BASE_ID) Packs.BASE.takeIf { it.version >= ref.version }
            else packs.keys.firstOrNull { it.id == ref.id && it.version >= ref.version }
        val scenario = env["INTERCHANGE_SCENARIO"]?.takeIf { it.isNotBlank() && next == null }?.let(::File)
        val world = if (!fresh) {
            log.info("Carrying on ${saved.path}")
            Save.read(saved.readBytes(), ::find)
        } else if (scenario != null) {
            log.info("Starting the scenario ${scenario.path}")
            Scenarios.play(scenario.readBytes(), ::find)
        } else {
            val settings = next ?: newGameSettings(env)
            val roster = try {
                Roster.build(listOf(Packs.BASE) + packs.keys)
            } catch (e: BadPack) {
                throw IllegalStateException("the packs don't fit together: ${e.problems.first()}")
            }
            log.info("Making a new ${settings.width}x${settings.height} map, seed ${settings.seed}" +
                if (packs.isEmpty()) "" else ", with ${packs.keys.joinToString { "${it.id} ${it.version}" }}")
            makeWorld(settings, roster)
        }
        val runner = GameThread({ world })
        runner.company = -1
        runner.autosaveDays = 30
        runner.start()
        while (runner.game == null) Thread.sleep(100)

        // Players' companies nobody has played, as a new world's first, go to the first new players.
        val free = world.companies.filter { !it.gone && world.human(it.id) && it.id !in claimed.values }.map { it.id }
        val host = try {
            Host(runner, name, port, env["INTERCHANGE_PASSWORD"] ?: "", hostCompany = -1, free = free, claimed = claimed,
                passwords = passwords, announce = env["INTERCHANGE_LAN_DISCOVERY"] != "false",
                packBytes = { ref -> packs.entries.firstOrNull { it.key.ref == ref }?.value }).also {
                it.log = log::info
                it.start()
            }
        } catch (e: Exception) {
            runner.stop()
            throw e
        }
        val r = Running(runner, host)
        if (fresh) {
            if (next != null) {
                File(state, saved.name + ".old").let { old ->
                    old.delete()
                    saved.renameTo(old)
                }
                players.delete()
                locks.delete()
            }
            // A new world is saved straight away, so a restart carries on with it.
            onGame(r, 60) { g -> keep(Save.write(g.world), host) }
        }
        runner.onAutosave = { bytes -> keep(bytes, host) }
        now = r
    }

    /** The packs in the packs folder, in name order, each with its zip; any that can't be read are logged and left out. */
    private fun readPacks(): Map<Pack, ByteArray> {
        val out = LinkedHashMap<Pack, ByteArray>()
        val files = packsDir.listFiles { f -> f.isFile && f.name.endsWith(".ichpack") }?.sortedBy { it.name } ?: return out
        for (f in files) {
            try {
                val zip = f.readBytes()
                val pack = Packs.unzip(zip)
                if (pack.id == Packs.BASE_ID || out.keys.any { it.id == pack.id }) log.warn("Left out ${f.name}: ${pack.id} is in already")
                else out[pack] = zip
            } catch (e: BadPack) {
                log.warn("Left out ${f.name}: ${e.problems.first()}")
            } catch (e: IOException) {
                log.warn("Couldn't read ${f.name}: ${e.message}")
            }
        }
        return out
    }

    /** Stops [r], saving it first if [save]. No more of its autosaves are written once this returns. */
    private fun shut(r: Running, save: Boolean) {
        r.runner.onAutosave = null
        onGame(r, 60) { g -> if (save) keep(Save.write(g.world), r.host) }
        r.host.stop()
        r.runner.stop()
    }

    /** On the game thread: writes the game, who plays which company and the passwords. */
    private fun keep(bytes: ByteArray, host: Host) {
        try {
            keep(saved, bytes)
            keep(players, host.claimsNow.entries.joinToString("") { "${it.key}\t${it.value}\n" }.toByteArray())
            keep(locks, host.passwordsNow.entries.joinToString("") { "${it.key}\t${it.value}\n" }.toByteArray())
            lastSave = System.currentTimeMillis()
        } catch (e: IOException) {
            log.warn("Couldn't save: ${e.message}")
        }
    }

    /** Runs [task] on [r]'s game thread and waits for what it gives. */
    private fun <T> onGame(r: Running, seconds: Long = 10, task: (Game) -> T): T {
        val done = CompletableFuture<T>()
        r.runner.onGameThread { g ->
            try {
                done.complete(task(g))
            } catch (e: Throwable) {
                done.completeExceptionally(e)
            }
        }
        return try {
            done.get(seconds, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** The game running now; refused while a new one is being made. */
    private fun running() = now ?: throw IllegalArgumentException("a new game is being made")

    override fun status(): Map<String, JsonElement> {
        val base = mapOf(
            "name" to JsonPrimitive(name),
            "port" to JsonPrimitive(gamePort ?: port),
            "uptimeSeconds" to JsonPrimitive((System.currentTimeMillis() - startedAt) / 1000),
            "lastSaveEpochMillis" to JsonPrimitive(lastSave),
            "newGame" to newGameDefaults(),
        )
        val r = now ?: return base + ("making" to JsonPrimitive(true))
        return base + onGame(r) { g ->
            val w = g.world
            mapOf(
                "making" to JsonPrimitive(false),
                "date" to JsonPrimitive(date(w)),
                "year" to JsonPrimitive(Calendar.year(w.startYear, w.day)),
                "mapWidth" to JsonPrimitive(w.width),
                "mapHeight" to JsonPrimitive(w.settings.height),
                "seed" to JsonPrimitive(w.settings.seed.toString()),
                "region" to JsonPrimitive(w.settings.region.name),
                "speed" to JsonPrimitive(r.runner.speed),
                "paused" to JsonPrimitive(r.runner.paused),
                "players" to JsonPrimitive(r.host.players.size),
                "companies" to JsonPrimitive(w.companies.count { !it.gone }),
                "packs" to JsonArray(w.roster.packs.filter { it.id != Packs.BASE_ID }.map { p ->
                    JsonObject(mapOf("id" to JsonPrimitive(p.id), "version" to JsonPrimitive(p.version)))
                }),
            )
        }
    }

    /** What a new game gets unless the admin picks otherwise, and what may be picked. */
    private fun newGameDefaults(): JsonElement {
        val s = newGameSettings(env)
        return JsonObject(mapOf(
            "size" to JsonPrimitive(s.width),
            "seed" to JsonPrimitive(env["INTERCHANGE_SEED"]?.toLongOrNull()?.toString() ?: ""),
            "year" to JsonPrimitive(s.startYear),
            "region" to JsonPrimitive(s.region.name),
            "rivals" to JsonPrimitive(s.rivals),
            "dayLength" to JsonPrimitive(s.dayLength),
            "sizes" to JsonArray(NewGame.SIZES.map(::JsonPrimitive)),
            "regions" to JsonArray(Region.entries.map { JsonPrimitive(it.name) }),
            "dayLengths" to JsonArray(NewGame.DAY_LENGTHS.map(::JsonPrimitive)),
            "mostRivals" to JsonPrimitive(NewGame.MOST_RIVALS),
            "firstYear" to JsonPrimitive(YEARS.first),
            "lastYear" to JsonPrimitive(YEARS.last),
        ))
    }

    override fun players(): List<Map<String, JsonElement>> {
        val r = now ?: return emptyList()
        return onGame(r) { g ->
            r.host.players.map { p ->
                mapOf(
                    "name" to JsonPrimitive(p.name),
                    "company" to JsonPrimitive(p.company),
                    "companyName" to JsonPrimitive(g.world.companies.getOrNull(p.company)?.name ?: ""),
                )
            }
        }
    }

    override fun companies(): List<Map<String, JsonElement>> {
        val r = now ?: return emptyList()
        return onGame(r) { g ->
            val w = g.world
            val on = r.host.players.groupingBy { it.company }.eachCount()
            val locked = r.host.passwordsNow.keys
            w.companies.filter { !it.gone }.map { c ->
                mapOf(
                    "id" to JsonPrimitive(c.id),
                    "name" to JsonPrimitive(c.name),
                    "human" to JsonPrimitive(w.human(c.id)),
                    "money" to JsonPrimitive(c.money),
                    "value" to JsonPrimitive(w.netWorth(c)),
                    "made" to JsonPrimitive(w.companyValue(c)),
                    "players" to JsonPrimitive(on[c.id] ?: 0),
                    "password" to JsonPrimitive(c.id in locked),
                )
            }
        }
    }

    override fun logLines(count: Int) = log.recent(count).map { it.toString() }

    override fun pause(paused: Boolean) {
        val r = running()
        onGame(r) { r.runner.paused = paused }
        log.info(if (paused) "Paused by the admin" else "Resumed by the admin")
    }

    override fun speed(speed: Int) {
        val r = running()
        onGame(r) { r.runner.speed = speed }
        log.info("Speed set to ${speed}x by the admin")
    }

    override fun say(text: String) {
        running().host.say(text, from = "Server")
        log.info("[admin] $text")
    }

    override fun kick(name: String): Boolean {
        val kicked = running().host.kick(name)
        if (kicked) log.info("Kicked $name")
        return kicked
    }

    override fun unlock(company: Int): Boolean {
        val r = running()
        if (company !in r.host.passwordsNow) return false
        r.host.lock(company, "")
        return true
    }

    override fun save(): Map<String, JsonElement> = synchronized(swap) {
        val r = running()
        onGame(r, 60) { g -> keep(Save.write(g.world), r.host) }
        log.info("Saved, as the admin asked")
        mapOf("saved" to JsonPrimitive(lastSave > 0), "bytes" to JsonPrimitive(saved.length()))
    }

    /**
     * Ends the game and makes a new one from [settings] over the env vars. Players are let go and join again. The
     * game it replaces is kept as game.ichg.old, or carried on if the new one can't be made. The map is made on a
     * thread of its own, as a big one takes a while.
     */
    override fun newGame(settings: Map<String, String>): Map<String, JsonElement> {
        val next = newGameSettings(env, settings)
        synchronized(swap) {
            val r = running()
            log.info("Starting a new game, as the admin asked")
            // Saved first, while it still runs; if that fails it carries on.
            r.runner.onAutosave = null
            try {
                onGame(r, 60) { g -> keep(Save.write(g.world), r.host) }
            } catch (e: Exception) {
                r.runner.onAutosave = { bytes -> keep(bytes, r.host) }
                throw IllegalStateException("couldn't save this game first, so it carries on: $e")
            }
            r.host.say("Starting a new game. Join again in a moment.", from = "Server")
            now = null
            r.host.stop()
            r.runner.stop()
            Thread({
                synchronized(swap) {
                    try {
                        open(next)
                    } catch (e: Exception) {
                        log.warn("Couldn't start the new game, carrying on the last one: $e")
                        try {
                            open(null)
                        } catch (e: Exception) {
                            log.warn("Couldn't carry on the last game either: $e")
                        }
                    }
                }
            }, "interchange-new-game").start()
        }
        return mapOf("size" to JsonPrimitive(next.width), "seed" to JsonPrimitive(next.seed.toString()))
    }

    private companion object {
        val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

        /** The world's date, as 4 Mar 1852. */
        fun date(w: World) =
            "${Calendar.dayOfMonth(w.startYear, w.day)} ${MONTHS[Calendar.month(w.startYear, w.day)]} ${Calendar.year(w.startYear, w.day)}"

        /** Writes [bytes] to [file] through a new file, so a crash part way leaves the last save whole. */
        fun keep(file: File, bytes: ByteArray) {
            val next = File(file.parentFile, file.name + ".new")
            next.writeBytes(bytes)
            if (!next.renameTo(file)) {
                file.delete()
                next.renameTo(file)
            }
        }
    }
}
