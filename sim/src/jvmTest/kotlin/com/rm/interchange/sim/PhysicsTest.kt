package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhysicsTest {
    /** A line along y = 10 from a depot at x = 2, rising a step a tile from x = [climbFrom] for ten tiles if [hill]. */
    private fun line(hill: Boolean, engine: String, wagon: String, wagons: Int, year: Int = 1935, climbFrom: Int = 20): Pair<World, Train> {
        val w = World(64, 64, 1, startYear = year)
        for (y in 0..64) for (x in 0..64) {
            val rise = if (hill) (x - climbFrom).coerceIn(0, 10) else 0
            w.corners[w.corner(x, y)] = (3 + rise).toByte()
        }
        w.companies[0].money = 100_000_000
        w.apply(Command.Depot(0, 2, 10, Track.XP))
        w.apply(Command.Rail(0, 3, 10, 60, 10, Track.X, remove = false))
        w.apply(Command.Station(0, 57, 10, 60, 10, Track.X))
        val e = w.roster.engines.first { it.key == "base:$engine" }
        val wg = w.roster.engines.first { it.key == "base:$wagon" }
        w.apply(Command.BuyTrain(0, 0, e.id, wg.id, wagons))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.ORDERS, intArrayOf(0)))
        w.apply(Command.TrainOrder(0, 0, Command.TrainOrder.START_STOP))
        val t = w.trains[0]!!
        // Loaded full.
        for (c in 0 until Cargo.COUNT) if (t.capacity(c) > 0) t.cargo[c] += Packet(-1, -1, t.capacity(c), 0)
        return w to t
    }

    /** The fastest the train goes, in km/h, while its nose is between tiles [from] and [until] along x. */
    private fun topSpeed(w: World, t: Train, from: Int, until: Int): Int {
        var top = 0
        repeat(6000) {
            w.step()
            val x = stepTile(t.steps.lastOrNull() ?: return@repeat) % w.width
            if (!t.inDepot && x in from until until) top = maxOf(top, t.speed * 2700 / 65536)
        }
        return top
    }

    @Test
    fun aGradeSlowsAHeavyTrainAndALightOneLess() {
        val (flat, ft) = line(false, "consolidation", "hopper", 12, climbFrom = 12)
        val (hill, ht) = line(true, "consolidation", "hopper", 12, climbFrom = 12)
        val onFlat = topSpeed(flat, ft, 18, 22)
        val onHill = topSpeed(hill, ht, 18, 22)
        assertTrue(onHill < onFlat, "$onHill on the hill, $onFlat on the flat")
        val (light, lt) = line(true, "consolidation", "hopper", 2, climbFrom = 12)
        assertTrue(topSpeed(light, lt, 18, 22) > onHill)
    }

    @Test
    fun aTrainTooHeavyForTheGradeCrawlsUpIt() {
        val (w, t) = line(true, "dorchester", "flatcar", 12, year = 1850, climbFrom = 12)
        assertTrue(w.gradeForce(t) == 0L)
        var crept = false
        repeat(20_000) {
            w.step()
            val x = stepTile(t.steps.lastOrNull() ?: return@repeat) % w.width
            if (!t.inDepot && x in 16..20 && t.speed in 1..CRAWL) crept = true
        }
        assertTrue(crept, "never down to a crawl on the grade")
    }

    @Test
    fun forceAndResistanceAreSensible() {
        val (w, t) = line(false, "hudson", "coach_steel", 8)
        val mass = t.massKg()
        assertEquals((150 + 110 + 8 * 40) * 1000L + 8 * 64 * 90, mass)
        // Full grip at a stand: its tractive effort, 201 kN.
        assertEquals(201_000L, t.tractiveForce(0, 100))
        // At 100 km/h the power is what limits it: 3200 hp at 27.8 m/s is about 86 kN.
        val at100 = t.tractiveForce(2427, 100)
        assertTrue(at100 in 80_000L..92_000L, "$at100")
        assertTrue(t.tractiveForce(0, 60) < t.tractiveForce(0, 100))
        assertTrue(resistance(mass, 2427) > resistance(mass, 0))
        assertEquals(0L, w.gradeForce(t))
    }
}
