package com.rm.interchange.sim

/**
 * Rolling land from a few layers of smooth noise, then pulled down wherever a
 * corner stands more than a step above a neighbour. The noise is drawn first,
 * in order, so the map only depends on the seed; adding the layers up is
 * split across the cores in bands of rows.
 */
internal fun generateLand(world: World) {
    val w = world.width + 1
    val h = world.height + 1
    val sum = IntArray(w * h)
    // Only broad layers: finer ones jitter around each step and leave hardly a flat tile.
    val layers = ArrayList<Triple<Int, Int, IntArray>>()
    var spacing = 128
    var weight = 16
    while (spacing >= 16) {
        val gw = w / spacing + 2
        val gh = h / spacing + 2
        layers += Triple(spacing, weight, IntArray(gw * gh) { world.rng.below(256) })
        spacing /= 2
        weight /= 2
    }
    parallelBands(h) { from, until ->
        for ((sp, wt, grid) in layers) {
            val gw = w / sp + 2
            for (y in from until until) for (x in 0 until w) {
                val gx = x / sp
                val gy = y / sp
                // Weights in 256ths, smoothed so the grid doesn't show.
                val fx = smooth((x % sp) * 256 / sp)
                val fy = smooth((y % sp) * 256 / sp)
                val a = grid[gy * gw + gx] * (256 - fx) + grid[gy * gw + gx + 1] * fx
                val b = grid[(gy + 1) * gw + gx] * (256 - fx) + grid[(gy + 1) * gw + gx + 1] * fx
                sum[y * w + x] += (a / 256 * (256 - fy) + b / 256 * fy) / 256 * wt
            }
        }
    }
    // About a third under water: 0 at the 28th percentile, as a tile needs all four corners up to be dry.
    var top = 0
    for (v in sum) if (v > top) top = v
    val counts = IntArray(top + 1)
    for (v in sum) counts[v]++
    var sea = 0
    var below = 0
    while (sea < top && below + counts[sea] < sum.size * 28 / 100) below += counts[sea++]
    val max = World.MAX_HEIGHT
    parallelBands(sum.size) { from, until ->
        for (i in from until until) {
            sum[i] = ((sum[i] - sea).coerceAtLeast(0).toLong() * (max * 3 / 4) / (top - sea).coerceAtLeast(1)).toInt()
        }
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
    for (i in sum.indices) world.corners[i] = sum[i].coerceIn(0, max).toByte()
}

private fun smooth(t: Int): Int = t * t * (768 - 2 * t) / 65536

/**
 * Moves corner ([cx], [cy]) a step and pushes the corners round it along so
 * none is more than a step from its neighbours. Nothing moves if any tile it
 * would change has track on it.
 */
internal fun World.terraform(company: Company, cx: Int, cy: Int, up: Boolean) {
    if (!cornerInside(cx, cy)) return
    val target = heightAt(cx, cy) + if (up) 1 else -1
    if (target < 0 || target > World.MAX_HEIGHT) return
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
