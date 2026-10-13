package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharingTest {
    /**
     * Flat land, a line along y = 10 with a station at each end, both the player's, but the middle stretch, x 21 to 35,
     * laid by another company.
     */
    private fun line(sharing: Boolean): World {
        val w = World(NewGame(64, 64, 1, 1850, Region.MIXED, sea = 0, towns = 0, industries = 0, sharing = sharing))
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies += Company(1, "Other", 10_000_000)
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 20, 10, Track.X, remove = false))
        w.apply(Command.Rail(1, 21, 10, 35, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        w.apply(Command.Station(0, 36, 10, 39, 10, Track.X))
        assertEquals(1, w.owner[w.index(28, 10)].toInt())
        w.apply(Command.BuyTrain(0, 0, 0, 18, 3))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        return w
    }

    private fun World.calls(ticks: Int): List<Int> {
        val t = trains[0]!!
        val out = ArrayList<Int>()
        repeat(ticks) {
            step()
            if (t.waiting == Calendar.TICKS_PER_DAY * 2 - 1) out += stationTiles[stepTile(t.steps.last())] ?: -1
        }
        return out
    }

    @Test
    fun sharedTrackIsPaidForByTheTile() {
        val w = line(sharing = true)
        val calls = w.calls(30_000)
        assertTrue(1 in calls, "never got through: $calls")
        // The other company's paid for each of its tiles the train ran onto.
        val income = w.companies[1].thisYear[Books.INCOME] + w.companies[1].lastYear[Books.INCOME]
        assertTrue(income >= w.price(SHARE_FEE) * 15 * 2, "paid $income")
        assertEquals(0L, income % w.price(SHARE_FEE))
    }

    @Test
    fun withoutSharingEachKeepsToItsOwn() {
        val w = line(sharing = false)
        val calls = w.calls(30_000)
        assertTrue(1 !in calls, "ran through: $calls")
        assertEquals(0L, w.companies[1].thisYear[Books.INCOME] + w.companies[1].lastYear[Books.INCOME])
    }
}
