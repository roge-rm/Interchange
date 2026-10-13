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
    /** The train a car belongs to, or -1 for a road vehicle. */
    fun train(i: Int) = if (key(i) >= 0) key(i) / 256 else -1
    /** The road vehicle, ship or aircraft it is, or -1. */
    fun roadVehicle(i: Int) = craft(key(i), ROAD)
    fun ship(i: Int) = craft(key(i), SHIP)
    fun aircraft(i: Int) = craft(key(i), AIRCRAFT)
    /** Up in the air: drawn from its flying sprites, with a shadow on the ground. */
    fun flying(i: Int) = slope(i) == FLYING

    companion object {
        const val CAR_FIELDS = 8
        /** What a key below zero is: [ROAD], [SHIP] or [AIRCRAFT] in its low byte, the id above. */
        const val ROAD = 0
        const val SHIP = 1
        const val AIRCRAFT = 2
        /** A slope that means flying. */
        const val FLYING = 2

        fun key(kind: Int, id: Int) = -((id + 1) * 256 + kind)
        /** The id in [key] if it's of [kind], else -1. */
        fun craft(key: Int, kind: Int): Int {
            if (key >= 0) return -1
            val n = -key
            return if (n % 256 == kind) n / 256 - 1 else -1
        }
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
    /** An electric engine in a depot with no wires, so it can't leave. */
    val needsWires: Boolean = false,
)

/** A bus, truck or streetcar as the screen shows it. */
class RoadVehicleView(
    val id: Int,
    val name: String,
    val model: Int,
    val depot: Int,
    val inDepot: Boolean,
    val running: Boolean,
    val toDepot: Boolean,
    val orders: IntArray,
    val order: Int,
    val kmh: Int,
    val waiting: Boolean,
    val lost: Boolean,
    val carries: Int,
    val load: Int,
    val capacity: Int,
    val profitThisYear: Long,
    val profitLastYear: Long,
    val age: Int,
    val reliability: Int,
    val broken: Boolean,
    val slid: Boolean,
    val service: Boolean,
    val servicing: Boolean,
    val tires: Int,
    val seasonalTires: Boolean,
    val renewCost: Long,
)

/** A ship as the screen shows it. */
class ShipView(
    val id: Int,
    val name: String,
    val model: Int,
    val depot: Int,
    val inDepot: Boolean,
    val running: Boolean,
    val toDepot: Boolean,
    val orders: IntArray,
    val order: Int,
    val kmh: Int,
    val waiting: Boolean,
    val lost: Boolean,
    val carries: Int,
    val load: Int,
    val capacity: Int,
    val profitThisYear: Long,
    val profitLastYear: Long,
    val age: Int,
    val reliability: Int,
    val broken: Boolean,
    val service: Boolean,
    val servicing: Boolean,
    val renewCost: Long,
    /** Held up by ice it can't break, or in port for a storm. */
    val iced: Boolean,
    val stormbound: Boolean,
)

/** An aircraft as the screen shows it. */
class AircraftView(
    val id: Int,
    val name: String,
    val model: Int,
    val depot: Int,
    val inDepot: Boolean,
    val running: Boolean,
    val toDepot: Boolean,
    val orders: IntArray,
    val order: Int,
    val kmh: Int,
    val state: Int,
    val waiting: Boolean,
    val carries: Int,
    val load: Int,
    val capacity: Int,
    val profitThisYear: Long,
    val profitLastYear: Long,
    val age: Int,
    val reliability: Int,
    val broken: Boolean,
    val service: Boolean,
    val servicing: Boolean,
    val renewCost: Long,
    val gear: Int,
    val seasonalGear: Boolean,
    /** Why it's holding, as [cantLand] says, or null. */
    val holdingFor: String?,
)

/** An airport as the screen shows it. */
class AirportView(val id: Int, val station: Int, val kind: Int, val x: Int, val y: Int, val w: Int, val h: Int, val axis: Int, val depot: Int, val ils: Boolean, val deice: Boolean)

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
    /** The company it's for, and the middle of its tiles on the map, for its sign. */
    val owner: Int = 0,
    val x: Float = 0f,
    val y: Float = 0f,
    val waypoint: Boolean = false,
)

/** Passengers and mail waiting at a station for [dest] (-1 for anywhere), changing at [via] on the way or -1 if it's the next stop. */
class GoingView(val dest: Int, val via: Int, val counts: IntArray)

/** A depot as the screen shows it, to draw over the trains going in and out of it. */
class DepotView(val tile: Int, val edge: Int, val id: Int = -1, val wired: Boolean = false)

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
    /** How much it spends keeping its track up, an [Upkeep] level. */
    val upkeep: Int = Upkeep.NORMAL,
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
    /** Snow sheds and snow fences, by tile. */
    val sheds: IntArray = IntArray(0),
    val fences: IntArray = IntArray(0),
    val roadVehicles: List<RoadVehicleView> = emptyList(),
    val ships: List<ShipView> = emptyList(),
    val aircraft: List<AircraftView> = emptyList(),
    val airports: List<AirportView> = emptyList(),
    /** Buoys, by tile. */
    val buoys: IntArray = IntArray(0),
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
    // Road vehicles, each a single car, keyed below zero.
    for (v in roadVehicles) {
        if (v == null || v.inDepot) continue
        val p = roadPosition(v)
        val tile = roadStepTile(v.at)
        val x = tile % width
        val y = tile / width
        val rise = edgeHeight(x, y, 0, roadStepExit(v.at)) - edgeHeight(x, y, 0, roadStepEntry(v.at))
        cars += VehicleView.key(VehicleView.ROAD, v.id).toFloat()
        cars += p[0]
        cars += p[1]
        cars += p[2]
        cars += v.info.sprite.toFloat()
        cars += rise.coerceIn(-1, 1).toFloat()
        cars += v.info.length / 16f
        cars += -1f
    }
    for (v in ships) {
        if (v == null || v.inDepot) continue
        cars += VehicleView.key(VehicleView.SHIP, v.id).toFloat()
        cars += v.x / SHIP_UNIT.toFloat()
        cars += v.y / SHIP_UNIT.toFloat()
        cars += v.heading.toFloat()
        cars += v.info.sprite.toFloat()
        cars += 0f
        cars += v.info.length / 16f
        cars += -1f
    }
    for (v in aircraft) {
        if (v == null || v.inDepot) continue
        cars += VehicleView.key(VehicleView.AIRCRAFT, v.id).toFloat()
        cars += v.x / SHIP_UNIT.toFloat()
        cars += v.y / SHIP_UNIT.toFloat()
        cars += v.heading.toFloat()
        cars += v.info.sprite.toFloat()
        cars += if (v.flying) VehicleView.FLYING.toFloat() else 0f
        cars += v.info.length / 16f
        cars += if (v.flying) v.altitude / 16f else -1f
    }
    val trainViews = trains.filterNotNull().map { t ->
        TrainView(
            t.id, t.name, t.engine, t.wagon, t.wagons, t.depot, t.inDepot, t.running, t.toDepot,
            t.orders.toIntArray(), t.order, t.speed / 16 * 2700 / 4096, t.waiting > 0, t.running && !t.inDepot && t.lostUntil > tick,
            IntArray(Cargo.COUNT) { t.load(it) }, IntArray(Cargo.COUNT) { t.capacity(it) }, t.profitThisYear, t.profitLastYear, t.shared,
            age(t), t.reliability / 100, t.brokenFor > 0, t.breakdowns, t.service, t.servicing, price(t.price) - value(t), age(t) >= t.engineInfo.life,
            t.inDepot && t.engineInfo.traction == Traction.ELECTRIC && depots.getOrNull(t.depot)?.let { !depotWired(it) } == true,
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
        val places = s.places
        val mx = if (places.isEmpty()) s.signX + 0.5f else places.sumOf { it % width } / places.size.toFloat() + 0.5f
        val my = if (places.isEmpty()) s.signY + 0.5f else places.sumOf { it / width } / places.size.toFloat() + 0.5f
        StationView(s.id, s.name, s.tiles.size, IntArray(Cargo.COUNT) { s.waiting(it) }, list, s.rating.copyOf(), served, s.owner, mx, my, s.waypoint)
    }
    val signalViews = signals.entries.sortedBy { it.key }.map { (k, s) ->
        val tile = k / 8
        val piece = Track.PIECES[k % 8]
        // Red while a train is in the block the signal guards: the one its piece is in.
        SignalView(tile, piece, Signals.facing(s), signalRed(tile, piece, s), Signals.kind(s))
    }
    // Only engine houses are drawn over what goes in and out of them.
    val depotViews = depots.filterNotNull().filter { it.kind == Depots.RAIL }.map { DepotView(it.tile, it.edge, it.id, depotWired(it)) }
    // Towns change seldom, so their list is only made again when one has.
    if (townsChanged) {
        townViews = towns.map { TownView(it.id, it.name, it.x, it.y, it.population, it.houses) }
        townsChanged = false
    }
    return WorldView(VehicleView(tick, cars.toFloatArray()), trainViews, stationViews, signalViews, depotViews, townViews, payments.filter { it.company == 0 }, news.toList(),
        companies.firstOrNull()?.let { c -> CompanyView(c.money, c.loan, maxLoan(), c.thisYear.copyOf(), c.lastYear.copyOf(), c.history.map { it.copyOf() }, c.upkeep) }, tick, weatherView(),
        stations.filterNotNull().filter { it.waypoint }.flatMap { it.tiles }.toIntArray(),
        spans.filterNotNull().map { SpanView(it.tunnel, it.style, it.x0, it.y0, it.x1, it.y1, it.height) },
        sheds.toIntArray(), fences.toIntArray(),
        roadVehicles.filterNotNull().map { v ->
            val c = v.carries
            RoadVehicleView(
                v.id, v.name, v.model, v.depot, v.inDepot, v.running, v.toDepot, v.orders.toIntArray(), v.order, v.speed / 16 * 2700 / 4096,
                v.waiting > 0, v.running && !v.inDepot && v.lostUntil > tick, c, if (c >= 0) v.load(c) else 0, if (c >= 0) v.capacity(c) else 0,
                v.profitThisYear, v.profitLastYear, (day - v.built).coerceAtLeast(0) / 365, v.reliability / 100, v.brokenFor > 0 && !v.slid, v.slid,
                v.service, v.servicing, v.tires, v.seasonalTires, price(v.price) - roadValue(v),
            )
        },
        ships.filterNotNull().map { v ->
            val c = v.carries
            val r = v.route
            val iced = !v.inDepot && v.running && r == null && v.lostUntil > tick
            ShipView(
                v.id, v.name, v.model, v.depot, v.inDepot, v.running, v.toDepot, v.orders.toIntArray(), v.order, v.speed / 16 * 2700 / 4096,
                v.waiting > 0, iced, c, if (c >= 0) v.load(c) else 0, if (c >= 0) v.capacity(c) else 0,
                v.profitThisYear, v.profitLastYear, (day - v.built).coerceAtLeast(0) / 365, v.reliability / 100, v.brokenFor > 0,
                v.service, v.servicing, price(v.price) - craftValue(v.price, v.built, v.info.life),
                iced && (weather.ice[shipTile(v)].toInt() and 255) > 0, v.waiting == 1 && weather.wind >= STORM_WIND,
            )
        },
        aircraft.filterNotNull().map { v ->
            val c = v.carries
            val dest = if (v.target >= 0) airportOf(v.target) else null
            AircraftView(
                v.id, v.name, v.model, v.depot, v.inDepot, v.running, v.toDepot, v.orders.toIntArray(), v.order, v.speed / 16 * 2700 / 4096,
                v.state, v.waiting > 0, c, if (c >= 0) v.load(c) else 0, if (c >= 0) v.capacity(c) else 0,
                v.profitThisYear, v.profitLastYear, (day - v.built).coerceAtLeast(0) / 365, v.reliability / 100, v.brokenFor > 0,
                v.service, v.servicing, price(v.price) - craftValue(v.price, v.built, v.info.life), v.gear, v.seasonalGear,
                if (v.state == Flight.HOLD && dest != null) cantLand(v, dest) else null,
            )
        },
        airports.filterNotNull().map { AirportView(it.id, it.station, it.kind, it.x, it.y, it.w, it.h, it.axis, it.depot, it.ils, it.deice) },
        buoys.keys.sorted().toIntArray(),
    )
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
