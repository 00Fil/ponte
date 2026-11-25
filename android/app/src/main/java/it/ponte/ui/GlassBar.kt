package it.ponte.ui

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Spazio che il contenuto deve lasciare in basso perché la barra di vetro non copra l'ultima riga. */
val LocalBarInset = compositionLocalOf { 0.dp }
/** Altezza della barra di navigazione e della barra di stato, misurate fuori dai dialoghi (dentro i dialoghi gli inset arrivano a zero). */
val LocalNavBottom = compositionLocalOf { 0.dp }
val LocalStatusTop = compositionLocalOf { 0.dp }

// ---------------------------------------------------------------- vetro che rifrange (Android 13+)

/**
 * Rifrazione come nel componente GlassSurface: il vetro è piatto al centro e si piega vicino al bordo, con le tre componenti di colore
 * spostate di quantità diverse (aberrazione cromatica) e una saturazione più viva. La mappa di spostamento qui non è un'immagine:
 * si ricava dalla distanza dal bordo di un rettangolo arrotondato (capsula), quindi costa solo una passata di shader.
 */
internal const val GLASS_AGSL = """
uniform shader content;
uniform float2 size;      // dimensione del nodo (vetro + margine)
uniform float2 rectSize;  // dimensione del vetro visibile
uniform float radius;
uniform float edge;       // larghezza della fascia che piega la luce
uniform float amount;     // spostamento massimo sul bordo, in pixel
uniform float aberr;      // 0..1: separazione dei colori
uniform float sat;
uniform float magnify;    // 0..1: ingrandimento al centro

half4 main(float2 fc) {
  float2 c = size * 0.5;
  float2 p = fc - c;
  float2 hb = rectSize * 0.5 - float2(radius);
  float2 q = abs(p) - hb;
  float sd = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
  float depth = -sd;
  float2 s = float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
  float2 n = (q.x > 0.0 || q.y > 0.0) ? normalize(max(q, float2(0.0001))) * s : (q.x > q.y ? float2(s.x, 0.0) : float2(0.0, s.y));
  float t = clamp(1.0 - depth / edge, 0.0, 1.0);
  float bend = t * t * (3.0 - 2.0 * t);
  bend = bend * bend;
  float2 zoom = c + p * (1.0 - magnify);
  float2 base = zoom - n * bend * amount;
  float2 off = n * bend * amount * aberr;
  half4 r = content.eval(base - off);
  half4 g = content.eval(base);
  half4 b = content.eval(base + off);
  half3 rgb = half3(r.r, g.g, b.b);
  float l = dot(rgb, half3(0.213, 0.715, 0.072));
  rgb = mix(half3(l), rgb, half(sat));
  return half4(clamp(rgb, 0.0, 1.0), (r.a + g.a + b.a) / 3.0);
}
"""

internal class LensParams(
    val w: Float, val h: Float, val rectW: Float, val rectH: Float, val radius: Float,
    val edge: Float, val amount: Float, val aberr: Float, val sat: Float, val magnify: Float, val blur: Float,
)

internal fun lensEffect(p: LensParams, sh: Any? = null): RenderEffect? {
    if (Build.VERSION.SDK_INT >= 33) return lensEffect33(p, sh as? android.graphics.RuntimeShader)
    if (Build.VERSION.SDK_INT >= 31) {
        val sat = android.graphics.ColorMatrix().apply { setSaturation(p.sat) }
        return AndroidRenderEffect.createColorFilterEffect(
            android.graphics.ColorMatrixColorFilter(sat),
            AndroidRenderEffect.createBlurEffect(maxOf(p.blur, 1f), maxOf(p.blur, 1f), Shader.TileMode.CLAMP),
        ).asComposeRenderEffect()
    }
    return null
}

@RequiresApi(33)
private fun lensEffect33(p: LensParams, cached: android.graphics.RuntimeShader? = null): RenderEffect {
    // lo shader si compila una volta sola per lastra: quando la lastra cambia misura si aggiornano solo i parametri
    val sh = cached ?: android.graphics.RuntimeShader(GLASS_AGSL)
    sh.setFloatUniform("size", p.w, p.h)
    sh.setFloatUniform("rectSize", p.rectW, p.rectH)
    sh.setFloatUniform("radius", p.radius)
    sh.setFloatUniform("edge", p.edge)
    sh.setFloatUniform("amount", p.amount)
    sh.setFloatUniform("aberr", p.aberr)
    sh.setFloatUniform("sat", p.sat)
    sh.setFloatUniform("magnify", p.magnify)
    val bend = AndroidRenderEffect.createRuntimeShaderEffect(sh, "content")
    val e = if (p.blur > 0.5f) AndroidRenderEffect.createChainEffect(bend, AndroidRenderEffect.createBlurEffect(p.blur, p.blur, Shader.TileMode.CLAMP)) else bend
    return e.asComposeRenderEffect()
}

/**
 * Una lastra di vetro: ridisegna il [layer] (il contenuto sotto) con rifrazione. Il nodo è più grande del vetro di [margin] per lato,
 * così ai bordi la luce piegata trova ancora contenuto da pescare; poi tutto viene ritagliato a capsula.
 */
@Composable
internal fun Glass(
    layer: GraphicsLayer,
    origin: () -> Offset,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    edge: androidx.compose.ui.unit.Dp,
    amount: androidx.compose.ui.unit.Dp,
    aberr: Float,
    sat: Float,
    magnify: Float,
    blur: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    corner: androidx.compose.ui.unit.Dp? = null,
) {
    val d = LocalDensity.current
    val margin = 28.dp
    val shader = remember { if (Build.VERSION.SDK_INT >= 33) android.graphics.RuntimeShader(GLASS_AGSL) else null }
    val effect = remember(width, height, edge, amount, aberr, sat, magnify, blur, corner, d.density) {
        with(d) {
            val m = margin.toPx()
            lensEffect(
                LensParams(
                    width.toPx() + 2 * m, height.toPx() + 2 * m, width.toPx(), height.toPx(), corner?.toPx() ?: (height.toPx() / 2f),
                    edge.toPx(), amount.toPx(), aberr, sat, magnify, blur.toPx(),
                ),
                shader,
            )
        }
    }
    Box(modifier.size(width, height).graphicsLayer { clip = true; shape = if (corner != null) androidx.compose.foundation.shape.RoundedCornerShape(corner) else CircleShape }, contentAlignment = Alignment.Center) {
        Box(
            Modifier.requiredSize(width + margin * 2, height + margin * 2)
                .graphicsLayer { renderEffect = effect }
                .drawBehind {
                    val m = margin.toPx()
                    val o = origin()
                    translate(-(o.x - m), -(o.y - m)) { drawLayer(layer) }
                },
        )
    }
}

// ---------------------------------------------------------------- barra

/**
 * Barra delle schede in stile Liquid Glass (iOS 26/27): capsula fluttuante, sempre visibile.
 *  - il vetro rifrange davvero il contenuto che scorre sotto (piegato ai bordi, colori leggermente separati) invece di grigiarlo
 *  - la scheda attiva è una "bolla" di vetro: tocca e tieni premuto, si gonfia con un rimbalzo, la trascini e ti segue con un po' di ritardo,
 *    si allunga nel verso del movimento e al rilascio si assesta sulla scheda con un rimbalzo elastico
 *  - da Android 13 con rifrazione; Android 12 solo sfocatura; prima un vetro opaco
 */
@Composable
fun GlassTabBar(
    layer: GraphicsLayer,
    labels: List<String>,
    glyphs: List<Int>,
    pos: () -> Float,
    current: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    merge: () -> Float = { 0f },
    song: (@Composable () -> Unit)? = null,
) {
    val c = Canone.colors
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(33.dp)
    val white = Color.White
    val n = labels.size
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val currentNow by rememberUpdatedState(current)
    val selectNow by rememberUpdatedState(onSelect)
    val posNow by rememberUpdatedState(pos)
    val dens = LocalDensity.current

    var origin by remember { mutableStateOf(Offset.Zero) }
    var holding by remember { mutableStateOf(false) }
    var settling by remember { mutableStateOf(false) }
    val barLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val follow = remember { Animatable(0f) }
    val bubble = remember { Animatable(1f) }
    LaunchedEffect(holding) {
        // si gonfia quando la tocchi, ma si assesta quasi senza oscillare
        bubble.animateTo(if (holding) 1.24f else 1f, spring(dampingRatio = 0.78f, stiffness = 360f))
    }
    val pillPos = { if (holding || settling) follow.value else pos() }

    val rowH = 66.dp
    // il player agganciato allarga la barra verso l'alto: i dati del brano stanno nello stesso vetro, sopra le icone
    val m = merge()
    val extra = SONG_H * m.coerceAtLeast(0f)
    val barH = rowH + extra
    BoxWithConstraints(
        modifier.onGloballyPositioned { origin = it.positionInParent() }
            .widthIn(max = 440.dp).fillMaxWidth().height(barH)
            .shadow(8.dp, shape, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.10f)),
    ) {
        val barW = maxWidth
        val padX = 6.dp
        val padY = 5.dp
        val pillW = (barW - padX * 2) / n
        val pillH = rowH - padY * 2

        // Tutto ciò che sta nella barra (vetro, bordo, icone) viene registrato in barLayer: la bolla lo rifrange,
        // così sotto la lente anche le icone si piegano e si ingrandiscono, come su iOS.
        Box(Modifier.fillMaxSize().drawWithContent { barLayer.record { this@drawWithContent.drawContent() }; drawLayer(barLayer) }) {
            // 1) il vetro della barra: rifrazione, sfocatura poca, colori vivi
            Glass(
                layer, { origin }, barW, barH,
                edge = 12.dp, amount = 5.dp, aberr = 0.18f, sat = 1.5f, magnify = 0f, blur = 14.dp, corner = 33.dp,
            )
            // 2) velo: dà corpo e leggibilità alle icone sopra uno sfondo sfocato più denso
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to c.bgCanvas.copy(alpha = if (dark) 0.42f else 0.50f),
                        1f to c.bgCanvas.copy(alpha = if (dark) 0.30f else 0.36f),
                    ),
                    shape,
                ),
            )
            // 3) contorno tonale (niente bordi bianchi) e riflesso morbido in alto a sinistra
            Box(Modifier.fillMaxSize().drop(dark, corner = 33.dp, tint = Color.Transparent))
            // 3b) il brano, piccolo, nella stessa bolla
            if (song != null && m > 0.02f) Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(extra).graphicsLayer { alpha = ((m - 0.35f) / 0.65f).coerceIn(0f, 1f); translationY = (1f - m.coerceAtMost(1f)) * 10.dp.toPx() },
            ) { song() }
            // 4) icone ed etichette. Il dito che tiene premuto sposta la bolla; al rilascio la scheda sotto la bolla diventa attiva.
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(rowH).padding(horizontal = padX, vertical = padY).pointerInput(n) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val itemPx = size.width / n
                        fun toTab(x: Float) = (x / itemPx - 0.5f).coerceIn(0f, (n - 1).toFloat())
                        val soft = spring<Float>(dampingRatio = 0.9f, stiffness = 480f)
                        holding = true; settling = false
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch { follow.snapTo(posNow()); follow.animateTo(toTab(down.position.x), soft) }
                        var lastX = down.position.x
                        var lastTab = Math.round(toTab(lastX))
                        drag(down.id) { ch ->
                            ch.consume()
                            lastX = ch.position.x
                            val t = Math.round(toTab(lastX))
                            if (t != lastTab) { lastTab = t; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                            scope.launch { follow.animateTo(toTab(lastX), soft) }
                        }
                        val idx = toTab(lastX).roundToInt().coerceIn(0, n - 1)
                        settling = true; holding = false
                        if (idx != currentNow) selectNow(idx)
                        scope.launch {
                            follow.animateTo(idx.toFloat(), spring(dampingRatio = 0.86f, stiffness = 300f))
                            settling = false
                        }
                    }
                },
            ) {
                labels.forEachIndexed { i, label ->
                    val nearNow = (1f - abs(i - pillPos())).coerceIn(0f, 1f)
                    val tint = lerp(c.labelSecondary, c.accent, nearNow)
                    Column(
                        Modifier.weight(1f).fillMaxHeight().semantics {
                            contentDescription = label; role = Role.Tab
                            onClick { selectNow(i); true }
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        TabGlyph(glyphs[i], tint, current == i, Modifier.size(28.dp))
                    }
                }
            }
        }

        // 5) la bolla di vetro sopra la barra: rifrange la barra intera (vetro + icone), si allunga appena con la velocità
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(rowH).padding(horizontal = padX, vertical = padY)) {
            Box(
                Modifier.graphicsLayer {
                    val px = pillW.toPx()
                    translationX = pillPos() * px
                    val stretch = (abs(follow.targetValue - follow.value) * 0.30f).coerceAtMost(0.34f)
                    scaleX = bubble.value * (1f + stretch)
                    scaleY = 1f + (bubble.value - 1f) * 0.75f - stretch * 0.28f
                },
            ) {
                Glass(
                    barLayer,
                    { Offset(with(dens) { padX.toPx() + pillPos() * pillW.toPx() }, with(dens) { (SONG_H * merge().coerceAtLeast(0f) + padY).toPx() }) },
                    pillW, pillH,
                    edge = 10.dp, amount = 8.dp, aberr = 0.30f, sat = 1.3f, magnify = 0f, blur = 0.dp,
                )
                Box(Modifier.size(pillW, pillH).drop(dark, tint = Color.Transparent))
            }
        }
    }
}


/** Altezza in più della barra quando il player ci si fonde. */
val SONG_H = 44.dp
