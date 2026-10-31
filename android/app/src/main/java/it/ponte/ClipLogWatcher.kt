package it.ponte

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Appunti in tempo reale anche con l'app in background.
 *
 * Da Android 10 un'app senza il focus non riceve la notifica "appunti cambiati" e non li può leggere.
 * Però ogni volta che copi, il sistema scrive nel log di sistema che ha negato l'accesso a Ponte: quella riga
 * è il segnale. Appena arriva, una finestra invisibile da 1 pixel prende il focus per un istante e permette
 * di leggere gli appunti. Servono due permessi, entrambi concessi una volta sola:
 *  - "Mostra sopra altre app" (dalle impostazioni di Ponte)
 *  - READ_LOGS (con `adb shell pm grant it.ponte.app android.permission.READ_LOGS`, oppure root dalle impostazioni di Ponte)
 */
class ClipLogWatcher(private val ctx: Context, private val r: Router) {
    private val main = Handler(Looper.getMainLooper())
    private var last = 0L
    @Volatile private var proc: Process? = null
    @Volatile private var approvedRun = false

    fun hasLogPermission() =
        ctx.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    private fun ready() = hasLogPermission() && Settings.canDrawOverlays(ctx)

    fun start() {
        r.scope.launch {
            while (isActive) {
                if (ready() && proc?.isAlive != true) watch(foreground = false)
                delay(30_000)
            }
        }
    }

    /**
     * Da Android 13 il sistema chiede "Consentire l'accesso ai log?" solo se l'app è in primo piano:
     * avviato in background, logcat viene limitato in silenzio ai soli log di Ponte.
     * Per questo, ogni volta che apri Ponte, se la lettura non era partita in primo piano si riavvia.
     */
    fun onForeground() {
        if (!ready() || (approvedRun && proc?.isAlive == true)) return
        proc?.destroy()
        r.scope.launch { watch(foreground = true) }
    }

    private fun watch(foreground: Boolean) {
        try {
            val p = ProcessBuilder("logcat", "-T", "1", "-v", "brief", "-b", "main", "ClipboardService:E", "*:S")
                .redirectErrorStream(true).start()
            proc = p
            approvedRun = foreground
            val started = System.currentTimeMillis()
            p.inputStream.bufferedReader().useLines { lines ->
                for (l in lines) {
                    // La prima riga può essere vecchia (-T 1): la si ignora.
                    if (System.currentTimeMillis() - started < 1500) continue
                    if (l.contains("Denying clipboard access") && l.contains(ctx.packageName)) {
                        val now = System.currentTimeMillis()
                        if (now - last > 250) {
                            last = now
                            main.post { grabFocusAndRead() }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Una finestra trasparente e non toccabile, ma con il focus: per il sistema Ponte è l'app attiva. */
    private fun grabFocusAndRead() {
        if (!Settings.canDrawOverlays(ctx)) return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var done = false
        lateinit var view: View
        fun finish(read: Boolean) {
            if (done) return
            done = true
            try { wm.removeView(view) } catch (_: Exception) {}
            // Se la finestra non ha preso il focus, si ripiega su un'attività trasparente.
            if (!read) {
                try {
                    ctx.startActivity(
                        Intent(ctx, ClipSendActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                            .putExtra("silent", true),
                    )
                } catch (_: Exception) {}
            }
        }
        view = object : View(ctx) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (hasWindowFocus && !done) {
                    r.clipboard.readAndSendFocused()
                    finish(read = true)
                }
            }
        }
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            wm.addView(view, lp)
            main.postDelayed({ finish(read = false) }, 400)
        } catch (_: Exception) {
            finish(read = false)
        }
    }
}
