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

/** One zoom level's sheets: the ground textures and the static sprites as pixels for baking, the signals as a bitmap for drawing. */
class LevelSheets(val ground: IntArray, val static: IntArray, val staticWidth: Int, val signals: ImageBitmap)

/** The packed sheets for each level, as read from the app, and each vehicle's, by its place in [Atlas.VEHICLE_KEYS]. */
class PackedSheets(val ground: List<ByteArray>, val static: List<ByteArray>, val signals: List<ByteArray>, val vehicles: List<List<ByteArray>>)

/**
 * The sheets, sharpest level first. They're kept packed and a level is only
 * unpacked the first time something asks for it, off the main thread, since
 * the sharpest is large and only used up close.
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
                    LevelSheets(ground, static, width, packed.signals[level].decodeToImageBitmap())
                }
                revision.intValue++
            }
        }
        return null
    }

    private val vehicles = HashMap<Int, ImageBitmap>()
    private val vehiclesUnpacking = HashSet<Int>()

    /** Vehicle drawing [model]'s sheet at [level], unpacked the first time it's asked for; null until then. */
    @OptIn(ExperimentalResourceApi::class)
    fun vehicle(model: Int, level: Int): ImageBitmap? {
        val key = model * Atlas.LEVELS + level
        vehicles[key]?.let { return it }
        if (vehiclesUnpacking.add(key)) {
            scope.launch {
                vehicles[key] = withContext(decoder) { packed.vehicles[model][level].decodeToImageBitmap() }
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
}

/** The packed sheets, read off the main thread; null until they're read. */
@Composable
fun rememberPackedSheets(): State<PackedSheets?> = produceState<PackedSheets?>(null) {
    value = withContext(Dispatchers.Default) {
        suspend fun read(name: String) = (0 until Atlas.LEVELS).map { Res.readBytes("files/${name}_$it.png") }
        PackedSheets(read("ground"), read("static"), read("signals"), Atlas.VEHICLE_KEYS.map { read("vehicle_$it") })
    }
}
