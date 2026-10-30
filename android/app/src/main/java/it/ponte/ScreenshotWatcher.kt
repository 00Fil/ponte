package it.ponte

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Manda agli altri dispositivi ogni nuovo screenshot comparso nella galleria. Serve il permesso di lettura delle immagini. */
class ScreenshotWatcher(private val ctx: Context, private val r: Router) {
    private val collection: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val running = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    @Volatile private var lastId = -1L

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            r.scope.launch { check() }
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        lastId = try { maxId() } catch (_: SecurityException) { running.set(false); return }
        ctx.contentResolver.registerContentObserver(collection, true, observer)
    }

    fun stop() {
        if (running.compareAndSet(true, false)) ctx.contentResolver.unregisterContentObserver(observer)
    }

    private fun maxId(): Long =
        ctx.contentResolver.query(collection, arrayOf(MediaStore.Images.Media._ID), null, null, "${MediaStore.Images.Media._ID} DESC")
            ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L

    private suspend fun check() {
        if (!busy.compareAndSet(false, true)) return
        try {
            val proj = arrayOf(
                MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.IS_PENDING,
            )
            val rows = mutableListOf<Triple<Long, String, String>>()
            var stop = lastId
            ctx.contentResolver.query(collection, proj, "${MediaStore.Images.Media._ID} > ?", arrayOf(lastId.toString()), "${MediaStore.Images.Media._ID} ASC")
                ?.use { c ->
                    while (c.moveToNext()) {
                        if (c.getInt(4) != 0) break // ancora in scrittura: ci si riprova alla prossima notifica
                        stop = c.getLong(0)
                        val path = c.getString(3) ?: ""
                        if (path.contains("screenshot", ignoreCase = true)) rows += Triple(c.getLong(0), c.getString(1) ?: "screenshot.png", c.getString(2) ?: "image/png")
                    }
                }
            lastId = stop
            for ((id, name, mime) in rows) {
                val uri = ContentUris.withAppendedId(collection, id)
                r.files.importUri(uri, name, mime, "screenshot")?.let { r.files.transmit(it) }
            }
        } catch (_: SecurityException) {
        } finally {
            busy.set(false)
        }
    }
}
