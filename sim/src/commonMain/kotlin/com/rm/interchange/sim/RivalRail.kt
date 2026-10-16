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

/** The best few places for a terminus in [site]: level, clear, the mouth facing the way the line goes. */
private fun World.termini(c: Company, site: RailSite, depot: Boolean): List<Terminus> {
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
        found += t to chebyshev(m % width, m / width, site.cx, site.cy) + away
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
 * The train to run for [cargo], about [load] of it at a time and no longer than a platform: the wagon of the day
 * holding most for its length, and the engine cheapest to run of those strong enough to pull it, and for
 * passengers near the fastest. Steam or diesel, as the line has no wires.
 */
private fun World.railTrain(cargo: Int, load: Int): RailTrain? {
    val year = Calendar.year(startYear, day)
    val wagon = roster.available(year, false).filter { cargo in it.cargoes && it.capacity > 0 && it.plow == Plows.NONE }
        .maxWithOrNull(compareBy<Engine> { it.capacity * 16 / it.length }.thenBy { -it.id }) ?: return null
    val engines = roster.available(year, true).filter { it.traction != Traction.ELECTRIC }
    val fastest = engines.maxOfOrNull { it.speed } ?: return null
    fun fits(e: Engine) = PLATFORMS * 16 - e.length - (roster.getOrNull(e.tender)?.length ?: 0)
    var best: RailTrain? = null
    for (e in engines) {
        if (cargo == Cargo.PASSENGERS && e.speed * 4 < fastest * 3) continue
        val n = ((load + wagon.capacity - 1) / wagon.capacity).coerceIn(1, (fits(e) / wagon.length).coerceAtLeast(1))
        // Loaded weight in tonnes against power, about what a freight engine pulls on the level at a fair speed.
        val tonnes = e.weight + (roster.getOrNull(e.tender)?.weight ?: 0) + n * (wagon.weight + wagon.capacity)
        if (e.power < tonnes * HP_PER_TONNE) continue
        val b = best
        if (b == null || e.running < b.engine.running || e.running == b.engine.running && e.speed > b.engine.speed) best = RailTrain(e, wagon, n)
    }
    return best
}

/**
 * Whether [train] would take [margin] percent of what it costs to run, carrying [cargo] between tiles [from] and [to]
 * over [tiles] of track: a year of whichever's less, what it can haul at its speed or about [load] a month, at the
 * full fare, both ways for passengers.
 */
private fun World.pays(train: RailTrain, cargo: Int, load: Int, tiles: Int, from: Int, to: Int, margin: Int): Boolean {
    val capacity = train.wagon.capacity * train.wagons
    // A tile a day at 40-odd km/h, and a couple of days at each end.
    val there = tiles * KMH_PER_TILE_A_DAY / train.engine.speed.coerceAtLeast(1) + 2
    val hauled = minOf(365 / (there * 2) * capacity, load * 12)
    val dist = kotlin.math.abs(from % width - to % width) + kotlin.math.abs(from / width - to / width)
    val ways = if (cargo == Cargo.PASSENGERS) 2 else 1
    val income = pay(hauled.toLong() * dist * Cargo.RATE[cargo] / 100) * ways
    val running = price(train.engine.running + train.wagon.running * train.wagons + (roster.getOrNull(train.engine.tender)?.running ?: 0))
    return income * 100 >= running * margin
}

/** What [t] costs, its tender with it. */
private fun World.trainCost(t: RailTrain): Long = price(t.engine.cost + t.wagon.cost * t.wagons + (roster.getOrNull(t.engine.tender)?.cost ?: 0))

/**
 * Joins sites [sa] and [sb] by a railway carrying [cargo], [load] at a time, with a terminus at each and a depot behind the first,
 * and sets a train going, handing the route to [built]. [serves] checks each new station reaches what it's for. Only
 * a line expected to take [margin] percent of its train's running cost is built.
 */
internal fun World.tryRail(
    c: Company, sa: RailSite, sb: RailSite, cargo: Int, load: Int, from: Int, to: Int,
    serves: (Station, Boolean) -> Boolean, margin: Int = RAIL_MARGIN, built: (RivalRoute) -> Unit,
): Tried {
    val train = railTrain(cargo, load) ?: return Tried.CANT
    for (a in termini(c, sa, true)) for (b in termini(c, sb, false)) {
        val path = railPath(a, b) ?: continue
        if (!pays(train, cargo, load, path.size + PLATFORMS, a.at(this, 1), b.at(this, 1), margin)) return Tried.CANT
        val runs = railRuns(c.id, path)
        var cost = 0L
        for (run in runs) {
            val (each, n) = railCost(run)
            if (each < 0 || n == 0) return Tried.CANT
            cost += each
        }
        cost += price(Prices.PLATFORM) * PLATFORMS * 2 + price(Prices.DEPOT) + trainCost(train)
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

/** What a rival's railway has to be expected to take, in percent of its train's running cost. */
internal const val RAIL_MARGIN = 150
/** Platforms at each terminus, enough for an engine and a few cars. */
private const val PLATFORMS = 5
/** How far from a town's middle its station goes. */
private const val TOWN_REACH = 6
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
private const val KMH_PER_TILE_A_DAY = 42
/** Horsepower an engine needs for each tonne of its train, loaded. */
private const val HP_PER_TONNE = 3
