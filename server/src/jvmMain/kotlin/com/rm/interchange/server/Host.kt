package com.rm.interchange.server

import com.rm.interchange.net.Beacon
import com.rm.interchange.net.CompanyInfo
import com.rm.interchange.net.Connection
import com.rm.interchange.net.LanDiscovery
import com.rm.interchange.net.Listener
import com.rm.interchange.net.Message
import com.rm.interchange.net.Player
import com.rm.interchange.net.Reject
import com.rm.interchange.net.Wire
import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.MAX_COMPANIES
import com.rm.interchange.sim.World
import com.rm.interchange.sim.human
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Hosts the game [runner] plays for players on the network: lets them in,
 * gives each a company, sends them the world, then every command and frame.
 * A phone hosting plays [hostCompany] itself as [hostName]; a dedicated server
 * plays none (-1). Several players may share a company, and a company may
 * have a password, needed to join it unless it's the one you played last
 * since the password was set.
 *
 * Network threads only read and pass things on; everything touching the world
 * runs on the game thread.
 */
class Host(
    private val runner: GameThread,
    /** The game's name, as players see it in the list. */
    val name: String,
    port: Int = LanDiscovery.GAME_PORT,
    /** Needed to join, or empty for none. */
    private val password: String = "",
    private val hostName: String = "",
    private val hostCompany: Int = 0,
    /** Players' companies nobody has played yet, given out first to new players. */
    free: List<Int> = emptyList(),
    /** The company each client last played, from before the server was last stopped. */
    claimed: Map<String, Int> = emptyMap(),
    /** Each company's password, as [hash] makes it, from before the server was last stopped. */
    passwords: Map<Int, String> = emptyMap(),
    /** Whether to announce the game on the local network. */
    private val announce: Boolean = true,
) {
    private class Session(val connection: Connection) {
        @Volatile var name = ""
        @Volatile var client = ""
        @Volatile var company = -1
        /** Sent the world and getting every frame; only changed on the game thread. */
        @Volatile var joined = false
        @Volatile var helloed = false
    }

    private val sessions = CopyOnWriteArrayList<Session>()
    /** The company each client last played, so they get it back on rejoining. */
    private val claims = ConcurrentHashMap(claimed)

    /** The company each client last played, to keep for next time. */
    val claimsNow: Map<String, Int> get() = HashMap(claims)

    /** Each company's password hashed, kept here and never in the world, so it isn't in saves or checksums. */
    private val passwords = ConcurrentHashMap(passwords)

    /** Each company's password hashed, to keep for next time. */
    val passwordsNow: Map<Int, String> get() = HashMap(passwords)

    /** The companies players can play, as last sent to everyone. */
    @Volatile var companies: List<CompanyInfo> = emptyList()
        private set
    private val free = ArrayList(free)
    /** Slots promised to new companies whose commands haven't run yet, with the frame each runs in. */
    private val promised = HashMap<Int, Long>()
    /** The last frame a company is renamed in, so the companies are sent again once it's run; -1 if none. Game thread only. */
    private var renamedIn = -1L
    private val listener = Listener(port, ::connected)
    private val id = UUID.randomUUID().toString()
    @Volatile private var closed = false

    /** Something to say about what's happening, for the log. */
    var log: (String) -> Unit = {}

    val port get() = listener.port

    /** Who's playing now, the host first. */
    val players: List<Player>
        get() = (if (hostCompany >= 0 || hostName.isNotEmpty()) listOf(Player(hostName, hostCompany)) else emptyList()) +
            sessions.filter { it.joined }.map { Player(it.name, it.company) }

    @Volatile var chat: List<ChatLine> = emptyList()
        private set

    fun start() {
        runner.onGameThread { g -> g.broadcast = ::broadcast }
        sendPlayers()
        listener.start()
        if (announce) LanDiscovery.announce({ closed }) {
            val w = runner.game?.world
            Beacon(name, port, players.size, Wire.PROTOCOL, password.isNotEmpty(), id,
                w?.let { Calendar.year(it.startYear, runner.day) } ?: 0, w?.width ?: 0)
        }
        log("Hosting $name on port $port")
    }

    fun stop() {
        closed = true
        listener.close()
        for (s in sessions) s.connection.close("server closed")
        runner.onGameThread { g -> g.broadcast = null }
    }

    /** Says [text] to everyone, from the host, or as [from] with no company. */
    fun say(text: String, from: String? = null) {
        tell(if (from != null) Message.Chat(from, -1, text.take(CHAT_MOST)) else Message.Chat(hostName, hostCompany, text.take(CHAT_MOST)))
    }

    /** Keeps [m] and sends it to everyone in, in the same order for all. */
    @Synchronized private fun tell(m: Message.Chat) {
        chat = (chat + ChatLine(m.name, m.company, m.text)).takeLast(Remote.CHAT_KEPT)
        for (s in sessions) if (s.joined) s.connection.send(m)
    }

    /** Sets the password for [company], or clears it if [password] is empty. */
    fun lock(company: Int, password: String) {
        if (company < 0) return
        if (password.isEmpty()) passwords.remove(company)
        else {
            passwords[company] = hash(password)
            // Only those on the company now keep coming back to it without the new password.
            val on = sessions.filter { it.joined && it.company == company }.map { it.client }.toSet()
            claims.entries.removeAll { it.value == company && it.key !in on }
        }
        log("The password for company $company was ${if (password.isEmpty()) "cleared" else "set"}")
        sendPlayers()
    }

    /** Sends the player called [name] away; true if there was one. */
    fun kick(name: String): Boolean {
        val s = sessions.firstOrNull { it.joined && it.name == name } ?: return false
        s.connection.send(Message.Rejected(Reject.KICKED))
        s.connection.close("kicked")
        return true
    }

    private fun connected(c: Connection) {
        if (closed || sessions.size >= MAX_CONNECTIONS) return c.close("full")
        val s = Session(c)
        c.onMessage = { m -> take(s, m) }
        c.onClosed = { why -> left(s, why) }
        sessions += s
        c.start()
    }

    private fun take(s: Session, m: Message) {
        when (m) {
            is Message.Hello -> if (!s.helloed) {
                s.helloed = true
                runner.onGameThread { g -> admit(s, m, g) }
            }
            is Message.Look -> if (!s.helloed) {
                s.helloed = true
                runner.onGameThread { g ->
                    if (m.protocol != Wire.PROTOCOL) refuse(s, Reject.VERSION)
                    else {
                        s.connection.send(Message.Players(players, companiesOf(g.world)))
                        s.connection.close("looked")
                    }
                }
            }
            is Message.Lock -> if (s.joined && s.company >= 0) lock(s.company, m.password.take(PASSWORD_MOST))
            is Message.Request -> {
                val c = m.command
                if (s.joined && s.company >= 0 && c.company == s.company && c !is Command.NewCompany) runner.request(c)
            }
            is Message.Say -> if (s.joined) tell(Message.Chat(s.name, s.company, m.text.take(CHAT_MOST)))
            Message.Resync -> if (s.joined) runner.onGameThread { g ->
                log("${s.name} went out of step at frame ${g.frame}, sending the world again")
                welcome(s, g)
            }
            else -> {}
        }
    }

    /** On the game thread: lets [s] in if it may come, with a company. */
    private fun admit(s: Session, hello: Message.Hello, g: Game) {
        val w = g.world
        val reason = when {
            hello.protocol != Wire.PROTOCOL -> Reject.VERSION
            password.isNotEmpty() && hello.password != password -> Reject.PASSWORD
            sessions.count { it.joined } >= MAX_PLAYERS -> Reject.FULL
            else -> 0
        }
        if (reason != 0) return refuse(s, reason)
        s.name = hello.name.take(NAME_MOST).ifBlank { "Player" }
        s.client = hello.client
        promised.values.removeAll { it < g.frame }
        val company = when {
            hello.join == Message.SPECTATE -> -1
            hello.join >= 0 -> when {
                !playable(w, hello.join) && hello.join !in promised -> return refuse(s, Reject.NO_COMPANY)
                // The company played last is theirs to come back to; any other needs its password.
                claims[hello.client] != hello.join && !opens(hello.join, hello.companyPassword) -> return refuse(s, Reject.COMPANY_PASSWORD)
                else -> hello.join
            }
            else -> {
                free.removeAll { !playable(w, it) }
                (free.removeFirstOrNull() ?: newSlot(w).let { slot ->
                    if (slot < 0) return refuse(s, Reject.FULL)
                    promised[slot] = g.frame + Game.DELAY
                    g.request(Command.NewCompany(slot, ""))
                    slot
                }).also { forget(it, g) }
            }
        }
        s.company = company
        if (company >= 0) claims[hello.client] = company
        welcome(s, g)
        s.joined = true
        log("${s.name} joined from ${s.connection.address}, playing company $company")
        sendPlayers()
    }

    /**
     * On the game thread: a slot given to a new company starts clean, with no password, no claims, and anyone
     * still on the company that went under there moved to watching.
     */
    private fun forget(slot: Int, g: Game) {
        passwords.remove(slot)
        claims.values.removeAll { it == slot }
        for (o in sessions) if (o.joined && o.company == slot) {
            o.company = -1
            welcome(o, g)
            log("${o.name} is watching now, as company $slot went under")
        }
    }

    private fun refuse(s: Session, reason: Int) {
        log("Turned away ${s.connection.address}: $reason")
        s.connection.send(Message.Rejected(reason))
        s.connection.close("refused")
    }

    /** Whether company [id] is one a player may play: a player's and still going. */
    private fun playable(w: World, id: Int) = w.human(id) && !w.companies[id].gone

    /** Whether [password] lets someone into [company]: right, or none needed. */
    private fun opens(company: Int, password: String) = passwords[company]?.let { it == hash(password) } ?: true

    /** The companies players can play, with those promised to new players that haven't started yet. */
    private fun companiesOf(w: World): List<CompanyInfo> {
        val joined = sessions.filter { it.joined }
        val ids = (w.companies.filter { playable(w, it.id) }.map { it.id } + promised.keys.filter { !playable(w, it) }).sorted()
        return ids.map { id ->
            val name = w.companies.getOrNull(id)?.takeIf { !it.gone }?.name ?: joined.firstOrNull { it.company == id }?.name ?: ""
            val on = joined.count { it.company == id } + if (id == hostCompany) 1 else 0
            CompanyInfo(id, name, on, passwords.containsKey(id))
        }
    }

    /** A slot for a new company: one gone under, or the next, and not promised; -1 if there's none. */
    private fun newSlot(w: World): Int {
        for (c in w.companies) if (c.gone && c.id !in promised && w.human(c.id)) return c.id
        var next = w.companies.size
        while (next in promised) next++
        return if (next < MAX_COMPANIES) next else -1
    }

    private fun welcome(s: Session, g: Game) {
        val (save, pending) = g.joining()
        s.connection.send(Message.Welcome(s.company, name, g.frame, save, pending))
    }

    /** On the game thread: sends [m] to everyone in. A player far behind on reading is let go. */
    private fun broadcast(m: Message) {
        if (m is Message.Scheduled && m.command is Command.RenameCompany) renamedIn = maxOf(renamedIn, m.frame)
        if (m is Message.Frame && renamedIn in 0..m.frame) {
            renamedIn = -1
            sendPlayers()
        }
        for (s in sessions) {
            if (!s.joined) continue
            if (s.connection.backlog > MAX_BACKLOG) {
                s.connection.close("too far behind")
                continue
            }
            s.connection.send(m)
        }
    }

    private fun left(s: Session, why: String) {
        sessions.remove(s)
        if (s.joined) {
            log("${s.name} left: $why")
            sendPlayers()
        }
    }

    /** Sends who's playing and the companies to everyone in, from the game thread where the world can be read. */
    private fun sendPlayers() = runner.onGameThread { g ->
        val m = Message.Players(players, companiesOf(g.world))
        companies = m.companies
        for (s in sessions) if (s.joined) s.connection.send(m)
    }

    companion object {
        const val MAX_PLAYERS = 16
        /** Connections at once, joined or not. */
        const val MAX_CONNECTIONS = 32
        const val NAME_MOST = 24
        const val CHAT_MOST = 300
        const val PASSWORD_MOST = 64

        /** A password as it's kept: hashed, so the dedicated server's state folder doesn't hold it as typed. */
        fun hash(password: String): String =
            MessageDigest.getInstance("SHA-256").digest(("interchange:" + password).toByteArray()).joinToString("") { "%02x".format(it) }
        /** Bytes waiting to go to a player before they're let go. */
        const val MAX_BACKLOG = 64L shl 20
    }
}
