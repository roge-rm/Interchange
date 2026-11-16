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
    /** A dual-mode engine's power in hp and top speed in km/h on its diesel, off the wires. */
    val dieselPower: Int = power,
    val dieselSpeed: Int = speed,
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
    /** Its drawing off the wires, a place in [Roster.sprites]: a dual-mode engine's with its pantograph down. */
    val offWireSprite: Int = sprite,
) {
    val pulls get() = kind == VehicleKind.ENGINE
    /** Its power and top speed with wires overhead or without them. */
    fun power(wired: Boolean) = if (traction == Traction.DUAL && !wired) dieselPower else power
    fun speed(wired: Boolean) = if (traction == Traction.DUAL && !wired) dieselSpeed else speed
    /** Its drawing with wires overhead or without them. */
    fun sprite(wired: Boolean) = if (wired) sprite else offWireSprite
    /** What it can carry as [Cargo] numbers; empty for nothing. */
    val cargoes: IntArray = carries.map { Cargo.of(it) }.filter { it >= 0 }.toIntArray()
    val isTender get() = kind == VehicleKind.TENDER

    fun name(language: String) = names[language] ?: names["en"] ?: key
    fun plural(language: String) = plurals[language] ?: if (language == "en" || language !in names) plurals["en"] ?: (name("en") + "s") else name(language)
    fun type(language: String) = types[language] ?: types["en"] ?: ""
}

/**
 * Snow plows: a wedge throws snow aside, a rotary blows it away through anything. A one-way plow throws it all to one
 * side; only road vehicles have them, and they clear the road as a wedge does.
 */
object Plows {
    const val NONE = 0
    const val WEDGE = 1
    const val ROTARY = 2
    const val ONE_WAY = 3
    val NAMES = listOf("wedge", "rotary", "oneway")
}

/**
 * How an engine is driven. Electric ones need wires over the track. Dual-mode ones run anywhere, on their electric
 * power under wires and on their diesel off them. Horses pull road vehicles: shod, they need no
 * winter tires, and in winter their vehicles go on sleigh runners, so deep snow doesn't hold them up.
 */
object Traction {
    const val STEAM = 0
    const val DIESEL = 1
    const val ELECTRIC = 2
    const val HORSE = 3
    const val DUAL = 4
    val NAMES = listOf("steam", "diesel", "electric", "horse", "dual")
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
    /** A statue and the like: put up when a company pays for it, never built, rebuilt or pulled down by the town. */
    val monument: Boolean = false,
    /** Its drawing from a pack, a place in [Roster.sprites], or -1 for one the game draws itself. */
    val sprite: Int = -1,
    /** Turned off by a pack: kept so ids stay put, but no town builds it. */
    val enabled: Boolean = true,
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
    /** Turned off by a pack: kept so ids stay put, but no new ones open. */
    val enabled: Boolean = true,
    /** Drawings from packs for layout parts, by part name, as places in [Roster.sprites]; parts not here the game draws itself. */
    val parts: Map<String, Int> = emptyMap(),
    /** By cargo number, a rule for its monthly amount in place of the usual, or null. */
    val production: Array<Rule?> = arrayOfNulls(Cargo.COUNT),
    /** A rule that's 0 where and when one can't open, and one that closes it at the month when it isn't 0. */
    val open: Rule? = null,
    val closes: Rule? = null,
) {
    /** The cargo it makes from what's brought, as bits: what it makes but has no monthly amount or rule of its own for. */
    val converts: Int = (0 until Cargo.COUNT).fold(0) { m, c -> if (outputs and (1 shl c) != 0 && produces[c] == 0 && production[c] == null) m or (1 shl c) else m }
    /** Makes cargo of its own each month. */
    val primary get() = produces.any { it > 0 } || production.any { it != null }
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

/** A drawing of a vehicle, a town building or an industry part: the sheet [name] in pack [pack]. */
data class SpriteRef(val pack: String, val name: String)

/** Everything that can run on the track in one game, from its packs in order, base first. */
class Roster(
    val packs: List<PackRef>,
    val engines: List<Engine>,
    val sprites: List<SpriteRef>,
    val houses: List<House> = emptyList(),
    val industries: List<IndustryKind> = emptyList(),
) {
    /** The places in [sprites] that houses and industry parts draw with, each once and in order: the map gives each four cells of its static sheet. */
    val buildingSprites: List<Int> by lazy {
        (houses.map { it.sprite } + industries.flatMap { it.parts.values }).filter { it >= 0 }.distinct().sorted()
    }

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
            class Spec(val key: String, var def: VehicleDef, var sprite: SpriteRef?, var offWire: SpriteRef?, var enabled: Boolean = true)
            val specs = ArrayList<Spec>()
            val byKey = HashMap<String, Spec>()
            class HouseSpec(val key: String, var def: HouseDef, var sprite: SpriteRef?, var enabled: Boolean = true)
            class IndustrySpec(val key: String, var def: IndustryDef, val parts: HashMap<String, SpriteRef>, var enabled: Boolean = true)
            val houseSpecs = ArrayList<HouseSpec>()
            val houseByKey = HashMap<String, HouseSpec>()
            val industrySpecs = ArrayList<IndustrySpec>()
            val industryByKey = HashMap<String, IndustrySpec>()
            val seen = HashSet<String>()
            fun qualify(pack: Pack, ref: String) = if (':' in ref) ref else "${pack.id}:$ref"
            for (pack in packs) {
                if (!seen.add(pack.id)) { problems += "${pack.id} is on twice"; continue }
                for (need in pack.needs) if (need !in seen) problems += "${pack.id} needs $need before it"
                for (v in pack.vehicles) {
                    val sprite = v.sprite?.let { SpriteRef(pack.id, it) }
                    val offWire = v.spriteOffWire?.let { SpriteRef(pack.id, it) }
                    if (v.replaces == null) {
                        val s = Spec(qualify(pack, v.id), v, sprite, offWire)
                        specs += s
                        byKey[s.key] = s
                        continue
                    }
                    val old = byKey[qualify(pack, v.replaces)] ?: run { problems += "${pack.id}:${v.id} replaces ${v.replaces}, which isn't there"; continue }
                    old.def = merge(old.def, v, pack)
                    // New art without a drawing off the wires uses the new drawing for both, not the old one's.
                    if (sprite != null) {
                        old.sprite = sprite
                        old.offWire = offWire
                    } else if (offWire != null) old.offWire = offWire
                }
                for (h in pack.houses) {
                    val sprite = h.sprite?.let { SpriteRef(pack.id, it) }
                    if (h.replaces == null) {
                        val k = HouseSpec(qualify(pack, h.id), h, sprite)
                        houseSpecs += k
                        houseByKey[k.key] = k
                        continue
                    }
                    val old = houseByKey[qualify(pack, h.replaces)] ?: run { problems += "${pack.id}:${h.id} replaces ${h.replaces}, which isn't there"; continue }
                    old.def = mergeHouse(old.def, h)
                    if (sprite != null) old.sprite = sprite
                }
                for (d in pack.industries) {
                    val parts = d.parts.orEmpty().mapValues { SpriteRef(pack.id, it.value) }
                    if (d.replaces == null) {
                        val k = IndustrySpec(qualify(pack, d.id), d, HashMap(parts))
                        industrySpecs += k
                        industryByKey[k.key] = k
                        continue
                    }
                    val old = industryByKey[qualify(pack, d.replaces)] ?: run { problems += "${pack.id}:${d.id} replaces ${d.replaces}, which isn't there"; continue }
                    old.def = mergeIndustry(old.def, d)
                    old.parts.putAll(parts)
                }
                for (d in pack.disables) {
                    val k = qualify(pack, d)
                    val v = byKey[k]
                    val h = houseByKey[k]
                    val ind = industryByKey[k]
                    if (v == null && h == null && ind == null) problems += "${pack.id} turns off $d, which isn't there"
                    v?.enabled = false
                    h?.enabled = false
                    ind?.enabled = false
                }
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
                val offWire = s.offWire ?: sprite
                if (s.offWire != null && d.traction != "dual") problems += "${s.key}: only dual-mode engines have a drawing off the wires"
                if (d.plow == "oneway" && d.kind != VehicleKind.ROAD) problems += "${s.key}: only road vehicles have one-way plows"
                if (d.plow != null && d.kind != VehicleKind.WAGON && d.kind != VehicleKind.ROAD) problems += "${s.key}: only wagons and road vehicles are plows"
                if ((d.dieselPower != null || d.dieselSpeed != null) && d.traction != "dual") problems += "${s.key}: only dual-mode engines have a diesel power and speed"
                if (d.traction == "dual" && d.kind != VehicleKind.ENGINE) problems += "${s.key}: only engines are dual-mode"
                Engine(
                    id = i, key = s.key, kind = d.kind!!, names = d.names!!, plurals = d.plurals ?: emptyMap(), types = d.types ?: emptyMap(),
                    from = d.from!!, until = d.until!!, speed = d.speed ?: 0, power = d.power ?: 0, weight = d.weight!!,
                    cost = d.cost!!, running = d.running!!, length = d.length!!, carries = d.carries ?: emptyList(), capacity = d.capacity ?: 0,
                    sprite = spriteIds.getOrPut(sprite) { sprites += sprite; sprites.size - 1 },
                    offWireSprite = spriteIds.getOrPut(offWire) { sprites += offWire; sprites.size - 1 }, tender = tender, enabled = s.enabled,
                    tractive = d.tractive ?: (d.weight!! * 245 / 100), traction = Traction.NAMES.indexOf(d.traction ?: "steam").coerceAtLeast(0),
                    dieselPower = d.dieselPower ?: d.power ?: 0, dieselSpeed = d.dieselSpeed ?: d.speed ?: 0,
                    life = d.life ?: 35, reliability = d.reliability ?: 85,
                    plow = d.plow?.let { Plows.NAMES.indexOf(it) + 1 } ?: Plows.NONE,
                    tram = d.tram ?: false,
                    icebreaker = d.icebreaker ?: 0, hovercraft = d.hovercraft ?: false,
                    range = d.range ?: 0, runway = d.runway?.let { Runways.NAMES.indexOf(it) } ?: Runways.REGIONAL, floats = d.floats ?: false,
                )
            }
            fun spriteId(ref: SpriteRef) = spriteIds.getOrPut(ref) { sprites += ref; sprites.size - 1 }
            val houses = houseSpecs.mapIndexed { i, s ->
                val h = s.def
                House(
                    i, s.key, h.names!!, h.from!!, h.until!!, h.residents!!, h.jobs!!, h.zones!!, h.minPopulation ?: 0, h.weight ?: 1, h.monument ?: false,
                    sprite = s.sprite?.let { spriteId(it) } ?: -1, enabled = s.enabled,
                )
            }
            val baseParts = packs.firstOrNull { it.id == Packs.BASE_ID }?.industries.orEmpty().flatMap { it.layout.orEmpty().flatten() }.toSet()
            val industries = industrySpecs.mapIndexed { i, s ->
                val d = s.def
                val layout = d.layout!!
                val produces = d.produces.orEmpty()
                for (part in layout.flatten().toSet()) if (part !in s.parts && part !in baseParts) problems += "${s.key}: part \"$part\" has no drawing in its pack or the base pack"
                for (cg in d.production.orEmpty().keys) if (cg !in produces) problems += "${s.key}: production: \"$cg\" should be in \"produces\" too"
                IndustryKind(
                    i, s.key, d.names!!, d.from!!, d.until!!, d.regions.orEmpty(), d.ground.orEmpty(), layout,
                    IntArray(Cargo.COUNT) { c -> produces[Cargo.IDS[c]] ?: 0 },
                    d.accepts.orEmpty().fold(0) { m, cg -> Cargo.of(cg).let { if (it >= 0) m or (1 shl it) else m } },
                    produces.keys.fold(0) { m, cg -> Cargo.of(cg).let { if (it >= 0) m or (1 shl it) else m } },
                    (d.seasons ?: List(12) { 100 }).toIntArray(), d.water ?: false,
                    d.weather.orEmpty().fold(0) { m, r -> IndustryKind.WEATHER.indexOf(r).let { if (it >= 0) m or (1 shl it) else m } },
                    enabled = s.enabled,
                    parts = s.parts.entries.sortedBy { it.key }.associate { it.key to spriteId(it.value) },
                    production = Array(Cargo.COUNT) { c -> d.production?.get(Cargo.IDS[c]) },
                    open = d.open, closes = d.closes,
                )
            }
            if (problems.isNotEmpty()) throw BadPack(problems)
            return Roster(packs.map { it.ref }, engines, sprites, houses, industries)
        }

        /** [old] with whatever [h] gives in its place. */
        private fun mergeHouse(old: HouseDef, h: HouseDef) = HouseDef(
            old.id, h.names ?: old.names, h.from ?: old.from, h.until ?: old.until, h.residents ?: old.residents, h.jobs ?: old.jobs,
            h.zones ?: old.zones, h.minPopulation ?: old.minPopulation, h.weight ?: old.weight, h.monument ?: old.monument,
            null, h.sprite ?: old.sprite,
        )

        /** [old] with whatever [d] gives in its place. Its parts are merged where the drawings are found. */
        private fun mergeIndustry(old: IndustryDef, d: IndustryDef) = IndustryDef(
            old.id, d.names ?: old.names, d.from ?: old.from, d.until ?: old.until, d.regions ?: old.regions, d.ground ?: old.ground,
            d.layout ?: old.layout, d.produces ?: old.produces, d.accepts ?: old.accepts, d.seasons ?: old.seasons, d.water ?: old.water,
            d.weather ?: old.weather, null, null, d.production ?: old.production, d.open ?: old.open, d.closes ?: old.closes,
        )

        /** [old] with whatever [v] gives in its place. */
        private fun merge(old: VehicleDef, v: VehicleDef, pack: Pack) = VehicleDef(
            id = old.id, replaces = null, kind = old.kind,
            names = v.names ?: old.names, plurals = v.plurals ?: old.plurals, types = v.types ?: old.types,
            from = v.from ?: old.from, until = v.until ?: old.until, speed = v.speed ?: old.speed, power = v.power ?: old.power,
            weight = v.weight ?: old.weight, cost = v.cost ?: old.cost, running = v.running ?: old.running, length = v.length ?: old.length,
            carries = v.carries ?: old.carries, capacity = v.capacity ?: old.capacity,
            tender = v.tender?.let { if (':' in it) it else "${pack.id}:$it" } ?: old.tender,
            sprite = v.sprite ?: old.sprite, spriteOffWire = if (v.sprite != null) v.spriteOffWire else v.spriteOffWire ?: old.spriteOffWire,
            tractive = v.tractive ?: old.tractive, traction = v.traction ?: old.traction,
            dieselPower = v.dieselPower ?: old.dieselPower, dieselSpeed = v.dieselSpeed ?: old.dieselSpeed,
            life = v.life ?: old.life, reliability = v.reliability ?: old.reliability,
            plow = v.plow ?: old.plow,
            tram = v.tram ?: old.tram,
            icebreaker = v.icebreaker ?: old.icebreaker, hovercraft = v.hovercraft ?: old.hovercraft,
            range = v.range ?: old.range, runway = v.runway ?: old.runway, floats = v.floats ?: old.floats,
        )
    }
}
