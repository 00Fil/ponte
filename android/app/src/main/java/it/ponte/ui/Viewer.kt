package it.ponte.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.format.Formatter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import it.ponte.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun decodeLarge(path: String): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    if (o.outWidth <= 0) return null
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 2048) s *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
}

/**
 * Visualizzatore di foto a tutto schermo: scorri di lato tra le immagini, pizzica o tocca due volte per ingrandire,
 * tocca una volta per nascondere i comandi.
 */
@Composable
fun PhotoViewer(items: List<FileEntry>, start: Int, onShare: (FileEntry) -> Unit, onClose: () -> Unit) {
    if (items.isEmpty()) { LaunchedEffect(Unit) { onClose() }; return }
    val ctx = LocalContext.current
    val pager = rememberPagerState(initialPage = start.coerceIn(0, items.lastIndex)) { items.size }
    var zoomed by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(320, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))) }
    val cur = items[pager.currentPage.coerceIn(0, items.lastIndex)]

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.96f * enter.value))) {
            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, key = { items[it].fid }) { i ->
                Zoomable(
                    items[i], page = i == pager.currentPage,
                    onZoom = { zoomed = it }, onTap = { chrome = !chrome },
                    modifier = Modifier.graphicsLayer { val e = enter.value; scaleX = 0.94f + 0.06f * e; scaleY = 0.94f + 0.06f * e },
                )
            }
            val a = if (chrome) 1f else 0f
            Row(
                Modifier.fillMaxWidth().alpha(a).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                    .statusBarsPadding().padding(Space.s4),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${pager.currentPage + 1} di ${items.size}", style = CanoneType.footnote, color = Color.White.copy(alpha = 0.8f))
                Text("Chiudi", style = CanoneType.bodyMedium, color = Color.White, modifier = Modifier.clickable(enabled = chrome, onClick = onClose).padding(Space.s2))
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().alpha(a)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                    .padding(bottom = LocalNavBottom.current).padding(Space.s5),
                verticalArrangement = Arrangement.spacedBy(Space.s3),
            ) {
                Column {
                    Text(cur.name, style = CanoneType.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        Formatter.formatShortFileSize(ctx, cur.size) + " · " + dateTime(cur.ts, ctx),
                        style = CanoneType.footnote, color = Color.White.copy(alpha = 0.65f),
                    )
                }
                CButton("Condividi", { onShare(cur) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = chrome)
            }
        }
    }
}

@Composable
private fun Zoomable(e: FileEntry, page: Boolean, onZoom: (Boolean) -> Unit, onTap: () -> Unit, modifier: Modifier) {
    val bmp by produceState<Bitmap?>(null, e.path) { value = withContext(Dispatchers.IO) { decodeLarge(e.path) } }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(page) { if (!page) { scale = 1f; offset = Offset.Zero } }
    LaunchedEffect(scale, page) { if (page) onZoom(scale > 1.01f) }

    fun clamp(o: Offset, s: Float): Offset {
        val mx = box.width * (s - 1f) / 2f
        val my = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onDoubleTap = {
                if (scale > 1.01f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
            }) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        val fingers = ev.changes.count { it.pressed }
                        if (fingers >= 2 || scale > 1.01f) {
                            val z = ev.calculateZoom()
                            val p = ev.calculatePan()
                            scale = (scale * z).coerceIn(1f, 5f)
                            offset = if (scale <= 1.01f) Offset.Zero else clamp(offset + p, scale)
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                    if (scale < 1.05f) { scale = 1f; offset = Offset.Zero }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let {
            Image(
                it.asImageBitmap(), e.name,
                Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
                    .onSizeChanged { box = it },
                contentScale = ContentScale.Fit,
            )
        }
    }
}
