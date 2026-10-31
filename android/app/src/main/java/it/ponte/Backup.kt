package it.ponte

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Backup completo del telefono sul tuo server, pensato per sopravvivere allo sblocco del bootloader (che cancella tutto).
 *
 * Le parti delicate usano formati standard e collaudati, così il ripristino non dipende da Relay:
 *  - file: copia identica con SHA-256 controllato dal server mentre arrivano
 *  - contatti: vCard prodotti dal Contatti di Android (stesso formato di "Esporta")
 *  - SMS e registro chiamate: XML compatibile con "SMS Backup & Restore"
 *  - app: APK delle app non installate da Play Store + elenco di tutte le altre
 * I dati privati delle app (chat, database) NON si possono copiare senza root: per quelli servono il backup Google e quello delle app stesse.
 */
class Backup(private val ctx: Context, private val r: Router) {
    enum class Phase { Idle, Scanning, Running, Verifying, Restoring, Done, Failed }

    data class Storage(val total: Long, val free: Long, val reserve: Long, val deviceBytes: Long, val deviceFiles: Int)
    data class Plan(val files: Int, val bytes: Long, val toSend: Int, val toSendBytes: Long)
    data class Report(val ok: Boolean, val lines: List<String>, val at: Long = System.currentTimeMillis())
    data class State(
        val phase: Phase = Phase.Idle,
        val step: String = "",
        val done: Int = 0,
        val total: Int = 0,
        val bytes: Long = 0,
        val failed: List<String> = emptyList(),
        val storage: Storage? = null,
        val storageError: String = "",
        val plan: Plan? = null,
        val report: Report? = null,
        val lastBackup: Long = 0,
        val lastVerifiedOk: Long = 0,
    )

    val state = MutableStateFlow(State())
    private val sp = ctx.getSharedPreferences("backup", Context.MODE_PRIVATE)
    private var job: Job? = null
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(10, TimeUnit.MINUTES).writeTimeout(10, TimeUnit.MINUTES).build()

    init { state.value = state.value.copy(lastBackup = sp.getLong("lastBackup", 0), lastVerifiedOk = sp.getLong("lastVerifiedOk", 0)) }

    private fun set(f: (State) -> State) { state.value = f(state.value) }
    val busy: Boolean get() = state.value.phase.let { it == Phase.Scanning || it == Phase.Running || it == Phase.Verifying || it == Phase.Restoring }
    private val device: String get() = r.prefs.deviceId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)

    // ---------------------------------------------------------------- permessi

    fun hasAllFiles() = Environment.isExternalStorageManager()
    fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    val hasContacts get() = has(Manifest.permission.READ_CONTACTS)
    val hasSms get() = has(Manifest.permission.READ_SMS)
    val hasCalls get() = has(Manifest.permission.READ_CALL_LOG)

    // ---------------------------------------------------------------- server

    private fun base(): HttpUrl.Builder? = r.prefs.httpBase()?.toHttpUrlOrNull()?.newBuilder()?.addPathSegment("api")
    private fun url(vararg seg: String): HttpUrl? = base()?.also { b -> seg.forEach { s -> b.addPathSegment(s) } }?.build()
    private fun fileUrl(path: String): HttpUrl? = base()?.also { b ->
        b.addPathSegment("backup"); b.addPathSegment(device); path.split('/').forEach { b.addPathSegment(it) }
    }?.build()
    private fun req(u: HttpUrl) = Request.Builder().url(u).header("Authorization", "Bearer ${r.prefs.token}")

    suspend fun refreshStorage() {
        val u = url("storage")
        if (u == null) { set { it.copy(storageError = "Server non configurato") }; return }
        try {
            http.newCall(req(u).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("Il server ha risposto ${resp.code}")
                val j = JSONObject(resp.body!!.string())
                val mine = j.optJSONObject("backup")?.optJSONObject(device)
                set {
                    it.copy(
                        storage = Storage(j.getLong("total"), j.getLong("free"), j.optLong("reserve"), mine?.optLong("bytes") ?: 0, mine?.optInt("files") ?: 0),
                        storageError = "",
                    )
                }
            }
        } catch (e: Exception) {
            set { it.copy(storageError = e.message ?: e.javaClass.simpleName) }
        }
    }

    private fun manifest(): Map<String, Pair<Long, Long>> {
        val u = url("backup", device) ?: error("Server non configurato")
        http.newCall(req(u).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("Il server ha risposto ${resp.code}")
            val a = JSONObject(resp.body!!.string()).getJSONArray("files")
            val out = HashMap<String, Pair<Long, Long>>(a.length())
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); out[o.getString("p")] = o.getLong("s") to o.getLong("m") }
            return out
        }
    }

    // ---------------------------------------------------------------- file locali

    private val skipTop = setOf("Android", ".thumbnails", ".trash", "lost+found")
    private val restoreDir get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Relay-ripristino")

    private fun localFiles(): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        val skipRestore = restoreDir.absolutePath
        fun rec(dir: File, rel: String) {
            val list = dir.listFiles() ?: return
            for (f in list) {
                val p = if (rel.isEmpty()) f.name else "$rel/${f.name}"
                if (rel.isEmpty() && f.name in skipTop) continue
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue
                if (f.isDirectory) { if (f.absolutePath != skipRestore) rec(f, p) }
                else if (f.isFile && !f.name.endsWith(".part")) out += p to f
            }
        }
        rec(Environment.getExternalStorageDirectory(), "")
        return out
    }

    private fun same(server: Pair<Long, Long>?, f: File) = server != null && server.first == f.length() && kotlin.math.abs(server.second - f.lastModified()) < 2000

    /** Calcola quanto c'è da copiare e se sul server c'è posto. */
    fun prepare() {
        if (busy) return
        job = r.scope.launch {
            set { it.copy(phase = Phase.Scanning, step = "Conto i file…", plan = null, report = null) }
            try {
                refreshStorage()
                val local = if (hasAllFiles()) localFiles() else emptyList()
                val srv = try { manifest() } catch (_: Exception) { emptyMap() }
                var send = 0; var sendBytes = 0L
                for ((p, f) in local) if (!same(srv["files/$p"], f)) { send++; sendBytes += f.length() }
                val all = local.sumOf { pair -> pair.second.length() }
                set { st -> st.copy(phase = Phase.Idle, step = "", plan = Plan(local.size, all, send, sendBytes)) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                set { it.copy(phase = Phase.Failed, step = e.message ?: "Errore") }
            }
        }
    }

    // ---------------------------------------------------------------- invio

    private class Counting(val file: File, val digest: MessageDigest) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = file.length()
        override fun writeTo(sink: BufferedSink) {
            digest.reset()
            file.inputStream().use { ins ->
                val buf = ByteArray(256 * 1024)
                while (true) { val n = ins.read(buf); if (n < 0) break; digest.update(buf, 0, n); sink.write(buf, 0, n) }
            }
        }
    }

    private fun sha256(f: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(256 * 1024); while (true) { val n = i.read(b); if (n < 0) break; d.update(b, 0, n) } }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    /** Carica un file e confronta l'SHA-256 calcolato qui con quello calcolato dal server su ciò che ha ricevuto. */
    private suspend fun upload(path: String, f: File) {
        val u = fileUrl(path) ?: error("Server non configurato")
        var last: Exception? = null
        repeat(4) { attempt ->
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                val rq = req(u).header("x-mtime", f.lastModified().toString()).put(Counting(f, digest)).build()
                http.newCall(rq).execute().use { resp ->
                    if (resp.code == 507) throw NoSpace(resp.body?.string().orEmpty())
                    if (!resp.isSuccessful) error("server ${resp.code}")
                    val got = JSONObject(resp.body!!.string()).getString("sha256")
                    val mine = digest.digest().joinToString("") { "%02x".format(it) }
                    if (got != mine) error("controllo SHA-256 non coincide")
                }
                return
            } catch (e: NoSpace) { throw e } catch (e: CancellationException) { throw e } catch (e: Exception) {
                last = e; delay(1500L * (attempt + 1))
            }
        }
        throw last ?: IllegalStateException("errore")
    }
    private class NoSpace(m: String) : Exception(m)

    private suspend fun uploadText(path: String, text: String, scratch: File) {
        val f = File(scratch, path.substringAfterLast('/')); f.writeText(text); upload(path, f); f.delete()
    }

    // ---------------------------------------------------------------- esportazioni standard

    private fun esc(s: String?): String = (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")

    private fun exportContacts(out: File): Int {
        var n = 0
        out.bufferedWriter().use { w ->
            ctx.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.LOOKUP_KEY), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val key = c.getString(0) ?: continue
                    val u = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, key)
                    try {
                        ctx.contentResolver.openAssetFileDescriptor(u, "r")?.use { fd ->
                            fd.createInputStream().bufferedReader().use { w.write(it.readText().trimEnd() + "\r\n") }
                            n++
                        }
                    } catch (_: Exception) { }
                }
            }
        }
        return n
    }

    private fun exportSms(out: File): Int {
        var n = 0
        out.bufferedWriter().use { w ->
            w.write("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n<smses count=\"@@\">\n")
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.BODY, Telephony.Sms.READ, Telephony.Sms.DATE_SENT, Telephony.Sms.PROTOCOL, Telephony.Sms.SUBJECT),
                null, null, "date ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    w.write(
                        "<sms protocol=\"${c.getInt(6)}\" address=\"${esc(c.getString(0))}\" date=\"${c.getLong(1)}\" type=\"${c.getInt(2)}\" subject=\"${esc(c.getString(7) ?: "null")}\" " +
                            "body=\"${esc(c.getString(3))}\" toa=\"null\" sc_toa=\"null\" service_center=\"null\" read=\"${c.getInt(4)}\" status=\"-1\" locked=\"0\" " +
                            "date_sent=\"${c.getLong(5)}\" readable_date=\"\" contact_name=\"(Unknown)\" />\n",
                    )
                    n++
                }
            }
            w.write("</smses>\n")
        }
        // il conteggio va nell'intestazione, come fa SMS Backup & Restore
        out.writeText(out.readText().replaceFirst("@@", n.toString()))
        return n
    }

    private fun exportCalls(out: File): Int {
        var n = 0
        out.bufferedWriter().use { w ->
            w.write("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n<calls count=\"@@\">\n")
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DURATION, CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME),
                null, null, "date ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    w.write("<call number=\"${esc(c.getString(0))}\" duration=\"${c.getLong(1)}\" date=\"${c.getLong(2)}\" type=\"${c.getInt(3)}\" presentation=\"1\" readable_date=\"\" contact_name=\"${esc(c.getString(4) ?: "(Unknown)")}\" />\n")
                    n++
                }
            }
            w.write("</calls>\n")
        }
        out.writeText(out.readText().replaceFirst("@@", n.toString()))
        return n
    }

    private class AppInfo(val pkg: String, val label: String, val version: String, val installer: String?, val apks: List<File>)

    private fun installedApps(): List<AppInfo> {
        val pm = ctx.packageManager
        return pm.getInstalledApplications(0).filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != ctx.packageName }.map { a ->
            val installer = try {
                if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(a.packageName).installingPackageName else @Suppress("DEPRECATION") pm.getInstallerPackageName(a.packageName)
            } catch (_: Exception) { null }
            val ver = try { pm.getPackageInfo(a.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
            val apks = buildList {
                add(File(a.sourceDir)); a.splitSourceDirs?.forEach { add(File(it)) }
            }.filter { it.exists() }
            AppInfo(a.packageName, pm.getApplicationLabel(a).toString(), ver, installer, apks)
        }.sortedBy { it.label.lowercase() }
    }

    // ---------------------------------------------------------------- backup completo

    fun start() {
        if (busy) return
        val wl = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "relay:backup")
        job = r.scope.launch {
            wl.acquire(6 * 60 * 60 * 1000L)
            val failed = ArrayList<String>()
            val scratch = File(ctx.cacheDir, "backup").apply { deleteRecursively(); mkdirs() }
            try {
                set { it.copy(phase = Phase.Running, step = "Controllo il server…", done = 0, total = 0, bytes = 0, failed = emptyList(), report = null) }
                refreshStorage()
                val srv = manifest()

                // 1) esportazioni standard
                fun step(s: String) = set { it.copy(step = s) }
                if (hasContacts) { step("Contatti…"); val f = File(scratch, "contatti.vcf"); val n = exportContacts(f); if (n > 0) upload("_relay/contatti.vcf", f); f.delete() }
                if (hasSms) { step("SMS…"); val f = File(scratch, "sms.xml"); exportSms(f); upload("_relay/sms.xml", f); f.delete() }
                if (hasCalls) { step("Registro chiamate…"); val f = File(scratch, "chiamate.xml"); exportCalls(f); upload("_relay/chiamate.xml", f); f.delete() }

                // 2) app: elenco di tutte, APK solo di quelle non installate da Play Store
                step("App installate…")
                val apps = installedApps()
                val arr = JSONArray()
                for (a in apps) arr.put(JSONObject().put("pkg", a.pkg).put("label", a.label).put("version", a.version).put("installer", a.installer ?: ""))
                uploadText("_relay/app.json", arr.toString(1), scratch)
                val sideloaded = apps.filter { it.installer != "com.android.vending" }
                for ((i, a) in sideloaded.withIndex()) {
                    if (!isActive) return@launch
                    step("APK ${i + 1}/${sideloaded.size}: ${a.label}")
                    for (f in a.apks) {
                        val p = "_relay/apk/${a.pkg}/${f.name}"
                        val s = srv[p]
                        if (s == null || s.first != f.length()) {
                            try { upload(p, f) } catch (e: NoSpace) { throw e } catch (e: Exception) { failed += "${a.label}: ${e.message}" }
                        }
                    }
                }

                // 3) file
                if (hasAllFiles()) {
                    step("Elenco dei file…")
                    val todo = localFiles().filter { (p, f) -> !same(srv["files/$p"], f) }
                    set { it.copy(total = todo.size, done = 0) }
                    for ((p, f) in todo) {
                        if (!isActive) return@launch
                        set { it.copy(step = p.substringAfterLast('/')) }
                        try { upload("files/$p", f); set { it.copy(done = it.done + 1, bytes = it.bytes + f.length()) } }
                        catch (e: NoSpace) { throw e }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { failed += "$p: ${e.message}"; set { it.copy(done = it.done + 1, failed = failed.toList()) } }
                    }
                }
                sp.edit().putLong("lastBackup", System.currentTimeMillis()).apply()
                set { it.copy(phase = Phase.Done, step = "Copia finita. Ora premi Verifica.", failed = failed.toList(), lastBackup = System.currentTimeMillis()) }
                refreshStorage()
            } catch (e: CancellationException) {
                set { it.copy(phase = Phase.Idle, step = "Interrotto", failed = failed.toList()) }
                throw e
            } catch (e: NoSpace) {
                set { it.copy(phase = Phase.Failed, step = "Spazio insufficiente sul server", failed = failed.toList()) }
            } catch (e: Exception) {
                set { it.copy(phase = Phase.Failed, step = e.message ?: e.javaClass.simpleName, failed = failed.toList()) }
            } finally {
                scratch.deleteRecursively(); if (wl.isHeld) wl.release()
            }
        }
    }

    fun cancel() { job?.cancel() }

    // ---------------------------------------------------------------- verifica

    /** Confronta ogni file del telefono con quello sul server (esistenza, dimensione, data) e controlla le esportazioni. */
    fun verify() {
        if (busy) return
        job = r.scope.launch {
            set { it.copy(phase = Phase.Verifying, step = "Leggo l'elenco dal server…", report = null) }
            try {
                val srv = manifest()
                val lines = ArrayList<String>()
                var bad = 0
                val local = if (hasAllFiles()) localFiles() else emptyList()
                if (!hasAllFiles()) { lines += "Manca il permesso «Tutti i file»: i file non sono stati controllati"; bad++ }
                var missing = 0; var diff = 0
                for ((p, f) in local) {
                    val s = srv["files/$p"]
                    if (s == null) missing++ else if (!same(s, f)) diff++
                }
                lines += "File: ${local.size - missing - diff} su ${local.size} identici sul server"
                if (missing > 0) { lines += "$missing file mancanti sul server"; bad++ }
                if (diff > 0) { lines += "$diff file diversi (modificati dopo il backup?)"; bad++ }
                fun need(p: String, label: String, granted: Boolean) {
                    if (!granted) { lines += "$label: permesso non concesso, non copiato"; bad++ }
                    else if (srv[p] == null) { lines += "$label: non trovato sul server"; bad++ } else lines += "$label: ok (${srv[p]!!.first / 1024} KB)"
                }
                need("_relay/contatti.vcf", "Contatti", hasContacts)
                need("_relay/sms.xml", "SMS", hasSms)
                need("_relay/chiamate.xml", "Chiamate", hasCalls)
                need("_relay/app.json", "Elenco app", true)
                val apks = srv.keys.count { it.startsWith("_relay/apk/") }
                lines += "APK copiati: $apks"
                val ok = bad == 0
                if (ok) sp.edit().putLong("lastVerifiedOk", System.currentTimeMillis()).apply()
                set { it.copy(phase = Phase.Done, step = if (ok) "Verifica superata" else "Verifica non superata", report = Report(ok, lines), lastVerifiedOk = if (ok) System.currentTimeMillis() else it.lastVerifiedOk) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                set { it.copy(phase = Phase.Failed, step = e.message ?: "Errore") }
            }
        }
    }

    // ---------------------------------------------------------------- ripristino

    private fun download(path: String, to: File): Boolean {
        val u = fileUrl(path) ?: return false
        to.parentFile?.mkdirs()
        val part = File(to.path + ".part")
        http.newCall(req(u).build()).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val mt = resp.header("x-mtime")?.toLongOrNull()
            part.outputStream().use { o -> resp.body!!.byteStream().copyTo(o, 256 * 1024) }
            if (!part.renameTo(to)) { part.copyTo(to, overwrite = true); part.delete() }
            if (mt != null) to.setLastModified(mt)
        }
        return true
    }

    /** Dopo la reinstallazione: rimette i file al loro posto e prepara in Download/Relay-ripristino il resto. */
    fun restore() {
        if (busy) return
        val wl = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "relay:restore")
        job = r.scope.launch {
            wl.acquire(6 * 60 * 60 * 1000L)
            val failed = ArrayList<String>()
            try {
                set { it.copy(phase = Phase.Restoring, step = "Leggo l'elenco dal server…", done = 0, total = 0, bytes = 0, failed = emptyList(), report = null) }
                val srv = manifest()
                val root = Environment.getExternalStorageDirectory()
                val out = restoreDir.apply { mkdirs() }
                // standard: pronti per le app che li importano
                for (n in listOf("contatti.vcf", "sms.xml", "chiamate.xml", "app.json")) {
                    if (srv["_relay/$n"] != null) { set { it.copy(step = n) }; if (!download("_relay/$n", File(out, n))) failed += n }
                }
                // elenco leggibile delle app da Play Store
                try {
                    val f = File(out, "app.json")
                    if (f.exists()) {
                        val a = JSONArray(f.readText())
                        val txt = buildString {
                            appendLine("App da reinstallare da Play Store (le altre sono in apk/):")
                            for (i in 0 until a.length()) { val o = a.getJSONObject(i); appendLine("- ${o.getString("label")}  (${o.getString("pkg")})" + if (o.getString("installer") == "com.android.vending") "" else "  [APK in apk/]") }
                        }
                        File(out, "app-da-reinstallare.txt").writeText(txt)
                    }
                } catch (_: Exception) { }
                for (p in srv.keys.filter { it.startsWith("_relay/apk/") }) {
                    if (!isActive) return@launch
                    set { it.copy(step = p.substringAfterLast('/')) }
                    if (!download(p, File(out, "apk/" + p.removePrefix("_relay/apk/")))) failed += p
                }
                // file
                val todo = srv.filterKeys { it.startsWith("files/") }.toList()
                set { it.copy(total = todo.size, done = 0) }
                for ((p, s) in todo) {
                    if (!isActive) return@launch
                    val rel = p.removePrefix("files/")
                    val dest = File(root, rel)
                    set { it.copy(step = rel.substringAfterLast('/')) }
                    if (dest.exists() && dest.length() == s.first) { set { it.copy(done = it.done + 1) }; continue }
                    try { if (!download(p, dest)) failed += rel; set { it.copy(done = it.done + 1, bytes = it.bytes + s.first, failed = failed.toList()) } }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failed += "$rel: ${e.message}"; set { it.copy(done = it.done + 1, failed = failed.toList()) } }
                }
                set { it.copy(phase = Phase.Done, step = "Ripristino finito", failed = failed.toList()) }
            } catch (e: CancellationException) {
                set { it.copy(phase = Phase.Idle, step = "Interrotto", failed = failed.toList()) }
                throw e
            } catch (e: Exception) {
                set { it.copy(phase = Phase.Failed, step = e.message ?: e.javaClass.simpleName, failed = failed.toList()) }
            } finally { if (wl.isHeld) wl.release() }
        }
    }

    fun restoreFolder(): String = restoreDir.absolutePath
}
