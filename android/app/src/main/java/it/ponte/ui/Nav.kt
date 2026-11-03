package it.ponte.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Struttura comune di ogni modulo: una dashboard iniziale con riquadri e tasti che portano alle funzioni, e sotto-pagine con "‹ Modulo" per tornare.
 * La pagina attiva (null = dashboard) vive nella shell, così anche la Home può aprire direttamente una funzione.
 */
@Composable
fun <T> ModuleSwitch(state: T?, content: @Composable (T?) -> Unit) {
    AnimatedContent(
        state,
        transitionSpec = {
            val into = targetState != null
            (fadeIn(tween(260)) + slideInHorizontally(spring(1f, 380f)) { if (into) it / 6 else -it / 6 }) togetherWith
                (fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { if (into) -it / 8 else it / 8 })
        },
        label = "modulo",
    ) { content(it) }
}

@Composable
fun BackRow(parent: String, title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        CButton("‹  $parent", onBack, kind = ButtonKind.Tertiary)
        Text(title, style = CanoneType.title3, color = Canone.colors.labelPrimary)
    }
}

/** Riquadro della dashboard, compatto: bollino tonale con icona, titolo, cifra che rotola e una nota. Tocco = apre la funzione. */
@Composable
fun NavTile(icon: Int, title: String, value: String, note: String, tint: List<Color>, modifier: Modifier, onClick: () -> Unit) {
    val c = Canone.colors
    val shape = RoundedCornerShape(Radius.xl)
    Row(
        modifier.height(76.dp).clip(shape).glass(shape)
            .dropClickable(onClick).padding(horizontal = Space.s3),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(c.accentSubtle), contentAlignment = Alignment.Center) {
            TabGlyph(icon, c.accentOnSubtle, false, Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = CanoneType.footnote, color = c.labelTertiary, maxLines = 1)
            RollingText(value, CanoneType.title3, c.labelPrimary)
            Text(note, style = CanoneType.footnote.copy(fontSize = 11.sp), color = c.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

val TINT_A = listOf(Color(0xFF2F5FFF), Color(0xFF6FB4FF))
val TINT_B = listOf(Color(0xFF1B34D6), Color(0xFF3F86FF))
val TINT_C = listOf(Color(0xFF3F86FF), Color(0xFF7DB4FF))
val TINT_D = listOf(Color(0xFF5B7BFF), Color(0xFF2F5FFF))

/** Tasto tondo della dashboard: una goccia con simbolo (o icona) e nome sotto, stessa fisica degli altri controlli. */
@Composable
fun Action(symbol: String, label: String, accent: Boolean, modifier: Modifier = Modifier, glyph: Int? = null, state: Int = 0, onClick: () -> Unit) {
    val c = Canone.colors
    val dark = isDark()
    val t = rememberDropTouch()
    OrbitGlow(t, state == 1)
    Column(modifier.dropGestures(t, onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Box(
            Modifier.size(58.dp).onSizeChanged { t.center = Offset(it.width / 2f, it.height / 2f); t.half = minOf(it.width, it.height) / 2f }.dropDeform(t).drop(dark, tint = if (accent) c.accent.copy(alpha = if (dark) 0.38f else 0.26f) else Color.Unspecified, press = { t.press.value }, touch = t),
            contentAlignment = Alignment.Center,
        ) {
            val fg = if (accent) c.accentOnSubtle else c.labelPrimary
            when (state) {
                1 -> if (glyph != null) TabGlyph(glyph, fg, false, Modifier.size(24.dp))
                2 -> Icon24(Ico.CHECK, c.positive, Modifier.size(26.dp))
                else -> if (glyph != null) TabGlyph(glyph, fg, false, Modifier.size(24.dp)) else Text(symbol, style = CanoneType.title2, color = fg)
            }
        }
        Text(label, style = CanoneType.footnote, color = c.labelSecondary, maxLines = 1)
    }
}
