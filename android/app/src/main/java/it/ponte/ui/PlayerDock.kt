package it.ponte.ui

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
        Box(Modifier.size(34.dp).clip(CircleShape).background(c.bgSunken), contentAlignment = Alignment.Center) {
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
            Box(Modifier.size(40.dp).clip(CircleShape).background(c.bgSunken), contentAlignment = Alignment.Center) {
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
    val c = Canone.colors
    val ctx = LocalContext.current
    androidx.activity.compose.BackHandler(onBack = onClose)
    val moods = remember(np.art) { moodColors(np.art) }
    val am = remember { ctx.getSystemService(android.media.AudioManager::class.java) }
    val maxVol = remember { am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var vol by remember { mutableIntStateOf(am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)) }
    LaunchedEffect(Unit) { while (true) { delay(1200); vol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) } }
    val dur = np.controller.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
    var pos by remember { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(np.controller, np.title, np.playing) {
        while (true) {
            val st = np.controller.playbackState
            if (!seeking) pos = when {
                st == null -> 0L
                st.state == PlaybackState.STATE_PLAYING -> st.position + ((android.os.SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
                else -> st.position
            }.coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
            delay(500)
        }
    }
    val tc = np.controller.transportControls
    var dy by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier.fillMaxSize().background(c.bgCanvas)
            .pointerInput(Unit) { detectVerticalDragGestures(onDragEnd = { if (dy > 140f) onClose(); dy = 0f }, onDragCancel = { dy = 0f }) { _, d -> dy += d } }
            .drawBehind {
                val w = size.width; val hh = size.height
                val spots = listOf(Offset(w * 0.15f, hh * 0.10f), Offset(w * 0.9f, hh * 0.45f), Offset(w * 0.3f, hh * 0.95f))
                moods.forEachIndexed { i, col -> drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.42f), Color.Transparent), spots[i % 3], w * 0.95f), w * 0.95f, spots[i % 3]) }
            },
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.size(width = 42.dp, height = 5.dp).clip(CircleShape).background(c.lineStrong))
            Spacer(Modifier.weight(0.6f))
            Box(
                Modifier.fillMaxWidth().widthIn(max = 420.dp).aspectRatio(1f).shadow(28.dp, RoundedCornerShape(Radius.xl), ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.35f))
                    .clip(RoundedCornerShape(Radius.xl)).background(c.bgSunken),
                contentAlignment = Alignment.Center,
            ) {
                np.art?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: TabGlyph(4, c.labelTertiary, false, Modifier.size(64.dp))
            }
            Spacer(Modifier.weight(0.6f))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(np.title.ifEmpty { "Senza titolo" }, style = CanoneType.title2, color = c.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(np.artist, style = CanoneType.body, color = c.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(18.dp))
            if (dur > 0) Column(Modifier.fillMaxWidth()) {
                DropSlider(pos.toFloat() / dur, { f -> seeking = true; pos = (f * dur).toLong() }, onFinish = { f ->
                    pos = (f * dur).toLong(); tc.seekTo(pos)
                    scope.launch { delay(900); seeking = false }
                })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(clockText(pos), style = CanoneType.footnote, color = c.labelTertiary)
                    Text("−" + clockText(dur - pos), style = CanoneType.footnote, color = c.labelTertiary)
                }
            }
            Spacer(Modifier.height(18.dp))
            GlassTransport(listOf(0, if (np.playing) 2 else 1, 3), { i -> when (i) { 0 -> tc.skipToPrevious(); 1 -> if (np.playing) tc.pause() else tc.play(); else -> tc.skipToNext() } })
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                SpeakerIcon(false, c.labelTertiary, Modifier.size(18.dp))
                DropSlider(vol.toFloat() / maxVol, { f ->
                    val v = (f * maxVol).roundToInt().coerceIn(0, maxVol)
                    if (v != vol) { vol = v; am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0) }
                }, Modifier.weight(1f))
                SpeakerIcon(true, c.labelTertiary, Modifier.size(18.dp))
            }
            Spacer(Modifier.height(20.dp))
        }
    }
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
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(c.bgSunken), contentAlignment = Alignment.Center) {
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
