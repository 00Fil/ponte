package it.ponte.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.draw.alpha
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
internal fun catColor(cat: String) = CAT_COLORS[CATEGORIES.indexOf(cat).coerceAtLeast(0) % CAT_COLORS.size]

/** Colori e tagli delle banconote in euro. */
private fun noteColor(d: Int) = when (d) {
    500 -> Color(0xFF9AA19A); 1000 -> Color(0xFFD9534F); 2000 -> Color(0xFF3F7FD6); 5000 -> Color(0xFFE88A2E)
    10000 -> Color(0xFF5DA85B); 20000 -> Color(0xFFD9B73A); else -> Color(0xFF8E5BB5)
}

@Composable
fun FinanceScreen(r: Router, sub: String?, onSub: (String?) -> Unit) {
    val c = Canone.colors
    val ctx = LocalContext.current
    val m by r.finance.state.collectAsState()
    val now = remember { Calendar.getInstance() }
    var year by remember { mutableIntStateOf(now.get(Calendar.YEAR)) }
    var month by remember { mutableIntStateOf(now.get(Calendar.MONTH)) }   // -1 = tutto l'anno
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("") }
    var income by remember { mutableStateOf(false) }
    var revInput by remember { mutableStateOf("") }
    var cashMode by remember { mutableIntStateOf(1) }            // 1 ho speso, 2 ho ricevuto
    var tally by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var showNote by remember { mutableStateOf(false) }
    var src by remember { mutableStateOf("cash") }
    var srcMenu by remember { mutableStateOf(false) }

    fun range(y: Int, mo: Int): Pair<Long, Long> {
        val a = Calendar.getInstance().apply { clear(); set(y, if (mo < 0) 0 else mo, 1) }
        val b = (a.clone() as Calendar).apply { if (mo < 0) add(Calendar.YEAR, 1) else add(Calendar.MONTH, 1) }
        return a.timeInMillis to b.timeInMillis
    }
    val (from, to) = range(year, month)
    val inPeriod = m.txs.filter { it.ts in from until to }
    val spent = -inPeriod.filter { it.cents < 0 }.sumOf { it.cents }
    val earned = inPeriod.filter { it.cents > 0 }.sumOf { it.cents }
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
    val dayCal = Calendar.getInstance()
    // andamento cumulato: un punto per giorno (mese) o per mese (anno)
    val trend = remember(inPeriod, month, year) {
        val buckets = if (month < 0) 12 else Calendar.getInstance().apply { clear(); set(year, month, 1) }.getActualMaximum(Calendar.DAY_OF_MONTH)
        val last = if (month < 0) (if (year == now.get(Calendar.YEAR)) now.get(Calendar.MONTH) + 1 else 12)
        else (if (year == now.get(Calendar.YEAR) && month == now.get(Calendar.MONTH)) now.get(Calendar.DAY_OF_MONTH) else buckets)
        val sums = FloatArray(buckets)
        inPeriod.forEach { t -> dayCal.timeInMillis = t.ts; sums[if (month < 0) dayCal.get(Calendar.MONTH) else dayCal.get(Calendar.DAY_OF_MONTH) - 1] += t.cents / 100f }
        var acc = 0f
        (0 until last.coerceAtLeast(2)).map { i -> acc += sums.getOrElse(i) { 0f }; acc }
    }

    // ritmo di spesa: spesa cumulata di questo periodo contro il periodo prima, con la previsione a fine periodo
    val pace = remember(m.txs, month, year) {
        val cal = Calendar.getInstance()
        val buckets = if (month < 0) 12 else Calendar.getInstance().apply { clear(); set(year, month, 1) }.getActualMaximum(Calendar.DAY_OF_MONTH)
        fun bucket(ts: Long): Int { cal.timeInMillis = ts; return (if (month < 0) cal.get(Calendar.MONTH) else cal.get(Calendar.DAY_OF_MONTH) - 1).coerceIn(0, buckets - 1) }
        val cur = FloatArray(buckets); val prev = FloatArray(buckets)
        m.txs.forEach { t ->
            if (t.cents >= 0) return@forEach
            if (t.ts in from until to) cur[bucket(t.ts)] += -t.cents / 100f
            else if (t.ts in pf until pt) prev[bucket(t.ts)] += -t.cents / 100f
        }
        val ongoing = System.currentTimeMillis() in from until to
        val last = if (ongoing) bucket(System.currentTimeMillis()) + 1 else if (System.currentTimeMillis() < from) 0 else buckets
        var a = 0f; val curCum = (0 until last).map { a += cur[it]; a }
        var b = 0f; val prevCum = (0 until buckets).map { b += prev[it]; b }
        val proj = if (ongoing && last >= 3 && last < buckets) curCum.last() / last * buckets else null
        PaceData(curCum, prevCum, buckets, proj)
    }

    // La barra delle schede, in questa pagina, diventa il tasto che conferma: tutto il necessario passa da CashBar.
    val tallyAll = tally.entries.sumOf { it.key.toLong() * it.value }
    val spendNow = cashMode != 2
    androidx.compose.runtime.SideEffect {
        CashBar.on = sub == "cash"
        CashBar.enabled = tallyAll != 0L
        CashBar.label = (if (spendNow) "Scorri per registrare −" else "Scorri per registrare +") + eur(tallyAll)
        CashBar.onBack = { tally = emptyMap(); cat = ""; onSub(null) }
        CashBar.onConfirm = {
            val before = m.totalCents
            r.finance.add(if (spendNow) -tallyAll else tallyAll, if (spendNow) cat.ifEmpty { "Altro" } else "Entrate", "Contanti", "cash")
            Flash.show(before, r.finance.state.value.totalCents - before)
            tally = emptyMap(); cat = ""; onSub(null)
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { CashBar.on = false } }

    @Composable
    fun Back(label: String) = BackRow("Finanze", label) { onSub(null) }

    // Nuovo movimento: pagina fissa, tutto visibile senza scorrere e sopra la barra.
    @Composable
    fun AddPage() {
        val dark = isDark()
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxSize().padding(start = Space.s4, end = Space.s4, top = Space.s3, bottom = Space.s3 + LocalBarInset.current),
                verticalArrangement = Arrangement.spacedBy(Space.s3),
            ) {
                Back(if (income) "Nuova entrata" else "Nuova spesa")
                GlassSegmented(listOf("Spesa", "Entrata"), if (income) 1 else 0, { income = it == 1 })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(58.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            "€" + amount.ifEmpty { "0" },
                            style = CanoneType.title1.copy(fontSize = 48.sp, lineHeight = 58.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                            color = if (amount.isEmpty()) c.labelTertiary else c.labelPrimary, maxLines = 1,
                        )
                    }
                    Action("", "Banconote", false, glyph = 9) { cashMode = if (income) 2 else 1; tally = emptyMap(); onSub("cash") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    (if (income) listOf("Entrate") else CATEGORIES.dropLast(1)).let { listOf("") + it }.forEach { k ->
                        val sel = cat == k
                        Box(
                            Modifier.clip(CircleShape).background(if (sel) c.accent else c.bgSunken)
                                .dropClickable({ cat = k }, corner = 100.dp).padding(horizontal = Space.s3, vertical = Space.s2),
                        ) { Text(k.ifEmpty { "Auto" }, style = CanoneType.footnote, color = if (sel) Color.White else c.labelSecondary, maxLines = 1) }
                    }
                    Box {
                        Row(
                            Modifier.clip(CircleShape).background(srcColor(src).copy(alpha = 0.22f)).dropClickable({ srcMenu = true }, corner = 100.dp).padding(horizontal = Space.s3, vertical = Space.s2),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(srcColor(src)))
                            Text(srcName(src) + " ▾", style = CanoneType.footnote, color = c.labelPrimary, maxLines = 1)
                        }
                        androidx.compose.material3.DropdownMenu(srcMenu, { srcMenu = false }) {
                            SOURCES.forEach { (k, n) ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Box(Modifier.size(10.dp).clip(CircleShape).background(srcColor(k))); Text(n) } },
                                    onClick = { src = k; srcMenu = false },
                                )
                            }
                        }
                    }
                    Box(Modifier.clip(CircleShape).background(c.bgSunken).dropClickable({ showNote = !showNote }, corner = 100.dp).padding(horizontal = Space.s3, vertical = Space.s2)) {
                        Text(if (showNote) "Nascondi nota" else "+ Nota", style = CanoneType.footnote, color = c.labelSecondary, maxLines = 1)
                    }
                }
                if (showNote) CField("Nota", note, { note = it }, "Dove o cosa")
                AmountPad(amount, Modifier.weight(1f).fillMaxWidth()) { amount = it }
                CButton(if (income) "Aggiungi entrata" else "Aggiungi spesa", {
                    val v = r.finance.toCents(amount)
                    if (v == null || v == 0L) { toast(ctx, "Scrivi un importo"); return@CButton }
                    val k = if (cat.isNotEmpty()) cat else if (income) "Entrate" else r.finance.guess(note)
                    val before = r.finance.state.value.totalCents
                    r.finance.add(if (income) v else -v, k, note.trim(), src)
                    Flash.show(before, if (income) v else -v)
                    amount = ""; note = ""; cat = ""; showNote = false; onSub(null)
                }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
            }
        }
    }

    androidx.compose.animation.AnimatedContent(
        sub,
        transitionSpec = {
            val into = targetState != null
            (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260)) + androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.spring(1f, 380f)) { if (into) it / 6 else -it / 6 }) togetherWith
                (androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)) + androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(220)) { if (into) -it / 8 else it / 8 })
        },
        label = "finanze",
    ) { page ->
        if (page == "add") AddPage() else ScreenList("Finanze") {
            when (page) {
                null -> {
                    // ------------------------------------------------ cruscotto: solo grafici e tasti
                    item {
                        CCard {
                            Column {
                                Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Saldo", style = CanoneType.footnote, color = c.labelTertiary)
                                    EyeToggle(r, 28.dp)
                                }
                                CountUp(m.totalCents, CanoneType.title1.copy(fontSize = 34.sp), c.labelPrimary)
                            }
                            PaceChart(pace, month < 0, c.accent, Modifier.fillMaxWidth().blurIf(MoneyPrivacy.hidden))
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.s4)) { SOURCES.forEach { (k, n) -> Legend(n, srcColor(k)) } }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Action("−", "Spesa", true) { income = false; onSub("add") }
                            Action("+", "Entrata", false) { income = true; onSub("add") }
                            Action("€", "Contanti", false) { cashMode = 1; tally = emptyMap(); onSub("cash") }
                            Action("≡", "Movimenti", false) { onSub("moves") }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                            GlassSegmented(listOf("Mese", "Anno"), if (month < 0) 1 else 0, { month = if (it == 1) -1 else now.get(Calendar.MONTH) })
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                CButton("‹", { step(-1) }, kind = ButtonKind.Tertiary)
                                Text(title, style = CanoneType.title3, color = c.labelPrimary)
                                CButton("›", { step(1) }, kind = ButtonKind.Tertiary)
                            }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                                Kpi("Entrate", earned, Modifier.weight(1f))
                                Kpi("Uscite", spent, Modifier.weight(1f))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                                Kpi("Risparmio", earned - spent, Modifier.weight(1f))
                                Kpi("Al giorno", if (daysIn > 0) spent / daysIn else 0, Modifier.weight(1f))
                            }
                            val diff = if (prevSpent > 0) ((spent - prevSpent) * 100 / prevSpent).toInt() else null
                            if (diff != null) Text(if (diff > 0) "Spesa +$diff% rispetto al periodo prima" else if (diff < 0) "Spesa $diff% rispetto al periodo prima" else "Spesa invariata", style = CanoneType.footnote, color = if (diff > 0) c.critical else c.positive)
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
                            Box(Modifier.blurIf(MoneyPrivacy.hidden)) { BarChart(bars, c.accent, c.labelTertiary) }
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.s4)) { Legend("Entrate", c.accent); Legend("Uscite", c.labelTertiary) }
                        }
                    }
                    item {
                        CCard {
                            SectionTitle("Dove vanno i soldi")
                            Box(Modifier.blurIf(MoneyPrivacy.hidden)) { CategoryDonut(inPeriod.filter { it.cents < 0 }, "Nessuna spesa in questo periodo.") }
                        }
                    }
                }

                "cash" -> {
                    val tallyCents = tally.entries.sumOf { it.key.toLong() * it.value }
                    val spend = cashMode != 2
                    item { GlassSegmented(listOf("Ho speso", "Ho ricevuto"), if (spend) 0 else 1, { cashMode = if (it == 0) 1 else 2; tally = emptyMap() }) }
                    item {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            // numero a slot, come nell'animazione a schermo intero
                            RollingText(eur(tallyCents), CanoneType.title1.copy(fontSize = 48.sp, lineHeight = 60.sp, fontWeight = FontWeight.SemiBold), if (tallyCents == 0L) c.labelTertiary else if (spend) c.critical else c.positive, haptic = true)
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                            NOTES_EUR.chunked(2).forEach { pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                                    pair.forEach { d ->
                                        Banknote(d, tally[d] ?: 0, 0,
                                            { tally = tally + (d to (tally[d] ?: 0) + 1) },
                                            { tally = tally + (d to ((tally[d] ?: 0) - 1).coerceAtLeast(0)) },
                                            Modifier.weight(1f))
                                    }
                                    if (pair.size == 1) Box(Modifier.weight(1f))
                                }
                            }
                            CText(if (spend) "Ogni tocco conta una banconota data. Il − sul contatore ne toglie una." else "Ogni tocco conta una banconota presa. Il − sul contatore ne toglie una.", tertiary = true)
                        }
                    }
                    if (spend) item {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                            CATEGORIES.dropLast(1).forEach { k ->
                                val sel = cat == k
                                Box(Modifier.clip(CircleShape).background(if (sel) c.accent else c.bgSunken).dropClickable({ cat = if (sel) "" else k }, corner = 100.dp).padding(horizontal = Space.s3, vertical = Space.s2)) {
                                    Text(k, style = CanoneType.footnote, color = if (sel) Color.White else c.labelSecondary, maxLines = 1)
                                }
                            }
                        }
                    }
                }

                "add" -> {}

                "moves" -> {
                    item { Back("Movimenti") }
                    item {
                        GlassSegmented(listOf("Mese", "Anno"), if (month < 0) 1 else 0, { month = if (it == 1) -1 else now.get(Calendar.MONTH) })
                    }
                    item {
                        CCard {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                CButton("‹", { step(-1) }, kind = ButtonKind.Tertiary)
                                Text(title, style = CanoneType.title3, color = c.labelPrimary)
                                CButton("›", { step(1) }, kind = ButtonKind.Tertiary)
                            }
                            if (inPeriod.isEmpty()) CText("Niente in questo periodo.", tertiary = true) else {
                                CText("Tieni premuto un movimento per eliminarlo.", tertiary = true)
                                inPeriod.take(60).forEach { t -> TxRow(t, ctx) { r.finance.remove(t.id) } }
                            }
                        }
                    }
                }

                else -> {
                    item { Back("Revolut") }
                    item {
                        CCard {
                            CountUp(m.totalCents, CanoneType.title1, c.labelPrimary)
                            CRow("Pagamenti dalle notifiche", "Ogni pagamento che Revolut ti notifica entra da solo") {
                                GrantMark(listenerEnabled(ctx)) { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                            }
                            CButton("Importa estratto (CSV)", { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth())
                            CField("Imposta il saldo totale", revInput, { revInput = it }, "0,00", number = true)
                            CButton("Salva saldo", {
                                r.finance.toCents(revInput)?.let { r.finance.setTotal(it); revInput = ""; toast(ctx, "Saldo aggiornato") } ?: toast(ctx, "Scrivi un importo")
                            }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
                            CText(
                                "Revolut non offre un'API per i conti personali (solo per quelli aziendali). I movimenti arrivano dalle sue notifiche, " +
                                    "lo storico dall'estratto: in Revolut apri Conto → Estratti → Excel/CSV.",
                                tertiary = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Kpi(label: String, cents: Long, modifier: Modifier) {
    val c = Canone.colors
    Column(modifier.clip(RoundedCornerShape(Radius.xl)).background(c.bgSurface).padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        Text(label, style = CanoneType.footnote, color = c.labelTertiary)
        CountUp(cents, CanoneType.title3, if (cents < 0) c.criticalOnSubtle else c.labelPrimary)
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

internal val SOURCES = listOf("cash" to "Contanti", "revolut" to "Revolut", "manual" to "Altro")
internal fun srcColor(s: String) = when (s) { "cash" -> Color(0xFF34C759); "revolut" -> Color(0xFF3F86FF); else -> Color(0xFFFF9F0A) }
internal fun srcName(s: String) = SOURCES.firstOrNull { it.first == s }?.second ?: "Altro"

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
        Box(Modifier.width(4.dp).height(30.dp).clip(CircleShape).background(srcColor(t.src)))
        Column(Modifier.weight(1f)) {
            Text(t.note.ifEmpty { t.cat }, style = CanoneType.body, color = c.labelPrimary, maxLines = 1)
            Text(t.cat + " · " + DateFormat.getMediumDateFormat(ctx).format(Date(t.ts)) + " · " + srcName(t.src), style = CanoneType.footnote, color = c.labelTertiary, maxLines = 1)
        }
        Text((if (t.cents > 0) "+" else "") + eur(t.cents), style = CanoneType.body, color = if (t.cents > 0) c.positiveOnSubtle else c.labelPrimary, maxLines = 1, modifier = Modifier.blurIf(MoneyPrivacy.hidden))
    }
}

/** Banconota disegnata: colore e taglio come l'euro vero, con la finestra ad arco e i cerchi concentrici. Tocco = una in più, pressione lunga = una in meno. */
@Composable
private fun Banknote(denom: Int, count: Int, owned: Int, onAdd: () -> Unit, onSub: () -> Unit, modifier: Modifier) {
    val touch = rememberDropTouch()
    val base = noteColor(denom)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val res = remember(denom) { ctx.resources.getIdentifier("eur_${denom / 100}", "drawable", ctx.packageName) }
    Box(modifier.aspectRatio(2.05f).dropGestures(touch, onAdd)) {
        Box(Modifier.fillMaxSize().dropDeform(touch).clip(RoundedCornerShape(10.dp))) {
            if (res != 0) androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(res), null, Modifier.fillMaxSize().alpha(if (count > 0) 1f else 0.55f),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            ) else Canvas(Modifier.fillMaxSize()) {
                val a = if (count > 0) 1f else 0.62f
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
            if (res == 0) Text("${denom / 100}", style = CanoneType.title1.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold), color = Color.White, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp))
            if (res == 0) Text("EURO", style = CanoneType.footnote.copy(fontSize = 9.sp, letterSpacing = 1.5.sp), color = Color.White.copy(alpha = 0.8f), modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 8.dp))
        }
        // il contatore e il "−" esplicito: un tocco sulla banconota ne aggiunge una, per toglierne una si tocca il "−"
        if (count > 0) Row(
            Modifier.align(Alignment.TopStart).padding(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.clip(CircleShape).clickable(onClick = onSub).padding(horizontal = 9.dp, vertical = 3.dp)) { Text("−", style = CanoneType.footnote.copy(fontWeight = FontWeight.Bold), color = Color.White) }
            RollingText("×$count", CanoneType.footnote.copy(fontWeight = FontWeight.Medium), Color.White, modifier = Modifier.padding(end = 10.dp))
        }
        if (owned > 0) Text("hai $owned", style = CanoneType.footnote.copy(fontSize = 10.sp), color = Color.White.copy(alpha = 0.85f), modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 10.dp))
    }
}

private fun Color.compositeOver(bg: Color): Color {
    val a = alpha
    return Color(red * a + bg.red * (1 - a), green * a + bg.green * (1 - a), blue * a + bg.blue * (1 - a), 1f)
}

@Composable
private fun rememberGrow(key: Any): Float {
    val a = remember(key) { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(key) { a.animateTo(1f, androidx.compose.animation.core.tween(850, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    return a.value
}

/** Cifra in euro che rotola verso il nuovo valore. */
@Composable
internal fun CountUp(cents: Long, style: androidx.compose.ui.text.TextStyle, color: Color) {
    // importi nascosti = sfocati (non puntini); il valore resta reale sotto la sfocatura
    Box(Modifier.blurIf(MoneyPrivacy.hidden)) { MorphText(eur(cents), style, color, haptic = true) }
}

/** Occhio per nascondere o mostrare gli importi (vale per Home e Finanze). */
@Composable
internal fun EyeToggle(r: Router, size: androidx.compose.ui.unit.Dp = 36.dp, tint: Color = Color.Unspecified) {
    val c = Canone.colors
    val t = rememberDropTouch()
    Box(
        Modifier.size(size).dropGestures(t, { MoneyPrivacy.hidden = !MoneyPrivacy.hidden; r.prefs.hideMoney = MoneyPrivacy.hidden }).drawBehind { drawCircle(c.labelPrimary.copy(alpha = 0.10f * t.press.value)) },
        contentAlignment = Alignment.Center,
    ) {
        Icon24(if (MoneyPrivacy.hidden) Ico.EYE_OFF else Ico.EYE, if (tint != Color.Unspecified) tint else c.labelSecondary, Modifier.size(size * 0.62f))
    }
}

/** Ciambella per categoria con legenda: tocca uno spicchio o una voce e al centro compare l'importo preciso. */
@Composable
internal fun CategoryDonut(list: List<Tx>, empty: String) {
    val c = Canone.colors
    val by = list.groupBy { it.cat }.mapValues { kotlin.math.abs(it.value.sumOf { t -> t.cents }) }.entries.sortedByDescending { it.value }
    if (by.isEmpty()) { CText(empty, tertiary = true); return }
    val total = by.sumOf { it.value }.coerceAtLeast(1)
    var sel by remember(by) { mutableStateOf<Int?>(null) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s4), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            Donut(by.map { it.value.toFloat() to catColor(it.key) }, Modifier.fillMaxSize(), sel) { sel = if (sel == it) null else it }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val s = sel
                Text(if (s == null) "Totale" else by[s].key, style = CanoneType.footnote, color = c.labelTertiary, maxLines = 1)
                CountUp(if (s == null) total else by[s].value, CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), c.labelPrimary)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            by.take(6).forEachIndexed { i, (k, v) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.sm)).background(if (sel == i) c.bgSunken else Color.Transparent)
                        .dropClickable({ sel = if (sel == i) null else i }, corner = Radius.sm).padding(horizontal = Space.s1, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(catColor(k)))
                    Text(k, style = CanoneType.footnote, color = c.labelSecondary, modifier = Modifier.weight(1f), maxLines = 1)
                    Text("${v * 100 / total}%", style = CanoneType.footnote, color = c.labelTertiary)
                }
            }
        }
    }
}

@Composable
private fun BarChart(bars: List<Triple<String, Long, Long>>, income: Color, spend: Color) {
    val c = Canone.colors
    val grow = rememberGrow(bars)
    var sel by remember(bars) { mutableStateOf<Int?>(null) }
    val max = (bars.maxOfOrNull { maxOf(it.second, it.third) } ?: 0L).coerceAtLeast(1L)
    Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        // valore preciso della colonna toccata
        Row(Modifier.fillMaxWidth().height(22.dp), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
            val s = sel
            if (s == null) Text("Tocca una colonna", style = CanoneType.footnote, color = c.labelTertiary)
            else {
                Text(bars[s].first, style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.labelPrimary)
                Text("+" + eur(bars[s].second), style = CanoneType.footnote, color = income, modifier = Modifier.blurIf(MoneyPrivacy.hidden))
                Text("−" + eur(bars[s].third), style = CanoneType.footnote, color = c.labelSecondary, modifier = Modifier.blurIf(MoneyPrivacy.hidden))
            }
        }
        Canvas(Modifier.fillMaxWidth().height(130.dp).pointerInput(bars) {
            detectTapGestures { o -> val i = (o.x / (size.width / bars.size)).toInt().coerceIn(0, bars.size - 1); sel = if (sel == i) null else i }
        }) {
            val slot = size.width / bars.size
            val bw = slot * 0.30f
            for (g in 1..3) drawLine(c.lineSeparator, Offset(0f, size.height * g / 4f), Offset(size.width, size.height * g / 4f), 1f)
            bars.forEachIndexed { i, (_, inc, out) ->
                val x = i * slot + slot / 2f
                val dim = if (sel == null || sel == i) 1f else 0.35f
                if (sel == i) drawRoundRect(c.bgSunken, Offset(i * slot + 2f, 0f), Size(slot - 4f, size.height), CornerRadius(10f))
                val hi = size.height * inc / max * grow; val ho = size.height * out / max * grow
                drawRoundRect(Brush.verticalGradient(listOf(income.copy(alpha = dim), income.copy(alpha = 0.55f * dim)), startY = size.height - hi, endY = size.height), Offset(x - bw - 1f, size.height - hi), Size(bw, hi.coerceAtLeast(2f)), CornerRadius(6f))
                drawRoundRect(Brush.verticalGradient(listOf(spend.copy(alpha = dim), spend.copy(alpha = 0.5f * dim)), startY = size.height - ho, endY = size.height), Offset(x + 1f, size.height - ho), Size(bw, ho.coerceAtLeast(2f)), CornerRadius(6f))
            }
        }
        Row(Modifier.fillMaxWidth()) { bars.forEachIndexed { i, b -> Text(b.first, style = CanoneType.footnote, color = if (sel == i) c.labelPrimary else c.labelTertiary, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
    }
}

@Composable
internal fun Donut(parts: List<Pair<Float, Color>>, modifier: Modifier, selected: Int? = null, onSelect: (Int) -> Unit = {}) {
    val grow = rememberGrow(parts)
    val total = parts.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(1f)
    Canvas(modifier.pointerInput(parts) {
        detectTapGestures { o ->
            val dx = o.x - size.width / 2f; val dy = o.y - size.height / 2f
            var ang = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
            if (ang < 0) ang += 360f
            var acc = 0f
            parts.forEachIndexed { i, (v, _) -> val sw = 360f * v / total; if (ang >= acc && ang < acc + sw) { onSelect(i); return@detectTapGestures }; acc += sw }
        }
    }) {
        val sw = size.minDimension * 0.15f
        var start = -90f
        parts.forEachIndexed { i, (v, col) ->
            val sweep = 360f * v / total * grow
            val on = selected == null || selected == i
            val w = if (selected == i) sw * 1.35f else sw
            drawArc(col.copy(alpha = if (on) 1f else 0.3f), start, (sweep - 2f).coerceAtLeast(0.5f), false, Offset(sw * 0.7f, sw * 0.7f), Size(size.width - sw * 1.4f, size.height - sw * 1.4f), style = Stroke(w, cap = StrokeCap.Butt))
            start += sweep
        }
    }
}

/** Andamento cumulato (entrate − uscite) nel periodo: si disegna progressivamente; tocca o trascina per leggere il valore di un giorno. */
@Composable
private fun Trend(points: List<Float>, accent: Color, modifier: Modifier) {
    val c = Canone.colors
    val grow = rememberGrow(points)
    var sel by remember(points) { mutableStateOf<Int?>(null) }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize().pointerInput(points) {
            fun pick(x: Float) { if (points.size > 1) sel = ((x / size.width) * (points.size - 1)).roundToInt().coerceIn(0, points.size - 1) }
            detectDragGestures(onDragStart = { pick(it.x) }, onDragEnd = { sel = null }, onDragCancel = { sel = null }) { ch, _ -> ch.consume(); pick(ch.position.x) }
        }.pointerInput(points) { detectTapGestures { o -> if (points.size > 1) { val i = ((o.x / size.width) * (points.size - 1)).roundToInt(); sel = if (sel == i) null else i } } }) {
            if (points.size < 2) return@Canvas
            val lo = minOf(points.min(), 0f); val hi = maxOf(points.max(), 1f)
            fun y(v: Float) = size.height * (1f - (v - lo) / (hi - lo)) * 0.86f + size.height * 0.07f
            val n = ((points.size - 1) * grow).coerceAtLeast(0.01f)
            val line = androidx.compose.ui.graphics.Path()
            val area = androidx.compose.ui.graphics.Path()
            var lastX = 0f; var lastY = 0f
            for (i in 0..n.toInt()) {
                val x = size.width * i / (points.size - 1); val yy = y(points[i])
                if (i == 0) { line.moveTo(x, yy); area.moveTo(x, size.height); area.lineTo(x, yy) } else { line.lineTo(x, yy); area.lineTo(x, yy) }
                lastX = x; lastY = yy
            }
            val fi = n.toInt()
            if (fi < points.size - 1) {
                val t = n - fi
                val nx = size.width * (fi + 1) / (points.size - 1); val ny = y(points[fi + 1])
                lastX += (nx - lastX) * t; lastY += (ny - lastY) * t
                line.lineTo(lastX, lastY); area.lineTo(lastX, lastY)
            }
            area.lineTo(lastX, size.height); area.close()
            drawLine(c.lineSeparator, Offset(0f, y(0f)), Offset(size.width, y(0f)), 1f)
            drawPath(area, Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), Color.Transparent)))
            drawPath(line, accent, style = Stroke(3f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            val s = sel
            if (s != null) {
                val x = size.width * s / (points.size - 1); val yy = y(points[s])
                drawLine(c.labelTertiary, Offset(x, 0f), Offset(x, size.height), 1.5f)
                drawCircle(accent, 7f, Offset(x, yy)); drawCircle(Color.White, 3f, Offset(x, yy))
            } else { drawCircle(accent, 6f, Offset(lastX, lastY)); drawCircle(Color.White.copy(alpha = 0.9f), 2.5f, Offset(lastX, lastY)) }
        }
        val s = sel
        if (s != null) Text(
            "${s + 1} · " + eur((points[s] * 100).toLong()),
            style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.labelPrimary,
            modifier = Modifier.align(Alignment.TopStart).clip(RoundedCornerShape(Radius.sm)).background(c.bgSunken).padding(horizontal = Space.s2, vertical = 2.dp).blurIf(MoneyPrivacy.hidden),
        )
    }
}

@Composable
private fun AmountPad(value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    fun press(k: String) {
        val next = when (k) {
            "," -> if (',' in value) value else if (value.isEmpty()) "0," else "$value,"
            else -> {
                val dec = value.substringAfter(',', "")
                if (',' in value && dec.length >= 2) value
                else if (value == "0") k
                else if (value.length >= 9) value
                else value + k
            }
        }
        onChange(next)
    }
    // Tastierino a tutta larghezza: tasti rettangolari come gli elementi della barra, righe uguali.
    val c = Canone.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(",", "0", "⌫")).forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                row.forEach { k ->
                    val t = rememberDropTouch()
                    val shape = RoundedCornerShape(Radius.lg)
                    Box(
                        Modifier.weight(1f).fillMaxHeight().heightIn(min = 52.dp)
                            .dropGestures(t, { if (k == "⌫") onChange(value.dropLast(1)) else press(k) }, if (k == "⌫") ({ onChange("") }) else null)
                            .dropDeform(t).clip(shape).background(c.bgSurface).border(BorderStroke(1.dp, c.lineSeparator), shape),
                        contentAlignment = Alignment.Center,
                    ) { Text(k, style = CanoneType.title2.copy(fontWeight = FontWeight.Medium), color = c.labelPrimary) }
                }
            }
        }
    }
}


internal class PaceData(val cur: List<Float>, val prev: List<Float>, val buckets: Int, val proj: Float?)

/**
 * Ritmo di spesa: quanto hai speso finora (linea piena) contro lo stesso punto del periodo prima (linea grigia), con la previsione
 * a fine periodo (tratteggio). Tocca o trascina per leggere giorno per giorno.
 */
@Composable
internal fun PaceChart(d: PaceData, year: Boolean, accent: Color, modifier: Modifier) {
    val c = Canone.colors
    val grow = rememberGrow(d.cur to d.prev)
    var sel by remember(d) { mutableStateOf<Int?>(null) }
    val months = listOf("gen", "feb", "mar", "apr", "mag", "giu", "lug", "ago", "set", "ott", "nov", "dic")
    fun label(i: Int) = if (year) months[i] else "giorno ${i + 1}"
    val now = d.cur.lastOrNull() ?: 0f
    val prevSame = d.prev.getOrElse((d.cur.size - 1).coerceAtLeast(0)) { 0f }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        // riga di lettura: valore toccato, oppure confronto con il periodo prima e previsione
        Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            val s = sel
            if (s != null) {
                Text(label(s), style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.labelPrimary)
                d.cur.getOrNull(s)?.let { Text(eur((it * 100).toLong()), style = CanoneType.footnote, color = accent) }
                Text("prima " + eur((d.prev.getOrElse(s) { 0f } * 100).toLong()), style = CanoneType.footnote, color = c.labelTertiary)
            } else {
                val diff = now - prevSame
                Text(
                    if (d.cur.isEmpty()) "Nessuna spesa ancora" else if (diff > 0.5f) "+" + eur((diff * 100).toLong()) + " rispetto a prima" else if (diff < -0.5f) "−" + eur((-diff * 100).toLong()) + " rispetto a prima" else "Come il periodo prima",
                    style = CanoneType.footnote.copy(fontWeight = FontWeight.Medium), color = if (diff > 0.5f) c.critical else c.positive,
                )
                Spacer(Modifier.weight(1f))
                d.proj?.let { Text("a fine " + (if (year) "anno" else "mese") + " ~" + eur((it * 100).toLong()), style = CanoneType.footnote, color = c.labelTertiary) }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(120.dp).pointerInput(d) {
            fun pick(x: Float) { sel = ((x / size.width) * (d.buckets - 1)).roundToInt().coerceIn(0, d.buckets - 1) }
            detectDragGestures(onDragStart = { pick(it.x) }, onDragEnd = { sel = null }, onDragCancel = { sel = null }) { ch, _ -> ch.consume(); pick(ch.position.x) }
        }.pointerInput(d) { detectTapGestures { o -> val i = ((o.x / size.width) * (d.buckets - 1)).roundToInt().coerceIn(0, d.buckets - 1); sel = if (sel == i) null else i } }) {
            if (d.buckets < 2) return@Canvas
            val hi = maxOf(d.cur.maxOrNull() ?: 0f, d.prev.maxOrNull() ?: 0f, d.proj ?: 0f, 1f) * 1.08f
            fun x(i: Int) = size.width * i / (d.buckets - 1)
            fun y(v: Float) = size.height * (1f - v / hi)
            // griglia leggera
            for (k in 1..3) drawLine(c.lineSeparator, Offset(0f, size.height * k / 4f), Offset(size.width, size.height * k / 4f), 1f)
            // periodo prima
            val pp = androidx.compose.ui.graphics.Path()
            d.prev.forEachIndexed { i, v -> if (i == 0) pp.moveTo(x(i), y(v * grow)) else pp.lineTo(x(i), y(v * grow)) }
            drawPath(pp, c.labelTertiary.copy(alpha = 0.55f), style = Stroke(2.2f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            // questo periodo
            if (d.cur.isNotEmpty()) {
                val line = androidx.compose.ui.graphics.Path(); val area = androidx.compose.ui.graphics.Path()
                area.moveTo(x(0), size.height)
                d.cur.forEachIndexed { i, v -> val yy = y(v * grow); if (i == 0) line.moveTo(x(i), yy) else line.lineTo(x(i), yy); area.lineTo(x(i), yy) }
                area.lineTo(x(d.cur.size - 1), size.height); area.close()
                drawPath(area, Brush.verticalGradient(listOf(accent.copy(alpha = 0.30f), Color.Transparent)))
                drawPath(line, accent, style = Stroke(3.2f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                // previsione
                d.proj?.let { p ->
                    val lx = x(d.cur.size - 1); val ly = y(d.cur.last() * grow)
                    drawLine(accent.copy(alpha = 0.6f), Offset(lx, ly), Offset(x(d.buckets - 1), y(p * grow)), 2.4f, StrokeCap.Round, androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                    drawCircle(accent.copy(alpha = 0.6f), 4f, Offset(x(d.buckets - 1), y(p * grow)))
                }
                drawCircle(accent, 6f, Offset(x(d.cur.size - 1), y(d.cur.last() * grow))); drawCircle(Color.White, 2.5f, Offset(x(d.cur.size - 1), y(d.cur.last() * grow)))
            }
            sel?.let { s ->
                drawLine(c.labelTertiary, Offset(x(s), 0f), Offset(x(s), size.height), 1.5f)
                d.cur.getOrNull(s)?.let { drawCircle(accent, 7f, Offset(x(s), y(it))); drawCircle(Color.White, 3f, Offset(x(s), y(it))) }
                drawCircle(c.labelTertiary, 5f, Offset(x(s), y(d.prev.getOrElse(s) { 0f })))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
            Legend(if (year) "Quest'anno" else "Questo mese", accent)
            Legend(if (year) "Anno prima" else "Mese prima", c.labelTertiary)
            if (d.proj != null) Legend("Previsione", accent.copy(alpha = 0.5f))
        }
    }
}
