package com.rm.interchange.dedicated

import com.rm.interchange.server.Game
import com.rm.interchange.sim.World

/**
 * Runs a game with no screen. For now it only keeps time; players joining over
 * the network come with :net's sockets.
 */
fun main() {
    val seed = System.getenv("INTERCHANGE_SEED")?.toLongOrNull() ?: 1L
    val game = Game(World(256, 256, seed))
    println("Interchange server running, seed $seed")
    val tickNanos = 1_000_000_000L / Game.TICKS_PER_SECOND
    var next = System.nanoTime()
    while (true) {
        game.step()
        if (game.world.tick % (Game.TICKS_PER_SECOND * 60L) == 0L) {
            println("Tick ${game.world.tick}, checksum ${game.world.checksum()}")
        }
        next += tickNanos
        val wait = next - System.nanoTime()
        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
    }
}
