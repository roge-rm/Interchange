package com.rm.interchange.sim

/*
 * Subsidies, as in OpenTTD: now and then a route nobody runs is offered for a
 * year, passengers between two towns or freight from an industry to one that
 * takes it. The first company to carry it there wins it, and is paid double
 * for that route for a year.
 */

/**
 * A subsidy for [cargo] from [from] to [to]: towns for passengers and mail, industries for freight. Unwon it's on
 * offer for [months] more; won, [company] is paid double for [months] more. Kept when it runs out, so news can still
 * name it.
 */
class Subsidy(val cargo: Int, val from: Int, val to: Int, val towns: Boolean) {
    var company = -1
    var months = SUBSIDY_MONTHS
    val open get() = months > 0
}

/** Months a subsidy is offered for, and paid for once won. */
const val SUBSIDY_MONTHS = 12

/** Most subsidies on offer at once. */
private const val MOST_OFFERED = 4

/** How far apart, in tiles, a subsidy's two ends are. */
private const val SUBSIDY_MIN = 10
private const val SUBSIDY_MAX = 60

/** How near a town's middle a station is to count for it, in tiles. */
private const val SUBSIDY_TOWN_REACH = 10

/** Towns smaller than this aren't offered subsidies. */
private const val SUBSIDY_TOWN = 100

/** The first of each month: subsidies run down, and maybe a new one is offered. */
internal fun World.subsidiesMonth() {
    for (s in subsidies) if (s.open) s.months--
    if (subsidies.count { it.open && it.company < 0 } >= MOST_OFFERED || rng.below(3) != 0) return
    val offer = if (rng.below(2) == 0) passengerSubsidy() else freightSubsidy()
    if (offer != null) {
        subsidies += offer
        tell(News.SUBSIDY_OFFERED, subsidies.size - 1)
    }
}

/** Two towns of fair size a fair way apart, not already on offer or won; null if none come up. */
private fun World.passengerSubsidy(): Subsidy? {
    val big = towns.filter { it.population >= SUBSIDY_TOWN }
    if (big.size < 2) return null
    repeat(10) {
        val a = big[rng.below(big.size)]
        val b = big[rng.below(big.size)]
        val d = chebyshev(a.x, a.y, b.x, b.y)
        if (a.id == b.id || d < SUBSIDY_MIN || d > SUBSIDY_MAX) return@repeat
        if (subsidies.any { it.open && it.towns && it.cargo == Cargo.PASSENGERS && setOf(it.from, it.to) == setOf(a.id, b.id) }) return@repeat
        return Subsidy(Cargo.PASSENGERS, a.id, b.id, towns = true)
    }
    return null
}

/** An industry's cargo to one that takes it a fair way off, that nobody carries yet; null if none come up. */
private fun World.freightSubsidy(): Subsidy? {
    val makers = industries.filter { ind -> !ind.closed && roster.industries[ind.kind].produces.any { it > 0 } }
    if (makers.isEmpty()) return null
    repeat(10) {
        val a = makers[rng.below(makers.size)]
        val kind = roster.industries[a.kind]
        val cargoes = (0 until Cargo.COUNT).filter { kind.produces[it] > 0 }
        val c = cargoes[rng.below(cargoes.size)]
        // Not one already served.
        if (industryStations[a.id].orEmpty().any { stations.getOrNull(it)?.let { s -> s.lastCall[c] >= 0 } == true }) return@repeat
        val (ax, ay) = middle(a)
        val takers = industries.filter { b ->
            !b.closed && b.id != a.id && roster.industries[b.kind].takes(c) && middle(b).let { (bx, by) -> chebyshev(ax, ay, bx, by) in SUBSIDY_MIN..SUBSIDY_MAX }
        }
        if (takers.isEmpty()) return@repeat
        val b = takers[rng.below(takers.size)]
        if (subsidies.any { it.open && !it.towns && it.cargo == c && it.from == a.id && it.to == b.id }) return@repeat
        return Subsidy(c, a.id, b.id, towns = false)
    }
    return null
}

/** Whether station [s] serves [place] of a subsidy: an industry it reaches, or a town it's near the middle of. */
private fun World.serves(s: Station, place: Int, town: Boolean): Boolean {
    if (town) return towns.getOrNull(place)?.let { chebyshev(s.signX, s.signY, it.x, it.y) <= SUBSIDY_TOWN_REACH } == true
    // Asked as cargo's paid for, at any tick, so on a just loaded copy the reach may not be worked out yet.
    if (catchmentDirty) workOutCatchment()
    return stationIndustries[s.id]?.contains(place) == true
}

/**
 * What [paid] for [cargo] carried from [from] to [to] by [company] comes to with subsidies: an open one it matches
 * is won if nobody has it, and pays double to whoever has.
 */
internal fun World.subsidised(company: Int, cargo: Int, from: Station, to: Station, paid: Long): Long {
    for ((i, s) in subsidies.withIndex()) {
        if (!s.open || s.cargo != cargo || (s.company >= 0 && s.company != company)) continue
        // Passengers go either way between the two towns.
        val forward = serves(from, s.from, s.towns) && serves(to, s.to, s.towns)
        val back = s.towns && serves(from, s.to, true) && serves(to, s.from, true)
        if (!forward && !back) continue
        if (s.company < 0) {
            s.company = company
            s.months = SUBSIDY_MONTHS
            tell(News.SUBSIDY_AWARDED, i)
        }
        return paid * SUBSIDY_PAYS
    }
    return paid
}

/** Whether a subsidy nobody has won yet is on offer for [cargo] between [from] and [to], towns or industries. */
fun World.onOffer(cargo: Int, from: Int, to: Int, towns: Boolean): Boolean = subsidies.any {
    it.open && it.company < 0 && it.cargo == cargo && it.towns == towns && (it.from == from && it.to == to || towns && it.from == to && it.to == from)
}

/** What a won subsidy multiplies the fare by. */
const val SUBSIDY_PAYS = 2

/** Works out every company's worth for [World.worth], on the game thread, for the screen to read. */
internal fun World.worthDay() {
    for (i in companies.indices) if (i < worth.size) worth[i] = companyValue(companies[i])
}

/**
 * What [c] is worth: its money less its loan, and what its vehicles would sell for. Track, stations and the rest
 * aren't counted, as nobody would buy them.
 */
fun World.companyValue(c: Company): Long {
    var v = c.money - c.loan
    for (t in trains) if (t != null && t.owner == c.id) v += value(t)
    for (r in roadVehicles) if (r != null && r.owner == c.id) v += roadValue(r)
    for (s in ships) if (s != null && s.owner == c.id) v += craftValue(s.price, s.built, s.info.life)
    for (a in aircraft) if (a != null && a.owner == c.id) v += craftValue(a.price, a.built, a.info.life)
    return v
}
