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
import androidx.compose.runtime.LaunchedEffect
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
import com.rm.interchange.map.trainAt
import com.rm.interchange.map.Sheets
import com.rm.interchange.map.rememberPackedSheets
import androidx.compose.runtime.rememberCoroutineScope
import com.rm.interchange.map.MapView
import com.rm.interchange.map.Pick
import com.rm.interchange.map.View
import com.rm.interchange.server.Game
import com.rm.interchange.server.GameThread
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
private enum class Win { Menu, Load, Settings, Packs, Overlays, Finances, Industries }

@Composable
private fun Root(settings: Settings) {
    val scope = rememberCoroutineScope()
    val packed by rememberPackedSheets()
    val sheets = remember(packed) { packed?.let { Sheets(it, scope) } }
    var screen by remember { mutableStateOf(Screen.Title) }
    var window by remember { mutableStateOf<Win?>(null) }
    // A world point for the map to go to, set from a window and taken by the game screen.
    val goTo = remember { mutableStateOf<Pair<Float, Float>?>(null) }
    // Saved from the menu since it was opened.
    var saved by remember { mutableStateOf(false) }
    var runner by remember { mutableStateOf<GameThread?>(null) }
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
        Lifecycle.onHide = { runner?.let { SavedGames.save(it, SavedGames.AUTOSAVE) } }
        onDispose { Lifecycle.onHide = null }
    }
    // Autosave follows the setting while a game runs.
    LaunchedEffect(runner, settings.autosave) { runner?.autosaveDays = settings.autosaveDays }

    fun play(make: () -> World) {
        runner?.stop()
        val r = GameThread(make)
        r.autosaveDays = settings.autosaveDays
        r.onAutosave = { bytes -> r.game?.let { SavedGames.writeAutosave(bytes, it.world) } }
        r.start()
        runner = r
        screen = Screen.Playing
        window = null
    }

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

    Box(Modifier.fillMaxSize()) {
        when (screen) {
            Screen.Title -> TitleScreen(
                continueLine = saves.firstOrNull()?.let { date(it.day, it.startYear) + " · " + saveLine(it) },
                onContinue = { saves.firstOrNull()?.let { load(it) } },
                onNew = { screen = Screen.NewGame },
                onLoad = { saves = SavedGames.list(); window = Win.Load },
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
                    Playing(r, s, settings, onMenu = { saved = false; window = Win.Menu }, onOverlays = { window = Win.Overlays }, onFinances = { window = Win.Finances }, goTo = goTo)
                }
            }
        }
        when (window) {
            Win.Menu -> MenuWindow(
                saved,
                onSave = { runner?.let { r -> SavedGames.save(r) { scope.launch { saves = SavedGames.list(); saved = true } } } },
                onLoad = { saves = SavedGames.list(); window = Win.Load },
                onNew = { window = null; screen = Screen.NewGame },
                onSettings = { window = Win.Settings },
                onIndustries = { window = Win.Industries },
                // Back to the title screen the game is saved and stopped; Continue loads it again.
                onTitle = {
                    runner?.let { r ->
                        SavedGames.save(r, SavedGames.AUTOSAVE) {
                            r.stop()
                            scope.launch { saves = SavedGames.list() }
                        }
                    }
                    runner = null
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
            null -> {}
        }
    }
}

/** A game being played: waits for the world to be made, then shows it. */
@Composable
private fun Playing(runner: GameThread, sheets: Sheets, settings: Settings, onMenu: () -> Unit, onOverlays: () -> Unit, onFinances: () -> Unit, goTo: MutableState<Pair<Float, Float>?>) {
    var game by remember(runner) { mutableStateOf<Game?>(null) }
    // Redraws every display frame, and picks up the game once it's made.
    val frame = remember(runner) { mutableLongStateOf(0L) }
    LaunchedEffect(runner) {
        while (true) withFrameNanos {
            frame.longValue++
            if (game == null) game = runner.game
        }
    }
    val g = game
    if (g == null) {
        Box(Modifier.fillMaxSize().background(LocalColors.current.page))
        return
    }
    key(runner) { GameScreen(runner, g.world, sheets, frame, settings, onMenu, onOverlays, onFinances, goTo) }
}

@Composable
private fun GameScreen(runner: GameThread, world: World, sheets: Sheets, frame: State<Long>, settings: Settings, onMenu: () -> Unit, onOverlays: () -> Unit, onFinances: () -> Unit, goTo: MutableState<Pair<Float, Float>?>) {
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
    var mapSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }

    fun built() { folded = true }

    val input = remember(tool, removing, stationAlongX, addingStops, signalKind, bridgeStyle) {
        object : MapInput {
            var start: Pick? = null
            override val drags get() = addingStops == null && (tool == Tool.Track || tool == Tool.Wires || tool == Tool.Station || tool == Tool.Bridge)
            override fun tap(at: Pick) {
                val tile = world.index(at.tileX, at.tileY)
                addingStops?.let { id ->
                    val station = world.stationTiles[tile]
                    val t = worldView.value?.trains?.firstOrNull { it.id == id }
                    if (station != null && t != null) runner.request(Command.TrainOrder(0, id, Command.TrainOrder.ORDERS, t.orders + station))
                    return
                }
                when (tool) {
                    Tool.Inspect -> {
                        val train = trainAt(world, view, moving(runner), at.screenX, at.screenY, mapSize.width, mapSize.height)
                        selected = when {
                            train >= 0 -> Selection.Train(train)
                            world.stationTiles.containsKey(tile) -> Selection.Station(world.stationTiles.getValue(tile))
                            world.depotTiles.containsKey(tile) -> Selection.Depot(world.depotTiles.getValue(tile))
                            else -> Selection.Tile(at.tileX, at.tileY)
                        }
                    }
                    Tool.Track -> { runner.request(trackLine(0, at, at, removing)); built() }
                    Tool.Wires -> { runner.request(trackLine(0, at, at, removing).let { Command.Wire(0, it.sx, it.sy, it.ex, it.ey, it.first, it.remove) }); built() }
                    Tool.Signals -> signalAt(world, 0, at)?.let { runner.request(it.copy(kind = Signals.PLACED[signalKind])); built() }
                    Tool.Waypoint -> { runner.request(Command.Waypoint(0, at.tileX, at.tileY)); built() }
                    Tool.Tunnel -> { runner.request(Command.Tunnel(0, at.tileX, at.tileY)); built() }
                    Tool.Station -> { runner.request(stationPlan(0, at, at, stationAlongX)); built() }
                    Tool.Depot -> {
                        runner.request(depotAt(world, 0, at))
                        built()
                    }
                    Tool.Clear -> { runner.request(Command.Clear(0, at.tileX, at.tileY)); built() }
                    Tool.Raise, Tool.Lower -> {
                        runner.request(Command.Terraform(0, at.x.roundToInt(), at.y.roundToInt(), tool == Tool.Raise))
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
                when (tool) {
                    Tool.Station -> stationPlan = stationPlan(0, s, at, stationAlongX)
                    Tool.Bridge -> bridgePlan = bridgePlan(0, s, at, bridgeStyle)
                    else -> plan = trackLine(0, s, at, removing)
                }
            }
            override fun dragEnd() {
                plan?.let { runner.request(if (tool == Tool.Wires) Command.Wire(0, it.sx, it.sy, it.ex, it.ey, it.first, it.remove) else it); built() }
                stationPlan?.let { runner.request(it); built() }
                bridgePlan?.let { runner.request(it); built() }
                dragCancel()
            }
            override fun dragCancel() {
                plan = null
                stationPlan = null
                bridgePlan = null
                start = null
            }
        }
    }

    val townLabels = rememberTownLabels(c.chrome, c.text, c.textDim)
    // Money as the language writes it, for drawing over the map outside composition.
    val moneyPattern = stringResource(Res.string.money, "%s")
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
                ?: bridgePlan?.let { p -> { painter -> drawBridgePlan(world, painter, p) } },
            overlays = (if (settings.grid) ChunkBaker.GRID else 0) or (if (settings.heights) ChunkBaker.HEIGHTS else 0) or (if (settings.snow) ChunkBaker.SNOW else 0),
            labels = {
                val wv = worldView.value
                with(townLabels) {
                    draw(world, view, wv?.towns.orEmpty()) { Numbers.grouped(it.toLong()) }
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
            settings.grid || settings.heights || settings.snow, onOverlays,
            weekdayDate(day, world.startYear),
            money(money), money < 0,
            { view.turnBy(-1) }, { view.turnBy(1) }, onFinances,
            weather, { selected = if (selected == Selection.Weather) null else Selection.Weather },
            compact = layout.compact || layout.narrow,
            twoLines = twoLines,
            cameras = cameras,
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

        // Under the strip, the latest news; a tap goes to where it happened.
        worldView.value?.news?.lastOrNull()?.let { n ->
            val ind = if (n.kind == News.PROSPECTING_FAILED || n.kind == News.SNOWBOUND || n.kind == News.TRAIN_OLD) null else world.industries.getOrNull(n.about)
            val text = newsText(world, n)
            Box(
                Modifier.align(Alignment.TopCenter)
                    .padding(top = with(density) { stripHeight.toDp() } + gap)
                    .windowInsetsPadding(safe.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = gap),
            ) {
                MessageChip(text) {
                    if (ind != null) {
                        view.focusX = ind.x + 1f
                        view.focusY = ind.y + 1f
                    }
                    if (n.kind == News.SNOWBOUND || n.kind == News.TRAIN_OLD) selected = Selection.Train(n.about)
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
            if (g != ToolGroup.Inspect) selected = null
        }

        if (layout.sideTools) {
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
                is Selection.Tile -> InspectCard(world, sel.x, sel.y, { selected = null }, trayWidth)
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
                is Selection.Station -> {
                    val s = wv?.stations?.firstOrNull { it.id == sel.id }
                    if (s == null || wv == null) selected = null
                    else StationCard(wv, s, { selected = Selection.Train(it) }, { selected = null }, trayWidth)
                }
                null -> {}
            }
            if (tool.group != ToolGroup.Inspect && addingStops == null) {
                val detail = when (tool) {
                    Tool.Track -> if (removing) stringResource(Res.string.cost_lift, money(world.price(Prices.TRACK_REMOVE))) else stringResource(Res.string.cost_track, money(world.price(Prices.TRACK)), money(world.price(Prices.FOUNDATION)))
                    Tool.Signals, Tool.Waypoint -> stringResource(Res.string.cost_signal, money(world.price(Prices.SIGNAL)))
                    Tool.Bridge -> stringResource(Res.string.cost_tile, money(world.price(Spans.COST[bridgeStyle])))
                    Tool.Wires -> stringResource(Res.string.cost_tile, money(world.price(Prices.WIRE)))
                    Tool.Tunnel -> stringResource(Res.string.cost_tile, money(world.price(Spans.COST[Spans.TUNNEL])))
                    Tool.Station -> stringResource(Res.string.cost_tile, money(world.price(Prices.PLATFORM)))
                    Tool.Depot -> money(world.price(Prices.DEPOT))
                    Tool.Clear -> stringResource(Res.string.cost_tile, money(world.price(Prices.CLEAR)))
                    Tool.Raise, Tool.Lower -> stringResource(Res.string.cost_corner, money(world.price(Prices.TERRAFORM)))
                    else -> null
                }
                ToolTray(tool, { tool = it; plan = null; stationPlan = null }, detail, folded, { folded = it }, layout.compact, trayWidth) {
                    if (tool == Tool.Track || tool == Tool.Wires) TrayToggle(Glyph.Lift, stringResource(Res.string.remove_track), removing) { removing = !removing }
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
            if (!layout.sideTools) ToolBar(tool.group, ::pickGroup, vertical = false, compact = compactTools, modifier = trayWidth)
        }
    }
}

/** What a tap in Inspect picked. */
private sealed interface Selection {
    data class Tile(val x: Int, val y: Int) : Selection
    data class Train(val id: Int) : Selection
    data class Depot(val id: Int) : Selection
    data class Station(val id: Int) : Selection
    data object Weather : Selection
}

/** What moves, from the game thread, ready to draw. */
private fun moving(runner: GameThread): Moving? = runner.view?.let { Moving(it, runner.lastView, runner.sinceTick()) }

@Composable
private fun InspectCard(world: World, x: Int, y: Int, onClose: () -> Unit, modifier: Modifier) {
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
            if (world.owner[i].toInt() == 0) add(stringResource(Res.string.owned_by, stringResource(Res.string.company)))
        }
    }
    val industry = world.industryAt(i)
    if (industry != null) {
        IndustryCard(world, industry, lines.first(), onClose, modifier)
        return
    }
    val title = when {
        house != null -> house.name(lang)
        road != 0 -> stringResource(if (road and PAVED != 0) Res.string.paved_street else Res.string.dirt_road)
        t != 0 -> stringResource(Res.string.kind_track)
        water -> stringResource(Res.string.kind_water)
        else -> stringResource(Res.string.kind_land)
    }
    InfoCard(title, lines, onClose, modifier)
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

/** What a piece of news says. */
@Composable
private fun newsText(world: World, n: News): String {
    if (n.kind == News.PROSPECTING_FAILED) return stringResource(Res.string.news_prospect_failed, world.roster.industries.getOrNull(n.about)?.name(languageCode()) ?: "")
    if (n.kind == News.SNOWBOUND || n.kind == News.TRAIN_OLD) {
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
