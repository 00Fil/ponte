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
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
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

/** Il pomodoro e i dati di studio, dentro l'Agenda. */
fun androidx.compose.foundation.lazy.LazyListScope.studyItems(r: Router, p: PlanState, onSettings: () -> Unit) {
    item { PomodoroCard(r, p, onSettings) }
    item { TodayCard(p) }
    item { StatsPage(p) }
}

/** Durate del pomodoro. */
@Composable
fun StudySettings(r: Router, back: () -> Unit) {
    val p by r.plan.state.collectAsState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = Space.s4, end = Space.s4, top = Space.s3, bottom = Space.s5 + LocalBarInset.current),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            BackRow("Agenda", "Pomodoro") { back() }
            SettingsPage(r, p)
        }
    }
}

// -------------------------------------------------------------------------------------------------------------------- pomodoro
@Composable
private fun PomodoroCard(r: Router, p: PlanState, onSettings: () -> Unit) {
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

    val np = rememberNowPlaying()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val palette = remember(np?.art) { moodColors(np?.art) }
    val dark = isDark()
    val base = listOf(c.accent, Color(0xFF5DA0FF), Color(0xFF8E5BB5))
    val m0 by androidx.compose.animation.animateColorAsState(palette.getOrNull(0) ?: base[0], tween(1600), label = "m0")
    val m1 by androidx.compose.animation.animateColorAsState(palette.getOrNull(1) ?: palette.getOrNull(0) ?: base[1], tween(1600), label = "m1")
    val m2 by androidx.compose.animation.animateColorAsState(palette.getOrNull(2) ?: palette.getOrNull(0) ?: base[2], tween(1600), label = "m2")
    val inf = androidx.compose.animation.core.rememberInfiniteTransition(label = "ambiente")
    val drift by inf.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(16000, easing = androidx.compose.animation.core.LinearEasing)), label = "deriva")
    val breathe by inf.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(3200), androidx.compose.animation.core.RepeatMode.Reverse), label = "respiro")
    val strength by animateFloatAsState(if (t.running) 1f else if (np?.playing == true) 0.7f else 0.4f, tween(900), label = "forza")
    val shape = RoundedCornerShape(Radius.xl)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.bgSurface)
            .drawBehind {
                val cols = listOf(m0, m1, m2)
                val rad = size.maxDimension * 0.75f
                val a = (if (dark) 0.50f else 0.34f) * strength * (0.82f + 0.18f * breathe)
                for (i in 0 until 3) {
                    val ang = (drift + i / 3f) * 2f * Math.PI.toFloat()
                    val ctr = Offset(size.width * (0.5f + 0.42f * kotlin.math.cos(ang)), size.height * (0.5f + 0.38f * kotlin.math.sin(ang * (1f + i * 0.3f))))
                    drawCircle(Brush.radialGradient(listOf(cols[i].copy(alpha = a), Color.Transparent), ctr, rad), rad, ctr)
                }
            }
            .border(androidx.compose.foundation.BorderStroke(1.dp, c.lineSeparator), shape).padding(Space.s5),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
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
                        if (t.running && !mine) "su un altro dispositivo" else "Studio",
                        style = CanoneType.footnote, color = c.labelTertiary,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until rounds) Box(Modifier.padding(horizontal = 3.dp).size(8.dp).clip(CircleShape).background(if (i < t.cycle % rounds || (t.phase == "long" && t.cycle > 0)) tint else c.bgSunken))
        }
        if (np != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            Column(Modifier.weight(1f)) {
                Text(np.title.ifBlank { np.app }, style = CanoneType.bodyMedium, color = c.labelPrimary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (np.artist.isNotBlank()) Text(np.artist, style = CanoneType.footnote, color = c.labelSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            val cost = t.running && focus
            val tk = rememberDropTouch()
            Row(
                Modifier.height(36.dp).dropGestures(tk, {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    np.controller.transportControls.skipToNext()
                    if (cost) r.plan.timerPenalty(60_000L)
                }),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    Modifier.fillMaxHeight().dropDeform(tk).drop(dark, press = { tk.press.value }, touch = tk).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Canvas(Modifier.size(14.dp)) {
                        val w = size.width; val h = size.height
                        val tri = androidx.compose.ui.graphics.Path().apply { moveTo(0f, 0f); lineTo(w * 0.72f, h / 2); lineTo(0f, h); close() }
                        drawPath(tri, c.labelPrimary)
                        drawRect(c.labelPrimary, Offset(w * 0.82f, 0f), Size(w * 0.16f, h))
                    }
                    if (cost) Text("+1:00", style = CanoneType.footnote.copy(fontFeatureSettings = "tnum"), color = c.labelSecondary)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            if (t.running) CButton("Ferma", { r.plan.timerStop() }, Modifier.weight(1f))
            else CButton("Avvia", { r.plan.timerStart("") }, Modifier.weight(1f), kind = ButtonKind.Primary)
            CButton(if (focus) "Salta alla pausa" else "Salta la pausa", {
                r.plan.timerPhase(if (focus) (if ((t.cycle + 1) % rounds == 0) "long" else "short") else "focus")
            }, Modifier.weight(1f))
        }
    }
}


/** Fino a tre colori vivi e distanti tra loro presi dalla copertina: servono alla luce d'ambiente. */
internal fun moodColors(art: android.graphics.Bitmap?): List<Color> {
    if (art == null) return emptyList()
    return try {
        val b = android.graphics.Bitmap.createScaledBitmap(art, 12, 12, true)
        val hsv = FloatArray(3)
        val all = (0 until 144).map { android.graphics.Color.colorToHSV(b.getPixel(it % 12, it / 12), hsv); hsv.clone() }.sortedByDescending { it[1] * it[2] }
        val picked = ArrayList<FloatArray>()
        for (h in all) {
            if (picked.size >= 3) break
            if (picked.none { val d = kotlin.math.abs(it[0] - h[0]); minOf(d, 360f - d) < 40f }) picked += h
        }
        picked.map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it[0], it[1].coerceAtLeast(0.5f), it[2].coerceIn(0.65f, 1f)))) }
    } catch (_: Exception) { emptyList() }
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
