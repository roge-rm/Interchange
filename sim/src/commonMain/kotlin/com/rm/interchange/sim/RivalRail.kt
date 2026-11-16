package com.rm.interchange.sim

/*
 * Rival railways: a single line between two terminus stations, a depot behind
 * the first, and one train running back and forth. The line turns only on
 * level ground and its pieces meet level at every edge, so it lays as planned.
 */

/**
 * Where a terminus can go: its platforms inside ([x0], [y0]) to ([x1], [y1]), its line heading off towards ([cx], [cy]).
 * [town] is the town it's for, whose buildings it may pull down to get nearer the middle, or -1.
 */
internal class RailSite(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val cx: Int, val cy: Int, val town: Int = -1)

/**
 * A terminus: the tile behind it at ([x], [y]), then [lead] tiles of plain track, [n] platforms and the mouth, running
 * out through edge [out]. The first terminus of a line has a depot behind and one tile of track with a signal between,
 * so a train leaving the depot waits there while another is at the platforms.
 */
internal class Terminus(val x: Int, val y: Int, val out: Int, val n: Int, val lead: Int) {
    /** The tile [k] steps out from the back. */
    fun at(w: World, k: Int) = w.index(x + Track.dx(out) * k, y + Track.dy(out) * k)
    /** Platform [k], 1 to [n]. */
    fun platform(w: World, k: Int) = at(w, lead + k)
    val last get() = lead + n + 1
    fun mouth(w: World) = at(w, last)
    /** The tiles it takes, the back one included, the mouth left out. */
    fun tiles(w: World) = (0 until last).map { at(w, it) }
}

/** The site round industry [ind]: platforms in reach of it. */
internal fun World.railSite(ind: Industry, cx: Int, cy: Int): RailSite {
    val kind = roster.industries[ind.kind]
    val reach = CATCHMENT - 1
    return RailSite(ind.x - reach, ind.y - reach, ind.x + kind.across(ind.turn) - 1 + reach, ind.y + kind.along(ind.turn) - 1 + reach, cx, cy)
}

/** The site round town [t]'s middle. */
internal fun railSite(t: Town, cx: Int, cy: Int) = RailSite(t.x - TOWN_REACH, t.y - TOWN_REACH, t.x + TOWN_REACH, t.y + TOWN_REACH, cx, cy, t.id)

/** Whether tile [i] is clear for track or a platform: dry land with nothing built on it. */
private fun World.railFree(i: Int): Boolean {
    val x = i % width
    val y = i / width
    return !isWater(x, y) && track[i].toInt() == 0 && road[i].toInt() == 0 && house[i].toInt() == 0 && industryOf[i].toInt() == 0 &&
        !stationTiles.containsKey(i) && !depotTiles.containsKey(i) && !roadStops.containsKey(i) && !spanHeads.containsKey(i) &&
        !deck.containsKey(i) && !canals.contains(i) && !airportTiles.containsKey(i) && !saltDepots.contains(i) && !stationBuildings.containsKey(i)
}

/** Whether tile [i] holds a town building [c] may pull down for its line, with nothing else there. */
private fun World.clearable(c: Company, i: Int): Boolean =
    house[i].toInt() != 0 && road[i].toInt() == 0 && track[i].toInt() == 0 && !stationTiles.containsKey(i) && demolishCost(c.id, i) >= 0

/** How many of town [id]'s buildings [c] may pull down for a line, keeping well clear of the town refusing it. */
private fun World.mayClear(c: Company, id: Int): Int {
    val t = towns.getOrNull(id) ?: return 0
    return ((t.approval[c.id] - APPROVAL_REFUSES - CLEAR_MARGIN) / DEMOLISH_APPROVAL).coerceIn(0, MOST_CLEARED)
}

/**
 * The best few places for a terminus in [site]: level, clear or with a few town buildings to pull down if [clearing],
 * the mouth facing the way the line goes, and for [people] in reach of as many as it can. [depot] puts a depot and its
 * signal tile behind. [loose] needs only the first platform in the site, for freight.
 */
private fun World.termini(c: Company, site: RailSite, depot: Boolean, people: Boolean, clearing: Boolean, loose: Boolean, job: RailJob): List<Terminus> {
    val near = sites(c, site, depot, people, clearing, loose, job)
    if (near.isNotEmpty() || site.town < 0 || human(c.id)) return near
    // A rival's town with no room for one near the middle, a little further out.
    val w = RailSite(site.x0 - WIDER_REACH, site.y0 - WIDER_REACH, site.x1 + WIDER_REACH, site.y1 + WIDER_REACH, site.cx, site.cy, site.town)
    return sites(c, w, depot, people, clearing, loose, job)
}

/** As [termini], within [site] only. Each place looked at counts against [RailJob.left]. */
private fun World.sites(c: Company, site: RailSite, depot: Boolean, people: Boolean, clearing: Boolean, loose: Boolean, job: RailJob): List<Terminus> {
    val found = ArrayList<Pair<Terminus, Int>>()
    val budget = if (clearing && people && site.town >= 0) mayClear(c, site.town) else 0
    val sizes = if (budget > 0) intArrayOf(PLATFORMS, TOWN_PLATFORMS) else intArrayOf(PLATFORMS)
    val lead = if (depot) 1 else 0
    for (n in sizes) for (y in site.y0..site.y1) for (x in site.x0..site.x1) for (out in 0 until 4) {
        job.left--
        // The first platform at ([x], [y]).
        val t = Terminus(x - Track.dx(out) * (lead + 1), y - Track.dy(out) * (lead + 1), out, n, lead)
        val last = t.last
        if (!inside(t.x, t.y) || !inside(t.x + Track.dx(out) * last, t.y + Track.dy(out) * last)) continue
        // The platforms inside the site, all of it level at one height.
        val px = t.x + Track.dx(out) * (lead + n)
        val py = t.y + Track.dy(out) * (lead + n)
        if ((!loose || people) && (px !in site.x0..site.x1 || py !in site.y0..site.y1)) continue
        val h = heightAt(x, y)
        var ok = true
        var houses = 0
        for (k in (if (depot) 0 else 1)..last) {
            val i = t.at(this, k)
            if (!isFlat(i % width, i / width) || heightAt(i % width, i / width) != h) { ok = false; break }
            if (railFree(i)) continue
            // The mouth can cross a road.
            if (k == last && road[i].toInt() != 0 && track[i].toInt() == 0 && crossable(i, Track.straight(out))) continue
            if (budget == 0 || !clearable(c, i) || townOf[i].toInt() != site.town || ++houses > budget) { ok = false; break }
        }
        if (!ok || townRefuses(c, x, y)) continue
        val m = t.mouth(this)
        // Facing away from where it's going costs it the turn round.
        val away = if (Track.dx(out) * (site.cx - x) + Track.dy(out) * (site.cy - y) < 0) 12 else 0
        // For passengers, nearer the houses: a tile further for every few dozen people more in reach, and a few for each building pulled down.
        val near = if (people) catchPeople(t).also { job.left -= CATCH_STATES } / PEOPLE_A_TILE else 0
        found += t to chebyshev(m % width, m / width, site.cx, site.cy) + away - near + houses * HOUSE_TILES
    }
    found.sortWith(compareBy<Pair<Terminus, Int>> { it.second }.thenBy { it.first.y }.thenBy { it.first.x }.thenBy { it.first.out }.thenBy { -it.first.n })
    // The places kept face another way or stand a few tiles apart, so when one pair of ends doesn't pay the next
    // pairs tried for the same two sites are somewhere else.
    val picked = ArrayList<Terminus>()
    for ((t, _) in found) {
        if (picked.size == SITE_TRIES) break
        if (picked.none { it.out == t.out && chebyshev(it.x, it.y, t.x, t.y) < SPOT_APART }) picked += t
    }
    return picked
}

/** Starts a search for a way for track from [a]'s mouth to [b]'s, run by [runPath]. */
private fun World.startPath(job: RailJob, a: Terminus, b: Terminus, clearing: Boolean, bridges: Boolean): RailSearch {
    val start = a.mouth(this)
    val goal = b.mouth(this)
    val limit = (chebyshev(start % width, start / width, goal % width, goal / width) + 10).let { it * it * 16 }
        .coerceAtMost(if (bridges) LOOSE_PATH_LIMIT else RAIL_PATH_LIMIT)
    val search = RailSearch(clearing, limit)
    val states = job.states(width * height).apply { clear() }
    val s0 = (start * 4 + Track.opposite(a.out)) * 5 + BEND_STRAIGHT
    states.put(s0, 0, IntMap.NONE)
    search.heap.push(((kotlin.math.abs(start % width - goal % width) + kotlin.math.abs(start / width - goal / width)) * RAIL_MOVE).toLong() shl 32 or s0.toLong())
    return search
}

/**
 * Carries on [search], from [a]'s mouth to [b]'s, until it's done or [RailJob.left] runs out. The way found, as tiles
 * each with the edges it's come in and goes out by, packed tile * 16 + in * 4 + out; null if there's none within reach
 * or it's not done yet ([RailSearch.done]). With [bridges], [c]'s bridges can carry it straight over water or anything
 * else in the way, from one tile on the way to the next some tiles on.
 * The search keeps what the tile before did, so a tight bend (a lone diagonal between straights, or two diagonals
 * turning the same way) costs more and a 90 is spread over three diagonals where there's room. Paused and carried on,
 * it pushes and pops the same as it would in one go.
 */
private fun World.runPath(c: Company, job: RailJob, a: Terminus, b: Terminus, search: RailSearch, bridges: Boolean): IntArray? {
    val goal = b.mouth(this)
    val gx = goal % width
    val gy = goal / width
    val into = Track.opposite(b.out)
    // The stations' own tiles stay clear of the line.
    val kept = HashSet<Int>()
    kept += a.tiles(this)
    kept += b.tiles(this)
    val keptTiles = kept.toIntArray()
    val states = job.states(width * height)
    val clearing = search.clearing
    // Each step's cost by tile, edge come in at and edge left by, or -1 where it can't go. Kept for every search of one
    // pick, as worked out the first time.
    val steps = job.steps(width * height).let { if (clearing) it.steps else it.plain }
    val heap = search.heap
    val limit = search.limit
    fun h(i: Int) = (kotlin.math.abs(i % width - gx) + kotlin.math.abs(i / width - gy)) * RAIL_MOVE
    // A state is a tile, the edge come in at and what the tile before did: [BEND_STRAIGHT], a lone diagonal after a
    // straight or one in a run of diagonals, each turning one way or the other.
    fun state(tile: Int, came: Int, bend: Int) = (tile * 4 + came) * 5 + bend
    fun tileOf(s: Int) = s / 20
    while (!heap.isEmpty() && search.expanded < limit) {
        if (job.left <= 0) return null
        val top = heap.pop()
        val s = (top and 0xffffffffL).toInt()
        val g = states.cost(s)
        if (g < 0) continue
        if ((top ushr 32).toInt() - h(tileOf(s)) > g) continue
        search.expanded++
        job.left--
        val t = tileOf(s)
        val came = (s / 5) % 4
        val bend = s % 5
        // A bridge's far head takes straight track only.
        val from = states.from(s)
        val landed = from >= 0 && !next(tileOf(from), t)
        // A mouth on a road is crossed straight.
        if (t == goal && came != into && (road[t].toInt() == 0 || came == b.out) && (!landed || came == b.out)) {
            val out = ArrayList<Int>()
            var at = s
            var exit = into
            while (true) {
                val atCame = (at / 5) % 4
                out += tileOf(at) * 16 + atCame * 4 + exit
                exit = Track.opposite(atCame)
                at = states.from(at)
                if (at < 0) break
            }
            out.reverse()
            search.done = true
            return out.toIntArray()
        }
        val tx = t % width
        val ty = t / width
        val flat = isFlat(tx, ty)
        val crossing = road[t].toInt() != 0
        for (e in 0 until 4) {
            if (e == came) continue
            val turn = e != Track.opposite(came)
            if (turn && (!flat || crossing || landed)) continue
            val nx = tx + Track.dx(e)
            val ny = ty + Track.dy(e)
            if (!inside(nx, ny)) continue
            val n = index(nx, ny)
            if (keptTiles.contains(n)) continue
            if (bridges && !turn && !landed && blockedAhead(c, tx, ty, e)) {
                val land = bridgeLanding(c, tx, ty, e, kept)
                if (land >= 0) {
                    val ns = state(land, Track.opposite(e), BEND_STRAIGHT)
                    val ng = g + chebyshev(tx, ty, land % width, land / width) * RAIL_MOVE * RAIL_BRIDGE + (if (tightAt(bend, came, e)) RAIL_TIGHT else 0)
                    val was = states.cost(ns)
                    if (was < 0 || ng < was) {
                        states.put(ns, ng, s)
                        heap.push((ng + h(land)).toLong() shl 32 or ns.toLong())
                    }
                }
            }
            val key = t * 16 + came * 4 + e
            var step = steps[key]
            if (step == IntMap.NONE) {
                step = railStep(c, n, e, clearing)?.takeIf {
                    edgeHeight(tx, ty, Track.pieceOf(came, e), e) == edgeHeight(nx, ny, Track.straight(e), Track.opposite(e))
                } ?: -1
                steps[key] = step
            }
            if (step < 0) continue
            val tight = tightAt(bend, came, e)
            val nextBend = bendAfter(bend, came, e)
            val ns = state(n, Track.opposite(e), nextBend)
            // The goal's own piece is laid on the way into the station's straight, so its bend is counted here too.
            val atGoal = n == goal && (tightAt(nextBend, Track.opposite(e), into) || bendAfter(nextBend, Track.opposite(e), into).let { it == BEND_LONE_LEFT || it == BEND_LONE_RIGHT })
            val ng = g + step + (if (turn) RAIL_TURN else 0) + (if (tight) RAIL_TIGHT else 0) + (if (atGoal) RAIL_TIGHT else 0)
            val was = states.cost(ns)
            if (was in 0..ng) continue
            states.put(ns, ng, s)
            heap.push((ng + h(n)).toLong() shl 32 or ns.toLong())
        }
    }
    search.done = true
    if (search.expanded >= limit) job.ranOut++
    return null
}

/** A [railFlood] under way, for [RailJob.floods] key [key]: the tiles seen, one bit a tile, and those still to look on from. */
internal class RailReach(val key: Int, val seen: ByteArray, var queue: IntArray, var head: Int, var tail: Int)

/** A flood from [a]'s mouth for [RailJob.floods] key [key]. */
private fun World.startFlood(a: Terminus, key: Int): RailReach {
    val start = a.mouth(this)
    val f = RailReach(key, ByteArray((width * height + 7) / 8), IntArray(1 shl 10), 0, 0)
    f.seen.mark(start)
    f.queue[f.tail++] = start
    return f
}

/**
 * Carries on flood [f]: the tiles track could reach from [a]'s mouth in [runPath]'s search, each entered by any edge it
 * could leave the tile before by and only [a]'s own tiles kept clear, so every tile the search could reach is in it.
 * Once done it goes in [RailJob.floods], null if there are more than [RAIL_PATH_LIMIT] tiles, and it returns true;
 * false if [RailJob.left] runs out first, each tile looked on from counting against it.
 */
private fun World.railFlood(c: Company, a: Terminus, clearing: Boolean, job: RailJob, f: RailReach): Boolean {
    val kept = a.tiles(this).toIntArray()
    val seen = f.seen
    while (f.head < f.tail) {
        if (job.left <= 0) return false
        job.left--
        val t = f.queue[f.head++]
        val tx = t % width
        val ty = t / width
        val turns = isFlat(tx, ty) && road[t].toInt() == 0
        for (e in 0 until 4) {
            val nx = tx + Track.dx(e)
            val ny = ty + Track.dy(e)
            if (!inside(nx, ny)) continue
            val n = index(nx, ny)
            if (seen.has(n) || kept.contains(n) || railStep(c, n, e, clearing) == null) continue
            val there = edgeHeight(nx, ny, Track.straight(e), Track.opposite(e))
            var fits = false
            for (came in 0 until 4) {
                if (came == e || came != Track.opposite(e) && !turns) continue
                if (edgeHeight(tx, ty, Track.pieceOf(came, e), e) == there) { fits = true; break }
            }
            if (!fits) continue
            if (f.tail == RAIL_PATH_LIMIT) {
                job.floods[f.key] = null
                return true
            }
            seen.mark(n)
            if (f.tail == f.queue.size) f.queue = f.queue.copyOf(f.tail * 2)
            f.queue[f.tail++] = n
        }
    }
    job.floods[f.key] = seen
    return true
}

private fun ByteArray.has(i: Int) = (this[i ushr 3].toInt() shr (i and 7)) and 1 != 0
private fun ByteArray.mark(i: Int) { this[i ushr 3] = (this[i ushr 3].toInt() or (1 shl (i and 7))).toByte() }

/** Which way a tile come in at [came] and left by [e] turns: 0 straight, 1 or -1. */
private fun turnWay(came: Int, e: Int): Int {
    if (e == Track.opposite(came)) return 0
    val ix = Track.dx(Track.opposite(came))
    val iy = Track.dy(Track.opposite(came))
    return if (ix * Track.dy(e) - iy * Track.dx(e) > 0) 1 else -1
}

/** Whether a tile come in at [came] and left by [e], after a tile that did [bend], makes a tight bend. */
private fun tightAt(bend: Int, came: Int, e: Int): Boolean = when (turnWay(came, e)) {
    0 -> bend == BEND_LONE_LEFT || bend == BEND_LONE_RIGHT
    1 -> bend == BEND_LONE_LEFT || bend == BEND_RUN_LEFT
    else -> bend == BEND_LONE_RIGHT || bend == BEND_RUN_RIGHT
}

/** What a tile come in at [came] and left by [e] did, after one that did [bend]. */
private fun bendAfter(bend: Int, came: Int, e: Int): Int {
    val way = turnWay(came, e)
    return when {
        way == 0 -> BEND_STRAIGHT
        bend == BEND_STRAIGHT -> if (way > 0) BEND_LONE_LEFT else BEND_LONE_RIGHT
        else -> if (way > 0) BEND_RUN_LEFT else BEND_RUN_RIGHT
    }
}

/** The nearest tile a bridge from ([x], [y]) going out through edge [e] can land on, free for track beyond; -1 for none. */
private fun World.bridgeLanding(c: Company, x: Int, y: Int, e: Int, kept: Set<Int>): Int {
    for (k in 2..Spans.LONGEST[Spans.CONCRETE] + 1) {
        val lx = x + Track.dx(e) * k
        val ly = y + Track.dy(e) * k
        if (!inside(lx, ly)) return -1
        val style = bridgeStyle(k - 1)
        if (style < 0) return -1
        val n = index(lx, ly)
        if (n in kept || railStep(c, n, e, false) == null) continue
        if (bridgeCost(c, Command.Bridge(c.id, x, y, lx, ly, style)) >= 0) return n
    }
    return -1
}

/** Whether water or something track can't cross is one or two tiles on from ([x], [y]) through edge [e], so a bridge might do. */
private fun World.blockedAhead(c: Company, x: Int, y: Int, e: Int): Boolean = (1..2).any { k ->
    val nx = x + Track.dx(e) * k
    val ny = y + Track.dy(e) * k
    inside(nx, ny) && (isWater(nx, ny) || railStep(c, index(nx, ny), e, false) == null)
}

/** The cheapest bridge on sale that spans [len] tiles between its heads, or -1. */
private fun World.bridgeStyle(len: Int): Int {
    val year = Calendar.year(startYear, day)
    return (Spans.TRESTLE..Spans.CONCRETE).firstOrNull { year >= Spans.FROM[it] && len <= Spans.LONGEST[it] } ?: -1
}

/** The bridges [path] takes, one wherever it jumps from a tile to one not beside it. */
private fun World.railBridges(company: Int, path: IntArray): List<Command.Bridge> {
    val out = ArrayList<Command.Bridge>()
    for (k in 1 until path.size) {
        val a = path[k - 1] / 16
        val b = path[k] / 16
        val len = chebyshev(a % width, a / width, b % width, b / width) - 1
        if (len > 0) out += Command.Bridge(company, a % width, a / width, b % width, b / width, bridgeStyle(len))
    }
    return out
}

/** What laying track onto tile [i] across edge [e] costs in the search, or null if it can't go there. [clearing] lets it pull down town buildings. */
private fun World.railStep(c: Company, i: Int, e: Int, clearing: Boolean): Int? {
    val x = i % width
    val y = i / width
    val piece = Track.straight(e)
    // Across a road only straight over it, as a level crossing.
    if (road[i].toInt() != 0) return if (track[i].toInt() == 0 && crossable(i, piece)) RAIL_MOVE + RAIL_CROSSING else null
    val house = clearing && clearable(c, i)
    if (!railFree(i) && !house) return null
    return RAIL_MOVE + (if (house) RAIL_HOUSE else 0) + (if (isFlat(x, y)) 0 else RAIL_SLOPE) + (if (needsFoundation(x, y, piece)) RAIL_FOUNDATION else 0) + Trees.count(trees[i].toInt())
}

/** [path] as lines of track to lay: straight runs whole, each curve a piece on its own. */
private fun World.railRuns(company: Int, path: IntArray): List<Command.Rail> {
    val out = ArrayList<Command.Rail>()
    var k = 0
    while (k < path.size) {
        val t = path[k] / 16
        val piece = Track.pieceOf((path[k] / 4) % 4, path[k] % 4)
        var end = k
        if (piece == Track.X || piece == Track.Y) {
            while (end + 1 < path.size && Track.pieceOf((path[end + 1] / 4) % 4, path[end + 1] % 4) == piece && next(path[end] / 16, path[end + 1] / 16)) end++
        }
        val e = path[end] / 16
        out += Command.Rail(company, t % width, t / width, e % width, e / width, piece, remove = false)
        k = end + 1
    }
    return out
}

/** Whether tiles [a] and [b] are side by side, not a bridge's two heads. */
private fun World.next(a: Int, b: Int) = chebyshev(a % width, a / width, b % width, b / width) == 1

/** A train for [cargo] that fits a platform: the engine and wagons of the day, how many wagons, and a snow plow ahead of the engine or none. */
private class RailTrain(val engine: Engine, val wagon: Engine, val wagons: Int, val plow: Engine?)

/** The wedge plow a computer company fits its trains with where the snow lies in winter: weather on and a January normal below freezing. */
private fun World.railPlow(): Engine? {
    val year = Calendar.year(startYear, day)
    if (!settings.weather || Climate.normal(settings.region, year, JANUARY) >= 0) return null
    return roster.available(year, false).filter { it.plow == Plows.WEDGE }.minByOrNull { it.id }
}

/** The middle of January, as a day of the year. */
private const val JANUARY = 15

/**
 * The trains that could run for [cargo], about [load] of it at a time and no longer than [platforms]: the wagon of the
 * day holding most for its length behind each engine strong enough to pull them. Steam, diesel or dual-mode on its diesel, as the line has no wires.
 * [loose] takes as many wagons as each engine can pull at a third the power, for the scripted test player.
 */
private fun World.railTrains(cargo: Int, load: Int, platforms: Int, loose: Boolean = false): List<RailTrain> {
    val year = Calendar.year(startYear, day)
    val wagon = roster.available(year, false).filter { cargo in it.cargoes && it.capacity > 0 && it.plow == Plows.NONE }
        .maxWithOrNull(compareBy<Engine> { it.capacity * 16 / it.length }.thenBy { -it.id }) ?: return emptyList()
    val plow = railPlow()
    fun fits(e: Engine) = platforms * 16 - e.length - (roster.getOrNull(e.tender)?.length ?: 0) - (plow?.length ?: 0)
    val out = ArrayList<RailTrain>()
    for (e in roster.available(year, true)) {
        if (e.traction == Traction.ELECTRIC || fits(e) < wagon.length) continue
        var n = ((load + wagon.capacity - 1) / wagon.capacity).coerceIn(1, fits(e) / wagon.length)
        if (loose) {
            // Loaded weight in tonnes against power, a horsepower a tonne.
            fun tonnes() = e.weight + (roster.getOrNull(e.tender)?.weight ?: 0) + (plow?.weight ?: 0) + n * (wagon.weight + wagon.capacity)
            while (n > 1 && e.power(false) < tonnes()) n--
            if (e.power(false) >= tonnes()) out += RailTrain(e, wagon, n, plow)
            continue
        }
        while (n > 1 && !pulls(e, wagon, n, cargo, plow)) n--
        // Shorter ones too, as a few cars may pay better than many half empty.
        if (pulls(e, wagon, n, cargo, plow)) for (k in n downTo 1) out += RailTrain(e, wagon, k, plow)
    }
    return out
}

/** Whether a train for [cargo] can be bought now that fits a terminus. */
internal fun World.railCarries(cargo: Int): Boolean = railTrains(cargo, 1, PLATFORMS).isNotEmpty()

/**
 * Whether [e], off the wires, can start [n] of [wagon] loaded with [cargo] and any [plow] on a half percent grade and
 * hold 70% of its top speed up it, as the balance tables reckon a train.
 */
private fun World.pulls(e: Engine, wagon: Engine, n: Int, cargo: Int, plow: Engine?): Boolean {
    val tender = (roster.getOrNull(e.tender)?.weight ?: 0) + (plow?.weight ?: 0)
    // Kilograms, and newtons for the grade.
    val mass = (e.weight + tender + n * (wagon.weight + wagon.capacity * Cargo.KG[cargo] / 1000)).toLong() * 1000
    val grade = mass * 981 / 100 * 5 / 1000
    val slip = e.tractive * 1000L
    val units = maxOf(10, e.speed(false) * 7 / 10) * 65536 / 2700
    val pull = minOf(slip, e.power(false) * 74_570L / (units * 1144L / 1000).coerceAtLeast(150))
    return slip * 8 / 10 > resistance(mass, 0) + grade && pull > resistance(mass, units) + grade
}

/**
 * What [train] is expected to make in a work year after its running cost and the line's upkeep, carrying [cargo]
 * between tiles [from] and [to] over [tiles] of track one way: whichever's less of what it can haul at its speed and
 * what comes to it, at the fare for its days on board. That's about [load] a month for freight, and for passengers
 * what [peopleA] and [peopleB] in reach of each end make, each way, at the rating its trains every so often earn.
 */
private fun World.yearly(c: Company, train: RailTrain, cargo: Int, load: Int, peopleA: Int, peopleB: Int, tiles: Int, from: Int, to: Int, path: IntArray? = null, giveUp: Boolean = true): Long {
    val capacity = train.wagon.capacity * train.wagons
    val there = oneWay(train, tiles, path)
    val trips = 365 / (there * 2) * capacity
    val every = there * 2
    // A passenger train coming round less often than people wait for one isn't reckoned on: too many go home.
    if (giveUp && cargo == Cargo.PASSENGERS && every > GIVE_UP_DAYS) return -price(train.engine.cost)
    fun month(people: Int): Int {
        val first = people * 15 * railRating(every, train.engine.speed(false), 0) / (16 * 255)
        // About half of what gathers between calls is waiting at any time, and a fifth off for the slower running,
        // breakdowns and quiet days this leaves out.
        return people * 12 * railRating(every, train.engine.speed(false), first * every / 60) / (16 * 255)
    }
    // Passengers come from each end, both ways; freight one way.
    val hauled = if (cargo == Cargo.PASSENGERS) minOf(trips, month(peopleA) * 12) + minOf(trips, month(peopleB) * 12) else minOf(trips, load * 12)
    val dist = kotlin.math.abs(from % width - to % width) + kotlin.math.abs(from / width - to / width)
    // Freight waits on board for a full load, half of it the whole time it takes to fill.
    val filling = if (cargo == Cargo.PASSENGERS) 0 else capacity * 15 / load.coerceAtLeast(1)
    val late = (there + filling - dist * Cargo.PACE[cargo] - Cargo.GRACE[cargo]).coerceAtLeast(0)
    val share = (100 - late * Cargo.DECAY[cargo]).coerceAtLeast(33)
    val income = pay(hauled.toLong() * dist * Cargo.RATE[cargo] * share / 10_000)
    val running = price(train.engine.running + train.wagon.running * train.wagons + (roster.getOrNull(train.engine.tender)?.running ?: 0) + (train.plow?.running ?: 0))
    // Upkeep on the line and both stations, in tenths of a cent a work day.
    val upkeep = price(tiles.toLong() * Upkeep.TRACK[c.upkeep] * 365 / 1_000)
    return income - running - upkeep
}

/**
 * Work days [train] takes one way over [tiles] of track: a tile a day at 40-odd km/h, a fifth more for starting,
 * stopping and servicing, and a few days at each end; the bends on [path] count as the tiles they'd be at top speed.
 */
private fun World.oneWay(train: RailTrain, tiles: Int, path: IntArray?): Int {
    val sixteenths = tiles * 16 + (if (path != null) bendSixteenths(path, train) else 0)
    return sixteenths * KMH_PER_TILE_A_DAY * 6 / (5 * 16 * train.engine.speed(false).coerceAtLeast(1)) + 4
}

/**
 * How much longer than at top speed [train] takes over [path]'s bends, in sixteenths of a tile at top speed. Each bend
 * holds it to its cap from a tile before it, braking, till its tail is a tile past, picking up again.
 */
private fun World.bendSixteenths(path: IntArray, train: RailTrain): Int {
    val top = train.engine.speed(false)
    val steps = IntArray(path.size) { k -> step(path[k] / 16, Track.index(Track.pieceOf((path[k] / 4) % 4, path[k] % 4)), (path[k] / 4) % 4) }
    val caps = bendCaps(steps)
    val tender = (roster.getOrNull(train.engine.tender)?.length ?: 0) + (train.plow?.length ?: 0)
    val cars = (train.engine.length + tender + train.wagon.length * train.wagons + 15) / 16
    val held = IntArray(path.size)
    for (i in caps.indices) {
        val cap = caps[i]
        if (cap == 0 || cap >= top) continue
        for (k in maxOf(0, i - 1)..minOf(path.size - 1, i + cars)) if (held[k] == 0 || cap < held[k]) held[k] = cap
    }
    var extra = 0
    for (cap in held) if (cap > 0) extra += 16 * top / cap - 16
    return extra
}

/**
 * About the passenger rating a station keeps with a train calling every [days] at [speed] km/h and [waiting] there:
 * the rating follows how long since the last call, so it's the average of that over the days between calls.
 */
private fun railRating(days: Int, speed: Int, waiting: Int): Int {
    val n = days.coerceAtLeast(1)
    var calls = 0
    for (d in 0 until n) calls += when {
        d <= 7 -> 95
        d <= 15 -> 65
        d <= 30 -> 45
        d <= 52 -> 25
        else -> 0
    }
    val queue = when {
        waiting <= 100 -> 40
        waiting <= 300 -> 30
        waiting <= 600 -> 10
        else -> 0
    }
    return 33 + calls / n + queue + ((speed - 50) / 4).coerceIn(0, 42)
}

/**
 * People in reach of [t]'s platforms that no station has yet, its houses' residents and jobs, less the buildings it
 * pulls down: each makes about 15/16 of a passenger a month before the rating's share.
 */
private fun World.catchPeople(t: Terminus): Int {
    if (catchmentDirty) workOutCatchment()
    val a = t.platform(this, 1)
    val b = t.platform(this, t.n)
    val x0 = maxOf(0, minOf(a % width, b % width) - CATCHMENT)
    val x1 = minOf(width - 1, maxOf(a % width, b % width) + CATCHMENT)
    val y0 = maxOf(0, minOf(a / width, b / width) - CATCHMENT)
    val y1 = minOf(height - 1, maxOf(a / width, b / width) + CATCHMENT)
    val gone = (0..t.last).map { t.at(this, it) }.toSet()
    var people = 0
    for (y in y0..y1) for (x in x0..x1) {
        val i = index(x, y)
        if (house[i].toInt() == 0 || i in gone || caught(i)) continue
        val h = roster.houses.getOrNull(houseOf(house[i].toInt())) ?: continue
        people += h.residents + h.jobs
    }
    return people
}

/**
 * People a feeder bus from the middle of town [id] would bring to [t], when its platforms don't reach the middle:
 * half of those in reach of a stop there and not of the platforms, as some only ride the bus.
 */
private fun World.feederPeople(t: Terminus, id: Int): Int {
    val town = towns.getOrNull(id) ?: return 0
    if (catchmentDirty) workOutCatchment()
    val a = t.platform(this, 1)
    val b = t.platform(this, t.n)
    val px0 = minOf(a % width, b % width) - CATCHMENT
    val px1 = maxOf(a % width, b % width) + CATCHMENT
    val py0 = minOf(a / width, b / width) - CATCHMENT
    val py1 = maxOf(a / width, b / width) + CATCHMENT
    if (town.x in px0..px1 && town.y in py0..py1) return 0
    var people = 0
    for (y in maxOf(0, town.y - CATCHMENT)..minOf(height - 1, town.y + CATCHMENT)) for (x in maxOf(0, town.x - CATCHMENT)..minOf(width - 1, town.x + CATCHMENT)) {
        val i = index(x, y)
        if (x in px0..px1 && y in py0..py1 || house[i].toInt() == 0 || caught(i)) continue
        val h = roster.houses.getOrNull(houseOf(house[i].toInt())) ?: continue
        people += h.residents + h.jobs
    }
    return people / 2
}

/** Whether a station already has the house on tile [i]: [World.catchment] is tiles in order, each with its station. */
private fun World.caught(i: Int): Boolean {
    val c = catchment
    var lo = 0
    var hi = c.size / 2 - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val t = c[mid * 2]
        if (t == i) return true
        if (t < i) lo = mid + 1 else hi = mid - 1
    }
    return false
}

/** What [t] costs, its tender and plow with it. */
private fun World.trainCost(t: RailTrain): Long = price(t.engine.cost + t.wagon.cost * t.wagons + (roster.getOrNull(t.engine.tender)?.cost ?: 0) + (t.plow?.cost ?: 0))

/**
 * Joins sites [sa] and [sb] by a railway carrying [cargo], [load] a month of freight or the passengers its stations
 * get, with a terminus at each and a depot behind the first, and sets a train going, handing the route to [built].
 * [serves] checks each new station reaches what it's for. Only a line expected to pay for itself within [payback]
 * work years is built, or any line if that's 0. A computer company may pull down a few town buildings to get its
 * passenger stations nearer the middle of town. [loose] is for the scripted test player: any engine that moves its
 * train, as many wagons as the platforms hold, bridges over water and a longer search for the way. All at once,
 * through the same job a rival runs over ticks.
 */
internal fun World.tryRail(
    c: Company, sa: RailSite, sb: RailSite, cargo: Int, load: Int, from: Int, to: Int,
    serves: (Station, Boolean) -> Boolean, payback: Int, loose: Boolean = false, built: (RivalRoute) -> Unit,
): Tried {
    val job = RailJob(listOf(RailPick(from, to, cargo, load, sa, sb)), payback, loose)
    job.left = Int.MAX_VALUE
    return runPick(c, job, serves, built) ?: Tried.CANT
}

/** A railway a rival means to try: from [from] to [to], industries or towns, as for [tryRail]. */
internal class RailPick(val from: Int, val to: Int, val cargo: Int, val load: Int, val sa: RailSite, val sb: RailSite)

/**
 * A rival's railway planning, carried on a little each tick: the [picks] to try in turn, as [tryRail] does each, and
 * where it's got to. [running], [from] and [opensLeft] are where its look at opening routes carries on once it's done.
 */
internal class RailJob(val picks: List<RailPick>, val payback: Int, val loose: Boolean) {
    var running = -1
    var from = 0
    var opensLeft = 0
    /** The pick being tried, and its places for the first terminus and the second, each null before it's found. */
    var pick = 0
    var aList: List<Terminus>? = null
    var bList: List<Terminus>? = null
    /** The pair of termini being tried. */
    var ia = 0
    var ib = 0
    /** Searches of this pick that ran out of steps. */
    var ranOut = 0
    /** 0 before the pair's search, 1 in its first, 2 in the one round town buildings, each flooding while [search] is null. */
    var phase = 0
    var search: RailSearch? = null
    /** [railFlood] by first terminus and pulling down buildings or not, ia * 2 + 1 for pulling down. */
    val floods = HashMap<Int, ByteArray?>()
    /** A flood under way. */
    var reach: RailReach? = null
    /** Search steps left this tick, not saved. */
    var left = 0
    private var states: SearchStates? = null
    private var steps: RailSteps? = null

    /** The states the search uses, for a map of [tiles], made when first wanted. */
    fun states(tiles: Int): SearchStates = states ?: SearchStates(if (tiles <= FLAT_TILES) tiles * 20 else 0).also { states = it }

    /** The step costs worked out for this pick, on a map of [tiles]. */
    fun steps(tiles: Int): RailSteps = steps ?: RailSteps(tiles).also { steps = it }

    /** Whether any step costs have been worked out yet. */
    val hasSteps get() = steps != null

    fun nextPick() {
        pick++
        aList = null
        bList = null
        ia = 0
        ib = 0
        ranOut = 0
        nextPair()
        floods.clear()
        // The arrays go with the pick, so only the job part way through one holds them.
        states = null
        steps = null
    }

    fun nextPair() {
        phase = 0
        search = null
        reach = null
    }
}

/** A path search under way: its heap and steps so far, the states being [RailJob.states]. */
internal class RailSearch(val clearing: Boolean, val limit: Int) {
    val heap = LongHeap()
    var expanded = 0
    var done = false
}

/** What a pair of termini would carry and the trains that could run it; null if it isn't worth searching. */
private class PairPlan(val pa: Int, val pb: Int, val trains: List<RailTrain>)

private fun World.pairPlan(c: Company, p: RailPick, a: Terminus, b: Terminus, clearing: Boolean, loose: Boolean): PairPlan? {
    val passengers = p.cargo == Cargo.PASSENGERS
    // A computer company runs feeder buses from the middle of each town its platforms don't reach. Those riding
    // them go to either station by its people and nearness, as passengers choose, and only those for the far
    // one take the train.
    val ca = if (passengers) catchPeople(a) else 0
    val cb = if (passengers) catchPeople(b) else 0
    val apart = kotlin.math.abs(a.mouth(this) % width - b.mouth(this) % width) + kotlin.math.abs(a.mouth(this) / width - b.mouth(this) / width)
    fun people(t: Terminus, s: RailSite, here: Int, there: Int): Int {
        if (!clearing || s.town < 0) return here
        val town = towns.getOrNull(s.town) ?: return here
        val pl = t.platform(this, 1)
        val near = (here + 20) * 256L / (kotlin.math.abs(town.x - pl % width) + kotlin.math.abs(town.y - pl / width) + 8)
        val far = (there + 20) * 256L / (apart + 8)
        return here + (feederPeople(t, s.town) * far / (near + far)).toInt()
    }
    val pa = if (passengers) people(a, p.sa, ca, cb) else 0
    val pb = if (passengers) people(b, p.sb, cb, ca) else 0
    // Loose, a pair of termini where one has nobody in reach isn't built only to come up again.
    if (loose && (passengers || p.cargo == Cargo.MAIL) && minOf(catchPeople(a), catchPeople(b)) == 0) return null
    // Every length the platforms hold, the best picked below.
    val trains = railTrains(p.cargo, LOOSE_LOAD, minOf(a.n, b.n), loose)
    if (trains.isEmpty()) return null
    return PairPlan(pa, pb, trains)
}

/**
 * Carries on [job]'s pick, as [tryRail] describes, until it's built, can't be or can't be paid for, or [RailJob.left]
 * runs out (null). The map may change between goes; a line that no longer builds is given up as any that won't.
 */
internal fun World.runPick(c: Company, job: RailJob, serves: (Station, Boolean) -> Boolean, built: (RivalRoute) -> Unit): Tried? {
    val p = job.picks[job.pick]
    val passengers = p.cargo == Cargo.PASSENGERS
    val clearing = passengers && !human(c.id)
    val loose = job.loose
    val payback = job.payback
    // The places for each terminus, each in one go.
    if (job.aList == null) {
        if (job.left <= 0) return null
        job.aList = termini(c, p.sa, true, passengers, clearing, loose, job)
    }
    if (job.bList == null) {
        if (job.left <= 0) return null
        job.bList = termini(c, p.sb, false, passengers, clearing, loose, job)
    }
    val aList = job.aList!!
    val bList = job.bList!!
    // Town buildings in the way, by town: each town lets only so many go. A way that pulls down too many with the
    // termini' own is looked for again round the buildings.
    fun houses(a: Terminus, b: Terminus, path: IntArray) = (a.tiles(this) + b.tiles(this) + path.map { it / 16 }).filter { house[it].toInt() != 0 }.distinct()
    fun tooMany(houses: List<Int>) = houses.groupingBy { townOf[it].toInt() }.eachCount().any { (t, n) -> n > mayClear(c, t) }
    while (job.ia < aList.size) {
        if (job.ib >= bList.size) {
            job.ia++
            job.ib = 0
            continue
        }
        val a = aList[job.ia]
        val b = bList[job.ib]
        if (job.phase == 0) {
            if (job.left <= 0) return null
            // A computer company stops once a few searches to the far site have run out of steps, as the rest mostly do too.
            if (!loose && job.ranOut >= RAN_OUT_TRIES) return Tried.CANT
            val plan = pairPlan(c, p, a, b, clearing, loose)
            if (plan == null || !pays(c, p, a, b, plan, payback, null)) {
                job.ib++
                continue
            }
            job.phase = 1
        }
        // The first search pulls down town buildings if it may, the second goes round them.
        val clears = job.phase == 1 && clearing
        if (job.search == null) {
            // Without bridges, a goal that no track from the start could reach, whatever its bends, isn't searched for.
            if (!loose) {
                val key = job.ia * 2 + (if (clears) 1 else 0)
                if (!job.floods.containsKey(key)) {
                    val f = job.reach ?: startFlood(a, key).also { job.reach = it }
                    if (!railFlood(c, a, clears, job, f)) return null
                    job.reach = null
                }
                val reach = job.floods[key]
                if (reach != null && !reach.has(b.mouth(this))) {
                    job.ib++
                    job.nextPair()
                    continue
                }
            }
            job.search = startPath(job, a, b, clears, loose)
        }
        val search = job.search!!
        val path = runPath(c, job, a, b, search, loose)
        if (!search.done) return null
        if (path == null) {
            job.ib++
            job.nextPair()
            continue
        }
        if (job.phase == 1 && clearing && tooMany(houses(a, b, path))) {
            job.search = null
            job.phase = 2
            continue
        }
        job.ib++
        job.nextPair()
        val houses = houses(a, b, path)
        if (tooMany(houses)) continue
        // What it'd carry, worked out again as the map may have changed while it searched.
        val plan = pairPlan(c, p, a, b, clearing, loose) ?: continue
        return buildPair(c, p, a, b, plan, path, houses, payback, loose, serves, built) ?: continue
    }
    return Tried.CANT
}

/**
 * Whether a line between [a] and [b] could pay within [payback] work years ([pairPlan]'s trains over [path]'s
 * length), or with no path yet over the way straight there, which it can't be shorter than.
 */
private fun World.pays(c: Company, p: RailPick, a: Terminus, b: Terminus, plan: PairPlan, payback: Int, path: IntArray?): Boolean {
    if (payback <= 0) return true
    val am = a.mouth(this)
    val bm = b.mouth(this)
    val least = kotlin.math.abs(am % width - bm % width) + kotlin.math.abs(am / width - bm / width) + a.last + b.last
    val fixed = price(Prices.PLATFORM) * (a.n + b.n) + price(Prices.DEPOT) + price(Prices.SIGNAL) + price(Prices.TRACK) * least
    return plan.trains.any { made(c, p, a, b, plan, it, least) * payback >= fixed + trainCost(it) }
}

private fun World.made(c: Company, p: RailPick, a: Terminus, b: Terminus, plan: PairPlan, t: RailTrain, tiles: Int, path: IntArray? = null, giveUp: Boolean = true) =
    yearly(c, t, p.cargo, p.load, plan.pa, plan.pb, tiles, a.platform(this, 1), b.platform(this, 1), path, giveUp)

/** Prices the line along [path] and builds it if it pays and can be paid for; null to try the next pair. */
private fun World.buildPair(
    c: Company, p: RailPick, a: Terminus, b: Terminus, plan: PairPlan, path: IntArray, houses: List<Int>, payback: Int, loose: Boolean,
    serves: (Station, Boolean) -> Boolean, built: (RivalRoute) -> Unit,
): Tried? {
    val passengers = p.cargo == Cargo.PASSENGERS
    val years = if (payback > 0) payback else ANY_PAYBACK
    val trains = plan.trains
    val runs = railRuns(c.id, path)
    val spans = if (loose) railBridges(c.id, path) else emptyList()
    var cost = houses.sumOf { demolishCost(c.id, it) }
    for (run in runs) {
        val (each, n) = railCost(run)
        // Track on a town building is laid once it's down, so costs a plain tile then. Any other piece that
        // wouldn't go down leaves a gap, so no line.
        val line = Track.line(this, run.sx, run.sy, run.ex, run.ey, run.first)
        val laid = planRail(run)
        if (each < 0 || line == null || laid == null) return null
        var onHouses = 0
        var ok = true
        for (k in line.indices) if (!laid[k]) { if (house[line[k] / 64].toInt() != 0) onHouses++ else ok = false }
        if (!ok || n == 0 && onHouses == 0) return null
        cost += each + price(Prices.TRACK) * onHouses
    }
    var ok = true
    for (span in spans) {
        val each = bridgeCost(c, span)
        if (each < 0) ok = false
        cost += each
    }
    if (!ok) return null
    val tiles = path.size + a.last + b.last
    // The train that would do best over the payback time: what it makes in that time less what it costs.
    var train = trains.first()
    var best = made(c, p, a, b, plan, train, tiles, path) * years - trainCost(train)
    for (t in trains.drop(1)) {
        val v = made(c, p, a, b, plan, t, tiles, path) * years - trainCost(t)
        if (v > best) { train = t; best = v }
    }
    // Loose, on a line too long for any train's passengers to be reckoned on, the one that would do best if
    // they were.
    if (loose && passengers && trains.all { oneWay(it, tiles, path) * 2 > GIVE_UP_DAYS }) {
        train = trains.maxBy { made(c, p, a, b, plan, it, tiles, path, giveUp = false) * years - trainCost(it) }
    }
    cost += price(Prices.PLATFORM) * (a.n + b.n) + price(Prices.DEPOT) + price(Prices.TRACK) + price(Prices.SIGNAL) + trainCost(train)
    if (payback > 0 && made(c, p, a, b, plan, train, tiles, path) * payback < cost) return null
    if (!afford(c, cost * 6 / 5)) return Tried.POOR
    // One that doesn't build, as the map changed while it searched, goes on to the next pair.
    val route = buildRail(c, a, b, houses, path, runs + spans, train, p.cargo, p.from, p.to, serves) ?: return null
    built(route)
    return Tried.BUILT
}

private fun World.buildRail(
    c: Company, a: Terminus, b: Terminus, houses: List<Int>, path: IntArray, runs: List<Command>, train: RailTrain, cargo: Int, from: Int, to: Int,
    serves: (Station, Boolean) -> Boolean,
): RivalRoute? {
    // The town buildings in the way come down first; one that won't means no line.
    for (i in houses) apply(Command.Clear(c.id, i % width, i / width))
    if (houses.any { house[it].toInt() != 0 }) return null
    for (run in runs) apply(run)
    val axisA = Track.straight(a.out)
    val axisB = Track.straight(b.out)
    val a1 = a.platform(this, 1)
    val aN = a.platform(this, a.n)
    val b1 = b.platform(this, 1)
    val bN = b.platform(this, b.n)
    apply(Command.Station(c.id, a1 % width, a1 / width, aN % width, aN / width, axisA))
    apply(Command.Station(c.id, b1 % width, b1 / width, bN % width, bN / width, axisB))
    // The depot, and the signal tile between it and the platforms: a path signal, so a train coming out to the first
    // station needs only its platform clear.
    val lead = a.at(this, 1)
    apply(Command.Rail(c.id, lead % width, lead / width, lead % width, lead / width, axisA, remove = false))
    apply(Command.Signal(c.id, lead % width, lead / width, axisA, a.out, Signals.PATH))
    val before = depots.size
    apply(Command.Depot(c.id, a.x, a.y, a.out))
    val sa = stationTiles[a1]?.let { stations[it] }
    val sb = stationTiles[b1]?.let { stations[it] }
    val laid = path.all { p -> track[p / 16].toInt() and Track.pieceOf((p / 4) % 4, p % 4) != 0 } &&
        runs.all { it !is Command.Bridge || spanHeads.containsKey(index(it.x0, it.y0)) && spanHeads.containsKey(index(it.x1, it.y1)) }
    if (catchmentDirty) workOutCatchment()
    if (!laid || depots.size == before || pieceKey(lead, axisA) !in signals || sa == null || sb == null || sa.id == sb.id ||
        sa.owner != c.id || sb.owner != c.id || !serves(sa, true) || !serves(sb, false)
    ) {
        // Taken up again, a tile at a time: the track, the platforms and the depot.
        val tiles = path.map { it / 16 } + a.tiles(this) + b.tiles(this)
        for (i in tiles) if (owner[i].toInt() == c.id) clearTile(c, Command.Clear(c.id, i % width, i / width))
        return null
    }
    val route = RivalRoute(sa.id, sb.id, depots.size - 1, from, to, cargo, rail = true)
    buyTrain(c, route, train)
    return route
}

/**
 * Buys a train for [route] in its depot, its plow fitted ahead of the engine, and sets it going: full at the first
 * station for freight, coming back empty.
 */
private fun World.buyTrain(c: Company, route: RivalRoute, train: RailTrain) {
    val before = trains.size
    apply(Command.BuyTrain(c.id, route.depot, train.engine.id, train.wagon.id, train.wagons, route.cargo))
    if (trains.size == before) return
    val id = trains.size - 1
    train.plow?.let { p -> trains[id]?.let { apply(Command.EditTrain(c.id, id, listOf(TrainCar(p.id)) + it.formation)) } }
    val orders = if (route.freight) intArrayOf(route.a or Order.FULL_LOAD, route.b or Order.NO_LOAD) else intArrayOf(route.a, route.b)
    apply(Command.TrainOrder(c.id, id, Command.TrainOrder.ORDERS, orders))
    apply(Command.TrainOrder(c.id, id, Command.TrainOrder.START_STOP))
}

/*
 * More trains on a line. A single line takes one train each way between passing loops: a loop is a second track
 * beside the line for [LOOP_RUN] tiles, joined at each end, with one-way path signals so trains heading out from the
 * first station keep to the line and those heading back take the loop, and a train only leaves a loop once the line
 * and its track of the next loop, or the platforms, are clear. A line with n loops runs n + 1 trains.
 */

/** Tiles of a loop's straight between its signals, enough for the longest train a terminus takes, and a tile spare. */
private const val LOOP_RUN = 6
/** Tiles of line a loop takes, from one join to the other. */
private const val LOOP_LONG = LOOP_RUN + 3
/** Tiles of line for each loop, and the most loops a line has. */
private const val LOOP_EVERY = 14
private const val MOST_LOOPS = 3

/** A loop, packed: the line tile it leaves at, the way the line runs there and the side the loop is on, as [Track] edges. */
internal fun packLoop(tile: Int, along: Int, side: Int) = tile * 16 + along * 4 + side

/** The line's tiles a loop runs beside, from where it leaves to where it joins again, then the loop's own. */
internal fun World.loopTiles(loop: Int): IntArray {
    val t = loop / 16
    val f = (loop / 4) % 4
    val s = loop % 4
    val out = IntArray(LOOP_LONG * 2)
    for (k in 0 until LOOP_LONG) {
        val x = t % width + Track.dx(f) * k
        val y = t / width + Track.dy(f) * k
        out[k] = index(x, y)
        out[LOOP_LONG + k] = index(x + Track.dx(s), y + Track.dy(s))
    }
    return out
}

/** The plain track tile between [route]'s depot and its first platforms, or -1 for a line built without one. */
internal fun World.leadTile(c: Company, route: RivalRoute): Int {
    val d = depots.getOrNull(route.depot) ?: return -1
    val x = d.tile % width + Track.dx(d.edge)
    val y = d.tile / width + Track.dy(d.edge)
    if (!inside(x, y)) return -1
    val i = index(x, y)
    return if (owner[i].toInt() == c.id && !stationTiles.containsKey(i) && signals.containsKey(pieceKey(i, Track.straight(d.edge)))) i else -1
}

/**
 * The tiles of [route]'s line in order, from a platform of its first station to one of its second, along [c]'s own
 * track: plain tiles of one piece with no signal, or any of [through]. Null if it can't be followed.
 */
internal fun World.lineTiles(c: Company, route: RivalRoute, through: Set<Int>): List<Int>? {
    val sa = stations.getOrNull(route.a) ?: return null
    val sb = stations.getOrNull(route.b) ?: return null
    fun pieces(i: Int) = Track.PIECES.count { track[i].toInt() and it != 0 }
    fun plain(i: Int): Boolean {
        if (i in through) return owner[i].toInt() == c.id
        if (owner[i].toInt() != c.id || pieces(i) != 1 || i in signals || depotTiles.containsKey(i)) return false
        val id = stationTiles[i] ?: return true
        return id == sa.id || id == sb.id
    }
    val from = HashMap<Int, Int>()
    val queue = ArrayList<Int>()
    for (i in sa.tiles) if (plain(i)) { from[i] = -1; queue += i }
    var end = -1
    var k = 0
    while (k < queue.size && end < 0) {
        val i = queue[k++]
        if (stationTiles[i] == sb.id) { end = i; break }
        val bits = track[i].toInt()
        for (e in 0 until 4) {
            if (Track.PIECES.none { bits and it != 0 && Track.has(it, e) }) continue
            val x = i % width + Track.dx(e)
            val y = i / width + Track.dy(e)
            if (!inside(x, y)) continue
            val n = index(x, y)
            val back = Track.opposite(e)
            if (n in from || !plain(n) || Track.PIECES.none { track[n].toInt() and it != 0 && Track.has(it, back) }) continue
            from[n] = i
            queue += n
        }
    }
    if (end < 0) return null
    val line = ArrayList<Int>()
    var i = end
    while (i >= 0) { line += i; i = from.getValue(i) }
    line.reverse()
    return line
}

/** The tiles a given-up railway takes up beyond its line: its loops and the signal tile by its depot. */
internal fun World.railExtras(c: Company, route: RivalRoute): Set<Int> {
    val out = HashSet<Int>()
    for (l in route.loops) out += loopTiles(l).asList()
    leadTile(c, route).takeIf { it >= 0 }?.let { out += it }
    return out
}

/** The wagon [t] carries its cargo in, past any plow. */
private fun World.cargoWagon(t: Train): Int =
    t.formation.firstOrNull { roster[it.model].kind == VehicleKind.WAGON && roster[it.model].plow == Plows.NONE }?.model ?: t.wagon

/** The trains running [route]. */
private fun World.trainsOn(c: Company, route: RivalRoute) =
    trains.filter { it != null && it.owner == c.id && onRoute(it, route) }.map { it!! }

/**
 * Another train for railway [route] when its trains pay back what one costs within [payback] work years and leave a
 * trainload waiting: a passing loop first if the line has no room for one more, as long as it's long enough for it.
 */
internal fun World.growRail(c: Company, route: RivalRoute, payback: Int, ranLastYear: (Int) -> Boolean) {
    val on = trainsOn(c, route)
    val first = on.firstOrNull() ?: return
    // Not while one is on its way to the depot or about to be, as the new train would wait at the signal in its way.
    if (on.size > 1 && on.any { it.service } || on.any { it.toDepot || it.inDepot && !it.running || ranLastYear(it.built) && it.profitLastYear < 0 }) return
    val a = stations.getOrNull(route.a) ?: return
    val b = stations.getOrNull(route.b) ?: return
    val capacity = roster[cargoWagon(first)].let { w -> first.formation.count { it.model == w.id } * w.capacity }
    val waiting = if (route.freight) a.waiting(route.cargo) else maxOf(a.waiting(route.cargo), b.waiting(route.cargo))
    if (waiting < capacity) return
    // What each train made in a work year: last year's, or this year's once it's a few months of work in.
    val inYear = Calendar.dayOfYear(startYear, day)
    val each = if (on.all { ranLastYear(it.built) }) on.sumOf { it.profitLastYear } / on.size
    else if (workedThisYear() >= FEW_MONTHS) on.sumOf { it.profitThisYear } * 365 / inYear / on.size else return
    val perWork = each / settings.dayLength
    if (perWork * minOf(payback, TRAIN_PAYBACK) < price(first.price)) return
    if (leadTile(c, route) < 0) return
    if (on.size <= route.loops.size) {
        if (inHand(c, price(first.price))) addTrain(c, route)
    } else addLoop(c, route)
}

/** Buys another train for [route] like the one on it, or the best of the day that fits if that's no longer sold. Whether it did. */
internal fun World.addTrain(c: Company, route: RivalRoute): Boolean {
    val first = trainsOn(c, route).firstOrNull() ?: return false
    val a = stations.getOrNull(route.a) ?: return false
    val b = stations.getOrNull(route.b) ?: return false
    val year = Calendar.year(startYear, day)
    val e = roster[first.engine]
    val w = roster[cargoWagon(first)]
    val n = first.formation.count { it.model == w.id }
    val train = if (year >= e.from && year < e.until && year >= w.from && year < w.until) RailTrain(e, w, n, railPlow())
    else railTrains(route.cargo, n * w.capacity, minOf(a.tiles.size, b.tiles.size)).maxByOrNull { it.wagon.capacity * it.wagons * it.engine.speed(false) } ?: return false
    val before = trains.size
    buyTrain(c, route, train)
    return trains.size > before
}

/**
 * Each day, for a railway with more than one train. A train out of the depot waits at the signal by it while another
 * is at the platforms; if that one is heading into the depot, neither can move, and the one out of the depot can't
 * back in with its tail still in there. So one train at a time is serviced ([serviceTurn]), and one sent in to be sold
 * or renewed while another waits at the signal has its trip called off and goes on to the far end first, its orders
 * turned round so that's next.
 */
internal fun World.unblockDepot(c: Company, route: RivalRoute) {
    if (blocksDirty) return
    val on = trainsOn(c, route)
    if (on.size < 2) {
        // Down to one train, it's serviced as any is.
        for (t in on) if (!t.service) apply(Command.TrainOrder(c.id, t.id, Command.TrainOrder.SERVICE))
        return
    }
    // One held in the depot on its way to the far end calls at the near end first, so it comes out as soon as the
    // platform is clear.
    for (t in on) {
        if (!t.inDepot || !t.running || tick > t.lostUntil || t.orders.size != 2) continue
        val next = t.orders[t.order % 2]
        val other = t.orders[(t.order + 1) % 2]
        if (Order.station(next) != route.a && Order.station(other) == route.a) apply(Command.TrainOrder(c.id, t.id, Command.TrainOrder.ORDERS, intArrayOf(other, next)))
    }
    val lead = leadTile(c, route)
    saleTurn(c, on, lead)
    serviceTurn(c, on, lead)
    if (lead < 0) return
    if (on.none { !it.inDepot && !it.toDepot && it.speed == 0 && stepTile(it.steps.last()) == lead }) return
    val platforms = stations.getOrNull(route.a)?.tiles?.firstOrNull() ?: return
    val block = blocks[pieceKey(platforms, Track.straight(depots.getOrNull(route.depot)?.edge ?: return))] ?: return
    for (t in on) {
        if (t.inDepot || !t.toDepot || block !in t.onBlocks || stepTile(t.steps.last()) == lead) continue
        val far = if (Order.station(t.orders[0]) == route.a) intArrayOf(t.orders[1], t.orders[0]) else t.orders.toIntArray()
        apply(Command.TrainOrder(c.id, t.id, Command.TrainOrder.ORDERS, far))
        // A train going in to be renewed has servicing off, so its trip is called off as one going to be sold.
        apply(Command.TrainOrder(c.id, t.id, if (t.servicing && t.service) Command.TrainOrder.SERVICE else Command.TrainOrder.TO_DEPOT))
    }
}

/**
 * Selling on a railway with more than one train, as [serviceTurn] services: one losing train ([lostAWorkYear]) at a
 * time is sent in to be sold, while it stands at a platform with no other train on its way to the depot, in it or on
 * the signal tile [lead]. One whose trip [unjam] called off waits till its noDepotUntil.
 */
private fun World.saleTurn(c: Company, on: List<Train>, lead: Int) {
    if (on.any { it.toDepot || it.inDepot && it.running || lead in it.onTiles }) return
    val t = on.firstOrNull {
        it.running && it.waiting > 0 && !it.inDepot && it.wreckedFor == 0 && tick >= it.noDepotUntil && lostAWorkYear(it.built, it.profitThisYear, it.profitLastYear)
    } ?: return
    apply(Command.TrainOrder(c.id, t.id, Command.TrainOrder.TO_DEPOT))
}

/**
 * Servicing on a railway with more than one train: only one has servicing on, and only while it stands at a platform
 * due a service, with no other train on its way to the depot, in it, or on the signal tile [lead] by it (-1 for a line
 * without one, where one at a time is still kept to). So it goes in
 * from the station when it leaves, finds the way into the depot clear, and comes out to wait at the signal as a new
 * train does. The one longest since its service goes first, the lowest id on a tie; one whose trip [unjam] called
 * off waits till its noDepotUntil. Servicing is off for the rest.
 */
private fun World.serviceTurn(c: Company, on: List<Train>, lead: Int) {
    val busy = on.any { it.toDepot || it.inDepot && it.running || lead in it.onTiles }
    val next = if (busy) null else on.filter { it.running && it.waiting > 0 && !it.inDepot && tick >= it.noDepotUntil && due(true, it.serviced) }
        .minWithOrNull(compareBy<Train> { it.serviced }.thenBy { it.id })
    for (t in on) {
        // One already on its way for a service keeps it.
        val keep = t === next || t.toDepot && t.servicing
        if (t.service != keep) apply(Command.TrainOrder(c.id, t.id, Command.TrainOrder.SERVICE))
    }
}

/** Lays a passing loop on [route]'s line, midway along the longest stretch without one. Whether it did. */
internal fun World.addLoop(c: Company, route: RivalRoute): Boolean {
    val through = railExtras(c, route)
    val line = lineTiles(c, route, through) ?: return false
    if (line.size / LOOP_EVERY <= route.loops.size || route.loops.size >= MOST_LOOPS) return false
    // The stretch between loops, or a loop and an end, with the most line in it.
    val marks = (listOf(0, line.size) + route.loops.map { line.indexOf(it / 16) }.filter { it >= 0 }).sorted()
    var aim = 0
    var widest = -1
    for (k in 1 until marks.size) if (marks[k] - marks[k - 1] > widest) { widest = marks[k] - marks[k - 1]; aim = (marks[k] + marks[k - 1]) / 2 }
    val starts = (1 until line.size - LOOP_LONG).sortedWith(compareBy<Int> { kotlin.math.abs(it + LOOP_LONG / 2 - aim) }.thenBy { it })
    for (i in starts) {
        val along = edgeTo(line[i], line[i + 1]) ?: continue
        val h = heightAt(line[i] % width, line[i] / width)
        var ok = true
        for (k in -1 until LOOP_LONG) {
            val t = line[i + k]
            if (edgeTo(t, line[i + k + 1]) != along) { ok = false; break }
            if (k < 0) continue
            if (t in through || track[t].toInt() != Track.straight(along) || owner[t].toInt() != c.id || signals.containsKey(pieceKey(t, Track.straight(along))) ||
                stationTiles.containsKey(t) || depotTiles.containsKey(t) || road[t].toInt() != 0 || !isFlat(t % width, t / width) || heightAt(t % width, t / width) != h
            ) { ok = false; break }
        }
        if (!ok) continue
        for (side in if (along == Track.XM || along == Track.XP) intArrayOf(Track.YM, Track.YP) else intArrayOf(Track.XM, Track.XP)) {
            val loop = packLoop(line[i], along, side)
            val tiles = loopTiles(loop)
            val beside = (LOOP_LONG until LOOP_LONG * 2).map { tiles[it] }
            if (beside.any { t -> !inside(t % width, t / width) || !railFree(t) || !isFlat(t % width, t / width) || heightAt(t % width, t / width) != h }) continue
            if (beside.any { t -> chebyshev(t % width, t / width, line[i] % width, line[i] / width) > LOOP_LONG }) continue
            val cost = price(Prices.TRACK) * (LOOP_LONG + 2) + price(Prices.SIGNAL) * 4 + beside.sumOf { treeCost(it) }
            if (!inHand(c, cost)) return false
            return layLoop(c, route, loop)
        }
    }
    return false
}

/** The edge of tile [a] that leads to [b] next door, or null if they're not side by side. */
private fun World.edgeTo(a: Int, b: Int): Int? {
    val dx = b % width - a % width
    val dy = b / width - a / width
    return when {
        dx == 1 && dy == 0 -> Track.XP
        dx == -1 && dy == 0 -> Track.XM
        dx == 0 && dy == 1 -> Track.YP
        dx == 0 && dy == -1 -> Track.YM
        else -> null
    }
}

/** Lays [loop] beside [route]'s line and its two signals; taken up again if any of it won't go. */
private fun World.layLoop(c: Company, route: RivalRoute, loop: Int): Boolean {
    val tiles = loopTiles(loop)
    val f = (loop / 4) % 4
    val s = loop % 4
    val back = Track.opposite(f)
    val inner = Track.opposite(s)
    val straight = Track.straight(f)
    fun xy(i: Int) = intArrayOf(i % width, i / width)
    fun lay(i: Int, piece: Int) = xy(i).let { apply(Command.Rail(c.id, it[0], it[1], it[0], it[1], piece, remove = false)) }
    val p0 = tiles[0]
    val pEnd = tiles[LOOP_LONG - 1]
    val s0 = tiles[LOOP_LONG]
    val sEnd = tiles[LOOP_LONG * 2 - 1]
    val planned = listOf(
        p0 to Track.pieceOf(back, s), s0 to Track.pieceOf(inner, f), sEnd to Track.pieceOf(back, inner), pEnd to Track.pieceOf(s, f),
    ) + (1 until LOOP_LONG - 1).map { tiles[LOOP_LONG + it] to straight }
    for ((i, piece) in planned) lay(i, piece)
    // A one-way path signal at the way out of each side: out from the first station along the line, back along the
    // loop. With none at the ways in, a train leaving holds the line ahead and its side of the next loop, or the
    // platforms, so two trains never meet head on between loops.
    val placed = listOf(intArrayOf(tiles[LOOP_LONG - 2], straight, back), intArrayOf(s0, Track.pieceOf(inner, f), f))
    if (planned.all { (i, piece) -> track[i].toInt() and piece != 0 }) {
        for (p in placed) xy(p[0]).let { repeat(2) { _ -> apply(Command.Signal(c.id, it[0], it[1], p[1], p[2], Signals.PATH)) } }
    }
    val done = planned.all { (i, piece) -> track[i].toInt() and piece != 0 } &&
        placed.all { p -> signals[pieceKey(p[0], p[1])]?.let { Signals.kind(it) == Signals.ONE_WAY_PATH && Signals.facing(it) == p[2] } == true }
    if (done) {
        route.loops += loop
        return true
    }
    // Taken up again: any signals, the loop's own tiles and the joins' extra pieces.
    for (p in placed) repeat(3) { if (pieceKey(p[0], p[1]) in signals) xy(p[0]).let { apply(Command.Signal(c.id, it[0], it[1], p[1], p[2])) } }
    for (k in LOOP_LONG until LOOP_LONG * 2) if (owner[tiles[k]].toInt() == c.id) clearTile(c, Command.Clear(c.id, tiles[k] % width, tiles[k] / width))
    for ((i, piece) in planned.take(4).filter { it.first == p0 || it.first == pEnd }) {
        if (track[i].toInt() and piece != 0) xy(i).let { apply(Command.Rail(c.id, it[0], it[1], it[0], it[1], piece, remove = true)) }
    }
    return false
}

/** Work years a train is chosen to pay back over when any line will do. */
private const val ANY_PAYBACK = 10
/** Platforms at a terminus, enough for an engine and a few cars, and at one pulling down town buildings to get nearer the middle. */
private const val PLATFORMS = 5
private const val TOWN_PLATFORMS = 4
/** How far from a town's middle its station goes. */
private const val TOWN_REACH = 6
/** How much further out a terminus may go in a town with no room for one within [TOWN_REACH]. */
private const val WIDER_REACH = 3
/** People in reach worth a tile further from where the line heads, choosing a passenger terminus. */
private const val PEOPLE_A_TILE = 3
/** Tiles further a terminus may be for each town building it doesn't pull down. */
private const val HOUSE_TILES = 4
/** Most of one town's buildings a line pulls down, and approval kept above where the town refuses. */
private const val MOST_CLEARED = 4
private const val CLEAR_MARGIN = 100
/** Places tried for each terminus. */
private const val SITE_TRIES = 4
/** Tiles apart two places tried for a terminus facing the same way are at least. */
private const val SPOT_APART = 3
/** Path costs: a tile of new track, more on a slope or a foundation, a level crossing, a curve, a town building. */
private const val RAIL_MOVE = 8
private const val RAIL_SLOPE = 4
private const val RAIL_FOUNDATION = 6
private const val RAIL_CROSSING = 6
private const val RAIL_TURN = 4
/** A tight bend, which holds trains to [CURVE_TIGHT_KMH]: worth going three tiles further round. */
private const val RAIL_TIGHT = 24
/** What the tile before did, in the path search: straight, a lone diagonal or one in a run, turning either way. */
private const val BEND_STRAIGHT = 0
private const val BEND_LONE_LEFT = 1
private const val BEND_LONE_RIGHT = 2
private const val BEND_RUN_LEFT = 3
private const val BEND_RUN_RIGHT = 4
private const val RAIL_HOUSE = 40
/** A bridge tile costs the search this many times a tile of track on the ground. */
private const val RAIL_BRIDGE = 4
private const val RAIL_PATH_LIMIT = 120_000
/** Tiles past the map's edges a saved rail site may reach. */
private const val SITE_PAST = 64
/** What looking at how many people a place for a terminus would reach counts as against [RailJob.left], in search steps. */
private const val CATCH_STATES = 8
/** Searches for one line that run out of steps before a computer company gives up on it. */
private const val RAN_OUT_TRIES = 2
/** Maps up to this many tiles keep the path search's states in plain arrays. */
private const val FLAT_TILES = 256 * 256
/** For the scripted test player: the most steps searched, and a load big enough to fill the platforms. */
private const val LOOSE_PATH_LIMIT = 600_000
private const val LOOSE_LOAD = 10_000
/** Km/h for a train to go a tile a work day. */
internal const val KMH_PER_TILE_A_DAY = 42
/** Most work years another train on a line may take to pay for itself. */
private const val TRAIN_PAYBACK = 10

/** The step costs of the rail path searches of one pick, with and without pulling down buildings. */
internal class RailSteps(tiles: Int) {
    private val flat = tiles <= FLAT_TILES
    val steps = IntMap(if (flat) tiles * 16 else 0)
    val plain = IntMap(if (flat) tiles * 16 else 0)

    fun clear() {
        steps.clear()
        plain.clear()
    }
}

/**
 * Each path search state's cost so far and the state it came from, [IntMap.NONE] for one not reached. With [flat], states
 * below it sit in one array, a stamp, the cost and where from side by side; otherwise in maps.
 */
internal class SearchStates(flat: Int) {
    private val a = if (flat > 0) IntArray(flat * 3) else null
    private var gen = 1
    private val costs = if (flat > 0) null else IntMap()
    private val froms = if (flat > 0) null else IntMap()
    /** The states reached, in the order first reached, for a save. */
    var touched = IntArray(1 shl 10)
        private set
    var count = 0
        private set

    fun cost(s: Int): Int = if (a != null) (if (a[s * 3] == gen) a[s * 3 + 1] else IntMap.NONE) else costs!![s]

    fun from(s: Int): Int = if (a != null) (if (a[s * 3] == gen) a[s * 3 + 2] else IntMap.NONE) else froms!![s]

    fun put(s: Int, cost: Int, from: Int) {
        if (cost(s) == IntMap.NONE) {
            if (count == touched.size) touched = touched.copyOf(count * 2)
            touched[count++] = s
        }
        if (a != null) {
            a[s * 3] = gen
            a[s * 3 + 1] = cost
            a[s * 3 + 2] = from
        } else {
            costs!![s] = cost
            froms!![s] = from
        }
    }

    fun clear() {
        gen++
        count = 0
        costs?.clear()
        froms?.clear()
    }
}

/**
 * A map from keys of 0 and up to ints, [NONE] for a key not in it. Open addressing, cleared in place, or with [flatSize]
 * a plain array for keys below it.
 */
internal class IntMap(flatSize: Int = 0) {
    private val flat = flatSize > 0
    private var keys = IntArray(if (flat) 0 else 1 shl 12) { -1 }
    private var values = if (flat) IntArray(flatSize) { NONE } else IntArray(1 shl 12)
    private var size = 0
    private var mask = keys.size - 1
    /** Flat, the keys set since the last [clear], so a few are put back without going over them all. */
    private var set = IntArray(if (flat) 1 shl 10 else 0)

    operator fun get(key: Int): Int {
        if (flat) return values[key]
        var i = mix(key) and mask
        while (true) {
            val k = keys[i]
            if (k == key) return values[i]
            if (k < 0) return NONE
            i = (i + 1) and mask
        }
    }

    operator fun set(key: Int, value: Int) {
        if (flat) {
            if (values[key] == NONE && size < values.size / 8) {
                if (size == set.size) set = set.copyOf(size * 2)
                set[size] = key
            }
            if (values[key] == NONE) size++
            values[key] = value
            return
        }
        var i = mix(key) and mask
        while (true) {
            val k = keys[i]
            if (k == key) { values[i] = value; return }
            if (k < 0) break
            i = (i + 1) and mask
        }
        keys[i] = key
        values[i] = value
        if (++size * 2 > keys.size) grow()
    }

    fun clear() {
        if (flat) {
            if (size == 0) return
            if (size <= values.size / 8) for (k in 0 until size) values[set[k]] = NONE else values.fill(NONE)
            size = 0
            return
        }
        if (size == 0) return
        keys.fill(-1)
        size = 0
    }

    /** Each key and its value, keys in order. */
    fun entries(): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        if (flat) {
            if (size > 0) for (k in values.indices) if (values[k] != NONE) out += k to values[k]
            return out
        }
        for (j in keys.indices) if (keys[j] >= 0) out += keys[j] to values[j]
        out.sortBy { it.first }
        return out
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        keys = IntArray(oldKeys.size * 2) { -1 }
        values = IntArray(oldKeys.size * 2)
        mask = keys.size - 1
        size = 0
        for (j in oldKeys.indices) if (oldKeys[j] >= 0) set(oldKeys[j], oldValues[j])
    }

    private fun mix(key: Int) = (key * -0x61c88647) xor (key ushr 16)

    companion object {
        const val NONE = Int.MIN_VALUE
    }
}

/** Writes [j] for a save, on a map of [tiles]. */
internal fun writeRailJob(w: ByteWriter, j: RailJob, tiles: Int) {
    fun site(s: RailSite) { w.int(s.x0); w.int(s.y0); w.int(s.x1); w.int(s.y1); w.int(s.cx); w.int(s.cy); w.int(s.town) }
    fun termini(list: List<Terminus>) {
        w.uvar(list.size.toLong())
        for (t in list) { w.int(t.x); w.int(t.y); w.int(t.out); w.int(t.n); w.int(t.lead) }
    }
    w.uvar(j.picks.size.toLong())
    for (p in j.picks) { w.int(p.from); w.int(p.to); w.int(p.cargo); w.int(p.load); site(p.sa); site(p.sb) }
    w.int(j.payback); w.bool(j.loose); w.int(j.running); w.int(j.from); w.int(j.opensLeft); w.int(j.pick)
    for (list in listOf(j.aList, j.bList)) {
        w.bool(list != null)
        if (list != null) termini(list)
    }
    w.int(j.ia); w.int(j.ib); w.int(j.ranOut); w.int(j.phase)
    val floods = j.floods.keys.sorted()
    w.uvar(floods.size.toLong())
    for (k in floods) {
        val f = j.floods[k]
        w.int(k); w.bool(f != null)
        if (f != null) w.bytes(f)
    }
    // The step costs worked out so far, keys as the gap from the last.
    w.bool(j.hasSteps)
    if (j.hasSteps) for (m in j.steps(tiles).let { listOf(it.steps, it.plain) }) {
        val e = m.entries()
        w.uvar(e.size.toLong())
        var last = 0
        for ((k, v) in e) { w.uvar((k - last).toLong()); w.int(v); last = k }
    }
    val f = j.reach
    w.bool(f != null)
    if (f != null) {
        w.int(f.key); w.bytes(f.seen); w.int(f.head); w.int(f.tail)
        for (k in f.head until f.tail) w.int(f.queue[k])
    }
    val s = j.search
    w.bool(s != null)
    if (s == null) return
    w.bool(s.clearing); w.int(s.limit); w.int(s.expanded); w.bool(s.done)
    // The heap as laid out, each value its cost and state.
    val heap = s.heap.values()
    w.uvar(heap.size.toLong())
    for (v in heap) { w.int((v ushr 32).toInt()); w.int(v.toInt()) }
    val states = j.states(tiles)
    w.uvar(states.count.toLong())
    for (k in 0 until states.count) {
        val st = states.touched[k]
        w.int(st); w.int(states.cost(st)); w.int(states.from(st))
    }
}

/** Reads a job [writeRailJob] wrote. */
internal fun World.readRailJob(r: ByteReader): RailJob {
    val tiles = width * height
    fun count(most: Int, what: String) = r.uvar().also { if (it > most) throw BadData("too many $what") }.toInt()
    // A site may run a little past the map's edges; its middle and far end are on it.
    fun site() = RailSite(r.int(), r.int(), r.int(), r.int(), r.int(), r.int(), r.int()).also {
        if (it.x0 > it.x1 || it.y0 > it.y1 || it.x0 < -SITE_PAST || it.y0 < -SITE_PAST || it.x1 >= width + SITE_PAST || it.y1 >= height + SITE_PAST ||
            !inside(it.cx, it.cy) || it.town !in -1 until towns.size) throw BadData("bad rail site")
    }
    fun termini() = List(count(10_000, "rail termini")) {
        Terminus(r.int(), r.int(), r.int(), r.int(), r.int()).also { t ->
            if (t.out !in 0..3 || t.n !in 1..64 || t.lead !in 0..1 || !inside(t.x, t.y) || !inside(t.x + Track.dx(t.out) * t.last, t.y + Track.dy(t.out) * t.last)) throw BadData("bad terminus")
        }
    }
    val picks = List(count(10_000, "rail picks")) {
        RailPick(r.int(), r.int(), r.int(), r.int(), site(), site()).also { if (it.cargo !in 0 until Cargo.COUNT) throw BadData("bad rail cargo") }
    }
    val j = RailJob(picks, r.int(), r.bool())
    j.running = r.int(); j.from = r.int(); j.opensLeft = r.int(); j.pick = r.int()
    if (j.pick !in picks.indices) throw BadData("bad rail pick")
    if (r.bool()) j.aList = termini()
    if (r.bool()) j.bList = termini()
    if (j.aList == null && j.bList != null) throw BadData("bad rail termini")
    j.ia = r.int(); j.ib = r.int(); j.ranOut = r.int(); j.phase = r.int()
    if (j.ia < 0 || j.ib < 0 || j.phase !in 0..2) throw BadData("bad rail job")
    repeat(count(100_000, "rail floods")) {
        val k = r.int()
        j.floods[k] = if (r.bool()) r.bytes().also { if (it.size != (tiles + 7) / 8) throw BadData("bad rail flood") } else null
    }
    if (r.bool()) for (m in j.steps(tiles).let { listOf(it.steps, it.plain) }) {
        var key = 0
        repeat(count(tiles * 16, "rail step costs")) {
            key += r.uvar().toInt()
            if (key !in 0 until tiles * 16) throw BadData("bad rail step cost")
            m[key] = r.int()
        }
    }
    if (r.bool()) {
        val key = r.int()
        val seen = r.bytes()
        val head = r.int()
        val tail = r.int()
        if (seen.size != (tiles + 7) / 8 || head < 0 || tail < head || tail > tiles || j.bList == null || j.phase == 0) throw BadData("bad rail flood")
        val queue = IntArray(maxOf(tail, 1))
        for (k in head until tail) queue[k] = r.int().also { if (it !in 0 until tiles) throw BadData("bad rail flood") }
        j.reach = RailReach(key, seen, queue, head, tail)
    }
    if (!r.bool()) return j
    val s = RailSearch(r.bool(), r.int())
    s.expanded = r.int()
    s.done = r.bool()
    val most = tiles * 20
    s.heap.restore(LongArray(count(most * 4, "rail search steps")) {
        val f = r.int()
        val st = r.int()
        if (st !in 0 until most) throw BadData("bad rail search state")
        f.toLong() shl 32 or st.toLong()
    })
    val states = j.states(tiles).apply { clear() }
    repeat(count(most, "rail search states")) {
        val st = r.int()
        val cost = r.int()
        val from = r.int()
        if (st !in 0 until most || cost < 0 || from != IntMap.NONE && from !in 0 until most) throw BadData("bad rail search state")
        states.put(st, cost, from)
    }
    if (j.bList == null || j.phase == 0) throw BadData("bad rail search")
    j.search = s
    return j
}
