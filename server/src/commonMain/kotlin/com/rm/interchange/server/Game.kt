package com.rm.interchange.server

import com.rm.interchange.net.Message
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.World

/**
 * A game being hosted: the world, and the order commands run in. A single
 * player game is a game with one player and no network.
 *
 * Commands go in with [request] and run [DELAY] ticks later, so they reach
 * every player before their tick comes round; with one player they run at
 * once. Not thread safe: one thread calls everything.
 */
class Game(val world: World) {
    private val pending = ArrayDeque<Message.Scheduled>()

    /** Who to tell about each scheduled command. */
    var broadcast: (Message) -> Unit = {}

    /** One player and nobody to keep in step with: commands run as soon as they're asked for, paused or not. */
    var solo = true

    fun request(command: Command) {
        if (solo) {
            world.apply(command)
            return
        }
        val scheduled = Message.Scheduled(world.tick + DELAY, command)
        pending.addLast(scheduled)
        broadcast(scheduled)
    }

    /** Runs the commands due this tick, then the tick. */
    fun step() {
        while (pending.isNotEmpty() && pending.first().tick <= world.tick) {
            world.apply(pending.removeFirst().command)
        }
        world.step()
        broadcast(Message.RunTo(world.tick + DELAY - 1))
    }

    companion object {
        /** Ticks between a command being asked for and run. */
        const val DELAY = 2
        /** Ticks a second at normal speed. */
        const val TICKS_PER_SECOND = 30
    }
}
