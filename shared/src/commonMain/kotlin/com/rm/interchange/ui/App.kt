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
private enum class Win { Menu, Load, Settings, Packs, Overlays, Finances, Industries, Companies, Town }

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
                    Playing(r, s, settings, onMenu = { saved = false; window = Win.Menu }, onOverlays = { window = Win.Overlays }, onFinances = { window = Win.Finances }, onTown = { town = it; window = Win.Town }, goTo = goTo)
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
                onCompanies = { window = Win.Companies },
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
            Win.Companies -> runner?.game?.world?.let { w -> CompaniesWindow(w, { x, y -> goTo.value = x to y; window = null }) { window = null } }
            Win.Town -> runner?.game?.world?.let { w -> w.towns.getOrNull(town)?.let { t -> TownWindow(w, t, { runner?.request(it) }) { window = null } } }
            null -> {}
        }
    }
}

/** A game being played: waits for the world to be made, then shows it. */
@Composable
private fun Playing(runner: GameThread, sheets: Sheets, settings: Settings, onMenu: () -> Unit, onOverlays: () -> Unit, onFinances: () -> Unit, onTown: (Int) -> Unit, goTo: MutableState<Pair<Float, Float>?>) {
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
    key(runner) { GameScreen(runner, g.world, sheets, frame, settings, onMenu, onOverlays, onFinances, onTown, goTo) }
}

@Composable
private fun GameScreen(runner: GameThread, world: World, sheets: Sheets, frame: State<Long>, settings: Settings, onMenu: () -> Unit, onOverlays: () -> Unit, onFinances: () -> Unit, onTown: (Int) -> Unit, goTo: MutableState<Pair<Float, Float>?>) {
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
        if (trackPiece == 0 || tool != Tool.Track) trackLine(0, start, end, removing)
        else pieceLine(world, 0, start, end, worldPiece(LandPainter(world, view, view.halfWidth), trackPiece), removing)

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
                    if (station != null && t != null) runner.request(Command.TrainOrder(0, id, Command.TrainOrder.ORDERS, t.orders + station))
                    return
                }
                addingRoadStops?.let { id ->
                    val station = world.roadStops[tile] ?: world.stationTiles[tile]
                    val v = worldView.value?.roadVehicles?.firstOrNull { it.id == id }
                    if (station != null && v != null) runner.request(Command.RoadOrder(0, id, Command.TrainOrder.ORDERS, v.orders + station))
                    return
                }
                addingCraftStops?.let { (air, id) ->
                    val station = if (air) world.airportTiles[tile]?.let { world.airports.getOrNull(it)?.station }
                    else world.docks[tile] ?: world.buoys[tile]
                    if (station != null) {
                        val orders = if (air) worldView.value?.aircraft?.firstOrNull { it.id == id }?.orders else worldView.value?.ships?.firstOrNull { it.id == id }?.orders
                        if (orders != null) runner.request(Command.CraftOrder(0, air, id, Command.TrainOrder.ORDERS, orders + station))
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
                            world.airportTiles.containsKey(tile) -> Selection.Airport(world.airportTiles.getValue(tile))
                            world.docks.containsKey(tile) -> Selection.Station(world.docks.getValue(tile))
                            world.stationTiles.containsKey(tile) -> Selection.Station(world.stationTiles.getValue(tile))
                            world.roadStops.containsKey(tile) -> Selection.Station(world.roadStops.getValue(tile))
                            world.depotTiles.containsKey(tile) -> world.depotTiles.getValue(tile).let {
                                when (world.depots[it]?.kind) {
                                    Depots.ROAD -> Selection.Garage(it)
                                    Depots.SHIPYARD -> Selection.Shipyard(it)
                                    else -> Selection.Depot(it)
                                }
                            }
                            else -> Selection.Tile(at.tileX, at.tileY)
                        }
                    }
                    Tool.Track -> { runner.request(railFor(at, at)); built() }
                    Tool.Road -> { runner.request(roadLine(0, at, at, roadSurface, removing)); built() }
                    Tool.TramRails -> { runner.request(roadLine(0, at, at, roadSurface, removing, tram = true)); built() }
                    Tool.RoadStop -> { runner.request(Command.RoadStop(0, at.tileX, at.tileY)); built() }
                    Tool.Garage -> { runner.request(garageAt(world, 0, at)); built() }
                    Tool.OneWay -> { runner.request(Command.RoadOneWay(0, at.tileX, at.tileY)); built() }
                    Tool.SaltDepot -> { runner.request(Command.SaltDepot(0, at.tileX, at.tileY)); built() }
                    Tool.Canal -> { runner.request(Command.Canal(0, at.tileX, at.tileY, at.tileX, at.tileY, removing)); built() }
                    Tool.Lock -> { runner.request(Command.Lock(0, at.tileX, at.tileY)); built() }
                    Tool.Dock -> { runner.request(Command.Dock(0, at.tileX, at.tileY)); built() }
                    Tool.Shipyard -> { runner.request(Command.Shipyard(0, at.tileX, at.tileY)); built() }
                    Tool.Buoy -> { runner.request(Command.Buoy(0, at.tileX, at.tileY)); built() }
                    Tool.Airport -> { runner.request(Command.Airport(0, at.tileX, at.tileY, airportKind, if (runwayAlongX) Track.X else Track.Y)); built() }
                    Tool.Wires -> { runner.request(trackLine(0, at, at, removing).let { Command.Wire(0, it.sx, it.sy, it.ex, it.ey, it.first, it.remove) }); built() }
                    Tool.Signals -> signalAt(world, 0, at)?.let { runner.request(it.copy(kind = Signals.PLACED[signalKind])); built() }
                    Tool.Waypoint -> { runner.request(Command.Waypoint(0, at.tileX, at.tileY)); built() }
                    Tool.Tunnel -> { runner.request(Command.Tunnel(0, at.tileX, at.tileY)); built() }
                    Tool.SnowShed, Tool.SnowFence -> { runner.request(Command.SnowWorks(0, at.tileX, at.tileY, shed = tool == Tool.SnowShed)); built() }
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
                dragAt = at
                when (tool) {
                    Tool.Road -> roadPlan = roadLine(0, s, at, roadSurface, removing)
                    Tool.TramRails -> roadPlan = roadLine(0, s, at, roadSurface, removing, tram = true)
                    Tool.Station -> stationPlan = stationPlan(0, s, at, stationAlongX)
                    Tool.Bridge -> bridgePlan = bridgePlan(0, s, at, bridgeStyle)
                    Tool.Canal -> canalPlan = roadLine(0, s, at, 0, removing).let { Command.Canal(0, it.sx, it.sy, it.ex, it.ey, removing) }
                    Tool.Clear -> clearPlan = Command.ClearArea(0, s.tileX, s.tileY, at.tileX, at.tileY)
                    else -> plan = railFor(s, at)
                }
            }
            override fun dragEnd() {
                plan?.let { runner.request(if (tool == Tool.Wires) Command.Wire(0, it.sx, it.sy, it.ex, it.ey, it.first, it.remove) else it); built() }
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
    val company = world.companies[0]
    val dragged: Pair<Int, Long>? = plan?.let { p ->
        if (tool == Tool.Wires) Track.line(world, p.sx, p.sy, p.ex, p.ey, p.first)?.let { it.size to it.size * world.price(Prices.WIRE) }
        else world.railCost(p).let { (cost, n) -> if (cost < 0) null else n to cost }
    } ?: roadPlan?.let { p -> world.roadCost(company, p).takeIf { it >= 0 }?.let { (maxOf(kotlin.math.abs(p.ex - p.sx), kotlin.math.abs(p.ey - p.sy)) + 1) to it } }
        ?: canalPlan?.let { p -> world.canalCost(company, p).let { (cost, n) -> n to cost } }
        ?: clearPlan?.let { p -> world.clearCost(0, p).let { (cost, n) -> n to cost } }
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
                MessageChip(text) {
                    if (ind != null) {
                        view.focusX = ind.x + 1f
                        view.focusY = ind.y + 1f
                    }
                    if (n.kind == News.SNOWBOUND || n.kind == News.TRAIN_OLD) selected = Selection.Train(n.about)
                    if (n.kind == News.SLID) selected = Selection.RoadVehicle(n.about)
                    if (n.kind == News.DIVERTED) selected = Selection.Aircraft(n.about)
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

/** What a piece of news says. */
@Composable
private fun newsText(world: World, n: News): String {
    if (n.kind == News.PROSPECTING_FAILED) return stringResource(Res.string.news_prospect_failed, world.roster.industries.getOrNull(n.about)?.name(languageCode()) ?: "")
    if (n.kind == News.WASHOUT || n.kind == News.FIRE || n.kind == News.AVALANCHE) return stringResource(when (n.kind) { News.FIRE -> Res.string.news_fire; News.AVALANCHE -> Res.string.news_avalanche; else -> Res.string.news_washout })
    if (n.kind == News.DIVERTED) {
        val name = world.aircraft.getOrNull(n.about)?.name?.takeIf { it.isNotEmpty() } ?: stringResource(Res.string.aircraft_n, n.about + 1)
        return stringResource(Res.string.news_diverted, name)
    }
    if (n.kind == News.SLID) {
        val name = world.roadVehicles.getOrNull(n.about)?.name?.takeIf { it.isNotEmpty() } ?: stringResource(Res.string.road_vehicle_n, n.about + 1)
        return stringResource(Res.string.news_slid, name)
    }
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

/** How long a piece of news shows under the strip. */
private const val NEWS_MILLIS = 5000L
