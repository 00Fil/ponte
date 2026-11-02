package it.ponte.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.PathParser

/**
 * Icone delle schede: i tracciati sono quelli di Lucide (lucide.dev, licenza ISC), disegnati su una griglia di 24 con tratto sottile.
 * Ogni icona è un unico tracciato tratteggiato in un solo passaggio, così dove le linee si incrociano non si vede nessuna sovrapposizione.
 * Quando la scheda diventa attiva l'icona si ridisegna (come in lucide-animated) e l'ingranaggio ruota piano.
 *
 * Indici: 0 casa, 1 dispositivi, 2 appunti, 3 archivio, 4 cuffie, 5 impostazioni.
 */
private fun rr(x: Float, y: Float, w: Float, h: Float, r: Float) =
    "M${x + r} ${y}H${x + w - r}A$r $r 0 0 1 ${x + w} ${y + r}V${y + h - r}A$r $r 0 0 1 ${x + w - r} ${y + h}H${x + r}A$r $r 0 0 1 $x ${y + h - r}V${y + r}A$r $r 0 0 1 ${x + r} ${y}Z"

private fun circ(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}A$r $r 0 1 0 ${cx + r} ${cy}A$r $r 0 1 0 ${cx - r} ${cy}Z"

private val ICONS: List<List<String>> = listOf(
    // house
    listOf(
        "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8",
        "M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
    ),
    // monitor-smartphone
    listOf("M18 8V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h8", "M10 19v-3.96 3.15", "M7 19h5", rr(16f, 12f, 6f, 10f, 2f)),
    // clipboard-list
    listOf(
        rr(8f, 2f, 8f, 4f, 1f),
        "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2",
        "M12 11h4", "M12 16h4", "M8 11h.01", "M8 16h.01",
    ),
    // images
    listOf("m22 11-1.296-1.296a2.4 2.4 0 0 0-3.408 0L11 16", "M4 8a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2", circ(13f, 7f, 1f), rr(8f, 2f, 14f, 14f, 2f)),
    // headphones
    listOf("M3 14h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a9 9 0 0 1 18 0v7a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3"),
    // settings
    listOf(
        "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915",
        circ(12f, 12f, 3f),
    ),
    // wallet
    listOf("M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1", "M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4"),
    // upload
    listOf("M12 3v12", "m17 8-5-5-5 5", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"),
    // hard-drive
    listOf("M22 12H2", "M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z", "M6 16h.01", "M10 16h.01"),
)

@Composable
fun TabGlyph(index: Int, color: Color, selected: Boolean, modifier: Modifier = Modifier) {
    val i = index.coerceIn(0, ICONS.size - 1)
    val parts = remember(i) { ICONS[i].map { PathParser().parsePathString(it).toPath() } }
    val measure = remember { PathMeasure() }
    val draw = remember { Animatable(1f) }
    val turn = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        if (selected) { draw.snapTo(0f); draw.animateTo(1f, tween(560, easing = FastOutSlowInEasing)) } else draw.snapTo(1f)
    }
    LaunchedEffect(selected) {
        if (i == 5) turn.animateTo(if (selected) 1f else 0f, spring(dampingRatio = 0.85f, stiffness = 120f))
    }
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val p = draw.value
        // un solo tracciato per tutta l'icona: niente doppio colore dove le linee si toccano
        val all = Path()
        for (part in parts) {
            if (p >= 0.999f) all.addPath(part)
            else {
                measure.setPath(part, false)
                val seg = Path()
                if (measure.getSegment(0f, measure.length * p, seg, true)) all.addPath(seg)
            }
        }
        val scale = androidx.compose.ui.graphics.Matrix().apply { scale(u, u) }
        all.transform(scale)
        val st = Stroke(1.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        if (i == 5) rotate(turn.value * 60f, pivot = Offset(12f * u, 12f * u)) { drawPath(all, color, style = st) }
        else drawPath(all, color, style = st)
    }
}
