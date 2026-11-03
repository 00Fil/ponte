package it.ponte

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Gemini dentro Relay: capisce frasi libere e agisce su To-do e Agenda con le funzioni qui sotto. */
object Gemini {
    private val http = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()
    private const val MODEL = "gemini-2.5-flash"

    private fun fn(name: String, desc: String, props: JSONObject, req: List<String>) = JSONObject()
        .put("name", name).put("description", desc)
        .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req)))
    private fun str(d: String) = JSONObject().put("type", "string").put("description", d)

    private val tools = JSONArray().put(JSONObject().put("functionDeclarations", JSONArray()
        .put(fn("add_todo", "Aggiunge un elemento alla To-do.", JSONObject().put("title", str("Testo")).put("due", str("Scadenza YYYY-MM-DD, opzionale")), listOf("title")))
        .put(fn("complete_todo", "Segna come fatto un elemento della To-do.", JSONObject().put("title", str("Testo o parte del testo")), listOf("title")))
        .put(fn("remove_todo", "Elimina un elemento della To-do.", JSONObject().put("title", str("Testo o parte del testo")), listOf("title")))
        .put(fn("add_event", "Aggiunge un evento all'agenda.", JSONObject().put("title", str("Titolo")).put("start", str("Inizio YYYY-MM-DDTHH:MM, o YYYY-MM-DD se tutto il giorno"))
            .put("end", str("Fine YYYY-MM-DDTHH:MM, opzionale")).put("all_day", JSONObject().put("type", "boolean")), listOf("title", "start")))
        .put(fn("remove_event", "Elimina un evento dell'agenda.", JSONObject().put("title", str("Titolo o parte")).put("date", str("Giorno YYYY-MM-DD, opzionale")), listOf("title")))
        .put(fn("list_agenda", "Elenca eventi e To-do tra due giorni.", JSONObject().put("from", str("YYYY-MM-DD")).put("to", str("YYYY-MM-DD")), listOf("from", "to")))
    ))

    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val dt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
    private fun parse(s: String): Pair<Long, Boolean>? = try { dt.parse(s)!!.time to false } catch (_: Exception) { try { day.parse(s)!!.time to true } catch (_: Exception) { null } }

    private fun run(r: Router, name: String, a: JSONObject): JSONObject {
        val p = r.plan.state.value
        fun match(list: List<Task>) = a.optString("title").lowercase().let { q -> list.firstOrNull { it.title.lowercase() == q } ?: list.firstOrNull { it.title.lowercase().contains(q) || q.contains(it.title.lowercase()) } }
        return when (name) {
            "add_todo" -> {
                val due = a.optString("due").takeIf { it.isNotBlank() }?.let { parse(it)?.first } ?: 0L
                r.plan.saveTask(Task("", a.getString("title"), "", due, false)); JSONObject().put("ok", true)
            }
            "complete_todo" -> match(p.tasks.filter { !it.done })?.let { r.plan.saveTask(it.copy(done = true)); JSONObject().put("ok", true).put("title", it.title) } ?: JSONObject().put("ok", false)
            "remove_todo" -> match(p.tasks)?.let { r.plan.removeTask(it.id); JSONObject().put("ok", true).put("title", it.title) } ?: JSONObject().put("ok", false)
            "add_event" -> {
                val (st, all) = parse(a.getString("start")) ?: return JSONObject().put("ok", false)
                val allDay = a.optBoolean("all_day", all)
                val end = a.optString("end").takeIf { it.isNotBlank() }?.let { parse(it)?.first } ?: (st + if (allDay) 86_400_000L else 3_600_000L)
                r.plan.saveEvent(Ev("", st, end, allDay, a.getString("title"), 0, "", "", -1)); JSONObject().put("ok", true)
            }
            "remove_event" -> {
                val q = a.optString("title").lowercase()
                val d = a.optString("date").takeIf { it.isNotBlank() }?.let { parse(it)?.first }
                p.events.firstOrNull { it.title.lowercase().contains(q) && (d == null || it.start in d until d + 86_400_000L) }
                    ?.let { r.plan.removeEvent(it.id); JSONObject().put("ok", true).put("title", it.title) } ?: JSONObject().put("ok", false)
            }
            "list_agenda" -> {
                val f = parse(a.optString("from"))?.first ?: 0L
                val t = (parse(a.optString("to"))?.first ?: f) + 86_400_000L
                JSONObject()
                    .put("events", JSONArray(p.events.filter { it.start < t && it.end > f }.sortedBy { it.start }.map { "${dt.format(Date(it.start))} ${it.title}" }))
                    .put("todo", JSONArray(p.tasks.filter { !it.done }.map { it.title + if (it.due > 0) " (entro ${day.format(Date(it.due))})" else "" }))
            }
            else -> JSONObject().put("ok", false)
        }
    }

    /** Esegue la richiesta e restituisce una risposta breve da mostrare. */
    suspend fun ask(r: Router, text: String): String = withContext(Dispatchers.IO) {
        val key = r.prefs.geminiKey
        if (key.isBlank()) return@withContext "Manca la chiave Gemini"
        val now = Calendar.getInstance()
        val sys = "Sei l'assistente di Relay. Oggi è ${SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ITALY).format(now.time)} (fuso ${now.timeZone.id}). " +
            "Usa le funzioni per agire su To-do e agenda; più azioni se servono. Rispondi in italiano con una frase brevissima."
        val contents = JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))))
        repeat(4) {
            val body = JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
                .put("contents", contents).put("tools", tools)
            val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
                .header("x-goog-api-key", key).post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val res = try { http.newCall(req).execute().use { it.body?.string().orEmpty() to it.code } } catch (e: Exception) { return@withContext "Gemini non raggiungibile" }
            if (res.second !in 200..299) return@withContext "Gemini: errore ${res.second}"
            val cand = JSONObject(res.first).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content") ?: return@withContext "Nessuna risposta"
            val parts = cand.optJSONArray("parts") ?: JSONArray()
            val calls = (0 until parts.length()).mapNotNull { parts.getJSONObject(it).optJSONObject("functionCall") }
            if (calls.isEmpty()) return@withContext (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.trim().ifEmpty { "Fatto" }
            contents.put(cand)
            val out = JSONArray()
            calls.forEach { c ->
                val name = c.optString("name")
                val result = try { withContext(Dispatchers.Main) { run(r, name, c.optJSONObject("args") ?: JSONObject()) } } catch (e: Exception) { JSONObject().put("ok", false) }
                out.put(JSONObject().put("functionResponse", JSONObject().put("name", name).put("response", result)))
            }
            contents.put(JSONObject().put("role", "user").put("parts", out))
        }
        "Fatto"
    }
}
