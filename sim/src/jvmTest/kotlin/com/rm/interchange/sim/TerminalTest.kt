package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalTest {
    /** Stations A, B and C along a line with a depot and a train, houses only by C, nothing stepped. */
    private fun world(handling: Boolean = true): World {
        val w = World(NewGame(64, 64, 1, 1900, towns = 0, industries = 0, weather = false, handling = handling))
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 50, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        w.apply(Command.Station(0, 24, 10, 27, 10, Track.X))
        w.apply(Command.Station(0, 40, 10, 43, 10, Track.X))
        val house = w.roster.houses.first { it.key == "base:clapboard_house" }
        val t = Town(0, "Town", 40, 12, 3)
        w.towns += t
        for (x in 40..43) {
            val i = w.index(x, 12)
            w.house[i] = packHouse(house.id, Track.YM).toShort()
            w.townOf[i] = 0
            t.houses++
            t.population += house.residents
        }
        w.catchmentDirty = true
        w.apply(Command.BuyTrain(0, 0, 2, 18, 3))
        // Standing at A, as far as loading can tell.
        w.trains[0]!!.steps += step(w.index(11, 10), 0, 0)
        w.tick = 100_000
        return w
    }

    /** The ticks train 0 stands at A setting down [n] of [c] from B. */
    private fun setDown(w: World, c: Int, n: Int): Int {
        val t = w.trains[0]!!
        val a = w.stations[0]!!
        t.cargo[c] += Packet(a.id, 1, n, w.tick - 640, 0, 1)
        t.waiting = 0
        w.callAt(t, a, a.id)
        return t.waiting
    }

    @Test
    fun anOldSaveLoadsWithoutHandlingAndKeepsInStep() {
        val g = NewGame(128, 128, 6, 1930, Region.MIXED, towns = 8, industries = 6, rivals = 2, rivalLevel = RivalLevel.HARD)
        val old = Save.read(Save.write(World(g), version = 69))
        assertFalse(old.settings.handling)
        val off = Save.read(Save.write(World(g.copy(handling = false))))
        assertEquals(off.checksum(), old.checksum())
        while (old.day < 365) { old.step(); off.step() }
        assertEquals(off.checksum(), old.checksum())
        assertTrue(old.stations.all { it == null || it.equipment == 0 })
        assertTrue(old.flowsMonth.isEmpty() && old.flowsLast.isEmpty())
        assertTrue(old.companies.all { it.thisYear[Books.HANDLING] == 0L && it.lastYear[Books.HANDLING] == 0L })
        assertNull(old.handlingView(0))
    }

    @Test
    fun withoutHandlingAStopTakesNoLonger() {
        assertEquals(0, setDown(world(handling = false), Cargo.GOODS, 300))
    }

    @Test
    fun aBigLoadTakesLongerThanASmallOne() {
        val small = setDown(world(), Cargo.PASSENGERS, 10)
        val big = setDown(world(), Cargo.PASSENGERS, 300)
        assertTrue(small in 1 until big, "$small $big")
        // 300 passengers at 800 a day.
        assertEquals(300 * Calendar.TICKS_PER_DAY / 800, big)
    }

    @Test
    fun aCraneShortensIt() {
        val w = world()
        val plain = setDown(w, Cargo.GOODS, 300)
        val money = w.companies[0].money
        w.apply(Command.Equip(0, 0, Equipment.CRANE))
        assertEquals(money - w.price(Equipment.COST[Equipment.CRANE]), w.companies[0].money)
        val craned = setDown(w, Cargo.GOODS, 300)
        assertEquals(plain / 2, craned)
        // Bulk goes no faster for it.
        assertEquals(setDown(world(), Cargo.COAL, 300), setDown(w, Cargo.COAL, 300))
        assertEquals(2 * Handling.RATE[Handling.RAIL][Handling.GENERAL], w.handlingView(0)!!.rates[Handling.RAIL][Handling.GENERAL])
    }

    @Test
    fun aWarehouseKeepsFreightFromGivingUpAndRaisesTheCap() {
        for (stored in listOf(false, true)) {
            val w = world()
            val a = w.stations[0]!!
            if (stored) w.apply(Command.Equip(0, 0, Equipment.WAREHOUSE))
            a.cargo[Cargo.COAL] += Packet(1, 0, 50, w.tick - 100L * 64, waitingFrom = w.tick - 100L * 64)
            w.passengersDay()
            assertEquals(if (stored) 50 else 0, a.waiting(Cargo.COAL))
            assertEquals(if (stored) MAX_WAITING * Handling.STORE_TIMES else MAX_WAITING, w.waitCap(a, Cargo.COAL))
            // Passengers aren't stored.
            assertEquals(MAX_WAITING, w.waitCap(a, Cargo.PASSENGERS))
        }
    }

    @Test
    fun foodGivesUpSoonerWithoutAColdStore() {
        for (cold in listOf(false, true)) {
            val w = world()
            val a = w.stations[0]!!
            if (cold) w.apply(Command.Equip(0, 0, Equipment.COLD_STORE))
            a.cargo[Cargo.FOOD] += Packet(1, 0, 50, w.tick - 40L * 64, waitingFrom = w.tick - 40L * 64)
            w.passengersDay()
            assertEquals(if (cold) 50 else 0, a.waiting(Cargo.FOOD))
        }
    }

    @Test
    fun aTransferChargesWhoSetsItDownAndPaysTheStationsOwner() {
        val w = world()
        w.apply(Command.NewCompany(1, "Other"))
        val t = w.trains[0]!!
        val b = w.stations[1]!!
        for (owner in listOf(0, 1)) {
            b.owner = owner
            val before = LongArray(2) { w.companies[it].thisYear[Books.HANDLING] }
            t.cargo[Cargo.GOODS] += Packet(-1, 0, 100, w.tick - 640, 0, 0)
            w.callAt(t, b, b.id or Order.TRANSFER or Order.NO_LOAD)
            val charge = w.pay(100L * Handling.CHARGE[Handling.GENERAL]) / 100
            assertTrue(charge > 0)
            assertEquals(before[0] - charge, w.companies[0].thisYear[Books.HANDLING])
            assertEquals(before[1] + if (owner == 1) charge else 0, w.companies[1].thisYear[Books.HANDLING])
            b.cargo[Cargo.GOODS].clear()
        }
    }

    @Test
    fun endToEndStatsForATwoLegTripAddUp() {
        val w = world()
        w.apply(Command.NewCompany(1, "Other"))
        val t = w.trains[0]!!
        val (a, b, c) = Triple(w.stations[0]!!, w.stations[1]!!, w.stations[2]!!)
        b.owner = 1
        // A hundred from A, two days waited there and ten on board so far.
        t.cargo[Cargo.PASSENGERS] += Packet(-1, a.id, 100, w.tick - 640, 0, a.id).also { it.waited = 100L * 128 }
        w.callAt(t, b, b.id or Order.TRANSFER or Order.NO_LOAD)
        val h = 100 * Calendar.TICKS_PER_DAY / 800
        assertEquals(h, t.waiting)
        assertEquals(0, t.load(Cargo.PASSENGERS))
        w.tick += 640
        assertEquals(h, w.loadTimed(t, b))
        assertEquals(100, t.load(Cargo.PASSENGERS))
        w.tick += 640
        w.callAt(t, c, c.id)
        assertEquals(0, t.load(Cargo.PASSENGERS))
        w.flowsMonthEnd()
        val f = w.flowsAt(a.id).single()
        assertEquals(Cargo.PASSENGERS, f.cargo)
        assertEquals(c.id, f.to)
        assertEquals(100, f.units)
        // On board 1280 ticks, waited 128 at A and 640 at B, and the set down at C.
        val trip = 1280L + 128 + 640 + h
        assertEquals(trip, f.tripTicks)
        assertEquals((3L * h * 100 / trip).toInt(), f.handlingShare)
        assertEquals(w.pay(100L * Handling.CHARGE[Handling.PEOPLE]) / 100, f.costCents)
        assertEquals(f.units, w.flowsAt(c.id, to = true).single().units)
        // Dropped with the station.
        w.dropStation(c.id)
        assertTrue(w.flowsAt(a.id).isEmpty())
    }

    @Test
    fun equipmentGoesWithATakeoverAndIsKeptOnlyByTheOwner() {
        val w = world()
        w.apply(Command.NewCompany(1, "Other"))
        w.apply(Command.Equip(1, 0, Equipment.CRANE))
        assertEquals(0, w.stations[0]!!.equipment)
        w.apply(Command.Equip(0, 0, Equipment.CRANE))
        w.apply(Command.Equip(0, 0, Equipment.WAREHOUSE))
        assertEquals(0b101, w.stations[0]!!.equipment)
        w.apply(Command.Equip(0, 0, Equipment.CRANE, remove = true))
        assertEquals(0b100, w.stations[0]!!.equipment)
        w.takeOver(w.companies[0], w.companies[1])
        assertEquals(1, w.stations[0]!!.owner)
        assertEquals(0, w.stations[0]!!.equipment)
    }

    @Test
    fun equipmentHasUpkeep() {
        val w = world()
        val s = w.stations[0]!!
        assertEquals(0L, stationEquipmentUpkeep(s))
        w.apply(Command.Equip(0, 0, Equipment.TANK_FARM))
        assertEquals(Equipment.UPKEEP[Equipment.TANK_FARM] * 1_000 / WORK_MONTH, stationEquipmentUpkeep(s))
    }

    @Test
    fun saveSeventyKeepsInStep() {
        val g = NewGame(128, 128, 6, 1930, Region.MIXED, towns = 8, industries = 6, rivals = 2, rivalLevel = RivalLevel.HARD)
        val a = World(g)
        while (a.day < 200) a.step()
        val s = assertNotNull(a.stations.firstOrNull { it != null && !it.waypoint })
        a.companies[s.owner].money += 1_000_000
        a.apply(Command.Equip(s.owner, s.id, Equipment.WAREHOUSE))
        assertTrue(s.equipment != 0)
        while (a.day < 240) a.step()
        val b = Save.read(Save.write(a))
        assertTrue(b.settings.handling)
        assertEquals(s.equipment, b.stations[s.id]!!.equipment)
        assertEquals(a.flowsLast.keys, b.flowsLast.keys)
        assertEquals(a.checksum(), b.checksum())
        while (a.day < 300) { a.step(); b.step() }
        assertEquals(a.checksum(), b.checksum())
    }
}
