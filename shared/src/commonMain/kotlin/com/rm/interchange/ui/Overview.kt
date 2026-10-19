package com.rm.interchange.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.rm.interchange.map.View
import com.rm.interchange.map.companyColour
import com.rm.interchange.map.imageBitmapOf
import com.rm.interchange.map.terrainColour
import com.rm.interchange.res.*
import com.rm.interchange.server.GameThread
import com.rm.interchange.sim.Track
import com.rm.interchange.sim.Trees
import com.rm.interchange.sim.World
import com.rm.interchange.sim.roadBit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import kotlin.coroutines.resume

private data class MapPlace(val x: Float, val y: Float, val owner: Int = -1, val name: String = "", val population: Int = 0)
private data class MapSpan(val x: Int, val y: Int, val ex: Int, val ey: Int, val owner: Int)

/** Arrays and positions copied between ticks; rasterising and making paths run off the game thread. */
private class MapSnapshot(w: World) {
    val width = w.width
    val height = w.height
    val corners = w.corners.copyOf()
    val ground = w.ground.copyOf()
    val trees = w.trees.copyOf()
    val rail = w.track.copyOf()
    val roads = w.road.copyOf()
    val owner = w.owner.copyOf()
    val roadOwner = w.roadOwner.copyOf()
    val canals = w.canals.toHashSet()
    val spans = w.spans.filterNotNull().map { MapSpan(it.x0, it.y0, it.x1, it.y1, it.owner) }
    val towns = w.towns.map { MapPlace(it.x + 0.5f, it.y + 0.5f, name = it.name, population = it.population) }.sortedByDescending { it.population }
    val stations = w.stations.filterNotNull().map { MapPlace(it.signX + 0.5f, it.signY + 0.5f, it.owner) }
    val industries = w.industries.filter { it.level > 0 }.map { MapPlace(it.x + 1f, it.y + 1f) }
    val companies = w.companies.filter { !it.gone }.map { it.id to it.name }
}

private class MapImage(val data: MapSnapshot) {
    val terrain: ImageBitmap
    val rail = linkedMapOf<Int, Path>()
    val roads = linkedMapOf<Int, Path>()
    init {
        val w = data.width
        val pixels = IntArray(w * data.height)
        for (i in pixels.indices) {
            val corner = i / w * (w + 1) + i % w
            pixels[i] = if (i in data.canals) terrainColour(0, 0, 0, 0, 0, 0) else terrainColour(
                data.corners[corner].toInt(), data.corners[corner + 1].toInt(),
                data.corners[corner + w + 2].toInt(), data.corners[corner + w + 1].toInt(),
                data.ground[i].toInt(), Trees.count(data.trees[i].toInt()),
            )
            val x = i % w + 0.5f
            val y = i / w + 0.5f
            for (p in Track.PIECES) if (data.rail[i].toInt() and p != 0) {
                val edges = Track.edges(p)
                val a = edges and 3
                val b = edges shr 2
                rail.getOrPut(data.owner[i].toInt()) { Path() }.apply {
                    moveTo(x + Track.dx(a) * 0.5f, y + Track.dy(a) * 0.5f)
                    lineTo(x + Track.dx(b) * 0.5f, y + Track.dy(b) * 0.5f)
                }
            }
            for (e in 0 until 4) if (data.roads[i].toInt() and roadBit(e) != 0) {
                roads.getOrPut(data.roadOwner[i].toInt()) { Path() }.apply {
                    moveTo(x, y)
                    lineTo(x + Track.dx(e) * 0.5f, y + Track.dy(e) * 0.5f)
                }
            }
        }
        for (s in data.spans) rail.getOrPut(s.owner) { Path() }.apply {
            moveTo(s.x + 0.5f, s.y + 0.5f)
            lineTo(s.ex + 0.5f, s.ey + 0.5f)
        }
        terrain = imageBitmapOf(pixels, data.width, data.height)
    }
}

/** Filters kept while this game screen is open, including between visits to the map. */
internal class OverviewOptions {
    val rail = mutableStateOf(true)
    val roads = mutableStateOf(true)
    val stations = mutableStateOf(true)
    val industries = mutableStateOf(true)
    val names = mutableStateOf(false)
    val companies = mutableStateOf(true)
}

/** A north-up map in the same window and controls as the game's lists and settings. */
@Composable
internal fun OverviewWindow(runner: GameThread, view: View, viewport: Size, options: OverviewOptions, onClose: () -> Unit) {
    val c = LocalColors.current
    var image by remember(runner) { mutableStateOf<MapImage?>(null) }
    var rail by options.rail
    var roads by options.roads
    var stations by options.stations
    var industries by options.industries
    var names by options.names
    var companies by options.companies
    LaunchedEffect(runner) {
        while (true) {
            val snapshot = suspendCancellableCoroutine<MapSnapshot> { continuation ->
                runner.onGameThread { game ->
                    if (continuation.isActive) continuation.resume(MapSnapshot(game.world))
                }
            }
            image = withContext(Dispatchers.Default) { MapImage(snapshot) }
            delay(2_000)
        }
    }
    val title = stringResource(Res.string.overview_map)
    val measurer = rememberTextMeasurer()
    val screen = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val wide = screen.width > screen.height
    val ratio = view.mapWidth.toFloat() / view.mapHeight
    val mapWidth = with(density) { screen.height.toDp() }.let { if (wide) (it - 128.dp).coerceAtLeast(120.dp) else it * 0.55f } * ratio
    Window(title, onClose, Glyph.Map, maxWidth = 720.dp) {
        val current = image
        val shape = RoundedCornerShape(10.dp)
        SideBySide(wide, first = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Canvas(
                    Modifier.widthIn(max = mapWidth).fillMaxWidth().aspectRatio(ratio).clip(shape).background(c.page).border(1.dp, c.chromeEdge, shape)
                        .semantics { contentDescription = title }
                        .pointerInput(view, onClose) {
                            detectTapGestures { at ->
                                view.focusX = (at.x / size.width * view.mapWidth).coerceIn(0f, view.mapWidth.toFloat())
                                view.focusY = (at.y / size.height * view.mapHeight).coerceIn(0f, view.mapHeight.toFloat())
                                onClose()
                            }
                        },
                ) {
                    if (current == null) return@Canvas
                    val data = current.data
                    fun point(x: Float, y: Float) = Offset(x / data.width * size.width, y / data.height * size.height)
                    fun tint(owner: Int, fallback: Color) = if (companies && owner >= 0) companyColour(owner) else fallback
                    drawImage(current.terrain, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                    clipRect {
                        val scale = size.width / data.width
                        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
                            if (roads) for ((owner, path) in current.roads) drawPath(path, tint(owner, c.textDim), style = Stroke(1.dp.toPx() / scale))
                            if (rail) for ((owner, path) in current.rail) drawPath(path, tint(owner, c.accent), style = Stroke(1.5.dp.toPx() / scale))
                        }
                        if (industries) for (p in data.industries) {
                            val at = point(p.x, p.y)
                            drawRect(c.page, at - Offset(3.dp.toPx(), 3.dp.toPx()), Size(6.dp.toPx(), 6.dp.toPx()))
                            drawRect(c.warn, at - Offset(2.dp.toPx(), 2.dp.toPx()), Size(4.dp.toPx(), 4.dp.toPx()))
                        }
                        if (stations) for (p in data.stations) {
                            val at = point(p.x, p.y)
                            drawCircle(c.page, 3.dp.toPx(), at)
                            drawCircle(tint(p.owner, c.text), 2.dp.toPx(), at)
                        }
                        val occupied = ArrayList<Rect>()
                        for (p in data.towns) {
                            val at = point(p.x, p.y)
                            drawCircle(c.page, 2.5.dp.toPx(), at)
                            drawCircle(c.text, 1.5.dp.toPx(), at)
                            if (names) {
                                val text = measurer.measure(p.name, KitType.Dim.copy(color = c.text))
                                val x = (at.x + 4.dp.toPx()).coerceIn(0f, (size.width - text.size.width).coerceAtLeast(0f))
                                val y = (at.y - text.size.height / 2).coerceIn(0f, (size.height - text.size.height).coerceAtLeast(0f))
                                val rect = Rect(Offset(x, y), Size(text.size.width.toFloat(), text.size.height.toFloat()))
                                if (occupied.none { it.overlaps(rect) }) {
                                    drawRect(c.chrome, rect.topLeft, rect.size)
                                    drawText(text, topLeft = rect.topLeft)
                                    occupied += rect.inflate(2.dp.toPx())
                                }
                            }
                        }
                        // Project the game screen's four corners back onto the ground plane, including camera rotation.
                        val outline = Path()
                        for ((i, corner) in listOf(Offset.Zero, Offset(viewport.width, 0f), Offset(viewport.width, viewport.height), Offset(0f, viewport.height)).withIndex()) {
                            val dx = (corner.x - viewport.width / 2) / view.halfWidth
                            val dy = (corner.y - viewport.height / 2) / view.halfHeight
                            val vx = view.focusViewX() + (dx + dy) / 2
                            val vy = view.focusViewY() + (dy - dx) / 2
                            val at = point(view.toWorldX(vx, vy), view.toWorldY(vx, vy))
                            if (i == 0) outline.moveTo(at.x, at.y) else outline.lineTo(at.x, at.y)
                        }
                        outline.close()
                        drawPath(outline, c.page, style = Stroke(4.dp.toPx()))
                        drawPath(outline, c.accent, style = Stroke(2.dp.toPx()))
                    }
                }
            }
        }, second = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                if (companies) Grid(current?.data?.companies.orEmpty()) { (id, name) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(companyColour(id)))
                        Text(name, color = c.textDim, style = KitType.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        })
    }
}
