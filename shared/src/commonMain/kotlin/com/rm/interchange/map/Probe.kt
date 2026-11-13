package com.rm.interchange.map

import com.rm.interchange.platform.logLine

/** The platform's monotonic clock in nanoseconds, cheaper than [kotlin.time.TimeSource] for timing many small parts. */
internal expect fun probeNanos(): Long

/** Whether the system property debug.interchange.hash is 1, to run [Renderer]'s bake hash check in a [Probe.HASH] build. */
internal expect fun probeHashWanted(): Boolean

/** The app's saved games but the autosave, by file name, for the bake hash check. */
internal expect fun probeSaves(): Map<String, ByteArray>

/**
 * Measurement of what the map shows before it's ready: bake times, stand-ins, blank chunks, weather batches, zoom
 * waits and sheet decodes. Lines go to logcat under "Interchange", starting "PROBE". Off unless [ON] is set true in
 * a build made for measuring; with it false every call compiles away.
 *
 * Main thread only, but for [bakeDone] times taken on the bakers and handed back.
 */
internal object Probe {
    const val ON = false
    /** Bakes a fixed set of chunks from the saved games and logs a hash of each, to check a change to the baker leaves every pixel alone. */
    const val HASH = false

    private val clock = kotlin.time.TimeSource.Monotonic.markNow()
    fun now(): Long = clock.elapsedNow().inWholeMilliseconds
    /** For timing on any thread, in microseconds. */
    fun micros(): Long = clock.elapsedNow().inWholeMicroseconds
    /** For timing parts of a bake, in nanoseconds. */
    fun nanos(): Long = probeNanos()

    /** What one [nanos] call costs here now, in nanoseconds, so the bake split can say how much of it is the timing. */
    fun nanosCost(): Long {
        val n = 500
        val start = nanos()
        var sink = 0L
        for (i in 0 until n) sink += nanos()
        return ((nanos() - start) / n).also { if (sink == 42L) log("") }
    }

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

    // Turning: when the turn was asked for, the turn shown last frame, and blank frames since it turned.
    private var turnFrom = -1L
    private var turnSeen = -1
    private var turnStage = 0
    private var turnBlankFrames = 0
    private var turnMaxBlank = 0

    /**
     * Called once a frame after the chunks are drawn, with the turn shown, whether one is asked for and not shown yet,
     * and whether every chunk on screen is baked at its own level. Logs the time from the ask to the turn showing, to
     * no blank chunks and to sharp.
     */
    fun turn(turn: Int, asked: Boolean, allSharp: Boolean) {
        val t = now()
        if (asked && turnFrom < 0) { turnFrom = t; turnStage = 0; log("turnAsk from=$turn") }
        if (turnSeen >= 0 && turn != turnSeen) {
            if (turnFrom < 0) turnFrom = t
            turnStage = 1; turnBlankFrames = 0; turnMaxBlank = 0
            log("turnShown to=$turn ms=${t - turnFrom} nothing=$nothing")
        }
        turnSeen = turn
        if (turnStage == 1) {
            if (nothing > 0) { turnBlankFrames++; if (nothing > turnMaxBlank) turnMaxBlank = nothing }
            else { turnStage = 2; log("turnCovered ms=${t - turnFrom} blankFrames=$turnBlankFrames maxBlank=$turnMaxBlank") }
        }
        if (turnStage == 2 && allSharp) { turnStage = 0; log("turnSharp ms=${t - turnFrom}"); turnFrom = -1 }
        if (turnStage == 0 && !asked) turnFrom = -1
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

    /**
     * Where one chunk bake's time went, summed over its tiles in nanoseconds, logged as one "split" line in
     * microseconds. Made per bake on the baker's thread, so no locking.
     */
    class BakeTimes {
        val ns = LongArray(PARTS.size)
        val count = IntArray(PARTS.size)
        /** Ground pixels painted, by the same buckets as the fill times. */
        val pixels = IntArray(PARTS.size)
        /** How many [nanos] calls the split took, to tell its own cost from the bake's. */
        var calls = 0

        fun start(): Long { calls++; return nanos() }
        fun add(part: Int, from: Long) { calls++; ns[part] += nanos() - from; count[part]++ }

        fun log(level: Int, cx: Int, cy: Int, turn: Int, tiles: Int, top: Int, totalNs: Long) {
            val b = StringBuilder("split L=$level cx=$cx cy=$cy turn=$turn tiles=$tiles top=$top us=${totalNs / 1000} calls=$calls callNs=${nanosCost()}")
            for (i in PARTS.indices) if (count[i] > 0 || ns[i] > 0) {
                b.append(' ').append(PARTS[i]).append('=').append(ns[i] / 1000).append('/').append(count[i])
                if (pixels[i] > 0) b.append('/').append(pixels[i])
            }
            log(b.toString())
        }

        companion object {
            // Renderer's phases, then within the ground pass, then within standing (both shadow and object passes).
            const val MAKE = 0; const val PREPARE = 1; const val REACHES = 2; const val GROUND = 3; const val SHADOWS = 4
            const val OBJECTS = 5; const val TOPSCAN = 6; const val SOFT = 7; const val HARD = 8
            const val FILL_PLAIN = 9; const val FILL_SNOW = 10; const val FILL_WATER = 11; const val FILL_ROAD = 12; const val FILL_AERO = 13
            const val FILL_SETUP = 14; const val FACES = 15; const val TRACK = 16; const val CURVE = 17; const val WALLS = 18
            const val BUILDING = 19; const val TREES = 20; const val STREET = 21; const val DECOR = 22; const val STATION = 23
            const val BLIT = 24; const val SHADE = 25
            val PARTS = arrayOf(
                "make", "prep", "reach", "ground", "shadows", "objects", "topscan", "soft", "hard",
                "fPlain", "fSnow", "fWater", "fRoad", "fAero", "fSetup", "faces", "track", "curve", "walls",
                "bldg", "trees", "street", "decor", "station", "blit", "shade",
            )
        }
    }
}
