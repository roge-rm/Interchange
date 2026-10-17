package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SaveTest {
    /** A line with two stations, a depot and a running train, from the train tests. */
    private fun busy(dayLength: Int = 1): World {
        val w = World(NewGame(64, 64, 1, 1850, towns = 0, industries = 0, weather = false, dayLength = dayLength))
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 10_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 39, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 10, 10, 13, 10, Track.X))
        w.apply(Command.Station(0, 36, 10, 39, 10, Track.X))
        w.apply(Command.Signal(0, 20, 10, Track.X, Track.XM))
        w.apply(Command.BuyTrain(0, 0, 0, 18, 3))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        repeat(700) { w.step() }
        return w
    }

    @Test
    fun aLoadedWorldCarriesOnExactlyAsTheSavedOne() {
        val a = busy()
        val b = Save.read(Save.write(a))
        assertEquals(a.checksum(), b.checksum())
        // Both run on, a command mid-way, and stay the same.
        repeat(3_000) { i ->
            if (i == 1_000) for (w in listOf(a, b)) w.apply(Command.Rail(0, 20, 11, 30, 11, Track.X, remove = false))
            a.step()
            b.step()
        }
        assertEquals(a.checksum(), b.checksum())
    }

    @Test
    fun aVersion27SaveStillLoadsAndRuns() {
        val a = Save.read(Save.write(busy(), version = 27))
        val b = Save.read(Save.write(busy(), version = 27))
        repeat(1_000) { a.step(); b.step() }
        assertEquals(a.checksum(), b.checksum())
        assertEquals(a.trains.size, 1)
    }

    @Test
    fun aVehiclesAgeFromAVersion35SaveIsInWorkDays() {
        val w = busy(dayLength = 10)
        // Built 40 calendar days back, 400 work days.
        w.trains[0]!!.built = w.workDay - 400
        val back = Save.read(Save.write(w, version = 35))
        assertEquals(w.trains[0]!!.built / 10 * 10, back.trains[0]!!.built)
        assertEquals(w.trains[0]!!.built, Save.read(Save.write(w)).trains[0]!!.built)
    }

    /** A bus in its garage, a ship at its shipyard and an aircraft in its hangar, at day length [dayLength]. */
    private fun fleet(dayLength: Int): World {
        val w = World(NewGame(64, 64, 1, 1965, towns = 0, industries = 0, weather = false, dayLength = dayLength))
        for (y in 0..64) for (x in 0..64) w.corners[w.corner(x, y)] = if (x in 10..50 && y in 10..30) 0 else 1
        w.shoreChanged()
        w.companies[0].money = 100_000_000
        w.apply(Command.Road(0, 5, 40, 30, 40, Surface.PAVED, remove = false))
        w.apply(Command.Garage(0, 5, 41, Track.YM))
        w.apply(Command.BuyRoadVehicle(0, w.depots.size - 1, w.roster.engines.first { it.key == "base:new_look" }.id))
        w.apply(Command.Shipyard(0, 9, 15))
        w.apply(Command.BuyCraft(0, w.depots.size - 1, w.roster.engines.first { it.key == "base:steam_laker" }.id))
        w.apply(Command.Airport(0, 40, 45, Airports.REGIONAL, Track.X))
        w.apply(Command.BuyCraft(0, w.airports[0]!!.depot, w.roster.engines.first { it.key == "base:dc3" }.id))
        assertEquals(1, w.roadVehicles.size)
        assertEquals(1, w.ships.size)
        assertEquals(1, w.aircraft.size)
        return w
    }

    @Test
    fun otherVehiclesAgesFromAVersion35SaveAreInWorkDays() {
        val w = fleet(dayLength = 10)
        repeat(Calendar.TICKS_PER_DAY * 500) { w.step() }
        // Built 40 calendar days back, 400 work days.
        w.roadVehicles[0]!!.built = w.workDay - 400
        w.ships[0]!!.built = w.workDay - 401
        w.aircraft[0]!!.built = w.workDay - 402
        val back = Save.read(Save.write(w, version = 35))
        assertEquals(w.roadVehicles[0]!!.built / 10 * 10, back.roadVehicles[0]!!.built)
        assertEquals(w.ships[0]!!.built / 10 * 10, back.ships[0]!!.built)
        assertEquals(w.aircraft[0]!!.built / 10 * 10, back.aircraft[0]!!.built)
        val now = Save.read(Save.write(w))
        assertEquals(w.roadVehicles[0]!!.built, now.roadVehicles[0]!!.built)
        assertEquals(w.ships[0]!!.built, now.ships[0]!!.built)
        assertEquals(w.aircraft[0]!!.built, now.aircraft[0]!!.built)
    }

    @Test
    fun aVersion37SaveHasNoCollisions() {
        val w = busy()
        assertTrue(w.settings.collisions)
        assertTrue(!Save.read(Save.write(w, version = 37)).settings.collisions)
        assertTrue(Save.read(Save.write(w)).settings.collisions)
    }

    @Test
    fun aGeneratedMapSurvivesSavingAndLoading() {
        val a = World(NewGame(width = 128, height = 64, seed = 7, region = Region.SHIELD, hills = 2))
        val b = Save.read(Save.write(a))
        assertContentEquals(a.corners, b.corners)
        assertContentEquals(a.trees, b.trees)
        assertEquals(a.settings, b.settings)
    }

    @Test
    fun commandsReadBackAsWritten() {
        val commands = listOf(
            Command.Terraform(0, 3, -4, true),
            Command.Rail(0, 1, 2, 30, 2, Track.X, remove = true),
            Command.Station(0, 5, 5, 8, 6, Track.Y),
            Command.Depot(0, 9, 9, Track.YP),
            Command.Signal(0, 4, 4, Track.N, Track.YM),
            Command.Clear(0, 1, 1),
            Command.BuyTrain(0, 2, 4, 22, 9),
        )
        for (c in commands) assertEquals(c, Codec.decode(Codec.encode(c)))
        val orders = Codec.decode(Codec.encode(Command.TrainOrder(0, 3, Command.TrainOrder.ORDERS, intArrayOf(2, 0, 5)))) as Command.TrainOrder
        assertContentEquals(intArrayOf(2, 0, 5), orders.stations)
    }

    @Test
    fun aRecordedGamePlaysBackTheSame() {
        // The commands of a game, each with the tick it ran on, recorded as bytes and played into a fresh world.
        val log = ByteWriter()
        val a = World(64, 64, 3)
        val script = mapOf(5L to Command.Terraform(0, 10, 10, true), 40L to Command.Rail(0, 4, 4, 20, 4, Track.X, remove = false), 90L to Command.Terraform(0, 30, 30, false))
        repeat(200) {
            script[a.tick]?.let { c -> log.long(a.tick); Codec.write(log, c); a.apply(c) }
            a.step()
        }
        val b = World(64, 64, 3)
        val r = ByteReader(log.toByteArray())
        var next = if (r.done) Long.MAX_VALUE else r.long()
        repeat(200) {
            while (next == b.tick) { b.apply(Codec.read(r)); next = if (r.done) Long.MAX_VALUE else r.long() }
            b.step()
        }
        assertEquals(a.checksum(), b.checksum())
    }

    @Test
    fun damagedSavesAreTurnedAwayPlainly() {
        assertFailsWith<BadData> { Save.read("not a save".encodeToByteArray()) }
        val good = Save.write(World(64, 64, 1))
        assertFailsWith<BadData> { Save.read(good.copyOf(good.size / 2)) }
    }

    /**
     * Towns growing round a company's stops, as a game a player joins late: each day the world is copied through a
     * save, and the copy must carry on the same as the world it came from. Buildings put up again in reach of a stop
     * change the people round it, which share out where passengers go.
     */
    @Test
    fun aCopyTakenAnyDayCarriesOnTheSameWhileTownsGrow() {
        val a = World(NewGame(128, 128, 4242, 1930, Region.MIXED, towns = 6, industries = 0))
        a.companies[0].money = 100_000_000
        val stops = ArrayList<Int>()
        for (t in a.towns) {
            val i = (-3..3).flatMap { dy -> (-3..3).map { dx -> t.x + dx to t.y + dy } }.filter { (x, y) -> a.inside(x, y) }
                .map { (x, y) -> a.index(x, y) }
                .firstOrNull { a.straightRoad(it) != 0 && a.isFlat(it % a.width, it / a.width) && a.track[it].toInt() == 0 } ?: continue
            a.apply(Command.RoadStop(0, i % a.width, i / a.width))
            if (a.roadStops.containsKey(i)) stops += i
        }
        val stations = stops.mapNotNull { a.roadStops[it] }.distinct()
        assertTrue(stations.size >= 3, "stops in ${stations.size} towns")
        // A garage by a stop, and a bus there with orders calling at them all, so they're all linked.
        val sites = stops.flatMap { s -> (-5..5).flatMap { dy -> (-5..5).map { dx -> s % a.width + dx to s / a.width + dy } } }
        for ((x, y) in sites) for (e in 0 until 4) if (a.depots.isEmpty()) a.apply(Command.Garage(0, x, y, e))
        assertTrue(a.depots.isNotEmpty(), "a garage")
        val bus = a.roster.availableRoad(1930).first { !it.tram && Cargo.PASSENGERS in it.cargoes && it.capacity > 0 }
        a.apply(Command.BuyRoadVehicle(0, 0, bus.id, Cargo.PASSENGERS))
        a.apply(Command.RoadOrder(0, 0, Command.TrainOrder.ORDERS, stations.toIntArray()))
        repeat(300) { day ->
            val b = Save.read(Save.write(a))
            repeat(a.dayTicks * 2) {
                a.step()
                b.step()
            }
            assertEquals(a.checksum(), b.checksum(), "a copy taken on day ${a.day - 2} differs two days on")
        }
    }

    /**
     * Buses across a frozen lake on an ice road, copied through a save each day while it's open: the copy must keep
     * the buses' routes, as a player joining in winter would.
     */
    @Test
    fun aCopyTakenInWinterKeepsTheIceRoad() {
        val a = World(64, 64, 1, startYear = 1965)
        for (y in 0..64) for (x in 0..64) a.corners[a.corner(x, y)] = if (x in 21..29) 0 else 3
        for (y in 0 until 64) for (x in 20..30) a.weather.ice[a.index(x, y)] = 60
        a.companies[0].money = 10_000_000
        a.apply(Command.Road(0, 5, 20, 45, 20, Surface.GRAVEL, remove = false))
        a.apply(Command.Road(0, 5, 20, 5, 22, Surface.GRAVEL, remove = false))
        a.apply(Command.Garage(0, 5, 23, Track.YM))
        a.apply(Command.RoadStop(0, 8, 20))
        a.apply(Command.RoadStop(0, 42, 20))
        assertTrue(a.roadEdges(a.index(25, 20)) != 0, "an ice road")
        val bus = a.roster.availableRoad(1965).first { !it.tram && Cargo.PASSENGERS in it.cargoes && it.capacity > 0 }
        repeat(3) { id ->
            a.apply(Command.BuyRoadVehicle(0, 0, bus.id, Cargo.PASSENGERS))
            a.apply(Command.RoadOrder(0, id, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
            a.apply(Command.RoadOrder(0, id, Command.TrainOrder.START_STOP))
            repeat(a.dayTicks / 3) { a.step() }
        }
        repeat(20) {
            val b = Save.read(Save.write(a))
            sameFor(a, b, 3)
        }
    }

    /**
     * Coal carried to a steel mill, the world copied through a save at all times of day: a copy taken partway
     * through a day must hand the coal to the mill the same.
     */
    @Test
    fun aCopyTakenMidDayDeliversToTheSameIndustry() {
        val a = World(96, 64, 1, startYear = 1900)
        for (i in a.corners.indices) a.corners[i] = 3
        a.companies[0].money = 10_000_000
        for ((key, x) in listOf("base:coal_mine" to 10, "base:steel_mill" to 80)) {
            val kind = a.roster.industries.first { it.key == key }
            val ind = Industry(a.industries.size, kind.id, x, 12, 0, -1)
            a.industries += ind
            for (ty in 12 until 12 + kind.along(0)) for (tx in x until x + kind.across(0)) a.industryOf[a.index(tx, ty)] = (ind.id + 1).toShort()
        }
        a.apply(Command.Depot(0, 2, 10, Track.XP))
        a.apply(Command.Rail(0, 3, 10, 90, 10, Track.X, remove = false))
        a.apply(Command.Station(0, 9, 10, 12, 10, Track.X))
        a.apply(Command.Station(0, 79, 10, 82, 10, Track.X))
        val hopper = a.roster.engines.first { it.key == "base:hopper" }
        a.apply(Command.BuyTrain(0, 0, 4, hopper.id, 5, Cargo.COAL))
        a.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0, 1)))
        a.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        while (a.day < 150) {
            repeat(23) { a.step() }
            sameFor(a, Save.read(Save.write(a)), 1)
        }
        assertTrue(a.industries[1].broughtLastMonth > 0, "no coal reached the mill")
    }

    /** A command straight after loading, before the copy has moved: it must see the train on the track the same. */
    @Test
    fun aCopyJustLoadedKnowsWhereTheTrainsAre() {
        val a = busy()
        val b = Save.read(Save.write(a))
        val t = a.trains[0]!!
        val tile = stepTile(t.steps[t.steps.size / 2])
        for (w in listOf(a, b)) w.apply(Command.Clear(0, tile % w.width, tile / w.width))
        assertTrue(a.track[tile].toInt() != 0, "track cleared from under a train")
        sameFor(a, b, 1)
    }

    /** A train stuck in a drift, copied: the copy mustn't tell of it again. */
    @Test
    fun aCopyTakenWithATrainInADriftTellsOfItOnce() {
        val a = busy()
        a.weather.snow[a.index(25, 10)] = 120.toByte()
        val t = a.trains[0]!!
        var ticks = 0
        while (!t.snowbound && ticks++ < 20_000) a.step()
        assertTrue(t.snowbound, "never stuck")
        sameFor(a, Save.read(Save.write(a)), 1)
    }

    /** Runs [a] and its copy [b] on [days] days and checks they're still the same, by checksum and by save. */
    private fun sameFor(a: World, b: World, days: Int) {
        val from = a.day
        repeat(a.dayTicks * days) {
            a.step()
            b.step()
        }
        assertEquals(a.checksum(), b.checksum(), "a copy taken on day $from differs $days days on in ${partsDiffer(a, b)}")
        assertContentEquals(Save.write(a), Save.write(b), "a copy taken on day $from saves differently $days days on, in ${partsDiffer(a, b)}")
    }

    /**
     * A busy game over a few years, as players joining at any time would get it: the player's buses, train and ship,
     * rivals building routes, all four seasons' weather, subsidies, town actions and a company going under. Every day
     * or so, at a different time of day each time, the world is copied through a save and the copy run on beside it.
     */
    @Test
    fun aCopyTakenAnyTimeInAFullGameCarriesOnTheSame() {
        val a = World(NewGame(160, 160, 12, 1930, Region.MIXED, towns = 10, industries = 6, rivals = 2, rivalLevel = RivalLevel.HARD, goals = GoalSet.MIXED))
        a.companies[0].money = 100_000_000
        busesRoundTowns(a)
        trainAndShip(a)
        var copies = 0
        var action = 0
        var snow = false
        var ice = false
        var subsidies = false
        var troubled = 0
        while (a.day < 365 * 5) {
            // Partway into the second year one rival runs out of money, to go into trouble and under.
            a.companies[1].let { c -> if (a.day >= 400 && !c.gone) c.money = minOf(c.money, c.loan - a.maxLoan() - 1_000) }
            val b = Save.read(Save.write(a))
            // Now and then the player takes a town action, straight after the copy's made.
            if (copies++ % 4 == 0) for (w in listOf(a, b)) w.apply(Command.TownAction(0, action % w.towns.size, action % TownActions.COUNT))
            action++
            sameFor(a, b, 3)
            // On a day and some ticks, so copies are taken at all times of day.
            repeat(a.dayTicks + 37) { a.step() }
            snow = snow || a.weather.snow.any { it.toInt() != 0 }
            ice = ice || a.weather.ice.any { it.toInt() != 0 }
            subsidies = subsidies || a.subsidies.isNotEmpty()
            troubled = maxOf(troubled, a.companies.maxOf { it.trouble })
        }
        assertTrue(snow && ice && subsidies, "snow $snow, ice $ice, subsidies $subsidies")
        assertTrue(troubled >= TROUBLE_FOR_SALE && a.companies[1].gone, "a company in trouble and gone")
        assertTrue(a.rivals.sumOf { it.routes.size } >= 3, "rivals' routes growing")
        assertTrue(a.trains.any { it != null } && a.ships.any { it != null }, "a train and a ship")
    }

    /** A road stop in each town [a] can, a garage by one and a bus calling at them all. */
    private fun busesRoundTowns(a: World) {
        val stops = ArrayList<Int>()
        for (t in a.towns) {
            val i = (-3..3).flatMap { dy -> (-3..3).map { dx -> t.x + dx to t.y + dy } }.filter { (x, y) -> a.inside(x, y) }
                .map { (x, y) -> a.index(x, y) }
                .firstOrNull { a.straightRoad(it) != 0 && a.isFlat(it % a.width, it / a.width) && a.track[it].toInt() == 0 } ?: continue
            a.apply(Command.RoadStop(0, i % a.width, i / a.width))
            if (a.roadStops.containsKey(i)) stops += i
        }
        val stations = stops.mapNotNull { a.roadStops[it] }.distinct()
        val sites = stops.flatMap { s -> (-5..5).flatMap { dy -> (-5..5).map { dx -> s % a.width + dx to s / a.width + dy } } }
        for ((x, y) in sites) for (e in 0 until 4) if (a.depots.isEmpty()) a.apply(Command.Garage(0, x, y, e))
        val bus = a.roster.availableRoad(a.startYear).first { !it.tram && Cargo.PASSENGERS in it.cargoes && it.capacity > 0 }
        repeat(2) {
            a.apply(Command.BuyRoadVehicle(0, 0, bus.id, Cargo.PASSENGERS))
            a.apply(Command.RoadOrder(0, a.roadVehicles.size - 1, Command.TrainOrder.ORDERS, stations.toIntArray()))
            a.apply(Command.RoadOrder(0, a.roadVehicles.size - 1, Command.TrainOrder.START_STOP))
        }
    }

    /**
     * Clears a patch of open country in [a], 40 by 26, a step up, with a lake in it, and puts the player's train on a
     * line north of the lake and a ship across it.
     */
    private fun trainAndShip(a: World) {
        fun open(x0: Int, y0: Int) = (y0 - 1..y0 + 26).all { y -> (x0 - 1..x0 + 40).all { x ->
            a.inside(x, y) && a.index(x, y).let { i -> !a.isWater(x, y) && a.track[i].toInt() == 0 && !a.townBuilt(i) && a.owner[i].toInt() == -1 }
        } }
        val (sx, sy) = (2 until a.height - 28 step 2).flatMap { y -> (2 until a.width - 42 step 2).map { x -> x to y } }.firstOrNull { (x, y) -> open(x, y) } ?: error("no open country")
        for (y in sy..sy + 26) for (x in sx..sx + 40) a.corners[a.corner(x, y)] = if (x in sx + 8..sx + 32 && y in sy + 8..sy + 22) 0 else 1
        for (y in sy until sy + 26) for (x in sx until sx + 40) a.trees[a.index(x, y)] = 0
        a.shoreChanged()
        val y = sy + 3
        val depot = a.depots.size
        a.apply(Command.Depot(0, sx + 1, y, Track.XP))
        a.apply(Command.Rail(0, sx + 2, y, sx + 36, y, Track.X, remove = false))
        val station = a.stations.size
        a.apply(Command.Station(0, sx + 8, y, sx + 11, y, Track.X))
        a.apply(Command.Station(0, sx + 33, y, sx + 36, y, Track.X))
        val engine = a.roster.available(a.startYear, true).first()
        val wagon = a.roster.available(a.startYear, false).firstOrNull { Cargo.PASSENGERS in it.cargoes } ?: error("no coach")
        a.apply(Command.BuyTrain(0, depot, engine.id, wagon.id, 3))
        val train = a.trains.size - 1
        a.apply(Command.TrainOrder(0, train, Command.TrainOrder.ORDERS, intArrayOf(station, station + 1)))
        a.apply(Command.TrainOrder(0, train, Command.TrainOrder.START_STOP))
        val docks = a.stations.size
        a.apply(Command.Dock(0, sx + 7, sy + 14))
        a.apply(Command.Dock(0, sx + 32, sy + 16))
        val yard = a.depots.size
        a.apply(Command.Shipyard(0, sx + 7, sy + 10))
        val ship = a.roster.availableOf(VehicleKind.SHIP, a.startYear).firstOrNull { Cargo.PASSENGERS in it.cargoes } ?: error("no ship")
        a.apply(Command.BuyCraft(0, yard, ship.id, Cargo.PASSENGERS))
        a.apply(Command.CraftOrder(0, false, a.ships.size - 1, Command.TrainOrder.ORDERS, intArrayOf(docks, docks + 1)))
        a.apply(Command.CraftOrder(0, false, a.ships.size - 1, Command.TrainOrder.START_STOP))
        assertTrue(a.trains.singleOrNull()?.running == true, "a train")
        assertTrue(a.ships.singleOrNull()?.running == true, "a ship")
    }
}
