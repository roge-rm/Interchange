package com.rm.interchange.dedicated

import com.rm.interchange.net.Message
import com.rm.interchange.server.ChatLine
import com.rm.interchange.server.Remote
import com.rm.interchange.sim.Packs
import com.rm.interchange.sim.Save
import com.rm.interchange.sim.World
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** What the admin channel asks of a real game, with a player joined over the network. */
class ServerTest {
    private val state: File = Files.createTempDirectory("server").toFile()
    private val env = mapOf(
        "INTERCHANGE_NAME" to "Test", "INTERCHANGE_PORT" to "0", "INTERCHANGE_LAN_DISCOVERY" to "false",
        "INTERCHANGE_MAP_SIZE" to "64", "INTERCHANGE_SEED" to "7", "INTERCHANGE_RIVALS" to "1",
    )
    private val server = Server(env, state, LogRing()) { settings, roster ->
        // Seed 13 stands for a map that can't be made.
        if (settings.seed == 13L) throw IllegalStateException("no map") else World(settings, roster)
    }
    private var remote: Remote? = null

    @AfterTest
    fun stop() {
        remote?.leave()
        server.close()
        state.deleteRecursively()
    }

    private fun eventually(what: String, test: () -> Boolean) {
        repeat(400) {
            if (test()) return
            Thread.sleep(50)
        }
        throw AssertionError("$what didn't happen")
    }

    private fun status(key: String) = server.status()[key]!!.jsonPrimitive

    @Test
    fun theAdminRunsTheGame() {
        server.open()
        assertEquals(64, status("mapWidth").int)
        assertEquals("7", status("seed").content)
        assertFalse(status("paused").boolean)

        server.pause(true)
        assertTrue(status("paused").boolean)
        server.speed(4)
        assertEquals(4, status("speed").int)

        val alice = Remote.join("127.0.0.1", server.gamePort!!, Message.Hello(0, "a", "Alice", "", Message.NEW_COMPANY, ""))
        remote = alice
        eventually("Alice joining") { server.players().any { it["name"]!!.jsonPrimitive.content == "Alice" } }
        val company = alice.company

        alice.lock("secret")
        val locked = { server.companies().first { it["id"]!!.jsonPrimitive.int == company }["password"]!!.jsonPrimitive.boolean }
        eventually("the password being set") { locked() }
        assertTrue(server.unlock(company))
        assertFalse(locked())
        assertFalse(server.unlock(company))

        server.say("hello")
        eventually("Alice hearing the server") { ChatLine("Server", -1, "hello") in alice.chat }

        assertFalse(server.kick("Nobody"))
        assertTrue(server.kick("Alice"))
        eventually("Alice leaving") { server.players().isEmpty() }

        server.save()
        assertTrue(File(state, "game.ichg").exists())
    }

    @Test
    fun aNewGameReplacesTheOldOne() {
        server.open()
        server.save()
        server.newGame(mapOf("seed" to "9"))
        eventually("the new game") { !status("making").boolean }
        assertEquals("9", status("seed").content)
        assertTrue(File(state, "game.ichg.old").exists())
        server.save()
        assertTrue(File(state, "game.ichg").exists())
    }

    @Test
    fun aNewGameThatCantBeMadeLeavesTheOldOneRunning() {
        server.open()
        server.newGame(mapOf("seed" to "13"))
        eventually("the old game coming back") { !status("making").boolean }
        assertEquals("7", status("seed").content)
        assertFalse(File(state, "game.ichg.old").exists())
        assertTrue(File(state, "game.ichg").exists())
    }

    @Test
    fun aNewGameUsesThePacksFolderAndServesItsPacks() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, text) in mapOf(
                "manifest.json" to """{ "format": 1, "id": "railbus", "version": 1, "name": { "en": "Railbus" }, "needs": ["base"] }""",
                "vehicles.json" to """{ "vehicles": [ { "id": "railbus", "kind": "engine", "name": { "en": "Railbus" }, "from": 1900, "until": 2000,
                    "speed": 80, "power": 300, "weight": 20, "cost": 9000, "running": 800, "length": 8 } ] }""",
                "sprites/railbus.png" to "not drawn",
            )) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.encodeToByteArray())
                z.closeEntry()
            }
        }
        val zip = out.toByteArray()
        val pack = Packs.unzip(zip)
        File(state, "packs").mkdirs()
        File(state, "packs/railbus.ichpack").writeBytes(zip)
        server.open()
        val listed = server.status()["packs"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("railbus"), listed)
        assertContentEquals(zip, Remote.fetchPacks("127.0.0.1", server.gamePort!!, listOf(pack.ref))[pack.ref])
        val alice = Remote.join("127.0.0.1", server.gamePort!!, Message.Hello(0, "a", "Alice", "", Message.NEW_COMPANY, ""))
        remote = alice
        val find = { ref: com.rm.interchange.sim.PackRef -> listOf(Packs.BASE, pack).firstOrNull { it.ref == ref } }
        assertEquals(pack.ref, Save.read(alice.welcome!!.save, find).roster.packs.last())
    }
}
