package com.rm.interchange.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * The colours that change with the Colours setting: Standard, or a set from Okabe and Ito that stays apart for
 * red-green and blue-yellow colour blindness. [safe] is snapshot state, so what reads it
 * in composition or drawing runs again when the App changes it, and the map's threads can read it too.
 */
object Palette {
    var safe by mutableStateOf(false)

    /** Red, as the plan and overlay marks use it, at [alpha] 0 to 255. */
    fun bad(alpha: Int): Color = (if (safe) SAFE_BAD else STANDARD_BAD).withAlpha(alpha)

    /** Green, as the area marks use it, at [alpha] 0 to 255. */
    fun good(alpha: Int): Color = (if (safe) SAFE_GOOD else STANDARD_GOOD).withAlpha(alpha)

    /** The slower-track mark: amber, or yellow in the safe set. */
    fun slows(alpha: Int): Color = (if (safe) SAFE_WARN else STANDARD_SLOWS).withAlpha(alpha)

    private fun Color.withAlpha(alpha: Int) = copy(alpha = alpha / 255f)
}

private val STANDARD_BAD = Color(0xFFE05A4E)
private val STANDARD_GOOD = Color(0xFF58B368)
private val STANDARD_SLOWS = Color(0xFFE0A030)

/** Sky blue, yellow and vermillion for good, middling and bad. */
val SAFE_GOOD = Color(0xFF56B4E9)
val SAFE_WARN = Color(0xFFF0E442)
val SAFE_BAD = Color(0xFFD55E00)

val SafeColors = DarkColors.copy(good = SAFE_GOOD, warn = SAFE_WARN, bad = SAFE_BAD)

/** Each company's colour by its id, for its signs, lines and badges; the player's company is the first. */
val STANDARD_COMPANY_COLOURS = listOf(
    Color(0xFF2E6FB0), Color(0xFFC0392B), Color(0xFF2E8B57), Color(0xFFE0B020),
    Color(0xFF8E44AD), Color(0xFFE67E22), Color(0xFF16A085), Color(0xFF7F8C8D),
)
val SAFE_COMPANY_COLOURS = listOf(
    Color(0xFF0072B2), Color(0xFFE69F00), Color(0xFF009E73), Color(0xFFF0E442),
    Color(0xFFCC79A7), Color(0xFFD55E00), Color(0xFF56B4E9), Color(0xFFF2F2F2),
)

/** The line colours of the price chart: labour, steel, concrete, land, then coal, diesel, electricity, aviation fuel, bunker fuel and hydrogen. */
val STANDARD_PRICE_COLOURS = listOf(
    Color(0xFFE0A030), Color(0xFF7A8CA0), Color(0xFFB0A090), Color(0xFF6AAA5A), Color(0xFF555555),
    Color(0xFFD05A3A), Color(0xFF4A90D9), Color(0xFF9A70C8), Color(0xFF30A8A8), Color(0xFFE05AA0),
)
val SAFE_PRICE_COLOURS = listOf(
    Color(0xFFE69F00), Color(0xFF56B4E9), Color(0xFF009E73), Color(0xFFF0E442), Color(0xFFF2F2F2),
    Color(0xFFD55E00), Color(0xFF0072B2), Color(0xFFCC79A7), Color(0xFF606060), Color(0xFFA0522D),
)

fun priceColours(safe: Boolean = Palette.safe) = if (safe) SAFE_PRICE_COLOURS else STANDARD_PRICE_COLOURS
