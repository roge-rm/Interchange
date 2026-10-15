package com.rm.interchange.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.State
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.ImageBitmap
import com.rm.interchange.map.MapInput
import com.rm.interchange.map.Moving
import com.rm.interchange.map.vehicleAt
import com.rm.interchange.map.NO_VEHICLE
import com.rm.interchange.map.Sheets
import com.rm.interchange.map.rememberPackedSheets
import androidx.compose.runtime.rememberCoroutineScope
import com.rm.interchange.map.MapView
import com.rm.interchange.map.Pick
import com.rm.interchange.map.LandPainter
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.RowScope
import org.jetbrains.compose.resources.pluralStringResource
import com.rm.interchange.sim.railCost
import com.rm.interchange.sim.canalCost
import com.rm.interchange.sim.bridgeCost
import com.rm.interchange.sim.roadCost
import com.rm.interchange.sim.clearCost
import com.rm.interchange.map.View
import com.rm.interchange.map.buildingAt
import com.rm.interchange.platform.logLine
import com.rm.interchange.server.Game
import com.rm.interchange.server.GameThread
import com.rm.interchange.platform.Network
import com.rm.interchange.server.Host
import com.rm.interchange.server.ChatLine
import com.rm.interchange.server.Remote
import com.rm.interchange.map.VehicleSource
import com.rm.interchange.platform.cameraCutouts
import com.rm.interchange.platform.picker
import com.rm.interchange.map.ChunkBaker
import androidx.compose.ui.platform.LocalDensity
import com.rm.interchange.sim.BadData
import com.rm.interchange.sim.BadPack
import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.Cargo
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.MissingPacks
import com.rm.interchange.sim.Pack
import com.rm.interchange.sim.Packs
import com.rm.interchange.sim.Roster
import com.rm.interchange.sim.Save
import com.rm.interchange.sim.Prices
import com.rm.interchange.sim.price
import com.rm.interchange.sim.Track
import com.rm.interchange.sim.PAVED
import com.rm.interchange.sim.houseOf
import com.rm.interchange.sim.Industry
import com.rm.interchange.sim.News
import androidx.compose.ui.layout.onSizeChanged
import com.rm.interchange.sim.industryAt
import com.rm.interchange.sim.approvalLevel
import com.rm.interchange.map.rememberTownLabels
import com.rm.interchange.sim.World
import com.rm.interchange.sim.Signals
import com.rm.interchange.sim.Surface
import com.rm.interchange.sim.SALT_DEPOT_COST
import com.rm.interchange.sim.Airports
import com.rm.interchange.sim.Depots
import com.rm.interchange.sim.VehicleView
import com.rm.interchange.sim.CANAL_COST
import com.rm.interchange.sim.LOCK_DOLLARS
import com.rm.interchange.sim.DOCK_COST
import com.rm.interchange.sim.SHIPYARD_COST
import com.rm.interchange.sim.BUOY_COST
import com.rm.interchange.sim.FENCE_COST
import com.rm.interchange.sim.SHED_COST
import com.rm.interchange.sim.trackLimit
import com.rm.interchange.sim.Closure
import com.rm.interchange.sim.Spans
import com.rm.interchange.sim.mud
import kotlin.math.roundToInt
import androidx.compose.runtime.key
import com.rm.interchange.platform.Lifecycle
import com.rm.interchange.res.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun App() {
    val settings = remember { Settings.load() }
    InLanguage(settings.language) {
        CompositionLocalProvider(LocalColors provides DarkColors) { Root(settings) }
    }
}

/** Where the app is: one of its screens, or playing. */
private enum class Screen { Title, NewGame, Playing }

/** A window open over the screen. */
private enum class Win { Menu, Load, Settings, Packs, Overlays, Finances, Industries, Companies, Insignia, Town, Subsidies, Chronicle, Multiplayer, Players, Chat }

@Composable
private fun Root(settings: Settings) {
    val scope = rememberCoroutineScope()
    val packed by rememberPackedSheets()
    val sheets = remember(packed) { packed?.let { Sheets(it, scope) } }
    var screen by remember { mutableStateOf(Screen.Title) }
    var window by remember { mutableStateOf<Win?>(null) }
    var town by remember { mutableIntStateOf(-1) }
    // A world point for the map to go to, set from a window and taken by the game screen.
    val goTo = remember { mutableStateOf<Pair<Float, Float>?>(null) }
    // Saved from the menu since it was opened.
    var saved by remember { mutableStateOf(false) }
    var runner by remember { mutableStateOf<GameThread?>(null) }
    // The game opened to players on the network, while it is; and whether it's being opened, or why it couldn't be.
    var host by remember { mutableStateOf<Host?>(null) }
    var opening by remember { mutableStateOf(false) }
    var hostProblem by remember { mutableStateOf<String?>(null) }
    // The game joined stopped answering.
    var lost by remember { mutableStateOf(false) }
    val cantOpen = stringResource(Res.string.cant_open, "%s")
    val gameOf = stringResource(Res.string.game_of, "%s")
    var saves by remember { mutableStateOf(SavedGames.list()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val cantLoad = stringResource(Res.string.cant_load, "%s")
    val needsPacks = stringResource(Res.string.needs_packs, "%s")
    val cantAdd = stringResource(Res.string.cant_add_pack, "%s")
    val added = stringResource(Res.string.pack_added, "%s")
    val lang = languageCode()
    // The packs added, read off the main thread since each is unzipped; null until they're read.
    var packs by remember { mutableStateOf<List<Pack>?>(null) }
    var adding by remember { mutableStateOf(false) }
    var packMessage by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    fun readPacks() {
        scope.launch { packs = withContext(Dispatchers.Default) { InstalledPacks.list() } }
    }
    LaunchedEffect(Unit) { readPacks() }

    // The game saves itself when the app is put away.
    DisposableEffect(Unit) {
        Lifecycle.onHide = { runner?.takeIf { it.remote == null }?.let { SavedGames.save(it, SavedGames.AUTOSAVE) } }
        onDispose { Lifecycle.onHide = null }
    }
    // Autosave follows the setting while a game runs.
    LaunchedEffect(runner, settings.autosave) { runner?.takeIf { it.remote == null }?.autosaveDays = settings.autosaveDays }

    // The host last closed, stopping on a thread of its own; opening again waits for it so the port is free.
    val closing = remember { java.util.concurrent.atomic.AtomicReference<Thread?>(null) }

    /** Stops [h] off the main thread, after any host already stopping, so waiting on the last waits on them all. */
    fun stopHost(h: Host) {
        val before = closing.get()
        closing.set(Thread { before?.join(); h.stop() }.apply { start() })
    }

    /** Closes the game to players, off the main thread. */
    fun closeHost() {
        val h = host ?: return
        host = null
        Network.hosting(null, 0)
        stopHost(h)
    }

    // The platform keeps the app running while the game is open to players, and shows how many are playing.
    DisposableEffect(Unit) {
        Network.stopHosting = ::closeHost
        onDispose { Network.stopHosting = null; Network.hosting(null, 0) }
    }
    LaunchedEffect(host) {
        val h = host
        if (h == null) { Network.hosting(null, 0); return@LaunchedEffect }
        // Checks host itself, since this effect isn't restarted while the app is in the background.
        while (host === h) {
            Network.hosting(h.name, h.players.size)
            delay(5000)
        }
    }

    /** Stops the game running: closes it to players or leaves the game joined, then stops its thread. */
    fun stopGame() {
        closeHost()
        hostProblem = null
        lost = false
        runner?.remote?.leave()
        runner?.stop()
        runner = null
    }

    // The app closing stops the game too. The stop waits behind anything already asked of the game thread, such as
    // the save made when the app was put away.
    DisposableEffect(Unit) {
        onDispose {
            closeHost()
            runner?.let { r ->
                r.remote?.leave()
                r.onGameThread { r.stop() }
            }
            runner = null
        }
    }

    fun play(make: () -> World) {
        stopGame()
        val r = GameThread(make)
        r.autosaveDays = settings.autosaveDays
        r.onAutosave = { bytes -> r.game?.let { SavedGames.writeAutosave(bytes, it.world, r.company) } }
        r.start()
        runner = r
        screen = Screen.Playing
        window = null
    }

    /** Follows a game joined on the network, playing the company it gave us, with no autosaves. */
    fun joined(remote: Remote, world: World) {
        stopGame()
        val r = GameThread({ world }, remote)
        r.company = remote.company
        r.autosaveDays = 0
        // A world sent again after going out of step needs the same packs found.
        r.onGameThread { g -> g.findPack = InstalledPacks::findExact; g.log = ::logLine }
        r.start()
        runner = r
        lost = false
        screen = Screen.Playing
        window = null
    }

    /** Opens the game to players on the network, as the company played here; the port is taken off the main thread. */
    fun openToPlayers(password: String) {
        val r = runner ?: return
        if (opening || host != null || r.remote != null) return
        opening = true
        hostProblem = null
        val company = r.game?.world?.companies?.getOrNull(r.company)?.name ?: "Interchange"
        val player = settings.playerName.trim()
        // Listed by the host's name, or the company's if there's none.
        val name = if (player.isEmpty()) company else gameOf.replace("%s", player)
        val me = player.ifBlank { company }
        scope.launch {
            var made: Host? = null
            var kept = false
            try {
                val result = withContext(Dispatchers.IO) {
                    closing.get()?.join()
                    runCatching { Host(r, name, password = password, hostName = me, hostCompany = r.company, packBytes = InstalledPacks::zip).also { it.log = ::logLine; made = it; it.start() } }
                }
                // Kept only if the game it was opened for is still running.
                result.onSuccess { h -> if (runner === r) { host = h; kept = true } }
                    .onFailure { hostProblem = cantOpen.replace("%s", it.message ?: "") }
            } finally {
                opening = false
                if (!kept) made?.let { stopHost(it) }
            }
        }
    }

    // Watches a game joined for its connection dropping.
    LaunchedEffect(runner) {
        val r = runner ?: return@LaunchedEffect
        val remote = r.remote ?: return@LaunchedEffect
        while (remote.lost == null) delay(500)
        if (runner === r) lost = true
    }

    // The latest line of chat, and the latest one read, for lighting the strip's chat button.
    val lastChat by androidx.compose.runtime.produceState<ChatLine?>(null, runner, host) {
        while (true) {
            value = (host?.chat ?: runner?.remote?.chat)?.lastOrNull()
            delay(500)
        }
    }
    var readChat by remember { mutableStateOf<ChatLine?>(null) }
    LaunchedEffect(window, lastChat) { if (window == Win.Chat) readChat = lastChat }
    val inMultiplayer = host != null || runner?.remote != null

    /** Reads a save off the main thread, with the packs it needs, and plays it, or says why it can't. */
    fun load(entry: SavedGames.Entry) {
        scope.launch {
            val world = withContext(Dispatchers.Default) {
                try {
                    val w = Save.read(SavedGames.read(entry.name) ?: throw BadData("missing"), InstalledPacks::find)
                    InstalledPacks.prepare(w.roster)
                    Result.success(w)
                } catch (e: BadData) {
                    Result.failure(e)
                }
            }
            world.onSuccess { w -> loadError = null; play { w } }.onFailure {
                loadError = if (it is MissingPacks) needsPacks.replace("%s", it.packs.joinToString { p -> "${p.id} ${p.version}" })
                else cantLoad.replace("%s", it.message ?: "")
            }
        }
    }

    /** Asks for a pack's file and adds it, checked off the main thread. */
    fun addPack() {
        picker.pickFile { bytes ->
            if (bytes == null) return@pickFile
            adding = true
            scope.launch {
                packMessage = withContext(Dispatchers.Default) {
                    try {
                        added.replace("%s", InstalledPacks.add(bytes).name(lang)) to false
                    } catch (e: BadPack) {
                        cantAdd.replace("%s", e.problems.joinToString("\n")) to true
                    }
                }
                adding = false
                readPacks()
            }
        }
    }

    CompositionLocalProvider(LocalCompany provides (runner?.company ?: 0)) { Box(Modifier.fillMaxSize()) {
        when (screen) {
            Screen.Title -> TitleScreen(
                continueLine = saves.firstOrNull()?.let { date(it.day, it.startYear) + " · " + saveLine(it) },
                onContinue = { saves.firstOrNull()?.let { load(it) } },
                onNew = { screen = Screen.NewGame },
                onLoad = { saves = SavedGames.list(); window = Win.Load },
                onMultiplayer = { window = Win.Multiplayer },
                onPacks = { packMessage = null; window = Win.Packs },
                onSettings = { window = Win.Settings },
            )
            Screen.NewGame -> NewGameScreen(
                packs.orEmpty(),
                onStart = { ng, chosen ->
                    play {
                        val roster = Roster.build(listOf(Packs.BASE) + chosen)
                        InstalledPacks.prepare(roster)
                        World(ng, roster)
                    }
                },
                // Back to the game it was opened from, if there is one.
                onBack = { screen = if (runner != null) Screen.Playing else Screen.Title },
            )
            Screen.Playing -> {
                val r = runner
                val s = sheets
                if (r == null || s == null) {
                    Box(Modifier.fillMaxSize().background(LocalColors.current.page))
                } else {
                    Playing(r, s, settings, onMenu = { saved = false; window = Win.Menu }, onOverlays = { window = Win.Overlays }, onFinances = { window = Win.Finances }, onTown = { town = it; window = Win.Town }, goTo = goTo,
                        onChat = if (inMultiplayer) ({ window = Win.Chat }) else null, chatUnread = lastChat != null && lastChat !== readChat)
                }
            }
        }
        when (window) {
            Win.Menu -> MenuWindow(
                saved,
                canSave = runner?.remote == null,
                onSave = { runner?.let { r -> SavedGames.save(r) { scope.launch { saves = SavedGames.list(); saved = true } } } },
                onLoad = { saves = SavedGames.list(); window = Win.Load },
                onNew = { window = null; screen = Screen.NewGame },
                onPlayers = { hostProblem = null; window = Win.Players },
                onSettings = { window = Win.Settings },
                onIndustries = { window = Win.Industries },
                onCompanies = { window = Win.Companies },
                onSubsidies = { window = Win.Subsidies },
                onChronicle = { window = Win.Chronicle },
                // Back to the title screen the game is saved and stopped; Continue loads it again.
                onTitle = {
                    val r = runner
                    if (r != null && r.remote == null) {
                        // Saved first, stopped after; closing to players can't wait for it.
                        closeHost()
                        SavedGames.save(r, SavedGames.AUTOSAVE) {
                            r.stop()
                            scope.launch { saves = SavedGames.list() }
                        }
                        runner = null
                    } else stopGame()
                    window = null
                    screen = Screen.Title
                },
                onClose = { window = null },
            )
            Win.Load -> LoadWindow(
                saves, loadError, onLoad = { load(it) },
                onDelete = { SavedGames.delete(it.name); saves = SavedGames.list() },
                onClose = { loadError = null; window = null },
            )
            Win.Settings -> SettingsWindow(settings) { window = null }
            Win.Packs -> PacksWindow(
                packs.orEmpty(), adding, packMessage?.first, packMessage?.second == true,
                onAdd = ::addPack,
                onRemove = { p ->
                    scope.launch {
                        withContext(Dispatchers.Default) { InstalledPacks.remove(p.id) }
                        packMessage = null
                        readPacks()
                    }
                },
                onClose = { window = null },
            )
            Win.Overlays -> OverlaysWindow(settings) { window = null }
            Win.Finances -> runner?.let { r -> FinanceWindow(r) { window = null } }
            Win.Industries -> runner?.game?.world?.let { w -> IndustriesWindow(w, { x, y -> goTo.value = x to y; window = null }, { runner?.request(it) }) { window = null } }
            Win.Companies -> runner?.game?.world?.let { w -> CompaniesWindow(w, { x, y -> goTo.value = x to y; window = null }, { runner?.request(it) }, { window = Win.Insignia }) { window = null } }
            Win.Insignia -> runner?.game?.world?.let { w ->
                val own = w.companies.getOrNull(LocalCompany.current)?.takeIf { !it.gone }
                if (own != null) InsigniaWindow(own, { runner?.request(it) }) { window = Win.Companies }
                else LaunchedEffect(Unit) { window = null }
            }
            Win.Chronicle -> runner?.game?.world?.let { w -> ChronicleWindow(w, { x, y -> goTo.value = x to y; window = null }) { window = null } }
            Win.Subsidies -> runner?.game?.world?.let { w -> SubsidiesWindow(w, { x, y -> goTo.value = x to y; window = null }) { window = null } }
            Win.Town -> runner?.game?.world?.let { w -> w.towns.getOrNull(town)?.let { t -> TownWindow(w, t, { runner?.request(it) }) { window = null } } }
            Win.Multiplayer -> MultiplayerWindow(settings, ::joined) { window = null }
            Win.Players -> runner?.let { r ->
                PlayersWindow(
                    r, host, settings, opening, hostProblem,
                    onOpen = ::openToPlayers,
                    onShut = {
                        closeHost()
                    },
                    onLeave = {
                        stopGame()
                        window = null
                        screen = Screen.Title
                    },
                    onClose = { window = null },
                )
            }
            Win.Chat -> {
                val h = host
                val remote = runner?.remote
                if (h != null) ChatWindow({ h.chat }, h::say) { window = null }
                else if (remote != null) ChatWindow({ remote.chat }, { remote.say(it) }) { window = null }
            }
            null -> {}
        }
        // Over the game only: a new game being set up stays, and the window waits for the game to come back.
        if (lost && screen == Screen.Playing) LostWindow {
            lost = false
            stopGame()
            window = null
            screen = Screen.Title
        }
    } }
}

/** A game being played: waits for the world to be made, then shows it. */
@Composable
private fun Playing(runner: GameThread, sheets: Sheets, settings: Settings, onMenu: () -> Unit, onOverlays: () -> Unit, onFinances: () -> Unit, onTown: (Int) -> Unit, goTo: MutableState<Pair<Float, Float>?>, onChat: (() -> Unit)?, chatUnread: Boolean) {
    var world by remember(runner) { mutableStateOf<World?>(null) }
    // Redraws every display frame, and picks up the world once it's made, or when a server sends it again.
    val frame = remember(runner) { mutableLongStateOf(0L) }
    LaunchedEffect(runner) {
        while (true) withFrameNanos {
            frame.longValue++
            runner.game?.world?.let { if (it !== world) world = it }
        }
    }
    val w = world
    if (w == null) {
        Box(Modifier.fillMaxSize().background(LocalColors.current.page))
        return
    }
    key(runner, w) { GameScreen(runner, w, sheets, frame, settings, onMenu, onOverlays, onFinances, onTown, goTo, onChat, chatUnread) }
}

@Composable
private fun GameScreen(runner: GameThread, world: World, sheets: Sheets, frame: State<Long>, settings: Settings, onMenu: () -> Unit, onOverlays: () -> Unit, onFinances: () -> Unit, onTown: (Int) -> Unit, goTo: MutableState<Pair<Float, Float>?>, onChat: (() -> Unit)?, chatUnread: Boolean) {
    val me = runner.company
    val c = LocalColors.current
    // The drawings of this game's vehicles: the base pack's come with the app, the others from their packs.
    remember(world) {
        sheets.useVehicles(world.roster.sprites.map { ref ->
            if (ref.pack == Packs.BASE_ID) sheets.baseVehicle(ref.name)
            else InstalledPacks.rects(ref.pack, ref.name)?.let { rects -> VehicleSource(rects) { level -> InstalledPacks.sheet(ref.pack, ref.name, level) } }
        })
    }
    val view = remember {
        // Over the biggest town, or the dry land nearest the middle if there are none.
        View(world.width, world.height).apply {
            val town = world.towns.maxByOrNull { it.population }
            val i = if (town != null) world.index(town.x, town.y) else world.landNearMiddle()
            focusX = i % world.width + 0.5f
            focusY = i / world.width + 0.5f
        }
    }
    // Going where a window asked to.
    goTo.value?.let { (x, y) ->
        view.focusX = x
        view.focusY = y
        goTo.value = null
    }

    var paused by remember { mutableStateOf(false) }
    var speed by remember { mutableIntStateOf(0) }
    // What the strip shows, set only when it changes so the screen doesn't compose every frame.
    var day by remember { mutableIntStateOf(runner.day) }
    var money by remember { mutableLongStateOf(runner.money) }
    // The temperature where the map's looking, worked out again when the day or the tile changes.
    var weather by remember { mutableStateOf("") }
    var weatherAt by remember { mutableStateOf(-1L) }
    LaunchedEffect(runner) {
        while (true) withFrameNanos {
            if (runner.day != day) day = runner.day
            if (runner.money != money) money = runner.money
            val wv = runner.view?.weather
            val x = view.focusX.toInt().coerceIn(0, world.width - 1)
            val y = view.focusY.toInt().coerceIn(0, world.height - 1)
            val at = (wv?.day?.toLong() ?: -1L) * world.width * world.height + world.index(x, y)
            if (wv != null && at != weatherAt) {
                weatherAt = at
                weather = degrees(skyHere(world, wv, x, y).temperature)
            }
        }
    }

    // The game's latest view of trains, stations and signals; only the cards read it, so only they compose each tick.
    val worldView = remember { mutableStateOf(runner.view) }
    LaunchedEffect(runner) {
        while (true) withFrameNanos { if (runner.view !== worldView.value) worldView.value = runner.view }
    }

    var tool by remember { mutableStateOf(Tool.Inspect) }
    // The tool last used in each group, so a group's button opens on it.
    val lastTool = remember { mutableStateMapOf<ToolGroup, Tool>() }
    var removing by remember { mutableStateOf(false) }
    var stationAlongX by remember { mutableStateOf(true) }
    var signalKind by remember { mutableIntStateOf(0) }
    var bridgeStyle by remember { mutableIntStateOf(0) }
    var bridgePlan by remember { mutableStateOf<Command.Bridge?>(null) }
    // The tray folds to its line once something's built, to show more of the map.
    var folded by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<Command.Rail?>(null) }
    var stationPlan by remember { mutableStateOf<Command.Station?>(null) }
    var selected by remember { mutableStateOf<Selection?>(null) }
    // The train having stops added by tapping stations, if any.
    var addingStops by remember { mutableStateOf<Int?>(null) }
    var addingRoadStops by remember { mutableStateOf<Int?>(null) }
    // A ship (false) or aircraft (true) having stops added by tapping, and which.
    var addingCraftStops by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
    var canalPlan by remember { mutableStateOf<Command.Canal?>(null) }
    var clearPlan by remember { mutableStateOf<Command.ClearArea?>(null) }
    // The track tool lays lines on its own (0), or one piece, a view piece, at a time; and where the drag is now.
    var trackPiece by remember { mutableIntStateOf(0) }
    var dragAt by remember { mutableStateOf<Pick?>(null) }
    var airportKind by remember { mutableIntStateOf(Airports.entries(Calendar.year(world.startYear, world.day)).lastOrNull { it != Airports.SEAPLANE && it != Airports.HELIPORT } ?: Airports.STRIP) }
    var runwayAlongX by remember { mutableStateOf(true) }
    var roadPlan by remember { mutableStateOf<Command.Road?>(null) }
    var roadSurface by remember { mutableIntStateOf(Surface.entries(Calendar.year(world.startYear, world.day)).last()) }
    var mapSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }

    fun built() { folded = true }

    fun railFor(start: Pick, end: Pick): Command.Rail =
        if (trackPiece == 0 || tool != Tool.Track) trackLine(me, start, end, removing)
        else pieceLine(world, me, start, end, worldPiece(LandPainter(world, view, view.halfWidth), trackPiece), removing)

    val input = remember(tool, removing, stationAlongX, addingStops, addingRoadStops, addingCraftStops, signalKind, bridgeStyle, roadSurface, airportKind, runwayAlongX, trackPiece) {
        object : MapInput {
            var start: Pick? = null
            override val drags get() = addingStops == null && addingRoadStops == null && addingCraftStops == null &&
                (tool == Tool.Track || tool == Tool.Wires || tool == Tool.Station || tool == Tool.Bridge || tool == Tool.Road || tool == Tool.TramRails || tool == Tool.Canal || tool == Tool.Clear)
            override fun tap(at: Pick) {
                val ground = world.index(at.tileX, at.tileY)
                // Adding stops or looking, a tap on a building's roof means the building, not the land behind it.
                val picking = addingStops != null || addingRoadStops != null || addingCraftStops != null || tool == Tool.Inspect
                val tile = if (!picking) ground else view.buildingAt(world, at, mapSize.width, mapSize.height) {
                    world.depotTiles.containsKey(it) || world.stationTiles.containsKey(it) || world.roadStops.containsKey(it) ||
                        world.docks.containsKey(it) || world.airportTiles.containsKey(it) || world.buoys.containsKey(it)
                } ?: ground
                addingStops?.let { id ->
                    val station = world.stationTiles[tile]
                    val t = worldView.value?.trains?.firstOrNull { it.id == id }
                    if (station != null && t != null) runner.request(Command.TrainOrder(me, id, Command.TrainOrder.ORDERS, t.orders + station))
                    return
                }
                addingRoadStops?.let { id ->
                    val station = world.roadStops[tile] ?: world.stationTiles[tile]
                    val v = worldView.value?.roadVehicles?.firstOrNull { it.id == id }
                    if (station != null && v != null) runner.request(Command.RoadOrder(me, id, Command.TrainOrder.ORDERS, v.orders + station))
                    return
                }
                addingCraftStops?.let { (air, id) ->
                    val station = if (air) world.airportTiles[tile]?.let { world.airports.getOrNull(it)?.station }
                    else world.docks[tile] ?: world.buoys[tile]
                    if (station != null) {
                        val orders = if (air) worldView.value?.aircraft?.firstOrNull { it.id == id }?.orders else worldView.value?.ships?.firstOrNull { it.id == id }?.orders
                        if (orders != null) runner.request(Command.CraftOrder(me, air, id, Command.TrainOrder.ORDERS, orders + station))
                    }
                    return
                }
                when (tool) {
                    Tool.Inspect -> {
                        val key = vehicleAt(world, view, moving(runner), at.screenX, at.screenY, mapSize.width, mapSize.height)
                        selected = when {
                            key != NO_VEHICLE && key >= 0 -> Selection.Train(key / 256)
                            key != NO_VEHICLE && VehicleView.craft(key, VehicleView.SHIP) >= 0 -> Selection.Ship(VehicleView.craft(key, VehicleView.SHIP))
                            key != NO_VEHICLE && VehicleView.craft(key, VehicleView.AIRCRAFT) >= 0 -> Selection.Aircraft(VehicleView.craft(key, VehicleView.AIRCRAFT))
                            key != NO_VEHICLE -> Selection.RoadVehicle(VehicleView.craft(key, VehicleView.ROAD))
                            // Another company's airport shows as its station, without buying or building.
                            world.airportTiles.containsKey(tile) -> world.airports.getOrNull(world.airportTiles.getValue(tile))?.let {
                                if (it.owner == me) Selection.Airport(it.id) else Selection.Station(it.station)
                            } ?: Selection.Tile(at.tileX, at.tileY)
                            world.docks.containsKey(tile) -> Selection.Station(world.docks.getValue(tile))
                            world.stationTiles.containsKey(tile) -> Selection.Station(world.stationTiles.getValue(tile))
                            world.roadStops.containsKey(tile) -> Selection.Station(world.roadStops.getValue(tile))
                            world.depotTiles.containsKey(tile) -> world.depotTiles.getValue(tile).let {
                                if (world.depots[it]?.owner != me) Selection.Tile(at.tileX, at.tileY)
                                else when (world.depots[it]?.kind) {
                                    Depots.ROAD -> Selection.Garage(it)
                                    Depots.SHIPYARD -> Selection.Shipyard(it)
                                    else -> Selection.Depot(it)
                                }
                            }
                            else -> Selection.Tile(at.tileX, at.tileY)
                        }
                    }
                    Tool.Track -> { runner.request(railFor(at, at)); built() }
                    Tool.Road -> { runner.request(roadLine(me, at, at, roadSurface, removing)); built() }
                    Tool.TramRails -> { runner.request(roadLine(me, at, at, roadSurface, removing, tram = true)); built() }
                    Tool.RoadStop -> { runner.request(Command.RoadStop(me, at.tileX, at.tileY)); built() }
                    Tool.Garage -> { runner.request(garageAt(world, me, at)); built() }
                    Tool.OneWay -> { runner.request(Command.RoadOneWay(me, at.tileX, at.tileY)); built() }
                    Tool.SaltDepot -> { runner.request(Command.SaltDepot(me, at.tileX, at.tileY)); built() }
                    Tool.Canal -> { runner.request(Command.Canal(me, at.tileX, at.tileY, at.tileX, at.tileY, removing)); built() }
                    Tool.Lock -> { runner.request(Command.Lock(me, at.tileX, at.tileY)); built() }
                    Tool.Dock -> { runner.request(Command.Dock(me, at.tileX, at.tileY)); built() }
                    Tool.Shipyard -> { runner.request(Command.Shipyard(me, at.tileX, at.tileY)); built() }
                    Tool.Buoy -> { runner.request(Command.Buoy(me, at.tileX, at.tileY)); built() }
                    Tool.Airport -> { runner.request(Command.Airport(me, at.tileX, at.tileY, airportKind, if (runwayAlongX) Track.X else Track.Y)); built() }
                    Tool.Wires -> { runner.request(trackLine(me, at, at, removing).let { Command.Wire(me, it.sx, it.sy, it.ex, it.ey, it.first, it.remove) }); built() }
                    Tool.Signals -> signalAt(world, me, at)?.let { runner.request(it.copy(kind = Signals.PLACED[signalKind])); built() }
                    Tool.Waypoint -> { runner.request(Command.Waypoint(me, at.tileX, at.tileY)); built() }
                    Tool.Tunnel -> { runner.request(Command.Tunnel(me, at.tileX, at.tileY)); built() }
                    Tool.SnowShed, Tool.SnowFence -> { runner.request(Command.SnowWorks(me, at.tileX, at.tileY, shed = tool == Tool.SnowShed)); built() }
                    Tool.Station -> { runner.request(stationPlan(me, at, at, stationAlongX)); built() }
                    Tool.Depot -> {
                        runner.request(depotAt(world, me, at))
                        built()
                    }
                    Tool.Clear -> { runner.request(Command.Clear(me, at.tileX, at.tileY)); built() }
                    Tool.Raise, Tool.Lower -> {
                        runner.request(Command.Terraform(me, at.x.roundToInt(), at.y.roundToInt(), tool == Tool.Raise))
                        built()
                    }
                    else -> {}
                }
            }
            override fun dragStart(at: Pick) {
                start = at
                dragTo(at)
            }
            override fun dragTo(at: Pick) {
                val s = start ?: return
                dragAt = at
                when (tool) {
                    Tool.Road -> roadPlan = roadLine(me, s, at, roadSurface, removing)
                    Tool.TramRails -> roadPlan = roadLine(me, s, at, roadSurface, removing, tram = true)
                    Tool.Station -> stationPlan = stationPlan(me, s, at, stationAlongX)
                    Tool.Bridge -> bridgePlan = bridgePlan(me, s, at, bridgeStyle)
                    Tool.Canal -> canalPlan = roadLine(me, s, at, 0, removing).let { Command.Canal(me, it.sx, it.sy, it.ex, it.ey, removing) }
                    Tool.Clear -> clearPlan = Command.ClearArea(me, s.tileX, s.tileY, at.tileX, at.tileY)
                    else -> plan = railFor(s, at)
                }
            }
            override fun dragEnd() {
                plan?.let { runner.request(if (tool == Tool.Wires) Command.Wire(me, it.sx, it.sy, it.ex, it.ey, it.first, it.remove) else it); built() }
                stationPlan?.let { runner.request(it); built() }
                bridgePlan?.let { runner.request(it); built() }
                roadPlan?.let { runner.request(it); built() }
                canalPlan?.let { runner.request(it); built() }
                clearPlan?.let { runner.request(it); built() }
                dragCancel()
            }
            override fun dragCancel() {
                plan = null
                stationPlan = null
                bridgePlan = null
                roadPlan = null
                canalPlan = null
                clearPlan = null
                dragAt = null
                start = null
            }
        }
    }

    val townLabels = rememberTownLabels(c.chrome, c.text, c.textDim)
    // What the drag would take, how long it is, and whether there's the money: drawn by the finger.
    // A player who just joined may be drawn a frame or two before their company is made; costs go by the first till then.
    val company = world.companies.getOrNull(me) ?: world.companies[0]
    val dragged: Pair<Int, Long>? = plan?.let { p ->
        if (tool == Tool.Wires) Track.line(world, p.sx, p.sy, p.ex, p.ey, p.first)?.let { it.size to it.size * world.price(Prices.WIRE) }
        else world.railCost(p).let { (cost, n) -> if (cost < 0) null else n to cost }
    } ?: roadPlan?.let { p -> world.roadCost(company, p).takeIf { it >= 0 }?.let { (maxOf(kotlin.math.abs(p.ex - p.sx), kotlin.math.abs(p.ey - p.sy)) + 1) to it } }
        ?: canalPlan?.let { p -> world.canalCost(company, p).let { (cost, n) -> n to cost } }
        ?: clearPlan?.let { p -> world.clearCost(me, p).let { (cost, n) -> n to cost } }
        ?: stationPlan?.let { p -> ((kotlin.math.abs(p.x1 - p.x0) + 1) * (kotlin.math.abs(p.y1 - p.y0) + 1)).let { n -> n to n * world.price(Prices.PLATFORM) } }
        ?: bridgePlan?.let { p -> world.bridgeCost(company, p).takeIf { it >= 0 }?.let { (maxOf(kotlin.math.abs(p.x1 - p.x0), kotlin.math.abs(p.y1 - p.y0)) + 1) to it } }
    val dragText = dragged?.takeIf { it.first > 0 }?.let { (n, cost) ->
        stringResource(Res.string.drag_label, pluralStringResource(Res.plurals.drag_tiles, n, n), money(cost))
    }
    val shortOf = dragged != null && dragged.second > money
    val measurer = rememberTextMeasurer()
    // Money as the language writes it, for drawing over the map outside composition.
    val moneyPattern = stringResource(Res.string.money, "%s")
    val stationNumber = stringResource(Res.string.station_n, "%d")
    BoxWithConstraints(Modifier.fillMaxSize().background(c.page)) {
        val layout = screenLayout(maxWidth, maxHeight)
        val gap = if (layout.compact) 6.dp else 10.dp
        val compactTools = layout.compact || layout.short || layout.narrow
        val safe = WindowInsets.safeDrawing
        mapSize = androidx.compose.ui.geometry.Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())

        MapView(
            world, sheets, view, { frame.value }, input, { moving(runner) }, Modifier.fillMaxSize(),
            overlay = plan?.let { p -> { painter -> drawTrackPlan(world, painter, p) } }
                ?: stationPlan?.let { p -> { painter -> drawStationPlan(world, painter, p) } }
                ?: bridgePlan?.let { p -> { painter -> drawBridgePlan(world, painter, p) } }
                ?: roadPlan?.let { p -> { painter -> drawRoadPlan(world, painter, p) } }
                ?: canalPlan?.let { p -> { painter -> drawCanalPlan(world, painter, p) } }
                ?: clearPlan?.let { p -> { painter -> drawClearPlan(world, painter, p) } },
            label = dragAt?.takeIf { dragText != null }?.let { at -> { drawDragLabel(world, view, measurer, dragText!!, shortOf, at) } },
            page = c.page,
            overlays = (if (settings.grid) ChunkBaker.GRID else 0) or (if (settings.heights) ChunkBaker.HEIGHTS else 0) or (if (settings.snow) ChunkBaker.SNOW else 0) or (if (settings.condition) ChunkBaker.CONDITION else 0),
            labels = {
                val wv = worldView.value
                with(townLabels) {
                    draw(world, view, wv?.towns.orEmpty()) { Numbers.grouped(it.toLong()) }
                    if (wv != null) drawStations(world, view, wv.stations) { it.name.ifEmpty { stationNumber.replace("%d", (it.id + 1).toString()) } }
                    if (wv != null) drawPayments(world, view, wv.payments, runner.tick, c.good) { moneyPattern.replace("%s", Numbers.grouped(it)) }
                }
            },
        )

        // Every upright phone: one line can't hold the buttons and the readings without crowding them.
        val twoLines = layout.shape == ScreenShape.Tall && !layout.large
        // On an upright phone with its camera in the top edge, the strip goes up round it: if each camera is small
        // enough to leave room for the buttons, and short enough to stay within the strip's first row.
        val density = LocalDensity.current
        var stripHeight by remember { mutableIntStateOf(0) }
        val windowPx = with(density) { maxWidth.toPx() }
        val rowPx = with(density) { CAMERA_ROW.dp.toPx() }
        val deepestPx = with(density) { (CAMERA_ROW + CAMERA_DEEPER).dp.toPx() }
        val topCameras = cameraCutouts().filter { it.top <= 0f && it.height > 0f }
        val cameras = if (twoLines && topCameras.isNotEmpty() && topCameras.all { it.width <= windowPx * CAMERA_SHARE && it.bottom <= deepestPx }) {
            topCameras.map { it.left..it.right }
        } else emptyList()
        // A camera deeper than the first row takes the strip down with it, so it still sits level with the row.
        val cameraDrop = with(density) { if (cameras.isEmpty()) 0.dp else (topCameras.maxOf { it.bottom } - rowPx).coerceAtLeast(0f).toDp() }
        StatusStrip(
            onMenu,
            paused, { paused = !paused; runner.paused = paused }, speed, { speed = (speed + 1) % SPEEDS.size; runner.speed = SPEEDS[speed] },
            settings.grid || settings.heights || settings.snow || settings.condition, onOverlays,
            weekdayDate(day, world.startYear),
            money(money), money < 0,
            { view.turnBy(-1) }, { view.turnBy(1) }, onFinances,
            weather, { selected = if (selected == Selection.Weather) null else Selection.Weather },
            compact = layout.compact || layout.narrow,
            twoLines = twoLines,
            cameras = cameras,
            timeControls = runner.remote == null,
            showMoney = me >= 0,
            onChat = onChat,
            chatUnread = chatUnread,
            modifier = if (cameras.isNotEmpty()) {
                // Up into the band the camera takes, across the screen, the camera sitting on it with the buttons clear of it.
                Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(safe.only(WindowInsetsSides.Horizontal))
                    .padding(start = gap, end = gap, top = CAMERA_GAP.dp + cameraDrop)
                    .fillMaxWidth()
            } else {
                Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(safe.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(gap)
                    .then(if (twoLines) Modifier.fillMaxWidth() else Modifier)
            }.onSizeChanged { stripHeight = it.height },
        )

        // Under the strip, the latest news for a few seconds after it comes; a tap goes to where it happened.
        val latest = worldView.value?.news?.lastOrNull()
        var shown by remember(world) { mutableStateOf<News?>(null) }
        var seen by remember(world) { mutableStateOf<News?>(null) }
        LaunchedEffect(latest) {
            // The news there when the game opens is old, so only what comes after shows.
            if (seen == null) {
                seen = latest ?: return@LaunchedEffect
                return@LaunchedEffect
            }
            if (latest == null || latest === seen) return@LaunchedEffect
            seen = latest
            shown = latest
            delay(NEWS_MILLIS)
            shown = null
        }
        AnimatedVisibility(shown != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            val n = shown ?: seen ?: return@AnimatedVisibility
            val ind = if (n.kind >= News.PROSPECTING_FAILED) null else world.industries.getOrNull(n.about)
            val text = newsText(world, n)
            Box(
                Modifier
                    .padding(top = with(density) { stripHeight.toDp() } + gap)
                    .windowInsetsPadding(safe.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = gap),
            ) {
                val about = newsCompany(world, n)
                MessageChip(text, about?.let { co -> { CompanyInsignia(co, 28.dp) } }) {
                    if (ind != null) {
                        view.focusX = ind.x + 1f
                        view.focusY = ind.y + 1f
                    }
                    if (n.kind == News.SNOWBOUND || n.kind == News.TRAIN_OLD) selected = Selection.Train(n.about)
                    if (n.kind == News.SLID) selected = Selection.RoadVehicle(n.about)
                    if (n.kind == News.DIVERTED) selected = Selection.Aircraft(n.about)
                    if (n.kind == News.SUBSIDY_OFFERED || n.kind == News.SUBSIDY_AWARDED) subsidyStart(world, n.about).let { (x, y) -> view.focusX = x; view.focusY = y }
                    if (n.kind == News.WASHOUT || n.kind == News.FIRE || n.kind == News.AVALANCHE) {
                        view.focusX = n.about % world.width + 0.5f
                        view.focusY = n.about / world.width + 0.5f
                    }
                }
            }
        }

        fun pickGroup(g: ToolGroup) {
            if (tool.group != g) lastTool[tool.group] = tool
            tool = lastTool[g] ?: Tool.of(g).first { it.ready }
            plan = null
            stationPlan = null
            folded = false
            addingStops = null
            addingCraftStops = null
            if (g != ToolGroup.Inspect) selected = null
        }

        // Someone watching only looks, so the building tools are left out.
        if (layout.sideTools && me >= 0) {
            ToolBar(
                tool.group, ::pickGroup, vertical = true, compact = compactTools,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .windowInsetsPadding(safe.only(WindowInsetsSides.End))
                    .padding(gap),
            )
        }

        // Along the bottom: what's selected, the tool's tray, and on an upright screen the tools.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(safe.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(gap)
                .then(if (layout.sideTools) Modifier.padding(end = 80.dp) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            val trayWidth = Modifier.widthIn(max = TRAY_WIDTH.dp).fillMaxWidth()
            val wv = worldView.value
            when (val sel = selected) {
                is Selection.Tile -> InspectCard(world, sel.x, sel.y, onTown, { selected = null }, trayWidth)
                Selection.Weather -> wv?.weather?.let { w ->
                    WeatherCard(world, w, view.focusX.toInt().coerceIn(0, world.width - 1), view.focusY.toInt().coerceIn(0, world.height - 1), { selected = null }, trayWidth)
                }
                is Selection.Train -> {
                    val t = wv?.trains?.firstOrNull { it.id == sel.id }
                    if (t == null || wv == null) selected = null
                    else TrainCard(wv, world.roster, t, addingStops == t.id, { addingStops = if (addingStops == t.id) null else t.id }, runner::request, { selected = null; addingStops = null }, trayWidth)
                }
                is Selection.Depot -> if (wv != null) DepotCard(
                    wv, world.roster, sel.id, Calendar.year(world.startYear, day), money, { world.price(it) }, runner::request,
                    { selected = Selection.Train(it) }, { selected = null }, trayWidth,
                )
                is Selection.RoadVehicle -> {
                    val v = wv?.roadVehicles?.firstOrNull { it.id == sel.id }
                    if (v == null || wv == null) selected = null
                    else RoadVehicleCard(wv, world.roster, v, addingRoadStops == v.id, { addingRoadStops = if (addingRoadStops == v.id) null else v.id }, runner::request, { selected = null; addingRoadStops = null }, trayWidth)
                }
                is Selection.Garage -> if (wv != null) GarageCard(
                    wv, world.roster, sel.id, Calendar.year(world.startYear, day), money, { world.price(it) }, runner::request,
                    { selected = Selection.RoadVehicle(it) }, { selected = null }, trayWidth,
                )
                is Selection.Shipyard -> if (wv != null) ShipyardCard(
                    wv, world.roster, sel.id, Calendar.year(world.startYear, day), money, { world.price(it) }, runner::request,
                    { selected = Selection.Ship(it) }, { selected = null }, trayWidth,
                )
                is Selection.Airport -> {
                    val a = wv?.airports?.firstOrNull { it.id == sel.id }
                    if (a == null || wv == null) selected = null
                    else AirportCard(wv, world.roster, a, Calendar.year(world.startYear, day), money, { world.price(it) }, runner::request, { selected = Selection.Aircraft(it) }, { selected = null }, trayWidth)
                }
                is Selection.Ship -> {
                    val v = wv?.ships?.firstOrNull { it.id == sel.id }
                    if (v == null || wv == null) selected = null
                    else ShipCard(wv, world.roster, v, addingCraftStops == (false to v.id), { addingCraftStops = if (addingCraftStops == (false to v.id)) null else false to v.id }, runner::request, { selected = null; addingCraftStops = null }, trayWidth)
                }
                is Selection.Aircraft -> {
                    val v = wv?.aircraft?.firstOrNull { it.id == sel.id }
                    if (v == null || wv == null) selected = null
                    else AircraftCard(wv, world.roster, v, addingCraftStops == (true to v.id), { addingCraftStops = if (addingCraftStops == (true to v.id)) null else true to v.id }, runner::request, { selected = null; addingCraftStops = null }, trayWidth)
                }
                is Selection.Station -> {
                    val s = wv?.stations?.firstOrNull { it.id == sel.id }
                    if (s == null || wv == null) selected = null
                    else StationCard(wv, s, { selected = Selection.Train(it) }, { selected = null }, trayWidth)
                }
                null -> {}
            }
            if (tool.group != ToolGroup.Inspect && addingStops == null && addingRoadStops == null && addingCraftStops == null) {
                val detail = when (tool) {
                    Tool.Track -> if (removing) stringResource(Res.string.cost_lift, money(world.price(Prices.TRACK_REMOVE))) else stringResource(Res.string.cost_track, money(world.price(Prices.TRACK)), money(world.price(Prices.FOUNDATION)))
                    Tool.Signals, Tool.Waypoint -> stringResource(Res.string.cost_signal, money(world.price(Prices.SIGNAL)))
                    Tool.Bridge -> stringResource(Res.string.cost_tile, money(world.price(Spans.COST[bridgeStyle])))
                    Tool.Wires, Tool.TramRails -> stringResource(Res.string.cost_tile, money(world.price(Prices.WIRE)))
                    Tool.Road -> stringResource(Res.string.cost_tile, money(world.price(Surface.COST[roadSurface])))
                    Tool.RoadStop -> money(world.price(Prices.ROAD_STOP))
                    Tool.Garage -> money(world.price(Prices.DEPOT))
                    Tool.SaltDepot -> money(world.price(SALT_DEPOT_COST))
                    Tool.Canal -> if (removing) stringResource(Res.string.cost_tile, money(world.price(Prices.CLEAR))) else stringResource(Res.string.cost_tile, money(world.price(CANAL_COST)))
                    Tool.Lock -> money(world.price(LOCK_DOLLARS))
                    Tool.Dock -> money(world.price(DOCK_COST))
                    Tool.Shipyard -> money(world.price(SHIPYARD_COST))
                    Tool.Buoy -> money(world.price(BUOY_COST))
                    Tool.Airport -> money(world.price(Airports.COST[airportKind]))
                    Tool.SnowShed -> stringResource(Res.string.cost_tile, money(world.price(SHED_COST)))
                    Tool.SnowFence -> stringResource(Res.string.cost_tile, money(world.price(FENCE_COST)))
                    Tool.Tunnel -> stringResource(Res.string.cost_tile, money(world.price(Spans.COST[Spans.TUNNEL])))
                    Tool.Station -> stringResource(Res.string.cost_tile, money(world.price(Prices.PLATFORM)))
                    Tool.Depot -> money(world.price(Prices.DEPOT))
                    Tool.Raise, Tool.Lower -> stringResource(Res.string.cost_corner, money(world.price(Prices.TERRAFORM)))
                    else -> null
                }
                val pieceNames = listOf(Res.string.piece_x, Res.string.piece_y, Res.string.piece_n, Res.string.piece_e, Res.string.piece_s, Res.string.piece_w).map { stringResource(it) }
                val trackOptions: (@Composable RowScope.() -> Unit)? = if (tool != Tool.Track) null else {
                    {
                        OptionButton(Glyph.TrackAuto, stringResource(Res.string.track_auto), trackPiece == 0) { trackPiece = 0 }
                        for ((k, piece) in VIEW_PIECES.withIndex()) OptionButton(PIECE_GLYPHS[k], pieceNames[k], trackPiece == piece) { trackPiece = piece }
                    }
                }
                ToolTray(tool, { tool = it; plan = null; stationPlan = null }, detail, folded, { folded = it }, layout.compact, trayWidth, options = trackOptions) {
                    if (tool == Tool.Track || tool == Tool.Wires || tool == Tool.Road || tool == Tool.TramRails || tool == Tool.Canal) TrayToggle(Glyph.Lift, stringResource(Res.string.remove_track), removing) { removing = !removing }
                    if (tool == Tool.Road) {
                        val surfaces = stringArrayResource(Res.array.surfaces)
                        val built = Surface.entries(Calendar.year(world.startYear, day))
                        Chip(surfaces.getOrElse(roadSurface) { "" }, true) { roadSurface = built[(built.indexOf(roadSurface) + 1) % built.size] }
                    }
                    if (tool == Tool.Airport) {
                        val kinds = stringArrayResource(Res.array.airport_kinds)
                        val open = Airports.entries(Calendar.year(world.startYear, day))
                        Chip(kinds.getOrElse(airportKind) { "" }, true) { if (open.isNotEmpty()) airportKind = open[(open.indexOf(airportKind) + 1) % open.size] }
                        if (airportKind != Airports.SEAPLANE && airportKind != Airports.HELIPORT) TrayToggle(Glyph.TurnRight, stringResource(Res.string.runway_other_way), !runwayAlongX) { runwayAlongX = !runwayAlongX }
                    }
                    if (tool == Tool.Station) TrayToggle(Glyph.TurnRight, stringResource(Res.string.platforms_other_way), !stationAlongX) { stationAlongX = !stationAlongX }
                    if (tool == Tool.Bridge) {
                        val styles = stringArrayResource(Res.array.bridge_styles)
                        val year = Calendar.year(world.startYear, day)
                        Chip(styles.getOrElse(bridgeStyle) { "" }, true) {
                            // The next style built this year.
                            var s = bridgeStyle
                            do s = (s + 1) % 3 while (year < Spans.FROM[s] && s != bridgeStyle)
                            bridgeStyle = s
                        }
                    }
                    if (tool == Tool.Signals) {
                        val kinds = stringArrayResource(Res.array.signal_kinds)
                        Chip(kinds.getOrElse(signalKind) { "" }, true) { signalKind = (signalKind + 1) % Signals.PLACED.size }
                    }
                }
            }
            if (!layout.sideTools && me >= 0) ToolBar(tool.group, ::pickGroup, vertical = false, compact = compactTools, modifier = trayWidth)
        }
    }
}

/** What a tap in Inspect picked. */
private sealed interface Selection {
    data class Tile(val x: Int, val y: Int) : Selection
    data class Train(val id: Int) : Selection
    data class Depot(val id: Int) : Selection
    data class Station(val id: Int) : Selection
    data class RoadVehicle(val id: Int) : Selection
    data class Garage(val id: Int) : Selection
    data class Shipyard(val id: Int) : Selection
    data class Airport(val id: Int) : Selection
    data class Ship(val id: Int) : Selection
    data class Aircraft(val id: Int) : Selection
    data object Weather : Selection
}

/** What moves, from the game thread, ready to draw. */
private fun moving(runner: GameThread): Moving? = runner.view?.let { Moving(it, runner.lastView, runner.sinceTick()) }

@Composable
private fun InspectCard(world: World, x: Int, y: Int, onTown: (Int) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val water = world.isWater(x, y)
    val t = world.trackAt(x, y)
    val i = world.index(x, y)
    val grounds = stringArrayResource(Res.array.grounds)
    val lang = languageCode()
    val pieces = listOf(Track.X to Res.string.piece_x, Track.Y to Res.string.piece_y, Track.N to Res.string.piece_n, Track.E to Res.string.piece_e, Track.S to Res.string.piece_s, Track.W to Res.string.piece_w)
    val pieceNames = pieces.filter { t and it.first != 0 }.map { stringResource(it.second) }
    val join = stringResource(Res.string.list_join)
    val levels = stringArrayResource(Res.array.approval_levels)
    val house = world.roster.houses.getOrNull(houseOf(world.house[i].toInt()))
    val road = world.road[i].toInt()
    val town = world.towns.getOrNull(world.townOf[i].toInt())
    val lines = buildList {
        add(stringResource(Res.string.tile_at, x, y))
        if (house != null) {
            add(stringResource(Res.string.built_in, world.houseYear[i].toInt()))
            if (house.residents > 0) add(stringResource(Res.string.residents, house.residents))
            if (house.jobs > 0) add(stringResource(Res.string.jobs, house.jobs))
        } else if (!water) {
            add(stringResource(Res.string.height_of, minOf(world.heightAt(x, y), world.heightAt(x + 1, y), world.heightAt(x + 1, y + 1), world.heightAt(x, y + 1))))
            if (road == 0) add(grounds.getOrElse(world.ground[i].toInt()) { "" })
        }
        if (town != null) {
            add(stringResource(Res.string.town_of, town.name))
            add(stringResource(Res.string.population, Numbers.grouped(town.population.toLong())))
            add(stringResource(Res.string.picked_up, town.servedLastMonth))
            add(stringResource(Res.string.town_standing, levels.getOrElse(approvalLevel(town.approval[0])) { "" }))
        }
        val snow = world.weather.snow[i].toInt() and 255
        val ice = world.weather.ice[i].toInt() and 255
        if (water && ice >= 5) add(stringResource(Res.string.ice_on_water, ice))
        if (snow > 0) add(stringResource(Res.string.snow_on_ground, snow))
        if (!water && world.mud(i) > 0) add(stringResource(Res.string.muddy))
        if (t != 0) {
            add(stringResource(Res.string.track_pieces, pieceNames.joinToString(join)))
            add(stringResource(Res.string.condition_line, (world.condition[i].toInt() and 255) * 100 / 255))
            val limit = world.trackLimit(i)
            if (limit > 0) add(stringResource(Res.string.slow_order, limit))
            world.closed[i]?.let { v -> add(stringResource(if (v shr 16 == Closure.FIRE) Res.string.closed_fire else Res.string.closed_washout, v and 0xffff)) }
            if (world.owner[i].toInt() == LocalCompany.current) add(stringResource(Res.string.owned_by, stringResource(Res.string.company)))
        }
    }
    val industry = world.industryAt(i)
    if (industry != null) {
        IndustryCard(world, industry, lines.first(), onClose, modifier)
        return
    }
    val title = when {
        house != null -> house.name(lang)
        world.canals.contains(i) -> stringResource(Res.string.kind_canal)
        world.locks.containsKey(i) -> stringResource(Res.string.kind_lock)
        road != 0 -> stringResource(if (road and PAVED != 0) Res.string.paved_street else Res.string.dirt_road)
        t != 0 -> stringResource(Res.string.kind_track)
        water -> stringResource(Res.string.kind_water)
        else -> stringResource(Res.string.kind_land)
    }
    InfoCard(title, lines, onClose, modifier) {
        if (town != null) ActionButton(stringResource(Res.string.town_hall)) { onTown(town.id) }
    }
}

/** An industry: what it made last month and how much was carried, and what it takes. */
@Composable
private fun IndustryCard(world: World, ind: Industry, where: String, onClose: () -> Unit, modifier: Modifier) {
    val lang = languageCode()
    val kind = world.roster.industries[ind.kind]
    val names = stringArrayResource(Res.array.cargoes)
    val lines = buildList {
        add(where)
        for (c in 0 until Cargo.COUNT) {
            if (kind.outputs and (1 shl c) == 0) continue
            val made = ind.madeLastMonth[c]
            add(stringResource(Res.string.produced_last_month, names.getOrElse(c) { "" }, made, if (made == 0) 0 else ind.movedLastMonth[c] * 100 / made))
        }
        if (kind.secondary) {
            add(stringResource(Res.string.takes, (0 until Cargo.COUNT).filter { kind.takes(it) }.joinToString(stringResource(Res.string.list_join)) { names.getOrElse(it) { "" }.lowercase() }))
            add(stringResource(Res.string.brought_last_month, ind.broughtLastMonth))
        }
    }
    InfoCard(industryName(world, ind, lang), lines, onClose, modifier)
}

/** The company a piece of news is about, if it's about one: for one bought, the buyer. */
internal fun newsCompany(world: World, n: News): com.rm.interchange.sim.Company? = when (n.kind) {
    News.COMPANY_TROUBLE, News.COMPANY_FOR_SALE, News.COMPANY_BANKRUPT, News.COMPANY_RESTARTED, News.COMPANY_STARTED -> world.companies.getOrNull(n.about)
    News.COMPANY_BOUGHT -> world.companies.getOrNull(n.about shr 8)
    News.SUBSIDY_AWARDED -> world.companies.getOrNull(world.subsidies.getOrNull(n.about)?.company ?: -1)
    else -> null
}

/** What a piece of news says. */
@Composable
internal fun newsText(world: World, n: News): String {
    val me = LocalCompany.current
    if (n.kind == News.COMPANY_RESTARTED) return if (n.about == me) stringResource(Res.string.news_you_restart)
        else stringResource(Res.string.news_company_restart, world.companies.getOrNull(n.about)?.name ?: "")
    if (n.kind == News.COMPANY_STARTED) return if (n.about == me) "" else stringResource(Res.string.news_company_started, world.companies.getOrNull(n.about)?.name ?: "")
    if (n.kind == News.NEW_VEHICLE) {
        val e = world.roster.getOrNull(n.about) ?: return ""
        val lang = languageCode()
        return stringResource(Res.string.news_new_vehicle, e.name(lang), e.type(lang).lowercase())
    }
    if (n.kind in News.COMPANY_TROUBLE..News.COMPANY_BANKRUPT) {
        @Composable fun name(id: Int) = if (id == me) stringResource(Res.string.your_company) else world.companies.getOrNull(id)?.name ?: ""
        return when (n.kind) {
            News.COMPANY_TROUBLE -> if (n.about == me) stringResource(Res.string.news_your_trouble) else stringResource(Res.string.news_company_trouble, name(n.about))
            News.COMPANY_FOR_SALE -> stringResource(Res.string.news_company_for_sale, name(n.about))
            News.COMPANY_BOUGHT -> if (n.about and 255 == me) stringResource(Res.string.news_you_were_bought, name(n.about shr 8))
                else stringResource(Res.string.news_company_bought, name(n.about shr 8), name(n.about and 255))
            else -> stringResource(Res.string.news_company_bankrupt, name(n.about))
        }
    }
    if (n.kind == News.SUBSIDY_OFFERED) return stringResource(Res.string.news_subsidy_offered, subsidyRoute(world, n.about))
    if (n.kind == News.SUBSIDY_AWARDED) {
        val co = world.companies.getOrNull(world.subsidies.getOrNull(n.about)?.company ?: -1)
        val name = if (co == null || co.id == me) stringResource(Res.string.your_company) else co.name
        return stringResource(Res.string.news_subsidy_won, name, subsidyRoute(world, n.about))
    }
    if (n.kind == News.PROSPECTING_FAILED) return stringResource(Res.string.news_prospect_failed, world.roster.industries.getOrNull(n.about)?.name(languageCode()) ?: "")
    if (n.kind == News.WASHOUT || n.kind == News.FIRE || n.kind == News.AVALANCHE) return stringResource(when (n.kind) { News.FIRE -> Res.string.news_fire; News.AVALANCHE -> Res.string.news_avalanche; else -> Res.string.news_washout })
    if (n.kind == News.DIVERTED) {
        if (world.aircraft.getOrNull(n.about)?.owner != me) return ""
        val name = world.aircraft.getOrNull(n.about)?.name?.takeIf { it.isNotEmpty() } ?: stringResource(Res.string.aircraft_n, n.about + 1)
        return stringResource(Res.string.news_diverted, name)
    }
    if (n.kind == News.SLID) {
        if (world.roadVehicles.getOrNull(n.about)?.owner != me) return ""
        val name = world.roadVehicles.getOrNull(n.about)?.name?.takeIf { it.isNotEmpty() } ?: stringResource(Res.string.road_vehicle_n, n.about + 1)
        return stringResource(Res.string.news_slid, name)
    }
    if (n.kind == News.SNOWBOUND || n.kind == News.TRAIN_OLD) {
        if (world.trains.getOrNull(n.about)?.owner != me) return ""
        val name = world.trains.getOrNull(n.about)?.name?.takeIf { it.isNotEmpty() } ?: stringResource(Res.string.train_n, n.about + 1)
        return stringResource(if (n.kind == News.SNOWBOUND) Res.string.news_snowbound else Res.string.news_old, name)
    }
    val ind = world.industries.getOrNull(n.about) ?: return ""
    val name = industryName(world, ind, languageCode())
    return stringResource(
        when (n.kind) {
            News.INDUSTRY_OPENS -> Res.string.news_opens
            News.INDUSTRY_CLOSES -> Res.string.news_closes
            News.PRODUCTION_UP -> Res.string.news_more
            else -> Res.string.news_less
        },
        name,
    )
}

/**
 * An industry's name with its town's, as each language puts it: Elmbrook Coal
 * Mine, or Mine de charbon d'Elmbrook.
 */
/** Subsidy [i]'s route as words: what, from where, to where. */
@Composable
fun subsidyRoute(world: World, i: Int): String {
    val s = world.subsidies.getOrNull(i) ?: return ""
    val lang = languageCode()
    val cargo = stringArrayResource(Res.array.cargoes).getOrElse(s.cargo) { "" }
    fun place(id: Int) = if (s.towns) world.towns.getOrNull(id)?.name ?: "" else world.industries.getOrNull(id)?.let { industryName(world, it, lang) } ?: ""
    return stringResource(Res.string.subsidy_route, cargo, place(s.from), place(s.to))
}

/** Where subsidy [i]'s route starts, as a map spot to go to. */
fun subsidyStart(world: World, i: Int): Pair<Float, Float> {
    val s = world.subsidies.getOrNull(i) ?: return 0f to 0f
    return if (s.towns) world.towns.getOrNull(s.from)?.let { it.x + 0.5f to it.y + 0.5f } ?: (0f to 0f)
    else world.industries.getOrNull(s.from)?.let { it.x + 1f to it.y + 1f } ?: (0f to 0f)
}

fun industryName(world: World, ind: Industry, lang: String): String {
    // A second of a kind by the same town is numbered.
    val n = world.industries.count { it.id < ind.id && it.kind == ind.kind && it.town == ind.town } + 1
    val number = if (n > 1) " $n" else ""
    val kind = world.roster.industries[ind.kind].name(lang)
    val town = world.towns.getOrNull(ind.town)?.name ?: return kind + number
    if (lang != "fr") return "$town $kind$number"
    val vowel = town.firstOrNull()?.lowercaseChar()?.let { it in "aeiouyhéèêàâîôû" } == true
    return (if (vowel) "$kind d'$town" else "$kind de $town") + number
}

/** The widest the trays and the bottom tool bar go. */
private const val TRAY_WIDTH = 480

/**
 * Round a camera in the top edge, in dp: the gap above the strip, the deepest
 * a camera can reach and still sit within the strip's first row, how much
 * deeper it can be with the strip moved down to meet it, and the widest it can
 * be, as a share of the screen, and leave room for the buttons.
 */
private const val CAMERA_GAP = 6
private const val CAMERA_ROW = 54
private const val CAMERA_DEEPER = 24
private const val CAMERA_SHARE = 0.3f

/** How long a piece of news shows under the strip. */
private const val NEWS_MILLIS = 5000L
