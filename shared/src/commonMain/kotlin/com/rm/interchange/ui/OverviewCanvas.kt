package com.rm.interchange.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.*
import com.rm.interchange.map.View
import com.rm.interchange.map.companyColour
import com.rm.interchange.res.*
import com.rm.interchange.sim.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ln

@Composable
internal fun OverviewCanvas(image: MapImage?, traffic: State<List<OverviewVehicle>>, view: View, viewport: Size,
    options: OverviewOptions, owner: Int, modifier: Modifier, onIndustry: (Int) -> Unit, onClose: () -> Unit) {
    val c = LocalColors.current
    val camera = options.camera
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val title = stringResource(Res.string.overview_map)
    val latestImage by rememberUpdatedState(image)
    val latestIndustry by rememberUpdatedState(onIndustry)
    val latestClose by rememberUpdatedState(onClose)
    val shape = RoundedCornerShape(10.dp)
    Canvas(modifier.clip(shape).background(c.page).border(1.dp, c.chromeEdge, shape)
        .semantics { contentDescription = title }
        .pointerInput(view, camera) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                var travel = Offset.Zero
                var moving = false
                do {
                    val event = awaitPointerEvent()
                    val pan = event.calculatePan()
                    travel += pan
                    val zoom = event.calculateZoom()
                    if (travel.getDistance() > viewConfiguration.touchSlop || event.changes.count { it.pressed } > 1) moving = true
                    if (moving) {
                        val anchor = event.calculateCentroid(useCurrent = false)
                        if (anchor != Offset.Unspecified) camera.move(Offset(pan.x / size.width, pan.y / size.height), zoom,
                            Offset(anchor.x / size.width, anchor.y / size.height))
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
                if (!moving) {
                    val at = camera.at(Offset(down.position.x / size.width, down.position.y / size.height))
                    val data = latestImage?.data
                    val industry = if (options.industries.value) data?.industries?.minByOrNull {
                        val dx = (it.place.x / data.width - at.x) * size.width * camera.zoom
                        val dy = (it.place.y / data.height - at.y) * size.height * camera.zoom
                        dx * dx + dy * dy
                    }?.takeIf {
                        val dx = (it.place.x / data.width - at.x) * size.width * camera.zoom
                        val dy = (it.place.y / data.height - at.y) * size.height * camera.zoom
                        dx * dx + dy * dy < 16.dp.toPx() * 16.dp.toPx()
                    } else null
                    if (industry != null) latestIndustry(industry.place.id) else {
                        view.focusX = (at.x * view.mapWidth).coerceIn(0f, view.mapWidth.toFloat())
                        view.focusY = (at.y * view.mapHeight).coerceIn(0f, view.mapHeight.toFloat())
                        latestClose()
                    }
                }
            }
        }) {
        val current = image ?: return@Canvas
        val data = current.data
        val origin = Offset((0.5f - camera.x * camera.zoom) * size.width, (0.5f - camera.y * camera.zoom) * size.height)
        val scale = size.width / data.width * camera.zoom
        fun point(x: Float, y: Float) = origin + Offset(x / data.width * size.width * camera.zoom, y / data.height * size.height * camera.zoom)
        fun tint(id: Int, fallback: Color) = if (options.companies.value && id >= 0) companyColour(id) else fallback
        fun visible(id: Int) = owner < 0 || id == owner
        val slot = if (owner < 0) OVERVIEW_ALL else owner
        fun halo(p: MapPlace, percent: Int) {
            val colour = if (percent == 0) c.bad else if (percent < 75) c.warn else c.good
            drawCircle(colour.copy(alpha = 0.35f), 9.dp.toPx(), point(p.x, p.y))
            drawCircle(colour, 9.dp.toPx(), point(p.x, p.y), style = Stroke(1.dp.toPx()))
        }
        clipRect {
            drawImage(current.terrain, dstOffset = IntOffset(origin.x.toInt(), origin.y.toInt()),
                dstSize = IntSize((size.width * camera.zoom).toInt(), (size.height * camera.zoom).toInt()))
            withTransform({ translate(origin.x, origin.y); scale(scale, scale, pivot = Offset.Zero) }) {
                if (options.roads.value) for ((id, path) in current.roads) if (id < 0 || visible(id)) drawPath(path, tint(id, c.textDim), style = Stroke(1.dp.toPx() / scale))
                if (options.rail.value) for ((id, path) in current.rail) if (visible(id)) drawPath(path, tint(id, c.accent), style = Stroke(1.5.dp.toPx() / scale))
            }
            if (options.flows.value) {
                val flows = data.info.flows.filter { visible(it.owner) && (options.cargo.intValue < 0 || it.cargo == options.cargo.intValue) }
                    .groupBy { Triple(it.from, it.to, it.owner) }
                for ((leg, packets) in flows) {
                    val from = data.stationById[leg.first] ?: continue
                    val to = data.stationById[leg.second] ?: continue
                    val a = point(from.x, from.y)
                    val b = point(to.x, to.y)
                    val width = (1f + ln(1.0 + packets.sumOf { it.count }).toFloat() / 2).coerceAtMost(8f).dp.toPx()
                    val colour = tint(leg.third, c.accent).copy(alpha = 0.75f)
                    drawLine(colour, a, b, width)
                    val delta = b - a
                    if (delta.getDistance() > 12.dp.toPx()) {
                        val unit = delta / delta.getDistance()
                        val tip = a + delta * 0.65f
                        val back = tip - unit * 7.dp.toPx()
                        val side = Offset(-unit.y, unit.x) * 4.dp.toPx()
                        drawLine(colour, tip, back + side, 2.dp.toPx())
                        drawLine(colour, tip, back - side, 2.dp.toPx())
                    }
                }
            }
            if (options.coverage.value) {
                for (p in data.towns) halo(p, data.info.towns[p.id][slot])
                for (industry in data.industries) halo(industry.place, industry.info.coverage[slot])
            }
            if (options.industries.value) for (industry in data.industries) {
                val at = point(industry.place.x, industry.place.y)
                drawRect(c.page, at - Offset(3.dp.toPx(), 3.dp.toPx()), Size(6.dp.toPx(), 6.dp.toPx()))
                drawRect(c.warn, at - Offset(2.dp.toPx(), 2.dp.toPx()), Size(4.dp.toPx(), 4.dp.toPx()))
            }
            if (options.stations.value) for (p in data.stations) if (visible(p.owner)) {
                drawCircle(c.page, 3.dp.toPx(), point(p.x, p.y))
                drawCircle(tint(p.owner, c.text), 2.dp.toPx(), point(p.x, p.y))
            }
            for (vehicle in traffic.value) if (visible(vehicle.owner) && options.vehicleModes.intValue and (1 shl vehicle.mode) != 0) {
                val at = point(vehicle.x, vehicle.y)
                drawCircle(c.page, 3.dp.toPx(), at)
                drawCircle(tint(vehicle.owner, listOf(c.accent, c.text, c.good, c.warn)[vehicle.mode]), 2.dp.toPx(), at)
            }
            val occupied = ArrayList<Rect>()
            for (p in data.towns) {
                val at = point(p.x, p.y)
                if (at.x !in 0f..size.width || at.y !in 0f..size.height) continue
                drawCircle(c.page, 2.5.dp.toPx(), at)
                drawCircle(c.text, 1.5.dp.toPx(), at)
                if (options.names.value) {
                    val label = p.name + if (options.coverage.value) " ${data.info.towns[p.id][slot]}%" else ""
                    val text = measurer.measure(label, KitType.Dim.copy(color = c.text))
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
            // Project the main camera through its rotation into the north-up overview.
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
