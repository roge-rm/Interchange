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
    private const val BUY_COMPANY = 38
    private const val NEW_COMPANY = 39
    private const val RENAME_COMPANY = 40
    private const val SET_INSIGNIA = 41
    private const val EDIT_TERRAIN = 42
    private const val PLACE_TOWN = 43
    private const val GROW_TOWN = 44
    private const val PLACE_INDUSTRY = 45
    private const val REMOVE_INDUSTRY = 46
    private const val EDIT_TREES = 47
    private const val SET_GOALS = 48
    private const val SET_SCENARIO = 49
    private const val SIGNAL_RUN = 50
    private const val AUTORENEW = 51
    private const val REMOVE = 52
    private const val REMOVE_AREA = 53
    private const val LEVEL = 54
    private const val PLANT_TREES = 55
    private const val COPY_VEHICLE = 56
    private const val BUY_TRAINS = 57
    private const val BUY_ROAD_VEHICLES = 58
    private const val BUY_CRAFTS = 59
    private const val COPY_VEHICLES = 60
    private const val CREATE_GROUP = 61

    /** The most goals a [Command.SetGoals] may carry. */
    const val MOST_GOALS = 100

    fun write(w: ByteWriter, c: Command) {
        when (c) {
            is Command.Terraform -> { w.byte(TERRAFORM); w.int(c.company); w.int(c.x); w.int(c.y); w.bool(c.up) }
            is Command.Rail -> { w.byte(RAIL); w.int(c.company); w.int(c.sx); w.int(c.sy); w.int(c.ex); w.int(c.ey); w.int(c.first); w.bool(c.remove) }
            is Command.Station -> { w.byte(STATION); w.int(c.company); w.int(c.x0); w.int(c.y0); w.int(c.x1); w.int(c.y1); w.int(c.axis) }
            is Command.Depot -> { w.byte(DEPOT); w.int(c.company); w.int(c.x); w.int(c.y); w.int(c.edge) }
            is Command.Signal -> { w.byte(SIGNAL_KIND); w.int(c.company); w.int(c.x); w.int(c.y); w.int(c.piece); w.int(c.facing); w.int(c.kind) }
            is Command.SignalRun -> { w.byte(SIGNAL_RUN); w.int(c.company); w.int(c.sx); w.int(c.sy); w.int(c.ex); w.int(c.ey); w.int(c.first); w.int(c.kind); w.int(c.spacing); w.bool(c.remove) }
            is Command.Waypoint -> { w.byte(WAYPOINT); w.int(c.company); w.int(c.x); w.int(c.y) }
            is Command.Bridge -> { w.byte(BRIDGE); w.int(c.company); w.int(c.x0); w.int(c.y0); w.int(c.x1); w.int(c.y1); w.int(c.style) }
            is Command.Tunnel -> { w.byte(TUNNEL); w.int(c.company); w.int(c.x); w.int(c.y) }
            is Command.Road -> { w.byte(TRAM_ROAD); w.int(c.company); w.int(c.sx); w.int(c.sy); w.int(c.ex); w.int(c.ey); w.int(c.surface); w.bool(c.remove); w.bool(c.tram) }
            is Command.RoadOneWay -> { w.byte(ROAD_ONE_WAY); w.int(c.company); w.int(c.x); w.int(c.y) }
            is Command.RoadStop -> { w.byte(ROAD_STOP); w.int(c.company); w.int(c.x); w.int(c.y) }
            is Command.Garage -> { w.byte(GARAGE); w.int(c.company); w.int(c.x); w.int(c.y); w.int(c.edge) }
            is Command.BuyRoadVehicle -> { w.byte(if (c.count == 1) BUY_ROAD_VEHICLE else BUY_ROAD_VEHICLES); w.int(c.company); w.int(c.depot); w.int(c.model); w.int(c.cargo); if (c.count != 1) w.int(c.count) }
            is Command.RoadOrder -> { w.byte(ROAD_ORDER); w.int(c.company); w.int(c.vehicle); w.int(c.what); w.ints(c.stations) }
            is Command.SaltDepot -> { w.byte(SALT_DEPOT); w.int(c.company); w.int(c.x); w.int(c.y) }
            is Command.Canal -> { w.byte(CANAL); w.int(c.company); w.int(c.sx); w.int(c.sy); w.int(c.ex); w.int(c.ey); w.bool(c.remove) }
            is Command.Lock -> { w.byte(LOCK); w.int(c.company); w.int(c.x); w.int(c.y) }
            is Command.Dock -> { w.byte(DOCK); w.int(c.company); w.int(c.x); w.int(c.y) }
            is Command.Shipyard -> { w.byte(SHIPYARD); w.int(c.company); w.int(c.x); w.int(c.y) }
            is Command.Buoy -> { w.byte(BUOY); w.int(c.company); w.int(c.x); w.int(c.y) }
            is Command.Airport -> { w.byte(AIRPORT); w.int(c.company); w.int(c.x); w.int(c.y); w.int(c.kind); w.int(c.axis) }
            is Command.AirportUpgrade -> { w.byte(AIRPORT_UPGRADE); w.int(c.company); w.int(c.airport); w.int(c.what) }
            is Command.BuyCraft -> { w.byte(if (c.count == 1) BUY_CRAFT else BUY_CRAFTS); w.int(c.company); w.int(c.depot); w.int(c.model); w.int(c.cargo); if (c.count != 1) w.int(c.count) }
            is Command.CraftOrder -> { w.byte(CRAFT_ORDER); w.int(c.company); w.bool(c.aircraft); w.int(c.vehicle); w.int(c.what); w.ints(c.stations) }
            is Command.SnowWorks -> { w.byte(SNOW_WORKS); w.int(c.company); w.int(c.x); w.int(c.y); w.bool(c.shed) }
            is Command.SetUpkeep -> { w.byte(UPKEEP); w.int(c.company); w.int(c.level) }
            is Command.SetAutorenew -> { w.byte(AUTORENEW); w.int(c.company); w.bool(c.on); w.int(c.months); w.long(c.floor) }
            is Command.Wire -> { w.byte(WIRE); w.int(c.company); w.int(c.sx); w.int(c.sy); w.int(c.ex); w.int(c.ey); w.int(c.first); w.bool(c.remove) }
            is Command.Clear -> { w.byte(CLEAR); w.int(c.company); w.int(c.x); w.int(c.y) }
            is Command.ClearArea -> { w.byte(CLEAR_AREA); w.int(c.company); w.int(c.x0); w.int(c.y0); w.int(c.x1); w.int(c.y1) }
            is Command.Remove -> { w.byte(REMOVE); w.int(c.company); w.int(c.x); w.int(c.y) }
            is Command.RemoveArea -> { w.byte(REMOVE_AREA); w.int(c.company); w.int(c.x0); w.int(c.y0); w.int(c.x1); w.int(c.y1) }
            is Command.Level -> { w.byte(LEVEL); w.int(c.company); w.int(c.x0); w.int(c.y0); w.int(c.x1); w.int(c.y1); w.int(c.height) }
            is Command.PlantTrees -> { w.byte(PLANT_TREES); w.int(c.company); w.int(c.x0); w.int(c.y0); w.int(c.x1); w.int(c.y1) }
            is Command.CreateGroup -> { w.byte(CREATE_GROUP); w.int(c.company); w.int(c.kind); w.int(c.vehicle); w.string(c.name) }
            is Command.CopyVehicle -> { w.byte(if (c.count == 1) COPY_VEHICLE else COPY_VEHICLES); w.int(c.company); w.int(c.kind); w.int(c.vehicle); w.int(c.depot); if (c.count != 1) w.int(c.count) }
            is Command.BuyTrain -> { w.byte(if (c.count == 1) BUY_TRAIN_CARGO else BUY_TRAINS); w.int(c.company); w.int(c.depot); w.int(c.engine); w.int(c.wagon); w.int(c.wagons); w.int(c.cargo); if (c.count != 1) w.int(c.count) }
            is Command.Loan -> { w.byte(LOAN); w.int(c.company); w.bool(c.borrow) }
            is Command.FundIndustry -> { w.byte(FUND_INDUSTRY); w.int(c.company); w.int(c.kind) }
            is Command.TownAction -> { w.byte(TOWN_ACTION); w.int(c.company); w.int(c.town); w.int(c.action) }
            is Command.BuyCompany -> { w.byte(BUY_COMPANY); w.int(c.company); w.int(c.target) }
            is Command.NewCompany -> { w.byte(NEW_COMPANY); w.int(c.company); w.string(c.name) }
            is Command.RenameCompany -> { w.byte(RENAME_COMPANY); w.int(c.company); w.string(c.name) }
            is Command.SetInsignia -> { w.byte(SET_INSIGNIA); w.int(c.company); w.bytes(c.pixels) }
            is Command.EditTerrain -> { w.byte(EDIT_TERRAIN); w.int(c.company); w.int(c.x0); w.int(c.y0); w.int(c.x1); w.int(c.y1); w.int(c.how); w.int(c.height) }
            is Command.PlaceTown -> { w.byte(PLACE_TOWN); w.int(c.company); w.int(c.x); w.int(c.y); w.string(c.name); w.int(c.size) }
            is Command.GrowTown -> { w.byte(GROW_TOWN); w.int(c.company); w.int(c.town); w.int(c.size) }
            is Command.PlaceIndustry -> { w.byte(PLACE_INDUSTRY); w.int(c.company); w.string(c.kind); w.int(c.x); w.int(c.y); w.int(c.turn) }
            is Command.RemoveIndustry -> { w.byte(REMOVE_INDUSTRY); w.int(c.company); w.int(c.industry) }
            is Command.EditTrees -> { w.byte(EDIT_TREES); w.int(c.company); w.int(c.x0); w.int(c.y0); w.int(c.x1); w.int(c.y1); w.bool(c.plant) }
            is Command.SetGoals -> {
                w.byte(SET_GOALS); w.int(c.company); w.uvar(c.goals.size.toLong())
                for (g in c.goals) { w.int(g.kind); w.long(g.amount); w.int(g.deadline); w.int(g.cargo); w.int(g.town); w.int(g.town2); w.int(g.industry); w.int(g.from) }
            }
            is Command.SetScenario -> {
                w.byte(SET_SCENARIO); w.int(c.company); w.string(c.title); w.string(c.intro); w.long(c.money); w.long(c.loan); w.int(c.win); w.int(c.startYear); w.string(c.chapter)
            }
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
        REMOVE -> Command.Remove(r.int(), r.int(), r.int())
        REMOVE_AREA -> Command.RemoveArea(r.int(), r.int(), r.int(), r.int(), r.int())
        LEVEL -> Command.Level(r.int(), r.int(), r.int(), r.int(), r.int(), r.int())
        PLANT_TREES -> Command.PlantTrees(r.int(), r.int(), r.int(), r.int(), r.int())
        CREATE_GROUP -> Command.CreateGroup(r.int(), r.int(), r.int(), r.string())
        COPY_VEHICLE -> Command.CopyVehicle(r.int(), r.int(), r.int(), r.int())
        COPY_VEHICLES -> Command.CopyVehicle(r.int(), r.int(), r.int(), r.int(), r.int())
        BUY_TRAIN -> Command.BuyTrain(r.int(), r.int(), r.int(), r.int(), r.int())
        TRAIN_ORDER -> Command.TrainOrder(r.int(), r.int(), r.int(), r.ints())
        BUY_TRAIN_CARGO -> Command.BuyTrain(r.int(), r.int(), r.int(), r.int(), r.int(), r.int())
        BUY_TRAINS -> Command.BuyTrain(r.int(), r.int(), r.int(), r.int(), r.int(), r.int(), r.int())
        LOAN -> Command.Loan(r.int(), r.bool())
        FUND_INDUSTRY -> Command.FundIndustry(r.int(), r.int())
        TOWN_ACTION -> Command.TownAction(r.int(), r.int(), r.int())
        BUY_COMPANY -> Command.BuyCompany(r.int(), r.int())
        NEW_COMPANY -> Command.NewCompany(r.int(), r.string())
        RENAME_COMPANY -> Command.RenameCompany(r.int(), r.string())
        SET_INSIGNIA -> Command.SetInsignia(r.int(), r.bytes())
        SIGNAL_KIND -> Command.Signal(r.int(), r.int(), r.int(), r.int(), r.int(), r.int())
        SIGNAL_RUN -> Command.SignalRun(r.int(), r.int(), r.int(), r.int(), r.int(), r.int(), r.int(), r.int(), r.bool())
        WAYPOINT -> Command.Waypoint(r.int(), r.int(), r.int())
        BRIDGE -> Command.Bridge(r.int(), r.int(), r.int(), r.int(), r.int(), r.int())
        TUNNEL -> Command.Tunnel(r.int(), r.int(), r.int())
        UPKEEP -> Command.SetUpkeep(r.int(), r.int())
        AUTORENEW -> Command.SetAutorenew(r.int(), r.bool(), r.int(), r.long())
        SNOW_WORKS -> Command.SnowWorks(r.int(), r.int(), r.int(), r.bool())
        SALT_DEPOT -> Command.SaltDepot(r.int(), r.int(), r.int())
        ROAD -> Command.Road(r.int(), r.int(), r.int(), r.int(), r.int(), r.int(), r.bool())
        TRAM_ROAD -> Command.Road(r.int(), r.int(), r.int(), r.int(), r.int(), r.int(), r.bool(), r.bool())
        ROAD_ONE_WAY -> Command.RoadOneWay(r.int(), r.int(), r.int())
        ROAD_STOP -> Command.RoadStop(r.int(), r.int(), r.int())
        GARAGE -> Command.Garage(r.int(), r.int(), r.int(), r.int())
        BUY_ROAD_VEHICLE -> Command.BuyRoadVehicle(r.int(), r.int(), r.int(), r.int())
        BUY_ROAD_VEHICLES -> Command.BuyRoadVehicle(r.int(), r.int(), r.int(), r.int(), r.int())
        ROAD_ORDER -> Command.RoadOrder(r.int(), r.int(), r.int(), r.ints())
        CANAL -> Command.Canal(r.int(), r.int(), r.int(), r.int(), r.int(), r.bool())
        LOCK -> Command.Lock(r.int(), r.int(), r.int())
        DOCK -> Command.Dock(r.int(), r.int(), r.int())
        SHIPYARD -> Command.Shipyard(r.int(), r.int(), r.int())
        BUOY -> Command.Buoy(r.int(), r.int(), r.int())
        AIRPORT -> Command.Airport(r.int(), r.int(), r.int(), r.int(), r.int())
        AIRPORT_UPGRADE -> Command.AirportUpgrade(r.int(), r.int(), r.int())
        BUY_CRAFT -> Command.BuyCraft(r.int(), r.int(), r.int(), r.int())
        BUY_CRAFTS -> Command.BuyCraft(r.int(), r.int(), r.int(), r.int(), r.int())
        CRAFT_ORDER -> Command.CraftOrder(r.int(), r.bool(), r.int(), r.int(), r.ints())
        WIRE -> Command.Wire(r.int(), r.int(), r.int(), r.int(), r.int(), r.int(), r.bool())
        EDIT_TERRAIN -> Command.EditTerrain(r.int(), r.int(), r.int(), r.int(), r.int(), r.int(), r.int())
        PLACE_TOWN -> Command.PlaceTown(r.int(), r.int(), r.int(), r.string(), r.int())
        GROW_TOWN -> Command.GrowTown(r.int(), r.int(), r.int())
        PLACE_INDUSTRY -> Command.PlaceIndustry(r.int(), r.string(), r.int(), r.int(), r.int())
        REMOVE_INDUSTRY -> Command.RemoveIndustry(r.int(), r.int())
        EDIT_TREES -> Command.EditTrees(r.int(), r.int(), r.int(), r.int(), r.int(), r.bool())
        SET_GOALS -> {
            val company = r.int()
            val n = r.uvar().toInt().also { if (it !in 0..MOST_GOALS) throw BadData("too many goals") }
            Command.SetGoals(company, List(n) { Goal(r.int(), r.long(), r.int(), cargo = r.int(), town = r.int(), town2 = r.int(), industry = r.int(), from = r.int()) })
        }
        SET_SCENARIO -> Command.SetScenario(r.int(), r.string(), r.string(), r.long(), r.long(), r.int(), r.int(), r.string())
        else -> throw BadData("no such command: $kind")
    }

    fun encode(c: Command): ByteArray = ByteWriter(32).also { write(it, c) }.toByteArray()
    fun decode(b: ByteArray): Command = read(ByteReader(b))
}
