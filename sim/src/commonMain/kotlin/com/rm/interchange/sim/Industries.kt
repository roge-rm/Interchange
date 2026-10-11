package com.rm.interchange.sim

/*
 * Industries: where freight comes from and goes to.
 *
 * An industry stands on a rectangle of tiles, each a piece of its layout,
 * turned a quarter at a time as it was placed ([Industry.turn]). One that makes
 * cargo of its own (a mine, a farm) makes a month's worth over the month; one
 * that takes cargo (a mill) makes its own from what's brought to it. What it
 * makes goes to the stations whose catchment reaches it and where a train has
 * called that could carry it, by their ratings, two at most.
 */

class Industry(
    val id: Int,
    /** Its place in the roster's industries. */
    val kind: Int,
    /** Its top corner, and which way its layout is turned, 0 to 3. */
    val x: Int,
    val y: Int,
    val turn: Int,
    /** The town it's named after, or -1. */
    val town: Int,
) {
    /** What it makes, as sixteenths of its kind's monthly amount. */
    var level = 16
    /** By cargo: made this month and last, and how much of it went to stations. */
    val made = IntArray(Cargo.COUNT)
    val madeLastMonth = IntArray(Cargo.COUNT)
    val moved = IntArray(Cargo.COUNT)
    val movedLastMonth = IntArray(Cargo.COUNT)
    /** Cargo brought to it, waiting to be made into its own. */
    var brought = 0
    /** What's been brought this month and last, all cargo together. */
    var broughtThisMonth = 0
    var broughtLastMonth = 0
    /** Shut down: its tiles are cleared and it makes and takes nothing. Kept so ids stay put. */
    var closed = false
}

/** Something that happened in the world worth telling the player, by kind, with the industry or town it's about. */
class News(val kind: Int, val about: Int, val tick: Long) {
    companion object {
        const val INDUSTRY_OPENS = 0
        const val INDUSTRY_CLOSES = 1
        const val PRODUCTION_UP = 2
        const val PRODUCTION_DOWN = 3
    }
}

/** An industry's size on the map, its layout turned by [turn]. */
fun IndustryKind.across(turn: Int) = if (turn % 2 == 0) width else height
fun IndustryKind.along(turn: Int) = if (turn % 2 == 0) height else width

/** The part of [kind]'s layout at ([fx], [fy]) from its top corner, turned by [turn]. */
fun IndustryKind.part(fx: Int, fy: Int, turn: Int): String = when (turn) {
    0 -> layout[fy][fx]
    1 -> layout[height - 1 - fx][fy]
    2 -> layout[height - 1 - fy][width - 1 - fx]
    else -> layout[fx][width - 1 - fy]
}

/** The industry standing on tile [i], or null. */
fun World.industryAt(i: Int): Industry? = industries.getOrNull(industryOf[i].toInt() - 1)

/** How many industries a new map gets, by the setting from none to many. */
private fun industryCount(g: NewGame): Int {
    val area = g.width.toLong() * g.height
    return when (g.industries) {
        0 -> 0
        1 -> (area / 4000).toInt()
        2 -> (area / 1800).toInt()
        else -> (area / 900).toInt()
    }.coerceAtLeast(if (g.industries == 0) 0 else 3)
}

/** Places a new map's industries: every kind of the start year once if it'll go, then more by region. */
internal fun generateIndustries(world: World) = with(world) {
    val year = settings.startYear
    val kinds = roster.industries.filter { year >= it.from && year < it.until }
    if (kinds.isEmpty()) return
    val want = industryCount(settings)
    // One of each first, so every chain can be made, then the rest by how common each is here.
    for (k in kinds) if (industries.size < want) placeIndustry(k)
    val weights = kinds.map { it.weight(settings.region) }
    val total = weights.sum()
    if (total == 0) return
    var tries = want * 4
    while (industries.size < want && tries-- > 0) {
        var roll = rng.below(total)
        var i = 0
        while (roll >= weights[i]) roll -= weights[i++]
        placeIndustry(kinds[i])
    }
}

/** Puts an industry of [kind] somewhere it fits, trying a few spots. True if it found one. */
internal fun World.placeIndustry(kind: IndustryKind): Boolean {
    repeat(PLACE_TRIES) {
        val turn = rng.below(4)
        val w = kind.across(turn)
        val h = kind.along(turn)
        val x = 2 + rng.below(maxOf(1, width - w - 4))
        val y = 2 + rng.below(maxOf(1, height - h - 4))
        if (industryFits(kind, x, y, w, h)) {
            val town = towns.minByOrNull { maxOf(kotlin.math.abs(it.x - x), kotlin.math.abs(it.y - y)) }?.id ?: -1
            val ind = Industry(industries.size, kind.id, x, y, turn, town)
            industries += ind
            for (ty in y until y + h) for (tx in x until x + w) {
                val i = index(tx, ty)
                industryOf[i] = (ind.id + 1).toShort()
                clearTrees(i)
                touched(tx, ty)
            }
            catchmentDirty = true
            return true
        }
    }
    return false
}

private const val PLACE_TRIES = 40

/** How far an industry keeps from others and from a town's middle, in tiles. */
private const val INDUSTRY_SPACING = 5
private const val TOWN_CLEARANCE = 7

private fun World.industryFits(kind: IndustryKind, x: Int, y: Int, w: Int, h: Int): Boolean {
    if (x < 1 || y < 1 || x + w >= width || y + h >= height) return false
    var suits = 0
    for (ty in y until y + h) for (tx in x until x + w) {
        val i = index(tx, ty)
        if (isWater(tx, ty) || steep(tx, ty) || track[i].toInt() != 0 || townBuilt(i) || industryOf[i].toInt() != 0 || owner[i].toInt() != -1) return false
        if (minOf(minOf(heightAt(tx, ty), heightAt(tx + 1, ty)), minOf(heightAt(tx + 1, ty + 1), heightAt(tx, ty + 1))) == 0) return false
        if (kind.ground.isEmpty() || Ground.NAMES[ground[i].toInt()].lowercase() in kind.ground) suits++
    }
    // Most of it on the ground it needs.
    if (suits * 2 < w * h) return false
    for (o in industries) if (!o.closed && o.x - INDUSTRY_SPACING < x + w && x - INDUSTRY_SPACING < o.x + roster.industries[o.kind].across(o.turn) &&
        o.y - INDUSTRY_SPACING < y + h && y - INDUSTRY_SPACING < o.y + roster.industries[o.kind].along(o.turn)) return false
    for (t in towns) if (kotlin.math.abs(t.x - (x + w / 2)) < TOWN_CLEARANCE && kotlin.math.abs(t.y - (y + h / 2)) < TOWN_CLEARANCE) return false
    return true
}

/**
 * A day of industry: each makes its share of a month's cargo, or turns what's
 * been brought into its own, and sends it to the stations that serve it.
 * Returns what each station got, by station and cargo, to be given
 * destinations with everything else made that day.
 */
internal fun World.industriesDay(made: HashMap<Int, IntArray>) {
    for (ind in industries) {
        if (ind.closed) continue
        val kind = roster.industries[ind.kind]
        val out = IntArray(Cargo.COUNT)
        if (kind.secondary) {
            // What's brought, made into its own: the same amount, shared among what it makes.
            val n = ind.brought
            ind.brought = 0
            val kinds = (0 until Cargo.COUNT).filter { kind.outputs and (1 shl it) != 0 }
            for ((k, c) in kinds.withIndex()) out[c] = n / kinds.size + if (k < n % kinds.size) 1 else 0
        } else {
            for (c in 0 until Cargo.COUNT) {
                val month = kind.produces[c] * ind.level / 16
                if (month > 0) out[c] = (month * 8 + rng.below(240)) / 240
            }
        }
        for (c in 0 until Cargo.COUNT) {
            if (out[c] == 0) continue
            ind.made[c] += out[c]
            // The two best rated stations that have had a train for it, sharing by their ratings.
            val served = industryStations[ind.id].orEmpty().mapNotNull { stations.getOrNull(it) }.filter { it.lastCall[c] >= 0 }
                .sortedWith(compareByDescending<Station> { it.rating[c] }.thenBy { it.id }).take(2)
            if (served.isEmpty()) continue
            val total = served.sumOf { it.rating[c] }.coerceAtLeast(1)
            for (s in served) {
                // Each gets its share, and of that what its rating lets it have.
                val share = out[c] * s.rating[c] / total
                val got = (share * s.rating[c] + rng.below(255)) / 255
                if (got <= 0) continue
                made.getOrPut(s.id) { IntArray(Cargo.COUNT) }[c] += got
                ind.moved[c] += got
            }
        }
    }
}

/**
 * The first of each month: what each industry made and moved last month, and
 * now and then a change. A primary industry well served tends to make more,
 * one poorly served less, and one that's fallen as far as it can closes. Now
 * and then a new one opens somewhere.
 */
internal fun World.industriesMonth() {
    val year = Calendar.year(startYear, day)
    for (ind in industries) {
        if (ind.closed) continue
        val kind = roster.industries[ind.kind]
        if (!kind.secondary && rng.below(CHANGE_ODDS) == 0) {
            val made = ind.made.sum()
            val share = if (made == 0) 0 else ind.moved.sum() * 100 / made
            // Well served it's likelier to grow, badly served likelier to shrink.
            val up = rng.below(100) < 25 + share / 2
            if (up && ind.level < MAX_LEVEL) {
                ind.level = minOf(MAX_LEVEL, ind.level * 3 / 2)
                news += News(News.PRODUCTION_UP, ind.id, tick)
            } else if (!up) {
                if (ind.level <= MIN_LEVEL || year >= kind.until) closeIndustry(ind) else {
                    ind.level = maxOf(MIN_LEVEL, ind.level * 2 / 3)
                    news += News(News.PRODUCTION_DOWN, ind.id, tick)
                }
            }
        }
        ind.made.copyInto(ind.madeLastMonth)
        ind.moved.copyInto(ind.movedLastMonth)
        ind.made.fill(0)
        ind.moved.fill(0)
        ind.broughtLastMonth = ind.broughtThisMonth
        ind.broughtThisMonth = 0
    }
    // New industries, more often on bigger maps.
    if (settings.industries > 0 && rng.below(OPEN_ODDS * 256 * 256 / (width * height).coerceAtLeast(1) + 1) == 0) {
        val kinds = roster.industries.filter { year >= it.from && year < it.until && it.weight(settings.region) > 0 }
        if (kinds.isNotEmpty()) {
            val total = kinds.sumOf { it.weight(settings.region) }
            var roll = rng.below(total)
            var i = 0
            while (roll >= kinds[i].weight(settings.region)) roll -= kinds[i++].weight(settings.region)
            if (placeIndustry(kinds[i])) news += News(News.INDUSTRY_OPENS, industries.last().id, tick)
        }
    }
    news.removeAll { tick - it.tick > NEWS_TICKS }
}

/** Shuts [ind] down: its tiles cleared, its stations no longer reaching it. */
internal fun World.closeIndustry(ind: Industry) {
    ind.closed = true
    val kind = roster.industries[ind.kind]
    for (ty in ind.y until ind.y + kind.along(ind.turn)) for (tx in ind.x until ind.x + kind.across(ind.turn)) {
        industryOf[index(tx, ty)] = 0
        touched(tx, ty)
    }
    catchmentDirty = true
    news += News(News.INDUSTRY_CLOSES, ind.id, tick)
}

/** An industry's level in sixteenths: at most 4 times and at least a quarter of its kind's. */
const val MAX_LEVEL = 64
const val MIN_LEVEL = 4

/** One in this many chances a month that a primary industry's production changes. */
private const val CHANGE_ODDS = 20

/** About one in this many months a new industry opens, on a 256 by 256 map. */
private const val OPEN_ODDS = 6

/** How long news is kept for the screen. */
const val NEWS_TICKS = Calendar.TICKS_PER_DAY * 10L

/** The industry in reach of station [s] that takes cargo [c], the first by id, or null. */
internal fun World.takerAt(s: Station, c: Int): Industry? =
    stationIndustries[s.id].orEmpty().map { industries[it] }.firstOrNull { !it.closed && roster.industries[it.kind].takes(c) }
