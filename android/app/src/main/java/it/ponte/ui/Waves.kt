package it.ponte.ui

import android.opengl.GLES30
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Onde a gradiente (porting del componente GradientWaves): un mare visto dall'alto in prospettiva, ray marching sulla superficie,
 * nebbia verso l'orizzonte e grana. I colori arrivano dalla copertina del brano. Disegnato a bassa risoluzione: è morbido per natura.
 */
private const val WAVES_VS = """#version 300 es
in vec2 aPosition;
void main() { gl_Position = vec4(aPosition, 0.0, 1.0); }"""

private const val WAVES_FS = """#version 300 es
precision highp float;
uniform vec2 uResolution;
uniform float uTime;
uniform float uClock;
uniform vec3 uHorizon;
uniform vec3 uWave;
uniform vec3 uCrest;
uniform vec3 uBackground;
uniform vec3 uSun;
uniform float uReveal;
out vec4 outColor;

const float AMP = 2.5;
const float SCALE = 0.6;
const float RATIO = 0.9;
const float SWELL = 35.0;
const float TURB = 20.0;
const float HEIGHT = 5.5;
const float TILT = 1.117;
const float FOG = 15.0;
const float RELIEF = 1.0;
const float GRAIN = 0.2;
const float FAR = 4000.0;

float hash(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

vec3 surface(vec2 xy) {
  vec2 freq = vec2(SCALE / 7.0, (SCALE * RATIO) / 3.0);
  vec4 tc = vec4(uTime / 0.130, uTime / 0.810, uTime / 0.200, uTime / 0.710);
  float a = xy.x + tc.x;
  float swellPhase = (xy.y + a) / 20.0 + tc.y;
  float bend = SWELL * cos(swellPhase) / 20.0;
  float mx = a + SWELL * sin(swellPhase);
  float crossPhase = xy.x / 23.0 + tc.w;
  float my = xy.y - tc.z + TURB * cos(crossPhase);
  float twist = -TURB * sin(crossPhase) / 23.0;
  float cx = cos(mx * freq.x);
  float cy = cos(my * freq.y);
  float h = (sin(mx * freq.x) + sin(my * freq.y)) * AMP + HEIGHT;
  float dx = AMP * (freq.x * cx * (1.0 + bend) + freq.y * cy * twist);
  float dy = AMP * (freq.x * cx * bend + freq.y * cy);
  return vec3(h, dx, dy);
}

float march(vec3 origin, vec3 dir, float stride) {
  float dist = 0.0;
  for (int i = 0; i < 64; i++) {
    float gap = origin.z + dist * dir.z - surface(origin.xy + dist * dir.xy).x;
    if (abs(gap) < 0.002 * dist + 0.01) break;
    dist += stride * gap;
    if (!(abs(dist) < FAR)) return FAR;
  }
  return dist;
}

void main() {
  float c; float s;
  float vfov = 3.14159 / 2.3;
  vec3 cam = vec3(0.0, 0.0, 30.0);
  vec2 uv = gl_FragCoord.xy / uResolution - 0.5;
  uv.x *= uResolution.x / uResolution.y;
  uv.y *= -1.0;
  vec3 dir = vec3(0.0, 0.0, -1.0);
  float ulen = length(uv);
  float xrot = vfov * ulen;
  c = cos(xrot); s = sin(xrot);
  dir = mat3(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c) * dir;
  vec2 nuv = ulen > 1e-5 ? uv / ulen : vec2(1.0, 0.0);
  dir = mat3(nuv.x, -nuv.y, 0.0, nuv.y, nuv.x, 0.0, 0.0, 0.0, 1.0) * dir;
  c = cos(TILT); s = sin(TILT);
  dir = mat3(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c) * dir;

  float slope = AMP * ((SCALE / 7.0) * (1.0 + SWELL / 20.0) + ((SCALE * RATIO) / 3.0) * (1.0 + TURB / 23.0));
  float stride = 0.9 / max(1.0, slope / 1.5);
  float dist = march(cam, dir, stride);
  vec3 pos = cam + dist * dir;
  vec3 field = surface(pos.xy);
  vec3 view = -dir;

  float crest = clamp((field.x - HEIGHT) / max(2.0 * AMP, 0.001) * 0.5 + 0.5, 0.0, 1.0);
  vec3 body = mix(uWave, uCrest, smoothstep(0.45, 1.0, crest) * 0.5);
  vec3 normal = normalize(vec3(-field.yz * (1.0 + 1.5 * RELIEF), 1.0));
  float diffuse = clamp(dot(normal, uSun) * 0.5 + 0.5, 0.0, 1.0);
  float lightness = mix(1.0, diffuse * diffuse * (3.0 - 2.0 * diffuse), RELIEF);
  float rim = pow(1.0 - clamp(dot(normal, view), 0.0, 1.0), 3.0);
  float haze = 1.0 - exp(-dist / (FOG * 5.0));
  float fade = 1.0 - smoothstep(0.35, 1.0, haze);
  vec3 lit = mix(uHorizon * 0.18, body, lightness);
  lit = mix(lit, uHorizon, rim * 0.15 * RELIEF);
  vec3 col = clamp(mix(lit, uHorizon, smoothstep(0.0, 1.0, haze)) * 0.85, 0.0, 1.0);
  float grain = (hash(gl_FragCoord.xy + fract(uClock) * 64.0) - 0.5) * GRAIN;
  float alpha = clamp(fade * (1.0 + grain), 0.0, 1.0) * uReveal;
  outColor = vec4(mix(uBackground, col, alpha), 1.0);
}"""

class WavesRenderer : GlScene {
    override var light: Boolean = false
    @Volatile var horizon = floatArrayOf(0.32f, 0.15f, 1f)
    @Volatile var wave = floatArrayOf(1f, 0.62f, 0.99f)
    @Volatile var background = floatArrayOf(0.02f, 0.02f, 0.04f)
    private var prog = 0
    private var vao = 0
    private var vbo = 0
    private var w = 1
    private var h = 1
    private var time = 0f
    private var clock = 0f
    private val loc = HashMap<String, Int>()

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) { android.util.Log.w("PonteGL", GLES30.glGetShaderInfoLog(s)); GLES30.glDeleteShader(s); return 0 }
        return s
    }

    override fun init(): Boolean {
        val v = compile(GLES30.GL_VERTEX_SHADER, WAVES_VS)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, WAVES_FS)
        if (v == 0 || f == 0) return false
        prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, v)
        GLES30.glAttachShader(prog, f)
        GLES30.glBindAttribLocation(prog, 0, "aPosition")
        GLES30.glLinkProgram(prog)
        GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { GLES30.glDeleteProgram(prog); prog = 0; return false }
        listOf("uResolution", "uTime", "uClock", "uHorizon", "uWave", "uCrest", "uBackground", "uSun", "uReveal").forEach { loc[it] = GLES30.glGetUniformLocation(prog, it) }
        val a = IntArray(1)
        GLES30.glGenVertexArrays(1, a, 0); vao = a[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glGenBuffers(1, a, 0); vbo = a[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        val data = floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)
        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data).also { it.position(0) }
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        return true
    }

    override fun resize(w: Int, h: Int) { this.w = w; this.h = h }

    override fun step(dt: Float, elapsed: Float) { time += dt * 0.4f; clock += dt }

    override fun render(cssW: Float, cssH: Float) {
        if (prog == 0) return
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(prog)
        GLES30.glBindVertexArray(vao)
        GLES30.glUniform2f(loc.getValue("uResolution"), w.toFloat(), h.toFloat())
        GLES30.glUniform1f(loc.getValue("uTime"), time)
        GLES30.glUniform1f(loc.getValue("uClock"), clock)
        GLES30.glUniform3fv(loc.getValue("uHorizon"), 1, horizon, 0)
        GLES30.glUniform3fv(loc.getValue("uWave"), 1, wave, 0)
        GLES30.glUniform3f(loc.getValue("uCrest"), 1f, 1f, 1f)
        GLES30.glUniform3fv(loc.getValue("uBackground"), 1, background, 0)
        val ang = Math.toRadians(120.0); val alt = 0.52
        GLES30.glUniform3f(loc.getValue("uSun"), (Math.cos(alt) * Math.cos(ang)).toFloat(), (Math.cos(alt) * Math.sin(ang)).toFloat(), Math.sin(alt).toFloat())
        GLES30.glUniform1f(loc.getValue("uReveal"), (clock / 1.6f).coerceIn(0f, 1f))
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    override fun dispose() {
        if (prog != 0) GLES30.glDeleteProgram(prog)
        if (vbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        prog = 0; vbo = 0; vao = 0
    }
}

private fun Color.rgb() = floatArrayOf(red, green, blue)

/** Sfondo a onde: [colors] = colori della copertina (se vuoti, quelli di base del componente). */
@Composable
fun WavesBackground(colors: List<Color>, background: Color, modifier: Modifier = Modifier) {
    val renderer = remember { WavesRenderer() }
    val view = remember { mutableStateOf<GlView?>(null) }
    LaunchedEffect(colors, background) {
        renderer.horizon = colors.getOrNull(0)?.rgb() ?: floatArrayOf(0.32f, 0.15f, 1f)
        renderer.wave = colors.getOrNull(1)?.rgb() ?: colors.getOrNull(0)?.let { Color(it.red * 0.6f + 0.4f, it.green * 0.6f + 0.4f, it.blue * 0.6f + 0.4f).rgb() } ?: floatArrayOf(1f, 0.62f, 0.99f)
        renderer.background = background.rgb()
    }
    @Suppress("DEPRECATION")
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    AndroidView(modifier = modifier, factory = { ctx -> GlView(ctx, renderer, 0.9f).also { view.value = it } }, onRelease = { it.release() })
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
