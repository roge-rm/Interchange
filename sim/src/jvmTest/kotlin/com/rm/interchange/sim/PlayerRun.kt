package com.rm.interchange.sim

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test

/**
 * The scripted [Player] through start years and day lengths, 20 calendar years each on a few seeds, written to
 * build/balance/player.md. Slow, so it runs only when asked, with the years to play:
 * `./gradlew :sim:jvmTest --tests '*PlayerRun*' -Pinterchange.playerYears=20`, and optionally
 * interchange.playerStarts, playerLengths and playerSeeds as comma lists.
 */
class PlayerRun {
    @Test
    fun table() {
        val years = (System.getProperty("interchange.playerYears") ?: return).toInt()
        val starts = (System.getProperty("interchange.playerStarts") ?: "1836,1900,1950,2000").split(",").map { it.trim().toInt() }
        val lengths = (System.getProperty("interchange.playerLengths") ?: "1,10").split(",").map { it.trim().toInt() }
        val seeds = (System.getProperty("interchange.playerSeeds") ?: "1,2,3").split(",").map { it.trim().toLong() }
        class Job(val start: Int, val length: Int, val seed: Long)
        val jobs = starts.flatMap { s -> lengths.flatMap { l -> seeds.map { Job(s, l, it) } } }
        val pool = Executors.newFixedThreadPool(minOf(jobs.size, 10))
        val done = pool.invokeAll(jobs.map { j -> Callable { val t = System.nanoTime(); j to (Player.run(j.seed, j.start, j.length, years) to (System.nanoTime() - t) / 1_000_000_000) } })
        pool.shutdown()
        val out = StringBuilder("# Scripted player\n\n")
        out.append("Each calendar year's books for company 0 on a 256 by 256 map, default settings, no rivals. Money and value are on 31 December; ")
        out.append("income, running, upkeep and interest are the year's. Profit is income less running, upkeep and interest. ")
        out.append("Work years are calendar years times day length.\n\n")
        for (f in done) {
            val (j, r) = f.get()
            val (p, secs) = r
            out.append("## ${j.start}, day length ${j.length}, seed ${j.seed}\n\n")
            out.append("Routes: ${p.buses} bus, ${p.railways} rail, ${p.trucks} truck. Start money ${p.startMoney}, loan limit ${p.startLimit}. ${secs}s.\n\n")
            out.append("| year | money | loan | value | income | running | upkeep | interest | built | bought | profit | routes | vehicles |\n")
            out.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n")
            for (y in p.years) {
                out.append("| ${y.year} | ${y.money} | ${y.loan} | ${y.value} | ${y.income} | ${y.running} | ${y.upkeep} | ${y.interest} | ${y.built} | ${y.bought} | ${y.profit} | ${y.routes} | ${y.vehicles} |\n")
            }
            out.append("\n")
        }
        val dir = File("build/balance").apply { mkdirs() }
        File(dir, "player.md").writeText(out.toString())
    }
}
