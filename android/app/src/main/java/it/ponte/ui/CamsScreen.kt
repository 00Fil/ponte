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
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.ui.graphics.graphicsLayer
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

/** Orari del registratore: ora locale già dentro il numero, quindi si formatta in UTC. */
private fun nvrFmt(p: String, sec: Int): String = SimpleDateFormat(p, Locale.ITALY).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date(sec * 1000L))
private fun hm(sec: Int) = nvrFmt("HH:mm", sec)

/** Le cam da mostrare: quelle che hanno mandato immagini; finché nessuna l'ha fatto, quelle collegate. */
@Composable
private fun camList(all: Boolean = false): List<Int> {
    val seen by Cams.seen.collectAsState()
    val live by Cams.live.collectAsState()
    val hidden by Cams.hidden.collectAsState()
    return (if (seen.isNotEmpty()) seen else live).sorted().filter { all || it !in hidden }
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
    var edit by remember { mutableStateOf(false) }
    val hidden by Cams.hidden.collectAsState()
    val st by Cams.status.collectAsState()
    val err by Cams.error.collectAsState()
    val alerts by Cams.alerts.collectAsState()
    val list = camList(all = edit)
    val dens = LocalDensity.current
    val top = with(dens) { WindowInsets.statusBars.getTop(this).toDp() }.coerceAtLeast(LocalStatusTop.current).coerceAtLeast(24.dp)
    LaunchedEffect(ready) { if (ready) Cams.ensure(r) }
    AppOverlay(onClose) {
        Box(Modifier.fillMaxSize().background(c.bgCanvas)) {
            Column(Modifier.fillMaxSize().padding(top = top + Space.s2)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s2), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    RoundGlass(42.dp, false, onClose) { Text("‹", style = CanoneType.title2, color = c.labelPrimary) }
                    Text("Telecamere", style = CanoneType.title2.copy(fontWeight = FontWeight.SemiBold), color = c.labelPrimary, modifier = Modifier.weight(1f))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(when (st) { 6 -> Color(0xFF34C759); 0 -> Tint.third; else -> c.labelTertiary }))
                    if (ready && tab == 0) RoundGlass(42.dp, edit, { edit = !edit }) { Icon24(if (edit) Ico.CHECK else Ico.EYE, if (edit) Color.White else c.labelPrimary, Modifier.size(17.dp)) }
                    if (ready) RoundGlass(42.dp, false, { ready = false }) { Icon24(Ico.PENCIL, c.labelPrimary, Modifier.size(16.dp)) }
                }
                if (err.isNotEmpty()) Text(err, style = CanoneType.footnote, color = Tint.third, modifier = Modifier.padding(horizontal = Space.s5))
                if (!ready) CamSetup(r) { ready = true }
                else {
                    Box(Modifier.padding(horizontal = Space.s4, vertical = Space.s2)) {
                        GlassSegmented(listOf("Dal vivo", "Avvisi"), tab, { tab = it })
                    }
                    if (tab == 0) LazyVerticalGrid(
                        GridCells.Fixed(2), contentPadding = PaddingValues(start = Space.s4, end = Space.s4, top = Space.s2, bottom = 24.dp + LocalNavBottom.current),
                        horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s3),
                    ) {
                        items(list) { ch ->
                            val off = ch in hidden
                            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(Radius.lg)).dropClickable({ if (edit) Cams.toggleHidden(ch) else full = ch }, corner = Radius.lg)) {
                                CamView(ch, Modifier.fillMaxSize().graphicsLayer { alpha = if (edit && off) 0.3f else 1f })
                                Chip("${ch + 1}", Modifier.align(Alignment.TopStart).padding(8.dp), dot = Color(0xFFFF453A))
                                if (edit) Box(Modifier.align(Alignment.Center).size(44.dp).drop(true, tint = if (off) Color(0xFF1C1C1E).copy(alpha = 0.8f) else Canone.colors.accent), contentAlignment = Alignment.Center) {
                                    Icon24(if (off) Ico.EYE_OFF else Ico.EYE, Color.White, Modifier.size(20.dp))
                                }
                            }
                        }
                    } else LazyColumn(contentPadding = PaddingValues(start = Space.s4, end = Space.s4, top = Space.s2, bottom = 24.dp + LocalNavBottom.current), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                        if (alerts.isEmpty()) item { Text("Nessun avviso", style = CanoneType.body, color = c.labelTertiary, modifier = Modifier.padding(Space.s4)) }
                        items(alerts) { a ->
                            GlassTile(Modifier.fillMaxWidth(), Tint.blue, 0, { full = a.channel; pendingPlay = a.time.toInt() - 5 + Cams.tzOff() }) {
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

/** Voce di un menu a tendina di vetro: icona, nome, spunta se attiva. */
@Composable
private fun MenuRow(glyph: Int, label: String, on: Boolean, sub: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (on) Canone.colors.accent.copy(alpha = 0.22f) else Color.Transparent)
            .dropClickable(onClick, corner = 16.dp).padding(horizontal = Space.s3, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        TabGlyph(glyph, Color.White, false, Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = CanoneType.body.copy(fontWeight = FontWeight.Medium), color = Color.White)
            if (sub != null) Text(sub, style = CanoneType.footnote, color = Color.White.copy(alpha = 0.6f))
        }
        if (on) Icon24(Ico.CHECK, Canone.colors.accent, Modifier.size(18.dp))
    }
}

private val IMAGE_MODES = listOf(
    Triple("smart", "Automatico", 15), Triple("light", "Luce bianca", 13), Triple("ir", "Infrarossi", 14),
    Triple("daylight", "Sempre a colori", 13), Triple("night", "Bianco e nero", 14),
)

/** Tasto della barra: icona, titolo piccolo e valore attuale; acceso quando il suo pannello è aperto. */
@Composable
private fun BarButton(glyph: Int, title: String, value: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = rememberDropTouch()
    Row(
        modifier.fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(if (on) Canone.colors.accent.copy(alpha = 0.85f) else Color.Transparent)
            .dropGestures(t, onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TabGlyph(glyph, Color.White, false, Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = CanoneType.footnote.copy(fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp)), color = Color.White.copy(alpha = 0.7f), maxLines = 1)
            Text(value, style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

/** Schermo intero: video al centro, foto in una bolla in alto, sotto una barra chiara con Immagine, Qualità e Registrazioni. */
@Composable
private fun CamFull(ch: Int, startAt: Int, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var mode by remember { mutableStateOf("smart") }
    // pannello aperto: 0 immagine, 1 registrazioni, -1 nessuno
    var panel by remember { mutableIntStateOf(if (startAt > 0) 1 else -1) }
    val playing by Cams.playing.collectAsState()
    val hd by Cams.hd.collectAsState()
    var shot by remember { mutableStateOf(false) }
    LaunchedEffect(shot) { if (shot) { kotlinx.coroutines.delay(1500); shot = false } }
    val dens = LocalDensity.current
    val navB = with(dens) { WindowInsets.navigationBars.getBottom(this).toDp() }.coerceAtLeast(LocalNavBottom.current).coerceAtLeast(48.dp)
    val topB = with(dens) { WindowInsets.statusBars.getTop(this).toDp() }.coerceAtLeast(LocalStatusTop.current).coerceAtLeast(32.dp)
    DisposableEffect(ch) {
        Cams.enter(ch)
        if (startAt > 0) Cams.play(ch, startAt)
        onDispose { Cams.stopPlay(); Cams.leave(ch) }
    }
    androidx.activity.compose.BackHandler { if (panel >= 0) panel = -1 else onBack() }
    // il tocco sul video chiude i pannelli e non passa mai alla griglia sotto
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { panel = -1 } }) {
        CamView(ch, Modifier.fillMaxSize(), fill = false)
        Row(Modifier.fillMaxWidth().padding(top = topB + Space.s2, start = Space.s4, end = Space.s4), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            RoundGlass(44.dp, false, onBack) { Text("‹", style = CanoneType.title2, color = Color.White) }
            Column(Modifier.weight(1f)) {
                Text("Telecamera ${ch + 1}", style = CanoneType.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
                Text(if (shot) "Foto salvata in galleria" else if (playing == ch) "Registrazione" else "Dal vivo", style = CanoneType.footnote, color = if (shot) Color(0xFF34C759) else if (playing == ch) Tint.third else Color(0xFFFF6961))
            }
            RoundGlass(44.dp, false, { shot = Cams.snapshot(ctx, ch) }) { TabGlyph(22, Color.White, false, Modifier.size(20.dp)) }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = navB + 28.dp), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            AnimatedVisibility(panel == 0, enter = fadeIn() + slideInVertically { it / 4 }, exit = fadeOut() + slideOutVertically { it / 4 }) {
                Column(
                    Modifier.padding(horizontal = Space.s4).width(280.dp).clip(RoundedCornerShape(26.dp))
                        .drop(true, corner = 26.dp, tint = Color(0xFF1C1C1E).copy(alpha = 0.9f)).padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    IMAGE_MODES.forEach { (m, l, g) -> MenuRow(g, l, mode == m) { mode = m; Cams.light(ch, m); panel = -1 } }
                }
            }
            AnimatedVisibility(panel == 1, enter = fadeIn() + slideInVertically { it / 4 }, exit = fadeOut() + slideOutVertically { it / 4 }) { Recordings(ch) }
            Row(
                Modifier.padding(horizontal = Space.s4).fillMaxWidth().height(60.dp).clip(RoundedCornerShape(30.dp))
                    .drop(true, corner = 30.dp, tint = Color(0xFF1C1C1E).copy(alpha = 0.78f)).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BarButton(15, "Immagine ▾", IMAGE_MODES.firstOrNull { it.first == mode }?.second ?: "Automatico", panel == 0, Modifier.weight(1.15f)) { panel = if (panel == 0) -1 else 0 }
                BarButton(18, "Qualità", if (hd) "Alta (HD)" else "Normale", hd, Modifier.weight(1f)) { Cams.setHd(ch, !hd) }
                BarButton(16, "Registrazioni", if (playing == ch) "In riproduzione" else "Apri", panel == 1, Modifier.weight(1.15f)) { panel = if (panel == 1) -1 else 1 }
            }
        }
    }
}

/** Piccolo tasto con icona/testo e nome sotto, per i comandi della riproduzione. */
@Composable
private fun PlayBtn(label: String, big: Boolean = false, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        RoundGlass(if (big) 50.dp else 40.dp, big, onClick) { content() }
        Text(label, style = CanoneType.footnote.copy(fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp)), color = Color.White.copy(alpha = 0.7f), maxLines = 1)
    }
}

/**
 * Registrazioni e rilevamenti insieme: giorno, ora, timeline con zoom (blu = registrato, ambra = rilevato),
 * comandi di riproduzione e sotto l'elenco dei momenti rilevati con la foto. Un tocco ci salta.
 */
@Composable
private fun Recordings(ch: Int) {
    val c = Canone.colors
    var day by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableIntStateOf(1) }   // 0 giorno, 1 ora, 2 dieci minuti
    val segs by Cams.records.collectAsState()
    val busy by Cams.searching.collectAsState()
    val playing by Cams.playing.collectAsState()
    val paused by Cams.playPaused.collectAsState()
    val pt by Cams.playTime.collectAsState()
    val alerts by Cams.alerts.collectAsState()
    val off = remember { Cams.tzOff() }
    val dayStart = remember(day) { (Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -day); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis / 1000).toInt() + off }
    LaunchedEffect(ch, dayStart) { Cams.search(ch, dayStart, dayStart + 86_399) }
    var at by remember(dayStart) { mutableStateOf(if (day == 0) (System.currentTimeMillis() / 1000).toInt() + off - 60 else dayStart + 12 * 3600) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(segs) { if (!dragging && playing != ch) segs.maxByOrNull { it.end }?.let { at = (it.end - 30).coerceAtLeast(it.start) } }
    val started by Cams.playStarted.collectAsState()
    LaunchedEffect(playing, ch, started, paused) { while (playing == ch && started && !paused) { kotlinx.coroutines.delay(1000); if (!dragging) Cams.playTime.value = Cams.playTime.value + 1 } }
    LaunchedEffect(pt, playing) { if (playing == ch && !dragging && pt > 0) at = pt }
    val evs = remember(segs) { segs.filter { Cams.isEvent(it.type) }.sortedByDescending { it.start } }
    fun go(t: Int) { at = t.coerceIn(dayStart, dayStart + 86_399); Cams.play(ch, at) }
    // foto dell'avviso più vicino nel tempo allo stesso canale
    fun picFor(s: Cams.Seg): String = alerts.filter { it.channel == ch && kotlin.math.abs((it.time + off) - s.start) < 90 }.minByOrNull { kotlin.math.abs((it.time + off) - s.start) }?.img.orEmpty()
    Column(
        Modifier.padding(horizontal = Space.s4).fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .drop(true, corner = 28.dp, tint = Color(0xFF1C1C1E).copy(alpha = 0.88f)).padding(vertical = Space.s3),
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
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.s4), verticalAlignment = Alignment.CenterVertically) {
            Text(nvrFmt("HH:mm:ss", at), style = CanoneType.title2.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = Color.White, modifier = Modifier.weight(1f))
            Row(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).padding(3.dp)) {
                listOf("24 h", "1 h", "10 min").forEachIndexed { i, l ->
                    Box(Modifier.clip(CircleShape).background(if (zoom == i) Color.White.copy(alpha = 0.22f) else Color.Transparent).dropClickable({ zoom = i }, corner = 14.dp).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text(l, style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
                    }
                }
            }
        }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wPx = with(LocalDensity.current) { maxWidth.toPx() }
            val pxPerSec = wPx / when (zoom) { 0 -> 86_400f; 1 -> 3_600f; else -> 600f }
            Canvas(
                Modifier.fillMaxWidth().height(70.dp).pointerInput(ch, dayStart, zoom) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false; Cams.play(ch, at) },
                        onDragCancel = { dragging = false },
                    ) { ch2, dx -> ch2.consume(); at = (at - dx / pxPerSec).toInt().coerceIn(dayStart, dayStart + 86_399) }
                }.pointerInput(ch, dayStart, zoom) {
                    detectTapGestures { o -> go((at + (o.x - size.width / 2f) / pxPerSec).toInt()) }
                },
            ) {
                val mid = size.width / 2f
                fun x(t: Int) = mid + (t - at) * pxPerSec
                val trackY = size.height * 0.40f; val th = size.height * 0.34f
                drawRoundRect(Color.White.copy(alpha = 0.08f), Offset(x(dayStart), trackY - th / 2), Size(86_400 * pxPerSec, th), androidx.compose.ui.geometry.CornerRadius(8f))
                segs.sortedBy { if (Cams.isEvent(it.type)) 1 else 0 }.forEach { s ->
                    val a = x(s.start); val b = x(s.end)
                    if (b > 0 && a < size.width) {
                        val ev = Cams.isEvent(s.type)
                        drawRoundRect(if (ev) Tint.third else c.accent.copy(alpha = 0.85f), Offset(a, trackY - th / 2 + if (ev) -4f else 0f), Size((b - a).coerceAtLeast(if (ev) 6f else 3f), th + if (ev) 8f else 0f), androidx.compose.ui.geometry.CornerRadius(5f))
                    }
                }
                val step = when (zoom) { 0 -> 3 * 3600; 1 -> 600; else -> 60 }
                var t = dayStart
                val paint = android.graphics.Paint().apply { color = 0x99FFFFFF.toInt(); textSize = 26f; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true }
                while (t <= dayStart + 86_400) {
                    val xx = x(t)
                    if (xx > -60 && xx < size.width + 60) {
                        drawLine(Color.White.copy(alpha = 0.35f), Offset(xx, trackY + th / 2 + 6f), Offset(xx, trackY + th / 2 + 14f), 2f)
                        drawContext.canvas.nativeCanvas.drawText(nvrFmt("HH:mm", t), xx, size.height - 2f, paint)
                    }
                    t += step
                }
                drawLine(Color.White, Offset(mid, 0f), Offset(mid, trackY + th / 2 + 8f), 4f, StrokeCap.Round)
                drawCircle(Color.White, 6f, Offset(mid, 4f))
            }
        }
        if (segs.isEmpty()) Text(if (busy) "Ricerca…" else "Niente registrato in questo giorno", style = CanoneType.footnote, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = Space.s4))
        // comandi di riproduzione
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.s3), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
            PlayBtn("Evento prima", onClick = { evs.filter { it.start < at - 2 }.maxByOrNull { it.start }?.let { go(it.start - 3) } }) { TransportGlyph(0, Color.White, Modifier.size(14.dp)) }
            PlayBtn("−30 s", onClick = { go(at - 30) }) { Text("−30", style = CanoneType.footnote.copy(fontWeight = FontWeight.Bold), color = Color.White) }
            PlayBtn(if (playing != ch) "Riproduci" else if (paused) "Riprendi" else "Pausa", big = true, onClick = { if (playing != ch) go(at) else Cams.pausePlay(!paused) }) {
                TransportGlyph(if (playing == ch && !paused) 2 else 1, Color.White, Modifier.size(18.dp))
            }
            PlayBtn("+30 s", onClick = { go(at + 30) }) { Text("+30", style = CanoneType.footnote.copy(fontWeight = FontWeight.Bold), color = Color.White) }
            PlayBtn("Evento dopo", onClick = { evs.filter { it.start > at + 2 }.minByOrNull { it.start }?.let { go(it.start - 3) } }) { TransportGlyph(3, Color.White, Modifier.size(14.dp)) }
        }
        if (playing == ch) Box(Modifier.align(Alignment.CenterHorizontally).clip(CircleShape).background(Color(0xFFFF453A).copy(alpha = 0.85f)).dropClickable({ Cams.stopPlay() }, corner = 16.dp).padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text("Torna al vivo", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = Space.s4).height(1.dp).background(Color.White.copy(alpha = 0.1f)))
        Text("Rilevamenti · ${evs.size}", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(horizontal = Space.s4))
        LazyColumn(Modifier.heightIn(max = 200.dp).padding(horizontal = Space.s2), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (evs.isEmpty()) item { Text(if (busy) "…" else "Nessun movimento", style = CanoneType.body, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = Space.s3, vertical = 6.dp)) }
            items(evs) { s ->
                val on = at in s.start..s.end
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) Tint.third.copy(alpha = 0.18f) else Color.Transparent)
                        .dropClickable({ go(s.start - 3) }, corner = 14.dp).padding(horizontal = Space.s2, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3),
                ) {
                    AlertPic(picFor(s), Modifier.width(72.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(Cams.eventName(s.type), style = CanoneType.body.copy(fontWeight = FontWeight.Medium), color = if (s.type and Cams.T_HUMAN != 0) Tint.third else Color.White)
                        Text("${((s.end - s.start).coerceAtLeast(1) + 59) / 60} min", style = CanoneType.footnote, color = Color.White.copy(alpha = 0.6f))
                    }
                    Text(hm(s.start), style = CanoneType.bodyMedium.copy(fontFeatureSettings = "tnum"), color = Color.White)
                }
            }
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
