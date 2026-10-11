package com.rm.interchange.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import com.rm.interchange.res.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/** The atlas sheets, sharpest level first, read off the main thread; null until they're ready. */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun rememberSheets(): State<List<ImageBitmap>?> = produceState<List<ImageBitmap>?>(null) {
    val bytes = (0 until Atlas.LEVELS).map { Res.readBytes("files/atlas_$it.png") }
    value = withContext(Dispatchers.Default) { bytes.map { it.decodeToImageBitmap() } }
}
