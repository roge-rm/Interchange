package com.rm.interchange.server

import com.rm.interchange.net.Message
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.Pack
import com.rm.interchange.sim.PackRef
import com.rm.interchange.sim.Save
import com.rm.interchange.sim.World

/**
 * A game being played: the world, and the order commands run in. It's played
 * one of three ways:
 * - alone, where commands run as soon as they're asked for;
 * - hosting, with [broadcast] set: commands run [DELAY] frames after they're
 *   asked for, so they reach every player before their frame comes round, and
 *   each frame is sent out as it runs;
 * - following a server, with [send] set: commands go to the server, and frames
 *   run as the server sends them, with [receive].
 *
 * A frame runs its commands then some ticks: the speed, or none while paused.
 * Not thread safe: one thread calls everything.
 */
class Game(world: World) {
    /** The world, replaced whole when a follower is sent it again. */
    var world = world
        private set

    /** The next frame to run. */
    var frame = 0L
        private set

    private val pending = ArrayDeque<Message.Scheduled>()

    /** Hosting: who to tell about each command and frame. */
    var broadcast: ((Message) -> Unit)? = null

    /** Following: where requests go. */
    var send: ((Message) -> Unit)? = null
        private set

    /** Following: frames sent by the server, not run yet. */
    private val frames = ArrayDeque<Message.Frame>()

    /** Following: whether this copy went out of step and is waiting to be sent the world again. */
    var outOfStep = false
        private set

    /** Following: finds the packs a world sent again needs; the base pack only if null. */
    var findPack: ((PackRef) -> Pack?)? = null

    val following get() = send != null

    fun request(command: Command) {
        send?.let { return it(Message.Request(command)) }
        val b = broadcast ?: return run(command)
        val scheduled = Message.Scheduled(frame + DELAY, command)
        pending.addLast(scheduled)
        b(scheduled)
    }

    /** Alone or hosting: runs the next frame, its commands then [ticks] ticks. */
    fun frame(ticks: Int) {
        val f = frame
        runFrame(ticks)
        broadcast?.let { b ->
            b(Message.Frame(f, ticks, if (f % CHECK_FRAMES == 0L) world.checksum() or 1 else 0))
        }
    }

    /** What a player joining now needs: the world as of the start of the next frame, and the commands set to come. */
    fun joining(): Pair<ByteArray, List<Message.Scheduled>> = Save.write(world) to pending.toList()

    /** Starts following a server, from the world as of the start of [from] and the commands set to come. */
    fun follow(from: Long, scheduled: List<Message.Scheduled>, send: (Message) -> Unit) {
        this.send = send
        frame = from
        pending.clear()
        pending.addAll(scheduled)
        frames.clear()
        outOfStep = false
    }

    /** Following: takes in a message from the server. */
    fun receive(m: Message) {
        when (m) {
            is Message.Scheduled -> if (!outOfStep) pending.addLast(m)
            is Message.Frame -> if (!outOfStep) frames.addLast(m)
            is Message.Welcome -> {
                world = findPack?.let { Save.read(m.save, it) } ?: Save.read(m.save)
                follow(m.frame, m.pending, send ?: return)
            }
            else -> {}
        }
    }

    /** Following: frames waiting to run. */
    val behind get() = frames.size

    /**
     * Following: runs the frames the server has sent, until about [most] ticks have run. Sends for the world again
     * if a frame's checksum doesn't match.
     */
    fun catchUp(most: Int) {
        var ran = 0
        while (frames.isNotEmpty() && (ran == 0 || ran + frames.first().ticks <= most)) {
            val m = frames.removeFirst()
            if (m.frame != frame) return outOfStep("frame ${m.frame} sent, at $frame")
            runFrame(m.ticks)
            ran += m.ticks
            if (m.checksum != 0L && m.checksum != world.checksum() or 1) return outOfStep("checksums differ at frame ${m.frame}")
        }
    }

    /** Why this copy last went out of step, for the log. */
    var outOfStepWhy = ""
        private set

    /** Hears when this copy goes out of step. */
    var log: (String) -> Unit = {}

    private fun outOfStep(why: String) {
        outOfStep = true
        outOfStepWhy = why
        log("Out of step, asking for the world again: $why")
        frames.clear()
        pending.clear()
        send?.invoke(Message.Resync)
    }

    private fun runFrame(ticks: Int) {
        while (pending.isNotEmpty() && pending.first().frame <= frame) run(pending.removeFirst().command)
        repeat(ticks) { world.step() }
        frame++
    }

    /** Runs [command]; one that throws, from a broken or hostile player, is left half done the same everywhere. */
    private fun run(command: Command) {
        try {
            world.apply(command)
        } catch (_: RuntimeException) {
        }
    }

    companion object {
        /** Frames between a command being asked for and run. */
        const val DELAY = 2
        /** Frames a second, and so ticks a second at normal speed. */
        const val TICKS_PER_SECOND = 30
        /** Frames between checksums sent to players, about four seconds. */
        const val CHECK_FRAMES = 128L
    }
}
