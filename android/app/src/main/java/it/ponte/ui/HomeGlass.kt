package it.ponte.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.ponte.Router
import kotlinx.coroutines.delay

/** Grana: una piastrella di rumore chiara e scura, ripetuta, che toglie il liscio dai gradienti. */
private val NOISE: Brush by lazy {
    val n = 96
    val rnd = kotlin.random.Random(11)
    val px = IntArray(n * n) {
        val v = rnd.nextInt(256)
        val a = rnd.nextInt(10, 70)
        (a shl 24) or (v shl 16) or (v shl 8) or v
    }
    val bmp = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    ShaderBrush(BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT))
}

/** Palette piene dei riquadri: colore pieno, vetro sopra. */
object Tint {
    val sky = listOf(Color(0xFF3E7BFF), Color(0xFF7A4DF0))
    val sun = listOf(Color(0xFFFF9140), Color(0xFFFF3F78))
    val mint = listOf(Color(0xFF12C2A0), Color(0xFF2E7BE8))
    val money = listOf(Color(0xFF16B77A), Color(0xFF0B7A8F))
    val night = listOf(Color(0xFF6A35D6), Color(0xFF12A4E6))
    val rose = listOf(Color(0xFFF0489A), Color(0xFFF59E2B))
    val ocean = listOf(Color(0xFF12A6E6), Color(0xFF5B5BE6))
    val amber = listOf(Color(0xFFF5A623), Color(0xFFE5532D))
}

val OnGlass = Color.White
val OnGlass2 = Color.White.copy(alpha = 0.78f)
val OnGlass3 = Color.White.copy(alpha = 0.60f)

/** Entrata a cascata: ogni riquadro sale e si assesta con una molla, uno dopo l'altro. */
fun Modifier.stagger(index: Int): Modifier = composed {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(index * 70L); a.animateTo(1f, spring(0.78f, 170f)) }
    graphicsLayer {
        val v = a.value
        alpha = v.coerceIn(0f, 1f)
        translationY = (1f - v) * 30.dp.toPx()
        val s = 0.9f + 0.1f * v
        scaleX = s; scaleY = s
    }
}

/**
 * Riquadro di vetro colorato: gradiente pieno con grana, una luce che si sposta piano, e sopra il vetro della Goccia
 * (velo, bordo con dispersione, riflesso). Quadrato o rettangolare lo decide chi lo usa.
 */
@Composable
fun GlassTile(modifier: Modifier, colors: List<Color>, index: Int = 0, onClick: (() -> Unit)? = null, content: @Composable BoxScope.() -> Unit) {
    val dark = isDark()
    val shape = RoundedCornerShape(Radius.xl)
    val t = rememberDropTouch()
    val drift by rememberInfiniteTransition(label = "luce").animateFloat(0f, 1f, infiniteRepeatable(tween(8000 + index * 900, easing = LinearEasing), RepeatMode.Reverse), label = "d")
    val brush = remember(colors, dark) { Brush.linearGradient(colors.map { if (dark) it.copy(alpha = 0.92f) else it }) }
    Box(
        modifier.stagger(index)
            .graphicsLayer { val p = t.press.value; scaleX = 1f - 0.04f * p; scaleY = 1f - 0.04f * p }
            .clip(shape)
            .background(brush)
            .drawBehind {
                val d = drift
                drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.34f), Color.Transparent), center = Offset(size.width * (0.1f + 0.8f * d), size.height * (0.05f + 0.35f * d)), radius = size.maxDimension * 0.75f),
                    radius = size.maxDimension * 0.75f, center = Offset(size.width * (0.1f + 0.8f * d), size.height * (0.05f + 0.35f * d)),
                )
                drawRect(NOISE, alpha = 0.16f)
                val p = t.press.value
                if (p > 0.01f) drawRect(Color.White.copy(alpha = 0.16f * p))
            }
            .drop(dark, corner = Radius.xl)
            .then(if (onClick != null) Modifier.dropGestures(t, onClick) else Modifier),
        content = content,
    )
}

/** Anello di avanzamento su vetro (traccia chiara, arco bianco). */
@Composable
fun GlassRingMark(fraction: Float, size: Dp, stroke: Dp = 5.dp, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val tl = Offset(w / 2, w / 2); val sz = Size(this.size.width - w, this.size.height - w)
            drawArc(Color.White.copy(alpha = 0.24f), 0f, 360f, false, tl, sz, style = Stroke(w))
            drawArc(Color.White, -90f, 360f * fraction.coerceIn(0f, 1f), false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
        }
        content()
    }
}

/** Piccolo tasto bianco traslucido dentro un riquadro. */
@Composable
private fun PillOn(label: String, onClick: () -> Unit) {
    val t = rememberDropTouch()
    Box(
        Modifier.graphicsLayer { val p = t.press.value; scaleX = 1f - 0.06f * p; scaleY = 1f - 0.06f * p }
            .clip(CircleShape).background(Color.White.copy(alpha = 0.26f)).dropGestures(t, onClick).padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = OnGlass, maxLines = 1) }
}

/** Audio: chi sta suonando tra i dispositivi collegati, da dove esce, e il tasto per fermarlo da qui. */
@Composable
fun AudioTile(r: Router, modifier: Modifier, index: Int) {
    val devices by r.devices.collectAsState()
    val playing by r.media.playingNow.collectAsState()
    val holder by r.headphones.holder.collectAsState()
    val active by r.audio.activeSource.collectAsState()
    val me = r.deviceId
    val myName = "Questo telefono"
    // chi suona per primo, poi i PC da cui si può ascoltare
    val rows = buildList {
        add(Triple(me, myName, false))
        devices.forEach { add(Triple(it.id, it.name, "audio-src" in it.caps)) }
    }.sortedByDescending { it.first in playing }.take(3)
    GlassTile(modifier, Tint.night, index) {
        Column(Modifier.fillMaxSize().padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TabGlyph(4, OnGlass, false, Modifier.size(18.dp))
                Text("Audio", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = OnGlass2)
                if (playing.isEmpty()) Text("Silenzio", style = CanoneType.footnote, color = OnGlass3)
            }
            rows.forEach { (id, name, src) ->
                val isPlaying = id in playing
                val title = playing[id].orEmpty()
                val pic = DevicePics.rememberPic(id)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = if (isPlaying) 0.34f else 0.16f)), contentAlignment = Alignment.Center) {
                        if (pic != null) Image(pic, null, Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit) else TabGlyph(1, OnGlass, false, Modifier.size(15.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(name, style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = OnGlass, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = when {
                            isPlaying && title.isNotEmpty() -> title
                            isPlaying -> "In riproduzione"
                            holder == id -> "Cuffie collegate"
                            active == id -> "In ascolto qui"
                            else -> "Fermo"
                        }
                        Text(sub, style = CanoneType.footnote.copy(fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp)), color = OnGlass3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    when {
                        isPlaying -> PillOn("Ferma") { r.media.stopOn(id) }
                        src && active == id -> PillOn("Ferma") { r.audio.stopListening(id) }
                        src -> PillOn("Ascolta") { r.audio.listenTo(id) }
                    }
                }
            }
        }
    }
}

/** Cuffie in un quadrato: batteria nell'anello, nome e dove sono collegate. Solo se ci sono. */
@Composable
fun HeadphonesTile(r: Router, modifier: Modifier, index: Int) {
    val ctx = LocalContext.current
    val hps by r.headphones.list.collectAsState()
    val holder by r.headphones.holder.collectAsState()
    val preferred = r.prefs.preferredHp
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(4000); tick++ } }
    val connected = hps.firstOrNull { it.connected && (it.address == preferred || it.address in r.prefs.headphones) } ?: hps.firstOrNull { it.connected }
    if (connected == null && holder == null) return
    val battery = remember(connected?.address, tick) { connected?.let { headphonesBattery(ctx, it.address) } }
    val pic = DevicePics.rememberPic(holder)
    val level by androidx.compose.animation.core.animateFloatAsState((battery ?: 0) / 100f, spring(1f, 120f), label = "batteria")
    GlassTile(modifier, Tint.rose, index) {
        Column(Modifier.fillMaxSize().padding(Space.s4), verticalArrangement = Arrangement.SpaceBetween) {
            GlassRingMark(if (battery != null) level else 0f, 64.dp) {
                Box(Modifier.size(46.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                    if (pic != null) Image(pic, null, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit) else TabGlyph(4, OnGlass, false, Modifier.size(22.dp))
                }
            }
            Column {
                Text(connected?.name ?: "Cuffie", style = CanoneType.bodyMedium, color = OnGlass, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (battery != null) "$battery% · " else "") + when (holder) { null -> "Libere"; r.deviceId -> "Questo telefono"; else -> r.deviceName(holder ?: "") },
                    style = CanoneType.footnote, color = OnGlass2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Spazio usato nel drive condiviso (50 GB). */
@Composable
fun StorageTile(r: Router, modifier: Modifier, index: Int, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val all by r.files.items.collectAsState()
    val hist by r.clipboard.history.collectAsState()
    val used = all.sumOf { it.size } + hist.sumOf { it.text.length.toLong() }
    val frac = (used.toFloat() / (50L * 1024 * 1024 * 1024)).coerceIn(0f, 1f)
    GlassTile(modifier, Tint.ocean, index, onClick) {
        Column(Modifier.fillMaxSize().padding(Space.s4), verticalArrangement = Arrangement.SpaceBetween) {
            GlassRingMark(maxOf(frac, 0.02f), 64.dp) { TabGlyph(1, OnGlass, false, Modifier.size(24.dp)) }
            Column {
                Text(android.text.format.Formatter.formatShortFileSize(ctx, used), style = CanoneType.title3.copy(fontWeight = FontWeight.SemiBold), color = OnGlass, maxLines = 1)
                Text("di 50 GB", style = CanoneType.footnote, color = OnGlass2, maxLines = 1)
            }
        }
    }
}
