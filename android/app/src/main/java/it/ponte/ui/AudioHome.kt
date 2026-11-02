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

/** Le cuffie: immagine del dispositivo che le ha, batteria, blocco. Compare solo se c'è qualcosa da mostrare. */
@Composable
fun HeadphonesCard(r: Router) {
    val c = Canone.colors
    val ctx = LocalContext.current
    val hps by r.headphones.list.collectAsState()
    val holder by r.headphones.holder.collectAsState()
    val lockedBy by r.headphones.lockedBy.collectAsState()
    val preferred = r.prefs.preferredHp
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(4000); tick++ } }
    val connected = hps.firstOrNull { it.connected && (it.address == preferred || it.address in r.prefs.headphones) } ?: hps.firstOrNull { it.connected }
    if (connected == null && holder == null) return
    val battery = remember(connected?.address, tick) { connected?.let { headphonesBattery(ctx, it.address) } }
    val out = remember(tick, holder) { outputName(ctx) }
    fun who(id: String?) = when (id) { null -> "Libere"; r.deviceId -> "Su questo telefono"; else -> "Su " + r.deviceName(id) }
    val glow by androidx.compose.animation.core.animateFloatAsState(if (holder == r.deviceId) 1f else 0f, androidx.compose.animation.core.tween(500), label = "luce")
    val pic = DevicePics.rememberPic(holder)
    val shape = RoundedCornerShape(Radius.xl)
    Column(Modifier.fillMaxWidth().clip(shape).border(BorderStroke(1.dp, c.lineSeparator), shape)) {
        Column(Modifier.fillMaxWidth().background(c.bgSurface).padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                Box(
                    Modifier.size(92.dp).clip(CircleShape).background(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.10f + 0.25f * glow), c.bgSunken))),
                    contentAlignment = Alignment.Center,
                ) {
                    if (pic != null) Image(pic, null, Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
                    else TabGlyph(4, c.accentOnSubtle, false, Modifier.size(40.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(connected?.name ?: "Cuffie", style = CanoneType.footnote, color = c.labelTertiary, maxLines = 1)
                    Text(who(holder), style = CanoneType.title3, color = c.labelPrimary, maxLines = 2)
                    if (battery != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                        Box(Modifier.size(28.dp, 12.dp).clip(RoundedCornerShape(3.dp)).background(c.bgSunken)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(battery / 100f).background(if (battery < 20) c.critical else c.positive))
                        }
                        RollingText("$battery%", CanoneType.footnote, c.labelSecondary)
                    }
                    if (lockedBy != null) Text("Bloccate " + who(lockedBy).replaceFirstChar { it.lowercase() }, style = CanoneType.footnote, color = c.labelTertiary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                CButton(if (lockedBy == r.deviceId) "Sblocca" else "Tienile qui", { r.headphones.toggleLock() },
                    Modifier.weight(1f), kind = if (lockedBy == r.deviceId) ButtonKind.Secondary else ButtonKind.Primary)
                if (preferred.isNotEmpty() && hps.none { it.address == preferred && it.connected }) {
                    CButton("Portale qui", { r.headphones.connectHere(preferred) }, Modifier.weight(1f))
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(c.bgSunken).padding(horizontal = Space.s4, vertical = Space.s3), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Esce da", style = CanoneType.footnote, color = c.labelTertiary)
            Text(out, style = CanoneType.footnote.copy(fontWeight = FontWeight.Medium), color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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
