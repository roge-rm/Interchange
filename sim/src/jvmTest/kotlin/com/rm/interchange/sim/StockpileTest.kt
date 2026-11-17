package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StockpileTest {
    /** A flat map with industries of [keys] placed by hand along y 12, a station by each, track between them. */
    private fun world(vararg keys: String, settings: NewGame = NewGame(96, 64, 1, 1900, towns = 0, industries = 0, weather = false)): World {
        val w = World(settings)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Rail(0, 3, 10, 90, 10, Track.X, remove = false))
        for ((n, key) in keys.withIndex()) {
            val x = 10 + n * 35
            val kind = w.roster.industries.first { it.key == key }
            val ind = Industry(w.industries.size, kind.id, x, 12, 0, -1)
            w.industries += ind
            for (ty in 12 until 12 + kind.along(0)) for (tx in x until x + kind.across(0)) w.industryOf[w.index(tx, ty)] = (ind.id + 1).toShort()
            w.apply(Command.Station(0, x - 1, 10, x + 2, 10, Track.X))
        }
        w.catchmentDirty = true
        return w
    }

    private fun day(w: World): HashMap<Int, IntArray> {
        if (w.catchmentDirty) w.workOutCatchment()
        return HashMap<Int, IntArray>().also { w.industriesDay(it) }
    }

    /** Steps [w] on to the next first of a month. */
    private fun nextMonth(w: World) {
        do repeat(w.dayTicks) { w.step() } while (Calendar.dayOfMonth(w.settings.startYear, w.day) != 1)
    }

    @Test
    fun anOldSaveLoadsWithoutStockpilesAndKeepsInStep() {
        val g = NewGame(128, 128, 6, 1930, Region.MIXED, towns = 8, industries = 6, rivals = 2, rivalLevel = RivalLevel.HARD)
        val old = Save.read(Save.write(World(g), version = 68))
        assertFalse(old.settings.stockpiles)
        assertFalse(old.settings.contracts)
        val off = Save.read(Save.write(World(g.copy(stockpiles = false, contracts = false, handling = false, eraCosts = false, energy = false, future = false))))
        assertEquals(off.checksum(), old.checksum())
        while (old.day < 365) { old.step(); off.step() }
        assertEquals(off.checksum(), old.checksum())
        assertTrue(old.contracts.isEmpty() && old.industries.all { it.store.sum() == 0 && it.stock.sum() == 0 })
    }

    @Test
    fun aDeliveryIsWorkedThroughOverDays() {
        val w = world("base:sawmill")
        val mill = w.industries[0]
        w.bring(mill, Cargo.TIMBER, 1000)
        assertEquals(1000, mill.store[Cargo.TIMBER])
        day(w)
        assertEquals(1000 - WORK_RATE, mill.store[Cargo.TIMBER])
        assertEquals(WORK_RATE, mill.made[Cargo.LUMBER])
        repeat(20) { day(w) }
        assertEquals(0, mill.store[Cargo.TIMBER])
        assertEquals(1000, mill.made[Cargo.LUMBER])
    }

    @Test
    fun aSteelMillWithOnlyOreMakesNothingUntilCoalComes() {
        val w = world("base:steel_mill")
        val mill = w.industries[0]
        w.bring(mill, Cargo.IRON_ORE, 300)
        repeat(5) { day(w) }
        assertEquals(0, mill.made[Cargo.STEEL])
        assertEquals(300, mill.store[Cargo.IRON_ORE])
        w.bring(mill, Cargo.COAL, 10)
        day(w)
        // Ten batches of 2 ore and 1 coal, 2 steel each.
        assertEquals(20, mill.made[Cargo.STEEL])
        assertEquals(280, mill.store[Cargo.IRON_ORE])
        assertEquals(0, mill.store[Cargo.COAL])
    }

    @Test
    fun aRecipeBiggerThanADaysWorkComesAtTheRateOverTime() {
        val w = world("base:steel_mill")
        val mill = w.industries[0]
        // A work rate of 1 a day against a batch of 3: one batch every third day, not one a day.
        mill.level = 0
        assertEquals(1, workRate(mill))
        w.bring(mill, Cargo.IRON_ORE, 80)
        w.bring(mill, Cargo.COAL, 40)
        repeat(30) { day(w) }
        assertEquals(20, mill.made[Cargo.STEEL])
        assertEquals(60, mill.store[Cargo.IRON_ORE])
        assertEquals(30, mill.store[Cargo.COAL])
    }

    @Test
    fun aFullStoreStopsTakingThatCargo() {
        val w = world("base:steel_mill")
        val mill = w.industries[0]
        val s = w.stations[0]!!
        assertTrue(w.accepts(s, Cargo.COAL))
        mill.store[Cargo.COAL] = storeCap(mill)
        assertFalse(w.accepts(s, Cargo.COAL))
        assertEquals(WhyNot.NOT_ACCEPTED, w.whyNot(-1, s.id, Cargo.COAL))
        assertTrue(w.accepts(s, Cargo.IRON_ORE))
    }

    @Test
    fun aMillKeepsWorkingThroughAWinterOnStock() {
        val w = world("base:sawmill")
        val mill = w.industries[0]
        w.bring(mill, Cargo.TIMBER, storeCap(mill))
        // Three months with nothing brought, a full day's work each day.
        repeat(STORE_DAYS) { day(w) }
        assertEquals(storeCap(mill), mill.made[Cargo.LUMBER])
    }

    @Test
    fun perishablesSpoilInStoreAndTheRestKeep() {
        val w = world("base:cannery", "base:sawmill")
        val cannery = w.industries[0]
        val mill = w.industries[1]
        w.bring(cannery, Cargo.FISH, 3000)
        w.bring(mill, Cargo.TIMBER, 3000)
        day(w)
        assertEquals(3000 - 3000 / SPOIL_DAYS - WORK_RATE, cannery.store[Cargo.FISH])
        assertEquals(3000 - WORK_RATE, mill.store[Cargo.TIMBER])
    }

    @Test
    fun outputStockIsTakenByALaterCall() {
        val w = world("base:coal_mine")
        val mine = w.industries[0]
        val s = w.stations[0]!!
        repeat(30) { day(w) }
        val stocked = mine.stock[Cargo.COAL]
        assertTrue(stocked >= 50, "stock $stocked")
        assertEquals(0, mine.moved[Cargo.COAL])
        // A train calls, and from then the station gets the day's coal and some of the stock.
        s.lastCall[Cargo.COAL] = w.tick
        s.rating[Cargo.COAL] = 255
        var got = 0
        val made = mine.made[Cargo.COAL]
        repeat(10) { got += day(w)[s.id]?.get(Cargo.COAL) ?: 0 }
        assertTrue(got > mine.made[Cargo.COAL] - made, "got $got of ${mine.made[Cargo.COAL] - made} made")
        assertTrue(mine.stock[Cargo.COAL] < stocked)
    }

    /** A contract for 100 coal a month to the steel mill in [w], offered and accepted by company 0. */
    private fun contract(w: World, season: Int = Contract.ALL_YEAR, until: Int = 0): Contract {
        val k = Contract(Cargo.COAL, 0, -1, 100, 6, season, until, 5_000, 10_000)
        k.offerUntil = Int.MAX_VALUE
        w.contracts += k
        w.apply(Command.AcceptContract(0, w.contracts.size - 1))
        assertEquals(Contract.RUNNING, k.status)
        return k
    }

    @Test
    fun aContractPaysWhenMet() {
        val w = world("base:steel_mill")
        val k = contract(w)
        nextMonth(w)
        w.contractDelivered(0, Cargo.COAL, 100, null, w.industries[0])
        val before = w.companies[0].thisYear[Books.INCOME]
        nextMonth(w)
        assertEquals(1, k.met)
        assertEquals(100, k.lastCount)
        assertEquals(before + w.pay(5_000), w.companies[0].thisYear[Books.INCOME])
        assertTrue(w.chronicle.any { it.kind == News.CONTRACT_MET })
    }

    @Test
    fun threeMissesEndAContractWithAPenalty() {
        val w = world("base:steel_mill")
        val k = contract(w)
        nextMonth(w)
        val before = w.companies[0].thisYear[Books.INCOME]
        repeat(3) { nextMonth(w) }
        assertEquals(3, k.misses)
        assertEquals(Contract.FAILED, k.status)
        assertEquals(before - w.pay(10_000), w.companies[0].thisYear[Books.INCOME])
        assertTrue(w.chronicle.any { it.kind == News.CONTRACT_FAILED })
    }

    @Test
    fun aSeasonalContractFailsIfTheTotalIsNotInByItsDate() {
        val w = world("base:steel_mill")
        val now = w.settings.startYear * 12 + Calendar.month(w.settings.startYear, w.day)
        val k = contract(w, Contract.BY_DATE, now + 1)
        w.contractDelivered(0, Cargo.COAL, 60, null, w.industries[0])
        nextMonth(w)
        assertEquals(Contract.RUNNING, k.status)
        w.contractDelivered(0, Cargo.COAL, 30, null, w.industries[0])
        nextMonth(w)
        assertEquals(90, k.count)
        assertEquals(Contract.FAILED, k.status)
    }

    @Test
    fun aCompanyStartingAgainOrBoughtLosesItsContractsWithoutPenalty() {
        val w = world("base:steel_mill")
        val k = contract(w)
        val before = w.companies[0].thisYear[Books.INCOME]
        w.restart(w.companies[0])
        assertEquals(Contract.CANCELLED, k.status)
        repeat(4) { nextMonth(w) }
        assertEquals(0L, w.companies[0].thisYear[Books.INCOME] - before)
        w.apply(Command.NewCompany(1, "Other"))
        val bought = contract(w)
        w.takeOver(w.companies[0], w.companies[1])
        assertEquals(Contract.CANCELLED, bought.status)
        w.restart(w.companies[0])
        val gone = contract(w)
        w.dissolve(w.companies[0])
        assertEquals(Contract.CANCELLED, gone.status)
    }

    @Test
    fun coalToATownStationByAnIndustryCountsForTheTownsContract() {
        val w = world("base:steel_mill")
        w.towns += Town(0, "Ayton", 12, 8, 3)
        val s = w.stations[0]!!
        assertEquals(0, w.stationTown(s)?.id)
        val k = Contract(Cargo.COAL, -1, 0, 100, 3, Contract.WINTER, 0, 5_000, 10_000)
        k.offerUntil = Int.MAX_VALUE
        w.contracts += k
        w.apply(Command.AcceptContract(0, 0))
        // The mill there takes the coal and is paid for it; the town's contract counts it too.
        w.delivered(0, Cargo.COAL, 70, s, s, w.industries[0])
        assertEquals(70, k.count)
        w.delivered(1, Cargo.COAL, 50, s, s, w.industries[0])
        assertEquals(70, k.count)
    }

    @Test
    fun contractsComeWhenOnAndNotWhenOff() {
        val g = NewGame(128, 128, 4, 1930, Region.MIXED, towns = 8, industries = 6, weather = false)
        for (on in listOf(true, false)) {
            val w = World(g.copy(contracts = on))
            while (w.day < 365) w.step()
            assertEquals(on, w.contracts.isNotEmpty(), "contracts ${if (on) "on" else "off"}: ${w.contracts.size}")
            if (on) assertTrue(w.contracts.all { it.amount > 0 && it.reward > 0 && it.penalty > 0 })
        }
    }

    @Test
    fun aSave69KeepsInStep() {
        val g = NewGame(128, 128, 6, 1930, Region.MIXED, towns = 8, industries = 6, rivals = 2, rivalLevel = RivalLevel.HARD)
        val a = World(g)
        while (a.day < 200) a.step()
        a.industries.first { a.roster.industries[it.kind].stores(Cargo.COAL) || a.roster.industries[it.kind].stores(Cargo.TIMBER) }.let { ind ->
            val c = if (a.roster.industries[ind.kind].stores(Cargo.COAL)) Cargo.COAL else Cargo.TIMBER
            a.bring(ind, c, 500)
        }
        contract(a)
        val b = Save.read(Save.write(a))
        assertTrue(b.settings.stockpiles && b.settings.contracts)
        assertEquals(a.contracts.size, b.contracts.size)
        assertEquals(a.checksum(), b.checksum())
        repeat(60 * a.dayTicks + 13) { a.step(); b.step() }
        assertEquals(a.checksum(), b.checksum())
        assertTrue(a.industries.any { it.store.sum() > 0 || it.stock.sum() > 0 })
    }

    @Test
    fun rulesReadBroughtByCargoStockAndMade() {
        val w = world("base:steel_mill")
        val mill = w.industries[0]
        val kind = w.roster.industries[mill.kind]
        w.bring(mill, Cargo.COAL, 40)
        w.bring(mill, Cargo.IRON_ORE, 70)
        w.industriesMonth()
        val inputs = IndustryInputs(w, kind, mill, mill.x, mill.y, mill.turn)
        assertEquals(40L, Rule.parse("brought(coal)").eval(inputs))
        assertEquals(70L, Rule.parse("brought(iron_ore)").eval(inputs))
        assertEquals(110L, Rule.parse("stock(coal) + stock(iron_ore)").eval(inputs))
        day(w)
        w.industriesMonth()
        assertEquals(mill.madeLastMonth.sum().toLong(), IndustryInputs(w, kind, mill, mill.x, mill.y, mill.turn).let { Rule.parse("made").eval(it) })
        assertTrue(mill.madeLastMonth.sum() > 0)
    }

    /** A pack with a cargo [keeps] or not and an industry with [recipe] (a JSON object), taking timber and coal and making goods. */
    private fun pack(recipe: String, keeps: Boolean = false): Pack {
        val files = HashMap<String, ByteArray>()
        files["manifest.json"] = """{ "format": 3, "id": "mill", "version": 1, "name": { "en": "Mill" }, "needs": ["base"] }""".encodeToByteArray()
        files["cargo.json"] = """{ "cargo": [ { "id": "milk", "name": { "en": "Milk" }, "unit": { "en": "t" }, "rate": 40, "grace": 3, "decay": 4,
            "pace": 1, "kg": 1000, "colour": "#ffffff", "keeps": $keeps } ] }""".encodeToByteArray()
        files["industries.json"] = """{ "industries": [ { "id": "works", "name": { "en": "Works" }, "from": 1800, "until": 2100,
            "layout": ["coal_heap"], "accepts": ["timber", "coal"], "produces": { "goods": 0 }, "recipe": $recipe } ] }""".encodeToByteArray()
        return Packs.read(files)
    }

    @Test
    fun packsGiveRecipesAndCargoThatSpoils() {
        val roster = Roster.build(listOf(Packs.BASE, pack("""{ "inputs": { "timber": 3, "coal": 1 }, "makes": { "goods": 2 } }""")))
        val works = roster.industries.first { it.key == "mill:works" }
        assertEquals(3, works.recipeIn[Cargo.TIMBER])
        assertEquals(1, works.recipeIn[Cargo.COAL])
        assertEquals(2, works.recipeOut[Cargo.GOODS])
        assertFalse(roster.cargo.keeps[roster.cargo.of("milk")])
        assertTrue(roster.cargo.keeps[Cargo.COAL] && !roster.cargo.keeps[Cargo.FISH])
        val steel = Roster.BASE.industries.first { it.key == "base:steel_mill" }
        assertEquals(2, steel.recipeIn[Cargo.IRON_ORE])
        val bad = kotlin.test.assertFailsWith<BadPack> { pack("""{ "inputs": { "grain": 3 }, "makes": { "steel": 0 } }""") }.problems
        assertTrue("industries.json, \"works\": \"recipe\" amounts should be from 1 to 100" in bad, "$bad")
        assertTrue("industries.json, \"works\": recipe: \"grain\" should be in \"accepts\" too" in bad, "$bad")
        assertTrue("industries.json, \"works\": recipe: \"steel\" should be in \"produces\" too, at 0" in bad, "$bad")
        val shape = kotlin.test.assertFailsWith<BadPack> { pack("""{ "inputs": { "timber": 3 } }""") }.problems
        assertTrue(shape.any { "\"recipe\" should be" in it }, "$shape")
    }
}
