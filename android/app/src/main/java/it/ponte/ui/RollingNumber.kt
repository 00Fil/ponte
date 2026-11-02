package it.ponte.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
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

/**
 * Numero che "rotola" cifra per cifra, come il CountUp in modalità roll: ogni cifra è una colonna che gira verso il valore, con
 * le cifre di destra che fanno più giri e le colonne che partono scaglionate da sinistra. Parte quando il numero entra nello schermo
 * (una pagina fuori vista non lo consuma) e a ogni cambio di valore le sole cifre cambiate rotolano di nuovo.
 */
@Composable
fun RollingText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, durationMs: Int = 1800) {
    val density = LocalDensity.current
    val screenPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    var seen by remember { mutableStateOf(false) }
    val st = style.copy(fontFeatureSettings = "tnum", lineHeight = if (style.lineHeight.isSpecified) style.lineHeight else (style.fontSize.value * 1.25f).sp)
    Row(modifier.onGloballyPositioned { if (!seen) { val b = it.boundsInWindow(); if (b.right > 0f && b.left < screenPx && b.width > 0f) seen = true } }) {
        text.forEachIndexed { i, ch ->
            val fromRight = text.length - 1 - i
            key(fromRight, ch.isDigit()) {
                if (ch.isDigit()) RollDigit(ch.digitToInt(), st, color, rank = fromRight, index = i, seen = seen, durationMs = durationMs)
                else Text(ch.toString(), style = st, color = color, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun RollDigit(digit: Int, style: TextStyle, color: Color, rank: Int, index: Int, seen: Boolean, durationMs: Int) {
    val pos = remember { Animatable(0f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(digit, seen) {
        if (!seen) return@LaunchedEffect
        if (first) {
            first = false
            val turns = maxOf(1, 3 - rank)
            pos.animateTo((digit + 10 * turns).toFloat(), tween(durationMs, delayMillis = minOf(index, 8) * 70, easing = SPRING))
        } else {
            val cur = pos.value
            val delta = mod10(digit - mod10(cur.roundToInt()))
            if (delta != 0) pos.animateTo(cur.roundToInt() + delta.toFloat(), tween(900, easing = SPRING))
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
        Text(mod10(base + 1).toString(), style = style, color = color, maxLines = 1, modifier = Modifier.graphicsLayer { translationY = (1f - frac) * lhPx })
    }
}
