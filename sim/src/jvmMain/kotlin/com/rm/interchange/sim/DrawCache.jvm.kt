package com.rm.interchange.sim

import java.util.Collections
import java.util.WeakHashMap

// Weak keys: CarCurves holds its world only inside CarCurves.using, so nothing here keeps a dropped world.
actual object DrawCache {
    private val curves = Collections.synchronizedMap(WeakHashMap<World, CarCurves>())

    /** How many worlds have an entry, for tests. */
    internal fun size(): Int = curves.size

    actual fun carCurves(world: World): CarCurves = curves.getOrPut(world) { CarCurves(world) }
}
