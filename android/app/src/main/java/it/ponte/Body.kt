package it.ponte

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Valori nutrizionali: kcal, proteine, carboidrati (di cui zuccheri), grassi, fibre e sale, in grammi. Per 100 g negli alimenti, per la quantità nel diario. */
data class Nut(val kcal: Float = 0f, val p: Float = 0f, val c: Float = 0f, val sugar: Float = 0f, val f: Float = 0f, val fiber: Float = 0f, val salt: Float = 0f) {
    operator fun plus(o: Nut) = Nut(kcal + o.kcal, p + o.p, c + o.c, sugar + o.sugar, f + o.f, fiber + o.fiber, salt + o.salt)
    operator fun times(k: Float) = Nut(kcal * k, p * k, c * k, sugar * k, f * k, fiber * k, salt * k)
    fun toJson(): JSONArray = JSONArray().put(kcal.toDouble()).put(p.toDouble()).put(c.toDouble()).put(sugar.toDouble()).put(f.toDouble()).put(fiber.toDouble()).put(salt.toDouble())

    companion object {
        fun of(a: JSONArray?): Nut = if (a == null) Nut() else Nut(
            a.optDouble(0, 0.0).toFloat(), a.optDouble(1, 0.0).toFloat(), a.optDouble(2, 0.0).toFloat(), a.optDouble(3, 0.0).toFloat(),
            a.optDouble(4, 0.0).toFloat(), a.optDouble(5, 0.0).toFloat(), a.optDouble(6, 0.0).toFloat(),
        )
    }
}

/** Un alimento salvato: i valori sono per 100 g, [serving] è la porzione abituale in grammi. */
data class Food(val id: String, val name: String, val brand: String, val serving: Int, val per100: Nut)
/** Una voce del diario: [meal] 0 colazione, 1 pranzo, 2 cena, 3 spuntino. I valori sono già per la quantità mangiata. */
data class Entry(val id: String, val day: String, val meal: Int, val ts: Long, val name: String, val grams: Float, val n: Nut, val foodId: String)
data class PlanItem(val foodId: String, val name: String, val grams: Float, val n: Nut)
/** Un pasto del piano settimanale: [dow] 0 lunedì … 6 domenica, [minute] orario in minuti dalla mezzanotte. */
data class Slot(val id: String, val dow: Int, val minute: Int, val meal: Int, val label: String, val items: List<PlanItem>) {
    val total: Nut get() = items.fold(Nut()) { a, i -> a + i.n }
}
data class Weigh(val day: String, val kg10: Int) { val kg: Float get() = kg10 / 10f }
/** Obiettivi giornalieri e dati per calcolarli. [sex] 0 uomo, 1 donna. [act] livello di attività 0..4. */
data class Goals(
    val kcal: Int = 2200, val p: Int = 140, val c: Int = 250, val f: Int = 70, val fiber: Int = 30,
    val goalKg10: Int = 0, val height: Int = 0, val sex: Int = 0, val age: Int = 0, val act: Int = 1,
)

data class BodyState(
    val foods: List<Food> = emptyList(), val entries: List<Entry> = emptyList(), val slots: List<Slot> = emptyList(),
    val weights: List<Weigh> = emptyList(), val goals: Goals = Goals(),
)

val MEALS = listOf("Colazione", "Pranzo", "Cena", "Spuntino")
private val KEY = SimpleDateFormat("yyyyMMdd", Locale.ROOT)
fun dayKey(t: Long = System.currentTimeMillis()): String = KEY.format(Date(t))
fun keyTime(k: String): Long = try { KEY.parse(k)!!.time } catch (_: Exception) { 0L }

/**
 * Corpo: alimenti e valori nutrizionali, diario delle calorie, piano settimanale, pesate e foto fisiche.
 * Stesso modello di Agenda e Finanze: gruppi di registri "l'ultimo che scrive vince" sincronizzati dal server (/api/body) e tra i dispositivi.
 * Le foto restano sul telefono che le scatta, nella cartella body/.
 */
class Body(private val ctx: Context, private val r: Router) {
    private val file = File(ctx.filesDir, "body.json")
    private val dir = File(ctx.filesDir, "body").also { it.mkdirs() }
    private val groups = listOf("fd", "lg", "sl", "wt", "cfg")
    private val doc = HashMap<String, HashMap<String, JSONObject>>().also { m -> groups.forEach { m[it] = HashMap() } }
    val state = MutableStateFlow(BodyState())
    /** Giorni con la foto fisica, dal più vecchio al più recente. */
    val photos = MutableStateFlow<List<String>>(emptyList())
    private val http = okhttp3.OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
    private var syncJob: kotlinx.coroutines.Job? = null

    init {
        OpenFood.assets = ctx.assets
        try { merge(JSONObject(file.readText())) } catch (_: Exception) {}
        publish(); scanPhotos()
    }

    @Synchronized
    private fun merge(o: JSONObject): Boolean {
        var changed = false
        for (g in groups) {
            val j = o.optJSONObject(g) ?: continue
            val m = doc[g]!!
            j.keys().forEach { id ->
                val v = j.optJSONObject(id) ?: return@forEach
                if ((m[id]?.optLong("u") ?: -1L) < v.optLong("u")) { m[id] = v; changed = true }
            }
        }
        return changed
    }

    private fun live(g: String) = doc[g]!!.entries.filter { it.value.optInt("d") != 1 }

    private fun publish() {
        val cfg = doc["cfg"]!!["g"]?.takeIf { it.optInt("d") != 1 }
        state.value = BodyState(
            live("fd").map { (id, v) -> Food(id, v.optString("n"), v.optString("b"), v.optInt("s", 100), Nut.of(v.optJSONArray("v"))) }.sortedBy { it.name.lowercase() },
            live("lg").map { (id, v) -> Entry(id, v.optString("d"), v.optInt("m"), v.optLong("t"), v.optString("n"), v.optDouble("g", 0.0).toFloat(), Nut.of(v.optJSONArray("v")), v.optString("f")) }.sortedBy { it.ts },
            live("sl").map { (id, v) ->
                val it = v.optJSONArray("it") ?: JSONArray()
                Slot(id, v.optInt("w"), v.optInt("h"), v.optInt("m"), v.optString("l"), (0 until it.length()).map { i ->
                    val x = it.getJSONObject(i); PlanItem(x.optString("f"), x.optString("n"), x.optDouble("g", 0.0).toFloat(), Nut.of(x.optJSONArray("v")))
                })
            }.sortedWith(compareBy({ it.dow }, { it.minute })),
            live("wt").map { (id, v) -> Weigh(id, v.optInt("k")) }.filter { it.kg10 > 0 }.sortedBy { it.day },
            if (cfg == null) Goals() else Goals(
                cfg.optInt("k", 2200), cfg.optInt("p", 140), cfg.optInt("c", 250), cfg.optInt("f", 70), cfg.optInt("fi", 30),
                cfg.optInt("gw"), cfg.optInt("h"), cfg.optInt("sx"), cfg.optInt("a"), cfg.optInt("ac", 1),
            ),
        )
    }

    private fun toJson() = JSONObject().also { o -> for (g in groups) o.put(g, JSONObject().also { j -> doc[g]!!.forEach { (id, v) -> j.put(id, v) } }) }
    @Synchronized private fun save() { try { file.writeText(toJson().toString()) } catch (_: Exception) {} }

    @Synchronized
    private fun mutate(f: () -> Unit) {
        f(); publish(); save()
        syncJob?.cancel()
        syncJob = r.scope.launch { kotlinx.coroutines.delay(800); push() }
    }

    private fun put(g: String, id: String, f: JSONObject.() -> Unit) { doc[g]!![id] = JSONObject().apply(f).put("u", System.currentTimeMillis()) }
    private fun del(g: String, id: String) { doc[g]!![id]?.let { doc[g]!![id] = JSONObject(it.toString()).put("d", 1).put("u", System.currentTimeMillis()) } }
    private fun id() = UUID.randomUUID().toString().take(12)

    // ---------------------------------------------------------------- sincronizzazione
    suspend fun syncServer(): Boolean {
        val base = r.prefs.httpBase()?.toHttpUrlOrNull()?.newBuilder()?.addPathSegments("api/body")?.build() ?: return false
        return try {
            val body = toJson().toString().toRequestBody("application/json".toMediaType())
            http.newCall(Request.Builder().url(base).header("Authorization", "Bearer ${r.prefs.token}").post(body).build()).execute().use { resp ->
                if (!resp.isSuccessful) return false
                if (merge(JSONObject(resp.body!!.string()))) { publish(); save() }
                true
            }
        } catch (_: Exception) { false }
    }

    private fun broadcast() { val d = toJson(); if (d.toString().length < 400_000) r.send(newMsg("body", "doc" to d)) }
    suspend fun push() { syncServer(); broadcast() }

    fun onRemote(d: JSONObject?) { if (d != null && merge(d)) { publish(); save() } }

    fun start() {
        r.scope.launch {
            var first = true
            while (true) { syncServer(); if (first) { first = false; loadPreset() }; kotlinx.coroutines.delay(10 * 60_000L) }
        }
    }

    /** Una sola volta, a piano vuoto: carica obiettivi, alimenti CREA e pasti della settimana su misura. */
    private fun loadPreset() {
        val prefs = ctx.getSharedPreferences("body_preset", Context.MODE_PRIVATE)
        if (prefs.getBoolean("v1", false)) return
        prefs.edit().putBoolean("v1", true).apply()
        if (state.value.slots.isNotEmpty()) return
        val foods = HashMap<String, Food>()
        fun fd(display: String, crea: String): String {
            val base = OpenFood.crea(crea) ?: return ""
            val id = "p" + crea.hashCode().toUInt().toString(16)
            foods[id] = base.copy(id = id, name = display, brand = "CREA")
            return id
        }
        fun pi(display: String, crea: String, g: Int): PlanItem {
            val id = fd(display, crea)
            val f = foods[id]
            return PlanItem(id, display, g.toFloat(), (f?.per100 ?: Nut()) * (g / 100f))
        }
        fun frutta(d: Int) = when (d % 3) { 0 -> pi("Mela", "Mele, fresche, golden", 150); 1 -> pi("Banana", "Banane, fresche", 120); else -> pi("Arancia", "Arance Tarocco, fresche", 150) }
        val slots = ArrayList<Slot>()
        for (d in 0..6) {
            val toast = d % 2 == 0
            val colaz = if (toast) listOf(pi("Pane bianco", "Pane bianco", 80), pi("Prosciutto cotto", "Prosciutto cotto, sgrassato", 50), pi("Grana Padano", "Grana Padano, DOP", 20))
            else listOf(pi("Fette biscottate", "Fette biscottate", 50), pi("Marmellata", "Marmellata", 30), frutta(d))
            slots += Slot("pl${d}a", d, 7 * 60, 0, if (toast) "Toast" else "Colazione dolce", colaz)
            slots += Slot("pl${d}b", d, 10 * 60 + 30, 3, "Spuntino", listOf(frutta(d + 1), pi("Grana Padano", "Grana Padano, DOP", 30)))
            val carb = if (d % 2 == 0) pi("Pasta", "Pasta di semola", 100) else pi("Riso", "Riso, brillato", 90)
            val prot = if (d >= 5) pi("Tacchino", "Tacchino, fesa, crudo", 200) else pi("Pollo", "Pollo, petto, crudo", 200)
            slots += Slot("pl${d}c", d, 13 * 60, 1, "Pranzo", listOf(carb, prot, pi("Olio d'oliva", "Olio di oliva extra vergine", 10), pi("Pomodori", "Pomodori, da insalata, freschi", 200)))
            slots += Slot("pl${d}d", d, 16 * 60, 3, "Merenda", listOf(pi("Pane bianco", "Pane bianco", 80), pi("Tacchino arrosto", "Tacchino, fesa, cotta, al forno", 60), frutta(d + 2)))
            val cena = if (d == 0 || d == 3 || d == 6) listOf(pi("Uova", "Uova di gallina, intero", 180), pi("Patate", "Patate, crude", 300), pi("Olio d'oliva", "Olio di oliva extra vergine", 10), pi("Zucchine", "Zucchine, crude", 200), pi("Pane bianco", "Pane bianco", 40))
            else listOf(pi("Pollo", "Pollo, petto, crudo", 200), pi("Patate", "Patate, crude", 300), pi("Olio d'oliva", "Olio di oliva extra vergine", 10), pi("Carote", "Carote, crude", 200), pi("Pane bianco", "Pane bianco", 40))
            slots += Slot("pl${d}e", d, 20 * 60 + 30, 2, if (d == 0 || d == 3 || d == 6) "Frittata" else "Cena", cena)
        }
        mutate {
            foods.values.forEach { f -> put("fd", f.id) { put("n", f.name); put("b", f.brand); put("s", f.serving); put("v", f.per100.toJson()) } }
            slots.forEach { s ->
                put("sl", s.id) {
                    put("w", s.dow); put("h", s.minute); put("m", s.meal); put("l", s.label)
                    put("it", JSONArray().also { a -> s.items.forEach { i -> a.put(JSONObject().put("f", i.foodId).put("n", i.name).put("g", i.grams.toDouble()).put("v", i.n.toJson())) } })
                }
            }
            if (state.value.goals.height == 0) put("cfg", "g") { put("k", 2350); put("p", 165); put("c", 260); put("f", 75); put("fi", 30); put("gw", 795); put("h", 186); put("sx", 0); put("a", 19); put("ac", 3) }
            val today = dayKey()
            if (state.value.weights.none { it.day == today }) put("wt", today) { put("k", 886) }
        }
    }

    // ---------------------------------------------------------------- alimenti
    fun saveFood(f: Food): String {
        val id = f.id.ifEmpty { id() }
        mutate { put("fd", id) { put("n", f.name.trim()); put("b", f.brand.trim()); put("s", f.serving); put("v", f.per100.toJson()) } }
        return id
    }
    fun removeFood(id: String) = mutate { del("fd", id) }

    // ---------------------------------------------------------------- diario
    fun addEntry(day: String, meal: Int, name: String, grams: Float, n: Nut, foodId: String = "") = mutate {
        put("lg", id()) { put("d", day); put("m", meal); put("t", System.currentTimeMillis()); put("n", name); put("g", grams.toDouble()); put("v", n.toJson()); put("f", foodId) }
    }
    fun removeEntry(id: String) = mutate { del("lg", id) }

    /** Cambia la quantità di una voce: con l'alimento ancora salvato ricalcola dai suoi valori, altrimenti in proporzione. */
    fun setGrams(e: Entry, grams: Float) {
        if (grams <= 0f) return
        val food = state.value.foods.firstOrNull { it.id == e.foodId }
        val n = if (food != null) food.per100 * (grams / 100f) else if (e.grams > 0f) e.n * (grams / e.grams) else e.n
        mutate { put("lg", e.id) { put("d", e.day); put("m", e.meal); put("t", e.ts); put("n", e.name); put("g", grams.toDouble()); put("v", n.toJson()); put("f", e.foodId) } }
    }

    // ---------------------------------------------------------------- piano settimanale
    fun saveSlot(s: Slot) {
        val id = s.id.ifEmpty { id() }
        mutate {
            put("sl", id) {
                put("w", s.dow); put("h", s.minute); put("m", s.meal); put("l", s.label)
                put("it", JSONArray().also { a -> s.items.forEach { i -> a.put(JSONObject().put("f", i.foodId).put("n", i.name).put("g", i.grams.toDouble()).put("v", i.n.toJson())) } })
            }
        }
    }
    fun removeSlot(id: String) = mutate { del("sl", id) }

    /** Copia i pasti di un giorno della settimana sugli altri indicati (quelli già presenti nel giorno di arrivo vengono sostituiti). */
    fun copyDay(from: Int, to: List<Int>) {
        val src = state.value.slots.filter { it.dow == from }
        mutate {
            for (d in to.filter { it != from }) {
                state.value.slots.filter { it.dow == d }.forEach { del("sl", it.id) }
                src.forEach { s ->
                    put("sl", id()) {
                        put("w", d); put("h", s.minute); put("m", s.meal); put("l", s.label)
                        put("it", JSONArray().also { a -> s.items.forEach { i -> a.put(JSONObject().put("f", i.foodId).put("n", i.name).put("g", i.grams.toDouble()).put("v", i.n.toJson())) } })
                    }
                }
            }
        }
    }

    /** Registra nel diario di [day] tutto ciò che il piano prevede per quel pasto. */
    fun logSlot(s: Slot, day: String) = mutate {
        s.items.forEach { i -> put("lg", id()) { put("d", day); put("m", s.meal); put("t", System.currentTimeMillis()); put("n", i.name); put("g", i.grams.toDouble()); put("v", i.n.toJson()); put("f", i.foodId) } }
    }

    // ---------------------------------------------------------------- peso e obiettivi
    fun setWeight(day: String, kg10: Int) = mutate { put("wt", day) { put("k", kg10) } }
    fun clearWeight(day: String) = mutate { del("wt", day) }

    fun saveGoals(g: Goals) = mutate {
        put("cfg", "g") { put("k", g.kcal); put("p", g.p); put("c", g.c); put("f", g.f); put("fi", g.fiber); put("gw", g.goalKg10); put("h", g.height); put("sx", g.sex); put("a", g.age); put("ac", g.act) }
    }

    // ---------------------------------------------------------------- foto
    fun photoFile(day: String) = File(dir, "ph_$day.jpg")
    fun thumbFile(day: String) = File(dir, "th_$day.jpg")

    private fun scanPhotos() {
        photos.value = (dir.listFiles() ?: emptyArray()).mapNotNull { f -> f.name.takeIf { it.startsWith("ph_") && it.endsWith(".jpg") }?.substring(3, 11) }.sorted()
    }

    /** Salva la foto del giorno (grande, 3:4) e la sua miniatura per il time lapse e la galleria. */
    fun savePhoto(day: String, bmp: Bitmap) {
        try {
            photoFile(day).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            val th = Bitmap.createScaledBitmap(bmp, 360, (360f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true)
            thumbFile(day).outputStream().use { th.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            if (th !== bmp) th.recycle()
        } catch (_: Exception) {}
        scanPhotos()
    }

    fun deletePhoto(day: String) { photoFile(day).delete(); thumbFile(day).delete(); scanPhotos() }
}
