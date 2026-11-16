package com.rm.interchange.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rm.interchange.res.*
import com.rm.interchange.server.GameThread
import com.rm.interchange.sim.COMPANY_VALUES
import com.rm.interchange.sim.COMPANY_VALUE_NAME_MOST
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.Order
import com.rm.interchange.sim.WorldView
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/** [orders] with the one at [i] taken off, the conditional orders' jumps moved to follow. */
fun ordersWithout(orders: IntArray, i: Int): IntArray {
    if (i !in orders.indices) return orders
    // A stand-in no order equals, as the orders are told apart by value.
    var gone = Int.MAX_VALUE
    while (gone in orders) gone--
    return Order.remapped(orders.mapIndexed { j, o -> if (j == i) gone else o }) { if (it == gone) null else it }.toIntArray()
}

/** [orders] with the ones at [i] and [j] swapped, the conditional orders' jumps to either following it. */
fun ordersSwapped(orders: IntArray, i: Int, j: Int): IntArray {
    val a = orders.copyOf()
    a[i] = orders[j]
    a[j] = orders[i]
    for (k in a.indices) if (Order.isCond(a[k])) {
        val t = Order.condTarget(a[k])
        if (t == i) a[k] = Order.withTarget(a[k], j) else if (t == j) a[k] = Order.withTarget(a[k], i)
    }
    return a
}

private fun valueMost(what: Int) = if (what == Order.IF_LOAD || what == Order.IF_RELIABILITY) 100 else Order.COND_VALUE_MOST

/** What a conditional order's test is called for [what]'s group: the company value one for any value. */
private fun whatGroup(what: Int) = if (what >= Order.IF_VALUE) Order.IF_VALUE else what

/** Company value [k]'s name, or "Value k+1" if it has none. */
@Composable
fun valueName(view: WorldView, k: Int): String = view.valueNames.getOrNull(k)?.takeIf { it.isNotEmpty() } ?: stringResource(Res.string.value_n, k + 1)

/** A conditional order in words: "If load is over 80%, go to order 3". */
@Composable
fun condText(view: WorldView, o: Int): String {
    val words = stringArrayResource(Res.array.cond_compare)
    val cmp = words.getOrElse(Order.condCompare(o)) { "" }
    val what = Order.condWhat(o)
    val v = Order.condValue(o)
    val to = Order.condTarget(o) + 1
    return when {
        what == Order.IF_LOAD -> stringResource(Res.string.cond_if_load, cmp, "$v%", to)
        what == Order.IF_AGE -> stringResource(Res.string.cond_if_age, cmp, v.toString(), to)
        what == Order.IF_RELIABILITY -> stringResource(Res.string.cond_if_reliability, cmp, "$v%", to)
        what == Order.IF_SINCE_SERVICE -> stringResource(Res.string.cond_if_since_service, cmp, v.toString(), to)
        what == Order.IF_ALWAYS -> stringResource(Res.string.cond_if_always, to)
        else -> stringResource(Res.string.cond_if_value, valueName(view, what - Order.IF_VALUE), cmp, v.toString(), to)
    }
}

/** A new conditional order at the end of [count] orders: if load is over 80%, go to the first. */
fun newCondition() = Order.cond(Order.IF_LOAD, Order.MORE, 80, 0)

/** The controls for conditional order [o] of [count]: what to test, how, against what, and where to jump. */
@Composable
fun CondEditor(view: WorldView, o: Int, count: Int, change: (Int) -> Unit) {
    val c = LocalColors.current
    val what = Order.condWhat(o)
    val group = whatGroup(what)
    val groups = listOf(Order.IF_LOAD, Order.IF_AGE, Order.IF_RELIABILITY, Order.IF_SINCE_SERVICE, Order.IF_ALWAYS, Order.IF_VALUE)
    val names = listOf(
        stringResource(Res.string.cond_load), stringResource(Res.string.cond_age), stringResource(Res.string.cond_reliability),
        stringResource(Res.string.cond_since_service), stringResource(Res.string.cond_always), stringResource(Res.string.cond_value),
    )
    fun with(what: Int = Order.condWhat(o), compare: Int = Order.condCompare(o), value: Int = Order.condValue(o), target: Int = Order.condTarget(o)) =
        Order.cond(what, compare, value.coerceIn(0, valueMost(what)), target)
    SegmentedChoice(groups, group, { names[groups.indexOf(it)] }, perRow = 3) { g -> change(with(what = if (g == Order.IF_VALUE) Order.IF_VALUE else g)) }
    if (group == Order.IF_VALUE) {
        val ks = (0 until COMPANY_VALUES).toList()
        val valueNames = ks.map { valueName(view, it) }
        SegmentedChoice(ks, what - Order.IF_VALUE, { valueNames[it] }, perRow = 4) { change(with(what = Order.IF_VALUE + it)) }
    }
    if (what != Order.IF_ALWAYS) {
        val words = stringArrayResource(Res.array.cond_compare)
        val compares = (Order.LESS..Order.MORE).toList()
        SegmentedChoice(compares, Order.condCompare(o), { words[it] }, perRow = 3) { change(with(compare = it)) }
        val v = Order.condValue(o)
        val most = valueMost(what)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitSpace.Gap)) {
            ActionButton("-10", enabled = v > 0) { change(with(value = v - 10)) }
            SquareButton(Glyph.Minus, stringResource(Res.string.value_less), 32.dp, enabled = v > 0) { change(with(value = v - 1)) }
            Text(v.toString(), color = c.text, style = KitType.Value, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
            SquareButton(Glyph.Plus, stringResource(Res.string.value_more), 32.dp, enabled = v < most) { change(with(value = v + 1)) }
            ActionButton("+10", enabled = v < most) { change(with(value = v + 10)) }
        }
    }
    val target = Order.condTarget(o)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitSpace.Gap)) {
        SquareButton(Glyph.Minus, stringResource(Res.string.order_before), 32.dp, enabled = target > 0) { change(with(target = target - 1)) }
        Text(stringResource(Res.string.cond_go_to, target + 1), color = c.text, style = KitType.Value, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
        SquareButton(Glyph.Plus, stringResource(Res.string.order_after), 32.dp, enabled = target < count - 1) { change(with(target = target + 1)) }
    }
}

/** The company's eight values for conditional orders: each with a name and a stepper. */
@Composable
fun ValuesWindow(runner: GameThread, onClose: () -> Unit) {
    val view by produceState(runner.view, runner) {
        while (true) androidx.compose.runtime.withFrameNanos { value = runner.view }
    }
    val v = view ?: return
    val me = LocalCompany.current
    val c = LocalColors.current
    Window(stringResource(Res.string.company_values), onClose, Glyph.Calendar) {
        for (k in 0 until COMPANY_VALUES) androidx.compose.runtime.key(k) {
            val value = v.values.getOrElse(k) { 0 }
            val name = v.valueNames.getOrElse(k) { "" }
            var text by remember { mutableStateOf(name) }
            // Renamed by someone else: the field follows, unless it's being typed in.
            var seen by remember { mutableStateOf(name) }
            androidx.compose.runtime.LaunchedEffect(name) {
                if (text == seen) text = name
                seen = name
            }
            val clean = text.trim().take(COMPANY_VALUE_NAME_MOST).trim()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { Field(text, stringResource(Res.string.value_n, k + 1), { text = it.take(COMPANY_VALUE_NAME_MOST) }) }
                ActionButton(stringResource(Res.string.rename), enabled = clean != name, strong = true) { runner.request(Command.CompanyValue(me, k, -1, clean)) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitSpace.Gap)) {
                fun set(x: Int) = runner.request(Command.CompanyValue(me, k, x.coerceIn(0, Order.COND_VALUE_MOST), null))
                ActionButton("-10", enabled = value > 0) { set(value - 10) }
                SquareButton(Glyph.Minus, stringResource(Res.string.value_less), 32.dp, enabled = value > 0) { set(value - 1) }
                Text(value.toString(), color = c.text, style = KitType.Value, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
                SquareButton(Glyph.Plus, stringResource(Res.string.value_more), 32.dp, enabled = value < Order.COND_VALUE_MOST) { set(value + 1) }
                ActionButton("+10", enabled = value < Order.COND_VALUE_MOST) { set(value + 10) }
            }
        }
    }
}
