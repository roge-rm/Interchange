package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WaterTest {
    /**
     * Land a step up, a lake from x = 10 to 49 and y = 10 to 29, a dock on its west shore at (9, 20) and its
     * east shore at (50, 22), and a shipyard at (9, 15).
     */
    private fun lake(year: Int = 1965): World {
        val w = World(64, 64, 1, startYear = year)
        for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = if (x in 10..50 && y in 10..30) 0 else 1
        w.shoreChanged()
        w.companies[0].money = 100_000_000
        w.apply(Command.Dock(0, 9, 20))
        w.apply(Command.Dock(0, 50, 22))
        w.apply(Command.Shipyard(0, 9, 15))
        return w
    }

    private fun World.buy(key: String, orders: IntArray = intArrayOf(0, 1)): Ship {
        val model = roster.engines.first { it.key == "base:$key" }.id
        apply(Command.BuyCraft(0, 0, model))
        val id = ships.size - 1
        apply(Command.CraftOrder(0, false, id, Command.TrainOrder.ORDERS, orders))
        apply(Command.CraftOrder(0, false, id, Command.TrainOrder.START_STOP))
        return ships[id]!!
    }

    /** Runs [w] up to [ticks], noting each station [s] calls at. */
    private fun calls(w: World, s: Ship, ticks: Int): List<Int> {
        val called = ArrayList<Int>()
        repeat(ticks) {
            w.step()
            if (s.waiting == Calendar.TICKS_PER_DAY - 1) called += w.stations.indices.first { w.shipTile(s) in w.berths(it) }
        }
        return called
    }

    @Test
    fun docksAndAShipyardGoOnTheShore() {
        val w = lake()
        assertEquals(2, w.stations.size)
        assertEquals(Track.XP, w.dockFacing[w.index(9, 20)])
        assertEquals(Track.XM, w.dockFacing[w.index(50, 22)])
        assertEquals(listOf(w.index(10, 20)), w.berths(0))
        assertEquals(Depots.SHIPYARD, w.depots[0]!!.kind)
        // Not inland, and not on the water.
        w.apply(Command.Dock(0, 3, 3))
        w.apply(Command.Dock(0, 30, 20))
        assertEquals(2, w.stations.size)
    }

    @Test
    fun aLakerSailsBetweenItsDocks() {
        val w = lake()
        val s = w.buy("steam_laker")
        val called = calls(w, s, 40_000)
        assertTrue(called.size >= 3, "called at $called")
        assertEquals(listOf(0, 1, 0), called.take(3))
    }

    @Test
    fun iceStopsShipsAndAnIcebreakerOpensAChannel() {
        val w = lake()
        for (y in 10..29) w.weather.ice[w.index(30, y)] = 40
        val laker = w.buy("steam_laker", intArrayOf(1))
        repeat(3000) { w.step() }
        assertTrue(w.shipTile(laker) % w.width < 30, "the laker got through the ice")
        assertNull(w.planShipRoute(laker))
        val breaker = w.buy("icebreaker_diesel", intArrayOf(1))
        repeat(6000) { w.step() }
        assertTrue(w.shipTile(breaker) % w.width > 30, "the icebreaker didn't get through")
        assertNotNull(w.planShipRoute(laker))
        val called = calls(w, laker, 20_000)
        assertTrue(1 in called)
    }

    @Test
    fun aLockLiftsShipsIntoACanal() {
        val w = lake()
        // The east shore at (50, 20) slopes up a step to the land; a lock there, a canal on, and a dock at its end.
        w.apply(Command.Lock(0, 50, 20))
        assertEquals(Track.X, w.locks[w.index(50, 20)])
        w.apply(Command.Canal(0, 51, 20, 55, 20, remove = false))
        assertEquals(5, (51..55).count { w.canals.contains(w.index(it, 20)) })
        w.apply(Command.Dock(0, 56, 20))
        val canalStop = w.docks.getValue(w.index(56, 20))
        val s = w.buy("package_freighter", intArrayOf(0, canalStop))
        val called = calls(w, s, 40_000)
        assertTrue(canalStop in called, "called at $called")
        // Frozen over, the canal closes.
        for (x in 50..55) w.weather.ice[w.index(x, 20)] = 30
        val other = w.buy("package_freighter", intArrayOf(canalStop))
        repeat(200) { w.step() }
        assertNull(w.planShipRoute(other))
    }

    @Test
    fun shipsSaveAndCarryOnTheSame() {
        val w = lake()
        w.apply(Command.Buoy(0, 30, 15))
        val buoy = w.buoys.getValue(w.index(30, 15))
        w.buy("steam_laker", intArrayOf(0, buoy, 1))
        repeat(2000) { w.step() }
        val copy = Save.read(Save.write(w))
        assertEquals(w.checksum(), copy.checksum())
        repeat(3000) { w.step(); copy.step() }
        assertEquals(w.checksum(), copy.checksum())
    }
}
