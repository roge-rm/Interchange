package com.rm.interchange.sim

const val GROUP_NAME_MOST = 24

data class VehicleGroup(val id: Int, val name: String)

fun capGroupName(name: String): String = capName(name, GROUP_NAME_MOST)

fun cleanGroupName(name: String): String = capGroupName(name.trim()).trim()

internal fun World.groupVehicles(kind: Int): List<Carrier?> = when (kind) {
    Command.CopyVehicle.TRAIN -> trains
    Command.CopyVehicle.ROAD -> roadVehicles
    Command.CopyVehicle.SHIP -> ships
    Command.CopyVehicle.AIRCRAFT -> aircraft
    else -> emptyList()
}

internal fun World.pruneVehicleGroup(owner: Int, kind: Int, id: Int) {
    if (id < 0 || groupVehicles(kind).any { it != null && it.owner == owner && it.group == id }) return
    companies.getOrNull(owner)?.groups?.getOrNull(kind)?.removeAll { it.id == id }
}

internal fun World.setVehicleGroup(company: Company, kind: Int, vehicle: Carrier, id: Int?) {
    if (id == null || (id != -1 && company.groups[kind].none { it.id == id })) return
    val old = vehicle.group
    vehicle.group = id
    pruneVehicleGroup(company.id, kind, old)
}

internal fun World.createGroup(company: Company, c: Command.CreateGroup) {
    val groups = company.groups.getOrNull(c.kind) ?: return
    val name = cleanGroupName(c.name)
    if (name.isEmpty() || groups.any { it.name.equals(name, ignoreCase = true) }) return
    val vehicle = groupVehicles(c.kind).getOrNull(c.vehicle) ?: return
    if (vehicle.owner != company.id) return
    if (when (vehicle) {
        is Train -> vehicle.wreckedFor > 0
        is RoadVehicle -> vehicle.wreckedFor > 0
        is Aircraft -> vehicle.wreckedFor > 0
        else -> false
    }) return
    var id = 0
    while (groups.any { it.id == id }) id++
    groups += VehicleGroup(id, name)
    setVehicleGroup(company, c.kind, vehicle, id)
}
