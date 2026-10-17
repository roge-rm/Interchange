package com.rm.interchange.sim

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test

/**
 * Goals played through, written to build/balance/campaign.md: each campaign chapter by the scripted [Player] aiming at
 * its goals (three ways of playing, as a chapter's map is always the same) and by an idle company, then new games with
 * each Goals setting from a few start years at day lengths 1 and 10, by the player and idle. Slow, so it runs only
 * when asked: `./gradlew :sim:jvmTest --tests '*CampaignRun*' -Pinterchange.campaignRun=1`, and optionally
 * interchange.campaignParts (chapters, generated), campaignChapters, campaignStyles, campaignSets, campaignStarts,
 * campaignLengths and campaignSeeds as comma lists, and campaignYears for the generated games.
 */
class CampaignRun {
    @Test
    fun table() {
        System.getProperty("interchange.campaignRun") ?: return
        val parts = list("interchange.campaignParts", "chapters,generated")
        val chapters = list("interchange.campaignChapters", Campaign.CHAPTERS.filter { it.ready }.joinToString(",") { it.id })
        val styles = list("interchange.campaignStyles", "1,2,3").map { it.toInt() }
        val sets = list("interchange.campaignSets", "TOWNS,DELIVERIES,CONNECTIONS,MIXED").map { GoalSet.valueOf(it.uppercase()) }
        val starts = list("interchange.campaignStarts", "1836,1900,1950,2000").map { it.toInt() }
        val lengths = list("interchange.campaignLengths", "1,10").map { it.toInt() }
        val seeds = list("interchange.campaignSeeds", "1,2,3").map { it.toLong() }
        val years = (System.getProperty("interchange.campaignYears") ?: "20").toInt()

        // A style of 0 is the idle company.
        class ChapterJob(val id: String, val style: Int)
        class GameJob(val set: GoalSet, val start: Int, val length: Int, val seed: Long, val idle: Boolean)
        val chapterJobs = if ("chapters" in parts) chapters.flatMap { id -> (styles + 0).map { ChapterJob(id, it) } } else emptyList()
        val gameJobs = if ("generated" in parts) sets.flatMap { s -> starts.flatMap { y -> lengths.flatMap { l -> seeds.flatMap { e -> listOf(GameJob(s, y, l, e, false), GameJob(s, y, l, e, true)) } } } } else emptyList()
        val pool = Executors.newFixedThreadPool(8)
        // The big chapters first, so the pool isn't left waiting on one at the end.
        val cf = chapterJobs.sortedBy { if (it.id == "high_speed") 0 else 1 }.map { j -> j to pool.submit(Callable { chapter(j.id, j.style) }) }
        val gf = gameJobs.map { j -> j to pool.submit(Callable { game(j.set, j.start, j.length, j.seed, j.idle, years) }) }
        val chapterDone = cf.map { (j, f) -> j to f.get() }
        val gameDone = gf.map { (j, f) -> j to f.get() }
        pool.shutdown()

        val out = StringBuilder("# Goals and the campaign\n\n")
        out.append("Progress is company 0's against the goal's amount, in percent: people for a town (met only once the company has delivered there), ")
        out.append("what's delivered since the goal's start year, passengers between the two towns in the best calendar year for a connection, company value. ")
        out.append("The player aims at the goals (see `Player`), styles 1 rail first, 2 road first, 3 rail first taking the goals last to first; generated games play seed 1, 2 and 3 in styles 1, 2 and 3. Idle does nothing. ")
        out.append("Unserved: a town goal's town the company never delivered to. Never tried: the player kept its money back (a fifth of its start) and never built for it. ")
        out.append("Went under: the company couldn't pay its way and the bank started it again, goal counts and all.\n\n")
        if (chapterDone.isNotEmpty()) chapterReport(out, chapterDone.map { it.first.id to it.first.style to it.second })
        if (gameDone.isNotEmpty()) gameReport(out, gameDone.map { (j, r) -> GameKey(j.set, j.start, j.length, j.seed, j.idle) to r }, years)
        val dir = File("build/balance").apply { mkdirs() }
        File(dir, "campaign.md").writeText(out.toString())
    }

    private fun list(key: String, default: String) = (System.getProperty(key) ?: default).split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** A goal's end: its kind, cargo, amount and deadline, the best progress seen, and when it was met or failed. */
    class GoalEnd(
        val kind: Int, val cargo: Int, val toTown: Boolean, val amount: Long, val deadline: Int, val best: Long, val status: Int, val doneYear: Int,
        val served: Boolean, val tried: Boolean,
    ) {
        val percent get() = if (amount <= 0) 0L else best * 100 / amount
    }

    /** One calendar year's end: company 0's money, loan and value, each goal's progress, and each rival's routes, vehicles and value. */
    class Snap(val year: Int, val money: Long, val loan: Long, val value: Long, val status: String, val progress: List<Long>, val rivals: List<String>)

    class Result(val outcome: Int, val year: Int, val month: Int, val goals: List<GoalEnd>, val snaps: List<Snap>, val log: List<String>, val routes: String, val secs: Long)

    data class GameKey(val set: GoalSet, val start: Int, val length: Int, val seed: Long, val idle: Boolean)

    companion object {
        private val KINDS = listOf("town", "deliver", "connect", "value", "profit")

        /** Keeps the best progress seen on each goal, as connections count again each year. */
        private class Watch(val w: World) {
            val best = LongArray(w.goals.size)
            /** Each goal's best progress this calendar year, for connections, which count again each year. */
            val yearBest = LongArray(w.goals.size)
            val snaps = ArrayList<Snap>()
            var month = -1

            fun day() {
                for ((i, g) in w.goals.withIndex()) {
                    val p = w.goalProgress(g, 0)
                    yearBest[i] = maxOf(yearBest[i], p)
                    if (g.status == Goal.OPEN || best[i] == 0L) best[i] = maxOf(best[i], p)
                }
            }

            fun snap() {
                val c = w.companies[0]
                val rivals = w.rivals.map { r ->
                    val rc = w.companies[r.company]
                    val vehicles = w.trains.count { it != null && it.owner == rc.id } + w.roadVehicles.count { it != null && it.owner == rc.id }
                    "${r.routes.count { it.rail }}r/${r.routes.count { !it.rail }}d ${vehicles}v ${w.companyValue(rc) / 1000}k${if (rc.gone) " GONE" else ""}"
                }
                val status = if (c.gone) "GONE" else if (c.trouble > 0) "trouble ${c.trouble}" else "ok"
                snaps += Snap(Calendar.year(w.startYear, w.day) - 1, c.money, c.loan, w.companyValue(c), status, w.goals.mapIndexed { i, g -> if (g.kind == Goal.CONNECT) yearBest[i] else w.goalProgress(g, 0) }, rivals)
                yearBest.fill(0)
            }

            fun result(log: List<String>, routes: String, secs: Long, player: Player? = null): Result {
                val s = w.scenario
                val goals = w.goals.mapIndexed { i, g ->
                    val toTown = g.kind == Goal.DELIVER && g.industry < 0
                    // Met on the first of a month for the month before, so the best seen may be a day short of it.
                    val most = if (g.status == Goal.MET) maxOf(best[i], g.amount) else best[i]
                    val tried = player == null || i in player.aimTried || g.kind == Goal.COMPANY_VALUE || g.kind == Goal.YEAR_PROFIT
                    GoalEnd(g.kind, g.cargo, toTown, g.amount, g.deadline, most, g.status, g.doneYear, g.kind != Goal.TOWN_POP || g.count[0] > 0, tried)
                }
                return Result(s?.outcome ?: Scenario.OPEN, s?.let { lastDone(w) } ?: 0, 0, goals, snaps, log, routes, secs)
            }
        }

        /** The year the last goal was met or failed. */
        private fun lastDone(w: World) = w.goals.filter { it.status != Goal.OPEN }.maxOfOrNull { it.doneYear } ?: 0

        private fun routes(p: Player): String {
            val a = p.aims.routes
            return "${if (p.restarts > 0) "went under ${p.restarts} times; " else ""}goal routes ${a.count { it.rail }} rail, ${a.count { !it.rail && !it.freight }} bus, ${a.count { !it.rail && it.freight }} road freight; " +
                "other ${p.buses} bus, ${p.railways} rail, ${p.trucks} truck; ${p.vehicles()} vehicles"
        }

        /** Chapter [id] played by the player in [style] (1 to 3), or idle for 0, to its end or till it's won or lost. */
        fun chapter(id: String, style: Int): Result {
            val t0 = System.nanoTime()
            val w = Campaign.play(id, id, id)!!
            val end = Campaign.chapter(id)!!.endYear
            val watch = Watch(w)
            val over = { w.scenario!!.outcome != Scenario.OPEN }
            if (style == 0) {
                while (Calendar.year(w.startYear, w.day) <= end && !over()) idleYear(w, watch, over)
                return watch.result(emptyList(), "idle", (System.nanoTime() - t0) / 1_000_000_000)
            }
            val p = Player(w, aim = true, style = style - 1)
            while (Calendar.year(w.startYear, w.day) <= end && !over()) {
                p.play(1, over) { watch.day() }
                watch.snap()
            }
            return watch.result(p.aimLog, routes(p), (System.nanoTime() - t0) / 1_000_000_000, p)
        }

        /** Steps [w] to the next new year, or till [stop] says. */
        private fun idleYear(w: World, watch: Watch, stop: () -> Boolean) {
            val year = Calendar.year(w.startYear, w.day)
            while (Calendar.year(w.startYear, w.day) == year && !stop()) {
                val before = w.day
                w.step()
                if (w.day != before) watch.day()
            }
            watch.snap()
        }

        /** A new game with goals [set], by the player or [idle], till its goals are done or [years] more calendar years. */
        fun game(set: GoalSet, start: Int, length: Int, seed: Long, idle: Boolean, years: Int): Result {
            val t0 = System.nanoTime()
            val w = World(NewGame(256, 256, seed, start, dayLength = length, goals = set))
            val watch = Watch(w)
            val over = { w.goals.none { it.status == Goal.OPEN } }
            // Each seed played in its own style, so every style is seen without playing each map three times.
            val p = if (idle) null else Player(w, aim = true, style = ((seed - 1) % 3).toInt())
            while (Calendar.year(w.startYear, w.day) < start + years + 1 && !over()) {
                if (p == null) idleYear(w, watch, over)
                else {
                    p.play(1, over) { watch.day() }
                    watch.snap()
                }
            }
            return watch.result(p?.aimLog ?: emptyList(), p?.let { routes(it) } ?: "idle", (System.nanoTime() - t0) / 1_000_000_000, p)
        }

        private fun cargo(c: Int) = Cargo.IDS.getOrElse(c) { "-" }

        private fun goalName(g: GoalEnd) = when (g.kind) {
            Goal.DELIVER -> "deliver ${cargo(g.cargo)} to ${if (g.toTown) "town" else "industry"}"
            else -> KINDS[g.kind]
        }

        private fun state(g: GoalEnd) = when (g.status) {
            Goal.MET -> "met ${g.doneYear}"
            Goal.FAILED -> "failed ${g.doneYear}"
            else -> "open"
        }

        private fun chapterReport(out: StringBuilder, done: List<Pair<Pair<String, Int>, Result>>) {
            out.append("## Campaign chapters\n\n| chapter | player | outcome | year | goals (progress, state) | time |\n|---|---|---|---|---|---|\n")
            for ((key, r) in done.sortedWith(compareBy({ Campaign.CHAPTERS.indexOfFirst { c -> c.id == it.first.first } }, { if (it.first.second == 0) 9 else it.first.second }))) {
                val (id, style) = key
                val outcome = when (r.outcome) { Scenario.WON -> "WON"; Scenario.LOST -> "lost"; else -> "open" }
                val goals = r.goals.joinToString("; ") { "${goalName(it)} ${it.amount}: ${it.percent}%${if (!it.served) " unserved" else ""}${if (!it.tried) " never tried" else ""}, ${state(it)}" }
                out.append("| $id | ${if (style == 0) "idle" else "style $style"} | $outcome | ${if (r.outcome == Scenario.OPEN) "-" else r.year} | $goals | ${r.secs}s |\n")
            }
            out.append("\n")
            for ((key, r) in done) {
                val (id, style) = key
                out.append("### $id, ${if (style == 0) "idle" else "style $style"}\n\n${r.routes}.\n\n")
                if (r.log.isNotEmpty()) out.append("Built for goals: ${r.log.joinToString("; ")}.\n\n")
                out.append("| year | money | loan | value | company | goal progress | rivals (rail/road routes, vehicles, value) |\n|---|---|---|---|---|---|---|\n")
                for (s in r.snaps) {
                    out.append("| ${s.year} | ${s.money} | ${s.loan} | ${s.value} | ${s.status} | ${s.progress.joinToString(" / ")} | ${s.rivals.joinToString("; ")} |\n")
                }
                out.append("\n")
            }
        }

        private fun median(xs: List<Long>) = if (xs.isEmpty()) "-" else xs.sorted()[xs.size / 2].toString()

        private fun gameReport(out: StringBuilder, done: List<Pair<GameKey, Result>>, years: Int) {
            out.append("## Generated goals\n\n256 by 256 maps, default settings, no rivals, up to ${years + 1} calendar years or till every goal is met or failed.\n\n")
            val lengths = done.map { it.first.length }.distinct().sorted()
            out.append("### By goal kind\n\nMet is the share met by the player and idle; progress the median best progress in percent; years the median calendar years from the start to meeting it.\n\n")
            out.append("| goal | day length | goals | player tried | player met | player progress | years to meet | idle met | idle progress |\n|---|---|---|---|---|---|---|---|---|\n")
            fun rows(filter: (GameKey) -> Boolean) = done.filter { filter(it.first) }.flatMap { (k, r) -> r.goals.map { k to it } }
            val names = done.flatMap { d -> d.second.goals.map { goalName(it).replace(Regex("deliver \\S+ to"), "deliver to") } }.distinct().sorted()
            for (name in names) for (l in lengths) {
                fun pick(idle: Boolean) = rows { it.length == l && it.idle == idle }.filter { goalName(it.second).replace(Regex("deliver \\S+ to"), "deliver to") == name }
                val p = pick(false)
                val i = pick(true)
                if (p.isEmpty()) continue
                val met = p.filter { it.second.status == Goal.MET }
                out.append("| $name | $l | ${p.size} | ${p.count { it.second.tried } * 100 / p.size}% | ${met.size * 100 / p.size}% | ${median(p.map { it.second.percent })} | ${median(met.map { (it.second.doneYear - it.first.start).toLong() })} | ")
                out.append("${if (i.isEmpty()) "-" else "${i.count { it.second.status == Goal.MET } * 100 / i.size}%"} | ${median(i.map { it.second.percent })} |\n")
            }
            out.append("\n### By goal kind and start year\n\nMedian amount asked, and the player's met share and median progress, at each day length.\n\n")
            out.append("| goal | start | amount | ${lengths.joinToString(" | ") { "x$it met | x$it progress" }} |\n|---|---|---|${lengths.joinToString("") { "---|---|" }}\n")
            val starts = done.map { it.first.start }.distinct().sorted()
            for (kind in 0 until Goal.KINDS) for (s in starts) {
                val all = rows { it.start == s && !it.idle }.filter { it.second.kind == kind }
                if (all.isEmpty()) continue
                out.append("| ${KINDS[kind]} | $s | ${median(all.map { it.second.amount })} |")
                for (l in lengths) {
                    val p = all.filter { it.first.length == l }
                    if (p.isEmpty()) out.append(" - | - |") else out.append(" ${p.count { it.second.status == Goal.MET } * 100 / p.size}% | ${median(p.map { it.second.percent })} |")
                }
                out.append("\n")
            }
            out.append("\n### Games\n\n| set | start | day length | seed | player | goals (amount: progress, state) | value at end | time |\n|---|---|---|---|---|---|---|---|\n")
            for ((k, r) in done) {
                val goals = r.goals.joinToString("; ") { "${goalName(it)} ${it.amount}: ${it.percent}%${if (!it.served) " unserved" else ""}${if (!it.tried) " never tried" else ""}, ${state(it)}" }
                out.append("| ${k.set.name.lowercase()} | ${k.start} | ${k.length} | ${k.seed} | ${if (k.idle) "idle" else "player"} | $goals | ${r.snaps.lastOrNull()?.value ?: 0} | ${r.secs}s |\n")
            }
            out.append("\n")
            for ((k, r) in done) if (!k.idle && r.log.isNotEmpty()) {
                out.append("- ${k.set.name.lowercase()} ${k.start} x${k.length} seed ${k.seed}: ${r.routes}. ${r.log.joinToString("; ")}\n")
            }
            out.append("\n")
        }
    }
}
