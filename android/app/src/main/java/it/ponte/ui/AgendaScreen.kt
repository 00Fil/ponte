package it.ponte.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.ponte.CalendarBridge
import it.ponte.DevCal
import it.ponte.DevEvent
import it.ponte.Ev
import it.ponte.Router
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

private const val DAY = 86_400_000L

/** Una riga dell'agenda: evento di Relay, evento di Google Calendar, scadenza di un compito o esame. */
private class Item(val key: String, val kind: Int, val title: String, val start: Long, val end: Long, val allDay: Boolean, val color: Color, val place: String, val done: Boolean = false, val ref: Any? = null)

private class Draft(
    val id: String = "", val devId: Long = -1, val calId: Long = 0, var title: String = "", var start: Long = 0, var end: Long = 0, var allDay: Boolean = false,
    var color: Int = 0, var place: String = "", var note: String = "", var remind: Int = 10,
)

private val REMINDERS = listOf(-1 to "Nessuno", 0 to "All'ora", 10 to "10 min prima", 30 to "30 min prima", 60 to "1 ora prima", 1440 to "1 giorno prima")

@Composable
fun AgendaScreen(r: Router, sub: String?, onSub: (String?) -> Unit) {
    val c = Canone.colors
    val ctx = LocalContext.current
    val plan by r.plan.state.collectAsState()
    val bridge = remember { CalendarBridge(ctx) }
    var granted by remember { mutableStateOf(bridge.granted()) }
    var tick by remember { mutableIntStateOf(0) }
    var mode by remember { mutableIntStateOf(0) }                       // 0 mese, 1 prossimi
    val now = remember { System.currentTimeMillis() }
    var cursor by remember { mutableLongStateOf(Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis) }
    var selected by remember { mutableLongStateOf(dayStart(now)) }
    var draft by remember { mutableStateOf<Draft?>(null) }
    var cals by remember { mutableStateOf<List<DevCal>>(emptyList()) }
    var hidden by remember { mutableStateOf(bridge.hidden) }
    var target by remember { mutableStateOf(bridge.target) }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = bridge.granted(); tick++ }
    DisposableEffect(granted) {
        val o = bridge.observe { tick++ }
        onDispose { bridge.unobserve(o) }
    }

    // Griglia del mese (da lunedì), 6 righe.
    val first = Calendar.getInstance().apply { timeInMillis = cursor }
    val offset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val gridStart = addDays(dayStart(cursor), -offset)
    val rangeFrom = if (mode == 0) gridStart else dayStart(now)
    val rangeTo = if (mode == 0) gridStart + 42 * DAY else dayStart(now) + 45 * DAY

    var dev by remember { mutableStateOf<List<DevEvent>>(emptyList()) }
    LaunchedEffect(rangeFrom, rangeTo, tick, granted, hidden) {
        dev = if (granted) withContext(Dispatchers.IO) { bridge.events(rangeFrom, rangeTo) } else emptyList()
    }
    LaunchedEffect(granted, tick, sub) { cals = if (granted) withContext(Dispatchers.IO) { bridge.calendars() } else emptyList() }

    val subjects = plan.subjects.associateBy { it.id }
    val items = remember(plan, dev, rangeFrom, rangeTo) {
        val out = ArrayList<Item>()
        plan.events.forEach { e -> if (e.start < rangeTo && maxOf(e.end, e.start + 60_000) > rangeFrom) out += Item("r" + e.id, 0, e.title.ifBlank { "(senza titolo)" }, e.start, e.end, e.allDay, planColor(e.color), e.place, ref = e) }
        dev.forEach { e -> out += Item("g${e.id}-${e.start}", 1, e.title, e.start, e.end, e.allDay, Color(e.color or (0xFF shl 24)), e.place, ref = e) }
        plan.tasks.forEach { t -> if (t.due in rangeFrom until rangeTo) out += Item("t" + t.id, 2, t.title, dayStart(t.due), dayStart(t.due) + DAY, true, subjects[t.subject]?.let { planColor(it.color) } ?: Color(0xFF9AA19A), subjects[t.subject]?.name.orEmpty(), t.done, t) }
        plan.exams.forEach { e -> if (e.date in rangeFrom until rangeTo) out += Item("x" + e.id, 3, "Esame: " + e.title, e.date, e.date + 60 * 60_000L, false, Color(0xFFD9534F), subjects[e.subject]?.name.orEmpty(), ref = e) }
        out.sortedWith(compareBy<Item> { it.start }.thenBy { !it.allDay })
    }
    fun on(day: Long) = items.filter { it.start < day + DAY && maxOf(it.end, it.start + 60_000) > day }.sortedWith(compareBy<Item> { !it.allDay }.thenBy { it.start })

    fun newDraft(day: Long): Draft {
        val t = if (day == dayStart(now)) Calendar.getInstance().apply { timeInMillis = now; add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        else day + 9 * 3_600_000L
        return Draft(calId = target, start = t, end = t + 3_600_000L)
    }
    fun open(i: Item) {
        when (i.kind) {
            0 -> (i.ref as Ev).let { e -> draft = Draft(e.id, -1, 0, e.title, e.start, if (e.allDay) e.end - DAY else e.end, e.allDay, e.color, e.place, e.note, e.remind); onSub("edit") }
            1 -> (i.ref as DevEvent).let { e ->
                val cal = cals.firstOrNull { it.id == e.calId }
                if (cal?.writable == true) { draft = Draft("", e.id, e.calId, e.title, e.start, if (e.allDay) e.end - DAY else e.end, e.allDay, 0, e.place, e.note, -1); onSub("edit") }
                else try { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, e.id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }
            2 -> (i.ref as it.ponte.Task).let { t -> r.plan.saveTask(t.copy(done = !t.done)) }
            else -> toast(ctx, "Gli esami si modificano in Studio")
        }
    }

    LaunchedEffect(sub) { if (sub == "study") { mode = 2; onSub(null) } }
    AnimatedContent(
        sub,
        transitionSpec = {
            val into = targetState != null
            (fadeIn(tween(260)) + slideInHorizontally(spring(1f, 380f)) { if (into) it / 6 else -it / 6 }) togetherWith
                (fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { if (into) -it / 8 else it / 8 })
        },
        label = "agenda",
    ) { page ->
        when (page) {
            "edit" -> draft?.let { d -> EditorPage(r, d, cals, bridge, ctx, plan.events.size) { onSub(null); tick++ } } ?: Box(Modifier.fillMaxSize())
            "set" -> StudySettings(r) { onSub(null) }
            "cals" -> CalendarsPage(ctx, bridge, granted, cals, hidden, target, { hidden = it; bridge.hidden = it; tick++ }, { target = it; bridge.target = it }, { ask.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }) { onSub(null) }
            else -> ScreenList("Agenda") {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
                        GlassSegmented(listOf("Mese", "Prossimi", "Studio"), mode, { mode = it }, Modifier.weight(1f))
                        CButton("Calendari", { onSub("cals") }, kind = ButtonKind.Secondary)
                    }
                }
                if (mode == 2) studyItems(r, plan) { onSub("set") }
                if (!granted && mode != 2) item {
                    CCard {
                        Text("Google Calendar", style = CanoneType.bodyMedium, color = c.labelPrimary)
                        Text("Collega i calendari dell'account Google già sul telefono: gli eventi si vedono qui e quelli nuovi finiscono su Google.", style = CanoneType.footnote, color = c.labelSecondary)
                        CButton("Collega", { ask.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }, kind = ButtonKind.Primary)
                    }
                }
                if (mode == 2) { } else if (mode == 0) {
                    item {
                        CCard {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                CButton("‹", { cursor = Calendar.getInstance().apply { timeInMillis = cursor; add(Calendar.MONTH, -1) }.timeInMillis }, kind = ButtonKind.Tertiary)
                                Text(cap(fmt(cursor, "LLLL yyyy")), style = CanoneType.title3, color = c.labelPrimary, modifier = Modifier.dropClickable({
                                    cursor = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis; selected = dayStart(now)
                                }))
                                CButton("›", { cursor = Calendar.getInstance().apply { timeInMillis = cursor; add(Calendar.MONTH, 1) }.timeInMillis }, kind = ButtonKind.Tertiary)
                            }
                            Row(Modifier.fillMaxWidth()) {
                                listOf("L", "M", "M", "G", "V", "S", "D").forEach { Text(it, Modifier.weight(1f), style = CanoneType.footnote, color = c.labelTertiary, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                            }
                            val curMonth = first.get(Calendar.MONTH)
                            for (row in 0 until 6) Row(Modifier.fillMaxWidth()) {
                                for (col in 0 until 7) {
                                    val day = addDays(gridStart, row * 7 + col)
                                    val cal = Calendar.getInstance().apply { timeInMillis = day }
                                    val inMonth = cal.get(Calendar.MONTH) == curMonth
                                    val isSel = day == selected
                                    val isToday = day == dayStart(now)
                                    val dots = on(day).map { it.color }.distinct().take(3)
                                    val bg by animateColorAsState(if (isSel) c.accent else Color.Transparent, spring(1f, 420f), label = "g")
                                    Column(
                                        Modifier.weight(1f).height(48.dp).padding(2.dp).clip(RoundedCornerShape(Radius.md)).background(bg).dropClickable({ selected = day }, corner = Radius.md),
                                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                                    ) {
                                        Text(
                                            cal.get(Calendar.DAY_OF_MONTH).toString(), style = CanoneType.body.copy(fontWeight = if (isToday) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal),
                                            color = if (isSel) Color.White else if (isToday) c.accent else if (inMonth) c.labelPrimary else c.labelDisabled,
                                        )
                                        Row(Modifier.height(8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                                            dots.forEach { Box(Modifier.size(5.dp).clip(CircleShape).background(if (isSel) Color.White else it)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                SectionTitle(dayLabel(selected))
                                Text(cap(fmt(selected, "d MMMM yyyy")), style = CanoneType.footnote, color = c.labelTertiary)
                            }
                            CButton("＋ Evento", { draft = newDraft(selected); onSub("edit") }, kind = ButtonKind.Primary)
                        }
                    }
                    val list = on(selected)
                    if (list.isEmpty()) item { Text("Niente in programma.", style = CanoneType.footnote, color = c.labelTertiary) }
                    items(list.size, key = { list[it].key + selected }) { i -> Row1(list[i], ctx, false) { open(list[i]) } }
                } else {
                    item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { CButton("＋ Evento", { draft = newDraft(dayStart(now)); onSub("edit") }, kind = ButtonKind.Primary) } }
                    val days = (0 until 45).map { dayStart(now) + it * DAY }.map { it to on(it) }.filter { it.second.isNotEmpty() }
                    if (days.isEmpty()) item { Text("Niente nei prossimi 45 giorni.", style = CanoneType.footnote, color = c.labelTertiary) }
                    days.forEach { (day, list) ->
                        item(key = "h$day") { Text(dayLabel(day), style = CanoneType.bodyMedium, color = c.labelSecondary, modifier = Modifier.padding(top = Space.s2)) }
                        items(list.size, key = { list[it].key + day }) { i -> Row1(list[i], ctx, true) { open(list[i]) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Row1(i: Item, ctx: android.content.Context, showDate: Boolean, onClick: () -> Unit) {
    val c = Canone.colors
    val shape = RoundedCornerShape(Radius.lg)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.bgSurface).dropClickable(onClick, corner = Radius.lg).padding(Space.s3),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Box(Modifier.width(4.dp).height(38.dp).clip(CircleShape).background(i.color))
        Column(Modifier.weight(1f)) {
            Text(i.title, style = CanoneType.bodyMedium, color = if (i.done) c.labelTertiary else c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = if (i.done) TextDecoration.LineThrough else null)
            val whenText = when { i.kind == 2 -> "Scadenza" + (if (i.place.isNotEmpty()) " · " + i.place else ""); i.allDay -> "Tutto il giorno"; else -> fmt(i.start, "HH:mm") + " – " + fmt(i.end, "HH:mm") }
            Text(whenText + (if (i.kind in 0..1 && i.place.isNotEmpty()) " · " + i.place else ""), style = CanoneType.footnote, color = c.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(when (i.kind) { 0 -> "Relay"; 1 -> "Google"; 2 -> if (i.done) "Fatto" else "Compito"; else -> "Esame" }, style = CanoneType.footnote.copy(fontSize = 11.sp), color = c.labelTertiary)
    }
}

@Composable
private fun EditorPage(r: Router, d: Draft, cals: List<DevCal>, bridge: CalendarBridge, ctx: android.content.Context, @Suppress("UNUSED_PARAMETER") n: Int, done: () -> Unit) {
    val c = Canone.colors
    var title by remember(d) { mutableStateOf(d.title) }
    var start by remember(d) { mutableLongStateOf(d.start) }
    var end by remember(d) { mutableLongStateOf(d.end) }
    var allDay by remember(d) { mutableStateOf(d.allDay) }
    var color by remember(d) { mutableIntStateOf(d.color) }
    var place by remember(d) { mutableStateOf(d.place) }
    var note by remember(d) { mutableStateOf(d.note) }
    var remind by remember(d) { mutableIntStateOf(d.remind) }
    var cal by remember(d) { mutableStateOf(d.calId) }
    val existing = d.id.isNotEmpty() || d.devId > 0
    val google = cal != 0L
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = Space.s4, end = Space.s4, top = Space.s3, bottom = Space.s5 + LocalBarInset.current),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            BackRow("Agenda", if (existing) "Modifica" else "Nuovo evento") { done() }
            CField("Titolo", title, { title = it }, "Es. Lezione di analisi")
            CSwitchRow("Tutto il giorno", null, allDay) { allDay = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                PickField(if (allDay) "Dal" else "Giorno", fmt(start, "EEE d MMM yyyy"), Modifier.weight(1f)) {
                    pickDate(ctx, start) { t -> val len = end - start; start = t; if (allDay) { if (end < start) end = start } else end = start + len.coerceAtLeast(0) }
                }
                if (allDay) PickField("Al", fmt(end, "EEE d MMM yyyy"), Modifier.weight(1f)) { pickDate(ctx, end) { t -> end = maxOf(t, dayStart(start)) } }
            }
            if (!allDay) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                PickField("Inizio", fmt(start, "HH:mm"), Modifier.weight(1f)) { pickTime(ctx, start) { t -> val len = (end - start).coerceAtLeast(30 * 60_000L); start = t; end = t + len } }
                PickField("Fine", fmt(end, "HH:mm"), Modifier.weight(1f)) { pickTime(ctx, end) { t -> end = if (t <= start) t + DAY else t } }
            }
            CField("Luogo", place, { place = it }, "Aula, indirizzo o link")
            CField("Note", note, { note = it }, "Facoltative")
            if (!google) Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Text("Colore", style = CanoneType.footnote, color = c.labelSecondary)
                ColorDots(color) { color = it }
            }
            if (!existing || d.id.isNotEmpty() || d.devId > 0) Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Text("Promemoria", style = CanoneType.footnote, color = c.labelSecondary)
                if (!(existing && google)) ChipRow { REMINDERS.forEach { (m, l) -> Chip(l, remind == m) { remind = m } } }
                else Text("Gestito da Google Calendar", style = CanoneType.footnote, color = c.labelTertiary)
            }
            if (!existing) Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Text("Salva in", style = CanoneType.footnote, color = c.labelSecondary)
                ChipRow {
                    Chip("Relay", cal == 0L) { cal = 0L }
                    cals.filter { it.writable }.forEach { k -> Chip(k.name.ifBlank { k.account }, cal == k.id, planColor(0).let { Color(k.color or (0xFF shl 24)) }) { cal = k.id } }
                }
                Text(if (google) "Finisce nel calendario del tuo account Google." else "Resta in Relay e si sincronizza con i tuoi dispositivi.", style = CanoneType.footnote, color = c.labelTertiary)
            }
            CButton(if (existing) "Salva" else "Aggiungi", {
                if (title.isBlank()) { toast(ctx, "Scrivi un titolo"); return@CButton }
                val s = if (allDay) dayStart(start) else start
                val e = if (allDay) dayStart(maxOf(end, start)) + DAY else maxOf(end, start + 15 * 60_000L)
                when {
                    d.devId > 0 -> bridge.update(d.devId, title.trim(), s, e, allDay, place.trim(), note.trim())
                    cal != 0L && d.id.isEmpty() -> if (bridge.insert(cal, title.trim(), s, e, allDay, place.trim(), note.trim(), remind) < 0) { toast(ctx, "Non riesco a scrivere su Google Calendar"); return@CButton }
                    else -> r.plan.saveEvent(Ev(d.id, s, e, allDay, title.trim(), color, note.trim(), place.trim(), remind))
                }
                done()
            }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
            if (existing) CButton("Elimina", {
                if (d.devId > 0) bridge.delete(d.devId) else r.plan.removeEvent(d.id)
                done()
            }, Modifier.fillMaxWidth(), kind = ButtonKind.Destructive)
        }
    }
}

@Composable
private fun CalendarsPage(
    ctx: android.content.Context, bridge: CalendarBridge, granted: Boolean, cals: List<DevCal>, hidden: Set<Long>, target: Long,
    onHidden: (Set<Long>) -> Unit, onTarget: (Long) -> Unit, ask: () -> Unit, back: () -> Unit,
) {
    val c = Canone.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = Space.s4, end = Space.s4, top = Space.s3, bottom = Space.s5 + LocalBarInset.current),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            BackRow("Agenda", "Calendari") { back() }
            if (!granted) {
                CCard {
                    Text("Google Calendar", style = CanoneType.bodyMedium, color = c.labelPrimary)
                    Text("Relay legge e scrive i calendari degli account già presenti sul telefono. Nessun accesso da fare qui: basta consentire l'uso del calendario.", style = CanoneType.footnote, color = c.labelSecondary)
                    CButton("Consenti", ask, kind = ButtonKind.Primary)
                }
            } else {
                if (cals.isEmpty()) CCard {
                    Text("Nessun calendario trovato", style = CanoneType.bodyMedium, color = c.labelPrimary)
                    Text("Aggiungi un account Google in Impostazioni di Android → Password e account e attiva la sincronizzazione del Calendario.", style = CanoneType.footnote, color = c.labelSecondary)
                }
                cals.groupBy { it.account }.forEach { (acc, list) ->
                    CCard {
                        Text(acc.ifBlank { "Sul telefono" }, style = CanoneType.footnote, color = c.labelTertiary)
                        list.forEach { k -> CSwitchRow(k.name.ifBlank { acc }, if (k.writable) null else "Sola lettura", k.id !in hidden) { on -> onHidden(if (on) hidden - k.id else hidden + k.id) } }
                    }
                }
                if (cals.any { it.writable }) CCard {
                    Text("Nuovi eventi", style = CanoneType.bodyMedium, color = c.labelPrimary)
                    Text("Dove si salva un evento quando premi ＋ (puoi cambiarlo ogni volta).", style = CanoneType.footnote, color = c.labelSecondary)
                    ChipRow {
                        Chip("Relay", target == 0L) { onTarget(0L) }
                        cals.filter { it.writable }.forEach { k -> Chip(k.name.ifBlank { k.account }, target == k.id, Color(k.color or (0xFF shl 24))) { onTarget(k.id) } }
                    }
                }
                CButton("Apri Google Calendar", {
                    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { toast(ctx, "Nessuna app calendario") }
                }, Modifier.fillMaxWidth())
            }
        }
    }
}
