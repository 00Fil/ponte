package it.ponte.car

import android.graphics.Bitmap
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser.MediaItem
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.media.MediaBrowserService
import it.ponte.Cams
import it.ponte.Notify
import it.ponte.Ponte
import it.ponte.PonteService
import it.ponte.R
import it.ponte.Router
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Relay su Android Auto come app audio: è l'unico tipo che Android Auto accetta fuori dal Play Store.
 * Le schede sono le sezioni della Home; "riprodurre" una voce la apre nel lettore (pomodoro con barra
 * di avanzamento, audio degli altri dispositivi, immagine delle cam) oppure esegue l'azione (spunta una To-do).
 */
class RelayMediaService : MediaBrowserService() {
    private lateinit var r: Router
    private lateinit var session: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Cosa c'è nel lettore: "" | timer | audio:<id> | cam:<canale> | pic:<url> | info */
    @Volatile private var mode = ""
    @Volatile private var info = Pair("", "")
    private val pics = ConcurrentHashMap<String, Bitmap>()
    private val loading = ConcurrentHashMap.newKeySet<String>()

    override fun onCreate() {
        super.onCreate()
        try { PonteService.start(this) } catch (_: Exception) {}
        r = Ponte.ensure(this)
        session = MediaSession(this, "Relay").apply {
            setCallback(Cb())
            setSessionActivity(Notify.open(this@RelayMediaService, 8300, null))
        }
        sessionToken = session.sessionToken
        publish()
        scope.launch {
            merge(r.plan.state, r.media.media, r.finance.state, r.devices, Cams.alerts, Cams.seen).conflate().collect {
                listOf(TAB_AUDIO, TAB_OGGI, TAB_CAM, TAB_DISP).forEach { notifyChildrenChanged(it) }
                delay(2_000)
            }
        }
        scope.launch { while (true) { publish(); delay(if (mode.startsWith("cam:")) 2_000 else 1_000) } }
    }

    override fun onDestroy() {
        leaveCam()
        scope.cancel()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------- albero

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot(ROOT, Bundle().apply {
            putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
        })

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        result.sendResult(try { children(parentId).toMutableList() } catch (_: Exception) { mutableListOf() })
    }

    private fun item(id: String, title: String, sub: String = "", icon: Int = 0, bmp: Bitmap? = null, group: String? = null, browsable: Boolean = false, grid: Boolean = false): MediaItem {
        val d = MediaDescription.Builder().setMediaId(id).setTitle(title).setSubtitle(sub)
        if (bmp != null) d.setIconBitmap(bmp) else if (icon != 0) d.setIconUri(android.net.Uri.parse("android.resource://$packageName/$icon"))
        val ex = Bundle()
        if (group != null) ex.putString("android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT", group)
        if (grid) ex.putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 2)
        d.setExtras(ex)
        return MediaItem(d.build(), if (browsable) MediaItem.FLAG_BROWSABLE else MediaItem.FLAG_PLAYABLE)
    }

    private fun children(parent: String): List<MediaItem> {
        val now = System.currentTimeMillis()
        val p = r.plan.state.value
        return when (parent) {
            ROOT -> listOf(
                item(TAB_OGGI, "Oggi", icon = R.drawable.ic_n_event, browsable = true),
                item(TAB_AUDIO, "Audio", icon = R.drawable.ic_n_audio, browsable = true),
                item(TAB_CAM, "Cam", icon = R.drawable.ic_c_cam, browsable = true, grid = true),
                item(TAB_DISP, "Dispositivi", icon = R.drawable.ic_c_devices, browsable = true),
            )
            TAB_OGGI -> buildList {
                val t = p.timer
                if (t.running && t.endAt > now) {
                    add(item("t:open", phase(t.phase), "${((t.endAt - now) / 60_000) + 1} min · fino alle ${hm(t.endAt)}", R.drawable.ic_n_timer, group = "Studio"))
                    add(item("t:stop", "Ferma il pomodoro", icon = R.drawable.ic_c_pause, group = "Studio"))
                } else {
                    add(item("t:focus", "Concentrazione", "${p.cfg("focus", 25)} min", R.drawable.ic_n_timer, group = "Studio"))
                    add(item("t:short", "Pausa breve", "${p.cfg("short", 5)} min", R.drawable.ic_n_timer, group = "Studio"))
                }
                upcoming(r).take(6).forEachIndexed { i, e ->
                    add(item("e:$i", e.title.ifEmpty { "Evento" }, listOf(if (e.allDay) day(e.start) else "${day(e.start)} ${hm(e.start)}–${hm(e.end)}", e.place).filter { it.isNotEmpty() }.joinToString(" · "), R.drawable.ic_n_event, group = "Agenda"))
                }
                p.tasks.filter { !it.done }.sortedWith(compareBy({ if (it.due > 0) it.due else Long.MAX_VALUE }, { it.title })).take(8).forEach { k ->
                    add(item("k:${k.id}", k.title.ifEmpty { "—" }, if (k.due > 0) day(k.due) else "Tocca per spuntare", R.drawable.ic_c_todo, group = "To-do"))
                }
                val m = r.finance.state.value
                val month = dayStart(Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis)
                val spent = -m.txs.filter { it.ts >= month && it.cents < 0 }.sumOf { it.cents }
                add(item("s:saldo", euro(m.totalCents), "Uscite del mese ${euro(spent)}", R.drawable.ic_c_wallet, group = "Saldo"))
            }
            TAB_AUDIO -> r.media.media.value.values.sortedWith(compareBy({ !it.playing }, { -it.at })).map { m ->
                item("a:${m.id}", m.title.ifEmpty { "Senza titolo" }, listOf(m.artist, devName(m.id), if (m.playing) "▶" else "‖").filter { it.isNotEmpty() }.joinToString(" · "),
                    if (m.playing) R.drawable.ic_c_play else R.drawable.ic_c_pause)
            }.ifEmpty { listOf(item("x:none", "Niente in riproduzione", icon = R.drawable.ic_n_audio)) }
            TAB_CAM -> buildList {
                Cams.ensure(r)
                val cams = (if (Cams.seen.value.isNotEmpty()) Cams.seen.value else Cams.live.value).sorted().filter { it !in Cams.hidden.value }
                cams.forEach { ch -> add(item("c:$ch", "Cam ${ch + 1}", "Dal vivo", R.drawable.ic_c_cam, group = "Dal vivo")) }
                Cams.alerts.value.sortedByDescending { it.time }.take(12).forEach { a ->
                    load(a.img)
                    add(item("al:${a.time}:${a.channel}", Cams.kind(a.type), "Cam ${a.channel + 1} · ${day(a.time * 1000)} ${hm(a.time * 1000)}", R.drawable.ic_c_cam, pics[a.img], group = "Avvisi"))
                }
            }
            TAB_DISP -> {
                val playing = r.media.playingNow.value
                r.devices.value.map { d ->
                    val plat = when (d.platform) { "windows" -> "Windows"; "android" -> "Android"; else -> d.platform }
                    item("d:${d.id}", d.name, listOfNotNull(plat, if (d.via == "lan") "rete di casa" else "internet", playing[d.id]?.let { "♪ " + it.ifEmpty { "audio" } }).joinToString(" · "), R.drawable.ic_c_devices)
                }.ifEmpty { listOf(item("x:none", "Nessun dispositivo collegato", icon = R.drawable.ic_c_devices)) }
            }
            else -> emptyList()
        }
    }

    private fun devName(id: String) = if (id == r.deviceId) "Questo telefono" else r.deviceName(id)

    private fun load(url: String) {
        if (url.isEmpty() || pics.containsKey(url) || !loading.add(url)) return
        scope.launch(Dispatchers.IO) {
            val b = try {
                okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) android.graphics.BitmapFactory.decodeStream(it.body?.byteStream()) else null }
            } catch (_: Exception) { null }
            if (b != null) { pics[url] = scale(b, 480); notifyChildrenChanged(TAB_CAM); if (mode == "pic:$url") publish() }
        }
    }

    private fun scale(b: Bitmap, w: Int) = Bitmap.createScaledBitmap(b, w, (w.toLong() * b.height / b.width.coerceAtLeast(1)).toInt().coerceAtLeast(1), true)

    // ---------------------------------------------------------------------------------------- lettore

    private fun leaveCam() { mode.removePrefix("cam:").toIntOrNull()?.let { if (mode.startsWith("cam:")) { Cams.leave(it); if (!Notify.foreground) Cams.pause() } } }

    private fun open(id: String) {
        val p = r.plan.state.value
        when {
            id == "t:focus" || id == "t:short" -> {
                val ph = if (id == "t:focus") "focus" else "short"
                if (p.timer.phase != ph) r.plan.timerPhase(ph)
                scope.launch { delay(300); r.plan.timerStart(r.plan.state.value.timer.subject) }
                setMode("timer")
            }
            id == "t:open" -> setMode("timer")
            id == "t:stop" -> { r.plan.timerStop(); setMode("timer") }
            id.startsWith("a:") -> { val d = id.removePrefix("a:"); setMode("audio:$d"); r.media.media.value[d]?.let { if (!it.playing) r.media.cmd(d, "play") } }
            id.startsWith("d:") -> { val d = id.removePrefix("d:"); if (r.media.media.value.containsKey(d)) setMode("audio:$d") else { info = (r.devices.value.firstOrNull { it.id == d }?.name ?: "") to "Collegato"; setMode("info") } }
            id.startsWith("c:") -> id.removePrefix("c:").toIntOrNull()?.let { ch -> setMode("cam:$ch"); Cams.ensure(r); Cams.resume(); Cams.enter(ch) }
            id.startsWith("al:") -> {
                val (t, ch) = id.removePrefix("al:").split(":").let { it[0].toLong() to it[1].toInt() }
                val a = Cams.alerts.value.firstOrNull { it.time == t && it.channel == ch }
                if (a != null && pics.containsKey(a.img)) { info = Cams.kind(a.type) to "Cam ${ch + 1} · ${hm(t * 1000)}"; setMode("pic:${a.img}") }
                else { setMode("cam:$ch"); Cams.ensure(r); Cams.resume(); Cams.enter(ch) }
            }
            id.startsWith("k:") -> {
                p.tasks.firstOrNull { it.id == id.removePrefix("k:") }?.let { r.plan.saveTask(it.copy(done = true)) }
                notifyChildrenChanged(TAB_OGGI)
            }
            id.startsWith("e:") -> upcoming(r).getOrNull(id.removePrefix("e:").toIntOrNull() ?: -1)?.let { e ->
                info = e.title to listOf(if (e.allDay) day(e.start) else "${day(e.start)} ${hm(e.start)}–${hm(e.end)}", e.place).filter { it.isNotEmpty() }.joinToString(" · "); setMode("info")
            }
            id == "s:saldo" -> { val m = r.finance.state.value; info = euro(m.totalCents) to "Saldo"; setMode("info") }
        }
    }

    private fun setMode(m: String) {
        if (m != mode) leaveCam()
        mode = m
        session.isActive = true
        publish()
    }

    private fun publish() {
        if (!::session.isInitialized) return
        val now = System.currentTimeMillis()
        val md = MediaMetadata.Builder()
        val st = PlaybackState.Builder()
        var actions = 0L
        var state = PlaybackState.STATE_PAUSED
        var pos = 0L
        val m = mode
        when {
            m == "timer" -> {
                val p = r.plan.state.value; val t = p.timer
                val on = t.running && t.endAt > now
                val total = if (on) t.endAt - t.startedAt else when (t.phase) { "short" -> p.cfg("short", 5); "long" -> p.cfg("long", 15); else -> p.cfg("focus", 25) } * 60_000L
                md.putString(MediaMetadata.METADATA_KEY_TITLE, phase(t.phase))
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, if (on) "Fino alle ${hm(t.endAt)}" else "Pronto")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, total)
                state = if (on) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
                pos = if (on) now - t.startedAt else 0L
                actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
            }
            m.startsWith("audio:") -> {
                val id = m.removePrefix("audio:")
                val a = r.media.media.value[id]
                md.putString(MediaMetadata.METADATA_KEY_TITLE, a?.title?.ifEmpty { null } ?: "Fermo")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, listOfNotNull(a?.artist?.ifEmpty { null }, devName(id)).joinToString(" · "))
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, a?.dur ?: 0L)
                state = if (a?.playing == true) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
                pos = a?.let { if (it.playing) it.pos + (now - it.at) else it.pos } ?: 0L
                actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
            }
            m.startsWith("cam:") -> {
                val ch = m.removePrefix("cam:").toInt()
                val f = Cams.frame(ch).value?.let { try { scale(it, 640) } catch (_: Exception) { null } }
                md.putString(MediaMetadata.METADATA_KEY_TITLE, "Cam ${ch + 1}")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, if (f == null) "Collegamento…" else "Dal vivo · ${SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(Date(now))}")
                if (f != null) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, f)
                state = PlaybackState.STATE_PLAYING
                actions = PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                st.addCustomAction(PlaybackState.CustomAction.Builder("light", "Luce", R.drawable.ic_c_light).build())
                st.addCustomAction(PlaybackState.CustomAction.Builder("smart", "Automatico", R.drawable.ic_n_sync).build())
            }
            m.startsWith("pic:") -> {
                md.putString(MediaMetadata.METADATA_KEY_TITLE, info.first).putString(MediaMetadata.METADATA_KEY_ARTIST, info.second)
                pics[m.removePrefix("pic:")]?.let { md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
            }
            m == "info" -> md.putString(MediaMetadata.METADATA_KEY_TITLE, info.first).putString(MediaMetadata.METADATA_KEY_ARTIST, info.second)
            else -> md.putString(MediaMetadata.METADATA_KEY_TITLE, "Relay")
        }
        session.setMetadata(md.build())
        session.setPlaybackState(st.setActions(actions).setState(state, pos, if (state == PlaybackState.STATE_PLAYING && !m.startsWith("cam:")) 1f else 0f).build())
    }

    private inner class Cb : MediaSession.Callback() {
        override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) { open(mediaId) }
        override fun onPlay() {
            val m = mode
            when {
                m == "timer" -> { val t = r.plan.state.value.timer; if (!t.running) r.plan.timerStart(t.subject) }
                m.startsWith("audio:") -> r.media.cmd(m.removePrefix("audio:"), "play")
            }
            scope.launch { delay(400); publish() }
        }
        override fun onPause() {
            val m = mode
            when {
                m == "timer" -> r.plan.timerStop()
                m.startsWith("audio:") -> r.media.cmd(m.removePrefix("audio:"), "pause")
            }
            scope.launch { delay(400); publish() }
        }
        override fun onSkipToNext() = skip(1)
        override fun onSkipToPrevious() = skip(-1)
        private fun skip(d: Int) {
            val m = mode
            when {
                m.startsWith("audio:") -> r.media.cmd(m.removePrefix("audio:"), if (d > 0) "next" else "prev")
                m.startsWith("cam:") -> {
                    val cams = (if (Cams.seen.value.isNotEmpty()) Cams.seen.value else Cams.live.value).sorted().filter { it !in Cams.hidden.value }
                    if (cams.isEmpty()) return
                    val i = cams.indexOf(m.removePrefix("cam:").toInt())
                    open("c:" + cams[((i + d) % cams.size + cams.size) % cams.size])
                }
            }
        }
        override fun onCustomAction(action: String, extras: Bundle?) {
            val ch = mode.removePrefix("cam:").toIntOrNull() ?: return
            Cams.light(ch, if (action == "light") "light" else "smart")
        }
    }

    private companion object {
        const val ROOT = "root"; const val TAB_AUDIO = "audio"; const val TAB_OGGI = "oggi"; const val TAB_CAM = "cam"; const val TAB_DISP = "disp"
        fun phase(p: String) = when (p) { "short" -> "Pausa breve"; "long" -> "Pausa lunga"; else -> "Concentrazione" }
        fun hm(t: Long) = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(t))
        fun euro(c: Long) = NumberFormat.getCurrencyInstance(Locale.ITALY).format(c / 100.0)
        fun dayStart(t: Long) = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        fun day(t: Long): String {
            val d = dayStart(t); val today = dayStart(System.currentTimeMillis())
            return when (d) { today -> "Oggi"; today + 86_400_000L -> "Domani"; else -> SimpleDateFormat("EEE d MMM", Locale.ITALY).format(Date(t)) }
        }
        fun upcoming(r: Router): List<Ev2> {
            val now = System.currentTimeMillis()
            val until = dayStart(now) + 2 * 86_400_000L
            val p = r.plan.state.value
            return (p.events.map { Ev2(it.title, it.start, it.end, it.allDay, it.place) } + p.ext.map { Ev2(it.title, it.start, it.end, it.allDay, it.place) })
                .filter { it.end.coerceAtLeast(it.start) > now && it.start < until }.distinctBy { it.title to it.start }.sortedBy { it.start }
        }
    }

    class Ev2(val title: String, val start: Long, val end: Long, val allDay: Boolean, val place: String)
}
