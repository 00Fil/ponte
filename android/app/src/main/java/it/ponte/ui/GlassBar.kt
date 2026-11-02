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

// ---------------------------------------------------------------- vetro che rifrange (Android 13+)

/**
 * Rifrazione come nel componente GlassSurface: il vetro è piatto al centro e si piega vicino al bordo, con le tre componenti di colore
 * spostate di quantità diverse (aberrazione cromatica) e una saturazione più viva. La mappa di spostamento qui non è un'immagine:
 * si ricava dalla distanza dal bordo di un rettangolo arrotondato (capsula), quindi costa solo una passata di shader.
 */
private const val GLASS_AGSL = """
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

private class LensParams(
    val w: Float, val h: Float, val rectW: Float, val rectH: Float, val radius: Float,
    val edge: Float, val amount: Float, val aberr: Float, val sat: Float, val magnify: Float, val blur: Float,
)

private fun lensEffect(p: LensParams): RenderEffect? {
    if (Build.VERSION.SDK_INT >= 33) return lensEffect33(p)
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
private fun lensEffect33(p: LensParams): RenderEffect {
    val sh = android.graphics.RuntimeShader(GLASS_AGSL)
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
private fun Glass(
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
) {
    val d = LocalDensity.current
    val margin = 28.dp
    val effect = remember(width, height, edge, amount, aberr, sat, magnify, blur, d.density) {
        with(d) {
            val m = margin.toPx()
            lensEffect(
                LensParams(
                    width.toPx() + 2 * m, height.toPx() + 2 * m, width.toPx(), height.toPx(), height.toPx() / 2f,
                    edge.toPx(), amount.toPx(), aberr, sat, magnify, blur.toPx(),
                ),
            )
        }
    }
    Box(modifier.size(width, height).graphicsLayer { clip = true; shape = CircleShape }, contentAlignment = Alignment.Center) {
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
) {
    val c = Canone.colors
    val dark = isSystemInDarkTheme()
    val shape = CircleShape
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
    val follow = remember { Animatable(0f) }
    val bubble = remember { Animatable(1f) }
    LaunchedEffect(holding) {
        // gonfia con un rimbalzo elastico quando la tocchi, si sgonfia con un altro quando la lasci
        bubble.animateTo(if (holding) 1.26f else 1f, spring(dampingRatio = if (holding) 0.36f else 0.42f, stiffness = 420f))
    }
    val pillPos = { if (holding || settling) follow.value else pos() }

    val barH = 66.dp
    BoxWithConstraints(
        modifier.onGloballyPositioned { origin = it.positionInParent() }
            .widthIn(max = 440.dp).fillMaxWidth().height(barH)
            .shadow(8.dp, shape, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.10f)),
    ) {
        val barW = maxWidth
        val padX = 6.dp
        val padY = 5.dp
        val pillW = (barW - padX * 2) / n
        val pillH = barH - padY * 2

        // 1) il vetro della barra: rifrazione leggera, sfocatura poca, colori vivi
        Glass(
            layer, { origin }, barW, barH,
            edge = 20.dp, amount = 13.dp, aberr = 0.28f, sat = 1.7f, magnify = 0f, blur = 5.dp,
        )
        // 2) velo appena percettibile (non grigio): serve solo a dare corpo
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to white.copy(alpha = if (dark) 0.10f else 0.22f),
                    1f to white.copy(alpha = if (dark) 0.03f else 0.06f),
                ),
                shape,
            ),
        )
        // 3) bordo con riflesso speculare: luce forte in alto a sinistra e in basso a destra, buio in mezzo
        Box(
            Modifier.fillMaxSize()
                .border(
                    3.dp,
                    Brush.linearGradient(listOf(white.copy(alpha = if (dark) 0.10f else 0.20f), Color.Transparent, white.copy(alpha = if (dark) 0.06f else 0.12f))),
                    shape,
                )
                .border(
                    1.dp,
                    Brush.linearGradient(
                        0f to white.copy(alpha = if (dark) 0.70f else 1f),
                        0.35f to white.copy(alpha = if (dark) 0.10f else 0.22f),
                        0.65f to white.copy(alpha = if (dark) 0.06f else 0.16f),
                        1f to white.copy(alpha = if (dark) 0.55f else 0.95f),
                    ),
                    shape,
                ),
        )

        Box(Modifier.fillMaxSize().padding(horizontal = padX, vertical = padY)) {
            // 4) la bolla: vetro più spesso (rifrange e ingrandisce di più), si allunga con la velocità
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
                    layer,
                    { Offset(origin.x + with(dens) { padX.toPx() + pillPos() * pillW.toPx() }, origin.y + with(dens) { padY.toPx() }) },
                    pillW, pillH,
                    edge = 22.dp, amount = 20.dp, aberr = 0.45f, sat = 1.9f, magnify = 0.07f, blur = 2.dp,
                )
                Box(
                    Modifier.size(pillW, pillH)
                        .background(
                            Brush.verticalGradient(listOf(white.copy(alpha = if (dark) 0.20f else 0.34f), white.copy(alpha = if (dark) 0.06f else 0.10f))),
                            shape,
                        )
                        .border(
                            1.2.dp,
                            Brush.linearGradient(
                                0f to white.copy(alpha = if (dark) 0.75f else 1f),
                                0.4f to white.copy(alpha = if (dark) 0.14f else 0.30f),
                                0.7f to white.copy(alpha = if (dark) 0.10f else 0.22f),
                                1f to white.copy(alpha = if (dark) 0.60f else 0.95f),
                            ),
                            shape,
                        ),
                )
            }
            // 5) icone ed etichette. Il dito che tiene premuto sposta la bolla; al rilascio la scheda sotto la bolla diventa attiva.
            Row(
                Modifier.fillMaxSize().pointerInput(n) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val itemPx = size.width / n
                        fun toTab(x: Float) = (x / itemPx - 0.5f).coerceIn(0f, (n - 1).toFloat())
                        val soft = spring<Float>(dampingRatio = 0.62f, stiffness = 520f)
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
                            follow.animateTo(idx.toFloat(), spring(dampingRatio = 0.5f, stiffness = 260f))
                            settling = false
                        }
                    }
                },
            ) {
                labels.forEachIndexed { i, label ->
                    val near = { (1f - abs(i - pillPos())).coerceIn(0f, 1f) }
                    val nearNow = near()
                    val tint = lerp(c.labelSecondary, c.accent, nearNow)
                    Column(
                        Modifier.weight(1f).fillMaxHeight().semantics {
                            contentDescription = label; role = Role.Tab
                            onClick { selectNow(i); true }
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        TabGlyph(glyphs[i], tint, current == i, Modifier.size(24.dp))
                        Text(
                            label, color = tint, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, softWrap = false,
                            fontWeight = if (nearNow > 0.5f) FontWeight.SemiBold else FontWeight.Medium,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
