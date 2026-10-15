package com.rm.interchange.server

import com.rm.interchange.net.Message
import com.rm.interchange.net.Reject
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.Insignia
import com.rm.interchange.sim.NewGame
import com.rm.interchange.sim.Prices
import com.rm.interchange.sim.Region
import com.rm.interchange.sim.Save
import com.rm.interchange.sim.World
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    private fun joining(
        host: Host, client: String, name: String, password: String = "", join: Int = Message.NEW_COMPANY, companyPassword: String = "",
    ): GameThread {
        val remote = Remote.join("127.0.0.1", host.port, Message.Hello(0, client, name, password, join, companyPassword))
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
        // Each company is named after a town, none the same.
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.none { it == "Ann" || it == "Bo" || it.isBlank() })
        assertTrue(loans[0] > 0 && loans[a.company] > 0)
        assertEquals(3, host.players.size)
        // A rename goes through like any command and every player sees it.
        a.request(Command.RenameCompany(a.company, "  Ann's Lines "))
        Thread.sleep(800)
        inStep(runner, a, b)
        assertEquals("Ann's Lines", on(b) { it.world.companies[a.company].name })
        assertEquals("Ann's Lines", host.companies.first { it.id == a.company }.name)
        assertEquals("Ann's Lines", b.remote!!.companies.first { it.id == a.company }.name)
        // So does a new insignia, in the game and in the list of companies.
        val star = Insignia.presets.first { p -> on(a) { g -> g.world.companies.none { it.insignia.contentEquals(p) } } }
        a.request(Command.SetInsignia(a.company, star))
        Thread.sleep(800)
        inStep(runner, a, b)
        assertContentEquals(star, on(b) { it.world.companies[a.company].insignia })
        assertContentEquals(star, on(runner) { it.world.companies[a.company].insignia })
        assertContentEquals(star, b.remote!!.companies.first { it.id == a.company }.insignia)
    }

    @Test
    fun theHostsBuildingReachesPlayersWhilePaused() {
        val (runner, host) = hosting()
        runner.paused = true
        val a = joining(host, "a", "Ann")
        Thread.sleep(500)
        val before = on(a) { it.world.chunkVersions.sum() }
        runner.request(Command.Loan(0, true))
        runner.request(Command.Terraform(0, 10, 10, true))
        Thread.sleep(1_000)
        assertTrue(on(a) { it.world.companies[0].loan } > 0, "the loan didn't reach the player")
        assertTrue(on(a) { it.world.chunkVersions.sum() } > before, "the building didn't reach the player")
        assertEquals(on(runner) { it.world.checksum() }, on(a) { it.world.checksum() })
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
        val again = Remote.join("127.0.0.1", host.port, Message.Hello(0, "a", "Ann", "", first))
        stops += { again.leave() }
        assertEquals(first, again.company)
    }

    @Test
    fun aNewCompanyIsNewEvenForAPlayerComingBack() {
        val (_, host) = hosting()
        val remote = Remote.join("127.0.0.1", host.port, Message.Hello(0, "a", "Ann", "", Message.NEW_COMPANY))
        val first = remote.company
        remote.leave()
        Thread.sleep(500)
        val again = Remote.join("127.0.0.1", host.port, Message.Hello(0, "a", "Ann", "", Message.NEW_COMPANY))
        stops += { again.leave() }
        assertNotEquals(first, again.company)
        assertEquals(again.company, host.claimsNow["a"])
    }

    @Test
    fun aSlotGivenToANewCompanyStartsClean() {
        val (runner, host) = hosting()
        val a = joining(host, "a", "Ann")
        a.remote!!.lock("ours")
        Thread.sleep(1_000)
        val slot = a.company
        on(runner) { it.world.companies[slot].gone = true }
        val b = joining(host, "b", "Bo")
        assertEquals(slot, b.company)
        for (i in 0 until 100) if (a.company != -1) Thread.sleep(50)
        assertEquals(-1, a.company)
        assertEquals(mapOf("b" to slot), host.claimsNow)
        assertTrue(host.passwordsNow.isEmpty())
    }

    @Test
    fun aNewPasswordKeepsOldPartnersOut() {
        val (_, host) = hosting()
        val a = joining(host, "a", "Ann")
        val b = Remote.join("127.0.0.1", host.port, Message.Hello(0, "b", "Bo", "", a.company))
        b.leave()
        Thread.sleep(500)
        a.remote!!.lock("ours")
        Thread.sleep(500)
        val hello = Message.Hello(0, "b", "Bo", "", a.company)
        assertEquals(Reject.COMPANY_PASSWORD, assertFailsWith<Refused> { Remote.join("127.0.0.1", host.port, hello) }.reason)
        assertEquals(a.company, host.claimsNow["a"])
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

    @Test
    fun twoPlayersCanShareACompany() {
        val (runner, host) = hosting()
        val a = joining(host, "a", "Ann")
        Thread.sleep(500)
        val seen = Remote.look("127.0.0.1", host.port)
        assertEquals(listOf(0, a.company), seen.companies.map { it.id })
        assertEquals(on(runner) { it.world.companies[a.company].name }, seen.companies[1].name)
        val b = joining(host, "b", "Bo", join = a.company)
        assertEquals(a.company, b.company)
        Thread.sleep(500)
        val before = on(runner) { it.world.companies[a.company].loan }
        a.request(Command.Loan(a.company, true))
        b.request(Command.Loan(b.company, true))
        Thread.sleep(800)
        inStep(runner, a, b)
        assertEquals(before + 2 * Prices.LOAN_STEP, on(runner) { it.world.companies[a.company].loan })
        assertEquals(2, host.companies.first { it.id == a.company }.players)
        // The host's own company can be shared too.
        val c = joining(host, "c", "Cy", join = 0)
        assertEquals(0, c.company)
    }

    @Test
    fun aCompanyPasswordKeepsOthersOut() {
        val (_, host) = hosting()
        val a = joining(host, "a", "Ann")
        a.remote!!.lock("ours")
        host.lock(0, "mine")
        Thread.sleep(500)
        assertEquals(listOf(true, true), Remote.look("127.0.0.1", host.port).companies.map { it.locked })
        val hello = Message.Hello(0, "b", "Bo", "", a.company)
        assertEquals(Reject.COMPANY_PASSWORD, assertFailsWith<Refused> { Remote.join("127.0.0.1", host.port, hello) }.reason)
        assertEquals(Reject.COMPANY_PASSWORD, assertFailsWith<Refused> { Remote.join("127.0.0.1", host.port, hello.copy(companyPassword = "wrong")) }.reason)
        assertEquals(Reject.COMPANY_PASSWORD, assertFailsWith<Refused> { Remote.join("127.0.0.1", host.port, hello.copy(join = 0, companyPassword = "ours")) }.reason)
        val b = Remote.join("127.0.0.1", host.port, hello.copy(companyPassword = "ours"))
        stops += { b.leave() }
        assertEquals(a.company, b.company)
        // Bo can't move to the host's company without its password, but can come back to Ann's.
        b.leave()
        Thread.sleep(500)
        assertEquals(Reject.COMPANY_PASSWORD, assertFailsWith<Refused> { Remote.join("127.0.0.1", host.port, hello.copy(join = 0)) }.reason)
        val again = Remote.join("127.0.0.1", host.port, hello)
        stops += { again.leave() }
        assertEquals(a.company, again.company)
        // Cleared, anyone may join.
        a.remote.lock("")
        Thread.sleep(500)
        val c = joining(host, "c", "Cy", join = a.company)
        assertEquals(a.company, c.company)
    }

    @Test
    fun companyPasswordsAreKeptForNextTime() {
        val (runner, host) = hosting()
        val a = joining(host, "a", "Ann")
        a.remote!!.lock("ours")
        Thread.sleep(500)
        val kept = host.passwordsNow
        assertEquals(setOf(a.company), kept.keys)
        assertTrue("ours" !in kept.values)
        host.stop()
        // As the dedicated server does on starting again: the same world, claims and passwords.
        val next = Host(runner, "Test", port = 0, hostName = "Host", claimed = host.claimsNow, passwords = kept, announce = false)
        next.start()
        stops += { next.stop() }
        val hello = Message.Hello(0, "b", "Bo", "", a.company)
        assertEquals(Reject.COMPANY_PASSWORD, assertFailsWith<Refused> { Remote.join("127.0.0.1", next.port, hello) }.reason)
        val b = Remote.join("127.0.0.1", next.port, hello.copy(companyPassword = "ours"))
        stops += { b.leave() }
        assertEquals(a.company, b.company)
    }
}
