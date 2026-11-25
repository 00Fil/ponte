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
 * Indici: 0 casa, 1 dispositivi, 2 appunti, 3 archivio, 4 cuffie, 5 impostazioni, 6 portafoglio, 7 invia, 8 disco, 9 banconote, 10 scudo, 11 calendario, 12 laurea, 22 fotocamera, 23 fiamma.
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
    // banknote
    listOf(rr(2f, 6f, 20f, 12f, 2f), circ(12f, 12f, 2f), "M6 12h.01", "M18 12h.01"),
    // shield-check
    listOf("M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z", "m9 12 2 2 4-4"),
    // calendar-days
    listOf("M8 2v4", "M16 2v4", rr(3f, 4f, 18f, 18f, 2f), "M3 10h18", "M8 14h.01", "M12 14h.01", "M16 14h.01", "M8 18h.01", "M12 18h.01"),
    // graduation-cap
    listOf("M21.42 10.922a1 1 0 0 0-.019-1.838L12.83 5.18a2 2 0 0 0-1.66 0L2.6 9.08a1 1 0 0 0 0 1.832l8.57 3.908a2 2 0 0 0 1.66 0z", "M22 10v6", "M6 12.5V16a6 3 0 0 0 12 0v-3.5"),
    // 13 sun
    listOf(circ(12f, 12f, 4f), "M12 2v2", "M12 20v2", "m4.93 4.93 1.41 1.41", "m17.66 17.66 1.41 1.41", "M2 12h2", "M20 12h2", "m6.34 17.66-1.41 1.41", "m19.07 4.93-1.41 1.41"),
    // 14 moon
    listOf("M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"),
    // 15 contrast (automatico)
    listOf(circ(12f, 12f, 10f), "M12 18a6 6 0 0 0 0-12v12z"),
    // 16 history (registrazioni)
    listOf("M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5", "M12 7v5l4 2"),
    // 17 radio (dal vivo)
    listOf("M4.9 19.1C1 15.2 1 8.8 4.9 4.9", "M7.8 16.2c-2.3-2.3-2.3-6.1 0-8.5", circ(12f, 12f, 2f), "M16.2 7.8c2.3 2.3 2.3 6.1 0 8.5", "M19.1 4.9C23 8.8 23 15.1 19.1 19"),
    // 18 scan (alta qualità)
    listOf("M3 7V5a2 2 0 0 1 2-2h2", "M17 3h2a2 2 0 0 1 2 2v2", "M21 17v2a2 2 0 0 1-2 2h-2", "M7 21H5a2 2 0 0 1-2-2v-2", rr(7f, 8f, 10f, 8f, 1f)),
    // 19 expand (apri)
    listOf("m15 3 6 0 0 6", "m9 21-6 0 0-6", "M21 3l-7 7", "M3 21l7-7"),
    // 20 shrink (riduci)
    listOf("m14 10 7-7", "M20 10h-6V4", "m3 21 7-7", "M4 14h6v6"),
    // 21 activity (eventi)
    listOf("M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2 2 0 0 1 4.49 12H2"),
    // 22 camera (foto)
    listOf("M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z", circ(12f, 13f, 3f)),
    // 23 flame (corpo)
    listOf("M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.072-2.143-.224-4.054 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.153.433-2.294 1-3a2.5 2.5 0 0 0 2.5 2.5z"),
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
