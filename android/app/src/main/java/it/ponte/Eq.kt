package it.ponte

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer

/** Equalizzatore di sistema: agisce sulla sessione audio globale (0), quindi su tutto ciò che il telefono riproduce. */
object Eq {
    private class Fx(val eq: Equalizer?, val bass: BassBoost?, val loud: LoudnessEnhancer?) {
        fun release() { try { eq?.release() } catch (_: Throwable) {}; try { bass?.release() } catch (_: Throwable) {}; try { loud?.release() } catch (_: Throwable) {} }
    }
    /** Sessione audio -> effetti. La 0 è quella globale; le altre arrivano dalle app che annunciano la propria sessione. */
    private val fx = java.util.concurrent.ConcurrentHashMap<Int, Fx>()
    private var eq: Equalizer? = null
    private var sp: android.content.SharedPreferences? = null

    var bands = 0; private set
    var minDb = -15; private set
    var maxDb = 15; private set
    var freqs: List<Int> = emptyList(); private set
    var available = false; private set

    val presets = listOf("Piatto" to listOf(0, 0, 0, 0, 0), "Bassi" to listOf(6, 4, 0, -1, -1), "Voce" to listOf(-2, 0, 4, 4, 1), "Acuti" to listOf(-2, -1, 0, 4, 6), "Loudness" to listOf(5, 2, -1, 2, 5))

    var enabled: Boolean
        get() = sp?.getBoolean("on", false) ?: false
        set(v) { sp?.edit()?.putBoolean("on", v)?.apply(); apply() }
    var bassLevel: Int
        get() = sp?.getInt("bass", 0) ?: 0
        set(v) { sp?.edit()?.putInt("bass", v)?.apply(); apply() }
    var gain: Int
        get() = sp?.getInt("gain", 0) ?: 0
        set(v) { sp?.edit()?.putInt("gain", v)?.apply(); apply() }

    /** Livello della banda in dB. */
    fun level(i: Int): Int = sp?.getInt("b$i", 0) ?: 0
    fun setLevel(i: Int, db: Int) { sp?.edit()?.putInt("b$i", db)?.apply(); apply() }

    fun preset(levels: List<Int>) {
        val e = sp?.edit() ?: return
        for (i in 0 until bands) {
            // 5 punti del preset distribuiti sulle bande disponibili
            val pos = if (bands <= 1) 0f else i * (levels.size - 1).toFloat() / (bands - 1)
            val lo = pos.toInt(); val hi = minOf(lo + 1, levels.size - 1)
            e.putInt("b$i", (levels[lo] + (levels[hi] - levels[lo]) * (pos - lo)).toInt().coerceIn(minDb, maxDb))
        }
        e.apply(); apply()
    }

    private fun make(sid: Int): Fx {
        val e = try { Equalizer(1000, sid) } catch (_: Throwable) { null }
        val b = try { BassBoost(1000, sid) } catch (_: Throwable) { null }
        val l = try { LoudnessEnhancer(sid) } catch (_: Throwable) { null }
        return Fx(e, b, l)
    }

    fun start(ctx: Context) {
        if (sp == null) sp = ctx.applicationContext.getSharedPreferences("eq", Context.MODE_PRIVATE)
        if (fx[0] == null) {
            val g = make(0)
            fx[0] = g
            g.eq?.let { e ->
                eq = e; bands = e.numberOfBands.toInt()
                minDb = e.bandLevelRange[0] / 100; maxDb = e.bandLevelRange[1] / 100
                freqs = (0 until bands).map { e.getCenterFreq(it.toShort()) / 1000 }
                available = true
            }
            // Dove l'effetto globale è ignorato dal telefono, ci si aggancia a ogni sessione che le app annunciano (come fanno gli equalizzatori di sistema).
            try {
                val f = android.content.IntentFilter().apply {
                    addAction(android.media.audiofx.AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                    addAction(android.media.audiofx.AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                }
                androidx.core.content.ContextCompat.registerReceiver(ctx.applicationContext, object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: Context, i: android.content.Intent) {
                        val sid = i.getIntExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, -1)
                        if (sid <= 0) return
                        if (i.action == android.media.audiofx.AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) {
                            if (fx[sid] == null) {
                                val x = make(sid); fx[sid] = x
                                if (!available) x.eq?.let { e ->
                                    eq = e; bands = e.numberOfBands.toInt()
                                    minDb = e.bandLevelRange[0] / 100; maxDb = e.bandLevelRange[1] / 100
                                    freqs = (0 until bands).map { e.getCenterFreq(it.toShort()) / 1000 }
                                    available = true
                                }
                                apply()
                            }
                        } else fx.remove(sid)?.release()
                    }
                }, f, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
            } catch (_: Throwable) {}
            watchPlayers(ctx.applicationContext)
            watchdog()
        }
        apply()
    }

    private fun attach(sid: Int) {
        if (sid <= 0 || fx[sid] != null) return
        val x = make(sid); fx[sid] = x
        if (!available) x.eq?.let { e ->
            eq = e; bands = e.numberOfBands.toInt()
            minDb = e.bandLevelRange[0] / 100; maxDb = e.bandLevelRange[1] / 100
            freqs = (0 until bands).map { e.getCenterFreq(it.toShort()) / 1000 }
            available = true
        }
        apply()
    }

    /** Anche le app che non annunciano la propria sessione: si legge la sessione di ogni lettore attivo. */
    private fun watchPlayers(ctx: Context) {
        try {
            val am = ctx.getSystemService(android.media.AudioManager::class.java) ?: return
            val m = android.media.AudioPlaybackConfiguration::class.java.getDeclaredMethod("getSessionId").apply { isAccessible = true }
            fun scan(list: List<android.media.AudioPlaybackConfiguration>) {
                list.forEach { c -> try { attach(m.invoke(c) as Int) } catch (_: Throwable) {} }
            }
            scan(am.activePlaybackConfigurations)
            am.registerAudioPlaybackCallback(object : android.media.AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>) = scan(configs)
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (_: Throwable) {}
    }

    /** Se un'altra app si prende gli effetti, li si ricrea e si riapplicano: l'equalizzatore resta sempre attivo. */
    private fun watchdog() {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        h.postDelayed(object : Runnable {
            override fun run() {
                if (enabled) fx.entries.toList().forEach { (sid, x) ->
                    val lost = try { x.eq?.hasControl() == false } catch (_: Throwable) { true }
                    if (lost) { x.release(); fx[sid] = make(sid) }
                }
                apply()
                h.postDelayed(this, 5000)
            }
        }, 5000)
    }

    private fun apply() {
        val on = enabled
        fx.values.forEach { x ->
            try {
                x.eq?.let { e ->
                    for (i in 0 until minOf(bands, e.numberOfBands.toInt())) e.setBandLevel(i.toShort(), (level(i).coerceIn(minDb, maxDb) * 100).toShort())
                    e.enabled = on
                }
            } catch (_: Throwable) {}
            try { x.bass?.let { if (it.strengthSupported) it.setStrength((bassLevel * 10).toShort()); it.enabled = on && bassLevel > 0 } } catch (_: Throwable) {}
            try { x.loud?.let { it.setTargetGain(gain * 100); it.enabled = on && gain > 0 } } catch (_: Throwable) {}
        }
    }
}
