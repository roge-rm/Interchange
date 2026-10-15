package com.rm.interchange.dedicated

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The admin channel over a real Unix socket, as the web admin uses it. */
class ControlServerTest {
    private lateinit var folder: File
    private lateinit var socket: File
    private lateinit var control: ControlServer

    private val said = ArrayList<String>()
    private val kicked = ArrayList<String>()
    private val unlocked = ArrayList<Int>()
    private var paused: Boolean? = null
    private var speed = 1
    private var saves = 0
    private var newGame: Map<String, String>? = null

    private val handler = object : ControlServer.Handler {
        override fun status() = mapOf("name" to JsonPrimitive("Test"), "paused" to JsonPrimitive(paused == true))
        override fun players() = listOf(
            mapOf("name" to JsonPrimitive("Alice"), "company" to JsonPrimitive(0)),
            mapOf("name" to JsonPrimitive("Bob"), "company" to JsonPrimitive(-1)),
        )
        override fun companies() = listOf(mapOf("id" to JsonPrimitive(0), "password" to JsonPrimitive(true)))
        override fun logLines(count: Int) = (1..count).map { "line $it" }.take(3)
        override fun pause(paused: Boolean) { this@ControlServerTest.paused = paused }
        override fun speed(speed: Int) { this@ControlServerTest.speed = speed }
        override fun say(text: String) { said += text }
        override fun kick(name: String) = (name == "Alice").also { if (it) kicked += name }
        override fun unlock(company: Int) = (company == 0).also { if (it) unlocked += company }
        override fun save(): Map<String, JsonElement> {
            saves++
            return mapOf("saved" to JsonPrimitive(true))
        }
        override fun newGame(settings: Map<String, String>): Map<String, JsonElement> {
            newGameSettings(emptyMap(), settings)
            newGame = settings
            return mapOf("seed" to JsonPrimitive(settings["seed"] ?: ""))
        }
    }

    @BeforeTest
    fun start() {
        folder = Files.createTempDirectory("control").toFile()
        socket = File(folder, "control.sock")
        control = ControlServer(socket, handler, LogRing(10))
        control.start()
    }

    @AfterTest
    fun stop() {
        control.stop()
        folder.deleteRecursively()
    }

    /** Sends one request and reads the reply. */
    private fun call(vararg args: String): JsonObject =
        SocketChannel.open(StandardProtocolFamily.UNIX).use { c ->
            c.connect(UnixDomainSocketAddress.of(socket.toPath()))
            val writer = Channels.newWriter(c, Charsets.UTF_8)
            writer.write(args.joinToString("\t") + "\n")
            writer.flush()
            val line = BufferedReader(Channels.newReader(c, Charsets.UTF_8)).readLine() ?: error("no reply")
            Json.parseToJsonElement(line).jsonObject
        }

    private val JsonObject.ok get() = this["ok"]!!.jsonPrimitive.boolean
    private val JsonObject.error get() = this["error"]!!.jsonPrimitive.content

    @Test
    fun statusAnswersWithTheServersOwnFields() {
        val reply = call("status")
        assertTrue(reply.ok)
        assertEquals("Test", reply["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun playersAndCompaniesComeBackAsLists() {
        val players = call("players")["players"]!!.jsonArray
        assertEquals(2, players.size)
        assertEquals("Alice", players[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(1, call("companies")["companies"]!!.jsonArray.size)
    }

    @Test
    fun logTakesACount() {
        assertEquals(3, call("log", "3")["lines"]!!.jsonArray.size)
    }

    @Test
    fun pauseAndResume() {
        assertTrue(call("pause").ok)
        assertEquals(true, paused)
        assertTrue(call("resume").ok)
        assertEquals(false, paused)
    }

    @Test
    fun speedIsOneTheAppOffers() {
        assertTrue(call("speed", "4").ok)
        assertEquals(4, speed)
        val refused = call("speed", "3")
        assertFalse(refused.ok)
        assertTrue("speed" in refused.error)
        assertFalse(call("speed").ok)
        assertEquals(4, speed)
    }

    @Test
    fun chatCarriesTextAndEscapedTabs() {
        call("chat", "back in five minutes")
        call("chat", "before\\tafter")
        assertEquals(listOf("back in five minutes", "before\tafter"), said)
        assertFalse(call("chat", "").ok)
        assertEquals(2, said.size)
    }

    @Test
    fun kickingSomebodyNotPlayingIsRefused() {
        val refused = call("kick", "Nobody")
        assertFalse(refused.ok)
        assertTrue("Nobody" in refused.error)
        assertTrue(call("kick", "Alice").ok)
        assertEquals(listOf("Alice"), kicked)
    }

    @Test
    fun unlockClearsAPasswordThatIsSet() {
        assertTrue(call("unlock", "0").ok)
        assertEquals(listOf(0), unlocked)
        assertFalse(call("unlock", "3").ok)
        assertFalse(call("unlock", "first").ok)
    }

    @Test
    fun saveIsCarriedOut() {
        assertTrue(call("save").ok)
        assertEquals(1, saves)
    }

    @Test
    fun newGameTakesSettings() {
        assertTrue(call("newgame", "size=64", "seed=42", "region=prairies").ok)
        assertEquals(mapOf("size" to "64", "seed" to "42", "region" to "prairies"), newGame)
        assertTrue(call("newgame").ok)
        assertEquals(emptyMap(), newGame)
    }

    @Test
    fun newGameRefusesBadSettings() {
        newGame = null
        assertTrue("size" in call("newgame", "size=100").error)
        assertTrue("colour" in call("newgame", "colour=red").error)
        assertFalse(call("newgame", "size").ok)
        assertEquals(null, newGame)
    }

    @Test
    fun anUnknownCommandIsRefusedAndTheChannelCarriesOn() {
        val refused = call("restart")
        assertFalse(refused.ok)
        assertTrue("unknown command" in refused.error)
        assertFalse(call("").ok)
        assertTrue(call("status").ok)
    }
}
