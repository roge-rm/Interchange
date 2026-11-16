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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rm.interchange.res.*
import com.rm.interchange.server.GameThread
import com.rm.interchange.sim.Books
import com.rm.interchange.sim.Order
import com.rm.interchange.sim.World
import com.rm.interchange.sim.WorldView
import com.rm.interchange.sim.roadEdges
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** The most tiles looked at when seeing whether two stations are joined. */
private const val JOIN_SEARCH = 30_000

/** Whether track or road leads between two stations of [me], by tiles with track or road touching. Run on the game thread. */
internal fun stationsJoined(w: World, me: Int): Boolean {
    val mine = w.stations.filterNotNull().filter { it.owner == me && !it.waypoint }
    if (mine.size < 2) return false
    val at = HashMap<Int, Int>()
    for (s in mine) for (t in s.tiles + s.stops) at[t] = s.id
    for (first in mine.take(4)) {
        val seen = HashSet<Int>()
        val queue = ArrayDeque<Int>()
        for (t in first.tiles + first.stops) if (seen.add(t)) queue += t
        while (queue.isNotEmpty() && seen.size < JOIN_SEARCH) {
            val t = queue.removeFirst()
            val other = at[t]
            if (other != null && other != first.id) return true
            val x = t % w.width
            val y = t / w.width
            for (e in 0 until 4) {
                val nx = x + com.rm.interchange.sim.Track.dx(e)
                val ny = y + com.rm.interchange.sim.Track.dy(e)
                if (!w.inside(nx, ny)) continue
                val n = w.index(nx, ny)
                if (n in seen) continue
                if (w.track[n].toInt() != 0 || w.roadEdges(n) != 0 || w.stationTiles.containsKey(n) || w.roadStops.containsKey(n)) {
                    seen += n
                    queue += n
                }
            }
        }
    }
    return false
}

/** The order lists of every vehicle of [me] in [v]. */
private fun orderLists(v: WorldView, me: Int): List<IntArray> =
    v.trains.filter { it.owner == me }.map { it.orders } + v.roadVehicles.filter { it.owner == me }.map { it.orders } +
        v.ships.filter { it.owner == me }.map { it.orders } + v.aircraft.filter { it.owner == me }.map { it.orders }

/**
 * A small panel of what a first route takes, each step ticked once it's been done. [folded] starts it as one line.
 * It goes when everything's done or [onHide] is pressed, and both turn it off for later games.
 */
@Composable
fun FirstRouteChecklist(runner: GameThread, view: WorldView?, me: Int, folded: Boolean, onHide: () -> Unit, modifier: Modifier) {
    val c = LocalColors.current
    var open by remember(runner) { mutableStateOf(!folded) }
    // Each step stays ticked once seen, so selling the vehicle later doesn't undo it.
    var stations by remember(runner) { mutableStateOf(false) }
    var joined by remember(runner) { mutableStateOf(false) }
    var vehicle by remember(runner) { mutableStateOf(false) }
    var orders by remember(runner) { mutableStateOf(false) }
    var delivery by remember(runner) { mutableStateOf(false) }
    if (view != null) {
        val mine = view.stations.filter { it.owner == me && !it.waypoint }.map { it.id }.toSet()
        if (mine.size >= 2) stations = true
        val lists = orderLists(view, me)
        if (lists.isNotEmpty()) vehicle = true
        val both = lists.any { o -> o.filter { !Order.isDepot(it) }.map { Order.station(it) }.filter { it in mine }.toSet().size >= 2 }
        if (both) orders = true
        // A ship or aircraft needs no track, so one running between two stations joins them.
        if (both && (view.ships.any { it.owner == me } || view.aircraft.any { it.owner == me })) joined = true
        view.company?.let { co -> if (co.thisYear[Books.INCOME] > 0 || co.lastYear[Books.INCOME] > 0) delivery = true }
    }
    // Looks at the map every few seconds until it finds the stations joined.
    LaunchedEffect(runner, stations, joined) {
        if (!stations || joined) return@LaunchedEffect
        while (true) {
            if (mapRead(runner) { stationsJoined(it, me) }) { joined = true; return@LaunchedEffect }
            delay(2000)
        }
    }
    val steps = listOf(
        stations to Res.string.first_route_stations, joined to Res.string.first_route_join, vehicle to Res.string.first_route_vehicle,
        orders to Res.string.first_route_orders, delivery to Res.string.first_route_delivery,
    )
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
