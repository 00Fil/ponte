package it.ponte.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.ponte.BodyState
import it.ponte.Food
import it.ponte.Goals
import it.ponte.MEALS
import it.ponte.Nut
import it.ponte.PlanItem
import it.ponte.Router
import it.ponte.Slot
import java.util.Calendar
import kotlin.math.roundToInt

private val DAYS = listOf("Lun", "Mar", "Mer", "Gio", "Ven", "Sab", "Dom")
private val DAYS_FULL = listOf("Lunedì", "Martedì", "Mercoledì", "Giovedì", "Venerdì", "Sabato", "Domenica")
private val MEAL_TIMES = listOf(8 * 60, 13 * 60, 20 * 60, 16 * 60 + 30)

// ---------------------------------------------------------------- piano settimanale

/** Piano settimanale: per ogni giorno i pasti con orario e alimenti. La dashboard mostra quello in corso. */
@Composable
internal fun PlanPage(r: Router, b: BodyState, onBack: () -> Unit) {
    val c = Canone.colors
    val todayDow = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7
    var dow by remember { mutableIntStateOf(todayDow) }
    var edit by remember { mutableStateOf<Slot?>(null) }
    var copy by remember { mutableStateOf(false) }
    val slots = b.slots.filter { it.dow == dow }
    val tot = slots.fold(Nut()) { a, s -> a + s.total }
    ScreenList("Piano") {
        item { BackRow("Corpo", "Piano", onBack) }
        item {
            ChipRow { DAYS.forEachIndexed { i, d -> Chip(if (i == todayDow && i != dow) "$d ·" else d, dow == i) { dow = i } } }
        }
        item {
            GlassTile(Modifier.fillMaxWidth(), Tint.blue, 0) {
                Column(Modifier.padding(Space.s5), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                    Text(DAYS_FULL[dow], style = CanoneType.title3, color = c.labelPrimary)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                        Stat("kcal", num(tot.kcal) + " / ${b.goals.kcal}", Modifier.weight(1.4f))
                        Stat("Proteine", num(tot.p) + " g", Modifier.weight(1f), MacroP)
                        Stat("Carboidrati", num(tot.c) + " g", Modifier.weight(1f), MacroC)
                        Stat("Grassi", num(tot.f) + " g", Modifier.weight(1f), MacroF)
                    }
                }
            }
        }
        slots.forEachIndexed { i, s ->
            item {
                GlassTile(Modifier.fillMaxWidth(), Tint.blue, i + 1, { edit = s }) {
                    Row(Modifier.padding(Space.s5), horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                        Text(hhmm(s.minute), style = CanoneType.title3.copy(fontFeatureSettings = "tnum"), color = c.accent)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(s.label.ifBlank { MEALS[s.meal] }, style = CanoneType.bodyMedium, color = c.labelPrimary)
                                Text(num(s.total.kcal) + " kcal", style = CanoneType.footnote.copy(fontFeatureSettings = "tnum"), color = c.labelTertiary)
                            }
                            s.items.forEach { it ->
                                Text(
                                    num(it.grams) + " g  " + it.name, style = CanoneType.footnote.copy(fontSize = 13.sp, fontFeatureSettings = "tnum"), color = c.labelSecondary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (s.items.isEmpty()) Text("Vuoto", style = CanoneType.footnote, color = c.labelTertiary)
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                CButton("Aggiungi pasto", {
                    val used = slots.map { it.meal }.toSet()
                    val m = (0..3).firstOrNull { it !in used } ?: 3
                    edit = Slot("", dow, MEAL_TIMES[m], m, "", emptyList())
                }, Modifier.weight(1f), kind = ButtonKind.Primary)
                if (slots.isNotEmpty()) CButton("Copia giorno", { copy = true }, Modifier.weight(1f))
            }
        }
    }
    edit?.let { SlotEditor(r, b, it) { edit = null } }
    if (copy) {
        var pick by remember { mutableStateOf(setOf<Int>()) }
        BodySheet({ copy = false }) {
            Text("Copia ${DAYS_FULL[dow].lowercase()} su", style = CanoneType.title3, color = c.labelPrimary)
            ChipRow {
                Chip("Lun–Ven", pick == (0..4).filter { it != dow }.toSet()) { pick = (0..4).filter { it != dow }.toSet() }
                Chip("Tutti", pick == (0..6).filter { it != dow }.toSet()) { pick = (0..6).filter { it != dow }.toSet() }
            }
            ChipRow { DAYS.forEachIndexed { i, d -> if (i != dow) Chip(d, i in pick) { pick = if (i in pick) pick - i else pick + i } } }
            CButton("Copia", { r.body.copyDay(dow, pick.toList()); copy = false }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = pick.isNotEmpty())
        }
    }
}

@Composable
private fun SlotEditor(r: Router, b: BodyState, slot: Slot, onDismiss: () -> Unit) {
    val c = Canone.colors
    val ctx = LocalContext.current
    var meal by remember { mutableIntStateOf(slot.meal) }
    var minute by remember { mutableIntStateOf(slot.minute) }
    var touched by remember { mutableStateOf(slot.id.isNotEmpty()) }
    var label by remember { mutableStateOf(slot.label) }
    var items by remember { mutableStateOf(slot.items) }
    var picking by remember { mutableStateOf(slot.items.isEmpty()) }
    var q by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf<Food?>(null) }
    var editIdx by remember { mutableIntStateOf(-1) }
    val tot = items.fold(Nut()) { a, i -> a + i.n }
    BodySheet(onDismiss) {
        Text(DAYS_FULL[slot.dow], style = CanoneType.title3, color = c.labelPrimary)
        ChipRow { MEALS.forEachIndexed { i, m -> Chip(m, meal == i) { meal = i; if (!touched) minute = MEAL_TIMES[i] } } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            PickField("Orario", hhmm(minute), Modifier.weight(1f)) {
                val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, minute / 60); set(Calendar.MINUTE, minute % 60) }
                pickTime(ctx, cal.timeInMillis) { t -> val x = Calendar.getInstance().apply { timeInMillis = t }; minute = x.get(Calendar.HOUR_OF_DAY) * 60 + x.get(Calendar.MINUTE); touched = true }
            }
            CField("Nome", label, { label = it }, MEALS[meal], Modifier.weight(1.4f))
        }
        Column {
            items.forEachIndexed { i, it ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.lineSeparator))
                Row(Modifier.fillMaxWidth().dropClickable({ editIdx = i }, corner = Radius.md).padding(vertical = Space.s3), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
                    Text(it.name, style = CanoneType.body, color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(num(it.grams) + " g", style = CanoneType.body.copy(fontFeatureSettings = "tnum"), color = c.labelTertiary)
                    Text(num(it.n.kcal), style = CanoneType.body.copy(fontFeatureSettings = "tnum"), color = c.labelPrimary)
                }
            }
        }
        if (items.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            Stat("kcal", num(tot.kcal), Modifier.weight(1f))
            Stat("Proteine", num(tot.p) + " g", Modifier.weight(1f), MacroP)
            Stat("Carboidrati", num(tot.c) + " g", Modifier.weight(1f), MacroC)
            Stat("Grassi", num(tot.f) + " g", Modifier.weight(1f), MacroF)
        }
        if (!picking) CButton("Aggiungi alimento", { picking = true }, Modifier.fillMaxWidth())
        else {
            SearchField(q, { q = it }, "Cerca un alimento")
            val list = (if (q.isBlank()) b.foods else b.foods.filter { (it.name + " " + it.brand).contains(q.trim(), ignoreCase = true) }).take(8)
            if (list.isEmpty()) Text(if (b.foods.isEmpty()) "Nessun alimento salvato" else "Nessun risultato", style = CanoneType.footnote, color = c.labelTertiary)
            list.forEach { f ->
                Row(Modifier.fillMaxWidth().dropClickable({ qty = f }, corner = Radius.md).padding(vertical = Space.s3), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, style = CanoneType.body, color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(num(f.per100.kcal) + " kcal", style = CanoneType.footnote.copy(fontFeatureSettings = "tnum"), color = c.labelTertiary)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            if (slot.id.isNotEmpty()) CButton("Elimina", { r.body.removeSlot(slot.id); onDismiss() }, Modifier.weight(1f), kind = ButtonKind.Destructive)
            CButton("Salva", { r.body.saveSlot(Slot(slot.id, slot.dow, minute, meal, label.trim(), items)); onDismiss() }, Modifier.weight(1f), kind = ButtonKind.Primary, enabled = items.isNotEmpty())
        }
    }
    qty?.let { f ->
        QuantitySheet(f.name, f.per100, f.serving.toFloat(), f.serving, "Aggiungi", { qty = null }, null) { g ->
            items = items + PlanItem(f.id, f.name, g, f.per100 * (g / 100f)); qty = null; picking = false; q = ""
        }
    }
    if (editIdx in items.indices) {
        val it = items[editIdx]
        val per = if (it.grams > 0f) it.n * (100f / it.grams) else it.n
        QuantitySheet(it.name, per, it.grams, 100, "Salva", { editIdx = -1 }, { items = items.filterIndexed { k, _ -> k != editIdx }; editIdx = -1 }) { g ->
            items = items.mapIndexed { k, x -> if (k == editIdx) x.copy(grams = g, n = per * (g / 100f)) else x }; editIdx = -1
        }
    }
}

// ---------------------------------------------------------------- obiettivi

/** Obiettivi giornalieri: a mano o calcolati dal metabolismo basale (Mifflin-St Jeor), dall'attività e dall'obiettivo. */
@Composable
internal fun GoalsPage(r: Router, b: BodyState, onBack: () -> Unit) {
    val c = Canone.colors
    val ctx = LocalContext.current
    val g0 = b.goals
    fun t(x: Int) = if (x > 0) x.toString() else ""
    var kcal by remember { mutableStateOf(t(g0.kcal)) }
    var p by remember { mutableStateOf(t(g0.p)) }
    var cb by remember { mutableStateOf(t(g0.c)) }
    var fat by remember { mutableStateOf(t(g0.f)) }
    var fiber by remember { mutableStateOf(t(g0.fiber)) }
    var goal by remember { mutableStateOf(if (g0.goalKg10 > 0) kg(g0.goalKg10 / 10f).replace('.', ',') else "") }
    var height by remember { mutableStateOf(t(g0.height)) }
    var age by remember { mutableStateOf(t(g0.age)) }
    var sex by remember { mutableIntStateOf(g0.sex) }
    var act by remember { mutableIntStateOf(g0.act) }
    var obj by remember { mutableIntStateOf(1) }
    var calc by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val fromMacro = ((parseF(p) ?: 0f) * 4 + (parseF(cb) ?: 0f) * 4 + (parseF(fat) ?: 0f) * 9).roundToInt()
    fun calculate() {
        val w = b.weights.lastOrNull()?.kg ?: 0f
        val h = parseF(height) ?: 0f; val a = parseF(age) ?: 0f
        if (w <= 0f) { toast(ctx, "Registra prima il peso"); return }
        if (h <= 0f || a <= 0f) { toast(ctx, "Servono altezza ed età"); return }
        val bmr = 10f * w + 6.25f * h - 5f * a + (if (sex == 0) 5f else -161f)
        val tdee = bmr * listOf(1.2f, 1.375f, 1.55f, 1.725f, 1.9f)[act]
        var target = tdee * listOf(0.85f, 1f, 1.10f)[obj]
        if (obj == 0) target = maxOf(target, bmr)
        val prot = (if (obj == 0) 2.0f else 1.8f) * w
        val fats = target * 0.25f / 9f
        val carbs = ((target - prot * 4f - fats * 9f) / 4f).coerceAtLeast(0f)
        kcal = target.roundToInt().toString(); p = prot.roundToInt().toString(); fat = fats.roundToInt().toString(); cb = carbs.roundToInt().toString()
        fiber = (14f * target / 1000f).roundToInt().toString()
        calc = bmr.roundToInt() to tdee.roundToInt()
    }
    ScreenList("Obiettivi") {
        item { BackRow("Corpo", "Obiettivi", onBack) }
        item {
            GlassTile(Modifier.fillMaxWidth(), Tint.blue, 0) {
                Column(Modifier.padding(Space.s5), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                    Text("Ogni giorno", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.labelSecondary)
                    CField("Calorie (kcal)", kcal, { kcal = it }, "2200", number = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                        CField("Proteine (g)", p, { p = it }, "0", Modifier.weight(1f), number = true)
                        CField("Carboidrati (g)", cb, { cb = it }, "0", Modifier.weight(1f), number = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                        CField("Grassi (g)", fat, { fat = it }, "0", Modifier.weight(1f), number = true)
                        CField("Fibre (g)", fiber, { fiber = it }, "0", Modifier.weight(1f), number = true)
                    }
                    Stat("Dai macro", "$fromMacro kcal", tint = if (kotlin.math.abs(fromMacro - (parseF(kcal) ?: 0f)) > 100f) Tint.third else c.labelPrimary)
                }
            }
        }
        item {
            GlassTile(Modifier.fillMaxWidth(), Tint.blue, 1) {
                Column(Modifier.padding(Space.s5), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                    Text("Calcola", style = CanoneType.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.labelSecondary)
                    GlassSegmented(listOf("Uomo", "Donna"), sex, { sex = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                        CField("Età", age, { age = it }, "30", Modifier.weight(1f), number = true)
                        CField("Altezza (cm)", height, { height = it }, "175", Modifier.weight(1f), number = true)
                    }
                    ChipRow { listOf("Sedentario", "Leggero", "Moderato", "Intenso", "Atleta").forEachIndexed { i, s -> Chip(s, act == i) { act = i } } }
                    GlassSegmented(listOf("Dimagrire", "Mantenere", "Massa"), obj, { obj = it })
                    CButton("Calcola", { calculate() }, Modifier.fillMaxWidth())
                    calc?.let { (bmr, tdee) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                            Stat("Metabolismo basale", "$bmr kcal", Modifier.weight(1f))
                            Stat("Fabbisogno", "$tdee kcal", Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        item {
            GlassTile(Modifier.fillMaxWidth(), Tint.blue, 2) {
                Column(Modifier.padding(Space.s5), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                    CField("Peso obiettivo (kg)", goal, { goal = it }, "75", number = true)
                }
            }
        }
        item {
            CButton("Salva", {
                r.body.saveGoals(Goals(
                    (parseF(kcal) ?: 2200f).roundToInt(), (parseF(p) ?: 0f).roundToInt(), (parseF(cb) ?: 0f).roundToInt(), (parseF(fat) ?: 0f).roundToInt(), (parseF(fiber) ?: 0f).roundToInt(),
                    ((parseF(goal) ?: 0f) * 10).roundToInt(), (parseF(height) ?: 0f).roundToInt(), sex, (parseF(age) ?: 0f).roundToInt(), act,
                ))
                onBack()
            }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
        }
    }
}
