package com.rm.interchange.sim

/**
 * The map and everything on it. Layers are flat arrays indexed by
 * `y * width + x`. All maths is in integers so every copy of the world stays
 * the same to the bit: no floats in here.
 */
class World(val width: Int, val height: Int, seed: Long) {
    /** Corner height of each tile, in steps. Water is at [SEA] and below. */
    val heights = ByteArray(width * height)

    val rng = Rng(seed)

    /** Ticks since the game began. */
    var tick = 0L
        private set

    init {
        generate()
    }

    fun index(x: Int, y: Int) = y * width + x

    fun inside(x: Int, y: Int) = x in 0 until width && y in 0 until height

    fun heightAt(x: Int, y: Int): Int = heights[index(x, y)].toInt()

    /** Runs one command. One that can't be done does nothing. */
    fun apply(command: Command) {
        when (command) {
            is Command.RaiseLand -> if (inside(command.x, command.y)) {
                val i = index(command.x, command.y)
                if (heights[i] < MAX_HEIGHT) heights[i]++
            }
            is Command.LowerLand -> if (inside(command.x, command.y)) {
                val i = index(command.x, command.y)
                if (heights[i] > 0) heights[i]--
            }
        }
    }

    /** Moves the world on by one tick. */
    fun step() {
        tick++
    }

    /** A hash of the whole world. Players compare it to find a copy that's gone out of step. */
    fun checksum(): Long {
        var h = -0x340d631b7bdddcdbL
        fun mix(v: Long) {
            h = (h xor v) * 0x100000001b3L
        }
        mix(tick)
        mix(rng.state)
        for (b in heights) mix(b.toLong())
        return h
    }

    /** Rolling land from a few passes of smoothed random heights. */
    private fun generate() {
        val raw = IntArray(width * height) { rng.below(16) }
        val tmp = IntArray(raw.size)
        repeat(4) {
            for (y in 0 until height) for (x in 0 until width) {
                var sum = 0
                var n = 0
                for (dy in -2..2) for (dx in -2..2) {
                    val nx = x + dx
                    val ny = y + dy
                    if (inside(nx, ny)) {
                        sum += raw[index(nx, ny)]
                        n++
                    }
                }
                tmp[index(x, y)] = sum / n
            }
            tmp.copyInto(raw)
        }
        for (i in raw.indices) heights[i] = ((raw[i] - 4) * 2).coerceIn(0, MAX_HEIGHT).toByte()
    }

    companion object {
        const val SEA = 2
        const val MAX_HEIGHT = 15
    }
}
