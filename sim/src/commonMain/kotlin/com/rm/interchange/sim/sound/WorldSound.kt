package com.rm.interchange.sim.sound

import com.rm.interchange.sim.Calendar
import com.rm.interchange.sim.Climate
import com.rm.interchange.sim.Front
import com.rm.interchange.sim.News
import com.rm.interchange.sim.Traction
import com.rm.interchange.sim.Trees
import com.rm.interchange.sim.World
import com.rm.interchange.sim.skyAt
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The land's sound from where the map is looking. [survey] adds up the shore
 * and the trees by chunk, and [frame] turns what's near the view into the
 * synth's held sounds, panned across the screen, plus the odd clap of thunder.
 * Trains, traffic and the rest come in as plain positions on [frame]. A train
 * or a ship that sets off, and a train that reaches a crossing, get one whistle.
 */
class WorldSound(private val world: World) {
    private val across = world.width / World.CHUNK
    private val down = world.height / World.CHUNK
    private val trees = Layer(across * down)
    private val shore = Layer(across * down)
    private val random = Random(world.settings.seed)
    /** Trains and ships that waited and have not set off, by [voiceKey]. */
    private val wasWaiting = HashSet<Int>()
    /** The flashing crossing a train was already on, by id, so it whistles once. */
    private val crossingTile = HashMap<Int, Int>()

    /** Adds up the shore and the trees in each chunk. Every couple of seconds is plenty. */
    fun survey() {
        trees.clear()
        shore.clear()
        val w = world
        for (y in 0 until w.height) for (x in 0 until w.width) {
            val c = x / World.CHUNK + y / World.CHUNK * across
            val n = Trees.count(w.trees[w.index(x, y)].toInt())
            if (n > 0) trees.add(c, n.toFloat(), x + 0.5f, y + 0.5f)
            if (w.isWater(x, y) && besideLand(x, y)) shore.add(c, 1f, x + 0.5f, y + 0.5f)
        }
    }

    /** Water with land next to it. */
    private fun besideLand(x: Int, y: Int): Boolean {
        val w = world
        for ((dx, dy) in NEXT) {
            val nx = x + dx
            val ny = y + dy
            if (w.inside(nx, ny) && !w.isWater(nx, ny)) return true
        }
        return false
    }

    /**
     * The middle of the view and how far it reaches each way, in tiles, and which
     * way the map is turned, 0 to 3. A larger reach is zoomed further out.
     */
    class Listener(val x: Float, val y: Float, val halfWidth: Float, val halfHeight: Float, val turn: Int)

    /**
     * A vehicle the view can hear. [speed] is 0 standing and 1 at its top speed.
     * [jet] is an aircraft whose top speed is at least [JET_KMH].
     * [waiting] is standing at a station or a dock.
     */
    class Mover(
        val kind: Int,
        val id: Int,
        val x: Float,
        val y: Float,
        val speed: Float,
        val traction: Int,
        val jet: Boolean = false,
        val inDepot: Boolean = false,
        val wrecked: Boolean = false,
        val waiting: Boolean = false,
    )

    /** A station's middle. A [waypoint] has no crowd. */
    class StationSpot(val x: Float, val y: Float, val waypoint: Boolean)

    /** A one-shot to fire now, or [delay] seconds from now. [flags] is [VoiceFlags]. */
    class Shot(val recipe: Int, val params: FloatArray, val delay: Float = 0f, val flags: Int = 0)

    /** The held sounds this frame, laid out as the synth takes them, and the one-shots. */
    class Frame(capacity: Int = MAX_HELD) {
        var count = 0
        val keys = IntArray(capacity)
        val recipes = IntArray(capacity)
        val flags = IntArray(capacity)
        val params = FloatArray(capacity * SharedParams.COUNT)
        val shots = ArrayList<Shot>()

        /** How loud the sound with [key] is, or 0 if it isn't playing. */
        fun loudness(key: Int): Float {
            for (k in 0 until count) if (keys[k] == key) return params[k * SharedParams.COUNT + SharedParams.GAIN]
            return 0f
        }

        /** Where the sound with [key] sits, right of the screen positive, or 0 if it isn't playing. */
        fun pan(key: Int): Float {
            for (k in 0 until count) if (keys[k] == key) return params[k * SharedParams.COUNT + SharedParams.PAN]
            return 0f
        }

        internal fun clear() {
            count = 0
            params.fill(0f)
            shots.clear()
        }

        internal fun add(key: Int, recipe: Int, gain: Float, pan: Float, lowpass: Float, vararg p: Float, pitch: Float = 0f) {
            if (count >= keys.size || gain < QUIET) return
            keys[count] = key
            recipes[count] = recipe
            flags[count] = 0
            val o = count * SharedParams.COUNT
            for ((j, v) in p.withIndex()) params[o + j] = v
            params[o + SharedParams.GAIN] = gain
            params[o + SharedParams.PAN] = pan.coerceIn(-1f, 1f)
            params[o + SharedParams.LOWPASS] = lowpass
            params[o + SharedParams.PITCH] = pitch
            count++
        }
    }

    /** How much of [layer] is heard, and the pan of where it sits in view space. */
    private fun heard(layer: Layer, at: Listener, panOut: FloatArray): Float {
        val reach = max(at.halfWidth, at.halfHeight).coerceAtLeast(4f)
        val lx = viewX(at.x, at.y, at.turn)
        val ly = viewY(at.x, at.y, at.turn)
        var total = 0f
        var pan = 0f
        // A chunk just off the edge still counts, so a shore one chunk over isn't dropped.
        val pad = reach * 3f + World.CHUNK
        val cx0 = ((at.x - pad) / World.CHUNK).toInt().coerceAtLeast(0)
        val cx1 = ((at.x + pad) / World.CHUNK).toInt().coerceAtMost(across - 1)
        val cy0 = ((at.y - pad) / World.CHUNK).toInt().coerceAtLeast(0)
        val cy1 = ((at.y + pad) / World.CHUNK).toInt().coerceAtMost(down - 1)
        for (cy in cy0..cy1) for (cx in cx0..cx1) {
            val i = cx + cy * across
            val v = layer.amount[i]
            if (v <= 0f) continue
            val vx = viewX(layer.sumX[i] / v, layer.sumY[i] / v, at.turn)
            val vy = viewY(layer.sumX[i] / v, layer.sumY[i] / v, at.turn)
            val dx = vx - lx
            val dy = vy - ly
            val d = sqrt(dx * dx + dy * dy) / reach
            val w = v / (1f + d * d * 2f)
            total += w
            pan += w * (dx / max(1f, at.halfWidth)).coerceIn(-1f, 1f)
        }
        panOut[0] = if (total > 0f) (pan / total * PAN_WIDTH).coerceIn(-1f, 1f) else 0f
        // A wider view takes in more, all of it further off, so it's about as loud.
        return total * World.CHUNK / reach
    }

    /** From nothing to nearly full as [amount] passes [ref]. */
    private fun level(amount: Float, ref: Float) = 1f - exp(-amount / ref)

    /**
     * This moment's sound into [out]. [hour] is 0 to 24, [dt] seconds since the last frame.
     * [movers], [crossings] and [stations] are already copied off the game thread.
     * [crossings] are tile indexes whose lights are on.
     */
    fun frame(
        at: Listener,
        hour: Float,
        dt: Float,
        out: Frame,
        movers: List<Mover> = emptyList(),
        crossings: IntArray = IntArray(0),
        stations: List<StationSpot> = emptyList(),
    ) {
        out.clear()
        val x = at.x.toInt().coerceIn(0, world.width - 1)
        val y = at.y.toInt().coerceIn(0, world.height - 1)
        val h = (world.heightAt(x, y) + world.heightAt(x + 1, y) + world.heightAt(x + 1, y + 1) + world.heightAt(x, y + 1)) / 4
        val sky = skyAt(
            world.settings.region,
            Calendar.year(world.startYear, world.day),
            Calendar.dayOfYear(world.startYear, world.day),
            world.weather.anomaly,
            world.weather.fronts,
            world.height,
            x, y, h,
        )
        val snow = sky.precipitation > 0 && sky.temperature <= 0
        val muffle = if (snow) SNOW_MUFFLE else 1f
        // 0 leaves the mix open. Snow pulls it down to a few thousand Hz.
        val lowpass = if (snow) SNOW_LOWPASS else 0f
        val pan = FloatArray(1)

        val wind = (world.weather.wind / WIND_FULL).coerceIn(0f, 1f)
        val high = (h / World.MAX_HEIGHT.toFloat()).coerceIn(0f, 1f)
        val windGain = (0.07f + 0.28f * wind * wind) * (0.85f + 0.3f * high) * muffle
        out.add(KEY_WIND, Recipes.WIND, windGain, 0f, lowpass, 1f, 0.25f + 0.75f * wind, 0.35f + 0.65f * wind)

        if (sky.precipitation > 0 && sky.temperature > 0) {
            val wet = (sky.precipitation / RAIN_FULL).coerceIn(0f, 1f)
            out.add(KEY_RAIN, Recipes.RAIN, (0.12f + 0.35f * wet) * muffle, 0f, lowpass, 1f, 0.15f + 0.7f * wet)
        }

        val shoreHeard = heard(shore, at, pan)
        if (shoreHeard > 0f) out.add(KEY_SHORE, Recipes.SEA, level(shoreHeard, SHORE_REF) * 0.35f * muffle, pan[0], lowpass, 1f, 0.35f, 0f)

        val day = daylight(hour)
        val birdsHeard = heard(trees, at, pan)
        if (day > 0f && birdsHeard > 0f) {
            val loud = level(birdsHeard, TREES_REF) * BIRDS_LEVEL * day * birdsSeason() * muffle
            out.add(KEY_BIRDS, Recipes.BIRDS, loud, pan[0], lowpass, 1f, chorus(hour))
        }

        near(at, lowpass, muffle, movers, crossings, stations, out)
        whistles(at, lowpass, muffle, movers, crossings, out)
        thunder(x, y, dt, lowpass, out)
    }

    /** Close enough to [at] to hear. */
    fun hears(at: Listener, x: Float, y: Float) = place(x, y, at) != null

    /**
     * One whistle when a train or a ship that was waiting starts moving, and when a
     * train reaches a flashing crossing. Standing after the wait keeps it in memory.
     */
    private fun whistles(at: Listener, lowpass: Float, muffle: Float, movers: List<Mover>, crossings: IntArray, out: Frame) {
        val stillWaiting = HashSet<Int>()
        val stillCrossing = HashMap<Int, Int>()
        val seen = HashSet<Int>()
        for (m in movers) {
            if ((m.kind != TRAIN && m.kind != SHIP) || m.inDepot || m.wrecked) continue
            val key = voiceKey(m.kind, m.id)
            if (!seen.add(key)) continue
            val here = place(m.x, m.y, at)
            // Waiting records it. Standing at speed 0 keeps that record until it rolls.
            if (m.waiting || (m.speed <= 0f && key in wasWaiting)) stillWaiting.add(key)
            else if (m.speed > 0f && key in wasWaiting && here != null) horn(m, here, lowpass, muffle, out)
            if (m.kind != TRAIN) continue
            val tile = tileOf(m.x, m.y)
            if (tile < 0 || !crossings.contains(tile)) continue
            if (crossingTile[m.id] != tile && here != null) horn(m, here, lowpass, muffle, out)
            stillCrossing[m.id] = tile
        }
        wasWaiting.clear()
        wasWaiting.addAll(stillWaiting)
        crossingTile.clear()
        crossingTile.putAll(stillCrossing)
    }

    /** The tile under [x], [y], or -1 off the map. */
    private fun tileOf(x: Float, y: Float): Int {
        val tx = x.toInt()
        val ty = y.toInt()
        if (!world.inside(tx, ty)) return -1
        return world.index(tx, ty)
    }

    /** A whistle or a horn at [here], panned with the vehicle. */
    private fun horn(m: Mover, here: Placed, lowpass: Float, muffle: Float, out: Frame) {
        val ship = m.kind == SHIP
        val electric = m.traction == Traction.ELECTRIC
        val recipe = if (!ship && electric) Recipes.HORN else Recipes.WHISTLE
        val kind = when {
            ship || electric -> 2f
            m.traction == Traction.DIESEL || m.traction == Traction.DUAL -> 1f
            else -> 0f
        }
        val params = FloatArray(SharedParams.COUNT)
        params[0] = kind
        params[1] = WHISTLE_LENGTH
        params[SharedParams.GAIN] = (0.25f + 0.55f * here.weight) * muffle
        params[SharedParams.PAN] = here.pan
        params[SharedParams.LOWPASS] = lowpass
        out.shots += Shot(recipe, params)
    }

    /** Trains, road traffic, ships, aircraft, a crossing bell and a station crowd. */
    private fun near(
        at: Listener,
        lowpass: Float,
        muffle: Float,
        movers: List<Mover>,
        crossings: IntArray,
        stations: List<StationSpot>,
        out: Frame,
    ) {
        val roads = hear(ROAD, movers, at)
        if (roads.isNotEmpty()) {
            var horses = 0
            var engines = 0
            var electric = 0
            var weight = 0f
            var pan = 0f
            var speed = 0f
            for (c in roads) {
                when (c.mover.traction) {
                    Traction.HORSE -> horses++
                    Traction.ELECTRIC -> {
                        engines++
                        electric++
                    }
                    else -> engines++
                }
                weight += c.weight
                pan += c.weight * c.pan
                speed += c.weight * c.mover.speed.coerceIn(0f, 1f)
            }
            // 0 when they're all horses, 1 when none are. electric is the share of the rest that are electric.
            val motors = engines.toFloat() / (horses + engines)
            val electricMix = if (engines == 0) 0f else electric.toFloat() / engines
            out.add(KEY_TRAFFIC, Recipes.TRAFFIC, pointGain(weight, TRAFFIC_LEVEL) * muffle, pan / weight, lowpass, 1f, motors, electricMix, speed / weight)
        }

        val trams = hear(TRAM, movers, at)
        if (trams.isNotEmpty()) {
            var weight = 0f
            var pan = 0f
            for (c in trams) {
                weight += c.weight
                pan += c.weight * c.pan
            }
            out.add(KEY_TRAM, Recipes.TRAM, pointGain(weight, TRAM_LEVEL) * muffle, pan / weight, lowpass, 1f)
        }

        voices(hear(TRAIN, movers, at), TRAIN_VOICES, lowpass, muffle, out)
        voices(hear(SHIP, movers, at), SHIP_VOICES, lowpass, muffle, out)
        voices(hear(AIRCRAFT, movers, at), AIR_VOICES, lowpass, muffle, out)
        bell(at, lowpass, muffle, crossings, out)
        crowd(at, lowpass, muffle, stations, out)
    }

    /** The nearest [cap] of [found], each its own voice. */
    private fun voices(found: MutableList<Candidate>, cap: Int, lowpass: Float, muffle: Float, out: Frame) {
        if (found.isEmpty()) return
        found.sortBy { it.dist2 }
        val n = found.size.coerceAtMost(cap)
        for (i in 0 until n) {
            val c = found[i]
            val m = c.mover
            val speed = m.speed.coerceIn(0f, 1f)
            when (m.kind) {
                TRAIN -> {
                    val gain = pointGain(c.weight, TRAIN_LEVEL) * muffle
                    out.add(voiceKey(TRAIN, m.id), Recipes.TRAIN, gain, c.pan, lowpass, 1f, trainKind(m.traction), speed, pitch = trainPitch(speed))
                }
                SHIP -> {
                    val gain = pointGain(c.weight, SHIP_LEVEL * (0.3f + 0.7f * speed)) * muffle
                    out.add(voiceKey(SHIP, m.id), Recipes.OUTBOARD, gain, c.pan, lowpass, 1f, 0.15f + 0.85f * speed)
                }
                AIRCRAFT -> {
                    val gain = pointGain(c.weight, AIR_LEVEL * (0.35f + 0.65f * speed)) * muffle
                    if (m.jet) out.add(voiceKey(AIRCRAFT, m.id), Recipes.JET, gain, c.pan, lowpass, 1f, 0.25f + 0.75f * speed, speed)
                    else out.add(voiceKey(AIRCRAFT, m.id), Recipes.PROP, gain, c.pan, lowpass, 1f, 40f + 90f * speed, 0.25f + 0.75f * speed)
                }
            }
        }
    }

    /** One bell while any crossing you can hear has its lights on. */
    private fun bell(at: Listener, lowpass: Float, muffle: Float, crossings: IntArray, out: Frame) {
        if (crossings.isEmpty()) return
        val w = world.width
        val limit = w * world.height
        var weight = 0f
        var pan = 0f
        for (tile in crossings) {
            if (tile < 0 || tile >= limit) continue
            val p = place(tile % w + 0.5f, tile / w + 0.5f, at) ?: continue
            weight += p.weight
            pan += p.weight * p.pan
        }
        if (weight <= 0f) return
        out.add(KEY_SIREN, Recipes.SIREN, pointGain(weight, BELL_LEVEL) * muffle, pan / weight, lowpass, 1f, 0f)
    }

    /** The nearest station within a few tiles. A waypoint stays quiet. */
    private fun crowd(at: Listener, lowpass: Float, muffle: Float, stations: List<StationSpot>, out: Frame) {
        val limit = STATION_NEAR * STATION_NEAR
        var best = -1
        var bestD = limit
        for (i in stations.indices) {
            val s = stations[i]
            if (s.waypoint) continue
            val dx = s.x - at.x
            val dy = s.y - at.y
            val d = dx * dx + dy * dy
            if (d > limit || (best >= 0 && d >= bestD)) continue
            best = i
            bestD = d
        }
        if (best < 0) return
        val s = stations[best]
        val dist = sqrt(bestD)
        val closeness = (1f - dist / STATION_NEAR).coerceIn(0f, 1f)
        val p = place(s.x, s.y, at)
        out.add(KEY_CROWD, Recipes.CROWD, (0.3f + 0.5f * closeness) * muffle, p?.pan ?: 0f, lowpass, 1f)
    }

    /** [kind] in earshot, one per id, the nearer position kept. A depot or a wreck is left out. */
    private fun hear(kind: Int, movers: List<Mover>, at: Listener): ArrayList<Candidate> {
        val out = ArrayList<Candidate>()
        val index = HashMap<Int, Int>()
        for (m in movers) {
            if (m.kind != kind || m.inDepot || m.wrecked) continue
            val p = place(m.x, m.y, at) ?: continue
            val i = index[m.id]
            if (i == null) {
                index[m.id] = out.size
                out += Candidate(m, p.weight, p.pan, p.dist2)
            } else if (p.dist2 < out[i].dist2) {
                out[i] = Candidate(m, p.weight, p.pan, p.dist2)
            }
        }
        return out
    }

    /** Where [x], [y] sits in the view, or null past earshot. Pan is to the right of the screen. */
    private fun place(x: Float, y: Float, at: Listener): Placed? {
        val dxw = x - at.x
        val dyw = y - at.y
        val dist2 = dxw * dxw + dyw * dyw
        val reach = reach(at)
        val ear = reach * 3f + World.CHUNK
        if (dist2 > ear * ear) return null
        val lx = viewX(at.x, at.y, at.turn)
        val ly = viewY(at.x, at.y, at.turn)
        val vx = viewX(x, y, at.turn)
        val vy = viewY(x, y, at.turn)
        val dx = vx - lx
        val dy = vy - ly
        val d = sqrt(dx * dx + dy * dy) / reach
        val weight = 1f / (1f + d * d * 2f)
        val pan = (dx / max(1f, at.halfWidth)).coerceIn(-1f, 1f) * PAN_WIDTH
        return Placed(weight, pan, dist2)
    }

    private fun reach(at: Listener) = max(at.halfWidth, at.halfHeight).coerceAtLeast(4f)

    /** One sound's loudness, from how close it is, so a vehicle under the camera stays audible at any zoom. */
    private fun pointGain(weight: Float, full: Float) = level(weight, POINT_REF) * full

    /** 0 steam, 1 diesel, 2 electric; a dual-mode engine sounds like a diesel. */
    private fun trainKind(traction: Int) = when (traction) {
        Traction.DIESEL, Traction.DUAL -> 1f
        Traction.ELECTRIC -> 2f
        else -> 0f
    }

    /** A frequency factor. 0 is no shift, so anything moving passes a factor above 0, and a fast one is higher. */
    private fun trainPitch(speed: Float) = if (speed > 0f) 1f + 0.35f * speed else 0f

    private class Placed(val weight: Float, val pan: Float, val dist2: Float)

    private class Candidate(val mover: Mover, val weight: Float, val pan: Float, val dist2: Float)

    /** A clap now and then under a storm, from how far the front reaches and how long [dt] is. */
    private fun thunder(x: Int, y: Int, dt: Float, lowpass: Float, out: Frame) {
        var reach = 0
        for (f in world.weather.fronts) if (f.kind == Front.STORM) reach = max(reach, f.reach(x, y))
        if (reach <= 0) return
        val chance = (THUNDER_A_SECOND * dt * reach / 256f).coerceIn(0f, 0.85f)
        if (random.nextFloat() >= chance) return
        val params = FloatArray(SharedParams.COUNT)
        params[0] = 0.45f + 0.55f * random.nextFloat()
        params[SharedParams.GAIN] = 0.85f
        params[SharedParams.PAN] = (random.nextFloat() - 0.5f) * 1.4f
        params[SharedParams.LOWPASS] = lowpass
        out.shots += Shot(Recipes.THUNDER, params)
    }

    /** View x, down to the right on screen. Turn 0 is world x. */
    private fun viewX(x: Float, y: Float, turn: Int) = when (turn and 3) {
        0 -> x
        1 -> world.height - y
        2 -> world.width - x
        else -> y
    }

    private fun viewY(x: Float, y: Float, turn: Int) = when (turn and 3) {
        0 -> y
        1 -> x
        2 -> world.height - y
        else -> world.width - x
    }

    /** Quieter in winter. */
    private fun birdsSeason(): Float = when (world.lookToday()) {
        Climate.WINTER -> 0.4f
        Climate.EARLY_SPRING, Climate.LATE_AUTUMN -> 0.65f
        else -> 1f
    }

    private class Layer(n: Int) {
        val amount = FloatArray(n)
        val sumX = FloatArray(n)
        val sumY = FloatArray(n)

        fun clear() {
            amount.fill(0f)
            sumX.fill(0f)
            sumY.fill(0f)
        }

        fun add(c: Int, v: Float, x: Float, y: Float) {
            amount[c] += v
            sumX[c] += v * x
            sumY[c] += v * y
        }
    }

    companion object {
        /** Weather, the road, a tram, the nearest trains, ships and aircraft, a crossing and a station. */
        const val MAX_HELD = 24
        private const val QUIET = 0.02f
        private const val PAN_WIDTH = 0.8f
        private val NEXT = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

        const val TRAIN = 0
        const val ROAD = 1
        const val TRAM = 2
        const val SHIP = 3
        const val AIRCRAFT = 4

        /** Top speed in km/h from which an aircraft is a jet. */
        const val JET_KMH = 600

        private const val TRAIN_VOICES = 4
        private const val SHIP_VOICES = 2
        private const val AIR_VOICES = 2
        private const val POINT_REF = 4f
        private const val TRAIN_LEVEL = 0.75f
        private const val TRAFFIC_LEVEL = 0.55f
        private const val TRAM_LEVEL = 0.6f
        private const val SHIP_LEVEL = 0.6f
        private const val AIR_LEVEL = 0.7f
        private const val BELL_LEVEL = 0.5f
        /** Tiles from the listener inside which a station's crowd is heard. */
        private const val STATION_NEAR = 8f

        /** Wind, in km/h, taken as a full gale. */
        private const val WIND_FULL = 80f
        /** Precipitation, in hundredths of a mm a day, taken as heavy rain. */
        private const val RAIN_FULL = 2500f
        private const val SHORE_REF = 5f
        private const val TREES_REF = 8f
        private const val BIRDS_LEVEL = 0.3f
        private const val SNOW_MUFFLE = 0.6f
        private const val SNOW_LOWPASS = 3200f
        /** Claps a second under the middle of a storm. */
        private const val THUNDER_A_SECOND = 0.5f
        /** How long a whistle or a horn holds, 0 to 1. */
        private const val WHISTLE_LENGTH = 0.6f

        const val KEY_WIND = 1
        const val KEY_RAIN = 2
        const val KEY_SHORE = 3
        const val KEY_BIRDS = 4
        const val KEY_TRAFFIC = 10
        const val KEY_TRAM = 11
        const val KEY_SIREN = 12
        const val KEY_CROWD = 13

        /** A stable key for one train, ship or aircraft. */
        fun voiceKey(kind: Int, id: Int) = ((kind + 1) shl 20) or (id and 0xFFFFF)

        /** 0 at night, 1 through the middle of the day, with dawn and dusk between. */
        private fun daylight(hour: Float): Float = when {
            hour < 5.5f || hour >= 18.5f -> 0f
            hour < 7f -> (hour - 5.5f) / 1.5f
            hour < 17f -> 1f
            else -> (18.5f - hour) / 1.5f
        }

        /** Fuller around dawn, thinner later in the day. */
        private fun chorus(hour: Float): Float = when {
            hour < 5f || hour > 11f -> 0.2f
            hour < 7f -> 0.2f + 0.8f * (hour - 5f) / 2f
            else -> 0.2f + 0.8f * (11f - hour) / 4f
        }
    }
}

/** One recipe parameter and a modest loudness, the rest left at 0. */
private fun shot(recipe: Int, p0: Float, p1: Float = 0f, p2: Float = 0f, gain: Float = 0.7f, flags: Int = 0): WorldSound.Shot {
    val params = FloatArray(SharedParams.COUNT)
    params[0] = p0
    params[1] = p1
    params[2] = p2
    params[SharedParams.GAIN] = gain
    return WorldSound.Shot(recipe, params, flags = flags)
}

/** The one-shot for news of [kind], or null if this one stays quiet. */
fun newsShot(kind: Int, inView: Boolean): WorldSound.Shot? = when (kind) {
    News.CRASH, News.AVALANCHE -> if (inView) shot(Recipes.CRUNCH, 0.85f, flags = VoiceFlags.TOWN) else null
    News.SLID -> if (inView) shot(Recipes.IMPACT, 0.7f, Materials.SNOW.toFloat(), 0.55f, flags = VoiceFlags.TOWN) else null
    News.WASHOUT -> if (inView) shot(Recipes.SPLASH, 0.75f, flags = VoiceFlags.TOWN) else null
    News.FIRE -> if (inView) shot(Recipes.IMPACT, 0.65f, Materials.WOOD.toFloat(), 0.5f, flags = VoiceFlags.TOWN) else null
    News.GOAL_MET, News.SCENARIO_WON -> shot(Recipes.CHIME, 1f, gain = 0.45f)
    News.SUBSIDY_AWARDED, News.NEW_VEHICLE -> shot(Recipes.CHIME, 0f, gain = 0.4f)
    News.COMPANY_BANKRUPT, News.GOAL_FAILED, News.SCENARIO_LOST -> shot(Recipes.CHIME, 2f, gain = 0.45f)
    else -> null
}
