package com.rm.interchange.dedicated

import com.rm.interchange.sim.Workers
import java.io.File

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
 * - INTERCHANGE_CONTROL_SOCKET, the admin channel's socket file, none unless set
 * - for a new game: INTERCHANGE_MAP_SIZE, INTERCHANGE_SEED, INTERCHANGE_START_YEAR,
 *   INTERCHANGE_REGION (MIXED, PRAIRIES, ...), INTERCHANGE_RIVALS, INTERCHANGE_DAY_LENGTH
 */
fun main() {
    val env = System.getenv()
    val log = LogRing()
    val server = Server(env, File(env["INTERCHANGE_STATE_DIR"] ?: "state"), log)
    server.open()
    log.info("Running on ${Workers.threads} threads")

    val control = env["INTERCHANGE_CONTROL_SOCKET"]?.takeIf { it.isNotBlank() }?.let { path ->
        ControlServer(File(path), server, log).also { it.start() }
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Stopping, saving the game")
        control?.stop()
        server.close()
    })

    while (true) {
        Thread.sleep(60_000)
        server.summary()?.let(log::info)
    }
}
