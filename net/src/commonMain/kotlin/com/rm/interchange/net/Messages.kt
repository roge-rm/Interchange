package com.rm.interchange.net

import com.rm.interchange.sim.BadData
import com.rm.interchange.sim.ByteReader
import com.rm.interchange.sim.ByteWriter
import com.rm.interchange.sim.Codec
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.Insignia
import com.rm.interchange.sim.PackRef

/**
 * Lockstep, as OpenTTD does it: players send commands to the server, the
 * server stamps each with the frame it runs on and sends it to everyone, and
 * every copy of the world runs it then. Each frame the server says how many
 * ticks it ran, none while paused, so every copy keeps the same pace. Only
 * commands cross the network, never the world, except when a player joins or
 * has gone out of step.
 */
sealed interface Message {
    /**
     * Player to server, first: who they are and what they'd like to play. [client] is made once per install and
     * picks the same company again on rejoining; [join] is [NEW_COMPANY], [SPECTATE] or a company to share, which
     * needs its [companyPassword] if it has one.
     */
    data class Hello(
        val protocol: Int, val client: String, val name: String, val password: String, val join: Int,
        val companyPassword: String = "",
    ) : Message

    /** Player to server, instead of [Hello]: who's playing what? Answered with [Players], then the server closes. */
    data class Look(val protocol: Int) : Message

    /**
     * Player to server, instead of [Hello]: send me pack [ref], one the game uses. Answered with [PackPart]s, or
     * [Rejected] if it's not one the server will send. Several may be asked for on one connection, one at a time.
     */
    data class PackGet(val protocol: Int, val ref: PackRef) : Message

    /** Server to player: [bytes] of pack [ref]'s zip, from [offset], of [total] bytes in all. */
    class PackPart(val ref: PackRef, val offset: Int, val total: Int, val bytes: ByteArray) : Message

    /** Player to server: set the password for the company I play, or clear it with an empty one. */
    data class Lock(val password: String) : Message

    /** Player to server: run this, please. */
    data class Request(val command: Command) : Message

    /** Player to server: something to say to everyone. */
    data class Say(val text: String) : Message

    /** Player to server: my world has gone out of step, send it again. */
    data object Resync : Message

    /**
     * Server to player: you're in, playing [company] (-1 watching), in [server]'s game. [save] is the world as of the
     * start of [frame], and [pending] the commands already set for that frame and after.
     */
    class Welcome(val company: Int, val server: String, val frame: Long, val save: ByteArray, val pending: List<Scheduled>) : Message

    /** Server to player: not let in, for a [Reject] reason. The server closes the connection after. */
    data class Rejected(val reason: Int) : Message

    /** Server to everyone: run [command] in [frame], before its ticks. */
    data class Scheduled(val frame: Long, val command: Command) : Message

    /**
     * Server to everyone: run frame [frame], its commands then [ticks] ticks. [checksum] is the server's world after
     * it, now and then, to catch a copy gone out of step; 0 when not sent.
     */
    data class Frame(val frame: Long, val ticks: Int, val checksum: Long = 0) : Message

    /** Server to everyone: [name], playing [company] (-1 for the server or someone watching), said [text]. */
    data class Chat(val name: String, val company: Int, val text: String) : Message

    /**
     * Server to everyone: who's playing now, and the companies players can play. Answering a [Look] it also has
     * [packs], the add-on packs the game uses, to fetch before joining.
     */
    data class Players(val players: List<Player>, val companies: List<CompanyInfo> = emptyList(), val packs: List<PackRef> = emptyList()) : Message

    companion object {
        /** What [Hello.join] asks for besides a company to share. */
        const val NEW_COMPANY = -1
        const val SPECTATE = -2
    }
}

/** Someone in the game: their [name] and the [company] they play, -1 watching. */
data class Player(val name: String, val company: Int)

/**
 * A company players can play: its [id], [name], how many [players] are on it, whether it needs a password, and its
 * [insignia], empty for one not started yet.
 */
data class CompanyInfo(val id: Int, val name: String, val players: Int, val locked: Boolean, val insignia: ByteArray = ByteArray(0)) {
    override fun equals(other: Any?) = other is CompanyInfo && other.id == id && other.name == name && other.players == players &&
        other.locked == locked && other.insignia.contentEquals(insignia)
    override fun hashCode() = ((id * 31 + name.hashCode()) * 31 + players) * 31 + insignia.contentHashCode()
}

/** Why a player wasn't let in. */
object Reject {
    /** A different version of the game. */
    const val VERSION = 1
    /** A password is needed, or the one given was wrong. */
    const val PASSWORD = 2
    /** No room for another company or player. */
    const val FULL = 3
    /** The company asked for isn't a player's. */
    const val NO_COMPANY = 4
    /** The server sent them away. */
    const val KICKED = 5
    /** The company asked for needs a password, or the one given was wrong. */
    const val COMPANY_PASSWORD = 6
    /** The pack asked for isn't one this game uses, or the server hasn't got it. */
    const val NO_PACK = 7
}

/**
 * Messages as bytes. Each starts with its kind; commands are written by the
 * sim's [Codec]. [PROTOCOL] goes up whenever a message or command changes,
 * as every copy of the game in a session has to run the same sim.
 */
object Wire {
    /** The version of the protocol and the sim together; players and servers must match. */
    const val PROTOCOL = 41

    private const val HELLO = 1
    private const val REQUEST = 2
    private const val SAY = 3
    private const val RESYNC = 4
    private const val WELCOME = 5
    private const val REJECTED = 6
    private const val SCHEDULED = 7
    private const val FRAME = 8
    private const val CHAT = 9
    private const val PLAYERS = 10
    private const val LOOK = 11
    private const val LOCK = 12
    private const val PACK_GET = 13
    private const val PACK_PART = 14

    fun encode(m: Message): ByteArray {
        val w = ByteWriter(when (m) {
            is Message.Welcome -> m.save.size + 256
            is Message.PackPart -> m.bytes.size + 128
            else -> 64
        })
        when (m) {
            is Message.Hello -> { w.byte(HELLO); w.int(m.protocol); w.string(m.client); w.string(m.name); w.string(m.password); w.int(m.join); w.string(m.companyPassword) }
            is Message.Look -> { w.byte(LOOK); w.int(m.protocol) }
            is Message.Lock -> { w.byte(LOCK); w.string(m.password) }
            is Message.PackGet -> { w.byte(PACK_GET); w.int(m.protocol); ref(w, m.ref) }
            is Message.PackPart -> { w.byte(PACK_PART); ref(w, m.ref); w.int(m.offset); w.int(m.total); w.bytes(m.bytes) }
            is Message.Request -> { w.byte(REQUEST); Codec.write(w, m.command) }
            is Message.Say -> { w.byte(SAY); w.string(m.text) }
            Message.Resync -> w.byte(RESYNC)
            is Message.Welcome -> {
                w.byte(WELCOME); w.int(m.company); w.string(m.server); w.long(m.frame); w.bytes(m.save)
                w.int(m.pending.size)
                for (s in m.pending) { w.long(s.frame); Codec.write(w, s.command) }
            }
            is Message.Rejected -> { w.byte(REJECTED); w.int(m.reason) }
            is Message.Scheduled -> { w.byte(SCHEDULED); w.long(m.frame); Codec.write(w, m.command) }
            is Message.Frame -> { w.byte(FRAME); w.long(m.frame); w.int(m.ticks); w.long(m.checksum) }
            is Message.Chat -> { w.byte(CHAT); w.string(m.name); w.int(m.company); w.string(m.text) }
            is Message.Players -> {
                w.byte(PLAYERS); w.int(m.players.size)
                for (p in m.players) { w.string(p.name); w.int(p.company) }
                w.int(m.companies.size)
                for (c in m.companies) { w.int(c.id); w.string(c.name); w.int(c.players); w.bool(c.locked); w.bytes(c.insignia) }
                w.int(m.packs.size)
                for (p in m.packs) ref(w, p)
            }
        }
        return w.toByteArray()
    }

    /** Reads a message; throws [BadData] if it isn't one. */
    fun decode(b: ByteArray): Message {
        val r = ByteReader(b)
        return when (val kind = r.byte()) {
            HELLO -> {
                // Read no further on another protocol, so the server can say it's another version.
                val protocol = r.int()
                if (protocol != PROTOCOL) Message.Hello(protocol, "", "", "", Message.SPECTATE)
                else Message.Hello(protocol, r.string(), r.string(), r.string(), r.int(), r.string())
            }
            LOOK -> Message.Look(r.int())
            LOCK -> Message.Lock(r.string())
            PACK_GET -> {
                val protocol = r.int()
                Message.PackGet(protocol, if (protocol != PROTOCOL) PackRef("", 0, 0) else ref(r))
            }
            PACK_PART -> Message.PackPart(ref(r), r.int(), r.int(), r.bytes())
            REQUEST -> Message.Request(Codec.read(r))
            SAY -> Message.Say(r.string())
            RESYNC -> Message.Resync
            WELCOME -> {
                val company = r.int(); val server = r.string(); val frame = r.long(); val save = r.bytes()
                val n = r.int().also { if (it !in 0..1_000_000) throw BadData("too many commands") }
                Message.Welcome(company, server, frame, save, List(n) { Message.Scheduled(r.long(), Codec.read(r)) })
            }
            REJECTED -> Message.Rejected(r.int())
            SCHEDULED -> Message.Scheduled(r.long(), Codec.read(r))
            FRAME -> Message.Frame(r.long(), r.int(), r.long())
            CHAT -> Message.Chat(r.string(), r.int(), r.string())
            PLAYERS -> {
                val n = r.int().also { if (it !in 0..256) throw BadData("too many players") }
                val players = List(n) { Player(r.string(), r.int()) }
                val k = r.int().also { if (it !in 0..256) throw BadData("too many companies") }
                val companies = List(k) {
                    CompanyInfo(r.int(), r.string(), r.int(), r.bool(), r.bytes().also { if (it.isNotEmpty() && !Insignia.valid(it)) throw BadData("a bad insignia") })
                }
                val packs = List(r.int().also { if (it !in 0..256) throw BadData("too many packs") }) { ref(r) }
                Message.Players(players, companies, packs)
            }
            else -> throw BadData("no such message: $kind")
        }
    }

    private fun ref(w: ByteWriter, ref: PackRef) { w.string(ref.id); w.int(ref.version); w.long(ref.hash) }

    private fun ref(r: ByteReader) = PackRef(r.string(), r.int(), r.long())
}
