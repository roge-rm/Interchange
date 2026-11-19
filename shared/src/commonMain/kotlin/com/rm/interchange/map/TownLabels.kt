package com.rm.interchange.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.rm.interchange.map.gl.LabelBatch
import com.rm.interchange.map.gl.MapLabels
import com.rm.interchange.sim.PAYMENT_TICKS
import com.rm.interchange.sim.Payment
import com.rm.interchange.sim.StationView
import com.rm.interchange.sim.TownView
import com.rm.interchange.sim.World
import kotlin.math.ceil

/**
 * Town names over the map, each on a dark pill above its middle, with its population under the name once the map is
 * close enough to read it; smaller names when it's far out. Laid out here and drawn into the GPU map's label atlas,
 * which draws them at a fixed size in the same frame as the map. Laid out text is kept, since names hardly change.
 */
class TownLabels(private val measurer: TextMeasurer, private val back: Color, private val text: Color, private val dim: Color) {
    private val names = HashMap<String, TextLayoutResult>()
    private val smallNames = HashMap<String, TextLayoutResult>()
    private val counts = HashMap<String, TextLayoutResult>()
    private var sink: MapLabels? = null

    /**
     * Puts the labels [block] adds ([draw], [drawStations], [drawPayments]) into [into] for this frame. If the atlas
     * fills and starts again part way, they're placed once more.
     */
    fun DrawScope.place(into: MapLabels, block: DrawScope.() -> Unit) {
        sink = into
        block()
        if (into.overflowed) {
            into.restart()
            block()
        }
        sink = null
    }

    /** The atlas rect of the image [key], [w] by [h] pixels, drawn by [paint] the first time it's asked for. */
    private fun DrawScope.image(key: String, w: Float, h: Float, paint: DrawScope.() -> Unit): IntArray? {
        val iw = ceil(w).toInt()
        val ih = ceil(h).toInt()
        val drawing = Density(density, fontScale)
        val direction = layoutDirection
        return sink?.atlas?.get("$density\u0000$fontScale\u0000$key", iw, ih) {
            val bitmap = ImageBitmap(iw, ih)
            CanvasDrawScope().draw(drawing, direction, Canvas(bitmap), Size(iw.toFloat(), ih.toFloat()), paint)
            IntArray(iw * ih).also { bitmap.readPixels(it) }
        }
    }

    fun DrawScope.draw(world: World, view: View, towns: List<TownView>, population: (Int) -> String) {
        val labels = sink ?: return
        val close = view.halfWidth >= CLOSE
        val small = view.halfWidth < SMALL_BELOW
        val pad = (if (small) 4f else 6f) * density
        val corner = (if (small) 6f else 8f) * density
        for (t in towns) {
            val x = t.x + 0.5f
            val y = t.y + 0.5f
            val vx = view.toViewX(x, y)
            val vy = view.toViewY(x, y)
            val sx = view.screenX(vx, vy, size.width)
            val ground = groundHeight(world, x, y)
            // Above the middle, clear of the buildings round it.
            val sy = view.screenY(vx, vy, ground, size.height) - view.halfWidth * TOWN_LIFT
            if (sx < -300f || sx > size.width + 300f || sy < -100f || sy > size.height + 100f) continue
            val name = if (small) {
                smallNames.getOrPut(t.name) { measurer.measure(t.name, TextStyle(color = text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)) }
            } else names.getOrPut(t.name) { measurer.measure(t.name, TextStyle(color = text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)) }
            val p = if (close) population(t.population) else null
            val count = p?.let { counts.getOrPut(it) { measurer.measure(it, TextStyle(color = dim, fontSize = 11.sp)) } }
            val w = maxOf(name.size.width, count?.size?.width ?: 0) + pad * 2
            val h = name.size.height + (count?.size?.height ?: 0) + pad
            val key = "t\u0000${t.name}\u0000${p.orEmpty()}\u0000$small\u0000${back.value}\u0000${text.value}\u0000${dim.value}"
            val rect = image(key, w, h) {
                drawRoundRect(back, Offset.Zero, Size(w, h), CornerRadius(corner))
                drawText(name, topLeft = Offset(w / 2 - name.size.width / 2f, pad / 2))
                if (count != null) drawText(count, topLeft = Offset(w / 2 - count.size.width / 2f, pad / 2 + name.size.height))
            } ?: continue
            labels.add(x, y, ground, TOWN_LIFT, rect, 1f, LabelBatch.TOWN)
        }
    }

    private val signs = HashMap<String, TextLayoutResult>()

    /**
     * Each station's name on a small pill in its company's colour, just above its tiles: lighter and smaller than a
     * town's label, and left off where it would cover one, or another sign, or when the map's too far out to read it.
     * Call after [draw]. Waypoints and buoys have none.
     */
    fun DrawScope.drawStations(world: World, view: View, stations: List<StationView>, name: (StationView) -> String) {
        val labels = sink ?: return
        if (view.halfWidth < SIGNS_FROM) return
        val padX = 5f * density
        val padY = 2f * density
        for (st in stations) {
            if (st.waypoint) continue
            val vx = view.toViewX(st.x, st.y)
            val vy = view.toViewY(st.x, st.y)
            val sx = view.screenX(vx, vy, size.width)
            val ground = groundHeight(world, st.x, st.y)
            val sy = view.screenY(vx, vy, ground, size.height) - view.halfWidth * SIGN_LIFT
            if (sx < -200f || sx > size.width + 200f || sy < -60f || sy > size.height + 60f) continue
            val colour = companyColour(st.owner)
            val ink = if (colour.luminance() > 0.5f) Color(0xFF1E1E20) else Color.White
            val label = name(st)
            val text = signs.getOrPut("$label\u0000${colour.value}") { measurer.measure(label, TextStyle(color = ink, fontSize = 11.sp, fontWeight = FontWeight.Medium)) }
            val w = text.size.width + padX * 2
            val h = text.size.height + padY * 2
            val rect = image("s\u0000$label\u0000${colour.value}", w, h) {
                drawRoundRect(colour.copy(alpha = 0.85f), Offset.Zero, Size(w, h), CornerRadius(h / 2))
                drawText(text, topLeft = Offset(padX, padY))
            } ?: continue
            labels.add(st.x, st.y, ground, SIGN_LIFT, rect, 1f, LabelBatch.SIGN)
        }
    }

    private val sums = HashMap<Long, TextLayoutResult>()

    /**
     * Each recent payment as its amount rising from its station and fading,
     * over the [PAYMENT_TICKS] it's kept. [now] is the tick the view was made at.
     */
    fun DrawScope.drawPayments(world: World, view: View, payments: List<Payment>, now: Long, colour: Color, money: (Long) -> String) {
        val labels = sink ?: return
        for (p in payments) {
            val age = ((now - p.tick).toFloat() / PAYMENT_TICKS).coerceIn(0f, 1f)
            val x = p.tile % world.width + 0.5f
            val y = p.tile / world.width + 0.5f
            val vx = view.toViewX(x, y)
            val vy = view.toViewY(x, y)
            val sx = view.screenX(vx, vy, size.width)
            val ground = groundHeight(world, x, y)
            val lift = 0.6f + age * 1.2f
            val sy = view.screenY(vx, vy, ground, size.height) - view.halfWidth * lift
            if (sx < -200f || sx > size.width + 200f || sy < -100f || sy > size.height + 100f) continue
            val text = sums.getOrPut(p.amount) { measurer.measure("+" + money(p.amount), TextStyle(color = colour, fontSize = 13.sp, fontWeight = FontWeight.Bold)) }
            val rect = image("p\u0000${p.amount}\u0000${colour.value}", text.size.width.toFloat(), text.size.height.toFloat()) {
                drawText(text, topLeft = Offset.Zero)
            } ?: continue
            labels.add(x, y, ground, lift, rect, 1f - age * age, LabelBatch.PAYMENT)
        }
    }

    companion object {
        /** From this close, in half a tile's width on screen, the population shows too. */
        private const val CLOSE = 16f
        /** From this close, station signs show. */
        private const val SIGNS_FROM = 8f
        /** Further out than this, town names are smaller. */
        private const val SMALL_BELOW = 8f
        /** How far above its anchor a town name's bottom sits, in half tile widths; a station sign's. */
        private const val TOWN_LIFT = 1.4f
        private const val SIGN_LIFT = 0.8f
    }
}

fun companyColour(company: Int): Color = (if (com.rm.interchange.ui.Palette.safe) com.rm.interchange.ui.SAFE_COMPANY_COLOURS else com.rm.interchange.ui.STANDARD_COMPANY_COLOURS).let { it[company.mod(it.size)] }

@Composable
fun rememberTownLabels(back: Color, text: Color, dim: Color): TownLabels {
    val measurer = com.rm.interchange.ui.rememberCappedMeasurer()
    return remember(measurer, back, text, dim) { TownLabels(measurer, back, text, dim) }
}
