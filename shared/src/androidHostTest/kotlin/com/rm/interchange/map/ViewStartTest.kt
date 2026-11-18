package com.rm.interchange.map

import com.rm.interchange.sim.World
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewStartTest {
    private fun flat(w: Int, h: Int, height: Int) = World(w, h, 1, startYear = 1965).also { for (i in it.corners.indices) it.corners[i] = height.toByte() }

    /** Whether every corner of a screen [w] by [h] shows ground on [world]'s land at [height]. */
    private fun onMap(v: View, w: Float, h: Float, height: Float): Boolean {
        for (px in listOf(0f, w)) for (py in listOf(0f, h)) {
            val a = (px - w / 2) / v.halfWidth + (v.focusViewX() - v.focusViewY())
            val b = (py - h / 2 + height * v.step) / v.halfHeight + (v.focusViewX() + v.focusViewY())
            val vx = (a + b) / 2
            val vy = (b - a) / 2
            if (vx < -0.01f || vy < -0.01f || vx > v.viewWidth + 0.01f || vy > v.viewHeight + 0.01f) return false
        }
        return true
    }

    @Test
    fun aTownAtTheEdgeStillFillsTheScreen() {
        val world = flat(256, 512, 0)
        for ((x, y) in listOf(2f to 500f, 250f to 3f, 128f to 256f, 0.5f to 0.5f)) {
            val v = View(world.width, world.height)
            v.startAt(world, x, y, 1080f, 2340f)
            assertTrue(onMap(v, 1080f, 2340f, 0f), "at $x, $y")
        }
        // In the middle, it stays where it was asked.
        val v = View(world.width, world.height)
        v.startAt(world, 128f, 256f, 1080f, 2340f)
        assertEquals(128f, v.focusX, 0.01f)
        assertEquals(256f, v.focusY, 0.01f)
    }

    @Test
    fun highGroundIsInTheMiddle() {
        val world = flat(256, 256, 12)
        val v = View(world.width, world.height)
        v.startAt(world, 128.5f, 128.5f, 1080f, 2340f)
        val sy = v.screenY(v.toViewX(128.5f, 128.5f), v.toViewY(128.5f, 128.5f), groundHeight(world, 128.5f, 128.5f), 2340f)
        assertTrue(abs(sy - 1170f) < 0.5f, "drawn at $sy")
        assertTrue(onMap(v, 1080f, 2340f, groundHeight(world, 128.5f, 128.5f)))
    }

    @Test
    fun highGroundAtTheEdgeStillFillsTheScreen() {
        val world = flat(256, 512, 12)
        for ((x, y) in listOf(2f to 500f, 250f to 3f, 0.5f to 0.5f, 255.5f to 511.5f, 0.5f to 511.5f, 255.5f to 0.5f)) {
            val v = View(world.width, world.height)
            v.startAt(world, x, y, 1080f, 2340f)
            assertTrue(onMap(v, 1080f, 2340f, 12f), "at $x, $y")
        }
    }

    @Test
    fun aMapSmallerThanTheScreenIsCentred() {
        val world = flat(16, 16, 0)
        val v = View(world.width, world.height)
        v.startAt(world, 1f, 1f, 1080f, 2340f)
        assertEquals(8f, v.focusX, 0.01f)
        assertEquals(8f, v.focusY, 0.01f)
    }
}
