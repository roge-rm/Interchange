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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.interchange.sim.Command
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

/** A train: what it's doing, where it calls, and buttons to run it. */
@OptIn(ExperimentalLayoutApi::class)
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
    val c = LocalColors.current
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
        val w = roster[t.wagon]
        Text(stringResource(Res.string.consist, roster[t.engine].name(lang), t.wagons, (if (t.wagons == 1) w.name(lang) else w.plural(lang)).lowercase()), color = c.textDim, fontSize = 13.sp)
        Text(doing, color = if (t.wrecked) c.bad else if (t.lost || t.broken || t.needsWires) c.warn else c.text, fontSize = 13.sp)
        Text(
            stringResource(Res.string.age_line, t.age, t.reliability, t.breakdowns),
            color = if (t.old) c.warn else c.textDim, fontSize = 13.sp,
        )
        val names = stringArrayResource(Res.array.cargoes)
        val loads = (0 until Cargo.COUNT).filter { t.capacity[it] > 0 }.map {
            stringResource(Res.string.cargo_load, names.getOrElse(it) { "" }, t.load[it], t.capacity[it])
        }
        if (loads.isNotEmpty()) Text(loads.joinToString(" · "), color = c.text, fontSize = 13.sp)
        Text(
            stringResource(Res.string.profit_line, money(t.profitThisYear), money(t.profitLastYear)),
            color = if (t.profitThisYear < 0) c.bad else c.textDim, fontSize = 13.sp,
        )
        // Someone else's: what it is, but none of the controls.
        if (t.owner != me) {
            Text(stringResource(Res.string.owned_by, view.companies.getOrElse(t.owner) { "" }), color = c.textDim, fontSize = 13.sp)
            return@Card
        }
        // A wreck can't be given orders or sold; it's cleared away by itself.
        if (t.wrecked) return@Card
        // The stops, the one it's heading for lit; a tap opens what to do there.
        var open by remember(t.id) { mutableIntStateOf(-1) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((i, o) in t.orders.withIndex()) {
                val marks = buildString {
                    if (Order.has(o, Order.FULL_LOAD)) append(" ⬤")
                    if (Order.has(o, Order.UNLOAD) || Order.has(o, Order.TRANSFER)) append(" ↓")
                    if (Order.has(o, Order.NO_LOAD)) append(" ⊘")
                    if (Order.waitDays(o) > 0) append(" ${Order.waitDays(o)}d")
                    if (Order.has(o, Order.VIA)) append(" →")
                    if (Order.has(o, Order.SKIP_IF_NOTHING)) append(" ?")
                }
                Chip("${i + 1}. ${stationName(view, Order.station(o))}$marks", i == t.order % t.orders.size || i == open) { open = if (open == i) -1 else i }
            }
            Chip(stringResource(if (addingStops) Res.string.done_adding else Res.string.add_stops), addingStops, onClick = onAddStops)
        }
        t.orders.getOrNull(open)?.let { o ->
            fun change(new: Int) = send(Command.TrainOrder(me, t.id, Command.TrainOrder.ORDERS, t.orders.copyOf().also { it[open] = new }))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((flag, label) in listOf(
                    Order.FULL_LOAD to Res.string.full_load, Order.UNLOAD to Res.string.unload_all, Order.TRANSFER to Res.string.transfer,
                    Order.NO_LOAD to Res.string.no_loading, Order.VIA to Res.string.go_via, Order.SKIP_IF_NOTHING to Res.string.skip_if_nothing,
                )) {
                    Chip(stringResource(label), Order.has(o, flag)) { change(Order.toggle(o, flag)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SquareButton(Glyph.Lower, stringResource(Res.string.wait_less), 32.dp, enabled = Order.waitDays(o) > 0) { change(Order.withWait(o, Order.waitDays(o) - 1)) }
                Text(stringResource(Res.string.wait_days, Order.waitDays(o)), color = c.text, fontSize = 13.sp)
                SquareButton(Glyph.Raise, stringResource(Res.string.wait_more), 32.dp, enabled = Order.waitDays(o) < Order.MAX_WAIT_DAYS) { change(Order.withWait(o, Order.waitDays(o) + 1)) }
                Box(Modifier.weight(1f))
                ActionButton(stringResource(Res.string.remove_stop)) {
                    send(Command.TrainOrder(me, t.id, Command.TrainOrder.ORDERS, t.orders.filterIndexed { j, _ -> j != open }.toIntArray()))
                    open = -1
                }
            }
        }
        // Sharing orders with another train: pick which, or stop sharing.
        var sharing by remember(t.id) { mutableStateOf(false) }
        val others = view.trains.filter { it.id != t.id && it.owner == me }
        if (t.shared >= 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.orders_shared), color = c.textDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                ActionButton(stringResource(Res.string.stop_sharing)) { send(Command.TrainOrder(me, t.id, Command.TrainOrder.UNSHARE)) }
            }
        } else if (others.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(stringResource(Res.string.share_orders), sharing) { sharing = !sharing }
                if (sharing) for (o in others) Chip(trainName(o), false) {
                    send(Command.TrainOrder(me, t.id, Command.TrainOrder.SHARE, intArrayOf(o.id)))
                    sharing = false
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(stringResource(if (t.running) Res.string.stop else Res.string.start), strong = !t.running) { send(Command.TrainOrder(me, t.id, Command.TrainOrder.START_STOP)) }
            if (!t.inDepot) {
                ActionButton(stringResource(if (t.toDepot) Res.string.carry_on else Res.string.to_depot)) { send(Command.TrainOrder(me, t.id, Command.TrainOrder.TO_DEPOT)) }
                ActionButton(stringResource(Res.string.turn_round)) { send(Command.TrainOrder(me, t.id, Command.TrainOrder.TURN)) }
            } else {
                ActionButton(stringResource(Res.string.renew_for, money(t.renewCost))) { send(Command.TrainOrder(me, t.id, Command.TrainOrder.RENEW)) }
                ActionButton(stringResource(Res.string.sell)) { send(Command.TrainOrder(me, t.id, Command.TrainOrder.SELL)) }
            }
            Chip(stringResource(Res.string.servicing), t.service) { send(Command.TrainOrder(me, t.id, Command.TrainOrder.SERVICE)) }
        }
    }
}

/** A depot: buying a train, and the trains in it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DepotCard(view: WorldView, roster: Roster, depot: Int, year: Int, funds: Long, price: (Long) -> Long, send: (Command) -> Unit, onTrain: (Int) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val me = LocalCompany.current
    val c = LocalColors.current
    val lang = languageCode()
    val engines = roster.available(year, pulls = true)
    val wagons = roster.available(year, pulls = false)
    var engine by remember { mutableIntStateOf(engines.firstOrNull()?.id ?: 0) }
    var wagon by remember { mutableIntStateOf(wagons.firstOrNull()?.id ?: 0) }
    var count by remember { mutableIntStateOf(3) }
    var cargo by remember { mutableIntStateOf(-1) }
    val names = stringArrayResource(Res.array.cargoes)
    Card(stringResource(Res.string.depot), onClose, modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (e in engines) Chip(stringResource(Res.string.engine_speed, e.name(lang), e.type(lang), e.speed), e.id == engine) { engine = e.id }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (w in wagons) Chip(w.name(lang), w.id == wagon) { wagon = w.id; cargo = -1 }
        }
        // What the wagons are to carry, where they can carry more than one thing.
        val choices = roster.getOrNull(wagon)?.cargoes ?: IntArray(0)
        if (choices.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (cg in choices) Chip(names.getOrElse(cg) { "" }, cg == (if (cargo in choices) cargo else choices[0])) { cargo = cg }
            }
        }
        val e = roster[engine]
        val cost = price(e.cost + (roster.getOrNull(e.tender)?.cost ?: 0) + roster[wagon].cost * count)
        // Electric engines need wires over the depot to get out.
        val unwired = e.traction == Traction.ELECTRIC && view.depots.firstOrNull { it.id == depot }?.wired != true
        if (unwired) Text(stringResource(Res.string.depot_needs_wires), color = c.warn, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SquareButton(Glyph.Lower, stringResource(Res.string.fewer_wagons), 32.dp, enabled = count > 0) { count-- }
            Text(stringResource(Res.string.wagons_count, count), color = c.text, fontSize = 13.sp)
            SquareButton(Glyph.Raise, stringResource(Res.string.more_wagons), 32.dp, enabled = count < MAX_WAGONS) { count++ }
            ActionButton(stringResource(Res.string.buy_for, money(cost)), enabled = funds >= cost && !unwired, strong = true) {
                send(Command.BuyTrain(me, depot, engine, wagon, count, if (cargo in choices) cargo else -1))
            }
        }
        val here = view.trains.filter { it.depot == depot && it.inDepot }
        for (t in here) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(trainName(t), color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                ActionButton(stringResource(Res.string.orders)) { onTrain(t.id) }
                ActionButton(stringResource(if (t.running) Res.string.stop else Res.string.start), strong = !t.running) { send(Command.TrainOrder(me, t.id, Command.TrainOrder.START_STOP)) }
            }
        }
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
