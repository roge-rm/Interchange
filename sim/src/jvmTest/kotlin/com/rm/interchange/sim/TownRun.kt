package com.rm.interchange.sim

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test

/**
 * Towns and industries served and not, over calendar years at a few day lengths and both town growth settings,
 * written to build/balance/towns.md. A company with money to spare opens railways, bus lines and truck lines near
 * the biggest town at the start and adds buses where people wait; every other town and industry is left alone.
 * Slow, so it runs only when asked, with the years to play:
 * `./gradlew :sim:jvmTest --tests '*TownRun*' -Pinterchange.townYears=20`, and optionally interchange.townStart,
 * townLengths and townSeeds as comma lists.
 */
class TownRun {
    @Test
    fun table() {
        val years = (System.getProperty("interchange.townYears") ?: return).toInt()
        val start = (System.getProperty("interchange.townStart") ?: "1950").toInt()
        val lengths = (System.getProperty("interchange.townLengths") ?: "1,10").split(",").map { it.trim().toInt() }
        val seeds = (System.getProperty("interchange.townSeeds") ?: "1,2,3,4").split(",").map { it.trim().toLong() }
        class Job(val length: Int, val growth: TownGrowth, val seed: Long)
        val jobs = lengths.flatMap { l -> TownGrowth.entries.flatMap { g -> seeds.map { Job(l, g, it) } } }
        val pool = Executors.newFixedThreadPool(minOf(jobs.size, 10))
        val done = pool.invokeAll(jobs.map { j -> Callable { j to run(j.seed, start, j.length, j.growth, years) } }).map { it.get() }
        pool.shutdown()
        val out = StringBuilder("# Towns and industries\n\n")
        out.append("A 256 by 256 map from $start, default settings, no rivals. Company 0 has money to spare and opens up to two railways between the biggest towns 16 to 40 tiles apart, ")
        out.append("four bus lines and four truck lines from the biggest towns at the start, adding buses where people wait. ")
        out.append("Towns: mean population of those on a railway, on a bus line only, and the rest. Pickup is passengers picked up ")
        out.append("from a town's buildings per work month, in percent of its people. Supplied is the share of months its needs were met. ")
        out.append("Industries: mean level in sixteenths (16 at the start) of the primary ones served from the start and the rest, ")
        out.append("and how many of each have closed. Served share is the mean share of a served industry's cargo carried in a month.\n\n")
        out.append("## Summary, end against start\n\n")
        out.append("| day length | growth | seed | rail towns | bus towns | other towns | rail pickup | bus pickup | rail supplied | bus supplied | served share | served level | other level | served closed | other closed |\n")
        out.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n")
        for ((j, r) in done) {
            val a = r.years.first()
            val z = r.years.last()
            fun grow(i: Int) = if (r.towns[i] == 0) "-" else "${a.pop[i]} → ${z.pop[i]} (x${"%.2f".format(z.pop[i].toDouble() / a.pop[i].coerceAtLeast(1))})"
            out.append("| ${j.length} | ${j.growth} | ${j.seed} | ${grow(0)} | ${grow(1)} | ${grow(2)} | ${r.pickup(0)}% | ${r.pickup(1)}% | ${r.supplied(0)}% | ${r.supplied(1)}% | ${r.share}% | ")
            out.append("${a.level[0]} → ${z.level[0]} | ${a.level[1]} → ${z.level[1]} | ${z.closed[0]}/${r.industries[0]} | ${z.closed[1]}/${r.industries[1]} |\n")
        }
        out.append("\n")
        for ((j, r) in done) {
            out.append("## Day length ${j.length}, ${j.growth}, seed ${j.seed}\n\n")
            out.append("Towns: ${r.towns[0]} on rail, ${r.towns[1]} on buses, ${r.towns[2]} others. Primary industries: ${r.industries[0]} served, ${r.industries[1]} others. ${r.secs}s.\n\n")
            out.append("| year | rail pop | bus pop | other pop | rail pickup | bus pickup | served level | other level | served closed | other closed |\n")
            out.append("|---|---|---|---|---|---|---|---|---|---|\n")
            for (y in r.years) {
                out.append("| ${y.year} | ${y.pop[0]} | ${y.pop[1]} | ${y.pop[2]} | ${y.pickup[0]}% | ${y.pickup[1]}% | ${y.level[0]} | ${y.level[1]} | ${y.closed[0]} | ${y.closed[1]} |\n")
            }
            out.append("\n")
        }
        val dir = File("build/balance").apply { mkdirs() }
        File(dir, "towns.md").writeText(out.toString())
    }

    /** One year's look: by town group (rail, bus, other) and industry group (served, other). */
    class Year(val year: Int, val pop: IntArray, val pickup: IntArray, val level: IntArray, val closed: IntArray)

    class Result(val towns: IntArray, val industries: IntArray, val years: List<Year>, val months: IntArray, val suppliedMonths: IntArray, val share: Int, val secs: Long) {
        /** Mean pickup over the run, after the first year. */
        fun pickup(g: Int) = years.drop(1).map { it.pickup[g] }.average().takeIf { !it.isNaN() }?.toInt() ?: 0
        fun supplied(g: Int) = if (months[g] == 0) 0 else suppliedMonths[g] * 100 / months[g]
    }

    companion object {
        private const val RICH = 50_000_000L

        fun run(seed: Long, start: Int, length: Int, growth: TownGrowth, years: Int): Result {
            val t0 = System.nanoTime()
            val w = World(NewGame(256, 256, seed, start, dayLength = length, townGrowth = growth))
            val c = w.companies[0]
            c.money = RICH
            val home = w.towns.maxWithOrNull(compareBy<Town> { it.population }.thenBy { -it.id })?.id ?: 0
            val plan = Rival(0, home)
            // Railways between the biggest pairs of towns far enough apart, then bus lines and truck lines from the
            // biggest towns in turn as homes.
            val byPop = w.towns.sortedWith(compareByDescending<Town> { it.population }.thenBy { it.id })
            var rails = 0
            for (t1 in byPop) for (t2 in byPop) {
                if (rails >= 2 || t2.id == t1.id || plan.routes.any { it.townA in setOf(t1.id, t2.id) || it.townB in setOf(t1.id, t2.id) }) continue
                val d = maxOf(kotlin.math.abs(t1.x - t2.x), kotlin.math.abs(t1.y - t2.y))
                if (d !in 16..40) continue
                val serves = { s: Station, _: Boolean -> (w.stationPeople[s.id] ?: 0) > 0 }
                if (w.tryRail(c, railSite(t1, t2.x, t2.y), railSite(t2, t1.x, t1.y), Cargo.PASSENGERS, minOf(t1.population, t2.population) / 4, t1.id, t2.id, serves, payback = 0) { plan.routes += it } == Tried.BUILT) rails++
            }
            for (t in byPop.take(8)) {
                if (plan.routes.count { !it.rail && !it.freight } >= 4) break
                val p = Rival(0, t.id).also { it.routes += plan.routes; it.failed += plan.failed }
                w.newRoute(p, c, payback = 0)
                plan.routes.clear(); plan.routes += p.routes; plan.failed += p.failed
            }
            for (t in byPop.take(8)) {
                if (plan.routes.count { it.freight } >= 4) break
                val p = Rival(0, t.id).also { it.routes += plan.routes; it.failed += plan.failed }
                w.newFreightRoute(p, c, payback = 0)
                plan.routes.clear(); plan.routes += p.routes; plan.failed += p.failed
            }
            // Towns on a railway, then on buses only; industries a route loads at.
            val group = IntArray(w.towns.size) { 2 }
            for (r in plan.routes) if (!r.freight && !r.rail) { group[r.townA] = 1; group[r.townB] = 1 }
            for (r in plan.routes) if (!r.freight && r.rail) { group[r.townA] = 0; group[r.townB] = 0 }
            val primary = w.industries.filter { ind -> w.roster.industries[ind.kind].produces.any { it > 0 } }
            val served = plan.routes.filter { it.freight }.map { it.townA }.toSet()
            fun indGroup(ind: Industry) = if (ind.id in served) 0 else 1
            val towns = IntArray(3).also { n -> for (g in group) n[g]++ }
            val industries = IntArray(2).also { n -> for (ind in primary) n[indGroup(ind)]++ }
            val startTowns = w.towns.size
            val startIndustries = primary.map { it.id }.toSet()
            val out = ArrayList<Year>()
            val picked = LongArray(3)
            val months = IntArray(3)
            val suppliedMonths = IntArray(3)
            var shareSum = 0L
            var shareMonths = 0
            fun look() {
                val pop = IntArray(3)
                val pickup = IntArray(3)
                for (g in 0 until 3) {
                    val ts = w.towns.take(startTowns).filter { group[it.id] == g }
                    if (ts.isEmpty()) continue
                    pop[g] = ts.sumOf { it.population } / ts.size
                    val people = ts.sumOf { it.population }.coerceAtLeast(1)
                    // Picked up over the year, per work month, against the people now.
                    pickup[g] = (picked[g] * 100 / 12 / length / people).toInt()
                }
                picked.fill(0)
                val level = IntArray(2)
                val closed = IntArray(2)
                for (g in 0 until 2) {
                    val inds = w.industries.filter { it.id in startIndustries && indGroup(it) == g }
                    val open = inds.filter { !it.closed }
                    level[g] = if (open.isEmpty()) 0 else open.sumOf { it.level } / open.size
                    closed[g] = inds.count { it.closed }
                }
                out += Year(Calendar.year(w.startYear, w.day), pop, pickup, level, closed)
            }
            look()
            val end = Calendar.year(w.startYear, w.day) + years
            var nextThink = 0
            while (Calendar.year(w.startYear, w.day) < end) {
                val before = w.day
                w.step()
                if (w.workDay >= nextThink && w.tick % Calendar.TICKS_PER_DAY == 0L) {
                    nextThink = w.workDay + 30
                    c.money = RICH
                    for (r in plan.routes.toList()) w.growRoute(c, r)
                }
                if (w.day != before && Calendar.dayOfMonth(w.startYear, w.day) == 1) {
                    for (t in w.towns.take(startTowns)) {
                        val g = group[t.id]
                        picked[g] += (t.servedLastMonth - t.mailLastMonth).toLong()
                        if (g < 2) {
                            months[g]++
                            if (w.townSupplied(t)) suppliedMonths[g]++
                        }
                    }
                    for (ind in w.industries) if (ind.id in served && !ind.closed && ind.madeLastMonth.sum() > 0) {
                        shareSum += ind.movedLastMonth.sum() * 100L / ind.madeLastMonth.sum()
                        shareMonths++
                    }
                    if (Calendar.newYear(w.startYear, w.day)) look()
                }
            }
            return Result(towns, industries, out, months, suppliedMonths, (shareSum / shareMonths.coerceAtLeast(1)).toInt(), (System.nanoTime() - t0) / 1_000_000_000)
        }
    }
}
