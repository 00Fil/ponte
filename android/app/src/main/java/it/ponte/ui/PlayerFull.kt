@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package it.ponte.ui

import android.media.session.PlaybackState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.ponte.Router
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Lo stato di un player, che sia questo telefono o un altro dispositivo. */
private class PState(val id: String, val title: String, val artist: String, val playing: Boolean, val pos: Long, val dur: Long, val vol: Float?, val art: android.graphics.Bitmap?)

private fun clock(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

/** Colore medio della fascia bassa della copertina: lo schermo lo continua senza stacco. */
private fun edgeColor(b: android.graphics.Bitmap?): Color? = try {
    if (b == null) null else {
        val s = android.graphics.Bitmap.createScaledBitmap(b, 16, 16, true)
        var r = 0L; var g = 0L; var bl = 0L; var n = 0
        for (y in 12 until 16) for (x in 0 until 16) { val p = s.getPixel(x, y); r += (p shr 16) and 255; g += (p shr 8) and 255; bl += p and 255; n++ }
        Color((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    }
} catch (_: Exception) { null }

/** Barra di avanzamento e volume bianca: si ingrossa mentre la trascini. */
@Composable
private fun WhiteTrack(value: Float, onChange: (Float) -> Unit, onFinish: (Float) -> Unit, modifier: Modifier = Modifier) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val shown = (drag ?: value).coerceIn(0f, 1f)
    val thick by androidx.compose.animation.core.animateDpAsState(if (drag != null) 10.dp else 6.dp, spring(0.7f, 420f), label = "pista")
    BoxWithConstraints(
        modifier.fillMaxWidth().height(28.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f); onChange(drag!!) },
                    onDragEnd = { drag?.let(onFinish); drag = null }, onDragCancel = { drag = null },
                ) { ch, _ -> drag = (ch.position.x / size.width).coerceIn(0f, 1f); onChange(drag!!) }
            }
            .pointerInput(Unit) { detectTapGestures { o -> val f = (o.x / size.width).coerceIn(0f, 1f); onChange(f); onFinish(f) } },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(thick).clip(CircleShape).background(Color.White.copy(alpha = 0.22f))) {
            Box(Modifier.fillMaxWidth(shown).height(thick).clip(CircleShape).background(Color.White.copy(alpha = 0.88f)))
        }
    }
}

// ---------------------------------------------------------------- player

/**
 * Player universale a schermo intero: la copertina occupa la parte alta e si scioglie nei suoi colori, che continuano
 * fino in fondo; sotto titolo, avanzamento, comandi di vetro e volume. In basso il tasto dei dispositivi apre un'unica
 * sezione con tutti i dispositivi e la riproduzione una alla volta.
 */
@Composable
fun UniversalPlayer(r: Router, startId: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    androidx.activity.compose.BackHandler(onBack = onClose)
    val me = r.deviceId
    val np = LocalNowPlaying.current
    val media by r.media.media.collectAsState()
    val devices by r.devices.collectAsState()
    var sel by remember { mutableStateOf(startId) }
    var single by remember { mutableStateOf(r.prefs.singlePlay) }
    var panel by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = panel) { panel = false }

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
    val s = state(sel)
    val art = s?.art
    val pic = DevicePics.rememberPic(sel)

    // colori: la fascia bassa della copertina continua nello schermo e scurisce verso il fondo
    val edge = remember(art) { edgeColor(art) } ?: Color(0xFF070912)
    val top by animateColorAsState(lerp(edge, Color.Black, 0.10f), tween(600), label = "alto")
    val bottom by animateColorAsState(lerp(edge, Color.Black, 0.55f), tween(600), label = "basso")

    var dy by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom)))
            .pointerInput(Unit) { detectTapGestures { } }
            .pointerInput(Unit) { detectVerticalDragGestures(onDragEnd = { if (dy > 160f) onClose(); dy = 0f }, onDragCancel = { dy = 0f }) { _, d -> if (!panel) dy += d } }
            .graphicsLayer { translationY = (dy * 0.4f).coerceAtLeast(0f) },
    ) {
        // tutto lo sfondo finisce in un livello: i tasti di vetro lo rifrangono
        val bgLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
        var bgPos by remember { mutableStateOf(Offset.Zero) }
        val glass = remember(bgLayer) { Backdrop(bgLayer) { bgPos } }
        Box(
            Modifier.fillMaxSize().onGloballyPositioned { bgPos = it.positionInRoot() }
                .drawWithContent { bgLayer.record { this@drawWithContent.drawContent() }; drawLayer(bgLayer) },
        ) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom))))
            // copertina sfocata dietro tutto: i colori arrivano fino in fondo
            if (art != null) Image(art.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer { alpha = 0.6f }.blur(60.dp), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.55f to bottom.copy(alpha = 0.35f), 1f to bottom.copy(alpha = 0.85f))))

            // copertina infinita: alta, ritagliata al centro; la parte bassa diventa trasparente e si fonde con i suoi stessi colori sotto
            if (art != null) Box(
                Modifier.fillMaxWidth().fillMaxHeight(0.72f)
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.verticalGradient(0f to Color.Black, 0.45f to Color.Black, 0.75f to Color.Black.copy(alpha = 0.55f), 1f to Color.Transparent), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                    },
            ) {
                Image(art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxWidth().fillMaxHeight(0.16f).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.28f), Color.Transparent))))
            } else ParticlesField(Modifier.fillMaxSize())
        }

        Box(Modifier.statusBarsPadding().padding(start = 6.dp, top = 2.dp)) { GlassKey(GK.DOWN, 24.dp, onClose, backdrop = glass) }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(start = 28.dp, end = 28.dp, bottom = 6.dp)) {
            Text(name(sel), style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp), color = Color.White.copy(alpha = 0.7f), maxLines = 1)
            Text(s?.title?.ifEmpty { null } ?: "Silenzio", style = CanoneType.title2.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 1, modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500))
            Text(s?.artist?.ifEmpty { null } ?: " ", style = CanoneType.body, color = Color.White.copy(alpha = 0.72f), maxLines = 1, modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500))
            Spacer(Modifier.height(14.dp))

            var seek by remember(sel) { mutableStateOf<Float?>(null) }
            val dur = s?.dur ?: 0L
            val frac = seek ?: if (dur > 0) (s!!.pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            WhiteTrack(frac, { if (dur > 0) seek = it }, { f -> if (dur > 0) cmd(sel, "seek", (f * dur).toLong()); seek = null })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val ts = CanoneType.footnote.copy(fontFeatureSettings = "tnum")
                RollingText(if (dur > 0) clock((frac * dur).toLong()) else "0:00", ts, Color.White.copy(alpha = 0.65f))
                if (dur > 0) RollingText("−" + clock(dur - (frac * dur).toLong()), ts, Color.White.copy(alpha = 0.65f))
            }
            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                GlassKey(GK.PREV, 40.dp, { cmd(sel, "prev") }, backdrop = glass)
                PlayPauseKey(s?.playing == true, 50.dp, { cmd(sel, if (s?.playing == true) "pause" else "play") }, backdrop = glass)
                GlassKey(GK.NEXT, 40.dp, { cmd(sel, "next") }, backdrop = glass)
            }
            Spacer(Modifier.height(10.dp))

            var vol by remember(sel) { mutableStateOf<Float?>(null) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SpeakerIcon(false, Color.White.copy(alpha = 0.7f), Modifier.size(16.dp))
                WhiteTrack(vol ?: s?.vol ?: 0f, { f -> vol = f; if (sel == me) cmd(sel, "vol", (f * 100).roundToInt().toLong()) }, { f -> cmd(sel, "vol", (f * 100).roundToInt().toLong()); vol = null }, Modifier.weight(1f))
                SpeakerIcon(true, Color.White.copy(alpha = 0.7f), Modifier.size(16.dp))
            }
            Spacer(Modifier.height(6.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                GlassKey(GK.BACK15, 28.dp, { s?.let { cmd(sel, "seek", (it.pos - 15_000).coerceAtLeast(0)) } }, label = "15", backdrop = glass)
                GlassKey(GK.DEVICES, 22.dp, { panel = true }, backdrop = glass)
                GlassKey(GK.FWD15, 28.dp, { s?.let { cmd(sel, "seek", if (it.dur > 0) (it.pos + 15_000).coerceAtMost(it.dur) else it.pos + 15_000) } }, label = "15", backdrop = glass)
            }
        }

        // dispositivi: scheda di vetro come i pannelli dell'app
        val c = Canone.colors
        AnimatedVisibility(panel, Modifier.fillMaxSize(), enter = fadeIn(tween(180)), exit = fadeOut(tween(160))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.30f)).pointerInput(Unit) { detectTapGestures { panel = false } })
        }
        AnimatedVisibility(
            panel, Modifier.align(Alignment.Center),
            enter = scaleIn(spring(0.78f, 260f), initialScale = 0.82f) + fadeIn(tween(160)),
            exit = scaleOut(tween(150), targetScale = 0.9f) + fadeOut(tween(130)),
        ) {
            Box(Modifier.padding(Space.s4).widthIn(max = 420.dp).pointerInput(Unit) { detectTapGestures { } }) {
                GlassTile(Modifier.fillMaxWidth(), Tint.blue, 0, acrylic = true) {
                    Column(Modifier.padding(Space.s5), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                        Text("Dispositivi", style = CanoneType.title3.copy(fontWeight = FontWeight.SemiBold), color = c.labelPrimary)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Una alla volta", style = CanoneType.body, color = c.labelPrimary)
                            GlassSegmented(listOf("No", "Sì"), if (single) 1 else 0, { single = it == 1; r.prefs.singlePlay = single }, Modifier.widthIn(max = 120.dp))
                        }
                        Column(Modifier.heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.45f).dp.coerceAtLeast(160.dp)).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                            ids.forEach { id ->
                                val d = state(id)
                                val on = d?.playing == true
                                val dpic = DevicePics.rememberPic(id)
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(if (id == sel) c.accent.copy(alpha = 0.12f) else Color.Transparent)
                                        .dropClickable({ sel = id; panel = false }, corner = Radius.lg).padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    RoundGlass(42.dp, false, { sel = id; panel = false }, ring = if (on) c.accent else null) {
                                        if (dpic != null) Image(dpic, null, Modifier.fillMaxSize().padding(7.dp), contentScale = ContentScale.Fit) else TabGlyph(1, c.labelSecondary, false, Modifier.size(17.dp))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(name(id), style = CanoneType.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.labelPrimary, maxLines = 1)
                                        Text(d?.title?.ifEmpty { null }?.let { t -> listOf(t, d.artist).filter { it.isNotEmpty() }.joinToString(" · ") } ?: "Fermo",
                                            style = CanoneType.footnote, color = if (on) c.accent else c.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (d?.title?.isNotEmpty() == true) RoundGlass(38.dp, on, { cmd(id, if (on) "pause" else "play") }) { TransportGlyph(if (on) 2 else 1, if (on) Color.White else c.labelPrimary, Modifier.size(14.dp)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
