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
import com.rm.interchange.sim.AircraftView
import com.rm.interchange.sim.Airport
import com.rm.interchange.sim.AirportView
import com.rm.interchange.sim.Airports
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.DEICE_COST
import com.rm.interchange.sim.DEICE_FROM
import com.rm.interchange.sim.Engine
import com.rm.interchange.sim.Flight
import com.rm.interchange.sim.ILS_COST
import com.rm.interchange.sim.ILS_FROM
import com.rm.interchange.sim.Order
import com.rm.interchange.sim.Roster
import com.rm.interchange.sim.Runways
import com.rm.interchange.sim.ShipView
import com.rm.interchange.sim.VehicleKind
import com.rm.interchange.sim.WorldView
import com.rm.interchange.sim.serves

@Composable
fun shipName(v: ShipView): String = v.name.takeIf { it.isNotEmpty() } ?: stringResource(Res.string.ship_n, v.id + 1)

@Composable
fun aircraftName(v: AircraftView): String = v.name.takeIf { it.isNotEmpty() } ?: stringResource(Res.string.aircraft_n, v.id + 1)

/** What a buying card says when there's nothing of [kind] to buy: when the first come, if that's still ahead. */
@Composable
fun NoneOnSale(roster: Roster, kind: VehicleKind, year: Int) {
    val first = roster.firstOf(kind)
    Text(
        if (first != null && first > year) stringResource(Res.string.none_on_sale_until, first) else stringResource(Res.string.none_on_sale),
        color = LocalColors.current.textDim, fontSize = 13.sp,
    )
}

/**
 * A shipyard's or hangar's body: [top], then the models of [kind] on sale this year that [fits] lets in to pick one
 * from, a button to buy it, and [inDepot], the craft in it.
 */
@Composable
private fun CraftBuyPanel(
    roster: Roster, kind: VehicleKind, year: Int, funds: Long, price: (Long) -> Long, fits: (Engine) -> Boolean, buy: (Int, Int) -> Unit,
    top: @Composable () -> Unit = {}, inDepot: @Composable () -> Unit,
) {
    val models = roster.availableOf(kind, year).filter(fits)
    var model by remember { mutableIntStateOf(-1) }
    var cargo by remember { mutableIntStateOf(-1) }
    val e = models.firstOrNull { it.id == model } ?: models.firstOrNull()
    BuyPanel(
        top = top,
        list = {
            if (e == null) NoneOnSale(roster, kind, year)
            else BuyList(models, e.id, cargo, price, { model = it; cargo = -1 }) { cargo = it }
        },
        footer = { if (e != null) BuyButton(price(e.cost), funds) { buy(e.id, if (cargo in e.cargoes) cargo else -1) } },
        inDepot = inDepot,
    )
}

/** One craft in a shipyard or hangar, with buttons for its orders and to start or stop it. */
@Composable
private fun CraftInDepot(name: String, running: Boolean, onOrders: () -> Unit, onStartStop: () -> Unit) {
    val c = LocalColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        ActionButton(stringResource(Res.string.orders), onClick = onOrders)
        ActionButton(stringResource(if (running) Res.string.stop else Res.string.start), strong = !running, onClick = onStartStop)
    }
}

/** A shipyard: buying a ship, and those in it. */
@Composable
fun ShipyardCard(view: WorldView, roster: Roster, depot: Int, year: Int, funds: Long, price: (Long) -> Long, send: (Command) -> Unit, onShip: (Int) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val me = LocalCompany.current
    Card(stringResource(Res.string.tool_shipyard), onClose, modifier, scrolls = false) {
        CraftBuyPanel(roster, VehicleKind.SHIP, year, funds, price, { true }, { model, cargo -> send(Command.BuyCraft(me, depot, model, cargo)) }) {
            for (v in view.ships.filter { it.depot == depot && it.inDepot }) {
                CraftInDepot(shipName(v), v.running, { onShip(v.id) }) { send(Command.CraftOrder(me, false, v.id, Command.TrainOrder.START_STOP)) }
            }
        }
    }
}

/** An airport: what it has, adding landing aids and de-icing, buying an aircraft that can use it, and those in its hangar. */
@Composable
fun AirportCard(view: WorldView, roster: Roster, airport: AirportView, year: Int, funds: Long, price: (Long) -> Long, send: (Command) -> Unit, onAircraft: (Int) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val me = LocalCompany.current
    val c = LocalColors.current
    val kinds = stringArrayResource(Res.array.airport_kinds)
    val station = view.stations.firstOrNull { it.id == airport.station }
    val title = station?.let { stationName(view, it.id) } ?: kinds.getOrElse(airport.kind) { "" }
    // Only the kind and its runway matter for who can land, so a stand-in airport answers that.
    val stand = Airport(airport.id, 0, airport.station, airport.kind, airport.x, airport.y, airport.axis, airport.depot)
    Card(title, onClose, modifier, scrolls = false) {
        CraftBuyPanel(
            roster, VehicleKind.AIRCRAFT, year, funds, price, { stand.serves(it) }, { model, cargo -> send(Command.BuyCraft(me, airport.depot, model, cargo)) },
            top = {
                Text(kinds.getOrElse(airport.kind) { "" }, color = c.textDim, fontSize = 13.sp)
                val has = listOfNotNull(
                    stringResource(Res.string.has_landing_aids).takeIf { airport.ils },
                    stringResource(Res.string.has_deicing).takeIf { airport.deice },
                )
                if (has.isNotEmpty()) Text(has.joinToString(stringResource(Res.string.list_join)), color = c.textDim, fontSize = 13.sp)
                val ils = !airport.ils && year >= ILS_FROM && Airports.CLASS[airport.kind] >= Runways.REGIONAL
                val deice = !airport.deice && year >= DEICE_FROM && airport.kind != Airports.SEAPLANE
                if (ils || deice) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (ils) {
                        val cost = price(ILS_COST)
                        ActionButton(stringResource(Res.string.landing_aids_for, money(cost)), enabled = funds >= cost) { send(Command.AirportUpgrade(me, airport.id, 0)) }
                    }
                    if (deice) {
                        val cost = price(DEICE_COST)
                        ActionButton(stringResource(Res.string.deicing_for, money(cost)), enabled = funds >= cost) { send(Command.AirportUpgrade(me, airport.id, 1)) }
                    }
                }
            },
        ) {
            for (v in view.aircraft.filter { it.depot == airport.depot && it.inDepot }) {
                CraftInDepot(aircraftName(v), v.running, { onAircraft(v.id) }) { send(Command.CraftOrder(me, true, v.id, Command.TrainOrder.START_STOP)) }
            }
        }
    }
}

/** The stops on a ship's or aircraft's orders, tapping one to take it off, and a chip to add more by tapping stations. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StopsRow(view: WorldView, orders: IntArray, order: Int, adding: Boolean, onAdd: () -> Unit, set: (IntArray) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((i, o) in orders.withIndex()) {
            Chip("${i + 1}. ${stationName(view, Order.station(o))}", i == order % orders.size) { set(orders.filterIndexed { j, _ -> j != i }.toIntArray()) }
        }
        Chip(stringResource(if (adding) Res.string.done_adding else Res.string.add_stops), adding, onClick = onAdd)
    }
}

/** A ship: what it's doing, its stops and buttons to run it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShipCard(view: WorldView, roster: Roster, v: ShipView, addingStops: Boolean, onAddStops: () -> Unit, send: (Command) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val me = LocalCompany.current
    val c = LocalColors.current
    val lang = languageCode()
    fun order(what: Int, stations: IntArray = IntArray(0)) = send(Command.CraftOrder(me, false, v.id, what, stations))
    Card(shipName(v), onClose, modifier) {
        val target = if (v.orders.isEmpty()) null else Order.station(v.orders[v.order % v.orders.size])
        val doing = when {
            v.broken -> stringResource(Res.string.broken_down)
            v.inDepot && v.running -> stringResource(Res.string.waiting_to_leave)
            v.inDepot -> stringResource(Res.string.in_shipyard)
            !v.running -> stringResource(Res.string.stopped)
            v.stormbound -> stringResource(Res.string.storm_in_port)
            v.servicing -> stringResource(Res.string.going_to_shipyard, v.kmh)
            v.toDepot -> stringResource(Res.string.going_to_shipyard, v.kmh)
            v.waiting && target != null -> stringResource(Res.string.at_station, stationName(view, target))
            v.iced -> stringResource(Res.string.iced_in)
            v.lost -> stringResource(Res.string.lost)
            target != null -> stringResource(Res.string.heading_for, stationName(view, target), v.kmh)
            else -> stringResource(Res.string.no_stops)
        }
        Text(roster[v.model].name(lang), color = c.textDim, fontSize = 13.sp)
        Text(doing, color = if (v.lost || v.broken || v.iced) c.warn else c.text, fontSize = 13.sp)
        val names = stringArrayResource(Res.array.cargoes)
        if (v.capacity > 0) Text(stringResource(Res.string.cargo_load, names.getOrElse(v.carries) { "" }, v.load, v.capacity), color = c.text, fontSize = 13.sp)
        Text(stringResource(Res.string.profit_line, money(v.profitThisYear), money(v.profitLastYear)), color = if (v.profitThisYear < 0) c.bad else c.textDim, fontSize = 13.sp)
        // Someone else's: what it is, but none of the controls.
        if (v.owner != me) {
            Text(stringResource(Res.string.owned_by, view.companies.getOrElse(v.owner) { "" }), color = c.textDim, fontSize = 13.sp)
            return@Card
        }
        Text(stringResource(Res.string.age_line_road, v.age, v.reliability), color = c.textDim, fontSize = 13.sp)
        StopsRow(view, v.orders, v.order, addingStops, onAddStops) { order(Command.TrainOrder.ORDERS, it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(stringResource(if (v.running) Res.string.stop else Res.string.start), strong = !v.running) { order(Command.TrainOrder.START_STOP) }
            if (!v.inDepot) {
                ActionButton(stringResource(if (v.toDepot) Res.string.carry_on else Res.string.to_shipyard)) { order(Command.TrainOrder.TO_DEPOT) }
            } else {
                ActionButton(stringResource(Res.string.renew_for, money(v.renewCost))) { order(Command.TrainOrder.RENEW) }
                ActionButton(stringResource(Res.string.sell)) { order(Command.TrainOrder.SELL) }
            }
            Chip(stringResource(Res.string.servicing), v.service) { order(Command.TrainOrder.SERVICE) }
        }
    }
}

/** An aircraft: what it's doing (and why, if it's holding), its stops, its gear, and buttons to run it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AircraftCard(view: WorldView, roster: Roster, v: AircraftView, addingStops: Boolean, onAddStops: () -> Unit, send: (Command) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val me = LocalCompany.current
    val c = LocalColors.current
    val lang = languageCode()
    val e = roster[v.model]
    fun order(what: Int, stations: IntArray = IntArray(0)) = send(Command.CraftOrder(me, true, v.id, what, stations))
    Card(aircraftName(v), onClose, modifier) {
        val target = if (v.orders.isEmpty()) null else Order.station(v.orders[v.order % v.orders.size])
        val reasons = stringArrayResource(Res.array.hold_reasons)
        val reason = when (v.holdingFor) { "storm" -> 0; "fog" -> 1; "gates" -> 2; "ice" -> 3; "gear" -> 4; "snow" -> 5; "wreck" -> 7; else -> 6 }
        val doing = when {
            v.wrecked -> stringResource(Res.string.wrecked)
            v.broken -> stringResource(Res.string.broken_down)
            v.inDepot && v.running -> stringResource(Res.string.waiting_to_leave)
            v.inDepot -> stringResource(Res.string.in_hangar)
            !v.running && v.state == Flight.GATE -> stringResource(Res.string.stopped)
            v.state == Flight.HOLD && target != null -> stringResource(Res.string.holding_over, stationName(view, target), reasons.getOrElse(reason) { "" })
            v.state == Flight.TAKEOFF -> stringResource(Res.string.taking_off)
            v.state == Flight.LANDING && target != null -> stringResource(Res.string.landing_at, stationName(view, target))
            v.toDepot -> stringResource(Res.string.going_to_hangar, v.kmh)
            v.state == Flight.GATE && target != null && v.waiting -> stringResource(Res.string.at_station, stationName(view, target))
            target != null -> stringResource(Res.string.heading_for, stationName(view, target), v.kmh)
            else -> stringResource(Res.string.no_stops)
        }
        Text(e.name(lang), color = c.textDim, fontSize = 13.sp)
        Text(doing, color = if (v.wrecked) c.bad else if (v.broken || v.state == Flight.HOLD) c.warn else c.text, fontSize = 13.sp)
        val names = stringArrayResource(Res.array.cargoes)
        if (v.capacity > 0) Text(stringResource(Res.string.cargo_load, names.getOrElse(v.carries) { "" }, v.load, v.capacity), color = c.text, fontSize = 13.sp)
        Text(stringResource(Res.string.profit_line, money(v.profitThisYear), money(v.profitLastYear)), color = if (v.profitThisYear < 0) c.bad else c.textDim, fontSize = 13.sp)
        // Someone else's: what it is, but none of the controls.
        if (v.owner != me) {
            Text(stringResource(Res.string.owned_by, view.companies.getOrElse(v.owner) { "" }), color = c.textDim, fontSize = 13.sp)
            return@Card
        }
        // A wreck can't be given orders or sold; it's cleared away by itself.
        if (v.wrecked) return@Card
        Text(stringResource(Res.string.age_line_road, v.age, v.reliability), color = c.textDim, fontSize = 13.sp)
        if (e.range > 0) Text(stringResource(Res.string.range_line, e.range), color = c.textDim, fontSize = 13.sp)
        if (e.floats) {
            val gear = stringArrayResource(Res.array.gear_kinds)
            Text(stringResource(Res.string.tires_line, gear.getOrElse(v.gear) { "" }), color = c.textDim, fontSize = 13.sp)
        }
        StopsRow(view, v.orders, v.order, addingStops, onAddStops) { order(Command.TrainOrder.ORDERS, it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(stringResource(if (v.running) Res.string.stop else Res.string.start), strong = !v.running) { order(Command.TrainOrder.START_STOP) }
            if (!v.inDepot) {
                ActionButton(stringResource(if (v.toDepot) Res.string.carry_on else Res.string.to_hangar)) { order(Command.TrainOrder.TO_DEPOT) }
            } else {
                ActionButton(stringResource(Res.string.renew_for, money(v.renewCost))) { order(Command.TrainOrder.RENEW) }
                ActionButton(stringResource(Res.string.sell)) { order(Command.TrainOrder.SELL) }
            }
            Chip(stringResource(Res.string.servicing), v.service) { order(Command.TrainOrder.SERVICE) }
            if (e.floats) Chip(stringResource(Res.string.seasonal_gear), v.seasonalGear) { order(Command.CraftOrder.GEAR) }
        }
    }
}
