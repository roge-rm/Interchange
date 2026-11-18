package com.rm.interchange.map.gl

import com.rm.interchange.map.View
import com.rm.interchange.map.WeatherCopy
import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.Climate
import com.rm.interchange.sim.NewGame
import com.rm.interchange.sim.Region
import com.rm.interchange.sim.World
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pans back and forth at the widest zoom with recordings finishing late and out of order, as [GlScene] runs them,
 * and checks every tile a region says it holds has the sprites a whole record of it has.
 */
class KeepChurnTest {
    private companion object {
        const val SIZE = 256
        const val SW = 320
        const val SH = 200
        val world by lazy { World(NewGame(SIZE, SIZE, 11, 1930, Region.MIXED, towns = 4, industries = 4, rivals = 0)) }
        val weather by lazy { WeatherCopy.of(world, 0) }
        val year by lazy { Calendar.year(world.startYear, world.day) }
        val look by lazy { Climate.look(world.settings.region, year, Calendar.dayOfYear(world.startYear, world.day)) }
    }

    private class Recording(val i: Int, val version: Long, val base: RegionSprites?, val r: RegionSprites, val job: CompletableJob)

    private fun frame(turn: Int, hw: Float, fx: Float, fy: Float) = GlFrame.of(View(SIZE, SIZE).also {
        it.turnBy(turn)
        it.halfWidth = hw
        it.focusX = fx
        it.focusY = fy
    }, SW, SH)

    /** Region [r]'s packed ints for tile ([vx], [vy]) in [pass]. */
    private fun tile(r: RegionSprites, pass: Int, vy: Int, vx: Int): IntArray {
        val a = r.start(pass, vy, vx)
        val b = r.start(pass, vy, vx + 1)
        val f = r.firstPass
        val stride = if (pass == 0) GroundRecord.STRIDE else SPRITE_INTS
        val from = if (pass == 0) a * GroundRecord.STRIDE else f * GroundRecord.STRIDE + (a - f) * SPRITE_INTS
        return r.packed!!.copyOfRange(from, from + (b - a) * stride)
    }

    /** Fails unless every tile [r] holds has what [whole] has there, in each pass. */
    private fun check(r: RegionSprites, whole: RegionSprites, what: String) {
        for (vy in r.row0 until r.row1) {
            val k = 2 * (vy - r.row0)
            val c0 = r.cols?.get(k) ?: 0
            val c1 = r.cols?.get(k + 1) ?: (r.columns - 1)
            for (vx in c0..c1) {
                // Every tile has a piece of ground, which the renderer's glempty check counts on.
                if (r.start(0, vy, vx + 1) == r.start(0, vy, vx)) fail("$what: tile $vx, $vy has no ground")
            }
            for (vx in c0..c1) for (pass in 0 until PASSES) {
                if (!tile(r, pass, vy, vx).contentEquals(tile(whole, pass, vy, vx))) fail("$what: tile $vx, $vy pass $pass differs from a whole record")
            }
        }
        // The last start is the end of the sprites.
        assertEquals(r.count, r.start(PASSES, r.row0, 0), "$what: count")
    }

    @Test
    fun regionsHoldWhatTheySayThroughPansWithRecordingsLateAndOutOfOrder() {
        for (turn in 0 until 4) for (seed in 0 until 3) churn(turn, seed)
    }

    private fun churn(turn: Int, seed: Int) {
        val rnd = Random(seed * 31 + turn)
        val vw = if (turn % 2 == 0) world.width else world.height
        val vh = if (turn % 2 == 0) world.height else world.width
        val t = KeptTurn(turn, vw, vh)
        val wholes = HashMap<Int, RegionSprites>()
        fun whole(i: Int) = wholes.getOrPut(i) { recordRegion(world, turn, i, look, year, weather) }
        val running = ArrayList<Recording>()
        var merged = 0
        var fresh = 0
        var refused = 0
        var cancelled = 0
        var rekeeps = 0
        fun start(i: Int, cols: IntArray?) {
            val v = regionVersion(world, turn, i, look, year)
            val base = t.base(i, v, look, year, weather.baked)
            t.begin(i, v)
            val job = Job()
            t.regionJobs[i] = job
            t.jobs += job
            running += Recording(i, v, base, recordKept(world, turn, i, look, year, weather, null, v, cols, base), job)
        }
        fun finish(n: Int) {
            running.shuffle(rnd)
            repeat(minOf(n, running.size)) {
                val j = running.removeAt(running.size - 1)
                if (j.job.isCancelled) cancelled++
                else if (t.put(j.i, j.version, j.r, j.base)) { if (j.base != null) merged++ else fresh++ } else refused++
                j.job.complete()
            }
        }
        var hw = View.MIN_HALF_WIDTH
        var x = SIZE / 2f
        var y = SIZE / 2f
        var f = frame(turn, hw, x, y)
        assertFalse(keepsWholeMap(f))
        t.keepRound(f)
        // Before the map first shows only the screen and a quarter round it is recorded.
        val round = GlFrame(f.turn, f.originX + SW / 4f, f.originY + SH / 4f, f.scale, SW * 3 / 2, SH * 3 / 2, f.viewWidth, f.viewHeight)
        val shown = visibleColumns(round)
        for (i in t.regions.indices) {
            val row0 = i * REGION_ROWS
            val row1 = minOf(vh, row0 + REGION_ROWS)
            if ((row0 until row1).none { shown[2 * it] <= shown[2 * it + 1] }) continue
            start(i, shown.copyOfRange(2 * row0, 2 * row1))
        }
        finish(running.size)
        var dx = 0f
        var dy = 0f
        for (step in 0 until 600) {
            // A swipe in a new direction every few steps, often turning back.
            if (step % 6 == 0) {
                val a = rnd.nextFloat() * 6.2832f
                val speed = (0.1f + rnd.nextFloat() * 0.2f) * SW / hw / 2
                dx = kotlin.math.cos(a) * speed
                dy = kotlin.math.sin(a) * speed
            }
            if (step % 97 == 50) hw = if (hw == View.MIN_HALF_WIDTH) 4.5f else View.MIN_HALF_WIDTH
            x = (x + dx).coerceIn(0f, SIZE.toFloat())
            y = (y + dy).coerceIn(0f, SIZE.toFloat())
            f = frame(turn, hw, x, y)
            if (t.keepRound(f)) rekeeps++
            // fill: every region short and not recording starts.
            for (i in t.regions.indices) if (t.short(i)) start(i, t.slice(i))
            finish(rnd.nextInt(0, 4))
            for (i in t.regions.indices) {
                val r = t.regions[i] ?: continue
                check(r, whole(i), "turn $turn seed $seed step $step region $i")
            }
        }
        println("churn turn $turn seed $seed: rekeeps $rekeeps merged $merged fresh $fresh refused $refused cancelled $cancelled")
        assertTrue(rekeeps > 10 && merged > 50 && refused > 0, "turn $turn seed $seed didn't churn")
    }
}
