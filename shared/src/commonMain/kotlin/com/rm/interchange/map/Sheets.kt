package com.rm.interchange.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import com.rm.interchange.res.Res
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * One zoom level's sheets: the ground textures and the static sprites as pixels for baking, the shadows as a byte a
 * pixel of how dark, and the signals as a bitmap for drawing.
 */
class LevelSheets(val ground: IntArray, val static: IntArray, val staticWidth: Int, val shadows: ByteArray, val shadowWidth: Int, val signals: ImageBitmap)

/** A vehicle drawing: its sixteen rects, as [Atlas.VEHICLE_RECTS] has them for one vehicle, and its sheet at each level. */
class VehicleSource(val rects: IntArray, val sheet: (level: Int) -> ByteArray?)

/** The packed sheets for each level, as read from the app, and each base vehicle's, by its place in [Atlas.VEHICLE_KEYS]. */
class PackedSheets(val ground: List<ByteArray>, val static: List<ByteArray>, val staticSnow: List<ByteArray?>, val shadows: List<ByteArray>, val signals: List<ByteArray>, val vehicles: List<List<ByteArray>>)

/**
 * The sheets, sharpest level first. They're kept packed and a level is only
 * unpacked the first time something asks for it, off the main thread; the
 * renderer asks for the levels either side of the one on screen ahead of time.
 *
 * Main thread only: [get] hands back null while a level is still unpacking.
 */
class Sheets(private val packed: PackedSheets, private val scope: CoroutineScope) {
    private val levels = arrayOfNulls<LevelSheets>(Atlas.LEVELS)
    private val unpacking = BooleanArray(Atlas.LEVELS)
    private val decoder = Dispatchers.Default.limitedParallelism(1)

    /** Goes up as each level is unpacked, so whatever waited on it draws again. */
    val revision = mutableIntStateOf(0)

    @OptIn(ExperimentalResourceApi::class)
    fun get(level: Int): LevelSheets? {
        if (level < Renderer.SHARPEST) return null
        levels[level]?.let { return it }
        if (!unpacking[level]) {
            unpacking[level] = true
            scope.launch {
                levels[level] = withContext(decoder) {
                    fun pixels(bytes: ByteArray): Pair<IntArray, Int> {
                        val image = bytes.decodeToImageBitmap()
                        val out = IntArray(image.width * image.height)
                        image.readPixels(out)
                        return out to image.width
                    }
                    val (ground, _) = pixels(packed.ground[level])
                    val (static, width) = pixels(packed.static[level])
                    packed.staticSnow[level]?.let { restoreStaticSnow(static, it) }
                    // Grey, so any one channel is how dark. Read a strip at a time, as the sharpest is some 70 MB as ints.
                    val shade = packed.shadows[level].decodeToImageBitmap()
                    val shadeWidth = shade.width
                    val shadows = ByteArray(shade.width * shade.height)
                    val strip = IntArray(shade.width * SHADE_ROWS)
                    for (y in 0 until shade.height step SHADE_ROWS) {
                        val rows = minOf(SHADE_ROWS, shade.height - y)
                        shade.readPixels(strip, 0, y, shade.width, rows)
                        for (i in 0 until shade.width * rows) shadows[y * shade.width + i] = strip[i].toByte()
                    }
                    LevelSheets(ground, static, width, shadows, shadeWidth, packed.signals[level].decodeToImageBitmap())
                }
                // Every level stays unpacked but the sharpest, some 100 MB, which goes once the screen's well away from it.
                if (level >= 2 && levels[0] != null) {
                    levels[0] = null
                    unpacking[0] = false
                }
                revision.intValue++
            }
        }
        return null
    }

    private var sources: List<VehicleSource?> = emptyList()
    private val vehicles = HashMap<Int, ImageBitmap>()
    private val vehiclesUnpacking = HashSet<Int>()
    /** Goes up with each game's drawings, so a sheet still unpacking for the last game is dropped. */
    private var generation = 0

    /** The base pack's drawing [name], or null if there's none of that name. */
    fun baseVehicle(name: String): VehicleSource? {
        val i = Atlas.VEHICLE_KEYS.indexOf(name)
        if (i < 0) return null
        return VehicleSource(Atlas.VEHICLE_RECTS.copyOfRange(i * 96, i * 96 + 96)) { level -> packed.vehicles[i][level] }
    }

    /** The drawings of a game's vehicles, by their place in its roster's sprites. */
    fun useVehicles(list: List<VehicleSource?>) {
        sources = list
        vehicles.clear()
        vehiclesUnpacking.clear()
        generation++
    }

    /** Vehicle drawing [model]'s rects, or null if it has none. */
    fun vehicleRects(model: Int): IntArray? = sources.getOrNull(model)?.rects

    /** Vehicle drawing [model]'s sheet at [level], unpacked the first time it's asked for; null until then. */
    @OptIn(ExperimentalResourceApi::class)
    fun vehicle(model: Int, level: Int): ImageBitmap? {
        val key = model * Atlas.LEVELS + level
        vehicles[key]?.let { return it }
        val source = sources.getOrNull(model) ?: return null
        if (vehiclesUnpacking.add(key)) {
            val mine = generation
            scope.launch {
                val image = withContext(decoder) { source.sheet(level)?.decodeToImageBitmap() } ?: return@launch
                if (mine != generation) return@launch
                vehicles[key] = image
                revision.intValue++
            }
        }
        return null
    }

    private val statics = arrayOfNulls<ImageBitmap>(Atlas.LEVELS)
    private val staticsUnpacking = BooleanArray(Atlas.LEVELS)

    /**
     * The static sheet at [level] as a bitmap, for pictures of buildings in windows, unpacked the first time it's asked
     * for; null until then. Level 2 at the sharpest, some 6 MB as a bitmap, as it's a copy beside the renderer's.
     */
    @OptIn(ExperimentalResourceApi::class)
    fun staticImage(level: Int): ImageBitmap? {
        val at = level.coerceIn(PICTURE_SHARPEST, Atlas.LEVELS - 1)
        statics[at]?.let { return it }
        if (!staticsUnpacking[at]) {
            staticsUnpacking[at] = true
            scope.launch {
                statics[at] = withContext(decoder) { packed.static[at].decodeToImageBitmap() }
                revision.intValue++
            }
        }
        return null
    }

    /** The level nearest [level] that's unpacked, for drawing scaled until it is. */
    fun nearest(level: Int): Int? {
        get(level)?.let { return level }
        return levels.indices.filter { levels[it] != null }.minByOrNull { kotlin.math.abs(it - level) }
    }

    private companion object {
        const val SHADE_ROWS = 256
        /** The sharpest static sheet kept for pictures. */
        const val PICTURE_SHARPEST = 2
    }
}

/** The packed sheets, read off the main thread; null until they're read. */
@Composable
fun rememberPackedSheets(): State<PackedSheets?> = produceState<PackedSheets?>(null) {
    value = withContext(Dispatchers.Default) {
        suspend fun read(name: String) = (0 until Atlas.LEVELS).map { Res.readBytes("files/${name}_$it.webp") }
        val snow = (0 until Atlas.LEVELS).map { level ->
            if (level < 2) Res.readBytes("files/static_snow_$level.bin") else null
        }
        PackedSheets(read("ground"), read("static"), snow, read("shadows"), read("signals"), Atlas.VEHICLE_KEYS.map { read("vehicle_$it") })
    }
}
