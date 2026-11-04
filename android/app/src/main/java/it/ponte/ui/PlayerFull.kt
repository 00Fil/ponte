package it.ponte.ui

import android.media.session.PlaybackState
import androidx.compose.animation.AnimatedVisibility
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

// ---------------------------------------------------------------- simboli di vetro

/** Simboli dei comandi: forme piene e smussate, fatte del materiale delle gocce (bianco lattiginoso con riflesso in alto). */
private enum class G { PLAY, PAUSE, PREV, NEXT, DOWN, DEVICES, BACK15, FWD15, ONE }

private fun DrawScope.glassFill(path: Path, round: Float) {
    val fx = PathEffect.cornerPathEffect(round)
    // ombra morbida
    translate(0f, size.height * 0.04f) { drawPath(path, Color.Black.copy(alpha = 0.16f), style = Stroke(round * 0.9f, join = StrokeJoin.Round, pathEffect = fx)) }
    drawPath(path, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.98f), Color.White.copy(alpha = 0.80f))), style = Fill)
    drawPath(path, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.98f), Color.White.copy(alpha = 0.80f))), style = Stroke(round, join = StrokeJoin.Round, pathEffect = fx))
    // riflesso della goccia in alto
    drawPath(path, Brush.verticalGradient(0f to Color.White.copy(alpha = 0.55f), 0.35f to Color.Transparent), style = Stroke(round * 0.35f, join = StrokeJoin.Round, pathEffect = fx))
}

@Composable
private fun GlassGlyph(g: G, size: Dp, modifier: Modifier = Modifier, on: Boolean = false) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val k = w * 0.09f
        fun tri(x0: Float, x1: Float, y0: Float, y1: Float, left: Boolean) = Path().apply {
            if (left) { moveTo(x1, y0); lineTo(x0, (y0 + y1) / 2); lineTo(x1, y1) } else { moveTo(x0, y0); lineTo(x1, (y0 + y1) / 2); lineTo(x0, y1) }
            close()
        }
        when (g) {
            G.PLAY -> glassFill(tri(w * 0.24f, w * 0.84f, h * 0.14f, h * 0.86f, false), k)
            G.PAUSE -> {
                glassFill(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(w * 0.20f, h * 0.14f, w * 0.40f, h * 0.86f, androidx.compose.ui.geometry.CornerRadius(k))) }, k * 0.3f)
                glassFill(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(w * 0.60f, h * 0.14f, w * 0.80f, h * 0.86f, androidx.compose.ui.geometry.CornerRadius(k))) }, k * 0.3f)
            }
            G.NEXT -> { glassFill(tri(w * 0.06f, w * 0.52f, h * 0.20f, h * 0.80f, false), k); glassFill(tri(w * 0.48f, w * 0.94f, h * 0.20f, h * 0.80f, false), k) }
            G.PREV -> { glassFill(tri(w * 0.06f, w * 0.52f, h * 0.20f, h * 0.80f, true), k); glassFill(tri(w * 0.48f, w * 0.94f, h * 0.20f, h * 0.80f, true), k) }
            G.DOWN -> {
                val p = Path().apply { moveTo(w * 0.18f, h * 0.38f); lineTo(w * 0.5f, h * 0.64f); lineTo(w * 0.82f, h * 0.38f) }
                drawPath(p, Color.Black.copy(alpha = 0.18f), style = Stroke(w * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(p, Color.White.copy(alpha = 0.95f), style = Stroke(w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            G.DEVICES -> {
                // cassa: corpo smussato con due coni
                val body = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(w * 0.24f, h * 0.08f, w * 0.76f, h * 0.92f, androidx.compose.ui.geometry.CornerRadius(w * 0.12f))) }
                drawPath(body, Color.White.copy(alpha = if (on) 0.95f else 0.85f), style = Stroke(w * 0.08f))
                drawCircle(Color.White.copy(alpha = 0.9f), w * 0.13f, Offset(w * 0.5f, h * 0.62f), style = Stroke(w * 0.07f))
                drawCircle(Color.White.copy(alpha = 0.9f), w * 0.05f, Offset(w * 0.5f, h * 0.27f))
            }
            G.BACK15, G.FWD15 -> {
                val back = g == G.BACK15
                drawArc(Color.White.copy(alpha = 0.9f), if (back) 200f else -20f, if (back) 260f else -260f, false, Offset(w * 0.12f, h * 0.12f), Size(w * 0.76f, h * 0.76f), style = Stroke(w * 0.08f, cap = StrokeCap.Round))
                val ax = if (back) w * 0.16f else w * 0.84f
                val head = Path().apply { moveTo(ax - (if (back) -w * 0.0f else 0f), h * 0.18f); lineTo(ax + (if (back) w * 0.16f else -w * 0.16f), h * 0.22f); lineTo(ax + (if (back) w * 0.04f else -w * 0.04f), h * 0.36f); close() }
                drawPath(head, Color.White.copy(alpha = 0.9f))
            }
            G.ONE -> {}
        }
    }
}

/** Tasto senza contorno: il simbolo di vetro si gonfia con una molla al tocco. */
@Composable
private fun GlassKey(g: G, size: Dp, onClick: () -> Unit, label: String? = null, on: Boolean = false) {
    val t = rememberDropTouch()
    Box(
        Modifier.size(size + 20.dp).graphicsLayer { val p = t.press.value; scaleX = 1f + 0.14f * p; scaleY = 1f + 0.14f * p }
            .clip(CircleShape).dropGestures(t, onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlassGlyph(g, size, on = on)
        if (label != null) Text(label, style = CanoneType.footnote.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = Color.White.copy(alpha = 0.92f))
    }
}

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
    val edge = remember(art) { edgeColor(art) } ?: Color(0xFF1B2440)
    val top by animateColorAsState(lerp(edge, Color.Black, 0.10f), tween(600), label = "alto")
    val bottom by animateColorAsState(lerp(edge, Color.Black, 0.55f), tween(600), label = "basso")

    var dy by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom)))
            .pointerInput(Unit) { detectTapGestures { } }
            .pointerInput(Unit) { detectVerticalDragGestures(onDragEnd = { if (dy > 160f) onClose(); dy = 0f }, onDragCancel = { dy = 0f }) { _, d -> if (!panel) dy += d } }
            .graphicsLayer { translationY = (dy * 0.4f).coerceAtLeast(0f) },
    ) {
        val coverH = minOf(maxWidth, maxHeight * 0.58f)
        // copertina sfocata dietro tutto: i colori arrivano fino in fondo
        if (art != null) Image(
            art.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer { alpha = 0.35f }.blur(60.dp), contentScale = ContentScale.Crop,
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to bottom.copy(alpha = 0.4f), 1f to bottom.copy(alpha = 0.85f))))

        // copertina infinita: a tutta larghezza, si scioglie nel colore sotto
        Box(Modifier.fillMaxWidth().height(coverH)) {
            when {
                art != null -> Image(art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                pic != null -> Image(pic, null, Modifier.fillMaxSize().padding(72.dp), contentScale = ContentScale.Fit)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.25f), 0.16f to Color.Transparent, 0.62f to Color.Transparent, 1f to top)))
        }

        // in alto: chiudi
        Box(Modifier.statusBarsPadding().padding(start = 8.dp, top = 4.dp)) { GlassKey(G.DOWN, 26.dp, onClose) }

        Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 28.dp)) {
            Spacer(Modifier.height(coverH - 36.dp))
            Text(name(sel), style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp), color = Color.White.copy(alpha = 0.7f), maxLines = 1)
            Text(s?.title?.ifEmpty { null } ?: "Silenzio", style = CanoneType.title2.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s?.artist?.ifEmpty { null } ?: " ", style = CanoneType.title3.copy(fontWeight = FontWeight.Normal), color = Color.White.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))

            var seek by remember(sel) { mutableStateOf<Float?>(null) }
            val dur = s?.dur ?: 0L
            val frac = seek ?: if (dur > 0) (s!!.pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            WhiteTrack(frac, { if (dur > 0) seek = it }, { f -> if (dur > 0) cmd(sel, "seek", (f * dur).toLong()); seek = null })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (dur > 0) clock((frac * dur).toLong()) else "0:00", style = CanoneType.footnote, color = Color.White.copy(alpha = 0.65f))
                Text(if (dur > 0) "−" + clock(dur - (frac * dur).toLong()) else "", style = CanoneType.footnote, color = Color.White.copy(alpha = 0.65f))
            }
            Spacer(Modifier.weight(1f))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                GlassKey(G.PREV, 44.dp, { cmd(sel, "prev") })
                GlassKey(G.PLAY.takeIf { s?.playing != true } ?: G.PAUSE, 52.dp, { cmd(sel, if (s?.playing == true) "pause" else "play") })
                GlassKey(G.NEXT, 44.dp, { cmd(sel, "next") })
            }
            Spacer(Modifier.weight(1f))

            var vol by remember(sel) { mutableStateOf<Float?>(null) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SpeakerIcon(false, Color.White.copy(alpha = 0.7f), Modifier.size(16.dp))
                WhiteTrack(vol ?: s?.vol ?: 0f, { f -> vol = f; if (sel == me) cmd(sel, "vol", (f * 100).roundToInt().toLong()) }, { f -> cmd(sel, "vol", (f * 100).roundToInt().toLong()); vol = null }, Modifier.weight(1f))
                SpeakerIcon(true, Color.White.copy(alpha = 0.7f), Modifier.size(16.dp))
            }
            Spacer(Modifier.weight(0.8f))

            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                GlassKey(G.BACK15, 26.dp, { s?.let { cmd(sel, "seek", (it.pos - 15_000).coerceAtLeast(0)) } }, "15")
                GlassKey(G.DEVICES, 26.dp, { panel = true }, on = panel)
                GlassKey(G.FWD15, 26.dp, { s?.let { cmd(sel, "seek", if (it.dur > 0) (it.pos + 15_000).coerceAtMost(it.dur) else it.pos + 15_000) } }, "15")
            }
        }

        // sezione unica dei dispositivi
        AnimatedVisibility(panel, Modifier.fillMaxSize(), enter = fadeIn(tween(180)), exit = fadeOut(tween(160))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).pointerInput(Unit) { detectTapGestures { panel = false } })
        }
        AnimatedVisibility(
            panel, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(0.85f, 380f)) { it } + fadeIn(tween(160)),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(160)),
        ) {
            val shape = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl)
            Column(
                Modifier.fillMaxWidth().clip(shape).background(lerp(bottom, Color.Black, 0.35f).copy(alpha = 0.96f))
                    .pointerInput(Unit) { detectTapGestures { } }.navigationBarsPadding().padding(top = 18.dp, bottom = 12.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Dispositivi", style = CanoneType.title3.copy(fontWeight = FontWeight.Bold), color = Color.White, modifier = Modifier.weight(1f))
                    val t = rememberDropTouch()
                    Row(
                        Modifier.height(34.dp).clip(CircleShape).background(if (single) Canone.colors.accent else Color.White.copy(alpha = 0.12f))
                            .dropGestures(t, { single = !single; r.prefs.singlePlay = single }).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { Text("Una alla volta", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = Color.White) }
                }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    ids.forEach { id ->
                        val d = state(id)
                        val on = d?.playing == true
                        val dpic = DevicePics.rememberPic(id)
                        Row(
                            Modifier.fillMaxWidth().background(if (id == sel) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                                .dropClickable({ sel = id; panel = false }, corner = 0.dp).padding(horizontal = 24.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)).then(if (on) Modifier.border(2.dp, Canone.colors.accent, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
                                if (dpic != null) Image(dpic, null, Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit) else TabGlyph(1, Color.White, false, Modifier.size(18.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(name(id), style = CanoneType.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1)
                                Text(d?.title?.ifEmpty { null }?.let { t -> listOf(t, d.artist).filter { it.isNotEmpty() }.joinToString(" · ") } ?: "Fermo",
                                    style = CanoneType.footnote, color = if (on) Canone.colors.accent else Color.White.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (on) EqBars(Color.White)
                            if (d?.title?.isNotEmpty() == true) GlassKey(if (on) G.PAUSE else G.PLAY, 20.dp, { cmd(id, if (on) "pause" else "play") })
                        }
                    }
                }
            }
        }
    }
}
