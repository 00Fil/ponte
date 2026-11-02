package it.ponte.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.ponte.Card
import it.ponte.Exam
import it.ponte.PlanState
import it.ponte.Router
import it.ponte.Subject
import it.ponte.Task
import kotlinx.coroutines.delay

private const val DAYMS = 86_400_000L
private val BOX_DAYS = listOf(0, 1, 3, 7, 14, 30)   // 0 = ripassa tra poco

private fun minutesOn(p: PlanState, from: Long, to: Long) = p.sessions.filter { it.start in from until to }.sumOf { it.minutes }
private fun streak(p: PlanState): Int {
    val days = p.sessions.map { dayStart(it.start) }.toSet()
    var d = dayStart(System.currentTimeMillis())
    if (d !in days) d = addDays(d, -1)
    var n = 0
    while (d in days) { n++; d = addDays(d, -1) }
    return n
}
private fun hm(min: Int) = if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
private fun daysTo(t: Long): String {
    val n = ((dayStart(t) - dayStart(System.currentTimeMillis())) / DAYMS).toInt()
    return when { n < 0 -> "passato"; n == 0 -> "oggi"; n == 1 -> "domani"; else -> "tra $n giorni" }
}

@Composable
fun StudyScreen(r: Router, sub: String?, onSub: (String?) -> Unit) {
    val c = Canone.colors
    val ctx = LocalContext.current
    val p by r.plan.state.collectAsState()
    var subj by remember { mutableStateOf(p.timer.subject) }
    var reviewSubject by remember { mutableStateOf("") }

    AnimatedContent(
        sub,
        transitionSpec = {
            val into = targetState != null
            (fadeIn(tween(260)) + slideInHorizontally(spring(1f, 380f)) { if (into) it / 6 else -it / 6 }) togetherWith
                (fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { if (into) -it / 8 else it / 8 })
        },
        label = "studio",
    ) { page ->
        when (page) {
            "subjects" -> Sheet("Materie", onSub) { SubjectsPage(r, p) }
            "tasks" -> Sheet("Compiti", onSub) { TasksPage(r, p, ctx) }
            "exams" -> Sheet("Esami", onSub) { ExamsPage(r, p, ctx) }
            "cards" -> Sheet("Flashcard", onSub) { CardsPage(r, p, { reviewSubject = it; onSub("review") }) }
            "review" -> Sheet("Ripasso", { onSub("cards") }, parent = "Flashcard") { ReviewPage(r, p, reviewSubject) { onSub("cards") } }
            "stats" -> Sheet("Statistiche", onSub) { StatsPage(p) }
            "set" -> Sheet("Impostazioni", onSub) { SettingsPage(r, p) }
            else -> ScreenList("Studio") {
                item { PomodoroCard(r, p, subj, { subj = it }) { onSub("set") } }
                item { TodayCard(p) }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Action("", "Materie", false, glyph = 3) { onSub("subjects") }
                        Action("", "Compiti", true, glyph = 2) { onSub("tasks") }
                        Action("", "Esami", false, glyph = 10) { onSub("exams") }
                        Action("", "Flashcard", false, glyph = 9) { onSub("cards") }
                        Action("", "Dati", false, glyph = 6) { onSub("stats") }
                    }
                }
                val soon = p.exams.filter { it.date >= dayStart(System.currentTimeMillis()) }.take(3)
                val open = p.tasks.filter { !it.done }.sortedBy { if (it.due == 0L) Long.MAX_VALUE else it.due }.take(4)
                if (soon.isNotEmpty() || open.isNotEmpty()) item {
                    CCard {
                        if (soon.isNotEmpty()) {
                            SectionTitle("Esami in arrivo")
                            soon.forEach { e -> SubRow(p, e.subject, e.title, daysTo(e.date) + " · " + fmt(e.date, "d MMM HH:mm")) }
                        }
                        if (open.isNotEmpty()) {
                            SectionTitle("Da fare")
                            open.forEach { t -> SubRow(p, t.subject, t.title, if (t.due > 0) "Scadenza " + daysTo(t.due) else "Senza scadenza") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubRow(p: PlanState, subject: String, title: String, note: String) {
    val c = Canone.colors
    val s = p.subjects.firstOrNull { it.id == subject }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(s?.let { planColor(it.color) } ?: c.labelTertiary))
        Column(Modifier.weight(1f)) {
            Text(title, style = CanoneType.body, color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(note, style = CanoneType.footnote, color = c.labelTertiary, maxLines = 1)
        }
    }
}

/** Pagina secondaria a scorrimento con "‹ Studio" per tornare. */
@Composable
private fun Sheet(title: String, onSub: (String?) -> Unit, parent: String = "Studio", content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = Space.s4, end = Space.s4, top = Space.s3, bottom = Space.s5 + LocalBarInset.current),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            BackRow(parent, title) { onSub(null) }
            content()
        }
    }
}

// -------------------------------------------------------------------------------------------------------------------- pomodoro
@Composable
private fun PomodoroCard(r: Router, p: PlanState, subj: String, onSubj: (String) -> Unit, onSettings: () -> Unit) {
    val c = Canone.colors
    val t = p.timer
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(t.running, t.endAt) {
        while (t.running) {
            nowMs = System.currentTimeMillis()
            if (nowMs >= t.endAt) { r.plan.timerDone(); break }
            delay(250)
        }
    }
    val total = (when (t.phase) { "short" -> p.cfg("short", 5); "long" -> p.cfg("long", 15); else -> p.cfg("focus", 25) }) * 60_000L
    val left = if (t.running) (t.endAt - nowMs).coerceIn(0L, total) else total
    val progress by animateFloatAsState(if (t.running) 1f - left.toFloat() / total else 0f, tween(300), label = "anello")
    val focus = t.phase == "focus"
    val tint = if (focus) c.accent else c.positive
    val rounds = p.cfg("rounds", 4)
    val secs = ((left + 999) / 1000).toInt()
    val mine = t.device.isEmpty() || t.device == r.deviceId

    CCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(when (t.phase) { "short" -> "Pausa"; "long" -> "Pausa lunga"; else -> "Concentrazione" }, style = CanoneType.bodyMedium, color = tint)
            CButton("Durate", onSettings, kind = ButtonKind.Tertiary)
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(232.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = 14.dp.toPx()
                    val tl = Offset(w / 2, w / 2); val sz = Size(size.width - w, size.height - w)
                    drawArc(c.bgSunken, 0f, 360f, false, tl, sz, style = Stroke(w))
                    if (progress > 0.002f) drawArc(tint, -90f, 360f * progress, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RollingText("%02d:%02d".format(secs / 60, secs % 60), CanoneType.title1.copy(fontSize = 52.sp, lineHeight = 60.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), c.labelPrimary)
                    Text(
                        if (t.running && !mine) "su un altro dispositivo" else p.subjects.firstOrNull { it.id == subj }?.name ?: "Nessuna materia",
                        style = CanoneType.footnote, color = c.labelTertiary,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until rounds) Box(Modifier.padding(horizontal = 3.dp).size(8.dp).clip(CircleShape).background(if (i < t.cycle % rounds || (t.phase == "long" && t.cycle > 0)) tint else c.bgSunken))
        }
        if (focus && !t.running) ChipRow {
            Chip("Nessuna", subj.isEmpty()) { onSubj("") }
            p.subjects.forEach { s -> Chip(s.name, subj == s.id, planColor(s.color)) { onSubj(s.id) } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            if (t.running) CButton("Ferma", { r.plan.timerStop() }, Modifier.weight(1f))
            else CButton("Avvia", { r.plan.timerStart(subj) }, Modifier.weight(1f), kind = ButtonKind.Primary)
            CButton(if (focus) "Salta alla pausa" else "Salta la pausa", {
                r.plan.timerPhase(if (focus) (if ((t.cycle + 1) % rounds == 0) "long" else "short") else "focus")
            }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TodayCard(p: PlanState) {
    val c = Canone.colors
    val today = minutesOn(p, dayStart(System.currentTimeMillis()), Long.MAX_VALUE)
    val goal = p.cfg("goal", 120)
    val frac by animateFloatAsState((today.toFloat() / goal).coerceIn(0f, 1f), tween(600), label = "obiettivo")
    CCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Oggi", style = CanoneType.footnote, color = c.labelTertiary)
                Text(hm(today), style = CanoneType.title3, color = c.labelPrimary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Serie", style = CanoneType.footnote, color = c.labelTertiary)
                val s = streak(p)
                Text(if (s == 1) "1 giorno" else "$s giorni", style = CanoneType.title3, color = c.labelPrimary)
            }
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(c.bgSunken)) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(CircleShape).background(if (frac >= 1f) c.positive else c.accent))
        }
        Text("Obiettivo ${hm(goal)}" + if (today >= goal) " · raggiunto" else "", style = CanoneType.footnote, color = c.labelTertiary)
    }
}

// -------------------------------------------------------------------------------------------------------------------- materie
@Composable
private fun SubjectsPage(r: Router, p: PlanState) {
    val c = Canone.colors
    var name by remember { mutableStateOf("") }
    var color by remember { mutableIntStateOf(p.subjects.size) }
    CCard {
        CField("Nuova materia", name, { name = it }, "Es. Analisi 1")
        ColorDots(color % PLAN_COLORS.size) { color = it }
        CButton("Aggiungi", { if (name.isNotBlank()) { r.plan.saveSubject(Subject("", name.trim(), color % PLAN_COLORS.size)); name = ""; color = p.subjects.size + 1 } }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
    }
    if (p.subjects.isEmpty()) Text("Aggiungi le materie: servono a dividere le ore di studio, i compiti e le flashcard.", style = CanoneType.footnote, color = c.labelTertiary)
    p.subjects.forEach { s ->
        val week = minutesOn(p, dayStart(System.currentTimeMillis()) - 6 * DAYMS, Long.MAX_VALUE).let { _ -> p.sessions.filter { it.subject == s.id && it.start >= dayStart(System.currentTimeMillis()) - 6 * DAYMS }.sumOf { it.minutes } }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(c.bgSurface).padding(Space.s3), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(planColor(s.color)))
            Column(Modifier.weight(1f)) {
                Text(s.name, style = CanoneType.bodyMedium, color = c.labelPrimary, maxLines = 1)
                Text("Ultimi 7 giorni: " + hm(week), style = CanoneType.footnote, color = c.labelTertiary)
            }
            CButton("×", { r.plan.removeSubject(s.id) }, kind = ButtonKind.Tertiary)
        }
    }
}

// -------------------------------------------------------------------------------------------------------------------- compiti
@Composable
private fun TasksPage(r: Router, p: PlanState, ctx: android.content.Context) {
    val c = Canone.colors
    var title by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var due by remember { mutableLongStateOf(0L) }
    CCard {
        CField("Nuovo compito", title, { title = it }, "Es. Esercizi capitolo 4")
        if (p.subjects.isNotEmpty()) ChipRow {
            Chip("Nessuna materia", subject.isEmpty()) { subject = "" }
            p.subjects.forEach { s -> Chip(s.name, subject == s.id, planColor(s.color)) { subject = s.id } }
        }
        PickField("Scadenza", if (due == 0L) "Nessuna" else fmt(due, "EEE d MMM yyyy")) { pickDate(ctx, if (due == 0L) System.currentTimeMillis() else due) { due = dayStart(it) } }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), modifier = Modifier.fillMaxWidth()) {
            if (due != 0L) CButton("Senza scadenza", { due = 0L }, Modifier.weight(1f))
            CButton("Aggiungi", { if (title.isNotBlank()) { r.plan.saveTask(Task("", title.trim(), subject, due, false)); title = ""; due = 0L } }, Modifier.weight(1f), kind = ButtonKind.Primary)
        }
    }
    val sorted = p.tasks.sortedWith(compareBy<Task> { it.done }.thenBy { if (it.due == 0L) Long.MAX_VALUE else it.due })
    if (sorted.isEmpty()) Text("Nessun compito. Quelli con scadenza compaiono anche nell'Agenda.", style = CanoneType.footnote, color = c.labelTertiary)
    sorted.forEach { t ->
        val s = p.subjects.firstOrNull { it.id == t.subject }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(c.bgSurface).dropClickable({ r.plan.saveTask(t.copy(done = !t.done)) }, corner = Radius.lg).padding(Space.s3),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(if (t.done) c.accent else c.bgSunken), contentAlignment = Alignment.Center) { if (t.done) Text("✓", color = Color.White, style = CanoneType.footnote) }
            Column(Modifier.weight(1f)) {
                Text(t.title, style = CanoneType.body, color = if (t.done) c.labelTertiary else c.labelPrimary, textDecoration = if (t.done) TextDecoration.LineThrough else null, maxLines = 2)
                val over = !t.done && t.due > 0 && t.due < dayStart(System.currentTimeMillis())
                Text(listOfNotNull(s?.name, if (t.due > 0) daysTo(t.due) else null).joinToString(" · "), style = CanoneType.footnote, color = if (over) c.critical else c.labelTertiary)
            }
            CButton("×", { r.plan.removeTask(t.id) }, kind = ButtonKind.Tertiary)
        }
    }
}

// -------------------------------------------------------------------------------------------------------------------- esami
@Composable
private fun ExamsPage(r: Router, p: PlanState, ctx: android.content.Context) {
    val c = Canone.colors
    var title by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var date by remember { mutableLongStateOf(dayStart(System.currentTimeMillis()) + 7 * DAYMS + 9 * 3_600_000L) }
    CCard {
        CField("Nuovo esame", title, { title = it }, "Es. Scritto di Fisica")
        if (p.subjects.isNotEmpty()) ChipRow {
            Chip("Nessuna materia", subject.isEmpty()) { subject = "" }
            p.subjects.forEach { s -> Chip(s.name, subject == s.id, planColor(s.color)) { subject = s.id } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            PickField("Giorno", fmt(date, "EEE d MMM yyyy"), Modifier.weight(1f)) { pickDate(ctx, date) { date = it } }
            PickField("Ora", fmt(date, "HH:mm"), Modifier.weight(1f)) { pickTime(ctx, date) { date = it } }
        }
        CButton("Aggiungi", { if (title.isNotBlank()) { r.plan.saveExam(Exam("", title.trim(), subject, date)); title = "" } }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
    }
    if (p.exams.isEmpty()) Text("Gli esami compaiono nell'Agenda e ti avvisano la sera prima.", style = CanoneType.footnote, color = c.labelTertiary)
    p.exams.forEach { e ->
        val s = p.subjects.firstOrNull { it.id == e.subject }
        val n = ((dayStart(e.date) - dayStart(System.currentTimeMillis())) / DAYMS).toInt()
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(c.bgSurface).padding(Space.s3), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            Column(Modifier.size(52.dp).clip(RoundedCornerShape(Radius.md)).background(if (n < 0) c.bgSunken else c.accentSubtle), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(if (n < 0) "–" else n.toString(), style = CanoneType.title3, color = if (n < 0) c.labelTertiary else c.accentOnSubtle)
                Text("giorni", style = CanoneType.footnote.copy(fontSize = 10.sp), color = c.labelTertiary)
            }
            Column(Modifier.weight(1f)) {
                Text(e.title, style = CanoneType.bodyMedium, color = c.labelPrimary, maxLines = 1)
                Text(listOfNotNull(s?.name, fmt(e.date, "EEE d MMM · HH:mm")).joinToString(" · "), style = CanoneType.footnote, color = c.labelTertiary, maxLines = 1)
            }
            CButton("×", { r.plan.removeExam(e.id) }, kind = ButtonKind.Tertiary)
        }
    }
}

// -------------------------------------------------------------------------------------------------------------------- flashcard
@Composable
private fun CardsPage(r: Router, p: PlanState, onReview: (String) -> Unit) {
    val c = Canone.colors
    var q by remember { mutableStateOf("") }
    var a by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    val now = System.currentTimeMillis()
    val due = p.cards.filter { it.due <= now && (subject.isEmpty() || it.subject == subject) }
    CCard {
        Text("${p.cards.size} carte · ${p.cards.count { it.due <= now }} da ripassare", style = CanoneType.bodyMedium, color = c.labelPrimary)
        if (p.subjects.isNotEmpty()) ChipRow {
            Chip("Tutte", subject.isEmpty()) { subject = "" }
            p.subjects.forEach { s -> Chip(s.name, subject == s.id, planColor(s.color)) { subject = s.id } }
        }
        CButton(if (due.isEmpty()) "Niente da ripassare" else "Ripassa ${due.size}", { onReview(subject) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = due.isNotEmpty())
    }
    CCard {
        CField("Domanda", q, { q = it }, "Cosa dice il teorema di…?")
        CField("Risposta", a, { a = it }, "La risposta")
        CButton("Aggiungi carta", { if (q.isNotBlank() && a.isNotBlank()) { r.plan.saveCard(Card("", subject, q.trim(), a.trim(), 0, 0)); q = ""; a = "" } }, Modifier.fillMaxWidth())
    }
    p.cards.filter { subject.isEmpty() || it.subject == subject }.forEach { k ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(c.bgSurface).padding(Space.s3), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            Column(Modifier.weight(1f)) {
                Text(k.q, style = CanoneType.body, color = c.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(k.a, style = CanoneType.footnote, color = c.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("Livello ${k.box}", style = CanoneType.footnote.copy(fontSize = 11.sp), color = c.labelTertiary)
            CButton("×", { r.plan.removeCard(k.id) }, kind = ButtonKind.Tertiary)
        }
    }
}

@Composable
private fun ReviewPage(r: Router, p: PlanState, subject: String, done: () -> Unit) {
    val c = Canone.colors
    val queue = remember { mutableStateOf(p.cards.filter { it.due <= System.currentTimeMillis() && (subject.isEmpty() || it.subject == subject) }.shuffled().map { it.id }) }
    var shown by remember { mutableStateOf(false) }
    var known by remember { mutableIntStateOf(0) }
    val card = queue.value.firstOrNull()?.let { id -> p.cards.firstOrNull { it.id == id } }
    if (card == null) {
        CCard {
            Text("Ripasso finito", style = CanoneType.title3, color = c.labelPrimary)
            Text("Ne hai sapute $known. Le altre torneranno presto.", style = CanoneType.footnote, color = c.labelSecondary)
            CButton("Torna alle carte", done, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
        }
        return
    }
    fun answer(level: Int) {
        // 0 non la so, 1 quasi, 2 facile
        val now = System.currentTimeMillis()
        val box = when (level) { 0 -> 0; 1 -> card.box; else -> (card.box + 1).coerceAtMost(BOX_DAYS.size - 1) }
        val next = if (level == 0) now + 5 * 60_000L else if (level == 1) now + maxOf(BOX_DAYS[box], 1) * DAYMS / 2 else now + BOX_DAYS[box] * DAYMS
        r.plan.saveCard(card.copy(box = box, due = next))
        if (level == 2) known++
        queue.value = queue.value.drop(1) + (if (level == 0) listOf(card.id) else emptyList())
        shown = false
    }
    Text("Restano ${queue.value.size}", style = CanoneType.footnote, color = c.labelTertiary)
    CCard {
        Text(p.subjects.firstOrNull { it.id == card.subject }?.name ?: "Domanda", style = CanoneType.footnote, color = c.labelTertiary)
        Text(card.q, style = CanoneType.title3, color = c.labelPrimary)
        if (shown) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.lineSeparator))
            Text(card.a, style = CanoneType.body, color = c.labelPrimary)
        }
    }
    if (!shown) CButton("Mostra la risposta", { shown = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        CButton("Non la so", { answer(0) }, Modifier.weight(1f))
        CButton("Quasi", { answer(1) }, Modifier.weight(1f))
        CButton("Facile", { answer(2) }, Modifier.weight(1f), kind = ButtonKind.Primary)
    }
}

// -------------------------------------------------------------------------------------------------------------------- statistiche
@Composable
private fun StatsPage(p: PlanState) {
    val c = Canone.colors
    val today = dayStart(System.currentTimeMillis())
    val week = (6 downTo 0).map { today - it * DAYMS }
    val perDay = week.map { d -> minutesOn(p, d, d + DAYMS) }
    CCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s5)) {
            Column { Text("Oggi", style = CanoneType.footnote, color = c.labelTertiary); Text(hm(perDay.last()), style = CanoneType.title3, color = c.labelPrimary) }
            Column { Text("7 giorni", style = CanoneType.footnote, color = c.labelTertiary); Text(hm(perDay.sum()), style = CanoneType.title3, color = c.labelPrimary) }
            Column { Text("Serie", style = CanoneType.footnote, color = c.labelTertiary); Text("${streak(p)} g", style = CanoneType.title3, color = c.labelPrimary) }
        }
        val goal = p.cfg("goal", 120)
        val top = maxOf(perDay.max(), goal, 1)
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val n = perDay.size; val gap = 10.dp.toPx(); val bw = (size.width - gap * (n - 1)) / n
            perDay.forEachIndexed { i, v ->
                val h = size.height * v / top
                drawRoundRect(if (i == n - 1) c.accent else c.accent.copy(alpha = 0.45f), Offset(i * (bw + gap), size.height - h), Size(bw, h.coerceAtLeast(2.dp.toPx())), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
            }
            val gy = size.height * (1f - goal.toFloat() / top)
            drawLine(c.labelTertiary.copy(alpha = 0.6f), Offset(0f, gy), Offset(size.width, gy), 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
        }
        Row(Modifier.fillMaxWidth()) { week.forEach { Text(fmt(it, "EEEEE").uppercase(), Modifier.weight(1f), style = CanoneType.footnote, color = c.labelTertiary, textAlign = TextAlign.Center) } }
    }
    val bySub = p.sessions.filter { it.start >= today - 6 * DAYMS }.groupBy { it.subject }.mapValues { e -> e.value.sumOf { it.minutes } }.toList().sortedByDescending { it.second }
    if (bySub.isNotEmpty()) CCard {
        SectionTitle("Per materia (7 giorni)")
        val max = bySub.first().second.coerceAtLeast(1)
        bySub.forEach { (id, m) ->
            val s = p.subjects.firstOrNull { it.id == id }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(s?.name ?: "Senza materia", style = CanoneType.footnote, color = c.labelSecondary)
                    Text(hm(m), style = CanoneType.footnote, color = c.labelTertiary)
                }
                Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(c.bgSunken)) { Box(Modifier.fillMaxWidth(m.toFloat() / max).fillMaxHeight().clip(CircleShape).background(s?.let { planColor(it.color) } ?: c.labelTertiary)) }
            }
        }
    }
    CCard {
        Text("Totale", style = CanoneType.footnote, color = c.labelTertiary)
        Text("${hm(p.sessions.sumOf { it.minutes })} in ${p.sessions.size} pomodori", style = CanoneType.bodyMedium, color = c.labelPrimary)
    }
}

// -------------------------------------------------------------------------------------------------------------------- impostazioni
@Composable
private fun Stepper(label: String, value: Int, unit: String, min: Int, max: Int, step: Int, onChange: (Int) -> Unit) {
    val c = Canone.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        Text(label, Modifier.weight(1f), style = CanoneType.body, color = c.labelPrimary)
        CButton("−", { onChange((value - step).coerceAtLeast(min)) }, kind = ButtonKind.Secondary)
        Text("$value $unit", Modifier.widthIn(min = 64.dp), style = CanoneType.bodyMedium, color = c.labelPrimary, textAlign = TextAlign.Center)
        CButton("+", { onChange((value + step).coerceAtMost(max)) }, kind = ButtonKind.Secondary)
    }
}

@Composable
private fun SettingsPage(r: Router, p: PlanState) {
    CCard {
        Stepper("Concentrazione", p.cfg("focus", 25), "min", 5, 90, 5) { r.plan.setCfg("focus", it) }
        Stepper("Pausa", p.cfg("short", 5), "min", 1, 30, 1) { r.plan.setCfg("short", it) }
        Stepper("Pausa lunga", p.cfg("long", 15), "min", 5, 60, 5) { r.plan.setCfg("long", it) }
        Stepper("Pomodori per la pausa lunga", p.cfg("rounds", 4), "", 2, 8, 1) { r.plan.setCfg("rounds", it) }
        Stepper("Obiettivo di oggi", p.cfg("goal", 120), "min", 30, 600, 30) { r.plan.setCfg("goal", it) }
    }
    Text("Le durate valgono dal prossimo pomodoro e sono le stesse su tutti i tuoi dispositivi.", style = CanoneType.footnote, color = Canone.colors.labelTertiary)
}
