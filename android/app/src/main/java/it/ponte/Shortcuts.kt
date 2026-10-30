package it.ponte

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.launch

/**
 * Android non lascia leggere gli appunti a un'app in background. Questa attività trasparente, aperta dal riquadro
 * nelle impostazioni rapide o dal pulsante della notifica, passa un istante in primo piano, li legge e li invia.
 */
class ClipSendActivity : Activity() {
    private var done = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        PonteService.start(this)
        val r = Ponte.ensure(this)
        val ok = r.clipboard.sendCurrent()
        Toast.makeText(this, if (ok) "Appunti inviati" else "Negli appunti non c'è niente da inviare", Toast.LENGTH_SHORT).show()
        finish()
    }
}

class ClipTileService : TileService() {
    override fun onClick() {
        val intent = Intent(this, ClipSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

/** Voce "Invia con Ponte" nel menu Condividi di Android: testo come appunti, file e immagini nell'archivio. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PonteService.start(this)
        val r = Ponte.ensure(this)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        @Suppress("DEPRECATION")
        val stream: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
        when {
            stream != null -> {
                val name = contentResolver.query(stream, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "file"
                val mime = contentResolver.getType(stream) ?: "application/octet-stream"
                // L'attività resta aperta solo il tempo della copia locale: poi il permesso sull'URI decade.
                r.scope.launch {
                    val entry = r.files.importUri(stream, name, mime, "file")
                    runOnUiThread {
                        Toast.makeText(this@ShareActivity, if (entry != null) "File in invio" else "Impossibile leggere il file", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    if (entry != null) r.files.transmit(entry)
                }
                return
            }
            !text.isNullOrEmpty() -> {
                r.clipboard.send(text)
                Toast.makeText(this, "Testo inviato", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}
