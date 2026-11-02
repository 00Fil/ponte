package it.ponte.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Immagine scelta per ogni dispositivo (un PNG, anche un render 3D con sfondo trasparente). Resta su questo telefono, accanto all'id del dispositivo. */
object DevicePics {
    val rev = mutableIntStateOf(0)

    private fun file(ctx: Context, id: String) = File(File(ctx.filesDir, "devpics").apply { mkdirs() }, id.filter { it.isLetterOrDigit() || it == '-' } + ".png")

    /** Legge l'immagine scelta, la riduce (lato lungo 768) e la salva come PNG: la trasparenza si conserva. */
    suspend fun save(ctx: Context, id: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var s = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (s * 2) >= 768) s *= 2
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) } ?: return@withContext false
            file(ctx, id).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            rev.intValue++
            true
        } catch (_: Exception) { false }
    }

    fun remove(ctx: Context, id: String) { file(ctx, id).delete(); rev.intValue++ }

    @Composable
    fun rememberPic(id: String?): ImageBitmap? {
        val ctx = LocalContext.current
        val v = rev.intValue
        val pic by produceState<ImageBitmap?>(null, id, v) {
            value = if (id == null) null else withContext(Dispatchers.IO) {
                file(ctx, id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
            }
        }
        return pic
    }
}
