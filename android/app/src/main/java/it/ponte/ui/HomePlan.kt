package it.ponte.ui

import androidx.compose.foundation.BorderStroke
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
fun PlanWidgets(r: Router, go: (String) -> Unit, cell: androidx.compose.ui.unit.Dp, onLong: () -> Unit) {
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
    val t = p.timer
    val total = (when (t.phase) { "short" -> p.cfg("short", 5); "long" -> p.cfg("long", 15); else -> p.cfg("focus", 25) }) * 60_000L
    val left = if (t.running) (t.endAt - tick).coerceIn(0L, total) else total
    val secs = ((left + 999) / 1000).toInt()
    val today = p.sessions.filter { it.start >= dayStart(now) }.sumOf { it.minutes }
    // carosello: agenda e studio nello stesso riquadro, si passa con uno swipe
    val pages = androidx.compose.foundation.pager.rememberPagerState(initialPage = if (t.running) 1 else 0) { 2 }
    GlassTile(Modifier.size(cell), Tint.blue, 0, { go(if (pages.currentPage == 1) "studio" else "agenda") }, onLong) {
        androidx.compose.foundation.pager.HorizontalPager(pages, Modifier.fillMaxSize()) { pg ->
            Column(Modifier.fillMaxSize().padding(start = Space.s4, end = Space.s4, top = Space.s4, bottom = Space.s5), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconBubble(if (pg == 0) 11 else 12)
                    Text(if (pg == 0) "Agenda" else if (t.running) (if (t.phase == "focus") "Concentrazione" else "Pausa") else "Studio", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = OnGlass2, maxLines = 1)
                }
                if (pg == 0) {
                    if (next == null) Text("Niente in programma", style = CanoneType.bodyMedium, color = OnGlass, maxLines = 3)
                    else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(next.title, style = CanoneType.title3.copy(fontWeight = FontWeight.SemiBold), color = OnGlass, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(Tint.third))
                            Text(if (next.allDay) dayLabel(next.start) else dayLabel(next.start) + " " + fmt(next.start, "HH:mm"), style = CanoneType.footnote, color = OnGlass2, maxLines = 1)
                        }
                    }
                } else {
                    Text("%02d:%02d".format(secs / 60, secs % 60), style = CanoneType.title1.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = if (t.running) OnGlass else OnGlass2, maxLines = 1)
                    Text(if (today > 0) "$today min oggi" else "Oggi niente", style = CanoneType.footnote, color = OnGlass3, maxLines = 1)
                }
            }
        }
        // indicatore di pagina
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(2) { i ->
                val on = pages.currentPage == i
                Box(Modifier.size(if (on) 14.dp else 5.dp, 5.dp).clip(CircleShape).background(if (on) Canone.colors.accent else OnGlass3))
            }
        }
    }
}
