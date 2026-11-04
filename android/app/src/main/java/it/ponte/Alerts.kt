package it.ponte

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow

/** Un avviso dentro l'app, a forma di notifica: dice cosa è successo e permette di agire. */
class Banner(val title: String, val text: String, val action: String? = null, val onAction: (() -> Unit)? = null, val id: Long = System.nanoTime())

/**
 * Tutte le notifiche di Relay passano da qui: canali separati (si silenziano uno per uno), icona piccola coerente con il tipo,
 * stile di sistema (testo lungo, ora, azioni) e nessun logo superfluo. Il servizio in primo piano sta in un canale minimo,
 * senza icona nella barra di stato.
 */
object Notify {
    const val REMINDERS = "reminders"
    const val TIMER = "timer"
    const val UPDATES = "updates"
    const val AUDIO = "audio"
    const val SERVICE = "relay_service"
    const val CAMS = "cams"
    const val LIVE = "live"

    /** Quando l'app è in primo piano gli avvisi si mostrano dentro di lei, non come notifica. */
    @Volatile var foreground = false
    val banner = MutableStateFlow<Banner?>(null)

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        fun ch(id: String, name: String, imp: Int, quiet: Boolean = false) = nm.createNotificationChannel(NotificationChannel(id, name, imp).apply {
            if (quiet) { setSound(null, null); enableVibration(false) } else if (imp >= NotificationManager.IMPORTANCE_HIGH) enableVibration(true)
            setShowBadge(false)
            if (id == LIVE || id == CAMS || id == TIMER) lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        ch(REMINDERS, "Promemoria", NotificationManager.IMPORTANCE_HIGH)
        ch(TIMER, "Pomodoro", NotificationManager.IMPORTANCE_HIGH)
        ch(UPDATES, "Aggiornamenti", NotificationManager.IMPORTANCE_DEFAULT, true)
        ch(AUDIO, "Audio e cuffie", NotificationManager.IMPORTANCE_DEFAULT, true)
        ch(SERVICE, "Relay in esecuzione", NotificationManager.IMPORTANCE_MIN, true)
        ch(CAMS, "Telecamere", NotificationManager.IMPORTANCE_HIGH)
        ch(LIVE, "Lock screen", NotificationManager.IMPORTANCE_LOW, true)
        // canali dei rilasci precedenti
        listOf("piano", "ponte", "aggiornamenti").forEach { nm.deleteNotificationChannel(it) }
    }

    fun open(ctx: Context, code: Int, tab: String?): PendingIntent =
        PendingIntent.getActivity(ctx, code, ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.putExtra("tab", tab), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun post(ctx: Context, channel: String, icon: Int, code: Int, title: String, text: String, tab: String? = null, category: String? = null, actions: List<Notification.Action> = emptyList(), timeout: Long = 0) {
        channels(ctx)
        val b = Notification.Builder(ctx, channel).setSmallIcon(icon).setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(open(ctx, code, tab)).setShowWhen(true)
            .setColor(0xFF2F5FFF.toInt())
        if (category != null) b.setCategory(category)
        if (timeout > 0) b.setTimeoutAfter(timeout)
        actions.forEach { b.addAction(it) }
        ctx.getSystemService(NotificationManager::class.java).notify(code, b.build())
    }

    /** Avviso di sistema se l'app è in background, banner interno se è aperta. */
    fun say(ctx: Context, title: String, text: String, action: String? = null, onAction: (() -> Unit)? = null, code: Int = 6001) {
        if (foreground) banner.value = Banner(title, text, action, onAction)
        else post(ctx, AUDIO, R.drawable.ic_n_audio, code, title, text, category = Notification.CATEGORY_STATUS, timeout = 20_000)
    }
}

/** Promemoria di Agenda e Studio. */
object Alerts {
    fun notify(ctx: Context, kind: String, title: String, text: String, code: Int) {
        val timer = kind == "studio"
        val snooze = PendingIntent.getBroadcast(
            ctx, code + 500_000, Intent(ctx, AlertReceiver::class.java).putExtra("kind", "snooze").putExtra("orig", kind).putExtra("title", title).putExtra("text", text).putExtra("code", code),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        Notify.post(
            ctx, if (timer) Notify.TIMER else Notify.REMINDERS, if (timer) R.drawable.ic_n_timer else R.drawable.ic_n_event, code, title, text, tab = kind,
            category = if (timer) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER,
            actions = if (timer) emptyList() else listOf(Notification.Action.Builder(null, "Tra 10 minuti", snooze).build()),
        )
    }
}

class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val kind = i.getStringExtra("kind").orEmpty()
        if (kind == "timer") { Ponte.ensure(ctx).plan.timerDone(); return }
        val code = i.getIntExtra("code", 9000)
        if (kind == "snooze") {
            ctx.getSystemService(NotificationManager::class.java).cancel(code)
            val again = PendingIntent.getBroadcast(
                ctx, code, Intent(ctx, AlertReceiver::class.java).putExtra("kind", i.getStringExtra("orig")).putExtra("title", i.getStringExtra("title")).putExtra("text", i.getStringExtra("text")).putExtra("code", code),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 10 * 60_000L, again)
            return
        }
        Alerts.notify(ctx, if (kind == "ex") "studio" else "agenda", i.getStringExtra("title").orEmpty(), i.getStringExtra("text").orEmpty(), code)
    }
}
