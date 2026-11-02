package it.ponte

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** Un movimento. [cents] è negativo per le spese. [src]: manual, cash, revolut. */
data class Tx(val id: String, val ts: Long, val cents: Long, val cat: String, val note: String, val src: String)

data class Money(
    val txs: List<Tx> = emptyList(),
    /** Banconote in tasca: taglio in centesimi → quante. */
    val cash: Map<Int, Int> = emptyMap(),
    /** Saldo di Revolut alla data [revTs]; poi si sommano i movimenti Revolut più recenti. */
    val revBase: Long = 0, val revTs: Long = 0,
) {
    /** Un solo patrimonio: la fonte (contanti, Revolut, altro) è solo un colore sul movimento. */
    val totalCents: Long get() = revBase + txs.sumOf { if (it.src == "revolut" && it.ts <= revTs) 0L else it.cents }
}

val CATEGORIES = listOf("Cibo", "Casa", "Trasporti", "Svago", "Salute", "Shopping", "Abbonamenti", "Studio", "Altro", "Entrate")
val NOTES_EUR = listOf(500, 1000, 2000, 5000, 10000, 20000, 50000)

private class Rec(val t: Long, val c: Long, val k: String, val n: String, val s: String, val u: Long, val d: Boolean)

/**
 * Finanze: contanti contati a banconote, movimenti, Revolut (notifiche ed estratto CSV).
 * Ogni voce è un registro "l'ultimo che scrive vince" con la sua data di modifica: i dispositivi si scambiano il documento
 * dal server (principale) e, se il server non c'è, direttamente tra loro su rete locale, Bluetooth e Wi-Fi Direct.
 * Lo stesso formato è usato dal server (server/src/finance.js).
 */
class Finance(private val ctx: Context, private val r: Router) {
    private val file = File(ctx.filesDir, "finance.json")
    private val tx = HashMap<String, Rec>()
    private val cash = HashMap<Int, Pair<Int, Long>>()
    private var rev = Triple(0L, 0L, 0L)
    val state = MutableStateFlow(Money())
    private val http = okhttp3.OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
    private var syncJob: kotlinx.coroutines.Job? = null

    init {
        try {
            val o = JSONObject(file.readText())
            if (o.has("tx")) fromJson(o) else migrate(o)
        } catch (_: Exception) {}
        // Le banconote contate non sono più un conto a parte: ciò che c'era in tasca diventa un movimento "contanti" (una volta sola, uguale su ogni dispositivo).
        val inv = cash.entries.sumOf { it.key.toLong() * it.value.first }
        if (inv != 0L || cash.isNotEmpty()) {
            val past = tx.values.filter { !it.d && it.s == "cash" }.sumOf { it.c }
            val u = System.currentTimeMillis()
            if (inv - past != 0L && !tx.containsKey("cash-init")) tx["cash-init"] = Rec(u, inv - past, "Altro", "Contanti iniziali", "cash", u, false)
            cash.keys.toList().forEach { cash[it] = 0 to u }
            save()
        }
        publish()
    }

    /** Formato del primo rilascio (elenco di movimenti e banconote senza date): lo porto al nuovo. */
    private fun migrate(o: JSONObject) {
        val now = System.currentTimeMillis()
        val t = o.optJSONArray("txs") ?: JSONArray()
        for (i in 0 until t.length()) t.getJSONObject(i).let { j ->
            tx[j.getLong("id").toString()] = Rec(j.getLong("ts"), j.getLong("c"), j.getString("cat"), j.optString("n"), j.optString("s", "manual"), now, false)
        }
        o.optJSONObject("cash")?.let { c -> c.keys().forEach { k -> cash[k.toInt()] = c.getInt(k) to now } }
        rev = Triple(o.optLong("revBase"), o.optLong("revTs"), now)
    }

    private fun publish() {
        state.value = Money(
            tx.entries.filter { !it.value.d }.map { (id, v) -> Tx(id, v.t, v.c, v.k, v.n, v.s) }.sortedByDescending { it.ts },
            cash.filter { it.value.first > 0 }.mapValues { it.value.first },
            rev.first, rev.second,
        )
    }

    private fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("tx", JSONObject().also { j -> tx.forEach { (id, v) -> j.put(id, JSONObject().put("t", v.t).put("c", v.c).put("k", v.k).put("n", v.n).put("s", v.s).put("u", v.u).put("d", if (v.d) 1 else 0)) } })
        o.put("cash", JSONObject().also { j -> cash.forEach { (k, v) -> j.put(k.toString(), JSONArray().put(v.first).put(v.second)) } })
        o.put("rev", JSONArray().put(rev.first).put(rev.second).put(rev.third))
        return o
    }

    /** Unisce un documento ricevuto: per ogni voce vince la modifica più recente. Ritorna true se qualcosa è cambiato. */
    @Synchronized
    private fun fromJson(o: JSONObject): Boolean {
        var changed = false
        o.optJSONObject("tx")?.let { j -> j.keys().forEach { id ->
            val v = j.getJSONObject(id)
            val u = v.optLong("u")
            if ((tx[id]?.u ?: -1L) < u) { tx[id] = Rec(v.optLong("t"), v.optLong("c"), v.optString("k", "Altro"), v.optString("n"), v.optString("s", "manual"), u, v.optInt("d") == 1); changed = true }
        } }
        o.optJSONObject("cash")?.let { j -> j.keys().forEach { k ->
            val a = j.getJSONArray(k)
            if ((cash[k.toInt()]?.second ?: -1L) < a.getLong(1)) { cash[k.toInt()] = a.getInt(0) to a.getLong(1); changed = true }
        } }
        o.optJSONArray("rev")?.let { a -> if (a.getLong(2) > rev.third) { rev = Triple(a.getLong(0), a.getLong(1), a.getLong(2)); changed = true } }
        return changed
    }

    @Synchronized
    private fun save() { try { file.writeText(toJson().toString()) } catch (_: Exception) {} }

    @Synchronized
    private fun mutate(f: () -> Unit) {
        f(); publish(); save()
        syncJob?.cancel()
        syncJob = r.scope.launch { kotlinx.coroutines.delay(1200); push() }
    }

    // ---------------------------------------------------------------- sincronizzazione
    /** Server (principale): invia il documento e riceve quello unito. */
    suspend fun syncServer(): Boolean {
        val base = r.prefs.httpBase()?.toHttpUrlOrNull()?.newBuilder()?.addPathSegments("api/finance")?.build() ?: return false
        return try {
            val body = toJson().toString().toRequestBody("application/json".toMediaType())
            http.newCall(Request.Builder().url(base).header("Authorization", "Bearer ${r.prefs.token}").post(body).build()).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val changed = fromJson(JSONObject(resp.body!!.string()))
                if (changed) { publish(); save() }
                true
            }
        } catch (_: Exception) { false }
    }

    /** Messaggio ai dispositivi raggiungibili (server come relè, rete locale, Bluetooth, Wi-Fi Direct): il fallback quando il server non risponde. */
    private fun broadcast() {
        val doc = toJson()
        if (doc.toString().length < 400_000) r.send(newMsg("fin", "doc" to doc))
    }

    suspend fun push() { syncServer(); broadcast() }

    fun onRemote(doc: JSONObject?) {
        if (doc != null && fromJson(doc)) { publish(); save() }
    }

    fun start() {
        r.scope.launch {
            while (true) {
                kotlinx.coroutines.delay(2 * 60_000L)
                if (r.screenOn) push()
            }
        }
    }

    // ---------------------------------------------------------------- operazioni
    fun add(cents: Long, cat: String, note: String, src: String = "manual", ts: Long = System.currentTimeMillis()) {
        // Stesso importo e descrizione entro due minuti: è lo stesso movimento (notifica doppia, anche da due dispositivi).
        if (tx.values.any { !it.d && it.s == src && it.c == cents && it.n == note && kotlin.math.abs(it.t - ts) < 120_000 }) return
        val id = if (src == "revolut") "r" + java.lang.Long.toHexString((src + cents + note + ts / 120_000).hashCode().toLong() and 0xffffffffL) + java.lang.Long.toHexString(ts / 120_000)
        else java.util.UUID.randomUUID().toString().take(12)
        mutate { tx[id] = Rec(ts, cents, cat, note, src, System.currentTimeMillis(), false) }
    }
    fun remove(id: String) = mutate { tx[id]?.let { tx[id] = Rec(it.t, it.c, it.k, it.n, it.s, System.currentTimeMillis(), true) } }
    fun bump(denom: Int, by: Int) = mutate { cash[denom] = ((cash[denom]?.first ?: 0) + by).coerceAtLeast(0) to System.currentTimeMillis() }
    fun clearCash() = mutate { val u = System.currentTimeMillis(); cash.keys.toList().forEach { cash[it] = 0 to u } }
    /** Imposta il saldo totale a mano: la base si calcola sottraendo i movimenti che contano già. */
    fun setTotal(cents: Long) = mutate {
        val now = System.currentTimeMillis()
        rev = Triple(0L, now, now)
        val rest = tx.values.filter { !it.d }.sumOf { if (it.s == "revolut") 0L else it.c }
        rev = Triple(cents - rest, now, now)
    }
    fun setSource(id: String, src: String) = mutate { tx[id]?.let { tx[id] = Rec(it.t, it.c, it.k, it.n, src, System.currentTimeMillis(), it.d) } }

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
        var lastTs = 0L; var lastBal: Long? = null
        val before = state.value.txs.count { it.src == "revolut" }
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
        if (lastBal != null) mutate { rev = Triple(lastBal!!, lastTs, System.currentTimeMillis()) }
        return state.value.txs.count { it.src == "revolut" } - before
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
