package com.rm.interchange.sim

/** The last slot combines all companies; the others follow company ids. */
const val OVERVIEW_ALL = MAX_COMPANIES

class OverviewFlow(val from: Int, val to: Int, val owner: Int, val cargo: Int, val count: Long)
class OverviewIndustry(val id: Int, val made: IntArray, val moved: IntArray, val stock: Int, val waiting: Array<IntArray>, val coverage: IntArray)
class OverviewInfo(val towns: Array<IntArray>, val industries: List<OverviewIndustry>, val flows: List<OverviewFlow>)
class OverviewVehicle(val x: Float, val y: Float, val owner: Int, val mode: Int) {
    companion object {
        const val TRAIN = 0
        const val ROAD = 1
        const val SHIP = 2
        const val AIR = 3
    }
}

/** Read between ticks: a vehicle per dot, including trains inside tunnels. */
fun World.overviewVehicles(): List<OverviewVehicle> = buildList {
    for (t in trains) {
        if (t == null || t.inDepot || t.wreckedFor > 0) continue
        val s = t.steps.lastOrNull() ?: continue
        val tile = stepTile(s)
        val along = (t.nose.toFloat() / stepLength(s)).coerceIn(0f, 1f)
        val a = stepEntry(s)
        val b = stepExit(s)
        add(OverviewVehicle(
            tile % width + (Track.edgeX4(a) + (Track.edgeX4(b) - Track.edgeX4(a)) * along) / 4f,
            tile / width + (Track.edgeY4(a) + (Track.edgeY4(b) - Track.edgeY4(a)) * along) / 4f,
            t.owner, OverviewVehicle.TRAIN,
        ))
    }
    for (v in roadVehicles) if (v != null && !v.inDepot && v.wreckedFor == 0) {
        val p = roadPosition(v)
        add(OverviewVehicle(p[0], p[1], v.owner, OverviewVehicle.ROAD))
    }
    for (v in ships) if (v != null && !v.inDepot) add(OverviewVehicle(v.x / SHIP_UNIT.toFloat(), v.y / SHIP_UNIT.toFloat(), v.owner, OverviewVehicle.SHIP))
    for (v in aircraft) if (v != null && !v.inDepot && v.wreckedFor == 0) add(OverviewVehicle(v.x / SHIP_UNIT.toFloat(), v.y / SHIP_UNIT.toFloat(), v.owner, OverviewVehicle.AIR))
}

/** Station reach with recent calls, output queues at nearby stations, and cargo currently on board each leg. */
fun World.overviewInfo(): OverviewInfo {
    if (catchmentDirty) workOutCatchment()
    val recent = tick - Calendar.TICKS_PER_DAY * 90L
    fun called(s: Station, cargo: Int) = s.lastCall[cargo] >= 0 && s.lastCall[cargo] >= recent
    val total = IntArray(towns.size)
    val covered = Array(towns.size) { IntArray(MAX_COMPANIES + 1) }
    fun people(tile: Int): Int {
        val h = roster.houses.getOrNull(houseOf(house[tile].toInt())) ?: return 0
        return (h.residents + h.jobs).coerceAtLeast(1)
    }
    for (tile in house.indices) {
        val town = townOf[tile].toInt()
        if (town in total.indices) total[town] += people(tile)
    }
    for (k in catchment.indices step 2) {
        val tile = catchment[k]
        val town = townOf[tile].toInt()
        val s = stations.getOrNull(catchment[k + 1]) ?: continue
        if (town !in covered.indices || s.owner !in 0 until MAX_COMPANIES) continue
        if (!called(s, Cargo.PASSENGERS) && !called(s, Cargo.MAIL)) continue
        val n = people(tile)
        covered[town][s.owner] += n
        covered[town][OVERVIEW_ALL] += n
    }
    for (t in covered.indices) for (c in covered[t].indices) covered[t][c] =
        (covered[t][c].toLong() * 100 / total[t].coerceAtLeast(1)).toInt().coerceIn(0, 100)
    val industryInfo = industries.filter { !it.closed }.map { ind ->
        val kind = roster.industries[ind.kind]
        val waiting = Array(MAX_COMPANIES + 1) { IntArray(Cargo.COUNT) }
        val service = IntArray(MAX_COMPANIES + 1)
        for (sid in industryStations[ind.id].orEmpty()) {
            val s = stations.getOrNull(sid) ?: continue
            if (s.owner !in 0 until MAX_COMPANIES) continue
            var active = false
            for (c in 0 until Cargo.COUNT) {
                if ((kind.outputs or kind.accepts) and (1 shl c) != 0 && called(s, c)) active = true
                if (kind.outputs and (1 shl c) == 0) continue
                val n = s.waiting(c)
                waiting[s.owner][c] += n
                waiting[OVERVIEW_ALL][c] += n
            }
            if (active) { service[s.owner] = 100; service[OVERVIEW_ALL] = 100 }
        }
        OverviewIndustry(ind.id, ind.madeLastMonth.copyOf(), ind.movedLastMonth.copyOf(), ind.brought, waiting, service)
    }
    data class Leg(val from: Int, val to: Int, val owner: Int, val cargo: Int)
    val counts = linkedMapOf<Leg, Long>()
    for (v in carriers()) {
        val order = v.orders.getOrNull(v.order % v.orders.size.coerceAtLeast(1)) ?: continue
        val to = Order.station(order)
        if (stations.getOrNull(to) == null) continue
        for (c in 0 until Cargo.COUNT) for (p in v.cargo[c]) {
            val from = if (p.boarded >= 0) p.boarded else p.origin
            if (from == to || stations.getOrNull(from) == null || p.count <= 0) continue
            val leg = Leg(from, to, v.owner, c)
            counts[leg] = (counts[leg] ?: 0) + p.count
        }
    }
    return OverviewInfo(covered, industryInfo, counts.map { (leg, n) -> OverviewFlow(leg.from, leg.to, leg.owner, leg.cargo, n) })
}
