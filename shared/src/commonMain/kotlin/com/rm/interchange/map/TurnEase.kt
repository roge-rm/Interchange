package com.rm.interchange.map

/**
 * The angle each ship and aircraft is drawn at, in eighths, turned toward its sim heading at a steady rate the
 * shorter way round, so a turn shows the pictures in between rather than snapping. Drawing only, on the UI thread.
 *
 * Kept in two open-addressed tables by vehicle key, read from last frame's and written to this frame's, then
 * swapped, so a vehicle not drawn this frame is forgotten and nothing is allocated once the tables have grown.
 * Keys are below zero (ships and aircraft), so 0 marks an empty slot.
 */
internal class TurnEase {
    private var oldKeys = IntArray(64)
    private var oldAngles = FloatArray(64)
    private var newKeys = IntArray(64)
    private var newAngles = FloatArray(64)
    private var lastTime = Double.NaN
    /** Sim ticks since the last frame, 0 or more. */
    private var dt = 0f

    /**
     * Starts a frame at sim time [time] in ticks, for up to [count] vehicles. Time going back more than a few ticks
     * (a load or new game) forgets everything; a smaller step back, from reading a view mid-publish, just holds still.
     */
    fun begin(time: Double, count: Int) {
        val back = !(time >= lastTime - 4)
        dt = if (back || time < lastTime) 0f else (time - lastTime).toFloat()
        if (back || time > lastTime) lastTime = time
        if (back) oldKeys.fill(0)
        var cap = newKeys.size
        while (cap < count * 2) cap *= 2
        if (cap != newKeys.size) {
            newKeys = IntArray(cap)
            newAngles = FloatArray(cap)
        } else newKeys.fill(0)
    }

    /**
     * The angle to draw vehicle [key] at, eased from last frame's toward [target] at [rate] eighths a tick;
     * [jumped] (or a vehicle not seen last frame) starts it at [target].
     */
    fun angle(key: Int, target: Float, rate: Float, jumped: Boolean): Float {
        val was = if (jumped) Float.NaN else find(oldKeys, oldAngles, key)
        val drawn = if (was.isNaN()) target else toward(was, target, rate * dt)
        put(key, drawn)
        return drawn
    }

    /** Ends the frame: this frame's angles are the ones read next. */
    fun end() {
        val k = oldKeys; oldKeys = newKeys; newKeys = k
        val a = oldAngles; oldAngles = newAngles; newAngles = a
    }

    private fun slot(key: Int, mask: Int): Int {
        val h = key * -0x61c88647
        return (h xor (h ushr 16)) and mask
    }

    private fun find(keys: IntArray, angles: FloatArray, key: Int): Float {
        val mask = keys.size - 1
        var s = slot(key, mask)
        while (true) {
            val k = keys[s]
            if (k == key) return angles[s]
            if (k == 0) return Float.NaN
            s = (s + 1) and mask
        }
    }

    private fun put(key: Int, angle: Float) {
        val mask = newKeys.size - 1
        var s = slot(key, mask)
        while (newKeys[s] != 0 && newKeys[s] != key) s = (s + 1) and mask
        newKeys[s] = key
        newAngles[s] = angle
    }

    companion object {
        /**
         * Turn rates in eighths a tick, at 30 ticks a second. A ship turns 90 degrees in a second, so a half turn
         * takes 2 s, while it covers a tile in a few seconds. An aircraft, flying or taxiing, turns 90 degrees in
         * 0.4 s, quick enough that its picture keeps up with a plane covering several tiles a second.
         */
        const val SHIP_RATE = 2f / 30f
        const val AIRCRAFT_RATE = 2f / 12f

        /** [from] moved up to [step] eighths toward [to] the shorter way round, kept in 0 up to 8. */
        fun toward(from: Float, to: Float, step: Float): Float {
            var turn = to - from
            if (turn > 4f) turn -= 8f
            if (turn < -4f) turn += 8f
            if (turn >= -step && turn <= step) return to
            val a = from + if (turn > 0f) step else -step
            return (a % 8f + 8f) % 8f
        }
    }
}
