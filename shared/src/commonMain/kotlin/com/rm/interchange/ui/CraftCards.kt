package com.rm.interchange.ui

import com.rm.interchange.res.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.stringArrayResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
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

/** A ship's or aircraft's stops under their Head, each marked with [glyph] and tapped open to change, and a button to add more. */
@Composable
private fun CraftOrders(view: WorldView, id: Int, orders: IntArray, order: Int, glyph: Glyph, adding: Boolean, onAdd: () -> Unit, set: (IntArray) -> Unit) {
    Head(stringResource(Res.string.orders))
    OrdersList(view, id, orders, order, OrderFlags.CRAFT, glyph, set)
    ButtonTiles(listOf(addStopsTile(adding, onAdd)))
    Box(Modifier.height(KitSpace.Tight))
}

/** A ship's or aircraft's buttons: start or stop, to its [depot] or carry on, renew and sell, and the switches in [toggles] under them. */
@Composable
private fun CraftActions(running: Boolean, inDepot: Boolean, toDepot: Boolean, renewCost: Long, depot: org.jetbrains.compose.resources.StringResource, order: (Int) -> Unit, toggles: @Composable () -> Unit) {
    ButtonTiles(
        listOf(
            TileButton(if (running) Glyph.Pause else Glyph.Play, stringResource(if (running) Res.string.stop else Res.string.start), strong = !running) { order(Command.TrainOrder.START_STOP) },
        ) + if (!inDepot) listOf(
            TileButton(Glyph.Depot, stringResource(if (toDepot) Res.string.carry_on else depot), lit = toDepot) { order(Command.TrainOrder.TO_DEPOT) },
        ) else listOf(
            TileButton(Glyph.Renew, stringResource(Res.string.renew_for, money(renewCost))) { order(Command.TrainOrder.RENEW) },
            TileButton(Glyph.Minus, stringResource(Res.string.sell)) { order(Command.TrainOrder.SELL) },
        ),
    )
    toggles()
}

/** A ship: its picture and what it's doing, its numbers, its stops and buttons to run it. */
@Composable
fun ShipCard(view: WorldView, roster: Roster, v: ShipView, addingStops: Boolean, onAddStops: () -> Unit, send: (Command) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val me = LocalCompany.current
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
        val e = roster[v.model]
        val mine = v.owner == me
        VehicleHeader(e, e.name(lang), doing, statusColour(false, v.lost || v.broken || v.iced), if (mine) null else view.companies.getOrElse(v.owner) { "" })
        VehicleStats(
            v.kmh, e.speed, v.age, false, v.reliability, v.profitThisYear, v.profitLastYear,
            if (v.capacity > 0) listOf(VehicleLoad(v.carries, v.load, v.capacity)) else emptyList(),
        )
        // Someone else's: what it is, but none of the controls.
        if (!mine) return@Card
        CraftOrders(view, v.id, v.orders, v.order, Glyph.Dock, addingStops, onAddStops) { order(Command.TrainOrder.ORDERS, it) }
        CraftActions(v.running, v.inDepot, v.toDepot, v.renewCost, Res.string.to_shipyard, { order(it) }) {
            SwitchRow(stringResource(Res.string.servicing), v.service) { order(Command.TrainOrder.SERVICE) }
        }
    }
}

/** An aircraft: its picture and what it's doing (and why, if it's holding), its numbers, its stops, and buttons to run it. */
@Composable
fun AircraftCard(view: WorldView, roster: Roster, v: AircraftView, addingStops: Boolean, onAddStops: () -> Unit, send: (Command) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val me = LocalCompany.current
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
        val mine = v.owner == me
        VehicleHeader(e, e.name(lang), doing, statusColour(v.wrecked, v.broken || v.state == Flight.HOLD), if (mine) null else view.companies.getOrElse(v.owner) { "" })
        val gear = stringArrayResource(Res.array.gear_kinds)
        VehicleStats(
            v.kmh, e.speed, v.age, false, v.reliability, v.profitThisYear, v.profitLastYear,
            if (v.capacity > 0) listOf(VehicleLoad(v.carries, v.load, v.capacity)) else emptyList(),
            listOfNotNull(
                (stringResource(Res.string.range) to stringResource(Res.string.km, Numbers.grouped(e.range.toLong()))).takeIf { e.range > 0 },
                (stringResource(Res.string.gear) to gear.getOrElse(v.gear) { "" }).takeIf { e.floats },
            ),
        )
        // Someone else's shows what it is but none of the controls, and a wreck is cleared away by itself.
        if (!mine || v.wrecked) return@Card
        CraftOrders(view, v.id, v.orders, v.order, Glyph.Airport, addingStops, onAddStops) { order(Command.TrainOrder.ORDERS, it) }
        CraftActions(v.running, v.inDepot, v.toDepot, v.renewCost, Res.string.to_hangar, { order(it) }) {
            SwitchRow(stringResource(Res.string.servicing), v.service) { order(Command.TrainOrder.SERVICE) }
            if (e.floats) SwitchRow(stringResource(Res.string.seasonal_gear), v.seasonalGear) { order(Command.CraftOrder.GEAR) }
        }
    }
}
