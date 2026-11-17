package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClimateTest {
    private fun World.days(n: Int) = repeat(dayTicks * n) { step() }
    private fun u(b: Byte) = b.toInt() and 255

    /** A flat 64 by 64 map in [region] from [year] with the weather on, no towns, industries or trees. */
    private fun flat(year: Int, future: Boolean, region: Region = Region.NORTH, size: Int = 64): World {
        val w = World(NewGame(size, size, 3, year, region, towns = 0, industries = 0, weather = true, future = future))
        for (i in w.corners.indices) w.corners[i] = 3
        w.trees.fill(0)
        w.companies[0].money = 100_000_000
        return w
    }

    private val climateKinds = setOf(News.WARN_FIRE, News.WARN_FLOOD, News.WARN_THAW, News.BIG_FIRE, News.FLOOD, News.CLIMATE_DECADE)

    @Test
    fun theTrendStepsByTheDecadeOnlyWithFutureOn() {
        val on = flat(2025, true)
        val off = flat(2025, false)
        assertEquals(listOf(0, 1, 1, 2, 7, 7, 7), listOf(2029, 2030, 2039, 2040, 2090, 2100, 2200).map { on.trendSteps(it) })
        assertTrue(listOf(2029, 2030, 2090, 2200).all { off.trendSteps(it) == 0 })
        // Thawing ground: most of a north map, none of the mixed one.
        val north = (0 until 64).count { on.thawGround(10, it) }
        assertTrue(north in 30..55, "$north rows thaw")
        assertEquals(0, (0 until 64).count { flat(2025, true, Region.MIXED).thawGround(10, it) })
    }

    @Test
    fun fireAndFloodOddsByDecade() {
        // Every dice and fraction: how many of 65536 come up for a chance of 1 in 256, and of 3 in 65536.
        fun hits(odds: Int, steps: Int, wide: Boolean): Int {
            var n = 0
            for (d in 0 until (if (wide) 65536 else 256)) for (f in 0 until 256) if (trendHit(d, f, odds, steps)) n++
            return n
        }
        val washouts = (0..TREND_MOST).map { hits(WASHOUT_ODDS, it, false) }
        assertEquals(listOf(256, 320, 384, 448, 512, 576, 640, 704), washouts)
        val fires = (0..TREND_MOST).map { hits(3, it, true) }
        assertEquals((0..TREND_MOST).map { 3 * 256 * trendOdds(it) / 100 }, fires)
        println("Washout and fire odds by decade, per cent of today's: " + (0..TREND_MOST).map { trendOdds(it) })
    }

    @Test
    fun lightningLightsMoreFiresByTheDecade() {
        // A hot dry forest, each day's fires put out before the next so only lightning lights them.
        fun lit(year: Int, future: Boolean): Int {
            val w = flat(year, future, Region.MIXED, 128)
            for (i in 0 until w.width * w.height) w.trees[i] = Trees.pack(Trees.PINE, 3).toByte()
            var n = 0
            repeat(120) {
                w.weather.fronts.clear()
                w.weather.anomaly = 500
                w.fire.fill(0)
                for (i in 0 until w.width * w.height) { w.weather.wet[i] = 0; w.weather.snow[i] = 0 }
                w.days(1)
                n += (0 until w.width * w.height).count { u(w.fire[it]) == FIRE_DAYS }
            }
            return n
        }
        val off = lit(2095, false)
        val early = lit(2035, true)
        val late = lit(2095, true)
        println("Fires lit in 120 hot dry days: future off $off, 2030s $early, 2090s $late")
        assertTrue(off > 10, "only $off")
        assertTrue(late > off * 2, "$late against $off")
        assertTrue(early in off..late, "$early not between $off and $late")
    }

    /** Runs a north line from [year] for a year with future [future], giving tile-days on a slow order and washouts. */
    private fun thawYear(year: Int, future: Boolean): Pair<Int, Int> {
        val w = flat(year, future)
        for (y in 6..26 step 4) w.apply(Command.Rail(0, 4, y, 59, y, Track.X, remove = false))
        val line = (0 until w.width * w.height).filter { w.track[it].toInt() != 0 }
        assertTrue(line.size > 300)
        assertTrue(line.all { w.thawGround(it % w.width, it / w.width) })
        var slow = 0
        repeat(365) {
            w.days(1)
            slow += line.count { w.trackLimit(it) == WORN_KMH || w.trackLimit(it) == VERY_WORN_KMH }
        }
        return slow to w.chronicle.count { it.kind == News.WASHOUT }
    }

    @Test
    fun thawWearAndWashoutsRiseByTheDecade() {
        val off = thawYear(2095, false)
        val by = listOf(2035, 2055, 2075, 2095).map { thawYear(it, true) }
        println("Thaw on a north line, slow-order tile-days and washouts a year: future off 2090s $off, on 2030s to 2090s by 20 years $by")
        assertEquals(0 to 0, off)
        for (k in 1 until by.size) assertTrue(by[k].first >= by[k - 1].first, "$by")
        assertTrue(by.last().first > by.first().first)
        assertTrue(by.last().second > 0, "no washouts in the 2090s")
        assertTrue(by.last().second >= by.first().second)
    }

    @Test
    fun lakesFreezeLaterAndThawSoonerByDecade() {
        for (region in Region.values()) {
            val s = (2020..2100 step 10).map { iceSeason(region, it) }
            println("$region lakes freeze, thaw, frozen and open days, 2020s to 2100s: " + s.map { "${it.freezes}/${it.thaws}/${it.frozenDays}/${it.openDays}" })
            for (k in 1 until s.size) assertTrue(s[k].frozenDays <= s[k - 1].frozenDays, "$region ${s.map { it.frozenDays }}")
        }
        val n = (2020..2100 step 10).map { iceSeason(Region.NORTH, it) }
        assertTrue(n.first().frozenDays - n.last().frozenDays >= 10)
        assertTrue(n.last().freezes > n.first().freezes)
        // The decade line against the 2020s.
        val line = climateLine(Region.NORTH, 2070)
        val then = iceSeason(Region.NORTH, 2025)
        assertEquals(n[5].freezes - then.freezes, ClimateLine.later(line))
        assertEquals(then.thaws - n[5].thaws, ClimateLine.sooner(line))
        assertEquals(ClimateLine.later(line) + ClimateLine.sooner(line), ClimateLine.open(line))
    }

    @Test
    fun theChronicleTellsTheTrendEachDecade() {
        fun told(future: Boolean): List<Story> {
            val w = flat(2039, future, size = 32)
            while (Calendar.year(w.startYear, w.day) < 2040) w.days(1)
            w.days(1)
            return w.chronicle.filter { it.kind == News.CLIMATE_DECADE }
        }
        val on = told(true)
        assertEquals(1, on.size)
        assertEquals(climateLine(Region.NORTH, 2040), on[0].about)
        assertTrue(told(false).isEmpty())
    }

    @Test
    fun onlyRainWashoutsCountTowardTheFloodStory() {
        val w = flat(2050, true, size = 32)
        w.bigFireOrFlood(ByteArray(w.width * w.height), BIG_FLOOD - 1, 5)
        assertEquals(0, w.chronicle.count { it.kind == News.FLOOD })
        w.bigFireOrFlood(ByteArray(w.width * w.height), BIG_FLOOD, 5)
        assertEquals(1, w.chronicle.count { it.kind == News.FLOOD })
    }

    @Test
    fun aBigFireIsToldOnceWhileTheCountHoversRoundTheLine() {
        val w = flat(2050, true, size = 32)
        val none = ByteArray(w.width * w.height)
        repeat(BIG_FIRE + 5) { w.fire[it] = 3 }
        w.bigFireOrFlood(none, 0, -1)
        assertEquals(1, w.chronicle.count { it.kind == News.BIG_FIRE })
        // Even with the news list gone, the Chronicle holds it back.
        w.news.clear()
        w.bigFireOrFlood(none, 0, -1)
        assertEquals(1, w.chronicle.count { it.kind == News.BIG_FIRE })
        // Chronicle emptied, news still holds it back.
        w.chronicle = emptyList()
        w.news += News(News.BIG_FIRE, 0, w.tick)
        w.bigFireOrFlood(none, 0, -1)
        assertEquals(0, w.chronicle.count { it.kind == News.BIG_FIRE })
    }

    @Test
    fun burntForestGrowsBackWithFutureOn() {
        fun regrown(future: Boolean): Int {
            val w = flat(2050, future)
            w.fire.fill(BURNT_FROM.toByte())
            w.days(365)
            return (0 until w.width * w.height).count { Trees.count(w.trees[it].toInt()) > 0 && w.fire[it].toInt() == 0 }
        }
        val on = regrown(true)
        println("Burnt tiles grown back in a year of 4096: $on")
        assertTrue(on in 500..1500, "$on")
        assertEquals(0, regrown(false))
    }

    @Test
    fun theForecastWarnsOfFireFloodAndThawOnALine() {
        fun found(future: Boolean, setUp: (World) -> Unit): Set<Int> {
            val w = flat(2095, future, Region.MIXED)
            w.apply(Command.Rail(0, 4, 30, 59, 30, Track.X, remove = false))
            w.weather.fronts.clear()
            setUp(w)
            return w.warnFound(w.outlook(WARN_DAYS))!!.keys.map { (it ushr 40).toInt() }.toSet()
        }
        val fire: (World) -> Unit = { w ->
            for (x in 0 until w.width) w.trees[w.index(x, 31)] = Trees.pack(Trees.PINE, 3).toByte()
            w.weather.anomaly = 400
            for (i in 0 until w.width * w.height) { w.weather.wet[i] = 0; w.weather.snow[i] = 0 }
        }
        assertTrue(News.WARN_FIRE in found(true, fire))
        assertFalse(News.WARN_FIRE in found(false, fire))
        val flood: (World) -> Unit = { w ->
            for (x in 0 until w.width) for (y in 32..33) w.carveWater(Area(w).rect(x, y, x, y), rough = 0, salt = 1)
            for (i in 0 until w.width * w.height) w.weather.wet[i] = 220.toByte()
            w.weather.fronts += Front(Front.LOW, 32 * 256, 30 * 256, 0, 0, 40, 6000, 0, 10, false)
        }
        assertTrue(News.WARN_FLOOD in found(true, flood))
        assertFalse(News.WARN_FLOOD in found(false, flood))
        // A north line in summer, one slump from a slow order.
        fun thaw(future: Boolean): Set<Int> {
            val w = flat(2095, future)
            w.apply(Command.Rail(0, 4, 10, 59, 10, Track.X, remove = false))
            while (Calendar.dayOfYear(w.startYear, w.day) < 190) w.days(1)
            w.weather.fronts.clear()
            for (i in 0 until w.width * w.height) if (w.track[i].toInt() != 0) { w.condition[i] = (WORN + 10).toByte(); w.weather.frost[i] = 0 }
            return w.warnFound(w.outlook(WARN_DAYS))!!.keys.map { (it ushr 40).toInt() }.toSet()
        }
        assertTrue(News.WARN_THAW in thaw(true))
        assertFalse(News.WARN_THAW in thaw(false))
    }

    @Test
    fun aFutureOnGameSavesAndPlaysOnTheSame() {
        val w = World(NewGame(96, 96, 5, 2088, Region.NORTH, towns = 4, industries = 4, rivals = 1, future = true))
        w.companies[0].money = 100_000_000
        w.apply(Command.Rail(0, 4, 20, 90, 20, Track.X, remove = false))
        repeat(200) { w.days(1) }
        val back = Save.read(Save.write(w))
        assertEquals(w.checksum(), back.checksum())
        repeat(600) { w.days(1); back.days(1) }
        assertEquals(w.checksum(), back.checksum())
        assertTrue(w.chronicle.any { it.kind == News.CLIMATE_DECADE })
    }

    @Test
    fun futureOffTellsNoneOfIt() {
        val w = World(NewGame(96, 96, 5, 2088, Region.NORTH, towns = 4, industries = 4, rivals = 1, future = false))
        w.companies[0].money = 100_000_000
        w.apply(Command.Rail(0, 4, 20, 90, 20, Track.X, remove = false))
        repeat(800) { w.days(1) }
        assertTrue(w.chronicle.none { it.kind in climateKinds })
    }
}
