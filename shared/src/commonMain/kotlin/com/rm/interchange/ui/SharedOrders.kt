package com.rm.interchange.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rm.interchange.res.*
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.Order
import com.rm.interchange.sim.Roster
import com.rm.interchange.sim.WorldView
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

internal class SharedOrderChoice(val id: Int, val name: String, val model: Int, val orders: IntArray)

/** Shared orders and the picker, with the add-stops tile in the same row. */
@Composable
internal fun SharedOrdersControls(
    view: WorldView,
    roster: Roster,
    id: Int,
    group: Int,
    gap: Int,
    others: List<SharedOrderChoice>,
    adding: Boolean,
    onAdd: () -> Unit,
    order: (Int, IntArray) -> Unit,
    stops: @Composable () -> Unit,
) {
    val shared = group > 0
    if (shared) Text(pluralStringResource(Res.plurals.shared_with, group, group), color = LocalColors.current.textDim, style = KitType.Dim)
    stops()
    var sharing by remember(id) { mutableStateOf(false) }
    ButtonTiles(listOfNotNull(
        addStopsTile(adding, onAdd),
        when {
            shared -> TileButton(Glyph.Layers, stringResource(Res.string.stop_sharing)) { order(Command.TrainOrder.UNSHARE, IntArray(0)) }
            others.isNotEmpty() -> TileButton(Glyph.Layers, stringResource(Res.string.share_orders), lit = sharing) { sharing = !sharing }
            else -> null
        },
    ))
    if (sharing && !shared) {
        val stations = remember(view.stations) { view.stations.associateBy { it.id } }
        val none = stringResource(Res.string.no_stops)
        for (o in others) key(o.id) {
            val unnamed = o.orders.map { Order.station(it) }.filter { stations[it]?.name.isNullOrEmpty() }.distinct()
                .associateWith { stringResource(Res.string.station_n, it + 1) }
            val line = remember(o.orders.contentHashCode(), stations, unnamed) {
                if (o.orders.isEmpty()) none
                else o.orders.joinToString("  ·  ") { Order.station(it).let { id -> unnamed[id] ?: stations[id]?.name ?: "" } }
            }
            PictureRow(
                o.name, picture = { VehiclePicture(roster[o.model], it) },
                line = line,
                onClick = {
                    order(Command.TrainOrder.SHARE, intArrayOf(o.id))
                    sharing = false
                },
            )
        }
    }
    GapControls(gap) { order(Command.TrainOrder.GAP, intArrayOf(it)) }
}

/** The least number of days between departures on this order list. */
@Composable
internal fun GapControls(gap: Int, change: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitSpace.Gap)) {
        SquareButton(Glyph.Minus, stringResource(Res.string.gap_less), 32.dp, enabled = gap > 0) { change(gap - 1) }
        Text(
            if (gap == 0) stringResource(Res.string.no_gap) else pluralStringResource(Res.plurals.days_apart, gap, gap), color = LocalColors.current.text, style = KitType.Value,
            textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f),
        )
        SquareButton(Glyph.Plus, stringResource(Res.string.gap_more), 32.dp, enabled = gap < Order.MAX_WAIT_DAYS) { change(gap + 1) }
    }
}
