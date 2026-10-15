package com.rm.interchange.server

import com.rm.interchange.net.CompanyInfo
import com.rm.interchange.net.Connection
import com.rm.interchange.net.Message
import com.rm.interchange.net.Player
import com.rm.interchange.net.Reject
import com.rm.interchange.net.Wire
import com.rm.interchange.sim.BadData
import com.rm.interchange.sim.PackRef
import com.rm.interchange.sim.Packs
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** A server wouldn't let us in, for a [com.rm.interchange.net.Reject] reason. */
class Refused(val reason: Int) : IOException("refused: $reason")

/** A download was stopped part way, as asked. */
class Cancelled : IOException("cancelled")

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

    /** The company we play, -1 watching, as the latest welcome says; the server may move us to watching. */
    @Volatile var company = -1
        private set
    /** The server's name. */
    val server get() = welcome?.server ?: ""

    @Volatile var players: List<Player> = emptyList()
        private set
    /** The companies players can play, with how many are on each and which need a password. */
    @Volatile var companies: List<CompanyInfo> = emptyList()
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

    /** Sets the password for the company we play, or clears it with an empty one. */
    fun lock(password: String) = send(Message.Lock(password))

    fun leave() = connection.close("left")

    private fun take(m: Message) {
        when (m) {
            is Message.Welcome -> {
                company = m.company
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
            is Message.Players -> { players = m.players; companies = m.companies }
            else -> inbox.add(m)
        }
    }

    companion object {
        /** Lines of chat kept. */
        const val CHAT_KEPT = 100

        /**
         * Asks the game at [host] on [port] who's playing what, without joining, for picking a company to join.
         * Throws [Refused] if it's another version, or [IOException] if the server can't be reached.
         */
        fun look(host: String, port: Int, timeoutMillis: Long = 15_000): Message.Players {
            val c = Connection.connect(host, port)
            val answer = CompletableFuture<Message>()
            c.onMessage = { m -> answer.complete(m) }
            c.onClosed = { why -> answer.completeExceptionally(IOException(why)) }
            c.start()
            c.send(Message.Look(Wire.PROTOCOL))
            try {
                return when (val m = answer.get(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    is Message.Players -> m
                    is Message.Rejected -> throw Refused(m.reason)
                    else -> throw Refused(Reject.VERSION)
                }
            } catch (e: TimeoutException) {
                throw IOException("timed out")
            } catch (e: ExecutionException) {
                throw e.cause as? IOException ?: IOException(e.cause)
            } finally {
                c.close("looked")
            }
        }

        /**
         * Downloads packs [refs] from the game at [host] on [port] without joining, each checked to be the very one
         * asked for, and gives back their zips. [progress] hears each pack's bytes so far of its total; [cancelled]
         * stops it between parts. Throws [Refused] ([Reject.NO_PACK] for a pack the server won't send),
         * [IOException] if the server can't be reached or stops sending, [Cancelled], or [BadData] if what came
         * isn't the pack asked for.
         */
        fun fetchPacks(
            host: String, port: Int, refs: List<PackRef>,
            progress: (PackRef, Int, Int) -> Unit = { _, _, _ -> }, cancelled: () -> Boolean = { false }, timeoutMillis: Long = 30_000,
        ): Map<PackRef, ByteArray> {
            val c = Connection.connect(host, port)
            val inbox = LinkedBlockingQueue<Message>()
            val lost = AtomicReference<String?>(null)
            c.onMessage = { inbox.add(it) }
            c.onClosed = { why -> lost.set(why) }
            c.start()
            try {
                val got = LinkedHashMap<PackRef, ByteArray>()
                for (ref in refs) {
                    c.send(Message.PackGet(Wire.PROTOCOL, ref))
                    var zip: ByteArray? = null
                    var at = 0
                    var waited = 0L
                    while (zip == null || at < zip.size) {
                        if (cancelled()) throw Cancelled()
                        val m = inbox.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)
                        if (m == null) {
                            // Anything already sent is read before giving up on a closed connection.
                            lost.get()?.let { throw IOException(it) }
                            waited += POLL_MILLIS
                            if (waited > timeoutMillis) throw IOException("timed out")
                            continue
                        }
                        waited = 0
                        when (m) {
                            is Message.Rejected -> throw Refused(m.reason)
                            is Message.PackPart -> {
                                if (m.ref != ref || m.total !in 0..Packs.MAX_TOTAL || m.offset != at || (zip != null && m.total != zip.size) ||
                                    m.bytes.size > m.total - at || (m.bytes.isEmpty() && m.total > 0)
                                ) throw BadData("a broken download of ${ref.id}")
                                if (zip == null) zip = ByteArray(m.total)
                                val into = zip
                                m.bytes.copyInto(into, at)
                                at += m.bytes.size
                                progress(ref, at, into.size)
                            }
                            else -> {}
                        }
                    }
                    val bytes = zip ?: ByteArray(0)
                    val pack = try {
                        Packs.unzip(bytes)
                    } catch (e: Exception) {
                        throw BadData("the ${ref.id} pack sent can't be read")
                    }
                    if (pack.ref != ref) throw BadData("the ${ref.id} pack sent isn't the one asked for")
                    got[ref] = bytes
                }
                return got
            } finally {
                c.close("fetched")
            }
        }

        private const val POLL_MILLIS = 250L

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
