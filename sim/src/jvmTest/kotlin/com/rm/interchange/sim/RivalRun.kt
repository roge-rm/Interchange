package com.rm.interchange.sim

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test

/**
 * Computer companies at each difficulty over long runs with nobody playing (company 0 does nothing), written to
 * build/balance/rivals.md. Slow, so it runs only when asked, with the years to play:
 * `./gradlew :sim:jvmTest --tests '*RivalRun*' -Pinterchange.rivalYears=20`, and optionally interchange.rivalStarts,
 * rivalLengths, rivalSeeds, rivalLevels (0 easy, 1 normal, 2 hard) and rivalCount as comma lists or a number.
 */
class RivalRun {
    @Test
    fun table() {
        val years = (System.getProperty("interchange.rivalYears") ?: return).toInt()
        val starts = list("interchange.rivalStarts", "1836,1900,1950,2000").map { it.toInt() }
        val lengths = list("interchange.rivalLengths", "1,10").map { it.toInt() }
        val seeds = list("interchange.rivalSeeds", "1,2,3").map { it.toLong() }
        val levels = list("interchange.rivalLevels", "0,1,2").map { it.toInt() }
        val count = (System.getProperty("interchange.rivalCount") ?: "3").toInt()
        class Job(val level: Int, val start: Int, val length: Int, val seed: Long)
        val jobs = levels.flatMap { v -> starts.flatMap { s -> lengths.flatMap { l -> seeds.map { Job(v, s, l, it) } } } }
        val pool = Executors.newFixedThreadPool(minOf(jobs.size, 8))
        val done = pool.invokeAll(jobs.map { j -> Callable { j to run(j.seed, j.start, j.length, j.level, count, years) } }).map { it.get() }
        pool.shutdown()
        val names = listOf("easy", "normal", "hard")
        val out = StringBuilder("# Rivals\n\n")
        out.append("A 256 by 256 map, default settings, $count rivals, $years calendar years. Company 0 is the human one and does nothing. ")
        out.append("Per rival: routes open as bus/truck/rail, routes given up as bus/truck/rail, vehicles, money and loan in dollars, value (money less loan plus vehicles less start money), ")
        out.append("profit last year (income less running, upkeep and interest), status (ok, trouble in months, GONE). Tried is town pairs and industries it has given up on, in all. ")
        out.append("Bus routes include feeder buses to railway stations. Trains, passing loops and feeders are counted in the runs and summed by level at the end.\n\n")
        out.append("## Summary at the end\n\n")
        out.append("Median (min to max) over every rival of every run at that level and start year. Given up is all the routes given up by bus/truck/rail, over all those rivals.\n\n")
        out.append("| level | start | day length | routes | rail | trains | vehicles | value | given up | gone | in trouble | runs |\n|---|---|---|---|---|---|---|---|---|---|---|---|\n")
        for (v in levels) for (s in starts) for (l in lengths) {
            val rs = done.filter { it.first.level == v && it.first.start == s && it.first.length == l }.flatMap { it.second.last().rivals }
            if (rs.isEmpty()) continue
            fun spread(f: (Snap) -> Long) = rs.map(f).sorted().let { "${it[it.size / 2]} (${it.first()} to ${it.last()})" }
            out.append("| ${names[v]} | $s | $l | ${spread { it.open.sum().toLong() }} | ${spread { it.open[2].toLong() }} | ${spread { it.trains.toLong() }} | ${spread { it.vehicles.toLong() }} | ${spread { it.value }} | ${givenUp(rs)} | ${rs.count { it.gone } * 100 / rs.size}% | ${rs.count { it.trouble > 0 && !it.gone } * 100 / rs.size}% | ${rs.size} |\n")
        }
        out.append("\n## Summary by level\n\n| level | routes | rail | trains | vehicles | value | given up | gone | in trouble |\n|---|---|---|---|---|---|---|---|---|\n")
        for (v in levels) {
            val rs = done.filter { it.first.level == v }.flatMap { it.second.last().rivals }
            if (rs.isEmpty()) continue
            fun spread(f: (Snap) -> Long) = rs.map(f).sorted().let { "${it[it.size / 2]} (${it.first()} to ${it.last()})" }
            out.append("| ${names[v]} | ${spread { it.open.sum().toLong() }} | ${spread { it.open[2].toLong() }} | ${spread { it.trains.toLong() }} | ${spread { it.vehicles.toLong() }} | ${spread { it.value }} | ${givenUp(rs)} | ${rs.count { it.gone } * 100 / rs.size}% | ${rs.count { it.trouble > 0 && !it.gone } * 100 / rs.size}% |\n")
        }
        out.append("\nIn all at the end, by level: ")
        out.append(levels.joinToString("; ") { v ->
            val rs = done.filter { it.first.level == v }.flatMap { it.second.last().rivals }
            "${names[v]} ${rs.sumOf { it.open[2] }} railways, ${rs.sumOf { it.trains }} trains, ${rs.sumOf { it.loops }} loops, ${rs.sumOf { it.feeders }} feeders"
        })
        out.append(".\n\n## Runs\n\n")
        for ((j, r) in done) {
            out.append("### ${names[j.level]}, ${j.start}, day length ${j.length}, seed ${j.seed}\n\n")
            out.append("| year | rival | open | given up | trains | loops | feeders | vehicles | money | loan | value | profit | tried | status |\n|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n")
            for (m in r.marks) for ((i, s) in m.rivals.withIndex()) {
                out.append("| ${m.year} | ${i + 1} | ${s.open.joinToString("/")} | ${s.failed.joinToString("/")} | ${s.trains} | ${s.loops} | ${s.feeders} | ${s.vehicles} | ${s.money} | ${s.loan} | ${s.value} | ${s.profit} | ${s.tried} | ${if (s.gone) "GONE" else if (s.trouble > 0) "trouble ${s.trouble}" else "ok"} |\n")
            }
            out.append("\n")
        }
        val dir = File("build/balance").apply { mkdirs() }
        File(dir, "rivals.md").writeText(out.toString())
    }

    private fun givenUp(rs: List<Snap>) = (0 until 3).joinToString("/") { k -> rs.sumOf { it.failed[k] }.toString() }

    private fun list(key: String, default: String) = (System.getProperty(key) ?: default).split(",").map { it.trim() }

    /** One rival's state: routes open and given up by bus, truck and rail. */
    class Snap(
        val open: IntArray, val failed: IntArray, val vehicles: Int, val money: Long, val loan: Long, val value: Long,
        val profit: Long, val tried: Int, val trouble: Int, val gone: Boolean, val trains: Int, val loops: Int, val feeders: Int,
    )

    class Mark(val year: Int, val rivals: List<Snap>)

    class Result(val marks: List<Mark>) {
        fun last() = marks.last()
    }

    companion object {
        /** 0 bus, 1 truck, 2 rail. */
        private fun kind(r: RivalRoute) = if (r.rail) 2 else if (r.freight) 1 else 0


        private fun snap(w: World, r: Rival, given: IntArray): Snap {
            val c = w.companies[r.company]
            val open = IntArray(3)
            for (route in r.routes) open[kind(route)]++
            val vehicles = w.trains.count { it != null && it.owner == c.id } + w.roadVehicles.count { it != null && it.owner == c.id } +
                w.ships.count { it != null && it.owner == c.id } + w.aircraft.count { it != null && it.owner == c.id }
            // Costs are booked as negative amounts.
            val profit = c.lastYear[Books.INCOME] + c.lastYear[Books.RUNNING] + c.lastYear[Books.UPKEEP] + c.lastYear[Books.INTEREST]
            val trains = w.trains.count { it != null && it.owner == c.id }
            return Snap(open, given.copyOf(), vehicles, c.money, c.loan, w.companyValue(c), profit, r.failed.size, c.trouble, c.gone, trains, r.routes.sumOf { it.loops.size }, r.routes.count { it.feeder })
        }

        fun run(seed: Long, start: Int, length: Int, level: Int, count: Int, years: Int): Result {
            val w = World(NewGame(256, 256, seed, start, dayLength = length, rivals = count, rivalLevel = level))
            val marks = ArrayList<Mark>()
            // Routes given up so far, by kind: ones that leave the rival's list while it's still going.
            val given = List(w.rivals.size) { IntArray(3) }
            val running = List(w.rivals.size) { HashSet<RivalRoute>() }
            // A rival bought out leaves w.rivals, so keep them all.
            val all = w.rivals.toList()
            fun count() {
                for ((i, r) in all.withIndex()) {
                    if (w.companies[r.company].gone) continue
                    val now = r.routes.toSet()
                    for (route in running[i]) if (route !in now) given[i][kind(route)]++
                    running[i].clear()
                    running[i] += now
                }
            }
            fun lookAll(): Mark {
                count()
                return Mark(Calendar.year(w.startYear, w.day), all.mapIndexed { i, r -> snap(w, r, given[i]) })
            }
            marks += lookAll()
            val first = Calendar.year(w.startYear, w.day)
            val end = first + years
            while (Calendar.year(w.startYear, w.day) < end) {
                val before = Calendar.year(w.startYear, w.day)
                w.step()
                val now = Calendar.year(w.startYear, w.day)
                if (now != before && ((now - first) % 5 == 0 || now == end)) marks += lookAll()
                else if (w.tick % w.dayTicks == 0L) count()
            }
            if (marks.last().year != end) marks += lookAll()
            return Result(marks)
        }
    }
}
