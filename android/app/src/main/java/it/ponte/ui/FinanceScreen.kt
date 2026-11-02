package it.ponte.ui

import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.ponte.CATEGORIES
import it.ponte.NOTES_EUR
import it.ponte.Router
import it.ponte.Tx
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val EUR: NumberFormat = NumberFormat.getCurrencyInstance(Locale.ITALY)
internal fun eur(cents: Long): String = EUR.format(cents / 100.0)

private val CAT_COLORS = listOf(
    Color(0xFF3F86FF), Color(0xFF5DA85B), Color(0xFFE88A2E), Color(0xFF8E5BB5), Color(0xFFD9534F),
    Color(0xFF2FB5C4), Color(0xFFD9B73A), Color(0xFF6D7BFF), Color(0xFF9AA19A), Color(0xFF5DA85B),
)
private fun catColor(cat: String) = CAT_COLORS[CATEGORIES.indexOf(cat).coerceAtLeast(0) % CAT_COLORS.size]

/** Colori e tagli delle banconote in euro. */
private fun noteColor(d: Int) = when (d) {
    500 -> Color(0xFF9AA19A); 1000 -> Color(0xFFD9534F); 2000 -> Color(0xFF3F7FD6); 5000 -> Color(0xFFE88A2E)
    10000 -> Color(0xFF5DA85B); 20000 -> Color(0xFFD9B73A); else -> Color(0xFF8E5BB5)
}

@Composable
fun FinanceScreen(r: Router) {
    val c = Canone.colors
    val ctx = LocalContext.current
    val m by r.finance.state.collectAsState()
    val now = remember { Calendar.getInstance() }
    var year by remember { mutableIntStateOf(now.get(Calendar.YEAR)) }
    var month by remember { mutableIntStateOf(now.get(Calendar.MONTH)) }   // -1 = tutto l'anno
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("") }
    var revInput by remember { mutableStateOf("") }

    fun range(y: Int, mo: Int): Pair<Long, Long> {
        val a = Calendar.getInstance().apply { clear(); set(y, if (mo < 0) 0 else mo, 1) }
        val b = (a.clone() as Calendar).apply { if (mo < 0) add(Calendar.YEAR, 1) else add(Calendar.MONTH, 1) }
        return a.timeInMillis to b.timeInMillis
    }
    val (from, to) = range(year, month)
    val inPeriod = m.txs.filter { it.ts in from until to }
    val spent = -inPeriod.filter { it.cents < 0 }.sumOf { it.cents }
    val earned = inPeriod.filter { it.cents > 0 }.sumOf { it.cents }
    // periodo precedente, per il confronto
    val (pf, pt) = if (month < 0) range(year - 1, -1) else if (month == 0) range(year - 1, 11) else range(year, month - 1)
    val prevSpent = -m.txs.filter { it.ts in pf until pt && it.cents < 0 }.sumOf { it.cents }
    val daysIn = if (month < 0) (if (year == now.get(Calendar.YEAR)) now.get(Calendar.DAY_OF_YEAR) else 365)
    else if (year == now.get(Calendar.YEAR) && month == now.get(Calendar.MONTH)) now.get(Calendar.DAY_OF_MONTH)
    else Calendar.getInstance().apply { clear(); set(year, month, 1) }.getActualMaximum(Calendar.DAY_OF_MONTH)
    val title = if (month < 0) "$year" else SimpleDateFormat("LLLL yyyy", Locale.ITALY).format(Date(from)).replaceFirstChar { it.uppercase() }
    fun step(d: Int) {
        if (month < 0) year += d
        else { var mo = month + d; if (mo < 0) { mo = 11; year-- } else if (mo > 11) { mo = 0; year++ }; month = mo }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val n = try {
                val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
                r.finance.importCsv(text)
            } catch (_: Exception) { -1 }
            toast(ctx, if (n < 0) "File non leggibile" else "$n movimenti importati")
        }
    }

    ScreenList("Finanze") {
        // ------------------------------------------------------------ patrimonio
        item {
            CCard {
                Text("Patrimonio", style = CanoneType.footnote, color = c.labelTertiary)
                Text(eur(m.cashCents + m.revolutCents), style = CanoneType.title1, color = c.labelPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s5)) {
                    Column { Text("Contanti", style = CanoneType.footnote, color = c.labelTertiary); Text(eur(m.cashCents), style = CanoneType.body, color = c.labelPrimary) }
                    Column { Text("Revolut", style = CanoneType.footnote, color = c.labelTertiary); Text(eur(m.revolutCents), style = CanoneType.body, color = c.labelPrimary) }
                }
            }
        }

        // ------------------------------------------------------------ contanti
        item {
            CCard {
                SectionTitle("Contanti")
                CText("Tocca una banconota per ogni pezzo che hai. Tieni premuto per toglierne una.", tertiary = true)
                NOTES_EUR.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                        pair.forEach { d -> Banknote(d, m.cash[d] ?: 0, { r.finance.bump(d, 1) }, { r.finance.bump(d, -1) }, Modifier.weight(1f)) }
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(eur(m.cashCents), style = CanoneType.title3, color = c.labelPrimary)
                    if (m.cashCents > 0) CButton("Azzera", { r.finance.clearCash() }, kind = ButtonKind.Tertiary)
                }
            }
        }

        // ------------------------------------------------------------ nuovo movimento
        item {
            CCard {
                SectionTitle("Nuovo movimento")
                CField("Importo", amount, { amount = it }, "0,00", number = true)
                CField("Descrizione", note, { note = it }, "Dove o cosa")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    (listOf("") + CATEGORIES).forEach { k ->
                        val sel = cat == k
                        Box(
                            Modifier.clip(CircleShape).background(if (sel) c.accent else c.bgSunken)
                                .dropClickable({ cat = k }, corner = 100.dp).padding(horizontal = Space.s3, vertical = Space.s2),
                        ) { Text(k.ifEmpty { "Auto" }, style = CanoneType.footnote, color = if (sel) Color.White else c.labelSecondary, maxLines = 1) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    fun go(sign: Int) {
                        val v = r.finance.toCents(amount)
                        if (v == null || v == 0L) { toast(ctx, "Scrivi un importo"); return }
                        val k = cat.ifEmpty { r.finance.guess(note, sign > 0) }
                        r.finance.add(sign * v, if (sign > 0 && cat.isEmpty()) "Entrate" else k, note.trim())
                        amount = ""; note = ""; cat = ""
                    }
                    CButton("Spesa", { go(-1) }, Modifier.weight(1f), kind = ButtonKind.Primary)
                    CButton("Entrata", { go(1) }, Modifier.weight(1f))
                }
            }
        }

        // ------------------------------------------------------------ periodo, grafici, report
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                GlassSegmented(listOf("Mese", "Anno"), if (month < 0) 1 else 0, { month = if (it == 1) -1 else now.get(Calendar.MONTH) })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    CButton("‹", { step(-1) }, kind = ButtonKind.Tertiary)
                    Text(title, style = CanoneType.title3, color = c.labelPrimary)
                    CButton("›", { step(1) }, kind = ButtonKind.Tertiary)
                }
            }
        }
        item {
            CCard {
                SectionTitle("Report")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    Stat("Entrate", eur(earned), Modifier.weight(1f))
                    Stat("Uscite", eur(spent), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    Stat("Risparmio", eur(earned - spent), Modifier.weight(1f))
                    Stat("Al giorno", eur(if (daysIn > 0) spent / daysIn else 0), Modifier.weight(1f))
                }
                val diff = if (prevSpent > 0) ((spent - prevSpent) * 100 / prevSpent).toInt() else null
                CText(
                    when {
                        diff == null -> "Nessun dato nel periodo precedente per il confronto."
                        diff > 0 -> "Hai speso il $diff% in più del periodo precedente."
                        diff < 0 -> "Hai speso il ${-diff}% in meno del periodo precedente."
                        else -> "Spesa uguale al periodo precedente."
                    },
                    tertiary = true,
                )
            }
        }
        item {
            CCard {
                SectionTitle(if (month < 0) "Mese per mese" else "Ultimi mesi")
                val bars = (0 until (if (month < 0) 12 else 6)).map { i ->
                    val (y, mo) = if (month < 0) year to i else {
                        var mm = month - 5 + i; var yy = year
                        while (mm < 0) { mm += 12; yy-- }
                        yy to mm
                    }
                    val (a, b) = range(y, mo)
                    val l = m.txs.filter { it.ts in a until b }
                    Triple(listOf("G", "F", "M", "A", "M", "G", "L", "A", "S", "O", "N", "D")[mo], l.filter { it.cents > 0 }.sumOf { it.cents }, -l.filter { it.cents < 0 }.sumOf { it.cents })
                }
                BarChart(bars, c.accent, c.labelTertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                    Legend("Entrate", c.accent); Legend("Uscite", c.labelTertiary)
                }
            }
        }
        item {
            CCard {
                SectionTitle("Dove vanno i soldi")
                val by = inPeriod.filter { it.cents < 0 }.groupBy { it.cat }.mapValues { -it.value.sumOf { t -> t.cents } }.entries.sortedByDescending { it.value }
                if (by.isEmpty()) CText("Nessuna spesa in questo periodo.", tertiary = true) else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s4), verticalAlignment = Alignment.CenterVertically) {
                        Donut(by.map { it.value.toFloat() to catColor(it.key) }, Modifier.size(120.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                            by.take(6).forEach { (k, v) ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(catColor(k)))
                                    Text(k, style = CanoneType.footnote, color = c.labelSecondary, modifier = Modifier.weight(1f), maxLines = 1)
                                    Text("${v * 100 / spent.coerceAtLeast(1)}%", style = CanoneType.footnote, color = c.labelTertiary)
                                }
                            }
                        }
                    }
                    CDivider()
                    by.forEach { (k, v) -> CRow(k, null) { Text(eur(v), style = CanoneType.body, color = c.labelPrimary) } }
                }
            }
        }

        // ------------------------------------------------------------ movimenti
        item {
            CCard {
                SectionTitle("Movimenti")
                if (inPeriod.isEmpty()) CText("Niente in questo periodo.", tertiary = true) else {
                    CText("Tieni premuto per eliminare.", tertiary = true)
                    inPeriod.take(40).forEach { t -> TxRow(t, ctx) { r.finance.remove(t.id) } }
                }
            }
        }

        // ------------------------------------------------------------ Revolut
        item {
            CCard {
                SectionTitle("Revolut")
                CRow("Saldo", "Stimato dall'ultimo estratto più i pagamenti arrivati dopo") { Text(eur(m.revolutCents), style = CanoneType.body, color = c.labelPrimary) }
                CRow("Pagamenti dalle notifiche", "Ogni pagamento che Revolut ti notifica entra da solo") {
                    if (listenerEnabled(ctx)) CBadge("Attivo", Tone.Positive)
                    else CButton("Attiva", { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, kind = ButtonKind.Primary)
                }
                CButton("Importa estratto (CSV)", { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth())
                CField("Imposta il saldo a mano", revInput, { revInput = it }, "0,00", number = true)
                CButton("Salva saldo", {
                    r.finance.toCents(revInput)?.let { r.finance.setRevolut(it); revInput = ""; toast(ctx, "Saldo aggiornato") } ?: toast(ctx, "Scrivi un importo")
                }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
                CText(
                    "Revolut non offre un'API per i conti personali (solo per quelli aziendali). Per questo i movimenti arrivano dalle sue notifiche, " +
                        "e lo storico dall'estratto: in Revolut apri Conto → Estratti → Excel/CSV.",
                    tertiary = true,
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    val c = Canone.colors
    Column(modifier.clip(RoundedCornerShape(Radius.lg)).background(c.bgSunken).padding(Space.s3)) {
        Text(label, style = CanoneType.footnote, color = c.labelTertiary)
        Text(value, style = CanoneType.body.copy(fontWeight = FontWeight.Medium), color = c.labelPrimary, maxLines = 1)
    }
}

@Composable
private fun Legend(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, style = CanoneType.footnote, color = Canone.colors.labelSecondary)
    }
}

@Composable
private fun TxRow(t: Tx, ctx: android.content.Context, onDelete: () -> Unit) {
    val c = Canone.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).dropClickable({}, onDelete, Radius.lg).padding(vertical = Space.s2),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(catColor(t.cat)))
        Column(Modifier.weight(1f)) {
            Text(t.note.ifEmpty { t.cat }, style = CanoneType.body, color = c.labelPrimary, maxLines = 1)
            Text(t.cat + " · " + DateFormat.getMediumDateFormat(ctx).format(Date(t.ts)) + if (t.src == "revolut") " · Revolut" else "", style = CanoneType.footnote, color = c.labelTertiary, maxLines = 1)
        }
        Text((if (t.cents > 0) "+" else "") + eur(t.cents), style = CanoneType.body, color = if (t.cents > 0) c.positiveOnSubtle else c.labelPrimary, maxLines = 1)
    }
}

/** Banconota disegnata: colore e taglio come l'euro vero, con la finestra ad arco e i cerchi concentrici. Tocco = una in più, pressione lunga = una in meno. */
@Composable
private fun Banknote(denom: Int, count: Int, onAdd: () -> Unit, onSub: () -> Unit, modifier: Modifier) {
    val touch = rememberDropTouch()
    val base = noteColor(denom)
    Box(modifier.aspectRatio(2.05f).dropGestures(touch, onAdd, onSub)) {
        Box(Modifier.fillMaxSize().dropDeform(touch).clip(RoundedCornerShape(10.dp))) {
            Canvas(Modifier.fillMaxSize()) {
                val a = if (count > 0) 1f else 0.5f
                drawRect(Brush.linearGradient(listOf(base.copy(alpha = a), Color.White.copy(alpha = 0.35f * a).compositeOver(base))))
                val w = size.width; val h = size.height
                // margine interno chiaro
                drawRoundRect(Color.White.copy(alpha = 0.35f), Offset(w * 0.04f, h * 0.07f), Size(w * 0.92f, h * 0.86f), CornerRadius(6f), style = Stroke(1.5f))
                // cerchi concentrici a destra
                for (i in 1..5) drawCircle(Color.White.copy(alpha = 0.13f), h * 0.12f * i, Offset(w * 0.78f, h * 0.55f), style = Stroke(1.2f))
                // finestra ad arco
                drawArc(Color.White.copy(alpha = 0.28f), 180f, 180f, true, Offset(w * 0.08f, h * 0.18f), Size(w * 0.22f, w * 0.22f))
                drawRect(Color.White.copy(alpha = 0.28f), Offset(w * 0.08f, h * 0.18f + w * 0.11f), Size(w * 0.22f, h * 0.38f))
                // stelline in alto a destra
                for (i in 0 until 12) {
                    val ang = i * (2 * Math.PI / 12).toFloat()
                    drawCircle(Color.White.copy(alpha = 0.35f), 1.6f, Offset(w * 0.86f + 11f * kotlin.math.cos(ang), h * 0.22f + 11f * kotlin.math.sin(ang)))
                }
            }
            Text("${denom / 100}", style = CanoneType.title1.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold), color = Color.White, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp))
            Text("EURO", style = CanoneType.footnote.copy(fontSize = 9.sp, letterSpacing = 1.5.sp), color = Color.White.copy(alpha = 0.8f), modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 8.dp))
        }
        if (count > 0) Box(
            Modifier.align(Alignment.TopStart).padding(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 9.dp, vertical = 3.dp),
        ) { Text("×$count", style = CanoneType.footnote.copy(fontWeight = FontWeight.Medium), color = Color.White) }
    }
}

private fun Color.compositeOver(bg: Color): Color {
    val a = alpha
    return Color(red * a + bg.red * (1 - a), green * a + bg.green * (1 - a), blue * a + bg.blue * (1 - a), 1f)
}

@Composable
private fun BarChart(bars: List<Triple<String, Long, Long>>, income: Color, spend: Color) {
    val c = Canone.colors
    val max = (bars.maxOfOrNull { maxOf(it.second, it.third) } ?: 0L).coerceAtLeast(1L)
    Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Text(eur(max), style = CanoneType.footnote, color = c.labelTertiary)
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val slot = size.width / bars.size
            val bw = slot * 0.30f
            bars.forEachIndexed { i, (_, inc, out) ->
                val x = i * slot + slot / 2f
                val hi = size.height * inc / max; val ho = size.height * out / max
                drawRoundRect(income, Offset(x - bw - 1f, size.height - hi), Size(bw, hi.coerceAtLeast(2f)), CornerRadius(4f))
                drawRoundRect(spend, Offset(x + 1f, size.height - ho), Size(bw, ho.coerceAtLeast(2f)), CornerRadius(4f))
            }
        }
        Row(Modifier.fillMaxWidth()) { bars.forEach { Text(it.first, style = CanoneType.footnote, color = c.labelTertiary, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
    }
}

@Composable
private fun Donut(parts: List<Pair<Float, Color>>, modifier: Modifier) {
    val total = parts.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(1f)
    Canvas(modifier) {
        val sw = size.minDimension * 0.18f
        var start = -90f
        parts.forEach { (v, col) ->
            val sweep = 360f * v / total
            drawArc(col, start, (sweep - 2f).coerceAtLeast(0.5f), false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Butt))
            start += sweep
        }
    }
}
