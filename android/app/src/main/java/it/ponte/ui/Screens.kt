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
import it.ponte.Router
import it.ponte.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

@Composable
private fun ScreenList(title: String, content: LazyListScope.() -> Unit) {
    val c = Canone.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 720.dp).fillMaxHeight(),
            contentPadding = PaddingValues(horizontal = Space.s4, vertical = Space.s5),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            // Il titolo sta nella testata animata sopra la pagina.
            content()
        }
    }
}

/** Prima scheda: le fibre animate dietro lo stato generale. Non reagisce al tocco, così non ruba lo scorrimento. */
@Composable
private fun HeroCard(title: String, sub: String) {
    val c = Canone.colors
    val shape = RoundedCornerShape(Radius.xl)
    Box(Modifier.fillMaxWidth().height(176.dp).clip(shape).background(c.bgSunken).border(BorderStroke(1.dp, c.lineSeparator), shape)) {
        GradientView(Modifier.matchParentSize(), shape = "hero", cell = 12, ascii = 1, touch = false)
        Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0.3f to androidx.compose.ui.graphics.Color.Transparent, 1f to c.bgSurface.copy(alpha = 0.94f))))
        Column(Modifier.align(Alignment.BottomStart).padding(Space.s5), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = CanoneType.title2, color = c.labelPrimary)
            Text(sub, style = CanoneType.footnote, color = c.labelSecondary)
        }
    }
}

@Composable
private fun SectionTitle(text: String) = Text(text, style = CanoneType.title3, color = Canone.colors.labelPrimary)

@Composable
private fun CChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Canone.colors
    Box(
        Modifier.clip(CircleShape).background(if (selected) c.accentSubtle else c.bgSunken)
            .border(BorderStroke(1.5.dp, if (selected) c.accent else androidx.compose.ui.graphics.Color.Transparent), CircleShape)
            .clickable(onClick = onClick).padding(horizontal = Space.s4, vertical = Space.s2),
    ) { Text(label, style = CanoneType.footnote, color = if (selected) c.accentOnSubtle else c.labelPrimary) }
}

internal fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
internal fun imagesPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
internal fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
internal fun listenerEnabled(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
private fun time(ts: Long, ctx: Context) = DateFormat.getTimeFormat(ctx).format(Date(ts))
internal fun dateTime(ts: Long, ctx: Context) = DateFormat.getMediumDateFormat(ctx).format(Date(ts)) + " " + time(ts, ctx)

// ---------------------------------------------------------------- Dispositivi

@Composable
fun DevicesScreen(r: Router) {
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
                    CText("Nessun altro dispositivo. Apri Ponte su un altro dispositivo, con lo stesso server e lo stesso token.")
                } else devices.forEachIndexed { i, d ->
                    if (i > 0) CDivider()
                    val what = buildList {
                        add(if (d.platform == "windows") "Windows" else "Android")
                        if ("audio-src" in d.caps) add("sorgente audio")
                        if ("audio-sink" in d.caps) add("riceve audio")
                    }.joinToString(" · ")
                    CRow(d.name, what) { CBadge(when (d.via) { "lan" -> "Diretto"; "bt" -> "Bluetooth"; else -> "Server" }, if (d.via == "server") Tone.Neutral else Tone.Accent) }
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

// ---------------------------------------------------------------- Appunti

@Composable
fun ClipScreen(r: Router) {
    val ctx = LocalContext.current
    val history by r.clipboard.history.collectAsState()
    ScreenList("Appunti") {
        item {
            CCard {
                CText("Quello che copi su un dispositivo collegato arriva subito sugli altri.")
                CButton("Invia appunti", {
                    toast(ctx, if (r.clipboard.sendCurrent()) "Appunti inviati" else "Negli appunti non c'è niente da inviare")
                }, kind = ButtonKind.Primary)
                CText(
                    "Android non lascia a un'app leggere gli appunti in background. Per inviare dal telefono usa questo pulsante, " +
                        "il riquadro Invia appunti nelle impostazioni rapide o il pulsante nella notifica di Ponte.",
                    tertiary = true,
                )
            }
        }
        if (history.isEmpty()) {
            item { CCard { CText("Nessun appunto. Copia qualcosa su un dispositivo collegato e comparirà qui.") } }
        } else {
            items(history, key = { it.ts.toString() + it.text.hashCode() }) { h ->
                CCard {
                    CRow(h.text, (if (h.mine) "Inviato da qui" else r.deviceName(h.from)) + " · " + time(h.ts, ctx))
                    CButton("Copia", { r.clipboard.copyLocal(h.text); toast(ctx, "Copiato") })
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArchiveScreen(r: Router) {
    val ctx = LocalContext.current
    val c = Canone.colors
    val all by r.files.items.collectAsState()
    var filter by remember { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<FileEntry?>(null) }
    val list = all.filter { filter.isEmpty() || it.kind == filter }

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
            contentPadding = PaddingValues(horizontal = Space.s4, vertical = Space.s5),
            horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                CButton("Invia file", { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    CChip("Tutto", filter == "") { filter = "" }
                    CChip("Screenshot", filter == "screenshot") { filter = "screenshot" }
                    CChip("File", filter == "file") { filter = "file" }
                }
            }
            if (list.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    CCard { CText("Nessun elemento. Fai uno screenshot su un dispositivo collegato e comparirà qui.") }
                }
            }
            items(list, key = { it.fid }) { e ->
                Column(
                    Modifier.clip(RoundedCornerShape(Radius.lg)).background(c.bgSurface)
                        .border(BorderStroke(1.dp, c.lineSeparator), RoundedCornerShape(Radius.lg))
                        .combinedClickable(onClick = { open(e) }, onLongClick = { toDelete = e }).padding(Space.s2),
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
fun AudioScreen(r: Router) {
    val devices by r.devices.collectAsState()
    val active by r.audio.activeSource.collectAsState()
    val sources = devices.filter { "audio-src" in it.caps }
    val ctx = LocalContext.current
    val hps by r.headphones.list.collectAsState()
    val cannot by r.headphones.cannotDisconnect.collectAsState()
    var marked by remember { mutableStateOf(r.prefs.headphones) }
    var idle by remember { mutableIntStateOf(r.prefs.idleMinutes) }
    var perm by remember { mutableStateOf(r.headphones.hasPermission()) }
    val askBt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { perm = r.headphones.hasPermission() }
    val holder by r.headphones.holder.collectAsState()
    val focus by r.headphones.focus.collectAsState()
    val lockedBy by r.headphones.lockedBy.collectAsState()
    val rooted by r.headphones.rooted.collectAsState()
    var preferred by remember { mutableStateOf(r.prefs.preferredHp) }
    var mute by remember { mutableStateOf(r.prefs.fallbackMute) }
    fun who(id: String?) = when (id) { null -> "nessuno"; r.deviceId -> "questo dispositivo"; else -> r.deviceName(id) }
    ScreenList("Audio") {
        item {
            CCard {
                SectionTitle("Passaggio automatico")
                CText("Le cuffie vanno dove fai partire l'audio, se è il dispositivo che stai usando. Blocca per tenerle qui mentre usi altro.")
                CRow("Cuffie su", who(holder))
                CRow("In uso", who(focus))
                CRow("Blocco", if (lockedBy == null) "Nessuno" else "Su " + who(lockedBy)) {
                    CButton(if (lockedBy == r.deviceId) "Sblocca" else "Blocca qui", { r.headphones.toggleLock() },
                        kind = if (lockedBy == r.deviceId) ButtonKind.Secondary else ButtonKind.Primary)
                }
                CDivider()
                val choices = listOf("") + hps.map { it.address }
                CRow("Cuffie preferite qui", hps.firstOrNull { it.address == preferred }?.name ?: "Nessuna: passaggio spento") {
                    CButton("Cambia", {
                        preferred = choices[(choices.indexOf(preferred) + 1) % choices.size]
                        r.prefs.preferredHp = preferred
                    })
                }
                CRow("Se non ci sono", if (mute) "Muto" else "Altoparlante") {
                    CButton("Cambia", { mute = !mute; r.prefs.fallbackMute = mute })
                }
                if (preferred.isNotEmpty() && hps.none { it.address == preferred && it.connected }) {
                    CButton("Collega qui", { r.headphones.connectHere(preferred) })
                }
                if (rooted == false) {
                    CText("Su Android collegare e scollegare le cuffie richiede il root. Senza, Ponte avvisa gli altri dispositivi ma qui non può spostarle.", tertiary = true)
                }
                if (!android.provider.Settings.canDrawOverlays(ctx)) {
                    CText("Per capire quando usi questo dispositivo serve il permesso \"Mostra sopra altre app\" (in Impostazioni).", tertiary = true)
                }
            }
        }
        item {
            CCard {
                SectionTitle("Cuffie")
                CText(
                    "Segna le cuffie associate. Ponte tiene collegato un solo dispositivo alla volta: quando l'audio parte qui, " +
                        "gli altri le lasciano, e dopo un po' di inattività le lascia anche questo, così non occupi i due slot.",
                )
                if (!perm) {
                    CButton("Concedi l'accesso al Bluetooth", { askBt.launch(Manifest.permission.BLUETOOTH_CONNECT) }, kind = ButtonKind.Primary)
                } else if (hps.isEmpty()) {
                    CText("Nessuna cuffia associata a questo dispositivo.", tertiary = true)
                } else {
                    hps.forEach { h ->
                        CSwitchRow(h.name, if (h.connected) "Collegate qui" else "Non collegate qui", h.address in marked) { on ->
                            marked = if (on) marked + h.address else marked - h.address
                            r.prefs.headphones = marked
                        }
                    }
                    val options = listOf(0, 5, 10, 20, 30)
                    CRow("Scollega dopo", if (idle == 0) "Mai" else "$idle minuti senza audio") {
                        CButton("Cambia", {
                            idle = options[(options.indexOf(idle) + 1) % options.size]
                            r.prefs.idleMinutes = idle
                        })
                    }
                }
                if (cannot) {
                    CText(
                        "L'ultimo tentativo di spostare le cuffie da qui non è riuscito: serve il root, e Ponte deve averlo ottenuto.",
                        tertiary = true,
                    )
                }
            }
        }
        item {
            CCard {
                SectionTitle("Ascolta qui")
                CText(
                    "Collega le cuffie a questo dispositivo e scegli da quale altro vuoi sentire l'audio. " +
                        "Le cuffie restano sempre collegate a questo dispositivo: cambia solo la sorgente.",
                )
                CText("Il ritardo è di circa 100 ms. Va bene per video e musica, meno per i giochi.", tertiary = true)
            }
        }
        if (sources.isEmpty()) {
            item { CCard { CText("Nessuna sorgente. Apri Ponte su un PC Windows e il suo audio comparirà qui.") } }
        } else {
            items(sources, key = { it.id }) { d ->
                CCard {
                    CRow(d.name, if (active == d.id) "In riproduzione su questo dispositivo" else "Disponibile") {
                        if (active == d.id) CButton("Ferma", { r.audio.stopListening(d.id) })
                        else CButton("Ascolta qui", { r.audio.listenTo(d.id) })
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Impostazioni

@Composable
fun SettingsScreen(r: Router, replayOnboarding: () -> Unit = {}) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(r.prefs.name) }
    var hub by remember { mutableStateOf(r.prefs.hubUrl) }
    var token by remember { mutableStateOf(r.prefs.token) }
    var tick by remember { mutableIntStateOf(0) }

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

    ScreenList("Impostazioni") {
        item {
            CCard {
                SectionTitle("Questo dispositivo")
                CField("Nome del dispositivo", name, { name = it }, "Tablet del salotto")
                CField("Indirizzo del server", hub, { hub = it }, "ponte.esempio.it", mono = true)
                CField("Token", token, { token = it }, "Lo stesso impostato sul server", mono = true, secret = true)
                CButton("Salva", {
                    r.prefs.name = name; r.prefs.hubUrl = hub; r.prefs.token = token
                    r.reconfigure()
                    toast(ctx, "Salvato")
                }, kind = ButtonKind.Primary)
            }
        }
        item {
            @Suppress("UNUSED_EXPRESSION") tick
            CCard {
                SectionTitle("Permessi")
                if (Build.VERSION.SDK_INT >= 33) {
                    PermRow("Notifiche di Ponte", "Serve al servizio in primo piano", has(ctx, Manifest.permission.POST_NOTIFICATIONS)) {
                        askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    CDivider()
                }
                PermRow("Mostra sopra altre app", "Per inviare gli appunti copiati con l'app in background", Settings.canDrawOverlays(ctx)) {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
                }
                CDivider()
                PermRow("Appunti in tempo reale", "Legge i log di sistema per accorgersi di quando copi", r.clipLog.hasLogPermission()) {
                    // Con il root si concede da soli; altrimenti serve il comando adb mostrato qui sotto.
                    val ok = try {
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "pm grant ${ctx.packageName} android.permission.READ_LOGS")).waitFor() == 0
                    } catch (_: Exception) { false }
                    toast(ctx, if (ok) "Concesso: riapri Ponte" else "Serve il comando adb, vedi sotto")
                }
                CText("Senza root: dal PC, con il debug USB attivo, esegui  adb shell pm grant ${ctx.packageName} android.permission.READ_LOGS", tertiary = true)
                CDivider()
                if (Build.VERSION.SDK_INT >= 31) {
                    PermRow("Bluetooth", "Cuffie e collegamento di riserva senza Wi-Fi", has(ctx, Manifest.permission.BLUETOOTH_CONNECT)) {
                        askOther.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                    CDivider()
                }
                PermRow("Dispositivi Wi-Fi vicini", "Wi-Fi Direct tra telefono e tablet quando non c'è rete", has(ctx, nearbyPerm)) {
                    askOther.launch(nearbyPerm)
                }
                CDivider()
                PermRow("Foto e screenshot", "Per inviare gli screenshot", has(ctx, imagesPermission())) { askImages.launch(imagesPermission()) }
                CDivider()
                PermRow("Accesso alle notifiche", "Per mostrarle sul PC", listenerEnabled(ctx)) {
                    ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                CDivider()
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                PermRow("Nessun risparmio batteria", "Evita che Android fermi Ponte", pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }
        item {
            val status by r.updater.status.collectAsState()
            CCard {
                SectionTitle("Aggiornamenti")
                CRow("Versione 1.0.${r.updater.current}", status.ifEmpty { "Si aggiorna da sola dalle release su GitHub, tramite il tuo server" }) {
                    CButton("Cerca ora", { r.scope.launch { r.updater.check(install = true) } })
                }
            }
        }
        item {
            CButton("Rivedi la configurazione iniziale", replayOnboarding, Modifier.fillMaxWidth(), kind = ButtonKind.Tertiary)
        }
    }
}
