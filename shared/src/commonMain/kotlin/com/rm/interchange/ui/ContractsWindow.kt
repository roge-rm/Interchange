package com.rm.interchange.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rm.interchange.res.*
import com.rm.interchange.server.GameThread
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.Contract
import com.rm.interchange.sim.ContractView
import com.rm.interchange.sim.World
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/** The place contract [k] delivers to: its industry's or its town's name. */
@Composable
private fun contractPlace(world: World, k: ContractView): String =
    if (k.industry >= 0) world.industries.getOrNull(k.industry)?.let { industryName(world, it, languageCode()) } ?: ""
    else world.towns.getOrNull(k.town)?.name ?: ""

/** What contract [k] asks for, as words. */
@Composable
fun contractText(world: World, k: ContractView): String {
    val amount = cargoNames().amount(k.cargo, k.amount)
    val place = contractPlace(world, k)
    if (k.season != Contract.BY_DATE) return stringResource(Res.string.contract_monthly, amount, place)
    val months = stringArrayResource(Res.array.months)
    return stringResource(Res.string.contract_total, amount, place, months.getOrElse(k.untilMonth) { "" })
}

/** Where contract [i] delivers to on the map, its industry or town, or null if it's gone. */
fun contractMapPlace(world: World, i: Int): Pair<Float, Float>? {
    val k = world.contracts.getOrNull(i) ?: return null
    return placeOf(world, k.industry, k.town)
}

/** The map place of industry [industry], or of town [town] when there's none. */
private fun placeOf(world: World, industry: Int, town: Int): Pair<Float, Float>? {
    if (industry >= 0) return world.industries.getOrNull(industry)?.let { it.x + 1f to it.y + 1f }
    return world.towns.getOrNull(town)?.let { it.x + 0.5f to it.y + 0.5f }
}

/**
 * Contracts on offer, with a way to accept each, and the company's own with how this month stands; a tap goes to the
 * place. Read from [runner]'s view, so an accepted one moves under Yours as soon as the view has it.
 */
@Composable
fun ContractsWindow(runner: GameThread, world: World, onGo: (Float, Float) -> Unit, send: (Command) -> Unit, onClose: () -> Unit) {
    val c = LocalColors.current
    val me = LocalCompany.current
    val months = stringArrayResource(Res.array.months)
    val view by produceState(runner.view, runner) {
        while (true) withFrameNanos { value = runner.view }
    }
    val views = view?.contracts.orEmpty()
    val offers = views.filter { it.status == Contract.OFFERED }
    val mine = views.filter { it.status == Contract.RUNNING }
    Window(stringResource(Res.string.contracts), onClose, Glyph.Package) {
        if (offers.isEmpty() && mine.isEmpty()) Text(stringResource(Res.string.no_contracts), color = c.textDim, style = KitType.Body)
        if (offers.isNotEmpty()) Head(stringResource(Res.string.contracts_offers))
        for (k in offers) {
            val terms = when (k.season) {
                Contract.BY_DATE -> stringResource(Res.string.contract_offer_date, money(k.reward), money(k.penalty))
                Contract.WINTER -> stringResource(Res.string.contract_offer_winter, k.months, money(k.reward), money(k.penalty))
                else -> stringResource(Res.string.contract_offer_line, k.months, money(k.reward), money(k.penalty))
            }
            // The last month it's open: it lapses at the start of offerMonth.
            val last = k.offerYear * 12 + k.offerMonth - 1
            val ends = stringResource(Res.string.month_year, months.getOrElse(last % 12) { "" }, last / 12)
            val place = placeOf(world, k.industry, k.town)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ListRow(Glyph.Package, contractText(world, k), terms + "\n" + stringResource(Res.string.contract_offer_ends, ends), place?.let { p -> { onGo(p.first, p.second) } })
                if (me >= 0) BigButton(stringResource(Res.string.accept), primary = true, glyph = Glyph.Tick) { send(Command.AcceptContract(me, k.id)) }
            }
        }
        if (mine.isNotEmpty()) Head(stringResource(Res.string.contracts_yours))
        for (k in mine) {
            val total = k.season == Contract.BY_DATE
            val cargo = cargoNames()
            val line = if (total) stringResource(Res.string.contract_progress_total, cargo.amount(k.cargo, k.count), cargo.amount(k.cargo, k.amount))
                else stringResource(Res.string.contract_progress, cargo.amount(k.cargo, k.count), cargo.amount(k.cargo, k.amount), k.met, k.misses)
            val place = placeOf(world, k.industry, k.town)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ListRow(Glyph.Package, contractText(world, k), line, place?.let { p -> { onGo(p.first, p.second) } }, lineColour = if (k.misses > 0) c.bad else null)
                FillBar(if (k.amount > 0) k.count.toFloat() / k.amount else 1f, Modifier.fillMaxWidth().padding(horizontal = 10.dp))
            }
        }
    }
}
