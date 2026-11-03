package it.ponte.ui

import android.content.Intent
import android.speech.RecognizerIntent
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
import it.ponte.Gemini
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

/** Barra per parlare con Gemini: scrivi o detta, lui aggiunge To-do ed eventi. */
@Composable
fun AskBar(r: Router, modifier: Modifier = Modifier) {
    val c = Canone.colors
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf("") }
    var key by remember { mutableStateOf(r.prefs.geminiKey) }
    fun send(q: String) {
        if (q.isBlank() || busy) return
        busy = true; reply = ""
        scope.launch { reply = Gemini.ask(r, q); busy = false; text = "" }
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { text = it; send(it) }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        if (key.isBlank()) {
            var k by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                CField("Chiave Gemini", k, { k = it }, "AIza…", Modifier.weight(1f), secret = true)
                CButton("Salva", { r.prefs.geminiKey = k; key = k.trim() }, Modifier.padding(top = 22.dp), kind = ButtonKind.Primary)
            }
            return@Column
        }
        val shape = RoundedCornerShape(24.dp)
        Row(
            Modifier.fillMaxWidth().height(48.dp).clip(shape).glass(shape).border(1.dp, c.accent.copy(alpha = if (busy) 0.6f else 0.25f), shape).padding(start = Space.s3, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            val spin by animateFloatAsState(if (busy) 1f else 0f, spring(0.6f, 120f), label = "scintilla")
            Sparkle(c.accent, Modifier.size(18.dp).graphicsLayer { rotationZ = 90f * spin; scaleX = 1f + 0.2f * spin; scaleY = 1f + 0.2f * spin })
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Text(if (busy) "…" else "Chiedi a Gemini", style = CanoneType.body, color = c.labelTertiary, maxLines = 1)
                BasicTextField(
                    text, { text = it }, singleLine = true, textStyle = CanoneType.body.copy(color = c.labelPrimary), cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send(text) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            RoundGlass(40.dp, text.isNotBlank(), {
                if (text.isNotBlank()) send(text)
                else try {
                    voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT"))
                } catch (_: Exception) {}
            }) {
                if (text.isNotBlank()) TransportGlyph(1, Color.White, Modifier.size(16.dp)) else Mic(c.labelPrimary, Modifier.size(18.dp))
            }
        }
        AnimatedVisibility(reply.isNotEmpty()) {
            Text(reply, style = CanoneType.footnote, color = c.labelSecondary, modifier = Modifier.padding(horizontal = Space.s3))
        }
    }
}

/** Una riga della To-do: bolla di spunta e testo. */
@Composable
fun TodoRow(r: Router, t: Task, compact: Boolean = false) {
    val c = Canone.colors
    val dark = isDark()
    val done by animateFloatAsState(if (t.done) 1f else 0f, spring(0.6f, 300f), label = "fatto")
    Row(Modifier.fillMaxWidth().padding(vertical = if (compact) 4.dp else 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
        val tt = rememberDropTouch()
        Box(
            Modifier.size(if (compact) 22.dp else 26.dp).graphicsLayer { val p = tt.press.value; scaleX = 1f + 0.12f * p; scaleY = 1f + 0.12f * p }
                .then(if (t.done) Modifier.drop(dark, tint = c.accent) else Modifier.border(1.5.dp, c.labelTertiary, CircleShape))
                .clip(CircleShape).dropGestures(tt, { r.plan.saveTask(t.copy(done = !t.done)) }),
            contentAlignment = Alignment.Center,
        ) { if (done > 0.01f) Icon24(Ico.CHECK, Color.White, Modifier.size(14.dp).graphicsLayer { scaleX = done; scaleY = done }) }
        Text(
            t.title, style = (if (compact) CanoneType.footnote else CanoneType.body).copy(textDecoration = if (t.done) TextDecoration.LineThrough else null),
            color = if (t.done) c.labelTertiary else c.labelPrimary, maxLines = if (compact) 1 else 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (!compact && t.due > 0) Text(dayLabel(t.due), style = CanoneType.footnote, color = if (!t.done && t.due < dayStart(System.currentTimeMillis())) Tint.third else c.labelTertiary)
    }
}

/** Riquadro To-do della Home: le prime cose da fare e la barra di Gemini. */
@Composable
fun TodoTile(r: Router, modifier: Modifier, index: Int, onClick: () -> Unit, onLong: () -> Unit) {
    val c = Canone.colors
    val p by r.plan.state.collectAsState()
    val open = p.tasks.filter { !it.done }.sortedWith(compareBy({ if (it.due > 0) it.due else Long.MAX_VALUE }, { it.title }))
    GlassTile(modifier, Tint.blue, index, onClick, onLong) {
        Column(Modifier.fillMaxWidth().padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconBubble(12)
                Text("To-do", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.labelSecondary, modifier = Modifier.weight(1f))
                if (open.isNotEmpty()) Text("${open.size}", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
            }
            if (open.isEmpty()) Text("Tutto fatto", style = CanoneType.bodyMedium, color = c.labelTertiary, modifier = Modifier.padding(vertical = 4.dp))
            open.take(4).forEach { TodoRow(r, it, compact = true) }
            AskBar(r)
        }
    }
}

/** Pagina To-do nell'Agenda. */
fun LazyListScope.todoItems(r: Router, p: PlanState) {
    item { AskBar(r) }
    val open = p.tasks.filter { !it.done }.sortedWith(compareBy({ if (it.due > 0) it.due else Long.MAX_VALUE }, { it.title }))
    val done = p.tasks.filter { it.done }
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
                    Text("Svuota", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = Canone.colors.accent,
                        modifier = Modifier.clip(CircleShape).dropClickable({ done.forEach { r.plan.removeTask(it.id) } }, corner = 12.dp).padding(horizontal = 8.dp, vertical = 4.dp))
                }
                done.take(20).forEach { TodoRow(r, it) }
            }
        }
    }
}
