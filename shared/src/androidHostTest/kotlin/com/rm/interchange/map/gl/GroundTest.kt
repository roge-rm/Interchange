package com.rm.interchange.map.gl

import com.rm.interchange.map.Atlas
import com.rm.interchange.map.View
import com.rm.interchange.map.WeatherCopy
import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.Climate
import com.rm.interchange.sim.NewGame
import com.rm.interchange.sim.Region
import com.rm.interchange.sim.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroundTest {
    private companion object {
        val world by lazy { World(NewGame(64, 32, 7, 1930, Region.MIXED, towns = 2, industries = 2, rivals = 0)) }
        val weather by lazy { WeatherCopy.of(world, 0) }
        val year by lazy { Calendar.year(world.startYear, world.day) }
        val look by lazy { Climate.look(world.settings.region, year, Calendar.dayOfYear(world.startYear, world.day)) }

        fun regions(turn: Int) = Array(regionCount(world, turn)) { recordRegion(world, turn, it, look, year, weather) }

        // GROUND_VS's worldCorner and worldTile, written out again.
        fun shaderCorner(turn: Int, x: Int, y: Int) = when (turn) {
            0 -> x to y
            1 -> y to world.height - x
            2 -> world.width - x to world.height - y
            else -> world.width - y to x
        }

        fun shaderTile(turn: Int, x: Int, y: Int) = when (turn) {
            0 -> x to y
            1 -> y to world.height - 1 - x
            2 -> world.width - 1 - x to world.height - 1 - y
            else -> world.width - 1 - y to x
        }
    }

    @Test
    fun groundRecordsComeBackAsPacked() {
        val g = GroundRecord.ground(
            301, 77, 3, 4, 24, 0, Atlas.FOREST, intArrayOf(-1, Atlas.SNOW, Atlas.ROCK, -1), weather = true, smooth = false, foot = true,
            banks = 0b1010, road = 0x1e1f, charred = 0.7f, aero = 0x7abcdef1, f1 = 256, f2 = 333,
            reliefs = floatArrayOf(-1.5f, 0f, 2.25f, 0.125f), lights = floatArrayOf(256f, 200f, 310.5f, 148f),
        )
        assertEquals(GroundRecord.STRIDE, g.size)
        assertEquals(301, GroundRecord.vx(g))
        assertEquals(77, GroundRecord.vy(g))
        assertEquals(listOf(3, 4, 24, 0), (0 until 4).map { GroundRecord.height(g, it) })
        assertEquals(Atlas.FOREST, GroundRecord.kind(g))
        assertEquals(listOf(-1, Atlas.SNOW, Atlas.ROCK, -1), (0 until 4).map { GroundRecord.spread(g, it) })
        assertEquals(GroundRecord.TILE, GroundRecord.type(g))
        assertTrue(GroundRecord.weather(g))
        assertTrue(!GroundRecord.smooth(g))
        assertTrue(GroundRecord.foot(g))
        assertEquals(0b1010, GroundRecord.banks(g))
        assertEquals(0x1e1f, GroundRecord.road(g))
        assertEquals(0.7f, GroundRecord.charred(g), 0.51f / 255)
        assertEquals(0x7abcdef1, GroundRecord.aero(g))
        assertEquals(256, GroundRecord.f1(g))
        assertEquals(333, GroundRecord.f2(g))
        assertEquals(listOf(-1.5f, 0f, 2.25f, 0.125f), (0 until 4).map { GroundRecord.relief(g, it) })
        assertEquals(listOf(256f, 200f, 310.5f, 148f), (0 until 4).map { GroundRecord.light(g, it) })
        val f = GroundRecord.face(5, 6, GroundRecord.FACE_LEFT, 7, 8, 9)
        assertEquals(GroundRecord.FACE_LEFT, GroundRecord.type(f))
        assertEquals(listOf(0, 7, 8, 9), (0 until 4).map { GroundRecord.height(f, it) })
    }

    @Test
    fun everyTileLaysItsGroundFirstAndTheEdgesTheirFaces() {
        for (turn in 0 until 4) {
            val view = View(world.width, world.height).also { it.turnBy(turn) }
            var faces = 0
            for (r in regions(turn)) for (vy in r.row0 until r.row1) for (vx in 0 until r.columns) {
                val a = r.start(0, vy, vx)
                val b = r.start(0, vy, vx + 1)
                assertTrue(b > a, "turn $turn tile $vx,$vy has nothing")
                assertEquals(SpriteRecorder.GROUND, r.data[a * SpriteRecorder.STRIDE])
                val g = r.ground
                val first = r.data[a * SpriteRecorder.STRIDE + 1] * GroundRecord.STRIDE
                assertEquals(GroundRecord.TILE, GroundRecord.type(g, first))
                assertEquals(vx, GroundRecord.vx(g, first))
                assertEquals(vy, GroundRecord.vy(g, first))
                for (s in a until b) {
                    val o = s * SpriteRecorder.STRIDE
                    if (r.data[o] != SpriteRecorder.GROUND) continue
                    val go = r.data[o + 1] * GroundRecord.STRIDE
                    if (GroundRecord.type(g, go) == GroundRecord.FACE_RIGHT) { assertEquals(view.viewWidth - 1, vx); faces++ }
                    if (GroundRecord.type(g, go) == GroundRecord.FACE_LEFT) { assertEquals(view.viewHeight - 1, vy); faces++ }
                }
            }
            assertEquals(view.viewWidth + view.viewHeight, faces, "turn $turn faces")
        }
    }

    @Test
    fun groundHeightsAreTheWorldsCorners() {
        for (turn in 0 until 4) {
            val view = View(world.width, world.height).also { it.turnBy(turn) }
            for (r in regions(turn)) for (vy in r.row0 until r.row1) for (vx in 0 until r.columns) {
                val g = r.ground
                val first = r.data[r.start(0, vy, vx) * SpriteRecorder.STRIDE + 1] * GroundRecord.STRIDE
                val corners = listOf(vx to vy, vx + 1 to vy, vx + 1 to vy + 1, vx to vy + 1)
                for ((c, p) in corners.withIndex()) {
                    val want = world.heightAt(view.cornerX(p.first, p.second), view.cornerY(p.first, p.second))
                    assertEquals(want, GroundRecord.height(g, c, first), "turn $turn tile $vx,$vy corner $c")
                }
            }
        }
    }

    @Test
    fun shaderTurnsMatchTheView() {
        for (turn in 0 until 4) {
            val view = View(world.width, world.height).also { it.turnBy(turn) }
            for (vy in 0..view.viewHeight) for (vx in 0..view.viewWidth) {
                assertEquals(view.cornerX(vx, vy) to view.cornerY(vx, vy), shaderCorner(turn, vx, vy), "turn $turn corner $vx,$vy")
                if (vx < view.viewWidth && vy < view.viewHeight) {
                    assertEquals(view.worldX(vx, vy) to view.worldY(vx, vy), shaderTile(turn, vx, vy), "turn $turn tile $vx,$vy")
                }
            }
        }
    }

    @Test
    fun weatherCornersAreTheBakersInEveryTurn() {
        // Snow and ice on some tiles, by a pattern, with water and shore among them.
        val n = world.width * world.height
        val snow = ByteArray(n) { (it * 37 % 90).toByte() }
        val ice = ByteArray(n) { (it * 53 % 70).toByte() }
        val mud = ByteArray(n) { (it * 11 % 256).toByte() }
        val copy = WeatherCopy(1, snow, ice, mud)
        val gw = GroundWeather.of(world, copy, null)
        val w = world.width + 1
        var water = 0
        for (turn in 0 until 4) {
            val view = View(world.width, world.height).also { it.turnBy(turn) }
            for (vy in 0..view.viewHeight) for (vx in 0..view.viewWidth) {
                // A corner's snow and ice: the mean of the four tiles round it, the shore as thick as the thickest ice, in view tiles.
                var snowSum = 0
                var iceSum = 0
                var most = 0
                var shore = 0
                for (dy in -1..0) for (dx in -1..0) {
                    val x = (vx + dx).coerceIn(0, view.viewWidth - 1)
                    val y = (vy + dy).coerceIn(0, view.viewHeight - 1)
                    val wx = view.worldX(x, y)
                    val wy = view.worldY(x, y)
                    val i = world.index(wx, wy)
                    snowSum += snow[i].toInt() and 255
                    if (world.isWater(wx, wy) || world.canals.contains(i)) {
                        iceSum += ice[i].toInt() and 255
                        most = maxOf(most, ice[i].toInt() and 255)
                        water++
                    } else shore++
                }
                val (cx, cy) = shaderCorner(turn, vx, vy)
                val o = (cy * w + cx) * GroundWeather.TEXEL
                assertEquals(minOf(255, snowSum), gw.texels[o].toInt() and 255, "turn $turn corner $vx,$vy snow")
                assertEquals(minOf(255, iceSum + shore * most), gw.texels[o + 1].toInt() and 255, "turn $turn corner $vx,$vy ice")
                if (vx < view.viewWidth && vy < view.viewHeight) {
                    val (tx, ty) = shaderTile(turn, vx, vy)
                    assertEquals(mud[world.index(view.worldX(vx, vy), view.worldY(vx, vy))].toInt() and 255, gw.texels[(ty * w + tx) * GroundWeather.TEXEL + 2].toInt() and 255)
                }
            }
        }
        assertTrue(water > 0, "no water on the map to check ice by")
    }

    @Test
    fun onlyTheRowsWhoseWeatherChangedMoveOn() {
        val n = world.width * world.height
        val first = GroundWeather.of(world, WeatherCopy(1, ByteArray(n), ByteArray(n), ByteArray(n)), null)
        assertEquals(0, first.version)
        val snow = ByteArray(n)
        snow[world.index(10, 12)] = 20
        val second = GroundWeather.of(world, WeatherCopy(2, snow, ByteArray(n), ByteArray(n)), first)
        assertEquals(1, second.version)
        // Tile 10, 12 touches corner rows 12 and 13.
        for (cy in 0..world.height) assertEquals(if (cy == 12 || cy == 13) 1 else 0, second.rowVersions[cy], "row $cy")
        val third = GroundWeather.of(world, WeatherCopy(3, snow, ByteArray(n), ByteArray(n)), second)
        for (cy in 0..world.height) assertEquals(second.rowVersions[cy], third.rowVersions[cy], "row $cy unchanged")
    }

    @Test
    fun regionsPackAsTheShaderReadsThem() {
        for (r in regions(1)) {
            val p0 = r.firstPass
            val ints = packRegion(r)
            assertEquals(p0 * GroundRecord.STRIDE + (r.count - p0) * SPRITE_INTS, ints.size)
            for (i in 0 until p0) {
                val o = i * SpriteRecorder.STRIDE
                val k = i * GroundRecord.STRIDE
                if (r.data[o] == SpriteRecorder.GROUND) {
                    assertEquals(r.ground.copyOfRange(r.data[o + 1] * GroundRecord.STRIDE, (r.data[o + 1] + 1) * GroundRecord.STRIDE).toList(), ints.copyOfRange(k, k + GroundRecord.STRIDE).toList())
                } else {
                    assertEquals(GroundRecord.SPRITE, GroundRecord.type(ints, k))
                    assertEquals(r.data[o + 8], ints[k])
                    assertEquals(r.data[o + 11], ints[k + 1])
                    assertEquals(r.data[o + 9], ints[k + 4])
                    assertEquals(r.data[o + 10], ints[k + 5])
                }
            }
            for (i in p0 until r.count) {
                val o = i * SpriteRecorder.STRIDE
                val k = p0 * GroundRecord.STRIDE + (i - p0) * SPRITE_INTS
                assertEquals(listOf(r.data[o + 9], r.data[o + 10], r.data[o + 8], r.data[o + 11]), ints.copyOfRange(k, k + 4).toList())
            }
        }
    }

    @Test
    fun aRegionsVersionMovesWithTheWorldUnderIt() {
        for (turn in 0 until 4) {
            val n = regionCount(world, turn)
            val before = LongArray(n) { regionVersion(world, turn, it, look, year) }
            // A tile in the middle of world row 20, at view row 20, 43, 11 or 52 by turn.
            world.touched(30, 20)
            val after = LongArray(n) { regionVersion(world, turn, it, look, year) }
            val view = View(world.width, world.height).also { it.turnBy(turn) }
            val row = view.toViewY(30.5f, 20.5f).toInt()
            assertTrue(after[row / REGION_ROWS] != before[row / REGION_ROWS], "turn $turn region of view row $row didn't move")
            for (i in 0 until n) {
                val near = row in i * REGION_ROWS - REGION_MARGIN - World.BLOCK until (i + 1) * REGION_ROWS + REGION_MARGIN + World.BLOCK
                if (!near) assertEquals(before[i], after[i], "turn $turn region $i far from row $row moved")
            }
            assertTrue(regionVersion(world, turn, 0, (look + 1) % 7, year) != after[0], "a new look records again")
        }
    }
}
