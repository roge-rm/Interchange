package com.rm.interchange.sim

/**
 * The map and everything on it. Layers are flat arrays: tiles indexed by
 * `y * width + x`, corners by `y * (width + 1) + x`. All maths is in integers
 * so every copy of the world stays the same to the bit: no floats in here.
 *
 * Land is a height at each tile corner, in steps. Corners next to each other,
 * across or diagonally, differ by at most one, so every tile is flat, a slope
 * or a corner raised or sunk. A tile with all four corners at 0 is water.
 *
 * Only the game thread changes the world. Other threads may read it, and see
 * a tick's changes part done; the renderer copes through [chunkVersions].
 *
 * [roster] is what can run on the track, from the game's content packs.
 */
class World(val settings: NewGame, val roster: Roster = Roster.BASE, generate: Boolean = true) {
    /** A map with no towns and fair weather, for tests that lay their own track. */
    constructor(width: Int, height: Int, seed: Long, startYear: Int = 1850) : this(NewGame(width, height, seed, startYear, towns = 0, industries = 0, weather = false))

    val width get() = settings.width
    val height get() = settings.height
    val startYear get() = settings.startYear

    init {
        require(width % CHUNK == 0 && height % CHUNK == 0) { "Map sides are multiples of $CHUNK" }
    }

    val corners = ByteArray((width + 1) * (height + 1))

    /** The kind of ground on each tile, a [Ground] kind. */
    val ground = ByteArray(width * height)

    /** The trees on each tile, packed by [Trees.pack]. */
    val trees = ByteArray(width * height)

    /** Track pieces on each tile, as [Track] bits. */
    val track = ByteArray(width * height)

    /** Which company owns what's on each tile, or -1. */
    val owner = ByteArray(width * height) { -1 }

    /** Town roads on each tile, as [roadBit]s; buildings, packed by [packHouse], and the year each went up; and which town each is, or -1. */
    val road = ByteArray(width * height)
    /** Each road tile's surface and one-way, by [roadKindOf], and who built it, -1 for a town. */
    val roadKind = ByteArray(width * height)
    val roadOwner = ByteArray(width * height) { -1 }
    /** Road stop tiles to their station. */
    val roadStops = HashMap<Int, Int>()
    /** Buses, trucks and streetcars by id; a sold one leaves null. */
    val roadVehicles = ArrayList<RoadVehicle?>()
    internal var roadNetDirty = true
    /** Salt depots by tile; and which ice roads were open yesterday, not saved. */
    val saltDepots = HashSet<Int>()
    internal var iceRoadsOpen: BooleanArray? = null
    /** Canals and locks (by tile, to the way they run) the companies have dug; docks to their station and the edge each faces; buoys to theirs. */
    val canals = HashSet<Int>()
    val locks = HashMap<Int, Int>()
    val docks = HashMap<Int, Int>()
    val dockFacing = HashMap<Int, Int>()
    val buoys = HashMap<Int, Int>()
    /** Ships, airports and aircraft by id, a gone one leaving null; airport tiles to their airport. */
    val ships = ArrayList<Ship?>()
    val airports = ArrayList<Airport?>()
    val airportTiles = HashMap<Int, Int>()
    val aircraft = ArrayList<Aircraft?>()
    /** Today's weather at each airport, worked out as needed. Not saved. */
    internal val airSkies = HashMap<Int, Int>()
    val house = ShortArray(width * height)
    val houseYear = ShortArray(width * height)
    val townOf = ShortArray(width * height) { -1 }

    val towns = ArrayList<Town>()

    /** Industries, and which stands on each tile, as its id plus one, 0 for none. */
    val industries = ArrayList<Industry>()
    val industryOf = ShortArray(width * height)
    /** Inflation so far: what costs and fares are now against at the start, in 65536ths. */
    var costScale = 1L shl 16
    var payScale = 1L shl 16

    /** Snow, ice, wet and frost on each tile, and the fronts crossing the map. */
    val weather = Weather(width * height)
    /** How each tile's weather looked last, by [weatherLook], to know which chunks to draw again. Not saved. */
    internal var weatherLooks: ShortArray? = null
    internal var weatherViewCache: WeatherView? = null
    private var lookDay = -1
    private var look = Climate.SUMMER

    /** How the land looks today, one of [Climate]'s looks, worked out from the date. */
    fun lookToday(): Int {
        if (lookDay != day) {
            lookDay = day
            look = Climate.look(settings.region, Calendar.year(startYear, day), Calendar.dayOfYear(startYear, day))
        }
        return look
    }

    /** Recent news for the screen. Not saved; nothing in the game depends on it. */
    val news = ArrayList<News>()
    /** Set when a town grows, so its summary for the screen is made again. Not part of the game. */
    var townsChanged = true
    internal var townViews: List<TownView> = emptyList()

    /** Building tiles in reach of a station and the station each sends to, worked out again when [catchmentDirty]. Not saved. */
    internal var catchment = IntArray(0)
    internal var catchmentDirty = true
    /** The people round each station, and the next stop from each station towards each it can reach. Not saved. */
    internal val stationPeople = HashMap<Int, Int>()
    internal val nextHops = HashMap<Int, HashMap<Int, Int>>()
    /** The stations reaching each industry, and the industries each station reaches, by id. Not saved. */
    internal val industryStations = HashMap<Int, ArrayList<Int>>()
    internal val stationIndustries = HashMap<Int, ArrayList<Int>>()
    internal var linksDirty = true
    /** Recent payments, for the screen. Not part of the game. */
    val payments = ArrayList<Payment>()

    val companies = mutableListOf(Company(0, "Company", Prices.START_MONEY))

    // What's built on the track, by id; a removed one leaves null.
    val stations = ArrayList<Station?>()
    val depots = ArrayList<Depot?>()
    val trains = ArrayList<Train?>()

    /** Each tile's track condition, 255 new; fire on each tile, days left burning or how long it's been black; kinked track by tile, days left; closed track by tile, why in the high bits and days left in the low 16. */
    val condition = ByteArray(width * height) { -1 }
    val fire = ByteArray(width * height)
    val kinks = HashMap<Int, Int>()
    /** Snow fences on open land, and snow sheds over track, by tile. */
    val fences = HashSet<Int>()
    val sheds = HashSet<Int>()
    internal var fenceCoverCache: BooleanArray? = null
    val closed = HashMap<Int, Int>()

    /** Track pieces wired for electric engines, as [Track] bits, on the ground; bridges and tunnels are wired whole. */
    val wires = ByteArray(width * height)

    /** Bridges and tunnels by id, a removed one leaving null; the span over or under each tile between heads, and each head's span. */
    val spans = ArrayList<Span?>()
    val deck = HashMap<Int, Int>()
    val spanHeads = HashMap<Int, Int>()

    /** Platform tiles to their station, depot tiles to their depot. */
    val stationTiles = HashMap<Int, Int>()
    val depotTiles = HashMap<Int, Int>()

    /** Signals by [pieceKey], packed by [Signals.pack]. */
    val signals = HashMap<Int, Int>()

    // Worked out from the above as needed and left out of saves: the block each piece is in, and how many trains are on each tile and block.
    internal val blocks = HashMap<Int, Int>()
    internal var blocksDirty = true
    internal val tileTrains = HashMap<Int, Int>()
    /** Trains on each piece, by [pieceKey]; the ways out of each block, as signal kind and the block beyond. Not saved. */
    internal val pieceTrains = HashMap<Int, Int>()
    internal val exits = HashMap<Int, ArrayList<IntArray>>()
    /** Pieces held by trains that passed a path signal, by [pieceKey], to the train's id. */
    val reserved = HashMap<Int, Int>()
    internal val blockTrains = HashMap<Int, Int>()

    val rng = Rng(settings.seed)

    /** The seed folded into an int, for per-tile dice that don't draw from [rng]. */
    internal val seedBits = (settings.seed xor (settings.seed ushr 32)).toInt()

    /** Ticks since the game began. */
    var tick = 0L
        internal set

    val day get() = (tick / Calendar.TICKS_PER_DAY).toInt()

    /**
     * How many times each [CHUNK] by [CHUNK] block of tiles has changed, so the
     * renderer knows what to draw again. Not part of the game and not saved.
     */
    val chunkVersions = IntArray((width / BLOCK) * (height / BLOCK))

    /** The same for how the weather looks on each block: snow, ice, mud. Kept apart so the renderer can take its time over these. */
    val weatherVersions = IntArray((width / BLOCK) * (height / BLOCK))

    init {
        // A loaded game fills these in from its save instead.
        if (generate) {
            generateLand(this)
            generateCountry(this)
            generateTowns(this)
            generateIndustries(this)
            startWeather()
        }
    }

    fun index(x: Int, y: Int) = y * width + x

    fun corner(x: Int, y: Int) = y * (width + 1) + x

    fun inside(x: Int, y: Int) = x in 0 until width && y in 0 until height

    fun cornerInside(x: Int, y: Int) = x in 0..width && y in 0..height

    fun heightAt(cx: Int, cy: Int): Int = corners[corner(cx, cy)].toInt()

    fun isWater(x: Int, y: Int): Boolean =
        heightAt(x, y) == 0 && heightAt(x + 1, y) == 0 && heightAt(x + 1, y + 1) == 0 && heightAt(x, y + 1) == 0

    fun isFlat(x: Int, y: Int): Boolean {
        val n = heightAt(x, y)
        return n == heightAt(x + 1, y) && n == heightAt(x + 1, y + 1) && n == heightAt(x, y + 1)
    }

    fun trackAt(x: Int, y: Int): Int = track[index(x, y)].toInt()

    /** The dry tile nearest the middle of the map, for the camera to start on. */
    fun landNearMiddle(): Int {
        val cx = width / 2
        val cy = height / 2
        for (r in 0 until maxOf(width, height) / 2) {
            for (y in cy - r..cy + r) for (x in cx - r..cx + r) {
                if ((kotlin.math.abs(x - cx) == r || kotlin.math.abs(y - cy) == r) && inside(x, y) && !isWater(x, y)) return index(x, y)
            }
        }
        return index(cx, cy)
    }

    /** Runs one command. What can't be done is left undone. */
    fun apply(command: Command) {
        val company = companies.getOrNull(command.company) ?: return
        when (command) {
            is Command.Terraform -> terraform(company, command.x, command.y, command.up)
            is Command.Rail -> buildRail(company, command)
            is Command.Station -> buildStation(company, command)
            is Command.Depot -> buildDepot(company, command)
            is Command.Signal -> placeSignal(company, command)
            is Command.Waypoint -> buildWaypoint(company, command)
            is Command.Bridge -> buildBridge(company, command)
            is Command.Tunnel -> buildTunnel(company, command)
            is Command.Wire -> wire(company, command)
            is Command.Road -> buildRoad(company, command)
            is Command.RoadOneWay -> roadOneWay(company, command)
            is Command.RoadStop -> buildRoadStop(company, command)
            is Command.Garage -> buildGarage(company, command)
            is Command.BuyRoadVehicle -> buyRoadVehicle(company, command)
            is Command.RoadOrder -> roadVehicleCommand(company, command)
            is Command.SaltDepot -> saltDepot(company, command)
            is Command.Canal -> buildCanal(company, command)
            is Command.Lock -> buildLock(company, command)
            is Command.Dock -> buildDock(company, command)
            is Command.Shipyard -> buildShipyard(company, command)
            is Command.Buoy -> buildBuoy(company, command)
            is Command.Airport -> buildAirport(company, command)
            is Command.AirportUpgrade -> upgradeAirport(company, command)
            is Command.BuyCraft -> buyCraft(company, command)
            is Command.CraftOrder -> if (command.aircraft) aircraftCommand(company, command) else shipCommand(company, command)
            is Command.SnowWorks -> snowWorks(company, command)
            is Command.SetUpkeep -> company.upkeep = command.level.coerceIn(Upkeep.LOW, Upkeep.HIGH)
            is Command.Clear -> clearTile(company, command)
            is Command.BuyTrain -> buyTrain(company, command)
            is Command.TrainOrder -> trainCommand(company, command)
            is Command.Loan -> loanCommand(company, command)
            is Command.FundIndustry -> fundIndustry(company, command)
        }
    }

    /** Notes that tile ([x], [y]) has changed, for the renderer. */
    fun touched(x: Int, y: Int) {
        chunkVersions[(y / BLOCK) * (width / BLOCK) + x / BLOCK]++
    }

    /** Moves the world on by one tick. */
    fun step() {
        tick++
        moveTrains()
        moveRoadVehicles()
        moveShips()
        moveAircraft()
        if (tick % Calendar.TICKS_PER_DAY == 0L) {
            weatherDay()
            airSkies.clear()
            trackDay()
            weatherLooks()
            trainsDay()
            roadVehiclesDay()
            roadsDay()
            shipsDay()
            aircraftDay()
            payRunningCosts()
            passengersDay()
            townsDay()
            if (Calendar.dayOfMonth(startYear, day) == 1 && day > 0) {
                companiesMonth()
                passengersMonth()
                industriesMonth()
            }
            if (Calendar.newYear(startYear, day) && day > 0) {
                passengersYear()
                companiesYear()
            }
            payments.removeAll { tick - it.tick > PAYMENT_TICKS }
        }
    }

    /**
     * A hash of the whole world. Players compare it to find a copy that's gone
     * out of step. Each band of rows is hashed on its own, across the cores,
     * and the bands put together in order.
     */
    fun checksum(): Long {
        val bands = LongArray(height)
        Workers.run(height) { y ->
            var h = -0x340d631b7bdddcdbL + y
            val from = y * width
            for (i in from until from + width) {
                h = (h xor corners[corner(i - from, y)].toLong()) * 0x100000001b3L
                h = (h xor track[i].toLong()) * 0x100000001b3L
                h = (h xor wires[i].toLong()) * 0x100000001b3L
                h = (h xor (condition[i].toLong() shl 8 or fire[i].toLong())) * 0x100000001b3L
                h = (h xor trees[i].toLong()) * 0x100000001b3L
                h = (h xor owner[i].toLong()) * 0x100000001b3L
                h = (h xor road[i].toLong()) * 0x100000001b3L
                h = (h xor (roadKind[i].toLong() shl 8 or roadOwner[i].toLong())) * 0x100000001b3L
                h = (h xor house[i].toLong()) * 0x100000001b3L
                h = (h xor industryOf[i].toLong()) * 0x100000001b3L
                h = (h xor (weather.snow[i].toLong() or (weather.ice[i].toLong() shl 8) or (weather.wet[i].toLong() shl 16) or (weather.frost[i].toLong() shl 24))) * 0x100000001b3L
            }
            bands[y] = h
        }
        var h = -0x340d631b7bdddcdbL
        fun mix(v: Long) {
            h = (h xor v) * 0x100000001b3L
        }
        mix(tick)
        mix(rng.state)
        for (b in bands) mix(b)
        // The last row and column of corners, which the rows above leave out.
        for (x in 0..width) mix(heightAt(x, height).toLong())
        for (y in 0 until height) mix(heightAt(width, y).toLong())
        for (c in companies) { mix(c.money); mix(c.loan) }
        mix(costScale); mix(payScale)
        mix(weather.anomaly.toLong()); mix(weather.recent.toLong())
        for (f in weather.fronts) { mix(f.x.toLong()); mix(f.y.toLong()); mix(f.life.toLong()) }
        for (t in towns) { mix(t.population.toLong()); mix(t.growIn.toLong()); mix(t.servedThisMonth.toLong()); for (a in t.approval) mix(a.toLong()) }
        for (ind in industries) { mix(if (ind.closed) 1L else 0L); mix(ind.level.toLong()); mix(ind.brought.toLong()); for (m in ind.made) mix(m.toLong()) }
        for (s in stations) if (s != null) {
            for (c in s.cargo) for (p in c) { mix(p.dest.toLong()); mix(p.count.toLong()); mix(p.credit) }
            for (r in s.rating) mix(r.toLong())
        }
        for (k in signals.keys.sorted()) mix(k.toLong() * 31 + signals.getValue(k))
        for (k in stationTiles.keys.sorted()) mix(k.toLong() * 31 + stationTiles.getValue(k))
        for (k in depotTiles.keys.sorted()) mix(k.toLong() * 31 + depotTiles.getValue(k))
        for (k in reserved.keys.sorted()) mix(k.toLong() * 31 + reserved.getValue(k))
        for (k in closed.keys.sorted()) mix(k.toLong() * 31 + closed.getValue(k))
        for (k in kinks.keys.sorted()) mix(k.toLong() * 31 + kinks.getValue(k))
        for (k in fences.sorted()) mix(k.toLong())
        for (k in roadStops.keys.sorted()) mix(k.toLong() * 31 + roadStops.getValue(k))
        for (k in saltDepots.sorted()) mix(k.toLong() * 7)
        for (v in roadVehicles) if (v != null) { mix(v.id.toLong()); mix(v.at.toLong()); mix(v.along.toLong()); mix(v.speed.toLong()); for (c in v.cargo) for (p in c) mix(p.count.toLong()) }
        for (k in sheds.sorted()) mix(-k.toLong())
        for (k in canals.sorted()) mix(k.toLong() * 3)
        for (k in locks.keys.sorted()) mix(k.toLong() * 5 + locks.getValue(k))
        for (k in docks.keys.sorted()) mix(k.toLong() * 11 + docks.getValue(k))
        for (k in buoys.keys.sorted()) mix(k.toLong() * 13 + buoys.getValue(k))
        for (v in ships) if (v != null) { mix(v.id.toLong()); mix(v.x.toLong()); mix(v.y.toLong()); mix(v.speed.toLong()); mix(v.waiting.toLong()); for (c in v.cargo) for (p in c) mix(p.count.toLong()) }
        for (a in airports) if (a != null) { mix(a.id.toLong()); mix(a.station.toLong()); mix(if (a.ils) 1L else 0L); mix(if (a.deice) 1L else 0L) }
        for (v in aircraft) if (v != null) { mix(v.id.toLong()); mix(v.x.toLong()); mix(v.y.toLong()); mix(v.altitude.toLong()); mix(v.state.toLong()); mix(v.waiting.toLong()); for (c in v.cargo) for (p in c) mix(p.count.toLong()) }
        for (s in spans) if (s != null) { mix(s.x0.toLong()); mix(s.y0.toLong()); mix(s.x1.toLong()); mix(s.y1.toLong()); mix(s.height.toLong()) }
        for (t in trains) {
            if (t == null) continue
            mix(t.id.toLong()); mix(t.nose.toLong()); mix(t.speed.toLong()); mix(t.waiting.toLong()); mix(t.order.toLong())
            for (c in t.cargo) for (p in c) { mix(p.dest.toLong()); mix(p.count.toLong()) }
            mix(t.profitThisYear); mix(t.reliability.toLong()); mix(t.brokenFor.toLong())
            for (st in t.steps) mix(st.toLong())
        }
        return h
    }

    companion object {
        const val MAX_HEIGHT = 24
        /** Tiles a side in a block the renderer redraws together; map sides are a multiple of it. */
        const val CHUNK = 16
        /** Tiles a side in a block whose changes are counted together, for the renderer: small, so building redraws little. */
        const val BLOCK = 4
    }
}
