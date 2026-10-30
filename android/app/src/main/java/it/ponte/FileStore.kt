package it.ponte

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class FileEntry(
    val fid: String,
    val name: String,
    val kind: String,
    val mime: String,
    val size: Long,
    val ts: Long,
    val from: String,
    val path: String,
)

/** Archivio locale di screenshot e file, con invio e ricezione a blocchi. */
class FileStore(private val ctx: Context, private val r: Router) {
    private val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "Ponte").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val sp = ctx.getSharedPreferences("ponte-files", Context.MODE_PRIVATE)
    private val http by lazy { OkHttpClient() }

    val items = MutableStateFlow<List<FileEntry>>(emptyList())

    private class Incoming(val raf: RandomAccessFile, val part: File, val meta: FileEntry) {
        var received = 0L
        var ended = false
    }

    private val incoming = ConcurrentHashMap<String, Incoming>()
    private val orphans = ConcurrentHashMap<String, MutableList<JSONObject>>()

    init {
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
        load()
    }

    // ---- invio ----

    suspend fun sendUri(uri: Uri, name: String, mime: String, kind: String): Boolean {
        val e = importUri(uri, name, mime, kind) ?: return false
        transmit(e)
        return true
    }

    /** Copia il contenuto nell'archivio locale. Veloce: dopo questo il permesso sull'URI non serve più. */
    fun importUri(uri: Uri, name: String, mime: String, kind: String): FileEntry? {
        val input = ctx.contentResolver.openInputStream(uri) ?: return null
        val fid = "f" + UUID.randomUUID().toString().replace("-", "")
        val safe = safeName(name)
        val local = File(dir, "${fid}_$safe")
        var size = 0L
        input.use { i -> FileOutputStream(local).use { o -> size = i.copyTo(o) } }
        if (size > MAX_FILE) { local.delete(); return null }
        return FileEntry(fid, safe, kind, mime, size, System.currentTimeMillis(), r.deviceId, local.absolutePath).also { add(it) }
    }

    /** Invia a tutti i collegamenti un file già nell'archivio, a blocchi. */
    suspend fun transmit(e: FileEntry) {
        val fid = e.fid
        r.send(newMsg("file.begin", "fid" to fid, "name" to e.name, "kind" to e.kind, "mime" to e.mime, "size" to e.size))
        val buf = ByteArray(CHUNK_BYTES)
        var seq = 0
        File(e.path).inputStream().use { f ->
            while (true) {
                var n = 0
                while (n < buf.size) {
                    val k = f.read(buf, n, buf.size - n)
                    if (k < 0) break
                    n += k
                }
                if (n == 0) break
                r.send(newMsg("file.chunk", "fid" to fid, "seq" to seq++, "data" to Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)))
                while (r.maxQueued() > 2_000_000) delay(20)
                if (n < buf.size) break
            }
        }
        r.send(newMsg("file.end", "fid" to fid))
    }

    // ---- ricezione ----

    fun onMessage(m: JSONObject) {
        val fid = m.optString("fid")
        if (!FID.matches(fid)) return
        when (m.optString("t")) {
            "file.begin" -> begin(fid, m)
            "file.chunk", "file.end" -> {
                val inc = incoming[fid]
                if (inc == null) stash(fid, m) else apply(fid, inc, m)
            }
        }
    }

    private fun begin(fid: String, m: JSONObject) {
        if (items.value.any { it.fid == fid } || incoming.containsKey(fid)) return
        val size = m.optLong("size", -1)
        if (size < 0 || size > MAX_FILE) return
        val part = File(dir, "$fid.part")
        val meta = FileEntry(
            fid, safeName(m.optString("name")), m.optString("kind", "file"), m.optString("mime", "application/octet-stream"),
            size, m.optLong("ts", System.currentTimeMillis()), m.optString("from"), "",
        )
        val inc = Incoming(RandomAccessFile(part, "rw").apply { setLength(size) }, part, meta)
        incoming[fid] = inc
        orphans.remove(fid)?.forEach { apply(fid, inc, it) }
    }

    private fun stash(fid: String, m: JSONObject) {
        if (orphans.size >= 16 && !orphans.containsKey(fid)) return
        val l = orphans.getOrPut(fid) { mutableListOf() }
        synchronized(l) { if (l.size < 64) l.add(m) }
    }

    @Synchronized
    private fun apply(fid: String, inc: Incoming, m: JSONObject) {
        if (incoming[fid] !== inc) return
        if (m.optString("t") == "file.chunk") {
            val data = Base64.decode(m.optString("data"), Base64.DEFAULT)
            val off = m.optLong("seq", -1) * CHUNK_BYTES
            if (off < 0 || off + data.size > inc.meta.size) return drop(fid, inc)
            inc.raf.seek(off)
            inc.raf.write(data)
            inc.received += data.size
        } else {
            inc.ended = true
        }
        if (inc.ended && inc.received >= inc.meta.size) finish(fid, inc)
    }

    private fun drop(fid: String, inc: Incoming) {
        incoming.remove(fid)
        runCatching { inc.raf.close() }
        inc.part.delete()
    }

    private fun finish(fid: String, inc: Incoming) {
        incoming.remove(fid)
        inc.raf.close()
        val final = File(dir, "${fid}_${inc.meta.name}")
        if (inc.part.renameTo(final)) add(inc.meta.copy(path = final.absolutePath))
    }

    // ---- recupero da offline ----

    /** Scarica dal server ciò che è stato caricato mentre questo dispositivo era spento. */
    suspend fun catchUp() {
        val base = r.prefs.httpBase() ?: return
        val auth = "Bearer ${r.prefs.token}"
        try {
            val since = sp.getLong("since", System.currentTimeMillis() - 7L * 24 * 3600 * 1000)
            val list = http.newCall(Request.Builder().url("$base/api/files?since=$since").header("Authorization", auth).build())
                .execute().use { if (!it.isSuccessful) return else JSONArray(it.body!!.string()) }
            var maxTs = since
            for (i in 0 until list.length()) {
                val e = list.getJSONObject(i)
                maxTs = maxOf(maxTs, e.optLong("ts"))
                val fid = e.optString("fid")
                if (!FID.matches(fid) || items.value.any { it.fid == fid } || incoming.containsKey(fid)) continue
                val name = safeName(e.optString("name"))
                val final = File(dir, "${fid}_$name")
                http.newCall(Request.Builder().url("$base/api/files/$fid").header("Authorization", auth).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    resp.body!!.byteStream().use { inp -> FileOutputStream(final).use { inp.copyTo(it) } }
                    add(FileEntry(fid, name, e.optString("kind", "file"), e.optString("mime", "application/octet-stream"),
                        final.length(), e.optLong("ts"), e.optString("from"), final.absolutePath))
                }
            }
            sp.edit().putLong("since", maxTs).apply()
        } catch (_: Exception) {
        }
    }

    // ---- archivio ----

    fun uriFor(e: FileEntry): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(e.path))

    fun delete(e: FileEntry) {
        File(e.path).delete()
        items.value = items.value.filter { it.fid != e.fid }
        persist()
    }

    private fun add(e: FileEntry) {
        items.value = (items.value.filter { it.fid != e.fid } + e).sortedByDescending { it.ts }
        persist()
    }

    private fun load() {
        try {
            val a = JSONArray(indexFile.readText())
            items.value = (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull {
                val path = it.optString("path")
                if (!File(path).exists()) null
                else FileEntry(it.optString("fid"), it.optString("name"), it.optString("kind"), it.optString("mime"),
                    it.optLong("size"), it.optLong("ts"), it.optString("from"), path)
            }.sortedByDescending { it.ts }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun persist() {
        val a = JSONArray()
        for (e in items.value) a.put(JSONObject().put("fid", e.fid).put("name", e.name).put("kind", e.kind).put("mime", e.mime)
            .put("size", e.size).put("ts", e.ts).put("from", e.from).put("path", e.path))
        runCatching { indexFile.writeText(a.toString()) }
    }

    private fun safeName(n: String) =
        n.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\u0000-\\u001f<>:\"|?*]"), "_").trim().take(120).ifEmpty { "file" }

    companion object {
        const val MAX_FILE = 200L * 1024 * 1024
        private val FID = Regex("^[A-Za-z0-9_-]{8,64}$")
    }
}
