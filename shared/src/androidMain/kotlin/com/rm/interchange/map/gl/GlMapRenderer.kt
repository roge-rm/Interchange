package com.rm.interchange.map.gl

import android.opengl.GLES30.*
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import androidx.compose.ui.graphics.asAndroidBitmap
import android.util.Log
import com.rm.interchange.map.MapOverlays
import com.rm.interchange.map.Atlas
import com.rm.interchange.map.TileRecorder
import com.rm.interchange.map.LevelSheets
import com.rm.interchange.map.Zoom
import java.lang.ref.WeakReference
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
 * and foundations, as [com.rm.interchange.map.TileRecorder] lays them down. Sprites come from the sheets of the
 * level [com.rm.interchange.map.Zoom.levelFor] the zoom, placed by cell tables (a row a level), so region buffers are the same at every level; they're
 * uploaded once each and kept while their turn shows and the scene keeps them round the screen. The weather texture takes only the rows that changed.
 *
 * GL thread only, but for [scene]'s shared fields, which it only reads.
 */
internal class GlMapRenderer(private val scene: GlScene, private val page: Int) : GLSurfaceView.Renderer {
    private var spriteProgram = 0
    private var shadowProgram = 0
    private var groundProgram = 0
    /** The ground program with the overlays' code, made the first time an overlay's on. */
    private var overlayProgram = 0
    private var vao = 0
    private var groundVao = 0
    private var weatherTex = 0
    private var weatherUp: GroundWeather? = null
    private var legsTex = 0
    /** The track wear for the condition overlay, as an R8UI texture by world tile, and the copy it holds. */
    private var wearTex = 0
    private var wearUp: TrackWear? = null
    /** The overlays drawn this frame: the scene's, but for the condition overlay till its wear is up. */
    private var overlays = 0
    /** The look of the year drawn this frame, the shown frame's, read once so every pass and square agrees. */
    private var look = -1
    private var maxTexture = 0
    private var maxLayers = 0
    /** The sheets the GPU wouldn't take, a level a slot, so they aren't tried again. */
    private val refused = arrayOfNulls<LevelSheets>(Atlas.LEVELS)
    private val levels = arrayOfNulls<LevelTextures>(Atlas.LEVELS)
    private var staticCells = 0
    private var shadowCells = 0
    private var width = 1
    private var height = 1

    private class Buffer(from: RegionSprites, val id: Int) {
        /** What's drawn from it: the region but for its sprites, which are on the GPU, so the heap lets them go once the scene does. */
        val region = RegionSprites(
            from.turn, from.index, from.row0, from.row1, from.columns, IntArray(0), from.count, from.starts, IntArray(0), from.version,
            from.cols, from.look, from.year, null, from.content, from.weather,
        )
        /** The region it was uploaded from, held weakly, to tell whether it's the one shown. */
        private val source = WeakReference(from)
        fun of(r: RegionSprites) = source.get() === r
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

    private var labelProgram = 0
    private var labelVao = 0
    private var labelBuffer = 0
    private var labelTex = 0
    /** The atlas page on [labelTex] and its side; a batch for another page isn't drawn. */
    private var labelGen = -1
    private var labelSide = 0
    private var labelQuads = FloatArray(0)
    private var labelTaken = FloatArray(0)
    private var labelBytes = ByteBuffer.allocateDirect(0)

    private var toolProgram = 0
    private var toolVao = 0
    private var toolTex = 0
    private var toolTexW = 0
    private var toolTexH = 0
    /** The tool overlay image on [toolTex], with where it was drawn. */
    private var toolUp: OverlayShot? = null
    private val toolRect = FloatArray(4)

    private var shadeProgram = 0
    private var shadePlaced = FloatArray(0)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context: whatever was made in the last went with it.
        levels.fill(null)
        refused.fill(null)
        weatherTex = 0
        weatherUp = null
        legsTex = 0
        wearTex = 0
        wearUp = null
        buffers = arrayOfNulls(0)
        bufferTurn = -1
        for (p in livePages) p.clear()
        liveDone.clear()
        liveUp = null
        maxTexture = IntArray(1).also { glGetIntegerv(GL_MAX_TEXTURE_SIZE, it, 0) }[0]
        maxLayers = IntArray(1).also { glGetIntegerv(GL_MAX_ARRAY_TEXTURE_LAYERS, it, 0) }[0]
        // Nothing's on the GPU in a new context: the cover stays up till every level's in again.
        context++
        scene.labelAtlas.limit(maxTexture)
        scene.lost(context)
        spriteProgram = program(SPRITE_VS, SPRITE_FS)
        shadowProgram = program(SPRITE_VS, SHADOW_FS)
        groundProgram = program(GROUND_VS, GROUND_FS)
        overlayProgram = 0
        liveProgram = program(LIVE_VS, LIVE_FS)
        labelProgram = program(LABEL_VS, LABEL_FS)
        labelTex = 0
        labelGen = -1
        toolProgram = program(TOOL_VS, TOOL_FS)
        toolVao = IntArray(1).also { glGenVertexArrays(1, it, 0) }[0]
        shadeProgram = program(SHADE_VS, SHADE_FS)
        toolTex = 0
        toolTexW = 0
        toolTexH = 0
        toolUp = null
        val ids = IntArray(3)
        glGenVertexArrays(3, ids, 0)
        vao = ids[0]
        groundVao = ids[1]
        liveVao = ids[2]
        squareProgram = program(SQUARE_VS, SQUARE_FS)
        ragSampler = IntArray(1).also { glGenSamplers(1, it, 0) }[0]
        glSamplerParameteri(ragSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST)
        glSamplerParameteri(ragSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glSamplerParameteri(ragSampler, GL_TEXTURE_WRAP_S, GL_REPEAT)
        glSamplerParameteri(ragSampler, GL_TEXTURE_WRAP_T, GL_REPEAT)
        convertProgram = program(SQUARE_VS, CONVERT_FS)
        squareVao = IntArray(1).also { glGenVertexArrays(1, it, 0) }[0]
        squareFbo = 0
        squares.clear()
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
        labelVao = IntArray(1).also { glGenVertexArrays(1, it, 0) }[0]
        labelBuffer = IntArray(1).also { glGenBuffers(1, it, 0) }[0]
        glBindVertexArray(labelVao)
        glBindBuffer(GL_ARRAY_BUFFER, labelBuffer)
        for (a in 0 until 3) {
            glEnableVertexAttribArray(a)
            glVertexAttribDivisor(a, 1)
        }
        glVertexAttribPointer(0, 4, GL_FLOAT, false, LabelBatch.QUAD * 4, 0)
        glVertexAttribPointer(1, 4, GL_FLOAT, false, LabelBatch.QUAD * 4, 16)
        glVertexAttribPointer(2, 1, GL_FLOAT, false, LabelBatch.QUAD * 4, 32)
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
        maxSquares = squareBudget(width, height)
        glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        drawFrame()
    }

    private fun drawFrame() {
        // Vehicles and weather go up while the screen's level is still unpacking, and sheets before there's anything to
        // show; vehicles again after a level, as the signals come with it.
        takeLive()
        scene.weather?.let { if (it !== weatherUp) upload(it) }
        val wanted = scene.overlays
        val wear = scene.wear
        if (wanted and MapOverlays.CONDITION != 0 && wear != null && wear !== wearUp) upload(wear)
        val drawn = if (wearUp == null || wear == null) wanted and MapOverlays.CONDITION.inv() else wanted
        // The safe colours only count with an overlay that uses them, so the plain ground program stays.
        overlays = if (drawn and MapOverlays.CONDITION == 0) drawn and MapOverlays.SAFE.inv() else drawn
        if (takeLevels(scene.levels)) {
            takeLive()
            scene.requestRender()
        }
        val shown = scene.shown ?: return clearScreen()
        // After taking the frame, so the label images it uses are among those taken.
        takeLabels(shown.labels)
        takeTool()
        val f = shown.frame
        look = shown.look
        // The screen's level is uploaded before the cover lifts and the zoom's held to levels that are in, so there's
        // never another level to stand in.
        val lv = Zoom.levelFor(f.scale * SOURCE_HALF_WIDTH)
        val tex = levels[lv] ?: return clearScreen()
        if (weatherTex == 0) return clearScreen()
        if (columns.size < f.viewHeight * 2) columns = IntArray(f.viewHeight * 2)
        visibleColumns(f, columns)
        uploadRegions(shown, columns)
        val far = scene.far
        for (i in drawable.indices) {
            val r = drawable[i]
            if (r != null && far != null && far.covers(r, f)) drawable[i] = null
        }
        // Squares are drawn ahead before the screen, as a tiler pays to come back to a framebuffer it's left.
        frameNo++
        shownFrame = f
        val on = squaresOn(f, lv)
        val left = prepareSquares(f, lv, tex, on)
        if (GL_CHECKS) {
            checkHoles(f, left)
            checkEmpty(f)
        }
        clearScreen()
        // Clip space from source level pixels: screen = origin + p * scale, then to -1..1 with y up.
        val xf = floatArrayOf(2f * f.scale / width, -2f * f.scale / height, 2f * f.originX / width - 1f, 1f - 2f * f.originY / height)
        glEnable(GL_BLEND)
        // The fresh squares mark the depth buffer, so where the rest are the static map is drawn here as it'd have
        // been, the depth test turning away its pixels elsewhere before they're shaded.
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_ALWAYS)
        drawSquares(f, lv, tex, on)
        if (left > 0) {
            // Only the tiles reaching into the box round the squares not drawn ahead.
            if (directCols.size < f.viewHeight * 2) directCols = IntArray(f.viewHeight * 2)
            squaresBoxColumns(f, lv, leftBox[0], leftBox[1], leftBox[2], leftBox[3], directCols)
            runs.build(drawable, directCols)
            glDepthFunc(GL_GREATER)
            glDepthMask(false)
            for (pass in 0 until PASSES) drawPass(pass, runs, xf, f, lv, tex)
            glDepthMask(true)
        }
        glDisable(GL_DEPTH_TEST)
        // Back to clear for what moves, which cuts by depth.
        glClear(GL_DEPTH_BUFFER_BIT)
        shown.live?.let { drawLive(it) }
        shown.sky?.let { drawShades(it, shown) }
        if (shown.overlay != null) drawTool(shown)
        shown.labels?.let { drawLabels(it, shown) }
        glBindVertexArray(0)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glDisable(GL_BLEND)
        scene.drew(shown)
    }

    private fun clearScreen() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, width, height)
        glClearColor(((page shr 16) and 255) / 255f, ((page shr 8) and 255) / 255f, (page and 255) / 255f, 1f)
        glClearDepthf(0f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
    }

    /** Draws pass [pass] of [runs] with clip transform [xf] (scale x, y, then offset x, y from source level pixels) for frame [f] at level [lv]. */
    private fun drawPass(pass: Int, runs: DrawRuns, xf: FloatArray, f: GlFrame, lv: Int, tex: LevelTextures) {
        val shadow = pass == 1
        val ground = pass == 0
        val prog = if (ground) groundFor() else if (shadow) shadowProgram else spriteProgram
        val sheet = if (shadow) tex.shadows else tex.static
        glUseProgram(prog)
        glBindVertexArray(if (ground) groundVao else vao)
        // Sprites are premultiplied and the ground is opaque; a shadow darkens what's under it by how dark it is and
        // keeps its alpha, so a square's stays as the sprites and ground left it.
        if (shadow) glBlendFuncSeparate(GL_ZERO, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE) else glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        glUniform4f(glGetUniformLocation(prog, "uXf"), xf[0], xf[1], xf[2], xf[3])
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
        glUniform1f(glGetUniformLocation(prog, "uToSource"), toSource(lv))
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

    /**
     * A square of the static map drawn ahead into a texture at its level, and what it was drawn from: the level's
     * textures, the weather, the look and the regions it reaches, [first] on. It's drawn again once any of them change,
     * the weather only in the corner [rows] it reads.
     */
    private class Square(val tex: Int, val lv: Int, val cx: Int, val cy: Int) {
        var from: LevelTextures? = null
        var overlays = 0
        var wear: TrackWear? = null
        /** The weather it was drawn with, by size and [GroundWeather.version], so it doesn't hold the texels on the heap. */
        var weatherWidth = -1
        var weatherHeight = -1
        var weatherVersion = -1
        var rows = IntArray(2)
        var look = -1
        var first = 0
        /** The [RegionSprites.content] and look of each region it reaches, so it doesn't hold them on the heap. */
        var contents = arrayOfNulls<Any>(0)
        var looks = IntArray(0)
        var used = 0L
    }
    private val squares = HashMap<Long, Square>()
    private var squareFbo = 0
    /** Nearest sampling of the ground sheet for its noise, on unit 8. */
    private var ragSampler = 0
    private var squareProgram = 0
    private var convertProgram = 0
    private var squareVao = 0
    private var frameNo = 0L
    private var squareCols = IntArray(0)
    private val squareRuns = DrawRuns()
    /** The frame being drawn, for the squares' tiles ([tilesOf]). */
    private var shownFrame: GlFrame? = null
    private var heldCols = IntArray(0)
    /** The last square frame [tilesOf] made, with what it was made for, so one square's checks share it. */
    private var tilesFrame: GlFrame? = null
    private var tilesFor: GlFrame? = null
    private var tilesLv = 0
    private var tilesCx = 0
    private var tilesCy = 0
    /** The squares on screen neither fresh nor standing, first and last column then row, for the direct passes. */
    private val leftBox = IntArray(4)
    private var directCols = IntArray(0)
    /** Squares kept, from [squareBudget] for the screen. */
    private var maxSquares = squareBudget(1, 1)

    private fun squareKey(turn: Int, lv: Int, cx: Int, cy: Int) =
        ((turn.toLong() * 8 + lv) shl 48) or ((cx + 0x800000L) shl 24) or (cy + 0x800000L)

    private fun squareColumns(f: GlFrame): IntArray {
        if (squareCols.size < f.viewHeight * 2) squareCols = IntArray(f.viewHeight * 2)
        return squareCols
    }

    /** Square [s]'s tiles, [visibleColumns] of its frame, into a scratch array. */
    private fun tilesOf(s: Square): IntArray {
        val f = shownFrame!!
        if (heldCols.size < f.viewHeight * 2) heldCols = IntArray(f.viewHeight * 2)
        val sf = tilesFrame?.takeIf { tilesFor === f && tilesLv == s.lv && tilesCx == s.cx && tilesCy == s.cy }
            ?: squareFrame(f, s.lv, s.cx, s.cy).also { tilesFrame = it; tilesFor = f; tilesLv = s.lv; tilesCx = s.cx; tilesCy = s.cy }
        return visibleColumns(sf, heldCols)
    }

    /**
     * Whether square [s] was drawn from what's showing now, its regions still holding its tiles; a new weather with
     * no change in its rows is taken as its own.
     */
    private fun fresh(s: Square, tex: LevelTextures): Boolean {
        if (s.from !== tex || s.look != look || s.overlays != overlays) return false
        if (overlays and MapOverlays.CONDITION != 0 && s.wear !== wearUp) return false
        if (!squareRegionsSame(s.contents, s.first, drawable, tilesOf(s))) {
            if (GL_CHECKS) holes.lacking++
            return false
        }
        val w = weatherUp ?: return false
        if (s.weatherWidth != w.width || s.weatherHeight != w.height || s.weatherVersion != w.version) {
            if (s.weatherWidth != w.width || s.weatherHeight != w.height || w.version < s.weatherVersion || !weatherSame(w, s.weatherVersion, s.rows)) return false
            s.weatherVersion = w.version
        }
        return true
    }

    /**
     * Whether square [s], drawn for another look of the year, stays on screen till it's drawn again: drawn from what's
     * there now but for regions recorded again with the new look, so a change of season never leaves it to the
     * direct passes all at once.
     */
    private fun stands(s: Square, tex: LevelTextures): Boolean {
        if (s.from !== tex || s.look == look || s.overlays != overlays) return false
        if (overlays and MapOverlays.CONDITION != 0 && s.wear !== wearUp) return false
        return squareRegionsStand(s.look, s.contents, s.looks, s.first, drawable, tilesOf(s))
    }

    /**
     * Draws ahead the squares [on] (columns then rows, inclusive) of level [lv] that are missing or stale, a few a
     * frame, the nearest the middle of the screen first, then one round the screen if there's time. Returns how many
     * on screen are still neither fresh nor standing ([stands]), which the direct passes draw, and puts their box in
     * [leftBox]; asks for another frame while it gets through them.
     */
    private fun prepareSquares(f: GlFrame, lv: Int, tex: LevelTextures, on: IntArray): Int {
        val turn = f.turn
        val todo = squaresToDraw(on) { cx, cy ->
            val s = squares[squareKey(turn, lv, cx, cy)]
            s?.used = frameNo
            s != null && fresh(s, tex)
        }
        var made = 0
        var left = 0
        leftBox[0] = Int.MAX_VALUE; leftBox[1] = Int.MIN_VALUE; leftBox[2] = Int.MAX_VALUE; leftBox[3] = Int.MIN_VALUE
        for (k in todo) {
            val cx = squareX(k)
            val cy = squareY(k)
            if (made < SQUARES_A_FRAME && drawSquare(f, lv, tex, cx, cy)) made++
            else if (squares[squareKey(turn, lv, cx, cy)]?.let { stands(it, tex) } != true) {
                left++
                leftBox[0] = minOf(leftBox[0], cx); leftBox[1] = maxOf(leftBox[1], cx)
                leftBox[2] = minOf(leftBox[2], cy); leftBox[3] = maxOf(leftBox[3], cy)
            }
        }
        if (todo.isEmpty() && !ahead(f, lv, tex, on, true)) {
            // Then the next level out's, a quarter as many, so a zoom out finds them there.
            val out = lv + 1
            val outTex = levels.getOrNull(out)
            if (outTex != null) ahead(f, out, outTex, squaresOn(f, out), false)
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        // A still view asks for no frames, so keep going till they're all in; regions not in yet ask when they come.
        if (made > 0 && todo.size > made) scene.requestRender()
        return left
    }

    /** Draws ahead one square of level [lv] not drawn yet: round [on] if [ring], else on it. Returns whether it drew one. */
    private fun ahead(f: GlFrame, lv: Int, tex: LevelTextures, on: IntArray, ring: Boolean): Boolean {
        val m = if (ring) 1 else 0
        for (cy in on[2] - m..on[3] + m) for (cx in on[0] - m..on[1] + m) {
            if (ring && cx in on[0]..on[1] && cy in on[2]..on[3]) continue
            if (squares[squareKey(f.turn, lv, cx, cy)] != null) continue
            if (drawSquare(f, lv, tex, cx, cy)) return true
        }
        return false
    }

    /** Draws square [cx], [cy] of level [lv] ahead; false if a region it reaches isn't ready, so it can't be yet. */
    private fun drawSquare(f: GlFrame, lv: Int, tex: LevelTextures, cx: Int, cy: Int): Boolean {
        val sf = squareFrame(f, lv, cx, cy)
        val cols = squareColumns(f)
        visibleColumns(sf, cols)
        var r0 = Int.MAX_VALUE
        var r1 = -1
        for (vy in 0 until f.viewHeight) if (cols[2 * vy] <= cols[2 * vy + 1]) {
            r0 = minOf(r0, vy / REGION_ROWS)
            r1 = vy / REGION_ROWS
        }
        if (r1 < 0) { r0 = 0; r1 = -1 }
        for (i in r0..r1) if (drawable.getOrNull(i) == null) return false
        // Regions are kept only round the screen, so one may not have the square's tiles yet.
        for (vy in 0 until f.viewHeight) {
            if (cols[2 * vy] > cols[2 * vy + 1]) continue
            if (!drawable[vy / REGION_ROWS]!!.holds(vy, cols[2 * vy], cols[2 * vy + 1])) return false
        }
        val key = squareKey(f.turn, lv, cx, cy)
        val s = squares[key] ?: Square(squareTexture(), lv, cx, cy).also { evictSquares(); squares[key] = it }
        s.used = frameNo
        if (squareFbo == 0) squareFbo = IntArray(1).also { glGenFramebuffers(1, it, 0) }[0]
        glBindFramebuffer(GL_FRAMEBUFFER, squareFbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, s.tex, 0)
        if (GL_CHECKS && glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) holes.incomplete++
        glViewport(0, 0, SQUARE_SIDE, SQUARE_SIDE)
        glClearColor(0f, 0f, 0f, 0f)
        glClear(GL_COLOR_BUFFER_BIT)
        glEnable(GL_BLEND)
        squareRuns.build(drawable, cols)
        val xf = floatArrayOf(2f * sf.scale / SQUARE_SIDE, -2f * sf.scale / SQUARE_SIDE, 2f * sf.originX / SQUARE_SIDE - 1f, 1f - 2f * sf.originY / SQUARE_SIDE)
        for (pass in 0 until PASSES) drawPass(pass, squareRuns, xf, f, lv, tex)
        glDisable(GL_BLEND)
        s.from = tex
        s.weatherWidth = weatherUp!!.width
        s.weatherHeight = weatherUp!!.height
        s.weatherVersion = weatherUp!!.version
        s.rows = weatherRows(cols, f.viewHeight, f.turn, weatherUp!!.height)
        s.look = look
        s.overlays = overlays
        s.wear = wearUp
        s.first = r0
        s.contents = Array(maxOf(0, r1 - r0 + 1)) { drawable[r0 + it]!!.content }
        s.looks = IntArray(s.contents.size) { drawable[r0 + it]!!.look }
        return true
    }

    private fun squareTexture(): Int {
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glBindTexture(GL_TEXTURE_2D, ids[0])
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, SQUARE_SIDE, SQUARE_SIDE)
        if (GL_CHECKS) glGetError().let { if (it != GL_NO_ERROR) holes.failed(it) }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glBindTexture(GL_TEXTURE_2D, 0)
        return ids[0]
    }

    /** Lets go of the squares longest unused while there are [maxSquares] or more, but none used this frame. */
    private fun evictSquares() {
        if (squares.size < maxSquares) return
        val old = squares.entries.filter { it.value.used < frameNo }.sortedBy { it.value.used }
        for (e in old.take(squares.size - maxSquares + 1)) {
            glDeleteTextures(1, intArrayOf(e.value.tex), 0)
            squares.remove(e.key)
        }
    }

    /** What [checkHoles] has counted since it last logged. */
    private class Holes {
        /** Rows on screen the regions drawn don't hold, and frames with any. */
        var rows = 0
        var frames = 0
        /** Squares their regions' content alone would have kept, the regions no longer holding their tiles; checks, not squares. */
        var lacking = 0
        /** Square framebuffers that weren't complete. */
        var incomplete = 0
        /** GL errors, after an upload or a square's texture or once a second, and the last. */
        var errors = 0
        var error = 0
        var logged = 0L

        fun failed(e: Int) {
            errors++
            error = e
        }
    }
    private val holes = Holes()
    private var emptyChecked = 0L

    /**
     * Once a second, counts the tiles on screen that the regions drawn say they hold but have no piece of ground for,
     * as every tile has one; logged as `glempty`.
     */
    private fun checkEmpty(f: GlFrame) {
        val now = System.nanoTime()
        if (now - emptyChecked < 1_000_000_000L) return
        emptyChecked = now
        var tiles = 0
        var rows = 0
        for (vy in 0 until f.viewHeight) {
            val c0 = columns[2 * vy]
            val c1 = columns[2 * vy + 1]
            if (c0 > c1) continue
            val r = drawable.getOrNull(vy / REGION_ROWS) ?: continue
            if (!r.holds(vy, c0, c1)) continue
            var n = 0
            for (vx in c0..c1) if (r.start(0, vy, vx + 1) == r.start(0, vy, vx)) n++
            if (n > 0) rows++
            tiles += n
        }
        if (tiles > 0) Log.w(TAG, "glempty tiles=$tiles rows=$rows")
    }

    /**
     * Counts what could leave the screen blank, for a device run to show: rows on screen the regions drawn ([drawable])
     * don't hold, squares their regions no longer hold, square framebuffers that won't draw and GL errors; logged as
     * `glhole` at most twice a second, with [left], the squares the direct passes draw this frame.
     */
    private fun checkHoles(f: GlFrame, left: Int) {
        var rows = 0
        for (vy in 0 until f.viewHeight) {
            val c0 = columns[2 * vy]
            val c1 = columns[2 * vy + 1]
            if (c0 > c1) continue
            val r = drawable.getOrNull(vy / REGION_ROWS)
            if (r == null || !r.holds(vy, c0, c1)) rows++
        }
        if (rows > 0) {
            holes.rows += rows
            holes.frames++
        }
        if (frameNo % 60 == 0L) glGetError().let { if (it != GL_NO_ERROR) holes.failed(it) }
        if (holes.rows == 0 && holes.lacking == 0 && holes.incomplete == 0 && holes.errors == 0) return
        val now = System.nanoTime()
        if (now - holes.logged < 500_000_000L) return
        Log.w(TAG, "glhole rows=${holes.rows} frames=${holes.frames} lacking=${holes.lacking} fbo=${holes.incomplete} errors=${holes.errors} last=0x${holes.error.toString(16)} left=$left")
        holes.rows = 0
        holes.frames = 0
        holes.lacking = 0
        holes.incomplete = 0
        holes.errors = 0
        holes.logged = now
    }

    private fun freeSquares() {
        for (s in squares.values) glDeleteTextures(1, intArrayOf(s.tex), 0)
        squares.clear()
    }

    /** Draws the squares [on] that are drawn ahead and fresh or standing onto the screen; the direct passes draw the rest. */
    private fun drawSquares(f: GlFrame, lv: Int, tex: LevelTextures, on: IntArray) {
        glUseProgram(squareProgram)
        glBindVertexArray(squareVao)
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        glActiveTexture(GL_TEXTURE0)
        glUniform1i(glGetUniformLocation(squareProgram, "uTex"), 0)
        val rect = glGetUniformLocation(squareProgram, "uRect")
        for (cy in on[2]..on[3]) for (cx in on[0]..on[1]) {
            val s = squares[squareKey(f.turn, lv, cx, cy)] ?: continue
            if (!fresh(s, tex) && !stands(s, tex)) continue
            val x0 = squareEdge(f, lv, cx, false)
            val x1 = squareEdge(f, lv, cx + 1, false)
            val y0 = squareEdge(f, lv, cy, true)
            val y1 = squareEdge(f, lv, cy + 1, true)
            glBindTexture(GL_TEXTURE_2D, s.tex)
            glUniform4f(rect, 2f * x0 / width - 1f, 1f - 2f * y0 / height, 2f * x1 / width - 1f, 1f - 2f * y1 / height)
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        }
        glBindTexture(GL_TEXTURE_2D, 0)
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

    /** Puts [b]'s names, signs and payments over everything at [shown]'s view, at a fixed size in screen pixels. */
    private fun drawLabels(b: LabelBatch, shown: GlShown) {
        if (labelTex == 0 || b.gen != labelGen || b.count == 0) return
        if (labelQuads.size < b.count * LabelBatch.QUAD) labelQuads = FloatArray(b.count * LabelBatch.QUAD * 2)
        if (labelTaken.size < b.count * 4) labelTaken = FloatArray(b.count * 8)
        val n = b.place(shown.at, width, height, labelQuads, labelTaken)
        if (n == 0) return
        val bytes = n * LabelBatch.QUAD * 4
        if (labelBytes.capacity() < bytes) labelBytes = ByteBuffer.allocateDirect(bytes * 2).order(ByteOrder.nativeOrder())
        labelBytes.clear()
        labelBytes.asFloatBuffer().put(labelQuads, 0, n * LabelBatch.QUAD)
        labelBytes.position(0)
        glBindBuffer(GL_ARRAY_BUFFER, labelBuffer)
        glBufferData(GL_ARRAY_BUFFER, bytes, labelBytes, GL_STREAM_DRAW)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        val p = labelProgram
        glUseProgram(p)
        glBindVertexArray(labelVao)
        glUniform2f(glGetUniformLocation(p, "uScreen"), width.toFloat(), height.toFloat())
        glUniform1f(glGetUniformLocation(p, "uSide"), labelSide.toFloat())
        glUniform1i(glGetUniformLocation(p, "uTex"), 0)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, labelTex)
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, n)
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    /**
     * Puts [b]'s cloud shades and fog over the map at [shown]'s view, each an ellipse fading from its colour in the
     * middle to clear at its edge.
     */
    private fun drawShades(b: ShadeBatch, shown: GlShown) {
        if (shadePlaced.size < b.count * ShadeBatch.PLACED) shadePlaced = FloatArray(b.count * ShadeBatch.PLACED)
        val n = b.place(shown.at, width, height, shadePlaced)
        if (n == 0) return
        val p = shadeProgram
        glUseProgram(p)
        glBindVertexArray(toolVao)
        glUniform2f(glGetUniformLocation(p, "uScreen"), width.toFloat(), height.toFloat())
        val shape = glGetUniformLocation(p, "uShape")
        val colour = glGetUniformLocation(p, "uColour")
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        for (i in 0 until n) {
            val q = i * ShadeBatch.PLACED
            glUniform4f(shape, shadePlaced[q], shadePlaced[q + 1], shadePlaced[q + 2], shadePlaced[q + 3])
            glUniform4f(colour, shadePlaced[q + 4], shadePlaced[q + 5], shadePlaced[q + 6], shadePlaced[q + 7])
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    /** Puts the tool overlay image over the map, moved and scaled from where it was drawn to [shown]'s view. */
    private fun drawTool(shown: GlShown) {
        val up = toolUp ?: return
        if (toolTex == 0 || !up.place.mapTo(shown.at, width, height, toolRect)) return
        val p = toolProgram
        glUseProgram(p)
        glBindVertexArray(toolVao)
        glUniform2f(glGetUniformLocation(p, "uScreen"), width.toFloat(), height.toFloat())
        glUniform4f(glGetUniformLocation(p, "uRect"), toolRect[0], toolRect[1], toolRect[2], toolRect[3])
        glUniform1i(glGetUniformLocation(p, "uTex"), 0)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, toolTex)
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    /** Puts the newest tool overlay image [scene] has drawn onto its texture, made or remade for its size. */
    private fun takeTool() {
        val shot = scene.toolOverlay.take() ?: return
        try {
            val bitmap = shot.image.asAndroidBitmap()
            val w = bitmap.width
            val h = bitmap.height
            if (w > maxTexture || h > maxTexture) {
                Log.e(TAG, "gl tool overlay ${w}x$h past $maxTexture")
                toolUp = null
                return
            }
            while (glGetError() != GL_NO_ERROR) Unit
            if (toolTex == 0) {
                toolTex = IntArray(1).also { glGenTextures(1, it, 0) }[0]
                glBindTexture(GL_TEXTURE_2D, toolTex)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            } else glBindTexture(GL_TEXTURE_2D, toolTex)
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4)
            // Android bitmaps hold premultiplied pixels, which go up as they are.
            if (w != toolTexW || h != toolTexH) {
                GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0)
                toolTexW = w
                toolTexH = h
            } else GLUtils.texSubImage2D(GL_TEXTURE_2D, 0, 0, 0, bitmap)
            val error = glGetError()
            glBindTexture(GL_TEXTURE_2D, 0)
            if (error != GL_NO_ERROR) {
                Log.e(TAG, "gl tool overlay ${w}x$h failed: $error")
                glDeleteTextures(1, intArrayOf(toolTex), 0)
                toolTex = 0
                toolTexW = 0
                toolTexH = 0
                toolUp = null
                return
            }
            toolUp = shot
        } finally {
            scene.toolOverlay.taken(shot)
        }
    }

    /**
     * Copies the label images [scene] has placed since the last frame onto the label page, made the first time or
     * when it grows; only those for [b]'s page, so a page that started again is never drawn with an older batch.
     */
    private fun takeLabels(b: LabelBatch?) {
        b ?: return
        val taken = scene.labelAtlas.take(b.gen) ?: return
        val ups = taken.list
        if (labelTex == 0 || labelSide != taken.side) {
            if (labelTex != 0) glDeleteTextures(1, intArrayOf(labelTex), 0)
            labelGen = -1
            while (glGetError() != GL_NO_ERROR) Unit
            val side = taken.side
            labelSide = side
            labelTex = livePage(side)
            // Placed on whole pixels at their own size, so each texel lands on one pixel.
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
            val error = glGetError()
            if (error != GL_NO_ERROR) {
                Log.e(TAG, "gl label page $side failed: $error")
                glDeleteTextures(1, intArrayOf(labelTex), 0)
                labelTex = 0
                glBindTexture(GL_TEXTURE_2D, 0)
                // The images taken are dropped with it, so they're all placed and sent again.
                scene.labelAtlas.failed(side)
                return
            }
        }
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glBindTexture(GL_TEXTURE_2D, labelTex)
        for (u in ups) {
            val n = u.w * u.h
            if (strip.size < n * 4) strip = ByteArray(n * 4)
            val out = strip
            for (i in 0 until n) {
                val q = u.pixels[i]
                val a = q ushr 24
                out[i * 4] = (((q shr 16) and 255) * a / 255).toByte()
                out[i * 4 + 1] = (((q shr 8) and 255) * a / 255).toByte()
                out[i * 4 + 2] = ((q and 255) * a / 255).toByte()
                out[i * 4 + 3] = a.toByte()
            }
            if (spriteBytes.capacity() < n * 4) spriteBytes = ByteBuffer.allocateDirect(n * 8)
            spriteBytes.clear()
            spriteBytes.put(out, 0, n * 4).flip()
            glTexSubImage2D(GL_TEXTURE_2D, 0, u.x, u.y, u.w, u.h, GL_RGBA, GL_UNSIGNED_BYTE, spriteBytes)
        }
        glBindTexture(GL_TEXTURE_2D, 0)
        labelGen = taken.gen
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
            if (f[o].toInt() != LIVE_SPRITE && f[o].toInt() != LIVE_PIECE) continue
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
        // A premultiplied bitmap goes up a sprite at a time as it's held, with no sums on the CPU.
        val bitmap = u.image.asAndroidBitmap().takeIf { it.config == Bitmap.Config.ARGB_8888 && it.isPremultiplied }
        for (k in 0 until c.size / LiveAtlas.UP) {
            val o = k * LiveAtlas.UP
            val page = c[o + 4]
            if (page < 0) continue
            while (pages.size <= page) pages += livePage(side)
            val w = c[o + 2]
            val h = c[o + 3]
            if (bitmap != null) {
                if (w <= 0 || h <= 0) continue
                val one = Bitmap.createBitmap(bitmap, c[o], c[o + 1], w, h)
                glBindTexture(GL_TEXTURE_2D, pages[page])
                GLUtils.texSubImage2D(GL_TEXTURE_2D, 0, c[o + 5], c[o + 6], one)
                if (one !== bitmap) one.recycle()
                continue
            }
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
    private var strip = ByteArray(0)
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
        val p = groundFor()
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D_ARRAY, groundTex)
        glUniform1i(glGetUniformLocation(p, "uGround"), 1)
        // Unit 8 is past the live pages' units, so its sampler stays bound.
        glActiveTexture(GL_TEXTURE8)
        glBindTexture(GL_TEXTURE_2D_ARRAY, groundTex)
        glBindSampler(8, ragSampler)
        glUniform1i(glGetUniformLocation(p, "uRag"), 8)
        glActiveTexture(GL_TEXTURE3)
        glBindTexture(GL_TEXTURE_2D, legsTex)
        glUniform1i(glGetUniformLocation(p, "uLegs"), 3)
        glActiveTexture(GL_TEXTURE6)
        glBindTexture(GL_TEXTURE_2D, if (overlays and MapOverlays.CONDITION != 0) wearTex else 0)
        glUniform1i(glGetUniformLocation(p, "uWear"), 6)
        glUniform1i(glGetUniformLocation(p, "uOverlay"), overlays)
        glActiveTexture(GL_TEXTURE0)
        val w = weatherUp!!
        glUniform3i(glGetUniformLocation(p, "uMap"), w.width, w.height, f.turn)
        val legs = TileRecorder.TAXI_LEGS
        glUniform1iv(glGetUniformLocation(p, "uLegCount"), legs.size, IntArray(legs.size) { legs[it].size / 4 }, 0)
        val grades = TileRecorder.GRADES[look]
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
        val legs = TileRecorder.TAXI_LEGS
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

    private var weatherBytes = ByteBuffer.allocateDirect(0)

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
        // A few rows at a time through one buffer, as a direct buffer the size of the map is 4 MB of heap at 1024.
        val bytes = cw * GroundWeather.TEXEL
        if (weatherBytes.capacity() < WEATHER_ROWS * bytes) weatherBytes = ByteBuffer.allocateDirect(WEATHER_ROWS * bytes)
        while (y < ch) {
            if (w.rowVersions[y] <= since) { y++; continue }
            var e = y
            while (e < ch && e - y < WEATHER_ROWS && w.rowVersions[e] > since) e++
            val buf = weatherBytes
            buf.clear()
            buf.put(w.texels, y * bytes, (e - y) * bytes).flip()
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, y, cw, e - y, GL_RGBA_INTEGER, GL_UNSIGNED_BYTE, buf)
            rows += e - y
            y = e
        }
        glBindTexture(GL_TEXTURE_2D, 0)
        weatherUp = w
        Log.i(TAG, "gl weather v${w.version} rows=$rows of $ch")
    }

    /** Takes [w] into the wear texture, made again at a new map size. */
    private fun upload(w: TrackWear) {
        val whole = wearTex == 0 || wearUp?.let { it.width != w.width || it.height != w.height } != false
        if (whole) {
            if (wearTex != 0) glDeleteTextures(1, intArrayOf(wearTex), 0)
            val ids = IntArray(1)
            glGenTextures(1, ids, 0)
            wearTex = ids[0]
            glBindTexture(GL_TEXTURE_2D, wearTex)
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_R8UI, w.width, w.height)
            nearest(GL_TEXTURE_2D)
        } else glBindTexture(GL_TEXTURE_2D, wearTex)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        val buf = ByteBuffer.allocateDirect(w.bands.size)
        buf.put(w.bands).flip()
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w.width, w.height, GL_RED_INTEGER, GL_UNSIGNED_BYTE, buf)
        glBindTexture(GL_TEXTURE_2D, 0)
        wearUp = w
    }

    private fun nearest(target: Int) {
        glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(target, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    }

    /** Uploads [shown]'s regions not yet in a buffer, lets go of other turns', and lists those ready to draw in [drawable]. */
    private fun uploadRegions(shown: GlShown, columns: IntArray) {
        val turn = shown.frame.turn
        if (turn != bufferTurn || buffers.size != shown.regions.size) {
            freeBuffers()
            buffers = arrayOfNulls(shown.regions.size)
            bufferTurn = turn
        }
        if (drawable.size != shown.regions.size) drawable = arrayOfNulls(shown.regions.size)
        var off = 0
        var waiting = false
        for (i in shown.regions.indices) {
            val r = shown.regions[i]
            val b = buffers[i]
            // A region let go of, off round the screen, goes from the GPU too.
            if (r == null && b != null) {
                glDeleteBuffers(1, intArrayOf(b.id), 0)
                buffers[i] = null
            }
            if (r != null && b?.of(r) != true) {
                // Those on screen at once, the rest a few a frame, so a turn's first frame isn't held up by them all.
                if (onScreen(i, shown.frame.viewHeight, columns) || off++ < REGIONS_A_FRAME) {
                    if (b != null) glDeleteBuffers(1, intArrayOf(b.id), 0)
                    buffers[i] = Buffer(r, upload(r))
                } else waiting = true
            }
            // One waiting its turn to go up draws as it was till then.
            drawable[i] = if (r != null) buffers[i]?.region else null
        }
        if (waiting) scene.requestRender()
    }

    /** Whether region [i] has tiles in [columns], the view rows' ranges on screen. */
    private fun onScreen(i: Int, viewHeight: Int, columns: IntArray): Boolean {
        for (vy in i * REGION_ROWS until minOf(viewHeight, (i + 1) * REGION_ROWS)) if (columns[2 * vy] <= columns[2 * vy + 1]) return true
        return false
    }

    /**
     * Where a region's ints are sent from, kept and grown, so an upload allocates nothing; [packInts] only for a region
     * that keeps its record unpacked (tests). A direct buffer is on the Java heap on Android.
     */
    private var packInts = IntArray(0)
    private var packBytes = ByteBuffer.allocateDirect(0)

    private fun upload(r: RegionSprites): Int {
        val n = packedInts(r)
        val bytes = maxOf(1, n) * 4
        if (packBytes.capacity() < bytes) packBytes = ByteBuffer.allocateDirect(bytes + bytes / 4).order(ByteOrder.nativeOrder())
        packBytes.clear()
        val packed = r.packed
        if (packed != null) packBytes.asIntBuffer().put(packed, 0, n)
        else {
            if (packInts.size < n) packInts = IntArray(n + n / 4)
            packRegion(r, packInts)
            packBytes.asIntBuffer().put(packInts, 0, n)
        }
        packBytes.limit(bytes)
        val ids = IntArray(1)
        glGenBuffers(1, ids, 0)
        glBindBuffer(GL_ARRAY_BUFFER, ids[0])
        glBufferData(GL_ARRAY_BUFFER, bytes, packBytes, GL_STATIC_DRAW)
        if (GL_CHECKS) glGetError().let { if (it != GL_NO_ERROR) holes.failed(it) }
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        return ids[0]
    }

    /**
     * Uploads the levels [from] has that aren't on the GPU yet, lets go of those it's dropped and tells [scene] which
     * are in, or that they wouldn't go. Returns whether anything changed.
     */
    private fun takeLevels(from: Array<LevelSheets?>): Boolean {
        var changed = false
        while (glGetError() != GL_NO_ERROR) Unit
        for (l in levels.indices) {
            val want = from.getOrNull(l)
            val have = levels[l]
            if (have?.from === want) continue
            if (want != null && refused[l] === want) continue
            // Let go of after an upload to a context since lost; the scene unpacks them again.
            if (want != null && want.released) continue
            changed = true
            if (have != null) {
                glDeleteTextures(4, have.textures, 0)
                levels[l] = null
            }
            if (want != null) {
                // Too big for the GPU's textures, or out of memory for the buffers: the scene drops the level, or if it's
                // the screen's own, shows the cover that says the map can't be drawn.
                val t = try {
                    upload(l, want)
                } catch (e: RuntimeException) {
                    Log.e(TAG, "gl level $l won't fit: ${e.message}")
                    null
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "gl level $l out of memory")
                    null
                }
                val error = glGetError()
                if (t == null && error == GL_NO_ERROR && (want.released || want.static.isEmpty())) continue
                if (t == null || error != GL_NO_ERROR) {
                    if (error != GL_NO_ERROR) Log.e(TAG, "gl level $l upload failed, GL error 0x${error.toString(16)}")
                    if (t != null) glDeleteTextures(4, t.textures, 0)
                    while (glGetError() != GL_NO_ERROR) Unit
                    refused[l] = want
                    scene.uploadFailed(l)
                    continue
                }
                levels[l] = t
            }
            Log.i(TAG, "gl level $l ${if (want != null) "up" else "dropped"}, level textures ${levels.sumOf { it?.bytes ?: 0L } / 1_000_000} MB")
        }
        // What's really up, leaving out levels skipped, so the scene counts only those.
        if (changed) scene.uploaded(Array(levels.size) { levels[it]?.from })
        return changed
    }

    /**
     * Level [level]'s static sheet and shadows as texture arrays split to the GPU's largest texture, the static
     * sheet's sky-facing bits (the low two of blue) taken out into a texture of their own, its ground and its rows of
     * the cell tables. No mipmaps on the sprites: each level is drawn from half to twice its size.
     */
    private fun upload(level: Int, sheets: LevelSheets): LevelTextures? {
        building.clear()
        buildingFbo = 0
        try {
            return build(level, sheets).also { building.clear() }
        } catch (e: Throwable) {
            // Half-made: let go of what was made and put the bindings back for the frames that follow.
            if (building.isNotEmpty()) glDeleteTextures(building.size, building.toIntArray(), 0)
            if (buildingFbo != 0) glDeleteFramebuffers(1, intArrayOf(buildingFbo), 0)
            building.clear()
            buildingFbo = 0
            throw e
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            glUseProgram(0)
            glBindVertexArray(0)
            glViewport(0, 0, width, height)
        }
    }

    /** The textures and the framebuffer [upload] has made so far for the level it's on. */
    private val building = ArrayList<Int>()
    private var buildingFbo = 0

    private fun build(level: Int, sheets: LevelSheets): LevelTextures? {
        val t0 = System.nanoTime()
        // Taken before the released flag's read; the main thread may let go of them once they're up in a context since lost.
        val staticPixels = sheets.static
        val shadowPixels = sheets.shadows
        val groundPixels = sheets.ground
        if (sheets.released || staticPixels.isEmpty() || shadowPixels.isEmpty() || groundPixels.isEmpty()) return null
        val sp = AtlasPages(sheets.staticWidth, staticPixels.size / sheets.staticWidth, AtlasPages.tallestStatic(level), maxTexture)
        val hp = AtlasPages(sheets.shadowWidth, shadowPixels.size / sheets.shadowWidth, AtlasPages.tallestShadow(level), maxTexture)
        require(sp.layers <= maxLayers && hp.layers <= maxLayers) { "${maxOf(sp.layers, hp.layers)} layers, up to $maxLayers" }
        val (staticTex, up) = staticTextures(sp, staticPixels)
        val static = Paged(staticTex, sp)
        val shadows = Paged(pagedTexture(hp, GL_R8) { y0, rows, buf -> buf.put(shadowPixels, y0 * sheets.shadowWidth, rows * sheets.shadowWidth) }, hp)
        cellRow(staticCells, SpriteRecorder.STATIC, level, sp)
        cellRow(shadowCells, SpriteRecorder.SHADOW, level, hp)
        val ground = groundTexture(groundPixels)
        // The ground with its mipmaps, a third again.
        val bytes = sp.width.toLong() * sp.pageHeight * sp.layers * 5 + hp.width.toLong() * hp.pageHeight * hp.layers + groundPixels.size * 16L / 3
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
        building += ids[0]
        glBindTexture(GL_TEXTURE_2D_ARRAY, ids[0])
        val levels = 1 + (31 - Integer.numberOfLeadingZeros(size))
        glTexStorage3D(GL_TEXTURE_2D_ARRAY, levels, GL_RGBA8, size, size, layers)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4)
        // The ARGB ints go up as they are, blue byte first, and the swizzle reads them back as RGB, opaque.
        val buf = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
        val ints = buf.asIntBuffer()
        for (layer in 0 until layers) {
            ints.clear()
            ints.put(ground, layer * size * size, size * size)
            buf.position(0)
            glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, size, size, 1, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        }
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_SWIZZLE_R, GL_BLUE)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_SWIZZLE_B, GL_RED)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_SWIZZLE_A, GL_ONE)
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
        building += ids[0]
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

    /**
     * The static sheet [src] (ARGB) as texture arrays cut as [pages] says: premultiplied RGBA with the sky-facing bits
     * (the low two of blue) taken out, and those bits on their own as 0 to 255. The ints go up as they are, a strip of
     * rows at a time, and [CONVERT_FS] works both out on the GPU, the same sums the CPU would do.
     */
    private fun staticTextures(pages: AtlasPages, src: IntArray): Pair<Int, Int> {
        val w = pages.width
        val ids = IntArray(4)
        glGenTextures(3, ids, 0)
        for (i in 0 until 3) building += ids[i]
        val colour = ids[0]
        val up = ids[1]
        val raw = ids[2]
        glBindTexture(GL_TEXTURE_2D_ARRAY, colour)
        glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1, GL_RGBA8, w, pages.pageHeight, pages.layers)
        linear(GL_TEXTURE_2D_ARRAY)
        glBindTexture(GL_TEXTURE_2D_ARRAY, up)
        glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1, GL_R8, w, pages.pageHeight, pages.layers)
        linear(GL_TEXTURE_2D_ARRAY)
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0)
        glBindTexture(GL_TEXTURE_2D, raw)
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8UI, w, STRIP_ROWS)
        nearest(GL_TEXTURE_2D)
        glGenFramebuffers(1, ids, 3)
        buildingFbo = ids[3]
        glBindFramebuffer(GL_FRAMEBUFFER, ids[3])
        glDrawBuffers(2, intArrayOf(GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1), 0)
        glDisable(GL_BLEND)
        glUseProgram(convertProgram)
        glBindVertexArray(squareVao)
        // The whole viewport, a strip of a layer each draw.
        glUniform4f(glGetUniformLocation(convertProgram, "uRect"), -1f, -1f, 1f, 1f)
        glActiveTexture(GL_TEXTURE0)
        glUniform1i(glGetUniformLocation(convertProgram, "uRaw"), 0)
        val y0At = glGetUniformLocation(convertProgram, "uY0")
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4)
        val bytes = ByteBuffer.allocateDirect(w * STRIP_ROWS * 4).order(ByteOrder.nativeOrder())
        val ints = bytes.asIntBuffer()
        val zeros = IntArray(w * STRIP_ROWS)
        for (layer in 0 until pages.layers) {
            glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, colour, 0, layer)
            glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, up, 0, layer)
            val (first, rows) = pages.rowsOf(layer)
            var y = 0
            while (y < pages.pageHeight) {
                val n = minOf(STRIP_ROWS, pages.pageHeight - y)
                val real = (rows - y).coerceIn(0, n)
                ints.clear()
                if (real > 0) ints.put(src, (first + y) * w, real * w)
                // Rows past the sheet's end are clear.
                if (real < n) ints.put(zeros, 0, (n - real) * w)
                bytes.position(0)
                glBindTexture(GL_TEXTURE_2D, raw)
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, n, GL_RGBA_INTEGER, GL_UNSIGNED_BYTE, bytes)
                glViewport(0, y, w, n)
                glUniform1i(y0At, y)
                glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
                y += n
            }
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glDeleteFramebuffers(1, ids, 3)
        buildingFbo = 0
        glBindTexture(GL_TEXTURE_2D, 0)
        glDeleteTextures(1, ids, 2)
        glBindVertexArray(0)
        glViewport(0, 0, width, height)
        return colour to up
    }

    private fun linear(target: Int) {
        glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(target, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    }

    private fun freeBuffers() {
        for (b in buffers) if (b != null) glDeleteBuffers(1, intArrayOf(b.id), 0)
        buffers = arrayOfNulls(buffers.size)
    }

    /** Frees every texture, buffer and program, as the surface goes. */
    fun release() {
        freeBuffers()
        freeSquares()
        if (squareFbo != 0) glDeleteFramebuffers(1, intArrayOf(squareFbo), 0)
        squareFbo = 0
        if (squareVao != 0) glDeleteVertexArrays(1, intArrayOf(squareVao), 0)
        squareVao = 0
        if (squareProgram != 0) glDeleteProgram(squareProgram)
        squareProgram = 0
        if (convertProgram != 0) glDeleteProgram(convertProgram)
        convertProgram = 0
        for (p in livePages) {
            if (p.isNotEmpty()) glDeleteTextures(p.size, p.toIntArray(), 0)
            p.clear()
        }
        liveDone.clear()
        liveUp = null
        if (liveBuffer != 0) glDeleteBuffers(1, intArrayOf(liveBuffer), 0)
        liveBuffer = 0
        if (labelBuffer != 0) glDeleteBuffers(1, intArrayOf(labelBuffer), 0)
        labelBuffer = 0
        if (labelTex != 0) glDeleteTextures(1, intArrayOf(labelTex), 0)
        labelTex = 0
        labelGen = -1
        if (toolTex != 0) glDeleteTextures(1, intArrayOf(toolTex), 0)
        toolTex = 0
        toolTexW = 0
        toolTexH = 0
        toolUp = null
        for (l in levels) if (l != null) glDeleteTextures(4, l.textures, 0)
        levels.fill(null)
        for (t in intArrayOf(weatherTex, legsTex, staticCells, shadowCells, wearTex)) if (t != 0) glDeleteTextures(1, intArrayOf(t), 0)
        wearTex = 0
        wearUp = null
        staticCells = 0
        shadowCells = 0
        weatherTex = 0
        weatherUp = null
        legsTex = 0
        for (a in intArrayOf(vao, groundVao, liveVao, labelVao, toolVao)) if (a != 0) glDeleteVertexArrays(1, intArrayOf(a), 0)
        for (p in intArrayOf(spriteProgram, shadowProgram, groundProgram, overlayProgram, liveProgram, labelProgram, toolProgram, shadeProgram)) {
            if (p != 0) glDeleteProgram(p)
        }
        labelVao = 0
        labelProgram = 0
        toolVao = 0
        toolProgram = 0
        shadeProgram = 0
        vao = 0
        groundVao = 0
        liveVao = 0
        liveProgram = 0
        spriteProgram = 0
        shadowProgram = 0
        groundProgram = 0
        overlayProgram = 0
    }

    /** The ground program for this frame's [overlays]. */
    private fun groundFor(): Int {
        if (overlays == 0) return groundProgram
        if (overlayProgram == 0) overlayProgram = program(withOverlays(GROUND_VS), withOverlays(GROUND_FS))
        return overlayProgram
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
        /** Squares drawn ahead a frame while some on screen are missing, each a quarter of a screen's pixels at its level. */
        const val SQUARES_A_FRAME = 2

        /** Region buffers off screen uploaded a frame, at most. */
        const val REGIONS_A_FRAME = 2

        /** Weather rows sent at a time, 260 KB at 1024 wide. */
        const val WEATHER_ROWS = 64
    }
}

/** A label from the label page onto its screen rect (left, top, width, height in pixels), premultiplied, at its alpha. */
private const val LABEL_VS = """#version 300 es
precision highp float;
layout(location = 0) in vec4 aRect;
layout(location = 1) in vec4 aUv;
layout(location = 2) in float aAlpha;
uniform vec2 uScreen;
uniform float uSide;
out vec2 vUv;
flat out float vAlpha;
void main() {
    vec2 o = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    vec2 p = aRect.xy + o * aRect.zw;
    vUv = (aUv.xy + o * aUv.zw) / uSide;
    vAlpha = aAlpha;
    gl_Position = vec4(p.x / uScreen.x * 2.0 - 1.0, 1.0 - p.y / uScreen.y * 2.0, 0.0, 1.0);
}
"""

private const val LABEL_FS = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
in highp vec2 vUv;
flat in float vAlpha;
out vec4 frag;
void main() {
    frag = texture(uTex, vUv) * vAlpha;
}
"""

/** The tool overlay image onto its screen rect (left, top, right, bottom in pixels), premultiplied. */
private const val TOOL_VS = """#version 300 es
precision highp float;
uniform vec2 uScreen;
uniform vec4 uRect;
out vec2 vUv;
void main() {
    vec2 o = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    vec2 p = mix(uRect.xy, uRect.zw, o);
    vUv = o;
    gl_Position = vec4(p.x / uScreen.x * 2.0 - 1.0, 1.0 - p.y / uScreen.y * 2.0, 0.0, 1.0);
}
"""

private const val TOOL_FS = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
in highp vec2 vUv;
out vec4 frag;
void main() {
    frag = texture(uTex, vUv);
}
"""

/**
 * A cloud shade or fog bank: the box round an ellipse with centre uShape.xy and radii uShape.zw in screen pixels, with
 * vPos from -1 to 1 across it.
 */
private const val SHADE_VS = """#version 300 es
precision highp float;
uniform vec2 uScreen;
uniform vec4 uShape;
out vec2 vPos;
void main() {
    vec2 o = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1)) * 2.0 - 1.0;
    vec2 p = uShape.xy + o * uShape.zw;
    vPos = o;
    gl_Position = vec4(p.x / uScreen.x * 2.0 - 1.0, 1.0 - p.y / uScreen.y * 2.0, 0.0, 1.0);
}
"""

/** The shade's colour fading straight to clear from the middle to the edge of the ellipse, premultiplied. */
private const val SHADE_FS = """#version 300 es
precision mediump float;
uniform vec4 uColour;
in highp vec2 vPos;
out vec4 frag;
void main() {
    float a = uColour.a * max(0.0, 1.0 - length(vPos));
    frag = vec4(uColour.rgb * a, a);
}
"""

/** A square of the static map drawn ahead, onto its place on screen (clip space left, top, right, bottom). */
private const val SQUARE_VS = """#version 300 es
precision highp float;
uniform vec4 uRect;
out vec2 vUv;
void main() {
    vec2 o = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    // Its top row was drawn at the texture's top.
    vUv = vec2(o.x, 1.0 - o.y);
    // At the far depth, to keep the first pass drawn after it off.
    gl_Position = vec4(mix(uRect.xy, uRect.zw, o), 1.0, 1.0);
}
"""

private const val SQUARE_FS = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
in highp vec2 vUv;
out vec4 frag;
void main() {
    frag = texture(uTex, vUv);
}
"""

/**
 * Converts a strip of the static sheet, its ARGB ints read as RGBA8UI (blue byte first), into the sheet's texture
 * (premultiplied, the sky-facing bits out of blue) and the sky-facing bits' own, as the CPU did with integer sums.
 */
private const val CONVERT_FS = """#version 300 es
precision highp float;
precision highp int;
uniform highp usampler2D uRaw;
uniform int uY0;
layout(location = 0) out vec4 oColour;
layout(location = 1) out vec4 oUp;
void main() {
    uvec4 t = texelFetch(uRaw, ivec2(int(gl_FragCoord.x), int(gl_FragCoord.y) - uY0), 0);
    uint a = t.a;
    oColour = vec4(float(t.b * a / 255u), float(t.g * a / 255u), float((t.r & 252u) * a / 255u), float(a)) / 255.0;
    oUp = vec4(a == 0u ? 0.0 : float((t.r & 3u) * 85u) / 255.0, 0.0, 0.0, 0.0);
}
"""

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
    if (kind == 0 || kind == 3 || kind == 4) {
        p = mix(aA.xy, aA.zw, o);
        vUv = mix(aB.xy, aB.zw, o) / uSide;
        // Half a texel in, so smoothing never reaches past the sprite; a piece's whole sprite's rect is in aC.
        vec4 e = kind == 4 ? aC : aB;
        vEdge = vec4(e.xy + 0.5, e.zw - 0.5) / uSide;
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
    if (vKind == 0 || vKind == 4) {
        vec4 t = page(vUnit, clamp(vUv, vEdge.xy, vEdge.zw));
        frag = vKind == 4 ? t : vShadow > 0.5 ? vec4(0.0, 0.0, 0.0, t.a * vC.a) : t * vC;
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
