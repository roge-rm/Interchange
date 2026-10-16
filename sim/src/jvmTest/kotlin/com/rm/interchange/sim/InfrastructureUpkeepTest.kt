package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Upkeep on what a company has built: track, roads, road stops, docks and airports are charged each work day,
 * the same over a work year whatever the day length, and a copy loaded mid-day charges what the original does.
 */
class InfrastructureUpkeepTest {
    private val parts = setOf("track", "road", "stop", "dock", "airport")

    /** Flat land a step up with a lake from x = 10 to 49, y = 10 to 29, and company 0's [build] on it, without inflation. */
    private fun site(dayLength: Int, build: Set<String> = parts): World {
        val w = World(NewGame(64, 64, 1, 1965, sea = 0, trees = 0, towns = 0, industries = 0, inflation = false, weather = false, dayLength = dayLength))
        for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = if (x in 10..50 && y in 10..30) 0 else 1
        w.shoreChanged()
        w.companies[0].money = 100_000_000
        if ("track" in build) w.apply(Command.Rail(0, 3, 45, 30, 45, Track.X, remove = false))
        if ("road" in build || "stop" in build) w.apply(Command.Road(0, 5, 55, 30, 55, Surface.PAVED, remove = false))
        if ("stop" in build) w.apply(Command.RoadStop(0, 8, 55))
        if ("dock" in build) w.apply(Command.Dock(0, 9, 20))
        if ("airport" in build) w.apply(Command.Airport(0, 40, 40, Airports.REGIONAL, Track.Y))
        return w
    }

    /** What company 0 owes a work day, in tenths of a cent, counted straight from the map. */
    private fun rate(w: World): Long {
        var r = 0L
        for (i in 0 until w.width * w.height) {
            if (w.track[i].toInt() != 0 && w.owner[i].toInt() == 0) r += Upkeep.TRACK[w.companies[0].upkeep]
            if (w.roadEdges(i) != 0 && w.roadOwner[i].toInt() == 0) r += Upkeep.ROAD[w.surfaceAt(i)]
        }
        for (s in w.stations) if (s != null && s.owner == 0 && !s.waypoint) r += s.stops.size * Upkeep.STOP.toLong() + s.docks.size * Upkeep.DOCK.toLong()
        for (a in w.airports) if (a != null && a.owner == 0) r += Upkeep.AIRPORT[a.kind]
        return r
    }

    /** Company 0's upkeep so far this year and last. */
    private fun upkeep(w: World) = -(w.companies[0].thisYear[Books.UPKEEP] + w.companies[0].lastYear[Books.UPKEEP])

    /** Runs [w] on [ticks] ticks and gives what company 0 paid for upkeep, the books turning over a year or not. */
    private fun charged(w: World, ticks: Int): Long {
        var sum = 0L
        repeat(ticks) {
            val year = Calendar.year(w.startYear, w.day)
            val before = w.companies[0].thisYear[Books.UPKEEP]
            w.step()
            val c = w.companies[0]
            sum -= if (Calendar.year(w.startYear, w.day) != year) c.lastYear[Books.UPKEEP] - before + c.thisYear[Books.UPKEEP] else c.thisYear[Books.UPKEEP] - before
        }
        return sum
    }

    @Test
    fun eachPartIsChargedEachWorkDay() {
        for (part in parts) {
            val w = site(1, setOf(part))
            val r = rate(w)
            val days = 40
            val wanted = (r * days) / 1_000
            assertTrue(wanted > 0, "$part costs nothing")
            when (part) {
                "stop" -> assertEquals(1, w.stations.count { it != null && it.stops.size == 1 })
                "dock" -> assertEquals(1, w.stations.count { it != null && it.docks.size == 1 })
                "airport" -> assertEquals(1, w.airports.count { it != null })
            }
            val paid = charged(w, Calendar.TICKS_PER_DAY * days)
            assertEquals(wanted, paid, "$part over $days work days")
        }
        // Each work day, not saved up for the calendar day.
        val w = site(10)
        val r = rate(w)
        repeat(5) { d ->
            val before = upkeep(w)
            repeat(Calendar.TICKS_PER_DAY) { w.step() }
            assertTrue(upkeep(w) > before, "nothing charged on work day $d")
        }
        assertEquals(r * 5 / 1_000, upkeep(w))
    }

    @Test
    fun aWorkYearCostsTheSameAtAnyDayLength() {
        val year = Calendar.TICKS_PER_DAY * 365
        val one = charged(site(1), year)
        val ten = charged(site(10), year)
        assertTrue(one > 0)
        assertEquals(one, ten)
        assertEquals(rate(site(1)) * 365 / 1_000, one)
    }

    @Test
    fun aCopyLoadedMidDayChargesTheSame() {
        val a = site(10)
        repeat(a.dayTicks * 2 + a.dayTicks / 3) { a.step() }
        // More track since the last count, which neither charges till the next calendar day.
        a.apply(Command.Rail(0, 3, 50, 30, 50, Track.X, remove = false))
        repeat(Calendar.TICKS_PER_DAY * 2 + 7) { a.step() }
        val b = Save.read(Save.write(a))
        assertEquals(a.checksum(), b.checksum())
        repeat(a.dayTicks * 3) {
            a.step()
            b.step()
            assertEquals(upkeep(a), upkeep(b), "on tick ${a.tick}")
        }
        assertEquals(a.checksum(), b.checksum())
        assertTrue(a.companies[0].trackTiles > 50)
    }
}
