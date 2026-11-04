package it.ponte.ui

import android.media.session.PlaybackState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.ponte.Router
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Lo stato di un player, che sia questo telefono o un altro dispositivo. */
private class PState(val id: String, val title: String, val artist: String, val playing: Boolean, val pos: Long, val dur: Long, val vol: Float?, val art: android.graphics.Bitmap?)

private fun clock(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

/**
 * Player universale a schermo intero: in alto i dispositivi (anello = suona), al centro quello scelto con copertina,
 * avanzamento, comandi e volume; in fondo gli altri in riga, ciascuno con il suo tasto.
 */
@Composable
fun UniversalPlayer(r: Router, startId: String, onClose: () -> Unit) {
    val c = Canone.colors
    val ctx = LocalContext.current
    androidx.activity.compose.BackHandler(onBack = onClose)
    val me = r.deviceId
    val np = LocalNowPlaying.current
    val media by r.media.media.collectAsState()
    val devices by r.devices.collectAsState()
    var sel by remember { mutableStateOf(startId) }
    var single by remember { mutableStateOf(r.prefs.singlePlay) }

    // volume del telefono
    val am = remember { ctx.getSystemService(android.media.AudioManager::class.java) }
    val maxVol = remember { am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var localVol by remember { mutableIntStateOf(am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)) }
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(500); tick = System.currentTimeMillis(); localVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) } }

    fun state(id: String): PState? {
        if (id == me) {
            val n = np ?: return media[me]?.let { PState(me, it.title, it.artist, it.playing, it.pos, it.dur, localVol.toFloat() / maxVol, null) }
            val st = n.controller.playbackState
            val dur = n.controller.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val pos = when {
                st == null -> 0L
                st.state == PlaybackState.STATE_PLAYING -> st.position + ((android.os.SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
                else -> st.position
            }
            return PState(me, n.title, n.artist, n.playing, pos.coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE), dur, localVol.toFloat() / maxVol, n.art)
        }
        val m = media[id] ?: return null
        val pos = if (m.playing) (m.pos + (tick - m.at)).coerceAtMost(if (m.dur > 0) m.dur else Long.MAX_VALUE) else m.pos
        return PState(id, m.title, m.artist, m.playing, pos, m.dur, if (m.vol >= 0) m.vol / 100f else null, null)
    }

    fun cmd(id: String, a: String, v: Long = 0L) {
        if (id == me && np != null) {
            val tc = np.controller.transportControls
            when (a) { "play" -> tc.play(); "pause" -> tc.pause(); "next" -> tc.skipToNext(); "prev" -> tc.skipToPrevious(); "seek" -> tc.seekTo(v) }
            if (a == "vol") { localVol = (v * maxVol / 100).toInt().coerceIn(0, maxVol); am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, localVol, 0) }
        } else r.media.cmd(id, a, v)
    }

    val ids = buildList { add(me); devices.forEach { add(it.id) }; media.keys.forEach { add(it) } }.distinct()
    fun name(id: String) = if (id == me) "Questo telefono" else r.deviceName(id)
    val cur = state(sel)
    val moods = remember(cur?.art) { moodColors(cur?.art) }
    val anyPlaying = ids.count { state(it)?.playing == true }

    var dy by remember { mutableStateOf(0f) }
    Box(
        Modifier.fillMaxSize().background(c.bgCanvas)
            .pointerInput(Unit) { detectTapGestures { } }
            .pointerInput(Unit) { detectVerticalDragGestures(onDragEnd = { if (dy > 160f) onClose(); dy = 0f }, onDragCancel = { dy = 0f }) { _, d -> dy += d } }
            .drawBehind {
                val w = size.width; val hh = size.height
                val spots = listOf(Offset(w * 0.1f, hh * 0.12f), Offset(w * 0.95f, hh * 0.42f), Offset(w * 0.25f, hh * 0.92f))
                moods.forEachIndexed { i, col -> drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.38f), Color.Transparent), spots[i % 3], w), w, spots[i % 3]) }
            },
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())) {
            // barra in alto: chiudi, una alla volta
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.s5, vertical = Space.s3), verticalAlignment = Alignment.CenterVertically) {
                RoundGlass(40.dp, false, onClose) { Text("⌄", style = CanoneType.title3, color = c.labelPrimary) }
                Spacer(Modifier.weight(1f))
                val t = rememberDropTouch()
                Row(
                    Modifier.height(36.dp).clip(CircleShape).background(if (single) c.accent else c.labelPrimary.copy(alpha = 0.08f))
                        .dropGestures(t, { single = !single; r.prefs.singlePlay = single }).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("1", style = CanoneType.footnote.copy(fontWeight = FontWeight.Bold), color = if (single) Color.White else c.labelSecondary)
                    Text("Una alla volta", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = if (single) Color.White else c.labelSecondary)
                }
            }

            // dispositivi
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.s5),
                horizontalArrangement = Arrangement.spacedBy(Space.s4),
            ) {
                ids.forEach { id ->
                    val s = state(id)
                    val on = s?.playing == true
                    val pic = DevicePics.rememberPic(id)
                    Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box {
                            RoundGlass(56.dp, id == sel, { sel = id }, ring = if (on) c.accent else null) {
                                if (pic != null) Image(pic, null, Modifier.fillMaxSize().padding(10.dp), contentScale = ContentScale.Fit)
                                else TabGlyph(1, if (id == sel) Color.White else c.labelSecondary, false, Modifier.size(20.dp))
                            }
                            if (on) Box(Modifier.align(Alignment.BottomEnd).clip(CircleShape).background(c.accent).padding(horizontal = 5.dp, vertical = 4.dp)) { EqBars(Color.White) }
                        }
                        Text(name(id), style = CanoneType.footnote.copy(fontSize = 11.sp, fontWeight = if (id == sel) FontWeight.SemiBold else FontWeight.Normal),
                            color = if (id == sel) c.labelPrimary else c.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }

            Spacer(Modifier.height(Space.s5))

            // player scelto
            AnimatedContent(sel, transitionSpec = { fadeIn(tween(220)).togetherWith(fadeOut(tween(160))) }, label = "player") { id ->
                val s = state(id)
                Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    BoxWithConstraints(Modifier.fillMaxWidth().widthIn(max = 380.dp)) {
                        val side = minOf(maxWidth, 340.dp)
                        Box(
                            Modifier.align(Alignment.Center).size(side).shadow(24.dp, RoundedCornerShape(Radius.xl), ambientColor = Color.Black.copy(alpha = 0.22f), spotColor = Color.Black.copy(alpha = 0.30f))
                                .clip(RoundedCornerShape(Radius.xl)).background(c.bgSunken),
                            contentAlignment = Alignment.Center,
                        ) {
                            val art = s?.art
                            val pic = DevicePics.rememberPic(id)
                            when {
                                art != null -> Image(art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                pic != null -> Image(pic, null, Modifier.fillMaxSize().padding(56.dp), contentScale = ContentScale.Fit)
                                else -> TabGlyph(4, c.labelTertiary, false, Modifier.size(64.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(Space.s5))
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(name(id), style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp), color = if (s?.playing == true) c.accent else c.labelTertiary)
                        Text(s?.title?.ifEmpty { null } ?: "Silenzio", style = CanoneType.title2, color = c.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(s?.artist?.ifEmpty { null } ?: " ", style = CanoneType.body, color = c.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(Space.s4))

                    // avanzamento
                    var seek by remember(id) { mutableStateOf<Float?>(null) }
                    val dur = s?.dur ?: 0L
                    val frac = seek ?: if (dur > 0) (s!!.pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                    DropSlider(frac, { if (dur > 0) seek = it }, onFinish = { f -> if (dur > 0) cmd(id, "seek", (f * dur).toLong()); seek = null })
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (dur > 0) clock((frac * dur).toLong()) else "–:––", style = CanoneType.footnote, color = c.labelTertiary)
                        Text(if (dur > 0) "−" + clock(dur - (frac * dur).toLong()) else "–:––", style = CanoneType.footnote, color = c.labelTertiary)
                    }
                    Spacer(Modifier.height(Space.s4))

                    // comandi: −15 s, indietro, play, avanti, +15 s
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        RoundGlass(44.dp, false, { s?.let { cmd(id, "seek", (it.pos - 15_000).coerceAtLeast(0)) } }) { Text("−15", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.labelSecondary) }
                        RoundGlass(54.dp, false, { cmd(id, "prev") }) { MediaIcon(0, c.labelPrimary, Modifier.size(22.dp)) }
                        RoundGlass(72.dp, true, { cmd(id, if (s?.playing == true) "pause" else "play") }) { MediaIcon(if (s?.playing == true) 2 else 1, Color.White, Modifier.size(30.dp)) }
                        RoundGlass(54.dp, false, { cmd(id, "next") }) { MediaIcon(3, c.labelPrimary, Modifier.size(22.dp)) }
                        RoundGlass(44.dp, false, { s?.let { cmd(id, "seek", if (it.dur > 0) (it.pos + 15_000).coerceAtMost(it.dur) else it.pos + 15_000) } }) { Text("+15", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.labelSecondary) }
                    }
                    Spacer(Modifier.height(Space.s5))

                    // volume del dispositivo
                    var vol by remember(id) { mutableStateOf<Float?>(null) }
                    val shownVol = vol ?: s?.vol
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                        SpeakerIcon(false, c.labelTertiary, Modifier.size(18.dp))
                        DropSlider(shownVol ?: 0f, { f -> vol = f; if (id == me) cmd(id, "vol", (f * 100).roundToInt().toLong()) }, Modifier.weight(1f),
                            onFinish = { f -> cmd(id, "vol", (f * 100).roundToInt().toLong()); vol = null })
                        SpeakerIcon(true, c.labelTertiary, Modifier.size(18.dp))
                    }
                }
            }

            // gli altri
            val others = ids.filter { it != sel && state(it)?.title?.isNotEmpty() == true }
            if (others.isNotEmpty() || anyPlaying > 1) {
                Spacer(Modifier.height(Space.s6))
                Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Altri dispositivi", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.labelSecondary, modifier = Modifier.weight(1f))
                    if (anyPlaying > 1) PillOn("Ferma tutti") { ids.forEach { if (state(it)?.playing == true) cmd(it, "pause") } }
                }
                Spacer(Modifier.height(Space.s2))
                val shape = RoundedCornerShape(Radius.lg)
                Column(Modifier.fillMaxWidth().padding(horizontal = Space.s5).clip(shape).background(c.bgSurface).padding(vertical = 4.dp)) {
                    others.forEachIndexed { i, id ->
                        val s = state(id) ?: return@forEachIndexed
                        if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 64.dp).height(1.dp).background(c.lineSeparator))
                        val pic = DevicePics.rememberPic(id)
                        Row(
                            Modifier.fillMaxWidth().dropClickable({ sel = id }, corner = 16.dp).padding(horizontal = Space.s3, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3),
                        ) {
                            Box(Modifier.size(40.dp).clip(CircleShape).background(c.bgSunken).then(if (s.playing) Modifier.border(2.dp, c.accent, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
                                if (pic != null) Image(pic, null, Modifier.fillMaxSize().padding(7.dp), contentScale = ContentScale.Fit) else TabGlyph(1, c.labelSecondary, false, Modifier.size(16.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(s.title, style = CanoneType.bodyMedium, color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(name(id), s.artist).filter { it.isNotEmpty() }.joinToString(" · "), style = CanoneType.footnote, color = if (s.playing) c.accent else c.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            RoundGlass(40.dp, s.playing, { cmd(id, if (s.playing) "pause" else "play") }) { MediaIcon(if (s.playing) 2 else 1, if (s.playing) Color.White else c.labelPrimary, Modifier.size(18.dp)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(Space.s6))
        }
    }
}
