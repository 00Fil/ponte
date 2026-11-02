package it.ponte.ui

import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import it.ponte.NotifListener
import kotlin.math.roundToInt

/** Cosa sta suonando ora su questo telefono (serve l'accesso alle notifiche, lo stesso usato per le notifiche sul PC). */
class NowPlaying(val title: String, val artist: String, val app: String, val art: Bitmap?, val playing: Boolean, val controller: MediaController)

@Composable
fun rememberNowPlaying(): NowPlaying? {
    val ctx = LocalContext.current
    var np by remember { mutableStateOf<NowPlaying?>(null) }
    DisposableEffect(Unit) {
        val msm = ctx.getSystemService(MediaSessionManager::class.java)
        val comp = ComponentName(ctx, NotifListener::class.java)
        val main = Handler(Looper.getMainLooper())
        var ctrl: MediaController? = null
        fun refresh() {
            val c = ctrl
            np = c?.metadata?.let { m ->
                val label = try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(c.packageName, 0)).toString() } catch (_: Exception) { c.packageName }
                NowPlaying(
                    m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "", m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "", label,
                    m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART),
                    c.playbackState?.state == PlaybackState.STATE_PLAYING, c,
                )
            }
        }
        val cb = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
            override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
            override fun onSessionDestroyed() { ctrl = null; refresh() }
        }
        fun pick(list: List<MediaController>?) {
            ctrl?.unregisterCallback(cb)
            ctrl = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
            ctrl?.registerCallback(cb, main)
            refresh()
        }
        val l = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }
        try { msm.addOnActiveSessionsChangedListener(l, comp, main); pick(msm.getActiveSessions(comp)) } catch (_: SecurityException) {}
        onDispose {
            try { msm.removeOnActiveSessionsChangedListener(l) } catch (_: Exception) {}
            ctrl?.unregisterCallback(cb)
        }
    }
    return np
}

/** Dove esce l'audio adesso: le cuffie Bluetooth se ci sono, altrimenti l'altoparlante. */
fun outputName(ctx: Context): String {
    val am = ctx.getSystemService(AudioManager::class.java)
    val bt = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }
    return bt?.productName?.toString()?.ifBlank { null } ?: if (bt != null) "Cuffie" else "Altoparlante"
}

/** Batteria delle cuffie collegate (Android la espone solo con un metodo nascosto: se non c'è, null). */
fun headphonesBattery(ctx: Context, address: String): Int? = try {
    val dev = ctx.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
    (dev.javaClass.getMethod("getBatteryLevel").invoke(dev) as Int).takeIf { it in 0..100 }
} catch (_: Exception) { null }

/** Cursore del volume: pista tonale, riempimento accento e pomello-goccia; trascina o tocca. */
@Composable
fun DropSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val c = Canone.colors
    var w by remember { mutableStateOf(1) }
    val d = LocalDensity.current
    Box(
        modifier.fillMaxWidth().height(36.dp).onSizeChanged { w = it.width }
            .pointerInput(Unit) { detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { ch, _ -> ch.consume(); onChange((ch.position.x / size.width).coerceIn(0f, 1f)) } },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(c.bgSunken))
        Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(10.dp).clip(CircleShape).background(c.accent))
        val thumb = with(d) { 28.dp.toPx() }
        Box(Modifier.offset { IntOffset(((w - thumb) * value).roundToInt(), 0) }.size(28.dp).drop(isDark(), tint = Color.White.copy(alpha = 0.9f)))
    }
}

/** Icone dei controlli multimediali, disegnate. kind: 0 indietro, 1 play, 2 pausa, 3 avanti. */
@Composable
fun MediaIcon(kind: Int, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        fun tri(x0: Float, x1: Float) = Path().apply { moveTo(x0, h * 0.2f); lineTo(x1, h * 0.5f); lineTo(x0, h * 0.8f); close() }
        when (kind) {
            1 -> drawPath(tri(w * 0.3f, w * 0.8f), color)
            2 -> { drawRoundRect(color, Offset(w * 0.25f, h * 0.2f), androidx.compose.ui.geometry.Size(w * 0.17f, h * 0.6f), androidx.compose.ui.geometry.CornerRadius(3f)); drawRoundRect(color, Offset(w * 0.58f, h * 0.2f), androidx.compose.ui.geometry.Size(w * 0.17f, h * 0.6f), androidx.compose.ui.geometry.CornerRadius(3f)) }
            3 -> { drawPath(tri(w * 0.15f, w * 0.6f), color); drawRect(color, Offset(w * 0.66f, h * 0.2f), androidx.compose.ui.geometry.Size(w * 0.12f, h * 0.6f)) }
            else -> { drawPath(Path().apply { moveTo(w * 0.85f, h * 0.2f); lineTo(w * 0.4f, h * 0.5f); lineTo(w * 0.85f, h * 0.8f); close() }, color); drawRect(color, Offset(w * 0.22f, h * 0.2f), androidx.compose.ui.geometry.Size(w * 0.12f, h * 0.6f)) }
        }
    }
}


@Composable
fun SpeakerIcon(loud: Boolean, color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width; val h = size.height
        drawPath(Path().apply { moveTo(w * .1f, h * .38f); lineTo(w * .32f, h * .38f); lineTo(w * .56f, h * .18f); lineTo(w * .56f, h * .82f); lineTo(w * .32f, h * .62f); lineTo(w * .1f, h * .62f); close() }, color)
        if (loud) drawArc(color, -42f, 84f, false, Offset(w * .42f, h * .24f), androidx.compose.ui.geometry.Size(w * .46f, h * .52f), style = androidx.compose.ui.graphics.drawscope.Stroke(w * .08f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

private fun clock(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

/**
 * Player in stile iOS, una sola scheda: copertina grande con sfondo sfocato dalla copertina, titolo e artista, avanzamento, comandi
 * e volume in fondo. [footer] aggiunge una riga (dove esce l'audio, ecc.).
 */
@Composable
fun PlayerCard(np: NowPlaying, modifier: Modifier = Modifier, footer: (@Composable () -> Unit)? = null) {
    val c = Canone.colors
    val ctx = LocalContext.current
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(Radius.xl)
    val am = remember { ctx.getSystemService(android.media.AudioManager::class.java) }
    val maxVol = remember { am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var vol by remember { androidx.compose.runtime.mutableIntStateOf(am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1200); vol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) } }
    val dur = np.controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
    var pos by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(np.controller, np.title, np.playing) {
        while (true) {
            val st = np.controller.playbackState
            pos = when {
                st == null -> 0L
                st.state == android.media.session.PlaybackState.STATE_PLAYING -> st.position + ((android.os.SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
                else -> st.position
            }.coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
            kotlinx.coroutines.delay(500)
        }
    }
    val tc = np.controller.transportControls
    Box(modifier.fillMaxWidth().clip(shape).border(androidx.compose.foundation.BorderStroke(1.dp, c.lineSeparator), shape)) {
        Box(Modifier.matchParentSize().background(c.bgSurface))
        np.art?.let {
            androidx.compose.foundation.Image(
                it.asImageBitmap(), null,
                Modifier.matchParentSize().then(if (android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(46.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Rectangle) else Modifier).alpha(0.55f),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
            Box(Modifier.matchParentSize().background(c.bgSurface.copy(alpha = 0.45f)))
        }
        Column(Modifier.fillMaxWidth().padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                Box(Modifier.size(84.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(Radius.lg)).background(c.bgSunken), contentAlignment = Alignment.Center) {
                    np.art?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                        ?: TabGlyph(4, c.labelTertiary, false, Modifier.size(32.dp))
                }
                Column(Modifier.weight(1f)) {
                    androidx.compose.material3.Text(np.title.ifEmpty { "Senza titolo" }, style = CanoneType.callout.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = c.labelPrimary, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    androidx.compose.material3.Text(np.artist, style = CanoneType.body, color = c.labelSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    androidx.compose.material3.Text(np.app, style = CanoneType.footnote, color = c.labelTertiary, maxLines = 1)
                }
            }
            if (dur > 0) Column {
                DropSlider(pos.toFloat() / dur, { f -> pos = (f * dur).toLong(); tc.seekTo(pos) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    androidx.compose.material3.Text(clock(pos), style = CanoneType.footnote, color = c.labelTertiary)
                    androidx.compose.material3.Text("−" + clock(dur - pos), style = CanoneType.footnote, color = c.labelTertiary)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                listOf(0 to { tc.skipToPrevious() }, (if (np.playing) 2 else 1) to { if (np.playing) tc.pause() else tc.play() }, 3 to { tc.skipToNext() }).forEach { (k, act) ->
                    val t = rememberDropTouch()
                    val big = k == 1 || k == 2
                    Box(
                        Modifier.size(if (big) 68.dp else 54.dp).dropGestures(t, act).dropDeform(t)
                            .drop(isDark(), tint = if (big) c.accent.copy(alpha = 0.32f) else Color.Unspecified, press = { t.press.value }, touch = t),
                        contentAlignment = Alignment.Center,
                    ) { MediaIcon(k, c.labelPrimary, Modifier.size(if (big) 26.dp else 20.dp)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                SpeakerIcon(false, c.labelTertiary, Modifier.size(18.dp))
                DropSlider(vol.toFloat() / maxVol, { f ->
                    val v = (f * maxVol).roundToInt().coerceIn(0, maxVol)
                    if (v != vol) { vol = v; am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0) }
                }, Modifier.weight(1f))
                SpeakerIcon(true, c.labelTertiary, Modifier.size(18.dp))
            }
            footer?.invoke()
        }
    }
}
