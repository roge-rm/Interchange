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
    const val VERSION = 2

    fun write(world: World): ByteArray {
        val w = ByteWriter(world.width * world.height * 6 + 4096)
        with(world) {
            val g = settings
            w.int(g.width); w.int(g.height); w.long(g.seed); w.int(g.startYear); w.int(g.region.ordinal)
            w.int(g.sea); w.int(g.hills); w.int(g.trees)
            w.uvar(roster.packs.size.toLong())
            for (p in roster.packs) { w.string(p.id); w.int(p.version); w.long(p.hash) }
            w.uvar(roster.engines.size.toLong())
            for (e in roster.engines) w.string(e.key)
            w.long(tick)
            w.long(rng.state)
            w.uvar(companies.size.toLong())
            for (c in companies) { w.int(c.id); w.string(c.name); w.long(c.money); w.long(c.runningOwed) }
            w.bytes(corners); w.bytes(ground); w.bytes(trees); w.bytes(track); w.bytes(owner)
            w.uvar(stations.size.toLong())
            for (s in stations) {
                w.bool(s != null)
                if (s == null) continue
                w.int(s.owner); w.string(s.name); w.int(s.signX); w.int(s.signY); w.ints(s.tiles.toIntArray())
            }
            w.uvar(depots.size.toLong())
            for (d in depots) {
                w.bool(d != null)
                if (d == null) continue
                w.int(d.owner); w.int(d.tile); w.int(d.edge)
            }
            val keys = signals.keys.sorted()
            w.uvar(keys.size.toLong())
            for (k in keys) { w.int(k); w.int(signals.getValue(k)) }
            w.uvar(trains.size.toLong())
            for (t in trains) {
                w.bool(t != null)
                if (t == null) continue
                w.int(t.owner); w.int(t.engine); w.int(t.wagon); w.int(t.wagons); w.int(t.depot); w.string(t.name)
                w.bool(t.inDepot); w.bool(t.running); w.int(t.waiting)
                w.ints(t.steps.toIntArray()); w.int(t.nose); w.int(t.speed)
                w.ints(t.orders.toIntArray()); w.int(t.order); w.bool(t.toDepot)
                val r = t.route
                w.bool(r != null)
                if (r != null) w.ints(r)
                w.int(t.routeAt); w.bool(t.turnRound); w.long(t.lostUntil)
            }
        }
        val body = Squeeze.pack(w.toByteArray())
        val out = ByteWriter(body.size + 16)
        for (b in MAGIC) out.byte(b.toInt())
        out.int(VERSION)
        out.bytes(body)
        return out.toByteArray()
    }

    /** The packs a save needs, as named in it, without reading the rest. */
    fun packs(data: ByteArray): List<PackRef> = body(data).let { (version, r) -> readSettings(r); readPacks(version, r) }

    /**
     * Reads a world, finding the packs it needs with [find]: the pack with that
     * id at that version or newer, or null if it hasn't got one.
     */
    fun read(data: ByteArray, find: (PackRef) -> Pack? = ::basePack): World {
        val (version, r) = body(data)
        val settings = readSettings(r)
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
        val world = World(settings, roster, generate = false)
        with(world) {
            tick = r.long()
            rng.state = r.long()
            companies.clear()
            repeat(r.uvar().toInt()) { companies += Company(r.int(), r.string(), r.long()).also { it.runningOwed = r.long() } }
            fill(corners, r.bytes()); fill(ground, r.bytes()); fill(trees, r.bytes()); fill(track, r.bytes()); fill(owner, r.bytes())
            repeat(r.uvar().toInt()) { id ->
                if (!r.bool()) { stations += null; return@repeat }
                val s = Station(id, r.int(), r.string(), r.int(), r.int())
                for (tile in r.ints()) { s.tiles += tile; stationTiles[tile] = id }
                stations += s
            }
            repeat(r.uvar().toInt()) { id ->
                if (!r.bool()) { depots += null; return@repeat }
                val d = Depot(id, r.int(), r.int(), r.int())
                depotTiles[d.tile] = id
                depots += d
            }
            repeat(r.uvar().toInt()) { signals[r.int()] = r.int() }
            repeat(r.uvar().toInt()) { id ->
                if (!r.bool()) { trains += null; return@repeat }
                val t = Train(roster, id, r.int(), vehicle(r.int()), vehicle(r.int()), r.int(), r.int())
                t.name = r.string()
                t.inDepot = r.bool(); t.running = r.bool(); t.waiting = r.int()
                t.steps += r.ints().toList(); t.nose = r.int(); t.speed = r.int()
                t.orders += r.ints().toList(); t.order = r.int(); t.toDepot = r.bool()
                t.route = if (r.bool()) r.ints() else null
                t.routeAt = r.int(); t.turnRound = r.bool(); t.lostUntil = r.long()
                trains += t
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

    private fun readSettings(r: ByteReader): NewGame {
        val settings = NewGame(
            width = r.int(), height = r.int(), seed = r.long(), startYear = r.int(),
            region = Region.entries.getOrElse(r.int()) { Region.MIXED },
            sea = r.int(), hills = r.int(), trees = r.int(),
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

    private fun fill(into: ByteArray, from: ByteArray) {
        if (from.size != into.size) throw BadData("a map layer is the wrong size")
        from.copyInto(into)
    }
}
