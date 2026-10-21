package com.rm.interchange.sim

/*
 * Companies that can't pay their way, as in OpenTTD. One that couldn't
 * cover its bills even borrowing all it can is in trouble; after four months
 * the news says so, after seven it's offered for sale, and a computer company
 * nobody buys goes under at ten: its vehicles are gone and its track,
 * stations and depots taken up, its roads left to everyone. The player is
 * warned at four, seven and ten months and goes under after a year, or when
 * a hard rival buys it, and the bank sets them up with a new company.
 * A player can also buy any computer company outright at a premium, as in
 * OpenTTD's hostile takeover. The months are months of work ([workMonthEnded]),
 * so a company has as long to put things right against the work done at any
 * day length.
 */

/** Months of work in trouble before the news warns, a company is offered for sale, a rival goes under, and the player does. */
const val TROUBLE_WARN = 4
const val TROUBLE_FOR_SALE = 7
const val TROUBLE_BANKRUPT = 10
const val TROUBLE_PLAYER = 12

/** Whether [c] couldn't pay its way even borrowing all the bank will lend. */
fun World.inTrouble(c: Company): Boolean = c.money + (loanLimit(c) - c.loan) < 0

/** Whether [c] is up for sale: long in trouble, and if it's the player's, only to hard rivals. */
fun World.forSale(c: Company): Boolean = !c.gone && c.trouble >= TROUBLE_FOR_SALE && (!human(c.id) || settings.rivalLevel == RivalLevel.HARD)

/** What buying [c] costs: what it's worth, debts and all, or nothing if it owes more than it has. */
fun World.salePrice(c: Company): Long = netWorth(c).coerceAtLeast(0)

/** What buying computer company [c] outright costs when it isn't for sale: half again what it's worth. */
fun World.takeoverPrice(c: Company): Long = salePrice(c) * 3 / 2

/** Whether a player can buy [c] outright: a computer company still running and not up for sale. */
fun World.canTakeOver(c: Company): Boolean = !c.gone && !human(c.id) && !forSale(c)

/** The end of each month of work: who's in trouble, warned, for sale or gone under. */
internal fun World.troubleMonth() {
    for (c in companies) {
        if (c.gone) continue
        if (inTrouble(c)) c.trouble++ else c.trouble = 0
        when {
            c.trouble == 0 -> {}
            human(c.id) -> when (c.trouble) {
                TROUBLE_WARN, TROUBLE_BANKRUPT -> tell(News.COMPANY_TROUBLE, c.id)
                TROUBLE_FOR_SALE -> tell(if (forSale(c)) News.COMPANY_FOR_SALE else News.COMPANY_TROUBLE, c.id)
                TROUBLE_PLAYER -> {
                    dissolve(c)
                    restart(c)
                }
            }
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
    if (target.id == buyer.id) return
    val cost = when {
        forSale(target) -> salePrice(target)
        canTakeOver(target) && human(buyer.id) -> takeoverPrice(target)
        else -> return
    }
    if (buyer.money < cost) return
    val player = human(target.id)
    spend(buyer, Books.CONSTRUCTION, cost)
    takeOver(target, buyer)
    tell(News.COMPANY_BOUGHT, target.id or (buyer.id shl 8))
    if (player) restart(target)
}

/**
 * A player's company, gone under or bought out, starts again: the start money and loan, as a new one would, its books
 * clean, every town thinking of it as of a stranger. A statue it had stays up as the town's own.
 */
internal fun World.restart(c: Company, news: Boolean = true) {
    c.gone = false
    c.trouble = 0
    startFunds(c)
    c.runningOwed = 0
    c.upkeepOwed = 0
    c.thisYear.fill(0)
    c.lastYear.fill(0)
    c.history.clear()
    c.monthIn = 0
    c.monthOut = 0
    // The company starting again does not keep orders from vehicles the last one sold.
    c.orderBackups.fill(null)
    for (g in goals) g.count[c.id] = 0
    for (t in towns) {
        t.approval[c.id] = START_APPROVAL
        t.statue[c.id] = -1
        if (t.exclusive == c.id) { t.exclusive = -1; t.exclusiveDays = 0 }
    }
    if (news) tell(News.COMPANY_RESTARTED, c.id)
}

/** Everything [from] has goes to [to], its money and debts with it, and [from] is gone. */
internal fun World.takeOver(from: Company, to: Company) {
    val a = from.id.toByte()
    val b = to.id.toByte()
    for (i in owner.indices) {
        if (owner[i] == a) { owner[i] = b; touched(i % width, i / width) }
        if (roadOwner[i] == a) { roadOwner[i] = b; touched(i % width, i / width) }
    }
    for (g in goals) g.count[from.id] = 0
    for (s in stations) if (s != null && s.owner == from.id) s.owner = to.id
    for (d in depots) if (d != null && d.owner == from.id) d.owner = to.id
    for (s in spans) if (s != null && s.owner == from.id) s.owner = to.id
    for (p in airports) if (p != null && p.owner == from.id) p.owner = to.id
    for (t in trains) if (t != null && t.owner == from.id) t.owner = to.id
    for (v in roadVehicles) if (v != null && v.owner == from.id) v.owner = to.id
    for (v in ships) if (v != null && v.owner == from.id) v.owner = to.id
    for (v in aircraft) if (v != null && v.owner == from.id) v.owner = to.id
    for (s in subsidies) if (s.company == from.id) s.company = to.id
    to.trackTiles += from.trackTiles
    to.roadUpkeep += from.roadUpkeep
    from.trackTiles = 0
    from.roadUpkeep = 0
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
    for (groups in c.groups) groups.clear()
    c.orderBackups.fill(null)
    // With nothing standing on them, Clear takes up what's on each of its tiles; the bank pays.
    c.money = Long.MAX_VALUE / 4
    for (i in 0 until width * height) {
        if (owns(c.id, i)) clearTile(c, Command.Clear(c.id, i % width, i / width))
        if (roadOwner[i].toInt() == c.id) { roadOwner[i] = -1; touched(i % width, i / width) }
    }
    c.money = 0
    c.loan = 0
    c.trackTiles = 0
    c.roadUpkeep = 0
    c.gone = true
    c.trouble = 0
    for (s in subsidies) if (s.company == c.id) s.months = 0
    rivals.removeAll { it.company == c.id }
    roadsChanged()
    catchmentDirty = true
}

/** Whether company [id] is a player's, not the computer's. A rival gone under or bought out counts, as a player may take its slot. */
fun World.human(id: Int): Boolean = id in companies.indices && rivals.none { it.company == id }

/**
 * Starts a player's company in slot [c].company, if that slot is free, named [c].name, or after a town if that's
 * blank or taken.
 */
internal fun World.newCompany(c: Command.NewCompany) {
    val id = c.company
    val given = cleanCompanyName(c.name)
    fun name() = if (given.isNotEmpty() && companyNameFree(given, id)) given else playerCompanyName(id)
    when {
        id == companies.size && id < MAX_COMPANIES -> companies += Company(id, name(), 0).also { startFunds(it); giveInsignia(it) }
        companies.getOrNull(id)?.gone == true -> {
            companies[id].name = name()
            giveInsignia(companies[id])
            restart(companies[id], news = false)
        }
        else -> return
    }
    for (t in towns) t.approval[id] = START_APPROVAL
    tell(News.COMPANY_STARTED, id)
}
