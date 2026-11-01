package it.ponte.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
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
private val financeBanknotes = listOf(5, 10, 20, 50, 100, 200)
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
            CText("Andamento giornaliero · ultimi 6 mesi")
                FinanceHeatmap(entries, now)
                val recentWeek = entries.filter { it.kind == "expense" && financeDate(it.ts) >= now.minusDays(6) }.sumOf { -it.cents }
                val halfYear = entries.filter { it.kind == "expense" && financeDate(it.ts) >= now.with(DayOfWeek.MONDAY).minusWeeks(25) }.sumOf { -it.cents }
                CText("7 giorni ${money(recentWeek)}   ·   6 mesi ${money(halfYear)}", tertiary = true)
            }
        }
        item {
            CCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SectionTitle("Entrate e uscite")
                        CText("Confronto mensile · ultimi 6 mesi")
                    }
                    CBadge((if (net >= 0) "+" else "−") + money(kotlin.math.abs(net)), if (net >= 0) Tone.Positive else Tone.Critical)
                }
                FinanceFlowChart(entries, month)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    FinanceChartLegend(Canone.colors.positive, "Entrate")
                    FinanceChartLegend(Canone.colors.critical, "Uscite")
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
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Canone.colors.bgSunken)) {
                            Box(Modifier.fillMaxWidth((amount.toFloat() / max).coerceIn(0f, 1f)).height(8.dp).clip(CircleShape).background(Canone.colors.critical))
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
    var banknotes by remember(mode) { mutableStateOf(emptyList<Int>()) }
    val parsed = parseCents(amount)
    val valid = parsed != null && (mode == "balance" || parsed > 0)
    val title = if (mode == "balance") "Aggiorna saldo" else if (kind == "income") "Nuova entrata" else "Nuova uscita"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = CanoneType.title3, color = Canone.colors.labelPrimary) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                if (mode != "balance") FinanceKindPicker(kind, onChange = { kind = it })
                MoneyAmountField(amount, {
                    banknotes = emptyList()
                    amount = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' || ch == '-' }.take(14)
                })
                if (mode != "balance") {
                    BanknotePicker(banknotes, onUndo = {
                        banknotes.lastOrNull()?.let { denomination ->
                            amount = moneyInput(((parseCents(amount) ?: 0L) - denomination * 100L).coerceAtLeast(0L))
                            banknotes = banknotes.dropLast(1)
                        }
                    }, onClear = { amount = ""; banknotes = emptyList() }) { denomination ->
                        val next = (parseCents(amount) ?: 0L) + denomination * 100L
                        amount = moneyInput(next)
                        banknotes = banknotes + denomination
                    }
                    CText("Categoria")
                    Box {
                        CButton("Categoria · $category", { categoryOpen = true }, Modifier.fillMaxWidth())
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
            CButton(if (mode == "balance") "Salva saldo" else if (kind == "income") "Aggiungi entrata" else "Aggiungi uscita", {
                if (valid) onSave(parsed!!, if (mode == "balance") "" else "$kind|$category", note)
            }, kind = ButtonKind.Primary, enabled = valid)
        },
        dismissButton = { CButton("Annulla", onDismiss) },
    )
}

@Composable
private fun MoneyAmountField(amount: String, onChange: (String) -> Unit) {
    val c = Canone.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Text("Importo", style = CanoneType.footnote, color = c.labelSecondary)
        BasicTextField(
            value = amount,
            onValueChange = onChange,
            singleLine = true,
            textStyle = CanoneType.figure.copy(fontSize = 30.sp, lineHeight = 38.sp, color = c.labelPrimary),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(Radius.lg))
                        .background(c.bgSurface).border(1.5.dp, c.lineStrong, RoundedCornerShape(Radius.lg))
                        .padding(horizontal = Space.s4),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s3),
                ) {
                    Text("€", style = CanoneType.title2, color = c.accent)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (amount.isEmpty()) Text("0,00", style = CanoneType.figure.copy(fontSize = 30.sp), color = c.labelTertiary)
                        inner()
                    }
                    Text("EUR", style = CanoneType.footnote, color = c.labelTertiary)
                }
            },
        )
    }
}

@Composable
private fun FinanceKindPicker(kind: String, onChange: (String) -> Unit) {
    val c = Canone.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.bgSunken).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FinanceKindOption("Uscita", "expense", kind == "expense", Modifier.weight(1f), onChange)
        FinanceKindOption("Entrata", "income", kind == "income", Modifier.weight(1f), onChange)
    }
}

@Composable
private fun FinanceKindOption(label: String, value: String, selected: Boolean, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    val c = Canone.colors
    val (background, foreground) = when {
        value == "expense" && selected -> c.criticalSubtle to c.criticalOnSubtle
        value == "income" && selected -> c.positiveSubtle to c.positiveOnSubtle
        else -> Color.Transparent to c.labelSecondary
    }
    Row(
        modifier.height(46.dp).clip(RoundedCornerShape(Radius.sm)).background(background)
            .selectable(selected = selected, role = Role.RadioButton, onClick = { onChange(value) }).padding(horizontal = Space.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(if (value == "expense") "−" else "+", style = CanoneType.bodyMedium, color = foreground)
        Text("  $label", style = CanoneType.bodyMedium, color = foreground)
    }
}

@Composable
private fun BanknotePicker(selected: List<Int>, onUndo: () -> Unit, onClear: () -> Unit, onAdd: (Int) -> Unit) {
    val c = Canone.colors
    val counts = selected.groupingBy { it }.eachCount()
    val total = selected.sumOf { it.toLong() } * 100L
    val palettes = listOf(
        c.bgSunken to c.labelSecondary,
        c.criticalSubtle to c.criticalOnSubtle,
        c.accentSubtle to c.accentOnSubtle,
        c.cautionSubtle to c.cautionOnSubtle,
        c.positiveSubtle to c.positiveOnSubtle,
        c.bgSunken to c.cautionOnSubtle,
    )
    Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Banconote", style = CanoneType.bodyMedium, color = c.labelPrimary)
                CText("Tocca un taglio per aggiungerlo")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s1), verticalAlignment = Alignment.CenterVertically) {
                Text("Indietro", style = CanoneType.footnote, color = if (selected.isEmpty()) c.labelDisabled else c.accent,
                    modifier = Modifier.clickable(enabled = selected.isNotEmpty(), role = Role.Button, onClick = onUndo).padding(horizontal = 8.dp, vertical = 12.dp))
                Text("Azzera", style = CanoneType.footnote, color = if (selected.isEmpty()) c.labelDisabled else c.accent,
                    modifier = Modifier.clickable(enabled = selected.isNotEmpty(), role = Role.Button, onClick = onClear).padding(horizontal = 8.dp, vertical = 12.dp))
            }
        }
        financeBanknotes.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                row.forEach { denomination ->
                    val (background, foreground) = palettes[financeBanknotes.indexOf(denomination)]
                    BanknoteButton(denomination, counts.getOrDefault(denomination, 0), background, foreground,
                        Modifier.weight(1f), onClick = { onAdd(denomination) })
                }
            }
        }
        if (selected.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.sm)).background(c.bgSunken).padding(horizontal = Space.s3, vertical = Space.s2),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${selected.size} ${if (selected.size == 1) "banconota" else "banconote"}", style = CanoneType.footnote, color = c.labelSecondary)
                Text(money(total), style = CanoneType.bodyMedium, color = c.labelPrimary)
            }
        }
    }
}

@Composable
private fun BanknoteButton(denomination: Int, count: Int, background: Color, foreground: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Canone.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = androidx.compose.animation.core.spring(stiffness = 560f),
        label = "banconota-premuta",
    )
    val shape = RoundedCornerShape(Radius.md)
    val outlineColor by androidx.compose.animation.animateColorAsState(
        if (count > 0) foreground else foreground.copy(alpha = 0.24f), label = "contorno-banconota",
    )
    Box(
        modifier = modifier.height(76.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape).background(background)
            .border(if (count > 0) 2.dp else 1.dp, outlineColor, shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                onClickLabel = "Aggiungi una banconota da $denomination euro", onClick = onClick),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val inset = 5.dp.toPx()
            drawRoundRect(foreground.copy(alpha = 0.18f), topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            for (line in 0..8) {
                val x = size.width * 0.58f + line * 6.dp.toPx()
                drawLine(foreground.copy(alpha = 0.08f), androidx.compose.ui.geometry.Offset(x, size.height),
                    androidx.compose.ui.geometry.Offset(x + size.height * 0.46f, size.height * 0.42f), 1.dp.toPx())
            }
            val center = androidx.compose.ui.geometry.Offset(size.width * 0.82f, size.height * 0.52f)
            drawCircle(foreground.copy(alpha = 0.13f), radius = 18.dp.toPx(), center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            drawCircle(foreground.copy(alpha = 0.10f), radius = 13.dp.toPx(), center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
        Column(Modifier.align(Alignment.CenterStart).padding(start = Space.s4), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("€$denomination", style = CanoneType.title3.copy(fontSize = 23.sp, lineHeight = 26.sp), color = foreground)
            Text("BANCONOTA · EURO", style = CanoneType.footnote.copy(fontSize = 9.sp, lineHeight = 12.sp), color = foreground.copy(alpha = 0.78f))
        }
        if (count > 0) Text("×$count", style = CanoneType.footnote.copy(fontSize = 10.sp), color = c.accentOnAccent,
            modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).clip(CircleShape).background(c.accent).padding(horizontal = 7.dp, vertical = 2.dp))
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
                val color = if (amount == 0L) c.bgSunken else androidx.compose.ui.graphics.lerp(c.accentSubtle, c.accent, 0.25f + 0.75f * intensity)
                drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(week * (cell + gap), day * (cell + gap)),
                    size = androidx.compose.ui.geometry.Size(cell, cell), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            }
    }
    val first = start.format(DateTimeFormatter.ofPattern("d MMM", itLocale))
    val last = today.format(DateTimeFormatter.ofPattern("d MMM", itLocale))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        CText(first, tertiary = true); CText(last, tertiary = true)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s1)) {
        CText("Meno", tertiary = true)
        listOf(c.bgSunken, c.accentSubtle, c.accent).forEach { color ->
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        }
        CText("Più", tertiary = true)
    }
    if (daily.isEmpty()) CText("Registra un'uscita per popolare la mappa", tertiary = true)
}

@Composable
private fun FinanceFlowChart(entries: List<FinanceEntry>, currentMonth: YearMonth) {
    val c = Canone.colors
    val months = (5 downTo 0).map { offset ->
        val month = currentMonth.minusMonths(offset.toLong())
        val moves = entries.filter { YearMonth.from(financeDate(it.ts)) == month }
        Triple(month, moves.filter { it.kind == "income" }.sumOf { it.cents }, moves.filter { it.kind == "expense" }.sumOf { -it.cents })
    }
    val maxValue = months.maxOfOrNull { maxOf(it.second, it.third) }?.coerceAtLeast(1L) ?: 1L
    Canvas(Modifier.fillMaxWidth().height(106.dp)) {
        val baseline = size.height - 6.dp.toPx()
        val maxHeight = size.height - 18.dp.toPx()
        val step = size.width / months.size
        val barWidth = step * 0.22f
        val barGap = 3.dp.toPx()
        drawLine(c.lineSeparator, androidx.compose.ui.geometry.Offset(0f, baseline), androidx.compose.ui.geometry.Offset(size.width, baseline), 1.dp.toPx())
        months.forEachIndexed { index, values ->
            val groupWidth = barWidth * 2 + barGap
            val startX = index * step + (step - groupWidth) / 2f
            listOf(values.second to c.positive, values.third to c.critical).forEachIndexed { barIndex, (amount, color) ->
                val height = (amount.toDouble() / maxValue * maxHeight).toFloat().let { if (amount > 0) it.coerceAtLeast(3.dp.toPx()) else 0f }
                val x = startX + barIndex * (barWidth + barGap)
                drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(x, baseline - height), size = androidx.compose.ui.geometry.Size(barWidth, height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        months.forEach { (month, _, _) ->
            val label = month.month.getDisplayName(java.time.format.TextStyle.SHORT, itLocale).replaceFirstChar { it.uppercase(itLocale) }
            Text(label, style = CanoneType.footnote, color = c.labelTertiary)
        }
    }
    if (months.all { it.second == 0L && it.third == 0L }) CText("Registra un movimento per vedere il confronto", tertiary = true)
}

@Composable
private fun FinanceChartLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s1)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = CanoneType.footnote, color = Canone.colors.labelSecondary)
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
