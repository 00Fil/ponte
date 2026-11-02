package it.ponte

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer

/** Equalizzatore di sistema: agisce sulla sessione audio globale (0), quindi su tutto ciò che il telefono riproduce. */
object Eq {
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var loud: LoudnessEnhancer? = null
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

    fun start(ctx: Context) {
        if (sp == null) sp = ctx.applicationContext.getSharedPreferences("eq", Context.MODE_PRIVATE)
        if (eq == null) {
            try {
                val e = Equalizer(1000, 0)
                eq = e; bands = e.numberOfBands.toInt()
                minDb = e.bandLevelRange[0] / 100; maxDb = e.bandLevelRange[1] / 100
                freqs = (0 until bands).map { e.getCenterFreq(it.toShort()) / 1000 }
                available = true
            } catch (_: Throwable) { available = false }
            try { bass = BassBoost(1000, 0) } catch (_: Throwable) {}
            try { loud = LoudnessEnhancer(0) } catch (_: Throwable) {}
        }
        apply()
    }

    private fun apply() {
        val on = enabled
        try {
            eq?.let { e ->
                for (i in 0 until bands) e.setBandLevel(i.toShort(), (level(i).coerceIn(minDb, maxDb) * 100).toShort())
                e.enabled = on
            }
        } catch (_: Throwable) {}
        try { bass?.let { if (it.strengthSupported) it.setStrength((bassLevel * 10).toShort()); it.enabled = on && bassLevel > 0 } } catch (_: Throwable) {}
        try { loud?.let { it.setTargetGain(gain * 100); it.enabled = on && gain > 0 } } catch (_: Throwable) {}
    }
}
