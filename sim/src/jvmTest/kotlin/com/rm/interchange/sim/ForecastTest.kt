package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForecastTest {
    private fun World.days(n: Int) = repeat(Calendar.TICKS_PER_DAY * n) { step() }
    private fun u(b: Byte) = b.toInt() and 255

    private fun weathered(region: Region = Region.MIXED, seed: Long = 3) = World(NewGame(128, 128, seed, 1900, region, towns = 1, industries = 0))

    @Test
    fun aForecastLeavesTheWorldAndItsRngAlone() {
        val a = weathered()
        val b = weathered()
        val sum = a.checksum()
        val rng = a.rng.state
        repeat(1000) { k -> a.forecast(k % a.width, k * 7 % a.height) }
        assertEquals(sum, a.checksum())
        assertEquals(rng, a.rng.state)
        a.days(3)
        b.days(3)
        assertEquals(b.checksum(), a.checksum())
    }

    @Test
    fun aKnownFrontShowsUpOnTheDayItArrives() {
        val w = weathered(Region.MARITIMES)
        var found: Triple<Int, Int, Int>? = null
        for (tries in 0 until 60) {
            for (y in 0 until w.height step 4) {
                for (x in 0 until w.width step 4) {
                    if (w.skyAt(x, y).precipitation > 0) continue
                    val f = w.forecast(x, y, 3)
                    if (f[0].precipitation == 0 && f[1].precipitation > 0) { found = Triple(x, y, f[1].precipitation); break }
                }
                if (found != null) break
            }
            if (found != null) break
            w.days(1)
        }
        val (x, y, p) = found ?: error("no front coming anywhere")
        w.days(2)
        val now = w.skyAt(x, y).precipitation
        assertTrue(now >= p, "forecast $p, fell $now")
    }

    @Test
    fun confidenceDropsWithDays() {
        val w = weathered()
        for ((x, y) in listOf(10 to 10, 64 to 64, 120 to 30)) {
            val f = w.forecast(x, y)
            assertEquals(5, f.size)
            assertEquals(Confidence.HIGH, f.first().confidence)
            assertEquals(Confidence.LOW, f.last().confidence)
            for (d in 1 until f.size) assertTrue(f[d].confidence <= f[d - 1].confidence)
        }
    }

    @Test
    fun aWarningComesOnceForSnowOnThePlayersLineNotARivals() {
        val w = World(NewGame(64, 64, 1, 1900, Region.NORTH, towns = 0, industries = 0))
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.companies += Company(1, "Rival", 10_000_000)
        w.rivals += Rival(1, 0).also { it.thinkOn = Int.MAX_VALUE }
        w.apply(Command.Rail(0, 5, 20, 58, 20, Track.X, remove = false))
        w.apply(Command.Rail(1, 5, 24, 58, 24, Track.X, remove = false))
        for (i in 0 until w.width * w.height) w.weather.snow[i] = 0
        w.weather.fronts.clear()
        w.weather.anomaly = -100
        // A low coming in from the west, over both lines in two days.
        w.weather.fronts += Front(Front.LOW, -30 * 256, 22 * 256, 16 * 256, 0, 12, 6000, 0, 10, false)
        w.warnDay()
        fun warnings(company: Int) = w.news.count { it.kind == News.WARN_SNOW && Warning.company(it.about) == company }
        assertEquals(1, warnings(0))
        assertEquals(0, warnings(1))
        val n = w.news.first { it.kind == News.WARN_SNOW }
        assertEquals(2, Warning.days(n.about))
        assertEquals(Warning.NO_TOWN, Warning.town(n.about))
        w.warnDay()
        w.days(2)
        assertEquals(1, warnings(0))
        assertEquals(0, warnings(1))
        assertTrue(u(w.weather.snow[w.index(8, 20)]) >= WARN_SNOW, "the snow didn't come")
    }

    @Test
    fun warningsOnABigNetworkAreGroupedByCellAndTheSameOnACopy() {
        val w = World(NewGame(128, 128, 1, 1900, Region.NORTH, towns = 4, industries = 0))
        w.companies[0].money = 1_000_000_000
        for (y in 4 until 124 step 3) w.apply(Command.Rail(0, 2, y, 125, y, Track.X, remove = false))
        val laid = (0 until w.width * w.height).count { w.track[it].toInt() != 0 && w.owner[it].toInt() == 0 }
        assertTrue(laid > 2_000, "only $laid tiles laid")
        for (i in 0 until w.width * w.height) w.weather.snow[i] = 0
        w.weather.fronts.clear()
        w.weather.anomaly = -100
        w.weather.fronts += Front(Front.LOW, -40 * 256, 64 * 256, 60 * 256, 0, 20, 6000, 0, 10, false)
        val found = w.warnFound(w.outlook(WARN_DAYS))!!
        assertTrue(found.isNotEmpty(), "nothing found")
        // At most one finding per kind and cell, so naming towns is bounded by the map, not the line.
        val cells = (w.width / WARN_CELL) * (w.height / WARN_CELL)
        assertTrue(found.size <= 4 * cells, "${found.size} findings")
        val copy = Save.read(Save.write(w))
        w.warnDay()
        copy.warnDay()
        fun told(x: World) = x.news.filter { it.kind in News.WARN_SNOW..News.WARN_COLD }.map { it.kind to it.about }
        assertTrue(told(w).isNotEmpty())
        assertEquals(told(w), told(copy))
        assertEquals(w.warned, copy.warned)
    }

    @Test
    fun aLapsedWarningIsNotToldAgainForAFewDays() {
        val w = World(NewGame(64, 64, 1, 1900, Region.NORTH, towns = 0, industries = 0))
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Rail(0, 5, 20, 58, 20, Track.X, remove = false))
        for (i in 0 until w.width * w.height) w.weather.snow[i] = 0
        w.weather.fronts.clear()
        w.weather.anomaly = -100
        val low = Front(Front.LOW, -30 * 256, 22 * 256, 16 * 256, 0, 12, 6000, 0, 10, false)
        w.weather.fronts += low.copy()
        fun warnings() = w.news.count { it.kind == News.WARN_SNOW }
        w.warnDay()
        assertEquals(1, warnings())
        // Gone for a day, then back: not told again.
        w.weather.fronts.clear()
        w.warnDay()
        assertEquals(1, w.warned.size)
        for (k in w.warned.keys) w.warned[k] = w.day - 2
        w.weather.fronts += low.copy()
        w.warnDay()
        assertEquals(1, warnings())
        // Gone longer than the quiet days: dropped, and told when it comes back.
        for (k in w.warned.keys) w.warned[k] = w.day - WARN_QUIET - 1
        w.weather.fronts.clear()
        w.warnDay()
        assertTrue(w.warned.isEmpty())
        w.weather.fronts += low.copy()
        w.warnDay()
        assertEquals(2, warnings())
    }

    /** With [old], made without the settings older saves load without. */
    private fun line(old: Boolean = false): World {
        val g = NewGame(64, 64, 1, 1850, towns = 0, industries = 0, weather = false)
        val w = World(if (old) g.copy(stockpiles = false, contracts = false, handling = false, eraCosts = false) else g)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 39, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        w.apply(Command.Station(0, 36, 10, 39, 10, Track.X))
        return w
    }

    @Test
    fun priorityTrackIsMendedFirstAndKeptHigher() {
        val w = line()
        w.apply(Command.Priority(0, 3, 10, 20, 10, Track.X, road = false, on = true))
        assertEquals(18, w.priorityCount(0))
        for (x in 3..39) w.condition[w.index(x, 10)] = 60
        w.trackWorkDay()
        val fast = u(w.condition[w.index(15, 10)])
        val slow = u(w.condition[w.index(30, 10)])
        assertTrue(fast >= PRIORITY_FLOOR && fast > slow, "priority $fast, plain $slow")
        repeat(5) { w.trackWorkDay() }
        assertTrue(u(w.condition[w.index(15, 10)]) > u(w.condition[w.index(30, 10)]))
        w.days(3)
        assertTrue(w.companies[0].thisYear[Books.PRIORITY] < 0, "not charged")
        w.apply(Command.Priority(0, 3, 10, 10, 10, Track.X, road = false, on = false))
        assertEquals(10, w.priorityCount(0))
    }

    @Test
    fun priorityIsOnlyOnTheCompanysOwnTrackAndCapped() {
        val w = line()
        w.companies += Company(1, "Other", 10_000_000)
        w.apply(Command.Priority(1, 3, 10, 20, 10, Track.X, road = false, on = true))
        assertEquals(0, w.priorityCount(1))
        w.apply(Command.Priority(0, 3, 10, 9, 10, Track.X, road = false, on = true))
        assertEquals(7, w.priorityCount(0))
        for (y in 30 until 60) w.apply(Command.Rail(0, 0, y, 63, y, Track.X, remove = false))
        for (y in 30 until 60) w.apply(Command.Priority(0, 0, y, 63, y, Track.X, road = false, on = true))
        assertEquals(PRIORITY_MOST, w.priorityCount(0))
        // Lifted track loses its mark the next day.
        w.apply(Command.Rail(0, 3, 10, 9, 10, Track.X, remove = true))
        w.priorityDay()
        assertTrue(w.priorityTrack.keys.none { it / w.width == 10 })
    }

    @Test
    fun onlyLiveMarksAreChargedAndCountedAndALiftedTileLosesItsMark() {
        val w = line()
        w.apply(Command.Priority(0, 3, 10, 20, 10, Track.X, road = false, on = true))
        assertEquals(18, w.priorityCount(0))
        // Lifted and laid again the same day, the tiles come back unmarked.
        w.apply(Command.Rail(0, 3, 10, 9, 10, Track.X, remove = true))
        w.apply(Command.Rail(0, 3, 10, 9, 10, Track.X, remove = false))
        assertEquals(11, w.priorityCount(0))
        assertTrue((3..9).none { w.priorityTrack.containsKey(w.index(it, 10)) })
        // A mark left on a tile with no track isn't counted or charged.
        w.priorityTrack[w.index(50, 50)] = 0
        assertEquals(11, w.priorityCount(0))
        assertEquals(11 * PRIORITY_TRACK, w.priorityOwedToday()[0])
        w.companies[0].priorityOwed = 500
        w.restart(w.companies[0], news = false)
        assertEquals(0L, w.companies[0].priorityOwed)
        assertTrue(w.priorityTrack.isEmpty())
    }

    @Test
    fun priorityRoadIsPlowedMoreAndSaltedFurther() {
        val w = World(NewGame(64, 64, 1, 1960, Region.MIXED, towns = 0, industries = 0))
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Road(0, 5, 20, 58, 20, Surface.PAVED, remove = false))
        w.apply(Command.Road(0, 5, 30, 58, 30, Surface.PAVED, remove = false))
        w.apply(Command.Priority(0, 5, 20, 58, 20, 0, road = true, on = true))
        assertEquals(54, w.priorityCount(0))
        val first = (5..58).map { w.index(it, 20) }
        val plain = (5..58).map { w.index(it, 30) }
        var onFirst = 0
        var onPlain = 0
        repeat(60) {
            w.days(1)
            onFirst += first.count { u(w.weather.snow[it]) >= DEEP_ROAD_SNOW }
            onPlain += plain.count { u(w.weather.snow[it]) >= DEEP_ROAD_SNOW }
        }
        assertTrue(onPlain > 0, "no deep snow")
        assertTrue(onFirst < onPlain, "priority $onFirst, plain $onPlain")

        w.apply(Command.SaltDepot(0, 10, 21))
        w.apply(Command.SaltDepot(0, 10, 31))
        for (x in 5..58) { w.weather.ice[w.index(x, 20)] = 20; w.weather.ice[w.index(x, 30)] = 20; w.weather.snow[w.index(x, 20)] = 0; w.weather.snow[w.index(x, 30)] = 0 }
        w.roadsDay()
        assertEquals(0, u(w.weather.ice[w.index(24, 20)]), "priority road out of reach")
        assertTrue(u(w.weather.ice[w.index(24, 30)]) > 0, "plain road salted too far")
    }

    @Test
    fun aSnowboundTrainOnPriorityTrackIsDugOutAndCharged() {
        fun stuck(priority: Boolean): World {
            val w = line()
            if (priority) w.apply(Command.Priority(0, 3, 10, 39, 10, Track.X, road = false, on = true))
            w.apply(Command.BuyTrain(0, 0, 0, 18, 3))
            w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
            w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
            w.weather.snow[w.index(25, 10)] = 250.toByte()
            val t = w.trains[0]!!
            var ticks = 0
            while (!t.snowbound && ticks++ < 20_000) w.step()
            assertTrue(t.snowbound, "never stuck")
            return w
        }
        val dug = stuck(true)
        val left = stuck(false)
        val before = dug.companies[0].thisYear[Books.PRIORITY]
        dug.days(DIG_DAYS)
        left.days(DIG_DAYS)
        assertTrue(u(dug.weather.snow[dug.index(25, 10)]) < Weather.BLOCKING, "not dug out")
        assertTrue(u(left.weather.snow[left.index(25, 10)]) >= Weather.BLOCKING, "dug out without priority")
        assertTrue(before - dug.companies[0].thisYear[Books.PRIORITY] >= dug.price(DIG_COST), "not charged")
        assertTrue(!dug.trains[0]!!.snowbound)
    }

    @Test
    fun aDigOutWithNoSnowToClearCostsNothing() {
        val w = line()
        w.apply(Command.Priority(0, 3, 10, 39, 10, Track.X, road = false, on = true))
        w.apply(Command.BuyTrain(0, 0, 0, 18, 3))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        w.weather.snow[w.index(25, 10)] = 250.toByte()
        val t = w.trains[0]!!
        var ticks = 0
        while (!t.snowbound && ticks++ < 20_000) w.step()
        assertTrue(t.snowbound, "never stuck")
        for (x in 0 until w.width) w.weather.snow[w.index(x, 10)] = 0
        val before = w.companies[0].thisYear[Books.PRIORITY]
        repeat(DIG_DAYS) { w.priorityDay() }
        assertEquals(before, w.companies[0].thisYear[Books.PRIORITY])
    }

    @Test
    fun prioritiesSurviveASaveAndAVersion66SaveLoadsWithNone() {
        val w = line(old = true)
        val plain = Save.read(Save.write(w, version = 66))
        assertEquals(w.checksum(), plain.checksum())
        w.apply(Command.Priority(0, 3, 10, 20, 10, Track.X, road = false, on = true))
        w.days(1)
        val back = Save.read(Save.write(w))
        assertEquals(w.priorityTrack, back.priorityTrack)
        assertEquals(w.companies[0].thisYear.toList(), back.companies[0].thisYear.toList())
        assertEquals(w.checksum(), back.checksum())
        val old = Save.read(Save.write(w, version = 66))
        assertTrue(old.priorityTrack.isEmpty())
    }

    @Test
    fun thePriorityCommandReadsBackFromBytes() {
        val c = Command.Priority(0, 4, 10, 30, 10, Track.X, road = true, on = false)
        assertEquals(c, Codec.decode(Codec.encode(c)))
    }

    @Test
    fun theViewHasTheForecastWarningsAndPriorities() {
        val w = weathered()
        val v = w.view(0, forecastAt = w.index(30, 40))
        assertEquals(5, v.forecast.size)
        assertTrue(v.forecast.all { it.high >= it.low })
        assertTrue(w.view(0).forecast.isEmpty())
    }
}
