package com.rm.interchange.sim

/*
 * Upgrade rules. A company's rule turns its vehicles of one model into another at their next service, in one
 * group or the whole fleet, a group's rule before the fleet's for the same model. For trains a rule's models are
 * both engines or both cars, and every car of that model in a train changes. It happens where autorenew would, in
 * place of it that visit, for vehicles with autorenew on for them and the company's upgrade switch on, and costs
 * what replacing does if the company keeps its reserve ([Company.renewFloor]) after. A vehicle a rule can't upgrade
 * keeps the reason in [Carrier.upgradeWhy], with news once till the reason changes.
 */

/** Vehicles of [kind] (a [Command.CopyVehicle] kind) of model [from] in [group], or -1 for the whole fleet, become [to]. */
data class UpgradeRule(val kind: Int, val from: Int, val to: Int, val group: Int = -1)

/** The most upgrade rules a company has. */
const val UPGRADE_RULES_MOST = 64

/** Why a rule can't upgrade a vehicle, or a model can't replace it. Ships have no size check, so there's none for them. */
object UpgradeWhy {
    /** No rule applies, or upgrades are off for it. */
    const val NONE = -1
    const val OK = 0
    /** The new model isn't on sale, or is the wrong kind. */
    const val NOT_ON_SALE = 1
    /** The company can't pay and keep its reserve, or for a replace by hand can't pay. */
    const val NO_MONEY = 2
    /** An electric engine with its depot, or a station in its orders, without wires. */
    const val NO_WIRES = 3
    /** The new train is longer than the shortest platform in its orders, which the old one fitted. */
    const val TOO_LONG = 4
    /** An airport in its orders, or its hangar's, doesn't take the new model. */
    const val AIRPORT = 5
    /** A streetcar to a bus or a bus to a streetcar. */
    const val TRAM = 6
    const val MOST = TRAM
}

/** Upgrade rules sorted the way they're kept: by kind, group, then from model. */
private val ruleOrder = compareBy<UpgradeRule>({ it.kind }, { it.group }, { it.from })

/** Whether [e] is a model a rule of [kind] can name. */
private fun ruleModel(kind: Int, e: Engine) = when (kind) {
    Command.CopyVehicle.TRAIN -> e.kind == VehicleKind.ENGINE || e.kind == VehicleKind.WAGON
    Command.CopyVehicle.ROAD -> e.kind == VehicleKind.ROAD
    Command.CopyVehicle.SHIP -> e.kind == VehicleKind.SHIP
    Command.CopyVehicle.AIRCRAFT -> e.kind == VehicleKind.AIRCRAFT
    else -> false
}

/** Whether [rule] names models that fit it and a group of [c]'s that's there; on sale isn't asked. */
internal fun World.ruleFits(c: Company, rule: UpgradeRule): Boolean {
    val from = roster.getOrNull(rule.from) ?: return false
    val to = roster.getOrNull(rule.to) ?: return false
    return rule.kind in 0..3 && rule.from != rule.to && ruleModel(rule.kind, from) && to.kind == from.kind &&
        (rule.group == -1 || c.groups[rule.kind].any { it.id == rule.group })
}

/** The model [c]'s rules turn [model] into for a vehicle of [kind] in [group], or -1. */
internal fun Company.upgradeTo(kind: Int, group: Int, model: Int): Int {
    var to = -1
    for (r in upgrades) {
        if (r.kind != kind || r.from != model) continue
        if (r.group >= 0 && r.group == group) return r.to
        if (r.group == -1) to = r.to
    }
    return to
}

private fun wrecked(v: Carrier) = when (v) {
    is Train -> v.wreckedFor > 0
    is RoadVehicle -> v.wreckedFor > 0
    is Aircraft -> v.wreckedFor > 0
    else -> false
}

/** [v]'s company, if upgrades can apply to it at all. Rivals don't use rules. */
private fun World.upgrading(v: Carrier): Company? {
    val c = companies.getOrNull(v.owner) ?: return null
    if (!c.upgrade || c.gone || c.upgrades.isEmpty() || v.renewOff || wrecked(v) || !human(v.owner)) return null
    return c
}

/** [t]'s cars with [c]'s rules applied, or null if no rule changes any. A car keeps its cargo if the new one carries it. */
private fun World.upgradedCars(c: Company, t: Train): List<TrainCar>? {
    var changed = false
    val cars = t.formation.map { car ->
        val to = c.upgradeTo(Command.CopyVehicle.TRAIN, t.group, car.model)
        val e = roster.getOrNull(to)
        if (e == null) car else {
            changed = true
            TrainCar(to, if (car.cargo == -1 || car.cargo in e.cargoes) car.cargo else -1)
        }
    }
    return if (changed) cars else null
}

/** The model a rule would give [v], the first car's a rule changes for a train, or -1. */
fun World.upgradeTo(v: Carrier): Int {
    val c = upgrading(v) ?: return -1
    val kind = kindOf(v)
    return when (v) {
        is Train -> v.formation.firstNotNullOfOrNull { car -> c.upgradeTo(kind, v.group, car.model).takeIf { it >= 0 } } ?: -1
        is RoadVehicle -> c.upgradeTo(kind, v.group, v.model)
        is Ship -> c.upgradeTo(kind, v.group, v.model)
        is Aircraft -> c.upgradeTo(kind, v.group, v.model)
        else -> -1
    }
}

/** The stations in [v]'s orders, each once, in order. */
private fun orderStations(v: Carrier): List<Int> {
    val out = ArrayList<Int>()
    for (o in v.orders) {
        val s = Order.station(o)
        if (s >= 0 && s !in out) out += s
    }
    return out
}

/** The way platform tile [i]'s track runs. */
private fun World.platformAxis(i: Int) = if (track[i].toInt() and Track.X != 0) Track.X else Track.Y

/** Whether any of station [s]'s platforms has wires over it. */
private fun World.stationWired(s: Station) = s.tiles.any { wires[it].toInt() and platformAxis(it) != 0 }

/** The shortest platform at station [s], in the trains' length units, or [Int.MAX_VALUE] with none. */
private fun World.shortestPlatform(s: Station): Int {
    var least = Int.MAX_VALUE
    for (i in s.tiles) {
        val axis = platformAxis(i)
        val dx = if (axis == Track.X) 1 else 0
        val dy = 1 - dx
        // Counted from the platform's first tile only, so each run once.
        val bx = i % width - dx
        val by = i / width - dy
        if (inside(bx, by) && stationTiles[index(bx, by)] == s.id && platformAxis(index(bx, by)) == axis) continue
        var n = 0
        var x = i % width
        var y = i / width
        while (inside(x, y) && stationTiles[index(x, y)] == s.id && platformAxis(index(x, y)) == axis && n <= MAX_STATION) {
            n++
            x += dx
            y += dy
        }
        least = minOf(least, n * 16 * FIX)
    }
    return least
}

/** What [trainWhy] asks of where [t] goes: wires over its depot and its stations, and its shortest platform. */
internal class TrainPlaces(val wired: Boolean, val shortest: Int)

/** Its depot is the one it's in, or its home; some platform of each station in its orders, waypoints included, needs wires. */
internal fun World.trainPlaces(t: Train): TrainPlaces {
    val stations = orderStations(t).mapNotNull { stations.getOrNull(it) }.filter { it.tiles.isNotEmpty() }
    val d = depots.getOrNull(if (t.inDepot) t.shedIn else t.depot)
    val wired = d != null && depotWired(d) && stations.all { stationWired(it) }
    return TrainPlaces(wired, stations.filter { !it.waypoint }.minOfOrNull { shortestPlatform(it) } ?: Int.MAX_VALUE)
}

/**
 * Why [t] can't become a train of [cars]: new models on sale, wires for an electric engine as [trainPlaces] has
 * them, and still fitting the shortest platform in its orders if it did. Money isn't asked.
 */
internal fun World.trainWhy(t: Train, cars: List<TrainCar>, places: TrainPlaces = trainPlaces(t)): Int {
    for (car in cars) {
        val e = roster.getOrNull(car.model) ?: return UpgradeWhy.NOT_ON_SALE
        if (t.formation.none { it.model == car.model } && !onSale(e)) return UpgradeWhy.NOT_ON_SALE
    }
    if (!places.wired && cars.any { roster[it.model].pulls && roster[it.model].traction == Traction.ELECTRIC }) return UpgradeWhy.NO_WIRES
    val length = formationModels(roster, cars).sumOf { roster[it].length } * FIX
    if (length > places.shortest && t.length <= places.shortest) return UpgradeWhy.TOO_LONG
    return UpgradeWhy.OK
}

/** Why [v] can't become model [e]: on sale and of its kind, and a streetcar for a streetcar. */
internal fun World.roadWhy(v: RoadVehicle, e: Engine): Int = when {
    e.kind != VehicleKind.ROAD || !onSale(e) -> UpgradeWhy.NOT_ON_SALE
    e.tram != v.info.tram -> UpgradeWhy.TRAM
    else -> UpgradeWhy.OK
}

internal fun World.shipWhy(e: Engine): Int = if (e.kind != VehicleKind.SHIP || !onSale(e)) UpgradeWhy.NOT_ON_SALE else UpgradeWhy.OK

/** Why [v] can't become model [e]: on sale and of its kind, taken by its hangar's airport and every airport in its orders. */
internal fun World.aircraftWhy(v: Aircraft, e: Engine): Int {
    if (e.kind != VehicleKind.AIRCRAFT || !onSale(e)) return UpgradeWhy.NOT_ON_SALE
    val home = if (v.inDepot) hangarAirport(v) else airports.firstOrNull { it != null && it.depot == v.depot }
    if (home != null && !home.serves(e)) return UpgradeWhy.AIRPORT
    for (s in orderStations(v)) if (airportOf(s)?.serves(e) == false) return UpgradeWhy.AIRPORT
    return UpgradeWhy.OK
}

/** What making [t] a train of [cars] costs: the new train's price less what the old one fetches, car by car. */
internal fun World.trainSwapCost(t: Train, cars: List<TrainCar>) =
    price(formationModels(roster, cars).sumOf { roster[it].cost }) - value(t)

internal fun World.craftSwapCost(price: Long, built: Int, life: Int, e: Engine) = price(e.cost) - craftValue(price, built, life)

/** [why], or [UpgradeWhy.NO_MONEY] if it's OK but [c] can't pay [cost] and keep its reserve. */
private fun reserveWhy(c: Company, why: Int, cost: Long) =
    if (why == UpgradeWhy.OK && c.money - cost < c.renewFloor) UpgradeWhy.NO_MONEY else why

/** Why [v] can't be upgraded by its company's rules now, [UpgradeWhy.OK] if it can, or [UpgradeWhy.NONE] if none applies. */
internal fun World.upgradeState(v: Carrier): Int {
    val c = upgrading(v) ?: return UpgradeWhy.NONE
    val kind = kindOf(v)
    return when (v) {
        is Train -> {
            val cars = upgradedCars(c, v) ?: return UpgradeWhy.NONE
            reserveWhy(c, trainWhy(v, cars), trainSwapCost(v, cars))
        }
        is RoadVehicle -> {
            val e = roster.getOrNull(c.upgradeTo(kind, v.group, v.model)) ?: return UpgradeWhy.NONE
            reserveWhy(c, roadWhy(v, e), price(e.cost) - roadValue(v))
        }
        is Ship -> {
            val e = roster.getOrNull(c.upgradeTo(kind, v.group, v.model)) ?: return UpgradeWhy.NONE
            reserveWhy(c, shipWhy(e), craftSwapCost(v.price, v.built, v.info.life, e))
        }
        is Aircraft -> {
            val e = roster.getOrNull(c.upgradeTo(kind, v.group, v.model)) ?: return UpgradeWhy.NONE
            reserveWhy(c, aircraftWhy(v, e), craftSwapCost(v.price, v.built, v.info.life, e))
        }
        else -> UpgradeWhy.NONE
    }
}

/** Why [v] can't be upgraded with [rule] alone, money and reserve included; [UpgradeWhy.NONE] if the rule doesn't fit it. */
fun World.upgradeCheck(v: Carrier, rule: UpgradeRule): Int {
    val c = companies.getOrNull(v.owner) ?: return UpgradeWhy.NONE
    if (rule.kind != kindOf(v)) return UpgradeWhy.NONE
    val e = roster.getOrNull(rule.to) ?: return UpgradeWhy.NOT_ON_SALE
    return when (v) {
        is Train -> {
            if (v.formation.none { it.model == rule.from }) return UpgradeWhy.NONE
            val cars = v.formation.map { if (it.model == rule.from) TrainCar(rule.to, if (it.cargo == -1 || it.cargo in e.cargoes) it.cargo else -1) else it }
            reserveWhy(c, trainWhy(v, cars), trainSwapCost(v, cars))
        }
        is RoadVehicle -> if (v.model != rule.from) UpgradeWhy.NONE else reserveWhy(c, roadWhy(v, e), price(e.cost) - roadValue(v))
        is Ship -> if (v.model != rule.from) UpgradeWhy.NONE else reserveWhy(c, shipWhy(e), craftSwapCost(v.price, v.built, v.info.life, e))
        is Aircraft -> if (v.model != rule.from) UpgradeWhy.NONE else reserveWhy(c, aircraftWhy(v, e), craftSwapCost(v.price, v.built, v.info.life, e))
        else -> UpgradeWhy.NONE
    }
}

/** Keeps [why] on [v], news once if it's a reason it can't be upgraded and [tell], till the reason changes. */
private fun World.noteUpgrade(v: Carrier, why: Int, tell: Boolean) {
    if (why != v.upgradeWhy) {
        v.upgradeWhy = why
        v.upgradeTold = false
    }
    if (!tell || why <= UpgradeWhy.OK || v.upgradeTold) return
    v.upgradeTold = true
    tell(News.UPGRADE_FAILED, kindOf(v) shl Autorenew.KIND_SHIFT or v.id)
}

/** Works out [upgradeState] again for [v], without news. */
internal fun World.refreshUpgrade(v: Carrier) = noteUpgrade(v, upgradeState(v), false)

/** Works out [upgradeState] again for [company]'s vehicles of [kind], or all kinds with -1, without news. */
internal fun World.refreshUpgrades(company: Int, kind: Int = -1) {
    for (k in 0..3) {
        if (kind >= 0 && k != kind) continue
        for (v in groupVehicles(k)) if (v != null && v.owner == company) noteUpgrade(v, upgradeState(v), false)
    }
}

/**
 * [v], in a depot for a service: if a rule upgrades it, it's put down to be rebuilt at the end of the tick, when
 * nothing else holds it, and true, so it isn't renewed this visit. Else the reason is kept, with news.
 */
internal fun World.upgradeAtService(v: Carrier): Boolean {
    val why = upgradeState(v)
    noteUpgrade(v, why, true)
    if (why != UpgradeWhy.OK) return false
    upgradesDue += kindOf(v).toLong() shl 32 or v.id.toLong()
    return true
}

/** Rebuilds the vehicles [upgradeAtService] put down this tick, in the order they came in, if still in a depot and paid for. */
internal fun World.upgradeDue() {
    if (upgradesDue.isEmpty()) return
    val due = upgradesDue.toLongArray()
    upgradesDue.clear()
    for (k in due) {
        val v = groupVehicles((k shr 32).toInt()).getOrNull(k.toInt()) ?: continue
        val c = companies.getOrNull(v.owner) ?: continue
        val why = upgradeState(v)
        noteUpgrade(v, why, true)
        if (why != UpgradeWhy.OK) continue
        val made: Carrier = when (v) {
            is Train -> {
                if (!v.inDepot) continue
                val cars = upgradedCars(c, v) ?: continue
                val cost = trainSwapCost(v, cars)
                rebuildTrain(v, cars).also { trains[v.id] = it; spend(c, Books.NEW_TRAINS, cost) }
            }
            is RoadVehicle -> {
                if (!v.inDepot) continue
                val e = roster[c.upgradeTo(Command.CopyVehicle.ROAD, v.group, v.model)]
                val cost = price(e.cost) - roadValue(v)
                rebuildRoadVehicle(v, e).also { roadVehicles[v.id] = it; spend(c, Books.NEW_TRAINS, cost) }
            }
            is Ship -> {
                if (!v.inDepot) continue
                val e = roster[c.upgradeTo(Command.CopyVehicle.SHIP, v.group, v.model)]
                val cost = craftSwapCost(v.price, v.built, v.info.life, e)
                rebuildShip(v, e).also { ships[v.id] = it; spend(c, Books.NEW_TRAINS, cost) }
            }
            is Aircraft -> {
                if (!v.inDepot) continue
                val e = roster[c.upgradeTo(Command.CopyVehicle.AIRCRAFT, v.group, v.model)]
                val cost = craftSwapCost(v.price, v.built, v.info.life, e)
                rebuildAircraft(v, e).also { aircraft[v.id] = it; spend(c, Books.NEW_TRAINS, cost) }
            }
            else -> continue
        }
        made.renewFailed = false
        made.renewAsked = NEVER_ASKED
        noteUpgrade(made, upgradeState(made), false)
        linksDirty = true
    }
}

/** Adds, changes or removes one of [company]'s rules as [c] says, or turns its upgrades on or off. */
internal fun World.upgradeCommand(company: Company, c: Command.Upgrade) {
    val rules = company.upgrades
    val rule = UpgradeRule(c.kind, c.from, c.to, c.group)
    val at = rules.indexOfFirst { it.kind == c.kind && it.group == c.group && it.from == c.from }
    if (c.remove) {
        if (at < 0) return
        rules.removeAt(at)
    } else {
        if (!ruleFits(company, rule) || !onSale(roster[rule.to])) return
        if (at >= 0) rules[at] = rule
        else {
            if (rules.size >= UPGRADE_RULES_MOST) return
            // The switch comes on with the first rule.
            if (rules.isEmpty()) company.upgrade = true
            rules += rule
            rules.sortWith(ruleOrder)
        }
    }
    refreshUpgrades(company.id, c.kind)
}

internal fun World.setUpgrade(company: Company, on: Boolean) {
    company.upgrade = on
    refreshUpgrades(company.id)
}

/** [rules] as a save has them, kept if they still fit [c], sorted, the first of each kind, group and model, at most [UPGRADE_RULES_MOST]. */
internal fun World.cleanRules(c: Company, rules: List<UpgradeRule>): List<UpgradeRule> {
    val out = ArrayList<UpgradeRule>()
    for (r in rules.filter { ruleFits(c, it) }.sortedWith(ruleOrder)) {
        if (out.size >= UPGRADE_RULES_MOST) break
        if (out.none { it.kind == r.kind && it.group == r.group && it.from == r.from }) out += r
    }
    return out
}

/** An upgrade rule as the screen shows it, with its models' names by language and its group's name, empty for the fleet. */
class UpgradeView(val kind: Int, val from: Int, val to: Int, val group: Int, val fromNames: Map<String, String>, val toNames: Map<String, String>, val groupName: String)

internal fun World.upgradeViews(company: Int): List<UpgradeView> {
    val c = companies.getOrNull(company) ?: return emptyList()
    return c.upgrades.map { r ->
        UpgradeView(r.kind, r.from, r.to, r.group, roster[r.from].names, roster[r.to].names, c.groups[r.kind].firstOrNull { it.id == r.group }?.name ?: "")
    }
}

/**
 * Why each model on sale that [v] could be replaced with by hand can't, money aside: model id to an [UpgradeWhy],
 * only those that fail. For the player's own vehicles in a depot, else empty.
 */
internal fun World.replaceWhy(v: Carrier, company: Int): Map<Int, Int> {
    // Ships have no checks.
    if (v.owner != company || !when (v) {
        is Train -> v.inDepot
        is RoadVehicle -> v.inDepot
        is Aircraft -> v.inDepot
        else -> false
    }) return emptyMap()
    val places = if (v is Train) trainPlaces(v) else null
    val out = HashMap<Int, Int>()
    for (e in roster.engines) {
        if (!onSale(e)) continue
        val why = when (v) {
            is Train -> if (e.kind != VehicleKind.ENGINE || e.id == v.engine) continue else trainWhy(v, leadSwapped(v, e.id), places!!)
            is RoadVehicle -> if (e.kind != VehicleKind.ROAD || e.id == v.model) continue else roadWhy(v, e)
            is Aircraft -> if (e.kind != VehicleKind.AIRCRAFT || e.id == v.model) continue else aircraftWhy(v, e)
            else -> continue
        }
        if (why != UpgradeWhy.OK) out[e.id] = why
    }
    return out
}
