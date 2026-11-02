package it.ponte.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.dp
import it.ponte.Router
import it.ponte.Tx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Home a widget: riquadri di misure diverse secondo importanza e interattività.
 * Saldo con le ultime operazioni che scorrono, ultimo screenshot e ultimo testo da copiare al volo, entrate/uscite in un grafico da sfogliare,
 * attività della settimana; cuffie e impostazioni compaiono solo quando c'è qualcosa di reale da mostrare o da sistemare.
 */
@Composable
fun HomeScreen(r: Router, go: (String) -> Unit) {
    val c = Canone.colors
    val ctx = LocalContext.current
    val clips by r.clipboard.history.collectAsState()
    val files by r.files.items.collectAsState()
    val holder by r.headphones.holder.collectAsState()
    val active by r.audio.activeSource.collectAsState()
    val money by r.finance.state.collectAsState()
    val missing = missingPerms(ctx)
    val np = rememberNowPlaying()

    Box(Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 720.dp).fillMaxHeight(),
            contentPadding = PaddingValues(start = Space.s4, end = Space.s4, top = Space.s2, bottom = 24.dp + LocalBarInset.current),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            if (missing > 0) item { FixWidget(missing) { go("perm") } }
            item { MoneyWidget(r, money.totalCents, money.txs) { go("finance") } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    ShotWidget(r, files.firstOrNull { it.mime.startsWith("image/") }, Modifier.weight(1f)) { go("images") }
                    ClipWidget(r, clips.firstOrNull()?.text, Modifier.weight(1f)) { go("clips") }
                }
            }
            item {
                val audioOn = np == null && (holder != null || active != null)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    if (np != null) {
                        // musica in un quadrato, accanto l'attività della settimana della stessa altezza
                        MusicSquare(np, Modifier.weight(1f).aspectRatio(1f)) { go("audio") }
                        ActivityWidget(clips.map { it.ts }, files.map { it.ts }, Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        ActivityWidget(clips.map { it.ts }, files.map { it.ts }, if (audioOn) Modifier.weight(1f).height(150.dp) else Modifier.fillMaxWidth().height(150.dp))
                        if (audioOn) AudioWidget(r, holder, active, Modifier.weight(1f)) { go("audio") }
                    }
                }
            }
        }
    }
}

/** Contenitore dei widget: stesso bordo e raggio ovunque. */
@Composable
private fun Widget(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, onTap: (() -> Unit)? = null, onDouble: (() -> Unit)? = null, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val c = Canone.colors
    val shape = RoundedCornerShape(Radius.xl)
    Box(
        modifier.clip(shape).background(c.bgSurface).border(BorderStroke(1.dp, c.lineSeparator), shape)
            .then(if (onTap != null && onDouble != null) Modifier.dropTaps(onTap, onDouble) else if (onClick != null) Modifier.dropClickable(onClick) else Modifier),
    ) { content() }
}

@Composable
private fun FixWidget(n: Int, onClick: () -> Unit) {
    val c = Canone.colors
    Widget(Modifier.fillMaxWidth(), onClick) {
        Row(Modifier.padding(Space.s4), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            Box(Modifier.size(40.dp).drop(isDark(), tint = c.caution.copy(alpha = 0.28f)), contentAlignment = Alignment.Center) { Text("!", style = CanoneType.title3, color = c.labelPrimary) }
            Column(Modifier.weight(1f)) {
                Text(if (n == 1) "Un permesso da concedere" else "$n permessi da concedere", style = CanoneType.body, color = c.labelPrimary)
                Text("Senza, alcune funzioni non vanno", style = CanoneType.footnote, color = c.labelTertiary)
            }
            Text("›", style = CanoneType.title3, color = c.labelTertiary)
        }
    }
}

// ---------------------------------------------------------------- saldo e ultime operazioni

@Composable
private fun MoneyWidget(r: Router, total: Long, txs: List<Tx>, onClick: () -> Unit) {
    val c = Canone.colors
    val shape = RoundedCornerShape(Radius.xl)
    Column(Modifier.fillMaxWidth().clip(shape).border(BorderStroke(1.dp, c.lineSeparator), shape)) {
        Column(Modifier.fillMaxWidth().background(c.bgSurface).padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Saldo", style = CanoneType.footnote, color = c.labelTertiary)
                EyeToggle(r)
            }
            Box(Modifier.dropClickable(onClick, corner = Radius.md).padding(vertical = 2.dp)) {
                CountUp(total, CanoneType.title1.copy(fontWeight = FontWeight.SemiBold), c.labelPrimary)
            }
        }
        // il cassetto con le ultime operazioni, a scorrimento dall'alto verso il basso e dissolvenza ai bordi
        Box(Modifier.fillMaxWidth().background(c.bgSunken).padding(horizontal = Space.s4)) { OpsTicker(txs.take(8)) }
    }
}

@Composable
private fun OpsTicker(ops: List<Tx>) {
    val c = Canone.colors
    val rowH = 40.dp
    if (ops.isEmpty()) { Text("Nessuna operazione", style = CanoneType.footnote, color = c.labelTertiary, modifier = Modifier.padding(vertical = Space.s3)); return }
    val rolling = ops.size > 3
    var head by remember(ops) { mutableIntStateOf(0) }
    val shift = remember(ops) { Animatable(0f) }
    LaunchedEffect(ops) {
        if (!rolling) return@LaunchedEffect
        while (true) {
            delay(2600)
            shift.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
            head = (head - 1 + ops.size) % ops.size
            shift.snapTo(0f)
        }
    }
    val visible = if (rolling) 3 else ops.size
    Box(
        Modifier.fillMaxWidth().height(rowH * visible).clipToBounds()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (rolling) drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.28f to Color.Black, 0.72f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            },
    ) {
        val range = if (rolling) -1..visible else 0 until visible
        for (i in range) {
            val t = ops[((head + i) % ops.size + ops.size) % ops.size]
            Row(
                Modifier.fillMaxWidth().height(rowH).graphicsLayer { translationY = (i + shift.value) * rowH.toPx() },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(catColor(t.cat)))
                Text(t.note.ifEmpty { t.cat }, style = CanoneType.footnote, color = c.labelSecondary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (t.cents > 0) "+" else "") + eur(t.cents),
                    style = CanoneType.footnote.copy(fontWeight = FontWeight.Medium), color = if (t.cents > 0) c.positive else c.labelPrimary,
                    modifier = Modifier.blurIf(MoneyPrivacy.hidden),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- ultimo screenshot e ultimo testo

@Composable
private fun ShotWidget(r: Router, e: it.ponte.FileEntry?, modifier: Modifier, onClick: () -> Unit) {
    val c = Canone.colors
    val ctx = LocalContext.current
    val fx = rememberCopyFx()
    val scope = rememberCoroutineScope()
    val copy = {
        if (e != null) {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newUri(ctx.contentResolver, e.name, r.files.uriFor(e)))
            fx.play(scope)
        }
    }
    Widget(modifier.height(190.dp), onTap = copy, onDouble = onClick) {
      Box(Modifier.matchParentSize().copyBlur(fx)) {
        val bmp by produceState<Bitmap?>(null, e?.path) {
            value = e?.let { withContext(Dispatchers.IO) { thumb(it.path) } }
        }
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.65f))))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(Space.s3)) {
            Text(if (e == null) "Nessuno screenshot" else "Ultimo screenshot", style = CanoneType.footnote, color = if (bmp != null) Color.White else c.labelTertiary)
            if (e != null) Text("Tocca per copiare · due volte per aprire", style = CanoneType.footnote, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
        }
      }
        CopiedVeil(fx, Radius.xl, Modifier.matchParentSize())
    }
}

@Composable
private fun ClipWidget(r: Router, text: String?, modifier: Modifier, onClick: () -> Unit) {
    val c = Canone.colors
    val fx = rememberCopyFx()
    val scope = rememberCoroutineScope()
    Widget(modifier.height(190.dp), onTap = { if (text != null) { r.clipboard.copyLocal(text); fx.play(scope) } }, onDouble = onClick) {
        Column(Modifier.fillMaxSize().copyBlur(fx).padding(Space.s3), verticalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                Text("Ultimo copiato", style = CanoneType.footnote, color = c.labelTertiary)
                Text(text ?: "Niente per ora", style = CanoneType.body, color = if (text == null) c.labelTertiary else c.labelPrimary, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
            if (text != null) Text("Tocca per copiare · due volte per aprire", style = CanoneType.footnote, color = c.labelTertiary, maxLines = 2)
        }
        CopiedVeil(fx, Radius.xl, Modifier.matchParentSize())
    }
}

private fun thumb(path: String): Bitmap? {
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, o)
    if (o.outWidth <= 0) return null
    var s = 1
    while (o.outWidth / (s * 2) >= 400) s *= 2
    return android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })
}

// ---------------------------------------------------------------- entrate e uscite, da sfogliare

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FlowPager(txs: List<Tx>) {
    val c = Canone.colors
    val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val month = txs.filter { it.ts >= cal.timeInMillis }
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Questo mese", style = CanoneType.footnote, color = c.labelTertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                listOf("Uscite", "Entrate").forEachIndexed { i, l ->
                    val on = pager.currentPage == i
                    Box(
                        Modifier.clip(CircleShape).background(if (on) c.accentSubtle else Color.Transparent)
                            .dropClickable({ scope.launch { pager.animateScrollToPage(i) } }, corner = 100.dp).padding(horizontal = Space.s3, vertical = 4.dp),
                    ) { Text(l, style = CanoneType.footnote, color = if (on) c.accentOnSubtle else c.labelTertiary) }
                }
            }
        }
        HorizontalPager(pager, Modifier.fillMaxWidth()) { p ->
            Box(Modifier.blurIf(MoneyPrivacy.hidden)) {
                CategoryDonut(month.filter { if (p == 0) it.cents < 0 else it.cents > 0 }, if (p == 0) "Nessuna uscita questo mese." else "Nessuna entrata questo mese.")
            }
        }
    }
}

// ---------------------------------------------------------------- attività e cuffie

@Composable
private fun ActivityWidget(clipTs: List<Long>, fileTs: List<Long>, modifier: Modifier) {
    val c = Canone.colors
    val day = 86_400_000L
    val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val counts = (6 downTo 0).map { k -> val a = today - k * day; (clipTs + fileTs).count { it >= a && it < a + day } }
    val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
    var sel by remember(counts) { mutableIntStateOf(6) }
    Widget(modifier) {
        Column(Modifier.fillMaxSize().padding(Space.s3), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(if (sel == 6) "Oggi" else if (sel == 5) "Ieri" else "${6 - sel} giorni fa", style = CanoneType.footnote, color = c.labelTertiary)
                RollingText("${counts[sel]} condivisi", CanoneType.title3, c.labelPrimary)
            }
            Canvas(Modifier.fillMaxWidth().height(56.dp).pointerInput(Unit) { detectTapGestures { o -> sel = ((o.x / size.width) * 7).toInt().coerceIn(0, 6) } }) {
                val slot = size.width / 7f
                counts.forEachIndexed { i, n ->
                    val h = (size.height * n / max).coerceAtLeast(4f)
                    drawRoundRect(if (i == sel) c.accent else c.accent.copy(alpha = 0.35f), Offset(i * slot + slot * 0.2f, size.height - h), Size(slot * 0.6f, h), CornerRadius(6f))
                }
            }
        }
    }
}

@Composable
private fun AudioWidget(r: Router, holder: String?, active: String?, modifier: Modifier, onClick: () -> Unit) {
    val c = Canone.colors
    val pic = DevicePics.rememberPic(holder)
    Widget(modifier.height(150.dp), onClick) {
        Column(Modifier.fillMaxSize().padding(Space.s3), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(c.bgSunken), contentAlignment = Alignment.Center) {
                    if (pic != null) Image(pic, null, Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit)
                    else TabGlyph(4, c.accentOnSubtle, false, Modifier.size(22.dp))
                }
                Text(if (active != null) "In ascolto" else "Cuffie", style = CanoneType.footnote, color = c.labelTertiary)
            }
            Text(
                when {
                    active != null -> "Da " + r.deviceName(active)
                    holder == r.deviceId -> "Su questo telefono"
                    else -> "Su " + r.deviceName(holder ?: "")
                },
                style = CanoneType.body.copy(fontWeight = FontWeight.Medium), color = c.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
