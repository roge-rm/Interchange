package com.rm.interchange.server

import com.rm.interchange.net.Message
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.Save
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Joins a server already running, as INTERCHANGE_PROBE=host:port says, plays
 * a little and checks it stays in step. Skipped without it.
 *
 *     INTERCHANGE_PROBE=127.0.0.1:45688 ./gradlew :server:jvmTest --tests '*ProbeTest*'
 */
class ProbeTest {
    @Test
    fun aRunningServerCanBeJoinedAndKeptInStepWith() {
        val probe = System.getenv("INTERCHANGE_PROBE") ?: return
        val host = probe.substringBeforeLast(':')
        val port = probe.substringAfterLast(':').toInt()
        val remote = Remote.join(host, port, Message.Hello(0, "probe", "Probe", System.getenv("INTERCHANGE_PROBE_PASSWORD") ?: "", Message.NEW_COMPANY))
        val runner = GameThread({ Save.read(remote.welcome!!.save) }, remote)
        runner.start()
        try {
            println("Joined ${remote.server} as company ${remote.company}, players ${remote.players}")
            runner.request(Command.Loan(remote.company, true))
            remote.say("probe here")
            // Long enough for a few checksums.
            Thread.sleep(15_000)
            val done = CompletableFuture<Pair<Boolean, Long>>()
            runner.onGameThread { done.complete(it.outOfStep to it.world.companies[remote.company].loan) }
            val (outOfStep, loan) = done.get(10, TimeUnit.SECONDS)
            println("Day ${runner.day}, loan $loan, chat ${remote.chat}")
            assertTrue(remote.lost == null, "lost: ${remote.lost}")
            assertTrue(!outOfStep)
            assertTrue(loan > 0)
        } finally {
            remote.leave()
            runner.stop()
        }
    }
}
