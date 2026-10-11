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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.Cargo
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
    val c = LocalColors.current
    Card(trainName(t), onClose, modifier) {
        val target = if (t.orders.isEmpty()) null else t.orders[t.order % t.orders.size]
        val doing = when {
            t.inDepot && t.running -> stringResource(Res.string.waiting_to_leave)
            t.inDepot -> stringResource(Res.string.in_depot)
            !t.running -> stringResource(Res.string.stopped)
            t.toDepot -> stringResource(Res.string.going_to_depot, t.kmh)
            t.waiting && target != null -> stringResource(Res.string.at_station, stationName(view, target))
            t.lost -> stringResource(Res.string.lost)
            target != null -> stringResource(Res.string.heading_for, stationName(view, target), t.kmh)
            else -> stringResource(Res.string.no_stops)
        }
        val lang = languageCode()
        val w = roster[t.wagon]
        Text(stringResource(Res.string.consist, roster[t.engine].name(lang), t.wagons, (if (t.wagons == 1) w.name(lang) else w.plural(lang)).lowercase()), color = c.textDim, fontSize = 13.sp)
        Text(doing, color = if (t.lost) c.warn else c.text, fontSize = 13.sp)
        val loads = buildList {
            if (t.capacity[Cargo.PASSENGERS] > 0) add(stringResource(Res.string.passengers_load, t.load[Cargo.PASSENGERS], t.capacity[Cargo.PASSENGERS]))
            if (t.capacity[Cargo.MAIL] > 0) add(stringResource(Res.string.mail_load, t.load[Cargo.MAIL], t.capacity[Cargo.MAIL]))
        }
        if (loads.isNotEmpty()) Text(loads.joinToString(" · "), color = c.text, fontSize = 13.sp)
        Text(
            stringResource(Res.string.profit_line, money(t.profitThisYear), money(t.profitLastYear)),
            color = if (t.profitThisYear < 0) c.bad else c.textDim, fontSize = 13.sp,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((i, s) in t.orders.withIndex()) {
                // Tapping a stop takes it off.
                Chip("${i + 1}. ${stationName(view, s)} ✕", i == t.order % t.orders.size) {
                    send(Command.TrainOrder(0, t.id, Command.TrainOrder.ORDERS, t.orders.filterIndexed { j, _ -> j != i }.toIntArray()))
                }
            }
            Chip(stringResource(if (addingStops) Res.string.done_adding else Res.string.add_stops), addingStops, onClick = onAddStops)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(stringResource(if (t.running) Res.string.stop else Res.string.start), strong = !t.running) { send(Command.TrainOrder(0, t.id, Command.TrainOrder.START_STOP)) }
            if (!t.inDepot) {
                ActionButton(stringResource(if (t.toDepot) Res.string.carry_on else Res.string.to_depot)) { send(Command.TrainOrder(0, t.id, Command.TrainOrder.TO_DEPOT)) }
                ActionButton(stringResource(Res.string.turn_round)) { send(Command.TrainOrder(0, t.id, Command.TrainOrder.TURN)) }
            } else {
                ActionButton(stringResource(Res.string.sell)) { send(Command.TrainOrder(0, t.id, Command.TrainOrder.SELL)) }
            }
        }
    }
}

/** A depot: buying a train, and the trains in it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DepotCard(view: WorldView, roster: Roster, depot: Int, year: Int, funds: Long, send: (Command) -> Unit, onTrain: (Int) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val c = LocalColors.current
    val lang = languageCode()
    val engines = roster.available(year, pulls = true)
    val wagons = roster.available(year, pulls = false)
    var engine by remember { mutableIntStateOf(engines.firstOrNull()?.id ?: 0) }
    var wagon by remember { mutableIntStateOf(wagons.firstOrNull()?.id ?: 0) }
    var count by remember { mutableIntStateOf(3) }
    Card(stringResource(Res.string.depot), onClose, modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (e in engines) Chip(stringResource(Res.string.engine_speed, e.name(lang), e.type(lang), e.speed), e.id == engine) { engine = e.id }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (w in wagons) Chip(w.name(lang), w.id == wagon) { wagon = w.id }
        }
        val e = roster[engine]
        val cost = e.cost + (roster.getOrNull(e.tender)?.cost ?: 0) + roster[wagon].cost * count
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SquareButton(Glyph.Lower, stringResource(Res.string.fewer_wagons), 32.dp, enabled = count > 0) { count-- }
            Text(stringResource(Res.string.wagons_count, count), color = c.text, fontSize = 13.sp)
            SquareButton(Glyph.Raise, stringResource(Res.string.more_wagons), 32.dp, enabled = count < MAX_WAGONS) { count++ }
            ActionButton(stringResource(Res.string.buy_for, money(cost)), enabled = funds >= cost, strong = true) {
                send(Command.BuyTrain(0, depot, engine, wagon, count))
            }
        }
        val here = view.trains.filter { it.depot == depot && it.inDepot }
        for (t in here) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(trainName(t), color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                ActionButton(stringResource(Res.string.orders)) { onTrain(t.id) }
                ActionButton(stringResource(if (t.running) Res.string.stop else Res.string.start), strong = !t.running) { send(Command.TrainOrder(0, t.id, Command.TrainOrder.START_STOP)) }
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
        Text(stringResource(Res.string.passengers_waiting, s.waiting[Cargo.PASSENGERS]), color = c.text, fontSize = 13.sp)
        Text(stringResource(Res.string.mail_waiting, s.waiting[Cargo.MAIL]), color = c.text, fontSize = 13.sp)
        val calling = view.trains.filter { s.id in it.orders }
        if (calling.isEmpty()) Text(stringResource(Res.string.no_trains_call), color = c.textDim, fontSize = 13.sp)
        for (t in calling) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(trainName(t), color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                ActionButton(stringResource(Res.string.show)) { onTrain(t.id) }
            }
        }
    }
}
