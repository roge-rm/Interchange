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
    /** How much it spends keeping its track up, an [Upkeep] level, and what it owes short of a dollar, in cents. */
    var upkeep = Upkeep.NORMAL
    var upkeepOwed = 0L
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
    const val START_MONEY = 250_000L
    /** The most a company can borrow, and the steps it borrows and repays in. */
    const val MAX_LOAN = 300_000L
    const val LOAN_STEP = 10_000L
    /** Interest on the loan, in hundredths of a percent a year. */
    const val INTEREST = 400
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

/** Gives [amount] to [company] and books it to [kind]. */
internal fun World.earn(company: Company, kind: Int, amount: Long) {
    company.money += amount
    company.thisYear[kind] += amount
    company.monthIn += amount
}

/** The most [company] may borrow now: the bank's limit, risen with inflation. */
fun World.maxLoan(): Long = price(Prices.MAX_LOAN) / Prices.LOAN_STEP * Prices.LOAN_STEP

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

/** The end of each month: interest on the loan, and the month's money put in the history. */
internal fun World.companiesMonth() {
    for (c in companies) {
        // A month's interest for each work day in it, so a loan costs the same against the work done whatever the day length.
        val interest = c.loan * Prices.INTEREST / 10_000 / 12 * settings.dayLength
        if (interest > 0) spend(c, Books.INTEREST, interest)
        c.history += longArrayOf(c.money, c.monthIn, c.monthOut)
        while (c.history.size > HISTORY_MONTHS) c.history.removeAt(0)
        c.monthIn = 0
        c.monthOut = 0
    }
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
    if (settings.inflation && Calendar.year(startYear, day) <= startYear + INFLATION_YEARS) {
        costScale += costScale * 2 / 100
        payScale += payScale * 15 / 1000
    }
}

/** How many years inflation runs for, as in OpenTTD. */
const val INFLATION_YEARS = 170
