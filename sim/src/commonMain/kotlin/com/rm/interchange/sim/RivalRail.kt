package com.rm.interchange.sim

/*
 * Rival railways: a single line between two terminus stations, a depot behind
 * the first, and one train running back and forth. The line turns only on
 * level ground and its pieces meet level at every edge, so it lays as planned.
 */

/** Where a terminus can go: its platforms inside ([x0], [y0]) to ([x1], [y1]), its line heading off towards ([cx], [cy]). */
internal class RailSite(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val cx: Int, val cy: Int)

/** A terminus: the tile behind its platforms at ([x], [y]), the platforms running from there out through edge [out], then the mouth. */
private class Terminus(val x: Int, val y: Int, val out: Int) {
    /** The tile [k] steps out from the back: 1 to [PLATFORMS] are platforms, one more the mouth. */
    fun at(w: World, k: Int) = w.index(x + Track.dx(out) * k, y + Track.dy(out) * k)
    fun mouth(w: World) = at(w, PLATFORMS + 1)
}

/** The site round industry [ind]: platforms in reach of it. */
internal fun World.railSite(ind: Industry, cx: Int, cy: Int): RailSite {
    val kind = roster.industries[ind.kind]
    val reach = CATCHMENT - 1
    return RailSite(ind.x - reach, ind.y - reach, ind.x + kind.across(ind.turn) - 1 + reach, ind.y + kind.along(ind.turn) - 1 + reach, cx, cy)
}

/** The site round town [t]'s middle. */
internal fun railSite(t: Town, cx: Int, cy: Int) = RailSite(t.x - TOWN_REACH, t.y - TOWN_REACH, t.x + TOWN_REACH, t.y + TOWN_REACH, cx, cy)

/** Whether tile [i] is clear for track or a platform: dry land with nothing built on it. */
private fun World.railFree(i: Int): Boolean {
    val x = i % width
    val y = i / width
    return !isWater(x, y) && track[i].toInt() == 0 && road[i].toInt() == 0 && house[i].toInt() == 0 && industryOf[i].toInt() == 0 &&
        !stationTiles.containsKey(i) && !depotTiles.containsKey(i) && !roadStops.containsKey(i) && !spanHeads.containsKey(i) &&
        !deck.containsKey(i) && !canals.contains(i) && !airportTiles.containsKey(i) && !saltDepots.contains(i)
}

/**
 * The best few places for a terminus in [site]: level, clear, the mouth facing the way the line goes, and for
 * [people] in reach of as many as it can.
 */
private fun World.termini(c: Company, site: RailSite, depot: Boolean, people: Boolean): List<Terminus> {
    val found = ArrayList<Pair<Terminus, Int>>()
    for (y in site.y0..site.y1) for (x in site.x0..site.x1) for (out in 0 until 4) {
        val t = Terminus(x - Track.dx(out), y - Track.dy(out), out)
        val last = PLATFORMS + 1
        if (!inside(t.x, t.y) || !inside(t.x + Track.dx(out) * last, t.y + Track.dy(out) * last)) continue
        // The platforms inside the site, all of it level at one height.
        val px = t.x + Track.dx(out) * PLATFORMS
        val py = t.y + Track.dy(out) * PLATFORMS
        if (px !in site.x0..site.x1 || py !in site.y0..site.y1) continue
        val h = heightAt(x, y)
        var ok = true
        for (k in (if (depot) 0 else 1)..last) {
            val i = t.at(this, k)
            if (!railFree(i) || !isFlat(i % width, i / width) || heightAt(i % width, i / width) != h) { ok = false; break }
        }
        if (!ok || townRefuses(c, x, y)) continue
        val m = t.mouth(this)
        // Facing away from where it's going costs it the turn round.
        val away = if (Track.dx(out) * (site.cx - x) + Track.dy(out) * (site.cy - y) < 0) 12 else 0
        // For passengers, nearer the houses: a tile further for every few dozen people more in reach.
        val near = if (people) catchPeople(t) / PEOPLE_A_TILE else 0
        found += t to chebyshev(m % width, m / width, site.cx, site.cy) + away - near
    }
    found.sortWith(compareBy<Pair<Terminus, Int>> { it.second }.thenBy { it.first.y }.thenBy { it.first.x }.thenBy { it.first.out })
    return found.take(SITE_TRIES).map { it.first }
}

/**
 * A way for track from [a]'s mouth to [b]'s, as tiles each with the edges it's come in and goes out by, packed
 * tile * 16 + in * 4 + out. Null if there's none within reach.
 */
private fun World.railPath(a: Terminus, b: Terminus): IntArray? {
    val start = a.mouth(this)
    val goal = b.mouth(this)
    val gx = goal % width
    val gy = goal / width
    val into = Track.opposite(b.out)
    // The stations' own tiles stay clear of the line.
    val kept = HashSet<Int>()
    for (k in 0..PLATFORMS) { kept += a.at(this, k); kept += b.at(this, k) }
    val best = HashMap<Int, Int>()
    val back = HashMap<Int, Int>()
    val heap = LongHeap()
    fun h(i: Int) = (kotlin.math.abs(i % width - gx) + kotlin.math.abs(i / width - gy)) * RAIL_MOVE
    val s0 = start * 4 + Track.opposite(a.out)
    best[s0] = 0
    heap.push(h(start).toLong() shl 32 or s0.toLong())
    val limit = (chebyshev(start % width, start / width, gx, gy) + 10).let { it * it * 16 }.coerceAtMost(RAIL_PATH_LIMIT)
    var expanded = 0
    while (!heap.isEmpty() && expanded < limit) {
        val top = heap.pop()
        val s = (top and 0xffffffffL).toInt()
        val g = best[s] ?: continue
        if ((top ushr 32).toInt() - h(s / 4) > g) continue
        expanded++
        val t = s / 4
        val came = s % 4
        if (t == goal && came != into) {
            val out = ArrayList<Int>()
            var at = s
            var exit = into
            while (true) {
                out += (at / 4) * 16 + (at % 4) * 4 + exit
                exit = Track.opposite(at % 4)
                at = back[at] ?: break
            }
            out.reverse()
            return out.toIntArray()
        }
        val tx = t % width
        val ty = t / width
        val flat = isFlat(tx, ty)
        val crossing = road[t].toInt() != 0
        for (e in 0 until 4) {
            if (e == came) continue
            val turn = e != Track.opposite(came)
            if (turn && (!flat || crossing)) continue
            val nx = tx + Track.dx(e)
            val ny = ty + Track.dy(e)
            if (!inside(nx, ny)) continue
            val n = index(nx, ny)
            if (n in kept) continue
            val step = railStep(n, e) ?: continue
            if (edgeHeight(tx, ty, Track.pieceOf(came, e), e) != edgeHeight(nx, ny, Track.straight(e), Track.opposite(e))) continue
            val ns = n * 4 + Track.opposite(e)
            val ng = g + step + if (turn) RAIL_TURN else 0
            if (ng >= (best[ns] ?: Int.MAX_VALUE)) continue
            best[ns] = ng
            back[ns] = s
            heap.push((ng + h(n)).toLong() shl 32 or ns.toLong())
        }
    }
    return null
}

/** What laying track onto tile [i] across edge [e] costs in the search, or null if it can't go there. */
private fun World.railStep(i: Int, e: Int): Int? {
    val x = i % width
    val y = i / width
    val piece = Track.straight(e)
    // Across a road only straight over it, as a level crossing.
    if (road[i].toInt() != 0) return if (track[i].toInt() == 0 && crossable(i, piece)) RAIL_MOVE + RAIL_CROSSING else null
    if (!railFree(i)) return null
    return RAIL_MOVE + (if (isFlat(x, y)) 0 else RAIL_SLOPE) + (if (needsFoundation(x, y, piece)) RAIL_FOUNDATION else 0) + Trees.count(trees[i].toInt())
}

/** [path] as lines of track to lay: straight runs whole, each curve a piece on its own. */
private fun World.railRuns(company: Int, path: IntArray): List<Command.Rail> {
    val out = ArrayList<Command.Rail>()
    var k = 0
    while (k < path.size) {
        val t = path[k] / 16
        val piece = Track.pieceOf((path[k] / 4) % 4, path[k] % 4)
        var end = k
        if (piece == Track.X || piece == Track.Y) {
            while (end + 1 < path.size && Track.pieceOf((path[end + 1] / 4) % 4, path[end + 1] % 4) == piece) end++
        }
        val e = path[end] / 16
        out += Command.Rail(company, t % width, t / width, e % width, e / width, piece, remove = false)
        k = end + 1
    }
    return out
}

/** A train for [cargo] that fits a platform: the engine and wagons of the day, and how many wagons. */
private class RailTrain(val engine: Engine, val wagon: Engine, val wagons: Int)

/**
 * The trains that could run for [cargo], about [load] of it at a time and no longer than a platform: the wagon of the
 * day holding most for its length behind each engine strong enough to pull them. Steam or diesel, as the line has no wires.
 */
private fun World.railTrains(cargo: Int, load: Int): List<RailTrain> {
    val year = Calendar.year(startYear, day)
    val wagon = roster.available(year, false).filter { cargo in it.cargoes && it.capacity > 0 && it.plow == Plows.NONE }
        .maxWithOrNull(compareBy<Engine> { it.capacity * 16 / it.length }.thenBy { -it.id }) ?: return emptyList()
    fun fits(e: Engine) = PLATFORMS * 16 - e.length - (roster.getOrNull(e.tender)?.length ?: 0)
    val out = ArrayList<RailTrain>()
    for (e in roster.available(year, true)) {
        if (e.traction == Traction.ELECTRIC) continue
        val n = ((load + wagon.capacity - 1) / wagon.capacity).coerceIn(1, (fits(e) / wagon.length).coerceAtLeast(1))
        // Loaded weight in tonnes against power, about what a freight engine pulls on the level at a fair speed.
        val tonnes = e.weight + (roster.getOrNull(e.tender)?.weight ?: 0) + n * (wagon.weight + wagon.capacity)
        if (e.power >= tonnes * HP_PER_TONNE) out += RailTrain(e, wagon, n)
    }
    return out
}

/**
 * What [train] is expected to make in a work year after its running cost and the line's upkeep, carrying [cargo]
 * between tiles [from] and [to] over [tiles] of track one way: whichever's less of what it can haul at its speed and
 * what comes to it, at the fare for its days on board. That's about [load] a month for freight, and for passengers
 * what [people] in reach of the quieter end make each way at the rating its trains every so often earn.
 */
private fun World.yearly(c: Company, train: RailTrain, cargo: Int, load: Int, people: Int, tiles: Int, from: Int, to: Int): Long {
    val capacity = train.wagon.capacity * train.wagons
    // A tile a day at 40-odd km/h, a fifth more for starting, stopping and servicing, and a few days at each end.
    val there = tiles * KMH_PER_TILE_A_DAY * 6 / (5 * train.engine.speed.coerceAtLeast(1)) + 4
    val month = if (cargo == Cargo.PASSENGERS) people * 15 * railRating(there * 2, train.engine.speed) / (16 * 255) else load
    val hauled = minOf(365 / (there * 2) * capacity, month * 12)
    val dist = kotlin.math.abs(from % width - to % width) + kotlin.math.abs(from / width - to / width)
    // Freight waits on board for a full load, half of it the whole time it takes to fill.
    val filling = if (cargo == Cargo.PASSENGERS) 0 else capacity * 15 / load.coerceAtLeast(1)
    val late = (there + filling - dist * Cargo.PACE[cargo] - Cargo.GRACE[cargo]).coerceAtLeast(0)
    val share = (100 - late * Cargo.DECAY[cargo]).coerceAtLeast(33)
    val ways = if (cargo == Cargo.PASSENGERS) 2 else 1
    val income = pay(hauled.toLong() * dist * Cargo.RATE[cargo] * share / 10_000) * ways
    val running = price(train.engine.running + train.wagon.running * train.wagons + (roster.getOrNull(train.engine.tender)?.running ?: 0))
    // Upkeep on the line and both stations, in tenths of a cent a work day.
    val upkeep = price((tiles + PLATFORMS + 1).toLong() * Upkeep.TRACK[c.upkeep] * 365 / 1_000)
    return income - running - upkeep
}

/** About the passenger rating a station settles at with a train calling every [days] at [speed] km/h, few waiting. */
private fun railRating(days: Int, speed: Int): Int {
    val calls = when {
        days <= 7 -> 95
        days <= 15 -> 65
        days <= 30 -> 45
        days <= 52 -> 25
        else -> 0
    }
    return 33 + calls + 40 + ((speed - 50) / 4).coerceIn(0, 42)
}

/**
 * People in reach of [t]'s platforms that no station has yet, its houses' residents and jobs: each makes about 15/16
 * of a passenger a month before the rating's share.
 */
private fun World.catchPeople(t: Terminus): Int {
    if (catchmentDirty) workOutCatchment()
    val a = t.at(this, 1)
    val b = t.at(this, PLATFORMS)
    val x0 = maxOf(0, minOf(a % width, b % width) - CATCHMENT)
    val x1 = minOf(width - 1, maxOf(a % width, b % width) + CATCHMENT)
    val y0 = maxOf(0, minOf(a / width, b / width) - CATCHMENT)
    val y1 = minOf(height - 1, maxOf(a / width, b / width) + CATCHMENT)
    var people = 0
    for (y in y0..y1) for (x in x0..x1) {
        val i = index(x, y)
        if (house[i].toInt() == 0 || caught(i)) continue
        val h = roster.houses.getOrNull(houseOf(house[i].toInt())) ?: continue
        people += h.residents + h.jobs
    }
    return people
}

/** Whether a station already has the house on tile [i]: [World.catchment] is tiles in order, each with its station. */
private fun World.caught(i: Int): Boolean {
    val c = catchment
    var lo = 0
    var hi = c.size / 2 - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val t = c[mid * 2]
        if (t == i) return true
        if (t < i) lo = mid + 1 else hi = mid - 1
    }
    return false
}

/** What [t] costs, its tender with it. */
private fun World.trainCost(t: RailTrain): Long = price(t.engine.cost + t.wagon.cost * t.wagons + (roster.getOrNull(t.engine.tender)?.cost ?: 0))

/**
 * Joins sites [sa] and [sb] by a railway carrying [cargo], [load] a month of freight or the passengers its stations
 * get, with a terminus at each and a depot behind the first, and sets a train going, handing the route to [built].
 * [serves] checks each new station reaches what it's for. Only a line expected to pay for itself within [payback]
 * work years is built, or any line if that's 0.
 */
internal fun World.tryRail(
    c: Company, sa: RailSite, sb: RailSite, cargo: Int, load: Int, from: Int, to: Int,
    serves: (Station, Boolean) -> Boolean, payback: Int, built: (RivalRoute) -> Unit,
): Tried {
    val passengers = cargo == Cargo.PASSENGERS
    for (a in termini(c, sa, true, passengers)) for (b in termini(c, sb, false, passengers)) {
        val people = if (passengers) minOf(catchPeople(a), catchPeople(b)) else 0
        // Sized for about what it'll get a month at a middling rating.
        val trains = railTrains(cargo, if (passengers) people * 3 / 8 else load)
        if (trains.isEmpty()) return Tried.CANT
        val path = railPath(a, b) ?: continue
        val runs = railRuns(c.id, path)
        var cost = 0L
        for (run in runs) {
            val (each, n) = railCost(run)
            if (each < 0 || n == 0) return Tried.CANT
            cost += each
        }
        // The train that would do best over the payback time: what it makes in that time less what it costs.
        val years = if (payback > 0) payback else ANY_PAYBACK
        fun made(t: RailTrain) = yearly(c, t, cargo, load, people, path.size + PLATFORMS, a.at(this, 1), b.at(this, 1))
        var train = trains.first()
        var best = made(train) * years - trainCost(train)
        for (t in trains.drop(1)) {
            val v = made(t) * years - trainCost(t)
            if (v > best) { train = t; best = v }
        }
        cost += price(Prices.PLATFORM) * PLATFORMS * 2 + price(Prices.DEPOT) + trainCost(train)
        if (payback > 0 && made(train) * payback < cost) return Tried.CANT
        if (!afford(c, cost * 6 / 5)) return Tried.POOR
        val route = buildRail(c, a, b, path, runs, train, cargo, from, to, serves) ?: return Tried.CANT
        built(route)
        return Tried.BUILT
    }
    return Tried.CANT
}

private fun World.buildRail(
    c: Company, a: Terminus, b: Terminus, path: IntArray, runs: List<Command.Rail>, train: RailTrain, cargo: Int, from: Int, to: Int,
    serves: (Station, Boolean) -> Boolean,
): RivalRoute? {
    for (run in runs) apply(run)
    val axisA = Track.straight(a.out)
    val axisB = Track.straight(b.out)
    val a1 = a.at(this, 1)
    val aN = a.at(this, PLATFORMS)
    val b1 = b.at(this, 1)
    val bN = b.at(this, PLATFORMS)
    apply(Command.Station(c.id, a1 % width, a1 / width, aN % width, aN / width, axisA))
    apply(Command.Station(c.id, b1 % width, b1 / width, bN % width, bN / width, axisB))
    val before = depots.size
    apply(Command.Depot(c.id, a.x, a.y, a.out))
    val sa = stationTiles[a1]?.let { stations[it] }
    val sb = stationTiles[b1]?.let { stations[it] }
    val laid = path.all { p -> track[p / 16].toInt() and Track.pieceOf((p / 4) % 4, p % 4) != 0 }
    if (catchmentDirty) workOutCatchment()
    if (!laid || depots.size == before || sa == null || sb == null || sa.id == sb.id || sa.owner != c.id || sb.owner != c.id || !serves(sa, true) || !serves(sb, false)) {
        // Taken up again, a tile at a time: the track, the platforms and the depot.
        val tiles = path.map { it / 16 } + (0..PLATFORMS).map { a.at(this, it) } + (1..PLATFORMS).map { b.at(this, it) }
        for (i in tiles) if (owner[i].toInt() == c.id) clearTile(c, Command.Clear(c.id, i % width, i / width))
        return null
    }
    val route = RivalRoute(sa.id, sb.id, depots.size - 1, from, to, cargo, rail = true)
    buyTrain(c, route, train)
    return route
}

/** Buys a train for [route] in its depot and sets it going: full at the first station for freight, coming back empty. */
private fun World.buyTrain(c: Company, route: RivalRoute, train: RailTrain) {
    val before = trains.size
    apply(Command.BuyTrain(c.id, route.depot, train.engine.id, train.wagon.id, train.wagons, route.cargo))
    if (trains.size == before) return
    val id = trains.size - 1
    val orders = if (route.freight) intArrayOf(route.a or Order.FULL_LOAD, route.b or Order.NO_LOAD) else intArrayOf(route.a, route.b)
    apply(Command.TrainOrder(c.id, id, Command.TrainOrder.ORDERS, orders))
    apply(Command.TrainOrder(c.id, id, Command.TrainOrder.START_STOP))
}

/** Work years a train is chosen to pay back over when any line will do. */
private const val ANY_PAYBACK = 10
/** Platforms at each terminus, enough for an engine and a few cars. */
private const val PLATFORMS = 5
/** How far from a town's middle its station goes. */
private const val TOWN_REACH = 6
/** People in reach worth a tile further from where the line heads, choosing a passenger terminus. */
private const val PEOPLE_A_TILE = 40
/** Places tried for each terminus. */
private const val SITE_TRIES = 3
/** Path costs: a tile of new track, more on a slope or a foundation, a level crossing, a curve. */
private const val RAIL_MOVE = 8
private const val RAIL_SLOPE = 4
private const val RAIL_FOUNDATION = 6
private const val RAIL_CROSSING = 6
private const val RAIL_TURN = 4
private const val RAIL_PATH_LIMIT = 120_000
/** Km/h for a train to go a tile a work day. */
internal const val KMH_PER_TILE_A_DAY = 42
/** Horsepower an engine needs for each tonne of its train, loaded. */
private const val HP_PER_TONNE = 3
