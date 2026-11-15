package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A rival's railway planned a little each tick: the same as in one go, and carried on exactly from a save. */
class RailJobTest {
    private fun world(): World {
        val w = World(NewGame(160, 160, 5, 1930, Region.MIXED, towns = 3, industries = 0, rivals = 1))
        w.rivals[0].thinkOn = Int.MAX_VALUE
        w.companies[w.rivals[0].company].money = 10_000_000
        return w
    }

    /** Pairs of the biggest towns, as one job that builds any line that'll go. */
    private fun job(w: World): RailJob {
        val towns = w.towns.sortedByDescending { it.population }
        val picks = ArrayList<RailPick>()
        for (t1 in towns) for (t2 in towns) if (t1.id < t2.id) picks += RailPick(t1.id, t2.id, Cargo.PASSENGERS, 0, railSite(t1, t2.x, t2.y), railSite(t2, t1.x, t1.y))
        // Nothing more opened once it's done.
        return RailJob(picks.take(8), 0, loose = false).also { it.running = 0; it.from = 99 }
    }

    private fun serves(w: World) = { s: Station, _: Boolean -> (w.stationPeople[s.id] ?: 0) > 0 }

    @Test
    fun pausedEveryFewStepsItBuildsTheSame() {
        val w = world()
        val a = Save.read(Save.write(w))
        val b = Save.read(Save.write(w))
        val ja = job(a)
        ja.left = Int.MAX_VALUE
        val ra = a.rivals[0]
        val ta = a.runPick(a.companies[ra.company], ja, serves(a)) { ra.routes += it }
        val jb = job(b)
        val rb = b.rivals[0]
        var goes = 0
        var tb: Tried? = null
        while (tb == null) {
            jb.left = 37
            tb = b.runPick(b.companies[rb.company], jb, serves(b)) { rb.routes += it }
            goes++
        }
        assertEquals(Tried.BUILT, ta)
        assertEquals(ta, tb)
        assertTrue(goes > 20, "only $goes goes")
        assertEquals(ra.routes.size, rb.routes.size)
        assertEquals(a.checksum(), b.checksum())
        assertTrue((0 until a.width * a.height).all { a.track[it] == b.track[it] }, "different track")
    }

    @Test
    fun aSaveMidSearchCarriesOnTheSame() = savedMidway(150) { (it.search?.expanded ?: 0) >= 300 }

    @Test
    fun aSaveMidFloodCarriesOnTheSame() = savedMidway(40) { it.reach != null && it.reach!!.head > 20 }

    /** Saves once [at] holds for the job, ticks taking [states] search steps, and runs on the copy and the first. */
    private fun savedMidway(states: Int, at: (RailJob) -> Boolean) {
        val w = world()
        w.railTickStates = states
        val r = w.rivals[0]
        r.job = job(w)
        var ticks = 0
        while (!at(r.job!!)) { w.step(); ticks++ }
        val bytes = Save.write(w)
        val copy = Save.read(bytes)
        copy.railTickStates = states
        val rc = copy.rivals[0]
        assertTrue(at(rc.job!!), "not saved as it was")
        assertEquals(w.checksum(), copy.checksum())
        // A save of the copy is the same as the first.
        assertTrue(Save.write(copy).contentEquals(bytes), "saved again differently")
        while (r.job != null) {
            w.step()
            copy.step()
            ticks++
            assertEquals(w.checksum(), copy.checksum(), "out of step at tick ${w.tick}")
        }
        assertNull(rc.job)
        assertTrue(ticks > 20, "done in $ticks ticks")
        assertTrue(r.routes.any { it.rail }, "nothing built")
        repeat(Calendar.TICKS_PER_DAY * 3) { w.step(); copy.step() }
        assertEquals(w.checksum(), copy.checksum())
        assertEquals(r.routes.size, rc.routes.size)
    }

    @Test
    fun twoWorldsKeepInStepThroughAJob() {
        val a = world()
        val b = world()
        a.railTickStates = 150
        b.railTickStates = 150
        a.rivals[0].job = job(a)
        b.rivals[0].job = job(b)
        var ticks = 0
        while (a.rivals[0].job != null) {
            a.step()
            b.step()
            ticks++
            assertEquals(a.checksum(), b.checksum(), "out of step at tick ${a.tick}")
        }
        assertNull(b.rivals[0].job)
        assertTrue(ticks > 20, "done in $ticks ticks")
    }

    @Test
    fun aJobGoesWithItsCompany() {
        val w = world()
        val r = w.rivals[0]
        r.job = job(w)
        w.companies[r.company].gone = true
        w.step()
        assertNull(r.job)
    }

    @Test
    fun anOlderSaveLoadsWithNoJob() {
        val w = world()
        w.railTickStates = 150
        w.rivals[0].job = job(w)
        repeat(5) { w.step() }
        assertNotNull(w.rivals[0].job)
        assertNull(Save.read(Save.write(w, version = 57)).rivals[0].job)
    }
}
