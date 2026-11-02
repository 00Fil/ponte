package it.ponte.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import it.ponte.Router
import java.util.Calendar

/**
 * Home: in alto la fibra ottica con il saluto; poi una griglia ordinata 2×2 (stesse misure, stesso ritmo)
 * dove il colore sta solo nei bollini a gradiente blu; in fondo l'ultimo appunto. Ogni riquadro apre la sua scheda.
 */
@Composable
fun HomeScreen(r: Router, go: (Int) -> Unit) {
    val c = Canone.colors
    val devices by r.devices.collectAsState()
    val hub by r.hubState.collectAsState()
    val clips by r.clipboard.history.collectAsState()
    val files by r.files.items.collectAsState()
    val holder by r.headphones.holder.collectAsState()

    val hello = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) { in 5..12 -> "Buongiorno"; in 13..17 -> "Buon pomeriggio"; else -> "Buonasera" }
    val audio = when (holder) {
        null -> "Libere"
        r.deviceId -> "Qui"
        else -> devices.firstOrNull { it.id == holder }?.name ?: "Altrove"
    }
    val shots = files.count { it.kind == "screenshot" }

    Box(Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 720.dp).fillMaxHeight(),
            contentPadding = PaddingValues(start = Space.s4, end = Space.s4, top = Space.s2, bottom = 24.dp + LocalBarInset.current),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            item {
                HeroCard(
                    hello,
                    when {
                        devices.isEmpty() -> "Solo questo dispositivo per ora"
                        devices.size == 1 -> "Collegato con ${devices[0].name}"
                        else -> "Collegato con ${devices.size} dispositivi"
                    },
                    height = 196.dp,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    Tile(1, "Dispositivi", "${devices.size + 1}", if (hub == "Connesso") "server attivo" else hub.lowercase(),
                        listOf(Color(0xFF2F5FFF), Color(0xFF6FB4FF)), Modifier.weight(1f)) { go(1) }
                    Tile(2, "Appunti", "${clips.size}", "in cronologia",
                        listOf(Color(0xFF1B34D6), Color(0xFF3F86FF)), Modifier.weight(1f)) { go(2) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    Tile(3, "Immagini e file", "${files.size}", if (shots > 0) "$shots screenshot" else "condivisi",
                        listOf(Color(0xFF3F86FF), Color(0xFF7DB4FF)), Modifier.weight(1f)) { go(3) }
                    Tile(4, "Cuffie", audio, "dove suonano ora",
                        listOf(Color(0xFF5B7BFF), Color(0xFF2F5FFF)), Modifier.weight(1f)) { go(4) }
                }
            }
            item {
                val last = clips.firstOrNull()
                CCard(Modifier.dropClickable({ go(2) })) {
                    Text("Ultimo appunto", style = CanoneType.title3, color = c.labelPrimary)
                    if (last == null) {
                        CText("Niente di copiato per ora. Copia un testo e compare qui e sugli altri dispositivi.")
                    } else {
                        Text(last.text, style = CanoneType.body, color = c.labelPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (last.mine) "Da questo dispositivo" else "Da ${devices.firstOrNull { it.id == last.from }?.name ?: "un altro dispositivo"}",
                            style = CanoneType.footnote, color = c.labelTertiary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(icon: Int, title: String, value: String, note: String, tint: List<Color>, modifier: Modifier, onClick: () -> Unit) {
    val c = Canone.colors
    val shape = RoundedCornerShape(Radius.xl)
    Column(
        modifier.height(132.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(tint[0].copy(alpha = 0.10f), c.bgSurface), endY = 300f))
            .border(BorderStroke(1.dp, c.lineSeparator), shape)
            .dropClickable(onClick).padding(Space.s4),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(Brush.linearGradient(tint)), contentAlignment = Alignment.Center) {
                TabGlyph(icon, Color.White, false, Modifier.size(18.dp))
            }
            Text(title, style = CanoneType.footnote, color = c.labelSecondary)
        }
        Column {
            Text(value, style = CanoneType.title2.copy(fontFeatureSettings = "tnum"), color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(note, style = CanoneType.footnote, color = c.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
