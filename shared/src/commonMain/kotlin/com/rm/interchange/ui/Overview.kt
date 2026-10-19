package com.rm.interchange.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.rm.interchange.map.companyColour
import com.rm.interchange.map.View
import com.rm.interchange.res.*
import com.rm.interchange.server.GameThread
import com.rm.interchange.sim.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** A proportional camera for any canvas shape. Fit shows the whole world; normal zoom fills the canvas. */
internal class OverviewCamera {
    var zoom by mutableFloatStateOf(1f)
        private set
    var x by mutableFloatStateOf(0.5f)
        private set
    var y by mutableFloatStateOf(0.5f)
        private set
    var extent by mutableStateOf(Size(1f, 1f))
        private set
    val minZoom get() = minOf(extent.width, extent.height)
    fun resize(canvas: Size, worldWidth: Int, worldHeight: Int) {
        val scale = maxOf(canvas.width / worldWidth, canvas.height / worldHeight)
        if (scale <= 0f) return
        extent = Size(canvas.width / (worldWidth * scale), canvas.height / (worldHeight * scale))
        move(Offset.Zero)
    }
    fun fit() { zoom = minZoom; x = 0.5f; y = 0.5f }
    fun at(p: Offset) = Offset(x + (p.x - 0.5f) * extent.width / zoom, y + (p.y - 0.5f) * extent.height / zoom)
    fun move(pan: Offset, factor: Float = 1f, anchor: Offset = Offset(0.5f, 0.5f)) {
        val before = at(anchor)
        zoom = (zoom * factor).coerceIn(minZoom, 16f)
        fun bound(value: Float, span: Float): Float {
            val half = span / (2 * zoom)
            return if (half >= 0.5f) 0.5f else value.coerceIn(half, 1f - half)
        }
        x = bound(before.x - (anchor.x - 0.5f + pan.x) * extent.width / zoom, extent.width)
        y = bound(before.y - (anchor.y - 0.5f + pan.y) * extent.height / zoom, extent.height)
    }
}

/** Filters and camera kept between visits while this game screen is open. */
internal class OverviewOptions {
    val camera = OverviewCamera()
    val rail = mutableStateOf(true)
    val roads = mutableStateOf(true)
    val stations = mutableStateOf(true)
    val industries = mutableStateOf(true)
    val names = mutableStateOf(false)
    val companies = mutableStateOf(true)
    val vehicleModes = mutableIntStateOf(15)
    val owner = mutableIntStateOf(-1)
    val flows = mutableStateOf(false)
    val cargo = mutableIntStateOf(-1)
    val coverage = mutableStateOf(false)
}

private suspend fun <T> mapRead(runner: GameThread, read: (World) -> T): T = suspendCancellableCoroutine { continuation ->
    runner.onGameThread { game -> if (continuation.isActive) continuation.resume(read(game.world)) }
}

/** A square north-up map with compact controls in either orientation. */
@Composable
internal fun OverviewWindow(runner: GameThread, view: View, viewport: Size, options: OverviewOptions, onClose: () -> Unit) {
    val c = LocalColors.current
    var image by remember(runner) { mutableStateOf<MapImage?>(null) }
    val traffic = remember(runner) { mutableStateOf<List<OverviewVehicle>>(emptyList()) }
    var selectedIndustry by remember { mutableIntStateOf(-1) }
    var showHelp by remember { mutableStateOf(false) }
    var rail by options.rail
    var roads by options.roads
    var stations by options.stations
    var industries by options.industries
    var names by options.names
    var companies by options.companies
    var modes by options.vehicleModes
    var owner by options.owner
    var flow by options.flows
    var cargo by options.cargo
    var coverage by options.coverage
    LaunchedEffect(runner) {
        while (true) {
            val snapshot = mapRead(runner) { MapSnapshot(it) }
            image = withContext(Dispatchers.Default) { MapImage(snapshot) }
            delay(2_000)
        }
    }
    LaunchedEffect(runner, modes) {
        traffic.value = emptyList()
        while (modes != 0) {
            traffic.value = mapRead(runner) { it.overviewVehicles() }
            delay(100)
        }
    }
    val screen = LocalWindowInfo.current.containerSize
    val wide = screen.width > screen.height
    val current = image
    val companyIds = listOf(-1) + current?.data?.companies.orEmpty().map { it.first }
    val filter = owner.takeIf { it in companyIds } ?: -1
    val cargoNames = stringArrayResource(Res.array.cargoes)
    val lang = languageCode()
    val me = LocalCompany.current
    val companyLabel = when (filter) {
        -1 -> stringResource(Res.string.map_everyone)
        me -> stringResource(Res.string.your_company)
        else -> current?.data?.companies?.firstOrNull { it.first == filter }?.second.orEmpty()
    }
    val windowWidth = with(LocalDensity.current) { if (wide) minOf(screen.width.toDp(), screen.height.toDp() + 100.dp) else 480.dp }
    Window(stringResource(Res.string.overview_map), onClose, Glyph.Map, maxWidth = windowWidth, scrollable = false) {
        BoxWithConstraints {
            val tile = 34.dp
            val layers: @Composable () -> Unit = {
                MapTileGroup(wide) {
                    MapTile(Glyph.Rail, stringResource(Res.string.map_rail), rail, tile) { rail = !rail }
                    MapTile(Glyph.Road, stringResource(Res.string.map_roads), roads, tile) { roads = !roads }
                    MapTile(Glyph.Station, stringResource(Res.string.map_stations), stations, tile) { stations = !stations }
                    MapTile(Glyph.Factory, stringResource(Res.string.industries), industries, tile) { industries = !industries }
                    MapTile(Glyph.Home, stringResource(Res.string.map_town_names), names, tile) { names = !names }
                    MapTile(Glyph.Flag, stringResource(Res.string.map_company_colours), companies, tile) { companies = !companies }
                }
            }
            val trafficTiles: @Composable () -> Unit = {
                MapTileGroup(wide) {
                    val labels = listOf(Res.string.map_trains, Res.string.map_road_vehicles, Res.string.map_ships, Res.string.map_aircraft)
                    val glyphs = listOf(Glyph.Train, Glyph.Bus, Glyph.Ship, Glyph.Air)
                    for (mode in 0..3) MapTile(glyphs[mode], stringResource(labels[mode]), modes and (1 shl mode) != 0, tile) { modes = modes xor (1 shl mode) }
                    MapTile(Glyph.Graph, stringResource(Res.string.map_cargo_flow), flow, tile) { flow = !flow }
                    MapTile(Glyph.People, stringResource(Res.string.map_coverage), coverage, tile) { coverage = !coverage }
                }
            }
            val companyPicker: @Composable (Modifier) -> Unit = { modifier ->
                MapChoice(companyLabel, stringResource(Res.string.company), Glyph.Flag, modifier, companyIds,
                    { id -> if (id < 0) stringResource(Res.string.map_everyone) else if (id == me) stringResource(Res.string.your_company)
                        else current?.data?.companies?.firstOrNull { it.first == id }?.second.orEmpty() }, filter, colour = { if (it >= 0) companyColour(it) else null }) { owner = it }
            }
            val cargoPicker: @Composable (Modifier) -> Unit = { modifier ->
                MapChoice(if (cargo < 0) stringResource(Res.string.map_all_cargo) else cargoNames[cargo], stringResource(Res.string.map_cargo), Glyph.Package,
                    modifier, listOf(-1) + (0 until Cargo.COUNT),
                    { if (it < 0) stringResource(Res.string.map_all_cargo) else cargoNames[it] }, cargo) { cargo = it }
            }
            val navigation: @Composable () -> Unit = {
                MapTile(Glyph.Minus, stringResource(Res.string.map_zoom_out), false, tile, options.camera.zoom > options.camera.minZoom) { options.camera.move(Offset.Zero, 0.5f) }
                Text("${(options.camera.zoom * 100).roundToInt()}%", color = c.textDim, style = KitType.Value)
                MapTile(Glyph.Plus, stringResource(Res.string.map_zoom_in), false, tile, options.camera.zoom < 16f) { options.camera.move(Offset.Zero, 2f) }
                MapTile(Glyph.Map, stringResource(Res.string.map_fit), false, tile) { options.camera.fit() }
                MapTile(Glyph.Info, stringResource(Res.string.map_controls), false, tile) { showHelp = true }
            }
            val map: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier) {
                    OverviewCanvas(current, traffic, view, viewport, options, filter, Modifier.fillMaxSize(),
                        onIndustry = { selectedIndustry = it }, onClose = onClose)
                    if (flow) {
                        val onBoard = current?.data?.info?.flows.orEmpty().filter { (filter < 0 || it.owner == filter) && (cargo < 0 || it.cargo == cargo) }.sumOf { it.count }
                        val loadLabel = stringResource(Res.string.map_on_board)
                        Row(Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(6.dp))
                            .background(c.chrome.copy(alpha = 0.92f)).padding(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            GlyphIcon(Glyph.Package, c.textDim, Modifier.size(14.dp))
                            Text(Numbers.grouped(onBoard), color = c.textDim, style = KitType.Dim,
                                modifier = Modifier.semantics { contentDescription = "$loadLabel: ${Numbers.grouped(onBoard)}" })
                        }
                    }
                    if (coverage) Row(Modifier.align(Alignment.BottomCenter).padding(6.dp).clip(RoundedCornerShape(6.dp))
                        .background(c.chrome.copy(alpha = 0.92f)).padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        for ((colour, label) in listOf(c.bad to "0%", c.warn to "1-74%", c.good to "75%+")) {
                            Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(colour))
                            Text(label, color = c.textDim, style = KitType.Dim, maxLines = 1)
                        }
                    }
                }
            }
            val mapSide = if (wide) minOf(maxHeight - 48.dp, maxWidth - 216.dp) else minOf(maxWidth, maxHeight - 168.dp)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (wide) {
                    Row(Modifier.fillMaxWidth().height(mapSide), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            layers()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                MapTile(Glyph.Minus, stringResource(Res.string.map_zoom_out), false, tile, options.camera.zoom > options.camera.minZoom) { options.camera.move(Offset.Zero, 0.5f) }
                                Text("${(options.camera.zoom * 100).roundToInt()}%", color = c.textDim, style = KitType.Dim, modifier = Modifier.weight(1f), maxLines = 1)
                                MapTile(Glyph.Plus, stringResource(Res.string.map_zoom_in), false, tile, options.camera.zoom < 16f) { options.camera.move(Offset.Zero, 2f) }
                            }
                        }
                        map(Modifier.size(mapSide))
                        Column(Modifier.width(100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            trafficTiles()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                MapTile(Glyph.Map, stringResource(Res.string.map_fit), false, tile) { options.camera.fit() }
                                MapTile(Glyph.Info, stringResource(Res.string.map_controls), false, tile) { showHelp = true }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        companyPicker(Modifier.weight(1f))
                        cargoPicker(Modifier.weight(1f))
                    }
                } else {
                    layers()
                    map(Modifier.size(mapSide).align(Alignment.CenterHorizontally))
                    trafficTiles()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        companyPicker(Modifier.weight(1f))
                        cargoPicker(Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { navigation() }
                }
            }
        }
    }
    if (showHelp) MapControlsHelp { showHelp = false }
    current?.data?.industries?.firstOrNull { it.place.id == selectedIndustry }?.let { industry ->
        val info = industry.info
        val slot = if (filter < 0) OVERVIEW_ALL else filter
        Window(industry.name(lang), { selectedIndustry = -1 }, Glyph.Factory) {
            PictureRow(industry.kind.name(lang), picture = { IndustryPicture(industry.kind, it) })
            Head(stringResource(Res.string.head_made), Glyph.Up)
            for (cg in 0 until Cargo.COUNT) if (industry.kind.outputs and (1 shl cg) != 0) {
                val made = info.made[cg]
                CargoRow(cg, Numbers.grouped(made.toLong()), share = if (made > 0) info.moved[cg].toFloat() / made else 0f)
            }
            if (industry.kind.accepts != 0) {
                Head(stringResource(Res.string.head_takes), Glyph.Down)
                for (cg in 0 until Cargo.COUNT) if (industry.kind.takes(cg)) CargoRow(cg, "")
                InfoRow(stringResource(Res.string.map_input_stock), Numbers.grouped(info.stock.toLong()), Glyph.Package)
            }
            Head(stringResource(Res.string.map_nearby_waiting), Glyph.Station)
            for (cg in 0 until Cargo.COUNT) if (industry.kind.outputs and (1 shl cg) != 0) CargoRow(cg, Numbers.grouped(info.waiting[slot][cg].toLong()))
            InfoRow(stringResource(Res.string.map_coverage), "${info.coverage[slot]}%", Glyph.Station)
            BigButton(stringResource(Res.string.map_go_to), primary = true, glyph = Glyph.Inspect) {
                view.focusX = industry.place.x
                view.focusY = industry.place.y
                onClose()
            }
        }
    }
}
