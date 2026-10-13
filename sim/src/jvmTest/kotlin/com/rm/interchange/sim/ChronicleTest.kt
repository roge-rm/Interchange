package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChronicleTest {
    @Test
    fun newsIsKeptWithItsDateAndSaved() {
        val w = World(NewGame(128, 128, 7, 1934, Region.MIXED, towns = 3, industries = 3, rivals = 1))
        repeat(Calendar.TICKS_PER_DAY * 400) { w.step() }
        // The log truck goes on sale in 1935, and the news says so in January.
        val logTruck = w.roster.engines.first { it.key == "base:log_truck" }
        val story = w.chronicle.first { it.kind == News.NEW_VEHICLE && it.about == logTruck.id }
        assertEquals(1935, story.year)
        assertEquals(0, story.month)
        // Nothing that was on sale from the start is news.
        assertTrue(w.chronicle.none { it.kind == News.NEW_VEHICLE && w.roster.engines[it.about].from < 1935 })
        // In order, oldest first.
        assertTrue(w.chronicle.zipWithNext().all { (a, b) -> a.year * 12 + a.month <= b.year * 12 + b.month })
        val copy = Save.read(Save.write(w))
        assertEquals(w.chronicle.map { listOf(it.year, it.month, it.kind, it.about) }, copy.chronicle.map { listOf(it.year, it.month, it.kind, it.about) })
    }
}
