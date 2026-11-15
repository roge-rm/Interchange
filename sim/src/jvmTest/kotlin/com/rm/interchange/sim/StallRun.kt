package com.rm.interchange.sim

import java.io.File
import kotlin.test.Test

/**
 * Loads the save -Pinterchange.stallSave names and runs it a calendar day at a time, -Pinterchange.stallDays days
 * (default 40), printing each day's time in ms and the world checksum. Days over 200 ms, or with a step over
 * -Pinterchange.stallStep ms, get a stack sample of the sim thread every 2 ms, the top frames in the sim printed by
 * count. With -Pinterchange.stallWarm it runs the days once first from the same save, so the timed run is warmed up.
 * Each day also prints its longest single step, and the end each rival's routes, rail lines and money. Skipped without
 * the save.
 */
class StallRun {
    @Test
    fun run() {
        val path = System.getProperty("interchange.stallSave") ?: return
        val days = Integer.getInteger("interchange.stallDays", 40)
        // Days whose longest step is over this many ms get their stack samples printed too.
        val stepMs = Integer.getInteger("interchange.stallStep", Int.MAX_VALUE)
        if (System.getProperty("interchange.stallWarm") != null) {
            val warm = Save.read(File(path).readBytes())
            val end = warm.day + days
            while (warm.day < end) warm.step()
        }
        val w = Save.read(File(path).readBytes())
        val main = Thread.currentThread()
        val sampling = java.util.concurrent.atomic.AtomicBoolean(false)
        val counts = HashMap<String, Int>()
        val sampler = Thread {
            while (true) {
                if (sampling.get()) {
                    val st = main.stackTrace
                    val key = st.filter { it.className.startsWith("com.rm.interchange") && !it.className.contains("StallRun") }
                        .take(6).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                    synchronized(counts) { counts[key] = (counts[key] ?: 0) + 1 }
                }
                Thread.sleep(2)
            }
        }.apply { isDaemon = true; start() }
        var total = 0L
        var longest = 0L
        repeat(days) {
            val start = w.day
            synchronized(counts) { counts.clear() }
            sampling.set(true)
            val t0 = System.nanoTime()
            var most = 0L
            while (w.day == start) {
                val s0 = System.nanoTime()
                w.step()
                most = maxOf(most, System.nanoTime() - s0)
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            longest = maxOf(longest, most)
            sampling.set(false)
            total += ms
            println("STALL day ${w.day} ${Calendar.year(w.settings.startYear, start)}-${Calendar.month(w.settings.startYear, start) + 1}-${Calendar.dayOfMonth(w.settings.startYear, start)} ${ms} ms max ${most / 100_000 / 10.0} ms checksum ${w.checksum()}")
            if (ms > 200 || most / 1_000_000 > stepMs) synchronized(counts) {
                for ((k, n) in counts.entries.sortedByDescending { it.value }.take(12)) println("STALL   $n  $k")
            }
        }
        println("STALL total $total ms, longest step ${longest / 100_000 / 10.0} ms")
        for (r in w.rivals) {
            val c = w.companies[r.company]
            println("STALL rival ${r.company} routes ${r.routes.size} rail ${r.routes.count { it.rail }} money ${c.money} loan ${c.loan}")
        }
        sampler.interrupt()
    }
}
