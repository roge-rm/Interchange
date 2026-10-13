package com.rm.interchange.sim

/**
 * Something that runs on the track, as the packs in a game make it. Speeds in
 * km/h, power in horsepower, weights in tonnes, money in dollars, running
 * costs a year, length in sixteenths of a tile.
 */
class Engine(
    /** Its place in the [Roster], which is what commands and saves use. */
    val id: Int,
    /** pack:id, the same in every game with its pack. */
    val key: String,
    val kind: VehicleKind,
    val names: Map<String, String>,
    /** What more than one is called, for wagons. */
    val plurals: Map<String, String>,
    /** Its type, as railwaymen called it: a wheel arrangement, or what a car is for. */
    val types: Map<String, String>,
    val from: Int,
    val until: Int,
    val speed: Int,
    val power: Int,
    val weight: Int,
    val cost: Long,
    val running: Long,
    val length: Int,
    /** What a wagon can carry, the first unless set otherwise, and how much. */
    val carries: List<String>,
    val capacity: Int,
    /** Its drawing, a place in [Roster.sprites]. */
    val sprite: Int,
    /** The tender it brings, an engine id, or -1. */
    val tender: Int,
    /** Turned off by a pack: kept so ids stay put, but not for sale. */
    val enabled: Boolean,
    /** Starting tractive effort, in kN: what the wheels can pull before they slip. */
    val tractive: Int = weight * 245 / 100,
    /** A [Traction] kind. */
    val traction: Int = Traction.STEAM,
    /** Years it runs well, and how reliable it is new, in percent. */
    val life: Int = 35,
    val reliability: Int = 85,
    /** A [Plows] kind for a snow plow, or [Plows.NONE]. */
    val plow: Int = Plows.NONE,
    /** A streetcar, running only on tram rails in the road. */
    val tram: Boolean = false,
    /** The ice a ship breaks through, in cm, 0 for none; a hovercraft rides over it. */
    val icebreaker: Int = 0,
    val hovercraft: Boolean = false,
    /** How far an aircraft flies, in km; the least airport it lands at, a [Runways] kind; a bush plane that takes floats and skis. */
    val range: Int = 0,
    val runway: Int = Runways.REGIONAL,
    val floats: Boolean = false,
) {
    val pulls get() = kind == VehicleKind.ENGINE
    /** What it can carry as [Cargo] numbers; empty for nothing. */
    val cargoes: IntArray = carries.map { Cargo.of(it) }.filter { it >= 0 }.toIntArray()
    val isTender get() = kind == VehicleKind.TENDER

    fun name(language: String) = names[language] ?: names["en"] ?: key
    fun plural(language: String) = plurals[language] ?: if (language == "en" || language !in names) plurals["en"] ?: (name("en") + "s") else name(language)
    fun type(language: String) = types[language] ?: types["en"] ?: ""
}

/** Snow plows: a wedge throws snow aside, a rotary blows it away through anything. */
object Plows {
    const val NONE = 0
    const val WEDGE = 1
    const val ROTARY = 2
    val NAMES = listOf("wedge", "rotary")
}

/** How an engine is driven. Electric ones need wires over the track. */
object Traction {
    const val STEAM = 0
    const val DIESEL = 1
    const val ELECTRIC = 2
    val NAMES = listOf("steam", "diesel", "electric")
}

/**
 * A kind of town building, as the packs in a game make it: who lives and works
 * in it, the years it's built in, how far into a town ([zones], [Zones] bits)
 * and how big a town has to be first. [weight] is how often it's picked among
 * those that fit.
 */
class House(
    /** Its place in [Roster.houses], which the map and saves use. */
    val id: Int,
    val key: String,
    val names: Map<String, String>,
    val from: Int,
    val until: Int,
    val residents: Int,
    val jobs: Int,
    val zones: Int,
    val minPopulation: Int,
    val weight: Int,
) {
    fun name(language: String) = names[language] ?: names["en"] ?: key
}

/**
 * A kind of industry, as the packs in a game make it. [layout] is its tile
 * pieces by row; [produces] what it makes a month of each [Cargo], and
 * [accepts] the cargo it takes, as bits by cargo number. One that takes
 * cargo makes its own from what it's brought.
 */
class IndustryKind(
    val id: Int,
    val key: String,
    val names: Map<String, String>,
    val from: Int,
    val until: Int,
    val regions: Map<String, Int>,
    val ground: List<String>,
    val layout: List<List<String>>,
    val produces: IntArray,
    val accepts: Int,
    /** The cargo it makes, as bits by cargo number, whether a month's worth of its own or from what it's brought. */
    val outputs: Int,
    /** Percent of its usual it makes each month, January first. */
    val seasons: IntArray = IntArray(12) { 100 },
    /** It stands on the shore, its layout's first row facing the water. */
    val water: Boolean = false,
    /** How the weather changes it, as bits: [CROPS], [THAW], [ICE]. */
    val weather: Int = 0,
) {
    /** The cargo it makes from what's brought, as bits: what it makes but has no monthly amount of its own for. */
    val converts: Int = (0 until Cargo.COUNT).fold(0) { m, c -> if (outputs and (1 shl c) != 0 && produces[c] == 0) m or (1 shl c) else m }
    fun name(language: String) = names[language] ?: names["en"] ?: key
    val width get() = layout[0].size
    val height get() = layout.size
    /** Makes its cargo from what's brought rather than of its own. */
    val secondary get() = converts != 0
    fun takes(c: Int) = accepts and (1 shl c) != 0
    /** How often it's placed in [region], by weight; 2 where it doesn't say. */
    fun weight(region: Region) = regions[region.name.lowercase()] ?: 2

    companion object {
        /** Crops that do best in ground neither parched nor soaked. */
        const val CROPS = 1
        /** Stops for the spring break-up, when thawing ground turns to mud. */
        const val THAW = 2
        /** Shut while the water it stands on is frozen. */
        const val ICE = 4
        /** The rules' names in a pack, in bit order. */
        val WEATHER = listOf("crops", "thaw", "ice")
    }
}

/** A vehicle drawing: the sheet [name] in pack [pack]. */
data class SpriteRef(val pack: String, val name: String)

/** Everything that can run on the track in one game, from its packs in order, base first. */
class Roster(
    val packs: List<PackRef>,
    val engines: List<Engine>,
    val sprites: List<SpriteRef>,
    val houses: List<House> = emptyList(),
    val industries: List<IndustryKind> = emptyList(),
) {
    operator fun get(id: Int) = engines[id]
    fun getOrNull(id: Int) = engines.getOrNull(id)

    /** What can be bought in [year]: engines if [pulls], else wagons. */
    fun available(year: Int, pulls: Boolean) = engines.filter { it.enabled && it.pulls == pulls && !it.isTender && (it.kind == VehicleKind.ENGINE || it.kind == VehicleKind.WAGON) && year >= it.from && year < it.until }

    /** The road vehicles on sale in [year]. */
    fun availableRoad(year: Int) = availableOf(VehicleKind.ROAD, year)

    /** The first year anything of [kind] is on sale, or null if nothing ever is. */
    fun firstOf(kind: VehicleKind): Int? = engines.filter { it.enabled && it.kind == kind }.minOfOrNull { it.from }

    /** What of [kind] is on sale in [year]. */
    fun availableOf(kind: VehicleKind, year: Int) = engines.filter { it.enabled && it.kind == kind && year >= it.from && year < it.until }

    companion object {
        val BASE: Roster by lazy { build(listOf(Packs.BASE)) }

        /** The roster from [packs], in order. Packs build on those before them: adding vehicles, changing them or turning them off. */
        fun build(packs: List<Pack>): Roster {
            val problems = ArrayList<String>()
            class Spec(val key: String, var def: VehicleDef, var sprite: SpriteRef?, var enabled: Boolean = true)
            val specs = ArrayList<Spec>()
            val byKey = HashMap<String, Spec>()
            val seen = HashSet<String>()
            fun qualify(pack: Pack, ref: String) = if (':' in ref) ref else "${pack.id}:$ref"
            for (pack in packs) {
                if (!seen.add(pack.id)) { problems += "${pack.id} is on twice"; continue }
                for (need in pack.needs) if (need !in seen) problems += "${pack.id} needs $need before it"
                for (v in pack.vehicles) {
                    val sprite = v.sprite?.let { SpriteRef(pack.id, it) }
                    if (v.replaces == null) {
                        val s = Spec(qualify(pack, v.id), v, sprite)
                        specs += s
                        byKey[s.key] = s
                        continue
                    }
                    val old = byKey[qualify(pack, v.replaces)] ?: run { problems += "${pack.id}:${v.id} replaces ${v.replaces}, which isn't there"; continue }
                    old.def = merge(old.def, v, pack)
                    if (sprite != null) old.sprite = sprite
                }
                for (d in pack.disables) byKey[qualify(pack, d)]?.let { it.enabled = false } ?: run { problems += "${pack.id} turns off $d, which isn't there" }
            }
            val sprites = ArrayList<SpriteRef>()
            val spriteIds = HashMap<SpriteRef, Int>()
            val ids = specs.withIndex().associate { (i, s) -> s.key to i }
            val engines = specs.mapIndexed { i, s ->
                val d = s.def
                val tender = d.tender?.let { t ->
                    val k = if (':' in t) t else s.key.substringBefore(':') + ":" + t
                    val at = ids[k]
                    if (at == null || specs[at].def.kind != VehicleKind.TENDER) { problems += "${s.key}: tender $t isn't a tender in these packs"; -1 } else at
                } ?: -1
                val sprite = s.sprite!!
                Engine(
                    id = i, key = s.key, kind = d.kind!!, names = d.names!!, plurals = d.plurals ?: emptyMap(), types = d.types ?: emptyMap(),
                    from = d.from!!, until = d.until!!, speed = d.speed ?: 0, power = d.power ?: 0, weight = d.weight!!,
                    cost = d.cost!!, running = d.running!!, length = d.length!!, carries = d.carries ?: emptyList(), capacity = d.capacity ?: 0,
                    sprite = spriteIds.getOrPut(sprite) { sprites += sprite; sprites.size - 1 }, tender = tender, enabled = s.enabled,
                    tractive = d.tractive ?: (d.weight!! * 245 / 100), traction = Traction.NAMES.indexOf(d.traction ?: "steam").coerceAtLeast(0),
                    life = d.life ?: 35, reliability = d.reliability ?: 85,
                    plow = d.plow?.let { Plows.NAMES.indexOf(it) + 1 } ?: Plows.NONE,
                    tram = d.tram ?: false,
                    icebreaker = d.icebreaker ?: 0, hovercraft = d.hovercraft ?: false,
                    range = d.range ?: 0, runway = d.runway?.let { Runways.NAMES.indexOf(it) } ?: Runways.REGIONAL, floats = d.floats ?: false,
                )
            }
            val houses = packs.flatMap { p -> p.houses.map { p.id to it } }.mapIndexed { i, (pack, h) ->
                House(i, "$pack:${h.id}", h.names, h.from, h.until, h.residents, h.jobs, h.zones, h.minPopulation, h.weight)
            }
            val industries = packs.flatMap { p -> p.industries.map { p.id to it } }.mapIndexed { i, (pack, d) ->
                IndustryKind(
                    i, "$pack:${d.id}", d.names, d.from, d.until, d.regions, d.ground, d.layout,
                    IntArray(Cargo.COUNT) { c -> d.produces[Cargo.IDS[c]] ?: 0 },
                    d.accepts.fold(0) { m, cg -> Cargo.of(cg).let { if (it >= 0) m or (1 shl it) else m } },
                    d.produces.keys.fold(0) { m, cg -> Cargo.of(cg).let { if (it >= 0) m or (1 shl it) else m } },
                    d.seasons.toIntArray(), d.water,
                    d.weather.fold(0) { m, r -> IndustryKind.WEATHER.indexOf(r).let { if (it >= 0) m or (1 shl it) else m } },
                )
            }
            if (problems.isNotEmpty()) throw BadPack(problems)
            return Roster(packs.map { it.ref }, engines, sprites, houses, industries)
        }

        /** [old] with whatever [v] gives in its place. */
        private fun merge(old: VehicleDef, v: VehicleDef, pack: Pack) = VehicleDef(
            id = old.id, replaces = null, kind = old.kind,
            names = v.names ?: old.names, plurals = v.plurals ?: old.plurals, types = v.types ?: old.types,
            from = v.from ?: old.from, until = v.until ?: old.until, speed = v.speed ?: old.speed, power = v.power ?: old.power,
            weight = v.weight ?: old.weight, cost = v.cost ?: old.cost, running = v.running ?: old.running, length = v.length ?: old.length,
            carries = v.carries ?: old.carries, capacity = v.capacity ?: old.capacity,
            tender = v.tender?.let { if (':' in it) it else "${pack.id}:$it" } ?: old.tender,
            sprite = v.sprite ?: old.sprite,
            tractive = v.tractive ?: old.tractive, traction = v.traction ?: old.traction,
            life = v.life ?: old.life, reliability = v.reliability ?: old.reliability,
            plow = v.plow ?: old.plow,
            tram = v.tram ?: old.tram,
            icebreaker = v.icebreaker ?: old.icebreaker, hovercraft = v.hovercraft ?: old.hovercraft,
            range = v.range ?: old.range, runway = v.runway ?: old.runway, floats = v.floats ?: old.floats,
        )
    }
}
