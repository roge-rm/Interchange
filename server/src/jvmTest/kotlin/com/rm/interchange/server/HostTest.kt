package com.rm.interchange.server

import com.rm.interchange.net.Message
import com.rm.interchange.net.Reject
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.NewGame
import com.rm.interchange.sim.Region
import com.rm.interchange.sim.Save
import com.rm.interchange.sim.World
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A host and players over real sockets on this machine. */
class HostTest {
    private val stops = ArrayList<() -> Unit>()

    @AfterTest
    fun stopAll() = stops.asReversed().forEach { it() }

    private fun hosting(password: String = ""): Pair<GameThread, Host> {
        val runner = GameThread({ World(NewGame(64, 64, 7, 1930, Region.MIXED, towns = 2, industries = 2, rivals = 1)) })
        runner.speed = 4
        runner.start()
        val host = Host(runner, "Test", port = 0, password = password, hostName = "Host", announce = false)
        host.start()
        stops += { host.stop(); runner.stop() }
        return runner to host
    }

    private fun joining(host: Host, client: String, name: String, password: String = "", join: Int = Message.NEW_COMPANY): GameThread {
        val remote = Remote.join("127.0.0.1", host.port, Message.Hello(0, client, name, password, join))
        val runner = GameThread({ Save.read(remote.welcome!!.save) }, remote)
        runner.company = remote.company
        runner.start()
        stops += { remote.leave(); runner.stop() }
        return runner
    }

    private fun <T> on(runner: GameThread, f: (Game) -> T): T {
        val done = CompletableFuture<T>()
        runner.onGameThread { done.complete(f(it)) }
        return done.get(20, TimeUnit.SECONDS)
    }

    /** Pauses the host, waits for [players] to catch up to it, and checks every world is the same. */
    private fun inStep(host: GameThread, vararg players: GameThread) {
        host.paused = true
        Thread.sleep(200)
        val (frame, sum) = on(host) { it.frame to it.world.checksum() }
        for (p in players) {
            var at = 0L
            for (i in 0 until 200) {
                at = on(p) { it.frame }
                if (at >= frame) break
                Thread.sleep(50)
            }
            // Paused, frames go on with no ticks in them, so one further on is the same world.
            assertTrue(at >= frame, "a player didn't catch up")
            assertEquals(sum, on(p) { it.world.checksum() }, "a player's world isn't the host's")
        }
        host.paused = false
    }

    @Test
    fun twoPlayersEachGetACompanyAndKeepTheSameWorld() {
        val (runner, host) = hosting()
        Thread.sleep(300)
        val a = joining(host, "a", "Ann")
        val b = joining(host, "b", "Bo")
        assertNotEquals(a.company, b.company)
        assertTrue(a.company > 0 && b.company > 0)
        // Each borrows, and builds, while the game runs.
        repeat(5) { i ->
            runner.request(Command.Loan(0, true))
            a.request(Command.Loan(a.company, true))
            b.request(Command.Terraform(b.company, 10 + i, 10, true))
            Thread.sleep(150)
        }
        Thread.sleep(1_000)
        inStep(runner, a, b)
        val (names, loans) = on(runner) { g -> g.world.companies.map { it.name } to g.world.companies.map { it.loan } }
        assertEquals("Ann", names[a.company])
        assertEquals("Bo", names[b.company])
        assertTrue(loans[0] > 0 && loans[a.company] > 0)
        assertEquals(3, host.players.size)
    }

    @Test
    fun aPlayerCantGiveOrdersForAnotherCompany() {
        val (runner, host) = hosting()
        val a = joining(host, "a", "Ann")
        a.request(Command.Loan(0, true))
        val watcher = joining(host, "w", "Wes", join = Message.SPECTATE)
        assertEquals(-1, watcher.company)
        watcher.request(Command.Loan(a.company, true))
        Thread.sleep(800)
        inStep(runner, a, watcher)
        assertEquals(listOf(0L, 0L), on(runner) { g -> listOf(g.world.companies[0].loan, g.world.companies[a.company].loan) })
    }

    @Test
    fun aPlayerOutOfStepIsBroughtBack() {
        val (runner, host) = hosting()
        val a = joining(host, "a", "Ann")
        Thread.sleep(500)
        on(a) { it.world.companies[0].money += 1 }
        // A checksum comes every 128 frames, a little over four seconds.
        Thread.sleep(6_000)
        inStep(runner, a)
    }

    @Test
    fun aPlayerComingBackGetsTheirCompanyBack() {
        val (_, host) = hosting()
        val remote = Remote.join("127.0.0.1", host.port, Message.Hello(0, "a", "Ann", "", Message.NEW_COMPANY))
        val first = remote.company
        remote.leave()
        Thread.sleep(2_500)
        val again = Remote.join("127.0.0.1", host.port, Message.Hello(0, "a", "Ann", "", Message.NEW_COMPANY))
        stops += { again.leave() }
        assertEquals(first, again.company)
    }

    @Test
    fun aPasswordKeepsOthersOut() {
        val (_, host) = hosting(password = "secret")
        val e = assertFailsWith<Refused> { Remote.join("127.0.0.1", host.port, Message.Hello(0, "a", "Ann", "wrong", Message.NEW_COMPANY)) }
        assertEquals(Reject.PASSWORD, e.reason)
        val ok = Remote.join("127.0.0.1", host.port, Message.Hello(0, "a", "Ann", "secret", Message.NEW_COMPANY))
        stops += { ok.leave() }
        assertTrue(ok.company > 0)
    }

    @Test
    fun chatReachesEveryone() {
        val (_, host) = hosting()
        val a = Remote.join("127.0.0.1", host.port, Message.Hello(0, "a", "Ann", "", Message.NEW_COMPANY))
        val b = Remote.join("127.0.0.1", host.port, Message.Hello(0, "b", "Bo", "", Message.NEW_COMPANY))
        stops += { a.leave(); b.leave() }
        a.say("hello")
        Thread.sleep(300)
        host.say("welcome")
        Thread.sleep(500)
        assertEquals(listOf("hello", "welcome"), b.chat.map { it.text })
        assertEquals("Ann", b.chat[0].name)
        assertEquals(listOf("hello", "welcome"), host.chat.map { it.text })
    }
}
