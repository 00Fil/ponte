package it.ponte

import android.content.Context
import android.os.Build
import java.util.UUID

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("ponte", Context.MODE_PRIVATE)

    val deviceId: String = sp.getString("deviceId", null)
        ?: UUID.randomUUID().toString().also { sp.edit().putString("deviceId", it).apply() }

    var name: String
        get() = sp.getString("name", null)?.ifBlank { null } ?: Build.MODEL
        set(v) = sp.edit().putString("name", v.trim()).apply()

    var hubUrl: String
        get() = sp.getString("hubUrl", "") ?: ""
        set(v) = sp.edit().putString("hubUrl", v.trim()).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()

    var lanEnabled: Boolean
        get() = sp.getBoolean("lan", true)
        set(v) = sp.edit().putBoolean("lan", v).apply()

    var screenshots: Boolean
        get() = sp.getBoolean("screenshots", true)
        set(v) = sp.edit().putBoolean("screenshots", v).apply()

    /** Configurazione iniziale completata. */
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    /** Ultima versione (versionCode) di cui l'utente ha visto la pagina "Passato alla versione". */
    var seenVersion: Int
        get() = sp.getInt("seenVersion", 0)
        set(v) = sp.edit().putInt("seenVersion", v).apply()

    var notifications: Boolean
        get() = sp.getBoolean("notifications", false)
        set(v) = sp.edit().putBoolean("notifications", v).apply()

    /** Ultimi indirizzi diretti noti di ogni dispositivo: servono quando il server non è raggiungibile. */
    var peers: Map<String, List<String>>
        get() = try {
            val o = org.json.JSONObject(sp.getString("peers", "{}") ?: "{}")
            o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } } }
        } catch (_: Exception) { emptyMap() }
        set(v) {
            val o = org.json.JSONObject()
            v.forEach { (k, l) -> o.put(k, org.json.JSONArray(l)) }
            sp.edit().putString("peers", o.toString()).apply()
        }

    /** Indirizzi Bluetooth delle cuffie marcate come tali. */
    var headphones: Set<String>
        get() = sp.getStringSet("headphones", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("headphones", v.toSet()).apply()

    /** Cuffie in cui far andare l'audio di questo dispositivo ("" = passaggio automatico spento). */
    var preferredHp: String
        get() = sp.getString("preferredHp", "") ?: ""
        set(v) = sp.edit().putString("preferredHp", v).apply()

    /** Quando qui parte la musica, ferma gli altri dispositivi (e viceversa). */
    var autoPause: Boolean
        get() = sp.getBoolean("autoPause", true)
        set(v) = sp.edit().putBoolean("autoPause", v).apply()

    /** Una sola riproduzione alla volta tra tutti i dispositivi: quando un audio parte, gli altri si fermano. */
    var singlePlay: Boolean
        get() = sp.getBoolean("singlePlay", true)
        set(v) = sp.edit().putBoolean("singlePlay", v).apply()

    /** Se le cuffie sono qui e un PC inizia a suonare, il suo audio arriva da solo nelle cuffie tramite Relay. */
    var autoRoute: Boolean
        get() = sp.getBoolean("autoRoute2", false)
        set(v) = sp.edit().putBoolean("autoRoute2", v).apply()

    /** Se le cuffie preferite non ci sono: true = muto, false = casse. */
    var fallbackMute: Boolean
        get() = sp.getBoolean("fallbackMute", false)
        set(v) = sp.edit().putBoolean("fallbackMute", v).apply()

    /** Minuti di inattività dopo i quali scollegare le cuffie (0 = mai). */
    var idleMinutes: Int
        get() = sp.getInt("idleMinutes", 10)
        set(v) = sp.edit().putInt("idleMinutes", v).apply()

    /** Importi nascosti nelle dashboard (Home e Finanze). */
    var geminiKey: String
        get() = sp.getString("geminiKey", "") ?: ""
        set(v) = sp.edit().putString("geminiKey", v.trim()).apply()

    var camId: String
        get() = sp.getString("camId", "") ?: ""
        set(v) = sp.edit().putString("camId", v.trim()).apply()
    var camUser: String
        get() = sp.getString("camUser", "admin") ?: "admin"
        set(v) = sp.edit().putString("camUser", v.trim()).apply()
    var camPwd: String
        get() = sp.getString("camPwd", "") ?: ""
        set(v) = sp.edit().putString("camPwd", v).apply()

    var camHidden: Set<Int>
        get() = (sp.getString("camHidden", "") ?: "").split(',').mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = sp.edit().putString("camHidden", v.joinToString(",")).apply()

    var hideMoney: Boolean
        get() = sp.getBoolean("hideMoney", false)
        set(v) = sp.edit().putBoolean("hideMoney", v).apply()

    /** Registra da solo i pagamenti che Revolut notifica. */
    var revolutNotif: Boolean
        get() = sp.getBoolean("revolutNotif", true)
        set(v) = sp.edit().putBoolean("revolutNotif", v).apply()

    /** Cartelle create in Condividi, e in quale cartella sta ogni elemento (fid -> nome). */
    var folders: List<String>
        get() = try { org.json.JSONArray(sp.getString("folders", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } } catch (_: Exception) { emptyList() }
        set(v) = sp.edit().putString("folders", org.json.JSONArray(v).toString()).apply()

    var fileFolders: Map<String, String>
        get() = try { org.json.JSONObject(sp.getString("fileFolders", "{}") ?: "{}").let { o -> o.keys().asSequence().associateWith { o.getString(it) } } } catch (_: Exception) { emptyMap() }
        set(v) = sp.edit().putString("fileFolders", org.json.JSONObject(v).toString()).apply()

    /** "wss://host/ws" a partire da quello che scrive l'utente (host, https://host, wss://host/ws...). */
    fun wsUrl(): String? {
        var u = hubUrl.trim().trimEnd('/')
        if (u.isEmpty()) return null
        u = when {
            u.startsWith("https://") -> "wss://" + u.removePrefix("https://")
            u.startsWith("http://") -> "ws://" + u.removePrefix("http://")
            u.startsWith("wss://") || u.startsWith("ws://") -> u
            else -> "wss://$u"
        }
        return if (u.endsWith("/ws")) u else "$u/ws"
    }

    fun httpBase(): String? = wsUrl()?.removeSuffix("/ws")?.replaceFirst("wss://", "https://")?.replaceFirst("ws://", "http://")
}
