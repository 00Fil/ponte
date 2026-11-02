package it.ponte.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Importi nascosti: uno stato solo per Home e Finanze, salvato nelle preferenze. */
object MoneyPrivacy {
    var hidden by mutableStateOf(false)
    const val MASK = "€ ••••"
}

/** Animazione a schermo intero dopo un'entrata o un'uscita: il saldo al centro, sotto l'operazione in verde o rosso, poi il saldo rotola al risultato. */
object Flash {
    class Ev(val before: Long, val delta: Long, val id: Long = System.nanoTime())
    var ev by mutableStateOf<Ev?>(null)
    fun show(before: Long, delta: Long) { if (delta != 0L) ev = Ev(before, delta) }
}

@Composable
fun FlashHost() {
    val c = Canone.colors
    var last by remember { mutableStateOf<Flash.Ev?>(null) }
    Flash.ev?.let { last = it }
    AnimatedVisibility(Flash.ev != null, enter = fadeIn(tween(220)), exit = fadeOut(tween(320))) {
        val e = last ?: return@AnimatedVisibility
        var shown by remember(e.id) { mutableLongStateOf(e.before) }
        LaunchedEffect(e.id) {
            delay(700)
            shown = e.before + e.delta
            delay(2300)
            if (Flash.ev?.id == e.id) Flash.ev = null
        }
        Box(
            Modifier.fillMaxSize().background(c.bgCanvas.copy(alpha = 0.97f))
                .clickable(remember { MutableInteractionSource() }, null) { Flash.ev = null },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(true, enter = scaleIn(spring(0.7f, 300f), initialScale = 0.85f) + fadeIn(), exit = scaleOut() + fadeOut()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    Text("Saldo", style = CanoneType.footnote, color = c.labelTertiary)
                    RollingText(eur(shown), CanoneType.title1.copy(fontSize = 52.sp, fontWeight = FontWeight.SemiBold), c.labelPrimary, haptic = true)
                    Text(
                        (if (e.delta > 0) "+ " else "− ") + eur(kotlin.math.abs(e.delta)),
                        style = CanoneType.title3, color = if (e.delta > 0) c.positive else c.critical,
                    )
                }
            }
        }
    }
}
