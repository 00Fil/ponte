package it.ponte

import android.content.Context
import android.graphics.Bitmap
import android.os.PowerManager
import android.util.Base64
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Le telecamere sugli altri dispositivi: il PC non ha il motore del produttore, quindi le immagini gliele gira il telefono.
 * Chi guarda manda "cam.watch" ogni pochi secondi (che cosa sta guardando); finché arriva, i flussi restano aperti e partono i "cam.frame".
 * Si comprime fuori dal thread del motore e solo l'ultima immagine: se il collegamento è lento si salta, non si accumula.
 */
object CamRelay {
    private const val EXPIRY = 12_000L
    private const val MAX_QUEUE = 384 * 1024L
    private const val MAX_PENDING = 2
    private val MODES = setOf("smart", "light", "ir", "daylight", "night")

    private class Watch {
        @Volatile var view = "tile"
        @Volatile var ch = -1
        @Volatile var hd = false
        @Volatile var hide = emptySet<Int>()
        @Volatile var until = 0L
        /** Immagini affidate al collegamento e non ancora scritte. */
        val pending = AtomicInteger()
        val sentAt = HashMap<Int, Long>()
        val sent = HashMap<Int, Bitmap>()
    }

    private val watchers = ConcurrentHashMap<String, Watch>()
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null
    @Volatile private var connectAt = 0L

    private fun configured(r: Router) = r.prefs.camId.isNotEmpty() && r.prefs.camPwd.isNotEmpty()

    fun onWatch(r: Router, m: JSONObject) {
        val from = m.optString("from")
        if (from.isEmpty()) return
        val view = m.optString("view")
        if (view != "tile" && view != "grid" && view != "full") {
            watchers.remove(from)
            return
        }
        val ready = configured(r)
        if (ready) {
            val fresh = !watchers.containsKey(from)
            val now = System.currentTimeMillis()
            // si ritenta il collegamento quando qualcuno comincia a guardare, poi con calma
            if (fresh || Cams.status.value == -1 || now - connectAt > 30_000L) { connectAt = now; Cams.ensure(r) }
            val w = watchers.getOrPut(from) { Watch() }
            val hide = m.optJSONArray("hide")
            w.view = view
            w.ch = m.optInt("ch", -1)
            w.hd = view == "full" && m.optBoolean("hd")
            w.hide = if (hide == null) emptySet() else (0 until hide.length()).map { hide.optInt(it, -1) }.toSet()
            w.until = now + EXPIRY
            start(r)
        }
        r.send(state(from, ready, m.optBoolean("alerts")))
    }

    fun onCmd(r: Router, m: JSONObject) {
        // il valore finisce nel comando per il registratore: solo canali e modi conosciuti
        val ch = m.optInt("ch", -1)
        val mode = m.optString("light")
        if (!configured(r) || ch !in 0..63 || mode !in MODES) return
        Cams.light(ch, mode)
    }

    private fun state(to: String, ready: Boolean, alerts: Boolean): JSONObject {
        val m = newMsg(
            "cam.state", "to" to to, "ready" to ready, "status" to Cams.status.value, "error" to Cams.error.value,
            "live" to JSONArray(Cams.live.value.sorted()), "seen" to JSONArray(Cams.seen.value.sorted()),
        )
        if (alerts) m.put("alerts", JSONArray().also { a ->
            Cams.alerts.value.sortedByDescending { it.time }.take(30).forEach {
                a.put(JSONObject().put("time", it.time).put("channel", it.channel).put("type", it.type).put("img", it.img))
            }
        })
        return m
    }

    @Synchronized
    private fun start(r: Router) {
        // il telefono a schermo spento non deve addormentarsi mentre qualcuno guarda
        val lock = wake ?: (r.ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "relay:cams").apply { setReferenceCounted(false) }.also { wake = it }
        try { lock.acquire(EXPIRY) } catch (_: SecurityException) {}
        if (job != null) return
        job = r.scope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                watchers.entries.removeAll { it.value.until < now }
                if (stopIfIdle()) return@launch
                val full = watchers.values.firstOrNull { it.view == "full" && it.ch >= 0 }
                Cams.remoteDemand(true, full?.ch ?: -1, full?.hd == true)
                for ((id, w) in watchers) try { pump(r, id, w) } catch (_: Exception) {}
                delay(60)
            }
        }
    }

    @Synchronized
    private fun stopIfIdle(): Boolean {
        if (watchers.isNotEmpty()) return false
        job = null
        Cams.remoteDemand(false)
        wake?.let { try { if (it.isHeld) it.release() } catch (_: RuntimeException) {} }
        return true
    }

    /** Ogni quanto serve un'immagine di quel canale, e quanto grande. Null = non serve. */
    private fun need(w: Watch, ch: Int): Pair<Long, Int>? = when (w.view) {
        "full" -> if (ch == w.ch) 150L to (if (w.hd) 1280 else 960) else null
        "grid" -> 900L to 480
        else -> if (ch == w.ch) 500L to 640 else 5_000L to 480
    }

    /** Manda al più un'immagine per giro: quella che aspetta da più tempo tra quelle scadute e cambiate. */
    private fun pump(r: Router, to: String, w: Watch) {
        if (w.pending.get() >= MAX_PENDING || r.queuedTo(to) > MAX_QUEUE) return
        val now = System.currentTimeMillis()
        val list = if (w.view == "full") listOf(w.ch) else Cams.seen.value.ifEmpty { Cams.live.value }.filter { it !in w.hide }
        var best = -1
        var bestAt = Long.MAX_VALUE
        var frame: Bitmap? = null
        var side = 0
        for (ch in list) {
            if (ch < 0) continue
            val (every, width) = need(w, ch) ?: continue
            val at = w.sentAt[ch] ?: 0L
            if (now - at < every || at >= bestAt) continue
            val bmp = Cams.frame(ch).value ?: continue
            if (bmp === w.sent[ch]) continue
            best = ch; bestAt = at; frame = bmp; side = width
        }
        val bmp = frame ?: return
        val small = if (bmp.width > side) Bitmap.createScaledBitmap(bmp, side, (side.toFloat() * bmp.height / bmp.width).toInt().coerceAtLeast(1), true) else bmp
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, if (w.view == "full") 68 else 55, out)
        w.sentAt[best] = now
        w.sent[best] = bmp
        w.pending.incrementAndGet()
        r.send(
            newMsg(
                "cam.frame", "to" to to, "ch" to best, "w" to small.width, "h" to small.height,
                "jpg" to Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP),
            ),
        ) { w.pending.decrementAndGet() }
        if (small !== bmp) small.recycle()
    }
}
