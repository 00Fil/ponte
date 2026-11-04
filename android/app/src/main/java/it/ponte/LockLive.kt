package it.ponte

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cosa mostrare fuori dall'app: pomodoro, audio, prossimo evento, To-do, ultimo avviso delle cam. */
private class Glance(
    val timerTitle: String?, val timerEnd: Long, val audioTitle: String?, val audioText: String, val audioId: String, val audioPlaying: Boolean,
    val eventTitle: String?, val eventTime: String, val todoCount: Int, val todos: List<String>, val camText: String?,
)

/**
 * Notifiche fisse visibili anche da bloccato e widget per la lock screen. Si aggiornano da sole quando cambia
 * il pomodoro, l'audio, l'agenda, le cose da fare o arriva un avviso delle cam.
 */
object LockLive {
    private const val N_TIMER = 8001
    private const val N_AUDIO = 8002
    private const val N_DAY = 8003
    private var started = false

    fun start(ctx: Context, r: Router) {
        if (started) return
        started = true
        val tick = flow { while (true) { emit(Unit); delay(60_000) } }
        r.scope.launch {
            combine(r.plan.state, r.media.media, Cams.alerts, tick) { p, m, a, _ -> Triple(p, m, a) }.conflate().collect { (p, m, a) ->
                try { render(ctx, r, p, m, a) } catch (_: Exception) {}
                delay(1_500)
            }
        }
    }

    private fun hm(t: Long) = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(t))

    private fun render(ctx: Context, r: Router, p: PlanState, m: Map<String, RemoteMedia>, alerts: List<Cams.Alert>) {
        val now = System.currentTimeMillis()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        Notify.channels(ctx)

        // pomodoro
        val t = p.timer
        val timerOn = t.running && t.endAt > now
        val timerTitle = if (timerOn) when (t.phase) { "short" -> "Pausa breve"; "long" -> "Pausa lunga"; else -> "Concentrazione" } else null
        if (timerOn) {
            val stop = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, LockActionReceiver::class.java).putExtra("a", "timer.stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(N_TIMER, base(ctx, R.drawable.ic_n_timer, timerTitle!!, t.subject, "studio", 1)
                .setUsesChronometer(true).setChronometerCountDown(true).setWhen(t.endAt).setShowWhen(true)
                .setTimeoutAfter(t.endAt - now + 3_000).setCategory(Notification.CATEGORY_PROGRESS)
                .addAction(Notification.Action.Builder(null, "Ferma", stop).build()).build())
        } else nm.cancel(N_TIMER)

        // audio: preferisco chi suona, poi chi ha qualcosa in pausa
        val pick = m.values.filter { it.playing }.maxByOrNull { it.at } ?: m.values.filter { it.title.isNotEmpty() }.maxByOrNull { it.at }
        val remote = pick != null && pick.id != r.deviceId && pick.playing
        if (remote) {
            fun act(code: Int, a: String, label: String) = Notification.Action.Builder(null, label, PendingIntent.getBroadcast(
                ctx, code, Intent(ctx, LockActionReceiver::class.java).putExtra("a", "media").putExtra("id", pick!!.id).putExtra("c", a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
            nm.notify(N_AUDIO, base(ctx, R.drawable.ic_n_audio, pick!!.title.ifEmpty { "In riproduzione" }, listOf(pick.artist, r.deviceName(pick.id)).filter { it.isNotEmpty() }.joinToString(" · "), null, 2)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(act(11, "prev", "Indietro")).addAction(act(12, "pause", "Pausa")).addAction(act(13, "next", "Avanti")).build())
        } else nm.cancel(N_AUDIO)

        // prossimo evento e cose da fare
        val ev = p.events.filter { (if (it.allDay) it.end else it.end.coerceAtLeast(it.start)) > now && it.start < now + 8 * 3_600_000L }.minByOrNull { it.start }
        val open = p.tasks.filter { !it.done }.sortedWith(compareBy({ if (it.due > 0) it.due else Long.MAX_VALUE }, { it.title }))
        val evTime = if (ev == null) "" else if (ev.allDay) "Oggi" else hm(ev.start)
        if (ev != null || open.isNotEmpty()) {
            val title = if (ev != null) "$evTime  ${ev.title}" else "${open.size} da fare"
            val text = if (ev != null && open.isNotEmpty()) "${open.size} da fare · ${open.first().title}" else open.take(1).joinToString { it.title }
            val b = base(ctx, R.drawable.ic_n_event, title, text, null, 3).setCategory(Notification.CATEGORY_EVENT)
            if (open.size > 1) b.setStyle(Notification.InboxStyle().also { s -> open.take(5).forEach { s.addLine(it.title) } })
            nm.notify(N_DAY, b.build())
        } else nm.cancel(N_DAY)

        val cam = alerts.maxByOrNull { it.time }?.takeIf { now / 1000 - it.time < 6 * 3600 }
        val g = Glance(
            timerTitle, t.endAt, pick?.title?.ifEmpty { null }, listOf(pick?.artist.orEmpty(), if (pick != null && pick.id != r.deviceId) r.deviceName(pick.id) else "").filter { it.isNotEmpty() }.joinToString(" · "),
            pick?.id.orEmpty(), pick?.playing == true, ev?.title, evTime, open.size, open.take(3).map { it.title },
            cam?.let { "${Cams.kind(it.type)} · cam ${it.channel + 1} · ${hm(it.time * 1000)}" },
        )
        LockWidget.update(ctx, g)
    }

    private fun base(ctx: Context, icon: Int, title: String, text: String, tab: String?, code: Int): Notification.Builder =
        Notification.Builder(ctx, Notify.LIVE).setSmallIcon(icon).setContentTitle(title).setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setColor(0xFF2F5FFF.toInt()).setContentIntent(Notify.open(ctx, 8100 + code, tab))
            .addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })

    internal fun glance(ctx: Context) {
        val r = Ponte.router ?: return
        render(ctx, r, r.plan.state.value, r.media.media.value, Cams.alerts.value)
    }

    internal fun open(ctx: Context): PendingIntent = Notify.open(ctx, 8200, null)
}

/** Tasti delle notifiche fisse. */
class LockActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val r = Ponte.ensure(ctx)
        when (i.getStringExtra("a")) {
            "timer.stop" -> r.plan.timerStop()
            "media" -> r.media.cmd(i.getStringExtra("id").orEmpty(), i.getStringExtra("c").orEmpty())
        }
    }
}

/** Widget per la lock screen (dove il telefono lo permette) e per la Home. */
class LockWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) {
        Ponte.ensure(ctx)
        LockLive.glance(ctx)
    }

    internal companion object {
        fun update(ctx: Context, g: Any) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, LockWidget::class.java))
            if (ids.isEmpty()) return
            val v = RemoteViews(ctx.packageName, R.layout.widget_lock)
            g as Glance
            fun row(id: Int, text: String?) { v.setViewVisibility(id, if (text.isNullOrEmpty()) View.GONE else View.VISIBLE); if (!text.isNullOrEmpty()) v.setTextViewText(id, text) }

            val timerOn = g.timerTitle != null
            v.setViewVisibility(R.id.w_timer, if (timerOn) View.VISIBLE else View.GONE)
            if (timerOn) {
                v.setTextViewText(R.id.w_timer_t, g.timerTitle)
                v.setChronometerCountDown(R.id.w_timer_c, true)
                v.setChronometer(R.id.w_timer_c, SystemClock.elapsedRealtime() + (g.timerEnd - System.currentTimeMillis()), null, true)
            }
            row(R.id.w_audio, g.audioTitle?.let { (if (g.audioPlaying) "" else "‖  ") + it + if (g.audioText.isNotEmpty()) "  ·  ${g.audioText}" else "" })
            row(R.id.w_event, g.eventTitle?.let { "${g.eventTime}  $it" })
            row(R.id.w_todo, if (g.todoCount > 0) "${g.todoCount} da fare · " + g.todos.joinToString(", ") else null)
            row(R.id.w_cam, g.camText)
            v.setViewVisibility(R.id.w_empty, if (!timerOn && g.audioTitle == null && g.eventTitle == null && g.todoCount == 0 && g.camText == null) View.VISIBLE else View.GONE)
            v.setOnClickPendingIntent(R.id.w_root, LockLive.open(ctx))
            ids.forEach { mgr.updateAppWidget(it, v) }
        }
    }
}
