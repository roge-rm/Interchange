package com.rm.interchange.sim

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InsigniaTest {
    private fun game(seed: Long = 7, rivals: Int = 3) =
        World(NewGame(128, 128, seed, 1930, Region.MIXED, towns = 3, industries = 0, rivals = rivals))

    @Test
    fun thePresetsAreWholeAndDifferent() {
        val presets = Insignia.presets
        assertTrue(presets.size >= MAX_COMPANIES)
        for (p in presets) {
            assertTrue(Insignia.valid(p))
            assertTrue(Insignia.unpack(p).count { it != Insignia.CLEAR } > 30)
        }
        assertEquals(presets.size, presets.map { it.toList() }.toSet().size)
    }

    @Test
    fun packingKeepsEveryPixel() {
        val pixels = IntArray(Insignia.PIXELS) { it % Insignia.COLOURS }
        assertContentEquals(pixels, Insignia.unpack(Insignia.pack(pixels)))
    }

    @Test
    fun everyCompanyStartsWithAPresetOfItsOwn() {
        for (seed in 1L..6L) {
            val w = game(seed, rivals = 5)
            for (id in w.companies.size until MAX_COMPANIES) w.apply(Command.NewCompany(id, ""))
            assertEquals(MAX_COMPANIES, w.companies.size)
            assertTrue(w.companies.all { c -> Insignia.presets.any { it.contentEquals(c.insignia) } })
            assertEquals(w.companies.size, w.companies.map { it.insignia.toList() }.toSet().size, "seed $seed")
            // The same game gives the same.
            val again = game(seed, rivals = 5)
            for (c in again.companies) assertContentEquals(w.companies[c.id].insignia, c.insignia)
        }
    }

    @Test
    fun settingKeepsToTheRules() {
        val w = game()
        val star = Insignia.presets.first { !it.contentEquals(w.companies[0].insignia) }
        val rival = w.companies[1].insignia.copyOf()
        w.apply(Command.SetInsignia(0, star))
        assertContentEquals(star, w.companies[0].insignia)
        assertContentEquals(rival, w.companies[1].insignia)
        // The wrong size, or a pixel past the palette, is left undone.
        w.apply(Command.SetInsignia(0, ByteArray(Insignia.BYTES - 1)))
        w.apply(Command.SetInsignia(0, ByteArray(Insignia.BYTES + 1)))
        w.apply(Command.SetInsignia(0, ByteArray(Insignia.BYTES) { 0x08 }))
        w.apply(Command.SetInsignia(0, ByteArray(Insignia.BYTES) { 0x80.toByte() }))
        assertContentEquals(star, w.companies[0].insignia)
        // Changing the bytes given after doesn't change the company's.
        val given = ByteArray(Insignia.BYTES) { 0x11 }
        w.apply(Command.SetInsignia(0, given))
        given.fill(0)
        assertTrue(w.companies[0].insignia.all { it.toInt() == 0x11 })
        // Two companies may have the same.
        w.apply(Command.SetInsignia(1, w.companies[0].insignia))
        assertContentEquals(w.companies[0].insignia, w.companies[1].insignia)
    }

    @Test
    fun aCompanyGoneCantSetOne() {
        val w = game()
        val before = w.companies[1].insignia.copyOf()
        w.companies[1].gone = true
        w.apply(Command.SetInsignia(1, ByteArray(Insignia.BYTES)))
        assertContentEquals(before, w.companies[1].insignia)
    }

    @Test
    fun anInsigniaIsInTheChecksumSavedAndSent() {
        val w = game()
        val before = w.checksum()
        val pixels = Insignia.unpack(w.companies[0].insignia).also { it[0] = Insignia.GOLD }
        w.apply(Command.SetInsignia(0, Insignia.pack(pixels)))
        assertNotEquals(before, w.checksum())
        val copy = Save.read(Save.write(w))
        for (c in w.companies) assertContentEquals(c.insignia, copy.companies[c.id].insignia)
        assertEquals(w.checksum(), copy.checksum())
        val c = Command.SetInsignia(0, Insignia.pack(pixels))
        assertEquals(c, Codec.decode(Codec.encode(c)))
    }

    @Test
    fun anOldSaveGivesEachCompanyOne() {
        val w = game(rivals = 4)
        for (c in w.companies) c.insignia = ByteArray(Insignia.BYTES)
        val old = Save.write(w, version = 26)
        val a = Save.read(old)
        val b = Save.read(old)
        assertTrue(a.companies.all { c -> Insignia.presets.any { it.contentEquals(c.insignia) } })
        assertEquals(a.companies.size, a.companies.map { it.insignia.toList() }.toSet().size)
        for (c in a.companies) assertContentEquals(c.insignia, b.companies[c.id].insignia)
        assertEquals(a.checksum(), b.checksum())
    }

    @Test
    fun aBadInsigniaInASaveIsRefused() {
        val w = game()
        w.companies[0].insignia = ByteArray(Insignia.BYTES) { 0x0f }
        assertFalse(runCatching { Save.read(Save.write(w)) }.isSuccess)
    }

    /**
     * Every preset at 8 times its size, on a light and a dark ground, in two companies' colours, to
     * build/insignia/sheet.png, for looking over the drawings.
     */
    @Test
    fun contactSheet() {
        val scale = 8
        val cell = Insignia.SIZE * scale + 16
        val grounds = intArrayOf(0xECECE6, 0x282A30)
        val colours = intArrayOf(0x2E6FB0, 0xE0B020)
        val across = 6
        val presets = Insignia.presets
        val rows = (presets.size + across - 1) / across
        val image = BufferedImage(across * cell * 4, rows * cell, BufferedImage.TYPE_INT_RGB)
        for ((i, p) in presets.withIndex()) {
            val pixels = Insignia.unpack(p)
            var k = 0
            for (colour in colours) for (ground in grounds) {
                val ox = ((i % across) * 4 + k) * cell
                val oy = (i / across) * cell
                k++
                val palette = palette(colour)
                for (y in 0 until cell) for (x in 0 until cell) image.setRGB(ox + x, oy + y, ground)
                for (n in pixels.indices) {
                    if (pixels[n] == Insignia.CLEAR) continue
                    val px = ox + 8 + n % Insignia.SIZE * scale
                    val py = oy + 8 + n / Insignia.SIZE * scale
                    for (y in 0 until scale) for (x in 0 until scale) image.setRGB(px + x, py + y, palette[pixels[n]])
                }
            }
        }
        val file = File("build/insignia/sheet.png")
        file.parentFile.mkdirs()
        ImageIO.write(image, "png", file)
        assertTrue(file.length() > 0)
    }

    /** The palette as the game draws it, for [colour]: see the shared module's insigniaPalette. */
    private fun palette(colour: Int): IntArray {
        fun mix(to: Int, t: Float): Int {
            var out = 0
            for (s in intArrayOf(16, 8, 0)) {
                val a = colour shr s and 255
                val b = to shr s and 255
                out = out or (Math.round(a + (b - a) * t) shl s)
            }
            return out
        }
        return intArrayOf(0, colour, mix(0xFFFFFF, 0.45f), mix(0, 0.5f), 0xF4F4F0, 0x1A1A1A, 0x8A8F96, 0xE8B422)
    }
}
