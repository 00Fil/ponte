package it.ponte

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.UUID

data class Ev(val id: String, val start: Long, val end: Long, val allDay: Boolean, val title: String, val color: Int, val note: String, val place: String, val remind: Int)
data class Subject(val id: String, val name: String, val color: Int)
data class Task(val id: String, val title: String, val subject: String, val due: Long, val done: Boolean)
data class Exam(val id: String, val title: String, val subject: String, val date: Long)
data class Sess(val id: String, val subject: String, val start: Long, val minutes: Int)
data class Card(val id: String, val subject: String, val q: String, val a: String, val box: Int, val due: Long)

/** Pomodoro condiviso: [phase] focus | short | long, [running] se il conto alla rovescia corre fino a [endAt]. */
data class Timer(val phase: String = "focus", val running: Boolean = false, val startedAt: Long = 0, val endAt: Long = 0, val subject: String = "", val cycle: Int = 0, val device: String = "")

data class PlanState(
    val events: List<Ev> = emptyList(), val subjects: List<Subject> = emptyList(), val tasks: List<Task> = emptyList(),
    val exams: List<Exam> = emptyList(), val sessions: List<Sess> = emptyList(), val cards: List<Card> = emptyList(),
    val cfg: Map<String, Int> = emptyMap(), val timer: Timer = Timer(),
) {
    fun cfg(k: String, def: Int) = cfg[k] ?: def
}

/**
 * Agenda e Studio. Stesso modello delle Finanze: gruppi di registri "l'ultimo che scrive vince" (campo u, d = eliminato),
 * sincronizzati dal server (/api/plan) e, se non risponde, tra i dispositivi. Lo stesso formato è in server/src/plan.js.
 */
class Plan(private val ctx: Context, private val r: Router) {
    private val file = File(ctx.filesDir, "plan.json")
    private val groups = listOf("ev", "sub", "tk", "ex", "ses", "card", "cfg", "tm")
    private val doc = HashMap<String, HashMap<String, JSONObject>>().also { m -> groups.forEach { m[it] = HashMap() } }
    val state = MutableStateFlow(PlanState())
    private val http = okhttp3.OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
    private var syncJob: kotlinx.coroutines.Job? = null

    init {
        try { merge(JSONObject(file.readText())) } catch (_: Exception) {}
        publish()
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
        val cfg = HashMap<String, Int>().also { c -> live("cfg").forEach { (k, v) -> c[k] = v.optInt("v") } }
        val t = doc["tm"]!!["t"]
        state.value = PlanState(
            live("ev").map { (id, v) -> Ev(id, v.optLong("t"), v.optLong("e"), v.optInt("a") == 1, v.optString("ti"), v.optInt("c"), v.optString("n"), v.optString("l"), v.optInt("r", -1)) }.sortedBy { it.start },
            live("sub").map { (id, v) -> Subject(id, v.optString("ti"), v.optInt("c")) }.sortedBy { it.name.lowercase() },
            live("tk").map { (id, v) -> Task(id, v.optString("ti"), v.optString("s"), v.optLong("due"), v.optInt("f") == 1) },
            live("ex").map { (id, v) -> Exam(id, v.optString("ti"), v.optString("s"), v.optLong("t")) }.sortedBy { it.date },
            live("ses").map { (id, v) -> Sess(id, v.optString("s"), v.optLong("t"), v.optInt("m")) }.sortedByDescending { it.start },
            live("card").map { (id, v) -> Card(id, v.optString("s"), v.optString("q"), v.optString("a"), v.optInt("b"), v.optLong("due")) },
            cfg,
            if (t == null) Timer() else Timer(t.optString("p", "focus"), t.optInt("n") == 1, t.optLong("s"), t.optLong("e"), t.optString("sb"), t.optInt("c"), t.optString("dv")),
        )
    }

    private fun toJson() = JSONObject().also { o -> for (g in groups) o.put(g, JSONObject().also { j -> doc[g]!!.forEach { (id, v) -> j.put(id, v) } }) }

    @Synchronized private fun save() { try { file.writeText(toJson().toString()) } catch (_: Exception) {} }

    @Synchronized
    private fun mutate(f: () -> Unit) {
        f(); publish(); save(); reschedule()
        syncJob?.cancel()
        syncJob = r.scope.launch { kotlinx.coroutines.delay(800); push() }
    }

    private fun put(g: String, id: String, f: JSONObject.() -> Unit) { doc[g]!![id] = JSONObject().apply(f).put("u", System.currentTimeMillis()) }
    private fun del(g: String, id: String) { doc[g]!![id]?.let { doc[g]!![id] = JSONObject(it.toString()).put("d", 1).put("u", System.currentTimeMillis()) } }
    private fun id() = UUID.randomUUID().toString().take(12)

    // ---------------------------------------------------------------- sincronizzazione
    suspend fun syncServer(): Boolean {
        val base = r.prefs.httpBase()?.toHttpUrlOrNull()?.newBuilder()?.addPathSegments("api/plan")?.build() ?: return false
        return try {
            val body = toJson().toString().toRequestBody("application/json".toMediaType())
            http.newCall(Request.Builder().url(base).header("Authorization", "Bearer ${r.prefs.token}").post(body).build()).execute().use { resp ->
                if (!resp.isSuccessful) return false
                if (merge(JSONObject(resp.body!!.string()))) { publish(); save(); reschedule() }
                true
            }
        } catch (_: Exception) { false }
    }

    private fun broadcast() { val d = toJson(); if (d.toString().length < 400_000) r.send(newMsg("plan", "doc" to d)) }
    suspend fun push() { syncServer(); broadcast() }
    fun onRemote(d: JSONObject?) { if (d != null && merge(d)) { publish(); save(); reschedule() } }

    fun start() {
        r.scope.launch { while (true) { kotlinx.coroutines.delay(2 * 60_000L); if (r.screenOn) push() } }
        reschedule()
    }

    // ---------------------------------------------------------------- eventi
    fun saveEvent(e: Ev) = mutate { put("ev", e.id.ifEmpty { id() }) { put("t", e.start); put("e", e.end); put("a", if (e.allDay) 1 else 0); put("ti", e.title); put("c", e.color); put("n", e.note); put("l", e.place); put("r", e.remind) } }
    fun removeEvent(id: String) = mutate { del("ev", id) }

    // ---------------------------------------------------------------- studio
    fun saveSubject(s: Subject) = mutate { put("sub", s.id.ifEmpty { id() }) { put("ti", s.name); put("c", s.color) } }
    fun removeSubject(id: String) = mutate { del("sub", id) }
    fun saveTask(t: Task) = mutate { put("tk", t.id.ifEmpty { id() }) { put("ti", t.title); put("s", t.subject); put("due", t.due); put("f", if (t.done) 1 else 0) } }
    fun removeTask(id: String) = mutate { del("tk", id) }
    fun saveExam(e: Exam) = mutate { put("ex", e.id.ifEmpty { id() }) { put("ti", e.title); put("s", e.subject); put("t", e.date) } }
    fun removeExam(id: String) = mutate { del("ex", id) }
    fun saveCard(c: Card) = mutate { put("card", c.id.ifEmpty { id() }) { put("s", c.subject); put("q", c.q); put("a", c.a); put("b", c.box); put("due", c.due) } }
    fun removeCard(id: String) = mutate { del("card", id) }
    fun setCfg(k: String, v: Int) = mutate { put("cfg", k) { put("v", v) } }
    fun removeSession(id: String) = mutate { del("ses", id) }
    fun addSession(subject: String, start: Long, minutes: Int) = mutate { put("ses", "s$start") { put("s", subject); put("t", start); put("m", minutes) } }

    // ---------------------------------------------------------------- pomodoro
    private fun minutes(phase: String): Int = state.value.let { when (phase) { "short" -> it.cfg("short", 5); "long" -> it.cfg("long", 15); else -> it.cfg("focus", 25) } }

    private fun writeTimer(t: Timer) = put("tm", "t") { put("p", t.phase); put("n", if (t.running) 1 else 0); put("s", t.startedAt); put("e", t.endAt); put("sb", t.subject); put("c", t.cycle); put("dv", t.device) }

    fun timerStart(subject: String) = mutate {
        val t = state.value.timer; val now = System.currentTimeMillis()
        writeTimer(t.copy(running = true, startedAt = now, endAt = now + minutes(t.phase) * 60_000L, subject = subject, device = r.deviceId))
    }
    fun timerStop() = mutate { writeTimer(state.value.timer.copy(running = false, endAt = 0, startedAt = 0)) }
    fun timerPhase(p: String) = mutate { writeTimer(Timer(p, false, 0, 0, state.value.timer.subject, state.value.timer.cycle, r.deviceId)) }

    /** Il conto alla rovescia è finito: registro la sessione (id fisso dall'inizio, quindi senza doppioni tra dispositivi) e passo alla fase dopo. */
    fun timerDone() {
        val t = state.value.timer
        if (!t.running || t.endAt > System.currentTimeMillis() + 500) return
        mutate {
            val rounds = state.value.cfg("rounds", 4)
            if (t.phase == "focus") {
                put("ses", "s${t.startedAt}") { put("s", t.subject); put("t", t.startedAt); put("m", minutes("focus")) }
                val c = t.cycle + 1
                writeTimer(Timer(if (c % rounds == 0) "long" else "short", false, 0, 0, t.subject, c, r.deviceId))
            } else writeTimer(Timer("focus", false, 0, 0, t.subject, t.cycle, r.deviceId))
        }
        Alerts.notify(ctx, "studio", "Studio", if (t.phase == "focus") "Pomodoro finito: fai una pausa" else "Pausa finita: si riparte", 7001)
    }

    // ---------------------------------------------------------------- avvisi
    private val local = ctx.getSharedPreferences("plan_local", Context.MODE_PRIVATE)

    /** Programma gli avvisi: la fine del pomodoro e i promemoria dei prossimi eventi (e delle scadenze). */
    @Synchronized
    fun reschedule() {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        fun pi(code: Int, kind: String, title: String, text: String): PendingIntent = PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, AlertReceiver::class.java).putExtra("kind", kind).putExtra("title", title).putExtra("text", text).putExtra("code", code),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val old = local.getStringSet("alarms", emptySet()).orEmpty()
        old.forEach { code -> am.cancel(pi(code.toInt(), "", "", "")) }
        val codes = HashSet<String>()
        fun at(time: Long, code: Int, kind: String, title: String, text: String) {
            if (time <= now) return
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi(code, kind, title, text)); codes += code.toString()
        }
        val s = state.value
        if (s.timer.running) at(s.timer.endAt, 7001, "timer", "Studio", if (s.timer.phase == "focus") "Pomodoro finito: fai una pausa" else "Pausa finita: si riparte")
        val df = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
        s.events.filter { it.remind >= 0 && it.start > now && it.start < now + 14 * 86_400_000L }.take(40).forEach { e ->
            at(e.start - e.remind * 60_000L, 9000 + (e.id.hashCode() and 0x3fff), "ev", e.title, if (e.allDay) "Oggi, tutto il giorno" else "Alle ${df.format(java.util.Date(e.start))}${if (e.place.isNotBlank()) " · " + e.place else ""}")
        }
        s.exams.filter { it.date > now && it.date < now + 14 * 86_400_000L }.take(10).forEach { e ->
            val morning = Calendar.getInstance().apply { timeInMillis = e.date; add(Calendar.DAY_OF_YEAR, -1); set(Calendar.HOUR_OF_DAY, 18); set(Calendar.MINUTE, 0) }.timeInMillis
            at(morning, 30000 + (e.id.hashCode() and 0x3fff), "ex", "Esame domani: ${e.title}", "Ripassa con un pomodoro")
        }
        local.edit().putStringSet("alarms", codes).apply()
    }
}
