package it.ponte

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class FinanceEntry(
    val id: String,
    val ts: Long,
    /** Valore con segno in centesimi. */
    val cents: Long,
    val kind: String,
    val category: String,
    val note: String,
)

/** Dati finanziari privati di questo dispositivo. */
class FinanceStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("ponte_finance", Context.MODE_PRIVATE)
    private val lock = Any()
    private val mutableEntries = MutableStateFlow(read())
    val entries: StateFlow<List<FinanceEntry>> = mutableEntries

    val balanceCents: Long get() = mutableEntries.value.sumOf { it.cents }

    fun setBalance(cents: Long) = synchronized(lock) {
        val current = mutableEntries.value
        val delta = cents - current.sumOf { it.cents }
        if (delta == 0L && current.isNotEmpty()) return
        append(FinanceEntry(UUID.randomUUID().toString(), System.currentTimeMillis(), delta,
            if (current.isEmpty()) "opening" else "adjustment", "Saldo", "Saldo aggiornato"))
    }

    fun add(kind: String, cents: Long, category: String, note: String) = synchronized(lock) {
        require(kind == "income" || kind == "expense")
        require(cents > 0)
        append(FinanceEntry(UUID.randomUUID().toString(), System.currentTimeMillis(),
            if (kind == "income") cents else -cents, kind, category, note.trim()))
    }

    fun delete(id: String) = synchronized(lock) {
        mutableEntries.value = mutableEntries.value.filterNot { it.id == id }
        save(mutableEntries.value)
    }

    private fun append(entry: FinanceEntry) {
        mutableEntries.value = mutableEntries.value + entry
        save(mutableEntries.value)
    }

    private fun read(): List<FinanceEntry> = try {
        val a = JSONArray(prefs.getString("entries", "[]"))
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            FinanceEntry(o.optString("id"), o.optLong("ts"), o.optLong("cents"),
                o.optString("kind"), o.optString("category", "Altro"), o.optString("note"))
        }
    } catch (_: Exception) { emptyList() }

    private fun save(entries: List<FinanceEntry>) {
        val a = JSONArray()
        entries.forEach { e -> a.put(JSONObject().put("id", e.id).put("ts", e.ts).put("cents", e.cents)
            .put("kind", e.kind).put("category", e.category).put("note", e.note)) }
        prefs.edit().putString("entries", a.toString()).apply()
    }
}
