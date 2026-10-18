package com.rm.interchange.ui

import com.rm.interchange.res.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rm.interchange.sim.Engine
import com.rm.interchange.sim.Order
import com.rm.interchange.sim.WorldView

/*
 * The pieces every vehicle's card is made of, so trains, road vehicles, ships and aircraft look alike: the picture
 * and what it's doing, its numbers under Heads, its orders as a numbered list and its buttons.
 */

/** The top of a vehicle's card: [e]'s picture, its [model] and [status] in [statusColour], and [owner] if it's someone else's. */
@Composable
fun VehicleHeader(e: Engine, model: String, status: String, statusColour: Color, owner: String? = null) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitSpace.Inline)) {
        PictureBox { VehiclePicture(e, Modifier.fillMaxSize()) }
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(KitSpace.Tight)) {
            Text(model, color = c.text, style = KitType.RowTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(status, color = statusColour, style = KitType.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (owner != null) Text(stringResource(Res.string.owned_by, owner), color = c.textDim, style = KitType.Dim, maxLines = 1)
        }
    }
}

/** What the vehicle can carry, as a cargo with how much it has on board. */
class VehicleLoad(val cargo: Int, val load: Int, val capacity: Int)

/**
 * A vehicle's numbers: under Running its speed [kmh] and [top] speed, its [age] (lit when [old]) and [reliability] in
 * percent, then [extra] facts; under Profit this year's and last year's; and under Load each of [loads].
 */
@Composable
fun VehicleStats(
    kmh: Int, top: Int, age: Int, old: Boolean, reliability: Int, profitThisYear: Long, profitLastYear: Long, loads: List<VehicleLoad>,
    extra: List<Pair<String, String>> = emptyList(),
) {
    val c = LocalColors.current
    Head(stringResource(Res.string.head_running))
    StatColumns(
        listOf(
            stringResource(Res.string.speed) to stringResource(Res.string.kmh, kmh),
            stringResource(Res.string.top_speed) to stringResource(Res.string.kmh, top),
            stringResource(Res.string.age) to pluralStringResource(Res.plurals.years_n, age, age),
        ),
        valueColours = listOf(null, null, if (old) c.warn else null),
    )
    FillBarRow(stringResource(Res.string.reliability), reliability / 100f)
    for ((label, value) in extra) InfoRow(label, value)
    Head(stringResource(Res.string.head_profit))
    StatColumns(
        listOf(stringResource(Res.string.this_year) to money(profitThisYear), stringResource(Res.string.last_year) to money(profitLastYear)),
        valueColours = listOf(if (profitThisYear < 0) c.bad else null, if (profitLastYear < 0) c.bad else null),
    )
    if (loads.isNotEmpty()) {
        Head(stringResource(Res.string.head_load))
        for (l in loads) {
            CargoRow(l.cargo, stringResource(Res.string.load_of, l.load, l.capacity), share = if (l.capacity > 0) l.load.toFloat() / l.capacity else 0f)
        }
    }
}

/** The colour a vehicle's status line is drawn in: red for a wreck, amber when something's wrong. */
@Composable
fun statusColour(wrecked: Boolean, trouble: Boolean): Color {
    val c = LocalColors.current
    return when {
        wrecked -> c.bad
        trouble -> c.warn
        else -> c.text
    }
}

/** Which of an order's flags a kind of vehicle can be given. */
enum class OrderFlags { TRAIN, ROAD, CRAFT }

/** What an order says to do besides stopping, as one short line, or null if nothing. */
@Composable
private fun orderLine(o: Int): String? {
    val parts = ArrayList<String>()
    if (Order.has(o, Order.VIA)) parts += stringResource(Res.string.go_via)
    if (Order.has(o, Order.FULL_LOAD)) parts += stringResource(Res.string.full_load)
    if (Order.has(o, Order.NO_LOAD)) parts += stringResource(Res.string.no_loading)
    if (Order.has(o, Order.UNLOAD)) parts += stringResource(Res.string.unload_all)
    if (Order.has(o, Order.TRANSFER)) parts += stringResource(Res.string.transfer)
    if (Order.waitDays(o) > 0) parts += pluralStringResource(Res.plurals.wait_days_n, Order.waitDays(o), Order.waitDays(o))
    if (Order.has(o, Order.SKIP_IF_NOTHING)) parts += stringResource(Res.string.skip_if_nothing)
    return parts.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
}

/**
 * One stop in a vehicle's orders: its number, lit when it's the [current] one, the kind of stop's [glyph], the
 * station's name and what to do there. Tapping it opens it, [open], with [below] under it.
 */
@Composable
private fun OrderRow(number: Int, name: String, line: String?, glyph: Glyph, current: Boolean, open: Boolean, onClick: () -> Unit, below: @Composable () -> Unit) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(KitSpace.Corner)
    // Opened, the stop and what's under it scroll into sight, once they're laid out.
    val sight = remember { BringIntoViewRequester() }
    LaunchedEffect(open) {
        if (open) {
            withFrameNanos { }
            sight.bringIntoView()
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(sight)
            .clip(shape)
            .background(if (open) c.accent.copy(alpha = 0.16f) else c.button)
            .border(2.dp, if (open) c.accent else c.button, shape)
            .padding(horizontal = KitSpace.RowAcross, vertical = KitSpace.RowDown),
        verticalArrangement = Arrangement.spacedBy(KitSpace.Gap),
    ) {
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { selected = open }.clickable(role = Role.Button, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KitSpace.Inline),
        ) {
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(if (current) c.accent else c.page),
                contentAlignment = Alignment.Center,
            ) {
                Text(number.toString(), color = if (current) c.onAccent else c.text, style = KitType.Small, textAlign = TextAlign.Center)
            }
            GlyphIcon(glyph, c.textDim, Modifier.size(KitSpace.Glyph))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(name, color = c.text, style = KitType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (line != null) Text(line, color = c.textDim, style = KitType.Dim, maxLines = 2)
            }
        }
        if (open) below()
    }
}

/** Which stop is open: the [nth] time [station] comes in the orders, so it stays open as stops move or the orders change. */
private data class OpenStop(val station: Int, val nth: Int)

/** Where [stop] is in [orders] now, or -1 if it's gone. */
private fun place(orders: IntArray, stop: OpenStop?): Int {
    if (stop == null) return -1
    var seen = 0
    for ((i, o) in orders.withIndex()) if (Order.station(o) == stop.station && seen++ == stop.nth) return i
    return -1
}

/** The stop at [i] in [orders], as an [OpenStop]. */
private fun stopAt(orders: IntArray, i: Int): OpenStop {
    val station = Order.station(orders[i])
    return OpenStop(station, (0 until i).count { Order.station(orders[it]) == station })
}

/** How long an edit to the orders waits to come back from the game before more edits are let through anyway. */
private const val EDIT_WAIT_MS = 1500L

/**
 * Sends edits of a vehicle's [orders] through [set]. The orders as they were when an edit was sent: until they
 * change, the next edit would work from old orders and undo it, so edits wait for it to come back.
 */
class OrderEdits(private val orders: IntArray, private val sentFrom: MutableState<IntArray?>, private val set: (IntArray) -> Unit) {
    val waiting get() = sentFrom.value?.contentEquals(orders) == true

    fun send(new: IntArray) {
        if (waiting) return
        sentFrom.value = orders
        set(new)
    }
}

/** The [OrderEdits] for [vehicle]'s [orders], letting edits through again after a while if the orders never change. */
@Composable
fun rememberOrderEdits(vehicle: Int, orders: IntArray, set: (IntArray) -> Unit): OrderEdits {
    val sentFrom = remember(vehicle) { mutableStateOf<IntArray?>(null) }
    LaunchedEffect(sentFrom.value) {
        if (sentFrom.value != null) {
            delay(EDIT_WAIT_MS)
            sentFrom.value = null
        }
    }
    return OrderEdits(orders, sentFrom, set)
}

/**
 * A vehicle's [orders] as a numbered list, the one at [order] lit, and a tap on one opening what can be changed about
 * it: what [flags] lets this kind of vehicle do there, how long to wait, moving it and taking it off. [set] sends the
 * new orders. [stopGlyph] marks each stop, the waypoint glyph for waypoints and buoys. [vehicle] keys what's open.
 */
@Composable
fun OrdersList(view: WorldView, vehicle: Int, orders: IntArray, order: Int, flags: OrderFlags, stopGlyph: Glyph, edits: OrderEdits? = null, set: (IntArray) -> Unit) {
    val c = LocalColors.current
    val edits = edits ?: rememberOrderEdits(vehicle, orders, set)
    var open by remember(vehicle) { mutableStateOf<OpenStop?>(null) }
    fun send(new: IntArray, opened: OpenStop?) {
        if (edits.waiting) return
        open = opened
        edits.send(new)
    }
    if (orders.isEmpty()) {
        Text(stringResource(Res.string.no_stops), color = c.textDim, style = KitType.Body)
        return
    }
    val stations = remember(view.stations) { view.stations.associateBy { it.id } }
    val at = place(orders, open)
    for ((i, o) in orders.withIndex()) {
        val s = stations[Order.station(o)]
        OrderRow(
            i + 1, s?.name?.takeIf { it.isNotEmpty() } ?: stringResource(Res.string.station_n, Order.station(o) + 1), orderLine(o),
            if (s?.waypoint == true) Glyph.Waypoint else stopGlyph,
            current = i == order % orders.size, open = i == at, onClick = { open = if (at == i) null else stopAt(orders, i) },
        ) {
            OrderEditor(o, flags, i, orders.size, change = { new -> send(orders.copyOf().also { a -> a[i] = new }, open) }, move = { to ->
                val a = orders.copyOf()
                a[i] = a[to]
                a[to] = o
                send(a, stopAt(a, to))
            }) {
                send(orders.filterIndexed { j, _ -> j != i }.toIntArray(), null)
            }
        }
    }
}

/** What loading an order says: as usual, a full load or nothing. */
private val LOADING = listOf(0, Order.FULL_LOAD, Order.NO_LOAD)
/** What unloading an order says: as usual, everything off or everything off to change. */
private val UNLOADING = listOf(0, Order.UNLOAD, Order.TRANSFER)

/** The controls for order [o], at [at] of [count]: its flags, its wait, moving it up or down and removing it. */
@Composable
private fun OrderEditor(o: Int, flags: OrderFlags, at: Int, count: Int, change: (Int) -> Unit, move: (Int) -> Unit, remove: () -> Unit) {
    val c = LocalColors.current
    if (flags != OrderFlags.CRAFT) {
        val loading = LOADING.firstOrNull { it != 0 && Order.has(o, it) } ?: 0
        val names = listOf(stringResource(Res.string.load_as_usual), stringResource(Res.string.full_load), stringResource(Res.string.no_loading))
        SegmentedChoice(LOADING, loading, { names[LOADING.indexOf(it)] }, perRow = 3) { change((o and (Order.FULL_LOAD or Order.NO_LOAD).inv()) or it) }
    }
    if (flags == OrderFlags.TRAIN) {
        val unloading = UNLOADING.firstOrNull { it != 0 && Order.has(o, it) } ?: 0
        val names = listOf(stringResource(Res.string.unload_as_usual), stringResource(Res.string.unload_all), stringResource(Res.string.transfer))
        SegmentedChoice(UNLOADING, unloading, { names[UNLOADING.indexOf(it)] }, perRow = 3) { change((o and (Order.UNLOAD or Order.TRANSFER).inv()) or it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KitSpace.Gap)) {
            TogglePill(stringResource(Res.string.go_via), Order.has(o, Order.VIA), Modifier.weight(1f)) { change(Order.toggle(o, Order.VIA)) }
            TogglePill(stringResource(Res.string.skip_if_nothing), Order.has(o, Order.SKIP_IF_NOTHING), Modifier.weight(1f)) { change(Order.toggle(o, Order.SKIP_IF_NOTHING)) }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitSpace.Gap)) {
        val wait = Order.waitDays(o)
        SquareButton(Glyph.Minus, stringResource(Res.string.wait_less), 32.dp, enabled = wait > 0) { change(Order.withWait(o, wait - 1)) }
        Text(
            pluralStringResource(Res.plurals.wait_days_n, wait, wait), color = c.text, style = KitType.Value,
            textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f),
        )
        SquareButton(Glyph.Plus, stringResource(Res.string.wait_more), 32.dp, enabled = wait < Order.MAX_WAIT_DAYS) { change(Order.withWait(o, wait + 1)) }
        Box(Modifier.size(KitSpace.Gap))
        SquareButton(Glyph.Up, stringResource(Res.string.move_up), 32.dp, enabled = at > 0) { move(at - 1) }
        SquareButton(Glyph.Down, stringResource(Res.string.move_down), 32.dp, enabled = at < count - 1) { move(at + 1) }
        SquareButton(Glyph.Remove, stringResource(Res.string.remove_stop), 32.dp, onClick = remove)
    }
}

/** The button under a vehicle's orders that starts and ends adding stops by tapping stations on the map. */
@Composable
fun addStopsTile(adding: Boolean, onClick: () -> Unit) =
    TileButton(Glyph.Plus, stringResource(if (adding) Res.string.done_adding else Res.string.add_stops), lit = adding, onClick = onClick)

/**
 * A vehicle's card while stops are added from the map: its [title], its [orders] in a short list that keeps the
 * newest in view and still opens a stop to change it, and buttons to finish and to take off the last stop. The
 * rest of the card comes back after [onDone].
 */
@Composable
fun AddingStopsCard(
    title: String,
    view: WorldView,
    vehicle: Int,
    orders: IntArray,
    order: Int,
    flags: OrderFlags,
    stopGlyph: Glyph,
    set: (IntArray) -> Unit,
    onDone: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val edits = rememberOrderEdits(vehicle, orders, set)
    SlimCard(title, onClose, modifier, follow = orders.size, list = {
        OrdersList(view, vehicle, orders, order, flags, stopGlyph, edits, set)
    }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KitSpace.Gap)) {
            BigButton(stringResource(Res.string.done_adding), primary = true, glyph = Glyph.Tick, modifier = Modifier.weight(1f), onClick = onDone)
            BigButton(
                stringResource(Res.string.remove_last_stop), glyph = Glyph.Back, enabled = orders.isNotEmpty() && !edits.waiting,
                modifier = Modifier.weight(1f),
            ) { edits.send(orders.copyOf(orders.size - 1)) }
        }
    }
}
