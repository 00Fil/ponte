package it.ponte

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Accesso root: serve solo per collegare e scollegare le cuffie, cosa che Android non permette alle app normali. */
object Root {
    @Volatile private var known: Boolean? = null
    @Volatile private var failedAt = 0L

    /** Ultimo messaggio di errore del root o del Bluetooth, da mostrare in Audio per capire cosa non va. */
    val lastError = kotlinx.coroutines.flow.MutableStateFlow("")

    /**
     * Si ricorda solo il sì: un no può essere temporaneo (la richiesta di Magisk non ancora confermata, root che parte tardi),
     * quindi si ricontrolla dopo 15 secondi o subito se [force].
     */
    suspend fun available(force: Boolean = false): Boolean {
        if (known == true) return true
        if (!force && System.currentTimeMillis() - failedAt < 15_000) return false
        return withContext(Dispatchers.IO) {
            var why = ""
            val ok = try {
                val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                val done = p.waitFor(20, TimeUnit.SECONDS)
                if (!done) { p.destroy(); why = "su non ha risposto in 20 s: conferma la richiesta di root (Magisk/KernelSU) quando compare" }
                else if (p.exitValue() != 0 || !out.contains("uid=0")) why = "su ha risposto: " + out.trim().take(160)
                done && p.exitValue() == 0 && out.contains("uid=0")
            } catch (e: Exception) {
                why = "su non trovato: " + (e.message ?: e.javaClass.simpleName)
                false
            }
            if (ok) { known = true; lastError.value = "" } else { known = null; failedAt = System.currentTimeMillis(); lastError.value = why }
            ok
        }
    }

    /** Esegue RootBt con i privilegi di sistema. Restituisce true se Android ha accettato la richiesta. */
    suspend fun bluetooth(ctx: Context, action: String, address: String): Boolean = withContext(Dispatchers.IO) {
        if (!available()) return@withContext false
        if (!Regex("^[0-9A-F:]{17}$").matches(address) || action !in setOf("connect", "disconnect")) return@withContext false
        val apk = ctx.applicationInfo.sourceDir
        try {
            val p = ProcessBuilder("su", "-c", "CLASSPATH=$apk /system/bin/app_process /system/bin it.ponte.RootBt $action $address")
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            val done = p.waitFor(20, TimeUnit.SECONDS)
            if (!done) p.destroy()
            val good = done && p.exitValue() == 0 && out.contains("ok")
            lastError.value = if (good) "" else (out.trim().takeLast(300).ifEmpty { "nessuna risposta dal comando (uscita ${if (done) p.exitValue() else "timeout"})" })
            good
        } catch (e: Exception) {
            lastError.value = "avvio del comando: " + (e.message ?: e.javaClass.simpleName)
            false
        }
    }
}

/**
 * Gira in un processo a parte, lanciato con su: diventa l'utente di sistema (uid 1000), che ha il permesso
 * BLUETOOTH_PRIVILEGED, e chiama le API nascoste di Android per collegare o scollegare le cuffie.
 * Uso: app_process /system/bin it.ponte.RootBt connect|disconnect AA:BB:CC:DD:EE:FF
 */
object RootBt {
    @SuppressLint("MissingPermission", "PrivateApi", "DiscouragedPrivateApi")
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) exitProcess(64)
        val action = args[0]
        val address = args[1]
        try {
            val proc = Class.forName("android.os.Process")
            proc.getMethod("setGid", Int::class.javaPrimitiveType).invoke(null, 1000)
            proc.getMethod("setUid", Int::class.javaPrimitiveType).invoke(null, 1000)
        } catch (t: Throwable) {
            System.err.println("setuid: $t")
        }
        Looper.prepareMainLooper()
        val ctx: Context = try {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("systemMain").invoke(null)
            at.getMethod("getSystemContext").invoke(thread) as Context
        } catch (t: Throwable) {
            System.err.println("context: $t"); exitProcess(3)
        }
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: exitProcess(4)
        val dev = adapter.getRemoteDevice(address)

        // 1) API di sistema di BluetoothDevice (Android 13+): collega o scollega tutti i profili audio.
        val direct = try {
            val r = dev.javaClass.getMethod(action).invoke(dev)
            r == 0 || r == true
        } catch (t: Throwable) {
            System.err.println("device.$action: $t"); false
        }
        if (direct) { println("ok"); exitProcess(0) }

        // 2) Ripiego sui singoli profili (A2DP e vivavoce).
        var pending = 2
        var ok = false
        for (profile in intArrayOf(android.bluetooth.BluetoothProfile.A2DP, android.bluetooth.BluetoothProfile.HEADSET)) {
            adapter.getProfileProxy(ctx, object : android.bluetooth.BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: android.bluetooth.BluetoothProfile) {
                    try {
                        if (action == "connect") call(proxy, "setConnectionPolicy", dev, 100)
                        ok = call(proxy, action, dev) || ok
                    } catch (t: Throwable) {
                        System.err.println("profilo $p: $t")
                    }
                    if (--pending == 0) { println(if (ok) "ok" else "errore"); exitProcess(if (ok) 0 else 1) }
                }
                override fun onServiceDisconnected(p: Int) {}
            }, profile)
        }
        Handler(Looper.getMainLooper()).postDelayed({ println("timeout"); exitProcess(2) }, 8000)
        Looper.loop()
    }

    private fun call(o: Any, name: String, vararg a: Any): Boolean {
        val m = o.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == a.size } ?: return false
        m.isAccessible = true
        val r = m.invoke(o, *a)
        return r == null || r == true || r == 0
    }
}
