package com.rm.interchange.sim

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The balance harness. Reference routes for every mode on a flat map with no towns, trees or industries, run for at
 * least three work years with the vehicles on sale in sample years, and a table of what each costs and earns.
 *
 * Every sample year and day length runs in about a minute on 10 threads, and writes build/balance/table.md and table.csv.
 *
 * Notes on the numbers:
 * - Price is the vehicles bought (a train with its wagons and tender); built is the track, stops, docks or airports.
 * - Income and running cost are what the books show from 1 January over whole calendar years, as many as make three
 *   work years (three at day length 1, one at 10 or 16), so a slow ship's one or two trips a year average out.
 *   Wear, upkeep and interest aren't in running cost; upkeep (track, roads, stops, docks, airports) has its own column. Inflation is off.
 * - Load "full" keeps a fresh thousand (or two loads, for a big ship) waiting at the start (and, for passengers and
 *   mail, at the far end too) each work day.
 *   Load "supply" is what a town of about 500 makes: 10 passengers a work day at each end (about 300 a month, from
 *   the houses' 8 passengers a day per 256 people, less the station rating), and for freight 100 every 30 work days at
 *   the start only, spread evenly, as a mine or a farm makes (industries' monthly amounts run 40 to 110).
 * - Profit is over the whole run; profit a work year divides that by the work years run, and payback is price over
 *   profit a work year, so it reads the same at any day length. Line payback counts what was built as well.
 * - Bush planes on floats and skis are left out: with the weather off they hold forever in winter, as there's no snow to land skis on.
 * - Full runs use one vehicle (one train). Supply runs use as many as the supply fills, up to 6 (rail has one train).
 */
class Balance {
    private companion object {
        /** Mail a work day at each end on a supply run: a town of about 500 makes far less mail than passengers. */
        const val MAIL_A_DAY = 3
    }

    class Run(
        val mode: String,
        val vehicle: String,
        val year: Int,
        val dayLength: Int,
        val tiles: Int,
        val load: String,
        val cargo: Int,
        val engine: Engine,
        val wagon: Engine?,
        val wagons: Int,
    )

    class Row(
        val run: Run,
        val fleet: Int,
        val price: Long,
        val income: Long,
        val running: Long,
        val upkeep: Long = 0,
        val built: Long = 0,
        val workYears: Int = run.dayLength,
    ) {
        val profit get() = income - running
        val workProfit get() = profit / workYears
        val years get() = if (profit <= 0) Double.POSITIVE_INFINITY else price.toDouble() * workYears / profit
        val payback get() = if (profit <= 0) "never" else "%.1f".format(years)
        /** Payback counting the track, stops, docks or airports too. */
        val lineYears get() = if (profit <= 0) Double.POSITIVE_INFINITY else (price + built).toDouble() * workYears / profit
        val linePayback get() = if (profit <= 0) "never" else "%.1f".format(lineYears)
    }

    private fun world(year: Int, dayLength: Int, width: Int = 128): World {
        // No crashes: they'd make paybacks a matter of luck.
        val w = World(NewGame(width, 64, 1, year, Region.MIXED, towns = 0, industries = 0, weather = false, inflation = false, dayLength = dayLength, collisions = false))
        for (i in w.corners.indices) w.corners[i] = 3
        w.trees.fill(0)
        w.companies[0].money = 1_000_000_000L
        return w
    }

    internal fun onSale(w: Roster, year: Int, kind: VehicleKind) = w.engines.filter { it.enabled && it.kind == kind && year >= it.from && year < it.until }

    /** The most wagons [e] pulls with [wagon] loaded with [cargo]: it must start on a half percent grade, and hold 70% of its top speed on that grade. */
    internal fun wagonsFor(r: Roster, e: Engine, wagon: Engine, cargo: Int): Int {
        val tender = if (e.tender >= 0) r[e.tender].weight else 0
        val per = wagon.weight + wagon.capacity * Cargo.KG[cargo] / 1000
        var best = 1
        for (n in 1..MAX_WAGONS) {
            val mass = (e.weight + tender + n * per) * 1000L
            val grade = mass * 981 / 100 * 5 / 1000
            val slip = e.tractive * 1000L
            val kmh = maxOf(10, e.speed * 7 / 10)
            val units = kmh * 65536 / 2700
            val pull = minOf(slip, e.power * 74_570L / (units * 1144L / 1000).coerceAtLeast(150))
            val starts = slip * 8 / 10 > resistance(mass, 0) + grade
            if (starts && pull > resistance(mass, units) + grade) best = n else break
        }
        return best
    }

    /** The best and the cheapest of [list] by [score], once if they're the same. */
    private fun extremes(list: List<Engine>, score: (Engine) -> Long): List<Engine> {
        if (list.isEmpty()) return emptyList()
        val best = list.maxWith(compareBy<Engine> { score(it) }.thenByDescending { it.cost })
        val cheap = list.minWith(compareBy<Engine> { it.cost }.thenByDescending { it.capacity })
        return listOf(best, cheap).distinct()
    }

    private val freightPrefs = listOf(Cargo.COAL, Cargo.GRAIN, Cargo.TIMBER, Cargo.GOODS)

    internal fun freightCargo(e: Engine) = freightPrefs.firstOrNull { it in e.cargoes } ?: e.cargoes.first()

    /** Ships carry bulk first, then lumber for a tug and barge, then goods. */
    private val shipPrefs = listOf(Cargo.COAL, Cargo.GRAIN, Cargo.IRON_ORE, Cargo.OIL, Cargo.LUMBER, Cargo.GOODS)

    internal fun shipCargo(e: Engine) = shipPrefs.firstOrNull { it in e.cargoes } ?: e.cargoes.first()

    /** Whether [cargo] goes both ways between the ends, as passengers and mail do, or only from the first. */
    private fun bothWays(cargo: Int) = cargo == Cargo.PASSENGERS || cargo == Cargo.MAIL

    internal fun airKind(e: Engine) = when (e.runway) {
        Runways.STRIP -> Airports.STRIP
        Runways.REGIONAL -> Airports.REGIONAL
        Runways.CITY -> Airports.CITY
        Runways.INTERNATIONAL -> Airports.INTERNATIONAL
        else -> Airports.HELIPORT
    }

    /** Every run for [years], [days] and [lengths]; [lengths] gives the short and long route of each mode. */
    private fun plan(years: List<Int>, days: List<Int>, loads: List<String>): List<Run> {
        val r = Roster.BASE
        val out = ArrayList<Run>()
        val rail = listOf(20, 60)
        val road = listOf(15, 40)
        val ferry = listOf(12, 30, 80)
        val sea = listOf(30, 80)
        val air = listOf(20, 60, 120)
        fun add(mode: String, e: Engine, wagon: Engine?, wagons: Int, year: Int, cargo: Int, lengths: List<Int>) {
            for (dl in days) for (len in lengths) for (load in loads) {
                out += Run(mode, e.key.substringAfter(':'), year, dl, len, load, cargo, e, wagon, wagons)
            }
        }
        for (year in years) {
            val engines = onSale(r, year, VehicleKind.ENGINE).filter { it.traction != Traction.ELECTRIC }
            val wagonsOn = onSale(r, year, VehicleKind.WAGON)
            val coach = wagonsOn.filter { Cargo.PASSENGERS in it.cargoes }.maxByOrNull { it.capacity }
            if (coach != null) for (e in extremes(engines) { it.power.toLong() }) {
                add("train-pass", e, coach, wagonsFor(r, e, coach, Cargo.PASSENGERS), year, Cargo.PASSENGERS, rail)
            }
            val freight = freightPrefs.firstNotNullOfOrNull { c -> wagonsOn.filter { c in it.cargoes }.maxByOrNull { it.capacity }?.let { c to it } }
            if (freight != null) for (e in extremes(engines) { it.power.toLong() }) {
                add("train-freight", e, freight.second, wagonsFor(r, e, freight.second, freight.first), year, freight.first, rail)
            }
            val roads = onSale(r, year, VehicleKind.ROAD).filter { !it.tram && it.capacity > 0 && it.cargoes.isNotEmpty() }
            val speedCap = { e: Engine -> e.capacity.toLong() * e.speed }
            for (e in extremes(roads.filter { Cargo.PASSENGERS in it.cargoes }, speedCap)) add("bus", e, null, 0, year, Cargo.PASSENGERS, road)
            for (e in extremes(roads.filter { Cargo.PASSENGERS !in it.cargoes && !it.cargoes.contentEquals(intArrayOf(Cargo.MAIL)) }, speedCap)) add("truck", e, null, 0, year, freightCargo(e), road)
            val ships = onSale(r, year, VehicleKind.SHIP).filter { it.capacity > 0 && it.cargoes.isNotEmpty() }
            for (e in extremes(ships.filter { Cargo.PASSENGERS in it.cargoes }, speedCap)) add("ship-pass", e, null, 0, year, Cargo.PASSENGERS, ferry)
            for (e in extremes(ships.filter { Cargo.PASSENGERS !in it.cargoes }, speedCap)) add("ship-freight", e, null, 0, year, shipCargo(e), sea)
            val planes = onSale(r, year, VehicleKind.AIRCRAFT).filter { it.capacity > 0 && !it.floats && Airports.FROM[airKind(it)] <= year }
            for (e in extremes(planes.filter { Cargo.PASSENGERS in it.cargoes }, speedCap)) add("air-pass", e, null, 0, year, Cargo.PASSENGERS, air)
            for (e in extremes(planes.filter { Cargo.PASSENGERS !in it.cargoes }, speedCap)) add("air-freight", e, null, 0, year, freightCargo(e), air)
            for (e in extremes(planes.filter { Cargo.PASSENGERS in it.cargoes && Cargo.MAIL in it.cargoes }, speedCap)) add("air-mail", e, null, 0, year, Cargo.MAIL, air)
        }
        return out
    }

    /** How many vehicles a supply keeps busy: the demand over a round trip (a tile a work day at 40 km/h, and a few days at each stop) against one load. */
    private fun fleetFor(run: Run): Int {
        val e = run.engine
        if (run.mode.startsWith("train")) return 1
        if (run.load == "full") return 1
        val perDay = when (run.cargo) {
            Cargo.PASSENGERS -> 10.0
            Cargo.MAIL -> MAIL_A_DAY.toDouble()
            else -> 100.0 / 30
        }
        val trip = 2.0 * run.tiles / (e.speed / 40.0) + 6 + if (run.mode.startsWith("air")) 10 else 0
        return Math.ceil(perDay * trip / e.capacity).toInt().coerceIn(1, 6)
    }

    private fun execute(run: Run): Row? {
        val w = world(run.year, run.dayLength, if (run.tiles > 100) 256 else 128)
        val e = run.engine
        val len = run.tiles
        val c = w.companies[0]
        val orders = intArrayOf(0, 1)
        val fleet = fleetFor(run)
        val before: Long
        val tiles = len
        val start = c.money
        when {
            run.mode.startsWith("train") -> {
                w.apply(Command.Depot(0, 2, 10, Track.XP))
                w.apply(Command.Rail(0, 3, 10, 10 + len + 4, 10, Track.X, remove = false))
                w.apply(Command.Station(0, 6, 10, 9, 10, Track.X))
                w.apply(Command.Station(0, 6 + len, 10, 9 + len, 10, Track.X))
                before = c.money
                w.apply(Command.BuyTrain(0, 0, e.id, run.wagon!!.id, run.wagons, run.cargo))
                if (w.trains.isEmpty()) return null
                w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, orders))
                w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
            }
            run.mode == "bus" || run.mode == "truck" -> {
                val x0 = 10
                val y = 30
                val x1 = x0 + len + 4
                w.apply(Command.Road(0, x0, y, x1, y, Surface.PAVED.coerceAtMost(Surface.entries(run.year).last()), remove = false))
                w.apply(Command.Road(0, x0, y, x0, y + 2, Surface.DIRT, remove = false))
                w.apply(Command.Garage(0, x0, y + 3, Track.YM))
                w.apply(Command.RoadStop(0, x0 + 2, y))
                w.apply(Command.RoadStop(0, x1 - 2, y))
                before = c.money
                repeat(fleet) {
                    w.apply(Command.BuyRoadVehicle(0, 0, e.id, run.cargo))
                    val id = w.roadVehicles.size - 1
                    if (id < 0) return null
                    w.apply(Command.RoadOrder(0, id, Command.TrainOrder.ORDERS, orders))
                    w.apply(Command.RoadOrder(0, id, Command.TrainOrder.START_STOP))
                }
            }
            run.mode.startsWith("ship") -> {
                // A lake from x = 10 to the far dock, y = 10 to 50, with docks on both shores and a shipyard by the first.
                for (y in 0..64) for (x in 0..128) w.corners[w.corner(x, y)] = if (x in 10..(9 + len) && y in 10..50) 0 else 1
                w.shoreChanged()
                w.apply(Command.Dock(0, 9, 30))
                w.apply(Command.Dock(0, 9 + len, 30))
                w.apply(Command.Shipyard(0, 9, 27))
                if (w.stations.size < 2 || w.depots.isEmpty()) return null
                before = c.money
                repeat(fleet) {
                    w.apply(Command.BuyCraft(0, 0, e.id, run.cargo))
                    val id = w.ships.size - 1
                    if (id < 0) return null
                    w.apply(Command.CraftOrder(0, false, id, Command.TrainOrder.ORDERS, orders))
                    w.apply(Command.CraftOrder(0, false, id, Command.TrainOrder.START_STOP))
                }
            }
            else -> {
                val kind = airKind(e)
                w.apply(Command.Airport(0, 5, 30, kind, Track.X))
                w.apply(Command.Airport(0, 5 + len, 30, kind, Track.X))
                if (w.airports.size < 2) return null
                before = c.money
                repeat(fleet) {
                    w.apply(Command.BuyCraft(0, w.airports[0]!!.depot, e.id, run.cargo))
                    val id = w.aircraft.size - 1
                    if (id < 0) return null
                    w.apply(Command.CraftOrder(0, true, id, Command.TrainOrder.ORDERS, orders))
                    w.apply(Command.CraftOrder(0, true, id, Command.TrainOrder.START_STOP))
                }
            }
        }
        val price = before - c.money
        val built = start - before
        // Passengers and mail go both ways; freight only from the first stop.
        val both = bothWays(run.cargo)
        // Whole calendar years, enough for three work years.
        val calendarYears = (3 + run.dayLength - 1) / run.dayLength
        val total = Calendar.TICKS_PER_DAY * 365L * run.dayLength * calendarYears
        val sums = LongArray(Books.COUNT)
        var year = Calendar.year(w.startYear, w.day)
        while (w.tick < total) {
            if (w.tick % Calendar.TICKS_PER_DAY == 0L) {
                for (s in w.stations) {
                    if (s == null || (!both && s.id != 0)) continue
                    val dest = if (s.id == 0) 1 else 0
                    if (run.load == "full") {
                        s.cargo[run.cargo].clear()
                        w.addWaiting(s, run.cargo, dest, s.id, maxOf(1000, e.capacity * 2), w.tick)
                    } else {
                        // Freight 100 every 30 work days, the remainder carried so it's 3 or 4 a day.
                        val d = w.tick / Calendar.TICKS_PER_DAY
                        val n = when {
                            run.cargo == Cargo.PASSENGERS -> 10
                            run.cargo == Cargo.MAIL -> MAIL_A_DAY
                            else -> ((d + 1) * 100 / 30 - d * 100 / 30).toInt()
                        }
                        w.addWaiting(s, run.cargo, dest, s.id, minOf(n, MAX_WAITING - s.waiting(run.cargo)), w.tick)
                    }
                }
            }
            w.step()
            val now = Calendar.year(w.startYear, w.day)
            if (now != year) {
                for (k in sums.indices) sums[k] += c.lastYear[k]
                year = now
            }
        }
        for (k in sums.indices) sums[k] += c.thisYear[k]
        return Row(run, fleet, price, sums[Books.INCOME], -sums[Books.RUNNING], -sums[Books.UPKEEP], built, run.dayLength * calendarYears)
            .also { check(tiles == len) }
    }

    private fun table(runs: List<Run>): List<Row> {
        val pool = Executors.newFixedThreadPool(10)
        try {
            // The longest first, so the slow ones don't end up alone at the finish.
            val order = runs.sortedByDescending { it.dayLength }
            val futures = order.map { r -> pool.submit(Callable { r to execute(r) }) }
            val done = futures.map { it.get() }.mapNotNull { (_, row) -> row }
            return done.sortedWith(
                compareBy<Row>({ it.run.mode }, { it.run.year }, { it.run.vehicle }, { it.run.dayLength }, { it.run.tiles }, { it.run.load }),
            )
        } finally {
            pool.shutdown()
        }
    }

    private fun write(rows: List<Row>) {
        val dir = File("build/balance").apply { mkdirs() }
        val head = listOf(
            "mode", "vehicle", "year", "day", "tiles", "load", "cargo", "fleet", "wagons", "price", "built", "work yrs", "income", "running",
            "profit", "upkeep", "work-yr profit", "work-yr payback", "line payback",
        )
        fun cells(r: Row) = listOf(
            r.run.mode, r.run.vehicle, r.run.year, r.run.dayLength, r.run.tiles, r.run.load, Cargo.IDS[r.run.cargo], r.fleet, r.run.wagons,
            r.price, r.built, r.workYears, r.income, r.running, r.profit, r.upkeep, r.workProfit, r.payback, r.linePayback,
        )
        File(dir, "table.csv").writeText((listOf(head) + rows.map { cells(it) }).joinToString("\n") { it.joinToString(",") } + "\n")
        val md = StringBuilder()
        md.append("| ").append(head.joinToString(" | ")).append(" |\n")
        md.append("|").append(head.joinToString("|") { "---" }).append("|\n")
        for (r in rows) md.append("| ").append(cells(r).joinToString(" | ")).append(" |\n")
        File(dir, "table.md").writeText(md.toString())
    }

    @Test
    fun theTable() {
        val years = listOf(1836, 1860, 1890, 1920, 1950, 1980, 2010, 2040)
        val days = listOf(1, 10, 16)
        val runs = plan(years, days, listOf("full", "supply"))
        val started = System.currentTimeMillis()
        val rows = table(runs)
        println("balance: ${rows.size} of ${runs.size} runs in ${(System.currentTimeMillis() - started) / 1000} s")
        write(rows)
        assertTrue(rows.isNotEmpty())
    }

    /**
     * A sensible first railway pays its train back in 1.5 to 5 work years at day length 1 and 10, on a town of about
     * 500 at each end or one mine's output, and the line makes money counting its upkeep.
     */
    @Test
    fun earlyRailwaysPayBack() {
        val r = Roster.BASE
        fun e(key: String) = r.engines.first { it.key == "base:$key" }
        val lines = listOf(
            Triple(1836, "dorchester", "coach_early") to Triple(4, 30, Cargo.PASSENGERS),
            Triple(1860, "american", "coach_wood") to Triple(4, 30, Cargo.PASSENGERS),
            Triple(1860, "american", "boxcar_wood") to Triple(10, 40, Cargo.GRAIN),
        )
        val runs = lines.flatMap { (t, s) ->
            listOf(1, 10).map { dl ->
                Run("train", t.second, t.first, dl, s.second, "supply", s.third, e(t.second), e(t.third), s.first)
            }
        }
        val rows = table(runs)
        assertEquals(runs.size, rows.size, "every early line was built")
        for (row in rows) println("early: ${row.run.year} ${row.run.vehicle} ${Cargo.IDS[row.run.cargo]} ${row.run.tiles} tiles, day length ${row.run.dayLength}: ${row.payback}")
        for (row in rows) {
            val what = "${row.run.year} ${row.run.vehicle} and ${row.run.wagons} ${Cargo.IDS[row.run.cargo]} wagons, ${row.run.tiles} tiles, day length ${row.run.dayLength}"
            assertTrue(row.years in 1.5..5.0, "$what pays back in ${row.payback} work years")
            assertTrue(row.profit > row.upkeep, "$what makes ${row.profit} against ${row.upkeep} upkeep")
        }
    }

    /**
     * Each kind in its niche: a full 1950s truck pays back in 2 to 5 work years on a short haul of 15 tiles, a 1950s
     * freight train pays back faster than the trucks on 60 tiles, as a 1980 and a 2010 diesel do against their
     * trucks, and a horse freight wagon pays on 10 tiles in 1850.
     */
    @Test
    fun eachKindPaysInItsNiche() {
        val r = Roster.BASE
        fun e(key: String) = r.engines.first { it.key == "base:$key" }
        val days = listOf(1, 10)
        fun road(key: String, year: Int, tiles: Int, cargo: Int) = days.map { dl -> Run("truck", key, year, dl, tiles, "full", cargo, e(key), null, 0) }
        val coal = onSale(r, 1950, VehicleKind.WAGON).filter { Cargo.COAL in it.cargoes }.maxBy { it.capacity }
        val pacific = e("pacific")
        val train = days.map { dl -> Run("train-freight", "pacific", 1950, dl, 60, "full", Cargo.COAL, pacific, coal, wagonsFor(r, pacific, coal, Cargo.COAL)) }
        val trucks = listOf("dump_truck" to Cargo.COAL, "semi_van" to Cargo.GOODS)
        val short = trucks.flatMap { (k, c) -> road(k, 1950, 15, c) }
        val long = trucks.flatMap { (k, c) -> road(k, 1950, 60, c) }
        val horse = road("freight_wagon", 1850, 10, Cargo.GRAIN)
        fun diesel(key: String, year: Int) = days.map { dl -> Run("train-freight", key, year, dl, 60, "full", Cargo.COAL, e(key), coal, wagonsFor(r, e(key), coal, Cargo.COAL)) }
        val diesels = diesel("sd40", 1980) + diesel("dash9", 2010)
        val modern = listOf(
            Triple("tandem_dump", 1980, Cargo.COAL), Triple("highway_tractor", 1980, Cargo.GOODS),
            Triple("quad_dump", 2010, Cargo.COAL), Triple("grain_train", 2010, Cargo.GRAIN), Triple("aero_tractor", 2010, Cargo.GOODS),
        ).flatMap { (k, y, c) -> road(k, y, 60, c) }
        val all = short + long + train + horse + diesels + modern
        val rows = table(all)
        assertEquals(all.size, rows.size, "every route was built")
        fun what(row: Row) = "${row.run.year} ${row.run.vehicle}, ${row.run.tiles} tiles, day length ${row.run.dayLength}"
        for (row in rows) println("niche: ${what(row)}: ${row.payback}")
        for (row in rows.filter { it.run.mode == "truck" && it.run.tiles == 15 }) assertTrue(row.years in 2.0..5.0, "${what(row)} pays back in ${row.payback} work years")
        for (t in rows.filter { it.run.mode == "train-freight" }) {
            // Against the trucks of its own day: 1950s ones for the Pacific, 1980 or 2010 ones for the diesels.
            for (row in rows.filter { it.run.mode == "truck" && it.run.tiles == 60 && it.run.dayLength == t.run.dayLength && it.run.year == t.run.year }) {
                assertTrue(t.years < row.years, "${what(t)} pays back in ${t.payback} work years, ${what(row)} in ${row.payback}")
            }
        }
        for (row in rows.filter { it.run.year == 1850 }) assertTrue(row.years <= 8.0, "${what(row)} pays back in ${row.payback} work years")
    }

    /**
     * Water and air in their niches, full, at day length 1 and 10:
     * - Bulk by water on a long haul of 80 tiles pays back in 1.5 to 5 work years, and from the diesels on, counting
     *   the docks against the track, faster than the coal train of its day on the same 80 tiles. Steam freight trains
     *   pay back in 1.2 to 1.8 there, faster than any laker.
     * - Ferries pay back in 5 work years or less on a short crossing of 12 tiles.
     * - Airliners of the 1950s, 1960s and 1980s pay back in 2.5 to 6.5 work years on 60 and 120 tiles, and at least
     *   twice as slowly on a short hop of 20.
     * - Freight aircraft pay on 120 tiles within 8 work years, but never as fast as the freight train of their day.
     * - A helicopter pays modestly on a short hop: 3 to 8 work years on 20 tiles.
     */
    @Test
    fun waterAndAirPayInTheirNiches() {
        val r = Roster.BASE
        fun e(key: String) = r.engines.first { it.key == "base:$key" }
        val days = listOf(1, 10)
        fun runs(mode: String, key: String, year: Int, tiles: Int, cargo: Int) = days.map { dl -> Run(mode, key, year, dl, tiles, "full", cargo, e(key), null, 0) }
        val coal = onSale(r, 1980, VehicleKind.WAGON).filter { Cargo.COAL in it.cargoes }.maxBy { it.capacity }
        fun train(key: String, year: Int, tiles: Int) = days.map { dl -> Run("train-freight", key, year, dl, tiles, "full", Cargo.COAL, e(key), coal, wagonsFor(r, e(key), coal, Cargo.COAL)) }
        val bulk = listOf(
            Triple("steam_laker", 1950, Cargo.COAL), Triple("lake_tanker", 1950, Cargo.OIL), Triple("canaller", 1920, Cargo.COAL),
            Triple("seaway_laker", 1980, Cargo.COAL), Triple("self_unloader", 2010, Cargo.COAL), Triple("product_tanker", 2010, Cargo.OIL),
        ).flatMap { (k, y, c) -> runs("ship-freight", k, y, 80, c) }
        val tug = runs("ship-freight", "tug_barge", 1980, 80, Cargo.LUMBER)
        val trains = train("mikado", 1920, 80) + train("pacific", 1950, 80) + train("sd40", 1980, 80) + train("dash9", 2010, 80) +
            train("pacific", 1950, 60) + train("sd40", 1980, 60) + train("dash9", 2010, 60)
        val ferries = listOf("paddle_steamer" to 1850, "lake_steamer" to 1900, "car_ferry" to 1950, "superferry" to 1980, "hovercraft" to 1990, "battery_ferry" to 2020)
            .flatMap { (k, y) -> runs("ship-pass", k, y, 12, Cargo.PASSENGERS) }
        val liners = listOf("north_star" to 1950, "dc8" to 1965, "b767" to 1985)
        val air = liners.flatMap { (k, y) -> listOf(20, 60, 120).flatMap { runs("air-pass", k, y, it, Cargo.PASSENGERS) } }
        // The rest of the airliners, printed only.
        val others = listOf("dc3" to 1945, "viscount" to 1960, "dc9" to 1970, "b737_200" to 1975, "dash7" to 1980, "dash8" to 1990, "a320" to 1995, "crj" to 2000, "a220" to 2020)
            .flatMap { (k, y) -> listOf(20, 60, 120).flatMap { runs("air-pass", k, y, it, Cargo.PASSENGERS) } }
        val freighters = listOf("b747f" to 1980, "hercules" to 1980, "bristol_freighter" to 1960).flatMap { (k, y) -> runs("air-freight", k, y, 120, Cargo.GOODS) }
        val helis = listOf("bell_47" to 1955, "s55" to 1960, "jetranger" to 1980, "s92" to 2010).flatMap { (k, y) -> runs("air-pass", k, y, 20, Cargo.PASSENGERS) }
        val all = bulk + tug + trains + ferries + air + others + freighters + helis
        val rows = table(all)
        assertEquals(all.size, rows.size, "every route was built")
        fun what(row: Row) = "${row.run.year} ${row.run.vehicle} ${Cargo.IDS[row.run.cargo]}, ${row.run.tiles} tiles, day length ${row.run.dayLength}"
        for (row in rows) println("water and air: ${what(row)}: ${row.payback}, line ${row.linePayback}")
        fun trainOf(row: Row, tiles: Int) = rows.filter { it.run.mode == "train-freight" && it.run.tiles == tiles && it.run.dayLength == row.run.dayLength }
            .minBy { kotlin.math.abs(it.run.year - row.run.year) }
        for (row in rows.filter { it.run.mode == "ship-freight" }) {
            assertTrue(row.years in 1.5..5.0, "${what(row)} pays back in ${row.payback} work years")
            if (row.run.vehicle == "tug_barge" || row.run.year < 1960) continue
            val t = trainOf(row, 80)
            assertTrue(row.lineYears < t.lineYears, "${what(row)} pays back its line in ${row.linePayback}, ${what(t)} in ${t.linePayback}")
        }
        for (row in rows.filter { it.run.mode == "ship-pass" }) assertTrue(row.years <= 5.0, "${what(row)} pays back in ${row.payback} work years")
        val names = liners.map { it.first }
        for (row in rows.filter { it.run.vehicle in names }) {
            if (row.run.tiles >= 60) assertTrue(row.years in 2.5..6.5, "${what(row)} pays back in ${row.payback} work years")
            else {
                val long = rows.first { it.run.vehicle == row.run.vehicle && it.run.dayLength == row.run.dayLength && it.run.tiles == 120 }
                assertTrue(row.years > long.years * 2, "${what(row)} pays back in ${row.payback} work years, on 120 tiles in ${long.payback}")
            }
        }
        for (row in rows.filter { it.run.mode == "air-freight" && it.run.vehicle != "bristol_freighter" }) {
            assertTrue(row.years <= 8.0, "${what(row)} pays back in ${row.payback} work years")
            val t = trainOf(row, 60)
            assertTrue(row.years > t.years, "${what(row)} pays back in ${row.payback} work years, ${what(t)} in ${t.payback}")
        }
        for (row in rows.filter { it.run.vehicle == "jetranger" || it.run.vehicle == "s92" }) {
            assertTrue(row.years in 3.0..8.0, "${what(row)} pays back in ${row.payback} work years")
        }
    }

    // The checks FareTest made: full vehicles on a year's run earn comfortably more than they cost, trains most of all.

    private fun run(weather: Boolean, year: Int, key: String, gap: Int): Pair<Long, Long> {
        val w = World(NewGame(96, 64, 1, year, Region.MIXED, towns = 0, industries = 0, weather = weather, collisions = false))
        for (i in w.corners.indices) w.corners[i] = 3
        w.trees.fill(0)
        w.companies[0].money = 10_000_000
        // Two towns' worth of people: a big block of houses round each stop.
        val y = 30
        val x0 = 10
        val x1 = x0 + gap
        w.apply(Command.Road(0, x0, y, x1, y, Surface.PAVED.coerceAtMost(Surface.entries(year).last()), remove = false))
        w.apply(Command.Road(0, x0, y, x0, y + 2, Surface.DIRT, remove = false))
        w.apply(Command.Garage(0, x0, y + 3, Track.YM))
        w.apply(Command.RoadStop(0, x0 + 2, y))
        w.apply(Command.RoadStop(0, x1 - 2, y))
        val model = w.roster.engines.first { it.key == "base:$key" }
        repeat(3) {
            w.apply(Command.BuyRoadVehicle(0, 0, model.id))
            val id = w.roadVehicles.size - 1
            w.apply(Command.RoadOrder(0, id, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
            w.apply(Command.RoadOrder(0, id, Command.TrainOrder.START_STOP))
        }
        // Plenty waiting at each end, always.
        repeat(Calendar.TICKS_PER_DAY * 365) {
            for (s in w.stations) if (s != null && s.waiting(Cargo.PASSENGERS) < 200) w.addWaiting(s, Cargo.PASSENGERS, if (s.id == 0) 1 else 0, s.id, 100, w.tick, 0)
            w.step()
        }
        val c = w.companies[0]
        return (c.thisYear[Books.INCOME] + c.lastYear[Books.INCOME]) to -(c.thisYear[Books.RUNNING] + c.lastYear[Books.RUNNING])
    }

    private fun train(year: Int, engine: String, wagon: String, wagons: Int, gap: Int): Pair<Long, Long> {
        val w = World(NewGame(96, 64, 1, year, Region.MIXED, towns = 0, industries = 0, weather = false, collisions = false))
        for (i in w.corners.indices) w.corners[i] = 3
        w.trees.fill(0)
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 10 + gap + 4, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 6, 10, 9, 10, Track.X))
        w.apply(Command.Station(0, 6 + gap, 10, 9 + gap, 10, Track.X))
        val e = w.roster.engines.first { it.key == "base:$engine" }.id
        val wg = w.roster.engines.first { it.key == "base:$wagon" }.id
        w.apply(Command.BuyTrain(0, 0, e, wg, wagons))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        repeat(Calendar.TICKS_PER_DAY * 365) {
            for (s in w.stations) if (s != null && s.waiting(Cargo.PASSENGERS) < 400) w.addWaiting(s, Cargo.PASSENGERS, if (s.id == 0) 1 else 0, s.id, 200, w.tick, 0)
            w.step()
        }
        val c = w.companies[0]
        return (c.thisYear[Books.INCOME] + c.lastYear[Books.INCOME]) to -(c.thisYear[Books.RUNNING] + c.lastYear[Books.RUNNING])
    }

    private fun pays(what: String, r: Pair<Long, Long>, times: Int) =
        assertTrue(r.first * 10 > r.second * times, "$what earns ${r.first} against ${r.second} to run")

    @Test
    fun everyKindOfVehiclePaysItsWay() {
        pays("an 1860 train", train(1860, "american", "coach_wood", 4, 40), 30)
        pays("a 1960 train", train(1960, "gp9", "coach_steel", 6, 60), 30)
        pays("a motor bus", run(false, 1930, "motor_bus", 25), 15)
        pays("a New Look bus", run(false, 1965, "new_look", 25), 20)
        // Between towns in winter, on a company road the province plows.
        pays("a New Look bus in winter", run(true, 1965, "new_look", 25), 20)
        pays("a horse omnibus", run(false, 1850, "horse_omnibus", 15), 15)
        pays("a stagecoach", run(false, 1850, "stagecoach", 25), 15)
    }
}
