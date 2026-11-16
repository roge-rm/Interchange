package com.rm.interchange.sim

import java.io.File
import kotlin.test.Test

/**
 * Times [workOutLinks] on the saves -Pinterchange.linkSaves names, comma separated: each loaded, run
 * -Pinterchange.linkDays work days (default 28), then worked out 50 times to warm up and 500 timed, printing the
 * mean and slowest in microseconds. Skipped without the saves.
 */
class LinkTiming {
    @Test
    fun run() {
        val paths = System.getProperty("interchange.linkSaves") ?: return
        val days = Integer.getInteger("interchange.linkDays", 28)
        for (path in paths.split(',')) {
            val w = Save.read(File(path).readBytes())
            repeat(days * Calendar.TICKS_PER_DAY) { w.step() }
            repeat(50) { w.workOutLinks() }
            var total = 0L
            var most = 0L
            repeat(500) {
                val t0 = System.nanoTime()
                w.workOutLinks()
                val ns = System.nanoTime() - t0
                total += ns
                most = maxOf(most, ns)
            }
            println("LINKS ${File(path).name} carriers ${w.carriers().size} stations ${w.stations.count { it != null }} mean ${total / 500 / 1000} us max ${most / 1000} us")
        }
    }
}
