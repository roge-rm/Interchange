package com.rm.interchange.sim

/*
 * Airports and aircraft.
 *
 * An airport is a block of flat land of one of [Airports]' kinds, from a
 * gravel strip to an international airport, with a runway along its length,
 * gates and a hangar; it belongs to a station as platforms do. A seaplane
 * base is a dock on the shore, its runway the water in front. A heliport is
 * a pad.
 *
 * Aircraft fly straight from airport to airport, in 4096ths of a tile, up to
 * their range. Each needs a runway long enough ([Runways]); helicopters land
 * on any airport or pad, bush planes on floats at seaplane bases.
 *
 * On the ground they taxi along a fixed set of points for the airport's kind ([Taxiways]) between the runway, the
 * stands and the hangar door, one at a time on the runway.
 *
 * The weather rules the air: storms ground small aircraft first and then
 * everyone, fog closes airports without landing aids, and icing keeps
 * aircraft at the gate until airports have de-icing. Bush planes go on floats
 * in summer and skis in winter; a seaplane base is shut while its lake is
 * freezing or breaking up, and a gravel strip deep in snow takes only skis.
 */

/** The least airport an aircraft needs: a strip, a regional, city or international airport; or a helicopter's pad. */
object Runways {
    const val STRIP = 0
    const val REGIONAL = 1
    const val CITY = 2
    const val INTERNATIONAL = 3
    const val HELI = 4
    val NAMES = listOf("strip", "regional", "city", "international", "heli")
}

object Airports {
    const val STRIP = 0
    const val SEAPLANE = 1
    const val HELIPORT = 2
    const val REGIONAL = 3
    const val CITY = 4
    const val INTERNATIONAL = 5
    const val COUNT = 6

    /** The first year each can be built, its price before inflation, its size along the runway and across, its gates, and the runway class it has. */
    val FROM = intArrayOf(1920, 1920, 1950, 1930, 1950, 1960)
    val COST = longArrayOf(8_000, 5_000, 6_000, 30_000, 80_000, 200_000)
    val LENGTH = intArrayOf(4, 1, 1, 5, 6, 8)
    val WIDTH = intArrayOf(1, 1, 1, 2, 3, 4)
    val GATES = intArrayOf(2, 2, 1, 3, 5, 8)
    val CLASS = intArrayOf(Runways.STRIP, -1, -1, Runways.REGIONAL, Runways.CITY, Runways.INTERNATIONAL)

    /** The kinds a company can build in [year]. */
    fun entries(year: Int) = (0 until COUNT).filter { year >= FROM[it] }
}

class Airport(
    val id: Int,
    var owner: Int,
    val station: Int,
    val kind: Int,
    /** Its corner nearest the map's origin, its runway's way ([Track.X] or [Track.Y]; a seaplane base's, the edge facing its water), and its hangar. */
    val x: Int,
    val y: Int,
    val axis: Int,
    val depot: Int,
) {
    /** Landing aids, for landing in fog; de-icing, for leaving in freezing rain and snow. */
    var ils = false
    var deice = false
    val w get() = if (kind == Airports.SEAPLANE) 1 else if (axis == Track.X) Airports.LENGTH[kind] else Airports.WIDTH[kind]
    val h get() = if (kind == Airports.SEAPLANE) 1 else if (axis == Track.X) Airports.WIDTH[kind] else Airports.LENGTH[kind]
}

/** An airport's tiles, row by row. */
fun Airport.tiles(world: World) = (y until y + h).flatMap { ty -> (x until x + w).map { tx -> world.index(tx, ty) } }

/** Floats, skis or wheels under a bush plane. */
object Gear {
    const val WHEELS = 0
    const val FLOATS = 1
    const val SKIS = 2
}

class Aircraft(
    val roster: Roster,
    override val id: Int,
    override var owner: Int,
    val model: Int,
    var depot: Int,
    var cargoChoice: Int = -1,
) : Carrier {
    var name = ""

    /** Its number in its company's aircraft, from 1. Each company counts its own. */
    var number = 0
    override var group = -1
    override var shared = -1
    override var gap = 0
    override var departed = 0L
    override var leftFrom = -1
    override var leftAt = -1L
    override var heldTicks = 0
    override var orderLeft = -1
    override var orderLeftAt = -1L
    override var orderReachedAt = -1L
    override var late = 0
    override var renewAsked = NEVER_ASKED
    override var renewFailed = false
    override var renewOff = false
    override var upgradeWhy = UpgradeWhy.NONE
    override var upgradeTold = false
    val info get() = roster[model]
    var inDepot = true
    var running = false
    override var waiting = 0
    /** Where it is, in 4096ths of a tile; how high, in 16ths of a step; the way it faces, in 256ths of a turn from along x towards y. */
    var x = 0
    var y = 0
    var altitude = 0
    var heading = 0
    var speed = 0
    /** The way it lands, in 256ths, once it's lining up on a runway. */
    var landing = 0
    /** A [Flight] state, the airport it's at or last left, and how long it's been holding. */
    var state = Flight.GATE
    var at = -1
    // At a gate, -1 marks a retry or diversion rather than a station call; taxiing, the ticks it has waited for another.
    var holding = 0
    /**
     * Taxiing: its way, from and to [Taxiways] places packed as from * 16 + to, plus [Taxiways.FAR_WAY] out to an exit
     * to take off from it the way of [Taxiways.END1]; and the point of it it last passed.
     */
    var route = -1
    var node = 0
    /** The stand it has at airport [at], landing, parked or taxiing to or from it; -1 for none. */
    var stand = -1
    var gear = Gear.WHEELS
    var seasonalGear = true
    override val cargo = Array(Cargo.COUNT) { ArrayList<Packet>() }
    override var profitThisYear = 0L
    var profitLastYear = 0L
    override val orders = ArrayList<Int>()
    override var order = 0
    var toDepot = false
    var built = 0
    var serviced = 0
    var reliability = 8500
    var breakdowns = 0
    var brokenFor = 0
    var servicing = false
    var service = true
    /** A wreck on the runway of airport [at], with this many ticks left before it's cleared away; 0 for one that's whole. */
    var wreckedFor = 0

    val carries: Int get() = if (cargoChoice in info.cargoes) cargoChoice else info.cargoes.firstOrNull() ?: -1
    override fun capacity(c: Int) = if (c == carries) info.capacity else 0
    override val topSpeed get() = info.speed
    val maxSpeed get() = info.speed * 4096 * 16 / 2700
    val price get() = info.cost
    val heli get() = info.runway == Runways.HELI
    val flying get() = state == Flight.TAKEOFF && altitude > 0 || state == Flight.CRUISE || state == Flight.HOLD || state == Flight.LANDING && altitude > 0
    val taxiing get() = state == Flight.TAXI_OUT || state == Flight.TAXI_IN

    /** Where it's heading: a station id, -1 for its hangar, or -2 for nowhere. */
    val target: Int get() = when {
        toDepot -> -1
        orders.isEmpty() -> -2
        else -> Order.station(orders[order % orders.size])
    }
}

object Flight {
    const val GATE = 0
    const val TAKEOFF = 1
    const val CRUISE = 2
    const val HOLD = 3
    const val LANDING = 4
    /** Taxiing to the runway to take off; and to a stand or the hangar door. */
    const val TAXI_OUT = 5
    const val TAXI_IN = 6
}

/** Whether aircraft [v] can use airport [a] at all. */
fun Airport.serves(v: Engine): Boolean = when {
    v.runway == Runways.HELI -> kind != Airports.SEAPLANE
    kind == Airports.SEAPLANE -> v.floats
    kind == Airports.HELIPORT -> false
    else -> Airports.CLASS[kind] >= v.runway
}

/** The airport of station [id], or null. */
fun World.airportOf(id: Int): Airport? {
    val s = stations.getOrNull(id) ?: return null
    val t = s.aero.firstOrNull() ?: return null
    return airportTiles[t]?.let { airports.getOrNull(it) }
}

/** Where airport [c] would go, its runway's way and its tiles; or why it can't, a [Refusal]. */
private class AirportSite(val refusal: Int, val axis: Int = Track.X, val tiles: List<Int> = emptyList())

private fun World.airportSite(company: Company, c: Command.Airport): AirportSite {
    val kind = c.kind
    if (kind !in 0 until Airports.COUNT || Calendar.year(startYear, day) < Airports.FROM[kind]) return AirportSite(Refusal.NOT_YET)
    val axis: Int
    val tiles: List<Int>
    if (kind == Airports.SEAPLANE) {
        if (!inside(c.x, c.y)) return AirportSite(Refusal.NO_ROOM)
        val i = index(c.x, c.y)
        if (!buildable(i)) return AirportSite(Refusal.IN_THE_WAY)
        // Its water: three tiles of open water straight out from one edge.
        axis = intArrayOf(Track.XM, Track.XP, Track.YM, Track.YP).firstOrNull { e ->
            (1..3).all { k -> val x = c.x + Track.dx(e) * k; val y = c.y + Track.dy(e) * k; inside(x, y) && isWater(x, y) && industryOf[index(x, y)].toInt() == 0 }
        } ?: return AirportSite(Refusal.NO_WATER)
        tiles = listOf(i)
    } else {
        axis = if (c.axis == Track.Y) Track.Y else Track.X
        val w = if (axis == Track.X) Airports.LENGTH[kind] else Airports.WIDTH[kind]
        val h = if (axis == Track.X) Airports.WIDTH[kind] else Airports.LENGTH[kind]
        if (!inside(c.x, c.y) || !inside(c.x + w - 1, c.y + h - 1)) return AirportSite(Refusal.NO_ROOM)
        val level = heightAt(c.x, c.y)
        tiles = (c.y until c.y + h).flatMap { ty -> (c.x until c.x + w).map { tx -> index(tx, ty) } }
        for (i in tiles) {
            if (!isFlat(i % width, i / width) || heightAt(i % width, i / width) != level) return AirportSite(Refusal.NOT_FLAT)
            if (!buildable(i)) return AirportSite(Refusal.IN_THE_WAY)
        }
    }
    if (townRefuses(company, c.x, c.y)) return AirportSite(Refusal.TOWN)
    val cost = price(Airports.COST[kind]) + tiles.sumOf { treeCost(it) }
    if (company.money < cost) return AirportSite(Refusal.MONEY)
    return AirportSite(Refusal.NONE, axis, tiles)
}

/** Why [company] can't build airport [c], a [Refusal]; [Refusal.NONE] if it can. */
fun World.airportRefusal(company: Company, c: Command.Airport): Int = airportSite(company, c).refusal

/** Builds an airport of [c]'s kind with its corner at ([x], [y]), joining a station of the company's within a tile or starting one. */
internal fun World.buildAirport(company: Company, c: Command.Airport) {
    val site = airportSite(company, c)
    if (site.refusal != Refusal.NONE) return
    val kind = c.kind
    val axis = site.axis
    val tiles = site.tiles
    var station: Station? = null
    for (t in tiles) for (y in t / width - 1..t / width + 1) for (x in t % width - 1..t % width + 1) {
        if (station != null || !inside(x, y)) continue
        val n = index(x, y)
        val id = stationTiles[n] ?: roadStops[n] ?: docks[n] ?: continue
        station = stations.getOrNull(id)?.takeIf { it.owner == company.id && !it.waypoint && it.aero.isEmpty() }
    }
    spend(company, Books.CONSTRUCTION, price(Airports.COST[kind]))
    for (t in tiles) fellTrees(company, t)
    val s = station ?: Station(stations.size, company.id, stationName(c.x, c.y), c.x, c.y).also { stations += it }
    val d = Depot(depots.size, company.id, hangarTile(kind, c.x, c.y, axis), 0, Depots.HANGAR).also { it.built = Calendar.year(startYear, day) }
    depots += d
    depotTiles[d.tile] = d.id
    val a = Airport(airports.size, company.id, s.id, kind, c.x, c.y, axis, d.id)
    airports += a
    for (t in tiles) {
        airportTiles[t] = a.id
        owner[t] = company.id.toByte()
        s.aero += t
        touched(t % width, t / width)
    }
    networkChanged()
}

/** Whether land tile [i] has nothing on it an airport can't go over. */
private fun World.buildable(i: Int): Boolean {
    val x = i % width
    val y = i / width
    return !isWater(x, y) && track[i].toInt() == 0 && road[i].toInt() == 0 && house[i].toInt() == 0 && industryOf[i].toInt() == 0 &&
        !stationTiles.containsKey(i) && !depotTiles.containsKey(i) && !canals.contains(i) && !locks.containsKey(i) && !docks.containsKey(i) &&
        !airportTiles.containsKey(i) && !saltDepots.contains(i) && !deck.containsKey(i) && !spanHeads.containsKey(i) &&
        !stationBuildings.containsKey(i)
}

/** Whether an aircraft is at [a] or based there, so the airport stays. */
internal fun World.airportBusy(a: Airport): Boolean = aircraft.any {
    it != null && (it.inDepot && hangarAirport(it)?.id == a.id || !it.inDepot && it.at == a.id && (it.state == Flight.GATE || it.taxiing || it.wreckedFor > 0 || it.state == Flight.LANDING && it.altitude == 0))
}

/** The airport whose hangar [v] is in: where it waited for a stand if that's not its home hangar's, else its home's. */
internal fun World.hangarAirport(v: Aircraft): Airport? =
    airports.getOrNull(v.at)?.takeIf { v.inDepot && depots.getOrNull(it.depot) != null }
        ?: airports.firstOrNull { it != null && it.depot == v.depot }

/** Takes airport [a] down, if no aircraft is at it or based there, and pays scrap for its kind once. */
internal fun World.removeAirport(company: Company, a: Airport) {
    if (airportBusy(a)) return
    scrap(company, Airports.COST[a.kind])
    val s = stations[a.station]
    for (t in a.tiles(this)) {
        airportTiles.remove(t)
        owner[t] = -1
        s?.aero?.remove(t)
        touched(t % width, t / width)
    }
    val hangar = depots.getOrNull(a.depot)
    depotTiles.remove(hangar?.tile ?: -1)
    depots[a.depot] = null
    airports[a.id] = null
    hangar?.let { depotGone(it) }
    if (s != null && s.empty) dropStation(s.id)
    for (v in aircraft) if (v != null && v.at == a.id) v.at = -1
    networkChanged()
}

/** Adds landing aids ([what] 0) or de-icing ([what] 1) to an airport, once they're to be had. */
internal fun World.upgradeAirport(company: Company, c: Command.AirportUpgrade) {
    val a = airports.getOrNull(c.airport) ?: return
    if (a.owner != company.id) return
    val year = Calendar.year(startYear, day)
    when (c.what) {
        0 -> {
            if (a.ils || year < ILS_FROM || Airports.CLASS[a.kind] < Runways.REGIONAL || company.money < price(ILS_COST)) return
            spend(company, Books.CONSTRUCTION, price(ILS_COST))
            a.ils = true
        }
        1 -> {
            if (a.deice || year < DEICE_FROM || a.kind == Airports.SEAPLANE || company.money < price(DEICE_COST)) return
            spend(company, Books.CONSTRUCTION, price(DEICE_COST))
            a.deice = true
        }
    }
}

/** Buys an aircraft in airport hangar [depot], if it can use that airport. */
internal fun World.buyAircraft(company: Company, depot: Depot, e: Engine, cargo: Int) {
    val a = airports.firstOrNull { it != null && it.depot == depot.id } ?: return
    if (!a.serves(e)) return
    val v = Aircraft(roster, aircraft.size, company.id, e.id, depot.id, if (cargo in e.cargoes) cargo else -1)
    v.number = fleetNumber(company.id, aircraft, { it.owner }, { it.number })
    if (company.money < price(v.price)) return
    spend(company, Books.NEW_TRAINS, price(v.price))
    v.built = workDay
    v.serviced = workDay
    v.reliability = e.reliability * 100
    v.at = a.id
    if (e.floats) v.gear = wantedGear(v, a)
    aircraft += v
}

internal fun World.aircraftCommand(company: Company, c: Command.CraftOrder) {
    val v = aircraft.getOrNull(c.vehicle) ?: return
    if (v.owner != company.id || v.wreckedFor > 0) return
    if (c.what != Command.TrainOrder.REPLACE && c.what != Command.TrainOrder.GROUP && c.what != Command.TrainOrder.AUTORENEW) linksDirty = true
    when (c.what) {
        Command.TrainOrder.GROUP -> setVehicleGroup(company, Command.CopyVehicle.AIRCRAFT, v, c.stations.firstOrNull())
        Command.TrainOrder.REPLACE -> replaceAircraft(company, v, c.stations.firstOrNull())
        Command.TrainOrder.REFIT -> {
            val cargo = c.stations.firstOrNull() ?: return
            if (!v.inDepot || cargo !in v.info.cargoes) return
            v.cargoChoice = cargo
            for (c in v.cargo.indices) if (v.capacity(c) == 0) v.cargo[c].clear()
        }
        Command.TrainOrder.START_STOP -> if (v.inDepot) { if (v.running) v.running = false else { v.running = true; outOfHangar(v) } } else v.running = !v.running
        Command.TrainOrder.ORDERS -> {
            val group = aircraft.filterNotNull().filter { v.shared >= 0 && it.shared == v.shared }.ifEmpty { listOf(v) }
            for (g in group) {
                g.orders.clear()
                g.orders += allowedOrders(c.stations, company.id, Depots.HANGAR, depotOrders = false)
                firstOrder(g)
                ordersChanged(g)
            }
        }
        Command.TrainOrder.GAP -> {
            val days = c.stations.firstOrNull() ?: return
            if (days !in 0..Order.MAX_WAIT_DAYS) return
            val group = aircraft.filterNotNull().filter { v.shared >= 0 && it.shared == v.shared }.ifEmpty { listOf(v) }
            for (g in group) g.gap = days
        }
        Command.TrainOrder.SHARE -> {
            val other = aircraft.getOrNull(c.stations.firstOrNull() ?: -1) ?: return
            if (other.owner != company.id || other.id == v.id || other.wreckedFor > 0) return
            if (other.shared < 0) other.shared = freshShared(other)
            val was = v.shared
            v.shared = other.shared
            v.gap = other.gap
            v.orders.clear()
            v.orders += other.orders
            firstOrder(v)
            restartTimetable(v)
            if (was != v.shared) pruneService(Command.CopyVehicle.AIRCRAFT, was)
        }
        Command.TrainOrder.UNSHARE -> {
            val was = v.shared
            v.shared = -1
            restartTimetable(v)
            pruneService(Command.CopyVehicle.AIRCRAFT, was)
        }
        Command.TrainOrder.TO_DEPOT -> if (!v.inDepot) {
            v.toDepot = !v.toDepot
            v.servicing = false
            // Its home hangar gone: the nearest it can use.
            if (v.toDepot && depots.getOrNull(v.depot) == null) nearestHangar(v)?.let { v.depot = it.depot }
        }
        Command.TrainOrder.SELL -> if (v.inDepot) {
            val backup = OrderBackup(ArrayList(v.orders), v.gap)
            earn(company, Books.TRAINS_SOLD, craftValue(v.price, v.built, v.info.life))
            aircraft[v.id] = null
            pruneVehicleGroup(v.owner, Command.CopyVehicle.AIRCRAFT, v.group)
            rememberOrders(company, Command.CopyVehicle.AIRCRAFT, v, backup)
            pruneService(Command.CopyVehicle.AIRCRAFT, v.shared)
        }
        Command.TrainOrder.SERVICE -> v.service = !v.service
        Command.TrainOrder.AUTORENEW -> v.renewOff = !v.renewOff
        Command.TrainOrder.RENEW -> if (v.inDepot) {
            val cost = price(v.price) - craftValue(v.price, v.built, v.info.life)
            if (company.money < cost) return
            spend(company, Books.NEW_TRAINS, cost)
            v.built = workDay
            v.serviced = workDay
            v.reliability = v.info.reliability * 100
        }
        Command.CraftOrder.GEAR -> v.seasonalGear = !v.seasonalGear
    }
}

/**
 * The hangar's airport rolls the aircraft out of the door to a free stand; a helicopter is set straight on one. It
 * stays in while every stand is taken, something is at the door, or the runway the door opens onto is in use.
 */
private fun World.outOfHangar(v: Aircraft) {
    val a = hangarAirport(v) ?: return
    val s = freeStand(a, v)
    if (s < 0) return
    v.at = a.id
    if (v.heli) {
        v.inDepot = false
        v.state = Flight.GATE
        v.stand = s
        v.waiting = 0
        v.altitude = 0
        v.speed = 0
        val p = standPoint(a, s)
        v.x = p[0]; v.y = p[1]
        return
    }
    val route = Taxiways.DOOR * 16 + s
    val pts = taxiRoute(a, route)
    val door = taxiPoint(a, pts, 0)
    if (Taxiways.runwayLeg(pts, 0) && runwayBusy(a, v)) return
    if (aircraft.any { w -> w != null && w !== v && !w.inDepot && w.at == a.id && w.taxiing && near(w, door, (v.info.length + w.info.length) * SHIP_UNIT / 32) }) return
    v.inDepot = false
    v.waiting = 0
    v.altitude = 0
    v.x = door[0]; v.y = door[1]
    val next = taxiPoint(a, pts, 1)
    v.heading = bearing256(next[0] - door[0], next[1] - door[1])
    v.stand = s
    startTaxi(v, Flight.TAXI_IN, route)
}

/** Whether [w] is within [d] of point [p]. */
private fun near(w: Aircraft, p: IntArray, d: Int): Boolean {
    val dx = (w.x - p[0]).toLong()
    val dy = (w.y - p[1]).toLong()
    return dx * dx + dy * dy < d.toLong() * d
}

/**
 * The points aircraft taxi between on each kind of airport, in 16ths of a tile along the runway and across it from the
 * airport's corner (for a seaplane base, along its water from where the runway starts, its middle line 8 across).
 * The runway's middle line is 8 across; a point on it is on the runway, and so is a leg to or from one.
 *
 * Each place is a short spur, its first point where it joins the others: a stand is the point in front of it then the
 * stand, parked nose in; the hangar door the same; a runway end the points out to where it lines up to take off, which
 * is also where a landing rolls out to with no exit before it. Where the end's spur is given only for end 0, end 1
 * mirrors it along the runway. An exit is where a landing turns off partway: on a city or international airport an
 * angled one onto the taxiway for landings going one way, elsewhere a point on the runway for either way.
 * Strips, regional airports and seaplane bases taxi on the runway itself, turning round at its ends; city and
 * international airports have a taxiway, held short of where it turns onto the runway. It runs on the line the
 * map draws down the middle of row 1 (24 across). The map paves and lines the legs off it from these points too.
 */
object Taxiways {
    /** Places on a route: stands are 0 up, then these. */
    const val EXIT0 = 8
    const val DOOR = 13
    const val END0 = 14
    const val END1 = 15
    /** Added to a route out to an exit that takes off the way of [END1]. */
    const val FAR_WAY = 256

    /** Where route [route] starts and ends. */
    fun from(route: Int) = (route shr 4) and 15
    fun to(route: Int) = route and 15

    /** Each kind's stands, as pairs of points: the one in front of it, then the stand. */
    val STANDS = arrayOf(
        intArrayOf(24, 8, 24, 14, 40, 8, 40, 14),
        intArrayOf(4, 8, -4, 4, 4, 8, -4, 12),
        intArrayOf(8, 8, 8, 8),
        intArrayOf(24, 8, 24, 25, 56, 8, 56, 25, 40, 8, 40, 19),
        IntArray(20) { i -> if (i % 4 == 0 || i % 4 == 2) 24 + i / 4 * 16 else if (i % 4 == 1) 24 else 34 },
        IntArray(32) { i -> if (i % 4 == 0 || i % 4 == 2) 8 + i / 4 * 16 else if (i % 4 == 1) 24 else 40 },
    )
    /** The hangar door, the same way, and the hangar's tile along and across. */
    val DOORS = arrayOf(
        intArrayOf(56, 8, 56, 14), intArrayOf(4, 8, -10, 8), intArrayOf(8, 8, 8, 8),
        intArrayOf(8, 8, 8, 21), intArrayOf(8, 24, 8, 35), intArrayOf(16, 24, 16, 50),
    )
    val HANGAR_TILE = arrayOf(intArrayOf(3, 0), intArrayOf(0, 0), intArrayOf(0, 0), intArrayOf(0, 1), intArrayOf(0, 2), intArrayOf(1, 3))
    /** Runway end 0's spur, from where it joins to where it lines up; a seaplane base's end 1 as well. */
    val ENDS = arrayOf(
        intArrayOf(4, 8, 12, 8), intArrayOf(10, 8), IntArray(0),
        intArrayOf(4, 8, 12, 8), intArrayOf(4, 24, 2, 8, 14, 8), intArrayOf(4, 24, 2, 8, 16, 8),
    )
    val SEAPLANE_END1 = intArrayOf(44, 8, 36, 8)
    /** Each kind's exits, from where each joins to where it leaves the runway, [EXIT0] up. */
    val EXITS = arrayOf(
        arrayOf(intArrayOf(24, 8), intArrayOf(40, 8), intArrayOf(56, 8)),
        arrayOf(intArrayOf(20, 8), intArrayOf(28, 8)),
        arrayOf(),
        arrayOf(intArrayOf(24, 8), intArrayOf(40, 8), intArrayOf(56, 8)),
        arrayOf(intArrayOf(48, 24, 32, 8), intArrayOf(72, 24, 56, 8), intArrayOf(48, 24, 64, 8), intArrayOf(24, 24, 40, 8)),
        arrayOf(intArrayOf(56, 24, 40, 8), intArrayOf(88, 24, 72, 8), intArrayOf(72, 24, 88, 8), intArrayOf(40, 24, 56, 8)),
    )

    /** Whether [place] is a stand. */
    fun stand(place: Int) = place < EXIT0

    /** Whether [place] is on the runway: an end or an exit. */
    fun fromRunway(place: Int) = place >= END0 || place in EXIT0 until DOOR

    /** Which way along the runway exit [e] of [kind] is for, +1 or -1, or 0 for either. */
    fun exitWay(kind: Int, e: IntArray) = if (e.size < 4) 0 else if (e[0] > e[2]) 1 else -1

    /** The spur of [place] on an airport of [kind]. */
    fun spur(kind: Int, place: Int): IntArray = when (place) {
        DOOR -> DOORS[kind]
        END0 -> ENDS[kind]
        END1 -> if (kind == Airports.SEAPLANE) SEAPLANE_END1 else ENDS[kind].let { e ->
            val len = Airports.LENGTH[kind] * 16
            IntArray(e.size) { i -> if (i % 2 == 0) len - e[i] else e[i] }
        }
        in EXIT0 until DOOR -> EXITS[kind][place - EXIT0]
        else -> STANDS[kind].copyOfRange(place * 4, place * 4 + 4)
    }

    /** Route [route]'s points, along and across in pairs: [spur] of where it starts backwards, then of where it ends. */
    fun points(kind: Int, route: Int): IntArray {
        val from = spur(kind, from(route))
        val to = spur(kind, to(route))
        val out = ArrayList<Int>(from.size + to.size)
        for (i in from.size / 2 - 1 downTo 0) { out += from[i * 2]; out += from[i * 2 + 1] }
        val same = out.size >= 2 && to.size >= 2 && out[out.size - 2] == to[0] && out[out.size - 1] == to[1]
        for (i in (if (same) 2 else 0) until to.size) out += to[i]
        return out.toIntArray()
    }

    /** Whether leg [i] of [pts], from point [i] to the next, is on the runway. */
    fun runwayLeg(pts: IntArray, i: Int) = pts[i * 2 + 1] == 8 || pts[i * 2 + 3] == 8
}

/** The tile of [kind]'s hangar at corner ([x], [y]) with its runway along [axis]. */
private fun World.hangarTile(kind: Int, x: Int, y: Int, axis: Int): Int {
    if (kind == Airports.SEAPLANE || kind == Airports.HELIPORT) return index(x, y)
    val t = Taxiways.HANGAR_TILE[kind]
    return if (axis == Track.X) index(x + t[0], y + t[1]) else index(x + t[1], y + t[0])
}

/** Route [route]'s points on airport [a], in 16ths along and across. */
internal fun taxiRoute(a: Airport, route: Int) = Taxiways.points(a.kind, route)

/** Point [i] of [pts] on airport [a], in 4096ths. */
internal fun taxiPoint(a: Airport, pts: IntArray, i: Int) = localPoint(a, pts[i * 2], pts[i * 2 + 1])

/** The point [l] along and [c] across airport [a], in 16ths of a tile, in 4096ths. */
internal fun localPoint(a: Airport, l: Int, c: Int): IntArray {
    val u = SHIP_UNIT
    val k = u / 16
    if (a.kind == Airports.SEAPLANE) {
        val dx = Track.dx(a.axis)
        val dy = Track.dy(a.axis)
        // Across is the way round from the water that counts up in x or y; the runway starts a tile out from the dock.
        val cx = kotlin.math.abs(dy)
        val cy = kotlin.math.abs(dx)
        val ox = a.x * u + u / 2 + dx * u - cx * u / 2
        val oy = a.y * u + u / 2 + dy * u - cy * u / 2
        return intArrayOf(ox + dx * l * k + cx * c * k, oy + dy * l * k + cy * c * k)
    }
    return if (a.axis == Track.X) intArrayOf(a.x * u + l * k, a.y * u + c * k) else intArrayOf(a.x * u + c * k, a.y * u + l * k)
}

/** Where stand [s] of airport [a] is, in 4096ths. */
fun World.standPoint(a: Airport, s: Int): IntArray {
    val st = Taxiways.STANDS[a.kind]
    val k = s.coerceIn(0, st.size / 4 - 1) * 4
    return localPoint(a, st[k + 2], st[k + 3])
}

/** The first stand of airport [a] no aircraft but [v] has, landing, parked or taxiing there, or -1. */
internal fun World.freeStand(a: Airport, v: Aircraft): Int {
    for (s in 0 until Taxiways.STANDS[a.kind].size / 4) {
        if (aircraft.none { it != null && it !== v && holdsStand(it, a, s) }) return s
    }
    return -1
}

private fun holdsStand(w: Aircraft, a: Airport, s: Int) = !w.inDepot && w.at == a.id && w.stand == s && w.wreckedFor == 0 &&
    (w.state == Flight.GATE || w.state == Flight.LANDING || w.taxiing)

/**
 * The free stand of [a] for [v] landing there: the one nearest where the runway end it will take off from for its
 * next stop joins, or the first free one for a helicopter or with no next stop. -1 with none free.
 */
private fun World.standFor(a: Airport, v: Aircraft): Int {
    val first = freeStand(a, v)
    if (first < 0 || v.heli || v.toDepot || Order.stops(v.orders) < 2) return first
    val next = airportOf(Order.nextStation(v.orders, v.order + 1))
    val join = Taxiways.spur(a.kind, endFor(a, takeOffTowards(a, next)))[0]
    val st = Taxiways.STANDS[a.kind]
    var best = first
    for (s in first + 1 until st.size / 4) {
        if (kotlin.math.abs(st[s * 4] - join) >= kotlin.math.abs(st[best * 4] - join)) continue
        if (aircraft.none { it != null && it !== v && holdsStand(it, a, s) }) best = s
    }
    return best
}

/** Parks [v] on airport [a]'s first free stand, or in its hangar with none free, to come out when one is. */
private fun World.park(v: Aircraft, a: Airport) {
    v.state = Flight.GATE
    v.route = -1
    v.node = 0
    v.altitude = 0
    v.speed = 0
    v.stand = freeStand(a, v)
    if (v.stand < 0) { v.at = a.id; v.inDepot = true; return }
    val p = standPoint(a, v.stand)
    v.x = p[0]; v.y = p[1]
}

/** Sets [v] off taxiing on [route]. */
private fun startTaxi(v: Aircraft, state: Int, route: Int) {
    v.state = state
    v.route = route
    v.node = 0
    v.holding = 0
    v.speed = 0
}

/** The runway end [v] takes off from on [a] heading [h]: [Taxiways.END0] going the runway's way, else [Taxiways.END1]. */
private fun World.endFor(a: Airport, h: Int): Int {
    val r = runway(a)
    return if (h == bearing256(r[2] - r[0], r[3] - r[1])) Taxiways.END0 else Taxiways.END1
}

/** The way a plane takes off from runway end [end] of [a], in 256ths. */
private fun World.endHeading(a: Airport, end: Int): Int {
    val r = runway(a)
    val way = bearing256(r[2] - r[0], r[3] - r[1])
    return if (end == Taxiways.END0) way else (way + 128) and 255
}

/** Whether [v], taxiing at [a], is on the runway: on a runway leg it has started, or came onto from the runway. */
private fun taxiHoldsRunway(v: Aircraft, a: Airport): Boolean {
    if (v.route < 0) return false
    val pts = taxiRoute(a, v.route)
    val i = v.node
    // At the end of a way out to the runway it is lined up on it.
    if (i >= pts.size / 2 - 1) return Taxiways.fromRunway(Taxiways.to(v.route))
    if (!Taxiways.runwayLeg(pts, i)) return false
    if (i > 0 && Taxiways.runwayLeg(pts, i - 1) || i == 0 && Taxiways.fromRunway(Taxiways.from(v.route))) return true
    val p = taxiPoint(a, pts, i)
    return v.x != p[0] || v.y != p[1]
}

/** Whether [w] has airport [a]'s runway: lining up to land or rolling, taking off, taxiing on it, or wrecked on it. */
internal fun onRunway(w: Aircraft, a: Airport): Boolean {
    if (w.inDepot || w.at != a.id) return false
    if (w.wreckedFor > 0) return true
    if (w.heli) return false
    return when (w.state) {
        Flight.LANDING, Flight.TAKEOFF -> true
        Flight.TAXI_IN, Flight.TAXI_OUT -> taxiHoldsRunway(w, a)
        else -> false
    }
}

/** Whether an aircraft other than [v] has airport [a]'s runway. */
internal fun World.runwayBusy(a: Airport, v: Aircraft) = aircraft.any { it != null && it !== v && onRunway(it, a) }

/**
 * Whether taxiing aircraft [v] waits for another taxiing at [a] just ahead of it the way it's going, ([dx], [dy]):
 * closer than half their lengths together, and not off to the side by more than half that. Two head on, the lower id
 * goes first.
 */
private fun World.taxiBlocked(v: Aircraft, a: Airport, dx: Int, dy: Int): Boolean {
    val len = isqrt(dx.toLong() * dx + dy.toLong() * dy).coerceAtLeast(1L)
    for (w in aircraft) {
        if (w == null || w === v || w.inDepot || w.at != a.id || !w.taxiing) continue
        val ox = (w.x - v.x).toLong()
        val oy = (w.y - v.y).toLong()
        val sep = ((v.info.length + w.info.length) * SHIP_UNIT / 32).toLong()
        val ahead = (ox * dx + oy * dy) / len
        val side = kotlin.math.abs(ox * dy - oy * dx) / len
        if (ahead <= 0 || ahead > sep || side > sep / 2) continue
        if (v.id < w.id && -ox * cos256(w.heading) - oy * sin256(w.heading) > 0) continue
        return true
    }
    return false
}

/**
 * Taxiing a tick at [TAXI_STEP] along its route's legs, its heading turning at [TAXI_TURN] (a turn tighter than a
 * quarter turns on the spot first), backing off a stand, holding short of a runway someone else has, and waiting for
 * one just ahead, for [TAXI_PATIENCE] ticks at most. At the end it lines up to take off, parks, or goes into the hangar.
 */
private fun World.taxi(v: Aircraft) {
    val a = airports.getOrNull(v.at)
    if (a == null) {
        // Its airport's gone: off from where it is.
        v.stand = -1
        v.state = Flight.CRUISE
        return
    }
    if (v.route < 0) { park(v, a); return }
    val pts = taxiRoute(a, v.route)
    val i = v.node
    if (i >= pts.size / 2 - 1) { endTaxi(v, a); return }
    val from = taxiPoint(a, pts, i)
    val to = taxiPoint(a, pts, i + 1)
    val dx = to[0] - from[0]
    val dy = to[1] - from[1]
    if (Taxiways.runwayLeg(pts, i) && !taxiHoldsRunway(v, a) && runwayBusy(a, v)) { v.speed = 0; return }
    // Off a stand it backs out, nose to the stand.
    val back = i == 0 && Taxiways.stand(Taxiways.from(v.route))
    val want = if (back) bearing256(-dx, -dy) else bearing256(dx, dy)
    if (kotlin.math.abs(turnTo(v.heading, want)) > 64) { v.speed = 0; steer(v, want, TAXI_TURN); return }
    if (!taxiHoldsRunway(v, a) && v.holding < TAXI_PATIENCE && taxiBlocked(v, a, dx, dy)) { v.speed = 0; v.holding++; return }
    v.holding = 0
    v.speed = TAXI_STEP * 16
    steer(v, want, TAXI_TURN)
    if (moveTo(v, to[0], to[1], TAXI_STEP) > 0) return
    v.node = i + 1
    // Out of its stand onto the way out, it gives the stand up.
    if (i == 0 && Taxiways.stand(Taxiways.from(v.route))) v.stand = -1
    if (v.node >= pts.size / 2 - 1) endTaxi(v, a)
}

/**
 * At the end of its taxi: lined up, off down the runway (at an exit, turning on the spot to face down it first); at a
 * stand, parked and calling; at the door, into the hangar.
 */
private fun World.endTaxi(v: Aircraft, a: Airport) {
    val to = Taxiways.to(v.route)
    val came = Taxiways.from(v.route)
    if (v.state == Flight.TAXI_OUT && to in Taxiways.EXIT0 until Taxiways.DOOR) {
        val h = endHeading(a, if (v.route >= Taxiways.FAR_WAY) Taxiways.END1 else Taxiways.END0)
        v.speed = 0
        if (v.heading != h) { steer(v, h, TAXI_TURN); return }
        v.route = -1
        v.node = 0
        v.holding = 0
        v.state = Flight.TAKEOFF
        return
    }
    v.route = -1
    v.node = 0
    v.holding = 0
    v.speed = 0
    when {
        to >= Taxiways.END0 -> {
            v.state = Flight.TAKEOFF
            v.heading = endHeading(a, to)
        }
        to == Taxiways.DOOR -> {
            v.state = Flight.GATE
            v.stand = -1
            if (v.target == -1 && a.depot == v.depot) intoHangar(v)
            else {
                // Somewhere to wait for a stand: in the hangar, coming out again when one's free.
                v.at = a.id
                v.inDepot = true
            }
        }
        else -> {
            v.state = Flight.GATE
            val st = Taxiways.STANDS[a.kind]
            val p = localPoint(a, st[to * 4], st[to * 4 + 1])
            val s = standPoint(a, to)
            v.heading = bearing256(s[0] - p[0], s[1] - p[1])
            v.waiting = 0
            if (Taxiways.fromRunway(came)) callHere(v, a)
        }
    }
}

/** Down and rolled out to [end], a runway end or exit of [a]: taxiing to its stand, or to the door for its hangar or with none free. */
private fun World.taxiIn(v: Aircraft, a: Airport, end: Int) {
    val home = v.target == -1 && a.depot == v.depot
    if (!home && v.stand < 0) v.stand = standFor(a, v)
    if (home) v.stand = -1
    startTaxi(v, Flight.TAXI_IN, end * 16 + (if (v.stand >= 0) v.stand else Taxiways.DOOR))
}

/** Off its stand on [a] for the runway end into the wind or towards where it's going, or a small plane for an exit on the way there. */
private fun World.taxiOut(v: Aircraft, a: Airport) {
    if (v.heli || v.stand < 0) { v.stand = -1; depart(v, a); return }
    val end = endFor(a, takeOffHeading(v, a))
    val at = lineUpAt(v, a, v.stand, end)
    startTaxi(v, Flight.TAXI_OUT, v.stand * 16 + at + if (at != end && end == Taxiways.END1) Taxiways.FAR_WAY else 0)
}

/**
 * Where [v] at stand [s] of [a] joins the runway to take off the way of runway end [end]: for a strip-class plane, the
 * exit whose join is nearest the stand's, if nearer than the end's, that is for landings the other way (or either) and
 * leaves at least [PARTWAY_SPARE] hundredths of its [takeOffRoll] of runway ahead; else the end. Airliners use the end.
 */
internal fun World.lineUpAt(v: Aircraft, a: Airport, s: Int, end: Int): Int {
    if (v.heli || v.info.runway != Runways.STRIP) return end
    val kind = a.kind
    val way = if (end == Taxiways.END0) 1 else -1
    val r = runway(a)
    val len = (kotlin.math.abs(r[2] - r[0]) + kotlin.math.abs(r[3] - r[1])) / (SHIP_UNIT / 16)
    val need = takeOffRoll(v, a).toLong() * PARTWAY_SPARE / 100
    val join = Taxiways.spur(kind, s)[0]
    var best = end
    var bestGap = kotlin.math.abs(Taxiways.spur(kind, end)[0] - join)
    for (k in Taxiways.EXITS[kind].indices) {
        val e = Taxiways.EXITS[kind][k]
        if (Taxiways.exitWay(kind, e) == way) continue
        val on = e[e.size - 2]
        val run = if (way > 0) len - on else on
        if (run.toLong() * (SHIP_UNIT / 16) < need) continue
        val gap = kotlin.math.abs(e[0] - join)
        if (gap < bestGap) { best = Taxiways.EXIT0 + k; bestGap = gap }
    }
    return best
}

/** From its stand on [a] to the hangar door; a helicopter straight in. */
private fun World.taxiToHangar(v: Aircraft, a: Airport) {
    if (v.heli || v.stand < 0) { v.stand = -1; intoHangar(v); return }
    startTaxi(v, Flight.TAXI_IN, v.stand * 16 + Taxiways.DOOR)
}

/**
 * Settles aircraft from a save before taxiing (version [version]) onto stands: one at a gate is [park]ed, and one
 * rolling out given a stand to taxi to. Every version: hangars on their hangar's tile.
 */
internal fun World.settleAirports(version: Int) {
    for (a in airports) {
        if (a == null) continue
        val d = depots.getOrNull(a.depot) ?: continue
        val tile = hangarTile(a.kind, a.x, a.y, a.axis)
        if (d.tile == tile) continue
        depotTiles.remove(d.tile)
        depots[d.id] = Depot(d.id, d.owner, tile, d.edge, d.kind).also { it.built = d.built }
        depotTiles[tile] = d.id
    }
    if (version >= 53) return
    for (v in aircraft) {
        if (v == null || v.inDepot || v.wreckedFor > 0) continue
        val a = airports.getOrNull(v.at) ?: continue
        if (v.state == Flight.GATE || v.taxiing) park(v, a)
        else if (v.state == Flight.LANDING && v.altitude == 0 && !v.heli) v.stand = freeStand(a, v)
    }
}

/** Where airport [a]'s runway starts and ends, in 4096ths: along its first row, or out across a seaplane base's water. */
fun World.runway(a: Airport): IntArray {
    val u = SHIP_UNIT
    if (a.kind == Airports.SEAPLANE) {
        val e = a.axis
        val sx = (a.x + Track.dx(e)) * u + u / 2
        val sy = (a.y + Track.dy(e)) * u + u / 2
        return intArrayOf(sx, sy, sx + Track.dx(e) * u * 3, sy + Track.dy(e) * u * 3)
    }
    return if (a.axis == Track.X) intArrayOf(a.x * u, a.y * u + u / 2, (a.x + a.w) * u, a.y * u + u / 2)
    else intArrayOf(a.x * u + u / 2, a.y * u, a.x * u + u / 2, (a.y + a.h) * u)
}

/**
 * Today's weather at an airport, as bits: [FOGGY], [ICING]; worked out once a day and forgotten when the weather moves on.
 * Fog and icing last through the first third of a calendar day's work days, then lift.
 */
private fun World.airSky(a: Airport): Int {
    val lifted = (tick / Calendar.TICKS_PER_DAY) % settings.dayLength >= (settings.dayLength + 2) / 3
    airSkies[a.id]?.let { return if (lifted) 0 else it }
    if (!settings.weather || lifted) return 0
    return airSkies.getOrPut(a.id) {
        val sky = skyAt(a.x, a.y)
        (if (sky.fog) FOGGY else 0) or (if (sky.precipitation > 0 && sky.temperature in -100..10) ICING else 0)
    }
}

/** Wind, in km/h, that grounds small aircraft, and everyone. */
private fun Engine.stormLimit() = if (runway == Runways.STRIP || runway == Runways.HELI || floats) SMALL_STORM else BIG_STORM

/** Why aircraft [v] can't land at airport [a] now: null if it can. */
fun World.cantLand(v: Aircraft, a: Airport): String? =
    cantLandFor(v, a) ?: if (!v.heli && runwayBusy(a, v)) "busy" else null

private fun World.cantLandFor(v: Aircraft, a: Airport): String? {
    if (!a.serves(v.info)) return "runway"
    if (wreckAt(a)) return "wreck"
    if (weather.wind >= v.info.stormLimit()) return "storm"
    if (airSky(a) and FOGGY != 0 && !a.ils) return "fog"
    if (!homeBound(v, a) && freeStand(a, v) < 0) return "gates"
    if (a.kind == Airports.SEAPLANE) {
        val e = a.axis
        val water = index(a.x + Track.dx(e), a.y + Track.dy(e))
        val ice = weather.ice[water].toInt() and 255
        return when {
            v.gear == Gear.FLOATS && ice == 0 -> null
            v.gear == Gear.SKIS && ice >= SKI_ICE -> null
            else -> "ice"
        }
    }
    if (v.info.floats) {
        val snow = a.tiles(this).maxOf { weather.snow[it].toInt() and 255 }
        if (v.gear == Gear.FLOATS) return "gear"
        if (v.gear == Gear.WHEELS && a.kind == Airports.STRIP && snow >= STRIP_SNOW) return "snow"
        if (v.gear == Gear.SKIS && snow < 5) return "gear"
    } else if (a.kind == Airports.STRIP && a.tiles(this).maxOf { weather.snow[it].toInt() and 255 } >= STRIP_SNOW) return "snow"
    return null
}

/** Whether aircraft [v] can leave airport [a] now: not in a storm it can't fly in, and not iced up without de-icing. */
private fun World.mayLeave(v: Aircraft, a: Airport): Boolean {
    if (weather.wind >= v.info.stormLimit()) return false
    if (airSky(a) and ICING != 0 && !a.deice) return false
    return !wreckAt(a)
}

/** The gear a bush plane wants: skis in winter, floats if it calls at a seaplane base, else wheels. */
internal fun World.wantedGear(v: Aircraft, home: Airport? = null): Int {
    if (!v.info.floats) return Gear.WHEELS
    val look = lookToday()
    if (look == Climate.WINTER || look == Climate.LATE_AUTUMN) return Gear.SKIS
    val seaplane = home?.kind == Airports.SEAPLANE || v.orders.any { airportOf(Order.station(it))?.kind == Airports.SEAPLANE }
    return if (seaplane) Gear.FLOATS else Gear.WHEELS
}

/** Moves every aircraft a tick, in order of their ids. */
internal fun World.moveAircraft() {
    for (v in aircraft) {
        if (v == null) continue
        if (v.inDepot) { if (v.running) outOfHangar(v); continue }
        if (v.wreckedFor > 0) {
            if (--v.wreckedFor == 0) {
                aircraft[v.id] = null
                pruneVehicleGroup(v.owner, Command.CopyVehicle.AIRCRAFT, v.group)
                linksDirty = true
            }
            continue
        }
        fly(v)
    }
}

private fun World.fly(v: Aircraft) {
    when (v.state) {
        Flight.GATE -> atGate(v)
        Flight.TAKEOFF -> takeOff(v)
        Flight.CRUISE -> cruise(v)
        Flight.HOLD -> {
            if (v.leftAt >= 0) v.heldTicks++
            hold(v)
        }
        Flight.LANDING -> land(v)
        Flight.TAXI_OUT, Flight.TAXI_IN -> taxi(v)
    }
}

/** At the gate: loading, then off when it may go and has somewhere it can reach. */
private fun World.atGate(v: Aircraft) {
    if (v.waiting > 0) {
        if (--v.waiting == 0) {
            val due = !v.toDepot && (due(v.service, v.serviced) || needsGear(v) || hasDepot(v.owner, Depots.HANGAR) && renewNow(v))
            val here = airports.getOrNull(v.at)
            val o = v.orders.getOrNull(v.order % v.orders.size.coerceAtLeast(1))
            // Load and the gap run only when it is calling at the airport on this order.
            if (v.holding >= 0 && here != null && o != null && Order.station(o) == here.station && here.serves(v.info)) {
                if (!v.toDepot && !due && waitForLoad(v, o)) return
                if (waitForGap(v, due)) return
            }
            v.holding = 0
            if (v.orders.isNotEmpty() && !v.toDepot) {
                passOrder(v)
                stepOrder(v)
            }
            // Due a service or a change of gear: from this stop to the nearest hangar, then on to its next order.
            if (due) forService(v)
        }
        return
    }
    if (v.brokenFor > 0) { v.brokenFor--; return }
    if (!v.running) return
    val here = airports.getOrNull(v.at)
    val target = v.target
    if (target == -2) return
    if (target == -1) {
        if (here != null && here.depot == v.depot) taxiToHangar(v, here) else if (here != null && mayLeave(v, here)) taxiOut(v, here)
        return
    }
    val dest = airportOf(target)
    // Somewhere it can't land at all, or out of its range from here: on to the next order.
    if (dest == null || !dest.serves(v.info) || here != null && tooFar(v, here, dest)) {
        passOrder(v)
        stepOrder(v)
        v.holding = -1
        v.waiting = Calendar.TICKS_PER_DAY / 4
        return
    }
    if (dest === here) {
        callHere(v, here)
        return
    }
    if (here == null) { depart(v, null); return }
    if (!mayLeave(v, here)) { v.holding = -1; v.waiting = Calendar.TICKS_PER_DAY / 8; return }
    taxiOut(v, here)
}

/** Whether airport [b] is beyond [v]'s range from [a]. */
private fun World.tooFar(v: Aircraft, a: Airport, b: Airport): Boolean {
    val dx = (a.x - b.x).toLong()
    val dy = (a.y - b.y).toLong()
    val km = isqrt(dx * dx + dy * dy) * TILE_METRES / 1000
    return v.info.range > 0 && km > v.info.range
}

/** Straight up, for a helicopter or one with no airport or stand to taxi from (from an old save); a plane from the runway's end. */
private fun World.depart(v: Aircraft, from: Airport?) {
    v.state = Flight.TAKEOFF
    v.speed = 0
    if (from != null && !v.heli) {
        val h = takeOffHeading(v, from)
        val e = runwayEnds(from, h)
        v.x = e[0]; v.y = e[1]
        v.heading = h
    }
}

/** The airport it's flying to: its next stop's, or its hangar's. */
private fun World.destination(v: Aircraft): Airport? =
    if (v.target == -1) airports.firstOrNull { it != null && it.depot == v.depot } else airportOf(v.target)

/** The height it cruises at, in 16ths of a step, over the highest ground at either end. */
private fun World.cruiseAltitude(v: Aircraft): Int {
    val dest = airportOf(v.target)?.let { heightAt(it.x, it.y) } ?: 0
    val from = airports.getOrNull(v.at)?.let { heightAt(it.x, it.y) } ?: 0
    val over = if (v.heli || v.info.runway == Runways.STRIP) LOW_CRUISE else CRUISE
    return (maxOf(dest, from) + over) * 16
}

/**
 * Rolling down the runway the way it faces, speeding up until it reaches its [liftOffSpeed], then climbing straight
 * ahead until [CLIMB_OUT] steps up and past the runway's end; off at the end if it hasn't lifted by then. A helicopter goes straight up.
 */
private fun World.takeOff(v: Aircraft) {
    val top = cruiseAltitude(v)
    if (v.heli) {
        v.altitude += CLIMB
        if (v.altitude >= top) leaveAirport(v)
        return
    }
    val from = airports.getOrNull(v.at) ?: run { leaveAirport(v); return }
    val e = runwayEnds(from, v.heading)
    val lift = liftOffSpeed(v)
    val roll = takeOffRoll(v, from)
    // On the ground at [rollAccel], the speed that reaches [lift] in [roll]; up, as fast as in the cruise.
    val gain = if (v.altitude > 0) maxOf(rollAccel(lift, roll), v.maxSpeed / 40 + 16) else rollAccel(lift, roll)
    v.speed = minOf(v.speed + gain, v.maxSpeed)
    advance(v, v.speed / 16)
    val total = isqrt((e[2] - e[0]).toLong() * (e[2] - e[0]) + (e[3] - e[1]).toLong() * (e[3] - e[1])).toInt().coerceAtLeast(1)
    val ground = heightAt(from.x, from.y) * 16
    if (v.altitude == 0) {
        if (v.speed >= lift || along(v, e, v.heading) >= total) v.altitude = ground + CLIMB
        return
    }
    v.altitude = minOf(top, v.altitude + CLIMB)
    // It holds the runway until it is past the end, so nothing goes onto the runway under it.
    if (v.altitude >= minOf(top, ground + CLIMB_OUT * 16) && along(v, e, v.heading) >= total) leaveAirport(v)
}

/** The speed [v] lifts off at, in its speed's units: [LIFT_OFF] hundredths of its top speed for its runway class. */
internal fun liftOffSpeed(v: Aircraft) = v.maxSpeed * LIFT_OFF[v.info.runway.coerceIn(0, LIFT_OFF.size - 1)] / 100

/** How far [v] rolls from standing to lifting off at [a], in 4096ths: on floats on water [WATER_ROLL], else [TAKE_OFF_ROLL] for its class. */
internal fun takeOffRoll(v: Aircraft, a: Airport) =
    (if (a.kind == Airports.SEAPLANE) WATER_ROLL else TAKE_OFF_ROLL[v.info.runway.coerceIn(0, TAKE_OFF_ROLL.size - 1)]) * SHIP_UNIT / 16

/** The speed gained a tick to reach [lift] from standing in [roll] 4096ths, a tick moving a 16th of the speed. */
private fun rollAccel(lift: Int, roll: Int) = ((lift.toLong() * lift + 32L * roll - 1) / (32L * roll)).toInt().coerceAtLeast(1)

private fun World.leaveAirport(v: Aircraft) {
    v.state = Flight.CRUISE
    v.holding = 0
}

/** Moves [v] up to [step] straight towards ([tx], [ty]); what's left of the way after. */
private fun moveTo(v: Aircraft, tx: Int, ty: Int, step: Int): Int {
    val dx = tx - v.x
    val dy = ty - v.y
    val dist = isqrt(dx.toLong() * dx + dy.toLong() * dy).toInt()
    if (dist == 0) return 0
    if (dist <= step) { v.x = tx; v.y = ty; return 0 }
    v.x += (dx.toLong() * step / dist).toInt()
    v.y += (dy.toLong() * step / dist).toInt()
    return dist - step
}

/** Moves [v] [step] the way it faces. */
private fun advance(v: Aircraft, step: Int) {
    v.x += (step.toLong() * cos256(v.heading) / TRIG_ONE).toInt()
    v.y += (step.toLong() * sin256(v.heading) / TRIG_ONE).toInt()
}

/** The turn from heading [from] to [to] the shorter way, in 256ths, -128 up to 127. */
private fun turnTo(from: Int, to: Int) = ((to - from + 128) and 255) - 128

/** Turns [v] towards heading [to] the shorter way, by up to [rate]. */
private fun steer(v: Aircraft, to: Int, rate: Int) {
    v.heading = (v.heading + turnTo(v.heading, to).coerceIn(-rate, rate)) and 255
}

/** Turns [v] towards ([tx], [ty]) by up to [rate]; nothing if it's there. */
private fun steerAt(v: Aircraft, tx: Int, ty: Int, rate: Int) {
    if (tx != v.x || ty != v.y) steer(v, bearing256(tx - v.x, ty - v.y), rate)
}

/**
 * Turns plane [v] towards ([tx], [ty]) at its [turnRate]; but if that point is inside the circle it would turn round,
 * where it could only circle it, it flies straight on until it can reach it.
 */
private fun steerPlane(v: Aircraft, tx: Int, ty: Int) {
    val dx = (tx - v.x).toLong()
    val dy = (ty - v.y).toLong()
    val ahead = (dx * cos256(v.heading) + dy * sin256(v.heading)) / TRIG_ONE
    val side = kotlin.math.abs(dy * cos256(v.heading) - dx * sin256(v.heading)) / TRIG_ONE
    val r = turnRadius(v).toLong()
    if (ahead * ahead + (side - r) * (side - r) < r * r) return
    steerAt(v, tx, ty, turnRate(v))
}

/** Its turning circle's radius, in 4096ths: two tiles for airliners, one for small planes, float planes and a helicopter holding. */
internal fun turnRadius(v: Aircraft) = if (v.heli || v.info.runway == Runways.STRIP || v.info.floats) SHIP_UNIT else 2 * SHIP_UNIT

/** How far [v] turns at most a tick, in 256ths: a helicopter almost on the spot, a plane round its [turnRadius] at its speed, [TAXI_TURN] taxiing. */
internal fun turnRate(v: Aircraft) = if (v.taxiing) TAXI_TURN else if (v.heli) HELI_TURN else circleRate(v.speed / 16, turnRadius(v))

/** The turn a tick, in 256ths, that goes round a circle of [radius] at [step] a tick; at least 1. */
private fun circleRate(step: Int, radius: Int): Int = maxOf(1, ((step.toLong() * 256_000 + 3_141L * radius) / (6_283L * radius)).toInt())

/** Airport [a]'s runway from the end it starts at to the end it finishes at for heading [h]: start x, y, end x, y. */
private fun World.runwayEnds(a: Airport, h: Int): IntArray {
    val r = runway(a)
    val dot = (r[2] - r[0]).toLong() * cos256(h) + (r[3] - r[1]).toLong() * sin256(h)
    return if (dot >= 0) r else intArrayOf(r[2], r[3], r[0], r[1])
}

/** How far [v] is past the start of runway ends [e] along heading [h], and off to its right ([h] + a quarter turn), in 4096ths. */
private fun along(v: Aircraft, e: IntArray, h: Int) = (((v.x - e[0]).toLong() * cos256(h) + (v.y - e[1]).toLong() * sin256(h)) / TRIG_ONE).toInt()
private fun across(v: Aircraft, e: IntArray, h: Int) = (((v.y - e[1]).toLong() * cos256(h) - (v.x - e[0]).toLong() * sin256(h)) / TRIG_ONE).toInt()

/** The point [a] along and [c] across from the start of runway ends [e] for heading [h]. */
private fun point(e: IntArray, h: Int, a: Int, c: Int) = intArrayOf(
    e[0] + ((a.toLong() * cos256(h) - c.toLong() * sin256(h)) / TRIG_ONE).toInt(),
    e[1] + ((a.toLong() * sin256(h) + c.toLong() * cos256(h)) / TRIG_ONE).toInt(),
)

/** The way to take off and land on [a] into the wind, in 256ths; null when it's calm, weather is off or the wind is across the runway. */
private fun World.intoWind(a: Airport): Int? {
    if (!settings.weather || weather.wind < CALM) return null
    val r = runway(a)
    val f = weather.windFrom and 7
    val dot = (r[2] - r[0]).coerceIn(-1, 1) * WIND_X[f] + (r[3] - r[1]).coerceIn(-1, 1) * WIND_Y[f]
    if (dot == 0) return null
    val way = bearing256(r[2] - r[0], r[3] - r[1])
    return if (dot > 0) way else (way + 128) and 255
}

/** The way [v] takes off from [a]: into the wind, or without one towards where it's going. */
private fun World.takeOffHeading(v: Aircraft, a: Airport) = takeOffTowards(a, destination(v))

/** The way a plane takes off from [a] for [dest]: into the wind, or without one towards it. */
private fun World.takeOffTowards(a: Airport, dest: Airport?): Int {
    intoWind(a)?.let { return it }
    val r = runway(a)
    val way = bearing256(r[2] - r[0], r[3] - r[1])
    if (dest == null) return way
    val u = SHIP_UNIT
    val dx = (dest.x * u + dest.w * u / 2 - (r[0] + r[2]) / 2).toLong()
    val dy = (dest.y * u + dest.h * u / 2 - (r[1] + r[3]) / 2).toLong()
    return if (dx * (r[2] - r[0]) + dy * (r[3] - r[1]) < 0) (way + 128) and 255 else way
}

/** The way [v] lands on [a]: into the wind, or without one from the end nearer it. */
private fun World.landingHeading(v: Aircraft, a: Airport): Int {
    intoWind(a)?.let { return it }
    val r = runway(a)
    val way = bearing256(r[2] - r[0], r[3] - r[1])
    val dx = (v.x - (r[0] + r[2]) / 2).toLong()
    val dy = (v.y - (r[1] + r[3]) / 2).toLong()
    return if (dx * (r[2] - r[0]) + dy * (r[3] - r[1]) > 0) (way + 128) and 255 else way
}

/**
 * Where a plane steers to land on [a] heading [h]: x, y, and 1 if it's on final. On final (ahead of the runway, facing
 * within a quarter turn of [h], and inside a wedge along the extended centreline that narrows to half a tile [APPROACH]
 * tiles out) it follows a point further along the line, so it joins it gently; well out on that side, it makes for the
 * line two turns past [APPROACH] tiles out; nearer in or past the runway, it goes round, out to the side it's on.
 */
private fun World.approachTarget(v: Aircraft, a: Airport, h: Int): IntArray {
    val e = runwayEnds(a, h)
    val r = turnRadius(v)
    val out = APPROACH * SHIP_UNIT
    val along = along(v, e, h)
    val across = across(v, e, h)
    val wedge = maxOf(0, (-along - out) / 2) + SHIP_UNIT / 2
    if (along < 0 && kotlin.math.abs(turnTo(v.heading, h)) <= 64 && kotlin.math.abs(across) <= wedge) {
        val p = point(e, h, along + maxOf(r + SHIP_UNIT, -along / 2), 0)
        return intArrayOf(p[0], p[1], 1)
    }
    val join = out + 2 * r
    // Going round, to the side it's on, or near the line the side it's heading for.
    val side = if (kotlin.math.abs(across) > r) across else sin256(v.heading - h)
    val p = if (along < -join) point(e, h, -join, 0) else point(e, h, -join - r, (if (side < 0) -2 else 2) * r)
    return intArrayOf(p[0], p[1], 0)
}

/** Flying to its next airport at its cruising height, lining up to land on the way; near it, landing or holding. */
private fun World.cruise(v: Aircraft) {
    val target = v.target
    val dest = destination(v)
    if (dest == null || !dest.serves(v.info)) {
        // Its airport's gone, or it can't land there: on to the next stop, or home.
        if (target >= 0 && Order.stops(v.orders) > 1) {
            passOrder(v)
            stepOrder(v)
        } else v.toDepot = true
        return
    }
    v.speed = minOf(v.speed + v.maxSpeed / 40 + 16, v.maxSpeed)
    val top = cruiseAltitude(v)
    v.altitude = if (v.altitude < top) minOf(top, v.altitude + CLIMB) else maxOf(top, v.altitude - CLIMB)
    if (v.heli) {
        val p = heliPoint(v, dest)
        steerAt(v, p[0], p[1], HELI_TURN)
        if (moveTo(v, p[0], p[1], v.speed / 16) == 0) overAirport(v, dest, v.heading)
        return
    }
    val h = landingHeading(v, dest)
    val t = approachTarget(v, dest, h)
    steerPlane(v, t[0], t[1])
    advance(v, v.speed / 16)
    val e = runwayEnds(dest, h)
    val dx = (v.x - e[0]).toLong()
    val dy = (v.y - e[1]).toLong()
    val near = (APPROACH + 2L) * SHIP_UNIT
    if (dx * dx + dy * dy <= near * near) overAirport(v, dest, h)
}

/** Where a helicopter puts down on [a]: the first free stand, or the hangar door going home. */
private fun World.heliPoint(v: Aircraft, a: Airport): IntArray {
    if (homeBound(v, a)) return Taxiways.DOORS[a.kind].let { localPoint(a, it[2], it[3]) }
    return standPoint(a, freeStand(a, v).coerceAtLeast(0))
}

/** Whether [v] is going into its hangar at [a]. */
private fun homeBound(v: Aircraft, a: Airport) = v.target == -1 && a.depot == v.depot

/** Near [dest]: landing on it heading [h] if it can, with a stand to go to unless it's going into the hangar, holding if not. */
private fun World.overAirport(v: Aircraft, dest: Airport, h: Int) {
    v.holding = 0
    val stand = if (homeBound(v, dest)) -1 else standFor(dest, v)
    // A helicopter comes down where it is, so only onto the stand it's over.
    val over = !v.heli || stand < 0 || standPoint(dest, stand).let { it[0] == v.x && it[1] == v.y }
    if (over && cantLand(v, dest) == null) {
        v.state = Flight.LANDING
        v.at = dest.id
        v.landing = h
        v.stand = stand
    } else v.state = Flight.HOLD
}

/** Circling steadily where it is, looking now and then to land; diverting on to its next stop after a while. */
private fun World.hold(v: Aircraft) {
    val target = v.target
    val dest = destination(v)
    if (dest == null) { v.state = Flight.CRUISE; return }
    v.holding++
    v.heading = (v.heading + circleRate(v.speed / 16, turnRadius(v))) and 255
    advance(v, v.speed / 16)
    val top = cruiseAltitude(v)
    if (v.altitude < top) v.altitude = minOf(top, v.altitude + CLIMB)
    if (v.holding % 16 == 0) {
        val why = cantLand(v, dest)
        if (why == null) {
            v.state = Flight.CRUISE
            return
        }
        // A runway in use soon frees: that never makes it divert.
        if (why == "busy") v.holding -= 16
    }
    if (v.holding > HOLD_TICKS && Order.stops(v.orders) > 1 && target >= 0) {
        // Diverted: on to its next stop with what it carries.
        passOrder(v)
        stepOrder(v)
        v.state = Flight.CRUISE
        // A wreck closing the airport has its own news.
        if (!wreckAt(dest)) tell(News.DIVERTED, v.id)
    }
}

/**
 * Lining up and down the glide path to the runway's start, going round if it can't line up, then rolling to its end;
 * a helicopter straight down onto its pad.
 */
private fun World.land(v: Aircraft) {
    val a = airports.getOrNull(v.at) ?: run { v.state = Flight.CRUISE; return }
    val ground = heightAt(a.x, a.y) * 16
    if (v.heli) {
        v.altitude -= CLIMB
        if (v.altitude <= ground) { v.altitude = 0; arriveAircraft(v, a) }
        return
    }
    if (v.altitude > 0) {
        // A wreck on the runway: round again and hold, giving its stand up.
        if (wreckAt(a)) { v.state = Flight.HOLD; v.holding = 0; v.stand = -1; return }
        v.holding++
        val h = v.landing
        val t = approachTarget(v, a, h)
        steerPlane(v, t[0], t[1])
        advance(v, v.speed / 16)
        val top = cruiseAltitude(v)
        if (t[2] == 0) {
            if (v.altitude < top) v.altitude = minOf(top, v.altitude + CLIMB)
            return
        }
        val e = runwayEnds(a, h)
        val along = along(v, e, h)
        // Down the glide path from its cruising height [APPROACH] tiles out, never climbing back up to it.
        val glide = (ground + (top - ground).toLong() * -along / (APPROACH * SHIP_UNIT)).toInt().coerceIn(ground + 1, top)
        if (glide < v.altitude) v.altitude = maxOf(glide, v.altitude - GLIDE)
        if (along < 0) return
        // Over the runway's start: down if it's lined up, rolling out onto the centreline, else round again; one that's
        // gone round too long is put down on the line.
        val lined = kotlin.math.abs(across(v, e, h)) <= SHIP_UNIT / 4 && kotlin.math.abs(turnTo(v.heading, h)) <= turnRate(v)
        if (!lined && v.holding <= LANDING_TICKS) return
        if (!lined) {
            val p = point(e, h, along, 0)
            v.x = p[0]; v.y = p[1]
        }
        v.heading = h
        v.altitude = 0
        if (settings.collisions && rng.below(crashOdds(v, a)) == 0) crashLanding(v, a)
        return
    }
    // Rolling out to the exit it can make nearest its stand, braking to taxi speed just in time, or to the far end's spur.
    val brake = v.maxSpeed / 10
    val out = rollOut(v, a, brake)
    val p = Taxiways.spur(a.kind, out).let { localPoint(a, it[it.size - 2], it[it.size - 1]) }
    val gap = isqrt((p[0] - v.x).toLong() * (p[0] - v.x) + (p[1] - v.y).toLong() * (p[1] - v.y)).toLong()
    if (stopping(v.speed, brake) + v.speed / 16 >= gap) v.speed = maxOf(v.speed - brake, minOf(v.speed, TAXI_STEP * 16))
    if (moveTo(v, p[0], p[1], v.speed / 16) == 0) taxiIn(v, a, out)
}

/** How far, in 4096ths, rolling at [speed] takes to slow to taxi speed braking by [brake] a tick. */
private fun stopping(speed: Int, brake: Int): Long {
    val slow = TAXI_STEP * 16
    val ticks = if (speed <= slow) 0 else (speed - slow + brake - 1) / brake.coerceAtLeast(1)
    return ticks.toLong() * (speed + slow) / 32
}

/**
 * Where [v], landing on [a] and braking by [brake] a tick, turns off: of the exits ahead for its way no nearer than it
 * takes to slow to taxi speed, the one joining nearest its stand (or the door without one), else the far end.
 */
private fun World.rollOut(v: Aircraft, a: Airport, brake: Int): Int {
    val far = if (endFor(a, v.heading) == Taxiways.END0) Taxiways.END1 else Taxiways.END0
    val way = if (far == Taxiways.END1) 1 else -1
    val o = localPoint(a, 0, 0)
    val e = localPoint(a, 16, 0)
    // Along the runway in 4096ths, from the airport's corner.
    val at = ((v.x - o[0]).toLong() * (e[0] - o[0]) + (v.y - o[1]).toLong() * (e[1] - o[1])) / SHIP_UNIT
    val stop = stopping(v.speed, brake)
    val goal = Taxiways.spur(a.kind, if (v.stand >= 0) v.stand else Taxiways.DOOR)[0]
    var best = far
    var bestOff = Int.MAX_VALUE
    for ((k, x) in Taxiways.EXITS[a.kind].withIndex()) {
        val w = Taxiways.exitWay(a.kind, x)
        if (w != 0 && w != way) continue
        if ((x[x.size - 2] * 256L - at) * way < stop) continue
        val off = kotlin.math.abs(x[0] - goal)
        if (off < bestOff) { best = Taxiways.EXIT0 + k; bestOff = off }
    }
    return best
}

/** Whether a wreck stands on airport [a]'s runway, closing it. */
private fun World.wreckAt(a: Airport) = aircraft.any { it != null && it.wreckedFor > 0 && it.at == a.id }

/**
 * One landing in this many crashes, with collisions on: [CRASH_ODDS], or [SHORT_CRASH_ODDS] for a fast aircraft on
 * a strip or regional airport, as OpenTTD has jets on small airports.
 */
internal fun crashOdds(v: Aircraft, a: Airport) =
    if (v.info.speed >= FAST_KMH && Airports.CLASS[a.kind] in Runways.STRIP..Runways.REGIONAL) SHORT_CRASH_ODDS else CRASH_ODDS

/** Aircraft [v] crashes touching down on [a]: everyone aboard lost, the wreck left on the runway. */
private fun World.crashLanding(v: Aircraft, a: Airport) {
    v.wreckedFor = WRECK_TICKS
    v.running = false
    v.speed = 0
    v.waiting = 0
    v.brokenFor = 0
    v.toDepot = false
    v.servicing = false
    v.holding = 0
    v.stand = -1
    v.orders.clear()
    v.order = 0
    v.shared = -1
    linksDirty = true
    val tile = index((v.x / SHIP_UNIT).coerceIn(0, width - 1), (v.y / SHIP_UNIT).coerceIn(0, height - 1))
    crashed(tile, listOf(CrashVehicle(Crash.AIRCRAFT, v.id, v.owner, v.name) to v))
}

/** A helicopter down on its stand: calling at the station, or into the hangar. */
private fun World.arriveAircraft(v: Aircraft, a: Airport) {
    v.state = Flight.GATE
    v.altitude = 0
    v.speed = 0
    v.holding = 0
    v.at = a.id
    if (homeBound(v, a)) { v.stand = -1; intoHangar(v); return }
    if (v.stand < 0) v.stand = freeStand(a, v)
    callHere(v, a)
}

private fun World.callHere(v: Aircraft, a: Airport) {
    val s = stations.getOrNull(a.station) ?: return
    val o = v.orders.getOrNull(v.order % v.orders.size.coerceAtLeast(1)) ?: 0
    v.waiting = maxOf(Calendar.TICKS_PER_DAY / 2, Calendar.TICKS_PER_DAY * Order.waitDays(o))
    v.holding = if (v.target == s.id) 0 else -1
    if (v.target == s.id) callAt(v, s, o) else v.waiting = 1
}

private fun World.intoHangar(v: Aircraft) {
    // Sent in by the player: back on time when it comes out.
    if (v.toDepot && !v.servicing) restartTimetable(v)
    v.inDepot = true
    v.leftFrom = -1
    v.stand = -1
    v.running = false
    v.toDepot = false
    if (v.servicing) {
        autorenew(v)
        v.reliability = v.info.reliability * 100
        v.serviced = workDay
        v.servicing = false
        v.running = true
        // Gear for the airports of its orders, as [needsGear] asks.
        if (v.seasonalGear && v.info.floats) v.gear = wantedGear(v)
    }
}

/** Whether bush plane [v] wants its gear changed for the season. */
private fun World.needsGear(v: Aircraft) = v.seasonalGear && v.info.floats && v.gear != wantedGear(v)

/**
 * A compatible company hangar with the shortest flight through it to [next], or the nearest if none has an onward flight.
 * Without [next], choose the nearest compatible hangar.
 */
internal fun World.nearestHangar(v: Aircraft, next: Airport? = null): Airport? {
    // From the airport it's at, or where it is in the air, in tiles.
    val here = if (v.state == Flight.GATE || v.taxiing) airports.getOrNull(v.at) else null
    val x = here?.x ?: (v.x / SHIP_UNIT)
    val y = here?.y ?: (v.y / SHIP_UNIT)
    var best: Airport? = null
    var bestDist = Long.MAX_VALUE
    var bestOut = Long.MAX_VALUE
    var nearest: Airport? = null
    var nearestDist = Long.MAX_VALUE
    for (a in airports) {
        if (a == null || !a.serves(v.info)) continue
        val d = depots.getOrNull(a.depot) ?: continue
        if (d.owner != v.owner || d.kind != Depots.HANGAR) continue
        val dx = (a.x - x).toLong()
        val dy = (a.y - y).toLong()
        val km = isqrt(dx * dx + dy * dy) * TILE_METRES / 1000
        if (v.info.range > 0 && km > v.info.range) continue
        // Wheels don't land on water, nor floats on land.
        if (if (a.kind == Airports.SEAPLANE) v.gear == Gear.WHEELS else v.info.floats && v.gear == Gear.FLOATS) continue
        val distance = isqrt((dx * dx + dy * dy) * 1_000_000)
        if (distance < nearestDist) { nearest = a; nearestDist = distance }
        if (next != null && (!next.serves(v.info) || tooFar(v, a, next))) continue
        val nx = (next?.x ?: a.x) - a.x
        val ny = (next?.y ?: a.y) - a.y
        val trip = distance + isqrt((nx.toLong() * nx + ny.toLong() * ny) * 1_000_000)
        if (trip < bestDist || trip == bestDist && distance < bestOut) { best = a; bestDist = trip; bestOut = distance }
    }
    return best ?: nearest
}

/** Sends [v] for a service at [nearestHangar], its home after. Nothing happens if there's none. */
internal fun World.forService(v: Aircraft) {
    val next = v.orders.getOrNull(v.order % v.orders.size.coerceAtLeast(1))?.let { airportOf(Order.station(it)) }
    val a = nearestHangar(v, next) ?: return
    v.depot = a.depot
    v.toDepot = true
    v.servicing = true
}

/** Each day: wear and breakdowns (kept on the ground for repairs), trips to a hangar for one long overdue a service, and running costs. */
internal fun World.aircraftDay() {
    if (aircraft.isEmpty()) return
    for (v in aircraft) {
        if (v == null || v.inDepot || v.wreckedFor > 0) continue
        v.reliability = (v.reliability - WEAR_A_DAY).coerceAtLeast(LEAST_RELIABLE / 2)
        if (v.running && v.state == Flight.GATE && v.brokenFor == 0 && rng.below(BREAKDOWN_ODDS) < 10_000 - v.reliability) {
            v.brokenFor = Calendar.TICKS_PER_DAY
            v.breakdowns++
        }
        // Due ones go after their next call; this catches one that's called nowhere in twice the time.
        if (overdue(v.service, v.serviced) && v.running && !v.toDepot) forService(v)
    }
    for (v in aircraft) {
        if (v == null || v.inDepot && !v.running || v.wreckedFor > 0) continue
        val c = companies.getOrNull(v.owner) ?: continue
        c.runningOwed += price(v.info.running)
        val today = c.runningOwed / 365
        spend(c, Books.RUNNING, today)
        v.profitThisYear -= today
        c.runningOwed %= 365
    }
}

/** Where aircraft [v] is, in tiles: x, y, the way it faces in eighths, and how high in steps. */
fun World.aircraftPosition(v: Aircraft) = floatArrayOf(v.x / SHIP_UNIT.toFloat(), v.y / SHIP_UNIT.toFloat(), v.heading / 32f, v.altitude / 16f)

/** Sine of heading [h] in 256ths of a turn, and its cosine, in [TRIG_ONE]ths; whole numbers so every copy of the game agrees. */
internal fun sin256(h: Int): Int {
    val a = h and 255
    return when {
        a <= 64 -> SINE[a]
        a <= 128 -> SINE[128 - a]
        a <= 192 -> -SINE[a - 128]
        else -> -SINE[256 - a]
    }
}

internal fun cos256(h: Int) = sin256(h + 64)

/** The heading of ([dx], [dy]) in 256ths of a turn, 0 along x, on round towards y; 0 for no way at all. */
internal fun bearing256(dx: Int, dy: Int): Int {
    val ax = kotlin.math.abs(dx.toLong())
    val ay = kotlin.math.abs(dy.toLong())
    val lo = minOf(ax, ay)
    val hi = maxOf(ax, ay)
    var k = 0
    while (k < 32 && lo * TRIG_ONE > TANGENT[k] * hi) k++
    val a = if (ay > ax) 64 - k else k
    val q = if (dx < 0) 128 - a else a
    return (if (dy < 0) -q else q) and 255
}

const val TRIG_ONE = 16384
/** Sines from 0 to a quarter turn in 256ths, in [TRIG_ONE]ths. */
private val SINE = intArrayOf(
    0, 402, 804, 1205, 1606, 2006, 2404, 2801, 3196, 3590, 3981, 4370, 4756, 5139, 5520, 5897, 6270, 6639, 7005, 7366, 7723, 8076,
    8423, 8765, 9102, 9434, 9760, 10080, 10394, 10702, 11003, 11297, 11585, 11866, 12140, 12406, 12665, 12916, 13160, 13395, 13623,
    13842, 14053, 14256, 14449, 14635, 14811, 14978, 15137, 15286, 15426, 15557, 15679, 15791, 15893, 15986, 16069, 16143, 16207,
    16261, 16305, 16340, 16364, 16379, 16384,
)
/** Tangents halfway between 256ths, from a half to 31 and a half, in [TRIG_ONE]ths: [bearing256] counts how many an angle is past. */
private val TANGENT = longArrayOf(
    201, 603, 1007, 1411, 1817, 2225, 2636, 3050, 3469, 3891, 4318, 4751, 5190, 5636, 6090, 6552, 7023, 7504, 7997, 8501, 9018, 9549,
    10096, 10659, 11241, 11842, 12466, 13113, 13786, 14487, 15220, 15987,
)
/** Where each of the eight winds of [Weather.windFrom] blows from, as a step in x and y. */
private val WIND_X = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)
private val WIND_Y = intArrayOf(0, -1, -1, -1, 0, 1, 1, 1)

/** Bits of [airSky]. */
private const val FOGGY = 1
private const val ICING = 2
/** Metres a tile. */
const val TILE_METRES = 750L
/** Wind, in km/h, that grounds small aircraft, and all. */
const val SMALL_STORM = 45
const val BIG_STORM = 75
/** Snow, in cm, on a gravel strip that only skis land on. */
const val STRIP_SNOW = 20
/** Ice, in cm, ski planes land on. */
const val SKI_ICE = 30
/** Steps over the higher airport aircraft cruise at: airliners, and bush planes and helicopters. */
const val CRUISE = 10
const val LOW_CRUISE = 6
/** How fast aircraft climb and descend, in 16ths of a step a tick, and the most they come down a tick on the glide path. */
const val CLIMB = 6
const val GLIDE = 16
/** How far out aircraft line up to land, in tiles; and how high over the airport they climb straight ahead after lifting off before turning, in steps. */
const val APPROACH = 5
const val CLIMB_OUT = 3
/**
 * Take-off by the runway class an aircraft needs (strip, regional, city, international): the speed it lifts off at, in
 * hundredths of its top speed, and how far it rolls from standing to get there, in 16ths of a tile; and on floats on
 * water. A bush plane is off within a third of a strip, an airliner most of the way down its runway.
 */
val LIFT_OFF = intArrayOf(50, 65, 75, 75)
val TAKE_OFF_ROLL = intArrayOf(8, 48, 72, 96)
const val WATER_ROLL = 12
/** A strip-class plane takes off from an exit partway along with at least this many hundredths of its roll left ahead. */
const val PARTWAY_SPARE = 300
/** How far a helicopter turns a tick, in 256ths. */
const val HELI_TURN = 32
/** How far aircraft taxi a tick, in 4096ths, quick on the map's scale so taxiing adds a day or two a trip; and the most they turn a tick, as round an eighth of a tile. */
const val TAXI_STEP = 320
private val TAXI_TURN = circleRate(TAXI_STEP, SHIP_UNIT / 8)
/** Ticks a taxiing aircraft waits for one in its way before going on anyway, so none waits for another for ever. */
const val TAXI_PATIENCE = 64
/** Wind under this, in km/h, is calm: aircraft take off towards where they're going and land from the nearer end. */
const val CALM = 5
/** Ticks lining up after which a plane lands at the next pass whether it's lined up or not. */
const val LANDING_TICKS = 4 * 64
/** One landing in this many crashes with collisions on, and for a fast aircraft on a small airport; fast is from this, in km/h. */
const val CRASH_ODDS = 400
const val SHORT_CRASH_ODDS = 100
const val FAST_KMH = 550
/** Ticks an aircraft holds before diverting. */
const val HOLD_TICKS = 3 * 64
/** The years landing aids and de-icing come in, and what each costs. */
const val ILS_FROM = 1948
const val DEICE_FROM = 1960
const val ILS_COST = 20_000L
const val DEICE_COST = 15_000L
