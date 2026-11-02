package it.ponte.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.ponte.Router

/** Le cuffie: l'immagine del dispositivo dentro l'anello della batteria, il nome e dove sono collegate. Compare solo se ci sono. */
@Composable
fun HeadphonesCard(r: Router) {
    val c = Canone.colors
    val ctx = LocalContext.current
    val hps by r.headphones.list.collectAsState()
    val holder by r.headphones.holder.collectAsState()
    val preferred = r.prefs.preferredHp
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(4000); tick++ } }
    val connected = hps.firstOrNull { it.connected && (it.address == preferred || it.address in r.prefs.headphones) } ?: hps.firstOrNull { it.connected }
    if (connected == null && holder == null) return
    val battery = remember(connected?.address, tick) { connected?.let { headphonesBattery(ctx, it.address) } }
    val pic = DevicePics.rememberPic(holder)
    val level by androidx.compose.animation.core.animateFloatAsState((battery ?: 0) / 100f, androidx.compose.animation.core.spring(1f, 120f), label = "batteria")
    val shape = RoundedCornerShape(Radius.xl)
    val ring = if ((battery ?: 100) < 20) c.critical else c.positive
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.bgSurface).border(BorderStroke(1.dp, c.lineSeparator), shape).padding(Space.s3),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Box(Modifier.size(68.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                val w = 4.dp.toPx()
                val tl = androidx.compose.ui.geometry.Offset(w / 2, w / 2)
                val sz = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
                drawArc(c.bgSunken, 0f, 360f, false, tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
                if (battery != null) drawArc(ring, -90f, 360f * level, false, tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
            Box(Modifier.size(52.dp).clip(CircleShape).background(c.bgSunken), contentAlignment = Alignment.Center) {
                if (pic != null) Image(pic, null, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
                else TabGlyph(4, c.labelSecondary, false, Modifier.size(24.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(connected?.name ?: "Cuffie", style = CanoneType.bodyMedium, color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (holder) { null -> "Libere"; r.deviceId -> "Questo telefono"; else -> r.deviceName(holder ?: "") },
                style = CanoneType.footnote, color = c.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (battery != null) RollingText("$battery%", CanoneType.title3.copy(fontWeight = FontWeight.SemiBold), c.labelPrimary)
    }
}

/** L'audio dei PC collegati: appare solo se un PC sta trasmettendo. */
@Composable
fun PcAudioCard(r: Router) {
    val c = Canone.colors
    val devices by r.devices.collectAsState()
    val active by r.audio.activeSource.collectAsState()
    val sources = devices.filter { "audio-src" in it.caps }
    if (sources.isEmpty()) return
    CCard {
        sources.forEach { d ->
            val dp = DevicePics.rememberPic(d.id)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(c.bgSunken), contentAlignment = Alignment.Center) {
                    if (dp != null) Image(dp, null, Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit) else TabGlyph(1, c.labelTertiary, false, Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(d.name, style = CanoneType.body, color = c.labelPrimary, maxLines = 1)
                    Text(if (active == d.id) "In ascolto qui" else "Disponibile", style = CanoneType.footnote, color = c.labelTertiary)
                }
                if (active == d.id) CButton("Ferma", { r.audio.stopListening(d.id) })
                else CButton("Ascolta", { r.audio.listenTo(d.id) }, kind = ButtonKind.Primary)
            }
        }
    }
}
