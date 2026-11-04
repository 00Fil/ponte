package it.ponte.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Simboli dei comandi senza bolla: forme piene e smussate fatte di vetro che rifrange quello che c'è sotto. */
enum class GK { PREV, NEXT, BACK15, FWD15, DOWN, DEVICES }

/** Ciò che sta dietro ai tasti: il [layer] registrato e la sua posizione sullo schermo. */
class Backdrop(val layer: GraphicsLayer, val pos: () -> Offset)

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun poly(p: List<Offset>) = android.graphics.Path().apply { moveTo(p[0].x, p[0].y); p.drop(1).forEach { lineTo(it.x, it.y) }; close() }

/** Arrotonda gli angoli di una forma piena. */
private fun rounded(src: android.graphics.Path, corner: Float): android.graphics.Path {
    val out = android.graphics.Path()
    android.graphics.Paint().apply { style = android.graphics.Paint.Style.FILL; pathEffect = android.graphics.CornerPathEffect(corner) }.getFillPath(src, out)
    return out
}

/** Contorno di un tratto (linea spessa) come forma piena. */
private fun stroked(src: android.graphics.Path, width: Float): android.graphics.Path {
    val out = android.graphics.Path()
    android.graphics.Paint().apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = width; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND }.getFillPath(src, out)
    return out
}

private fun mirror(p: android.graphics.Path, w: Float) = p.apply { transform(android.graphics.Matrix().apply { setScale(-1f, 1f, w / 2, 0f) }) }

/** Forma del play/pausa: [t] 0 = play (un solo triangolo), 1 = pausa (due barre). */
private fun playPausePath(w: Float, h: Float, t: Float): android.graphics.Path {
    val x0 = w * 0.24f; val x1 = w * 0.88f; val y0 = h * 0.12f; val y1 = h * 0.88f; val ym = h * 0.5f
    val xm = (x0 + x1) / 2
    val p0 = w * 0.18f; val p1 = w * 0.41f; val p2 = w * 0.59f; val p3 = w * 0.82f; val b0 = h * 0.13f; val b1 = h * 0.87f
    fun P(ax: Float, ay: Float, bx: Float, by: Float) = Offset(lerpF(ax, bx, t), lerpF(ay, by, t))
    fun top(x: Float) = y0 + (ym - y0) * (x - x0) / (x1 - x0)
    fun bot(x: Float) = y1 - (y1 - ym) * (x - x0) / (x1 - x0)
    // le due metà si sovrappongono restando sulle rette dei lati: unite danno il triangolo esatto
    val lx = xm + (1f - t) * w * 0.05f
    val left = poly(listOf(P(x0, y0, p0, b0), P(lx, top(lx), p1, b0), P(lx, bot(lx), p1, b1), P(x0, y1, p0, b1)))
    val right = poly(listOf(P(xm, top(xm), p2, b0), P(x1, ym, p3, b0), P(x1, ym, p3, b1), P(xm, bot(xm), p2, b1)))
    left.op(right, android.graphics.Path.Op.UNION)
    return rounded(left, w * 0.09f)
}

private fun glyphPath(g: GK, w: Float, h: Float): android.graphics.Path = when (g) {
    GK.NEXT, GK.PREV -> {
        val p = poly(listOf(Offset(w * 0.04f, h * 0.18f), Offset(w * 0.50f, h * 0.5f), Offset(w * 0.04f, h * 0.82f)))
        p.op(poly(listOf(Offset(w * 0.48f, h * 0.18f), Offset(w * 0.94f, h * 0.5f), Offset(w * 0.48f, h * 0.82f))), android.graphics.Path.Op.UNION)
        val r = rounded(p, w * 0.09f)
        if (g == GK.PREV) mirror(r, w) else r
    }
    GK.BACK15, GK.FWD15 -> {
        // freccia circolare in senso orario con la punta in alto
        val r = w * 0.40f; val cx = w / 2; val cy = h / 2
        val arc = android.graphics.Path().apply { addArc(cx - r, cy - r, cx + r, cy + r, -30f, 290f) }
        val p = stroked(arc, w * 0.10f)
        p.op(rounded(poly(listOf(Offset(cx + w * 0.13f, cy - r), Offset(cx - w * 0.07f, cy - r - w * 0.15f), Offset(cx - w * 0.07f, cy - r + w * 0.15f))), w * 0.035f), android.graphics.Path.Op.UNION)
        if (g == GK.BACK15) mirror(p, w) else p
    }
    GK.DOWN -> stroked(android.graphics.Path().apply { moveTo(w * 0.16f, h * 0.36f); lineTo(w * 0.5f, h * 0.66f); lineTo(w * 0.84f, h * 0.36f) }, w * 0.14f)
    GK.DEVICES -> {
        val body = android.graphics.Path().apply { addRoundRect(w * 0.22f, h * 0.04f, w * 0.78f, h * 0.96f, w * 0.16f, w * 0.16f, android.graphics.Path.Direction.CW) }
        val holes = android.graphics.Path().apply {
            addCircle(w * 0.5f, h * 0.63f, w * 0.15f, android.graphics.Path.Direction.CW)
            addCircle(w * 0.5f, h * 0.26f, w * 0.06f, android.graphics.Path.Direction.CW)
        }
        body.op(holes, android.graphics.Path.Op.DIFFERENCE); body
    }
}

/**
 * Il simbolo di vetro: dentro la forma si vede quello che c'è dietro, piegato e ingrandito come da una lente spessa, un po' sfocato e
 * lattiginoso; i bordi prendono luce in alto a sinistra e un'ombra leggera in basso; intorno un alone appena accennato.
 * Senza [backdrop] (o prima di Android 13) resta il vetro opaco bianco.
 */
@Composable
private fun GlassShape(path: () -> android.graphics.Path, size: Dp, tint: Color, backdrop: Backdrop?, modifier: Modifier = Modifier, version: Float = 0f) {
    val d = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val margin = 18.dp
    val shader = remember { if (android.os.Build.VERSION.SDK_INT >= 33 && backdrop != null) android.graphics.RuntimeShader(GLASS_AGSL) else null }
    val effect = remember(size, d.density, shader) {
        if (shader == null) null else with(d) {
            val m = margin.toPx(); val s = size.toPx()
            lensEffect(LensParams(s + 2 * m, s + 2 * m, s, s, s / 2f, s * 0.6f, s * 0.30f, 0.5f, 1.6f, 0.32f, 1.5f.dp.toPx()), shader)
        }
    }
    val shape = remember(path, version) { GenericShape { _, _ -> addPath(path().asComposePath()) } }
    Box(modifier.size(size).onGloballyPositioned { origin = it.positionInRoot() }) {
        if (backdrop != null && effect != null) {
            // corpo di vetro: il fondo rifratto, ritagliato alla forma del simbolo
            Box(Modifier.size(size).graphicsLayer { clip = true; this.shape = shape }) {
                Box(
                    Modifier.requiredSize(size + margin * 2).graphicsLayer { renderEffect = effect }
                        .drawBehind {
                            val m = margin.toPx(); val o = origin - backdrop.pos()
                            translate(-(o.x - m), -(o.y - m)) { drawLayer(backdrop.layer) }
                        },
                )
            }
        }
        Canvas(Modifier.size(size)) {
            val path0 = path()
            val w = this.size.width; val h = this.size.height; val m = minOf(w, h)
            val c = drawContext.canvas.nativeCanvas
            val glassy = backdrop != null && effect != null
            fun paint(block: android.graphics.Paint.() -> Unit) = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply(block)
            fun shifted(dx: Float, dy: Float) = android.graphics.Path(path0).apply { offset(dx, dy) }
            // tutto in un livello a parte: niente ombre fuori dalla forma, solo volume dentro
            c.saveLayer(null, null)
            // neve: bianco pieno; col fondo rifratto resta un velo che lo lascia vedere
            c.drawPath(path0, paint { style = android.graphics.Paint.Style.FILL; color = tint.copy(alpha = tint.alpha * (if (glassy) 0.62f else 0.94f)).toArgb() })
            // il bordo in basso a destra diventa più trasparente: lì il vetro è sottile e la luce passa (si vede la rifrazione)
            c.drawPath(shifted(-m * 0.05f, -m * 0.06f), paint {
                style = android.graphics.Paint.Style.FILL; color = android.graphics.Color.argb(if (glassy) 150 else 70, 0, 0, 0)
                maskFilter = android.graphics.BlurMaskFilter(m * 0.07f + 1f, android.graphics.BlurMaskFilter.Blur.OUTER)
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
            })
            // spessore: il bordo in alto a sinistra è più denso e bianco, come vetro curvo visto di taglio
            c.drawPath(shifted(m * 0.04f, m * 0.05f), paint {
                style = android.graphics.Paint.Style.FILL; color = tint.copy(alpha = 1f).toArgb()
                maskFilter = android.graphics.BlurMaskFilter(m * 0.06f + 1f, android.graphics.BlurMaskFilter.Blur.OUTER)
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_ATOP)
            })
            // spigolo vivo: una linea sottile e netta lungo il bordo alto, che sfuma verso il basso
            c.drawPath(path0, paint {
                style = android.graphics.Paint.Style.STROKE; strokeWidth = m * 0.035f
                shader = android.graphics.LinearGradient(0f, 0f, w * 0.6f, h, tint.copy(alpha = 1f).toArgb(), android.graphics.Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_ATOP)
            })
            c.restore()
        }
    }
}

/** Tasto senza contorno: il simbolo si gonfia con una molla al tocco e si muove nel senso del comando. */
@Composable
fun GlassKey(g: GK, size: Dp, onClick: () -> Unit, tint: Color = Color.White, label: String? = null, backdrop: Backdrop? = null) {
    val t = rememberDropTouch()
    val kick = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val d = LocalDensity.current
    val px = with(d) { size.toPx() }
    val path = remember(g, px) { glyphPath(g, px, px) }
    val get = remember(path) { { path } }
    Box(
        Modifier.size(size + 18.dp).graphicsLayer { val p = t.press.value; scaleX = 1f + 0.14f * p; scaleY = 1f + 0.14f * p }.clip(CircleShape)
            .dropGestures(t, { scope.launch { kick.snapTo(1f); kick.animateTo(0f, spring(0.45f, 260f)) }; onClick() }),
        contentAlignment = Alignment.Center,
    ) {
        GlassShape(get, size, tint, backdrop, Modifier.graphicsLayer {
            val k = kick.value + 0.35f * t.press.value
            when (g) {
                GK.BACK15 -> rotationZ = -70f * k
                GK.FWD15 -> rotationZ = 70f * k
                GK.NEXT -> translationX = px * 0.22f * k
                GK.PREV -> translationX = -px * 0.22f * k
                GK.DOWN -> translationY = px * 0.2f * k
                else -> {}
            }
        })
        if (label != null) Text(label, style = CanoneType.footnote.copy(fontSize = (size.value * 0.30f).sp, fontWeight = FontWeight.Bold), color = tint)
    }
}

/** Play/pausa di vetro che si trasforma subito al tocco (senza aspettare il dispositivo), poi si allinea allo stato vero. */
@Composable
fun PlayPauseKey(playing: Boolean, size: Dp, onClick: () -> Unit, tint: Color = Color.White, backdrop: Backdrop? = null) {
    var shown by remember { mutableStateOf(playing) }
    var holdUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing, holdUntil) { delay((holdUntil - System.currentTimeMillis()).coerceAtLeast(0)); shown = playing }
    val m by animateFloatAsState(if (shown) 1f else 0f, spring(0.72f, 900f), label = "playpausa")
    val t = rememberDropTouch()
    val px = with(LocalDensity.current) { size.toPx() }
    // la forma cambia a ogni passo dell'animazione: la si ricalcola solo quando serve
    val cache = remember { arrayOf<Any?>(null, null) }
    val get = remember(px) {
        {
            val k = m.coerceIn(0f, 1f)
            if (cache[0] != k) { cache[0] = k; cache[1] = playPausePath(px, px, k) }
            cache[1] as android.graphics.Path
        }
    }
    Box(
        Modifier.size(size + 18.dp).graphicsLayer { val p = t.press.value; scaleX = 1f + 0.12f * p; scaleY = 1f + 0.12f * p }.clip(CircleShape)
            .dropGestures(t, { shown = !shown; holdUntil = System.currentTimeMillis() + 2_500; onClick() }),
        contentAlignment = Alignment.Center,
    ) {
        GlassShape(get, size, tint, backdrop, version = m)
    }
}
