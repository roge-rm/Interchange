package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrainTest {
    /** Flat land at height 3, a depot at the west end of a line along y = 10, and two stations on it. */
    private fun line(): World {
        val w = World(64, 64, 1)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 39, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        w.apply(Command.Station(0, 36, 10, 39, 10, Track.X))
        return w
    }

    private fun World.run(ticks: Int, each: () -> Unit = {}) = repeat(ticks) { step(); each() }

    @Test
    fun aTrainRunsBetweenTwoStationsAndTurnsAtTheEnd() {
        val w = line()
        assertEquals(2, w.stations.size)
        w.apply(Command.BuyTrain(0, 0, 0, 18, 3))
        val t = w.trains[0]!!
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        val calledAt = ArrayList<Int>()
        w.run(30_000) {
            if (t.waiting == Calendar.TICKS_PER_DAY * 2 - 1) calledAt += w.stationTiles[stepTile(t.steps.last())] ?: -1
        }
        assertTrue(calledAt.size >= 4, "called at $calledAt")
        assertEquals(listOf(0, 1, 0, 1), calledAt.take(4))
    }

    @Test
    fun signalsKeepTwoTrainsApart() {
        val w = line()
        // Signals every few tiles, facing east, one way.
        for (x in listOf(6, 16, 22, 28, 33)) {
            w.apply(Command.Signal(0, x, 10, Track.X, Track.XM))
            w.apply(Command.Signal(0, x, 10, Track.X, Track.XM))
        }
        w.apply(Command.BuyTrain(0, 0, 0, 18, 2))
        w.apply(Command.BuyTrain(0, 0, 0, 22, 2))
        for (id in 0..1) {
            w.apply(Command.TrainOrder(0, id, Command.TrainOrder.ORDERS, intArrayOf(1)))
            w.apply(Command.TrainOrder(0, id, Command.TrainOrder.START_STOP))
        }
        w.run(6_000) {
            val a = w.trains[0]!!
            val b = w.trains[1]!!
            if (!a.inDepot && !b.inDepot) {
                val shared = a.onTiles.filter { it in b.onTiles }
                assertTrue(shared.isEmpty(), "trains on the same tiles $shared at tick ${w.tick}")
            }
        }
        // Both got out of the depot, the second only once the first had cleared the block.
        assertTrue(!w.trains[0]!!.inDepot && !w.trains[1]!!.inDepot)
    }

    @Test
    fun twoCopiesRunTheSame() {
        val a = line()
        val b = line()
        for (w in listOf(a, b)) {
            w.apply(Command.BuyTrain(0, 0, 0, 18, 3))
            w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
            w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
            w.run(5_000)
        }
        assertEquals(a.checksum(), b.checksum())
    }

    @Test
    fun aTrainFindsItsWayThroughAJunction() {
        val w = line()
        // A branch off the line at x = 25 going south to a third station.
        w.apply(Command.Rail(0, 25, 10, 25, 10, Track.S, remove = false))
        w.apply(Command.Rail(0, 25, 11, 25, 20, Track.Y, remove = false))
        w.apply(Command.Station(0, 25, 17, 25, 20, Track.Y))
        val third = w.stations.size - 1
        w.apply(Command.BuyTrain(0, 0, 0, 18, 2))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(third)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        var arrived = false
        w.run(10_000) {
            val t = w.trains[0]!!
            if (t.waiting > 0 && w.stationTiles[stepTile(t.steps.last())] == third) arrived = true
        }
        assertTrue(arrived)
    }

    @Test
    fun aTrainOnlyTurnsAtTheEndsOfTheLine() {
        // The depot right against the first station, as people will build it.
        val w = World(64, 64, 1)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Rail(0, 3, 10, 20, 10, Track.X, remove = false))
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Station(0, 3, 10, 6, 10, Track.X))
        w.apply(Command.Station(0, 19, 10, 20, 10, Track.X))
        w.apply(Command.BuyTrain(0, 0, 0, 18, 3))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        val t = w.trains[0]!!
        var lastDir = -1
        val turnedAt = ArrayList<Int>()
        w.run(8_000) {
            if (t.inDepot) return@run
            val s = t.steps.last()
            if (lastDir >= 0 && stepExit(s) != lastDir) turnedAt += stepTile(s) % 64
            lastDir = stepExit(s)
        }
        assertTrue(turnedAt.size >= 3)
        // Once turned, the nose is where the last car was, so within a train's length of either end, never out on the line.
        assertTrue(turnedAt.all { it <= 7 || it >= 16 }, "turned at $turnedAt")
    }
}
