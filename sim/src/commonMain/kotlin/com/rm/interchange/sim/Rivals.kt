package com.rm.interchange.sim

/*
 * Companies run by the computer. Each starts from a home town and, every
 * month or so, looks after its routes and opens new ones: a road between two
 * towns where there isn't one, a stop in each, a garage, and buses or coaches
 * of the day running between them. It builds only through the same commands a
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

/** A route a rival runs: its two stations, the garage its vehicles come from, the towns, and what they carry. */
class RivalRoute(val a: Int, val b: Int, val depot: Int, val townA: Int, val townB: Int, val cargo: Int)

private fun pairKey(a: Int, b: Int): Long = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()

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
    for (r in rivals) {
        if (day < r.thinkOn) continue
        r.thinkOn = day + THINK_DAYS
        val c = companies.getOrNull(r.company) ?: continue
        think(r, c)
    }
}

private fun World.think(r: Rival, c: Company) {
    // Out of money: borrow, a step at a time. Well off: pay the loan back.
    while (c.money < 0 && c.loan < maxLoan()) apply(Command.Loan(c.id, true))
    while (c.loan > 0 && c.money > price(Prices.LOAN_STEP) * 4) apply(Command.Loan(c.id, false))
    for (route in r.routes) growRoute(c, route)
    if (r.routes.size < maxRoutes(r)) newRoute(r, c)
}

/** More routes as the years go by. */
private fun World.maxRoutes(r: Rival): Int = (1 + (Calendar.year(startYear, day) - startYear) / 3).coerceAtMost(MAX_ROUTES)

/** Adds a vehicle to [route] when it's leaving people behind at either end. */
private fun World.growRoute(c: Company, route: RivalRoute) {
    val a = stations.getOrNull(route.a) ?: return
    val b = stations.getOrNull(route.b) ?: return
    val on = roadVehicles.filter { it != null && it.owner == c.id && it.orders.size == 2 && Order.station(it.orders[0]) == route.a && Order.station(it.orders[1]) == route.b }.map { it!! }
    if (on.size >= MAX_PER_ROUTE) return
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

/** Whether [c] has [cost] or can borrow it, borrowing what it needs. */
private fun World.afford(c: Company, cost: Long): Boolean {
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
    apply(Command.RoadOrder(c.id, id, Command.TrainOrder.ORDERS, intArrayOf(route.a, route.b)))
    apply(Command.RoadOrder(c.id, id, Command.TrainOrder.START_STOP))
}

/** Tries the most promising pairs of towns not yet joined by this rival, nearest its home first. */
private fun World.newRoute(r: Rival, c: Company) {
    val model = bestModel(Cargo.PASSENGERS) ?: return
    val home = towns.getOrNull(r.home) ?: return
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
            // Bigger towns, closer together, nearer home.
            val score = minOf(t1.population, t2.population).toLong() * 1000 / (d + chebyshev(t1.x, t1.y, home.x, home.y) / 2 + 5)
            pairs += Triple(t1, t2, score)
        }
    }
    pairs.sortWith(compareByDescending<Triple<Town, Town, Long>> { it.third }.thenBy { it.first.id }.thenBy { it.second.id })
    for ((t1, t2, _) in pairs.take(TRIES)) {
        when (tryRoute(r, c, t1, t2, model)) {
            Tried.BUILT -> return
            Tried.CANT -> r.failed += pairKey(t1.id, t2.id)
            Tried.POOR -> return
        }
    }
}

private enum class Tried { BUILT, CANT, POOR }

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
private fun World.placeStop(c: Company, t: Town, end: Int): Int? {
    val ex = end and 0xffff
    val ey = end ushr 16
    val tried = ArrayList<Int>()
    for (y in t.y - STOP_REACH..t.y + STOP_REACH) for (x in t.x - STOP_REACH..t.x + STOP_REACH) {
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

private fun chebyshev(x0: Int, y0: Int, x1: Int, y1: Int) = maxOf(kotlin.math.abs(x0 - x1), kotlin.math.abs(y0 - y1))

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

/**
 * A way for a road from near [from]'s middle to near [to]'s, as packed tiles (x in the low 16 bits, y above), over
 * ground a road can be laid on and along roads already there. It turns only on level ground, and on a slope keeps
 * straight, so the road it lays meets itself level at every join. Null if there's none within reach.
 */
private fun World.roadPath(from: Town, to: Town): IntArray? {
    val goals = HashSet<Int>()
    for (y in to.y - STOP_REACH..to.y + STOP_REACH) for (x in to.x - STOP_REACH..to.x + STOP_REACH) {
        if (inside(x, y) && roadEdges(index(x, y)) != 0 && townOf[index(x, y)].toInt() == to.id) goals += index(x, y)
    }
    if (goals.isEmpty()) return null
    // States are a tile and the edge it was come into by, 4 for a start.
    val best = HashMap<Int, Int>()
    val back = HashMap<Int, Int>()
    val heap = LongHeap()
    fun h(i: Int) = (kotlin.math.abs(i % width - to.x) + kotlin.math.abs(i / width - to.y)) * MOVE_ROAD
    for (y in from.y - STOP_REACH..from.y + STOP_REACH) for (x in from.x - STOP_REACH..from.x + STOP_REACH) {
        if (!inside(x, y)) continue
        val i = index(x, y)
        if (roadEdges(i) == 0 || townOf[i].toInt() != from.id) continue
        val s = i * 5 + 4
        best[s] = 0
        heap.push(h(i).toLong() shl 32 or s.toLong())
    }
    val limit = (chebyshev(from.x, from.y, to.x, to.y) + 10).let { it * it * 12 }.coerceAtMost(PATH_LIMIT)
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
private class LongHeap {
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

/** Calendar days between a rival's looks at its business. */
private const val THINK_DAYS = 30
private const val MAX_ROUTES = 8
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
