package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndustryTest {
    @Test
    fun aNewMapHasIndustriesOfEveryChainThatFit() {
        val w = World(NewGame(width = 256, height = 256, seed = 3, startYear = 1900))
        assertTrue(w.industries.size >= 20, "only ${w.industries.size}")
        val keys = w.industries.map { w.roster.industries[it.kind].key }.toSet()
        for (k in listOf("base:coal_mine", "base:steel_mill", "base:sawmill", "base:farm", "base:flour_mill")) assertTrue(k in keys, "no $k in $keys")
        // Every tile of each is marked as its, on dry land, clear of towns.
        for (ind in w.industries) {
            val kind = w.roster.industries[ind.kind]
            for (ty in ind.y until ind.y + kind.along(ind.turn)) for (tx in ind.x until ind.x + kind.across(ind.turn)) {
                val i = w.index(tx, ty)
                assertEquals(ind.id + 1, w.industryOf[i].toInt())
                assertTrue(!w.isWater(tx, ty) && w.road[i].toInt() == 0 && w.house[i].toInt() == 0)
            }
        }
    }

    @Test
    fun noIndustriesBeforeTheirTime() {
        val w = World(NewGame(width = 256, height = 256, seed = 3, startYear = 1850))
        assertTrue(w.industries.none { w.roster.industries[it.kind].key == "base:steel_mill" })
    }

    /** A coal mine at one end of a line and a steel mill at the other, placed by hand on flat land. */
    private fun chain(year: Int = 1900): World {
        val w = World(96, 64, 1, startYear = year)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        fun put(key: String, x: Int, y: Int) {
            val kind = w.roster.industries.first { it.key == key }
            val ind = Industry(w.industries.size, kind.id, x, y, 0, -1)
            w.industries += ind
            for (ty in y until y + kind.along(0)) for (tx in x until x + kind.across(0)) w.industryOf[w.index(tx, ty)] = (ind.id + 1).toShort()
        }
        put("base:coal_mine", 10, 12)
        put("base:steel_mill", 80, 12)
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 90, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 9, 10, 12, 10, Track.X))
        w.apply(Command.Station(0, 79, 10, 82, 10, Track.X))
        w.catchmentDirty = true
        return w
    }

    @Test
    fun stationsTakeWhatIndustriesInReachTake() {
        val w = chain()
        assertTrue(w.accepts(w.stations[1]!!, Cargo.COAL))
        assertTrue(!w.accepts(w.stations[0]!!, Cargo.COAL))
        assertTrue(!w.accepts(w.stations[1]!!, Cargo.PASSENGERS))
    }

    /** A mine makes a month's worth of cargo in 30 work days whatever the day length. */
    @Test
    fun industriesMakeCargoByWorkDays() {
        for (dayLength in listOf(1, 10)) {
            val w = World(NewGame(96, 64, 1, 1900, towns = 0, industries = 0, weather = false, dayLength = dayLength))
            val kind = w.roster.industries.first { it.key == "base:coal_mine" }
            w.industries += Industry(0, kind.id, 10, 12, 0, -1)
            // 25 work days, inside January at either length so no month's turn clears the tally.
            repeat(Calendar.TICKS_PER_DAY * 25) { w.step() }
            val made = w.industries[0].made[Cargo.COAL]
            assertTrue(made in 70..100, "made $made in 25 work days at day length $dayLength")
        }
    }

    @Test
    fun coalCarriedToTheMillIsMadeIntoSteel() {
        val w = chain(1905)
        val hopper = w.roster.engines.first { it.key == "base:hopper" }
        assertTrue(Cargo.COAL in hopper.cargoes)
        w.apply(Command.BuyTrain(0, 0, 4, hopper.id, 5, Cargo.COAL))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        repeat(Calendar.TICKS_PER_DAY * 200) { w.step() }
        val mine = w.industries[0]
        val mill = w.industries[1]
        assertTrue(mine.made[Cargo.COAL] + mine.madeLastMonth[Cargo.COAL] > 0)
        assertTrue(mill.broughtLastMonth + mill.broughtThisMonth > 0, "no coal reached the mill")
        assertTrue(mill.made[Cargo.STEEL] + mill.madeLastMonth[Cargo.STEEL] > 0, "no steel made")
    }

    @Test
    fun wagonsCarryWhatTheyreSetTo() {
        val w = chain()
        val gondola = w.roster.engines.first { it.key == "base:gondola" }
        w.apply(Command.BuyTrain(0, 0, 6, gondola.id, 3, Cargo.IRON_ORE))
        val t = w.trains[0]!!
        assertEquals(0, t.capacity(Cargo.STEEL))
        assertEquals(3 * gondola.capacity, t.capacity(Cargo.IRON_ORE))
        // Saved and loaded, it still carries the same.
        val c = Save.read(Save.write(w))
        assertEquals(3 * gondola.capacity, c.trains[0]!!.capacity(Cargo.IRON_ORE))
        assertEquals(2, c.industries.size)
    }

    @Test
    fun aBuyCommandReadsBackWithItsCargo() {
        val cmd = Command.BuyTrain(0, 1, 2, 3, 4, Cargo.GRAIN)
        assertEquals(cmd, Codec.decode(Codec.encode(cmd)))
    }

    @Test
    fun overYearsIndustriesOpenCloseAndChange() {
        val w = World(NewGame(width = 256, height = 256, seed = 8, startYear = 1900))
        val start = w.industries.size
        var opened = 0
        var closed = 0
        var changed = 0
        while (Calendar.year(w.startYear, w.day) < 1930) {
            w.step()
            for (n in w.news) if (n.tick == w.tick) when (n.kind) {
                News.INDUSTRY_OPENS -> opened++
                News.INDUSTRY_CLOSES -> closed++
                else -> changed++
            }
        }
        assertTrue(opened > 0, "none opened")
        assertTrue(closed > 0, "none closed")
        assertTrue(changed > 0, "none changed")
        assertEquals(start + opened, w.industries.size)
        // A closed one leaves nothing on the map.
        for (ind in w.industries.filter { it.closed }) assertTrue(w.industryOf.none { it.toInt() == ind.id + 1 })
        for (ind in w.industries) assertTrue(ind.level in MIN_LEVEL..MAX_LEVEL)
        // And it all carries on the same after saving.
        val c = Save.read(Save.write(w))
        repeat(Calendar.TICKS_PER_DAY * 100) { w.step(); c.step() }
        assertEquals(w.checksum(), c.checksum())
    }

    @Test
    fun aFullLoadOrderWaitsUntilTheTrainIsFull() {
        val w = chain()
        val hopper = w.roster.engines.first { it.key == "base:hopper" }
        w.apply(Command.BuyTrain(0, 0, 6, hopper.id, 3, Cargo.COAL))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0 or Order.FULL_LOAD, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        val t = w.trains[0]!!
        // Every time it leaves the mine, it leaves full.
        var departures = 0
        var wasAtMine = false
        repeat(Calendar.TICKS_PER_DAY * 300) {
            w.step()
            val atMine = t.waiting > 0 && t.order == 0
            if (wasAtMine && !atMine) {
                departures++
                assertEquals(t.capacity(Cargo.COAL), t.load(Cargo.COAL))
            }
            wasAtMine = atMine
        }
        assertTrue(departures > 0)
    }

    @Test
    fun ordersKeepWhatToDoAndHowLongToWait() {
        val o = Order.withWait(Order.toggle(5, Order.UNLOAD), 7)
        assertEquals(5, Order.station(o))
        assertTrue(Order.has(o, Order.UNLOAD))
        assertEquals(7, Order.waitDays(o))
        // Transfer and unload rule each other out.
        val t = Order.toggle(o, Order.TRANSFER)
        assertTrue(Order.has(t, Order.TRANSFER) && !Order.has(t, Order.UNLOAD))
    }

    @Test
    fun aTransferOrderLeavesCargoAndCreditsTheTrain() {
        val w = chain()
        val hopper = w.roster.engines.first { it.key == "base:hopper" }
        // A station halfway along, where the train sets its coal down to transfer.
        w.apply(Command.Station(0, 44, 10, 46, 10, Track.X))
        w.apply(Command.BuyTrain(0, 0, 6, hopper.id, 3, Cargo.COAL))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 2 or Order.TRANSFER)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        repeat(Calendar.TICKS_PER_DAY * 120) { w.step() }
        assertTrue(w.stations[2]!!.waiting(Cargo.COAL) > 0, "nothing set down")
        assertTrue(w.trains[0]!!.profitThisYear > -1_000_000)
    }

    @Test
    fun portsAndHarboursStandOnTheShoreFacingTheWater() {
        val w = World(NewGame(width = 256, height = 256, seed = 4, startYear = 1900, region = Region.MARITIMES))
        val coastal = w.industries.filter { w.roster.industries[it.kind].water }
        assertTrue(coastal.isNotEmpty(), "no ports or harbours")
        for (ind in coastal) {
            val kind = w.roster.industries[ind.kind]
            // Water just beyond the side its first row faces.
            val tiles = (ind.y until ind.y + kind.along(ind.turn)).flatMap { ty -> (ind.x until ind.x + kind.across(ind.turn)).map { tx -> tx to ty } }
            val wet = tiles.count { (tx, ty) -> listOf(tx - 1 to ty, tx + 1 to ty, tx to ty - 1, tx to ty + 1).any { (nx, ny) -> w.inside(nx, ny) && w.isWater(nx, ny) } }
            assertTrue(wet > 0, "${kind.key} at ${ind.x},${ind.y} isn't by the water")
        }
    }

    @Test
    fun farmsMakeMostAtHarvest() {
        val kind = Roster.BASE.industries.first { it.key == "base:farm" }
        assertTrue(kind.seasons[8] > kind.seasons[0] * 4)
        assertEquals(1200, kind.seasons.sum())
    }

    @Test
    fun aGoViaOrderRunsThroughWithoutStopping() {
        val w = chain()
        w.apply(Command.Station(0, 44, 10, 46, 10, Track.X))
        w.apply(Command.BuyTrain(0, 0, 6, 18, 2))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 2 or Order.VIA, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        val t = w.trains[0]!!
        var stoppedAtVia = false
        var reachedMill = false
        repeat(Calendar.TICKS_PER_DAY * 60) {
            w.step()
            val here = w.stationTiles[stepTile(t.steps.lastOrNull() ?: 0)]
            if (t.waiting > 0 && here == 2) stoppedAtVia = true
            if (t.waiting > 0 && here == 1) reachedMill = true
        }
        assertTrue(!stoppedAtVia, "stopped at the via station")
        assertTrue(reachedMill)
    }

    @Test
    fun sharedOrdersChangeTogether() {
        val w = chain()
        w.apply(Command.BuyTrain(0, 0, 6, 18, 2))
        w.apply(Command.BuyTrain(0, 0, 6, 18, 2))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 1, Command.TrainOrder.SHARE, intArrayOf(0)))
        assertEquals(listOf(0, 1), w.trains[1]!!.orders.toList())
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(1, 0 or Order.FULL_LOAD)))
        assertEquals(w.trains[0]!!.orders.toList(), w.trains[1]!!.orders.toList())
        w.apply(Command.TrainOrder(0, 1, Command.TrainOrder.UNSHARE))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0)))
        assertEquals(2, w.trains[1]!!.orders.size)
        assertEquals(-1, Save.read(Save.write(w)).trains[1]!!.shared)
    }

    @Test
    fun aStopWithNothingToDoIsSkipped() {
        val w = chain()
        // A station in the middle with nothing round it, set to be skipped when there's nothing there.
        w.apply(Command.Station(0, 44, 10, 46, 10, Track.X))
        w.apply(Command.BuyTrain(0, 0, 6, 18, 2))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 2 or Order.SKIP_IF_NOTHING, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        val t = w.trains[0]!!
        var called = false
        repeat(Calendar.TICKS_PER_DAY * 60) {
            w.step()
            if (t.waiting > 0 && w.stationTiles[stepTile(t.steps.lastOrNull() ?: 0)] == 2) called = true
        }
        assertTrue(!called, "called where there was nothing to do")
    }

    @Test
    fun fundingBuildsAMillAndProspectsForAMine() {
        val w = World(NewGame(width = 256, height = 256, seed = 3, startYear = 1900))
        w.companies[0].money = 10_000_000
        val mill = w.roster.industries.first { it.key == "base:sawmill" }
        val before = w.industries.size
        w.apply(Command.FundIndustry(0, mill.id))
        assertEquals(before + 1, w.industries.size)
        assertEquals(mill.id, w.industries.last().kind)
        // Prospecting: some found, some not, money spent each time.
        val mine = w.roster.industries.first { it.key == "base:coal_mine" }
        val money = w.companies[0].money
        repeat(9) { w.apply(Command.FundIndustry(0, mine.id)) }
        val found = w.industries.size - before - 1
        assertTrue(found in 3..9, "$found found")
        assertTrue(w.companies[0].money < money - 8 * w.price(fundCost(mine)))
        assertEquals(Command.FundIndustry(0, 3), Codec.decode(Codec.encode(Command.FundIndustry(0, 3))))
    }
}
