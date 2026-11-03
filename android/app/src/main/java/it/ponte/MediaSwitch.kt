package it.ponte

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Passaggio istantaneo della musica: quando qui parte un contenuto multimediale, gli altri dispositivi
 * che stanno suonando vengono messi in pausa (messaggio `media.take`). Vale a prescindere dalle cuffie.
 */
/** Il player di un dispositivo, come lo vede il player universale. */
data class RemoteMedia(val id: String, val title: String, val artist: String, val playing: Boolean, val pos: Long, val dur: Long, val vol: Int, val at: Long)

class MediaSwitch(private val ctx: Context, private val r: Router) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile private var playing = false
    @Volatile private var startedAt = 0L
    /** Dopo una pausa chiesta da un altro dispositivo non annunciamo nulla per qualche secondo: evita rimbalzi. */
    @Volatile private var quietUntil = 0L

    /** Chi sta suonando adesso tra i dispositivi (id -> titolo, anche vuoto). Include questo telefono. */
    val playingNow = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    /** Cosa c'è su ogni dispositivo (anche in pausa): il player universale lo mostra e lo comanda. Include questo telefono. */
    val media = kotlinx.coroutines.flow.MutableStateFlow<Map<String, RemoteMedia>>(emptyMap())
    private val remote = java.util.concurrent.ConcurrentHashMap<String, RemoteMedia>()

    private fun sessions(): List<android.media.session.MediaController> = try {
        val mgr = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        mgr.getActiveSessions(ComponentName(ctx, NotifListener::class.java))
    } catch (_: Exception) { emptyList() }

    private fun current() = sessions().let { l -> l.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: l.firstOrNull() }

    private fun title(): String = current()?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty()

    private fun local(): RemoteMedia? {
        val s = current() ?: return null
        val md = s.metadata
        val st = s.playbackState
        val pos = when {
            st == null -> 0L
            st.state == PlaybackState.STATE_PLAYING -> st.position + ((android.os.SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
            else -> st.position
        }
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return RemoteMedia(
            r.deviceId, md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty(), md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            playing || st?.state == PlaybackState.STATE_PLAYING, pos, md?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L, vol, System.currentTimeMillis(),
        )
    }

    private fun publish() {
        val now = System.currentTimeMillis()
        remote.entries.removeIf { now - it.value.at > 45_000 }
        val all = HashMap<String, RemoteMedia>(remote)
        local()?.let { if (it.title.isNotEmpty() || it.playing) all[r.deviceId] = it }
        media.value = all
        playingNow.value = all.filterValues { it.playing }.mapValues { it.value.title }
    }

    private fun announce() {
        val l = local()
        r.send(newMsg("media.state", "playing" to (l?.playing ?: playing), "title" to (l?.title ?: ""), "artist" to (l?.artist ?: ""), "pos" to (l?.pos ?: 0L), "dur" to (l?.dur ?: 0L), "vol" to (l?.vol ?: -1)))
        publish()
    }

    fun onState(m: JSONObject) {
        val from = m.optString("from").ifEmpty { return }
        val t = m.optString("title"); val p = m.optBoolean("playing")
        if (!p && t.isEmpty()) remote.remove(from)
        else remote[from] = RemoteMedia(from, t, m.optString("artist"), p, m.optLong("pos"), m.optLong("dur"), m.optInt("vol", -1), System.currentTimeMillis())
        publish()
    }

    /** Qualcuno (dalla Home di un altro dispositivo) chiede di fermare l'audio qui. */
    fun onPauseReq() { quietUntil = System.currentTimeMillis() + 5_000; r.scope.launch { pause() } }

    /** Ferma l'audio su un dispositivo (anche questo). */
    fun stopOn(id: String) = cmd(id, "pause")

    /** Comando al player di un dispositivo (anche questo): play, pause, next, prev, seek (ms), vol (0..100). */
    fun cmd(id: String, a: String, v: Long = 0L) {
        if (id == r.deviceId) runLocal(a, v)
        else {
            r.send(newMsg("media.cmd", "to" to id, "a" to a, "v" to v))
            // risposta ottimista: il player cambia subito, poi arriva lo stato vero
            remote[id]?.let { m -> remote[id] = when (a) { "pause" -> m.copy(playing = false); "play" -> m.copy(playing = true); "seek" -> m.copy(pos = v, at = System.currentTimeMillis()); "vol" -> m.copy(vol = v.toInt()); else -> m }; publish() }
        }
    }

    fun onCmd(m: JSONObject) = runLocal(m.optString("a"), m.optLong("v"))

    private fun runLocal(a: String, v: Long) {
        if (a == "pause") quietUntil = System.currentTimeMillis() + 5_000
        if (a == "vol") {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max / 100).toInt().coerceIn(0, max), 0)
        } else {
            val tc = current()?.transportControls
            val code = when (a) { "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE; "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; else -> 0 }
            if (tc != null) when (a) { "play" -> tc.play(); "pause" -> tc.pause(); "next" -> tc.skipToNext(); "prev" -> tc.skipToPrevious(); "seek" -> tc.seekTo(v) }
            else if (code != 0) try {
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            } catch (_: Exception) {}
        }
        r.scope.launch { delay(700); announce() }
    }

    fun start() {
        r.scope.launch { while (true) { delay(8_000); announce() } }
        am.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                onChange(am.isMusicActive)
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun onChange(active: Boolean) {
        if (active == playing) return
        playing = active
        announce()
        if (!active) return
        val now = System.currentTimeMillis()
        startedAt = now
        if (!r.prefs.autoPause || now < quietUntil) return
        // L'audio che arriva da Ponte stesso (PC -> telefono) non è un "nuovo contenuto".
        if (r.audio.activeSource.value != null) return
        val lock = r.headphones.lockedBy.value
        if (lock != null && lock != r.deviceId) return
        r.send(newMsg("media.take"))
    }

    fun onTake(m: JSONObject) {
        if (!r.prefs.autoPause) return
        val from = m.optString("from").ifEmpty { return }
        if (r.headphones.lockedBy.value == r.deviceId) return
        if (r.audio.activeSource.value != null) return
        if (!am.isMusicActive) return
        // Partiti insieme: resta acceso quello con l'id minore.
        if (System.currentTimeMillis() - startedAt < 2_000 && r.deviceId < from) return
        quietUntil = System.currentTimeMillis() + 5_000
        r.scope.launch { pause() }
    }

    /** Un altro dispositivo è diventato quello in uso: qui l'audio si ferma subito e un avviso lo dice. */
    fun yieldTo(from: String) {
        if (!r.prefs.autoPause || !am.isMusicActive) return
        if (r.headphones.lockedBy.value == r.deviceId || r.audio.activeSource.value != null) return
        quietUntil = System.currentTimeMillis() + 5_000
        r.scope.launch { pause() }
        Notify.say(ctx, "Ora usi " + r.deviceName(from), "Audio fermato su questo dispositivo", "Riprendi", onAction = { play() })
    }

    private fun play() {
        try {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
        } catch (_: Exception) {}
    }

    private suspend fun pause() {
        var done = false
        try {
            val mgr = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            for (s in mgr.getActiveSessions(ComponentName(ctx, NotifListener::class.java))) {
                if (s.playbackState?.state == PlaybackState.STATE_PLAYING) { s.transportControls.pause(); done = true }
            }
        } catch (_: SecurityException) {
            // Senza accesso alle notifiche si ricade sul tasto multimediale.
        } catch (_: Exception) {
        }
        if (!done) key()
        delay(800)
        if (am.isMusicActive) key()
    }

    private fun key() {
        try {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        } catch (_: Exception) {
        }
    }
}
