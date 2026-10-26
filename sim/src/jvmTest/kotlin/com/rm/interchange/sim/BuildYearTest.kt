package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BuildYearTest {
    private fun line(): World {
        val w = World(64, 64, 1, startYear = 1900)
        for (i in w.corners.indices) w.corners[i] = 3
        for (i in 0 until w.width * w.height) w.trees[i] = 0
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 39, 10, Track.X, remove = false))
        w.apply(Command.SnowWorks(0, 10, 10, shed = true))
        return w
    }

    @Test
    fun shedsAndDepotsKeepTheYearTheyWentUp() {
        val w = line()
        val shed = w.index(10, 10)
        assertEquals(1900, w.depots[0]!!.built)
        assertEquals(1900, w.shedYears[shed])
        // Years later another shed goes up; the first keeps its year.
        w.tick += w.dayTicks.toLong() * 365 * 60
        w.apply(Command.SnowWorks(0, 20, 10, shed = true))
        val later = w.index(20, 10)
        assertEquals(Calendar.year(w.startYear, w.day), w.shedYears[later])
        assertEquals(1900, w.shedYears[shed])
        val view = w.view()
        assertEquals(w.sheds.map { w.shedYears.getValue(it) }, view.shedYears.toList())
        assertEquals(1900, view.depots[0].built)
        // Taking a shed down forgets its year.
        w.apply(Command.SnowWorks(0, 20, 10, shed = true))
        assertTrue(later !in w.shedYears)
    }

    @Test
    fun buildYearsSurviveASave() {
        val w = line()
        w.tick += w.dayTicks.toLong() * 365 * 60
        w.apply(Command.SnowWorks(0, 20, 10, shed = true))
        val back = Save.read(Save.write(w))
        assertEquals(w.shedYears, back.shedYears)
        assertEquals(1900, back.depots[0]!!.built)
        assertEquals(w.checksum(), back.checksum())
    }

    @Test
    fun anOlderSaveLoadsWithNoYears() {
        val w = line()
        val back = Save.read(Save.write(w, version = 47))
        assertTrue(back.sheds.contains(w.index(10, 10)))
        assertTrue(back.shedYears.isEmpty())
        assertEquals(0, back.depots[0]!!.built)
    }

    @Test
    fun aShedsYearIsInTheChecksum() {
        val a = line()
        val b = line()
        assertEquals(a.checksum(), b.checksum())
        b.shedYears[b.index(10, 10)] = 1960
        assertNotEquals(a.checksum(), b.checksum())
    }

    @Test
    fun liftingTheTrackTakesItsShedDown() {
        val w = line()
        val shed = w.index(10, 10)
        assertTrue(shed in w.sheds)
        w.apply(Command.Rail(0, 9, 10, 11, 10, Track.X, remove = true))
        assertEquals(0, w.track[shed].toInt())
        assertTrue(shed !in w.sheds)
        assertTrue(shed !in w.shedYears)
        assertTrue(w.view().sheds.isEmpty())
    }
}
