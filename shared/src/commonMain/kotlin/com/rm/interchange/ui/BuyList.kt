package com.rm.interchange.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.rm.interchange.res.*
import com.rm.interchange.sim.Engine
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/** How tall a buying list gets before it scrolls, set from the screen's height. */
val LocalBuyListHeight = staticCompositionLocalOf { 320.dp }

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
    val lang = languageCode()
    // Picked, the row and its cargo choices scroll into sight, once they're laid out.
    val sight = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) {
        if (selected) {
            withFrameNanos { }
            sight.bringIntoView()
        }
    }
    val names = stringArrayResource(Res.array.cargoes)
    PictureRow(
        title = e.name(lang),
        modifier = Modifier.bringIntoViewRequester(sight),
        picture = { VehiclePicture(e, it) },
        titleEnd = money(cost),
        line = listOfNotNull(
            e.type(lang).takeIf { it.isNotEmpty() && it != e.name(lang) },
            stringResource(Res.string.on_sale_until, e.until - 1).takeIf { e.until < FOR_GOOD },
        ).joinToString(SEP),
        lineEnd = stringResource(Res.string.a_year, money(running)),
        stats = statsText(e, if (selected) cargo else -1, selected),
        selected = selected,
        onClick = onSelect,
        role = Role.RadioButton,
        below = if (selected && e.cargoes.size > 1) {
            { SegmentedChoice(e.cargoes.toList(), if (cargo in e.cargoes) cargo else e.cargoes[0], { names.getOrElse(it) { "" } }, onSelect = onCargo) }
        } else null,
    )
}

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
        verticalArrangement = Arrangement.spacedBy(KitSpace.Gap),
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
    val gap = KitSpace.Gap
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
    if (short) Text(stringResource(Res.string.not_enough_money), color = c.warn, style = KitType.Dim)
    BigButton(stringResource(Res.string.buy_for, money(cost)), primary = true, enabled = !short && !blocked, glyph = Glyph.Plus, onClick = onBuy)
}
