package com.rm.interchange.map.gl

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import com.rm.interchange.map.ChunkBaker
import com.rm.interchange.map.CurveArt
import com.rm.interchange.map.LevelSheets
import com.rm.interchange.map.Renderer
import com.rm.interchange.map.View
import com.rm.interchange.map.WeatherCopy
import com.rm.interchange.sim.World

/**
 * The sprites and shadows [ChunkBaker] lays down, recorded in its order instead of painted. Positions are in the
 * pixels of [SOURCE_LEVEL] from view point 0, 0 at height 0; sheet places are in that level's sheets.
 */
internal class SpriteRecorder(private val shiftX: Int = 0, private val shiftY: Int = 0) {
    var count = 0
        private set
    /** [STRIDE] ints a sprite: sheet, left, top, width, height, sheet x, sheet y, snow (0 to 255). */
    var data = IntArray(STRIDE * 1024)
        private set

    fun add(sheet: Int, left: Int, top: Int, w: Int, h: Int, sx: Int, sy: Int, snow: Int) {
        if (w <= 0 || h <= 0) return
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
        count++
    }

    companion object {
        const val STRIDE = 8
        const val STATIC = 0
        const val SHADOW = 1
    }
}

/** The baker's three passes, drawn in this order: ground (foundations and track), shadows, what stands up. */
internal const val PASSES = 3

/** The zoom level the GPU map's sprites and sheets come from, upload and zoom alike: L1, a half tile width of 64. */
internal const val SOURCE_LEVEL = 1
internal val SOURCE_HALF_WIDTH = Renderer.LEVELS[SOURCE_LEVEL]

/** View rows in a region. A region is the unit that's recorded, kept in a GPU buffer and culled. */
internal const val REGION_ROWS = 16

/**
 * One region's static sprites for view direction [turn]: view rows [row0] until [row1], every column, in drawing
 * order (pass, row, column), as [ChunkBaker] lays them down. [starts] holds the first sprite of each tile, indexed
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
) {
    val rows get() = row1 - row0

    fun start(pass: Int, row: Int, column: Int) = starts[(pass * rows + row - row0) * columns + column]
}

/** Stands in for the signals sheet, which recording never reads, so no platform bitmap is made. */
private object NoImage : ImageBitmap {
    override val width get() = 1
    override val height get() = 1
    override val colorSpace get() = ColorSpaces.Srgb
    override val hasAlpha get() = true
    override val config get() = ImageBitmapConfig.Argb8888
    override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) {}
    override fun prepareToDraw() {}
}

/** Sheets for a baker that only records: it never reads them. */
internal val noSheets = LevelSheets(IntArray(0), IntArray(0), 1, ByteArray(0), 1, NoImage)

/**
 * Records region [index] of [world] seen from [turn]: what [ChunkBaker] would draw there at [SOURCE_LEVEL], with
 * the season [look], [year] and [weather] it'd bake with. Reads the world as a bake does, off the main thread.
 */
internal fun recordRegion(world: World, turn: Int, index: Int, look: Int, year: Int, weather: WeatherCopy, curves: CurveArt? = null): RegionSprites {
    val view = View(world.width, world.height).apply { turnBy(turn) }
    val vw = view.viewWidth
    val row0 = index * REGION_ROWS
    val row1 = minOf(view.viewHeight, row0 + REGION_ROWS)
    val baker = ChunkBaker(world, view, SOURCE_LEVEL, noSheets, 1, 1, 0, look, IntArray(1), weather, year, curves)
    // Laid out from a far off origin so every position is positive and rounds down as it does in a chunk, then moved back.
    val shiftX = ((world.width + world.height + 8) * SOURCE_HALF_WIDTH).toInt()
    val shiftY = ((World.MAX_HEIGHT + 64) * SOURCE_HALF_WIDTH).toInt()
    baker.originX = shiftX.toFloat()
    baker.originY = shiftY.toFloat()
    val recorder = SpriteRecorder(shiftX, shiftY)
    baker.recorder = recorder
    val rows = row1 - row0
    val starts = IntArray(PASSES * rows * vw + 1)
    if (rows > 0) baker.prepare(0, row0, vw, row1)
    var k = 0
    for (pass in 0 until PASSES) for (vy in row0 until row1) for (vx in 0 until vw) {
        starts[k++] = recorder.count
        when (pass) {
            0 -> baker.ground(vx, vy)
            1 -> baker.shadows(vx, vy)
            else -> baker.objects(vx, vy)
        }
    }
    starts[k] = recorder.count
    return RegionSprites(turn, index, row0, row1, vw, recorder.data, recorder.count, starts)
}

/** How many regions a map seen from [turn] has. */
internal fun regionCount(world: World, turn: Int): Int {
    val vh = if (turn % 2 == 0) world.height else world.width
    return (vh + REGION_ROWS - 1) / REGION_ROWS
}
