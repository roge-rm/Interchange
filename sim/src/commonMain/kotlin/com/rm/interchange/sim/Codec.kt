package com.rm.interchange.sim

/**
 * Commands as bytes, for saves, the network and replays. Each starts with its
 * kind; new kinds go on the end and old ones never change, so old recordings
 * keep reading.
 */
object Codec {
    private const val TERRAFORM = 1
    private const val RAIL = 2
    private const val STATION = 3
    private const val DEPOT = 4
    private const val SIGNAL = 5
    private const val CLEAR = 6
    private const val BUY_TRAIN = 7
    private const val TRAIN_ORDER = 8
    /** A train bought with what its wagons carry. */
    private const val BUY_TRAIN_CARGO = 9
    private const val LOAN = 10
    private const val FUND_INDUSTRY = 11
    /** A signal of a kind. */
    private const val SIGNAL_KIND = 12
    private const val WAYPOINT = 13
    private const val BRIDGE = 14
    private const val TUNNEL = 15
    private const val WIRE = 16
    private const val UPKEEP = 17
    private const val SNOW_WORKS = 18
    private const val ROAD = 19
    private const val ROAD_ONE_WAY = 20
    private const val ROAD_STOP = 21
    private const val GARAGE = 22
    private const val BUY_ROAD_VEHICLE = 23
    private const val ROAD_ORDER = 24
    private const val TRAM_ROAD = 25
    private const val SALT_DEPOT = 26
    private const val CANAL = 27
    private const val LOCK = 28
    private const val DOCK = 29
    private const val SHIPYARD = 30
    private const val BUOY = 31
    private const val AIRPORT = 32
    private const val AIRPORT_UPGRADE = 33
    private const val BUY_CRAFT = 34
    private const val CRAFT_ORDER = 35
    private const val CLEAR_AREA = 36
    private const val TOWN_ACTION = 37

    fun write(w: ByteWriter, c: Command) {
        when (c) {
            is Command.Terraform -> { w.byte(TERRAFORM); w.int(c.company); w.int(c.x); w.int(c.y); w.bool(c.up) }
            is Command.Rail -> { w.byte(RAIL); w.int(c.company); w.int(c.sx); w.int(c.sy); w.int(c.ex); w.int(c.ey); w.int(c.first); w.bool(c.remove) }
            is Command.Station -> { w.byte(STATION); w.int(c.company); w.int(c.x0); w.int(c.y0); w.int(c.x1); w.int(c.y1); w.int(c.axis) }
            is Command.Depot -> { w.byte(DEPOT); w.int(c.company); w.int(c.x); w.int(c.y); w.int(c.edge) }
            is Command.Signal -> { w.byte(SIGNAL_KIND); w.int(c.company); w.int(c.x); w.int(c.y); w.int(c.piece); w.int(c.facing); w.int(c.kind) }
            is Command.Waypoint -> { w.byte(WAYPOINT); w.int(c.company); w.int(c.x); w.int(c.y) }
            is Command.Bridge -> { w.byte(BRIDGE); w.int(c.company); w.int(c.x0); w.int(c.y0); w.int(c.x1); w.int(c.y1); w.int(c.style) }
            is Command.Tunnel -> { w.byte(TUNNEL); w.int(c.company); w.int(c.x); w.int(c.y) }
            is Command.Road -> { w.byte(TRAM_ROAD); w.int(c.company); w.int(c.sx); w.int(c.sy); w.int(c.ex); w.int(c.ey); w.int(c.surface); w.bool(c.remove); w.bool(c.tram) }
            is Command.RoadOneWay -> { w.byte(ROAD_ONE_WAY); w.int(c.company); w.int(c.x); w.int(c.y) }
            is Command.RoadStop -> { w.byte(ROAD_STOP); w.int(c.company); w.int(c.x); w.int(c.y) }
            is Command.Garage -> { w.byte(GARAGE); w.int(c.company); w.int(c.x); w.int(c.y); w.int(c.edge) }
            is Command.BuyRoadVehicle -> { w.byte(BUY_ROAD_VEHICLE); w.int(c.company); w.int(c.depot); w.int(c.model); w.int(c.cargo) }
            is Command.RoadOrder -> { w.byte(ROAD_ORDER); w.int(c.company); w.int(c.vehicle); w.int(c.what); w.ints(c.stations) }
            is Command.SaltDepot -> { w.byte(SALT_DEPOT); w.int(c.company); w.int(c.x); w.int(c.y) }
            is Command.Canal -> { w.byte(CANAL); w.int(c.company); w.int(c.sx); w.int(c.sy); w.int(c.ex); w.int(c.ey); w.bool(c.remove) }
            is Command.Lock -> { w.byte(LOCK); w.int(c.company); w.int(c.x); w.int(c.y) }
            is Command.Dock -> { w.byte(DOCK); w.int(c.company); w.int(c.x); w.int(c.y) }
            is Command.Shipyard -> { w.byte(SHIPYARD); w.int(c.company); w.int(c.x); w.int(c.y) }
            is Command.Buoy -> { w.byte(BUOY); w.int(c.company); w.int(c.x); w.int(c.y) }
            is Command.Airport -> { w.byte(AIRPORT); w.int(c.company); w.int(c.x); w.int(c.y); w.int(c.kind); w.int(c.axis) }
            is Command.AirportUpgrade -> { w.byte(AIRPORT_UPGRADE); w.int(c.company); w.int(c.airport); w.int(c.what) }
            is Command.BuyCraft -> { w.byte(BUY_CRAFT); w.int(c.company); w.int(c.depot); w.int(c.model); w.int(c.cargo) }
            is Command.CraftOrder -> { w.byte(CRAFT_ORDER); w.int(c.company); w.bool(c.aircraft); w.int(c.vehicle); w.int(c.what); w.ints(c.stations) }
            is Command.SnowWorks -> { w.byte(SNOW_WORKS); w.int(c.company); w.int(c.x); w.int(c.y); w.bool(c.shed) }
            is Command.SetUpkeep -> { w.byte(UPKEEP); w.int(c.company); w.int(c.level) }
            is Command.Wire -> { w.byte(WIRE); w.int(c.company); w.int(c.sx); w.int(c.sy); w.int(c.ex); w.int(c.ey); w.int(c.first); w.bool(c.remove) }
            is Command.Clear -> { w.byte(CLEAR); w.int(c.company); w.int(c.x); w.int(c.y) }
            is Command.ClearArea -> { w.byte(CLEAR_AREA); w.int(c.company); w.int(c.x0); w.int(c.y0); w.int(c.x1); w.int(c.y1) }
            is Command.BuyTrain -> { w.byte(BUY_TRAIN_CARGO); w.int(c.company); w.int(c.depot); w.int(c.engine); w.int(c.wagon); w.int(c.wagons); w.int(c.cargo) }
            is Command.Loan -> { w.byte(LOAN); w.int(c.company); w.bool(c.borrow) }
            is Command.FundIndustry -> { w.byte(FUND_INDUSTRY); w.int(c.company); w.int(c.kind) }
            is Command.TownAction -> { w.byte(TOWN_ACTION); w.int(c.company); w.int(c.town); w.int(c.action) }
            is Command.TrainOrder -> { w.byte(TRAIN_ORDER); w.int(c.company); w.int(c.train); w.int(c.what); w.ints(c.stations) }
        }
    }

    fun read(r: ByteReader): Command = when (val kind = r.byte()) {
        TERRAFORM -> Command.Terraform(r.int(), r.int(), r.int(), r.bool())
        RAIL -> Command.Rail(r.int(), r.int(), r.int(), r.int(), r.int(), r.int(), r.bool())
        STATION -> Command.Station(r.int(), r.int(), r.int(), r.int(), r.int(), r.int())
        DEPOT -> Command.Depot(r.int(), r.int(), r.int(), r.int())
        SIGNAL -> Command.Signal(r.int(), r.int(), r.int(), r.int(), r.int())
        CLEAR -> Command.Clear(r.int(), r.int(), r.int())
        CLEAR_AREA -> Command.ClearArea(r.int(), r.int(), r.int(), r.int(), r.int())
        BUY_TRAIN -> Command.BuyTrain(r.int(), r.int(), r.int(), r.int(), r.int())
        TRAIN_ORDER -> Command.TrainOrder(r.int(), r.int(), r.int(), r.ints())
        BUY_TRAIN_CARGO -> Command.BuyTrain(r.int(), r.int(), r.int(), r.int(), r.int(), r.int())
        LOAN -> Command.Loan(r.int(), r.bool())
        FUND_INDUSTRY -> Command.FundIndustry(r.int(), r.int())
        TOWN_ACTION -> Command.TownAction(r.int(), r.int(), r.int())
        SIGNAL_KIND -> Command.Signal(r.int(), r.int(), r.int(), r.int(), r.int(), r.int())
        WAYPOINT -> Command.Waypoint(r.int(), r.int(), r.int())
        BRIDGE -> Command.Bridge(r.int(), r.int(), r.int(), r.int(), r.int(), r.int())
        TUNNEL -> Command.Tunnel(r.int(), r.int(), r.int())
        UPKEEP -> Command.SetUpkeep(r.int(), r.int())
        SNOW_WORKS -> Command.SnowWorks(r.int(), r.int(), r.int(), r.bool())
        SALT_DEPOT -> Command.SaltDepot(r.int(), r.int(), r.int())
        ROAD -> Command.Road(r.int(), r.int(), r.int(), r.int(), r.int(), r.int(), r.bool())
        TRAM_ROAD -> Command.Road(r.int(), r.int(), r.int(), r.int(), r.int(), r.int(), r.bool(), r.bool())
        ROAD_ONE_WAY -> Command.RoadOneWay(r.int(), r.int(), r.int())
        ROAD_STOP -> Command.RoadStop(r.int(), r.int(), r.int())
        GARAGE -> Command.Garage(r.int(), r.int(), r.int(), r.int())
        BUY_ROAD_VEHICLE -> Command.BuyRoadVehicle(r.int(), r.int(), r.int(), r.int())
        ROAD_ORDER -> Command.RoadOrder(r.int(), r.int(), r.int(), r.ints())
        CANAL -> Command.Canal(r.int(), r.int(), r.int(), r.int(), r.int(), r.bool())
        LOCK -> Command.Lock(r.int(), r.int(), r.int())
        DOCK -> Command.Dock(r.int(), r.int(), r.int())
        SHIPYARD -> Command.Shipyard(r.int(), r.int(), r.int())
        BUOY -> Command.Buoy(r.int(), r.int(), r.int())
        AIRPORT -> Command.Airport(r.int(), r.int(), r.int(), r.int(), r.int())
        AIRPORT_UPGRADE -> Command.AirportUpgrade(r.int(), r.int(), r.int())
        BUY_CRAFT -> Command.BuyCraft(r.int(), r.int(), r.int(), r.int())
        CRAFT_ORDER -> Command.CraftOrder(r.int(), r.bool(), r.int(), r.int(), r.ints())
        WIRE -> Command.Wire(r.int(), r.int(), r.int(), r.int(), r.int(), r.int(), r.bool())
        else -> throw BadData("no such command: $kind")
    }

    fun encode(c: Command): ByteArray = ByteWriter(32).also { write(it, c) }.toByteArray()
    fun decode(b: ByteArray): Command = read(ByteReader(b))
}
