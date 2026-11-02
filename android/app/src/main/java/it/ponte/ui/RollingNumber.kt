package it.ponte.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Molla del template CountUp (mode "roll", easing "spring"): sale con un leggero superamento e si assesta. */
private fun springEase(overshoot: Float): Easing {
    val lg = ln(overshoot.coerceIn(0.001f, 0.5f).toDouble())
    val zeta = -lg / sqrt(PI * PI + lg * lg)
    val damped = PI / 0.52
    val natural = damped / sqrt(1 - zeta * zeta)
    fun raw(t: Double) = 1 - exp(-zeta * natural * t) * (cos(damped * t) + (zeta * natural / damped) * sin(damped * t))
    val residual = 1 - raw(1.0)
    return Easing { t -> if (t >= 1f) 1f else (raw(t.toDouble()) + residual * t * t * t).toFloat() }
}
private val SPRING = springEase(0.06f)
private fun mod10(v: Int) = ((v % 10) + 10) % 10

/** Un solo "tic" ogni tanto anche se rotolano più colonne insieme: la vibrazione segue l'andamento senza diventare un ronzio. */
private object Tick { @Volatile var last = 0L }

/**
 * Numero che "rotola" cifra per cifra, come il CountUp in modalità roll: all'apertura il valore è già fermo,
 * quando cambia le cifre cambiate girano verso il nuovo valore (su se sale, giù se scende) con la molla del template,
 * scaglionate da sinistra. Con [haptic] ogni scatto di cifra dà un tic leggero.
 */
@Composable
fun RollingText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, haptic: Boolean = false) {
    // riga mai più bassa del carattere: con cifre grandi (48–52 sp) la colonna non taglia più il numero
    val minLh = style.fontSize.value * 1.22f
    val st = style.copy(fontFeatureSettings = "tnum", lineHeight = if (style.lineHeight.isSpecified && style.lineHeight.value >= minLh) style.lineHeight else minLh.sp)
    // verso: confronto numerico grezzo tra il valore precedente e il nuovo
    val num = remember(text) { text.filter { it.isDigit() || it == '-' }.toLongOrNull() ?: 0L }
    var prev by remember { mutableStateOf(num) }
    val up = num >= prev
    LaunchedEffect(num) { prev = num }
    Row(modifier) {
        text.forEachIndexed { i, ch ->
            val fromRight = text.length - 1 - i
            key(fromRight, ch.isDigit()) {
                if (ch.isDigit()) RollDigit(ch.digitToInt(), st, color, index = i, up = up, haptic = haptic)
                else Text(ch.toString(), style = st, color = color, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun RollDigit(digit: Int, style: TextStyle, color: Color, index: Int, up: Boolean, haptic: Boolean) {
    val pos = remember { Animatable(digit.toFloat()) }
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(digit) {
        val cur = pos.value.roundToInt()
        if (mod10(cur) == digit) return@LaunchedEffect
        val delta = if (up) mod10(digit - mod10(cur)) else -mod10(mod10(cur) - digit)
        pos.animateTo((cur + delta).toFloat(), tween(380, delayMillis = minOf(index, 4) * 28, easing = FastOutSlowInEasing))
    }
    if (haptic) LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { floor(pos.value).toInt() }.collect {
            val now = System.currentTimeMillis()
            if (now - Tick.last > 38) { Tick.last = now; view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
        }
    }
    val lh = with(LocalDensity.current) { style.lineHeight.toDp() }
    val lhPx = with(LocalDensity.current) { style.lineHeight.toPx() }
    val p = pos.value
    val base = floor(p).toInt()
    val frac = p - base
    Box(Modifier.height(lh).clipToBounds()) {
        Text("0", style = style, color = color, modifier = Modifier.alpha(0f), maxLines = 1)
        Text(mod10(base).toString(), style = style, color = color, maxLines = 1, modifier = Modifier.graphicsLayer { translationY = -frac * lhPx })
        if (frac > 0.001f) Text(mod10(base + 1).toString(), style = style, color = color, maxLines = 1, modifier = Modifier.graphicsLayer { translationY = (1f - frac) * lhPx })
    }
}


/**
 * Morphing: il testo nuovo sostituisce il vecchio con una dissolvenza sfocata e un leggero ingrandimento, senza cifre che girano.
 * Per i numeri grandi e isolati (il saldo), dove il rotolo risulterebbe lungo e confuso.
 */
@Composable
fun MorphText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, haptic: Boolean = false) {
    val st = style.copy(fontFeatureSettings = "tnum", lineHeight = maxOf(if (style.lineHeight.isSpecified) style.lineHeight.value else 0f, style.fontSize.value * 1.22f).sp)
    val view = androidx.compose.ui.platform.LocalView.current
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(text) {
        if (first) first = false
        else if (haptic) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }
    androidx.compose.animation.AnimatedContent(
        text, modifier,
        transitionSpec = {
            (androidx.compose.animation.fadeIn(tween(260, 60)) + androidx.compose.animation.scaleIn(tween(260, 60), initialScale = 0.9f))
                .togetherWith(androidx.compose.animation.fadeOut(tween(160)) + androidx.compose.animation.scaleOut(tween(160), targetScale = 1.08f))
                .using(androidx.compose.animation.SizeTransform(clip = false))
        },
        label = "morph",
    ) { t ->
        val b by transition.animateFloat({ tween(240) }, label = "sfoca") { if (it == androidx.compose.animation.EnterExitState.Visible) 0f else 10f }
        Text(t, style = st, color = color, maxLines = 1, softWrap = false, modifier = Modifier.then(if (b > 0.3f) Modifier.blur(b.dp) else Modifier))
    }
}
