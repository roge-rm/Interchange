package com.rm.interchange.sim

/*
 * How hard a train pulls and what holds it back, in newtons and kilograms.
 *
 * A tick is a second of the train's time and a tile 750 m, which is what the
 * speed units already say: 1 m/s² is [UNITS_PER_MS2] speed units a tick.
 * The engine pulls with the least of its tractive effort, cut by the grip the
 * rail gives, and its power over its speed. Against it are rolling and air
 * resistance, growing with speed, and the grade: the rise from the train's
 * tail to its nose over its length, a step of height to a tile being
 * [GRADE_PER_STEP] percent in tenths.
 */

/** Speed units a tick for an acceleration of 1 m/s², in tenths. */
const val UNITS_PER_MS2 = 874
/** The grade of a rise of one step over a tile, in tenths of a percent. */
const val GRADE_PER_STEP = 15
/** The slowest a train pulling at all creeps up a grade too much for it: 3 km/h, in speed units. */
const val CRAWL = 73

/** What the whole train weighs now, its load too, in kg. */
fun Train.massKg(): Long {
    var kg = carEngines.sumOf { roster[it].weight.toLong() } * 1000
    for (c in 0 until Cargo.COUNT) kg += load(c).toLong() * Cargo.KG[c]
    return kg
}

/** How high the track is at [along] (4096ths of a tile) into step [s], in 4096ths of a step. */
private fun World.heightAlong(s: Int, along: Int): Long {
    spanOf(s)?.let { return it.height * 4096L }
    val tile = stepTile(s)
    val x = tile % width
    val y = tile / width
    val pieces = track[tile].toInt()
    val a = edgeHeight(x, y, pieces, stepEntry(s)).toLong()
    val b = edgeHeight(x, y, pieces, stepExit(s)).toLong()
    return a * 4096 + (b - a) * 4096 * along.coerceIn(0, stepLength(s)) / stepLength(s)
}

/** How far the nose stands above the tail, in 4096ths of a step; 0 for a train only partly out of its depot. */
fun World.rise(t: Train): Long {
    val tail = t.behind(t.length)
    val i = (tail shr 32).toInt()
    if (i < 0 || t.steps.isEmpty()) return 0
    return heightAlong(t.steps.last(), t.nose) - heightAlong(t.steps[i], (tail and 0xffffffffL).toInt())
}

/** The grade force on the train, in newtons, uphill positive. */
fun World.gradeForce(t: Train, mass: Long = t.massKg()): Long =
    mass * 981 / 100 * GRADE_PER_STEP / 1000 * rise(t) / t.length.coerceAtLeast(1)

/** Rolling and air resistance at [speed] units, in newtons. */
fun resistance(mass: Long, speed: Int): Long {
    val kmh = speed.toLong() * 2700 / 65536
    return mass / 1000 * (12 + kmh / 10 + kmh * kmh / 500)
}

/** The engine's pull at [speed] units with [grip] percent of full grip, in newtons. */
fun Train.tractiveForce(speed: Int, grip: Int): Long {
    val e = engineInfo
    val slip = e.tractive.toLong() * 1000 * grip / 100
    // Power over speed, in cm/s so slow speeds don't lose it, never from less than 1.5 m/s.
    val cms = (speed.toLong() * 1144 / 1000).coerceAtLeast(150)
    val power = e.power.toLong() * 74_570 / cms
    return minOf(slip, power)
}

/** How much train [t]'s speed changes this tick under power with [grip] percent grip, in speed units. */
fun World.acceleration(t: Train, grip: Int): Int {
    val mass = t.massKg()
    val net = t.tractiveForce(t.speed, grip) - resistance(mass, t.speed) - gradeForce(t, mass)
    // A twentieth more for the wheels and axles turning.
    return (net * UNITS_PER_MS2 / (mass * 21 / 20 * 10).coerceAtLeast(1)).toInt()
}
