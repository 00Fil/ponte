package it.ponte

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Riserva via Wi-Fi Direct tra dispositivi Android, quando non c'è né server né rete locale.
 * Veloce quanto il Wi-Fi: passano anche file grandi e audio.
 *
 * Tutti i tuoi dispositivi ricavano dal token lo stesso nome di gruppo e la stessa password: uno crea il gruppo,
 * gli altri ci entrano senza richieste di conferma. Chi crea e chi entra si decide da solo: prima si prova a entrare,
 * se non c'è nessun gruppo lo si crea per un paio di minuti, poi si ricomincia con un'attesa a caso.
 * Gira solo a schermo acceso, per non consumare batteria.
 */
class P2pMesh(private val ctx: Context, private val r: Router) {
    private val mgr = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var ch: WifiP2pManager.Channel? = null
    @Volatile private var grouped = false

    private fun allowed(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    }

    private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun config(): WifiP2pConfig {
        val t = r.prefs.token
        return WifiP2pConfig.Builder()
            .setNetworkName("DIRECT-Pn-" + hash("p2p-name|$t").take(8))
            .setPassphrase(hash("p2p-pass|$t").take(20))
            .build()
    }

    fun start() {
        if (mgr == null || Build.VERSION.SDK_INT < 29) return
        ContextCompat.registerReceiver(ctx, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { onConnectionChanged() }
        }, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION), ContextCompat.RECEIVER_EXPORTED)
        r.scope.launch {
            while (true) {
                try {
                    if (r.prefs.token.isNotEmpty() && allowed() && r.isolated() && r.screenOn) cycle() else leave()
                } catch (_: Exception) {
                }
                delay(30_000)
            }
        }
    }

    private fun channel(): WifiP2pManager.Channel? {
        if (ch == null) ch = mgr?.initialize(ctx, Looper.getMainLooper(), null)
        return ch
    }

    @SuppressLint("MissingPermission")
    private suspend fun cycle() {
        if (grouped) return
        val c = channel() ?: return
        // 1) Si prova a entrare nel gruppo di un altro dispositivo.
        mgr!!.connect(c, config(), null)
        delay(15_000)
        if (grouped || !r.isolated()) return
        // 2) Nessun gruppo: lo si crea e si aspetta che gli altri entrino.
        mgr.cancelConnect(c, null)
        mgr.createGroup(c, config(), null)
        delay(120_000)
        if (!r.isolated()) return
        // 3) Nessuno è arrivato: si scioglie e si riprova più tardi, con un'attesa a caso per non scontrarsi.
        mgr.removeGroup(c, null)
        grouped = false
        delay(Random.nextLong(10_000, 60_000))
    }

    private fun leave() {
        if (!grouped) return
        val c = channel() ?: return
        mgr?.removeGroup(c, null)
        grouped = false
    }

    @SuppressLint("MissingPermission")
    private fun onConnectionChanged() {
        val c = channel() ?: return
        mgr?.requestConnectionInfo(c) { info ->
            grouped = info?.groupFormed == true
            // Chi è entrato si collega al capo gruppo; il capo gruppo accetta sulla porta di sempre.
            if (grouped && info?.isGroupOwner == false) info.groupOwnerAddress?.hostAddress?.let { r.dialHost(it) }
        }
    }
}
