package com.rm.interchange.sim

import kotlin.test.Test

class WeatherProbe {
    @Test
    fun probe() {
        if (System.getenv("PROBE") == null) return
        for (region in Region.entries) {
            val w = World(NewGame(256, 256, 5, 1900, region, towns = 1, industries = 0))
            val got = LongArray(12); val want = LongArray(12); val temp = LongArray(12); val snow = LongArray(12); val n = IntArray(12)
            repeat(365) {
                repeat(Calendar.TICKS_PER_DAY) { w.step() }
                val m = Calendar.month(1900, w.day)
                var s = 0L; var t = 0L; var sn = 0L; var k = 0
                for (y in 0 until w.height step 8) for (x in 0 until w.width step 8) { val sky = w.skyAt(x, y); s += sky.precipitation; t += sky.temperature; sn += w.weather.snow[w.index(x, y)].toInt() and 255; k++ }
                got[m] += s / k; temp[m] += t / k; snow[m] += sn / k; n[m]++
                want[m] += Climate.precipitation(region, 1900, Calendar.dayOfYear(1900, w.day)).toLong()
            }
            println("$region")
            println("  mm/month got  " + (0 until 12).joinToString(" ") { "%4d".format(got[it] / 100) })
            println("  mm/month want " + (0 until 12).joinToString(" ") { "%4d".format(want[it] / 100) })
            println("  temp          " + (0 until 12).joinToString(" ") { "%4d".format(temp[it] / n[it] / 10) })
            println("  snow cm       " + (0 until 12).joinToString(" ") { "%4d".format(snow[it] / n[it]) })
            println("  fronts ${w.weather.fronts.size}")
        }
    }
}
