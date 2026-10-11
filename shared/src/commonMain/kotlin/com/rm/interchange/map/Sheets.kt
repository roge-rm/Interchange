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
 * The atlas sheets, sharpest first. They're kept packed and a level is only
 * unpacked the first time something asks for it, off the main thread, since
 * the sharpest is large and only used up close.
 *
 * Main thread only: [get] hands back null while a level is still unpacking.
 */
class Sheets(private val packed: List<ByteArray>, private val scope: CoroutineScope) {
    private val images = arrayOfNulls<ImageBitmap>(packed.size)
    private val unpacking = BooleanArray(packed.size)
    private val decoder = Dispatchers.Default.limitedParallelism(1)

    /** Goes up as each level is unpacked, so whatever waited on it draws again. */
    val revision = mutableIntStateOf(0)

    @OptIn(ExperimentalResourceApi::class)
    fun get(level: Int): ImageBitmap? {
        images[level]?.let { return it }
        if (!unpacking[level]) {
            unpacking[level] = true
            scope.launch {
                images[level] = withContext(decoder) { packed[level].decodeToImageBitmap() }
                revision.intValue++
            }
        }
        return null
    }

    /** The level nearest [level] that's unpacked, for drawing scaled until it is. */
    fun nearest(level: Int): Int? {
        get(level)?.let { return level }
        return images.indices.filter { images[it] != null }.minByOrNull { kotlin.math.abs(it - level) }
    }
}

/** The packed atlas sheets, read off the main thread; null until they're read. */
@Composable
fun rememberPackedSheets(): State<List<ByteArray>?> = produceState<List<ByteArray>?>(null) {
    value = withContext(Dispatchers.Default) { (0 until Atlas.LEVELS).map { Res.readBytes("files/atlas_$it.png") } }
}
