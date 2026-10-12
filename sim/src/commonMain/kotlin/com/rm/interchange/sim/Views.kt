package com.rm.interchange.sim

/*
 * What the screen needs from the world each tick, copied out on the game
 * thread so the screen never reads the game's own lists while they change.
 * Floats are fine here: they're only for drawing, never fed back into the game.
 */

/** Where every wagon is: [CAR_FIELDS] floats each. */
class VehicleView(val tick: Long, val cars: FloatArray) {
    val count get() = cars.size / CAR_FIELDS

    fun key(i: Int) = cars[i * CAR_FIELDS].toInt()
    fun x(i: Int) = cars[i * CAR_FIELDS + 1]
    fun y(i: Int) = cars[i * CAR_FIELDS + 2]
    /** The way it faces in the world, 0 along x, on round towards y, in eighths. */
    fun heading(i: Int) = cars[i * CAR_FIELDS + 3].toInt()
    fun sprite(i: Int) = cars[i * CAR_FIELDS + 4].toInt()
    /** 1 climbing, -1 going down, 0 level. */
    fun slope(i: Int) = cars[i * CAR_FIELDS + 5].toInt()
    /** How long it is, in tiles. */
    fun length(i: Int) = cars[i * CAR_FIELDS + 6]
    /** How high it runs on a bridge, in steps, or -1 on the ground. */
    fun deck(i: Int) = cars[i * CAR_FIELDS + 7]
    fun train(i: Int) = key(i) / 256

    companion object {
        const val CAR_FIELDS = 8
    }
}

/** A train as the screen shows it. */
class TrainView(
    val id: Int,
    val name: String,
    val engine: Int,
    val wagon: Int,
    val wagons: Int,
    val depot: Int,
    val inDepot: Boolean,
    val running: Boolean,
    val toDepot: Boolean,
    val orders: IntArray,
    val order: Int,
    val kmh: Int,
    val waiting: Boolean,
    val lost: Boolean,
    /** What it's carrying and has room for, by [Cargo]. */
    val load: IntArray = IntArray(Cargo.COUNT),
    val capacity: IntArray = IntArray(Cargo.COUNT),
    val profitThisYear: Long = 0,
    val profitLastYear: Long = 0,
    /** The group of trains it shares its orders with, or -1. */
    val shared: Int = -1,
    /** Years old, how reliable in percent, broken down now, and how often it has. */
    val age: Int = 0,
    val reliability: Int = 100,
    val broken: Boolean = false,
    val breakdowns: Int = 0,
    /** Set to be serviced, and on its way for one. */
    val service: Boolean = true,
    val servicing: Boolean = false,
    /** What renewing it costs. */
    val renewCost: Long = 0,
    /** Past its engine's working life. */
    val old: Boolean = false,
)

/** A station as the screen shows it. */
class StationView(
    val id: Int,
    val name: String,
    val platforms: Int,
    val waiting: IntArray = IntArray(Cargo.COUNT),
    /** Where what's waiting is going, the most first. */
    val going: List<GoingView> = emptyList(),
    /** Its rating for each cargo, 0 to 255. */
    val rating: IntArray = IntArray(Cargo.COUNT),
    /** The cargo it deals in, as bits: what's waiting or what a train has called for. */
    val served: Int = 0,
)

/** Passengers and mail waiting at a station for [dest] (-1 for anywhere), changing at [via] on the way or -1 if it's the next stop. */
class GoingView(val dest: Int, val via: Int, val counts: IntArray)

/** A depot as the screen shows it, to draw over the trains going in and out of it. */
class DepotView(val tile: Int, val edge: Int)

/** A signal as the screen shows it: where, which way and whether it shows red. */
class SignalView(val tile: Int, val piece: Int, val facing: Int, val red: Boolean, val kind: Int = Signals.TWO_WAY)

/** A bridge or tunnel as the screen shows it. */
class SpanView(val tunnel: Boolean, val style: Int, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val height: Int)

/** A town as the screen shows it: its name over the map at its middle, and how big it is. */
class TownView(val id: Int, val name: String, val x: Int, val y: Int, val population: Int, val houses: Int)

/** The player's company as the finance window shows it. */
class CompanyView(
    val money: Long,
    val loan: Long,
    val maxLoan: Long,
    val thisYear: LongArray,
    val lastYear: LongArray,
    /** Money, income and spending at the end of each month, oldest first. */
    val history: List<LongArray>,
)

/** The weather as the screen shows it: copies of the fronts, the air and the wind, as of the start of [day]. */
class WeatherView(val day: Int, val year: Int, val doy: Int, val anomaly: Int, val fronts: List<Front>, val windFrom: Int, val wind: Int)

/** Everything the screen draws and lists that moves or changes as the game runs. */
class WorldView(
    val vehicles: VehicleView,
    val trains: List<TrainView>,
    val stations: List<StationView>,
    val signals: List<SignalView>,
    val depots: List<DepotView>,
    val towns: List<TownView> = emptyList(),
    val payments: List<Payment> = emptyList(),
    val news: List<News> = emptyList(),
    val company: CompanyView? = null,
    val tick: Long = 0,
    val weather: WeatherView? = null,
    /** Waypoint tiles. */
    val waypoints: IntArray = IntArray(0),
    val spans: List<SpanView> = emptyList(),
)

fun World.view(): WorldView {
    val cars = ArrayList<Float>()
    for (t in trains) {
        if (t == null || t.inDepot) continue
        for (c in 0 until t.cars) {
            val at = t.behind(t.carMiddle(c))
            val i = (at shr 32).toInt()
            if (i < 0) continue
            val s = t.steps[i]
            val tile = stepTile(s)
            // A car that's run on in past the end of its depot's tile is inside the shed; one in a tunnel is out of sight.
            if ((at and 0xffffffffL).toInt() > stepLength(s) && depotTiles.containsKey(tile)) continue
            val span = spanOf(s)
            if (span != null && span.tunnel) continue
            val along = (at and 0xffffffffL).toFloat() / stepLength(s)
            val a = stepEntry(s)
            val b = stepExit(s)
            val ax = tile % width + Track.edgeX4(a) / 4f
            val ay = tile / width + Track.edgeY4(a) / 4f
            val bx = tile % width + Track.edgeX4(b) / 4f
            val by = tile / width + Track.edgeY4(b) / 4f
            cars += (t.id * 256 + c).toFloat()
            cars += ax + (bx - ax) * along
            cars += ay + (by - ay) * along
            cars += heading(Track.edgeX4(b) - Track.edgeX4(a), Track.edgeY4(b) - Track.edgeY4(a)).toFloat()
            cars += roster[t.carEngines[c]].sprite.toFloat()
            // Climbing or going down: the edge it leaves by against the edge it came in at.
            val pieces = track[tile].toInt()
            val rise = if (span != null) 0 else edgeHeight(tile % width, tile / width, pieces, b) - edgeHeight(tile % width, tile / width, pieces, a)
            cars += rise.coerceIn(-1, 1).toFloat()
            cars += roster[t.carEngines[c]].length / 16f
            cars += span?.height?.toFloat() ?: -1f
        }
    }
    val trainViews = trains.filterNotNull().map { t ->
        TrainView(
            t.id, t.name, t.engine, t.wagon, t.wagons, t.depot, t.inDepot, t.running, t.toDepot,
            t.orders.toIntArray(), t.order, t.speed / 16 * 2700 / 4096, t.waiting > 0, t.running && !t.inDepot && t.lostUntil > tick,
            IntArray(Cargo.COUNT) { t.load(it) }, IntArray(Cargo.COUNT) { t.capacity(it) }, t.profitThisYear, t.profitLastYear, t.shared,
            age(t), t.reliability / 100, t.brokenFor > 0, t.breakdowns, t.service, t.servicing, price(t.price) - value(t), age(t) >= t.engineInfo.life,
        )
    }
    val stationViews = stations.filterNotNull().map { s ->
        val going = HashMap<Int, IntArray>()
        for (c in 0 until Cargo.COUNT) for (p in s.cargo[c]) going.getOrPut(p.dest) { IntArray(Cargo.COUNT) }[c] += p.count
        val list = going.entries.sortedWith(compareByDescending<Map.Entry<Int, IntArray>> { it.value.sum() }.thenBy { it.key }).map { (d, n) ->
            val hop = if (d >= 0) nextHop(s.id, d) else -1
            GoingView(d, if (hop >= 0 && hop != d) hop else -1, n)
        }
        var served = 0
        for (c in 0 until Cargo.COUNT) if (s.lastCall[c] >= 0 || s.waiting(c) > 0) served = served or (1 shl c)
        StationView(s.id, s.name, s.tiles.size, IntArray(Cargo.COUNT) { s.waiting(it) }, list, s.rating.copyOf(), served)
    }
    val signalViews = signals.entries.sortedBy { it.key }.map { (k, s) ->
        val tile = k / 8
        val piece = Track.PIECES[k % 8]
        // Red while a train is in the block the signal guards: the one its piece is in.
        SignalView(tile, piece, Signals.facing(s), signalRed(tile, piece, s), Signals.kind(s))
    }
    val depotViews = depots.filterNotNull().map { DepotView(it.tile, it.edge) }
    // Towns change seldom, so their list is only made again when one has.
    if (townsChanged) {
        townViews = towns.map { TownView(it.id, it.name, it.x, it.y, it.population, it.houses) }
        townsChanged = false
    }
    return WorldView(VehicleView(tick, cars.toFloatArray()), trainViews, stationViews, signalViews, depotViews, townViews, payments.toList(), news.toList(),
        companies.firstOrNull()?.let { c -> CompanyView(c.money, c.loan, maxLoan(), c.thisYear.copyOf(), c.lastYear.copyOf(), c.history.map { it.copyOf() }) }, tick, weatherView(),
        stations.filterNotNull().filter { it.waypoint }.flatMap { it.tiles }.toIntArray(),
        spans.filterNotNull().map { SpanView(it.tunnel, it.style, it.x0, it.y0, it.x1, it.y1, it.height) })
}

/** The weather's copy for the screen, made again only once a day. */
private fun World.weatherView(): WeatherView {
    weatherViewCache?.let { if (it.day == day) return it }
    return WeatherView(
        day, Calendar.year(startYear, day), Calendar.dayOfYear(startYear, day), weather.anomaly, weather.fronts.map { it.copy() }, weather.windFrom, weather.wind,
    ).also { weatherViewCache = it }
}

/** The heading, in eighths from along x towards y, of a step of ([dx], [dy]). */
fun heading(dx: Int, dy: Int): Int {
    val sx = dx.coerceIn(-1, 1)
    val sy = dy.coerceIn(-1, 1)
    return when {
        sx == 1 && sy == 0 -> 0
        sx == 1 && sy == 1 -> 1
        sx == 0 && sy == 1 -> 2
        sx == -1 && sy == 1 -> 3
        sx == -1 && sy == 0 -> 4
        sx == -1 && sy == -1 -> 5
        sx == 0 && sy == -1 -> 6
        else -> 7
    }
}
