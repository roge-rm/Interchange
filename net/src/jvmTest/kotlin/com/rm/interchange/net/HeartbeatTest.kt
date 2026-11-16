package com.rm.interchange.net

import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Pings keep a quiet connection up, and one that hears nothing ends. */
class HeartbeatTest {
    private val beat = Heartbeat(pingMillis = 50, timeoutMillis = 300)
    private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())

    @AfterTest
    fun close() = server.close()

    private fun accepted(): CompletableFuture<Socket> = CompletableFuture.supplyAsync { server.accept() }

    @Test
    fun aQuietConnectionStaysUpOnPings() {
        val other = accepted()
        val a = Connection.connect("127.0.0.1", server.localPort, beat = beat)
        val b = Connection(other.get(5, TimeUnit.SECONDS), beat)
        val heard = ArrayList<Message>()
        for (c in listOf(a, b)) c.onMessage = { synchronized(heard) { heard += it } }
        a.start(); b.start()
        Thread.sleep(1_000)
        assertFalse(a.isClosed)
        assertFalse(b.isClosed)
        // Pings aren't handed on.
        assertEquals(emptyList(), synchronized(heard) { heard.toList() })
        a.close(); b.close()
    }

    @Test
    fun aConnectionThatHearsNothingEnds() {
        val other = accepted()
        // A peer that says hello then goes silent, as a phone that lost its network would.
        val raw = Socket("127.0.0.1", server.localPort)
        val c = Connection(other.get(5, TimeUnit.SECONDS), beat)
        val why = CompletableFuture<String>()
        c.onClosed = { why.complete(it) }
        c.start()
        DataOutputStream(raw.getOutputStream()).apply {
            val b = Wire.encode(Message.Say("hi"))
            writeInt(b.size); write(b); flush()
        }
        assertEquals("timed out", why.get(5, TimeUnit.SECONDS))
        raw.close()
    }
}
