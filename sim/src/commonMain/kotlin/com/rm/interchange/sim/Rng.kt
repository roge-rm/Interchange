package com.rm.interchange.sim

/**
 * The world's random numbers: SplitMix64, all in integers, so every player's
 * copy of the world draws the same numbers. Its state is one Long, saved with
 * the world.
 */
class Rng(var state: Long) {
    fun next(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** From 0 until [bound]. */
    fun below(bound: Int): Int = ((next() ushr 1) % bound).toInt()
}
