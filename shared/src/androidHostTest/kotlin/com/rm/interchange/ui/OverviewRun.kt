package com.rm.interchange.ui

import com.rm.interchange.map.terrainColour
import com.rm.interchange.sim.NewGame
import com.rm.interchange.sim.RivalLevel
import com.rm.interchange.sim.Trees
import com.rm.interchange.sim.World
import com.rm.interchange.sim.overviewInfo
import java.lang.management.ManagementFactory
import kotlin.test.Test

/**
 * What the overview map costs to open on a big map: the snapshot taken on the game thread, and what it holds, against
 * the whole-map copies and full size terrain it took before, with its industries matched to their figures and numbered by search. Slow, so it runs only when asked:
 * `./gradlew :shared:testAndroidHostTest --tests '*OverviewRun*' -Pinterchange.overview=1024,4096`.
 */
class OverviewRun {
    @Test
    fun measure() {
        val sizes = (System.getProperty("interchange.overview") ?: return).split(",").map { it.trim().toInt() }
        for (size in sizes) {
            val w = World(NewGame(size, size, 1, rivals = 6, rivalLevel = RivalLevel.HARD))
            var base: Long
            // The quickest of three, each way.
            val snap = (1..3).minOf { time { MapSnapshot(w) } }
            val oldTime = (1..3).minOf { time { OldSnapshot(w) } }
            base = used()
            val s = MapSnapshot(w)
            val held = (used() - base) / 1e6
            base = used()
            val old = OldSnapshot(w)
            val oldHeld = (used() - base) / 1e6
            println("overview $size: now ${"%.0f".format(snap)} ms on the game thread, holds ${"%.0f".format(held)} MB, terrain ${s.terrainWidth}; " +
                "before ${"%.0f".format(oldTime)} ms for its figures, names, copies and pixels, holds ${"%.0f".format(oldHeld)} MB, terrain ${old.width} (${s.rail.tiles.size} rail, ${s.roads.tiles.size} road tiles)")
        }
    }

    /** The figures, industry names, whole-map copies and full size terrain pixels the overview took before; towns and stations left out. */
    private class OldSnapshot(w: World) {
        val width = w.width
        // The industries matched to their figures one search at a time, as before.
        val info = w.overviewInfo()
        val matched = w.industries.filter { !it.closed }.map { ind -> info.industries.first { it.id == ind.id } }
        val names = w.industries.filter { !it.closed }.map { industryName(w, it, "en") + industryName(w, it, "fr") }
        val corners = w.corners.copyOf()
        val ground = w.ground.copyOf()
        val trees = w.trees.copyOf()
        val rail = w.track.copyOf()
        val roads = w.road.copyOf()
        val owner = w.owner.copyOf()
        val roadOwner = w.roadOwner.copyOf()
        val pixels = IntArray(width * w.height).also { pixels ->
            for (i in pixels.indices) {
                val corner = i / width * (width + 1) + i % width
                pixels[i] = if (i in w.canals) terrainColour(0, 0, 0, 0, 0, 0) else terrainColour(
                    corners[corner].toInt(), corners[corner + 1].toInt(), corners[corner + width + 2].toInt(), corners[corner + width + 1].toInt(),
                    ground[i].toInt(), Trees.count(trees[i].toInt()),
                )
            }
        }
    }

    private fun time(make: () -> Any): Double {
        val t0 = System.nanoTime()
        make()
        return (System.nanoTime() - t0) / 1e6
    }

    private fun used(): Long {
        repeat(3) { System.gc() }
        return ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
    }
}
