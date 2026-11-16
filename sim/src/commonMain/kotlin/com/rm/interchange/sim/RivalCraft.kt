package com.rm.interchange.sim

/*
 * Shipping lines and airlines run by the computer. A shipping line joins two
 * industries or towns on the same natural water with a dock at each end and a
 * shipyard by the first; whether the water joins up is found a little each
 * tick, as railways are planned. An airline joins two bigger towns far apart
 * with an airport at each, the biggest kind that pays, fits and it can afford.
 */

/** A shipping line a rival would try: from [from] to [to] (industries, or towns if [town]) carrying [cargo]. */
internal class ShipPick(
    val from: Int, val to: Int, val cargo: Int, val town: Boolean,
    /** Where the first dock goes, a land tile; where the second can go, and the water each calls at. */
    val site: Int, val sites: IntArray, val berths: IntArray,
)

/**
 * The shipping lines a rival is trying in turn, [pick] the one under way: a breadth-first look over open water from
 * the first dock's berth, [depth] steps out, for one of the second's. [queue] holds every tile reached; those from
 * [head] are still to look from, those before [layerEnd] are [depth] steps out.
 */
internal class ShipJob(val picks: List<ShipPick>, val payback: Int) {
    var running = -1
    var from = 0
    var opensLeft = 0
    var pick = 0
    var queue: IntArray? = null
    var head = 0
    var tail = 0
    var depth = 0
    var layerEnd = 0
    /** Tiles reached, a bit each, packed eight to a byte. */
    var seen: ByteArray? = null
    /** Tiles it may still look at this tick, and how many it looked at last tick; not saved. */
    var left = 0
    var spent = 0

    fun nextPick() {
        pick++
        queue = null
        seen = null
        head = 0
        tail = 0
        depth = 0
        layerEnd = 0
    }
}

private fun ByteArray.has(i: Int) = this[i shr 3].toInt() and (1 shl (i and 7)) != 0
private fun ByteArray.mark(i: Int) { this[i shr 3] = (this[i shr 3].toInt() or (1 shl (i and 7))).toByte() }

/** Whether ships may cross tile [i] as a rival plans: open water at sea level, no industry on it. Canals and locks aren't. */
private fun World.openWater(i: Int) = isWater(i % width, i / width) && industryOf[i].toInt() == 0

/** The water dock [site] would face, or -1 if it can't go there or faces anything but open water. */
private fun World.berthOf(c: Company, site: Int): Int {
    val x = site % width
    val y = site / width
    if (!clearLand(site) || townRefuses(c, x, y)) return -1
    val e = waterSide(x, y)
    if (e < 0) return -1
    val b = index(x + Track.dx(e), y + Track.dy(e))
    return if (openWater(b) && !buoys.containsKey(b)) b else -1
}

/** Land tiles from ([x0], [y0]) to ([x1], [y1]) a dock could go on, facing open water, nearest ([cx], [cy]) first. */
private fun World.dockSites(c: Company, x0: Int, y0: Int, x1: Int, y1: Int, cx: Int, cy: Int): List<Int> {
    val out = ArrayList<Int>()
    for (y in maxOf(0, y0)..minOf(height - 1, y1)) for (x in maxOf(0, x0)..minOf(width - 1, x1)) {
        val i = index(x, y)
        if (berthOf(c, i) >= 0) out += i
    }
    out.sortWith(compareBy<Int> { chebyshev(it % width, it / width, cx, cy) }.thenBy { it })
    return out
}

private fun World.dockSites(c: Company, ind: Industry): List<Int> {
    val kind = roster.industries[ind.kind]
    val (cx, cy) = middle(ind)
    val r = CATCHMENT - 1
    return dockSites(c, ind.x - r, ind.y - r, ind.x + kind.across(ind.turn) - 1 + r, ind.y + kind.along(ind.turn) - 1 + r, cx, cy)
}

private fun World.dockSites(c: Company, t: Town): List<Int> =
    dockSites(c, t.x - SHIP_TOWN_REACH, t.y - SHIP_TOWN_REACH, t.x + SHIP_TOWN_REACH, t.y + SHIP_TOWN_REACH, t.x, t.y)

/** The ships on sale for [cargo]. */
private fun World.shipsFor(cargo: Int): List<Engine> =
    roster.availableOf(VehicleKind.SHIP, Calendar.year(startYear, day)).filter { cargo in it.cargoes && it.capacity > 0 }

/**
 * Whether a freight ship [e] fills from [load] a month within [SHIP_FILL_MONTHS]; one that takes longer earns in some
 * years and not others and is sold as a loser.
 */
private fun fills(e: Engine, load: Int) = e.capacity <= load * SHIP_FILL_MONTHS

/** Days ship [e] takes there and back over [tiles] of water, and for freight to fill from [load] a month. */
private fun cycle(e: Engine, town: Boolean, load: Int, tiles: Int): Int =
    2 * (tiles * KMH_PER_TILE_A_DAY / e.speed.coerceAtLeast(1) + SHIP_ENDS) + if (town) 0 else e.capacity * 30 / load.coerceAtLeast(1)

/** The ship on sale for [cargo] that carries the most the fastest, or null. */
internal fun World.bestShip(cargo: Int): Engine? =
    shipsFor(cargo).maxWithOrNull(compareBy<Engine> { it.capacity.toLong() * it.speed }.thenBy { -it.id })

/**
 * The shipping lines [r] would try, best first, as a job; null if there are none. Freight goes from an industry to the
 * nearest on the water that takes it, passengers between towns on the water; a rival that leans to freight scores
 * freight twice, else passengers. Nearer home or its stations scores more.
 */
internal fun World.shipJob(r: Rival, c: Company, payback: Int): ShipJob? {
    // Water may be far from home: its first line can be anywhere.
    val reachMost = if (r.routes.none { it.ship }) Int.MAX_VALUE else homeReach(c)
    val home = towns.getOrNull(r.home) ?: return null
    val served = r.routes.filter { it.ship }.map { keyOf(it) }.toSet()
    val most = SHIP_MAX * (2 + r.far) / 3
    class Pick(val p: ShipPick, val score: Long)
    val picks = ArrayList<Pick>()
    val sitesOf = HashMap<Int, List<Int>>()
    fun sites(ind: Industry) = sitesOf.getOrPut(ind.id) { dockSites(c, ind) }
    fun pick(from: Int, to: Int, cg: Int, town: Boolean, a: List<Int>, b: List<Int>): ShipPick {
        val berths = b.map { berthOf(c, it) }
        return ShipPick(from, to, cg, town, a.first(), b.toIntArray(), berths.toIntArray())
    }
    for (ind in industries) {
        if (ind.closed) continue
        val kind = roster.industries[ind.kind]
        val (cx, cy) = middle(ind)
        val fromHome = reach(r, home, cx, cy)
        if (fromHome > reachMost) continue
        for (cg in 0 until cargoCount) {
            val month = kind.produces[cg] * ind.level / 16
            if (month <= 0 || shipsFor(cg).none { fills(it, month / 2) } || servedByOthers(ind, cg, c.id)) continue
            if (sites(ind).isEmpty()) break
            // The nearest that takes it on the water.
            var to: Industry? = null
            var best = Int.MAX_VALUE
            for (o in industries) {
                if (o.closed || o.id == ind.id || !roster.industries[o.kind].takes(cg)) continue
                val (ox, oy) = middle(o)
                val d = chebyshev(cx, cy, ox, oy)
                if (d < SHIP_MIN || d > most || d >= best) continue
                val key = shipKey(ind.id, o.id, cg)
                if (key in served || triedLately(r, key) || sites(o).isEmpty()) continue
                to = o
                best = d
            }
            if (to == null) continue
            val score = month.toLong() * 1000 / (best / 2 + fromHome / 2 + 5) * (if (r.freight) 2 else 1)
            picks += Pick(pick(ind.id, to.id, cg, false, sites(ind), sites(to)), score)
        }
    }
    if (bestShip(Cargo.PASSENGERS) != null) {
        val townSites = HashMap<Int, List<Int>>()
        fun sites(t: Town) = townSites.getOrPut(t.id) { dockSites(c, t) }
        for (t1 in towns) {
            if (t1.population < SHIP_TOWN || leaveAlone(t1)) continue
            val fromHome = reach(r, home, t1.x, t1.y)
            if (fromHome > reachMost) continue
            for (t2 in towns) {
                if (t2.id == t1.id || t2.population < SHIP_TOWN || leaveAlone(t2)) continue
                val d = chebyshev(t1.x, t1.y, t2.x, t2.y)
                if (d < SHIP_MIN || d > most) continue
                // Each pair once, from the end nearer home.
                if (t2.id < t1.id && reach(r, home, t2.x, t2.y) <= reachMost) continue
                val key = shipKey(minOf(t1.id, t2.id), maxOf(t1.id, t2.id), Cargo.PASSENGERS)
                if (key in served || triedLately(r, key)) continue
                if (sites(t1).isEmpty() || sites(t2).isEmpty()) continue
                val score = minOf(t1.population, t2.population).toLong() * 1000 / (d / 2 + fromHome / 2 + 5) * (if (r.freight) 1 else 2)
                val (a, b) = if (t1.id < t2.id) t1 to t2 else t2 to t1
                picks += Pick(pick(a.id, b.id, Cargo.PASSENGERS, true, sites(a), sites(b)), score)
            }
        }
    }
    if (picks.isEmpty()) return null
    picks.sortWith(compareByDescending<Pick> { it.score }.thenBy { it.p.from }.thenBy { it.p.to }.thenBy { it.p.cargo })
    return ShipJob(picks.take(SHIP_TRIES).map { it.p }, payback)
}

/**
 * Carries on [job] for [r] until a line is built, every pick is given up (each remembered in [Rival.failed]) or it
 * can't be paid for; null once [ShipJob.left] runs out first.
 */
internal fun World.runShipJob(r: Rival, c: Company, job: ShipJob): Tried? {
    val budget = job.left
    try {
        while (job.pick < job.picks.size) {
            val p = job.picks[job.pick]
            val found = lookForWater(c, job, p)
            if (found == -1) return null
            val t = if (found >= 0) buildShipLine(r, c, p, found, job) else Tried.CANT
            if (t != Tried.CANT) return t
            giveUp(r, shipKey(p.from, p.to, p.cargo))
            job.nextPick()
        }
        return Tried.CANT
    } finally {
        job.spent = budget - job.left
    }
}

/**
 * The breadth-first look for [p], for up to [ShipJob.left] tiles: the place in [ShipPick.berths] reached, -1 if it
 * isn't done, or -2 if the water doesn't join up within [SHIP_LOOK] tiles or [SHIP_STEPS] steps.
 */
private fun World.lookForWater(c: Company, job: ShipJob, p: ShipPick): Int {
    val tiles = width * height
    if (job.queue == null) {
        val start = berthOf(c, p.site)
        if (start < 0) return -2
        job.queue = IntArray(64).also { it[0] = start }
        job.seen = ByteArray((tiles + 7) / 8).also { it.mark(start) }
        job.head = 0
        job.tail = 1
        job.depth = 0
        job.layerEnd = 1
    }
    var queue = job.queue!!
    val seen = job.seen!!
    val goals = HashMap<Int, Int>()
    for ((k, b) in p.berths.withIndex()) if (b >= 0 && b !in goals) goals[b] = k
    while (job.left > 0) {
        if (job.head >= job.tail || job.tail > SHIP_LOOK) return -2
        if (job.head == job.layerEnd) {
            job.depth++
            job.layerEnd = job.tail
            if (job.depth > SHIP_STEPS) return -2
        }
        val a = queue[job.head++]
        job.left--
        goals[a]?.let { return it }
        val x = a % width
        val y = a / width
        for (e in 0 until 4) {
            val nx = x + Track.dx(e)
            val ny = y + Track.dy(e)
            if (!inside(nx, ny)) continue
            val b = index(nx, ny)
            if (seen.has(b) || !openWater(b)) continue
            seen.mark(b)
            if (job.tail == queue.size) { queue = queue.copyOf(queue.size * 2); job.queue = queue }
            queue[job.tail++] = b
        }
    }
    return -1
}

/**
 * Builds the shipping line [p] with its second dock at place [k] of its sites, the water found [ShipJob.depth] steps
 * across, if it'll pay within the job's payback and [c] can pay for it: two docks, a shipyard by the first, and a ship.
 */
private fun World.buildShipLine(r: Rival, c: Company, p: ShipPick, k: Int, job: ShipJob): Tried {
    val siteB = p.sites[k]
    if (berthOf(c, p.site) < 0 || berthOf(c, siteB) < 0) return Tried.CANT
    // An industry at either end may have closed while the look went on.
    if (!p.town && (industries.getOrNull(p.from)?.closed != false || industries.getOrNull(p.to)?.closed != false)) return Tried.CANT
    // A shipyard by the first dock, onto water the look reached.
    val seen = job.seen ?: return Tried.CANT
    var yard = -1
    var yardD = Int.MAX_VALUE
    val sx = p.site % width
    val sy = p.site / width
    for (y in sy - SHIPYARD_REACH..sy + SHIPYARD_REACH) for (x in sx - SHIPYARD_REACH..sx + SHIPYARD_REACH) {
        if (!inside(x, y)) continue
        val i = index(x, y)
        if (i == p.site || i == siteB) continue
        val b = berthOf(c, i)
        val d = chebyshev(x, y, sx, sy)
        if (b < 0 || !seen.has(b) || d >= yardD) continue
        yard = i
        yardD = d
    }
    if (yard < 0) return Tried.CANT
    val load = if (p.town) {
        minOf(towns[p.from].population, towns[p.to].population) / 3
    } else {
        val from = industries[p.from]
        roster.industries[from.kind].produces[p.cargo] * from.level / 16 / 2
    }
    val dist = kotlin.math.abs(sx - siteB % width) + kotlin.math.abs(sy - siteB / width)
    val built = price(DOCK_COST) * 2 + price(SHIPYARD_COST)
    // The ship that pays best, if any does.
    var model: Engine? = null
    var margin = 0L
    for (e in shipsFor(p.cargo)) {
        if (!p.town && !fills(e, load) || cycle(e, p.town, load, job.depth) > SHIP_CYCLE) continue
        val m = craftMargin(e, p.cargo, load, job.depth, dist, built, 2L * Upkeep.DOCK, job.payback, MAX_SHIPS, SHIP_ENDS)
        if (m >= margin) { model = e; margin = m }
    }
    if (model == null) return Tried.CANT
    val cost = built + price(model.cost)
    if (!afford(c, cost * 6 / 5)) return Tried.POOR
    apply(Command.Dock(c.id, sx, sy))
    val a = docks[p.site]?.takeIf { owner[p.site].toInt() == c.id } ?: return Tried.CANT
    apply(Command.Dock(c.id, siteB % width, siteB / width))
    val b = docks[siteB]?.takeIf { owner[siteB].toInt() == c.id } ?: return undoCraft(c, p.site)
    apply(Command.Shipyard(c.id, yard % width, yard / width))
    val depot = depotTiles[yard]?.takeIf { owner[yard].toInt() == c.id } ?: return undoCraft(c, p.site, siteB)
    if (catchmentDirty) workOutCatchment()
    // Docks that join one station, or don't reach what they're for, won't do.
    val reaches = if (p.town) (stationPeople[a] ?: 0) > 0 && (stationPeople[b] ?: 0) > 0
    else stationIndustries[a]?.contains(p.from) == true && stationIndustries[b]?.contains(p.to) == true
    if (a == b || !reaches) return undoCraft(c, p.site, siteB, yard)
    val route = RivalRoute(a, b, depot, p.from, p.to, p.cargo, craft = RivalRoute.SHIP)
    r.routes += route
    buyShipOn(c, route, model)
    return Tried.BUILT
}

/** Takes down what was built on [tiles]: a shipping line or airline that won't go after all. */
private fun World.undoCraft(c: Company, vararg tiles: Int): Tried {
    for (i in tiles) clearTile(c, Command.Clear(c.id, i % width, i / width))
    return Tried.CANT
}

/** Buys a [model] ship at [route]'s shipyard and sends it off: freight waits for a full load and comes back empty. */
private fun World.buyShipOn(c: Company, route: RivalRoute, model: Engine) {
    val before = ships.size
    apply(Command.BuyCraft(c.id, route.depot, model.id, route.cargo))
    if (ships.size == before) return
    val id = ships.size - 1
    val orders = if (route.freight) intArrayOf(route.a or Order.FULL_LOAD, route.b or Order.NO_LOAD) else intArrayOf(route.a, route.b)
    apply(Command.CraftOrder(c.id, false, id, Command.TrainOrder.ORDERS, orders))
    apply(Command.CraftOrder(c.id, false, id, Command.TrainOrder.START_STOP))
}

/** Buys a [model] aircraft at [route]'s hangar carrying [cargo] and sends it off. */
private fun World.buyAircraftOn(c: Company, route: RivalRoute, model: Engine, cargo: Int) {
    val before = aircraft.size
    apply(Command.BuyCraft(c.id, route.depot, model.id, cargo))
    if (aircraft.size == before) return
    val id = aircraft.size - 1
    apply(Command.CraftOrder(c.id, true, id, Command.TrainOrder.ORDERS, intArrayOf(route.a, route.b)))
    apply(Command.CraftOrder(c.id, true, id, Command.TrainOrder.START_STOP))
}

/**
 * What a line of ships or aircraft is expected to make past paying for itself within [payback] work years, below 0 if
 * it won't: as many [model]s as it takes, up to [most], carrying [cargo] [tiles] each way, paid by [dist] tiles, about
 * [load] a month (each way for passengers), with [built] spent and [upkeep] tenths of a cent a work day, losing
 * [ends] days at each end. Freight waits on board for a full load, half of it the whole time it takes to fill.
 */
internal fun World.craftMargin(model: Engine, cargo: Int, load: Int, tiles: Int, dist: Int, built: Long, upkeep: Long, payback: Int, most: Int, ends: Int): Long {
    if (payback <= 0) return Long.MAX_VALUE
    // A tile a day at 40-odd km/h.
    val there = tiles * KMH_PER_TILE_A_DAY / model.speed.coerceAtLeast(1) + ends
    val cap = model.capacity.coerceAtLeast(1)
    // Freight waits for a full load each trip, about a month for each month's load it holds.
    val fill = if (cargo == Cargo.PASSENGERS) 0 else cap * 30 / load.coerceAtLeast(1)
    val trips = (365 / (there * 2 + fill).coerceAtLeast(1)).coerceAtLeast(1)
    val wanted = load * 12
    val n = ((wanted + trips * cap - 1) / (trips * cap)).coerceIn(1, most)
    val hauled = minOf(n * trips * cap, wanted)
    val filling = if (cargo == Cargo.PASSENGERS) 0 else cap * 15 / load.coerceAtLeast(1)
    val late = (there + filling - dist * roster.cargo.pace[cargo] - roster.cargo.grace[cargo]).coerceAtLeast(0)
    val share = (100 - late * roster.cargo.decay[cargo]).coerceAtLeast(33)
    val ways = if (cargo == Cargo.PASSENGERS) 2 else 1
    val income = pay(hauled.toLong() * dist * roster.cargo.rate[cargo] * share / 10_000) * ways
    val up = price(upkeep * 365 / 1_000)
    return (income - price(model.running) * n - up) * payback - (built + price(model.cost) * n)
}

/** Adds a ship to [route] once a full load waits for one, while what it runs pays. */
internal fun World.growShips(c: Company, route: RivalRoute) {
    val a = stations.getOrNull(route.a) ?: return
    val b = stations.getOrNull(route.b) ?: return
    if (depots.getOrNull(route.depot) == null) return
    val on = ships.filter { it != null && it.owner == c.id && onRoute(it, route) }.map { it!! }
    if (on.size >= MAX_SHIPS || on.any { it.toDepot && !it.servicing || ranLastYear(it.built) && it.profitLastYear < 0 }) return
    if (on.sumOf { it.profitLastYear } <= 0 && (workedThisYear() < FEW_MONTHS || on.sumOf { it.profitThisYear } <= 0)) return
    val model = on.firstOrNull()?.info?.takeIf { it.id in shipsFor(route.cargo).map { e -> e.id } } ?: bestShip(route.cargo) ?: return
    val waiting = if (route.freight) a.waiting(route.cargo) else maxOf(a.waiting(route.cargo), b.waiting(route.cargo))
    if (waiting < model.capacity) return
    if (!afford(c, price(model.cost) * 2)) return
    buyShipOn(c, route, model)
}

/** The aircraft on sale for [cargo] that land at airports of [kind] and fly [km]. */
private fun World.aircraftFor(cargo: Int, kind: Int, km: Long): List<Engine> =
    roster.availableOf(VehicleKind.AIRCRAFT, Calendar.year(startYear, day)).filter {
        cargo in it.cargoes && it.capacity > 0 && it.runway != Runways.HELI && Airports.CLASS[kind] >= it.runway && (it.range <= 0 || it.range >= km)
    }

/** Of [aircraftFor], the one that carries the most the fastest. */
private fun World.bestAircraft(cargo: Int, kind: Int, km: Long): Engine? =
    aircraftFor(cargo, kind, km).maxWithOrNull(compareBy<Engine> { it.capacity.toLong() * it.speed }.thenBy { -it.id })

/**
 * Adds an aircraft to [route] when people are left behind, and one for mail when a rival that carries it has a
 * load of mail waiting; and landing aids and de-icing at both ends once it runs a few.
 */
internal fun World.growAir(c: Company, route: RivalRoute) {
    val a = stations.getOrNull(route.a) ?: return
    val b = stations.getOrNull(route.b) ?: return
    val pa = airportOf(route.a) ?: return
    val pb = airportOf(route.b) ?: return
    val r = rivals.firstOrNull { it.company == c.id } ?: return
    val on = aircraft.filter { it != null && it.owner == c.id && onRoute(it, route) }.map { it!! }
    if (on.any { it.toDepot && !it.servicing || ranLastYear(it.built) && it.profitLastYear < 0 }) return
    if (on.sumOf { it.profitLastYear } <= 0 && (workedThisYear() < FEW_MONTHS || on.sumOf { it.profitThisYear } <= 0)) return
    val year = Calendar.year(startYear, day)
    // Landing aids at a line of two or more, de-icing at three, from money in hand.
    for (p in listOf(pa, pb)) {
        if (on.size >= 2 && !p.ils && year >= ILS_FROM && Airports.CLASS[p.kind] >= Runways.REGIONAL && inHand(c, price(ILS_COST) * 2)) apply(Command.AirportUpgrade(c.id, p.id, 0))
        if (on.size >= 3 && !p.deice && year >= DEICE_FROM && settings.weather && inHand(c, price(DEICE_COST) * 2)) apply(Command.AirportUpgrade(c.id, p.id, 1))
    }
    if (on.size >= MAX_AIRCRAFT) return
    val km = flightKm(pa, pb)
    val kind = minOf(pa.kind, pb.kind)
    val fits = aircraftFor(Cargo.PASSENGERS, kind, km)
    val model = on.firstOrNull { it.carries == Cargo.PASSENGERS }?.info?.takeIf { e -> fits.any { it.id == e.id } } ?: bestAircraft(Cargo.PASSENGERS, kind, km) ?: return
    val people = maxOf(a.waiting(Cargo.PASSENGERS), b.waiting(Cargo.PASSENGERS))
    if (people >= (model.capacity * 2).coerceAtLeast(10)) {
        if (afford(c, price(model.cost) * 2)) buyAircraftOn(c, route, model, Cargo.PASSENGERS)
        return
    }
    if (!r.freight || on.any { it.carries == Cargo.MAIL }) return
    val mail = bestAircraft(Cargo.MAIL, kind, km) ?: return
    if (maxOf(a.waiting(Cargo.MAIL), b.waiting(Cargo.MAIL)) >= mail.capacity.coerceAtLeast(5) && afford(c, price(mail.cost) * 2)) buyAircraftOn(c, route, mail, Cargo.MAIL)
}

/** How far apart airports [a] and [b] are, in km. */
private fun World.flightKm(a: Airport, b: Airport): Long = flightKm(a.x, a.y, b.x, b.y)

/** How far apart airports with corners at ([ax], [ay]) and ([bx], [by]) are, in km, as an aircraft's range is checked. */
private fun flightKm(ax: Int, ay: Int, bx: Int, by: Int): Long {
    val dx = (ax - bx).toLong()
    val dy = (ay - by).toLong()
    return isqrt(dx * dx + dy * dy) * TILE_METRES / 1000
}

/** Whether a company other than [company] has an airport within twice [AIR_TOWN_REACH] of town [t]. */
private fun World.airportNear(company: Int, t: Town) =
    airports.any { it != null && it.owner != company && chebyshev(it.x, it.y, t.x, t.y) <= AIR_TOWN_REACH * 2 }

/**
 * Tries the most promising pairs of bigger towns at least [AIR_MIN] tiles apart (more for a rival that likes them far)
 * with no other company's airport at both, nearest home or its stations first.
 */
internal fun World.newAirRoute(r: Rival, c: Company, payback: Int): Tried {
    val year = Calendar.year(startYear, day)
    val kinds = Airports.entries(year).filter { it != Airports.SEAPLANE && it != Airports.HELIPORT }.sortedDescending()
    if (kinds.isEmpty()) return Tried.CANT
    val reachMost = homeReach(c)
    val home = towns.getOrNull(r.home) ?: return Tried.CANT
    val served = r.routes.filter { it.air }.map { keyOf(it) }.toSet()
    val least = AIR_MIN * (2 + r.far) / 3
    class Pair2(val t1: Town, val t2: Town, val d: Int, val score: Long)
    val pairs = ArrayList<Pair2>()
    for (t1 in towns) {
        if (t1.population < AIR_TOWN || leaveAlone(t1) || townRefuses(c, t1.x, t1.y)) continue
        val fromHome = reach(r, home, t1.x, t1.y)
        if (fromHome > reachMost) continue
        for (t2 in towns) {
            if (t2.id == t1.id || t2.population < AIR_TOWN || leaveAlone(t2) || townRefuses(c, t2.x, t2.y)) continue
            val d = chebyshev(t1.x, t1.y, t2.x, t2.y)
            if (d < least) continue
            if (t2.id < t1.id && reach(r, home, t2.x, t2.y) <= reachMost) continue
            val key = airKey(t1.id, t2.id)
            if (key in served || triedLately(r, key) || airportNear(c.id, t1) && airportNear(c.id, t2)) continue
            val score = minOf(t1.population, t2.population).toLong() * minOf(d, AIR_BEST) * 100 / (fromHome + 10)
            pairs += Pair2(t1, t2, d, if (onOffer(Cargo.PASSENGERS, t1.id, t2.id, true)) score * 2 else score)
        }
    }
    pairs.sortWith(compareByDescending<Pair2> { it.score }.thenBy { minOf(it.t1.id, it.t2.id) }.thenBy { maxOf(it.t1.id, it.t2.id) })
    for (p in pairs.take(AIR_TRIES)) {
        when (val t = tryAir(r, c, p.t1, p.t2, p.d, kinds, payback)) {
            Tried.CANT -> giveUp(r, airKey(p.t1.id, p.t2.id))
            else -> return t
        }
    }
    return Tried.CANT
}

/** Where an airport of [kind] can go near the middle of town [t], nearest first, as the command to build it; or null. */
private fun World.airportNearTown(c: Company, t: Town, kind: Int): Command.Airport? {
    for (d in 0..AIR_TOWN_REACH) for (y in t.y - d..t.y + d) for (x in t.x - d..t.x + d) {
        if (chebyshev(x, y, t.x, t.y) != d) continue
        for (axis in intArrayOf(Track.X, Track.Y)) {
            val w = if (axis == Track.X) Airports.LENGTH[kind] else Airports.WIDTH[kind]
            val h = if (axis == Track.X) Airports.WIDTH[kind] else Airports.LENGTH[kind]
            val cmd = Command.Airport(c.id, x - w / 2, y - h / 2, kind, axis)
            val why = airportRefusal(c, cmd)
            if (why == Refusal.NONE || why == Refusal.MONEY) return cmd
        }
    }
    return null
}

/**
 * Joins towns [t1] and [t2], [d] tiles apart, by air: the biggest kind of airport in [kinds] that fits by both, an
 * aircraft on sale lands at and that pays within [payback] work years, and one aircraft.
 */
private fun World.tryAir(r: Rival, c: Company, t1: Town, t2: Town, d: Int, kinds: List<Int>, payback: Int): Tried {
    val most = (if (settings.rivalLevel == RivalLevel.EASY) maxLoan() / 2 else maxLoan()) * (2 + r.borrow) / 4
    val load = minOf(t1.population, t2.population) / AIR_SHARE
    for (kind in kinds) {
        val built = price(Airports.COST[kind]) * 2
        // Too dear even with the cheapest aircraft, borrowing all it may: a smaller kind.
        val cheapest = aircraftFor(Cargo.PASSENGERS, kind, 0).minOfOrNull { it.cost } ?: continue
        if (c.money + (most - c.loan) < (built + price(cheapest)) * 6 / 5 + price(Prices.LOAN_STEP) * 2) continue
        val siteA = airportNearTown(c, t1, kind) ?: continue
        val siteB = airportNearTown(c, t2, kind) ?: continue
        // The aircraft that pays best and flies from one site to the other, as the aircraft measure it.
        val km = flightKm(siteA.x, siteA.y, siteB.x, siteB.y)
        var model: Engine? = null
        var margin = 0L
        for (e in aircraftFor(Cargo.PASSENGERS, kind, km)) {
            val m = craftMargin(e, Cargo.PASSENGERS, load, d, d, built, 2L * Upkeep.AIRPORT[kind], payback, MAX_AIRCRAFT, AIR_ENDS)
            if (m >= margin) { model = e; margin = m }
        }
        if (model == null) continue
        val cost = built + price(model.cost)
        if (c.money + (most - c.loan) < cost * 6 / 5 + price(Prices.LOAN_STEP) * 2) continue
        if (!afford(c, cost * 6 / 5)) return Tried.POOR
        apply(siteA)
        val pa = airportTiles[index(siteA.x, siteA.y)]?.let { airports.getOrNull(it) }?.takeIf { it.owner == c.id } ?: return Tried.CANT
        apply(siteB)
        val pb = airportTiles[index(siteB.x, siteB.y)]?.let { airports.getOrNull(it) }?.takeIf { it.owner == c.id } ?: return undoCraft(c, index(pa.x, pa.y))
        if (catchmentDirty) workOutCatchment()
        if (pa.station == pb.station || (stationPeople[pa.station] ?: 0) <= 0 || (stationPeople[pb.station] ?: 0) <= 0) return undoCraft(c, index(pa.x, pa.y), index(pb.x, pb.y))
        val route = RivalRoute(pa.station, pb.station, pa.depot, t1.id, t2.id, Cargo.PASSENGERS, craft = RivalRoute.AIR)
        r.routes += route
        buyAircraftOn(c, route, model, Cargo.PASSENGERS)
        return Tried.BUILT
    }
    return Tried.CANT
}

/** Writes [j] for a save. */
internal fun writeShipJob(w: ByteWriter, j: ShipJob) {
    w.uvar(j.picks.size.toLong())
    for (p in j.picks) {
        w.int(p.from); w.int(p.to); w.int(p.cargo); w.bool(p.town); w.int(p.site)
        w.uvar(p.sites.size.toLong())
        for (k in p.sites.indices) { w.int(p.sites[k]); w.int(p.berths[k]) }
    }
    w.int(j.payback); w.int(j.running); w.int(j.from); w.int(j.opensLeft); w.int(j.pick)
    val q = j.queue
    w.bool(q != null)
    if (q == null) return
    w.bytes(j.seen!!); w.int(j.head); w.int(j.tail); w.int(j.depth); w.int(j.layerEnd)
    for (k in j.head until j.tail) w.int(q[k])
}

/** Reads a job [writeShipJob] wrote. */
internal fun World.readShipJob(r: ByteReader): ShipJob {
    val tiles = width * height
    fun count(most: Int, what: String) = r.uvar().also { if (it > most) throw BadData("too many $what") }.toInt()
    fun tile() = r.int().also { if (it !in 0 until tiles) throw BadData("bad ship tile") }
    val picks = List(count(1_000, "ship picks")) {
        val from = r.int(); val to = r.int(); val cargo = r.int(); val town = r.bool(); val site = tile()
        val n = count(tiles, "dock sites")
        val sites = IntArray(n)
        val berths = IntArray(n)
        for (k in 0 until n) { sites[k] = tile(); berths[k] = r.int().also { if (it !in -1 until tiles) throw BadData("bad berth") } }
        if (cargo !in 0 until cargoCount || from < 0 || to < 0 || n == 0) throw BadData("bad ship pick")
        ShipPick(from, to, cargo, town, site, sites, berths)
    }
    val j = ShipJob(picks, r.int())
    j.running = r.int(); j.from = r.int(); j.opensLeft = r.int(); j.pick = r.int()
    if (j.pick !in picks.indices) throw BadData("bad ship pick")
    if (!r.bool()) return j
    j.seen = r.bytes().also { if (it.size != (tiles + 7) / 8) throw BadData("bad ship look") }
    j.head = r.int(); j.tail = r.int(); j.depth = r.int(); j.layerEnd = r.int()
    if (j.head < 0 || j.tail < j.head || j.tail > tiles || j.layerEnd !in j.head..j.tail || j.depth < 0) throw BadData("bad ship look")
    val q = IntArray(maxOf(j.tail, 64))
    for (k in j.head until j.tail) q[k] = tile()
    j.queue = q
    return j
}

/** Tiles of water rivals' shipping line looks take in one tick. */
internal const val SHIP_TICK_STATES = 4000
/** Work years a new shipping line or airline is to pay for itself in, easy to hard. */
internal val SHIP_PAYBACK = intArrayOf(12, 14, 16)
internal val AIR_PAYBACK = intArrayOf(12, 14, 16)
/** Open water tiles a map needs in all, towns and industries it needs near open water, and how near, for shipping lines. */
internal const val SHIP_WATER = 400
internal const val SHIP_PLACES = 3
internal const val SHIP_SHORE = 5
/** Tiles apart a shipping line's ends are, at the least and most for a rival of middling ways. */
private const val SHIP_MIN = 12
private const val SHIP_MAX = 120
/** People a town needs for a passenger boat, and how far from its middle the dock goes. */
private const val SHIP_TOWN = 150
private const val SHIP_TOWN_REACH = 5
private const val SHIPYARD_REACH = 6
private const val SHIP_TRIES = 3
/** Tiles reached, and steps out, before a look for water gives up. */
internal const val SHIP_LOOK = MAX_SHIP_LOOK
private const val SHIP_STEPS = 600
/** Days a ship loses at each end, slowing, turning and loading. */
private const val SHIP_ENDS = 3
internal const val MAX_SHIPS = 4
/** Months of a freight line's load its ship may hold at the most. */
private const val SHIP_FILL_MONTHS = 14
/** Days a ship may take round a line, filling included, so it delivers at least every other year. */
private const val SHIP_CYCLE = 730
/** People a town needs for an airport, tiles apart the ends are at the least, the distance past which it scores no more, and how far from the middle the airport goes. */
private const val AIR_TOWN = 250
private const val AIR_MIN = 40
private const val AIR_BEST = 120
private const val AIR_TOWN_REACH = 10
private const val AIR_TRIES = 3
/** Days an aircraft loses at each end, taxiing, climbing and loading. */
private const val AIR_ENDS = 2
/** Of the smaller town's people, the share flying each way a month. */
private const val AIR_SHARE = 5
internal const val MAX_AIRCRAFT = 3
