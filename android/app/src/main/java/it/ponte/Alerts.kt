package it.ponte

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import android.app.Notification

/** Notifiche di Agenda e Studio (promemoria, fine pomodoro). */
object Alerts {
    fun notify(ctx: Context, kind: String, title: String, text: String, code: Int) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("piano", "Agenda e Studio", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        val open = PendingIntent.getActivity(ctx, code, ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.putExtra("tab", kind), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Notifica con la grafica dell'app: il corpo è un riquadro blu con la stella, non quello di sistema.
        val rv = android.widget.RemoteViews(ctx.packageName, R.layout.notif_plan).apply {
            setTextViewText(R.id.n_title, title)
            setTextViewText(R.id.n_text, text)
            setTextViewText(R.id.n_time, java.text.SimpleDateFormat("HH:mm", java.util.Locale.ITALY).format(java.util.Date()))
        }
        nm.notify(code, Notification.Builder(ctx, "piano").setSmallIcon(R.drawable.ic_ponte).setContentTitle(title).setContentText(text)
            .setStyle(Notification.DecoratedCustomViewStyle()).setCustomContentView(rv).setCustomBigContentView(rv).setColor(0xFF2F5FFF.toInt())
            .setAutoCancel(true).setContentIntent(open).setCategory(Notification.CATEGORY_REMINDER).build())
        @Suppress("DEPRECATION")
        (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 120, 180), -1))
    }
}

class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val kind = i.getStringExtra("kind").orEmpty()
        if (kind == "timer") { Ponte.ensure(ctx).plan.timerDone(); return }
        Alerts.notify(ctx, if (kind == "ex") "studio" else "agenda", i.getStringExtra("title").orEmpty(), i.getStringExtra("text").orEmpty(), i.getIntExtra("code", 9000))
    }
}
