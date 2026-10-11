package com.rm.interchange.sim

/**
 * The map and everything on it. Layers are flat arrays: tiles indexed by
 * `y * width + x`, corners by `y * (width + 1) + x`. All maths is in integers
 * so every copy of the world stays the same to the bit: no floats in here.
 *
 * Land is a height at each tile corner, in steps. Corners next to each other,
 * across or diagonally, differ by at most one, so every tile is flat, a slope
 * or a corner raised or sunk. A tile with all four corners at 0 is water.
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
        generate()
    }

    fun index(x: Int, y: Int) = y * width + x

    fun corner(x: Int, y: Int) = y * (width + 1) + x

    fun inside(x: Int, y: Int) = x in 0 until width && y in 0 until height

    fun cornerInside(x: Int, y: Int) = x in 0..width && y in 0..height

    fun heightAt(cx: Int, cy: Int): Int = corners[corner(cx, cy)].toInt()

    fun isWater(x: Int, y: Int): Boolean =
        heightAt(x, y) == 0 && heightAt(x + 1, y) == 0 && heightAt(x + 1, y + 1) == 0 && heightAt(x, y + 1) == 0

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

    fun trackAt(x: Int, y: Int): Int = track[index(x, y)].toInt()

    fun isFlat(x: Int, y: Int): Boolean {
        val n = heightAt(x, y)
        return n == heightAt(x + 1, y) && n == heightAt(x + 1, y + 1) && n == heightAt(x, y + 1)
    }

    /**
     * Whether track [pieces] on tile ([x], [y]) need a foundation, building
     * the tile up level with its highest corner. Only a single straight piece
     * running straight up a slope lies on the land as it is.
     */
    fun needsFoundation(x: Int, y: Int, pieces: Int): Boolean {
        if (pieces == 0 || isFlat(x, y)) return false
        val n = heightAt(x, y)
        val e = heightAt(x + 1, y)
        val s = heightAt(x + 1, y + 1)
        val w = heightAt(x, y + 1)
        return when (pieces) {
            Track.X -> !(n == w && e == s)
            Track.Y -> !(n == e && w == s)
            else -> true
        }
    }

    fun hasFoundation(x: Int, y: Int) = needsFoundation(x, y, trackAt(x, y))

    /** Whether [piece] could go on tile ([x], [y]). Anywhere dry will do, on a foundation if need be. */
    fun pieceFits(x: Int, y: Int, piece: Int): Boolean = inside(x, y) && !isWater(x, y)

    /** Runs one command. What can't be done is left undone. */
    fun apply(command: Command) {
        val company = companies.getOrNull(command.company) ?: return
        when (command) {
            is Command.Terraform -> terraform(company, command.x, command.y, command.up)
            is Command.Rail -> rail(company, command)
        }
    }

    private fun rail(company: Company, c: Command.Rail) {
        val line = Track.line(this, c.sx, c.sy, c.ex, c.ey, c.first) ?: return
        for (entry in line) {
            val i = entry / 64
            val piece = entry % 64
            val x = i % width
            val y = i / width
            val has = track[i].toInt()
            if (c.remove) {
                if (has and piece == 0 || owner[i].toInt() != company.id) continue
                if (company.money < Prices.TRACK_REMOVE) return
                company.money -= Prices.TRACK_REMOVE
                track[i] = (has and piece.inv()).toByte()
                if (track[i].toInt() == 0) owner[i] = -1
            } else {
                if (has and piece != 0 || !pieceFits(x, y, piece)) continue
                if (has != 0 && owner[i].toInt() != company.id) continue
                val cost = Prices.TRACK + if (!needsFoundation(x, y, has) && needsFoundation(x, y, has or piece)) Prices.FOUNDATION else 0
                if (company.money < cost) return
                company.money -= cost
                track[i] = (has or piece).toByte()
                owner[i] = company.id.toByte()
            }
            touched(x, y)
        }
    }

    /**
     * Moves corner ([cx], [cy]) a step and pushes the corners round it along so
     * none is more than a step from its neighbours. Nothing moves if any tile
     * it would change has track on it.
     */
    private fun terraform(company: Company, cx: Int, cy: Int, up: Boolean) {
        if (!cornerInside(cx, cy)) return
        val target = heightAt(cx, cy) + if (up) 1 else -1
        if (target < 0 || target > MAX_HEIGHT) return
        val changed = LinkedHashMap<Int, Int>()
        val queue = ArrayDeque<Int>()
        changed[corner(cx, cy)] = target
        queue.addLast(corner(cx, cy))
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            val x = c % (width + 1)
            val y = c / (width + 1)
            val h = changed.getValue(c)
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if ((dx == 0 && dy == 0) || !cornerInside(nx, ny)) continue
                val n = corner(nx, ny)
                val nh = changed[n] ?: heightAt(nx, ny)
                val want = if (up) h - 1 else h + 1
                if (if (up) nh < want else nh > want) {
                    changed[n] = want
                    queue.addLast(n)
                }
            }
        }
        val cost = Prices.TERRAFORM * changed.size
        if (company.money < cost) return
        for (c in changed.keys) {
            val x = c % (width + 1)
            val y = c / (width + 1)
            for (ty in y - 1..y) for (tx in x - 1..x) if (inside(tx, ty) && track[index(tx, ty)].toInt() != 0) return
        }
        company.money -= cost
        for ((c, h) in changed) {
            corners[c] = h.toByte()
            val x = c % (width + 1)
            val y = c / (width + 1)
            for (ty in y - 1..y) for (tx in x - 1..x) if (inside(tx, ty)) touched(tx, ty)
        }
    }

    private fun touched(x: Int, y: Int) {
        chunkVersions[(y / CHUNK) * (width / CHUNK) + x / CHUNK]++
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
        for (b in corners) mix(b.toLong())
        for (b in track) mix(b.toLong())
        for (b in owner) mix(b.toLong())
        for (c in companies) mix(c.money)
        return h
    }

    /**
     * Rolling land from a few layers of smooth noise, then pulled down wherever
     * a corner stands more than a step above a neighbour.
     */
    private fun generate() {
        val w = width + 1
        val h = height + 1
        val sum = IntArray(w * h)
        // Only broad layers: finer ones jitter around each step and leave hardly a flat tile.
        var spacing = 128
        var weight = 16
        while (spacing >= 16) {
            val gw = w / spacing + 2
            val gh = h / spacing + 2
            val grid = IntArray(gw * gh) { rng.below(256) }
            for (y in 0 until h) for (x in 0 until w) {
                val gx = x / spacing
                val gy = y / spacing
                // Weights in 256ths, smoothed so the grid doesn't show.
                val fx = smooth((x % spacing) * 256 / spacing)
                val fy = smooth((y % spacing) * 256 / spacing)
                val a = grid[gy * gw + gx] * (256 - fx) + grid[gy * gw + gx + 1] * fx
                val b = grid[(gy + 1) * gw + gx] * (256 - fx) + grid[(gy + 1) * gw + gx + 1] * fx
                sum[y * w + x] += (a / 256 * (256 - fy) + b / 256 * fy) / 256 * weight
            }
            spacing /= 2
            weight /= 2
        }
        // About a third under water: 0 at the 28th percentile, as a tile needs all four corners up to be dry, then a step for every few points above.
        val sorted = sum.sortedArray()
        val sea = sorted[sorted.size * 28 / 100]
        val top = sorted[sorted.size - 1]
        for (i in sum.indices) {
            sum[i] = ((sum[i] - sea).coerceAtLeast(0).toLong() * (MAX_HEIGHT * 3 / 4) / (top - sea).coerceAtLeast(1)).toInt()
        }
        // Each corner no more than a step above any of its eight neighbours, in two sweeps.
        for (y in 0 until h) for (x in 0 until w) {
            var m = sum[y * w + x]
            if (x > 0) m = minOf(m, sum[y * w + x - 1] + 1)
            if (y > 0) {
                m = minOf(m, sum[(y - 1) * w + x] + 1)
                if (x > 0) m = minOf(m, sum[(y - 1) * w + x - 1] + 1)
                if (x < w - 1) m = minOf(m, sum[(y - 1) * w + x + 1] + 1)
            }
            sum[y * w + x] = m
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            var m = sum[y * w + x]
            if (x < w - 1) m = minOf(m, sum[y * w + x + 1] + 1)
            if (y < h - 1) {
                m = minOf(m, sum[(y + 1) * w + x] + 1)
                if (x < w - 1) m = minOf(m, sum[(y + 1) * w + x + 1] + 1)
                if (x > 0) m = minOf(m, sum[(y + 1) * w + x - 1] + 1)
            }
            sum[y * w + x] = m
        }
        for (i in sum.indices) corners[i] = sum[i].coerceIn(0, MAX_HEIGHT).toByte()
    }

    private fun smooth(t: Int): Int = t * t * (768 - 2 * t) / 65536

    companion object {
        const val MAX_HEIGHT = 24
        /** Tiles a side in a block the renderer redraws together; map sides are a multiple of it. */
        const val CHUNK = 16
    }
}
