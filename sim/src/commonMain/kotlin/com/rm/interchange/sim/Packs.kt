package com.rm.interchange.sim

/*
 * Content packs: vehicles, town buildings and industries as data, so anyone
 * can add to the game. packs/SPEC.md is the standard; this reads it.
 *
 * A pack is a zip of manifest.json, vehicles.json, houses.json,
 * industries.json, cargo.json and sprites/<name>.png. The game's own are the base pack,
 * in the same format.
 */

/** A pack as a save or a server names it: which one, which version, and a hash of its data so both sides have the same. */
data class PackRef(val id: String, val version: Int, val hash: Long)

/** What's wrong with a pack, every problem found, one to a line. */
class BadPack(val problems: List<String>) : Exception(problems.joinToString("\n"))

enum class VehicleKind { ENGINE, WAGON, TENDER, ROAD, SHIP, AIRCRAFT }

/** A vehicle as a pack gives it. Only [id] is sure to be there: one that [replaces] another gives just what it changes. */
class VehicleDef(
    val id: String,
    val replaces: String?,
    val kind: VehicleKind?,
    val names: Map<String, String>?,
    val plurals: Map<String, String>?,
    val types: Map<String, String>?,
    val from: Int?,
    val until: Int?,
    val speed: Int?,
    val power: Int?,
    val weight: Int?,
    val cost: Long?,
    val running: Long?,
    val length: Int?,
    /** What it can be set to carry, by cargo id; the first is what it carries unless set otherwise. */
    val carries: List<String>?,
    val capacity: Int?,
    val tender: String?,
    val sprite: String?,
    /** A dual-mode engine's drawing off the wires, its pantograph down; [sprite] if not given. */
    val spriteOffWire: String? = null,
    /** Starting tractive effort in kN, for engines. */
    val tractive: Int? = null,
    /** steam, diesel, electric or dual, for engines. */
    val traction: String? = null,
    /** A dual-mode engine's power in hp and top speed in km/h off the wires. */
    val dieselPower: Int? = null,
    val dieselSpeed: Int? = null,
    /** Years it runs well before it wears out, and how reliable it is new, in percent. */
    val life: Int? = null,
    val reliability: Int? = null,
    /** wedge, rotary or oneway (road vehicles only), for a snow plow. */
    val plow: String? = null,
    /** A streetcar: a road vehicle that runs only on tram rails. */
    val tram: Boolean? = null,
    /** The ice a ship breaks through, in cm. */
    val icebreaker: Int? = null,
    /** A hovercraft: a ship that rides over ice. */
    val hovercraft: Boolean? = null,
    /** How far an aircraft flies without stopping, in km. */
    val range: Int? = null,
    /** The least airport an aircraft lands at, one of [Runways.NAMES]. */
    val runway: String? = null,
    /** A bush plane, that goes on floats or skis as well as wheels. */
    val floats: Boolean? = null,
    /** A road vehicle with no crew, with future transport on. */
    val autonomous: Boolean? = null,
)

/**
 * A town building as a pack gives it. [zones] are bits of [Zones], from the outskirts in to the centre. Only [id] is
 * sure to be there: one that [replaces] another gives just what it changes.
 */
class HouseDef(
    val id: String,
    val names: Map<String, String>?,
    val from: Int?,
    val until: Int?,
    val residents: Int?,
    val jobs: Int?,
    val zones: Int?,
    val minPopulation: Int?,
    val weight: Int?,
    val monument: Boolean? = null,
    val replaces: String? = null,
    /** Its sprite sheet in its pack; null for the base pack's, drawn by the game. */
    val sprite: String? = null,
)

/**
 * An industry as a pack gives it: its tile pieces as rows of part names, the
 * regions it's found in with how often, the ground it needs, what it makes a
 * month by cargo id, and what it takes. One that takes something makes its
 * cargo from what it's brought. Only [id] is sure to be there: one that
 * [replaces] another gives just what it changes.
 */
class IndustryDef(
    val id: String,
    val names: Map<String, String>?,
    val from: Int?,
    val until: Int?,
    val regions: Map<String, Int>?,
    val ground: List<String>?,
    val layout: List<List<String>>?,
    val produces: Map<String, Int>?,
    val accepts: List<String>?,
    /** How much it makes each month, January first, in percent of its usual. */
    val seasons: List<Int>?,
    /** It stands on the shore, the first row of its layout facing the water. */
    val water: Boolean?,
    /** How the weather changes it, by [IndustryKind.WEATHER] names. */
    val weather: List<String>? = null,
    val replaces: String? = null,
    /** Sprite sheets in its pack for layout parts, by part name; parts not here are the base pack's. */
    val parts: Map<String, String>? = null,
    /** Rules for a cargo's monthly amount in place of the usual, by cargo id. */
    val production: Map<String, Rule>? = null,
    /** A rule that's 0 where and when it can't open, and one that closes it at the month when it isn't 0. */
    val open: Rule? = null,
    val closes: Rule? = null,
    /** What it makes in whole batches from its stores, in place of a tonne for a tonne. */
    val recipe: RecipeDef? = null,
)

/** A batch: [inputs] taken from the stores, by cargo id, make [makes]. */
class RecipeDef(val inputs: Map<String, Int>, val makes: Map<String, Int>)

/**
 * A cargo as a pack gives it, its numbers as in [Cargo] and [colour] as
 * 0xrrggbb. Only [id] is sure to be there: one that [replaces] another gives
 * just what it changes.
 */
class CargoDef(
    val id: String,
    val replaces: String?,
    val names: Map<String, String>?,
    val units: Map<String, String>?,
    val rate: Int?,
    val grace: Int?,
    val decay: Int?,
    val pace: Int?,
    val fast: Int?,
    val kg: Int?,
    val colour: Int?,
    val towns: Boolean?,
    /** False for cargo that spoils in an industry's store. */
    val keeps: Boolean? = null,
    /** Its [Handling] class, or null for general freight. */
    val handling: Int? = null,
)

/** How far into a town a building stands, as bits, from the outskirts in to the centre. */
object Zones {
    const val OUTSKIRTS = 1
    const val SUBURBS = 2
    const val INNER = 4
    const val CENTRE = 8
    val NAMES = listOf("outskirts", "suburbs", "inner", "centre")
}

class Pack(
    val id: String,
    val version: Int,
    val names: Map<String, String>,
    val descriptions: Map<String, String>,
    val author: String,
    val licence: String,
    /** Packs that have to come before this one. */
    val needs: List<String>,
    /** Vehicles, town buildings and industries of other packs this one turns off, as pack:id. */
    val disables: List<String>,
    val vehicles: List<VehicleDef>,
    val houses: List<HouseDef>,
    val industries: List<IndustryDef>,
    /** The sprite sheets by name, as PNG. */
    val sprites: Map<String, ByteArray>,
    val hash: Long,
    val cargo: List<CargoDef> = emptyList(),
) {
    val ref get() = PackRef(id, version, hash)
    fun name(language: String) = names[language] ?: names["en"] ?: id
    fun description(language: String) = descriptions[language] ?: descriptions["en"] ?: ""
}

object Packs {
    /** The newest pack format this game reads. */
    const val FORMAT = 3
    const val BASE_ID = "base"

    /** Sprite sheets: four by four cells, each [CELL] pixels square at the sharpest level. */
    const val CELL = 256
    const val GRID = 4
    /** Where a vehicle's middle stands on the track, in its cell. */
    const val STAND_X = 128
    const val STAND_Y = 160

    // Limits, so a broken or hostile zip can't fill the phone.
    const val MAX_FILE = 16 shl 20
    const val MAX_TOTAL = 64 shl 20
    /** The most of a cargo a recipe takes or makes in a batch. */
    const val RECIPE_MOST = 100

    /** The most tiles an industry's layout has each way. */
    const val MAX_LAYOUT = 5

    private val ID = Regex("[a-z0-9][a-z0-9_-]{0,31}")
    private val LANGUAGES = setOf("en", "fr")
    private val COLOUR = Regex("#[0-9a-fA-F]{6}")

    /** The game's own vehicles. */
    val BASE: Pack by lazy {
        read(mapOf("manifest.json" to BaseData.read("manifest.json"), "vehicles.json" to BaseData.read("vehicles.json"), "houses.json" to BaseData.read("houses.json"), "industries.json" to BaseData.read("industries.json")), spritesElsewhere = true)
    }

    /** Whether [id] is one a pack may have. */
    fun validId(id: String) = ID.matches(id)

    /** A pack from a zip. */
    fun unzip(zip: ByteArray): Pack = read(Zip.read(zip, MAX_FILE, MAX_TOTAL))

    /**
     * A pack from its files by path. A zip made of a folder holding the pack
     * is taken as that folder. [base] lets it be the base pack, and
     * [spritesElsewhere] is for the base pack in the game, whose sprites ship
     * ready packed.
     */
    fun read(files: Map<String, ByteArray>, spritesElsewhere: Boolean = false, base: Boolean = spritesElsewhere): Pack {
        val all = files.filterKeys { !it.endsWith("/") }
        val top = all.keys.map { it.substringBefore('/', "") }.distinct()
        val inner = if ("manifest.json" !in all && top.size == 1 && top[0].isNotEmpty()) all.mapKeys { it.key.substringAfter('/') } else all
        val problems = ArrayList<String>()
        val manifestBytes = inner["manifest.json"] ?: throw BadPack(listOf("manifest.json is missing"))
        val vehiclesBytes = inner["vehicles.json"] ?: byteArrayOf()
        val housesBytes = inner["houses.json"] ?: byteArrayOf()
        val industriesBytes = inner["industries.json"] ?: byteArrayOf()
        val cargoBytes = inner["cargo.json"] ?: byteArrayOf()

        fun json(name: String, bytes: ByteArray): Any? = try {
            Json.parse(bytes.decodeToString())
        } catch (e: BadJson) {
            problems += "$name, ${e.message}"
            null
        }

        val m = Fields("manifest.json", json("manifest.json", manifestBytes) as? Map<*, *> ?: run {
            if (problems.isEmpty()) problems += "manifest.json should be an object { }"
            throw BadPack(problems)
        }, problems)
        val format = m.int("format", 1, 1000, need = true) ?: 0
        if (format > FORMAT) throw BadPack(listOf("made for a newer version of the game (pack format $format, this game reads up to $FORMAT)"))
        val id = m.string("id", need = true) ?: ""
        if (id.isNotEmpty() && !ID.matches(id)) problems += "manifest.json: id \"$id\" should be lower case letters, digits, - and _, up to 32"
        if (id == BASE_ID && !base) problems += "manifest.json: the id \"base\" is the game's own"
        val version = m.int("version", 1, 1_000_000, need = true) ?: 0
        val names = m.words("name", need = true) ?: emptyMap()
        val descriptions = m.words("description") ?: emptyMap()
        val author = m.string("author") ?: ""
        val licence = m.string("licence") ?: ""
        val needs = m.strings("needs") ?: emptyList()
        val disables = m.strings("disables") ?: emptyList()
        m.done()

        val cargo = ArrayList<CargoDef>()
        if (format < 3 && cargoBytes.isNotEmpty()) problems += "manifest.json: a pack with cargo.json needs \"format\": 3"
        if (cargoBytes.isNotEmpty()) {
            val root = json("cargo.json", cargoBytes)
            val list = (root as? Map<*, *>)?.get("cargo") as? List<*>
            if (root != null && list == null) problems += "cargo.json should be { \"cargo\": [ ... ] }"
            val seen = HashSet<String>()
            list?.forEachIndexed { n, item ->
                val map = item as? Map<*, *> ?: run { problems += "cargo.json, cargo ${n + 1} should be an object { }"; return@forEachIndexed }
                val f = Fields("cargo.json, cargo ${n + 1}", map, problems)
                val cid = f.string("id", need = true) ?: ""
                f.where = "cargo.json, \"$cid\""
                if (cid.isNotEmpty() && !ID.matches(cid)) problems += "${f.where}: id should be lower case letters, digits, - and _"
                if (!seen.add(cid)) problems += "${f.where}: id is used twice"
                val replaces = f.string("replaces")
                val fresh = replaces == null
                val colour = f.string("colour", need = fresh)?.let { c ->
                    if (COLOUR.matches(c)) c.substring(1).toInt(16) else { problems += "${f.where}: \"colour\" should be \"#rrggbb\""; null }
                }
                cargo += CargoDef(
                    cid, replaces, f.words("name", need = fresh), f.words("unit", need = fresh),
                    f.int("rate", 1, 1000, need = fresh), f.int("grace", 0, 365, need = fresh), f.int("decay", 0, 100, need = fresh),
                    f.int("pace", 1, 10, need = fresh), f.int("fast", 0, 400), f.int("kg", 1, 100_000, need = fresh), colour, f.bool("towns"),
                    f.bool("keeps"),
                    f.string("handling")?.let { h ->
                        Handling.KEYS.indexOf(h).takeIf { it >= 0 } ?: run { problems += "${f.where}: \"handling\" should be one of ${Handling.KEYS.joinToString()}"; null }
                    },
                )
                f.done()
            }
        }
        // Cargo this pack can name: the base pack's and its own, and if it needs other packs, theirs too, which only the roster knows.
        val ownCargo = cargo.filter { it.replaces == null }.map { it.id }.toSet()
        val othersCargo = needs.any { it != BASE_ID }
        fun knownCargo(cg: String) = othersCargo || Cargo.of(cg) >= 0 || cg in ownCargo

        val vehicles = ArrayList<VehicleDef>()
        if (vehiclesBytes.isNotEmpty()) {
            val root = json("vehicles.json", vehiclesBytes)
            val list = (root as? Map<*, *>)?.get("vehicles") as? List<*>
            if (root != null && list == null) problems += "vehicles.json should be { \"vehicles\": [ ... ] }"
            val seen = HashSet<String>()
            list?.forEachIndexed { n, v ->
                val where = "vehicles.json, vehicle ${n + 1}"
                val map = v as? Map<*, *> ?: run { problems += "$where should be an object { }"; return@forEachIndexed }
                val f = Fields(where, map, problems)
                val vid = f.string("id", need = true) ?: ""
                val at = if (vid.isEmpty()) where else "vehicles.json, \"$vid\""
                f.where = at
                if (vid.isNotEmpty() && !ID.matches(vid)) problems += "$at: id should be lower case letters, digits, - and _"
                if (!seen.add(vid)) problems += "$at: id is used twice"
                val replaces = f.string("replaces")
                val fresh = replaces == null
                val kind = f.string("kind", need = fresh)?.let { k ->
                    VehicleKind.entries.firstOrNull { it.name.lowercase() == k } ?: run { problems += "$at: kind should be engine, wagon, tender, road, ship or aircraft"; null }
                }
                val def = VehicleDef(
                    id = vid,
                    replaces = replaces,
                    kind = kind,
                    names = f.words("name", need = fresh),
                    plurals = f.words("plural"),
                    types = f.words("type"),
                    from = f.int("from", 1700, 2300, need = fresh),
                    until = f.int("until", 1700, 2300, need = fresh),
                    speed = f.int("speed", 1, 2000, need = fresh && (kind == VehicleKind.ENGINE || kind == VehicleKind.ROAD || kind == VehicleKind.SHIP || kind == VehicleKind.AIRCRAFT)),
                    power = f.int("power", 1, 1_000_000, need = fresh && (kind == VehicleKind.ENGINE || kind == VehicleKind.ROAD || kind == VehicleKind.SHIP || kind == VehicleKind.AIRCRAFT)),
                    weight = f.int("weight", 1, 100_000, need = fresh),
                    cost = f.int("cost", 0, 1_000_000_000, need = fresh)?.toLong(),
                    running = f.int("running", 0, 1_000_000_000, need = fresh)?.toLong(),
                    length = f.int("length", 1, 16, need = fresh),
                    carries = f.stringOrList("carries"),
                    capacity = f.int("capacity", 0, 100_000),
                    tender = f.string("tender"),
                    sprite = f.string("sprite") ?: if (fresh) vid else null,
                    spriteOffWire = f.string("sprite_off_wire"),
                    tractive = f.int("tractive", 1, 10_000),
                    traction = f.string("traction"),
                    dieselPower = f.int("diesel_power", 1, 1_000_000),
                    dieselSpeed = f.int("diesel_speed", 1, 2000),
                    life = f.int("life", 1, 200),
                    reliability = f.int("reliability", 1, 100),
                    plow = f.string("plow"),
                    tram = f.bool("tram"),
                    icebreaker = f.int("icebreaker", 1, 500),
                    hovercraft = f.bool("hovercraft"),
                    range = f.int("range", 1, 30_000),
                    runway = f.string("runway"),
                    floats = f.bool("floats"),
                    autonomous = f.bool("autonomous"),
                )
                f.done()
                if (!fresh && kind != null) problems += "$at: a vehicle that replaces another keeps its kind"
                if (def.from != null && def.until != null && def.from >= def.until) problems += "$at: from should be before until"
                val moves = kind == VehicleKind.ENGINE || kind == VehicleKind.ROAD || kind == VehicleKind.SHIP || kind == VehicleKind.AIRCRAFT
                if (kind != null && !moves && (def.speed != null || def.power != null)) problems += "$at: only engines, road vehicles, ships and aircraft have speed and power"
                val stored = def.traction == "battery" || def.traction == "hydrogen"
                val maglevCar = (def.traction == "maglev" || def.traction == "hyperloop") && kind == VehicleKind.WAGON
                if (kind != null && kind != VehicleKind.ENGINE && kind != VehicleKind.ROAD && (def.tractive != null || def.traction != null && !(stored && moves) && !maglevCar)) problems += "$at: only engines and road vehicles have tractive effort and traction, ships and aircraft only battery or hydrogen, and wagons only maglev or hyperloop"
                if (def.traction == "maglev" && kind != null && kind != VehicleKind.ENGINE && kind != VehicleKind.WAGON) problems += "$at: only engines and wagons are maglev"
                if (def.traction == "hyperloop" && kind != null && kind != VehicleKind.ENGINE && kind != VehicleKind.WAGON) problems += "$at: only engines and wagons are hyperloop pods"
                if (def.plow != null && def.plow !in Plows.NAMES) problems += "$at: plow should be wedge, rotary or oneway"
                if (def.plow == "oneway" && kind != null && kind != VehicleKind.ROAD) problems += "$at: only road vehicles have one-way plows"
                if (def.plow != null && kind != null && kind != VehicleKind.WAGON && kind != VehicleKind.ROAD) problems += "$at: only wagons and road vehicles are plows"
                if (def.tram != null && kind != null && kind != VehicleKind.ROAD) problems += "$at: only road vehicles are streetcars"
                if (def.autonomous != null && kind != null && kind != VehicleKind.ROAD) problems += "$at: only road vehicles are autonomous"
                if ((def.icebreaker != null || def.hovercraft != null) && kind != null && kind != VehicleKind.SHIP) problems += "$at: only ships break ice or hover"
                if ((def.runway != null || def.floats != null) && kind != null && kind != VehicleKind.AIRCRAFT) problems += "$at: only aircraft have a runway and floats"
                if (def.range != null && kind != null && kind != VehicleKind.AIRCRAFT && !stored) problems += "$at: only aircraft and battery or hydrogen vehicles have a range"
                if (def.runway != null && def.runway !in Runways.NAMES) problems += "$at: runway should be ${Runways.NAMES.joinToString(", ")}"
                if (kind == VehicleKind.AIRCRAFT && fresh && def.runway == null) problems += "$at: an aircraft needs a runway"
                if (def.traction != null && def.traction !in Traction.NAMES) problems += "$at: traction should be ${Traction.NAMES.joinToString(", ")}"
                if (def.tender != null && kind != null && kind != VehicleKind.ENGINE) problems += "$at: only engines have a tender"
                if (def.capacity != null && def.capacity > 0 && def.carries == null) problems += "$at: capacity without carries"
                for (cg in def.carries.orEmpty()) if (!knownCargo(cg)) problems += "$at: unknown cargo \"$cg\" (${(Cargo.IDS + ownCargo).joinToString()})"
                if (!spritesElsewhere && def.sprite != null && "sprites/${def.sprite}.png" !in inner) problems += "$at: sprites/${def.sprite}.png is missing"
                if (!spritesElsewhere && def.spriteOffWire != null && "sprites/${def.spriteOffWire}.png" !in inner) problems += "$at: sprites/${def.spriteOffWire}.png is missing"
                if (def.spriteOffWire != null && def.traction != null && def.traction != "dual") problems += "$at: only dual-mode engines have a drawing off the wires"
                vehicles += def
            }
        }
        // Town buildings, industries and rules came in format 2; the base pack has always had them.
        if (id != BASE_ID && format < 2 && (housesBytes.isNotEmpty() || industriesBytes.isNotEmpty())) {
            problems += "manifest.json: a pack with houses.json or industries.json needs \"format\": 2"
        }
        fun sprite(at: String, name: String?) {
            if (!spritesElsewhere && name != null && "sprites/$name.png" !in inner) problems += "$at: sprites/$name.png is missing"
        }
        val houses = ArrayList<HouseDef>()
        if (housesBytes.isNotEmpty()) {
            val root = json("houses.json", housesBytes)
            val list = (root as? Map<*, *>)?.get("houses") as? List<*>
            if (root != null && list == null) problems += "houses.json should be { \"houses\": [ ... ] }"
            val seen = HashSet<String>()
            list?.forEachIndexed { n, h ->
                val map = h as? Map<*, *> ?: run { problems += "houses.json, building ${n + 1} should be an object { }"; return@forEachIndexed }
                val f = Fields("houses.json, building ${n + 1}", map, problems)
                val hid = f.string("id", need = true) ?: ""
                f.where = "houses.json, \"$hid\""
                if (hid.isNotEmpty() && !ID.matches(hid)) problems += "${f.where}: id should be lower case letters, digits, - and _"
                if (!seen.add(hid)) problems += "${f.where}: id is used twice"
                val replaces = f.string("replaces")
                val fresh = replaces == null
                val zoneNames = f.strings("zones")
                var zones = 0
                for (z in zoneNames ?: emptyList()) {
                    val k = Zones.NAMES.indexOf(z)
                    if (k < 0) problems += "${f.where}: zone \"$z\" should be outskirts, suburbs, inner or centre" else zones = zones or (1 shl k)
                }
                val monument = f.bool("monument")
                if (fresh && zones == 0 && monument != true) problems += "${f.where}: \"zones\" needs at least one zone"
                val def = HouseDef(
                    hid, f.words("name", need = fresh),
                    f.int("from", 1700, 2300, need = fresh), f.int("until", 1700, 2300, need = fresh),
                    f.int("residents", 0, 10_000, need = fresh), f.int("jobs", 0, 10_000, need = fresh),
                    if (zoneNames == null && !fresh) null else zones, f.int("min_population", 0, 10_000_000), f.int("weight", 1, 100), monument,
                    replaces, f.string("sprite") ?: if (fresh && id != BASE_ID) hid else null,
                )
                f.done()
                if (def.from != null && def.until != null && def.from >= def.until) problems += "${f.where}: from should be before until"
                sprite(f.where, def.sprite)
                houses += def
            }
        }
        val industries = ArrayList<IndustryDef>()
        if (industriesBytes.isNotEmpty()) {
            val root = json("industries.json", industriesBytes)
            val list = (root as? Map<*, *>)?.get("industries") as? List<*>
            if (root != null && list == null) problems += "industries.json should be { \"industries\": [ ... ] }"
            val seen = HashSet<String>()
            // The parts the game draws itself, which any pack's layout may use.
            val baseParts = if (id == BASE_ID) emptySet() else BASE.industries.flatMap { d -> d.layout.orEmpty().flatten() }.toSet()
            list?.forEachIndexed { n, item ->
                val map = item as? Map<*, *> ?: run { problems += "industries.json, industry ${n + 1} should be an object { }"; return@forEachIndexed }
                val f = Fields("industries.json, industry ${n + 1}", map, problems)
                val iid = f.string("id", need = true) ?: ""
                f.where = "industries.json, \"$iid\""
                val at = f.where
                if (iid.isNotEmpty() && !ID.matches(iid)) problems += "$at: id should be lower case letters, digits, - and _"
                if (!seen.add(iid)) problems += "$at: id is used twice"
                val replaces = f.string("replaces")
                val fresh = replaces == null
                val regions = f.intMap("regions", 0, 100)
                for (r in regions?.keys.orEmpty()) if (Region.entries.none { it.name.lowercase() == r }) problems += "$at: unknown region \"$r\""
                val ground = f.strings("ground")
                for (g in ground.orEmpty()) if (Ground.NAMES.none { it.lowercase() == g }) problems += "$at: ground \"$g\" should be ${Ground.NAMES.joinToString { it.lowercase() }}"
                val layout = f.strings("layout", need = fresh)?.map { row -> row.split(' ').filter { it.isNotEmpty() } }
                if (layout != null) {
                    if (layout.isEmpty() || layout[0].isEmpty() || layout.any { it.size != layout[0].size }) problems += "$at: \"layout\" needs rows of the same length"
                    else if (layout.size > MAX_LAYOUT || layout[0].size > MAX_LAYOUT) problems += "$at: \"layout\" can be at most $MAX_LAYOUT by $MAX_LAYOUT"
                }
                val produces = f.intMap("produces", 0, 100_000)
                val accepts = f.strings("accepts")
                for (cg in produces?.keys.orEmpty() + accepts.orEmpty()) if (!knownCargo(cg)) problems += "$at: unknown cargo \"$cg\""
                val parts = f.stringMap("parts")
                for ((part, sheet) in parts.orEmpty()) {
                    if (!ID.matches(part)) problems += "$at: part \"$part\" should be lower case letters, digits, - and _"
                    sprite(at, sheet)
                }
                if (fresh && id != BASE_ID) for (part in layout.orEmpty().flatten().toSet()) {
                    if (part !in parts.orEmpty() && part !in baseParts) problems += "$at: part \"$part\" isn't in \"parts\" or the base pack"
                }
                val production = f.stringMap("production")?.entries?.mapNotNull { (cg, src) ->
                    if (!knownCargo(cg)) { problems += "$at: production: unknown cargo \"$cg\""; return@mapNotNull null }
                    if (fresh && (produces?.get(cg) ?: 0) <= 0) problems += "$at: production: \"$cg\" should be in \"produces\" too, above 0"
                    rule(at, "production $cg", src, problems, ::knownCargo)?.let { cg to it }
                }?.toMap()
                val open = f.string("open")?.let { rule(at, "open", it, problems, ::knownCargo) }
                val closes = f.string("closes")?.let { rule(at, "closes", it, problems, ::knownCargo) }
                val weather = f.strings("weather")
                weather.orEmpty().filter { it !in IndustryKind.WEATHER }.forEach { problems += "$at: unknown weather rule \"$it\"" }
                val recipe = f.recipe()
                for (cg in recipe?.inputs?.keys.orEmpty() + recipe?.makes?.keys.orEmpty()) if (!knownCargo(cg)) problems += "$at: recipe: unknown cargo \"$cg\""
                if (fresh && recipe != null) {
                    for (cg in recipe.inputs.keys) if (cg !in accepts.orEmpty()) problems += "$at: recipe: \"$cg\" should be in \"accepts\" too"
                    for (cg in recipe.makes.keys) if (produces?.get(cg) != 0) problems += "$at: recipe: \"$cg\" should be in \"produces\" too, at 0"
                }
                val def = IndustryDef(
                    iid, f.words("name", need = fresh), f.int("from", 1700, 2300, need = fresh), f.int("until", 1700, 2300, need = fresh),
                    regions ?: if (fresh) emptyMap() else null, ground ?: if (fresh) emptyList() else null, layout,
                    produces ?: if (fresh) emptyMap() else null, accepts ?: if (fresh) emptyList() else null,
                    f.seasons(fresh), f.bool("water") ?: if (fresh) false else null, weather ?: if (fresh) emptyList() else null,
                    replaces, parts, production, open, closes, recipe,
                )
                f.done()
                if (def.from != null && def.until != null && def.from >= def.until) problems += "$at: from should be before until"
                industries += def
            }
        }
        if (problems.isNotEmpty()) throw BadPack(problems)
        val sprites = inner.filterKeys { it.startsWith("sprites/") && it.endsWith(".png") }.mapKeys { it.key.removePrefix("sprites/").removeSuffix(".png") }
        // cargo.json is hashed only when there is one, so packs without it keep their hash.
        val hash = if (cargoBytes.isEmpty()) hash(manifestBytes, vehiclesBytes, housesBytes, industriesBytes) else hash(manifestBytes, vehiclesBytes, housesBytes, industriesBytes, cargoBytes)
        return Pack(id, version, names, descriptions, author, licence, needs, disables, vehicles, houses, industries, sprites, hash, cargo)
    }

    /** [source] parsed as a rule, or null with its problem noted, at its column. */
    private fun rule(at: String, field: String, source: String, problems: MutableList<String>, known: (String) -> Boolean): Rule? = try {
        Rule.parse(source, known)
    } catch (e: BadRule) {
        problems += "$at: $field, column ${e.column}: ${e.what}"
        null
    }

    /** FNV-1a over the data files, so two packs match when their data does. Sprites are left out: they don't change the game. */
    fun hash(vararg parts: ByteArray): Long {
        var h = -0x340d631b7bdddcdbL
        for (p in parts) {
            for (b in p) h = (h xor (b.toLong() and 0xff)) * 0x100000001b3L
            h = (h xor 0xff) * 0x100000001b3L
        }
        return h
    }

    /** Reads typed fields out of a JSON object, noting each problem, and any field left over as unknown. */
    private class Fields(var where: String, val map: Map<*, *>, val problems: MutableList<String>) {
        /** An industry's months, twelve percentages, or all a hundred if it doesn't say and it's [fresh]. */
        fun seasons(fresh: Boolean = true): List<Int>? {
            val v = intList("seasons") ?: return if (fresh) List(12) { 100 } else null
            if (v.size != 12) { problems += "$where: \"seasons\" needs twelve months"; return List(12) { 100 } }
            if (v.any { it < 0 || it > 1000 }) problems += "$where: \"seasons\" should be from 0 to 1000 each"
            return v
        }

        /** { "name": whole number } with each from [lo] to [hi]. */
        fun intMap(k: String, lo: Int, hi: Int): Map<String, Int>? {
            val v = get(k, false) ?: return null
            val m = v as? Map<*, *>
            if (m == null || m.values.any { it !is Long }) { problems += "$where: \"$k\" should be { \"name\": whole number }"; return null }
            if (m.values.any { (it as Long) !in lo.toLong()..hi.toLong() }) problems += "$where: \"$k\" should be from $lo to $hi each"
            return m.entries.associate { it.key as String to (it.value as Long).coerceIn(lo.toLong(), hi.toLong()).toInt() }
        }

        /** { "inputs": { "cargo": n }, "makes": { "cargo": n } }, each from 1 to [RECIPE_MOST], neither empty. */
        fun recipe(): RecipeDef? {
            val v = get("recipe", false) ?: return null
            val m = v as? Map<*, *>
            fun part(k: String): Map<String, Int>? {
                val p = m?.get(k) as? Map<*, *>
                if (p == null || p.isEmpty() || p.values.any { it !is Long }) return null
                return p.entries.associate { it.key as String to (it.value as Long).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt() }
            }
            val inputs = part("inputs")
            val makes = part("makes")
            if (m == null || inputs == null || makes == null || m.keys.any { it != "inputs" && it != "makes" }) {
                problems += "$where: \"recipe\" should be { \"inputs\": { \"cargo\": n }, \"makes\": { \"cargo\": n } }"
                return null
            }
            if ((inputs.values + makes.values).any { it !in 1..RECIPE_MOST }) problems += "$where: \"recipe\" amounts should be from 1 to $RECIPE_MOST"
            return RecipeDef(inputs, makes)
        }

        /** { "name": "text" }. */
        fun stringMap(k: String): Map<String, String>? {
            val v = get(k, false) ?: return null
            val m = v as? Map<*, *>
            if (m == null || m.values.any { it !is String }) { problems += "$where: \"$k\" should be { \"name\": \"...\" }"; return null }
            return m.entries.associate { it.key as String to it.value as String }
        }

        fun intList(k: String): List<Int>? {
            val v = get(k, false) ?: return null
            val list = v as? List<*>
            if (list == null || list.any { it !is Long }) { problems += "$where: \"$k\" should be a list of whole numbers"; return null }
            return list.map { (it as Long).toInt() }
        }

        private val used = HashSet<String>()

        fun bool(k: String): Boolean? {
            val v = get(k, false) ?: return null
            return v as? Boolean ?: run { problems += "$where: \"$k\" should be true or false"; null }
        }

        /** Marks [k] as read by hand, so it isn't reported unknown. */
        fun used(k: String) {
            used += k
        }

        private fun get(k: String, need: Boolean): Any? {
            used += k
            val v = map[k]
            if (v == null && need) problems += "$where: \"$k\" is missing"
            return v
        }

        fun string(k: String, need: Boolean = false): String? {
            val v = get(k, need) ?: return null
            return v as? String ?: run { problems += "$where: \"$k\" should be text in quotes"; null }
        }

        fun int(k: String, lo: Int, hi: Int, need: Boolean = false): Int? {
            val v = get(k, need) ?: return null
            val n = v as? Long ?: run { problems += "$where: \"$k\" should be a whole number"; return null }
            if (n < lo || n > hi) { problems += "$where: \"$k\" should be from $lo to $hi"; return null }
            return n.toInt()
        }

        /** One text or a list of them. */
        fun stringOrList(k: String): List<String>? {
            val v = map[k]
            return if (v is String) string(k)?.let { listOf(it) } else strings(k)
        }

        fun strings(k: String, need: Boolean = false): List<String>? {
            val v = get(k, need) ?: return null
            val list = v as? List<*>
            if (list == null || list.any { it !is String }) { problems += "$where: \"$k\" should be a list of text [ \"...\" ]"; return null }
            return list.map { it as String }
        }

        /** Text in each language, { "en": "...", "fr": "..." }, English needed. */
        fun words(k: String, need: Boolean = false): Map<String, String>? {
            val v = get(k, need) ?: return null
            val m = v as? Map<*, *>
            if (m == null || m.values.any { it !is String }) { problems += "$where: \"$k\" should be { \"en\": \"...\", \"fr\": \"...\" }"; return null }
            for (lang in m.keys) if (lang !in LANGUAGES) problems += "$where: \"$k\" has an unknown language \"$lang\" (en or fr)"
            if ("en" !in m) problems += "$where: \"$k\" needs English, \"en\""
            return m.entries.associate { it.key as String to it.value as String }
        }

        fun done() {
            for (k in map.keys) if (k !in used) problems += "$where: unknown field \"$k\""
        }
    }
}

/** The base pack's data files, shipped inside the game. */
expect object BaseData {
    fun read(name: String): ByteArray
}

/** Unzips into files by path, refusing any file over [maxFile] bytes or a total over [maxTotal]. */
expect object Zip {
    fun read(zip: ByteArray, maxFile: Int, maxTotal: Int): Map<String, ByteArray>
}
