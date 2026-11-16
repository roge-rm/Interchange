package com.rm.interchange.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rm.interchange.res.*
import com.rm.interchange.server.GameThread
import com.rm.interchange.sim.Order
import com.rm.interchange.sim.World
import com.rm.interchange.sim.WorldView
import com.rm.interchange.sim.stationsJoined
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** The game and player the checklist is for: its seed, start year and size, and the company. */
internal fun checklistKey(w: World, me: Int) = "${w.settings.seed}/${w.startYear}/${w.width}x${w.height}/$me"

/** Whether [me] has nothing on the map yet: no stations or vehicles. Run on the game thread. */
internal fun startingOut(w: World, me: Int) =
    w.stations.none { it?.owner == me } && w.trains.none { it?.owner == me } && w.roadVehicles.none { it?.owner == me } &&
        w.ships.none { it?.owner == me } && w.aircraft.none { it?.owner == me }

/** The order lists of every vehicle of [me] in [v]. */
private fun orderLists(v: WorldView, me: Int): List<IntArray> =
    v.trains.filter { it.owner == me }.map { it.orders } + v.roadVehicles.filter { it.owner == me }.map { it.orders } +
        v.ships.filter { it.owner == me }.map { it.orders } + v.aircraft.filter { it.owner == me }.map { it.orders }

/**
 * A small panel of what a first route takes, each step ticked once it's been done. [folded] starts it as one line.
 * It goes when everything's done or [onHide] is pressed, and both turn it off for later games. [saved] are the steps
 * ticked so far in this game, and [onTicks] keeps them when more are.
 */
@Composable
fun FirstRouteChecklist(runner: GameThread, view: WorldView?, me: Int, folded: Boolean, saved: Int, onTicks: (Int) -> Unit, onHide: () -> Unit, modifier: Modifier) {
    val c = LocalColors.current
    var open by remember(runner) { mutableStateOf(!folded) }
    // Each step stays ticked once seen, so selling the vehicle later doesn't undo it. A bit each, kept with the game.
    var ticks by remember(runner, me) { mutableIntStateOf(saved) }
    var seen = 0
    if (view != null) {
        val mine = view.stations.filter { it.owner == me && !it.waypoint }.map { it.id }.toSet()
        fun both(o: IntArray) = o.filter { !Order.isDepot(it) }.map { Order.station(it) }.filter { it in mine }.toSet().size >= 2
        if (mine.size >= 2) seen = seen or STATIONS
        val lists = orderLists(view, me)
        if (lists.isNotEmpty()) seen = seen or VEHICLE
        if (lists.any(::both)) seen = seen or ORDERS
        // A ship or aircraft needs no track, so one with orders to two stations joins them.
        if (view.ships.any { it.owner == me && both(it.orders) } || view.aircraft.any { it.owner == me && both(it.orders) }) seen = seen or JOINED
        // Only cargo delivered makes a payment; track fees from sharing don't.
        if (view.payments.isNotEmpty()) seen = seen or DELIVERY
    }
    val now = ticks or seen
    LaunchedEffect(now) {
        if (now != ticks) {
            ticks = now
            onTicks(now)
        }
    }
    // Looks at the map every few seconds until it finds two stations joined, searching again only once track,
    // stations or roads have changed.
    val stations = now and STATIONS != 0
    val joined = now and JOINED != 0
    LaunchedEffect(runner, me, stations, joined) {
        if (!stations || joined) return@LaunchedEffect
        var last = -1L
        while (true) {
            val (version, found) = mapRead(runner) { w -> w.layoutVersion to (w.layoutVersion != last && w.stationsJoined(me)) }
            last = version
            if (found) { ticks = ticks or JOINED; onTicks(ticks); return@LaunchedEffect }
            delay(2000)
        }
    }
    val steps = listOf(
        STATIONS to Res.string.first_route_stations, JOINED to Res.string.first_route_join, VEHICLE to Res.string.first_route_vehicle,
        ORDERS to Res.string.first_route_orders, DELIVERY to Res.string.first_route_delivery,
    ).map { (bit, text) -> (now and bit != 0) to text }
    val done = steps.count { it.first }
    LaunchedEffect(done) {
        if (done == steps.size) {
            delay(4000)
            onHide()
        }
    }
    ChromeBox(modifier.widthIn(max = 220.dp)) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (open) stringResource(Res.string.first_route) else stringResource(Res.string.first_route_progress, done, steps.size),
                    color = c.text, style = KitType.RowTitle, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                SquareButton(if (open) Glyph.Up else Glyph.Down, stringResource(if (open) Res.string.first_route_fold else Res.string.first_route_unfold), 28.dp) { open = !open }
                SquareButton(Glyph.Remove, stringResource(Res.string.first_route_hide), 28.dp, onClick = onHide)
            }
            if (open) for ((ok, text) in steps) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GlyphIcon(Glyph.Tick, if (ok) c.accent else c.textDim.copy(alpha = 0.3f), Modifier.size(16.dp))
                    Text(stringResource(text), color = if (ok) c.textDim else c.text, style = KitType.ListLine, maxLines = 2)
                }
            }
        }
    }
}

private const val STATIONS = 1
private const val JOINED = 2
private const val VEHICLE = 4
private const val ORDERS = 8
private const val DELIVERY = 16
