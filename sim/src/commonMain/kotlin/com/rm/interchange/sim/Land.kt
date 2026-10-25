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
    val g = world.settings
    val sum = IntArray(w * h)
    // Only broad layers: finer ones jitter around each step and leave hardly a flat tile. The Rockies and the
    // Shield add a finer one, for ridges and for lakes everywhere.
    val layers = ArrayList<Triple<Int, Int, IntArray>>()
    var spacing = 128
    var weight = 16
    val finest = if (g.region == Region.ROCKIES || g.region == Region.SHIELD) 8 else 16
    while (spacing >= finest) {
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
    // The Maritimes have the sea round their edges: the land falls away towards them.
    if (g.region == Region.MARITIMES) {
        parallelBands(h) { from, until ->
            for (y in from until until) for (x in 0 until w) {
                val edge = minOf(minOf(x, w - 1 - x), minOf(y, h - 1 - y))
                val reach = minOf(w, h) / 5
                if (edge < reach) sum[y * w + x] = sum[y * w + x] * edge / reach
            }
        }
    }
    // As much under water as asked: 0 at that percentile, a tile needing all four corners up to be dry.
    var top = 0
    for (v in sum) if (v > top) top = v
    val counts = IntArray(top + 1)
    for (v in sum) counts[v]++
    var sea = 0
    var below = 0
    while (sea < top && below + counts[sea] < sum.size.toLong() * g.sea.coerceIn(0, 80) / 100) below += counts[sea++]
    val max = World.MAX_HEIGHT
    // How high the highest land goes, by how hilly the map was asked to be and where it is.
    var peak = when (g.hills) { 0 -> 6; 1 -> 14; 2 -> 19; else -> 24 }
    when (g.region) {
        Region.PRAIRIES, Region.NORTH -> peak = minOf(peak, 10)
        Region.ROCKIES -> peak = maxOf(peak, 22)
        else -> {}
    }
    // Mountain land rises quickly from the shore and keeps rising to its peaks: f becomes (3f - f²) / 2, halfway
    // between the plain rise and one that levels off at the top, so most of it stands high without flat tops.
    val raised = g.region == Region.ROCKIES || g.hills >= 3
    val range = (top - sea).coerceAtLeast(1).toLong()
    parallelBands(sum.size) { from, until ->
        for (i in from until until) {
            val f = (sum[i] - sea).coerceAtLeast(0).toLong()
            sum[i] = if (raised) (peak * (3 * f * range - f * f) / (2 * range * range)).toInt() else (f * peak / range).toInt()
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
    val cost = price(Prices.TERRAFORM) * changed.size
    if (company.money < cost) return
    for (c in changed.keys) {
        val x = c % (width + 1)
        val y = c / (width + 1)
        for (ty in y - 1..y) for (tx in x - 1..x) if (inside(tx, ty) && (track[index(tx, ty)].toInt() != 0 || townBuilt(index(tx, ty)) || stationBuildings.containsKey(index(tx, ty)))) return
    }
    spend(company, Books.CONSTRUCTION, cost)
    for ((c, h) in changed) {
        corners[c] = h.toByte()
        val x = c % (width + 1)
        val y = c / (width + 1)
        for (ty in y - 1..y) for (tx in x - 1..x) if (inside(tx, ty)) touched(tx, ty)
    }
    if (target == 0 || target == 1) shoreChanged()
}

/**
 * Levels the tiles [c] names to [Command.Level.height], the land round them following, the same as the editor's level.
 * Each corner that moves costs what raising one does. Short of the money, or with something built in the way, nothing moves.
 */
internal fun World.levelLand(company: Company, c: Command.Level) {
    val changed = terrainChanges(c.x0, c.y0, c.x1, c.y1, Command.EditTerrain.LEVEL, c.height) ?: return
    if (changed.isEmpty()) return
    val cost = price(Prices.TERRAFORM) * changed.size
    if (company.money < cost) return
    spend(company, Books.CONSTRUCTION, cost)
    applyTerrainChanges(changed)
}

/** What [c] would cost, and how many tiles it covers. Off the map, too big, or blocked, both are 0. Land that stays put costs nothing and still counts its tiles. */
fun World.levelCost(c: Command.Level): Pair<Long, Int> {
    val changed = terrainChanges(c.x0, c.y0, c.x1, c.y1, Command.EditTerrain.LEVEL, c.height) ?: return 0L to 0
    val box = area(c.x0, c.y0, c.x1, c.y1) ?: return 0L to 0
    val n = (box[2] - box[0] + 1) * (box[3] - box[1] + 1)
    return price(Prices.TERRAFORM) * changed.size to n
}

/**
 * Plants trees on the open tiles [c] names, the kinds the ground grows, the same as the editor.
 * Each tile that takes one costs what felling a tree does. Short of the money, none are planted.
 */
internal fun World.plantTrees(company: Company, c: Command.PlantTrees) {
    val (ax, ay, bx, by) = area(c.x0, c.y0, c.x1, c.y1) ?: return
    var n = 0
    for (y in ay..by) for (x in ax..bx) if (canPlantTree(x, y)) n++
    if (n == 0) return
    val cost = price(Prices.TREE) * n
    if (company.money < cost) return
    spend(company, Books.CONSTRUCTION, cost)
    for (y in ay..by) for (x in ax..bx) if (canPlantTree(x, y)) plantTree(x, y)
}

/** What [c] would cost, and how many tiles would take a tree. */
fun World.plantCost(c: Command.PlantTrees): Pair<Long, Int> {
    val (ax, ay, bx, by) = area(c.x0, c.y0, c.x1, c.y1) ?: return 0L to 0
    var n = 0
    for (y in ay..by) for (x in ax..bx) if (canPlantTree(x, y)) n++
    return price(Prices.TREE) * n to n
}
