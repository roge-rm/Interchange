package com.rm.interchange.ui

import com.rm.interchange.res.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.stringArrayResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.rm.interchange.sim.Traction
import com.rm.interchange.sim.VehicleKind
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.Order
import com.rm.interchange.sim.RoadVehicleView
import com.rm.interchange.sim.Roster
import com.rm.interchange.sim.WorldView

/** A road vehicle's name, or "Vehicle 3" in the language shown if it hasn't one of its own. */
@Composable
fun roadVehicleName(v: RoadVehicleView): String = v.name.takeIf { it.isNotEmpty() } ?: stringResource(Res.string.road_vehicle_n, v.id + 1)

/** A garage: buying a bus, truck or streetcar, and those in it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GarageCard(view: WorldView, roster: Roster, depot: Int, year: Int, funds: Long, price: (Long) -> Long, send: (Command) -> Unit, onVehicle: (Int) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val me = LocalCompany.current
    val c = LocalColors.current
    val lang = languageCode()
    val models = roster.availableRoad(year)
    var model by remember { mutableIntStateOf(models.firstOrNull()?.id ?: -1) }
    var cargo by remember { mutableIntStateOf(-1) }
    val names = stringArrayResource(Res.array.cargoes)
    Card(stringResource(Res.string.garage), onClose, modifier) {
        if (models.isEmpty()) NoneOnSale(roster, VehicleKind.ROAD, year)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (e in models) Chip(stringResource(Res.string.engine_speed, e.name(lang), e.type(lang), e.speed), e.id == model) { model = e.id; cargo = -1 }
        }
        val e = roster.getOrNull(model)
        val choices = e?.cargoes ?: IntArray(0)
        if (choices.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (cg in choices) Chip(names.getOrElse(cg) { "" }, cg == (if (cargo in choices) cargo else choices[0])) { cargo = cg }
            }
        }
        if (e != null) {
            val cost = price(e.cost)
            if (e.tram) Text(stringResource(Res.string.needs_tram_rails), color = c.textDim, fontSize = 13.sp)
            ActionButton(stringResource(Res.string.buy_for, money(cost)), enabled = funds >= cost, strong = true) {
                send(Command.BuyRoadVehicle(me, depot, e.id, if (cargo in choices) cargo else -1))
            }
        }
        for (v in view.roadVehicles.filter { it.depot == depot && it.inDepot }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(roadVehicleName(v), color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                ActionButton(stringResource(Res.string.orders)) { onVehicle(v.id) }
                ActionButton(stringResource(if (v.running) Res.string.stop else Res.string.start), strong = !v.running) { send(Command.RoadOrder(me, v.id, Command.TrainOrder.START_STOP)) }
            }
        }
    }
}

/** A road vehicle: what it's doing, its stops, its tires, and buttons to run it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoadVehicleCard(view: WorldView, roster: Roster, v: RoadVehicleView, addingStops: Boolean, onAddStops: () -> Unit, send: (Command) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val me = LocalCompany.current
    val c = LocalColors.current
    val lang = languageCode()
    Card(roadVehicleName(v), onClose, modifier) {
        val target = if (v.orders.isEmpty()) null else Order.station(v.orders[v.order % v.orders.size])
        val doing = when {
            v.slid -> stringResource(Res.string.in_the_ditch)
            v.broken -> stringResource(Res.string.broken_down)
            v.inDepot && v.running -> stringResource(Res.string.waiting_to_leave)
            v.inDepot -> stringResource(Res.string.in_garage)
            !v.running -> stringResource(Res.string.stopped)
            v.servicing -> stringResource(Res.string.going_for_service, v.kmh)
            v.toDepot -> stringResource(Res.string.going_to_garage, v.kmh)
            v.waiting && target != null -> stringResource(Res.string.at_station, stationName(view, target))
            v.lost -> stringResource(Res.string.lost)
            target != null -> stringResource(Res.string.heading_for, stationName(view, target), v.kmh)
            else -> stringResource(Res.string.no_stops)
        }
        Text(roster[v.model].name(lang), color = c.textDim, fontSize = 13.sp)
        Text(doing, color = if (v.lost || v.broken || v.slid) c.warn else c.text, fontSize = 13.sp)
        val names = stringArrayResource(Res.array.cargoes)
        if (v.capacity > 0) Text(stringResource(Res.string.cargo_load, names.getOrElse(v.carries) { "" }, v.load, v.capacity), color = c.text, fontSize = 13.sp)
        Text(stringResource(Res.string.profit_line, money(v.profitThisYear), money(v.profitLastYear)), color = if (v.profitThisYear < 0) c.bad else c.textDim, fontSize = 13.sp)
        // Someone else's: what it is, but none of the controls.
        if (v.owner != me) {
            Text(stringResource(Res.string.owned_by, view.companies.getOrElse(v.owner) { "" }), color = c.textDim, fontSize = 13.sp)
            return@Card
        }
        Text(stringResource(Res.string.age_line_road, v.age, v.reliability), color = c.textDim, fontSize = 13.sp)
        val horses = roster[v.model].traction == Traction.HORSE
        val tires = stringArrayResource(Res.array.tire_kinds)
        if (!horses) Text(stringResource(Res.string.tires_line, tires.getOrElse(v.tires) { "" }), color = c.textDim, fontSize = 13.sp)
        // The stops, the one it's heading for lit; a tap opens what to do there.
        var open by remember(v.id) { mutableIntStateOf(-1) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((i, o) in v.orders.withIndex()) {
                val marks = buildString {
                    if (Order.has(o, Order.FULL_LOAD)) append(" ⬤")
                    if (Order.has(o, Order.NO_LOAD)) append(" ⊘")
                }
                Chip("${i + 1}. ${stationName(view, Order.station(o))}$marks", i == v.order % v.orders.size || i == open) { open = if (open == i) -1 else i }
            }
            Chip(stringResource(if (addingStops) Res.string.done_adding else Res.string.add_stops), addingStops, onClick = onAddStops)
        }
        v.orders.getOrNull(open)?.let { o ->
            fun change(new: Int) = send(Command.RoadOrder(me, v.id, Command.TrainOrder.ORDERS, v.orders.copyOf().also { it[open] = new }))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(stringResource(Res.string.full_load), Order.has(o, Order.FULL_LOAD)) { change(Order.toggle(o, Order.FULL_LOAD)) }
                Chip(stringResource(Res.string.no_loading), Order.has(o, Order.NO_LOAD)) { change(Order.toggle(o, Order.NO_LOAD)) }
                ActionButton(stringResource(Res.string.remove_stop)) {
                    send(Command.RoadOrder(me, v.id, Command.TrainOrder.ORDERS, v.orders.filterIndexed { j, _ -> j != open }.toIntArray()))
                    open = -1
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(stringResource(if (v.running) Res.string.stop else Res.string.start), strong = !v.running) { send(Command.RoadOrder(me, v.id, Command.TrainOrder.START_STOP)) }
            if (!v.inDepot) {
                ActionButton(stringResource(if (v.toDepot) Res.string.carry_on else Res.string.to_garage)) { send(Command.RoadOrder(me, v.id, Command.TrainOrder.TO_DEPOT)) }
                ActionButton(stringResource(Res.string.turn_round)) { send(Command.RoadOrder(me, v.id, Command.TrainOrder.TURN)) }
            } else {
                ActionButton(stringResource(Res.string.renew_for, money(v.renewCost))) { send(Command.RoadOrder(me, v.id, Command.TrainOrder.RENEW)) }
                ActionButton(stringResource(Res.string.sell)) { send(Command.RoadOrder(me, v.id, Command.TrainOrder.SELL)) }
            }
            Chip(stringResource(Res.string.servicing), v.service) { send(Command.RoadOrder(me, v.id, Command.TrainOrder.SERVICE)) }
            if (!horses) Chip(stringResource(Res.string.seasonal_tires), v.seasonalTires) { send(Command.RoadOrder(me, v.id, Command.RoadOrder.TIRES)) }
        }
    }
}
