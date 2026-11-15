package com.rm.interchange.sim

/*
 * Finding the way: a search over the track from a train's nose to where it's
 * going, a station's platforms or its depot. A place in the search is a tile
 * and the edge come in at, so the way a train faces counts, and one-way
 * signals are kept to. Costs are the length of track run, with a little more
 * for running through someone else's platforms.
 *
 * The full search reads where other trains stand and what they hold only to
 * weigh taken platforms, and nothing moves while it runs, so every train's
 * route can be found at once on the worker threads. [chooseFrom] reads them to
 * choose a way it can hold now, so it runs only in the moves, one train after
 * another.
 */

/** A way ahead: the steps after the train's nose, and whether the train has to turn round first. */
class Plan(val steps: IntArray, val behind: Boolean)

internal fun World.planRoute(t: Train): Plan? {
    val next = if (t.servicing && t.target == -1) t.orders.getOrNull(t.order % t.orders.size.coerceAtLeast(1))?.let { Order.station(it) } else null
    val onward = next?.let { railOnwardCosts(t, it) }?.takeIf { it.isNotEmpty() }
    val ahead = search(t.steps.last(), t, onward = onward)
    if (ahead != null && !t.servicing) return Plan(ahead.steps, false)
    // No way on ahead: try from the last wagon, turned round. With a way ahead it only weighs turning where it may;
    // with none at all it turns whatever, as at a dead end.
    val tail = t.behind(t.length)
    val i = (tail shr 32).toInt()
    if (i < 0 || ahead != null && !mayTurn(t, i)) return ahead?.let { Plan(it.steps, false) }
    val s = t.steps[i]
    val turned = step(stepTile(s), Track.index(stepPiece(s)), stepExit(s), stepLevel(s))
    val back = search(turned, t, onward = onward)
    return if (ahead != null && (back == null || (ahead.resumes && !back.resumes || ahead.resumes == back.resumes && ahead.cost <= back.cost))) Plan(ahead.steps, false) else back?.let { Plan(it.steps, true) }
}

/**
 * Whether [t] may turn round by itself where it stands, its last wagon on step [from], to go round a train or weigh a
 * way behind against one ahead: not with any of it on a one-way signal's piece, as it would then run back against
 * the signal.
 */
internal fun World.mayTurn(t: Train, from: Int): Boolean {
    for (k in from until t.steps.size) {
        val s = t.steps[k]
        if (stepLevel(s) != 0) continue
        val sig = signalAt(stepTile(s), stepPiece(s))
        if (sig >= 0 && Signals.oneWay(sig)) return false
    }
    return true
}

/** A way on from [t]'s last wagon, turned round, that keeps off the pieces in [avoid], or null. */
internal fun World.wayRound(t: Train, avoid: Set<Int>): IntArray? {
    val i = (t.behind(t.length) shr 32).toInt()
    if (i < 0 || !mayTurn(t, i)) return null
    val s = t.steps[i]
    val next = if (t.servicing) t.orders.getOrNull(t.order % t.orders.size.coerceAtLeast(1))?.let { Order.station(it) } else null
    val onward = next?.let { railOnwardCosts(t, it) }?.takeIf { it.isNotEmpty() }
    return search(step(stepTile(s), Track.index(stepPiece(s)), stepExit(s), stepLevel(s)), t, avoid, onward)?.steps
}

/**
 * A way back from [t]'s last wagon, turned round, into a loop or siding where the whole train stands off the tiles in
 * [clear] (tile keys), keeping off the pieces in [avoid], the tiles in [past] and other trains and what they hold; the
 * nearest, or null if there's none within [ASIDE_REACH]. The way ends where the nose stops.
 */
internal fun World.wayAside(t: Train, clear: Set<Int>, past: Set<Int>, avoid: Set<Int>): IntArray? {
    val i = (t.behind(t.length) shr 32).toInt()
    if (i < 0 || !mayTurn(t, i)) return null
    val s = t.steps[i]
    val from = step(stepTile(s), Track.index(stepPiece(s)), stepExit(s), stepLevel(s))
    val exit = stepExit(from)
    val a = across(stepTile(from), stepLevel(from), exit)
    if (a < 0) return null
    // A place is (tile * 2 + level) * 4 + the edge come in at; each is reached by the step run on the tile before.
    val first = a * 4 + Track.opposite(exit)
    val dist = HashMap<Int, Int>()
    val prev = HashMap<Int, Int>()
    val heap = Heap()
    dist[first] = 0
    heap.push(0, first)
    fun before(w: Int) = (stepTile(w) * 2 + stepLevel(w)) * 4 + stepEntry(w)
    // Whether the train, nose up to [node], stands wholly off [clear].
    fun standsClear(node: Int): Boolean {
        var n = node
        var run = 0
        while (run < t.length) {
            if (n == first) return false
            val w = prev.getValue(n)
            if (tileKey(w) in clear) return false
            run += stepLength(w)
            n = before(w)
        }
        return true
    }
    var looked = 0
    while (heap.size > 0) {
        val top = heap.pop()
        val cost = (top ushr 32).toInt()
        val node = top.toInt()
        if (cost > (dist[node] ?: Int.MAX_VALUE)) continue
        if (node != first && standsClear(node)) {
            val back = ArrayList<Int>()
            var n = node
            while (n != first) {
                val w = prev.getValue(n)
                back += w
                n = before(w)
            }
            back.reverse()
            return back.toIntArray()
        }
        if (cost > ASIDE_REACH || ++looked > MAX_LOOK) return null
        val nt = node / 8
        val level = (node / 4) % 2
        val entry = node % 4
        if (level == 0 && depotTiles.containsKey(nt)) continue
        for (w in waysIn(nt, entry, level)) {
            val piece = stepPiece(w)
            if (level == 0 && !mayPass(nt, piece, entry)) continue
            if (t.electric && !wired(w)) continue
            if (stepKey(w) in avoid) continue
            if (level == 0 && closedTile(nt)) continue
            if (!settings.sharing && owner[nt].toInt() != t.owner) continue
            if (takenByOther(t, w) || tileKey(w) in past) continue
            val out = Track.other(piece, entry)
            val na = across(nt, level, out)
            if (na < 0 || piecesAt(na / 2, na % 2) == 0) continue
            val n = na * 4 + Track.opposite(out)
            val c = cost + Track.length(piece)
            if (c < (dist[n] ?: Int.MAX_VALUE)) {
                dist[n] = c
                prev[n] = w
                heap.push(c, n)
            }
        }
    }
    return null
}

/** Whether another train is on step [w]'s tile, or holds its piece or one meeting it there. */
private fun World.takenByOther(t: Train, w: Int): Boolean {
    val place = tileKey(w)
    if ((tileTrains[place] ?: 0) - (if (place in t.onTiles) 1 else 0) > 0) return true
    val tile = stepTile(w)
    val level = stepLevel(w)
    val piece = stepPiece(w)
    for (q in Track.PIECES) {
        if (q != piece && !Track.touch(q, piece)) continue
        val id = reserved[pieceKey(tile, q, level)] ?: continue
        if (id != t.id) return true
    }
    return false
}

/** How far back a train looks for a loop or siding to stand aside in, in sixteenths of a tile: 64 tiles. */
private const val ASIDE_REACH = 64 * 16

/** Whether [next] carries on from [cur]:the tile through its way out, come in at the matching edge. */
fun World.follows(cur: Int, next: Int): Boolean {
    val a = across(stepTile(cur), stepLevel(cur), stepExit(cur))
    return a >= 0 && stepTile(next) == a / 2 && stepLevel(next) == a % 2 && stepEntry(next) == Track.opposite(stepExit(cur))
}

private class RailPath(val steps: IntArray, val cost: Int, val resumes: Boolean = true)

/**
 * The cheapest way from step [from] to where [t] is going. [reserving] is for a train at a path signal, [from] the step
 * past it: up to the next signal a piece another train holds or stands on can't be used and a taken platform isn't a
 * goal, so the first section can be held now; past it a piece another holds costs [HELD_PIECE] more. It then looks no
 * further than [CHOOSE_REACH] past [shortest] and at no more than [CHOOSE_LOOK] places.
 */
private fun World.search(from: Int, t: Train, avoid: Set<Int>? = null, onward: Map<Int, Int>? = null, reserving: Boolean = false, shortest: Int = 0): RailPath? {
    val target = t.target
    if (target == -2) return null
    // Off for a service, or with its home gone, it ends at any depot of its company's it can get into; sent in, only at its own.
    val anyDepot = anyDepot(t)
    val depotTile = if (target == -1 && !anyDepot) depots.getOrNull(t.depot)?.tile ?: return null else -1
    val electric = t.electric
    fun depotGoal(tile: Int, entry: Int): Boolean {
        val d = depotAt(tile) ?: return false
        if (d.edge != entry) return false
        if (!anyDepot) return tile == depotTile
        return d.owner == t.owner && d.kind == Depots.RAIL && (!electric || depotWired(d))
    }
    val dist = HashMap<Int, Int>()
    // How each place was reached: the place before, times 8, plus the piece run along from it.
    val prev = HashMap<Int, Long>()
    val heap = Heap()
    // A place is ((tile * 2 + level) * 4 + the edge come in at) * 2 + 1 if the way has passed a signal, only ever
    // set when [reserving].
    val exit = stepExit(from)
    val a = across(stepTile(from), stepLevel(from), exit)
    if (a < 0) return null
    val first = (a * 4 + Track.opposite(exit)) * 2
    dist[first] = 0
    heap.push(0, first)
    val most = if (reserving) CHOOSE_LOOK else MAX_LOOK
    val reach = if (reserving) shortest + CHOOSE_REACH else Int.MAX_VALUE
    // The step that came into a place.
    fun stepInto(place: Int): Int {
        if (place == first) return from
        val p = prev.getValue(place)
        val f = (p ushr 3).toInt() / 2
        return step(f / 8, (p and 7).toInt(), f % 4, (f / 4) % 2)
    }
    var looked = 0
    var nearest = -1
    var nearestCost = 0
    var best = -1
    var bestCost = Int.MAX_VALUE
    fun found(): RailPath? = when {
        best >= 0 -> RailPath(path(first, best, prev, target), bestCost)
        nearest >= 0 -> RailPath(path(first, nearest, prev, target), nearestCost, onward == null)
        else -> null
    }
    while (heap.size > 0) {
        val top = heap.pop()
        val cost = (top ushr 32).toInt()
        val place = top.toInt()
        if (cost > (dist[place] ?: Int.MAX_VALUE)) continue
        val node = place / 2
        val passed = place % 2 == 1
        val nt = node / 8
        val level = (node / 4) % 2
        val entry = node % 4
        if (cost >= bestCost || cost > reach) break
        if (level == 0 && if (target >= 0) stationTiles[nt] == target else depotGoal(nt, entry)) {
            // A platform taken in the first section can't be held now, so it's no goal.
            if (reserving && target >= 0 && !passed && signalFor(stepInto(place), platformStep(nt, entry)) < 0 && platformTaken(t, nt, entry)) continue
            if (onward == null) return RailPath(path(first, place, prev, target), cost)
            if (nearest < 0) { nearest = place; nearestCost = cost }
            onward[nt]?.let { after ->
                if (cost + after < bestCost) { best = place; bestCost = cost + after }
            }
            continue
        }
        if (++looked > most) return found()
        val came = if (reserving) stepInto(place) else 0
        for (w in waysIn(nt, entry, level)) {
            val piece = stepPiece(w)
            if (level == 0 && !mayPass(nt, piece, entry)) continue
            if (electric && !wired(w)) continue
            if (avoid != null && stepKey(w) in avoid) continue
            if (level == 0 && closedTile(nt)) continue
            if (!settings.sharing && owner[nt].toInt() != t.owner) continue
            val out = Track.other(piece, entry)
            val na = across(nt, level, out)
            if (na < 0 || piecesAt(na / 2, na % 2) == 0) continue
            var beyond = passed
            var c = cost + Track.length(piece)
            if (reserving) {
                if (!beyond && signalFor(came, w) >= 0) beyond = true
                if (!beyond && !pieceFree(t, w, occupiedTile = false, reserving = true)) continue
                if (beyond && heldPieceByOther(t, w)) c += HELD_PIECE
            }
            val n = (na * 4 + Track.opposite(out)) * 2 + if (beyond) 1 else 0
            if (level == 0 && stationTiles.containsKey(nt)) c += 16
            // A platform of the station it's going to with a train on it or held for one costs more, so another is
            // taken if there is one. In the first section a taken one isn't a goal at all.
            val into = na / 2
            if (na % 2 == 0 && target >= 0 && stationTiles[into] == target) {
                val inAt = Track.opposite(out)
                if ((!reserving || beyond || signalFor(w, platformStep(into, inAt)) >= 0) && platformTaken(t, into, inAt)) c += TAKEN_PLATFORM
            }
            if (c < (dist[n] ?: Int.MAX_VALUE)) {
                dist[n] = c
                prev[n] = (place.toLong() shl 3) or Track.index(piece).toLong()
                heap.push(c, n)
            }
        }
    }
    return found()
}

/** Costs out of each depot to the next station, respecting track direction, power and ownership. */
private fun World.railOnwardCosts(t: Train, target: Int): Map<Int, Int> {
    val electric = t.electric
    val pending = HashMap<Int, MutableList<Int>>()
    for (d in depots) {
        if (d == null || d.owner != t.owner || d.kind != Depots.RAIL || electric && !depotWired(d)) continue
        val a = across(d.tile, 0, d.edge)
        if (a >= 0) pending.getOrPut(a * 4 + Track.opposite(d.edge)) { ArrayList() }.add(d.tile)
    }
    val dist = HashMap<Int, Int>()
    val result = HashMap<Int, Int>()
    val heap = Heap()
    for (tile in stations.getOrNull(target)?.tiles.orEmpty()) for (entry in 0 until 4) {
        val node = tile * 8 + entry
        dist[node] = 0
        heap.push(0, node)
    }
    var looked = 0
    while (heap.size > 0 && pending.isNotEmpty()) {
        val top = heap.pop()
        val cost = (top ushr 32).toInt()
        val node = top.toInt()
        if (cost > (dist[node] ?: Int.MAX_VALUE)) continue
        pending.remove(node)?.forEach { result[it] = cost }
        if (++looked > MAX_LOOK) break
        val a = across(node / 8, node / 4 % 2, node % 4)
        if (a < 0) continue
        val tile = a / 2
        val level = a % 2
        if (depotTiles.containsKey(tile) && level == 0) continue
        val exit = Track.opposite(node % 4)
        for (entry in 0 until 4) for (w in waysIn(tile, entry, level)) {
            val piece = stepPiece(w)
            if (Track.other(piece, entry) != exit) continue
            if (level == 0 && (!mayPass(tile, piece, entry) || closedTile(tile))) continue
            if (electric && !wired(w)) continue
            if (!settings.sharing && owner[tile].toInt() != t.owner) continue
            // Confirm the level at both ends, including bridge heads.
            if (across(tile, level, exit) != node / 4) continue
            var c = cost + Track.length(piece)
            if (level == 0 && stationTiles.containsKey(tile)) c += 16
            val into = node / 8
            if (node / 4 % 2 == 0 && stationTiles[into] == target && platformTaken(t, into, node % 4)) c += TAKEN_PLATFORM
            val prev = a * 4 + entry
            if (c < (dist[prev] ?: Int.MAX_VALUE)) {
                dist[prev] = c
                heap.push(c, prev)
            }
        }
    }
    return result
}

/** The steps from [first] to [goal], then on to the far end of the platform if it's a station. */
private fun World.path(first: Int, goal: Int, prev: HashMap<Int, Long>, target: Int): IntArray {
    val back = ArrayList<Int>()
    var place = goal
    while (place != first) {
        val p = prev.getValue(place)
        val from = (p ushr 3).toInt()
        val f = from / 2
        back += step(f / 8, (p and 7).toInt(), f % 4, (f / 4) % 2)
        place = from
    }
    back.reverse()
    back += platformStep(goal / 16, goal / 2 % 4)
    if (target >= 0) {
        // Along the platform to its end.
        while (true) {
            val nx = onlyWayOn(back.last())
            if (nx < 0 || stationTiles[stepTile(nx)] != target || back.size > 4 * MAX_STATION) break
            back += nx
        }
    }
    return back.toIntArray()
}

/** The step [path] takes onto [tile] come in at [entry], at ground level: the first piece there that edge leads onto. */
private fun World.platformStep(tile: Int, entry: Int): Int {
    val piece = waysIn(tile, entry).firstOrNull()?.let { stepPiece(it) } ?: Track.straight(entry)
    return step(tile, Track.index(piece), entry)
}

/**
 * Whether the platform come into at [tile] by [entry] is taken for [t]: any tile of it, from there to its end as
 * [path] runs along it, with another train on it or holding a piece of it.
 */
internal fun World.platformTaken(t: Train, tile: Int, entry: Int): Boolean {
    val station = stationTiles[tile] ?: return false
    var s = platformStep(tile, entry)
    var n = 0
    while (true) {
        val k = stepTile(s)
        if ((tileTrains[k] ?: 0) - (if (k in t.onTiles) 1 else 0) > 0 || heldByOther(t, k)) return true
        val nx = onlyWayOn(s)
        if (nx < 0 || stepLevel(nx) != 0 || stationTiles[stepTile(nx)] != station || ++n > 4 * MAX_STATION) return false
        s = nx
    }
}

/** Whether a train other than [t] holds step [w]'s piece or one meeting it there. */
private fun World.heldPieceByOther(t: Train, w: Int): Boolean {
    val piece = stepPiece(w)
    for (q in Track.PIECES) {
        if (q != piece && !Track.touch(q, piece)) continue
        val id = reserved[pieceKey(stepTile(w), q, stepLevel(w))] ?: continue
        if (id != t.id) return true
    }
    return false
}

/**
 * At a path signal, a way on for [t] from step [next] past it, place [at] in its route, to where it's going, whose
 * first section up to the next signal it can hold now: the platform or bay free soonest, or a way round. Its route
 * becomes the steps up to [next] and the new way. False, leaving the route, if there's none.
 */
internal fun World.chooseFrom(t: Train, next: Int, at: Int): Boolean {
    val r = t.route ?: return false
    if (!pieceFree(t, next, occupiedTile = false, reserving = true)) return false
    val shortest = wayLeft(t, next, at)
    if (shortest < 0) return false
    val found = search(next, t, reserving = true, shortest = shortest) ?: return false
    val steps = found.steps
    if (r.size == at + 1 + steps.size && steps.indices.all { r[at + 1 + it] == steps[it] }) return true
    t.route = r.copyOf(at + 1) + steps
    return true
}

/**
 * How far [t]'s route runs on past step [next], place [at] in it, to the end, in sixteenths of a tile with station
 * tiles' extra, or -1 if [t] doesn't choose its way at the path signal before [next]: it does when the route ends at
 * its station or its own depot within [CHOOSE_NEAR] and [next] isn't on that station already.
 */
internal fun World.wayLeft(t: Train, next: Int, at: Int): Int {
    val r = t.route ?: return -1
    val target = t.target
    if (at >= r.size || r[at] != next || target == -2 || target == -1 && anyDepot(t)) return -1
    if (target >= 0 && stationTiles[stepTile(next)] == target) return -1
    var left = 0
    for (k in at + 1 until r.size) {
        left += Track.length(stepPiece(r[k])) + if (stepLevel(r[k]) == 0 && stationTiles.containsKey(stepTile(r[k]))) 16 else 0
        if (left > CHOOSE_NEAR) return -1
    }
    return left
}

/** Whether a train other than [t] holds any piece of [tile]. */
private fun World.heldByOther(t: Train, tile: Int): Boolean {
    for (q in Track.PIECES) reserved[pieceKey(tile, q)]?.let { if (it != t.id) return true }
    return false
}

/** What a platform already taken adds to a way's cost. */
private const val TAKEN_PLATFORM = 400

/** What a piece another train holds past the first signal adds to a way's cost, choosing at a path signal. */
private const val HELD_PIECE = 32

/** How much further than its planned way a train at a path signal looks for one it can hold: 64 tiles. */
private const val CHOOSE_REACH = 64 * 16

/** How near its goal a train's route has to end for it to choose its way at a path signal: 64 tiles. */
private const val CHOOSE_NEAR = 64 * 16

/** The most places a search at a path signal looks at. */
private const val CHOOSE_LOOK = 20_000

/** The most places a search looks at before giving up. */
private const val MAX_LOOK = 200_000

/** A min-heap of costs and places packed into longs. */
private class Heap {
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
            val s = a[p]; a[p] = a[i]; a[i] = s
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
            val s = a[m]; a[m] = a[i]; a[i] = s
            i = m
        }
        return top
    }
}
