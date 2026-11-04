package it.ponte

import android.content.Context
import android.graphics.Bitmap
import com.app.jagles.connect.JAConnectorV2
import com.juan.base.encryption.EncryptionUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Telecamere Eseecloud dal cloud del produttore, col loro stesso motore nativo: nessun PC né VPN.
 * Stato: 0 in corso, 6 collegato, 10 password errata, 2/3/11/12 non raggiungibile, 9 scollegato.
 * Eventi dei canali: 13 aperto, 14 chiuso, 15 primo fotogramma.
 */
object Cams {
    val status = MutableStateFlow(-1)
    val error = MutableStateFlow("")
    val frames = ConcurrentHashMap<Int, MutableStateFlow<Bitmap?>>()
    val channels = MutableStateFlow(8)
    /** Canali collegati e canali che hanno mandato almeno un'immagine (le cam che esistono davvero). */
    val live = MutableStateFlow(setOf<Int>())
    val seen = MutableStateFlow(setOf<Int>())
    /** Pagina Telecamere aperta (dalla Home o da una notifica). */
    val ui = MutableStateFlow(false)
    /** Canale in riproduzione di una registrazione, o -1. */
    val playing = MutableStateFlow(-1)
    val records = MutableStateFlow<List<Seg>>(emptyList())
    val searching = MutableStateFlow(false)
    /** Istante (secondi) della registrazione in riproduzione, dall'orologio impresso nel video. */
    val playTime = MutableStateFlow(0)
    /** Alta qualità attiva sul canale a tutto schermo. */
    val hd = MutableStateFlow(false)
    /** La riproduzione ha mandato il primo fotogramma: da lì parte l'orologio della timeline. */
    val playStarted = MutableStateFlow(false)
    /** Cam nascoste dall'utente. */
    val hidden = MutableStateFlow(setOf<Int>())
    private var prefs: Prefs? = null
    fun toggleHidden(ch: Int) { val h = if (ch in hidden.value) hidden.value - ch else hidden.value + ch; hidden.value = h; prefs?.camHidden = h }
    /** Avvisi ricevuti dal cloud (persone, movimento). */
    val alerts = MutableStateFlow<List<Alert>>(emptyList())

    data class Seg(val start: Int, val end: Int, val type: Int)
    data class Alert(val time: Long, val channel: Int, val type: String, val img: String)

    private var key = ""
    private var user = ""
    private var pwd = ""
    private var ok = false
    @Volatile private var handle = 0L
    private val lastAt = ConcurrentHashMap<Int, Long>()
    private val bigAt = ConcurrentHashMap<Int, Long>()
    private val open = ConcurrentHashMap.newKeySet<Pair<Int, Int>>()   // (canale, flusso: 0 principale, 1 leggero)
    @Volatile private var paused = false
    /** Canale a tutto schermo: niente limite di immagini al secondo e si tiene il flusso principale. */
    @Volatile var focus = -1
    private val bufs = ConcurrentHashMap<Int, java.nio.ByteBuffer>()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun frame(ch: Int) = frames.getOrPut(ch) { MutableStateFlow(null) }

    private fun engine(): JAConnectorV2? = try {
        val c = JAConnectorV2.get()
        if (!ok) { ok = true; JAConnectorV2.sink = Sink }
        c
    } catch (e: Throwable) { error.value = "Motore non caricato"; null }

    fun ensure(r: Router) {
        val p = r.prefs
        if (prefs == null) { prefs = p; hidden.value = p.camHidden }
        if (p.camId.isEmpty() || p.camPwd.isEmpty()) return
        if (key == p.camId && (status.value == 6 || status.value == 0)) return
        connect(p.camId, p.camUser, p.camPwd)
    }

    fun connect(id: String, u: String, pw: String, count: Int = channels.value) {
        val c = engine() ?: return
        if (key.isNotEmpty() && key != id) disconnect()
        key = id; user = u; pwd = pw
        status.value = 0; error.value = ""
        val verify = try { EncryptionUtil.encryptVerify("${System.currentTimeMillis() / 1000}&$u&$pw&") } catch (e: Throwable) { error.value = "Cifratura non disponibile"; return }
        try {
            if (handle == 0L) handle = c.nativeCreateConnector(id, false)
            for (ch in 0 until count) c.nativeConnect(handle, id, "", verify, count, ch, ch, 40, false)
        } catch (e: Throwable) { error.value = "Collegamento non riuscito" }
    }

    fun disconnect() {
        val c = engine() ?: return
        open.toList().forEach { (ch, b) -> close(ch, b) }
        if (handle != 0L) for (ch in 0 until channels.value) try { c.nativeDisconnect(handle, ch) } catch (_: Throwable) {}
        live.value = emptySet()
        status.value = -1
    }

    private fun openRaw(ch: Int, b: Int) {
        val c = engine() ?: return
        if (handle == 0L || paused || !open.add(ch to b)) return
        try { c.nativeOpenStream(handle, b, ch, ch, ch, false) } catch (_: Throwable) { open.remove(ch to b) }
    }

    private fun close(ch: Int, b: Int) {
        val c = engine() ?: return
        if (!open.remove(ch to b)) return
        try { c.nativeCloseStream(handle, b, ch, ch) } catch (_: Throwable) {}
    }

    /** Tutto schermo: si apre l'alta qualità senza chiudere quella leggera, che resta a vista finché la prima non arriva. */
    /** Tutto schermo: resta il flusso già aperto (immagine subito in tempo reale); l'alta qualità solo se richiesta. */
    fun enter(ch: Int) { focus = ch; openRaw(ch, 1); if (hd.value) openRaw(ch, 0) }
    fun leave(ch: Int) { if (focus == ch) focus = -1; close(ch, 0) }
    fun setHd(ch: Int, on: Boolean) { hd.value = on; if (on) openRaw(ch, 0) else { close(ch, 0); bigAt.remove(ch) } }

    /** App in secondo piano: si chiudono i flussi; al ritorno si riaprono subito quelli leggeri di tutte le cam. */
    fun pause() { paused = true; open.toList().forEach { (ch, b) -> close(ch, b) } }
    fun resume() { paused = false; live.value.forEach { openRaw(it, 1) }; if (focus >= 0) openRaw(focus, 0) }

    fun send(json: String, ch: Int = 0) {
        val c = engine() ?: return
        if (handle == 0L) return
        val b = json.toByteArray()
        try { c.nativeSendData(handle, b, b.size, ch, 10, 0) } catch (_: Throwable) {}
    }

    fun light(ch: Int, mode: String) = send(
        """{"IPCam":{"ModeSetting":{"IRCutFilterMode":"$mode"},"ChannelManager":{"ChannelList":"$ch","Operation":"SetChannel"}},"Version":"2.0.0","Method":"set","Authorization":{"Verify":"","username":"$user","password":"$pwd"}}""", ch,
    )

    // ---------------------------------------------------------------- registrazioni
    private val found = ArrayList<Seg>()

    /** Registrazioni di un canale tra due istanti (secondi). */
    fun search(ch: Int, from: Int, to: Int) {
        val c = engine() ?: return
        if (handle == 0L) return
        synchronized(found) { found.clear() }
        records.value = emptyList(); searching.value = true
        try { c.nativeSearchRecord(handle, from, to, 65535, ch, ch, 0) } catch (_: Throwable) { searching.value = false }
        io.launch { delay(12_000); searching.value = false }
    }

    fun play(ch: Int, at: Int) {
        val c = engine() ?: return
        if (handle == 0L) return
        if (playing.value >= 0) stopPlay()
        close(ch, 0); close(ch, 1)
        focus = ch; playing.value = ch; playTime.value = at; playStarted.value = false
        try { c.nativeStartPlayback(handle, at, 65535, false, ch, ch, ch) } catch (_: Throwable) { playing.value = -1 }
    }

    fun stopPlay() {
        val c = engine() ?: return
        val ch = playing.value
        if (ch < 0) return
        try { c.nativeStopPlayback(handle, ch, ch) } catch (_: Throwable) {}
        playing.value = -1
        openRaw(ch, 1); if (focus == ch) openRaw(ch, 0)
    }

    private object Sink : JAConnectorV2.Sink {
        override fun status(key: String, st: Int, index: Int) {
            if (st == 6) { live.value = live.value + index; openRaw(index, 1) }
            else if (st in listOf(2, 3, 9, 10, 11, 12)) live.value = live.value - index
            if (st < 12 && (st == 6 || live.value.isEmpty())) status.value = st
            if (st == 10) error.value = "Password errata"
        }
        override fun frame(key: String, w: Int, h: Int, data: ByteArray?, len: Int, channel: Int, ptr: Long) {
            if (w <= 0 || h <= 0 || ptr == 0L || data != null || len < w * h * 4 || !CamMem.ok) return
            val now = System.currentTimeMillis()
            if (channel == focus) {
                // a tutto schermo: appena arriva l'alta qualità, quella leggera non sostituisce più l'immagine
                if (w >= 1000) bigAt[channel] = now
                else if (now - (bigAt[channel] ?: 0L) < 1500) return
            } else if (now - (lastAt[channel] ?: 0L) < 110) return
            lastAt[channel] = now
            // la riproduzione parte dal fotogramma chiave prima dell'istante chiesto: circa 2 secondi indietro
            if (channel == playing.value && !playStarted.value) { playTime.value = playTime.value - 2; playStarted.value = true }
            if (channel !in seen.value) seen.value = seen.value + channel
            val n = w * h * 4
            var buf = bufs[channel]
            if (buf == null || buf.capacity() < n) { buf = java.nio.ByteBuffer.allocateDirect(n); bufs[channel] = buf }
            CamMem.copy(ptr, buf!!, n)
            buf.rewind()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)
            buf.rewind()
            frame(channel).value = bmp
        }
        override fun audio(key: String, data: ByteArray?) {}
        override fun vcon(key: String, json: String, index: Int) {}
        override fun command(code: Int, key: String, st: Int, index: Int) {}
        override fun stream(key: String, st: Int, channel: Int) {}
        override fun osd(key: String, a: Int, b: Int) { if (a > 1_000_000_000) playTime.value = a else if (b > 1_000_000_000) playTime.value = b }
        override fun record(key: String, a: Int, b: Int, c: Int, d: Int, e: Int, f: Int) {
            synchronized(found) {
                if (a > 0 && b > a) found.add(Seg(a, b, c))
                records.value = found.sortedByDescending { it.start }
            }
            if (d == 1) searching.value = false
        }
    }

    // ---------------------------------------------------------------- avvisi dal cloud (persone rilevate)
    private val http by lazy { okhttp3.OkHttpClient.Builder().callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build() }
    private const val PM = "https://pm.dvr163.com"

    /** Ultimi avvisi del registratore dal server push di Eseecloud, come fa l'app ufficiale. */
    fun fetchAlerts(id: String): List<Alert>? = try {
        val n = http.newCall(okhttp3.Request.Builder().url("$PM/message/nonce?method=get").build()).execute().use { org.json.JSONObject(it.body?.string().orEmpty()) }
        val nonce = n.getString("nonce"); val rid = n.getString("request_id")
        val s = (nonce.uppercase() + rid.uppercase())
        val verify = EncryptionUtil.nativeSignViaIndex(arrayOf(s), s.length, 4)
        val cal = java.util.Calendar.getInstance()
        val time = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time) + " " + (cal.timeZone.getOffset(cal.timeInMillis) / 3_600_000)
        val url = "$PM/message/message?method=newlist_v5&start=0&count=30&time=" + java.net.URLEncoder.encode(time, "utf-8")
        val form = okhttp3.FormBody.Builder().add("mac_token", "02:00:00:00:00:00").add("eseeid", "[\"$id\"]").add("request_id", rid).add("verify", verify).build()
        val body = http.newCall(okhttp3.Request.Builder().url(url).header("Cookie", "PHPSESSID=$rid").post(form).build()).execute().use { it.body?.string().orEmpty() }
        val arr = org.json.JSONObject(body).optJSONArray("list") ?: org.json.JSONArray()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val img = o.optString("img_url").let { u -> if (u.startsWith("http")) u else try { String(android.util.Base64.decode(u, 0)) } catch (_: Exception) { "" } }
            Alert(o.optLong("time"), o.optInt("channel"), o.optString("type"), img)
        }
    } catch (_: Exception) { null }

    /** Controllo periodico: una notifica per ogni persona (o movimento) nuova. */
    fun watch(ctx: Context, r: Router) {
        io.launch {
            while (true) {
                val id = r.prefs.camId
                if (id.isNotEmpty()) fetchAlerts(id)?.let { list ->
                    alerts.value = list
                    val sp = ctx.getSharedPreferences("cams", Context.MODE_PRIVATE)
                    val last = sp.getLong("lastAlert", 0L)
                    val fresh = list.filter { it.time > last }.sortedBy { it.time }
                    if (last == 0L) sp.edit().putLong("lastAlert", list.maxOfOrNull { it.time } ?: 1L).apply()
                    else if (fresh.isNotEmpty()) {
                        sp.edit().putLong("lastAlert", fresh.last().time).apply()
                        fresh.takeLast(3).forEach { a -> notifyAlert(ctx, a) }
                    }
                }
                delay(if (Notify.foreground) 30_000L else 60_000L)
            }
        }
    }

    /** Il registratore conta il tempo come ora locale scritta in UTC: differenza da aggiungere all'orario vero. */
    fun tzOff(): Int = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000

    /** Tipi di registrazione del registratore (bit). */
    const val T_MOTION = 2; const val T_ALARM = 4; const val T_HUMAN = 16
    fun isEvent(t: Int) = t and (T_MOTION or T_ALARM or T_HUMAN or 32 or 64 or 128) != 0
    fun eventName(t: Int) = when { t and T_HUMAN != 0 -> "Persona"; t and T_MOTION != 0 -> "Movimento"; t and T_ALARM != 0 -> "Allarme"; else -> "Evento" }

    /** Salva l'immagine attuale della cam nella galleria. */
    fun snapshot(ctx: Context, ch: Int): Boolean {
        val bmp = frame(ch).value ?: return false
        return try {
            val v = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "Cam${ch + 1}_${System.currentTimeMillis()}.jpg")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Relay")
            }
            val uri = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return false
            ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            true
        } catch (_: Exception) { false }
    }

    fun kind(t: String) = when (t) { "hd" -> "Persona rilevata"; "md", "motion" -> "Movimento"; else -> "Avviso" }

    private fun notifyAlert(ctx: Context, a: Alert) {
        val pic = if (a.img.isNotEmpty()) try { http.newCall(okhttp3.Request.Builder().url(a.img).build()).execute().use { if (it.isSuccessful) android.graphics.BitmapFactory.decodeStream(it.body?.byteStream()) else null } } catch (_: Exception) { null } else null
        Notify.channels(ctx)
        val title = kind(a.type)
        val text = "Telecamera ${a.channel + 1} · " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.ITALY).format(java.util.Date(a.time * 1000))
        val b = android.app.Notification.Builder(ctx, Notify.CAMS).setSmallIcon(R.drawable.ic_n_event).setContentTitle(title).setContentText(text)
            .setAutoCancel(true).setContentIntent(Notify.open(ctx, 7100, "cams")).setColor(0xFF2F5FFF.toInt()).setCategory(android.app.Notification.CATEGORY_EVENT)
        if (pic != null) b.setLargeIcon(pic).setStyle(android.app.Notification.BigPictureStyle().bigPicture(pic))
        ctx.getSystemService(android.app.NotificationManager::class.java).notify(7100 + (a.time % 1000).toInt(), b.build())
    }
}
