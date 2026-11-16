package com.rm.interchange.sim

/** How a cargo is handled at a station, for handling time and transfer charges. */
object Handling {
    const val PEOPLE = 0
    const val BULK = 1
    const val GENERAL = 2
    const val LIQUID = 3
    const val LIVESTOCK = 4
    const val COUNT = 5
    /** The names packs give them in a cargo's `handling` field. */
    val KEYS = listOf("people", "bulk", "general", "liquid", "livestock")

    /** What calls: the [RATE] row. */
    const val RAIL = 0
    const val ROAD = 1
    const val WATER = 2
    const val AIR = 3

    /** Units a station moves a work day, by mode and class, loading and unloading counted alike. */
    val RATE = arrayOf(
        intArrayOf(800, 400, 300, 600, 200),
        intArrayOf(200, 100, 80, 150, 50),
        intArrayOf(600, 600, 300, 600, 150),
        intArrayOf(800, 100, 100, 100, 50),
    )

    /** What a unit set down to transfer costs, by class, in cents before fares rise. */
    val CHARGE = intArrayOf(50, 100, 200, 100, 200)

    /** Times the waiting cap with storage for the cargo, and days unstored perishable freight waits before it gives up. */
    const val STORE_TIMES = 4
    const val PERISH_DAYS = 30
}

/** A station's terminal equipment, as bits of [Station.equipment]. */
object Equipment {
    /** Doubles the rate for general freight. */
    const val CRANE = 0
    /** Doubles the rate for bulk. */
    const val CONVEYOR = 1
    /** Stores bulk and general freight. */
    const val WAREHOUSE = 2
    /** Stores liquids. */
    const val TANK_FARM = 3
    /** Stores freight that spoils. */
    const val COLD_STORE = 4
    const val COUNT = 5

    /** What each costs to build, and its upkeep a month of work, in dollars before inflation. */
    val COST = longArrayOf(8_000, 6_000, 10_000, 10_000, 12_000)
    val UPKEEP = longArrayOf(80, 60, 100, 100, 120)

    fun has(bits: Int, part: Int) = bits and (1 shl part) != 0
}

/** Adds or takes away a piece of terminal equipment at one of the company's stations; taking it away pays half back. */
internal fun World.equipStation(company: Company, c: Command.Equip) {
    if (!settings.handling || c.part !in 0 until Equipment.COUNT) return
    val s = stations.getOrNull(c.station) ?: return
    if (s.owner != company.id || s.waypoint) return
    val bit = 1 shl c.part
    if (c.remove) {
        if (s.equipment and bit == 0) return
        s.equipment = s.equipment and bit.inv()
        scrap(company, Equipment.COST[c.part])
    } else {
        val cost = price(Equipment.COST[c.part])
        if (s.equipment and bit != 0 || company.money < cost) return
        spend(company, Books.CONSTRUCTION, cost)
        s.equipment = s.equipment or bit
    }
}

/** Drops the equipment of every station [company] owns, as its stations change hands or it starts again. */
internal fun World.dropEquipmentOf(company: Int) {
    for (s in stations) if (s != null && s.owner == company) s.equipment = 0
}

/** Equipment upkeep a work day for station [s], in tenths of a cent, as [upkeepDay] counts. */
internal fun stationEquipmentUpkeep(s: Station): Long {
    var n = 0L
    for (p in 0 until Equipment.COUNT) if (Equipment.has(s.equipment, p)) n += Equipment.UPKEEP[p] * 1_000 / WORK_MONTH
    return n
}

/** What calls at stations by [t]'s kind, a [Handling] mode. */
internal fun handlingMode(t: Carrier) = when (t) {
    is RoadVehicle -> Handling.ROAD
    is Ship -> Handling.WATER
    is Aircraft -> Handling.AIR
    else -> Handling.RAIL
}

/** Units of class [cl] station [s] moves a work day for a vehicle of [mode]. */
fun handlingRate(s: Station, mode: Int, cl: Int): Int {
    val r = Handling.RATE[mode][cl]
    val double = (cl == Handling.GENERAL && Equipment.has(s.equipment, Equipment.CRANE)) ||
        (cl == Handling.BULK && Equipment.has(s.equipment, Equipment.CONVEYOR))
    return if (double) r * 2 else r
}

/** Whether station [s] stores cargo [c], so more of it waits and none gives up. */
internal fun World.stores(s: Station, c: Int): Boolean {
    if (!settings.handling || s.equipment == 0) return false
    if (!roster.cargo.keeps[c]) return Equipment.has(s.equipment, Equipment.COLD_STORE)
    return when (roster.cargo.handling[c]) {
        Handling.BULK, Handling.GENERAL -> Equipment.has(s.equipment, Equipment.WAREHOUSE)
        Handling.LIQUID -> Equipment.has(s.equipment, Equipment.TANK_FARM)
        else -> false
    }
}

/** The most of cargo [c] that waits at [s]. */
internal fun World.waitCap(s: Station, c: Int) = if (stores(s, c)) MAX_WAITING * Handling.STORE_TIMES else MAX_WAITING

/** Ticks cargo [c] waits at [s] before it gives up, or -1 if it never does. */
internal fun World.giveUpTicks(s: Station, c: Int): Long {
    if (!settings.handling) return GIVE_UP_DAYS * Calendar.TICKS_PER_DAY.toLong()
    if (stores(s, c)) return -1
    val days = if (!roster.cargo.keeps[c] && roster.cargo.handling[c] != Handling.PEOPLE) Handling.PERISH_DAYS else GIVE_UP_DAYS
    return days * Calendar.TICKS_PER_DAY.toLong()
}

/**
 * What one call moves, for its handling time, and the lots it moved, so each gets the call's handling time once it's
 * known. Only with handling on.
 */
internal class CallTally {
    val moved = IntArray(Handling.COUNT)
    val lots = ArrayList<Packet>()
    val counts = ArrayList<Int>()
    /** Lots delivered here, with where they came from, for the flow stats. */
    val delivered = ArrayList<Packet>()
    val deliveredFrom = ArrayList<Int>()
    val deliveredCargo = ArrayList<Int>()

    fun move(cl: Int, p: Packet, n: Int) {
        moved[cl] += n
        lots += p
        counts += n
    }
}

/** Ticks [tally]'s moves take at [s] for [t]. */
internal fun World.handlingTicks(t: Carrier, s: Station, tally: CallTally): Int {
    val mode = handlingMode(t)
    var sum = 0L
    for (cl in 0 until Handling.COUNT) if (tally.moved[cl] > 0) sum += tally.moved[cl].toLong() * Calendar.TICKS_PER_DAY * 1_000 / handlingRate(s, mode, cl)
    return ((sum + 999) / 1_000).toInt()
}

/** Gives the call's [ticks] to each lot moved and puts what was delivered into the flow stats. */
internal fun World.settleCall(tally: CallTally, ticks: Int, at: Int) {
    for (i in tally.lots.indices) tally.lots[i].handled += ticks.toLong() * tally.counts[i]
    for (i in tally.delivered.indices) {
        val p = tally.delivered[i]
        val key = flowKey(tally.deliveredCargo[i], tally.deliveredFrom[i], at)
        val f = flowsMonth.getOrPut(key) { LongArray(FLOW_FIELDS) }
        f[0] += p.count
        f[1] += p.count * (tick - p.since + ticks) + p.waited
        f[2] += p.handled
        f[3] += p.charged
    }
}

/** A flow stat's key: cargo, origin and destination station. */
internal fun flowKey(c: Int, from: Int, to: Int) = (c.toLong() shl 32) or linkKey(from, to)

/** Units, unit-ticks of the whole trip, unit-ticks handled and cents charged, in a flow stat. */
const val FLOW_FIELDS = 4

/** Forgets flows from or to station [id]. */
internal fun World.dropFlows(id: Int) {
    if (flowsMonth.isEmpty() && flowsLast.isEmpty()) return
    val gone = { k: Long -> ((k shr 16) and 0xffff).toInt() == id || (k and 0xffff).toInt() == id }
    flowsMonth.keys.removeAll(gone)
    flowsLast.keys.removeAll(gone)
}

/** The first of each month: this month's flows become last month's. */
internal fun World.flowsMonthEnd() {
    if (flowsMonth.isEmpty() && flowsLast.isEmpty()) return
    flowsLast.clear()
    flowsLast.putAll(flowsMonth)
    flowsMonth.clear()
}

/** The charge for [n] of class [cl] set down to transfer, in cents after fares rise. */
internal fun World.transferCharge(cl: Int, n: Int): Long = pay(n.toLong() * Handling.CHARGE[cl])

/**
 * A station's handling for the card: rates a work day by mode and class (rows by [Handling] mode), its equipment
 * bits, and each part's cost now and upkeep a month now, by [Equipment] part.
 */
class HandlingView(val rates: Array<IntArray>, val equipment: Int, val cost: LongArray, val upkeep: LongArray)

/** Station [id]'s handling, or null without handling. */
fun World.handlingView(id: Int): HandlingView? {
    if (!settings.handling) return null
    val s = stations.getOrNull(id) ?: return null
    return HandlingView(
        Array(4) { m -> IntArray(Handling.COUNT) { handlingRate(s, m, it) } }, s.equipment,
        LongArray(Equipment.COUNT) { price(Equipment.COST[it]) }, LongArray(Equipment.COUNT) { price(Equipment.UPKEEP[it]) },
    )
}

/**
 * A flow delivered last month from [from] to [to] of [cargo]: [units], the average trip in ticks (waiting, handling
 * and on board), the handling share of it in percent, and the charges a unit in cents.
 */
class FlowView(val cargo: Int, val from: Int, val to: Int, val units: Int, val tripTicks: Long, val handlingShare: Int, val costCents: Long)

/** Last month's flows from station [id], or to it with [to], the most units first. */
fun World.flowsAt(id: Int, to: Boolean = false): List<FlowView> {
    val out = ArrayList<FlowView>()
    for ((k, f) in flowsLast) {
        val a = ((k shr 16) and 0xffff).toInt()
        val b = (k and 0xffff).toInt()
        if ((if (to) b else a) != id || f[0] <= 0) continue
        val trip = f[1] / f[0]
        val share = if (f[1] > 0) (f[2] * 100 / f[1]).toInt().coerceIn(0, 100) else 0
        out += FlowView((k shr 32).toInt(), a, b, f[0].toInt(), trip, share, f[3] / f[0])
    }
    out.sortWith(compareByDescending<FlowView> { it.units }.thenBy { it.cargo }.thenBy { it.from }.thenBy { it.to })
    return out
}
