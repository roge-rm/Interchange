package com.rm.interchange.sim

/*
 * What a company can pay a town's authority for, as in OpenTTD: advertising
 * that lifts its stations' ratings round the town for a while, road works
 * that hold everyone's road vehicles to a crawl there for six months, a
 * statue that pleases the town for good, new building that makes it grow
 * fast for three months, and a year of exclusive transport rights, when no
 * other company's stations near it get anything.
 */

object TownActions {
    const val AD_SMALL = 0
    const val AD_MEDIUM = 1
    const val AD_LARGE = 2
    const val ROAD_WORKS = 3
    const val STATUE = 4
    const val FUND = 5
    const val EXCLUSIVE = 6
    const val COUNT = 7

    /** What each costs in [Prices.ERA_BASE] before inflation, in dollars; other years scale it by [Prices.era]. */
    val COST = longArrayOf(3_000, 8_000, 15_000, 20_000, 40_000, 60_000, 100_000)

    /** How far from the town's middle each size of advertising reaches, in tiles, and what it adds to ratings there, out of 255. */
    val AD_REACH = intArrayOf(10, 15, 20)
    val AD_LIFT = intArrayOf(30, 60, 100)

    /** Road works and exclusive rights last work days, as what they hold up goes by work days; funding's growth goes by the calendar. */
    const val ROAD_WORKS_DAYS = 180
    const val FUND_DAYS = 90
    const val EXCLUSIVE_DAYS = 365
}

/** Road vehicles' top speed on a town's streets during its road works, in km/h. */
const val ROAD_WORKS_KMH = 15

/** Days between a funded town's growth spurts. */
const val FUNDED_GROW_DAYS = 2

/** What a statue does for a company's standing with its town when it goes up, and for its stations' ratings near it from then on. */
const val STATUE_APPROVAL = 200
const val STATUE_RATING = 10

/** How far from a town's middle its statue can stand, in tiles. */
private const val STATUE_REACH = 6

/** What [action] costs now: dearer in later eras, as a year's takings are, and with inflation. */
fun World.townActionCost(action: Int): Long =
    price(TownActions.COST[action] * Prices.era(Calendar.year(startYear, day)) / Prices.era(Prices.ERA_BASE)) / 100 * 100

/** Whether [company] can ask [t] for [action] now, money aside: one statue each, and one of the lasting things at a time. */
fun World.townActionOpen(company: Int, t: Town, action: Int): Boolean = when (action) {
    TownActions.STATUE -> t.statue[company] < 0 && statueSpot(t) >= 0
    TownActions.ROAD_WORKS -> t.roadWorks == 0
    TownActions.FUND -> t.funded == 0
    TownActions.EXCLUSIVE -> t.exclusive < 0
    in 0 until TownActions.COUNT -> true
    else -> false
}

internal fun World.townAction(company: Company, c: Command.TownAction) {
    val t = towns.getOrNull(c.town) ?: return
    if (!townActionOpen(company.id, t, c.action)) return
    val cost = townActionCost(c.action)
    if (company.money < cost) return
    when (c.action) {
        TownActions.AD_SMALL, TownActions.AD_MEDIUM, TownActions.AD_LARGE -> {
            val reach = TownActions.AD_REACH[c.action]
            val lift = TownActions.AD_LIFT[c.action]
            for (s in stations) {
                if (s == null || s.owner != company.id || s.waypoint || chebyshev(s.signX, s.signY, t.x, t.y) > reach) continue
                for (cg in 0 until Cargo.COUNT) s.rating[cg] = minOf(255, s.rating[cg] + lift)
            }
        }
        TownActions.ROAD_WORKS -> t.roadWorks = TownActions.ROAD_WORKS_DAYS
        TownActions.STATUE -> {
            val i = statueSpot(t)
            val kind = roster.houses.firstOrNull { it.monument } ?: return
            if (i < 0) return
            val x = i % width
            val y = i / width
            // Facing the road beside it.
            val facing = (0 until 4).first { e -> inside(x + Track.dx(e), y + Track.dy(e)) && road[index(x + Track.dx(e), y + Track.dy(e))].toInt() != 0 }
            clearTrees(i)
            house[i] = packHouse(kind.id, facing).toShort()
            houseYear[i] = Calendar.year(startYear, day).toShort()
            townOf[i] = t.id.toShort()
            t.statue[company.id] = i
            t.approval[company.id] = minOf(1000, t.approval[company.id] + STATUE_APPROVAL)
            catchmentDirty = true
            townsChanged = true
            touched(x, y)
        }
        TownActions.FUND -> t.funded = TownActions.FUND_DAYS
        TownActions.EXCLUSIVE -> {
            t.exclusive = company.id
            t.exclusiveDays = TownActions.EXCLUSIVE_DAYS
        }
    }
    spend(company, Books.CONSTRUCTION, cost)
}

/** Where a statue could go in [t]: free, flat land beside a road, nearest the middle; -1 if there's nowhere. */
fun World.statueSpot(t: Town): Int {
    if (roster.houses.none { it.monument }) return -1
    var best = -1
    var bestD = Int.MAX_VALUE
    for (y in t.y - STATUE_REACH..t.y + STATUE_REACH) for (x in t.x - STATUE_REACH..t.x + STATUE_REACH) {
        if (!inside(x, y) || !isFlat(x, y) || isWater(x, y)) continue
        val i = index(x, y)
        if (townBuilt(i) || track[i].toInt() != 0 || owner[i].toInt() != -1 || stationTiles.containsKey(i) || depotTiles.containsKey(i) ||
            canals.contains(i) || airportTiles.containsKey(i) || saltDepots.contains(i) || spanHeads.containsKey(i) || deck.containsKey(i)) continue
        if ((0 until 4).none { e -> inside(x + Track.dx(e), y + Track.dy(e)) && road[index(x + Track.dx(e), y + Track.dy(e))].toInt() != 0 }) continue
        val d = chebyshev(x, y, t.x, t.y)
        if (d < bestD) { best = i; bestD = d }
    }
    return best
}

/** Each calendar day: the lasting things [t] was paid for run down, and road works done leave its streets as new. */
internal fun World.townActionsDay(t: Town) {
    val work = settings.dayLength
    if (t.funded > 0) t.funded--
    if (t.exclusiveDays > 0) {
        t.exclusiveDays = (t.exclusiveDays - work).coerceAtLeast(0)
        if (t.exclusiveDays == 0) t.exclusive = -1
    }
    if (t.roadWorks > 0) {
        t.roadWorks = (t.roadWorks - work).coerceAtLeast(0)
        if (t.roadWorks > 0) return
        for (i in 0 until width * height) if (townOf[i].toInt() == t.id && road[i].toInt() != 0 && track[i].toInt() == 0) condition[i] = -1
        townsChanged = true
    }
}

/** Whether road tile [i] is under road works. */
fun World.underRoadWorks(i: Int): Boolean = towns.getOrNull(townOf[i].toInt())?.let { it.roadWorks > 0 && road[i].toInt() != 0 } == true

/** The stations shut out by another company's exclusive rights in a town they're near, worked out once a day. */
internal fun World.shutOut(): Set<Int> {
    if (towns.none { it.exclusive >= 0 }) return emptySet()
    val out = HashSet<Int>()
    for (s in stations) {
        if (s == null) continue
        if (towns.any { it.exclusive >= 0 && it.exclusive != s.owner && chebyshev(s.signX, s.signY, it.x, it.y) <= TOWN_AREA }) out += s.id
    }
    return out
}

/** The stations with their company's statue in a town they're near, worked out once a day. */
internal fun World.nearStatues(): Set<Int> {
    if (towns.none { t -> t.statue.any { it >= 0 } }) return emptySet()
    val out = HashSet<Int>()
    for (s in stations) {
        if (s == null || s.owner !in 0 until MAX_COMPANIES) continue
        if (towns.any { it.statue[s.owner] >= 0 && chebyshev(s.signX, s.signY, it.x, it.y) <= TOWN_AREA }) out += s.id
    }
    return out
}
