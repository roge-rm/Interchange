package com.rm.interchange.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.interchange.map.companyColour
import com.rm.interchange.net.Beacon
import com.rm.interchange.net.CompanyInfo
import com.rm.interchange.net.LanDiscovery
import com.rm.interchange.net.Message
import com.rm.interchange.net.Player
import com.rm.interchange.net.Reject
import com.rm.interchange.net.Wire
import com.rm.interchange.platform.Network
import com.rm.interchange.res.*
import com.rm.interchange.server.Cancelled
import com.rm.interchange.server.ChatLine
import com.rm.interchange.server.GameThread
import com.rm.interchange.server.Host
import com.rm.interchange.server.Refused
import com.rm.interchange.server.Remote
import com.rm.interchange.sim.BadData
import com.rm.interchange.sim.BadPack
import com.rm.interchange.sim.MissingPacks
import com.rm.interchange.sim.PackRef
import com.rm.interchange.sim.Packs
import com.rm.interchange.sim.Save
import com.rm.interchange.sim.World
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.util.concurrent.atomic.AtomicBoolean

/** A game heard nearby is dropped from the list this long after its last beacon. */
private const val HEARD_NANOS = 4_000_000_000L

/** A text box in the game's colours. */
@Composable
internal fun Field(value: String, label: String, onChange: (String) -> Unit, secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Text) {
    val c = LocalColors.current
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = c.text, unfocusedTextColor = c.text, focusedBorderColor = c.accent, unfocusedBorderColor = c.chromeEdge,
            focusedLabelColor = c.accent, unfocusedLabelColor = c.textDim, cursorColor = c.accent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** What came of trying to join: in, with the world read, or why not. */
private sealed interface Joining {
    class In(val remote: Remote, val world: World) : Joining
    class Turned(val reason: Int) : Joining
    class NeedsPacks(val packs: String) : Joining
    /** Let in, but missing [packs] the host can send; [company] is the one we were given, to come back to. */
    class Download(val company: Int, val packs: List<PackRef>) : Joining
    class Unreachable(val why: String) : Joining
}

/** A game looked at, to pick what to join as: where it is, its name, the companies in it and who's playing. */
private class Looked(val address: String, val port: Int, val name: String, val companies: List<CompanyInfo>, val players: List<Player>, val packs: List<PackRef>)

/** The address and port typed, "host" or "host:port". */
private fun parseAddress(text: String): Pair<String, Int> {
    val t = text.trim()
    val colon = t.lastIndexOf(':')
    // More than one colon is an IPv6 address without a port.
    if (colon > 0 && t.indexOf(':') == colon) t.substring(colon + 1).toIntOrNull()?.let { return t.substring(0, colon) to it }
    return t to LanDiscovery.GAME_PORT
}

/**
 * Joining a game on the network, from the title screen: your name, the games
 * heard nearby, or one by its address. Picking a game lists its companies, to
 * join one, start a new one or watch. A game or company that wants a password
 * asks for it once it's turned you away. Packs the game uses that you
 * haven't got are downloaded from the host, then it joins again. Joined, the
 * remote and the world it sent go to [onJoined].
 */
@Composable
fun MultiplayerWindow(settings: Settings, onJoined: (Remote, World) -> Unit, onClose: () -> Unit) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var looked by remember { mutableStateOf<Looked?>(null) }
    // What was last asked to join as, so a password goes with it.
    var choice by remember { mutableStateOf(Message.NEW_COMPANY) }
    var asked by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var askedCompany by remember { mutableStateOf(false) }
    var companyPassword by remember { mutableStateOf("") }
    // Set while packs are downloading, to stop them.
    var cancel by remember { mutableStateOf<AtomicBoolean?>(null) }
    // Closing the window stops a download, and the joining after it.
    DisposableEffect(Unit) { onDispose { cancel?.set(true) } }

    // Games heard, by their host's id, with when each was last heard.
    val heard = remember { mutableStateMapOf<String, Pair<Beacon, Long>>() }
    DisposableEffect(Unit) {
        val stop = AtomicBoolean(false)
        Network.listening(true)
        LanDiscovery.listen({ stop.get() }) { b -> scope.launch { heard[b.host] = b to System.nanoTime() } }
        onDispose {
            stop.set(true)
            Network.listening(false)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            val now = System.nanoTime()
            heard.filterValues { now - it.second > HEARD_NANOS }.keys.forEach { heard.remove(it) }
        }
    }

    val fallbackName = stringResource(Res.string.player)
    val needsPassword = stringResource(Res.string.needs_password)
    val needsCompanyPassword = stringResource(Res.string.needs_company_password)
    val wrongPassword = stringResource(Res.string.wrong_password)
    val otherVersion = stringResource(Res.string.refused_version)
    val full = stringResource(Res.string.refused_full)
    val noCompany = stringResource(Res.string.refused_company)
    val kicked = stringResource(Res.string.refused_kicked)
    val cantReach = stringResource(Res.string.cant_reach, "%s")
    val needsPacks = stringResource(Res.string.needs_packs, "%s")
    val cantLoad = stringResource(Res.string.cant_load, "%s")
    val lookingText = stringResource(Res.string.looking)
    val joiningText = stringResource(Res.string.joining)
    val downloading = stringResource(Res.string.downloading_packs, "%1", "%2")
    val notSent = stringResource(Res.string.pack_not_sent, "%s")
    val broken = stringResource(Res.string.pack_broken, "%s")

    fun where(address: String, port: Int) = if (port == LanDiscovery.GAME_PORT) address else "$address:$port"

    /** Asks the game what companies it has, to pick one. */
    fun look(address: String, port: Int, name: String) {
        if (busy != null || address.isBlank()) return
        settings.save()
        busy = lookingText
        error = null
        scope.launch {
            try {
                val seen = withContext(Dispatchers.IO) { runCatching { Remote.look(address, port) } }
                seen.onSuccess { m ->
                    if (looked?.let { it.address == address && it.port == port } != true) {
                        asked = false; password = ""; askedCompany = false; companyPassword = ""
                    }
                    looked = Looked(address, port, name, m.companies, m.players, m.packs)
                }.onFailure { e ->
                    error = if ((e as? Refused)?.reason == Reject.VERSION) otherVersion else cantReach.replace("%s", where(address, port))
                }
            } finally {
                busy = null
            }
        }
    }

    fun join(game: Looked, join: Int) {
        if (busy != null) return
        settings.save()
        if (join != choice) { askedCompany = false; companyPassword = "" }
        choice = join
        busy = joiningText
        error = null
        val hello = Message.Hello(Wire.PROTOCOL, settings.client, settings.playerName.trim().ifBlank { fallbackName }, password, join, companyPassword)
        fun names(packs: List<PackRef>) = packs.joinToString { p -> "${p.id} ${p.version}" }
        scope.launch {
            var got: Remote? = null
            var handed = false
            /** Joins as [h] and reads the world sent, with only the very packs it names. */
            fun enter(h: Message.Hello): Joining = try {
                val r = Remote.join(game.address, game.port, h).also { got = it }
                val w = Save.read(r.welcome!!.save, InstalledPacks::findExact)
                InstalledPacks.prepare(w.roster, InstalledPacks::findExact)
                Joining.In(r, w)
            } catch (e: Refused) {
                Joining.Turned(e.reason)
            } catch (e: MissingPacks) {
                val company = got?.company ?: -1
                got?.leave()
                got = null
                // The base pack comes with the game, so missing it means another version.
                if (e.packs.any { it.id == Packs.BASE_ID }) Joining.Turned(Reject.VERSION) else Joining.Download(company, e.packs)
            } catch (e: BadData) {
                Joining.Unreachable(cantLoad.replace("%s", e.message ?: ""))
            } catch (e: Exception) {
                Joining.Unreachable(cantReach.replace("%s", where(game.address, game.port)))
            }
            try {
                // Connecting, downloading and reading the world all take a while, so none holds up the screen.
                val outcome = withContext(Dispatchers.IO) {
                    /** Fetches [packs] and keeps them; null if it worked, otherwise why not (also null with [stop] set, for cancelling). */
                    fun download(packs: List<PackRef>): Joining? {
                        val stop = AtomicBoolean(false).also { cancel = it }
                        try {
                            val zips = Remote.fetchPacks(game.address, game.port, packs, progress = { _, at, total ->
                                busy = downloading.replace("%1", "%.1f".format(at / 1e6)).replace("%2", "%.1f".format(total / 1e6))
                            }, cancelled = { stop.get() })
                            for ((ref, zip) in zips) InstalledPacks.keepDownloaded(ref, zip)
                            return null
                        } catch (e: Cancelled) {
                            return null
                        } catch (e: Refused) {
                            return Joining.Unreachable(notSent.replace("%s", names(packs)))
                        } catch (e: BadData) {
                            return Joining.Unreachable(broken.replace("%s", e.message ?: ""))
                        } catch (e: BadPack) {
                            return Joining.Unreachable(broken.replace("%s", e.problems.first()))
                        } catch (e: Exception) {
                            return Joining.Unreachable(cantReach.replace("%s", where(game.address, game.port)))
                        } finally {
                            cancel = null
                        }
                    }
                    // Packs the game uses are fetched before joining, so a new company isn't made and left.
                    val missing = game.packs.filter { InstalledPacks.findExact(it) == null }
                    if (missing.isNotEmpty()) {
                        download(missing)?.let { return@withContext it }
                        if (missing.any { InstalledPacks.findExact(it) == null }) return@withContext null
                        busy = joiningText
                    }
                    val first = enter(hello)
                    if (first !is Joining.Download) return@withContext first
                    download(first.packs)?.let { return@withContext it }
                    if (first.packs.any { InstalledPacks.findExact(it) == null }) return@withContext null
                    busy = joiningText
                    // Back as the same client, to the company we were given.
                    when (val again = enter(hello.copy(join = if (first.company >= 0) first.company else Message.SPECTATE))) {
                        is Joining.Download -> Joining.NeedsPacks(names(again.packs))
                        else -> again
                    }
                }
                when (outcome) {
                    null -> {}
                    is Joining.Download -> error = needsPacks.replace("%s", names(outcome.packs))
                    is Joining.In -> { handed = true; onJoined(outcome.remote, outcome.world) }
                    is Joining.Turned -> when (outcome.reason) {
                        Reject.PASSWORD -> {
                            error = if (asked && password.isNotEmpty()) wrongPassword else needsPassword
                            asked = true
                        }
                        Reject.COMPANY_PASSWORD -> {
                            error = if (askedCompany && companyPassword.isNotEmpty()) wrongPassword else needsCompanyPassword
                            askedCompany = true
                        }
                        Reject.VERSION -> error = otherVersion
                        Reject.FULL -> error = full
                        Reject.NO_COMPANY -> error = noCompany
                        Reject.KICKED -> error = kicked
                        else -> error = cantReach.replace("%s", where(game.address, game.port))
                    }
                    is Joining.NeedsPacks -> error = needsPacks.replace("%s", outcome.packs)
                    is Joining.Unreachable -> error = outcome.why
                }
            } finally {
                if (!handed) got?.leave()
                busy = null
            }
        }
    }

    Window(stringResource(Res.string.multiplayer), onClose, Glyph.People) {
        Field(settings.playerName, stringResource(Res.string.your_name), { settings.playerName = it.take(Host.NAME_MOST) })
        val game = looked
        if (game != null) {
            val passwordLabel = stringResource(Res.string.password)
            Head(game.name)
            ListRow(Glyph.Plus, stringResource(Res.string.new_company), null, if (busy == null) ({ join(game, Message.NEW_COMPANY) }) else null)
            for (co in game.companies) {
                // Who's playing it.
                val names = game.players.filter { it.company == co.id }.joinToString(", ") { it.name }
                val line = listOfNotNull(names.ifEmpty { pluralStringResource(Res.plurals.players_count, co.players, co.players) }, passwordLabel.takeIf { co.locked })
                    .joinToString(" · ")
                ListRow(Glyph.People, co.name.ifBlank { fallbackName }, line, if (busy == null) ({ join(game, co.id) }) else null, tint = companyColour(co.id), insignia = co.insignia)
            }
            ListRow(Glyph.Inspect, stringResource(Res.string.watch), null, if (busy == null) ({ join(game, Message.SPECTATE) }) else null)
            if (asked) Field(password, passwordLabel, { password = it }, secret = true)
            if (askedCompany) Field(companyPassword, stringResource(Res.string.company_password), { companyPassword = it.take(Host.PASSWORD_MOST) }, secret = true)
            if (error != null) Text(error ?: "", color = c.bad, fontSize = 14.sp)
            busy?.let { Text(it, color = c.textDim, fontSize = 14.sp) }
            cancel?.let { stop -> BigButton(stringResource(Res.string.cancel), glyph = Glyph.Remove) { stop.set(true) } }
            if (asked || askedCompany) BigButton(stringResource(Res.string.join), primary = true, enabled = busy == null, glyph = Glyph.Play) { join(game, choice) }
            BigButton(stringResource(Res.string.back), enabled = busy == null, glyph = Glyph.Back) {
                looked = null
                error = null
            }
        } else {
            if (error != null) Text(error ?: "", color = c.bad, fontSize = 14.sp)
            busy?.let { Text(it, color = c.textDim, fontSize = 14.sp) }

            Head(stringResource(Res.string.games_nearby))
            if (heard.isEmpty()) Text(stringResource(Res.string.looking_for_games), color = c.textDim, fontSize = 14.sp)
            for ((b, _) in heard.values.sortedBy { it.first.name }) {
                val line = listOfNotNull(
                    b.address,
                    b.year.takeIf { it > 0 }?.toString(),
                    pluralStringResource(Res.plurals.players_count, b.players, b.players),
                    if (b.locked) stringResource(Res.string.password) else null,
                    if (b.protocol != Wire.PROTOCOL) otherVersion else null,
                ).joinToString(" · ")
                ListRow(Glyph.People, b.name, line, if (busy != null) null else ({ look(b.address, b.port, b.name) }))
            }

            Head(stringResource(Res.string.join_by_address))
            Field(settings.lastAddress, stringResource(Res.string.address), { settings.lastAddress = it.trim() }, keyboard = KeyboardType.Uri)
            BigButton(stringResource(Res.string.join), enabled = busy == null && settings.lastAddress.isNotBlank(), glyph = Glyph.Play) {
                val (a, p) = parseAddress(settings.lastAddress)
                look(a, p, where(a, p))
            }
        }
    }
}

/** Who's in: a row each, named, with the company they play in its colour, or watching. */
@Composable
private fun PlayerRows(players: List<Player>, world: World?) {
    val watching = stringResource(Res.string.watching)
    for (p in players) {
        val co = world?.companies?.getOrNull(p.company)
        ListRow(Glyph.People, p.name, co?.name ?: watching, null, tint = co?.let { companyColour(it.id) }, insignia = co?.insignia)
    }
}

/** The password for the company played here: whether it has one, and setting or clearing it. */
@Composable
private fun CompanyPassword(locked: Boolean, lock: (String) -> Unit) {
    val c = LocalColors.current
    var text by remember { mutableStateOf("") }
    Head(stringResource(Res.string.company_password))
    Text(stringResource(if (locked) Res.string.has_password else Res.string.no_password), color = c.textDim, fontSize = 14.sp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
            Field(text, stringResource(Res.string.password), { text = it.take(Host.PASSWORD_MOST) }, secret = true)
        }
        ActionButton(stringResource(Res.string.set_password), enabled = text.isNotEmpty(), strong = true) {
            lock(text)
            text = ""
        }
        ActionButton(stringResource(Res.string.clear_password), enabled = locked) { lock("") }
    }
}

/** [read]'s latest, picked up twice a second. */
@Composable
private fun <T> polled(key: Any?, read: () -> T): T = produceState(read(), key) {
    while (true) {
        value = read()
        delay(500)
    }
}.value

/**
 * Playing with others, from the game menu. Alone, opening the game to
 * players on the network, with a password if wanted. Hosting, the addresses
 * players can join at and who's in, and closing it again. Joined, the
 * server's name, who's in and leaving. Playing a company with others, its
 * password.
 */
@Composable
fun PlayersWindow(
    runner: GameThread,
    host: Host?,
    settings: Settings,
    opening: Boolean,
    problem: String?,
    onOpen: (password: String) -> Unit,
    onShut: () -> Unit,
    onLeave: () -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalColors.current
    val remote = runner.remote
    val world = runner.game?.world
    Window(stringResource(Res.string.multiplayer), onClose, Glyph.People) {
        when {
            remote != null -> {
                Text(remote.server, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Head(stringResource(Res.string.players))
                PlayerRows(polled(remote) { remote.players }, world)
                if (runner.company >= 0) {
                    val locked = polled(remote) { remote.companies.any { it.id == runner.company && it.locked } }
                    CompanyPassword(locked) { remote.lock(it) }
                }
                BigButton(stringResource(Res.string.leave), glyph = Glyph.Home, onClick = onLeave)
            }
            host != null -> {
                Head(stringResource(Res.string.players_join_at))
                val addresses = produceState<List<String>?>(null, host) {
                    while (true) {
                        value = withContext(Dispatchers.IO) { LanDiscovery.addresses() }
                        delay(3_000)
                    }
                }.value
                if (addresses?.isEmpty() == true) Text(stringResource(Res.string.no_network), color = c.bad, fontSize = 14.sp)
                for (a in addresses.orEmpty()) {
                    Text(if (host.port == LanDiscovery.GAME_PORT) a else "$a:${host.port}", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Head(stringResource(Res.string.players))
                PlayerRows(polled(host) { host.players }, world)
                if (runner.company >= 0) {
                    val locked = polled(host) { host.companies.any { it.id == runner.company && it.locked } }
                    CompanyPassword(locked) { host.lock(runner.company, it) }
                }
                BigButton(stringResource(Res.string.close_to_players), glyph = Glyph.Remove, onClick = onShut)
            }
            else -> {
                var password by remember { mutableStateOf("") }
                Field(settings.playerName, stringResource(Res.string.your_name), { settings.playerName = it.take(Host.NAME_MOST) })
                Field(password, stringResource(Res.string.password), { password = it }, secret = true)
                if (problem != null) Text(problem, color = c.bad, fontSize = 14.sp)
                BigButton(stringResource(Res.string.open_to_players), primary = true, enabled = !opening, glyph = Glyph.People) {
                    settings.save()
                    onOpen(password)
                }
            }
        }
    }
}

/** What's been said, the latest at the bottom, each name in its company's colour, and a box to say something. */
@Composable
fun ChatWindow(read: () -> List<ChatLine>, say: (String) -> Unit, onClose: () -> Unit) {
    val c = LocalColors.current
    val lines = polled(Unit) { read() }
    var text by remember { mutableStateOf("") }
    Window(stringResource(Res.string.chat), onClose, Glyph.Chat) {
        if (lines.isEmpty()) Text(stringResource(Res.string.no_chat), color = c.textDim, fontSize = 14.sp)
        for (l in lines) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = if (l.company >= 0) companyColour(l.company) else c.textDim, fontWeight = FontWeight.SemiBold)) { append(l.name) }
                    append("  ")
                    append(l.text)
                },
                color = c.text, fontSize = 14.sp,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                Field(text, stringResource(Res.string.message), { text = it.take(Host.CHAT_MOST) })
            }
            ActionButton(stringResource(Res.string.send), enabled = text.isNotBlank(), strong = true) {
                say(text.trim())
                text = ""
            }
        }
    }
}

/** The game on the other end can't be reached any more; closing goes back to the title screen. */
@Composable
fun LostWindow(onClose: () -> Unit) {
    val c = LocalColors.current
    Window(stringResource(Res.string.connection_lost), onClose, Glyph.People) {
        Text(stringResource(Res.string.connection_lost_line), color = c.text, fontSize = 14.sp)
        BigButton(stringResource(Res.string.title_screen), primary = true, glyph = Glyph.Home, onClick = onClose)
    }
}
