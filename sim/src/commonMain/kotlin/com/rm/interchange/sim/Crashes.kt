package com.rm.interchange.sim

/*
 * Crashes. With the Collisions setting on, vehicles that run into each other with nothing keeping them apart are
 * wrecked: everyone and everything aboard is lost, the news tells of it, and the wreck stands where it is for
 * [WRECK_TICKS] before it's cleared away, with no money back. Trains crash here; road vehicles and aircraft
 * can use [crashed] the same way.
 */

/** A vehicle in a crash: what kind ([Crash.TRAIN], [Crash.ROAD] or [Crash.AIRCRAFT]), its id, owner and name then. */
class CrashVehicle(val kind: Int, val id: Int, val owner: Int, val name: String)

/** A crash, kept for its news: the vehicles in it, the tile, the nearest town or -1, and how many people were lost. */
class Crash(val vehicles: List<CrashVehicle>, val tile: Int, val town: Int, val lost: Int) {
    companion object {
        const val TRAIN = 0
        const val ROAD = 1
        const val AIRCRAFT = 2
    }
}

/** The crashes kept, oldest first; [first] is the number of the first, as [News.about] counts them. */
class CrashLog(val first: Int = 0, val list: List<Crash> = emptyList())

/** Ticks a wreck stands before it's cleared: three work days. */
const val WRECK_TICKS = Calendar.TICKS_PER_DAY * 3

/** The most crashes kept for their news; the oldest go first, and their stories with them. */
const val CRASHES_MOST = 1000

/**
 * The vehicles in [hit] crashed at [tile]: empties them, notes the crash and tells the news once for it.
 * The caller stops them and keeps them standing as wrecks.
 */
internal fun World.crashed(tile: Int, hit: List<Pair<CrashVehicle, Carrier>>) {
    var lost = 0
    for ((_, v) in hit) {
        lost += v.cargo[Cargo.PASSENGERS].sumOf { it.count }
        for (c in v.cargo) c.clear()
    }
    val log = crashes
    val drop = if (log.list.size >= CRASHES_MOST) 1 else 0
    crashes = CrashLog(log.first + drop, log.list.drop(drop) + Crash(hit.map { it.first }, tile, nearestTown(tile), lost))
    tell(News.CRASH, crashes.first + crashes.list.size - 1)
}

/** The crash news [about] tells of, as [News.about] has it, or null if it's no longer kept. */
fun World.crashFor(about: Int): Crash? = crashes.let { it.list.getOrNull(about - it.first) }

/** The town nearest [tile], the lower id if two are as near, or -1 with none. */
internal fun World.nearestTown(tile: Int): Int {
    val x = tile % width
    val y = tile / width
    var best = -1
    var bestD = Long.MAX_VALUE
    for (t in towns) {
        val d = (t.x - x).toLong() * (t.x - x) + (t.y - y).toLong() * (t.y - y)
        if (d < bestD) { bestD = d; best = t.id }
    }
    return best
}
