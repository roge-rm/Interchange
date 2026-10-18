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
    val cargoChoice: Int = -1,
) : Carrier {
    var name = ""
    override var renewAsked = NEVER_ASKED
    override var renewFailed = false
    val info get() = roster[model]
    var inDepot = true
    var running = false
    var waiting = 0
    /** Where it is, in 4096ths of a tile; how high, in 16ths of a step; the way it faces, in eighths. */
    var x = 0
    var y = 0
    var altitude = 0
    var heading = 0
    var speed = 0
    /** A [Flight] state, the airport it's at or last left, and how long it's been holding. */
    var state = Flight.GATE
    var at = -1
    var holding = 0
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
    val d = Depot(depots.size, company.id, tiles[0], 0, Depots.HANGAR)
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
        !airportTiles.containsKey(i) && !saltDepots.contains(i) && !deck.containsKey(i) && !spanHeads.containsKey(i)
}

/** Takes airport [a] down, if no aircraft is at it or based there. */
internal fun World.removeAirport(company: Company, a: Airport) {
    if (aircraft.any { it != null && (it.inDepot && it.depot == a.depot || !it.inDepot && it.at == a.id && (it.state == Flight.GATE || it.wreckedFor > 0)) }) return
    if (company.money < price(Prices.CLEAR)) return
    spend(company, Books.CONSTRUCTION, price(Prices.CLEAR))
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
    linksDirty = true
    when (c.what) {
        Command.TrainOrder.START_STOP -> if (v.inDepot) { if (v.running) v.running = false else outOfHangar(v) } else v.running = !v.running
        Command.TrainOrder.ORDERS -> {
            v.orders.clear()
            for (o in c.stations) if (stations.getOrNull(Order.station(o))?.owner == company.id) v.orders += o
            v.order = 0
        }
        Command.TrainOrder.TO_DEPOT -> if (!v.inDepot) {
            v.toDepot = !v.toDepot
            v.servicing = false
            // Its home hangar gone: the nearest it can use.
            if (v.toDepot && depots.getOrNull(v.depot) == null) nearestHangar(v)?.let { v.depot = it.depot }
        }
        Command.TrainOrder.SELL -> if (v.inDepot) {
            earn(company, Books.TRAINS_SOLD, craftValue(v.price, v.built, v.info.life))
            aircraft[v.id] = null
        }
        Command.TrainOrder.SERVICE -> v.service = !v.service
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

/** The hangar's airport rolls the aircraft out to a gate. */
private fun World.outOfHangar(v: Aircraft) {
    val a = airports.firstOrNull { it != null && it.depot == v.depot } ?: return
    v.running = true
    v.inDepot = false
    v.at = a.id
    v.state = Flight.GATE
    v.waiting = 0
    v.altitude = 0
    v.speed = 0
    val g = gatePoint(a, v.id)
    v.x = g[0]; v.y = g[1]
}

/** Where on airport [a] aircraft [id] stands at a gate, in 4096ths: off to the side of the runway, spread by id. */
fun World.gatePoint(a: Airport, id: Int): IntArray {
    val u = SHIP_UNIT
    if (a.kind == Airports.SEAPLANE) {
        val e = a.axis
        return intArrayOf(a.x * u + u / 2 + Track.dx(e) * u * 3 / 4, a.y * u + u / 2 + Track.dy(e) * u * 3 / 4)
    }
    if (a.kind == Airports.HELIPORT) return intArrayOf(a.x * u + u / 2, a.y * u + u / 2)
    val gates = Airports.GATES[a.kind]
    val k = id % gates
    // Gates in a row along the far side from the runway.
    return if (a.axis == Track.X) intArrayOf(a.x * u + (a.w * u) * (k + 1) / (gates + 1), a.y * u + a.h * u - u / 3)
    else intArrayOf(a.x * u + a.w * u - u / 3, a.y * u + (a.h * u) * (k + 1) / (gates + 1))
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
fun World.cantLand(v: Aircraft, a: Airport): String? {
    if (!a.serves(v.info)) return "runway"
    if (wreckAt(a)) return "wreck"
    if (weather.wind >= v.info.stormLimit()) return "storm"
    if (airSky(a) and FOGGY != 0 && !a.ils) return "fog"
    val busy = aircraft.count { it != null && it !== v && !it.inDepot && it.at == a.id && (it.state == Flight.GATE || it.state == Flight.LANDING) }
    if (busy >= Airports.GATES[a.kind]) return "gates"
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
            if (--v.wreckedFor == 0) { aircraft[v.id] = null; linksDirty = true }
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
        Flight.HOLD -> hold(v)
        Flight.LANDING -> land(v)
    }
}

/** At the gate: loading, then off when it may go and has somewhere it can reach. */
private fun World.atGate(v: Aircraft) {
    if (v.waiting > 0) {
        if (--v.waiting == 0) {
            if (v.orders.isNotEmpty() && !v.toDepot) v.order = (v.order + 1) % v.orders.size
            // Due a service or a change of gear: from this stop to the nearest hangar, then on to its next order.
            if (!v.toDepot && (due(v.service, v.serviced) || needsGear(v) || hasDepot(v.owner, Depots.HANGAR) && renewNow(v))) forService(v)
        }
        return
    }
    if (v.brokenFor > 0) { v.brokenFor--; return }
    if (!v.running) return
    val here = airports.getOrNull(v.at)
    val target = v.target
    if (target == -2) return
    if (target == -1) {
        if (here != null && here.depot == v.depot) intoHangar(v) else if (here != null && mayLeave(v, here)) depart(v, here)
        return
    }
    val dest = airportOf(target)
    // Somewhere it can't land at all, or out of its range from here: on to the next order.
    if (dest == null || !dest.serves(v.info) || here != null && tooFar(v, here, dest)) {
        v.order = (v.order + 1) % v.orders.size
        v.waiting = Calendar.TICKS_PER_DAY / 4
        return
    }
    if (dest === here) {
        callHere(v, here)
        return
    }
    if (here == null) { depart(v, null); return }
    if (!mayLeave(v, here)) { v.waiting = Calendar.TICKS_PER_DAY / 8; return }
    depart(v, here)
}

/** Whether airport [b] is beyond [v]'s range from [a]. */
private fun World.tooFar(v: Aircraft, a: Airport, b: Airport): Boolean {
    val dx = (a.x - b.x).toLong()
    val dy = (a.y - b.y).toLong()
    val km = isqrt(dx * dx + dy * dy) * TILE_METRES / 1000
    return v.info.range > 0 && km > v.info.range
}

private fun World.depart(v: Aircraft, from: Airport?) {
    v.state = Flight.TAKEOFF
    v.speed = 0
    if (from != null && !v.heli) {
        val r = runway(from)
        v.x = r[0]; v.y = r[1]
        v.heading = heading(r[2] - r[0], r[3] - r[1])
    }
}

/** The height it cruises at, in 16ths of a step, over the highest ground at either end. */
private fun World.cruiseAltitude(v: Aircraft): Int {
    val dest = airportOf(v.target)?.let { heightAt(it.x, it.y) } ?: 0
    val from = airports.getOrNull(v.at)?.let { heightAt(it.x, it.y) } ?: 0
    val over = if (v.heli || v.info.runway == Runways.STRIP) LOW_CRUISE else CRUISE
    return (maxOf(dest, from) + over) * 16
}

/** Rolling down the runway and climbing away, or a helicopter straight up. */
private fun World.takeOff(v: Aircraft) {
    val top = cruiseAltitude(v)
    if (v.heli) {
        v.altitude += CLIMB
        if (v.altitude >= top) leaveAirport(v)
        return
    }
    val from = airports.getOrNull(v.at) ?: run { leaveAirport(v); return }
    val r = runway(from)
    v.speed = minOf(v.speed + v.maxSpeed / 40 + 16, v.maxSpeed)
    val left = moveTo(v, r[2], r[3], v.speed / 16)
    val total = isqrt((r[2] - r[0]).toLong() * (r[2] - r[0]) + (r[3] - r[1]).toLong() * (r[3] - r[1])).toInt().coerceAtLeast(1)
    val ground = heightAt(from.x, from.y) * 16
    // Off the ground halfway along.
    val gone = total - left
    if (gone > total / 2) v.altitude = ground + (gone - total / 2) * 48 / total
    if (left == 0) leaveAirport(v)
}

private fun World.leaveAirport(v: Aircraft) {
    v.state = Flight.CRUISE
    v.holding = 0
}

/** Moves [v] up to [step] towards ([tx], [ty]), turning to face that way; what's left of the way after. */
private fun moveTo(v: Aircraft, tx: Int, ty: Int, step: Int): Int {
    val dx = tx - v.x
    val dy = ty - v.y
    val dist = isqrt(dx.toLong() * dx + dy.toLong() * dy).toInt()
    if (dist == 0) return 0
    v.heading = flightHeading(dx, dy)
    if (dist <= step) { v.x = tx; v.y = ty; return 0 }
    v.x += (dx.toLong() * step / dist).toInt()
    v.y += (dy.toLong() * step / dist).toInt()
    return dist - step
}

private fun flightHeading(dx: Int, dy: Int): Int {
    val ax = kotlin.math.abs(dx)
    val ay = kotlin.math.abs(dy)
    return when {
        ax > ay * 2 -> heading(dx, 0)
        ay > ax * 2 -> heading(0, dy)
        else -> heading(dx, dy)
    }
}

/** Where an aircraft lines up to land on [a]: out along the runway's line from its start, or over the pad for a helicopter. */
private fun World.approach(v: Aircraft, a: Airport): IntArray {
    if (v.heli) return gatePoint(a, v.id)
    val r = runway(a)
    val dx = (r[2] - r[0]).coerceIn(-1, 1)
    val dy = (r[3] - r[1]).coerceIn(-1, 1)
    return intArrayOf(r[0] - dx * SHIP_UNIT * APPROACH, r[1] - dy * SHIP_UNIT * APPROACH)
}

/** Flying to where it lines up for its next airport, at its cruising height. */
private fun World.cruise(v: Aircraft) {
    val target = v.target
    val dest = if (target == -1) airports.firstOrNull { it != null && it.depot == v.depot } else airportOf(target)
    if (dest == null || !dest.serves(v.info)) {
        // Its airport's gone, or it can't land there: on to the next stop, or home.
        if (target >= 0 && v.orders.size > 1) v.order = (v.order + 1) % v.orders.size else v.toDepot = true
        return
    }
    v.speed = minOf(v.speed + v.maxSpeed / 40 + 16, v.maxSpeed)
    val top = cruiseAltitude(v)
    v.altitude = if (v.altitude < top) minOf(top, v.altitude + CLIMB) else maxOf(top, v.altitude - CLIMB)
    val p = approach(v, dest)
    if (moveTo(v, p[0], p[1], v.speed / 16) == 0) {
        val why = cantLand(v, dest)
        if (why == null) { v.state = Flight.LANDING; v.at = dest.id } else { v.state = Flight.HOLD; v.holding = 0 }
    }
}

/** Circling where it lines up, looking now and then to land; diverting on to its next stop after a while. */
private fun World.hold(v: Aircraft) {
    val target = v.target
    val dest = if (target == -1) airports.firstOrNull { it != null && it.depot == v.depot } else airportOf(target)
    if (dest == null) { v.state = Flight.CRUISE; return }
    v.holding++
    val p = approach(v, dest)
    // Round a circle of a tile and a half, a sixteenth of the way each few ticks; whole numbers keep every copy alike.
    val k = (v.holding / 6) % 16
    val cx = p[0] + CIRCLE[k] * SHIP_UNIT * 3 / 2000
    val cy = p[1] + CIRCLE[(k + 12) % 16] * SHIP_UNIT * 3 / 2000
    moveTo(v, cx, cy, v.speed / 16)
    if (v.holding % 16 == 0 && cantLand(v, dest) == null) {
        v.state = Flight.CRUISE
        return
    }
    if (v.holding > HOLD_TICKS && v.orders.size > 1 && target >= 0) {
        // Diverted: on to its next stop with what it carries.
        v.order = (v.order + 1) % v.orders.size
        v.state = Flight.CRUISE
        // A wreck closing the airport has its own news.
        if (!wreckAt(dest)) tell(News.DIVERTED, v.id)
    }
}

/** Down the glide path to the runway's start, then rolling to its end; a helicopter straight down onto its pad. */
private fun World.land(v: Aircraft) {
    val a = airports.getOrNull(v.at) ?: run { v.state = Flight.CRUISE; return }
    val ground = heightAt(a.x, a.y) * 16
    if (v.heli) {
        v.altitude -= CLIMB
        if (v.altitude <= ground) { v.altitude = 0; arriveAircraft(v, a) }
        return
    }
    val r = runway(a)
    if (v.altitude > 0) {
        // A wreck on the runway: round again and hold.
        if (wreckAt(a)) { v.state = Flight.HOLD; v.holding = 0; return }
        val left = moveTo(v, r[0], r[1], v.speed / 16)
        val glide = SHIP_UNIT * APPROACH
        v.altitude = ground + ((cruiseAltitude(v) - ground).toLong() * left / glide).toInt().coerceAtLeast(0)
        if (left == 0) {
            v.altitude = 0
            if (settings.collisions && rng.below(crashOdds(v, a)) == 0) crashLanding(v, a)
        }
        return
    }
    v.speed = maxOf(v.speed - v.maxSpeed / 30, v.maxSpeed / 6)
    if (moveTo(v, r[2], r[3], v.speed / 16) == 0) arriveAircraft(v, a)
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
    v.orders.clear()
    v.order = 0
    linksDirty = true
    val tile = index((v.x / SHIP_UNIT).coerceIn(0, width - 1), (v.y / SHIP_UNIT).coerceIn(0, height - 1))
    crashed(tile, listOf(CrashVehicle(Crash.AIRCRAFT, v.id, v.owner, v.name) to v))
}

/** Down and at a gate: calling at the station, or into the hangar. */
private fun World.arriveAircraft(v: Aircraft, a: Airport) {
    v.state = Flight.GATE
    v.altitude = 0
    v.speed = 0
    v.holding = 0
    v.at = a.id
    val g = gatePoint(a, v.id)
    v.x = g[0]; v.y = g[1]
    if (v.target == -1 && a.depot == v.depot) { intoHangar(v); return }
    callHere(v, a)
}

private fun World.callHere(v: Aircraft, a: Airport) {
    val s = stations.getOrNull(a.station) ?: return
    val o = v.orders.getOrNull(v.order % v.orders.size.coerceAtLeast(1)) ?: 0
    v.waiting = maxOf(Calendar.TICKS_PER_DAY / 2, Calendar.TICKS_PER_DAY * Order.waitDays(o))
    if (v.target == s.id) callAt(v, s, o) else v.waiting = 1
}

private fun World.intoHangar(v: Aircraft) {
    v.inDepot = true
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
    val here = if (v.state == Flight.GATE) airports.getOrNull(v.at) else null
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

/** Where aircraft [v] is, in tiles: x, y, the way it faces, and how high in steps. */
fun World.aircraftPosition(v: Aircraft) = floatArrayOf(v.x / SHIP_UNIT.toFloat(), v.y / SHIP_UNIT.toFloat(), v.heading.toFloat(), v.altitude / 16f)

/** Cosines round a circle in sixteenths, in thousandths. */
private val CIRCLE = intArrayOf(1000, 924, 707, 383, 0, -383, -707, -924, -1000, -924, -707, -383, 0, 383, 707, 924)

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
/** How fast aircraft climb and descend, in 16ths of a step a tick; how far out they line up to land, in tiles. */
const val CLIMB = 6
const val APPROACH = 5
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
