package it.ponte.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import it.ponte.Entry
import it.ponte.Nut
import it.ponte.Weigh
import it.ponte.dayKey
import it.ponte.keyTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** Colori dei macronutrienti: tre toni della stessa famiglia blu e l'ambra del sistema. */
internal val MacroP = Color(0xFF2F5FFF)
internal val MacroC = Color(0xFF6FB4FF)
internal val MacroF = Color(0xFFFFB347)

/** Numero per le etichette: intero da 100 in su, un decimale sotto (con virgola). */
internal fun num(x: Float): String =
    if (x >= 100f || x == 0f) x.roundToInt().toString() else String.format(Locale.ITALY, "%.1f", x).removeSuffix(",0")

internal fun kg(x: Float): String = String.format(Locale.ITALY, "%.1f", x)
internal fun parseF(s: String): Float? = s.trim().replace(',', '.').toFloatOrNull()

internal fun dayTotal(entries: List<Entry>, day: String): Nut = entries.filter { it.day == day }.fold(Nut()) { a, e -> a + e.n }

/** "Mer 25 nov" per le etichette brevi di un giorno. */
internal fun shortDay(key: String): String = cap(fmt(keyTime(key), "EEE d MMM"))

/** Foglio dal basso su sfondo scurito: tocco fuori per chiudere. */
@Composable
internal fun BodySheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = Canone.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.9f).dp).clip(RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl)).background(c.bgSurface)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                    .imePadding().verticalScroll(rememberScrollState()).padding(start = Space.s5, end = Space.s5, top = Space.s5, bottom = Space.s5),
                verticalArrangement = Arrangement.spacedBy(Space.s4),
            ) { content() }
        }
    }
}

/** Anello di avanzamento: [fraction] 0..1+, oltre 1 il giro si chiude e resta in ambra. */
@Composable
internal fun Ring(fraction: Float, modifier: Modifier, width: Float = 14f, color: Color = Canone.colors.accent, content: @Composable () -> Unit = {}) {
    val c = Canone.colors
    val v by animateFloatAsState(fraction.coerceIn(0f, 1.2f), spring(0.9f, 120f), label = "anello")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = width.dp.toPx()
            val inset = w / 2
            val sz = Size(size.width - w, size.height - w)
            drawArc(c.bgSunken, 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(w, cap = StrokeCap.Round))
            val over = v > 1f
            drawArc(if (over) Tint.third else color, -90f, 360f * v.coerceAtMost(1f), false, Offset(inset, inset), sz, style = Stroke(w, cap = StrokeCap.Round))
        }
        content()
    }
}

/** Riga di un macronutriente: nome, grammi / obiettivo e barra. */
@Composable
internal fun MacroBar(label: String, value: Float, goal: Int, color: Color, modifier: Modifier = Modifier) {
    val c = Canone.colors
    val f by animateFloatAsState(if (goal > 0) (value / goal).coerceIn(0f, 1f) else 0f, spring(0.9f, 140f), label = "macro")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(label, style = CanoneType.footnote, color = c.labelSecondary, maxLines = 1)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(num(value), style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = c.labelPrimary)
                Text(" / $goal g", style = CanoneType.footnote.copy(fontSize = 11.sp, fontFeatureSettings = "tnum"), color = c.labelTertiary)
            }
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.bgSunken)) {
            Box(Modifier.fillMaxWidth(f.coerceAtLeast(0.001f)).height(6.dp).clip(CircleShape).background(color))
        }
    }
}

/** Una cifra con la sua etichetta sopra. */
@Composable
internal fun Stat(label: String, value: String, modifier: Modifier = Modifier, tint: Color = Canone.colors.labelPrimary) {
    val c = Canone.colors
    Column(modifier) {
        Text(label, style = CanoneType.footnote.copy(fontSize = 11.sp), color = c.labelTertiary, maxLines = 1)
        Text(value, style = CanoneType.bodyMedium.copy(fontFeatureSettings = "tnum"), color = tint, maxLines = 1)
    }
}

// ---------------------------------------------------------------- immagini

internal object BodyImages {
    private val cache = object : LruCache<String, ImageBitmap>(120) {}
    fun get(f: File, maxSide: Int = 0): ImageBitmap? {
        if (!f.exists()) return null
        val key = f.path + "|" + f.lastModified() + "|" + maxSide
        cache.get(key)?.let { return it }
        val opt = BitmapFactory.Options()
        if (maxSide > 0) {
            opt.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.path, opt)
            var s = 1
            while (opt.outHeight / (s * 2) >= maxSide) s *= 2
            opt.inJustDecodeBounds = false; opt.inSampleSize = s
        }
        val bmp: Bitmap = BitmapFactory.decodeFile(f.path, opt) ?: return null
        return bmp.asImageBitmap().also { cache.put(key, it) }
    }
}

@Composable
internal fun rememberImage(f: File?, maxSide: Int = 0): ImageBitmap? {
    if (f == null) return null
    val img by produceState<ImageBitmap?>(null, f.path, f.lastModified()) {
        value = withContext(Dispatchers.IO) { BodyImages.get(f, maxSide) }
    }
    return img
}

/** Disegna un'immagine riempiendo il riquadro (taglia ai bordi come "crop"). */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCrop(img: ImageBitmap, alpha: Float = 1f) {
    val s = maxOf(size.width / img.width, size.height / img.height)
    val w = img.width * s; val h = img.height * s
    val dx = (size.width - w) / 2f; val dy = (size.height - h) / 2f
    drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height), dstOffset = IntOffset(dx.roundToInt(), dy.roundToInt()), dstSize = IntSize(w.roundToInt(), h.roundToInt()), alpha = alpha)
}

@Composable
internal fun PhotoThumb(f: File?, modifier: Modifier, maxSide: Int = 480) {
    val c = Canone.colors
    val img = rememberImage(f, maxSide)
    Box(modifier.background(c.bgSunken)) {
        if (img != null) Canvas(Modifier.fillMaxSize()) { drawCrop(img) }
    }
}

// ---------------------------------------------------------------- grafici

/** Media mobile esponenziale: la tendenza del peso senza il rumore del giorno. */
internal fun trend(w: List<Weigh>): List<Float> {
    var t = 0f
    return w.mapIndexed { i, x -> t = if (i == 0) x.kg else t + 0.25f * (x.kg - t); t }
}

/** Peso nel tempo: punti reali, linea di tendenza e (se impostato) l'obiettivo tratteggiato. */
@Composable
internal fun WeightChart(points: List<Weigh>, goalKg: Float?, modifier: Modifier) {
    val c = Canone.colors
    val tm = rememberTextMeasurer()
    val lab = CanoneType.footnote.copy(fontSize = 10.sp, color = c.labelTertiary)
    val tr = remember(points) { trend(points) }
    Canvas(modifier) {
        if (points.isEmpty()) return@Canvas
        val padL = 34.dp.toPx(); val padB = 18.dp.toPx(); val padT = 6.dp.toPx(); val padR = 6.dp.toPx()
        val all = points.map { it.kg } + listOfNotNull(goalKg)
        var lo = all.min(); var hi = all.max()
        if (hi - lo < 1.5f) { val m = (hi + lo) / 2; lo = m - 0.75f; hi = m + 0.75f }
        val pad = (hi - lo) * 0.12f; lo -= pad; hi += pad
        val t0 = keyTime(points.first().day); val t1 = keyTime(points.last().day).coerceAtLeast(t0 + 86_400_000L)
        val w = size.width - padL - padR; val h = size.height - padT - padB
        fun x(day: String) = padL + w * ((keyTime(day) - t0).toFloat() / (t1 - t0))
        fun y(v: Float) = padT + h * (1f - (v - lo) / (hi - lo))
        // griglia e scala
        for (k in 0..2) {
            val v = lo + (hi - lo) * k / 2f
            drawLine(c.lineSeparator, Offset(padL, y(v)), Offset(size.width - padR, y(v)), 1.dp.toPx())
            val r = tm.measure(kg(v), lab)
            drawText(r, topLeft = Offset(padL - r.size.width - 6.dp.toPx(), y(v) - r.size.height / 2f))
        }
        goalKg?.let { g ->
            drawLine(Tint.third, Offset(padL, y(g)), Offset(size.width - padR, y(g)), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
        // area sotto la tendenza
        if (points.size > 1) {
            val area = Path().apply {
                moveTo(x(points.first().day), y(tr.first()))
                points.forEachIndexed { i, p -> lineTo(x(p.day), y(tr[i])) }
                lineTo(x(points.last().day), padT + h); lineTo(x(points.first().day), padT + h); close()
            }
            drawPath(area, Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.28f), Color.Transparent), startY = padT, endY = padT + h))
            val line = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.day), y(tr[i])) else lineTo(x(p.day), y(tr[i])) } }
            drawPath(line, c.accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        points.forEach { p -> drawCircle(c.accent.copy(alpha = if (points.size > 40) 0.5f else 0.9f), if (points.size > 40) 2.dp.toPx() else 3.dp.toPx(), Offset(x(p.day), y(p.kg))) }
        val a = tm.measure(fmt(t0, "d MMM"), lab); val b = tm.measure(fmt(t1, "d MMM"), lab)
        drawText(a, topLeft = Offset(padL, size.height - a.size.height))
        drawText(b, topLeft = Offset(size.width - padR - b.size.width, size.height - b.size.height))
    }
}

/** Calorie degli ultimi sette giorni contro l'obiettivo. */
@Composable
internal fun WeekBars(entries: List<Entry>, goal: Int, modifier: Modifier) {
    val c = Canone.colors
    val now = System.currentTimeMillis()
    val days = (6 downTo 0).map { dayKey(addDays(now, -it)) }
    val vals = days.map { dayTotal(entries, it).kcal }
    val top = maxOf(vals.maxOrNull() ?: 0f, goal * 1.15f, 1f)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val gap = 10.dp.toPx(); val bw = (size.width - gap * 6) / 7f
            vals.forEachIndexed { i, v ->
                val hh = size.height * (v / top)
                val col = if (i == 6) c.accent else if (v > goal * 1.05f) Tint.third.copy(alpha = 0.7f) else c.accent.copy(alpha = 0.35f)
                if (v > 0f) drawRoundRect(col, Offset(i * (bw + gap), size.height - hh), Size(bw, hh.coerceAtLeast(4.dp.toPx())), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
                else drawRoundRect(c.bgSunken, Offset(i * (bw + gap), size.height - 4.dp.toPx()), Size(bw, 4.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
            }
            val gy = size.height * (1f - goal / top)
            drawLine(c.labelTertiary.copy(alpha = 0.6f), Offset(0f, gy), Offset(size.width, gy), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            days.forEachIndexed { i, d ->
                Text(
                    fmt(keyTime(d), "EEEEE").uppercase(), style = CanoneType.footnote.copy(fontSize = 11.sp), color = if (i == 6) c.labelPrimary else c.labelTertiary,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}
