package com.rm.interchange.sim

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test

/**
 * The scripted [Player] for the same number of work years at each day length, to see where x1 and x10 part:
 * `./gradlew :sim:jvmTest --tests '*DayLengthRun*' -Pinterchange.dayLengthRun=1`, writes build/balance/daylength.md.
 * Optional comma lists interchange.dayLengthStarts, dayLengths, dayLengthSeeds and interchange.dayLengthYears (work
 * years, 10) and dayLengthModes. Modes: plain (the player's own lines), still (towns kept from growing), flat (still and
 * no inflation), value (a value goal it can't meet kept open, so it goes for money) and valuestill.
 */
class DayLengthRun {
    class Cell(val start: Int, val length: Int, val seed: Long, val mode: String)

    class Out(
        val value: Long, val income: Long, val running: Long, val upkeep: Long, val interest: Long,
        val breakdowns: Long, val delivered: Long, val vehicleYears: Double, val load: Int,
        val townsStart: Int, val townsEnd: Int, val served: Int, val vehicles: Int, val calendarDays: Int, val secs: Long,
    )

    @Test
    fun table() {
        System.getProperty("interchange.dayLengthRun") ?: return
        fun list(key: String, def: String) = (System.getProperty(key) ?: def).split(",").map { it.trim() }
        val starts = list("interchange.dayLengthStarts", "1836,1900,1950,2000").map { it.toInt() }
        val lengths = list("interchange.dayLengths", "1,10,16").map { it.toInt() }
        val seeds = list("interchange.dayLengthSeeds", "1,2,3").map { it.toLong() }
        val modes = list("interchange.dayLengthModes", "plain,still,flat,value,valuestill")
        val years = (System.getProperty("interchange.dayLengthYears") ?: "10").toInt()
        val cells = starts.flatMap { s -> modes.flatMap { m -> lengths.flatMap { l -> seeds.map { Cell(s, l, it, m) } } } }
        val pool = Executors.newFixedThreadPool(minOf(cells.size, 12))
        val done = pool.invokeAll(cells.map { c -> Callable { c to play(c, years) } }).map { it.get() }
        pool.shutdown()

        val out = StringBuilder("# Day length\n\n")
        out.append("The scripted player on a 256 by 256 map, default settings, no rivals, for $years work years at each day length. ")
        out.append("Plain is its own lines; still is plain with towns kept from growing; flat is still with no inflation; value has a value goal it can't meet kept open, so it goes for money; valuestill is value with towns kept from growing. ")
        out.append("Money columns are totals over the run. Value is company value at the end. Carried is cargo and passengers delivered a vehicle a work year, ")
        out.append("load is the mean share of capacity filled over every work day a vehicle is out. Towns is the people in towns its stations count for, at the start and end.\n\n")
        for (mode in modes) for (start in starts) {
            out.append("## $start, $mode\n\n")
            out.append("| day length | seed | value | income | running | upkeep | interest | run % of income | breakdowns / veh yr | carried / veh yr | income / veh yr | load % | towns served | vehicles | time |\n")
            out.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n")
            for (length in lengths) {
                val rows = done.filter { it.first.start == start && it.first.mode == mode && it.first.length == length }
                for ((c, o) in rows) {
                    val vy = o.vehicleYears.coerceAtLeast(0.01)
                    out.append("| ${c.length} | ${c.seed} | ${o.value / 1000}k | ${o.income / 1000}k | ${o.running / 1000}k | ${o.upkeep / 1000}k | ${o.interest / 1000}k | ")
                    out.append("${if (o.income > 0) o.running * 100 / o.income else 0} | ${"%.1f".format(o.breakdowns / vy)} | ${(o.delivered / vy).toLong()} | ${(o.income / vy).toLong()} | ")
                    out.append("${o.load} | ${o.served} (${o.townsStart} to ${o.townsEnd}) | ${o.vehicles} | ${o.secs}s |\n")
                }
                if (rows.size > 1) {
                    fun med(f: (Out) -> Long) = rows.map { f(it.second) }.sorted()[rows.size / 2]
                    out.append("| ${length} | median | ${med { it.value } / 1000}k | ${med { it.income } / 1000}k | ${med { it.running } / 1000}k | ${med { it.upkeep } / 1000}k | ${med { it.interest } / 1000}k | ")
                    out.append("${med { if (it.income > 0) it.running * 100 / it.income else 0 }} | | ${med { (it.delivered / it.vehicleYears.coerceAtLeast(0.01)).toLong() }} | ${med { (it.income / it.vehicleYears.coerceAtLeast(0.01)).toLong() }} | ")
                    out.append("${med { it.load.toLong() }} | ${med { it.served.toLong() }} | ${med { it.vehicles.toLong() }} | |\n")
                }
            }
            out.append("\n")
        }
        val dir = File("build/balance").apply { mkdirs() }
        File(dir, "daylength.md").writeText(out.toString())
    }

    private fun play(c: Cell, years: Int): Out {
        val t0 = System.nanoTime()
        val w = World(NewGame(256, 256, c.seed, c.start, dayLength = c.length, inflation = c.mode != "flat"))
        val still = c.mode == "still" || c.mode == "flat" || c.mode == "valuestill"
        if (c.mode.startsWith("value")) w.goals += Goal(Goal.COMPANY_VALUE, Long.MAX_VALUE / 4, c.start + 200)
        val p = Player(w, aim = c.mode.startsWith("value"))
        val workDays = years * 365
        val townsStart = w.towns.sumOf { it.population }
        if (still) for (t in w.towns) t.growIn = Int.MAX_VALUE
        var breakdowns = 0L
        val seen = HashMap<Long, Int>()
        var vehicleDays = 0L
        var loadSum = 0L
        var capSum = 0L
        var delivered = 0L
        fun deliveredNow() = w.towns.sumOf { it.delivered.sum().toLong() }
        var deliveredMonth = 0L
        fun workDay() {
            for (t in w.trains) if (t != null && t.owner == 0) {
                val key = t.id.toLong()
                val was = seen[key] ?: 0
                breakdowns += if (t.breakdowns >= was) t.breakdowns - was else t.breakdowns
                seen[key] = t.breakdowns
                if (!t.inDepot) {
                    vehicleDays++
                    for (cg in 0 until Cargo.COUNT) { loadSum += t.load(cg); capSum += t.capacity(cg) }
                }
            }
            for (v in w.roadVehicles) if (v != null && v.owner == 0) {
                val key = (1L shl 32) + v.id
                val was = seen[key] ?: 0
                breakdowns += if (v.breakdowns >= was) v.breakdowns - was else v.breakdowns
                seen[key] = v.breakdowns
                if (!v.inDepot) {
                    vehicleDays++
                    for (cg in 0 until Cargo.COUNT) { loadSum += v.load(cg); capSum += v.capacity(cg) }
                }
            }
        }
        val industryBrought = LongArray(w.industries.size)
        p.play(10_000, { w.workDay >= workDays }, { workDay() }) {
            // A new calendar day. Town deliveries are kept a month at a time; industries' as they're brought.
            val now = deliveredNow()
            if (now < deliveredMonth) delivered += deliveredMonth
            deliveredMonth = now
            for ((i, ind) in w.industries.withIndex()) if (i < industryBrought.size) {
                if (ind.broughtThisMonth < industryBrought[i]) delivered += industryBrought[i]
                industryBrought[i] = ind.broughtThisMonth.toLong()
            }
            if (still) for (t in w.towns) t.growIn = Int.MAX_VALUE
        }
        delivered += deliveredMonth + industryBrought.sum()
        val books = p.years
        val y = w.companies[0].thisYear
        val income = books.sumOf { it.income } + y[Books.INCOME]
        val running = books.sumOf { it.running } - y[Books.RUNNING]
        val upkeep = books.sumOf { it.upkeep } - y[Books.UPKEEP]
        val interest = books.sumOf { it.interest } - y[Books.INTEREST]
        val served = w.stations.filter { it != null && it.owner == 0 }.mapNotNull { w.stationTown(it!!)?.id }.toSet()
        return Out(
            w.companyValue(w.companies[0]), income, running, upkeep, interest, breakdowns, delivered,
            vehicleDays / 365.0, if (capSum > 0) (loadSum * 100 / capSum).toInt() else 0,
            townsStart, w.towns.sumOf { it.population }, served.sumOf { w.towns[it].population }, p.vehicles(), w.day,
            (System.nanoTime() - t0) / 1_000_000_000,
        )
    }
}
