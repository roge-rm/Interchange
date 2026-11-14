package com.rm.interchange.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import com.rm.interchange.res.Res
import kotlinx.coroutines.CoroutineScope
import com.rm.interchange.sim.VehicleView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * One zoom level's sheets: the ground textures and the static sprites as pixels for the GPU, the shadows as a byte a
 * pixel of how dark, and the signals as a bitmap for drawing.
 */
class LevelSheets(ground: IntArray, static: IntArray, val staticWidth: Int, shadows: ByteArray, val shadowWidth: Int, val signals: ImageBitmap) {
    var ground = ground
        private set
    var static = static
        private set
    var shadows = shadows
        private set

    /** Set once the pixels are let go of, before they go; a reader on another thread takes them, then checks this and that none is empty. */
    @Volatile
    var released = false
        private set

    /** Lets go of the pixels, for the GPU map once they're on the GPU; the signals stay. */
    fun release() {
        released = true
        ground = IntArray(0)
        static = IntArray(0)
        shadows = ByteArray(0)
    }
}

/**
 * A vehicle drawing: its level 0 rects, as [Atlas.VEHICLE_RECTS] has them for one vehicle (32 sprites, or the 16 of a
 * pack vehicle, which has no odd sixteenths), each level's own in [levels] if its sheets are packed level by level
 * (null for a pack's, each level half the last), and its sheet at each level.
 */
class VehicleSource(val rects: IntArray, private val levels: Array<IntArray>? = null, val sheet: (level: Int) -> ByteArray?) {
    private val halved = arrayOfNulls<IntArray>(Atlas.LEVELS)

    /** The rects at [level], sizes and offsets in that level's pixels. */
    fun rectsAt(level: Int): IntArray =
        levels?.get(level) ?: halved[level] ?: IntArray(rects.size) { rects[it] / (1 shl level) }.also { halved[level] = it }
}

/** The packed sheets for each level, as read from the app, and each base vehicle's, by its place in [Atlas.VEHICLE_KEYS]. */
class PackedSheets(val ground: List<ByteArray>, val static: List<ByteArray>, val staticSnow: List<ByteArray?>, val shadows: List<ByteArray>, val signals: List<ByteArray>, val vehicles: List<List<ByteArray>>)

/**
 * The sheets, sharpest level first. They're kept packed and a level is only
 * unpacked the first time something asks for it, off the main thread; the
 * GPU map asks for the screen's level first, then every other.
 *
 * Main thread only: [get] hands back null while a level is still unpacking.
 */
class Sheets(private val packed: PackedSheets, private val scope: CoroutineScope) {
    private val levels = arrayOfNulls<LevelSheets>(Atlas.LEVELS)
    private val unpacking = BooleanArray(Atlas.LEVELS)
    /** Each level's decode while it runs. */
    private val decoding = arrayOfNulls<Job>(Atlas.LEVELS)
    /** Level decodes, a level's parts side by side; two threads, as four together took the Fire's heap near its 256 MB. */
    private val levelDecoder = Dispatchers.Default.limitedParallelism(2)
    /** Vehicles' own, so they don't wait behind a level's sheets. */
    private val vehicleDecoder = Dispatchers.Default.limitedParallelism(1)

    /** Goes up as each level is unpacked, so whatever waited on it draws again. */
    val revision = mutableIntStateOf(0)

    /** Which of each level's parts are unpacked, [PART_WEIGHTS]' order, for the loading cover. */
    private val partsIn = Array(Atlas.LEVELS) { BooleanArray(PART_WEIGHTS.size) }

    /** How much of [level]'s sheets are unpacked, 0 to 1, weighted by how long each part takes. */
    fun unpacked(level: Int): Float {
        if (levels[level] != null) return 1f
        var sum = 0f
        for (i in PART_WEIGHTS.indices) if (partsIn[level][i]) sum += PART_WEIGHTS[i]
        return sum
    }

    /**
     * [level]'s sheets, or null while they unpack. Every level stays once unpacked, as the GPU map holds them all.
     * Sheets whose pixels the GPU map let go of ([LevelSheets.released]) still come back, signals and all, till
     * [forget] has them unpacked again.
     */
    fun get(level: Int): LevelSheets? {
        if (level < Zoom.SHARPEST) return null
        levels[level]?.let { return it }
        if (!unpacking[level]) {
            unpacking[level] = true
            decoding[level] = scope.launch {
                partsIn[level].fill(false)
                levels[level] = withContext(levelDecoder) { coroutineScope { decodeLevel(level, partsIn[level]) } }
                decoding[level] = null
                revision.intValue++
            }
        }
        return null
    }

    /**
     * Decodes [level]'s four images side by side on [levelDecoder], then restores the static snow and copies the
     * shadows in strips across it. [done] gets each part as it's in, in [PART_WEIGHTS]' order.
     */
    @OptIn(ExperimentalResourceApi::class)
    private suspend fun CoroutineScope.decodeLevel(level: Int, done: BooleanArray): LevelSheets {
        warmWebp(packed.ground[Atlas.LEVELS - 1])
        fun pixels(bytes: ByteArray): Pair<IntArray, Int> {
            val image = bytes.decodeToImageBitmap()
            val out = IntArray(image.width * image.height)
            image.readPixels(out)
            return out to image.width
        }
        val ground = async { pixels(packed.ground[level]).first.also { done[0] = true } }
        val static = async {
            val (static, width) = pixels(packed.static[level])
            done[1] = true
            packed.staticSnow[level]?.let { snow ->
                strips(static.size, STRIPS).map { r -> launch { restoreStaticSnow(static, snow, r.first, r.last + 1) } }.joinAll()
            }
            done[2] = true
            static to width
        }
        // Grey, so any one channel is how dark. Read a few rows at a time, as the sharpest is some 70 MB as ints.
        val shadows = async {
            val shade = packed.shadows[level].decodeToImageBitmap()
            val w = shade.width
            val out = ByteArray(w * shade.height)
            strips(shade.height, STRIPS).map { rows ->
                launch {
                    val strip = IntArray(w * SHADE_ROWS)
                    for (y in rows step SHADE_ROWS) {
                        val n = minOf(SHADE_ROWS, rows.last + 1 - y)
                        shade.readPixels(strip, 0, y, w, n)
                        copyShade(strip, w * n, out, y * w)
                    }
                }
            }.joinAll()
            done[3] = true
            out to w
        }
        val signals = async { packed.signals[level].decodeToImageBitmap().also { done[4] = true } }
        val (staticPixels, staticWidth) = static.await()
        val (shadowBytes, shadowWidth) = shadows.await()
        return LevelSheets(ground.await(), staticPixels, staticWidth, shadowBytes, shadowWidth, signals.await())
    }

    private val signalsAlone = arrayOfNulls<ImageBitmap>(Atlas.LEVELS)
    private val signalsAsked = BooleanArray(Atlas.LEVELS)
    private val signalsFailed = IntArray(Atlas.LEVELS)

    /**
     * [level]'s signals sheet, or null while it unpacks: the level's own if it's in, else unpacked alone, for the GPU
     * map, which puts what moves on the GPU at every level before it takes the levels past the screen's.
     */
    @OptIn(ExperimentalResourceApi::class)
    fun signals(level: Int): ImageBitmap? {
        levels[level]?.let {
            // The level's own stands in from now on, so the one unpacked alone goes.
            signalsAlone[level] = null
            signalsAsked[level] = false
            signalsFailed[level] = 0
            return it.signals
        }
        signalsAlone[level]?.let { return it }
        if (level >= Zoom.SHARPEST && !signalsAsked[level] && signalsFailed[level] < 3) {
            signalsAsked[level] = true
            scope.launch {
                // Asked again next time if it fails, up to 3 times, or the level came in meanwhile.
                val got = try {
                    withContext(vehicleDecoder) { warmWebp(packed.ground[Atlas.LEVELS - 1]); packed.signals[level].decodeToImageBitmap() }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    signalsFailed[level]++
                    null
                } finally {
                    signalsAsked[level] = false
                }
                if (got != null && levels[level] == null) signalsAlone[level] = got
                revision.intValue++
            }
        }
        return null
    }

    private var sources: List<VehicleSource?> = emptyList()
    private val vehicles = HashMap<Int, ImageBitmap>()
    private val vehiclesUnpacking = HashSet<Int>()
    /** When each unpacked sheet was last asked for, in [nanos]. */
    private val vehicleUsed = HashMap<Int, Long>()
    private val clock = TimeSource.Monotonic.markNow()
    private fun nanos(): Long = clock.elapsedNow().inWholeNanoseconds
    private var vehicleBytes = 0L
    /** What unpacked vehicle sheets may hold: an eighth of the heap, 24 to 96 MB. */
    private val vehicleBudget = (heapBytes() / 8).coerceIn(24L shl 20, 96L shl 20)
    /** Goes up with each game's drawings, so a sheet still unpacking for the last game is dropped. */
    private var generation = 0

    /** The base pack's drawing [name], or null if there's none of that name. */
    fun baseVehicle(name: String): VehicleSource? {
        val i = Atlas.VEHICLE_KEYS.indexOf(name)
        if (i < 0) return null
        // Levels sharper than the renderer draws aren't held.
        val levels = Array(Atlas.LEVELS) { l -> ((i * Atlas.LEVELS + l) * Atlas.VEHICLE_STRIDE).let { Atlas.VEHICLE_RECTS.copyOfRange(it, it + Atlas.VEHICLE_STRIDE) } }
        return VehicleSource(levels[0], levels) { level -> packed.vehicles[i][level].takeIf { it.isNotEmpty() } }
    }

    /** The drawings of a game's vehicles, by their place in its roster's sprites. */
    fun useVehicles(list: List<VehicleSource?>) {
        sources = list
        vehicles.clear()
        vehicleUsed.clear()
        vehicleBytes = 0
        vehiclesUnpacking.clear()
        generation++
        asked = false
    }

    private var asked = false

    /** Once a game, asks for the sheets at [level] of every model in [v], those nearest ([x], [y]) first. */
    fun askVehicles(v: VehicleView, level: Int, x: Float, y: Float) {
        if (asked || v.count == 0) return
        asked = true
        val order = (0 until v.count).sortedBy { (v.x(it) - x) * (v.x(it) - x) + (v.y(it) - y) * (v.y(it) - y) }
        for (i in order) vehicle(v.sprite(i), level)
    }

    /**
     * The sprite for vehicle drawing [model] flat at heading [h16], in sixteenths of a turn. Even ones are the eight
     * headings; a drawing without the odd ones (a pack's) uses the eighth nearest, the later one on a tie.
     */
    fun flatCell(model: Int, h16: Int): Int = cell16(model, h16, 0, 16)

    /** The same for an aircraft in the air: its eight flying headings from sprite 8, the odd sixteenths from 24. */
    fun flyingCell(model: Int, h16: Int): Int = cell16(model, h16, 8, 24)

    private fun cell16(model: Int, h16: Int, even: Int, odd: Int): Int {
        if (h16 and 1 == 0) return even + (h16 shr 1)
        val rects = sources.getOrNull(model)?.rects
        val cell = odd + (h16 shr 1)
        if (rects != null && rects.size > cell * 6 + 3 && rects[cell * 6 + 2] > 0) return cell
        return even + ((h16 + 1 shr 1) and 7)
    }

    /** Vehicle drawing [model]'s level 0 rects, or null if it has none. */
    fun vehicleRects(model: Int): IntArray? = sources.getOrNull(model)?.rects

    /** Vehicle drawing [model]'s rects at [level], in that level's pixels, or null if it has none. */
    fun vehicleRects(model: Int, level: Int): IntArray? = sources.getOrNull(model)?.rectsAt(level)

    /** Whether the unpacked vehicle sheets are under their budget, once those long unused are let go. */
    fun vehicleRoom(): Boolean {
        trimVehicles()
        return vehicleBytes < vehicleBudget
    }

    /** Lets go of vehicle drawing [model]'s sheet at [level], once the GPU map has it on its own pages. */
    fun dropVehicle(model: Int, level: Int) {
        val key = model * Atlas.LEVELS + level
        val image = vehicles.remove(key) ?: return
        vehicleBytes -= image.width.toLong() * image.height * 4
        vehicleUsed.remove(key)
        vehiclesUnpacking.remove(key)
    }

    /**
     * Vehicle drawing [model]'s sheet at [level], unpacked the first time it's asked for if [ask] is set; null until
     * then.
     */
    @OptIn(ExperimentalResourceApi::class)
    fun vehicle(model: Int, level: Int, ask: Boolean = true): ImageBitmap? {
        val key = model * Atlas.LEVELS + level
        vehicles[key]?.let { vehicleUsed[key] = nanos(); return it }
        if (!ask) return null
        val source = sources.getOrNull(model) ?: return null
        if (vehiclesUnpacking.add(key)) {
            val mine = generation
            scope.launch {
                val image = withContext(vehicleDecoder) {
                    warmWebp(packed.ground[Atlas.LEVELS - 1])
                    source.sheet(level)?.decodeToImageBitmap()
                } ?: return@launch
                if (mine != generation) return@launch
                vehicles[key] = image
                vehicleUsed[key] = nanos()
                vehicleBytes += image.width.toLong() * image.height * 4
                trimVehicles()
                revision.intValue++
            }
        }
        return null
    }

    /** Lets go of the sheets longest unused while over the budget, but none asked for in the last [VEHICLE_KEEP_S] seconds. */
    private fun trimVehicles() {
        if (vehicleBytes <= vehicleBudget) return
        val now = nanos()
        val old = vehicleUsed.entries.filter { now - it.value > VEHICLE_KEEP_S * 1_000_000_000L }.sortedBy { it.value }
        for ((key, _) in old) {
            if (vehicleBytes <= vehicleBudget) break
            val image = vehicles.remove(key) ?: continue
            vehicleBytes -= image.width.toLong() * image.height * 4
            vehicleUsed.remove(key)
            vehiclesUnpacking.remove(key)
        }
    }

    private val statics = arrayOfNulls<ImageBitmap>(Atlas.LEVELS)
    private val staticsUnpacking = BooleanArray(Atlas.LEVELS)

    /**
     * The static sheet at [level] as a bitmap, for pictures of buildings in windows, unpacked the first time it's asked
     * for; null until then. Level 2 at the sharpest, some 6 MB as a bitmap, as it's a copy beside the renderer's.
     */
    @OptIn(ExperimentalResourceApi::class)
    fun staticImage(level: Int): ImageBitmap? {
        val at = level.coerceIn(PICTURE_SHARPEST, Atlas.LEVELS - 1)
        statics[at]?.let { return it }
        if (!staticsUnpacking[at]) {
            staticsUnpacking[at] = true
            scope.launch {
                statics[at] = withContext(levelDecoder) { warmWebp(packed.ground[Atlas.LEVELS - 1]); packed.static[at].decodeToImageBitmap() }
                revision.intValue++
            }
        }
        return null
    }

    /** Forgets [level]'s sheets, so the next [get] unpacks them again: for the GPU map's, once it's let go of their pixels and needs them back. */
    fun forget(level: Int) {
        if (decoding[level] != null) return
        levels[level] = null
        unpacking[level] = false
    }

    /** The level nearest [level] that's unpacked, for drawing scaled until it is. */
    fun nearest(level: Int): Int? {
        get(level)?.let { return level }
        return levels.indices.filter { levels[it] != null }.minByOrNull { kotlin.math.abs(it - level) }
    }

    private companion object {
        /** Rows of shadow read at a time per strip, 0.5 MB as ints at level 1. */
        const val SHADE_ROWS = 128
        /** How many strips the snow restore and shadow copy are split into, more than the threads so they even out. */
        const val STRIPS = 8
        /** The sharpest static sheet kept for pictures. */
        const val PICTURE_SHARPEST = 2
        const val VEHICLE_KEEP_S = 5

        /**
         * A level's parts as they count towards [unpacked]: ground, static pixels, static snow, shadows, signals.
         * From the sharpest level's times on the Fire: static webp and its read some 1.5 s, the snow 0.7 s.
         */
        val PART_WEIGHTS = floatArrayOf(0.05f, 0.5f, 0.25f, 0.15f, 0.05f)
    }
}

private val webpLock = Mutex()
@Volatile
private var webpWarmed = false

/**
 * Decodes [lossy], a small lossy webp, once and alone before any other sheet. The Fire's webp decoder (Android 7)
 * crashed in its lossy colour conversion when the first lossy decodes ran side by side, as if it sets that up on first use.
 */
@OptIn(ExperimentalResourceApi::class)
private suspend fun warmWebp(lossy: ByteArray) {
    if (webpWarmed) return
    webpLock.withLock {
        if (webpWarmed) return
        try {
            lossy.decodeToImageBitmap()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
        webpWarmed = true
    }
}

/** The packed sheets, read off the main thread; null until they're read. */
@Composable
fun rememberPackedSheets(): State<PackedSheets?> = produceState<PackedSheets?>(null) {
    value = withContext(Dispatchers.Default) {
        suspend fun read(name: String) = (0 until Atlas.LEVELS).map { Res.readBytes("files/${name}_$it.webp") }
        // Vehicle sheets sharper than the renderer draws are left unread.
        suspend fun readVehicle(name: String) = (0 until Atlas.LEVELS).map { if (it < Zoom.SHARPEST) ByteArray(0) else Res.readBytes("files/${name}_$it.webp") }
        val snow = (0 until Atlas.LEVELS).map { level ->
            if (level < 2) Res.readBytes("files/static_snow_$level.bin") else null
        }
        PackedSheets(read("ground"), read("static"), snow, read("shadows"), read("signals"), Atlas.VEHICLE_KEYS.map { readVehicle("vehicle_$it") })
    }
}
