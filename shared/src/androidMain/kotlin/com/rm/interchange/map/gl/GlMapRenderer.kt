package com.rm.interchange.map.gl

import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.util.Log
import com.rm.interchange.map.Atlas
import com.rm.interchange.map.ChunkBaker
import com.rm.interchange.map.LevelSheets
import com.rm.interchange.map.Renderer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val TAG = "Interchange"

/** Bytes a sprite of the second and third passes takes in a region's buffer: anchor x and y, cell, snow tile. */
private const val SPRITE_BYTES = SPRITE_INTS * 4

/** Bytes an instance of the first pass takes, ground or sprite: a [GroundRecord] as it is. */
private const val GROUND_BYTES = GroundRecord.STRIDE * 4

/** A sheet uploaded as a texture array, cut as [pages] says. */
private class Paged(val tex: Int, val pages: AtlasPages)

/** One level's sheets on the GPU: the static sprites, how much each of their pixels faces the sky, the shadows and the ground. */
private class LevelTextures(val from: LevelSheets, val static: Paged, val up: Int, val shadows: Paged, val ground: Int, val bytes: Long) {
    val textures get() = intArrayOf(static.tex, up, shadows.tex, ground)
}

/**
 * Draws the GPU map on the GL thread (OpenGL ES 3.0): the page colour, then [scene]'s regions in drawing order, each
 * pass a few instanced draws of the runs on screen. The first pass draws the ground ([GROUND_FS]) among the track
 * and foundations, as the baker lays them down. Sprites come from the sheets of the level the chunks would bake at
 * for the zoom, placed by cell tables (a row a level), so region buffers are the same at every level; they're
 * uploaded once each and kept while their turn shows. The weather texture takes only the rows that changed.
 *
 * GL thread only, but for [scene]'s shared fields, which it only reads.
 */
internal class GlMapRenderer(private val scene: GlScene, private val page: Int) : GLSurfaceView.Renderer {
    private var spriteProgram = 0
    private var shadowProgram = 0
    private var groundProgram = 0
    private var vao = 0
    private var groundVao = 0
    private var weatherTex = 0
    private var weatherUp: GroundWeather? = null
    private var legsTex = 0
    private var maxTexture = 0
    private var maxLayers = 0
    /** Set once a level wouldn't go on the GPU; the map's gone back to the chunks. */
    private var failed = false
    private val levels = arrayOfNulls<LevelTextures>(Atlas.LEVELS)
    private var staticCells = 0
    private var shadowCells = 0
    private var width = 1
    private var height = 1

    private class Buffer(val region: RegionSprites, val id: Int) {
        /** Where the second pass starts, in bytes. */
        val secondAt = region.firstPass * GROUND_BYTES
    }
    private var buffers = arrayOfNulls<Buffer>(0)
    private var bufferTurn = -1
    private var drawable = arrayOfNulls<RegionSprites>(0)
    private var columns = IntArray(0)
    private val runs = DrawRuns()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context: whatever was made in the last went with it.
        levels.fill(null)
        weatherTex = 0
        weatherUp = null
        legsTex = 0
        buffers = arrayOfNulls(0)
        bufferTurn = -1
        maxTexture = IntArray(1).also { glGetIntegerv(GL_MAX_TEXTURE_SIZE, it, 0) }[0]
        maxLayers = IntArray(1).also { glGetIntegerv(GL_MAX_ARRAY_TEXTURE_LAYERS, it, 0) }[0]
        // Nothing's on the GPU in a new context: the cover stays up till every level's in again.
        scene.lost()
        spriteProgram = program(SPRITE_VS, SPRITE_FS)
        shadowProgram = program(SPRITE_VS, SHADOW_FS)
        groundProgram = program(GROUND_VS, GROUND_FS)
        val ids = IntArray(2)
        glGenVertexArrays(2, ids, 0)
        vao = ids[0]
        groundVao = ids[1]
        glBindVertexArray(vao)
        for (a in 0 until 1) {
            glEnableVertexAttribArray(a)
            glVertexAttribDivisor(a, 1)
        }
        glBindVertexArray(groundVao)
        for (a in 0 until 4) {
            glEnableVertexAttribArray(a)
            glVertexAttribDivisor(a, 1)
        }
        glBindVertexArray(0)
        legsTex = legsTexture()
        staticCells = cellTable(SpriteRecorder.STATIC)
        shadowCells = cellTable(SpriteRecorder.SHADOW)
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_CULL_FACE)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
        glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        glClearColor(((page shr 16) and 255) / 255f, ((page shr 8) and 255) / 255f, (page and 255) / 255f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        val shown = scene.shown ?: return
        val f = shown.frame
        // Clip space from source level pixels: screen = origin + p * scale, then to -1..1 with y up.
        val sx = 2f * f.scale / width
        val sy = -2f * f.scale / height
        val tx = 2f * f.originX / width - 1f
        val ty = 1f - 2f * f.originY / height
        // Every level's uploaded before the cover lifts, so there's never another level to stand in. A frame that
        // uploads draws nothing, under the cover, and asks for the next.
        if (takeLevels(scene.levels)) {
            scene.requestRender()
            return
        }
        val lv = Renderer.levelFor(f.scale * SOURCE_HALF_WIDTH)
        val tex = levels[lv] ?: return
        val st = tex.static
        val sh = tex.shadows
        // Level lv pixels to source level pixels.
        val toSource = SOURCE_HALF_WIDTH / Renderer.LEVELS[lv]
        scene.weather?.let { if (it !== weatherUp) upload(it) }
        if (weatherTex == 0) return
        uploadRegions(shown)
        if (columns.size < f.viewHeight * 2) columns = IntArray(f.viewHeight * 2)
        visibleColumns(f, columns)
        val far = scene.far
        for (i in drawable.indices) {
            val r = drawable[i]
            if (r != null && far != null && far.covers(r, f)) drawable[i] = null
        }
        runs.build(drawable, columns)
        glEnable(GL_BLEND)
        for (pass in 0 until PASSES) {
            val shadow = pass == 1
            val ground = pass == 0
            val prog = if (ground) groundProgram else if (shadow) shadowProgram else spriteProgram
            val sheet = if (shadow) sh else st
            glUseProgram(prog)
            glBindVertexArray(if (ground) groundVao else vao)
            // Sprites are premultiplied and the ground is opaque; a shadow darkens what's under it by how dark it is.
            if (shadow) glBlendFunc(GL_ZERO, GL_ONE_MINUS_SRC_ALPHA) else glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
            glUniform4f(glGetUniformLocation(prog, "uXf"), sx, sy, tx, ty)
            glUniform2f(glGetUniformLocation(prog, "uPage"), sheet.pages.width.toFloat(), sheet.pages.pageHeight.toFloat())
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D_ARRAY, sheet.tex)
            glUniform1i(glGetUniformLocation(prog, "uSheet"), 0)
            glActiveTexture(GL_TEXTURE2)
            glBindTexture(GL_TEXTURE_2D, weatherTex)
            glUniform1i(glGetUniformLocation(prog, "uWeather"), 2)
            glActiveTexture(GL_TEXTURE4)
            glBindTexture(GL_TEXTURE_2D_ARRAY, if (shadow) 0 else tex.up)
            glUniform1i(glGetUniformLocation(prog, "uUp"), 4)
            glActiveTexture(GL_TEXTURE5)
            glBindTexture(GL_TEXTURE_2D, if (shadow) shadowCells else staticCells)
            glUniform1i(glGetUniformLocation(prog, "uCells"), 5)
            glUniform1i(glGetUniformLocation(prog, "uCellRows"), cellRows(if (shadow) SpriteRecorder.SHADOW else SpriteRecorder.STATIC))
            glActiveTexture(GL_TEXTURE0)
            glUniform1i(glGetUniformLocation(prog, "uLevel"), lv)
            glUniform1f(glGetUniformLocation(prog, "uToSource"), toSource)
            if (ground) groundUniforms(f, tex.ground)
            for (k in runs.passStart[pass] until runs.passStart[pass + 1]) {
                val b = buffers[runs.region(k)] ?: continue
                glBindBuffer(GL_ARRAY_BUFFER, b.id)
                if (ground) {
                    val at = runs.first(k) * GROUND_BYTES
                    glVertexAttribIPointer(0, 4, GL_UNSIGNED_INT, GROUND_BYTES, at)
                    glVertexAttribIPointer(1, 2, GL_INT, GROUND_BYTES, at + 16)
                    glVertexAttribPointer(2, 4, GL_FLOAT, false, GROUND_BYTES, at + 24)
                    glVertexAttribPointer(3, 4, GL_FLOAT, false, GROUND_BYTES, at + 40)
                } else {
                    val at = b.secondAt + (runs.first(k) - b.region.firstPass) * SPRITE_BYTES
                    glVertexAttribIPointer(0, 4, GL_INT, SPRITE_BYTES, at)
                }
                glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, runs.count(k))
            }
        }
        glBindVertexArray(0)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glDisable(GL_BLEND)
    }

    /** The ground shader's textures and uniforms for frame [f]. */
    private fun groundUniforms(f: GlFrame, groundTex: Int) {
        val p = groundProgram
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D_ARRAY, groundTex)
        glUniform1i(glGetUniformLocation(p, "uGround"), 1)
        glActiveTexture(GL_TEXTURE3)
        glBindTexture(GL_TEXTURE_2D, legsTex)
        glUniform1i(glGetUniformLocation(p, "uLegs"), 3)
        glActiveTexture(GL_TEXTURE0)
        val w = weatherUp!!
        glUniform3i(glGetUniformLocation(p, "uMap"), w.width, w.height, f.turn)
        val legs = ChunkBaker.TAXI_LEGS
        glUniform1iv(glGetUniformLocation(p, "uLegCount"), legs.size, IntArray(legs.size) { legs[it].size / 4 }, 0)
        val grades = ChunkBaker.GRADES[scene.look]
        val g = FloatArray(4 * GRADE_KINDS)
        for (k in 0 until minOf(GRADE_KINDS, grades.size)) {
            val c = grades[k]
            g[4 * k] = ((c shr 16) and 255) / 255f
            g[4 * k + 1] = ((c shr 8) and 255) / 255f
            g[4 * k + 2] = (c and 255) / 255f
            g[4 * k + 3] = (c ushr 24) / 255f
        }
        glUniform4fv(glGetUniformLocation(p, "uGrade"), GRADE_KINDS, g, 0)
    }

    /** Every airport kind's taxi legs as a float texture, a row a kind, a texel a leg. */
    private fun legsTexture(): Int {
        val legs = ChunkBaker.TAXI_LEGS
        val w = maxOf(1, legs.maxOf { it.size / 4 })
        val data = FloatArray(w * legs.size * 4)
        for (k in legs.indices) legs[k].copyInto(data, k * w * 4)
        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(data)
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glBindTexture(GL_TEXTURE_2D, ids[0])
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32F, w, legs.size)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, legs.size, GL_RGBA, GL_FLOAT, buf)
        nearest(GL_TEXTURE_2D)
        glBindTexture(GL_TEXTURE_2D, 0)
        return ids[0]
    }

    /** Takes [w] into the weather texture: all of it the first time or at a new size, else the rows changed since the last. */
    private fun upload(w: GroundWeather) {
        val last = weatherUp
        val cw = w.width + 1
        val ch = w.height + 1
        val whole = weatherTex == 0 || last == null || last.width != w.width || last.height != w.height
        if (whole) {
            if (weatherTex != 0) glDeleteTextures(1, intArrayOf(weatherTex), 0)
            val ids = IntArray(1)
            glGenTextures(1, ids, 0)
            weatherTex = ids[0]
            glBindTexture(GL_TEXTURE_2D, weatherTex)
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8UI, cw, ch)
            nearest(GL_TEXTURE_2D)
        } else glBindTexture(GL_TEXTURE_2D, weatherTex)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        val since = if (whole) Int.MIN_VALUE else last!!.version
        var y = 0
        var rows = 0
        while (y < ch) {
            if (w.rowVersions[y] <= since) { y++; continue }
            var e = y
            while (e < ch && w.rowVersions[e] > since) e++
            val bytes = cw * GroundWeather.TEXEL
            val buf = ByteBuffer.allocateDirect((e - y) * bytes)
            buf.put(w.texels, y * bytes, (e - y) * bytes).flip()
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, y, cw, e - y, GL_RGBA_INTEGER, GL_UNSIGNED_BYTE, buf)
            rows += e - y
            y = e
        }
        glBindTexture(GL_TEXTURE_2D, 0)
        weatherUp = w
        Log.i(TAG, "gl weather v${w.version} rows=$rows of $ch")
    }

    private fun nearest(target: Int) {
        glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(target, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    }

    /** Uploads [shown]'s regions not yet in a buffer, lets go of other turns', and lists those ready to draw in [drawable]. */
    private fun uploadRegions(shown: GlShown) {
        val turn = shown.frame.turn
        if (turn != bufferTurn || buffers.size != shown.regions.size) {
            freeBuffers()
            buffers = arrayOfNulls(shown.regions.size)
            bufferTurn = turn
        }
        if (drawable.size != shown.regions.size) drawable = arrayOfNulls(shown.regions.size)
        for (i in shown.regions.indices) {
            val r = shown.regions[i]
            val b = buffers[i]
            if (r != null && b?.region !== r) {
                if (b != null) glDeleteBuffers(1, intArrayOf(b.id), 0)
                buffers[i] = Buffer(r, upload(r))
            }
            drawable[i] = if (r != null && buffers[i]?.region === r) r else null
        }
    }

    private fun upload(r: RegionSprites): Int {
        val ints = packRegion(r)
        val bytes = ByteBuffer.allocateDirect(maxOf(1, ints.size) * 4).order(ByteOrder.nativeOrder())
        bytes.asIntBuffer().put(ints)
        bytes.position(0)
        val ids = IntArray(1)
        glGenBuffers(1, ids, 0)
        glBindBuffer(GL_ARRAY_BUFFER, ids[0])
        glBufferData(GL_ARRAY_BUFFER, bytes.capacity(), bytes, GL_STATIC_DRAW)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        return ids[0]
    }

    /**
     * Uploads the levels [from] has that aren't on the GPU yet, lets go of those it's dropped and tells [scene] they're
     * in, or that they wouldn't go. Returns whether anything changed.
     */
    private fun takeLevels(from: Array<LevelSheets?>): Boolean {
        if (failed) return false
        var changed = false
        while (glGetError() != GL_NO_ERROR) Unit
        for (l in levels.indices) {
            val want = from.getOrNull(l)
            val have = levels[l]
            if (have?.from === want) continue
            changed = true
            if (have != null) {
                glDeleteTextures(4, have.textures, 0)
                levels[l] = null
            }
            if (want != null) {
                val t = try { upload(l, want) } catch (e: IllegalArgumentException) { Log.e(TAG, "gl level $l won't fit: ${e.message}"); null }
                val error = glGetError()
                if (t == null || error != GL_NO_ERROR) {
                    if (error != GL_NO_ERROR) Log.e(TAG, "gl level $l upload failed, GL error 0x${error.toString(16)}")
                    if (t != null) glDeleteTextures(4, t.textures, 0)
                    failed = true
                    scene.uploadFailed()
                    return true
                }
                levels[l] = t
            }
            Log.i(TAG, "gl level $l ${if (want != null) "up" else "dropped"}, level textures ${levels.sumOf { it?.bytes ?: 0L } / 1_000_000} MB")
        }
        if (changed) scene.uploaded(from)
        return changed
    }

    /**
     * Level [level]'s static sheet and shadows as texture arrays split to the GPU's largest texture, the static
     * sheet's sky-facing bits (the low two of blue) taken out into a texture of their own, its ground and its rows of
     * the cell tables. No mipmaps on the sprites: each level is drawn from half to twice its size, as a chunk is.
     */
    private fun upload(level: Int, sheets: LevelSheets): LevelTextures {
        val t0 = System.nanoTime()
        val sp = AtlasPages(sheets.staticWidth, sheets.static.size / sheets.staticWidth, AtlasPages.tallestStatic(level), maxTexture)
        val hp = AtlasPages(sheets.shadowWidth, sheets.shadows.size / sheets.shadowWidth, AtlasPages.tallestShadow(level), maxTexture)
        require(sp.layers <= maxLayers && hp.layers <= maxLayers) { "${maxOf(sp.layers, hp.layers)} layers, up to $maxLayers" }
        val static = Paged(pagedTexture(sp, GL_RGBA8) { y0, rows, buf -> premultiplied(sheets.static, sheets.staticWidth, y0, rows, buf) }, sp)
        val up = pagedTexture(sp, GL_R8) { y0, rows, buf -> skyBits(sheets.static, sheets.staticWidth, y0, rows, buf) }
        val shadows = Paged(pagedTexture(hp, GL_R8) { y0, rows, buf -> buf.put(sheets.shadows, y0 * sheets.shadowWidth, rows * sheets.shadowWidth) }, hp)
        cellRow(staticCells, SpriteRecorder.STATIC, level, sp)
        cellRow(shadowCells, SpriteRecorder.SHADOW, level, hp)
        val ground = groundTexture(sheets.ground)
        // The ground with its mipmaps, a third again.
        val bytes = sp.width.toLong() * sp.pageHeight * sp.layers * 5 + hp.width.toLong() * hp.pageHeight * hp.layers + sheets.ground.size * 16L / 3
        Log.i(TAG, "gl sheets L$level static ${sp.width}x${sp.pageHeight}x${sp.layers} shadows ${hp.width}x${hp.pageHeight}x${hp.layers} " +
            "MB=${bytes / 1_000_000} maxTex=$maxTexture ms=${(System.nanoTime() - t0) / 1_000_000}")
        return LevelTextures(sheets, static, up, shadows, ground, bytes)
    }

    /** An empty cell table for [sheet], [CELL_INTS] / 4 texels a cell, [CELLS_ACROSS] cells a row and [cellRows] rows a level. */
    private fun cellTable(sheet: Int): Int {
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glBindTexture(GL_TEXTURE_2D, ids[0])
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32I, CELLS_ACROSS * CELL_INTS / 4, cellRows(sheet) * Atlas.LEVELS)
        nearest(GL_TEXTURE_2D)
        glBindTexture(GL_TEXTURE_2D, 0)
        return ids[0]
    }

    /** Fills [level]'s rows of cell table [tex] from [cellRects]. */
    private fun cellRow(tex: Int, sheet: Int, level: Int, pages: AtlasPages) {
        val rows = cellRows(sheet)
        val rects = cellRects(sheet, level, pages).copyOf(rows * CELLS_ACROSS * CELL_INTS)
        val buf = ByteBuffer.allocateDirect(rects.size * 4).order(ByteOrder.nativeOrder())
        buf.asIntBuffer().put(rects)
        glBindTexture(GL_TEXTURE_2D, tex)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4)
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, level * rows, CELLS_ACROSS * CELL_INTS / 4, rows, GL_RGBA_INTEGER, GL_INT, buf)
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    /** The ground sheet, its kinds and noise square and one after another, as a texture array a kind a layer, repeating, with mipmaps. */
    private fun groundTexture(ground: IntArray): Int {
        val size = groundSize(ground)
        val layers = ground.size / (size * size)
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glBindTexture(GL_TEXTURE_2D_ARRAY, ids[0])
        val levels = 1 + (31 - Integer.numberOfLeadingZeros(size))
        glTexStorage3D(GL_TEXTURE_2D_ARRAY, levels, GL_RGBA8, size, size, layers)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        val out = ByteArray(size * size * 4)
        val buf = ByteBuffer.allocateDirect(out.size)
        for (layer in 0 until layers) {
            var k = 0
            for (i in layer * size * size until (layer + 1) * size * size) {
                val p = ground[i]
                out[k] = (p shr 16).toByte()
                out[k + 1] = (p shr 8).toByte()
                out[k + 2] = p.toByte()
                out[k + 3] = -1
                k += 4
            }
            buf.clear()
            buf.put(out).flip()
            glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, size, size, 1, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        }
        glGenerateMipmap(GL_TEXTURE_2D_ARRAY)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT)
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0)
        return ids[0]
    }

    /** A texture array for [pages], each layer filled a strip of rows at a time by [fill], which puts rows from sheet row y0 into the buffer. */
    private fun pagedTexture(pages: AtlasPages, format: Int, fill: (y0: Int, rows: Int, buf: ByteBuffer) -> Unit): Int {
        val texel = if (format == GL_R8) 1 else 4
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glBindTexture(GL_TEXTURE_2D_ARRAY, ids[0])
        glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1, format, pages.width, pages.pageHeight, pages.layers)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        val buf = ByteBuffer.allocateDirect(pages.width * STRIP_ROWS * texel)
        for (layer in 0 until pages.layers) {
            val (y0, rows) = pages.rowsOf(layer)
            var y = 0
            while (y < pages.pageHeight) {
                val n = minOf(STRIP_ROWS, pages.pageHeight - y)
                buf.clear()
                val real = (rows - y).coerceIn(0, n)
                if (real > 0) fill(y0 + y, real, buf)
                // Rows past the sheet's end are clear.
                val clear = n * pages.width * texel - buf.position()
                if (clear > 0) buf.put(ByteArray(clear))
                buf.flip()
                glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, y, layer, pages.width, n, 1, if (format == GL_R8) GL_RED else GL_RGBA, GL_UNSIGNED_BYTE, buf)
                y += n
            }
        }
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0)
        return ids[0]
    }

    /** Rows [y0] until y0 + [rows] of an ARGB sheet [w] wide into [buf] as premultiplied RGBA bytes, the sky-facing bits taken out of blue. */
    private fun premultiplied(src: IntArray, w: Int, y0: Int, rows: Int, buf: ByteBuffer) {
        val n = rows * w
        if (strip.size < n * 4) strip = ByteArray(n * 4)
        val out = strip
        var k = 0
        for (i in y0 * w until y0 * w + n) {
            val p = src[i]
            val a = p ushr 24
            out[k] = (((p shr 16) and 255) * a / 255).toByte()
            out[k + 1] = (((p shr 8) and 255) * a / 255).toByte()
            out[k + 2] = ((p and 252) * a / 255).toByte()
            out[k + 3] = a.toByte()
            k += 4
        }
        buf.put(out, 0, n * 4)
    }

    /** The same rows' sky-facing bits (0 to 3) as bytes 0 to 255, for blending as the sheet is. */
    private fun skyBits(src: IntArray, w: Int, y0: Int, rows: Int, buf: ByteBuffer) {
        val n = rows * w
        if (strip.size < n) strip = ByteArray(n)
        val out = strip
        var k = 0
        for (i in y0 * w until y0 * w + n) {
            val p = src[i]
            out[k++] = (if (p ushr 24 == 0) 0 else (p and 3) * 85).toByte()
        }
        buf.put(out, 0, n)
    }

    private var strip = ByteArray(0)

    private fun freeBuffers() {
        for (b in buffers) if (b != null) glDeleteBuffers(1, intArrayOf(b.id), 0)
        buffers = arrayOfNulls(buffers.size)
    }

    /** Frees every texture, buffer and program, as the surface goes. */
    fun release() {
        freeBuffers()
        for (l in levels) if (l != null) glDeleteTextures(4, l.textures, 0)
        levels.fill(null)
        for (t in intArrayOf(weatherTex, legsTex, staticCells, shadowCells)) if (t != 0) glDeleteTextures(1, intArrayOf(t), 0)
        staticCells = 0
        shadowCells = 0
        weatherTex = 0
        weatherUp = null
        legsTex = 0
        for (a in intArrayOf(vao, groundVao)) if (a != 0) glDeleteVertexArrays(1, intArrayOf(a), 0)
        for (p in intArrayOf(spriteProgram, shadowProgram, groundProgram)) if (p != 0) glDeleteProgram(p)
        vao = 0
        groundVao = 0
        spriteProgram = 0
        shadowProgram = 0
        groundProgram = 0
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = glCreateShader(type)
            glShaderSource(s, src)
            glCompileShader(s)
            val ok = IntArray(1)
            glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e(TAG, "gl shader: ${glGetShaderInfoLog(s)}")
            return s
        }
        val p = glCreateProgram()
        val v = shader(GL_VERTEX_SHADER, vs)
        val f = shader(GL_FRAGMENT_SHADER, fs)
        glAttachShader(p, v)
        glAttachShader(p, f)
        glLinkProgram(p)
        glDeleteShader(v)
        glDeleteShader(f)
        val ok = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "gl link: ${glGetProgramInfoLog(p)}")
        return p
    }

    private companion object {
        /** Rows of a sheet converted and uploaded at a time, 1 MB at 1024 wide. */
        const val STRIP_ROWS = 256
        /** Ground kinds with a grass colour through the year, and some to spare: the shader's uGrade. */
        const val GRADE_KINDS = 8
    }
}

/** The second and third passes' sprites: anchor x and y, cell and snow tile, placed by [SPRITE_PLACE]. */
private val SPRITE_VS = """#version 300 es
precision highp float;
precision highp int;
layout(location = 0) in ivec4 aI;
uniform vec4 uXf;
uniform vec2 uPage;
uniform highp usampler2D uWeather;
$SPRITE_PLACE
out vec3 vUv;
flat out vec4 vEdge;
flat out float vSnow;
void main() {
    vec2 o = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    vec2 p = spriteAt(aI.z, aI.xy, o, vUv, vEdge);
    vSnow = snowCover(aI.w);
    gl_Position = vec4(p * uXf.xy + uXf.zw, 0.0, 1.0);
}
"""

private val SPRITE_FS = """#version 300 es
precision mediump float;
uniform mediump sampler2DArray uSheet;
in highp vec3 vUv;
flat in highp vec4 vEdge;
flat in float vSnow;
out vec4 frag;
$STATIC_TEXEL
void main() {
    frag = staticTexel(vUv, vEdge, vSnow);
}
"""

/** A shadow darkens what's under it by how dark it is, the blend multiplying as shadeOn does. */
private const val SHADOW_FS = """#version 300 es
precision mediump float;
uniform mediump sampler2DArray uSheet;
in highp vec3 vUv;
flat in highp vec4 vEdge;
out vec4 frag;
void main() {
    frag = vec4(0.0, 0.0, 0.0, texture(uSheet, vec3(clamp(vUv.xy, vEdge.xy, vEdge.zw), vUv.z)).r);
}
"""
