package com.rm.interchange.sim

import java.util.IdentityHashMap

/**
 * A scripted player for balance tests: a normal new game, played by a simple plan through the same commands a
 * player sends. It borrows as it needs, opens a bus line between the biggest towns near its home (the biggest town),
 * a railway between towns or from a mine to its taker once it can pay for one, and truck lines, adds vehicles where
 * people or cargo wait, sells what loses and repays the loan when it's well off. The planning is the rivals' own
 * ([newRoute], [newRailRoute], [newFreightRoute], [growRoute], [sellLosers]), run for company 0.
 *
 * It looks over its business every [thinkDays] work days, so it plays as often against the work done at any day
 * length, as a person playing would.
 *
 * With [aim] on it goes after the world's goals first, a way for each kind of goal: a railway or bus line between
 * towns to connect, trucks or a railway from the nearest makers of a cargo to deliver (feeding a mill first if only
 * a mill makes it), bus lines out of a town to grow, with what it needs when towns grow only supplied, and buildings
 * paid for there when well off. Those routes are kept and grown even when they lose. [style] varies its choices:
 * 0 rail first, 1 road first, 2 rail first with the goals taken last to first.
 *
 * While a value goal is open it also goes for money: more routes of its own, sooner, of the kind that pays back
 * fastest in the era by [Balance]'s reference routes (passenger trains between towns before 1940, buses after), and
 * on borrowed money as long as last year made a profit.
 */
class Player(val world: World, val thinkDays: Int = 30, val mostRoutes: Int = 12, val aim: Boolean = false, val style: Int = 0) {
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

    /** Routes it built for goals, kept apart from [plan] so they're grown and kept even when they lose. */
    val aims = Rival(0, plan.home)
    /** What it built for goals and when, for the report. */
    val aimLog = ArrayList<String>()
    private val aimFor = HashMap<RivalRoute, Int>()
    /** By goal: the work day it next tries a route for it, the calendar day of its first route and the work day of its last. */
    private val aimWait = HashMap<Int, Int>()
    private val aimFirst = HashMap<Int, Int>()
    private val aimLast = HashMap<Int, Int>()
    private val aimFails = HashMap<Int, Int>()
    /** Goals it has tried to build for at all, by index. */
    val aimTried = HashSet<Int>()
    /** Times it went under and the bank started it again. */
    var restarts = 0
    /** Legs built, by [legKey]. */
    private val legs = HashSet<Long>()

    /**
     * Plays on for [n] calendar years, keeping each year's books in [years], or till [stop] says, calling [daily] each
     * new calendar day and [work] each new work day.
     */
    fun play(n: Int, stop: () -> Boolean = { false }, work: () -> Unit = {}, daily: () -> Unit = {}) {
        val w = world
        val end = Calendar.year(w.startYear, w.day) + n
        while (Calendar.year(w.startYear, w.day) < end && !stop()) {
            val before = w.day
            w.step()
            if (w.workDay >= nextThink && w.tick % Calendar.TICKS_PER_DAY == 0L) {
                nextThink = w.workDay + thinkDays
                think()
            }
            if (w.tick % Calendar.TICKS_PER_DAY == 0L) work()
            if (w.day != before) daily()
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
        // Gone under and started again by the bank: everything it had is gone, so it plans afresh.
        if ((aims.routes.isNotEmpty() || plan.routes.isNotEmpty()) && w.stations.none { it != null && it.owner == 0 }) {
            restarts++
            aimLog += "${Calendar.year(w.startYear, w.day)}: went under and started again"
            aims.routes.clear()
            plan.routes.clear()
            aimFor.clear()
            aimWait.clear()
            aimFirst.clear()
            aimLast.clear()
            legs.clear()
        }
        while (c.money < 0 && c.loan < w.maxLoan()) w.apply(Command.Loan(0, true))
        // Going for value, its first line of its own goes in before any for the other goals, to pay for them.
        val value = forValue()
        if (value && plan.routes.isEmpty()) openRoute()
        if (aim) aimAtGoals()
        if (value) sellWorkLosers()
        countPay()
        sellLosers()
        for (route in plan.routes.toList()) if (value && !route.rail) growValue(c, route) else w.growRoute(c, route)
        // Going for value, its own lines don't wait on the other goals' routes.
        if (plan.routes.size < (if (value) VALUE_ROUTES else mostRoutes) && newRouteDue() && (value || aimsSettled())) openRoute()
        // Well off: the loan goes back, keeping a quarter of what a company starts with now.
        val keep = w.price(Prices.era(Calendar.year(w.startYear, w.day))) / 4
        while (c.loan > 0 && c.money - Prices.LOAN_STEP > keep && plan.routes.size >= 1) {
            val was = c.loan
            w.apply(Command.Loan(0, false))
            if (c.loan == was) break
        }
    }

    /** Whether every open goal it can build for has a route or is waiting to try again, so other lines can open. */
    private fun aimsSettled(): Boolean {
        if (!aim) return true
        val w = world
        return w.goals.indices.none { i ->
            val g = w.goals[i]
            g.status == Goal.OPEN && g.kind != Goal.COMPANY_VALUE && g.kind != Goal.YEAR_PROFIT &&
                aims.routes.none { aimFor[it] == i } && w.workDay >= (aimWait[i] ?: 0)
        }
    }

    /** One new route for the open goals at a look, then more vehicles where goal routes leave cargo waiting. */
    private fun aimAtGoals() {
        val w = world
        val c = company
        val order = w.goals.indices.filter { w.goals[it].status == Goal.OPEN }.let { if (style == 2) it.reversed() else it }
        for (i in order) {
            val g = w.goals[i]
            if (w.workDay < (aimWait[i] ?: 0)) continue
            val mine = aims.routes.filter { aimFor[it] == i }
            if (!wants(g, i, mine)) continue
            val before = aims.routes.toList()
            aimTried += i
            val tried = build(g, i, mine)
            for (route in aims.routes) if (route !in before) {
                aimFor[route] = i
                aimLog += "${Calendar.year(w.startYear, w.day)}: goal ${i + 1} ${what(route)}"
            }
            if (tried == Tried.BUILT) {
                aimFirst.getOrPut(i) { w.day }
                aimLast[i] = w.workDay
                break
            }
            if (tried == Tried.POOR) break
            aimWait[i] = w.workDay + AIM_RETRY
            if (mine.isEmpty() && aimFails.merge(i, 1, Int::plus)!! <= 2) aimLog += "${Calendar.year(w.startYear, w.day)}: goal ${i + 1} nothing could be built"
        }
        // A goal met or failed lets its routes go to the plan, to be kept only if they pay.
        for (route in aims.routes.toList()) {
            if (w.goals.getOrNull(aimFor[route] ?: -1)?.status == Goal.OPEN) continue
            aims.routes -= route
            plan.routes += route
        }
        for (route in aims.routes) growAim(c, route)
        // A town to grow gets new buildings paid for when there's money to spare.
        for (g in w.goals) {
            if (g.status != Goal.OPEN || g.kind != Goal.TOWN_POP) continue
            val t = w.towns.getOrNull(g.town) ?: continue
            val cost = w.townActionCost(TownActions.FUND)
            if (c.money - c.loan > cost * 3 && w.townActionOpen(0, t, TownActions.FUND)) w.apply(Command.TownAction(0, t.id, TownActions.FUND))
        }
    }

    private fun what(r: RivalRoute): String {
        val w = world
        val kind = if (r.rail) "rail" else if (r.freight) "road" else "bus"
        fun place(id: Int, town: Boolean) = if (town) w.towns.getOrNull(id)?.name ?: "?" else w.industries.getOrNull(id)?.let { w.roster.industries[it.kind].key } ?: "?"
        val towns = r.cargo == Cargo.PASSENGERS || r.cargo == Cargo.MAIL
        return "$kind ${Cargo.IDS.getOrElse(r.cargo) { "${r.cargo}" }} ${place(r.townA, towns)} to ${place(r.townB, towns || stationIsTown(r.b, r.townB))}"
    }

    private fun stationIsTown(station: Int, town: Int): Boolean =
        world.stations.getOrNull(station)?.let { world.stationTown(it)?.id == town && world.stationIndustries[station]?.contains(town) != true } == true

    /**
     * Whether goal [g] wants another route: its first, or one more when it's behind, a work year after the last, and
     * only while the company's out of debt and made a profit last year.
     */
    private fun wants(g: Goal, i: Int, mine: List<RivalRoute>): Boolean {
        val w = world
        val c = company
        // A fifth of what it started with is kept back.
        if (c.money - c.loan < startMoney / 5) return false
        if (mine.isEmpty()) return g.kind != Goal.COMPANY_VALUE && g.kind != Goal.YEAR_PROFIT
        if (w.workDay - (aimLast[i] ?: 0) < 365 || mine.size >= 4) return false
        val profit = c.lastYear[Books.INCOME] + c.lastYear[Books.RUNNING] + c.lastYear[Books.UPKEEP] + c.lastYear[Books.INTEREST]
        if (c.money - c.loan <= 0 || profit <= 0) return false
        // About the calendar day the deadline year ends.
        val end = (g.deadline + 1 - w.startYear) * 36525 / 100
        return when (g.kind) {
            Goal.CONNECT -> g.count[0] * 365 < g.amount * Calendar.dayOfYear(w.startYear, w.day)
            Goal.DELIVER -> {
                val since = (w.day - (aimFirst[i] ?: w.day)).coerceAtLeast(1)
                g.count[0] + g.count[0] * (end - w.day) / since < g.amount
            }
            Goal.TOWN_POP -> true
            else -> false
        }
    }

    /** Tries one more route for goal [g]. */
    private fun build(g: Goal, i: Int, mine: List<RivalRoute>): Tried {
        val w = world
        return when (g.kind) {
            Goal.CONNECT -> {
                val a = w.towns[g.town]
                val b = w.towns[g.town2]
                // A railway and a bus line, the bus first for style 1, then more railways beside the first.
                val ways = if (style == 1) listOf({ bus(a, b) }, { townRail(a, b) }) else listOf({ townRail(a, b) }, { bus(a, b) })
                for (way in ways + (1..2).map { n -> { townRail(a, b, n = n) } }) {
                    val tried = way()
                    if (tried != Tried.CANT) return tried
                }
                Tried.CANT
            }
            Goal.DELIVER -> if (g.industry >= 0) deliver(w.industries[g.industry], g.cargo)
            else {
                val t = w.towns[g.town]
                if (g.cargo == Cargo.MAIL || g.cargo == Cargo.PASSENGERS) linesOut(t, mail = g.cargo == Cargo.MAIL) else supplyTown(t, g.cargo)
            }
            Goal.TOWN_POP -> {
                val t = w.towns[g.town]
                var tried = Tried.CANT
                if (w.settings.townGrowth == TownGrowth.SUPPLIED) {
                    for (need in w.townNeeds(t)) {
                        if (need.met || need.cargo == Cargo.PASSENGERS) continue
                        tried = if (need.cargo == Cargo.MAIL) linesOut(t, mail = true) else supplyTown(t, need.cargo)
                        if (need.heating && tried == Tried.CANT) tried = supplyTown(t, Cargo.FUEL)
                        if (tried != Tried.CANT) return tried
                    }
                }
                if (mine.count { !it.freight } < 3) linesOut(t, mail = false) else tried
            }
            else -> Tried.CANT
        }
    }

    /** Railway [n] between towns [a] and [b] for [cargo], passengers or mail, any that can be built. */
    private fun townRail(a: Town, b: Town, cargo: Int = Cargo.PASSENGERS, n: Int = 0): Tried {
        val w = world
        val key = legKey(a.id, b.id, cargo, LEG_TOWN_RAIL) or (n.toLong() shl 53)
        if (key in legs || w.triedLately(aims, key)) return Tried.CANT
        val serves = { s: Station, _: Boolean -> (w.stationPeople[s.id] ?: 0) > 0 }
        // Further out than a rival looks, as towns are built up in the middle.
        fun site(t: Town, o: Town) = RailSite(t.x - TOWN_RAIL_REACH, t.y - TOWN_RAIL_REACH, t.x + TOWN_RAIL_REACH, t.y + TOWN_RAIL_REACH, o.x, o.y)
        val (lo, hi) = if (cargo == Cargo.PASSENGERS) minOf(a.id, b.id) to maxOf(a.id, b.id) else a.id to b.id
        val t = w.tryRail(company, site(w.towns[lo], w.towns[hi]), site(w.towns[hi], w.towns[lo]), cargo, 30, lo, hi, serves, 0, loose = true) { aims.routes += it }
        return done(key, t)
    }

    /** A bus line between towns [a] and [b], any that can be built. */
    private fun bus(a: Town, b: Town): Tried {
        val w = world
        val key = legKey(a.id, b.id, Cargo.PASSENGERS, LEG_BUS)
        if (key in legs || w.triedLately(aims, key)) return Tried.CANT
        val model = w.bestModel(Cargo.PASSENGERS) ?: return Tried.CANT
        return done(key, w.tryRoute(aims, company, a, b, model, 0, loose = true))
    }

    /**
     * A line out of [t] to one of the nearest towns it has none to yet, by bus when near enough, else by rail; with
     * [mail], mail vans on a bus line it has into the town, else a mail railway from a town near into it.
     */
    private fun linesOut(t: Town, mail: Boolean): Tried {
        val w = world
        val near = w.towns.filter { it.id != t.id && chebyshev(it.x, it.y, t.x, t.y) in 6..LINE_FURTHEST }.sortedBy { chebyshev(it.x, it.y, t.x, t.y) }.take(4)
        if (mail) {
            val model = w.bestModel(Cargo.MAIL)
            val line = aims.routes.firstOrNull { !it.rail && !it.freight && (it.townA == t.id) != (it.townB == t.id) && aims.routes.none { m -> m.cargo == Cargo.MAIL && m.a == it.a && m.b == it.b } }
            if (line != null && model != null) {
                val route = RivalRoute(line.a, line.b, line.depot, line.townA, line.townB, Cargo.MAIL)
                aims.routes += route
                repeat(2) { w.buyAndStart(company, route, model) }
                return Tried.BUILT
            }
            for (o in near) {
                val road = chebyshev(o.x, o.y, t.x, t.y) <= BUS_FURTHEST && model != null
                val tried = if (road) bus(o, t).let { if (it == Tried.CANT) townRail(o, t, Cargo.MAIL) else it } else townRail(o, t, Cargo.MAIL)
                if (tried != Tried.CANT) return tried
            }
            return Tried.CANT
        }
        // Buses first where they reach, at any style: stops in the middle of town carry more of it than a terminus at its edge.
        for (o in near) {
            val road = chebyshev(o.x, o.y, t.x, t.y) <= BUS_FURTHEST
            val tried = if (road) bus(t, o).let { if (it == Tried.CANT) townRail(t, o) else it } else townRail(t, o)
            if (tried != Tried.CANT) return tried
        }
        return Tried.CANT
    }

    /** Cargo [cargo] to industry [to] from the nearest that make it, feeding a mill that makes it from what's brought. */
    private fun deliver(to: Industry, cargo: Int): Tried {
        val w = world
        for (from in makers(cargo, to.x, to.y, to.id)) {
            val kind = w.roster.industries[from.kind]
            if (kind.converts and (1 shl cargo) != 0) {
                val fed = feed(from)
                if (fed == Tried.BUILT || fed == Tried.POOR) return fed
                if (fed == Tried.CANT) continue
            }
            val tried = haul(from, to, cargo)
            if (tried != Tried.CANT) return tried
        }
        return Tried.CANT
    }

    /** Cargo [cargo] into town [t] by truck from the nearest that make it, feeding a mill first. */
    private fun supplyTown(t: Town, cargo: Int): Tried {
        val w = world
        for (from in makers(cargo, t.x, t.y, -1)) {
            val kind = w.roster.industries[from.kind]
            if (kind.converts and (1 shl cargo) != 0) {
                val fed = feed(from)
                if (fed == Tried.BUILT || fed == Tried.POOR) return fed
                if (fed == Tried.CANT) continue
            }
            val key = legKey(from.id, t.id, cargo, LEG_TOWN)
            if (key in legs || w.triedLately(aims, key)) continue
            val model = w.bestModel(cargo) ?: return Tried.CANT
            val tried = done(key, w.tryFreightToTown(aims, company, from, t, cargo, model))
            if (tried != Tried.CANT) return tried
        }
        return Tried.CANT
    }

    /**
     * What a mill [m] takes, from the nearest that make it: BUILT or POOR if it tried one now, null if it's fed
     * already, CANT if nothing could be.
     */
    private fun feed(m: Industry): Tried? {
        val w = world
        val kind = w.roster.industries[m.kind]
        if (legs.any { (it ushr 60).toInt().let { way -> way == LEG_ROAD || way == LEG_RAIL } && (it and 0xffffff).toInt() == m.id }) return null
        for (c in 0 until Cargo.COUNT) {
            if (!kind.takes(c)) continue
            for (from in makers(c, m.x, m.y, m.id).filter { w.roster.industries[it.kind].produces[c] > 0 }) {
                val tried = haul(from, m, c)
                if (tried != Tried.CANT) return tried
            }
        }
        return Tried.CANT
    }

    /** Open industries that make [cargo], nearest ([x], [y]) first, up to 4 within [MAKERS_FURTHEST] tiles, leaving out [not]. */
    private fun makers(cargo: Int, x: Int, y: Int, not: Int): List<Industry> {
        val w = world
        return w.industries.filter { !it.closed && it.id != not && w.roster.industries[it.kind].outputs and (1 shl cargo) != 0 }
            .filter { chebyshev(it.x, it.y, x, y) <= MAKERS_FURTHEST }.sortedBy { chebyshev(it.x, it.y, x, y) }.take(4)
    }

    /** [cargo] from [from] to [to], by rail or road as its style goes first, the other if that can't be built. */
    private fun haul(from: Industry, to: Industry, cargo: Int): Tried {
        val w = world
        val (fx, fy) = w.middle(from)
        val (tx, ty) = w.middle(to)
        val far = chebyshev(fx, fy, tx, ty) >= 16
        val ways = if (style == 1 || !far) listOf(LEG_ROAD, LEG_RAIL) else listOf(LEG_RAIL, LEG_ROAD)
        for (way in ways) {
            val key = legKey(from.id, to.id, cargo, way)
            if (key in legs) return Tried.CANT
            if (w.triedLately(aims, key)) continue
            val tried = if (way == LEG_RAIL) {
                val month = w.roster.industries[from.kind].produces[cargo] * from.level / 16
                val serves = { s: Station, first: Boolean -> w.stationIndustries[s.id]?.contains(if (first) from.id else to.id) == true }
                w.tryRail(company, w.railSite(from, tx, ty), w.railSite(to, fx, fy), cargo, maxOf(month / 2, 30), from.id, to.id, serves, 0, loose = true) { aims.routes += it }
            } else {
                val model = w.bestModel(cargo) ?: continue
                w.tryFreight(aims, company, from, to, cargo, model, 0, loose = true)
            }
            val d = done(key, tried)
            if (d != Tried.CANT) return d
        }
        return Tried.CANT
    }

    /** Keeps note of what came of trying leg [key]. */
    private fun done(key: Long, t: Tried): Tried {
        if (t == Tried.BUILT) legs += key
        if (t == Tried.CANT) world.giveUp(aims, key)
        return t
    }

    private fun legKey(from: Int, to: Int, cargo: Int, way: Int): Long =
        (way.toLong() shl 60) or (cargo.toLong() shl 48) or (from.toLong() shl 24) or to.toLong()

    /** Another vehicle on a goal's road route when a load and a half waits. Railways are single track, one train. */
    private fun growAim(c: Company, route: RivalRoute) {
        val w = world
        if (route.rail) return
        val a = w.stations.getOrNull(route.a) ?: return
        val b = w.stations.getOrNull(route.b) ?: return
        val on = w.roadVehicles.count { it != null && it.owner == 0 && onRoute(it, route) }
        if (on >= AIM_MOST) return
        val model = w.bestModel(route.cargo) ?: return
        val waiting = if (route.freight) a.waiting(route.cargo) else maxOf(a.waiting(route.cargo), b.waiting(route.cargo))
        if (waiting < (model.capacity * 3 / 2).coerceAtLeast(10)) return
        // Out of its own money, not borrowed.
        if (c.money - c.loan < w.price(model.cost) * 2) return
        w.buyAndStart(c, route, model)
    }

    /** As the rivals sell what loses, but never a goal route's vehicles. */
    private fun sellLosers() {
        val w = world
        if (aims.routes.isEmpty()) {
            w.sellLosers(plan, company)
            return
        }
        fun kept(v: Carrier) = aims.routes.any { onRoute(v, it) }
        val roads = w.roadVehicles.filter { it != null && it.owner == 0 && !it.toDepot && kept(it) }.map { it!! }
        val rails = w.trains.filter { it != null && it.owner == 0 && !it.toDepot && kept(it) }.map { it!! }
        w.sellLosers(plan, company)
        for (v in roads) if (v.toDepot && !v.servicing) w.apply(Command.RoadOrder(0, v.id, Command.TrainOrder.TO_DEPOT))
        for (t in rails) if (t.toDepot && !t.servicing) w.apply(Command.TrainOrder(0, t.id, Command.TrainOrder.TO_DEPOT))
    }

    /** Whether it's going for money, with a value goal open. */
    private fun forValue() = aim && world.goals.any { it.status == Goal.OPEN && it.kind == Goal.COMPANY_VALUE }

    /**
     * The first line at once; after that one half a work year after the last, once the company's out of debt. Going
     * for value, a quarter of a work year after the last while last year made a profit, borrowing up to half the
     * bank's limit.
     */
    private fun newRouteDue(): Boolean {
        if (plan.routes.isEmpty()) return true
        val c = company
        if (forValue()) {
            if (world.workDay - lastNew < VALUE_WAIT) return false
            if (c.money - c.loan > 0) return true
            return ownPay() > 0 && c.loan <= world.maxLoan() / 2
        }
        if (world.workDay - lastNew < 180) return false
        return c.money - c.loan > 0
    }

    /**
     * Going for value, its own routes' vehicles that lost money over their last work year go to the depot to be sold,
     * judged by this calendar year's books once a work year of it has gone by, so a long day length doesn't keep a
     * loser running for ten work years.
     */
    private fun sellWorkLosers() {
        val w = world
        val dl = w.settings.dayLength
        if (dl == 1) return
        val worked = Calendar.dayOfYear(w.startYear, w.day).toLong() * dl
        if (worked < 365) return
        fun old(built: Int) = w.workDay - built >= 365 + worked
        for (v in w.roadVehicles) {
            if (v == null || v.owner != 0 || v.inDepot || v.toDepot || !old(v.built) || v.profitThisYear >= 0) continue
            if (plan.routes.any { onRoute(v, it) }) w.apply(Command.RoadOrder(0, v.id, Command.TrainOrder.TO_DEPOT))
        }
        for (t in w.trains) {
            if (t == null || t.owner != 0 || t.inDepot || t.toDepot || !old(t.built) || t.profitThisYear >= 0) continue
            if (plan.routes.any { onRoute(t, it) }) w.apply(Command.TrainOrder(0, t.id, Command.TrainOrder.TO_DEPOT))
        }
    }

    /** Each road vehicle's profit so far, from its yearly books at each look, and that total at its looks over the last work year. */
    private class Pay(var year: Int, var seen: Long, var total: Long) { val looks = ArrayDeque<Pair<Int, Long>>() }
    private val pay = IdentityHashMap<RoadVehicle, Pay>()

    /** Brings [pay] up to date; a look comes at least once a calendar year, so at most one year end has gone by. */
    private fun countPay() {
        val w = world
        val year = Calendar.year(w.startYear, w.day)
        pay.keys.retainAll { v -> w.roadVehicles.getOrNull(v.id) === v }
        for (v in w.roadVehicles) {
            if (v == null || v.owner != 0) continue
            val p = pay.getOrPut(v) { Pay(year, 0, 0).also { it.looks.addLast(v.built to 0L) } }
            // A year end since the last look moved this year's books to last year's.
            p.total += if (year == p.year) v.profitThisYear - p.seen else v.profitLastYear - p.seen + v.profitThisYear
            p.year = year
            p.seen = v.profitThisYear
            p.looks.addLast(w.workDay to p.total)
            while (p.looks.size > 1 && w.workDay - p.looks[1].first >= 365) p.looks.removeFirst()
        }
    }

    /** What road vehicle [v] made over about its last work year, or since it was bought if that's sooner. */
    private fun lastWorkYear(v: RoadVehicle): Long = pay[v]?.let { it.total - it.looks.first().second } ?: 0

    /** What the vehicles on its own routes, not the goals', made this calendar year and last together. */
    private fun ownPay(): Long {
        val w = world
        var made = 0L
        for (v in w.roadVehicles) if (v != null && v.owner == 0 && plan.routes.any { onRoute(v, it) }) made += v.profitThisYear + v.profitLastYear
        for (t in w.trains) if (t != null && t.owner == 0 && plan.routes.any { onRoute(t, it) }) made += t.profitThisYear + t.profitLastYear
        return made
    }

    /**
     * Going for value, another vehicle on one of its own road routes when a load and a half waits and what's on it
     * made money over about the last work year, borrowing for it up to three quarters of the bank's limit.
     */
    private fun growValue(c: Company, route: RivalRoute) {
        val w = world
        val on = w.roadVehicles.filter { it != null && it.owner == 0 && onRoute(it, route) }.map { it!! }
        if (on.isEmpty() || on.size >= AIM_MOST || on.any { it.toDepot && !it.servicing }) return
        if (on.sumOf { lastWorkYear(it) } <= 0) return
        val a = w.stations.getOrNull(route.a) ?: return
        val b = w.stations.getOrNull(route.b) ?: return
        val model = w.bestModel(route.cargo) ?: return
        val waiting = if (route.freight) a.waiting(route.cargo) else maxOf(a.waiting(route.cargo), b.waiting(route.cargo))
        if (waiting < (model.capacity * 3 / 2).coerceAtLeast(10)) return
        val cost = w.price(model.cost)
        if (c.money < cost && c.loan + cost > w.maxLoan() * 3 / 4) return
        if (w.afford(c, cost)) w.buyAndStart(c, route, model)
    }

    /**
     * Buses first, then a railway, then trucks, round again; anything it can't do now, the next kind. Going for value,
     * the kind that pays best in the era first each time.
     */
    private fun openRoute() {
        val w = world
        val c = company
        val value = forValue()
        val kinds = if (!value) listOf(KIND_BUS, KIND_RAIL, KIND_TRUCK)
        else if (Calendar.year(w.startYear, w.day) < VALUE_BUS_YEAR) listOf(KIND_RAIL, KIND_BUS, KIND_TRUCK) else listOf(KIND_BUS, KIND_TRUCK, KIND_RAIL)
        val n = if (value) 0 else plan.routes.size
        for (k in kinds.indices) {
            val kind = kinds[(n + k) % kinds.size]
            val tried = when (kind) {
                KIND_BUS -> w.newRoute(plan, c)
                // A railway that's expected to pay for itself within ten work years.
                KIND_RAIL -> w.newRailRoute(plan, c, payback = 10)
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

        /** Work days between tries at a goal that couldn't be built for, and the most road vehicles on a goal's route. */
        private const val AIM_RETRY = 365
        /** Going for value: the most routes of its own, work days between new ones, and the year buses pay better than trains. */
        private const val VALUE_ROUTES = 20
        private const val VALUE_WAIT = 90
        private const val VALUE_BUS_YEAR = 1940
        private const val AIM_MOST = 10
        /** How far from a town's middle it looks for room for a terminus. */
        private const val TOWN_RAIL_REACH = 10
        /** The furthest it looks for a town to run a line to, for a bus line, and for what a goal's cargo comes from. */
        private const val LINE_FURTHEST = 200
        private const val BUS_FURTHEST = 64
        private const val MAKERS_FURTHEST = 260

        /** Ways a goal's leg goes, for [legKey]. */
        private const val LEG_ROAD = 0
        private const val LEG_RAIL = 1
        private const val LEG_BUS = 2
        private const val LEG_TOWN = 3
        private const val LEG_TOWN_RAIL = 4

        /** Plays a new game from [year] at [dayLength] for [n] calendar years. */
        fun run(seed: Long, year: Int, dayLength: Int, n: Int, rivals: Int = 0): Player =
            Player(game(seed, year, dayLength, rivals)).also { it.play(n) }
    }
}
