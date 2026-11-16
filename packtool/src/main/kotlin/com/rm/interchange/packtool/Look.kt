package com.rm.interchange.packtool

import com.rm.interchange.sim.Pack
import com.rm.interchange.sim.PackSprites
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** The view edge a building facing world edge [edge] (XM 0, XP 1, YM 2, YP 3) shows when the view is turned [turn] times, as the map works it out. */
fun viewEdge(turn: Int, edge: Int): Int = when (turn and 3) {
    0 -> edge
    1 -> intArrayOf(2, 3, 1, 0)[edge]
    2 -> edge xor 1
    else -> intArrayOf(3, 2, 0, 1)[edge]
}

private const val PANEL_W = 340
private const val PANEL_H = 300
private const val ANCHOR_X = 170
private const val ANCHOR_Y = 130

private const val CARGO_H = 40

/**
 * A look sheet of [pack]: first a line for each cargo it adds or changes, with its colour as a swatch, then a row for
 * each house and each industry part with its own drawing. The first four panels are cells 1 to 4, the edges XM, XP, YM
 * and YP; the last four are a building facing XP as the view turns 0 to 3. Each stands on its tile as the map draws
 * it, at level 0.
 */
fun look(pack: Pack, out: File): Boolean {
    val rows = ArrayList<Pair<String, String>>()
    for (h in pack.houses) h.sprite?.let { rows += "house ${h.id}" to it }
    for (i in pack.industries) for ((part, sheet) in i.parts.orEmpty()) rows += "${i.id} part $part" to sheet
    if (rows.isEmpty() && pack.cargo.isEmpty()) { println("${pack.id} has no cargo and no buildings with their own drawings"); return false }
    val top = CARGO_H * pack.cargo.size
    val image = BufferedImage(PANEL_W * 8, top + PANEL_H * rows.size, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color(0xd8d8d0)
    g.fillRect(0, 0, image.width, image.height)
    for ((k, c) in pack.cargo.withIndex()) {
        val y = k * CARGO_H
        val what = if (c.replaces != null) "changes ${c.replaces}" else "cargo ${c.id}"
        val names = c.names?.entries?.joinToString(" / ") { it.value } ?: ""
        val units = c.units?.get("en")?.let { "in $it" } ?: ""
        val numbers = listOf("rate" to c.rate, "grace" to c.grace, "decay" to c.decay, "pace" to c.pace, "fast" to c.fast, "kg" to c.kg)
            .filter { it.second != null }.joinToString(", ") { "${it.first} ${it.second}" }
        val towns = if (c.towns == true) "towns take it" else ""
        val colour = c.colour
        if (colour != null) {
            g.color = Color(colour)
            g.fillRect(8, y + 6, 28, 28)
            g.color = Color.BLACK
            g.drawRect(8, y + 6, 28, 28)
        }
        g.color = Color.BLACK
        g.drawString(listOf(what, names, units, numbers, towns, colour?.let { "#%06x".format(it) } ?: "").filter { it.isNotEmpty() }.joinToString(", "), 48, y + 25)
        println(listOf(what, names, units, numbers, towns).filter { it.isNotEmpty() }.joinToString(", "))
    }
    for ((r, row) in rows.withIndex()) {
        val png = pack.sprites[row.second] ?: continue
        val packed = PackSprites.pack(JvmPictures.decode(png))
        val sheet = packed.levels[0]
        val cut = BufferedImage(sheet.width, sheet.height, BufferedImage.TYPE_INT_ARGB).also { it.setRGB(0, 0, sheet.width, sheet.height, sheet.argb, 0, sheet.width) }
        for (p in 0 until 8) {
            val cell = if (p < 4) p else viewEdge(p - 4, 1)
            val ox = p * PANEL_W + ANCHOR_X
            val oy = top + r * PANEL_H + ANCHOR_Y
            g.color = Color(0xb8b8a8)
            g.fillPolygon(intArrayOf(ox, ox + 128, ox, ox - 128), intArrayOf(oy, oy + 64, oy + 128, oy + 64), 4)
            g.color = Color(0x707060)
            g.drawPolygon(intArrayOf(ox, ox + 128, ox, ox - 128), intArrayOf(oy, oy + 64, oy + 128, oy + 64), 4)
            val q = packed.rects
            val x = q[cell * 6]
            val y = q[cell * 6 + 1]
            val w = q[cell * 6 + 2]
            val h = q[cell * 6 + 3]
            if (w > 0 && h > 0) g.drawImage(cut.getSubimage(x, y, w, h), ox + q[cell * 6 + 4], oy + q[cell * 6 + 5], null)
            g.color = Color.BLACK
            val edges = arrayOf("XM", "XP", "YM", "YP")
            g.drawString(if (p < 4) "${row.first}: cell ${cell + 1}, ${edges[cell]}" else "${row.first}: facing XP, turn ${p - 4} (cell ${cell + 1})", p * PANEL_W + 8, top + r * PANEL_H + 16)
        }
    }
    g.dispose()
    out.absoluteFile.parentFile.mkdirs()
    ImageIO.write(image, "png", out)
    println("Wrote $out")
    return true
}
