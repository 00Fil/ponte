package it.ponte

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

const val HISTORY_MAX = 5000

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
        if (text == null && sendFileIfAny()) return@OnPrimaryClipChangedListener
        when {
            // Con l'app in background Android restituisce vuoto: se c'è il permesso "sopra le altre app",
            // un'attività trasparente passa un istante in primo piano e legge gli appunti.
            text == null -> if (android.provider.Settings.canDrawOverlays(ctx)) launchReader()
            text != lastApplied && text.isNotEmpty() && text.length <= 1_000_000 -> send(text)
        }
    }

    /** Chiamato da ClipLogWatcher mentre una finestra di Ponte ha il focus: qui gli appunti sono leggibili. */
    fun readAndSendFocused() {
        if (System.currentTimeMillis() < ignoreUntil) return
        val text = read()
        if (text == null) { sendFileIfAny(); return }
        if (text.isNotEmpty() && text != lastApplied && text.length <= 1_000_000) {
            lastApplied = text
            send(text)
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
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.let { item ->
            // Con un URI (immagine, file) coerceToText darebbe l'indirizzo: lì conta il file, non il testo.
            if (item.uri != null) item.text?.toString() else item.coerceToText(ctx)?.toString()
        }
    } catch (_: Exception) {
        null
    }

    /** Immagine o file negli appunti, se ci sono. Va chiamato mentre si ha il focus. */
    private fun readFile(): Triple<android.net.Uri, String, String>? = try {
        val clip = cm.primaryClip?.takeIf { it.itemCount > 0 }
        val uri = clip?.getItemAt(0)?.uri
        if (clip == null || uri == null) null
        else {
            val mime = ctx.contentResolver.getType(uri) ?: clip.description.getMimeType(0) ?: "application/octet-stream"
            val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
                ?: ("Appunti-" + System.currentTimeMillis() + "." + (android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"))
            Triple(uri, mime, name)
        }
    } catch (_: Exception) {
        null
    }

    /** Legge ciò che c'è negli appunti e, se è un file o un'immagine, lo invia. Restituisce true se ha trovato qualcosa da inviare. */
    private fun sendFileIfAny(): Boolean {
        val (uri, mime, name) = readFile() ?: return false
        if (uri.toString() == lastApplied) return false
        lastApplied = uri.toString()
        // La copia nell'archivio parte subito, finché il permesso sull'URI è valido.
        r.scope.launch { r.files.sendClipUri(uri, name, mime) }
        return true
    }

    /** Un file copiato su un altro dispositivo arriva anche negli appunti di questo. */
    fun applyFile(e: FileEntry) {
        main.post {
            try {
                val uri = r.files.uriFor(e)
                lastApplied = uri.toString()
                ignoreUntil = System.currentTimeMillis() + 3000
                cm.setPrimaryClip(ClipData.newUri(ctx.contentResolver, e.name, uri))
            } catch (_: Exception) {}
        }
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
        if (text == null && sendFileIfAny()) return true
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
            val req = okhttp3.Request.Builder().url("$base/api/clips").header("Authorization", "Bearer ${r.prefs.token}").build()
            val arr = http.newCall(req).execute().use { if (!it.isSuccessful) return else org.json.JSONArray(it.body!!.string()) }
            val known = history.value.map { it.text }.toSet()
            val add = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val t = o.optString("text")
                if (t.isEmpty() || t in known) null else ClipItem(t, o.optString("from"), o.optLong("ts"), o.optString("from") == r.deviceId)
            }
            if (add.isNotEmpty()) history.value = (history.value + add).sortedByDescending { it.ts }.take(HISTORY_MAX)
        } catch (_: Exception) {
        }
    }

    private fun push(item: ClipItem) {
        history.value = (listOf(item) + history.value.filter { it.text != item.text }).take(HISTORY_MAX)
    }
}
