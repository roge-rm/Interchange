package com.rm.interchange.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.rm.interchange.res.*
import com.rm.interchange.sim.Cargo
import com.rm.interchange.sim.MAX_LOCOMOTIVES
import com.rm.interchange.sim.MAX_WAGONS
import com.rm.interchange.sim.Roster
import com.rm.interchange.sim.Train
import com.rm.interchange.sim.TrainCar
import com.rm.interchange.sim.TrainView
import com.rm.interchange.sim.Traction
import com.rm.interchange.sim.VehicleKind
import com.rm.interchange.sim.trainFormationCost
import com.rm.interchange.sim.validTrainFormation
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/** All the models in a train, in their running order, with adjacent identical cars counted together. */
@Composable
internal fun trainFormationName(roster: Roster, t: TrainView): String {
    val lang = languageCode()
    if (t.formation.isEmpty()) return roster[t.engine].name(lang)
    val parts = ArrayList<String>()
    var at = 0
    while (at < t.formation.size) {
        val car = t.formation[at]
        var end = at + 1
        while (end < t.formation.size && t.formation[end] == car) end++
        val name = roster[car.model].name(lang)
        parts += if (end - at == 1) name else "${end - at} × $name"
        at = end
    }
    return parts.joinToString(" · ")
}

/** A draft stays local until Apply sends the whole formation as one command. */
@Composable
internal fun TrainFormationEditor(
    roster: Roster, t: TrainView, year: Int, funds: Long, price: (Long) -> Long, wired: Boolean,
    apply: (List<TrainCar>) -> Unit, cancel: () -> Unit,
) {
    val colours = LocalColors.current
    val lang = languageCode()
    val cargoNames = stringArrayResource(Res.array.cargoes)
    var draft by remember(t.id, t.formation) { mutableStateOf(t.formation) }
    var selected by remember(t.id) { mutableIntStateOf(0) }
    var picking by remember(t.id) { mutableStateOf(false) }
    var wagons by remember(t.id) { mutableStateOf(true) }
    var model by remember(t.id) { mutableIntStateOf(-1) }
    var cargo by remember(t.id) { mutableIntStateOf(-1) }
    fun move(from: Int, to: Int) {
        draft = draft.toMutableList().also { it.add(to, it.removeAt(from)) }
        selected = to
    }
    Head(stringResource(Res.string.train_formation))
    Text(stringResource(Res.string.formation_help), color = colours.textDim, style = KitType.Dim)
    for ((index, car) in draft.withIndex()) {
        val e = roster[car.model]
        val chosen = index == selected
        val carried = if (car.cargo in e.cargoes) car.cargo else e.cargoes.firstOrNull() ?: -1
        PictureRow(
            stringResource(Res.string.formation_car, index + 1, e.name(lang)),
            picture = { VehiclePicture(e, it) }, selected = chosen, onClick = { selected = index },
            line = if (e.tender >= 0) stringResource(Res.string.formation_tender, roster[e.tender].name(lang)) else null,
            stats = if (carried >= 0 && e.capacity > 0) cargoAmount(carried, e.capacity) else null,
        )
        if (chosen) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitSpace.Inline)) {
                SquareButton(Glyph.Up, stringResource(Res.string.move_up), 36.dp, enabled = index > 0) { move(index, index - 1) }
                SquareButton(Glyph.Down, stringResource(Res.string.move_down), 36.dp, enabled = index < draft.lastIndex) { move(index, index + 1) }
                SquareButton(Glyph.Minus, stringResource(Res.string.remove), 36.dp) {
                    draft = draft.filterIndexed { i, _ -> i != index }
                    selected = minOf(index, draft.lastIndex)
                }
            }
            if (e.cargoes.size > 1) SegmentedChoice(e.cargoes.toList(), carried, { cargoNames[it] }) { choice ->
                draft = draft.mapIndexed { i, old -> if (i == index) old.copy(cargo = choice) else old }
            }
        }
    }
    ActionButton(stringResource(Res.string.formation_add), enabled = draft.size < MAX_WAGONS + MAX_LOCOMOTIVES) { picking = !picking }
    if (picking) {
        FillChips(listOf(false, true), wagons, { stringResource(if (it) Res.string.wagons else Res.string.engines) }) { wagons = it; model = -1; cargo = -1 }
        val models = roster.available(year, pulls = !wagons).filter { wired || !it.pulls || it.traction != Traction.ELECTRIC }
        val chosen = models.firstOrNull { it.id == model } ?: models.firstOrNull()
        if (chosen == null) NoneOnSale(roster, if (wagons) VehicleKind.WAGON else VehicleKind.ENGINE, year)
        else {
            BuyList(
                models, chosen.id, cargo, price, { model = it; cargo = -1 },
                costOf = { it.cost + (roster.getOrNull(it.tender)?.cost ?: 0) },
                runningOf = { it.running + (roster.getOrNull(it.tender)?.running ?: 0) },
            ) { cargo = it }
            val room = if (wagons) draft.count { roster[it.model].kind == VehicleKind.WAGON } < MAX_WAGONS
                else draft.count { roster[it.model].pulls } < MAX_LOCOMOTIVES
            ActionButton(stringResource(Res.string.formation_add_model, chosen.name(lang)), enabled = room) {
                draft = draft + TrainCar(chosen.id, if (cargo in chosen.cargoes) cargo else -1)
                selected = draft.lastIndex
                picking = false
            }
        }
    }
    val valid = validTrainFormation(roster, draft)
    val preview = if (valid) Train(roster, t.id, t.owner, t.engine, t.wagon, 0, t.depot, formation = draft) else null
    val cost = trainFormationCost(roster, t.formation, draft, t.age, price)
    val fits = preview != null && (0 until Cargo.COUNT).all { t.load[it] <= preview.capacity(it) }
    if (preview != null) {
        // With a dual-mode engine, its numbers under wires and off them.
        val power = draft.sumOf { roster[it.model].power }.toLong()
        val dieselPower = draft.sumOf { roster[it.model].power(false) }.toLong()
        val speedText = if (preview.dieselSpeed != preview.topSpeed) stringResource(Res.string.kmh_dual, preview.topSpeed, preview.dieselSpeed)
        else stringResource(Res.string.kmh, preview.topSpeed)
        val powerText = if (dieselPower != power) stringResource(Res.string.horsepower_dual, Numbers.grouped(power), Numbers.grouped(dieselPower))
        else stringResource(Res.string.horsepower, Numbers.grouped(power))
        Head(stringResource(Res.string.formation_preview))
        StatColumns(listOf(
            stringResource(Res.string.top_speed) to speedText,
            stringResource(Res.string.formation_weight) to stringResource(Res.string.formation_tonnes, preview.weight),
            stringResource(Res.string.formation_power) to powerText,
        ))
        val tenths = preview.length * 10 / (com.rm.interchange.sim.FIX * 16)
        InfoRow(stringResource(Res.string.formation_length), stringResource(Res.string.formation_tiles, tenths / 10, tenths % 10))
        InfoRow(stringResource(Res.string.formation_running), stringResource(Res.string.a_year, money(price(preview.carEngines.sumOf { roster[it].running }))))
        for (cg in 0 until Cargo.COUNT) if (preview.capacity(cg) > 0) CargoRow(cg, cargoAmount(cg, preview.capacity(cg)))
    }
    val problem = when {
        !valid -> stringResource(Res.string.formation_limits, MAX_LOCOMOTIVES, MAX_WAGONS)
        !fits -> stringResource(Res.string.formation_loaded)
        cost > 0 && cost > funds -> stringResource(Res.string.not_enough_money)
        else -> null
    }
    if (problem != null) Text(problem, color = colours.warn, style = KitType.Body)
    ActionButton(
        stringResource(if (cost < 0) Res.string.formation_apply_credit else Res.string.formation_apply_cost, money(if (cost < 0) -cost else cost)),
        enabled = problem == null && draft != t.formation, strong = true,
    ) { apply(draft) }
    ActionButton(stringResource(Res.string.cancel), onClick = cancel)
}
