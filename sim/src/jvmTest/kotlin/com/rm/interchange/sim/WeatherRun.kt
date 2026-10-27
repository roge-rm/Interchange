package com.rm.interchange.sim

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test

/**
 * What winter, wear and the weather take from reference routes, written to build/balance/weather.md. Each route runs
 * full, one vehicle (one train), on a flat map for the same calendar years at each day length, as:
 * - ideal: no weather, and the vehicle kept at full reliability and never sent for a service;
 * - wear: no weather, with wear, breakdowns and servicing as the game has them;
 * - each region: the weather on, as the game has it;
 * - capped, in the first region: the weather on, but deep snow on the road kept just under [DEEP_ROAD_SNOW] (so the
 *   winter tire cap stays and the deep snow cap goes), or the line kept clear of closures, kinks and slow orders;
 * - salt, in the first region: the weather on, with salt (or sand) depots along the road;
 * - for trains, in the first region: the track on low and high upkeep, and busy, worn as if [BUSY] more trains like
 *   it passed each tile a work day, on each upkeep level.
 * Profit is income less running and upkeep (salt is upkeep). The "bad" line has forest along both sides and a lake
 * beside its middle, for fires and washouts.
 *
 * Slow, so it runs only when asked: `./gradlew :sim:jvmTest --tests '*WeatherRun*' -Pinterchange.weatherYears=4`,
 * and optionally interchange.weatherEras, weatherLengths, weatherRegions, weatherSeeds and weatherModes (route modes
 * starting with these, as train, bus, ship, or busy for the busy road only) as comma lists. The busy road ([busyRun]),
 * several trucks and buses on one road with and without sand, takes weatherBusyEras, weatherBusyRegions and weatherBusySeeds.
 */
class WeatherRun {
    class Route(val mode: String, val key: String, val year: Int, val tiles: Int, val cargo: Int, val engine: Engine, val wagon: Engine?, val wagons: Int, val bad: Boolean = false) {
        val name get() = "$mode ${key.substringAfter(':')}${if (bad) " (bad line)" else ""}, $year, $tiles tiles"
    }

    /** Ideal, wear, weather, capped, salt, or for trains an upkeep level (low, high, busy low, busy, busy high), the rest in a region. */
    class Variant(val kind: String, val region: Region = Region.MIXED) {
        val weather get() = kind != "ideal" && kind != "wear"
        val upkeep get() = if (kind.endsWith("low")) Upkeep.LOW else if (kind.endsWith("high")) Upkeep.HIGH else Upkeep.NORMAL
        val busy get() = kind.startsWith("busy")
        val label get() = if (weather) "$kind ${region.name.lowercase()}" else kind
    }

    class Result(
        val profit: Long, val income: Long, val upkeep: Long, val workYears: Int, val calendarYears: Int,
        /** Calendar days: nothing moved, any slow order on the line, closed, kinked, deep snow on the road, the tire cap, ice past [SHIP_ICE] on the way. */
        val shut: Int, val slow: Int, val closed: Int, val kinked: Int, val deep: Int, val capped: Int, val iced: Int, val breakdowns: Int,
        /** Heat kinks that started on the line, and calendar days with a 40 km/h slow order on it. */
        val kinks: Int = 0, val veryWorn: Int = 0,
    ) {
        val perWorkYear get() = profit / workYears.coerceAtLeast(1)
        fun perYear(days: Int) = days.toDouble() / calendarYears
    }

    private val b = Balance()

    private fun routes(eras: List<Int>): List<Route> {
        val r = Roster.BASE
        val out = ArrayList<Route>()
        val speedCap = { e: Engine -> e.capacity.toLong() * e.speed }
        // Grain before coal, as coal to no industry pays more in the cold for heating.
        val freightCargoes = listOf(Cargo.GRAIN, Cargo.COAL, Cargo.TIMBER, Cargo.GOODS)
        for (year in eras) {
            val engines = b.onSale(r, year, VehicleKind.ENGINE).filter { it.traction != Traction.ELECTRIC }
            val wagons = b.onSale(r, year, VehicleKind.WAGON)
            val engine = engines.maxWithOrNull(compareBy<Engine> { it.power }.thenByDescending { it.cost })
            val freight = freightCargoes.firstNotNullOfOrNull { c -> wagons.filter { c in it.cargoes }.maxByOrNull { it.capacity }?.let { c to it } }
            val coach = wagons.filter { Cargo.PASSENGERS in it.cargoes }.maxByOrNull { it.capacity }
            if (engine != null && freight != null) {
                val n = b.wagonsFor(r, engine, freight.second, freight.first)
                out += Route("train-freight", engine.key, year, 60, freight.first, engine, freight.second, n)
                out += Route("train-freight", engine.key, year, 60, freight.first, engine, freight.second, n, bad = true)
            }
            if (engine != null && coach != null) out += Route("train-pass", engine.key, year, 40, Cargo.PASSENGERS, engine, coach, b.wagonsFor(r, engine, coach, Cargo.PASSENGERS))
            val roads = b.onSale(r, year, VehicleKind.ROAD).filter { !it.tram && it.capacity > 0 && it.cargoes.isNotEmpty() }
            // Horses only pay on short runs.
            val horses = year < 1900
            roads.filter { Cargo.PASSENGERS in it.cargoes }.maxByOrNull(speedCap)?.let { out += Route("bus", it.key, year, if (horses) 12 else 25, Cargo.PASSENGERS, it, null, 0) }
            roads.filter { Cargo.PASSENGERS !in it.cargoes && !it.cargoes.contentEquals(intArrayOf(Cargo.MAIL)) }.maxByOrNull(speedCap)
                ?.let { out += Route("truck", it.key, year, if (horses) 12 else 40, b.freightCargo(it), it, null, 0) }
            val ships = b.onSale(r, year, VehicleKind.SHIP).filter { it.capacity > 0 && it.cargoes.isNotEmpty() }
            ships.filter { Cargo.PASSENGERS !in it.cargoes }.maxByOrNull(speedCap)?.let { out += Route("ship-freight", it.key, year, 60, b.shipCargo(it), it, null, 0) }
            ships.filter { Cargo.PASSENGERS in it.cargoes }.maxByOrNull(speedCap)?.let { out += Route("ship-pass", it.key, year, 12, Cargo.PASSENGERS, it, null, 0) }
            b.onSale(r, year, VehicleKind.AIRCRAFT).filter { it.capacity > 0 && !it.floats && Airports.FROM[b.airKind(it)] <= year && Cargo.PASSENGERS in it.cargoes }
                .maxByOrNull(speedCap)?.let { out += Route("air-pass", it.key, year, 60, Cargo.PASSENGERS, it, null, 0) }
        }
        return out
    }

    private fun variants(mode: String, regions: List<Region>): List<Variant> {
        val out = arrayListOf(Variant("ideal"), Variant("wear"))
        for (r in regions) out += Variant("weather", r)
        if (mode.startsWith("train") || mode == "bus" || mode == "truck") out += Variant("capped", regions.first())
        if (mode.startsWith("train")) for (k in listOf("low", "high", "busy low", "busy", "busy high")) out += Variant(k, regions.first())
        if (mode == "bus" || mode == "truck") out += Variant("salt", regions.first())
        return out
    }

    private fun run(route: Route, v: Variant, dayLength: Int, years: Int, seed: Long): Result? {
        val w = World(NewGame(128, 64, seed, route.year, v.region, towns = 0, industries = 0, weather = v.weather, inflation = false, dayLength = dayLength))
        for (i in w.corners.indices) w.corners[i] = 3
        w.trees.fill(0)
        val c = w.companies[0]
        c.money = 1_000_000_000L
        c.upkeep = v.upkeep
        val len = route.tiles
        val e = route.engine
        val line = ArrayList<Int>()
        val water = ArrayList<Int>()
        when {
            route.mode.startsWith("train") -> {
                if (route.bad) {
                    // Forest two tiles deep each side, and a lake beside the middle third.
                    for (x in 0 until w.width) for (y in listOf(7, 8, 12, 13)) w.trees[w.index(x, y)] = Trees.pack(Trees.SPRUCE, 3).toByte()
                    val from = 10 + len / 3
                    val until = 10 + len * 2 / 3
                    for (y in 12..20) for (x in from..until) w.corners[w.corner(x, y)] = 0
                    w.shoreChanged()
                }
                w.apply(Command.Depot(0, 2, 10, Track.XP))
                w.apply(Command.Rail(0, 3, 10, 10 + len + 4, 10, Track.X, remove = false))
                w.apply(Command.Station(0, 6, 10, 9, 10, Track.X))
                w.apply(Command.Station(0, 6 + len, 10, 9 + len, 10, Track.X))
                w.apply(Command.BuyTrain(0, 0, e.id, route.wagon!!.id, route.wagons, route.cargo))
                if (w.trains.isEmpty()) return null
                w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
                w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
                for (x in 3..10 + len + 4) line += w.index(x, 10)
            }
            route.mode == "bus" || route.mode == "truck" -> {
                val x0 = 10
                val y = 30
                val x1 = x0 + len + 4
                w.apply(Command.Road(0, x0, y, x1, y, Surface.PAVED.coerceAtMost(Surface.entries(route.year).last()), remove = false))
                w.apply(Command.Road(0, x0, y, x0, y + 2, Surface.DIRT, remove = false))
                w.apply(Command.Garage(0, x0, y + 3, Track.YM))
                w.apply(Command.RoadStop(0, x0 + 2, y))
                w.apply(Command.RoadStop(0, x1 - 2, y))
                if (v.kind == "salt") for (x in x0 + 4..x1 step 16) w.apply(Command.SaltDepot(0, x, y - 1))
                w.apply(Command.BuyRoadVehicle(0, 0, e.id, route.cargo))
                if (w.roadVehicles.isEmpty()) return null
                w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
                w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.START_STOP))
                for (x in x0..x1) line += w.index(x, y)
            }
            route.mode.startsWith("ship") -> {
                for (y in 0..64) for (x in 0..128) w.corners[w.corner(x, y)] = if (x in 10..(9 + len) && y in 10..50) 0 else 1
                w.shoreChanged()
                w.apply(Command.Dock(0, 9, 30))
                w.apply(Command.Dock(0, 9 + len, 30))
                w.apply(Command.Shipyard(0, 9, 27))
                if (w.stations.size < 2 || w.depots.isEmpty()) return null
                w.apply(Command.BuyCraft(0, 0, e.id, route.cargo))
                if (w.ships.isEmpty()) return null
                w.apply(Command.CraftOrder(0, false, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
                w.apply(Command.CraftOrder(0, false, 0, Command.TrainOrder.START_STOP))
                for (x in 10..9 + len) water += w.index(x, 30)
            }
            else -> {
                val kind = b.airKind(e)
                w.apply(Command.Airport(0, 5, 30, kind, Track.X))
                w.apply(Command.Airport(0, 5 + len, 30, kind, Track.X))
                if (w.airports.size < 2) return null
                w.apply(Command.BuyCraft(0, w.airports[0]!!.depot, e.id, route.cargo))
                if (w.aircraft.isEmpty()) return null
                w.apply(Command.CraftOrder(0, true, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
                w.apply(Command.CraftOrder(0, true, 0, Command.TrainOrder.START_STOP))
            }
        }
        val both = route.cargo == Cargo.PASSENGERS || route.cargo == Cargo.MAIL
        val total = Calendar.TICKS_PER_DAY * 365L * dayLength * years
        val sums = LongArray(Books.COUNT)
        var year = Calendar.year(w.startYear, w.day)
        var moved = 0L
        var shut = 0; var slow = 0; var closed = 0; var kinked = 0; var deep = 0; var capped = 0; var iced = 0
        var kinks = 0; var veryWorn = 0
        var wasKinked = emptySet<Int>()
        val train = route.mode.startsWith("train")
        val road = route.mode == "bus" || route.mode == "truck"
        while (w.tick < total) {
            if (w.tick % Calendar.TICKS_PER_DAY == 0L) {
                for (s in w.stations) {
                    if (s == null || (!both && s.id != 0)) continue
                    s.cargo[route.cargo].clear()
                    w.addWaiting(s, route.cargo, if (s.id == 0) 1 else 0, s.id, maxOf(1000, e.capacity * 2), w.tick)
                }
            }
            if (v.kind == "ideal") {
                for (t in w.trains) if (t != null) { t.reliability = 10_000; t.service = false }
                for (r in w.roadVehicles) if (r != null) { r.reliability = 10_000; r.service = false }
                for (s in w.ships) if (s != null) { s.reliability = 10_000; s.service = false }
                for (a in w.aircraft) if (a != null) { a.reliability = 10_000; a.service = false }
            }
            if (v.kind == "capped") {
                if (road) for (i in line) if ((w.weather.snow[i].toInt() and 255) >= DEEP_ROAD_SNOW) w.weather.snow[i] = (DEEP_ROAD_SNOW - 1).toByte()
                if (train) for (i in line) {
                    w.closed.remove(i)
                    w.kinks.remove(i)
                    if ((w.condition[i].toInt() and 255) < WORN) w.condition[i] = WORN.toByte()
                }
            }
            if (v.busy && w.tick % Calendar.TICKS_PER_DAY == 0L) {
                val mass = w.trains.firstOrNull { it != null }?.massKg() ?: 0L
                for (i in line) repeat(BUSY) { w.wearBy(i, mass) }
            }
            w.step()
            for (t in w.trains) if (t != null) moved += t.speed
            for (r in w.roadVehicles) if (r != null) moved += r.speed
            for (s in w.ships) if (s != null) moved += s.speed
            for (a in w.aircraft) if (a != null) moved += a.speed
            if (w.tick % w.dayTicks == 0L) {
                if (moved == 0L) shut++
                moved = 0
                if (line.any { w.trackLimit(it) > 0 } && train) slow++
                if (line.any { w.closedTile(it) }) closed++
                if (line.any { w.kinks.containsKey(it) }) kinked++
                if (train) {
                    val kinkedNow = line.filter { w.kinks.containsKey(it) }.toSet()
                    kinks += (kinkedNow - wasKinked).size
                    wasKinked = kinkedNow
                    if (line.any { w.trackLimit(it) == VERY_WORN_KMH }) veryWorn++
                }
                if (road && line.any { (w.weather.snow[it].toInt() and 255) >= DEEP_ROAD_SNOW }) deep++
                if (road && line.any { w.slippery(it) || (w.weather.snow[it].toInt() and 255) >= 5 }) capped++
                if (water.any { (w.weather.ice[it].toInt() and 255) >= SHIP_ICE }) iced++
            }
            val now = Calendar.year(w.startYear, w.day)
            if (now != year) {
                for (k in sums.indices) sums[k] += c.lastYear[k]
                year = now
            }
        }
        for (k in sums.indices) sums[k] += c.thisYear[k]
        val income = sums[Books.INCOME]
        val upkeep = -sums[Books.UPKEEP]
        val profit = income + sums[Books.RUNNING] - upkeep
        val breakdowns = w.trains.sumOf { it?.breakdowns ?: 0 } + w.roadVehicles.sumOf { it?.breakdowns ?: 0 } +
            w.ships.sumOf { it?.breakdowns ?: 0 } + w.aircraft.sumOf { it?.breakdowns ?: 0 }
        return Result(profit, income, upkeep, dayLength * years, years, shut, slow, closed, kinked, deep, capped, iced, breakdowns, kinks, veryWorn)
    }


    /** Profit, income less running and upkeep, sand's upkeep and work years of a busy road run. */
    class Busy(val profit: Long, val upkeep: Long, val workYears: Int)

    /**
     * A busy road in [year]: [BUSY_TRUCKS] trucks and [BUSY_BUSES] buses, the best on sale, full, on one road of
     * [BUSY_TILES] tiles, with sand (or salt) depots placed as a player would to cover it when [sand].
     */
    private fun busyRun(year: Int, region: Region?, sand: Boolean, dayLength: Int, years: Int, seed: Long): Busy? {
        val r = Roster.BASE
        val speedCap = { e: Engine -> e.capacity.toLong() * e.speed }
        val roads = b.onSale(r, year, VehicleKind.ROAD).filter { !it.tram && it.capacity > 0 && it.cargoes.isNotEmpty() }
        val bus = roads.filter { Cargo.PASSENGERS in it.cargoes }.maxByOrNull(speedCap) ?: return null
        val truck = roads.filter { Cargo.PASSENGERS !in it.cargoes && !it.cargoes.contentEquals(intArrayOf(Cargo.MAIL)) }.maxByOrNull(speedCap) ?: return null
        val freight = b.freightCargo(truck)
        val w = World(NewGame(128, 64, seed, year, region ?: Region.MIXED, towns = 0, industries = 0, weather = region != null, inflation = false, dayLength = dayLength))
        for (i in w.corners.indices) w.corners[i] = 3
        w.trees.fill(0)
        val c = w.companies[0]
        c.money = 1_000_000_000L
        val x0 = 10
        val y = 30
        val x1 = x0 + BUSY_TILES - 1
        w.apply(Command.Road(0, x0, y, x1, y, Surface.PAVED.coerceAtMost(Surface.entries(year).last()), remove = false))
        w.apply(Command.Road(0, x0, y, x0, y + 2, Surface.DIRT, remove = false))
        w.apply(Command.Garage(0, x0, y + 3, Track.YM))
        // Two stops at each end, joined into one station.
        for (x in listOf(x0 + 2, x0 + 3, x1 - 3, x1 - 2)) w.apply(Command.RoadStop(0, x, y))
        if (w.stations.count { it != null } != 2) return null
        if (sand) for (x in busyDepots(x0, x1)) w.apply(Command.SaltDepot(0, x, y - 1))
        if (sand && w.saltDepots.size != busyDepots(x0, x1).size) return null
        repeat(BUSY_TRUCKS) { w.apply(Command.BuyRoadVehicle(0, 0, truck.id, freight)) }
        repeat(BUSY_BUSES) { w.apply(Command.BuyRoadVehicle(0, 0, bus.id, Cargo.PASSENGERS)) }
        if (w.roadVehicles.count { it != null } != BUSY_TRUCKS + BUSY_BUSES) return null
        for (v in w.roadVehicles) if (v != null) w.apply(Command.RoadOrder(0, v.id, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        val total = Calendar.TICKS_PER_DAY * 365L * dayLength * years
        val sums = LongArray(Books.COUNT)
        var calendar = Calendar.year(w.startYear, w.day)
        // One vehicle out of the garage every few ticks, trucks and buses in turn.
        val order = w.roadVehicles.filterNotNull().let { all -> all.filter { it.model == truck.id }.zip(all.filter { it.model == bus.id }).flatMap { listOf(it.first, it.second) } }
        var next = 0
        while (w.tick < total) {
            if (next < order.size && w.tick % 200 == 0L) w.apply(Command.RoadOrder(0, order[next++].id, Command.TrainOrder.START_STOP))
            if (w.tick % Calendar.TICKS_PER_DAY == 0L) for (s in w.stations) {
                if (s == null) continue
                if (s.id == 0) {
                    s.cargo[freight].clear()
                    w.addWaiting(s, freight, 1, s.id, 1000, w.tick)
                }
                s.cargo[Cargo.PASSENGERS].clear()
                w.addWaiting(s, Cargo.PASSENGERS, if (s.id == 0) 1 else 0, s.id, 1000, w.tick)
            }
            w.step()
            val now = Calendar.year(w.startYear, w.day)
            if (now != calendar) {
                for (k in sums.indices) sums[k] += c.lastYear[k]
                calendar = now
            }
        }
        for (k in sums.indices) sums[k] += c.thisYear[k]
        val upkeep = -sums[Books.UPKEEP]
        return Busy(sums[Books.INCOME] + sums[Books.RUNNING] - upkeep, upkeep, dayLength * years)
    }

    /** Where along a road from [x0] to [x1] a player puts sand depots to cover it: one every reach's width, the last at the end. */
    private fun busyDepots(x0: Int, x1: Int) = (x0 + SALT_REACH..x1 + SALT_REACH step SALT_REACH * 2 + 1).map { it.coerceAtMost(x1) }

    /** The busy road in each region, with and without sand, as a markdown section. */
    private fun busyTable(years: Int, lengths: List<Int>): String {
        val regions = System.getProperty("interchange.weatherBusyRegions")?.split(",")?.map { Region.valueOf(it.trim()) } ?: Region.entries
        val seeds = (System.getProperty("interchange.weatherBusySeeds") ?: "1,2,3,4,5,6").split(",").map { it.trim().toLong() }
        val eras = (System.getProperty("interchange.weatherBusyEras") ?: "1930,1940").split(",").map { it.trim().toInt() }
        class Job(val year: Int, val region: Region?, val sand: Boolean, val length: Int, val seed: Long)
        val jobs = eras.flatMap { e -> (regions + null).flatMap { r -> (if (r == null) listOf(false) else listOf(false, true)).flatMap { s -> lengths.flatMap { l -> seeds.map { Job(e, r, s, l, it) } } } } }
            .sortedByDescending { it.length }
        val pool = Executors.newFixedThreadPool(10)
        val done = pool.invokeAll(jobs.map { j -> Callable { j to busyRun(j.year, j.region, j.sand, j.length, years, j.seed) } }).map { it.get() }
        pool.shutdown()
        val depots = busyDepots(10, 10 + BUSY_TILES - 1).size
        val out = StringBuilder("## Busy road\n\n")
        out.append("$BUSY_TRUCKS trucks and $BUSY_BUSES buses, the best on sale, full, on one road of $BUSY_TILES tiles, $years calendar years on seeds ${seeds.joinToString()}, ")
        out.append("with no depot and with $depots sand depots covering the road. Company road profit a work year is income less running and upkeep; ")
        out.append("no weather is the road with the weather off; sand is the extra upkeep a work year; the depots are their price over $DEPOT_LIFE work years. Gain is sand's profit less the depots, less no depot's profit.\n\n")
        out.append("| year | day length | region | no weather | no depot | sand depot | sand | depots | gain |\n|").append("---|".repeat(9)).append("\n")
        for (e in eras) for (l in lengths) for (reg in regions) {
            fun got(sand: Boolean, region: Region? = reg) = done.filter { (j, x) -> j.year == e && j.length == l && j.region == region && j.sand == sand && x != null }.map { it.second!! }
            val clear = got(false, null)
            val none = got(false)
            val sand = got(true)
            if (none.isEmpty() || sand.isEmpty()) continue
            val wy = none.sumOf { it.workYears }
            val a = none.sumOf { it.profit } / wy
            val bProfit = sand.sumOf { it.profit } / wy
            val sandCost = (sand.sumOf { it.upkeep } - none.sumOf { it.upkeep }) / wy
            val depotCost = depots * saltDepotCost(e) / DEPOT_LIFE
            val ideal = if (clear.isEmpty()) "-" else "${clear.sumOf { it.profit } / clear.sumOf { it.workYears }}"
            out.append("| $e | $l | ${reg.name.lowercase()} | $ideal | $a | $bProfit | $sandCost | $depotCost | ${bProfit - depotCost - a} |\n")
        }
        return out.append("\n").toString()
    }

    @Test
    fun table() {
        val years = (System.getProperty("interchange.weatherYears") ?: return).toInt()
        val eras = (System.getProperty("interchange.weatherEras") ?: "1860,1890,1920,1950,1980,2010,2040").split(",").map { it.trim().toInt() }
        val lengths = (System.getProperty("interchange.weatherLengths") ?: "1,10").split(",").map { it.trim().toInt() }
        val regions = (System.getProperty("interchange.weatherRegions") ?: "MIXED,PRAIRIES,MARITIMES,NORTH").split(",").map { Region.valueOf(it.trim()) }
        val seeds = (System.getProperty("interchange.weatherSeeds") ?: "1,2").split(",").map { it.trim().toLong() }
        val modes = System.getProperty("interchange.weatherModes")?.split(",")?.map { it.trim() }
        class Job(val route: Route, val v: Variant, val length: Int, val seed: Long)
        val rs = routes(eras).filter { r -> modes == null || modes.any { r.mode.startsWith(it) } }
        val jobs = rs.flatMap { r -> variants(r.mode, regions).flatMap { v -> lengths.flatMap { l -> seeds.map { Job(r, v, l, it) } } } }
            .sortedByDescending { it.length }
        val started = System.currentTimeMillis()
        val pool = Executors.newFixedThreadPool(10)
        val done = pool.invokeAll(jobs.map { j -> Callable { j to run(j.route, j.v, j.length, years, j.seed) } }).map { it.get() }
        pool.shutdown()
        println("weather: ${jobs.size} runs in ${(System.currentTimeMillis() - started) / 1000} s")
        // Seeds summed: profit and days per route, variant and day length.
        fun sum(r: Route, label: String, l: Int): Result? {
            val got = done.filter { (j, x) -> j.route === r && j.v.label == label && j.length == l && x != null }.map { it.second!! }
            if (got.isEmpty()) return null
            return Result(
                got.sumOf { it.profit }, got.sumOf { it.income }, got.sumOf { it.upkeep }, got.sumOf { it.workYears }, got.sumOf { it.calendarYears },
                got.sumOf { it.shut }, got.sumOf { it.slow }, got.sumOf { it.closed }, got.sumOf { it.kinked }, got.sumOf { it.deep },
                got.sumOf { it.capped }, got.sumOf { it.iced }, got.sumOf { it.breakdowns }, got.sumOf { it.kinks }, got.sumOf { it.veryWorn },
            )
        }
        fun share(lost: Long, of: Long) = if (of <= 0) "-" else "${lost * 100 / of}%"
        fun days(d: Double) = "%.0f".format(d)
        val out = StringBuilder("# Weather and wear\n\n")
        out.append("Reference routes, full, one vehicle, $years calendar years on seeds ${seeds.joinToString()}, on a flat 128 by 64 map. ")
        out.append("Profit is income less running and upkeep, a work year, with no weather and the vehicle kept as new and never serviced (ideal). ")
        out.append("Wear is the share of that lost to wear, breakdowns and servicing. Each region's share is what the weather takes of the wear run's profit, ")
        out.append("with the calendar days a year that nothing moved (shut) and, for trains, the days with a slow order, closed or kinked, ")
        out.append("for road, the days with deep snow and with the tire cap, and for ships the days iced in. ")
        out.append("Of that, ${regions.first().name.lowercase()}'s capped share is the part that goes when deep snow on the road ")
        out.append("is kept under $DEEP_ROAD_SNOW cm (road) or the line is kept clear of closures, kinks and slow orders (trains); salt is the weather share with salt depots along the road.\n\n")
        for (l in lengths) {
            out.append("## Day length $l\n\n")
            out.append("| route | ideal/work yr | wear | breakdowns/work yr |")
            for (r in regions) out.append(" ${r.name.lowercase()} | shut | other days |")
            out.append(" capped | salt |\n|")
            out.append("---|".repeat(4 + regions.size * 3 + 2)).append("\n")
            for (r in rs) {
                val ideal = sum(r, "ideal", l) ?: continue
                val wear = sum(r, "wear", l) ?: continue
                out.append("| ${r.name} | ${ideal.perWorkYear} | ${share(ideal.profit - wear.profit, ideal.profit)} | ${"%.1f".format(wear.breakdowns.toDouble() / wear.workYears)} |")
                for (reg in regions) {
                    val x = sum(r, "weather ${reg.name.lowercase()}", l)
                    if (x == null) { out.append(" - | - | - |"); continue }
                    val other = when {
                        r.mode.startsWith("train") -> "slow ${days(x.perYear(x.slow))}, closed ${days(x.perYear(x.closed))}, kinked ${days(x.perYear(x.kinked))}"
                        r.mode == "bus" || r.mode == "truck" -> "deep ${days(x.perYear(x.deep))}, tire cap ${days(x.perYear(x.capped))}"
                        r.mode.startsWith("ship") -> "iced ${days(x.perYear(x.iced))}"
                        else -> ""
                    }
                    out.append(" ${share(wear.profit - x.profit, wear.profit)} | ${days(x.perYear(x.shut) - wear.perYear(wear.shut))} | $other |")
                }
                val first = sum(r, "weather ${regions.first().name.lowercase()}", l)
                val cap = sum(r, "capped ${regions.first().name.lowercase()}", l)
                val salt = sum(r, "salt ${regions.first().name.lowercase()}", l)
                out.append(" ${if (cap != null && first != null) share(cap.profit - first.profit, wear.profit) else "-"} |")
                out.append(" ${if (salt != null) share(wear.profit - salt.profit, wear.profit) else "-"} |\n")
            }
            out.append("\n")
            val first = regions.first().name.lowercase()
            val levels = listOf("low", "weather", "high", "busy low", "busy", "busy high")
            out.append("Track upkeep in $first: the weather share, calendar days a year with a slow order (and with a 40 km/h one) and heat kinks a year.\n\n")
            out.append("| route | low | normal | high | busy, low | busy, normal | busy, high |\n|").append("---|".repeat(7)).append("\n")
            for (r in rs) {
                if (!r.mode.startsWith("train")) continue
                val wear = sum(r, "wear", l) ?: continue
                out.append("| ${r.name} |")
                for (k in levels) {
                    val x = sum(r, "$k $first", l)
                    if (x == null) { out.append(" - |"); continue }
                    out.append(" ${share(wear.profit - x.profit, wear.profit)}, slow ${days(x.perYear(x.slow))} (${days(x.perYear(x.veryWorn))}), kinks ${"%.1f".format(x.perYear(x.kinks))} |")
                }
                out.append("\n")
            }
            out.append("\n")
        }
        if (modes == null || "busy" in modes) out.append(busyTable(years, lengths))
        val dir = File("build/balance").apply { mkdirs() }
        File(dir, "weather.md").writeText(out.toString())
    }

    companion object {
        /** Extra trains a work day over each tile of a busy line. */
        const val BUSY = 8
        /** The busy road: trucks, buses, its length in tiles, and the work years a sand depot's price is spread over. */
        const val BUSY_TRUCKS = 4
        const val BUSY_BUSES = 4
        const val BUSY_TILES = 40
        const val DEPOT_LIFE = 20
    }
}
