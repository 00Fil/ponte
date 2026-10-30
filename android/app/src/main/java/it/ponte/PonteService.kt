package it.ponte

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Tiene vivo il Router: appunti, screenshot, audio e rete locale funzionano anche con l'app chiusa. */
class PonteService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ponte in esecuzione", NotificationManager.IMPORTANCE_LOW))
        val send = PendingIntent.getActivity(
            this, 0, Intent(this, ClipSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_ponte)
            .setContentTitle("Ponte")
            .setContentText("Collegato ai tuoi dispositivi")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Invia appunti", send).build())
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        Ponte.ensure(this).start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    companion object {
        private const val CHANNEL = "ponte"
        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, PonteService::class.java))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs(context).token.isNotEmpty()) PonteService.start(context)
    }
}
