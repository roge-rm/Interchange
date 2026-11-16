package com.rm.interchange.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rm.interchange.res.*
import com.rm.interchange.server.GameThread
import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.Order
import com.rm.interchange.sim.ServiceView
import com.rm.interchange.sim.WorldView
import com.rm.interchange.sim.capGroupName
import com.rm.interchange.sim.cleanGroupName
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Opens the service window for a service's id. Cards use it from their service tile. */
val LocalOpenService = staticCompositionLocalOf<(Int) -> Unit> { {} }

private const val LATE_WARN = Calendar.TICKS_PER_DAY
private const val LATE_BAD = 3 * Calendar.TICKS_PER_DAY
private const val MOST_TENTHS = 3000

/** [ticks] in tenths of a day, rounded to the nearest, halves away from 0 either side of it. */
private fun ticksToTenths(ticks: Int): Int =
    if (ticks < 0) -ticksToTenths(-ticks) else (ticks * 10 + Calendar.TICKS_PER_DAY / 2) / Calendar.TICKS_PER_DAY

/** The ticks of [tenths] of a day, rounded up as autofill does. */
private fun tenthsToTicks(tenths: Int) = (tenths * Calendar.TICKS_PER_DAY + 9) / 10

/** A service's name, or its first and last stop if it hasn't one. */
@Composable
fun serviceTitle(view: WorldView, s: ServiceView): String {
    if (s.name.isNotEmpty()) return s.name
    val stops = s.orders.map { Order.station(it) }.filter { it >= 0 }
    return when {
        stops.isEmpty() -> stringResource(Res.string.service)
        stops.size == 1 || stops.first() == stops.last() -> stationName(view, stops.first())
        else -> stringResource(Res.string.link_title, stationName(view, stops.first()), stationName(view, stops.last()))
    }
}

/** "On time", "1.2 days late" or "0.5 days early". */
@Composable
fun lateText(late: Int): String {
    if (ticksToTenths(late) == 0) return stringResource(Res.string.on_time)
    return stringResource(if (late > 0) Res.string.days_late else Res.string.days_early, daysText(kotlin.math.abs(late)))
}

/** Amber over a day late, red over three. */
fun lateColour(late: Int, c: Colors): Color = when {
    late > LATE_BAD -> c.bad
    late > LATE_WARN -> c.warn
    else -> c.textDim
}

/** The tile on a vehicle's shared-orders controls: opens its service, or makes it a service of one. */
@Composable
fun serviceTile(kind: Int, id: Int, serviceId: Int, send: (Command) -> Unit): TileButton {
    val me = LocalCompany.current
    val open = LocalOpenService.current
    return if (serviceId >= 0) TileButton(Glyph.Calendar, stringResource(Res.string.service)) { open(serviceId) }
    else TileButton(Glyph.Calendar, stringResource(Res.string.start_service)) { send(Command.ServiceOrder(me, kind, id, Command.ServiceOrder.START)) }
}

/** On a card: the service's name and how late this vehicle is, when the service has a timetable. */
@Composable
fun ServiceLine(view: WorldView, serviceId: Int, late: Int) {
    if (serviceId < 0) return
    val s = view.services.firstOrNull { it.id == serviceId }?.takeIf { it.timetabled } ?: return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(serviceTitle(view, s), color = LocalColors.current.textDim, style = KitType.Dim, maxLines = 1, modifier = Modifier.weight(1f))
        Text(lateText(late), color = lateColour(late, LocalColors.current), style = KitType.Dim, maxLines = 1)
    }
}

/** Every service the company has. A tap opens it with [onOpen] (the service's id). */
@Composable
fun ServicesWindow(runner: GameThread, onOpen: (Int) -> Unit, onClose: () -> Unit) {
    val view by produceState(runner.view, runner) {
        while (true) androidx.compose.runtime.withFrameNanos { value = runner.view }
    }
    val v = view ?: return
    val c = LocalColors.current
    Window(stringResource(Res.string.services), onClose, Glyph.Calendar) {
        if (v.services.isEmpty()) Text(stringResource(Res.string.no_services), color = c.textDim, style = KitType.Body)
        for (s in v.services) key(s.id) {
            val worst = s.late.maxOrNull() ?: 0
            ListRow(
                when (s.kind) {
                    ShowVehicle.TRAIN -> Glyph.Train
                    ShowVehicle.ROAD -> Glyph.Bus
                    ShowVehicle.SHIP -> Glyph.Ship
                    else -> Glyph.Air
                },
                serviceTitle(v, s),
                pluralStringResource(Res.plurals.vehicles_count, s.vehicleIds.size, s.vehicleIds.size),
                { onOpen(s.id) },
            ) {
                if (s.timetabled) Text(lateText(worst), color = lateColour(worst, c), style = KitType.ListLine, maxLines = 1)
            }
        }
    }
}

/**
 * One service: its name, the times of each order planned and measured, autofill, clear and the timetable switch,
 * and its vehicles with how late each is. A vehicle tapped opens with [onVehicle].
 */
@Composable
fun ServiceWindow(runner: GameThread, id: Int, onVehicle: (ShowVehicle) -> Unit, onClose: () -> Unit) {
    val view by produceState(runner.view, runner) {
        while (true) androidx.compose.runtime.withFrameNanos { value = runner.view }
    }
    val v = view ?: return
    val s = v.services.firstOrNull { it.id == id }
    // A sold last vehicle takes the service with it.
    LaunchedEffect(s == null) { if (s == null) onClose() }
    if (s == null) return
    val me = LocalCompany.current
    val c = LocalColors.current
    fun send(what: Int, values: IntArray = IntArray(0), name: String = "") {
        val first = s.vehicleIds.firstOrNull() ?: return
        runner.request(Command.ServiceOrder(me, s.kind, first, what, values, name))
    }
    Window(serviceTitle(v, s), onClose, Glyph.Calendar) {
        var text by remember(id) { mutableStateOf(s.name) }
        // Renamed by someone else: the field follows, unless it's being typed in.
        var seen by remember(id) { mutableStateOf(s.name) }
        LaunchedEffect(s.name) {
            if (text == seen) text = s.name
            seen = s.name
        }
        val clean = cleanGroupName(text)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Field(text, stringResource(Res.string.service_name), { text = capGroupName(it) }) }
            ActionButton(stringResource(Res.string.rename), enabled = clean != s.name, strong = true) { send(Command.ServiceOrder.NAME, name = clean) }
        }
        Head(stringResource(Res.string.orders), Glyph.Station)
        var open by remember(id) { mutableIntStateOf(-1) }
        val depotWord = stringResource(Res.string.depot)
        // Conditional orders take no time, so they have no row.
        for (i in s.orders.indices) if (!Order.isCond(s.orders[i])) key(i) {
            val o = s.orders[i]
            val title = if (Order.isDepot(o)) depotWord else stationName(v, Order.station(o))
            PictureRow(
                title, icon = { Badge(if (Order.isDepot(o)) Glyph.Depot else Glyph.Station, size = 28.dp) },
                onClick = { open = if (open == i) -1 else i },
                below = {
                    Column(verticalArrangement = Arrangement.spacedBy(KitSpace.Gap)) {
                        TimeRow(Res.string.service_travel, s.planTravel[i], s.measuredTravel[i], open == i) { send(Command.ServiceOrder.PLAN, intArrayOf(i, it, -1)) }
                        // A depot, via order or waypoint has no wait to plan.
                        val stands = !Order.isDepot(o) && !Order.has(o, Order.VIA) && v.stations.firstOrNull { it.id == Order.station(o) }?.waypoint != true
                        if (stands) TimeRow(Res.string.service_wait, s.planWait[i], s.measuredWait[i], open == i) { send(Command.ServiceOrder.PLAN, intArrayOf(i, -1, it)) }
                    }
                },
            )
        }
        ButtonTiles(listOf(
            TileButton(Glyph.Down, stringResource(Res.string.autofill)) { send(Command.ServiceOrder.AUTOFILL) },
            TileButton(Glyph.Clear, stringResource(Res.string.clear_times)) { send(Command.ServiceOrder.CLEAR) },
        ))
        SwitchRow(stringResource(Res.string.timetable), s.timetabled) { send(Command.ServiceOrder.TIMETABLE, intArrayOf(if (it) 1 else 0)) }
        Head(stringResource(Res.string.vehicles), Glyph.Train)
        for (k in s.vehicleIds.indices) key(k) {
            val vid = s.vehicleIds[k]
            val late = s.late[k]
            val timed = s.timetabled
            // Name, glyph, and where it is, by kind.
            val row: Triple<String, Glyph, ShowVehicle>? = when (s.kind) {
                ShowVehicle.TRAIN -> v.trains.firstOrNull { it.id == vid }?.let { Triple(trainName(it), Glyph.Train, ShowVehicle(s.kind, vid, it.x, it.y)) }
                ShowVehicle.ROAD -> v.roadVehicles.firstOrNull { it.id == vid }?.let { Triple(roadVehicleName(it), Glyph.Bus, ShowVehicle(s.kind, vid, it.x, it.y)) }
                ShowVehicle.SHIP -> v.ships.firstOrNull { it.id == vid }?.let { Triple(shipName(it), Glyph.Ship, ShowVehicle(s.kind, vid, it.x, it.y)) }
                else -> v.aircraft.firstOrNull { it.id == vid }?.let { Triple(aircraftName(it), Glyph.Air, ShowVehicle(s.kind, vid, it.x, it.y)) }
            }
            if (row != null) ListRow(row.second, row.first, if (timed) lateText(late) else null, { onVehicle(row.third) }, lineColour = if (timed) lateColour(late, c) else null)
        }
    }
}

/** A time under an order: [plan] and, in grey, what was [measured], with steppers of a tenth of a day when [editing]. [set] gets the new plan in ticks. */
@Composable
private fun TimeRow(label: org.jetbrains.compose.resources.StringResource, plan: Int, measured: Int, editing: Boolean, set: (Int) -> Unit) {
    val c = LocalColors.current
    val tenths = ticksToTenths(plan)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitSpace.Gap)) {
        Text(stringResource(label), color = c.textDim, style = KitType.Dim, maxLines = 1, modifier = Modifier.weight(1f))
        if (editing) SquareButton(Glyph.Minus, stringResource(Res.string.gap_less), 32.dp, enabled = plan > 0) { set(tenthsToTicks((tenths - 1).coerceAtLeast(0))) }
        Text(if (plan > 0) daysText(plan) else "-", color = c.text, style = KitType.Value, maxLines = 1, textAlign = TextAlign.End)
        if (editing) SquareButton(Glyph.Plus, stringResource(Res.string.gap_more), 32.dp, enabled = tenths < MOST_TENTHS) { set(tenthsToTicks(tenths + 1)) }
        Text(if (measured >= 0) daysText(measured) else "-", color = c.textDim, style = KitType.Dim, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}
