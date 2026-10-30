package it.ponte.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.matchParentSize
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
import androidx.compose.ui.graphics.Color
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

/** Pulsante tondo alto 44, area di tocco 48. Uno solo `Primary` per vista. */
@Composable
fun CButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: ButtonKind = ButtonKind.Secondary, enabled: Boolean = true) {
    val c = Canone.colors
    val (bg, fg) = when (kind) {
        ButtonKind.Primary -> c.bgInverse to c.labelInverse
        ButtonKind.Secondary -> c.bgSunken to c.labelPrimary
        ButtonKind.Tertiary -> Color.Transparent to c.accent
        ButtonKind.Destructive -> c.critical to c.criticalOnCritical
    }
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(
        modifier.minimumInteractiveComponentSize().height(44.dp).clip(CircleShape).background(bg).alpha(if (enabled) 1f else 0.4f)
            .clickable(interactionSource = src, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.matchParentSize().background(fg.copy(alpha = if (pressed) 0.12f else 0f)))
        Text(label, style = CanoneType.bodyMedium, color = fg, modifier = Modifier.padding(horizontal = Space.s5), maxLines = 1)
    }
}

@Composable
fun CBadge(text: String, tone: Tone = Tone.Neutral) {
    val c = Canone.colors
    val (bg, fg) = when (tone) {
        Tone.Neutral -> c.bgSunken to c.labelSecondary
        Tone.Positive -> c.positiveSubtle to c.positiveOnSubtle
        Tone.Caution -> c.cautionSubtle to c.cautionOnSubtle
        Tone.Critical -> c.criticalSubtle to c.criticalOnSubtle
        Tone.Accent -> c.accentSubtle to c.accentOnSubtle
    }
    Box(Modifier.clip(CircleShape).background(bg).padding(horizontal = Space.s3, vertical = Space.s1)) {
        Text(text, style = CanoneType.footnote, color = fg, maxLines = 1)
    }
}

@Composable
fun CSwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Canone.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = CanoneType.body, color = c.labelPrimary)
            if (subtitle != null) Text(subtitle, style = CanoneType.footnote, color = c.labelSecondary)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.bgSurface, checkedTrackColor = c.fillSelected, checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = c.bgSurface, uncheckedTrackColor = c.fillControl, uncheckedBorderColor = Color.Transparent,
            ),
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
fun CField(label: String, value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, mono: Boolean = false, secret: Boolean = false) {
    val c = Canone.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Text(label, style = CanoneType.footnote, color = c.labelSecondary)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = (if (mono) CanoneType.figure else CanoneType.body).copy(color = c.labelPrimary),
            cursorBrush = SolidColor(c.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri),
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
