package com.rm.interchange.ui

import com.rm.interchange.res.*
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.Handling
import com.rm.interchange.sim.HandlingView
import com.rm.interchange.sim.FlowView
import com.rm.interchange.sim.Equipment
import androidx.compose.foundation.layout.size
import com.rm.interchange.sim.Engine
import com.rm.interchange.sim.VehicleKind
import com.rm.interchange.sim.Traction
import com.rm.interchange.sim.Cargo
import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.OverviewVehicle
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
fun trainName(t: TrainView): String = t.name.takeIf { it.isNotEmpty() } ?: stringResource(Res.string.train_n, if (t.number > 0) t.number else t.id + 1)

/** A train: its picture and what it's doing, its numbers, its orders, and buttons to run it. */
@Composable
fun TrainCard(
    view: WorldView,
    roster: Roster,
    t: TrainView,
    year: Int,
    funds: Long,
    price: (Long) -> Long,
    addingStops: Boolean,
    onAddStops: () -> Unit,
    send: (Command) -> Unit,
    onShow: (Float, Float) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val me = LocalCompany.current
    fun order(what: Int, stations: IntArray = IntArray(0)) = send(Command.TrainOrder(me, t.id, what, stations))
    // While adding stops, only the orders and what goes with adding them, so the map stays clear.
    if (addingStops && t.owner == me && !t.wrecked) {
        AddingStopsCard(trainName(t), view, t.id, t.orders, t.order, OrderFlags.TRAIN, Glyph.Station, { order(Command.TrainOrder.ORDERS, it) }, onAddStops, onClose, modifier)
        return
    }
    var editingFormation by remember(t.id) { mutableStateOf(false) }
    if (editingFormation && t.owner == me && t.inDepot && !t.wrecked) {
        Card(trainName(t), onClose, modifier) {
            VehicleHeader(roster[t.engine], trainFormationName(roster, t), trainStatus(view, t), statusColour(false, trainWrong(t)))
            TrainFormationEditor(
                roster, t, year, funds, price, view.depots.firstOrNull { it.id == t.shed }?.wired == true,
                apply = { send(Command.EditTrain(me, t.id, it)); editingFormation = false },
                cancel = { editingFormation = false },
            )
        }
        return
    }
    Card(trainName(t), onClose, modifier) {
        val doing = trainStatus(view, t)
        val lang = languageCode()
        val e = roster[t.engine]
        val w = roster[t.wagon]
        val mine = t.owner == me
        VehicleHeader(
            e, trainFormationName(roster, t),
            doing, statusColour(t.wrecked, trainWrong(t)), if (mine) null else view.companies.getOrElse(t.owner) { "" },
        )
        VehicleStats(
            t.kmh, t.topSpeed, t.age, t.old, t.reliability, t.profitThisYear, t.profitLastYear,
            (0 until Cargo.MAX).filter { t.capacity[it] > 0 }.map { VehicleLoad(it, t.load[it], t.capacity[it]) },
            listOfNotNull(
                stringResource(Res.string.breakdowns) to t.breakdowns.toString(),
            ) + worthRows(t.value, t.nextService, mine) + linkRows(OverviewVehicle.TRAIN, t.id, t.orders, t.order),
            t.dieselSpeed, t.curveKmh, t.energy,
        )
        // Someone else's shows what it is but none of the controls, and a wreck is cleared away by itself.
        if (!mine || t.wrecked) return@Card
        VehicleGroupControls(view, Command.CopyVehicle.TRAIN, t.id, t.group, send) { order(Command.TrainOrder.GROUP, intArrayOf(it)) }
        if (t.inDepot && t.formation.all { it.model == t.engine || it.model == t.wagon } && t.formation.filter { it.model == t.wagon }.map { it.cargo }.distinct().size <= 1 && w.cargoes.size > 1) {
            val cargoes = cargoNames()
            val selected = if (t.wagonCargo in w.cargoes) t.wagonCargo else w.cargoes.first()
            SegmentedChoice(w.cargoes.toList(), selected, { cargoes[it] }) { order(Command.TrainOrder.REFIT, intArrayOf(it)) }
        }
        Head(stringResource(Res.string.orders))
        val group = if (t.shared >= 0) view.trains.count { it.id != t.id && it.shared == t.shared } else 0
        // The last train left in a group shares with nobody, so it's shown as not sharing.
        val shared = group > 0
        if (shared) Text(pluralStringResource(Res.plurals.shared_with, group, group), color = LocalColors.current.textDim, style = KitType.Dim)
        ServiceLine(view, t.serviceId, t.late)
        OrdersList(view, t.id, t.orders, t.order, OrderFlags.TRAIN, Glyph.Station) { order(Command.TrainOrder.ORDERS, it) }
        RangeWarning(t.energy)
        // Sharing orders with another train: pick which from a list, or stop sharing.
        var sharing by remember(t.id) { mutableStateOf(false) }
        val others = view.trains.filter { it.id != t.id && it.owner == me }
        ButtonTiles(listOfNotNull(
            addStopsTile(addingStops, t.orders.size >= Order.ORDERS_MOST, onAddStops),
            when {
                shared -> TileButton(Glyph.Layers, stringResource(Res.string.stop_sharing)) { order(Command.TrainOrder.UNSHARE) }
                others.isNotEmpty() -> TileButton(Glyph.Layers, stringResource(Res.string.share_orders), lit = sharing) { sharing = !sharing }
                else -> null
            },
            serviceTile(Command.CopyVehicle.TRAIN, t.id, t.serviceId, send),
        ))
        if (sharing && !shared) {
            val stations = remember(view.stations) { view.stations.associateBy { it.id } }
            val none = stringResource(Res.string.no_stops)
            val depotWord = stringResource(Res.string.depot)
            for (o in others) key(o.id) {
                // Unnamed stations are rare, so their stand-in names are looked up one by one.
                val unnamed = o.orders.map { Order.station(it) }.filter { it >= 0 && stations[it]?.name.isNullOrEmpty() }.distinct()
                    .associateWith { stringResource(Res.string.station_n, it + 1) }
                val line = remember(o.orders.contentHashCode(), stations, unnamed) {
                    if (o.orders.isEmpty()) none
                    else o.orders.filter { !Order.isCond(it) }.joinToString("  ·  ") { if (Order.isDepot(it)) depotWord else Order.station(it).let { id -> unnamed[id] ?: stations[id]?.name ?: "" } }
                }
                PictureRow(
                    trainName(o), picture = { VehiclePicture(roster[o.engine], it) },
                    line = line,
                    onClick = {
                        order(Command.TrainOrder.SHARE, intArrayOf(o.id))
                        sharing = false
                    },
                )
            }
        }
        GapControls(t.gap) { order(Command.TrainOrder.GAP, intArrayOf(it)) }
        Box(Modifier.height(KitSpace.Tight))
        var replacing by remember(t.id) { mutableStateOf(false) }
        ButtonTiles(
            listOf(
                TileButton(if (t.running) Glyph.Pause else Glyph.Play, stringResource(if (t.running) Res.string.stop else Res.string.start), strong = !t.running) { order(Command.TrainOrder.START_STOP) },
            ) + if (!t.inDepot) listOf(
                TileButton(Glyph.Depot, stringResource(if (t.toDepot) Res.string.carry_on else Res.string.to_depot), lit = t.toDepot) { order(Command.TrainOrder.TO_DEPOT) },
                TileButton(Glyph.TurnLeft, stringResource(Res.string.turn_round)) { order(Command.TrainOrder.TURN) },
            ) else listOf(
                TileButton(Glyph.Train, stringResource(Res.string.edit_formation)) { editingFormation = true },
                TileButton(Glyph.Renew, stringResource(Res.string.renew_for, money(t.renewCost))) { order(Command.TrainOrder.RENEW) },
                TileButton(Glyph.Replace, stringResource(Res.string.replace), lit = replacing) { replacing = !replacing },
                TileButton(Glyph.Minus, stringResource(Res.string.sell)) { order(Command.TrainOrder.SELL) },
            ),
        )
        if (t.inDepot && replacing) {
            val stockCost = t.formation.sumOf { car -> roster[car.model].let { it.cost + (roster.getOrNull(it.tender)?.cost ?: 0) } }
            fun base(engine: Engine) = stockCost - e.cost - (roster.getOrNull(e.tender)?.cost ?: 0) + engine.cost + (roster.getOrNull(engine.tender)?.cost ?: 0)
            ReplaceList(
                roster.available(year, pulls = true).filter { it.id != t.engine }, funds,
                costOf = { price(base(it)) - (price(base(e)) - t.renewCost) },
                blocked = { it.traction == Traction.ELECTRIC && view.depots.firstOrNull { d -> d.id == t.shed }?.wired != true },
                why = t.replaceWhy,
            ) {
                order(Command.TrainOrder.REPLACE, intArrayOf(it))
                replacing = false
            }
        }
        UpgradeLine(roster, t.upgradeTo, t.upgradeWhy, t.service)
        SwitchRow(stringResource(Res.string.servicing), t.service) { order(Command.TrainOrder.SERVICE) }
        VehicleMapControls(companyRenews(view), t.renewOff, t.x, t.y, onShow) { order(Command.TrainOrder.AUTORENEW) }
    }
}

/** A depot: buying a train, its engine and wagons each picked from a list, and the trains in it. */
@Composable
fun DepotCard(view: WorldView, roster: Roster, depot: Int, year: Int, funds: Long, price: (Long) -> Long, send: (Command) -> Unit, onTrain: (Int) -> Unit, copying: Boolean, onCopy: (Int) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val me = LocalCompany.current
    val c = LocalColors.current
    val lang = languageCode()
    val engines = roster.available(year, pulls = true)
    val wagons = roster.available(year, pulls = false)
    var engineId by remember { mutableIntStateOf(-1) }
    var wagonId by remember { mutableIntStateOf(-1) }
    var count by remember { mutableIntStateOf(3) }
    var vehicleCount by remember(depot) { mutableIntStateOf(1) }
    var cargo by remember { mutableIntStateOf(-1) }
    var showWagons by remember { mutableStateOf(false) }
    var inherit by remember { mutableStateOf(false) }
    Card(stringResource(Res.string.depot), onClose, modifier, scrolls = false) {
        // What's picked, or the first on sale once the one picked goes off sale.
        val e = engines.firstOrNull { it.id == engineId } ?: engines.firstOrNull()
        val w = wagons.firstOrNull { it.id == wagonId } ?: wagons.firstOrNull()
        // An engine's price and running cost with its tender's, as it's bought.
        fun tender(t: Engine) = roster.getOrNull(t.tender)
        BuyPanel(
            top = {
                FillChips(listOf(false, true), showWagons, { stringResource(if (it) Res.string.wagons else Res.string.engines) }) { showWagons = it }
            },
            list = {
                when {
                    showWagons && w == null -> NoneOnSale(roster, VehicleKind.WAGON, year)
                    showWagons && w != null -> BuyList(wagons, w.id, cargo, price, { wagonId = it; cargo = -1 }) { cargo = it }
                    e == null -> NoneOnSale(roster, VehicleKind.ENGINE, year)
                    else -> BuyList(
                        engines, e.id, -1, price, { engineId = it },
                        costOf = { it.cost + (tender(it)?.cost ?: 0) }, runningOf = { it.running + (tender(it)?.running ?: 0) }, tenderOf = ::tender,
                    ) {}
                }
            },
            footer = {
                if (e != null) {
                    val n = if (w == null) 0 else count
                    val choices = w?.cargoes ?: IntArray(0)
                    val cost = price(e.cost + (tender(e)?.cost ?: 0) + (w?.cost ?: 0) * n) * vehicleCount
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (w == null || n == 0) e.name(lang) else stringResource(Res.string.consist_line, e.name(lang), n, if (n == 1) w.name(lang) else w.plural(lang)),
                            color = c.text, style = KitType.ListLine, maxLines = 2, modifier = Modifier.weight(1f),
                        )
                        if (w != null) {
                            SquareButton(Glyph.Minus, stringResource(Res.string.fewer_wagons), 32.dp, enabled = count > 0) { count-- }
                            Text(count.toString(), color = c.text, style = KitType.RowTitle, textAlign = TextAlign.Center, modifier = Modifier.width(24.dp))
                            SquareButton(Glyph.Plus, stringResource(Res.string.more_wagons), 32.dp, enabled = count < MAX_WAGONS) { count++ }
                        }
                    }
                    if (w == null) Text(stringResource(Res.string.no_wagons_on_sale), color = c.warn, style = KitType.ListLine)
                    // Electric engines need wires over the depot to get out.
                    val unwired = e.traction == Traction.ELECTRIC && view.depots.firstOrNull { it.id == depot }?.wired != true
                    if (unwired) Text(stringResource(Res.string.depot_needs_wires), color = c.warn, style = KitType.ListLine)
                    InheritPill(view, Command.CopyVehicle.TRAIN, inherit) { inherit = it }
                    BuyButton(cost, funds, blocked = unwired || w == null) {
                        send(Command.BuyTrain(me, depot, e.id, w?.id ?: 0, n, if (cargo in choices) cargo else -1, vehicleCount, inherit))
                    }
                }
                VehicleCount(vehicleCount) { vehicleCount = it }
                ActionButton(stringResource(Res.string.copy_one), strong = copying, onClick = { onCopy(vehicleCount) })
            },
            inDepot = {
                for (t in view.trains.filter { it.shed == depot && it.inDepot }) {
                    InDepotRow(
                        trainName(t), t.running, { onTrain(t.id) },
                        onCopy = if (t.owner == me) { { send(Command.CopyVehicle(me, Command.CopyVehicle.TRAIN, t.id, depot, vehicleCount)) } } else null,
                    ) { send(Command.TrainOrder(me, t.id, Command.TrainOrder.START_STOP)) }
                }
            },
        )
    }
}

/** A station: what's waiting there and its ratings, where passengers and mail are going, and what calls there. */
@Composable
fun StationCard(view: WorldView, roster: Roster, s: StationView, handling: HandlingView?, flows: List<FlowView>, funds: Long, send: (Command) -> Unit, onVehicle: (VehicleKind, Int) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val c = LocalColors.current
    val lang = languageCode()
    val me = LocalCompany.current
    Card(stationName(view, s.id), onClose, modifier) {
        if (s.platforms > 0) InfoRow(stringResource(Res.string.platform_tiles_label), s.platforms.toString(), Glyph.Station)
        serviceText(s.service)?.let {
            InfoRow(stringResource(Res.string.service_score), it, Glyph.Signal,
                valueColour = if (s.service >= 60) c.good else if (s.service <= 40) c.warn else null)
        }
        // Each cargo it deals in, with how much is waiting and its rating; passengers and mail always.
        Head(stringResource(Res.string.head_waiting))
        // Also what the vehicles calling here could load, before any has.
        var loadable = 0
        fun loads(orders: IntArray, cargo: (Int) -> Boolean) {
            if (orders.none { Order.station(it) == s.id && !Order.has(it, Order.NO_LOAD) }) return
            for (cg in 0 until Cargo.MAX) if (cargo(cg)) loadable = loadable or (1 shl cg)
        }
        for (t in view.trains) loads(t.orders) { t.capacity[it] > 0 }
        for (v in view.roadVehicles) loads(v.orders) { it == v.carries }
        for (v in view.ships) loads(v.orders) { it == v.carries }
        for (v in view.aircraft) loads(v.orders) { it == v.carries }
        for (cg in 0 until Cargo.MAX) {
            if (cg > Cargo.MAIL && (s.served or loadable) and (1 shl cg) == 0) continue
            CargoRow(cg, Numbers.grouped(s.waiting[cg].toLong()), share = s.rating[cg] / 255f, percent = if (s.served and (1 shl cg) == 0) "-" else null)
            // Last month's average wait for what boarded, and how many gave up, under its cargo's row.
            val waited = s.waited.getOrElse(cg) { -1 }
            val gaveUp = s.gaveUp.getOrElse(cg) { 0 }
            if (waited >= 0 || gaveUp > 0) {
                val tenths = (waited.coerceAtLeast(0) * 10 + Calendar.TICKS_PER_DAY / 2) / Calendar.TICKS_PER_DAY
                val line = listOfNotNull(
                    stringResource(Res.string.waited_days, tenths / 10, tenths % 10).takeIf { waited >= 0 },
                    pluralStringResource(Res.plurals.gave_up_n, gaveUp, Numbers.grouped(gaveUp.toLong())).takeIf { gaveUp > 0 },
                ).joinToString(", ")
                Text(line, color = c.textDim, style = KitType.Dim, modifier = Modifier.padding(start = 12.dp + KitSpace.Gap))
            }
        }
        // Where it's going, the most first, and where it'll change on the way.
        if (s.going.isNotEmpty()) {
            Head(stringResource(Res.string.head_going))
            val anywhere = stringResource(Res.string.anywhere)
            // Why one can't be got to, from the latest view built with the network, for its first few destinations.
            val why = LocalNetwork.current?.stations?.firstOrNull { it.id == s.id }?.going.orEmpty().associate { it.dest to it.why }
            val names = s.going.take(GOING_SHOWN).map { g ->
                GoingCell(
                    if (g.dest < 0) anywhere else stationName(view, g.dest),
                    if (g.via >= 0) stringResource(Res.string.changing_at, stationName(view, g.via)) else null,
                    (0 until Cargo.MAX).filter { g.counts[it] > 0 }.map { it to g.counts[it] },
                    if (g.dest >= 0) whyText(why[g.dest] ?: -1) else null,
                )
            }
            Grid(names) { PlaceCell(it.name, it.amounts, it.via, it.why) }
        }
        if (handling != null) HandlingSection(view, s, handling, flows, funds, me, send)
        s.charger?.let { ch ->
            if (ch.built || s.owner == me) {
                if (handling == null) Head(stringResource(Res.string.head_equipment), Glyph.Shed)
                ActionRow(Glyph.Shed, stringResource(Res.string.equipment_line, stringResource(Res.string.charger), money(ch.upkeep)), dim = !ch.built, end = {
                    if (s.owner == me) {
                        if (ch.built) ActionButton(stringResource(Res.string.remove)) { send(Command.Equip(me, s.id, Equipment.CHARGER, remove = true)) }
                        else ActionButton(stringResource(Res.string.build_for, money(ch.cost)), enabled = funds >= ch.cost, strong = true) { send(Command.Equip(me, s.id, Equipment.CHARGER)) }
                    } else GlyphIcon(Glyph.Tick, c.good, Modifier.size(22.dp))
                })
            }
        }
        // Everything with a stop here, each tapped to open it.
        Head(stringResource(Res.string.head_calling))
        fun calls(orders: IntArray) = orders.any { Order.station(it) == s.id }
        val hereNow = stringResource(Res.string.here_now)
        var any = false
        for (t in view.trains) if (calls(t.orders)) key("t", t.id) {
            any = true
            val e = roster[t.engine]
            val w = roster[t.wagon]
            CallingRow(
                trainName(t), e,
                trainFormationName(roster, t),
                t.load.sum(), t.capacity.sum(), if (t.at == s.id) hereNow else null,
            ) { onVehicle(VehicleKind.ENGINE, t.id) }
        }
        for (v in view.roadVehicles) if (calls(v.orders)) key("r", v.id) {
            any = true
            val e = roster[v.model]
            CallingRow(roadVehicleName(v), e, e.name(lang), v.load, v.capacity, if (v.at == s.id) hereNow else null) { onVehicle(VehicleKind.ROAD, v.id) }
        }
        for (v in view.ships) if (calls(v.orders)) key("s", v.id) {
            any = true
            val e = roster[v.model]
            CallingRow(shipName(v), e, e.name(lang), v.load, v.capacity, if (v.at == s.id) hereNow else null) { onVehicle(VehicleKind.SHIP, v.id) }
        }
        for (v in view.aircraft) if (calls(v.orders)) key("a", v.id) {
            any = true
            val e = roster[v.model]
            CallingRow(aircraftName(v), e, e.name(lang), v.load, v.capacity, if (v.at == s.id) hereNow else null) { onVehicle(VehicleKind.AIRCRAFT, v.id) }
        }
        if (!any) Text(stringResource(Res.string.nothing_calls), color = c.textDim, style = KitType.Body)
    }
}

/** A vehicle that calls at a station: its picture, [name], [model], what it has on board and [here] if it's there now. */
@Composable
private fun CallingRow(name: String, e: Engine, model: String, load: Int, capacity: Int, here: String?, onClick: () -> Unit) {
    PictureRow(
        name, picture = { VehiclePicture(e, it) }, titleEnd = here,
        line = model, lineEnd = if (capacity > 0) stringResource(Res.string.load_of, load, capacity) else null,
        onClick = onClick,
    )
}

private class GoingCell(val name: String, val via: String?, val amounts: List<Pair<Int, Int>>, val why: String?)

/** How many destinations a station's card lists, two to a row. */
private const val GOING_SHOWN = 8

/** A station's handling rate a day for each class by the ways of travel that call there, its equipment to build or remove, and last month's flows from it. */
@Composable
private fun HandlingSection(view: WorldView, s: StationView, h: HandlingView, flows: List<FlowView>, funds: Long, me: Int, send: (Command) -> Unit) {
    val c = LocalColors.current
    fun calls(orders: IntArray) = orders.any { Order.station(it) == s.id }
    val modes = listOf(
        Handling.RAIL to view.trains.any { calls(it.orders) },
        Handling.ROAD to view.roadVehicles.any { calls(it.orders) },
        Handling.WATER to view.ships.any { calls(it.orders) },
        Handling.AIR to view.aircraft.any { calls(it.orders) },
    ).filter { it.second }.map { it.first }.ifEmpty { listOf(if (s.platforms > 0) Handling.RAIL else Handling.ROAD) }
    val modeNames = stringArrayResource(Res.array.handling_modes)
    val classes = stringArrayResource(Res.array.handling_classes)
    Head(stringResource(Res.string.head_handling), Glyph.Speed)
    for (k in 0 until Handling.COUNT) {
        val value = modes.map { m ->
            val rate = Numbers.grouped(h.rates[m][k].toLong())
            if (modes.size > 1) stringResource(Res.string.mode_rate, modeNames.getOrElse(m) { "" }, rate) else rate
        }.joinToString(", ")
        InfoRow(classes.getOrElse(k) { "" }, value)
    }
    Head(stringResource(Res.string.head_equipment), Glyph.Shed)
    val names = stringArrayResource(Res.array.equipment_names)
    val mine = s.owner == me
    for (part in 0 until Equipment.COUNT) {
        val has = Equipment.has(h.equipment, part)
        if (!has && !mine) continue
        ActionRow(Glyph.Shed, stringResource(Res.string.equipment_line, names.getOrElse(part) { "" }, money(h.upkeep[part])), dim = !has, end = {
            if (mine) {
                if (has) ActionButton(stringResource(Res.string.remove)) { send(Command.Equip(me, s.id, part, remove = true)) }
                else ActionButton(stringResource(Res.string.build_for, money(h.cost[part])), enabled = funds >= h.cost[part], strong = true) { send(Command.Equip(me, s.id, part)) }
            } else GlyphIcon(Glyph.Tick, c.good, Modifier.size(22.dp))
        })
    }
    if (flows.isNotEmpty()) {
        Head(stringResource(Res.string.head_flows), Glyph.Up)
        val comma = languageCode() == "fr"
        for (f in flows.take(FLOWS_SHOWN)) {
            CargoRow(f.cargo, stringResource(Res.string.flow_to, Numbers.grouped(f.units.toLong()), stationName(view, f.to)))
            val tenths = ((f.tripTicks * 10 + Calendar.TICKS_PER_DAY / 2) / Calendar.TICKS_PER_DAY).toInt()
            val whole = (f.costCents / 100).toString() + (if (comma) "," else ".") + (f.costCents % 100).toString().padStart(2, '0')
            Text(
                stringResource(Res.string.flow_line, "${tenths / 10}${if (comma) "," else "."}${tenths % 10}", f.handlingShare, stringResource(Res.string.money_cents, whole)),
                color = c.textDim, style = KitType.Dim, modifier = Modifier.padding(start = 12.dp + KitSpace.Gap),
            )
        }
    }
}

/** How many flows a station's card lists. */
private const val FLOWS_SHOWN = 6
