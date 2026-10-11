package com.rm.interchange.server

import com.rm.interchange.sim.Command
import com.rm.interchange.sim.World
import com.rm.interchange.sim.WorldView
import com.rm.interchange.sim.view
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Runs a [Game] on a thread of its own, at its tick rate times [speed]. The
 * game thread is the only one that changes the world. Other threads hand it
 * commands with [request] and read what it publishes after each tick.
 *
 * The world is made on the game thread too, by [make], so making a big map
 * doesn't hold up the screen; [game] is null until it's ready.
 */
class GameThread(private val make: () -> World) {
    @Volatile var game: Game? = null
        private set

    @Volatile var paused = false
    /** Ticks run for each one at normal speed. */
    @Volatile var speed = 1

    /** What the screen shows, published after each tick. */
    @Volatile var tick = 0L
        private set
    @Volatile var day = 0
        private set
    @Volatile var money = 0L
        private set
    /** What the screen draws and lists, as of the last tick, and the tick before for smoothing moves between them. */
    @Volatile var view: WorldView? = null
        private set
    @Volatile var lastView: WorldView? = null
        private set
    @Volatile private var viewAt = 0L

    /** How far through to the next tick it is now, 0 to 1, for drawing moves between ticks. */
    fun sinceTick(): Float {
        val period = 1_000_000_000f / Game.TICKS_PER_SECOND
        return ((System.nanoTime() - viewAt) / period).coerceIn(0f, 1f)
    }

    /** The world's checksum, worked out every [CHECKSUM_TICKS] ticks. */
    @Volatile var checksum = 0L
        private set

    private val incoming = ConcurrentLinkedQueue<Command>()
    @Volatile private var running = true

    private val thread = Thread(::loop, "interchange-game").apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY + 1
    }

    fun start() = thread.start()

    fun stop() {
        running = false
        thread.interrupt()
    }

    /** Asks for [command] to run, from any thread. */
    fun request(command: Command) {
        incoming.add(command)
    }

    private fun loop() {
        val g = Game(make())
        game = g
        publish(g)
        val tickNanos = 1_000_000_000L / Game.TICKS_PER_SECOND
        var next = System.nanoTime()
        while (running) {
            while (true) g.request(incoming.poll() ?: break)
            if (!paused) repeat(speed) {
                g.step()
                if (g.world.tick % CHECKSUM_TICKS == 0L) checksum = g.world.checksum()
            }
            publish(g)
            next += tickNanos
            val wait = next - System.nanoTime()
            if (wait > 0) {
                try { Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt()) } catch (_: InterruptedException) {}
            } else if (wait < -tickNanos * 4) {
                // Fallen well behind: carry on from now rather than rushing to catch up.
                next = System.nanoTime()
            }
        }
    }

    private fun publish(g: Game) {
        lastView = view
        view = g.world.view()
        viewAt = System.nanoTime()
        tick = g.world.tick
        day = g.world.day
        money = g.world.companies[0].money
    }

    companion object {
        const val CHECKSUM_TICKS = 256L
    }
}
