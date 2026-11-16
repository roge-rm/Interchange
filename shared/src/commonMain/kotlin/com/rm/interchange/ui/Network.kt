package com.rm.interchange.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import com.rm.interchange.res.*
import com.rm.interchange.sim.*
import org.jetbrains.compose.resources.stringResource

/** The latest view built with the network (links and why cargo can't get there), or null while it isn't being built. */
val LocalNetwork = compositionLocalOf<WorldView?> { null }

/** [ticks] as days to a tenth, "2.4 days", or "About 2.4 days" for an estimate. */
@Composable
fun daysText(ticks: Int, about: Boolean = false): String {
    val tenths = (ticks * 10 + Calendar.TICKS_PER_DAY / 2) / Calendar.TICKS_PER_DAY
    return stringResource(if (about) Res.string.days_tenths_about else Res.string.days_tenths, tenths / 10, tenths % 10)
}

/** Whether a link's runs stood held for more than a quarter of the journey last month. */
fun LinkView.heldLong() = runs > 0 && ticks > 0 && lostPerRun * 4 > ticks

/** How full a link ran last month, 0 to 1, for [cargo] or all cargo when it's -1; null with no room leaving on it. */
fun LinkView.share(cargo: Int): Float? {
    val room = if (cargo < 0) capacity.sum() else capacity.getOrElse(cargo) { 0 }
    val carried = if (cargo < 0) load.sum() else load.getOrElse(cargo) { 0 }
    return if (room > 0) carried.toFloat() / room else null
}

/** The colour of a load share, as [FillBar] fills: red when low, amber in the middle and green when high. */
fun shareColour(share: Float, c: Colors): Color = when {
    share < 1f / 3 -> c.bad
    share < 2f / 3 -> c.warn
    else -> c.good
}

/** A short reason for a [WhyNot] value, or null when it can get there or there's no reason. */
@Composable
fun whyText(why: Int): String? = when (why) {
    WhyNot.NO_SERVICE -> stringResource(Res.string.why_no_service)
    WhyNot.NOT_CARRIED -> stringResource(Res.string.why_not_carried)
    WhyNot.ORDERS -> stringResource(Res.string.why_orders)
    WhyNot.NOT_ACCEPTED -> stringResource(Res.string.why_not_accepted)
    else -> null
}

/**
 * The link a vehicle of [mode] and [id] is running now, towards the station of its current order: of the links it
 * runs there, the one from the stop before in its orders, or else any.
 */
fun currentLink(network: WorldView?, mode: Int, id: Int, orders: IntArray, order: Int): LinkView? {
    if (network == null || orders.isEmpty()) return null
    val now = orders[order.mod(orders.size)]
    if (Order.isDepot(now)) return null
    val to = Order.station(now)
    val runs = network.links.filter { l -> l.to == to && l.vehicleIds.indices.any { l.vehicleIds[it] == id && l.vehicleModes[it] == mode } }
    if (runs.size <= 1) return runs.firstOrNull()
    for (back in 1 until orders.size) {
        val o = orders[(order - back).mod(orders.size)]
        if (Order.isDepot(o)) continue
        runs.firstOrNull { it.from == Order.station(o) }?.let { return it }
    }
    return runs.first()
}

/** Rows for a vehicle card: the journey time and time held of the link it's running now, if the network is shown. */
@Composable
fun linkRows(mode: Int, id: Int, orders: IntArray, order: Int): List<Pair<String, String>> {
    val link = currentLink(LocalNetwork.current, mode, id, orders, order) ?: return emptyList()
    return listOfNotNull(
        stringResource(Res.string.link_journey) to daysText(link.ticks, !link.measured),
        (stringResource(Res.string.link_held) to daysText(link.lostPerRun)).takeIf { link.runs > 0 },
    )
}

/**
 * A link between two stations: its journey time, time held a run, departures, what it carried last month against
 * the room there was, and the vehicles that run it, each tapped to open it with [onVehicle] (mode, id).
 */
@Composable
fun LinkWindow(network: WorldView, roster: Roster, link: LinkView, onVehicle: (Int, Int) -> Unit, onClose: () -> Unit) {
    val c = LocalColors.current
    val lang = languageCode()
    Window(stringResource(Res.string.link_title, stationName(network, link.from), stationName(network, link.to)), onClose, Glyph.Links) {
        InfoRow(stringResource(Res.string.link_journey), daysText(link.ticks, !link.measured), Glyph.Calendar)
        if (link.runs > 0) InfoRow(stringResource(Res.string.link_held), daysText(link.lostPerRun), Glyph.Signal,
            valueColour = if (link.heldLong()) c.warn else null)
        InfoRow(stringResource(Res.string.link_departures), Numbers.grouped(link.departures.toLong()), Glyph.Up)
        Head(stringResource(Res.string.head_link_load), Glyph.Package)
        val cargoes = (0 until Cargo.COUNT).filter { link.capacity.getOrElse(it) { 0 } > 0 }
        if (cargoes.isEmpty()) Text(stringResource(Res.string.link_no_departures), color = c.textDim, style = KitType.Body)
        for (cg in cargoes) {
            val room = link.capacity[cg]
            CargoRow(cg, stringResource(Res.string.load_of, link.load[cg], room), share = link.load[cg].toFloat() / room)
        }
        if (link.vehicleIds.isNotEmpty()) Head(stringResource(Res.string.vehicles), Glyph.Train)
        for (i in link.vehicleIds.indices) key(link.vehicleModes[i], link.vehicleIds[i]) {
            val id = link.vehicleIds[i]
            val mode = link.vehicleModes[i]
            val row: Triple<String, Engine, String>? = when (mode) {
                OverviewVehicle.TRAIN -> network.trains.firstOrNull { it.id == id }?.let { Triple(trainName(it), roster[it.engine], trainFormationName(roster, it)) }
                OverviewVehicle.ROAD -> network.roadVehicles.firstOrNull { it.id == id }?.let { Triple(roadVehicleName(it), roster[it.model], roster[it.model].name(lang)) }
                OverviewVehicle.SHIP -> network.ships.firstOrNull { it.id == id }?.let { Triple(shipName(it), roster[it.model], roster[it.model].name(lang)) }
                else -> network.aircraft.firstOrNull { it.id == id }?.let { Triple(aircraftName(it), roster[it.model], roster[it.model].name(lang)) }
            }
            if (row != null) PictureRow(row.first, picture = { VehiclePicture(row.second, it) }, line = row.third, onClick = { onVehicle(mode, id) })
        }
    }
}
