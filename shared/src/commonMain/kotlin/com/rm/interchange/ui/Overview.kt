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

/** A normalised camera, clamped to the map so zooming and panning leave no empty border. */
internal class OverviewCamera {
    var zoom by mutableFloatStateOf(1f)
        private set
    var x by mutableFloatStateOf(0.5f)
        private set
    var y by mutableFloatStateOf(0.5f)
        private set
    fun fit() { zoom = 1f; x = 0.5f; y = 0.5f }
    fun at(p: Offset) = Offset(x + (p.x - 0.5f) / zoom, y + (p.y - 0.5f) / zoom)
    fun move(pan: Offset, factor: Float = 1f, anchor: Offset = Offset(0.5f, 0.5f)) {
        val before = at(anchor)
        zoom = (zoom * factor).coerceIn(1f, 16f)
        val half = 0.5f / zoom
        x = (before.x - (anchor.x - 0.5f + pan.x) / zoom).coerceIn(half, 1f - half)
        y = (before.y - (anchor.y - 0.5f + pan.y) / zoom).coerceIn(half, 1f - half)
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

/** A north-up map with independently scrolling Kit controls. */
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
    val height = with(LocalDensity.current) { screen.height.toDp() }
    val wide = screen.width > screen.height
    val ratio = view.mapWidth.toFloat() / view.mapHeight
    val mapWidth = (if (wide) (height - 132.dp).coerceAtLeast(100.dp) else (height - 370.dp).coerceAtLeast(100.dp)) * ratio
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
    Window(stringResource(Res.string.overview_map), onClose, Glyph.Map, maxWidth = 920.dp, scrollable = false) {
        BoxWithConstraints {
            val tile = ((if (wide) maxWidth / 2 else maxWidth) - 30.dp).div(6).coerceIn(36.dp, 46.dp)
            val layers: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MapTile(Glyph.Rail, stringResource(Res.string.map_rail), rail, tile) { rail = !rail }
                    MapTile(Glyph.Road, stringResource(Res.string.map_roads), roads, tile) { roads = !roads }
                    MapTile(Glyph.Station, stringResource(Res.string.map_stations), stations, tile) { stations = !stations }
                    MapTile(Glyph.Factory, stringResource(Res.string.industries), industries, tile) { industries = !industries }
                    MapTile(Glyph.Home, stringResource(Res.string.map_town_names), names, tile) { names = !names }
                    MapTile(Glyph.Flag, stringResource(Res.string.map_company_colours), companies, tile) { companies = !companies }
                }
            }
            val trafficTiles: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val labels = listOf(Res.string.map_trains, Res.string.map_road_vehicles, Res.string.map_ships, Res.string.map_aircraft)
                    val glyphs = listOf(Glyph.Train, Glyph.Bus, Glyph.Ship, Glyph.Air)
                    for (mode in 0..3) MapTile(glyphs[mode], stringResource(labels[mode]), modes and (1 shl mode) != 0, tile) { modes = modes xor (1 shl mode) }
                    MapTile(Glyph.Graph, stringResource(Res.string.map_cargo_flow), flow, tile) { flow = !flow }
                    MapTile(Glyph.People, stringResource(Res.string.map_coverage), coverage, tile) { coverage = !coverage }
                }
            }
            val controls: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MapChoice(companyLabel, stringResource(Res.string.company), Glyph.Flag, Modifier.weight(1f), companyIds,
                        { id -> if (id < 0) stringResource(Res.string.map_everyone) else if (id == me) stringResource(Res.string.your_company)
                            else current?.data?.companies?.firstOrNull { it.first == id }?.second.orEmpty() }, filter, colour = { if (it >= 0) companyColour(it) else null }) { owner = it }
                    MapChoice(if (cargo < 0) stringResource(Res.string.map_all_cargo) else cargoNames[cargo], stringResource(Res.string.map_cargo), Glyph.Package,
                        Modifier.weight(1f), listOf(-1) + (0 until Cargo.COUNT),
                        { if (it < 0) stringResource(Res.string.map_all_cargo) else cargoNames[it] }, cargo) { cargo = it }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    MapTile(Glyph.Minus, stringResource(Res.string.map_zoom_out), false, tile, options.camera.zoom > 1f) { options.camera.move(Offset.Zero, 0.5f) }
                    Text("${options.camera.zoom.roundToInt()}×", color = c.textDim, style = KitType.Value)
                    MapTile(Glyph.Plus, stringResource(Res.string.map_zoom_in), false, tile, options.camera.zoom < 16f) { options.camera.move(Offset.Zero, 2f) }
                    MapTile(Glyph.Map, stringResource(Res.string.map_fit), false, tile) { options.camera.fit() }
                    MapTile(Glyph.Info, stringResource(Res.string.map_controls), false, tile) { showHelp = true }
                }
                Row(Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (flow) {
                        val onBoard = current?.data?.info?.flows.orEmpty().filter { (filter < 0 || it.owner == filter) && (cargo < 0 || it.cargo == cargo) }.sumOf { it.count }
                        GlyphIcon(Glyph.Package, c.textDim, Modifier.size(14.dp))
                        val loadLabel = stringResource(Res.string.map_on_board)
                        Text(Numbers.grouped(onBoard), color = c.textDim, style = KitType.Dim,
                            modifier = Modifier.semantics { contentDescription = "$loadLabel: ${Numbers.grouped(onBoard)}" })
                    }
                    Spacer(Modifier.weight(1f))
                    if (coverage) for ((colour, label) in listOf(c.bad to "0%", c.warn to "1-74%", c.good to "75-100%")) {
                        Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(colour))
                        Text(label, color = c.textDim, style = KitType.Dim)
                    }
                }
            }
            SideBySide(wide, first = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!wide) layers()
                    OverviewCanvas(current, traffic, view, viewport, options, filter,
                        Modifier.widthIn(max = mapWidth).fillMaxWidth().aspectRatio(ratio),
                        onIndustry = { selectedIndustry = it }, onClose = onClose)
                    if (!wide) { trafficTiles(); controls() }
                }
            }, second = {
                if (wide) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) { layers(); trafficTiles(); controls() }
            })
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
