package com.rm.interchange.dedicated

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files

/**
 * The admin channel: a Unix domain socket, one tab-separated line in and one
 * line of JSON out. File permissions guard it, so it needs no password; the
 * web admin reaches it through a shared volume. A tab, newline or backslash
 * in an argument is sent as \t, \n or \\.
 *
 * | Command | |
 * |---|---|
 * | `status` | the game: name, date, map size, speed, paused, players, last save |
 * | `players` | who's playing and which company |
 * | `companies` | money, value and whether a password is set |
 * | `log <n>` | the last n log lines |
 * | `pause`, `resume` | |
 * | `speed <n>` | 1, 2, 4 or 8 |
 * | `chat <text>` | say something to everyone |
 * | `kick <name>` | send a player away |
 * | `unlock <company>` | clear a company's password |
 * | `save` | save the game now |
 * | `newgame [key=value ...]` | a new map; size, seed, year, region, rivals, dayLength, the rest from the env vars |
 */
class ControlServer(private val socketFile: File, private val handler: Handler, private val log: LogRing) {
    /** What the channel can ask the server for. A bad request throws [IllegalArgumentException] with the reason. */
    interface Handler {
        fun status(): Map<String, JsonElement>
        fun players(): List<Map<String, JsonElement>>
        fun companies(): List<Map<String, JsonElement>>
        fun logLines(count: Int): List<String>
        fun pause(paused: Boolean)
        fun speed(speed: Int)
        fun say(text: String)
        fun kick(name: String): Boolean
        fun unlock(company: Int): Boolean
        fun save(): Map<String, JsonElement>
        fun newGame(settings: Map<String, String>): Map<String, JsonElement>
    }

    @Volatile private var channel: ServerSocketChannel? = null

    fun start() {
        // A socket file left by a crash would make bind fail.
        Files.deleteIfExists(socketFile.toPath())
        socketFile.absoluteFile.parentFile?.mkdirs()
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(socketFile.toPath()))
        channel = server
        log.info("Control socket on ${socketFile.absolutePath}")
        Thread({
            try {
                while (true) {
                    val client = server.accept()
                    // Served one at a time: requests are one short line.
                    runCatching { client.use(::serve) }
                }
            } catch (_: IOException) {
                // Closed by stop().
            }
        }, "interchange-control").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { channel?.close() }
        channel = null
        runCatching { Files.deleteIfExists(socketFile.toPath()) }
    }

    private fun serve(client: SocketChannel) {
        val line = BufferedReader(Channels.newReader(client, Charsets.UTF_8)).readLine() ?: return
        val reply = try {
            dispatch(line)
        } catch (e: IllegalArgumentException) {
            error(e.message ?: "bad request")
        } catch (e: Exception) {
            error(e.message ?: e::class.simpleName ?: "failed")
        }
        val writer = Channels.newWriter(client, Charsets.UTF_8)
        writer.write(reply)
        writer.write("\n")
        writer.flush()
    }

    private fun dispatch(line: String): String {
        val parts = line.split('\t').map(::unescape)
        val arg = parts.getOrNull(1).orEmpty()
        return when (val command = parts.first().lowercase()) {
            "status" -> ok(handler.status())
            "players" -> ok(mapOf("players" to list(handler.players())))
            "companies" -> ok(mapOf("companies" to list(handler.companies())))
            "log" -> ok(mapOf("lines" to JsonArray(handler.logLines(arg.toIntOrNull() ?: 100).map(::JsonPrimitive))))
            "pause", "resume" -> {
                handler.pause(command == "pause")
                ok(mapOf("paused" to JsonPrimitive(command == "pause")))
            }
            "speed" -> {
                val speed = arg.toIntOrNull()
                require(speed != null && speed in SPEEDS) { "speed is one of ${SPEEDS.joinToString()}" }
                handler.speed(speed)
                ok(mapOf("speed" to JsonPrimitive(speed)))
            }
            "chat" -> {
                require(arg.isNotBlank()) { "chat needs something to say" }
                handler.say(arg)
                ok(mapOf("sent" to JsonPrimitive(true)))
            }
            "kick" -> {
                require(arg.isNotBlank()) { "kick needs a player's name" }
                require(handler.kick(arg)) { "nobody called \"$arg\" is playing" }
                ok(mapOf("kicked" to JsonPrimitive(arg)))
            }
            "unlock" -> {
                val company = arg.toIntOrNull() ?: throw IllegalArgumentException("unlock needs a company number")
                require(handler.unlock(company)) { "company $company has no password" }
                ok(mapOf("unlocked" to JsonPrimitive(company)))
            }
            "save" -> ok(handler.save())
            "newgame" -> {
                val settings = parts.drop(1).filter { it.isNotBlank() }.associate {
                    val (key, value) = it.split('=', limit = 2).takeIf { kv -> kv.size == 2 }
                        ?: throw IllegalArgumentException("newgame takes key=value, not \"$it\"")
                    key.trim() to value.trim()
                }
                ok(handler.newGame(settings))
            }
            "" -> error("empty request")
            else -> error("unknown command \"$command\"")
        }
    }

    private fun list(items: List<Map<String, JsonElement>>) = JsonArray(items.map(::JsonObject))

    private fun ok(fields: Map<String, JsonElement>) = JsonObject(mapOf("ok" to JsonPrimitive(true)) + fields).toString()

    private fun error(message: String) = JsonObject(mapOf("ok" to JsonPrimitive(false), "error" to JsonPrimitive(message))).toString()

    private fun unescape(value: String): String = buildString(value.length) {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                i++
                append(when (value[i]) { 't' -> '\t'; 'n' -> '\n'; else -> value[i] })
            } else {
                append(c)
            }
            i++
        }
    }

    companion object {
        /** The speeds the app offers, ticks run for each one at normal speed. */
        val SPEEDS = listOf(1, 2, 4, 8)
    }
}
