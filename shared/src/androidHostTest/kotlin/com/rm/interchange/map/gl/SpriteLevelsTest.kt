package com.rm.interchange.map.gl

import com.rm.interchange.map.Atlas
import com.rm.interchange.map.WeatherCopy
import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.Climate
import com.rm.interchange.sim.NewGame
import com.rm.interchange.sim.Region
import com.rm.interchange.sim.World
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpriteLevelsTest {
    private companion object {
        val world by lazy { World(NewGame(64, 32, 7, 1930, Region.MIXED, towns = 2, industries = 2, rivals = 0)) }
        val year by lazy { Calendar.year(world.startYear, world.day) }
        val look by lazy { Climate.look(world.settings.region, year, Calendar.dayOfYear(world.startYear, world.day)) }
        /** Snow of every depth the cover steps by, tile by tile. */
        val weather by lazy {
            val n = world.width * world.height
            val rnd = Random(5)
            val depths = intArrayOf(0, 1, 2, 5, 12, 40, 200)
            WeatherCopy(1, ByteArray(n) { depths[rnd.nextInt(depths.size)].toByte() }, ByteArray(n), ByteArray(n))
        }

        /** Pages that hold a whole sheet in one layer, so a row in its page is its sheet row. */
        val whole = AtlasPages(4096, 1 shl 20, 0, Int.MAX_VALUE)
    }

    @Test
    fun cellTablesPlaceSpritesAsTheBakerDoesAtEveryLevel() {
        // A region recorded at source level, placed at each level by the cell tables, against the baker recording at that level.
        var checked = 0
        var off = 0
        for (turn in 0 until 4) for (index in 0 until regionCount(world, turn)) {
            val source = recordRegion(world, turn, index, look, year, weather, keepRecord = true)
            for (level in 0 until Atlas.LEVELS) {
                val baked = if (level == SOURCE_LEVEL) source else recordRegion(world, turn, index, look, year, weather, level = level, keepRecord = true)
                assertEquals(source.count, baked.count, "turn $turn region $index level $level")
                val tables = arrayOf(cellRects(SpriteRecorder.STATIC, level, whole), cellRects(SpriteRecorder.SHADOW, level, whole))
                for (i in 0 until source.count) {
                    val o = i * SpriteRecorder.STRIDE
                    val sheet = source.data[o]
                    assertEquals(sheet, baked.data[o])
                    if (sheet == SpriteRecorder.GROUND) continue
                    // The baker only records sprites with something in them at its level; empty ones draw nothing.
                    val w = baked.data[o + 3]
                    val h = baked.data[o + 4]
                    val rects = tables[sheet]
                    val c = source.data[o + 8] * CELL_INTS
                    val msg = "turn $turn region $index level $level sprite $i"
                    assertEquals(source.data[o + 8], baked.data[o + 8], msg)
                    if (w <= 0 || h <= 0) {
                        assertTrue(rects[c + 3] <= 0 || rects[c + 4] <= 0, msg)
                        continue
                    }
                    assertEquals(listOf(baked.data[o + 5], baked.data[o + 6], w, h), listOf(rects[c], rects[c + 1], rects[c + 3], rects[c + 4]), msg)
                    val (left, top) = placeAt(rects, c, source.data[o + 9], source.data[o + 10], level)
                    // Float sums at another level's scale can land either side of a whole pixel, so a pixel out is allowed for a few.
                    assertTrue(abs(left - baked.data[o + 1]) <= 1 && abs(top - baked.data[o + 2]) <= 1, "$msg: $left,$top vs ${baked.data[o + 1]},${baked.data[o + 2]}")
                    if (left != baked.data[o + 1] || top != baked.data[o + 2]) off++
                    checked++
                }
            }
        }
        assertTrue(checked > 10000, "only $checked sprites checked")
        assertTrue(off * 200 < checked, "$off of $checked a pixel out")
    }

    @Test
    fun spritesShowTheSnowOfTheirTileFromTheWeatherTexture() {
        // The shaders' snowCover on the weather texture's fourth byte, written out again.
        fun cover(d: Int) = when {
            d == 0 -> 0
            d < 3 -> 90
            d < 10 -> 170
            d < 30 -> 230
            else -> 256
        }
        val gw = GroundWeather.of(world, weather, null)
        var snowy = 0
        for (turn in 0 until 4) for (r in Array(regionCount(world, turn)) { recordRegion(world, turn, it, look, year, weather, keepRecord = true) }) {
            for (i in 0 until r.count) {
                val o = i * SpriteRecorder.STRIDE
                val sheet = r.data[o]
                if (sheet == SpriteRecorder.GROUND) continue
                val tile = r.data[o + 11]
                if (sheet == SpriteRecorder.SHADOW) {
                    assertEquals(-1, tile)
                    continue
                }
                val x = tile and 0xffff
                val y = tile ushr 16
                val d = gw.texels[(y * (world.width + 1) + x) * GroundWeather.TEXEL + 3].toInt() and 255
                assertEquals(r.data[o + 7], cover(d), "turn $turn sprite $i tile $x,$y")
                if (d > 0) snowy++
            }
        }
        assertTrue(snowy > 1000, "only $snowy snowy sprites")
    }

    @Test
    fun cellTablesFitTheSmallestTexturesGles3Promises() {
        for (sheet in intArrayOf(SpriteRecorder.STATIC, SpriteRecorder.SHADOW)) {
            assertTrue(CELLS_ACROSS * CELL_INTS / 4 <= 2048)
            assertTrue(cellRows(sheet) * Atlas.LEVELS <= 2048)
            assertTrue(cellRows(sheet) * CELLS_ACROSS >= cellCount(sheet))
        }
    }
}
