package it.ponte

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** Un movimento. [cents] è negativo per le spese. [src]: manual, revolut. */
data class Tx(val id: Long, val ts: Long, val cents: Long, val cat: String, val note: String, val src: String)

data class Money(
    val txs: List<Tx> = emptyList(),
    /** Banconote in tasca: taglio in centesimi → quante. */
    val cash: Map<Int, Int> = emptyMap(),
    /** Saldo di Revolut alla data [revTs]; poi si sommano i movimenti Revolut più recenti. */
    val revBase: Long = 0, val revTs: Long = 0,
) {
    val cashCents: Long get() = cash.entries.sumOf { it.key.toLong() * it.value }
    val revolutCents: Long get() = revBase + txs.filter { it.src == "revolut" && it.ts > revTs }.sumOf { it.cents }
}

val CATEGORIES = listOf("Cibo", "Casa", "Trasporti", "Svago", "Salute", "Shopping", "Abbonamenti", "Studio", "Altro", "Entrate")
val NOTES_EUR = listOf(500, 1000, 2000, 5000, 10000, 20000, 50000)

/** Finanze: contanti contati a banconote, movimenti, Revolut (notifiche ed estratto CSV). Tutto resta su questo telefono, in un file JSON. */
class Finance(private val ctx: Context) {
    private val file = File(ctx.filesDir, "finance.json")
    val state = MutableStateFlow(load())

    private fun load(): Money = try {
        val o = JSONObject(file.readText())
        val t = o.optJSONArray("txs") ?: JSONArray()
        val c = o.optJSONObject("cash") ?: JSONObject()
        Money(
            (0 until t.length()).map { t.getJSONObject(it).let { j -> Tx(j.getLong("id"), j.getLong("ts"), j.getLong("c"), j.getString("cat"), j.optString("n"), j.optString("s", "manual")) } },
            c.keys().asSequence().associate { it.toInt() to c.getInt(it) },
            o.optLong("revBase"), o.optLong("revTs"),
        )
    } catch (_: Exception) { Money() }

    @Synchronized
    private fun update(f: (Money) -> Money) {
        val m = f(state.value)
        state.value = m
        val o = JSONObject()
        o.put("txs", JSONArray().also { a -> m.txs.forEach { a.put(JSONObject().put("id", it.id).put("ts", it.ts).put("c", it.cents).put("cat", it.cat).put("n", it.note).put("s", it.src)) } })
        o.put("cash", JSONObject().also { j -> m.cash.forEach { (k, v) -> if (v > 0) j.put(k.toString(), v) } })
        o.put("revBase", m.revBase).put("revTs", m.revTs)
        try { file.writeText(o.toString()) } catch (_: Exception) {}
    }

    fun add(cents: Long, cat: String, note: String, src: String = "manual", ts: Long = System.currentTimeMillis()) = update { m ->
        // Stesso importo, stessa descrizione, entro due minuti: è lo stesso movimento (notifica doppia).
        if (m.txs.any { it.src == src && it.cents == cents && it.note == note && kotlin.math.abs(it.ts - ts) < 120_000 }) m
        else m.copy(txs = (m.txs + Tx(ts * 1000 + (m.txs.size % 1000), ts, cents, cat, note, src)).sortedByDescending { it.ts })
    }
    fun remove(id: Long) = update { it.copy(txs = it.txs.filter { t -> t.id != id }) }
    fun bump(denom: Int, by: Int) = update { m -> m.copy(cash = m.cash + (denom to ((m.cash[denom] ?: 0) + by).coerceAtLeast(0))) }
    fun clearCash() = update { it.copy(cash = emptyMap()) }
    fun setRevolut(cents: Long) = update { it.copy(revBase = cents, revTs = System.currentTimeMillis()) }

    // ---------------------------------------------------------------- Revolut
    /** Notifica di un pagamento Revolut: "Hai pagato 12,50 € a Esselunga" → spesa. Nessuna API: Revolut non ne ha una per i conti personali. */
    fun fromNotification(title: String, text: String) {
        val all = "$title $text"
        val amount = parseAmount(all) ?: return
        val low = all.lowercase(Locale.ITALY)
        val income = listOf("ricevut", "accredit", "rimbors", "received", "refund", "incoming", "hai ricevuto", "top-up", "ricarica").any { it in low }
        val spent = listOf("pagat", "pagamento", "spes", "addebit", "prelev", "paid", "payment", "sent", "inviat", "bonifico").any { it in low }
        if (!income && !spent) return
        val note = merchant(title, text)
        add(if (income) amount else -amount, guess(note, income), note, "revolut")
    }

    private fun merchant(title: String, text: String): String {
        val m = Regex("""\b(?:a|presso|at|to|da|from)\s+([^.,\n]{2,40})""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
        return (m ?: title.ifBlank { text }).take(60)
    }

    /** Importo in centesimi dal primo "12,50 €" / "€12.50" trovato. */
    private fun parseAmount(s: String): Long? {
        val m = Regex("""(?:€|EUR)\s?(\d[\d.,]*)|(\d[\d.,]*)\s?(?:€|EUR)""").find(s) ?: return null
        return toCents(m.groupValues[1].ifEmpty { m.groupValues[2] })
    }

    fun toCents(raw: String): Long? {
        var t = raw.trim().trimEnd('.', ',')
        if (t.isEmpty()) return null
        val lc = t.lastIndexOf(','); val ld = t.lastIndexOf('.')
        t = when {
            lc >= 0 && ld >= 0 -> if (lc > ld) t.replace(".", "").replace(',', '.') else t.replace(",", "")
            lc >= 0 -> t.replace(',', '.')
            ld >= 0 && t.length - ld - 1 == 3 && t.count { it == '.' } >= 1 && t.indexOf('.') == ld && t.length > 4 -> t.replace(".", "")
            else -> t
        }
        return t.toBigDecimalOrNull()?.movePointRight(2)?.toLong()
    }

    fun guess(note: String, income: Boolean = false): String {
        if (income) return "Entrate"
        val n = note.lowercase(Locale.ITALY)
        fun any(vararg k: String) = k.any { it in n }
        return when {
            any("esselunga", "coop", "conad", "carrefour", "lidl", "eurospin", "bar ", "ristor", "pizz", "mcdonald", "burger", "panific", "supermerc", "just eat", "glovo", "deliveroo", "caff") -> "Cibo"
            any("affitto", "bolletta", "enel", "eni ", "a2a", "ikea", "luce", "gas ", "condomin") -> "Casa"
            any("trenitalia", "italo", "atm", "uber", "taxi", "bolt", "benzin", "q8", "shell", "autostrad", "flixbus", "ryanair", "easyjet", "bus") -> "Trasporti"
            any("netflix", "spotify", "disney", "prime", "youtube", "icloud", "google one", "apple.com", "chatgpt", "openai", "anthropic") -> "Abbonamenti"
            any("farmaci", "pharm", "ospedale", "medic", "dentist", "clinic") -> "Salute"
            any("cinema", "steam", "playstation", "xbox", "nintendo", "concert", "ticket", "bowling", "discoteca") -> "Svago"
            any("amazon", "zalando", "h&m", "zara", "decathlon", "mediaworld", "unieuro") -> "Shopping"
            any("univers", "unibs", "libri", "libreria", "tasse", "cancelleria") -> "Studio"
            else -> "Altro"
        }
    }

    /** Estratto conto Revolut (CSV: Tipo, Prodotto, Data inizio, Data completamento, Descrizione, Importo, Costo, Valuta, Stato, Saldo). Ritorna quanti movimenti nuovi. */
    fun importCsv(text: String): Int {
        val rows = text.lineSequence().drop(1).filter { it.isNotBlank() }.map { splitCsv(it) }.filter { it.size >= 9 }.toList()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        var added = 0
        var lastTs = 0L; var lastBal: Long? = null
        val before = state.value.txs.size
        for (c in rows) {
            if (!c[8].uppercase().startsWith("COMPLET")) continue
            val ts = try { fmt.parse(c[3].ifBlank { c[2] })?.time } catch (_: Exception) { null } ?: continue
            val amt = toCents(c[5].removePrefix("-")) ?: continue
            val neg = c[5].trim().startsWith("-")
            val fee = toCents(c.getOrElse(6) { "0" }.removePrefix("-")) ?: 0
            val cents = (if (neg) -amt else amt) - fee
            val note = c[4].take(60)
            add(cents, guess(note, cents > 0), note, "revolut", ts)
            if (ts >= lastTs) { lastTs = ts; lastBal = c.getOrNull(9)?.let { toCents(it.removePrefix("-"))?.let { v -> if (it.trim().startsWith("-")) -v else v } } }
        }
        added = state.value.txs.size - before
        if (lastBal != null) update { it.copy(revBase = lastBal!!, revTs = lastTs) }
        return added
    }

    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false
        for (ch in line) when {
            ch == '"' -> q = !q
            ch == ',' && !q -> { out += sb.toString(); sb.clear() }
            else -> sb.append(ch)
        }
        out += sb.toString()
        return out
    }
}
