package it.ponte.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
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
import it.ponte.PlanState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

private const val DAY = 86_400_000L

/** Una riga dell'agenda: evento di Relay o di Google Calendar. */
private class Item(val key: String, val kind: Int, val title: String, val start: Long, val end: Long, val allDay: Boolean, val color: Color, val place: String, val ref: Any? = null)

private class Draft(
    val id: String = "", val devId: Long = -1, val calId: Long = 0, var title: String = "", var start: Long = 0, var end: Long = 0, var allDay: Boolean = false,
    var color: Int = 0, var place: String = "", var note: String = "", var remind: Int = 10,
)

private class Sheet(val item: Item?, val draft: Draft?, val edit: Boolean)

private val REMINDERS = listOf(-1 to "Nessuno", 0 to "All'ora", 10 to "10 min", 30 to "30 min", 60 to "1 ora", 1440 to "1 giorno")
private fun weekStart(t: Long) = addDays(dayStart(t), -((Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.DAY_OF_WEEK) + 5) % 7))

@Composable
fun AgendaScreen(r: Router, sub: String?, onSub: (String?) -> Unit) {
    val c = Canone.colors
    val ctx = LocalContext.current
    val plan by r.plan.state.collectAsState()
    val bridge = remember { CalendarBridge(ctx) }
    var granted by remember { mutableStateOf(bridge.granted()) }
    var tick by remember { mutableIntStateOf(0) }
    val mode = 0
    val now = remember { System.currentTimeMillis() }
    var cursor by remember { mutableLongStateOf(Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis) }
    var selected by remember { mutableLongStateOf(dayStart(now)) }
    var expanded by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var closeTick by remember { mutableIntStateOf(0) }
    var cals by remember { mutableStateOf<List<DevCal>>(emptyList()) }
    var hidden by remember { mutableStateOf(bridge.hidden) }
    var target by remember { mutableStateOf(bridge.target) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = bridge.granted(); tick++ }
    DisposableEffect(granted) {
        val o = bridge.observe { tick++ }
        onDispose { bridge.unobserve(o) }
    }
    androidx.activity.compose.BackHandler(enabled = sheet != null) { closeTick++ }
    androidx.compose.runtime.SideEffect {
        val sh = sheet
        DrawerBar.mode = if (sh == null) 0 else if (sh.edit) 2 else 1
        DrawerBar.onBack = { closeTick++ }
        DrawerBar.onEdit = {
            val cur = sheet
            if (cur?.draft != null) sheet = Sheet(cur.item, cur.draft, true)
            else (cur?.item?.ref as? DevEvent)?.let { e -> try { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, e.id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { DrawerBar.mode = 0 } }

    val first = Calendar.getInstance().apply { timeInMillis = cursor }
    val offset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val gridStart = addDays(dayStart(cursor), -offset)
    val weeks = ((offset + first.getActualMaximum(Calendar.DAY_OF_MONTH) + 6) / 7)
    val rangeFrom = if (mode == 0) gridStart else dayStart(now)
    val rangeTo = if (mode == 0) gridStart + 42 * DAY else dayStart(now) + 45 * DAY

    var dev by remember { mutableStateOf<List<DevEvent>>(emptyList()) }
    LaunchedEffect(rangeFrom, rangeTo, tick, granted, hidden) {
        dev = if (granted) withContext(Dispatchers.IO) { bridge.events(rangeFrom, rangeTo) } else emptyList()
    }
    LaunchedEffect(granted, tick, sub) { cals = if (granted) withContext(Dispatchers.IO) { bridge.calendars() } else emptyList() }

    val items = remember(plan, dev, rangeFrom, rangeTo) {
        val out = ArrayList<Item>()
        plan.events.forEach { e -> if (e.start < rangeTo && maxOf(e.end, e.start + 60_000) > rangeFrom) out += Item("r" + e.id, 0, e.title.ifBlank { "(senza titolo)" }, e.start, e.end, e.allDay, planColor(e.color), e.place, e) }
        dev.forEach { e -> out += Item("g${e.id}-${e.start}", 1, e.title, e.start, e.end, e.allDay, Color(e.color or (0xFF shl 24)), e.place, e) }
        // gli eventi Google copiati da un altro dispositivo (qui non c'è l'account)
        plan.ext.filter { it.device != r.deviceId && it.start < rangeTo && maxOf(it.end, it.start + 60_000) > rangeFrom }
            .forEach { e -> out += Item(e.id, 2, e.title, e.start, e.end, e.allDay, Color(e.color or (0xFF shl 24)), e.place, e) }
        out.sortedWith(compareBy<Item> { it.start }.thenBy { !it.allDay })
    }
    fun scan(day: Long) = items.filter { it.start < day + DAY && maxOf(it.end, it.start + 60_000) > day }.sortedWith(compareBy<Item> { !it.allDay }.thenBy { it.start })
    // eventi per giorno calcolati una volta per griglia (non 42 filtri a ogni tocco)
    val byDay = remember(items, gridStart) { HashMap<Long, List<Item>>(64).apply { for (k in 0 until 42) { val d = addDays(gridStart, k); put(d, scan(d)) } } }
    val dotsByDay = remember(byDay) { byDay.mapValues { e -> e.value.map { it.color }.distinct().take(3) } }
    fun on(day: Long) = byDay[day] ?: scan(day)

    fun newDraft(day: Long): Draft {
        val t = if (day == dayStart(now)) Calendar.getInstance().apply { timeInMillis = now; add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        else day + 9 * 3_600_000L
        return Draft(calId = target, start = t, end = t + 3_600_000L)
    }
    fun open(i: Item) {
        val dr: Draft? = when (i.kind) {
            0 -> (i.ref as Ev).let { e -> Draft(e.id, -1, 0, e.title, e.start, if (e.allDay) e.end - DAY else e.end, e.allDay, e.color, e.place, e.note, e.remind) }
            1 -> (i.ref as DevEvent).let { e ->
                if (cals.firstOrNull { it.id == e.calId }?.writable == true) Draft("", e.id, e.calId, e.title, e.start, if (e.allDay) e.end - DAY else e.end, e.allDay, 0, e.place, e.note, -1) else null
            }
            else -> null
        }
        sheet = Sheet(i, dr, false)
    }
    fun remove(i: Item) {
        when (i.kind) { 0 -> r.plan.removeEvent((i.ref as Ev).id); 1 -> { bridge.delete((i.ref as DevEvent).id); tick++ } }
    }
    val top: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TodoButton(plan) { onSub("todo") }
            TomatoButton(plan) { onSub("study") }
            CButton("Google", { onSub("cals") }, kind = ButtonKind.Secondary)
        }
    }

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
            "set" -> StudySettings(r) { onSub("study") }
            "todo" -> ScreenList("To-do") {
                item { BackRow("Agenda", "To-do") { onSub(null) } }
                todoItems(r, plan)
            }
            "study" -> ScreenList("Studio") {
                item { BackRow("Agenda", "Studio") { onSub(null) } }
                studyItems(r, plan) { onSub("set") }
            }
            "cals" -> CalendarsPage(ctx, bridge, granted, cals, hidden, target, { hidden = it; bridge.hidden = it; tick++ }, { target = it; bridge.target = it }, { ask.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }) { onSub(null) }
            else -> Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(start = Space.s4, end = Space.s4, top = Space.s2, bottom = Space.s3).widthIn(max = 720.dp)) { top() }
                    Box(Modifier.weight(1f).fillMaxWidth()) { val md = 0
                        when (md) {
                            0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                Column(Modifier.widthIn(max = 720.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                                    MonthCard(
                                        if (expanded) cursor else selected, selected, expanded, weeks, gridStart, { dotsByDay[it] ?: emptyList() }, now,
                                        onPick = { d -> selected = d; haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove); if (Calendar.getInstance().apply { timeInMillis = d }.get(Calendar.MONTH) != first.get(Calendar.MONTH)) cursor = Calendar.getInstance().apply { timeInMillis = d; set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis },
                                        onStep = { dir ->
                                            if (expanded) cursor = Calendar.getInstance().apply { timeInMillis = cursor; add(Calendar.MONTH, dir) }.timeInMillis
                                            else { selected = addDays(selected, 7 * dir); cursor = Calendar.getInstance().apply { timeInMillis = selected; set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis }
                                        },
                                        onToday = { selected = dayStart(now); cursor = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis },
                                        onExpand = { expanded = it },
                                    )
                                    DayDrawer(selected, on(selected), expanded, { expanded = it }, { open(it) }, { remove(it) }, Modifier.weight(1f))
                                }
                            }
                            else -> ScreenList("Agenda") {
                                studyItems(r, plan) { onSub("set") }
                            }
                        }
                    }
                }
                if (mode != 2) Fab(Modifier.align(Alignment.BottomEnd).padding(end = Space.s4, bottom = LocalBarInset.current + 4.dp)) { sheet = Sheet(null, newDraft(if (mode == 0) selected else dayStart(now)), true) }
                sheet?.let { sh ->
                    SheetHost({ sheet = null }, closeTick) { close ->
                        if (sh.edit && sh.draft != null) EditorSheet(r, sh.draft, cals, bridge, ctx) { tick++; close() }
                        else if (sh.item != null) DetailSheet(sh.item, cals)
                    }
                }
            }
        }
    }
}

@Composable
private fun Fab(modifier: Modifier, onClick: () -> Unit) {
    val c = Canone.colors
    val t = rememberDropTouch()
    Box(
        modifier.size(60.dp).dropGestures(t, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.matchParentSize().dropDeform(t).drop(isDark(), fill = Brand.gradient, press = { t.press.value }, touch = t))
        Text("+", style = CanoneType.title2.copy(fontWeight = FontWeight.Medium), color = Color.White)
    }
}

/** Il mese: scorri di lato per cambiare mese (o settimana), trascina in su per ridurlo a una sola settimana. */
@Composable
private fun MonthCard(
    shown: Long, selected: Long, expanded: Boolean, weeks: Int, gridStart: Long, dots: (Long) -> List<Color>, now: Long,
    onPick: (Long) -> Unit, onStep: (Int) -> Unit, onToday: () -> Unit, onExpand: (Boolean) -> Unit,
) {
    val c = Canone.colors
    val shape = RoundedCornerShape(Radius.xl)
    val start = if (expanded) gridStart else weekStart(selected)
    val rows = if (expanded) weeks else 1
    val curMonth = Calendar.getInstance().apply { timeInMillis = shown }.get(Calendar.MONTH)
    var dx by remember { mutableStateOf(0f) }
    var dy by remember { mutableStateOf(0f) }
    Column(
        Modifier.padding(horizontal = Space.s4).fillMaxWidth().clip(shape).glass(shape)
            .animateContentSize(spring(1f, 420f))
            .padding(horizontal = Space.s3, vertical = Space.s2),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            CButton("‹", { onStep(-1) }, kind = ButtonKind.Tertiary)
            AnimatedContent(cap(fmt(shown, "LLLL yyyy")), transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "mese") { t ->
                Text(t, style = CanoneType.title3, color = c.labelPrimary, modifier = Modifier.dropClickable(onToday))
            }
            CButton("›", { onStep(1) }, kind = ButtonKind.Tertiary)
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("L", "M", "M", "G", "V", "S", "D").forEach { Text(it, Modifier.weight(1f), style = CanoneType.footnote, color = c.labelTertiary, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        // griglia leggera: un solo gestore di tocco per tutte le celle e la selezione è una pastiglia che scivola (solo disegno)
        val today = remember(now) { dayStart(now) }
        val cells = remember(start, rows, curMonth) {
            List(rows * 7) { k -> val d = addDays(start, k); val cal = Calendar.getInstance().apply { timeInMillis = d }; Triple(d, cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) == curMonth) }
        }
        val selIdx = cells.indexOfFirst { it.first == selected }
        val selX = remember { androidx.compose.animation.core.Animatable(0f) }
        val selY = remember { androidx.compose.animation.core.Animatable(0f) }
        val selA = remember { androidx.compose.animation.core.Animatable(0f) }
        val first = remember { booleanArrayOf(true) }
        LaunchedEffect(selIdx, rows) {
            if (selIdx < 0) { selA.animateTo(0f, tween(120)); return@LaunchedEffect }
            val tx = (selIdx % 7).toFloat(); val ty = (selIdx / 7).toFloat()
            if (first[0] || selA.value < 0.05f) { selX.snapTo(tx); selY.snapTo(ty); first[0] = false; selA.animateTo(1f, tween(140)) }
            else { selA.snapTo(1f); launch { selX.animateTo(tx, spring(0.8f, 700f)) }; selY.animateTo(ty, spring(0.8f, 700f)) }
        }
        val accent = c.accent
        val cellH = 46.dp
        Column(
            Modifier.fillMaxWidth()
                .drawBehind {
                    val cw = size.width / 7f; val ch = cellH.toPx(); val p = 2.dp.toPx()
                    if (selA.value > 0.01f) drawRoundRect(accent.copy(alpha = selA.value), Offset(selX.value * cw + p, selY.value * ch + p), Size(cw - 2 * p, ch - 2 * p), CornerRadius(Radius.md.toPx()))
                }
                .pointerInput(cells) {
                    detectTapGestures { o ->
                        val col = (o.x / (size.width / 7f)).toInt().coerceIn(0, 6)
                        val row = (o.y / cellH.toPx()).toInt().coerceIn(0, rows - 1)
                        cells.getOrNull(row * 7 + col)?.let { onPick(it.first) }
                    }
                },
        ) {
            for (row in 0 until rows) Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val (day, num, inMonth) = cells[row * 7 + col]
                    val isSel = day == selected
                    val isToday = day == today
                    Column(Modifier.weight(1f).height(cellH), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(
                            num.toString(), style = CanoneType.body.copy(fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal),
                            color = if (isSel) Color.White else if (isToday) c.accent else if (inMonth || !expanded) c.labelPrimary else c.labelDisabled,
                        )
                        Row(Modifier.height(7.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                            dots(day).forEach { Box(Modifier.size(5.dp).background(if (isSel) Color.White else it, CircleShape)) }
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(18.dp).dropClickable({ onExpand(!expanded) }, corner = Radius.md), contentAlignment = Alignment.Center) {
            Box(Modifier.width(34.dp).height(4.dp).clip(CircleShape).background(c.lineStrong))
        }
    }
}

/** Il cassetto del giorno: sale a riempire lo schermo quando il mese si riduce. */
@Composable
private fun DayDrawer(day: Long, list: List<Item>, expanded: Boolean, onExpand: (Boolean) -> Unit, open: (Item) -> Unit, remove: (Item) -> Unit, modifier: Modifier) {
    val c = Canone.colors
    val shape = RoundedCornerShape(Radius.xl)
    var dy by remember { mutableStateOf(0f) }
    Column(
        modifier.padding(horizontal = Space.s4).padding(bottom = (LocalBarInset.current - 4.dp).coerceAtLeast(0.dp)).fillMaxWidth().clip(shape).glass(shape),
    ) {
        Column(
            Modifier.fillMaxWidth().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { onExpand(!expanded) }.padding(horizontal = Space.s5, vertical = Space.s3),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(34.dp).height(4.dp).clip(CircleShape).background(c.lineStrong))
            Row(Modifier.fillMaxWidth().padding(top = Space.s2), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(dayLabel(day), style = CanoneType.title3, color = c.labelPrimary)
                    Text(cap(fmt(day, "d MMMM yyyy")), style = CanoneType.footnote, color = c.labelTertiary)
                }
                if (list.isNotEmpty()) Text(list.size.toString(), style = CanoneType.title3, color = c.labelTertiary)
            }
        }
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = Space.s4, end = Space.s4, top = Space.s1, bottom = 76.dp),
            verticalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            if (list.isEmpty()) item { Text("Libero", style = CanoneType.footnote, color = c.labelTertiary, modifier = Modifier.padding(Space.s2)) }
            items(list.size, key = { list[it].key + day }) { i -> SwipeRow(list[i].kind <= 1, { remove(list[i]) }) { EventRow(list[i]) { open(list[i]) } } }
        }
    }
}

/** Trascina a sinistra per eliminare. */
@Composable
private fun SwipeRow(canDelete: Boolean, onDelete: () -> Unit, content: @Composable () -> Unit) {
    // niente swipe: si elimina dal dettaglio dell'evento
    content()
}

@Composable
private fun EventRow(i: Item, onClick: () -> Unit) {
    val c = Canone.colors
    val shape = RoundedCornerShape(Radius.lg)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.labelPrimary.copy(alpha = 0.07f)).dropClickable(onClick, corner = Radius.lg).padding(Space.s3),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Box(Modifier.width(4.dp).height(38.dp).clip(CircleShape).background(i.color))
        Column(Modifier.weight(1f)) {
            Text(i.title, style = CanoneType.bodyMedium, color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val whenText = if (i.allDay) "Tutto il giorno" else fmt(i.start, "HH:mm") + " – " + fmt(i.end, "HH:mm")
            Text(whenText + (if (i.place.isNotEmpty()) " · " + i.place else ""), style = CanoneType.footnote, color = c.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (i.kind != 0) Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF4285F4)))
    }
}

/** Cassetto che sale dal basso con velo scuro; si chiude toccando fuori o trascinando giù. */
@Composable
internal fun SheetHost(onDismiss: () -> Unit, closeTick: Int, content: @Composable (close: () -> Unit) -> Unit) {
    val c = Canone.colors
    val drag = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    fun close() { scope.launch { shown = false; kotlinx.coroutines.delay(230); onDismiss() } }
    var lastTick by remember { mutableIntStateOf(closeTick) }
    LaunchedEffect(closeTick) { if (closeTick != lastTick) { lastTick = closeTick; close() } }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedVisibility(shown, enter = fadeIn(tween(220)), exit = fadeOut(tween(180))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).pointerInput(Unit) { detectTapGestures { close() } })
        }
        androidx.compose.animation.AnimatedVisibility(
            shown, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBarInset.current + Space.s3),
            enter = androidx.compose.animation.slideInVertically(spring(0.88f, 360f)) { it }, exit = androidx.compose.animation.slideOutVertically(tween(220)) { it },
        ) {
            val shape = RoundedCornerShape(Radius.xl)
            Column(
                Modifier.widthIn(max = 560.dp).padding(horizontal = Space.s4).fillMaxWidth().heightIn(max = maxHeight * 0.72f).offset { androidx.compose.ui.unit.IntOffset(0, drag.value.toInt()) }
                    .clip(shape).glass(shape, acrylic = true).imePadding()
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Box(
                    Modifier.fillMaxWidth().height(28.dp).pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = { scope.launch { if (drag.value > 160f) close() else drag.animateTo(0f, spring(0.8f, 400f)) } },
                            onDragCancel = { scope.launch { drag.animateTo(0f, spring(0.8f, 400f)) } },
                        ) { _, d -> scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } }
                    },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.width(38.dp).height(4.dp).clip(CircleShape).background(c.lineStrong)) }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(start = Space.s5, end = Space.s5, bottom = Space.s5), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                    content { close() }
                }
            }
        }
    }
}

@Composable
private fun EditorSheet(r: Router, d: Draft, cals: List<DevCal>, bridge: CalendarBridge, ctx: android.content.Context, done: () -> Unit) {
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
    CField("Titolo", title, { title = it }, "Nuovo evento")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Tutto il giorno", style = CanoneType.body, color = c.labelPrimary)
        GlassSwitch(allDay, { allDay = it })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
        PickField(if (allDay) "Dal" else "Giorno", fmt(start, "EEE d MMM"), Modifier.weight(1f)) {
            pickDate(ctx, start) { t -> val len = end - start; start = t; if (allDay) { if (end < start) end = start } else end = start + len.coerceAtLeast(0) }
        }
        if (allDay) PickField("Al", fmt(end, "EEE d MMM"), Modifier.weight(1f)) { pickDate(ctx, end) { t -> end = maxOf(t, dayStart(start)) } }
        else {
            PickField("Inizio", fmt(start, "HH:mm"), Modifier.weight(1f)) { pickTime(ctx, start) { t -> val len = (end - start).coerceAtLeast(30 * 60_000L); start = t; end = t + len } }
            PickField("Fine", fmt(end, "HH:mm"), Modifier.weight(1f)) { pickTime(ctx, end) { t -> end = if (t <= start) t + DAY else t } }
        }
    }
    CField("Luogo", place, { place = it }, "Aula, indirizzo o link")
    CField("Note", note, { note = it }, "")
    if (!google) ColorDots(color) { color = it }
    if (!(existing && google)) ChipRow { REMINDERS.forEach { (m, l) -> Chip(l, remind == m) { remind = m } } }
    if (!existing && cals.any { it.writable }) ChipRow {
        Chip("Relay", cal == 0L) { cal = 0L }
        cals.filter { it.writable }.forEach { k -> Chip(k.name.ifBlank { k.account }, cal == k.id, Color(k.color or (0xFF shl 24))) { cal = k.id } }
    }
    val save: () -> Unit = save@{
        if (title.isBlank()) { toast(ctx, "Scrivi un titolo"); return@save }
        val s = if (allDay) dayStart(start) else start
        val e = if (allDay) dayStart(maxOf(end, start)) + DAY else maxOf(end, start + 15 * 60_000L)
        when {
            d.devId > 0 -> bridge.update(d.devId, title.trim(), s, e, allDay, place.trim(), note.trim())
            cal != 0L && d.id.isEmpty() -> if (bridge.insert(cal, title.trim(), s, e, allDay, place.trim(), note.trim(), remind) < 0) { toast(ctx, "Non riesco a scrivere su Google Calendar"); return@save }
            else -> r.plan.saveEvent(Ev(d.id, s, e, allDay, title.trim(), color, note.trim(), place.trim(), remind))
        }
        done()
    }
    androidx.compose.runtime.SideEffect { DrawerBar.onConfirm = save }
    if (existing) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(Modifier.size(44.dp).dropClickable({ if (d.devId > 0) bridge.delete(d.devId) else r.plan.removeEvent(d.id); done() }, corner = 100.dp), contentAlignment = Alignment.Center) { Icon24(Ico.TRASH, c.critical, Modifier.size(24.dp)) }
    }
}

/** Il cassetto di lettura di un evento: titolo, quando, dove, note. La matita nella barra apre la modifica. */
@Composable
private fun DetailSheet(i: Item, cals: List<DevCal>) {
    val c = Canone.colors
    val whenText = if (i.allDay) {
        val last = i.end - DAY
        if (dayStart(last) <= dayStart(i.start)) cap(fmt(i.start, "EEEE d MMMM")) else cap(fmt(i.start, "d MMM")) + " – " + fmt(last, "d MMM")
    } else cap(fmt(i.start, "EEEE d MMMM")) + "\n" + fmt(i.start, "HH:mm") + " – " + fmt(i.end, "HH:mm")
    val note = when (val ref = i.ref) { is Ev -> ref.note; is DevEvent -> ref.note; else -> "" }
    val source = when (i.kind) { 0 -> "Relay"; 1 -> (i.ref as DevEvent).let { e -> cals.firstOrNull { it.id == e.calId }?.let { k -> k.name.ifBlank { k.account } } ?: "Google" }; else -> "Google" }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
        Box(Modifier.padding(top = 6.dp).width(5.dp).height(44.dp).clip(CircleShape).background(i.color))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(i.title, style = CanoneType.title2, color = c.labelPrimary)
            Text(whenText, style = CanoneType.body, color = c.labelSecondary)
        }
    }
    if (i.place.isNotEmpty()) DetailRow("Luogo", i.place)
    if (note.isNotEmpty()) DetailRow("Note", note)
    DetailRow("Calendario", source)
}

@Composable
private fun DetailRow(label: String, value: String) {
    val c = Canone.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(c.labelPrimary.copy(alpha = 0.07f)).padding(Space.s3), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = CanoneType.footnote, color = c.labelTertiary)
        Text(value, style = CanoneType.body, color = c.labelPrimary)
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
                    CButton("Collega", ask, kind = ButtonKind.Primary)
                }
            } else {
                if (cals.isEmpty()) Text("Nessun calendario", style = CanoneType.footnote, color = c.labelTertiary)
                cals.groupBy { it.account }.forEach { (acc, list) ->
                    CCard {
                        Text(acc.ifBlank { "Sul telefono" }, style = CanoneType.footnote, color = c.labelTertiary)
                        list.forEach { k -> CSwitchRow(k.name.ifBlank { acc }, if (k.writable) null else "Sola lettura", k.id !in hidden) { on -> onHidden(if (on) hidden - k.id else hidden + k.id) } }
                    }
                }
                if (cals.any { it.writable }) CCard {
                    Text("Nuovi eventi", style = CanoneType.bodyMedium, color = c.labelPrimary)
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


/** Tasto To-do: spunta e quante cose restano. */
@Composable
private fun TodoButton(plan: PlanState, onClick: () -> Unit) {
    val c = Canone.colors
    val t = rememberDropTouch()
    val n = plan.tasks.count { !it.done }
    Row(
        Modifier.height(44.dp).dropGestures(t, onClick).dropDeform(t).drop(isDark(), press = { t.press.value }, touch = t).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(22.dp).drop(isDark(), tint = c.accent), contentAlignment = Alignment.Center) { Icon24(Ico.CHECK, Color.White, Modifier.size(13.dp)) }
        if (n > 0) Text("$n", style = CanoneType.bodyMedium.copy(fontFeatureSettings = "tnum"), color = c.labelPrimary)
    }
}

/** Tasto pomodoro: porta allo Studio; col timer acceso mostra il tempo che resta. */
@Composable
private fun TomatoButton(plan: PlanState, onClick: () -> Unit) {
    val c = Canone.colors
    val t = rememberDropTouch()
    val tm = plan.timer
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(tm.running, tm.endAt) { while (tm.running) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    val left = ((tm.endAt - now).coerceAtLeast(0L) / 1000).toInt()
    Row(
        Modifier.height(44.dp).dropGestures(t, onClick).dropDeform(t).drop(isDark(), press = { t.press.value }, touch = t).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
            val w = size.width
            drawCircle(Color(0xFFE5483B), w * 0.40f, Offset(w / 2, w * 0.56f))
            drawCircle(Color.White.copy(alpha = 0.35f), w * 0.10f, Offset(w * 0.36f, w * 0.44f))
            val leaf = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.5f, w * 0.22f); lineTo(w * 0.26f, w * 0.10f); lineTo(w * 0.42f, w * 0.26f); lineTo(w * 0.5f, w * 0.04f)
                lineTo(w * 0.58f, w * 0.26f); lineTo(w * 0.76f, w * 0.10f); close()
            }
            drawPath(leaf, Color(0xFF3FAE5A))
        }
        if (tm.running) Text("%02d:%02d".format(left / 60, left % 60), style = CanoneType.bodyMedium.copy(fontFeatureSettings = "tnum"), color = c.labelPrimary)
    }
}
