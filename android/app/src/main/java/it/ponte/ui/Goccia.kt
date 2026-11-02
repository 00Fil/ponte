package it.ponte.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * GOCCIA: il design system dei controlli di Relay.
 *
 * Ogni parte interattiva è una goccia di vetro, come la pillola della barra delle schede:
 *  - FORMA: capsula (o rettangolo molto arrotondato); niente bordi bianchi. Il contorno è tonale (una linea del colore del testo,
 *    molto leggera) e lo spessore del vetro si vede da una parete interna morbida, un riflesso in alto a sinistra e una luce in basso.
 *  - MATERIALE: velo tinto con il colore del testo (scuro su chiaro, chiaro su scuro); sulle azioni primarie il gradiente azzurro.
 *  - MOVIMENTO: quando la tocchi la goccia si gonfia e si allunga (+7% in larghezza, +2% in altezza), si illumina, e si assesta con una molla
 *    quasi senza rimbalzo. Le selezioni (filtri, interruttori, barra) sono UNA goccia che scivola tra le opzioni, si può tenere premuta e trascinare.
 *  - COSTO: tutto è disegnato in una sola passata con gradienti calcolati una volta per dimensione, e l'animazione vive nella fase di disegno.
 *    La rifrazione vera (shader sul contenuto sotto) c'è dove sotto scorre qualcosa: la barra. Su una scheda piatta non ci sarebbe nulla da piegare.
 */
object Goccia {
    /** Molla del tocco: poco rimbalzo. */
    val press = spring<Float>(dampingRatio = 0.8f, stiffness = 520f)
    /** Molla di assestamento di una goccia che scivola da un'opzione all'altra. */
    val settle = spring<Float>(dampingRatio = 0.86f, stiffness = 320f)
    /** Molla con cui la goccia segue il dito. */
    val follow = spring<Float>(dampingRatio = 0.9f, stiffness = 480f)
    const val INFLATE_X = 0.07f
    const val INFLATE_Y = 0.02f

    fun ink(dark: Boolean) = if (dark) Color.White else Color(0xFF14204A)
    fun edge(dark: Boolean) = ink(dark).copy(alpha = if (dark) 0.14f else 0.10f)
}

@Composable
fun isDark() = isSystemInDarkTheme()

/** Una goccia: velo, parete interna, riflesso e contorno tonale. [press] (0..1) è letto solo in fase di disegno. */
fun Modifier.drop(
    dark: Boolean,
    corner: Dp? = null,
    fill: Brush? = null,
    tint: Color = Color.Unspecified,
    press: () -> Float = { 0f },
): Modifier = this.drawWithCache {
    val r = corner?.toPx() ?: (size.minDimension / 2f)
    val cr = CornerRadius(r)
    val ink = Goccia.ink(dark)
    val white = Color.White
    val base = if (tint != Color.Unspecified) tint else ink.copy(alpha = if (dark) 0.10f else 0.07f)
    val spot = Brush.radialGradient(
        listOf(white.copy(alpha = if (dark) 0.28f else 0.80f), Color.Transparent),
        center = Offset(size.width * 0.26f, size.height * 0.20f), radius = size.height * 1.15f,
    )
    val lift = Brush.verticalGradient(0.55f to Color.Transparent, 1f to white.copy(alpha = if (dark) 0.07f else 0.30f), startY = 0f, endY = size.height)
    val wall = 2.dp.toPx()
    val hair = 1.dp.toPx()
    val edge = Goccia.edge(dark)
    onDrawBehind {
        if (fill != null) drawRoundRect(fill, cornerRadius = cr) else drawRoundRect(base, cornerRadius = cr)
        drawRoundRect(lift, cornerRadius = cr)
        drawRoundRect(spot, cornerRadius = cr)
        val p = press()
        if (p > 0.01f) drawRoundRect(white.copy(alpha = (if (dark) 0.14f else 0.28f) * p), cornerRadius = cr)
        drawRoundRect(edge.copy(alpha = edge.alpha * 0.45f), Offset(wall / 2, wall / 2), Size(size.width - wall, size.height - wall), CornerRadius((r - wall / 2).coerceAtLeast(0f)), style = Stroke(wall))
        drawRoundRect(edge, Offset(hair / 2, hair / 2), Size(size.width - hair, size.height - hair), CornerRadius((r - hair / 2).coerceAtLeast(0f)), style = Stroke(hair))
    }
}

/**
 * Stato del tocco di una goccia: quanto è premuta e di quanto è tirata (in pixel, già smorzati come un elastico).
 * Letto solo in fase di disegno, quindi muovere la goccia non ricompone nulla.
 */
class DropTouch {
    val press = Animatable(0f)
    val dx = Animatable(0f)
    val dy = Animatable(0f)
}

@Composable
fun rememberDropTouch() = remember { DropTouch() }

/**
 * Comportamento di una goccia al tocco:
 *  - tocchi: si gonfia e si illumina subito, con una molla quasi senza rimbalzo;
 *  - tieni premuto e muovi il dito: la goccia si ALLUNGA verso il dito come un elastico (più tiri, più resiste) e lo segue un poco;
 *  - rilasci dentro la goccia: scatta il click e torna indietro con un piccolo rimbalzo; rilasci fuori: torna indietro senza click.
 * Sono i punti in cui il dito resta fermo a lungo che "armano" lo stiramento: un gesto veloce lascia scorrere liste e pagine.
 */
fun Modifier.dropTouch(
    t: DropTouch,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    shrink: Boolean = false,
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongClick)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val limit = with(density) { 44.dp.toPx() }
    fun rubber(d: Float) = limit * (1f - kotlin.math.exp(-kotlin.math.abs(d) / limit)) * (if (d < 0f) -1f else 1f)
    this
        .semantics { role = Role.Button; this.onClick { click(); true } }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                scope.launch { t.press.animateTo(1f, Goccia.press) }
                var fired = false
                val timer = scope.launch {
                    kotlinx.coroutines.delay(viewConfiguration.longPressTimeoutMillis)
                    val lc = long
                    if (lc != null) { fired = true; haptic.performHapticFeedback(HapticFeedbackType.LongPress); lc() }
                }
                var inside = true
                var cancelled = false
                val slop = viewConfiguration.touchSlop
                val margin = 28.dp.toPx()
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (ch.isConsumed && !ch.pressed) { cancelled = true; break }
                    if (!ch.pressed) break
                    val raw = ch.position - down.position
                    if (raw.getDistance() > slop * 2) timer.cancel()
                    inside = ch.position.x > -margin && ch.position.x < size.width + margin && ch.position.y > -margin && ch.position.y < size.height + margin
                    val armed = ch.uptimeMillis - down.uptimeMillis > 160
                    if (armed) {
                        ch.consume()
                        scope.launch { t.dx.snapTo(rubber(raw.x)); t.dy.snapTo(rubber(raw.y)) }
                    } else if (ch.isConsumed) { cancelled = true; break }
                }
                timer.cancel()
                scope.launch { t.press.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 420f)) }
                scope.launch { t.dx.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 360f)) }
                scope.launch { t.dy.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 360f)) }
                if (!cancelled && inside && !fired) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    click()
                }
            }
        }
        .graphicsLayer {
            val p = t.press.value
            val x = t.dx.value
            val y = t.dy.value
            val sx = abs(x) / size.width
            val sy = abs(y) / size.height
            translationX = x * 0.5f
            translationY = y * 0.5f
            // si allunga verso il dito: il perno sta sul lato opposto
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                0.5f - 0.42f * (x / limit).coerceIn(-1f, 1f),
                0.5f - 0.42f * (y / limit).coerceIn(-1f, 1f),
            )
            val inflate = if (shrink) -0.035f else Goccia.INFLATE_X + 0.04f
            val inflateY = if (shrink) -0.035f else Goccia.INFLATE_Y + 0.02f
            scaleX = 1f + inflate * p + sx * 0.55f - sy * 0.12f
            scaleY = 1f + inflateY * p + sy * 0.55f - sx * 0.16f
        }
}

/** Riga/scheda cliccabile: al tocco si schiaccia appena, si può tirare come un elastico, torna con un piccolo rimbalzo. */
fun Modifier.dropClickable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier = composed {
    val t = rememberDropTouch()
    this.dropTouch(t, onClick, onLongClick, shrink = true)
}

/**
 * Selettore a gocce (filtri): una goccia azzurra scivola sotto l'opzione scelta, si gonfia quando la tieni premuta e la puoi trascinare.
 * Stessi movimenti e stesse molle della barra delle schede.
 */
@Composable
fun GlassSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Canone.colors
    val dark = isDark()
    val n = options.size
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val follow = remember { Animatable(selected.toFloat()) }
    val bubble = remember { Animatable(1f) }
    var holding by remember { mutableStateOf(false) }
    val selNow by rememberUpdatedState(selected)
    val onSel by rememberUpdatedState(onSelect)
    LaunchedEffect(selected) { if (!holding) follow.animateTo(selected.toFloat(), Goccia.settle) }
    LaunchedEffect(holding) { bubble.animateTo(if (holding) 1.24f else 1f, spring(dampingRatio = 0.78f, stiffness = 360f)) }

    BoxWithConstraints(modifier.fillMaxWidth().height(42.dp).drop(dark)) {
        val pad = 3.dp
        val itemW = (maxWidth - pad * 2) / n
        val h = maxHeight - pad * 2
        Box(Modifier.fillMaxSize().padding(pad)) {
            Box(
                Modifier.size(itemW, h)
                    .graphicsLayer {
                        translationX = follow.value * itemW.toPx()
                        val stretch = (abs(follow.targetValue - follow.value) * 0.30f).coerceAtMost(0.34f)
                        scaleX = bubble.value * (1f + stretch)
                        scaleY = 1f + (bubble.value - 1f) * 0.75f - stretch * 0.28f
                    }
                    .drop(dark, tint = c.accent.copy(alpha = if (dark) 0.34f else 0.22f)),
            )
            Row(
                Modifier.fillMaxSize().pointerInput(n) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val itemPx = size.width / n
                        fun toTab(x: Float) = (x / itemPx - 0.5f).coerceIn(0f, (n - 1).toFloat())
                        holding = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch { follow.animateTo(toTab(down.position.x), Goccia.follow) }
                        var lastX = down.position.x
                        var lastTab = Math.round(toTab(lastX))
                        drag(down.id) { ch ->
                            ch.consume()
                            lastX = ch.position.x
                            val t = Math.round(toTab(lastX))
                            if (t != lastTab) { lastTab = t; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                            scope.launch { follow.animateTo(toTab(lastX), Goccia.follow) }
                        }
                        val idx = toTab(lastX).roundToInt().coerceIn(0, n - 1)
                        holding = false
                        if (idx != selNow) onSel(idx)
                        scope.launch { follow.animateTo(idx.toFloat(), Goccia.settle) }
                    }
                },
            ) {
                options.forEachIndexed { i, label ->
                    val near = (1f - abs(i - follow.value)).coerceIn(0f, 1f)
                    Box(
                        Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = label; role = Role.Tab; onClick { onSel(i); true } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label, style = CanoneType.footnote, maxLines = 1, softWrap = false,
                            fontWeight = if (near > 0.5f) FontWeight.Medium else FontWeight.Normal,
                            color = lerp(c.labelSecondary, c.accentOnSubtle, near),
                        )
                    }
                }
            }
        }
    }
}
