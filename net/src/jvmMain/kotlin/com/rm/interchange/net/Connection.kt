package com.rm.interchange.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A TCP connection carrying [Message]s, each sent as its length in four bytes
 * then its bytes. A thread reads and hands each message to [onMessage]; another
 * writes, so [send] never blocks on a slow network. [onClosed] is called once,
 * from either thread, when the connection ends for any reason.
 */
class Connection(private val socket: Socket) {
    var onMessage: (Message) -> Unit = {}
    var onClosed: (String) -> Unit = {}

    val address: String = socket.inetAddress?.hostAddress ?: ""

    private val out = LinkedBlockingQueue<ByteArray>()
    private val closed = AtomicBoolean(false)

    /** Bytes waiting to go out, to spot a player who's stopped reading. */
    val backlog: Long get() = waiting.get()
    private val waiting = AtomicLong()

    /** Starts the reading and writing threads; set [onMessage] and [onClosed] first. */
    fun start() {
        socket.tcpNoDelay = true
        Thread(::read, "interchange-read-$address").apply { isDaemon = true }.start()
        Thread(::write, "interchange-write-$address").apply { isDaemon = true }.start()
    }

    fun send(m: Message) {
        if (closed.get()) return
        val b = Wire.encode(m)
        waiting.addAndGet(b.size.toLong())
        out.add(b)
    }

    /** Sends what's waiting, then closes. */
    fun close(why: String = "closed") {
        if (closed.get()) return
        out.add(CLOSE)
        Thread {
            Thread.sleep(2_000)
            end(why)
        }.apply { isDaemon = true }.start()
    }

    private fun read() {
        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 1 shl 16))
            while (!closed.get()) {
                val n = input.readInt()
                if (n < 1 || n > MAX_MESSAGE) throw IOException("a message of $n bytes")
                val b = ByteArray(n)
                input.readFully(b)
                onMessage(Wire.decode(b))
            }
        } catch (e: Exception) {
            end(e.message ?: e.toString())
        }
    }

    private fun write() {
        try {
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 1 shl 16))
            while (!closed.get()) {
                val b = out.take()
                if (b === CLOSE) {
                    output.flush()
                    end("closed")
                    return
                }
                output.writeInt(b.size)
                output.write(b)
                waiting.addAndGet(-b.size.toLong())
                if (out.isEmpty()) output.flush()
            }
        } catch (e: InterruptedException) {
            end("closed")
        } catch (e: Exception) {
            end(e.message ?: e.toString())
        }
    }

    private fun end(why: String) {
        if (!closed.compareAndSet(false, true)) return
        out.clear()
        out.add(CLOSE)
        try { socket.close() } catch (_: IOException) {}
        onClosed(why)
    }

    companion object {
        /** The biggest message, a save of the biggest map with room to spare. */
        const val MAX_MESSAGE = 512 shl 20
        private val CLOSE = ByteArray(0)

        /** Connects to [host] on [port], or throws [IOException]. */
        fun connect(host: String, port: Int, timeoutMillis: Int = 10_000): Connection {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMillis)
            return Connection(s)
        }
    }
}

/** Listens on [port] (0 for any) and hands each player who connects to [onConnection], on its own thread. */
class Listener(port: Int, private val onConnection: (Connection) -> Unit) {
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(port))
    }
    val port: Int get() = server.localPort

    private val thread = Thread({
        while (!server.isClosed) {
            val s = try { server.accept() } catch (_: IOException) { break }
            onConnection(Connection(s))
        }
    }, "interchange-listen").apply { isDaemon = true }

    fun start() = thread.start()

    fun close() {
        try { server.close() } catch (_: IOException) {}
    }
}
