package com.rm.interchange.sim

/*
 * Towns, and how they grow.
 *
 * A town grows on a street grid from its middle, as most Canadian towns were
 * laid out: roads run along every [Town.grid]th row and column, and buildings
 * fill the lots between, each facing a road. Which building goes up depends on
 * the year, how far in from the edge of town the lot is (its zone) and how big
 * the town has got. Buildings past their era are pulled down and built again.
 *
 * Roads are kept per tile as bits, one for each edge the road reaches
 * ([roadBit]), and [PAVED] once a town has paved it: new roads from
 * [PAVING_FROM] and old ones bit by bit after. Buildings are kept per tile as their place in the roster plus
 * one, and the way they face ([packHouse]).
 */

class Town(
    val id: Int,
    val name: String,
    /** Its middle, where its first roads cross. */
    val x: Int,
    val y: Int,
    /** Tiles from one street to the next. */
    val grid: Int,
) {
    var population = 0
    var houses = 0
    /** Days until it next grows. */
    var growIn = 0
}

/** The bit for a road reaching [edge], a [Track] edge. */
fun roadBit(edge: Int) = 1 shl edge

/** Set on a road tile once it's paved. */
const val PAVED = 16
/** The year towns start paving their roads. */
const val PAVING_FROM = 1910

/** A building's tile value: [house], its place in the roster, and the edge it faces. 0 is none. */
fun packHouse(house: Int, facing: Int) = (house + 1) or (facing shl 12)
fun houseOf(value: Int) = (value and 0xfff) - 1
fun houseFacing(value: Int) = (value shr 12) and 3

fun World.roadAt(x: Int, y: Int): Int = road[index(x, y)].toInt()
fun World.houseAt(x: Int, y: Int): Int = houseOf(house[index(x, y)].toInt())

/** Something of a town's on tile [i]: a road or a building. */
fun World.townBuilt(i: Int) = road[i].toInt() != 0 || house[i].toInt() != 0

private fun stepX(edge: Int) = when (edge) { Track.XM -> -1; Track.XP -> 1; else -> 0 }
private fun stepY(edge: Int) = when (edge) { Track.YM -> -1; Track.YP -> 1; else -> 0 }

/**
 * Whether road [bits] can lie on tile ([x], [y]): any on the level, and on a
 * slope only along it, since a road can't turn on a hillside.
 */
fun World.roadFits(x: Int, y: Int, bits: Int): Boolean {
    val n = heightAt(x, y)
    val e = heightAt(x + 1, y)
    val s = heightAt(x + 1, y + 1)
    val w = heightAt(x, y + 1)
    if (n == e && e == s && s == w) return true
    val alongX = n == w && e == s
    val alongY = n == e && w == s
    return when {
        alongX -> bits and (roadBit(Track.YM) or roadBit(Track.YP)) == 0
        alongY -> bits and (roadBit(Track.XM) or roadBit(Track.XP)) == 0
        else -> false
    }
}

/** Two steps between a tile's lowest and highest corners. */
fun World.steep(x: Int, y: Int): Boolean {
    val a = heightAt(x, y)
    val b = heightAt(x + 1, y)
    val c = heightAt(x + 1, y + 1)
    val d = heightAt(x, y + 1)
    return maxOf(maxOf(a, b), maxOf(c, d)) - minOf(minOf(a, b), minOf(c, d)) > 1
}

/** How far into town ([Zones] bit) tile ([x], [y]) is, by its distance from the middle against the town's size. */
fun Town.zoneAt(x: Int, y: Int): Int {
    val d2 = (x - this.x) * (x - this.x) + (y - this.y) * (y - this.y)
    val s = maxOf(houses, 6)
    return when {
        d2 * 100 <= s * 12 -> Zones.CENTRE
        d2 * 100 <= s * 35 -> Zones.INNER
        d2 * 100 <= s * 80 -> Zones.SUBURBS
        else -> Zones.OUTSKIRTS
    }
}

/** How many towns a new map gets for its size, by the setting from none to many. */
private fun townCount(g: NewGame): Int {
    val area = g.width.toLong() * g.height
    return when (g.towns) {
        0 -> 0
        1 -> (area / 6000).toInt()
        2 -> (area / 2500).toInt()
        else -> (area / 1200).toInt()
    }.coerceAtLeast(if (g.towns == 0) 0 else 1)
}

/** Places a new map's towns and grows each to its starting size. */
internal fun generateTowns(world: World) = with(world) {
    val want = townCount(settings)
    val year = settings.startYear
    val grid = if (settings.region == Region.PRAIRIES) 4 else 3
    val margin = 8
    if (width <= margin * 2 || height <= margin * 2) return
    // Towns already placed, by 16 tile square, for keeping new ones apart.
    val near = HashMap<Int, MutableList<Town>>()
    val taken = HashSet<String>()
    var tries = want * 30
    while (towns.size < want && tries-- > 0) {
        val x = margin + rng.below(width - margin * 2)
        val y = margin + rng.below(height - margin * 2)
        if (isWater(x, y) || !isFlat(x, y) || track[index(x, y)].toInt() != 0) continue
        var close = false
        for (by in (y - TOWN_SPACING) / 16..(y + TOWN_SPACING) / 16) for (bx in (x - TOWN_SPACING) / 16..(x + TOWN_SPACING) / 16) {
            for (o in near[by * 4096 + bx].orEmpty()) if (maxOf(kotlin.math.abs(o.x - x), kotlin.math.abs(o.y - y)) < TOWN_SPACING) close = true
        }
        if (close) continue
        val name = TownNames.make(rng, settings.region, taken)
        taken += name
        val t = Town(towns.size, name, x, y, grid)
        towns += t
        near.getOrPut((y / 16) * 4096 + x / 16) { ArrayList() } += t
        // Its first crossing: a road out each way that fits.
        for (edge in 0 until 4) extendRoad(t, x, y, edge, year)
        // Most are villages, some towns, a few cities; smaller the earlier the start.
        val r = rng.below(100)
        var target = when {
            r < 65 -> 6 + rng.below(16)
            r < 93 -> 22 + rng.below(60)
            else -> 80 + rng.below(160)
        }
        if (year < 1880) target = target * 2 / 3
        var attempts = target * 8 + 40
        while (t.houses < target && attempts-- > 0) growTown(t, year)
        t.growIn = 1 + rng.below(30)
    }
    townsChanged = true
}

/** One in this many chances to rebuild something still in its era bigger. */
private const val UPGRADE_ODDS = 10

/** Towns at least this many tiles apart, each way. */
private const val TOWN_SPACING = 14

/** Each town counts down to its next growth, once a day. */
internal fun World.townsDay() {
    val year = Calendar.year(startYear, day)
    for (t in towns) {
        if (--t.growIn > 0) continue
        growTown(t, year)
        t.growIn = (40 - t.houses / 8).coerceIn(10, 40) + rng.below(10)
    }
}

/**
 * One go at growing [t]: a walk out from its middle along its roads, building
 * at the first spot beside the way that will take something. True if it built.
 */
internal fun World.growTown(t: Town, year: Int): Boolean {
    var x = t.x
    var y = t.y
    val steps = 2 + rng.below(minOf(80, 6 + t.houses))
    repeat(steps) {
        val edge = rng.below(4)
        val nx = x + stepX(edge)
        val ny = y + stepY(edge)
        if (!inside(nx, ny)) return@repeat
        val i = index(x, y)
        if (road[i].toInt() and roadBit(edge) != 0) {
            // Paving the old dirt roads as it goes, once it's the time for it.
            if (year >= PAVING_FROM && road[i].toInt() and PAVED == 0 && rng.below(4) == 0) {
                road[i] = (road[i].toInt() or PAVED).toByte()
                touched(x, y)
            }
            x = nx
            y = ny
            return@repeat
        }
        if (buildBeside(t, x, y, edge, year)) return true
    }
    return false
}

/** Builds what fits on the tile across [edge] from road tile ([x], [y]): more road on a street line, else a building. */
private fun World.buildBeside(t: Town, x: Int, y: Int, edge: Int, year: Int): Boolean {
    val nx = x + stepX(edge)
    val ny = y + stepY(edge)
    val ni = index(nx, ny)
    val ownTown = townOf[ni].toInt()
    if (house[ni].toInt() != 0) return ownTown == t.id && rng.below(5) == 0 && renew(t, nx, ny, year)
    if (road[ni].toInt() != 0) return ownTown == t.id && onStreet(t, nx, ny, edge) && joinRoads(x, y, edge)
    // A street only goes on once the stretch before it has buildings along it, so towns don't sprout bare stubs.
    if (onStreet(t, nx, ny, edge)) return (builtAlong(x, y, edge) || rng.below(6) == 0) && extendRoad(t, x, y, edge, year)
    if (!onLot(t, nx, ny)) return false
    return buildHouse(t, nx, ny, Track.opposite(edge), year)
}

/** Whether a building stands on either side of road tile ([x], [y]), across the way a road going over [edge] runs. */
private fun World.builtAlong(x: Int, y: Int, edge: Int): Boolean {
    val alongX = edge == Track.XM || edge == Track.XP
    for (s in intArrayOf(-1, 1)) {
        val sx = if (alongX) x else x + s
        val sy = if (alongX) y + s else y
        if (inside(sx, sy) && house[index(sx, sy)].toInt() != 0) return true
    }
    return false
}

/** Whether a road going across [edge] onto ([x], [y]) keeps to the town's street lines. */
private fun onStreet(t: Town, x: Int, y: Int, edge: Int): Boolean =
    if (edge == Track.XM || edge == Track.XP) (y - t.y).mod(t.grid) == 0 else (x - t.x).mod(t.grid) == 0

/** Whether ([x], [y]) is a lot, off every street line. */
private fun onLot(t: Town, x: Int, y: Int) = (x - t.x).mod(t.grid) != 0 && (y - t.y).mod(t.grid) != 0

/** Free for a town to build on: dry, unowned, with nothing on it. */
private fun World.freeLand(x: Int, y: Int): Boolean {
    val i = index(x, y)
    return !isWater(x, y) && owner[i].toInt() == -1 && track[i].toInt() == 0 && road[i].toInt() == 0 && house[i].toInt() == 0
}

/** A road from ([x], [y]) across [edge] onto the free tile there, if both ends take it. */
private fun World.extendRoad(t: Town, x: Int, y: Int, edge: Int, year: Int): Boolean {
    val nx = x + stepX(edge)
    val ny = y + stepY(edge)
    if (!inside(nx, ny) || !freeLand(nx, ny)) return false
    val i = index(x, y)
    val ni = index(nx, ny)
    val paved = if (year >= PAVING_FROM) PAVED else 0
    val here = road[i].toInt() or roadBit(edge) or paved
    val there = roadBit(Track.opposite(edge)) or paved
    if (!roadFits(x, y, here) || !roadFits(nx, ny, there)) return false
    road[i] = here.toByte()
    road[ni] = there.toByte()
    townOf[i] = t.id.toShort()
    townOf[ni] = t.id.toShort()
    clearTrees(i)
    clearTrees(ni)
    touched(x, y)
    touched(nx, ny)
    townsChanged = true
    return true
}

/** Joins two roads that meet across [edge] without being joined, closing a block. */
private fun World.joinRoads(x: Int, y: Int, edge: Int): Boolean {
    val nx = x + stepX(edge)
    val ny = y + stepY(edge)
    val i = index(x, y)
    val ni = index(nx, ny)
    val here = road[i].toInt() or roadBit(edge)
    val there = road[ni].toInt() or roadBit(Track.opposite(edge))
    if (!roadFits(x, y, here) || !roadFits(nx, ny, there)) return false
    road[i] = here.toByte()
    road[ni] = there.toByte()
    touched(x, y)
    touched(nx, ny)
    return true
}

/** The kind of building that suits ([x], [y]) in [t] in [year], by weight, or -1 if none does. */
private fun World.pickHouse(t: Town, x: Int, y: Int, year: Int): Int {
    val zone = t.zoneAt(x, y)
    var total = 0
    for (h in roster.houses) if (fits(h, t, zone, year)) total += h.weight
    if (total == 0) return -1
    var roll = rng.below(total)
    for (h in roster.houses) {
        if (!fits(h, t, zone, year)) continue
        roll -= h.weight
        if (roll < 0) return h.id
    }
    return -1
}

private fun fits(h: House, t: Town, zone: Int, year: Int) = year >= h.from && year < h.until && h.zones and zone != 0 && t.population >= h.minPopulation

/**
 * A building on the free lot ([x], [y]), facing [facing]. On a slope it
 * stands on a foundation, level with its highest corner; a lot two steps from
 * corner to corner is too steep.
 */
private fun World.buildHouse(t: Town, x: Int, y: Int, facing: Int, year: Int): Boolean {
    if (!freeLand(x, y) || steep(x, y)) return false
    val h = pickHouse(t, x, y, year)
    if (h < 0) return false
    val i = index(x, y)
    house[i] = packHouse(h, facing).toShort()
    houseYear[i] = year.toShort()
    townOf[i] = t.id.toShort()
    clearTrees(i)
    t.houses++
    t.population += roster.houses[h].residents
    touched(x, y)
    townsChanged = true
    return true
}

/**
 * Pulls down [t]'s building at ([x], [y]) and puts up another if its era is
 * past, or now and then if one holding more people suits the lot, so a town
 * centre fills in slowly and keeps a mix of old and new.
 */
private fun World.renew(t: Town, x: Int, y: Int, year: Int): Boolean {
    val i = index(x, y)
    val old = roster.houses.getOrNull(houseOf(house[i].toInt())) ?: return false
    val h = pickHouse(t, x, y, year)
    if (h < 0 || h == old.id) return false
    val new = roster.houses[h]
    if (year < old.until && (new.residents + new.jobs <= old.residents + old.jobs || rng.below(UPGRADE_ODDS) != 0)) return false
    house[i] = packHouse(h, houseFacing(house[i].toInt())).toShort()
    houseYear[i] = year.toShort()
    t.population += new.residents - old.residents
    touched(x, y)
    townsChanged = true
    return true
}
