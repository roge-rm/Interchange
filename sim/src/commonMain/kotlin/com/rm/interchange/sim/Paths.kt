package com.rm.interchange.sim

/*
 * Finding the way: a search over the track from a train's nose to where it's
 * going, a station's platforms or its depot. A place in the search is a tile
 * and the edge come in at, so the way a train faces counts, and one-way
 * signals are kept to. Costs are the length of track run, with a little more
 * for running through someone else's platforms.
 *
 * It only reads the track, never where other trains are, so every train's
 * route can be found at once on the worker threads.
 */

/** A way ahead: the steps after the train's nose, and whether the train has to turn round first. */
class Plan(val steps: IntArray, val behind: Boolean)

internal fun World.planRoute(t: Train): Plan? {
    val next = if (t.servicing && t.target == -1) t.orders.getOrNull(t.order % t.orders.size.coerceAtLeast(1))?.let { Order.station(it) } else null
    val onward = next?.let { railOnwardCosts(t, it) }?.takeIf { it.isNotEmpty() }
    val ahead = search(t.steps.last(), t, onward = onward)
    if (ahead != null && !t.servicing) return Plan(ahead.steps, false)
    // No way on ahead: try from the last wagon, turned round.
    val tail = t.behind(t.length)
    val i = (tail shr 32).toInt()
    if (i < 0) return ahead?.let { Plan(it.steps, false) }
    val s = t.steps[i]
    val turned = step(stepTile(s), Track.index(stepPiece(s)), stepExit(s), stepLevel(s))
    val back = search(turned, t, onward = onward)
    return if (ahead != null && (back == null || (ahead.resumes && !back.resumes || ahead.resumes == back.resumes && ahead.cost <= back.cost))) Plan(ahead.steps, false) else back?.let { Plan(it.steps, true) }
}

/** A way on from [t]'s last wagon, turned round, that keeps off the pieces in [avoid], or null. */
internal fun World.wayRound(t: Train, avoid: Set<Int>): IntArray? {
    val i = (t.behind(t.length) shr 32).toInt()
    if (i < 0) return null
    val s = t.steps[i]
    val next = if (t.servicing) t.orders.getOrNull(t.order % t.orders.size.coerceAtLeast(1))?.let { Order.station(it) } else null
    val onward = next?.let { railOnwardCosts(t, it) }?.takeIf { it.isNotEmpty() }
    return search(step(stepTile(s), Track.index(stepPiece(s)), stepExit(s), stepLevel(s)), t, avoid, onward)?.steps
}

/** Whether [next] carries on from [cur]: the tile through its way out, come in at the matching edge. */
fun World.follows(cur: Int, next: Int): Boolean {
    val a = across(stepTile(cur), stepLevel(cur), stepExit(cur))
    return a >= 0 && stepTile(next) == a / 2 && stepLevel(next) == a % 2 && stepEntry(next) == Track.opposite(stepExit(cur))
}

private class RailPath(val steps: IntArray, val cost: Int, val resumes: Boolean = true)

private fun World.search(from: Int, t: Train, avoid: Set<Int>? = null, onward: Map<Int, Int>? = null): RailPath? {
    val target = t.target
    if (target == -2) return null
    // Off for a service, or with its home gone, it ends at any depot of its company's it can get into; sent in, only at its own.
    val anyDepot = anyDepot(t)
    val depotTile = if (target == -1 && !anyDepot) depots.getOrNull(t.depot)?.tile ?: return null else -1
    val electric = t.engineInfo.traction == Traction.ELECTRIC
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
    // A place is (tile * 2 + level) * 4 + the edge come in at.
    val exit = stepExit(from)
    val a = across(stepTile(from), stepLevel(from), exit)
    if (a < 0) return null
    val first = a * 4 + Track.opposite(exit)
    dist[first] = 0
    heap.push(0, first)
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
        val node = top.toInt()
        if (cost > (dist[node] ?: Int.MAX_VALUE)) continue
        val nt = node / 8
        val level = (node / 4) % 2
        val entry = node % 4
        if (cost >= bestCost) break
        if (level == 0 && if (target >= 0) stationTiles[nt] == target else depotGoal(nt, entry)) {
            if (onward == null) return RailPath(path(first, node, prev, target), cost)
            if (nearest < 0) { nearest = node; nearestCost = cost }
            onward[nt]?.let { after ->
                if (cost + after < bestCost) { best = node; bestCost = cost + after }
            }
            continue
        }
        if (++looked > MAX_LOOK) return found()
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
            val n = na * 4 + Track.opposite(out)
            var c = cost + Track.length(piece)
            if (level == 0 && stationTiles.containsKey(nt)) c += 16
            // A platform of the station it's going to with a train on it or held for one costs more, so another is taken if there is one.
            val into = na / 2
            if (na % 2 == 0 && target >= 0 && stationTiles[into] == target && ((tileTrains[into] ?: 0) - (if (into in t.onTiles) 1 else 0) > 0 || heldByOther(t, into))) c += TAKEN_PLATFORM
            if (c < (dist[n] ?: Int.MAX_VALUE)) {
                dist[n] = c
                prev[n] = (node.toLong() shl 3) or Track.index(piece).toLong()
                heap.push(c, n)
            }
        }
    }
    return found()
}

/** Costs out of each depot to the next station, respecting track direction, power and ownership. */
private fun World.railOnwardCosts(t: Train, target: Int): Map<Int, Int> {
    val electric = t.engineInfo.traction == Traction.ELECTRIC
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
            if (node / 4 % 2 == 0 && stationTiles[into] == target && ((tileTrains[into] ?: 0) - (if (into in t.onTiles) 1 else 0) > 0 || heldByOther(t, into))) c += TAKEN_PLATFORM
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
    var node = goal
    while (node != first) {
        val p = prev.getValue(node)
        val from = (p ushr 3).toInt()
        back += step(from / 8, (p and 7).toInt(), from % 4, (from / 4) % 2)
        node = from
    }
    back.reverse()
    val goalTile = goal / 8
    val goalEntry = goal % 4
    val piece = waysIn(goalTile, goalEntry).firstOrNull()?.let { stepPiece(it) } ?: Track.straight(goalEntry)
    back += step(goalTile, Track.index(piece), goalEntry)
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

/** Whether a train other than [t] holds any piece of [tile]. */
private fun World.heldByOther(t: Train, tile: Int): Boolean {
    for (q in Track.PIECES) reserved[pieceKey(tile, q)]?.let { if (it != t.id) return true }
    return false
}

/** What a platform already taken adds to a way's cost. */
private const val TAKEN_PLATFORM = 400

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
