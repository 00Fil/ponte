package it.ponte.ui

import androidx.compose.animation.core.Animatable
import android.os.Build
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
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

/**
 * Bordo di vetro spesso, stessa luce della barra: sul lato verso la luce (in alto a sinistra) il bordo si illumina, sul lato opposto passa
 * un poco di luce rifratta, e i tre colori si sfalsano di una frazione di pixel lungo il bordo (dispersione). Dentro, la goccia è limpida.
 * È un overlay procedurale: nessun livello, nessuna copia dello sfondo, quindi costa quasi niente su ogni componente.
 */
private const val BEVEL_AGSL = """
uniform float2 size;
uniform float radius;
uniform float bevel;
uniform float strength;
uniform float press;

float edgeW(float depth) {
  float t = clamp(1.0 - depth / bevel, 0.0, 1.0);
  return t * t * (3.0 - 2.0 * t);
}

half4 main(float2 fc) {
  float2 p = fc - size * 0.5;
  float2 hb = size * 0.5 - float2(radius);
  float2 q = abs(p) - hb;
  float sd = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
  float depth = -sd;
  if (depth < 0.0) return half4(0.0);
  float2 s = float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
  float2 n = (q.x > 0.0 || q.y > 0.0) ? normalize(max(q, float2(0.0001))) * s : (q.x > q.y ? float2(s.x, 0.0) : float2(0.0, s.y));
  float lit = dot(n, normalize(float2(-0.55, -0.83)));
  float w = edgeW(depth);
  float hi = max(lit, 0.0) * w;
  float lo = max(-lit, 0.0) * w * 0.45;
  float shift = 0.9;
  float wr = edgeW(depth - shift);
  float wb = edgeW(depth + shift);
  float disp = (max(lit, 0.0) + 0.35) * strength;
  float3 fringe = float3(wr - w, 0.0, wb - w) * disp;
  float a = (hi + lo) * strength * (1.0 + 0.5 * press);
  float3 rgb = float3(1.0) * a + fringe * 0.8;
  float alpha = clamp(a + abs(fringe.r) + abs(fringe.b), 0.0, 1.0);
  return half4(half3(clamp(rgb, 0.0, 1.0)), half(alpha));
}
"""

/** Una goccia: velo, bordo spesso con luce e dispersione, riflesso e contorno tonale. [press] (0..1) è letto solo in fase di disegno. */
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
        listOf(white.copy(alpha = if (dark) 0.22f else 0.60f), Color.Transparent),
        center = Offset(size.width * 0.26f, size.height * 0.20f), radius = size.height * 1.15f,
    )
    val lift = Brush.verticalGradient(0.55f to Color.Transparent, 1f to white.copy(alpha = if (dark) 0.06f else 0.22f), startY = 0f, endY = size.height)
    val wall = 2.dp.toPx()
    val hair = 1.dp.toPx()
    val edge = Goccia.edge(dark)
    val shader = if (Build.VERSION.SDK_INT >= 33) {
        android.graphics.RuntimeShader(BEVEL_AGSL).apply {
            setFloatUniform("size", size.width, size.height)
            setFloatUniform("radius", r)
            setFloatUniform("bevel", minOf(size.minDimension * 0.30f, 7.dp.toPx()))
            setFloatUniform("strength", if (dark) 0.55f else 0.95f)
        }
    } else null
    val brush = shader?.let { ShaderBrush(it) }
    onDrawBehind {
        if (fill != null) drawRoundRect(fill, cornerRadius = cr) else drawRoundRect(base, cornerRadius = cr)
        drawRoundRect(lift, cornerRadius = cr)
        drawRoundRect(spot, cornerRadius = cr)
        val p = press()
        if (p > 0.01f) drawRoundRect(white.copy(alpha = (if (dark) 0.12f else 0.22f) * p), cornerRadius = cr)
        if (brush != null && Build.VERSION.SDK_INT >= 33) {
            shader!!.setFloatUniform("press", p)
            drawRoundRect(brush, cornerRadius = cr)
        } else {
            drawRoundRect(edge.copy(alpha = edge.alpha * 0.45f), Offset(wall / 2, wall / 2), Size(size.width - wall, size.height - wall), CornerRadius((r - wall / 2).coerceAtLeast(0f)), style = Stroke(wall))
        }
        drawRoundRect(edge, Offset(hair / 2, hair / 2), Size(size.width - hair, size.height - hair), CornerRadius((r - hair / 2).coerceAtLeast(0f)), style = Stroke(hair))
    }
}

/** Stato del tocco di una goccia: quanto è premuta (0..1). Letto solo in fase di disegno. */
class DropTouch {
    val press = Animatable(0f)
}

@Composable
fun rememberDropTouch() = remember { DropTouch() }

/**
 * Tocco normale e uguale per tutti: la goccia si gonfia e si illumina quando la premi, torna a posto con una molla morbida quando
 * rilasci; il click scatta al rilascio se il dito è ancora sopra. La pressione lunga (se prevista) scatta dopo il tempo di sistema.
 */
fun Modifier.dropGestures(
    t: DropTouch,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongClick)
    this
        .semantics { role = Role.Button; this.onClick { click(); true } }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                scope.launch { t.press.animateTo(1f, Goccia.press) }
                var fired = false
                val timer = scope.launch {
                    kotlinx.coroutines.delay(viewConfiguration.longPressTimeoutMillis)
                    val lc = long
                    if (lc != null) { fired = true; haptic.performHapticFeedback(HapticFeedbackType.LongPress); lc() }
                }
                val up = waitForUpOrCancellation()
                timer.cancel()
                scope.launch { t.press.animateTo(0f, Goccia.settle) }
                if (up != null && !fired) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    click()
                }
            }
        }
}

/** Il solo contenitore si gonfia appena quando è premuto; l'etichetta e il contenuto restano fermi. */
fun Modifier.dropDeform(t: DropTouch): Modifier = this.graphicsLayer {
    val p = t.press.value
    scaleX = 1f + 0.035f * p
    scaleY = 1f + 0.012f * p
}

/** Riga/scheda cliccabile: non si muove; il contenitore si illumina appena mentre lo premi. */
fun Modifier.dropClickable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null, corner: Dp = Radius.xl): Modifier = composed {
    val t = rememberDropTouch()
    val dark = isDark()
    this.drawBehind {
        val p = t.press.value
        if (p > 0.01f) drawRoundRect(Goccia.ink(dark).copy(alpha = 0.07f * p), cornerRadius = CornerRadius(corner.toPx()))
    }.dropGestures(t, onClick, onLongClick)
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

    val segLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val dens = androidx.compose.ui.platform.LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth().height(42.dp)) {
        val pad = 3.dp
        val itemW = (maxWidth - pad * 2) / n
        val h = maxHeight - pad * 2
        // Pista ed etichette vengono registrate: la goccia le rifrange con la stessa lente della barra.
        Box(Modifier.fillMaxSize().drawWithContent { segLayer.record { this@drawWithContent.drawContent() }; drawLayer(segLayer) }.clip(androidx.compose.foundation.shape.CircleShape).background(c.bgCanvas).drop(dark)) {
        Box(Modifier.fillMaxSize().padding(pad)) {
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
        // La goccia di vetro: lente della barra sopra pista ed etichette, si gonfia appena quando la tieni premuta.
        Box(Modifier.fillMaxSize().padding(pad)) {
            Box(Modifier.graphicsLayer { translationX = follow.value * itemW.toPx(); scaleX = bubble.value; scaleY = 1f + (bubble.value - 1f) * 0.75f }) {
                Glass(
                    segLayer, { Offset(with(dens) { pad.toPx() + follow.value * itemW.toPx() }, with(dens) { pad.toPx() }) },
                    itemW, h, edge = 9.dp, amount = 7.dp, aberr = 0.30f, sat = 1.3f, magnify = 0f, blur = 0.dp,
                )
                Box(Modifier.size(itemW, h).drop(dark, tint = c.accent.copy(alpha = if (dark) 0.16f else 0.10f)))
            }
        }
    }
}
