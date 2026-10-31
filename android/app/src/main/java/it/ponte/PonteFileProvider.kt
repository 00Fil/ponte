package it.ponte

import android.net.Uri
import androidx.core.content.FileProvider

/**
 * Come FileProvider, ma i file di testo (HTML, JSON, CSS...) si dichiarano "binari". Con un tipo text/* le app, WhatsApp
 * compreso, incollano il CONTENUTO del file come testo invece di trattarlo come un file da allegare.
 */
class PonteFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? {
        val t = super.getType(uri) ?: return null
        return if (t.startsWith("text/") || t == "application/xhtml+xml" || t == "application/json" || t == "application/xml") "application/octet-stream" else t
    }
}
