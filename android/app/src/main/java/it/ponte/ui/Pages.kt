package it.ponte.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import it.ponte.Router

/** Dispositivi: era una scheda, ora è una pagina che si apre dalla Home (la barra in basso ha al massimo 5 schede, come su iOS). */
@Composable
fun DevicesPage(r: Router, onClose: () -> Unit) {
    val c = Canone.colors
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().liquidCanvas(isDark()).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = Space.s5, end = Space.s4, top = Space.s4), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Dispositivi", style = CanoneType.title1, color = c.labelPrimary)
            CButton("Chiudi", onClose, kind = ButtonKind.Tertiary)
        }
        Box(Modifier.weight(1f)) { DevicesScreen(r) }
    }
}
