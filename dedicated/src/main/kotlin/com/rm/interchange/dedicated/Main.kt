package com.rm.interchange.dedicated

import com.rm.interchange.net.LanDiscovery
import com.rm.interchange.server.GameThread
import com.rm.interchange.server.Host
import com.rm.interchange.sim.NewGame
import com.rm.interchange.sim.Region
import com.rm.interchange.sim.Save
import com.rm.interchange.sim.Workers
import com.rm.interchange.sim.World
import com.rm.interchange.sim.human
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Hosts a game with no screen. It carries on the game saved in the state
 * folder, or starts a new one from the settings, and saves it every game month
 * and on being stopped. Set up by environment variables:
 *
 * - INTERCHANGE_NAME, the game's name in the list
 * - INTERCHANGE_PORT, 45688 unless set
 * - INTERCHANGE_PASSWORD, needed to join, none unless set
 * - INTERCHANGE_STATE_DIR, where the game is saved, ./state unless set
 * - INTERCHANGE_LAN_DISCOVERY, false to keep it off the local network's list
 * - for a new game: INTERCHANGE_MAP_SIZE, INTERCHANGE_SEED, INTERCHANGE_START_YEAR,
 *   INTERCHANGE_REGION (MIXED, PRAIRIES, ...), INTERCHANGE_RIVALS, INTERCHANGE_DAY_LENGTH
 */
fun main() {
    val env = System.getenv()
    val name = env["INTERCHANGE_NAME"] ?: "Interchange"
    val port = env["INTERCHANGE_PORT"]?.toIntOrNull() ?: LanDiscovery.GAME_PORT
    val state = File(env["INTERCHANGE_STATE_DIR"] ?: "state").apply { mkdirs() }
    val saved = File(state, "game.ichg")
    val players = File(state, "players.tsv")
    val claimed = if (players.exists()) players.readLines().mapNotNull { l ->
        l.split('\t').takeIf { it.size == 2 }?.let { (client, company) -> company.toIntOrNull()?.let { client to it } }
    }.toMap() else emptyMap()
    // Company passwords, hashed.
    val locks = File(state, "passwords.tsv")
    val passwords = if (locks.exists()) locks.readLines().mapNotNull { l ->
        l.split('\t').takeIf { it.size == 2 }?.let { (company, hash) -> company.toIntOrNull()?.let { it to hash } }
    }.toMap() else emptyMap()

    val make = {
        if (saved.exists()) {
            log("Carrying on ${saved.path}")
            Save.read(saved.readBytes())
        } else {
            val size = env["INTERCHANGE_MAP_SIZE"]?.toIntOrNull()?.takeIf { it in NewGame.SIZES } ?: 256
            val settings = NewGame(
                width = size, height = size,
                seed = env["INTERCHANGE_SEED"]?.toLongOrNull() ?: System.currentTimeMillis(),
                startYear = env["INTERCHANGE_START_YEAR"]?.toIntOrNull() ?: 1930,
                region = env["INTERCHANGE_REGION"]?.let { r -> Region.entries.firstOrNull { it.name.equals(r, ignoreCase = true) } } ?: Region.MIXED,
                rivals = env["INTERCHANGE_RIVALS"]?.toIntOrNull()?.coerceIn(0, NewGame.MOST_RIVALS) ?: 2,
                dayLength = env["INTERCHANGE_DAY_LENGTH"]?.toIntOrNull()?.takeIf { it in NewGame.DAY_LENGTHS } ?: 10,
            )
            log("Making a new ${size}x$size map, seed ${settings.seed}")
            World(settings)
        }
    }
    val runner = GameThread(make)
    runner.company = -1
    runner.autosaveDays = 30
    runner.start()
    while (runner.game == null) Thread.sleep(100)

    // Players' companies nobody has played, as a new world's first, go to the first new players.
    val world = runner.game!!.world
    val free = world.companies.filter { !it.gone && world.human(it.id) && it.id !in claimed.values }.map { it.id }
    val host = Host(runner, name, port, env["INTERCHANGE_PASSWORD"] ?: "", hostCompany = -1, free = free, claimed = claimed, passwords = passwords,
        announce = env["INTERCHANGE_LAN_DISCOVERY"] != "false")
    val keepAll = { bytes: ByteArray ->
        try {
            keep(saved, bytes)
            keep(players, host.claimsNow.entries.joinToString("") { "${it.key}\t${it.value}\n" }.toByteArray())
            keep(locks, host.passwordsNow.entries.joinToString("") { "${it.key}\t${it.value}\n" }.toByteArray())
        } catch (e: java.io.IOException) {
            log("Couldn't save: ${e.message}")
        }
    }
    runner.onAutosave = keepAll
    host.log = ::log
    host.start()
    log("Running on ${Workers.threads} threads")

    Runtime.getRuntime().addShutdownHook(Thread {
        log("Stopping, saving the game")
        val done = java.util.concurrent.CountDownLatch(1)
        runner.save { bytes -> keepAll(bytes); done.countDown() }
        done.await(30, java.util.concurrent.TimeUnit.SECONDS)
        host.stop()
        runner.stop()
    })

    while (true) {
        Thread.sleep(60_000)
        log("Day ${runner.day}, ${host.players.size} playing")
    }
}

/** Writes [bytes] to [file] through a new file, so a crash part way leaves the last save whole. */
private fun keep(file: File, bytes: ByteArray) {
    val next = File(file.parentFile, file.name + ".new")
    next.writeBytes(bytes)
    if (!next.renameTo(file)) {
        file.delete()
        next.renameTo(file)
    }
}

private fun log(text: String) = println("${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))} $text")
