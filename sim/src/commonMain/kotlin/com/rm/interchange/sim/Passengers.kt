package com.rm.interchange.sim

/*
 * Passengers and mail, and where they're going.
 *
 * Town buildings make them each day for the station whose platforms are
 * nearest within [CATCHMENT] tiles. Each lot is given somewhere to go among
 * the stations it can reach by train, more to big places and near ones, and
 * waits as a [Packet]. Trains' orders link the stations they call at; the way
 * from each station to each other, by distance with a cost for each change of
 * train, gives the next stop to take for each destination ([nextHop]).
 *
 * A train calling at a station lets off those who've arrived, who pay for the
 * whole journey, and those who change here, whose leg is credited to the train;
 * then takes on those whose next stop is its own. Cargo at a station with no
 * links goes anywhere, as it would with no plan.
 */

/**
 * A lot of passengers or mail travelling together: going to station [dest]
 * (-1 for anywhere), from station [origin], since tick [since]. [credit] is
 * what its earlier legs have been credited to the trains that carried them;
 * [boarded] is the station it got on its present train at, and [waitingFrom]
 * when it started waiting at the station it's at.
 */
class Packet(val dest: Int, val origin: Int, var count: Int, val since: Long, var credit: Long = 0, var boarded: Int = -1, var waitingFrom: Long = since)

/** How far from its platforms a station gathers from, in tiles each way. */
const val CATCHMENT = 4

/** The most of each cargo a station holds waiting. */
const val MAX_WAITING = 4095

/** Days cargo waits at a station before it gives up, counted from when it got there. */
const val GIVE_UP_DAYS = 90

/** What a change of train costs a journey, in tiles of distance, when working out the way. */
const val CHANGE_COST = 16

/** A new station's rating. */
const val START_RATING = 175

/** A payment where cargo got off, kept a while for the screen to show rising over the station. */
class Payment(val tile: Int, val amount: Long, val tick: Long)

fun Station.waiting(c: Int) = cargo[c].sumOf { it.count }
fun Train.load(c: Int) = cargo[c].sumOf { it.count }

/** Each building tile within reach of a station, and the station it sends to, as pairs; and the people round each station. */
internal fun World.workOutCatchment() {
    catchmentDirty = false
    val best = HashMap<Int, Int>()
    val bestDist = HashMap<Int, Int>()
    for (s in stations) {
        if (s == null) continue
        for (p in s.tiles) {
            val px = p % width
            val py = p / width
            for (y in maxOf(0, py - CATCHMENT)..minOf(height - 1, py + CATCHMENT)) for (x in maxOf(0, px - CATCHMENT)..minOf(width - 1, px + CATCHMENT)) {
                val i = index(x, y)
                if (house[i].toInt() == 0) continue
                val d = maxOf(kotlin.math.abs(x - px), kotlin.math.abs(y - py))
                val had = bestDist[i]
                // The nearest platform wins; between stations as near, the older.
                if (had == null || d < had || (d == had && s.id < best.getValue(i))) {
                    best[i] = s.id
                    bestDist[i] = d
                }
            }
        }
    }
    val keys = best.keys.sorted()
    catchment = IntArray(keys.size * 2).also { a -> for ((k, tile) in keys.withIndex()) { a[k * 2] = tile; a[k * 2 + 1] = best.getValue(tile) } }
    stationPeople.clear()
    for (tile in keys) {
        val h = roster.houses.getOrNull(houseOf(house[tile].toInt())) ?: continue
        val s = best.getValue(tile)
        stationPeople[s] = (stationPeople[s] ?: 0) + h.residents + h.jobs
    }
    // Industries a station reaches, and the stations each industry is reached by: any of its tiles in reach.
    industryStations.clear()
    stationIndustries.clear()
    for (s in stations) {
        if (s == null) continue
        val found = ArrayList<Int>()
        for (p in s.tiles) {
            val px = p % width
            val py = p / width
            for (y in maxOf(0, py - CATCHMENT)..minOf(height - 1, py + CATCHMENT)) for (x in maxOf(0, px - CATCHMENT)..minOf(width - 1, px + CATCHMENT)) {
                val n = industryOf[index(x, y)].toInt()
                if (n > 0 && (n - 1) !in found) found += n - 1
            }
        }
        found.sort()
        if (found.isEmpty()) continue
        stationIndustries[s.id] = found
        for (n in found) industryStations.getOrPut(n) { ArrayList() } += s.id
    }
    linksDirty = true
}


/**
 * The links trains' orders make, each stop to the next and the last back to
 * the first, and from them the next stop from each station towards each it
 * can reach, by the shortest way.
 */
internal fun World.workOutLinks() {
    linksDirty = false
    val links = HashMap<Int, MutableList<Int>>()
    for (t in trains) {
        if (t == null || t.orders.size < 2) continue
        for (k in t.orders.indices) {
            val a = t.orders[k]
            val b = t.orders[(k + 1) % t.orders.size]
            if (a == b || stations.getOrNull(a) == null || stations.getOrNull(b) == null) continue
            val out = links.getOrPut(a) { ArrayList() }
            if (b !in out) out += b
        }
    }
    for (out in links.values) out.sort()
    nextHops.clear()
    for (from in links.keys.sorted()) {
        // Dijkstra over the stations, each link costing its length and a change of train; there are few enough
        // stations to pick the nearest unsettled one by looking through them all.
        val dist = HashMap<Int, Int>()
        val first = HashMap<Int, Int>()
        val settled = HashSet<Int>()
        dist[from] = 0
        while (true) {
            var s = -1
            var d = Int.MAX_VALUE
            for ((k, v) in dist) if (k !in settled && (v < d || (v == d && k < s))) { s = k; d = v }
            if (s < 0) break
            settled += s
            for (n in links[s].orEmpty()) {
                val nd = d + stationDistance(s, n) + CHANGE_COST
                if (nd < (dist[n] ?: Int.MAX_VALUE)) {
                    dist[n] = nd
                    first[n] = if (s == from) n else first.getValue(s)
                }
            }
        }
        first.remove(from)
        nextHops[from] = first
    }
}

/** Tiles between two stations' signs, across and along. */
internal fun World.stationDistance(a: Int, b: Int): Int {
    val sa = stations.getOrNull(a) ?: return 0
    val sb = stations.getOrNull(b) ?: return 0
    return kotlin.math.abs(sa.signX - sb.signX) + kotlin.math.abs(sa.signY - sb.signY)
}

/** The next stop from [from] towards [dest], or -1 if it can't be reached. */
fun World.nextHop(from: Int, dest: Int): Int {
    if (catchmentDirty) workOutCatchment()
    if (linksDirty) workOutLinks()
    return nextHops[from]?.get(dest) ?: -1
}

/** Adds [n] to station [s]'s cargo [c] for [dest], with the lot from the same place already waiting if there is one. */
private fun World.addWaiting(s: Station, c: Int, dest: Int, origin: Int, n: Int, since: Long, credit: Long = 0) {
    if (n <= 0) return
    val same = s.cargo[c].firstOrNull { it.dest == dest && it.origin == origin }
    if (same != null) {
        same.count += n
        same.credit += credit
    } else s.cargo[c] += Packet(dest, origin, n, since, credit, -1, tick)
}

/**
 * A day of passengers and mail: each building in reach of a station makes
 * them there, given destinations among the stations reachable from it, more
 * to the bigger and the nearer. Then those waiting too long give up.
 */
internal fun World.passengersDay() {
    if (catchmentDirty) workOutCatchment()
    if (linksDirty) workOutLinks()
    val made = HashMap<Int, IntArray>()
    // Freight, rated by each station as it's shared out.
    val freight = HashMap<Int, IntArray>()
    industriesDay(freight)
    val c = catchment
    var k = 0
    while (k < c.size) {
        val tile = c[k]
        val sid = c[k + 1]
        k += 2
        val h = roster.houses.getOrNull(houseOf(house[tile].toInt())) ?: continue
        val people = h.residents + h.jobs
        val passengers = (people * 8 + rng.below(256)) / 256
        val mail = (people * 2 + rng.below(256)) / 256
        val m = made.getOrPut(sid) { IntArray(Cargo.COUNT) }
        m[Cargo.PASSENGERS] += passengers
        m[Cargo.MAIL] += mail
        towns.getOrNull(townOf[tile].toInt())?.let { it.servedThisMonth += passengers + mail }
    }
    val travel = Calendar.travel(startYear, day)
    val post = Calendar.mail(startYear, day)
    for (sid in made.keys.sorted()) {
        val s = stations.getOrNull(sid) ?: continue
        // Busier or quieter by the day, and only the station's rating's share comes to it at all.
        val m = made.getValue(sid)
        m[Cargo.PASSENGERS] = m[Cargo.PASSENGERS] * travel / 100
        m[Cargo.MAIL] = m[Cargo.MAIL] * post / 100
        for (cg in 0 until Cargo.COUNT) m[cg] = (m[cg] * s.rating[cg] + rng.below(255)) / 255
    }
    for ((sid, f) in freight) {
        val m = made.getOrPut(sid) { IntArray(Cargo.COUNT) }
        for (cg in 0 until Cargo.COUNT) m[cg] += f[cg]
    }
    for (sid in made.keys.sorted()) {
        val s = stations.getOrNull(sid) ?: continue
        val reachable = nextHops[sid]?.keys?.sorted().orEmpty()
        for (cg in 0 until Cargo.COUNT) {
            val n = minOf(made.getValue(sid)[cg], MAX_WAITING - s.waiting(cg))
            if (n <= 0) continue
            // Where it can go: stations that take it, people for passengers and mail weighed by how many, freight
            // by nearness alone.
            val reach = reachable.filter { accepts(stations[it]!!, cg) }
            val people = cg == Cargo.PASSENGERS || cg == Cargo.MAIL
            val weights = IntArray(reach.size) { i ->
                (if (people) (stationPeople[reach[i]] ?: 0) + 20 else 64) * 256 / (stationDistance(sid, reach[i]) + 8)
            }
            val total = weights.sum()
            if (total == 0) {
                addWaiting(s, cg, -1, sid, n, tick)
                continue
            }
            val counts = IntArray(reach.size)
            repeat(n) {
                var roll = rng.below(total)
                var i = 0
                while (roll >= weights[i]) roll -= weights[i++]
                counts[i]++
            }
            for (i in reach.indices) addWaiting(s, cg, reach[i], sid, counts[i], tick)
        }
    }
    for (s in stations) {
        if (s == null) continue
        for (cg in 0 until Cargo.COUNT) {
            s.cargo[cg].removeAll { tick - it.waitingFrom > GIVE_UP_DAYS * Calendar.TICKS_PER_DAY.toLong() }
            // The rating creeps towards what the service deserves, a little a day.
            val target = ratingTarget(s, cg)
            s.rating[cg] += (target - s.rating[cg]).coerceIn(-RATING_STEP, RATING_STEP)
        }
    }
}

/**
 * What station [s]'s rating for cargo [c] is heading for, 0 to 255: how
 * recently a train called that could take it, how little is waiting, and how
 * fast the last such train goes.
 */
internal fun World.ratingTarget(s: Station, c: Int): Int {
    var r = 33
    val days = if (s.lastCall[c] < 0) Int.MAX_VALUE else ((tick - s.lastCall[c]) / Calendar.TICKS_PER_DAY).toInt()
    // On OpenTTD's scale: a train every week or so is good service, one every couple of months poor.
    r += when {
        days <= 7 -> 95
        days <= 15 -> 65
        days <= 30 -> 45
        days <= 52 -> 25
        else -> 0
    }
    val waiting = s.waiting(c)
    r += when {
        waiting <= 100 -> 40
        waiting <= 300 -> 30
        waiting <= 600 -> 10
        waiting <= 1000 -> 0
        waiting <= 1500 -> -35
        else -> -90
    }
    r += ((s.lastSpeed[c] - 50) / 4).coerceIn(0, 42)
    return r.coerceIn(0, 255)
}

/** How far a station's rating moves in a day. */
const val RATING_STEP = 2

/**
 * Whether station [s] takes cargo [c] off trains: passengers and mail where
 * anyone lives or works in reach, a town's goods, food, fuel and lumber where
 * enough do, and anything an industry in reach takes.
 */
internal fun World.accepts(s: Station, c: Int): Boolean {
    if (catchmentDirty) workOutCatchment()
    val people = stationPeople[s.id] ?: 0
    if ((c == Cargo.PASSENGERS || c == Cargo.MAIL) && people > 0) return true
    if (Cargo.townWants(c) && people >= Cargo.TOWN_GOODS_PEOPLE) return true
    return takerAt(s, c) != null
}

/** The station train [t] calls at after the one it's at now, or -1. */
private fun nextStop(t: Train): Int = if (t.orders.size < 2) -1 else t.orders[(t.order + 1) % t.orders.size]

/**
 * Train [t] standing at station [s]: those who've arrived get off and pay for
 * their whole journey, those changing here get off to wait and their leg is
 * credited to the train, then on get those whose next stop is the train's.
 */
internal fun World.callAt(t: Train, s: Station) {
    val company = companies.getOrNull(t.owner) ?: return
    val next = nextStop(t)
    val days = { p: Packet -> ((tick - p.since) / Calendar.TICKS_PER_DAY).toInt() }
    var paid = 0L
    for (c in 0 until Cargo.COUNT) {
        val stay = ArrayList<Packet>()
        for (p in t.cargo[c]) {
            val from = stations.getOrNull(p.origin)
            val arrived = p.dest == s.id || (p.dest == -1 && p.origin != s.id && accepts(s, c))
            when {
                arrived && from != null -> {
                    val full = fare(c, p.count, from, s, days(p))
                    paid += full
                    // Freight for an industry here goes to it to be made into its own.
                    takerAt(s, c)?.let { ind ->
                        ind.brought += p.count
                        ind.broughtThisMonth += p.count
                    }
                    // The legs before were credited to the trains that carried them; this one gets the rest.
                    t.profitThisYear += full - p.credit
                }
                arrived -> {}
                p.dest >= 0 && nextHop(s.id, p.dest) != next -> {
                    // Changing here: the leg it's done on this train is this train's, up to what the whole journey will pay.
                    val boarded = stations.getOrNull(p.boarded)
                    val dest = stations.getOrNull(p.dest)
                    val leg = if (boarded != null && boarded.id != s.id) fare(c, p.count, boarded, s, days(p)) else 0L
                    val whole = if (from != null && dest != null) fare(c, p.count, from, dest, days(p)) else 0L
                    val credit = minOf(leg, maxOf(0L, whole - p.credit))
                    t.profitThisYear += credit
                    addWaiting(s, c, p.dest, p.origin, p.count, p.since, p.credit + credit)
                }
                else -> stay += p
            }
        }
        t.cargo[c].clear()
        t.cargo[c].addAll(stay)
    }
    if (paid > 0) {
        company.money += paid
        payments += Payment(s.tiles.first(), paid, tick)
    }
    for (c in 0 until Cargo.COUNT) {
        val capacity = t.capacity(c)
        if (capacity > 0) {
            s.lastCall[c] = tick
            s.lastSpeed[c] = t.engineInfo.speed
        }
        var room = capacity - t.load(c)
        val it = s.cargo[c].iterator()
        while (room > 0 && it.hasNext()) {
            val p = it.next()
            if (p.dest != -1 && (next < 0 || nextHop(s.id, p.dest) != next)) continue
            val take = minOf(room, p.count)
            // A share of the lot's credit goes with those who board.
            val credit = p.credit * take / p.count
            t.cargo[c] += Packet(p.dest, p.origin, take, p.since, credit, s.id)
            p.count -= take
            p.credit -= credit
            room -= take
            if (p.count == 0) it.remove()
        }
    }
}

/**
 * What [n] of cargo [c] pays carried from [from] to [to] in [days]: by the
 * distance between the stations, full if it came as fast as that cargo needs,
 * then less each day after, down to a third.
 */
fun fare(c: Int, n: Int, from: Station, to: Station, days: Int): Long {
    val dist = kotlin.math.abs(from.signX - to.signX) + kotlin.math.abs(from.signY - to.signY)
    val quick = dist / 2 + Cargo.GRACE[c]
    val share = (100 - maxOf(0, days - quick) * Cargo.DECAY[c]).coerceAtLeast(33)
    return n.toLong() * dist * Cargo.RATE[c] * share / 10_000
}

/**
 * The first of each month: what towns had picked up last month is what makes
 * them grow, and each company's stations near a town move what it thinks of
 * the company: up for good ratings, down for poor ones, back towards where
 * it started for a company with none there.
 */
internal fun World.passengersMonth() {
    for (t in towns) {
        t.servedLastMonth = t.servedThisMonth
        t.servedThisMonth = 0
        for (c in companies) {
            val near = stations.filter { s -> s != null && s.owner == c.id && maxOf(kotlin.math.abs(s.signX - t.x), kotlin.math.abs(s.signY - t.y)) <= TOWN_AREA }
            val a = t.approval[c.id]
            t.approval[c.id] = if (near.isEmpty()) a - (a - START_APPROVAL).coerceIn(-5, 5) else {
                val percent = near.sumOf { s -> s!!.rating.sum() * 100 / (255 * Cargo.COUNT) } / near.size
                a + when {
                    percent >= 70 -> 20
                    percent >= 50 -> 10
                    percent >= 30 -> 0
                    else -> -15
                }
            }.coerceIn(-1000, 1000)
        }
    }
}

/** The first of each year: trains' profit moves to last year's. */
internal fun World.passengersYear() {
    for (t in trains) {
        if (t == null) continue
        t.profitLastYear = t.profitThisYear
        t.profitThisYear = 0
    }
}

/** Payments shown rising over stations, kept this many ticks. */
const val PAYMENT_TICKS = Calendar.TICKS_PER_DAY * 3L
