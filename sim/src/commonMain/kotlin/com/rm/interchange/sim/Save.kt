package com.rm.interchange.sim

/**
 * A whole world as bytes, and back: what a saved game is, and what a player
 * joining a multiplayer game is sent. Everything that decides what happens
 * next is in it, trains' planned routes too, so a loaded world carries on
 * exactly as the one saved would have. What can be worked out again (signal
 * blocks, who's on which tile) is left out and worked out on loading.
 *
 * The file is MAGIC, the format's version, then the rest squeezed. A newer
 * version reads older ones; each change to the format bumps [VERSION].
 *
 * A save names the packs it was played with, by [PackRef], and the key of
 * each vehicle in their roster. It loads with the same packs at that version
 * or newer, its vehicles found again by key.
 */
object Save {
    private val MAGIC = "ICHG".encodeToByteArray()
    const val VERSION = 16

    fun write(world: World): ByteArray {
        val w = ByteWriter(world.width * world.height * 6 + 4096)
        with(world) {
            val g = settings
            w.int(g.width); w.int(g.height); w.long(g.seed); w.int(g.startYear); w.int(g.region.ordinal)
            w.int(g.sea); w.int(g.hills); w.int(g.trees); w.int(g.towns); w.int(g.industries); w.bool(g.inflation); w.bool(g.weather)
            w.uvar(roster.packs.size.toLong())
            for (p in roster.packs) { w.string(p.id); w.int(p.version); w.long(p.hash) }
            w.uvar(roster.engines.size.toLong())
            for (e in roster.engines) w.string(e.key)
            w.uvar(roster.houses.size.toLong())
            for (h in roster.houses) w.string(h.key)
            w.long(tick)
            w.long(rng.state)
            w.uvar(companies.size.toLong())
            for (c in companies) {
                w.int(c.id); w.string(c.name); w.long(c.money); w.long(c.runningOwed)
                w.long(c.loan); w.long(c.monthIn); w.long(c.monthOut)
                for (v in c.thisYear) w.long(v)
                for (v in c.lastYear) w.long(v)
                w.int(c.upkeep); w.long(c.upkeepOwed)
                w.uvar(c.history.size.toLong())
                for (h in c.history) for (v in h) w.long(v)
            }
            w.long(costScale); w.long(payScale)
            w.bytes(corners); w.bytes(ground); w.bytes(trees); w.bytes(track); w.bytes(owner)
            w.uvar(stations.size.toLong())
            for (s in stations) {
                w.bool(s != null)
                if (s == null) continue
                w.int(s.owner); w.string(s.name); w.int(s.signX); w.int(s.signY); w.bool(s.waypoint); w.ints(s.tiles.toIntArray()); w.ints(s.stops.toIntArray())
                writeCargo(w, s.cargo)
                w.ints(s.rating); w.uvar(s.lastCall.size.toLong()); for (v in s.lastCall) w.long(v); w.ints(s.lastSpeed)
            }
            w.uvar(depots.size.toLong())
            for (d in depots) {
                w.bool(d != null)
                if (d == null) continue
                w.int(d.owner); w.int(d.tile); w.int(d.edge); w.bool(d.road)
            }
            val keys = signals.keys.sorted()
            w.uvar(keys.size.toLong())
            for (k in keys) { w.int(k); w.int(signals.getValue(k)) }
            w.uvar(trains.size.toLong())
            for (t in trains) {
                w.bool(t != null)
                if (t == null) continue
                w.int(t.owner); w.int(t.engine); w.int(t.wagon); w.int(t.wagons); w.int(t.depot); w.int(t.wagonCargo); w.string(t.name)
                w.bool(t.inDepot); w.bool(t.running); w.int(t.waiting)
                w.ints(t.steps.toIntArray()); w.int(t.nose); w.int(t.speed)
                w.ints(t.orders.toIntArray()); w.int(t.order); w.bool(t.toDepot)
                val r = t.route
                w.bool(r != null)
                if (r != null) w.ints(r)
                w.int(t.routeAt); w.bool(t.turnRound); w.long(t.lostUntil)
                writeCargo(w, t.cargo); w.long(t.profitThisYear); w.long(t.profitLastYear)
                w.int(t.shared)
                w.int(t.built); w.int(t.serviced); w.int(t.reliability); w.int(t.breakdowns); w.int(t.brokenFor); w.bool(t.servicing); w.bool(t.service)
            }
            w.uvar(towns.size.toLong())
            for (t in towns) { w.string(t.name); w.int(t.x); w.int(t.y); w.int(t.grid); w.bool(t.french); w.int(t.population); w.int(t.houses); w.int(t.growIn); w.int(t.servedThisMonth); w.int(t.servedLastMonth); w.ints(t.approval) }
            w.bytes(road); w.shorts(house); w.shorts(houseYear); w.shorts(townOf)
            w.uvar(roster.industries.size.toLong())
            for (k in roster.industries) w.string(k.key)
            w.uvar(industries.size.toLong())
            for (ind in industries) {
                w.int(ind.kind); w.int(ind.x); w.int(ind.y); w.int(ind.turn); w.int(ind.town); w.int(ind.level); w.int(ind.brought)
                w.ints(ind.made); w.ints(ind.madeLastMonth); w.ints(ind.moved); w.ints(ind.movedLastMonth)
                w.int(ind.broughtThisMonth); w.int(ind.broughtLastMonth); w.bool(ind.closed)
            }
            w.shorts(industryOf)
            w.uvar(spans.size.toLong())
            for (s in spans) {
                w.bool(s != null)
                if (s == null) continue
                w.int(s.owner); w.bool(s.tunnel); w.int(s.style); w.int(s.x0); w.int(s.y0); w.int(s.x1); w.int(s.y1); w.int(s.height); w.bool(s.wired)
            }
            w.bytes(wires)
            w.bytes(condition); w.bytes(fire)
            for (m in listOf(kinks, closed)) {
                val ks = m.keys.sorted()
                w.uvar(ks.size.toLong())
                for (k in ks) { w.int(k); w.int(m.getValue(k)) }
            }
            w.ints(fences.sorted().toIntArray()); w.ints(sheds.sorted().toIntArray())
            w.bytes(roadKind); w.bytes(roadOwner); w.ints(saltDepots.sorted().toIntArray())
            w.uvar(roadVehicles.size.toLong())
            for (v in roadVehicles) {
                w.bool(v != null)
                if (v == null) continue
                w.int(v.owner); w.int(v.model); w.int(v.depot); w.int(v.cargoChoice); w.string(v.name)
                w.bool(v.inDepot); w.bool(v.running); w.int(v.waiting); w.int(v.at); w.int(v.along); w.int(v.speed)
                writeCargo(w, v.cargo); w.long(v.profitThisYear); w.long(v.profitLastYear)
                w.ints(v.orders.toIntArray()); w.int(v.order); w.bool(v.toDepot)
                val r = v.route
                w.bool(r != null)
                if (r != null) w.ints(r)
                w.int(v.routeAt); w.long(v.lostUntil); w.int(v.built); w.int(v.serviced); w.int(v.reliability); w.int(v.breakdowns); w.int(v.brokenFor)
                w.bool(v.servicing); w.bool(v.service); w.int(v.tires); w.bool(v.seasonalTires); w.bool(v.slid)
            }
            val held = reserved.keys.sorted()
            w.uvar(held.size.toLong())
            for (k in held) { w.int(k); w.int(reserved.getValue(k)) }
            val wx = weather
            w.bytes(wx.snow); w.bytes(wx.ice); w.bytes(wx.wet); w.bytes(wx.frost)
            w.int(wx.anomaly); w.int(wx.recent); w.int(wx.windFrom); w.int(wx.wind)
            w.uvar(wx.fronts.size.toLong())
            for (f in wx.fronts) { w.int(f.kind); w.int(f.x); w.int(f.y); w.int(f.dx); w.int(f.dy); w.int(f.radius); w.int(f.strength); w.int(f.warmth); w.int(f.life); w.bool(f.freezing) }
        }
        val body = Squeeze.pack(w.toByteArray())
        val out = ByteWriter(body.size + 16)
        for (b in MAGIC) out.byte(b.toInt())
        out.int(VERSION)
        out.bytes(body)
        return out.toByteArray()
    }

    /** The packs a save needs, as named in it, without reading the rest. */
    fun packs(data: ByteArray): List<PackRef> = body(data).let { (version, r) -> readSettings(version, r); readPacks(version, r) }

    /**
     * Reads a world, finding the packs it needs with [find]: the pack with that
     * id at that version or newer, or null if it hasn't got one.
     */
    fun read(data: ByteArray, find: (PackRef) -> Pack? = ::basePack): World {
        val (version, r) = body(data)
        val settings = readSettings(version, r)
        val refs = readPacks(version, r)
        val found = refs.map { find(it) }
        val missing = refs.filterIndexed { i, _ -> found[i] == null }
        if (missing.isNotEmpty()) throw MissingPacks(missing)
        val roster = try {
            Roster.build(found.map { it!! })
        } catch (e: BadPack) {
            throw BadData(e.problems.first())
        }
        // Each saved vehicle's place in this roster.
        val keys = if (version < 2) Roster.BASE.engines.map { it.key } else List(r.uvar().toInt().also { if (it > 65536) throw BadData("too many vehicles") }) { r.string() }
        val ids = roster.engines.associate { it.key to it.id }
        val remap = IntArray(keys.size) { ids[keys[it]] ?: throw BadData("${keys[it]} is no longer in its pack") }
        fun vehicle(n: Int) = remap.getOrNull(n) ?: throw BadData("a train of an unknown vehicle")
        val houseIds = roster.houses.associate { it.key to it.id }
        val houseRemap = if (version < 3) IntArray(0) else {
            val keys = List(r.uvar().toInt().also { if (it > 4095) throw BadData("too many buildings") }) { r.string() }
            IntArray(keys.size) { houseIds[keys[it]] ?: throw BadData("${keys[it]} is no longer in its pack") }
        }
        val world = World(settings, roster, generate = false)
        with(world) {
            tick = r.long()
            rng.state = r.long()
            companies.clear()
            repeat(r.uvar().toInt()) {
                val c = Company(r.int(), r.string(), r.long()).also { it.runningOwed = r.long() }
                if (version >= 8) {
                    c.loan = r.long(); c.monthIn = r.long(); c.monthOut = r.long()
                    // Upkeep was booked from version 14.
                    val books = if (version >= 14) Books.COUNT else 6
                    for (k in 0 until books) c.thisYear[k] = r.long()
                    for (k in 0 until books) c.lastYear[k] = r.long()
                    if (version >= 14) { c.upkeep = r.int(); c.upkeepOwed = r.long() }
                    repeat(r.uvar().toInt().also { if (it > HISTORY_MONTHS) throw BadData("too long a history") }) { c.history += LongArray(3) { r.long() } }
                }
                companies += c
            }
            if (version >= 8) { costScale = r.long(); payScale = r.long() }
            fill(corners, r.bytes()); fill(ground, r.bytes()); fill(trees, r.bytes()); fill(track, r.bytes()); fill(owner, r.bytes())
            repeat(r.uvar().toInt()) { id ->
                if (!r.bool()) { stations += null; return@repeat }
                val s = Station(id, r.int(), r.string(), r.int(), r.int(), if (version >= 12) r.bool() else false)
                for (tile in r.ints()) { s.tiles += tile; stationTiles[tile] = id }
                if (version >= 16) for (tile in r.ints()) { s.stops += tile; roadStops[tile] = id }
                when {
                    version >= 5 -> readCargo(r, s.cargo, version)
                    // Before journeys, what waited could go anywhere.
                    version == 4 -> r.ints().forEachIndexed { c, n -> if (c < Cargo.COUNT && n > 0) s.cargo[c] += Packet(-1, id, n, 0) }
                }
                if (version >= 6) {
                    r.ints().let { a -> a.copyInto(s.rating, endIndex = minOf(a.size, Cargo.COUNT)) }
                    // Version 6 had passengers and mail only, and no count.
                    val calls = if (version >= 7) r.uvar().toInt().also { if (it > 64) throw BadData("too many kinds of cargo") } else 2
                    for (c in 0 until calls) r.long().let { v -> if (c < Cargo.COUNT) s.lastCall[c] = v }
                    r.ints().let { a -> a.copyInto(s.lastSpeed, endIndex = minOf(a.size, Cargo.COUNT)) }
                }
                stations += s
            }
            repeat(r.uvar().toInt()) { id ->
                if (!r.bool()) { depots += null; return@repeat }
                val d = Depot(id, r.int(), r.int(), r.int(), if (version >= 16) r.bool() else false)
                depotTiles[d.tile] = id
                depots += d
            }
            repeat(r.uvar().toInt()) { signals[r.int()] = r.int() }
            repeat(r.uvar().toInt()) { id ->
                if (!r.bool()) { trains += null; return@repeat }
                val owner = r.int()
                val engine = vehicle(r.int())
                val wagon = vehicle(r.int())
                val wagons = r.int()
                val depot = r.int()
                val t = Train(roster, id, owner, engine, wagon, wagons, depot, if (version >= 7) r.int() else -1)
                t.name = r.string()
                t.inDepot = r.bool(); t.running = r.bool(); t.waiting = r.int()
                // Before bridges a step had no level and its tile one bit lower.
                fun stepOf(v: Int) = if (version >= 13) v else ((v ushr 5) shl 6) or (v and 31)
                t.steps += r.ints().map(::stepOf); t.nose = r.int(); t.speed = r.int()
                t.orders += r.ints().toList(); t.order = r.int(); t.toDepot = r.bool()
                t.route = if (r.bool()) r.ints().map(::stepOf).toIntArray() else null
                t.routeAt = r.int(); t.turnRound = r.bool(); t.lostUntil = r.long()
                if (version >= 5) {
                    readCargo(r, t.cargo, version)
                    t.profitThisYear = r.long(); t.profitLastYear = r.long()
                    if (version >= 9) t.shared = r.int()
                    if (version >= 11) {
                        t.built = r.int(); t.serviced = r.int(); t.reliability = r.int(); t.breakdowns = r.int(); t.brokenFor = r.int()
                        t.servicing = r.bool(); t.service = r.bool()
                    } else {
                        t.built = (tick / Calendar.TICKS_PER_DAY).toInt()
                        t.serviced = t.built
                        t.reliability = t.engineInfo.reliability * 100
                    }
                } else if (version == 4) {
                    val load = r.ints()
                    val at = r.ints()
                    val since = LongArray(load.size) { r.long() }
                    for (c in load.indices) if (c < Cargo.COUNT && load[c] > 0) t.cargo[c] += Packet(-1, at.getOrElse(c) { -1 }, load[c], since[c], 0, at.getOrElse(c) { -1 })
                    t.profitThisYear = r.long(); t.profitLastYear = r.long()
                }
                trains += t
            }
            if (version >= 3) {
                repeat(r.uvar().toInt()) { id ->
                    val t = Town(id, r.string(), r.int(), r.int(), r.int(), if (version >= 4) r.bool() else false)
                    t.population = r.int(); t.houses = r.int(); t.growIn = r.int()
                    if (version >= 4) { t.servedThisMonth = r.int(); t.servedLastMonth = r.int() }
                    if (version >= 6) r.ints().let { a -> a.copyInto(t.approval, endIndex = minOf(a.size, MAX_COMPANIES)) }
                    towns += t
                }
                fill(road, r.bytes())
                val h = r.shorts()
                if (h.size != house.size) throw BadData("a map layer is the wrong size")
                for (k in h.indices) {
                    val v = h[k].toInt()
                    if (v == 0) continue
                    val n = houseRemap.getOrNull(houseOf(v)) ?: throw BadData("a building of an unknown kind")
                    house[k] = packHouse(n, houseFacing(v)).toShort()
                }
                fillShorts(houseYear, r.shorts()); fillShorts(townOf, r.shorts())
            }
            if (version >= 7) {
                val kindIds = roster.industries.associate { it.key to it.id }
                val kinds = List(r.uvar().toInt().also { if (it > 4095) throw BadData("too many kinds of industry") }) { r.string() }
                val kindRemap = IntArray(kinds.size) { kindIds[kinds[it]] ?: throw BadData("${kinds[it]} is no longer in its pack") }
                repeat(r.uvar().toInt()) { id ->
                    val kind = kindRemap.getOrNull(r.int()) ?: throw BadData("an industry of an unknown kind")
                    val ind = Industry(id, kind, r.int(), r.int(), r.int(), r.int())
                    ind.level = r.int(); ind.brought = r.int()
                    fun into(a: IntArray) = r.ints().let { v -> v.copyInto(a, endIndex = minOf(v.size, Cargo.COUNT)) }
                    into(ind.made); into(ind.madeLastMonth); into(ind.moved); into(ind.movedLastMonth)
                    ind.broughtThisMonth = r.int(); ind.broughtLastMonth = r.int(); ind.closed = r.bool()
                    industries += ind
                }
                fillShorts(industryOf, r.shorts())
            }
            if (version >= 13) repeat(r.uvar().toInt().also { if (it > 1_000_000) throw BadData("too many bridges") }) { id ->
                if (!r.bool()) { spans += null; return@repeat }
                val s = Span(id, r.int(), r.bool(), r.int(), r.int(), r.int(), r.int(), r.int(), r.int())
                s.wired = r.bool()
                if (!inside(s.x0, s.y0) || !inside(s.x1, s.y1) || s.length < 0) throw BadData("a bridge off the map")
                spans += s
                spanHeads[index(s.x0, s.y0)] = id
                spanHeads[index(s.x1, s.y1)] = id
                val dx = (s.x1 - s.x0).coerceIn(-1, 1)
                val dy = (s.y1 - s.y0).coerceIn(-1, 1)
                for (k in 1..s.length) deck[index(s.x0 + dx * k, s.y0 + dy * k)] = id
            }
            if (version >= 13) fill(wires, r.bytes())
            if (version >= 14) {
                fill(condition, r.bytes()); fill(fire, r.bytes())
                for (m in listOf(kinks, closed)) repeat(r.uvar().toInt().also { if (it > 10_000_000) throw BadData("too many closures") }) { m[r.int()] = r.int() }
            }
            if (version >= 15) { fences += r.ints().toList(); sheds += r.ints().toList() }
            if (version >= 16) {
                fill(roadKind, r.bytes()); fill(roadOwner, r.bytes()); saltDepots += r.ints().toList()
                repeat(r.uvar().toInt().also { if (it > 1_000_000) throw BadData("too many road vehicles") }) { id ->
                    if (!r.bool()) { roadVehicles += null; return@repeat }
                    val owner = r.int()
                    val model = vehicle(r.int())
                    val v = RoadVehicle(roster, id, owner, model, r.int(), r.int())
                    v.name = r.string()
                    v.inDepot = r.bool(); v.running = r.bool(); v.waiting = r.int(); v.at = r.int(); v.along = r.int(); v.speed = r.int()
                    readCargo(r, v.cargo, version); v.profitThisYear = r.long(); v.profitLastYear = r.long()
                    v.orders += r.ints().toList(); v.order = r.int(); v.toDepot = r.bool()
                    v.route = if (r.bool()) r.ints() else null
                    v.routeAt = r.int(); v.lostUntil = r.long(); v.built = r.int(); v.serviced = r.int(); v.reliability = r.int(); v.breakdowns = r.int(); v.brokenFor = r.int()
                    v.servicing = r.bool(); v.service = r.bool(); v.tires = r.int(); v.seasonalTires = r.bool(); v.slid = r.bool()
                    roadVehicles += v
                }
            } else {
                // Before company roads, a town's paved road was paved and the rest dirt.
                for (i in road.indices) if (road[i].toInt() and PAVED != 0) roadKind[i] = Surface.PAVED.toByte()
            }
            if (version >= 12) repeat(r.uvar().toInt().also { if (it > 10_000_000) throw BadData("too many held pieces") }) { reserved[r.int()] = r.int() }
            if (version >= 10) {
                val wx = weather
                fill(wx.snow, r.bytes()); fill(wx.ice, r.bytes()); fill(wx.wet, r.bytes()); fill(wx.frost, r.bytes())
                wx.anomaly = r.int(); wx.recent = r.int(); wx.windFrom = r.int(); wx.wind = r.int()
                repeat(r.uvar().toInt().also { if (it > 4096) throw BadData("too many weather fronts") }) {
                    wx.fronts += Front(r.int(), r.int(), r.int(), r.int(), r.int(), r.int(), r.int(), r.int(), r.int(), r.bool())
                }
            }
        }
        // Signal blocks and who's on which tile are worked out afresh before the next move.
        world.networkChangedKeepingRoutes()
        return world
    }

    private fun body(data: ByteArray): Pair<Int, ByteReader> {
        val head = ByteReader(data)
        for (b in MAGIC) if (head.byte() != (b.toInt() and 0xff)) throw BadData("not an Interchange save")
        val version = head.int()
        if (version < 1 || version > VERSION) throw BadData("saved by a newer version of the game ($version)")
        return version to ByteReader(Squeeze.unpack(head.bytes()))
    }

    private fun readSettings(version: Int, r: ByteReader): NewGame {
        val settings = NewGame(
            width = r.int(), height = r.int(), seed = r.long(), startYear = r.int(),
            region = Region.entries.getOrElse(r.int()) { Region.MIXED },
            sea = r.int(), hills = r.int(), trees = r.int(),
            // Before version 3 there were no towns.
            towns = if (version >= 3) r.int() else 0,
            industries = if (version >= 7) r.int() else 0,
            inflation = if (version >= 8) r.bool() else false,
            weather = if (version >= 10) r.bool() else true,
        )
        if (settings.width !in 16..4096 || settings.height !in 16..4096) throw BadData("map size out of range")
        return settings
    }

    /** Version 1 saves came before packs, with the base pack's first version. */
    private fun readPacks(version: Int, r: ByteReader): List<PackRef> {
        if (version < 2) return listOf(Packs.BASE.ref)
        return List(r.uvar().toInt().also { if (it > 256) throw BadData("too many packs") }) { PackRef(r.string(), r.int(), r.long()) }
    }

    private fun basePack(ref: PackRef) = Packs.BASE.takeIf { it.id == ref.id && it.version >= ref.version }

    private fun writeCargo(w: ByteWriter, cargo: Array<ArrayList<Packet>>) {
        w.uvar(cargo.size.toLong())
        for (lots in cargo) {
            w.uvar(lots.size.toLong())
            for (p in lots) { w.int(p.dest); w.int(p.origin); w.int(p.count); w.long(p.since); w.long(p.credit); w.int(p.boarded); w.long(p.waitingFrom) }
        }
    }

    private fun readCargo(r: ByteReader, into: Array<ArrayList<Packet>>, version: Int) {
        val kinds = r.uvar().toInt()
        if (kinds > 64) throw BadData("too many kinds of cargo")
        for (c in 0 until kinds) {
            val n = r.uvar().toInt()
            if (n > 1_000_000) throw BadData("too many lots of cargo")
            repeat(n) {
                val p = Packet(r.int(), r.int(), r.int(), r.long(), r.long(), r.int())
                if (version >= 7) p.waitingFrom = r.long()
                if (c < into.size) into[c] += p
            }
        }
    }

    private fun fillShorts(into: ShortArray, from: ShortArray) {
        if (from.size != into.size) throw BadData("a map layer is the wrong size")
        from.copyInto(into)
    }

    private fun fill(into: ByteArray, from: ByteArray) {
        if (from.size != into.size) throw BadData("a map layer is the wrong size")
        from.copyInto(into)
    }
}
