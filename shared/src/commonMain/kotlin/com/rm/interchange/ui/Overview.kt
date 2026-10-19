package com.rm.interchange.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rm.interchange.map.View
import com.rm.interchange.map.companyColour
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
    val mapWidth = (if (wide) (height - 230.dp).coerceAtLeast(120.dp) else height * 0.38f) * ratio
    val controlsHeight = if (wide) (height - 128.dp).coerceAtLeast(120.dp) else (height * 0.28f).coerceIn(140.dp, 300.dp)
    val current = image
    val companyIds = listOf(-1) + current?.data?.companies.orEmpty().map { it.first }
    val filter = owner.takeIf { it in companyIds } ?: -1
    val cargoNames = stringArrayResource(Res.array.cargoes)
    val lang = languageCode()
    val me = LocalCompany.current
    Window(stringResource(Res.string.overview_map), onClose, Glyph.Map, maxWidth = 720.dp, scrollable = false) {
        SideBySide(wide, first = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OverviewCanvas(current, traffic, view, viewport, options, filter,
                    Modifier.widthIn(max = mapWidth).fillMaxWidth().aspectRatio(ratio),
                    onIndustry = { selectedIndustry = it }, onClose = onClose)
                Text(stringResource(Res.string.map_help), color = c.textDim, style = KitType.Dim)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SquareButton(Glyph.Minus, stringResource(Res.string.map_zoom_out), 32.dp, enabled = options.camera.zoom > 1f) { options.camera.move(Offset.Zero, 0.5f) }
                    Text("${options.camera.zoom.roundToInt()}×", color = c.textDim, style = KitType.Value)
                    SquareButton(Glyph.Plus, stringResource(Res.string.map_zoom_in), 32.dp, enabled = options.camera.zoom < 16f) { options.camera.move(Offset.Zero, 2f) }
                    TogglePill(stringResource(Res.string.map_fit), false) { options.camera.fit() }
                }
            }
        }, second = {
            Column(Modifier.fillMaxWidth().heightIn(max = controlsHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StepSlider(stringResource(Res.string.company), companyIds, filter, { id ->
                    when (id) {
                        -1 -> stringResource(Res.string.map_everyone)
                        me -> stringResource(Res.string.your_company)
                        else -> current?.data?.companies?.firstOrNull { it.first == id }?.second.orEmpty()
                    }
                }) { owner = it }
                Head(stringResource(Res.string.overlays), Glyph.Layers)
                Grid(listOf(0, 1, 2, 3, 4, 5)) { option ->
                    when (option) {
                        0 -> TogglePill(stringResource(Res.string.map_rail), rail, Modifier.fillMaxWidth()) { rail = it }
                        1 -> TogglePill(stringResource(Res.string.map_roads), roads, Modifier.fillMaxWidth()) { roads = it }
                        2 -> TogglePill(stringResource(Res.string.map_stations), stations, Modifier.fillMaxWidth()) { stations = it }
                        3 -> TogglePill(stringResource(Res.string.industries), industries, Modifier.fillMaxWidth()) { industries = it }
                        4 -> TogglePill(stringResource(Res.string.map_town_names), names, Modifier.fillMaxWidth()) { names = it }
                        else -> TogglePill(stringResource(Res.string.map_company_colours), companies, Modifier.fillMaxWidth()) { companies = it }
                    }
                }
                Head(stringResource(Res.string.map_vehicles), Glyph.Inspect)
                Grid((0..3).toList()) { mode ->
                    val labels = listOf(Res.string.map_trains, Res.string.map_road_vehicles, Res.string.map_ships, Res.string.map_aircraft)
                    TogglePill(stringResource(labels[mode]), modes and (1 shl mode) != 0, Modifier.fillMaxWidth()) { enabled ->
                        modes = if (enabled) modes or (1 shl mode) else modes and (1 shl mode).inv()
                    }
                }
                Head(stringResource(Res.string.map_planning), Glyph.Graph)
                TogglePill(stringResource(Res.string.map_cargo_flow), flow, Modifier.fillMaxWidth()) { flow = it }
                if (flow) {
                    StepSlider(stringResource(Res.string.map_cargo), listOf(-1) + (0 until Cargo.COUNT), cargo,
                        { if (it < 0) stringResource(Res.string.map_all_cargo) else cargoNames[it] }) { cargo = it }
                    val onBoard = current?.data?.info?.flows.orEmpty().filter { (filter < 0 || it.owner == filter) && (cargo < 0 || it.cargo == cargo) }.sumOf { it.count }
                    InfoRow(stringResource(Res.string.map_on_board), Numbers.grouped(onBoard), Glyph.Package)
                }
                TogglePill(stringResource(Res.string.map_coverage), coverage, Modifier.fillMaxWidth()) { coverage = it }
                if (coverage) {
                    Text(stringResource(Res.string.map_coverage_help), color = c.textDim, style = KitType.Dim)
                    InfoRow(stringResource(Res.string.map_unserved), "0%", Glyph.Alert, c.bad)
                    InfoRow(stringResource(Res.string.map_partial), "1-74%", Glyph.People, c.warn)
                    InfoRow(stringResource(Res.string.map_served), "75-100%", Glyph.Tick, c.good)
                }
                if (companies) Grid(current?.data?.companies.orEmpty()) { (id, name) ->
                    Row(Modifier.clip(RoundedCornerShape(6.dp)).background(if (filter == id) c.button else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { owner = if (filter == id) -1 else id }.padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(companyColour(id)))
                        Text(name, color = if (filter == id) c.text else c.textDim, style = KitType.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        })
    }
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
