package com.rm.interchange.sim

/*
 * Content packs: vehicles (and later everything else) as data, so anyone can
 * add to the game. packs/SPEC.md is the standard; this reads it.
 *
 * A pack is a zip of manifest.json, vehicles.json and sprites/<name>.png. The
 * game's own vehicles are the base pack, in the same format.
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
    /** Starting tractive effort in kN, for engines. */
    val tractive: Int? = null,
    /** steam, diesel or electric, for engines. */
    val traction: String? = null,
    /** Years it runs well before it wears out, and how reliable it is new, in percent. */
    val life: Int? = null,
    val reliability: Int? = null,
    /** wedge or rotary, for a snow plow. */
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
)

/** A town building as a pack gives it. [zones] are bits of [Zones], from the outskirts in to the centre. */
class HouseDef(
    val id: String,
    val names: Map<String, String>,
    val from: Int,
    val until: Int,
    val residents: Int,
    val jobs: Int,
    val zones: Int,
    val minPopulation: Int,
    val weight: Int,
    val monument: Boolean = false,
)

/**
 * An industry as a pack gives it: its tile pieces as rows of part names, the
 * regions it's found in with how often, the ground it needs, what it makes a
 * month by cargo id, and what it takes. One that takes something makes its
 * cargo from what it's brought.
 */
class IndustryDef(
    val id: String,
    val names: Map<String, String>,
    val from: Int,
    val until: Int,
    val regions: Map<String, Int>,
    val ground: List<String>,
    val layout: List<List<String>>,
    val produces: Map<String, Int>,
    val accepts: List<String>,
    /** How much it makes each month, January first, in percent of its usual. */
    val seasons: List<Int>,
    /** It stands on the shore, the first row of its layout facing the water. */
    val water: Boolean,
    /** How the weather changes it, by [IndustryKind.WEATHER] names. */
    val weather: List<String> = emptyList(),
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
    /** Vehicles of other packs this one turns off, as pack:id. */
    val disables: List<String>,
    val vehicles: List<VehicleDef>,
    val houses: List<HouseDef>,
    val industries: List<IndustryDef>,
    /** The sprite sheets by name, as PNG. */
    val sprites: Map<String, ByteArray>,
    val hash: Long,
) {
    val ref get() = PackRef(id, version, hash)
    fun name(language: String) = names[language] ?: names["en"] ?: id
    fun description(language: String) = descriptions[language] ?: descriptions["en"] ?: ""
}

object Packs {
    /** The newest pack format this game reads. */
    const val FORMAT = 1
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

    private val ID = Regex("[a-z0-9][a-z0-9_-]{0,31}")
    private val LANGUAGES = setOf("en", "fr")

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
                    tractive = f.int("tractive", 1, 10_000),
                    traction = f.string("traction"),
                    life = f.int("life", 1, 200),
                    reliability = f.int("reliability", 1, 100),
                    plow = f.string("plow"),
                    tram = f.bool("tram"),
                    icebreaker = f.int("icebreaker", 1, 500),
                    hovercraft = f.bool("hovercraft"),
                    range = f.int("range", 1, 30_000),
                    runway = f.string("runway"),
                    floats = f.bool("floats"),
                )
                f.done()
                if (!fresh && kind != null) problems += "$at: a vehicle that replaces another keeps its kind"
                if (def.from != null && def.until != null && def.from >= def.until) problems += "$at: from should be before until"
                val moves = kind == VehicleKind.ENGINE || kind == VehicleKind.ROAD || kind == VehicleKind.SHIP || kind == VehicleKind.AIRCRAFT
                if (kind != null && !moves && (def.speed != null || def.power != null)) problems += "$at: only engines, road vehicles, ships and aircraft have speed and power"
                if (kind != null && kind != VehicleKind.ENGINE && kind != VehicleKind.ROAD && (def.tractive != null || def.traction != null)) problems += "$at: only engines and road vehicles have tractive effort and traction"
                if (def.plow != null && def.plow !in Plows.NAMES) problems += "$at: plow should be ${Plows.NAMES.joinToString(" or ")}"
                if (def.plow != null && kind != null && kind != VehicleKind.WAGON && kind != VehicleKind.ROAD) problems += "$at: only wagons and road vehicles are plows"
                if (def.tram != null && kind != null && kind != VehicleKind.ROAD) problems += "$at: only road vehicles are streetcars"
                if ((def.icebreaker != null || def.hovercraft != null) && kind != null && kind != VehicleKind.SHIP) problems += "$at: only ships break ice or hover"
                if ((def.range != null || def.runway != null || def.floats != null) && kind != null && kind != VehicleKind.AIRCRAFT) problems += "$at: only aircraft have a range, a runway and floats"
                if (def.runway != null && def.runway !in Runways.NAMES) problems += "$at: runway should be ${Runways.NAMES.joinToString(", ")}"
                if (kind == VehicleKind.AIRCRAFT && fresh && def.runway == null) problems += "$at: an aircraft needs a runway"
                if (def.traction != null && def.traction !in Traction.NAMES) problems += "$at: traction should be ${Traction.NAMES.joinToString(", ")}"
                if (def.tender != null && kind != null && kind != VehicleKind.ENGINE) problems += "$at: only engines have a tender"
                if (def.capacity != null && def.capacity > 0 && def.carries == null) problems += "$at: capacity without carries"
                for (cg in def.carries.orEmpty()) if (Cargo.of(cg) < 0) problems += "$at: unknown cargo \"$cg\" (${Cargo.IDS.joinToString()})"
                if (!spritesElsewhere && def.sprite != null && "sprites/${def.sprite}.png" !in inner) problems += "$at: sprites/${def.sprite}.png is missing"
                vehicles += def
            }
        }
        val houses = ArrayList<HouseDef>()
        if (housesBytes.isNotEmpty()) {
            // Town buildings are drawn from the game's own sheets for now, so only the base pack has them.
            if (!base) problems += "houses.json: town buildings in packs come with a later version of the game"
            val root = json("houses.json", housesBytes)
            val list = (root as? Map<*, *>)?.get("houses") as? List<*>
            if (root != null && list == null) problems += "houses.json should be { \"houses\": [ ... ] }"
            val seen = HashSet<String>()
            list?.forEachIndexed { n, h ->
                val map = h as? Map<*, *> ?: run { problems += "houses.json, building ${n + 1} should be an object { }"; return@forEachIndexed }
                val f = Fields("houses.json, building ${n + 1}", map, problems)
                val hid = f.string("id", need = true) ?: ""
                f.where = "houses.json, \"$hid\""
                if (!seen.add(hid)) problems += "${f.where}: id is used twice"
                var zones = 0
                for (z in f.strings("zones") ?: emptyList()) {
                    val k = Zones.NAMES.indexOf(z)
                    if (k < 0) problems += "${f.where}: zone \"$z\" should be outskirts, suburbs, inner or centre" else zones = zones or (1 shl k)
                }
                val monument = f.bool("monument") ?: false
                if (zones == 0 && !monument) problems += "${f.where}: \"zones\" needs at least one zone"
                houses += HouseDef(
                    hid, f.words("name", need = true) ?: emptyMap(),
                    f.int("from", 1700, 2300, need = true) ?: 0, f.int("until", 1700, 2300, need = true) ?: 0,
                    f.int("residents", 0, 10_000, need = true) ?: 0, f.int("jobs", 0, 10_000, need = true) ?: 0,
                    zones, f.int("min_population", 0, 10_000_000) ?: 0, f.int("weight", 1, 100) ?: 1, monument,
                )
                f.done()
            }
        }
        val industries = ArrayList<IndustryDef>()
        if (industriesBytes.isNotEmpty()) {
            if (!base) problems += "industries.json: industries in packs come with a later version of the game"
            val root = json("industries.json", industriesBytes)
            val list = (root as? Map<*, *>)?.get("industries") as? List<*>
            if (root != null && list == null) problems += "industries.json should be { \"industries\": [ ... ] }"
            list?.forEachIndexed { n, item ->
                val map = item as? Map<*, *> ?: run { problems += "industries.json, industry ${n + 1} should be an object { }"; return@forEachIndexed }
                val f = Fields("industries.json, industry ${n + 1}", map, problems)
                val iid = f.string("id", need = true) ?: ""
                f.where = "industries.json, \"$iid\""
                val regions = (map["regions"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to ((v as? Long)?.toInt() ?: 0) } ?: emptyMap()
                f.used("regions")
                for (r in regions.keys) if (Region.entries.none { it.name.lowercase() == r }) problems += "${f.where}: unknown region \"$r\""
                val layout = (f.strings("layout") ?: emptyList()).map { row -> row.split(' ').filter { it.isNotEmpty() } }
                if (layout.isEmpty() || layout.any { it.size != layout[0].size }) problems += "${f.where}: \"layout\" needs rows of the same length"
                val produces = (map["produces"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to ((v as? Long)?.toInt() ?: 0) } ?: emptyMap()
                f.used("produces")
                val accepts = f.strings("accepts") ?: emptyList()
                for (cg in produces.keys + accepts) if (Cargo.of(cg) < 0) problems += "${f.where}: unknown cargo \"$cg\""
                industries += IndustryDef(
                    iid, f.words("name", need = true) ?: emptyMap(), f.int("from", 1700, 2300, need = true) ?: 0, f.int("until", 1700, 2300, need = true) ?: 0,
                    regions, f.strings("ground") ?: emptyList(), layout, produces, accepts,
                    f.seasons(), f.bool("water") ?: false, f.strings("weather") ?: emptyList(),
                )
                industries.last().weather.filter { it !in IndustryKind.WEATHER }.forEach { problems += "${f.where}: unknown weather rule \"$it\"" }
                f.done()
            }
        }
        if (problems.isNotEmpty()) throw BadPack(problems)
        val sprites = inner.filterKeys { it.startsWith("sprites/") && it.endsWith(".png") }.mapKeys { it.key.removePrefix("sprites/").removeSuffix(".png") }
        return Pack(id, version, names, descriptions, author, licence, needs, disables, vehicles, houses, industries, sprites, hash(manifestBytes, vehiclesBytes, housesBytes, industriesBytes))
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
        /** An industry's months, twelve percentages, or all a hundred if it doesn't say. */
        fun seasons(): List<Int> {
            val v = intList("seasons") ?: return List(12) { 100 }
            if (v.size != 12) { problems += "$where: \"seasons\" needs twelve months"; return List(12) { 100 } }
            return v
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

        fun strings(k: String): List<String>? {
            val v = get(k, false) ?: return null
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
