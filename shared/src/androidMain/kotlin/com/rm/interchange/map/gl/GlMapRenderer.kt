package com.rm.interchange.map.gl

import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.util.Log
import com.rm.interchange.map.LevelSheets
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val TAG = "Interchange"

/** Bytes a sprite takes in a region's buffer: left, top, width, height as floats, then sheet x, y, layer and snow as shorts. */
private const val SPRITE_BYTES = 24

/** The ground until G2 draws it: one flat colour over the map. */
private const val FLAT_GROUND = 0xff6f8f45.toInt()

/** A sheet uploaded as a texture array, cut as [pages] says. */
private class Paged(val tex: Int, val pages: AtlasPages)

/**
 * Draws the GPU map on the GL thread (OpenGL ES 3.0): the page colour, the map's ground in one flat colour, then the
 * static sprites of [scene]'s regions in drawing order, each pass a few instanced draws of the runs on screen.
 * Region buffers are uploaded once each and kept while their turn shows.
 *
 * GL thread only, but for [scene]'s shared fields, which it only reads.
 */
internal class GlMapRenderer(private val scene: GlScene, private val page: Int) : GLSurfaceView.Renderer {
    private var spriteProgram = 0
    private var shadowProgram = 0
    private var flatProgram = 0
    private var vao = 0
    private var maxTexture = 0
    private var static: Paged? = null
    private var shadows: Paged? = null
    private var sheetsUp: LevelSheets? = null
    private var width = 1
    private var height = 1

    private class Buffer(val region: RegionSprites, val id: Int)
    private var buffers = arrayOfNulls<Buffer>(0)
    private var bufferTurn = -1
    private var drawable = arrayOfNulls<RegionSprites>(0)
    private var columns = IntArray(0)
    private val runs = DrawRuns()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context: whatever was made in the last went with it.
        static = null
        shadows = null
        sheetsUp = null
        buffers = arrayOfNulls(0)
        bufferTurn = -1
        maxTexture = IntArray(1).also { glGetIntegerv(GL_MAX_TEXTURE_SIZE, it, 0) }[0]
        spriteProgram = program(SPRITE_VS, SPRITE_FS)
        shadowProgram = program(SPRITE_VS, SHADOW_FS)
        flatProgram = program(FLAT_VS, FLAT_FS)
        val ids = IntArray(1)
        glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        glBindVertexArray(vao)
        glEnableVertexAttribArray(0)
        glVertexAttribDivisor(0, 1)
        glEnableVertexAttribArray(1)
        glVertexAttribDivisor(1, 1)
        glBindVertexArray(0)
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
        drawGround(f, sx, sy, tx, ty)
        scene.sheets?.let { if (it !== sheetsUp) upload(it) }
        val st = static ?: return
        val sh = shadows ?: return
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
        glBindVertexArray(vao)
        for (pass in 0 until PASSES) {
            val shadow = pass == 1
            val prog = if (shadow) shadowProgram else spriteProgram
            val sheet = if (shadow) sh else st
            glUseProgram(prog)
            // Sprites are premultiplied; a shadow darkens what's under it by how dark it is.
            if (shadow) glBlendFunc(GL_ZERO, GL_ONE_MINUS_SRC_ALPHA) else glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
            glUniform4f(glGetUniformLocation(prog, "uXf"), sx, sy, tx, ty)
            glUniform2f(glGetUniformLocation(prog, "uPage"), sheet.pages.width.toFloat(), sheet.pages.pageHeight.toFloat())
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D_ARRAY, sheet.tex)
            glUniform1i(glGetUniformLocation(prog, "uSheet"), 0)
            for (k in runs.passStart[pass] until runs.passStart[pass + 1]) {
                val b = buffers[runs.region(k)] ?: continue
                val at = runs.first(k) * SPRITE_BYTES
                glBindBuffer(GL_ARRAY_BUFFER, b.id)
                glVertexAttribPointer(0, 4, GL_FLOAT, false, SPRITE_BYTES, at)
                glVertexAttribPointer(1, 4, GL_UNSIGNED_SHORT, false, SPRITE_BYTES, at + 16)
                glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, runs.count(k))
            }
        }
        glBindVertexArray(0)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glDisable(GL_BLEND)
    }

    /** The map's diamond at sea level in one colour. */
    private fun drawGround(f: GlFrame, sx: Float, sy: Float, tx: Float, ty: Float) {
        val hw = SOURCE_HALF_WIDTH
        val vw = f.viewWidth.toFloat()
        val vh = f.viewHeight.toFloat()
        glUseProgram(flatProgram)
        glUniform4f(glGetUniformLocation(flatProgram, "uXf"), sx, sy, tx, ty)
        // Strip order: top, right, left, front.
        glUniform2fv(glGetUniformLocation(flatProgram, "uCorner"), 4, floatArrayOf(0f, 0f, vw * hw, vw * hw / 2, -vh * hw, vh * hw / 2, (vw - vh) * hw, (vw + vh) * hw / 2), 0)
        glUniform4f(glGetUniformLocation(flatProgram, "uColour"), ((FLAT_GROUND shr 16) and 255) / 255f, ((FLAT_GROUND shr 8) and 255) / 255f, (FLAT_GROUND and 255) / 255f, 1f)
        // No attributes: the corners come from the uniform.
        glBindVertexArray(0)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
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
        val st = static!!.pages
        val sh = shadows!!.pages
        for (i in shown.regions.indices) {
            val r = shown.regions[i]
            val b = buffers[i]
            if (r != null && b?.region !== r) {
                if (b != null) glDeleteBuffers(1, intArrayOf(b.id), 0)
                buffers[i] = Buffer(r, upload(r, st, sh))
            }
            drawable[i] = if (r != null && buffers[i]?.region === r) r else null
        }
    }

    private fun upload(r: RegionSprites, st: AtlasPages, sh: AtlasPages): Int {
        val bytes = ByteBuffer.allocateDirect(maxOf(1, r.count) * SPRITE_BYTES).order(ByteOrder.nativeOrder())
        val d = r.data
        for (i in 0 until r.count) {
            val o = i * SpriteRecorder.STRIDE
            val pages = if (d[o] == SpriteRecorder.SHADOW) sh else st
            val sheetY = d[o + 6]
            bytes.putFloat(d[o + 1].toFloat()).putFloat(d[o + 2].toFloat()).putFloat(d[o + 3].toFloat()).putFloat(d[o + 4].toFloat())
            bytes.putShort(d[o + 5].toShort()).putShort(pages.rowIn(sheetY).toShort()).putShort(pages.layerOf(sheetY).toShort()).putShort(d[o + 7].toShort())
        }
        bytes.position(0)
        val ids = IntArray(1)
        glGenBuffers(1, ids, 0)
        glBindBuffer(GL_ARRAY_BUFFER, ids[0])
        glBufferData(GL_ARRAY_BUFFER, bytes.capacity(), bytes, GL_STATIC_DRAW)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        return ids[0]
    }

    /** The static sheet and the shadows as texture arrays split to the GPU's largest texture, with mipmaps for zooming out. */
    private fun upload(sheets: LevelSheets) {
        static?.let { glDeleteTextures(1, intArrayOf(it.tex), 0) }
        shadows?.let { glDeleteTextures(1, intArrayOf(it.tex), 0) }
        // The regions' sheet places depend on the pages.
        freeBuffers()
        buffers = arrayOfNulls(0)
        val t0 = System.nanoTime()
        val sp = AtlasPages(sheets.staticWidth, sheets.static.size / sheets.staticWidth, AtlasPages.tallestStatic(SOURCE_LEVEL), maxTexture)
        static = Paged(pagedTexture(sp, GL_RGBA8) { y0, rows, buf -> premultiplied(sheets.static, sheets.staticWidth, y0, rows, buf) }, sp)
        val hp = AtlasPages(sheets.shadowWidth, sheets.shadows.size / sheets.shadowWidth, AtlasPages.tallestShadow(SOURCE_LEVEL), maxTexture)
        shadows = Paged(pagedTexture(hp, GL_R8) { y0, rows, buf -> buf.put(sheets.shadows, y0 * sheets.shadowWidth, rows * sheets.shadowWidth) }, hp)
        sheetsUp = sheets
        Log.i(TAG, "gl sheets static ${sp.width}x${sp.pageHeight}x${sp.layers} shadows ${hp.width}x${hp.pageHeight}x${hp.layers} maxTex=$maxTexture ms=${(System.nanoTime() - t0) / 1_000_000}")
    }

    /** A texture array for [pages], each layer filled a strip of rows at a time by [fill], which puts rows from sheet row y0 into the buffer. */
    private fun pagedTexture(pages: AtlasPages, format: Int, fill: (y0: Int, rows: Int, buf: ByteBuffer) -> Unit): Int {
        val texel = if (format == GL_R8) 1 else 4
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glBindTexture(GL_TEXTURE_2D_ARRAY, ids[0])
        val levels = 1 + (31 - Integer.numberOfLeadingZeros(maxOf(pages.width, pages.pageHeight)))
        glTexStorage3D(GL_TEXTURE_2D_ARRAY, levels, format, pages.width, pages.pageHeight, pages.layers)
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
                // Rows past the sheet's end are clear, so the mipmaps don't pick up whatever the memory held.
                val clear = n * pages.width * texel - buf.position()
                if (clear > 0) buf.put(ByteArray(clear))
                buf.flip()
                glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, y, layer, pages.width, n, 1, if (format == GL_R8) GL_RED else GL_RGBA, GL_UNSIGNED_BYTE, buf)
                y += n
            }
        }
        glGenerateMipmap(GL_TEXTURE_2D_ARRAY)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0)
        return ids[0]
    }

    /** Rows [y0] until y0 + [rows] of an ARGB sheet [w] wide into [buf] as premultiplied RGBA bytes. */
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
            out[k + 2] = ((p and 255) * a / 255).toByte()
            out[k + 3] = a.toByte()
            k += 4
        }
        buf.put(out, 0, n * 4)
    }

    private var strip = ByteArray(0)

    private fun freeBuffers() {
        for (b in buffers) if (b != null) glDeleteBuffers(1, intArrayOf(b.id), 0)
        buffers = arrayOfNulls(buffers.size)
    }

    /** Frees every texture, buffer and program, as the surface goes. */
    fun release() {
        freeBuffers()
        static?.let { glDeleteTextures(1, intArrayOf(it.tex), 0) }
        shadows?.let { glDeleteTextures(1, intArrayOf(it.tex), 0) }
        static = null
        shadows = null
        sheetsUp = null
        if (vao != 0) glDeleteVertexArrays(1, intArrayOf(vao), 0)
        for (p in intArrayOf(spriteProgram, shadowProgram, flatProgram)) if (p != 0) glDeleteProgram(p)
        vao = 0
        spriteProgram = 0
        shadowProgram = 0
        flatProgram = 0
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
    }
}

private const val SPRITE_VS = """#version 300 es
precision highp float;
layout(location = 0) in vec4 aRect;
layout(location = 1) in vec4 aTex;
uniform vec4 uXf;
uniform vec2 uPage;
out vec3 vUv;
void main() {
    vec2 o = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    vUv = vec3((aTex.xy + o * aRect.zw) / uPage, aTex.z);
    gl_Position = vec4((aRect.xy + o * aRect.zw) * uXf.xy + uXf.zw, 0.0, 1.0);
}
"""

private const val SPRITE_FS = """#version 300 es
precision mediump float;
uniform mediump sampler2DArray uSheet;
in vec3 vUv;
out vec4 frag;
void main() {
    frag = texture(uSheet, vUv);
}
"""

private const val SHADOW_FS = """#version 300 es
precision mediump float;
uniform mediump sampler2DArray uSheet;
in vec3 vUv;
out vec4 frag;
void main() {
    frag = vec4(0.0, 0.0, 0.0, texture(uSheet, vUv).r);
}
"""

private const val FLAT_VS = """#version 300 es
precision highp float;
uniform vec4 uXf;
uniform vec2 uCorner[4];
void main() {
    gl_Position = vec4(uCorner[gl_VertexID] * uXf.xy + uXf.zw, 0.0, 1.0);
}
"""

private const val FLAT_FS = """#version 300 es
precision mediump float;
uniform vec4 uColour;
out vec4 frag;
void main() {
    frag = uColour;
}
"""
