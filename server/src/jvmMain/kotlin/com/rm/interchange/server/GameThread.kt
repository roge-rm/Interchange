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
 *
 * A frame where nothing ran, as while paused, publishes only if something
 * that goes into the view changed, so a still game leaves [view] as it is.
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
    /** Whether [view] lists the pieces trains hold, for drawing them; off, the list isn't made. */
    @Volatile var showReserved = false
    /** Whether [view] has the links and why cargo can't get where it's going, for the network screens; off, they aren't made. */
    @Volatile var showNetwork = false
    /** The latest view built with the network while [showNetwork] is on, at most every [NETWORK_NANOS]; null while it's off. */
    @Volatile var networkView: WorldView? = null
        private set
    private var networkAt = 0L
    /** The tile the weather card wants a forecast for, or -1 while it's closed; off, the forecast isn't worked out. */
    @Volatile var forecastAt = -1
    /** Whether the snow risk overlay is shown; off, [riskView] is null and the risk isn't worked out. */
    @Volatile var showRisk = false
    /** The latest view built with the risk while [showRisk] is on, at most every [RISK_NANOS]; null while it's off. */
    @Volatile var riskView: WorldView? = null
        private set
    private var riskAt = 0L
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
    /** Tasks and reads in the order asked; a read doesn't count as a change. */
    private class Task(val reads: Boolean, val run: (Game) -> Unit)
    private val tasks = ConcurrentLinkedQueue<Task>()

    /** Days between autosaves, or 0 for none; and what's done with each, called on the game thread. */
    @Volatile var autosaveDays = 30
    @Volatile var onAutosave: ((ByteArray) -> Unit)? = null
    private var lastAutosaveDay = -1

    /** Runs [task] on the game thread between ticks, where it can read the world whole. */
    fun onGameThread(task: (Game) -> Unit) {
        tasks.add(Task(false, task))
    }

    /** Runs [task] on the game thread between ticks, like [onGameThread], for a task that only reads the world. */
    fun readOnGameThread(task: (Game) -> Unit) {
        tasks.add(Task(true, task))
    }

    /** The world saved, handed to [done] on the game thread. */
    fun save(done: (ByteArray) -> Unit) = readOnGameThread { g -> done(Save.write(g.world)) }
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

    /** Waits up to [millis] for the thread to end. */
    fun join(millis: Long) = thread.join(millis)

    /** Asks for [command] to run, from any thread. */
    fun request(command: Command) {
        incoming.add(command)
    }

    /** Stopping interrupts the thread wherever it is; that ends it quietly, and the step it was in isn't published. */
    private fun loop() {
        try { run() } catch (_: InterruptedException) {}
    }

    private fun run() {
        val g = Game(make())
        remote?.let { r -> r.welcome?.let { w -> g.follow(w.frame, w.pending, r::send) } }
        game = g
        publish(g)
        val tickNanos = 1_000_000_000L / Game.TICKS_PER_SECOND
        var next = System.nanoTime()
        lastAutosaveDay = g.world.day
        while (running) {
            var changed = false
            while (true) {
                g.request(incoming.poll() ?: break)
                changed = true
            }
            while (true) {
                val t = tasks.poll() ?: break
                t.run(g)
                if (!t.reads) changed = true
            }
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
            if (changed || shownChanges != g.changes || wantsPublish(g)) publish(g)
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

    /** What the last [publish] was made from, to tell whether a frame where nothing ran needs another. */
    private var shownChanges = -1L
    private var shownWorld: World? = null
    private var shownCompany = 0
    private var shownReserved = false
    private var shownNetwork = false
    private var shownForecast = -1
    private var shownRisk = false
    /** Whether something was published since the network or risk part was last built, while it's shown. */
    private var networkBehind = false
    private var riskBehind = false

    /** Whether the view needs publishing though no command or tick ran: what it's asked to hold changed, or is due. */
    private fun wantsPublish(g: Game): Boolean {
        if (g.world !== shownWorld || company != shownCompany || showReserved != shownReserved || showNetwork != shownNetwork ||
            forecastAt != shownForecast || showRisk != shownRisk) return true
        val now = System.nanoTime()
        return (showNetwork && networkBehind && now - networkAt >= NETWORK_NANOS) || (showRisk && riskBehind && now - riskAt >= RISK_NANOS)
    }

    private fun publish(g: Game) {
        shownChanges = g.changes
        shownWorld = g.world
        shownCompany = company
        shownReserved = showReserved
        shownNetwork = showNetwork
        shownForecast = forecastAt
        shownRisk = showRisk
        lastView = view
        val now = System.nanoTime()
        // The links and whyNot cost more than the rest of a view, so they're built only every so often.
        val network = showNetwork && (networkView == null || now - networkAt >= NETWORK_NANOS)
        val risk = showRisk && (riskView == null || now - riskAt >= RISK_NANOS)
        view = g.world.view(company, showReserved, network, forecastAt, risk)
        if (risk) {
            riskView = view
            riskAt = now
        } else if (!showRisk) riskView = null
        riskBehind = showRisk && !risk
        if (network) {
            networkView = view
            networkAt = now
        } else if (!showNetwork) networkView = null
        networkBehind = showNetwork && !network
        viewAt = now
        tick = g.world.tick
        day = g.world.day
        money = g.world.companies.getOrNull(company)?.money ?: 0L
    }

    companion object {
        /** How often the network part of the view is built while it's shown: half a second. */
        const val NETWORK_NANOS = 500_000_000L
        /** How often the risk part of the view is built while it's shown: every two seconds. */
        const val RISK_NANOS = 2_000_000_000L
        /** The most ticks a follower runs each time round when it isn't behind. */
        const val FOLLOW_TICKS = 64
        const val CATCH_UP_TICKS = 1024
    }
}
