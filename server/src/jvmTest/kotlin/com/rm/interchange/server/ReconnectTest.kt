package com.rm.interchange.server

import com.rm.interchange.net.ChatEvent
import com.rm.interchange.net.Heartbeat
import com.rm.interchange.net.Listener
import com.rm.interchange.net.Message
import com.rm.interchange.net.Reject
import com.rm.interchange.net.Wire
import com.rm.interchange.sim.NewGame
import com.rm.interchange.sim.Region
import com.rm.interchange.sim.Save
import com.rm.interchange.sim.World
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Players whose connection drops, or goes quiet, and come back. */
class ReconnectTest {
    private val stops = ArrayList<() -> Unit>()
    private val beat = Heartbeat(pingMillis = 50, timeoutMillis = 400)
    private val quick = longArrayOf(50, 100)

    @AfterTest
    fun stopAll() = stops.asReversed().forEach { it() }

    private fun hosting(): Pair<GameThread, Host> {
        val runner = GameThread({ World(NewGame(64, 64, 7, 1930, Region.MIXED, towns = 2, industries = 2, rivals = 1)) })
        runner.speed = 4
        runner.start()
        val host = Host(runner, "Test", port = 0, hostName = "Host", announce = false, beat = beat)
        host.start()
        stops += { host.stop(); runner.stop() }
        return runner to host
    }

    private fun join(host: Host, client: String, name: String, join: Int = Message.NEW_COMPANY): Remote =
        Remote.join("127.0.0.1", host.port, Message.Hello(0, client, name, "", join), beat = beat).also { r -> stops += { r.leave() } }

    private fun follow(remote: Remote, world: World = Save.read(remote.welcome!!.save)): GameThread {
        val runner = GameThread({ world }, remote)
        runner.company = remote.company
        runner.start()
        stops += { runner.stop() }
        return runner
    }

    private fun <T> on(runner: GameThread, f: (Game) -> T): T {
        val done = CompletableFuture<T>()
        runner.onGameThread { done.complete(f(it)) }
        return done.get(20, TimeUnit.SECONDS)
    }

    private fun eventually(what: String, test: () -> Boolean) {
        val until = System.nanoTime() + 10_000_000_000L
        while (!test()) {
            assertTrue(System.nanoTime() < until, what)
            Thread.sleep(20)
        }
    }

    private fun events(lines: List<ChatLine>) = lines.filter { it.event != 0 }.map { it.event to it.name }

    @Test
    fun aClientThatGoesQuietIsDroppedAfterTheTimeout() {
        val (_, host) = hosting()
        Thread.sleep(200)
        // Says hello, then reads and sends nothing more.
        val raw = Socket("127.0.0.1", host.port)
        stops += { raw.close() }
        DataOutputStream(raw.getOutputStream()).apply {
            val b = Wire.encode(Message.Hello(Wire.PROTOCOL, "q", "Quiet", "", Message.NEW_COMPANY))
            writeInt(b.size); write(b); flush()
        }
        eventually("the quiet player joined") { host.players.any { it.name == "Quiet" } }
        eventually("the quiet player was let go") { host.players.none { it.name == "Quiet" } }
        assertEquals(listOf(ChatEvent.JOINED to "Quiet", ChatEvent.LEFT to "Quiet"), events(host.chat))
    }

    @Test
    fun aSecondHelloReplacesTheFirstAndKeepsTheCompany() {
        val (_, host) = hosting()
        Thread.sleep(200)
        val first = join(host, "a", "Ann")
        val company = first.company
        assertTrue(company >= 0)
        val second = join(host, "a", "Ann", company)
        assertEquals(company, second.company)
        eventually("the first connection was closed") { first.lost != null }
        Thread.sleep(200)
        assertEquals(1, host.players.count { it.name == "Ann" })
        // Back, and never said to have left.
        assertEquals(listOf(ChatEvent.JOINED to "Ann", ChatEvent.BACK to "Ann"), events(host.chat))
    }

    @Test
    fun aCutConnectionReconnectsToTheSameCompanyAndWorld() {
        val (hostRunner, host) = hosting()
        Thread.sleep(200)
        val watcher = join(host, "w", "Wes", Message.SPECTATE)
        val remote = join(host, "a", "Ann")
        val company = remote.company
        follow(remote)
        Thread.sleep(300)
        remote.cut()
        eventually("the connection dropped") { remote.lost != null }
        assertTrue(remote.dropped)
        val again = Reconnect.of(remote, { Save.read(it.welcome!!.save) }, quick, 10_000, beat = beat)
        again.start()
        eventually("back in") { again.state is Reconnect.State.Back }
        val back = again.state as Reconnect.State.Back
        stops += { back.remote.leave() }
        assertEquals(company, back.remote.company)
        val runner = follow(back.remote, back.world)

        hostRunner.paused = true
        Thread.sleep(200)
        val (frame, sum) = on(hostRunner) { it.frame to it.world.checksum() }
        eventually("the player caught up") { on(runner) { it.frame } >= frame }
        assertEquals(sum, on(runner) { it.world.checksum() })
        hostRunner.paused = false

        fun ann() = events(watcher.chat).filter { it.second == "Ann" }
        eventually("the lines came") { ann().lastOrNull()?.first == ChatEvent.BACK }
        val lines = ann()
        assertEquals(ChatEvent.JOINED to "Ann", lines[0])
        // The cut is seen as leaving, unless the host only heard of it once Ann was back.
        assertTrue(lines.drop(1) == listOf(ChatEvent.LEFT to "Ann", ChatEvent.BACK to "Ann") || lines.drop(1) == listOf(ChatEvent.BACK to "Ann"), "$lines")
    }

    @Test
    fun aKickOrAClosedGameIsntADrop() {
        val (_, host) = hosting()
        Thread.sleep(200)
        val ann = join(host, "a", "Ann")
        val bo = join(host, "b", "Bo")
        assertTrue(host.kick("Ann"))
        eventually("Ann was sent away") { ann.lost != null }
        assertEquals(Reject.KICKED, ann.ended)
        assertFalse(ann.dropped)
        host.stop()
        eventually("the game closed") { bo.lost != null }
        assertEquals(Reject.CLOSED, bo.ended)
        assertFalse(bo.dropped)
        val left = join(hosting().second, "c", "Cy")
        left.leave()
        eventually("Cy left") { left.lost != null }
        assertFalse(left.dropped)
    }

    @Test
    fun aRefusalGivesUpAtOnce() {
        val tries = AtomicInteger()
        val fake = Listener(0, beat) { c ->
            tries.incrementAndGet()
            c.onMessage = { if (it is Message.Hello) { c.send(Message.Rejected(Reject.VERSION)); c.close() } }
            c.start()
        }
        fake.start()
        stops += { fake.close() }
        val again = Reconnect("127.0.0.1", fake.port, Message.Hello(0, "a", "Ann", "", 0), { Save.read(it.welcome!!.save) }, quick, 10_000, beat = beat)
        again.start()
        eventually("gave up") { again.state is Reconnect.State.GaveUp }
        assertEquals(Reject.VERSION, (again.state as Reconnect.State.GaveUp).reason)
        Thread.sleep(300)
        assertEquals(1, tries.get())
    }

    @Test
    fun noAnswerGivesUpInTime() {
        // A port nothing listens on.
        val port = ServerSocket(0).use { it.localPort }
        val again = Reconnect("127.0.0.1", port, Message.Hello(0, "a", "Ann", "", 0), { Save.read(it.welcome!!.save) }, quick, 600, beat = beat)
        again.start()
        eventually("gave up") { again.state is Reconnect.State.GaveUp }
        assertEquals(Reconnect.TIMED_OUT, assertIs<Reconnect.State.GaveUp>(again.state).reason)
    }

    @Test
    fun cancelStopsTrying() {
        val port = ServerSocket(0).use { it.localPort }
        val again = Reconnect("127.0.0.1", port, Message.Hello(0, "a", "Ann", "", 0), { Save.read(it.welcome!!.save) }, quick, 10_000, beat = beat)
        again.start()
        Thread.sleep(200)
        again.cancel()
        Thread.sleep(300)
        assertEquals(Reconnect.State.Cancelled, again.state)
    }
}
