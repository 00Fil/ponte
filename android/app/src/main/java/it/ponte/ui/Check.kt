package it.ponte.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spunta di Ponte: il disco sfumato entra con una molla appena elastica e, subito dopo, il segno si traccia da sinistra a destra.
 * Un anello leggero si allarga e svanisce quando la spunta si completa.
 */
@Composable
fun AnimatedCheck(size: Dp, modifier: Modifier = Modifier, delayMs: Long = 0L) {
    val disc = remember { Animatable(0f) }
    val draw = remember { Animatable(0f) }
    val ring = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delayMs)
        disc.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow))
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delayMs + 140)
        draw.animateTo(1f, tween(380, easing = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)))
        ring.animateTo(1f, tween(520, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
    }
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val r = s / 2f
        val center = Offset(r, r)
        // anello di conferma
        if (ring.value in 0.001f..0.999f) {
            drawCircle(
                Brand.royal.copy(alpha = 0.35f * (1f - ring.value)), radius = r * (1f + 0.45f * ring.value),
                center = center, style = Stroke(width = s * 0.04f),
            )
        }
        // disco
        val d = disc.value
        drawCircle(Brand.gradient, radius = r * d.coerceAtLeast(0f), center = center, alpha = d.coerceIn(0f, 1f))
        // segno
        val p = Path().apply {
            moveTo(s * 0.29f, s * 0.52f)
            lineTo(s * 0.44f, s * 0.67f)
            lineTo(s * 0.72f, s * 0.35f)
        }
        val seg = Path()
        val m = PathMeasure().apply { setPath(p, false) }
        m.getSegment(0f, m.length * draw.value, seg, true)
        drawPath(
            seg, Color.White,
            style = Stroke(width = s * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
