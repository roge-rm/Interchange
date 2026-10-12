package com.rm.interchange.sim

/*
 * Trains wear and age. Each one's reliability, in hundredths of a percent,
 * falls a little every day it's out, and the lower it is the likelier a
 * breakdown, which stands it for a day or two; steam breaks down more in deep
 * cold. Every [SERVICE_DAYS] a train set to be serviced goes to its depot and
 * comes out as good as its age lets it be. Past its engine's life it's never
 * as good again, and is worth less; renewing it in the depot makes it new.
 */

/** Days between services, and how much reliability a day out takes, in hundredths of a percent. */
const val SERVICE_DAYS = 180
const val WEAR_A_DAY = 8
/** Reliability lost for each year past its engine's life, and the least it can be. */
const val OLD_AGE_LOSS = 400
const val LEAST_RELIABLE = 2000

/** Whole years since train [t] was built or renewed. */
fun World.age(t: Train) = (day - t.built).coerceAtLeast(0) / 365

/** The best train [t] can be, in hundredths of a percent: its engine's reliability new, less for each year past its life. */
fun World.bestReliability(t: Train): Int =
    (t.engineInfo.reliability * 100 - maxOf(0, age(t) - t.engineInfo.life) * OLD_AGE_LOSS).coerceAtLeast(LEAST_RELIABLE)

/** What train [t] fetches sold: half its price new, less as it ages, never under a twentieth. */
fun World.value(t: Train): Long = price(t.price) * maxOf(10, 100 - 100 * age(t) / t.engineInfo.life.coerceAtLeast(1)) / 200

/** Each day: wear, breakdowns, trips to the depot for a service, and news of trains grown old. */
internal fun World.trainsDay() {
    for (t in trains) {
        if (t == null || t.inDepot) continue
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
        if (t.service && t.running && !t.toDepot && day - t.serviced >= SERVICE_DAYS && depots.getOrNull(t.depot) != null) {
            t.toDepot = true
            t.servicing = true
            t.route = null
        }
        if (day - t.built == t.engineInfo.life * 365) news += News(News.TRAIN_OLD, t.id, tick)
    }
}

/** A train in for a service comes out as good as it can be, and goes back to its orders. */
internal fun World.serviced(t: Train) {
    t.reliability = bestReliability(t)
    t.serviced = day
    t.servicing = false
    t.running = true
}

/** Below this, in tenths of a degree, steam engines break down twice as often. */
const val DEEP_COLD = -250
/** A day's chance of a breakdown is (100% less reliability) in this. */
const val BREAKDOWN_ODDS = 150_000
