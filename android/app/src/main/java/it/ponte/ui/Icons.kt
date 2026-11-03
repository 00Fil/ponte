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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate

/** Icone a tratto, tutte sullo stesso reticolo (24 × 24) e con lo stesso spessore. */
object Ico {
    const val FOLDER = 0; const val FOLDER_PLUS = 1; const val SHARE = 2; const val OPEN = 3; const val TRASH = 4; const val EYE = 5; const val EYE_OFF = 6; const val CHECK = 7; const val PENCIL = 8; const val CLOSE = 9; const val BACK = 10
}

@Composable
fun Icon24(kind: Int, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val st = Stroke(1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(f: Path.() -> Unit) = Path().apply(f)
        fun P(x: Float) = x * u
        when (kind) {
            Ico.FOLDER, Ico.FOLDER_PLUS -> {
                drawPath(p {
                    moveTo(P(3f), P(8f)); quadraticTo(P(3f), P(6f), P(5f), P(6f)); lineTo(P(9f), P(6f)); lineTo(P(11f), P(8.5f)); lineTo(P(19f), P(8.5f))
                    quadraticTo(P(21f), P(8.5f), P(21f), P(10.5f)); lineTo(P(21f), P(17f)); quadraticTo(P(21f), P(19f), P(19f), P(19f)); lineTo(P(5f), P(19f))
                    quadraticTo(P(3f), P(19f), P(3f), P(17f)); close()
                }, color, style = st)
                if (kind == Ico.FOLDER_PLUS) { drawLine(color, Offset(P(12f), P(11.5f)), Offset(P(12f), P(16.5f)), st.width, StrokeCap.Round); drawLine(color, Offset(P(9.5f), P(14f)), Offset(P(14.5f), P(14f)), st.width, StrokeCap.Round) }
            }
            Ico.SHARE -> {
                drawPath(p { moveTo(P(12f), P(15f)); lineTo(P(12f), P(4f)); moveTo(P(8f), P(7.5f)); lineTo(P(12f), P(3.8f)); lineTo(P(16f), P(7.5f)) }, color, style = st)
                drawPath(p { moveTo(P(7f), P(11f)); lineTo(P(6f), P(11f)); quadraticTo(P(4.5f), P(11f), P(4.5f), P(12.5f)); lineTo(P(4.5f), P(18f)); quadraticTo(P(4.5f), P(20f), P(6.5f), P(20f)); lineTo(P(17.5f), P(20f)); quadraticTo(P(19.5f), P(20f), P(19.5f), P(18f)); lineTo(P(19.5f), P(12.5f)); quadraticTo(P(19.5f), P(11f), P(18f), P(11f)); lineTo(P(17f), P(11f)) }, color, style = st)
            }
            Ico.OPEN -> {
                drawPath(p { moveTo(P(10f), P(5f)); lineTo(P(6f), P(5f)); quadraticTo(P(4f), P(5f), P(4f), P(7f)); lineTo(P(4f), P(18f)); quadraticTo(P(4f), P(20f), P(6f), P(20f)); lineTo(P(17f), P(20f)); quadraticTo(P(19f), P(20f), P(19f), P(18f)); lineTo(P(19f), P(14f)) }, color, style = st)
                drawPath(p { moveTo(P(14f), P(4f)); lineTo(P(20f), P(4f)); lineTo(P(20f), P(10f)); moveTo(P(20f), P(4f)); lineTo(P(11f), P(13f)) }, color, style = st)
            }
            Ico.TRASH -> {
                drawLine(color, Offset(P(4f), P(7f)), Offset(P(20f), P(7f)), st.width, StrokeCap.Round)
                drawPath(p { moveTo(P(9f), P(7f)); lineTo(P(9f), P(5f)); quadraticTo(P(9f), P(4f), P(10f), P(4f)); lineTo(P(14f), P(4f)); quadraticTo(P(15f), P(4f), P(15f), P(5f)); lineTo(P(15f), P(7f)) }, color, style = st)
                drawPath(p { moveTo(P(6f), P(7f)); lineTo(P(7f), P(18.5f)); quadraticTo(P(7.1f), P(20f), P(8.6f), P(20f)); lineTo(P(15.4f), P(20f)); quadraticTo(P(16.9f), P(20f), P(17f), P(18.5f)); lineTo(P(18f), P(7f)) }, color, style = st)
            }
            Ico.EYE, Ico.EYE_OFF -> {
                drawPath(p {
                    moveTo(P(2.5f), P(12f)); cubicTo(P(5f), P(7.2f), P(8.4f), P(5.5f), P(12f), P(5.5f)); cubicTo(P(15.6f), P(5.5f), P(19f), P(7.2f), P(21.5f), P(12f))
                    cubicTo(P(19f), P(16.8f), P(15.6f), P(18.5f), P(12f), P(18.5f)); cubicTo(P(8.4f), P(18.5f), P(5f), P(16.8f), P(2.5f), P(12f)); close()
                }, color, style = st)
                drawCircle(color, P(3.2f), Offset(P(12f), P(12f)), style = st)
                if (kind == Ico.EYE_OFF) drawLine(color, Offset(P(4.5f), P(4.5f)), Offset(P(19.5f), P(19.5f)), st.width * 1.15f, StrokeCap.Round)
            }
            Ico.PENCIL -> {
                drawPath(p { moveTo(P(4f), P(20f)); lineTo(P(4.8f), P(15.6f)); lineTo(P(16.2f), P(4.2f)); quadraticTo(P(17.2f), P(3.2f), P(18.2f), P(4.2f)); lineTo(P(19.8f), P(5.8f)); quadraticTo(P(20.8f), P(6.8f), P(19.8f), P(7.8f)); lineTo(P(8.4f), P(19.2f)); close() }, color, style = st)
                drawLine(color, Offset(P(14.2f), P(6.2f)), Offset(P(17.8f), P(9.8f)), st.width, StrokeCap.Round)
            }
            Ico.CLOSE -> { drawLine(color, Offset(P(6f), P(6f)), Offset(P(18f), P(18f)), st.width * 1.1f, StrokeCap.Round); drawLine(color, Offset(P(18f), P(6f)), Offset(P(6f), P(18f)), st.width * 1.1f, StrokeCap.Round) }
            Ico.BACK -> drawPath(p { moveTo(P(14.5f), P(5f)); lineTo(P(7.5f), P(12f)); lineTo(P(14.5f), P(19f)) }, color, style = Stroke(2.1f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
            Ico.CHECK -> drawPath(p { moveTo(P(5f), P(12.5f)); lineTo(P(10f), P(17.5f)); lineTo(P(19f), P(7f)) }, color, style = Stroke(2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** L'attesa di un'operazione, stessa linea ovunque: pista piena e un arco pieno di lunghezza fissa che gira, senza code sfumate né punte. */
@Composable
fun BusyArc(color: Color, modifier: Modifier = Modifier, stroke: Float = 2.4f) {
    val t = rememberInfiniteTransition(label = "attesa")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "giro")
    val track = Canone.colors.lineSeparator
    Canvas(modifier) {
        val w = stroke * (size.minDimension / 24f)
        val inset = w / 2f
        val tl = Offset(inset, inset); val sz = Size(size.width - w, size.height - w)
        drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(w))
        rotate(a) { drawArc(color, 0f, 100f, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round)) }
    }
}
