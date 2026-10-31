package it.ponte

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Riserva via Bluetooth classico (RFCOMM), per quando non c'è né server né rete locale.
 * Funziona con i dispositivi già associati tra loro (telefono, tablet, PC), senza Wi-Fi né internet.
 * È lento: bastano appunti, notifiche, segnali delle cuffie e file piccoli; l'audio non passa di qui.
 * Android chiama, il PC risponde; tra due Android resta il collegamento aperto da chi ha l'id minore.
 */
class BtMesh(private val ctx: Context, private val r: Router) {
    private var server: BluetoothServerSocket? = null
    private val backoff = ConcurrentHashMap<String, Long>()
    private val dialing = ConcurrentHashMap.newKeySet<String>()

    private fun allowed() =
        Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun adapter() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun start() {
        r.scope.launch {
            while (true) {
                try {
                    if (r.prefs.token.isNotEmpty() && allowed()) {
                        if (server == null) listen()
                        if (r.isolated()) dialAll()
                    }
                } catch (_: Exception) {
                }
                delay(if (r.isolated() && r.screenOn) 30_000L else 120_000L)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun listen() {
        val ad = adapter() ?: return
        if (!ad.isEnabled) return
        val ss = try { ad.listenUsingRfcommWithServiceRecord("Ponte", SERVICE) } catch (_: Exception) { return }
        server = ss
        r.scope.launch {
            try {
                while (true) {
                    val sock = ss.accept()
                    r.scope.launch {
                        StreamLink(r, sock.inputStream, sock.outputStream, { sock.close() }, "bt").run()
                    }
                }
            } catch (_: Exception) {
            } finally {
                server = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun dialAll() {
        val ad = adapter() ?: return
        if (!ad.isEnabled) return
        val now = System.currentTimeMillis()
        val candidates = ad.bondedDevices.filter {
            val major = it.bluetoothClass?.majorDeviceClass
            major == BluetoothClass.Device.Major.COMPUTER || major == BluetoothClass.Device.Major.PHONE
        }
        for (d in candidates) {
            val addr = d.address
            if ((backoff[addr] ?: 0L) > now || !dialing.add(addr)) continue
            r.scope.launch {
                val started = System.currentTimeMillis()
                try {
                    try { ad.cancelDiscovery() } catch (_: SecurityException) {}
                    val sock = d.createRfcommSocketToServiceRecord(SERVICE)
                    sock.connect()
                    StreamLink(r, sock.inputStream, sock.outputStream, { sock.close() }, "bt", initiator = true).run()
                } catch (_: Exception) {
                } finally {
                    dialing.remove(addr)
                    // Durato poco (non c'è Ponte, è spento o tocca all'altro chiamare): si riprova più tardi.
                    if (System.currentTimeMillis() - started < 10_000) backoff[addr] = System.currentTimeMillis() + 10 * 60_000L
                }
            }
        }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("7f2a0c1e-6b1d-4b8e-9a51-50e7e0a1c0de")
    }
}
