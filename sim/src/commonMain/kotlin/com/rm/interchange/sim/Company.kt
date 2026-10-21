package com.rm.interchange.sim

/**
 * A player's company, or one run by the computer. Money is in whole dollars.
 * Every dollar in or out is booked to a [Books] kind, this year's and last
 * year's kept apart, and the last [HISTORY_MONTHS] months' money, income and
 * spending kept for graphs.
 */
class Company(val id: Int, var name: String, var money: Long) {
    /** Running costs owed short of a whole dollar a day, carried to the next. */
    var runningOwed = 0L
    /** What it's borrowed from the bank. */
    var loan = 0L
    val thisYear = LongArray(Books.COUNT)
    val lastYear = LongArray(Books.COUNT)
    /** Money, income and spending at the end of each month, oldest first. */
    val history = ArrayList<LongArray>()
    /** Income and spending this month so far. */
    var monthIn = 0L
    var monthOut = 0L
    /** How much it spends keeping its track up, an [Upkeep] level, and what it owes short of a dollar, in tenths of a cent. */
    var upkeep = Upkeep.NORMAL
    var upkeepOwed = 0L
    /** Its tiles of track, and what its roads cost a work day in tenths of a cent, as counted on the last calendar day. */
    var trackTiles = 0
    var roadUpkeep = 0L
    /** Months running it's been unable to pay its way, even borrowing all it can. */
    var trouble = 0
    /** Bought out or gone under: it owns nothing and does nothing. Kept so ids stay put. */
    var gone = false
    /** The money it was set up with, its loan aside, which its [World.companyValue] leaves out. */
    var founded = money
    /** Its insignia, [Insignia.BYTES] bytes; see-through all over until it's given one. */
    var insignia = ByteArray(Insignia.BYTES)
    /** Autorenew on, how many work months from the end of a vehicle's life it's renewed (before if negative), and the money it keeps in hand. */
    var autorenew = true
    var renewMonths = Autorenew.MONTHS_DEFAULT
    var renewFloor = 0L
}

/** What money is booked to: what it was spent on or where it came from. */
object Books {
    const val CONSTRUCTION = 0
    const val NEW_TRAINS = 1
    const val RUNNING = 2
    const val INCOME = 3
    const val INTEREST = 4
    const val TRAINS_SOLD = 5
    const val UPKEEP = 6
    const val COUNT = 7
}

/** Months of money kept for the finance graphs. */
const val HISTORY_MONTHS = 24

/** What things cost, in dollars, before inflation. */
object Prices {
    const val TRACK = 120L
    const val TRACK_REMOVE = 20L
    /** Building a sloping tile up level under track. */
    const val FOUNDATION = 250L
    const val TERRAFORM = 60L
    const val PLATFORM = 600L
    const val DEPOT = 1_500L
    const val SIGNAL = 200L
    /** A drive-through road stop. */
    const val ROAD_STOP = 400L
    /** Wires over a piece of track, for electric engines. */
    const val WIRE = 300L
    const val CLEAR = 50L
    /** Clearing a tree to build. */
    const val TREE = 40L
    /** Pulling down a town building, for each of the people who lived or worked in it. */
    const val DEMOLISH_PER_PERSON = 30L
    /** A scenario's money unless it's given its own. */
    const val START_MONEY = 250_000L
    /** The steps a company borrows and repays in. */
    const val LOAN_STEP = 10_000L
    /** Interest on the loan, in hundredths of a percent a work year. */
    const val INTEREST = 400

    /**
     * What a company starts with in each era, and the most the bank lends then, before inflation: about a first
     * railway of the day (30 tiles, two stations of five platforms, a depot and an engine with three coaches) and a
     * little over, from the Dorchester's $23,000 line in 1836 to the F40's $253,000 in 2000, rising a few years after
     * each dearer engine comes in. In between it's a straight line from one year here to the next.
     */
    private val ERA_YEARS = intArrayOf(1836, 1852, 1860, 1904, 1912, 1928, 1956, 1962, 1988, 1994, 2020, 2026)
    private val ERA_MONEY = longArrayOf(30_000, 32_000, 45_000, 55_000, 75_000, 95_000, 130_000, 160_000, 220_000, 280_000, 330_000, 450_000)

    /** The year town actions are priced for in [TownActions.COST]; other years scale them by [era]. */
    const val ERA_BASE = 1950

    /** What [ERA_MONEY] gives for [year]. */
    fun era(year: Int): Long {
        if (year <= ERA_YEARS.first()) return ERA_MONEY.first()
        for (k in 1 until ERA_YEARS.size) {
            if (year > ERA_YEARS[k]) continue
            val y0 = ERA_YEARS[k - 1]
            val m0 = ERA_MONEY[k - 1]
            return m0 + (ERA_MONEY[k] - m0) * (year - y0) / (ERA_YEARS[k] - y0)
        }
        return ERA_MONEY.last()
    }

    /** What a new company starts with in [year], before inflation, a whole number of thousands. */
    fun startMoney(year: Int): Long = era(year) / 1_000 * 1_000
}

/** [base] dollars as they are now, after inflation. */
fun World.price(base: Long): Long = base * costScale shr 16

/** What a fare of [base] dollars pays now, after inflation. */
fun World.pay(base: Long): Long = base * payScale shr 16

/** Takes [amount] from [company] and books it to [kind]. Doesn't check it can pay. */
internal fun World.spend(company: Company, kind: Int, amount: Long) {
    company.money -= amount
    company.thisYear[kind] -= amount
    company.monthOut += amount
}

/** Pays [company] half of [base] back for selling its own property, booked to construction. Nothing if that half is 0. */
internal fun World.scrap(company: Company, base: Long) {
    val back = scrapBack(base)
    if (back == 0L) return
    spend(company, Books.CONSTRUCTION, -back)
}

/** Half of [base] now, the same amount [scrap] pays. */
internal fun World.scrapBack(base: Long): Long = price(base) / 2

/** Gives [amount] to [company] and books it to [kind]. */
internal fun World.earn(company: Company, kind: Int, amount: Long) {
    company.money += amount
    company.thisYear[kind] += amount
    company.monthIn += amount
}

/** The most a company may borrow now: about a first railway of the year's era, risen with inflation, in whole steps. */
fun World.maxLoan(): Long = (price(Prices.era(Calendar.year(startYear, day))) / Prices.LOAN_STEP).coerceAtLeast(1) * Prices.LOAN_STEP

/** The most [c] may owe without being in trouble: the bank's limit, or more if it owed more when the limit was higher, as in an older save. */
fun World.loanLimit(c: Company): Long = maxOf(maxLoan(), c.loan)

/** Borrows or repays a step, as far as the limit or the money in hand allows. */
internal fun World.loanCommand(company: Company, c: Command.Loan) {
    if (c.borrow) {
        val step = minOf(Prices.LOAN_STEP, maxLoan() - company.loan)
        if (step <= 0) return
        company.loan += step
        company.money += step
    } else {
        val step = minOf(Prices.LOAN_STEP, company.loan)
        if (step <= 0 || company.money < step) return
        company.loan -= step
        company.money -= step
    }
}

/** The end of each calendar month: interest on the loan, and the month's money put in the history. */
internal fun World.companiesMonth() {
    for (c in companies) {
        // A month's interest for each work day in it, so a loan costs the same against the work done whatever the day length.
        val interest = c.loan * Prices.INTEREST / 10_000 / 12 * settings.dayLength
        if (interest > 0) spend(c, Books.INTEREST, interest)
        if (c.gone) continue
        c.history += longArrayOf(c.money, c.monthIn, c.monthOut)
        while (c.history.size > HISTORY_MONTHS) c.history.removeAt(0)
        c.monthIn = 0
        c.monthOut = 0
    }
}

/** Work days in a month of work, for what goes by months of work at any day length. */
const val WORK_MONTH = 30

/**
 * Whether a month of work ended today: at day length 1 the calendar month's first, otherwise each time another
 * [WORK_MONTH] work days have gone by, so trouble and subsidies keep time with the work done.
 */
internal fun World.workMonthEnded(): Boolean {
    if (day <= 0) return false
    val n = settings.dayLength
    if (n == 1) return Calendar.dayOfMonth(startYear, day) == 1
    return day.toLong() * n / WORK_MONTH != (day - 1).toLong() * n / WORK_MONTH
}

/**
 * The first of each year: the books turn over, and with inflation on, costs
 * go up 2% and fares 1.5%, for the first [INFLATION_YEARS] years.
 */
internal fun World.companiesYear() {
    for (c in companies) {
        c.thisYear.copyInto(c.lastYear)
        c.thisYear.fill(0)
    }
    // What goes on sale this year, engines and the vehicles that run on their own, not wagons.
    val year = Calendar.year(startYear, day)
    for (e in roster.engines) {
        if (!e.enabled || e.from != year || e.kind == VehicleKind.WAGON || e.kind == VehicleKind.TENDER) continue
        tell(News.NEW_VEHICLE, e.id)
    }
    if (settings.inflation && Calendar.year(startYear, day) <= startYear + INFLATION_YEARS) {
        costScale += costScale * 2 / 100
        payScale += payScale * 15 / 1000
    }
}

/** How many years inflation runs for, as in OpenTTD. */
const val INFLATION_YEARS = 170
