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
    /** The work day it next looks over its business. */
    var thinkOn = 0
    val routes = ArrayList<RivalRoute>()
    /**
     * Town pairs and industries it couldn't join or gave up on, packed as [pairKey] and the like, with the work day it
     * did. Each is tried again [RETRY_DAYS] after.
     */
    val failed = HashMap<Long, Int>()
}

/** Whether [r] has tried [key] lately and not tried again yet. */
internal fun World.triedLately(r: Rival, key: Long): Boolean = r.failed[key]?.let { workDay - it < RETRY_DAYS } == true

/** Remembers that [r] couldn't do [key] for now. */
internal fun World.giveUp(r: Rival, key: Long) {
    r.failed[key] = workDay
}

/**
 * A route a rival runs: its two stations, the depot its vehicles come from, the towns, what they carry, and whether
 * it's a railway. A freight route's [townA] and [townB] are the industry it loads at and the one it takes the cargo to.
 */
class RivalRoute(val a: Int, val b: Int, val depot: Int, val townA: Int, val townB: Int, val cargo: Int, val rail: Boolean = false) {
    val freight get() = cargo != Cargo.PASSENGERS
    /** A railway's feeder buses, from the middle of town [townA] to a stop at its station [b]. */
    val feeder get() = !rail && !freight && townA == townB
    /** A railway's passing loops, each packed by [packLoop]. */
    val loops = ArrayList<Int>()
}

internal fun pairKey(a: Int, b: Int): Long = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()

/** A freight route from industry [from] to [to] carrying [cargo], packed apart from town pairs, for [Rival.failed]. */
private fun freightKey(from: Int, to: Int, cargo: Int): Long = (1L shl 62) or (cargo.toLong() shl 48) or (from.toLong() shl 24) or to.toLong()

/** A railway from [from] to [to] carrying [cargo]: industries for freight, towns for passengers. */
private fun railKey(from: Int, to: Int, cargo: Int): Long = (1L shl 61) or (cargo.toLong() shl 48) or (from.toLong() shl 24) or to.toLong()

/** Feeder buses from town [town] to station [station]. */
private fun feederKey(town: Int, station: Int): Long = (1L shl 60) or (town.toLong() shl 24) or station.toLong()

/** The key [route] is remembered by in [Rival.failed] once given up. */
private fun keyOf(route: RivalRoute): Long = when {
    route.rail -> railKey(route.townA, route.townB, route.cargo)
    route.feeder -> feederKey(route.townA, route.b)
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
        companies += Company(id, rivalName(home, id), Prices.startMoney(startYear)).also { giveInsignia(it) }
        rivals += Rival(id, home.id).also { it.thinkOn = workDay + 3 + i * 9 }
    }
}

/**
 * A name from the home town in its own language, or from the nearest town no company is named after if another
 * company has the home town's, and that no other company has.
 */
private fun World.rivalName(home: Town, id: Int): String {
    val kind = rng.below(TOWN_NAME_KINDS)
    return townCompanyName(nameTown(home, id), kind, id)
}

/** Each calendar day: any rival due to think does, every so many work days. */
internal fun World.rivalsDay() {
    // A copy, as one may buy out another while it thinks.
    for (r in rivals.toList()) {
        if (r !in rivals) continue
        companies.getOrNull(r.company)?.let { c -> for (route in r.routes) if (route.rail) unblockDepot(c, route) }
        if (workDay < r.thinkOn) continue
        r.thinkOn = workDay + if (settings.rivalLevel == RivalLevel.HARD) HARD_THINK_DAYS else THINK_DAYS
        val c = companies.getOrNull(r.company) ?: continue
        think(r, c)
    }
}

private fun World.think(r: Rival, c: Company) {
    // Out of money: borrow, a step at a time. Well off, or losing money on what it owes: pay the loan back.
    while (c.money < 0 && c.loan < maxLoan()) apply(Command.Loan(c.id, true))
    val keep = if (shedding(c)) 0L else price(Prices.LOAN_STEP) * 3
    while (c.loan > 0 && c.money > price(Prices.LOAN_STEP) + keep) {
        val was = c.loan
        apply(Command.Loan(c.id, false))
        if (c.loan == was) break
    }
    buyStruggling(c)
    if (c.gone) return
    // Its vehicles are renewed as they age, keeping two loan steps in hand, but not while it sells to pay its loan.
    c.autorenew = !shedding(c)
    c.renewFloor = price(Prices.LOAN_STEP) * 2
    shed(c)
    sellLosers(r, c)
    townWork(r, c)
    for (route in r.routes) growRoute(c, route)
    for (route in r.routes.toList()) if (route.rail && !route.freight) feeders(r, c, route)
    // A hard rival with money to spare opens two at a look.
    val opens = if (settings.rivalLevel == RivalLevel.HARD && c.money - c.loan > maxLoan()) 2 else 1
    repeat(opens) {
        val running = r.routes.count { !it.feeder }
        if (running >= maxRoutes(c)) return
        // On borrowed money, only once what it runs pays.
        if (c.loan > 0 && r.routes.isNotEmpty() && profitAWorkYear(c) <= 0) return
        // Each level's kinds in turn; anything it can't do this time, the next kind.
        val kinds = KINDS[level()]
        var done = false
        for (k in kinds.indices) {
            val tried = when (kinds[(running + k) % kinds.size]) {
                KIND_BUS -> newRoute(r, c)
                KIND_TRUCK -> newFreightRoute(r, c)
                else -> newRailRoute(r, c)
            }
            if (tried == Tried.POOR) return
            if (tried == Tried.BUILT) { done = true; break }
        }
        if (!done) return
    }
}

/** What each level opens in turn, easy to hard: buses, trucks, railways. */
private const val KIND_BUS = 0
private const val KIND_TRUCK = 1
private const val KIND_RAIL = 2
private val KINDS = listOf(
    intArrayOf(KIND_BUS, KIND_TRUCK, KIND_BUS, KIND_TRUCK, KIND_RAIL),
    intArrayOf(KIND_BUS, KIND_TRUCK, KIND_RAIL),
    intArrayOf(KIND_RAIL, KIND_BUS, KIND_RAIL, KIND_TRUCK),
)

/** Work days so far this calendar year. */
internal fun World.workedThisYear(): Long = Calendar.dayOfYear(startYear, day).toLong() * settings.dayLength

/** Work days into the calendar year before this year's books are a fair guide to a route's pay. */
internal const val FEW_MONTHS = 90

/** Whether a vehicle built on work day [built] ran the whole of last calendar year, so its profit then is a full year's. */
private fun World.ranLastYear(built: Int) = Calendar.year(startYear, built / settings.dayLength) < Calendar.year(startYear, day) - 1

/**
 * Whether a vehicle built on work day [built] lost money: last calendar year if it ran all of it. Past day length 1
 * a newer one is judged by all its books once it has run two work years, so a new loser isn't kept for the many work
 * years in a calendar year. Part of a calendar year isn't enough to judge one that ran all of last year, as winter
 * would sell lines that pay over the year.
 */
internal fun World.lostAWorkYear(built: Int, thisYear: Long, lastYear: Long): Boolean {
    if (ranLastYear(built)) return lastYear < 0
    return settings.dayLength > 1 && workDay - built >= 2 * 365 && thisYear + lastYear < 0
}

/**
 * Sends vehicles that lose money ([lostAWorkYear]) to their garage and sells them once there. A route left with none
 * is given up: its stops and garage come down, a railway's track too, the road stays, and it isn't tried again for a while.
 */
internal fun World.sellLosers(r: Rival, c: Company) {
    // Its own vehicles only stand stopped in a depot when sent there to be sold.
    for (v in roadVehicles) {
        if (v == null || v.owner != c.id || v.wreckedFor > 0) continue
        if (v.inDepot && !v.running) apply(Command.RoadOrder(c.id, v.id, Command.TrainOrder.SELL))
        else if (!v.inDepot && (!v.toDepot || v.servicing) && lostAWorkYear(v.built, v.profitThisYear, v.profitLastYear)) {
            // One on its way for a service is turned off that first, then sent to stay.
            if (v.toDepot) apply(Command.RoadOrder(c.id, v.id, Command.TrainOrder.TO_DEPOT))
            apply(Command.RoadOrder(c.id, v.id, Command.TrainOrder.TO_DEPOT))
        }
    }
    for (t in trains) {
        if (t == null || t.owner != c.id || t.wreckedFor > 0) continue
        // One stopped there that no longer loses money is set going again, unless the company is selling to pay its loan.
        val sell = lostAWorkYear(t.built, t.profitThisYear, t.profitLastYear) || shedding(c)
        if (t.inDepot && !t.running) apply(Command.TrainOrder(c.id, t.id, if (sell) Command.TrainOrder.SELL else Command.TrainOrder.START_STOP))
        else if (!t.inDepot && !t.toDepot && lostAWorkYear(t.built, t.profitThisYear, t.profitLastYear)) {
            apply(Command.TrainOrder(c.id, t.id, Command.TrainOrder.TO_DEPOT))
        }
    }
    val gone = r.routes.filter { route -> (if (route.rail) trains else roadVehicles).none { it != null && it.owner == c.id && onRoute(it, route) } }
    for (route in gone) {
        r.routes -= route
        giveUp(r, keyOf(route))
        // A railway's line is its own, so it all comes up; stations and depots another route still uses stay.
        if (route.rail) takeUpLine(c, route)
        // Its stops come down unless another road route uses them, its platforms unless another railway does.
        for (id in listOf(route.a, route.b)) {
            val s = stations.getOrNull(id) ?: continue
            val others = r.routes.filter { it.a == id || it.b == id }
            val tiles = (if (others.none { !it.rail }) s.stops.toList() else emptyList()) + (if (others.none { it.rail }) s.tiles.toList() else emptyList())
            for (i in tiles) clearTile(c, Command.Clear(c.id, i % width, i / width))
        }
        // A railway's feeder buses go with it.
        if (route.rail) for (f in r.routes) if (f.feeder && (f.b == route.a || f.b == route.b)) {
            for (v in roadVehicles) if (v != null && v.owner == c.id && onRoute(v, f) && !v.toDepot) apply(Command.RoadOrder(c.id, v.id, Command.TrainOrder.TO_DEPOT))
        }
        if (r.routes.none { it.depot == route.depot }) depots.getOrNull(route.depot)?.let { clearTile(c, Command.Clear(c.id, it.tile % width, it.tile / width)) }
    }
}

/**
 * Takes up the track of [route], a railway given up: its two stations, the shortest run of [c]'s plain track between
 * them, its passing loops and the signal tile by its depot. Junctions, signals and other stations not its own stop the
 * search, so track that came with a company it bought and was joined on stays, and a line that can't be followed
 * without passing one is left whole.
 */
private fun World.takeUpLine(c: Company, route: RivalRoute) {
    val sa = stations.getOrNull(route.a) ?: return
    val sb = stations.getOrNull(route.b) ?: return
    val extras = railExtras(c, route)
    val line = LinkedHashSet(lineTiles(c, route, extras) ?: return)
    fun own(i: Int) = owner[i].toInt() == c.id && Track.PIECES.count { track[i].toInt() and it != 0 } == 1 && i !in signals
    for (t in sa.tiles) if (own(t)) line += t
    for (t in sb.tiles) if (own(t)) line += t
    for (t in extras) if (owner[t].toInt() == c.id) line += t
    // Stations used by another of its routes stay.
    fun kept(i: Int) = stationTiles[i]?.let { id -> rivals.any { rv -> rv.company == c.id && rv.routes.any { it.rail && (it.a == id || it.b == id) } } } == true
    for (t in line) if (!kept(t)) clearTile(c, Command.Clear(c.id, t % width, t / width))
}

/** Whether [v] runs [route]; a train may have its two stops either way round. */
internal fun onRoute(v: Carrier, route: RivalRoute): Boolean {
    if (v.orders.size != 2) return false
    val a = Order.station(v.orders[0])
    val b = Order.station(v.orders[1])
    return a == route.a && b == route.b || route.rail && a == route.b && b == route.a
}

/**
 * More routes as work years go by, and more again for money in hand beyond what the bank lends in a go, up to the
 * level's most.
 */
private fun World.maxRoutes(c: Company): Int {
    val years = workDay / 365
    val spare = ((c.money - c.loan) / maxLoan()).toInt().coerceIn(0, MAX_ROUTES)
    return when (settings.rivalLevel) {
        RivalLevel.EASY -> (1 + years / 3 + spare / 2).coerceAtMost(EASY_ROUTES)
        RivalLevel.HARD -> (2 + years + spare).coerceAtMost(HARD_ROUTES)
        else -> (1 + years / 2 + spare).coerceAtMost(MAX_ROUTES)
    }
}

/** Whether a player has a station within [PLAYER_REACH] of ([x], [y]), which easy rivals keep clear of. */
private fun World.playerNear(x: Int, y: Int): Boolean =
    stations.any { it != null && human(it.owner) && !it.waypoint && chebyshev(it.signX, it.signY, x, y) <= PLAYER_REACH }

/** Easy rivals keep clear of towns the player serves. */
private fun World.leaveAlone(t: Town) = settings.rivalLevel == RivalLevel.EASY && playerNear(t.x, t.y)

/** Whether a company other than [company] already has a passenger station in both [t1] and [t2], so the pair's taken. */
private fun World.pairTaken(company: Int, t1: Town, t2: Town): Boolean {
    // A rival's new stops count before anyone's called at them.
    val rivalStops = HashSet<Int>()
    for (r in rivals) for (route in r.routes) if (!route.freight) { rivalStops += route.a; rivalStops += route.b }
    fun owners(t: Town) = stations.filter {
        it != null && it.owner != company && !it.waypoint && chebyshev(it.signX, it.signY, t.x, t.y) <= TOWN_AREA / 2 &&
            (it.lastCall[Cargo.PASSENGERS] >= 0 || it.id in rivalStops)
    }.map { it!!.owner }.toSet()
    val a = owners(t1)
    return a.isNotEmpty() && owners(t2).any { it in a }
}

/**
 * Now and then, what a rival pays a town for, as its difficulty allows: a statue at home and advertising where its
 * stations are poorly rated; and when hard, exclusive rights and road works where the player runs. One a month at most,
 * and only what it can well afford.
 */
private fun World.townWork(r: Rival, c: Company) {
    val level = settings.rivalLevel
    if (level == RivalLevel.EASY || rng.below(3) != 0) return
    // Out of what it makes: a sixth of a work year's profit at most, and three times that in hand.
    fun rich(action: Int) = c.money - c.loan > townActionCost(action) * 3 && profitAWorkYear(c) > townActionCost(action) * 6
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

/**
 * What [c] makes a work year after running costs, upkeep and interest: by this calendar year's books once a work year
 * of it has gone by, so a long day length doesn't judge by a year ten work years old, else by last year's.
 */
private fun World.profitAWorkYear(c: Company): Long {
    val worked = Calendar.dayOfYear(startYear, day).toLong() * settings.dayLength
    return if (worked >= 365 && settings.dayLength > 1) made(c.thisYear) * 365 / worked else made(c.lastYear) / settings.dayLength
}

/** A year's books after running costs, upkeep and interest. */
private fun made(y: LongArray) = y[Books.INCOME] + y[Books.RUNNING] + y[Books.UPKEEP] + y[Books.INTEREST]

/**
 * Whether rival [c] owes the bank and lost money on what it runs last year and still does, so it pays back what it
 * can and sells to do it. Both years, so a line just opened on borrowed money has its time to pay.
 */
private fun World.shedding(c: Company): Boolean = !human(c.id) && c.loan > 0 && made(c.lastYear) < 0 && profitAWorkYear(c) < 0

/**
 * A rival in [shedding] sends its least paying vehicle that has run a work year to its depot to be sold, one a look,
 * till what's left pays its interest or the loan is paid off.
 */
private fun World.shed(c: Company) {
    if (!shedding(c)) return
    val old = { built: Int -> workDay - built >= 365 }
    val road = roadVehicles.filter { it != null && it.owner == c.id && it.wreckedFor == 0 && !it.inDepot && !it.toDepot && old(it.built) }.map { it!! }
    val rail = trains.filter { it != null && it.owner == c.id && it.wreckedFor == 0 && !it.inDepot && !it.toDepot && old(it.built) }.map { it!! }
    val v = road.minByOrNull { it.profitThisYear + it.profitLastYear }
    val t = rail.minByOrNull { it.profitThisYear + it.profitLastYear }
    if (t != null && (v == null || t.profitThisYear + t.profitLastYear < v.profitThisYear + v.profitLastYear)) {
        apply(Command.TrainOrder(c.id, t.id, Command.TrainOrder.TO_DEPOT))
    } else if (v != null) {
        apply(Command.RoadOrder(c.id, v.id, Command.TrainOrder.TO_DEPOT))
    }
}

/** Adds a vehicle to [route] when it's leaving people behind at either end, or for a railway a train or a passing loop. */
internal fun World.growRoute(c: Company, route: RivalRoute) {
    if (route.rail) return growRail(c, route, RAIL_PAYBACK[level()]) { ranLastYear(it) }
    val a = stations.getOrNull(route.a) ?: return
    val b = stations.getOrNull(route.b) ?: return
    val on = roadVehicles.filter { it != null && it.owner == c.id && onRoute(it, route) }.map { it!! }
    if (on.size >= MAX_PER_ROUTE || on.any { it.toDepot && !it.servicing || ranLastYear(it.built) && it.profitLastYear < 0 }) return
    // Only on a route that pays: last year's profit, or this year's once it's a few months of work in.
    val lastYear = on.sumOf { it.profitLastYear }
    val thisYear = on.sumOf { it.profitThisYear }
    if (lastYear <= 0 && (workedThisYear() < FEW_MONTHS || thisYear <= 0)) return
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
        if (netWorth(c) < cost * 3 || !afford(c, cost * 2)) continue
        apply(Command.BuyCompany(c.id, o.id))
    }
}

/** Whether [c] has [cost] or can borrow it, borrowing what it needs; an easy rival borrows only half what the bank lends. */
internal fun World.afford(c: Company, cost: Long): Boolean {
    val most = if (settings.rivalLevel == RivalLevel.EASY && !human(c.id)) maxLoan() / 2 else maxLoan()
    // A computer company keeps a little in hand past it, for the bills that come before the takings.
    val spare = if (human(c.id)) 0L else price(Prices.LOAN_STEP) * SPARE_STEPS
    if (c.money + (most - c.loan) < cost + spare) return false
    while (c.money < cost && c.loan < most) apply(Command.Loan(c.id, true))
    return c.money >= cost
}

/** Whether [c] has [cost] in hand without borrowing, and a loan step over, for growing what it has. */
internal fun World.inHand(c: Company, cost: Long): Boolean = c.money >= cost + price(Prices.LOAN_STEP)

/** The road vehicle for [cargo] on sale now that carries the most the fastest, trams and plows left out. */
internal fun World.bestModel(cargo: Int): Engine? {
    val year = Calendar.year(startYear, day)
    return roster.availableRoad(year).filter { !it.tram && it.plow == Plows.NONE && cargo in it.cargoes && it.capacity > 0 }
        .maxWithOrNull(compareBy<Engine> { it.capacity.toLong() * it.speed }.thenBy { -it.id })
}

internal fun World.buyAndStart(c: Company, route: RivalRoute, model: Engine) {
    val before = roadVehicles.size
    apply(Command.BuyRoadVehicle(c.id, route.depot, model.id, route.cargo))
    if (roadVehicles.size == before) return
    val id = roadVehicles.size - 1
    // Freight waits for a full load at the first stop and comes back empty.
    val orders = if (route.freight) intArrayOf(route.a or Order.FULL_LOAD, route.b or Order.NO_LOAD) else intArrayOf(route.a, route.b)
    apply(Command.RoadOrder(c.id, id, Command.TrainOrder.ORDERS, orders))
    apply(Command.RoadOrder(c.id, id, Command.TrainOrder.START_STOP))
}

/** How far ([x], [y]) is from [r]'s [home] or the nearest of its stations, so it grows out from what it has. */
private fun World.reach(r: Rival, home: Town, x: Int, y: Int): Int {
    var best = chebyshev(x, y, home.x, home.y)
    for (route in r.routes) for (id in intArrayOf(route.a, route.b)) stations.getOrNull(id)?.let { best = minOf(best, chebyshev(x, y, it.signX, it.signY)) }
    return best
}

/** Tries the most promising pairs of towns not yet joined by this rival, nearest its home or its stations first. */
internal fun World.newRoute(r: Rival, c: Company, payback: Int = ROAD_PAYBACK[level()]): Tried {
    val model = bestModel(Cargo.PASSENGERS) ?: return Tried.CANT
    val home = towns.getOrNull(r.home) ?: return Tried.CANT
    val served = r.routes.map { pairKey(it.townA, it.townB) }.toSet()
    val pairs = ArrayList<Triple<Town, Town, Long>>()
    for (t1 in towns) {
        if (t1.population < MIN_TOWN || reach(r, home, t1.x, t1.y) > HOME_REACH) continue
        for (t2 in towns) {
            if (t2.id <= t1.id || t2.population < MIN_TOWN) continue
            val d = chebyshev(t1.x, t1.y, t2.x, t2.y)
            if (d < MIN_APART || d > MAX_APART) continue
            val key = pairKey(t1.id, t2.id)
            if (key in served || triedLately(r, key) || pairTaken(c.id, t1, t2)) continue
            // A town that won't have it at either end now may later.
            if (townRefuses(c, t1.x, t1.y) || townRefuses(c, t2.x, t2.y) || leaveAlone(t1) || leaveAlone(t2)) continue
            // Bigger towns, closer together, nearer home or its stations.
            val score = minOf(t1.population, t2.population).toLong() * 1000 / (d + reach(r, home, t1.x, t1.y) / 2 + 5)
            // A subsidy on offer makes it worth twice as much.
            pairs += Triple(t1, t2, if (onOffer(Cargo.PASSENGERS, t1.id, t2.id, true)) score * 2 else score)
        }
    }
    pairs.sortWith(compareByDescending<Triple<Town, Town, Long>> { it.third }.thenBy { it.first.id }.thenBy { it.second.id })
    for ((t1, t2, _) in pairs.take(TRIES)) {
        when (val t = tryRoute(r, c, t1, t2, model, payback)) {
            Tried.CANT -> giveUp(r, pairKey(t1.id, t2.id))
            else -> return t
        }
    }
    return Tried.CANT
}

/**
 * Tries the most promising industries near home not yet served by this rival, each to the nearest that takes what
 * it makes, by how much it makes and how near.
 */
internal fun World.newFreightRoute(r: Rival, c: Company, payback: Int = ROAD_PAYBACK[level()]): Tried {
    val home = towns.getOrNull(r.home) ?: return Tried.CANT
    val served = r.routes.filter { it.freight }.map { it.townA to it.cargo }.toSet()
    class Pick(val from: Industry, val to: Industry, val cargo: Int, val model: Engine, val score: Long)
    val picks = ArrayList<Pick>()
    for (ind in industries) {
        if (ind.closed) continue
        val kind = roster.industries[ind.kind]
        val (cx, cy) = middle(ind)
        if (reach(r, home, cx, cy) > HOME_REACH) continue
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
                if (d < MIN_APART || d > MAX_APART || d >= best || triedLately(r, freightKey(ind.id, o.id, cg))) continue
                to = o
                best = d
            }
            if (to == null) continue
            val (ox, oy) = middle(to)
            if (townRefuses(c, cx, cy) || townRefuses(c, ox, oy)) continue
            val score = month.toLong() * 1000 / (best + reach(r, home, cx, cy) / 2 + 5)
            picks += Pick(ind, to, cg, model, if (onOffer(cg, ind.id, to.id, false)) score * 2 else score)
        }
    }
    picks.sortWith(compareByDescending<Pick> { it.score }.thenBy { it.from.id }.thenBy { it.cargo })
    for (p in picks.take(TRIES)) {
        when (val t = tryFreight(r, c, p.from, p.to, p.cargo, p.model, payback)) {
            Tried.CANT -> giveUp(r, freightKey(p.from.id, p.to.id, p.cargo))
            else -> return t
        }
    }
    return Tried.CANT
}

/**
 * Tries a railway: from an industry near home to the nearest that takes what it makes, or between two big towns,
 * farther apart than road vehicles go, the longer the better up to a point.
 */
internal fun World.newRailRoute(r: Rival, c: Company, payback: Int = RAIL_PAYBACK[level()]): Tried {
    val home = towns.getOrNull(r.home) ?: return Tried.CANT
    val served = r.routes.filter { it.rail }.map { keyOf(it) }.toSet()
    // Its own trucks at an industry would take half of what a train waits for.
    val trucked = r.routes.filter { it.freight }.map { it.townA to it.cargo }.toSet()
    class Pick(val from: Int, val to: Int, val cargo: Int, val load: Int, val sa: RailSite, val sb: RailSite, val score: Long)
    val picks = ArrayList<Pick>()
    for (ind in industries) {
        if (ind.closed) continue
        val kind = roster.industries[ind.kind]
        val (cx, cy) = middle(ind)
        val fromHome = reach(r, home, cx, cy)
        if (fromHome > HOME_REACH) continue
        for (cg in 0 until Cargo.COUNT) {
            val month = kind.produces[cg] * ind.level / 16
            if (month < RAIL_LEAST || (ind.id to cg) in trucked || servedByOthers(ind, cg, c.id)) continue
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
            if (key in served || triedLately(r, key)) continue
            val (ox, oy) = middle(to)
            if (townRefuses(c, cx, cy) || townRefuses(c, ox, oy)) continue
            // About what it'll get in a month: its station's rating takes a share and the weather some more.
            picks += Pick(ind.id, to.id, cg, month / 2, railSite(ind, ox, oy), railSite(to, cx, cy), month.toLong() * minOf(best, RAIL_BEST) * 100 / (fromHome + 10))
        }
    }
    for (t1 in towns) {
        if (t1.population < RAIL_TOWN) continue
        val fromHome = reach(r, home, t1.x, t1.y)
        if (fromHome > HOME_REACH) continue
        for (t2 in towns) {
            if (t2.id == t1.id || t2.population < RAIL_TOWN) continue
            val d = chebyshev(t1.x, t1.y, t2.x, t2.y)
            if (d < RAIL_MIN || d > RAIL_MAX) continue
            val key = railKey(minOf(t1.id, t2.id), maxOf(t1.id, t2.id), Cargo.PASSENGERS)
            if (key in served || triedLately(r, key) || t2.id < t1.id && reach(r, home, t2.x, t2.y) <= HOME_REACH) continue
            if (pairTaken(c.id, t1, t2)) continue
            if (townRefuses(c, t1.x, t1.y) || townRefuses(c, t2.x, t2.y) || leaveAlone(t1) || leaveAlone(t2)) continue
            // Its load comes from the people its stations reach, worked out where they go.
            picks += Pick(minOf(t1.id, t2.id), maxOf(t1.id, t2.id), Cargo.PASSENGERS, 0, railSite(t1, t2.x, t2.y), railSite(t2, t1.x, t1.y),
                minOf(t1.population, t2.population).toLong() * minOf(d, RAIL_BEST) * 100 / (fromHome + 10))
        }
    }
    picks.sortWith(compareByDescending<Pick> { it.score }.thenBy { it.from }.thenBy { it.to }.thenBy { it.cargo })
    for (p in picks.take(RAIL_TRIES)) {
        val freight = p.cargo != Cargo.PASSENGERS
        val serves = { s: Station, first: Boolean ->
            if (freight) stationIndustries[s.id]?.contains(if (first) p.from else p.to) == true else (stationPeople[s.id] ?: 0) > 0
        }
        when (val t = tryRail(c, p.sa, p.sb, p.cargo, p.load, p.from, p.to, serves, payback) { r.routes += it }) {
            Tried.CANT -> giveUp(r, railKey(p.from, p.to, p.cargo))
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
        stations.getOrNull(id)?.let { it.owner != company && !(hard && human(it.owner)) && it.lastCall[cg] >= 0 } == true
    }
}

/** The tile in the middle of [ind]. */
internal fun World.middle(ind: Industry): Pair<Int, Int> {
    val kind = roster.industries[ind.kind]
    return ind.x + kind.across(ind.turn) / 2 to ind.y + kind.along(ind.turn) / 2
}

/** The rivals' level, as an index into the tables by level. */
private fun World.level() = settings.rivalLevel.coerceIn(RivalLevel.EASY, RivalLevel.HARD)

/**
 * Whether a road route is expected to pay for itself within [payback] work years, or any will do if that's 0: as many
 * [model]s as it takes, up to [MAX_PER_ROUTE], carrying [cargo] along [path] on [surface], about [load] a month (each
 * way for passengers), with [built] spent on its road, stops and garage.
 */
private fun World.roadPays(model: Engine, cargo: Int, load: Int, path: IntArray, surface: Int, built: Long, payback: Int): Boolean {
    if (payback <= 0) return true
    val first = path.first()
    val last = path.last()
    val dist = kotlin.math.abs((first and 0xffff) - (last and 0xffff)) + kotlin.math.abs((first ushr 16) - (last ushr 16))
    // A tile a day at 40-odd km/h, a fifth more for corners and stops, and a day at each end.
    val there = path.size * KMH_PER_TILE_A_DAY * 6 / (5 * model.speed.coerceAtLeast(1)) + 2
    val trips = (365 / (there * 2)).coerceAtLeast(1)
    val wanted = load * 12
    val n = ((wanted + trips * model.capacity - 1) / (trips * model.capacity).coerceAtLeast(1)).coerceIn(2, MAX_PER_ROUTE)
    val hauled = minOf(n * trips * model.capacity, wanted)
    // Freight waits on board for a full load, half of it the whole time it takes to fill.
    val filling = if (cargo == Cargo.PASSENGERS) 0 else model.capacity * 15 / load.coerceAtLeast(1)
    val late = (there + filling - dist * Cargo.PACE[cargo] - Cargo.GRACE[cargo]).coerceAtLeast(0)
    val share = (100 - late * Cargo.DECAY[cargo]).coerceAtLeast(33)
    val ways = if (cargo == Cargo.PASSENGERS) 2 else 1
    val income = pay(hauled.toLong() * dist * Cargo.RATE[cargo] * share / 10_000) * ways
    // Upkeep on its new road and two stops, in tenths of a cent a work day.
    val fresh = path.count { road[tileOf(it)].toInt() == 0 }
    val upkeep = price((fresh.toLong() * Upkeep.ROAD[surface] + 2 * Upkeep.STOP) * 365 / 1_000)
    return (income - price(model.running) * n - upkeep) * payback >= built + price(model.cost) * n
}

/**
 * Joins industries [from] and [to] by road with a stop at each, a garage and two trucks for [cargo], if it'll pay within
 * [payback] work years. [loose] looks further for a garage, for the scripted test player.
 */
internal fun World.tryFreight(r: Rival, c: Company, from: Industry, to: Industry, cargo: Int, model: Engine, payback: Int, loose: Boolean = false): Tried {
    val (tx, ty) = middle(to)
    val path = roadPath(around(from), around(to).toHashSet(), tx, ty) ?: return Tried.CANT
    val year = Calendar.year(startYear, day)
    val surface = Surface.entries(year).firstOrNull { Surface.SPEED[it] >= model.speed } ?: Surface.entries(year).last()
    val runs = runs(c.id, path, surface)
    val roadCost = runs.sumOf { roadCost(c, it).coerceAtLeast(0) }
    val cost = roadCost + price(Prices.ROAD_STOP) * 2 + price(Prices.DEPOT) + price(model.cost) * 2
    val month = roster.industries[from.kind].produces[cargo] * from.level / 16
    if (!roadPays(model, cargo, month / 2, path, surface, cost - price(model.cost) * 2, payback)) return Tried.CANT
    // Where the stops and garage go is found before anything's spent.
    val laid = laidEdges(path)
    val joined = joinedBy(path, laid)
    val siteA = stopSiteBy(c, from, path, true, laid, joined, -1) ?: return Tried.CANT
    siteA.spur?.let { addSpur(laid, it) }
    val siteB = stopSiteBy(c, to, path, false, laid, joined, siteA.tile) ?: return Tried.CANT
    siteB.spur?.let { addSpur(laid, it) }
    val garage = garageSite(siteA.tile, laid, joined, loose) ?: return Tried.CANT
    if (!afford(c, cost * 6 / 5)) return Tried.POOR
    for (run in runs) apply(run)
    // Something that fails even so comes down again; the road stays, as anyone can use it.
    val stopA = stopAt(c, siteA) ?: placeStopBy(c, from, path, true) ?: return Tried.CANT
    val stopB = stopAt(c, siteB) ?: placeStopBy(c, to, path, false) ?: return undo(c, stopA)
    if (!roadConnected(stopA, stopB)) return undo(c, stopA, stopB)
    val depot = garageAt(c, garage, stopA) ?: placeGarage(c, stopA) ?: return undo(c, stopA, stopB)
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

/**
 * As [tryFreight] but to a stop in town [to], for cargo a town takes (goods, food, mail): any road that can be built,
 * paying or not. Only the scripted test player uses it.
 */
internal fun World.tryFreightToTown(r: Rival, c: Company, from: Industry, to: Town, cargo: Int, model: Engine): Tried {
    val ends = ArrayList<Int>()
    for (y in to.y - STOP_REACH..to.y + STOP_REACH) for (x in to.x - STOP_REACH..to.x + STOP_REACH) {
        if (inside(x, y) && roadEdges(index(x, y)) != 0 && townOf[index(x, y)].toInt() == to.id) ends += index(x, y)
    }
    val path = roadPath(around(from), ends.toHashSet(), to.x, to.y) ?: return Tried.CANT
    val year = Calendar.year(startYear, day)
    val surface = Surface.entries(year).firstOrNull { Surface.SPEED[it] >= model.speed } ?: Surface.entries(year).last()
    val runs = runs(c.id, path, surface)
    val cost = runs.sumOf { roadCost(c, it).coerceAtLeast(0) } + price(Prices.ROAD_STOP) * 2 + price(Prices.DEPOT) + price(model.cost) * 2
    val laid = laidEdges(path)
    val joined = joinedBy(path, laid)
    val siteA = stopSiteBy(c, from, path, true, laid, joined, -1) ?: return Tried.CANT
    siteA.spur?.let { addSpur(laid, it) }
    val siteB = stopSite(c, to.x - STOP_REACH, to.y - STOP_REACH, to.x + STOP_REACH, to.y + STOP_REACH, path.last(), laid, joined, siteA.tile) ?: return Tried.CANT
    val garage = garageSite(siteA.tile, laid, joined, true) ?: return Tried.CANT
    if (!afford(c, cost * 6 / 5)) return Tried.POOR
    for (run in runs) apply(run)
    val stopA = stopAt(c, siteA) ?: placeStopBy(c, from, path, true) ?: return Tried.CANT
    val stopB = stopAt(c, StopSite(siteB, null)) ?: placeStop(c, to, path.last()) ?: return undo(c, stopA)
    if (!roadConnected(stopA, stopB)) return undo(c, stopA, stopB)
    val depot = garageAt(c, garage, stopA) ?: placeGarage(c, stopA) ?: return undo(c, stopA, stopB)
    val a = roadStops.getValue(stopA)
    val b = roadStops.getValue(stopB)
    if (catchmentDirty) workOutCatchment()
    if (a == b || stationIndustries[a]?.contains(from.id) != true || stations.getOrNull(b)?.let { stationTown(it)?.id } != to.id) return undo(c, stopA, stopB)
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

/**
 * Joins towns [t1] and [t2] by road with a stop in each, a garage and two vehicles, if it can and can pay, and it'll
 * pay within [payback] work years. [loose] looks further for a garage, for the scripted test player.
 */
internal fun World.tryRoute(r: Rival, c: Company, t1: Town, t2: Town, model: Engine, payback: Int, loose: Boolean = false): Tried {
    val path = roadPath(t1, t2) ?: return Tried.CANT
    val year = Calendar.year(startYear, day)
    // The cheapest surface the vehicles can go their top speed on.
    val surface = Surface.entries(year).firstOrNull { Surface.SPEED[it] >= model.speed } ?: Surface.entries(year).last()
    val runs = runs(c.id, path, surface)
    val roadCost = runs.sumOf { roadCost(c, it).coerceAtLeast(0) }
    val cost = roadCost + price(Prices.ROAD_STOP) * 2 + price(Prices.DEPOT) + price(model.cost) * 2
    // What a stop in the middle of the smaller town gets a month, each way.
    if (!roadPays(model, Cargo.PASSENGERS, minOf(t1.population, t2.population) / 3, path, surface, cost - price(model.cost) * 2, payback)) return Tried.CANT
    // Where the stops and garage go is found before anything's spent.
    val laid = laidEdges(path)
    val joined = joinedBy(path, laid)
    val siteA = stopSite(c, t1.x - STOP_REACH, t1.y - STOP_REACH, t1.x + STOP_REACH, t1.y + STOP_REACH, path.first(), laid, joined, -1) ?: return Tried.CANT
    val siteB = stopSite(c, t2.x - STOP_REACH, t2.y - STOP_REACH, t2.x + STOP_REACH, t2.y + STOP_REACH, path.last(), laid, joined, siteA) ?: return Tried.CANT
    val garage = garageSite(siteA, laid, joined, loose) ?: return Tried.CANT
    if (!afford(c, cost * 6 / 5)) return Tried.POOR
    for (run in runs) apply(run)
    // Something that fails even so comes down again; the road stays, as anyone can use it.
    val stopA = stopAt(c, StopSite(siteA, null)) ?: placeStop(c, t1, path.first()) ?: return Tried.CANT
    val stopB = stopAt(c, StopSite(siteB, null)) ?: placeStop(c, t2, path.last()) ?: return undo(c, stopA)
    if (!roadConnected(stopA, stopB)) return undo(c, stopA, stopB)
    val depot = garageAt(c, garage, stopA) ?: placeGarage(c, stopA) ?: return undo(c, stopA, stopB)
    val a = roadStops.getValue(stopA)
    val b = roadStops.getValue(stopB)
    val route = RivalRoute(a, b, depot, t1.id, t2.id, Cargo.PASSENGERS)
    r.routes += route
    repeat(2) { buyAndStart(c, route, model) }
    return Tried.BUILT
}

/**
 * Feeder buses for passenger railway [route]: from a stop in the middle of each end's town its platforms don't reach to
 * a stop beside its station, which joins it, so people change to the train there.
 */
private fun World.feeders(r: Rival, c: Company, route: RivalRoute) {
    for (sid in intArrayOf(route.a, route.b)) {
        val s = stations.getOrNull(sid) ?: continue
        // The end's town is whichever of the two is nearer the station.
        val t = listOfNotNull(towns.getOrNull(route.townA), towns.getOrNull(route.townB)).minByOrNull { chebyshev(it.x, it.y, s.signX, s.signY) } ?: continue
        if (r.routes.any { it.feeder && it.b == sid }) continue
        val key = feederKey(t.id, sid)
        if (triedLately(r, key)) continue
        if (s.tiles.any { chebyshev(it % width, it / width, t.x, t.y) <= CATCHMENT }) { giveUp(r, key); continue }
        when (tryFeeder(r, c, t, s)) {
            Tried.CANT -> giveUp(r, key)
            Tried.POOR -> return
            Tried.BUILT -> {}
        }
    }
}

/** Joins the middle of town [t] to railway station [s] by bus: a road to beside its platforms if there's none, a stop at each end, a garage and two buses. */
private fun World.tryFeeder(r: Rival, c: Company, t: Town, s: Station): Tried {
    val model = bestModel(Cargo.PASSENGERS) ?: return Tried.CANT
    if (s.tiles.isEmpty() || townRefuses(c, t.x, t.y)) return Tried.CANT
    val x0 = s.tiles.minOf { it % width } - 1
    val x1 = s.tiles.maxOf { it % width } + 1
    val y0 = s.tiles.minOf { it / width } - 1
    val y1 = s.tiles.maxOf { it / width } + 1
    val starts = ArrayList<Int>()
    for (y in t.y - STOP_REACH..t.y + STOP_REACH) for (x in t.x - STOP_REACH..t.x + STOP_REACH) {
        if (inside(x, y) && roadEdges(index(x, y)) != 0 && townOf[index(x, y)].toInt() == t.id) starts += index(x, y)
    }
    // Beside the platforms: road there already, or land a road can go on.
    val goals = HashSet<Int>()
    for (y in y0..y1) for (x in x0..x1) {
        if (!inside(x, y)) continue
        val i = index(x, y)
        if (!stationTiles.containsKey(i) && isFlat(x, y) && (roadEdges(i) != 0 || stepCost(i, Track.XM) != null)) goals += i
    }
    var path = roadPath(starts, goals, (x0 + x1) / 2, (y0 + y1) / 2) ?: return Tried.CANT
    if (path.size < 2) return Tried.CANT
    // A stop needs road straight through it: on past the last tile if it ends there.
    val last = tileOf(path.last())
    val laid = laidEdges(path)
    if (!straightEdges(laid.getValue(last))) {
        val e = dirOf(path[path.size - 2], path.last())
        val nx = last % width + Track.dx(e)
        val ny = last / width + Track.dy(e)
        if (!inside(nx, ny) || stepCost(index(nx, ny), e) == null) return Tried.CANT
        path += (ny shl 16) or nx
        if (!straightEdges(laidEdges(path).getValue(last))) return Tried.CANT
    }
    if (track[last].toInt() != 0 || roadStops.containsKey(last) || roadOwner[last].toInt().let { roadEdges(last) != 0 && it >= 0 && it != c.id }) return Tried.CANT
    val year = Calendar.year(startYear, day)
    val surface = Surface.entries(year).firstOrNull { Surface.SPEED[it] >= model.speed } ?: Surface.entries(year).last()
    val runs = runs(c.id, path, surface)
    val cost = runs.sumOf { roadCost(c, it).coerceAtLeast(0) } + price(Prices.ROAD_STOP) * 2 + price(Prices.DEPOT) + price(model.cost) * 2
    if (!inHand(c, cost)) return Tried.POOR
    for (run in runs) apply(run)
    apply(Command.RoadStop(c.id, last % width, last / width))
    if (roadStops[last] != s.id) return if (roadStops.containsKey(last)) undo(c, last) else Tried.CANT
    val stop = placeStop(c, t, path.first()) ?: return undo(c, last)
    val a = roadStops.getValue(stop)
    if (a == s.id) return undo(c, last, stop)
    val depot = placeGarage(c, stop) ?: return undo(c, last, stop)
    val route = RivalRoute(a, s.id, depot, t.id, t.id, Cargo.PASSENGERS)
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

/** Packed tile [p] (x in the low 16 bits, y above) as a tile index. */
private fun World.tileOf(p: Int) = index(p and 0xffff, p ushr 16)

/** Whether road edges [edges] make a straight road. */
private fun straightEdges(edges: Int) =
    edges == roadBit(Track.XM) or roadBit(Track.XP) || edges == roadBit(Track.YM) or roadBit(Track.YP)

/** The road edges each tile of [path] will have once it's laid, by tile index. */
private fun World.laidEdges(path: IntArray): HashMap<Int, Int> {
    val out = HashMap<Int, Int>()
    for (k in path.indices) {
        val i = tileOf(path[k])
        var e = out[i] ?: roadEdges(i)
        if (k > 0) e = e or roadBit(dirOf(path[k], path[k - 1]))
        if (k < path.size - 1) e = e or roadBit(dirOf(path[k], path[k + 1]))
        out[i] = e
    }
    return out
}

/** Whether tile [i] will be on the road [path] joins once [laid] is built: on it, or on road leading to either end. */
private fun World.joinedBy(path: IntArray, laid: Map<Int, Int>): (Int) -> Boolean {
    val first = tileOf(path.first())
    val last = tileOf(path.last())
    return { i -> i in laid || roadConnected(first, i) || roadConnected(last, i) }
}

/** A stop's tile and the spur of road to lay first to put it on, if any. */
private class StopSite(val tile: Int, val spur: Command.Road?)

/** Adds the edges [spur] lays to [laid]. */
private fun World.addSpur(laid: HashMap<Int, Int>, spur: Command.Road) {
    val e = dirOf(spur.sx or (spur.sy shl 16), spur.ex or (spur.ey shl 16))
    val o = Track.opposite(e)
    var x = spur.sx
    var y = spur.sy
    while (true) {
        val i = index(x, y)
        var bits = laid[i] ?: roadEdges(i)
        if (x != spur.sx || y != spur.sy) bits = bits or roadBit(o)
        if (x != spur.ex || y != spur.ey) bits = bits or roadBit(e)
        laid[i] = bits
        if (x == spur.ex && y == spur.ey) break
        x += Track.dx(e)
        y += Track.dy(e)
    }
}

/**
 * Where a drive-through stop can go once [laid] is built: straight, flat road from ([x0], [y0]) to ([x1], [y1]) that
 * [joined] says is on the new road's network, nearest [end] first, not next to tile [apart]; its tile.
 */
private fun World.stopSite(c: Company, x0: Int, y0: Int, x1: Int, y1: Int, end: Int, laid: Map<Int, Int>, joined: (Int) -> Boolean, apart: Int): Int? {
    val ex = end and 0xffff
    val ey = end ushr 16
    val tried = ArrayList<Int>()
    for (y in y0..y1) for (x in x0..x1) {
        if (!inside(x, y)) continue
        val i = index(x, y)
        if (!straightEdges(laid[i] ?: roadEdges(i)) || track[i].toInt() != 0 || roadStops.containsKey(i) || depotTiles.containsKey(i) || !isFlat(x, y)) continue
        val owned = roadOwner[i].toInt()
        if (roadEdges(i) != 0 && owned >= 0 && owned != c.id) continue
        if (apart >= 0 && chebyshev(x, y, apart % width, apart / width) <= 1) continue
        if (townRefuses(c, x, y)) continue
        tried += i
    }
    tried.sortWith(compareBy<Int> { chebyshev(it % width, it / width, ex, ey) }.thenBy { it })
    return tried.take(6).firstOrNull { joined(it) }
}

/** As [stopSite] round [ind], from the [start] or far end of [path], or on a short spur off the new road if there's no straight road there. */
private fun World.stopSiteBy(c: Company, ind: Industry, path: IntArray, start: Boolean, laid: Map<Int, Int>, joined: (Int) -> Boolean, apart: Int): StopSite? {
    val kind = roster.industries[ind.kind]
    val reach = CATCHMENT - 1
    val x0 = ind.x - reach
    val y0 = ind.y - reach
    val x1 = ind.x + kind.across(ind.turn) - 1 + reach
    val y1 = ind.y + kind.along(ind.turn) - 1 + reach
    val end = if (start) path.first() else path.last()
    stopSite(c, x0, y0, x1, y1, end, laid, joined, apart)?.let { return StopSite(it, null) }
    val near = (if (start) path.asList() else path.asList().asReversed()).take(SPUR_TRIES)
    for (p in near) {
        val px = p and 0xffff
        val py = p ushr 16
        if (px !in x0..x1 || py !in y0..y1) continue
        val pi = index(px, py)
        for (e in 0 until 4) {
            val mx = px + Track.dx(e)
            val my = py + Track.dy(e)
            val fx = mx + Track.dx(e)
            val fy = my + Track.dy(e)
            if (mx !in x0..x1 || my !in y0..y1 || !inside(fx, fy) || !isFlat(mx, my) || !isFlat(fx, fy)) continue
            val m = index(mx, my)
            val f = index(fx, fy)
            if (roadEdges(m) != 0 || roadEdges(f) != 0 || m in laid || f in laid) continue
            if (stepCost(m, e) == null || stepCost(f, e) == null || track[m].toInt() != 0) continue
            if (roadStops.containsKey(pi) || !roadFits(px, py, (laid[pi] ?: roadEdges(pi)) or roadBit(e))) continue
            if (apart >= 0 && chebyshev(mx, my, apart % width, apart / width) <= 1) continue
            if (townRefuses(c, mx, my)) continue
            return StopSite(m, Command.Road(c.id, px, py, fx, fy, Surface.entries(Calendar.year(startYear, day)).first(), remove = false))
        }
    }
    return null
}

/** Lays [site]'s spur and puts the stop on it; its tile, or null if it didn't go. */
private fun World.stopAt(c: Company, site: StopSite): Int? {
    site.spur?.let { apply(it) }
    apply(Command.RoadStop(c.id, site.tile % width, site.tile / width))
    return site.tile.takeIf { roadStops.containsKey(it) }
}

/**
 * Where a garage can go on free flat land beside the road near [stop] once [laid] is built, its door onto road
 * [joined] says is on the new road's network; its tile and door edge. [loose] looks [LOOSE_GARAGE_REACH] away.
 */
private fun World.garageSite(stop: Int, laid: Map<Int, Int>, joined: (Int) -> Boolean, loose: Boolean = false): Pair<Int, Int>? {
    val reach = if (loose) LOOSE_GARAGE_REACH else GARAGE_REACH
    val sx = stop % width
    val sy = stop / width
    fun edges(i: Int) = laid[i] ?: roadEdges(i)
    val spots = ArrayList<Pair<Int, Int>>()
    for (y in sy - reach..sy + reach) for (x in sx - reach..sx + reach) {
        if (!inside(x, y)) continue
        for (e in 0 until 4) {
            val nx = x + Track.dx(e)
            val ny = y + Track.dy(e)
            if (inside(nx, ny) && edges(index(nx, ny)) != 0) spots += index(x, y) to e
        }
    }
    spots.sortWith(compareBy<Pair<Int, Int>> { chebyshev(it.first % width, it.first / width, sx, sy) }.thenBy { it.first }.thenBy { it.second })
    for ((i, e) in spots.take(if (loose) 400 else 24)) {
        val x = i % width
        val y = i / width
        if (!isFlat(x, y) || edges(i) != 0 || house[i].toInt() != 0 || track[i].toInt() != 0 || isWater(x, y)) continue
        if (stationTiles.containsKey(i) || depotTiles.containsKey(i) || industryOf[i].toInt() != 0) continue
        val nx = x + Track.dx(e)
        val ny = y + Track.dy(e)
        val n = index(nx, ny)
        if (n == stop || track[n].toInt() != 0 || roadStops.containsKey(n) || depotTiles.containsKey(n)) continue
        if (!roadFits(nx, ny, edges(n) or roadBit(Track.opposite(e))) || !joined(n)) continue
        return i to e
    }
    return null
}

/** Builds garage [site] from [garageSite]; its depot id, or null if it didn't go or doesn't reach [stop]. */
private fun World.garageAt(c: Company, site: Pair<Int, Int>, stop: Int): Int? {
    val (i, e) = site
    if (!roadConnected(index(i % width + Track.dx(e), i / width + Track.dy(e)), stop)) return null
    val before = depots.size
    apply(Command.Garage(c.id, i % width, i / width, e))
    return if (depots.size > before) depots.size - 1 else null
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
    if (spanHeads.containsKey(i) || deck.containsKey(i) || canals.contains(i) || airportTiles.containsKey(i) || saltDepots.contains(i) || stationBuildings.containsKey(i)) return null
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
private const val RAIL_TOWN = 150
/** Railways tried at each look. */
private const val RAIL_TRIES = 3

/** Work years a rival's railway and its road routes have to pay for themselves in, easy to hard. */
private val RAIL_PAYBACK = intArrayOf(10, 12, 16)
private val ROAD_PAYBACK = intArrayOf(14, 16, 24)
/** Railways given up and town pairs and industries it couldn't join are tried again after this many work days. */
internal const val RETRY_DAYS = 365 * 5
/** Work days between a rival's looks at its business, and a hard one's. */
private const val THINK_DAYS = 30
private const val HARD_THINK_DAYS = 20
/** Loan steps a rival keeps unspent when it builds or buys. */
private const val SPARE_STEPS = 2
/** Most routes a rival runs: normal, easy and hard. */
private const val MAX_ROUTES = 8
private const val EASY_ROUTES = 5
private const val HARD_ROUTES = 14
/** How near the player's stations easy rivals keep away from, in tiles. */
private const val PLAYER_REACH = 10
/** A station rating, out of 255, poor enough for a rival to advertise. */
private const val POOR_RATING = 110
private const val MAX_PER_ROUTE = 6
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
private const val LOOSE_GARAGE_REACH = 8
/** Path costs: along a road already there, a new tile, a slope, a turn. */
private const val MOVE_ROAD = 2
private const val MOVE_NEW = 8
private const val SLOPE = 4
private const val TURN = 3
/** Most states the path search looks at, and tiles the road search. */
private const val PATH_LIMIT = 60_000
private const val ROAD_SEARCH = 20_000
