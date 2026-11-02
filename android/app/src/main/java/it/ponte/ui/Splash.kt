package it.ponte.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Ingresso: il marchio compare con una molla morbida, un anello si traccia attorno, poi tutto si dissolve verso l'app. Piatto, nitido, breve. */
@Composable
fun Splash(onDone: () -> Unit) {
    val c = Canone.colors
    val appear = remember { Animatable(0f) }
    val ring = remember { Animatable(0f) }
    val out = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { appear.animateTo(1f, spring(0.82f, 300f)) }
        launch { ring.animateTo(1f, tween(640, easing = CubicBezierEasing(0.3f, 0f, 0.2f, 1f))) }
        delay(820)
        out.animateTo(1f, tween(240))
        onDone()
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - out.value }.background(c.bgCanvas), contentAlignment = Alignment.Center) {
        Box(
            Modifier.graphicsLayer {
                val s = (0.86f + 0.14f * appear.value) * (1f + 0.06f * out.value)
                scaleX = s; scaleY = s; alpha = appear.value.coerceIn(0f, 1f)
            },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(136.dp)) {
                val w = 3.dp.toPx()
                val tl = Offset(w / 2, w / 2)
                val sz = Size(size.width - w, size.height - w)
                drawArc(c.lineSeparator, 0f, 360f, false, tl, sz, style = Stroke(w))
                drawArc(Brand.gradient, -90f, 360f * ring.value, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
            }
            RelayMark(84.dp)
        }
    }
}
