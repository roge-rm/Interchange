package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeatherTest {
    private fun World.run(days: Int) = repeat(Calendar.TICKS_PER_DAY * days) { step() }

    private fun world(region: Region, seed: Long = 3, year: Int = 1900) =
        World(NewGame(128, 128, seed, year, region, towns = 1, industries = 0))

    private fun World.landTiles() = (0 until width * height).filter { !isWater(it % width, it / width) }
    private fun World.waterTiles() = (0 until width * height).filter { isWater(it % width, it / width) }
    private fun u(b: Byte) = b.toInt() and 255

    @Test
    fun theDayOfTheYearCountsLeapDays() {
        assertEquals(0, Calendar.dayOfYear(1904, 0))
        assertEquals(365, Calendar.dayOfYear(1904, 365))
        assertEquals(0, Calendar.dayOfYear(1904, 366))
        assertEquals(59, Calendar.dayOfYear(1903, 59))
    }

    @Test
    fun theClimateWarmsThroughTheDecades() {
        assertTrue(Climate.normal(Region.MIXED, 2050, 15) > Climate.normal(Region.MIXED, 1900, 15) + 20)
        assertTrue(Climate.normal(Region.NORTH, 1900, 15) < -200)
        assertEquals(3, Climate.season(Region.PRAIRIES, 1900, 20))
        assertEquals(1, Climate.season(Region.PRAIRIES, 1900, 200))
        assertEquals(0, Climate.season(Region.MIXED, 1900, 120))
        assertEquals(2, Climate.season(Region.MIXED, 1900, 280))
    }

    @Test
    fun aNorthernWinterOpensUnderSnowWithTheBaysFrozen() {
        val w = world(Region.NORTH)
        val land = w.landTiles()
        val snowy = land.count { u(w.weather.snow[it]) >= 5 }
        assertTrue(snowy > land.size * 3 / 4, "$snowy of ${land.size} under snow")
        val frozen = w.waterTiles().count { u(w.weather.ice[it]) >= 10 }
        assertTrue(frozen > 0, "no ice")
        assertTrue(land.count { u(w.weather.frost[it]) > 20 } > land.size / 2, "frost")
    }

    @Test
    fun theSnowIsGoneBySummerAndTheLakesOpen() {
        val w = world(Region.MIXED)
        assertTrue(w.landTiles().count { u(w.weather.snow[it]) > 0 } > 0)
        w.run(200)
        val low = w.landTiles().filter { w.tileHeight(it % w.width, it / w.width) < 10 }
        assertEquals(0, low.count { u(w.weather.snow[it]) > 0 })
        assertEquals(0, w.waterTiles().count { u(w.weather.ice[it]) > 0 })
        assertEquals(0, low.count { u(w.weather.frost[it]) > 0 })
    }

    @Test
    fun theMaritimesAreWetterThanThePrairies() {
        fun fallen(region: Region): Long {
            val w = world(region)
            var sum = 0L
            repeat(180) {
                w.run(1)
                sum += w.skyAt(w.width / 2, w.height / 2).precipitation
                for (y in 0 until w.height step 16) for (x in 0 until w.width step 16) sum += w.skyAt(x, y).precipitation
            }
            return sum
        }
        val wet = fallen(Region.MARITIMES)
        val dry = fallen(Region.PRAIRIES)
        assertTrue(wet > dry * 3, "$wet against $dry")
    }

    @Test
    fun weatherIsTheSameEveryTimeAndCarriesOnThroughASave() {
        val a = world(Region.SHIELD, seed = 9)
        val b = world(Region.SHIELD, seed = 9)
        a.run(40); b.run(40)
        assertEquals(a.checksum(), b.checksum())
        val c = Save.read(Save.write(a))
        assertEquals(a.checksum(), c.checksum())
        a.run(60); c.run(60)
        assertEquals(a.checksum(), c.checksum())
        assertTrue(a.weather.fronts.isNotEmpty() || c.weather.recent > 0)
    }

    /** Flat land, a depot at the west end of a line along y = 10 and stations at each end, in fair weather. */
    private fun line(): World {
        val w = World(64, 64, 1)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 39, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        w.apply(Command.Station(0, 36, 10, 39, 10, Track.X))
        w.apply(Command.BuyTrain(0, 0, 0, 18, 3))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        return w
    }

    @Test
    fun wetRailIceAndSnowTakeTheGripAway() {
        val w = line()
        val tile = w.index(20, 10)
        assertEquals(100, w.adhesion(tile))
        w.weather.wet[tile] = 60
        assertEquals(80, w.adhesion(tile))
        w.weather.snow[tile] = 12
        assertEquals(75, w.adhesion(tile))
        w.weather.ice[tile] = 5
        assertEquals(60, w.adhesion(tile))
    }

    @Test
    fun aDriftStopsATrainTillItPushesThroughAndItPlowsAsItGoes() {
        val w = line()
        val drift = w.index(25, 10)
        w.weather.snow[drift] = 120.toByte()
        val deep = w.index(30, 10)
        w.weather.snow[deep] = 40
        val t = w.trains[0]!!
        var stuck = false
        var told = false
        repeat(20_000) {
            w.step()
            if (t.snowbound) stuck = true
            if (w.news.any { it.kind == News.SNOWBOUND && it.about == 0 }) told = true
        }
        assertTrue(stuck, "never stuck")
        assertTrue(told, "no news")
        assertTrue(u(w.weather.snow[drift]) < Weather.DEEP, "drift left at ${u(w.weather.snow[drift])}")
        assertTrue(u(w.weather.snow[deep]) < Weather.DEEP, "deep snow left at ${u(w.weather.snow[deep])}")
        // It got past to the far station.
        assertTrue(t.profitThisYear != 0L || t.order > 0 || stepTile(t.steps.last()) % w.width > 30)
    }

    @Test
    fun aHarbourShutsWhenTheIceComesIn() {
        val w = World(NewGame(128, 128, 4, 1900, Region.MARITIMES, towns = 0, industries = 3))
        val harbour = w.industries.firstOrNull { w.roster.industries[it.kind].weather and IndustryKind.ICE != 0 } ?: return
        for (i in 0 until w.width * w.height) w.weather.ice[i] = 0
        assertTrue(!w.iceBound(harbour))
        for (i in 0 until w.width * w.height) if (w.isWater(i % w.width, i / w.width)) w.weather.ice[i] = 40
        assertTrue(w.iceBound(harbour))
        assertEquals(0, w.weatherShare(harbour))
    }

    @Test
    fun weatherCanBeLeftOut() {
        val w = World(NewGame(64, 64, 2, 1900, Region.NORTH, towns = 0, industries = 0, weather = false))
        w.run(30)
        assertEquals(0, (0 until w.width * w.height).count { u(w.weather.snow[it]) > 0 })
        assertTrue(w.weather.fronts.isEmpty())
        assertTrue(!Save.read(Save.write(w)).settings.weather)
    }
}
