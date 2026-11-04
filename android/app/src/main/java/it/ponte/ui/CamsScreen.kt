package it.ponte.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.ponte.Cams
import it.ponte.Router

private fun camState(s: Int) = when (s) { 6 -> "Collegato"; 0 -> "Collegamento…"; 10 -> "Password errata"; -1 -> "Spento"; 2, 3, 11, 12 -> "Non raggiungibile"; 9 -> "Scollegato"; else -> "Stato $s" }

/** Una diretta: fotogramma che arriva dal motore, nero finché non c'è. */
@Composable
fun CamView(ch: Int, modifier: Modifier, fill: Boolean = true) {
    val bmp by Cams.frame(ch).collectAsState()
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = if (fill) ContentScale.Crop else ContentScale.Fit)
        else Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f)))
    }
}

@Composable
private fun CamSetup(r: Router, onDone: () -> Unit) {
    var id by remember { mutableStateOf(r.prefs.camId) }
    var user by remember { mutableStateOf(r.prefs.camUser) }
    var pwd by remember { mutableStateOf(r.prefs.camPwd) }
    Column(Modifier.fillMaxWidth().padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        CField("Cloud ID", id, { id = it }, "6882972254", number = true)
        CField("Utente", user, { user = it }, "admin")
        CField("Password", pwd, { pwd = it }, "", secret = true)
        CButton("Collega", { r.prefs.camId = id; r.prefs.camUser = user; r.prefs.camPwd = pwd; onDone() }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
    }
}

/** Pagina Telecamere: griglia delle dirette (flusso leggero), tocco = schermo intero in alta qualità. */
@Composable
fun CamsScreen(r: Router, onClose: () -> Unit) {
    val c = Canone.colors
    var ready by remember { mutableStateOf(r.prefs.camId.isNotEmpty() && r.prefs.camPwd.isNotEmpty()) }
    var full by remember { mutableIntStateOf(-1) }
    val st by Cams.status.collectAsState()
    val err by Cams.error.collectAsState()
    val n by Cams.channels.collectAsState()
    val diag by Cams.diag.collectAsState()
    LaunchedEffect(ready) { if (ready && Cams.status.value != 6 && Cams.status.value != 0) Cams.connect(r.prefs.camId, r.prefs.camUser, r.prefs.camPwd) }
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(c.bgCanvas)) {
            Column(Modifier.fillMaxSize().padding(top = 48.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s2), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    RoundGlass(40.dp, false, onClose) { Text("‹", style = CanoneType.title2, color = c.labelPrimary) }
                    Column(Modifier.weight(1f)) {
                        Text("Telecamere", style = CanoneType.title3.copy(fontWeight = FontWeight.SemiBold), color = c.labelPrimary)
                        Text(if (err.isNotEmpty()) err else camState(st), style = CanoneType.footnote, color = if (st == 6) c.accent else c.labelTertiary, maxLines = 2)
                        if (diag.isNotEmpty()) Text(diag, style = CanoneType.footnote, color = c.labelTertiary, maxLines = 2)
                    }
                    if (ready) RoundGlass(40.dp, false, { Cams.disconnect(); ready = false }) { Icon24(Ico.PENCIL, c.labelPrimary, Modifier.size(16.dp)) }
                }
                if (!ready) CamSetup(r) { ready = true }
                else LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(Space.s3), horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    items((0 until n).toList()) { ch ->
                        DisposableEffect(ch, st) { if (st == 6) Cams.open(ch, false); onDispose { } }
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(Radius.md)).dropClickable({ full = ch }, corner = Radius.md)) {
                            CamView(ch, Modifier.fillMaxSize())
                            Text("${ch + 1}", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).padding(horizontal = 7.dp, vertical = 2.dp))
                        }
                    }
                }
            }
            if (full >= 0) CamFull(r, full) { Cams.open(full, false); Cams.focus = -1; full = -1 }
        }
    }
    DisposableEffect(Unit) { onDispose { (0 until Cams.channels.value).forEach { Cams.close(it) } } }
}

@Composable
private fun CamFull(r: Router, ch: Int, onBack: () -> Unit) {
    var light by remember { mutableStateOf("smart") }
    LaunchedEffect(ch) { Cams.focus = ch; Cams.open(ch, true) }
    androidx.activity.compose.BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CamView(ch, Modifier.fillMaxSize(), fill = false)
        Row(Modifier.align(Alignment.TopStart).padding(top = 48.dp, start = Space.s4), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            RoundGlass(40.dp, false, onBack) { Text("‹", style = CanoneType.title2, color = Color.White) }
            Text("Telecamera ${ch + 1}", style = CanoneType.bodyMedium, color = Color.White)
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            listOf("smart" to "Auto", "light" to "Luce", "ir" to "IR", "daylight" to "Giorno").forEach { (m, l) ->
                RoundGlass(56.dp, light == m, { light = m; Cams.light(ch, m, r.prefs.camUser, r.prefs.camPwd) }) {
                    Text(l, style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
                }
            }
        }
    }
}

/** Riquadro della Home: la prima telecamera dal vivo. */
@Composable
fun CamsTile(r: Router, modifier: Modifier, index: Int, onOpen: () -> Unit) {
    val st by Cams.status.collectAsState()
    LaunchedEffect(Unit) { if (r.prefs.camId.isNotEmpty() && r.prefs.camPwd.isNotEmpty() && Cams.status.value != 6 && Cams.status.value != 0) Cams.connect(r.prefs.camId, r.prefs.camUser, r.prefs.camPwd) }
    DisposableEffect(st) { if (st == 6) Cams.open(0, false); onDispose { } }
    GlassTile(modifier, Tint.blue, index, onOpen) {
        CamView(0, Modifier.fillMaxSize())
        Row(Modifier.align(Alignment.TopStart).padding(Space.s3).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (st == 6) Color(0xFFFF453A) else Color.White.copy(alpha = 0.5f)))
            Text(if (r.prefs.camId.isEmpty()) "Telecamere" else camState(st), style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
        }
    }
}
