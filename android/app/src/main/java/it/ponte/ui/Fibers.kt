package it.ponte.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.withFrameNanos
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Fibra ottica di Ponte, disegnata direttamente in Compose: un fascio di fibre blu che ondeggiano piano
 * e impulsi di luce che le percorrono. Due forme: `band` (fascio orizzontale, dietro i titoli) e ventaglio
 * (si apre dal basso a sinistra, per le schede grandi). Ferma da sola quando l'app non è in primo piano.
 */
@Composable
fun FiberField(modifier: Modifier, band: Boolean = false, strands: Int = if (band) 24 else 30, pulses: Boolean = true, centerY: Float = 0.5f, spread: Float = 1f) {
    val dark = isSystemInDarkTheme()
    val density = LocalDensity.current.density
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time.floatValue = (it - start) / 1e9f }
    }
    val royal = Color(0xFF2E4BFF)
    val c1 = if (dark) Color(0xFF4D9BFF) else Color(0xFF3F86FF)
    val hi = if (dark) Color(0xFF9CC6FF) else Color(0xFF2E4BFF)
    val core = if (dark) Color.White else Color(0xFF9CC6FF)
    val path = remember { Path() }
    val pulse = remember { Path() }

    Canvas(modifier) {
        val t = time.floatValue
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        // Entrata: le fibre si allungano da sinistra nei primi 1,6 secondi.
        val reveal = (1f - (1f - (t / 1.6f).coerceIn(0f, 1f)).pow(3))
        val seg = 56
        for (i in 0 until strands) {
            val s = hash(i)
            val k = if (strands > 1) i / (strands - 1f) else 0.5f
            val ox: Float; val oy: Float; val ex: Float; val ey: Float
            if (band) {
                ox = -0.04f * w; oy = h * (centerY + 0.42f * spread * (k - 0.5f))
                ex = 1.04f * w; ey = h * (centerY - 0.30f * spread * (k - 0.5f) + 0.06f * sin(i.toFloat()))
            } else {
                ox = -0.06f * w; oy = h * 1.04f
                ex = 1.06f * w; ey = h * (0.04f + 0.9f * k)
            }
            val amp = h * (if (band) 0.05f else 0.04f) * (0.4f + s)
            val f = 0.8f + 1.4f * hash(i + 9)
            val v = 0.05f + 0.08f * hash(i + 3)
            val ph = (2 * PI).toFloat() * hash(i + 5)
            fun x(u: Float) = ox + (ex - ox) * u
            fun y(u: Float): Float {
                val base = if (band) oy + (ey - oy) * (u * u * (3 - 2 * u)) else oy + (ey - oy) * (1f - (1f - u).pow(3))
                return base + amp * sin((2 * PI).toFloat() * (f * u - t * v) + ph) * (if (band) 1f else u)
            }

            val a = (if (dark) 0.16f else 0.20f) + 0.32f * s
            val brush = Brush.horizontalGradient(
                0f to royal.copy(alpha = 0f), 0.18f to royal.copy(alpha = a), 0.8f to c1.copy(alpha = a), 1f to c1.copy(alpha = 0f),
                startX = ox, endX = ex,
            )
            path.reset()
            val last = max(1, (seg * reveal).toInt())
            for (j in 0..last) {
                val u = j / seg.toFloat()
                if (j == 0) path.moveTo(x(u), y(u)) else path.lineTo(x(u), y(u))
            }
            if (dark) drawPath(path, brush, alpha = 0.22f, style = Stroke(width = 5f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, brush, style = Stroke(width = 0.9f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))

            if (!pulses || reveal < 1f) continue
            // Impulso di luce che corre lungo la fibra.
            val raw = t * (0.06f + 0.09f * hash(i + 11)) + hash(i + 13)
            val p = (raw - floor(raw / 1.3f) * 1.3f) - 0.1f
            if (p <= 0.02f || p >= 1f) continue
            val len = 0.14f
            pulse.reset()
            val n = 14
            var tail = Offset.Zero
            var head = Offset.Zero
            for (j in 0..n) {
                val u = max(0f, p - len + len * j / n)
                val q = Offset(x(u), y(u))
                if (j == 0) { tail = q; pulse.moveTo(q.x, q.y) } else pulse.lineTo(q.x, q.y)
                head = q
            }
            drawPath(
                pulse, Brush.linearGradient(listOf(hi.copy(alpha = 0f), hi.copy(alpha = 0.92f)), start = tail, end = head),
                style = Stroke(width = 1.5f * density, cap = StrokeCap.Round),
            )
            val r = 4.5f * density
            drawCircle(
                Brush.radialGradient(
                    0f to core.copy(alpha = if (dark) 0.9f else 0.7f), 0.3f to hi.copy(alpha = if (dark) 0.45f else 0.3f), 1f to hi.copy(alpha = 0f),
                    center = head, radius = r,
                ),
                radius = r, center = head,
            )
        }
    }
}

private fun hash(i: Int): Float {
    val x = sin(i * 127.1f + 311.7f) * 43758.5453f
    return x - floor(x)
}
