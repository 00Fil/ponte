package it.ponte.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Icone delle schede, disegnate a mano su una griglia di 24: Dispositivi, Appunti, Archivio, Audio, Impostazioni.
 * Quando la scheda si seleziona l'icona fa un piccolo rimbalzo elastico; Audio e Impostazioni hanno anche un gesto proprio.
 */
@Composable
fun TabGlyph(index: Int, color: Color, selected: Boolean, modifier: Modifier = Modifier) {
    val pop = remember { Animatable(1f) }
    val gesture = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        if (selected) {
            pop.snapTo(0.88f)
            pop.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 420f))
        }
    }
    LaunchedEffect(selected) {
        gesture.snapTo(0f)
        if (selected) gesture.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 200f))
    }
    Canvas(modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }) {
        val u = size.minDimension / 24f
        val st = Stroke(1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(x * u, y * u)
        fun sz(w: Float, h: Float) = Size(w * u, h * u)
        when (index) {
            0 -> { // casa
                val roof = Path().apply { moveTo(3.5f * u, 11f * u); lineTo(12f * u, 4f * u); lineTo(20.5f * u, 11f * u) }
                drawPath(roof, color, style = st)
                val body = Path().apply {
                    moveTo(6f * u, 9.6f * u); lineTo(6f * u, 19f * u); lineTo(10f * u, 19f * u); lineTo(10f * u, 14f * u)
                    lineTo(14f * u, 14f * u); lineTo(14f * u, 19f * u); lineTo(18f * u, 19f * u); lineTo(18f * u, 9.6f * u)
                }
                drawPath(body, color, style = st)
            }
            1 -> { // schermo e piedistallo
                drawRoundRect(color, o(4f, 5f), sz(16f, 11f), CornerRadius(2.5f * u), style = st)
                drawLine(color, o(9f, 20f), o(15f, 20f), 1.8f * u, StrokeCap.Round)
                drawLine(color, o(12f, 16f), o(12f, 20f), 1.8f * u, StrokeCap.Round)
            }
            2 -> { // cartellina con molletta
                drawRoundRect(color, o(6f, 5f), sz(12f, 16f), CornerRadius(2.5f * u), style = st)
                drawRoundRect(color, o(9f, 2.8f), sz(6f, 4f), CornerRadius(1.6f * u), style = st)
                drawLine(color, o(9f, 12f), o(15f, 12f), 1.8f * u, StrokeCap.Round)
                drawLine(color, o(9f, 16f), o(13f, 16f), 1.8f * u, StrokeCap.Round)
            }
            3 -> { // fotografia
                drawRoundRect(color, o(3.5f, 5f), sz(17f, 14f), CornerRadius(3f * u), style = st)
                val p = Path().apply {
                    moveTo(3.8f * u, 16.5f * u); lineTo(9f * u, 11.5f * u); lineTo(13f * u, 15.2f * u)
                    lineTo(15.4f * u, 13f * u); lineTo(20.2f * u, 17.2f * u)
                }
                drawPath(p, color, style = st)
                drawCircle(color, 1.4f * u, o(15.8f, 9.2f), style = st)
            }
            4 -> { // cuffie: le conchiglie scendono al posto con un rimbalzo
                val dy = if (selected) (1f - gesture.value) * -2.4f * u else 0f
                val arc = Path().apply { arcTo(androidx.compose.ui.geometry.Rect(o(4.2f, 3.5f), sz(15.6f, 15.6f)), 180f, 180f, true) }
                drawPath(arc, color, style = st)
                drawRoundRect(color, Offset(3f * u, 12.5f * u + dy), sz(4.2f, 7.5f), CornerRadius(2f * u), style = st)
                drawRoundRect(color, Offset(16.8f * u, 12.5f * u + dy), sz(4.2f, 7.5f), CornerRadius(2f * u), style = st)
            }
            else -> { // cursori: le manopole scorrono al loro posto
                val k = if (selected) 1f - gesture.value else 0f
                fun knob(y: Float, x: Float, delta: Float) {
                    drawLine(color, o(4f, y), o(20f, y), 1.8f * u, StrokeCap.Round)
                    drawCircle(Color.Black.copy(alpha = 0f), 2.3f * u, o(x + delta * k, y))
                    drawCircle(color, 2.3f * u, o(x + delta * k, y), style = st)
                }
                knob(7f, 15f, -6f)
                knob(12f, 9f, 6f)
                knob(17f, 14f, -5f)
            }
        }
    }
}
