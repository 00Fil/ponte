package it.ponte.ui

import android.opengl.GLES30
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
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * MicroSlats (preset "swell") su Android: lo stesso shader del componente, in OpenGL ES 3.
 * Lamelle arrotondate che si accendono come onde di luce, nei blu di Ponte. Sfondo trasparente: sotto c'è la scheda.
 */
@Composable
fun SlatField(modifier: Modifier, onBrand: Boolean = false) {
    val dark = isSystemInDarkTheme()
    val view = remember { mutableStateOf<GlView?>(null) }
    @Suppress("DEPRECATION")
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    AndroidView(
        modifier = modifier,
        factory = { ctx -> GlView(ctx, SlatRenderer(!dark, onBrand), maxRatio = 2f).also { view.value = it } },
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

internal class SlatRenderer(@Volatile override var light: Boolean, private val onBrand: Boolean = false) : GlScene {
    // preset swell
    private val scale = 1.5f
    private val speed = 0.6f
    private val direction = 250f * (Math.PI.toFloat() / 180f)
    private val chop = 0.55f
    private val stretch = 0f
    private val glint = 0.7f
    private val contrast = 1.25f
    private val perspective = 0.55f
    private val fog = 0.55f
    private val slatW = 10f
    private val slatH = 25f
    private val gap = 3f
    private val roundness = 0.75f
    private val introDuration = 1.5f

    private var time = 0f
    private var introClock = 0f
    private var pw = 1
    private var ph = 1
    private var field = 0
    private var slat = 0
    private var vao = 0
    private var blank = 0
    private var fieldTex = 0
    private var fieldFb = 0
    private var cols = 0
    private var rows = 0
    private val uf = HashMap<String, Int>()
    private val us = HashMap<String, Int>()

    override fun init(): Boolean {
        field = link(VERTEX, FIELD, uf, listOf("uSize", "uGrid", "uOrigin", "uSlat", "uTime", "uScale", "uDirection", "uChop", "uStretch", "uGlint",
            "uContrast", "uPerspective", "uFog", "uIntro", "tVelocity", "tInk", "uFluidTexel", "uFluid", "uInk", "uLean"))
        slat = link(VERTEX, SLAT, us, listOf("tField", "uSize", "uDpr", "uGrid", "uOrigin", "uSlat", "uRound", "uTilt", "uColor", "uGlintColor", "uBackground"))
        if (field == 0 || slat == 0) return false
        val a = IntArray(1)
        GLES30.glGenVertexArrays(1, a, 0); vao = a[0]
        GLES30.glGenTextures(1, a, 0); blank = a[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blank)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, java.nio.ByteBuffer.allocateDirect(4))
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glGenFramebuffers(1, a, 0); fieldFb = a[0]
        return true
    }

    override fun dispose() {
        if (field != 0) GLES30.glDeleteProgram(field)
        if (slat != 0) GLES30.glDeleteProgram(slat)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        if (blank != 0) GLES30.glDeleteTextures(1, intArrayOf(blank), 0)
        if (fieldTex != 0) GLES30.glDeleteTextures(1, intArrayOf(fieldTex), 0)
        if (fieldFb != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fieldFb), 0)
        field = 0; slat = 0; vao = 0; blank = 0; fieldTex = 0; fieldFb = 0; cols = 0; rows = 0
    }

    override fun resize(w: Int, h: Int) { pw = w; ph = h }

    override fun step(dt: Float, elapsed: Float) {
        time += dt * speed
        introClock += dt / max(0.2f, introDuration)
    }

    override fun render(cssW: Float, cssH: Float) {
        if (field == 0) return
        val il = min(introClock, 1f)
        val introEase = if (il < 0.5f) 4f * il * il * il else 1f - (-2f * il + 2f).pow(3) / 2f

        val pitchX = slatW + gap
        val pitchY = slatH + gap
        val c = min(2048, ceil((cssW + gap) / pitchX).toInt() + 1)
        val r = min(2048, ceil((cssH + gap) / pitchY).toInt() + 1)
        val ox = (cssW - (c * pitchX - gap)) / 2f
        val oy = (cssH - (r * pitchY - gap)) / 2f
        if (c != cols || r != rows) {
            if (fieldTex != 0) GLES30.glDeleteTextures(1, intArrayOf(fieldTex), 0)
            val a = IntArray(1)
            GLES30.glGenTextures(1, a, 0); fieldTex = a[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fieldTex)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, c, r)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fieldFb)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fieldTex, 0)
            cols = c; rows = r
        }
        GLES30.glBindVertexArray(vao)
        GLES30.glDisable(GLES30.GL_BLEND)

        // 1) campo: un texel per lamella
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fieldFb)
        GLES30.glViewport(0, 0, cols, rows)
        GLES30.glUseProgram(field)
        GLES30.glUniform2f(uf.getValue("uSize"), cssW, cssH)
        GLES30.glUniform4f(uf.getValue("uGrid"), cols.toFloat(), rows.toFloat(), pitchX, pitchY)
        GLES30.glUniform2f(uf.getValue("uOrigin"), ox, oy)
        GLES30.glUniform2f(uf.getValue("uSlat"), slatW, slatH)
        GLES30.glUniform1f(uf.getValue("uTime"), time)
        GLES30.glUniform1f(uf.getValue("uScale"), scale)
        GLES30.glUniform1f(uf.getValue("uDirection"), direction)
        GLES30.glUniform1f(uf.getValue("uChop"), chop)
        GLES30.glUniform1f(uf.getValue("uStretch"), stretch)
        GLES30.glUniform1f(uf.getValue("uGlint"), glint)
        GLES30.glUniform1f(uf.getValue("uContrast"), contrast)
        GLES30.glUniform1f(uf.getValue("uPerspective"), perspective)
        GLES30.glUniform1f(uf.getValue("uFog"), fog)
        GLES30.glUniform1f(uf.getValue("uIntro"), introEase)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blank)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blank)
        GLES30.glUniform1i(uf.getValue("tVelocity"), 0)
        GLES30.glUniform1i(uf.getValue("tInk"), 1)
        GLES30.glUniform2f(uf.getValue("uFluidTexel"), 1f, 1f)
        GLES30.glUniform1f(uf.getValue("uFluid"), 0f)
        GLES30.glUniform1f(uf.getValue("uInk"), 1f)
        GLES30.glUniform1f(uf.getValue("uLean"), 0f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        // 2) lamelle sullo schermo
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, pw, ph)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(slat)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fieldTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glUniform1i(us.getValue("tField"), 0)
        GLES30.glUniform2f(us.getValue("uSize"), cssW, cssH)
        GLES30.glUniform1f(us.getValue("uDpr"), ph / max(cssH, 1f))
        GLES30.glUniform4f(us.getValue("uGrid"), cols.toFloat(), rows.toFloat(), pitchX, pitchY)
        GLES30.glUniform2f(us.getValue("uOrigin"), ox, oy)
        GLES30.glUniform2f(us.getValue("uSlat"), slatW, slatH)
        GLES30.glUniform1f(us.getValue("uRound"), roundness)
        GLES30.glUniform1f(us.getValue("uTilt"), 0f)
        val col = if (onBrand) rgb(0xA9CFFF) else if (light) rgb(0x2F5FFF) else rgb(0x5C9DFF)
        val gl = if (onBrand) rgb(0xFFFFFF) else if (light) rgb(0x1233C4) else rgb(0xCFE2FF)
        GLES30.glUniform3f(us.getValue("uColor"), col[0], col[1], col[2])
        GLES30.glUniform3f(us.getValue("uGlintColor"), gl[0], gl[1], gl[2])
        GLES30.glUniform4f(us.getValue("uBackground"), 0f, 0f, 0f, 0f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    private fun rgb(hex: Int) = floatArrayOf(((hex shr 16) and 255) / 255f, ((hex shr 8) and 255) / 255f, (hex and 255) / 255f)

    private fun link(vs: String, fs: String, out: HashMap<String, Int>, names: List<String>): Int {
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
        if (v == 0 || f == 0) return 0
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { android.util.Log.w("PonteGL", GLES30.glGetProgramInfoLog(p)); GLES30.glDeleteProgram(p); return 0 }
        for (n in names) out[n] = GLES30.glGetUniformLocation(p, n)
        return p
    }

    private companion object {
        const val VERTEX = """#version 300 es
void main() {
  vec2 p = vec2(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0);
  gl_Position = vec4(p, 0.0, 1.0);
}
"""
        const val FIELD = """#version 300 es
precision highp float;
uniform vec2 uSize;
uniform vec4 uGrid;
uniform vec2 uOrigin;
uniform vec2 uSlat;
uniform float uTime;
uniform float uScale;
uniform float uDirection;
uniform float uChop;
uniform float uStretch;
uniform float uGlint;
uniform float uContrast;
uniform float uPerspective;
uniform float uFog;
uniform float uIntro;
uniform sampler2D tVelocity;
uniform sampler2D tInk;
uniform vec2 uFluidTexel;
uniform float uFluid;
uniform float uInk;
uniform float uLean;
out vec4 fragColor;

const float TURN[4] = float[4](0.0, -0.95, 0.78, 1.62);
const float LENGTH[4] = float[4](2.6, 1.7, 1.12, 0.76);
const float HEIGHT[4] = float[4](1.0, 0.75, 0.5, 0.3);
const float SHIFT[4] = float[4](0.0, 2.3, 4.1, 1.1);

void main() {
  vec2 cell = floor(gl_FragCoord.xy);
  float row = uGrid.y - 1.0 - cell.y;
  vec2 center = uOrigin + vec2(cell.x, row) * uGrid.zw + uSlat * 0.5;
  float v = clamp(center.y / uSize.y, 0.0, 1.0);

  vec2 flow = vec2(0.0);
  float ink = 0.0;
  if (uFluid > 0.5) {
    vec2 fluidUv = clamp(vec2(center.x / uSize.x, 1.0 - center.y / uSize.y), 0.0, 1.0);
    flow = texture(tVelocity, fluidUv).xy * uFluidTexel * uSize;
    ink = texture(tInk, fluidUv).x;
  }

  float horizon = mix(8.0, 0.5, uPerspective);
  float depth = (1.0 + horizon) / (v + horizon);
  vec2 world = vec2((center.x - uSize.x * 0.5) / uSize.y * depth, (depth - 1.0) * horizon * 2.2);
  world -= flow * 0.00018 * depth;
  world /= max(uScale, 0.05);

  float meander = 0.55 * sin(dot(world, vec2(0.23, 0.41)) * 0.9 + uTime * 0.13)
    + 0.3 * sin(dot(world, vec2(-0.37, 0.19)) * 1.4 - uTime * 0.09);

  float steep = uChop * 0.45;
  float height = 0.0;
  float total = 0.0;
  for (int i = 0; i < 4; i++) {
    float angle = uDirection + TURN[i];
    vec2 heading = vec2(cos(angle), sin(angle));
    float k = 6.2831853 / LENGTH[i];
    float omega = sqrt(9.81 * k) * 0.35;
    float theta = k * dot(world, heading) - omega * uTime + SHIFT[i] + meander * (0.6 + 0.3 * float(i));
    height += HEIGHT[i] * (cos(theta) + steep * cos(2.0 * theta));
    total += HEIGHT[i] * (1.0 + steep);
  }
  float level = clamp(0.5 + 0.5 * height / (total * 0.85), 0.0, 1.0);
  float crest = smoothstep(0.6, 1.0, level);
  float glint = crest * crest * uGlint;

  float haze = mix(1.0, 1.0 - uFog, pow(1.0 - v, 1.4));
  float light = (pow(level, uContrast) + glint * 0.8) * haze;
  float glow = 1.0 - exp(-max(ink, 0.0) * uInk * 1.6);
  light = 1.0 - (1.0 - clamp(light, 0.0, 1.0)) * (1.0 - glow);

  float front = uIntro * 1.35;
  float reveal = 1.0 - smoothstep(front - 0.35, front, v);
  float sweep = exp(-pow((v - front + 0.22) / 0.07, 2.0)) * (1.0 - uIntro);
  light = (light + sweep * 0.8) * reveal;
  light = max(light, 0.045 * reveal);
  float span = mix(1.0 - uStretch, 1.0, level) + glow * 0.3;
  span = clamp(span, 0.08, 1.0) * mix(0.1, 1.0, reveal);
  float tint = clamp(max(glint * 1.4, glow * 0.9) + sweep, 0.0, 1.0);
  float lean = clamp(flow.x / 1400.0, -1.0, 1.0) * uLean * reveal;

  fragColor = vec4(clamp(light, 0.0, 1.0), span, tint, 0.5 + 0.5 * lean);
}
"""
        const val SLAT = """#version 300 es
precision highp float;
uniform sampler2D tField;
uniform vec2 uSize;
uniform float uDpr;
uniform vec4 uGrid;
uniform vec2 uOrigin;
uniform vec2 uSlat;
uniform float uRound;
uniform float uTilt;
uniform vec3 uColor;
uniform vec3 uGlintColor;
uniform vec4 uBackground;
out vec4 fragColor;

float pill(vec2 p, vec2 b, float r) {
  vec2 q = abs(p) - b + r;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
  vec2 p = vec2(gl_FragCoord.x, uSize.y * uDpr - gl_FragCoord.y) / uDpr;
  vec2 local = p - uOrigin;
  vec2 cell = floor(local / uGrid.zw);
  vec4 background = vec4(uBackground.rgb * uBackground.a, uBackground.a);
  vec4 slat = vec4(0.0);
  int reach = uTilt > 0.0 ? 1 : 0;
  for (int dx = -1; dx <= 1; dx++) {
    if (abs(dx) > reach) continue;
    vec2 neighbour = cell + vec2(float(dx), 0.0);
    if (neighbour.x < 0.0 || neighbour.y < 0.0 || neighbour.x >= uGrid.x || neighbour.y >= uGrid.y) continue;
    vec4 field = texelFetch(tField, ivec2(int(neighbour.x), int(uGrid.y - 1.0 - neighbour.y)), 0);
    vec2 offset = local - neighbour * uGrid.zw - uSlat * 0.5;
    float angle = (field.a - 0.5) * 2.0 * uTilt;
    float c = cos(angle);
    float s = sin(angle);
    vec2 turned = vec2(c * offset.x + s * offset.y, c * offset.y - s * offset.x);
    vec2 halfSize = vec2(uSlat.x * 0.5, uSlat.y * 0.5 * field.g);
    float radius = uRound * min(halfSize.x, halfSize.y);
    float alpha = clamp(0.5 - pill(turned, halfSize, radius) * uDpr, 0.0, 1.0) * field.r;
    if (alpha > slat.a) slat = vec4(mix(uColor, uGlintColor, field.b) * alpha, alpha);
  }
  fragColor = slat + background * (1.0 - slat.a);
}
"""
    }
}

