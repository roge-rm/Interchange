package com.rm.interchange.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaletteTest {
    private val protan = arrayOf(doubleArrayOf(0.152286, 1.052583, -0.204868), doubleArrayOf(0.114503, 0.786281, 0.099216), doubleArrayOf(-0.003882, -0.048116, 1.051998))
    private val deutan = arrayOf(doubleArrayOf(0.367322, 0.860646, -0.227968), doubleArrayOf(0.280085, 0.672501, 0.047413), doubleArrayOf(-0.011820, 0.042940, 0.968881))
    private val tritan = arrayOf(doubleArrayOf(1.255528, -0.076749, -0.178779), doubleArrayOf(-0.078411, 0.930809, 0.147602), doubleArrayOf(0.004733, 0.691367, 0.303900))

    private fun linear(v: Float): Double = v.toDouble().let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }

    /** The colour as CIE Lab, after [m] (null for normal sight) is applied in linear RGB. */
    private fun lab(c: Color, m: Array<DoubleArray>?): DoubleArray {
        val rgb = doubleArrayOf(linear(c.red), linear(c.green), linear(c.blue))
        val s = if (m == null) rgb else DoubleArray(3) { i -> (m[i][0] * rgb[0] + m[i][1] * rgb[1] + m[i][2] * rgb[2]).coerceIn(0.0, 1.0) }
        val x = (0.4124 * s[0] + 0.3576 * s[1] + 0.1805 * s[2]) / 0.95047
        val y = 0.2126 * s[0] + 0.7152 * s[1] + 0.0722 * s[2]
        val z = (0.0193 * s[0] + 0.1192 * s[1] + 0.9505 * s[2]) / 1.08883
        fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116
        return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }

    private fun difference(a: Color, b: Color, m: Array<DoubleArray>?): Double {
        val p = lab(a, m)
        val q = lab(b, m)
        return sqrt((0..2).sumOf { (p[it] - q[it]) * (p[it] - q[it]) })
    }

    /** The closest any two of [colours] come under each kind of colour blindness. */
    private fun closest(colours: List<Color>): Double {
        var least = Double.MAX_VALUE
        for (m in listOf(protan, deutan, tritan)) for (i in colours.indices) for (j in i + 1 until colours.size) {
            least = minOf(least, difference(colours[i], colours[j], m))
        }
        return least
    }

    @Test
    fun everyPaletteHasAsManyEntriesAsTheStandard() {
        assertEquals(STANDARD_COMPANY_COLOURS.size, SAFE_COMPANY_COLOURS.size)
        assertEquals(STANDARD_PRICE_COLOURS.size, SAFE_PRICE_COLOURS.size)
        assertEquals(priceColours(false).size, priceColours(true).size)
    }

    @Test
    fun goodWarnAndBadStayApart() {
        val least = closest(listOf(SafeColors.good, SafeColors.warn, SafeColors.bad))
        assertTrue(least > 30, "closest pair $least")
    }

    @Test
    fun companyColoursStayApart() {
        val least = closest(SAFE_COMPANY_COLOURS)
        assertTrue(least > 12, "closest pair $least")
    }

    @Test
    fun priceColoursStayApartBetterThanTheStandard() {
        val least = closest(SAFE_PRICE_COLOURS)
        assertTrue(least > 12, "closest pair $least")
        assertTrue(least > closest(STANDARD_PRICE_COLOURS), "safe $least, standard ${closest(STANDARD_PRICE_COLOURS)}")
    }

    @Test
    fun theSafeGoodAndBadStayFurtherApartForRedGreenBlindnessThanTheStandard() {
        val c = DarkColors
        assertTrue(abs(difference(c.good, c.bad, deutan)) < abs(difference(SafeColors.good, SafeColors.bad, deutan)))
    }
}
