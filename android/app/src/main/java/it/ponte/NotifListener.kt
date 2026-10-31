package it.ponte

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Inoltra le notifiche di questo telefono ai dispositivi che le mostrano (il PC). Solo se l'utente l'ha attivato. */
class NotifListener : NotificationListenerService() {
    private val sent = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val recent = java.util.concurrent.ConcurrentHashMap<String, Long>()

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        sent.remove(sbn.key)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val r = Ponte.router ?: return
        if (!r.prefs.notifications || sbn.packageName == packageName || sbn.isOngoing) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || n.visibility == Notification.VISIBILITY_SECRET) return
        // Lettori musicali, download, servizi in corso: si aggiornano di continuo e non sono messaggi.
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return
        if (n.category in SKIP_CATEGORIES) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        // Aggiornamenti della stessa notifica: si inoltra solo se il contenuto è cambiato davvero,
        // e quelle che chiedono di avvisare una volta sola (FLAG_ONLY_ALERT_ONCE) solo la prima volta.
        val sig = "$title\u0000$text"
        val prev = sent[sbn.key]
        if (prev == sig || (prev != null && n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)) return
        // Stesso contenuto da due chiavi diverse (messaggio e riepilogo, o canali doppi): una sola volta ogni 10 secondi.
        val content = "${sbn.packageName}\u0000$sig"
        val now = System.currentTimeMillis()
        val last = recent[content]
        if (last != null && now - last < 10_000) return
        recent[content] = now
        if (recent.size > 200) recent.entries.removeAll { now - it.value > 10_000 }
        sent[sbn.key] = sig
        if (sent.size > 500) sent.keys.take(250).forEach { sent.remove(it) }
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            sbn.packageName
        }
        r.send(newMsg("notif", "key" to sbn.key, "app" to app, "title" to title.take(200), "text" to text.take(500)))
    }
}

private val SKIP_CATEGORIES = setOf(
    Notification.CATEGORY_TRANSPORT,
    Notification.CATEGORY_PROGRESS,
    Notification.CATEGORY_SERVICE,
    Notification.CATEGORY_STATUS,
    Notification.CATEGORY_NAVIGATION,
    Notification.CATEGORY_STOPWATCH,
    Notification.CATEGORY_WORKOUT,
    Notification.CATEGORY_LOCATION_SHARING,
)
