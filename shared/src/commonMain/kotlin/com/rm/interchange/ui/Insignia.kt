package com.rm.interchange.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rm.interchange.map.companyColour
import com.rm.interchange.res.Res
import com.rm.interchange.res.insignia
import com.rm.interchange.res.insignia_clear
import com.rm.interchange.res.insignia_colours
import com.rm.interchange.res.insignia_fill
import com.rm.interchange.res.insignia_paint
import com.rm.interchange.res.insignia_set
import com.rm.interchange.res.insignia_undo
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.Company
import com.rm.interchange.sim.Insignia
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The colours of an insignia's palette for a company in [colour], [Insignia.CLEAR] as see-through. */
fun insigniaPalette(colour: Color): List<Color> = listOf(
    Color.Transparent, colour, lerp(colour, Color.White, 0.45f), lerp(colour, Color.Black, 0.5f),
    Color(0xFFF4F4F0), Color(0xFF1A1A1A), Color(0xFF8A8F96), Color(0xFFE8B422),
)

/** Draws [pixels] (an insignia, packed) at the largest whole number of screen pixels a pixel that fits, centred. */
private fun DrawScope.drawInsignia(pixels: IntArray, palette: List<Color>) {
    val scale = max(1f, floor(min(size.width, size.height) / Insignia.SIZE))
    val ox = floor((size.width - scale * Insignia.SIZE) / 2)
    val oy = floor((size.height - scale * Insignia.SIZE) / 2)
    for (i in pixels.indices) {
        val p = pixels[i]
        if (p == Insignia.CLEAR) continue
        drawRect(palette[p], Offset(ox + i % Insignia.SIZE * scale, oy + i / Insignia.SIZE * scale), Size(scale, scale))
    }
}

/** An insignia in company colour [colour], pixel-crisp. Nothing for one that isn't whole. */
@Composable
fun InsigniaImage(packed: ByteArray, colour: Color, modifier: Modifier = Modifier) {
    if (!Insignia.valid(packed)) return
    val pixels = remember(packed.contentHashCode()) { Insignia.unpack(packed) }
    val palette = remember(colour) { insigniaPalette(colour) }
    Canvas(modifier.clearAndSetSemantics { }) { drawInsignia(pixels, palette) }
}

/** A company's insignia in its colour, in a tile [side] across. */
@Composable
fun CompanyInsignia(co: Company, side: Dp = 36.dp) {
    val c = LocalColors.current
    Box(Modifier.size(side).clip(RoundedCornerShape(8.dp)).background(c.page), contentAlignment = Alignment.Center) {
        InsigniaImage(co.insignia, companyColour(co.id), Modifier.size(side - 4.dp))
    }
}

/**
 * Picking and drawing [own]'s insignia: the presets to start from, the 16 by 16 canvas to paint on with the palette,
 * and Set to give the company what's drawn.
 */
@Composable
fun InsigniaWindow(own: Company, send: (Command) -> Unit, onClose: () -> Unit) {
    val c = LocalColors.current
    val palette = remember(own.id) { insigniaPalette(companyColour(own.id)) }
    val names = stringArrayResource(Res.array.insignia_colours)
    var pixels by remember(own.id) { mutableStateOf(Insignia.unpack(own.insignia)) }
    val undo = remember(own.id) { mutableStateListOf<IntArray>() }
    var colour by remember { mutableIntStateOf(Insignia.COLOUR) }
    var filling by remember { mutableStateOf(false) }
    // What the canvas showed when a touch began, kept for undo once it's done.
    val before = remember { arrayOfNulls<IntArray>(1) }
    fun keep(was: IntArray) {
        if (was.contentEquals(pixels)) return
        undo += was
        if (undo.size > UNDO_MOST) undo.removeAt(0)
    }
    fun change(next: IntArray) {
        val was = pixels
        pixels = next
        keep(was)
    }

    Window(stringResource(Res.string.insignia), onClose, Glyph.Home) {
        // The presets, a tap loads one to touch up.
        for (row in Insignia.presets.chunked(PRESETS_ACROSS)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in row) {
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(c.button)
                            .clickable(role = Role.Button) { change(Insignia.unpack(p)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        InsigniaImage(p, palette[Insignia.COLOUR], Modifier.padding(4.dp).fillMaxWidth().aspectRatio(1f))
                    }
                }
                repeat(PRESETS_ACROSS - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            InsigniaCanvas(
                pixels, palette,
                begin = { before[0] = pixels },
                paint = { from, to -> pixels = if (filling) pixels.flood(to, colour) else pixels.line(from, to, colour) },
                end = { before[0]?.let { keep(it) }; before[0] = null },
                filling = { filling },
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f),
            )
        }
        // The palette: see-through first, as the eraser.
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (k in 0 until Insignia.COLOURS) {
                val on = k == colour
                Box(
                    Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                        .border(if (on) 3.dp else 1.dp, if (on) c.accent else c.chromeEdge, RoundedCornerShape(8.dp))
                        .selectable(selected = on, role = Role.RadioButton) { colour = k }
                        .semantics { contentDescription = names.getOrElse(k) { "" } },
                ) {
                    Canvas(Modifier.matchParentSize().padding(4.dp)) {
                        if (k == Insignia.CLEAR) drawChecks(size.minDimension / 4) else drawRect(palette[k])
                    }
                }
            }
        }
        FillChips(listOf(false, true), filling, { stringResource(if (it) Res.string.insignia_fill else Res.string.insignia_paint) }) { filling = it }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(stringResource(Res.string.insignia_undo), enabled = undo.isNotEmpty()) { pixels = undo.removeAt(undo.size - 1) }
            ActionButton(stringResource(Res.string.insignia_clear)) { change(IntArray(Insignia.PIXELS)) }
            Box(Modifier.weight(1f))
            // As it shows in the lists.
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(c.page), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(32.dp).clearAndSetSemantics { }) { drawInsignia(pixels, palette) }
            }
            ActionButton(stringResource(Res.string.insignia_set), enabled = !Insignia.pack(pixels).contentEquals(own.insignia), strong = true) {
                send(Command.SetInsignia(own.id, Insignia.pack(pixels)))
                onClose()
            }
        }
    }
}

/** Presets in a row of the picker. */
private const val PRESETS_ACROSS = 8

/** Steps of undo kept. */
private const val UNDO_MOST = 64

/** The canvas: see-through pixels as checks, a faint grid. [paint] is given the pixel a touch went from and to. */
@Composable
private fun InsigniaCanvas(
    pixels: IntArray, palette: List<Color>, begin: () -> Unit, paint: (Int, Int) -> Unit, end: () -> Unit, filling: () -> Boolean, modifier: Modifier,
) {
    val c = LocalColors.current
    val grid = c.text.copy(alpha = 0.12f)
    Canvas(
        modifier.clip(RoundedCornerShape(4.dp)).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                val cell = floor(min(size.width, size.height).toFloat() / Insignia.SIZE)
                val ox = floor((size.width - cell * Insignia.SIZE) / 2)
                val oy = floor((size.height - cell * Insignia.SIZE) / 2)
                fun at(p: Offset): Int {
                    val x = floor((p.x - ox) / cell).toInt().coerceIn(0, Insignia.SIZE - 1)
                    val y = floor((p.y - oy) / cell).toInt().coerceIn(0, Insignia.SIZE - 1)
                    return y * Insignia.SIZE + x
                }
                var last = at(down.position)
                begin()
                try {
                    paint(last, last)
                    if (filling()) return@awaitEachGesture
                    // Dragging paints, and keeps the window from scrolling.
                    while (true) {
                        val event = awaitPointerEvent()
                        val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        ch.consume()
                        val i = at(ch.position)
                        if (i != last) {
                            paint(last, i)
                            last = i
                        }
                    }
                } finally {
                    end()
                }
            }
        },
    ) {
        val cell = floor(size.minDimension / Insignia.SIZE)
        val ox = floor((size.width - cell * Insignia.SIZE) / 2)
        val oy = floor((size.height - cell * Insignia.SIZE) / 2)
        for (i in pixels.indices) {
            val at = Offset(ox + i % Insignia.SIZE * cell, oy + i / Insignia.SIZE * cell)
            if (pixels[i] == Insignia.CLEAR) {
                val half = cell / 2
                val dark = (i / Insignia.SIZE + i % Insignia.SIZE) % 2 == 0
                drawRect(if (dark) CHECK_DARK else CHECK_LIGHT, at, Size(cell, cell))
                drawRect(if (dark) CHECK_LIGHT else CHECK_DARK, at, Size(half, half))
                drawRect(if (dark) CHECK_LIGHT else CHECK_DARK, at + Offset(half, half), Size(half, half))
            } else drawRect(palette[pixels[i]], at, Size(cell, cell))
        }
        for (k in 0..Insignia.SIZE) {
            drawRect(grid, Offset(ox + k * cell, oy), Size(1f, cell * Insignia.SIZE))
            drawRect(grid, Offset(ox, oy + k * cell), Size(cell * Insignia.SIZE, 1f))
        }
    }
}

private val CHECK_LIGHT = Color(0xFFDADADA)
private val CHECK_DARK = Color(0xFFB4B4B4)

/** Checks [side] across over the whole area, for see-through. */
private fun DrawScope.drawChecks(side: Float) {
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = 0f
        var col = row
        while (x < size.width) {
            drawRect(if (col % 2 == 0) CHECK_DARK else CHECK_LIGHT, Offset(x, y), Size(min(side, size.width - x), min(side, size.height - y)))
            x += side
            col++
        }
        y += side
        row++
    }
}

/** These pixels with a straight line from pixel [from] to [to] in [colour]. */
private fun IntArray.line(from: Int, to: Int, colour: Int): IntArray {
    val out = copyOf()
    var x = from % Insignia.SIZE
    var y = from / Insignia.SIZE
    val x1 = to % Insignia.SIZE
    val y1 = to / Insignia.SIZE
    val dx = abs(x1 - x)
    val dy = -abs(y1 - y)
    val sx = if (x < x1) 1 else -1
    val sy = if (y < y1) 1 else -1
    var err = dx + dy
    while (true) {
        out[y * Insignia.SIZE + x] = colour
        if (x == x1 && y == y1) break
        val e2 = 2 * err
        if (e2 >= dy) { err += dy; x += sx }
        if (e2 <= dx) { err += dx; y += sy }
    }
    return out
}

/** These pixels with the patch of one colour round pixel [at] filled with [colour]. */
private fun IntArray.flood(at: Int, colour: Int): IntArray {
    val out = copyOf()
    val was = out[at]
    if (was == colour) return out
    val todo = ArrayDeque<Int>().apply { add(at) }
    while (todo.isNotEmpty()) {
        val i = todo.removeLast()
        if (out[i] != was) continue
        out[i] = colour
        val x = i % Insignia.SIZE
        if (x > 0) todo += i - 1
        if (x < Insignia.SIZE - 1) todo += i + 1
        if (i >= Insignia.SIZE) todo += i - Insignia.SIZE
        if (i < Insignia.PIXELS - Insignia.SIZE) todo += i + Insignia.SIZE
    }
    return out
}
