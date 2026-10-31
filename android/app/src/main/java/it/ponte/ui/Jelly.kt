package it.ponte.ui

import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.ponte.Updater
import it.ponte.BuildConfig

/**
 * Anello dell'aggiornamento.
 * - Ricerca: un arco sfumato gira e respira (si allunga e si accorcia).
 * - Download: l'arco insegue l'avanzamento con una molla morbida; a ogni 10 % l'anello fa un piccolo rimbalzo "gelatina".
 * - Installazione: anello pieno con un riflesso che gira.
 */
@Composable
fun UpdateRing(phase: Updater.Phase, fraction: Float, size: Dp, modifier: Modifier = Modifier) {
    val c = Canone.colors
    val spin = rememberInfiniteTransition(label = "giro")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "angolo")
    val breath by spin.animateFloat(0.18f, 0.62f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "respiro")

    val shown = remember { Animatable(0f) }
    val jelly = remember { Animatable(0f) }
    val tenth = (fraction * 10).toInt()
    LaunchedEffect(fraction) { shown.animateTo(fraction.coerceIn(0f, 1f), spring(dampingRatio = 0.8f, stiffness = 90f)) }
    LaunchedEffect(tenth) {
        if (phase != Updater.Phase.Downloading || tenth == 0) return@LaunchedEffect
        jelly.snapTo(1f)
        jelly.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 260f))
    }

    Canvas(
        modifier.size(size).graphicsLayer {
            // gelatina: si allarga in un verso e si stringe nell'altro, poi torna
            scaleX = 1f + 0.06f * jelly.value
            scaleY = 1f - 0.06f * jelly.value
        },
    ) {
        val w = this.size.minDimension * 0.12f
        val inset = w / 2f
        val arc = Size(this.size.width - w, this.size.height - w)
        val tl = Offset(inset, inset)
        drawArc(c.fillControl, 0f, 360f, false, tl, arc, style = Stroke(w))
        val brush = Brush.sweepGradient(listOf(Brand.royal, Brand.sky, Brand.royal), center)
        when (phase) {
            Updater.Phase.Checking -> rotate(angle) { drawArc(brush, -90f, 360f * breath, false, tl, arc, style = Stroke(w, cap = StrokeCap.Round)) }
            Updater.Phase.Downloading -> {
                val sweep = 360f * shown.value
                if (sweep > 1f) drawArc(brush, -90f, sweep, false, tl, arc, style = Stroke(w, cap = StrokeCap.Round))
            }
            Updater.Phase.Installing -> {
                drawArc(brush, 0f, 360f, false, tl, arc, style = Stroke(w))
                rotate(angle) { drawArc(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.55f), -90f, 40f, false, tl, arc, style = Stroke(w, cap = StrokeCap.Round)) }
            }
            else -> {}
        }
    }
}


/**
 * Scheda dell'aggiornamento, sempre nello stesso ordine: anello a sinistra, titolo e dettagli al centro,
 * percentuale a destra; sotto, i tre passi allineati. Galleggia sopra al contenuto, non lo sposta.
 */
@Composable
fun UpdateSheet(u: Updater.Ui, modifier: Modifier = Modifier) {
    val c = Canone.colors
    val ctx = LocalContext.current
    val frac = if (u.total > 0) (u.done.toFloat() / u.total).coerceIn(0f, 1f) else 0f
    val shape = RoundedCornerShape(Radius.xl)
    Column(
        modifier.fillMaxWidth().shadow(18.dp, shape, ambientColor = Brand.royal.copy(alpha = 0.25f), spotColor = Brand.royal.copy(alpha = 0.25f))
            .clip(shape).background(c.bgSurface).border(BorderStroke(1.dp, c.lineSeparator), shape).padding(Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    u.phase == Updater.Phase.UpToDate || u.phase == Updater.Phase.Failed,
                    transitionSpec = { (scaleIn(spring(0.6f, 300f), 0.6f) + fadeIn(tween(200))).togetherWith(fadeOut(tween(150))) },
                    label = "esito",
                ) { done ->
                    when {
                        !done -> UpdateRing(u.phase, frac, 48.dp)
                        u.phase == Updater.Phase.UpToDate -> AnimatedCheck(48.dp)
                        else -> Box(Modifier.size(48.dp).clip(CircleShape).background(c.critical), contentAlignment = Alignment.Center) {
                            Text("!", style = CanoneType.title3, color = c.criticalOnCritical)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val title = when (u.phase) {
                    Updater.Phase.Checking -> "Cerco aggiornamenti"
                    Updater.Phase.Downloading -> "Scarico la versione ${u.version}"
                    Updater.Phase.Installing -> "Installo la versione ${u.version}"
                    Updater.Phase.UpToDate -> u.message.ifEmpty { "Hai l'ultima versione" }
                    Updater.Phase.Failed -> "Aggiornamento non riuscito"
                }
                Text(title, style = CanoneType.bodyMedium, color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = when (u.phase) {
                    Updater.Phase.Checking -> "Chiedo al server l'ultima release"
                    Updater.Phase.Downloading -> {
                        val left = if (u.bytesPerSec > 1 && u.total > u.done) ((u.total - u.done) / u.bytesPerSec).toInt() else -1
                        buildString {
                            append(Formatter.formatShortFileSize(ctx, u.done))
                            if (u.total > 0) append(" di ").append(Formatter.formatShortFileSize(ctx, u.total))
                            if (u.bytesPerSec > 1) append(" · ").append(Formatter.formatShortFileSize(ctx, u.bytesPerSec.toLong())).append("/s")
                            if (left >= 0) append(" · ").append(if (left >= 60) "${left / 60} min" else "$left s")
                        }
                    }
                    Updater.Phase.Installing -> "Ponte si riapre da solo tra poco"
                    Updater.Phase.UpToDate -> "Versione installata: 1.0.${BuildConfig.VERSION_CODE}"
                    Updater.Phase.Failed -> u.message
                }
                Text(
                    detail, style = CanoneType.footnote.copy(fontFeatureSettings = "tnum"), color = c.labelSecondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (u.phase == Updater.Phase.Downloading) {
                Text(
                    "${(frac * 100).toInt()}%", style = CanoneType.bodyMedium.copy(fontFeatureSettings = "tnum"), color = c.accent,
                    modifier = Modifier.width(48.dp), textAlign = TextAlign.End,
                )
            }
        }
        if (u.phase != Updater.Phase.UpToDate && u.phase != Updater.Phase.Failed) Steps(u.phase)
    }
}

/** I tre passi, in fila e alla stessa larghezza: fatto (spunta), in corso (punto pieno), da fare (punto vuoto). */
@Composable
private fun Steps(phase: Updater.Phase) {
    val c = Canone.colors
    val current = when (phase) { Updater.Phase.Checking -> 0; Updater.Phase.Downloading -> 1; else -> 2 }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        listOf("Controllo", "Download", "Installazione").forEachIndexed { i, label ->
            val done = i < current
            val now = i == current
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(3.dp).clip(CircleShape)
                        .background(if (done || now) Brand.gradient else Brush.linearGradient(listOf(c.fillControl, c.fillControl))),
                )
                Text(
                    label, style = CanoneType.footnote, maxLines = 1,
                    color = if (now) c.labelPrimary else if (done) c.labelSecondary else c.labelTertiary,
                )
            }
        }
    }
}
