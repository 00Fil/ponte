package it.ponte.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.ponte.Router
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** L'app volante: tieni premuto un riquadro e si apre una scheda di vetro con le impostazioni veloci di quel servizio. */
@Composable
fun QuickPanel(kind: String?, r: Router, go: (String) -> Unit, onDismiss: () -> Unit) {
    var shown by remember { mutableStateOf(kind) }
    if (kind != null) shown = kind
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Canone.colors
    val dark = isDark()
    // il file da inviare
    var fileSt by remember { mutableIntStateOf(0) }
    var clipSt by remember { mutableIntStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "file"
            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            fileSt = 1
            scope.launch {
                try { withContext(Dispatchers.IO) { r.files.sendUri(uri, name, mime, "file") }; fileSt = 2; delay(1000) } catch (_: Exception) { toast(ctx, "Invio non riuscito") }
                fileSt = 0
            }
        }
    }
    fun open(to: String) { onDismiss(); go(to) }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(kind != null, enter = fadeIn(tween(180)), exit = fadeOut(tween(160))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)).pointerInput(Unit) { detectTapGestures { onDismiss() } })
        }
        AnimatedVisibility(
            kind != null, Modifier.align(Alignment.Center),
            enter = scaleIn(spring(0.78f, 260f), initialScale = 0.82f) + fadeIn(tween(160)),
            exit = scaleOut(tween(150), targetScale = 0.9f) + fadeOut(tween(130)),
        ) {
            Box(Modifier.padding(Space.s4).widthIn(max = 420.dp).pointerInput(Unit) { detectTapGestures { } }) {
                GlassTile(Modifier.fillMaxWidth(), Tint.blue, 0) {
                    Column(Modifier.padding(Space.s5).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                        when (shown) {
                            "agenda" -> {
                                val p by r.plan.state.collectAsState()
                                val t = p.timer
                                var tick by remember { mutableStateOf(System.currentTimeMillis()) }
                                LaunchedEffect(t.running) { while (t.running) { tick = System.currentTimeMillis(); delay(500) } }
                                val total = (when (t.phase) { "short" -> p.cfg("short", 5); "long" -> p.cfg("long", 15); else -> p.cfg("focus", 25) }) * 60_000L
                                val left = if (t.running) (t.endAt - tick).coerceIn(0L, total) else total
                                val secs = ((left + 999) / 1000).toInt()
                                PanelTitle("Agenda e studio")
                                RollingText("%02d:%02d".format(secs / 60, secs % 60), CanoneType.title1.copy(fontSize = androidx.compose.ui.unit.TextUnit(52f, androidx.compose.ui.unit.TextUnitType.Sp), fontWeight = FontWeight.Light, fontFeatureSettings = "tnum"), c.labelPrimary)
                                GlassSegmented(listOf("Studio", "Pausa", "Lunga"), when (t.phase) { "short" -> 1; "long" -> 2; else -> 0 }, { r.plan.timerPhase(listOf("focus", "short", "long")[it]) })
                                Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                                    CButton(if (t.running) "Ferma" else "Avvia", { if (t.running) r.plan.timerStop() else r.plan.timerStart(t.subject) }, Modifier.weight(1f), kind = ButtonKind.Primary)
                                    CButton("Agenda", { open("agenda") }, Modifier.weight(1f))
                                }
                            }
                            "share" -> {
                                PanelTitle("Condividi")
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    Action("", "Invia appunti", true, glyph = 2, state = clipSt) {
                                        if (clipSt == 0) {
                                            clipSt = 1
                                            scope.launch {
                                                val ok = r.clipboard.sendCurrent()
                                                delay(700)
                                                if (ok) { clipSt = 2; delay(1000) } else toast(ctx, "Negli appunti non c'è niente")
                                                clipSt = 0
                                            }
                                        }
                                    }
                                    Action("", "Invia file", false, glyph = 7, state = fileSt) { if (fileSt == 0) picker.launch(arrayOf("*/*")) }
                                    Action("", "Backup", false, glyph = 8) { open("backup") }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                                    CButton("Appunti", { open("clips") }, Modifier.weight(1f))
                                    CButton("Immagini", { open("images") }, Modifier.weight(1f))
                                }
                            }
                            "finance" -> {
                                PanelTitle("Finanze")
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Nascondi saldo", style = CanoneType.body, color = c.labelPrimary)
                                    EyeToggle(r)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                                    CButton("Nuova spesa", { open("fin-add") }, Modifier.weight(1f), kind = ButtonKind.Primary)
                                    CButton("Apri", { open("finance") }, Modifier.weight(1f))
                                }
                            }
                            "audio" -> {
                                var auto by remember { mutableStateOf(r.prefs.autoPause) }
                                PanelTitle("Audio")
                                AudioRows(r, 8)
                                HeadphonesInfo(r)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Pausa automatica", style = CanoneType.body, color = c.labelPrimary)
                                    GlassSegmented(listOf("No", "Sì"), if (auto) 1 else 0, { auto = it == 1; r.prefs.autoPause = auto }, Modifier.widthIn(max = 120.dp))
                                }
                                CButton("Impostazioni audio", { open("settings") }, Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelTitle(text: String) {
    Text(text, style = CanoneType.title3.copy(fontWeight = FontWeight.SemiBold), color = Canone.colors.labelPrimary)
}
