package com.rm.interchange.sim

/*
 * The scenario editor. An editor world stands still: [World.step] does
 * nothing, building costs nothing, and the [Command.Edit] commands shape the
 * land, place towns and industries, plant trees and set the goals and the
 * scenario. They're refused in any other world.
 */

/** How big a town [Command.PlaceTown] makes, or how much [Command.GrowTown] adds. */
object TownSize {
    const val SMALL = 0
    const val MEDIUM = 1
    const val LARGE = 2
    const val CITY = 3

    /** About how many buildings each adds. */
    val HOUSES = intArrayOf(10, 30, 80, 160)
}

/** A new world to edit, made from [settings] as a new game would be, standing still with a blank scenario. */
fun World.Companion.newEditor(settings: NewGame, roster: Roster = Roster.BASE): World = World(settings, roster).also {
    it.editor = true
    it.scenario = Scenario(money = it.startMoney())
}

/** Runs an editor command. */
internal fun World.edit(c: Command.Edit) {
    when (c) {
        is Command.EditTerrain -> editTerrain(c)
        is Command.PlaceTown -> placeTown(c)
        is Command.GrowTown -> towns.getOrNull(c.town)?.let { growBy(it, c.size) }
        is Command.PlaceIndustry -> placeIndustryAt(c)
        is Command.RemoveIndustry -> removeIndustry(c.industry)
        is Command.EditTrees -> editTrees(c)
        is Command.SetGoals -> setGoals(c.goals)
        is Command.SetScenario -> setScenario(c)
    }
}

/**
 * Runs [run] for [company] as if money were no object, then puts its money, books and what towns think of it back
 * as they were, so building in the editor costs nothing.
 */
internal fun World.free(company: Company, run: () -> Unit) {
    val money = company.money
    val books = company.thisYear.copyOf()
    val monthIn = company.monthIn
    val monthOut = company.monthOut
    val approval = IntArray(towns.size) { towns[it].approval[company.id] }
    company.money = Long.MAX_VALUE / 4
    try {
        run()
    } finally {
        company.money = money
        books.copyInto(company.thisYear)
        company.monthIn = monthIn
        company.monthOut = monthOut
        for (k in approval.indices) towns[k].approval[company.id] = approval[k]
    }
}

/** The tiles from ([x0], [y0]) to ([x1], [y1]) either way round, cut to the map; null if none of it is on the map or it's too big. */
private fun World.area(x0: Int, y0: Int, x1: Int, y1: Int): IntArray? {
    val ax = maxOf(0, minOf(x0, x1))
    val ay = maxOf(0, minOf(y0, y1))
    val bx = minOf(width - 1, maxOf(x0, x1))
    val by = minOf(height - 1, maxOf(y0, y1))
    if (ax > bx || ay > by || bx - ax >= EDIT_MOST || by - ay >= EDIT_MOST) return null
    return intArrayOf(ax, ay, bx, by)
}

/** Whether anything stands on tile [i] that the land can't move under: track, roads, buildings, industries, anything a company owns. */
private fun World.built(i: Int): Boolean =
    track[i].toInt() != 0 || townBuilt(i) || owner[i].toInt() != -1 || roadOwner[i].toInt() != -1 ||
        deck.containsKey(i) || spanHeads.containsKey(i) || i in canals

/** The tiles a ship's on, out of its shipyard, and those round them, as a ship can be partway onto the next. */
private fun World.shipsNear(): Set<Int> {
    val out = HashSet<Int>()
    for (s in ships) {
        if (s == null || s.inDepot) continue
        val i = shipTile(s)
        val x = i % width
        val y = i / width
        for (dy in -1..1) for (dx in -1..1) if (inside(x + dx, y + dy)) out += index(x + dx, y + dy)
    }
    return out
}

/**
 * Sets the area's corners as [c] asks, then moves the corners round them as far as they have to so none is more
 * than a step from its neighbours. Nothing moves if any tile that would change has something built on it.
 */
private fun World.editTerrain(c: Command.EditTerrain) {
    val (ax, ay, bx, by) = area(c.x0, c.y0, c.x1, c.y1) ?: return
    if (c.how !in Command.EditTerrain.RAISE..Command.EditTerrain.LEVEL) return
    val changed = LinkedHashMap<Int, Int>()
    val fixed = HashSet<Int>()
    val queue = ArrayDeque<Int>()
    for (cy in ay..by + 1) for (cx in ax..bx + 1) {
        val h = heightAt(cx, cy)
        val want = when (c.how) {
            Command.EditTerrain.RAISE -> h + 1
            Command.EditTerrain.LOWER -> h - 1
            else -> c.height
        }.coerceIn(0, World.MAX_HEIGHT)
        val k = corner(cx, cy)
        fixed += k
        if (want != h) changed[k] = want
        queue.addLast(k)
    }
    // Each corner pulled to within a step of the one that changed beside it, spreading out until nothing more moves.
    var work = 0
    while (queue.isNotEmpty()) {
        if (++work > TERRAIN_WORK_MOST) return
        val k = queue.removeFirst()
        val x = k % (width + 1)
        val y = k / (width + 1)
        val h = changed[k] ?: heightAt(x, y)
        for (dy in -1..1) for (dx in -1..1) {
            val nx = x + dx
            val ny = y + dy
            if ((dx == 0 && dy == 0) || !cornerInside(nx, ny)) continue
            val n = corner(nx, ny)
            if (n in fixed) continue
            val nh = changed[n] ?: heightAt(nx, ny)
            val to = nh.coerceIn(h - 1, h + 1)
            if (to != nh) {
                changed[n] = to
                queue.addLast(n)
            }
        }
    }
    changed.entries.removeAll { (k, h) -> h == heightAt(k % (width + 1), k / (width + 1)) }
    if (changed.isEmpty()) return
    val boats = shipsNear()
    for (k in changed.keys) {
        val x = k % (width + 1)
        val y = k / (width + 1)
        for (ty in y - 1..y) for (tx in x - 1..x) if (inside(tx, ty) && (built(index(tx, ty)) || index(tx, ty) in boats)) return
    }
    var shore = false
    for ((k, h) in changed) {
        val x = k % (width + 1)
        val y = k / (width + 1)
        if (h <= 1 || heightAt(x, y) <= 1) shore = true
        corners[k] = h.toByte()
        for (ty in y - 1..y) for (tx in x - 1..x) if (inside(tx, ty)) {
            if (isWater(tx, ty)) clearTrees(index(tx, ty))
            touched(tx, ty)
        }
    }
    if (shore) shoreChanged()
}

/** A new town where [c] asks, if the land's free and flat there and no other town is close. */
private fun World.placeTown(c: Command.PlaceTown) {
    val x = c.x
    val y = c.y
    if (c.size !in TownSize.HOUSES.indices) return
    if (x !in TOWN_EDGE until width - TOWN_EDGE || y !in TOWN_EDGE until height - TOWN_EDGE || towns.size >= TOWNS_MOST) return
    val i = index(x, y)
    if (isWater(x, y) || !isFlat(x, y) || built(i)) return
    if (towns.any { chebyshev(it.x, it.y, x, y) < EDITOR_TOWN_SPACING }) return
    val taken = towns.mapTo(HashSet()) { it.name }
    val given = c.name.trim().take(TOWN_NAME_MOST)
    val (name, french) = if (given.isNotEmpty() && given !in taken) given to false else TownNames.make(rng, settings.region, taken)
    val t = Town(towns.size, name, x, y, if (settings.region == Region.PRAIRIES) 4 else 3, french)
    var roads = 0
    for (edge in 0 until 4) if (extendRoad(t, x, y, edge, Calendar.year(startYear, day))) roads++
    if (roads == 0) return
    towns += t
    t.growIn = 1 + rng.below(30)
    growBy(t, c.size)
}

/** Grows [t] by about [TownSize.HOUSES] buildings for [size]. */
private fun World.growBy(t: Town, size: Int) {
    val n = TownSize.HOUSES.getOrNull(size) ?: return
    val year = Calendar.year(startYear, day)
    val target = t.houses + n
    var attempts = n * 8 + 40
    while (t.houses < target && attempts-- > 0) growTown(t, year)
    townsChanged = true
    townChanged()
}

/** After a town's placed or grown: what stations reach and which town each serves are worked out again. */
private fun World.townChanged() {
    catchmentDirty = true
    linksDirty = true
    roadNetDirty = true
    stationTowns.clear()
}

/** An industry where [c] asks, if it fits there as one on a new map would. */
private fun World.placeIndustryAt(c: Command.PlaceIndustry) {
    val kind = roster.industries.firstOrNull { it.key == c.kind } ?: return
    if (c.turn !in 0..3 || industries.size >= INDUSTRIES_MOST) return
    if (!industryFits(kind, c.x, c.y, kind.across(c.turn), kind.along(c.turn), c.turn)) return
    buildIndustry(kind, c.x, c.y, c.turn)
    linksDirty = true
}

/** Whether an industry of the kind with key [kind] fits with its top corner at ([x], [y]) turned by [turn], for showing it before it's placed. */
fun World.industryFitsAt(kind: String, x: Int, y: Int, turn: Int): Boolean {
    val k = roster.industries.firstOrNull { it.key == kind } ?: return false
    return turn in 0..3 && industryFits(k, x, y, k.across(turn), k.along(turn), turn)
}

/** Takes an industry off the map, kept closed in the list so ids stay put, and drops the goals about it. */
private fun World.removeIndustry(id: Int) {
    val ind = industries.getOrNull(id) ?: return
    if (ind.closed) return
    ind.closed = true
    val kind = roster.industries[ind.kind]
    for (ty in ind.y until ind.y + kind.along(ind.turn)) for (tx in ind.x until ind.x + kind.across(ind.turn)) {
        industryOf[index(tx, ty)] = 0
        touched(tx, ty)
    }
    catchmentDirty = true
    linksDirty = true
    goals.removeAll { it.industry == id }
}

/** Plants or clears trees over an area: planted on open dry land, the kinds its ground grows, none on snow or the beach. */
private fun World.editTrees(c: Command.EditTrees) {
    val (ax, ay, bx, by) = area(c.x0, c.y0, c.x1, c.y1) ?: return
    for (y in ay..by) for (x in ax..bx) {
        val i = index(x, y)
        if (!c.plant) { clearTrees(i); continue }
        if (isWater(x, y) || built(i) || industryOf[i].toInt() != 0 || stationTiles.containsKey(i) || depotTiles.containsKey(i)) continue
        val low = minOf(minOf(heightAt(x, y), heightAt(x + 1, y)), minOf(heightAt(x + 1, y + 1), heightAt(x, y + 1)))
        if (low == 0) continue
        val pick = rng.below(100)
        val packed = when (ground[i].toInt()) {
            Ground.FOREST -> Trees.pack(if (pick < 45) Trees.SPRUCE else if (pick < 70) Trees.FIR else if (pick < 85) Trees.PINE else if (pick < 95) Trees.BIRCH else Trees.ASPEN, 3)
            Ground.MEADOW -> Trees.pack(if (pick < 50) Trees.MAPLE else if (pick < 80) Trees.BIRCH else Trees.ASPEN, 2)
            Ground.PRAIRIE -> Trees.pack(Trees.ASPEN, 2)
            Ground.ROCK -> Trees.pack(Trees.PINE, 1)
            Ground.TUNDRA -> Trees.pack(Trees.SPRUCE, 1)
            else -> continue
        }
        trees[i] = packed.toByte()
        touched(x, y)
    }
}

/** Sets the goals to [list], each open and counted from nothing; the whole list is refused if any goal in it makes no sense. */
private fun World.setGoals(list: List<Goal>) {
    if (list.size > Codec.MOST_GOALS) return
    for (g in list) {
        if (g.kind !in 0 until Goal.KINDS || g.amount <= 0 || g.deadline !in NewGame.FIRST_YEAR..NewGame.LAST_YEAR + 100) return
        if (g.cargo !in -1 until Cargo.COUNT || g.town !in -1 until towns.size || g.town2 !in -1 until towns.size) return
        if (g.industry !in -1 until industries.size) return
    }
    goals.clear()
    for (g in list) goals += Goal(g.kind, g.amount, g.deadline, g.cargo, g.town, g.town2, g.industry, g.from)
}

/** Sets the scenario's title, intro, money, win rule and chapter, and the year it starts while it's still its first day. */
private fun World.setScenario(c: Command.SetScenario) {
    if (c.win != Scenario.ALL && c.win != Scenario.ANY) return
    val s = scenario ?: Scenario().also { scenario = it }
    s.title = c.title.trim().take(Scenario.TITLE_MOST)
    s.intro = c.intro.trim().take(Scenario.INTRO_MOST)
    s.money = c.money.coerceIn(0, MONEY_MOST)
    s.win = c.win
    s.chapter = c.chapter.trim().take(Scenario.CHAPTER_MOST)
    val year = c.startYear.coerceIn(NewGame.FIRST_YEAR, NewGame.LAST_YEAR)
    val by = year - settings.startYear
    val moves = tick == 0L && by != 0
    if (moves) startYear(year)
    // The loan limit goes by the era, so the year comes first.
    s.loan = c.loan.coerceIn(0, maxLoan()) / Prices.LOAN_STEP * Prices.LOAN_STEP
    if (!moves) return
    // The goals move with the start, keeping their years from it.
    val moved = goals.map { g -> Goal(g.kind, g.amount, g.deadline + by, g.cargo, g.town, g.town2, g.industry, if (g.from == 0) 0 else g.from + by) }
    goals.clear()
    goals += moved
}

/** The most tiles a side an area command reaches over. */
private const val EDIT_MOST = 128

/** The most corners land shaping looks at, so a hostile command can't run on forever. */
private const val TERRAIN_WORK_MOST = 2_000_000

/** Towns placed in the editor keep at least this many tiles from each other and from the map's edge. */
private const val EDITOR_TOWN_SPACING = 8
private const val TOWN_EDGE = 4

/** The most towns and industries a map can have, as their ids are kept in shorts. */
private const val TOWNS_MOST = 30_000
private const val INDUSTRIES_MOST = 30_000

private const val TOWN_NAME_MOST = 40

/** The most money a scenario can start each company with. */
private const val MONEY_MOST = 1_000_000_000_000L
