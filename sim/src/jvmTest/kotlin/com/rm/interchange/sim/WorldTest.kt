package com.rm.interchange.sim

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorldTest {
    @Test
    fun sameSeedAndCommandsGiveTheSameWorld() {
        val a = World(64, 64, 42)
        val b = World(64, 64, 42)
        for (w in listOf(a, b)) {
            w.apply(Command.Terraform(0, 10, 10, true))
            repeat(100) { w.step() }
            w.apply(Command.Terraform(0, 20, 5, false))
        }
        assertEquals(a.checksum(), b.checksum())
    }

    @Test
    fun aDifferentCommandChangesTheChecksum() {
        val a = World(64, 64, 42)
        val b = World(64, 64, 42)
        b.apply(Command.Terraform(0, 30, 30, true))
        assertNotEquals(a.checksum(), b.checksum())
    }

    @Test
    fun neighbouringCornersAreNeverMoreThanAStepApart() {
        val w = World(128, 128, 3)
        w.companies[0].money = Long.MAX_VALUE / 2
        val start = w.heightAt(64, 64)
        repeat(20) { w.apply(Command.Terraform(0, 64, 64, true)) }
        for (y in 0 until 128) for (x in 0 until 128) {
            val h = w.heightAt(x, y)
            assertTrue(abs(h - w.heightAt(x + 1, y)) <= 1)
            assertTrue(abs(h - w.heightAt(x, y + 1)) <= 1)
            assertTrue(abs(h - w.heightAt(x + 1, y + 1)) <= 1)
        }
        assertEquals(minOf(World.MAX_HEIGHT, start + 20), w.heightAt(64, 64))
    }

    @Test
    fun aDiagonalLineZigzags() {
        val w = World(32, 32, 1)
        val line = Track.line(w, 2, 2, 5, 5, Track.E)!!
        assertEquals(7, line.size)
        val tiles = line.map { (it / 64 % 32) to (it / 64 / 32) }
        assertEquals(listOf(2 to 2, 3 to 2, 3 to 3, 4 to 3, 4 to 4, 5 to 4, 5 to 5), tiles)
        val pieces = line.map { it % 64 }
        assertEquals(listOf(Track.E, Track.W, Track.E, Track.W, Track.E, Track.W, Track.E), pieces)
    }

    @Test
    fun trackGoesOnFlatLandAndCostsMoney() {
        val w = World(32, 32, 1)
        // Flatten a strip so it's sure to fit.
        for (i in w.corners.indices) w.corners[i] = 3
        val before = w.companies[0].money
        w.apply(Command.Rail(0, 1, 4, 10, 4, Track.X, remove = false))
        for (x in 1..10) assertEquals(Track.X, w.trackAt(x, 4))
        assertEquals(before - 10 * Prices.TRACK, w.companies[0].money)
        w.apply(Command.Rail(0, 1, 4, 10, 4, Track.X, remove = true))
        assertEquals(0, w.trackAt(5, 4))
    }

    @Test
    fun landUnderTrackCantBeMoved() {
        val w = World(32, 32, 1)
        for (i in w.corners.indices) w.corners[i] = 3
        w.apply(Command.Rail(0, 4, 4, 4, 4, Track.X, remove = false))
        w.apply(Command.Terraform(0, 4, 4, true))
        assertEquals(3, w.heightAt(4, 4))
    }

    @Test
    fun aDiagonalOnASlopeGetsAFoundation() {
        val w = World(32, 32, 1)
        for (i in w.corners.indices) w.corners[i] = 3
        // Tile 5, 5 sloping up along x.
        w.corners[w.corner(6, 5)] = 4
        w.corners[w.corner(6, 6)] = 4
        val before = w.companies[0].money
        w.apply(Command.Rail(0, 5, 5, 5, 5, Track.X, remove = false))
        assertEquals(false, w.hasFoundation(5, 5))
        w.apply(Command.Rail(0, 5, 5, 5, 5, Track.N, remove = false))
        assertEquals(true, w.hasFoundation(5, 5))
        assertEquals(before - 2 * Prices.TRACK - Prices.FOUNDATION, w.companies[0].money)
    }
}
