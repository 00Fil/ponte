package it.ponte

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Cuffie con due soli slot Bluetooth: Ponte tiene collegato al massimo un dispositivo alla volta.
 * Quando qui parte l'audio avvisa gli altri ("bt.claim") e loro scollegano le cuffie;
 * dopo un po' di inattività le scollega anche da qui, così gli slot restano liberi.
 */
class Headphones(private val ctx: Context, private val r: Router) {
    data class Hp(val address: String, val name: String, val connected: Boolean)

    val list = MutableStateFlow<List<Hp>>(emptyList())

    /** Vero se Android ha rifiutato di scollegare le cuffie (serve un permesso riservato al sistema). */
    val cannotDisconnect = MutableStateFlow(false)

    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var a2dp: BluetoothA2dp? = null
    private val since = HashMap<String, Long>()
    private var lastActive = 0L
    private var wasActive = false

    fun hasPermission() =
        Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        r.scope.launch {
            while (true) {
                try { tick() } catch (_: Exception) {}
                delay(5000)
            }
        }
    }

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
        refresh()
        val marked = r.prefs.headphones
        val mine = list.value.filter { it.connected && it.address in marked }
        val now = System.currentTimeMillis()
        since.keys.retainAll(mine.map { it.address }.toSet())
        if (mine.isEmpty()) { wasActive = false; return }
        mine.forEach { since.getOrPut(it.address) { now } }

        val active = am.isMusicActive
        if (active) {
            lastActive = now
            // Appena qui parte l'audio, gli altri dispositivi lasciano le cuffie.
            if (!wasActive) mine.forEach { claim(it.address) }
        }
        wasActive = active

        val idle = r.prefs.idleMinutes
        if (idle > 0 && !active) {
            val ref = maxOf(lastActive, since.values.minOrNull() ?: now)
            if (now - ref > idle * 60_000L) mine.forEach { release(it.address) }
        }
    }

    fun claim(address: String) {
        r.send(newMsg("bt.claim", "hp" to address))
    }

    fun onClaim(m: JSONObject) {
        val addr = m.optString("hp")
        if (addr.isEmpty() || addr !in r.prefs.headphones) return
        if (list.value.any { it.address == addr && it.connected }) release(addr)
    }

    /** Scollega le cuffie da qui. Android non offre un'API pubblica: si prova quella nascosta e, se rifiuta, lo si segnala. */
    @SuppressLint("MissingPermission", "DiscouragedPrivateApi", "PrivateApi")
    fun release(address: String) {
        val proxy = a2dp ?: return
        val dev: BluetoothDevice = adapter()?.getRemoteDevice(address) ?: return
        val ok = try {
            proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java).invoke(proxy, dev)
            true
        } catch (_: Throwable) {
            false
        }
        cannotDisconnect.value = !ok
        refresh()
    }
}
