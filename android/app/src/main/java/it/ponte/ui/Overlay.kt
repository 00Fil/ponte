package it.ponte.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Pagine e fogli a tutto schermo. Non usano più una finestra Dialog (dove le barre di sistema arrivano a zero e i tasti finivano sotto la barra di stato
 * e quella di navigazione): salgono sopra l'app, nella stessa finestra, e [OverlayHost] li misura con le barre vere.
 */
object Overlays { val items = MutableStateFlow<Map<Any, @Composable () -> Unit>>(emptyMap()) }

@Composable
fun AppOverlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val key = remember { Any() }
    val latest = rememberUpdatedState(content)
    DisposableEffect(key) {
        Overlays.items.update { it + (key to { latest.value() }) }
        onDispose { Overlays.items.update { it - key } }
    }
    BackHandler(onBack = onDismiss)
}
