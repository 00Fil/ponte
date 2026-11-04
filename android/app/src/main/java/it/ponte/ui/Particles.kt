package it.ponte.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.withFrameNanos
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Nuvola di particelle in 3D che ruota piano, con prospettiva, scintillio e alone; un tocco manda un'onda.
 * Versione Compose dell'effetto "Particles" (WebGL): stessi parametri di base, disegnata su Canvas.
 */
@Composable
fun ParticlesField(
    modifier: Modifier = Modifier,
    count: Int = 220,
    spread: Float = 10f,
    speed: Float = 0.1f,
    colors: List<Color> = listOf(Color.White, Color(0xFF2F5FFF), Color(0xFFB4D2FF)),
    baseSize: Float = 100f,
    camera: Float = 20f,
    glow: Float = 0.5f,
    twinkle: Float = 0.3f,
) {
    val depth = 10f
    val focal = 1f / tan(Math.toRadians(7.5)).toFloat()
    // punti nella sfera (seme fisso: sempre la stessa nuvola)
    val cloud = remember(count) {
        var st = 1L
        fun next(): Float { st = (st * 6364136223846793005L + 1442695040888963407L); return ((st ushr 33) and 0x7fffffff).toFloat() / 0x7fffffff }
        List(count) {
            var x: Float; var y: Float; var z: Float; var l: Float
            do { x = next() * 2 - 1; y = next() * 2 - 1; z = next() * 2 - 1; l = x * x + y * y + z * z } while (l > 1f || l == 0f)
            val r = cbrt(next())
            floatArrayOf(x * r, y * r, z * r, next(), next(), next(), next(), (next() * colors.size).toInt().coerceAtMost(colors.size - 1).toFloat())
        }
    }
    var clock by remember { mutableFloatStateOf(0f) }
    var spin by remember { mutableFloatStateOf(0f) }
    var reveal by remember { mutableFloatStateOf(0f) }
    val bursts = remember { ArrayList<FloatArray>() } // x, y, età
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 1f / 60 else ((now - last) / 1e9f).coerceIn(0.0005f, 0.05f)
                last = now
                clock += dt * speed
                spin += dt * speed * 0.6f
                if (reveal < 1f) reveal = (reveal + dt / 1.8f).coerceAtMost(1f)
                bursts.forEach { it[2] += dt }
                bursts.removeAll { it[2] >= 1.5f }
            }
        }
    }
    Canvas(modifier.pointerInput(Unit) { detectTapGestures { o -> bursts.add(floatArrayOf(o.x, o.y, 0f)); if (bursts.size > 4) bursts.removeAt(0) } }) {
        val w = size.width; val h = size.height; val aspect = w / h
        val el = clock * 1000f
        val ax = sin(el * 0.0002f) * 0.1f; val ay = cos(el * 0.0005f) * 0.15f; val az = spin
        val sx = sin(ax); val cx = cos(ax); val sy = sin(ay); val cy = cos(ay); val sz = sin(az); val cz = cos(az)
        val m = floatArrayOf(cy * cz + sy * sx * sz, cx * sz, -sy * cz + cy * sx * sz, -cy * sz + sy * sx * cz, cx * cz, sy * sz + cy * sx * cz, sy * cx, -sx, cy * cx)
        val oz = -(1f - (1f - (1f - reveal).pow(3))) * 70f
        val dens = density
        for (p in cloud) {
            val px = p[0] * spread; val py = p[1] * spread; val pz = p[2] * spread * depth
            val r0 = p[3]; val r1 = p[4]; val r2 = p[5]; val r3 = p[6]
            val bx = sin(clock * r2 + 6.28f * r3) * (0.1f + 1.4f * r0)
            val by = sin(clock * r1 + 6.28f * r0) * (0.1f + 1.4f * r3)
            val bz = sin(clock * r3 + 6.28f * r1) * (0.1f + 1.4f * r2)
            val wx = m[0] * px + m[3] * py + m[6] * pz + bx
            val wy = m[1] * px + m[4] * py + m[7] * pz + by
            val wz = m[2] * px + m[5] * py + m[8] * pz + oz + bz
            val ahead = camera - wz
            if (ahead < 0.1f || ahead > 100f) continue
            val dist = hypot(hypot(wx, wy), ahead)
            var x = (wx * focal / aspect / ahead) * 0.5f * w + w / 2
            var y = h / 2 - (wy * focal / ahead) * 0.5f * h
            var flash = 0f
            for (b in bursts) {
                val prog = b[2] / 1.5f
                val ex = x - b[0]; val ey = y - b[1]; val reach = hypot(ex, ey)
                if (reach < 0.001f) continue
                val wave = exp(-((reach - prog * 520f * dens / 2.5f) / (46f * dens / 2.5f)).pow(2))
                val push = wave * (1 - prog) * (1 - prog) * 56f * dens / 2.5f
                x += ex / reach * push; y += ey / reach * push
                flash += wave * (1 - prog) * (1 - prog)
            }
            val close = ((ahead - 1f) / 3f).coerceIn(0f, 1f)
            val edge = close * close * (3 - 2 * close)
            val rev = (reveal * 1.6f - r2 * 0.6f).coerceIn(0f, 1f)
            val tw = 1f - twinkle * 0.6f * (0.5f + 0.5f * sin(clock * 10f * (6f + r1 * 14f) + r3 * 40f))
            val diameter = baseSize * (1f + (r0 - 0.5f)) / dist * (1f + flash.coerceAtMost(1f) * 0.6f) * dens * 0.55f
            val radius = (diameter / 2f).coerceAtLeast(0.6f)
            val alpha = (tw * (1f + flash * 1.4f)).coerceAtMost(1f) * edge * rev * rev
            if (alpha < 0.01f) continue
            val col = colors[p[7].toInt()]
            val c = Offset(x, y)
            if (glow > 0f) drawCircle(Brush.radialGradient(listOf(col.copy(alpha = alpha * glow * 0.35f), Color.Transparent), c, radius * 3.5f + 2f), radius * 3.5f + 2f, c)
            drawCircle(col.copy(alpha = alpha), radius, c)
        }
    }
}
