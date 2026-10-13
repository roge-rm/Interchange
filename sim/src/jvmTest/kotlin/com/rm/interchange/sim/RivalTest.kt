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
        val w = World(NewGame(160, 160, 5, 1930, Region.MIXED, towns = 3, industries = 4, rivals = 2))
        // Past a railway's first year, while its stations' ratings and traffic build.
        w.days(365 * 7)
        val rail = w.rivals.flatMap { r -> r.routes.filter { it.rail }.map { r.company to it } }
        assertTrue(rail.isNotEmpty(), "no rival built a railway")
        for ((company, rt) in rail) {
            assertEquals(company, w.stations[rt.a]!!.owner)
            assertTrue(w.stations[rt.a]!!.tiles.isNotEmpty() && w.stations[rt.b]!!.tiles.isNotEmpty(), "a railway without platforms")
            val train = w.trains.single { it != null && it.owner == company && Order.station(it.orders[0]) == rt.a }!!
            assertTrue(train.profitThisYear + train.profitLastYear > 0, "its train doesn't pay")
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
            v.built = w.day - 1000
            v.profitLastYear = -100
        }
        w.days(120)
        assertTrue(route !in r.routes, "the route is still run")
        assertTrue(w.roadVehicles.none { it != null && it.owner == r.company && it.orders.isNotEmpty() && Order.station(it.orders[0]) == route.a }, "its vehicles weren't sold")
        assertTrue(w.stations.getOrNull(route.a) == null || r.routes.any { it.a == route.a || it.b == route.a }, "its stop is still there")
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
