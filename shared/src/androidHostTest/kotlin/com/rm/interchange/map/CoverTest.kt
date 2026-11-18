package com.rm.interchange.map

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.Surface
import com.rm.interchange.sim.Track
import com.rm.interchange.sim.NewGame
import com.rm.interchange.sim.Region
import com.rm.interchange.sim.Trees
import com.rm.interchange.sim.World
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoverTest {
    private object Blank : ImageBitmap {
        override val width = 1
        override val height = 1
        override val colorSpace = ColorSpaces.Srgb
        override val hasAlpha = true
        override val config = ImageBitmapConfig.Argb8888
        override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) {}
        override fun prepareToDraw() {}
    }

    private val world by lazy { World(NewGame(64, 64, 7, 1930, Region.MIXED, towns = 2, industries = 2, rivals = 0)) }
    private val view by lazy { View(world.width, world.height).also { it.halfWidth = Zoom.LEVELS[LEVEL] } }
    private val picker by lazy { TileRecorder(world, view, LEVEL, shown = WeatherCopy(0, ByteArray(0), ByteArray(0), ByteArray(0)), year = Calendar.year(world.startYear, world.day)) }

    private fun cover(): Cover {
        // Every column solid top to bottom, so the sheet's pixels aren't needed.
        val full = HashMap<Int, IntArray>()
        val shrink = 1 shl LEVEL
        for (cell in 0 until StaticCells.rects.size / 6) {
            val w = StaticCells.rects[cell * 6 + 2] / shrink
            val h = StaticCells.rects[cell * 6 + 3] / shrink
            if (w > 0 && h > 0) full[cell] = IntArray(w * 2) { if (it % 2 == 0) 0 else h - 1 }
        }
        Cover.takeColumns(LEVEL, full)
        return Cover(world, view, LEVEL, LevelSheets(IntArray(4), IntArray(4), 2, ByteArray(4), 2, Blank))
    }

    private fun firstTile(test: (Int, Int) -> Boolean): Pair<Int, Int> {
        for (vy in 4 until view.viewHeight - 4) for (vx in 4 until view.viewWidth - 4) if (test(vx, vy)) return vx to vy
        error("no such tile")
    }

    private fun wooded(vx: Int, vy: Int): Boolean {
        val wx = view.worldX(vx, vy)
        val wy = view.worldY(vx, vy)
        val i = world.index(wx, wy)
        return Trees.count(world.trees[i].toInt()) > 0 && world.trackAt(wx, wy) == 0 && world.road[i].toInt() == 0 && picker.building(vx, vy) == TileRecorder.NOTHING
    }

    /** The rects standing in front of view point ([vx], [vy]) give, rounded to hundredths. */
    private fun standing(c: Cover, vx: Float, vy: Float): List<List<Float>> {
        val out = ArrayList<List<Float>>()
        c.standing(vx, vy, -1e6f, -1e6f, 1e6f, 1e6f, 800f, 600f) { l, t, r, b -> out += listOf(l, t, r, b).map { kotlin.math.round(it * 100f) } }
        return out
    }

    private fun treesCutWhatsBehindOnly() {
        val (tx, ty) = firstTile { vx, vy -> wooded(vx, vy) }
        val c = cover()
        // Standing on the tile behind, across the diagonal: the trees are in front of it. In front of them, they aren't.
        val behind = standing(c, tx - 0.5f, ty - 0.5f)
        assertTrue(behind.isNotEmpty())
        val buildings = ArrayList<Int>()
        c.rects(tx - 0.5f, ty - 0.5f, -1e6f, -1e6f, 1e6f, 1e6f, 800f, 600f, { true }) { _, _, _, _, d -> buildings += d }
        assertTrue(tx + ty !in buildings, "the buildings alone leave them out")
        val ahead = standing(c, tx + 1.5f, ty + 1.5f)
        assertTrue(behind.any { it !in ahead })
        // Kept from frame to frame: a new frame's cover gives the same.
        assertTrue(standing(cover(), tx - 0.5f, ty - 0.5f) == behind)
    }

    @Test
    fun aSignalIsCutByTheTreesInFrontOfItOnly() = treesCutWhatsBehindOnly()

    @Test
    fun aSignalIsCutByTheTreesInFrontOfItTurned() {
        view.turnBy(1)
        try { treesCutWhatsBehindOnly() } finally { view.turnBy(-1) }
    }

    @Test
    fun aBuildingCutsASignalWhereItCutsAVehicle() {
        val (bx, by) = firstTile { vx, vy -> world.trackAt(view.worldX(vx, vy), view.worldY(vx, vy)) == 0 && picker.building(vx, vy) >= 0 }
        val c = cover()
        val vehicle = ArrayList<List<Float>>()
        c.rects(bx - 0.5f, by - 0.5f, -1e6f, -1e6f, 1e6f, 1e6f, 800f, 600f, { true }) { l, t, r, b, _ -> vehicle += listOf(l, t, r, b).map { kotlin.math.round(it * 100f) } }
        assertTrue(vehicle.isNotEmpty())
        // Trees on the other tiles in front may come too.
        assertTrue(standing(c, bx - 0.5f, by - 0.5f).containsAll(vehicle))
    }

    @Test
    fun aGarageCoversARoadVehicleOnItsOwnTile() {
        val w = World(64, 64, 1, startYear = 1965)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 100_000_000
        w.apply(Command.Road(0, 5, 20, 5, 22, Surface.PAVED, remove = false))
        w.apply(Command.Garage(0, 5, 23, Track.YM))
        val v = View(w.width, w.height).also { it.halfWidth = Zoom.LEVELS[LEVEL] }
        cover()
        val c = Cover(w, v, LEVEL, LevelSheets(IntArray(4), IntArray(4), 2, ByteArray(4), 2, Blank))
        val gx = v.toViewX(5.5f, 23.5f)
        val gy = v.toViewY(5.5f, 23.5f)
        val tx = kotlin.math.floor(gx).toInt()
        val ty = kotlin.math.floor(gy).toInt()
        // The garage's columns go in at its tile's depth, and a vehicle in it is cut half a tile behind that.
        fun depths(x: Float, y: Float) = ArrayList<Int>().also { out -> c.rects(x, y, -1e6f, -1e6f, 1e6f, 1e6f, 800f, 600f, { true }) { _, _, _, _, d -> out += d } }
        assertTrue(tx + ty in depths(gx, gy), "in the garage")
        assertTrue(c.inGarage(gx, gy))
        // An empty tile far from it has none.
        assertTrue(depths(v.toViewX(40.5f, 40.5f), v.toViewY(40.5f, 40.5f)).isEmpty())
        assertFalse(c.inGarage(v.toViewX(40.5f, 40.5f), v.toViewY(40.5f, 40.5f)))
    }

    @Test
    fun aGarageLeavesAVehicleOnATileBesideItUncut() {
        val w = World(64, 64, 1, startYear = 1965)
        for (i in w.corners.indices) w.corners[i] = 3
        w.companies[0].money = 100_000_000
        w.apply(Command.Road(0, 5, 20, 5, 22, Surface.PAVED, remove = false))
        w.apply(Command.Garage(0, 5, 23, Track.YM))
        val v = View(w.width, w.height).also { it.halfWidth = Zoom.LEVELS[LEVEL] }
        cover()
        val c = Cover(w, v, LEVEL, LevelSheets(IntArray(4), IntArray(4), 2, ByteArray(4), 2, Blank))
        val tx = kotlin.math.floor(v.toViewX(5.5f, 23.5f)).toInt()
        val ty = kotlin.math.floor(v.toViewY(5.5f, 23.5f)).toInt()
        val garage = ty * v.viewWidth + tx
        // A vehicle in the garage puts its columns in, at the garage tile's depth.
        val columns = ArrayList<Int>()
        var tile = -1
        c.rects(tx + 0.5f, ty + 0.5f, -1e6f, -1e6f, 1e6f, 1e6f, 800f, 600f, { tile = it; true }) { _, _, _, _, d -> if (tile == garage) columns += d }
        assertTrue(columns.isNotEmpty())
        for ((sx, sy) in listOf(tx + 1 to ty - 1, tx - 1 to ty + 1)) {
            // On a tile beside it, on the same diagonal: not in the garage, so cut at its tile's and a half, in front of
            // the garage's columns.
            assertFalse(c.inGarage(sx + 0.5f, sy + 0.5f))
            assertTrue(columns.all { it < sx + sy + 0.5f }, "beside the garage at $sx, $sy")
        }
    }

    private companion object {
        const val LEVEL = 2
    }
}
