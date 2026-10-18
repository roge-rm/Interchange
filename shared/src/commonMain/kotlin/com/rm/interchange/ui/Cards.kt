package com.rm.interchange.ui

import com.rm.interchange.res.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource
import androidx.compose.foundation.layout.height
import org.jetbrains.compose.resources.stringArrayResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.Engine
import com.rm.interchange.sim.VehicleKind
import com.rm.interchange.sim.Traction
import com.rm.interchange.sim.Cargo
import com.rm.interchange.sim.Order
import com.rm.interchange.sim.Roster
import com.rm.interchange.sim.MAX_WAGONS
import com.rm.interchange.sim.StationView
import com.rm.interchange.sim.TrainView
import com.rm.interchange.sim.WorldView

/** A station's name, or "Station 4" in the language shown if it hasn't one of its own. */
@Composable
fun stationName(view: WorldView, id: Int): String {
    val s = view.stations.firstOrNull { it.id == id }
    return s?.name?.takeIf { it.isNotEmpty() } ?: stringResource(Res.string.station_n, id + 1)
}

/** A train's name, or "Train 2" in the language shown if it hasn't one of its own. */
@Composable
fun trainName(t: TrainView): String = t.name.takeIf { it.isNotEmpty() } ?: stringResource(Res.string.train_n, t.id + 1)

/** A train: its picture and what it's doing, its numbers, its orders, and buttons to run it. */
@Composable
fun TrainCard(
    view: WorldView,
    roster: Roster,
    t: TrainView,
    addingStops: Boolean,
    onAddStops: () -> Unit,
    send: (Command) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val me = LocalCompany.current
    fun order(what: Int, stations: IntArray = IntArray(0)) = send(Command.TrainOrder(me, t.id, what, stations))
    Card(trainName(t), onClose, modifier) {
        val target = if (t.orders.isEmpty()) null else Order.station(t.orders[t.order % t.orders.size])
        val doing = when {
            t.wrecked -> stringResource(Res.string.wrecked)
            t.broken -> stringResource(Res.string.broken_down)
            t.needsWires -> stringResource(Res.string.needs_wires)
            t.inDepot && t.running -> stringResource(Res.string.waiting_to_leave)
            t.inDepot -> stringResource(Res.string.in_depot)
            !t.running -> stringResource(Res.string.stopped)
            t.servicing -> stringResource(Res.string.going_for_service, t.kmh)
            t.toDepot -> stringResource(Res.string.going_to_depot, t.kmh)
            t.waiting && target != null -> stringResource(Res.string.at_station, stationName(view, target))
            t.lost -> stringResource(Res.string.lost)
            target != null -> stringResource(Res.string.heading_for, stationName(view, target), t.kmh)
            else -> stringResource(Res.string.no_stops)
        }
        val lang = languageCode()
        val e = roster[t.engine]
        val w = roster[t.wagon]
        val mine = t.owner == me
        VehicleHeader(
            e, stringResource(Res.string.consist, e.name(lang), t.wagons, (if (t.wagons == 1) w.name(lang) else w.plural(lang)).lowercase()),
            doing, statusColour(t.wrecked, t.lost || t.broken || t.needsWires), if (mine) null else view.companies.getOrElse(t.owner) { "" },
        )
        VehicleStats(
            t.kmh, e.speed, t.age, t.old, t.reliability, t.profitThisYear, t.profitLastYear,
            (0 until Cargo.COUNT).filter { t.capacity[it] > 0 }.map { VehicleLoad(it, t.load[it], t.capacity[it]) },
            listOf(stringResource(Res.string.breakdowns) to t.breakdowns.toString()),
        )
        // Someone else's shows what it is but none of the controls, and a wreck is cleared away by itself.
        if (!mine || t.wrecked) return@Card
        Head(stringResource(Res.string.orders))
        val group = if (t.shared >= 0) view.trains.count { it.id != t.id && it.shared == t.shared } else 0
        // The last train left in a group shares with nobody, so it's shown as not sharing.
        val shared = group > 0
        if (shared) Text(pluralStringResource(Res.plurals.shared_with, group, group), color = LocalColors.current.textDim, style = KitType.Dim)
        OrdersList(view, t.id, t.orders, t.order, OrderFlags.TRAIN, Glyph.Station) { order(Command.TrainOrder.ORDERS, it) }
        // Sharing orders with another train: pick which from a list, or stop sharing.
        var sharing by remember(t.id) { mutableStateOf(false) }
        val others = view.trains.filter { it.id != t.id && it.owner == me }
        ButtonTiles(listOfNotNull(
            addStopsTile(addingStops, onAddStops),
            when {
                shared -> TileButton(Glyph.Layers, stringResource(Res.string.stop_sharing)) { order(Command.TrainOrder.UNSHARE) }
                others.isNotEmpty() -> TileButton(Glyph.Layers, stringResource(Res.string.share_orders), lit = sharing) { sharing = !sharing }
                else -> null
            },
        ))
        if (sharing && !shared) {
            val stations = remember(view.stations) { view.stations.associateBy { it.id } }
            val none = stringResource(Res.string.no_stops)
            for (o in others) key(o.id) {
                // Unnamed stations are rare, so their stand-in names are looked up one by one.
                val unnamed = o.orders.map { Order.station(it) }.filter { stations[it]?.name.isNullOrEmpty() }.distinct()
                    .associateWith { stringResource(Res.string.station_n, it + 1) }
                val line = remember(o.orders.contentHashCode(), stations, unnamed) {
                    if (o.orders.isEmpty()) none
                    else o.orders.joinToString("  ·  ") { Order.station(it).let { id -> unnamed[id] ?: stations[id]?.name ?: "" } }
                }
                PictureRow(
                    trainName(o), picture = { VehiclePicture(roster[o.engine], it) },
                    line = line,
                    onClick = {
                        order(Command.TrainOrder.SHARE, intArrayOf(o.id))
                        sharing = false
                    },
                )
            }
        }
        Box(Modifier.height(KitSpace.Tight))
        ButtonTiles(
            listOf(
                TileButton(if (t.running) Glyph.Pause else Glyph.Play, stringResource(if (t.running) Res.string.stop else Res.string.start), strong = !t.running) { order(Command.TrainOrder.START_STOP) },
            ) + if (!t.inDepot) listOf(
                TileButton(Glyph.Depot, stringResource(if (t.toDepot) Res.string.carry_on else Res.string.to_depot), lit = t.toDepot) { order(Command.TrainOrder.TO_DEPOT) },
                TileButton(Glyph.TurnLeft, stringResource(Res.string.turn_round)) { order(Command.TrainOrder.TURN) },
            ) else listOf(
                TileButton(Glyph.Renew, stringResource(Res.string.renew_for, money(t.renewCost))) { order(Command.TrainOrder.RENEW) },
                TileButton(Glyph.Minus, stringResource(Res.string.sell)) { order(Command.TrainOrder.SELL) },
            ),
        )
        SwitchRow(stringResource(Res.string.servicing), t.service) { order(Command.TrainOrder.SERVICE) }
    }
}

/** A depot: buying a train, its engine and wagons each picked from a list, and the trains in it. */
@Composable
fun DepotCard(view: WorldView, roster: Roster, depot: Int, year: Int, funds: Long, price: (Long) -> Long, send: (Command) -> Unit, onTrain: (Int) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val me = LocalCompany.current
    val c = LocalColors.current
    val lang = languageCode()
    val engines = roster.available(year, pulls = true)
    val wagons = roster.available(year, pulls = false)
    var engineId by remember { mutableIntStateOf(-1) }
    var wagonId by remember { mutableIntStateOf(-1) }
    var count by remember { mutableIntStateOf(3) }
    var cargo by remember { mutableIntStateOf(-1) }
    var showWagons by remember { mutableStateOf(false) }
    Card(stringResource(Res.string.depot), onClose, modifier, scrolls = false) {
        // What's picked, or the first on sale once the one picked goes off sale.
        val e = engines.firstOrNull { it.id == engineId } ?: engines.firstOrNull()
        val w = wagons.firstOrNull { it.id == wagonId } ?: wagons.firstOrNull()
        // An engine's price and running cost with its tender's, as it's bought.
        fun tender(t: Engine) = roster.getOrNull(t.tender)
        BuyPanel(
            top = {
                FillChips(listOf(false, true), showWagons, { stringResource(if (it) Res.string.wagons else Res.string.engines) }) { showWagons = it }
            },
            list = {
                when {
                    showWagons && w == null -> NoneOnSale(roster, VehicleKind.WAGON, year)
                    showWagons && w != null -> BuyList(wagons, w.id, cargo, price, { wagonId = it; cargo = -1 }) { cargo = it }
                    e == null -> NoneOnSale(roster, VehicleKind.ENGINE, year)
                    else -> BuyList(
                        engines, e.id, -1, price, { engineId = it },
                        costOf = { it.cost + (tender(it)?.cost ?: 0) }, runningOf = { it.running + (tender(it)?.running ?: 0) },
                    ) {}
                }
            },
            footer = {
                if (e != null) {
                    val n = if (w == null) 0 else count
                    val choices = w?.cargoes ?: IntArray(0)
                    val cost = price(e.cost + (tender(e)?.cost ?: 0) + (w?.cost ?: 0) * n)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (w == null || n == 0) e.name(lang) else stringResource(Res.string.consist_line, e.name(lang), n, if (n == 1) w.name(lang) else w.plural(lang)),
                            color = c.text, fontSize = 13.sp, maxLines = 2, modifier = Modifier.weight(1f),
                        )
                        if (w != null) {
                            SquareButton(Glyph.Minus, stringResource(Res.string.fewer_wagons), 32.dp, enabled = count > 0) { count-- }
                            Text(count.toString(), color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.width(24.dp))
                            SquareButton(Glyph.Plus, stringResource(Res.string.more_wagons), 32.dp, enabled = count < MAX_WAGONS) { count++ }
                        }
                    }
                    if (w == null) Text(stringResource(Res.string.no_wagons_on_sale), color = c.warn, fontSize = 13.sp)
                    // Electric engines need wires over the depot to get out.
                    val unwired = e.traction == Traction.ELECTRIC && view.depots.firstOrNull { it.id == depot }?.wired != true
                    if (unwired) Text(stringResource(Res.string.depot_needs_wires), color = c.warn, fontSize = 13.sp)
                    BuyButton(cost, funds, blocked = unwired || w == null) {
                        send(Command.BuyTrain(me, depot, e.id, w?.id ?: 0, n, if (cargo in choices) cargo else -1))
                    }
                }
            },
            inDepot = {
                for (t in view.trains.filter { it.depot == depot && it.inDepot }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(trainName(t), color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        ActionButton(stringResource(Res.string.orders)) { onTrain(t.id) }
                        ActionButton(stringResource(if (t.running) Res.string.stop else Res.string.start), strong = !t.running) { send(Command.TrainOrder(me, t.id, Command.TrainOrder.START_STOP)) }
                    }
                }
            },
        )
    }
}

/** A station: its platforms and the trains that call there. */
@Composable
fun StationCard(view: WorldView, s: StationView, onTrain: (Int) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val c = LocalColors.current
    Card(stationName(view, s.id), onClose, modifier) {
        Text(stringResource(Res.string.platform_tiles, s.platforms), color = c.textDim, fontSize = 13.sp)
        // Each cargo it deals in, with how much is waiting and its rating; passengers and mail always.
        val names = stringArrayResource(Res.array.cargoes)
        for (cg in 0 until Cargo.COUNT) {
            if (cg > Cargo.MAIL && s.served and (1 shl cg) == 0) continue
            Text(
                stringResource(Res.string.cargo_waiting, names.getOrElse(cg) { "" }, s.waiting[cg], s.rating[cg] * 100 / 255),
                color = c.text, fontSize = 13.sp,
            )
        }
        // Where it's going, the most first, and where it'll change on the way.
        if (s.going.isNotEmpty()) Text(stringResource(Res.string.going_to), color = c.textDim, fontSize = 12.sp)
        for (g in s.going.take(GOING_SHOWN)) {
            val dest = if (g.dest < 0) stringResource(Res.string.anywhere) else stationName(view, g.dest)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (g.via >= 0) stringResource(Res.string.via, dest, stationName(view, g.via)) else dest,
                    color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1,
                )
                Text(
                    (0 until Cargo.COUNT).filter { g.counts[it] > 0 }.joinToString(" · ") { "${g.counts[it]} ${names.getOrElse(it) { "" }.lowercase()}" },
                    color = c.textDim, fontSize = 13.sp,
                )
            }
        }
        val calling = view.trains.filter { tr -> tr.orders.any { Order.station(it) == s.id } }
        if (calling.isEmpty()) Text(stringResource(Res.string.no_trains_call), color = c.textDim, fontSize = 13.sp)
        for (t in calling) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(trainName(t), color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                ActionButton(stringResource(Res.string.show)) { onTrain(t.id) }
            }
        }
    }
}

/** How many destinations a station's card lists. */
private const val GOING_SHOWN = 6
