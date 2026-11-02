package it.ponte.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Cuffie in 3D vere, disegnate a mano: archetto e padiglioni sono solidi costruiti con sezioni (sfere lungo l'arco, dischi lungo l'asse),
 * ruotati e proiettati con la prospettiva a ogni fotogramma e ordinati per profondità. Si girano trascinando e tornano a posto con una molla.
 * [glow] è 0..1: quanto sono "accese" (collegate a questo dispositivo): l'anello dei padiglioni prende l'accento.
 */
@Composable
fun Headphones3D(glow: Float, accent: Color, dark: Boolean, modifier: Modifier = Modifier) {
    val yaw = remember { Animatable(1.5f) }
    val scope = rememberCoroutineScope()
    val rest = 0.62f
    LaunchedEffect(Unit) { yaw.animateTo(rest, spring(0.7f, 60f)) }

    Box(
        modifier.fillMaxWidth().aspectRatio(1.5f).pointerInput(Unit) {
            detectDragGestures(
                onDragEnd = { scope.launch { yaw.animateTo(rest, spring(0.55f, 90f)) } },
                onDragCancel = { scope.launch { yaw.animateTo(rest, spring(0.55f, 90f)) } },
            ) { change, d -> change.consume(); scope.launch { yaw.snapTo((yaw.value + d.x * 0.012f).coerceIn(-1.6f, 3.2f)) } }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) { draw3d(yaw.value, glow, accent, dark) }
    }
}

private fun DrawScope.draw3d(yaw: Float, glow: Float, accent: Color, dark: Boolean) {
    val pitch = -0.16f
    val ca = cos(yaw); val sa = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
    val unit = size.height * 0.62f
    val cx = size.width / 2f; val cy = size.height * 0.50f
    val f = 4.2f

    val body = if (dark) Color(0xFF3A3D45) else Color(0xFFCFD3DC)
    val hi = if (dark) Color(0xFF7A808C) else Color(0xFFFFFFFF)
    val lo = if (dark) Color(0xFF14161A) else Color(0xFF8E94A0)

    class P(val sx: Float, val sy: Float, val s: Float, val z: Float)
    fun proj(x: Float, y: Float, z: Float): P {
        val x1 = x * ca + z * sa
        val z1 = -x * sa + z * ca
        val y1 = y * cp - z1 * sp
        val z2 = y * sp + z1 * cp
        val s = f / (f - z2)
        return P(cx + x1 * s * unit, cy - y1 * s * unit, s, z2)
    }

    // ---- elementi da disegnare, poi ordinati dal più lontano al più vicino
    class Item(val z: Float, val draw: () -> Unit)
    val items = ArrayList<Item>()

    // archetto: sfere lungo una semicirconferenza, con un piccolo spessore in profondità
    val R = 0.64f
    val n = 34
    for (i in 0..n) {
        val t = PI.toFloat() * (0.02f + 0.96f * i / n)
        val x = R * cos(t); val y = 0.06f + R * 0.95f * sin(t)
        val p = proj(x, y, 0f)
        val r = 0.062f * p.s * unit
        items += Item(p.z) {
            drawCircle(Brush.radialGradient(listOf(hi.copy(alpha = 0.9f), body, lo), Offset(p.sx - r * 0.35f, p.sy - r * 0.45f), r * 1.5f), r, Offset(p.sx, p.sy))
        }
    }

    // padiglioni: dischi lungo l'asse x, cupola esterna più stretta
    for (side in intArrayOf(-1, 1)) {
        val baseX = side * (R + 0.02f)
        val slices = 12
        for (k in 0..slices) {
            val u = k / slices.toFloat()                    // 0 interno (cuscino) .. 1 esterno (guscio)
            val dx = side * (0.02f + 0.20f * u)
            val rad = 0.30f * (if (u > 0.78f) 1f - ((u - 0.78f) / 0.22f) * ((u - 0.78f) / 0.22f) * 0.22f else 1f)
            val p = proj(baseX + dx, -0.12f, 0f)
            // ellisse: asse orizzontale = profondità del disco (z) ruotata, asse verticale = y
            val rxp = rad * abs(sa) * p.s * unit
            val ryp = rad * (cp * 1f) * p.s * unit
            val outer = k == slices
            val cushion = k <= 2
            items += Item(p.z + 0.0001f * k) {
                val c0 = if (cushion) (if (dark) Color(0xFF22252B) else Color(0xFFB6BBC6)) else body
                val tl = Offset(p.sx - rxp, p.sy - ryp)
                val sz = Size(rxp * 2f, ryp * 2f)
                drawOval(
                    Brush.radialGradient(listOf(hi.copy(alpha = if (outer) 0.95f else 0.55f), c0, lo), Offset(p.sx - rxp * 0.4f, p.sy - ryp * 0.5f), (ryp + rxp) * 0.9f),
                    tl, sz,
                )
                drawOval(lo.copy(alpha = 0.45f), tl, sz, style = Stroke(1f))
                if (outer && glow > 0.01f) {
                    val rr = Size(rxp * 1.25f, ryp * 1.25f)
                    drawOval(accent.copy(alpha = 0.9f * glow), Offset(p.sx - rr.width / 2, p.sy - rr.height / 2), rr, style = Stroke(size.height * 0.012f))
                    drawOval(accent.copy(alpha = 0.18f * glow), Offset(p.sx - rxp * 0.75f, p.sy - ryp * 0.75f), Size(rxp * 1.5f, ryp * 1.5f))
                }
            }
        }
        // giunto tra archetto e padiglione
        val a = proj(baseX, 0.06f, 0f); val b = proj(baseX, 0.20f, 0f)
        items += Item(a.z + 0.01f) { drawLine(lo, Offset(a.sx, a.sy), Offset(b.sx, b.sy), size.height * 0.03f) }
    }

    items.sortBy { it.z }
    items.forEach { it.draw() }
}
