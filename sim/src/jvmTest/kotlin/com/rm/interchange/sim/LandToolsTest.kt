package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LandToolsTest {
    /** Flat meadow at height 3, no trees, costs as printed. */
    private fun flat(): World {
        val w = World(NewGame(64, 64, 1, 1900, towns = 0, industries = 0, inflation = false, weather = false))
        for (i in w.corners.indices) w.corners[i] = 3
        w.trees.fill(0)
        w.ground.fill(Ground.MEADOW.toByte())
        w.companies[0].money = 10_000_000
        return w
    }

    private fun setTile(w: World, x: Int, y: Int, h: Int) {
        w.corners[w.corner(x, y)] = h.toByte()
        w.corners[w.corner(x + 1, y)] = h.toByte()
        w.corners[w.corner(x, y + 1)] = h.toByte()
        w.corners[w.corner(x + 1, y + 1)] = h.toByte()
    }

    /** Every corner within a step of the ones beside it, the eight around it included. */
    private fun slopesHold(w: World) {
        for (y in 0..w.height) for (x in 0..w.width) {
            val h = w.heightAt(x, y)
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0..w.width || ny !in 0..w.height) continue
                assertTrue(kotlin.math.abs(h - w.heightAt(nx, ny)) <= 1, "corner $x,$y at $h beside $nx,$ny at ${w.heightAt(nx, ny)}")
            }
        }
    }

    @Test
    fun flatLandLevelledToItselfChangesNothing() {
        val w = flat()
        val before = w.corners.copyOf()
        val money = w.companies[0].money
        val c = Command.Level(0, 8, 8, 9, 9, 3)
        assertEquals(0L to 4, w.levelCost(c))
        w.apply(c)
        assertEquals(money, w.companies[0].money)
        assertContentEquals(before, w.corners)
    }

    @Test
    fun aRaisedTileLevelledDownCostsItsFourCorners() {
        val w = flat()
        setTile(w, 10, 10, 4)
        val c = Command.Level(0, 10, 10, 10, 10, 3)
        val cost = 4 * w.price(Prices.TERRAFORM)
        assertEquals(cost to 1, w.levelCost(c))
        val money = w.companies[0].money
        w.apply(c)
        assertEquals(money - cost, w.companies[0].money)
        assertEquals(3, w.heightAt(10, 10))
        assertEquals(3, w.heightAt(11, 11))
        assertEquals(3, w.heightAt(9, 10))
        slopesHold(w)
    }

    @Test
    fun levellingOneTileUpPullsTheLandAroundIt() {
        val w = flat()
        val c = Command.Level(0, 20, 20, 20, 20, 6)
        val (cost, n) = w.levelCost(c)
        assertEquals(1, n)
        assertTrue(cost > 4 * w.price(Prices.TERRAFORM))
        val money = w.companies[0].money
        w.apply(c)
        assertEquals(money - cost, w.companies[0].money)
        assertEquals(6, w.heightAt(20, 20))
        assertEquals(6, w.heightAt(21, 21))
        assertEquals(5, w.heightAt(19, 20))
        assertEquals(4, w.heightAt(18, 20))
        assertEquals(3, w.heightAt(17, 20))
        slopesHold(w)
    }

    @Test
    fun trackInTheWayStopsTheWholeLevel() {
        val w = flat()
        w.apply(Command.Rail(0, 10, 10, 14, 10, Track.X, remove = false))
        val before = w.corners.copyOf()
        val money = w.companies[0].money
        val c = Command.Level(0, 10, 10, 12, 12, 6)
        assertEquals(0L to 0, w.levelCost(c))
        w.apply(c)
        assertEquals(money, w.companies[0].money)
        assertContentEquals(before, w.corners)
    }

    @Test
    fun shortOfTheMoneyTheLandStaysPut() {
        val w = flat()
        setTile(w, 12, 12, 4)
        w.companies[0].money = w.price(Prices.TERRAFORM)
        val before = w.corners.copyOf()
        val c = Command.Level(0, 12, 12, 12, 12, 3)
        assertEquals(4 * w.price(Prices.TERRAFORM) to 1, w.levelCost(c))
        w.apply(c)
        assertEquals(w.price(Prices.TERRAFORM), w.companies[0].money)
        assertContentEquals(before, w.corners)
        assertEquals(4, w.heightAt(12, 12))
    }

    @Test
    fun aMeadowTileTakesOneTree() {
        val w = flat()
        val money = w.companies[0].money
        val drawn = w.rng.state
        w.apply(Command.PlantTrees(0, 10, 10, 10, 10))
        assertEquals(money - w.price(Prices.TREE), w.companies[0].money)
        assertTrue(Trees.count(w.trees[w.index(10, 10)].toInt()) > 0)
        assertNotEquals(drawn, w.rng.state)
        assertEquals(w.price(Prices.TREE) to 1, w.plantCost(Command.PlantTrees(0, 10, 10, 10, 10)))
    }

    @Test
    fun waterIsSkipped() {
        val w = flat()
        setTile(w, 4, 4, 0)
        val money = w.companies[0].money
        val drawn = w.rng.state
        val c = Command.PlantTrees(0, 4, 4, 4, 4)
        assertEquals(0L to 0, w.plantCost(c))
        w.apply(c)
        assertEquals(money, w.companies[0].money)
        assertEquals(drawn, w.rng.state)
        assertEquals(0, w.trees[w.index(4, 4)].toInt())
    }

    @Test
    fun shortOfTheMoneyNoTreesArePlanted() {
        val w = flat()
        w.companies[0].money = w.price(Prices.TREE)
        val drawn = w.rng.state
        val before = w.trees.copyOf()
        val c = Command.PlantTrees(0, 30, 30, 31, 30)
        assertEquals(2 * w.price(Prices.TREE) to 2, w.plantCost(c))
        w.apply(c)
        assertEquals(w.price(Prices.TREE), w.companies[0].money)
        assertEquals(drawn, w.rng.state)
        assertContentEquals(before, w.trees)
    }

    @Test
    fun twoWorldsOfOneSeedPlantTheSameTrees() {
        fun planted() = flat().also { it.apply(Command.PlantTrees(0, 12, 12, 16, 14)) }
        val a = planted()
        val b = planted()
        assertContentEquals(a.trees, b.trees)
        assertTrue(Trees.count(a.trees[a.index(12, 12)].toInt()) > 0)
    }

    @Test
    fun theEditorStillLevelsAndPlantsForNothing() {
        val w = World.newEditor(NewGame(64, 64, 1, 1900, towns = 0, industries = 0, inflation = false, weather = false))
        for (i in w.corners.indices) w.corners[i] = 3
        w.trees.fill(0)
        w.ground.fill(Ground.MEADOW.toByte())
        val money = w.companies[0].money
        w.apply(Command.EditTerrain(0, 10, 10, 10, 10, Command.EditTerrain.LEVEL, 6))
        assertEquals(6, w.heightAt(10, 10))
        w.apply(Command.EditTrees(0, 10, 10, 10, 10, plant = true))
        assertTrue(Trees.count(w.trees[w.index(10, 10)].toInt()) > 0)
        assertEquals(money, w.companies[0].money)
    }

    @Test
    fun levelAndPlantCommandsRoundTrip() {
        val level = Command.Level(1, 2, 3, 4, 5, 6)
        val plant = Command.PlantTrees(2, 7, 8, 9, 10)
        assertEquals(level, Codec.decode(Codec.encode(level)))
        assertEquals(plant, Codec.decode(Codec.encode(plant)))
    }
}
