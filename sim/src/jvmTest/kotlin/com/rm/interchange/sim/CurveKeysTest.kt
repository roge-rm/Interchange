package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [CurveKeys] against tools/blender/curve_art.py, by curve_keys_reference.txt (`curve_art.py --reference`). */
class CurveKeysTest {
    private class Scene(val name: String) {
        val masks = HashMap<Pair<Int, Int>, Int>()
        val flags = HashMap<Pair<Int, Int>, Int>()
        val keys = HashMap<Pair<Int, Int>, MutableList<String>>()
    }

    private val scenes = ArrayList<Scene>().also { list ->
        val text = CurveKeysTest::class.java.getResourceAsStream("/curve_keys_reference.txt")!!.bufferedReader().readText()
        for (line in text.lines()) {
            val w = line.split(" ")
            when (w[0]) {
                "#" -> list += Scene(w[1])
                "T" -> {
                    list.last().masks[w[1].toInt() to w[2].toInt()] = w[3].toInt()
                    list.last().flags[w[1].toInt() to w[2].toInt()] = w[4].toInt()
                }
                "K" -> list.last().keys.getOrPut(w[1].toInt() to w[2].toInt()) { ArrayList() } += "${w[3]} ${w[4]}"
            }
        }
    }

    @Test
    fun matchesCurveArtPy() {
        var count = 0
        for (s in scenes) {
            val mask = { x: Int, y: Int -> s.masks[x to y] ?: 0 }
            val curves = TrackCurves(mask) { x, y -> s.flags[x to y] ?: 0 }
            for ((tile, m) in s.masks) {
                if (m == 0) continue
                val got = CurveKeys.at(curves, mask, tile.first, tile.second)
                assertEquals(s.keys[tile].orEmpty().sorted(), got.sorted(), "${s.name} tile $tile")
                count += got.size
            }
        }
        assertTrue(count > 5000, "only $count keys")
    }

    @Test
    fun layersComeInOrder() {
        val s = scenes.first { it.name == "turnout" }
        val mask = { x: Int, y: Int -> s.masks[x to y] ?: 0 }
        val got = CurveKeys.at(TrackCurves(mask) { _, _ -> 0 }, mask, 1, 0).map { it.substringBefore(' ') }
        assertEquals(got.sortedBy { listOf("bed", "ties", "running", "gear").indexOf(it) }, got)
        assertTrue("gear" in got)
    }
}
