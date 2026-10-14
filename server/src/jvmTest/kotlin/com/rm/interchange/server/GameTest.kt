package com.rm.interchange.server

import com.rm.interchange.net.Message
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameTest {
    /** A player's copy of the world, kept in step by the host's messages alone, ends up the same, paused frames and all. */
    @Test
    fun aPlayerFollowingTheMessagesKeepsTheSameWorld() {
        val host = Game(World(32, 32, 7))
        val copy = Game(World(32, 32, 7))
        val sent = ArrayList<Message>()
        copy.follow(0, emptyList()) { sent += it }
        host.broadcast = { copy.receive(it) }
        repeat(300) { t ->
            if (t % 7 == 0) host.request(Command.Terraform(0, t % 32, 3, true))
            if (t % 11 == 0) copy.request(Command.Terraform(0, 5, t % 32, false))
            while (sent.isNotEmpty()) host.request((sent.removeAt(0) as Message.Request).command)
            host.frame(if (t in 100..150) 0 else 1 + t % 4)
            copy.catchUp(64)
        }
        assertTrue(!copy.outOfStep)
        assertEquals(host.frame, copy.frame)
        assertEquals(host.world.checksum(), copy.world.checksum())
    }

    /** A copy that goes out of step asks for the world again, and once sent it carries on in step. */
    @Test
    fun aCopyOutOfStepIsSentTheWorldAgain() {
        val host = Game(World(32, 32, 7))
        val copy = Game(World(32, 32, 7))
        val sent = ArrayList<Message>()
        copy.follow(0, emptyList()) { sent += it }
        host.broadcast = { copy.receive(it) }
        repeat(10) { host.frame(2); copy.catchUp(64) }
        copy.world.companies[0].money += 1
        repeat(Game.CHECK_FRAMES.toInt() * 2) {
            host.frame(2)
            copy.catchUp(64)
            if (Message.Resync in sent) {
                sent.clear()
                val (save, pending) = host.joining()
                copy.receive(Message.Welcome(0, "", host.frame, save, pending))
            }
        }
        assertTrue(!copy.outOfStep)
        assertEquals(host.world.checksum(), copy.world.checksum())
    }
}
