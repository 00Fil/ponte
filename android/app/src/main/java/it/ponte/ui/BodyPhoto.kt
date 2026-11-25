package it.ponte.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.ExifInterface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import it.ponte.BodyState
import it.ponte.Router
import it.ponte.dayKey
import it.ponte.keyTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Apre la fotocamera del Corpo sopra tutta l'app. */
object BodyCam { val open = MutableStateFlow(false) }

// ---------------------------------------------------------------- peso

/** Peso del giorno: numero grande con passi da 1 kg e da 100 g. */
@Composable
internal fun WeightSheet(initialKg10: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    val c = Canone.colors
    var v by remember { mutableIntStateOf(initialKg10) }
    BodySheet(onDismiss) {
        Text("Peso", style = CanoneType.title3, color = c.labelPrimary)
        WeightStepper(v) { v = it }
        CButton("Salva", { onSave(v) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
    }
}

@Composable
internal fun WeightStepper(kg10: Int, onChange: (Int) -> Unit) {
    val c = Canone.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
        RoundGlass(40.dp, false, { onChange((kg10 - 10).coerceAtLeast(200)) }) { Text("−1", style = CanoneType.footnote, color = c.labelPrimary) }
        RoundGlass(40.dp, false, { onChange((kg10 - 1).coerceAtLeast(200)) }) { Text("−", style = CanoneType.body, color = c.labelPrimary) }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
            RollingText(kg(kg10 / 10f), CanoneType.title1.copy(fontSize = 44.sp, fontWeight = FontWeight.SemiBold), c.labelPrimary)
            Text(" kg", style = CanoneType.body, color = c.labelTertiary, modifier = Modifier.padding(bottom = 10.dp))
        }
        RoundGlass(40.dp, false, { onChange((kg10 + 1).coerceAtMost(3000)) }) { Text("+", style = CanoneType.body, color = c.labelPrimary) }
        RoundGlass(40.dp, false, { onChange((kg10 + 10).coerceAtMost(3000)) }) { Text("+1", style = CanoneType.footnote, color = c.labelPrimary) }
    }
}

// ---------------------------------------------------------------- time lapse

private fun <T> sample(l: List<T>, max: Int): List<T> =
    if (l.size <= max) l else (0 until max).map { l[(it * (l.size - 1).toFloat() / (max - 1)).roundToInt()] }

/** Le foto in sequenza, avanti e indietro in loop con dissolvenza fra una e l'altra. */
@Composable
internal fun TimeLapse(r: Router, days: List<String>, weights: Map<String, Float>, modifier: Modifier) {
    val picks = remember(days) { sample(days, 40) }
    val frames by produceState<List<Pair<String, ImageBitmap>>>(emptyList(), picks) {
        value = withContext(Dispatchers.IO) { picks.mapNotNull { d -> BodyImages.get(r.body.thumbFile(d))?.let { d to it } } }
    }
    val n = frames.size
    val tr = rememberInfiniteTransition(label = "timelapse")
    val t by tr.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween((max(n, 2) * 190).coerceIn(2200, 9000), easing = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)), RepeatMode.Reverse), label = "t",
    )
    val idx by remember(n) { derivedStateOf { if (n < 2) 0 else (t * (n - 1)).roundToInt().coerceIn(0, n - 1) } }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            if (n == 0) return@Canvas
            if (n == 1) { drawCrop(frames[0].second); return@Canvas }
            val pos = t * (n - 1)
            val i = pos.toInt().coerceIn(0, n - 1)
            drawCrop(frames[i].second)
            if (i + 1 < n && pos - i > 0.01f) drawCrop(frames[i + 1].second, pos - i)
        }
        if (n > 0) {
            val d = frames[idx].first
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))).padding(Space.s3)) {
                Text(
                    shortDay(d) + (weights[d]?.let { " · " + kg(it) + " kg" } ?: ""), style = CanoneType.footnote.copy(fontSize = 11.sp, fontFeatureSettings = "tnum"),
                    color = Color.White, maxLines = 1,
                )
            }
        }
    }
}

/** Riquadro della dashboard: il time lapse a sinistra, i numeri della trasformazione a destra. */
@Composable
internal fun TimeLapseCard(r: Router, b: BodyState, photos: List<String>, onOpen: () -> Unit, onShoot: () -> Unit) {
    val c = Canone.colors
    val w = b.weights.associate { it.day to it.kg }
    GlassTile(Modifier.fillMaxWidth(), Tint.blue, 1, onOpen) {
        Row(Modifier.padding(Space.s4), horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
            Box(Modifier.width(150.dp).height(200.dp).clip(RoundedCornerShape(Radius.lg)).background(c.bgSunken)) {
                if (photos.isEmpty()) Box(Modifier.fillMaxSize().dropClickable(onShoot, corner = Radius.lg), contentAlignment = Alignment.Center) {
                    TabGlyph(22, c.labelTertiary, false, Modifier.size(34.dp))
                } else TimeLapse(r, photos, w, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f).height(200.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Trasformazione", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.labelSecondary)
                    if (photos.isNotEmpty()) {
                        val span = ((keyTime(photos.last()) - keyTime(photos.first())) / 86_400_000L).toInt() + 1
                        Text("$span giorni", style = CanoneType.title3, color = c.labelPrimary)
                    }
                }
                if (photos.isNotEmpty()) {
                    val a = w[photos.first()]; val z = w[photos.last()]
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                        Stat("Foto", photos.size.toString())
                        Stat("Dal", fmt(keyTime(photos.first()), "d MMM yyyy"))
                        if (a != null && z != null && photos.size > 1) {
                            val d = z - a
                            Stat("Peso", kg(a) + " → " + kg(z) + " kg")
                            Stat("Variazione", (if (d > 0) "+" else if (d < 0) "−" else "") + kg(abs(d)) + " kg", tint = c.accent)
                        }
                    }
                } else Text("Scatta la prima foto", style = CanoneType.body, color = c.labelTertiary)
            }
        }
    }
}

// ---------------------------------------------------------------- galleria

/** Disegna l'immagine intera nel riquadro (senza tagliare). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFit(img: ImageBitmap) {
    val s = min(size.width / img.width, size.height / img.height)
    val w = img.width * s; val h = img.height * s
    drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset(((size.width - w) / 2f).roundToInt(), ((size.height - h) / 2f).roundToInt()), IntSize(w.roundToInt(), h.roundToInt()))
}

@Composable
internal fun GalleryPage(r: Router, b: BodyState, photos: List<String>, onBack: () -> Unit) {
    val c = Canone.colors
    val today = dayKey()
    var range by remember { mutableIntStateOf(1) }
    var open by remember { mutableStateOf<String?>(null) }
    var weightSheet by remember { mutableStateOf(false) }
    val w = b.weights.associate { it.day to it.kg }
    val days = (photos + b.weights.map { it.day }).distinct().sortedDescending()
    val from = if (range == 2) 0L else addDays(System.currentTimeMillis(), if (range == 0) -30 else -90)
    val pts = b.weights.filter { keyTime(it.day) >= from }
    ScreenList("Galleria") {
        item { BackRow("Corpo", "Galleria", onBack) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                CButton(if (today in photos) "Rifai la foto" else "Foto di oggi", { BodyCam.open.value = true }, Modifier.weight(1f), kind = ButtonKind.Primary)
                CButton("Peso", { weightSheet = true }, Modifier.weight(1f))
            }
        }
        item {
            GlassTile(Modifier.fillMaxWidth(), Tint.blue, 0) {
                Column(Modifier.padding(Space.s5), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                    GlassSegmented(listOf("30 giorni", "90 giorni", "Tutto"), range, { range = it })
                    if (pts.size >= 2) {
                        WeightChart(pts, b.goals.goalKg10.takeIf { it > 0 }?.let { it / 10f }, Modifier.fillMaxWidth().height(160.dp))
                        val d = pts.last().kg - pts.first().kg
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                            Stat("Attuale", kg(pts.last().kg) + " kg", Modifier.weight(1f))
                            Stat("Variazione", (if (d > 0) "+" else if (d < 0) "−" else "") + kg(abs(d)) + " kg", Modifier.weight(1f), c.accent)
                            Stat("Minimo", kg(pts.minOf { it.kg }) + " kg", Modifier.weight(1f))
                            Stat("Massimo", kg(pts.maxOf { it.kg }) + " kg", Modifier.weight(1f))
                        }
                    } else Text("Servono due pesate", style = CanoneType.body, color = c.labelTertiary, modifier = Modifier.padding(vertical = Space.s3))
                }
            }
        }
        days.chunked(3).forEach { row ->
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    row.forEach { d ->
                        val shape = RoundedCornerShape(Radius.md)
                        Box(Modifier.weight(1f).aspectRatio(3f / 4f).clip(shape).dropClickable({ open = d }, corner = Radius.md)) {
                            if (d in photos) PhotoThumb(r.body.thumbFile(d), Modifier.fillMaxSize(), 360)
                            else Box(Modifier.fillMaxSize().background(c.bgSunken), contentAlignment = Alignment.Center) { Text(w[d]?.let { kg(it) } ?: "—", style = CanoneType.title3, color = c.labelTertiary) }
                            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))).padding(horizontal = 8.dp, vertical = 6.dp)) {
                                Text(fmt(keyTime(d), "d MMM") + (w[d]?.let { " · " + kg(it) } ?: ""), style = CanoneType.footnote.copy(fontSize = 11.sp, fontFeatureSettings = "tnum"), color = Color.White, maxLines = 1)
                            }
                        }
                    }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
    open?.let { d -> PhotoDetail(r, b, d, photos) { open = null } }
    if (weightSheet) WeightSheet(w[today]?.let { (it * 10).roundToInt() } ?: b.weights.lastOrNull()?.kg10 ?: 700, { weightSheet = false }) { v -> r.body.setWeight(today, v); weightSheet = false }
}

/** Una foto a tutto schermo, con il confronto a cursore contro la prima. */
@Composable
private fun PhotoDetail(r: Router, b: BodyState, day: String, photos: List<String>, onClose: () -> Unit) {
    val c = Canone.colors
    val img = rememberImage(r.body.photoFile(day).takeIf { day in photos }, 1600)
    val first = photos.firstOrNull()?.takeIf { it != day }
    val ref = rememberImage(first?.let { r.body.photoFile(it) }, 1600)
    var compare by remember { mutableStateOf(false) }
    var frac by remember { mutableFloatStateOf(0.5f) }
    var sure by remember { mutableStateOf(false) }
    var weightSheet by remember { mutableStateOf(false) }
    val kgNow = b.weights.firstOrNull { it.day == day }
    val kgFirst = first?.let { f -> b.weights.firstOrNull { it.day == f } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Canvas(
                Modifier.fillMaxSize().padding(top = 96.dp, bottom = 120.dp).pointerInput(compare) {
                    if (compare) detectHorizontalDragGestures { change, _ -> frac = (change.position.x / size.width).coerceIn(0f, 1f) }
                },
            ) {
                if (img != null) drawFit(img)
                if (compare && ref != null) clipRect(0f, 0f, size.width * frac, size.height) { drawFit(ref) }
                if (compare) {
                    drawLine(Color.White, Offset(size.width * frac, 0f), Offset(size.width * frac, size.height), 2.dp.toPx())
                    drawCircle(Color.White, 14.dp.toPx(), Offset(size.width * frac, size.height / 2f))
                    drawCircle(Color.Black, 4.dp.toPx(), Offset(size.width * frac, size.height / 2f))
                }
            }
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(Space.s4), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(cap(fmt(keyTime(day), "EEEE d MMMM yyyy")), style = CanoneType.bodyMedium, color = Color.White)
                    Text(
                        if (compare && first != null) "Prima " + shortDay(first) + (kgFirst?.let { " · " + kg(it.kg) + " kg" } ?: "") else (kgNow?.let { kg(it.kg) + " kg" } ?: ""),
                        style = CanoneType.footnote.copy(fontFeatureSettings = "tnum"), color = Color.White.copy(alpha = 0.7f),
                    )
                }
                RoundGlass(40.dp, false, onClose) { Icon24(Ico.CLOSE, Color.White, Modifier.size(18.dp)) }
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(Space.s4), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                if (first != null && ref != null && img != null) CButton(if (compare) "Foto" else "Confronta", { compare = !compare }, Modifier.weight(1f), kind = ButtonKind.Primary)
                CButton("Peso", { weightSheet = true }, Modifier.weight(1f))
                CButton(if (sure) "Conferma" else "Elimina", {
                    if (sure) { r.body.deletePhoto(day); r.body.clearWeight(day); onClose() } else sure = true
                }, Modifier.weight(1f), kind = ButtonKind.Destructive)
            }
        }
    }
    if (weightSheet) WeightSheet(kgNow?.kg10 ?: b.weights.lastOrNull()?.kg10 ?: 700, { weightSheet = false }) { v -> r.body.setWeight(day, v); weightSheet = false }
}

// ---------------------------------------------------------------- fotocamera

private fun Context.lifecycleOwner(): LifecycleOwner? {
    var c: Context = this
    while (c is ContextWrapper) { if (c is LifecycleOwner) return c; c = c.baseContext }
    return null
}

/** Decodifica lo scatto, lo raddrizza con l'EXIF e lo taglia al centro in 3:4 (1080 × 1440): sempre la stessa inquadratura. */
private fun prepare(file: File): Bitmap? {
    val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, opt)
    var s = 1
    while (max(opt.outWidth, opt.outHeight) / (s * 2) >= 2400) s *= 2
    var bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
    val m = Matrix()
    when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
    }
    if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    // verticale 3:4
    var w = bmp.width; var h = bmp.height
    if (w > h) { // telefono tenuto in orizzontale: ruota per avere sempre la figura in piedi
        bmp = Bitmap.createBitmap(bmp, 0, 0, w, h, Matrix().apply { postRotate(90f) }, true); w = bmp.width; h = bmp.height
    }
    val cw: Int; val ch: Int
    if (w * 4 > h * 3) { ch = h; cw = h * 3 / 4 } else { cw = w; ch = w * 4 / 3 }
    val crop = Bitmap.createBitmap(bmp, (w - cw) / 2, (h - ch) / 2, cw, ch)
    return if (cw > 1080) Bitmap.createScaledBitmap(crop, 1080, 1440, true) else crop
}

/**
 * Fotocamera per la foto fisica del mattino. Per rifare sempre la stessa inquadratura: la foto precedente in trasparenza sopra l'anteprima,
 * una linea di mezzeria, un livello che segna l'inclinazione del telefono (si colora quando è dritto) e il timer per scattare a distanza.
 * Camera, timer e guida si ricordano da una volta all'altra.
 */
@Composable
fun BodyCamera(r: Router, onClose: () -> Unit) {
    val c = Canone.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("body_cam", Context.MODE_PRIVATE) }
    val today = dayKey()
    val photos by r.body.photos.collectAsState()
    val bodyState by r.body.state.collectAsState()
    var granted by remember { mutableStateOf(has(ctx, Manifest.permission.CAMERA)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    var front by remember { mutableStateOf(prefs.getBoolean("front", false)) }
    var timer by remember { mutableIntStateOf(prefs.getInt("timer", 5)) }
    var guide by remember { mutableIntStateOf(prefs.getInt("guide", 0)) }
    var count by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var shot by remember { mutableStateOf<Bitmap?>(null) }
    var kg10 by remember { mutableIntStateOf(bodyState.weights.firstOrNull { it.day == today }?.kg10 ?: bodyState.weights.lastOrNull()?.kg10 ?: 700) }
    BackHandler { if (shot != null) { shot = null } else { onClose() } }

    val cam = remember {
        LifecycleCameraController(ctx).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            previewTargetSize = CameraController.OutputSize(AspectRatio.RATIO_4_3)
            imageCaptureTargetSize = CameraController.OutputSize(AspectRatio.RATIO_4_3)
        }
    }
    val owner = remember { ctx.lifecycleOwner() }
    LaunchedEffect(front) { cam.cameraSelector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA }
    DisposableEffect(granted) {
        if (granted && owner != null) cam.bindToLifecycle(owner)
        onDispose { cam.unbind() }
    }

    // livello: inclinazione laterale e avanti/indietro rispetto al telefono dritto
    var tilt by remember { mutableStateOf(0f to 0f) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var lr = 0f; var lp = 0f
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val roll = Math.toDegrees(atan2(e.values[0].toDouble(), e.values[1].toDouble())).toFloat()
                val pitch = Math.toDegrees(atan2(e.values[2].toDouble(), e.values[1].toDouble())).toFloat()
                lr += 0.2f * (roll - lr); lp += 0.2f * (pitch - lp)
                tilt = lr to lp
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    val (roll, pitch) = tilt
    val level = abs(roll) < 1.5f && abs(pitch) < 2f

    // foto di riferimento: l'ultima, meglio se non è quella di oggi
    val refDay = photos.lastOrNull { it != today } ?: photos.lastOrNull()
    val refImg = rememberImage(refDay?.let { r.body.photoFile(it) }, 1200)

    fun capture() {
        val out = File(ctx.cacheDir, "corpo_scatto.jpg")
        cam.takePicture(
            ImageCapture.OutputFileOptions.Builder(out).build(), ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(res: ImageCapture.OutputFileResults) {
                    scope.launch { shot = withContext(Dispatchers.IO) { prepare(out) }; busy = false }
                }
                override fun onError(e: ImageCaptureException) { busy = false; toast(ctx, "Scatto non riuscito") }
            },
        )
    }
    fun go() {
        if (busy) return
        busy = true
        scope.launch {
            if (timer > 0) { for (i in timer downTo 1) { count = i; delay(1000) }; count = 0 }
            capture()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        // l'anteprima resta 3:4 ma si restringe se lo schermo è basso: i comandi sotto hanno sempre il loro spazio
        val topB = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navB = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val ph = minOf(maxWidth * 4f / 3f, maxHeight - topB - navB - 190.dp).coerceAtLeast(220.dp)
        val pw = ph * 3f / 4f
        Column(Modifier.fillMaxSize().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(pw).height(ph).background(Color(0xFF0B0B0C))) {
                val s = shot
                if (s != null) {
                    val ib = remember(s) { s.asImageBitmap() }
                    Canvas(Modifier.fillMaxSize()) { drawCrop(ib) }
                } else if (granted) {
                    AndroidView(
                        { PreviewView(it).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER; this.controller = cam } },
                        Modifier.fillMaxSize(),
                    )
                    // guide
                    Canvas(Modifier.fillMaxSize()) {
                        val line = Color.White.copy(alpha = 0.35f)
                        val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 12f))
                        drawLine(line, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.dp.toPx(), pathEffect = dash)
                        if (guide == 0 && refImg != null) drawCrop(refImg, 0.4f)
                        if (guide == 0 && refImg == null || guide == 1) {
                            if (guide == 1) for (k in 1..2) {
                                drawLine(line, Offset(size.width * k / 3f, 0f), Offset(size.width * k / 3f, size.height), 1.dp.toPx())
                                drawLine(line, Offset(0f, size.height * k / 3f), Offset(size.width, size.height * k / 3f), 1.dp.toPx())
                            }
                            // sagoma: testa, spalle, busto, gambe
                            val w = size.width; val h = size.height
                            val st = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash)
                            val col = Color.White.copy(alpha = 0.55f)
                            drawCircle(col, w * 0.06f, Offset(w * 0.5f, h * 0.11f), style = st)
                            val p = Path().apply {
                                moveTo(w * 0.47f, h * 0.17f); lineTo(w * 0.47f, h * 0.2f); lineTo(w * 0.30f, h * 0.23f); lineTo(w * 0.24f, h * 0.50f)
                                moveTo(w * 0.53f, h * 0.17f); lineTo(w * 0.53f, h * 0.2f); lineTo(w * 0.70f, h * 0.23f); lineTo(w * 0.76f, h * 0.50f)
                                moveTo(w * 0.34f, h * 0.24f); lineTo(w * 0.36f, h * 0.52f); lineTo(w * 0.38f, h * 0.93f); lineTo(w * 0.48f, h * 0.93f); lineTo(w * 0.5f, h * 0.55f)
                                moveTo(w * 0.66f, h * 0.24f); lineTo(w * 0.64f, h * 0.52f); lineTo(w * 0.62f, h * 0.93f); lineTo(w * 0.52f, h * 0.93f); lineTo(w * 0.5f, h * 0.55f)
                            }
                            drawPath(p, col, style = st)
                        }
                    }
                    // livello
                    Column(Modifier.align(Alignment.TopCenter).padding(top = Space.s4), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Canvas(Modifier.width(120.dp).height(10.dp)) {
                            rotate(-roll, Offset(size.width / 2, size.height / 2)) {
                                drawLine(if (level) c.accent else Tint.third, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 3.dp.toPx(), StrokeCap.Round)
                            }
                        }
                        Text(
                            if (level) "Dritto" else if (abs(pitch) >= 2f) (if (pitch > 0) "Inclina in avanti " else "Inclina indietro ") + abs(pitch).roundToInt() + "°" else "",
                            style = CanoneType.footnote.copy(fontSize = 12.sp), color = if (level) c.accent else Tint.third,
                        )
                    }
                } else {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                        Text("Fotocamera non consentita", style = CanoneType.body, color = Color.White.copy(alpha = 0.7f))
                        CButton("Consenti", { ask.launch(Manifest.permission.CAMERA) }, kind = ButtonKind.Primary)
                    }
                }
                if (count > 0) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    RollingText(count.toString(), CanoneType.title1.copy(fontSize = 120.sp, fontWeight = FontWeight.SemiBold), Color.White)
                }
                Box(Modifier.align(Alignment.TopStart).padding(Space.s3)) {
                    RoundGlass(40.dp, false, { if (shot != null) { shot = null } else { onClose() } }) {
                        Icon24(if (shot != null) Ico.BACK else Ico.CLOSE, Color.White, Modifier.size(18.dp))
                    }
                }
            }
            val s = shot
            if (s == null) {
                // comandi
                Column(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().padding(Space.s4), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                    ChipRow {
                        listOf("Sagoma", "Griglia", "Niente").forEachIndexed { i, t -> Chip(t, guide == i) { guide = i; prefs.edit().putInt("guide", i).apply() } }
                        listOf(0 to "Subito", 5 to "5 s", 10 to "10 s").forEach { (v, t) -> Chip(t, timer == v) { timer = v; prefs.edit().putInt("timer", v).apply() } }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp))
                        Box(
                            Modifier.size(78.dp).clip(CircleShape).background(Color.White.copy(alpha = if (busy) 0.35f else 1f)).dropClickable({ go() }, corner = 39.dp).padding(5.dp)
                                .clip(CircleShape).background(Color.Black).padding(3.dp).clip(CircleShape).background(Color.White.copy(alpha = if (busy) 0.35f else 1f)),
                        )
                        RoundGlass(48.dp, false, { front = !front; prefs.edit().putBoolean("front", front).apply() }) { TabGlyph(22, Color.White, false, Modifier.size(22.dp)) }
                    }
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().padding(Space.s4), verticalArrangement = Arrangement.SpaceEvenly) {
                    WeightStepper(kg10) { kg10 = it }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                        CButton("Rifai", { shot = null }, Modifier.weight(1f))
                        CButton("Salva", {
                            r.body.savePhoto(today, s); r.body.setWeight(today, kg10)
                            toast(ctx, "Salvata"); onClose()
                        }, Modifier.weight(1f), kind = ButtonKind.Primary)
                    }
                }
            }
        }
    }
}
