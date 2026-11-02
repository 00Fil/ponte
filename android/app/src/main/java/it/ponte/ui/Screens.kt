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

/** Condividi: tutto quello che passa tra i dispositivi in un posto solo — appunti, screenshot e file — con un selettore in alto. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShareList(r: Router, tab: Int, onTab: (Int) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = Canone.colors
    val all by r.files.items.collectAsState()
    val history by r.clipboard.history.collectAsState()
    var toDelete by remember { mutableStateOf<FileEntry?>(null) }
    val filter = if (tab == 1) "screenshot" else if (tab == 2) "file" else ""
    val list = if (tab == 0) emptyList() else all.filter { it.kind == filter }

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

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            GridCells.Adaptive(160.dp), Modifier.widthIn(max = 1024.dp).fillMaxHeight(),
            contentPadding = PaddingValues(start = Space.s4, end = Space.s4, top = Space.s5, bottom = Space.s5 + LocalBarInset.current),
            horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            item(key = "back", span = { GridItemSpan(maxLineSpan) }) { BackRow("Condividi", listOf("Appunti", "Immagini", "File")[tab], onBack) }
            item(key = "seg", span = { GridItemSpan(maxLineSpan) }) {
                GlassSegmented(listOf("Appunti", "Immagini", "File"), tab, onTab)
            }
            if (tab == 0) {
                item(key = "send", span = { GridItemSpan(maxLineSpan) }) {
                    CButton("Invia appunti", {
                        toast(ctx, if (r.clipboard.sendCurrent()) "Appunti inviati" else "Negli appunti non c'è niente da inviare")
                    }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
                }
                if (history.isEmpty()) {
                    item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                        CCard { CText("Niente per ora. Copia su un altro dispositivo e arriva qui.") }
                    }
                }
                items(history, key = { "c" + it.ts + it.text.hashCode() }, span = { GridItemSpan(maxLineSpan) }) { h ->
                    CCard(Modifier.dropClickable({ r.clipboard.copyLocal(h.text); toast(ctx, "Copiato") })) {
                        Text(h.text, style = CanoneType.body, color = c.labelPrimary, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text((if (h.mine) "Da qui" else r.deviceName(h.from)) + " · " + time(h.ts, ctx) + " · tocca per copiare", style = CanoneType.footnote, color = c.labelTertiary)
                    }
                }
            } else {
                item(key = "sendf", span = { GridItemSpan(maxLineSpan) }) {
                    CButton("Invia file", { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
                }
                if (list.isEmpty()) {
                    item(key = "nonef", span = { GridItemSpan(maxLineSpan) }) {
                        CCard { CText(if (tab == 1) "Nessuna immagine. Fai uno screenshot su un dispositivo collegato e arriva qui." else "Nessun file. Mandane uno da qualsiasi dispositivo.") }
                    }
                }
            }
            items(list, key = { it.fid }) { e ->
                Column(
                    Modifier.clip(RoundedCornerShape(Radius.lg)).background(c.bgSurface)
                        .border(BorderStroke(1.dp, c.lineSeparator), RoundedCornerShape(Radius.lg))
                        .dropClickable({ open(e) }, { toDelete = e }, Radius.lg).padding(Space.s2),
                    verticalArrangement = Arrangement.spacedBy(Space.s2),
                ) {
                    val bmp by produceState<Bitmap?>(null, e.path) {
                        value = if (e.mime.startsWith("image/")) withContext(Dispatchers.IO) { decodeThumb(e.path) } else null
                    }
                    // Raggio interno = 20 - margine 8 = 12.
                    Box(Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(Radius.lg - Space.s2)).background(c.bgSunken), contentAlignment = Alignment.Center) {
                        bmp?.let { Image(it.asImageBitmap(), e.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            ?: Text(e.name.substringAfterLast('.', "file").take(5), style = CanoneType.figure, color = c.labelTertiary)
                    }
                    Column(Modifier.padding(horizontal = Space.s2).padding(bottom = Space.s2)) {
                        Text(e.name, style = CanoneType.footnote, color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Formatter.formatShortFileSize(ctx, e.size) + " · " + dateTime(e.ts, ctx), style = CanoneType.footnote, color = c.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    viewing?.let { i ->
        PhotoViewer(images, i, onShare = { share(it) }, onClose = { viewing = null })
    }

    toDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            containerColor = c.bgSurface,
            shape = RoundedCornerShape(36.dp),
            title = { Text(e.name, style = CanoneType.title3, color = c.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text("Condividi lo manda a WhatsApp o a un'altra app. Elimina lo toglie solo da questo dispositivo.", style = CanoneType.body, color = c.labelSecondary) },
            dismissButton = { CButton("Elimina", { r.files.delete(e); toDelete = null }, kind = ButtonKind.Destructive) },
            confirmButton = {
                CButton("Condividi", {
                    toDelete = null
                    val send = Intent(Intent.ACTION_SEND)
                        .setType(if (e.mime.startsWith("text/")) "application/octet-stream" else e.mime)
                        .putExtra(Intent.EXTRA_STREAM, r.files.uriFor(e))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try { ctx.startActivity(Intent.createChooser(send, e.name)) } catch (_: ActivityNotFoundException) { toast(ctx, "Nessuna app può riceverlo") }
                }, kind = ButtonKind.Primary)
            },
        )
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
                        Text(if (d.id == r.deviceId) "Android" else if (d.platform == "windows") "Windows" else "Android", style = CanoneType.footnote, color = c.labelTertiary)
                    }
                    if (d.id != r.deviceId) CBadge(when (d.via) { "lan" -> "Diretto"; "bt" -> "Bluetooth"; else -> "Server" }, if (d.via == "server") Tone.Neutral else Tone.Accent)
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


/** Condividi: dashboard con dispositivi, contatori e tasti rapidi; da qui si apre ogni funzione (appunti, immagini, file, dispositivi, backup). */
@Composable
fun ShareScreen(r: Router, sub: String?, onSub: (String?) -> Unit, onBackup: () -> Unit) {
    val tabs = listOf("clips", "images", "files")
    ModuleSwitch(sub) { s ->
        when (s) {
            null -> ShareDashboard(r, onSub, onBackup)
            else -> ShareList(r, tabs.indexOf(s).coerceAtLeast(0), { onSub(tabs[it]) }) { onSub(null) }
        }
    }
}

@Composable
private fun ShareDashboard(r: Router, go: (String?) -> Unit, onBackup: () -> Unit) {
    val c = Canone.colors
    val ctx = LocalContext.current
    val devices by r.devices.collectAsState()
    val hub by r.hubState.collectAsState()
    val clips by r.clipboard.history.collectAsState()
    val files by r.files.items.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "file"
            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            toast(ctx, "File in invio")
            r.scope.launch { r.files.sendUri(uri, name, mime, "file") }
        }
    }
    val shots = files.count { it.kind == "screenshot" }
    ScreenList("Condividi") {
        item { DevicesCard(r) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Action("", "Invia appunti", true, glyph = 2) { toast(ctx, if (r.clipboard.sendCurrent()) "Appunti inviati" else "Negli appunti non c'è niente da inviare") }
                Action("", "Invia file", false, glyph = 7) { picker.launch(arrayOf("*/*")) }
                Action("", "Backup", false, glyph = 8) { onBackup() }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                NavTile(2, "Appunti", "${clips.size}", clips.firstOrNull()?.text?.take(24) ?: "in cronologia", TINT_B, Modifier.weight(1f)) { go("clips") }
                NavTile(3, "Immagini", "$shots", "screenshot", TINT_C, Modifier.weight(1f)) { go("images") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                NavTile(7, "File", "${files.count { it.kind != "screenshot" }}", "condivisi", TINT_A, Modifier.weight(1f)) { go("files") }
                NavTile(8, "Backup", "Server", "file, contatti, SMS, app", TINT_D, Modifier.weight(1f)) { onBackup() }
            }
        }
    }
}


// ---------------------------------------------------------------- Audio
@Composable
fun AudioScreen(r: Router, openSettings: () -> Unit = {}) {
    val c = Canone.colors
    val devices by r.devices.collectAsState()
    val active by r.audio.activeSource.collectAsState()
    val sources = devices.filter { "audio-src" in it.caps }
    val hps by r.headphones.list.collectAsState()
    val holder by r.headphones.holder.collectAsState()
    val focus by r.headphones.focus.collectAsState()
    val lockedBy by r.headphones.lockedBy.collectAsState()
    val preferred = r.prefs.preferredHp
    fun who(id: String?) = when (id) { null -> "Libere"; r.deviceId -> "Su questo dispositivo"; else -> "Su " + r.deviceName(id) }
    val glow by androidx.compose.animation.core.animateFloatAsState(if (holder == r.deviceId) 1f else 0f, androidx.compose.animation.core.tween(500), label = "luce")
    ScreenList("Audio") {
        item {
            CCard {
                val pic = DevicePics.rememberPic(holder)
                Box(
                    Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(Radius.lg))
                        .background(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.22f * glow), Color.Transparent))),
                    contentAlignment = Alignment.Center,
                ) {
                    if (pic != null) Image(pic, null, Modifier.fillMaxSize().padding(Space.s3), contentScale = ContentScale.Fit)
                    else TabGlyph(4, c.labelTertiary, false, Modifier.size(72.dp))
                }
                Text(who(holder), style = CanoneType.title2, color = c.labelPrimary)
                Text(
                    if (lockedBy != null) "Bloccate " + who(lockedBy).replaceFirstChar { it.lowercase() } else "Seguono dove ascolti.",
                    style = CanoneType.footnote, color = c.labelTertiary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    CButton(if (lockedBy == r.deviceId) "Sblocca" else "Tienile qui", { r.headphones.toggleLock() },
                        Modifier.weight(1f), kind = if (lockedBy == r.deviceId) ButtonKind.Secondary else ButtonKind.Primary)
                    if (preferred.isNotEmpty() && hps.none { it.address == preferred && it.connected }) {
                        CButton("Portale qui", { r.headphones.connectHere(preferred) }, Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            CCard {
                SectionTitle("Ascolta dal PC")
                if (sources.isEmpty()) {
                    CText("Apri Relay sul PC e il suo audio compare qui.", tertiary = true)
                } else {
                    sources.forEach { d ->
                        CRow(d.name, if (active == d.id) "In ascolto" else "Disponibile") {
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
    val rootState by r.headphones.rooted.collectAsState()

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
        CRow(title, note) {
            if (granted) CBadge("Concesso", Tone.Positive) else {
                CBadge("Da concedere", Tone.Caution)
                CButton("Concedi", action)
            }
        }
    }
    fun save() {
        r.prefs.name = name; r.prefs.hubUrl = hub; r.prefs.token = token
        r.reconfigure()
        toast(ctx, "Salvato")
    }
    val titles = mapOf("perm" to "Permessi", "sys" to "Sistema", "share" to "Condividi", "audio" to "Audio")

    ModuleSwitch(sub) { page ->
        ScreenList("Impostazioni") {
            if (page != null) item { BackRow("Impostazioni", titles[page] ?: "", { onSub(null) }) }
            @Suppress("UNUSED_EXPRESSION") tick
            when (page) {
                null -> {
                    val missing = missingPerms(ctx)
                    item {
                        CCard {
                            Text("Relay 1.0.${r.updater.current}", style = CanoneType.title2, color = c.labelPrimary)
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                                CBadge(if (hubState == "Connesso") "Server online" else "Server offline", if (hubState == "Connesso") Tone.Positive else Tone.Caution)
                                CBadge(if (missing == 0) "Permessi ok" else "$missing permessi da concedere", if (missing == 0) Tone.Positive else Tone.Caution)
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                            NavTile(5, "Permessi", if (missing == 0) "Tutti" else "$missing", if (missing == 0) "concessi" else "da concedere", TINT_B, Modifier.weight(1f)) { onSub("perm") }
                            NavTile(0, "Sistema", "Versione", "aggiornamenti, server, dispositivo", TINT_A, Modifier.weight(1f)) { onSub("sys") }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                            NavTile(1, "Condividi", if (r.prefs.lanEnabled) "Diretto" else "Server", "collegamento, screenshot, notifiche", TINT_C, Modifier.weight(1f)) { onSub("share") }
                            NavTile(4, "Audio", when (rootState) { true -> "Root"; false -> "No root"; null -> "…" }, "cuffie e passaggio", TINT_D, Modifier.weight(1f)) { onSub("audio") }
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
                            CRow("Stato", hubState) { CBadge(if (hubState == "Connesso") "Online" else "Offline", if (hubState == "Connesso") Tone.Positive else Tone.Caution) }
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
                                    CBadge(when (rooted) { true -> "Attivo"; false -> "No"; null -> "…" }, if (rooted == true) Tone.Positive else Tone.Neutral)
                                    if (rooted != true) CButton("Riprova", { r.headphones.retryRoot() })
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
