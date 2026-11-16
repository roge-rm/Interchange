package com.rm.interchange.sim

class OrderBackup(val orders: ArrayList<Int>, val gap: Int, var shared: Int = -1)

private fun World.sharedSurvivor(kind: Int, owner: Int, shared: Int, excluded: Int): Carrier? {
    if (shared < 0) return null
    val list: List<Carrier?> = when (kind) {
        Command.CopyVehicle.TRAIN -> trains
        Command.CopyVehicle.ROAD -> roadVehicles
        Command.CopyVehicle.SHIP -> ships
        Command.CopyVehicle.AIRCRAFT -> aircraft
        else -> return null
    }
    return list.firstOrNull { v ->
        v != null && v.id != excluded && v.owner == owner && v.shared == shared && when (v) {
            is Train -> v.wreckedFor == 0
            is RoadVehicle -> v.wreckedFor == 0
            is Aircraft -> v.wreckedFor == 0
            else -> true
        }
    }
}

internal fun World.rememberOrders(company: Company, kind: Int, sold: Carrier, backup: OrderBackup) {
    // A group keeps its id after its first vehicle is sold.
    backup.shared = if (sharedSurvivor(kind, company.id, sold.shared, sold.id) != null) sold.shared else -1
    company.orderBackups[kind] = backup
}

internal fun World.inheritOrders(company: Company, kind: Int, made: Carrier?) {
    if (made == null) return
    val backup = company.orderBackups[kind] ?: return
    val survivor = sharedSurvivor(kind, company.id, backup.shared, made.id)
    made.orders.clear()
    made.orders += survivor?.orders ?: backup.orders
    made.gap = survivor?.gap ?: backup.gap
    made.shared = survivor?.shared ?: -1
    when (made) {
        is Train -> made.route = null
        is RoadVehicle -> made.route = null
        is Ship -> made.route = null
    }
    firstOrder(made)
    restartTimetable(made)
    company.orderBackups[kind] = null
    linksDirty = true
}

/**
 * Orders kept from the last vehicle of [kind] (a [Command.CopyVehicle] kind) sold, which a buy with inherit gives the
 * next one: the orders as a vehicle has them, how many stop at a station, and those stations' names in order.
 */
class KeptOrdersView(val kind: Int, val orders: IntArray, val stops: Int, val stations: List<String>)

/** Company [company]'s kept orders, by kind. */
internal fun World.keptOrderViews(company: Int): List<KeptOrdersView> {
    val c = companies.getOrNull(company) ?: return emptyList()
    val out = ArrayList<KeptOrdersView>()
    for (kind in c.orderBackups.indices) {
        val b = c.orderBackups[kind] ?: continue
        // A survivor sharing the sold one's orders is what a buy would take them from.
        val orders = sharedSurvivor(kind, company, b.shared, -1)?.orders ?: b.orders
        // Stops only: depot, conditional and pass-through orders left out.
        val names = orders.filter { Order.station(it) >= 0 && it and Order.VIA == 0 }.mapNotNull { stations.getOrNull(Order.station(it))?.name }
        out += KeptOrdersView(kind, orders.toIntArray(), names.size, names)
    }
    return out
}
