package com.rm.interchange.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.rm.interchange.sim.PAYMENT_TICKS
import com.rm.interchange.sim.Payment
import com.rm.interchange.sim.TownView
import com.rm.interchange.sim.World

/**
 * Town names over the map, each on a dark pill above its middle, with its
 * population under the name once the map is close enough to read it. Laid out
 * text is kept, since names hardly change.
 */
class TownLabels(private val measurer: TextMeasurer, private val back: Color, private val text: Color, private val dim: Color) {
    private val names = HashMap<String, TextLayoutResult>()
    private val counts = HashMap<String, TextLayoutResult>()

    fun DrawScope.draw(world: World, view: View, towns: List<TownView>, population: (Int) -> String) {
        val close = view.halfWidth >= CLOSE
        val pad = 6f * density
        for (t in towns) {
            val x = t.x + 0.5f
            val y = t.y + 0.5f
            val vx = view.toViewX(x, y)
            val vy = view.toViewY(x, y)
            val sx = view.screenX(vx, vy, size.width)
            // Above the middle, clear of the buildings round it.
            val sy = view.screenY(vx, vy, groundHeight(world, x, y), size.height) - view.halfWidth * 1.4f
            if (sx < -300f || sx > size.width + 300f || sy < -100f || sy > size.height + 100f) continue
            val name = names.getOrPut(t.name) { measurer.measure(t.name, TextStyle(color = text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)) }
            val count = if (close) population(t.population).let { p -> counts.getOrPut(p) { measurer.measure(p, TextStyle(color = dim, fontSize = 11.sp)) } } else null
            val w = maxOf(name.size.width, count?.size?.width ?: 0) + pad * 2
            val h = name.size.height + (count?.size?.height ?: 0) + pad
            val left = sx - w / 2
            val top = sy - h
            drawRoundRect(back, Offset(left, top), Size(w, h), CornerRadius(8f * density))
            drawText(name, topLeft = Offset(sx - name.size.width / 2f, top + pad / 2))
            if (count != null) drawText(count, topLeft = Offset(sx - count.size.width / 2f, top + pad / 2 + name.size.height))
        }
    }

    private val sums = HashMap<Long, TextLayoutResult>()

    /**
     * Each recent payment as its amount rising from its station and fading,
     * over the [PAYMENT_TICKS] it's kept. [now] is the tick the view was made at.
     */
    fun DrawScope.drawPayments(world: World, view: View, payments: List<Payment>, now: Long, colour: Color, money: (Long) -> String) {
        for (p in payments) {
            val age = ((now - p.tick).toFloat() / PAYMENT_TICKS).coerceIn(0f, 1f)
            val x = p.tile % world.width + 0.5f
            val y = p.tile / world.width + 0.5f
            val vx = view.toViewX(x, y)
            val vy = view.toViewY(x, y)
            val sx = view.screenX(vx, vy, size.width)
            val sy = view.screenY(vx, vy, groundHeight(world, x, y), size.height) - view.halfWidth * (0.6f + age * 1.2f)
            if (sx < -200f || sx > size.width + 200f || sy < -100f || sy > size.height + 100f) continue
            val text = sums.getOrPut(p.amount) { measurer.measure("+" + money(p.amount), TextStyle(color = colour, fontSize = 13.sp, fontWeight = FontWeight.Bold)) }
            drawText(text, topLeft = Offset(sx - text.size.width / 2f, sy - text.size.height), alpha = 1f - age * age)
        }
    }

    companion object {
        /** From this close, in half a tile's width on screen, the population shows too. */
        private const val CLOSE = 16f
    }
}

@Composable
fun rememberTownLabels(back: Color, text: Color, dim: Color): TownLabels {
    val measurer = rememberTextMeasurer()
    return remember(measurer, back, text, dim) { TownLabels(measurer, back, text, dim) }
}
