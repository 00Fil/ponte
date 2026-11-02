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

// ---------------------------------------------------------------- Dispositivi

@Composable
fun DevicesScreen(r: Router) {
    val c = Canone.colors
    val ctx = LocalContext.current
    val devices by r.devices.collectAsState()
    val hub by r.hubState.collectAsState()
    var lan by remember { mutableStateOf(r.prefs.lanEnabled) }
    var shots by remember { mutableStateOf(r.prefs.screenshots) }
    var notif by remember { mutableStateOf(r.prefs.notifications) }
    val askImages = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { r.reconfigure() }

    val hubTone = when (hub) {
        "Connesso" -> Tone.Positive
        "Non configurato" -> Tone.Neutral
        "Connessione in corso" -> Tone.Caution
        else -> Tone.Critical
    }
    val near = devices.count { it.via == "lan" }

    ScreenList("Dispositivi") {
        item { HeroCard(if (devices.isEmpty()) "Solo questo dispositivo" else if (devices.size == 1) "1 dispositivo collegato" else "${devices.size} dispositivi collegati", "Server: ${hub.lowercase()}") }
        item {
            CCard {
                SectionTitle("Collegamenti")
                CRow("Server", "Per chi è fuori casa e per l'archivio") { CBadge(hub, hubTone) }
                CDivider()
                CRow("Diretto", "Rete locale o rete privata, senza server") {
                    when {
                        !lan -> CBadge("Disattivata")
                        near == 0 -> CBadge("Nessun dispositivo")
                        else -> CBadge("$near collegati", Tone.Positive)
                    }
                }
            }
        }
        item {
            CCard {
                SectionTitle("Altri dispositivi")
                if (devices.isEmpty()) {
                    CText("Nessun altro dispositivo. Apri Relay su un altro dispositivo, con lo stesso server e lo stesso token.")
                } else devices.forEachIndexed { i, d ->
                    if (i > 0) CDivider()
                    val what = buildList {
                        add(if (d.platform == "windows") "Windows" else "Android")
                        if ("audio-src" in d.caps) add("sorgente audio")
                        if ("audio-sink" in d.caps) add("riceve audio")
                    }.joinToString(" · ")
                    CRow(d.name, what, Modifier.clip(RoundedCornerShape(Radius.lg)).background(Brush.horizontalGradient(listOf(c.accentSubtle, androidx.compose.ui.graphics.Color.Transparent))).padding(horizontal = Space.s3)) { CBadge(when (d.via) { "lan" -> "Diretto"; "bt" -> "Bluetooth"; else -> "Server" }, if (d.via == "server") Tone.Neutral else Tone.Accent) }
                }
            }
        }
        item {
            CCard {
                SectionTitle("Funzioni")
                CSwitchRow("Collegamento diretto", "Rete locale e rete privata Headscale", lan) {
                    lan = it; r.prefs.lanEnabled = it; r.reconfigure()
                }
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
}

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
fun ShareScreen(r: Router, tab: Int, onTab: (Int) -> Unit) {
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
                Headphones3D(glow, c.accent, c.bgCanvas.luminance() < 0.5f)
                Text(who(holder), style = CanoneType.title2, color = c.labelPrimary)
                Text(
                    if (lockedBy != null) "Bloccate " + who(lockedBy).replaceFirstChar { it.lowercase() } else "Seguono dove ascolti. Trascina per ruotarle.",
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

@Composable
fun SettingsScreen(r: Router, replayOnboarding: () -> Unit = {}, onBackup: () -> Unit = {}) {
    val ctx = LocalContext.current
    val c = Canone.colors
    var sub by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf(r.prefs.name) }
    var hub by remember { mutableStateOf(r.prefs.hubUrl) }
    var token by remember { mutableStateOf(r.prefs.token) }
    var tick by remember { mutableIntStateOf(0) }
    val hubState by r.hubState.collectAsState()
    androidx.activity.compose.BackHandler(enabled = sub != null) { sub = null }

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
    @Composable
    fun Module(title: String, note: String, glyph: Int, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.xl)).background(c.bgSurface)
                .border(BorderStroke(1.dp, c.lineSeparator), RoundedCornerShape(Radius.xl))
                .dropClickable(onClick).padding(Space.s4),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.accentSubtle), contentAlignment = Alignment.Center) {
                TabGlyph(glyph, c.accentOnSubtle, false, Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = CanoneType.body, color = c.labelPrimary)
                Text(note, style = CanoneType.footnote, color = c.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("›", style = CanoneType.title3, color = c.labelTertiary)
        }
    }

    androidx.compose.animation.AnimatedContent(sub, transitionSpec = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) togetherWith (androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140))) }, label = "modulo") { page ->
        ScreenList("Impostazioni") {
            if (page != null) item {
                CButton("‹  Impostazioni", { sub = null }, kind = ButtonKind.Tertiary)
            }
            when (page) {
                null -> {
                    item { Module("Relay", "Dispositivo, server e aggiornamenti", 0) { sub = "relay" } }
                    item { Module("Condividi", "Appunti, screenshot e file", 1) { sub = "share" } }
                    item { Module("Audio", "Cuffie, Bluetooth e root", 4) { sub = "audio" } }
                    item { Module("Backup", "Copia il telefono sul tuo server", 3) { onBackup() } }
                    item { Module("Sistema", "Permessi e batteria", 5) { sub = "system" } }
                }
                "relay" -> {
                    item {
                        CCard {
                            SectionTitle("Questo dispositivo")
                            CField("Nome", name, { name = it }, "Tablet del salotto")
                            CRow("Identificativo", r.deviceId, mono = true)
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
                        val upd by r.updater.ui.collectAsState()
                        val status by r.updater.status.collectAsState()
                        CCard {
                            SectionTitle("Aggiornamenti")
                            CRow("Versione 1.0.${r.updater.current}", status.ifEmpty { "Si aggiorna da sola tramite il tuo server" }) {
                                val busy = upd?.phase == Updater.Phase.Checking || upd?.phase == Updater.Phase.Downloading || upd?.phase == Updater.Phase.Installing
                                if (busy) UpdateRing(upd!!.phase, if ((upd?.total ?: 0) > 0) upd!!.done.toFloat() / upd!!.total else 0f, 28.dp)
                                else CButton("Cerca ora", { r.scope.launch { r.updater.check(install = true, manual = true) } })
                            }
                            CDivider()
                            CButton("Rivedi la configurazione iniziale", replayOnboarding, Modifier.fillMaxWidth(), kind = ButtonKind.Tertiary)
                        }
                    }
                }
                "share" -> {
                    item {
                        @Suppress("UNUSED_EXPRESSION") tick
                        CCard {
                            SectionTitle("Permessi di Condividi")
                            PermRow("Foto e screenshot", "Per inviare gli screenshot", has(ctx, imagesPermission())) { askImages.launch(imagesPermission()) }
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
                }
                "audio" -> {
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
                        @Suppress("UNUSED_EXPRESSION") tick
                        Column(verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                            CCard {
                                SectionTitle("Cuffie")
                                if (!r.headphones.hasPermission()) {
                                    PermRow("Bluetooth", "Cuffie e collegamento di riserva senza Wi-Fi", false) { askOther.launch(Manifest.permission.BLUETOOTH_CONNECT) }
                                } else if (hps.isEmpty()) {
                                    CText("Nessuna cuffia associata a questo dispositivo.", tertiary = true)
                                } else hps.forEach { h ->
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
                else -> {
                    item {
                        @Suppress("UNUSED_EXPRESSION") tick
                        CCard {
                            SectionTitle("Permessi di sistema")
                            if (Build.VERSION.SDK_INT >= 33) {
                                PermRow("Notifiche di Relay", "Serve al servizio in primo piano", has(ctx, Manifest.permission.POST_NOTIFICATIONS)) {
                                    askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                CDivider()
                            }
                            PermRow("Accesso alle notifiche", "Per mostrarle sul PC", listenerEnabled(ctx)) {
                                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                            CDivider()
                            PermRow("Dispositivi Wi-Fi vicini", "Wi-Fi Direct tra telefono e tablet quando non c'è rete", has(ctx, nearbyPerm)) {
                                askOther.launch(nearbyPerm)
                            }
                            CDivider()
                            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                            PermRow("Nessun risparmio batteria", "Evita che Android fermi Relay", pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        }
                    }
                }
            }
        }
    }
}
