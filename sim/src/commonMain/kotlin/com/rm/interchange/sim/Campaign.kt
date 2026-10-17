package com.rm.interchange.sim

/*
 * The campaign: chapters made in code. Each chapter is a recipe that makes the
 * same world every time from its own settings and seed: the land shaped with
 * the helpers below, named towns and set industries placed, then its goals,
 * money and win rule. A chapter's title and intro come from the game's strings
 * by its id, so the sim holds no text. The world it gives is a scenario to
 * play, as [Scenarios.play] gives, with [Scenario.chapter] set to its id.
 */

object Campaign {
    /** A chapter: its [id], the year it starts and the year its goals are due, and its [recipe], null while it's still to come. */
    class Chapter internal constructor(val id: String, val startYear: Int, val endYear: Int, internal val recipe: Recipe?) {
        val ready get() = recipe != null
    }

    val CHAPTERS = listOf(
        Chapter("portage", 1836, PortageLine.END, PortageLine),
        Chapter("trunk", 1856, TrunkLine.END, TrunkLine),
        Chapter("last_spike", 1881, LastSpike.END, LastSpike),
        Chapter("seaway", 1954, Seaway.END, Seaway),
        Chapter("iron_ore", 1955, IronOre.END, IronOre),
        Chapter("high_speed", 2032, HighSpeed.END, HighSpeed),
        Chapter("warming_north", 2062, WarmingNorth.END, WarmingNorth),
    )

    fun chapter(id: String) = CHAPTERS.firstOrNull { it.id == id }

    /** The chapter after [id], or null if it's the last. */
    fun next(id: String) = CHAPTERS.indexOfFirst { it.id == id }.let { if (it < 0) null else CHAPTERS.getOrNull(it + 1) }

    /** Chapter [id]'s world to play, called [title] with [intro], its packs found by [find]; null if it isn't ready. */
    fun play(id: String, title: String, intro: String, find: (PackRef) -> Pack? = Save::basePack): World? {
        val w = build(id, title, intro) ?: return null
        return Scenarios.play(Scenarios.export(w), find)
    }

    /** Chapter [id] as its recipe leaves it, still an editor world; null if it isn't ready. */
    internal fun build(id: String, title: String, intro: String): World? {
        val r = chapter(id)?.recipe ?: return null
        val w = World(r.settings, Roster.BASE, generate = false)
        generateLand(w)
        r.shape(w)
        w.landShaped()
        w.editor = true
        w.scenario = Scenario()
        val goals = r.fill(w)
        w.companies[0].name = w.playerCompanyName(0)
        w.giveInsignia(w.companies[0])
        if (r.settings.rivals > 0) {
            w.startRivals(r.settings.rivals)
            for ((k, rival) in w.rivals.withIndex()) r.rivalNames.getOrNull(k)?.let { w.companies[rival.company].name = it }
        }
        r.started(w)
        w.startWeather()
        w.apply(Command.SetGoals(0, goals))
        w.apply(Command.SetScenario(0, title, intro, r.money, r.loan, Scenario.ALL, r.settings.startYear, id))
        return w
    }
}

/** How a chapter's world is made: its [settings], what the player starts with, its land, and its towns, industries and goals. */
internal abstract class Recipe {
    abstract val settings: NewGame
    abstract val money: Long
    open val loan = 0L
    /** Names for the rivals [settings] asks for, in the order they start. */
    open val rivalNames: List<String> = emptyList()

    /** Shapes the land, from the noise [generateLand] made. */
    abstract fun shape(w: World)

    /** Places the towns and industries, in an editor world, and gives the goals. */
    abstract fun fill(w: World): List<Goal>

    /** Anything else once the rivals have started, still in the editor. */
    open fun started(w: World) {}
}

// Land shaping. Every helper keeps corners within a step of their neighbours: each sets a corner from its distance to
// an area, and a height that changes by at most one a step from one corner to the next keeps the land as it must be.

/** A set of tiles to shape the land by. */
internal class Area(private val w: World) {
    val tiles = BooleanArray(w.width * w.height)

    fun has(x: Int, y: Int) = w.inside(x, y) && tiles[w.index(x, y)]

    /** Tiles from ([x0], [y0]) to ([x1], [y1]) either way round. */
    fun rect(x0: Int, y0: Int, x1: Int, y1: Int): Area {
        for (y in minOf(y0, y1)..maxOf(y0, y1)) for (x in minOf(x0, x1)..maxOf(x0, x1)) if (w.inside(x, y)) tiles[w.index(x, y)] = true
        return this
    }

    /**
     * An oval round ([cx], [cy]) reaching [rx] and [ry] tiles, its edge ragged by up to [rough] 256ths of its size
     * by [roughness] with [salt].
     */
    fun oval(cx: Int, cy: Int, rx: Int, ry: Int, rough: Int = 0, salt: Int = 0): Area {
        val rx2 = rx.toLong() * rx
        val ry2 = ry.toLong() * ry
        for (y in maxOf(0, cy - ry * 2)..minOf(w.height - 1, cy + ry * 2)) for (x in maxOf(0, cx - rx * 2)..minOf(w.width - 1, cx + rx * 2)) {
            val dx = (x - cx).toLong()
            val dy = (y - cy).toLong()
            val wob = if (rough == 0) 0 else w.roughness(x, y, salt, 12) * rough / 128
            if ((dx * dx * ry2 + dy * dy * rx2) * 256 <= rx2 * ry2 * (256 + wob)) tiles[w.index(x, y)] = true
        }
        return this
    }

    /** A band [half] tiles either side of the line through [points], given as x, y pairs. */
    fun line(points: IntArray, half: Int): Area {
        for (k in 0 until points.size / 2 - 1) {
            val ax = points[k * 2]; val ay = points[k * 2 + 1]
            val bx = points[k * 2 + 2]; val by = points[k * 2 + 3]
            for (y in maxOf(0, minOf(ay, by) - half)..minOf(w.height - 1, maxOf(ay, by) + half)) {
                for (x in maxOf(0, minOf(ax, bx) - half)..minOf(w.width - 1, maxOf(ax, bx) + half)) {
                    if (segmentDistance2(x, y, ax, ay, bx, by) <= half.toLong() * half) tiles[w.index(x, y)] = true
                }
            }
        }
        return this
    }

    /** Every tile on or below line y = [edge] of x (one entry per column), to the map's bottom. */
    fun below(edge: IntArray): Area {
        for (x in 0 until w.width) for (y in maxOf(0, edge[x])..w.height - 1) tiles[w.index(x, y)] = true
        return this
    }

    /** Adds [other]'s tiles to this. */
    fun plus(other: Area): Area {
        for (i in tiles.indices) if (other.tiles[i]) tiles[i] = true
        return this
    }

    /** Takes [other]'s tiles out of this. */
    fun minus(other: Area): Area {
        for (i in tiles.indices) if (other.tiles[i]) tiles[i] = false
        return this
    }

    /**
     * A copy with a ragged edge, by noise from the map's seed and [salt]: tiles up to [depth] outside taken in and up
     * to half that inside let go. Tiles in [keepOut] are never taken in.
     */
    fun ragged(depth: Int, salt: Int, keepOut: Area? = null): Area {
        val out = Area(w)
        val inside = distances(true, depth)
        val outside = distances(false, depth)
        for (y in 0 until w.height) for (x in 0 until w.width) {
            val i = w.index(x, y)
            // Big bays and points with small coves on them, stretched so the noise reaches its ends.
            val n = ((w.roughness(x, y, salt, 9) * 2 + w.roughness(x, y, salt + 1, 4)) / 2).coerceIn(-128, 128)
            out.tiles[i] = if (tiles[i]) {
                val d = inside[i]
                !(d <= depth / 2 && -n * (depth / 2) > (d - 1) * 128 + 64)
            } else {
                val d = outside[i]
                d <= depth && n * depth > (d - 1) * 128 + 64 && keepOut?.tiles?.get(i) != true
            }
        }
        return out
    }

    /** For each tile in the area ([into] true) or out of it, how many tiles it is from the other side, up to [most] + 1. */
    private fun distances(into: Boolean, most: Int): IntArray {
        val d = IntArray(tiles.size) { most + 1 }
        val queue = IntArray(tiles.size)
        var tail = 0
        for (y in 0 until w.height) for (x in 0 until w.width) {
            val i = w.index(x, y)
            if (tiles[i] != into) continue
            var edge = false
            for (dy in -1..1) for (dx in -1..1) if (w.inside(x + dx, y + dy) && tiles[w.index(x + dx, y + dy)] != into) edge = true
            if (edge) { d[i] = 1; queue[tail++] = i }
        }
        var head = 0
        while (head < tail) {
            val i = queue[head++]
            if (d[i] >= most) continue
            val x = i % w.width
            val y = i / w.width
            for (dy in -1..1) for (dx in -1..1) {
                if (!w.inside(x + dx, y + dy)) continue
                val n = w.index(x + dx, y + dy)
                if (tiles[n] == into && d[n] > d[i] + 1) { d[n] = d[i] + 1; queue[tail++] = n }
            }
        }
        return d
    }
}

/** The square of the distance from ([x], [y]) to the segment from a to b, in whole tiles. */
private fun segmentDistance2(x: Int, y: Int, ax: Int, ay: Int, bx: Int, by: Int): Long {
    val vx = (bx - ax).toLong(); val vy = (by - ay).toLong()
    val px = (x - ax).toLong(); val py = (y - ay).toLong()
    val len = vx * vx + vy * vy
    if (len == 0L) return px * px + py * py
    val t = (px * vx + py * vy).coerceIn(0, len)
    // The nearest point, in len-ths of a tile, then the distance squared back in whole tiles.
    val dx = px * len - t * vx
    val dy = py * len - t * vy
    return (dx * dx + dy * dy) / (len * len)
}

/** Smooth bumps from -128 to 128 over the map, about [spacing] tiles apart, the same for the same seed and [salt]. */
internal fun World.roughness(x: Int, y: Int, salt: Int, spacing: Int): Int {
    val gx = x / spacing; val gy = y / spacing
    val fx = (x % spacing) * 256 / spacing; val fy = (y % spacing) * 256 / spacing
    val a = shapeDice(gx, gy, salt) * (256 - fx) + shapeDice(gx + 1, gy, salt) * fx
    val b = shapeDice(gx, gy + 1, salt) * (256 - fx) + shapeDice(gx + 1, gy + 1, salt) * fx
    return (a / 256 * (256 - fy) + b / 256 * fy) / 256 - 128
}

/**
 * Rounder bumps than [roughness], from -128 to 128 about [spacing] tiles apart, on a grid turned a little off the
 * map's rows so no straight lines show.
 */
internal fun World.bumps(x: Int, y: Int, salt: Int, spacing: Int): Int {
    // (4, 2) and (-2, 4) are about 4.5 long, so the turned grid's cells are 4.5 times the spacing in these units.
    val u = x * 4 + y * 2 + 8192
    val v = y * 4 - x * 2 + 8192
    val sp = maxOf(1, spacing * 9 / 2)
    fun ease(f: Int) = f * f * (768 - 2 * f) / 65536
    val fx = ease(u % sp * 256 / sp)
    val fy = ease(v % sp * 256 / sp)
    val gx = u / sp; val gy = v / sp
    val a = shapeDice(gx, gy, salt) * (256 - fx) + shapeDice(gx + 1, gy, salt) * fx
    val b = shapeDice(gx, gy + 1, salt) * (256 - fx) + shapeDice(gx + 1, gy + 1, salt) * fx
    return (a / 256 * (256 - fy) + b / 256 * fy) / 256 - 128
}

/** Smooth bumps along a line, from -[amp] to [amp], about [spacing] tiles apart. */
internal fun World.wander(t: Int, salt: Int, spacing: Int, amp: Int): Int = roughness(t, 0, salt, spacing) * amp / 128

/** A roll from 0 to 255 for ([x], [y]), the same for the same seed and [salt]. */
internal fun World.shapeDice(x: Int, y: Int, salt: Int): Int {
    var h = x * 374761393 + y * 668265263 + salt * 1442695041 + seedBits
    h = (h xor (h ushr 13)) * 1274126177
    return (h xor (h ushr 16)) and 255
}

/** How far each corner is from [area]'s corners, in steps across or diagonally. */
internal fun World.cornerDistance(area: Area): IntArray {
    val cw = width + 1
    val d = IntArray(cw * (height + 1)) { Int.MAX_VALUE }
    val queue = IntArray(d.size)
    var tail = 0
    for (y in 0 until height) for (x in 0 until width) {
        if (!area.tiles[index(x, y)]) continue
        for (k in intArrayOf(corner(x, y), corner(x + 1, y), corner(x, y + 1), corner(x + 1, y + 1))) {
            if (d[k] != 0) { d[k] = 0; queue[tail++] = k }
        }
    }
    var head = 0
    while (head < tail) {
        val k = queue[head++]
        val x = k % cw
        val y = k / cw
        for (dy in -1..1) for (dx in -1..1) {
            val nx = x + dx; val ny = y + dy
            if (!cornerInside(nx, ny)) continue
            val n = ny * cw + nx
            if (d[n] == Int.MAX_VALUE) { d[n] = d[k] + 1; queue[tail++] = n }
        }
    }
    return d
}

/** All the land [by] steps higher, as high as it goes. */
internal fun World.liftLand(by: Int) {
    for (k in corners.indices) corners[k] = (corners[k] + by).coerceIn(0, World.MAX_HEIGHT).toByte()
}

/**
 * The land under [area] at sea level, so it's water, and the land round it falling to it. Its edge is made ragged
 * by up to [rough] tiles, with [salt], except into [dry].
 */
internal fun World.carveWater(area: Area, rough: Int = 3, salt: Int = 0, dry: Area? = null) {
    val d = cornerDistance(if (rough > 0) area.ragged(rough, 900 + salt, dry) else area)
    val cw = width + 1
    for (k in corners.indices) {
        if (d[k] >= corners[k]) continue
        // Steep banks in places and shelving shores in others, up to a step every three tiles.
        val shelf = if (rough > 0) maxOf(0, roughness(k % cw, k / cw, 950 + salt, 11)) * 3 else 0
        corners[k] = minOf(corners[k].toInt(), d[k] * 256 / (256 + shelf)).toByte()
    }
    settleLand()
}

/** The land under [area] at [height], and the land round it meeting it, a step a tile. */
internal fun World.levelLand(area: Area, height: Int) {
    val d = cornerDistance(area)
    for (k in corners.indices) {
        if (d[k] == Int.MAX_VALUE) continue
        val h = corners[k].toInt()
        corners[k] = h.coerceIn(maxOf(0, height - d[k]), minOf(World.MAX_HEIGHT, height + d[k])).toByte()
    }
}

/** A ridge or range [top] high along [area], falling a step every [spread] tiles; land already higher stays. */
internal fun World.raiseLand(area: Area, top: Int, spread: Int = 1) {
    val d = cornerDistance(area)
    for (k in corners.indices) {
        if (d[k] == Int.MAX_VALUE) continue
        val want = (top - d[k] / spread).coerceAtMost(World.MAX_HEIGHT)
        if (want > corners[k]) corners[k] = want.toByte()
    }
}

/** A valley or pass [bottom] deep along [area], rising a step every [spread] tiles; land already lower stays. */
internal fun World.lowerLand(area: Area, bottom: Int, spread: Int = 1) {
    val d = cornerDistance(area)
    for (k in corners.indices) {
        if (d[k] == Int.MAX_VALUE) continue
        val want = bottom + d[k] / spread
        if (want < corners[k]) corners[k] = want.toByte()
    }
}

/**
 * A valley or pass along [points], given as x, y pairs, [half] tiles either side, its floor from [bottoms] at each
 * point to the next and its walls rising a step every [spread] tiles; land already lower stays.
 */
internal fun World.valleyAlong(points: IntArray, bottoms: IntArray, half: Int, spread: Int = 1) {
    val cw = width + 1
    val top = (World.MAX_HEIGHT + 1) * spread
    // Each corner's floor height times spread, from the nearest bit of floor; worked out from the lowest up.
    val v = IntArray(cw * (height + 1)) { Int.MAX_VALUE }
    val buckets = Array(top + 1) { ArrayList<Int>() }
    for (k in 0 until points.size / 2 - 1) {
        val ax = points[k * 2]; val ay = points[k * 2 + 1]
        val bx = points[k * 2 + 2]; val by = points[k * 2 + 3]
        val n = maxOf(kotlin.math.abs(bx - ax), kotlin.math.abs(by - ay), 1)
        for (t in 0..n) {
            val x = ax + (bx - ax) * t / n
            val y = ay + (by - ay) * t / n
            val floor = (bottoms[k] + (bottoms[k + 1] - bottoms[k]) * t / n) * spread
            for (dy in -half..half + 1) for (dx in -half..half + 1) {
                if (!cornerInside(x + dx, y + dy)) continue
                val c = (y + dy) * cw + x + dx
                if (floor < v[c] && floor <= top) { v[c] = floor; buckets[floor] += c }
            }
        }
    }
    for (b in 0..top) {
        var j = 0
        while (j < buckets[b].size) {
            val c = buckets[b][j++]
            if (v[c] != b) continue
            val x = c % cw
            val y = c / cw
            for (dy in -1..1) for (dx in -1..1) {
                if (!cornerInside(x + dx, y + dy)) continue
                val n = (y + dy) * cw + x + dx
                if (b + 1 < v[n] && b + 1 <= top) { v[n] = b + 1; buckets[b + 1] += n }
            }
        }
    }
    for (k in corners.indices) {
        if (v[k] == Int.MAX_VALUE) continue
        val want = v[k] / spread
        if (want < corners[k]) corners[k] = want.toByte()
    }
}

/** Every corner pulled down to within a step of its neighbours, after land's been scaled. */
internal fun World.settleLand() {
    val cw = width + 1
    val buckets = Array(World.MAX_HEIGHT + 1) { ArrayList<Int>() }
    for (k in corners.indices) buckets[corners[k].toInt()] += k
    for (b in 0..World.MAX_HEIGHT) {
        var j = 0
        while (j < buckets[b].size) {
            val c = buckets[b][j++]
            if (corners[c].toInt() != b) continue
            val x = c % cw
            val y = c / cw
            for (dy in -1..1) for (dx in -1..1) {
                if (!cornerInside(x + dx, y + dy)) continue
                val n = (y + dy) * cw + x + dx
                if (corners[n] > b + 1) { corners[n] = (b + 1).toByte(); buckets[b + 1] += n }
            }
        }
    }
}

/** After shaping: the ground and trees laid again over the new land. */
internal fun World.landShaped() {
    ground.fill(0)
    trees.fill(0)
    generateCountry(this)
    shoreChanged()
}

/** The dry land in [area] made [kind] of ground; forest gets its trees, rock loses them. */
internal fun World.setGround(area: Area, kind: Int) {
    for (y in 0 until height) for (x in 0 until width) {
        val i = index(x, y)
        if (!area.tiles[i] || isWater(x, y)) continue
        ground[i] = kind.toByte()
        val beach = minOf(minOf(heightAt(x, y), heightAt(x + 1, y)), minOf(heightAt(x + 1, y + 1), heightAt(x, y + 1))) == 0
        trees[i] = when {
            kind != Ground.FOREST || beach -> 0
            (x * 7 + y * 13) % 5 == 0 -> Trees.pack(Trees.BIRCH, 2)
            (x + y) % 3 == 0 -> Trees.pack(Trees.PINE, 3)
            else -> Trees.pack(Trees.SPRUCE, 3)
        }.toByte()
    }
}

// Placing, in an editor world.

/**
 * A town called [name] of [size] as near ([x], [y]) as it'll go, by [Command.PlaceTown], [french] if its name is;
 * its id, or -1 if none would go.
 */
internal fun World.townNear(name: String, x: Int, y: Int, size: Int, french: Boolean = false): Int {
    for (r in 0..PLACE_REACH) for (dy in -r..r) for (dx in -r..r) {
        if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != r) continue
        val before = towns.size
        apply(Command.PlaceTown(0, x + dx, y + dy, name, size))
        if (towns.size > before) return towns.last().also { it.french = french }.id
    }
    return -1
}

/** An industry of [key] as near ([x], [y]) as it fits, any way round; its id, or -1 if it fits nowhere near. */
internal fun World.industryNear(key: String, x: Int, y: Int): Int {
    val kind = roster.industries.firstOrNull { it.key == key } ?: return -1
    for (r in 0..PLACE_REACH) for (dy in -r..r) for (dx in -r..r) {
        if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != r) continue
        for (turn in 0..3) {
            if (!industryFits(kind, x + dx, y + dy, kind.across(turn), kind.along(turn), turn)) continue
            linksDirty = true
            return buildIndustry(kind, x + dx, y + dy, turn).id
        }
    }
    return -1
}

/** How far from where it's asked for a town or industry may go, in tiles. */
private const val PLACE_REACH = 12

/**
 * Railway lands: a strip from ([x0], [y0]) to ([x1], [y1]) kept clear while towns are placed and grown round it, so
 * a station fits in the middle of town and its line runs out. Its tiles, to give back with [release].
 */
internal fun World.reserve(x0: Int, y0: Int, x1: Int, y1: Int): List<Int> {
    val out = ArrayList<Int>()
    for (y in minOf(y0, y1)..maxOf(y0, y1)) for (x in minOf(x0, x1)..maxOf(x0, x1)) {
        if (!inside(x, y)) continue
        val i = index(x, y)
        if (owner[i].toInt() != -1) continue
        // Held by the player's company in the editor, as towns don't build on anyone's land.
        owner[i] = 0
        out += i
    }
    return out
}

/** Gives back the tiles [reserve] kept clear. */
internal fun World.release(tiles: List<Int>) {
    for (i in tiles) owner[i] = -1
}

/**
 * Chapter 1, 1836: a portage line. Two waters, a long lake to the west and another to the east, joined by a river
 * that can't be run between them: a ledge of rapids in a gorge through a low ridge. A landing town at each end of
 * the rapids, timber up the east shore and a sawmill at the west landing.
 */
internal object PortageLine : Recipe() {
    const val END = 1846

    override val settings = NewGame(
        128, 128, seed = 1836_0001L, startYear = 1836, region = Region.MIXED, sea = 0, hills = 0, trees = 2,
        towns = 0, industries = 0, weather = true, dayLength = 10,
    )
    override val money = 200_000L
    override val loan = 0L

    /** Where the river runs, across the map, and the rapids between its two reaches. */
    const val RIVER_Y = 70
    const val RAPIDS_X0 = 57
    const val RAPIDS_X1 = 68

    override fun shape(w: World) {
        w.liftLand(2)
        // The ridge across the neck, then the gorge through it, broad and level for the towns and the mill.
        w.raiseLand(Area(w).line(w.wandering(60, 0, 65, w.height, 8, 5, 3), 1), 8, 2)
        w.levelLand(Area(w).rect(30, 40, 96, RIVER_Y + 6).ragged(6, 4), 2)
        // The rapids, a rock ledge a step above the water.
        val rapids = Area(w).rect(RAPIDS_X0 - 1, RIVER_Y - 2, RAPIDS_X1 + 1, RIVER_Y + 2)
        w.lowerLand(Area(w).rect(RAPIDS_X0, RIVER_Y - 1, RAPIDS_X1, RIVER_Y + 1), 1)
        val lakes = Area(w)
            .oval(4, 70, 30, 54, rough = 80, salt = 1)
            .oval(122, 66, 28, 52, rough = 80, salt = 2)
        w.carveWater(lakes, rough = 4, salt = 1)
        // Each reach of the river wanders a little between the lake and the rapids.
        val river = Area(w)
            .line(reach(w, 28, RAPIDS_X0 - 2, 3), 1)
            .line(reach(w, RAPIDS_X1 + 2, 100, 4), 1)
        w.carveWater(river, rough = 1, salt = 2, dry = rapids)
    }

    /** Points along the river from [x0] to [x1], wandering a tile or two either side of [RIVER_Y], the ends on it. */
    private fun reach(w: World, x0: Int, x1: Int, salt: Int): IntArray {
        val pts = ArrayList<Int>()
        var x = x0
        while (x < x1) {
            pts += x
            pts += RIVER_Y + if (x == x0) 0 else w.wander(x, salt, 6, 2)
            x += 4
        }
        pts += x1; pts += RIVER_Y
        return pts.toIntArray()
    }

    override fun fill(w: World): List<Goal> {
        w.setGround(Area(w).rect(RAPIDS_X0, RIVER_Y - 1, RAPIDS_X1, RIVER_Y + 1), Ground.ROCK)
        w.setGround(Area(w).oval(92, 66, 8, 6), Ground.FOREST)
        val lower = w.townNear("Port Ellery", 43, 52, TownSize.CITY)
        val upper = w.townNear("Carrick Landing", 84, 52, TownSize.CITY)
        for (t in intArrayOf(lower, upper)) repeat(2) { w.apply(Command.GrowTown(0, t, TownSize.CITY)) }
        w.industryNear("base:logging_camp", 90, 66)
        val mill = w.industryNear("base:sawmill", 40, 66)
        return listOf(
            Goal(Goal.CONNECT, 500, END, town = minOf(lower, upper), town2 = maxOf(lower, upper)),
            Goal(Goal.DELIVER, 10_000, END, cargo = Cargo.TIMBER, industry = mill, from = settings.startYear),
        )
    }
}

/**
 * Chapter 2, 1856: a trunk line. A long lake shore across the south of the map, mostly level, with four rivers
 * coming down to it and five towns along it, the biggest in the middle, and a rival running coaches.
 */
internal object TrunkLine : Recipe() {
    const val END = 1868

    override val settings = NewGame(
        256, 256, seed = 1856_0002L, startYear = 1856, region = Region.MIXED, sea = 0, hills = 0, trees = 2,
        towns = 0, industries = 0, weather = true, dayLength = 10, rivals = 1, rivalLevel = RivalLevel.EASY,
    )
    override val money = 300_000L
    override val loan = 0L
    override val rivalNames = listOf("Lakeshore Stage Company")

    val TOWN_X = intArrayOf(26, 76, 128, 180, 230)
    val RIVER_X = intArrayOf(51, 102, 154, 205)
    private val NAMES = listOf("Ashcombe", "Port Wexley", "Marlowe", "Pointe-Galarneau", "Saint-Évrard")
    private val SIZES = intArrayOf(TownSize.LARGE, TownSize.MEDIUM, TownSize.CITY, TownSize.MEDIUM, TownSize.LARGE)

    /** The shore's first row of water in each column. */
    private fun shore(w: World) = IntArray(w.width) { x -> 176 + w.wander(x, 7, 40, 10) }

    override fun shape(w: World) {
        w.liftLand(1)
        val edge = shore(w)
        val lake = Area(w).below(edge)
        val rivers = Area(w)
        for ((k, rx) in RIVER_X.withIndex()) {
            // Each river wanders down from the north edge to the shore.
            val pts = ArrayList<Int>()
            var y = 0
            while (y < edge[rx]) {
                pts += rx + w.wander(y, 20 + k, 24, 7)
                pts += y
                y += 8
            }
            pts += rx; pts += edge[rx] + 2
            rivers.line(pts.toIntArray(), 1)
        }
        // A plain rising gently from the shore, broad shallow valleys down to the rivers, and the towns on level ground.
        w.lowerLand(lake, 1, 6)
        w.lowerLand(rivers, 1, 3)
        // Room round each town to grow, the most round Marlowe.
        for (x in TOWN_X) {
            val r = if (x == 128) 16 else 10
            w.levelLand(Area(w).rect(x - r, edge[x] - 10 - r, x + r, edge[x] - 6 + r / 2).ragged(4, 61), 3)
        }
        w.carveWater(lake, rough = 5, salt = 1)
        w.carveWater(rivers, rough = 1, salt = 2)
    }

    override fun fill(w: World): List<Goal> {
        val edge = shore(w)
        w.setGround(Area(w).oval(150, 60, 14, 10), Ground.FOREST)
        // Railway lands along the shore through Marlowe and into each end town, for stations in the middle of town.
        fun row(k: Int) = edge[TOWN_X[k]] - 7
        val lands = w.reserve(TOWN_X[0] + 3, row(0), TOWN_X[0] + 16, row(0)) +
            w.reserve(TOWN_X[2] - 20, row(2), TOWN_X[2] - 3, row(2)) + w.reserve(TOWN_X[2] + 3, row(2), TOWN_X[2] + 20, row(2)) +
            w.reserve(TOWN_X[4] - 16, row(4), TOWN_X[4] - 3, row(4))
        val ids = IntArray(TOWN_X.size) { k -> w.townNear(NAMES[k], TOWN_X[k], edge[TOWN_X[k]] - 10, SIZES[k], french = k >= 3) }
        w.industryNear("base:farm", 70, edge[70] - 40)
        w.industryNear("base:flour_mill", 186, edge[186] - 26)
        w.industryNear("base:logging_camp", 150, 60)
        w.industryNear("base:sawmill", 232, edge[232] - 28)
        w.industryNear("base:port", 140, edge[140] - 1)
        val middle = ids[2]
        w.apply(Command.GrowTown(0, middle, TownSize.CITY))
        w.release(lands)
        return listOf(
            Goal(Goal.CONNECT, 1_000, END, town = minOf(ids[0], middle), town2 = maxOf(ids[0], middle)),
            Goal(Goal.CONNECT, 1_000, END, town = minOf(ids[4], middle), town2 = maxOf(ids[4], middle)),
            Goal(Goal.TOWN_POP, 700, END, town = middle),
        )
    }
}

/** Points from ([x0], [y0]) to ([x1], [y1]) every [step] tiles, the ones between moved up to [amp] tiles sideways by [salt]. */
private fun World.wandering(x0: Int, y0: Int, x1: Int, y1: Int, step: Int, amp: Int, salt: Int): IntArray {
    val n = maxOf(1, maxOf(kotlin.math.abs(x1 - x0), kotlin.math.abs(y1 - y0)) / step)
    val across = kotlin.math.abs(x1 - x0) < kotlin.math.abs(y1 - y0)
    val pts = IntArray((n + 1) * 2)
    for (k in 0..n) {
        val off = if (k == 0 || k == n) 0 else wander(k * step, salt, step * 3, amp)
        pts[k * 2] = x0 + (x1 - x0) * k / n + if (across) off else 0
        pts[k * 2 + 1] = y0 + (y1 - y0) * k / n + if (across) 0 else off
    }
    return pts
}

/**
 * Chapter 3, 1881: the last spike. Prairie to the east, then a front range, a long valley and the main range of
 * the mountains, and the sea on the west. Two passes cross both ranges, high enough for deep snow and avalanches.
 * A town at each end and a few between, and a rival railway building from the coast.
 */
internal object LastSpike : Recipe() {
    const val END = 1886

    override val settings = NewGame(
        256, 256, seed = 1881_0003L, startYear = 1881, region = Region.ROCKIES, sea = 0, hills = 2, trees = 2,
        towns = 0, industries = 0, weather = true, dayLength = 10, rivals = 1, rivalLevel = RivalLevel.NORMAL,
    )
    override val money = 400_000L
    override val loan = 0L
    override val rivalNames = listOf("Western Slope Railway")

    /** The coast's first dry column, roughly; the front range's crest; the valley between the ranges. */
    const val COAST_X = 12
    const val FRONT_X = 118
    const val VALLEY_X = 96
    /** Where the prairie starts, flat from here east. */
    const val PLAIN_X = 150
    /** The passes' rows, north and south. */
    val PASS_Y = intArrayOf(82, 178)
    /** The port's row, on the coast north of Port Haldane. */
    const val PORT_Y = 112

    override fun shape(w: World) {
        // The mountains from noise, as high as the range they're in lets them go: highest in the main range, lower
        // in the front range, fading out over the foothills to the prairie.
        val cw = w.width + 1
        for (k in w.corners.indices) {
            val x = k % cw
            val y = k / cw
            val n = (w.bumps(x, y, 30, 24) * 4 + w.bumps(x, y, 31, 11) * 3 + w.bumps(x, y, 32, 5) * 2) / 9
            // Sharp crests where the middle-sized bumps cross zero, as ridges run.
            val crest = 6 - kotlin.math.abs(w.bumps(x, y, 33, 14)) / 12
            val peaks = ((n + 128) * 30 / 256 - 6 + crest.coerceAtLeast(0)).coerceIn(0, 22)
            // The ranges' edges wander north to south.
            val rx = x + w.wander(y, 39, 28, 8)
            val range = when {
                rx < COAST_X + 4 -> 0
                rx < 34 -> (rx - COAST_X - 4) * 100 / (30 - COAST_X)
                rx < 80 -> 100
                rx < 90 -> 100 - (rx - 80) * 6
                rx < 102 -> 40
                rx < 110 -> 40 + (rx - 102) * 5
                rx < 128 -> 80
                rx < PLAIN_X -> 80 - (rx - 128) * 75 / (PLAIN_X - 128)
                else -> 5
            }
            w.corners[k] = (2 + (6 + peaks) * range / 100).coerceAtMost(World.MAX_HEIGHT).toByte()
        }
        w.settleLand()
        // The long valley between the ranges.
        val valley = w.wandering(VALLEY_X, 0, VALLEY_X, w.height, 12, 9, 35)
        w.valleyAlong(valley, IntArray(valley.size / 2) { 7 }, 2, 2)
        // The passes, climbing from the foothills over the front range, down into the valley, over the main range
        // and down to the coast, a step every four or five tiles.
        // Each turns and twists between its high points, a point every few tiles moved up to 6 tiles either way.
        for ((k, py) in PASS_Y.withIndex()) {
            val xs = intArrayOf(PLAIN_X + 4, FRONT_X + 12, FRONT_X, VALLEY_X, 76, 50, COAST_X + 8)
            val floors = intArrayOf(3, 6, 9 + k, 6, 11 + k, 8, 2)
            val pts = ArrayList<Int>()
            val bottoms = ArrayList<Int>()
            for (j in 0 until xs.size - 1) {
                val n = maxOf(1, (xs[j] - xs[j + 1]) / 6)
                for (t in 0 until n) {
                    pts += xs[j] + (xs[j + 1] - xs[j]) * t / n
                    pts += py + if (j == 0 && t == 0) 0 else w.wander(pts[pts.size - 1], 40 + k, 18, 7)
                    bottoms += floors[j] + (floors[j + 1] - floors[j]) * t / n
                }
            }
            pts += xs.last(); pts += py; bottoms += floors.last()
            w.valleyAlong(pts.toIntArray(), bottoms.toIntArray(), 2)
        }
        // The towns' and the port's level ground, the coast town's running north to the port with room for a terminus.
        val coast = Area(w).rect(COAST_X + 4, PORT_Y - 8, COAST_X + 22, 138)
        w.levelLand(coast.ragged(4, 62), 2)
        w.levelLand(Area(w).rect(222, 120, 240, 136).ragged(4, 63), 3)
        w.levelLand(Area(w).rect(VALLEY_X - 5, 124, VALLEY_X + 5, 134).ragged(4, 64), 7)
        // A river winding down across the prairie.
        val river = Area(w).line(w.wandering(196, 0, 204, w.height, 12, 14, 36), 1)
        w.lowerLand(river, 1, 3)
        val sea = Area(w)
        for (y in 0 until w.height) sea.rect(0, y, COAST_X + w.wander(y, 37, 24, 5), y)
        w.carveWater(sea, rough = 4, salt = 3, dry = coast)
        w.carveWater(river, rough = 1, salt = 4)
    }

    override fun fill(w: World): List<Goal> {
        w.setGround(Area(w).rect(PLAIN_X, 0, w.width - 1, w.height - 1), Ground.PRAIRIE)
        w.setGround(Area(w).oval(40, 150, 14, 20, rough = 60, salt = 5), Ground.FOREST)
        // The rival's coast town is the biggest, so it starts there.
        val west = w.townNear("Port Haldane", COAST_X + 14, 129, TownSize.LARGE)
        w.townNear("Larchmere", VALLEY_X, 129, TownSize.SMALL)
        w.townNear("Medley Crossing", 160, 150, TownSize.SMALL)
        w.townNear("Wolf Creek", 188, 112, TownSize.SMALL)
        val east = w.townNear("Fort Calder", 231, 128, TownSize.MEDIUM)
        // A big seam, twice a usual mine's coal.
        w.industries[w.industryNear("base:coal_mine", VALLEY_X + 2, 150)].level = 32
        val port = w.industryNear("base:port", COAST_X + 2, PORT_Y)
        w.industryNear("base:logging_camp", 40, 150)
        w.industryNear("base:sawmill", COAST_X + 16, 146)
        w.industryNear("base:farm", 214, 160)
        w.industryNear("base:grain_elevator", 236, 110)
        return listOf(
            Goal(Goal.CONNECT, 1_000, END, town = minOf(west, east), town2 = maxOf(west, east)),
            Goal(Goal.DELIVER, 8_000, END, cargo = Cargo.COAL, industry = port, from = settings.startYear),
        )
    }
}

/**
 * Chapter 4, 1954: the Seaway. A long river from a great lake in the west down to the sea in the east, cut in the
 * middle by a rock ledge of rapids. A canal past it, with two locks up and two down, opens the river to ships. An
 * iron mine and farms by the lake, a steel mill and a port downstream.
 */
internal object Seaway : Recipe() {
    const val END = 1964

    override val settings = NewGame(
        256, 256, seed = 1954_0004L, startYear = 1954, region = Region.MIXED, sea = 0, hills = 1, trees = 2,
        towns = 0, industries = 0, weather = true, dayLength = 10, rivals = 1, rivalLevel = RivalLevel.EASY,
    )
    override val money = 400_000L
    override val loan = 0L
    override val rivalNames = listOf("Laurentian Carriers")

    /** The river's row at the rapids, and the rock ledge across it. */
    const val RIVER_Y = 128
    const val LEDGE_X0 = 121
    const val LEDGE_X1 = 134
    /** The port's corner, on the south bank of the river near its mouth. */
    const val PORT_X = 206
    const val PORT_Y = 146

    override fun shape(w: World) {
        w.liftLand(2)
        // Level ground round the rapids, two steps up, for the ledge and the canal past it.
        w.levelLand(Area(w).rect(LEDGE_X0 - 10, RIVER_Y - 14, LEDGE_X1 + 10, RIVER_Y + 14), 2)
        val lake = Area(w).oval(34, 112, 44, 62, rough = 40, salt = 1)
        val upper = Area(w).line(w.wandering(70, 118, LEDGE_X0 - 12, RIVER_Y, 10, 4, 41), 5)
        val lower = Area(w).line(w.wandering(LEDGE_X1 + 12, RIVER_Y, 236, 140, 10, 5, 42), 6)
        val sea = Area(w).oval(262, 150, 30, 56, rough = 40, salt = 2)
        // The neck at the rapids, narrow and square to the ledge, so the canal's locks sit on straight slopes.
        val neck = Area(w).rect(LEDGE_X0 - 12, RIVER_Y - 3, LEDGE_X0 - 1, RIVER_Y + 3).rect(LEDGE_X1 + 1, RIVER_Y - 3, LEDGE_X1 + 12, RIVER_Y + 3)
        val keep = Area(w).rect(LEDGE_X0 - 12, RIVER_Y - 14, LEDGE_X1 + 12, RIVER_Y + 14)
        // The port's level ground on the south bank near the mouth, with room for a terminus.
        val port = Area(w).rect(PORT_X - 6, PORT_Y, PORT_X + 14, PORT_Y + 10)
        w.levelLand(port, 2)
        keep.plus(port)
        w.carveWater(lake.plus(upper).plus(lower).plus(sea).minus(keep), rough = 4, salt = 5, dry = keep)
        w.carveWater(neck, rough = 0)
    }

    override fun fill(w: World): List<Goal> {
        w.setGround(Area(w).rect(LEDGE_X0, RIVER_Y - 4, LEDGE_X1, RIVER_Y + 4), Ground.ROCK)
        w.setGround(Area(w).oval(50, 196, 30, 16), Ground.MEADOW)
        w.townNear("Port Carlow", 70, 98, TownSize.MEDIUM)
        w.townNear("Elmhurst", 86, 168, TownSize.SMALL)
        w.townNear("Morrisdale", 110, 112, TownSize.SMALL)
        w.townNear("Sainte-Clémence", 150, 146, TownSize.SMALL, french = true)
        w.townNear("Pointe-Aubry", 196, 116, TownSize.LARGE, french = true)
        w.townNear("Havre-Lemieux", 226, 168, TownSize.MEDIUM, french = true)
        // A big mine and big farms, as much as they can make.
        w.industries[w.industryNear("base:iron_mine", 46, 30)].level = MAX_LEVEL
        w.industries[w.industryNear("base:farm", 40, 192)].level = 32
        w.industries[w.industryNear("base:farm", 66, 200)].level = 32
        val mill = w.industryNear("base:steel_mill", 176, 110)
        w.industryNear("base:coal_mine", 210, 80)
        val port = w.industryNear("base:port", PORT_X, PORT_Y - 2)
        return listOf(
            Goal(Goal.DELIVER, 15_000, END, cargo = Cargo.IRON_ORE, industry = mill, from = settings.startYear),
            Goal(Goal.DELIVER, 12_000, END, cargo = Cargo.GRAIN, industry = port, from = settings.startYear),
        )
    }
}

/**
 * Chapter 5, 1955: iron ore to the coast. A big iron mine far inland, rough rock and lakes everywhere between,
 * and a harbour town on the coast, the steel mill beside it.
 */
internal object IronOre : Recipe() {
    const val END = 1965

    override val settings = NewGame(
        256, 256, seed = 1955_0005L, startYear = 1955, region = Region.SHIELD, sea = 0, hills = 2, trees = 2,
        towns = 0, industries = 0, weather = true, dayLength = 10,
    )
    override val money = 500_000L
    override val loan = 0L

    const val MINE_X = 40
    const val MINE_Y = 44
    const val HARBOUR_X = 214
    const val HARBOUR_Y = 204
    /** A fishing village up the shore from the harbour. */
    const val VILLAGE_X = 202
    const val VILLAGE_Y = 170

    override fun shape(w: World) {
        // Low, worn country: the hills at half their height, a step above the lakes.
        for (k in w.corners.indices) w.corners[k] = (1 + w.corners[k] / 2).toByte()
        w.settleLand()
        // The mine's plateau, the harbour's flat with room for the town to grow, and the village up the shore.
        w.levelLand(Area(w).rect(MINE_X - 10, MINE_Y - 8, MINE_X + 18, MINE_Y + 14).ragged(4, 65), 5)
        w.levelLand(Area(w).rect(HARBOUR_X - 18, HARBOUR_Y - 10, HARBOUR_X + 6, HARBOUR_Y + 10).ragged(4, 66), 2)
        w.levelLand(Area(w).rect(VILLAGE_X - 8, VILLAGE_Y - 6, VILLAGE_X + 6, VILLAGE_Y + 6).ragged(3, 78), 2)
        // Lakes strewn across the country between, kept off the mine and the harbour.
        val lakes = Area(w)
        for (k in 0 until 18) {
            val x = 30 + w.shapeDice(k, 1, 51) * 180 / 256
            val y = 30 + w.shapeDice(k, 2, 51) * 190 / 256
            if (chebyshev(x, y, MINE_X, MINE_Y) < 26 || chebyshev(x, y, HARBOUR_X, HARBOUR_Y) < 30 || chebyshev(x, y, VILLAGE_X, VILLAGE_Y) < 20) continue
            lakes.oval(x, y, 4 + w.shapeDice(k, 3, 51) % 10, 3 + w.shapeDice(k, 4, 51) % 8, rough = 70, salt = 52 + k)
        }
        // The sea along the east and into the south-east corner.
        val sea = Area(w)
        for (y in 0 until w.height) sea.rect(232 + w.wander(y, 53, 30, 8) - maxOf(0, y - 150) / 3, y, w.width - 1, y)
        val shore = Area(w).rect(HARBOUR_X - 18, HARBOUR_Y - 10, HARBOUR_X + 4, HARBOUR_Y + 10).rect(VILLAGE_X - 8, VILLAGE_Y - 6, VILLAGE_X + 6, VILLAGE_Y + 6)
        w.carveWater(lakes, rough = 3, salt = 6, dry = shore)
        w.carveWater(sea, rough = 5, salt = 7, dry = shore)
    }

    override fun fill(w: World): List<Goal> {
        // Bare rock in bands across the country.
        val rock = Area(w)
        for (y in 0 until w.height) for (x in 0 until w.width) if (w.roughness(x, y, 54, 14) > 40) rock.rect(x, y, x, y)
        w.setGround(rock.minus(Area(w).rect(HARBOUR_X - 20, HARBOUR_Y - 12, HARBOUR_X + 8, HARBOUR_Y + 12)).minus(Area(w).rect(VILLAGE_X - 10, VILLAGE_Y - 8, VILLAGE_X + 8, VILLAGE_Y + 8)).minus(Area(w).rect(MINE_X - 12, MINE_Y - 10, MINE_X + 20, MINE_Y + 16)), Ground.ROCK)
        w.townNear("Carrow Lake", MINE_X + 10, MINE_Y + 8, TownSize.SMALL)
        w.townNear("Esker Lake", 128, 122, TownSize.SMALL)
        val harbour = w.townNear("Baie-Clairval", HARBOUR_X - 4, HARBOUR_Y, TownSize.MEDIUM, french = true)
        w.townNear("Anse-aux-Pins", VILLAGE_X, VILLAGE_Y, TownSize.SMALL, french = true)
        w.industries[w.industryNear("base:iron_mine", MINE_X - 2, MINE_Y - 2)].level = MAX_LEVEL
        val mill = w.industryNear("base:steel_mill", HARBOUR_X - 6, HARBOUR_Y - 16)
        w.industryNear("base:port", HARBOUR_X + 8, HARBOUR_Y + 10)
        w.industryNear("base:logging_camp", 150, 60)
        return listOf(
            Goal(Goal.DELIVER, 15_000, END, cargo = Cargo.IRON_ORE, industry = mill, from = settings.startYear),
            Goal(Goal.TOWN_POP, 650, END, town = harbour),
        )
    }
}

/**
 * Chapter 6, 2032: a high-speed corridor. Two cities far apart along a lake shore, towns between, and a highway
 * joining them that a coach company runs on.
 */
internal object HighSpeed : Recipe() {
    const val END = 2042

    override val settings = NewGame(
        512, 512, seed = 2032_0006L, startYear = 2032, region = Region.MIXED, sea = 0, hills = 1, trees = 2,
        towns = 0, industries = 0, weather = true, dayLength = 10, rivals = 1, rivalLevel = RivalLevel.NORMAL,
    )
    override val money = 1_500_000L
    override val loan = 0L
    override val rivalNames = listOf("Corridor Coachways")

    val TOWN_X = intArrayOf(100, 170, 230, 290, 350, 420)
    private val NAMES = listOf("Westbridge", "Ashford Mills", "Kenmore", "Port Avalon", "Rivière-aux-Saules", "Montrevel")
    private val SIZES = intArrayOf(TownSize.CITY, TownSize.MEDIUM, TownSize.SMALL, TownSize.MEDIUM, TownSize.SMALL, TownSize.CITY)
    /** The highway's row, north of the towns. */
    const val HIGHWAY_Y = 262

    /** The shore's first row of water in each column. */
    private fun shore(w: World) = IntArray(w.width) { x -> 330 + w.wander(x, 61, 60, 16) }

    override fun shape(w: World) {
        w.liftLand(2)
        val edge = shore(w)
        w.lowerLand(Area(w).below(edge), 1, 8)
        for ((k, x) in TOWN_X.withIndex()) {
            val r = if (SIZES[k] == TownSize.CITY) 18 else 8
            w.levelLand(Area(w).rect(x - r, edge[x] - 22 - r, x + r, edge[x] - 10).ragged(4, 67), 3)
        }
        w.levelLand(Area(w).rect(TOWN_X.first(), HIGHWAY_Y, TOWN_X.last(), HIGHWAY_Y), 3)
        for (x in intArrayOf(TOWN_X.first(), TOWN_X.last())) w.levelLand(Area(w).rect(x, HIGHWAY_Y, x, edge[x] - 16), 3)
        w.carveWater(Area(w).below(edge), rough = 6, salt = 8)
    }

    override fun fill(w: World): List<Goal> {
        val edge = shore(w)
        fun townY(k: Int) = edge[TOWN_X[k]] - 16 - if (SIZES[k] == TownSize.CITY) 6 else 0
        // Railway lands into the middle of each city from the side facing the other, for a station downtown.
        val west = TOWN_X.first()
        val east = TOWN_X.last()
        val lands = w.reserve(west + 3, townY(0) + 3, west + 26, townY(0) + 3) +
            w.reserve(east - 26, townY(TOWN_X.size - 1) + 3, east - 3, townY(TOWN_X.size - 1) + 3)
        val ids = IntArray(TOWN_X.size) { k -> w.townNear(NAMES[k], TOWN_X[k], townY(k), SIZES[k], french = k >= 4) }
        // The cities each grown again, to be cities of the day.
        for (k in intArrayOf(0, TOWN_X.size - 1)) repeat(3) { w.apply(Command.GrowTown(0, ids[k], TownSize.CITY)) }
        w.release(lands)
        w.industryNear("base:farm", 200, 180)
        w.industryNear("base:flour_mill", 260, 200)
        w.industryNear("base:factory", 120, 220)
        w.industryNear("base:oil_wells", 380, 170)
        w.industryNear("base:refinery", 400, 220)
        // The highway: up from each city and along the row between.
        w.apply(Command.Road(0, west, edge[west] - 16, west, HIGHWAY_Y, Surface.HIGHWAY, false))
        w.apply(Command.Road(0, west, HIGHWAY_Y, east, HIGHWAY_Y, Surface.HIGHWAY, false))
        w.apply(Command.Road(0, east, HIGHWAY_Y, east, edge[east] - 16, Surface.HIGHWAY, false))
        val a = ids.first()
        val b = ids.last()
        return listOf(
            // More than a coach line on the highway carries, for a fast line between the downtowns.
            Goal(Goal.CONNECT, 11_000, END, town = minOf(a, b), town2 = maxOf(a, b)),
            // Value leaves out the chapter's $1,500,000 start money.
            Goal(Goal.COMPANY_VALUE, 100_000, END),
        )
    }

    /** The highway out of town is the coach company's. */
    override fun started(w: World) {
        val rival = w.rivals.firstOrNull()?.company ?: return
        for (i in 0 until w.width * w.height) if (w.roadOwner[i].toInt() == 0 && w.townOf[i].toInt() < 0) w.roadOwner[i] = rival.toByte()
    }
}

/**
 * Chapter 7, 2062: a warming north. Two towns on lakes in the tundra, reached by ice roads that close earlier each
 * spring, and a railhead town to the south with a refinery and food. Towns only grow when they're supplied.
 */
internal object WarmingNorth : Recipe() {
    const val END = 2072

    override val settings = NewGame(
        256, 256, seed = 2062_0007L, startYear = 2062, region = Region.NORTH, sea = 0, hills = 1, trees = 2,
        towns = 0, industries = 0, weather = true, dayLength = 10, townGrowth = TownGrowth.SUPPLIED,
    )
    override val money = 1_500_000L
    override val loan = 0L

    override fun shape(w: World) {
        // Low, flat tundra, the hills at a third of their height and a step or two above the lakes.
        for (k in w.corners.indices) w.corners[k] = (1 + w.corners[k] / 3).toByte()
        w.settleLand()
        w.levelLand(Area(w).rect(116, 214, 142, 236).ragged(4, 68), 3)
        // The northern towns with room to grow, and a village near each.
        w.levelLand(Area(w).rect(52, 44, 84, 66).ragged(4, 69), 2)
        w.levelLand(Area(w).rect(172, 32, 204, 54).ragged(4, 70), 2)
        w.levelLand(Area(w).rect(26, 30, 40, 42).ragged(3, 79), 2)
        w.levelLand(Area(w).rect(218, 18, 232, 30).ragged(3, 80), 2)
        val lakes = Area(w)
            .oval(128, 132, 70, 22, rough = 60, salt = 71)
            .oval(60, 80, 26, 12, rough = 60, salt = 72)
            .oval(196, 66, 22, 10, rough = 60, salt = 73)
            .oval(40, 170, 16, 12, rough = 60, salt = 74)
            .oval(210, 180, 14, 18, rough = 60, salt = 75)
        val river = Area(w).line(w.wandering(128, 154, 150, 255, 10, 6, 76), 1)
        w.carveWater(lakes, rough = 4, salt = 9)
        w.carveWater(river, rough = 1, salt = 10)
    }

    override fun fill(w: World): List<Goal> {
        w.setGround(Area(w).oval(100, 216, 30, 18, rough = 60, salt = 77), Ground.MEADOW)
        w.setGround(Area(w).rect(0, 0, w.width - 1, 110).minus(Area(w).oval(196, 66, 30, 18)), Ground.TUNDRA)
        // Railway lands between the two northern towns, for stations in the middle of each.
        val lands = w.reserve(71, 61, 86, 61) + w.reserve(170, 49, 185, 49)
        val north = w.townNear("Tamarack Point", 68, 58, TownSize.LARGE)
        val northEast = w.townNear("Fort Delorme", 188, 46, TownSize.LARGE, french = true)
        w.release(lands)
        w.townNear("Spruce Narrows", 33, 36, TownSize.SMALL)
        w.townNear("Lac-Brûlé", 225, 24, TownSize.SMALL, french = true)
        w.townNear("Fort Ashdown", 128, 226, TownSize.LARGE)
        w.industryNear("base:oil_wells", 180, 230)
        w.industryNear("base:refinery", 150, 214)
        w.industryNear("base:farm", 96, 214)
        w.industryNear("base:flour_mill", 112, 238)
        w.industryNear("base:fishing_harbour", 128, 112)
        w.industryNear("base:cannery", 150, 100)
        return listOf(
            Goal(Goal.TOWN_POP, 550, END, town = north),
            Goal(Goal.TOWN_POP, 550, END, town = northEast),
        )
    }
}
