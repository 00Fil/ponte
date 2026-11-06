package it.ponte.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.ponte.Exam
import it.ponte.PlanState
import it.ponte.Router
import it.ponte.Subject
import it.ponte.Task
import kotlin.math.ceil

private const val DAY = 86_400_000L

/** Situazione di una materia rispetto al suo esame. */
internal class Course(val subject: Subject, val exam: Exam?, val topics: List<Task>) {
    val open = topics.filter { !it.done }
    val done = topics.size - open.size
    /** Giorni interi da oggi all'esame (0 = oggi). */
    val days: Int? = exam?.let { ((dayStart(it.date) - dayStart(System.currentTimeMillis())) / DAY).toInt() }
    /** Giorni utili per studiare: l'ultimo prima dell'esame resta per il ripasso. */
    val usable: Int get() = days?.let { (it - 1).coerceAtLeast(1) } ?: 30
    /** Argomenti da fare ogni giorno per arrivare in tempo. */
    val perDay: Int get() = if (open.isEmpty()) 0 else ceil(open.size.toDouble() / usable).toInt()
    /** Quanto è stretta: argomenti rimasti per giorno utile (più alto = più urgente). */
    val pressure: Double get() = if (open.isEmpty() || (days ?: 0) < 0) -1.0 else open.size.toDouble() / usable + (if (exam == null) -100.0 else 0.0)
    val doneToday = topics.count { it.done && it.doneAt >= dayStart(System.currentTimeMillis()) }
}

internal fun courses(p: PlanState): List<Course> = p.subjects.map { s ->
    val ex = p.exams.filter { it.subject == s.id && dayStart(it.date) >= dayStart(System.currentTimeMillis()) }.minByOrNull { it.date }
        ?: p.exams.filter { it.subject == s.id }.maxByOrNull { it.date }
    Course(s, ex, p.topics.filter { it.subject == s.id })
}.sortedBy { it.exam?.date ?: Long.MAX_VALUE }

/** La sola cosa da fare adesso: il primo argomento rimasto della materia più indietro rispetto al suo esame. */
internal fun nowTopic(p: PlanState): Pair<Course, Task>? {
    val c = courses(p).filter { it.open.isNotEmpty() && (it.days ?: 0) >= 0 }
        // prima chi non ha ancora fatto la sua quota di oggi, poi chi è più stretto, poi l'esame più vicino
        .sortedWith(compareBy<Course>({ if (it.doneToday < it.perDay) 0 else 1 }, { -it.pressure }, { it.exam?.date ?: Long.MAX_VALUE }))
        .firstOrNull() ?: return null
    return c to c.open.first()
}

private fun whenLabel(days: Int?): String = when {
    days == null -> "Senza data"
    days < 0 -> "Passato"
    days == 0 -> "Oggi"
    days == 1 -> "Domani"
    else -> "Tra $days giorni"
}

/** Adesso: un solo argomento, con i due gesti che servono. */
@Composable
internal fun NowStudy(r: Router, p: PlanState, onAdd: () -> Unit) {
    val c = Canone.colors
    val now = nowTopic(p)
    CCard {
        if (now == null) {
            val all = courses(p)
            Text(if (all.isEmpty()) "Esami" else "Tutto fatto", style = CanoneType.title3, color = c.labelPrimary)
            if (all.isEmpty()) PillOn("+ Materia", onAdd)
            return@CCard
        }
        val (course, topic) = now
        val col = planColor(course.subject.color)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(col))
            Text(course.subject.name, style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.labelSecondary)
            Text("· " + whenLabel(course.days), style = CanoneType.footnote, color = c.labelTertiary)
            Box(Modifier.weight(1f))
            Text("${course.doneToday}/${course.perDay} oggi", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = if (course.doneToday >= course.perDay) c.positive else c.accent)
        }
        Text(topic.title, style = CanoneType.title2.copy(fontWeight = FontWeight.Bold), color = c.labelPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
            val running = p.timer.running && p.timer.endAt > System.currentTimeMillis()
            RoundGlass(48.dp, true, { if (running) r.plan.timerStop() else r.plan.timerStart(course.subject.id) }) {
                TransportGlyph(if (running) 2 else 1, Color.White, Modifier.size(18.dp))
            }
            RoundGlass(48.dp, false, { r.plan.saveTask(topic.copy(done = true, doneAt = System.currentTimeMillis())) }) { Icon24(Ico.CHECK, c.labelPrimary, Modifier.size(20.dp)) }
        }
    }
}

/** Le materie: data d'esame, avanzamento, quota di oggi; un tocco apre gli argomenti. */
@Composable
internal fun ExamsCard(r: Router, p: PlanState, adding: Boolean, onAdding: (Boolean) -> Unit) {
    val c = Canone.colors
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    CCard(Modifier.animateContentSize(spring(0.9f, 400f))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Esami", style = CanoneType.title3, color = c.labelPrimary, modifier = Modifier.weight(1f))
            RoundGlass(34.dp, adding, { onAdding(!adding) }) { Text("+", style = CanoneType.bodyMedium, color = if (adding) Color.White else c.labelPrimary) }
        }
        AnimatedVisibility(adding, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Field("Nome della materia", single = true) { name ->
                r.plan.saveSubject(Subject("", name, p.subjects.size))
                onAdding(false)
            }
        }
        courses(p).forEach { course ->
            CourseRow(r, course, open == course.subject.id) { open = if (open == course.subject.id) null else course.subject.id }
        }
    }
}

@Composable
private fun CourseRow(r: Router, course: Course, expanded: Boolean, onToggle: () -> Unit) {
    val c = Canone.colors
    val ctx = LocalContext.current
    val col = planColor(course.subject.color)
    val total = course.topics.size
    val frac by animateFloatAsState(if (total == 0) 0f else course.done.toFloat() / total, tween(500), label = "avanzamento")
    val late = course.perDay > 2 || (course.days != null && course.days <= 3 && course.open.isNotEmpty())
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).dropClickable(onToggle, corner = Radius.md).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Box(Modifier.width(4.dp).height(38.dp).clip(CircleShape).background(col))
            Column(Modifier.weight(1f)) {
                Text(course.subject.name, style = CanoneType.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.labelPrimary, maxLines = 1)
                Text(
                    listOf(whenLabel(course.days) + (course.exam?.let { " · " + fmt(it.date, "d MMM") } ?: ""), "${course.done}/$total").joinToString(" · "),
                    style = CanoneType.footnote, color = c.labelTertiary, maxLines = 1,
                )
            }
            if (course.open.isNotEmpty()) Column(horizontalAlignment = Alignment.End) {
                Text("${course.perDay}", style = CanoneType.title3.copy(fontWeight = FontWeight.Bold), color = if (late) Tint.third else c.accent)
                Text("al giorno", style = CanoneType.footnote, color = c.labelTertiary)
            } else if (total > 0) Icon24(Ico.CHECK, c.positive, Modifier.size(20.dp))
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.labelPrimary.copy(alpha = 0.07f))) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(CircleShape).background(col))
        }
        AnimatedVisibility(expanded, enter = expandVertically(spring(0.9f, 400f)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
                    PillOn(course.exam?.let { "Esame " + fmt(it.date, "EEE d MMM") } ?: "Data esame") {
                        val start = course.exam?.date ?: addDays(System.currentTimeMillis(), 30)
                        pickDate(ctx, start) { d -> r.plan.saveExam(Exam(course.exam?.id ?: "", course.subject.name, course.subject.id, dayStart(d) + 9 * 3_600_000L)) }
                    }
                    Box(Modifier.weight(1f))
                    Text("Elimina", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.critical,
                        modifier = Modifier.clip(CircleShape).dropClickable({
                            course.topics.forEach { r.plan.removeTask(it.id) }
                            course.exam?.let { r.plan.removeExam(it.id) }
                            r.plan.removeSubject(course.subject.id)
                        }, corner = 12.dp).padding(horizontal = 8.dp, vertical = 4.dp))
                }
                course.topics.forEach { t -> TopicRow(r, t) }
                Field("Argomenti, uno per riga", single = false) { text ->
                    r.plan.addTopics(course.subject.id, text.lines().map { it.trim().trimStart('-', '•', '*', ' ') }.filter { it.isNotEmpty() })
                }
            }
        }
    }
}

@Composable
private fun TopicRow(r: Router, t: Task) {
    val c = Canone.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
        val tt = rememberDropTouch()
        Box(
            Modifier.size(24.dp).then(if (t.done) Modifier.drop(isDark(), tint = c.accent) else Modifier.border(1.5.dp, c.labelTertiary, CircleShape))
                .clip(CircleShape).dropGestures(tt, { r.plan.saveTask(t.copy(done = !t.done, doneAt = if (t.done) 0 else System.currentTimeMillis())) }),
            contentAlignment = Alignment.Center,
        ) { if (t.done) Icon24(Ico.CHECK, Color.White, Modifier.size(13.dp)) }
        Text(
            t.title, style = CanoneType.body.copy(textDecoration = if (t.done) TextDecoration.LineThrough else null),
            color = if (t.done) c.labelTertiary else c.labelPrimary, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text("×", style = CanoneType.title3, color = c.labelTertiary, modifier = Modifier.clip(CircleShape).dropClickable({ r.plan.removeTask(t.id) }, corner = 14.dp).padding(horizontal = 8.dp))
    }
}

/** Campo di testo con invio: su più righe accetta un elenco incollato. */
@Composable
private fun Field(hint: String, single: Boolean, onDone: (String) -> Unit) {
    val c = Canone.colors
    var text by remember { mutableStateOf("") }
    fun send() { if (text.isNotBlank()) { onDone(text.trim()); text = "" } }
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(shape).background(c.labelPrimary.copy(alpha = 0.06f)).padding(start = Space.s4, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Box(Modifier.weight(1f).padding(vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Text(hint, style = CanoneType.body, color = c.labelTertiary, maxLines = 1)
            BasicTextField(
                text, { text = it }, singleLine = single, maxLines = if (single) 1 else 8,
                textStyle = CanoneType.body.copy(color = c.labelPrimary), cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = if (single) ImeAction.Done else ImeAction.Default),
                keyboardActions = KeyboardActions(onDone = { send() }), modifier = Modifier.fillMaxWidth(),
            )
        }
        RoundGlass(38.dp, text.isNotBlank(), { send() }) { Text("+", style = CanoneType.bodyMedium, color = if (text.isNotBlank()) Color.White else c.labelPrimary) }
    }
}
