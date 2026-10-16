package com.rm.interchange.sim

/**
 * A scripted player for balance tests: a normal new game, played by a simple plan through the same commands a
 * player sends. It borrows as it needs, opens a bus line between the biggest towns near its home (the biggest town),
 * a railway between towns or from a mine to its taker once it can pay for one, and truck lines, adds vehicles where
 * people or cargo wait, sells what loses and repays the loan when it's well off. The planning is the rivals' own
 * ([newRoute], [newRailRoute], [newFreightRoute], [growRoute], [sellLosers]), run for company 0.
 *
 * It looks over its business every [thinkDays] work days, so it plays as often against the work done at any day
 * length, as a person playing would.
 */
class Player(val world: World, val thinkDays: Int = 30, val mostRoutes: Int = 12) {
    /** One calendar year's books, read on 1 January for the year before. */
    class Year(
        val year: Int,
        val money: Long,
        val loan: Long,
        val value: Long,
        val income: Long,
        val running: Long,
        val upkeep: Long,
        val interest: Long,
        val built: Long,
        val bought: Long,
        val routes: Int,
        val vehicles: Int,
    ) {
        val profit get() = income - running - upkeep - interest
    }

    val company get() = world.companies[0]
    /** Its plans, as a rival keeps them, from a home town that moves on to the next biggest when nothing near it will do. */
    var plan = Rival(0, homes().firstOrNull() ?: 0)
        private set
    val years = ArrayList<Year>()
    /** What it started with and could borrow then. */
    val startMoney = company.money
    val startLimit = world.maxLoan()
    /** Routes opened, by kind, for the report. */
    var buses = 0
    var railways = 0
    var trucks = 0
    /** The work day of the last new route, so it gives each one a while to pay before the next. */
    private var lastNew = -1_000_000
    private var nextThink = 0

    /** Plays on for [n] calendar years, keeping each year's books in [years]. */
    fun play(n: Int) {
        val w = world
        val end = Calendar.year(w.startYear, w.day) + n
        while (Calendar.year(w.startYear, w.day) < end) {
            val before = w.day
            w.step()
            if (w.workDay >= nextThink && w.tick % Calendar.TICKS_PER_DAY == 0L) {
                nextThink = w.workDay + thinkDays
                think()
            }
            if (w.day != before && Calendar.newYear(w.startYear, w.day) && w.day > 0) years += books()
        }
    }

    private fun books(): Year {
        val c = company
        val y = c.lastYear
        return Year(
            Calendar.year(world.startYear, world.day) - 1, c.money, c.loan, world.companyValue(c),
            y[Books.INCOME], -y[Books.RUNNING], -y[Books.UPKEEP], -y[Books.INTEREST], -y[Books.CONSTRUCTION], -y[Books.NEW_TRAINS],
            plan.routes.size, vehicles(),
        )
    }

    fun vehicles(): Int = world.trains.count { it != null && it.owner == 0 } + world.roadVehicles.count { it != null && it.owner == 0 }

    /** A month or so's look over the business, as a simple player would. */
    fun think() {
        val w = world
        val c = company
        if (c.gone) return
        while (c.money < 0 && c.loan < w.maxLoan()) w.apply(Command.Loan(0, true))
        w.sellLosers(plan, c)
        for (route in plan.routes.toList()) w.growRoute(c, route)
        if (plan.routes.size < mostRoutes && newRouteDue()) openRoute()
        // Well off: the loan goes back, keeping a quarter of what a company starts with now.
        val keep = w.price(Prices.era(Calendar.year(w.startYear, w.day))) / 4
        while (c.loan > 0 && c.money - Prices.LOAN_STEP > keep && plan.routes.size >= 1) {
            val was = c.loan
            w.apply(Command.Loan(0, false))
            if (c.loan == was) break
        }
    }

    /** The first line at once; after that one half a work year after the last, once the company's out of debt. */
    private fun newRouteDue(): Boolean {
        if (plan.routes.isEmpty()) return true
        if (world.workDay - lastNew < 180) return false
        val c = company
        return c.money - c.loan > 0
    }

    /** Buses first, then a railway, then trucks, round again; anything it can't do now, the next kind. */
    private fun openRoute() {
        val w = world
        val c = company
        val kinds = listOf(KIND_BUS, KIND_RAIL, KIND_TRUCK)
        val n = plan.routes.size
        for (k in kinds.indices) {
            val kind = kinds[(n + k) % kinds.size]
            val tried = when (kind) {
                KIND_BUS -> w.newRoute(plan, c)
                // A railway that's expected to cover its running cost, where a rival wants half as much again.
                KIND_RAIL -> w.newRailRoute(plan, c, margin = 100)
                else -> w.newFreightRoute(plan, c)
            }
            if (tried == Tried.BUILT) {
                when (kind) { KIND_BUS -> buses++; KIND_RAIL -> railways++; else -> trucks++ }
                lastNew = w.workDay
                return
            }
            if (tried == Tried.POOR) return
        }
        // Nothing near home will do: the next biggest town is home from now on.
        val towns = homes()
        val next = towns.getOrNull((towns.indexOf(plan.home) + 1) % towns.size.coerceAtLeast(1)) ?: return
        if (next == plan.home) return
        plan = Rival(0, next).also { it.routes += plan.routes; it.failed += plan.failed }
    }

    /** Towns by size, biggest first. */
    private fun homes(): List<Int> = world.towns.sortedWith(compareByDescending<Town> { it.population }.thenBy { it.id }).map { it.id }

    companion object {
        private const val KIND_BUS = 0
        private const val KIND_RAIL = 1
        private const val KIND_TRUCK = 2

        /** A normal new game for the player: towns and industries on, the default settings, no rivals unless asked. */
        fun game(seed: Long, year: Int, dayLength: Int, rivals: Int = 0, size: Int = 256) =
            World(NewGame(size, size, seed, year, dayLength = dayLength, rivals = rivals))

        /** Plays a new game from [year] at [dayLength] for [n] calendar years. */
        fun run(seed: Long, year: Int, dayLength: Int, n: Int, rivals: Int = 0): Player =
            Player(game(seed, year, dayLength, rivals)).also { it.play(n) }
    }
}
