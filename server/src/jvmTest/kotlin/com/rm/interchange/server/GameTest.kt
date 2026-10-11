package com.rm.interchange.server

import com.rm.interchange.net.Message
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.World
import kotlin.test.Test
import kotlin.test.assertEquals

class GameTest {
    /** A player's copy of the world, kept in step by the host's messages alone, ends up the same. */
    @Test
    fun aPlayerFollowingTheMessagesKeepsTheSameWorld() {
        val host = Game(World(32, 32, 7))
        val copy = World(32, 32, 7)
        val waiting = ArrayDeque<Message.Scheduled>()
        var allowed = -1L
        host.broadcast = { m ->
            when (m) {
                is Message.Scheduled -> waiting.addLast(m)
                is Message.RunTo -> allowed = m.tick
                else -> {}
            }
        }
        repeat(50) { t ->
            if (t % 7 == 0) host.request(Command.RaiseLand(0, t % 32, 3))
            host.step()
            while (copy.tick < allowed && copy.tick < host.world.tick) {
                while (waiting.isNotEmpty() && waiting.first().tick <= copy.tick) copy.apply(waiting.removeFirst().command)
                copy.step()
            }
        }
        assertEquals(host.world.checksum(), copy.checksum())
    }
}
