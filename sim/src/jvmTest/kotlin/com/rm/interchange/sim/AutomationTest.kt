package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutomationTest {
    private val bus get() = "base:new_look"

    /** A road with two stops and a garage, and [buses] buses between the stops, started if [start]. */
    private fun busLine(breakdowns: Int = Breakdowns.NORMAL, buses: Int = 1, start: Boolean = true): World {
        val w = World(NewGame(64, 64, 1, 1965, towns = 0, industries = 0, weather = false, breakdowns = breakdowns))
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Road(0, 5, 20, 40, 20, Surface.PAVED, remove = false))
        w.apply(Command.Road(0, 5, 20, 5, 22, Surface.PAVED, remove = false))
        w.apply(Command.Garage(0, 5, 23, Track.YM))
        w.apply(Command.RoadStop(0, 8, 20))
        w.apply(Command.RoadStop(0, 38, 20))
        repeat(buses) { id ->
            w.apply(Command.BuyRoadVehicle(0, 0, w.roster.engines.first { it.key == bus }.id))
            w.apply(Command.RoadOrder(0, id, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
            if (start) w.apply(Command.RoadOrder(0, id, Command.TrainOrder.START_STOP))
            w.roadVehicles[id]!!.seasonalTires = false
        }
        return w
    }

    /** Runs [w] [days] work days with every bus kept at 30% reliability, and gives the breakdowns they had. */
    private fun breakdownsOver(w: World, days: Int): Int {
        repeat(days) {
            for (v in w.roadVehicles) if (v != null) v.reliability = 3000
            repeat(Calendar.TICKS_PER_DAY) { w.step() }
        }
        return w.roadVehicles.sumOf { it?.breakdowns ?: 0 }
    }

    @Test
    fun aNewCompanyHasAutorenewOffAndARivalHasItOn() {
        val w = World(NewGame(128, 128, 7, 1930, Region.MIXED, towns = 3, industries = 0, rivals = 1))
        assertFalse(w.companies[0].autorenew)
        assertFalse(w.view(0).company!!.autorenew)
        for (r in w.rivals) assertTrue(w.companies[r.company].autorenew)
        w.apply(Command.NewCompany(w.companies.size, "Second"))
        assertFalse(w.companies.last().autorenew)
    }

    @Test
    fun aBoughtVehicleTakesKeptOrdersOnlyWithInherit() {
        val w = busLine(start = false)
        val kind = Command.CopyVehicle.ROAD
        assertTrue(w.view(0).keptOrders.isEmpty())
        w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.SELL))
        val kept = assertNotNull(w.companies[0].orderBackups[kind])
        val view = w.view(0).keptOrders.single()
        assertEquals(kind, view.kind)
        assertEquals(listOf(0, 1), view.orders.toList())
        assertEquals(2, view.stops)
        assertEquals(listOf(w.stations[0]!!.name, w.stations[1]!!.name), view.stations)

        val model = w.roster.engines.first { it.key == bus }.id
        w.apply(Command.BuyRoadVehicle(0, 0, model))
        val plain = w.roadVehicles.last()!!
        assertTrue(plain.orders.isEmpty())
        assertEquals(kept, w.companies[0].orderBackups[kind])
        assertEquals(1, w.view(0).keptOrders.size)

        w.apply(Command.BuyRoadVehicle(0, 0, model, inherit = true))
        val heir = w.roadVehicles.last()!!
        assertEquals(listOf(0, 1), heir.orders)
        assertNull(w.companies[0].orderBackups[kind])
        assertTrue(w.view(0).keptOrders.isEmpty())
    }

    @Test
    fun inheritTravelsOverTheWire() {
        val commands = listOf(
            Command.BuyTrain(2, 3, 4, 5, 6, Cargo.PAPER, inherit = true),
            Command.BuyTrain(2, 3, 4, 5, 6, Cargo.PAPER, 3, inherit = true),
            Command.BuyRoadVehicle(2, 3, 4, Cargo.FOOD, inherit = true),
            Command.BuyCraft(2, 3, 4, Cargo.MAIL, 5, inherit = true),
            Command.BuyCraft(2, 3, 4, Cargo.MAIL, 5),
        )
        for (c in commands) assertEquals(c, Codec.decode(Codec.encode(c)))
    }

    @Test
    fun noneGivesNoBreakdowns() {
        val w = busLine(Breakdowns.NONE, buses = 3)
        assertEquals(0, breakdownsOver(w, 1500))
        assertTrue(w.roadVehicles.all { it!!.reliability > 0 })
        assertEquals(Breakdowns.NONE, w.view(0).breakdowns)
    }

    @Test
    fun reducedGivesFewerThanNormalOnTheSameSeed() {
        val normal = breakdownsOver(busLine(Breakdowns.NORMAL, buses = 3), 1500)
        val reduced = breakdownsOver(busLine(Breakdowns.REDUCED, buses = 3), 1500)
        assertTrue(normal > 20, "normal had $normal")
        assertTrue(reduced in 1 until normal * 3 / 4, "reduced had $reduced, normal $normal")
    }

    @Test
    fun aGameMadeWithoutTheSettingBreaksDownAsNormal() {
        assertEquals(Breakdowns.NORMAL, NewGame().breakdowns)
        assertNotEquals(busLine(Breakdowns.NORMAL).checksum(), busLine(Breakdowns.REDUCED).checksum())
    }

    @Test
    fun save65KeepsTheSettingAndStep() {
        assertEquals(65, Save.VERSION)
        for (level in listOf(Breakdowns.NONE, Breakdowns.REDUCED)) {
            val w = busLine(level, buses = 2)
            breakdownsOver(w, 60)
            val copy = Save.read(Save.write(w))
            assertEquals(level, copy.settings.breakdowns)
            assertEquals(w.checksum(), copy.checksum())
            repeat(Calendar.TICKS_PER_DAY * 200) { w.step(); copy.step() }
            assertEquals(w.checksum(), copy.checksum())
        }
    }

    @Test
    fun save64LoadsAsNormalWithAutorenewAsSaved() {
        for (on in listOf(true, false)) {
            val w = busLine(Breakdowns.REDUCED)
            w.companies[0].autorenew = on
            val old = Save.read(Save.write(w, version = 64))
            assertEquals(Breakdowns.NORMAL, old.settings.breakdowns)
            assertEquals(on, old.companies[0].autorenew)
        }
    }
}
