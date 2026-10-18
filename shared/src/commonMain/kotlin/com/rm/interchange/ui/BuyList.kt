package com.rm.interchange.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.interchange.map.Atlas
import com.rm.interchange.map.Sheets
import com.rm.interchange.res.*
import com.rm.interchange.sim.Engine
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.min

/** The game's sheets, for drawing vehicles in the buying lists; null outside a game. */
val LocalSheets = staticCompositionLocalOf<Sheets?> { null }

/** How tall a buying list gets before it scrolls, set from the screen's height. */
val LocalBuyListHeight = staticCompositionLocalOf { 320.dp }

/** The drawing of each vehicle shown in a buying list: facing down and to the left, three quarters on. */
private const val PICTURE_CELL = 2

/** [e]'s drawing from the map's sheets, scaled to fit the box and centred; blank until its sheet is unpacked. */
@Composable
fun VehiclePicture(e: Engine, modifier: Modifier) {
    val sheets = LocalSheets.current
    Canvas(modifier) {
        sheets ?: return@Canvas
        // Read here so the picture draws again once the sheet below is unpacked.
        sheets.revision.intValue
        val rects = sheets.vehicleRects(e.sprite) ?: return@Canvas
        val r = PICTURE_CELL * 6
        val w0 = rects[r + 2]
        val h0 = rects[r + 3]
        if (w0 == 0 || h0 == 0) return@Canvas
        // Drawn from the smallest sheet still at least as big as it's drawn; past the sharpest it's scaled up.
        val scale = min(size.width / w0, size.height / h0)
        var level = 0
        while (level + 1 < Atlas.LEVELS && scale * (1 shl (level + 1)) <= 1f) level++
        val image = sheets.vehicle(e.sprite, level) ?: return@Canvas
        val shrink = 1 shl level
        val sw = w0 / shrink
        val sh = h0 / shrink
        if (sw == 0 || sh == 0) return@Canvas
        val dw = (w0 * scale).toInt()
        val dh = (h0 * scale).toInt()
        drawImage(
            image, IntOffset(rects[r] / shrink, rects[r + 1] / shrink), IntSize(sw, sh),
            IntOffset(((size.width - dw) / 2).toInt(), ((size.height - dh) / 2).toInt()), IntSize(dw, dh),
            filterQuality = FilterQuality.Medium,
        )
    }
}

/** How much of [cargo] [n] is, as "31 passengers" or "20 t coal". */
@Composable
fun cargoAmount(cargo: Int, n: Int): String =
    stringArrayResource(Res.array.cargo_amounts).getOrElse(cargo) { "%d" }.replace("%d", Numbers.grouped(n.toLong()))

/** What [e] is carrying in the list: [cargo] if it's one of its choices, else its first, and how many others it could unless [choosing]. */
@Composable
private fun capacityText(e: Engine, cargo: Int, choosing: Boolean): String? {
    if (e.capacity <= 0 || e.cargoes.isEmpty()) return null
    val shown = if (cargo in e.cargoes) cargo else e.cargoes[0]
    val amount = cargoAmount(shown, e.capacity)
    return if (e.cargoes.size > 1 && !choosing) pluralStringResource(Res.plurals.or_others, e.cargoes.size - 1, amount, e.cargoes.size - 1) else amount
}

/** The numbers that tell models apart: speed, what it carries, its pull, and how far it flies or the ice it breaks. */
@Composable
private fun statsText(e: Engine, cargo: Int, choosing: Boolean): String {
    val parts = ArrayList<String>()
    if (e.speed > 0) parts += stringResource(Res.string.kmh, e.speed)
    capacityText(e, cargo, choosing)?.let { parts += it }
    if (e.pulls) {
        if (e.power > 0) parts += stringResource(Res.string.horsepower, Numbers.grouped(e.power.toLong()))
        parts += stringResource(Res.string.kilonewtons, e.tractive)
    }
    if (e.range > 0) parts += stringResource(Res.string.range_stat, Numbers.grouped(e.range.toLong()))
    if (e.icebreaker > 0) parts += stringResource(Res.string.breaks_ice, e.icebreaker)
    // Each stat kept on one line, so a long row wraps only between them.
    return parts.joinToString(SEP) { it.replace(' ', '\u00A0') }
}

private const val SEP = "  ·  "

/** Models past this year are on sale for good. */
private const val FOR_GOOD = 2100

/**
 * One row a model for a buying list: its picture, name and [cost], its type and [running] cost, and its numbers. The
 * [selected] one is lit and offers its cargo choices, if it has more than one.
 */
@Composable
private fun ModelRow(e: Engine, cost: Long, running: Long, selected: Boolean, cargo: Int, onSelect: () -> Unit, onCargo: (Int) -> Unit) {
    val c = LocalColors.current
    val lang = languageCode()
    val shape = RoundedCornerShape(10.dp)
    // Picked, the row and its cargo choices scroll into sight, once they're laid out.
    val sight = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) {
        if (selected) {
            withFrameNanos { }
            sight.bringIntoView()
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(sight)
            .clip(shape)
            .background(if (selected) c.accent.copy(alpha = 0.16f) else c.button)
            .border(2.dp, if (selected) c.accent else c.button, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            VehiclePicture(e, Modifier.size(PICTURE_WIDTH, PICTURE_HEIGHT).clip(RoundedCornerShape(8.dp)).background(c.page).padding(2.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.name(lang), color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(money(cost), color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val sub = listOfNotNull(
                        e.type(lang).takeIf { it.isNotEmpty() && it != e.name(lang) },
                        stringResource(Res.string.on_sale_until, e.until - 1).takeIf { e.until < FOR_GOOD },
                    ).joinToString(SEP)
                    Text(sub, color = c.textDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(stringResource(Res.string.a_year, money(running)), color = c.textDim, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                }
                val stats = statsText(e, if (selected) cargo else -1, selected)
                if (stats.isNotEmpty()) Text(stats, color = c.text, fontSize = 12.sp, maxLines = 2)
            }
        }
        if (selected && e.cargoes.size > 1) CargoChoice(e.cargoes, if (cargo in e.cargoes) cargo else e.cargoes[0], onCargo)
    }
}

/** The cargo a vehicle is to carry, as segments of one size in as few even rows of up to four as they need. */
@Composable
private fun CargoChoice(choices: IntArray, chosen: Int, onCargo: (Int) -> Unit) {
    val c = LocalColors.current
    val names = stringArrayResource(Res.array.cargoes)
    val rows = (choices.size + CARGO_ROW - 1) / CARGO_ROW
    val across = (choices.size + rows - 1) / rows
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.page).padding(2.dp).selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (row in choices.toList().chunked(across)) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (cg in row) {
                    val on = cg == chosen
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (on) c.accent else c.page)
                            .selectable(selected = on, role = Role.RadioButton) { onCargo(cg) }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(names.getOrElse(cg) { "" }, color = if (on) c.onAccent else c.text, fontSize = 13.sp, maxLines = 2, textAlign = TextAlign.Center)
                    }
                }
                // Blanks keep a short last row's segments the size of the rest.
                repeat(across - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

private const val CARGO_ROW = 4

private val PICTURE_WIDTH = 76.dp
private val PICTURE_HEIGHT = 52.dp

/**
 * A scrolling list of [models] to pick one from, the [selected] one lit with its cargo choices. Each shows what
 * [costOf] and [runningOf] say it costs, after [price].
 */
@Composable
fun BuyList(
    models: List<Engine>, selected: Int, cargo: Int, price: (Long) -> Long, onSelect: (Int) -> Unit,
    costOf: (Engine) -> Long = { it.cost }, runningOf: (Engine) -> Long = { it.running }, onCargo: (Int) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = LocalBuyListHeight.current).verticalScroll(rememberScrollState()).selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (e in models) ModelRow(e, price(costOf(e)), price(runningOf(e)), e.id == selected, cargo, { onSelect(e.id) }) { onCargo(it) }
    }
}

/**
 * A depot card's body: [top] and the buttons in [footer] at their own size, [inDepot] (the vehicles in the depot) up to
 * [IN_DEPOT_HEIGHT] and scrolling past it, and [list] given what height is left between them, so the buy button stays
 * on a short screen.
 */
@Composable
fun BuyPanel(top: @Composable () -> Unit, list: @Composable () -> Unit, footer: @Composable () -> Unit, inDepot: @Composable () -> Unit) {
    val gap = 6.dp
    Layout(
        contents = listOf(
            { Column(verticalArrangement = Arrangement.spacedBy(gap)) { top() } },
            list,
            {
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    footer()
                    Column(Modifier.heightIn(max = IN_DEPOT_HEIGHT).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(gap)) { inDepot() }
                }
            },
        ),
    ) { (tops, lists, foots), cs ->
        val loose = cs.copy(minWidth = cs.maxWidth, minHeight = 0)
        val t = tops.map { it.measure(loose) }
        val f = foots.map { it.measure(loose) }
        val g = gap.roundToPx()
        val fixed = t.sumOf { it.height } + f.sumOf { it.height }
        val gaps = g * listOf(t.any { it.height > 0 }, f.any { it.height > 0 }).count { it }
        val room = if (cs.hasBoundedHeight) (cs.maxHeight - fixed - gaps).coerceAtLeast(LIST_LEAST.roundToPx()) else Constraints.Infinity
        val l = lists.map { it.measure(loose.copy(maxHeight = room)) }
        val parts = listOf(t, l, f).filter { p -> p.any { it.height > 0 } }
        val height = parts.sumOf { p -> p.sumOf { it.height } } + g * (parts.size - 1).coerceAtLeast(0)
        layout(cs.maxWidth, height.coerceIn(cs.minHeight, cs.maxHeight)) {
            var y = 0
            for (p in parts) {
                for (pl in p) { pl.place(0, y); y += pl.height }
                y += g
            }
        }
    }
}

/** The least a buying list is cut to on a short screen, a row and a bit, and the most the vehicles in a depot take. */
private val LIST_LEAST = 96.dp
private val IN_DEPOT_HEIGHT = 120.dp

/** The button to buy what's picked for [cost], greyed with a note when there's too little money or [blocked]. */
@Composable
fun BuyButton(cost: Long, funds: Long, blocked: Boolean = false, onBuy: () -> Unit) {
    val c = LocalColors.current
    val short = funds < cost
    if (short) Text(stringResource(Res.string.not_enough_money), color = c.warn, fontSize = 13.sp)
    BigButton(stringResource(Res.string.buy_for, money(cost)), primary = true, enabled = !short && !blocked, glyph = Glyph.Plus, onClick = onBuy)
}
