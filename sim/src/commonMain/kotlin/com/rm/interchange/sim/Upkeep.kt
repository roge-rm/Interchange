package com.rm.interchange.sim

/*
 * Trains wear and age. Each one's reliability, in hundredths of a percent,
 * falls a little every day it's out, and the lower it is the likelier a
 * breakdown, which stands it for a day or two; steam breaks down more in deep
 * cold. Once [SERVICE_DAYS] have gone by, a train set to be serviced finishes
 * its leg, calls at its next stop, then chooses a depot on the way to its
 * following stop, and comes out as good as its age lets it
 * be. One that's gone twice as long, calling nowhere, goes from where it is.
 * Past its engine's life it's never as good again, and is worth less;
 * renewing it in the depot makes it new.
 */

/** Days between services, and how much reliability a day out takes, in hundredths of a percent. */
const val SERVICE_DAYS = 180
const val WEAR_A_DAY = 8
/** Reliability lost for each year past its engine's life, and the least it can be. */
const val OLD_AGE_LOSS = 400
const val LEAST_RELIABLE = 2000

/** Whole work years since train [t] was built or renewed. */
fun World.age(t: Train) = (workDay - t.built).coerceAtLeast(0) / 365

/** The best train [t] can be, in hundredths of a percent: its engine's reliability new, less for each year past its life. */
fun World.bestReliability(t: Train): Int =
    (t.engineInfo.reliability * 100 - maxOf(0, age(t) - t.engineInfo.life) * OLD_AGE_LOSS).coerceAtLeast(LEAST_RELIABLE)

/** What train [t] fetches sold: half its price new, less as it ages, never under a twentieth. */
fun World.value(t: Train): Long = if (t.wreckedFor > 0) 0 else price(t.price) * maxOf(10, 100 - 100 * age(t) / t.engineInfo.life.coerceAtLeast(1)) / 200

/** Each day: wear, breakdowns, trips to the depot for a service, and news of trains grown old. */
internal fun World.trainsDay() {
    for (t in trains) {
        if (t == null || t.inDepot || t.wreckedFor > 0) continue
        t.reliability = (t.reliability - WEAR_A_DAY).coerceAtLeast(LEAST_RELIABLE / 2)
        if (t.running && t.brokenFor == 0 && t.waiting == 0) {
            var odds = 10_000 - t.reliability
            if (t.engineInfo.traction == Traction.STEAM) {
                val x = stepTile(t.steps.last()) % width
                val y = stepTile(t.steps.last()) / width
                if (skyAt(x, y).temperature < DEEP_COLD) odds *= 2
            }
            if (rng.below(BREAKDOWN_ODDS) < odds) {
                t.brokenFor = Calendar.TICKS_PER_DAY * (1 + rng.below(2))
                t.breakdowns++
            }
        }
        // Due ones go after their next call; this catches one that's called nowhere in twice the time.
        if (t.running && !t.toDepot && overdue(t.service, t.serviced) && tick >= t.noDepotUntil && hasDepot(t.owner, Depots.RAIL)) forService(t)
        // Once, on the work day it comes of age.
        if (workDay - t.built == t.engineInfo.life * 365) tell(News.TRAIN_OLD, t.id)
    }
}

/** Due a service: set to be serviced and [SERVICE_DAYS] work days since the last. */
fun World.due(service: Boolean, serviced: Int) = service && workDay - serviced >= SERVICE_DAYS

/** Gone twice as long as [due] without a service, so it goes from wherever it is. */
fun World.overdue(service: Boolean, serviced: Int) = service && workDay - serviced >= SERVICE_DAYS * 2

/** Whether company [owner] has a depot of [kind] standing. */
fun World.hasDepot(owner: Int, kind: Int) = depots.any { it != null && it.owner == owner && it.kind == kind }

/** Sends train [t] off for a service, choosing a depot on the way to its next order. */
internal fun World.forService(t: Train) {
    t.toDepot = true
    t.servicing = true
    t.route = null
}

/** Whether train [t], heading for a depot, may take any of its company's: off for a service, or with its home gone. */
internal fun World.anyDepot(t: Train) = t.toDepot && (t.servicing || depots.getOrNull(t.depot) == null)

/** Train [t] found no depot to reach for its service: on with its orders, and the daily check leaves it a while. */
internal fun World.noDepot(t: Train) {
    t.toDepot = false
    t.servicing = false
    t.noDepotUntil = tick + NO_DEPOT_RETRY
}

/**
 * Depot [d] is gone: each vehicle based there takes the nearest of its company's depots of that kind as home,
 * or none, and one on its way there looks for its way afresh.
 */
internal fun World.depotGone(d: Depot) {
    fun dist(e: Depot) = kotlin.math.abs(e.tile % width - d.tile % width) + kotlin.math.abs(e.tile / width - d.tile / width)
    fun nearest(owner: Int, fits: (Depot) -> Boolean = { true }): Int =
        depots.filter { it != null && it.id != d.id && it.owner == owner && it.kind == d.kind && fits(it) }.minByOrNull { dist(it!!) }?.id ?: -1
    when (d.kind) {
        Depots.RAIL -> for (t in trains) if (t != null && t.depot == d.id) { t.depot = nearest(t.owner); if (t.toDepot) t.route = null }
        Depots.ROAD -> for (v in roadVehicles) if (v != null && v.depot == d.id) { v.depot = nearest(v.owner); if (v.toDepot) v.route = null }
        Depots.SHIPYARD -> for (v in ships) if (v != null && v.depot == d.id) { v.depot = nearest(v.owner); if (v.toDepot) v.route = null }
        Depots.HANGAR -> for (v in aircraft) if (v != null && v.depot == d.id) {
            v.depot = nearest(v.owner) { e -> airports.any { it != null && it.depot == e.id && it.serves(v.info) } }
        }
    }
}

/** A train in for a service comes out as good as it can be, and goes back to its orders. */
internal fun World.serviced(t: Train) {
    t.reliability = bestReliability(t)
    t.serviced = workDay
    t.servicing = false
    t.running = true
}

/** Ticks the daily check for a long overdue service waits after finding no depot to reach. */
const val NO_DEPOT_RETRY = Calendar.TICKS_PER_DAY * 30L
/** Below this, in tenths of a degree, steam engines break down twice as often. */
const val DEEP_COLD = -250
/** A day's chance of a breakdown is (100% less reliability) in this. */
const val BREAKDOWN_ODDS = 150_000
