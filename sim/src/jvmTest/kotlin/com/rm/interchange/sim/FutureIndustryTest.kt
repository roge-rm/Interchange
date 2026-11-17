package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FutureIndustryTest {
    private fun kind(w: World, key: String) = w.roster.industries.first { it.key == "base:$key" }

    /** A flat map from [year] with no towns or industries. */
    private fun flat(year: Int, future: Boolean = true, size: Int = 96): World {
        val w = World(NewGame(size, size, 1, year, towns = 0, industries = 0, weather = false, handling = false, eraCosts = false, future = future))
        for (i in w.corners.indices) w.corners[i] = 3
        w.trees.fill(0)
        w.companies[0].money = 100_000_000
        return w
    }

    /** An industry of [key] put down by hand at ([x], [y]). */
    private fun place(w: World, key: String, x: Int, y: Int): Industry {
        val k = kind(w, key)
        val ind = Industry(w.industries.size, k.id, x, y, 0, -1)
        w.industries += ind
        for (ty in y until y + k.along(0)) for (tx in x until x + k.across(0)) w.industryOf[w.index(tx, ty)] = (ind.id + 1).toShort()
        w.catchmentDirty = true
        return ind
    }

    /** Runs [w]'s first-of-the-month industry work from its start until [until], each month calling [each] with its year. */
    private fun months(w: World, until: Int, each: (Int) -> Unit = {}) {
        var d = 0
        while (true) {
            val year = Calendar.year(w.startYear, d)
            if (year >= until) return
            if (Calendar.dayOfMonth(w.startYear, d) == 1) {
                w.tick = d.toLong() * w.dayTicks
                w.industriesMonth()
                each(year)
            }
            d++
        }
    }

    @Test
    fun anOldSaveLoadsAndPlaysAsBefore() {
        // A v75 game with future off and a 2040 start plays the same read back as 75 or 76, and nothing new opens.
        val g = NewGame(128, 128, 6, 2040, Region.SHIELD, towns = 8, industries = 6, rivals = 2, rivalLevel = RivalLevel.HARD, future = false)
        val w = World(g)
        assertEquals(Cargo.COUNT, w.cargoCount)
        repeat(3_000) { w.step() }
        val old = Save.read(Save.write(w, version = 75))
        val now = Save.read(Save.write(w))
        assertEquals(w.checksum(), old.checksum())
        assertEquals(w.checksum(), now.checksum())
        while (w.day < 1_500) { w.step(); old.step(); now.step() }
        assertEquals(w.checksum(), old.checksum())
        assertEquals(w.checksum(), now.checksum())
        assertTrue(w.industries.none { w.roster.industries[it.kind].future })
        assertTrue(w.news.none { it.kind == News.INDUSTRY_CLOSING })
        assertEquals(Cargo.COUNT, old.cargoCount)
    }

    @Test
    fun theNewChainMakesAndTakes() {
        val w = flat(2040)
        assertEquals(Cargo.COUNT + 3, w.cargoCount)
        val lithium = w.roster.cargo.of("lithium")
        val batteries = w.roster.cargo.of("batteries")
        val hydrogen = w.roster.cargo.of("hydrogen")
        assertEquals(listOf(Cargo.COUNT, Cargo.COUNT + 1, Cargo.COUNT + 2), listOf(lithium, batteries, hydrogen))
        assertEquals("Hydrogène", w.roster.cargo[hydrogen].name("fr"))
        assertTrue(w.townWants(batteries) && w.townWants(hydrogen) && !w.townWants(lithium))
        val mine = place(w, "lithium_mine", 10, 12)
        val plant = place(w, "battery_plant", 30, 12)
        val h2 = place(w, "hydrogen_plant", 50, 12)
        val made = HashMap<Int, IntArray>()
        repeat(30) { w.industriesDay(made) }
        assertTrue(mine.made[lithium] > 0)
        assertTrue(h2.made[hydrogen] > 0)
        val bp = kind(w, "battery_plant")
        assertTrue(bp.takes(lithium) && bp.takes(Cargo.NICKEL) && !bp.takes(Cargo.COAL))
        // Two lithium and a nickel make two batteries.
        w.bring(plant, lithium, 200)
        w.bring(plant, Cargo.NICKEL, 100)
        repeat(10) { w.industriesDay(made) }
        assertEquals(200, plant.made[batteries])
        // Their wagons and trucks are on sale only with future on.
        val hauler = w.roster.engines.first { it.key == "base:battery_hauler" }
        assertTrue(hauler.future)
        assertTrue(batteries in hauler.cargoes)
        assertTrue(hauler in w.roster.availableRoad(2040, future = true))
        assertFalse(hauler in w.roster.availableRoad(2040, future = false))
        assertFalse(w.roster.engines.first { it.key == "base:quad_dump" }.future)
    }

    @Test
    fun theNewKindsOpenAfterTheirYearsAndOnlyWithFutureOn() {
        fun kinds(year: Int, future: Boolean) = World(NewGame(256, 256, 3, year, Region.MIXED, towns = 4, industries = 3, future = future)).let { w ->
            w.industries.map { w.roster.industries[it.kind].key.removePrefix("base:") }.toSet()
        }
        val future = setOf("lithium_mine", "battery_plant", "hydrogen_plant")
        assertTrue(kinds(2020, true).intersect(future).isEmpty())
        assertTrue(kinds(2045, true).intersect(future).isNotEmpty())
        assertTrue(kinds(2045, false).intersect(future).isEmpty())
        // Weights by the decade: future kinds more from 2030, coal less.
        val on = flat(2020)
        val off = flat(2020, future = false)
        val lithium = kind(on, "lithium_mine")
        val coal = kind(on, "coal_mine")
        val w = lithium.weight(Region.MIXED)
        assertEquals(listOf(w, 8 * w, 12 * w, 16 * w, 16 * w), listOf(2029, 2035, 2045, 2055, 2080).map { on.openWeight(lithium, it) })
        assertEquals(0, off.openWeight(lithium, 2045))
        val c = coal.weight(Region.MIXED)
        assertEquals(listOf(c, 2 * c, c, c / 2, 0), listOf(2029, 2035, 2045, 2055, 2065).map { on.openWeight(coal, it) })
        val factory = kind(on, "factory")
        assertEquals(factory.weight(Region.MIXED), on.openWeight(factory, 2029))
        assertEquals(4 * factory.weight(Region.MIXED), on.openWeight(factory, 2035))
        assertEquals(c, off.openWeight(coal, 2060))
        assertEquals(0, on.fadeDecades(kind(on, "factory"), 2060))
        assertEquals(2030, kind(on, "steel_mill").fades)
    }

    /** Warnings a decade with fading steel mills, as a share in thousandths of those still open at its start. */
    private fun warnings(future: Boolean): Map<Int, Int> {
        val w = flat(2020, future, size = 512)
        repeat(200) { place(w, "steel_mill", 4 + it % 20 * 24, 4 + it / 20 * 24) }
        val warned = HashMap<Int, Int>()
        val open = HashMap<Int, Int>()
        w.news.clear()
        months(w, 2070) { year ->
            val decade = year / 10 * 10
            // Counted after the decade's first month, so add back any told then.
            open.getOrPut(decade) { w.industries.count { !it.closed && it.closing == 0 } + w.news.count { it.kind == News.INDUSTRY_CLOSING } }
            warned[decade] = (warned[decade] ?: 0) + w.news.count { it.kind == News.INDUSTRY_CLOSING }
            w.news.clear()
        }
        return open.keys.associateWith { (warned[it] ?: 0) * 1000 / open.getValue(it).coerceAtLeast(1) }
    }

    @Test
    fun coalClosesMoreEachDecadeFrom2030WithFutureOn() {
        val on = warnings(true)
        assertEquals(0, on[2020])
        assertTrue(on.getValue(2030) > 100, "$on")
        assertTrue(on.getValue(2040) > on.getValue(2030), "$on")
        assertTrue(on.getValue(2050) > on.getValue(2040), "$on")
        val off = warnings(false)
        assertTrue(off.values.all { it == 0 }, "$off")
    }

    @Test
    fun theWarningComesBeforeTheClosing() {
        val w = flat(2045, size = 128)
        val mills = List(16) { place(w, "steel_mill", 4 + it % 4 * 24, 4 + it / 4 * 24) }
        val stories = ArrayList<Pair<Int, Int>>()
        var month = 0
        months(w, 2075) {
            month++
            for (n in w.news) if (n.kind == News.INDUSTRY_CLOSING || n.kind == News.INDUSTRY_CLOSES) stories += n.kind to n.about
            w.news.clear()
        }
        val told = stories.filter { it.first == News.INDUSTRY_CLOSING }.map { it.second }
        assertTrue(told.isNotEmpty())
        for (id in told) {
            val warn = stories.indexOf(News.INDUSTRY_CLOSING to id)
            val close = stories.indexOf(News.INDUSTRY_CLOSES to id)
            assertTrue(close > warn, "closing of $id came before its warning")
            assertTrue(mills[id].closed)
        }
        // Six months between them: one told closes on the sixth first of the month after.
        val w2 = flat(2045, size = 128)
        val mill = place(w2, "steel_mill", 10, 10)
        var at = -1
        var shut = -1
        month = 0
        months(w2, 2100) {
            month++
            if (at < 0 && mill.closing > 0) at = month
            if (shut < 0 && mill.closed) shut = month
        }
        assertTrue(at > 0)
        assertEquals(at + CLOSING_NOTICE, shut)
    }

    @Test
    fun aSaveKeepsAClosingAndStep() {
        val w = World(NewGame(128, 128, 6, 2048, Region.MARITIMES, towns = 8, industries = 6, rivals = 2, rivalLevel = RivalLevel.HARD))
        repeat(3_000) { w.step() }
        val mill = place(w, "steel_mill", 2, 2)
        mill.closing = 4
        assertEquals(76, Save.VERSION)
        val again = Save.read(Save.write(w))
        assertEquals(4, again.industries[mill.id].closing)
        assertEquals(w.checksum(), again.checksum())
        repeat(3_000) { w.step(); again.step() }
        assertEquals(w.checksum(), again.checksum())
    }

    @Test
    fun unservedFutureCargoLeavesApprovalAsBefore() {
        // Ratings of 130 over the non-future cargo are 50%, a rise of 10; a served future cargo at 0 makes it 48%, all 3 44%.
        fun approval(served: Boolean): Int {
            val w = flat(1990)
            assertEquals(Cargo.COUNT + 3, w.cargoCount)
            w.apply(Command.Rail(0, 10, 10, 20, 10, Track.X, remove = false))
            w.apply(Command.Station(0, 12, 10, 15, 10, Track.X))
            val s = w.stations[0]!!
            val t = Town(0, "Town", s.signX, s.signY + 2, 3)
            w.towns += t
            val before = t.approval[0]
            for (c in 0 until w.cargoCount) s.rating[c] = if (c < Cargo.COUNT) 130 else 0
            if (served) s.lastCall[Cargo.COUNT] = 0
            w.passengersMonth()
            return t.approval[0] - before
        }
        assertEquals(10, approval(served = false))
        assertEquals(0, approval(served = true))
    }
}
