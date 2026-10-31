package it.ponte

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Aggiornamenti automatici: il server di Ponte legge l'ultima release su GitHub e la gira qui.
 * Si controlla all'avvio e ogni 6 ore; se c'è una build più nuova si scarica e si installa da sola
 * (con il root senza chiedere nulla; senza root Android chiede conferma la prima volta, poi non più).
 */
class Updater(private val ctx: Context, private val r: Router) {
    val status = MutableStateFlow("")
    val available = MutableStateFlow<Int?>(null)
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.MINUTES).build()
    @Volatile private var busy = false

    val current: Int get() = BuildConfig.VERSION_CODE

    fun start() {
        r.scope.launch {
            delay(30_000)
            while (true) {
                check(install = true)
                delay(6 * 3600_000L)
            }
        }
    }

    suspend fun check(install: Boolean) {
        if (busy) return
        val base = r.prefs.httpBase() ?: run { status.value = "Serve il server per gli aggiornamenti"; return }
        busy = true
        try {
            status.value = "Controllo in corso"
            val info = http.newCall(get("$base/api/update")).execute().use {
                if (!it.isSuccessful) { status.value = "Server senza aggiornamenti (${it.code})"; return }
                JSONObject(it.body!!.string())
            }
            val build = info.optInt("build")
            if (build <= current || !info.optBoolean("android")) {
                available.value = null
                status.value = "Hai l'ultima versione"
                return
            }
            available.value = build
            if (!install) { status.value = "Disponibile la versione 1.0.$build"; return }
            status.value = "Scarico la versione 1.0.$build"
            val apk = File(ctx.cacheDir, "ponte-update.apk")
            http.newCall(get("$base/api/update/android")).execute().use { resp ->
                if (!resp.isSuccessful) { status.value = "Download non riuscito"; return }
                resp.body!!.byteStream().use { i -> apk.outputStream().use { i.copyTo(it) } }
            }
            status.value = "Installo la versione 1.0.$build"
            if (Root.available() && rootInstall(apk)) return
            sessionInstall(apk)
        } catch (_: Exception) {
            status.value = "Aggiornamento non riuscito, riprovo più tardi"
        } finally {
            busy = false
        }
    }

    private fun get(url: String) = Request.Builder().url(url).header("Authorization", "Bearer ${r.prefs.token}").build()

    private fun rootInstall(apk: File): Boolean = try {
        val p = ProcessBuilder("su", "-c", "pm install -r \"${apk.absolutePath}\"").redirectErrorStream(true).start()
        p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (_: Exception) {
        false
    }

    /** Installazione con PackageInstaller: da Android 12 gli aggiornamenti di sé stessa non chiedono conferma. */
    private fun sessionInstall(apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("ponte.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val intent = Intent(ctx, InstallReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }
}

/** Esito dell'installazione. Se Android vuole una conferma, la si chiede con una notifica. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val r = Ponte.router
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { c.startActivity(confirm) } catch (_: Exception) {}
                val nm = c.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel("aggiornamenti", "Aggiornamenti", NotificationManager.IMPORTANCE_DEFAULT))
                val pi = PendingIntent.getActivity(c, 7, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                nm.notify(7, Notification.Builder(c, "aggiornamenti")
                    .setSmallIcon(R.drawable.ic_ponte)
                    .setContentTitle("Aggiornamento di Ponte pronto")
                    .setContentText("Tocca per installarlo")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build())
                r?.updater?.status?.value = "In attesa della tua conferma"
            }
            PackageInstaller.STATUS_SUCCESS -> r?.updater?.status?.value = "Aggiornato"
            else -> r?.updater?.status?.value = "Installazione non riuscita: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "")
        }
    }
}
