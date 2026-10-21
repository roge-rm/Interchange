package com.rm.interchange.sim

/** Builds a copy in the given depot and shares orders, even when the source is out on the line. */
internal fun World.copyVehicle(company: Company, c: Command.CopyVehicle) {
    repeat(c.count.coerceIn(0, MAX_AT_ONCE)) {
        when (c.kind) {
            Command.CopyVehicle.TRAIN -> {
                if (depots.getOrNull(c.depot)?.kind != Depots.RAIL) return
                val src = trains.getOrNull(c.vehicle) ?: return
                if (src.owner != company.id || src.wreckedFor > 0) return
                val sizeBefore = trains.size
                buyTrain(company, Command.BuyTrain(company.id, c.depot, src.engine, src.wagon, src.wagons, src.wagonCargo))
                val made = trains.getOrNull(sizeBefore) ?: return
                trainCommand(company, Command.TrainOrder(company.id, made.id, Command.TrainOrder.SHARE, intArrayOf(src.id)))
            }
            Command.CopyVehicle.ROAD -> {
                val src = roadVehicles.getOrNull(c.vehicle) ?: return
                if (src.owner != company.id || src.wreckedFor > 0) return
                val sizeBefore = roadVehicles.size
                buyRoadVehicle(company, Command.BuyRoadVehicle(company.id, c.depot, src.model, src.cargoChoice))
                val made = roadVehicles.getOrNull(sizeBefore) ?: return
                roadVehicleCommand(company, Command.RoadOrder(company.id, made.id, Command.TrainOrder.SHARE, intArrayOf(src.id)))
            }
            Command.CopyVehicle.SHIP -> {
                val src = ships.getOrNull(c.vehicle) ?: return
                if (src.owner != company.id) return
                val sizeBefore = ships.size
                buyCraft(company, Command.BuyCraft(company.id, c.depot, src.model, src.cargoChoice))
                val made = ships.getOrNull(sizeBefore) ?: return
                shipCommand(company, Command.CraftOrder(company.id, false, made.id, Command.TrainOrder.SHARE, intArrayOf(src.id)))
            }
            Command.CopyVehicle.AIRCRAFT -> {
                val src = aircraft.getOrNull(c.vehicle) ?: return
                if (src.owner != company.id || src.wreckedFor > 0) return
                val sizeBefore = aircraft.size
                buyCraft(company, Command.BuyCraft(company.id, c.depot, src.model, src.cargoChoice))
                val made = aircraft.getOrNull(sizeBefore) ?: return
                aircraftCommand(company, Command.CraftOrder(company.id, true, made.id, Command.TrainOrder.SHARE, intArrayOf(src.id)))
            }
        }
    }
}
