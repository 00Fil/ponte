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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.isSpecified
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

/** Il vetro è neutro: del colore restano solo il blu dell'app e, come dettaglio, un terzo colore caldo. */
object Tint {
    val third = Color(0xFFFFB347)
    val blue = listOf(Color(0xFF3E7BFF), third)
    val sky = blue; val sun = blue; val mint = blue; val money = blue; val night = blue; val rose = blue; val ocean = blue; val amber = blue
}

val OnGlass: Color @Composable get() = Canone.colors.labelPrimary
val OnGlass2: Color @Composable get() = Canone.colors.labelSecondary
val OnGlass3: Color @Composable get() = Canone.colors.labelTertiary

/** Entrata a cascata: ogni riquadro sale e si assesta con una molla, uno dopo l'altro. */
object HomeIntro { var played = false }

fun Modifier.stagger(index: Int): Modifier = composed {
    // solo la prima volta: tornando sulla pagina i riquadri sono già al loro posto
    val a = remember { Animatable(if (HomeIntro.played) 1f else 0f) }
    LaunchedEffect(Unit) { if (a.value < 1f) { delay(index * 70L); a.animateTo(1f, spring(0.78f, 170f)) }; HomeIntro.played = true }
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
fun GlassTile(modifier: Modifier, colors: List<Color>, index: Int = 0, onClick: (() -> Unit)? = null, onLong: (() -> Unit)? = null, acrylic: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val dark = isDark()
    val c = Canone.colors
    val shape = RoundedCornerShape(Radius.xl)
    val t = rememberDropTouch()
    Box(
        modifier.stagger(index)
            .then(if (!dark && !acrylic) Modifier.shadow(14.dp, shape, ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.10f)) else Modifier)
            .graphicsLayer { val p = t.press.value; scaleX = 1f - 0.04f * p; scaleY = 1f - 0.04f * p }
            .clip(shape)
            // profondità leggera: piano che scende di tono verso il basso e filo di luce in alto, come i widget di iOS
            .background(
                if (acrylic) Brush.verticalGradient(listOf(c.bgSurface.copy(alpha = 0.96f), c.bgSurface.copy(alpha = 0.96f)))
                else if (dark) Brush.verticalGradient(listOf(Color(0xFF26262A), Color(0xFF19191C)))
                else Brush.verticalGradient(listOf(Color.White, Color(0xFFF7F7FA))),
            )
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.10f else 0.9f), Color.White.copy(alpha = if (dark) 0.02f else 0.4f))), shape)
            .drawBehind {
                // la luce del tocco, la stessa delle bolle
                val p = t.press.value
                if (p > 0.01f) drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = (if (dark) 0.10f else 0.25f) * p), Color.Transparent), center = if (t.pos.isSpecified) t.pos else center, radius = size.maxDimension * 0.7f))
            }
            .then(if (onClick != null || onLong != null) Modifier.dropGestures(t, onClick ?: {}, onLong) else Modifier),
        content = content,
    )
}

/** Anello di avanzamento su vetro (traccia chiara, arco bianco). */
@Composable
fun GlassRingMark(fraction: Float, size: Dp, stroke: Dp = 5.dp, content: @Composable BoxScope.() -> Unit) {
    val acc = Canone.colors.accent
    val tr = Canone.colors.labelPrimary.copy(alpha = 0.12f)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val tl = Offset(w / 2, w / 2); val sz = Size(this.size.width - w, this.size.height - w)
            drawArc(tr, 0f, 360f, false, tl, sz, style = Stroke(w))
            drawArc(acc, -90f, 360f * fraction.coerceIn(0f, 1f), false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
        }
        content()
    }
}

/** Piccolo tasto bianco traslucido dentro un riquadro. */
@Composable
fun PillOn(label: String, onClick: () -> Unit) {
    val t = rememberDropTouch()
    Box(
        Modifier.graphicsLayer { val p = t.press.value; scaleX = 1f - 0.06f * p; scaleY = 1f - 0.06f * p }
            .clip(CircleShape).background(Canone.colors.accent.copy(alpha = 0.16f)).dropGestures(t, onClick).padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = Canone.colors.accent, maxLines = 1) }
}

/** Elenco dei dispositivi con stato audio e tasto per fermarli (o ascoltarli). */
@Composable
fun AudioRows(r: Router, max: Int) {
    val devices by r.devices.collectAsState()
    val playing by r.media.playingNow.collectAsState()
    val known by r.media.media.collectAsState()
    val holder by r.headphones.holder.collectAsState()
    val active by r.audio.activeSource.collectAsState()
    val me = r.deviceId
    val rows = buildList {
        add(Triple(me, "Questo telefono", false))
        devices.forEach { add(Triple(it.id, it.name, "audio-src" in it.caps)) }
    }.sortedByDescending { it.first in playing }.take(max)
    Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        rows.forEach { (id, name, src) ->
            val isPlaying = id in playing
            val title = playing[id].orEmpty()
            val pic = DevicePics.rememberPic(id)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(Canone.colors.accent.copy(alpha = if (isPlaying) 0.22f else 0.08f)), contentAlignment = Alignment.Center) {
                    if (pic != null) Image(pic, null, Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit) else TabGlyph(1, OnGlass2, false, Modifier.size(15.dp))
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
                    (known[id]?.title ?: "").isNotEmpty() -> PillOn("Riprendi") { r.media.cmd(id, "play") }
                    src && active == id -> PillOn("Ferma") { r.audio.stopListening(id) }
                    src -> PillOn("Ascolta") { r.audio.listenTo(id) }
                }
            }
        }
    }
}

/** Audio: cosa suona e dove, con i comandi; sotto i dispositivi, toccane uno per fermarlo o farlo ripartire. */
@Composable
fun AudioTile(r: Router, modifier: Modifier, index: Int, onClick: () -> Unit, onLong: () -> Unit) {
    val c = Canone.colors
    val dark = isDark()
    val np = rememberNowPlaying()
    val known by r.media.media.collectAsState()
    val devices by r.devices.collectAsState()
    val me = r.deviceId
    val remote = known.values.filter { it.id != me && it.title.isNotEmpty() }.sortedByDescending { it.playing }
    val localOn = np != null && np.playing
    val focusRemote = if (localOn) null else remote.firstOrNull { it.playing } ?: if (np == null) remote.firstOrNull() else null
    val title = focusRemote?.title ?: np?.title.orEmpty()
    val artist = focusRemote?.artist ?: np?.artist.orEmpty()
    val playing = focusRemote?.playing ?: (np?.playing == true)
    val where = focusRemote?.let { r.deviceName(it.id) } ?: "Questo telefono"
    fun send(a: String) {
        val f = focusRemote
        if (f != null) r.media.cmd(f.id, a)
        else np?.controller?.transportControls?.let { t -> when (a) { "play" -> t.play(); "pause" -> t.pause(); "next" -> t.skipToNext(); "prev" -> t.skipToPrevious() } }
    }
    GlassTile(modifier, Tint.blue, index, onClick, onLong) {
        Row(Modifier.fillMaxSize().padding(Space.s3), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            // copertina a tutta altezza
            val shape = RoundedCornerShape(18.dp)
            Box(Modifier.fillMaxHeight().aspectRatio(1f).clip(shape).background(c.bgSunken), contentAlignment = Alignment.Center) {
                val art = if (focusRemote == null) np?.art else null
                if (art != null) Image(art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else {
                    val pic = DevicePics.rememberPic(focusRemote?.id ?: me)
                    if (pic != null && title.isNotEmpty()) Image(pic, null, Modifier.fillMaxSize().padding(18.dp), contentScale = ContentScale.Fit)
                    else Box(Modifier.size(44.dp).drop(dark, tint = c.accent), contentAlignment = Alignment.Center) { TabGlyph(4, Color.White, false, Modifier.size(22.dp)) }
                }
                if (playing) Box(Modifier.align(Alignment.BottomStart).padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 7.dp, vertical = 5.dp)) { EqBars(Color.White) }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = 2.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(where, style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = if (playing) c.accent else OnGlass3, maxLines = 1)
                    Text(title.ifEmpty { "Silenzio" }, style = CanoneType.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = OnGlass, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (artist.isNotEmpty()) Text(artist, style = CanoneType.footnote, color = OnGlass2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (title.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    RoundGlass(38.dp, false, { send("prev") }) { TransportGlyph(0, OnGlass, Modifier.size(15.dp)) }
                    RoundGlass(46.dp, true, { send(if (playing) "pause" else "play") }) { TransportGlyph(if (playing) 2 else 1, Color.White, Modifier.size(18.dp)) }
                    RoundGlass(38.dp, false, { send("next") }) { TransportGlyph(3, OnGlass, Modifier.size(15.dp)) }
                }
                // dispositivi: anello blu se suonano; un tocco li ferma o li fa ripartire
                val list = buildList { add(me); devices.forEach { add(it.id) } }.take(5)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    list.forEach { id ->
                        val on = if (id == me) localOn else known[id]?.playing == true
                        val pic = DevicePics.rememberPic(id)
                        RoundGlass(30.dp, false, {
                            if (id == me) np?.controller?.transportControls?.let { if (on) it.pause() else it.play() }
                            else r.media.cmd(id, if (on) "pause" else "play")
                        }, ring = if (on) c.accent else null) {
                            if (pic != null) Image(pic, null, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit) else TabGlyph(1, OnGlass2, false, Modifier.size(13.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Bolla di vetro rotonda; [solid] = piena di blu. */
@Composable
fun RoundGlass(size: Dp, solid: Boolean, onClick: () -> Unit, ring: Color? = null, content: @Composable BoxScope.() -> Unit) {
    val c = Canone.colors
    val dark = isDark()
    val t = rememberDropTouch()
    Box(
        Modifier.size(size)
            .graphicsLayer { val p = t.press.value; scaleX = 1f + 0.08f * p; scaleY = 1f + 0.08f * p }
            .then(if (ring != null) Modifier.border(2.dp, ring, CircleShape) else Modifier)
            .drop(dark, tint = if (solid) c.accent else Color.Unspecified, press = { t.press.value }, touch = t)
            .clip(CircleShape).dropGestures(t, onClick),
        contentAlignment = Alignment.Center, content = content,
    )
}

/** Tre barrette che ballano: sta suonando. */
@Composable
fun EqBars(color: Color) {
    val inf = androidx.compose.animation.core.rememberInfiniteTransition(label = "eq")
    val a by inf.animateFloat(0.3f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(420), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    val b by inf.animateFloat(1f, 0.35f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(520), androidx.compose.animation.core.RepeatMode.Reverse), label = "b")
    val d by inf.animateFloat(0.5f, 0.9f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(360), androidx.compose.animation.core.RepeatMode.Reverse), label = "d")
    Canvas(Modifier.size(11.dp, 10.dp)) {
        val w = size.width / 5
        listOf(a, b, d).forEachIndexed { i, v -> drawRoundRect(color, Offset(i * 2 * w, size.height * (1 - v)), Size(w, size.height * v), CornerRadius(w / 2)) }
    }
}

/** Simboli dei comandi: 0 indietro, 1 play, 2 pausa, 3 avanti. */
@Composable
fun TransportGlyph(kind: Int, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        fun tri(x0: Float, x1: Float) = androidx.compose.ui.graphics.Path().apply { moveTo(x0, h * 0.18f); lineTo(x1, h / 2); lineTo(x0, h * 0.82f); close() }
        when (kind) {
            1 -> drawPath(tri(w * 0.24f, w * 0.86f), color)
            2 -> { drawRoundRect(color, Offset(w * 0.22f, h * 0.18f), Size(w * 0.2f, h * 0.64f), CornerRadius(w * 0.06f)); drawRoundRect(color, Offset(w * 0.58f, h * 0.18f), Size(w * 0.2f, h * 0.64f), CornerRadius(w * 0.06f)) }
            3 -> { drawPath(tri(w * 0.08f, w * 0.52f), color); drawPath(tri(w * 0.46f, w * 0.90f), color) }
            else -> { scale(-1f, 1f) { drawPath(tri(w * 0.08f, w * 0.52f), color); drawPath(tri(w * 0.46f, w * 0.90f), color) } }
        }
    }
}

/** Cuffie: batteria nell'anello, nome e dove sono collegate. Niente se non ci sono. */
@Composable
fun HeadphonesInfo(r: Router) {
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
        GlassRingMark(if (battery != null) level else 0f, 52.dp, 4.dp) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Canone.colors.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                if (pic != null) Image(pic, null, Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit) else TabGlyph(4, OnGlass2, false, Modifier.size(18.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(connected?.name ?: "Cuffie", style = CanoneType.bodyMedium, color = OnGlass, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (battery != null) "$battery% · " else "") + when (holder) { null -> "Libere"; r.deviceId -> "Questo telefono"; else -> r.deviceName(holder ?: "") },
                style = CanoneType.footnote, color = OnGlass3, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


/** Icona in una piccola bolla di vetro blu, come nelle liste di iOS. */
@Composable
fun IconBubble(glyph: Int) {
    val c = Canone.colors
    Box(Modifier.size(28.dp).drop(isDark(), tint = c.accent.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
        TabGlyph(glyph, Color.White, false, Modifier.size(16.dp))
    }
}
