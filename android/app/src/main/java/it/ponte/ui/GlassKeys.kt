package it.ponte.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Simboli dei comandi senza bolla: forme piene, angoli smussati, materiale delle gocce (lattiginoso con riflesso in alto). */
enum class GK { PREV, NEXT, BACK15, FWD15, DOWN, DEVICES }

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Riempie i poligoni con angoli arrotondati, ombra morbida e luce dall'alto. */
private fun polyPath(p: List<Offset>) = android.graphics.Path().apply { if (p.isNotEmpty()) { moveTo(p[0].x, p[0].y); p.drop(1).forEach { lineTo(it.x, it.y) }; close() } }

private fun DrawScope.glassPolys(polys: List<List<Offset>>, corner: Float, tint: Color) {
    val path = android.graphics.Path()
    polys.forEach { path.addPath(polyPath(it)) }
    glassPath(path, corner, tint)
}

private fun DrawScope.glassPath(path: android.graphics.Path, corner: Float, tint: Color) {
    val c = drawContext.canvas.nativeCanvas
    val fx = android.graphics.CornerPathEffect(corner)
    // bagliore esterno dello stesso colore: vetro opaco che diffonde luce intorno
    val halo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL_AND_STROKE; strokeWidth = size.minDimension * 0.06f; pathEffect = fx
        color = tint.copy(alpha = tint.alpha * 0.45f).toArgb()
        maskFilter = android.graphics.BlurMaskFilter(size.minDimension * 0.16f + 2f, android.graphics.BlurMaskFilter.Blur.OUTER)
    }
    c.drawPath(path, halo)
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL; pathEffect = fx
        shader = android.graphics.LinearGradient(0f, 0f, 0f, size.height, tint.copy(alpha = tint.alpha * 0.96f).toArgb(), tint.copy(alpha = tint.alpha * 0.84f).toArgb(), android.graphics.Shader.TileMode.CLAMP)
    }
    c.drawPath(path, fill)
    val gloss = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = size.minDimension * 0.035f; pathEffect = fx
        shader = android.graphics.LinearGradient(0f, 0f, 0f, size.height * 0.5f, Color.White.copy(alpha = 0.55f).toArgb(), Color.Transparent.toArgb(), android.graphics.Shader.TileMode.CLAMP)
    }
    c.drawPath(path, gloss)
}

/** Play che diventa pausa (e viceversa) trasformando la forma: [t] 0 = play, 1 = pausa. Le due metà del triangolo si fondono in una sola forma. */
private fun DrawScope.playPause(t: Float, tint: Color) {
    val w = size.width; val h = size.height
    val x0 = w * 0.24f; val x1 = w * 0.88f; val y0 = h * 0.12f; val y1 = h * 0.88f; val ym = h * 0.5f
    val xm = (x0 + x1) / 2; val q = (ym - y0) / 2
    val p0 = w * 0.18f; val p1 = w * 0.41f; val p2 = w * 0.59f; val p3 = w * 0.82f; val b0 = h * 0.13f; val b1 = h * 0.87f
    fun P(ax: Float, ay: Float, bx: Float, by: Float) = Offset(lerpF(ax, bx, t), lerpF(ay, by, t))
    // da play le metà si sovrappongono un poco, così l'unione non lascia fessure
    val over = (1f - t) * w * 0.03f
    // il prolungamento resta sulla stessa retta dei lati del triangolo: nessun gradino tra le due metà
    val lx = xm + over
    val f = (lx - x0) / (x1 - x0)
    val left = listOf(P(x0, y0, p0, b0), P(lx, y0 + (ym - y0) * f, p1, b0), P(lx, y1 - (y1 - ym) * f, p1, b1), P(x0, y1, p0, b1))
    val right = listOf(P(xm, y0 + q, p2, b0), P(x1, ym, p3, b0), P(x1, ym, p3, b1), P(xm, y1 - q, p2, b1))
    val path = polyPath(left)
    path.op(polyPath(right), android.graphics.Path.Op.UNION)
    glassPath(path, w * 0.09f, tint)
}

private fun DrawScope.glyph(g: GK, tint: Color) {
    val w = size.width; val h = size.height
    when (g) {
        GK.NEXT, GK.PREV -> scale(if (g == GK.PREV) -1f else 1f, 1f) {
            val a = listOf(Offset(w * 0.04f, h * 0.18f), Offset(w * 0.50f, h * 0.5f), Offset(w * 0.04f, h * 0.82f))
            val b = listOf(Offset(w * 0.48f, h * 0.18f), Offset(w * 0.94f, h * 0.5f), Offset(w * 0.48f, h * 0.82f))
            glassPolys(listOf(a, b), w * 0.09f, tint)
        }
        GK.BACK15, GK.FWD15 -> scale(if (g == GK.BACK15) -1f else 1f, 1f) {
            // freccia circolare in senso orario con la punta in alto
            val r = w * 0.40f; val cx = w / 2; val cy = h / 2; val sw = w * 0.085f
            val c = drawContext.canvas.nativeCanvas
            val arc = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE; strokeWidth = sw; strokeCap = android.graphics.Paint.Cap.ROUND
                color = tint.copy(alpha = tint.alpha * 0.92f).toArgb()
            }
            c.drawArc(cx - r, cy - r, cx + r, cy + r, -30f, 290f, false, arc)
            val tip = Offset(cx + w * 0.12f, cy - r)
            glassPolys(listOf(listOf(tip, Offset(cx - w * 0.06f, cy - r - w * 0.13f), Offset(cx - w * 0.06f, cy - r + w * 0.13f))), w * 0.035f, tint)
        }
        GK.DOWN -> {
            val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.16f, h * 0.36f); lineTo(w * 0.5f, h * 0.66f); lineTo(w * 0.84f, h * 0.36f) }
            drawPath(p, Color.Black.copy(alpha = 0.18f), style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.15f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            drawPath(p, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.12f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
        GK.DEVICES -> {
            val body = listOf(Offset(w * 0.22f, h * 0.06f), Offset(w * 0.78f, h * 0.06f), Offset(w * 0.78f, h * 0.94f), Offset(w * 0.22f, h * 0.94f))
            glassPolys(listOf(body), w * 0.16f, tint)
            drawCircle(Color.Black.copy(alpha = 0.35f), w * 0.15f, Offset(w * 0.5f, h * 0.63f))
            drawCircle(Color.Black.copy(alpha = 0.35f), w * 0.06f, Offset(w * 0.5f, h * 0.26f))
        }
    }
}

/** Tasto senza contorno: il simbolo si gonfia con una molla al tocco e si muove nel senso del comando. */
@Composable
fun GlassKey(g: GK, size: Dp, onClick: () -> Unit, tint: Color = Color.White, label: String? = null) {
    val t = rememberDropTouch()
    val kick = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier.size(size + 18.dp).graphicsLayer { val p = t.press.value; scaleX = 1f + 0.14f * p; scaleY = 1f + 0.14f * p }.clip(CircleShape)
            .dropGestures(t, { scope.launch { kick.snapTo(1f); kick.animateTo(0f, spring(0.45f, 260f)) }; onClick() }),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val k = kick.value + 0.35f * t.press.value
            when (g) {
                GK.BACK15 -> rotate(-70f * k) { glyph(g, tint) }
                GK.FWD15 -> rotate(70f * k) { glyph(g, tint) }
                GK.NEXT -> translate(this.size.width * 0.22f * k, 0f) { glyph(g, tint) }
                GK.PREV -> translate(-this.size.width * 0.22f * k, 0f) { glyph(g, tint) }
                GK.DOWN -> translate(0f, this.size.height * 0.2f * k) { glyph(g, tint) }
                else -> glyph(g, tint)
            }
        }
        if (label != null) Text(label, style = CanoneType.footnote.copy(fontSize = (size.value * 0.30f).sp, fontWeight = FontWeight.Bold), color = tint)
    }
}

/** Play/pausa che si trasforma subito al tocco (senza aspettare il dispositivo), poi si allinea allo stato vero. */
@Composable
fun PlayPauseKey(playing: Boolean, size: Dp, onClick: () -> Unit, tint: Color = Color.White) {
    var shown by remember { mutableStateOf(playing) }
    var holdUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing, holdUntil) { delay((holdUntil - System.currentTimeMillis()).coerceAtLeast(0)); shown = playing }
    val m by animateFloatAsState(if (shown) 1f else 0f, spring(0.72f, 900f), label = "playpausa")
    val t = rememberDropTouch()
    Box(
        Modifier.size(size + 18.dp).graphicsLayer { val p = t.press.value; scaleX = 1f + 0.12f * p; scaleY = 1f + 0.12f * p }.clip(CircleShape)
            .dropGestures(t, { shown = !shown; holdUntil = System.currentTimeMillis() + 2_500; onClick() }),
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(size)) { playPause(m.coerceIn(0f, 1f), tint) } }
}
