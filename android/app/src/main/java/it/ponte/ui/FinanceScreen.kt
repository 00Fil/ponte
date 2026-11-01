package it.ponte.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.ponte.FinanceEntry
import it.ponte.Router
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val financeCategories = listOf("Casa", "Cibo", "Trasporti", "Tempo libero", "Salute", "Altro")
private val itLocale = Locale.ITALY
private val financeZone get() = ZoneId.systemDefault()
private fun financeDate(ts: Long) = Instant.ofEpochMilli(ts).atZone(financeZone).toLocalDate()
private fun money(cents: Long): String = NumberFormat.getCurrencyInstance(itLocale).format(cents / 100.0)
private fun moneyInput(cents: Long): String = BigDecimal.valueOf(cents, 2).setScale(2).toPlainString().replace('.', ',')
private fun parseCents(raw: String): Long? = try {
    val value = raw.trim().replace("€", "").replace(" ", "")
    val normalized = if (',' in value) value.replace(".", "").replace(',', '.') else value
    BigDecimal(normalized).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
} catch (_: Exception) { null }

@Composable
fun FinanceScreen(r: Router) {
    val entries by r.finances.entries.collectAsState()
    val now = LocalDate.now()
    val month = YearMonth.from(now)
    val balance = entries.sumOf { it.cents }
    val monthEntries = entries.filter { financeDate(it.ts).let { day -> YearMonth.from(day) == month } }
    val expenses = monthEntries.filter { it.kind == "expense" }.sumOf { -it.cents }
    val income = monthEntries.filter { it.kind == "income" }.sumOf { it.cents }
    var dialogMode by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<FinanceEntry?>(null) }

    ScreenList("Finanze") {
        item {
            CCard {
                CText("SALDO DISPONIBILE", tertiary = true)
                Text(if (entries.isEmpty()) "Saldo non impostato" else money(balance), style = CanoneType.title2, color = Canone.colors.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    CBadge("Entrate ${money(income)}", Tone.Positive)
                    CBadge("Uscite ${money(expenses)}", Tone.Critical)
                }
                CButton("Aggiorna saldo", { dialogMode = "balance" }, Modifier.fillMaxWidth(), ButtonKind.Primary)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    CButton("− Uscita", { dialogMode = "expense" }, Modifier.weight(1f))
                    CButton("+ Entrata", { dialogMode = "income" }, Modifier.weight(1f))
                }
                CText("I dati sono salvati solo su questo dispositivo.", tertiary = true)
            }
        }
        item {
            CCard {
                SectionTitle("Spese giornaliere")
                CText("Heatmap · ultime 26 settimane")
                FinanceHeatmap(entries, now)
                val recentWeek = entries.filter { it.kind == "expense" && financeDate(it.ts) >= now.minusDays(6) }.sumOf { -it.cents }
                val halfYear = entries.filter { it.kind == "expense" && financeDate(it.ts) >= now.with(DayOfWeek.MONDAY).minusWeeks(25) }.sumOf { -it.cents }
                CText("Ultimi 7 giorni ${money(recentWeek)}  ·  periodo ${money(halfYear)}")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CText("Meno")
                    (0..4).forEach { HeatCell(it / 4f, 12.dp, Modifier) }
                    CText("Più spese")
                }
            }
        }
        item {
            CCard {
                SectionTitle("Andamento del saldo")
                CText("Chiusura mensile · ultimi 6 mesi")
                if (entries.isEmpty()) CText("Aggiorna il saldo per iniziare a vedere l'andamento.")
                else {
                    BalanceChart(entries, month)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        (5 downTo 0).forEach { offset ->
                            val label = month.minusMonths(offset.toLong()).month.getDisplayName(java.time.format.TextStyle.SHORT, itLocale).replaceFirstChar { it.uppercase(itLocale) }
                            Text(label, style = CanoneType.footnote, color = Canone.colors.labelTertiary)
                        }
                    }
                }
            }
        }
        item {
            CCard {
                SectionTitle("Uscite per categoria")
                CText(month.month.getDisplayName(java.time.format.TextStyle.FULL, itLocale).replaceFirstChar { it.uppercase(itLocale) })
                val byCategory = monthEntries.filter { it.kind == "expense" }.groupBy { it.category }
                    .mapValues { (_, list) -> list.sumOf { -it.cents } }.toList().sortedByDescending { it.second }
                if (byCategory.isEmpty()) CText("Nessuna uscita registrata questo mese.")
                else {
                    val max = byCategory.first().second.coerceAtLeast(1L)
                    byCategory.forEach { (category, amount) ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            CRow(category, money(amount), mono = true)
                            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Canone.colors.bgSunken)) {
                                Box(Modifier.fillMaxWidth((amount.toFloat() / max).coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(Canone.colors.positive))
                            }
                        }
                    }
                }
            }
        }
        item {
            CCard {
                SectionTitle("Movimenti recenti")
                if (entries.isEmpty()) CText("Registra la prima entrata o uscita per popolare il riepilogo.")
                else entries.sortedByDescending { it.ts }.take(12).forEachIndexed { index, e ->
                    if (index > 0) CDivider()
                    FinanceEntryRow(e) { pendingDelete = e }
                }
            }
        }
    }

    dialogMode?.let { mode ->
        FinanceDialog(mode, balance, onDismiss = { dialogMode = null }) { cents, category, note ->
            when (mode) {
                "balance" -> r.finances.setBalance(cents)
                else -> r.finances.add(mode, cents, category, note)
            }
            dialogMode = null
        }
    }
    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Eliminare il movimento?", style = CanoneType.title3, color = Canone.colors.labelPrimary) },
            text = { CText("L'importo verrà ricalcolato dal saldo.") },
            confirmButton = { CButton("Elimina", { r.finances.delete(entry.id); pendingDelete = null }, kind = ButtonKind.Destructive) },
            dismissButton = { CButton("Annulla", { pendingDelete = null }) },
        )
    }
}

@Composable
private fun FinanceDialog(mode: String, balance: Long, onDismiss: () -> Unit, onSave: (Long, String, String) -> Unit) {
    var amount by remember(mode) { mutableStateOf(if (mode == "balance") moneyInput(balance) else "") }
    var category by remember(mode) { mutableStateOf(financeCategories.first()) }
    var note by remember(mode) { mutableStateOf("") }
    val parsed = parseCents(amount)
    val valid = parsed != null && (mode == "balance" || parsed > 0)
    val title = when (mode) { "balance" -> "Aggiorna saldo"; "income" -> "Nuova entrata"; else -> "Nuova uscita" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = CanoneType.title3, color = Canone.colors.labelPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                CField("Importo · EUR", amount, { amount = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' || ch == '-' }.take(14) }, "0,00", keyboardType = KeyboardType.Decimal)
                if (mode != "balance") {
                    CText("Categoria")
                    financeCategories.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                            row.forEach { item ->
                                FinanceChip(item, category == item) { category = item }
                            }
                        }
                    }
                    CField("Nota", note, { note = it.take(80) }, "Facoltativa", keyboardType = KeyboardType.Text)
                }
            }
        },
        confirmButton = {
            CButton(if (mode == "balance") "Salva saldo" else "Registra", {
                if (valid) onSave(parsed!!, category, note)
            }, kind = ButtonKind.Primary, enabled = valid)
        },
        dismissButton = { CButton("Annulla", onDismiss) },
    )
}

@Composable
private fun FinanceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Canone.colors
    Box(Modifier.clip(CircleShape).background(if (selected) c.accentSubtle else c.bgSunken)
        .border(1.dp, if (selected) c.accent else Color.Transparent, CircleShape)
        .clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 7.dp)) {
        Text(label, style = CanoneType.footnote, color = if (selected) c.accentOnSubtle else c.labelSecondary, maxLines = 1)
    }
}

@Composable
private fun FinanceHeatmap(entries: List<FinanceEntry>, today: LocalDate) {
    val start = today.with(DayOfWeek.MONDAY).minusWeeks(25)
    val daily = entries.filter { e -> e.kind == "expense" && financeDate(e.ts) >= start && financeDate(e.ts) <= today }
        .groupBy { financeDate(it.ts) }
        .mapValues { (_, list) -> list.sumOf { -it.cents } }
    val maximum = daily.values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val c = Canone.colors
    Canvas(Modifier.fillMaxWidth().height(112.dp)) {
            val gap = 2.dp.toPx()
            val cell = minOf((size.width - gap * 25) / 26, (size.height - gap * 6) / 7)
            for (week in 0 until 26) for (day in 0 until 7) {
                val date = start.plusWeeks(week.toLong()).plusDays(day.toLong())
                if (date.isAfter(today)) continue
                val amount = daily[date] ?: 0L
                val intensity = if (amount == 0L) 0f else (kotlin.math.ln(1.0 + amount.toDouble()) / kotlin.math.ln(1.0 + maximum.toDouble())).toFloat().coerceIn(0f, 1f)
                val color = if (amount == 0L) c.bgSunken else androidx.compose.ui.graphics.lerp(c.positiveSubtle, c.positive, 0.2f + 0.8f * intensity)
                drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(week * (cell + gap), day * (cell + gap)),
                    size = androidx.compose.ui.geometry.Size(cell, cell), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            }
    }
    val first = start.format(DateTimeFormatter.ofPattern("d MMM", itLocale))
    val last = today.format(DateTimeFormatter.ofPattern("d MMM", itLocale))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        CText(first, tertiary = true); CText(last, tertiary = true)
    }
}

@Composable
private fun HeatCell(level: Float, size: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val c = Canone.colors
    Box(modifier.size(size).clip(RoundedCornerShape(3.dp)).background(
        if (level == 0f) c.bgSunken else androidx.compose.ui.graphics.lerp(c.positiveSubtle, c.positive, 0.2f + 0.8f * level)))
}

@Composable
private fun BalanceChart(entries: List<FinanceEntry>, month: YearMonth) {
    val c = Canone.colors
    val points = (5 downTo 0).map { offset ->
        val end = month.minusMonths(offset.toLong()).atEndOfMonth().atTime(23, 59, 59, 999_000_000).atZone(financeZone).toInstant().toEpochMilli()
        entries.asSequence().filter { it.ts <= end }.sumOf { it.cents }.toFloat()
    }
    Canvas(Modifier.fillMaxWidth().height(128.dp)) {
        val left = 8.dp.toPx(); val right = size.width - 8.dp.toPx()
        val top = 10.dp.toPx(); val bottom = size.height - 10.dp.toPx()
        val min = minOf(0f, points.minOrNull() ?: 0f); val max = maxOf(0f, points.maxOrNull() ?: 0f)
        val span = (max - min).takeIf { it > 0f } ?: 1f
        for (i in 0..2) {
            val y = top + (bottom - top) * i / 2f
            drawLine(c.lineSeparator, androidx.compose.ui.geometry.Offset(left, y), androidx.compose.ui.geometry.Offset(right, y), 1.dp.toPx())
        }
        val path = Path()
        points.forEachIndexed { i, value ->
            val x = left + (right - left) * i / (points.size - 1)
            val y = bottom - (value - min) / span * (bottom - top)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            drawCircle(c.accent, 3.5.dp.toPx(), androidx.compose.ui.geometry.Offset(x, y))
        }
        drawPath(path, c.accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun FinanceEntryRow(entry: FinanceEntry, onDelete: () -> Unit) {
    val positive = entry.cents >= 0
    val kind = when (entry.kind) { "opening" -> "Saldo iniziale"; "adjustment" -> "Saldo aggiornato"; else -> entry.category }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        Column(Modifier.weight(1f)) {
            Text(kind, style = CanoneType.body, color = Canone.colors.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val date = financeDate(entry.ts).format(DateTimeFormatter.ofPattern("d MMM yyyy", itLocale))
            Text(listOfNotNull(entry.note.takeIf { it.isNotBlank() && it != "Saldo aggiornato" }, date).joinToString(" · "), style = CanoneType.footnote, color = Canone.colors.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text((if (positive) "+" else "−") + money(kotlin.math.abs(entry.cents)), style = CanoneType.bodyMedium,
            color = if (positive) Canone.colors.positive else Canone.colors.critical, maxLines = 1)
        Text("×", style = CanoneType.title3, color = Canone.colors.labelTertiary, modifier = Modifier.clickable(onClick = onDelete).padding(6.dp))
    }
}
