package com.rm.interchange.sim

import java.io.File
import java.lang.management.ManagementFactory
import kotlin.test.Test

/**
 * What a big map costs on the host: heap held by the World, tick time, save and load, and the checksum, written to
 * build/balance/bigmap.md. Slow and needs a big heap, so it runs only when asked:
 * `./gradlew :sim:jvmTest --tests '*BigMapRun*' -Pinterchange.bigmap=1024,4096`, with optionally
 * interchange.bigmapDays (calendar days, 365), bigmapLengths (day lengths, 1,10), bigmapSeed (1) and bigmapRivals (6, hard).
 */
class BigMapRun {
    @Test
    fun table() {
        val sizes = (System.getProperty("interchange.bigmap") ?: return).split(",").map { it.trim().toInt() }
        val days = (System.getProperty("interchange.bigmapDays") ?: "365").toInt()
        val lengths = (System.getProperty("interchange.bigmapLengths") ?: "1,10").split(",").map { it.trim().toInt() }
        val seed = (System.getProperty("interchange.bigmapSeed") ?: "1").toLong()
        val rivals = (System.getProperty("interchange.bigmapRivals") ?: "6").toInt()
        val out = StringBuilder("# Big maps\n\n")
        out.append("Default settings, seed $seed, $rivals hard rivals, $days calendar days. Heap in MB after a full GC. ")
        out.append("Tick and day times in ms, mean and p99. Body is the save before packing. Peaks are the heap's most during the save or load, less what was in use before the world was made (so they include the world).\n\n")
        out.append("| size | length | towns | industries | make s | world MB | tick mean | tick p99 | day mean | day p99 | save s | save peak MB | save MB | body MB | load s | load peak MB | checksum | after load | body hash |\n")
        out.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n")
        for (size in sizes) for (length in lengths) {
            val base = used()
            var t0 = System.nanoTime()
            var w: World? = World(NewGame(size, size, seed, dayLength = length, rivals = rivals, rivalLevel = RivalLevel.HARD))
            val make = (System.nanoTime() - t0) / 1e9
            val world = (used() - base) / 1e6
            val ticks = days.toLong() * w!!.dayTicks
            val times = LongArray(ticks.toInt())
            val profile = if (System.getProperty("interchange.bigmapProfile") != null) Profile(Thread.currentThread()).also { it.start() } else null
            for (i in times.indices) {
                t0 = System.nanoTime()
                w.step()
                times[i] = System.nanoTime() - t0
            }
            profile?.let { it.done = true; it.join(); out.append(it.report(size, length)) }
            val per = w.dayTicks.toInt()
            val dayTimes = LongArray(times.size / per) { d -> (0 until per).sumOf { times[d * per + it] } }
            val sum = w.checksum()
            val towns = w.towns.size
            val inds = w.industries.size
            used()
            resetPeak()
            t0 = System.nanoTime()
            val bytes = Save.write(w)
            val save = (System.nanoTime() - t0) / 1e9
            val savePeak = (peak() - base) / 1e6
            val body = Squeeze.unpack(bytes.copyOfRange(bytes.size - packedSize(bytes), bytes.size))
            val bodyHash = body.contentHashCode()
            val bodyMb = body.size / 1e6
            w = null
            used()
            resetPeak()
            t0 = System.nanoTime()
            val back = Save.read(bytes)
            val load = (System.nanoTime() - t0) / 1e9
            val loadPeak = (peak() - base) / 1e6
            val after = back.checksum()
            out.append("| $size | $length | $towns | $inds | ${"%.1f".format(make)} | ${"%.0f".format(world)} | ${ms(mean(times))} | ${ms(p99(times))} | ${ms(mean(dayTimes))} | ${ms(p99(dayTimes))} | ")
            out.append("${"%.2f".format(save)} | ${"%.0f".format(savePeak)} | ${"%.1f".format(bytes.size / 1e6)} | ${"%.0f".format(bodyMb)} | ${"%.2f".format(load)} | ${"%.0f".format(loadPeak)} | ${sum.toString(16)} | ${if (after == sum) "same" else after.toString(16)} | ${bodyHash.toString(16)} |\n")
            println("bigmap $size x$length done: checksum ${sum.toString(16)}")
        }
        val dir = File("build/balance").apply { mkdirs() }
        File(dir, "bigmap.md").writeText(out.toString())
        println(out)
    }

    /** The packed body's length: it's last in the file after its uvar length, so read the head as [Save.heading] does. */
    private fun packedSize(data: ByteArray): Int {
        val r = ByteReader(data)
        repeat(4) { r.byte() }
        val version = r.int()
        if (version >= 30 && r.bool()) { r.string(); r.string(); r.int() }
        return r.bytes().size
    }

    private fun used(): Long {
        val mem = ManagementFactory.getMemoryMXBean()
        repeat(3) { System.gc() }
        return mem.heapMemoryUsage.used
    }

    /** Samples [target]'s stack every 2 ms and counts the innermost game frames, and each frame anywhere on the stack. */
    private class Profile(val target: Thread) : Thread() {
        @Volatile var done = false
        val top = HashMap<String, Int>()
        val any = HashMap<String, Int>()
        var samples = 0
        override fun run() {
            while (!done) {
                val st = target.stackTrace
                val game = st.filter { it.className.startsWith("com.rm.interchange.sim") && !it.className.contains("BigMapRun") }
                if (game.isNotEmpty()) {
                    samples++
                    val f = game.first()
                    top.merge("${f.className.substringAfterLast('.')}.${f.methodName}", 1, Int::plus)
                    for (g in game.map { "${it.className.substringAfterLast('.')}.${it.methodName}" }.toSet()) any.merge(g, 1, Int::plus)
                }
                sleep(2)
            }
        }
        fun report(size: Int, length: Int): String {
            val sb = StringBuilder("\nProfile $size x$length, $samples samples. Innermost: ")
            sb.append(top.entries.sortedByDescending { it.value }.take(15).joinToString(", ") { "${it.key} ${it.value * 100 / samples}%" })
            sb.append(". Anywhere: ")
            sb.append(any.entries.sortedByDescending { it.value }.take(30).joinToString(", ") { "${it.key} ${it.value * 100 / samples}%" })
            return sb.append(".\n\n").toString()
        }
    }

    private fun heapPools() = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == java.lang.management.MemoryType.HEAP }
    private fun resetPeak() = heapPools().forEach { it.resetPeakUsage() }
    private fun peak() = heapPools().sumOf { it.peakUsage.used }

    private fun mean(a: LongArray) = if (a.isEmpty()) 0.0 else a.average()
    private fun p99(a: LongArray) = if (a.isEmpty()) 0.0 else a.sorted()[(a.size * 99 / 100).coerceAtMost(a.size - 1)].toDouble()
    private fun ms(ns: Double) = "%.2f".format(ns / 1e6)
}
