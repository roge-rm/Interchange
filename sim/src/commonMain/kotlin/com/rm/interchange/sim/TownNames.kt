package com.rm.interchange.sim

/**
 * Town names made the way Canadian places were named: after people and
 * saints, the land and the water, with English and French patterns. How many
 * are French depends on the region. Names come from the world's random
 * numbers, so every player's map has the same ones.
 */
object TownNames {
    /** How likely a town in each region is to have a French name, in hundredths. */
    private fun frenchShare(region: Region) = when (region) {
        Region.SHIELD -> 65
        Region.MARITIMES -> 30
        Region.MIXED -> 35
        Region.NORTH -> 20
        Region.PRAIRIES -> 12
        Region.ROCKIES -> 5
    }

    /** A name not in [taken], or one with a number after it if none comes up, and whether it's French. */
    fun make(rng: Rng, region: Region, taken: Set<String>): Pair<String, Boolean> {
        repeat(30) {
            val isFrench = rng.below(100) < frenchShare(region)
            val name = if (isFrench) french(rng, region) else english(rng, region)
            if (name !in taken) return name to isFrench
        }
        val base = english(rng, region)
        var n = 2
        while ("$base $n" in taken) n++
        return "$base $n" to false
    }

    private fun <T> Rng.pick(list: List<T>) = list[below(list.size)]

    // English: a root and an ending run together, or a family name with a word for the place.
    private val ROOTS = listOf(
        "Ash", "Bel", "Birch", "Black", "Brant", "Brook", "Cedar", "Clear", "Corn", "Dun", "Elm", "Fair", "Glen", "Grand",
        "Green", "Hart", "High", "Kin", "Lin", "Mid", "Mill", "Moose", "North", "Oak", "Pine", "Red", "Rock", "Rose",
        "Silver", "Spruce", "Stone", "Sun", "Swift", "Thorn", "Wil", "Wolf", "Wood", "Maple", "Hazel", "Lark", "Beaver",
        "Ox", "Kings", "Queens", "East", "West", "Fern", "Ridge", "Clover", "Willow", "Alder", "Tam", "Bram", "Brad",
    )
    private val ENDINGS = listOf(
        "ton", "ville", "field", "wood", "ford", "dale", "burn", "mount", "brook", "bury", "ridge", "view", "more",
        "land", "stead", "worth", "wick", "by", "haven", "port", "hurst", "ley", "leigh", "well", "gate", "cliff",
    )
    private val FAMILIES = listOf(
        "Abbott", "Barrie", "Campbell", "Dalton", "Fraser", "Gordon", "Hamilton", "Keith", "Lennox", "Morrison",
        "Murray", "Ross", "Stewart", "Wallace", "Grant", "Douglas", "Lachlan", "McLeod", "MacKay", "Carleton",
        "Sutherland", "Mackenzie", "Ferguson", "Harrison", "Elliott", "Graham", "Logan", "Munro", "Napier",
        "Beatty", "Cameron", "Galt", "Pembroke", "Selkirk", "Durham", "Wentworth", "Lindsay", "Melville", "Ainslie",
    )
    private val PLACES = listOf("Falls", "Junction", "Landing", "Creek", "Lake", "River", "Corners", "Mills", "Station", "Crossing", "Hill", "Springs")
    private val SEA_PLACES = listOf("Harbour", "Cove", "Bay", "Point", "Head", "Island")
    private val PRAIRIE_PLACES = listOf("Siding", "Junction", "Coulee", "Plains", "Creek", "Lake", "Station", "Butte")
    private val NORTH_PLACES = listOf("Lake", "Narrows", "Rapids", "River", "Portage", "Landing", "Bay")

    private fun english(rng: Rng, region: Region): String {
        val places = when (region) {
            Region.MARITIMES -> SEA_PLACES + PLACES
            Region.PRAIRIES -> PRAIRIE_PLACES
            Region.NORTH -> NORTH_PLACES
            else -> PLACES
        }
        return when (rng.below(10)) {
            0 -> "Port " + rng.pick(FAMILIES)
            1 -> (if (region == Region.NORTH || region == Region.ROCKIES) "Fort " else "Mount ") + rng.pick(FAMILIES)
            2 -> "New " + rng.pick(FAMILIES)
            3, 4 -> rng.pick(FAMILIES) + " " + rng.pick(places)
            else -> rng.pick(ROOTS) + rng.pick(ENDINGS)
        }
    }

    // French: saints, lakes and rivers, Notre-Dame, and roots with a French ending.
    private val SAINTS = listOf(
        "Jean", "Pierre", "Louis", "Laurent", "Joseph", "Félix", "Rémi", "Gédéon", "Ambroise", "Hilaire", "Benoît",
        "Valentin", "Eustache", "Lin", "Côme", "Zénon", "Ubalde", "Prosper", "Damase", "Honoré", "Anselme", "Léon",
        "Cyprien", "Isidore", "Narcisse", "Théophile", "Adrien", "Fabien", "Médard", "Odilon", "Siméon", "Urbain",
    )
    private val SAINTES = listOf(
        "Anne", "Marie", "Agathe", "Julie", "Claire", "Rose", "Émélie", "Monique", "Hélène", "Thérèse", "Brigitte",
        "Adèle", "Flavie", "Justine", "Cécile", "Germaine", "Luce", "Perpétue", "Sabine", "Sophie", "Victoire",
    )
    private val OF_PLACES = listOf("du-Lac", "des-Bois", "des-Monts", "de-la-Rivière", "du-Nord", "des-Prés", "de-l'Anse", "sur-Mer", "des-Érables", "de-la-Montagne")
    private val LAKES = listOf("Noir", "Vert", "Blanc", "aux-Sables", "à-la-Truite", "des-Loups", "Long", "Croche", "Bouchette", "Clair", "des-Îles", "aux-Cèdres")
    private val RIVERS = listOf("du-Loup", "Blanche", "aux-Saumons", "des-Prairies", "Noire", "aux-Outardes", "du-Moulin", "à-Pierre", "Bleue", "aux-Rats")
    private val NOTRE_DAME = listOf("Lourdes", "Bonsecours", "la-Paix", "Montauban", "la-Merci", "Pontmain", "la-Salette", "Grâce")
    private val FR_ROOTS = listOf("Beau", "Belle", "Bon", "Clair", "Grand", "Haut", "Mont", "Val", "Roche", "Pierre", "Ber", "Lan", "Dor", "Fleur", "Ver", "Mar", "Sau", "Ros")
    private val FR_ENDINGS = listOf("ville", "mont", "val", "rive", "fort", "bourg", "ière", "lieu", "court", "pré")
    private val FR_FRONTS = listOf("Pointe", "Cap", "Anse", "Baie", "Mont", "Grande-Anse", "Petite-Rivière", "Havre")
    private val FR_FAMILIES = listOf("Tremblay", "Gagnon", "Côté", "Bouchard", "Gauthier", "Morin", "Lavoie", "Fortin", "Gagné", "Ouellet", "Pelletier", "Bélanger", "Lévesque", "Bergeron", "Leblanc", "Paquette", "Girard", "Simard", "Boucher", "Caron")

    private fun french(rng: Rng, region: Region): String = when (rng.below(12)) {
        0, 1, 2 -> "Saint-" + rng.pick(SAINTS) + (if (rng.below(3) == 0) "-" + rng.pick(OF_PLACES) else "")
        3, 4 -> "Sainte-" + rng.pick(SAINTES) + (if (rng.below(3) == 0) "-" + rng.pick(OF_PLACES) else "")
        5 -> "Lac-" + rng.pick(LAKES)
        6 -> "Rivière-" + rng.pick(RIVERS)
        7 -> "Notre-Dame-de-" + rng.pick(NOTRE_DAME)
        8 -> rng.pick(FR_FRONTS) + "-" + rng.pick(FR_FAMILIES)
        9 -> if (region == Region.NORTH) "Fort-" + rng.pick(FR_FAMILIES) else rng.pick(FR_FAMILIES) + "ville"
        else -> rng.pick(FR_ROOTS) + rng.pick(FR_ENDINGS)
    }
}
