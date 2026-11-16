package com.rm.interchange.sim

/*
 * Passengers and mail, and where they're going.
 *
 * Town buildings make them each day for the station whose platforms are
 * nearest within [CATCHMENT] tiles. Each lot is given somewhere to go among
 * the stations it can reach by train, more to big places and near ones, and
 * waits as a [Packet]. Vehicles' orders link the stations they call at, for
 * each cargo they can carry; the way from each station to each other, by the
 * links' journey times with a cost for each change, gives the next stop to
 * take for each cargo and destination ([nextHop]).
 *
 * A train calling at a station lets off those who've arrived, who pay for the
 * whole journey, and those who change here, whose leg is credited to the train;
 * then takes on those whose next stop is its own. Cargo at a station with no
 * links goes anywhere, as it would with no plan.
 */

/**
 * A lot of passengers or mail travelling together: going to station [dest]
 * (-1 for anywhere), from station [origin]. Its fare goes by its time on
 * board: on a vehicle that's the ticks since [since]; waiting at a station
 * it's [waitingFrom] less [since], which doesn't grow while it waits.
 * [credit] is what its earlier legs have been credited to the trains that
 * carried them; [boarded] is the station it got on its present train at, and
 * [waitingFrom] when it started waiting at the station it's at.
 */
class Packet(val dest: Int, val origin: Int, var count: Int, val since: Long, var credit: Long = 0, var boarded: Int = -1, var waitingFrom: Long = since)

/** How far from its platforms a station gathers from, in tiles each way. */
const val CATCHMENT = 4

/** The most of each cargo a station holds waiting. */
const val MAX_WAITING = 4095

/** Days cargo waits at a station before it gives up, counted from when it got there. */
const val GIVE_UP_DAYS = 90

/**
 * Ticks a vehicle at 1 km/h takes over a tile: positions are 4096ths of a tile, and a vehicle at top speed moves
 * km/h * 4096 / 2700 of them a tick.
 */
const val TILE_TICKS_KMH = 2700

/** What a change of vehicle costs a journey when working out the way, in ticks: 16 tiles at 100 km/h. */
const val CHANGE_TICKS = 16 * TILE_TICKS_KMH / 100

/** The longest a link's journey time is taken to be, in ticks. */
const val MAX_LINK_TICKS = 1_000_000

/** A new station's rating. */
const val START_RATING = 175

/** A payment where cargo got off, kept a while for the screen to show rising over the station. */
class Payment(val tile: Int, val amount: Long, val tick: Long, val company: Int = 0)

fun Station.waiting(c: Int) = cargo[c].sumOf { it.count }
fun Carrier.load(c: Int) = cargo[c].sumOf { it.count }

/** Each building tile within reach of a station, and the station it sends to, as pairs; and the people round each station. */
internal fun World.workOutCatchment() {
    catchmentDirty = false
    val best = HashMap<Int, Int>()
    val bestDist = HashMap<Int, Int>()
    for (s in stations) {
        if (s == null || s.waypoint) continue
        for (p in s.places) {
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
        if (s == null || s.waypoint) continue
        val found = ArrayList<Int>()
        for (p in s.places) {
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


/** A link's key in [World.linkTimes]: station [from] in the high bits, [to] in the low 16. */
internal fun linkKey(from: Int, to: Int) = (from.toLong() shl 16) or to.toLong()

/** Whether order [o] is a stop: a station order that isn't run through, to a station that isn't a waypoint. */
internal fun World.isStop(o: Int): Boolean {
    val id = Order.station(o)
    return id >= 0 && !Order.has(o, Order.VIA) && stations.getOrNull(id)?.waypoint == false
}

/**
 * The links vehicles' orders make for each cargo, each stop to the next and the last back to the first, for every
 * cargo the vehicle can carry and none out of a stop it doesn't load at; and from them the next stop from each station
 * towards each it can reach, by the quickest way. Cargoes with the same links share one table.
 */
internal fun World.workOutLinks() {
    linksDirty = false
    val sets = Array(Cargo.MAX) { HashSet<Long>() }
    // A link no vehicle has timed yet goes by its distance at the top speed of the fastest running it.
    val guess = HashMap<Long, Int>()
    for (t in carriers()) {
        // Depot orders and stations run through are left out: a stop links to the next stop on.
        val stops = t.orders.filter { isStop(it) }
        if (stops.size < 2) continue
        val carries = (0 until cargoCount).filter { t.capacity(it) > 0 }
        if (carries.isEmpty()) continue
        for (k in stops.indices) {
            if (Order.has(stops[k], Order.NO_LOAD)) continue
            val a = Order.station(stops[k])
            val b = Order.station(stops[(k + 1) % stops.size])
            if (a == b) continue
            val key = linkKey(a, b)
            val ticks = (stationDistance(a, b) * TILE_TICKS_KMH / t.topSpeed.coerceAtLeast(1)).coerceIn(1, MAX_LINK_TICKS)
            guess[key] = minOf(guess[key] ?: MAX_LINK_TICKS, ticks)
            for (c in carries) sets[c] += key
        }
    }
    val keys = Array(Cargo.MAX) { c -> sets[c].toLongArray().also { it.sort() } }
    val tables = ArrayList<HashMap<Int, HashMap<Int, Int>>>()
    val costTables = ArrayList<HashMap<Int, HashMap<Int, Int>>>()
    for (c in 0 until cargoCount) {
        val same = (0 until c).firstOrNull { keys[it].contentEquals(keys[c]) }
        if (same != null) {
            tables += tables[same]
            costTables += costTables[same]
        } else {
            val costs = HashMap<Int, HashMap<Int, Int>>()
            tables += routes(keys[c], guess, costs)
            costTables += costs
        }
    }
    nextHops = Array(Cargo.MAX) { tables.getOrElse(it) { HashMap() } }
    routeCosts = Array(Cargo.MAX) { costTables.getOrElse(it) { HashMap() } }
}

/**
 * The next stop from each station towards each it can reach over the links [keys] (sorted), by Dijkstra: each link
 * costs its journey time with the wait for it and a change, ties going to the lower station id. The whole way's cost
 * from each to each goes in [costsOut].
 */
private fun World.routes(keys: LongArray, guess: Map<Long, Int>, costsOut: HashMap<Int, HashMap<Int, Int>>): HashMap<Int, HashMap<Int, Int>> {
    val hops = HashMap<Int, HashMap<Int, Int>>()
    if (keys.isEmpty()) return hops
    // The stations in the links, by id, numbered in that order.
    val ids = keys.flatMap { listOf((it shr 16).toInt(), (it and 0xffff).toInt()) }.distinct().sorted().toIntArray()
    val index = HashMap<Int, Int>()
    for ((i, id) in ids.withIndex()) index[id] = i
    val n = ids.size
    val outs = Array(n) { ArrayList<Int>() }
    val costs = Array(n) { ArrayList<Int>() }
    for (k in keys) {
        val a = index.getValue((k shr 16).toInt())
        outs[a] += index.getValue((k and 0xffff).toInt())
        costs[a] += (linkCosts[k] ?: guess[k] ?: MAX_LINK_TICKS) + CHANGE_TICKS
    }
    val dist = IntArray(n)
    val first = IntArray(n)
    val settled = BooleanArray(n)
    for (from in 0 until n) {
        if (outs[from].isEmpty()) continue
        dist.fill(Int.MAX_VALUE)
        first.fill(-1)
        settled.fill(false)
        dist[from] = 0
        while (true) {
            // Few enough stations to pick the nearest unsettled one by looking through them all.
            var s = -1
            var d = Int.MAX_VALUE
            for (i in 0 until n) if (!settled[i] && dist[i] < d) { s = i; d = dist[i] }
            if (s < 0) break
            settled[s] = true
            val out = outs[s]
            for (j in out.indices) {
                val m = out[j]
                val nd = d + costs[s][j]
                if (nd < dist[m]) {
                    dist[m] = nd
                    first[m] = if (s == from) m else first[s]
                }
            }
        }
        val map = HashMap<Int, Int>()
        val costMap = HashMap<Int, Int>()
        for (i in 0 until n) if (i != from && first[i] >= 0) {
            map[ids[i]] = ids[first[i]]
            costMap[ids[i]] = dist[i]
        }
        hops[ids[from]] = map
        costsOut[ids[from]] = costMap
    }
    return hops
}

/**
 * Feeds a vehicle's run of [ticks] over the link from station [from] to [to] into the link's journey time, at most
 * [SAMPLE_MOST] times what it was, so one held up or lost on the way doesn't make the link look slow for weeks.
 */
internal fun World.timeLink(from: Int, to: Int, ticks: Long) {
    val key = linkKey(from, to)
    val old = linkTimes[key]
    val most = if (old == null) MAX_LINK_TICKS.toLong() else minOf(MAX_LINK_TICKS.toLong(), old.toLong() * SAMPLE_MOST)
    val sample = ticks.coerceIn(1, most).toInt()
    linkTimes[key] = if (old == null) sample else (7 * old + sample) / 8
}

/** The most one run counts for, as a multiple of the link's time before it. */
const val SAMPLE_MOST = 4

/**
 * Each week: the way goes by the journey times measured so far with each link's wait and reliability charge, until
 * the next week, worked out again if they changed.
 */
internal fun World.linksWeek() {
    val charges = linkCharges()
    val next = HashMap<Long, Int>()
    for ((k, t) in linkTimes) next[k] = (t.toLong() + (charges[k]?.sum() ?: 0)).coerceAtMost(MAX_LINK_TICKS.toLong()).toInt()
    if (linkCosts == next) return
    linkCosts.clear()
    linkCosts.putAll(next)
    linksDirty = true
}

/**
 * What each link costs on top of its journey time, in ticks, by [linkKey]: the wait for it, half the time between
 * departures over the month, and its reliability charge, the ticks held a run and how late on average the vehicles
 * running it are. Links with no departures yet have no wait.
 */
internal fun World.linkCharges(): HashMap<Long, IntArray> {
    val out = HashMap<Long, IntArray>()
    val monthTicks = 30L * dayTicks
    for ((k, st) in linkStats) {
        // This month's so far counts for a line new this month.
        val departures = maxOf(st.last.departures, st.month.departures)
        val wait = if (departures == 0) 0 else (minOf(monthTicks / departures, WAIT_MOST_DAYS.toLong() * dayTicks) / 2).toInt()
        val lost = if (st.last.runs == 0) 0 else (st.last.lost / st.last.runs).coerceAtMost(MAX_LINK_TICKS.toLong()).toInt()
        if (wait > 0 || lost > 0) out[k] = intArrayOf(wait, lost)
    }
    // Lateness of the vehicles on each link, each counted once a link.
    val late = HashMap<Long, LongArray>()
    for (v in carriers()) {
        val stops = v.orders.filter { isStop(it) }
        if (stops.size < 2) continue
        val seen = HashSet<Long>()
        for (k in stops.indices) {
            val a = Order.station(stops[k])
            val b = Order.station(stops[(k + 1) % stops.size])
            val key = linkKey(a, b)
            if (a == b || !seen.add(key)) continue
            val l = late.getOrPut(key) { LongArray(2) }
            l[0] += v.late.coerceAtLeast(0).toLong()
            l[1]++
        }
    }
    for ((k, l) in late) {
        val ticks = (l[0] / l[1]).toInt()
        if (ticks == 0) continue
        val c = out.getOrPut(k) { IntArray(2) }
        c[1] = (c[1].toLong() + ticks).coerceAtMost(MAX_LINK_TICKS.toLong()).toInt()
    }
    return out
}

/** The most game days between departures a link's wait goes by, at any day length: one a month, a wait of half that. */
const val WAIT_MOST_DAYS = 30

/** Forgets the journey times of links to and from station [id], and vehicles' leaving it. */
internal fun World.dropLinkTimes(id: Int) {
    val gone = { k: Long -> (k shr 16).toInt() == id || (k and 0xffff).toInt() == id }
    linkTimes.keys.removeAll(gone)
    linkCosts.keys.removeAll(gone)
    linkStats.keys.removeAll(gone)
    tripsMonth.keys.removeAll(gone)
    tripsLast.keys.removeAll(gone)
    for (list in listOf<List<Carrier?>>(trains, roadVehicles, ships, aircraft)) for (v in list) if (v != null && v.leftFrom == id) {
        v.leftFrom = -1
        v.leftAt = -1
    }
}

/** Tiles between two stations' signs, across and along. */
internal fun World.stationDistance(a: Int, b: Int): Int {
    val sa = stations.getOrNull(a) ?: return 0
    val sb = stations.getOrNull(b) ?: return 0
    return kotlin.math.abs(sa.signX - sb.signX) + kotlin.math.abs(sa.signY - sb.signY)
}

/** The next stop from [from] towards [dest] for cargo [c], or -1 if it can't be reached. */
fun World.nextHop(from: Int, dest: Int, c: Int = Cargo.PASSENGERS): Int {
    if (catchmentDirty) workOutCatchment()
    if (linksDirty) workOutLinks()
    return nextHops[c][from]?.get(dest) ?: -1
}

/**
 * Adds [n] to station [s]'s cargo [c] for [dest], with the lot from the same place already waiting if there is one
 * and it's been on board as long. [since] is the tick its time on board started from: now for new cargo.
 */
internal fun World.addWaiting(s: Station, c: Int, dest: Int, origin: Int, n: Int, since: Long, credit: Long = 0) {
    if (n <= 0) return
    val aboard = tick - since
    val same = s.cargo[c].firstOrNull { it.dest == dest && it.origin == origin && it.waitingFrom - it.since == aboard }
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
    // Stations another company's exclusive rights shut out get nothing.
    val shut = shutOut()
    // Freight, rated by each station as it's shared out.
    val freight = HashMap<Int, IntArray>()
    industriesDay(freight, shut)
    val c = catchment
    var k = 0
    while (k < c.size) {
        val tile = c[k]
        val sid = c[k + 1]
        k += 2
        if (sid in shut) continue
        val h = roster.houses.getOrNull(houseOf(house[tile].toInt())) ?: continue
        val people = h.residents + h.jobs
        val passengers = (people * 8 + rng.below(256)) / 256
        val mail = (people * 2 + rng.below(256)) / 256
        val m = made.getOrPut(sid) { IntArray(Cargo.MAX) }
        m[Cargo.PASSENGERS] += passengers
        m[Cargo.MAIL] += mail
    }
    val travel = Calendar.travel(startYear, day)
    val post = Calendar.mail(startYear, day)
    for (sid in made.keys.sorted()) {
        val s = stations.getOrNull(sid) ?: continue
        // Busier or quieter by the day, and only the station's rating's share comes to it at all.
        val m = made.getValue(sid)
        m[Cargo.PASSENGERS] = m[Cargo.PASSENGERS] * travel / 100 * outings(s) / 100
        m[Cargo.MAIL] = m[Cargo.MAIL] * post / 100
        // And more or fewer by how good the service from here is, in the same rounded step so a quiet station
        // isn't cut to nothing.
        for (cg in 0 until cargoCount) {
            m[cg] = ((m[cg].toLong() * s.rating[cg] * serviceFactor(s, cg) + 100L * rng.below(255)) / (255 * 100)).toInt()
        }
    }
    for ((sid, f) in freight) {
        val m = made.getOrPut(sid) { IntArray(Cargo.MAX) }
        for (cg in 0 until cargoCount) m[cg] += f[cg]
    }
    for (sid in made.keys.sorted()) {
        val s = stations.getOrNull(sid) ?: continue
        for (cg in 0 until cargoCount) {
            val n = minOf(made.getValue(sid)[cg], MAX_WAITING - s.waiting(cg))
            if (n <= 0) continue
            val reachable = nextHops[cg][sid]?.keys?.sorted().orEmpty()
            // Where it can go: stations that take it, people for passengers and mail weighed by how many over how
            // long the way takes, freight by nearness alone.
            // Never to another industry of a kind that made it here, so grain doesn't go round from elevator to elevator.
            val makers = stationIndustries[sid].orEmpty().map { industries[it] }.filter { roster.industries[it.kind].outputs and (1 shl cg) != 0 }.map { it.kind }.toSet()
            val reach = reachable.filter { d -> accepts(stations[d]!!, cg) && takerAt(stations[d]!!, cg)?.kind?.let { it in makers } != true }
            val people = cg == Cargo.PASSENGERS || cg == Cargo.MAIL
            val costs = routeCosts[cg][sid]
            val weights = IntArray(reach.size) { i ->
                if (people) ((stationPeople[reach[i]] ?: 0) + 20) * 256 / (costTiles(costs?.get(reach[i]) ?: 0) + 8)
                else 64 * 256 / (stationDistance(sid, reach[i]) + 8)
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
    val statues = nearStatues()
    for (s in stations) {
        if (s == null) continue
        for (cg in 0 until cargoCount) {
            s.cargo[cg].removeAll { p ->
                val old = tick - p.waitingFrom > GIVE_UP_DAYS * Calendar.TICKS_PER_DAY.toLong()
                if (old) s.gaveUp[cg] += p.count
                old
            }
            // The rating creeps towards what the service deserves, a little a day.
            val target = (ratingTarget(s, cg) + if (s.id in statues) STATUE_RATING else 0).coerceAtMost(255)
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
    if (townWants(c) && people >= Cargo.TOWN_GOODS_PEOPLE) return true
    return takerAt(s, c) != null
}

/** Whether towns take cargo [c]: goods, food, fuel and lumber, and coal for heating homes until oil and gas take over. */
internal fun World.townWants(c: Int) = roster.cargo.towns[c] || (c == Cargo.COAL && Calendar.year(startYear, day) < COAL_HEATING_UNTIL)

/**
 * What coal or fuel brought to a town for heating pays on the day, in percent
 * of the usual: more the colder it is at station [s], up to [HEATING_MOST]
 * more in deep cold, nothing extra in summer.
 */
internal fun World.heating(s: Station): Int {
    val t = skyAt(s.signX.coerceIn(0, width - 1), s.signY.coerceIn(0, height - 1)).temperature
    return 100 + ((180 - t) * 4 / 10).coerceIn(0, HEATING_MOST)
}

/**
 * How many go out from station [s] today for the weather, in percent: fewer
 * in a downpour or a blizzard, more on a fine summer day, more again where
 * there's water or mountains near, and in winter at mountain stations with snow
 * for skiing.
 */
internal fun World.outings(s: Station): Int {
    val x = s.signX.coerceIn(0, width - 1)
    val y = s.signY.coerceIn(0, height - 1)
    val sky = skyAt(x, y)
    if (sky.precipitation >= HEAVY) return 75
    val look = lookToday()
    val fine = sky.precipitation == 0 && !sky.fog
    val summer = look == Climate.SUMMER || look == Climate.LATE_SUMMER
    val high = tileHeight(x, y) >= RESORT_HEIGHT
    var f = 100
    if (fine && summer) {
        f += 10
        if (high || waterNear(x, y)) f += 30
    }
    if (fine && look == Climate.WINTER && high && (weather.snow[index(x, y)].toInt() and 255) >= 30) f += 30
    return f
}

/** Whether there's water within [RESORT_REACH] tiles of ([x], [y]). */
private fun World.waterNear(x: Int, y: Int): Boolean {
    for (dy in -RESORT_REACH..RESORT_REACH) for (dx in -RESORT_REACH..RESORT_REACH) if (inside(x + dx, y + dy) && isWater(x + dx, y + dy)) return true
    return false
}

/** The year town homes stop burning coal. */
const val COAL_HEATING_UNTIL = 1960
/** The most extra, in percent, heating fuel pays in the cold. */
const val HEATING_MOST = 80
/** Precipitation, in hundredths of a mm a day, that keeps people home. */
const val HEAVY = 1500
/** Height in steps, and reach in tiles to water, that bring holidaymakers. */
const val RESORT_HEIGHT = 12
const val RESORT_REACH = 4

/** The station of [t]'s stop before the order it's on, past depot orders and stations it runs through, or -1. */
private fun World.stopBefore(t: Carrier): Int {
    val n = t.orders.size
    for (k in 1..n) t.orders[(t.order - k).mod(n)].let { if (isStop(it)) return Order.station(it) }
    return -1
}

/** The station [t] stops at after the one it's at now, past depot orders and stations it runs through, or -1. */
internal fun World.nextStop(t: Carrier): Int {
    if (t.orders.count { isStop(it) } < 2) return -1
    val n = t.orders.size
    for (k in 1..n) t.orders[(t.order + k).mod(n)].let { if (isStop(it)) return Order.station(it) }
    return -1
}

/**
 * Train [t] standing at station [s] on order [o]: those who've arrived get
 * off and pay for their whole journey, those changing here get off to wait
 * and their leg is credited to the train, then on get those whose next stop
 * is the train's. An order to unload sets everything down, paid where the
 * station takes it; one to transfer sets everything down to change trains;
 * one not to load takes nothing on.
 */
internal fun World.callAt(t: Carrier, s: Station, o: Int = 0) {
    if (t.orders.getOrNull(t.order)?.let { Order.station(it) } == s.id) reachOrder(t)
    // The link it has just run, timed from when it left its last stop, unless it left out a stop on the way.
    if (t.leftFrom >= 0 && t.leftAt >= 0 && t.leftFrom != s.id && stopBefore(t) == t.leftFrom) {
        timeLink(t.leftFrom, s.id, tick - t.leftAt)
        countRun(t.leftFrom, s.id, t.heldTicks)
    }
    t.leftFrom = s.id
    t.leftAt = -1
    t.heldTicks = 0
    val company = companies.getOrNull(t.owner) ?: return
    val next = nextStop(t)
    // Days on board, over every leg so far.
    val days = { p: Packet -> ((tick - p.since) / Calendar.TICKS_PER_DAY).toInt() }
    val ticks = { p: Packet -> tick - p.since }
    val unload = Order.has(o, Order.UNLOAD)
    val transfer = Order.has(o, Order.TRANSFER)
    var paid = 0L
    for (c in 0 until cargoCount) {
        val stay = ArrayList<Packet>()
        for (p in t.cargo[c]) {
            val from = stations.getOrNull(p.origin)
            val arrived = !transfer && (p.dest == s.id || ((p.dest == -1 || unload) && p.origin != s.id && accepts(s, c)))
            when {
                arrived && from != null -> {
                    var full = subsidised(company.id, c, from, s, pay(fare(roster.cargo, c, p.count, from, s, days(p), ticks(p))))
                    val taker = takerAt(s, c)
                    // Coal and fuel for a town's homes pay more in the cold.
                    if ((c == Cargo.COAL || c == Cargo.FUEL) && taker == null) full = full * heating(s) / 100
                    paid += full
                    // Freight for an industry here goes to it to be made into its own.
                    taker?.let { bring(it, c, p.count) }
                    delivered(company.id, c, p.count, from, s, taker)
                    if (c == Cargo.PASSENGERS) linkKey(from.id, s.id).let { k -> tripsMonth[k] = (tripsMonth[k] ?: 0) + p.count }
                    // The legs before were credited to the trains that carried them; this one gets the rest.
                    t.profitThisYear += full - p.credit
                }
                arrived -> {}
                transfer || unload || (p.dest >= 0 && nextHop(s.id, p.dest, c) != next) -> {
                    // Changing here: the leg it's done on this train is this train's, up to what the whole journey will pay.
                    val boarded = stations.getOrNull(p.boarded)
                    val dest = stations.getOrNull(p.dest)
                    val leg = if (boarded != null && boarded.id != s.id) pay(fare(roster.cargo, c, p.count, boarded, s, days(p), ticks(p))) else 0L
                    val whole = if (from != null && dest != null) pay(fare(roster.cargo, c, p.count, from, dest, days(p), ticks(p))) else leg
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
        earn(company, Books.INCOME, paid)
        payments += Payment(s.places.first(), paid, tick, company.id)
    }
    if (!Order.has(o, Order.NO_LOAD)) loadAt(t, s)
}

/** Train [t] at station [s] takes on what's waiting for its next stop, as much as it has room for. */
internal fun World.loadAt(t: Carrier, s: Station) {
    val next = nextStop(t)
    for (c in 0 until cargoCount) {
        // Trucks go half loaded on the spring thaw's load limits.
        val capacity = if (t is RoadVehicle && c > Cargo.MAIL && loadLimits()) t.capacity(c) / 2 else t.capacity(c)
        if (capacity > 0) {
            s.lastCall[c] = tick
            // A dual-mode train off the wires runs at its diesel speed.
            s.lastSpeed[c] = if (t is Train && !wired(t.steps.last())) t.dieselSpeed else t.topSpeed
        }
        var room = capacity - t.load(c)
        val it = s.cargo[c].iterator()
        while (room > 0 && it.hasNext()) {
            val p = it.next()
            // Only towards where it's going, and never back where it came from.
            if (next == p.origin || (p.dest != -1 && (next < 0 || nextHop(s.id, p.dest, c) != next))) continue
            val take = minOf(room, p.count)
            s.waitedTicks[c] += (tick - p.waitingFrom) * take
            s.boarded[c] += take
            // Passengers and mail starting out here count for the town as picked up from it.
            if (p.origin == s.id && (c == Cargo.PASSENGERS || c == Cargo.MAIL)) stationTown(s)?.let { town ->
                town.servedThisMonth += take
                if (c == Cargo.MAIL) town.mailThisMonth += take
            }
            // A share of the lot's credit goes with those who board, and its time on board carries on from where it was.
            val credit = p.credit * take / p.count
            t.cargo[c] += Packet(p.dest, p.origin, take, tick - (p.waitingFrom - p.since), credit, s.id)
            p.count -= take
            p.credit -= credit
            room -= take
            if (p.count == 0) it.remove()
        }
    }
}

/**
 * What [n] of cargo [c] pays carried from [from] to [to] with [days] on board: by the
 * distance between the stations, full if it came at its pace (a tile a day, about
 * 40 km/h, or two days a tile for freight that keeps) and within that cargo's
 * grace, then less each day after, down to a third. Passengers, mail and goods
 * carried faster than a train pay more, up to [Cargo.FAST] percent, by their
 * pace over [ticks] on board. [table] is the game's cargo.
 */
fun fare(table: CargoTable, c: Int, n: Int, from: Station, to: Station, days: Int, ticks: Long = days.toLong() * Calendar.TICKS_PER_DAY): Long {
    val dist = kotlin.math.abs(from.signX - to.signX) + kotlin.math.abs(from.signY - to.signY)
    val quick = dist * table.pace[c] + table.grace[c]
    val share = (100 - maxOf(0, days - quick) * table.decay[c]).coerceAtLeast(33)
    // Tiles a day on board, in hundredths, from the ticks so a short hop isn't rounded down to a day or none.
    val pace = (dist.toLong() * 100 * Calendar.TICKS_PER_DAY / ticks.coerceAtLeast(1)).toInt()
    val span = (Cargo.FAST_TO - Cargo.FAST_FROM) * 100
    val fast = table.fast[c] * (pace - Cargo.FAST_FROM * 100).coerceIn(0, span) / span
    return n.toLong() * dist * table.rate[c] * (share + fast) / 10_000
}

/**
 * The first of each month: what towns had picked up last month is what makes
 * them grow, and each company's stations near a town move what it thinks of
 * the company: up for good ratings, down for poor ones, back towards where
 * it started for a company with none there.
 */
internal fun World.passengersMonth() {
    networkStatsMonth()
    servicesMonth()
    serviceMonth()
    demandMonth()
    for (t in towns) {
        t.servedLastMonth = t.servedThisMonth
        t.servedThisMonth = 0
        t.mailLastMonth = t.mailThisMonth
        t.mailThisMonth = 0
        t.delivered.copyInto(t.deliveredLastMonth)
        t.delivered.fill(0)
        for (c in companies) {
            val near = stations.filter { s -> s != null && s.owner == c.id && maxOf(kotlin.math.abs(s.signX - t.x), kotlin.math.abs(s.signY - t.y)) <= TOWN_AREA }
            val a = t.approval[c.id]
            t.approval[c.id] = if (near.isEmpty()) a - (a - START_APPROVAL).coerceIn(-5, 5) else {
                val percent = near.sumOf { s -> (0 until cargoCount).sumOf { s!!.rating[it] } * 100 / (255 * cargoCount) } / near.size
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

/** The first of each year: every vehicle's profit moves to last year's. */
internal fun World.passengersYear() {
    for (t in trains) if (t != null) { t.profitLastYear = t.profitThisYear; t.profitThisYear = 0 }
    for (v in roadVehicles) if (v != null) { v.profitLastYear = v.profitThisYear; v.profitThisYear = 0 }
    for (v in ships) if (v != null) { v.profitLastYear = v.profitThisYear; v.profitThisYear = 0 }
    for (v in aircraft) if (v != null) { v.profitLastYear = v.profitThisYear; v.profitThisYear = 0 }
}

/** Payments shown rising over stations, kept this many ticks. */
const val PAYMENT_TICKS = Calendar.TICKS_PER_DAY * 3L

/** Whether spring load limits are on the roads: through the thaw, trucks carry half. */
internal fun World.loadLimits() = settings.weather && lookToday() == Climate.EARLY_SPRING
