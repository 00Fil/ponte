package it.ponte.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.drawWithContent
import it.ponte.Cams
import it.ponte.Router
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun hm(sec: Int) = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(sec * 1000L))

/** Le cam da mostrare: quelle che hanno mandato immagini; finché nessuna l'ha fatto, quelle collegate. */
@Composable
private fun camList(): List<Int> {
    val seen by Cams.seen.collectAsState()
    val live by Cams.live.collectAsState()
    return (if (seen.isNotEmpty()) seen else live).sorted()
}

/** Una diretta: l'ultima immagine dal motore, nero finché non c'è. */
@Composable
fun CamView(ch: Int, modifier: Modifier, fill: Boolean = true) {
    val bmp by Cams.frame(ch).collectAsState()
    Box(modifier.background(Color(0xFF0B0B0D)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = if (fill) ContentScale.Crop else ContentScale.Fit)
        else Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)))
    }
}

@Composable
private fun CamSetup(r: Router, onDone: () -> Unit) {
    var id by remember { mutableStateOf(r.prefs.camId) }
    var user by remember { mutableStateOf(r.prefs.camUser) }
    var pwd by remember { mutableStateOf(r.prefs.camPwd) }
    Column(Modifier.fillMaxWidth().padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        CField("Cloud ID", id, { id = it }, "6882972254", number = true)
        CField("Utente", user, { user = it }, "admin")
        CField("Password", pwd, { pwd = it }, "", secret = true)
        CButton("Collega", { r.prefs.camId = id; r.prefs.camUser = user; r.prefs.camPwd = pwd; Cams.disconnect(); Cams.ensure(r); onDone() }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
    }
}

/** Etichetta su vetro scuro sopra il video. */
@Composable
private fun Chip(text: String, modifier: Modifier = Modifier, dot: Color? = null) {
    Row(
        modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (dot != null) Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Text(text, style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1)
    }
}

/** Pagina Telecamere: dirette in griglia e avvisi; tocco = schermo intero. */
@Composable
fun CamsScreen(r: Router, onClose: () -> Unit) {
    val c = Canone.colors
    var ready by remember { mutableStateOf(r.prefs.camId.isNotEmpty() && r.prefs.camPwd.isNotEmpty()) }
    var full by remember { mutableIntStateOf(-1) }
    var tab by remember { mutableIntStateOf(0) }
    val st by Cams.status.collectAsState()
    val err by Cams.error.collectAsState()
    val alerts by Cams.alerts.collectAsState()
    val list = camList()
    val dens = LocalDensity.current
    val top = with(dens) { WindowInsets.statusBars.getTop(this).toDp() }.coerceAtLeast(24.dp)
    LaunchedEffect(ready) { if (ready) Cams.ensure(r) }
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(c.bgCanvas)) {
            Column(Modifier.fillMaxSize().padding(top = top + Space.s2)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s2), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    RoundGlass(42.dp, false, onClose) { Text("‹", style = CanoneType.title2, color = c.labelPrimary) }
                    Text("Telecamere", style = CanoneType.title2.copy(fontWeight = FontWeight.SemiBold), color = c.labelPrimary, modifier = Modifier.weight(1f))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(when (st) { 6 -> Color(0xFF34C759); 0 -> Tint.third; else -> c.labelTertiary }))
                    if (ready) RoundGlass(42.dp, false, { ready = false }) { Icon24(Ico.PENCIL, c.labelPrimary, Modifier.size(16.dp)) }
                }
                if (err.isNotEmpty()) Text(err, style = CanoneType.footnote, color = Tint.third, modifier = Modifier.padding(horizontal = Space.s5))
                if (!ready) CamSetup(r) { ready = true }
                else {
                    Box(Modifier.padding(horizontal = Space.s4, vertical = Space.s2)) {
                        GlassSegmented(listOf("Dal vivo", "Avvisi"), tab, { tab = it })
                    }
                    if (tab == 0) LazyVerticalGrid(
                        GridCells.Fixed(2), contentPadding = PaddingValues(start = Space.s4, end = Space.s4, top = Space.s2, bottom = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s3),
                    ) {
                        items(list) { ch ->
                            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(Radius.lg)).dropClickable({ full = ch }, corner = Radius.lg)) {
                                CamView(ch, Modifier.fillMaxSize())
                                Chip("${ch + 1}", Modifier.align(Alignment.TopStart).padding(8.dp), dot = Color(0xFFFF453A))
                            }
                        }
                    } else LazyColumn(contentPadding = PaddingValues(start = Space.s4, end = Space.s4, top = Space.s2, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                        if (alerts.isEmpty()) item { Text("Nessun avviso", style = CanoneType.body, color = c.labelTertiary, modifier = Modifier.padding(Space.s4)) }
                        items(alerts) { a ->
                            GlassTile(Modifier.fillMaxWidth(), Tint.blue, 0, { full = a.channel; pendingPlay = a.time.toInt() - 5 }) {
                                Row(Modifier.padding(Space.s3), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                                    AlertPic(a.img, Modifier.width(96.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(Radius.md)))
                                    Column(Modifier.weight(1f)) {
                                        Text(Cams.kind(a.type), style = CanoneType.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = if (a.type == "hd") c.accent else c.labelPrimary)
                                        Text("Telecamera ${a.channel + 1}", style = CanoneType.footnote, color = c.labelSecondary)
                                        Text(SimpleDateFormat("d MMM · HH:mm", Locale.ITALY).format(Date(a.time * 1000)), style = CanoneType.footnote, color = c.labelTertiary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(full >= 0, enter = fadeIn() + slideInVertically { it / 8 }, exit = fadeOut() + slideOutVertically { it / 8 }) {
                var last by remember { mutableIntStateOf(0) }
                if (full >= 0) last = full
                CamFull(last, pendingPlay.also { pendingPlay = -1 }) { full = -1 }
            }
        }
    }
}

/** Ora da cui far partire la registrazione quando si apre una cam da un avviso. */
private var pendingPlay = -1

@Composable
private fun AlertPic(url: String, modifier: Modifier) {
    val bmp by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, url) {
        value = if (url.isEmpty()) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { java.net.URL(url).openStream().use { android.graphics.BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
        }
    }
    Box(modifier.background(Color(0xFF0B0B0D)), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: PersonGlyph(Color.White.copy(alpha = 0.5f), Modifier.size(22.dp))
    }
}

// ---------------------------------------------------------------- icone dei comandi
@Composable private fun PersonGlyph(col: Color, m: Modifier) = Canvas(m) {
    val w = size.width
    drawCircle(col, w * 0.18f, Offset(w / 2, w * 0.28f))
    drawArc(col, 180f, 180f, true, Offset(w * 0.18f, w * 0.52f), Size(w * 0.64f, w * 0.7f))
}
@Composable private fun SunGlyph(col: Color, m: Modifier) = Canvas(m) {
    val w = size.width; val c = Offset(w / 2, w / 2)
    drawCircle(col, w * 0.2f, c)
    for (i in 0 until 8) { val a = Math.toRadians(i * 45.0); val d1 = w * 0.32f; val d2 = w * 0.46f
        drawLine(col, Offset(c.x + d1 * kotlin.math.cos(a).toFloat(), c.y + d1 * kotlin.math.sin(a).toFloat()), Offset(c.x + d2 * kotlin.math.cos(a).toFloat(), c.y + d2 * kotlin.math.sin(a).toFloat()), w * 0.08f, StrokeCap.Round) }
}
@Composable private fun MoonGlyph(col: Color, m: Modifier) = Canvas(m) {
    val w = size.width
    drawArc(col, 60f, 250f, false, Offset(w * 0.14f, w * 0.14f), Size(w * 0.72f, w * 0.72f), style = Stroke(w * 0.1f, cap = StrokeCap.Round))
}
@Composable private fun AutoGlyph(col: Color, m: Modifier) = Canvas(m) {
    val w = size.width
    drawCircle(col, w * 0.38f, Offset(w / 2, w / 2), style = Stroke(w * 0.09f))
    drawArc(col, 90f, 180f, true, Offset(w * 0.12f, w * 0.12f), Size(w * 0.76f, w * 0.76f))
}
@Composable private fun ClockGlyph(col: Color, m: Modifier) = Canvas(m) {
    val w = size.width; val c = Offset(w / 2, w / 2)
    drawCircle(col, w * 0.4f, c, style = Stroke(w * 0.09f))
    drawLine(col, c, Offset(w / 2, w * 0.24f), w * 0.09f, StrokeCap.Round)
    drawLine(col, c, Offset(w * 0.68f, w * 0.6f), w * 0.09f, StrokeCap.Round)
}
@Composable private fun LiveGlyph(col: Color, m: Modifier) = Canvas(m) {
    val w = size.width
    drawCircle(col, w * 0.16f, Offset(w / 2, w / 2))
    drawArc(col, -50f, 100f, false, Offset(w * 0.18f, w * 0.18f), Size(w * 0.64f, w * 0.64f), style = Stroke(w * 0.08f, cap = StrokeCap.Round))
    drawArc(col, 130f, 100f, false, Offset(w * 0.18f, w * 0.18f), Size(w * 0.64f, w * 0.64f), style = Stroke(w * 0.08f, cap = StrokeCap.Round))
}

/** Schermo intero: video al centro, sotto la stessa barra di navigazione dell'app con le voci delle cam, e la timeline delle registrazioni. */
@Composable
private fun CamFull(ch: Int, startAt: Int, onBack: () -> Unit) {
    var light by remember { mutableStateOf("smart") }
    var recs by remember { mutableStateOf(startAt > 0) }
    val playing by Cams.playing.collectAsState()
    val hd by Cams.hd.collectAsState()
    val dens = LocalDensity.current
    val navB = with(dens) { WindowInsets.navigationBars.getBottom(this).toDp() }.coerceAtLeast(16.dp)
    val topB = with(dens) { WindowInsets.statusBars.getTop(this).toDp() }.coerceAtLeast(24.dp)
    DisposableEffect(ch) {
        Cams.enter(ch)
        if (startAt > 0) Cams.play(ch, startAt)
        onDispose { Cams.stopPlay(); Cams.leave(ch) }
    }
    androidx.activity.compose.BackHandler { if (recs) { recs = false; Cams.stopPlay() } else onBack() }
    val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // il video registrato nel livello: la barra di vetro lo rifrange come fa con le pagine
        Box(Modifier.fillMaxSize().drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }) {
            CamView(ch, Modifier.fillMaxSize(), fill = false)
        }
        Row(Modifier.fillMaxWidth().padding(top = topB + Space.s2, start = Space.s4, end = Space.s4), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            RoundGlass(42.dp, false, onBack) { Text("‹", style = CanoneType.title2, color = Color.White) }
            Text("Telecamera ${ch + 1}", style = CanoneType.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Color.White, modifier = Modifier.weight(1f))
            Chip(if (playing == ch) "Registrazione" else if (hd) "Dal vivo · HD" else "Dal vivo", dot = if (playing == ch) Tint.third else Color(0xFFFF453A))
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = navB + 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            AnimatedVisibility(recs, enter = fadeIn() + slideInVertically { it / 3 }, exit = fadeOut() + slideOutVertically { it / 3 }) { Timeline(ch) }
            val labels = listOf("Auto", "Luce", "Notte", if (playing == ch) "Dal vivo" else "Video", "HD")
            val glyphs = listOf(15, 13, 14, if (playing == ch) 17 else 16, 18)
            val cur = if (recs) 3 else when (light) { "light" -> 1; "ir" -> 2; else -> 0 }
            GlassTabBar(
                layer, labels, glyphs, { cur.toFloat() }, cur,
                { i ->
                    when (i) {
                        0 -> { light = "smart"; Cams.light(ch, "smart") }
                        1 -> { light = "light"; Cams.light(ch, "light") }
                        2 -> { light = "ir"; Cams.light(ch, "ir") }
                        3 -> if (playing == ch) { Cams.stopPlay(); recs = false } else recs = !recs
                        4 -> Cams.setHd(ch, !hd)
                    }
                },
                Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

/**
 * Timeline delle registrazioni: 24 ore che scorrono sotto una lancetta fissa al centro, i pezzi registrati in blu.
 * Trascina per scegliere l'istante, lascia per rivederlo.
 */
@Composable
private fun Timeline(ch: Int) {
    val c = Canone.colors
    var day by remember { mutableIntStateOf(0) }
    val segs by Cams.records.collectAsState()
    val busy by Cams.searching.collectAsState()
    val playing by Cams.playing.collectAsState()
    val pt by Cams.playTime.collectAsState()
    val dayStart = remember(day) { (Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -day); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis / 1000).toInt() }
    LaunchedEffect(ch, dayStart) { Cams.search(ch, dayStart, dayStart + 86_399) }
    // istante sotto la lancetta (secondi)
    var at by remember(dayStart) { mutableStateOf(if (day == 0) (System.currentTimeMillis() / 1000).toInt() - 60 else dayStart + 12 * 3600) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(segs) { if (!dragging && playing != ch) segs.maxByOrNull { it.end }?.let { at = (it.end - 30).coerceAtLeast(it.start) } }
    // durante la riproduzione la lancetta segue il video (orologio del video, o un secondo alla volta)
    LaunchedEffect(playing, ch) { while (playing == ch) { kotlinx.coroutines.delay(1000); if (!dragging) Cams.playTime.value = Cams.playTime.value + 1 } }
    LaunchedEffect(pt, playing) { if (playing == ch && !dragging && pt > 0) at = pt }
    val pxPerSec = with(LocalDensity.current) { 90.dp.toPx() } / 3600f   // un'ora = 90dp
    Column(
        Modifier.padding(horizontal = Space.s4).fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .drop(true, corner = 28.dp, tint = Color(0xFF1C1C1E).copy(alpha = 0.80f)).padding(vertical = Space.s3),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Row(Modifier.padding(horizontal = Space.s3).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (0..6).forEach { d ->
                val label = when (d) { 0 -> "Oggi"; 1 -> "Ieri"; else -> SimpleDateFormat("EEE d", Locale.ITALY).format(Date(System.currentTimeMillis() - d * 86_400_000L)) }
                Box(Modifier.clip(CircleShape).background(if (d == day) c.accent else Color.White.copy(alpha = 0.10f)).dropClickable({ day = d }, corner = 16.dp).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(label, style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
                }
            }
        }
        Text(
            SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(Date(at * 1000L)) + if (busy && segs.isEmpty()) "  …" else if (segs.isEmpty()) "  · niente registrato" else "",
            style = CanoneType.title3.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = Color.White,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Canvas(
            Modifier.fillMaxWidth().height(64.dp).pointerInput(ch, dayStart) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false; Cams.play(ch, at) },
                    onDragCancel = { dragging = false },
                ) { ch2, dx -> ch2.consume(); at = (at - dx / pxPerSec).toInt().coerceIn(dayStart, dayStart + 86_399) }
            }.pointerInput(ch, dayStart, segs) {
                detectTapGestures { o -> at = (at + (o.x - size.width / 2f) / pxPerSec).toInt().coerceIn(dayStart, dayStart + 86_399); Cams.play(ch, at) }
            },
        ) {
            val mid = size.width / 2f
            fun x(t: Int) = mid + (t - at) * pxPerSec
            val trackY = size.height * 0.42f; val th = size.height * 0.30f
            drawRoundRect(Color.White.copy(alpha = 0.08f), Offset(x(dayStart), trackY - th / 2), Size(86_400 * pxPerSec, th), androidx.compose.ui.geometry.CornerRadius(th / 2))
            segs.forEach { s ->
                val a = x(s.start); val b = x(s.end)
                if (b > 0 && a < size.width) drawRoundRect(if (s.type and 2 != 0) Tint.third else c.accent, Offset(a, trackY - th / 2), Size((b - a).coerceAtLeast(3f), th), androidx.compose.ui.geometry.CornerRadius(4f))
            }
            for (hh in 0..24) {
                val xx = x(dayStart + hh * 3600)
                if (xx < -40 || xx > size.width + 40) continue
                drawLine(Color.White.copy(alpha = 0.35f), Offset(xx, trackY + th / 2 + 4f), Offset(xx, trackY + th / 2 + 12f), 2f)
                drawContext.canvas.nativeCanvas.drawText("%02d".format(hh % 24), xx, size.height - 2f, android.graphics.Paint().apply { color = 0x99FFFFFF.toInt(); textSize = 26f; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true })
            }
            // lancetta
            drawLine(Color.White, Offset(mid, 2f), Offset(mid, trackY + th / 2 + 6f), 4f, StrokeCap.Round)
        }
    }
}

/**
 * Riquadro della Home: carosello delle cam dal vivo che si scorre col dito. Tocco = pagina completa;
 * pressione lunga = la card si allarga lì in Home, con i comandi in una bolla che si apre in barra.
 */
@Composable
fun CamsTile(r: Router, modifier: Modifier, index: Int, big: Boolean = false, onBig: (Boolean) -> Unit = {}) {
    val list = camList()
    LaunchedEffect(Unit) { Cams.ensure(r) }
    val life = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(life) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) { androidx.lifecycle.Lifecycle.Event.ON_STOP -> Cams.pause(); androidx.lifecycle.Lifecycle.Event.ON_START -> { Cams.resume(); Cams.ensure(r) }; else -> {} }
        }
        life.addObserver(obs); onDispose { life.removeObserver(obs) }
    }
    val pages = androidx.compose.foundation.pager.rememberPagerState { list.size.coerceAtLeast(1) }
    val dragged by pages.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(list.size, dragged, big) { while (list.size > 1 && !dragged && !big) { kotlinx.coroutines.delay(4000); pages.animateScrollToPage((pages.currentPage + 1) % list.size) } }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(big) { if (!big) menu = false }
    androidx.activity.compose.BackHandler(enabled = big) { onBig(false) }
    GlassTile(modifier, Tint.blue, index, { if (big) Cams.ui.value = true else Cams.ui.value = true }, { onBig(!big) }) {
        if (r.prefs.camId.isEmpty()) Column(Modifier.fillMaxSize().padding(Space.s4), verticalArrangement = Arrangement.SpaceBetween) {
            IconBubble(1)
            Text("Telecamere", style = CanoneType.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = OnGlass)
        } else {
            androidx.compose.foundation.pager.HorizontalPager(pages, Modifier.fillMaxSize(), pageSpacing = 0.dp) { p ->
                val ch = list.getOrNull(p)
                if (ch != null) CamView(ch, Modifier.fillMaxSize()) else Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0D)))
            }
            Chip(list.getOrNull(pages.currentPage)?.let { "${it + 1}" } ?: "…", Modifier.align(Alignment.TopStart).padding(Space.s3), dot = Color(0xFFFF453A))
            if (list.size > 1) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(list.size) { i -> Box(Modifier.size(if (i == pages.currentPage) 12.dp else 5.dp, 5.dp).clip(CircleShape).background(Color.White.copy(alpha = if (i == pages.currentPage) 1f else 0.45f))) }
            }
            // comandi: una bolla nell'angolo che si allarga in barra
            if (big) {
                val ch = list.getOrNull(pages.currentPage) ?: 0
                val w by androidx.compose.animation.core.animateDpAsState(if (menu) 248.dp else 46.dp, androidx.compose.animation.core.spring(0.72f, 380f), label = "bolla")
                Row(
                    Modifier.align(Alignment.BottomEnd).padding(Space.s3).width(w).height(46.dp).clip(CircleShape)
                        .drop(true, tint = Color(0xFF1C1C1E).copy(alpha = 0.7f)),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End,
                ) {
                    if (w > 120.dp) {
                        listOf(15 to "smart", 13 to "light", 14 to "ir").forEach { (g, m) ->
                            Box(Modifier.size(40.dp).clip(CircleShape).dropClickable({ Cams.light(ch, m) }, corner = 20.dp), contentAlignment = Alignment.Center) { TabGlyph(g, Color.White, false, Modifier.size(20.dp)) }
                        }
                        Box(Modifier.size(40.dp).clip(CircleShape).dropClickable({ Cams.ui.value = true }, corner = 20.dp), contentAlignment = Alignment.Center) { TabGlyph(19, Color.White, false, Modifier.size(18.dp)) }
                        Box(Modifier.size(40.dp).clip(CircleShape).dropClickable({ onBig(false) }, corner = 20.dp), contentAlignment = Alignment.Center) { TabGlyph(20, Color.White, false, Modifier.size(18.dp)) }
                    }
                    Box(Modifier.size(46.dp).clip(CircleShape).dropClickable({ menu = !menu }, corner = 23.dp), contentAlignment = Alignment.Center) {
                        Text(if (menu) "×" else "•••", style = CanoneType.bodyMedium.copy(fontWeight = FontWeight.Bold), color = Color.White)
                    }
                }
            }
        }
    }
}
