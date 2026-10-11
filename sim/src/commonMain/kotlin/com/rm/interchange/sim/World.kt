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

    /** Track pieces on each tile, as [Track] bits. */
    val track = ByteArray(width * height)

    /** Which company owns what's on each tile, or -1. */
    val owner = ByteArray(width * height) { -1 }

    val companies = mutableListOf(Company(0, "Company", Prices.START_MONEY))

    val rng = Rng(seed)

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
        }
    }

    /** Notes that tile ([x], [y]) has changed, for the renderer. */
    fun touched(x: Int, y: Int) {
        chunkVersions[(y / CHUNK) * (width / CHUNK) + x / CHUNK]++
    }

    /** Moves the world on by one tick. */
    fun step() {
        tick++
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
        return h
    }

    companion object {
        const val MAX_HEIGHT = 24
        /** Tiles a side in a block the renderer redraws together; map sides are a multiple of it. */
        const val CHUNK = 16
    }
}
