package it.ponte.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import it.ponte.Router

/**
 * Sotto "Home": una pillola con il numero di dispositivi. Si apre come una notifica a tutta larghezza (stessa forma che cresce,
 * senza salti) mostrando le bolle dei dispositivi, poi si ritira; un tocco sulla pillola la riapre.
 */
@Composable
fun DevicesMorph(r: Router, open: Boolean, onClose: () -> Unit = {}, onToggle: () -> Unit) {
    val c = Canone.colors
    val devices by r.devices.collectAsState()
    val n = devices.size
    val p by animateFloatAsState(if (open) 1f else 0f, spring(0.86f, 240f), label = "morph")
    var full by remember { mutableIntStateOf(0) }
    val d = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(30.dp)) {
        val maxW = maxWidth
        val bleed = 8.dp
        val w = lerp(206.dp, maxW + bleed * 2, p)
        val h = lerp(30.dp, with(d) { full.toDp() }.coerceAtLeast(120.dp), p)
        val shape = RoundedCornerShape(lerp(15.dp, Radius.xl, p))
        Box(
            Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).offset(x = lerp(0.dp, -bleed, p)).size(w, h).clip(shape).background(c.bgSurface.copy(alpha = 0.96f))
                .border(BorderStroke(1.dp, c.lineSeparator), shape)
                .then(if (p < 0.5f) Modifier.dropClickable(onToggle, corner = 15.dp) else Modifier)
                .pointerInput(open) {
                    if (!open) return@pointerInput
                    var dy = 0f
                    detectVerticalDragGestures(onDragStart = { dy = 0f }, onDragEnd = { if (dy < -40f) onClose() }, onDragCancel = {}) { _, a -> dy += a }
                },
        ) {
            if (p < 0.6f) Box(Modifier.size(w, 30.dp).graphicsLayer { alpha = (1f - p * 2f).coerceIn(0f, 1f) }, contentAlignment = Alignment.Center) {
                Text(
                    buildAnnotatedString {
                        append(if (n == 0) "Solo questo dispositivo" else "Collegato a ")
                        if (n > 0) withStyle(SpanStyle(color = c.accent, fontWeight = FontWeight.Medium)) { append(if (n == 1) "1 dispositivo" else "$n dispositivi") }
                    },
                    style = CanoneType.footnote, color = c.labelSecondary, maxLines = 1,
                )
            }
            if (p > 0.05f) Box(
                Modifier.width(maxW + bleed * 2).graphicsLayer { alpha = ((p - 0.45f) * 2f).coerceIn(0f, 1f) }
                    .layout { m, cs ->
                        val pl = m.measure(Constraints(cs.maxWidth, cs.maxWidth, 0, Constraints.Infinity))
                        if (full != pl.height) full = pl.height
                        layout(cs.maxWidth, pl.height) { pl.place(0, 0) }
                    },
            ) { DevicesCard(r, bare = true) }
        }
    }
}
