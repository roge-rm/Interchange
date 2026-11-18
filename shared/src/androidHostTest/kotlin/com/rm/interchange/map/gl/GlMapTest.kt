package com.rm.interchange.map.gl

import com.rm.interchange.map.Zoom
import com.rm.interchange.map.View
import com.rm.interchange.map.WeatherCopy
import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.Climate
import com.rm.interchange.sim.NewGame
import com.rm.interchange.sim.Region
import com.rm.interchange.sim.World
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlMapTest {
    private companion object {
        // Not square, so the turns that swap the view's width and height show up.
        val world by lazy { World(NewGame(64, 32, 7, 1930, Region.MIXED, towns = 2, industries = 2, rivals = 0)) }
        val weather by lazy { WeatherCopy.of(world, 0) }
        val year by lazy { Calendar.year(world.startYear, world.day) }
        val look by lazy { Climate.look(world.settings.region, year, Calendar.dayOfYear(world.startYear, world.day)) }
        val scenes = HashMap<Int, Array<RegionSprites?>>()

        fun regions(turn: Int): Array<RegionSprites?> = scenes.getOrPut(turn) {
            Array(regionCount(world, turn)) { recordRegion(world, turn, it, look, year, weather, keepRecord = true) }
        }

        fun view(turn: Int, halfWidth: Float, fx: Float, fy: Float) = View(world.width, world.height).also {
            it.turnBy(turn)
            it.halfWidth = halfWidth
            it.focusX = fx
            it.focusY = fy
        }
    }

    /** One sprite's fields as recorded, sheet first. */
    private fun sprite(data: IntArray, i: Int) = IntArray(SpriteRecorder.STRIDE) { data[i * SpriteRecorder.STRIDE + it] }

    @Test
    fun partOfARegionRecordsItsTilesAsTheWholeDoes() {
        for (turn in 0 until 4) for (r in regions(turn)) {
            r!!
            // A band of columns that moves along the rows, empty on some.
            val cols = IntArray(2 * r.rows) { k -> val row = k / 2; if (row % 5 == 4) (if (k % 2 == 0) 1 else 0) else if (k % 2 == 0) r.columns / 4 + row % 3 else r.columns * 3 / 4 - row % 2 }
            val part = recordRegion(world, turn, r.index, look, year, weather, cols = cols, keepRecord = true)
            assertEquals(r.columns, part.columns)
            for (pass in 0 until PASSES) for (vy in r.row0 until r.row1) for (vx in 0 until r.columns) {
                val k = 2 * (vy - r.row0)
                val inside = vx >= cols[k] && vx <= cols[k + 1]
                // The tile after the last of a row is the next row's first, so start() gives each tile's end.
                val n = part.start(pass, vy, vx + 1) - part.start(pass, vy, vx)
                val whole = r.start(pass, vy, vx + 1) - r.start(pass, vy, vx)
                if (!inside) { assertEquals(0, n, "turn $turn region ${r.index} tile $vx,$vy pass $pass outside"); continue }
                assertEquals(whole, n, "turn $turn region ${r.index} tile $vx,$vy pass $pass sprite count")
                for (j in 0 until n) {
                    val a = sprite(part.data, part.start(pass, vy, vx) + j)
                    val b = sprite(r.data, r.start(pass, vy, vx) + j)
                    if (a[0] == SpriteRecorder.GROUND && b[0] == SpriteRecorder.GROUND) {
                        val ga = part.ground.copyOfRange(a[1] * GroundRecord.STRIDE, (a[1] + 1) * GroundRecord.STRIDE)
                        val gb = r.ground.copyOfRange(b[1] * GroundRecord.STRIDE, (b[1] + 1) * GroundRecord.STRIDE)
                        assertTrue(ga.contentEquals(gb), "turn $turn region ${r.index} tile $vx,$vy ground differs")
                    } else assertTrue(a.contentEquals(b), "turn $turn region ${r.index} tile $vx,$vy pass $pass sprite $j differs")
                }
            }
            for (vy in r.row0 until r.row1) {
                val k = 2 * (vy - r.row0)
                assertTrue(part.holds(vy, cols[k], cols[k + 1]))
                assertTrue(part.holds(vy, 1, 0))
                if (cols[k] <= cols[k + 1]) assertTrue(!part.holds(vy, cols[k], cols[k + 1] + 1))
            }
            assertTrue(r.holds(r.row0, 0, r.columns - 1))
        }
    }

    @Test
    fun eachPassDrawsFromOneSheet() {
        for (turn in 0 until 4) for (r in regions(turn)) {
            r!!
            for (pass in 0 until PASSES) for (s in r.start(pass, r.row0, 0) until r.start(pass, r.row1 - 1, r.columns)) {
                val sheet = r.data[s * SpriteRecorder.STRIDE]
                if (pass == 0) assertTrue(sheet == SpriteRecorder.STATIC || sheet == SpriteRecorder.GROUND, "turn $turn pass 0 sheet $sheet")
                else assertEquals(if (pass == 1) SpriteRecorder.SHADOW else SpriteRecorder.STATIC, sheet, "turn $turn pass $pass")
            }
        }
    }

    @Test
    fun wholeMapOnScreenDrawsEverySpriteInOrder() {
        for (turn in 0 until 4) {
            val regions = regions(turn)
            val view = view(turn, View.MIN_HALF_WIDTH, world.width / 2f, world.height / 2f)
            val frame = GlFrame.of(view, 1280, 800)
            val runs = DrawRuns()
            runs.build(regions, visibleColumns(frame))
            for (pass in 0 until PASSES) {
                // The whole map's pass, region by region, as one list.
                val want = ArrayList<Pair<Int, Int>>()
                for (r in regions) for (s in r!!.start(pass, r.row0, 0) until r.start(pass, r.row1 - 1, r.columns)) want += r.index to s
                val got = ArrayList<Pair<Int, Int>>()
                for (k in runs.passStart[pass] until runs.passStart[pass + 1]) for (s in runs.first(k) until runs.first(k) + runs.count(k)) got += runs.region(k) to s
                assertEquals(want, got, "turn $turn pass $pass")
                // Whole regions join up into one run each.
                assertEquals(regions.count { it!!.start(pass, it.row0, 0) < it.start(pass, it.row1 - 1, it.columns) }, runs.passStart[pass + 1] - runs.passStart[pass])
            }
        }
    }

    @Test
    fun squaresCoverTheScreenAndPlaceAsTheScreenDoes() {
        for (lv in Zoom.SHARPEST until Zoom.LEVELS.size) for (halfWidth in floatArrayOf(96f, 64f, 40f, 17f, 6f, 3f)) {
            if (Zoom.levelFor(halfWidth) != lv) continue
            for ((fx, fy) in listOf(32f to 16f, 5f to 28f, 0f to 0f)) {
                val f = GlFrame.of(view(1, halfWidth, fx, fy), 800, 1280)
                val on = squaresOn(f, lv)
                // The first and last squares each way reach the screen's edges, and no further square does.
                assertTrue(squareEdge(f, lv, on[0], false) <= 0f && squareEdge(f, lv, on[0] + 1, false) > 0f, "lv $lv hw $halfWidth x0")
                assertTrue(squareEdge(f, lv, on[1] + 1, false) >= f.width && squareEdge(f, lv, on[1], false) < f.width, "lv $lv hw $halfWidth x1")
                assertTrue(squareEdge(f, lv, on[2], true) <= 0f && squareEdge(f, lv, on[2] + 1, true) > 0f, "lv $lv hw $halfWidth y0")
                assertTrue(squareEdge(f, lv, on[3] + 1, true) >= f.height && squareEdge(f, lv, on[3], true) < f.height, "lv $lv hw $halfWidth y1")
                // A source pixel lands in a square where the screen puts it, scaled to the level.
                val cx = on[0] + 1
                val cy = on[2]
                val sf = squareFrame(f, lv, cx, cy)
                for (p in floatArrayOf(-700f, 0f, 1234.5f)) {
                    val k = SQUARE_SIDE * toSource(lv) * f.scale
                    assertEquals(f.screenX(p), squareEdge(f, lv, cx, false) + sf.screenX(p) / SQUARE_SIDE * k, 0.01f, "lv $lv x $p")
                    assertEquals(f.screenY(p), squareEdge(f, lv, cy, true) + sf.screenY(p) / SQUARE_SIDE * k, 0.01f, "lv $lv y $p")
                }
            }
        }
    }

    @Test
    fun staleSquaresAreDrawnAgainAsMissingOnesAre() {
        val on = intArrayOf(-1, 2, 0, 2)
        // Fresh: every other one. Missing and stale both come back as not fresh.
        val todo = squaresToDraw(on) { cx, cy -> (cx + cy) % 2 == 0 }
        val want = (0..2).flatMap { cy -> (-1..2).map { cx -> cx to cy } }.filter { (cx, cy) -> (cx + cy) % 2 != 0 }
        assertEquals(want.toSet(), todo.map { squareX(it) to squareY(it) }.toSet())
        // The nearest the middle first.
        val d = todo.map { val dx = squareX(it) - 0.5f; val dy = squareY(it) - 1f; dx * dx + dy * dy }
        assertEquals(d.sorted(), d)
        assertEquals(0, squaresToDraw(on) { _, _ -> true }.size)
        assertEquals(12, squaresToDraw(on) { _, _ -> false }.size)
    }

    @Test
    fun squareBudgetHoldsTheScreenAndItsRing() {
        for ((w, h) in listOf(800 to 1280, 1280 to 800, 1080 to 2400, 1440 to 3200, 320 to 480)) {
            val budget = squareBudget(w, h)
            // The most any zoom shows, from the frames themselves.
            var most = 0
            for (lv in Zoom.SHARPEST until Zoom.LEVELS.size) for (halfWidth in floatArrayOf(192f, 128f, 96f, 64f, 40f, 33f, 17f, 9f, 6f, 4.1f, 3f)) {
                if (Zoom.levelFor(halfWidth) != lv) continue
                for ((fx, fy) in listOf(32f to 16f, 5.3f to 28.7f, 0f to 0f)) {
                    val on = squaresOn(GlFrame.of(view(0, halfWidth, fx, fy), w, h), lv)
                    most = max(most, (on[1] - on[0] + 1) * (on[3] - on[2] + 1))
                }
            }
            assertTrue(budget >= most, "${w}x$h budget $budget, screen $most")
            // Past the memory cap only for a screen that shows about that many.
            val cap = (SQUARE_MEMORY / (SQUARE_SIDE * SQUARE_SIDE * 4)).toInt()
            assertTrue(budget <= cap || budget <= most * 3 / 2, "${w}x$h budget $budget over memory")
        }
        // A phone screen keeps its ring and the next level out too.
        assertTrue(squareBudget(800, 1280) >= 30 + 26 + 12)
    }

    @Test
    fun squaresReadTheWeatherRowsOfTheirTiles() {
        for (turn in 0 until 4) {
            val v = View(world.width, world.height).also { it.turnBy(turn) }
            val f = GlFrame.of(view(turn, 64f, 20f, 10f), 800, 1280)
            val lv = Zoom.levelFor(64f)
            val on = squaresOn(f, lv)
            for (cy in on[2]..on[3]) for (cx in on[0]..on[1]) {
                val cols = visibleColumns(squareFrame(f, lv, cx, cy))
                val rows = weatherRows(cols, f.viewHeight, turn, world.height)
                for (vy in 0 until f.viewHeight) for (vx in cols[2 * vy]..cols[2 * vy + 1]) {
                    val y = v.worldY(vx, vy)
                    assertTrue(y >= rows[0] && y + 1 <= rows[1], "turn $turn square $cx,$cy tile $vx,$vy row $y outside ${rows[0]}..${rows[1]}")
                }
                assertTrue(rows[0] >= 0 && rows[1] <= world.height)
            }
        }
        val w = GroundWeather(4, 6, ByteArray(5 * 7 * GroundWeather.TEXEL), 5, intArrayOf(0, 0, 5, 3, 0, 0, 0))
        assertTrue(weatherSame(w, 5, intArrayOf(0, 6)))
        assertTrue(weatherSame(w, 4, intArrayOf(3, 6)))
        assertTrue(!weatherSame(w, 4, intArrayOf(0, 2)))
        assertTrue(!weatherSame(w, 2, intArrayOf(3, 3)))
        // None read: nothing to change.
        assertTrue(weatherSame(w, 0, intArrayOf(1, 0)))
    }

    @Test
    fun cullingKeepsEverySpriteOnScreen() {
        val w = 1280
        val h = 800
        for (turn in 0 until 4) {
            val regions = regions(turn)
            for (halfWidth in floatArrayOf(96f, 64f, 16f, 6f)) for ((fx, fy) in listOf(32f to 16f, 5f to 28f, 60f to 3f, 0f to 0f)) {
                val frame = GlFrame.of(view(turn, halfWidth, fx, fy), w, h)
                val runs = DrawRuns()
                val columns = visibleColumns(frame)
                runs.build(regions, columns)
                val drawn = HashSet<Long>()
                for (k in 0 until runs.size) for (s in runs.first(k) until runs.first(k) + runs.count(k)) drawn += runs.region(k).toLong() shl 32 or s.toLong()
                var on = 0
                for (r in regions) for (s in 0 until r!!.count) {
                    val o = s * SpriteRecorder.STRIDE
                    var left = r.data[o + 1].toFloat()
                    var top = r.data[o + 2].toFloat()
                    var right = left + r.data[o + 3]
                    var bottom = top + r.data[o + 4]
                    if (r.data[o] == SpriteRecorder.GROUND) {
                        // A tile's diamond from its highest corner down to the foot of an earth face.
                        val g = r.ground
                        val go = r.data[o + 1] * GroundRecord.STRIDE
                        val vx = GroundRecord.vx(g, go)
                        val vy = GroundRecord.vy(g, go)
                        val high = (0 until 4).maxOf { GroundRecord.height(g, it, go) }
                        val hw = SOURCE_HALF_WIDTH
                        left = (vx - vy - 1) * hw
                        right = (vx - vy + 1) * hw
                        top = (vx + vy) * hw / 2 - high * hw / 4
                        bottom = (vx + vy + 2) * hw / 2 + 2 * hw / 4
                    }
                    val x0 = frame.screenX(left)
                    val y0 = frame.screenY(top)
                    val x1 = frame.screenX(right)
                    val y1 = frame.screenY(bottom)
                    if (x1 <= 0f || y1 <= 0f || x0 >= w || y0 >= h) continue
                    on++
                    assertTrue(r.index.toLong() shl 32 or s.toLong() in drawn, "turn $turn zoom $halfWidth focus $fx,$fy region ${r.index} sprite $s at $x0,$y0 left out")
                }
                // Something to check, and fewer tiles kept than the whole map when zoomed in.
                if (fx == 32f && fy == 16f) assertTrue(on > 0, "turn $turn zoom $halfWidth: nothing on screen")
                val kept = (0 until frame.viewHeight).sumOf { maxOf(0, columns[2 * it + 1] - columns[2 * it] + 1) }
                if (halfWidth >= 64f) assertTrue(kept < world.width * world.height / 2, "turn $turn zoom $halfWidth focus $fx,$fy kept $kept tiles")
            }
        }
    }

    @Test
    fun framePlacesPointsAsTheViewDoes() {
        val w = 1280f
        val h = 800f
        for (turn in 0 until 4) for (halfWidth in floatArrayOf(96f, 64f, 37.5f, 3f)) {
            val view = view(turn, halfWidth, 17.3f, 29.6f)
            val frame = GlFrame.of(view, w.toInt(), h.toInt())
            assertEquals(turn, frame.turn)
            for ((vx, vy, ht) in listOf(Triple(0f, 0f, 0f), Triple(12.5f, 3.25f, 4f), Triple(39f, 47f, 20f), Triple(7f, 30f, 1.5f))) {
                // A view point at height ht in source level pixels from view point 0, 0, as the baker lays it out.
                val px = (vx - vy) * SOURCE_HALF_WIDTH
                val py = (vx + vy) * SOURCE_HALF_WIDTH / 2 - ht * SOURCE_HALF_WIDTH / 4
                // Within the half pixel the origin's rounded by.
                assertEquals(view.screenX(vx, vy, w), frame.screenX(px), 0.51f, "turn $turn x")
                assertEquals(view.screenY(vx, vy, ht, h), frame.screenY(py), 0.51f, "turn $turn y")
            }
        }
    }

    @Test
    fun turnsPutAWorldPointWhereTheViewDoes() {
        // A world point seen from each turn lands where View puts it, through the turned view coordinates the regions use.
        val wx = 30.5f
        val wy = 11.5f
        for (turn in 0 until 4) {
            val view = view(turn, 64f, wx, wy)
            val frame = GlFrame.of(view, 1000, 600)
            val vx = view.toViewX(wx, wy)
            val vy = view.toViewY(wx, wy)
            // The focus is the screen's middle.
            assertEquals(500f, frame.screenX((vx - vy) * SOURCE_HALF_WIDTH), 0.51f)
            assertEquals(300f, frame.screenY((vx + vy) * SOURCE_HALF_WIDTH / 2), 0.51f)
            assertEquals(if (turn % 2 == 0) world.width else world.height, frame.viewWidth)
        }
    }

    @Test
    fun atlasPagesHoldEverySpriteWhole() {
        val pages = AtlasPages(1024, 9136, 400, 8192)
        assertEquals(2, pages.layers)
        assertTrue(pages.pageHeight <= 8192)
        for (h in intArrayOf(1, 100, 400)) for (sy in 0..9136 - h) {
            val layer = pages.layerOf(sy)
            val (y0, rows) = pages.rowsOf(layer)
            assertTrue(sy >= y0 && sy + h <= y0 + rows && pages.rowIn(sy) + h <= pages.pageHeight, "sy $sy h $h layer $layer")
        }
        val one = AtlasPages(1024, 5000, 400, 8192)
        assertEquals(1, one.layers)
        assertEquals(5000, one.pageHeight)
        // The real sheets' tallest sprites fit a page.
        assertTrue(AtlasPages.tallestStatic(SOURCE_LEVEL) in 1 until 4096)
        assertTrue(AtlasPages.tallestShadow(SOURCE_LEVEL) in 1 until 4096)
    }
}
