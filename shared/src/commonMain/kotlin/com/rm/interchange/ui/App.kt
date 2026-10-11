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
import com.rm.interchange.map.rememberSheets
import com.rm.interchange.map.MapView
import com.rm.interchange.map.Pick
import com.rm.interchange.map.View
import com.rm.interchange.server.Game
import com.rm.interchange.server.GameThread
import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.Prices
import com.rm.interchange.sim.Track
import com.rm.interchange.sim.World
import kotlin.math.roundToInt

@Composable
fun App() {
    CompositionLocalProvider(LocalColors provides DarkColors) { GameScreen() }
}

@Composable
private fun GameScreen() {
    // The game runs on its own thread and makes the world there.
    val runner = remember { GameThread { World(256, 256, 1) }.also { it.start() } }
    DisposableEffect(runner) { onDispose { runner.stop() } }
    var game by remember { mutableStateOf<Game?>(null) }
    // Redraws every display frame, and picks up the game once it's made.
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(runner) {
        while (true) withFrameNanos {
            frame.longValue++
            if (game == null) game = runner.game
        }
    }
    val sheets by rememberSheets()
    val g = game
    val s = sheets
    if (g == null || s == null) {
        Box(Modifier.fillMaxSize().background(LocalColors.current.page))
        return
    }
    GameScreen(runner, g.world, s, frame)
}

@Composable
private fun GameScreen(runner: GameThread, world: World, sheets: List<ImageBitmap>, frame: State<Long>) {
    val c = LocalColors.current
    val view = remember {
        View(world.width, world.height).apply {
            val i = world.landNearMiddle()
            focusX = i % world.width + 0.5f
            focusY = i / world.width + 0.5f
        }
    }
    var paused by remember { mutableStateOf(false) }
    var speed by remember { mutableIntStateOf(0) }
    // What the strip shows, set only when it changes so the screen doesn't compose every frame.
    var day by remember { mutableIntStateOf(runner.day) }
    var money by remember { mutableLongStateOf(runner.money) }
    LaunchedEffect(runner) {
        while (true) withFrameNanos {
            if (runner.day != day) day = runner.day
            if (runner.money != money) money = runner.money
        }
    }

    var tool by remember { mutableStateOf(Tool.Inspect) }
    // The tool last used in each group, so a group's button opens on it.
    val lastTool = remember { mutableStateMapOf<ToolGroup, Tool>() }
    var removing by remember { mutableStateOf(false) }
    // The tray folds to its line once something's built, to show more of the map.
    var folded by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<Command.Rail?>(null) }
    var inspected by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val input = remember(tool, removing) {
        object : MapInput {
            var start: Pick? = null
            override val drags get() = tool == Tool.Track
            override fun tap(at: Pick) {
                when (tool) {
                    Tool.Inspect -> inspected = at.tileX to at.tileY
                    Tool.Track -> { runner.request(trackLine(0, at, at, removing)); folded = true }
                    Tool.Raise, Tool.Lower -> {
                        runner.request(Command.Terraform(0, at.x.roundToInt(), at.y.roundToInt(), tool == Tool.Raise))
                        folded = true
                    }
                    else -> {}
                }
            }
            override fun dragStart(at: Pick) {
                start = at
                plan = trackLine(0, at, at, removing)
            }
            override fun dragTo(at: Pick) {
                start?.let { plan = trackLine(0, it, at, removing) }
            }
            override fun dragEnd() {
                plan?.let { runner.request(it); folded = true }
                plan = null
                start = null
            }
            override fun dragCancel() {
                plan = null
                start = null
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(c.page)) {
        val layout = screenLayout(maxWidth, maxHeight)
        val gap = if (layout.compact) 6.dp else 10.dp
        val compactTools = layout.compact || layout.short || layout.narrow
        val safe = WindowInsets.safeDrawing

        MapView(
            world, sheets, view, { frame.value }, input, Modifier.fillMaxSize(),
            overlay = plan?.let { p -> { painter -> drawTrackPlan(world, painter, p) } },
        )

        StatusStrip(
            paused, { paused = !paused; runner.paused = paused }, speed, { speed = (speed + 1) % SPEEDS.size; runner.speed = SPEEDS[speed] },
            dateText(Calendar.dayOfMonth(day), Calendar.month(day), Calendar.year(world.startYear, day)),
            moneyText(money), money < 0,
            { view.turnBy(-1) }, { view.turnBy(1) },
            compact = layout.compact || layout.narrow,
            twoLines = layout.narrow && layout.shape == ScreenShape.Tall,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(safe.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(gap)
                .then(if (layout.narrow && layout.shape == ScreenShape.Tall) Modifier.fillMaxWidth() else Modifier),
        )

        fun pickGroup(g: ToolGroup) {
            if (tool.group != g) lastTool[tool.group] = tool
            tool = lastTool[g] ?: Tool.of(g).first { it.ready }
            plan = null
            folded = false
            if (g != ToolGroup.Inspect) inspected = null
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

        // Along the bottom: what's being inspected, the tool's tray, and on an upright screen the tools.
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
            inspected?.let { (x, y) -> InspectCard(world, x, y, { inspected = null }, trayWidth) }
            if (tool.group != ToolGroup.Inspect) {
                val detail = when (tool) {
                    Tool.Track -> if (removing) "${moneyText(Prices.TRACK_REMOVE)} a piece to lift" else "${moneyText(Prices.TRACK)} a piece, ${moneyText(Prices.FOUNDATION)} more on a slope"
                    Tool.Raise, Tool.Lower -> "${moneyText(Prices.TERRAFORM)} a corner"
                    else -> null
                }
                ToolTray(tool, { tool = it; plan = null }, detail, folded, { folded = it }, compactTools, trayWidth) {
                    if (tool == Tool.Track) TrayToggle(Glyph.Lift, "Remove track", removing) { removing = !removing }
                }
            }
            if (!layout.sideTools) ToolBar(tool.group, ::pickGroup, vertical = false, compact = compactTools, modifier = trayWidth)
        }
    }
}

@Composable
private fun InspectCard(world: World, x: Int, y: Int, onClose: () -> Unit, modifier: Modifier) {
    val lines = buildList {
        add("Tile $x, $y")
        if (world.isWater(x, y)) add("Water")
        else add("Height ${minOf(world.heightAt(x, y), world.heightAt(x + 1, y), world.heightAt(x + 1, y + 1), world.heightAt(x, y + 1))}")
        val t = world.trackAt(x, y)
        if (t != 0) {
            val names = listOf(Track.X to "along x", Track.Y to "along y", Track.N to "north", Track.E to "east", Track.S to "south", Track.W to "west")
            add("Track: " + names.filter { t and it.first != 0 }.joinToString { it.second })
            world.companies.getOrNull(world.owner[world.index(x, y)].toInt())?.let { add("Owned by ${it.name}") }
        }
    }
    InfoCard(if (world.trackAt(x, y) != 0) "Track" else if (world.isWater(x, y)) "Water" else "Land", lines, onClose, modifier)
}

/** The widest the trays and the bottom tool bar go. */
private const val TRAY_WIDTH = 480
