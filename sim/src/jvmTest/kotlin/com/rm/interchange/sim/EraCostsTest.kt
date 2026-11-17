package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EraCostsTest {
    /** A flat world with nothing on it, started in [year]. */
    private fun world(year: Int, region: Region = Region.MIXED, era: Boolean = true, weather: Boolean = false): World {
        val w = World(NewGame(64, 64, 1, year, region, towns = 0, industries = 0, weather = weather, eraCosts = era))
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        return w
    }

    /** What [base] of [mix] costs against plain inflation, per mille. */
    private fun World.share(base: Long, mix: IntArray, i: Int = -1) = build(base, mix, i) * 1_000 / price(base)

    private fun engine(w: World, kind: VehicleKind, traction: Int) = w.roster.engines.first { it.enabled && it.kind == kind && it.traction == traction && it.running > 0 }

    @Test
    fun anOldSaveCostsAsBeforeAndKeepsInStep() {
        val g = NewGame(128, 128, 6, 1930, Region.MIXED, towns = 8, industries = 6, rivals = 2, rivalLevel = RivalLevel.HARD)
        val old = Save.read(Save.write(World(g), version = 70))
        assertFalse(old.settings.eraCosts)
        val off = Save.read(Save.write(World(g.copy(eraCosts = false, energy = false, future = false))))
        assertEquals(off.checksum(), old.checksum())
        for (mix in listOf(Mix.TRACK, Mix.PLATFORM, Mix.SPANS[Spans.TUNNEL], Mix.LABOUR)) assertEquals(old.price(9_000), old.build(9_000, mix, old.index(5, 5), true))
        val diesel = engine(old, VehicleKind.ENGINE, Traction.DIESEL)
        assertEquals(old.price(diesel.running), old.running(diesel))
        assertNull(old.view().prices)
        while (old.day < 365) { old.step(); off.step() }
        assertEquals(off.checksum(), old.checksum())
        assertEquals(off.companies.map { it.money }, old.companies.map { it.money })
        assertTrue(old.news.none { it.kind == News.FUEL_SHOCK })
    }

    @Test
    fun theOverallLevelStaysNearInflation() {
        for (year in 1836..2100) {
            val mean = (0 until 3).sumOf { EraCosts.index(it, year) } / 3
            assertTrue(mean in 950..1_050, "$year $mean")
        }
    }

    @Test
    fun aTunnelCostsLessAgainstACuttingLateThanEarly() {
        val early = world(1880)
        val late = world(2000)
        val tunnel = Spans.COST[Spans.TUNNEL]
        fun against(w: World) = w.share(tunnel, Mix.SPANS[Spans.TUNNEL]) * 1_000 / w.share(Prices.TERRAFORM, Mix.LABOUR)
        assertTrue(against(early) > against(late) * 3 / 2, "${against(early)} ${against(late)}")
        // And a timber trestle against a steel truss the same way.
        fun trestle(w: World) = w.share(Spans.COST[Spans.TRESTLE], Mix.SPANS[Spans.TRESTLE]) * 1_000 / w.share(Spans.COST[Spans.TRUSS], Mix.SPANS[Spans.TRUSS])
        assertTrue(trestle(early) < trestle(late))
    }

    @Test
    fun aStationCostsLessIn1960ThanIn1870AgainstInflation() {
        assertTrue(world(1960).share(Prices.PLATFORM, Mix.PLATFORM) < world(1870).share(Prices.PLATFORM, Mix.PLATFORM))
        assertTrue(EraCosts.factor(Mix.PLATFORM, 1960) < EraCosts.factor(Mix.SPANS[Spans.TRUSS], 1870))
    }

    @Test
    fun steamRunsDearerWithCoalAndDieselJumpsIn1973() {
        val steam = engine(world(1880), VehicleKind.ENGINE, Traction.STEAM)
        fun share(w: World, e: Engine) = w.running(e) * 1_000 / w.price(e.running)
        assertTrue(share(world(1920), steam) > share(world(1880), steam))
        val diesel = engine(world(1972), VehicleKind.ENGINE, Traction.DIESEL)
        val before = share(world(1972), diesel)
        val after = share(world(1973), diesel)
        assertTrue(after > before * 6 / 5, "$before $after")
    }

    @Test
    fun electricIsCheaperThanDieselAfter1960() {
        val w = world(1965)
        val electric = engine(w, VehicleKind.ENGINE, Traction.ELECTRIC)
        val diesel = engine(w, VehicleKind.ENGINE, Traction.DIESEL)
        for (year in 1961..2100) {
            val fuels = IntArray(EraCosts.FUELS) { EraCosts.fuel(it, year) }
            fun perMille(e: Engine) = EraCosts.running(e, fuels).sum() * 1_000 / e.running
            assertTrue(perMille(electric) < perMille(diesel), "$year")
        }
        // Wagons and horses burn nothing.
        assertEquals(-1, EraCosts.fuelOf(w.roster.engines.first { it.kind == VehicleKind.WAGON }))
        assertEquals(-1, EraCosts.fuelOf(engine(w, VehicleKind.ROAD, Traction.HORSE)))
    }

    @Test
    fun aColdRegionKeepsTrackUpDearer() {
        fun upkeep(region: Region): Long {
            val w = world(1950, region)
            w.apply(Command.Rail(0, 3, 10, 50, 10, Track.X, remove = false))
            val before = w.companies[0].thisYear[Books.UPKEEP]
            while (w.day < 60) w.step()
            return before - w.companies[0].thisYear[Books.UPKEEP]
        }
        val north = upkeep(Region.NORTH)
        val mild = upkeep(Region.MARITIMES)
        assertTrue(north > mild, "$north $mild")
        assertTrue(EraCosts.roadUpkeep(Region.MARITIMES, 1950) > EraCosts.roadUpkeep(Region.PRAIRIES, 1950))
        // Warmer winters later make the north cheaper to keep.
        assertTrue(EraCosts.trackUpkeep(Region.NORTH, 2080) < EraCosts.trackUpkeep(Region.NORTH, 1950))
    }

    @Test
    fun foundationsCostMoreInTheColdAndDoubleOnPermafrost() {
        val north = world(1950, Region.NORTH)
        val mild = world(1950, Region.MARITIMES)
        val i = north.index(20, 20)
        assertEquals(EraCosts.PERMAFROST, north.frostAt(i))
        assertTrue(mild.frostAt(i) in 1_001 until EraCosts.PERMAFROST)
        assertTrue(north.build(Prices.FOUNDATION, Mix.FOUNDATION, i, true) > mild.build(Prices.FOUNDATION, Mix.FOUNDATION, i, true))
        assertTrue(north.costMark(i) and CostMark.FROST != 0)
        // Snow sheds cost less where wood is local.
        assertTrue(north.woodBuild(SHED_COST, Mix.SHED, i) > mild.woodBuild(SHED_COST, Mix.SHED, i))
    }

    @Test
    fun buildingOnFrozenGroundCostsMore() {
        val w = world(1950, Region.NORTH)
        val c = Command.Rail(0, 3, 10, 20, 10, Track.X, remove = false)
        val thawed = w.railCost(c).first
        for (x in 3..20) w.weather.frost[w.index(x, 10)] = 40
        val frozen = w.railCost(c).first
        assertTrue(frozen > thawed, "$thawed $frozen")
        assertTrue(w.costMark(w.index(5, 10)) and CostMark.WINTER != 0)
        // What's charged is what was asked.
        val money = w.companies[0].money
        w.apply(c)
        assertEquals(frozen, money - w.companies[0].money)
    }

    @Test
    fun landNearATownCostsMore() {
        val w = world(1950)
        w.towns += Town(0, "Town", 30, 30, 3).also { it.population = 2_000 }
        assertTrue(w.build(Prices.PLATFORM, Mix.PLATFORM, w.index(30, 30)) > w.build(Prices.PLATFORM, Mix.PLATFORM, w.index(2, 2)))
    }

    @Test
    fun rivalsPaybackSeesFuelPrices() {
        val on = world(1980)
        val off = world(1980, era = false)
        val plane = on.roster.engines.first { it.enabled && it.kind == VehicleKind.AIRCRAFT && it.from <= 1980 && it.running > 0 }
        fun margin(w: World) = w.craftMargin(plane, Cargo.PASSENGERS, 200, 40, 40, 100_000, 1_000, 5, 4, 2)
        assertTrue(margin(on) < margin(off), "${margin(on)} ${margin(off)}")
        assertTrue(on.running(plane) > off.running(plane))
    }

    @Test
    fun aFuelShockMakesTheNewsAndTheViewCarriesPrices() {
        val w = world(1972)
        while (Calendar.year(w.startYear, w.day) < 1973) w.step()
        assertTrue(w.news.any { it.kind == News.FUEL_SHOCK && it.about == 0 })
        val p = assertNotNull(w.view().prices)
        assertEquals(1973, p.year)
        assertEquals(EraCosts.fuel(EraCosts.DIESEL, 1973), p.fuels[EraCosts.DIESEL])
        assertEquals(listOf(1972, 1973), p.years.toList())
        assertEquals(EraCosts.index(EraCosts.LABOUR, 1972), p.indexHistory[EraCosts.LABOUR][0])
        val diesel = engine(w, VehicleKind.ENGINE, Traction.DIESEL)
        // Split, it rounds a little differently from what's paid.
        assertTrue(kotlin.math.abs(w.running(diesel) - p.running(diesel).sum()) <= w.running(diesel) / 100 + 1, "${p.running(diesel).toList()}")
    }

    @Test
    fun aGameStartedInAShockYearHearsOfIt() {
        val w = world(1979)
        assertEquals(1, w.news.count { it.kind == News.FUEL_SHOCK && it.about == 1 })
        assertEquals(1, w.chronicle.count { it.kind == News.FUEL_SHOCK })
        val off = world(1979, era = false)
        assertTrue(off.news.none { it.kind == News.FUEL_SHOCK })
        // News only: the game goes on as one started the year before would, and a load doesn't tell it again.
        assertTrue(world(1978).news.none { it.kind == News.FUEL_SHOCK })
        val loaded = Save.read(Save.write(w))
        assertTrue(loaded.news.none { it.kind == News.FUEL_SHOCK })
        assertEquals(1, loaded.chronicle.count { it.kind == News.FUEL_SHOCK })
        while (w.day < 400) w.step()
        assertEquals(1, w.chronicle.count { it.kind == News.FUEL_SHOCK })
    }

    @Test
    fun aLongGameKeepsTheShockYearsInTheView() {
        val w = world(1836)
        val p = assertNotNull(w.view().prices)
        assertEquals(listOf(1836), p.years.toList())
        val c = world(1900)
        // 1 February 2020: 120 years with 29 leap days, and a month.
        c.tick = c.dayTicks * (120L * 365 + 29 + 31)
        assertEquals(2020, Calendar.year(c.startYear, c.day))
        val q = assertNotNull(c.view().prices)
        assertEquals(q.years.sorted(), q.years.toList())
        assertEquals(q.years.distinct(), q.years.toList())
        for (y in listOf(1972, 1973, 1978, 1979, 2007, 2008, 2020)) assertTrue(y in q.years, "$y")
        assertTrue(q.years.size <= PRICE_POINTS + 7)
    }

    @Test
    fun twoWorldsKeepInStep() {
        val g = NewGame(128, 128, 3, 1968, Region.SHIELD, towns = 8, industries = 6, rivals = 2, rivalLevel = RivalLevel.HARD)
        val a = World(g)
        val b = World(g)
        while (a.day < 200) { a.step(); b.step() }
        val c = Save.read(Save.write(a))
        assertTrue(c.settings.eraCosts)
        while (a.day < 600) { a.step(); b.step(); c.step() }
        assertEquals(a.checksum(), b.checksum())
        assertEquals(a.checksum(), c.checksum())
        assertEquals(a.companies.map { it.money }, c.companies.map { it.money })
    }
}
