package com.rm.interchange.sim

/*
 * Contracts, with stockpiles and contracts on: now and then an industry or a
 * town offers one, [Contract.amount] of a cargo each month for some months, or
 * a total by a date before freeze-up. The first player's company to accept it
 * has it. Each month met pays its reward; three misses, or a total not in by
 * its date, end it with a penalty. Rivals don't take contracts.
 */

/**
 * A contract for [cargo] to industry [industry], or to town [town] with [industry] -1. Each counted month asks
 * [amount] (for [BY_DATE] the whole total), over [months] counted months. [until] is a month as year * 12 + month (0 is
 * January): for [BY_DATE] the last month the total can come in, for an offer the month it lapses at the start of.
 * [reward] and [penalty] are dollars before inflation: paid each month met (for [BY_DATE] once), taken when it fails.
 */
class Contract(
    val cargo: Int,
    val industry: Int,
    val town: Int,
    val amount: Int,
    val months: Int,
    val season: Int,
    val until: Int,
    val reward: Long,
    val penalty: Long,
) {
    var status = OFFERED
    /** The company that has it, or -1. */
    var company = -1
    /** The month that lapses an offer, as [until]. */
    var offerUntil = 0
    /** Brought this month, or for [BY_DATE] since it was accepted; and last counted month's. */
    var count = 0
    var lastCount = 0
    /** Counted months met and missed. */
    var met = 0
    var misses = 0
    /** Counting from the first month after it was accepted. */
    var started = false

    companion object {
        /** Every month counts. */
        const val ALL_YEAR = 0
        /** Only November to March count. */
        const val WINTER = 1
        /** A total by the end of month [until]. */
        const val BY_DATE = 2

        const val OFFERED = 0
        const val RUNNING = 1
        /** Ran its months out. */
        const val DONE = 2
        /** Ended by misses or a missed date, with the penalty paid. */
        const val FAILED = 3
        /** Offered and nobody took it. */
        const val LAPSED = 4
        /** Its industry closed, or its company went under, was bought or started again; no penalty. */
        const val CANCELLED = 5
    }
}

/** Whether contracts are on in this game: they need stockpiles too. */
val World.contractsOn get() = settings.stockpiles && settings.contracts

/** The calendar month [day] is in, as year * 12 + month. */
private fun World.monthIndex(day: Int) = Calendar.year(startYear, day) * 12 + Calendar.month(startYear, day)

/** Whether [month] (0 is January) is one a [Contract.WINTER] contract counts. */
private fun winter(month: Int) = month >= 10 || month <= 2

/** [n] of cargo [c] delivered by [company] to industry [taker] if one takes it, at a station serving [town]: counted for its running contracts. */
internal fun World.contractDelivered(company: Int, c: Int, n: Int, town: Town?, taker: Industry?) {
    for (k in contracts) {
        if (k.status != Contract.RUNNING || k.company != company || k.cargo != c) continue
        if (if (k.industry >= 0) taker?.id == k.industry else town?.id == k.town) k.count += n
    }
}

/**
 * The first of each calendar month: offers lapse, running contracts are settled for the month just gone, and now and
 * then a new one is offered.
 */
internal fun World.contractsMonth() {
    if (!contractsOn) return
    val ended = day - 1
    val now = monthIndex(ended)
    val month = Calendar.month(startYear, ended)
    for ((i, k) in contracts.withIndex()) when (k.status) {
        Contract.OFFERED -> if (now + 1 >= k.offerUntil) k.status = Contract.LAPSED
        Contract.RUNNING -> settle(i, k, now, month)
    }
    if (contracts.count { it.status == Contract.OFFERED } < MOST_OFFERS && rng.below(OFFER_ODDS) == 0) offerContract(now + 1)
}

/** Contract [k], number [i], at the end of month [now] (as [Contract.until]), [month] of the year. */
private fun World.settle(i: Int, k: Contract, now: Int, month: Int) {
    val company = companies.getOrNull(k.company)
    if (company == null || company.gone) {
        k.status = Contract.CANCELLED
        return
    }
    if (k.season == Contract.BY_DATE) {
        if (k.count >= k.amount) {
            earn(company, Books.INCOME, pay(k.reward))
            k.status = Contract.DONE
            tell(News.CONTRACT_DONE, i)
        } else if (now >= k.until) fail(i, k, company)
        return
    }
    // Accepted part way through a month, it counts from the next.
    if (!k.started || (k.season == Contract.WINTER && !winter(month))) {
        k.started = true
        k.count = 0
        return
    }
    k.lastCount = k.count
    k.count = 0
    if (k.lastCount >= k.amount) {
        k.met++
        earn(company, Books.INCOME, pay(k.reward))
        tell(News.CONTRACT_MET, i)
    } else {
        k.misses++
        tell(News.CONTRACT_MISSED, i)
    }
    if (k.misses >= MOST_MISSES) fail(i, k, company)
    else if (k.met + k.misses >= k.months) {
        k.status = Contract.DONE
        tell(News.CONTRACT_DONE, i)
    }
}

private fun World.fail(i: Int, k: Contract, company: Company) {
    k.status = Contract.FAILED
    spend(company, Books.INCOME, pay(k.penalty))
    tell(News.CONTRACT_FAILED, i)
}

/** Industry [ind] has closed: its offers and running contracts end, without a penalty. */
internal fun World.cancelContractsFor(ind: Industry) {
    for (k in contracts) if (k.industry == ind.id && (k.status == Contract.OFFERED || k.status == Contract.RUNNING)) k.status = Contract.CANCELLED
}

/** Company [id] has gone under, been bought or starts again: its running contracts end, without a penalty. */
internal fun World.cancelContractsOf(id: Int) {
    for (k in contracts) if (k.status == Contract.RUNNING && k.company == id) k.status = Contract.CANCELLED
}

/** [company] accepts contract [index], if it's still offered and its company is a player's. */
internal fun World.acceptContract(company: Company, index: Int) {
    if (!contractsOn || !human(company.id)) return
    val k = contracts.getOrNull(index) ?: return
    if (k.status != Contract.OFFERED) return
    k.status = Contract.RUNNING
    k.company = company.id
    k.count = 0
}

/**
 * A new offer from month [next]: two times in three an industry that takes a cargo made on the map, else a town of
 * [OFFER_TOWN] people or more for its food, goods or fuel, or coal or fuel through the winter. None if what comes up
 * is already on offer or running.
 */
private fun World.offerContract(next: Int) {
    var made = 0
    for (ind in industries) if (!ind.closed) made = made or roster.industries[ind.kind].outputs
    if (made == 0) return
    val year = Calendar.year(startYear, day)
    var cargo: Int
    var industry = -1
    var town = -1
    var season = Contract.ALL_YEAR
    val x: Int
    val y: Int
    if (rng.below(3) != 0) {
        val takers = industries.filter { !it.closed && it.closing == 0 && roster.industries[it.kind].accepts and made != 0 }
        if (takers.isEmpty()) return
        val ind = takers[rng.below(takers.size)]
        val kind = roster.industries[ind.kind]
        val cargoes = (0 until cargoCount).filter { kind.takes(it) && made and (1 shl it) != 0 }
        cargo = cargoes[rng.below(cargoes.size)]
        industry = ind.id
        // One by water that freezes wants it in before freeze-up.
        if (kind.weather and IndustryKind.ICE != 0) season = Contract.BY_DATE
        val (mx, my) = middle(ind)
        x = mx
        y = my
    } else {
        val big = towns.filter { it.population >= OFFER_TOWN }
        if (big.isEmpty()) return
        val t = big[rng.below(big.size)]
        val cargoes = listOf(Cargo.FOOD, Cargo.GOODS, Cargo.FUEL, Cargo.COAL).filter { it < cargoCount && made and (1 shl it) != 0 && townWants(it) }
        if (cargoes.isEmpty()) return
        cargo = cargoes[rng.below(cargoes.size)]
        town = t.id
        if (cargo == Cargo.COAL || cargo == Cargo.FUEL) season = Contract.WINTER
        x = t.x
        y = t.y
    }
    if (contracts.any { (it.status == Contract.OFFERED || it.status == Contract.RUNNING) && it.cargo == cargo && it.industry == industry && it.town == town }) return
    // How far it comes: from the nearest industry that makes it.
    var far = Int.MAX_VALUE
    for (ind in industries) {
        if (ind.closed || ind.id == industry || roster.industries[ind.kind].outputs and (1 shl cargo) == 0) continue
        val (mx, my) = middle(ind)
        far = minOf(far, kotlin.math.abs(mx - x) + kotlin.math.abs(my - y))
    }
    if (far == Int.MAX_VALUE) return
    far = far.coerceAtLeast(OFFER_NEAREST)
    var amount = roundTo(offerAmount(year) * (2 + rng.below(3)) / 3, 10) * settings.dayLength
    if (town >= 0) amount /= 2
    var months = if (season == Contract.WINTER) 3 + rng.below(3) else 3 + rng.below(10)
    var until = 0
    if (season == Contract.BY_DATE) {
        // The end of November this year, or next year's if that's less than three months off.
        until = next / 12 * 12 + 10
        if (until - next < 2) until += 12
        months = until - next + 1
    }
    // Half what carrying a month's amount that far pays at full fare; the penalty is two months of that.
    val monthly = amount.toLong() * far * roster.cargo.rate[cargo] / 200
    val total = if (season == Contract.BY_DATE) amount * months else amount
    val k = Contract(cargo, industry, town, total, months, season, until, if (season == Contract.BY_DATE) monthly * months else monthly, monthly * 2)
    k.offerUntil = next + OFFER_MONTHS
    contracts += k
    tell(News.CONTRACT_OFFERED, contracts.size - 1)
}

private fun roundTo(v: Int, step: Int) = maxOf(step, (v + step / 2) / step * step)

/** A contract's monthly amount by the year, before day length: about what a first freight line of the day carries. */
private fun offerAmount(year: Int): Int = when {
    year < 1860 -> 60
    year < 1900 -> 100
    year < 1950 -> 200
    year < 2000 -> 300
    else -> 400
}

/** Most offers standing at once, one in this many months a new one comes, and the calendar months an offer stands. */
private const val MOST_OFFERS = 3
private const val OFFER_ODDS = 2
private const val OFFER_MONTHS = 2

/** Missed months that end a contract. */
const val MOST_MISSES = 3

/** The fewest people a town offering a contract has, and the shortest distance a reward is worked out on, in tiles. */
private const val OFFER_TOWN = 1000
private const val OFFER_NEAREST = 20

/** A contract as the player sees it: the reward and penalty after inflation, [count] this month's or the total so far. */
class ContractView(
    val id: Int,
    val cargo: Int,
    val industry: Int,
    val town: Int,
    val amount: Int,
    val months: Int,
    val season: Int,
    /** For [Contract.BY_DATE], the year and month (0 is January) it has to be in by. */
    val untilYear: Int,
    val untilMonth: Int,
    val reward: Long,
    val penalty: Long,
    val status: Int,
    val company: Int,
    val count: Int,
    val lastCount: Int,
    val met: Int,
    val misses: Int,
    /** For an offer, the year and month it lapses at the start of. */
    val offerYear: Int,
    val offerMonth: Int,
)

/** Contract [i] as a view, whatever its status, or null if there's none. */
fun World.contractView(i: Int): ContractView? {
    val k = contracts.getOrNull(i) ?: return null
    return ContractView(
        i, k.cargo, k.industry, k.town, k.amount, k.months, k.season, k.until / 12, k.until % 12, pay(k.reward), pay(k.penalty),
        k.status, k.company, k.count, k.lastCount, k.met, k.misses, k.offerUntil / 12, k.offerUntil % 12,
    )
}

/** Contracts on offer and [company]'s running ones, offers first, each by its place in [World.contracts]. */
fun World.contractViews(company: Int): List<ContractView> {
    if (contracts.isEmpty()) return emptyList()
    val out = ArrayList<ContractView>()
    for (status in listOf(Contract.OFFERED, Contract.RUNNING)) for ((i, k) in contracts.withIndex()) {
        if (k.status != status || (status == Contract.RUNNING && k.company != company)) continue
        out += contractView(i)!!
    }
    return out
}
