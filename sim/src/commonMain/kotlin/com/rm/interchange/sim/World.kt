package com.rm.interchange.sim

/**
 * The map and everything on it. Layers are flat arrays: tiles indexed by
 * `y * width + x`, corners by `y * (width + 1) + x`. All maths is in integers
 * so every copy of the world stays the same to the bit: no floats in here.
 *
 * Land is a height at each tile corner, in steps. Corners next to each other,
 * across or diagonally, differ by at most one, so every tile is flat, a slope
 * or a corner raised or sunk. A tile with all four corners at 0 is water.
 *
 * Only the game thread changes the world. Other threads may read it, and see
 * a tick's changes part done; the renderer copes through [chunkVersions].
 */
class World(val width: Int, val height: Int, seed: Long, val startYear: Int = 1850) {
    init {
        require(width % CHUNK == 0 && height % CHUNK == 0) { "Map sides are multiples of $CHUNK" }
    }

    val corners = ByteArray((width + 1) * (height + 1))

    /** The kind of ground on each tile, a [Ground] kind. */
    val ground = ByteArray(width * height)

    /** The trees on each tile, packed by [Trees.pack]. */
    val trees = ByteArray(width * height)

    /** Track pieces on each tile, as [Track] bits. */
    val track = ByteArray(width * height)

    /** Which company owns what's on each tile, or -1. */
    val owner = ByteArray(width * height) { -1 }

    val companies = mutableListOf(Company(0, "Company", Prices.START_MONEY))

    // What's built on the track, by id; a removed one leaves null.
    val stations = ArrayList<Station?>()
    val depots = ArrayList<Depot?>()
    val trains = ArrayList<Train?>()

    /** Platform tiles to their station, depot tiles to their depot. */
    val stationTiles = HashMap<Int, Int>()
    val depotTiles = HashMap<Int, Int>()

    /** Signals by [pieceKey], packed by [Signals.pack]. */
    val signals = HashMap<Int, Int>()

    // Worked out from the above as needed, not saved: the block each piece is in, and how many trains are on each tile and block.
    internal val blocks = HashMap<Int, Int>()
    internal var blocksDirty = true
    internal val tileTrains = HashMap<Int, Int>()
    internal val blockTrains = HashMap<Int, Int>()

    val rng = Rng(seed)

    /** The seed folded into an int, for per-tile dice that don't draw from [rng]. */
    internal val seedBits = (seed xor (seed ushr 32)).toInt()

    /** Ticks since the game began. */
    var tick = 0L
        private set

    val day get() = (tick / Calendar.TICKS_PER_DAY).toInt()

    /**
     * How many times each [CHUNK] by [CHUNK] block of tiles has changed, so the
     * renderer knows what to draw again. Not part of the game and not saved.
     */
    val chunkVersions = IntArray((width / CHUNK) * (height / CHUNK))

    init {
        generateLand(this)
        generateCountry(this)
    }

    fun index(x: Int, y: Int) = y * width + x

    fun corner(x: Int, y: Int) = y * (width + 1) + x

    fun inside(x: Int, y: Int) = x in 0 until width && y in 0 until height

    fun cornerInside(x: Int, y: Int) = x in 0..width && y in 0..height

    fun heightAt(cx: Int, cy: Int): Int = corners[corner(cx, cy)].toInt()

    fun isWater(x: Int, y: Int): Boolean =
        heightAt(x, y) == 0 && heightAt(x + 1, y) == 0 && heightAt(x + 1, y + 1) == 0 && heightAt(x, y + 1) == 0

    fun isFlat(x: Int, y: Int): Boolean {
        val n = heightAt(x, y)
        return n == heightAt(x + 1, y) && n == heightAt(x + 1, y + 1) && n == heightAt(x, y + 1)
    }

    fun trackAt(x: Int, y: Int): Int = track[index(x, y)].toInt()

    /** The dry tile nearest the middle of the map, for the camera to start on. */
    fun landNearMiddle(): Int {
        val cx = width / 2
        val cy = height / 2
        for (r in 0 until maxOf(width, height) / 2) {
            for (y in cy - r..cy + r) for (x in cx - r..cx + r) {
                if ((kotlin.math.abs(x - cx) == r || kotlin.math.abs(y - cy) == r) && inside(x, y) && !isWater(x, y)) return index(x, y)
            }
        }
        return index(cx, cy)
    }

    /** Runs one command. What can't be done is left undone. */
    fun apply(command: Command) {
        val company = companies.getOrNull(command.company) ?: return
        when (command) {
            is Command.Terraform -> terraform(company, command.x, command.y, command.up)
            is Command.Rail -> buildRail(company, command)
            is Command.Station -> buildStation(company, command)
            is Command.Depot -> buildDepot(company, command)
            is Command.Signal -> placeSignal(company, command)
            is Command.Clear -> clearTile(company, command)
            is Command.BuyTrain -> buyTrain(company, command)
            is Command.TrainOrder -> trainCommand(company, command)
        }
    }

    /** Notes that tile ([x], [y]) has changed, for the renderer. */
    fun touched(x: Int, y: Int) {
        chunkVersions[(y / CHUNK) * (width / CHUNK) + x / CHUNK]++
    }

    /** Moves the world on by one tick. */
    fun step() {
        tick++
        moveTrains()
        if (tick % Calendar.TICKS_PER_DAY == 0L) payRunningCosts()
    }

    /**
     * A hash of the whole world. Players compare it to find a copy that's gone
     * out of step. Each band of rows is hashed on its own, across the cores,
     * and the bands put together in order.
     */
    fun checksum(): Long {
        val bands = LongArray(height)
        Workers.run(height) { y ->
            var h = -0x340d631b7bdddcdbL + y
            val from = y * width
            for (i in from until from + width) {
                h = (h xor corners[corner(i - from, y)].toLong()) * 0x100000001b3L
                h = (h xor track[i].toLong()) * 0x100000001b3L
                h = (h xor trees[i].toLong()) * 0x100000001b3L
                h = (h xor owner[i].toLong()) * 0x100000001b3L
            }
            bands[y] = h
        }
        var h = -0x340d631b7bdddcdbL
        fun mix(v: Long) {
            h = (h xor v) * 0x100000001b3L
        }
        mix(tick)
        mix(rng.state)
        for (b in bands) mix(b)
        // The last row and column of corners, which the rows above leave out.
        for (x in 0..width) mix(heightAt(x, height).toLong())
        for (y in 0 until height) mix(heightAt(width, y).toLong())
        for (c in companies) mix(c.money)
        for (k in signals.keys.sorted()) mix(k.toLong() * 31 + signals.getValue(k))
        for (k in stationTiles.keys.sorted()) mix(k.toLong() * 31 + stationTiles.getValue(k))
        for (k in depotTiles.keys.sorted()) mix(k.toLong() * 31 + depotTiles.getValue(k))
        for (t in trains) {
            if (t == null) continue
            mix(t.id.toLong()); mix(t.nose.toLong()); mix(t.speed.toLong()); mix(t.waiting.toLong()); mix(t.order.toLong())
            for (st in t.steps) mix(st.toLong())
        }
        return h
    }

    companion object {
        const val MAX_HEIGHT = 24
        /** Tiles a side in a block the renderer redraws together; map sides are a multiple of it. */
        const val CHUNK = 16
    }
}
