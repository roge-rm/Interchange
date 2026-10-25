package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DepartureGapTest {
    private class Standing(val world: World, val vehicle: Carrier, val other: Carrier) {
        fun command(id: Int, what: Int, values: IntArray = IntArray(0)) {
            world.apply(when (vehicle) {
                is Train -> Command.TrainOrder(0, id, what, values)
                is RoadVehicle -> Command.RoadOrder(0, id, what, values)
                else -> Command.CraftOrder(0, vehicle is Aircraft, id, what, values)
            })
        }
    }

    /** Two vehicles sharing two stops, with the first finishing a real stop's wait. */
    private fun standing(kind: VehicleKind = VehicleKind.ROAD): Standing {
        val w = World(64, 64, 1, startYear = 1965)
        w.companies[0].money = 100_000_000
        val pair: Pair<Carrier, Carrier> = when (kind) {
            VehicleKind.ENGINE -> {
                for (i in w.corners.indices) w.corners[i] = 3
                w.apply(Command.Depot(0, 2, 10, Track.XP))
                w.apply(Command.Rail(0, 3, 10, 39, 10, Track.X, remove = false))
                w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
                w.apply(Command.Station(0, 36, 10, 39, 10, Track.X))
                val engine = w.roster.engines.first { it.key == "base:gp9" }.id
                val wagon = w.roster.engines.first { it.key == "base:coach_steel" }.id
                repeat(2) { w.apply(Command.BuyTrain(0, 0, engine, wagon, 2)) }
                val a = w.trains[0]!!
                a.inDepot = false
                a.running = true
                a.steps += step(w.index(13, 10), Track.PIECES.indexOf(Track.X), Track.XM)
                a to w.trains[1]!!
            }
            VehicleKind.ROAD -> {
                for (i in w.corners.indices) w.corners[i] = 3
                w.apply(Command.Road(0, 5, 20, 40, 20, Surface.PAVED, remove = false))
                w.apply(Command.Road(0, 5, 20, 5, 22, Surface.PAVED, remove = false))
                w.apply(Command.Garage(0, 5, 23, Track.YM))
                for (x in listOf(8, 38)) w.apply(Command.RoadStop(0, x, 20))
                val model = w.roster.engines.first { it.key == "base:new_look" }.id
                repeat(2) { w.apply(Command.BuyRoadVehicle(0, 0, model)) }
                val a = w.roadVehicles[0]!!
                a.inDepot = false
                a.running = true
                a.at = roadStep(w.index(8, 20), Track.XM, Track.XP)
                a to w.roadVehicles[1]!!
            }
            VehicleKind.SHIP -> {
                for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = if (x in 10..50 && y in 10..30) 0 else 1
                w.shoreChanged()
                w.apply(Command.Dock(0, 9, 20))
                w.apply(Command.Dock(0, 50, 22))
                w.apply(Command.Shipyard(0, 9, 15))
                val model = w.roster.engines.first { it.key == "base:steam_laker" }.id
                repeat(2) { w.apply(Command.BuyCraft(0, 0, model)) }
                val a = w.ships[0]!!
                a.inDepot = false
                a.running = true
                val tile = w.berths(0).first()
                a.x = (tile % w.width) * SHIP_UNIT + SHIP_UNIT / 2
                a.y = (tile / w.width) * SHIP_UNIT + SHIP_UNIT / 2
                w.weather.wind = 0
                assertFalse(w.stations[0]!!.waypoint)
                a to w.ships[1]!!
            }
            else -> {
                for (i in w.corners.indices) w.corners[i] = 1
                w.shoreChanged()
                w.apply(Command.Airport(0, 5, 5, Airports.REGIONAL, Track.X))
                w.apply(Command.Airport(0, 40, 40, Airports.REGIONAL, Track.Y))
                val model = w.roster.engines.first { it.key == "base:dc3" }.id
                repeat(2) { w.apply(Command.BuyCraft(0, w.airports[0]!!.depot, model)) }
                val a = w.aircraft[0]!!
                a.inDepot = false
                a.running = true
                a.state = Flight.GATE
                a.at = 0
                val gate = w.gatePoint(w.airports[0]!!, a.id)
                a.x = gate[0]; a.y = gate[1]
                a to w.aircraft[1]!!
            }
        }
        assertEquals(2, w.stations.size)
        val f = Standing(w, pair.first, pair.second)
        f.command(f.vehicle.id, Command.TrainOrder.ORDERS, intArrayOf(0, 1))
        f.command(f.other.id, Command.TrainOrder.SHARE, intArrayOf(f.vehicle.id))
        f.command(f.vehicle.id, Command.TrainOrder.GAP, intArrayOf(3))
        w.tick = 1000L
        f.other.departed = w.tick
        f.vehicle.waiting = 1
        return f
    }

    private val kinds = listOf(VehicleKind.ENGINE, VehicleKind.ROAD, VehicleKind.SHIP, VehicleKind.AIRCRAFT)

    private fun assertHeld(kind: VehicleKind) {
        val (w, v, other) = standing(kind).let { Triple(it.world, it.vehicle, it.other) }
        w.step()
        assertEquals(0, v.order)
        assertEquals(3 * Calendar.TICKS_PER_DAY - 1, v.waiting)
        assertEquals(0L, v.departed)
        assertEquals(1000L, other.departed)
    }

    @Test fun roadWaitsForGap() = assertHeld(VehicleKind.ROAD)
    @Test fun trainWaitsForGap() = assertHeld(VehicleKind.ENGINE)
    @Test fun shipWaitsForGap() = assertHeld(VehicleKind.SHIP)

    @Test
    fun aShipDoesNotCallAtAStopItIsNotBerthedAt() {
        val f = standing(VehicleKind.SHIP)
        val w = f.world
        val v = f.vehicle as Ship
        val tile = w.berths(1).first()
        v.x = (tile % w.width) * SHIP_UNIT + SHIP_UNIT / 2
        v.y = (tile / w.width) * SHIP_UNIT + SHIP_UNIT / 2
        f.command(v.id, Command.TrainOrder.ORDERS, intArrayOf(0 or Order.FULL_LOAD, 1))
        v.waiting = 1
        w.step()
        assertEquals(0, v.order)
        assertEquals(0, v.waiting)
        assertEquals(0, (0 until Cargo.COUNT).sumOf { v.load(it) })
    }
    @Test fun aircraftWaitsForGap() = assertHeld(VehicleKind.AIRCRAFT)

    @Test
    fun lateRoadVehicleLeavesAtItsOwnTick() {
        val f = standing()
        f.other.departed = f.world.tick - 3 * Calendar.TICKS_PER_DAY
        f.world.step()
        assertEquals(1, f.vehicle.order)
        assertEquals(0, f.vehicle.waiting)
        assertEquals(f.world.tick, f.vehicle.departed)
        assertEquals(1001L, f.vehicle.departed)
    }

    @Test
    fun offUnsharedAndNoPreviousDepartureLeaveImmediately() {
        for (kind in kinds) for (mode in 0..2) {
            val f = standing(kind)
            when (mode) {
                0 -> f.command(0, Command.TrainOrder.GAP, intArrayOf(0))
                1 -> f.command(0, Command.TrainOrder.UNSHARE)
                else -> f.other.departed = 0L
            }
            f.world.step()
            assertEquals(1, f.vehicle.order, "kind $kind, mode $mode")
            assertEquals(0, f.vehicle.waiting)
            assertEquals(f.world.tick, f.vehicle.departed)
        }
    }

    @Test
    fun loadingWinsBeforeGap() {
        for (kind in kinds) for (flag in listOf(Order.FULL_LOAD, Order.FULL_LOAD_ANY)) {
            val f = standing(kind)
            f.command(0, Command.TrainOrder.ORDERS, intArrayOf(flag, 1))
            assertTrue((0 until Cargo.COUNT).any { f.vehicle.capacity(it) > 0 })
            f.world.step()
            assertEquals(0, f.vehicle.order, "kind $kind, flag $flag")
            assertEquals(FULL_LOAD_RETRY, f.vehicle.waiting)
            assertEquals(0L, f.vehicle.departed)
        }
    }

    @Test
    fun satisfiedFullLoadThenWaitsForGap() {
        for (kind in kinds) {
            val f = standing(kind)
            f.command(0, Command.TrainOrder.ORDERS, intArrayOf(Order.FULL_LOAD, 1))
            for (c in 0 until Cargo.COUNT) if (f.vehicle.capacity(c) > 0) {
                f.vehicle.cargo[c] += Packet(1, 0, f.vehicle.capacity(c), f.world.tick)
            }
            f.world.step()
            assertEquals(0, f.vehicle.order)
            assertEquals(3 * Calendar.TICKS_PER_DAY - 1, f.vehicle.waiting)
            assertEquals(0L, f.vehicle.departed)
        }
    }

    @Test
    fun serviceLeavesDespiteFullLoadAndGap() {
        for (kind in kinds) {
            val f = standing(kind)
            f.command(0, Command.TrainOrder.ORDERS, intArrayOf(Order.FULL_LOAD, 1))
            val serviced = f.world.workDay - SERVICE_DAYS
            when (val v = f.vehicle) {
                is Train -> { v.service = true; v.serviced = serviced }
                is RoadVehicle -> { v.service = true; v.serviced = serviced }
                is Ship -> { v.service = true; v.serviced = serviced }
                is Aircraft -> { v.service = true; v.serviced = serviced }
            }
            assertTrue(f.world.due(true, serviced))
            f.world.step()
            assertEquals(1, f.vehicle.order, "kind $kind")
            assertEquals(0, f.vehicle.waiting)
            assertEquals(f.world.tick, f.vehicle.departed)
            assertTrue(when (val v = f.vehicle) {
                is Train -> v.toDepot && v.servicing
                is RoadVehicle -> v.toDepot && v.servicing
                is Ship -> v.toDepot && v.servicing
                is Aircraft -> v.toDepot && v.servicing
                else -> false
            })
        }
    }

    @Test
    fun commandsShareGapButKeepDeparturesAndOrderState() {
        for (kind in kinds) {
            val f = standing(kind)
            val a = f.vehicle
            val b = f.other
            a.departed = 17L
            b.departed = 23L
            when (a) {
                is Train -> a.route = intArrayOf(12)
                is RoadVehicle -> a.route = intArrayOf(12)
                is Ship -> a.route = intArrayOf(12)
            }
            f.command(0, Command.TrainOrder.GAP, intArrayOf(255))
            assertEquals(255, a.gap)
            assertEquals(255, b.gap)
            assertEquals(listOf(0, 1), a.orders)
            assertEquals(0, a.order)
            assertEquals(17L, a.departed)
            assertEquals(23L, b.departed)
            when (a) {
                is Train -> assertEquals(listOf(12), a.route!!.toList())
                is RoadVehicle -> assertEquals(listOf(12), a.route!!.toList())
                is Ship -> assertEquals(listOf(12), a.route!!.toList())
            }
            for (bad in listOf(intArrayOf(), intArrayOf(-1), intArrayOf(256))) {
                f.command(0, Command.TrainOrder.GAP, bad)
                assertEquals(255, a.gap)
                assertEquals(255, b.gap)
            }
            f.command(0, Command.TrainOrder.UNSHARE)
            f.command(0, Command.TrainOrder.GAP, intArrayOf(7))
            assertEquals(7, a.gap)
            assertEquals(255, b.gap)
            f.command(0, Command.TrainOrder.SHARE, intArrayOf(1))
            assertEquals(255, a.gap)
            assertEquals(17L, a.departed)
            assertEquals(23L, b.departed)
            f.command(0, Command.TrainOrder.ORDERS, intArrayOf(1, 0))
            assertEquals(255, a.gap)
            assertEquals(255, b.gap)
            assertEquals(17L, a.departed)
            assertEquals(23L, b.departed)
        }
    }

    @Test
    fun savesKeepGapAndDepartureOnlyFromVersion43() {
        for (kind in kinds) {
            val f = standing(kind)
            f.vehicle.departed = 321L
            f.other.departed = 654L
            // Saves before version 47 have no station buildings.
            for (s in f.world.stations) if (s != null) f.world.dropStationBuilding(s)
            for (version in listOf(42, Save.VERSION)) {
                val back = Save.read(Save.write(f.world, version = version))
                assertEquals(f.world.checksum(), back.checksum())
                val members: List<Carrier?> = when (kind) {
                    VehicleKind.ENGINE -> back.trains
                    VehicleKind.ROAD -> back.roadVehicles
                    VehicleKind.SHIP -> back.ships
                    else -> back.aircraft
                }
                assertEquals(if (version == 42) listOf(0, 0) else listOf(3, 3), members.map { it!!.gap })
                assertEquals(if (version == 42) listOf(0L, 0L) else listOf(321L, 654L), members.map { it!!.departed })
                val viewGaps = when (kind) {
                    VehicleKind.ENGINE -> back.view().trains.map { it.gap }
                    VehicleKind.ROAD -> back.view().roadVehicles.map { it.gap }
                    VehicleKind.SHIP -> back.view().ships.map { it.gap }
                    else -> back.view().aircraft.map { it.gap }
                }
                assertEquals(members.map { it!!.gap }, viewGaps)
            }
        }
    }

    @Test
    fun aGapHoldKeepsTheRouteAndRechecksNewerDepartures() {
        val f = standing()
        val v = f.vehicle as RoadVehicle
        v.route = intArrayOf(12)
        f.world.step()
        assertEquals(listOf(12), v.route!!.toList())
        f.other.departed = f.world.tick
        f.world.tick += v.waiting - 1
        v.waiting = 1
        f.world.step()
        assertEquals(1, v.waiting)
        assertEquals(0, v.order)
        assertEquals(0L, v.departed)
        f.world.step()
        assertEquals(1, v.order)
        assertEquals(f.world.tick, v.departed)
        assertNull(v.route)
    }

    @Test
    fun aircraftSkippingAnUnusableStopDoesNotStampOrWaitForGap() {
        val f = standing(VehicleKind.AIRCRAFT)
        val v = f.vehicle as Aircraft
        v.waiting = 0
        v.orders[0] = 999
        v.orders[1] = 0
        f.world.step()
        assertEquals(1, v.order)
        assertEquals(0L, v.departed)
        repeat(v.waiting) { f.world.step() }
        assertEquals(0L, v.departed)
    }

    @Test
    fun onlyTheLatestOtherDepartureOnThisKindsListCounts() {
        val f = standing()
        val w = f.world
        val v = f.vehicle as RoadVehicle
        v.departed = w.tick + 1000
        f.other.departed = w.tick - 20
        w.roadVehicles += null
        val latest = RoadVehicle(w.roster, w.roadVehicles.size, 0, v.model, v.depot)
        latest.shared = v.shared
        latest.departed = w.tick - 5
        w.roadVehicles += latest
        val elsewhere = RoadVehicle(w.roster, w.roadVehicles.size, 0, v.model, v.depot)
        elsewhere.shared = 99
        elsewhere.departed = w.tick + 1000
        w.roadVehicles += elsewhere
        val model = w.roster.engines.first { it.key == "base:steam_laker" }.id
        val ship = Ship(w.roster, 0, 0, model, -1)
        ship.shared = v.shared
        ship.departed = w.tick + 1000
        w.ships += ship
        w.step()
        assertEquals(0, v.order)
        assertEquals(3 * Calendar.TICKS_PER_DAY - 6, v.waiting)
        assertEquals(2000L, v.departed)
    }

}
