package it.ponte.ui

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.ponte.BodyState
import it.ponte.Entry
import it.ponte.Food
import it.ponte.MEALS
import it.ponte.Nut
import it.ponte.OpenFood
import it.ponte.Router
import it.ponte.Slot
import it.ponte.dayKey
import it.ponte.keyTime
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

/** Cosa si sta per aggiungere al diario: l'alimento scelto e il pasto. */
private class Pending(val food: Food, val meal: Int)

/**
 * Corpo: dashboard con calorie, macro, prossimo pasto del piano, time lapse delle foto e peso; poi diario, alimenti, piano, galleria e obiettivi.
 * La pagina aperta (null = dashboard) vive nella shell come per gli altri moduli.
 */
@Composable
fun BodyScreen(r: Router, sub: String?, onSub: (String?) -> Unit) {
    val ctx = LocalContext.current
    val b by r.body.state.collectAsState()
    val photos by r.body.photos.collectAsState()
    var day by remember { mutableStateOf(dayKey()) }
    var editFood by remember { mutableStateOf<Food?>(null) }
    var foodBack by remember { mutableStateOf("foods") }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var editEntry by remember { mutableStateOf<Entry?>(null) }
    var quickMeal by remember { mutableIntStateOf(-1) }

    AnimatedContent(
        sub,
        transitionSpec = {
            val into = targetState != null
            (fadeIn(tween(260)) + slideInHorizontally(spring(1f, 380f)) { if (into) it / 6 else -it / 6 }) togetherWith
                (fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { if (into) -it / 8 else it / 8 })
        },
        label = "corpo",
    ) { page ->
        when {
            page == "diary" -> DiaryPage(r, b, day, { day = it }, { onSub("pick$it") }, { editEntry = it }, { onSub(null) })
            page != null && page.startsWith("pick") -> {
                val meal = page.removePrefix("pick").toIntOrNull() ?: 0
                PickPage(b, meal, { onSub("diary") }, { f -> pending = Pending(f, meal) }, { quickMeal = meal }) { foodBack = page; editFood = null; onSub("food") }
            }
            page == "foods" -> FoodsPage(b, { onSub(null) }) { f -> foodBack = "foods"; editFood = f; onSub("food") }
            page == "food" -> FoodForm(r, editFood) { onSub(foodBack) }
            page == "plan" -> PlanPage(r, b) { onSub(null) }
            page == "gallery" -> GalleryPage(r, b, photos) { onSub(null) }
            page == "goals" -> GoalsPage(r, b) { onSub(null) }
            else -> Dashboard(r, b, photos, onSub)
        }
    }

    pending?.let { p ->
        QuantitySheet(p.food.name, p.food.per100, p.food.serving.toFloat(), p.food.serving, "Aggiungi", onDismiss = { pending = null }, onDelete = null) { g ->
            r.body.addEntry(day, p.meal, p.food.name, g, p.food.per100 * (g / 100f), p.food.id)
            pending = null
            onSub("diary")
        }
    }
    editEntry?.let { e ->
        val food = b.foods.firstOrNull { it.id == e.foodId }
        val per = if (food != null) food.per100 else if (e.grams > 0f) e.n * (100f / e.grams) else e.n
        QuantitySheet(
            e.name, per, if (e.grams > 0f) e.grams else 100f, food?.serving ?: 100, "Salva", onDismiss = { editEntry = null },
            onDelete = { r.body.removeEntry(e.id); editEntry = null }, fixed = e.grams <= 0f,
        ) { g -> if (e.grams > 0f) r.body.setGrams(e, g); editEntry = null }
    }
    if (quickMeal >= 0) QuickSheet({ quickMeal = -1 }) { name, n ->
        r.body.addEntry(day, quickMeal, name.ifBlank { "Calorie libere" }, 0f, n)
        quickMeal = -1; onSub("diary")
    }
}

// ---------------------------------------------------------------- dashboard

private fun nextSlot(slots: List<Slot>): Pair<Slot, Int>? {
    val cal = Calendar.getInstance()
    val dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    slots.filter { it.dow == dow && it.minute >= minute - 45 }.minByOrNull { it.minute }?.let { return it to 0 }
    for (k in 1..7) slots.filter { it.dow == (dow + k) % 7 }.minByOrNull { it.minute }?.let { return it to k }
    return null
}

internal fun hhmm(m: Int) = "%02d:%02d".format(m / 60, m % 60)

@Composable
private fun Dashboard(r: Router, b: BodyState, photos: List<String>, onSub: (String?) -> Unit) {
    val c = Canone.colors
    val ctx = LocalContext.current
    val today = dayKey()
    val tot = dayTotal(b.entries, today)
    val g = b.goals
    val left = g.kcal - tot.kcal.roundToInt()
    var weightSheet by remember { mutableStateOf(false) }
    ScreenList("Corpo") {
        item {
            GlassTile(Modifier.fillMaxWidth(), Tint.blue, 0, { onSub("diary") }) {
                Column(Modifier.padding(Space.s5), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Oggi", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.labelSecondary)
                        Text(
                            "Obiettivi", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.accent,
                            modifier = Modifier.clip(CircleShape).dropClickable({ onSub("goals") }, corner = 12.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s5), verticalAlignment = Alignment.CenterVertically) {
                        Ring(if (g.kcal > 0) tot.kcal / g.kcal else 0f, Modifier.size(132.dp), 13f) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                RollingText(abs(left).toString(), CanoneType.title2.copy(fontWeight = FontWeight.SemiBold), c.labelPrimary)
                                Text(if (left >= 0) "kcal rimaste" else "kcal oltre", style = CanoneType.footnote.copy(fontSize = 11.sp), color = if (left >= 0) c.labelTertiary else Tint.third)
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                            MacroBar("Proteine", tot.p, g.p, MacroP)
                            MacroBar("Carboidrati", tot.c, g.c, MacroC)
                            MacroBar("Grassi", tot.f, g.f, MacroF)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.lineSeparator))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                        Stat("Mangiate", num(tot.kcal) + " kcal", Modifier.weight(1f))
                        Stat("Fibre", num(tot.fiber) + " / ${g.fiber} g", Modifier.weight(1f))
                        Stat("Zuccheri", num(tot.sugar) + " g", Modifier.weight(1f))
                        Stat("Sale", num(tot.salt) + " g", Modifier.weight(1f))
                    }
                }
            }
        }
        item { NowCard(r, b, today) { onSub("plan") } }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Action("+", "Pasto", true) { onSub("diary") }
                Action("", "Piano", false, glyph = 11) { onSub("plan") }
                Action("", "Foto", false, glyph = 22) { BodyCam.open.value = true }
                Action("", "Alimenti", false, glyph = 2) { onSub("foods") }
            }
        }
        item { TimeLapseCard(r, b, photos, { onSub("gallery") }) { BodyCam.open.value = true } }
        item {
            val pts = b.weights.filter { keyTime(it.day) >= addDays(System.currentTimeMillis(), -60) }
            GlassTile(Modifier.fillMaxWidth(), Tint.blue, 2, { weightSheet = true }) {
                Column(Modifier.padding(Space.s5), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                    val last = b.weights.lastOrNull()
                    val ref = b.weights.lastOrNull { keyTime(it.day) <= addDays(System.currentTimeMillis(), -7) } ?: b.weights.firstOrNull()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Peso", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.labelSecondary)
                            Text(if (last != null) kg(last.kg) + " kg" else "—", style = CanoneType.title2.copy(fontFeatureSettings = "tnum"), color = c.labelPrimary)
                        }
                        if (last != null && ref != null && ref.day != last.day) {
                            val d = last.kg - ref.kg
                            Text((if (d > 0) "+" else if (d < 0) "−" else "") + kg(abs(d)) + " kg", style = CanoneType.bodyMedium.copy(fontFeatureSettings = "tnum"), color = c.labelSecondary)
                        }
                    }
                    if (pts.size >= 2) WeightChart(pts, b.goals.goalKg10.takeIf { it > 0 }?.let { it / 10f }, Modifier.fillMaxWidth().height(120.dp))
                }
            }
        }
        item {
            GlassTile(Modifier.fillMaxWidth(), Tint.blue, 3) {
                Column(Modifier.padding(Space.s5), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                    val days = (0..6).map { dayKey(addDays(System.currentTimeMillis(), -it)) }
                    val logged = days.map { dayTotal(b.entries, it).kcal }.filter { it > 0f }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Ultimi 7 giorni", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.labelSecondary)
                        if (logged.isNotEmpty()) Text("media " + logged.average().roundToInt() + " kcal", style = CanoneType.footnote.copy(fontFeatureSettings = "tnum"), color = c.labelTertiary)
                    }
                    WeekBars(b.entries, g.kcal, Modifier.fillMaxWidth())
                }
            }
        }
    }
    if (weightSheet) {
        val cur = b.weights.lastOrNull()?.kg10 ?: 700
        WeightSheet(b.weights.firstOrNull { it.day == today }?.kg10 ?: cur, { weightSheet = false }) { v -> r.body.setWeight(today, v); weightSheet = false }
    }
}

/** Il prossimo pasto del piano: che cosa mangiare adesso, con un tasto per registrarlo nel diario. */
@Composable
private fun NowCard(r: Router, b: BodyState, today: String, onOpen: () -> Unit) {
    val c = Canone.colors
    val next = nextSlot(b.slots)
    GlassTile(Modifier.fillMaxWidth(), Tint.blue, 1, onOpen) {
        Column(Modifier.padding(Space.s5), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            if (next == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Piano settimanale", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.labelSecondary)
                    Text("Imposta", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
                }
            } else {
                val (s, ahead) = next
                val tot = s.total
                val logged = s.items.isNotEmpty() && s.items.all { i -> b.entries.any { e -> e.day == today && e.meal == s.meal && e.name == i.name && e.foodId == i.foodId } }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (ahead == 0) "Adesso" else if (ahead == 1) "Domani" else cap(fmt(addDays(System.currentTimeMillis(), ahead), "EEEE"))) + " · " + hhmm(s.minute),
                        style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.labelSecondary,
                    )
                    Text(num(tot.kcal) + " kcal", style = CanoneType.footnote.copy(fontFeatureSettings = "tnum"), color = c.labelTertiary)
                }
                Text(s.label.ifBlank { MEALS[s.meal] }, style = CanoneType.title3, color = c.labelPrimary)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    s.items.forEach { i ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(i.name, style = CanoneType.body, color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(num(i.grams) + " g", style = CanoneType.body.copy(fontFeatureSettings = "tnum"), color = c.labelTertiary)
                        }
                    }
                }
                if (ahead == 0 && s.items.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s4), verticalAlignment = Alignment.CenterVertically) {
                        Stat("P", num(tot.p) + " g", tint = MacroP)
                        Stat("C", num(tot.c) + " g", tint = MacroC)
                        Stat("G", num(tot.f) + " g", tint = MacroF)
                        Box(Modifier.weight(1f))
                        if (logged) Text("Registrato", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
                        else CButton("Registra", { r.body.logSlot(s, today) }, kind = ButtonKind.Primary)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- diario

@Composable
private fun DiaryPage(
    r: Router, b: BodyState, day: String, setDay: (String) -> Unit, onAdd: (Int) -> Unit, onEdit: (Entry) -> Unit, onBack: () -> Unit,
) {
    val c = Canone.colors
    val g = b.goals
    val entries = b.entries.filter { it.day == day }
    val tot = entries.fold(Nut()) { a, e -> a + e.n }
    fun step(d: Int) = setDay(dayKey(addDays(keyTime(day), d)))
    ScreenList("Diario") {
        item { BackRow("Corpo", "Diario", onBack) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).dropClickable({ step(-1) }, corner = 18.dp), contentAlignment = Alignment.Center) { Text("‹", style = CanoneType.title3, color = c.labelSecondary) }
                Text(dayLabel(keyTime(day)), style = CanoneType.bodyMedium, color = c.labelPrimary, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Box(Modifier.size(36.dp).clip(CircleShape).dropClickable({ step(1) }, corner = 18.dp), contentAlignment = Alignment.Center) { Text("›", style = CanoneType.title3, color = c.labelSecondary) }
            }
        }
        item {
            GlassTile(Modifier.fillMaxWidth(), Tint.blue, 0) {
                Row(Modifier.padding(Space.s5), horizontalArrangement = Arrangement.spacedBy(Space.s5), verticalAlignment = Alignment.CenterVertically) {
                    Ring(if (g.kcal > 0) tot.kcal / g.kcal else 0f, Modifier.size(96.dp), 10f) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            RollingText(tot.kcal.roundToInt().toString(), CanoneType.title3.copy(fontWeight = FontWeight.SemiBold), c.labelPrimary)
                            Text("/ ${g.kcal}", style = CanoneType.footnote.copy(fontSize = 11.sp, fontFeatureSettings = "tnum"), color = c.labelTertiary)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                        MacroBar("Proteine", tot.p, g.p, MacroP)
                        MacroBar("Carboidrati", tot.c, g.c, MacroC)
                        MacroBar("Grassi", tot.f, g.f, MacroF)
                    }
                }
            }
        }
        MEALS.forEachIndexed { m, name ->
            item {
                val list = entries.filter { it.meal == m }
                GlassTile(Modifier.fillMaxWidth(), Tint.blue, m + 1) {
                    Column(Modifier.padding(horizontal = Space.s5, vertical = Space.s3)) {
                        Row(Modifier.fillMaxWidth().padding(vertical = Space.s2), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(name, style = CanoneType.bodyMedium, color = c.labelPrimary)
                                if (list.isNotEmpty()) Text(num(list.sumOf { it.n.kcal.toDouble() }.toFloat()) + " kcal", style = CanoneType.footnote.copy(fontFeatureSettings = "tnum"), color = c.labelTertiary)
                            }
                            RoundGlass(36.dp, true, { onAdd(m) }) { Text("+", style = CanoneType.title3, color = Color.White) }
                        }
                        list.forEach { e ->
                            Box(Modifier.fillMaxWidth().height(1.dp).background(c.lineSeparator))
                            Row(
                                Modifier.fillMaxWidth().dropClickable({ onEdit(e) }, corner = Radius.md).padding(vertical = Space.s3),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.name, style = CanoneType.body, color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        (if (e.grams > 0f) num(e.grams) + " g · " else "") + "P ${num(e.n.p)} · C ${num(e.n.c)} · G ${num(e.n.f)}",
                                        style = CanoneType.footnote.copy(fontSize = 12.sp, fontFeatureSettings = "tnum"), color = c.labelTertiary, maxLines = 1,
                                    )
                                }
                                Text(num(e.n.kcal), style = CanoneType.body.copy(fontFeatureSettings = "tnum"), color = c.labelPrimary)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- scelta alimento

@Composable
private fun FoodRow(f: Food, onClick: () -> Unit) {
    val c = Canone.colors
    Row(
        Modifier.fillMaxWidth().dropClickable(onClick, corner = Radius.md).padding(vertical = Space.s3),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Column(Modifier.weight(1f)) {
            Text(f.name, style = CanoneType.body, color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (f.brand.isNotBlank()) f.brand + " · " else "") + "P ${num(f.per100.p)} · C ${num(f.per100.c)} · G ${num(f.per100.f)}",
                style = CanoneType.footnote.copy(fontSize = 12.sp, fontFeatureSettings = "tnum"), color = c.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(num(f.per100.kcal), style = CanoneType.body.copy(fontFeatureSettings = "tnum"), color = c.labelPrimary)
            Text("kcal / 100 g", style = CanoneType.footnote.copy(fontSize = 10.sp), color = c.labelTertiary)
        }
    }
}

private fun List<Food>.matching(q: String) = if (q.isBlank()) this else filter { (it.name + " " + it.brand).contains(q.trim(), ignoreCase = true) }

@Composable
private fun PickPage(b: BodyState, meal: Int, onBack: () -> Unit, onPick: (Food) -> Unit, onQuick: () -> Unit, onNew: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val recent = remember(b.entries, b.foods) {
        b.entries.sortedByDescending { it.ts }.mapNotNull { e -> b.foods.firstOrNull { it.id == e.foodId } }.distinctBy { it.id }.take(6)
    }
    val list = if (q.isBlank()) b.foods else b.foods.matching(q)
    ScreenList(MEALS[meal]) {
        item { BackRow("Diario", MEALS[meal], onBack) }
        item { SearchField(q, { q = it }, "Cerca un alimento") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                CButton("Nuovo alimento", onNew, Modifier.weight(1f), kind = ButtonKind.Primary)
                CButton("Calorie libere", onQuick, Modifier.weight(1f))
            }
        }
        if (q.isBlank() && recent.isNotEmpty()) item {
            GlassTile(Modifier.fillMaxWidth(), Tint.blue, 0) {
                Column(Modifier.padding(horizontal = Space.s5, vertical = Space.s3)) {
                    Text("Recenti", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = Canone.colors.labelSecondary, modifier = Modifier.padding(vertical = Space.s2))
                    recent.forEach { FoodRow(it) { onPick(it) } }
                }
            }
        }
        item {
            GlassTile(Modifier.fillMaxWidth(), Tint.blue, 1) {
                Column(Modifier.padding(horizontal = Space.s5, vertical = Space.s3)) {
                    if (list.isEmpty()) Text(if (b.foods.isEmpty()) "Nessun alimento salvato" else "Nessun risultato", style = CanoneType.body, color = Canone.colors.labelTertiary, modifier = Modifier.padding(vertical = Space.s3))
                    list.forEachIndexed { i, f ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Canone.colors.lineSeparator))
                        FoodRow(f) { onPick(f) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FoodsPage(b: BodyState, onBack: () -> Unit, onEdit: (Food?) -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = b.foods.matching(q)
    ScreenList("Alimenti") {
        item { BackRow("Corpo", "Alimenti", onBack) }
        item { SearchField(q, { q = it }, "Cerca un alimento") }
        item { CButton("Nuovo alimento", { onEdit(null) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary) }
        item {
            GlassTile(Modifier.fillMaxWidth(), Tint.blue, 0) {
                Column(Modifier.padding(horizontal = Space.s5, vertical = Space.s3)) {
                    if (list.isEmpty()) Text("Nessun alimento", style = CanoneType.body, color = Canone.colors.labelTertiary, modifier = Modifier.padding(vertical = Space.s3))
                    list.forEachIndexed { i, f ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Canone.colors.lineSeparator))
                        FoodRow(f) { onEdit(f) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- scheda alimento

@Composable
private fun FoodForm(r: Router, initial: Food?, onDone: () -> Unit) {
    val c = Canone.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var brand by remember { mutableStateOf(initial?.brand.orEmpty()) }
    fun s(x: Float?) = if (x == null || x == 0f) "" else num(x).replace('.', ',')
    var serving by remember { mutableStateOf(initial?.serving?.toString() ?: "100") }
    var kcal by remember { mutableStateOf(s(initial?.per100?.kcal)) }
    var p by remember { mutableStateOf(s(initial?.per100?.p)) }
    var cb by remember { mutableStateOf(s(initial?.per100?.c)) }
    var sugar by remember { mutableStateOf(s(initial?.per100?.sugar)) }
    var fat by remember { mutableStateOf(s(initial?.per100?.f)) }
    var fiber by remember { mutableStateOf(s(initial?.per100?.fiber)) }
    var salt by remember { mutableStateOf(s(initial?.per100?.salt)) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Food>?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun fill(f: Food) {
        name = f.name; brand = f.brand; serving = f.serving.toString()
        kcal = s(f.per100.kcal); p = s(f.per100.p); cb = s(f.per100.c); sugar = s(f.per100.sugar); fat = s(f.per100.f); fiber = s(f.per100.fiber); salt = s(f.per100.salt)
        results = null
    }
    ScreenList(if (initial == null) "Nuovo alimento" else "Alimento") {
        item { BackRow("Alimenti", if (initial == null) "Nuovo" else "Modifica", onDone) }
        item {
            GlassTile(Modifier.fillMaxWidth(), Tint.blue, 0) {
                Column(Modifier.padding(Space.s5), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.Bottom) {
                        Box(Modifier.weight(1f)) { SearchField(query, { query = it }, "Cerca online o codice a barre") }
                        CButton("Cerca", {
                            if (query.isNotBlank()) { busy = true; scope.launch { results = OpenFood.search(query); busy = false } }
                        }, busy = busy)
                    }
                    results?.let { list ->
                        if (list.isEmpty()) Text("Nessun risultato", style = CanoneType.footnote, color = c.labelTertiary)
                        list.forEachIndexed { i, f ->
                            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.lineSeparator))
                            FoodRow(f) { fill(f) }
                        }
                    }
                }
            }
        }
        item {
            GlassTile(Modifier.fillMaxWidth(), Tint.blue, 1) {
                Column(Modifier.padding(Space.s5), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                    CField("Nome", name, { name = it }, "Riso basmati")
                    CField("Marca", brand, { brand = it }, "Facoltativa")
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                        CField("Porzione (g)", serving, { serving = it }, "100", Modifier.weight(1f), number = true)
                        CField("kcal / 100 g", kcal, { kcal = it }, "0", Modifier.weight(1f), number = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                        CField("Proteine (g)", p, { p = it }, "0", Modifier.weight(1f), number = true)
                        CField("Carboidrati (g)", cb, { cb = it }, "0", Modifier.weight(1f), number = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                        CField("di cui zuccheri (g)", sugar, { sugar = it }, "0", Modifier.weight(1f), number = true)
                        CField("Grassi (g)", fat, { fat = it }, "0", Modifier.weight(1f), number = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                        CField("Fibre (g)", fiber, { fiber = it }, "0", Modifier.weight(1f), number = true)
                        CField("Sale (g)", salt, { salt = it }, "0", Modifier.weight(1f), number = true)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                if (initial != null) CButton("Elimina", { r.body.removeFood(initial.id); onDone() }, Modifier.weight(1f), kind = ButtonKind.Destructive)
                CButton("Salva", {
                    if (name.isBlank()) toast(ctx, "Serve un nome")
                    else {
                        fun f(x: String) = parseF(x) ?: 0f
                        r.body.saveFood(Food(initial?.id.orEmpty(), name, brand, (parseF(serving) ?: 100f).roundToInt().coerceAtLeast(1), Nut(f(kcal), f(p), f(cb), f(sugar), f(fat), f(fiber), f(salt))))
                        onDone()
                    }
                }, Modifier.weight(1f), kind = ButtonKind.Primary)
            }
        }
    }
}

// ---------------------------------------------------------------- fogli

/** Quantità di un alimento: grammi con scorciatoie e i valori che si aggiornano mentre si scrive. */
@Composable
internal fun QuantitySheet(
    name: String, per100: Nut, initial: Float, serving: Int, confirm: String, onDismiss: () -> Unit, onDelete: (() -> Unit)?, fixed: Boolean = false, onConfirm: (Float) -> Unit,
) {
    val c = Canone.colors
    var text by remember { mutableStateOf(num(initial).replace('.', ',')) }
    val g = parseF(text) ?: 0f
    val n = if (fixed) per100 * (initial / 100f) else per100 * (g / 100f)
    BodySheet(onDismiss) {
        Text(name, style = CanoneType.title3, color = c.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!fixed) {
            CField("Quantità (g)", text, { text = it }, "0", number = true)
            ChipRow {
                listOf(serving, 50, 100, 150, 200, 250).distinct().forEach { v -> Chip(if (v == serving) "Porzione $v g" else "$v g", g == v.toFloat()) { text = v.toString() } }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            Stat("kcal", num(n.kcal), Modifier.weight(1f))
            Stat("Proteine", num(n.p) + " g", Modifier.weight(1f), MacroP)
            Stat("Carboidrati", num(n.c) + " g", Modifier.weight(1f), MacroC)
            Stat("Grassi", num(n.f) + " g", Modifier.weight(1f), MacroF)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            if (onDelete != null) CButton("Elimina", onDelete, Modifier.weight(1f), kind = ButtonKind.Destructive)
            CButton(confirm, { if (g > 0f || fixed) onConfirm(g) }, Modifier.weight(1f), kind = ButtonKind.Primary, enabled = g > 0f || fixed)
        }
    }
}

/** Calorie senza un alimento salvato: si scrivono i valori a mano. */
@Composable
private fun QuickSheet(onDismiss: () -> Unit, onAdd: (String, Nut) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var p by remember { mutableStateOf("") }
    var cb by remember { mutableStateOf("") }
    var f by remember { mutableStateOf("") }
    BodySheet(onDismiss) {
        Text("Calorie libere", style = CanoneType.title3, color = Canone.colors.labelPrimary)
        CField("Nome", name, { name = it }, "Facoltativo")
        CField("kcal", kcal, { kcal = it }, "0", number = true)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            CField("Proteine (g)", p, { p = it }, "0", Modifier.weight(1f), number = true)
            CField("Carboidrati (g)", cb, { cb = it }, "0", Modifier.weight(1f), number = true)
            CField("Grassi (g)", f, { f = it }, "0", Modifier.weight(1f), number = true)
        }
        CButton("Aggiungi", {
            val k = parseF(kcal) ?: ((parseF(p) ?: 0f) * 4 + (parseF(cb) ?: 0f) * 4 + (parseF(f) ?: 0f) * 9)
            if (k > 0f) onAdd(name, Nut(k, parseF(p) ?: 0f, parseF(cb) ?: 0f, 0f, parseF(f) ?: 0f))
        }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
    }
}
