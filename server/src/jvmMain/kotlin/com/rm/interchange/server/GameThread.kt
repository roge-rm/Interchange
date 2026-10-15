package com.rm.interchange.server

import com.rm.interchange.sim.Command
import com.rm.interchange.sim.Save
import com.rm.interchange.sim.World
import com.rm.interchange.sim.WorldView
import com.rm.interchange.sim.view
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Runs a [Game] on a thread of its own, a frame at a time at its frame rate,
 * each frame [speed] ticks. The game thread is the only one that changes the
 * world. Other threads hand it commands with [request] and read what it
 * publishes after each frame.
 *
 * The world is made on the game thread too, by [make], so making a big map
 * doesn't hold up the screen; [game] is null until it's ready.
 *
 * Following a server, with [remote], frames run as the server sends them,
 * and [paused] and [speed] are the server's to set.
 */
class GameThread(private val make: () -> World, val remote: Remote? = null) {
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
    /** The company played here, whose money is [money]; -1 watching. */
    @Volatile var company = 0
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

    private val incoming = ConcurrentLinkedQueue<Command>()
    private val tasks = ConcurrentLinkedQueue<(Game) -> Unit>()

    /** Days between autosaves, or 0 for none; and what's done with each, called on the game thread. */
    @Volatile var autosaveDays = 30
    @Volatile var onAutosave: ((ByteArray) -> Unit)? = null
    private var lastAutosaveDay = -1

    /** Runs [task] on the game thread between ticks, where it can read the world whole. */
    fun onGameThread(task: (Game) -> Unit) {
        tasks.add(task)
    }

    /** The world saved, handed to [done] on the game thread. */
    fun save(done: (ByteArray) -> Unit) = onGameThread { g -> done(Save.write(g.world)) }
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
        remote?.let { r -> r.welcome?.let { w -> g.follow(w.frame, w.pending, r::send) } }
        game = g
        publish(g)
        val tickNanos = 1_000_000_000L / Game.TICKS_PER_SECOND
        var next = System.nanoTime()
        lastAutosaveDay = g.world.day
        while (running) {
            while (true) g.request(incoming.poll() ?: break)
            while (true) (tasks.poll() ?: break)(g)
            if (remote != null) {
                company = remote.company
                while (true) g.receive(remote.inbox.poll() ?: break)
                // Well behind, as on joining, it runs many frames each time round to catch up.
                g.catchUp(if (g.behind > 4) CATCH_UP_TICKS else FOLLOW_TICKS)
            } else {
                g.frame(if (paused) 0 else speed)
            }
            val every = autosaveDays
            if (every > 0 && g.world.day - lastAutosaveDay >= every) {
                lastAutosaveDay = g.world.day
                onAutosave?.invoke(Save.write(g.world))
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
        view = g.world.view(company)
        viewAt = System.nanoTime()
        tick = g.world.tick
        day = g.world.day
        money = g.world.companies.getOrNull(company)?.money ?: 0L
    }

    companion object {
        /** The most ticks a follower runs each time round when it isn't behind. */
        const val FOLLOW_TICKS = 64
        const val CATCH_UP_TICKS = 1024
    }
}
