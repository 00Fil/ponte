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

    private val listener = ClipboardManager.OnPrimaryClipChangedListener { sendCurrent() }

    fun start() {
        cm.removePrimaryClipChangedListener(listener)
        cm.addPrimaryClipChangedListener(listener)
    }

    /**
     * Legge gli appunti e li invia. Da Android 10 funziona solo con l'app in primo piano:
     * in background il sistema restituisce vuoto, e allora non si invia nulla.
     */
    fun sendCurrent(): Boolean {
        val text = try {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
        } catch (_: SecurityException) {
            null
        }
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
        main.post { cm.setPrimaryClip(ClipData.newPlainText("Ponte", text)) }
    }

    /** Rimette negli appunti locali una voce della cronologia, senza rimandarla agli altri. */
    fun copyLocal(text: String) {
        lastApplied = text
        cm.setPrimaryClip(ClipData.newPlainText("Ponte", text))
    }

    private fun push(item: ClipItem) {
        history.value = (listOf(item) + history.value.filter { it.text != item.text }).take(50)
    }
}
