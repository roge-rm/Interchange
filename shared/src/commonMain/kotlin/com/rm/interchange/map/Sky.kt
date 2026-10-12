package com.rm.interchange.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.Front
import com.rm.interchange.sim.World
import com.rm.interchange.sim.skyAt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.TimeSource

private val started = TimeSource.Monotonic.markNow()

/** The fronts where they are now, slid on from where the day began. */
private fun slid(fronts: List<Front>, dayPart: Float): List<Front> = fronts.map { f ->
    f.copy().also {
        it.x = (f.x + f.dx * dayPart).toInt()
        it.y = (f.y + f.dy * dayPart).toInt()
    }
}

/**
 * The weather over the map: a shade of cloud under each low, fog banks, and
 * rain or snow falling wherever it's falling on the map below. The drops and
 * flakes are a fixed set moving down the screen, each shown only where the
 * weather under it would let it fall, so showers move across with their front.
 */
internal fun DrawScope.drawSky(world: World, view: View, moving: Moving) {
    val weather = moving.now.weather ?: return
    val dayPart = ((moving.now.tick % Calendar.TICKS_PER_DAY) + moving.between) / Calendar.TICKS_PER_DAY
    val fronts = slid(weather.fronts, dayPart)
    val w = size.width
    val h = size.height
    // A front's circle on the map is an ellipse on screen, twice as wide as tall.
    for (f in fronts) {
        val wx = f.x / 256f
        val wy = f.y / 256f
        val vx = view.toViewX(wx, wy)
        val vy = view.toViewY(wx, wy)
        val cx = view.screenX(vx, vy, w)
        val cy = view.screenY(vx, vy, 4f, h)
        val rx = f.radius * view.halfWidth * SQRT2
        val ry = f.radius * view.halfHeight * SQRT2
        if (cx + rx < 0 || cx - rx > w || cy + ry < 0 || cy - ry > h) continue
        val colour = when (f.kind) {
            Front.FOG -> Color(0xffeef1f3).copy(alpha = 0.55f)
            Front.STORM -> Color(0xff283038).copy(alpha = 0.22f)
            else -> Color(0xff384450).copy(alpha = 0.12f)
        }
        val brush = Brush.radialGradient(listOf(colour, colour.copy(alpha = 0f)), Offset(cx, cy), rx)
        // Drawn as a circle squashed to the view's ellipse.
        withTransform({ scale(1f, ry / rx, Offset(cx, cy)) }) { drawCircle(brush, rx, Offset(cx, cy)) }
    }
    if (fronts.none { it.kind != Front.FOG }) return
    val region = world.settings.region
    // The weather under the drops, on a grid of blocks [SKY_BLOCK] tiles a side, worked out as needed and kept for a
    // sixteenth of a day as the fronts move on: a front is tens of tiles across, so a block's middle speaks for it.
    val across = (world.width + SKY_BLOCK - 1) / SKY_BLOCK
    val down = (world.height + SKY_BLOCK - 1) / SKY_BLOCK
    if (skyGrid.size != across * down) skyGrid = IntArray(across * down)
    val stamp = weather.day * 16 + (dayPart * 16).toInt()
    if (stamp != skyStamp) {
        skyStamp = stamp
        skyGrid.fill(0)
    }
    // The block's weather packed: set, snowing, freezing, then the precipitation.
    fun skyOn(px: Float, py: Float): Int {
        val a = (px - w / 2) / view.halfWidth
        val b = (py - h / 2 + 4 * view.step) / view.halfHeight
        val vx = view.focusViewX() + (a + b) / 2
        val vy = view.focusViewY() + (b - a) / 2
        val tx = view.toWorldX(vx, vy).toInt()
        val ty = view.toWorldY(vx, vy).toInt()
        if (!world.inside(tx, ty)) return 0
        val cell = ty / SKY_BLOCK * across + tx / SKY_BLOCK
        var v = skyGrid[cell]
        if (v == 0) {
            val x = minOf(world.width - 1, tx / SKY_BLOCK * SKY_BLOCK + SKY_BLOCK / 2)
            val y = minOf(world.height - 1, ty / SKY_BLOCK * SKY_BLOCK + SKY_BLOCK / 2)
            val sky = skyAt(region, weather.year, weather.doy, weather.anomaly, fronts, world.height, x, y, world.heightAt(x, y))
            v = 1 or (if (sky.snowing) 2 else 0) or (if (sky.freezing) 4 else 0) or (sky.precipitation.coerceAtLeast(0) shl 3)
            skyGrid[cell] = v
        }
        return v
    }
    val t = started.elapsedNow().inWholeMilliseconds / 1000f
    val unit = (w + h) / 1400f
    // Gathered first and drawn in one go for each kind, which is far quicker than a line at a time.
    flakes.clear(); rains.clear(); sleets.clear()
    for (i in 0 until DROPS) {
        val r1 = hash(i)
        val r2 = hash(i + 7919)
        val r3 = hash(i + 15401)
        val x0 = r1 * w
        val speed = 0.7f + r3 * 0.6f
        // Snow drifts down slowly with a sway, rain streaks; each is shown where the weather under it is that.
        val snowY = (r2 * h + t * SNOW_SPEED * unit * speed) % h
        val snowX = x0 + sin(t * 1.3f + i) * 6f * unit
        val snow = skyOn(snowX, snowY)
        if (snow and 2 != 0 && r3 * FULL < snow ushr 3) {
            flakes += Offset(snowX, snowY)
            continue
        }
        val rainY = (r2 * h + t * RAIN_SPEED * unit * speed) % h
        val rain = skyOn(x0, rainY)
        if (rain == 0 || rain and 2 != 0 || rain ushr 3 <= 0 || r3 * FULL > rain ushr 3) continue
        val into = if (rain and 4 != 0) sleets else rains
        into += Offset(x0, rainY)
        into += Offset(x0 - 3f * unit, rainY + 14f * unit)
    }
    if (flakes.isNotEmpty()) drawPoints(flakes, PointMode.Points, SNOWFLAKE, strokeWidth = 3.2f * unit, cap = StrokeCap.Round)
    if (rains.isNotEmpty()) drawPoints(rains, PointMode.Lines, RAIN, strokeWidth = 1.2f * unit)
    if (sleets.isNotEmpty()) drawPoints(sleets, PointMode.Lines, SLEET, strokeWidth = 1.2f * unit)
}

/** The drops on screen this frame, kept between frames to save making new lists. */
private val flakes = ArrayList<Offset>()
private val rains = ArrayList<Offset>()
private val sleets = ArrayList<Offset>()


/** The weather worked out for blocks under the drops, and when for. Drawn on the main thread only. */
private var skyGrid = IntArray(0)
private var skyStamp = -1
private const val SKY_BLOCK = 4

/** A number from 0 until 1 for [i], the same every time. */
private fun hash(i: Int): Float {
    var h = i * 374761393 + 668265263
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xffff) / 65536f
}

private val SQRT2 = sqrt(2f)
private const val DROPS = 700
private const val SNOW_SPEED = 60f
private const val RAIN_SPEED = 900f
/** Precipitation, in hundredths of a mm a day, at which every drop shows. */
private const val FULL = 2500f
private val SNOWFLAKE = Color(0xf0ffffff)
private val RAIN = Color(0x99b8c8d8)
private val SLEET = Color(0xccd8e4ee)
