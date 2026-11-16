package com.rm.interchange.map

import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.NewGame
import com.rm.interchange.sim.Packs
import com.rm.interchange.sim.Region
import com.rm.interchange.sim.Roster
import com.rm.interchange.sim.Save
import com.rm.interchange.sim.World
import com.rm.interchange.sim.industryAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildingArtTest {
    private fun rects(w: Int) = IntArray(96).also { for (c in 0 until 4) { it[c * 6 + 2] = w; it[c * 6 + 3] = w / 2; it[c * 6 + 4] = -w / 2; it[c * 6 + 5] = -w / 4 } }

    @Test
    fun cellsAreAddedAndRemoved() {
        val base = StaticCells.BASE
        val art = BuildingArt(listOf(3, 7), listOf("a/x", "b/y"), listOf(rects(128), null)) { _, _ -> null }
        StaticCells.set(art)
        try {
            assertEquals(base + 8, StaticCells.count)
            assertEquals(128, StaticCells.rects[base * 6 + 2])
            // The missing drawing is a box.
            assertEquals(192, StaticCells.rects[(base + 4) * 6 + 2])
            // Each level's cell lies under the base sheet's rows and inside its width, none on another.
            val bottom = (0 until base).maxOf { StaticCells.levelRects[(it * Atlas.LEVELS + 1) * 6 + 1] + StaticCells.levelRects[(it * Atlas.LEVELS + 1) * 6 + 3] }
            val boxes = (0 until 8).map { c -> StaticCells.levelRects.copyOfRange(((base + c) * Atlas.LEVELS + 1) * 6, ((base + c) * Atlas.LEVELS + 1) * 6 + 4) }
            for (b in boxes) assertTrue(b[1] >= bottom)
            for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                val a = boxes[i]; val b = boxes[j]
                assertTrue(a[0] + a[2] <= b[0] || b[0] + b[2] <= a[0] || a[1] + a[3] <= b[1] || b[1] + b[3] <= a[1], "cells $i and $j overlap")
            }
            assertTrue(art.rowsAt(1, 10) > bottom)
        } finally {
            StaticCells.set(null)
        }
        assertEquals(base, StaticCells.count)
    }

    private fun pack(version: Int, industries: String?): com.rm.interchange.sim.Pack {
        val files = HashMap<String, ByteArray>()
        files["manifest.json"] = """{ "format": 2, "id": "shrink", "version": $version, "name": { "en": "Shrink" }, "needs": ["base"] }""".encodeToByteArray()
        if (industries != null) files["industries.json"] = """{ "industries": [ $industries ] }""".encodeToByteArray()
        return Packs.read(files)
    }

    @Test
    fun aSavedIndustryDrawsAgainstASmallerLayout() {
        val w = World(NewGame(64, 64, 7, 1930, Region.MIXED, towns = 1, industries = 2, rivals = 0), Roster.build(listOf(Packs.BASE, pack(1, null))))
        val ind = w.industries.first()
        val old = w.roster.industries[ind.kind]
        // A newer version of the pack gives the industry one tile of its layout.
        val newer = pack(2, """{ "id": "small", "replaces": "base:${old.key.substringAfter(':')}", "layout": ["${old.layout[0][0]}"] }""")
        val copy = Save.read(Save.write(w)) { ref -> listOf(Packs.BASE, newer).firstOrNull { it.id == ref.id } }
        assertEquals(1, copy.roster.industries[ind.kind].width * copy.roster.industries[ind.kind].height)
        val view = View(copy.width, copy.height).also { it.halfWidth = Zoom.LEVELS[2] }
        val picker = TileRecorder(copy, view, 2, shown = WeatherCopy(0, ByteArray(0), ByteArray(0), ByteArray(0)), year = Calendar.year(copy.startYear, copy.day))
        var drawn = 0
        for (vy in 0 until view.viewHeight) for (vx in 0 until view.viewWidth) {
            val i = copy.index(view.worldX(vx, vy), view.worldY(vx, vy))
            if (copy.industryAt(i)?.id != ind.id) continue
            picker.building(vx, vy)
            picker.building(vx, vy, shadow = true)
            drawn++
        }
        assertEquals(old.width * old.height, drawn)
    }
}
