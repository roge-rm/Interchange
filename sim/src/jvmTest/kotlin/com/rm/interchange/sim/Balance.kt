package com.rm.interchange.sim

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The balance harness. Reference routes for every mode on a flat map with no towns, trees or industries, run for a
 * calendar year with the vehicles on sale in sample years, and a table of what each costs and earns.
 *
 * Every sample year and day length runs in about a minute on 10 threads, and writes build/balance/table.md and table.csv.
 *
 * Notes on the numbers:
 * - Price is the vehicles bought (a train with its wagons and tender), not the track, stops, docks or airports.
 * - Income and running cost are what the books show over one calendar year from 1 January, so a long day length
 *   runs 10 or 16 times as many work days. Wear, upkeep and interest aren't in running cost.
 * - Load "full" keeps a fresh thousand waiting at the start (and, for passengers, at the far end too) each work day.
 *   Load "supply" is what a town of about 500 makes: 10 passengers a work day at each end (about 300 a month, from
 *   the houses' 8 passengers a day per 256 people, less the station rating), and for freight 100 a calendar month at the
 *   start only, as a mine or a farm makes (industries' monthly amounts run 40 to 110).
 * - Bush planes on floats and skis are left out: with the weather off they hold forever in winter, as there's no snow to land skis on.
 * - Full runs use one vehicle (one train). Supply runs use as many as the supply fills, up to 6 (rail has one train).
 */
class Balance {
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

    class Row(val run: Run, val fleet: Int, val price: Long, val income: Long, val running: Long) {
        val profit get() = income - running
        val payback get() = if (profit <= 0) "never" else "%.1f".format(price.toDouble() / profit)
    }

    private fun world(year: Int, dayLength: Int): World {
        val w = World(NewGame(128, 64, 1, year, Region.MIXED, towns = 0, industries = 0, weather = false, dayLength = dayLength))
        for (i in w.corners.indices) w.corners[i] = 3
        w.trees.fill(0)
        w.companies[0].money = 1_000_000_000L
        return w
    }

    private fun onSale(w: Roster, year: Int, kind: VehicleKind) = w.engines.filter { it.enabled && it.kind == kind && year >= it.from && year < it.until }

    /** The most wagons [e] pulls with [wagon] loaded with [cargo]: it must start on a half percent grade, and hold 70% of its top speed on that grade. */
    private fun wagonsFor(r: Roster, e: Engine, wagon: Engine, cargo: Int): Int {
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

    private fun freightCargo(e: Engine) = freightPrefs.firstOrNull { it in e.cargoes } ?: e.cargoes.first()

    private fun airKind(e: Engine) = when (e.runway) {
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
        val sea = listOf(30, 80)
        val air = listOf(20, 60)
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
            for (e in extremes(ships.filter { Cargo.PASSENGERS in it.cargoes }, speedCap)) add("ship-pass", e, null, 0, year, Cargo.PASSENGERS, sea)
            for (e in extremes(ships.filter { Cargo.PASSENGERS !in it.cargoes }, speedCap)) add("ship-freight", e, null, 0, year, freightCargo(e), sea)
            val planes = onSale(r, year, VehicleKind.AIRCRAFT).filter { it.capacity > 0 && !it.floats && Airports.FROM[airKind(it)] <= year }
            for (e in extremes(planes.filter { Cargo.PASSENGERS in it.cargoes }, speedCap)) add("air-pass", e, null, 0, year, Cargo.PASSENGERS, air)
            for (e in extremes(planes.filter { Cargo.PASSENGERS !in it.cargoes }, speedCap)) add("air-freight", e, null, 0, year, freightCargo(e), air)
        }
        return out
    }

    /** How many vehicles a supply keeps busy: the demand over a round trip (a tile a work day at 40 km/h, and a few days at each stop) against one load. */
    private fun fleetFor(run: Run): Int {
        val e = run.engine
        if (run.mode.startsWith("train")) return 1
        if (run.load == "full") return 1
        val perDay = if (run.cargo == Cargo.PASSENGERS) 10.0 else 100.0 / (30 * run.dayLength)
        val trip = 2.0 * run.tiles / (e.speed / 40.0) + 6 + if (run.mode.startsWith("air")) 10 else 0
        return Math.ceil(perDay * trip / e.capacity).toInt().coerceIn(1, 6)
    }

    private fun execute(run: Run): Row? {
        val w = world(run.year, run.dayLength)
        val e = run.engine
        val len = run.tiles
        val c = w.companies[0]
        val orders = intArrayOf(0, 1)
        val fleet = fleetFor(run)
        val before: Long
        val tiles = len
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
                // A lake from x = 10 to the far dock, y = 10 to 50, with docks on both shores and a shipyard.
                for (y in 0..64) for (x in 0..128) w.corners[w.corner(x, y)] = if (x in 10..(9 + len) && y in 10..50) 0 else 1
                w.shoreChanged()
                w.apply(Command.Dock(0, 9, 30))
                w.apply(Command.Dock(0, 9 + len, 30))
                w.apply(Command.Shipyard(0, 9, 15))
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
        // Passengers go both ways; freight only from the first stop.
        val both = run.cargo == Cargo.PASSENGERS
        val dayTicks = w.dayTicks.toLong()
        val total = Calendar.TICKS_PER_DAY * 365L * run.dayLength
        while (w.tick < total) {
            if (w.tick % Calendar.TICKS_PER_DAY == 0L) {
                for (s in w.stations) {
                    if (s == null || (!both && s.id != 0)) continue
                    val dest = if (s.id == 0) 1 else 0
                    if (run.load == "full") {
                        s.cargo[run.cargo].clear()
                        w.addWaiting(s, run.cargo, dest, s.id, 1000, w.tick)
                    } else {
                        val monthStart = w.tick % dayTicks == 0L && Calendar.dayOfMonth(w.startYear, w.day) == 1
                        val n = if (both) 10 else if (monthStart) 100 else 0
                        w.addWaiting(s, run.cargo, dest, s.id, minOf(n, MAX_WAITING - s.waiting(run.cargo)), w.tick)
                    }
                }
            }
            w.step()
        }
        return Row(run, fleet, price, c.thisYear[Books.INCOME] + c.lastYear[Books.INCOME], -(c.thisYear[Books.RUNNING] + c.lastYear[Books.RUNNING]))
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
        val head = listOf("mode", "vehicle", "year", "day", "tiles", "load", "cargo", "fleet", "wagons", "price", "income", "running", "profit", "payback")
        fun cells(r: Row) = listOf(
            r.run.mode, r.run.vehicle, r.run.year, r.run.dayLength, r.run.tiles, r.run.load, Cargo.IDS[r.run.cargo], r.fleet, r.run.wagons,
            r.price, r.income, r.running, r.profit, r.payback,
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

    // The checks FareTest made: full vehicles on a year's run earn comfortably more than they cost, trains most of all.

    private fun run(weather: Boolean, year: Int, key: String, gap: Int): Pair<Long, Long> {
        val w = World(NewGame(96, 64, 1, year, Region.MIXED, towns = 0, industries = 0, weather = weather))
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
        val w = World(NewGame(96, 64, 1, year, Region.MIXED, towns = 0, industries = 0, weather = false))
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
