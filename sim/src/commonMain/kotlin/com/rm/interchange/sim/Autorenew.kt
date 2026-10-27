package com.rm.interchange.sim

/*
 * Autorenew, as OpenTTD has it. With it on, a company's vehicle is renewed with a new one of the same model once
 * it's [Company.renewMonths] work months from the end of its working life (before it if negative), when it's next in
 * a depot for a service, if that model is still on sale and the company keeps [Company.renewFloor] after paying. It
 * stays the same vehicle, so its orders, shared orders, name and cargo are kept; a train is renewed whole, engine and
 * cars. It costs what buying new would, less what the old one fetches. One due goes for a service after its next call, as a service due would, at most once a work month; one the company can't pay
 * for gets news once, till it's renewed. Players' vehicles not set to be serviced are never sent; rivals' trains on
 * lines of two or more, which aren't serviced, go in one at a time.
 */

object Autorenew {
    /** The choices of work months from the end of a vehicle's life, and of money kept in hand, in dollars. */
    val MONTHS = intArrayOf(-12, -6, -3, 0, 3, 6, 12)
    val FLOORS = longArrayOf(0, 10_000, 25_000, 50_000, 100_000, 250_000, 500_000, 1_000_000)
    const val MONTHS_DEFAULT = -6

    /** Vehicle kinds, for [News.RENEW_FAILED]'s [News.about]: the kind shifted up by [KIND_SHIFT], with the vehicle's id. */
    const val TRAIN = 0
    const val ROAD = 1
    const val SHIP = 2
    const val AIRCRAFT = 3
    const val KIND_SHIFT = 24
}

/** A work day long before the game began, for a vehicle never sent in to be renewed. */
const val NEVER_ASKED = Int.MIN_VALUE / 2

/** Whether [e] can be bought this year. */
internal fun World.onSale(e: Engine): Boolean {
    val year = Calendar.year(startYear, day)
    return e.enabled && year >= e.from && year < e.until
}

/** Whether [owner]'s vehicle built on work day [built], with a working life of [life] work years, is due renewing. */
internal fun World.renewDue(owner: Int, built: Int, life: Int): Boolean {
    val c = companies.getOrNull(owner) ?: return false
    return c.autorenew && !c.gone && workDay - built >= life * 365 + c.renewMonths * WORK_MONTH
}

internal fun World.renewDue(t: Train) = t.wreckedFor == 0 && renewDue(t.owner, t.built, t.life) &&
    t.formation.all { onSale(roster[it.model]) }
internal fun World.renewDue(v: RoadVehicle) = v.wreckedFor == 0 && renewDue(v.owner, v.built, v.info.life) && onSale(v.info)
internal fun World.renewDue(v: Ship) = renewDue(v.owner, v.built, v.info.life) && onSale(v.info)
internal fun World.renewDue(v: Aircraft) = v.wreckedFor == 0 && renewDue(v.owner, v.built, v.info.life) && onSale(v.info)

/** What renewing costs: the price new less what the old one fetches. */
internal fun World.renewCost(t: Train) = price(t.price) - value(t)
internal fun World.renewCost(v: RoadVehicle) = price(v.price) - roadValue(v)
internal fun World.renewCost(v: Ship) = price(v.price) - craftValue(v.price, v.built, v.info.life)
internal fun World.renewCost(v: Aircraft) = price(v.price) - craftValue(v.price, v.built, v.info.life)

/** Whether [c] can pay [cost] and keep its floor. */
private fun affords(c: Company, cost: Long) = c.money - cost >= c.renewFloor

/** News, once till it's renewed, that [v], of [kind], couldn't be for want of money. */
internal fun World.renewFailed(v: Carrier, kind: Int) {
    if (v.renewFailed) return
    v.renewFailed = true
    tell(News.RENEW_FAILED, kind shl Autorenew.KIND_SHIFT or v.id)
}

/**
 * As [v], of [kind], leaves a stop: whether to send it for a service to be renewed, as a service due then would be.
 * Once a work month at most, if it's due ([due]), set to be serviced ([service]) and its company can pay [cost]
 * now; news if it can't. A rival's vehicle losing money is left to be sold.
 */
internal inline fun World.renewNow(v: Carrier, kind: Int, due: Boolean, service: Boolean, built: Int, profitLastYear: Long, cost: () -> Long): Boolean {
    if (!service || workDay - v.renewAsked < WORK_MONTH || !due) return false
    if (!human(v.owner) && lostAWorkYear(built, v.profitThisYear, profitLastYear)) return false
    val c = companies.getOrNull(v.owner) ?: return false
    v.renewAsked = workDay
    if (c.money - cost() >= c.renewFloor) return true
    renewFailed(v, kind)
    return false
}

internal fun World.renewNow(t: Train) = renewNow(t, Autorenew.TRAIN, renewDue(t), t.service, t.built, t.profitLastYear) { renewCost(t) }
internal fun World.renewNow(v: RoadVehicle) = renewNow(v, Autorenew.ROAD, renewDue(v), v.service, v.built, v.profitLastYear) { renewCost(v) }
internal fun World.renewNow(v: Ship) = renewNow(v, Autorenew.SHIP, renewDue(v), v.service, v.built, v.profitLastYear) { renewCost(v) }
internal fun World.renewNow(v: Aircraft) = renewNow(v, Autorenew.AIRCRAFT, renewDue(v), v.service, v.built, v.profitLastYear) { renewCost(v) }

/**
 * [v], of [kind], in a depot for a service and due renewing: paid for at [cost] if its company keeps its floor,
 * or news that it couldn't be. Whether it was; the caller makes it new.
 */
private fun World.payRenewal(v: Carrier, kind: Int, cost: Long): Boolean {
    val c = companies.getOrNull(v.owner) ?: return false
    if (!affords(c, cost)) {
        renewFailed(v, kind)
        return false
    }
    spend(c, Books.NEW_TRAINS, cost)
    v.renewFailed = false
    v.renewAsked = NEVER_ASKED
    return true
}

/** Train [t], in for a service: renewed if it's due and its company can pay. */
internal fun World.autorenew(t: Train) {
    if (!renewDue(t) || !payRenewal(t, Autorenew.TRAIN, renewCost(t))) return
    t.built = workDay
    t.serviced = workDay
    t.reliability = t.newReliability
}

internal fun World.autorenew(v: RoadVehicle) {
    if (!renewDue(v) || !payRenewal(v, Autorenew.ROAD, renewCost(v))) return
    v.built = workDay
    v.serviced = workDay
    v.reliability = v.info.reliability * 100
}

internal fun World.autorenew(v: Ship) {
    if (!renewDue(v) || !payRenewal(v, Autorenew.SHIP, renewCost(v))) return
    v.built = workDay
    v.serviced = workDay
    v.reliability = v.info.reliability * 100
}

internal fun World.autorenew(v: Aircraft) {
    if (!renewDue(v) || !payRenewal(v, Autorenew.AIRCRAFT, renewCost(v))) return
    v.built = workDay
    v.serviced = workDay
    v.reliability = v.info.reliability * 100
}

/** Sets [company]'s autorenew from [c]: on or off, its work months from a vehicle's life, and the money it keeps. */
internal fun World.setAutorenew(company: Company, c: Command.SetAutorenew) {
    company.autorenew = c.on
    company.renewMonths = c.months.coerceIn(-12, 12)
    company.renewFloor = c.floor.coerceIn(0, 1_000_000_000_000)
}
