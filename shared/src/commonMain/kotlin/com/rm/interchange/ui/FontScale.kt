package com.rm.interchange.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density

/** The most the system font scale grows the status strip, tool bar and tray, whose buttons are a fixed size. */
const val BARS_FONT_SCALE = 1.3f

/** The most it grows words drawn on the map, which keep the size the map is drawn for. */
const val MAP_FONT_SCALE = 1.0f

/** The same [Density] with its font scale held to [max]; everything else in it stays. */
fun Density.cappedFontScale(max: Float): Density = if (fontScale <= max) this else Density(density, max)

/** [content] with the system font scale held to [max], for places that can't grow. Windows and cards aren't held. */
@Composable
fun CapFontScale(max: Float = BARS_FONT_SCALE, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    if (d.fontScale <= max) content()
    else {
        val capped = remember(d.density, max) { Density(d.density, max) }
        CompositionLocalProvider(LocalDensity provides capped, content = content)
    }
}

/** A text measurer that sizes words as if the font scale were at most [max], for text drawn on the map. */
@Composable
fun rememberCappedMeasurer(max: Float = MAP_FONT_SCALE): TextMeasurer {
    var measurer: TextMeasurer? = null
    CapFontScale(max) { measurer = rememberTextMeasurer() }
    return measurer!!
}
