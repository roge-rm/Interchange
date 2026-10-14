package com.rm.interchange.server

import com.rm.interchange.net.Connection
import com.rm.interchange.net.Message
import com.rm.interchange.net.Player
import com.rm.interchange.net.Wire
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A server wouldn't let us in, for a [com.rm.interchange.net.Reject] reason. */
class Refused(val reason: Int) : IOException("refused: $reason")

/** A line of chat: who said it, the company they play (-1 for none) and what they said. */
data class ChatLine(val name: String, val company: Int, val text: String)

/**
 * A game hosted somewhere else, as one player sees it. Made by [join], which
 * waits until it's let in; then hand it to a [GameThread] to follow the game.
 */
class Remote private constructor(private val connection: Connection) {
    /** Messages for the game thread, in the order they came. */
    val inbox = ConcurrentLinkedQueue<Message>()

    /** The first welcome: the world as it was when we joined. */
    @Volatile var welcome: Message.Welcome? = null
        private set

    /** The company we play, -1 watching, and the server's name. */
    val company get() = welcome?.company ?: -1
    val server get() = welcome?.server ?: ""

    @Volatile var players: List<Player> = emptyList()
        private set
    @Volatile var chat: List<ChatLine> = emptyList()
        private set

    /** Why the connection ended, or null while it's up. */
    @Volatile var lost: String? = null
        private set

    @Volatile private var refused: Int = 0
    private val answered = CountDownLatch(1)

    fun send(m: Message) = connection.send(m)

    fun say(text: String) = send(Message.Say(text))

    fun leave() = connection.close("left")

    private fun take(m: Message) {
        when (m) {
            is Message.Welcome -> {
                if (welcome == null) {
                    welcome = m
                    answered.countDown()
                } else {
                    inbox.add(m)
                }
            }
            is Message.Rejected -> {
                refused = m.reason
                answered.countDown()
            }
            is Message.Chat -> chat = (chat + ChatLine(m.name, m.company, m.text)).takeLast(CHAT_KEPT)
            is Message.Players -> players = m.players
            else -> inbox.add(m)
        }
    }

    companion object {
        /** Lines of chat kept. */
        const val CHAT_KEPT = 100

        /**
         * Connects to [host] on [port] and asks to join as [hello] says, waiting up to [timeoutMillis] to be let in.
         * Throws [Refused] if we're turned away, or [IOException] if the server can't be reached.
         */
        fun join(host: String, port: Int, hello: Message.Hello, timeoutMillis: Long = 60_000): Remote {
            val c = Connection.connect(host, port)
            val r = Remote(c)
            c.onMessage = r::take
            c.onClosed = { why ->
                r.lost = why
                r.answered.countDown()
            }
            c.start()
            c.send(hello.copy(protocol = Wire.PROTOCOL))
            if (!r.answered.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                c.close("timed out")
                throw IOException("timed out")
            }
            if (r.refused != 0) {
                c.close("refused")
                throw Refused(r.refused)
            }
            if (r.welcome == null) throw IOException(r.lost ?: "closed")
            return r
        }
    }
}
