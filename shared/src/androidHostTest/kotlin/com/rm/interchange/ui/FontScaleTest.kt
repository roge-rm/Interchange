package com.rm.interchange.ui

import androidx.compose.ui.unit.Density
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FontScaleTest {
    @Test
    fun capHoldsTheScaleAndKeepsTheDensity() {
        val capped = Density(2.5f, 2f).cappedFontScale(BARS_FONT_SCALE)
        assertEquals(BARS_FONT_SCALE, capped.fontScale)
        assertEquals(2.5f, capped.density)
    }

    @Test
    fun capLeavesSmallerScalesAlone() {
        val d = Density(2f, 1.15f)
        assertEquals(1.15f, d.cappedFontScale(BARS_FONT_SCALE).fontScale)
    }

    private fun strings(dir: String): Map<String, String> {
        val file = listOf("src/commonMain/composeResources/$dir/strings.xml", "shared/src/commonMain/composeResources/$dir/strings.xml").map(::File).first { it.exists() }
        return Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun mapDescriptionIsInBothLanguages() {
        for (dir in listOf("values", "values-fr")) assertTrue(strings(dir)["map_description"].orEmpty().isNotBlank(), dir)
    }

    @Test
    fun buttonLabelsAreInFrenchToo() {
        val en = strings("values")
        val fr = strings("values-fr")
        for (k in listOf("menu", "play", "pause", "speed", "overlays", "chat", "close", "overview_map", "turn_left", "turn_right", "weather", "finances")) {
            assertTrue(en[k].orEmpty().isNotBlank(), "en $k")
            assertTrue(fr[k].orEmpty().isNotBlank(), "fr $k")
        }
    }
}
