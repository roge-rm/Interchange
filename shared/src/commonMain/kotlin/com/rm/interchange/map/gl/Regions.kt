package com.rm.interchange.map.gl

import com.rm.interchange.map.Atlas
import com.rm.interchange.map.StaticCells
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
            else StaticCells.levelRects[cell * Atlas.LEVELS * 6 + 2] to StaticCells.levelRects[cell * Atlas.LEVELS * 6 + 3]
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
 * sprites and the end of one tile is the start of the next index. For a region recorded in part it holds only each
 * row's recorded columns and the one after, as [start] reads them, so it grows with what's recorded, not the map.
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
    /** The [com.rm.interchange.sim.Climate] look and the year it was recorded with, -1 if not known. */
    val look: Int = -1,
    val year: Int = -1,
    /**
     * The sprites as the GPU buffer takes them ([packRegion]), kept in place of [data] and [ground], which are then
     * empty, as they take about half as much; null when [data] and [ground] are kept.
     */
    val packed: IntArray? = null,
    /**
     * Stands for what's recorded per tile: shared by a region and those made from it by [extendRegion], which add
     * tiles but leave the rest as they are, so what was drawn from one holds for the others.
     */
    val content: Any = Any(),
    /** The [com.rm.interchange.map.WeatherCopy.baked] of the copy it was recorded with, -1 if not known. */
    val weather: Int = -1,
) {
    /** Ints this region holds on the heap. */
    val heapInts get() = data.size + ground.size + (packed?.size ?: 0) + starts.size

    /** Sprites in the first pass, which come first. */
    val firstPass get() = start(1, row0, 0)

    val rows get() = row1 - row0

    /** For a region recorded in part, where each pass and row's entries start in [starts]; see [partRows]. */
    private val rowAt: IntArray? = cols?.let { partRows(it, rows) }

    fun start(pass: Int, row: Int, column: Int): Int {
        val at = rowAt ?: return starts[(pass * rows + row - row0) * columns + column]
        val k = row - row0
        // Pass PASSES, row0 is the end; the tiles past a row's recorded ones are empty, so they start where it ends.
        if (pass == PASSES) return starts[at[PASSES * rows]]
        val c = cols!!
        val c0 = c[2 * k]
        val c1 = c[2 * k + 1]
        val base = at[pass * rows + k]
        return if (c0 > c1) starts[base] else starts[base + column.coerceIn(c0, c1 + 1) - c0]
    }

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
 * Only what the GPU takes is kept ([RegionSprites.packed]), unless [keepRecord], which keeps every field recorded.
 * Tiles [skip] holds are left empty too, for [extendRegion].
 */
internal fun recordRegion(
    world: World, turn: Int, index: Int, look: Int, year: Int, weather: WeatherCopy, curves: CurveArt? = null, version: Long = 0,
    level: Int = SOURCE_LEVEL, cols: IntArray? = null, keepRecord: Boolean = false, skip: RegionSprites? = null,
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
    val rowAt = cols?.let { partRows(it, rows) }
    val starts = IntArray(if (rowAt != null) rowAt[PASSES * rows] + 1 else PASSES * rows * vw + 1)
    // Only the columns recorded need their corners worked out.
    var vx0 = vw
    var vx1 = -1
    for (vy in row0 until row1) {
        val c0 = if (cols != null) cols[2 * (vy - row0)] else 0
        val c1 = if (cols != null) cols[2 * (vy - row0) + 1] else vw - 1
        for (vx in c0..c1) if (skip == null || !skip.holds(vy, vx, vx)) { vx0 = minOf(vx0, vx); break }
        for (vx in c1 downTo c0) if (skip == null || !skip.holds(vy, vx, vx)) { vx1 = maxOf(vx1, vx); break }
    }
    if (rows > 0 && vx0 <= vx1) tiles.prepare(vx0, row0, vx1 + 1, row1)
    var k = 0
    for (pass in 0 until PASSES) for (vy in row0 until row1) {
        // Whole, every column; in part, the recorded ones and the one after, or just one for an empty row.
        val c0 = if (cols != null) cols[2 * (vy - row0)] else 0
        val c1 = if (cols != null) cols[2 * (vy - row0) + 1] else vw - 1
        if (cols != null && c0 > c1) {
            starts[k++] = recorder.count
            continue
        }
        for (vx in c0..c1) {
            starts[k++] = recorder.count
            if (skip != null && skip.holds(vy, vx, vx)) continue
            when (pass) {
                0 -> tiles.ground(vx, vy)
                1 -> tiles.shadows(vx, vy)
                else -> tiles.objects(vx, vy)
            }
        }
        if (cols != null) starts[k++] = recorder.count
    }
    starts[k] = recorder.count
    if (!keepRecord) {
        val r = RegionSprites(turn, index, row0, row1, vw, recorder.data, recorder.count, starts, recorder.ground, version, cols, look, year)
        return RegionSprites(turn, index, row0, row1, vw, IntArray(0), recorder.count, starts, IntArray(0), version, cols, look, year, packRegion(r), weather = weather.baked)
    }
    // Cut to what's used, as a few turns of regions are kept.
    val data = recorder.data.copyOf(recorder.count * SpriteRecorder.STRIDE)
    val ground = recorder.ground.copyOf(recorder.groundCount * GroundRecord.STRIDE)
    return RegionSprites(turn, index, row0, row1, vw, data, recorder.count, starts, ground, version, cols, look, year, weather = weather.baked)
}

/**
 * Region [base] holding [cols] (each row's first and last column, two ints a row; null for every column): the tiles
 * it holds are taken as they are and only the rest recorded, and those outside [cols] are let go of. The world must
 * be at [base]'s version, look and year and [weather] have its snow and ice ([RegionSprites.weather]), as the result
 * shares its [RegionSprites.content].
 */
internal fun extendRegion(world: World, base: RegionSprites, cols: IntArray?, weather: WeatherCopy, curves: CurveArt? = null): RegionSprites {
    val piece = recordRegion(world, base.turn, base.index, base.look, base.year, weather, curves, base.version, cols = cols, skip = base)
    return mergeRegion(base, piece)
}

/** [piece]'s rows and columns, each tile from [base] where it holds it, else from [piece]; packed, with [base]'s content. */
internal fun mergeRegion(base: RegionSprites, piece: RegionSprites): RegionSprites {
    val bp = packRegion(base)
    val pp = packRegion(piece)
    val rows = piece.rows
    val cols = piece.cols
    val starts = IntArray(piece.starts.size)
    // Each row is up to three runs: from piece left of what base holds, from base, from piece right of it.
    val runs = IntArray(6)
    fun rowRuns(k: Int): Int {
        val vy = piece.row0 + k
        val c0 = cols?.get(2 * k) ?: 0
        val c1 = cols?.get(2 * k + 1) ?: (piece.columns - 1)
        if (c0 > c1) return 0
        val bc = base.cols
        val h0 = maxOf(c0, if (bc == null) 0 else bc[2 * (vy - base.row0)])
        val h1 = minOf(c1, if (bc == null) base.columns - 1 else bc[2 * (vy - base.row0) + 1])
        if (h0 > h1) {
            runs[0] = c0; runs[1] = c1
            return 1
        }
        runs[0] = c0; runs[1] = h0 - 1; runs[2] = h0; runs[3] = h1; runs[4] = h1 + 1; runs[5] = c1
        return 3
    }
    // From base for the middle of three runs.
    fun src(n: Int, j: Int) = if (n == 3 && j == 1) base else piece
    var first = 0
    var count = 0
    for (pass in 0 until PASSES) for (k in 0 until rows) {
        val vy = piece.row0 + k
        val n = rowRuns(k)
        for (j in 0 until n) {
            if (runs[2 * j] > runs[2 * j + 1]) continue
            val r = src(n, j)
            count += r.start(pass, vy, runs[2 * j + 1] + 1) - r.start(pass, vy, runs[2 * j])
        }
        if (pass == 0) first = count
    }
    // The first pass takes GroundRecord.STRIDE ints a sprite, the rest SPRITE_INTS.
    val out = IntArray(first * GroundRecord.STRIDE + (count - first) * SPRITE_INTS)
    var sprites = 0
    var o = 0
    var at = 0
    for (pass in 0 until PASSES) for (k in 0 until rows) {
        val vy = piece.row0 + k
        val stride = if (pass == 0) GroundRecord.STRIDE else SPRITE_INTS
        val n = rowRuns(k)
        if (n == 0) {
            if (cols != null) starts[at++] = sprites
            continue
        }
        for (j in 0 until n) {
            val a0 = runs[2 * j]
            val a1 = runs[2 * j + 1]
            if (a0 > a1) continue
            val r = src(n, j)
            val packed = if (r === base) bp else pp
            val a = r.start(pass, vy, a0)
            val b = r.start(pass, vy, a1 + 1)
            for (vx in a0..a1) starts[at++] = sprites + r.start(pass, vy, vx) - a
            val f = r.firstPass
            val from = if (pass == 0) a * GroundRecord.STRIDE else f * GroundRecord.STRIDE + (a - f) * SPRITE_INTS
            packed.copyInto(out, o, from, from + (b - a) * stride)
            o += (b - a) * stride
            sprites += b - a
        }
        if (cols != null) starts[at++] = sprites
    }
    starts[at] = sprites
    return RegionSprites(
        base.turn, base.index, piece.row0, piece.row1, piece.columns, IntArray(0), sprites, starts, IntArray(0), base.version, cols,
        base.look, base.year, out, base.content, base.weather,
    )
}

/**
 * Region [index] of [world] seen from [turn] holding [cols], as [recordRegion] takes them: added to [base] by
 * [extendRegion] if given, else recorded afresh.
 */
internal fun recordKept(
    world: World, turn: Int, index: Int, look: Int, year: Int, weather: WeatherCopy, curves: CurveArt?, version: Long,
    cols: IntArray?, base: RegionSprites?,
): RegionSprites =
    if (base != null) extendRegion(world, base, cols, weather, curves)
    else recordRegion(world, turn, index, look, year, weather, curves, version, cols = cols)

/**
 * Where each pass and row of a region recorded in part starts in its starts, for [cols] (each row's first and last
 * column, two ints a row) over [rows] rows, with the end at [PASSES] * [rows]: a row's recorded columns and the one
 * after, or one entry for an empty row.
 */
internal fun partRows(cols: IntArray, rows: Int): IntArray {
    val at = IntArray(PASSES * rows + 1)
    var n = 0
    for (pass in 0 until PASSES) for (k in 0 until rows) {
        at[pass * rows + k] = n
        n += if (cols[2 * k] > cols[2 * k + 1]) 1 else cols[2 * k + 1] - cols[2 * k] + 2
    }
    at[PASSES * rows] = n
    return at
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

/** The part of a [regionVersion] that counts the world's changes, without the look and eras, so it only goes up. */
internal fun regionChanges(version: Long): Long = version / TileRecorder.PART_ERA_COUNT / 8

/**
 * The regions of [world] seen from [turn] whose version ([regionVersion]) moves when world tiles [x0], [y0] to [x1],
 * [y1] (inclusive, either way round) change: those holding them and those within [REGION_MARGIN] of them.
 */
internal fun regionsTouching(world: World, turn: Int, x0: Int, y0: Int, x1: Int, y1: Int): IntRange {
    val xa = minOf(x0, x1)
    val xb = maxOf(x0, x1)
    val ya = minOf(y0, y1)
    val yb = maxOf(y0, y1)
    // View rows run along world y for turns 0 and 2, along world x for 1 and 3, the far way round for 2 and 3.
    val (a, b) = when (turn) {
        0 -> ya to yb
        1 -> xa to xb
        2 -> world.height - 1 - yb to world.height - 1 - ya
        else -> world.width - 1 - xb to world.width - 1 - xa
    }
    // The blocks of World.chunkVersions those rows are in, then each region whose version reads any of them, as
    // regionVersion picks its blocks.
    val along = if (turn % 2 == 0) world.height else world.width
    val flip = turn >= 2
    fun block(row: Int) = (if (flip) along - 1 - row else row) / World.BLOCK
    val k0 = minOf(block(a), block(b))
    val k1 = maxOf(block(a), block(b))
    var first = -1
    var last = -1
    for (i in 0 until regionCount(world, turn)) {
        val row0 = i * REGION_ROWS
        val row1 = minOf(along, row0 + REGION_ROWS) - 1
        val (ra, rb) = if (flip) along - 1 - row1 to along - 1 - row0 else row0 to row1
        val r0 = maxOf(0, ra - REGION_MARGIN) / World.BLOCK
        val r1 = minOf(along - 1, rb + REGION_MARGIN) / World.BLOCK
        if (r1 < k0 || r0 > k1) continue
        if (first < 0) first = i
        last = i
    }
    return if (first < 0) IntRange.EMPTY else first..last
}
