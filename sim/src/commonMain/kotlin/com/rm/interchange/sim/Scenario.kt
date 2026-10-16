package com.rm.interchange.sim

/*
 * Scenarios: a world made in the editor with a title, an intro, goals and the
 * money each player starts with. A scenario file is a save written by
 * [Scenarios.export], with the editor off and the scenario's money given, so
 * loading it starts play on its first day.
 */

/**
 * A scenario's [title] and [intro] (a few paragraphs, split by blank lines), the [money] each player's company starts
 * with and the [loan] it starts owing, the loan's money in hand on top, whether [ALL] its goals must be met to win or
 * [ANY] one, and its campaign [chapter], blank for none. [outcome] is [OPEN] until it's won or lost.
 */
class Scenario(
    var title: String = "",
    var intro: String = "",
    var money: Long = Prices.START_MONEY,
    var loan: Long = 0,
    var win: Int = ALL,
    var chapter: String = "",
) {
    var outcome = OPEN

    companion object {
        const val ALL = 0
        const val ANY = 1

        const val OPEN = 0
        const val WON = 1
        const val LOST = 2

        /** The longest title, intro and chapter id, in characters. */
        const val TITLE_MOST = 60
        const val INTRO_MOST = 4000
        const val CHAPTER_MOST = 40
    }
}

/**
 * Whether the scenario's been won or lost, told once: won when its goals are met as its rule asks, lost once that
 * can't happen any more because a goal it needs has failed.
 */
internal fun World.scenarioCheck() {
    val s = scenario ?: return
    if (s.outcome != Scenario.OPEN || goals.isEmpty()) return
    val met = goals.count { it.status == Goal.MET }
    val failed = goals.count { it.status == Goal.FAILED }
    s.outcome = when (s.win) {
        Scenario.ANY -> if (met > 0) Scenario.WON else if (failed == goals.size) Scenario.LOST else return
        else -> if (failed > 0) Scenario.LOST else if (met == goals.size) Scenario.WON else return
    }
    tell(if (s.outcome == Scenario.WON) News.SCENARIO_WON else News.SCENARIO_LOST, 0)
}

/** What a player's company starts with, or starts again with: the scenario's money if it has one, else the bank's. */
internal fun World.startMoney(): Long = scenario?.money ?: price(Prices.startMoney(Calendar.year(startYear, day)))

/** The loan a player's company starts owing in a scenario, its money in hand on top of [startMoney]. */
internal fun World.startLoan(): Long = scenario?.loan ?: 0L

/** Gives [c] what a player's company starts with: [startMoney] and [startLoan], owing the loan. */
internal fun World.startFunds(c: Company) {
    c.loan = startLoan()
    c.founded = startMoney()
    c.money = c.founded + c.loan
}

object Scenarios {
    /** Scenario files end in this, to list them apart from saves; inside they're saves. */
    const val EXTENSION = "ichscn"

    /** [world], made in the editor, as a scenario file: its editor off and each player's company given the scenario's money and loan. */
    fun export(world: World): ByteArray = Save.write(world, asScenario = true)

    /** A scenario file as a world to play. */
    fun play(data: ByteArray, find: (PackRef) -> Pack? = Save::basePack): World = Save.read(data, find).also { it.editor = false }

    /** A scenario file as a world to go on editing. */
    fun edit(data: ByteArray, find: (PackRef) -> Pack? = Save::basePack): World = Save.read(data, find).also {
        it.editor = true
        if (it.scenario == null) it.scenario = Scenario()
    }
}
