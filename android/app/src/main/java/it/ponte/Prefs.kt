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

    var notifications: Boolean
        get() = sp.getBoolean("notifications", false)
        set(v) = sp.edit().putBoolean("notifications", v).apply()

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
