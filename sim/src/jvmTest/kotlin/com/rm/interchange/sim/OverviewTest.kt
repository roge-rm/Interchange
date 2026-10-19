package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverviewTest {
    private fun world(): World {
        val w = World(32, 32, 1, startYear = 1965)
        w.corners.fill(3)
        w.tick = Calendar.TICKS_PER_DAY * 100L
        w.towns += Town(0, "Map Town", 8, 8, 3)
        val h = w.roster.houses.first { it.residents > 0 }
        for (x in listOf(8, 24)) {
            val tile = w.index(x, 8)
            w.house[tile] = packHouse(h.id, Track.XM).toShort()
            w.townOf[tile] = 0
        }
        for ((id, x) in listOf(8, 24).withIndex()) {
            val s = Station(id, id, "Station $id", x, 9)
            s.stops += w.index(x, 9)
            s.lastCall[Cargo.PASSENGERS] = w.tick
            w.stations += s
        }
        return w
    }

    @Test
    fun coverageFollowsCompanyAndRecentService() {
        val w = world()
        val before = w.checksum()
        val info = w.overviewInfo()
        assertEquals(50, info.towns[0][0])
        assertEquals(50, info.towns[0][1])
        assertEquals(100, info.towns[0][OVERVIEW_ALL])
        assertEquals(before, w.checksum())
        w.stations[1]!!.lastCall.fill(-1)
        assertEquals(50, w.overviewInfo().towns[0][OVERVIEW_ALL])
        w.stations[0]!!.lastCall[Cargo.PASSENGERS] = 0
        assertEquals(0, w.overviewInfo().towns[0][OVERVIEW_ALL])
        assertEquals(100, info.towns[0][OVERVIEW_ALL], "published snapshot changed")
    }

    @Test
    fun flowAggregatesActualCargoByLegCompanyAndKind() {
        val w = world()
        val model = w.roster.engines.first { it.key == "base:new_look" }.id
        repeat(2) { id ->
            val v = RoadVehicle(w.roster, id, 0, model, -1)
            v.orders.add(1)
            v.cargo[Cargo.PASSENGERS] += Packet(1, 0, 10 + id, w.tick, boarded = 0)
            w.roadVehicles += v
        }
        val info = w.overviewInfo()
        assertEquals(1, info.flows.size)
        assertEquals(21L, info.flows.single().count)
        assertEquals(0, info.flows.single().from)
        assertEquals(1, info.flows.single().to)
        w.roadVehicles[0]!!.cargo[Cargo.PASSENGERS][0].count = 1
        assertEquals(21L, info.flows.single().count)
        w.stations[1] = null
        assertTrue(w.overviewInfo().flows.isEmpty())
    }

    @Test
    fun industryServiceNeedsTheRightCargoAndQueuesFollowCompany() {
        val w = world()
        val kind = w.roster.industries.first { it.outputs and (1 shl Cargo.COAL) != 0 && !it.takes(Cargo.PASSENGERS) }
        val ind = Industry(0, kind.id, 8, 10, 0, 0)
        w.industries += ind
        w.industryOf[w.index(8, 10)] = 1
        w.stations[0]!!.cargo[Cargo.COAL] += Packet(-1, 0, 25, w.tick)
        var info = w.overviewInfo().industries.single()
        assertEquals(0, info.coverage[OVERVIEW_ALL], "a passenger stop counted as coal service")
        assertEquals(25, info.waiting[0][Cargo.COAL])
        assertEquals(0, info.waiting[1][Cargo.COAL])
        w.stations[0]!!.lastCall[Cargo.COAL] = w.tick
        info = w.overviewInfo().industries.single()
        assertEquals(100, info.coverage[0])
        assertEquals(0, info.coverage[1])
        ind.closed = true
        assertTrue(w.overviewInfo().industries.isEmpty())
    }

    @Test
    fun trainDotsFollowTravelAndIncludeTunnelSteps() {
        val w = world()
        val t = Train(w.roster, 0, 0, 0, 18, 1, -1)
        t.inDepot = false
        t.steps += step(w.index(8, 8), 0, Track.XM, level = 1)
        w.trains += t
        val before = w.overviewVehicles().single()
        t.nose = stepLength(t.steps.last())
        val after = w.overviewVehicles().single()
        assertEquals(OverviewVehicle.TRAIN, after.mode)
        assertTrue(after.x > before.x)
        assertEquals(before.y, after.y)
        t.inDepot = true
        assertTrue(w.overviewVehicles().isEmpty())
    }

    @Test
    fun vehicleDotsLeaveOutDepotsAndWrecks() {
        val w = world()
        val v = RoadVehicle(w.roster, 0, 0, w.roster.engines.first { it.key == "base:new_look" }.id, -1)
        v.at = roadStep(w.index(8, 8), Track.XM, Track.XP)
        w.roadVehicles += v
        assertTrue(w.overviewVehicles().isEmpty())
        v.inDepot = false
        assertEquals(OverviewVehicle.ROAD, w.overviewVehicles().single().mode)
        v.wreckedFor = 10
        assertTrue(w.overviewVehicles().isEmpty())
    }
}
