package it.ponte

import android.graphics.Bitmap
import com.app.jagles.connect.JAConnectorV2
import com.juan.base.encryption.EncryptionUtil
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Telecamere Eseecloud dal cloud del produttore, col loro stesso motore nativo: nessun PC né VPN.
 * Stato: 0 in corso, 6 collegato, 10 password errata, 2/3/11/12 non raggiungibile, 9 scollegato.
 */
object Cams {
    val status = MutableStateFlow(-1)
    val error = MutableStateFlow("")
    /** Ultimo fotogramma per canale. */
    val frames = ConcurrentHashMap<Int, MutableStateFlow<Bitmap?>>()
    val channels = MutableStateFlow(8)
    val lastJson = MutableStateFlow("")
    /** Diagnostica: fotogrammi arrivati, misura e byte dell'ultimo. */
    val diag = MutableStateFlow("")
    @Volatile private var nFrames = 0
    /** Collegato almeno una volta: gli stati 13-15 sono eventi dei canali, non perdita del collegamento. */
    val online = MutableStateFlow(false)
    private var key = ""
    private var ok = false
    private val lastAt = ConcurrentHashMap<Int, Long>()
    private val open = ConcurrentHashMap<Int, Int>()   // canale -> flusso (0 principale, 1 secondario)
    /** Fotogrammi al secondo massimi per canale in griglia; a tutto schermo nessun limite. */
    @Volatile var focus = -1

    fun frame(ch: Int) = frames.getOrPut(ch) { MutableStateFlow(null) }

    private fun engine(): JAConnectorV2? = try {
        val c = JAConnectorV2.get()
        if (!ok) { ok = true; JAConnectorV2.sink = Sink }
        c
    } catch (e: Throwable) { error.value = "Motore non caricato: ${e.message}"; null }

    fun connect(id: String, user: String, pwd: String, count: Int = channels.value) {
        val c = engine() ?: return
        if (key.isNotEmpty() && key != id) disconnect()
        key = id
        status.value = 0
        val verify = try { EncryptionUtil.encryptVerify("${System.currentTimeMillis() / 1000}&$user&$pwd&") } catch (e: Throwable) { error.value = "Cifratura: ${e.message}"; return }
        try {
            val h = c.nativeCreateConnector(id, false)
            handle = h
            val r = c.nativeConnect(h, id, "", verify, count, 0, 0, 0, false)
            if (r != 0) error.value = "Collegamento rifiutato ($r)"
        } catch (e: Throwable) { error.value = "Collegamento: ${e.message}" }
    }

    @Volatile private var handle = 0L

    fun disconnect() {
        val c = engine() ?: return
        open.keys.toList().forEach { close(it) }
        if (handle != 0L) try { c.nativeDisconnect(handle, 0) } catch (_: Throwable) {}
        status.value = -1
    }

    /** Apre la diretta di un canale: [hd] = flusso principale. */
    fun open(ch: Int, hd: Boolean) {
        val c = engine() ?: return
        if (handle == 0L) return
        val want = if (hd) 0 else 1
        val cur = open[ch]
        if (cur == want) return
        if (cur != null) close(ch)
        open[ch] = want
        try { c.nativeOpenStream(handle, want, ch, ch, ch, false) } catch (e: Throwable) { error.value = "Diretta: ${e.message}" }
    }

    fun close(ch: Int) {
        val c = engine() ?: return
        val b = open.remove(ch) ?: return
        try { c.nativeCloseStream(handle, b, ch, ch) } catch (_: Throwable) {}
    }

    /** Comando JSON come quelli dell'app (es. luce: IRCutFilterMode). */
    fun send(json: String, ch: Int = 0) {
        val c = engine() ?: return
        if (handle == 0L) return
        val b = json.toByteArray()
        try { c.nativeSendData(handle, b, b.size, ch, 10, 0) } catch (_: Throwable) {}
    }

    fun light(ch: Int, mode: String, user: String, pwd: String) = send(
        """{"IPCam":{"ModeSetting":{"IRCutFilterMode":"$mode"},"ChannelManager":{"ChannelList":"$ch","Operation":"SetChannel"}},"Version":"2.0.0","Method":"set","Authorization":{"Verify":"","username":"$user","password":"$pwd"}}""", ch,
    )

    @Volatile private var rgbaInfo = ""

    /** Lettura della memoria del motore senza codice nativo nostro: Unsafe di sistema. */
    private object Native {
        private val unsafe: Any? = try { Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) } catch (e: Throwable) { rgbaInfo = "unsafe: ${e.javaClass.simpleName}"; null }
        private val bulk = try { unsafe?.javaClass?.getMethod("copyMemoryToPrimitiveArray", Long::class.javaPrimitiveType, Any::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType) } catch (_: Throwable) { null }
        private val getByte = try { unsafe?.javaClass?.getMethod("getByte", Long::class.javaPrimitiveType) } catch (_: Throwable) { null }
        private val base: Long = try { (unsafe?.javaClass?.getMethod("arrayBaseOffset", Class::class.java)?.invoke(unsafe, ByteArray::class.java) as Int).toLong() } catch (_: Throwable) { 0L }
        fun copy(addr: Long, n: Int): ByteArray? {
            val u = unsafe ?: return null
            val out = ByteArray(n)
            try {
                val b = bulk
                if (b != null) { b.invoke(u, addr, out, base, n.toLong()); rgbaInfo = "copia ok"; return out }
            } catch (e: Throwable) { rgbaInfo = "bulk: ${(e.cause ?: e).javaClass.simpleName}" }
            val g = getByte ?: return null
            return try { for (i in 0 until n) out[i] = g.invoke(u, addr + i) as Byte; rgbaInfo = "copia lenta"; out } catch (e: Throwable) { rgbaInfo = "getByte: ${(e.cause ?: e).javaClass.simpleName}"; null }
        }
    }

    private object Sink : JAConnectorV2.Sink {
        override fun status(key: String, st: Int, index: Int) {
            if (st < 12) { status.value = st; online.value = st == 6 }
            if (st == 10) error.value = "Password errata"
            diag.value = "stato $st canale $index · fotogrammi $nFrames"
        }
        override fun frame(key: String, w: Int, h: Int, data: ByteArray?, len: Int, channel: Int, ptr: Long) {
            nFrames++
            if (nFrames % 15 == 1) diag.value = "fotogrammi $nFrames · ${w}x$h · ${data?.size ?: -1} byte · len $len · can $channel · ptr ${if (ptr != 0L) "sì" else "no"} $rgbaInfo"
            if (w <= 0 || h <= 0) return
            if (data == null) {
                // il fotogramma è già decodificato in RGBA nella memoria del motore: si copia subito, prima che venga riusata
                if (ptr == 0L || len < w * h * 4 || channel != focus && System.currentTimeMillis() - (lastAt[channel] ?: 0L) < 120) return
                lastAt[channel] = System.currentTimeMillis()
                val bytes = Native.copy(ptr, w * h * 4) ?: return
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
                frame(channel).value = bmp
                return
            }
            val now = System.currentTimeMillis()
            if (channel != focus && now - (lastAt[channel] ?: 0L) < 120) return
            lastAt[channel] = now
            val f = frame(channel)
            try { f.value = i420(data, w, h, f.value) } catch (e: Throwable) { diag.value = "conversione: ${e.message}" }
        }
        override fun audio(key: String, data: ByteArray?) {}
        override fun vcon(key: String, json: String, index: Int) { lastJson.value = json }
        override fun command(code: Int, key: String, st: Int, index: Int) {}
        override fun stream(key: String, st: Int, channel: Int) {}
        override fun record(key: String, a: Int, b: Int, c: Int, d: Int, e: Int, f: Int) {}
    }

    /** YUV 4:2:0 planare -> bitmap; riusa la bitmap se ha la stessa misura. */
    private fun i420(d: ByteArray, w: Int, h: Int, reuse: Bitmap?): Bitmap? {
        val ys = w * h
        if (d.size < ys * 3 / 2) return reuse
        val px = IntArray(ys)
        val us = ys; val vs = ys + ys / 4; val cw = w / 2
        var i = 0
        for (y in 0 until h) {
            val cr = (y shr 1) * cw
            for (x in 0 until w) {
                val Y = (d[i].toInt() and 0xFF) - 16
                val U = (d[us + cr + (x shr 1)].toInt() and 0xFF) - 128
                val V = (d[vs + cr + (x shr 1)].toInt() and 0xFF) - 128
                val yy = 1192 * (if (Y < 0) 0 else Y)
                var r = (yy + 1634 * V) shr 10; var g = (yy - 833 * V - 400 * U) shr 10; var b = (yy + 2066 * U) shr 10
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                px[i++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }
}
