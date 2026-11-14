package com.rm.interchange.sim

import java.io.File
import kotlin.test.Test

/**
 * Loads the save -Pinterchange.stallSave names and runs it a calendar day at a time, -Pinterchange.stallDays days
 * (default 40), printing each day's time in ms and the world checksum. Days over 200 ms get a stack sample of the sim
 * thread every 2 ms, the top frames in the sim printed by count. With -Pinterchange.stallWarm it runs the days once first
 * from the same save, so the timed run is warmed up. Skipped without the save.
 */
class StallRun {
    @Test
    fun run() {
        val path = System.getProperty("interchange.stallSave") ?: return
        val days = Integer.getInteger("interchange.stallDays", 40)
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
        repeat(days) {
            val start = w.day
            synchronized(counts) { counts.clear() }
            sampling.set(true)
            val t0 = System.nanoTime()
            while (w.day == start) w.step()
            val ms = (System.nanoTime() - t0) / 1_000_000
            sampling.set(false)
            total += ms
            println("STALL day ${w.day} ${Calendar.year(w.settings.startYear, start)}-${Calendar.month(w.settings.startYear, start) + 1}-${Calendar.dayOfMonth(w.settings.startYear, start)} ${ms} ms checksum ${w.checksum()}")
            if (ms > 200) synchronized(counts) {
                for ((k, n) in counts.entries.sortedByDescending { it.value }.take(12)) println("STALL   $n  $k")
            }
        }
        println("STALL total $total ms")
        sampler.interrupt()
    }
}
