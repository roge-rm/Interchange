package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnowTest {
    private fun u(b: Byte) = b.toInt() and 255

    private fun line(): World {
        val w = World(64, 64, 1, startYear = 1900)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 39, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 4, 10, 6, 10, Track.X))
        w.apply(Command.Station(0, 36, 10, 39, 10, Track.X))
        return w
    }

    @Test
    fun aPlowLeadsItsTrainAndClearsTheLine() {
        val w = line()
        for (x in 8..34) w.weather.snow[w.index(x, 10)] = 200.toByte()
        val rotary = w.roster.engines.first { it.key == "base:rotary_plow" }
        w.apply(Command.BuyTrain(0, 0, w.roster.engines.first { it.key == "base:consolidation" }.id, rotary.id, 1))
        val t = w.trains[0]!!
        assertEquals(rotary.id, t.carEngines[0])
        assertEquals(Plows.ROTARY, t.plow)
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        var stuck = false
        repeat(6000) {
            w.step()
            if (t.snowbound) stuck = true
        }
        assertTrue(!stuck, "a rotary got stuck")
        assertEquals(0, (8..34).count { u(w.weather.snow[w.index(it, 10)]) > 0 })
    }

    @Test
    fun aWedgeClearsLessThanARotary() {
        val w = line()
        for (x in 8..34) w.weather.snow[w.index(x, 10)] = 140.toByte()
        val wedge = w.roster.engines.first { it.key == "base:wedge_plow" }
        w.apply(Command.BuyTrain(0, 0, w.roster.engines.first { it.key == "base:consolidation" }.id, wedge.id, 1))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        repeat(4000) { w.step() }
        val left = u(w.weather.snow[w.index(20, 10)])
        assertEquals(140 - WEDGE_CLEARS, left)
    }

    @Test
    fun aShedKeepsSnowOffAndAFenceKeepsDriftsOff() {
        val w = World(NewGame(64, 64, 3, 1900, Region.PRAIRIES, towns = 0, industries = 0))
        for (i in w.corners.indices) w.corners[i] = 3
        for (i in 0 until w.width * w.height) w.trees[i] = 0
        w.companies[0].money = 10_000_000
        w.apply(Command.Rail(0, 5, 20, 55, 20, Track.X, remove = false))
        w.apply(Command.SnowWorks(0, 10, 20, shed = true))
        assertTrue(w.sheds.contains(w.index(10, 20)))
        w.apply(Command.SnowWorks(0, 40, 18, shed = false))
        assertTrue(w.fenceCover()[w.index(40, 20)])
        // A snowy, windy winter.
        var fenced = 0
        var open = 0
        repeat(60) {
            w.weather.wind = 45
            for (x in 5..55) w.weather.snow[w.index(x, 20)] = 0
            w.weather.fronts.clear()
            w.weather.fronts += Front(Front.STORM, 32 * 256, 20 * 256, 0, 0, 60, 800, 0, 2, false)
            w.weather.anomaly = -150
            repeat(Calendar.TICKS_PER_DAY) { w.step() }
            fenced += u(w.weather.snow[w.index(40, 20)])
            open += u(w.weather.snow[w.index(25, 20)])
            assertEquals(0, u(w.weather.snow[w.index(10, 20)]))
        }
        assertTrue(open > fenced, "drifted $open in the open and $fenced by the fence")
    }

    @Test
    fun anAvalancheBuriesTheLineBelow() {
        val w = World(NewGame(64, 64, 3, 1900, Region.ROCKIES, towns = 0, industries = 0))
        // A mountainside rising a step a tile north of the line along its foot at y = 30, up to 16.
        for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = (2 + (30 - y).coerceIn(0, 14)).toByte()
        for (i in 0 until w.width * w.height) w.trees[i] = 0
        w.companies[0].money = 10_000_000
        w.apply(Command.Rail(0, 5, 30, 55, 30, Track.X, remove = false))
        var buried = false
        repeat(40) {
            for (y in 10..24) for (x in 0 until w.width) w.weather.snow[w.index(x, y)] = 200.toByte()
            w.weather.anomaly = 300
            repeat(Calendar.TICKS_PER_DAY) { w.step() }
            if (w.news.any { it.kind == News.AVALANCHE }) buried = true
        }
        assertTrue(buried, "no avalanche reached the line")
    }
}
