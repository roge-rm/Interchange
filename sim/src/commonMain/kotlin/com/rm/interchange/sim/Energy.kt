package com.rm.interchange.sim

/*
 * With [NewGame.energy], battery and hydrogen trains, road vehicles and ships carry a charge or a tank good for their
 * range, used a tile at a time as they run. A battery charges under wires on rail, standing at a station with a
 * charger, and in a depot; a service charges it full. A hydrogen tank fills when it's serviced or stands in a depot.
 * Either goes for a service early when low, and one run flat crawls at a quarter of its top speed. Aircraft keep to
 * their range in km a hop, as before, and fill up at each stop. Road vehicles with electric traction that aren't
 * streetcars run as battery ones. With the setting off none of this is used and battery and hydrogen run as diesel.
 */
object Energy {
    /** Charge in a tile of running, and per tick in a tile a work day of charging: both [Calendar.TICKS_PER_DAY]. */
    const val UNIT = Calendar.TICKS_PER_DAY
    /** Range in tiles when a pack gives none. */
    const val BATTERY_RANGE = 100
    const val HYDROGEN_RANGE = 400
    /** Tiles of charge a work day: rail under wires, standing at a station's charger, and stood in a depot. */
    const val WIRE_RATE = 40
    const val CHARGER_RATE = 60
    const val DEPOT_RATE = 120
    /** Below this much of its range, per mille, it goes for a service early. */
    const val LOW = 250
    /** Run flat, its top speed is divided by this. */
    const val CRAWL = 4
}

/** [e]'s store, [Traction.BATTERY] or [Traction.HYDROGEN], or -1 for none or with energy off; aircraft have none. */
fun World.storeOf(e: Engine): Int {
    if (!settings.energy || e.kind == VehicleKind.AIRCRAFT) return -1
    if (e.traction == Traction.BATTERY || e.traction == Traction.HYDROGEN) return e.traction
    return if (e.kind == VehicleKind.ROAD && e.traction == Traction.ELECTRIC && !e.tram) Traction.BATTERY else -1
}

/** [e]'s range on a full charge or tank, in tiles. */
fun rangeOf(e: Engine): Int = if (e.range > 0) e.range else if (e.traction == Traction.HYDROGEN) Energy.HYDROGEN_RANGE else Energy.BATTERY_RANGE

/** [v]'s store, as [storeOf]: a train has one only when all its engines have the same. */
fun World.storeOf(v: Carrier): Int = when (v) {
    is Train -> {
        val first = roster.getOrNull(v.locomotives.firstOrNull() ?: -1)?.let { storeOf(it) } ?: -1
        if (first >= 0 && v.locomotives.all { storeOf(roster[it]) == first }) first else -1
    }
    is RoadVehicle -> storeOf(v.info)
    is Ship -> storeOf(v.info)
    else -> -1
}

/** [v]'s range in tiles, the least of a train's engines. */
fun World.rangeOf(v: Carrier): Int = when (v) {
    is Train -> v.locomotives.minOfOrNull { rangeOf(roster[it]) } ?: 0
    is RoadVehicle -> rangeOf(v.info)
    is Ship -> rangeOf(v.info)
    else -> 0
}

/** [v]'s charge in [Energy.UNIT]s, no more than full. */
internal fun World.chargeOf(v: Carrier) = minOf(v.charge, rangeOf(v) * Energy.UNIT)

/** Run flat: crawls. Never with energy off. */
internal fun World.flat(v: Carrier) = v.charge <= 0 && settings.energy && storeOf(v) >= 0

/** Low enough to go for a service early. */
internal fun World.lowCharge(v: Carrier): Boolean {
    if (!settings.energy || storeOf(v) < 0) return false
    return chargeOf(v).toLong() * 1_000 < rangeOf(v).toLong() * Energy.UNIT * Energy.LOW
}

/**
 * Due a service, as [due] with [service] and [serviced], or low on charge with servicing on. A vehicle set not to be
 * serviced never goes for charge either: it runs flat and crawls.
 */
internal fun World.serviceDue(v: Carrier, service: Boolean, serviced: Int) = due(service, serviced) || service && lowCharge(v)

/** Whether [v] is set to be serviced. */
private fun serviceOn(v: Carrier) = when (v) {
    is Train -> v.service
    is RoadVehicle -> v.service
    is Ship -> v.service
    is Aircraft -> v.service
    else -> false
}

/** Top speed [top], in whatever units, as [v] runs it now: a quarter when flat. */
internal fun World.flatCap(v: Carrier, top: Int) = if (flat(v)) maxOf(1, top / Energy.CRAWL) else top

/** The tile [v] is on, -1 in a depot. */
private fun World.storeTile(v: Carrier): Int = when (v) {
    is Train -> if (v.inDepot || v.steps.isEmpty()) -1 else stepTile(v.steps.last())
    is RoadVehicle -> if (v.inDepot) -1 else roadStepTile(v.at)
    is Ship -> if (v.inDepot) -1 else shipTile(v)
    else -> -1
}

private fun World.inDepot(v: Carrier) = when (v) {
    is Train -> v.inDepot
    is RoadVehicle -> v.inDepot
    is Ship -> v.inDepot
    else -> false
}

private fun serviced(v: Carrier) = when (v) {
    is Train -> v.serviced
    is RoadVehicle -> v.serviced
    is Ship -> v.serviced
    else -> -1
}

/** The station [v] is standing at, or -1. */
private fun World.standingAt(v: Carrier): Station? = if (v.leftFrom >= 0 && v.leftAt < 0) stations.getOrNull(v.leftFrom) else null

/**
 * What [v], with store [store], takes on a tick now, in [Energy.UNIT]s, or [Int.MAX_VALUE] to fill up: in a depot
 * after a service today, or a hydrogen one in a depot at all; otherwise a battery under wires, at a charger or in a
 * depot.
 */
internal fun World.chargeRate(v: Carrier, store: Int): Int {
    val depot = inDepot(v)
    if (depot && (store == Traction.HYDROGEN || serviced(v) == workDay)) return Int.MAX_VALUE
    if (store != Traction.BATTERY) return 0
    if (depot) return Energy.DEPOT_RATE
    var rate = 0
    if (v is Train && v.steps.isNotEmpty() && wired(v.steps.last())) rate = Energy.WIRE_RATE
    val s = standingAt(v)
    if (s != null && Equipment.has(s.equipment, Equipment.CHARGER)) rate = maxOf(rate, Energy.CHARGER_RATE)
    return rate
}

/** Each tick with energy on, after vehicles move: charge used by tiles run and taken on, and news of any run flat. */
internal fun World.energyTick() {
    if (!settings.energy) return
    for (t in trains) if (t != null && t.wreckedFor == 0) storeStep(t, Autorenew.TRAIN)
    for (v in roadVehicles) if (v != null && v.wreckedFor == 0) storeStep(v, Autorenew.ROAD)
    for (v in ships) if (v != null) storeStep(v, Autorenew.SHIP)
}

private fun World.storeStep(v: Carrier, kind: Int) {
    val store = storeOf(v)
    if (store < 0) return
    val full = rangeOf(v) * Energy.UNIT
    val was = minOf(v.charge, full)
    var c = was
    val tile = storeTile(v)
    // A tile a change of tile: a train turning round moves its nose its length at once.
    if (tile >= 0 && v.chargeTile >= 0 && tile != v.chargeTile) c -= Energy.UNIT
    v.chargeTile = tile
    val rate = chargeRate(v, store)
    c = if (rate == Int.MAX_VALUE) full else (c + rate).coerceIn(0, full)
    if (c == 0 && was > 0) tell(News.RAN_FLAT, kind shl Autorenew.KIND_SHIFT or v.id)
    v.charge = c
}

/** Mixes charge into the checksum, with energy on only, so older games keep theirs. */
internal fun World.energyChecksum(mix: (Long) -> Unit) {
    if (!settings.energy) return
    mix(901L)
    for (list in listOf<List<Carrier?>>(trains, roadVehicles, ships)) for (v in list) if (v != null && v.charge != Int.MAX_VALUE) {
        mix(v.id.toLong()); mix(v.charge.toLong()); mix(v.chargeTile.toLong())
    }
    for (s in stations) if (s != null && Equipment.has(s.equipment, Equipment.CHARGER)) mix(s.id.toLong())
}

/**
 * A battery or hydrogen vehicle's store for the card: [store] a [Traction] kind, [charge] and [range] in tiles,
 * [flat] crawling, [charging] taking on charge or fuel now, and [check] its orders against its range.
 */
class EnergyView(val store: Int, val charge: Int, val range: Int, val flat: Boolean, val charging: Boolean, val check: RangeCheck)

/**
 * Orders against a range: the longest run in tiles from one place it charges to the next, round the orders, and
 * whether the orders have any; with none, [longest] is the whole round. Places it charges: a depot order (one to be
 * serviced if due only with servicing on, as low charge makes it due), and for a battery a station with a charger, or
 * for rail one with wires over a platform. A hydrogen one with servicing on fills at its services whenever it runs
 * low, so only its longest single leg counts. Straight-line tiles across and down.
 */
class RangeCheck(val longest: Int, val range: Int, val charges: Boolean) {
    /** Longer than the range: worth a warning. */
    val short get() = longest > range
}

/** [v]'s store for the card, or null for none or with energy off. Sim thread. */
fun World.energyView(v: Carrier): EnergyView? {
    val store = storeOf(v)
    if (store < 0) return null
    val range = rangeOf(v)
    val rate = chargeRate(v, store)
    return EnergyView(store, chargeOf(v) / Energy.UNIT, range, v.charge <= 0, rate > 0 && chargeOf(v) < range * Energy.UNIT, rangeCheck(v.orders.toIntArray(), store, range, v is Train, serviceOn(v)))
}

/** [v]'s orders against its range, or null for none or with energy off. */
fun World.rangeCheck(v: Carrier): RangeCheck? {
    val store = storeOf(v)
    return if (store < 0) null else rangeCheck(v.orders.toIntArray(), store, rangeOf(v), v is Train, serviceOn(v))
}

/** [orders] for a vehicle of [store] with [range], on [rail], set to be serviced or not by [service], against its range. */
fun World.rangeCheck(orders: IntArray, store: Int, range: Int, rail: Boolean, service: Boolean = true): RangeCheck {
    val xs = ArrayList<Int>()
    val ys = ArrayList<Int>()
    val charges = ArrayList<Boolean>()
    for (o in orders) {
        val d = depots.getOrNull(Order.depot(o))
        val s = stations.getOrNull(Order.station(o))
        when {
            d != null -> { xs += d.tile % width; ys += d.tile / width; charges += service || Order.depotKind(o) != Order.DEPOT_SERVICE }
            s != null -> {
                xs += s.signX; ys += s.signY
                charges += store == Traction.BATTERY && (Equipment.has(s.equipment, Equipment.CHARGER) || rail && s.tiles.any { wires[it].toInt() != 0 })
            }
        }
    }
    val n = xs.size
    if (n < 2) return RangeCheck(0, range, charges.any { it })
    fun leg(i: Int): Int { val j = (i + 1) % n; return kotlin.math.abs(xs[j] - xs[i]) + kotlin.math.abs(ys[j] - ys[i]) }
    if (store == Traction.HYDROGEN && service) return RangeCheck((0 until n).maxOf { leg(it) }, range, true)
    val start = charges.indexOf(true)
    if (start < 0) return RangeCheck((0 until n).sumOf { leg(it) }, range, false)
    var longest = 0
    var run = 0
    for (k in 0 until n) {
        val i = (start + k) % n
        run += leg(i)
        if (charges[(i + 1) % n]) { longest = maxOf(longest, run); run = 0 }
    }
    return RangeCheck(longest, range, true)
}

/**
 * Whether a computer company buys [e] for a route of [tiles] between its ends in a straight line, -1 if not known: a
 * battery or hydrogen one only when there and back fits its range, the way taken as half as long again, as it builds
 * no chargers and fills only at services.
 */
internal fun World.rivalFits(e: Engine, tiles: Int) = storeOf(e) < 0 || tiles >= 0 && 2 * tiles * RIVAL_DETOUR / 2 <= rangeOf(e)

/** How much longer than a straight line a rival takes a route to be, in halves. */
private const val RIVAL_DETOUR = 3

/** Straight-line tiles across and down between stations [a] and [b]. */
internal fun stationsApart(a: Station, b: Station) = kotlin.math.abs(a.signX - b.signX) + kotlin.math.abs(a.signY - b.signY)

/** A station's charger for the card: whether it has one, and what one costs now and its upkeep a month now. */
class ChargerView(val built: Boolean, val cost: Long, val upkeep: Long)

/** A charger's cost now and upkeep a month now. */
internal fun World.chargerPrices() = build(Equipment.COST[Equipment.CHARGER], Mix.EQUIPMENT) to price(Equipment.UPKEEP[Equipment.CHARGER])
