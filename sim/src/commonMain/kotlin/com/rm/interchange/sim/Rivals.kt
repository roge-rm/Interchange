package com.rm.interchange.sim

/*
 * Companies run by the computer. Each starts from a home town and, every
 * month or so, looks after its routes and opens new ones: a road between two
 * towns where there isn't one, a stop in each, a garage, and buses or coaches
 * of the day running between them; or the same from an industry to one that
 * takes what it makes, with trucks. It builds only through the same commands a
 * player does, inside the sim, so every copy of a game makes the same moves.
 */

/** A computer-run company's plans: its home town, when it next thinks, and the routes it runs. */
class Rival(val company: Int, val home: Int) {
    /** The calendar day it next looks over its business. */
    var thinkOn = 0
    val routes = ArrayList<RivalRoute>()
    /** Town pairs it has tried and couldn't join, packed as [pairKey], so it doesn't try them again. */
    val failed = HashSet<Long>()
}

/**
 * A route a rival runs: its two stations, the depot its vehicles come from, the towns, what they carry, and whether
 * it's a railway. A freight route's [townA] and [townB] are the industry it loads at and the one it takes the cargo to.
 */
class RivalRoute(val a: Int, val b: Int, val depot: Int, val townA: Int, val townB: Int, val cargo: Int, val rail: Boolean = false) {
    val freight get() = cargo != Cargo.PASSENGERS
}

internal fun pairKey(a: Int, b: Int): Long = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()

/** A freight route from industry [from] to [to] carrying [cargo], packed apart from town pairs, for [Rival.failed]. */
private fun freightKey(from: Int, to: Int, cargo: Int): Long = (1L shl 62) or (cargo.toLong() shl 48) or (from.toLong() shl 24) or to.toLong()

/** A railway from [from] to [to] carrying [cargo]: industries for freight, towns for passengers. */
private fun railKey(from: Int, to: Int, cargo: Int): Long = (1L shl 61) or (cargo.toLong() shl 48) or (from.toLong() shl 24) or to.toLong()

/** The key [route] is remembered by in [Rival.failed] once given up. */
private fun keyOf(route: RivalRoute): Long = when {
    route.rail -> railKey(route.townA, route.townB, route.cargo)
    route.freight -> freightKey(route.townA, route.townB, route.cargo)
    else -> pairKey(route.townA, route.townB)
}

/** Sets up [n] rivals, each on its own home town, the biggest towns first and kept apart. */
internal fun World.startRivals(n: Int) {
    if (n <= 0 || towns.isEmpty()) return
    val homes = ArrayList<Town>()
    for (t in towns.sortedWith(compareByDescending<Town> { it.population }.thenBy { it.id })) {
        if (homes.size >= n) break
        if (homes.any { maxOf(kotlin.math.abs(it.x - t.x), kotlin.math.abs(it.y - t.y)) < RIVAL_APART }) continue
        homes += t
    }
    // Too few towns far enough apart: share them.
    var k = 0
    while (homes.size < n) homes += towns[k++ % towns.size]
    for ((i, home) in homes.withIndex()) {
        val id = companies.size
        companies += Company(id, rivalName(home), Prices.START_MONEY)
        rivals += Rival(id, home.id).also { it.thinkOn = day + 3 + i * 9 }
    }
}

/** A name from the home town, in its own language. */
private fun World.rivalName(t: Town): String {
    val names = if (t.french) listOf("Transports %s", "Messageries %s", "Diligences %s") else listOf("%s Transport", "%s Coach Lines", "%s & District")
    return names[rng.below(names.size)].replace("%s", t.name)
}

/** Each calendar day: any rival due to think does. */
internal fun World.rivalsDay() {
    // A copy, as one may buy out another while it thinks.
    for (r in rivals.toList()) {
        if (day < r.thinkOn || r !in rivals) continue
        r.thinkOn = day + THINK_DAYS
        val c = companies.getOrNull(r.company) ?: continue
        think(r, c)
    }
}

private fun World.think(r: Rival, c: Company) {
    // Out of money: borrow, a step at a time. Well off: pay the loan back.
    while (c.money < 0 && c.loan < maxLoan()) apply(Command.Loan(c.id, true))
    while (c.loan > 0 && c.money > price(Prices.LOAN_STEP) * 4) apply(Command.Loan(c.id, false))
    buyStruggling(c)
    if (c.gone) return
    sellLosers(r, c)
    townWork(r, c)
    for (route in r.routes) growRoute(c, route)
    if (r.routes.size >= maxRoutes(r)) return
    // Buses, then trucks, then a railway, round again; anything it can't do this time, the next kind.
    val kinds = listOf<(Rival, Company) -> Tried>({ a, b -> newRoute(a, b) }, { a, b -> newFreightRoute(a, b) }, { a, b -> newRailRoute(a, b) })
    for (k in 0 until kinds.size) if (kinds[(r.routes.size + k) % kinds.size](r, c) != Tried.CANT) return
}

/**
 * Sends vehicles that lost money last year to their garage and sells them once there. A route left with none is
 * given up: its stops and garage come down, the road stays, and it isn't tried again.
 */
private fun World.sellLosers(r: Rival, c: Company) {
    // Its own vehicles only stand stopped in a depot when sent there to be sold.
    for (v in roadVehicles) {
        if (v == null || v.owner != c.id) continue
        if (v.inDepot && !v.running) apply(Command.RoadOrder(c.id, v.id, Command.TrainOrder.SELL))
        else if (!v.inDepot && (!v.toDepot || v.servicing) && day - v.built >= SELL_AGE && v.profitLastYear < 0) {
            // One on its way for a service is turned off that first, then sent to stay.
            if (v.toDepot) apply(Command.RoadOrder(c.id, v.id, Command.TrainOrder.TO_DEPOT))
            apply(Command.RoadOrder(c.id, v.id, Command.TrainOrder.TO_DEPOT))
        }
    }
    for (t in trains) {
        if (t == null || t.owner != c.id) continue
        if (t.inDepot && !t.running) apply(Command.TrainOrder(c.id, t.id, Command.TrainOrder.SELL))
        else if (!t.inDepot && !t.toDepot && day - t.built >= SELL_AGE && t.profitLastYear < 0) {
            apply(Command.TrainOrder(c.id, t.id, Command.TrainOrder.TO_DEPOT))
        }
    }
    val gone = r.routes.filter { route -> (if (route.rail) trains else roadVehicles).none { it != null && it.owner == c.id && onRoute(it, route) } }
    for (route in gone) {
        r.routes -= route
        r.failed += keyOf(route)
        // Stations and depots another route still uses stay; a railway's track stays too.
        for (id in listOf(route.a, route.b)) {
            if (r.routes.any { it.a == id || it.b == id }) continue
            val s = stations.getOrNull(id) ?: continue
            for (i in s.stops.toList() + s.tiles.toList()) clearTile(c, Command.Clear(c.id, i % width, i / width))
        }
        if (r.routes.none { it.depot == route.depot }) depots.getOrNull(route.depot)?.let { clearTile(c, Command.Clear(c.id, it.tile % width, it.tile / width)) }
    }
}

/** Whether [v] runs on [route]. */
private fun onRoute(v: Carrier, route: RivalRoute) = v.orders.size == 2 && Order.station(v.orders[0]) == route.a && Order.station(v.orders[1]) == route.b

/** More routes as the years go by. */
private fun World.maxRoutes(r: Rival): Int {
    val years = Calendar.year(startYear, day) - startYear
    return when (settings.rivalLevel) {
        RivalLevel.EASY -> (1 + years / 3).coerceAtMost(MAX_ROUTES - 2)
        RivalLevel.HARD -> (1 + years * 2 / 3).coerceAtMost(MAX_ROUTES + 4)
        else -> (1 + years / 2).coerceAtMost(MAX_ROUTES)
    }
}

/** Whether the player has a station within [PLAYER_REACH] of ([x], [y]), which easy rivals keep clear of. */
private fun World.playerNear(x: Int, y: Int): Boolean =
    stations.any { it != null && it.owner == 0 && !it.waypoint && chebyshev(it.signX, it.signY, x, y) <= PLAYER_REACH }

/** Easy rivals keep clear of towns the player serves. */
private fun World.leaveAlone(t: Town) = settings.rivalLevel == RivalLevel.EASY && playerNear(t.x, t.y)

/**
 * Now and then, what a rival pays a town for, as its difficulty allows: a statue at home and advertising where its
 * stations are poorly rated; and when hard, exclusive rights and road works where the player runs. One a month at most,
 * and only what it can well afford.
 */
private fun World.townWork(r: Rival, c: Company) {
    val level = settings.rivalLevel
    if (level == RivalLevel.EASY || rng.below(3) != 0) return
    fun rich(action: Int) = c.money > townActionCost(action) * 3 && companyValue(c) > townActionCost(action) * 5
    fun go(t: Town, action: Int): Boolean {
        if (!townActionOpen(c.id, t, action) || !rich(action)) return false
        apply(Command.TownAction(c.id, t.id, action))
        return true
    }
    val mine = stations.filter { it != null && it.owner == c.id && !it.waypoint }.map { it!! }
    fun near(t: Town, owner: Int, stops: Boolean = false) = stations.any {
        it != null && it.owner == owner && !it.waypoint && (!stops || it.stops.isNotEmpty()) && chebyshev(it.signX, it.signY, t.x, t.y) <= TOWN_AREA / 2
    }
    if (level == RivalLevel.HARD) {
        // Shutting the player out of a town both run in, or holding up the player's buses where it has none of its own.
        for (t in towns) if (near(t, 0) && near(t, c.id) && go(t, TownActions.EXCLUSIVE)) return
        for (t in towns) if (near(t, 0, stops = true) && !near(t, c.id, stops = true) && go(t, TownActions.ROAD_WORKS)) return
    }
    towns.getOrNull(r.home)?.let { if (go(it, TownActions.STATUE)) return }
    val ad = if (level == RivalLevel.HARD) TownActions.AD_MEDIUM else TownActions.AD_SMALL
    val poor = mine.firstOrNull { it.lastCall[Cargo.PASSENGERS] >= 0 && it.rating[Cargo.PASSENGERS] < POOR_RATING } ?: return
    val t = towns.minByOrNull { chebyshev(it.x, it.y, poor.signX, poor.signY) } ?: return
    if (chebyshev(t.x, t.y, poor.signX, poor.signY) <= TownActions.AD_REACH[ad]) go(t, ad)
}

/** Adds a vehicle to [route] when it's leaving people behind at either end. */
private fun World.growRoute(c: Company, route: RivalRoute) {
    // A railway is one line of single track: one train.
    if (route.rail) return
    val a = stations.getOrNull(route.a) ?: return
    val b = stations.getOrNull(route.b) ?: return
    val on = roadVehicles.filter { it != null && it.owner == c.id && onRoute(it, route) }.map { it!! }
    if (on.size >= MAX_PER_ROUTE || on.any { it.toDepot && !it.servicing || day - it.built >= SELL_AGE && it.profitLastYear < 0 }) return
    // Only on a route that pays: last year's profit, or this year's once it's a few months in.
    val lastYear = on.sumOf { it.profitLastYear }
    val thisYear = on.sumOf { it.profitThisYear }
    if (lastYear <= 0 && (Calendar.dayOfYear(startYear, day) < 90 || thisYear <= 0)) return
    val model = bestModel(route.cargo) ?: return
    val waiting = maxOf(a.waiting(route.cargo), b.waiting(route.cargo))
    if (waiting < (model.capacity * 3 / 2).coerceAtLeast(10)) return
    if (!afford(c, price(model.cost) * 2)) return
    buyAndStart(c, route, model)
}

/** Buys a company up for sale when it's well able to, getting its routes and vehicles cheap. */
private fun World.buyStruggling(c: Company) {
    for (o in companies) {
        if (o.id == c.id || !forSale(o)) continue
        val cost = salePrice(o)
        if (companyValue(c) < cost * 3 || !afford(c, cost * 2)) continue
        apply(Command.BuyCompany(c.id, o.id))
    }
}

/** Whether [c] has [cost] or can borrow it, borrowing what it needs. */
internal fun World.afford(c: Company, cost: Long): Boolean {
    if (c.money + (maxLoan() - c.loan) < cost) return false
    while (c.money < cost && c.loan < maxLoan()) apply(Command.Loan(c.id, true))
    return c.money >= cost
}

/** The road vehicle for [cargo] on sale now that carries the most the fastest, trams and plows left out. */
private fun World.bestModel(cargo: Int): Engine? {
    val year = Calendar.year(startYear, day)
    return roster.availableRoad(year).filter { !it.tram && it.plow == Plows.NONE && cargo in it.cargoes && it.capacity > 0 }
        .maxWithOrNull(compareBy<Engine> { it.capacity.toLong() * it.speed }.thenBy { -it.id })
}

private fun World.buyAndStart(c: Company, route: RivalRoute, model: Engine) {
    val before = roadVehicles.size
    apply(Command.BuyRoadVehicle(c.id, route.depot, model.id, route.cargo))
    if (roadVehicles.size == before) return
    val id = roadVehicles.size - 1
    // Freight waits for a full load at the first stop and comes back empty.
    val orders = if (route.freight) intArrayOf(route.a or Order.FULL_LOAD, route.b or Order.NO_LOAD) else intArrayOf(route.a, route.b)
    apply(Command.RoadOrder(c.id, id, Command.TrainOrder.ORDERS, orders))
    apply(Command.RoadOrder(c.id, id, Command.TrainOrder.START_STOP))
}

/** Tries the most promising pairs of towns not yet joined by this rival, nearest its home first. */
private fun World.newRoute(r: Rival, c: Company): Tried {
    val model = bestModel(Cargo.PASSENGERS) ?: return Tried.CANT
    val home = towns.getOrNull(r.home) ?: return Tried.CANT
    val served = r.routes.map { pairKey(it.townA, it.townB) }.toSet()
    val pairs = ArrayList<Triple<Town, Town, Long>>()
    for (t1 in towns) {
        if (t1.population < MIN_TOWN || chebyshev(t1.x, t1.y, home.x, home.y) > HOME_REACH) continue
        for (t2 in towns) {
            if (t2.id <= t1.id || t2.population < MIN_TOWN) continue
            val d = chebyshev(t1.x, t1.y, t2.x, t2.y)
            if (d < MIN_APART || d > MAX_APART) continue
            val key = pairKey(t1.id, t2.id)
            if (key in served || key in r.failed) continue
            // A town that won't have it at either end now may later.
            if (townRefuses(c, t1.x, t1.y) || townRefuses(c, t2.x, t2.y) || leaveAlone(t1) || leaveAlone(t2)) continue
            // Bigger towns, closer together, nearer home.
            val score = minOf(t1.population, t2.population).toLong() * 1000 / (d + chebyshev(t1.x, t1.y, home.x, home.y) / 2 + 5)
            // A subsidy on offer makes it worth twice as much.
            pairs += Triple(t1, t2, if (onOffer(Cargo.PASSENGERS, t1.id, t2.id, true)) score * 2 else score)
        }
    }
    pairs.sortWith(compareByDescending<Triple<Town, Town, Long>> { it.third }.thenBy { it.first.id }.thenBy { it.second.id })
    for ((t1, t2, _) in pairs.take(TRIES)) {
        when (val t = tryRoute(r, c, t1, t2, model)) {
            Tried.CANT -> r.failed += pairKey(t1.id, t2.id)
            else -> return t
        }
    }
    return Tried.CANT
}

/**
 * Tries the most promising industries near home not yet served by this rival, each to the nearest that takes what
 * it makes, by how much it makes and how near.
 */
private fun World.newFreightRoute(r: Rival, c: Company): Tried {
    val home = towns.getOrNull(r.home) ?: return Tried.CANT
    val served = r.routes.filter { it.freight }.map { it.townA to it.cargo }.toSet()
    class Pick(val from: Industry, val to: Industry, val cargo: Int, val model: Engine, val score: Long)
    val picks = ArrayList<Pick>()
    for (ind in industries) {
        if (ind.closed) continue
        val kind = roster.industries[ind.kind]
        val (cx, cy) = middle(ind)
        if (chebyshev(cx, cy, home.x, home.y) > HOME_REACH) continue
        for (cg in 0 until Cargo.COUNT) {
            val month = kind.produces[cg] * ind.level / 16
            if (month <= 0 || (ind.id to cg) in served || servedByOthers(ind, cg, c.id)) continue
            val model = bestModel(cg) ?: continue
            // The nearest that takes it, far enough to be worth the trip.
            var to: Industry? = null
            var best = Int.MAX_VALUE
            for (o in industries) {
                if (o.closed || o.id == ind.id || !roster.industries[o.kind].takes(cg)) continue
                val (ox, oy) = middle(o)
                val d = chebyshev(cx, cy, ox, oy)
                if (d < MIN_APART || d > MAX_APART || d >= best || freightKey(ind.id, o.id, cg) in r.failed) continue
                to = o
                best = d
            }
            if (to == null) continue
            val (ox, oy) = middle(to)
            if (townRefuses(c, cx, cy) || townRefuses(c, ox, oy)) continue
            val score = month.toLong() * 1000 / (best + chebyshev(cx, cy, home.x, home.y) / 2 + 5)
            picks += Pick(ind, to, cg, model, if (onOffer(cg, ind.id, to.id, false)) score * 2 else score)
        }
    }
    picks.sortWith(compareByDescending<Pick> { it.score }.thenBy { it.from.id }.thenBy { it.cargo })
    for (p in picks.take(TRIES)) {
        when (val t = tryFreight(r, c, p.from, p.to, p.cargo, p.model)) {
            Tried.CANT -> r.failed += freightKey(p.from.id, p.to.id, p.cargo)
            else -> return t
        }
    }
    return Tried.CANT
}

/**
 * Tries a railway: from an industry near home to the nearest that takes what it makes, or between two big towns,
 * farther apart than road vehicles go, the longer the better up to a point.
 */
private fun World.newRailRoute(r: Rival, c: Company): Tried {
    val home = towns.getOrNull(r.home) ?: return Tried.CANT
    val served = r.routes.filter { it.rail }.map { keyOf(it) }.toSet()
    class Pick(val from: Int, val to: Int, val cargo: Int, val load: Int, val sa: RailSite, val sb: RailSite, val score: Long)
    val picks = ArrayList<Pick>()
    for (ind in industries) {
        if (ind.closed) continue
        val kind = roster.industries[ind.kind]
        val (cx, cy) = middle(ind)
        val fromHome = chebyshev(cx, cy, home.x, home.y)
        if (fromHome > HOME_REACH) continue
        for (cg in 0 until Cargo.COUNT) {
            val month = kind.produces[cg] * ind.level / 16
            if (month < RAIL_LEAST || servedByOthers(ind, cg, c.id)) continue
            var to: Industry? = null
            var best = Int.MAX_VALUE
            for (o in industries) {
                if (o.closed || o.id == ind.id || !roster.industries[o.kind].takes(cg)) continue
                val (ox, oy) = middle(o)
                val d = chebyshev(cx, cy, ox, oy)
                if (d < RAIL_MIN || d > RAIL_MAX || d >= best) continue
                to = o
                best = d
            }
            if (to == null) continue
            val key = railKey(ind.id, to.id, cg)
            if (key in served || key in r.failed) continue
            val (ox, oy) = middle(to)
            if (townRefuses(c, cx, cy) || townRefuses(c, ox, oy)) continue
            // About what it'll get in a month: its share is never all of it.
            picks += Pick(ind.id, to.id, cg, month * 2 / 3, railSite(ind, ox, oy), railSite(to, cx, cy), month.toLong() * minOf(best, RAIL_BEST) * 100 / (fromHome + 10))
        }
    }
    for (t1 in towns) {
        if (t1.population < RAIL_TOWN) continue
        val fromHome = chebyshev(t1.x, t1.y, home.x, home.y)
        if (fromHome > HOME_REACH) continue
        for (t2 in towns) {
            if (t2.id == t1.id || t2.population < RAIL_TOWN) continue
            val d = chebyshev(t1.x, t1.y, t2.x, t2.y)
            if (d < RAIL_MIN || d > RAIL_MAX) continue
            val key = railKey(minOf(t1.id, t2.id), maxOf(t1.id, t2.id), Cargo.PASSENGERS)
            if (key in served || key in r.failed || t2.id < t1.id && chebyshev(t2.x, t2.y, home.x, home.y) <= HOME_REACH) continue
            if (townRefuses(c, t1.x, t1.y) || townRefuses(c, t2.x, t2.y) || leaveAlone(t1) || leaveAlone(t2)) continue
            // Passengers go both ways, so a town's worth about a mine's month.
            picks += Pick(minOf(t1.id, t2.id), maxOf(t1.id, t2.id), Cargo.PASSENGERS, minOf(t1.population, t2.population) / 4, railSite(t1, t2.x, t2.y), railSite(t2, t1.x, t1.y),
                minOf(t1.population, t2.population).toLong() / 4 * minOf(d, RAIL_BEST) * 100 / (fromHome + 10))
        }
    }
    picks.sortWith(compareByDescending<Pick> { it.score }.thenBy { it.from }.thenBy { it.to }.thenBy { it.cargo })
    for (p in picks.take(RAIL_TRIES)) {
        val freight = p.cargo != Cargo.PASSENGERS
        val serves = { s: Station, first: Boolean ->
            if (freight) stationIndustries[s.id]?.contains(if (first) p.from else p.to) == true else (stationPeople[s.id] ?: 0) > 0
        }
        when (val t = tryRail(c, p.sa, p.sb, p.cargo, p.load, p.from, p.to, serves) { r.routes += it }) {
            Tried.CANT -> r.failed += railKey(p.from, p.to, p.cargo)
            else -> return t
        }
    }
    return Tried.CANT
}

/** Whether another company's station already takes cargo [cg] from [ind]. */
private fun World.servedByOthers(ind: Industry, cg: Int, company: Int): Boolean {
    // Hard rivals go after what the player carries.
    val hard = settings.rivalLevel == RivalLevel.HARD
    return industryStations[ind.id].orEmpty().any { id ->
        stations.getOrNull(id)?.let { it.owner != company && !(hard && it.owner == 0) && it.lastCall[cg] >= 0 } == true
    }
}

/** The tile in the middle of [ind]. */
internal fun World.middle(ind: Industry): Pair<Int, Int> {
    val kind = roster.industries[ind.kind]
    return ind.x + kind.across(ind.turn) / 2 to ind.y + kind.along(ind.turn) / 2
}

/** Joins industries [from] and [to] by road with a stop at each, a garage and two trucks for [cargo]. */
private fun World.tryFreight(r: Rival, c: Company, from: Industry, to: Industry, cargo: Int, model: Engine): Tried {
    val (tx, ty) = middle(to)
    val path = roadPath(around(from), around(to).toHashSet(), tx, ty) ?: return Tried.CANT
    val year = Calendar.year(startYear, day)
    val surface = Surface.entries(year).firstOrNull { Surface.SPEED[it] >= model.speed } ?: Surface.entries(year).last()
    val runs = runs(c.id, path, surface)
    val roadCost = runs.sumOf { roadCost(c, it).coerceAtLeast(0) }
    val cost = roadCost + price(Prices.ROAD_STOP) * 2 + price(Prices.DEPOT) + price(model.cost) * 2
    if (!afford(c, cost * 6 / 5)) return Tried.POOR
    for (run in runs) apply(run)
    val stopA = placeStopBy(c, from, path, true) ?: return Tried.CANT
    val stopB = placeStopBy(c, to, path, false) ?: return undo(c, stopA)
    if (!roadConnected(stopA, stopB)) return undo(c, stopA, stopB)
    val depot = placeGarage(c, stopA) ?: return undo(c, stopA, stopB)
    val a = roadStops.getValue(stopA)
    val b = roadStops.getValue(stopB)
    if (catchmentDirty) workOutCatchment()
    // A stop that's another of this rival's stations already, or that doesn't reach the industry it's for, won't do.
    if (a == b || stationIndustries[a]?.contains(from.id) != true || stationIndustries[b]?.contains(to.id) != true) return undo(c, stopA, stopB)
    val route = RivalRoute(a, b, depot, from.id, to.id, cargo)
    r.routes += route
    repeat(2) { buyAndStart(c, route, model) }
    return Tried.BUILT
}

/** Where a road to [ind] can end: road a step or two outside it, or flat land there a road can be laid on. */
private fun World.around(ind: Industry): List<Int> {
    val kind = roster.industries[ind.kind]
    val out = ArrayList<Int>()
    for (y in ind.y - 2..ind.y + kind.along(ind.turn) + 1) for (x in ind.x - 2..ind.x + kind.across(ind.turn) + 1) {
        if (!inside(x, y)) continue
        val i = index(x, y)
        if (industryOf[i].toInt() != 0) continue
        if (roadEdges(i) != 0 || (isFlat(x, y) && stepCost(i, Track.XM) != null)) out += i
    }
    return out
}

internal enum class Tried { BUILT, CANT, POOR }

/** Joins towns [t1] and [t2] by road with a stop in each, a garage and two vehicles, if it can and can pay. */
private fun World.tryRoute(r: Rival, c: Company, t1: Town, t2: Town, model: Engine): Tried {
    val path = roadPath(t1, t2) ?: return Tried.CANT
    val year = Calendar.year(startYear, day)
    // The cheapest surface the vehicles can go their top speed on.
    val surface = Surface.entries(year).firstOrNull { Surface.SPEED[it] >= model.speed } ?: Surface.entries(year).last()
    val runs = runs(c.id, path, surface)
    val roadCost = runs.sumOf { roadCost(c, it).coerceAtLeast(0) }
    val cost = roadCost + price(Prices.ROAD_STOP) * 2 + price(Prices.DEPOT) + price(model.cost) * 2
    if (!afford(c, cost * 6 / 5)) return Tried.POOR
    for (run in runs) apply(run)
    // What's built before it turns out it can't be done comes down again; the road stays, as anyone can use it.
    val stopA = placeStop(c, t1, path.first()) ?: return Tried.CANT
    val stopB = placeStop(c, t2, path.last()) ?: return undo(c, stopA)
    if (!roadConnected(stopA, stopB)) return undo(c, stopA, stopB)
    val depot = placeGarage(c, stopA) ?: return undo(c, stopA, stopB)
    val a = roadStops.getValue(stopA)
    val b = roadStops.getValue(stopB)
    val route = RivalRoute(a, b, depot, t1.id, t2.id, Cargo.PASSENGERS)
    r.routes += route
    repeat(2) { buyAndStart(c, route, model) }
    return Tried.BUILT
}

/** Takes up the stops on [tiles] after a route that couldn't be finished. */
private fun World.undo(c: Company, vararg tiles: Int): Tried {
    for (i in tiles) clearTile(c, Command.Clear(c.id, i % width, i / width))
    return Tried.CANT
}

/** [path] cut into straight runs of road to lay, each starting where the last ended. */
private fun runs(company: Int, path: IntArray, surface: Int): List<Command.Road> {
    val out = ArrayList<Command.Road>()
    var start = 0
    for (k in 1 until path.size) {
        val turns = k == path.size - 1 || dirOf(path[k - 1], path[k]) != dirOf(path[k], path[k + 1])
        if (turns) {
            out += Command.Road(company, path[start] and 0xffff, path[start] ushr 16, path[k] and 0xffff, path[k] ushr 16, surface, remove = false)
            start = k
        }
    }
    return out
}

/** The way from packed tile [a] to the next, [b], as a [Track] edge. */
private fun dirOf(a: Int, b: Int): Int {
    val dx = (b and 0xffff) - (a and 0xffff)
    val dy = (b ushr 16) - (a ushr 16)
    return if (dx > 0) Track.XP else if (dx < 0) Track.XM else if (dy > 0) Track.YP else Track.YM
}

/** A drive-through stop on a straight, flat road near [t]'s middle, tried from the end of the new road [end] out; its tile. */
private fun World.placeStop(c: Company, t: Town, end: Int): Int? =
    placeStop(c, t.x - STOP_REACH, t.y - STOP_REACH, t.x + STOP_REACH, t.y + STOP_REACH, end)

/**
 * A drive-through stop close enough to [ind] to serve it, tried from the [start] or far end of the new road [path]
 * out; its tile. With no straight road there it lays a short spur off the new road to put one on.
 */
private fun World.placeStopBy(c: Company, ind: Industry, path: IntArray, start: Boolean): Int? {
    val kind = roster.industries[ind.kind]
    val reach = CATCHMENT - 1
    val x0 = ind.x - reach
    val y0 = ind.y - reach
    val x1 = ind.x + kind.across(ind.turn) - 1 + reach
    val y1 = ind.y + kind.along(ind.turn) - 1 + reach
    val end = if (start) path.first() else path.last()
    placeStop(c, x0, y0, x1, y1, end)?.let { return it }
    val near = (if (start) path.asList() else path.asList().asReversed()).take(SPUR_TRIES)
    for (p in near) {
        val px = p and 0xffff
        val py = p ushr 16
        if (px !in x0..x1 || py !in y0..y1) continue
        for (e in 0 until 4) {
            val mx = px + Track.dx(e)
            val my = py + Track.dy(e)
            val fx = mx + Track.dx(e)
            val fy = my + Track.dy(e)
            if (mx !in x0..x1 || my !in y0..y1 || !inside(fx, fy) || !isFlat(mx, my) || !isFlat(fx, fy)) continue
            if (roadEdges(index(mx, my)) != 0 || roadEdges(index(fx, fy)) != 0) continue
            if (stepCost(index(mx, my), e) == null || stepCost(index(fx, fy), e) == null) continue
            apply(Command.Road(c.id, px, py, fx, fy, Surface.entries(Calendar.year(startYear, day)).first(), remove = false))
            if (straightRoad(index(mx, my)) == 0) continue
            apply(Command.RoadStop(c.id, mx, my))
            if (roadStops.containsKey(index(mx, my))) return index(mx, my)
        }
    }
    return null
}

/** A drive-through stop on a straight, flat road from ([x0], [y0]) to ([x1], [y1]), nearest [end] first; its tile. */
private fun World.placeStop(c: Company, x0: Int, y0: Int, x1: Int, y1: Int, end: Int): Int? {
    val ex = end and 0xffff
    val ey = end ushr 16
    val tried = ArrayList<Int>()
    for (y in y0..y1) for (x in x0..x1) {
        if (!inside(x, y)) continue
        val i = index(x, y)
        if (straightRoad(i) == 0 || track[i].toInt() != 0 || roadStops.containsKey(i) || !isFlat(x, y)) continue
        val owned = roadOwner[i].toInt()
        if (owned >= 0 && owned != c.id) continue
        tried += i
    }
    tried.sortWith(compareBy<Int> { chebyshev(it % width, it / width, ex, ey) }.thenBy { it })
    for (i in tried.take(6)) {
        if (!roadConnected(index(ex, ey), i)) continue
        apply(Command.RoadStop(c.id, i % width, i / width))
        if (roadStops.containsKey(i)) return i
    }
    return null
}

/** A garage on free flat land beside the road near [stop]; its depot id. */
private fun World.placeGarage(c: Company, stop: Int): Int? {
    val sx = stop % width
    val sy = stop / width
    val spots = ArrayList<Pair<Int, Int>>()
    for (y in sy - GARAGE_REACH..sy + GARAGE_REACH) for (x in sx - GARAGE_REACH..sx + GARAGE_REACH) {
        if (!inside(x, y)) continue
        for (e in 0 until 4) {
            val nx = x + Track.dx(e)
            val ny = y + Track.dy(e)
            if (inside(nx, ny) && roadEdges(index(nx, ny)) != 0) spots += index(x, y) to e
        }
    }
    spots.sortWith(compareBy<Pair<Int, Int>> { chebyshev(it.first % width, it.first / width, sx, sy) }.thenBy { it.first }.thenBy { it.second })
    for ((i, e) in spots.take(24)) {
        val x = i % width
        val y = i / width
        if (!isFlat(x, y) || road[i].toInt() != 0 || house[i].toInt() != 0 || track[i].toInt() != 0 || isWater(x, y)) continue
        val n = index(x + Track.dx(e), y + Track.dy(e))
        if (!roadConnected(n, stop)) continue
        val before = depots.size
        apply(Command.Garage(c.id, x, y, e))
        if (depots.size > before) return depots.size - 1
    }
    return null
}

internal fun chebyshev(x0: Int, y0: Int, x1: Int, y1: Int) = maxOf(kotlin.math.abs(x0 - x1), kotlin.math.abs(y0 - y1))

/** Whether road leads from tile [a] to tile [b], edge to edge, looking at no more than [ROAD_SEARCH] tiles. */
fun World.roadConnected(a: Int, b: Int): Boolean {
    if (a == b) return true
    val seen = HashSet<Int>()
    val queue = ArrayDeque<Int>()
    queue += a
    seen += a
    while (queue.isNotEmpty() && seen.size < ROAD_SEARCH) {
        val t = queue.removeFirst()
        val edges = roadEdges(t)
        for (e in 0 until 4) {
            if (edges and roadBit(e) == 0) continue
            val nx = t % width + Track.dx(e)
            val ny = t / width + Track.dy(e)
            if (!inside(nx, ny)) continue
            val n = index(nx, ny)
            if (roadEdges(n) and roadBit(Track.opposite(e)) == 0 || n in seen) continue
            // Vehicles don't drive through garages.
            if (depotTiles.containsKey(n) && n != b) continue
            if (n == b) return true
            seen += n
            queue += n
        }
    }
    return false
}

/** A road's way from near [from]'s middle to near [to]'s, along the towns' own roads at each end. */
private fun World.roadPath(from: Town, to: Town): IntArray? {
    fun ends(t: Town): List<Int> {
        val out = ArrayList<Int>()
        for (y in t.y - STOP_REACH..t.y + STOP_REACH) for (x in t.x - STOP_REACH..t.x + STOP_REACH) {
            if (inside(x, y) && roadEdges(index(x, y)) != 0 && townOf[index(x, y)].toInt() == t.id) out += index(x, y)
        }
        return out
    }
    return roadPath(ends(from), ends(to).toHashSet(), to.x, to.y)
}

/**
 * A way for a road from one of tiles [starts] to one of [goals], heading for ([gx], [gy]), as packed tiles (x in the
 * low 16 bits, y above), over ground a road can be laid on and along roads already there. It turns only on level
 * ground, and on a slope keeps straight, so the road it lays meets itself level at every join. Null if there's none
 * within reach.
 */
private fun World.roadPath(starts: List<Int>, goals: Set<Int>, gx: Int, gy: Int): IntArray? {
    if (starts.isEmpty() || goals.isEmpty()) return null
    // States are a tile and the edge it was come into by, 4 for a start.
    val best = HashMap<Int, Int>()
    val back = HashMap<Int, Int>()
    val heap = LongHeap()
    fun h(i: Int) = (kotlin.math.abs(i % width - gx) + kotlin.math.abs(i / width - gy)) * MOVE_ROAD
    for (i in starts) {
        val s = i * 5 + 4
        best[s] = 0
        heap.push(h(i).toLong() shl 32 or s.toLong())
    }
    val limit = (chebyshev(starts[0] % width, starts[0] / width, gx, gy) + 10).let { it * it * 12 }.coerceAtMost(PATH_LIMIT)
    var expanded = 0
    while (!heap.isEmpty() && expanded < limit) {
        val top = heap.pop()
        val s = (top and 0xffffffffL).toInt()
        val g = best[s] ?: continue
        if ((top ushr 32).toInt() - h(s / 5) > g) continue
        expanded++
        val t = s / 5
        val came = s % 5
        if (t in goals && came != 4) {
            // Back to a start, as packed tiles.
            val tiles = ArrayList<Int>()
            var at = s
            while (true) {
                val tile = at / 5
                tiles += (tile / width shl 16) or (tile % width)
                at = back[at] ?: break
            }
            tiles.reverse()
            return tiles.toIntArray()
        }
        val tx = t % width
        val ty = t / width
        val flat = isFlat(tx, ty)
        for (e in 0 until 4) {
            if (came != 4 && e == came) continue
            // Turning only on level ground; on a slope a road there already goes on along its own line.
            if (came != 4 && e != Track.opposite(came) && !flat) continue
            if (!flat && roadEdges(t) != 0 && !alongRoad(t, e)) continue
            val nx = tx + Track.dx(e)
            val ny = ty + Track.dy(e)
            if (!inside(nx, ny)) continue
            val ni = index(nx, ny)
            val step = stepCost(ni, e) ?: continue
            val ns = ni * 5 + Track.opposite(e)
            val ng = g + step + if (came != 4 && e != Track.opposite(came)) TURN else 0
            if (ng >= (best[ns] ?: Int.MAX_VALUE)) continue
            best[ns] = ng
            back[ns] = s
            heap.push((ng + h(ni)).toLong() shl 32 or ns.toLong())
        }
    }
    return null
}

/** Whether a road reaching [edge] of tile [i] keeps to the line of the road there now. */
private fun World.alongRoad(i: Int, edge: Int): Boolean {
    val bits = roadEdges(i)
    val axis = if (edge == Track.XM || edge == Track.XP) roadBit(Track.XM) or roadBit(Track.XP) else roadBit(Track.YM) or roadBit(Track.YP)
    return bits and axis.inv() == 0
}

/** What going onto tile [i] across edge [e] costs, or null if a road can't go there that way. */
private fun World.stepCost(i: Int, e: Int): Int? {
    val x = i % width
    val y = i / width
    if (isWater(x, y) || house[i].toInt() != 0 || industryOf[i].toInt() != 0 || stationTiles.containsKey(i) || depotTiles.containsKey(i)) return null
    if (spanHeads.containsKey(i) || deck.containsKey(i) || canals.contains(i) || airportTiles.containsKey(i) || saltDepots.contains(i)) return null
    val flat = isFlat(x, y)
    val existing = roadEdges(i)
    if (track[i].toInt() != 0) return if (existing != 0 && alongRoad(i, e)) MOVE_ROAD else null
    if (roadStops.containsKey(i)) return if (alongRoad(i, e)) MOVE_ROAD else null
    if (existing != 0) return if (flat || alongRoad(i, e)) MOVE_ROAD else null
    return MOVE_NEW + (if (flat) 0 else SLOPE) + Trees.count(trees[i].toInt())
}

/** A binary heap of longs, smallest first, for the path search: its order is the same everywhere. */
internal class LongHeap {
    private var a = LongArray(256)
    private var size = 0
    fun isEmpty() = size == 0
    fun push(v: Long) {
        if (size == a.size) a = a.copyOf(size * 2)
        var i = size++
        a[i] = v
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

/** How far apart, in tiles, the ends of a railway are; past [RAIL_BEST] a longer one isn't worth more to it. */
private const val RAIL_MIN = 16
private const val RAIL_MAX = 64
private const val RAIL_BEST = 40
/** Industries making less a month than this aren't worth a railway. */
private const val RAIL_LEAST = 60
/** Towns smaller than this aren't worth a railway. */
private const val RAIL_TOWN = 300
/** Railways tried at each look. */
private const val RAIL_TRIES = 2

/** Calendar days between a rival's looks at its business. */
private const val THINK_DAYS = 30
private const val MAX_ROUTES = 8
/** How near the player's stations easy rivals keep away from, in tiles. */
private const val PLAYER_REACH = 10
/** A station rating, out of 255, poor enough for a rival to advertise. */
private const val POOR_RATING = 110
private const val MAX_PER_ROUTE = 6
/** Calendar days a vehicle runs before it's sold for losing money: two years, so last year's figure is a whole year. */
private const val SELL_AGE = 730
/** Towns smaller than this aren't worth a route. */
private const val MIN_TOWN = 60
/** How far apart, in tiles, towns on a route are, and how far from home the nearer is. */
private const val MIN_APART = 6
private const val MAX_APART = 36
private const val HOME_REACH = 48
/** Homes kept this far apart, in tiles. */
private const val RIVAL_APART = 20
/** Pairs tried at each look. */
private const val TRIES = 3
/** Tiles of a new road, from its end, tried for a spur to put an industry's stop on. */
private const val SPUR_TRIES = 8
/** How far from a town's middle its stop and the road's ends go, and from a stop its garage. */
private const val STOP_REACH = 4
private const val GARAGE_REACH = 3
/** Path costs: along a road already there, a new tile, a slope, a turn. */
private const val MOVE_ROAD = 2
private const val MOVE_NEW = 8
private const val SLOPE = 4
private const val TURN = 3
/** Most states the path search looks at, and tiles the road search. */
private const val PATH_LIMIT = 60_000
private const val ROAD_SEARCH = 20_000
