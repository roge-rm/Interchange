package com.rm.interchange.ui

import com.rm.interchange.res.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.stringArrayResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
    val models = roster.availableRoad(year)
    var model by remember { mutableIntStateOf(models.firstOrNull()?.id ?: -1) }
    var cargo by remember { mutableIntStateOf(-1) }
    Card(stringResource(Res.string.garage), onClose, modifier, scrolls = false) {
        // What's picked, or the first on sale once the one picked goes off sale.
        val picked = models.firstOrNull { it.id == model } ?: models.firstOrNull()
        BuyPanel(
            top = {},
            list = {
                if (picked == null) NoneOnSale(roster, VehicleKind.ROAD, year)
                else BuyList(models, picked.id, cargo, price, { model = it; cargo = -1 }) { cargo = it }
            },
            footer = {
                picked?.let { e ->
                    if (e.tram) Text(stringResource(Res.string.needs_tram_rails), color = c.textDim, style = KitType.ListLine)
                    BuyButton(price(e.cost), funds) { send(Command.BuyRoadVehicle(me, depot, e.id, if (cargo in e.cargoes) cargo else -1)) }
                }
            },
            inDepot = {
                for (v in view.roadVehicles.filter { it.depot == depot && it.inDepot }) {
                    InDepotRow(roadVehicleName(v), v.running, { onVehicle(v.id) }) { send(Command.RoadOrder(me, v.id, Command.TrainOrder.START_STOP)) }
                }
            },
        )
    }
}

/** A road vehicle: its picture and what it's doing, its numbers, its stops, and buttons to run it. */
@Composable
fun RoadVehicleCard(view: WorldView, roster: Roster, v: RoadVehicleView, addingStops: Boolean, onAddStops: () -> Unit, send: (Command) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val me = LocalCompany.current
    val lang = languageCode()
    fun order(what: Int, stations: IntArray = IntArray(0)) = send(Command.RoadOrder(me, v.id, what, stations))
    Card(roadVehicleName(v), onClose, modifier) {
        val target = if (v.orders.isEmpty()) null else Order.station(v.orders[v.order % v.orders.size])
        val doing = when {
            v.wrecked -> stringResource(Res.string.wrecked)
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
        val e = roster[v.model]
        val mine = v.owner == me
        VehicleHeader(e, e.name(lang), doing, statusColour(v.wrecked, v.lost || v.broken || v.slid), if (mine) null else view.companies.getOrElse(v.owner) { "" })
        val horses = e.traction == Traction.HORSE
        val tires = stringArrayResource(Res.array.tire_kinds)
        VehicleStats(
            v.kmh, e.speed, v.age, false, v.reliability, v.profitThisYear, v.profitLastYear,
            if (v.capacity > 0) listOf(VehicleLoad(v.carries, v.load, v.capacity)) else emptyList(),
            if (horses) emptyList() else listOf(stringResource(Res.string.tires) to tires.getOrElse(v.tires) { "" }),
        )
        // Someone else's shows what it is but none of the controls, and a wreck is cleared away by itself.
        if (!mine || v.wrecked) return@Card
        Head(stringResource(Res.string.orders))
        OrdersList(view, v.id, v.orders, v.order, OrderFlags.ROAD, Glyph.RoadStop) { order(Command.TrainOrder.ORDERS, it) }
        ButtonTiles(listOf(addStopsTile(addingStops, onAddStops)))
        Box(Modifier.height(KitSpace.Tight))
        ButtonTiles(
            listOf(
                TileButton(if (v.running) Glyph.Pause else Glyph.Play, stringResource(if (v.running) Res.string.stop else Res.string.start), strong = !v.running) { order(Command.TrainOrder.START_STOP) },
            ) + if (!v.inDepot) listOf(
                TileButton(Glyph.Depot, stringResource(if (v.toDepot) Res.string.carry_on else Res.string.to_garage), lit = v.toDepot) { order(Command.TrainOrder.TO_DEPOT) },
                TileButton(Glyph.TurnLeft, stringResource(Res.string.turn_round)) { order(Command.TrainOrder.TURN) },
            ) else listOf(
                TileButton(Glyph.Renew, stringResource(Res.string.renew_for, money(v.renewCost))) { order(Command.TrainOrder.RENEW) },
                TileButton(Glyph.Minus, stringResource(Res.string.sell)) { order(Command.TrainOrder.SELL) },
            ),
        )
        SwitchRow(stringResource(Res.string.servicing), v.service) { order(Command.TrainOrder.SERVICE) }
        if (!horses) SwitchRow(stringResource(Res.string.seasonal_tires), v.seasonalTires) { order(Command.RoadOrder.TIRES) }
    }
}
