package it.ponte.ui

import androidx.compose.animation.core.Animatable
import android.os.Build
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
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
    val press = spring<Float>(dampingRatio = 0.72f, stiffness = 280f)
    /** Molla di assestamento di una goccia che scivola da un'opzione all'altra. */
    val settle = spring<Float>(dampingRatio = 0.86f, stiffness = 320f)
    /** Molla con cui la goccia segue il dito. */
    val follow = spring<Float>(dampingRatio = 0.9f, stiffness = 480f)
    const val INFLATE_X = 0.07f
    const val INFLATE_Y = 0.02f

    /** Corpo opaco e colorato del vetro: stesso azzurro del tocco su un dispositivo (accentSubtle), non bianco. */
    fun body(dark: Boolean) = if (dark) Color(0xFF1A2F63) else Color(0xFFD3E2FF)
    /** Velo trasparente dei controlli: azzurrato, mai bianco latte. */
    fun veil(dark: Boolean) = if (dark) Color(0xFF9DB6F0).copy(alpha = 0.09f) else Color(0xFF3A5BC0).copy(alpha = 0.045f)
    /** Rilascio "liquido": rimbalza oltre lo zero e si assesta, la goccia ondeggia. */
    val release = spring<Float>(dampingRatio = 0.38f, stiffness = 170f)

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
uniform float2 light;

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
  float lit = dot(n, light);
  float w = edgeW(depth);
  float hi = max(lit, 0.0) * w;
  float lo = max(-lit, 0.0) * w * 0.45;
  float shift = 0.9;
  float wr = edgeW(depth - shift);
  float wb = edgeW(depth + shift);
  float disp = (max(lit, 0.0) + 0.45) * strength * (1.0 + press);
  float3 fringe = float3(wr - w, 0.0, wb - w) * disp;
  float a = (hi + lo) * strength * (1.0 + 1.6 * press);
  float3 rgb = float3(1.0) * a + fringe * (0.9 + 1.4 * press);
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
    touch: DropTouch? = null,
): Modifier = this.drawWithCache {
    val r = corner?.toPx() ?: (size.minDimension / 2f)
    val cr = CornerRadius(r)
    val ink = Goccia.ink(dark)
    val white = Color.White
    // velo trasparente (si vede cosa c'è sotto), appena colorato di azzurro; nessun riempimento opaco
    val base = if (tint != Color.Unspecified) tint else Goccia.veil(dark)
    val spot = Brush.radialGradient(
        listOf(white.copy(alpha = if (dark) 0.10f else 0.22f), Color.Transparent),
        center = Offset(size.width * 0.26f, size.height * 0.20f), radius = size.height * 1.0f,
    )
    val lift = Brush.verticalGradient(0.55f to Color.Transparent, 1f to white.copy(alpha = if (dark) 0.03f else 0.06f), startY = 0f, endY = size.height)
    // lucido: una fascia di luce nitida sul bordo alto, come il vetro curvo
    val gloss = Brush.verticalGradient(0f to white.copy(alpha = if (dark) 0.14f else 0.26f), 0.30f to Color.Transparent, startY = 0f, endY = size.height)
    val glossInset = 2.dp.toPx()
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
        drawRoundRect(gloss, Offset(glossInset, glossInset), Size(size.width - 2 * glossInset, size.height - 2 * glossInset), CornerRadius((r - glossInset).coerceAtLeast(0f)))
        val p = press()
        if (p > 0.01f) {
            // bagliore alla pressione: la luce rifratta si accende dove tocchi e si allarga come un liquido
            val at = touch?.pos?.takeIf { it.isSpecified } ?: Offset(size.width * 0.5f, size.height * 0.5f)
            val glow = Brush.radialGradient(
                listOf(white.copy(alpha = (if (dark) 0.42f else 0.65f) * p.coerceIn(0f, 1.2f)), Color(0xFF6FB4FF).copy(alpha = 0.22f * p.coerceIn(0f, 1f)), Color.Transparent),
                center = at, radius = maxOf(size.width, size.height) * (0.55f + 0.5f * p.coerceAtMost(1f)),
            )
            drawRoundRect(glow, cornerRadius = cr)
        }
        if (brush != null && Build.VERSION.SDK_INT >= 33) {
            shader!!.setFloatUniform("press", p)
            val sp = touch?.spin ?: Float.NaN
            if (sp.isNaN()) shader.setFloatUniform("light", -0.55f, -0.83f) else shader.setFloatUniform("light", kotlin.math.cos(sp), kotlin.math.sin(sp))
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
    var pos by mutableStateOf(Offset.Unspecified)
    var center = Offset.Zero
    var half = 0f
    var spin by mutableFloatStateOf(Float.NaN)
}

@Composable
fun rememberDropTouch() = remember { DropTouch() }

/** Attesa: lo stesso bagliore della pressione resta acceso e gira dentro la goccia. Nessun elemento aggiunto. */
@Composable
fun OrbitGlow(t: DropTouch, on: Boolean) {
    LaunchedEffect(on) {
        if (!on) return@LaunchedEffect
        t.press.animateTo(1f, Goccia.press)
        val t0 = androidx.compose.runtime.withFrameNanos { it }
        val a0 = -2.15f
        val ease = androidx.compose.animation.core.CubicBezierEasing(0.55f, 0f, 0.25f, 1f)
        try {
            while (true) {
                androidx.compose.runtime.withFrameNanos { now ->
                    val u = ((now - t0) / 1_000_000_000.0 / 1.0)
                    val n = Math.floor(u).toFloat()
                    t.spin = a0 + (n + ease.transform((u - n).toFloat())) * 2f * Math.PI.toFloat()
                }
            }
        } finally {
            t.spin = Float.NaN
            t.press.animateTo(0f, Goccia.release)
        }
    }
}

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
    val view = androidx.compose.ui.platform.LocalView.current
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongClick)
    this
        .semantics { role = Role.Button; this.onClick { click(); true } }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                t.pos = down.position
                // una vibrazione per ogni tasto premuto
                view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                scope.launch { t.press.animateTo(1f, Goccia.press) }
                var fired = false
                val timer = scope.launch {
                    kotlinx.coroutines.delay(viewConfiguration.longPressTimeoutMillis)
                    val lc = long
                    if (lc != null) { fired = true; haptic.performHapticFeedback(HapticFeedbackType.LongPress); lc() }
                }
                val up = waitForUpOrCancellation()
                timer.cancel()
                // anche un tocco rapido fa vedere tutta la goccia: arriva gonfia, poi torna ondeggiando
                scope.launch {
                    if (t.press.value < 0.9f) t.press.animateTo(1f, androidx.compose.animation.core.tween(140, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                    t.press.animateTo(0f, Goccia.release)
                }
                if (up != null && !fired) click()
            }
        }
}

/** Il solo contenitore si gonfia appena quando è premuto; l'etichetta e il contenuto restano fermi. */
fun Modifier.dropDeform(t: DropTouch): Modifier = this.graphicsLayer {
    val p = t.press.value
    scaleX = 1f + 0.10f * p
    scaleY = 1f + 0.06f * p
}

/** Bagliore di luce rifratta che si accende dove tocchi una scheda e si allarga come un liquido. */
fun Modifier.pressGlow(t: DropTouch, dark: Boolean, corner: Dp): Modifier = this.drawBehind {
    val p = t.press.value
    if (p > 0.01f) {
        drawRoundRect(Goccia.ink(dark).copy(alpha = 0.05f * p), cornerRadius = CornerRadius(corner.toPx()))
        val at = t.pos.takeIf { it.isSpecified } ?: Offset(size.width / 2, size.height / 2)
        drawRoundRect(
            Brush.radialGradient(listOf(Color.White.copy(alpha = (if (dark) 0.24f else 0.60f) * p.coerceAtMost(1f)), Color(0xFF5C9DFF).copy(alpha = 0.10f * p.coerceAtMost(1f)), Color.Transparent), center = at, radius = maxOf(size.width, size.height) * (0.5f + 0.4f * p.coerceAtMost(1f))),
            cornerRadius = CornerRadius(corner.toPx()),
        )
    }
}

/** Riga/scheda cliccabile: non si muove; il contenitore si illumina dove lo premi. */
fun Modifier.dropClickable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null, corner: Dp = Radius.xl): Modifier = composed {
    val t = rememberDropTouch()
    val dark = isDark()
    this.pressGlow(t, dark, corner).dropGestures(t, onClick, onLongClick)
}

/**
 * Un tocco e doppio tocco: il primo tocco agisce SUBITO (nessuna attesa per capire se ne arriva un secondo); se un secondo tocco
 * segue entro 320 ms scatta anche [onDouble]. Stesso bagliore di [dropClickable].
 */
fun Modifier.dropTaps(onTap: () -> Unit, onDouble: () -> Unit, corner: Dp = Radius.xl): Modifier = composed {
    val t = rememberDropTouch()
    val dark = isDark()
    val scope = rememberCoroutineScope()
    val view = androidx.compose.ui.platform.LocalView.current
    val tap by rememberUpdatedState(onTap)
    val dbl by rememberUpdatedState(onDouble)
    var lastTap by remember { mutableStateOf(0L) }
    this.pressGlow(t, dark, corner).pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            t.pos = down.position
            view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            scope.launch { t.press.animateTo(1f, Goccia.press) }
            val up = waitForUpOrCancellation()
            scope.launch {
                if (t.press.value < 0.9f) t.press.animateTo(1f, androidx.compose.animation.core.tween(140))
                t.press.animateTo(0f, Goccia.release)
            }
            if (up != null) {
                val now = System.currentTimeMillis()
                if (now - lastTap < 320L) { lastTap = 0L; dbl() } else { lastTap = now; tap() }
            }
        }
    }
}

/**
 * "Copiato" a tutta scheda: il contenuto si sfoca (morph blur), sopra sale un velo di vetro leggermente opaco con la stessa forma della scheda,
 * e la scritta si mette a fuoco. Poi tutto torna nitido. Niente riquadri: velo e sfocatura seguono il bordo arrotondato.
 */
class CopyFx { val p = Animatable(0f) }

@Composable
fun rememberCopyFx() = remember { CopyFx() }

fun CopyFx.play(scope: kotlinx.coroutines.CoroutineScope) {
    scope.launch {
        p.animateTo(1f, androidx.compose.animation.core.tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        kotlinx.coroutines.delay(700)
        p.animateTo(0f, androidx.compose.animation.core.tween(520, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
}

/** Il contenuto della scheda sotto al velo: si sfoca mentre compare "Copiato". */
fun Modifier.copyBlur(fx: CopyFx): Modifier = this.graphicsLayer {
    val v = fx.p.value
    if (v > 0.005f && Build.VERSION.SDK_INT >= 31) {
        val b = v * 22f
        renderEffect = androidx.compose.ui.graphics.BlurEffect(b, b, androidx.compose.ui.graphics.TileMode.Decal)
    } else renderEffect = null
}

@Composable
fun CopiedVeil(fx: CopyFx, corner: Dp, modifier: Modifier = Modifier) {
    val dark = isDark()
    val c = Canone.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(corner)
    Box(
        modifier.graphicsLayer { alpha = fx.p.value }.clip(shape)
            .background(c.bgSurface.copy(alpha = if (dark) 0.38f else 0.46f))
            .drop(dark, corner = corner, tint = Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Copiato", style = CanoneType.title3, color = c.labelPrimary,
            modifier = Modifier.graphicsLayer {
                val v = fx.p.value
                val s = 0.86f + 0.14f * v
                scaleX = s; scaleY = s
                if (Build.VERSION.SDK_INT >= 31 && v < 0.98f) { val b = (1f - v) * 14f + 0.01f; renderEffect = androidx.compose.ui.graphics.BlurEffect(b, b, androidx.compose.ui.graphics.TileMode.Decal) } else renderEffect = null
            },
        )
    }
}

/**
 * Icona di vetro (comandi del player): la forma stessa è vetro, senza cerchio intorno. Corpo trasparente tinto, riflesso in alto,
 * bordo di luce e i tre colori sfalsati sul bordo come una lente; premuta si gonfia e si accende.
 */
@Composable
fun GlassGlyph(kind: Int, size: Dp, onClick: () -> Unit) {
    val t = rememberDropTouch()
    val dark = isDark()
    Box(
        Modifier.size(size).dropGestures(t, onClick).graphicsLayer { val p = t.press.value; val s = 1f + 0.16f * p; scaleX = s; scaleY = s },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(size * 0.64f)) {
            val path = mediaPath(kind, this.size.width, this.size.height)
            val ink = Goccia.ink(dark)
            val px = 1.dp.toPx()
            val p = t.press.value.coerceIn(0f, 1.2f)
            // ombra morbida sotto
            translate(0f, 1.6f * px) { drawPath(path, Color.Black.copy(alpha = if (dark) 0.35f else 0.14f)) }
            // dispersione: rosso e azzurro sfalsati di una frazione di pixel ai bordi
            translate(-1.1f * px * (1f + p), 0f) { drawPath(path, Color(0xFFFF5470).copy(alpha = 0.32f)) }
            translate(1.1f * px * (1f + p), 0f) { drawPath(path, Color(0xFF4DA3FF).copy(alpha = 0.40f)) }
            // corpo di vetro: trasparente in basso, più denso in alto
            drawPath(path, Brush.verticalGradient(listOf(ink.copy(alpha = if (dark) 0.92f else 0.86f), ink.copy(alpha = if (dark) 0.50f else 0.48f))))
            clipPath(path) {
                // riflesso in alto e bagliore alla pressione
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) 0.40f else 0.55f), 0.42f to Color.Transparent))
                if (p > 0.01f) drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.7f * p.coerceAtMost(1f)), Color.Transparent), center = t.pos.takeIf { it.isSpecified }?.let { Offset(it.x - (size.toPx() - this.size.width) / 2, it.y - (size.toPx() - this.size.height) / 2) } ?: center, radius = this.size.maxDimension), radius = this.size.maxDimension)
            }
            // bordo di luce
            drawPath(path, Color.White.copy(alpha = if (dark) 0.35f else 0.55f), style = Stroke(1.2f * px))
        }
    }
}

/** Forme dei comandi: 0 indietro, 1 play, 2 pausa, 3 avanti. Angoli arrotondati come le icone di sistema. */
private fun mediaPath(kind: Int, w: Float, h: Float): androidx.compose.ui.graphics.Path {
    val p = androidx.compose.ui.graphics.Path()
    fun tri(x0: Float, x1: Float, y0: Float = h * 0.14f, y1: Float = h * 0.86f) {
        p.moveTo(x0, y0); p.lineTo(x1, (y0 + y1) / 2); p.lineTo(x0, y1); p.close()
    }
    fun triL(x0: Float, x1: Float) { p.moveTo(x1, h * 0.2f); p.lineTo(x0, h * 0.5f); p.lineTo(x1, h * 0.8f); p.close() }
    when (kind) {
        1 -> tri(w * 0.22f, w * 0.92f, h * 0.08f, h * 0.92f)
        2 -> {
            p.addRoundRect(androidx.compose.ui.geometry.RoundRect(w * 0.18f, h * 0.1f, w * 0.42f, h * 0.9f, CornerRadius(w * 0.07f)))
            p.addRoundRect(androidx.compose.ui.geometry.RoundRect(w * 0.58f, h * 0.1f, w * 0.82f, h * 0.9f, CornerRadius(w * 0.07f)))
        }
        3 -> { tri(w * 0.02f, w * 0.50f, h * 0.2f, h * 0.8f); tri(w * 0.48f, w * 0.96f, h * 0.2f, h * 0.8f) }
        else -> { triL(w * 0.04f, w * 0.52f); triL(w * 0.50f, w * 0.98f) }
    }
    return p
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


/** Pressione lunga che non disturba il tocco: se scatta, il rilascio viene consumato e il tocco normale non parte. */
fun Modifier.onLongPressOnly(onLong: () -> Unit): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val done = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation(); true }
        if (done == null) { onLong(); waitForUpOrCancellation()?.consume() }
    }
}
