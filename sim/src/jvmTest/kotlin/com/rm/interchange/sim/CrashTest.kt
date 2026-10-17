package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Road vehicles hit on level crossings, and aircraft crashing on landing, with the Collisions setting. */
class CrashTest {
    /**
     * Flat land, a road along y = 20 from x = 5 to 40 with a stop at each end and a garage below its west end, and a
     * line across it at x = 25 from y = 10 to 30 with a station at each end and a depot below.
     */
    private fun crossing(collisions: Boolean): World {
        val w = World(NewGame(64, 64, 1, 1965, towns = 0, industries = 0, weather = false, collisions = collisions))
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 100_000_000
        w.apply(Command.Road(0, 5, 20, 40, 20, Surface.PAVED, remove = false))
        w.apply(Command.Road(0, 5, 20, 5, 22, Surface.PAVED, remove = false))
        w.apply(Command.Garage(0, 5, 23, Track.YM))
        w.apply(Command.RoadStop(0, 8, 20))
        w.apply(Command.RoadStop(0, 38, 20))
        w.apply(Command.Rail(0, 25, 10, 25, 30, Track.Y, remove = false))
        w.apply(Command.Depot(0, 25, 31, Track.YM))
        w.apply(Command.Station(0, 25, 10, 25, 12, Track.Y))
        w.apply(Command.Station(0, 25, 27, 25, 29, Track.Y))
        assertTrue(w.crossing(w.index(25, 20)))
        return w
    }

    private fun World.truck(orders: IntArray): RoadVehicle {
        apply(Command.BuyRoadVehicle(0, 0, roster.engines.first { it.key == "base:semi_van" }.id))
        val id = roadVehicles.size - 1
        apply(Command.RoadOrder(0, id, Command.TrainOrder.ORDERS, orders))
        apply(Command.RoadOrder(0, id, Command.TrainOrder.START_STOP))
        return roadVehicles[id]!!
    }

    private fun World.train(orders: IntArray): Train {
        apply(Command.BuyTrain(0, 1, roster.engines.first { it.key == "base:gp9" }.id, roster.engines.first { it.key == "base:boxcar_steel" }.id, 2))
        val id = trains.size - 1
        apply(Command.TrainOrder(0, id, Command.TrainOrder.ORDERS, orders))
        apply(Command.TrainOrder(0, id, Command.TrainOrder.START_STOP))
        return trains[id]!!
    }

    /** Runs till a truck heading east is broken down on the crossing, then sends a train north over it. */
    private fun brokenOnTheCrossing(w: World): Pair<RoadVehicle, Train> {
        val truck = w.truck(intArrayOf(1))
        val at = w.index(25, 20)
        var ticks = 0
        while (truck.inDepot || roadStepTile(truck.at) != at) { w.step(); assertTrue(ticks++ < 5_000, "the truck never got to the crossing") }
        truck.brokenFor = 100_000
        return truck to w.train(intArrayOf(2))
    }

    @Test
    fun aTruckBrokenDownOnACrossingIsHit() {
        val w = crossing(collisions = true)
        val (truck, train) = brokenOnTheCrossing(w)
        var ticks = 0
        while (train.wreckedFor == 0 && ticks++ < 5_000) w.step()
        assertTrue(train.wreckedFor > 0 && truck.wreckedFor > 0, "no crash")
        val crash = w.crashes.list.single()
        assertEquals(listOf(Crash.TRAIN to train.id, Crash.ROAD to truck.id), crash.vehicles.map { it.kind to it.id })
        assertEquals(w.index(25, 20), crash.tile)
        assertEquals(1, w.chronicle.count { it.kind == News.CRASH })
        // A wreck takes no orders, earns and costs nothing and isn't worth anything.
        assertTrue(w.carriers().none { it === truck } && truck.orders.isEmpty())
        w.apply(Command.RoadOrder(0, truck.id, Command.TrainOrder.START_STOP))
        assertTrue(!truck.running)
        assertEquals(0L, w.roadValue(truck))
        // It blocks its lane: a truck behind it waits.
        val next = w.truck(intArrayOf(1))
        repeat(WRECK_TICKS - 200) { w.step() }
        assertTrue(roadStepTile(next.at) % w.width < 25, "the next truck got past the wreck")
        // Then both are cleared, and the road and line are free again.
        repeat(200) { w.step() }
        assertNull(w.roadVehicles[truck.id])
        assertNull(w.trains[train.id])
        ticks = 0
        while (roadStepTile(next.at) % w.width <= 25 && ticks++ < 5_000) w.step()
        assertTrue(roadStepTile(next.at) % w.width > 25, "the next truck never got over the crossing")
        assertEquals(1, w.crashes.list.size)
    }

    /** Two trucks stopped on the crossing, one in each lane, are both in the one crash. */
    @Test
    fun bothLanesStoppedOnACrossingAreHit() {
        val w = crossing(collisions = true)
        val (east, train) = brokenOnTheCrossing(w)
        val west = w.truck(intArrayOf(0))
        // Put straight onto the crossing heading west, broken down.
        west.inDepot = false
        west.at = roadStep(w.index(25, 20), Track.XP, Track.XM)
        west.along = roadStepLength(west.at) / 2
        west.speed = 0
        west.brokenFor = 100_000
        var ticks = 0
        while (train.wreckedFor == 0 && ticks++ < 5_000) w.step()
        assertTrue(east.wreckedFor > 0 && west.wreckedFor > 0, "not both hit")
        val crash = w.crashes.list.single()
        assertEquals(listOf(Crash.TRAIN to train.id, Crash.ROAD to east.id, Crash.ROAD to west.id), crash.vehicles.map { it.kind to it.id })
    }

    @Test
    fun withCollisionsOffNothingIsWrecked() {
        val w = crossing(collisions = false)
        val (truck, train) = brokenOnTheCrossing(w)
        var ticks = 0
        while (!(train.waiting > 0 && stepTile(train.steps.last()) / w.width <= 12) && ticks++ < 5_000) w.step()
        assertTrue(train.waiting > 0, "the train never got to its station")
        assertTrue(train.wreckedFor == 0 && truck.wreckedFor == 0 && w.crashes.list.isEmpty())
    }

    /** A truck and a train running back and forth over the crossing for a while: the truck waits for the train, and nothing's hit. */
    @Test
    fun movingTrafficIsntHit() {
        val w = crossing(collisions = true)
        val truck = w.truck(intArrayOf(0, 1))
        val train = w.train(intArrayOf(2, 3))
        val at = w.index(25, 20)
        var crossed = 0
        var passed = 0
        var was = false
        var trainWas = false
        repeat(Calendar.TICKS_PER_DAY * 200) {
            w.step()
            val on = !truck.inDepot && roadStepTile(truck.at) == at
            if (on && !was) crossed++
            was = on
            val trainOn = w.trainOnTile(at)
            if (trainOn && !trainWas) passed++
            trainWas = trainOn
        }
        assertTrue(crossed >= 6 && passed >= 6, "the truck crossed $crossed times, the train $passed")
        assertTrue(w.crashes.list.isEmpty(), "crashed")
        assertTrue(w.litCrossings().size <= 1)
    }

    /** The crossing's lights show while a road vehicle would wait for a train there, and not otherwise. */
    @Test
    fun crossingLightsShowWhileATrainIsNear() {
        val w = crossing(collisions = true)
        val at = w.index(25, 20)
        assertTrue(w.view().crossings.isEmpty())
        val train = w.train(intArrayOf(2))
        var lit = false
        var ticks = 0
        while (!(train.waiting > 0) && ticks++ < 5_000) {
            w.step()
            assertEquals(w.trainNear(at), at in w.litCrossings())
            if (at in w.view().crossings) lit = true
        }
        assertTrue(lit, "never lit")
        assertTrue(w.view().crossings.isEmpty(), "still lit with the train at its station")
    }

    /** Flat land, two regional airports, in 1995. */
    private fun airports(): World {
        val w = World(NewGame(64, 64, 1, 1995, towns = 0, industries = 0, weather = false))
        for (i in w.corners.indices) w.corners[i] = 1
        w.companies[0].money = 100_000_000
        w.apply(Command.Airport(0, 5, 5, Airports.REGIONAL, Track.X))
        w.apply(Command.Airport(0, 40, 40, Airports.REGIONAL, Track.Y))
        return w
    }

    private fun World.plane(key: String): Aircraft {
        apply(Command.BuyCraft(0, airports[0]!!.depot, roster.engines.first { it.key == "base:$key" }.id))
        val id = aircraft.size - 1
        apply(Command.CraftOrder(0, true, id, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        return aircraft[id]!!
    }

    /** Touches [v] down on airport 1's runway [n] times, a tick each, and counts the crashes; with [keep], stops at the first and leaves the wreck. */
    private fun landings(w: World, v: Aircraft, n: Int, keep: Boolean = false): Int {
        val a = w.airports[1]!!
        val r = w.runway(a)
        var crashes = 0
        repeat(n) {
            v.inDepot = false
            v.running = true
            v.state = Flight.LANDING
            v.at = a.id
            v.x = r[0]; v.y = r[1]
            v.altitude = 5
            v.speed = v.maxSpeed / 2
            w.step()
            if (v.wreckedFor > 0) {
                crashes++
                if (keep) return crashes
                v.wreckedFor = 0
            }
        }
        return crashes
    }

    @Test
    fun aircraftCrashNowAndThenOnLanding() {
        val w = airports()
        val dash8 = w.plane("dash8")
        val crj = w.plane("crj")
        val a = w.airports[1]!!
        assertEquals(CRASH_ODDS, crashOdds(dash8, a))
        assertEquals(SHORT_CRASH_ODDS, crashOdds(crj, a))
        val slow = landings(w, dash8, 8_000)
        val fast = landings(w, crj, 4_000)
        // About 20 and 40.
        assertTrue(slow in 8..40, "$slow crashes in 8000 landings")
        assertTrue(fast in 20..70, "$fast crashes in 4000 landings")
        // At least twice as often.
        assertTrue(fast * 2 >= slow * 2, "a jet on a regional airport crashes too seldom: $fast in 4000, $slow in 8000")
        val off = World(NewGame(64, 64, 1, 1995, towns = 0, industries = 0, weather = false, collisions = false))
        for (i in off.corners.indices) off.corners[i] = 1
        off.companies[0].money = 100_000_000
        off.apply(Command.Airport(0, 5, 5, Airports.REGIONAL, Track.X))
        off.apply(Command.Airport(0, 40, 40, Airports.REGIONAL, Track.Y))
        assertEquals(0, landings(off, off.plane("crj"), 2_000))
    }

    /** A crash closes the airport to landings till the wreck's cleared, and everyone aboard is lost. */
    @Test
    fun aWreckClosesTheRunwayTillItsCleared() {
        val w = airports()
        val crj = w.plane("crj")
        val other = w.plane("dash8")
        val a = w.airports[1]!!
        crj.cargo[Cargo.PASSENGERS] += Packet(0, 0, 30, 0L)
        assertEquals(1, landings(w, crj, 10_000, keep = true))
        assertTrue(crj.wreckedFor > 0)
        val crash = w.crashes.list.last()
        assertEquals(Crash.AIRCRAFT, crash.vehicles.single().kind)
        assertEquals(30, crash.lost)
        assertEquals("wreck", w.cantLand(other, a))
        repeat(WRECK_TICKS) { w.step() }
        assertNull(w.aircraft[crj.id])
        assertNull(w.cantLand(other, a))
        // Saved and loaded alike.
        val copy = Save.read(Save.write(w))
        assertEquals(w.checksum(), copy.checksum())
    }
}
