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
        // Past a railway's first year, while its stations' ratings and traffic build.
        w.days(365 * 7)
        val rail = w.rivals.flatMap { r -> r.routes.filter { it.rail }.map { r.company to it } }
        assertTrue(rail.isNotEmpty(), "no rival built a railway")
        assertTrue(w.chronicle.none { it.kind == News.CRASH }, "a rival's trains crashed")
        for ((company, rt) in rail) {
            assertEquals(company, w.stations[rt.a]!!.owner)
            assertTrue(w.stations[rt.a]!!.tiles.isNotEmpty() && w.stations[rt.b]!!.tiles.isNotEmpty(), "a railway without platforms")
            val on = w.trains.filter { it != null && it.owner == company && Order.station(it.orders[0]) == rt.a }.map { it!! }
            assertTrue(on.isNotEmpty() && on.size <= rt.loops.size + 1, "${on.size} trains on ${rt.loops.size} loops")
            assertTrue(on.sumOf { it.profitThisYear + it.profitLastYear } > 0, "its trains don't pay")
        }
    }

    @Test
    fun rivalsGiveUpRoutesThatLose() {
        val w = game()
        w.days(400)
        val r = w.rivals.first { it.routes.isNotEmpty() }
        val route = r.routes.first()
        // Every vehicle on it old and losing money.
        for (v in w.roadVehicles) if (v != null && v.owner == r.company && Order.station(v.orders[0]) == route.a) {
            v.built = w.workDay - 1000 * w.settings.dayLength
            v.profitLastYear = -100
        }
        w.days(120)
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
