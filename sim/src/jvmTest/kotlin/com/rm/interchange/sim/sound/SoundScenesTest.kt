package com.rm.interchange.sim.sound

import com.rm.interchange.sim.Front
import com.rm.interchange.sim.NewGame
import com.rm.interchange.sim.Traction
import com.rm.interchange.sim.Trees
import com.rm.interchange.sim.World
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Short scenes run through [WorldSound] a frame every 200 ms, as the game does, for listening to:
 * a city at three zooms, a winter northern line and a busy airport. With
 * -Pinterchange.soundScenes=DIR each is written to DIR as a script that
 * `build/sound-host/render scene` plays through the synth (see tools/sound/build.sh).
 */
class SoundScenesTest {
    private val step = 0.2f

    private class Script {
        val text = StringBuilder()
        var held = 0
        var shots = 0

        fun add(t: Float, out: WorldSound.Frame) {
            text.append("F ").append(t).append('\n')
            for (k in 0 until out.count) {
                text.append("H ").append(out.keys[k]).append(' ').append(out.recipes[k]).append(' ').append(out.flags[k])
                for (j in 0 until SharedParams.COUNT) text.append(' ').append(out.params[k * SharedParams.COUNT + j])
                text.append('\n')
                held++
            }
            for (s in out.shots) {
                text.append("S ").append(s.recipe).append(' ').append(s.flags).append(' ').append(s.delay)
                for (v in s.params) text.append(' ').append(v)
                text.append('\n')
                shots++
            }
        }
    }

    private fun write(name: String, script: Script) {
        val dir = System.getProperty("interchange.soundScenes") ?: return
        File(dir).mkdirs()
        File(dir, "$name.txt").writeText(script.text.toString())
    }

    private fun flat(width: Int, height: Int, year: Int): World {
        val w = World(NewGame(width = width, height = height, seed = 3, startYear = year))
        w.weather.fronts.clear()
        w.weather.anomaly = 0
        w.weather.wind = 8
        for (i in w.corners.indices) w.corners[i] = 4
        w.trees.fill(0)
        // Only what the scene puts there.
        w.house.fill(0)
        w.industryOf.fill(0)
        return w
    }

    /** A loop of road around the middle, [n] cars evenly spaced and going round. */
    private fun cars(t: Float, n: Int, cx: Float, cy: Float, r: Float, traction: Int, idFrom: Int = 100) = (0 until n).map { i ->
        val a = t * 0.12f + i * 2f * PI.toFloat() / n
        WorldSound.Mover(WorldSound.ROAD, idFrom + i, cx + r * cos(a), cy + r * sin(a), 0.6f, traction)
    }

    /** A 1975 city: houses, works on the edge, cars, a streetcar, a station and trains. */
    private fun city(half: Float): Script {
        val w = flat(160, 160, 1975)
        for (y in 64 until 96) for (x in 64 until 96) if ((x + y) % 5 != 0) w.house[w.index(x, y)] = 1
        for (y in 98 until 104) for (x in 70 until 78) w.industryOf[w.index(x, y)] = 1
        for (y in 50 until 60) for (x in 50 until 110 step 3) w.trees[w.index(x, y)] = Trees.pack(Trees.MAPLE, 2).toByte()
        val sound = WorldSound(w)
        sound.survey()
        val at = WorldSound.Listener(80f, 80f, half, half, 0)
        val station = WorldSound.StationSpot(84f, 80f, false)
        val out = WorldSound.Frame()
        val script = Script()
        var t = 0f
        while (t < 20f) {
            val movers = ArrayList<WorldSound.Mover>()
            movers += cars(t, 8, 80f, 80f, 6f, Traction.DIESEL)
            movers += cars(t, 3, 80f, 80f, 14f, Traction.DIESEL, idFrom = 200)
            movers += WorldSound.Mover(WorldSound.TRAM, 300, 70f + (t * 0.8f) % 20f, 78f, 0.5f, Traction.ELECTRIC)
            // A train waits at the station, then pulls out; a freight goes through.
            val pulling = t >= 6f
            val trainX = if (pulling) 84f + (t - 6f) * (t - 6f) * 0.25f else 84f
            movers += WorldSound.Mover(WorldSound.TRAIN, 1, trainX, 80.5f, if (pulling) ((t - 6f) / 8f).coerceAtMost(1f) else 0f, Traction.DIESEL, waiting = !pulling)
            movers += WorldSound.Mover(WorldSound.TRAIN, 2, 40f + t * 4f, 81.5f, 0.8f, Traction.ELECTRIC)
            sound.frame(at, 14f, step, out, movers, IntArray(0), listOf(station))
            script.add(t, out)
            t += step
        }
        return script
    }

    /** 1958, January in the north: snow and wind, spruce, a diesel freight over a flashing crossing. */
    private fun northernLine(): Script {
        val w = flat(96, 96, 1958)
        w.tick = w.dayTicks * 15L
        w.weather.wind = 45
        w.weather.anomaly = -60
        w.weather.fronts += Front(Front.LOW, 48 * 256 + 128, 48 * 256 + 128, 256, 0, 40, 8000, -400, 4, false)
        for (y in 0 until 96) for (x in 0 until 96) if ((x * 7 + y * 13) % 4 == 0 && y !in 46..50) w.trees[w.index(x, y)] = Trees.pack(Trees.SPRUCE, 3).toByte()
        val sound = WorldSound(w)
        sound.survey()
        val at = WorldSound.Listener(48f, 48f, 10f, 10f, 0)
        val crossing = w.index(52, 48)
        val out = WorldSound.Frame()
        val script = Script()
        var t = 0f
        while (t < 24f) {
            val x = 20f + t * 2.4f
            val train = WorldSound.Mover(WorldSound.TRAIN, 5, x, 48.5f, 0.7f, Traction.DIESEL)
            // The lights come on as it nears and go off once it's past.
            val lit = if (x in 40f..60f) intArrayOf(crossing) else IntArray(0)
            sound.frame(at, 15f, step, out, listOf(train), lit)
            script.add(t, out)
            t += step
        }
        return script
    }

    /** 2045 at a busy airport: a jet rolling and taking off, a turboprop, a helicopter and drones. */
    private fun airport(): Script {
        val w = flat(128, 128, 2045)
        val sound = WorldSound(w)
        sound.survey()
        val at = WorldSound.Listener(64f, 64f, 12f, 12f, 0)
        val out = WorldSound.Frame()
        val script = Script()
        var t = 0f
        while (t < 24f) {
            val movers = ArrayList<WorldSound.Mover>()
            // The jet rolls past the listener, speeding up, and climbs away.
            val jetSpeed = (t / 10f).coerceIn(0.1f, 1f)
            movers += WorldSound.Mover(WorldSound.AIRCRAFT, 1, 40f + t * t * 0.35f, 66f, jetSpeed, Traction.STEAM, jet = true)
            // A turboprop lands toward the end.
            if (t > 12f) movers += WorldSound.Mover(WorldSound.AIRCRAFT, 2, 110f - (t - 12f) * 3.5f, 62f, 0.6f, Traction.STEAM)
            movers += WorldSound.Mover(WorldSound.AIRCRAFT, 3, 60f + 4f * sin(t * 0.3f), 58f, 0.5f, Traction.STEAM, sound = WorldSound.AIR_HELI)
            movers += WorldSound.Mover(WorldSound.AIRCRAFT, 4, 66f + 3f * cos(t * 0.5f), 62f + 2f * sin(t * 0.5f), 0.6f, Traction.BATTERY, sound = WorldSound.AIR_DRONE)
            movers += cars(t, 5, 64f, 72f, 5f, Traction.BATTERY)
            sound.frame(at, 11f, step, out, movers, IntArray(0), listOf(WorldSound.StationSpot(64f, 70f, false)))
            script.add(t, out)
            t += step
        }
        return script
    }

    @Test
    fun cityAtThreeZooms() {
        for ((name, half) in listOf("city_close" to 6f, "city_middle" to 20f, "city_far" to 60f)) {
            val s = city(half)
            assertTrue(s.held > 0, name)
            assertTrue(s.text.contains(" ${Recipes.CITY} "), "$name has no town hum")
            write(name, s)
        }
    }

    @Test
    fun winterNorthernLine() {
        val s = northernLine()
        assertTrue(s.text.contains("H ${WorldSound.KEY_SIREN} "), "no crossing bell")
        assertTrue(s.shots > 0, "no horn at the crossing")
        write("winter_northern_line", s)
    }

    @Test
    fun busyAirport() {
        val s = airport()
        assertTrue(s.text.contains(" ${Recipes.JET} "), "no jet")
        write("busy_airport", s)
    }
}
