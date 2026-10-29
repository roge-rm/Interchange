package com.rm.interchange.map

import com.rm.interchange.platform.logLine

/**
 * Measurement of what the map shows before it's ready: bake times, stand-ins, blank chunks, weather batches, zoom
 * waits and sheet decodes. Lines go to logcat under "Interchange", starting "PROBE". Off unless [ON] is set true in
 * a build made for measuring; with it false every call compiles away.
 *
 * Main thread only, but for [bakeDone] times taken on the bakers and handed back.
 */
internal object Probe {
    const val ON = false

    private val clock = kotlin.time.TimeSource.Monotonic.markNow()
    fun now(): Long = clock.elapsedNow().inWholeMilliseconds
    /** For timing on any thread, in microseconds. */
    fun micros(): Long = clock.elapsedNow().inWholeMicroseconds

    fun log(text: String) = logLine("PROBE t=${now()} $text")

    // Counts for the frames in the current half second.
    private var frames = 0
    private var windowStart = -1L
    /** On-screen chunks this frame: drawn sharp, fading in, drawn from a finer or coarser level, drawn as nothing. */
    var ok = 0; var fading = 0; var finer = 0; var coarser = 0; var nothing = 0
    /** Of the coarser stand-ins, those two or more levels out, scaled 4x or more. */
    var far = 0
    /** Whether the map is still hidden behind the cover; those frames are only counted. */
    var covered = false
    /** On-screen chunks drawn sharp but from another weather copy than the one showing (they'll pop when rebaked), older or newer than it. */
    var staleOld = 0; var staleNew = 0
    /** On-screen chunks fading from their old weather. */
    var crossfading = 0
    /** Stand-ins drawn whose weather copy differs from the one showing. */
    var standInOtherWeather = 0
    /** Vehicles left out this frame for a sheet still decoding. */
    var vehiclesSkipped = 0
    var level = -1
    private var sumOk = 0; private var sumFading = 0; private var sumFiner = 0; private var sumCoarser = 0; private var sumNothing = 0
    private var sumCross = 0; private var sumStaleOld = 0; private var sumStaleNew = 0; private var sumStandInW = 0; private var sumVehicles = 0
    private var framesNothing = 0; private var framesStandIn = 0; private var maxNothing = 0
    private var sumFar = 0; private var framesFar = 0; private var framesCovered = 0

    fun frameStart() {
        far = 0; crossfading = 0; ok = 0; fading = 0; finer = 0; coarser = 0; nothing = 0; staleOld = 0; staleNew = 0; standInOtherWeather = 0
    }

    /** Called once a frame after the chunks are drawn; vehicles skipped are counted into the next window. */
    fun frameEnd() {
        val t = now()
        if (windowStart < 0) windowStart = t
        frames++
        if (covered) framesCovered++ else {
            sumFar += far; if (far > 0) framesFar++
            sumOk += ok; sumFading += fading; sumFiner += finer; sumCoarser += coarser; sumNothing += nothing
            sumCross += crossfading; sumStaleOld += staleOld; sumStaleNew += staleNew; sumStandInW += standInOtherWeather; sumVehicles += vehiclesSkipped
            if (nothing > 0) framesNothing++
            if (finer + coarser > 0) framesStandIn++
            if (nothing > maxNothing) maxNothing = nothing
        }
        vehiclesSkipped = 0
        if (t - windowStart >= 500) {
            // Sums over the window's frames, so chunk-frames; divide by frames for a per-frame average.
            log("win ms=${t - windowStart} frames=$frames L=$level ok=$sumOk fade=$sumFading finer=$sumFiner coarser=$sumCoarser nothing=$sumNothing " +
                "fNothing=$framesNothing fStandIn=$framesStandIn maxNothing=$maxNothing cross=$sumCross staleOld=$sumStaleOld staleNew=$sumStaleNew standInW=$sumStandInW vehSkip=$sumVehicles " +
                "far=$sumFar fFar=$framesFar covered=$framesCovered")
            frames = 0; windowStart = t
            sumOk = 0; sumFading = 0; sumFiner = 0; sumCoarser = 0; sumNothing = 0
            sumCross = 0; sumStaleOld = 0; sumStaleNew = 0; sumStandInW = 0; sumVehicles = 0
            framesNothing = 0; framesStandIn = 0; maxNothing = 0
            sumFar = 0; framesFar = 0; framesCovered = 0
        }
    }

    // Zooming: when the wanted level first differed from the one shown.
    private var zoomFrom = -1L
    private var zoomTarget = -1

    fun zoom(shown: Int, target: Int, missing: Int) {
        val t = now()
        if (shown != target) {
            if (zoomTarget != target) { zoomTarget = target; zoomFrom = t; log("zoomWait from=$shown to=$target missing=$missing") }
        } else if (zoomTarget >= 0) {
            if (zoomTarget == target) log("zoomSwitch to=$target waitMs=${t - zoomFrom}")
            zoomTarget = -1
        }
    }

    fun bakeDone(level: Int, kind: String, micros: Long, waitMs: Long, weather: Int, fresh: Boolean) {
        log("bake L=$level kind=$kind ms=${micros / 1000}.${(micros % 1000) / 100} queueMs=$waitMs w=$weather new=$fresh")
    }

    /** Snow difference between two weather copies: tiles changed, total and largest change in cm, tiles changed by 3 cm or more, and blocks changed. */
    fun weatherDiff(old: WeatherCopy?, new: WeatherCopy, copyMs: Long) {
        if (old == null) { log("weather id=${new.id} first copyMs=$copyMs"); return }
        var tiles = 0; var sum = 0L; var most = 0; var big = 0; var ice = 0
        for (i in new.snow.indices) {
            val d = kotlin.math.abs((new.snow[i].toInt() and 255) - (old.snow[i].toInt() and 255))
            if (d > 0) { tiles++; sum += d; if (d > most) most = d; if (d >= 3) big++ }
            if (new.ice[i] != old.ice[i]) ice++
        }
        var blocks = 0
        if (old.blocks.size == new.blocks.size) for (b in new.blocks.indices) if (new.blocks[b] != old.blocks[b]) blocks++
        log("weather id=${new.id} from=${old.id} copyMs=$copyMs snowTiles=$tiles of=${new.snow.size} sumCm=$sum maxCm=$most ge3=$big iceTiles=$ice blocks=$blocks of=${new.blocks.size}")
    }
}
