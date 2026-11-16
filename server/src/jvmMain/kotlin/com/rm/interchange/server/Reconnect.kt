package com.rm.interchange.server

import com.rm.interchange.net.Heartbeat
import com.rm.interchange.net.Message
import com.rm.interchange.net.Reject
import com.rm.interchange.sim.World
import java.io.IOException

/**
 * Joins the game at [host] on [port] again as [hello] says, after a connection dropped; [of] makes one with the
 * same address, password and client as the one lost, asking for the company it played, or to watch. Each try is a full join, so the world comes again and [load] reads it, off the
 * main thread. Tries come after [firstWait], at once unless the server is starting a new game, then after [waits]
 * between them, the last repeated, until [giveUpMillis] has gone by. A refusal or a world that can't be read gives
 * up at once.
 *
 * Read [state] for where it's got to; [cancel] stops it.
 */
class Reconnect(
    val host: String,
    val port: Int,
    val hello: Message.Hello,
    private val load: (Remote) -> World,
    private val waits: LongArray = WAITS,
    private val giveUpMillis: Long = GIVE_UP_MILLIS,
    private val joinTimeoutMillis: Long = JOIN_TIMEOUT_MILLIS,
    private val beat: Heartbeat = Heartbeat(),
    /** Milliseconds before the first try. */
    private val firstWait: Long = 0,
) {
    sealed interface State {
        /** On try [tries]; the next one starts at [nextAt] by [System.nanoTime], or 0 while a try is under way. */
        data class Trying(val tries: Int, val nextAt: Long) : State {
            /** Whole seconds to the next try, 0 while one is under way. */
            fun secondsToNext(): Int = if (nextAt == 0L) 0 else ((nextAt - System.nanoTime() + 999_999_999) / 1_000_000_000).toInt().coerceAtLeast(0)
        }

        /** In again, with the [world] the server sent; hand both to a new [GameThread]. */
        class Back(val remote: Remote, val world: World) : State

        /** Stopped trying: a [com.rm.interchange.net.Reject] reason, [TIMED_OUT] or [CANT_LOAD] with [detail]. */
        data class GaveUp(val reason: Int, val detail: String = "") : State

        /** [cancel] was called. */
        data object Cancelled : State
    }

    @Volatile var state: State = State.Trying(1, 0)
        private set

    /** Whether it's joining a new game the server started, so the world is a different one. */
    var restarting = false
        private set

    private val lock = Object()
    @Volatile private var cancelled = false
    private val thread = Thread(::run, "interchange-reconnect").apply { isDaemon = true }

    fun start() = thread.start()

    /** Stops trying; a join that comes in after is left at once. */
    fun cancel() {
        synchronized(lock) {
            cancelled = true
            (state as? State.Back)?.remote?.leave()
            state = State.Cancelled
        }
        thread.interrupt()
    }

    private fun run() {
        val start = System.nanoTime()
        var tries = 0
        if (firstWait > 0) {
            if (!set(State.Trying(1, start + firstWait * 1_000_000))) return
            try {
                Thread.sleep(firstWait)
            } catch (e: InterruptedException) {
                return
            }
        }
        while (true) {
            tries++
            if (!set(State.Trying(tries, 0))) return
            val outcome: State? = try {
                val r = Remote.join(host, port, hello, joinTimeoutMillis, beat)
                try {
                    State.Back(r, load(r))
                } catch (e: Exception) {
                    r.leave()
                    throw e
                }
            } catch (e: Refused) {
                State.GaveUp(e.reason)
            } catch (e: IOException) {
                null
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                State.GaveUp(CANT_LOAD, e.message ?: e.toString())
            }
            if (outcome != null) {
                if (!set(outcome) && outcome is State.Back) outcome.remote.leave()
                return
            }
            val wait = waits[minOf(tries - 1, waits.size - 1)]
            val now = System.nanoTime()
            if ((now - start) / 1_000_000 + wait > giveUpMillis) {
                set(State.GaveUp(TIMED_OUT))
                return
            }
            if (!set(State.Trying(tries, now + wait * 1_000_000))) return
            try {
                Thread.sleep(wait)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    /** Sets [s] unless cancelled; false if it was. */
    private fun set(s: State): Boolean = synchronized(lock) {
        if (cancelled) return false
        state = s
        true
    }

    companion object {
        /**
         * Joins again where [lost] was, as the same client, asking for the company it played when it dropped. A
         * server starting a new game is given [restartWait] first, and a player asks for a new company in it.
         */
        fun of(
            lost: Remote, load: (Remote) -> World, waits: LongArray = WAITS, giveUpMillis: Long = GIVE_UP_MILLIS,
            joinTimeoutMillis: Long = JOIN_TIMEOUT_MILLIS, beat: Heartbeat = Heartbeat(), restartWait: Long = RESTART_WAIT,
        ): Reconnect {
            val restarting = lost.ended == Reject.RESTARTING
            val join = when {
                lost.company < 0 -> Message.SPECTATE
                restarting -> Message.NEW_COMPANY
                else -> lost.company
            }
            return Reconnect(
                lost.host, lost.port, lost.hello.copy(join = join), load, waits, giveUpMillis, joinTimeoutMillis, beat,
                if (restarting) restartWait else 0,
            ).also { it.restarting = restarting }
        }

        /** Milliseconds between tries: 1, 2, 4, 8 s, then 10 s for the rest. */
        val WAITS = longArrayOf(1_000, 2_000, 4_000, 8_000, 10_000)
        const val GIVE_UP_MILLIS = 180_000L
        /** How long a try waits to be let in, as long as a first join does. */
        const val JOIN_TIMEOUT_MILLIS = 60_000L
        /** Milliseconds before the first try at a server starting a new game, as making the map takes a while. */
        const val RESTART_WAIT = 5_000L
        /** No answer for [GIVE_UP_MILLIS]. */
        const val TIMED_OUT = -1
        /** In, but the world sent can't be read, such as for a missing pack. */
        const val CANT_LOAD = -2
    }
}
