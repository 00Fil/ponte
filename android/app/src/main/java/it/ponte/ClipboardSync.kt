package it.ponte

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow

data class ClipItem(val text: String, val from: String, val ts: Long, val mine: Boolean)

class ClipboardSync(private val ctx: Context, private val r: Router) {
    private val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val main = Handler(Looper.getMainLooper())
    private var lastApplied: String? = null

    val history = MutableStateFlow<List<ClipItem>>(emptyList())

    @Volatile private var ignoreUntil = 0L

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        if (System.currentTimeMillis() < ignoreUntil) return@OnPrimaryClipChangedListener
        val text = read()
        when {
            // Con l'app in background Android restituisce vuoto: se c'è il permesso "sopra le altre app",
            // un'attività trasparente passa un istante in primo piano e legge gli appunti.
            text == null -> if (android.provider.Settings.canDrawOverlays(ctx)) launchReader()
            text != lastApplied && text.isNotEmpty() && text.length <= 1_000_000 -> send(text)
        }
    }

    private fun launchReader() {
        try {
            ctx.startActivity(
                android.content.Intent(ctx, ClipSendActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra("silent", true),
            )
        } catch (_: Exception) {}
    }

    private fun read(): String? = try {
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
    } catch (_: Exception) {
        null
    }

    private fun apply(text: String) {
        lastApplied = text
        ignoreUntil = System.currentTimeMillis() + 1500
        try { cm.setPrimaryClip(ClipData.newPlainText("Ponte", text)) } catch (_: Exception) {}
    }

    fun start() {
        cm.removePrimaryClipChangedListener(listener)
        cm.addPrimaryClipChangedListener(listener)
    }

    /**
     * Legge gli appunti e li invia. Da Android 10 funziona solo con l'app in primo piano:
     * in background il sistema restituisce vuoto, e allora non si invia nulla.
     */
    fun sendCurrent(): Boolean {
        val text = read()
        if (text.isNullOrEmpty() || text == lastApplied || text.length > 1_000_000) return false
        send(text)
        return true
    }

    fun send(text: String) {
        push(ClipItem(text, r.deviceId, System.currentTimeMillis(), true))
        r.send(newMsg("clip", "text" to text))
    }

    fun onRemote(text: String, from: String) {
        if (text.isEmpty() || text.length > 1_000_000) return
        lastApplied = text
        push(ClipItem(text, from, System.currentTimeMillis(), false))
        main.post { apply(text) }
    }

    /** Rimette negli appunti locali una voce della cronologia, senza rimandarla agli altri. */
    fun copyLocal(text: String) {
        apply(text)
    }

    private val http by lazy { okhttp3.OkHttpClient() }

    /** Recupera dal server la cronologia degli appunti. Aggiorna solo la cronologia: gli appunti del telefono non si toccano. */
    fun catchUp() {
        val base = r.prefs.httpBase() ?: return
        try {
            val req = okhttp3.Request.Builder().url("$base/api/clips?limit=50").header("Authorization", "Bearer ${r.prefs.token}").build()
            val arr = http.newCall(req).execute().use { if (!it.isSuccessful) return else org.json.JSONArray(it.body!!.string()) }
            val known = history.value.map { it.text }.toSet()
            val add = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val t = o.optString("text")
                if (t.isEmpty() || t in known) null else ClipItem(t, o.optString("from"), o.optLong("ts"), o.optString("from") == r.deviceId)
            }
            if (add.isNotEmpty()) history.value = (history.value + add).sortedByDescending { it.ts }.take(50)
        } catch (_: Exception) {
        }
    }

    private fun push(item: ClipItem) {
        history.value = (listOf(item) + history.value.filter { it.text != item.text }).take(50)
    }
}
