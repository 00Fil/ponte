package it.ponte.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.flow.MutableStateFlow

/** Scanner di codici a barre e QR: [open] lo mostra, [result] riceve il codice letto. */
object BodyScan { val open = MutableStateFlow(false); val result = MutableStateFlow<String?>(null) }

private fun Context.owner(): LifecycleOwner? { var c: Context? = this; while (c is ContextWrapper) { if (c is LifecycleOwner) return c; c = c.baseContext }; return null }

@Composable
fun BarcodeScanner(onCode: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    BackHandler { onClose() }
    val cam = remember { LifecycleCameraController(ctx) }
    val scanner = remember { BarcodeScanning.getClient() }
    val owner = remember { ctx.owner() }
    var done by remember { mutableStateOf(false) }
    DisposableEffect(granted) {
        if (granted && owner != null) {
            cam.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            cam.setImageAnalysisAnalyzer(
                ContextCompat.getMainExecutor(ctx),
                MlKitAnalyzer(listOf(scanner), CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED, ContextCompat.getMainExecutor(ctx)) { res ->
                    val code = res?.getValue(scanner)?.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                    if (code != null && !done) { done = true; onCode(code) }
                },
            )
            cam.bindToLifecycle(owner)
        }
        onDispose { cam.clearImageAnalysisAnalyzer(); cam.unbind(); scanner.close() }
    }
    val c = Canone.colors
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) AndroidView({ PreviewView(it).apply { controller = cam; scaleType = PreviewView.ScaleType.FILL_CENTER } }, Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width * 0.78f; val h = w * 0.62f
            val tl = Offset((size.width - w) / 2f, (size.height - h) / 2f)
            drawRoundRect(Color.White, tl, Size(w, h), CornerRadius(22.dp.toPx()), Stroke(3.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth().padding(top = LocalStatusTop.current, start = Space.s4, end = Space.s4).padding(top = Space.s3), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {}
            RoundGlass(44.dp, false, onClose) { Icon24(Ico.CLOSE, Color.White, Modifier.size(18.dp)) }
        }
    }
}
