package com.rm.interchange.sim

/*
 * Goals a game is given: a town to grow, cargo to deliver, two towns to
 * connect, a company's worth or a year's profit to reach, each by the end of
 * its deadline year. They're checked at each month's end and met by any
 * player's company, never the computer's; one still open when its deadline
 * year ends has failed.
 *
 * Also here: what a town needs to grow when towns only grow supplied, and the
 * town each station delivers to.
 */

/**
 * A goal of [kind], to reach [amount] by the end of year [deadline]. [town] is the town it's about, for a connection
 * the first of two and [town2] the other; [industry] the industry a delivery goes to, with [town] -1; [cargo] what's
 * delivered; [from] the year deliveries start counting.
 */
class Goal(
    val kind: Int,
    val amount: Long,
    val deadline: Int,
    val cargo: Int = -1,
    val town: Int = -1,
    val town2: Int = -1,
    val industry: Int = -1,
    val from: Int = 0,
) {
    /**
     * By company id: for a delivery what each has delivered since [from], for a connection the passengers each has
     * carried this year, for a town what each has delivered there.
     */
    val count = LongArray(MAX_COMPANIES)
    var status = OPEN
    /** The company that met it, -1 for none yet; the year and month (0 is January) it was met or failed. */
    var by = -1
    var doneYear = 0
    var doneMonth = 0

    companion object {
        const val TOWN_POP = 0
        const val DELIVER = 1
        const val CONNECT = 2
        const val COMPANY_VALUE = 3
        const val YEAR_PROFIT = 4
        const val KINDS = 5

        const val OPEN = 0
        const val MET = 1
        const val FAILED = 2
    }
}

/**
 * How far [company] has got with [g], to set against [Goal.amount]: the town's people, what it's delivered or carried,
 * its worth, or its profit so far this year.
 */
fun World.goalProgress(g: Goal, company: Int): Long {
    val c = companies.getOrNull(company)
    return when (g.kind) {
        Goal.TOWN_POP -> towns.getOrNull(g.town)?.population?.toLong() ?: 0L
        Goal.DELIVER, Goal.CONNECT -> g.count.getOrElse(company) { 0L }
        Goal.COMPANY_VALUE -> worth.getOrElse(company) { 0L }
        else -> c?.thisYear?.sum() ?: 0L
    }
}

/** The town station [s] delivers to and is counted for: the nearest within [TOWN_AREA] tiles of its sign, or null. */
fun World.stationTown(s: Station): Town? {
    val key = s.signY * width + s.signX
    val id = stationTowns.getOrPut(key) {
        var best = -1
        var bestD = TOWN_AREA + 1
        // Between towns as near, the first.
        for (t in towns) {
            val d = chebyshev(t.x, t.y, s.signX, s.signY)
            if (d < bestD) { best = t.id; bestD = d }
        }
        best
    }
    return towns.getOrNull(id)
}

/**
 * [n] of cargo [c] carried by [company] from station [from] arrived and paid for at station [at], going to industry
 * [taker] if one there takes it: counted for the town and for goals.
 */
internal fun World.delivered(company: Int, c: Int, n: Int, from: Station, at: Station, taker: Industry?) {
    val town = if (taker == null) stationTown(at) else null
    if (town != null) town.delivered[c] += n
    if (goals.isEmpty() || company !in 0 until MAX_COMPANIES) return
    val year = Calendar.year(startYear, day)
    for (g in goals) {
        if (g.status != Goal.OPEN) continue
        when (g.kind) {
            Goal.TOWN_POP -> if (town != null && town.id == g.town) g.count[company] += n.toLong()
            Goal.DELIVER -> if (c == g.cargo && year >= g.from && (if (g.industry >= 0) taker?.id == g.industry else town != null && town.id == g.town)) {
                g.count[company] += n.toLong()
            }
            Goal.CONNECT -> if (c == Cargo.PASSENGERS) {
                val a = stationTown(from)?.id ?: -1
                val b = stationTown(at)?.id ?: -1
                if ((a == g.town && b == g.town2) || (a == g.town2 && b == g.town)) g.count[company] += n.toLong()
            }
        }
    }
}

/** A delivery goal for [ind], which has closed, can't be met any more: it fails at once. */
internal fun World.failGoalsFor(ind: Industry) {
    for ((i, g) in goals.withIndex()) {
        if (g.status != Goal.OPEN || g.kind != Goal.DELIVER || g.industry != ind.id) continue
        g.status = Goal.FAILED
        g.doneYear = Calendar.year(startYear, day)
        g.doneMonth = Calendar.month(startYear, day)
        tell(News.GOAL_FAILED, i)
    }
}

/**
 * The first of each month: open goals met last month are met, by the player's company furthest on, and at a year's
 * end those whose deadline it was fail. Connections count again from the new year.
 */
internal fun World.goalsMonth() {
    if (goals.isEmpty()) return
    worthDay()
    val ended = day - 1
    val year = Calendar.year(startYear, ended)
    val month = Calendar.month(startYear, ended)
    val yearEnded = month == 11
    for ((i, g) in goals.withIndex()) {
        if (g.status != Goal.OPEN) continue
        val by = whoMet(g, yearEnded)
        if (by >= 0) {
            g.status = Goal.MET
            g.by = by
        } else if (yearEnded && year >= g.deadline) g.status = Goal.FAILED
        else continue
        g.doneYear = year
        g.doneMonth = month
        tell(if (g.status == Goal.MET) News.GOAL_MET else News.GOAL_FAILED, i)
    }
    if (yearEnded) for (g in goals) if (g.kind == Goal.CONNECT && g.status == Goal.OPEN) g.count.fill(0)
}

/** The player's company that has met [g], the one furthest on, or -1. A year's profit only counts once the year's ended. */
private fun World.whoMet(g: Goal, yearEnded: Boolean): Int {
    if (g.kind == Goal.YEAR_PROFIT && !yearEnded) return -1
    // A town has to have been delivered to by the company that meets it, not just grow by itself.
    if (g.kind == Goal.TOWN_POP && (towns.getOrNull(g.town)?.population ?: 0) < g.amount) return -1
    var best = -1
    var most = 0L
    for (c in companies) {
        if (c.gone || !human(c.id)) continue
        val p = if (g.kind == Goal.TOWN_POP) g.count[c.id] else goalProgress(g, c.id)
        val enough = if (g.kind == Goal.TOWN_POP) p > 0 else p >= g.amount
        if (enough && (best < 0 || p > most)) { best = c.id; most = p }
    }
    return best
}

/** Sets this new world's goals, as [NewGame.goals] asks, from the seed, by their own dice so the rest of the game's are as without. */
internal fun World.makeGoals() {
    val set = settings.goals
    if (set == GoalSet.NONE) return
    val dice = Rng(settings.seed xor GOAL_DICE)
    when (set) {
        GoalSet.TOWNS -> goals += townGoals(dice, 3)
        GoalSet.DELIVERIES -> goals += deliveryGoals(dice, 3)
        GoalSet.CONNECTIONS -> goals += connectionGoals(dice, 3)
        else -> {
            goals += townGoals(dice, 1)
            goals += deliveryGoals(dice, 1)
            goals += connectionGoals(dice, 1)
            // Value leaves the start money out, so 7 to 11 times it is what 8 to 12 was when it counted.
            goals += Goal(Goal.COMPANY_VALUE, Prices.startMoney(startYear) * (7 + dice.below(5)), deadline(dice))
        }
    }
}

/** A goal's last year: 10 to 20 years on from the start. */
private fun World.deadline(dice: Rng) = startYear + 10 + dice.below(11)

private fun roundTo(v: Long, step: Long) = (v + step / 2) / step * step

/** [n] towns to grow 2 to 4 times over, one from each part of the towns by size, small to big. */
private fun World.townGoals(dice: Rng, n: Int): List<Goal> {
    val sorted = towns.sortedWith(compareBy({ it.population }, { it.id }))
    val out = ArrayList<Goal>()
    if (sorted.isEmpty()) return out
    for (k in 0 until n) {
        val lo = sorted.size * k / n
        val hi = maxOf(lo + 1, sorted.size * (k + 1) / n)
        val t = sorted[lo + dice.below(hi - lo)]
        if (out.any { it.town == t.id }) continue
        val target = roundTo(maxOf(t.population.toLong() * (2 + dice.below(3)), t.population + TOWN_GOAL_LEAST), 50)
        out += Goal(Goal.TOWN_POP, target, deadline(dice), town = t.id)
    }
    return out
}

/**
 * [n] deliveries: with more than one, the first goods or food to the biggest town, then cargo made on the map to
 * industries that take it, each a different industry. [DELIVER_A_YEAR] for each year to the deadline.
 */
private fun World.deliveryGoals(dice: Rng, n: Int): List<Goal> {
    var made = 0
    for (ind in industries) if (!ind.closed) made = made or roster.industries[ind.kind].outputs
    val takers = industries.filter { !it.closed && (0 until Cargo.COUNT).any { c -> made and (1 shl c) != 0 && roster.industries[it.kind].takes(c) } }
    val out = ArrayList<Goal>()
    val big = towns.maxByOrNull { it.population }
    if (big != null && (n > 1 || takers.isEmpty())) {
        val c = listOf(Cargo.GOODS, Cargo.FOOD).firstOrNull { made and (1 shl it) != 0 } ?: Cargo.PASSENGERS
        val end = deadline(dice)
        out += Goal(Goal.DELIVER, (end - startYear) * DELIVER_A_YEAR, end, cargo = c, town = big.id, from = startYear)
    }
    var tries = n * 10
    while (out.size < n && takers.isNotEmpty() && tries-- > 0) {
        val ind = takers[dice.below(takers.size)]
        if (out.any { it.industry == ind.id }) continue
        val cargoes = (0 until Cargo.COUNT).filter { c -> made and (1 shl c) != 0 && roster.industries[ind.kind].takes(c) }
        val c = cargoes[dice.below(cargoes.size)]
        val end = deadline(dice)
        out += Goal(Goal.DELIVER, (end - startYear) * DELIVER_A_YEAR, end, cargo = c, industry = ind.id, from = startYear)
    }
    return out
}

/** [n] pairs of towns [CONNECT_NEAREST] to [CONNECT_FURTHEST] tiles apart, passengers between them in a year by their size. */
private fun World.connectionGoals(dice: Rng, n: Int): List<Goal> {
    val out = ArrayList<Goal>()
    if (towns.size < 2) return out
    var tries = 200
    while (out.size < n && tries-- > 0) {
        val a = towns[dice.below(towns.size)]
        val b = towns[dice.below(towns.size)]
        if (a.id == b.id || chebyshev(a.x, a.y, b.x, b.y) !in CONNECT_NEAREST..CONNECT_FURTHEST) continue
        val lo = minOf(a.id, b.id)
        val hi = maxOf(a.id, b.id)
        if (out.any { it.town == lo && it.town2 == hi }) continue
        val amount = roundTo(maxOf(CONNECT_LEAST, (a.population + b.population) / 2L), 50)
        out += Goal(Goal.CONNECT, amount, deadline(dice), town = lo, town2 = hi)
    }
    return out
}

/** Folded into the seed for the goals' dice. */
private const val GOAL_DICE = 0x476f616cL

/** The fewest people a town goal adds. */
private const val TOWN_GOAL_LEAST = 200L

/** A delivery goal's amount for each year to its deadline. */
private const val DELIVER_A_YEAR = 500L

/** How far apart towns to connect are, in tiles, and the fewest passengers a year asked for. */
private const val CONNECT_NEAREST = 20
private const val CONNECT_FURTHEST = 80
private const val CONNECT_LEAST = 200L

/**
 * Something a town needs in a month to grow when towns grow only supplied: [needed] of [cargo] and the [got] of it
 * last month. Passengers and mail are what's picked up from its buildings, the rest what's delivered. [heating] is
 * coal or fuel together, for the cold months, with [cargo] coal.
 */
class TownNeed(val cargo: Int, val needed: Int, val got: Int, val heating: Boolean = false) {
    val met get() = got >= needed
}

/**
 * What [t] needs a month to grow when supplied, as OpenTTD's city builder scripts have it: passengers always, mail
 * from 500 people, food from 1000, goods from 2000, fuel from 4000, and heating from 1000 through the cold months,
 * December to March. Each so many for every 100 people, with what it got last month.
 */
fun World.townNeeds(t: Town): List<TownNeed> {
    val p = t.population
    fun need(per100: Int) = maxOf(1, p * per100 / 100)
    val out = ArrayList<TownNeed>()
    out += TownNeed(Cargo.PASSENGERS, need(NEED_PASSENGERS), t.servedLastMonth - t.mailLastMonth)
    if (p >= 500) out += TownNeed(Cargo.MAIL, need(NEED_MAIL), t.mailLastMonth)
    if (p >= 1000) out += TownNeed(Cargo.FOOD, need(NEED_FOOD), t.deliveredLastMonth[Cargo.FOOD])
    if (p >= 2000) out += TownNeed(Cargo.GOODS, need(NEED_GOODS), t.deliveredLastMonth[Cargo.GOODS])
    if (p >= 4000) out += TownNeed(Cargo.FUEL, need(NEED_FUEL), t.deliveredLastMonth[Cargo.FUEL])
    // Last month, as that's what the tallies are for.
    val lastMonth = (Calendar.month(startYear, day) + 11) % 12
    if (p >= 1000 && (lastMonth == 11 || lastMonth <= 2)) {
        out += TownNeed(Cargo.COAL, need(NEED_HEATING), t.deliveredLastMonth[Cargo.COAL] + t.deliveredLastMonth[Cargo.FUEL], heating = true)
    }
    return out
}

/** Whether [t] got all it needed last month. */
fun World.townSupplied(t: Town) = townNeeds(t).all { it.met }

/** What a town needs a month for every 100 people. */
private const val NEED_PASSENGERS = 10
private const val NEED_MAIL = 2
private const val NEED_FOOD = 3
private const val NEED_GOODS = 2
private const val NEED_FUEL = 1
private const val NEED_HEATING = 2
