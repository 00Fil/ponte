package it.ponte.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class ButtonKind { Primary, Secondary, Tertiary, Destructive }
enum class Tone { Neutral, Positive, Caution, Critical, Accent }

/** Scheda: Card di Canone. Raggio 28, margine interno 24, filetto a riposo. */
@Composable
fun CCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Canone.colors
    val shape = RoundedCornerShape(Radius.xl)
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.bgSurface).border(BorderStroke(1.dp, c.lineSeparator), shape).padding(Space.s5),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
        content = content,
    )
}

/**
 * Pulsante di vetro come la barra: velo, riflesso, bordo di luce. Al tocco si gonfia appena e si illumina (poco rimbalzo).
 * Tutto il movimento sta nella fase di disegno: nessuna ricomposizione mentre lo premi.
 */
@Composable
fun CButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: ButtonKind = ButtonKind.Secondary, enabled: Boolean = true, busy: Boolean = false) {
    val c = Canone.colors
    val dark = isDark()
    val fg = when (kind) {
        ButtonKind.Primary -> Color.White
        ButtonKind.Secondary -> c.labelPrimary
        ButtonKind.Tertiary -> c.accent
        ButtonKind.Destructive -> c.criticalOnCritical
    }
    val touch = rememberDropTouch()
    val glass = when (kind) {
        ButtonKind.Primary -> Modifier.drop(dark, fill = Brand.gradient, press = { touch.press.value }, touch = touch)
        ButtonKind.Secondary -> Modifier.drop(dark, press = { touch.press.value }, touch = touch)
        ButtonKind.Destructive -> Modifier.drop(dark, fill = Brush.verticalGradient(listOf(c.critical.copy(alpha = 0.92f), c.critical)), press = { touch.press.value }, touch = touch)
        ButtonKind.Tertiary -> Modifier.clip(CircleShape).drawBehind { drawRect(c.accent.copy(alpha = 0.12f * touch.press.value)) }
    }
    Box(
        modifier.minimumInteractiveComponentSize().height(44.dp).dropGestures(touch, onClick, enabled = enabled && !busy).alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        // il vetro si deforma, l'etichetta resta ferma
        Box(Modifier.matchParentSize().dropDeform(touch).then(glass))
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = CanoneType.bodyMedium, color = fg, modifier = Modifier.padding(horizontal = Space.s5).alpha(if (busy) 0f else 1f), maxLines = 1)
            if (busy) BusyArc(fg, Modifier.size(22.dp))
        }
    }
}

/** Etichetta di stato: sfondo tonale uguale per tutte, il colore sta solo nel pallino. Altezza fissa, centrata. */
@Composable
fun CBadge(text: String, tone: Tone = Tone.Neutral) {
    val c = Canone.colors
    val dot = when (tone) {
        Tone.Neutral -> c.labelDisabled
        Tone.Positive -> c.positive
        Tone.Caution -> c.caution
        Tone.Critical -> c.critical
        Tone.Accent -> c.accent
    }
    Row(
        Modifier.height(26.dp).clip(CircleShape).background(c.bgSunken).padding(horizontal = Space.s3),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(text, style = CanoneType.footnote, color = c.labelSecondary, maxLines = 1)
    }
}

/** Permesso: concesso = goccia verde con la spunta; da concedere = tasto "Concedi". */
@Composable
fun GrantMark(granted: Boolean, onGrant: () -> Unit) {
    val c = Canone.colors
    if (granted) Box(Modifier.size(30.dp).drop(isDark(), tint = c.positive.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(14.dp)) {
            val w = size.width; val h = size.height
            drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.1f, h * 0.55f); lineTo(w * 0.4f, h * 0.85f); lineTo(w * 0.92f, h * 0.18f) }, c.positive,
                style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.16f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    } else CButton("Concedi", onGrant, kind = ButtonKind.Primary)
}

@Composable
fun CSwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Canone.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = CanoneType.body, color = c.labelPrimary)
            if (subtitle != null) Text(subtitle, style = CanoneType.footnote, color = c.labelSecondary)
        }
        GlassSwitch(checked, onChange)
    }
}

/**
 * Interruttore con pomello di vetro: lo tocchi e il pomello si gonfia e si allunga, lo trascini col dito e si assesta con poco rimbalzo.
 * Pista e pomello si muovono in fase di disegno, senza ricomposizioni.
 */
@Composable
fun GlassSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Canone.colors
    val dark = isDark()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pos = remember { androidx.compose.animation.core.Animatable(if (checked) 1f else 0f) }
    var held by remember { androidx.compose.runtime.mutableStateOf(false) }
    val grow = androidx.compose.animation.core.animateFloatAsState(
        if (held) 1f else 0f, Goccia.press, label = "pomello",
    )
    val settle = spring<Float>(dampingRatio = 0.6f, stiffness = 380f)
    androidx.compose.runtime.LaunchedEffect(checked) { if (!held) pos.animateTo(if (checked) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 380f)) }
    val now by androidx.compose.runtime.rememberUpdatedState(checked)
    val change by androidx.compose.runtime.rememberUpdatedState(onChange)
    val trackW = 54.dp
    val trackH = 32.dp
    val thumb = 26.dp
    val pad = 3.dp
    val on = Brush.verticalGradient(listOf(Brand.sky, Brand.mid, Brand.royal))
    Box(
        modifier.size(trackW, trackH)
            .semantics { role = Role.Switch; toggleableState = androidx.compose.ui.state.ToggleableState(now); onClick { change(!now); true } }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    held = true
                    val travel = (trackW - thumb - pad * 2).toPx()
                    var moved = false
                    val startPos = pos.value
                    drag(down.id) { ch ->
                        ch.consume()
                        val d = (ch.position.x - down.position.x) / travel
                        if (kotlin.math.abs(ch.position.x - down.position.x) > viewConfiguration.touchSlop) moved = true
                        if (moved) scope.launch { pos.snapTo((startPos + d).coerceIn(0f, 1f)) }
                    }
                    held = false
                    val target = if (moved) pos.value > 0.5f else !now
                    if (target != now) change(target)
                    scope.launch { pos.animateTo(if (target) 1f else 0f, settle) }
                }
            },
    ) {
        // pista: grigio di vetro quando è spenta, azzurro lucido quando è accesa, con dissolvenza tra i due
        Box(Modifier.matchParentSize().drop(dark, tint = c.fillControl.copy(alpha = if (dark) 0.55f else 0.65f)))
        Box(
            Modifier.matchParentSize().graphicsLayer { alpha = pos.value.coerceIn(0f, 1f) }.drop(dark, fill = on),
        )
        // pomello: vetro chiaro, si gonfia e si allunga quando lo tieni premuto
        Box(
            Modifier.padding(pad).align(Alignment.CenterStart).size(thumb)
                .graphicsLayer {
                    val travel = (trackW - thumb - pad * 2).toPx()
                    translationX = pos.value * travel
                    val g = grow.value
                    scaleX = 1f + 0.12f * g
                    scaleY = 1f + 0.05f * g
                }
                .drop(dark, tint = Color.White.copy(alpha = if (dark) 0.88f else 0.97f)),
        )
    }
}

/** Riga di lista: titolo, nota, elemento finale. */
@Composable
fun CRow(title: String, note: String? = null, modifier: Modifier = Modifier, mono: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    val c = Canone.colors
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = if (mono) CanoneType.figure else CanoneType.body, color = c.labelPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, style = CanoneType.footnote, color = c.labelSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

@Composable
fun CDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Canone.colors.lineSeparator))
}

/** Campo di testo: etichetta sempre sopra e visibile, segnaposto solo come esempio. */
@Composable
fun CField(label: String, value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, mono: Boolean = false, secret: Boolean = false, number: Boolean = false) {
    val c = Canone.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Text(label, style = CanoneType.footnote, color = c.labelSecondary)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = (if (mono) CanoneType.figure else CanoneType.body).copy(color = c.labelPrimary),
            cursorBrush = SolidColor(c.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else if (number) KeyboardType.Decimal else KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(Radius.md)).background(c.bgSurface)
                        .border(BorderStroke(1.5.dp, c.lineStrong), RoundedCornerShape(Radius.md)).padding(horizontal = Space.s4),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) Text(placeholder, style = if (mono) CanoneType.figure else CanoneType.body, color = c.labelTertiary, maxLines = 1)
                    inner()
                }
            },
        )
    }
}

@Composable
fun CText(text: String, tertiary: Boolean = false, modifier: Modifier = Modifier) {
    val c = Canone.colors
    Text(text, style = CanoneType.footnote, color = if (tertiary) c.labelTertiary else c.labelSecondary, modifier = modifier)
}
