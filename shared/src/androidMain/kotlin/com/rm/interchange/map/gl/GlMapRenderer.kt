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

    private var liveProgram = 0
    private var liveVao = 0
    private var liveBuffer = 0
    /** Each level's live pages, as textures. */
    private val livePages = Array(Atlas.LEVELS) { ArrayList<Int>() }
    /** The uploads taken into this context's pages that the main thread hasn't answered yet. */
    private val liveDone = HashSet<LiveUpload>()
    /** Counts contexts, so batches and uploads placed for an earlier one's pages are left alone. */
    private var context = 0
    /** The batch in [liveBuffer], and its draws: first instance, count and the pages bound to its units. */
    private var liveUp: LiveBatch? = null
    private var liveDraws = IntArray(0)
    private var liveDrawCount = 0
    private var liveBytes = ByteBuffer.allocateDirect(0)
    private var liveFloats = FloatArray(0)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context: whatever was made in the last went with it.
        levels.fill(null)
        weatherTex = 0
        weatherUp = null
        legsTex = 0
        buffers = arrayOfNulls(0)
        bufferTurn = -1
        for (p in livePages) p.clear()
        liveDone.clear()
        liveUp = null
        maxTexture = IntArray(1).also { glGetIntegerv(GL_MAX_TEXTURE_SIZE, it, 0) }[0]
        maxLayers = IntArray(1).also { glGetIntegerv(GL_MAX_ARRAY_TEXTURE_LAYERS, it, 0) }[0]
        // Nothing's on the GPU in a new context: the cover stays up till every level's in again.
        context++
        scene.lost(context)
        spriteProgram = program(SPRITE_VS, SPRITE_FS)
        shadowProgram = program(SPRITE_VS, SHADOW_FS)
        groundProgram = program(GROUND_VS, GROUND_FS)
        liveProgram = program(LIVE_VS, LIVE_FS)
        val ids = IntArray(3)
        glGenVertexArrays(3, ids, 0)
        vao = ids[0]
        groundVao = ids[1]
        liveVao = ids[2]
        glGenBuffers(1, ids, 0)
        liveBuffer = ids[0]
        glBindVertexArray(liveVao)
        glBindBuffer(GL_ARRAY_BUFFER, liveBuffer)
        for (a in 0 until 4) {
            glEnableVertexAttribArray(a)
            glVertexAttribDivisor(a, 1)
            glVertexAttribPointer(a, 4, GL_FLOAT, false, LIVE_FLOATS * 4, a * 16)
        }
        glBindBuffer(GL_ARRAY_BUFFER, 0)
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
        glClearDepthf(0f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
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
        takeLive()
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
        shown.live?.let { drawLive(it) }
        glBindVertexArray(0)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glDisable(GL_BLEND)
    }

    /**
     * Draws what moves over the static map, in its order: the buildings' columns into the depth buffer only, keeping
     * the nearest at each pixel, then the instances, a cut one showing only where it's nearer than what's there.
     */
    private fun drawLive(b: LiveBatch) {
        if (b.generation != context) return
        if (b !== liveUp) {
            uploadLive(b)
            scene.liveTaken = b.seq
        }
        if (b.covers + b.count == 0) return
        val p = liveProgram
        glUseProgram(p)
        glBindVertexArray(liveVao)
        glUniform2f(glGetUniformLocation(p, "uScreen"), width.toFloat(), height.toFloat())
        glUniform1f(glGetUniformLocation(p, "uSide"), livePageSide(b.level).toFloat())
        for (u in 0 until LIVE_UNITS) glUniform1i(glGetUniformLocation(p, "uP$u"), u)
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_GREATER)
        if (b.covers > 0) {
            livePointers(0)
            glColorMask(false, false, false, false)
            glDepthMask(true)
            glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, b.covers)
            glColorMask(true, true, true, true)
        }
        glDepthMask(false)
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        val pages = livePages[b.level]
        for (d in 0 until liveDrawCount) {
            val o = d * (2 + LIVE_UNITS)
            for (u in 0 until LIVE_UNITS) {
                glActiveTexture(GL_TEXTURE0 + u)
                glBindTexture(GL_TEXTURE_2D, pages.getOrNull(liveDraws[o + 2 + u]) ?: 0)
            }
            livePointers(liveDraws[o])
            glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, liveDraws[o + 1])
        }
        glActiveTexture(GL_TEXTURE0)
        glDepthMask(true)
        glDisable(GL_DEPTH_TEST)
    }

    /** Points the live attributes at instance [first] on. */
    private fun livePointers(first: Int) {
        glBindBuffer(GL_ARRAY_BUFFER, liveBuffer)
        for (a in 0 until 4) glVertexAttribPointer(a, 4, GL_FLOAT, false, LIVE_FLOATS * 4, first * LIVE_FLOATS * 4 + a * 16)
    }

    /**
     * Streams [b] into the live buffer, its sprites' pages swapped for the texture units they're bound to, split into
     * draws of up to [LIVE_UNITS] pages each; the building columns are the first draw's, before its instances.
     */
    private fun uploadLive(b: LiveBatch) {
        liveUp = b
        val total = b.covers + b.count
        if (liveFloats.size < total * LIVE_FLOATS) liveFloats = FloatArray(total * LIVE_FLOATS * 2)
        val f = liveFloats
        b.data.copyInto(f, 0, 0, total * LIVE_FLOATS)
        liveDrawCount = 0
        val bound = IntArray(LIVE_UNITS) { -1 }
        var start = b.covers
        var used = 0
        fun close(end: Int) {
            if (end <= start) return
            val o = liveDrawCount * (2 + LIVE_UNITS)
            if (liveDraws.size < o + 2 + LIVE_UNITS) liveDraws = liveDraws.copyOf(maxOf(64, liveDraws.size * 2))
            liveDraws[o] = start
            liveDraws[o + 1] = end - start
            bound.copyInto(liveDraws, o + 2)
            liveDrawCount++
            start = end
        }
        for (i in b.covers until total) {
            val o = i * LIVE_FLOATS
            if (f[o].toInt() != LIVE_SPRITE) continue
            val page = f[o + 1].toInt()
            var unit = -1
            for (u in 0 until used) if (bound[u] == page) unit = u
            if (unit < 0) {
                if (used == LIVE_UNITS) {
                    close(i)
                    bound.fill(-1)
                    used = 0
                }
                unit = used++
                bound[unit] = page
            }
            f[o + 1] = unit.toFloat()
        }
        close(total)
        val bytes = total * LIVE_FLOATS * 4
        if (liveBytes.capacity() < bytes) liveBytes = ByteBuffer.allocateDirect(bytes * 2).order(ByteOrder.nativeOrder())
        liveBytes.clear()
        liveBytes.asFloatBuffer().put(f, 0, total * LIVE_FLOATS)
        liveBytes.position(0)
        glBindBuffer(GL_ARRAY_BUFFER, liveBuffer)
        // A new store each time, so the GPU needn't wait for the last frame's to be done with.
        glBufferData(GL_ARRAY_BUFFER, maxOf(bytes, 16), if (bytes > 0) liveBytes else null, GL_STREAM_DRAW)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
    }

    /** Copies the sheets [scene] has placed and not yet sent up onto their pages, and tells it they're in. */
    private fun takeLive() {
        val ups = scene.atlas.uploads
        // Answered ones are out of the list; letting go of them here lets go of their images.
        if (liveDone.isNotEmpty()) liveDone.retainAll(ups)
        if (ups.isEmpty()) return
        var done: ArrayList<LiveUpload>? = null
        val t0 = System.nanoTime()
        for (u in ups) {
            if (u.generation != context || u in liveDone) continue
            uploadSprites(u)
            liveDone += u
            (done ?: ArrayList<LiveUpload>().also { done = it }) += u
        }
        val d = done ?: return
        scene.liveUploaded(d)
        val side = IntArray(Atlas.LEVELS) { livePageSide(it) }
        val mb = livePages.indices.sumOf { livePages[it].size.toLong() * side[it] * side[it] * 4 } / 1_000_000
        Log.i(TAG, "gl live sheets up=${d.size} pages=${livePages.joinToString(",") { it.size.toString() }} MB=$mb ms=${(System.nanoTime() - t0) / 1_000_000}")
    }

    /** One sheet's sprites onto their pages at its level, making pages, cleared, as they're first needed. */
    private fun uploadSprites(u: LiveUpload) {
        val pages = livePages[u.level]
        val side = livePageSide(u.level)
        val c = u.cells
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        for (k in 0 until c.size / LiveAtlas.UP) {
            val o = k * LiveAtlas.UP
            val page = c[o + 4]
            if (page < 0) continue
            while (pages.size <= page) pages += livePage(side)
            val w = c[o + 2]
            val h = c[o + 3]
            if (spritePixels.size < w * h) spritePixels = IntArray(w * h)
            u.image.readPixels(spritePixels, c[o], c[o + 1], w, h)
            if (strip.size < w * h * 4) strip = ByteArray(w * h * 4)
            val out = strip
            for (i in 0 until w * h) {
                val q = spritePixels[i]
                val a = q ushr 24
                out[i * 4] = (((q shr 16) and 255) * a / 255).toByte()
                out[i * 4 + 1] = (((q shr 8) and 255) * a / 255).toByte()
                out[i * 4 + 2] = ((q and 255) * a / 255).toByte()
                out[i * 4 + 3] = a.toByte()
            }
            if (spriteBytes.capacity() < w * h * 4) spriteBytes = ByteBuffer.allocateDirect(w * h * 8)
            spriteBytes.clear()
            spriteBytes.put(out, 0, w * h * 4).flip()
            glBindTexture(GL_TEXTURE_2D, pages[page])
            glTexSubImage2D(GL_TEXTURE_2D, 0, c[o + 5], c[o + 6], w, h, GL_RGBA, GL_UNSIGNED_BYTE, spriteBytes)
        }
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    private var spritePixels = IntArray(0)
    private var spriteBytes = ByteBuffer.allocateDirect(0)

    /** A clear page [side] square, filtered as the sheets are. */
    private fun livePage(side: Int): Int {
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glBindTexture(GL_TEXTURE_2D, ids[0])
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, side, side)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        val fb = IntArray(1)
        glGenFramebuffers(1, fb, 0)
        glBindFramebuffer(GL_FRAMEBUFFER, fb[0])
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, ids[0], 0)
        glViewport(0, 0, side, side)
        glClearColor(0f, 0f, 0f, 0f)
        glClear(GL_COLOR_BUFFER_BIT)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glDeleteFramebuffers(1, fb, 0)
        glViewport(0, 0, width, height)
        glBindTexture(GL_TEXTURE_2D, ids[0])
        return ids[0]
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
        for (p in livePages) {
            if (p.isNotEmpty()) glDeleteTextures(p.size, p.toIntArray(), 0)
            p.clear()
        }
        liveDone.clear()
        liveUp = null
        if (liveBuffer != 0) glDeleteBuffers(1, intArrayOf(liveBuffer), 0)
        liveBuffer = 0
        for (l in levels) if (l != null) glDeleteTextures(4, l.textures, 0)
        levels.fill(null)
        for (t in intArrayOf(weatherTex, legsTex, staticCells, shadowCells)) if (t != 0) glDeleteTextures(1, intArrayOf(t), 0)
        staticCells = 0
        shadowCells = 0
        weatherTex = 0
        weatherUp = null
        legsTex = 0
        for (a in intArrayOf(vao, groundVao, liveVao)) if (a != 0) glDeleteVertexArrays(1, intArrayOf(a), 0)
        for (p in intArrayOf(spriteProgram, shadowProgram, groundProgram, liveProgram)) if (p != 0) glDeleteProgram(p)
        vao = 0
        groundVao = 0
        liveVao = 0
        liveProgram = 0
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
        /** Live pages bound at once, each to its own texture unit. */
        const val LIVE_UNITS = 8
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

/**
 * What moves, an instance a quad: a sprite's screen rect, a line's or shape's box round it with a pixel to spare for
 * smoothing, or a building's column. Positions are screen pixels, y down; depth is the instance's own.
 */
private const val LIVE_VS = """#version 300 es
precision highp float;
layout(location = 0) in vec4 aK;
layout(location = 1) in vec4 aA;
layout(location = 2) in vec4 aB;
layout(location = 3) in vec4 aC;
uniform vec2 uScreen;
uniform float uSide;
out vec2 vPos;
out vec2 vUv;
flat out int vKind;
flat out int vUnit;
flat out float vShadow;
flat out vec4 vA;
flat out vec4 vB;
flat out vec4 vC;
flat out vec4 vEdge;
void main() {
    vec2 o = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    int kind = int(aK.x + 0.5);
    vec2 p;
    vUv = vec2(0.0);
    vEdge = vec4(0.0);
    if (kind == 0 || kind == 3) {
        p = mix(aA.xy, aA.zw, o);
        vUv = mix(aB.xy, aB.zw, o) / uSide;
        // Half a texel in, so smoothing never reaches past the sprite.
        vEdge = vec4(aB.xy + 0.5, aB.zw - 0.5) / uSide;
    } else if (kind == 1) {
        vec2 d = aA.zw - aA.xy;
        float l = length(d);
        vec2 u = l > 0.0001 ? d / l : vec2(1.0, 0.0);
        vec2 n = vec2(-u.y, u.x);
        float hw = max(aB.x, 0.5);
        float ext = (aB.y > 0.5 ? hw : 0.0) + 1.0;
        p = mix(aA.xy - u * ext, aA.zw + u * ext, o.x) + n * (hw + 1.0) * (o.y * 2.0 - 1.0);
    } else {
        vec2 lo = min(min(aA.xy, aA.zw), min(aB.xy, aB.zw)) - 1.0;
        vec2 hi = max(max(aA.xy, aA.zw), max(aB.xy, aB.zw)) + 1.0;
        p = mix(lo, hi, o);
    }
    vPos = p;
    vKind = kind;
    vUnit = int(aK.y + 0.5);
    vShadow = aK.w;
    vA = aA;
    vB = aB;
    vC = aC;
    gl_Position = vec4(p.x / uScreen.x * 2.0 - 1.0, 1.0 - p.y / uScreen.y * 2.0, aK.z * 2.0 - 1.0, 1.0);
}
"""

/** A sprite from its page, tinted or as a shadow; a line or convex shape smoothed over a pixel at its edges. */
private const val LIVE_FS = """#version 300 es
precision highp float;
uniform mediump sampler2D uP0;
uniform mediump sampler2D uP1;
uniform mediump sampler2D uP2;
uniform mediump sampler2D uP3;
uniform mediump sampler2D uP4;
uniform mediump sampler2D uP5;
uniform mediump sampler2D uP6;
uniform mediump sampler2D uP7;
in vec2 vPos;
in vec2 vUv;
flat in int vKind;
flat in int vUnit;
flat in float vShadow;
flat in vec4 vA;
flat in vec4 vB;
flat in vec4 vC;
flat in vec4 vEdge;
out vec4 frag;
vec4 page(int u, vec2 uv) {
    if (u == 0) return textureLod(uP0, uv, 0.0);
    if (u == 1) return textureLod(uP1, uv, 0.0);
    if (u == 2) return textureLod(uP2, uv, 0.0);
    if (u == 3) return textureLod(uP3, uv, 0.0);
    if (u == 4) return textureLod(uP4, uv, 0.0);
    if (u == 5) return textureLod(uP5, uv, 0.0);
    if (u == 6) return textureLod(uP6, uv, 0.0);
    return textureLod(uP7, uv, 0.0);
}
// How far inside the edge from a to b the point is, for corners going round either way ([turn] the sign).
float inside(vec2 a, vec2 b, float turn) {
    vec2 d = b - a;
    float l = length(d);
    if (l < 0.0001) return 1e6;
    return turn * (d.x * (vPos.y - a.y) - d.y * (vPos.x - a.x)) / l;
}
void main() {
    if (vKind == 3) {
        frag = vec4(0.0);
        return;
    }
    if (vKind == 0) {
        vec4 t = page(vUnit, clamp(vUv, vEdge.xy, vEdge.zw));
        frag = vShadow > 0.5 ? vec4(0.0, 0.0, 0.0, t.a * vC.a) : t * vC;
        return;
    }
    float cover;
    if (vKind == 1) {
        vec2 d = vA.zw - vA.xy;
        float l = length(d);
        vec2 u = l > 0.0001 ? d / l : vec2(1.0, 0.0);
        vec2 q = vPos - vA.xy;
        float t = dot(q, u);
        // Lines under a pixel wide are drawn a pixel wide and fainter.
        float hw = max(vB.x, 0.5);
        if (vB.y > 0.5) {
            cover = clamp(hw + 0.5 - length(q - u * clamp(t, 0.0, l)), 0.0, 1.0);
        } else {
            float s = abs(u.x * q.y - u.y * q.x);
            cover = clamp(hw + 0.5 - s, 0.0, 1.0) * clamp(t + 0.5, 0.0, 1.0) * clamp(l - t + 0.5, 0.0, 1.0);
        }
        cover *= min(1.0, vB.x * 2.0);
    } else {
        vec2 p0 = vA.xy;
        vec2 p1 = vA.zw;
        vec2 p2 = vB.xy;
        vec2 p3 = vB.zw;
        float area = (p1.x - p0.x) * (p2.y - p0.y) - (p1.y - p0.y) * (p2.x - p0.x) + (p2.x - p0.x) * (p3.y - p0.y) - (p2.y - p0.y) * (p3.x - p0.x);
        float turn = area >= 0.0 ? 1.0 : -1.0;
        float m = min(min(inside(p0, p1, turn), inside(p1, p2, turn)), min(inside(p2, p3, turn), inside(p3, p0, turn)));
        cover = clamp(m + 0.5, 0.0, 1.0);
    }
    frag = vC * cover;
}
"""
