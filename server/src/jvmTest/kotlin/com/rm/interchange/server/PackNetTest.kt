package com.rm.interchange.server

import com.rm.interchange.net.Message
import com.rm.interchange.net.Reject
import com.rm.interchange.sim.BadData
import com.rm.interchange.sim.MissingPacks
import com.rm.interchange.sim.NewGame
import com.rm.interchange.sim.Pack
import com.rm.interchange.sim.PackRef
import com.rm.interchange.sim.Packs
import com.rm.interchange.sim.Region
import com.rm.interchange.sim.Roster
import com.rm.interchange.sim.Save
import com.rm.interchange.sim.World
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A game using an add-on pack, joined by a player who hasn't got it. */
class PackNetTest {
    private val stops = ArrayList<() -> Unit>()

    @AfterTest
    fun stopAll() = stops.asReversed().forEach { it() }

    private fun zip(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, v) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(v)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** A pack with one railbus, its sheet padded out with noise so it takes several parts to send. */
    private fun railbus(speed: Int = 80) = zip(mapOf(
        "manifest.json" to """{ "format": 1, "id": "railbus", "version": 2, "name": { "en": "Railbus" }, "needs": ["base"] }""".encodeToByteArray(),
        "vehicles.json" to """{ "vehicles": [ { "id": "railbus", "kind": "engine", "name": { "en": "Railbus" }, "from": 1900, "until": 2000,
            "speed": $speed, "power": 300, "weight": 20, "cost": 9000, "running": 800, "length": 8 } ] }""".encodeToByteArray(),
        "sprites/railbus.png" to Random(5).nextBytes(2_500_000),
    ))

    private val packZip = railbus()
    private val pack = Packs.unzip(packZip)

    private fun hosting(packBytes: (PackRef) -> ByteArray? = { ref -> packZip.takeIf { ref == pack.ref } }): Pair<GameThread, Host> {
        val roster = Roster.build(listOf(Packs.BASE, pack))
        val runner = GameThread({ World(NewGame(64, 64, 7, 1930, Region.MIXED, towns = 2, industries = 2, rivals = 1), roster) })
        runner.speed = 4
        runner.start()
        val host = Host(runner, "Test", port = 0, hostName = "Host", announce = false, packBytes = packBytes)
        host.start()
        stops += { host.stop(); runner.stop() }
        return runner to host
    }

    private fun <T> on(runner: GameThread, f: (Game) -> T): T {
        val done = CompletableFuture<T>()
        runner.onGameThread { done.complete(f(it)) }
        return done.get(20, TimeUnit.SECONDS)
    }

    @Test
    fun aPlayerWithoutThePackDownloadsItAndJoins() {
        val (runner, host) = hosting()
        Thread.sleep(300)
        // The player's packs: only exact matches, as in a game joined.
        val have = HashMap<PackRef, Pack>()
        val find = { ref: PackRef -> if (ref == Packs.BASE.ref) Packs.BASE else have[ref] }

        val hello = Message.Hello(0, "a", "Ann", "", Message.NEW_COMPANY)
        val first = Remote.join("127.0.0.1", host.port, hello)
        val missing = assertFailsWith<MissingPacks> { Save.read(first.welcome!!.save, find) }.packs
        assertEquals(listOf(pack.ref), missing)
        val company = first.company
        assertTrue(company > 0)
        first.leave()

        val seen = ArrayList<Int>()
        val got = Remote.fetchPacks("127.0.0.1", host.port, missing, progress = { _, at, total -> assertEquals(packZip.size, total); seen += at })
        assertContentEquals(packZip, got[pack.ref])
        assertTrue(seen.size >= 3 && seen.last() == packZip.size, "sent in parts")
        have[pack.ref] = Packs.unzip(got[pack.ref]!!)

        // Back with the same client, to the company made the first time.
        val remote = Remote.join("127.0.0.1", host.port, hello.copy(join = company))
        assertEquals(company, remote.company)
        val player = GameThread({ Save.read(remote.welcome!!.save, find) }, remote)
        player.company = company
        player.onGameThread { it.findPack = find }
        player.start()
        stops += { remote.leave(); player.stop() }
        assertEquals(company + 1, on(runner) { it.world.companies.size })

        // The railbus runs the same on both.
        Thread.sleep(1_500)
        runner.paused = true
        Thread.sleep(200)
        val (frame, sum) = on(runner) { it.frame to it.world.checksum() }
        var at = 0L
        for (i in 0 until 200) {
            at = on(player) { it.frame }
            if (at >= frame) break
            Thread.sleep(50)
        }
        assertTrue(at >= frame, "the player didn't catch up")
        assertEquals(sum, on(player) { it.world.checksum() })
        assertEquals(pack.ref, on(player) { it.world.roster.packs.last() })
    }

    @Test
    fun lookingTellsThePacksSoTheyAreFetchedBeforeJoining() {
        val (runner, host) = hosting()
        Thread.sleep(300)
        val seen = Remote.look("127.0.0.1", host.port)
        assertEquals(listOf(pack.ref), seen.packs)
        val companies = on(runner) { it.world.companies.size }
        val have = HashMap<PackRef, Pack>()
        val find = { ref: PackRef -> if (ref == Packs.BASE.ref) Packs.BASE else have[ref] }
        val missing = seen.packs.filter { find(it) == null }
        for ((ref, zip) in Remote.fetchPacks("127.0.0.1", host.port, missing)) have[ref] = Packs.unzip(zip)
        // Joined once, with the packs in hand, so only one company is made.
        val remote = Remote.join("127.0.0.1", host.port, Message.Hello(0, "a", "Ann", "", Message.NEW_COMPANY))
        stops += { remote.leave() }
        Save.read(remote.welcome!!.save, find)
        Thread.sleep(500)
        assertEquals(companies + 1, on(runner) { it.world.companies.size })
    }

    @Test
    fun twoPlayersCanDownloadAtOnce() {
        val (_, host) = hosting()
        val other = CompletableFuture.supplyAsync { Remote.fetchPacks("127.0.0.1", host.port, listOf(pack.ref)) }
        assertContentEquals(packZip, Remote.fetchPacks("127.0.0.1", host.port, listOf(pack.ref))[pack.ref])
        assertContentEquals(packZip, other.get(30, TimeUnit.SECONDS)[pack.ref])
    }

    @Test
    fun onlyPacksTheGameUsesAreSent() {
        val (_, host) = hosting { ref -> packZip.takeIf { ref.id == pack.id } }
        for (ref in listOf(Packs.BASE.ref, PackRef("other", 1, 0), pack.ref.copy(hash = pack.hash + 1), pack.ref.copy(version = 3))) {
            assertEquals(Reject.NO_PACK, assertFailsWith<Refused> { Remote.fetchPacks("127.0.0.1", host.port, listOf(ref)) }.reason)
        }
    }

    @Test
    fun aPackTheHostHasNotGotIsRefused() {
        val (_, host) = hosting { null }
        assertEquals(Reject.NO_PACK, assertFailsWith<Refused> { Remote.fetchPacks("127.0.0.1", host.port, listOf(pack.ref)) }.reason)
    }

    @Test
    fun aDownloadThatIsNotThePackAskedForIsTurnedAway() {
        // Another version of the pack under the same name, then something that isn't a zip at all.
        val other = railbus(speed = 90)
        assertNotEquals(pack.hash, Packs.unzip(other).hash)
        for (wrong in listOf(other, Random(9).nextBytes(1_500_000))) {
            val (_, host) = hosting { wrong }
            assertFailsWith<BadData> { Remote.fetchPacks("127.0.0.1", host.port, listOf(pack.ref)) }
        }
    }

    @Test
    fun theChecksumCoversThePacks() {
        val ng = NewGame(32, 32, 3, 1930, Region.MIXED, towns = 1, industries = 1, rivals = 0)
        assertNotEquals(World(ng).checksum(), World(ng, Roster.build(listOf(Packs.BASE, pack))).checksum())
    }

    @Test
    fun aDownloadCanBeCancelled() {
        val (_, host) = hosting()
        assertFailsWith<Cancelled> { Remote.fetchPacks("127.0.0.1", host.port, listOf(pack.ref), cancelled = { true }) }
    }
}
