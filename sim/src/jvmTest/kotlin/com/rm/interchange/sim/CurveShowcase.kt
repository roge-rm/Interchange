package com.rm.interchange.sim

import java.io.File
import kotlin.test.Test

/**
 * Writes a saved game of curved track to look at (docs/CURVES_PLAN.md), curves_showcase (.ichg with its .info), into the
 * folder -Pinterchange.curveShowcase names. Skipped without it. Flat bare land with the look test's scenes from
 * curves_reference.txt laid out in rows, each with straights on past its straights' open ends: a turn, an ease, a turnout, a
 * crossover, a wye, a straight into two diagonal lines, a corner, a ladder (fallback), the turnout with a signal
 * on its switch tile (fallback), and the ease with a slope (just the scene).
 */
class CurveShowcase {
    @Test
    fun write() {
        val out = System.getProperty("interchange.curveShowcase")?.let { File(it) } ?: return
        out.mkdirs()
        val w = World(NewGame(64, 64, 5, 1962, Region.MIXED, sea = 0, hills = 0, trees = 0, towns = 0, industries = 0, collisions = false))
        for (cy in 0..w.height) for (cx in 0..w.width) w.corners[w.corner(cx, cy)] = 3
        w.landShaped()
        for (i in w.trees.indices) w.trees[i] = 0
        w.shoreChanged()
        val scenes = LinkedHashMap<String, MutableMap<Pair<Int, Int>, Int>>()
        var name = ""
        for (line in CurveShowcase::class.java.getResourceAsStream("/curves_reference.txt")!!.bufferedReader().readLines()) {
            val p = line.split(" ")
            if (p[0] == "#") name = p[1].also { scenes[it] = HashMap() }
            if (p[0] == "T" && !name.startsWith("random")) scenes.getValue(name)[p[1].toInt() to p[2].toInt()] = p[3].toInt()
        }
        val shown = listOf("turn", "ease", "turnout", "crossover", "wye", "wye_diagonal", "corner", "ladder", "turnout_signal")
        val notes = StringBuilder()
        val placed = HashMap<String, Map<Pair<Int, Int>, Int>>()
        val offsets = HashMap<String, Pair<Int, Int>>()
        for ((k, s) in shown.withIndex()) {
            val tiles = scenes.getValue(s)
            val minX = tiles.keys.minOf { it.first }
            val minY = tiles.keys.minOf { it.second }
            val ox = 6 + (k % 3) * 18 - minX
            val oy = 6 + (k / 3) * 18 - minY
            val put = HashMap<Pair<Int, Int>, Int>()
            for ((t, m) in tiles) put[t.first + ox to t.second + oy] = m
            // Straights on past each straight's open end, three tiles.
            for ((t, m) in put.entries.toList()) for (p in listOf(Track.X, Track.Y)) if (m and p != 0) for (e in listOf(Track.edges(p) and 3, Track.edges(p) shr 2)) {
                var x = t.first + Track.dx(e)
                var y = t.second + Track.dy(e)
                if ((put[x to y] ?: 0) != 0) continue
                repeat(3) {
                    if ((put[x to y] ?: 0) == 0) put[x to y] = Track.straight(e)
                    x += Track.dx(e)
                    y += Track.dy(e)
                }
            }
            for ((t, m) in put) {
                val i = w.index(t.first, t.second)
                w.track[i] = (w.track[i].toInt() or m).toByte()
                w.owner[i] = 0
                w.touched(t.first, t.second)
            }
            placed[s] = put
            offsets[s] = ox to oy
            notes.append("$s at ${minX + ox},${minY + oy}\n")
        }
        // The fallback turnout's signal, on the through straight of its switch tile, scene tile (1, 0).
        w.signals[pieceKey(w.index(6 + 2 * 18 + 1, 6 + 2 * 18), Track.X)] = Signals.pack(Track.XP, Signals.TWO_WAY)
        // Wires on the turn, ease, turnout and corner; signals on the crossover and both wyes, one on each diagonal piece;
        // reserved track on the crossover, a wye, the ladder and the turnout's branch; a level crossing beside the turn and the ease's straight.
        for (s in listOf("turn", "ease", "turnout", "corner")) for ((t, m) in placed.getValue(s)) w.wires[w.index(t.first, t.second)] = m.toByte()
        for (s in listOf("crossover", "wye", "wye_diagonal")) for ((t, m) in placed.getValue(s)) for (p in listOf(Track.N, Track.E, Track.S, Track.W)) {
            if (m and p != 0) w.signals[pieceKey(w.index(t.first, t.second), p)] = Signals.pack(Track.edges(p) and 3, Signals.TWO_WAY)
        }
        for (s in listOf("crossover", "wye", "ladder")) for ((t, m) in placed.getValue(s)) for (p in Track.PIECES) {
            if (m and p != 0) w.reserved[pieceKey(w.index(t.first, t.second), p)] = 0
        }
        // The turnout's reserved path takes the branch: its diagonals, and the straights beside them but not the through piece on the switch tile.
        for ((t, m) in placed.getValue("turnout")) for (p in Track.PIECES) {
            val diagonal = p != Track.X && p != Track.Y
            if (m and p != 0 && (diagonal || Integer.bitCount(m) == 1)) w.reserved[pieceKey(w.index(t.first, t.second), p)] = 0
        }
        for ((s, at) in listOf("turn" to (1 to 0), "ease" to (2 to 2))) {
            val (ox, oy) = offsets.getValue(s)
            w.road[w.index(at.first + ox, at.second + oy)] = (roadBit(Track.YM) or roadBit(Track.YP)).toByte()
        }
        w.networkChanged()
        // On into July, for bare ground.
        while (Calendar.month(1962, w.day) < 6) w.step()
        val g = w.settings
        // And as save version 49 for the straight track build before curves, to look at side by side.
        for ((file, version) in listOf("curves_showcase" to Save.VERSION, "curves_showcase_49" to 49)) {
            File(out, "$file.ichg").writeBytes(Save.write(w, version))
            File(out, "$file.info").writeText("day=${w.day}\nstartYear=${g.startYear}\nmoney=${w.companies[0].money}\nwidth=${g.width}\nheight=${g.height}\nregion=${g.region.ordinal}\n")
        }
        File(out, "curves_showcase.txt").writeText(notes.toString())
    }
}
