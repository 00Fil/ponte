package it.ponte.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.dp
import it.ponte.Router
import it.ponte.Tx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.layer.drawLayer
import kotlinx.coroutines.Job
/** Home a widget: impegni e studio, saldo, ultimo screenshot e ultimo copiato in un cubetto, cuffie e audio dei PC. */
@Composable
fun HomeScreen(r: Router, go: (String) -> Unit) {
    val ctx = LocalContext.current
    val clips by r.clipboard.history.collectAsState()
    val files by r.files.items.collectAsState()
    val money by r.finance.state.collectAsState()
    val missing = missingPerms(ctx)

    Box(Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 720.dp).fillMaxHeight(),
            contentPadding = PaddingValues(start = Space.s4, end = Space.s4, top = Space.s2, bottom = 24.dp + LocalBarInset.current),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            if (missing > 0) item { FixWidget(missing) { go("perm") } }
            item { PlanWidgets(r, go) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    PairCube(r, files.firstOrNull { it.mime.startsWith("image/") }, clips.firstOrNull()?.text, go, Modifier.weight(1f).aspectRatio(1f))
                    BalanceCard(r, money.totalCents, Modifier.weight(1f).aspectRatio(1f)) { go("finance") }
                }
            }
            item { HeadphonesCard(r) }
            item { PcAudioCard(r) }
        }
    }
}

/** Contenitore dei widget: stesso bordo e raggio ovunque. */
@Composable
private fun Widget(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val c = Canone.colors
    val shape = RoundedCornerShape(Radius.xl)
    Box(
        modifier.clip(shape).background(c.bgSurface).border(BorderStroke(1.dp, c.lineSeparator), shape)
            .then(if (onClick != null) Modifier.dropClickable(onClick) else Modifier),
    ) { content() }
}

@Composable
private fun FixWidget(n: Int, onClick: () -> Unit) {
    val c = Canone.colors
    Widget(Modifier.fillMaxWidth(), onClick) {
        Row(Modifier.padding(Space.s4), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            Box(Modifier.size(40.dp).drop(isDark(), tint = c.caution.copy(alpha = 0.28f)), contentAlignment = Alignment.Center) { Text("!", style = CanoneType.title3, color = c.labelPrimary) }
            Text(if (n == 1) "Un permesso da concedere" else "$n permessi da concedere", style = CanoneType.body, color = c.labelPrimary, modifier = Modifier.weight(1f))
            Text("›", style = CanoneType.title3, color = c.labelTertiary)
        }
    }
}

/** Il saldo come un riquadro qualsiasi. */
@Composable
private fun BalanceCard(r: Router, total: Long, modifier: Modifier, onClick: () -> Unit) {
    val c = Canone.colors
    Widget(modifier, onClick) {
        Column(Modifier.fillMaxSize().padding(Space.s4), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Saldo", style = CanoneType.footnote, color = c.labelTertiary)
                EyeToggle(r)
            }
            CountUp(total, CanoneType.title2.copy(fontWeight = FontWeight.SemiBold), c.labelPrimary)
        }
    }
}

/**
 * Ultimo screenshot e ultimo copiato, piccoli e vicini in un cubetto. Il tocco copia: quel riquadro si allarga su tutto il cubetto,
 * seppellendo l'altro, e mostra l'anteprima con "Copiato"; poi si ritira. Due tocchi aprono la pagina.
 */
@Composable
private fun PairCube(r: Router, shot: it.ponte.FileEntry?, clip: String?, go: (String) -> Unit, modifier: Modifier) {
    val c = Canone.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = remember { Animatable(0f) }
    var which by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    val open = spring<Float>(1f, 700f)
    val shut = spring<Float>(1f, 600f)
    fun close() { job?.cancel(); job = scope.launch { p.animateTo(0f, shut); which = 0 } }
    fun expand(w: Int) {
        job?.cancel()
        job = scope.launch {
            which = w
            p.animateTo(1f, open)
            delay(1100)
            p.animateTo(0f, shut); which = 0
        }
    }
    BoxWithConstraints(modifier) {
        val w = maxWidth; val hh = maxHeight
        val gap = Space.s2
        val tileH = (hh - gap) / 2
        @Composable
        fun Tile(id: Int, baseY: Dp, onTap: () -> Unit, onDouble: () -> Unit, content: @Composable () -> Unit) {
            val mine = which == id
            val f = if (mine) p.value else 0f
            val shape = RoundedCornerShape(lerp(Radius.lg, Radius.xl, f))
            Box(
                Modifier.offset(y = lerp(baseY, 0.dp, f)).size(w, lerp(tileH, hh, f)).zIndex(if (mine) 2f else 1f)
                    .graphicsLayer { val o = if (!mine && which != 0) p.value else 0f; alpha = 1f - 0.7f * o; scaleX = 1f - 0.05f * o; scaleY = 1f - 0.05f * o }
                    .clip(shape).background(c.bgSurface).border(BorderStroke(1.dp, c.lineSeparator), shape)
                    .dropTaps({ if (mine && p.value > 0.5f) close() else onTap() }, onDouble, corner = Radius.lg),
            ) {
                content()
                if (mine) Box(Modifier.matchParentSize().graphicsLayer { alpha = ((p.value - 0.35f) / 0.5f).coerceIn(0f, 1f) }) {
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.45f))))
                    Row(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = Space.s3).clip(CircleShape).background(c.bgSurface.copy(alpha = 0.92f)).padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon24(Ico.CHECK, c.positive, Modifier.size(16.dp))
                        Text("Copiato", style = CanoneType.footnote.copy(fontWeight = FontWeight.Medium), color = c.labelPrimary, maxLines = 1)
                    }
                }
            }
        }
        Tile(1, 0.dp, {
            if (shot != null) {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newUri(ctx.contentResolver, shot.name, r.files.uriFor(shot)))
                expand(1)
            }
        }, { go("images") }) {
            val bmp by produceState<Bitmap?>(null, shot?.path) { value = shot?.let { withContext(Dispatchers.IO) { thumb(it.path) } } }
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TabGlyph(7, c.labelTertiary, false, Modifier.size(22.dp)) }
        }
        Tile(2, tileH + gap, {
            if (clip != null) { r.clipboard.copyLocal(clip); expand(2) }
        }, { go("clips") }) {
            Box(Modifier.fillMaxSize().padding(Space.s3), contentAlignment = Alignment.CenterStart) {
                if (clip == null) TabGlyph(2, c.labelTertiary, false, Modifier.size(22.dp))
                else Text(clip, style = CanoneType.body, color = c.labelPrimary, maxLines = if (which == 2 && p.value > 0.4f) 7 else 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun thumb(path: String): Bitmap? {
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, o)
    if (o.outWidth <= 0) return null
    var s = 1
    while (o.outWidth / (s * 2) >= 600) s *= 2
    return android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })
}
