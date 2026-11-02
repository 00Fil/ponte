package it.ponte.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Colori scelti per eventi e materie (indice salvato nei dati). */
internal val PLAN_COLORS = listOf(
    Color(0xFF2F5FFF), Color(0xFF5DA85B), Color(0xFFE88A2E), Color(0xFF8E5BB5), Color(0xFFD9534F), Color(0xFF2FB5C4), Color(0xFFD9B73A), Color(0xFF9AA19A),
)
internal fun planColor(i: Int) = PLAN_COLORS[i.mod(PLAN_COLORS.size)]

internal fun dayStart(t: Long): Long = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
internal fun addDays(t: Long, n: Int): Long = Calendar.getInstance().apply { timeInMillis = t; add(Calendar.DAY_OF_YEAR, n) }.timeInMillis
internal fun fmt(t: Long, p: String): String = SimpleDateFormat(p, Locale.ITALY).format(Date(t))
internal fun cap(s: String) = s.replaceFirstChar { it.uppercase() }
internal fun dayLabel(t: Long): String {
    val today = dayStart(System.currentTimeMillis()); val d = dayStart(t)
    return when (d) { today -> "Oggi"; addDays(today, 1) -> "Domani"; addDays(today, -1) -> "Ieri"; else -> cap(fmt(d, "EEEE d MMMM")) }
}

/** Pastiglia scorrevole di scelta (stesso stile delle categorie nelle Finanze). */
@Composable
internal fun Chip(text: String, selected: Boolean, tint: Color? = null, onClick: () -> Unit) {
    val c = Canone.colors
    Row(
        Modifier.clip(CircleShape).background(if (selected) (tint ?: c.accent) else c.bgSunken).dropClickable(onClick, corner = 100.dp).padding(horizontal = Space.s3, vertical = Space.s2),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (tint != null && !selected) Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
        Text(text, style = CanoneType.footnote, color = if (selected) Color.White else c.labelSecondary, maxLines = 1)
    }
}

@Composable
internal fun ChipRow(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
internal fun ColorDots(selected: Int, onPick: (Int) -> Unit) {
    val c = Canone.colors
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
        PLAN_COLORS.forEachIndexed { i, col ->
            Box(
                Modifier.size(if (i == selected) 30.dp else 26.dp).clip(CircleShape).background(col).dropClickable({ onPick(i) }, corner = 100.dp),
                contentAlignment = Alignment.Center,
            ) { if (i == selected) Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White)) }
        }
    }
}

internal fun pickDate(ctx: Context, t: Long, onPick: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = t }
    DatePickerDialog(ctx, { _, y, m, d -> onPick(Calendar.getInstance().apply { timeInMillis = t; set(y, m, d) }.timeInMillis) }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}

internal fun pickTime(ctx: Context, t: Long, onPick: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = t }
    TimePickerDialog(ctx, { _, h, m -> onPick(Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis) }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
}

/** Campo che mostra un valore e apre un selettore al tocco. */
@Composable
internal fun PickField(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Canone.colors
    androidx.compose.foundation.layout.Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Text(label, style = CanoneType.footnote, color = c.labelSecondary)
        Box(
            Modifier.fillMaxWidth().height(44.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(Radius.md)).background(c.bgSurface)
                .border(androidx.compose.foundation.BorderStroke(1.5.dp, c.lineStrong), androidx.compose.foundation.shape.RoundedCornerShape(Radius.md)).dropClickable(onClick, corner = Radius.md).padding(horizontal = Space.s4),
            contentAlignment = Alignment.CenterStart,
        ) { Text(value, style = CanoneType.body, color = c.labelPrimary, maxLines = 1) }
    }
}

