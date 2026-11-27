package it.ponte.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.ponte.PlanState
import it.ponte.Router
import it.ponte.Task
import kotlinx.coroutines.launch

/** Scintilla di Gemini: stella a quattro punte. */
@Composable
fun Sparkle(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height; val cx = w / 2; val cy = h / 2
        val p = Path().apply {
            moveTo(cx, 0f); quadraticTo(cx, cy, w, cy); quadraticTo(cx, cy, cx, h); quadraticTo(cx, cy, 0f, cy); quadraticTo(cx, cy, cx, 0f); close()
        }
        drawPath(p, color)
    }
}

/** Microfono stilizzato. */
@Composable
private fun Mic(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRoundRect(color, Offset(w * 0.34f, 0f), androidx.compose.ui.geometry.Size(w * 0.32f, h * 0.62f), androidx.compose.ui.geometry.CornerRadius(w * 0.16f))
        drawArc(color, 0f, 180f, false, Offset(w * 0.18f, h * 0.22f), androidx.compose.ui.geometry.Size(w * 0.64f, h * 0.56f), style = Stroke(w * 0.09f, cap = StrokeCap.Round))
        drawLine(color, Offset(w / 2, h * 0.78f), Offset(w / 2, h), w * 0.09f, StrokeCap.Round)
    }
}

/** To-do: 0 = vita, 1 = università. Condiviso tra la Home e l'Agenda. */
internal val todoMode = androidx.compose.runtime.mutableIntStateOf(0)

/** Cose da fare per l'università: prima quelle libere, poi gli argomenti d'esame nell'ordine in cui conviene farli. */
internal fun uniOpen(p: PlanState): List<Task> {
    val free = p.topics.filter { it.subject == "uni" && !it.done }
    val next = courses(p).filter { it.open.isNotEmpty() && (it.days ?: 0) >= 0 }
        .sortedWith(compareBy<Course>({ if (it.doneToday < it.perDay) 0 else 1 }, { -it.pressure }, { it.exam?.date ?: Long.MAX_VALUE }))
        .flatMap { it.open }
    return free + next
}

/** Selettore Life / Uni. */
@Composable
fun TodoSwitch(modifier: Modifier = Modifier) {
    GlassSegmented(listOf("Life", "Uni"), todoMode.intValue, { todoMode.intValue = it }, modifier)
}

/** Campo per aggiungere alla To-do. */
@Composable
fun AddTodo(r: Router, modifier: Modifier = Modifier, subject: String = "") {
    val c = Canone.colors
    var text by remember { mutableStateOf("") }
    fun add() { if (text.isNotBlank()) { r.plan.saveTask(Task("", text.trim(), subject, 0L, false)); text = "" } }
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier.fillMaxWidth().height(48.dp).clip(shape).glass(shape).padding(start = Space.s4, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Text("Nuova cosa da fare", style = CanoneType.body, color = c.labelTertiary, maxLines = 1)
            BasicTextField(
                text, { text = it }, singleLine = true, textStyle = CanoneType.body.copy(color = c.labelPrimary), cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        RoundGlass(40.dp, text.isNotBlank(), { add() }) { Text("+", style = CanoneType.title3, color = if (text.isNotBlank()) Color.White else c.labelPrimary) }
    }
}

/** Una riga della To-do: bolla di spunta e testo. */
@Composable
fun TodoRow(r: Router, t: Task, compact: Boolean = false) {
    val showDue = t.subject.isEmpty()
    val c = Canone.colors
    val dark = isDark()
    val done by animateFloatAsState(if (t.done) 1f else 0f, spring(0.6f, 300f), label = "fatto")
    Row(Modifier.fillMaxWidth().padding(vertical = if (compact) 4.dp else 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
        val tt = rememberDropTouch()
        Box(
            Modifier.size(if (compact) 22.dp else 26.dp).graphicsLayer { val p = tt.press.value; scaleX = 1f + 0.12f * p; scaleY = 1f + 0.12f * p }
                .then(if (t.done) Modifier.drop(dark, tint = c.accent) else Modifier.border(1.5.dp, c.labelTertiary, CircleShape))
                .clip(CircleShape).dropGestures(tt, { r.plan.saveTask(t.copy(done = !t.done, doneAt = if (t.done) 0L else System.currentTimeMillis())) }),
            contentAlignment = Alignment.Center,
        ) { if (done > 0.01f) Icon24(Ico.CHECK, Color.White, Modifier.size(14.dp).graphicsLayer { scaleX = done; scaleY = done }) }
        Text(
            t.title, style = (if (compact) CanoneType.footnote else CanoneType.body).copy(textDecoration = if (t.done) TextDecoration.LineThrough else null),
            color = if (t.done) c.labelTertiary else c.labelPrimary, maxLines = if (compact) 1 else 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (!compact && showDue && t.due > 0) Text(dayLabel(t.due), style = CanoneType.footnote, color = if (!t.done && t.due < dayStart(System.currentTimeMillis())) Tint.third else c.labelTertiary)
    }
}

/** Riquadro To-do della Home: le prime cose da fare e la barra di Gemini. */
@Composable
fun TodoTile(r: Router, modifier: Modifier, index: Int, onClick: () -> Unit, onLong: () -> Unit) {
    val c = Canone.colors
    val p by r.plan.state.collectAsState()
    val uni = todoMode.intValue == 1
    val open = if (uni) uniOpen(p) else p.tasks.filter { !it.done }.sortedWith(compareBy({ if (it.due > 0) it.due else Long.MAX_VALUE }, { it.title }))
    GlassTile(modifier, Tint.blue, index, onClick, onLong) {
        var dx by remember { mutableStateOf(0f) }
        Column(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = { if (dx < -40f) todoMode.intValue = 1 else if (dx > 40f) todoMode.intValue = 0 },
                    onDragCancel = { dx = 0f },
                ) { _, d -> dx += d }
            }.padding(Space.s4),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconBubble(12)
                Text(if (uni) "Uni" else "Life", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.labelSecondary,
                    modifier = Modifier.weight(1f).clip(CircleShape).dropClickable({ todoMode.intValue = 1 - todoMode.intValue }, corner = 12.dp))
                if (open.isNotEmpty()) Text("${open.size}", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
            }
            if (open.isEmpty()) Text("Tutto fatto", style = CanoneType.bodyMedium, color = c.labelTertiary, modifier = Modifier.padding(vertical = 4.dp))
            open.take(5).forEach { TodoRow(r, it, compact = true) }
        }
    }
}

/** Pagina To-do nell'Agenda. */
fun LazyListScope.todoItems(r: Router, p: PlanState, uni: Boolean) {
    item { AddTodo(r, subject = if (uni) "uni" else "") }
    val open = if (uni) uniOpen(p) else p.tasks.filter { !it.done }.sortedWith(compareBy({ if (it.due > 0) it.due else Long.MAX_VALUE }, { it.title }))
    val done = if (uni) p.topics.filter { it.done }.sortedByDescending { it.doneAt } else p.tasks.filter { it.done }
    item {
        GlassTile(Modifier.fillMaxWidth(), Tint.blue, 0) {
            Column(Modifier.padding(horizontal = Space.s4, vertical = Space.s2)) {
                if (open.isEmpty()) Text("Tutto fatto", style = CanoneType.body, color = Canone.colors.labelTertiary, modifier = Modifier.padding(vertical = Space.s3))
                open.forEachIndexed { i, t ->
                    if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 38.dp).height(1.dp).background(Canone.colors.lineSeparator))
                    TodoRow(r, t)
                }
            }
        }
    }
    if (done.isNotEmpty()) item {
        GlassTile(Modifier.fillMaxWidth(), Tint.blue, 1) {
            Column(Modifier.padding(horizontal = Space.s4, vertical = Space.s2)) {
                Row(Modifier.fillMaxWidth().padding(vertical = Space.s2), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Fatte", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = Canone.colors.labelSecondary)
                    if (!uni) Text("Svuota", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = Canone.colors.accent,
                        modifier = Modifier.clip(CircleShape).dropClickable({ done.forEach { r.plan.removeTask(it.id) } }, corner = 12.dp).padding(horizontal = 8.dp, vertical = 4.dp))
                }
                done.take(20).forEach { TodoRow(r, it) }
            }
        }
    }
}

/** To-do a due pagine, Life e Uni, da scorrere di lato come l'Agenda. */
@Composable
fun TodoPager(r: Router, p: PlanState, onBack: () -> Unit) {
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = todoMode.intValue) { 2 }
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(pager.currentPage) { todoMode.intValue = pager.currentPage }
    androidx.compose.runtime.LaunchedEffect(todoMode.intValue) { if (pager.currentPage != todoMode.intValue) pager.animateScrollToPage(todoMode.intValue) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = Space.s4).padding(top = Space.s2)) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                BackRow("Agenda", "To-do") { onBack() }
                GlassSegmented(listOf("Life", "Uni"), pager.currentPage, { scope.launch { pager.animateScrollToPage(it) } }, Modifier.fillMaxWidth())
            }
        }
        androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { pg ->
            ScreenList("To-do") { todoItems(r, p, pg == 1) }
        }
    }
}
