package it.ponte.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.ponte.CalendarBridge
import it.ponte.Router
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private class Next(val title: String, val start: Long, val allDay: Boolean, val color: Color)

/** Due riquadri della Home: il prossimo impegno e il pomodoro (o lo studio di oggi). */
@Composable
fun PlanWidgets(r: Router, go: (String) -> Unit, cell: androidx.compose.ui.unit.Dp) {
    val c = Canone.colors
    val ctx = LocalContext.current
    val p by r.plan.state.collectAsState()
    val bridge = remember { CalendarBridge(ctx) }
    var dev by remember { mutableStateOf<List<it.ponte.DevEvent>>(emptyList()) }
    LaunchedEffect(bridge.granted(), p.events.size) {
        val now = System.currentTimeMillis()
        dev = if (bridge.granted()) withContext(Dispatchers.IO) { bridge.events(now, now + 7 * 86_400_000L) } else emptyList()
    }
    val now = System.currentTimeMillis()
    val subjects = p.subjects.associateBy { it.id }
    val next = (
        p.events.filter { it.end > now }.map { Next(it.title.ifBlank { "(senza titolo)" }, it.start, it.allDay, planColor(it.color)) } +
            dev.filter { it.end > now }.map { Next(it.title, it.start, it.allDay, Color(it.color or (0xFF shl 24))) } +
            p.exams.filter { it.date > now }.map { Next("Esame: " + it.title, it.date, false, Color(0xFFD9534F)) } +
            p.tasks.filter { !it.done && it.due > 0 && it.due + 86_400_000L > now }.map { Next(it.title, maxOf(it.due, dayStart(now)), true, subjects[it.subject]?.let { s -> planColor(s.color) } ?: c.labelTertiary) }
        ).minByOrNull { if (it.allDay && it.start < now) dayStart(now) else it.start }

    var tick by remember { mutableLongStateOf(now) }
    LaunchedEffect(p.timer.running, p.timer.endAt) {
        while (p.timer.running) { tick = System.currentTimeMillis(); if (tick >= p.timer.endAt) { r.plan.timerDone(); break }; delay(500) }
    }
    val shape = RoundedCornerShape(Radius.xl)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
        GlassTile(Modifier.size(cell), Tint.sky, 0, { go("agenda") }) {
            Column(Modifier.fillMaxSize().padding(Space.s4), verticalArrangement = Arrangement.SpaceBetween) {
                Text("Prossimo impegno", style = CanoneType.footnote, color = OnGlass2, maxLines = 1)
                if (next == null) Text("Niente in programma", style = CanoneType.bodyMedium, color = OnGlass, maxLines = 3)
                else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(next.title, style = CanoneType.title3.copy(fontWeight = FontWeight.SemiBold), color = OnGlass, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
                        Text(if (next.allDay) dayLabel(next.start) else dayLabel(next.start) + " " + fmt(next.start, "HH:mm"), style = CanoneType.footnote, color = OnGlass2, maxLines = 1)
                    }
                }
            }
        }
        val t = p.timer
        val total = (when (t.phase) { "short" -> p.cfg("short", 5); "long" -> p.cfg("long", 15); else -> p.cfg("focus", 25) }) * 60_000L
        val left = if (t.running) (t.endAt - tick).coerceIn(0L, total) else total
        val secs = ((left + 999) / 1000).toInt()
        val today = p.sessions.filter { it.start >= dayStart(now) }.sumOf { it.minutes }
        GlassTile(Modifier.size(cell), Tint.sun, 1, { go("studio") }) {
            Column(Modifier.fillMaxSize().padding(Space.s4), verticalArrangement = Arrangement.SpaceBetween) {
                Text(if (t.running) (if (t.phase == "focus") "Concentrazione" else "Pausa") else "Studio", style = CanoneType.footnote, color = OnGlass2, maxLines = 1)
                RollingText("%02d:%02d".format(secs / 60, secs % 60), CanoneType.title1.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), OnGlass)
                Text(if (today > 0) "$today min oggi" else "", style = CanoneType.footnote, color = OnGlass2, maxLines = 1)
            }
        }
    }
}
