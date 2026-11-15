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


/** Packs a step: [tile], the index of the piece, the edge come in at, and the level, 1 on a bridge or in a tunnel. */
fun step(tile: Int, pieceIndex: Int, entry: Int, level: Int = 0) = (tile shl 6) or (level shl 5) or (pieceIndex shl 2) or entry
fun stepTile(s: Int) = s ushr 6
fun stepLevel(s: Int) = (s shr 5) and 1
fun stepPiece(s: Int) = Track.PIECES[(s shr 2) and 7]
fun stepEntry(s: Int) = s and 3
fun stepExit(s: Int) = Track.other(stepPiece(s), stepEntry(s))
fun stepLength(s: Int) = Track.length(stepPiece(s)) * FIX

class Train(
    /** The game's roster, which [engine] and [wagon] are places in. */
    val roster: Roster,
    override val id: Int,
    override var owner: Int,
    engine: Int,
    wagon: Int,
    wagons: Int,
    /** The depot it was bought in or last serviced at, and goes back to. */
    var depot: Int,
    /** What its wagons are set to carry, a [Cargo] number among those they can, or -1 for their first. */
    wagonCargo: Int = -1,
    formation: List<TrainCar>? = null,
) : Carrier {
    /** Its own name, or empty to be called by its number in the screen's language. */
    var name = ""

    /** Its number in its company's trains, from 1. Each company counts its own. */
    var number = 0

    internal val fallbackWagon = wagon
    /** Editable cars, nose first. Each locomotive brings its own tender. */
    var formation: List<TrainCar> = formation?.toList() ?: legacyFormation(roster, engine, wagon, wagons, wagonCargo)
        private set
    internal var locomotives = this.formation.filter { roster[it.model].pulls }.map { it.model }.toIntArray()
        private set
    val engine get() = locomotives.first()
    val wagon get() = formation.firstOrNull { roster[it.model].kind == VehicleKind.WAGON }?.model ?: fallbackWagon
    val wagons get() = formation.count { roster[it.model].kind == VehicleKind.WAGON }
    var wagonCargo: Int
        get() = formation.firstOrNull { it.model == wagon }?.cargo ?: -1
        set(value) { setFormation(formation.map { if (it.model == wagon) it.copy(cargo = value) else it }) }

    /** Physical cars include the tender immediately behind each locomotive. */
    var carEngines = formationModels(roster, this.formation)
        private set
    private var carCargoes = formationCargoes(roster, this.formation)
    var plow = carEngines.takeWhile { !roster[it].pulls }.maxOfOrNull { roster[it].plow } ?: Plows.NONE
        private set
    val cars get() = carEngines.size
    var length = carEngines.sumOf { roster[it].length } * FIX
        private set
    var electric = locomotives.any { roster[it].traction == Traction.ELECTRIC }
        private set
    var steam = locomotives.any { roster[it].traction == Traction.STEAM }
        private set
    var life = locomotives.minOf { roster[it].life }
        private set
    var newReliability = locomotives.minOf { roster[it].reliability } * 100
        private set

    internal fun setFormation(cars: List<TrainCar>) {
        formation = cars.toList()
        locomotives = formation.filter { roster[it.model].pulls }.map { it.model }.toIntArray()
        electric = locomotives.any { roster[it].traction == Traction.ELECTRIC }
        steam = locomotives.any { roster[it].traction == Traction.STEAM }
        life = locomotives.minOf { roster[it].life }
        newReliability = locomotives.minOf { roster[it].reliability } * 100
        topSpeed = locomotives.minOf { roster[it].speed }
        dieselSpeed = locomotives.minOf { roster[it].speed(false) }
        carEngines = formationModels(roster, formation)
        carCargoes = formationCargoes(roster, formation)
        length = carEngines.sumOf { roster[it].length } * FIX
        plow = carEngines.takeWhile { !roster[it].pulls }.maxOfOrNull { roster[it].plow } ?: Plows.NONE
    }

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
    override var waiting = 0

    val steps = ArrayList<Int>()
    var nose = 0
    var speed = 0

    /** What it's carrying, as lots by [Cargo]. */
    override val cargo = Array(Cargo.COUNT) { ArrayList<Packet>() }
    /** Fares less running costs, this year and last, in dollars. */
    override var profitThisYear = 0L
    var profitLastYear = 0L

    /** How much of cargo [c] its cars hold. */
    override fun capacity(c: Int) = carEngines.indices.sumOf { i -> if (carCargoes[i] == c) roster[carEngines[i]].capacity else 0 }

    /** Full of everything it can carry. */
    fun full() = (0 until Cargo.COUNT).all { load(it) >= capacity(it) }

    /** Full of at least one cargo it can carry, or unable to carry any cargo. */
    fun anyFull(): Boolean {
        var carries = false
        for (c in 0 until Cargo.COUNT) {
            val capacity = capacity(c)
            if (capacity > 0) {
                carries = true
                if (load(c) >= capacity) return true
            }
        }
        return !carries
    }

    /** The cargo choice of physical car [index], including tenders. */
    fun carCargo(index: Int): Int = carCargoes[index]

    override var group = -1
    /** The trains sharing their orders with this one have the same group, the first train's id; -1 for none. */
    override var shared = -1
    override var gap = 0
    override var departed = 0L

    /** The stations it calls at in turn, and what to do at each, packed by [Order]. */
    override val orders = ArrayList<Int>()
    override var order = 0
    var toDepot = false

    /** The way ahead, worked out by [planRoute], and how far along it the train has got. */
    var route: IntArray? = null
    var routeAt = 0
    /** Turn round before going on: the way ahead lies behind. */
    var turnRound = false
    /**
     * The depot its route ends in to turn round, or -1: it goes in, is serviced if due and comes out again to plan
     * afresh. While it's in there, the depot it's in.
     */
    var turnIn = -1
    /**
     * The station it has just called at when its next call is there again, or -1: it won't stop there till it has
     * been out and come back in. Cleared once no part of it is on the station.
     */
    var leave = -1
    /** No way to where it's going; it tries again at this tick. */
    var lostUntil = 0L
    /** Stuck in a drift, so the news is told once. */
    var snowbound = false
    /** Ticks it's stood held by a standing train coming the other way, counted every [HEAD_ON_CHECK] ticks. */
    var headOn = 0
    /** Held head-on with no way round, so the news is told once. */
    var stuck = false
    /** The id of the train it backed out of the way of, into a loop or siding, and stands there for; -1 for none. */
    var aside = -1
    /** Ticks since it set off to stand aside, to give up after [ASIDE_WAIT]. */
    var asideTicks = 0
    /** Standing at a signal or behind a train, as of its last move. */
    var held = false
    /** Came into the block it's in past a signal, so with collisions on it still stops for a train ahead. */
    var signalled = false
    /** A wreck, with this many ticks left before it's cleared away; 0 for a train that's whole. */
    var wreckedFor = 0
    /**
     * The curve speed in km/h a bend under it or ahead holds it to as of its last move, or 0. Only for the screen,
     * worked out again each tick from the track, so it's neither saved nor in the checksum.
     */
    var curveKmh = 0
    /**
     * The tick it last looked for a way at a signal, so it looks at most once a tick. Only ever compared with the
     * tick it's on, so it's neither saved nor in the checksum.
     */
    internal var choseAt = -1L

    /** The work day it was bought or last renewed, and last serviced. */
    var built = 0
    var serviced = 0
    /** How reliable it is now, in hundredths of a percent, and how many times it's broken down. */
    var reliability = 8500
    var breakdowns = 0
    /** Ticks left standing broken down. */
    var brokenFor = 0
    /** Sent for a service, and whether the player wants it serviced at all. */
    var servicing = false
    var service = true
    /** No depot it could reach for a service: the daily check for one long overdue waits till this tick. */
    var noDepotUntil = 0L
    override var renewAsked = NEVER_ASKED
    override var renewFailed = false
    override var renewOff = false

    // The tiles and blocks it's on, as last counted.
    internal var onTiles = IntArray(0)
    internal var onPieces = IntArray(0)
    internal var onBlocks = IntArray(0)

    val engineInfo get() = roster[engine]
    override var topSpeed = locomotives.minOf { roster[it].speed }
        private set
    val wagonInfo get() = roster[wagon]
    val maxSpeed get() = topSpeed * 4096 * 16 / 2700
    /** Its top speed off the wires, in km/h: lower than [topSpeed] for a dual-mode engine on its diesel. */
    var dieselSpeed = locomotives.minOf { roster[it].speed(false) }
        private set
    /** Its top speed in speed units, [wired] if under wires. */
    fun maxSpeed(wired: Boolean) = (if (wired) topSpeed else dieselSpeed) * 4096 * 16 / 2700
    val weight get() = carEngines.sumOf { roster[it].weight }
    /** What it cost new: the engine, its tender and its wagons. */
    val price get() = carEngines.sumOf { roster[it].cost }

    /** Where it's heading: a station id, -1 for a depot, [goalDepot], or -2 for nowhere. */
    val target: Int get() = when {
        toDepot -> -1
        orders.isEmpty() -> -2
        else -> Order.station(orders[order % orders.size])
    }

    /** The order it's on, or null with none. */
    val current: Int? get() = if (orders.isEmpty()) null else orders[order % orders.size]

    /** The depot it's heading for when [target] is -1: the one a depot order names, else its own. */
    val goalDepot: Int get() = if (toDepot) depot else current?.let { Order.depot(it) }?.takeIf { it >= 0 } ?: depot

    /** The depot its route runs into: the one it turns in, else [goalDepot] when it's going to a depot, else -1. */
    val shedGoal: Int get() = if (turnIn >= 0) turnIn else if (target == -1) goalDepot else -1

    /** The depot it's in when [inDepot]. */
    val shedIn: Int get() = if (turnIn >= 0) turnIn else depot

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

fun World.trainOnTile(tile: Int) = (tileTrains[tile] ?: 0) > 0 || (tileTrains[-tile - 1] ?: 0) > 0

/** Buys a train in a depot: an engine and [Command.BuyTrain.wagons] of one kind of wagon. */
internal fun World.buyTrain(company: Company, c: Command.BuyTrain) {
    val depot = depots.getOrNull(c.depot) ?: return
    if (depot.owner != company.id || depot.road) return
    val e = roster.getOrNull(c.engine) ?: return
    val w = roster.getOrNull(c.wagon) ?: return
    val year = Calendar.year(startYear, day)
    if (!e.enabled || !w.enabled || !e.pulls || w.kind != VehicleKind.WAGON || year < e.from || year >= e.until || year < w.from || year >= w.until) return
    if (c.wagons !in 0..MAX_WAGONS) return
    // An electric engine can't get out of a depot with no wires over it.
    if (e.traction == Traction.ELECTRIC && !depotWired(depot)) return
    buyTrainFormation(company, depot.id, legacyFormation(roster, e.id, w.id, c.wagons, if (c.cargo in w.cargoes) c.cargo else -1), w.id)
}

internal fun World.buyTrainFormation(company: Company, depotId: Int, cars: List<TrainCar>, fallbackWagon: Int) {
    val depot = depots.getOrNull(depotId) ?: return
    if (depot.owner != company.id || depot.kind != Depots.RAIL || !validTrainFormation(roster, cars)) return
    if (cars.any { !onSale(roster[it.model]) }) return
    val lead = cars.first { roster[it.model].pulls }.model
    val t = Train(roster, trains.size, company.id, lead, fallbackWagon, 0, depot.id, formation = cars)
    if (t.electric && !depotWired(depot)) return
    if (company.money < price(t.price)) return
    t.number = fleetNumber(company.id, trains, { it.owner }, { it.number })
    spend(company, Books.NEW_TRAINS, price(t.price))
    t.built = workDay
    t.serviced = workDay
    t.reliability = t.newReliability
    trains += t
}

internal fun World.trainCommand(company: Company, c: Command.TrainOrder) {
    val t = trains.getOrNull(c.train) ?: return
    if (t.owner != company.id || t.wreckedFor > 0) return
    // Orders and trains make the links passengers find their way by.
    if (c.what != Command.TrainOrder.REPLACE && c.what != Command.TrainOrder.GROUP && c.what != Command.TrainOrder.AUTORENEW) linksDirty = true
    when (c.what) {
        Command.TrainOrder.GROUP -> setVehicleGroup(company, Command.CopyVehicle.TRAIN, t, c.stations.firstOrNull())
        Command.TrainOrder.REPLACE -> replaceTrain(company, t, c.stations.firstOrNull())
        Command.TrainOrder.REFIT -> {
            val cargo = c.stations.firstOrNull() ?: return
            if (!t.inDepot || cargo !in t.wagonInfo.cargoes) return
            t.wagonCargo = cargo
            for (c in t.cargo.indices) if (t.capacity(c) == 0) t.cargo[c].clear()
        }
        Command.TrainOrder.START_STOP -> {
            if (t.inDepot) {
                if (t.running) t.running = false else leaveDepot(t)
            } else t.running = !t.running
        }
        Command.TrainOrder.ORDERS -> {
            // Every train sharing its orders gets the same.
            val group = trains.filter { it != null && t.shared >= 0 && it.shared == t.shared }.ifEmpty { listOf(t) }
            // A depot order has to be to one of the company's rail depots, wired if any of the trains is electric.
            val electric = group.any { it!!.electric }
            val orders = c.stations.toList().mapNotNull { allowedOrder(it, company.id, Depots.RAIL, electric) }
            for (g in group) {
                releaseAhead(g!!)
                g.orders.clear()
                g.orders += orders
                g.order = 0
                dropRoute(g)
                g.aside = -1
            }
        }
        Command.TrainOrder.GAP -> {
            val days = c.stations.firstOrNull() ?: return
            if (days !in 0..Order.MAX_WAIT_DAYS) return
            val group = trains.filterNotNull().filter { t.shared >= 0 && it.shared == t.shared }.ifEmpty { listOf(t) }
            for (g in group) g.gap = days
        }
        Command.TrainOrder.SHARE -> {
            val other = trains.getOrNull(c.stations.firstOrNull() ?: -1) ?: return
            if (other.owner != company.id || other.id == t.id || other.wreckedFor > 0) return
            if (other.shared < 0) other.shared = other.id
            // Depot orders an electric train can't take are dropped for the whole group, as ORDERS would.
            val orders = if (!t.electric) other.orders else other.orders.filter { !Order.isDepot(it) || depots.getOrNull(Order.depot(it))?.let { d -> depotWired(d) } == true }
            val group = if (orders.size == other.orders.size) listOf(t) else trains.filter { it != null && (it.shared == other.shared || it === t) }
            t.shared = other.shared
            t.gap = other.gap
            for (g in group) {
                if (g!! !== t) releaseAhead(g)
                g.orders.clear()
                g.orders += orders
                g.order = 0
                dropRoute(g)
                g.aside = -1
            }
        }
        Command.TrainOrder.UNSHARE -> t.shared = -1
        Command.TrainOrder.TO_DEPOT -> {
            if (!t.inDepot) {
                t.toDepot = !t.toDepot
                t.servicing = false
                t.aside = -1
                dropRoute(t)
            }
        }
        Command.TrainOrder.AUTORENEW -> t.renewOff = !t.renewOff
        Command.TrainOrder.SERVICE -> {
            t.service = !t.service
            if (!t.service && t.servicing) {
                t.servicing = false
                t.toDepot = false
                dropRoute(t)
                t.aside = -1
            }
        }
        Command.TrainOrder.RENEW -> if (t.inDepot) {
            val cost = price(t.price) - value(t)
            if (company.money < cost) return
            spend(company, Books.NEW_TRAINS, cost)
            t.built = workDay
            t.serviced = workDay
            t.reliability = t.newReliability
        }
        Command.TrainOrder.TURN -> if (!t.inDepot) {
            t.turnRound = true
            t.aside = -1
            t.route = null
        }
        Command.TrainOrder.SELL -> if (t.inDepot) {
            val backup = OrderBackup(ArrayList(t.orders), t.gap)
            reserved.entries.removeAll { it.value == t.id }
            earn(company, Books.TRAINS_SOLD, value(t))
            trains[t.id] = null
            pruneVehicleGroup(t.owner, Command.CopyVehicle.TRAIN, t.group)
            rememberOrders(company, Command.CopyVehicle.TRAIN, t, backup)
        }
    }
}

/** Puts a train in its depot onto the track at the depot's door, nose first, if the tile's clear. */
private fun World.leaveDepot(t: Train) {
    if (t.turnIn >= 0 && depots.getOrNull(t.turnIn) == null) t.turnIn = -1
    val d = depots.getOrNull(t.shedIn) ?: return
    // Its wires taken down since: it stays in, stopped.
    if (t.electric && !depotWired(d)) {
        t.running = false
        return
    }
    // An order to this depot it's done by being in it, and one to be serviced when it isn't due is skipped.
    t.current?.let { o -> if (Order.depot(o) == d.id && !t.toDepot) nextOrder(t) }
    skipService(t)
    // Every order a service it isn't due: it waits inside, set going, till one is.
    if (nothingDue(t)) {
        t.running = true
        return
    }
    // With no orders it has nowhere to go: it waits inside, set going, till it gets some. Held in by [wayOut], it
    // looks again at its lostUntil.
    if (trainOnTile(d.tile) || t.target == -2 || tick < t.lostUntil) {
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
    t.signalled = false
    t.aside = -1
    t.turnIn = -1
    countOn(t)
    val p = planRoute(t)
    if (p != null && !p.behind) {
        t.route = p.steps
        t.routeAt = 0
        t.turnIn = p.turnIn
    }
    if (!wayOut(t)) {
        // Its way out isn't clear: back inside to look again shortly.
        t.inDepot = true
        t.steps.clear()
        t.route = null
        t.turnIn = if (d.id == t.depot) -1 else d.id
        countOn(t)
        t.lostUntil = tick + HEAD_ON_CHECK
    }
}

/**
 * Whether [t], just put in its depot's door, may set out. Onto signalled track it goes as if past a signal at the
 * door: on a path signal's way it holds the pieces ahead, through to the next signal, and on through more path
 * signals while standing there would leave some of it in the door, as a train coming in would otherwise hold the lead
 * up to the door and wait for it; a block ahead has to be clear. Holds what it needs if it may. Onto unsignalled track
 * it goes once [plainWayClear].
 */
private fun World.wayOut(t: Train): Boolean {
    val door = t.steps.last()
    // Run from the start of the door step to standing wholly out of it.
    val need = stepLength(door) + t.length
    val hold = ArrayList<Int>()
    var first = door
    var at = -1
    var past = -1
    var run = 0
    var checked = false
    while (true) {
        // The section from [first] to the next signal, or the end of the way.
        val section = ArrayList<Int>()
        var s = first
        var i = at
        var sig = -1
        var path = past >= 0 && Signals.path(past)
        while (section.size < MAX_PATH) {
            section += s
            if (inPathArea(s)) path = true
            // Its way ends at the end of its route, or of the platform it's going to.
            val r = t.route
            if (r != null && i + 1 >= r.size) break
            val n = nextFrom(t, s, i + 1)
            if (n < 0) break
            if (t.target >= 0 && stationTiles[stepTile(s)] == t.target && stationTiles[stepTile(n)] != t.target) break
            sig = signalFor(s, n)
            if (sig >= 0) {
                if (Signals.path(sig)) path = true
                first = n
                at = i + 1
                break
            }
            s = n
            i++
        }
        // Into the first section past no signal, and none ahead: plain track, as before.
        if (past < 0 && sig < 0 && !path) return plainWayClear(t)
        checked = true
        if (path) {
            for (w in section) if (!pieceFree(t, w, occupiedTile = false, reserving = true)) return false
            hold += section
        } else {
            val b = blocks[stepKey(section[0])]
            if (b != null && !blockClear(t, b, if (past >= 0) Signals.kind(past) else Signals.TWO_WAY, 0)) return false
        }
        for (w in section) run += stepLength(w)
        if (sig < 0 || run >= need) break
        past = sig
    }
    for (w in hold) reserved[stepKey(w)] = t.id
    if (checked) t.signalled = true
    return true
}

/**
 * Whether [t], in its depot's door onto unsignalled track, may set out: no other train on or crossing the way it would
 * pull onto, through to standing out of the door and its braking reach on, and none with that way inside its own
 * braking reach, as a train come on past no signal doesn't look out for one ahead. Trains still in a depot have no
 * steps, and one stopped and standing won't come on.
 */
private fun World.plainWayClear(t: Train): Boolean {
    val door = t.steps.last()
    val reach = stepLength(door) + t.length + brakeReach(t)
    val way = ArrayList<Int>()
    var s = door
    var i = -1
    var run = 0
    while (way.size < MAX_PATH) {
        if (!pieceFree(t, s, occupiedTile = !inPathArea(s), reserving = true)) return false
        way += s
        run += stepLength(s)
        if (run >= reach) break
        val n = nextFrom(t, s, i + 1)
        if (n < 0) break
        s = n
        i++
    }
    for (u in trains) {
        if (u == null || u === t || u.inDepot || u.wreckedFor > 0 || !u.running && u.speed == 0) continue
        val r = brakeReach(u)
        var cur = u.steps.last()
        var at = u.routeAt
        var free = stepLength(cur) - u.nose
        while (free < r) {
            val n = nextFrom(u, cur, at)
            if (n < 0) break
            for (w in way) if (tileKey(w) == tileKey(n) && (stepPiece(w) == stepPiece(n) || Track.touch(stepPiece(w), stepPiece(n)))) return false
            free += stepLength(n)
            cur = n
            at++
        }
    }
    return true
}

/** How far ahead [t] looks for what it may have to stop for: its braking distance from its top speed, and a tile. */
private fun brakeReach(t: Train) = t.maxSpeed.toLong().let { (it * it / (2L * BRAKE) / 16).toInt() } + 16 * FIX

/** Moves every train on a tick: routes first, across the cores, then the moves, in order. */
internal fun World.moveTrains() {
    if (blocksDirty) workOutBlocks()
    val list = trains.filterNotNull()
    // Trains in their depot set going, waiting for the door to clear.
    for (t in list) if (t.inDepot && t.running) leaveDepot(t)
    val planning = list.filter { !it.inDepot && it.running && it.route == null && tick >= it.lostUntil && it.target != -2 && it.waiting == 0 && it.aside < 0 }
    val routes = arrayOfNulls<Plan>(planning.size)
    Workers.run(planning.size) { i -> routes[i] = planRoute(planning[i]) }
    for ((i, t) in planning.withIndex()) {
        val p = routes[i]
        if (p == null && t.leave >= 0 && t.leave == t.target) {
            // No way out of the station and back in to call again: on past the repeat to plan afresh.
            skipRepeat(t)
        } else if (p == null && unwiredOrder(t)) {
            // Its depot order is to a depot with no wires: on to its next order, waiting a while if that's one too.
            nextOrder(t)
            dropRoute(t)
            t.lostUntil = if (unwiredOrder(t)) tick + LOST_RETRY else 0
        } else if (p == null) {
            // No depot it can reach for its service: straight back to its orders, to try again after its next call.
            if (t.toDepot && t.servicing) noDepot(t) else t.lostUntil = tick + LOST_RETRY
            dropRoute(t)
        } else {
            // Off for a service, or home gone, it goes to whichever depot its route found, and lives there after.
            if (anyDepot(t)) depotTiles[stepTile(p.steps.last())]?.let { t.depot = it }
            t.route = p.steps
            t.routeAt = 0
            t.turnIn = p.turnIn
            if (p.behind) t.turnRound = true
        }
    }
    for (t in list) {
        if (t.leave >= 0 && t.onTiles.none { it >= 0 && stationTiles[it] == t.leave }) t.leave = -1
        if (!t.inDepot) moveTrain(t)
    }
}

/** Whether [t]'s order now is one to stop at a station. */
private fun World.stops(t: Train): Boolean {
    val o = t.current ?: return false
    val id = Order.station(o)
    return id >= 0 && !Order.has(o, Order.VIA) && stations.getOrNull(id)?.waypoint != true
}

/** On past the orders that would call again at the station [t] is leaving, as it has no way out and back in. */
private fun World.skipRepeat(t: Train) {
    repeat(t.orders.size) { if (t.target == t.leave) nextOrder(t) }
    t.leave = -1
    t.lostUntil = 0
}

private fun World.moveTrain(t: Train) {
    if (t.wreckedFor > 0) {
        if (--t.wreckedFor == 0) clearWreck(t)
        return
    }
    drive(t)
    if (!settings.collisions || t.inDepot) return
    val hit = trainsHit(t)
    if (hit.isEmpty()) return
    val all = listOf(t) + hit
    for (u in all) wreck(u)
    crashed(stepTile(t.steps.last()), all.map { CrashVehicle(Crash.TRAIN, it.id, it.owner, it.name) to it })
}

/**
 * The trains [t]'s nose has run into, in order of id: those that aren't wrecks on the piece under its nose or one
 * meeting or crossing it there. Trains kept apart by signals and reserved paths never get this close.
 */
private fun World.trainsHit(t: Train): List<Train> {
    val out = ArrayList<Train>()
    val s = t.steps.last()
    val tile = stepTile(s)
    val level = stepLevel(s)
    val piece = stepPiece(s)
    val pieces = piecesAt(tile, level)
    for (q in Track.PIECES) {
        if (pieces and q == 0 || (q != piece && !Track.touch(q, piece))) continue
        val key = pieceKey(tile, q, level)
        if ((pieceTrains[key] ?: 0) - (if (key in t.onPieces) 1 else 0) <= 0) continue
        for (u in trains) if (u != null && u.id != t.id && !u.inDepot && u.wreckedFor == 0 && key in u.onPieces && u !in out) out += u
    }
    out.sortBy { it.id }
    return out
}

/** Wrecks train [t] where it stands: stopped, its orders and path let go. */
internal fun World.wreck(t: Train) {
    t.wreckedFor = WRECK_TICKS
    t.running = false
    t.speed = 0
    t.waiting = 0
    t.brokenFor = 0
    t.toDepot = false
    t.servicing = false
    t.turnRound = false
    t.held = false
    t.stuck = false
    t.headOn = 0
    t.aside = -1
    t.snowbound = false
    dropRoute(t)
    t.orders.clear()
    t.order = 0
    t.shared = -1
    releaseAhead(t)
    linksDirty = true
}

/** Clears wreck [t] off the track, gone for good. */
private fun World.clearWreck(t: Train) {
    reserved.entries.removeAll { it.value == t.id }
    t.steps.clear()
    t.inDepot = true
    countOn(t)
    linksDirty = true
    trains[t.id] = null
    pruneVehicleGroup(t.owner, Command.CopyVehicle.TRAIN, t.group)
}

/** Whether some train is a wreck, so track ahead is looked over for one. */
private fun World.anyWreck() = trains.any { it != null && it.wreckedFor > 0 }

private fun World.drive(t: Train) {
    val wasHeld = t.held
    t.held = false
    t.curveKmh = 0
    if (t.aside >= 0 && ++t.asideTicks >= ASIDE_WAIT) {
        // Waited long enough, stood aside or still on its way there: it plans again from where it is.
        t.aside = -1
        t.headOn = 0
        dropRoute(t)
        t.lostUntil = 0
    }
    if (t.waiting > 0) {
        if (--t.waiting == 0) {
            // A full load order keeps it there, loading as more comes, till it's full, unless it's due a service.
            val o = t.orders.getOrNull(t.order % t.orders.size.coerceAtLeast(1)) ?: 0
            val s = stations.getOrNull(Order.station(o))
            // One held off the depot, by no way there or a jam called off, isn't sent again till its noDepotUntil.
            val due = !t.toDepot && tick >= t.noDepotUntil && hasDepot(t.owner, Depots.RAIL) && (due(t.service, t.serviced) || renewNow(t))
            fun loaded() = when {
                Order.has(o, Order.FULL_LOAD) -> t.full()
                Order.has(o, Order.FULL_LOAD_ANY) -> t.anyFull()
                else -> true
            }
            if (s != null && !loaded() && !due) {
                loadAt(t, s)
                if (!loaded()) {
                    t.waiting = FULL_LOAD_RETRY
                    return
                }
            }
            if (waitForGap(t, due)) return
            nextOrder(t)
            dropRoute(t)
            // Called here again next: out and back in first.
            val here = Order.station(o)
            t.leave = if (here >= 0 && t.target == here && stops(t)) here else -1
            // Due a service: choose a depot on the way to its next order, unless that's a depot.
            if (due && t.current?.let { Order.isDepot(it) } != true) forService(t)
        }
        return
    }
    if (t.brokenFor > 0) {
        // Broken down: it rolls to a stand and waits for the fitters, still held if it was stood at a signal or train.
        t.brokenFor--
        t.held = wasHeld && t.speed == 0
        t.speed = (t.speed - BRAKE).coerceAtLeast(0)
        if (t.speed > 0) advance(t, t.speed / 16, freeAhead(t).first)
        else if (atDoor(t)) {
            // Stood in its depot's door: back in, with the fitters there.
            t.brokenFor = 0
            backIn(t)
        } else if (inShed(t)) {
            // Stood wholly in its shed: in, with the fitters there.
            t.brokenFor = 0
            enterDepot(t)
        }
        return
    }
    if (!t.running || t.target == -2) {
        t.speed = (t.speed - BRAKE).coerceAtLeast(0)
        if (t.speed > 0) advance(t, t.speed / 16, freeAhead(t).first)
        else if (atDoor(t)) backIn(t)
        else if (inShed(t)) {
            // Stopped running in: in, and still stopped.
            enterDepot(t)
            t.running = false
        }
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
    if (t.aside >= 0 && t.route == null) {
        // Stood aside: it waits till the train it made way for has gone by.
        t.speed = 0
        if (tick % HEAD_ON_CHECK == 0L && gonePast(t)) {
            t.aside = -1
            t.lostUntil = 0
        }
        return
    }
    // An order to be serviced when it isn't due is skipped, as when its orders were set.
    if (skipService(t)) {
        dropRoute(t)
        t.aside = -1
    }
    // Going via a station: once the nose is in it, on to the next order without stopping.
    val o = t.orders.getOrNull(t.order % t.orders.size.coerceAtLeast(1)) ?: 0
    if ((Order.has(o, Order.VIA) || stations.getOrNull(Order.station(o))?.waypoint == true) && stationTiles[stepTile(t.steps.last())] == Order.station(o)) {
        nextOrder(t)
        dropRoute(t)
        t.aside = -1
    }
    // Wet rail, leaves, ice and snow take the grip away, for pulling away and for braking; deep snow holds it back.
    val nose = stepTile(t.steps.last())
    val out = exposed(t.steps.last())
    val grip = if (out) adhesion(nose) else 100
    val brake = BRAKE * (50 + grip / 2) / 100
    val bends = bendScan.reset(t.speed, brake)
    val (free, stop) = freeAhead(t, brake, bends)
    val allowed = isqrt(2L * brake * 16 * free).toInt()
    // A dual-mode engine runs on its electric power and speed with wires over its nose, on its diesel without, braking
    // down to the diesel's top speed when it leaves them.
    val wire = wired(t.steps.last())
    val top = t.maxSpeed(wire).let { if (t.speed > it) maxOf(it, t.speed - brake) else it }
    // Pulling: faster or slower by what the engine has over what holds the train back, never below a crawl.
    val pulled = maxOf(t.speed + acceleration(t, grip, wire), minOf(CRAWL, maxOf(t.speed, 1) + 4))
    val worn = if (stepLevel(t.steps.last()) == 0) trackLimit(nose) else -1
    val wornLimit = if (worn < 0) t.maxSpeed else worn * 4096 * 16 / 2700
    val speed = minOf(minOf(pulled, if (out) snowLimit(t, nose) else t.maxSpeed), minOf(spanLimit(t), wornLimit), minOf(allowed, top))
    if (bends.limit < speed) t.curveKmh = bends.kmh
    t.speed = minOf(speed, bends.limit)
    val move = minOf(t.speed / 16, free)
    advance(t, move, free)
    if (stop == Stop.SNOW && t.speed / 16 >= free) {
        // Up against a drift: it pushes into it, a little at a time.
        val ahead = nextFrom(t, t.steps.last(), t.routeAt)
        if (ahead >= 0 && tick % RAM_TICKS == 0L) {
            val tile = stepTile(ahead)
            weather.snow[tile] = ((weather.snow[tile].toInt() and 255) - (if (t.plow == Plows.WEDGE) 4 else 1)).coerceAtLeast(0).toByte()
        }
        if (!t.snowbound) {
            t.snowbound = true
            tell(News.SNOWBOUND, t.id)
        }
        t.speed = 0
        return
    }
    if (move > 0) {
        t.snowbound = false
        t.headOn = 0
        t.stuck = false
    }
    if (move == free && t.speed / 16 >= free) {
        // At the stopping point.
        t.speed = 0
        t.held = stop == Stop.TRAIN || stop == Stop.SIGNAL
        if ((stop == Stop.TRAIN || stop == Stop.SIGNAL) && tick % HEAD_ON_CHECK == 0L && headOnWait(t)) return
        when (stop) {
            Stop.SIGNAL -> if (tick % PATH_RETRY == 0L && t.route != null && t.aside < 0 && !choosesHere(t)) {
                // Held at a signal it doesn't choose its way at: look again for a way, another platform perhaps.
                dropRoute(t)
                releaseAhead(t)
            }
            Stop.STATION -> {
                val o = t.orders.getOrNull(t.order % t.orders.size.coerceAtLeast(1)) ?: 0
                t.waiting = maxOf(Calendar.TICKS_PER_DAY * 2, Calendar.TICKS_PER_DAY * Order.waitDays(o))
                stationAt(stepTile(t.steps.last()))?.let { callAt(t, it, o) }
            }
            Stop.DEPOT -> enterDepot(t)
            Stop.END -> if (t.route == null || t.routeAt >= (t.route?.size ?: 0)) {
                dropRoute(t)
                t.lostUntil = 0
            }
            else -> {}
        }
    }
}

/** Whether [t], standing at a signal, is at a path signal where it chooses its way, so keeps its route. */
private fun World.choosesHere(t: Train): Boolean {
    val cur = t.steps.last()
    val next = nextFrom(t, cur, t.routeAt)
    if (next < 0) return false
    val sig = signalFor(cur, next)
    return sig >= 0 && Signals.path(sig) && wayLeft(t, next, t.routeAt) >= 0
}

/**
 * Counts how long [t], standing, has been held by a standing train coming the other way on its path. When each holds
 * the other up, after [HEAD_ON_WAIT] the one with the higher id turns round onto a way that keeps off the other, or
 * the lower id once the higher is stuck; a train in the way that isn't held at a signal or behind a train (stopped,
 * lost, snowed in) is gone round by [t] whatever the ids. With no way round it tells the news once for the pair. A
 * train going the same way, or one held by something else, is just waited for. True if it turned.
 */
private fun World.headOnWait(t: Train): Boolean {
    val u = firstInWay(t)
    // A wreck in the way is waited out.
    if (u == null || u.wreckedFor > 0 || u.speed != 0 || u.waiting > 0 || u.brokenFor > 0 || (u.held && firstInWay(u) !== t)) {
        t.headOn = 0
        return false
    }
    t.headOn += HEAD_ON_CHECK.toInt()
    if (t.headOn < HEAD_ON_WAIT || (u.held && u.id > t.id && !u.stuck)) return false
    t.headOn = 0
    val avoid = HashSet<Int>()
    for (p in u.onPieces) avoid += p
    for ((key, id) in reserved) if (id == u.id) avoid += key
    // On a single line with no way round, it backs into a loop or siding behind it and lets the other by, once the
    // other has a way ahead to keep clear of.
    var aside = -1
    val ahead = u.route.let { it != null && u.routeAt < it.size }
    val way = wayRound(t, avoid) ?: if (u.held && ahead) wayAside(t, wayOf(u), endOf(u), avoid)?.also { aside = u.id } else null
    if (way == null) {
        if (!t.stuck) {
            t.stuck = true
            if (!u.stuck) tell(News.TRAIN_STUCK, t.id)
        }
        return false
    }
    turnRound(t)
    t.route = way
    t.routeAt = 0
    t.turnIn = -1
    t.lostUntil = 0
    t.aside = aside
    t.asideTicks = 0
    t.stuck = false
    return true
}

/** The tiles, as tile keys, train [u] is on, holds, or has yet to run over on its route. */
private fun World.wayOf(u: Train): Set<Int> {
    val out = HashSet<Int>()
    for (k in u.onTiles) out += k
    u.route?.let { r -> for (k in u.routeAt until r.size) out += tileKey(r[k]) }
    for ((key, id) in reserved) if (id == u.id) out += if (key >= 0) key / 8 else -((-key - 1) / 8) - 1
    return out
}

/**
 * The tiles, as tile keys, where [u]'s route ends: the whole station if it ends at one. A train standing aside past
 * them would have [u] stop in front of it, so it doesn't go there.
 */
private fun World.endOf(u: Train): Set<Int> {
    val last = u.route?.lastOrNull() ?: return emptySet()
    val station = if (stepLevel(last) == 0) stationTiles[stepTile(last)] else null
    return stations.getOrNull(station ?: -1)?.tiles?.toSet() ?: setOf(tileKey(last))
}

/**
 * Whether the train [t] stood aside for has gone by: it's gone, or nothing it's on, holds or has yet to run over lies
 * on [t]'s way on from here. One between routes isn't judged till it has one again.
 */
private fun World.gonePast(t: Train): Boolean {
    val u = trains.getOrNull(t.aside) ?: return true
    if (u.inDepot || u.wreckedFor > 0) return true
    if (u.route == null && u.running && u.waiting == 0) return false
    val p = planRoute(t) ?: return true
    val way = wayOf(u)
    return p.steps.none { tileKey(it) in way }
}

/**
 * The first train on [t]'s path ahead that holds it up, if it's coming the other way: on, or holding, a piece
 * there that meets [t]'s and heading out through the edge [t] comes in at. Null if the first is going the same way.
 */
private fun World.firstInWay(t: Train): Train? {
    var cur = t.steps.last()
    var at = t.routeAt
    // The other trains on each tile, by tile key in order of id, made when first needed.
    var onTile: HashMap<Int, ArrayList<Train>>? = null
    repeat(MAX_PATH) {
        val next = nextFrom(t, cur, at)
        if (next < 0) return null
        val tile = stepTile(next)
        val level = stepLevel(next)
        val piece = stepPiece(next)
        fun meets(q: Int) = q == piece || Track.touch(q, piece)
        val place = tileKey(next)
        var holder: Train? = null
        if ((tileTrains[place] ?: 0) - (if (place in t.onTiles) 1 else 0) > 0) {
            val map = onTile ?: HashMap<Int, ArrayList<Train>>().also { m ->
                for (u in trains) if (u != null && u.id != t.id && !u.inDepot) for (k in u.onTiles) m.getOrPut(k) { ArrayList() }.let { if (it.lastOrNull() !== u) it += u }
                onTile = m
            }
            val wholeTile = !inPathArea(next)
            holder = map[place]?.firstOrNull { u -> u.steps.any { stepTile(it) == tile && stepLevel(it) == level && (wholeTile || meets(stepPiece(it))) } }
        }
        if (holder == null) for (q in Track.PIECES) {
            if (!meets(q)) continue
            val id = reserved[pieceKey(tile, q, level)] ?: continue
            if (id != t.id) {
                holder = trains.getOrNull(id) ?: return null
                break
            }
        }
        if (holder != null) return if (toward(holder, tile, level, stepEntry(next))) holder else null
        cur = next
        at++
    }
    return null
}

/** Whether [u] heads out of [tile] through [edge]: going by the step it's on there, else its route up to there. */
private fun toward(u: Train, tile: Int, level: Int, edge: Int): Boolean {
    fun here(s: Int) = stepTile(s) == tile && stepLevel(s) == level
    u.steps.lastOrNull { here(it) }?.let { return stepExit(it) == edge }
    val r = u.route ?: return false
    for (k in u.routeAt until r.size) if (here(r[k])) return stepExit(r[k]) == edge
    return false
}

private enum class Stop { NONE, SIGNAL, TRAIN, STATION, DEPOT, END, SNOW }

/**
 * How much grip the rails on [tile] give, in percent: less on wet rail, less
 * again with leaves on it in the fall, on ice and under snow.
 */
fun World.adhesion(tile: Int): Int {
    val snow = weather.snow[tile].toInt() and 255
    val ice = weather.ice[tile].toInt() and 255
    val wet = weather.wet[tile].toInt() and 255
    return when {
        ice > 2 -> 60
        wet >= WET_RAIL && lookToday() == Climate.AUTUMN && leafy(tile) -> 55
        snow > 5 -> 75
        wet >= WET_RAIL -> 80
        else -> 100
    }
}

/** Whether leafy trees grow on [tile] or next to it, to drop their leaves on the line. */
private fun World.leafy(tile: Int): Boolean {
    val x = tile % width
    val y = tile / width
    for (dy in -1..1) for (dx in -1..1) {
        if (!inside(x + dx, y + dy)) continue
        val p = trees[index(x + dx, y + dy)].toInt()
        if (Trees.count(p) > 0 && Trees.kind(p) in Trees.MAPLE..Trees.ASPEN) return true
    }
    return false
}

/** The fastest train [t] can go over the bridge or through the tunnel its nose is on, in speed units: a trestle slowest. */
private fun World.spanLimit(t: Train): Int {
    val s = spanOf(t.steps.last()) ?: spans.getOrNull(spanHeads[stepTile(t.steps.last())] ?: -1) ?: return t.maxSpeed
    return minOf(t.maxSpeed, Spans.SPEED[s.style] * 4096 * 16 / 2700)
}

/** The fastest train [t] can go through the snow on [tile]: its top speed, less in deep snow. */
private fun World.snowLimit(t: Train, tile: Int): Int {
    if (t.plow != Plows.NONE) return t.maxSpeed
    val snow = weather.snow[tile].toInt() and 255
    if (snow < Weather.DEEP) return t.maxSpeed
    return t.maxSpeed * maxOf(25, 100 - (snow - Weather.DEEP) * 2) / 100
}

/**
 * How far the train can go before it has to stand, up to what it needs to brake from full speed, and why it would stop.
 * With [bends] it also works out how fast the bends under the train and on the way to that point let it go.
 */
private fun World.freeAhead(t: Train, brake: Int = BRAKE, bends: CurveScan? = null): Pair<Int, Stop> {
    if (bends == null) return walkAhead(t, brake, null)
    // Two steps behind the tail where the track doesn't branch, so a bend the tail is still on is known for what it
    // was. Where it does, the step before is taken as straight in through the edge it came in at.
    val b1 = soleWayOn(reverseStep(t.steps[0]))
    if (b1 >= 0) {
        val b2 = soleWayOn(b1)
        if (b2 >= 0) bends.add(stepHeading(reverseStep(b2)), CurveScan.BEHIND)
        else bends.add(straightHeading(stepEntry(reverseStep(b1))), CurveScan.BEHIND)
        bends.add(stepHeading(reverseStep(b1)), CurveScan.BEHIND)
    } else bends.add(straightHeading(stepEntry(t.steps[0])), CurveScan.BEHIND)
    for (i in t.steps.indices) bends.add(stepHeading(t.steps[i]), if (i == 0) CurveScan.BEHIND else CurveScan.UNDER)
    val r = walkAhead(t, brake, bends)
    bends.finish()
    return r
}

private fun World.walkAhead(t: Train, brake: Int, bends: CurveScan?): Pair<Int, Stop> {
    val reach = t.maxSpeed.toLong().let { (it * it / (2L * brake) / 16).toInt() } + 16 * FIX
    var cur = t.steps.last()
    var free = stepLength(cur) - t.nose
    var at = t.routeAt
    val target = t.target
    var stop = Stop.NONE
    var stand = 0
    // Leaving a station to call there again, it doesn't stop till it has been off it.
    var left = target < 0 || t.leave != target
    while (stop == Stop.NONE && free < reach) {
        val tile = stepTile(cur)
        stand = free
        if (!left && (stepLevel(cur) != 0 || stationTiles[tile] != target)) left = true
        if (left && target >= 0 && stationTiles[tile] == target && !Order.has(t.orders.getOrNull(t.order % t.orders.size.coerceAtLeast(1)) ?: 0, Order.VIA) && stations.getOrNull(target)?.waypoint != true) {
            val nx = nextFrom(t, cur, at)
            if (nx < 0 || stationTiles[stepTile(nx)] != target) { stop = Stop.STATION; break }
        }
        // Into the depot: it runs on in past the end of the tile until its last car is inside.
        if (depotTiles[tile] == t.shedGoal) { stand = free + t.length; stop = Stop.DEPOT; break }
        val next = nextFrom(t, cur, at)
        val nt = if (next >= 0) stepTile(next) else -1
        stop = when {
            next < 0 -> Stop.END
            stepLevel(next) == 0 && !mayPass(nt, stepPiece(next), stepEntry(next)) -> Stop.END
            t.electric && !wired(next) -> Stop.END
            stepLevel(next) == 0 && closedTile(nt) -> Stop.END
            signalFor(cur, next).let { it >= 0 && !signalLets(t, it, next, at, first = t.speed > 0 && free + t.speed / 16 >= reach) } -> Stop.SIGNAL
            stepLevel(next) == 0 && t.plow != Plows.ROTARY && (weather.snow[nt].toInt() and 255) >= (if (t.plow == Plows.WEDGE) WEDGE_STOPS else Weather.BLOCKING) -> Stop.SNOW
            // On tiles in a path signal's area other trains can be on pieces that don't cross; elsewhere a train anywhere on the tile holds it.
            !pieceFree(t, next, occupiedTile = !inPathArea(next)) -> Stop.TRAIN
            else -> Stop.NONE
        }
        if (stop != Stop.NONE) break
        bends?.add(stepHeading(next), maxOf(free, 0))
        free += stepLength(next)
        cur = next
        at++
    }
    if (stop == Stop.NONE) stand = free
    // Two steps more, past braking reach or the stopping point, so a bend there is known to be tight or gentle.
    if (bends != null) {
        var past = free
        for (i in 0 until 2) {
            val next = nextFrom(t, cur, at)
            if (next < 0) break
            bends.add(stepHeading(next), maxOf(past, 0))
            past += stepLength(next)
            cur = next
            at++
        }
    }
    return stand to stop
}

/** The next step from [cur] when the track doesn't branch, or -1, as [onlyWayOn] but making nothing. */
private fun World.soleWayOn(cur: Int): Int {
    val a = across(stepTile(cur), stepLevel(cur), stepExit(cur))
    if (a < 0) return -1
    val tile = a / 2
    val level = a % 2
    val entry = Track.opposite(stepExit(cur))
    val pieces = piecesAt(tile, level)
    if (pieces == 0) return -1
    if (level == 1) return if (Track.has(pieces, entry)) step(tile, Track.index(pieces), entry, 1) else -1
    depotAt(tile)?.let { d -> return if (d.edge == entry) step(tile, Track.index(Track.straight(entry)), entry) else -1 }
    var found = -1
    for ((i, p) in Track.PIECES.withIndex()) if (pieces and p != 0 && Track.has(p, entry)) {
        if (found >= 0) return -1
        found = step(tile, i, entry)
    }
    return found
}

/** The heading of a straight piece coming in at [entry]. */
private fun straightHeading(entry: Int) = stepHeading(step(0, Track.index(Track.straight(entry)), entry))

/** Step [s] run the other way. */
fun reverseStep(s: Int) = step(stepTile(s), (s shr 2) and 7, stepExit(s), stepLevel(s))

/** Which of the eight ways step [s] runs, from 0 along +x round towards +y, in eighths of a turn. */
fun stepHeading(s: Int): Int {
    val dx = Track.edgeX4(stepExit(s)).compareTo(Track.edgeX4(stepEntry(s)))
    val dy = Track.edgeY4(stepExit(s)).compareTo(Track.edgeY4(stepEntry(s)))
    return HEADINGS[(dx + 1) * 3 + dy + 1]
}

private val HEADINGS = intArrayOf(5, 4, 3, 6, 0, 2, 7, 0, 1)

/**
 * How fast bends let a train go, from the steps under it, tail first, then the steps ahead. Each edge between two steps
 * turns by -2 to 2 eighths. An edge is tight when it's in a run of up to three edges that turns 90 degrees or more,
 * so a 90 made within about a tile of track: a diagonal between straights, two diagonals, or a V of two diagonals.
 * Any other edge that turns is gentle: a single 45, or an S-bend that goes 45 one way and back. A run of diagonals
 * or straights doesn't turn at all. A bend holds the train to its cap from when the nose reaches it till the tail is
 * past it, and ahead it brakes down to the cap in time, so [limit] is the least over every bend in reach.
 */
internal class CurveScan {
    private var speed = 0
    private var brake = 0
    /** The speed the bends allow, and the cap in km/h of the bend that sets it. */
    var limit = Int.MAX_VALUE
        private set
    var kmh = 0
        private set

    private var heading = -1
    private var n = 0
    private val turns = IntArray(3)
    private val dists = IntArray(3)
    private val tight = BooleanArray(3)

    /** Starts again for a train at [speed] braking by [brake]. */
    fun reset(speed: Int, brake: Int): CurveScan {
        this.speed = speed
        this.brake = brake
        limit = Int.MAX_VALUE
        kmh = 0
        heading = -1
        n = 0
        return this
    }

    /** Adds a step heading [h], whose edge with the step before is [dist] ahead of the nose, or [UNDER] or [BEHIND] the train. */
    fun add(h: Int, dist: Int) {
        val last = heading
        heading = h
        if (last < 0) return
        if (n == 3) {
            settle(0)
            turns[0] = turns[1]; dists[0] = dists[1]; tight[0] = tight[1]
            turns[1] = turns[2]; dists[1] = dists[2]; tight[1] = tight[2]
            n = 2
        }
        turns[n] = (h - last + 12) % 8 - 4
        dists[n] = dist
        tight[n] = false
        n++
        // Runs of one, two and three edges ending here.
        var sum = 0
        for (i in n - 1 downTo 0) {
            sum += turns[i]
            if (sum >= 2 || sum <= -2) for (j in i until n) tight[j] = true
        }
    }

    /** Settles the edges still waiting on the ones after them. */
    fun finish() {
        for (i in 0 until n) settle(i)
        n = 0
    }

    private fun settle(i: Int) {
        val k = when {
            tight[i] -> CURVE_TIGHT_KMH
            turns[i] != 0 -> CURVE_GENTLE_KMH
            else -> return
        }
        val c = k * 4096 * 16 / 2700
        val d = dists[i]
        if (d == BEHIND) return
        val v = if (d == UNDER) maxOf(c, speed - brake) else isqrt(c.toLong() * c + 2L * brake * 16 * d).toInt()
        if (v < limit) {
            limit = v
            kmh = k
        }
    }

    companion object {
        const val UNDER = -1
        const val BEHIND = -2
    }
}

/**
 * The cap in km/h of each edge along [steps] by [CurveScan]'s rule, at the place of the step after the edge, 0 where
 * it doesn't turn; for working out ahead of time how bends slow a line.
 */
fun bendCaps(steps: IntArray): IntArray {
    val n = steps.size
    val caps = IntArray(n)
    if (n < 2) return caps
    val turns = IntArray(n)
    for (i in 1 until n) turns[i] = (stepHeading(steps[i]) - stepHeading(steps[i - 1]) + 12) % 8 - 4
    val tight = BooleanArray(n)
    for (i in 1 until n) {
        var sum = 0
        for (j in i until minOf(n, i + 3)) {
            sum += turns[j]
            if (sum >= 2 || sum <= -2) for (k in i..j) tight[k] = true
        }
    }
    for (i in 1 until n) caps[i] = if (tight[i]) CURVE_TIGHT_KMH else if (turns[i] != 0) CURVE_GENTLE_KMH else 0
    return caps
}

/** How fast a train takes a tight bend and a gentle one, in km/h. */
const val CURVE_TIGHT_KMH = 60
const val CURVE_GENTLE_KMH = 100

/** The step after [cur]: the route's, if it has one there, else where plain track leads with no choice to make. */
private fun World.nextFrom(t: Train, cur: Int, at: Int): Int {
    val r = t.route
    // Standing aside, it stops where its way into the loop or siding ends.
    if (t.aside >= 0 && (r == null || at >= r.size)) return -1
    val next = if (r != null && at < r.size) r[at] else onlyWayOn(cur)
    // Without sharing, another company's track is the end of the line.
    if (!settings.sharing && next >= 0 && owner[stepTile(next)].toInt() != t.owner) return -1
    return next
}

/** The next step from [cur] when the track doesn't branch, or -1. */
fun World.onlyWayOn(cur: Int): Int {
    val ways = waysOn(cur)
    return if (ways.size == 1) ways[0] else -1
}

/** The steps a train coming into [tile] at [entry] could take there. */
fun World.waysIn(tile: Int, entry: Int, level: Int = 0): IntArray {
    val pieces = piecesAt(tile, level)
    if (pieces == 0) return IntArray(0)
    if (level == 1) return if (Track.has(pieces, entry)) intArrayOf(step(tile, Track.index(pieces), entry, 1)) else IntArray(0)
    depotAt(tile)?.let { d -> return if (d.edge == entry) intArrayOf(step(tile, Track.index(Track.straight(entry)), entry)) else IntArray(0) }
    val out = ArrayList<Int>(2)
    for ((i, p) in Track.PIECES.withIndex()) if (pieces and p != 0 && Track.has(p, entry)) out += step(tile, i, entry)
    return out.toIntArray()
}

/** The signal that a train going from step [cur] to [next] comes to, packed, or -1. */
internal fun World.signalFor(cur: Int, next: Int): Int {
    val mine = if (stepLevel(cur) == 0) signalAt(stepTile(cur), stepPiece(cur)) else -1
    if (mine >= 0 && Signals.facing(mine) == stepExit(cur) && !Signals.oneWay(mine)) return mine
    val theirs = if (stepLevel(next) == 0) signalAt(stepTile(next), stepPiece(next)) else -1
    return if (theirs >= 0 && Signals.facing(theirs) == stepEntry(next)) theirs else -1
}

/**
 * Whether [signal] lets train [t] on into step [next] (place [at] in its route): a path signal if the
 * way through to the next signal is clear and can be held; a block signal if the block ahead is clear,
 * and for an entry or combo signal a way out of it too.
 */
private fun World.signalLets(t: Train, signal: Int, next: Int, at: Int, first: Boolean = false): Boolean {
    if (Signals.path(signal)) {
        if (reserved[stepKey(next)] == t.id) return true
        // Near its goal it chooses a way it can hold now on the tick the signal comes into reach ([first]), then
        // every CHOOSE_RETRY ticks, trying the way it has in between.
        if (mayChoose(t, next, at, first)) chooseFrom(t, next, at)
        return reserveThrough(t, next, at)
    }
    val b = blocks[stepKey(next)] ?: return true
    if (!blockClear(t, b, Signals.kind(signal), 0)) return false
    // Let in near its goal, it chooses the way behind green signals on the tick the signal comes into reach, then
    // every CHOOSE_RETRY ticks.
    if (mayChoose(t, next, at, first)) chooseFrom(t, next, at, holding = false)
    return true
}

/**
 * Whether [t] looks for a way at the signal before step [next] now: it chooses there ([wayLeft]), on the tick the
 * signal comes into reach ([first]) or every CHOOSE_RETRY ticks, and once a tick at most however often the way ahead
 * is looked over. Marks the tick if it does.
 */
private fun World.mayChoose(t: Train, next: Int, at: Int, first: Boolean): Boolean {
    if (t.choseAt == tick || !first && (tick + t.id) % CHOOSE_RETRY != 0L || wayLeft(t, next, at) < 0) return false
    t.choseAt = tick
    return true
}

/** Whether block [b] has no train but [t] in it and, for an entry or combo signal ([kind]) into it, a clear way out. */
internal fun World.blockClear(t: Train, b: Int, kind: Int, depth: Int): Boolean {
    if ((blockTrains[b] ?: 0) - (if (b in t.onBlocks) 1 else 0) > 0) return false
    if ((kind != Signals.ENTRY && kind != Signals.COMBO) || depth > MAX_PRESIGNALS) return true
    val outs = exits[b].orEmpty().filter { it[0] == Signals.EXIT || it[0] == Signals.COMBO }
    return outs.isEmpty() || outs.any { blockClear(t, it[1], it[0], depth + 1) }
}

/**
 * Whether step [s]'s piece is free for train [t]: neither it nor a piece crossing it held or run on by another train,
 * nor, if [occupiedTile], the tile. A path signal [reserving] the way always looks for trains on it.
 */
internal fun World.pieceFree(t: Train, s: Int, occupiedTile: Boolean, reserving: Boolean = false): Boolean {
    val tile = stepTile(s)
    val level = stepLevel(s)
    val piece = stepPiece(s)
    val place = tileKey(s)
    // With collisions on, a train that came in past no signal runs on into whatever's ahead, a wreck aside.
    val sees = !settings.collisions || t.signalled || reserving
    if (sees && occupiedTile && (tileTrains[place] ?: 0) - (if (place in t.onTiles) 1 else 0) > 0) return false
    val pieces = piecesAt(tile, level)
    var wrecks: Boolean? = null
    for (q in Track.PIECES) {
        if (pieces and q == 0 || (q != piece && !Track.touch(q, piece))) continue
        val key = pieceKey(tile, q, level)
        val holder = reserved[key]
        if (holder != null && holder != t.id) return false
        if ((pieceTrains[key] ?: 0) - (if (key in t.onPieces) 1 else 0) > 0) {
            if (sees) return false
            if (wrecks ?: anyWreck().also { wrecks = it }) for (u in trains) if (u != null && u.wreckedFor > 0 && key in u.onPieces) return false
        }
    }
    return true
}

/** Whether step [s] runs where trains hold paths: on a piece some train has reserved, or one with a path signal. */
private fun World.inPathArea(s: Int): Boolean {
    val tile = stepTile(s)
    for (q in Track.PIECES) {
        val key = pieceKey(tile, q, stepLevel(s))
        if (reserved.containsKey(key) || signals[key]?.let { Signals.path(it) } == true) return true
    }
    return false
}

/**
 * Holds the way from step [first] (place [at] in [t]'s route) through to the next signal, the end of its
 * platform or the end of its route, if every piece of it is free. True if it's held, by this call or before.
 */
private fun World.reserveThrough(t: Train, first: Int, at: Int): Boolean {
    if (reserved[stepKey(first)] == t.id) return true
    val way = ArrayList<Int>()
    var s = first
    var i = at
    while (way.size < MAX_PATH) {
        if (!pieceFree(t, s, occupiedTile = false, reserving = true)) return false
        way += s
        val target = t.target
        val n = nextFrom(t, s, i + 1)
        if (n < 0 || signalFor(s, n) >= 0) break
        if (target >= 0 && stationTiles[stepTile(s)] == target && stationTiles[stepTile(n)] != target) break
        s = n
        i++
    }
    for (w in way) reserved[stepKey(w)] = t.id
    return true
}

/** A train running onto [tile] of another company's track pays that company for it, out of its own profit. */
private fun World.shareFee(t: Train, tile: Int) {
    val o = owner[tile].toInt()
    if (o < 0 || o == t.owner) return
    val payer = companies.getOrNull(t.owner) ?: return
    val owner = companies.getOrNull(o)?.takeIf { !it.gone } ?: return
    val fee = price(SHARE_FEE)
    spend(payer, Books.RUNNING, fee)
    earn(owner, Books.INCOME, fee)
    t.profitThisYear -= fee
}

/** What a train pays another company for each tile of its track it runs onto, before inflation. */
const val SHARE_FEE = 3L

/** Lets go of what train [t] holds ahead of it, keeping only the pieces it stands on. */
internal fun World.releaseAhead(t: Train) {
    val under = t.steps.map { stepKey(it) }.toSet()
    reserved.entries.removeAll { it.value == t.id && it.key !in under }
}

/** Moves the nose on by [move], no further than [free], taking steps from the route as it goes. */
private fun World.advance(t: Train, move: Int, free: Int) {
    if (move <= 0) return
    t.nose += minOf(move, free)
    while (t.nose > stepLength(t.steps.last())) {
        // Running into its depot, the nose goes on past the end of the tile, into the shed.
        if (depotTiles[stepTile(t.steps.last())] == t.shedGoal) break
        val next = nextFrom(t, t.steps.last(), t.routeAt)
        if (next < 0) {
            t.nose = stepLength(t.steps.last())
            break
        }
        val cur = t.steps.last()
        t.nose -= stepLength(cur)
        // Past a signal it's kept apart from other trains; into another block past none, it isn't.
        if (signalFor(cur, next) >= 0) t.signalled = true
        else if (blocks[stepKey(next)] != blocks[stepKey(cur)]) t.signalled = false
        t.steps += next
        shareFee(t, stepTile(next))
        // The engine's pilot pushes the snow off the line as it goes. Snow falls by the calendar, so without a plow it's one pass
        // in the square root of day length: in between one in day length, which would leave more snow on the line at long day lengths, and all.
        val tile = stepTile(next)
        val snow = weather.snow[tile].toInt() and 255
        val pilot = t.plow != Plows.NONE || snow <= PLOWED || settings.dayLength == 1 || rng.below(isqrt(settings.dayLength.toLong()).toInt()) == 0
        if (stepLevel(next) == 0 && snow > 0 && pilot) weather.snow[tile] = when (t.plow) {
            Plows.ROTARY -> 0
            Plows.WEDGE -> if (snow <= WEDGE_CLEARS) minOf(snow, 3) else snow - WEDGE_CLEARS
            else -> if (snow > PLOWED) PLOWED + (snow - PLOWED) / 2 else snow
        }.toByte()
        if (stepLevel(next) == 0) wearBy(tile, t.massKg())
        if (t.route != null) t.routeAt++
    }
    // Lets go of the steps wholly behind the last wagon.
    val tail = t.behind(t.length)
    val keep = (tail shr 32).toInt()
    if (keep > 0) repeat(keep) {
        val gone = t.steps.removeAt(0)
        val key = stepKey(gone)
        if (reserved[key] == t.id && t.steps.none { stepKey(it) == key }) reserved.remove(key)
    }
    countOn(t)
}

/** Turns the train round where it stands: the last wagon leads. */
private fun World.turnRound(t: Train) {
    t.turnRound = false
    val tail = t.behind(t.length)
    val i = (tail shr 32).toInt()
    if (i < 0) return
    val along = (tail and 0xffffffffL).toInt()
    val kept = t.steps.subList(i, t.steps.size).map { s -> step(stepTile(s), Track.index(stepPiece(s)), stepExit(s), stepLevel(s)) }.reversed()
    t.steps.clear()
    t.steps += kept
    t.nose = stepLength(t.steps.last()) - along
    t.route = null
    t.turnIn = -1
    releaseAhead(t)
    countOn(t)
}

/** Whether some of [t] is still inside the depot it's coming out of, its own or one it turned in, so it stands in the door. */
private fun World.atDoor(t: Train) =
    t.steps.isNotEmpty() && stepLevel(t.steps[0]) == 0 && depotTiles.containsKey(stepTile(t.steps[0])) && (t.behind(t.length) shr 32) < 0

/** Whether [t], going in, stands wholly inside the shed of the depot it's going to or turning in, out of sight. */
private fun World.inShed(t: Train) =
    t.steps.isNotEmpty() && depotTiles[stepTile(t.steps.last())] == t.shedGoal && freeAhead(t).let { it.second == Stop.DEPOT && it.first <= 0 }

/**
 * Puts [t], stood in its depot's door, back inside, running or not as it was, so it's listed there and doesn't block
 * the door. A trip to the depot ends there, serviced if that's what it was for.
 */
private fun World.backIn(t: Train) {
    val running = t.running
    // Back into a depot it turned in, it comes out of that one again.
    val left = depotTiles[stepTile(t.steps[0])]
    t.turnIn = if (left == null || left == t.depot) -1 else left
    t.inDepot = true
    t.toDepot = false
    t.steps.clear()
    t.route = null
    t.lostUntil = 0
    reserved.entries.removeAll { it.value == t.id }
    countOn(t)
    if (t.servicing) {
        autorenew(t)
        serviced(t)
    }
    t.running = running
}

/**
 * Puts [t], stood wholly in the shed of the depot it was going to, inside, where it lives after. Sent in or off for a
 * service it stops there, a service then sending it on. On a depot order it's serviced and goes on to its next order,
 * set going again unless told to stop there.
 */
private fun World.enterDepot(t: Train) {
    if (t.turnIn >= 0) {
        turnInDepot(t)
        return
    }
    val d = depots.getOrNull(t.goalDepot)
    if (d == null || depotTiles[stepTile(t.steps.last())] != d.id) return
    val o = if (t.toDepot) null else t.current?.takeIf { Order.depot(it) == d.id }
    t.depot = d.id
    t.inDepot = true
    t.running = false
    t.toDepot = false
    t.steps.clear()
    t.route = null
    t.lostUntil = 0
    reserved.entries.removeAll { it.value == t.id }
    countOn(t)
    if (t.servicing || o != null) {
        autorenew(t)
        serviced(t)
    }
    if (o != null) {
        if (Order.depotKind(o) == Order.DEPOT_STOP) t.running = false
        nextOrder(t)
    }
}

/**
 * Puts [t], stood wholly in the shed of the depot it turns in, inside, still running, serviced if it's due. It comes
 * out again by [leaveDepot], planning afresh.
 */
private fun World.turnInDepot(t: Train) {
    val d = depots.getOrNull(t.turnIn)
    if (d == null || depotTiles[stepTile(t.steps.last())] != d.id) return
    t.inDepot = true
    t.steps.clear()
    t.route = null
    t.lostUntil = 0
    reserved.entries.removeAll { it.value == t.id }
    countOn(t)
    if (t.servicing || due(t.service, t.serviced)) {
        autorenew(t)
        serviced(t)
    }
}

/** Skips [t]'s order if it's to be serviced at a depot and isn't due; true if it did. */
private fun World.skipService(t: Train): Boolean {
    val o = t.current ?: return false
    if (!Order.isDepot(o) || Order.depotKind(o) != Order.DEPOT_SERVICE || due(t.service, t.serviced)) return false
    val was = t.order
    nextOrder(t)
    return t.order != was
}

/**
 * Lets go of [t]'s route, and of the depot it was to turn in unless it's in that depot or its nose is in the door,
 * as it plans afresh for where it's going.
 */
internal fun World.dropRoute(t: Train) {
    t.route = null
    if (t.turnIn < 0 || t.inDepot) return
    val nose = t.steps.lastOrNull()
    if (nose == null || stepLevel(nose) != 0 || depotTiles[stepTile(nose)] != t.turnIn) t.turnIn = -1
}

/** Whether every one of [t]'s orders is a depot service it isn't due, so it has nowhere to go till one is. */
private fun World.nothingDue(t: Train) =
    !t.toDepot && t.orders.isNotEmpty() && !due(t.service, t.serviced) && t.orders.all { Order.isDepot(it) && Order.depotKind(it) == Order.DEPOT_SERVICE }

/** Whether electric train [t]'s order now is to a depot with no wires over it, so it can't get there. */
private fun World.unwiredOrder(t: Train): Boolean {
    if (!t.electric || t.toDepot) return false
    val o = t.current ?: return false
    if (!Order.isDepot(o)) return false
    return depots.getOrNull(Order.depot(o))?.let { !depotWired(it) } == true
}

/** Counts the train afresh on the tiles and blocks it's on. */
internal fun World.countOn(t: Train) {
    for (tile in t.onTiles) tileTrains[tile] = (tileTrains[tile] ?: 1) - 1
    for (p in t.onPieces) pieceTrains[p] = (pieceTrains[p] ?: 1) - 1
    for (b in t.onBlocks) blockTrains[b] = (blockTrains[b] ?: 1) - 1
    if (t.inDepot) {
        t.onTiles = IntArray(0)
        t.onPieces = IntArray(0)
        t.onBlocks = IntArray(0)
        return
    }
    t.onTiles = t.steps.map { tileKey(it) }.distinct().toIntArray()
    t.onPieces = t.steps.map { stepKey(it) }.distinct().toIntArray()
    t.onBlocks = t.steps.mapNotNull { blocks[stepKey(it)] }.distinct().toIntArray()
    for (tile in t.onTiles) tileTrains[tile] = (tileTrains[tile] ?: 0) + 1
    for (p in t.onPieces) pieceTrains[p] = (pieceTrains[p] ?: 0) + 1
    for (b in t.onBlocks) blockTrains[b] = (blockTrains[b] ?: 0) + 1
}

/** Running costs, taken every day: a 365th of a year's, the remainders carried, so a leap year costs a day more. */
internal fun World.payRunningCosts() {
    for (t in trains) {
        if (t == null || t.inDepot && !t.running || t.wreckedFor > 0) continue
        val c = companies.getOrNull(t.owner) ?: continue
        val yearly = price(t.carEngines.sumOf { roster[it].running })
        c.runningOwed += yearly
        val today = c.runningOwed / 365
        spend(c, Books.RUNNING, today)
        t.profitThisYear -= today
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

/**
 * On to the train's next order, leaving out those to skip when there's nothing to do there, and depot orders to be
 * serviced when it isn't due.
 */
internal fun World.nextOrder(t: Train) {
    val n = t.orders.size
    if (n == 0) return
    repeat(n) {
        t.order = (t.order + 1) % n
        val o = t.orders[t.order]
        if (Order.isDepot(o)) {
            if (Order.depotKind(o) != Order.DEPOT_SERVICE || due(t.service, t.serviced)) return
        } else if (!Order.has(o, Order.SKIP_IF_NOTHING) || somethingAt(t, Order.station(o))) return
    }
}

/** Whether [t] has something to set down at station [id], or something waiting there it could carry. */
internal fun World.somethingAt(t: Carrier, id: Int): Boolean {
    val s = stations.getOrNull(id) ?: return false
    for (c in 0 until Cargo.COUNT) {
        if (t.cargo[c].any { it.dest == id || (it.dest == -1 && it.origin != id && accepts(s, c)) }) return true
        if (t.capacity(c) > 0 && s.waiting(c) > 0) return true
    }
    return false
}

/** How often a train waiting to fill up looks for more cargo, in ticks. */
const val FULL_LOAD_RETRY = Calendar.TICKS_PER_DAY / 2

/** Ground this wet, in mm, leaves the rails wet. */
const val WET_RAIL = 40
/** Snow, in cm, a wedge plow clears outright, and a drift it has to stop and push through. */
const val WEDGE_CLEARS = 100
const val WEDGE_STOPS = 160

/** Snow, in cm, a passing train leaves on the line, halving what's over it. */
const val PLOWED = 10
/** Ticks a train stuck in a drift takes to push through each cm of it. */
const val RAM_TICKS = 1L

/** The furthest through a path signal's area a train holds its way, in steps, and entry and exit signals chained. */
const val MAX_PATH = 128
const val MAX_PRESIGNALS = 4
/** How often a standing train looks for one coming the other way, and how long it waits for it, in ticks: a work day. */
const val HEAD_ON_CHECK = 8L
const val HEAD_ON_WAIT = Calendar.TICKS_PER_DAY
/** The longest a train spends backing aside for one coming the other way and standing there, in ticks: 8 work days. */
const val ASIDE_WAIT = 8 * Calendar.TICKS_PER_DAY
/** Ticks a train waits at a path signal before looking for another way, to a free platform say. */
const val PATH_RETRY = 128L

/** How often a train standing at a path signal near its goal looks again for a way it can hold, in ticks. */
const val CHOOSE_RETRY = 16L

/** How hard trains brake, in speed units a tick. */
const val BRAKE = 40
const val MAX_WAGONS = 12
/** Ticks before a train with no way to go tries again. */
const val LOST_RETRY = 64L
