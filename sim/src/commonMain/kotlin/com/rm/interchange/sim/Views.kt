package com.rm.interchange.sim

/*
 * What the screen needs from the world each tick, copied out on the game
 * thread so the screen never reads the game's own lists while they change.
 * Floats are fine here: they're only for drawing, never fed back into the game.
 */

/** Where every wagon is: [CAR_FIELDS] floats each. */
class VehicleView(val tick: Long, val cars: FloatArray) {
    val count get() = cars.size / CAR_FIELDS

    fun key(i: Int) = cars[i * CAR_FIELDS].toInt()
    fun x(i: Int) = cars[i * CAR_FIELDS + 1]
    fun y(i: Int) = cars[i * CAR_FIELDS + 2]
    /** The way it faces in the world, 0 along x, on round towards y, in eighths. */
    fun heading(i: Int) = cars[i * CAR_FIELDS + 3].toInt()
    fun sprite(i: Int) = cars[i * CAR_FIELDS + 4].toInt()
    /** 1 climbing, -1 going down, 0 level. */
    fun slope(i: Int) = cars[i * CAR_FIELDS + 5].toInt()
    /** How long it is, in tiles. */
    fun length(i: Int) = cars[i * CAR_FIELDS + 6]
    fun train(i: Int) = key(i) / 256

    companion object {
        const val CAR_FIELDS = 7
    }
}

/** A train as the screen shows it. */
class TrainView(
    val id: Int,
    val name: String,
    val engine: Int,
    val wagon: Int,
    val wagons: Int,
    val depot: Int,
    val inDepot: Boolean,
    val running: Boolean,
    val toDepot: Boolean,
    val orders: IntArray,
    val order: Int,
    val kmh: Int,
    val waiting: Boolean,
    val lost: Boolean,
)

/** A station as the screen shows it. */
class StationView(val id: Int, val name: String, val platforms: Int)

/** A signal as the screen shows it: where, which way and whether it shows red. */
class SignalView(val tile: Int, val piece: Int, val facing: Int, val red: Boolean)

/** Everything the screen draws and lists that moves or changes as the game runs. */
class WorldView(
    val vehicles: VehicleView,
    val trains: List<TrainView>,
    val stations: List<StationView>,
    val signals: List<SignalView>,
)

fun World.view(): WorldView {
    val cars = ArrayList<Float>()
    for (t in trains) {
        if (t == null || t.inDepot) continue
        for (c in 0 until t.cars) {
            val at = t.behind(t.carMiddle(c))
            val i = (at shr 32).toInt()
            if (i < 0) continue
            val s = t.steps[i]
            val tile = stepTile(s)
            if (depotTiles.containsKey(tile)) continue
            val along = (at and 0xffffffffL).toFloat() / stepLength(s)
            val a = stepEntry(s)
            val b = stepExit(s)
            val ax = tile % width + Track.edgeX4(a) / 4f
            val ay = tile / width + Track.edgeY4(a) / 4f
            val bx = tile % width + Track.edgeX4(b) / 4f
            val by = tile / width + Track.edgeY4(b) / 4f
            cars += (t.id * 256 + c).toFloat()
            cars += ax + (bx - ax) * along
            cars += ay + (by - ay) * along
            cars += heading(Track.edgeX4(b) - Track.edgeX4(a), Track.edgeY4(b) - Track.edgeY4(a)).toFloat()
            cars += Engines.ALL[t.carEngines[c]].sprite.toFloat()
            // Climbing or going down: the edge it leaves by against the edge it came in at.
            val pieces = track[tile].toInt()
            val rise = edgeHeight(tile % width, tile / width, pieces, b) - edgeHeight(tile % width, tile / width, pieces, a)
            cars += rise.coerceIn(-1, 1).toFloat()
            cars += Engines.ALL[t.carEngines[c]].length / 16f
        }
    }
    val trainViews = trains.filterNotNull().map { t ->
        TrainView(
            t.id, t.name, t.engine, t.wagon, t.wagons, t.depot, t.inDepot, t.running, t.toDepot,
            t.orders.toIntArray(), t.order, t.speed / 16 * 2700 / 4096, t.waiting > 0, t.running && !t.inDepot && t.lostUntil > tick,
        )
    }
    val stationViews = stations.filterNotNull().map { StationView(it.id, it.name, it.tiles.size) }
    val signalViews = signals.entries.sortedBy { it.key }.map { (k, s) ->
        val tile = k / 8
        val piece = Track.PIECES[k % 8]
        // Red while a train is in the block the signal guards: the one its piece is in.
        SignalView(tile, piece, Signals.facing(s), blockHasTrain(tile, piece))
    }
    return WorldView(VehicleView(tick, cars.toFloatArray()), trainViews, stationViews, signalViews)
}

/** The heading, in eighths from along x towards y, of a step of ([dx], [dy]). */
fun heading(dx: Int, dy: Int): Int {
    val sx = dx.coerceIn(-1, 1)
    val sy = dy.coerceIn(-1, 1)
    return when {
        sx == 1 && sy == 0 -> 0
        sx == 1 && sy == 1 -> 1
        sx == 0 && sy == 1 -> 2
        sx == -1 && sy == 1 -> 3
        sx == -1 && sy == 0 -> 4
        sx == -1 && sy == -1 -> 5
        sx == 0 && sy == -1 -> 6
        else -> 7
    }
}
