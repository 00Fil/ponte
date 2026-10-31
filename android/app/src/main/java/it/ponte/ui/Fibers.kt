package it.ponte.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.TextureView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * GhostFibers su Android: lo stesso componente (stessi shader GLSL ES 3.00, stessa logica di camera, bloom e
 * composizione) disegnato con OpenGL ES 3 su una TextureView, in un thread suo, senza WebView.
 * Si ferma quando l'app va in background.
 */
@Composable
fun FiberField(modifier: Modifier, band: Boolean = false, @Suppress("UNUSED_PARAMETER") centerY: Float = 0.5f, @Suppress("UNUSED_PARAMETER") spread: Float = 1f) {
    val dark = isSystemInDarkTheme()
    val view = remember { mutableStateOf<GlView?>(null) }
    @Suppress("DEPRECATION")
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    AndroidView(
        modifier = modifier,
        factory = { ctx -> GlView(ctx, FiberRenderer(band, !dark)).also { view.value = it } },
        onRelease = { it.release() },
    )
    LaunchedEffect(dark) { view.value?.setLight(!dark) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> view.value?.setRunning(false)
                Lifecycle.Event.ON_RESUME -> view.value?.setRunning(true)
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
}

/** Scena OpenGL ES 3 disegnata da [GlView]. */
interface GlScene {
    var light: Boolean
    fun init(): Boolean
    fun resize(w: Int, h: Int)
    fun step(dt: Float, elapsed: Float)
    fun render(cssW: Float, cssH: Float)
    fun dispose()
}

/** TextureView con un thread OpenGL suo, sincronizzato con lo schermo, che disegna una [GlScene]. */
class GlView(ctx: Context, private val renderer: GlScene, private val maxRatio: Float = 1.5f) : TextureView(ctx), TextureView.SurfaceTextureListener {
    private val thread = HandlerThread("ponte-fibre").apply { start() }
    private val handler = Handler(thread.looper)
    private var egl: Egl? = null
    @Volatile private var running = true
    private var scheduled = false
    private var cssW = 1f
    private var cssH = 1f
    private val density = ctx.resources.displayMetrics.density
    private var lastNanos = 0L
    private var surfW = 0
    private var surfH = 0

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun setLight(l: Boolean) = handler.post { renderer.light = l }

    fun setRunning(on: Boolean) = handler.post {
        running = on
        lastNanos = 0L
        if (on) schedule()
    }

    fun release() {
        running = false
        handler.post {
            renderer.dispose()
            egl?.destroy()
            egl = null
        }
        // Il thread chiude dopo aver liberato tutto (anche la superficie, se viene distrutta subito dopo).
        handler.postDelayed({ thread.quitSafely() }, 400)
    }

    private val frame = Choreographer.FrameCallback { now ->
        scheduled = false
        val e = egl ?: return@FrameCallback
        if (!running) return@FrameCallback
        val dt = if (lastNanos == 0L) 1f / 60f else ((now - lastNanos) / 1e9f)
        lastNanos = now
        // Si disegna sempre sulla dimensione reale della superficie: se il sistema ne tiene una diversa da quella chiesta
        // (succede quando la vista nasce durante una transizione) il disegno finirebbe in un angolo.
        val sz = e.size()
        if (sz[0] > 0 && sz[1] > 0 && (sz[0] != surfW || sz[1] != surfH)) {
            surfW = sz[0]; surfH = sz[1]
            renderer.resize(surfW, surfH)
        }
        renderer.step(min(dt, 0.05f), min(dt, 1f))
        renderer.render(cssW, cssH)
        e.swap()
        schedule()
    }

    private fun schedule() {
        if (scheduled || !running || egl == null) return
        scheduled = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun configure(st: SurfaceTexture, w: Int, h: Int) {
        // Risoluzione di disegno: al massimo 1,5 pixel per dp, come il componente con dpr limitato (meno lavoro, stesso aspetto).
        cssW = w / density
        cssH = h / density
        val ratio = min(density, maxRatio)
        val pw = max(1, (cssW * ratio).roundToInt())
        val ph = max(1, (cssH * ratio).roundToInt())
        st.setDefaultBufferSize(pw, ph)
        renderer.resize(pw, ph)
        surfW = 0 // alla prossima immagine si rilegge la dimensione effettiva
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        handler.post {
            val e = Egl(st)
            if (!e.ok || !renderer.init()) { e.destroy(); return@post }
            egl = e
            configure(st, w, h)
            schedule()
        }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        handler.post { if (egl != null) configure(st, w, h) }
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        val posted = handler.post {
            renderer.dispose()
            egl?.destroy()
            egl = null
            done.countDown()
        }
        // Si aspetta il thread solo se è ancora vivo, e per poco: la superficie va liberata dopo il contesto.
        if (posted) done.await(250, java.util.concurrent.TimeUnit.MILLISECONDS)
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}

/** Contesto EGL per OpenGL ES 3 su una SurfaceTexture. */
private class Egl(st: SurfaceTexture) {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    var ok = false

    init {
        val v = IntArray(2)
        if (EGL14.eglInitialize(display, v, 0, v, 1)) {
            val attrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT_KHR */, EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            if (EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) && n[0] > 0) {
                val cfg = configs[0]
                context = EGL14.eglCreateContext(display, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
                surface = EGL14.eglCreateWindowSurface(display, cfg, st, intArrayOf(EGL14.EGL_NONE), 0)
                ok = context != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE &&
                    EGL14.eglMakeCurrent(display, surface, surface, context)
                EGL14.eglSwapInterval(display, 1)
            }
        }
    }

    fun swap() { EGL14.eglSwapBuffers(display, surface) }

    private val dim = IntArray(2)
    private val one = IntArray(1)

    /** Larghezza e altezza reali della superficie in pixel. */
    fun size(): IntArray {
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, one, 0); dim[0] = one[0]
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, one, 0); dim[1] = one[0]
        return dim
    }

    fun destroy() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        surface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
    }
}

/**
 * Traduzione diretta della parte JavaScript di GhostFibers (render e frame), con i parametri di Ponte
 * (gli stessi di Windows): `band` per la fascia dietro i titoli, altrimenti la forma grande.
 */
internal class FiberRenderer(private val band: Boolean, @Volatile override var light: Boolean) : GlScene {
    private class Prog(val id: Int, val u: Map<String, Int>)
    private class Target { var tex = 0; var fb = 0; var w = 0; var h = 0 }

    // Impostazioni (come shared/gradient.html)
    private val speed = if (band) 0.35f else 0.45f
    private val scale = if (band) 1.6f else 1f
    private val rotation = (if (band) 3f else 16f) * DEG
    private val rollSpeed = 0.12f
    private val layers = if (band) 2 else 3
    private val waves = if (band) 0.35f else 1f
    private val twist = 1.1f
    private val threads = if (band) 48 else 72
    private val volume = 0.7f
    private val spread = if (band) 0.45f else 1f
    private val density = if (band) 0.7f else 1f
    private val thickness = 1f
    private val flowRate = 0.35f
    private val perspective = if (band) 0.35f else 0.65f
    private val dof = 0.85f
    private val particles = if (band) 0.25f else 0.5f
    private val sparkle = 0.5f
    private val glow = 0.8f
    private val brightness = 1.15f
    private val vignette = if (band) 0.5f else 0.3f
    private val grain = 0.05f

    private var time = 0f
    private var roll = 0f
    private var flow = 0f
    private var frame = 0
    private var intro = 0f
    private var pw = 1
    private var ph = 1

    private var dots: Prog? = null
    private var dust: Prog? = null
    private var bright: Prog? = null
    private var down: Prog? = null
    private var up: Prog? = null
    private var composite: Prog? = null
    private val scene = Target()
    private val chain = Array(BLOOM_LEVELS) { Target() }
    private var vao = 0
    private var format = GLES30.GL_RGBA8

    override fun init(): Boolean {
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        format = if (ext.contains("GL_EXT_color_buffer_float") || ext.contains("GL_EXT_color_buffer_half_float")) GLES30.GL_RGBA16F else GLES30.GL_RGBA8
        dots = link(FiberShaders.DOT_VERTEX, FiberShaders.DOT_FRAGMENT, DOT_U)
        dust = link(FiberShaders.DUST_VERTEX, FiberShaders.DOT_FRAGMENT, DUST_U)
        bright = link(FiberShaders.FULLSCREEN, FiberShaders.BRIGHT_FRAGMENT, PASS_U)
        down = link(FiberShaders.FULLSCREEN, FiberShaders.DOWN_FRAGMENT, PASS_U)
        up = link(FiberShaders.FULLSCREEN, FiberShaders.UP_FRAGMENT, PASS_U)
        composite = link(FiberShaders.FULLSCREEN, FiberShaders.COMPOSITE_FRAGMENT, COMP_U)
        if (dots == null || dust == null || bright == null || down == null || up == null || composite == null) {
            if (format == GLES30.GL_RGBA16F) android.util.Log.w("PonteGL", "shader non compilati")
            return false
        }
        val a = IntArray(1)
        GLES30.glGenVertexArrays(1, a, 0)
        vao = a[0]
        return true
    }

    override fun dispose() {
        listOf(dots, dust, bright, down, up, composite).forEach { it?.let { p -> GLES30.glDeleteProgram(p.id) } }
        dots = null
        (listOf(scene) + chain).forEach { t ->
            if (t.tex != 0) GLES30.glDeleteTextures(1, intArrayOf(t.tex), 0)
            if (t.fb != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(t.fb), 0)
            t.tex = 0; t.fb = 0; t.w = 0; t.h = 0
        }
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        vao = 0
    }

    override fun resize(w: Int, h: Int) { pw = w; ph = h }

    override fun step(dt: Float, elapsed: Float) {
        frame += 1
        time += dt * speed
        roll += dt * speed * rollSpeed
        flow += dt * flowRate * 12f
        intro += elapsed
    }

    override fun render(cssW: Float, cssH: Float) {
        val dp = dots ?: return
        val fit = (cssW / cssH / 1.5f).coerceIn(0.6f, 1f)
        val ratio = ph / max(cssH, 1f)
        val introT = (intro / INTRO_SECONDS).coerceIn(0f, 1f)
        val introEase = 1f - (1f - introT).pow(3)

        // camera
        val aspect = cssW / max(cssH, 1f)
        val proj = frustum(FOV, aspect, 0.1f, 60f)
        val view = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, -CAMERA, 1f)
        val viewProj = multiply(proj, view)
        val orient = orientation(perspective * 0.85f, 0.12f, rotation)
        val focus = CAMERA

        val pal = if (light) LIGHT else DARK

        allocate(scene, pw, ph)
        GLES30.glBindVertexArray(vao)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, scene.fb)
        GLES30.glViewport(0, 0, pw, ph)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)

        val dotsPerFiber = (260 * density).roundToInt()
        val blurCap = 22f * sqrt(56000f / (threads * dotsPerFiber * layers)).coerceIn(0.45f, 1f)
        val area = max(0.8f * thickness, 0.45f).pow(2) / 0.64f
        val load = ((threads / 72f) * density * area).pow(-0.75f)
        GLES30.glUseProgram(dp.id)
        val u = dp.u
        f1(u, "uTime", time); f1(u, "uTwist", twist); f1(u, "uRoll", roll); f1(u, "uWaves", waves)
        f1(u, "uWidth", 0.62f * spread)
        GLES30.glUniformMatrix3fv(u.getValue("uOrient"), 1, false, orient, 0)
        f1(u, "uScale", scale * fit)
        GLES30.glUniformMatrix4fv(u.getValue("uViewProj"), 1, false, viewProj, 0)
        GLES30.glUniform3f(u.getValue("uEye"), 0f, 0f, CAMERA)
        GLES30.glUniform2f(u.getValue("uViewport"), pw.toFloat(), ph.toFloat())
        f1(u, "uDpr", ratio); f1(u, "uFibers", threads.toFloat()); f1(u, "uDots", dotsPerFiber.toFloat())
        f1(u, "uFlow", flow); f1(u, "uSize", 1.6f * thickness); f1(u, "uFocus", focus); f1(u, "uDof", dof)
        f1(u, "uMaxCoc", blurCap); f1(u, "uFocal", CAMERA); f1(u, "uIntro", introEase)
        GLES30.glUniform4f(u.getValue("uPulse"), 0f, 9f, 0f, 0f)
        GLES30.glUniform4f(u.getValue("uHover"), 0f, 0f, 140f * ratio, 0f)
        f1(u, "uPart", 0f); f1(u, "uDotLength", 0f); f1(u, "uSparkle", sparkle); f1(u, "uDepthSpread", 0.12f)
        f1(u, "uVolume", volume)
        GLES30.glUniform3fv(u.getValue("uGlowColor"), 1, pal[0], 0)
        GLES30.glUniform3fv(u.getValue("uLineColor"), 1, pal[2], 0)
        GLES30.glUniform3fv(u.getValue("uSecondaryColor"), 1, pal[1], 0)
        f1(u, "uTips", 0f); f1(u, "uGradient", 1f); f1(u, "uFold", 1f)
        for (ribbon in 0 until layers) {
            val p = PLACEMENTS[ribbon]
            f1(u, "uRibbon", ribbon.toFloat())
            GLES30.glUniform4f(u.getValue("uPlace"), p[0], p[1], p[2], p[3])
            f1(u, "uGain", RIBBON_GAIN[ribbon] * load)
            f1(u, "uTint", RIBBON_TINT[ribbon])
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, threads * dotsPerFiber)
        }

        val dustCount = (420 * particles).roundToInt()
        if (dustCount > 0) {
            val d = dust!!
            GLES30.glUseProgram(d.id)
            val v = d.u
            GLES30.glUniformMatrix4fv(v.getValue("uViewProj"), 1, false, viewProj, 0)
            GLES30.glUniform2f(v.getValue("uViewport"), pw.toFloat(), ph.toFloat())
            f1(v, "uDpr", ratio); f1(v, "uTime", time); f1(v, "uSize", 2.2f * thickness); f1(v, "uFocus", focus)
            f1(v, "uDof", dof); f1(v, "uMaxCoc", 22f); f1(v, "uFocal", CAMERA); f1(v, "uIntro", introEase)
            f1(v, "uSparkle", sparkle); f1(v, "uScale", scale * fit); f1(v, "uGradient", 1f); f1(v, "uGain", 1f / thickness)
            GLES30.glUniform3fv(v.getValue("uGlowColor"), 1, pal[0], 0)
            GLES30.glUniform3fv(v.getValue("uLineColor"), 1, pal[2], 0)
            GLES30.glUniform3fv(v.getValue("uSecondaryColor"), 1, pal[1], 0)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, dustCount)
        }
        GLES30.glDisable(GLES30.GL_BLEND)

        val blooming = glow > 0.001f && !light
        if (blooming) {
            chain.forEachIndexed { i, t -> allocate(t, max(1, (pw / 2.0.pow(i + 1)).roundToInt()), max(1, (ph / 2.0.pow(i + 1)).roundToInt())) }
            pass(bright!!, chain[0], scene, 0.5f / scene.w, 0.5f / scene.h)
            for (i in 1 until BLOOM_LEVELS) pass(down!!, chain[i], chain[i - 1], 1f / chain[i - 1].w, 1f / chain[i - 1].h)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
            for (i in BLOOM_LEVELS - 1 downTo 1) pass(up!!, chain[i - 1], chain[i], 0.5f / chain[i].w, 0.5f / chain[i].h)
            GLES30.glDisable(GLES30.GL_BLEND)
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, pw, ph)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val c = composite!!
        GLES30.glUseProgram(c.id)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, scene.tex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (blooming) chain[0].tex else scene.tex)
        GLES30.glUniform1i(c.u.getValue("uScene"), 0)
        GLES30.glUniform1i(c.u.getValue("uBloom"), 1)
        f1(c.u, "uGlow", if (blooming) glow else 0f)
        f1(c.u, "uBrightness", brightness)
        f1(c.u, "uLightMode", if (light) 1f else 0f)
        f1(c.u, "uGrain", grain)
        f1(c.u, "uVignette", vignette)
        f1(c.u, "uFrame", (frame % 977).toFloat())
        GLES30.glUniform2f(c.u.getValue("uView"), pw.toFloat(), ph.toFloat())
        f1(c.u, "uDpr", ratio)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glBindVertexArray(0)
    }

    private fun f1(u: Map<String, Int>, name: String, v: Float) { GLES30.glUniform1f(u.getValue(name), v) }

    private fun pass(p: Prog, target: Target, source: Target, tx: Float, ty: Float) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.fb)
        GLES30.glViewport(0, 0, target.w, target.h)
        GLES30.glUseProgram(p.id)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, source.tex)
        GLES30.glUniform1i(p.u.getValue("uSource"), 0)
        GLES30.glUniform2f(p.u.getValue("uTexel"), tx, ty)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun allocate(t: Target, w: Int, h: Int) {
        if (t.tex != 0 && t.w == w && t.h == h) return
        if (t.tex != 0) GLES30.glDeleteTextures(1, intArrayOf(t.tex), 0)
        if (t.fb == 0) { val a = IntArray(1); GLES30.glGenFramebuffers(1, a, 0); t.fb = a[0] }
        val a = IntArray(1)
        GLES30.glGenTextures(1, a, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, a[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, format, w, h)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, t.fb)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, a[0], 0)
        // Se il formato a mezza precisione non è renderizzabile su questo telefono, si ripiega su 8 bit.
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE && format != GLES30.GL_RGBA8) {
            GLES30.glDeleteTextures(1, a, 0)
            format = GLES30.GL_RGBA8
            t.tex = 0
            allocate(t, w, h)
            return
        }
        t.tex = a[0]; t.w = w; t.h = h
    }

    private fun link(vs: String, fs: String, names: List<String>): Prog? {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) { android.util.Log.w("PonteGL", GLES30.glGetShaderInfoLog(s)); GLES30.glDeleteShader(s); return 0 }
            return s
        }
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        if (v == 0 || f == 0) return null
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { android.util.Log.w("PonteGL", GLES30.glGetProgramInfoLog(p)); GLES30.glDeleteProgram(p); return null }
        return Prog(p, names.associateWith { GLES30.glGetUniformLocation(p, it) })
    }

    companion object {
        const val BLOOM_LEVELS = 5
        const val INTRO_SECONDS = 2.6f
        const val CAMERA = 7.5f
        const val FOV = 0.56f
        const val DEG = (Math.PI / 180).toFloat()
        val RIBBON_TINT = floatArrayOf(0f, 0.75f, 0.35f)
        val RIBBON_GAIN = floatArrayOf(1f, 0.5f, 0.55f)
        val PLACEMENTS = arrayOf(floatArrayOf(0f, 0f, 0f, 0f), floatArrayOf(1.9f, -4.6f, -0.08f, 2.4f), floatArrayOf(-1.05f, 2.0f, 0.12f, 4.1f))
        private fun rgb(hex: Int) = floatArrayOf(((hex shr 16) and 255) / 255f, ((hex shr 8) and 255) / 255f, (hex and 255) / 255f)
        // glow, secondary, line: la palette di Ponte (come su Windows)
        val DARK = arrayOf(rgb(0x1E44FF), rgb(0x3F8CFF), rgb(0x9CC6FF))
        val LIGHT = arrayOf(rgb(0x2F63FF), rgb(0x6FB4FF), rgb(0x1F44CC))

        val SHAPE_U = listOf("uTime", "uTwist", "uRoll", "uWaves", "uWidth", "uRibbon", "uOrient", "uScale", "uPlace", "uVolume")
        val DOT_U = SHAPE_U + listOf(
            "uViewProj", "uEye", "uViewport", "uDpr", "uFibers", "uDots", "uFlow", "uSize", "uFocus", "uDof", "uMaxCoc", "uFocal",
            "uIntro", "uPulse", "uHover", "uPart", "uDotLength", "uSparkle", "uDepthSpread", "uGain", "uTips", "uTint", "uGradient",
            "uFold", "uGlowColor", "uLineColor", "uSecondaryColor",
        )
        val DUST_U = listOf(
            "uViewProj", "uViewport", "uDpr", "uTime", "uSize", "uFocus", "uDof", "uMaxCoc", "uFocal", "uIntro", "uSparkle", "uScale",
            "uGradient", "uGain", "uGlowColor", "uLineColor", "uSecondaryColor",
        )
        val PASS_U = listOf("uSource", "uTexel")
        val COMP_U = listOf("uScene", "uBloom", "uGlow", "uBrightness", "uLightMode", "uGrain", "uVignette", "uFrame", "uView", "uDpr")

        fun frustum(fov: Float, aspect: Float, near: Float, far: Float): FloatArray {
            val f = 1f / tan(fov / 2f)
            val nf = 1f / (near - far)
            return floatArrayOf(f / aspect, 0f, 0f, 0f, 0f, f, 0f, 0f, 0f, 0f, (far + near) * nf, -1f, 0f, 0f, 2f * far * near * nf, 0f)
        }

        fun multiply(a: FloatArray, b: FloatArray): FloatArray {
            val out = FloatArray(16)
            for (col in 0 until 4) for (row in 0 until 4) {
                out[col * 4 + row] = a[row] * b[col * 4] + a[4 + row] * b[col * 4 + 1] + a[8 + row] * b[col * 4 + 2] + a[12 + row] * b[col * 4 + 3]
            }
            return out
        }

        fun orientation(yaw: Float, pitch: Float, roll: Float): FloatArray {
            val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch); val cr = cos(roll); val sr = sin(roll)
            val ry = floatArrayOf(cy, 0f, -sy, 0f, 1f, 0f, sy, 0f, cy)
            val rx = floatArrayOf(1f, 0f, 0f, 0f, cp, sp, 0f, -sp, cp)
            val rz = floatArrayOf(cr, sr, 0f, -sr, cr, 0f, 0f, 0f, 1f)
            fun mul(a: FloatArray, b: FloatArray): FloatArray {
                val out = FloatArray(9)
                for (col in 0 until 3) for (row in 0 until 3) out[col * 3 + row] = a[row] * b[col * 3] + a[3 + row] * b[col * 3 + 1] + a[6 + row] * b[col * 3 + 2]
                return out
            }
            return mul(rz, mul(rx, ry))
        }
    }
}

