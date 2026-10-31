package it.ponte.ui

import android.text.format.Formatter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.ponte.Updater

/**
 * Anello di avanzamento "gelatina": il valore insegue l'avanzamento con una molla elastica che sfora e rimbalza,
 * e a ogni scatto l'anello si schiaccia e si allunga appena, come una gelatina che viene toccata.
 */
@Composable
fun JellyRing(fraction: Float, size: Dp, label: String, modifier: Modifier = Modifier) {
    val c = Canone.colors
    val shown = remember { Animatable(0f) }
    val squash = remember { Animatable(0f) }
    LaunchedEffect(fraction) {
        shown.animateTo(fraction.coerceIn(0f, 1f), spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessLow))
    }
    LaunchedEffect(fraction) {
        // piccolo colpo, poi la molla lo riporta a zero oscillando
        squash.snapTo(0.07f)
        squash.animateTo(0f, spring(dampingRatio = 0.22f, stiffness = 140f))
    }
    Box(
        modifier.size(size).graphicsLayer {
            scaleX = 1f + squash.value
            scaleY = 1f - squash.value
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val w = this.size.minDimension * 0.11f
            val inset = w / 2f
            val arc = Size(this.size.width - w, this.size.height - w)
            drawArc(c.fillControl, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
            val sweep = 360f * shown.value.coerceIn(0f, 1.04f)
            if (sweep > 0.5f) {
                drawArc(
                    Brush.sweepGradient(listOf(Brand.royal, Brand.sky, Brand.royal), center),
                    -90f, sweep, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round),
                )
            }
        }
        Text(label, style = CanoneType.bodyMedium, color = c.labelPrimary)
    }
}

/** Scheda del download in corso: anello gelatina e i dettagli (quanto, velocità, tempo che manca). */
@Composable
fun DownloadCard(d: Updater.Download, modifier: Modifier = Modifier) {
    val c = Canone.colors
    val ctx = LocalContext.current
    val frac = if (d.total > 0) d.done.toFloat() / d.total else 0f
    val left = if (d.bytesPerSec > 1 && d.total > d.done) ((d.total - d.done) / d.bytesPerSec).toInt() else -1
    CCard(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s4), verticalAlignment = Alignment.CenterVertically) {
            JellyRing(frac, 72.dp, "${(frac * 100).toInt()}%")
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${d.phase} la versione ${d.version}", style = CanoneType.bodyMedium, color = c.labelPrimary)
                Text(
                    Formatter.formatShortFileSize(ctx, d.done) + (if (d.total > 0) " di " + Formatter.formatShortFileSize(ctx, d.total) else ""),
                    style = CanoneType.footnote, color = c.labelSecondary,
                )
                if (d.phase == "Scarico") Text(
                    (if (d.bytesPerSec > 1) Formatter.formatShortFileSize(ctx, d.bytesPerSec.toLong()) + "/s" else "In avvio") +
                        (if (left >= 0) " · mancano circa " + (if (left >= 60) "${left / 60} min" else "$left s") else ""),
                    style = CanoneType.footnote, color = c.labelTertiary,
                )
            }
        }
    }
}
