package it.ponte.ui

import it.ponte.Ponte

import androidx.compose.ui.input.pointer.positionChange

import android.media.session.PlaybackState
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** La bolla con la copertina, appoggiata sopra la barra: un tocco apre l'estensione. */
@Composable
fun CoverBubble(np: NowPlaying, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Canone.colors
    val t = rememberDropTouch()
    Box(modifier.size(46.dp).dropGestures(t, onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().dropDeform(t).drop(isDark(), press = { t.press.value }, touch = t))
        Box(Modifier.size(34.dp).clip(CircleShape).background(c.labelPrimary.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
            np.art?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: TabGlyph(4, c.labelSecondary, false, Modifier.size(18.dp))
        }
    }
}

/** L'estensione in altezza della barra: copertina piccola e titolo in linea. Tocco = schermo intero, trascina in giù = si chiude. */
@Composable
fun MiniPlayer(np: NowPlaying, layer: GraphicsLayer, onOpen: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = Canone.colors
    val dark = isDark()
    var origin by remember { mutableStateOf(Offset.Zero) }
    val h = 56.dp
    var dy by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(
        modifier.onGloballyPositioned { origin = it.positionInParent() }.widthIn(max = 440.dp).fillMaxWidth().height(h)
            .shadow(8.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.10f))
            .pointerInput(Unit) { detectVerticalDragGestures(onDragEnd = { if (dy > 30f) onClose(); dy = 0f }, onDragCancel = { dy = 0f }) { _, d -> dy += d } },
    ) {
        Glass(layer, { origin }, maxWidth, h, edge = 12.dp, amount = 5.dp, aberr = 0.18f, sat = 1.5f, magnify = 0f, blur = 14.dp)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to c.bgCanvas.copy(alpha = if (dark) 0.42f else 0.50f), 1f to c.bgCanvas.copy(alpha = if (dark) 0.30f else 0.36f)), CircleShape))
        Box(Modifier.fillMaxSize().drop(dark, tint = Color.Transparent))
        Row(
            Modifier.fillMaxSize().dropClickable(onOpen, corner = 100.dp).padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(c.labelPrimary.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
                np.art?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: TabGlyph(4, c.labelSecondary, false, Modifier.size(20.dp))
            }
            Text(np.title.ifEmpty { "Senza titolo" }, style = CanoneType.bodyMedium, color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box(Modifier.size(44.dp).dropClickable({ val tc = np.controller.transportControls; if (np.playing) tc.pause() else tc.play() }, corner = 100.dp), contentAlignment = Alignment.Center) {
                MediaIcon(if (np.playing) 2 else 1, c.labelPrimary, Modifier.size(26.dp))
            }
        }
    }
}

private fun clockText(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

/** Il player a schermo intero, come su iOS: copertina grande, luce dai suoi colori, avanzamento, comandi di vetro e volume. */
@Composable
fun FullPlayer(np: NowPlaying, onClose: () -> Unit) {
    val r = Ponte.router ?: return
    UniversalPlayer(r, r.deviceId, onClose)
}


/** Avanzamento del brano come frazione 0..1, aggiornato due volte al secondo. */
@Composable
fun rememberPlayFraction(np: NowPlaying): Float {
    val dur = np.controller.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
    var f by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(np.controller, np.title, np.playing) {
        while (true) {
            val st = np.controller.playbackState
            val pos = when {
                st == null -> 0L
                st.state == PlaybackState.STATE_PLAYING -> st.position + ((android.os.SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
                else -> st.position
            }
            f = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            delay(500)
        }
    }
    return f
}

/** Il player agganciato alla barra: copertina, titolo, autore e sotto la linea di avanzamento. Tocco = schermo intero, trascina in su = torna la goccia. */
@Composable
fun DockedStrip(np: NowPlaying, layer: GraphicsLayer, onOpen: () -> Unit, onDetach: () -> Unit, modifier: Modifier = Modifier) {
    val c = Canone.colors
    val dark = isDark()
    var origin by remember { mutableStateOf(Offset.Zero) }
    val h = 58.dp
    val frac = rememberPlayFraction(np)
    var dy by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(
        modifier.onGloballyPositioned { origin = it.positionInParent() }.widthIn(max = 440.dp).fillMaxWidth().height(h)
            .shadow(8.dp, RoundedCornerShape(26.dp), ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.10f))
            .pointerInput(Unit) { detectVerticalDragGestures(onDragEnd = { if (dy < -30f) onDetach(); dy = 0f }, onDragCancel = { dy = 0f }) { _, d -> dy += d } },
    ) {
        val shape = RoundedCornerShape(26.dp)
        Glass(layer, { origin }, maxWidth, h, edge = 12.dp, amount = 5.dp, aberr = 0.18f, sat = 1.5f, magnify = 0f, blur = 14.dp, corner = 26.dp)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to c.bgCanvas.copy(alpha = if (dark) 0.42f else 0.50f), 1f to c.bgCanvas.copy(alpha = if (dark) 0.30f else 0.36f)), shape))
        Box(Modifier.fillMaxSize().clip(shape).border(androidx.compose.foundation.BorderStroke(1.dp, c.lineSeparator), shape))
        Column(Modifier.fillMaxSize().dropClickable(onOpen, corner = 26.dp).padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(c.labelPrimary.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
                    np.art?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: TabGlyph(4, c.labelSecondary, false, Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(np.title.ifEmpty { "Senza titolo" }, style = CanoneType.bodyMedium.copy(fontSize = 14.sp), color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (np.artist.isNotEmpty()) Text(np.artist, style = CanoneType.footnote.copy(fontSize = 12.sp), color = c.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(c.labelPrimary.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(c.labelSecondary))
            }
        }
    }
}

/** Osserva i trascinamenti verso l'alto senza consumarli, così la barra sotto continua a funzionare. */
fun swipeUpObserver(enabled: Boolean, onUp: () -> Unit): Modifier = if (!enabled) Modifier else Modifier.pointerInput(onUp) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
        var dx = 0f; var dy = 0f; var fired = false
        while (true) {
            val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            val ch = ev.changes.firstOrNull() ?: break
            if (!ch.pressed) break
            val d = ch.positionChange()
            dx += d.x; dy += d.y
            if (!fired && dy < -48.dp.toPx() && kotlin.math.abs(dy) > 2f * kotlin.math.abs(dx)) { fired = true; onUp() }
        }
    }
}


/** Il brano dentro la bolla della barra: copertina piccola, titolo e autore, niente separatori. Tocco = schermo intero. */
@Composable
fun DockSong(np: NowPlaying, onOpen: () -> Unit) {
    val c = Canone.colors
    Row(
        Modifier.fillMaxSize().dropClickable(onOpen, corner = 26.dp).padding(start = 16.dp, end = 18.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(c.labelPrimary.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
            np.art?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: TabGlyph(4, c.labelSecondary, false, Modifier.size(15.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(np.title.ifEmpty { "Senza titolo" }, style = CanoneType.footnote.copy(fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (np.artist.isNotEmpty()) Text(np.artist, style = CanoneType.footnote.copy(fontSize = 11.sp), color = c.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- player universale

private fun mediaName(r: it.ponte.Router, id: String) = if (id == r.deviceId) "Questo telefono" else r.deviceName(id)

/** Posizione stimata di un player remoto (avanza da sola mentre suona). */
@Composable
private fun livePos(m: it.ponte.RemoteMedia): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(m.playing, m.at) { while (m.playing) { now = System.currentTimeMillis(); delay(500) } }
    return if (m.playing) (m.pos + (now - m.at)).coerceAtMost(if (m.dur > 0) m.dur else Long.MAX_VALUE) else m.pos
}

/** Una riga per dispositivo: cosa suona, i comandi e il volume di quel dispositivo. */
@Composable
fun DeviceMediaRow(r: it.ponte.Router, m: it.ponte.RemoteMedia, onDark: Boolean = false) {
    val c = Canone.colors
    val fg = if (onDark) Color.White else c.labelPrimary
    val fg2 = if (onDark) Color.White.copy(alpha = 0.7f) else c.labelTertiary
    val shape = RoundedCornerShape(Radius.lg)
    var vol by remember(m.id, m.vol) { mutableStateOf(m.vol / 100f) }
    Column(Modifier.fillMaxWidth().clip(shape).glass(shape).padding(Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            Column(Modifier.weight(1f)) {
                Text(mediaName(r, m.id), style = CanoneType.footnote.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = if (m.playing) c.accent else fg2, maxLines = 1)
                Text(m.title.ifEmpty { "Niente in riproduzione" }, style = CanoneType.bodyMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (m.artist.isNotEmpty()) Text(m.artist, style = CanoneType.footnote, color = fg2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            GlassTransport(listOf(0, if (m.playing) 2 else 1, 3), { i -> r.media.cmd(m.id, when (i) { 0 -> "prev"; 1 -> if (m.playing) "pause" else "play"; else -> "next" }) }, Modifier.width(150.dp))
        }
        if (m.vol >= 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            SpeakerIcon(false, fg2, Modifier.size(14.dp))
            DropSlider(vol, { f -> vol = f }, Modifier.weight(1f), onFinish = { f -> r.media.cmd(m.id, "vol", (f * 100).toLong()) })
            SpeakerIcon(true, fg2, Modifier.size(14.dp))
        }
    }
}

/** Tutti i dispositivi con un player, tranne [except]. */
@Composable
fun OtherDevicesMedia(r: it.ponte.Router, except: String?, onDark: Boolean = false) {
    val all by r.media.media.collectAsState()
    val list = all.values.filter { it.id != except }.sortedByDescending { it.playing }
    if (list.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s2)) { list.forEach { DeviceMediaRow(r, it, onDark) } }
}

/** Goccia del player quando qui non suona niente ma suona un altro dispositivo: lo mostra e lo comanda. */
@Composable
fun RemoteDrop(r: it.ponte.Router, m: it.ponte.RemoteMedia, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = Canone.colors
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier.widthIn(max = 440.dp).fillMaxWidth().height(56.dp).clip(shape).glass(shape, acrylic = true).dropClickable(onOpen, corner = 28.dp).padding(start = 18.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Column(Modifier.weight(1f)) {
            Text(m.title.ifEmpty { "Senza titolo" }, style = CanoneType.footnote.copy(fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(mediaName(r, m.id), style = CanoneType.footnote.copy(fontSize = 11.sp), color = c.accent, maxLines = 1)
        }
        GlassTransport(listOf(if (m.playing) 2 else 1, 3), { i -> r.media.cmd(m.id, if (i == 0) (if (m.playing) "pause" else "play") else "next") }, Modifier.width(96.dp))
    }
}

/** Il player universale a schermo intero per un dispositivo remoto. */
@Composable
fun RemoteFullPlayer(r: it.ponte.Router, m: it.ponte.RemoteMedia, onClose: () -> Unit) = UniversalPlayer(r, m.id, onClose)
