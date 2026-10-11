package com.rm.interchange.dedicated

import com.rm.interchange.server.GameThread
import com.rm.interchange.sim.World
import com.rm.interchange.sim.Workers

/**
 * Runs a game with no screen. For now it only keeps time; players joining over
 * the network come with :net's sockets.
 */
fun main() {
    val seed = System.getenv("INTERCHANGE_SEED")?.toLongOrNull() ?: 1L
    val size = System.getenv("INTERCHANGE_MAP_SIZE")?.toIntOrNull() ?: 256
    val runner = GameThread { World(size, size, seed) }
    runner.start()
    println("Interchange server running, seed $seed, ${size}x$size, ${Workers.threads} threads")
    while (true) {
        Thread.sleep(60_000)
        if (runner.game == null) continue
        println("Tick ${runner.tick}, checksum ${runner.checksum}")
    }
}
