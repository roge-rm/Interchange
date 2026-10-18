package com.rm.interchange.sim

/*
 * Buses, trucks and streetcars, and how they drive.
 *
 * A road vehicle is always on one step of road: a tile, the edge it came in
 * at and the edge it's leaving by (the same at a dead end, where it turns
 * round), and how far along that it is. It keeps to the right of the road.
 * Positions and speeds are in the trains' units.
 *
 * Vehicles leaving a tile by the same edge share a lane and keep their
 * distance; one won't go on to a junction while another coming in from
 * another way is on it, and none crosses the track while a train is near.
 * Routes are found across the cores, as the trains' are, then the vehicles
 * move in order of their ids.
 */

/** What carries cargo and calls at stations: trains, and road vehicles. */
interface Carrier {
    val id: Int
    val owner: Int
    val cargo: Array<ArrayList<Packet>>
    var profitThisYear: Long
    val orders: ArrayList<Int>
    val order: Int
    fun capacity(c: Int): Int
    /** Its top speed, in km/h, for station ratings. */
    val topSpeed: Int
    /** The work day it was last sent in to be renewed or found its company couldn't pay, and told so since its last renewal. */
    var renewAsked: Int
    var renewFailed: Boolean
}

/** A step of road: [tile], the edge come in at and the edge leaving by. */
fun roadStep(tile: Int, entry: Int, exit: Int) = (tile shl 4) or (entry shl 2) or exit
fun roadStepTile(s: Int) = s ushr 4
fun roadStepEntry(s: Int) = (s shr 2) and 3
fun roadStepExit(s: Int) = s and 3

/** How long a step is, in the trains' units: straight across, round a corner, or turning round at a dead end. */
fun roadStepLength(s: Int): Int = when {
    roadStepEntry(s) == roadStepExit(s) -> 8 * FIX
    roadStepEntry(s) == Track.opposite(roadStepExit(s)) -> 16 * FIX
    else -> 11 * FIX
}

class RoadVehicle(
    val roster: Roster,
    override val id: Int,
    override var owner: Int,
    /** Its place in the roster. */
    val model: Int,
    var depot: Int,
    /** What it's set to carry, a [Cargo] number among those it can, or -1 for its first. */
    val cargoChoice: Int = -1,
) : Carrier {
    var name = ""
    val info get() = roster[model]
    var inDepot = true
    var running = false
    var waiting = 0
    /** The step it's on, and how far along it. */
    var at = 0
    var along = 0
    var speed = 0
    override val cargo = Array(Cargo.COUNT) { ArrayList<Packet>() }
    override var profitThisYear = 0L
    var profitLastYear = 0L
    override val orders = ArrayList<Int>()
    override var order = 0
    var toDepot = false
    var route: IntArray? = null
    var routeAt = 0
    var lostUntil = 0L
    var built = 0
    var serviced = 0
    var reliability = 8500
    var breakdowns = 0
    var brokenFor = 0
    var servicing = false
    var service = true
    /** No depot it could reach for a service: the daily check for one long overdue waits till this tick. */
    var noDepotUntil = 0L
    override var renewAsked = NEVER_ASKED
    override var renewFailed = false
    /** Its tires, a [Tires] kind, and whether it changes them at the garage as the seasons turn. */
    var tires = Tires.SUMMER
    var seasonalTires = true
    /** In the ditch after sliding off an icy road: ticks till it's pulled out. Counted with [brokenFor]. */
    var slid = false
    /** A wreck, with this many ticks left before it's cleared away; 0 for one that's whole. */
    var wreckedFor = 0

    val carries: Int get() = if (cargoChoice in info.cargoes) cargoChoice else info.cargoes.firstOrNull() ?: -1
    override fun capacity(c: Int) = if (c == carries) info.capacity else 0
    override val topSpeed get() = info.speed
    val maxSpeed get() = info.speed * 4096 * 16 / 2700
    val price get() = info.cost

    /** Where it's heading: a station id, -1 for its garage, or -2 for nowhere. */
    val target: Int get() = when {
        toDepot -> -1
        orders.isEmpty() -> -2
        else -> Order.station(orders[order % orders.size])
    }
}

/** Tires on a road vehicle: summer ones slide on ice and snow, winter ones grip better, chains best but slow. */
object Tires {
    const val SUMMER = 0
    const val WINTER = 1
    const val CHAINS = 2
    /** The years winter tires, and chains, are to be had. */
    const val WINTER_FROM = 1935
    const val CHAINS_FROM = 1905
}

/** Whether a road vehicle stands on tile [i]. */
fun World.roadVehicleOn(i: Int) = roadVehicles.any { it != null && !it.inDepot && roadStepTile(it.at) == i }

/** The steps a vehicle coming into [tile] at [entry] could take there: out by any other edge with road, or back the way it came at a dead end. */
fun World.roadWays(tile: Int, entry: Int, tram: Boolean = false): IntArray {
    val edges = roadEdges(tile)
    if (edges and roadBit(entry) == 0) return IntArray(0)
    if (tram && roadKind[tile].toInt() and TRAM == 0) return IntArray(0)
    if (isWater(tile % width, tile / width) && !iceRoadFits(tile)) return IntArray(0)
    val out = ArrayList<Int>(3)
    for (e in 0 until 4) if (e != entry && edges and roadBit(e) != 0 && mayLeave(tile, e)) out += roadStep(tile, entry, e)
    // Turning round where the road ends, or at the end of a one-way that doesn't go on.
    if (out.isEmpty() && edges and roadBit(entry) != 0) out += roadStep(tile, entry, entry)
    return out.toIntArray()
}

/** The tile across [edge] of [tile], or -1 off the map. */
private fun World.roadAcross(tile: Int, edge: Int): Int {
    val x = tile % width + Track.dx(edge)
    val y = tile / width + Track.dy(edge)
    return if (inside(x, y)) index(x, y) else -1
}

/** The steps after [s]: on to the tile across its exit, if the road goes on there. */
fun World.roadNext(s: Int, tram: Boolean = false): IntArray {
    val n = roadAcross(roadStepTile(s), roadStepExit(s))
    if (n < 0) return IntArray(0)
    return roadWays(n, Track.opposite(roadStepExit(s)), tram)
}

/** Buys a road vehicle in a garage. */
internal fun World.buyRoadVehicle(company: Company, c: Command.BuyRoadVehicle) {
    val depot = depots.getOrNull(c.depot) ?: return
    if (depot.owner != company.id || !depot.road) return
    val e = roster.getOrNull(c.model) ?: return
    val year = Calendar.year(startYear, day)
    if (!e.enabled || e.kind != VehicleKind.ROAD || year < e.from || year >= e.until) return
    val v = RoadVehicle(roster, roadVehicles.size, company.id, e.id, depot.id, if (c.cargo in e.cargoes) c.cargo else -1)
    if (company.money < price(v.price)) return
    spend(company, Books.NEW_TRAINS, price(v.price))
    v.built = workDay
    v.serviced = workDay
    v.reliability = e.reliability * 100
    if (year >= Tires.WINTER_FROM && Climate.look(settings.region, year, Calendar.dayOfYear(startYear, day)) == Climate.WINTER) v.tires = Tires.WINTER
    roadVehicles += v
}

internal fun World.roadVehicleCommand(company: Company, c: Command.RoadOrder) {
    val v = roadVehicles.getOrNull(c.vehicle) ?: return
    if (v.owner != company.id || v.wreckedFor > 0) return
    linksDirty = true
    when (c.what) {
        Command.TrainOrder.START_STOP -> if (v.inDepot) { if (v.running) v.running = false else leaveGarage(v) } else v.running = !v.running
        Command.TrainOrder.ORDERS -> {
            v.orders.clear()
            for (o in c.stations) if (stations.getOrNull(Order.station(o))?.owner == company.id) v.orders += o
            v.order = 0
            v.route = null
        }
        Command.TrainOrder.TO_DEPOT -> if (!v.inDepot) { v.toDepot = !v.toDepot; v.servicing = false; v.route = null }
        Command.TrainOrder.TURN -> if (!v.inDepot) turnAround(v)
        Command.TrainOrder.SELL -> if (v.inDepot) {
            earn(company, Books.TRAINS_SOLD, roadValue(v))
            roadVehicles[v.id] = null
        }
        Command.TrainOrder.SERVICE -> v.service = !v.service
        Command.TrainOrder.RENEW -> if (v.inDepot) {
            val cost = price(v.price) - roadValue(v)
            if (company.money < cost) return
            spend(company, Books.NEW_TRAINS, cost)
            v.built = workDay
            v.serviced = workDay
            v.reliability = v.info.reliability * 100
        }
        Command.RoadOrder.TIRES -> v.seasonalTires = !v.seasonalTires
    }
}

/** What road vehicle [v] fetches sold. */
fun World.roadValue(v: RoadVehicle): Long {
    if (v.wreckedFor > 0) return 0
    val age = (workDay - v.built).coerceAtLeast(0) / 365
    return price(v.price) * maxOf(10, 100 - 100 * age / v.info.life.coerceAtLeast(1)) / 200
}

/** Out of the garage onto the road at its door, if nobody's in the way. */
private fun World.leaveGarage(v: RoadVehicle) {
    val d = depots.getOrNull(v.depot) ?: return
    v.running = true
    val out = roadAcross(d.tile, d.edge)
    if (out < 0) return
    if (roadVehicles.any { it != null && it !== v && !it.inDepot && roadStepTile(it.at) == d.tile }) return
    v.at = roadStep(d.tile, d.edge, d.edge)
    // Starting at the back of the garage, halfway through turning out of it.
    v.along = roadStepLength(v.at) / 2
    v.speed = 0
    v.inDepot = false
    v.route = null
}

private fun World.turnAround(v: RoadVehicle) {
    val e = roadStepExit(v.at)
    v.at = roadStep(roadStepTile(v.at), e, roadStepEntry(v.at).let { if (roadWays(roadStepTile(v.at), e).isEmpty()) e else it })
    v.along = roadStepLength(v.at) - v.along.coerceAtMost(roadStepLength(v.at))
    v.route = null
}

/** Moves every road vehicle a tick: routes first, across the cores, then the moves in order. */
internal fun World.moveRoadVehicles() {
    if (roadNetDirty) roadNetDirty = false
    val list = roadVehicles.filterNotNull()
    for (v in list) if (v.inDepot && v.running) leaveGarage(v)
    val planning = list.filter { !it.inDepot && it.wreckedFor == 0 && it.running && it.route == null && tick >= it.lostUntil && it.target != -2 && it.waiting == 0 }
    val routes = arrayOfNulls<IntArray>(planning.size)
    Workers.run(planning.size) { i -> routes[i] = planRoadRoute(planning[i]) }
    for ((i, v) in planning.withIndex()) {
        val r = routes[i]
        if (r == null) {
            // No garage it can reach for its service: straight back to its orders, to try again after its next call.
            if (v.toDepot && v.servicing) {
                v.toDepot = false
                v.servicing = false
                v.noDepotUntil = tick + NO_DEPOT_RETRY
            } else v.lostUntil = tick + LOST_RETRY
        } else {
            // Off for a service, or home gone, it goes to whichever garage its route found, and lives there after.
            if (anyGarage(v) && r.isNotEmpty()) depotTiles[roadStepTile(r.last())]?.let { v.depot = it }
            v.route = r
            v.routeAt = 0
        }
    }
    // Who's on each tile, for keeping apart.
    val on = HashMap<Int, ArrayList<RoadVehicle>>()
    for (v in list) if (!v.inDepot) on.getOrPut(roadStepTile(v.at)) { ArrayList() } += v
    for (v in list) {
        if (v.inDepot) continue
        if (v.wreckedFor > 0) {
            if (--v.wreckedFor == 0) { roadVehicles[v.id] = null; linksDirty = true }
            continue
        }
        driveRoadVehicle(v, on)
    }
    if (settings.collisions) for (v in list) if (!v.inDepot && v.wreckedFor == 0 && v.speed == 0) crossingCrash(v, list)
}

/**
 * With collisions on, road vehicles standing on a level crossing (broken down, queued over it or stopped) are hit
 * by a train that comes onto the crossing, and all are wrecked together. One that's moving is taken to clear it in time.
 */
private fun World.crossingCrash(v: RoadVehicle, list: List<RoadVehicle>) {
    val tile = roadStepTile(v.at)
    if (!crossing(tile) || (tileTrains[tile] ?: 0) <= 0) return
    val hit = trains.filter { it != null && !it.inDepot && it.wreckedFor == 0 && tile in it.onTiles }.map { it!! }
    if (hit.isEmpty()) return
    val road = list.filter { !it.inDepot && it.wreckedFor == 0 && it.speed == 0 && roadStepTile(it.at) == tile }
    for (t in hit) wreck(t)
    for (u in road) wreckRoad(u)
    crashed(tile, hit.map { CrashVehicle(Crash.TRAIN, it.id, it.owner, it.name) to it } + road.map { CrashVehicle(Crash.ROAD, it.id, it.owner, it.name) to it })
}

/** Wrecks road vehicle [v] where it stands, blocking its lane till it's cleared; its orders are let go. */
internal fun World.wreckRoad(v: RoadVehicle) {
    v.wreckedFor = WRECK_TICKS
    v.running = false
    v.speed = 0
    v.waiting = 0
    v.brokenFor = 0
    v.slid = false
    v.toDepot = false
    v.servicing = false
    v.route = null
    v.orders.clear()
    v.order = 0
    linksDirty = true
}

/** Whether [v] has all it can carry, or all the spring load limits let it. */
private fun World.roadFull(v: RoadVehicle): Boolean = (0 until Cargo.COUNT).all { c ->
    v.load(c) >= if (c > Cargo.MAIL && loadLimits()) v.capacity(c) / 2 else v.capacity(c)
}

/** How long a road vehicle is, in the trains' units. */
private fun RoadVehicle.size() = info.length * FIX

private fun World.driveRoadVehicle(v: RoadVehicle, on: HashMap<Int, ArrayList<RoadVehicle>>) {
    if (v.waiting > 0) {
        if (--v.waiting == 0) {
            // A full load order keeps it there, loading as more comes, till it's full, unless it's due at a garage:
            // standing in a drive-through stop it would hold up everyone behind it.
            val o = v.orders.getOrNull(v.order % v.orders.size.coerceAtLeast(1)) ?: 0
            val s = stations.getOrNull(Order.station(o))
            val due = !v.toDepot && hasDepot(v.owner, Depots.ROAD) && (due(v.service, v.serviced) || needsTires(v) || renewNow(v))
            if (Order.has(o, Order.FULL_LOAD) && s != null && !v.toDepot && !due && !roadFull(v)) {
                loadAt(v, s)
                if (!roadFull(v)) {
                    v.waiting = FULL_LOAD_RETRY
                    return
                }
            }
            nextRoadOrder(v)
            v.route = null
            // Due a service or a change of tires: choose a garage on the way to its next order.
            if (due) {
                v.toDepot = true
                v.servicing = true
            }
        }
        return
    }
    if (v.brokenFor > 0) {
        v.brokenFor--
        v.speed = 0
        if (v.brokenFor == 0) v.slid = false
        return
    }
    if (!v.running || v.target == -2) {
        v.speed = (v.speed - BRAKE).coerceAtLeast(0)
        return
    }
    val tile = roadStepTile(v.at)
    val limit = roadLimit(v, tile)
    // Faster up to the limit, by its power against its weight; a road vehicle's acceleration is kept simple.
    val accel = (v.info.power.toLong() * 300 / (v.info.weight.coerceAtLeast(1) * 100)).toInt().coerceIn(4, 60)
    var free = roadFreeAhead(v, on)
    val allowed = isqrt(2L * BRAKE * 16 * free).toInt()
    v.speed = minOf(v.speed + accel, limit, allowed)
    var move = minOf(v.speed / 16, free)
    if (move <= 0) {
        if (free <= 0) v.speed = 0
        arrive(v)
        return
    }
    // Ice under summer tires: now and then it slides off into the ditch.
    if (slippery(tile) && v.tires == Tires.SUMMER && v.info.traction != Traction.HORSE && v.speed > SLIDE_SPEED && rng.below(SLIDE_ODDS) == 0) {
        v.brokenFor = Calendar.TICKS_PER_DAY
        v.slid = true
        v.speed = 0
        tell(News.SLID, v.id)
        return
    }
    while (move > 0) {
        val len = roadStepLength(v.at)
        val step = minOf(move, len - v.along)
        v.along += step
        move -= step
        // At the end of its step it only goes on with distance to spare, which [roadFreeAhead] gives only when the way's clear.
        if (v.along < len || move == 0) break
        val next = nextRoadStep(v) ?: run { v.along = len; v.speed = 0; return }
        on[roadStepTile(v.at)]?.remove(v)
        v.at = next
        v.along = 0
        on.getOrPut(roadStepTile(next)) { ArrayList() } += v
        if (v.route != null) v.routeAt++
        roadWearBy(roadStepTile(next), v)
    }
    arrive(v)
}

/** At the middle of a stop of the station it's heading for, it stops and calls; in its garage it goes in. */
private fun World.arrive(v: RoadVehicle) {
    val tile = roadStepTile(v.at)
    val half = roadStepLength(v.at) / 2
    if (v.along < half) return
    val target = v.target
    if (target >= 0 && roadStops[tile] == target && v.along <= half + MIDDLE_SLACK) {
        val s = stations.getOrNull(target) ?: return
        v.speed = 0
        v.along = half
        val o = v.orders.getOrNull(v.order % v.orders.size.coerceAtLeast(1)) ?: 0
        v.waiting = maxOf(Calendar.TICKS_PER_DAY, Calendar.TICKS_PER_DAY * Order.waitDays(o))
        callAt(v, s, o)
    } else if (target == -1 && depotTiles[tile] == v.depot) {
        v.inDepot = true
        v.running = false
        v.toDepot = false
        v.route = null
        if (v.servicing) {
            autorenew(v)
            v.reliability = v.info.reliability * 100
            v.serviced = workDay
            v.servicing = false
            v.running = true
            fitTires(v)
        }
    }
}

/** The step after the one [v] is on: its route's, or the only way on, or round at a dead end. */
private fun World.nextRoadStep(v: RoadVehicle): Int? {
    val ways = roadNext(v.at, v.info.tram)
    if (ways.isEmpty()) return null
    val r = v.route
    if (r != null && v.routeAt < r.size && r[v.routeAt] in ways) return r[v.routeAt]
    if (r != null) v.route = null
    // Keep straight on if it can, else the first way there is.
    return ways.firstOrNull { roadStepExit(it) == roadStepExit(v.at) } ?: ways[0]
}

/**
 * How far [v] can go before it has to stop: the end of the step if the way on is blocked by a vehicle ahead in
 * its lane, a busy junction or a train at a crossing, short of a vehicle ahead on its own step, and the middle
 * of its stop.
 */
private fun World.roadFreeAhead(v: RoadVehicle, on: HashMap<Int, ArrayList<RoadVehicle>>): Int {
    val tile = roadStepTile(v.at)
    val len = roadStepLength(v.at)
    var free = len - v.along
    for (o in on[tile].orEmpty()) {
        if (o === v || roadStepExit(o.at) != roadStepExit(v.at)) continue
        if (o.along > v.along) free = minOf(free, o.along - v.along - o.size() / 2 - GAP)
    }
    if (free < len - v.along) return free.coerceAtLeast(0)
    // Its stop's middle, if this is its stop.
    if (v.target >= 0 && roadStops[tile] == v.target && v.along < len / 2) return len / 2 - v.along
    val n = roadAcross(tile, roadStepExit(v.at))
    if (n < 0) return free
    // Trains have the crossing.
    if (crossing(n) && trainNear(n)) return free
    // Nor onto a crossing with another just past it that a train has, so it doesn't stand between the tracks.
    if (crossing(n)) {
        val way = v.route?.getOrNull(v.routeAt)?.takeIf { roadStepTile(it) == n }
        val n2 = roadAcross(n, if (way != null) roadStepExit(way) else roadStepExit(v.at))
        if (n2 >= 0 && crossing(n2) && trainNear(n2)) return free
    }
    val entry = Track.opposite(roadStepExit(v.at))
    for (o in on[n].orEmpty()) {
        if (o === v) continue
        // Someone ahead in the same lane, close in.
        if (roadStepEntry(o.at) == entry && o.along < o.size() + GAP) return free
        // On a junction, anyone come in another way holds it, unless they're leaving towards us in the other lane.
        if (roadEdges(n).countOneBits() >= 3 && roadStepEntry(o.at) != entry && roadStepExit(o.at) != entry) return free
    }
    return free + roadStepLength(roadStep(n, entry, Track.opposite(entry)))
}

/** Whether a train is on crossing [tile] or close along its track. */
internal fun World.trainNear(tile: Int): Boolean {
    if (trainOnTile(tile)) return true
    val t = track[tile].toInt()
    val edges = if (t == Track.X) intArrayOf(Track.XM, Track.XP) else intArrayOf(Track.YM, Track.YP)
    for (e in edges) {
        var n = tile
        repeat(CROSSING_LOOK) {
            n = roadAcross(n, e)
            if (n < 0) return@repeat
            if ((tileTrains[n] ?: 0) > 0) return true
        }
    }
    return false
}

/** The fastest [v] may go on [tile]: its own top speed, the road's surface, snow and ice for its tires, potholes. */
internal fun World.roadLimit(v: RoadVehicle, tile: Int): Int {
    var kmh = minOf(v.info.speed, Surface.SPEED[surfaceAt(tile)])
    if (mud(tile) > 60 && surfaceAt(tile) <= Surface.GRAVEL) kmh = minOf(kmh, MUD_KMH)
    val snow = weather.snow[tile].toInt() and 255
    if (slippery(tile) || snow >= 5) kmh = minOf(kmh, when (v.tires) { Tires.CHAINS -> 40; Tires.WINTER -> 55; else -> 35 })
    if (snow >= DEEP_ROAD_SNOW && v.info.plow == Plows.NONE && v.info.traction != Traction.HORSE) kmh = minOf(kmh, 10)
    val c = condition[tile].toInt() and 255
    if (track[tile].toInt() == 0 && c < POTHOLES) kmh = minOf(kmh, POTHOLE_KMH)
    if (crossing(tile)) kmh = minOf(kmh, 40)
    if (underRoadWorks(tile)) kmh = minOf(kmh, ROAD_WORKS_KMH)
    return kmh * 4096 * 16 / 2700
}

/** Whether tile [i]'s road is icy or under packed snow. */
fun World.slippery(i: Int) = (weather.ice[i].toInt() and 255) > 2 || (weather.snow[i].toInt() and 255) in 3..40

/**
 * Wear on a road from a vehicle running onto it: trucks more than buses; plows clear the snow, the rest pack deep snow
 * down a little, one pass in day length, as snow falls by the calendar and vehicles pass by work time.
 */
private fun World.roadWearBy(tile: Int, v: RoadVehicle) {
    val snow = weather.snow[tile].toInt() and 255
    if (v.info.plow != Plows.NONE) {
        weather.snow[tile] = minOf(snow, 2).toByte()
        weather.ice[tile] = 0
    } else if (snow >= DEEP_ROAD_SNOW && (settings.dayLength == 1 || rng.below(settings.dayLength) == 0)) {
        weather.snow[tile] = (snow - PACKED_SNOW).toByte()
    }
    if (track[tile].toInt() != 0) return
    if (rng.below(ROAD_WEAR_ODDS) < v.info.weight) condition[tile] = ((condition[tile].toInt() and 255) - 1).coerceAtLeast(0).toByte()
}

/** On to [v]'s next order, skipping those it has nothing to do at, as trains do. */
private fun World.nextRoadOrder(v: RoadVehicle) {
    if (v.orders.isEmpty()) return
    v.order = (v.order + 1) % v.orders.size
}

/** Whether [v], heading for a garage, may take any of its company's: off for a service, or with its home gone. */
private fun World.anyGarage(v: RoadVehicle) = v.toDepot && (v.servicing || depots.getOrNull(v.depot) == null)

/** Whether [v] wants its tires changed for the season, as [fitTires] would. */
private fun World.needsTires(v: RoadVehicle) =
    v.seasonalTires && v.info.traction != Traction.HORSE && v.tires != wantedTires()

/** Changes [v]'s tires for the season in its garage, if it's set to and they're to be had, for what a set costs. */
private fun World.fitTires(v: RoadVehicle) {
    if (!v.seasonalTires || v.info.traction == Traction.HORSE) return
    val want = wantedTires()
    if (want == v.tires) return
    val c = companies.getOrNull(v.owner) ?: return
    spend(c, Books.RUNNING, price(TIRE_COST))
    v.tires = want
}

/** The tires to be on now: winter ones from late fall to early spring, if they're about, else summer ones. */
internal fun World.wantedTires(): Int {
    val year = Calendar.year(startYear, day)
    val look = lookToday()
    val winter = look == Climate.WINTER || look == Climate.LATE_AUTUMN || look == Climate.EARLY_SPRING
    return when {
        !winter -> Tires.SUMMER
        year >= Tires.WINTER_FROM -> Tires.WINTER
        year >= Tires.CHAINS_FROM -> Tires.CHAINS
        else -> Tires.SUMMER
    }
}

/** Each day: wear and breakdowns, and a trip to a garage for one long overdue a service. */
internal fun World.roadVehiclesDay() {
    for (v in roadVehicles) {
        if (v == null || v.inDepot || v.wreckedFor > 0) continue
        v.reliability = (v.reliability - WEAR_A_DAY).coerceAtLeast(LEAST_RELIABLE / 2)
        if (v.running && v.brokenFor == 0 && v.waiting == 0 && rng.below(BREAKDOWN_ODDS) < 10_000 - v.reliability) {
            v.brokenFor = Calendar.TICKS_PER_DAY / 2
            v.breakdowns++
        }
        // Due ones go after their next call; this catches one that's called nowhere in twice the time.
        if (overdue(v.service, v.serviced) && v.running && !v.toDepot && tick >= v.noDepotUntil && hasDepot(v.owner, Depots.ROAD)) {
            v.toDepot = true
            v.servicing = true
            v.route = null
        }
    }
    // Running costs, as trains pay theirs.
    for (v in roadVehicles) {
        if (v == null || v.inDepot && !v.running || v.wreckedFor > 0) continue
        val c = companies.getOrNull(v.owner) ?: continue
        c.runningOwed += price(v.info.running)
        val today = c.runningOwed / 365
        spend(c, Books.RUNNING, today)
        v.profitThisYear -= today
        c.runningOwed %= 365
    }
}

/**
 * The way from [v] to where it's going: a search over road steps from the one after its own, costing each by
 * its length over the road's top speed, so it keeps to good roads. Stops of its station and its garage are
 * where it can end.
 */
internal fun World.planRoadRoute(v: RoadVehicle): IntArray? {
    val target = v.target
    if (target == -2) return null
    // Off for a service, or with its home gone, it ends at any garage of its company's; sent in, only at its own.
    val anyGarage = anyGarage(v)
    val garage = if (target == -1 && !anyGarage) depots.getOrNull(v.depot)?.tile ?: return null else -1
    fun isGarage(tile: Int) = if (anyGarage) depotAt(tile)?.let { it.owner == v.owner && it.kind == Depots.ROAD } == true else tile == garage
    val tram = v.info.tram
    val nextStop = if (v.servicing && target == -1) v.orders.getOrNull(v.order % v.orders.size.coerceAtLeast(1))?.let { Order.station(it) } else null
    val onward = nextStop?.let { garageOnwardCosts(v, it) }?.takeIf { it.isNotEmpty() }
    val dist = HashMap<Int, Int>()
    val prev = HashMap<Int, Int>()
    val heap = RoadHeap()
    for (s in roadNext(v.at, tram)) { dist[s] = 0; heap.push(0, s) }
    var looked = 0
    var nearest = -1
    var best = -1
    var bestCost = Int.MAX_VALUE
    fun path(goal: Int): IntArray {
        val back = ArrayList<Int>()
        var k = goal
        while (true) {
            back += k
            k = prev[k] ?: break
        }
        back.reverse()
        return back.toIntArray()
    }
    while (heap.size > 0) {
        val top = heap.pop()
        val cost = (top ushr 32).toInt()
        val s = top.toInt()
        if (cost > (dist[s] ?: Int.MAX_VALUE)) continue
        val tile = roadStepTile(s)
        if (cost >= bestCost) break
        if ((target >= 0 && roadStops[tile] == target) || target == -1 && isGarage(tile)) {
            if (onward == null) return path(s)
            if (nearest < 0) nearest = s
            onward[s]?.let { after ->
                if (cost + after < bestCost) { best = s; bestCost = cost + after }
            }
            continue
        }
        if (++looked > MAX_ROAD_LOOK) return if (onward != null && nearest >= 0) path(if (best >= 0) best else nearest) else null
        for (n in roadNext(s, tram)) {
            val nt = roadStepTile(n)
            // Garages only as the end of the way; other companies' stops are just road.
            if (depotTiles.containsKey(nt) && !(target == -1 && isGarage(nt))) continue
            val c = cost + roadRouteCost(n)
            if (c < (dist[n] ?: Int.MAX_VALUE)) {
                dist[n] = c
                prev[n] = s
                heap.push(c, n)
            }
        }
    }
    return if (nearest >= 0) path(if (best >= 0) best else nearest) else null
}

private fun World.roadRouteCost(s: Int) = roadStepLength(s) / FIX * 110 / Surface.SPEED[surfaceAt(roadStepTile(s))] +
    if (roadStepEntry(s) == roadStepExit(s)) U_TURN else 0

/** Costs from each garage out to the next stop, following the roads backwards with their usual turn and speed costs. */
private fun World.garageOnwardCosts(v: RoadVehicle, station: Int): Map<Int, Int> {
    val pending = depots.filterNotNull().filter { it.owner == v.owner && it.road }
        .map { roadStep(it.tile, it.edge, it.edge) }.toHashSet()
    val dist = HashMap<Int, Int>()
    val result = HashMap<Int, Int>()
    val heap = RoadHeap()
    for (tile in stations.getOrNull(station)?.stops.orEmpty()) {
        for (entry in 0 until 4) for (s in roadWays(tile, entry, v.info.tram)) {
            dist[s] = 0
            heap.push(0, s)
        }
    }
    var looked = 0
    while (heap.size > 0 && pending.isNotEmpty()) {
        val top = heap.pop()
        val cost = (top ushr 32).toInt()
        val s = top.toInt()
        if (cost > (dist[s] ?: Int.MAX_VALUE)) continue
        if (pending.remove(s)) result[s] = cost
        val tile = roadStepTile(s)
        // A garage can start a journey, but cannot be driven through.
        if (depotTiles.containsKey(tile)) continue
        if (++looked > MAX_ROAD_LOOK) break
        val before = roadAcross(tile, roadStepEntry(s))
        if (before < 0) continue
        val exit = Track.opposite(roadStepEntry(s))
        for (entry in 0 until 4) for (p in roadWays(before, entry, v.info.tram)) {
            if (roadStepExit(p) != exit) continue
            val c = cost + roadRouteCost(s)
            if (c < (dist[p] ?: Int.MAX_VALUE)) {
                dist[p] = c
                heap.push(c, p)
            }
        }
    }
    // Only settled garage costs are used if the search reached its limit.
    return result
}

/** A min-heap of costs and road steps packed into longs. */
private class RoadHeap {
    private var a = LongArray(64)
    var size = 0
        private set

    fun push(cost: Int, node: Int) {
        if (size == a.size) a = a.copyOf(size * 2)
        var i = size++
        a[i] = (cost.toLong() shl 32) or (node.toLong() and 0xffffffffL)
        while (i > 0) {
            val p = (i - 1) / 2
            if (a[p] <= a[i]) break
            val t = a[p]; a[p] = a[i]; a[i] = t
            i = p
        }
    }

    fun pop(): Long {
        val top = a[0]
        a[0] = a[--size]
        var i = 0
        while (true) {
            val l = i * 2 + 1
            val r = l + 1
            var m = i
            if (l < size && a[l] < a[m]) m = l
            if (r < size && a[r] < a[m]) m = r
            if (m == i) break
            val t = a[m]; a[m] = a[i]; a[i] = t
            i = m
        }
        return top
    }
}

/** Where road vehicle [v] is, in tiles, on the right of its lane: x, y and the way it faces, in eighths. */
fun World.roadPosition(v: RoadVehicle): FloatArray {
    val tile = roadStepTile(v.at)
    val a = roadStepEntry(v.at)
    val b = roadStepExit(v.at)
    val t = v.along.toFloat() / roadStepLength(v.at)
    val cx = tile % width + 0.5f
    val cy = tile / width + 0.5f
    val ax = tile % width + Track.edgeX4(a) / 4f
    val ay = tile / width + Track.edgeY4(a) / 4f
    val bx = tile % width + Track.edgeX4(b) / 4f
    val by = tile / width + Track.edgeY4(b) / 4f
    // Turning a corner: round the quarter circle the road sweeps, about the corner its two edges share.
    if (a != b && a != Track.opposite(b)) {
        val ex = if (a == Track.XM || a == Track.XP) a else b
        val ey = if (a == Track.YM || a == Track.YP) a else b
        val ox = tile % width + Track.edgeX4(ex) / 4f
        val oy = tile / width + Track.edgeY4(ey) / 4f
        val sx = ax - ox; val sy = ay - oy
        val fx = bx - ox; val fy = by - oy
        val angle = t * (kotlin.math.PI / 2).toFloat()
        val c = kotlin.math.cos(angle)
        val s = kotlin.math.sin(angle)
        var x = ox + sx * c + fx * s
        var y = oy + sy * c + fy * s
        val dx = -sx * s + fx * c
        val dy = -sy * s + fy * c
        val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
        x += -dy / len * LANE
        y += dx / len * LANE
        return floatArrayOf(x, y, heading(kotlin.math.round(dx * 4 / len).toInt(), kotlin.math.round(dy * 4 / len).toInt()).toFloat())
    }
    // Turning round: into the middle and back out.
    val (x0, y0, x1, y1, f) = if (a == b) {
        if (t < 0.5f) listOf(ax, ay, cx, cy, t * 2) else listOf(cx, cy, bx, by, t * 2 - 1)
    } else listOf(ax, ay, bx, by, t)
    var x = x0 + (x1 - x0) * f
    var y = y0 + (y1 - y0) * f
    var dx = x1 - x0
    var dy = y1 - y0
    if (a == b && t >= 0.5f) { dx = bx - cx; dy = by - cy }
    val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
    // Keep right: to the right of the way it's going is (-dy, dx) turned, as y runs down the map.
    x += -dy / len * LANE
    y += dx / len * LANE
    return floatArrayOf(x, y, heading(kotlin.math.round(dx * 4).toInt(), kotlin.math.round(dy * 4).toInt()).toFloat())
}

/** How far right of the road's middle vehicles keep, in tiles. */
const val LANE = 0.11f
/** Room a vehicle leaves to the one ahead, in the trains' units. */
const val GAP = 2 * FIX
/** How far past a stop's middle a vehicle still counts as at it. */
const val MIDDLE_SLACK = FIX
/** Tiles each way along the track a crossing looks for trains. */
const val CROSSING_LOOK = 3
/** Speed limits in km/h: in mud on a dirt or gravel road, on a road full of potholes. */
const val MUD_KMH = 20
const val POTHOLE_KMH = 30
/** A road's condition below which it has potholes. */
const val POTHOLES = 90
/** Snow, in cm, too deep for anything but a plow to get through at more than a crawl. */
const val DEEP_ROAD_SNOW = 40
/** Deep snow, in cm, packed down by each vehicle that runs onto a tile. */
const val PACKED_SNOW = 6
/** Above this speed, in the trains' units, summer tires on ice can slide off: one time in [SLIDE_ODDS] a tick. */
const val SLIDE_SPEED = 600
const val SLIDE_ODDS = 3000
/** One time in this many, times a vehicle's weight in tonnes, a road wears a point. */
const val ROAD_WEAR_ODDS = 4000
/** What turning round adds to a road route, and how far a search looks. */
const val U_TURN = 40
const val MAX_ROAD_LOOK = 100_000
/** A set of tires, in dollars before inflation. */
const val TIRE_COST = 40L
/** A tram rail in a road tile's [World.roadKind]. */
const val TRAM = 16
