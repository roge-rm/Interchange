package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WearTest {
    private fun World.days(n: Int) = repeat(Calendar.TICKS_PER_DAY * n) { step() }

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

    private fun World.runTrain(): Train {
        apply(Command.BuyTrain(0, 0, roster.engines.first { it.key == "base:consolidation" }.id, roster.engines.first { it.key == "base:hopper" }.id, 10))
        apply(Command.TrainOrder(0, trains.size - 1, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        apply(Command.TrainOrder(0, trains.size - 1, Command.TrainOrder.START_STOP))
        return trains.last()!!
    }

    @Test
    fun heavyTrainsWearTrackAndCrewsMendIt() {
        val w = line()
        val tile = w.index(20, 10)
        // Two hundred passes of a 2000 tonne train.
        repeat(200) { w.wearBy(tile, 2_000_000) }
        val worn = w.condition[tile].toInt() and 255
        assertTrue(worn < 200, "only down to $worn")
        // Crews mend it, faster the more they're paid.
        w.companies[0].upkeep = Upkeep.LOW
        w.condition[tile] = 100
        w.days(20)
        val low = w.condition[tile].toInt() and 255
        w.companies[0].upkeep = Upkeep.HIGH
        w.condition[tile] = 100
        w.days(20)
        val high = w.condition[tile].toInt() and 255
        assertTrue(high > low && low > 100, "$low on low upkeep, $high on high")
        assertTrue(w.companies[0].thisYear[Books.UPKEEP] < 0)
    }

    @Test
    fun wornTrackHasASlowOrder() {
        val w = line()
        for (x in 15..30) w.condition[w.index(x, 10)] = 50
        assertEquals(VERY_WORN_KMH, w.trackLimit(w.index(20, 10)))
        w.companies[0].upkeep = Upkeep.LOW
        val t = w.runTrain()
        var fastest = 0
        repeat(2000) {
            w.step()
            if (!t.inDepot && stepTile(t.steps.last()) % w.width in 18..28) fastest = maxOf(fastest, t.speed * 2700 / 65536)
        }
        assertTrue(fastest in 1..VERY_WORN_KMH, "went $fastest km/h on worn track")
    }

    @Test
    fun aClosedLineHoldsTrainsTillItsMended() {
        val w = line()
        val tile = w.index(20, 10)
        w.closed[tile] = Closure.WASHOUT shl 16 or 3
        val t = w.runTrain()
        var past = false
        repeat(Calendar.TICKS_PER_DAY * 2) {
            w.step()
            if (!t.inDepot && stepTile(t.steps.last()) % w.width > 20) past = true
        }
        assertTrue(!past, "ran through a washout")
        w.days(4)
        assertTrue(!w.closedTile(tile))
        repeat(4000) {
            w.step()
            if (!t.inDepot && stepTile(t.steps.last()) % w.width > 20) past = true
        }
        assertTrue(past, "never got by once it was open")
    }

    @Test
    fun aFireSpreadsThroughTheForestAndClosesTrackByIt() {
        val w = World(NewGame(64, 64, 2, 1900, Region.MIXED, towns = 0, industries = 0))
        for (i in w.corners.indices) w.corners[i] = 3
        for (i in 0 until w.width * w.height) { w.trees[i] = Trees.pack(Trees.PINE, 3).toByte(); w.weather.wet[i] = 0; w.weather.snow[i] = 0 }
        w.weather.fronts.clear()
        w.companies[0].money = 10_000_000
        w.apply(Command.Rail(0, 10, 30, 50, 30, Track.X, remove = false))
        w.fire[w.index(30, 27)] = FIRE_DAYS.toByte()
        // A hot dry spell.
        var closed = false
        repeat(8) {
            w.weather.anomaly = 500
            w.weather.fronts.clear()
            for (i in 0 until w.width * w.height) w.weather.wet[i] = 0
            w.days(1)
            if (w.closed.values.any { it shr 16 == Closure.FIRE }) closed = true
        }
        val burnt = (0 until w.width * w.height).count { w.burnt(it) }
        assertTrue(burnt > 5, "only $burnt tiles burnt")
        assertTrue((0 until w.width * w.height).count { w.burnt(it) && Trees.count(w.trees[it].toInt()) == 0 } > 0)
        assertTrue(closed, "the line never closed")
    }
}
