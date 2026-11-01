package it.ponte.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private val financeCategories = listOf("Altro", "Casa", "Cibo", "Trasporti", "Tempo libero", "Salute")
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
    val net = income - expenses
    var dialogMode by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<FinanceEntry?>(null) }
    var showAll by remember { mutableStateOf(false) }

    ScreenList("Finanze") {
        item { FinanceHero(balance, entries.isNotEmpty(), net, income, expenses,
            onBalance = { dialogMode = "balance" }, onMovement = { dialogMode = "entry" }) }
        item {
            CCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    SectionTitle("Spese")
                    CBadge(money(expenses), if (expenses > 0) Tone.Critical else Tone.Neutral)
                }
                CText("Giorno per giorno · più verde = più spese")
                FinanceHeatmap(entries, now)
                val recentWeek = entries.filter { it.kind == "expense" && financeDate(it.ts) >= now.minusDays(6) }.sumOf { -it.cents }
                val halfYear = entries.filter { it.kind == "expense" && financeDate(it.ts) >= now.with(DayOfWeek.MONDAY).minusWeeks(25) }.sumOf { -it.cents }
                CText("7 giorni ${money(recentWeek)}   ·   6 mesi ${money(halfYear)}", tertiary = true)
            }
        }
        item {
            CCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    SectionTitle("Entrate e uscite")
                    Text(if (net >= 0) "+${money(net)}" else "−${money(-net)}", style = CanoneType.footnote, color = if (net >= 0) Canone.colors.positive else Canone.colors.critical)
                }
                CText("Saldo netto per mese")
                FinanceFlowChart(entries, month)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    CBadge("↑ Entrate superiori", Tone.Positive)
                    CBadge("↓ Uscite superiori", Tone.Critical)
                }
            }
        }
        item {
            CCard {
                SectionTitle("Dove spendi")
                val byCategory = monthEntries.filter { it.kind == "expense" }.groupBy { it.category }
                    .mapValues { (_, list) -> list.sumOf { -it.cents } }.toList().sortedByDescending { it.second }
                if (byCategory.isEmpty()) CText("Nessuna uscita registrata questo mese.")
                else {
                    val max = byCategory.first().second.coerceAtLeast(1L)
                    byCategory.take(4).forEach { (category, amount) ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            CRow(category, money(amount), mono = true)
                            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Canone.colors.bgSunken)) {
                                Box(Modifier.fillMaxWidth((amount.toFloat() / max).coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(Canone.colors.positive))
                            }
                        }
                    }
                    val remaining = byCategory.drop(4).sumOf { it.second }
                    if (remaining > 0) CRow("Altre categorie", money(remaining))
                }
            }
        }
        item {
            CCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    SectionTitle("Movimenti")
                    if (entries.size > 4) Text(if (showAll) "Meno" else "Tutti", style = CanoneType.footnote, color = Canone.colors.accent,
                        modifier = Modifier.clickable { showAll = !showAll }.padding(8.dp))
                }
                if (entries.isEmpty()) CText("Registra la prima entrata o uscita per popolare il riepilogo.")
                else entries.sortedByDescending { it.ts }.take(if (showAll) entries.size else 4).forEachIndexed { index, e ->
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
                else -> {
                    val (kind, name) = category.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "Altro" } }
                    r.finances.add(kind, cents, name, note)
                }
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
private fun FinanceHero(balance: Long, hasBalance: Boolean, net: Long, income: Long, expenses: Long, onBalance: () -> Unit, onMovement: () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.xl)).background(Brand.gradient).padding(Space.s5)) {
        Text("€", style = CanoneType.title1.copy(fontSize = 108.sp, lineHeight = 112.sp), color = Color.White.copy(alpha = 0.12f), modifier = Modifier.align(Alignment.TopEnd))
        Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Text("SALDO ATTUALE", style = CanoneType.footnote, color = Color.White.copy(alpha = 0.82f))
            Text(if (hasBalance) money(balance) else "Da impostare", style = CanoneType.title1, color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text((if (net >= 0) "↑ " else "↓ ") + money(kotlin.math.abs(net)) + " questo mese",
                style = CanoneType.bodyMedium, color = Color.White)
            Text("Entrate ${money(income)}   ·   Uscite ${money(expenses)}", style = CanoneType.footnote, color = Color.White.copy(alpha = 0.82f))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                CButton("Saldo", onBalance, Modifier.weight(1f))
                CButton("+ Movimento", onMovement, Modifier.weight(1f), ButtonKind.Primary)
            }
        }
    }
}

@Composable
private fun FinanceDialog(mode: String, balance: Long, onDismiss: () -> Unit, onSave: (Long, String, String) -> Unit) {
    var amount by remember(mode) { mutableStateOf(if (mode == "balance") moneyInput(balance) else "") }
    var category by remember(mode) { mutableStateOf(financeCategories.first()) }
    var note by remember(mode) { mutableStateOf("") }
    var kind by remember(mode) { mutableStateOf("expense") }
    var categoryOpen by remember(mode) { mutableStateOf(false) }
    var showNote by remember(mode) { mutableStateOf(false) }
    val parsed = parseCents(amount)
    val valid = parsed != null && (mode == "balance" || parsed > 0)
    val title = if (mode == "balance") "Aggiorna saldo" else "Nuovo movimento"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = CanoneType.title3, color = Canone.colors.labelPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                CField("Importo · EUR", amount, { amount = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' || ch == '-' }.take(14) }, "0,00", keyboardType = KeyboardType.Decimal)
                if (mode != "balance") {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                        CButton("− Uscita", { kind = "expense" }, Modifier.weight(1f), if (kind == "expense") ButtonKind.Primary else ButtonKind.Secondary)
                        CButton("+ Entrata", { kind = "income" }, Modifier.weight(1f), if (kind == "income") ButtonKind.Primary else ButtonKind.Secondary)
                    }
                    Box {
                        CButton("Categoria · $category", { categoryOpen = true })
                        DropdownMenu(expanded = categoryOpen, onDismissRequest = { categoryOpen = false }) {
                            financeCategories.forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { category = item; categoryOpen = false })
                            }
                        }
                    }
                    Text(if (showNote) "Nascondi nota" else "+ Aggiungi una nota", style = CanoneType.footnote, color = Canone.colors.accent,
                        modifier = Modifier.clickable { showNote = !showNote }.padding(vertical = 4.dp))
                    if (showNote) CField("Nota", note, { note = it.take(80) }, "Facoltativa", keyboardType = KeyboardType.Text)
                }
            }
        },
        confirmButton = {
            CButton(if (mode == "balance") "Salva" else "Salva movimento", {
                if (valid) onSave(parsed!!, if (mode == "balance") "" else "$kind|$category", note)
            }, kind = ButtonKind.Primary, enabled = valid)
        },
        dismissButton = { CButton("Annulla", onDismiss) },
    )
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
private fun FinanceFlowChart(entries: List<FinanceEntry>, currentMonth: YearMonth) {
    val c = Canone.colors
    val months = (5 downTo 0).map { offset ->
        val month = currentMonth.minusMonths(offset.toLong())
        val moves = entries.filter { YearMonth.from(financeDate(it.ts)) == month }
        month to (moves.filter { it.kind == "income" }.sumOf { it.cents } - moves.filter { it.kind == "expense" }.sumOf { -it.cents })
    }
    val maxValue = months.maxOfOrNull { kotlin.math.abs(it.second) }?.coerceAtLeast(1L) ?: 1L
    Canvas(Modifier.fillMaxWidth().height(106.dp)) {
        val center = size.height * 0.58f
        val maxHeight = minOf(center - 10.dp.toPx(), size.height - center - 8.dp.toPx())
        val step = size.width / months.size
        val barWidth = step * 0.42f
        drawLine(c.lineSeparator, androidx.compose.ui.geometry.Offset(0f, center), androidx.compose.ui.geometry.Offset(size.width, center), 1.dp.toPx())
        months.forEachIndexed { index, (_, amount) ->
            val height = (kotlin.math.abs(amount).toDouble() / maxValue * maxHeight).toFloat().coerceAtLeast(if (amount == 0L) 0f else 3.dp.toPx())
            val x = index * step + (step - barWidth) / 2f
            val y = if (amount >= 0) center - height else center
            val color = when { amount > 0 -> c.positive; amount < 0 -> c.critical; else -> c.bgSunken }
            drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(x, y), size = androidx.compose.ui.geometry.Size(barWidth, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        months.forEach { (month, amount) ->
            val label = month.month.getDisplayName(java.time.format.TextStyle.SHORT, itLocale).replaceFirstChar { it.uppercase(itLocale) }
            Text(label, style = CanoneType.footnote, color = when { amount > 0 -> c.positive; amount < 0 -> c.critical; else -> c.labelTertiary })
        }
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
