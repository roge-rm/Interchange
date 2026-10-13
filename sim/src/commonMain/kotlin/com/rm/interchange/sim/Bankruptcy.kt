package com.rm.interchange.sim

/*
 * Companies that can't pay their way, as in OpenTTD. One that couldn't
 * cover its bills even borrowing all it can is in trouble; after a few months
 * the news says so, then a computer company is offered for sale, and if
 * nobody buys it, it goes under: its vehicles are gone and its track,
 * stations and depots taken up, its roads left to everyone. The player's
 * company is only warned.
 */

/** Months in trouble before the news warns, a company is offered for sale, and it goes under. */
const val TROUBLE_WARN = 3
const val TROUBLE_FOR_SALE = 6
const val TROUBLE_BANKRUPT = 9

/** Whether [c] couldn't pay its way even borrowing all the bank will lend. */
fun World.inTrouble(c: Company): Boolean = c.money + (maxLoan() - c.loan) < 0

/** Whether [c] is up for sale: a computer company long in trouble. */
fun World.forSale(c: Company): Boolean = !c.gone && c.id != 0 && c.trouble >= TROUBLE_FOR_SALE

/** What buying [c] costs: what it's worth, debts and all, or nothing if it owes more than it has. */
fun World.salePrice(c: Company): Long = companyValue(c).coerceAtLeast(0)

/** The end of each month, after interest: who's in trouble, warned, for sale or gone under. */
internal fun World.troubleMonth() {
    for (c in companies) {
        if (c.gone) continue
        if (inTrouble(c)) c.trouble++ else c.trouble = 0
        when {
            c.trouble == 0 -> {}
            c.id == 0 -> if (c.trouble % TROUBLE_WARN == 0) tell(News.COMPANY_TROUBLE, c.id)
            c.trouble == TROUBLE_WARN -> tell(News.COMPANY_TROUBLE, c.id)
            c.trouble == TROUBLE_FOR_SALE -> tell(News.COMPANY_FOR_SALE, c.id)
            c.trouble >= TROUBLE_BANKRUPT -> {
                dissolve(c)
                tell(News.COMPANY_BANKRUPT, c.id)
            }
        }
    }
}

internal fun World.buyCompany(buyer: Company, c: Command.BuyCompany) {
    val target = companies.getOrNull(c.target) ?: return
    if (target.id == buyer.id || !forSale(target)) return
    val cost = salePrice(target)
    if (buyer.money < cost) return
    spend(buyer, Books.CONSTRUCTION, cost)
    takeOver(target, buyer)
    tell(News.COMPANY_BOUGHT, target.id or (buyer.id shl 8))
}

/** Everything [from] has goes to [to], its money and debts with it, and [from] is gone. */
internal fun World.takeOver(from: Company, to: Company) {
    val a = from.id.toByte()
    val b = to.id.toByte()
    for (i in owner.indices) {
        if (owner[i] == a) { owner[i] = b; touched(i % width, i / width) }
        if (roadOwner[i] == a) { roadOwner[i] = b; touched(i % width, i / width) }
    }
    for (s in stations) if (s != null && s.owner == from.id) s.owner = to.id
    for (d in depots) if (d != null && d.owner == from.id) d.owner = to.id
    for (s in spans) if (s != null && s.owner == from.id) s.owner = to.id
    for (p in airports) if (p != null && p.owner == from.id) p.owner = to.id
    for (t in trains) if (t != null && t.owner == from.id) t.owner = to.id
    for (v in roadVehicles) if (v != null && v.owner == from.id) v.owner = to.id
    for (v in ships) if (v != null && v.owner == from.id) v.owner = to.id
    for (v in aircraft) if (v != null && v.owner == from.id) v.owner = to.id
    for (s in subsidies) if (s.company == from.id) s.company = to.id
    to.money += from.money - from.loan
    from.money = 0
    from.loan = 0
    from.gone = true
    from.trouble = 0
    // A computer company buying one runs its routes as its own; the player takes them on by hand.
    rivals.firstOrNull { it.company == from.id }?.let { gone ->
        rivals.firstOrNull { it.company == to.id }?.let { r ->
            r.routes += gone.routes
            r.failed += gone.failed
        }
        rivals.remove(gone)
    }
    networkChanged()
    roadsChanged()
    catchmentDirty = true
}

/** [c] goes under: its vehicles are gone, its track, stations and depots taken up, and its roads left to everyone. */
internal fun World.dissolve(c: Company) {
    for (t in trains) if (t != null && t.owner == c.id) {
        t.inDepot = true
        countOn(t)
        reserved.entries.removeAll { it.value == t.id }
        trains[t.id] = null
    }
    for (v in roadVehicles) if (v != null && v.owner == c.id) roadVehicles[v.id] = null
    for (v in ships) if (v != null && v.owner == c.id) ships[v.id] = null
    for (v in aircraft) if (v != null && v.owner == c.id) aircraft[v.id] = null
    // With nothing standing on them, Clear takes up what's on each of its tiles; the bank pays.
    c.money = Long.MAX_VALUE / 4
    for (i in 0 until width * height) {
        if (owns(c.id, i)) clearTile(c, Command.Clear(c.id, i % width, i / width))
        if (roadOwner[i].toInt() == c.id) { roadOwner[i] = -1; touched(i % width, i / width) }
    }
    c.money = 0
    c.loan = 0
    c.gone = true
    c.trouble = 0
    for (s in subsidies) if (s.company == c.id) s.months = 0
    rivals.removeAll { it.company == c.id }
    roadsChanged()
    catchmentDirty = true
}
