package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CampaignTest {
    private fun play(id: String) = assertNotNull(Campaign.play(id, "Title $id", "Intro $id"))

    /** The water bodies on the map, joined along edges, as lists of tiles, biggest first. */
    private fun waters(w: World): List<List<Int>> {
        val seen = BooleanArray(w.width * w.height)
        val out = ArrayList<List<Int>>()
        for (start in seen.indices) {
            if (seen[start] || !w.isWater(start % w.width, start / w.width)) continue
            val body = ArrayList<Int>()
            val queue = ArrayDeque<Int>()
            queue += start
            seen[start] = true
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                body += i
                val x = i % w.width
                val y = i / w.width
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nx = x + dx
                    val ny = y + dy
                    if (!w.inside(nx, ny)) continue
                    val n = w.index(nx, ny)
                    if (!seen[n] && w.isWater(nx, ny)) { seen[n] = true; queue += n }
                }
            }
            out += body
        }
        return out.sortedByDescending { it.size }
    }

    private fun nearest(w: World, body: List<Int>, x: Int, y: Int) = body.minOf { chebyshev(it % w.width, it / w.width, x, y) }

    @Test
    fun eachReadyChapterIsMadeTheSameEveryTime() {
        for (c in Campaign.CHAPTERS.filter { it.ready }) {
            val a = play(c.id)
            val b = play(c.id)
            assertEquals(a.checksum(), b.checksum(), c.id)
            assertEquals(emptyList(), partsDiffer(a, b), c.id)
            assertFalse(a.editor)
            assertEquals(c.id, a.scenario?.chapter)
            assertEquals("Title ${c.id}", a.scenario?.title)
            assertEquals(Scenario.ALL, a.scenario?.win)
            assertEquals(c.startYear, a.startYear)
        }
    }

    @Test
    fun thePortageHasTwoWatersAndALandingTownOnEach() {
        val w = play("portage")
        val big = waters(w).filter { it.size >= 200 }
        assertEquals(2, big.size, "two waters")
        assertEquals(2, w.towns.size)
        // Each landing is by a different water, and the rapids between them are dry.
        val (a, b) = w.towns
        val aNear = big.indexOfFirst { nearest(w, it, a.x, a.y) <= 12 }
        val bNear = big.indexOfFirst { nearest(w, it, b.x, b.y) <= 12 }
        assertTrue(aNear >= 0 && bNear >= 0 && aNear != bNear, "landings $aNear $bNear")
        for (x in PortageLine.RAPIDS_X0..PortageLine.RAPIDS_X1) assertFalse(w.isWater(x, PortageLine.RIVER_Y), "rapids at $x")
        assertTrue(w.industries.any { w.roster.industries[it.kind].key == "base:logging_camp" })
        assertTrue(w.industries.any { w.roster.industries[it.kind].key == "base:sawmill" })
    }

    @Test
    fun theTrunkLineHasFiveTownsAlongOneLakeShore() {
        val w = play("trunk")
        assertEquals(5, w.towns.size)
        val lake = waters(w).first()
        assertTrue(lake.size > w.width * w.height / 5, "a big lake")
        for (t in w.towns) assertTrue(nearest(w, lake, t.x, t.y) <= 16, "${t.name} by the lake")
        // Strung out along the shore, west to east.
        val xs = w.towns.map { it.x }
        assertEquals(xs.sorted(), xs)
        assertTrue(xs.last() - xs.first() > w.width * 3 / 4)
        assertEquals(1, w.rivals.size)
        assertEquals("Lakeshore Stage Company", w.companies[w.rivals[0].company].name)
        // The eastern towns are French.
        assertEquals(listOf(false, false, false, true, true), w.towns.map { it.french })
    }

    /** Whether land or water no higher than [top] joins ([x0], [y0]) to ([x1], [y1]), keeping off rows [off]. */
    private fun lowWay(w: World, x0: Int, y0: Int, x1: Int, y1: Int, top: Int, off: (Int) -> Boolean = { false }): Boolean {
        val seen = BooleanArray(w.width * w.height)
        val queue = ArrayDeque<Int>()
        queue += w.index(x0, y0)
        seen[w.index(x0, y0)] = true
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val x = i % w.width
            val y = i / w.width
            if (chebyshev(x, y, x1, y1) <= 2) return true
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = x + dx
                val ny = y + dy
                if (!w.inside(nx, ny) || off(ny)) continue
                val n = w.index(nx, ny)
                if (!seen[n] && w.tileHeight(nx, ny) <= top) { seen[n] = true; queue += n }
            }
        }
        return false
    }

    @Test
    fun theLastSpikeCrossesTheMountainsByItsPasses() {
        val w = play("last_spike")
        val west = w.towns.first { it.name == "Port Haldane" }
        val east = w.towns.first { it.name == "Fort Calder" }
        assertTrue(west.x < LastSpike.COAST_X + 30 && east.x > LastSpike.PLAIN_X + 50)
        // High mountains between, crossed below the tree line only by the passes.
        assertTrue((0 until w.height).any { y -> (30..LastSpike.PLAIN_X).any { x -> w.tileHeight(x, y) >= 18 } }, "high mountains")
        assertTrue(lowWay(w, west.x, west.y, east.x, east.y, 12), "a way through the passes")
        assertFalse(lowWay(w, west.x, west.y, east.x, east.y, 12) { y -> LastSpike.PASS_Y.any { kotlin.math.abs(it - y) <= 12 } }, "a way round the passes")
        // Flat prairie to the east.
        var flat = 0
        var all = 0
        for (y in 0 until w.height) for (x in LastSpike.PLAIN_X + 4 until w.width) if (!w.isWater(x, y)) { all++; if (w.isFlat(x, y)) flat++ }
        assertTrue(flat * 10 > all * 8, "prairie $flat of $all flat")
        assertTrue(w.settings.weather)
        assertEquals("Western Slope Railway", w.companies[w.rivals[0].company].name)
    }

    @Test
    fun theSeawayCanalAndLocksGoRoundTheRapids() {
        val w = play("seaway")
        w.companies[0].money = 100_000_000
        val y = Seaway.RIVER_Y
        // The lake and upper river apart from the lower river and the sea.
        val big = waters(w).filter { it.size >= 400 }
        assertEquals(2, big.size, "two waters")
        val upper = big.indexOfFirst { w.index(Seaway.LEDGE_X0 - 2, y) in it }
        val lower = big.indexOfFirst { w.index(Seaway.LEDGE_X1 + 2, y) in it }
        assertTrue(upper >= 0 && lower >= 0 && upper != lower, "the ledge between $upper and $lower")
        // Two locks up, a canal across the ledge, two locks down.
        for (x in intArrayOf(Seaway.LEDGE_X0, Seaway.LEDGE_X0 + 1, Seaway.LEDGE_X1 - 1, Seaway.LEDGE_X1)) {
            w.apply(Command.Lock(0, x, y))
            assertTrue(w.locks.containsKey(w.index(x, y)), "lock at $x")
        }
        w.apply(Command.Canal(0, Seaway.LEDGE_X0 + 2, y, Seaway.LEDGE_X1 - 2, y, remove = false))
        assertTrue((Seaway.LEDGE_X0 + 2..Seaway.LEDGE_X1 - 2).all { w.canals.contains(w.index(it, y)) }, "canal")
        // A dock on each water and a shipyard above the ledge.
        fun onShore(xs: IntProgression, build: (Int, Int) -> Command): Boolean {
            for (x in xs) for (dy in 2..8) for (sy in intArrayOf(y - dy, y + dy)) {
                val before = w.stations.size + w.depots.size
                w.apply(build(x, sy))
                if (w.stations.size + w.depots.size > before) return true
            }
            return false
        }
        assertTrue(onShore(Seaway.LEDGE_X0 - 4 downTo Seaway.LEDGE_X0 - 12) { x, sy -> Command.Shipyard(0, x, sy) }, "shipyard")
        assertTrue(onShore(Seaway.LEDGE_X0 - 6 downTo Seaway.LEDGE_X0 - 12) { x, sy -> Command.Dock(0, x, sy) }, "upper dock")
        assertTrue(onShore(Seaway.LEDGE_X1 + 4..Seaway.LEDGE_X1 + 12) { x, sy -> Command.Dock(0, x, sy) }, "lower dock")
        val yard = w.depots.indexOfLast { it?.kind == Depots.SHIPYARD }
        w.apply(Command.BuyCraft(0, yard, w.roster.engines.first { it.key == "base:steam_laker" }.id))
        val ship = w.ships.size - 1
        val docks = w.stations.indices.toList().takeLast(2)
        w.apply(Command.CraftOrder(0, false, ship, Command.TrainOrder.ORDERS, intArrayOf(docks[1], docks[0])))
        w.apply(Command.CraftOrder(0, false, ship, Command.TrainOrder.START_STOP))
        // Kept clear of ice, it sails through the locks to the lower dock.
        val v = w.ships[ship]!!
        val far = w.berths(docks[1])
        var through = false
        for (t in 0 until 40_000) {
            if (t % 50 == 0) w.weather.ice.fill(0)
            w.step()
            if (w.shipTile(v) in far) { through = true; break }
        }
        assertTrue(through, "a way through the locks")
    }

    @Test
    fun theIronOreLineRunsFarOverRockAndLakes() {
        val w = play("iron_ore")
        val mine = w.industries.first { w.roster.industries[it.kind].key == "base:iron_mine" }
        val mill = w.industries.first { w.roster.industries[it.kind].key == "base:steel_mill" }
        assertTrue(chebyshev(mine.x, mine.y, mill.x, mill.y) >= 100, "far apart")
        assertEquals(MAX_LEVEL, mine.level)
        assertTrue(waters(w).count { it.size in 20..5_000 } >= 8, "lakes")
        assertTrue(w.ground.count { it.toInt() == Ground.ROCK } > w.width * w.height / 10, "rock")
        assertTrue(w.towns.single { it.name == "Baie-Clairval" }.french)
    }

    @Test
    fun theHighSpeedCorridorJoinsTwoCitiesAlongARivalsHighway() {
        val w = play("high_speed")
        val (a, b) = w.goals.first { it.kind == Goal.CONNECT }.let { w.towns[it.town] to w.towns[it.town2] }
        assertTrue(chebyshev(a.x, a.y, b.x, b.y) >= 150, "far apart")
        assertTrue(a.population >= 1_000 && b.population >= 1_000, "cities")
        // The highway between them is the rival's.
        val rival = w.rivals[0].company
        assertEquals("Corridor Coachways", w.companies[rival].name)
        val y = HighSpeed.HIGHWAY_Y
        val owned = (a.x + 20 until b.x - 20).count { x -> w.road[w.index(x, y)].toInt() != 0 && w.roadOwner[w.index(x, y)].toInt() == rival }
        assertTrue(owned > (b.x - a.x) / 2, "rival's highway $owned")
        // Something fast to run on it.
        assertTrue(w.roster.engines.any { it.kind == VehicleKind.ENGINE && 2032 >= it.from && 2032 < it.until && it.speed >= 200 })
    }

    @Test
    fun theWarmingNorthRunsPastTheOldLastYear() {
        assertTrue(NewGame.LAST_YEAR >= 2100)
        val w = play("warming_north")
        assertEquals(TownGrowth.SUPPLIED, w.settings.townGrowth)
        val year = w.startYear
        for (kind in listOf(VehicleKind.ENGINE, VehicleKind.WAGON, VehicleKind.ROAD)) assertTrue(w.roster.engines.any { it.kind == kind && year >= it.from && year < it.until }, "kind $kind")
        assertTrue(waters(w).count { it.size >= 50 } >= 3, "lakes")
    }

    @Test
    fun everyGoalIsAboutSomethingOnTheMap() {
        for (c in Campaign.CHAPTERS.filter { it.ready }) {
            val w = play(c.id)
            assertTrue(w.goals.isNotEmpty(), c.id)
            for (g in w.goals) {
                assertEquals(c.endYear, g.deadline, c.id)
                when (g.kind) {
                    Goal.CONNECT -> assertTrue(g.town in w.towns.indices && g.town2 in w.towns.indices && g.town != g.town2, c.id)
                    Goal.TOWN_POP -> assertTrue(g.town in w.towns.indices, c.id)
                    Goal.DELIVER -> {
                        assertTrue(g.cargo in 0 until Cargo.COUNT, c.id)
                        if (g.industry >= 0) {
                            val ind = w.industries[g.industry]
                            assertFalse(ind.closed)
                            assertTrue(w.roster.industries[ind.kind].takes(g.cargo), "${c.id} industry takes it")
                        } else assertTrue(g.town in w.towns.indices, c.id)
                    }
                }
            }
        }
    }

    @Test
    fun thePlayerCanAffordAFirstLine() {
        for (c in Campaign.CHAPTERS.filter { it.ready }) {
            val w = play(c.id)
            val year = c.startYear
            val engines = w.roster.engines.filter { year >= it.from && year < it.until }
            val engine = engines.filter { it.kind == VehicleKind.ENGINE }.minOf { it.cost }
            val tender = engines.filter { it.kind == VehicleKind.TENDER }.minOfOrNull { it.cost } ?: 0
            val coach = engines.filter { it.kind == VehicleKind.WAGON }.minOf { it.cost }
            // Forty tiles of track through trees, a few on foundations, two stations of four platforms, a depot and a train.
            val line = 40 * (w.price(Prices.TRACK) + 3 * w.price(Prices.TREE)) + 10 * w.price(Prices.FOUNDATION) +
                8 * w.price(Prices.PLATFORM) + w.price(Prices.DEPOT) + engine + tender + 3 * coach
            assertTrue(w.companies[0].money >= line, "${c.id}: ${w.companies[0].money} against $line")
            assertEquals(Campaign.chapter(c.id)!!.recipe!!.loan, w.companies[0].loan)
        }
    }

    /**
     * A straight line along the first row in [rows] that's level, dry and clear from [x0] to [x1]: a depot at its west
     * end, stations of four at each end, and a train of [wagons] [wagon]s carrying [cargo] behind [engine], started.
     * Returns the row, or null if no row will do.
     */
    private fun line(w: World, rows: IntRange, x0: Int, x1: Int, engine: String, wagon: String, wagons: Int, cargo: Int = -1): Int? {
        val y = rows.firstOrNull { y ->
            (x0..x1).all { x ->
                val i = w.index(x, y)
                w.isFlat(x, y) && !w.isWater(x, y) && w.townOf[i].toInt() < 0 && w.industryOf[i].toInt() == 0 && w.road[i].toInt() == 0
            }
        } ?: return null
        val trains = w.trains.count { it != null }
        w.apply(Command.Depot(0, x0, y, Track.XP))
        w.apply(Command.Rail(0, x0 + 1, y, x1, y, Track.X, remove = false))
        w.apply(Command.Station(0, x0 + 2, y, x0 + 5, y, Track.X))
        w.apply(Command.Station(0, x1 - 4, y, x1 - 1, y, Track.X))
        val e = w.roster.engines.first { it.key == engine }
        val c = w.roster.engines.first { it.key == wagon }
        w.apply(Command.BuyTrain(0, w.depots.size - 1, e.id, c.id, wagons, cargo))
        assertEquals(trains + 1, w.trains.count { it != null }, "train bought")
        val t = w.trains.size - 1
        val st = w.stations.size
        w.apply(Command.TrainOrder(0, t, Command.TrainOrder.ORDERS, intArrayOf(st - 2, st - 1)))
        w.apply(Command.TrainOrder(0, t, Command.TrainOrder.START_STOP))
        return y
    }

    /** Runs [w] on to the start of December, before the year's counts start again. */
    private fun toDecember(w: World) {
        while (Calendar.month(w.startYear, w.day) < 11) w.step()
    }

    /**
     * Chapter [id] with a passenger line between towns [a] and [b], west and east on about one row, as a first line
     * would be: on a row through both, from the last building of one to the first of the other, a depot and a
     * station of four at each end, and a train of [wagons] [wagon]s behind [engine]. Null if no row will do.
     */
    private fun townLine(id: String, a: Int, b: Int, engine: String, wagon: String, wagons: Int): World? {
        val w = play(id)
        val ta = w.towns[a]
        val tb = w.towns[b]
        val mid = (ta.x + tb.x) / 2
        fun built(x: Int, y: Int) = w.index(x, y).let { w.house[it].toInt() != 0 || w.road[it].toInt() != 0 }
        for (dy in intArrayOf(1, -1, 2, -2, 0, 3, -3, 4, -4)) {
            val y = (ta.y + tb.y) / 2 + dy
            val x0 = (ta.x until mid).lastOrNull { built(it, y) }?.plus(1) ?: continue
            val x1 = (mid..tb.x).firstOrNull { built(it, y) }?.minus(1) ?: continue
            if (x1 - x0 < 12 || (x0..x1).any { x -> !w.isFlat(x, y) || w.isWater(x, y) || w.industryOf[w.index(x, y)].toInt() != 0 }) continue
            w.apply(Command.Depot(0, x0, y, Track.XP))
            w.apply(Command.Rail(0, x0 + 1, y, x1, y, Track.X, remove = false))
            w.apply(Command.Station(0, x0 + 1, y, x0 + 4, y, Track.X))
            w.apply(Command.Station(0, x1 - 3, y, x1, y, Track.X))
            val e = w.roster.engines.first { it.key == engine }
            val c = w.roster.engines.first { it.key == wagon }
            w.apply(Command.BuyTrain(0, 0, e.id, c.id, wagons))
            assertEquals(2, w.stations.size)
            assertEquals(1, w.trains.size)
            w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
            w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
            println("$id line on row $y from $x0 to $x1")
            return w
        }
        return null
    }

    /** Income less running costs, upkeep and interest so far this year. */
    private fun profit(w: World) = w.companies[0].thisYear.let { it[Books.INCOME] + it[Books.RUNNING] + it[Books.UPKEEP] + it[Books.INTEREST] }

    @Test
    fun aPortageTrainCarriesPassengers() {
        val w = assertNotNull(townLine("portage", 0, 1, "base:dorchester", "base:coach_early", 2))
        val connect = w.goals.first { it.kind == Goal.CONNECT }
        toDecember(w)
        println("portage passengers: towns ${w.towns.map { it.population }} carried ${connect.count[0]} of ${connect.amount}, profit ${profit(w)}, money ${w.companies[0].money}, books ${w.companies[0].thisYear.toList()}")
        assertTrue(connect.count[0] > 0, "passengers carried")
    }

    @Test
    fun aPortageTimberTrainDelivers() {
        val w = play("portage")
        val camp = w.industries.first { w.roster.industries[it.kind].key == "base:logging_camp" }
        val mill = w.industries.first { w.roster.industries[it.kind].key == "base:sawmill" }
        // The mill at the west end, the camp at the east: the station at each end within reach of each.
        assertNotNull(line(w, 60..PortageLine.RIVER_Y - 3, mill.x + w.roster.industries[mill.kind].across(mill.turn), camp.x - 1, "base:dorchester", "base:flatcar", 3, Cargo.TIMBER), "a row for the timber line")
        val deliver = w.goals.first { it.kind == Goal.DELIVER }
        toDecember(w)
        println("portage timber: delivered ${deliver.count[0]} of ${deliver.amount}, profit ${profit(w)}, books ${w.companies[0].thisYear.toList()}")
        assertTrue(deliver.count[0] > 0, "timber delivered")
    }

    /**
     * Each chapter run on [YEARS] years with nobody playing: nothing breaks, the player's company is still there, no
     * goal has failed, and the weather the chapter is about turns up. The deepest snow on steep high ground, the
     * thickest ice on the water, the most ice on a lake: each looked at on the first of every month.
     */
    @Test
    fun eachChapterRunsOnWithNobodyPlaying() {
        for (c in Campaign.CHAPTERS.filter { it.ready }) {
            val w = play(c.id)
            var snow = 0
            var ice = 0
            var month = -1
            while (Calendar.year(w.startYear, w.day) < w.startYear + YEARS) {
                w.step()
                val m = Calendar.month(w.startYear, w.day)
                if (m == month) continue
                month = m
                for (y in 0 until w.height) for (x in 0 until w.width) {
                    val i = w.index(x, y)
                    if (w.isWater(x, y)) ice = maxOf(ice, w.weather.ice[i].toInt() and 255)
                    else if (w.tileHeight(x, y) >= AVALANCHE_HEIGHT && !w.isFlat(x, y)) snow = maxOf(snow, w.weather.snow[i].toInt() and 255)
                }
            }
            println("${c.id} after $YEARS years: towns ${w.towns.map { "${it.name} ${it.population}" }}, deepest snow up high $snow, thickest ice $ice, money ${w.companies[0].money}")
            assertFalse(w.companies[0].gone, c.id)
            assertTrue(w.goals.none { it.status == Goal.FAILED }, c.id)
            when (c.id) {
                "last_spike" -> assertTrue(snow >= AVALANCHE_SNOW, "deep enough snow for avalanches: $snow")
                "seaway" -> assertTrue(ice >= SHIP_ICE, "ice to close the river: $ice")
                "warming_north" -> assertTrue(ice >= ICE_ROAD_CM, "ice for ice roads: $ice")
            }
        }
    }

    private companion object {
        const val YEARS = 3
    }
}
