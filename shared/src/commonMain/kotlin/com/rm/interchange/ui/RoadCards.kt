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
import androidx.compose.runtime.mutableStateOf
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
fun roadVehicleName(v: RoadVehicleView): String = v.name.takeIf { it.isNotEmpty() } ?: stringResource(Res.string.road_vehicle_n, if (v.number > 0) v.number else v.id + 1)

/** A garage: buying a bus, truck or streetcar, and those in it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GarageCard(view: WorldView, roster: Roster, depot: Int, year: Int, funds: Long, price: (Long) -> Long, send: (Command) -> Unit, onVehicle: (Int) -> Unit, copying: Boolean, onCopy: (Int) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val me = LocalCompany.current
    val c = LocalColors.current
    val models = roster.availableRoad(year)
    var model by remember { mutableIntStateOf(models.firstOrNull()?.id ?: -1) }
    var vehicleCount by remember(depot) { mutableIntStateOf(1) }
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
                    BuyButton(price(e.cost) * vehicleCount, funds) { send(Command.BuyRoadVehicle(me, depot, e.id, if (cargo in e.cargoes) cargo else -1, vehicleCount)) }
                }
                VehicleCount(vehicleCount) { vehicleCount = it }
                ActionButton(stringResource(Res.string.copy_one), strong = copying, onClick = { onCopy(vehicleCount) })
            },
            inDepot = {
                for (v in view.roadVehicles.filter { it.depot == depot && it.inDepot }) {
                    InDepotRow(
                        roadVehicleName(v), v.running, { onVehicle(v.id) },
                        onCopy = if (v.owner == me) { { send(Command.CopyVehicle(me, Command.CopyVehicle.ROAD, v.id, depot, vehicleCount)) } } else null,
                    ) { send(Command.RoadOrder(me, v.id, Command.TrainOrder.START_STOP)) }
                }
            },
        )
    }
}

/** A road vehicle: its picture and what it's doing, its numbers, its stops, and buttons to run it. */
@Composable
fun RoadVehicleCard(view: WorldView, roster: Roster, v: RoadVehicleView, year: Int, funds: Long, price: (Long) -> Long, addingStops: Boolean, onAddStops: () -> Unit, send: (Command) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val me = LocalCompany.current
    val lang = languageCode()
    fun order(what: Int, stations: IntArray = IntArray(0)) = send(Command.RoadOrder(me, v.id, what, stations))
    // While adding stops, only the orders and what goes with adding them, so the map stays clear.
    if (addingStops && v.owner == me && !v.wrecked) {
        AddingStopsCard(roadVehicleName(v), view, v.id, v.orders, v.order, OrderFlags.ROAD, Glyph.RoadStop, { order(Command.TrainOrder.ORDERS, it) }, onAddStops, onClose, modifier)
        return
    }
    Card(roadVehicleName(v), onClose, modifier) {
        val doing = roadStatus(view, v)
        val e = roster[v.model]
        val mine = v.owner == me
        VehicleHeader(e, e.name(lang), doing, statusColour(v.wrecked, roadWrong(v)), if (mine) null else view.companies.getOrElse(v.owner) { "" })
        val horses = e.traction == Traction.HORSE
        val tires = stringArrayResource(Res.array.tire_kinds)
        VehicleStats(
            v.kmh, e.speed, v.age, false, v.reliability, v.profitThisYear, v.profitLastYear,
            if (v.capacity > 0) listOf(VehicleLoad(v.carries, v.load, v.capacity)) else emptyList(),
            if (horses) emptyList() else listOf(stringResource(Res.string.tires) to tires.getOrElse(v.tires) { "" }),
        )
        // Someone else's shows what it is but none of the controls, and a wreck is cleared away by itself.
        if (!mine || v.wrecked) return@Card
        VehicleGroupControls(view, Command.CopyVehicle.ROAD, v.id, v.group, send) { order(Command.TrainOrder.GROUP, intArrayOf(it)) }
        if (v.inDepot && e.cargoes.size > 1) {
            val cargoes = stringArrayResource(Res.array.cargoes)
            SegmentedChoice(e.cargoes.toList(), v.carries, { cargoes[it] }) { order(Command.TrainOrder.REFIT, intArrayOf(it)) }
        }
        Head(stringResource(Res.string.orders))
        val group = if (v.shared >= 0) view.roadVehicles.count { it.id != v.id && it.shared == v.shared } else 0
        val others = view.roadVehicles.filter { it.id != v.id && it.owner == me }
            .map { SharedOrderChoice(it.id, roadVehicleName(it), it.model, it.orders) }
        SharedOrdersControls(view, roster, v.id, group, v.gap, others, addingStops, onAddStops, ::order) {
            OrdersList(view, v.id, v.orders, v.order, OrderFlags.ROAD, Glyph.RoadStop) { order(Command.TrainOrder.ORDERS, it) }
        }
        Box(Modifier.height(KitSpace.Tight))
        var replacing by remember(v.id) { mutableStateOf(false) }
        ButtonTiles(
            listOf(
                TileButton(if (v.running) Glyph.Pause else Glyph.Play, stringResource(if (v.running) Res.string.stop else Res.string.start), strong = !v.running) { order(Command.TrainOrder.START_STOP) },
            ) + if (!v.inDepot) listOf(
                TileButton(Glyph.Depot, stringResource(if (v.toDepot) Res.string.carry_on else Res.string.to_garage), lit = v.toDepot) { order(Command.TrainOrder.TO_DEPOT) },
                TileButton(Glyph.TurnLeft, stringResource(Res.string.turn_round)) { order(Command.TrainOrder.TURN) },
            ) else listOf(
                TileButton(Glyph.Renew, stringResource(Res.string.renew_for, money(v.renewCost))) { order(Command.TrainOrder.RENEW) },
                TileButton(Glyph.Replace, stringResource(Res.string.replace), lit = replacing) { replacing = !replacing },
                TileButton(Glyph.Minus, stringResource(Res.string.sell)) { order(Command.TrainOrder.SELL) },
            ),
        )
        if (v.inDepot && replacing) {
            ReplaceList(roster.availableRoad(year).filter { it.id != v.model }, funds, { price(it.cost) - (price(e.cost) - v.renewCost) }) {
                order(Command.TrainOrder.REPLACE, intArrayOf(it))
                replacing = false
            }
        }
        SwitchRow(stringResource(Res.string.servicing), v.service) { order(Command.TrainOrder.SERVICE) }
        if (!horses) SwitchRow(stringResource(Res.string.seasonal_tires), v.seasonalTires) { order(Command.RoadOrder.TIRES) }
    }
}
