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
    /** What a wagon carries, and how much. */
    val carries: String,
    val capacity: Int,
    /** Its drawing, a place in [Roster.sprites]. */
    val sprite: Int,
    /** The tender it brings, an engine id, or -1. */
    val tender: Int,
    /** Turned off by a pack: kept so ids stay put, but not for sale. */
    val enabled: Boolean,
) {
    val pulls get() = kind == VehicleKind.ENGINE
    val isTender get() = kind == VehicleKind.TENDER

    fun name(language: String) = names[language] ?: names["en"] ?: key
    fun plural(language: String) = plurals[language] ?: if (language == "en" || language !in names) plurals["en"] ?: (name("en") + "s") else name(language)
    fun type(language: String) = types[language] ?: types["en"] ?: ""
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

/** A vehicle drawing: the sheet [name] in pack [pack]. */
data class SpriteRef(val pack: String, val name: String)

/** Everything that can run on the track in one game, from its packs in order, base first. */
class Roster(val packs: List<PackRef>, val engines: List<Engine>, val sprites: List<SpriteRef>, val houses: List<House> = emptyList()) {
    operator fun get(id: Int) = engines[id]
    fun getOrNull(id: Int) = engines.getOrNull(id)

    /** What can be bought in [year]: engines if [pulls], else wagons. */
    fun available(year: Int, pulls: Boolean) = engines.filter { it.enabled && it.pulls == pulls && !it.isTender && year >= it.from && year < it.until }

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
                    cost = d.cost!!, running = d.running!!, length = d.length!!, carries = d.carries ?: "", capacity = d.capacity ?: 0,
                    sprite = spriteIds.getOrPut(sprite) { sprites += sprite; sprites.size - 1 }, tender = tender, enabled = s.enabled,
                )
            }
            val houses = packs.flatMap { p -> p.houses.map { p.id to it } }.mapIndexed { i, (pack, h) ->
                House(i, "$pack:${h.id}", h.names, h.from, h.until, h.residents, h.jobs, h.zones, h.minPopulation, h.weight)
            }
            if (problems.isNotEmpty()) throw BadPack(problems)
            return Roster(packs.map { it.ref }, engines, sprites, houses)
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
        )
    }
}
