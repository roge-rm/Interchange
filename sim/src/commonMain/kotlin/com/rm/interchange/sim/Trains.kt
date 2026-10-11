package com.rm.interchange.sim

/*
 * Trains and how they move.
 *
 * A train is an engine, its tender if it has one, and its wagons, nose to
 * tail, each as long as its pack says. Where it is
 * on the track is a list of steps, from the piece under its last wagon to
 * the piece under its nose, and how far along that last piece the nose is. A
 * step is a tile, the piece the train runs along there and the edge it came
 * in at, packed by [step].
 *
 * Positions are in 4096ths of a tile (a sixteenth of a tile is [FIX]) and
 * speeds in sixteenths of that a tick, all integers.
 *
 * Each tick the trains that need a route work one out, all at once across the
 * cores, since that only reads the track. Then they move one at a time in
 * order of their ids, each seeing where the ones before it have got to.
 */

const val FIX = 256


/** Packs a step: [tile], the index of the piece, and the edge come in at. */
fun step(tile: Int, pieceIndex: Int, entry: Int) = (tile shl 5) or (pieceIndex shl 2) or entry
fun stepTile(s: Int) = s ushr 5
fun stepPiece(s: Int) = Track.PIECES[(s shr 2) and 7]
fun stepEntry(s: Int) = s and 3
fun stepExit(s: Int) = Track.other(stepPiece(s), stepEntry(s))
fun stepLength(s: Int) = Track.length(stepPiece(s)) * FIX

class Train(
    /** The game's roster, which [engine] and [wagon] are places in. */
    val roster: Roster,
    val id: Int,
    val owner: Int,
    val engine: Int,
    val wagon: Int,
    val wagons: Int,
    /** The depot it was bought in, and goes back to. */
    var depot: Int,
) {
    /** Its own name, or empty to be called by its number in the screen's language. */
    var name = ""

    /** What each car is, nose first: the engine, its tender, then the wagons. */
    val carEngines: IntArray = buildList {
        add(engine)
        roster[engine].tender.takeIf { it >= 0 }?.let { add(it) }
        repeat(wagons) { add(wagon) }
    }.toIntArray()
    val cars get() = carEngines.size
    val length = carEngines.sumOf { roster[it].length } * FIX

    /** How far behind the nose car [i]'s middle is. */
    fun carMiddle(i: Int): Int {
        var d = 0
        for (k in 0 until i) d += roster[carEngines[k]].length
        return (d * 2 + roster[carEngines[i]].length) * FIX / 2
    }

    /** In its depot, off the map. */
    var inDepot = true
    /** Set going by the player, or stopped. */
    var running = false
    /** Ticks left waiting at a station. */
    var waiting = 0

    val steps = ArrayList<Int>()
    var nose = 0
    var speed = 0

    /** The stations it calls at, in turn. */
    val orders = ArrayList<Int>()
    var order = 0
    var toDepot = false

    /** The way ahead, worked out by [planRoute], and how far along it the train has got. */
    var route: IntArray? = null
    var routeAt = 0
    /** Turn round before going on: the way ahead lies behind. */
    var turnRound = false
    /** No way to where it's going; it tries again at this tick. */
    var lostUntil = 0L

    // The tiles and blocks it's on, as last counted.
    internal var onTiles = IntArray(0)
    internal var onBlocks = IntArray(0)

    val engineInfo get() = roster[engine]
    val wagonInfo get() = roster[wagon]
    val maxSpeed get() = engineInfo.speed * 4096 * 16 / 2700
    val weight get() = carEngines.sumOf { roster[it].weight }
    /** What it cost new: the engine, its tender and its wagons. */
    val price get() = carEngines.sumOf { roster[it].cost }

    /** Where it's heading: a station id, -1 for its depot, or -2 for nowhere. */
    val target: Int get() = when {
        toDepot -> -1
        orders.isEmpty() -> -2
        else -> orders[order % orders.size]
    }

    /**
     * The step and how far along it the point [d] behind the nose is. A step
     * index of -1 means it's still inside the depot it came out of.
     */
    fun behind(d: Int): Long {
        var left = d
        var i = steps.size - 1
        var along = nose
        while (left > along) {
            left -= along
            i--
            if (i < 0) return -1L shl 32
            along = stepLength(steps[i])
        }
        return (i.toLong() shl 32) or (along - left).toLong()
    }
}

fun World.trainOnTile(tile: Int) = (tileTrains[tile] ?: 0) > 0

/** Buys a train in a depot: an engine and [Command.BuyTrain.wagons] of one kind of wagon. */
internal fun World.buyTrain(company: Company, c: Command.BuyTrain) {
    val depot = depots.getOrNull(c.depot) ?: return
    if (depot.owner != company.id) return
    val e = roster.getOrNull(c.engine) ?: return
    val w = roster.getOrNull(c.wagon) ?: return
    val year = Calendar.year(startYear, day)
    if (!e.enabled || !w.enabled || !e.pulls || w.pulls || w.isTender || year < e.from || year >= e.until || year < w.from || year >= w.until) return
    if (c.wagons !in 0..MAX_WAGONS) return
    val t = Train(roster, trains.size, company.id, e.id, w.id, c.wagons, depot.id)
    if (company.money < t.price) return
    company.money -= t.price
    trains += t
}

internal fun World.trainCommand(company: Company, c: Command.TrainOrder) {
    val t = trains.getOrNull(c.train) ?: return
    if (t.owner != company.id) return
    when (c.what) {
        Command.TrainOrder.START_STOP -> {
            if (t.inDepot) {
                if (t.running) t.running = false else leaveDepot(t)
            } else t.running = !t.running
        }
        Command.TrainOrder.ORDERS -> {
            t.orders.clear()
            for (s in c.stations) if (stations.getOrNull(s)?.owner == company.id) t.orders += s
            t.order = 0
            t.route = null
        }
        Command.TrainOrder.TO_DEPOT -> {
            if (!t.inDepot) {
                t.toDepot = !t.toDepot
                t.route = null
            }
        }
        Command.TrainOrder.TURN -> if (!t.inDepot) {
            t.turnRound = true
            t.route = null
        }
        Command.TrainOrder.SELL -> if (t.inDepot) {
            company.money += t.price / 2
            trains[t.id] = null
        }
    }
}

/** Puts a train in its depot onto the track at the depot's door, nose first, if the tile's clear. */
private fun World.leaveDepot(t: Train) {
    val d = depots.getOrNull(t.depot) ?: return
    if (trainOnTile(d.tile)) {
        t.running = true
        return
    }
    t.steps.clear()
    t.steps += step(d.tile, Track.index(Track.straight(d.edge)), Track.opposite(d.edge))
    t.nose = 0
    t.speed = 0
    t.inDepot = false
    t.running = true
    t.route = null
    countOn(t)
}

/** Moves every train on a tick: routes first, across the cores, then the moves, in order. */
internal fun World.moveTrains() {
    if (blocksDirty) workOutBlocks()
    val list = trains.filterNotNull()
    // Trains in their depot set going, waiting for the door to clear.
    for (t in list) if (t.inDepot && t.running) leaveDepot(t)
    val planning = list.filter { !it.inDepot && it.running && it.route == null && tick >= it.lostUntil && it.target != -2 && it.waiting == 0 }
    val routes = arrayOfNulls<Plan>(planning.size)
    Workers.run(planning.size) { i -> routes[i] = planRoute(planning[i]) }
    for ((i, t) in planning.withIndex()) {
        val p = routes[i]
        if (p == null) {
            t.lostUntil = tick + LOST_RETRY
        } else {
            t.route = p.steps
            t.routeAt = 0
            if (p.behind) t.turnRound = true
        }
    }
    for (t in list) if (!t.inDepot) moveTrain(t)
}

private fun World.moveTrain(t: Train) {
    if (t.waiting > 0) {
        if (--t.waiting == 0) {
            t.order = (t.order + 1) % t.orders.size.coerceAtLeast(1)
            t.route = null
        }
        return
    }
    if (!t.running || t.target == -2) {
        t.speed = (t.speed - BRAKE).coerceAtLeast(0)
        if (t.speed > 0) advance(t, t.speed / 16, freeAhead(t).first)
        return
    }
    if (t.turnRound) {
        // Brake to a stand, then turn.
        if (t.speed > 0) {
            t.speed = (t.speed - BRAKE).coerceAtLeast(0)
            advance(t, t.speed / 16, freeAhead(t).first)
        } else turnRound(t)
        return
    }
    val (free, stop) = freeAhead(t)
    val allowed = isqrt(2L * BRAKE * 16 * free).toInt()
    val accel = (t.engineInfo.power * 16 / (t.weight * 8)).coerceAtLeast(1)
    t.speed = minOf(t.speed + accel, t.maxSpeed, allowed)
    val move = minOf(t.speed / 16, free)
    advance(t, move, free)
    if (move == free && t.speed / 16 >= free) {
        // At the stopping point.
        t.speed = 0
        when (stop) {
            Stop.STATION -> t.waiting = Calendar.TICKS_PER_DAY * 2
            Stop.DEPOT -> enterDepot(t)
            Stop.END -> if (t.route == null || t.routeAt >= (t.route?.size ?: 0)) {
                t.route = null
                t.lostUntil = 0
            }
            else -> {}
        }
    }
}

private enum class Stop { NONE, SIGNAL, TRAIN, STATION, DEPOT, END }

/** How far the train can go before it has to stand, up to what it needs to brake from full speed, and why it would stop. */
private fun World.freeAhead(t: Train): Pair<Int, Stop> {
    val reach = t.maxSpeed.toLong().let { (it * it / (2L * BRAKE) / 16).toInt() } + 16 * FIX
    var cur = t.steps.last()
    var free = stepLength(cur) - t.nose
    var at = t.routeAt
    val target = t.target
    while (free < reach) {
        val tile = stepTile(cur)
        if (target >= 0 && stationTiles[tile] == target) {
            val nx = nextFrom(t, cur, at)
            if (nx < 0 || stationTiles[stepTile(nx)] != target) return free to Stop.STATION
        }
        // Into the depot: it runs on in past the end of the tile until its last car is inside.
        if (target == -1 && depotTiles[tile] == t.depot) return free + t.length to Stop.DEPOT
        val next = nextFrom(t, cur, at)
        if (next < 0) return free to Stop.END
        val nt = stepTile(next)
        if (!mayPass(nt, stepPiece(next), stepEntry(next))) return free to Stop.END
        if (signalApplies(cur, next) && blockTaken(next, t)) return free to Stop.SIGNAL
        if ((tileTrains[nt] ?: 0) - (if (nt in t.onTiles) 1 else 0) > 0) return free to Stop.TRAIN
        free += stepLength(next)
        cur = next
        at++
    }
    return free to Stop.NONE
}

/** The step after [cur]: the route's, if it has one there, else where plain track leads with no choice to make. */
private fun World.nextFrom(t: Train, cur: Int, at: Int): Int {
    val r = t.route
    if (r != null && at < r.size) return r[at]
    return onlyWayOn(cur)
}

/** The next step from [cur] when the track doesn't branch, or -1. */
fun World.onlyWayOn(cur: Int): Int {
    val exit = stepExit(cur)
    val tile = stepTile(cur)
    val x = tile % width + Track.dx(exit)
    val y = tile / width + Track.dy(exit)
    if (!inside(x, y)) return -1
    val ways = waysIn(index(x, y), Track.opposite(exit))
    return if (ways.size == 1) ways[0] else -1
}

/** The steps a train coming into [tile] at [entry] could take there. */
fun World.waysIn(tile: Int, entry: Int): IntArray {
    val pieces = track[tile].toInt()
    if (pieces == 0) return IntArray(0)
    depotAt(tile)?.let { d -> return if (d.edge == entry) intArrayOf(step(tile, Track.index(Track.straight(entry)), entry)) else IntArray(0) }
    val out = ArrayList<Int>(2)
    for ((i, p) in Track.PIECES.withIndex()) if (pieces and p != 0 && Track.has(p, entry)) out += step(tile, i, entry)
    return out.toIntArray()
}

/** Whether a signal stands between step [cur] and the [next] one, for a train going that way. */
private fun World.signalApplies(cur: Int, next: Int): Boolean {
    val mine = signalAt(stepTile(cur), stepPiece(cur))
    if (mine >= 0 && Signals.facing(mine) == stepExit(cur) && Signals.kind(mine) == Signals.TWO_WAY) return true
    val theirs = signalAt(stepTile(next), stepPiece(next))
    return theirs >= 0 && Signals.facing(theirs) == stepEntry(next)
}

/** Whether the block [next] is in has another train in it. */
private fun World.blockTaken(next: Int, t: Train): Boolean {
    val b = blocks[pieceKey(stepTile(next), stepPiece(next))] ?: return false
    return (blockTrains[b] ?: 0) - (if (b in t.onBlocks) 1 else 0) > 0
}

/** Moves the nose on by [move], no further than [free], taking steps from the route as it goes. */
private fun World.advance(t: Train, move: Int, free: Int) {
    if (move <= 0) return
    t.nose += minOf(move, free)
    while (t.nose > stepLength(t.steps.last())) {
        // Running into its depot, the nose goes on past the end of the tile, into the shed.
        if (t.toDepot && depotTiles[stepTile(t.steps.last())] == t.depot) break
        val next = nextFrom(t, t.steps.last(), t.routeAt)
        if (next < 0) {
            t.nose = stepLength(t.steps.last())
            break
        }
        t.nose -= stepLength(t.steps.last())
        t.steps += next
        if (t.route != null) t.routeAt++
    }
    // Lets go of the steps wholly behind the last wagon.
    val tail = t.behind(t.length)
    val keep = (tail shr 32).toInt()
    if (keep > 0) repeat(keep) { t.steps.removeAt(0) }
    countOn(t)
}

/** Turns the train round where it stands: the last wagon leads. */
private fun World.turnRound(t: Train) {
    t.turnRound = false
    val tail = t.behind(t.length)
    val i = (tail shr 32).toInt()
    if (i < 0) return
    val along = (tail and 0xffffffffL).toInt()
    val kept = t.steps.subList(i, t.steps.size).map { s -> step(stepTile(s), Track.index(stepPiece(s)), stepExit(s)) }.reversed()
    t.steps.clear()
    t.steps += kept
    t.nose = stepLength(t.steps.last()) - along
    t.route = null
    countOn(t)
}

private fun World.enterDepot(t: Train) {
    val d = depots.getOrNull(t.depot)
    if (d == null || depotTiles[stepTile(t.steps.last())] != d.id) return
    t.inDepot = true
    t.running = false
    t.toDepot = false
    t.steps.clear()
    t.route = null
    countOn(t)
}

/** Counts the train afresh on the tiles and blocks it's on. */
internal fun World.countOn(t: Train) {
    for (tile in t.onTiles) tileTrains[tile] = (tileTrains[tile] ?: 1) - 1
    for (b in t.onBlocks) blockTrains[b] = (blockTrains[b] ?: 1) - 1
    if (t.inDepot) {
        t.onTiles = IntArray(0)
        t.onBlocks = IntArray(0)
        return
    }
    t.onTiles = t.steps.map { stepTile(it) }.distinct().toIntArray()
    t.onBlocks = t.steps.mapNotNull { blocks[pieceKey(stepTile(it), stepPiece(it))] }.distinct().toIntArray()
    for (tile in t.onTiles) tileTrains[tile] = (tileTrains[tile] ?: 0) + 1
    for (b in t.onBlocks) blockTrains[b] = (blockTrains[b] ?: 0) + 1
}

/** Running costs, taken every day: a 365th of a year's, the remainders carried. */
internal fun World.payRunningCosts() {
    for (t in trains) {
        if (t == null || t.inDepot && !t.running) continue
        val c = companies.getOrNull(t.owner) ?: continue
        val yearly = t.carEngines.sumOf { roster[it].running }
        c.runningOwed += yearly
        c.money -= c.runningOwed / 365
        c.runningOwed %= 365
    }
}

internal fun isqrt(n: Long): Long {
    if (n <= 0) return 0
    var x = kotlin.math.sqrt(n.toDouble()).toLong()
    // The double is only a guess; put it right in integers so every device agrees.
    while (x * x > n) x--
    while ((x + 1) * (x + 1) <= n) x++
    return x
}

/** How hard trains brake, in speed units a tick. */
const val BRAKE = 40
const val MAX_WAGONS = 12
/** Ticks before a train with no way to go tries again. */
const val LOST_RETRY = 64L
