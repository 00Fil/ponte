package it.ponte.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.ponte.Router
import java.util.Calendar

private val Navy = Color(0xFF0E1E8C)
private val Royal = Color(0xFF2E4BFF)
private val Azure = Color(0xFF3F86FF)
private val Sky = Color(0xFF4D9BFF)
private val Ice = Color(0xFF8DBBFF)

/**
 * Pagina iniziale: un saluto con le fibre e quattro riquadri a gradiente nella gamma del blu, ognuno porta alla sua scheda.
 */
@Composable
fun HomeScreen(r: Router, go: (Int) -> Unit) {
    val c = Canone.colors
    val devices by r.devices.collectAsState()
    val hub by r.hubState.collectAsState()
    val clips by r.clipboard.history.collectAsState()
    val files by r.files.items.collectAsState()
    val holder by r.headphones.holder.collectAsState()

    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val hello = when (hour) { in 5..12 -> "Buongiorno"; in 13..17 -> "Buon pomeriggio"; else -> "Buonasera" }
    val who = r.prefs.name.ifBlank { "" }
    val audioNow = when (holder) {
        null -> "Libere"
        r.deviceId -> "Qui"
        else -> devices.firstOrNull { it.id == holder }?.name ?: "Altrove"
    }

    Box(Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 720.dp).fillMaxHeight(),
            contentPadding = PaddingValues(horizontal = Space.s4, vertical = Space.s5),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            item {
                HeroCard(
                    if (who.isEmpty()) hello else "$hello, $who",
                    if (devices.isEmpty()) "Solo questo dispositivo · server ${hub.lowercase()}" else "${devices.size + 1} dispositivi insieme · server ${hub.lowercase()}",
                    height = 208.dp,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    Tile("Dispositivi", "${devices.size + 1}", "collegati", listOf(Royal, Sky), Modifier.weight(1f)) { go(1) }
                    Tile("Appunti", "${clips.size}", "in cronologia", listOf(Navy, Royal), Modifier.weight(1f)) { go(2) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    Tile("Archivio", "${files.size}", "file e screenshot", listOf(Azure, Ice), Modifier.weight(1f), dark = true) { go(3) }
                    Tile("Audio", audioNow, "cuffie su", listOf(Color(0xFF5A63FF), Azure), Modifier.weight(1f)) { go(4) }
                }
            }
            item {
                val last = clips.firstOrNull()
                CCard(Modifier.clickable { go(2) }) {
                    Text("Ultimo appunto", style = CanoneType.title3, color = c.labelPrimary)
                    if (last == null) CText("Niente di copiato per ora. Copia un testo e comparirà qui e sugli altri dispositivi.")
                    else {
                        Text(last.text, style = CanoneType.body, color = c.labelPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (last.mine) "Da questo dispositivo" else "Da ${devices.firstOrNull { it.id == last.from }?.name ?: "un altro dispositivo"}"),
                            style = CanoneType.footnote, color = c.labelTertiary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(title: String, value: String, note: String, colors: List<Color>, modifier: Modifier, dark: Boolean = false, onClick: () -> Unit) {
    val fg = if (dark) Color(0xFF07123F) else Color.White
    Column(
        modifier.height(128.dp).clip(RoundedCornerShape(Radius.xl))
            .background(Brush.linearGradient(colors, start = androidx.compose.ui.geometry.Offset(0f, 0f), end = androidx.compose.ui.geometry.Offset(600f, 600f)))
            .clickable(onClick = onClick).padding(Space.s4),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = CanoneType.footnote, color = fg.copy(alpha = 0.85f))
        Column {
            Text(value, style = CanoneType.title1.copy(fontSize = 34.sp, lineHeight = 38.sp), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(note, style = CanoneType.footnote, color = fg.copy(alpha = 0.8f))
        }
    }
}
