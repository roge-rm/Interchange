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
        is Train -> { made.order = 0; made.route = null }
        is RoadVehicle -> { made.order = 0; made.route = null }
        is Ship -> { made.order = 0; made.route = null }
        is Aircraft -> made.order = 0
    }
    company.orderBackups[kind] = null
    linksDirty = true
}
