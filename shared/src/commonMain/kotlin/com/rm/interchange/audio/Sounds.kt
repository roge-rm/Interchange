package com.rm.interchange.audio

import com.rm.interchange.sim.sound.Materials
import com.rm.interchange.sim.sound.Recipes
import com.rm.interchange.sim.sound.SharedParams

/** Short sounds for the screens. */
object Sounds {
    /** A button press. */
    fun click() {
        val params = FloatArray(SharedParams.COUNT)
        params[0] = 0f
        params[SharedParams.GAIN] = 0.35f
        params[SharedParams.PAN] = 0f
        AudioEngine.event(Recipes.CLICK, params)
    }

    /** Track, a road, tram rails, a canal or the bulldozer, once for the gesture. */
    fun earth() {
        val params = FloatArray(SharedParams.COUNT)
        params[0] = 0.45f
        params[1] = Materials.EARTH.toFloat()
        params[2] = 0.35f
        params[SharedParams.GAIN] = 0.4f
        AudioEngine.event(Recipes.IMPACT, params)
    }

    /** A signal, a bridge or wires, once for the gesture. */
    fun clunk() {
        val params = FloatArray(SharedParams.COUNT)
        params[0] = 0.5f
        params[SharedParams.GAIN] = 0.4f
        AudioEngine.event(Recipes.CLUNK, params)
    }
}
