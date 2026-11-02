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
class MediaSwitch(private val ctx: Context, private val r: Router) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile private var playing = false
    @Volatile private var startedAt = 0L
    /** Dopo una pausa chiesta da un altro dispositivo non annunciamo nulla per qualche secondo: evita rimbalzi. */
    @Volatile private var quietUntil = 0L

    fun start() {
        am.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                onChange(am.isMusicActive)
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun onChange(active: Boolean) {
        if (active == playing) return
        playing = active
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
