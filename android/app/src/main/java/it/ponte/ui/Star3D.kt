package it.ponte.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Contorno della stella di Relay (le stesse curve dell'icona), centrato e normalizzato a ±0.62. */
private val OUTLINE: List<Pair<Float, Float>> = run {
    val pts = ArrayList<Pair<Float, Float>>()
    val v = listOf(512f to 196f, 828f to 512f, 512f to 828f, 196f to 512f)
    val ctl = listOf(556f to 468f, 556f to 556f, 468f to 556f, 468f to 468f)
    for (i in 0 until 4) {
        val a = v[i]; val c = ctl[i]; val b = v[(i + 1) % 4]
        for (k in 0 until 14) {
            val t = k / 14f; val u = 1 - t
            pts += ((u * u * a.first + 2 * u * t * c.first + t * t * b.first) - 512f) / 512f to ((u * u * a.second + 2 * u * t * c.second + t * t * b.second) - 512f) / 512f
        }
    }
    pts
}

/**
 * La stella del logo in tre dimensioni: uno spessore di tanti strati che gira su se stesso con un leggero beccheggio,
 * facce che si illuminano al passaggio, alone dietro e scintille intorno.
 */
@Composable
fun Star3D(modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "stella")
    val spin by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(4600, easing = LinearEasing), RepeatMode.Restart), label = "giro")
    val bob by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(3400, easing = LinearEasing), RepeatMode.Restart), label = "beccheggio")
    val pulse by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Reverse), label = "alone")
    val deep = Color(0xFF0B1F6B); val mid = Color(0xFF2F5FFF); val sky = Color(0xFF9CC6FF)
    Canvas(modifier) {
        val s = size.minDimension
        val cx = size.width / 2; val cy = size.height / 2
        // alone
        drawCircle(Brush.radialGradient(listOf(mid.copy(alpha = 0.40f + 0.14f * pulse), Color.Transparent), Offset(cx, cy), s * 0.62f), s * 0.62f, Offset(cx, cy))
        val th = (spin * 2 * PI).toFloat()
        val tilt = (0.30 * sin(bob * 2 * PI)).toFloat() + 0.18f
        val ct = cos(th); val st = sin(th)
        val cxl = cos(tilt); val sxl = sin(tilt)
        val layers = 26
        val depth = 0.17f
        val f = 3.2f
        val scale = s * 0.72f
        data class L(val z: Float, val i: Int)
        val order = (0 until layers).map { i -> L(-depth + 2 * depth * i / (layers - 1), i) }
            .sortedBy { l -> -l.z * st * 0f + (l.z * ct) }          // più lontani prima
        fun proj(x: Float, y: Float, z: Float): Offset {
            val x1 = x * ct + z * st
            val z1 = -x * st + z * ct
            val y1 = y * cxl - z1 * sxl
            val z2 = y * sxl + z1 * cxl
            val k = f / (f - z2)
            return Offset(cx + x1 * k * scale, cy + y1 * k * scale)
        }
        for (l in order) {
            val front = l.i == layers - 1 || l.i == 0
            val path = Path()
            OUTLINE.forEachIndexed { n, (x, y) ->
                val p = proj(x, y, l.z)
                if (n == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            path.close()
            val shade = (l.i / (layers - 1f))
            // quanto la stella è di taglio: l'aspetto dei lati si accende
            val edge = abs(st)
            val side = lerp(deep, mid, (0.25f + 0.55f * shade) * (0.55f + 0.45f * edge))
            val faceOn = if (ct >= 0) l.i == layers - 1 else l.i == 0
            if (!front || !faceOn) {
                drawPath(path, side)
            } else {
                val a = proj(-0.6f, -0.6f, l.z); val b = proj(0.6f, 0.6f, l.z)
                drawPath(path, Brush.linearGradient(listOf(sky, mid, Color(0xFF1B34D6)), a, b))
                // riflesso che attraversa la faccia
                val sweep = ((spin * 2f) % 1f)
                val g0 = Offset(a.x + (b.x - a.x) * (sweep - 0.25f), a.y + (b.y - a.y) * (sweep - 0.25f))
                val g1 = Offset(a.x + (b.x - a.x) * (sweep + 0.05f), a.y + (b.y - a.y) * (sweep + 0.05f))
                drawPath(path, Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent), g0, g1))
                drawPath(path, Color.White.copy(alpha = 0.55f), style = Stroke(width = s * 0.006f))
            }
        }
        // scintille
        for (k in 0 until 5) {
            val ph = (pulse + k * 0.2f) % 1f
            val ang = (k * 72f + spin * 40f) * (PI / 180f).toFloat()
            val r = s * (0.40f + 0.06f * k % 3)
            val p = Offset(cx + r * cos(ang), cy + r * 0.8f * sin(ang))
            val a = 1f - abs(ph * 2 - 1f)
            drawCircle(Color.White.copy(alpha = 0.8f * a), s * 0.010f + s * 0.008f * a, p)
        }
    }
}
