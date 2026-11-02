package it.ponte

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Passaggio automatico delle cuffie, alla Apple.
 *
 * - Il dispositivo "in uso" è l'ultimo su cui hai toccato lo schermo, mosso il mouse o premuto un tasto.
 * - Quando su quel dispositivo parte l'audio, le cuffie preferite passano lì: gli altri le lasciano.
 * - Se le cuffie non arrivano (spente, lontane, bloccate altrove) vale la riserva: casse o muto.
 * - "Blocca qui" ferma tutto: le cuffie restano dove sono finché non sblocchi.
 * - Le cuffie hanno 2 posti: due dispositivi (telefono e PC) le tengono collegate insieme e il passaggio è immediato, senza
 *   ricollegare niente. Solo un terzo dispositivo che le chiede libera un posto, e a lasciarlo è quello usato meno di recente
 *   (mai quello bloccato, per ultimo quello che sta suonando). Dopo un po' di silenzio le cuffie vengono lasciate libere.
 * - Il dispositivo in uso si decide dall'ultimo input di ciascuno (tocco, mouse, tastiera), non dall'ordine dei messaggi.
 */
class Headphones(private val ctx: Context, private val r: Router) {
    data class Hp(val address: String, val name: String, val connected: Boolean)

    val list = MutableStateFlow<List<Hp>>(emptyList())
    val cannotDisconnect = MutableStateFlow(false)
    val rooted = MutableStateFlow<Boolean?>(null)
    /** Chi tiene le cuffie, chi è in uso, chi ha bloccato: id dei dispositivi (questo compreso). */
    val holder = MutableStateFlow<String?>(null)
    val focus = MutableStateFlow<String?>(null)
    val lockedBy = MutableStateFlow<String?>(null)
    /** Sto portando le cuffie qui: la Home mostra l'attesa finché non sono collegate. */
    val switching = MutableStateFlow(false)

    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private var a2dp: BluetoothA2dp? = null
    private val since = HashMap<String, Long>()
    private var connectedBefore = emptySet<String>()
    private var connectedNow = emptySet<String>()
    private var lastActive = 0L
    private var playing = false
    private var lastInput = 0L
    private var lastFocusSent = 0L
    private var lastAnnounce = 0L
    /** Ultimo input di ogni dispositivo, sull'orologio di questo (per gli altri: arrivo del messaggio meno "ago"). */
    private val lastInputAt = HashMap<String, Long>()
    private data class HpState(val on: Boolean, val play: Boolean, val use: Long, val seen: Long)
    /** Cuffie -> dispositivo -> stato, così ognuno sa chi occupa i 2 posti. */
    private val holders = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, HpState>>()
    private var mutedByUs = false
    private var watcher: View? = null
    private var ensureJob: Job? = null
    private var preJob: Job? = null
    @Volatile private var wanting: String? = null
    @Volatile private var nudge = false

    private val me get() = r.deviceId
    private val SLOTS = 2

    fun hasPermission() =
        Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun start() {
        r.scope.launch { rooted.value = Root.available() }
        am.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                r.scope.launch { onPlayback(am.isMusicActive) }
            }
        }, main)
        ContextCompat.registerReceiver(ctx, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { r.scope.launch { tick() } }
        }, IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        main.post { addTouchWatcher() }
        r.scope.launch {
            while (true) {
                try { tick() } catch (_: Exception) {}
                // Senza cuffie segnate non c'è nulla da controllare spesso.
                delay(if (r.prefs.headphones.isEmpty()) 60_000L else if (r.screenOn) 10_000L else 30_000L)
            }
        }
    }

    // ------------------------------------------------------------ dispositivo in uso

    /**
     * Una finestra da 1 pixel che non intercetta nulla ma riceve un avviso a ogni tocco sullo schermo
     * (FLAG_WATCH_OUTSIDE_TOUCH): così Ponte sa che stai usando questo dispositivo, senza leggere cosa tocchi.
     */
    private fun addTouchWatcher() {
        if (watcher != null || !Settings.canDrawOverlays(ctx)) return
        val v = object : View(ctx) {
            override fun onTouchEvent(e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) onUserInput()
                return false
            }
        }
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp)
            watcher = v
        } catch (_: Exception) {
        }
    }

    /** Chiamato a ogni tocco e quando accendi o sblocchi lo schermo. */
    fun onUserInput() {
        val now = System.currentTimeMillis()
        val wasIdle = now - lastInput > 30_000
        val was = focus.value
        lastInput = now
        lastInputAt[me] = now
        focus.value = computeFocus()
        if (was != me || (wasIdle && now - lastFocusSent > 15_000)) {
            lastFocusSent = now
            r.send(newMsg("focus", "ago" to 0))
        }
        if (was != me && focus.value == me) schedulePreConnect()
    }

    fun onFocus(m: JSONObject) {
        val from = m.optString("from").ifEmpty { return }
        lastInputAt[from] = System.currentTimeMillis() - m.optLong("ago", 0)
        focus.value = computeFocus()
    }

    /** Il dispositivo in uso è quello con l'input più recente; se nessuno ha toccato nulla da 10 minuti, nessuno. */
    private fun computeFocus(): String? {
        val now = System.currentTimeMillis()
        return lastInputAt.filterValues { now - it < 10 * 60_000L }.maxByOrNull { it.value }?.key
    }

    // ------------------------------------------------------------ blocco

    fun toggleLock() {
        val by = if (lockedBy.value == me) null else me
        lockedBy.value = by
        r.send(newMsg("audio.lock", "by" to (by ?: "")))
    }

    fun onLock(m: JSONObject) {
        lockedBy.value = m.optString("by").ifEmpty { null }
    }

    // ------------------------------------------------------------ audio che parte

    private fun onPlayback(active: Boolean) {
        val now = System.currentTimeMillis()
        if (active) lastActive = now
        if (active && !playing) {
            playing = true
            onAudioStart()
        } else if (!active) {
            playing = false
        }
    }

    private fun prio() = when {
        lockedBy.value == me -> 2
        focus.value == me -> 1
        else -> 0
    }

    private fun mayTake(): Boolean {
        val lock = lockedBy.value
        return lock == me || (lock == null && (focus.value == me || focus.value == null))
    }

    private fun onAudioStart() {
        val hp = r.prefs.preferredHp.ifEmpty { return }
        val here = isConnected(hp)
        if (!mayTake()) {
            if (!here) fallback()
            return
        }
        claim(hp)
        if (here) return
        ensureHere(hp, 6)
    }

    /**
     * Porta le cuffie qui e insiste finché non sono collegate: se i due posti sono pieni il primo tentativo viene rifiutato,
     * quindi si riprova a intervalli brevi e subito appena chi le deve lasciare avvisa di averlo fatto (vedi [onState]).
     */
    private fun ensureHere(hp: String, tries: Int) {
        if (isConnected(hp)) { switching.value = false; return }
        ensureJob?.cancel()
        wanting = hp
        switching.value = true
        ensureJob = r.scope.launch {
            val waits = longArrayOf(0, 700, 1200, 2000, 3000, 4000)
            try {
                for (i in 0 until minOf(tries, waits.size)) {
                    val end = System.currentTimeMillis() + waits[i]
                    while (System.currentTimeMillis() < end && !nudge) delay(80)
                    nudge = false
                    if (wanting != hp) return@launch
                    val lock = lockedBy.value
                    if (lock != null && lock != me) return@launch
                    refresh()
                    if (isConnected(hp)) return@launch
                    connect(hp)
                    if (isConnected(hp)) return@launch
                }
                if (playing && !isConnected(hp)) fallback()
            } finally {
                if (wanting == hp) wanting = null
                switching.value = false
            }
        }
    }

    /** Appena uso questo dispositivo le cuffie arrivano, prima ancora che parta l'audio. */
    private fun schedulePreConnect() {
        val hp = r.prefs.preferredHp.ifEmpty { return }
        preJob?.cancel()
        preJob = r.scope.launch {
            delay(1_200)
            val lock = lockedBy.value
            if (focus.value != me || (lock != null && lock != me)) return@launch
            if (isConnected(hp)) return@launch
            val busy = live(hp).values.any { it.on }
            claim(hp)
            ensureHere(hp, if (busy) 6 else 1)
        }
    }

    private fun fallback() {
        if (r.prefs.fallbackMute) mute()
    }

    private fun mute() {
        if (mutedByUs) return
        try { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0); mutedByUs = true } catch (_: Exception) {}
    }

    private fun unmute() {
        if (!mutedByUs) return
        try { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
        mutedByUs = false
    }

    // ------------------------------------------------------------ Bluetooth

    @SuppressLint("MissingPermission")
    private fun adapter() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @SuppressLint("MissingPermission")
    private fun ensureProxy() {
        if (a2dp != null) return
        adapter()?.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { a2dp = proxy as? BluetoothA2dp }
            override fun onServiceDisconnected(profile: Int) { a2dp = null }
        }, BluetoothProfile.A2DP)
    }

    private fun isConnected(address: String) = list.value.any { it.address == address && it.connected }

    @SuppressLint("MissingPermission")
    private fun refresh() {
        if (!hasPermission()) return
        val ad = adapter() ?: return
        if (!ad.isEnabled) { list.value = emptyList(); return }
        ensureProxy()
        val connected = a2dp?.connectedDevices?.map { it.address }?.toSet() ?: emptySet()
        list.value = ad.bondedDevices
            .filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO }
            .map { Hp(it.address, it.name ?: it.address, it.address in connected) }
            .sortedBy { it.name.lowercase() }
    }

    private fun tick() {
        main.post { addTouchWatcher() }
        refresh()
        val marked = r.prefs.headphones + listOfNotNull(r.prefs.preferredHp.ifEmpty { null })
        val mine = list.value.filter { it.connected && it.address in marked }.map { it.address }.toSet()
        val now = System.currentTimeMillis()

        // Cuffie appena collegate qui (anche a mano): se tocca a noi le teniamo e avvisiamo gli altri, se no le lasciamo.
        for (a in mine - connectedBefore) {
            unmute()
            val lock = lockedBy.value
            // Collegate dalle cuffie stesse o a mano: si tengono se c'è un posto libero o se qui è in uso; se tocca un altro, le lascio.
            if (lock == me || (lock == null && (focus.value == me || freeSlot(a)))) claim(a)
            else r.scope.launch { release(a) }
        }
        // Cuffie appena andate via mentre l'audio suona: riserva.
        if ((connectedBefore - mine).isNotEmpty() && playing) fallback()
        connectedBefore = mine

        since.keys.retainAll(mine)
        // Annuncio lo stato delle cuffie quando cambia e comunque ogni 45 secondi, così gli altri sanno chi occupa i posti.
        val changed = mine != connectedNow
        connectedNow = mine
        if (changed || now - lastAnnounce > 45_000) {
            lastAnnounce = now
            val all = (marked + holders.keys).toSet()
            for (a in all) announce(a, a in mine)
        }
        if (mine.isEmpty()) return
        mine.forEach { since.getOrPut(it) { now } }

        val idle = r.prefs.idleMinutes
        if (idle > 0 && !playing && lockedBy.value != me) {
            val ref = maxOf(lastActive, since.values.minOrNull() ?: now)
            if (now - ref > idle * 60_000L) mine.forEach { r.scope.launch { release(it) } }
        }
    }

    private fun live(hp: String): Map<String, HpState> {
        val now = System.currentTimeMillis()
        val m = holders[hp] ?: return emptyMap()
        m.entries.removeAll { now - it.value.seen > 150_000 || !it.value.on }
        return m
    }

    /** C'è un posto libero sulle cuffie, senza contare me? */
    private fun freeSlot(hp: String) = live(hp).count { it.key != me } < SLOTS

    private fun announce(hp: String, on: Boolean) {
        val now = System.currentTimeMillis()
        val use = maxOf(lastActive, lastInput)
        holders.getOrPut(hp) { java.util.concurrent.ConcurrentHashMap() }[me] = HpState(on, playing, use, now)
        r.send(newMsg("bt.state", "hp" to hp, "on" to on, "play" to playing, "ago" to if (use == 0L) 3_600_000L else now - use))
    }

    fun onState(m: JSONObject) {
        val hp = m.optString("hp")
        val from = m.optString("from")
        if (hp.isEmpty() || from.isEmpty()) return
        val now = System.currentTimeMillis()
        holders.getOrPut(hp) { java.util.concurrent.ConcurrentHashMap() }[from] = HpState(m.optBoolean("on"), m.optBoolean("play"), now - m.optLong("ago"), now)
        if (!m.optBoolean("on") && wanting == hp) nudge = true
    }

    fun claim(address: String) {
        val now = System.currentTimeMillis()
        holders.getOrPut(address) { java.util.concurrent.ConcurrentHashMap() }[me] = HpState(true, playing, now, now)
        holder.value = me
        r.send(newMsg("bt.claim", "hp" to address, "prio" to prio()))
    }

    /**
     * Qualcuno chiede le cuffie. Con un posto libero nessuno fa niente (restano collegati entrambi). Se i posti sono pieni
     * lascia le cuffie chi vale meno: non bloccato, non in riproduzione, usato meno di recente. Tutti applicano la stessa regola
     * sugli stessi dati, quindi se ne va solo il dispositivo giusto.
     */
    fun onClaim(m: JSONObject) {
        val addr = m.optString("hp")
        val from = m.optString("from")
        if (addr.isEmpty()) return
        holder.value = from
        val now = System.currentTimeMillis()
        holders.getOrPut(addr) { java.util.concurrent.ConcurrentHashMap() }[from] = HpState(true, false, now, now)
        if (!isConnected(addr)) return
        holders.getOrPut(addr) { java.util.concurrent.ConcurrentHashMap() }[me] = HpState(true, playing, maxOf(lastActive, lastInput), now)
        val others = live(addr).filter { it.key != from }
        val count = others.size - (SLOTS - 1)
        if (count <= 0) return
        val victims = others.entries.sortedWith(
            compareBy<Map.Entry<String, HpState>>({ if (lockedBy.value == it.key) 1 else 0 }, { if (it.value.play) 1 else 0 }, { it.value.use }, { it.key }),
        ).take(count).map { it.key }
        if (me in victims) r.scope.launch {
            release(addr)
            if (playing) fallback()
        }
    }

    /** Collega le cuffie qui (con il root) e avvisa gli altri. */
    suspend fun connect(address: String) {
        val ok = Root.bluetooth(ctx, "connect", address)
        if (!ok) cannotDisconnect.value = true
        delay(1_500)
        refresh()
    }

    /** Scollega le cuffie da qui (con il root). */
    suspend fun release(address: String) {
        val ok = Root.bluetooth(ctx, "disconnect", address)
        cannotDisconnect.value = !ok
        announce(address, false)
        delay(1_000)
        refresh()
    }

    /** Ricontrolla il root subito (dopo aver confermato la richiesta di Magisk/KernelSU). */
    fun retryRoot() { r.scope.launch { rooted.value = Root.available(force = true) } }

    fun connectHere(address: String) {
        claim(address)
        ensureHere(address, 6)
    }
}
