package com.rm.interchange.ui

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
import com.rm.interchange.sim.Engines
import com.rm.interchange.sim.MAX_WAGONS
import com.rm.interchange.sim.StationView
import com.rm.interchange.sim.TrainView
import com.rm.interchange.sim.WorldView

private fun stationName(view: WorldView, id: Int) = view.stations.firstOrNull { it.id == id }?.name ?: "?"

/** A train: what it's doing, where it calls, and buttons to run it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrainCard(
    view: WorldView,
    t: TrainView,
    addingStops: Boolean,
    onAddStops: () -> Unit,
    send: (Command) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val c = LocalColors.current
    Card(t.name, onClose, modifier) {
        val target = if (t.orders.isEmpty()) null else t.orders[t.order % t.orders.size]
        val doing = when {
            t.inDepot && t.running -> "Waiting to leave the depot"
            t.inDepot -> "In the depot"
            !t.running -> "Stopped"
            t.toDepot -> "Going to the depot, ${t.kmh} km/h"
            t.waiting && target != null -> "At ${stationName(view, target)}"
            t.lost -> "Can't find a way"
            target != null -> "To ${stationName(view, target)}, ${t.kmh} km/h"
            else -> "No stops"
        }
        val w = Engines.ALL[t.wagon]
        Text("${Engines.ALL[t.engine].name} and ${t.wagons} ${(if (t.wagons == 1) w.name else w.plural).lowercase()}", color = c.textDim, fontSize = 13.sp)
        Text(doing, color = if (t.lost) c.warn else c.text, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((i, s) in t.orders.withIndex()) {
                // Tapping a stop takes it off.
                Chip("${i + 1}. ${stationName(view, s)} ✕", i == t.order % t.orders.size) {
                    send(Command.TrainOrder(0, t.id, Command.TrainOrder.ORDERS, t.orders.filterIndexed { j, _ -> j != i }.toIntArray()))
                }
            }
            Chip(if (addingStops) "Done adding" else "Add stops", addingStops, onClick = onAddStops)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton(if (t.running) "Stop" else "Start", strong = !t.running) { send(Command.TrainOrder(0, t.id, Command.TrainOrder.START_STOP)) }
            if (!t.inDepot) {
                ActionButton(if (t.toDepot) "Carry on" else "To depot") { send(Command.TrainOrder(0, t.id, Command.TrainOrder.TO_DEPOT)) }
                ActionButton("Turn round") { send(Command.TrainOrder(0, t.id, Command.TrainOrder.TURN)) }
            } else {
                ActionButton("Sell") { send(Command.TrainOrder(0, t.id, Command.TrainOrder.SELL)) }
            }
        }
    }
}

/** A depot: buying a train, and the trains in it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DepotCard(view: WorldView, depot: Int, year: Int, money: Long, send: (Command) -> Unit, onTrain: (Int) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val c = LocalColors.current
    val engines = Engines.available(year, pulls = true)
    val wagons = Engines.available(year, pulls = false)
    var engine by remember { mutableIntStateOf(engines.firstOrNull()?.id ?: 0) }
    var wagon by remember { mutableIntStateOf(wagons.firstOrNull()?.id ?: 0) }
    var count by remember { mutableIntStateOf(3) }
    Card("Depot", onClose, modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (e in engines) Chip("${e.name}, ${e.speed} km/h", e.id == engine) { engine = e.id }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (w in wagons) Chip(w.name, w.id == wagon) { wagon = w.id }
        }
        val cost = Engines.ALL[engine].cost + Engines.ALL[wagon].cost * count
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SquareButton(Glyph.Lower, "Fewer wagons", 32.dp, enabled = count > 0) { count-- }
            Text("$count wagons", color = c.text, fontSize = 13.sp)
            SquareButton(Glyph.Raise, "More wagons", 32.dp, enabled = count < MAX_WAGONS) { count++ }
            ActionButton("Buy, ${moneyText(cost)}", enabled = money >= cost, strong = true) {
                send(Command.BuyTrain(0, depot, engine, wagon, count))
            }
        }
        val here = view.trains.filter { it.depot == depot && it.inDepot }
        for (t in here) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t.name, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                ActionButton("Orders") { onTrain(t.id) }
                ActionButton(if (t.running) "Stop" else "Start", strong = !t.running) { send(Command.TrainOrder(0, t.id, Command.TrainOrder.START_STOP)) }
            }
        }
    }
}

/** A station: its platforms and the trains that call there. */
@Composable
fun StationCard(view: WorldView, s: StationView, onTrain: (Int) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val c = LocalColors.current
    Card(s.name, onClose, modifier) {
        Text("${s.platforms} platform tile${if (s.platforms == 1) "" else "s"}", color = c.textDim, fontSize = 13.sp)
        val calling = view.trains.filter { s.id in it.orders }
        if (calling.isEmpty()) Text("No trains call here", color = c.textDim, fontSize = 13.sp)
        for (t in calling) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t.name, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                ActionButton("Show") { onTrain(t.id) }
            }
        }
    }
}
