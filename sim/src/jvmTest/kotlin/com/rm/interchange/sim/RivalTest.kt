package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RivalTest {
    private fun game() = World(NewGame(128, 128, 7, 1930, Region.MIXED, towns = 3, industries = 0, rivals = 2))

    private fun World.days(n: Int) = repeat(Calendar.TICKS_PER_DAY * n) { step() }

    @Test
    fun rivalsBuildRoutesAndCarryPeople() {
        val w = game()
        assertEquals(3, w.companies.size)
        w.days(400)
        assertTrue(w.rivals.any { it.routes.isNotEmpty() }, "no rival built a route")
        assertTrue(w.rivals.any { r -> w.companies[r.company].let { it.thisYear[Books.INCOME] + it.lastYear[Books.INCOME] } > 0 }, "no rival earned anything")
        // What they built is theirs.
        for (r in w.rivals) for (rt in r.routes) assertEquals(r.company, w.stations[rt.a]!!.owner)
        // A new year moved road vehicles' profit to last year's.
        assertTrue(w.roadVehicles.any { it != null && it.profitLastYear != 0L }, "no road vehicle has last year's profit")
    }

    @Test
    fun rivalsHaulFreight() {
        val w = World(NewGame(128, 128, 1, 1930, Region.MIXED, towns = 3, industries = 4, rivals = 2))
        w.days(365 * 4 + 200)
        val freight = w.rivals.flatMap { it.routes }.filter { it.freight }
        assertTrue(freight.isNotEmpty(), "no rival built a freight route")
        // The industry it loads at is sending it cargo.
        assertTrue(freight.any { rt -> w.industries[rt.townA].let { it.movedLastMonth[rt.cargo] + it.moved[rt.cargo] } > 0 }, "no freight moved")
        assertTrue(freight.any { rt -> w.industries[rt.townB].broughtLastMonth + w.industries[rt.townB].broughtThisMonth > 0 }, "no freight delivered")
    }

    @Test
    fun rivalsRunRailways() {
        // Hard rivals try rail first, before buses take the available town pairs.
        val w = World(NewGame(160, 160, 1, 1930, Region.MIXED, towns = 3, industries = 4, rivals = 2, rivalLevel = RivalLevel.HARD))
        // Past a railway's first year, while its stations' ratings and traffic build; one built in the last year may not pay yet.
        w.days(365 * 6)
        val yearOld = w.rivals.flatMap { r -> r.routes.filter { it.rail }.map { r.company to it.a } }.toSet()
        w.days(365)
        val rail = w.rivals.flatMap { r -> r.routes.filter { it.rail }.map { r.company to it } }
        assertTrue(rail.isNotEmpty(), "no rival built a railway")
        assertTrue(w.chronicle.none { it.kind == News.CRASH }, "a rival's trains crashed")
        assertTrue(rail.any { (company, rt) -> (company to rt.a) in yearOld }, "every railway is new")
        for ((company, rt) in rail) {
            assertEquals(company, w.stations[rt.a]!!.owner)
            assertTrue(w.stations[rt.a]!!.tiles.isNotEmpty() && w.stations[rt.b]!!.tiles.isNotEmpty(), "a railway without platforms")
            val on = w.trains.filter { it != null && it.owner == company && Order.station(it.orders[0]) == rt.a }.map { it!! }
            assertTrue(on.isNotEmpty() && on.size <= rt.loops.size + 1, "${on.size} trains on ${rt.loops.size} loops")
            if ((company to rt.a) in yearOld) assertTrue(on.sumOf { it.profitThisYear + it.profitLastYear } > 0, "its trains don't pay")
        }
    }

    @Test
    fun rivalsGiveUpRoutesThatLose() {
        val w = game()
        w.days(400)
        val r = w.rivals.first { it.routes.isNotEmpty() }
        val route = r.routes.first()
        // Every vehicle on it old and losing money.
        var n = 0
        for (v in w.roadVehicles) if (v != null && v.owner == r.company && Order.station(v.orders[0]) == route.a) {
            v.built = w.workDay - 1000 * w.settings.dayLength
            v.profitLastYear = -100
            n++
        }
        // Sent to the garage one at a time, a look apart, with a trip or two called off and sent again from the far end.
        w.days(30 * (n + 4))
        assertTrue(route !in r.routes, "the route is still run")
        assertTrue(w.roadVehicles.none { it != null && it.owner == r.company && it.orders.isNotEmpty() && Order.station(it.orders[0]) == route.a }, "its vehicles weren't sold")
        assertTrue(w.stations.getOrNull(route.a) == null || r.routes.any { it.a == route.a || it.b == route.a }, "its stop is still there")
    }

    @Test
    fun aRailwayGivenUpIsTakenUp() {
        val w = World(NewGame(160, 160, 5, 1930, Region.MIXED, towns = 3, industries = 0, rivals = 1))
        val r = w.rivals[0]
        val c = w.companies[r.company]
        c.money = 10_000_000
        // Any line between the two biggest towns, wherever it'll go.
        val towns = w.towns.sortedByDescending { it.population }
        val serves = { s: Station, _: Boolean -> (w.stationPeople[s.id] ?: 0) > 0 }
        var built = false
        loop@ for (t1 in towns) for (t2 in towns) {
            if (t1.id == t2.id) continue
            if (w.tryRail(c, railSite(t1, t2.x, t2.y), railSite(t2, t1.x, t1.y), Cargo.PASSENGERS, 100, t1.id, t2.id, serves, payback = 0) { r.routes += it } == Tried.BUILT) { built = true; break@loop }
        }
        assertTrue(built, "no line to test with")
        r.thinkOn = Int.MAX_VALUE
        val route = r.routes.single { it.rail }
        val line = (0 until w.width * w.height).filter { w.owner[it].toInt() == r.company && w.track[it].toInt() != 0 }
        assertTrue(line.size > 20, "line of ${line.size}")
        w.days(30)
        // Its train old and losing money.
        for (t in w.trains) if (t != null && t.owner == r.company) { t.built = w.workDay - 1000 * w.settings.dayLength; t.profitLastYear = -100 }
        repeat(12) { w.days(30); w.sellLosers(r, c) }
        assertTrue(route !in r.routes, "the railway is still run")
        assertTrue(line.none { w.track[it].toInt() != 0 }, "track left on ${line.count { w.track[it].toInt() != 0 }} tiles")
        assertTrue(w.triedLately(r, keyOfRail(route)), "not remembered")
    }

    @Test
    fun aGivenUpRailwayLeavesTrackJoinedOnToIt() {
        val w = World(NewGame(160, 160, 5, 1930, Region.MIXED, towns = 3, industries = 0, rivals = 1))
        val r = w.rivals[0]
        val c = w.companies[r.company]
        c.money = 10_000_000
        val towns = w.towns.sortedByDescending { it.population }
        val serves = { s: Station, _: Boolean -> (w.stationPeople[s.id] ?: 0) > 0 }
        var built = false
        loop@ for (t1 in towns) for (t2 in towns) {
            if (t1.id == t2.id) continue
            if (w.tryRail(c, railSite(t1, t2.x, t2.y), railSite(t2, t1.x, t1.y), Cargo.PASSENGERS, 100, t1.id, t2.id, serves, payback = 0) { r.routes += it } == Tried.BUILT) { built = true; break@loop }
        }
        assertTrue(built, "no line to test with")
        r.thinkOn = Int.MAX_VALUE
        val route = r.routes.single { it.rail }
        val line = (0 until w.width * w.height).filter { w.owner[it].toInt() == r.company && w.track[it].toInt() != 0 }
        // Track of the rival's own laid on past the far terminus's back end, as if from a company it bought.
        val far = w.stations[route.b]!!
        var joined = -1
        for (i in far.tiles) for (e in 0 until 4) {
            if (joined >= 0) break
            if (Track.PIECES.none { w.track[i].toInt() and it != 0 && Track.has(it, e) }) continue
            val x = i % w.width + Track.dx(e)
            val y = i / w.width + Track.dy(e)
            if (!w.inside(x, y)) continue
            val n = w.index(x, y)
            if (w.track[n].toInt() != 0 || w.stationTiles.containsKey(n) || w.depotTiles.containsKey(n)) continue
            w.track[n] = Track.PIECES.first { Track.has(it, Track.opposite(e)) }.toByte()
            w.owner[n] = r.company.toByte()
            joined = n
        }
        assertTrue(joined >= 0, "nowhere to join on")
        w.days(30)
        for (t in w.trains) if (t != null && t.owner == r.company) { t.built = w.workDay - 1000 * w.settings.dayLength; t.profitLastYear = -100 }
        repeat(12) { w.days(30); w.sellLosers(r, c) }
        assertTrue(route !in r.routes, "the railway is still run")
        assertTrue(w.track[joined].toInt() != 0, "the joined track was taken up")
        assertTrue(line.none { w.track[it].toInt() != 0 }, "track left on ${line.count { w.track[it].toInt() != 0 }} tiles")
    }

    /** A railway for [r] between the biggest towns that'll take one, any that will do. */
    private fun railFor(w: World, r: Rival): RivalRoute? {
        val c = w.companies[r.company]
        val towns = w.towns.sortedByDescending { it.population }
        val serves = { s: Station, _: Boolean -> (w.stationPeople[s.id] ?: 0) > 0 }
        for (t1 in towns) for (t2 in towns) {
            if (t1.id == t2.id) continue
            if (w.tryRail(c, railSite(t1, t2.x, t2.y), railSite(t2, t1.x, t1.y), Cargo.PASSENGERS, 100, t1.id, t2.id, serves, payback = 0) { r.routes += it } == Tried.BUILT) return r.routes.last()
        }
        return null
    }

    @Test
    fun aPassingLoopRunsASecondTrain() {
        val w = World(NewGame(160, 160, 1, 1930, Region.MIXED, towns = 3, industries = 0, rivals = 1))
        val r = w.rivals[0]
        val c = w.companies[r.company]
        c.money = 10_000_000
        r.thinkOn = Int.MAX_VALUE
        val route = railFor(w, r) ?: error("no line to test with")
        val line = (0 until w.width * w.height).filter { w.owner[it].toInt() == r.company && w.track[it].toInt() != 0 }
        assertTrue(w.addLoop(c, route), "no loop laid")
        assertEquals(1, route.loops.size)
        assertEquals(2, w.loopTiles(route.loops[0]).count { i -> Track.PIECES.any { w.signals[pieceKey(i, it)]?.let { s -> Signals.oneWay(s) } == true } })
        assertTrue(w.addTrain(c, route), "no second train")
        val on = w.trains.filterNotNull().filter { it.owner == r.company }
        assertEquals(2, on.size)
        // Both trains keep calling at both ends, passing on the loop.
        val calls = IntArray(2)
        val last = IntArray(2) { -1 }
        repeat(Calendar.TICKS_PER_DAY * 365) {
            w.step()
            for ((k, t) in on.withIndex()) if (t.order != last[k]) { calls[k]++; last[k] = t.order }
        }
        assertTrue(calls.all { it >= 8 }, "calls ${calls.toList()}")
        // Given up, the loop and the signal tile come up with the line.
        for (t in on) { t.built = w.workDay - 1000 * w.settings.dayLength; t.profitLastYear = -100 }
        val loop = w.loopTiles(route.loops[0]).asList()
        repeat(12) { w.days(30); w.sellLosers(r, c) }
        assertTrue(route !in r.routes, "the railway is still run")
        val all = line + loop
        assertTrue(all.none { w.track[it].toInt() != 0 }, "track left on ${all.count { w.track[it].toInt() != 0 }} tiles")
    }

    @Test
    fun aLineWithTwoTrainsHasThemServiced() {
        val w = World(NewGame(160, 160, 1, 1930, Region.MIXED, towns = 3, industries = 0, rivals = 1))
        val r = w.rivals[0]
        val c = w.companies[r.company]
        c.money = 10_000_000
        r.thinkOn = Int.MAX_VALUE
        val route = railFor(w, r) ?: error("no line to test with")
        assertTrue(w.addLoop(c, route), "no loop laid")
        assertTrue(w.addTrain(c, route), "no second train")
        val on = w.trains.filterNotNull().filter { it.owner == r.company }
        assertEquals(2, on.size)
        // Three work years: each is serviced in turn, never going twice the service time without, and both keep calling.
        val calls = IntArray(2)
        val last = IntArray(2) { -1 }
        var longest = 0
        var leastReliable = 10_000
        repeat(Calendar.TICKS_PER_DAY * 365 * 3) {
            w.step()
            for ((k, t) in on.withIndex()) {
                // The line only just pays: neither is let lose a year and be sent in to be sold.
                t.profitLastYear = maxOf(t.profitLastYear, 0)
                if (t.order != last[k]) { calls[k]++; last[k] = t.order }
                longest = maxOf(longest, w.workDay - t.serviced)
                leastReliable = minOf(leastReliable, t.reliability)
            }
        }
        assertTrue(longest <= SERVICE_DAYS * 2, "went $longest work days without a service")
        assertTrue(leastReliable >= 5000, "reliability fell to $leastReliable")
        assertTrue(calls.all { it >= 24 }, "calls ${calls.toList()}")
        assertTrue(on.all { it.running && !it.inDepot }, "a train stopped")
    }

    @Test
    fun aRivalsTrainGetsAPlowWhereTheSnowLies() {
        val w = World(NewGame(160, 160, 1, 1930, Region.MIXED, towns = 3, industries = 0, rivals = 1))
        val r = w.rivals[0]
        w.companies[r.company].money = 10_000_000
        r.thinkOn = Int.MAX_VALUE
        railFor(w, r) ?: error("no line to test with")
        val t = w.trains.filterNotNull().single { it.owner == r.company }
        assertEquals(Plows.WEDGE, t.plow)
        assertEquals(Plows.WEDGE, w.roster[t.formation[0].model].plow)
        // Without weather it runs without one.
        val dry = World(NewGame(160, 160, 1, 1930, Region.MIXED, towns = 3, industries = 0, rivals = 1, weather = false))
        val dr = dry.rivals[0]
        dry.companies[dr.company].money = 10_000_000
        dr.thinkOn = Int.MAX_VALUE
        railFor(dry, dr) ?: error("no line to test with")
        assertEquals(Plows.NONE, dry.trains.filterNotNull().single { it.owner == dr.company }.plow)
    }

    @Test
    fun theTrainLeftOnALineIsServicedAgain() {
        val w = World(NewGame(160, 160, 1, 1930, Region.MIXED, towns = 3, industries = 0, rivals = 1))
        val r = w.rivals[0]
        val c = w.companies[r.company]
        c.money = 10_000_000
        r.thinkOn = Int.MAX_VALUE
        val route = railFor(w, r) ?: error("no line to test with")
        assertTrue(w.addLoop(c, route), "no loop laid")
        assertTrue(w.addTrain(c, route), "no second train")
        val (left, sold) = w.trains.filterNotNull().filter { it.owner == r.company }
        w.days(30)
        // The second one is sent in and sold, as a loser would be.
        if (sold.toDepot) w.apply(Command.TrainOrder(c.id, sold.id, Command.TrainOrder.TO_DEPOT))
        w.apply(Command.TrainOrder(c.id, sold.id, Command.TrainOrder.TO_DEPOT))
        repeat(60) { if (!sold.inDepot) w.days(1) }
        assertTrue(sold.inDepot, "the train sent in never got there")
        w.apply(Command.TrainOrder(c.id, sold.id, Command.TrainOrder.SELL))
        assertTrue(w.trains[sold.id] == null, "not sold")
        w.days(SERVICE_DAYS * 2)
        assertTrue(left.service, "servicing left off")
        assertTrue(w.workDay - left.serviced < SERVICE_DAYS * 2, "not serviced in ${w.workDay - left.serviced} work days")
    }

    @Test
    fun trainsStuckOnTheWayToTheDepotSetOffAgain() {
        val w = World(NewGame(160, 160, 1, 1930, Region.MIXED, towns = 3, industries = 0, rivals = 1))
        val r = w.rivals[0]
        val c = w.companies[r.company]
        c.money = 10_000_000
        r.thinkOn = Int.MAX_VALUE
        val route = railFor(w, r) ?: error("no line to test with")
        assertTrue(w.addLoop(c, route), "no loop laid")
        assertTrue(w.addTrain(c, route), "no second train")
        val on = w.trains.filterNotNull().filter { it.owner == r.company }
        w.days(30)
        // The depot cut off, and both sent in to be sold: neither can get there, and they stand.
        val d = w.depots[route.depot]!!
        val x = d.tile % w.width + Track.dx(d.edge)
        val y = d.tile / w.width + Track.dy(d.edge)
        // Taken up once no train stands on it.
        for (k in 0 until 30) {
            w.apply(Command.Rail(c.id, x, y, x, y, Track.straight(d.edge), remove = true))
            if (w.track[w.index(x, y)].toInt() and Track.straight(d.edge) == 0) break
            w.days(1)
        }
        assertEquals(0, w.track[w.index(x, y)].toInt() and Track.straight(d.edge), "the way to the depot is still there")
        for (t in on) {
            if (t.toDepot) w.apply(Command.TrainOrder(c.id, t.id, Command.TrainOrder.TO_DEPOT))
            w.apply(Command.TrainOrder(c.id, t.id, Command.TrainOrder.TO_DEPOT))
        }
        w.days(JAM_DAYS / 2)
        assertTrue(on.all { it.speed == 0 && it.toDepot }, "not stuck")
        // Stuck that long, their trips are called off and they run their line again.
        val calls = IntArray(2)
        val last = IntArray(2) { on[it].order }
        repeat(Calendar.TICKS_PER_DAY * 365) {
            w.step()
            for ((k, t) in on.withIndex()) if (t.order != last[k]) { calls[k]++; last[k] = t.order }
        }
        assertTrue(calls.all { it >= 6 }, "calls ${calls.toList()}")
    }

    @Test
    fun losingTrainsGoInOneAtATimeAndNotStraightBackAfterAJam() {
        val w = World(NewGame(160, 160, 1, 1930, Region.MIXED, towns = 3, industries = 0, rivals = 1))
        val r = w.rivals[0]
        val c = w.companies[r.company]
        c.money = 10_000_000
        r.thinkOn = Int.MAX_VALUE
        val route = railFor(w, r) ?: error("no line to test with")
        assertTrue(w.addLoop(c, route), "no loop laid")
        assertTrue(w.addTrain(c, route), "no second train")
        val on = w.trains.filterNotNull().filter { it.owner == r.company }
        w.days(30)
        // The depot cut off, and both old and losing money: one goes in to be sold, can't get there and stands.
        val d = w.depots[route.depot]!!
        val x = d.tile % w.width + Track.dx(d.edge)
        val y = d.tile / w.width + Track.dy(d.edge)
        w.apply(Command.Rail(c.id, x, y, x, y, Track.straight(d.edge), remove = true))
        for (t in on) {
            if (t.toDepot) w.apply(Command.TrainOrder(c.id, t.id, Command.TrainOrder.TO_DEPOT))
            t.built = w.workDay - 1000 * w.settings.dayLength
            t.profitLastYear = -100
        }
        fun selling(t: Train) = t.toDepot && !t.servicing
        // Looks every 30 work days as a rival does, for the rest of the year.
        val trips = IntArray(2)
        val was = BooleanArray(2)
        var both = 0
        for (day in 0 until 300) {
            if (day % 30 == 0) w.sellLosers(r, c)
            repeat(Calendar.TICKS_PER_DAY) { w.step() }
            for ((k, t) in on.withIndex()) {
                if (selling(t) && !was[k]) trips[k]++
                was[k] = selling(t)
            }
            if (on.all { selling(it) }) both++
        }
        assertEquals(0, both, "days with both on their way to be sold")
        assertTrue(trips.all { it in 1..2 }, "trips ${trips.toList()}")
    }

    @Test
    fun aJammedRoadVehicleOverdueAServiceIsntSentStraightBack() {
        val w = game()
        w.days(400)
        val r = w.rivals.first { it.routes.any { !it.rail } }
        val c = w.companies[r.company]
        r.thinkOn = Int.MAX_VALUE
        val v = w.roadVehicles.first { it != null && it.owner == c.id && !it.inDepot && it.running }!!
        // Overdue, on its way to the garage, and stood there since a jam began.
        v.serviced = w.workDay - 1000
        v.toDepot = true
        v.servicing = true
        v.speed = 0
        v.waiting = 0
        v.brokenFor = 0
        r.stood[v.id] = w.workDay - JAM_DAYS
        w.unjam(r, c)
        assertTrue(!v.toDepot, "its garage trip wasn't called off")
        w.roadVehiclesDay()
        assertTrue(!v.toDepot, "sent back to the garage the next day")
        w.days(10)
        assertTrue(!v.toDepot || !v.servicing, "sent back to the garage within days")
    }

    @Test
    fun oneLoserARoadRouteGoesInAtATime() {
        val w = game()
        w.days(400)
        val (r, route) = w.rivals.flatMap { r -> r.routes.filter { !it.rail }.map { r to it } }
            .maxBy { (r, rt) -> w.roadVehicles.count { it != null && it.owner == r.company && onRoute(it, rt) } }
        val c = w.companies[r.company]
        r.thinkOn = Int.MAX_VALUE
        val on = w.roadVehicles.filterNotNull().filter { it.owner == c.id && onRoute(it, route) }
        assertTrue(on.size >= 2, "only ${on.size} on the route")
        for (v in on) {
            if (v.toDepot) w.apply(Command.RoadOrder(c.id, v.id, Command.TrainOrder.TO_DEPOT))
            v.built = w.workDay - 1000 * w.settings.dayLength
            v.profitLastYear = -100
        }
        w.sellLosers(r, c)
        assertEquals(1, on.count { it.toDepot && !it.servicing }, "on their way to be sold")
        w.sellLosers(r, c)
        assertEquals(1, on.count { it.toDepot && !it.servicing }, "on their way to be sold after another look")
    }

    @Test
    fun aRouteIsHeldWhileAnotherCompanysVehicleIsOnItsStop() {
        val w = game()
        w.days(400)
        val (r, route) = w.rivals.flatMap { r -> r.routes.filter { !it.rail }.map { r to it } }.first { (r, rt) ->
            r.routes.none { it !== rt && !it.rail && (it.a == rt.a || it.b == rt.a) } && w.roadVehicles.any { it != null && it.owner == r.company && onRoute(it, rt) }
        }
        val c = w.companies[r.company]
        r.thinkOn = Int.MAX_VALUE
        val stops = w.stations[route.a]!!.stops.toSet()
        // One of its vehicles on its stop, handed to another company and stopped there; the rest gone.
        var v: RoadVehicle? = null
        repeat(Calendar.TICKS_PER_DAY * 60) {
            if (v == null) {
                w.step()
                v = w.roadVehicles.firstOrNull { it != null && it.owner == c.id && onRoute(it, route) && !it.inDepot && roadStepTile(it.at) in stops }
            }
        }
        val parked = v ?: error("none reached its stop")
        val other = w.companies.first { it.id != c.id }.id
        parked.owner = other
        parked.running = false
        for (u in w.roadVehicles) if (u != null && u.owner == c.id && onRoute(u, route)) w.roadVehicles[u.id] = null
        w.sellLosers(r, c)
        assertTrue(route in r.routes, "given up with another's vehicle on its stop")
        assertEquals(1, r.held.size)
        // Held that long, it's given up anyway.
        r.held.replaceAll { _, _ -> w.workDay - HOLD_DAYS }
        w.sellLosers(r, c)
        assertTrue(route !in r.routes, "still held")
        assertTrue(r.held.isEmpty())
    }

    @Test
    fun standingVehiclesAndHeldRoutesAreSaved() {
        val w = game()
        w.days(100)
        val r = w.rivals[0]
        r.stood[3] = w.workDay - 5
        r.stood[-2] = w.workDay - 40
        r.held[1234L] = w.workDay - 7
        val c = Save.read(Save.write(w))
        assertEquals(r.stood, c.rivals[0].stood)
        assertEquals(r.held, c.rivals[0].held)
        assertEquals(w.checksum(), c.checksum())
        r.stood[3] = w.workDay - 6
        assertTrue(w.checksum() != c.checksum(), "stood isn't in the checksum")
    }

    /** Runs on a day at a time, up to a work year, till [a] and [b] are both out running the line, clear of the depot. */
    private fun World.bothOut(c: Company, route: RivalRoute, a: Train, b: Train): Boolean {
        val lead = leadTile(c, route)
        fun out(t: Train) = t.running && !t.inDepot && !t.toDepot && t.wreckedFor == 0 && lead !in t.onTiles
        for (d in 0 until 365) {
            if (out(a) && out(b) && !blocksDirty) return true
            days(1)
        }
        return false
    }

    @Test
    fun rivalReachAndPaybackGrowWithSpareMoneyWithinTheCap() {
        for (level in listOf(RivalLevel.NORMAL, RivalLevel.HARD)) {
            val w = World(NewGame(128, 128, 7, 1930, Region.MIXED, towns = 3, industries = 0, rivals = 1, rivalLevel = level))
            val c = w.companies[w.rivals[0].company]
            c.loan = 0
            val cap = if (level == RivalLevel.HARD) HARD_ROUTES else MAX_ROUTES
            var reach = 0
            var payback = 0
            for (k in 0..20) {
                c.money = w.maxLoan() * k
                val now = w.homeReach(c)
                val asks = w.richPayback(c, 16)
                assertTrue(now >= reach && asks >= payback, "level $level, $k limits: reach $now, payback $asks")
                assertTrue(now <= HOME_REACH + HARD_REACH + maxOf(w.width, w.height) && asks <= 32, "level $level, $k limits: reach $now, payback $asks")
                assertTrue(w.maxRoutes(c) <= cap, "level $level, $k limits: ${w.maxRoutes(c)} routes")
                reach = now
                payback = asks
            }
            assertTrue(reach > HOME_REACH + HARD_REACH && payback == 32, "level $level: reach $reach, payback $payback")
        }
    }

    @Test
    fun aPlayerKeepsHomeReachAndItsOwnPayback() {
        for (level in listOf(RivalLevel.NORMAL, RivalLevel.HARD)) {
            val w = World(NewGame(128, 128, 7, 1930, Region.MIXED, towns = 3, industries = 0, rivals = 1, rivalLevel = level))
            val c = w.companies[0]
            assertTrue(w.human(c.id))
            c.loan = 0
            for (k in 0..20) {
                c.money = w.maxLoan() * k
                assertEquals(HOME_REACH, w.homeReach(c), "level $level, $k limits")
                assertEquals(16, w.richPayback(c, 16), "level $level, $k limits")
            }
        }
        assertEquals(listOf(14, 16, 24), PLAYER_ROAD_PAYBACK.toList())
    }

    @Test
    fun theServiceTurnSkipsATrainHeldAfterAJam() {
        val w = World(NewGame(160, 160, 1, 1930, Region.MIXED, towns = 3, industries = 0, rivals = 1))
        val r = w.rivals[0]
        val c = w.companies[r.company]
        c.money = 10_000_000
        r.thinkOn = Int.MAX_VALUE
        val route = railFor(w, r) ?: error("no line to test with")
        assertTrue(w.addLoop(c, route), "no loop laid")
        assertTrue(w.addTrain(c, route), "no second train")
        val (held, other) = w.trains.filterNotNull().filter { it.owner == r.company }
        w.days(30)
        assertTrue(w.bothOut(c, route, held, other), "no day with both trains out on the line")
        // Both stand at a platform overdue; the one longest without, held by unjam, would go first.
        for (t in listOf(held, other)) { t.waiting = 100; t.speed = 0 }
        held.serviced = w.workDay - SERVICE_DAYS * 3
        other.serviced = w.workDay - SERVICE_DAYS * 2
        held.noDepotUntil = w.tick + UNJAM_HOLD
        w.unblockDepot(c, route)
        assertTrue(!held.service, "the held train has the service turn")
        assertTrue(other.service, "the other overdue train wasn't given the turn")
        // Its hold run out, it goes first again.
        held.noDepotUntil = 0
        w.unblockDepot(c, route)
        assertTrue(held.service && !other.service, "the turn didn't go back to the one longest without")
    }

    @Test
    fun aTrainQueuedBehindALoadingTrainIsntUnjammed() {
        val w = World(NewGame(160, 160, 1, 1930, Region.MIXED, towns = 3, industries = 0, rivals = 1))
        val r = w.rivals[0]
        val c = w.companies[r.company]
        c.money = 10_000_000
        r.thinkOn = Int.MAX_VALUE
        val route = railFor(w, r) ?: error("no line to test with")
        assertTrue(w.addLoop(c, route), "no loop laid")
        assertTrue(w.addTrain(c, route), "no second train")
        val (loading, queued) = w.trains.filterNotNull().filter { it.owner == r.company }
        w.days(30)
        assertTrue(w.bothOut(c, route, loading, queued), "no day with both trains out on the line")
        // One loads at a stop and the other, headed there, has stood the whole time.
        loading.waiting = 100
        loading.speed = 0
        val stop = Order.station(loading.orders[loading.order])
        queued.order = queued.orders.indexOfFirst { Order.station(it) == stop }
        queued.speed = 0
        queued.waiting = 0
        queued.brokenFor = 0
        if (queued.toDepot) w.apply(Command.TrainOrder(c.id, queued.id, Command.TrainOrder.TO_DEPOT))
        fun headedFor() = Order.station(queued.orders[queued.order])
        r.stood[-1 - queued.id] = w.workDay - JAM_DAYS
        w.unjam(r, c)
        assertEquals(stop, headedFor(), "the queued train's orders were turned")
        assertTrue(-1 - queued.id !in r.stood, "the queued train is still counted as standing")
        // With the other gone on, it's stuck.
        loading.waiting = 0
        loading.speed = 1
        r.stood[-1 - queued.id] = w.workDay - JAM_DAYS
        w.unjam(r, c)
        assertTrue(headedFor() != stop, "a stuck train's orders weren't turned")
    }

    @Test
    fun rivalStationsGoInTown() {
        // The same line built by a rival, which may pull down a few town buildings, and by the player, who doesn't here.
        fun build(rival: Boolean): Pair<World, RivalRoute> {
            val w = World(NewGame(160, 160, 5, 1930, Region.MIXED, towns = 3, industries = 0, rivals = 1))
            val r = w.rivals[0]
            r.thinkOn = Int.MAX_VALUE
            val plan = if (rival) r else Rival(0, 0)
            w.companies[plan.company].money = 10_000_000
            return w to (railFor(w, plan) ?: error("no line to test with"))
        }
        val (wr, rr) = build(true)
        val (wp, rp) = build(false)
        fun people(w: World, rt: RivalRoute) = (w.stationPeople[rt.a] ?: 0) + (w.stationPeople[rt.b] ?: 0)
        assertTrue(people(wr, rr) > people(wp, rp), "rival reaches ${people(wr, rr)}, player ${people(wp, rp)}")
    }

    private fun keyOfRail(route: RivalRoute) = (1L shl 61) or (route.cargo.toLong() shl 48) or (route.townA.toLong() shl 24) or route.townB.toLong()

    /**
     * Coal between two flat sites 64 tiles apart, at the least payback that builds anything. With [pocket], town
     * buildings wall in the east side of the first site's top rows, so the termini that rank best there have to go
     * the long way round. The first station's top row, or -1 if nothing's built.
     */
    private fun coalLine(pocket: Boolean): Int {
        for (payback in 1..80) {
            val w = World(96, 64, 1, startYear = 1930)
            for (i in w.corners.indices) w.corners[i] = 3
            w.trees.fill(0)
            if (pocket) {
                for (y in 6..24) w.house[w.index(19, y)] = 1
                for (x in 17..18) w.house[w.index(x, 25)] = 1
            }
            val c = w.companies[0]
            c.money = 10_000_000
            val routes = ArrayList<RivalRoute>()
            val tried = w.tryRail(c, RailSite(10, 20, 16, 40, 80, 30), RailSite(77, 20, 83, 40, 13, 30), Cargo.COAL, 300, 0, 1, { _, _ -> true }, payback) { routes += it }
            if (tried == Tried.BUILT) return w.stations[routes.single().a]!!.tiles.minOf { it / w.width }
        }
        return -1
    }

    @Test
    fun terminiThatDontPayLeaveOthersForTheSameEnds() {
        // Open ground: the best ranked spot, along the top of the site.
        assertEquals(20, coalLine(false))
        // Walled in, those don't pay at that payback, and a spot further down the same site does.
        val y = coalLine(true)
        assertTrue(y > 25, "built at row $y")
    }

    @Test
    fun pairsAreTriedAgainAfterAWhile() {
        val w = game()
        val r = w.rivals[0]
        w.giveUp(r, 42L)
        assertTrue(w.triedLately(r, 42L))
        w.days(RETRY_DAYS - 1)
        assertTrue(w.triedLately(r, 42L))
        w.days(1)
        assertTrue(!w.triedLately(r, 42L), "still not tried again")
    }

    @Test
    fun rivalsThinkByWorkDays() {
        val w = World(NewGame(128, 128, 7, 1930, Region.MIXED, towns = 3, industries = 0, rivals = 1, dayLength = 10))
        // A month of work is three calendar days at day length 10.
        val looks = HashSet<Int>()
        repeat(w.dayTicks * 30) { w.step(); looks += w.rivals[0].thinkOn }
        assertTrue(looks.size >= 9, "thought ${looks.size} times in 300 work days")
    }

    @Test
    fun rivalsMakeTheSameMovesInEveryCopy() {
        val a = game()
        val b = game()
        a.days(200)
        b.days(100)
        val c = Save.read(Save.write(b))
        b.days(100)
        c.days(100)
        assertEquals(a.checksum(), b.checksum())
        assertEquals(a.checksum(), c.checksum())
    }
}
