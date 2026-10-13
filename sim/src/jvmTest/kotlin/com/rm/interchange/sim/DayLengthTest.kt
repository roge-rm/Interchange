package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DayLengthTest {
    private fun game(dayLength: Int) = World(NewGame(64, 64, 1, 1900, Region.MIXED, towns = 0, industries = 0, dayLength = dayLength))

    @Test
    fun theCalendarSlowsButWorkDaysDont() {
        val w = game(10)
        repeat(Calendar.TICKS_PER_DAY * 10) { w.step() }
        assertEquals(1, w.day)
        assertEquals(10, w.workDay)
    }

    @Test
    fun runningCostsComeEachWorkDay() {
        val spent = listOf(1, 10).map { n ->
            val w = game(n)
            for (i in w.corners.indices) w.corners[i] = 3
            w.companies[0].money = 10_000_000
            w.apply(Command.Road(0, 5, 20, 40, 20, Surface.DIRT, remove = false))
            w.apply(Command.Road(0, 5, 20, 5, 22, Surface.DIRT, remove = false))
            w.apply(Command.Garage(0, 5, 23, Track.YM))
            w.apply(Command.BuyRoadVehicle(0, 0, w.roster.engines.first { it.key == "base:freight_wagon" }.id))
            w.apply(Command.RoadOrder(0, 0, Command.TrainOrder.START_STOP))
            val before = w.companies[0].thisYear[Books.RUNNING]
            repeat(Calendar.TICKS_PER_DAY * 360) { w.step() }
            before - w.companies[0].thisYear[Books.RUNNING]
        }
        // 360 work days cost the same whether that's 360 calendar days or 36.
        assertTrue(spent[0] > 0)
        assertEquals(spent[0], spent[1])
    }

    @Test
    fun aSaveKeepsItsDayLength() {
        val w = game(6)
        assertEquals(6, Save.read(Save.write(w)).settings.dayLength)
    }
}
