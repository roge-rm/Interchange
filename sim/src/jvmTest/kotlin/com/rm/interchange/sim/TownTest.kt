package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TownTest {
    private fun world(seed: Long = 5, region: Region = Region.MIXED, year: Int = 1900) =
        World(NewGame(width = 256, height = 256, seed = seed, startYear = year, region = region, hills = 1))

    @Test
    fun aNewMapHasNamedTownsThatAddUp() {
        val w = world()
        assertTrue(w.towns.size >= 10, "only ${w.towns.size} towns")
        assertEquals(w.towns.size, w.towns.map { it.name }.toSet().size, "names repeat")
        for (t in w.towns) {
            var people = 0
            var houses = 0
            for (i in w.house.indices) {
                if (w.townOf[i].toInt() != t.id) continue
                val v = w.house[i].toInt()
                if (v != 0) {
                    houses++
                    people += w.roster.houses[houseOf(v)].residents
                    assertTrue(!w.steep(i % w.width, i / w.width), "${t.name} built on a steep lot")
                }
                if (w.road[i].toInt() != 0) assertTrue(w.roadFits(i % w.width, i / w.width, w.road[i].toInt()), "${t.name} road doesn't fit")
            }
            assertEquals(t.houses, houses, t.name)
            assertEquals(t.population, people, t.name)
            assertTrue(houses > 0, "${t.name} has nothing")
        }
        println(w.towns.joinToString { "${it.name} ${it.population}" })
    }

    @Test
    fun theShieldIsMostlyFrenchAndTheRockiesEnglish() {
        val shield = world(region = Region.SHIELD).towns.count { '-' in it.name || it.name.endsWith("ville") }
        val rockies = world(region = Region.ROCKIES).towns
        assertTrue(shield >= 4, "$shield French names")
        assertTrue(rockies.count { it.name.startsWith("Saint") || it.name.startsWith("Lac-") } <= 2)
    }

    @Test
    fun townsGrowAndBuildForTheirEra() {
        val w = world(year = 1850)
        val before = w.towns.sumOf { it.houses }
        repeat(365 * 40 * Calendar.TICKS_PER_DAY) { w.step() }
        val after = w.towns.sumOf { it.houses }
        assertTrue(after > before, "$before then $after")
        val year = Calendar.year(w.startYear, w.day)
        // Nothing stands that wasn't built in its own years.
        for (i in w.house.indices) {
            val v = w.house[i].toInt()
            if (v == 0) continue
            val h = w.roster.houses[houseOf(v)]
            val built = w.houseYear[i].toInt()
            assertTrue(built >= h.from && built < h.until, "${h.key} built in $built")
        }
        assertTrue(year == 1890)
    }

    @Test
    fun twoCopiesGrowTheSameAndSaveAndLoadCarriesOn() {
        val a = world(seed = 9)
        val b = world(seed = 9)
        repeat(4000) { a.step(); b.step() }
        assertEquals(a.checksum(), b.checksum())
        val c = Save.read(Save.write(a))
        assertEquals(a.towns.map { it.name }, c.towns.map { it.name })
        repeat(20_000) { a.step(); c.step() }
        assertEquals(a.checksum(), c.checksum())
    }

    @Test
    fun trackStaysOffTownRoads() {
        val w = world()
        w.companies[0].money = 1_000_000
        val t = w.towns.first()
        assertTrue(w.road[w.index(t.x, t.y)].toInt() != 0)
        w.apply(Command.Rail(0, t.x - 2, t.y, t.x + 2, t.y, Track.X, remove = false))
        assertEquals(0, w.trackAt(t.x, t.y))
    }
}
