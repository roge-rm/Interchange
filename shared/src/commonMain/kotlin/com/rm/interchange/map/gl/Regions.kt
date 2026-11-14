package com.rm.interchange.map.gl

import com.rm.interchange.map.Atlas
import com.rm.interchange.map.TileRecorder
import com.rm.interchange.map.CurveArt
import com.rm.interchange.map.Zoom
import com.rm.interchange.map.View
import com.rm.interchange.map.WeatherCopy
import com.rm.interchange.sim.CurveTiles
import com.rm.interchange.sim.World

/**
 * The sprites and shadows [TileRecorder] lays down, recorded in its order instead of painted. Positions are in the
 * pixels of [SOURCE_LEVEL] from view point 0, 0 at height 0; sheet places are in that level's sheets.
 */
internal class SpriteRecorder(private val shiftX: Int = 0, private val shiftY: Int = 0) {
    var count = 0
        private set
    /**
     * [STRIDE] ints a sprite: sheet, left, top, width, height, sheet x, sheet y, snow cover (0 to 256) at this level,
     * then the cell, its anchor from view point 0, 0 at height 0 in level 0 pixels rounded down (x, y) and the world
     * tile whose snow it shows (x | y << 16), -1 for none. The GPU draws from the last four at any level ([cellRects]).
     */
    var data = IntArray(STRIDE * 1024)
        private set

    fun add(sheet: Int, left: Int, top: Int, w: Int, h: Int, sx: Int, sy: Int, snow: Int, cell: Int, ax: Int, ay: Int, snowTile: Int) {
        // Kept if it shows at any level, level 0 being the biggest, so every level draws the same list.
        if ((w <= 0 || h <= 0) && blank(sheet, cell)) return
        if ((count + 1) * STRIDE > data.size) data = data.copyOf(data.size * 2)
        val o = count * STRIDE
        data[o] = sheet
        data[o + 1] = left - shiftX
        data[o + 2] = top - shiftY
        data[o + 3] = w
        data[o + 4] = h
        data[o + 5] = sx
        data[o + 6] = sy
        data[o + 7] = snow
        data[o + 8] = cell
        data[o + 9] = ax
        data[o + 10] = ay
        data[o + 11] = snowTile
        count++
    }

    /** The pieces of ground, [GroundRecord.STRIDE] ints each, laid down in the first pass among its sprites. */
    var groundCount = 0
        private set
    var ground = IntArray(GroundRecord.STRIDE * 256)
        private set

    /** A piece of ground from [GroundRecord], as a sprite of sheet [GROUND] whose second field is its place in [ground]. */
    fun addGround(g: IntArray) {
        if ((count + 1) * STRIDE > data.size) data = data.copyOf(data.size * 2)
        if ((groundCount + 1) * GroundRecord.STRIDE > ground.size) ground = ground.copyOf(ground.size * 2)
        g.copyInto(ground, groundCount * GroundRecord.STRIDE)
        val o = count * STRIDE
        data.fill(0, o, o + STRIDE)
        data[o] = GROUND
        data[o + 1] = groundCount
        groundCount++
        count++
    }

    companion object {
        const val STRIDE = 12

        /** Whether [cell] of [sheet] is empty at level 0, and so at every level. */
        fun blank(sheet: Int, cell: Int): Boolean {
            val (w, h) = if (sheet == SHADOW) Atlas.SHADOW_RECTS[cell * 6 + 2] to Atlas.SHADOW_RECTS[cell * 6 + 3]
            else Atlas.STATIC_LEVEL_RECTS[cell * Atlas.LEVELS * 6 + 2] to Atlas.STATIC_LEVEL_RECTS[cell * Atlas.LEVELS * 6 + 3]
            return w <= 0 || h <= 0
        }
        const val STATIC = 0
        const val SHADOW = 1
        const val GROUND = 2
    }
}

/** The tile recorder's three passes, drawn in this order: ground (with foundations and track), shadows, what stands up. */
internal const val PASSES = 3

/** The zoom level regions are recorded in: positions are in its pixels, L1, a half tile width of 64. Each level draws from its own sheets. */
internal const val SOURCE_LEVEL = 1
internal val SOURCE_HALF_WIDTH = Zoom.LEVELS[SOURCE_LEVEL]

/** View rows in a region. A region is the unit that's recorded, kept in a GPU buffer and culled. */
internal const val REGION_ROWS = 16

/**
 * One region's static sprites for view direction [turn]: view rows [row0] until [row1], every column, in drawing
 * order (pass, row, column), as [TileRecorder] lays them down. [starts] holds the first sprite of each tile, indexed
 * (pass * rows + row - row0) * columns + column, with [count] at the end, so a run of tiles along a row is a run of
 * sprites and the end of one tile is the start of the next index.
 */
internal class RegionSprites(
    val turn: Int,
    val index: Int,
    val row0: Int,
    val row1: Int,
    val columns: Int,
    val data: IntArray,
    val count: Int,
    val starts: IntArray,
    /** The ground the first pass's [SpriteRecorder.GROUND] sprites point into. */
    val ground: IntArray = IntArray(0),
    /** The world's [World.chunkVersions] summed under the region and round it, [regionVersion], when it was recorded. */
    val version: Long = 0,
    /** For a region recorded only in part, each row's first and last column recorded, two ints a row; null when whole. */
    val cols: IntArray? = null,
) {
    /** Sprites in the first pass, which come first. */
    val firstPass get() = starts[rows * columns]

    val rows get() = row1 - row0

    fun start(pass: Int, row: Int, column: Int) = starts[(pass * rows + row - row0) * columns + column]

    /** Whether row [row]'s columns [c0] to [c1] are recorded. */
    fun holds(row: Int, c0: Int, c1: Int): Boolean {
        val c = cols ?: return true
        val k = 2 * (row - row0)
        return c0 > c1 || (c[k] <= c0 && c1 <= c[k + 1])
    }
}

/**
 * Records region [index] of [world] seen from [turn]: what [TileRecorder] lays down there at [level], with
 * the season [look], [year] and [weather]. Reads the world off the main thread.
 * With [cols] (each row's first and last column, two ints a row) only those tiles are recorded; the rest are empty.
 */
internal fun recordRegion(
    world: World, turn: Int, index: Int, look: Int, year: Int, weather: WeatherCopy, curves: CurveArt? = null, version: Long = 0,
    level: Int = SOURCE_LEVEL, cols: IntArray? = null,
): RegionSprites {
    val view = View(world.width, world.height).apply { turnBy(turn) }
    val vw = view.viewWidth
    val row0 = index * REGION_ROWS
    val row1 = minOf(view.viewHeight, row0 + REGION_ROWS)
    val tiles = TileRecorder(world, view, level, look, weather, year, curves)
    // Laid out from a far off origin so every position is positive and rounds down the same way, then moved back.
    val hw = Zoom.LEVELS[level]
    val shiftX = ((world.width + world.height + 8) * hw).toInt()
    val shiftY = ((World.MAX_HEIGHT + 64) * hw).toInt()
    tiles.originX = shiftX.toFloat()
    tiles.originY = shiftY.toFloat()
    val recorder = SpriteRecorder(shiftX, shiftY)
    tiles.recorder = recorder
    val rows = row1 - row0
    val starts = IntArray(PASSES * rows * vw + 1)
    if (rows > 0) tiles.prepare(0, row0, vw, row1)
    var k = 0
    for (pass in 0 until PASSES) for (vy in row0 until row1) for (vx in 0 until vw) {
        starts[k++] = recorder.count
        if (cols != null && (vx < cols[2 * (vy - row0)] || vx > cols[2 * (vy - row0) + 1])) continue
        when (pass) {
            0 -> tiles.ground(vx, vy)
            1 -> tiles.shadows(vx, vy)
            else -> tiles.objects(vx, vy)
        }
    }
    starts[k] = recorder.count
    return RegionSprites(turn, index, row0, row1, vw, recorder.data, recorder.count, starts, recorder.ground, version, cols)
}

/** How many regions a map seen from [turn] has. */
internal fun regionCount(world: World, turn: Int): Int {
    val vh = if (turn % 2 == 0) world.height else world.width
    return (vh + REGION_ROWS - 1) / REGION_ROWS
}

/** World tiles round a region that what's drawn in it reads: the curved track's reach, and ridges and hollows. */
internal val REGION_MARGIN = maxOf(CurveTiles.REACH, 5)

/**
 * How many times the world under region [index] seen from [turn] and within [REGION_MARGIN] tiles of it has changed,
 * from [World.chunkVersions], with the season [look] and the eras of [year] mixed in, so a region is recorded again
 * when any of them moves on.
 */
internal fun regionVersion(world: World, turn: Int, index: Int, look: Int, year: Int): Long {
    val vh = if (turn % 2 == 0) world.height else world.width
    val row0 = index * REGION_ROWS
    val row1 = minOf(vh, row0 + REGION_ROWS) - 1
    // View rows run along world y for turns 0 and 2, along world x for 1 and 3, the far way round for 2 and 3.
    val (a, b) = when (turn) {
        0, 1 -> row0 to row1
        2 -> world.height - 1 - row1 to world.height - 1 - row0
        else -> world.width - 1 - row1 to world.width - 1 - row0
    }
    val across = world.width / World.BLOCK
    val down = world.height / World.BLOCK
    val r0 = maxOf(0, a - REGION_MARGIN) / World.BLOCK
    val along = if (turn % 2 == 0) down else across
    val r1 = minOf(along - 1, minOf((if (turn % 2 == 0) world.height else world.width) - 1, b + REGION_MARGIN) / World.BLOCK)
    var sum = 0L
    for (k in r0..r1) {
        if (turn % 2 == 0) for (x in 0 until across) sum += world.chunkVersions[k * across + x]
        else for (y in 0 until down) sum += world.chunkVersions[y * across + k]
    }
    return (sum * 8 + look) * TileRecorder.PART_ERA_COUNT + TileRecorder.partEras(year)
}
