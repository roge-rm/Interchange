package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoadTest {
    /** Flat land, a paved road along y = 20 from x = 5 to 40, a stop at each end and a garage below its west end. */
    private fun road(year: Int = 1965): World {
        val w = World(64, 64, 1, startYear = year)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Road(0, 5, 20, 40, 20, Surface.PAVED, remove = false))
        w.apply(Command.Road(0, 5, 20, 5, 22, Surface.PAVED, remove = false))
        w.apply(Command.Garage(0, 5, 23, Track.YM))
        w.apply(Command.RoadStop(0, 8, 20))
        w.apply(Command.RoadStop(0, 38, 20))
        return w
    }

    private fun World.buy(key: String, orders: IntArray = intArrayOf(0, 1)): RoadVehicle {
        val model = roster.engines.first { it.key == "base:$key" }.id
        apply(Command.BuyRoadVehicle(0, 0, model))
        val id = roadVehicles.size - 1
        apply(Command.RoadOrder(0, id, Command.TrainOrder.ORDERS, orders))
        apply(Command.RoadOrder(0, id, Command.TrainOrder.START_STOP))
        return roadVehicles[id]!!
    }

    @Test
    fun roadsAreLaidJoinedAndMadeOneWay() {
        val w = road()
        val i = w.index(20, 20)
        assertEquals(roadBit(Track.XM) or roadBit(Track.XP), w.roadEdges(i))
        assertEquals(Surface.PAVED, w.surfaceAt(i))
        assertEquals(0, w.roadOwner[i].toInt())
        assertEquals(2, w.stations.size)
        assertTrue(w.depots[0]!!.road)
        w.apply(Command.RoadOneWay(0, 20, 20))
        assertTrue(w.mayLeave(i, Track.XP) && !w.mayLeave(i, Track.XM))
        // Highways come in 1950; not in 1900.
        val old = World(64, 64, 1, startYear = 1900)
        old.companies[0].money = 1_000_000
        assertEquals(-1L, old.roadCost(old.companies[0], Command.Road(0, 1, 1, 9, 1, Surface.HIGHWAY, remove = false)))
    }

    @Test
    fun aRoadGoesUpOnFoundationsOverUnevenLand() {
        val w = World(64, 64, 1, startYear = 1965)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        // One corner raised, under the road's line, so the tiles either side of it slope across the road.
        w.corners[w.corner(10, 20)] = 4
        val plain = w.roadCost(w.companies[0], Command.Road(0, 5, 20, 15, 20, Surface.PAVED, remove = false))
        val trees = (5..15).sumOf { w.treeCost(w.index(it, 20)) }
        assertEquals(11 * w.price(Surface.COST[Surface.PAVED]) + 2 * w.price(Prices.FOUNDATION) + trees, plain)
        w.apply(Command.Road(0, 5, 20, 15, 20, Surface.PAVED, remove = false))
        for (x in 5..15) assertTrue(w.roadEdges(w.index(x, 20)) != 0, "no road at $x")
        assertTrue(w.hasFoundation(9, 20) && w.hasFoundation(10, 20))
        // Up to the raised corner and down again, with no step at any join.
        assertEquals(4, w.edgeHeight(9, 20, 0, Track.XP))
        assertEquals(4, w.edgeHeight(10, 20, 0, Track.XM))
        assertEquals(3, w.edgeHeight(10, 20, 0, Track.XP))
        // A junction there would be level with the raised corner all round and leave a step to the road either side.
        w.apply(Command.Road(0, 10, 20, 10, 23, Surface.PAVED, remove = false))
        assertEquals(roadBit(Track.XM) or roadBit(Track.XP), w.roadEdges(w.index(10, 20)))
    }

    @Test
    fun aBusRunsBetweenItsStops() {
        val w = road()
        val bus = w.buy("new_look")
        val called = ArrayList<Int>()
        repeat(12_000) {
            w.step()
            if (bus.waiting == Calendar.TICKS_PER_DAY - 1) called += w.roadStops[roadStepTile(bus.at)] ?: -1
        }
        assertTrue(called.size >= 3, "called at $called")
        assertEquals(listOf(0, 1, 0), called.take(3))
    }

    @Test
    fun aTruckWaitsAtACrossingForATrain() {
        val w = road()
        // Track across the road at x = 25, a train sat on it.
        w.apply(Command.Rail(0, 25, 10, 25, 30, Track.Y, remove = false))
        assertTrue(w.crossing(w.index(25, 20)))
        w.apply(Command.Depot(0, 25, 31, Track.YM))
        w.apply(Command.Station(0, 25, 10, 25, 12, Track.Y))
        val north = w.stations.size - 1
        w.apply(Command.BuyTrain(0, 1, w.roster.engines.first { it.key == "base:gp9" }.id, w.roster.engines.first { it.key == "base:boxcar_steel" }.id, 2))
        val train = w.trains[0]!!
        // Out of its depot and stopped across the road.
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(north)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        repeat(2000) {
            w.step()
            if (!train.inDepot && stepTile(train.steps.last()) / w.width <= 20 && train.running) w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        }
        assertTrue((18..23).any { w.trainOnTile(w.index(25, it)) }, "the train isn't by the crossing")
        val truck = w.buy("semi_van", intArrayOf(1))
        repeat(3000) { k ->
            w.step()
        }
        // It's held short of the crossing.
        val x = roadStepTile(truck.at) % w.width
        assertTrue(!truck.inDepot && x < 25, "the truck got to x $x")
    }

    @Test
    fun aStreetcarKeepsToItsRails() {
        val w = road(1930)
        val car = w.buy("streetcar")
        repeat(3000) { w.step() }
        assertTrue(car.inDepot || car.route == null, "ran with no rails")
        w.apply(Command.Road(0, 5, 20, 40, 20, Surface.PAVED, remove = false, tram = true))
        w.apply(Command.Road(0, 5, 20, 5, 23, Surface.PAVED, remove = false, tram = true))
        var called = false
        repeat(12_000) {
            w.step()
            if (car.waiting > 0 && w.roadStops[roadStepTile(car.at)] == 1) called = true
        }
        assertTrue(called, "never got to the far stop on its rails")
    }

    @Test
    fun roadVehiclesCarryOnTheSameThroughASave() {
        val w = road()
        w.buy("new_look")
        w.buy("semi_van")
        repeat(3000) { w.step() }
        val c = Save.read(Save.write(w))
        assertEquals(w.checksum(), c.checksum())
        repeat(3000) { w.step(); c.step() }
        assertEquals(w.checksum(), c.checksum())
    }
}
