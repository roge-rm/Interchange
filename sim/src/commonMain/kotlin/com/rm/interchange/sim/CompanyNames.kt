package com.rm.interchange.sim

/** Longest a company's name can be, in characters as seen (code points). */
const val COMPANY_NAME_MOST = 32

/** Names from a town, in English and in French; %s is the town. */
private val ENGLISH_NAMES = listOf("%s Transport", "%s Coach Lines", "%s & District")
private val FRENCH_NAMES = listOf("Transports %s", "Messageries %s", "Diligences %s")

/** How many ways there are to name a company after a town. */
internal const val TOWN_NAME_KINDS = 3

/** Whether company [id] could be called [name]: no other company still going has it, in any case. */
fun World.companyNameFree(name: String, id: Int): Boolean =
    companies.none { it.id != id && !it.gone && it.name.equals(name, ignoreCase = true) }

/**
 * A name for company [id] from town [t] in the town's language, the [first] kind if it's free, else the next free
 * kind, else the first with a number after it.
 */
internal fun World.townCompanyName(t: Town, first: Int, id: Int): String {
    val names = if (t.french) FRENCH_NAMES else ENGLISH_NAMES
    for (k in names.indices) {
        val name = names[(first + k) % names.size].replace("%s", t.name)
        if (companyNameFree(name, id)) return name
    }
    return numbered(names[first % names.size].replace("%s", t.name), id)
}

/** [base] with the lowest number after it that company [id] could be called. */
private fun World.numbered(base: String, id: Int): String {
    var n = 2
    while (!companyNameFree("$base $n", id)) n++
    return "$base $n"
}

/**
 * A name for a player's company [id]: from the biggest town no company is named after yet, or the biggest town if
 * all are. The game starts looking at the biggest town.
 */
internal fun World.playerCompanyName(id: Int): String {
    if (towns.isEmpty()) return if (companyNameFree("Company", id)) "Company" else numbered("Company", id)
    val biggest = towns.sortedWith(compareByDescending<Town> { it.population }.thenBy { it.id })
    val t = biggest.firstOrNull { !townUsed(it, id) } ?: biggest[0]
    return townCompanyName(t, 0, id)
}

/** Whether a company other than [id], still going, is named after town [t]. */
private fun World.townUsed(t: Town, id: Int) = companies.any { it.id != id && !it.gone && hasWord(it.name, t.name) }

/** Whether [word] is in [text] whole, with no letter or digit either side, so "Lac" isn't in "Lachine Transport". */
private fun hasWord(text: String, word: String): Boolean {
    var at = text.indexOf(word)
    while (at >= 0) {
        val end = at + word.length
        if ((at == 0 || !text[at - 1].isLetterOrDigit()) && (end == text.length || !text[end].isLetterOrDigit())) return true
        at = text.indexOf(word, at + 1)
    }
    return false
}

/**
 * [name] with control characters and halves of broken pairs left out, cut to [COMPANY_NAME_MOST] code points.
 * A lone half would be saved as a replacement character and put players' worlds out of step.
 */
fun capCompanyName(name: String): String {
    val out = StringBuilder()
    var points = 0
    var i = 0
    while (i < name.length && points < COMPANY_NAME_MOST) {
        val ch = name[i]
        when {
            ch.isHighSurrogate() && i + 1 < name.length && name[i + 1].isLowSurrogate() -> { out.append(ch).append(name[i + 1]); i++; points++ }
            ch.isSurrogate() || ch < ' ' || ch == '\u007f' -> {}
            else -> { out.append(ch); points++ }
        }
        i++
    }
    return out.toString()
}

/** [name] as a company would be called: [capCompanyName] and trimmed. */
fun cleanCompanyName(name: String): String = capCompanyName(name.trim()).trim()

/** [t] if no other company is named after it, else the nearest town that isn't, else [t]. */
internal fun World.nameTown(t: Town, id: Int): Town {
    if (!townUsed(t, id)) return t
    return towns.filter { !townUsed(it, id) }
        .minWithOrNull(compareBy<Town> { maxOf(kotlin.math.abs(it.x - t.x), kotlin.math.abs(it.y - t.y)) }.thenBy { it.id }) ?: t
}

/** Renames [company] to [name], trimmed and cut to [COMPANY_NAME_MOST], if that leaves a name no other company has. */
internal fun World.renameCompany(company: Company, name: String) {
    val clean = cleanCompanyName(name)
    if (clean.isEmpty() || clean == company.name || !companyNameFree(clean, company.id)) return
    company.name = clean
}
