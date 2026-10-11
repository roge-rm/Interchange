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
    private fun chain(): World {
        val w = World(96, 64, 1, startYear = 1900)
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

    @Test
    fun coalCarriedToTheMillIsMadeIntoSteel() {
        val w = chain()
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
        w.apply(Command.BuyTrain(0, 0, 4, gondola.id, 3, Cargo.IRON_ORE))
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
}
