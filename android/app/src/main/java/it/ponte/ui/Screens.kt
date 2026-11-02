package it.ponte.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.size
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.roundToInt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.ComponentActivity
import it.ponte.FileEntry
import it.ponte.Root
import it.ponte.Device
import it.ponte.Router
import it.ponte.Updater
import it.ponte.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

@Composable
internal fun ScreenList(title: String, content: LazyListScope.() -> Unit) {
    val c = Canone.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 720.dp).fillMaxHeight(),
            contentPadding = PaddingValues(start = Space.s4, end = Space.s4, top = Space.s5, bottom = Space.s5 + LocalBarInset.current),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            // Il titolo sta nella testata animata sopra la pagina.
            content()
        }
    }
}

/** Prima scheda: le fibre animate dietro lo stato generale. Non reagisce al tocco, così non ruba lo scorrimento. */
@Composable
internal fun HeroCard(title: String, sub: String, height: androidx.compose.ui.unit.Dp = 176.dp) {
    val c = Canone.colors
    val shape = RoundedCornerShape(Radius.xl)
    val white = androidx.compose.ui.graphics.Color.White
    Box(Modifier.fillMaxWidth().height(height).clip(shape).background(Brand.gradient)) {
        SlatField(Modifier.matchParentSize(), onBrand = true)
        Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0.4f to androidx.compose.ui.graphics.Color.Transparent, 1f to androidx.compose.ui.graphics.Color(0x990E28AA))))
        Column(Modifier.align(Alignment.BottomStart).padding(Space.s5), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = CanoneType.title2, color = white)
            Text(sub, style = CanoneType.footnote, color = white.copy(alpha = 0.85f))
        }
    }
}

@Composable
internal fun SectionTitle(text: String) = Text(text, style = CanoneType.title3, color = Canone.colors.labelPrimary)

internal fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
internal fun imagesPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
internal fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
internal fun listenerEnabled(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
private fun time(ts: Long, ctx: Context) = DateFormat.getTimeFormat(ctx).format(Date(ts))
internal fun dateTime(ts: Long, ctx: Context) = DateFormat.getMediumDateFormat(ctx).format(Date(ts)) + " " + time(ts, ctx)

// ---------------------------------------------------------------- Archivio

private fun decodeThumb(path: String): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    if (o.outWidth <= 0) return null
    var s = 1
    while (o.outWidth / (s * 2) >= 480) s *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
}

/**
 * Condividi: in alto i dispositivi e i tasti rapidi (invia appunti, invia file, backup), poi le tre schede Appunti, Immagini e File.
 * Immagini e file si possono ordinare in cartelle; tenendo premuto un elemento compare l'anteprima con le azioni.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShareScreen(r: Router, tabKey: String?, onTab: (String?) -> Unit, onBackup: () -> Unit) {
    val ctx = LocalContext.current
    val c = Canone.colors
    val all by r.files.items.collectAsState()
    val history by r.clipboard.history.collectAsState()
    val keys = listOf("clips", "images", "files")
    val tabSel = keys.indexOf(tabKey).coerceAtLeast(0)
    // La scheda mostrata segue quella scelta a metà sfocatura: il cambio di contenuto (il momento pesante) avviene quando è invisibile.
    var tab by remember { mutableIntStateOf(tabSel) }
    var folders by remember { mutableStateOf(r.prefs.folders) }
    var placed by remember { mutableStateOf(r.prefs.fileFolders) }
    var folder by remember { mutableStateOf<String?>(null) }
    var newFolder by remember { mutableStateOf<String?>(null) }
    var peek by remember { mutableStateOf<FileEntry?>(null) }
    // passaggio tra le schede: il contenuto nuovo entra sfocato e si mette a fuoco (non scatta)
    val fx = remember { androidx.compose.animation.core.Animatable(1f) }
    val firstRun = remember { booleanArrayOf(true) }
    androidx.compose.runtime.LaunchedEffect(tabSel) {
        if (firstRun[0]) { firstRun[0] = false; tab = tabSel; return@LaunchedEffect }
        if (tab == tabSel) return@LaunchedEffect
        fx.animateTo(0f, androidx.compose.animation.core.tween(170, easing = androidx.compose.animation.core.FastOutLinearInEasing))
        tab = tabSel
        // due fotogrammi per comporre il contenuto nuovo mentre è ancora sfocato, poi messa a fuoco
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        fx.animateTo(1f, androidx.compose.animation.core.tween(380, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
    }
    val tabFx = Modifier.graphicsLayer {
        val v = fx.value
        alpha = 0.15f + 0.85f * v
        translationY = (1f - v) * 10.dp.toPx()
        renderEffect = if (v < 0.995f && android.os.Build.VERSION.SDK_INT >= 31) { val b = (1f - v) * 20f + 0.01f; androidx.compose.ui.graphics.BlurEffect(b, b, androidx.compose.ui.graphics.TileMode.Decal) } else null
    }
    val filter = if (tab == 1) "screenshot" else "file"
    val grouped = remember(history) {
        val fmt = java.text.SimpleDateFormat("d MMMM", java.util.Locale.ITALY)
        val today = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
        fun label(ts: Long) = if (ts >= today) "Oggi" else if (ts >= today - 86_400_000L) "Ieri" else fmt.format(java.util.Date(ts))
        buildList<Pair<String?, it.ponte.ClipItem?>> { var last = ""; history.forEach { h -> val d = label(h.ts); if (d != last) { add(d to null); last = d }; add(null to h) } }
    }
    val list = if (tab == 0) emptyList() else all.filter { it.kind == filter && (folder == null || placed[it.fid] == folder) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "file"
            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            toast(ctx, "File in invio")
            r.scope.launch { r.files.sendUri(uri, name, mime, "file") }
        }
    }

    var viewing by remember { mutableStateOf<Int?>(null) }
    val images = list.filter { it.mime.startsWith("image/") }
    fun share(e: FileEntry) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(if (e.mime.startsWith("text/")) "application/octet-stream" else e.mime)
            .putExtra(Intent.EXTRA_STREAM, r.files.uriFor(e))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { ctx.startActivity(Intent.createChooser(send, e.name)) } catch (_: ActivityNotFoundException) { toast(ctx, "Nessuna app può riceverlo") }
    }
    fun open(e: FileEntry) {
        if (e.mime.startsWith("image/")) { viewing = images.indexOfFirst { it.fid == e.fid }.coerceAtLeast(0); return }
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(r.files.uriFor(e), e.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: ActivityNotFoundException) {
            toast(ctx, "Nessuna app può aprire questo file")
        }
    }
    fun place(e: FileEntry, f: String?) {
        placed = if (f == null) placed - e.fid else placed + (e.fid to f)
        r.prefs.fileFolders = placed
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            GridCells.Adaptive(150.dp), Modifier.widthIn(max = 1024.dp).fillMaxHeight(),
            contentPadding = PaddingValues(start = Space.s4, end = Space.s4, top = Space.s2, bottom = Space.s5 + LocalBarInset.current),
            horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            item(key = "dev", span = { GridItemSpan(maxLineSpan) }) { DevicesCard(r) }
            item(key = "act", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Action("", "Invia appunti", true, glyph = 2) { toast(ctx, if (r.clipboard.sendCurrent()) "Appunti inviati" else "Negli appunti non c'è niente da inviare") }
                    Action("", "Invia file", false, glyph = 7) { picker.launch(arrayOf("*/*")) }
                    Action("", "Backup", false, glyph = 8) { onBackup() }
                }
            }
            item(key = "seg", span = { GridItemSpan(maxLineSpan) }) {
                GlassSegmented(listOf("Appunti", "Immagini", "File"), tabSel, { onTab(keys[it]); folder = null })
            }
            if (tab == 0) {
                if (history.isEmpty()) item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                    Box(tabFx) { CText("Niente per ora. Copia su un altro dispositivo e arriva qui.", tertiary = true) }
                }
                items(grouped, key = { (d, h) -> if (h == null) "d$d" else "c" + h.ts + h.text.hashCode() }, span = { GridItemSpan(maxLineSpan) }) { (day, h) ->
                    if (h == null) {
                        Text(day ?: "", style = CanoneType.footnote.copy(fontWeight = FontWeight.Medium), color = c.labelTertiary, modifier = Modifier.then(tabFx).padding(top = Space.s2, start = Space.s1))
                    } else {
                        val fx = rememberCopyFx()
                        val rowScope = rememberCoroutineScope()
                        val tone = deviceTone(if (h.mine) "me" else h.from)
                        val pic = DevicePics.rememberPic(if (h.mine) r.deviceId else h.from)
                        val link = h.text.trim().let { (it.startsWith("http://") || it.startsWith("https://")) && !it.contains(' ') }
                        val code = !link && h.text.trim().length in 4..12 && h.text.trim().none { it == ' ' } && h.text.trim().count { it.isDigit() } >= 4
                        val kind = when { link -> "Link"; code -> "Codice"; h.text.count { it == '\n' } >= 2 -> "Testo lungo"; else -> "Testo" }
                        val shape2 = RoundedCornerShape(Radius.lg)
                        Box(
                            Modifier.then(tabFx).fillMaxWidth().clip(shape2).background(c.bgSurface).border(BorderStroke(1.dp, c.lineSeparator), shape2)
                                .dropClickable({ r.clipboard.copyLocal(h.text); fx.play(rowScope) }, corner = Radius.lg),
                        ) {
                            Row(Modifier.copyBlur(fx).padding(Space.s3), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                                Box(Modifier.size(40.dp).drop(isDark(), tint = tone.copy(alpha = 0.34f)), contentAlignment = Alignment.Center) {
                                    if (pic != null) Image(pic, null, Modifier.fillMaxSize().padding(5.dp).clip(androidx.compose.foundation.shape.CircleShape), contentScale = ContentScale.Fit)
                                    else TabGlyph(if (h.mine) 0 else 1, c.labelPrimary, false, Modifier.size(20.dp))
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                                        Text(if (h.mine) "Questo telefono" else r.deviceName(h.from), style = CanoneType.footnote.copy(fontWeight = FontWeight.Medium), color = c.labelSecondary, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                                        Text("· $kind", style = CanoneType.footnote, color = if (link) c.accent else c.labelTertiary, maxLines = 1)
                                        Spacer(Modifier.weight(1f))
                                        Text(time(h.ts, ctx), style = CanoneType.footnote, color = c.labelTertiary)
                                    }
                                    Text(
                                        h.text, style = if (code) CanoneType.figure else CanoneType.body, color = if (link) c.accent else c.labelPrimary,
                                        maxLines = if (link) 1 else 3, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            CopiedVeil(fx, Radius.lg, Modifier.matchParentSize())
                        }
                    }
                }
            } else {
                item(key = "folders", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.then(tabFx).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                        (listOf<String?>(null) + folders).forEach { f ->
                            val on = folder == f
                            Box(
                                Modifier.clip(androidx.compose.foundation.shape.CircleShape).background(if (on) c.accent else c.bgSunken)
                                    .dropClickable({ folder = f }, if (f != null) ({
                                        folders = folders - f; r.prefs.folders = folders
                                        placed = placed.filterValues { it != f }; r.prefs.fileFolders = placed
                                        if (folder == f) folder = null
                                        toast(ctx, "Cartella $f eliminata")
                                    }) else null, corner = 100.dp).padding(horizontal = Space.s3, vertical = Space.s2),
                            ) { Text(f ?: "Tutte", style = CanoneType.footnote, color = if (on) Color.White else c.labelSecondary, maxLines = 1) }
                        }
                        Box(Modifier.clip(androidx.compose.foundation.shape.CircleShape).background(c.bgSunken).dropClickable({ newFolder = "" }, corner = 100.dp).padding(horizontal = Space.s3, vertical = Space.s2)) {
                            Text("+ Cartella", style = CanoneType.footnote, color = c.labelSecondary, maxLines = 1)
                        }
                    }
                }
                if (list.isEmpty()) item(key = "nonef", span = { GridItemSpan(maxLineSpan) }) {
                    CText(
                        if (folder != null) "Cartella vuota. Tieni premuto un elemento per spostarlo qui."
                        else if (tab == 1) "Nessuna immagine. Fai uno screenshot su un dispositivo collegato e arriva qui."
                        else "Nessun file. Mandane uno da qualsiasi dispositivo.",
                        tertiary = true,
                    )
                }
            }
            items(list, key = { it.fid }) { e ->
                Column(
                    Modifier.then(tabFx).clip(RoundedCornerShape(Radius.lg)).background(c.bgSurface)
                        .border(BorderStroke(1.dp, c.lineSeparator), RoundedCornerShape(Radius.lg))
                        .dropClickable({ open(e) }, { peek = e }, Radius.lg).padding(Space.s2),
                    verticalArrangement = Arrangement.spacedBy(Space.s2),
                ) {
                    val bmp by produceState<Bitmap?>(null, e.path) {
                        value = if (e.mime.startsWith("image/")) withContext(Dispatchers.IO) { decodeThumb(e.path) } else null
                    }
                    Box(Modifier.fillMaxWidth().aspectRatio(1.3f).clip(RoundedCornerShape(Radius.lg - Space.s2)).background(c.bgSunken), contentAlignment = Alignment.Center) {
                        bmp?.let { Image(it.asImageBitmap(), e.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            ?: Text(e.name.substringAfterLast('.', "file").take(5), style = CanoneType.figure, color = c.labelTertiary)
                    }
                    Column(Modifier.padding(horizontal = Space.s2).padding(bottom = Space.s1)) {
                        Text(e.name, style = CanoneType.footnote, color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text((placed[e.fid]?.let { "$it · " } ?: "") + Formatter.formatShortFileSize(ctx, e.size), style = CanoneType.footnote, color = c.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    viewing?.let { i -> PhotoViewer(images, i, onShare = { share(it) }, onClose = { viewing = null }) }

    peek?.let { e -> Peek(e, folders, placed[e.fid], onShare = { share(e) }, onFolder = { f -> place(e, f) }, onDelete = { r.files.delete(e); peek = null }) { peek = null } }

    newFolder?.let { name ->
        AlertDialog(
            onDismissRequest = { newFolder = null },
            containerColor = c.bgSurface,
            shape = RoundedCornerShape(36.dp),
            title = { Text("Nuova cartella", style = CanoneType.title3, color = c.labelPrimary) },
            text = { CField("Nome", name, { newFolder = it }, "Lavoro, Università…") },
            dismissButton = { CButton("Annulla", { newFolder = null }, kind = ButtonKind.Tertiary) },
            confirmButton = {
                CButton("Crea", {
                    val n = name.trim()
                    if (n.isNotEmpty() && n !in folders) { folders = folders + n; r.prefs.folders = folders; folder = n }
                    newFolder = null
                }, kind = ButtonKind.Primary)
            },
        )
    }
}

/** Anteprima tenendo premuto: l'immagine si apre con una molla al centro, sotto le azioni e le cartelle. Tocca fuori per chiudere. */
@Composable
private fun Peek(e: FileEntry, folders: List<String>, current: String?, onShare: () -> Unit, onFolder: (String?) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    val c = Canone.colors
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(e.fid) { view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) }
    val bmp by produceState<Bitmap?>(null, e.path) { value = if (e.mime.startsWith("image/")) withContext(Dispatchers.IO) { decodeThumb(e.path) } else null }
    val grow = remember { androidx.compose.animation.core.Animatable(0.82f) }
    LaunchedEffect(e.fid) { grow.animateTo(1f, androidx.compose.animation.core.spring(0.7f, 320f)) }
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.padding(Space.s5).graphicsLayer { scaleX = grow.value; scaleY = grow.value; alpha = ((grow.value - 0.82f) / 0.18f).coerceIn(0f, 1f) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s4),
            ) {
                Box(Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(Radius.xl)).background(c.bgSurface), contentAlignment = Alignment.Center) {
                    bmp?.let { Image(it.asImageBitmap(), e.name, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
                        ?: Text(e.name, style = CanoneType.title3, color = c.labelPrimary, modifier = Modifier.padding(Space.s6))
                }
                Text(e.name, style = CanoneType.footnote, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (folders.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    (listOf<String?>(null) + folders).forEach { f ->
                        val on = current == f
                        Box(Modifier.clip(androidx.compose.foundation.shape.CircleShape).background(if (on) c.accent else c.bgSurface).dropClickable({ onFolder(f) }, corner = 100.dp).padding(horizontal = Space.s3, vertical = Space.s2)) {
                            Text(f ?: "Nessuna cartella", style = CanoneType.footnote, color = if (on) Color.White else c.labelSecondary, maxLines = 1)
                        }
                    }
                }
                Row(Modifier.clip(RoundedCornerShape(Radius.xl)).background(c.bgSurface).padding(Space.s3), horizontalArrangement = Arrangement.spacedBy(Space.s5)) {
                    Action("↗", "Condividi", true, onClick = onShare)
                    Action("✕", "Elimina", false, onClick = onDelete)
                }
            }
        }
    }
}

/** Un indicatore di stato: pallino colorato e nome, per Server e per il collegamento diretto (P2P). */
@Composable
private fun StatusBubble(label: String, tone: Tone, on: Boolean) {
    val c = Canone.colors
    val col = when (tone) { Tone.Positive -> c.positive; Tone.Caution -> c.caution; Tone.Critical -> c.critical; else -> c.labelDisabled }
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "stato")
    val a by pulse.animateFloat(1f, if (on) 0.35f else 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1100), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulsa")
    Row(Modifier.height(28.dp).clip(RoundedCornerShape(50)).background(c.bgSunken).padding(horizontal = Space.s3), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).graphicsLayer { alpha = a }.clip(androidx.compose.foundation.shape.CircleShape).background(col))
        Text(label, style = CanoneType.footnote, color = c.labelSecondary, maxLines = 1)
    }
}

/**
 * Tutti i dispositivi in una scheda: in alto due bolle (server e collegamento diretto), poi l'elenco con l'immagine di ognuno.
 * Toccando un dispositivo la scheda si allunga e dal suo bordo inferiore esce un cassetto grigio con i dettagli, dentro lo stesso contorno.
 */
@Composable
private fun DevicesCard(r: Router) {
    val c = Canone.colors
    val ctx = LocalContext.current
    val devices by r.devices.collectAsState()
    val hub by r.hubState.collectAsState()
    val shape = RoundedCornerShape(Radius.xl)
    var open by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val id = target
        if (uri != null && id != null) r.scope.launch { if (!DevicePics.save(ctx, id, uri)) toast(ctx, "Immagine non leggibile") }
    }
    val near = devices.count { it.via != "server" }
    val rows = listOf(Device(r.deviceId, r.prefs.name, "android", emptySet(), "self")) + devices
    var last by remember { mutableStateOf<Device?>(null) }
    rows.firstOrNull { it.id == open }?.let { last = it }

    Column(Modifier.fillMaxWidth().clip(shape).border(BorderStroke(1.dp, c.lineSeparator), shape)) {
        Column(Modifier.fillMaxWidth().background(c.bgSurface).padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Dispositivi", style = CanoneType.title3, color = c.labelPrimary)
                    RollingText(if (devices.isEmpty()) "Solo questo" else "${devices.size + 1} collegati", CanoneType.footnote, c.labelTertiary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    StatusBubble(if (hub == "Connesso") "Server" else "Server off", if (hub == "Connesso") Tone.Positive else if (hub == "Non configurato") Tone.Neutral else Tone.Caution, hub == "Connesso")
                    StatusBubble(if (near > 0) "P2P $near" else "P2P", if (near > 0) Tone.Positive else Tone.Neutral, near > 0)
                }
            }
            rows.forEach { d ->
                val pic = DevicePics.rememberPic(d.id)
                val sel = open == d.id
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(if (sel) c.accentSubtle else Color.Transparent)
                        .dropClickable({ open = if (sel) null else d.id }, corner = Radius.lg).padding(Space.s2),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3),
                ) {
                    Box(Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.bgSunken), contentAlignment = Alignment.Center) {
                        if (pic != null) Image(pic, null, Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit)
                        else TabGlyph(1, c.labelTertiary, false, Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(d.name + if (d.id == r.deviceId) " (questo)" else "", style = CanoneType.body, color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (d.id == r.deviceId) "Android · questo" else (if (d.platform == "windows") "Windows" else "Android") + " · " + when (d.via) { "lan" -> "diretto"; "bt" -> "Bluetooth"; else -> "server" }, style = CanoneType.footnote, color = c.labelTertiary)
                    }
                    Text(if (sel) "⌃" else "⌄", style = CanoneType.body, color = c.labelTertiary)
                }
            }
        }
        // Il cassetto: stesso contorno della scheda, sfondo grigio, esce da sotto.
        androidx.compose.animation.AnimatedVisibility(
            open != null,
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.spring(1f, 380f)) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)),
            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(240)) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140)),
        ) {
            val d = last
            if (d != null) {
                val pic = DevicePics.rememberPic(d.id)
                Column(Modifier.fillMaxWidth().background(c.bgSunken).padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    CRow("Collegamento", when {
                        d.id == r.deviceId -> "Questo dispositivo"
                        d.via == "lan" -> "Diretto, rete locale o privata"
                        d.via == "bt" -> "Bluetooth"
                        else -> "Tramite il server"
                    })
                    if (d.id != r.deviceId) CRow("Funzioni", buildList {
                        add("appunti"); add("file")
                        if ("audio-src" in d.caps) add("sorgente audio")
                        if ("audio-sink" in d.caps) add("riceve audio")
                        if ("notif-src" in d.caps) add("notifiche")
                    }.joinToString(" · "))
                    else CRow("Identificativo", r.deviceId.take(13) + "…", mono = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                        CButton(if (pic == null) "Scegli immagine" else "Cambia immagine", { target = d.id; pick.launch("image/*") }, Modifier.weight(1f))
                        if (pic != null) CButton("Rimuovi", { DevicePics.remove(ctx, d.id) }, kind = ButtonKind.Tertiary)
                    }
                }
            }
        }
    }
}


// ---------------------------------------------------------------- Audio
/**
 * Audio: le cuffie con l'immagine del dispositivo e la batteria, cosa sta suonando con i controlli, il volume,
 * dove esce l'audio e l'ascolto dal PC. Le regole tecniche stanno in Impostazioni → Audio.
 */
@Composable
fun AudioScreen(r: Router, openSettings: () -> Unit = {}) {
    val c = Canone.colors
    val ctx = LocalContext.current
    val devices by r.devices.collectAsState()
    val active by r.audio.activeSource.collectAsState()
    val sources = devices.filter { "audio-src" in it.caps }
    val hps by r.headphones.list.collectAsState()
    val holder by r.headphones.holder.collectAsState()
    val lockedBy by r.headphones.lockedBy.collectAsState()
    val preferred = r.prefs.preferredHp
    val np = rememberNowPlaying()
    val am = remember { ctx.getSystemService(android.media.AudioManager::class.java) }
    val maxVol = remember { am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var vol by remember { mutableIntStateOf(am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(4000); tick++; vol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) } }
    val connected = hps.firstOrNull { it.connected && (it.address == preferred || it.address in r.prefs.headphones) } ?: hps.firstOrNull { it.connected }
    val battery = remember(connected?.address, tick) { connected?.let { headphonesBattery(ctx, it.address) } }
    val out = remember(tick, holder) { outputName(ctx) }
    fun who(id: String?) = when (id) { null -> "Libere"; r.deviceId -> "Su questo telefono"; else -> "Su " + r.deviceName(id) }
    val glow by androidx.compose.animation.core.animateFloatAsState(if (holder == r.deviceId) 1f else 0f, androidx.compose.animation.core.tween(500), label = "luce")
    val pic = DevicePics.rememberPic(holder)

    ScreenList("Audio") {
        // ------------------------------------------------ cuffie
        item {
            val shape = RoundedCornerShape(Radius.xl)
            Column(Modifier.fillMaxWidth().clip(shape).border(BorderStroke(1.dp, c.lineSeparator), shape)) {
                Column(Modifier.fillMaxWidth().background(c.bgSurface).padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                        Box(
                            Modifier.size(92.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                .background(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.10f + 0.25f * glow), c.bgSunken))),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (pic != null) Image(pic, null, Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
                            else TabGlyph(4, c.accentOnSubtle, false, Modifier.size(40.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(connected?.name ?: "Cuffie", style = CanoneType.footnote, color = c.labelTertiary, maxLines = 1)
                            Text(who(holder), style = CanoneType.title3, color = c.labelPrimary, maxLines = 2)
                            if (battery != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                                Box(Modifier.size(28.dp, 12.dp).clip(RoundedCornerShape(3.dp)).background(c.bgSunken)) {
                                    Box(Modifier.fillMaxHeight().fillMaxWidth(battery / 100f).background(if (battery < 20) c.critical else c.positive))
                                }
                                RollingText("$battery%", CanoneType.footnote, c.labelSecondary)
                            }
                            if (lockedBy != null) Text("Bloccate " + who(lockedBy).replaceFirstChar { it.lowercase() }, style = CanoneType.footnote, color = c.labelTertiary)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                        CButton(if (lockedBy == r.deviceId) "Sblocca" else "Tienile qui", { r.headphones.toggleLock() },
                            Modifier.weight(1f), kind = if (lockedBy == r.deviceId) ButtonKind.Secondary else ButtonKind.Primary)
                        if (preferred.isNotEmpty() && hps.none { it.address == preferred && it.connected }) {
                            CButton("Portale qui", { r.headphones.connectHere(preferred) }, Modifier.weight(1f))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().background(c.bgSunken).padding(horizontal = Space.s4, vertical = Space.s3), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Esce da", style = CanoneType.footnote, color = c.labelTertiary)
                    Text(out, style = CanoneType.footnote.copy(fontWeight = FontWeight.Medium), color = c.labelPrimary, maxLines = 1)
                }
            }
        }
        // ------------------------------------------------ player (copertina, comandi e volume in una sola scheda)
        item {
            if (np != null) PlayerCard(np) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Esce da", style = CanoneType.footnote, color = c.labelTertiary)
                    Text(out, style = CanoneType.footnote.copy(fontWeight = FontWeight.Medium), color = c.labelPrimary, maxLines = 1)
                }
            } else CCard {
                SectionTitle("In riproduzione")
                if (listenerEnabled(ctx)) CText("Fai partire la musica: qui compaiono copertina, comandi e volume.", tertiary = true)
                else {
                    CText("Per vedere e comandare la musica serve l'accesso alle notifiche.", tertiary = true)
                    CButton("Consenti", { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, kind = ButtonKind.Primary)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    SpeakerIcon(false, c.labelTertiary, Modifier.size(18.dp))
                    DropSlider(vol.toFloat() / maxVol, { f ->
                        val v = (f * maxVol).roundToInt().coerceIn(0, maxVol)
                        if (v != vol) { vol = v; am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0) }
                    }, Modifier.weight(1f))
                    SpeakerIcon(true, c.labelTertiary, Modifier.size(18.dp))
                }
            }
        }
        // ------------------------------------------------ dal PC (solo se c'è un PC che trasmette)
        if (sources.isNotEmpty()) item {
            CCard {
                SectionTitle("Ascolta dal PC")
                if (sources.isEmpty()) {
                    CText("Apri Relay sul PC e il suo audio compare qui. Il ritardo è di circa 100 ms.", tertiary = true)
                } else {
                    sources.forEach { d ->
                        val dp = DevicePics.rememberPic(d.id)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                            Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.bgSunken), contentAlignment = Alignment.Center) {
                                if (dp != null) Image(dp, null, Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit) else TabGlyph(1, c.labelTertiary, false, Modifier.size(20.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(d.name, style = CanoneType.body, color = c.labelPrimary, maxLines = 1)
                                Text(if (active == d.id) "In ascolto qui" else "Disponibile", style = CanoneType.footnote, color = c.labelTertiary)
                            }
                            if (active == d.id) CButton("Ferma", { r.audio.stopListening(d.id) })
                            else CButton("Ascolta", { r.audio.listenTo(d.id) }, kind = ButtonKind.Primary)
                        }
                    }
                }
            }
        }
        item { CButton("Impostazioni audio", openSettings, Modifier.fillMaxWidth(), kind = ButtonKind.Tertiary) }
    }
}

// ---------------------------------------------------------------- Impostazioni

/** Quante autorizzazioni mancano (stesso elenco della pagina Permessi). */
internal fun missingPerms(ctx: Context): Int {
    val nearby = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    return listOf(
        Build.VERSION.SDK_INT < 33 || has(ctx, Manifest.permission.POST_NOTIFICATIONS),
        listenerEnabled(ctx), has(ctx, imagesPermission()),
        Build.VERSION.SDK_INT < 31 || has(ctx, Manifest.permission.BLUETOOTH_CONNECT),
        has(ctx, nearby), Settings.canDrawOverlays(ctx), pm.isIgnoringBatteryOptimizations(ctx.packageName),
    ).count { !it }
}

/** Voce di categoria: goccia colorata con l'icona, nome e descrizione; tutta la riga è una goccia che si preme. */
@Composable
private fun Category(icon: Int, title: String, note: String, tint: Color, onClick: () -> Unit) {
    val c = Canone.colors
    val dark = isDark()
    val t = rememberDropTouch()
    Box(Modifier.fillMaxWidth().dropGestures(t, onClick)) {
        Row(
            Modifier.fillMaxWidth().dropDeform(t).drop(dark, corner = Radius.xl, press = { t.press.value }, touch = t).padding(Space.s3),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Box(Modifier.size(46.dp).drop(dark, tint = tint.copy(alpha = if (dark) 0.42f else 0.30f)), contentAlignment = Alignment.Center) {
                TabGlyph(icon, if (dark) Color.White else tint, false, Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = CanoneType.body.copy(fontWeight = FontWeight.Medium), color = c.labelPrimary)
                Text(note, style = CanoneType.footnote, color = c.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("›", style = CanoneType.title3, color = c.labelTertiary)
        }
    }
}

@Composable
private fun SetGroup(text: String) =
    Text(text.uppercase(), style = CanoneType.footnote, color = Canone.colors.labelTertiary, modifier = Modifier.padding(start = Space.s3, top = Space.s2))

/** Impostazioni: una dashboard con lo stato di Relay e quattro sezioni — Permessi, Sistema e le impostazioni tecniche di ogni modulo. */
@Composable
fun SettingsScreen(r: Router, sub: String?, onSub: (String?) -> Unit, replayOnboarding: () -> Unit = {}) {
    val ctx = LocalContext.current
    val c = Canone.colors
    var name by remember { mutableStateOf(r.prefs.name) }
    var hub by remember { mutableStateOf(r.prefs.hubUrl) }
    var token by remember { mutableStateOf(r.prefs.token) }
    var tick by remember { mutableIntStateOf(0) }
    val hubState by r.hubState.collectAsState()

    DisposableEffect(ctx) {
        val lc = (ctx as? ComponentActivity)?.lifecycle
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lc?.addObserver(obs)
        onDispose { lc?.removeObserver(obs) }
    }
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val askImages = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++; r.reconfigure() }
    val askOther = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val nearbyPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION

    @Composable
    fun PermRow(title: String, note: String, granted: Boolean, action: () -> Unit) {
        CRow(title, note) { GrantMark(granted, action) }
    }
    fun save() {
        r.prefs.name = name; r.prefs.hubUrl = hub; r.prefs.token = token
        r.reconfigure()
        toast(ctx, "Salvato")
    }
    val titles = mapOf("perm" to "Permessi", "sys" to "Sistema", "share" to "Condividi", "audio" to "Audio", "fin" to "Finanze")

    ModuleSwitch(sub) { page ->
        ScreenList("Impostazioni") {
            if (page != null) item { BackRow("Impostazioni", titles[page] ?: "", { onSub(null) }) }
            @Suppress("UNUSED_EXPRESSION") tick
            when (page) {
                null -> {
                    item {
                        Row(Modifier.fillMaxWidth().padding(vertical = Space.s2), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                            RelayMark(52.dp)
                            Column {
                                Text("Relay", style = CanoneType.title2, color = c.labelPrimary)
                                Text("Versione 1.0.${r.updater.current}", style = CanoneType.footnote, color = c.labelTertiary)
                            }
                        }
                    }
                    // Permessi solo se c'è qualcosa da concedere
                    if (missingPerms(ctx) > 0) item { Category(10, "Permessi", "Qualcosa da concedere", Color(0xFF3F86FF)) { onSub("perm") } }
                    item { Category(5, "Sistema", "Aggiornamenti, server e questo dispositivo", Color(0xFF8E8E93)) { onSub("sys") } }
                    item { SetGroup("Moduli") }
                    item { Category(1, "Condividi", "Collegamento diretto, screenshot, notifiche", Color(0xFF2FB5C4)) { onSub("share") } }
                    item { Category(6, "Finanze", "Importi nascosti, pagamenti Revolut", Color(0xFF5DA85B)) { onSub("fin") } }
                    item { Category(4, "Audio", "Cuffie, passaggio automatico, root", Color(0xFFE88A2E)) { onSub("audio") } }
                }

                "fin" -> {
                    item {
                        var rev by remember { mutableStateOf(r.prefs.revolutNotif) }
                        CCard {
                            CSwitchRow("Nascondi gli importi", "Nelle dashboard le cifre si sfocano (anche dall'occhio accanto al saldo)", MoneyPrivacy.hidden) {
                                MoneyPrivacy.hidden = it; r.prefs.hideMoney = it
                            }
                            CDivider()
                            CSwitchRow("Pagamenti Revolut dalle notifiche", "Ogni pagamento notificato da Revolut entra da solo nei movimenti", rev) { rev = it; r.prefs.revolutNotif = it }
                        }
                    }
                }

                "perm" -> {
                    item { SetGroup("Generali") }
                    item {
                        CCard {
                            if (Build.VERSION.SDK_INT >= 33) {
                                PermRow("Notifiche di Relay", "Serve al servizio in primo piano", has(ctx, Manifest.permission.POST_NOTIFICATIONS)) { askNotif.launch(Manifest.permission.POST_NOTIFICATIONS) }
                                CDivider()
                            }
                            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                            PermRow("Nessun risparmio batteria", "Evita che Android fermi Relay", pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        }
                    }
                    item { SetGroup("Condividi") }
                    item {
                        CCard {
                            PermRow("Foto e screenshot", "Per inviare gli screenshot", has(ctx, imagesPermission())) { askImages.launch(imagesPermission()) }
                            CDivider()
                            PermRow("Accesso alle notifiche", "Per mostrarle sul PC e leggere i pagamenti Revolut", listenerEnabled(ctx)) {
                                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                            CDivider()
                            PermRow("Dispositivi Wi-Fi vicini", "Wi-Fi Direct tra telefono e tablet quando non c'è rete", has(ctx, nearbyPerm)) { askOther.launch(nearbyPerm) }
                            CDivider()
                            PermRow("Mostra sopra altre app", "Per inviare gli appunti copiati con l'app in background", Settings.canDrawOverlays(ctx)) {
                                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
                            }
                            CDivider()
                            PermRow("Lettura dei log di sistema", "Serve ad accorgersi di quando copi", r.clipLog.hasLogPermission()) {
                                val ok = try {
                                    Runtime.getRuntime().exec(arrayOf("su", "-c", "pm grant ${ctx.packageName} android.permission.READ_LOGS")).waitFor() == 0
                                } catch (_: Exception) { false }
                                toast(ctx, if (ok) "Concesso: riapri Relay" else "Serve il comando adb, vedi sotto")
                            }
                            CText("Senza root: dal PC, con il debug USB attivo,  adb shell pm grant ${ctx.packageName} android.permission.READ_LOGS", tertiary = true)
                        }
                    }
                    item { SetGroup("Audio") }
                    item {
                        CCard {
                            if (Build.VERSION.SDK_INT >= 31) PermRow("Bluetooth", "Cuffie e collegamento di riserva senza Wi-Fi", has(ctx, Manifest.permission.BLUETOOTH_CONNECT)) { askOther.launch(Manifest.permission.BLUETOOTH_CONNECT) }
                            else CText("Nessun permesso richiesto su questa versione di Android.", tertiary = true)
                        }
                    }
                }

                "sys" -> {
                    item {
                        val upd by r.updater.ui.collectAsState()
                        val status by r.updater.status.collectAsState()
                        CCard {
                            SectionTitle("Aggiornamenti")
                            CRow("Versione 1.0.${r.updater.current}", status.ifEmpty { "Si aggiorna da sola tramite il tuo server" }) {
                                val busy = upd?.phase == Updater.Phase.Checking || upd?.phase == Updater.Phase.Downloading || upd?.phase == Updater.Phase.Installing
                                if (busy) UpdateRing(upd!!.phase, if ((upd?.total ?: 0) > 0) upd!!.done.toFloat() / upd!!.total else 0f, 28.dp)
                                else CButton("Cerca ora", { r.scope.launch { r.updater.check(install = true, manual = true) } })
                            }
                        }
                    }
                    item {
                        CCard {
                            SectionTitle("Server")
                            CRow("Stato", if (hubState == "Connesso") "Collegato" else hubState)
                            CField("Indirizzo", hub, { hub = it }, "ponte.esempio.it", mono = true)
                            CField("Token", token, { token = it }, "Lo stesso impostato sul server", mono = true, secret = true)
                            CButton("Salva e riconnetti", ::save, kind = ButtonKind.Primary)
                        }
                    }
                    item {
                        CCard {
                            SectionTitle("Questo dispositivo")
                            CField("Nome", name, { name = it }, "Tablet del salotto")
                            CButton("Salva", ::save, kind = ButtonKind.Primary)
                            CDivider()
                            CRow("Identificativo", r.deviceId, mono = true)
                            CDivider()
                            CButton("Rivedi la configurazione iniziale", replayOnboarding, Modifier.fillMaxWidth(), kind = ButtonKind.Tertiary)
                        }
                    }
                }

                "share" -> {
                    item {
                        var lan by remember { mutableStateOf(r.prefs.lanEnabled) }
                        var shots by remember { mutableStateOf(r.prefs.screenshots) }
                        var notif by remember { mutableStateOf(r.prefs.notifications) }
                        CCard {
                            CSwitchRow("Collegamento diretto", "Rete locale e rete privata Headscale", lan) { lan = it; r.prefs.lanEnabled = it; r.reconfigure() }
                            CDivider()
                            CSwitchRow("Screenshot automatici", "Ogni nuovo screenshot va nell'archivio di tutti", shots) {
                                shots = it; r.prefs.screenshots = it
                                if (it && !has(ctx, imagesPermission())) askImages.launch(imagesPermission()) else r.reconfigure()
                            }
                            CDivider()
                            CSwitchRow("Notifiche sul PC", "Le notifiche di questo telefono compaiono su Windows", notif) {
                                notif = it; r.prefs.notifications = it; r.reconfigure()
                                if (it && !listenerEnabled(ctx)) ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                        }
                    }
                }

                else -> {
                    item {
                        val rooted by r.headphones.rooted.collectAsState()
                        val rootError by Root.lastError.collectAsState()
                        val cannot by r.headphones.cannotDisconnect.collectAsState()
                        val hps by r.headphones.list.collectAsState()
                        var idle by remember { mutableIntStateOf(r.prefs.idleMinutes) }
                        var mute by remember { mutableStateOf(r.prefs.fallbackMute) }
                        var autoPause by remember { mutableStateOf(r.prefs.autoPause) }
                        var preferred by remember { mutableStateOf(r.prefs.preferredHp) }
                        var marked by remember { mutableStateOf(r.prefs.headphones) }
                        Column(verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                            CCard {
                                SectionTitle("Cuffie")
                                if (hps.isEmpty()) CText("Nessuna cuffia associata, oppure manca il permesso Bluetooth (Permessi).", tertiary = true)
                                else hps.forEach { h ->
                                    CSwitchRow(h.name, if (h.connected) "Collegate qui" else "Non collegate qui", h.address in marked) { on ->
                                        marked = if (on) marked + h.address else marked - h.address
                                        r.prefs.headphones = marked
                                    }
                                }
                                CDivider()
                                val choices = listOf("") + hps.map { it.address }
                                CRow("Cuffie preferite qui", hps.firstOrNull { it.address == preferred }?.name ?: "Nessuna: passaggio spento") {
                                    CButton("Cambia", {
                                        preferred = choices[(choices.indexOf(preferred) + 1) % choices.size]
                                        r.prefs.preferredHp = preferred
                                    })
                                }
                                CSwitchRow("Un dispositivo alla volta", "Quando la musica parte qui, gli altri si mettono in pausa", autoPause) { on ->
                                    autoPause = on; r.prefs.autoPause = on
                                }
                            }
                            CCard {
                                SectionTitle("Root")
                                CRow(
                                    "Root",
                                    when (rooted) { true -> "Relay può collegare e scollegare le cuffie"; false -> "Non disponibile: senza, qui le cuffie non si spostano"; null -> "Controllo in corso…" },
                                ) {
                                    if (rooted == true) GrantMark(true) {} else CButton("Riprova", { r.headphones.retryRoot() })
                                }
                                if (rootError.isNotEmpty()) CText("Dettaglio: $rootError", tertiary = true)
                                if (cannot) CText("L'ultimo tentativo di spostare le cuffie da qui non è riuscito.", tertiary = true)
                                CDivider()
                                val options = listOf(0, 5, 10, 20, 30)
                                CRow("Scollega le cuffie dopo", if (idle == 0) "Mai" else "$idle minuti senza audio") {
                                    CButton("Cambia", {
                                        idle = options[(options.indexOf(idle) + 1) % options.size]
                                        r.prefs.idleMinutes = idle
                                    })
                                }
                                CDivider()
                                CRow("Se le cuffie non ci sono", if (mute) "Muto" else "Altoparlante") {
                                    CButton("Cambia", { mute = !mute; r.prefs.fallbackMute = mute })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Un colore stabile per dispositivo, per distinguere a colpo d'occhio da dove arriva ogni copia. */
internal fun deviceTone(id: String): Color {
    val palette = listOf(Color(0xFF5C9DFF), Color(0xFF34C7A5), Color(0xFFF0A54A), Color(0xFFB38CFF), Color(0xFFFF7AA8), Color(0xFF7BD66B))
    return if (id == "me") palette[0] else palette[(id.hashCode() and 0x7fffffff) % (palette.size - 1) + 1]
}
