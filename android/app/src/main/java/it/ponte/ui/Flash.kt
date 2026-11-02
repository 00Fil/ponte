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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.composed
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Importi nascosti: uno stato solo per Home e Finanze, salvato nelle preferenze. */
object MoneyPrivacy {
    var hidden by mutableStateOf(false)
    const val MASK = "€ ••••"
    const val BLUR = 12
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
            delay(450)
            shown = e.before + e.delta
            delay(1100)
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


/**
 * Importi nascosti: la sfocatura è proporzionata all'altezza del dato (cifre grandi = più sfocatura) e si dissolve piano piano
 * dentro e fuori. Il contenuto viene registrato su un livello PIÙ GRANDE del testo, così la sfocatura esce oltre le cifre
 * e sfuma verso il nulla: nessun bordo, nessun taglio. Dalla sfocatura si passa gradualmente alla vista nitida.
 */
fun Modifier.blurIf(on: Boolean): Modifier = composed {
    val v by androidx.compose.animation.core.animateFloatAsState(
        if (on) 1f else 0f, androidx.compose.animation.core.tween(650, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "censura",
    )
    val blurLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val sharpLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    if (v < 0.001f) this else this.drawWithContent {
        if (android.os.Build.VERSION.SDK_INT < 31) {
            drawContent()
            return@drawWithContent
        }
        val r = (size.height * 0.30f).coerceIn(5.dp.toPx(), 16.dp.toPx())
        val m = (r * 3.2f).toInt()
        val w = size.width.toInt(); val h = size.height.toInt()
        blurLayer.record(size = androidx.compose.ui.unit.IntSize(w + 2 * m, h + 2 * m)) {
            translate(m.toFloat(), m.toFloat()) { this@drawWithContent.drawContent() }
        }
        blurLayer.renderEffect = androidx.compose.ui.graphics.BlurEffect(r * v + 0.01f, r * v + 0.01f, androidx.compose.ui.graphics.TileMode.Decal)
        blurLayer.alpha = v.coerceIn(0f, 1f)
        if (v < 1f) {
            sharpLayer.record { this@drawWithContent.drawContent() }
            sharpLayer.alpha = (1f - v * 1.15f).coerceIn(0f, 1f)
            drawLayer(sharpLayer)
        }
        translate(-m.toFloat(), -m.toFloat()) { drawLayer(blurLayer) }
    }
}
