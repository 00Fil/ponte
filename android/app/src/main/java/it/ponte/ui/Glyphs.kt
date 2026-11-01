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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Icone delle schede, disegnate a mano su una griglia di 24: Dispositivi, Appunti, Archivio, Audio, Finanze, Impostazioni.
 * Quando la scheda si seleziona l'icona fa un piccolo rimbalzo elastico; alcune icone hanno anche un gesto proprio.
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
        val st = Stroke(1.35f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
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
                drawPath(body, color.copy(alpha = if (selected) 0.13f else 0.04f), style = androidx.compose.ui.graphics.drawscope.Fill)
                drawPath(body, color, style = st)
            }
            1 -> { // computer e telefono: due schermi, invece di un generico monitor
                drawRoundRect(color.copy(alpha = if (selected) 0.12f else 0.035f), o(2.5f, 5f), sz(14.5f, 10.5f), CornerRadius(2.2f * u))
                drawRoundRect(color, o(2.5f, 5f), sz(14.5f, 10.5f), CornerRadius(2.2f * u), style = st)
                drawLine(color, o(7f, 19f), o(12f, 19f), 1.35f * u, StrokeCap.Round)
                drawLine(color, o(9.5f, 15.5f), o(9.5f, 19f), 1.35f * u, StrokeCap.Round)
                drawRoundRect(color.copy(alpha = if (selected) 0.18f else 0.04f), o(14.5f, 3.8f), sz(7f, 15.5f), CornerRadius(2.2f * u))
                drawRoundRect(color, o(14.5f, 3.8f), sz(7f, 15.5f), CornerRadius(2.2f * u), style = st)
                drawLine(color, o(17f, 16.8f), o(19f, 16.8f), 1.2f * u, StrokeCap.Round)
            }
            2 -> { // cartellina con molletta
                drawRoundRect(color.copy(alpha = if (selected) 0.11f else 0.035f), o(6f, 5f), sz(12f, 16f), CornerRadius(2.5f * u))
                drawRoundRect(color, o(6f, 5f), sz(12f, 16f), CornerRadius(2.5f * u), style = st)
                drawRoundRect(color, o(9f, 2.8f), sz(6f, 4f), CornerRadius(1.6f * u), style = st)
                drawLine(color, o(9f, 12f), o(15f, 12f), 1.3f * u, StrokeCap.Round)
                drawLine(color, o(9f, 16f), o(13f, 16f), 1.3f * u, StrokeCap.Round)
                drawRoundRect(color, o(9f, 2.8f), sz(6f, 4f), CornerRadius(1.6f * u), style = st)
            }
            3 -> { // archivio: scatola e maniglia, distinta dalla galleria immagini
                drawRoundRect(color.copy(alpha = if (selected) 0.12f else 0.035f), o(3.5f, 8f), sz(17f, 12f), CornerRadius(2.2f * u))
                drawRoundRect(color, o(3.5f, 8f), sz(17f, 12f), CornerRadius(2.2f * u), style = st)
                drawRoundRect(color.copy(alpha = if (selected) 0.14f else 0.045f), o(5f, 4f), sz(14f, 5f), CornerRadius(1.8f * u))
                drawRoundRect(color, o(5f, 4f), sz(14f, 5f), CornerRadius(1.8f * u), style = st)
                drawLine(color, o(9f, 12.5f), o(15f, 12.5f), 1.3f * u, StrokeCap.Round)
                drawLine(color, o(9f, 16f), o(15f, 16f), 1.3f * u, StrokeCap.Round)
            }
            4 -> { // cuffie: le conchiglie scendono al posto con un rimbalzo
                val dy = if (selected) (1f - gesture.value) * -2.4f * u else 0f
                val arc = Path().apply { arcTo(androidx.compose.ui.geometry.Rect(o(4.2f, 3.5f), sz(15.6f, 15.6f)), 180f, 180f, true) }
                drawPath(arc, color, style = st)
                drawRoundRect(color.copy(alpha = if (selected) 0.16f else 0.04f), Offset(3f * u, 12.5f * u + dy), sz(4.2f, 7.5f), CornerRadius(2f * u))
                drawRoundRect(color, Offset(3f * u, 12.5f * u + dy), sz(4.2f, 7.5f), CornerRadius(2f * u), style = st)
                drawRoundRect(color.copy(alpha = if (selected) 0.16f else 0.04f), Offset(16.8f * u, 12.5f * u + dy), sz(4.2f, 7.5f), CornerRadius(2f * u))
                drawRoundRect(color, Offset(16.8f * u, 12.5f * u + dy), sz(4.2f, 7.5f), CornerRadius(2f * u), style = st)
            }
            5 -> { // portafoglio con saldo
                drawRoundRect(color.copy(alpha = if (selected) 0.13f else 0.04f), o(3f, 6f), sz(18f, 13f), CornerRadius(3f * u))
                drawRoundRect(color, o(3f, 6f), sz(18f, 13f), CornerRadius(3f * u), style = st)
                drawLine(color, o(4f, 9f), o(15f, 9f), 1.2f * u, StrokeCap.Round)
                drawRoundRect(color, o(13f, 10.5f), sz(8f, 5f), CornerRadius(2f * u), style = st)
                drawCircle(color, 0.9f * u, o(16f, 13f))
                drawLine(color, o(7f, 4f), o(17f, 4f), 1.3f * u, StrokeCap.Round)
            }
            else -> { // impostazioni: ghiera con otto denti e centro aperto
                val center = o(12f, 12f)
                for (tooth in 0 until 8) {
                    rotate(tooth * 45f, center) {
                        drawLine(color, o(12f, 2f), o(12f, 4.5f), 1.35f * u, StrokeCap.Round)
                    }
                }
                drawCircle(color, 7.2f * u, center, style = st)
                drawCircle(color.copy(alpha = if (selected) 0.10f else 0.025f), 3.2f * u, center)
                drawCircle(color, 3.2f * u, center, style = st)
            }
        }
    }
}
